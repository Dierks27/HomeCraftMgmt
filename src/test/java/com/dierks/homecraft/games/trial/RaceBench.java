package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.EndReason;
import com.dierks.homecraft.games.GameProgress;
import com.dierks.homecraft.games.Refusal;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Race mode on a test bench, for the integration tests that run a whole Race Night and a whole party
 * race with no server (EV integration, item 2d).
 *
 * <p><b>Real:</b> every racer's state machine ({@link RaceRun}: the shared warm-up, the grid hold,
 * the one go instant, the line, park, re-grid and home); {@link RaceMode}'s {@code finish},
 * {@code park}, {@code endRace}, {@code left}, its holds and its guarded calls into the link; Time
 * Trials' {@code finish} (a warm-up lap to {@link Warmups#lap}, a race's line to race mode) and
 * {@code settleCounted} (the course boards, the rewards, the Weekly Cup and the quests); and the
 * coordinator on the other side of the {@link RaceLink} (Race Night's {@code NightRunner}, a
 * {@link PartyRace}).
 *
 * <p><b>Mirrored:</b> only the server I/O of {@code RaceMode.race}/{@code seated} (the world
 * session's entry), {@code tick}/{@code grid} (holding a boat still), {@code parkNow}/{@code toStand}
 * and {@code regrid} (teleports, boats and kits): the same {@link RaceRun} calls in the same order,
 * with the racer standing on their spot. A racer "crosses" the course by reaching its targets
 * evenly over the race time, then the finish goes through {@code TimeTrials.finish} as a move would.
 */
final class RaceBench {

    /** What the quests and achievements were told (FINISH_COURSE, raceNightFinished). */
    static final class Told implements GameProgress {
        final Map<UUID, Integer> courses = new HashMap<>();
        final Map<UUID, List<Boolean>> nights = new HashMap<>();

        @Override
        public void courseFinished(Player player, String courseId, boolean fresh, boolean record) {
            courses.merge(player.getUniqueId(), 1, Integer::sum);
        }

        @Override
        public void raceNightFinished(Player player, boolean won) {
            nights.computeIfAbsent(player.getUniqueId(), k -> new ArrayList<>()).add(won);
        }

        int courses(UUID id) {
            return courses.getOrDefault(id, 0);
        }
    }

    final TimeTrials trials;
    final RaceMode mode;
    final RaceRun.Clock clock = new RaceRun.Clock();
    /** The server tick. */
    long tick = 1_000;
    /** {@code System.nanoTime()}, 50 ms a tick. */
    long nanos = 7_000_000_000L;
    final Map<UUID, Player> players = new LinkedHashMap<>();
    /** Where each racer was last put on a grid by a re-grid, and how often. */
    final Map<UUID, Course.Spot> regridded = new HashMap<>();
    final Map<UUID, Integer> regrids = new HashMap<>();
    /** Who was parked on the stand, how often. */
    final Map<UUID, Integer> parked = new HashMap<>();
    /** Who went home, with the line they read ("" for none). */
    final Map<UUID, String> home = new LinkedHashMap<>();

    RaceBench(TimeTrials trials) {
        this.trials = trials;
        this.mode = trials.raceMode();
    }

    void add(Player p) {
        players.put(p.getUniqueId(), p);
    }

    Player player(UUID id) {
        return players.get(id);
    }

    /** Online and in no world game: a coordinator may take them to the track. */
    boolean free(UUID id) {
        return players.containsKey(id) && trials.run(id) == null;
    }

    // ---- TimeTrials.race / regrid / park / endRace --------------------------------------------------

    /** {@code TimeTrials.race}, the racer arriving at once: {@code null} when seated, else why not. */
    String seat(UUID id, Course base, Course raced, Course.Spot grid, Point stand, RaceLink link) {
        Player p = players.get(id);
        if (p == null || base == null || raced == null || link == null) {
            return "That racer isn't here.";
        }
        if (!mode.alive(link)) {
            return "That race is over.";
        }
        if (trials.run(id) != null) {
            return Refusal.IN_SESSION.message();
        }
        boolean warm = mode.call(link, () -> link.warmupUntil() > tick, false);
        Course.Spot spot = grid != null ? grid : raced.start();
        RaceRun rr = new RaceRun(link, base, spot, stand, warm);
        // RaceMode.seated, on arrival:
        long until = mode.call(link, link::warmupUntil, 0L);
        boolean warmNow = rr.state == RaceRun.State.WARMUP && until > tick;
        TrialRun run = new TrialRun(id, warmNow ? rr.base : raced, false, 0);
        run.race = rr;
        trials.replaceRun(run);
        mode.inRace(p, rr); // collisions off, and a runner on the no-push team
        if (warmNow && run.beginWarmup(until)) {
            run.progress = new Progress(run.course, base.start().point(), nanos); // free laps from the start
            run.phase = TrialRun.Phase.RUNNING;
            return null;
        }
        rr.state = RaceRun.State.GRID;
        return null;
    }

    /** {@code TimeTrials.regrid} (RaceMode.regrid): the next race, or the end of the shared warm-up. */
    void regrid(UUID id, Course raced, Course.Spot grid) {
        TrialRun old = trials.run(id);
        if (old == null || old.race == null || old.race.ended || raced == null) {
            return;
        }
        Course.Spot spot = grid != null ? grid : raced.start();
        RaceRun rr = old.race;
        if (old.warmup) {
            old.endWarmup();
        }
        rr.regrid(spot);
        rr.due = RaceRun.Due.NONE;
        TrialRun run = new TrialRun(id, raced, false, 0);
        run.race = rr;
        run.warmupUsed = old.warmupUsed;
        trials.replaceRun(run);
        regridded.put(id, spot);
        regrids.merge(id, 1, Integer::sum);
    }

    /** {@code TimeTrials.park} (real). */
    void park(UUID id) {
        Player p = players.get(id);
        if (p != null) {
            trials.park(p);
        }
    }

    /** "Ready" in a race's shared warm-up ({@code Warmups.ready} without its kit item). */
    void ready(UUID id) {
        TrialRun run = trials.run(id);
        if (run == null || !run.warmup || run.race == null || run.warmupReady) {
            return;
        }
        run.warmupReady = true;
        RaceRun rr = run.race;
        mode.call(rr.link, () -> {
            rr.link.ready(id);
            return null;
        }, null);
    }

    // ---- the trial tick -----------------------------------------------------------------------------

    /** One server tick of race mode ({@code RaceMode.tick} for every race run). */
    void tick() {
        tick++;
        nanos += 50_000_000L;
        for (TrialRun run : new ArrayList<>(trials.liveRuns())) {
            if (run.race == null) {
                continue;
            }
            run.ticks++;
            RaceRun rr = run.race;
            switch (rr.next(mode.alive(rr.link))) {
                case HOME -> {
                    rr.due = RaceRun.Due.NONE;
                    rr.ended = true;
                    leave(run.player, rr.line, rr.why == null ? EndReason.FINISH : rr.why);
                }
                case CALLED_OFF -> {
                    rr.ended = true;
                    leave(run.player, mode.call(rr.link, rr.link::calledOffLine, "&7The race was called off."),
                            EndReason.ADMIN);
                }
                case PARK -> {
                    rr.due = RaceRun.Due.NONE;
                    run.phase = TrialRun.Phase.PARKED; // RaceMode.parkNow, the boat gone and the racer on the stand
                    run.backDue = false;
                    rr.parked();
                    if (run.warmup) {
                        run.endWarmup();
                    }
                    parked.merge(run.player, 1, Integer::sum);
                }
                case GRID -> grid(run);
                default -> {
                    // ENDED, STAND, RACE and the warm-up's free laps: nothing the bench has to do
                }
            }
        }
    }

    /** {@code RaceMode.grid}: held until the shared go tick, then every clock starts at one instant. */
    private void grid(TrialRun run) {
        RaceRun rr = run.race;
        RaceRun.Release r = mode.call(rr.link, () -> rr.release(tick, true, clock, () -> nanos),
                new RaceRun.Release(true, 0, false, 0));
        if (r.hold() || !r.go()) {
            return;
        }
        run.progress = new Progress(run.course, run.course.start().point(), r.nanos());
        run.phase = TrialRun.Phase.RUNNING;
        rr.started();
    }

    /** The session ended the way RaceMode ends it: the line, then Time Trials' session end. */
    private void leave(UUID id, String line, EndReason why) {
        home.put(id, line == null ? "" : line);
        Player p = players.get(id);
        if (p != null) {
            trials.onSessionEnd(p, why);
        }
    }

    // ---- racing ---------------------------------------------------------------------------------------

    /** The start instant of the racer's clock (their run's progress), or -1. */
    long startOf(UUID id) {
        TrialRun run = trials.run(id);
        return run == null || run.progress == null ? -1 : run.progress.startNanos();
    }

    /**
     * The racer runs their course: every target reached, evenly over {@code ms} from their clock's
     * start, and the line crossed {@code ms} after it, through {@code TimeTrials.finish} as the move
     * that reached it would. A warm-up lap or a race's line, whichever the run is on.
     */
    void cross(UUID id, long ms) {
        TrialRun run = trials.run(id);
        Player p = players.get(id);
        if (run == null || p == null || run.progress == null || !run.running()) {
            throw new IllegalStateException("not on a running course: " + id);
        }
        Progress pr = run.progress;
        long start = pr.startNanos();
        int n = run.course.targets().size();
        for (int i = pr.reachedTargets(); i < n; i++) {
            pr.reachNext(start + ms * 1_000_000L * (i + 1) / n);
        }
        trials.finish(p, run, start + ms * 1_000_000L);
    }
}
