package com.dierks.homecraft.games.event;

import java.util.ArrayList;
import java.util.List;

/**
 * A Race Night's timeline as a pure state machine (EVENTS-DROPPER-SPEC §A.2, §A.4, §A.9; owner
 * decision D3 for the warm-up): {@link #step} takes where the night is, its timings and the facts
 * of the moment (the clock, who joined, who is seated, who is still racing), and returns where it
 * is now and what to do. No Bukkit, no clock of its own, so every transition is tested with a fake
 * clock; {@code NightRunner} does the actions.
 *
 * <pre>
 * SCHEDULED --joinAt--> OPEN --T-15s--> [WARMUP] --> GRID --Go--> RACING --> BREAK --> GRID ... --> SETTLING --> DONE
 *                        |                 |           |                                              (pay loop)
 *                        +-- too few, admin cancel, reload, stop, crash, race_night or trials off --> CALLED_OFF
 * </pre>
 *
 * <ul>
 *   <li><b>SCHEDULED</b>: the heads-up in chat at T − {@code announce_minutes}; the window opens at
 *       {@code joinAt}.</li>
 *   <li><b>OPEN</b>: joining. At T − 2 min the last call and the track is reserved (no new solo
 *       runs); at T − 1 min solo runs still on it end. At T − 15 s racers are taken to the track, or
 *       the night is called off when fewer than {@code min_racers} joined.</li>
 *   <li><b>WARMUP</b> (D3, {@code warmup_seconds} &gt; 0): free laps, never timed. Anyone not seated yet
 *       is tried again every second. It ends when the window runs out or every seated racer tapped
 *       Ready; then everyone goes to the grid.</li>
 *   <li><b>GRID</b>: boats held on their spots for the 5-second countdown (race 1 without a warm-up:
 *       until T, retrying the unseated until T − 3 s). At Go, race 1 needs {@code min_racers}
 *       seated or the night is called off; the unseated are DNS for that race.</li>
 *   <li><b>RACING</b> ends when everyone is in, left or voided; {@code finish_window_seconds} after
 *       the first finisher; or {@code max_race_minutes} after Go.</li>
 *   <li><b>BREAK</b> ({@code break_seconds}): the standings so far; then the next race's grid, or the
 *       end when fewer than 2 racers are left.</li>
 *   <li><b>SETTLING</b>: places, prizes, the night's board; the runner marks it DONE when paid.</li>
 * </ul>
 */
public final class EventMachine {

    /** The track is reserved and the last call goes out this long before the start. */
    public static final long RESERVE_MS = 2 * 60_000L;
    /** Solo runs still on the track end this long before the start. */
    public static final long SOLO_END_MS = 60_000L;
    /** Racers are taken to the track this long before the start. */
    public static final long GRID_LEAD_MS = 15_000L;
    /** Racers who couldn't be seated are tried again until this long before Go. */
    public static final long RETRY_UNTIL_MS = 3_000L;
    /** The countdown on the grid: "Race 2 of 3", 3, 2, 1, Go! */
    public static final long COUNTDOWN_MS = 5_000L;
    /** A night that was OPEN when the server stopped resumes only if its start is at least this far off. */
    public static final long RESUME_MIN_MS = 2 * 60_000L;
    /** The hub shows a night's results this long after it ends. */
    public static final long RESULTS_MS = 30 * 60_000L;
    /** Fewer racers than this left after a race ends the night early. */
    public static final int KEEP_GOING = 2;

    private EventMachine() {
    }

    /** Where a night is. */
    public enum Phase {
        SCHEDULED, OPEN, WARMUP, GRID, RACING, BREAK, SETTLING, DONE, CALLED_OFF;

        /** The {@code game_events.state} it is stored as. */
        public String stored() {
            return switch (this) {
                case SCHEDULED, OPEN -> "OPEN";
                case WARMUP, GRID, RACING, BREAK -> "RUNNING";
                case SETTLING -> "SETTLING";
                case DONE -> "DONE";
                case CALLED_OFF -> "CALLED_OFF";
            };
        }

        /** Whether racers are at the track (a stop calls the night off and settles what was raced). */
        public boolean running() {
            return this == WARMUP || this == GRID || this == RACING || this == BREAK;
        }

        /** Whether the night is over. */
        public boolean over() {
            return this == DONE || this == CALLED_OFF;
        }
    }

    /** What the runner does. */
    public enum Do {
        /** The chat heads-up (T − announce_minutes). */
        HEADS_UP,
        /** Write the night's row; the join window, bossbar and hub sign say JOIN NOW. */
        OPEN,
        /** "Last call" in chat, and the track is reserved. */
        LAST_CALL,
        /** End solo runs still on the track; warn joined racers busy elsewhere. */
        END_SOLO_RUNS,
        /** Take every joined, free racer to the track (onto the grid, or into the warm-up). */
        SEAT,
        /** Try the racers who couldn't be seated again. */
        RETRY_SEATS,
        /** Everyone to their grid spot for race {@code race}; the countdown ends at {@code at}. */
        GRID,
        /** Race {@code race} goes: the shared release tick. */
        GO,
        /** Race {@code race} is over: park anyone still on the track, score it, store it. */
        END_RACE,
        /** Places, prizes, the night's board, everyone home. */
        SETTLE,
        /** Call the night off ({@code why}); settle what was raced. */
        CALL_OFF
    }

    /** One thing to do. */
    public record Action(Do what, int race, long at, String why) {

        static Action of(Do what) {
            return new Action(what, 0, 0, null);
        }
    }

    /**
     * A night's timings.
     *
     * @param announceMs the heads-up this long before the start (0 = none)
     */
    public record Timing(long joinAt, long startsAt, long announceMs, int races, int minRacers, long warmupMs,
                         long finishWindowMs, long maxRaceMs, long breakMs) {

        /** A plan's timings. */
        public static Timing of(EventPlan plan, int announceMinutes) {
            NightRules r = plan.rules();
            return new Timing(plan.joinAt(), plan.startsAt(), announceMinutes * 60_000L, r.races(), r.minRacers(),
                    r.warmupSeconds() * 1000L, r.finishWindowSeconds() * 1000L, r.maxRaceMinutes() * 60_000L,
                    r.breakSeconds() * 1000L);
        }
    }

    /**
     * Where a night is.
     *
     * @param race       the race on now or next (1-based; 0 before race 1)
     * @param since      when this phase began
     * @param goAt       the current race's Go (epoch ms)
     * @param warmupEnds when the warm-up window ends
     * @param flags      what has been done once ({@link #HEADS_UP_DONE}...)
     */
    public record State(Phase phase, int race, long since, long goAt, long warmupEnds, int flags) {

        /** A night not open yet. */
        public static State scheduled() {
            return new State(Phase.SCHEDULED, 0, 0, 0, 0, 0);
        }

        /** A night open for joining (a new admin night, or one resumed after a restart). */
        public static State open(long now) {
            return new State(Phase.OPEN, 0, now, 0, 0, HEADS_UP_DONE);
        }

        boolean has(int flag) {
            return (flags & flag) != 0;
        }

        State with(int flag) {
            return new State(phase, race, since, goAt, warmupEnds, flags | flag);
        }

        State to(Phase p, long now) {
            return new State(p, race, now, goAt, warmupEnds, flags);
        }

        /** The same state, over (the runner's SETTLE or CALL_OFF finished). */
        public State ended(Phase p, long now) {
            return new State(p, race, now, goAt, warmupEnds, flags);
        }
    }

    public static final int HEADS_UP_DONE = 1;
    public static final int LAST_CALL_DONE = 2;
    public static final int SOLO_DONE = 4;

    /**
     * The facts of the moment.
     *
     * @param joined        racers on the list (status IN)
     * @param seated        racers at the track now
     * @param racing        seated racers still on the track this race (not finished, left or voided)
     * @param firstFinishAt when this race's first racer crossed the line, or -1
     * @param allReady      every seated racer tapped Ready in the warm-up
     */
    public record Facts(long now, int joined, int seated, int racing, long firstFinishAt, boolean allReady) {
    }

    /** Where the night is after a step, and what to do, in order. */
    public record Step(State state, List<Action> actions) {
    }

    /** One step. */
    public static Step step(State s, Timing t, Facts f) {
        List<Action> out = new ArrayList<>();
        long now = f.now();
        State n = s;
        switch (s.phase()) {
            case SCHEDULED -> {
                if (t.announceMs() > 0 && !n.has(HEADS_UP_DONE) && now >= t.startsAt() - t.announceMs()
                        && now < t.joinAt()) {
                    out.add(Action.of(Do.HEADS_UP));
                    n = n.with(HEADS_UP_DONE);
                }
                if (now >= t.joinAt()) {
                    out.add(Action.of(Do.OPEN));
                    n = n.with(HEADS_UP_DONE).to(Phase.OPEN, now);
                    Step more = step(n, t, f);
                    out.addAll(more.actions());
                    n = more.state();
                }
            }
            case OPEN -> {
                if (!n.has(LAST_CALL_DONE) && now >= t.startsAt() - RESERVE_MS) {
                    out.add(Action.of(Do.LAST_CALL));
                    n = n.with(LAST_CALL_DONE);
                }
                if (!n.has(SOLO_DONE) && now >= t.startsAt() - SOLO_END_MS) {
                    out.add(Action.of(Do.END_SOLO_RUNS));
                    n = n.with(SOLO_DONE);
                }
                if (now >= t.startsAt() - GRID_LEAD_MS) {
                    if (f.joined() < t.minRacers()) {
                        out.add(new Action(Do.CALL_OFF, 0, now, "too few racers"));
                        n = n.to(Phase.CALLED_OFF, now);
                    } else if (t.warmupMs() > 0) {
                        out.add(Action.of(Do.SEAT));
                        n = new State(Phase.WARMUP, 1, now, 0, now + t.warmupMs(), n.flags());
                    } else {
                        out.add(Action.of(Do.SEAT));
                        long go = Math.max(t.startsAt(), now + RETRY_UNTIL_MS);
                        n = new State(Phase.GRID, 1, now, go, 0, n.flags());
                    }
                }
            }
            case WARMUP -> {
                if (now >= s.warmupEnds() || (f.allReady() && f.seated() >= 1)) {
                    long go = now + COUNTDOWN_MS;
                    out.add(new Action(Do.GRID, 1, go, null));
                    n = new State(Phase.GRID, 1, now, go, s.warmupEnds(), n.flags());
                } else {
                    out.add(Action.of(Do.RETRY_SEATS));
                }
            }
            case GRID -> {
                if (now >= s.goAt()) {
                    if (s.race() <= 1 && f.seated() < t.minRacers()) {
                        out.add(new Action(Do.CALL_OFF, s.race(), now, "too few racers at the start"));
                        n = n.to(Phase.CALLED_OFF, now);
                    } else if (f.seated() < 1) {
                        out.add(new Action(Do.SETTLE, s.race(), now, "nobody left to race"));
                        n = n.to(Phase.SETTLING, now);
                    } else {
                        out.add(new Action(Do.GO, s.race(), s.goAt(), null));
                        n = n.to(Phase.RACING, now);
                    }
                } else if (s.race() <= 1 && t.warmupMs() <= 0 && now < s.goAt() - RETRY_UNTIL_MS) {
                    out.add(Action.of(Do.RETRY_SEATS));
                }
            }
            case RACING -> {
                boolean allIn = f.racing() <= 0;
                boolean window = f.firstFinishAt() >= 0 && now >= f.firstFinishAt() + t.finishWindowMs();
                boolean tooLong = now >= s.goAt() + t.maxRaceMs();
                if (allIn || window || tooLong) {
                    out.add(new Action(Do.END_RACE, s.race(), now, allIn ? "everyone is in"
                            : window ? "the finish window closed" : "the race took too long"));
                    if (s.race() >= t.races()) {
                        out.add(new Action(Do.SETTLE, s.race(), now, null));
                        n = n.to(Phase.SETTLING, now);
                    } else {
                        n = n.to(Phase.BREAK, now);
                    }
                }
            }
            case BREAK -> {
                if (now >= s.since() + t.breakMs()) {
                    if (f.joined() < KEEP_GOING) {
                        out.add(new Action(Do.SETTLE, s.race(), now, "not enough racers left"));
                        n = n.to(Phase.SETTLING, now);
                    } else {
                        long go = now + COUNTDOWN_MS;
                        out.add(new Action(Do.GRID, s.race() + 1, go, null));
                        n = new State(Phase.GRID, s.race() + 1, now, go, s.warmupEnds(), n.flags());
                    }
                }
            }
            case SETTLING, DONE, CALLED_OFF -> {
                // the runner finishes SETTLING; an ended night does nothing
            }
        }
        return new Step(n, out);
    }

    // ---- stops and restarts (§A.9) --------------------------------------------------------------

    /** What happens to a night stored in {@code state} when the server comes back. */
    public enum Boot {
        /** Nothing to do (over, or never opened). */
        NOTHING,
        /** OPEN, and its start is still at least {@link #RESUME_MIN_MS} away: it carries on with its sign-ups. */
        RESUME,
        /** Called off with nothing raced (a queued notice to entrants). */
        CALL_OFF,
        /** Called off after {@code races_done} races: those races' points stand, prizes owed if it held a slot. */
        CALL_OFF_SETTLE,
        /** It was paying: finish the unpaid rows (the refs stop a double pay), then DONE. */
        FINISH_PAYING
    }

    /** What a boot does with a night stored in {@code state} ({@code game_events.state}). */
    public static Boot boot(String state, long startsAt, int racesDone, long now) {
        if (state == null) {
            return Boot.NOTHING;
        }
        return switch (state) {
            case "OPEN" -> startsAt - now >= RESUME_MIN_MS ? Boot.RESUME : Boot.CALL_OFF;
            case "RUNNING" -> racesDone >= 1 ? Boot.CALL_OFF_SETTLE : Boot.CALL_OFF;
            case "SETTLING" -> Boot.FINISH_PAYING;
            default -> Boot.NOTHING;
        };
    }

    /**
     * What a reload, a stop or the game switching off does to a night in {@code phase} with
     * {@code racesDone} races stored: the same as a boot, at once.
     */
    public static Boot stop(Phase phase, int racesDone) {
        if (phase == null || phase.over() || phase == Phase.SCHEDULED) {
            return Boot.NOTHING;
        }
        if (phase == Phase.SETTLING) {
            return Boot.FINISH_PAYING;
        }
        if (phase == Phase.OPEN) {
            return Boot.CALL_OFF;
        }
        return racesDone >= 1 ? Boot.CALL_OFF_SETTLE : Boot.CALL_OFF;
    }
}
