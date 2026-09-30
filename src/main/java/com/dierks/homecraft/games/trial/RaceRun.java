package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.EndReason;
import com.dierks.homecraft.games.gen.api.GenTag;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * A racer's side of a Time Trials run in race mode (EVENTS-DROPPER-SPEC §A.4.11), pure and tested.
 *
 * <p>A race is a group of ordinary runs, each carrying one of these ({@code TrialRun.race}): the
 * {@link RaceLink} it reports to, the base course (the one its staleness is judged on, never the
 * course derived from the grid spot and the laps), where finishers wait, and where in the race it
 * is. A run without one is a normal run, byte for byte as it always was.
 *
 * <p><b>One Go for everyone.</b> Every racer of a race is held until the link's shared
 * {@link RaceLink#goTick()}, and every clock starts at the same instant: the {@link Clock} hands
 * out one {@code System.nanoTime()} per race, taken the first time any racer of it reaches the go
 * tick, so racers released on that tick share it, and a racer seated late (a slow chunk) starts at
 * once on it: their clock already shows the time since Go.
 *
 * <p><b>The states.</b> {@link State#WARMUP} free laps from the course's start before the grid
 * (owner decision D3), {@link State#GRID} held on the grid, {@link State#RACING}, and
 * {@link State#PARKED}: finished (or the race is over) and waiting on the stand. The coordinator
 * {@link #regrid re-grids} a parked or warming-up racer for the next race.
 *
 * <p><b>Every decision is made here.</b> What a race run's tick does ({@link #next}), when it is
 * released and on which clock ({@link #release}), what crossing the line does ({@link #line}), which
 * course staleness is judged on ({@link #stale}) and where a finish goes at all ({@link #route}) are
 * plain methods, so the tests pin exactly what {@link RaceMode} does on the server.
 */
final class RaceRun {

    /** Where a racer is in their race. */
    enum State {
        /** The shared warm-up: free laps, never timed. */
        WARMUP,
        /** On the grid, held until the go tick. */
        GRID,
        /** Racing: the clock runs from the shared Go. */
        RACING,
        /** Done with this race: on the stand, the run idle. */
        PARKED
    }

    /**
     * What the next trial tick does for the racer, so a finish seen inside a move event never
     * teleports from inside it: nothing, park them on the stand, or send them home.
     */
    enum Due {
        NONE,
        PARK,
        HOME
    }

    /** A parked racer this far from the stand is put back on it. */
    static final double STAND_RADIUS = 4;
    /** Live positions are reported this often (ticks). */
    static final int PROGRESS_EVERY = 5;

    final RaceLink link;
    /** The course as the coordinator raced it: its id, rev and layout decide staleness. */
    final Course base;
    /** {@link Course#layoutHash()} of {@link #base} when the racer was first seated. */
    final int baseLayout;
    /** Where finishers wait, or {@code null}: finishers go home at the line. */
    final Point stand;
    State state;
    /** The racer's spot on the current grid. */
    Course.Spot grid;
    /** 1 for the first race on this run, 2 after one re-grid, and so on. */
    int race = 1;
    /** The race whose finish was last reported (a line crossed once is reported once). */
    int reported;
    /** The race whose finish last also ran the course's normal finish ({@link RaceLink#normalRun()}). */
    int normalDone;
    /** The coordinator ended the run itself ({@code endRace}): its session end is not a "left". */
    boolean ended;
    /** The racer's collisions were switched off (racers never shove each other; restored when the run ends). */
    boolean noShove;
    /**
     * The racer is on the games' no-push team ({@code GamesService.noPush()}): a parkour or elytra
     * racer, from seating until the run ends, by any way at all. Never a boat racer: boats bump, as the
     * owner chose.
     */
    boolean noPush;
    /** What the next trial tick does ({@link Due}). */
    Due due = Due.NONE;
    /** WP-CH: the trip "home" goes to the Clubhouse instead (its session handed over in place). */
    boolean toClub;
    /** Going home: why, and the line they read (or {@code null}). */
    EndReason why;
    String line;

    RaceRun(RaceLink link, Course base, Course.Spot grid, Point stand, boolean warmup) {
        this.link = link;
        this.base = base;
        this.baseLayout = base.layoutHash();
        this.grid = grid;
        this.stand = stand;
        this.state = warmup ? State.WARMUP : State.GRID;
    }

    /** Whether the racer is held on the grid at server tick {@code tick} (the go tick hasn't come). */
    boolean held(long tick) {
        return state == State.GRID && tick < link.goTick();
    }

    /** Whether the racer goes at server tick {@code tick}: on the grid and the go tick has come. */
    boolean goes(long tick) {
        return state == State.GRID && tick >= link.goTick();
    }

    /** Ticks until Go (0 once it has come). */
    long ticksToGo(long tick) {
        long go = link.goTick();
        return go <= tick ? 0 : go - tick;
    }

    /** The clock is running. */
    void started() {
        state = State.RACING;
    }

    /**
     * The racer crossed the line: true the first time for this race (report it), false for a line
     * already reported.
     */
    boolean crossed() {
        if (reported == race) {
            return false;
        }
        reported = race;
        return true;
    }

    /**
     * Whether this race's finish should also run the course's normal finish: a party race's link
     * ({@link RaceLink#normalRun()}), a counted finish, and not already done for this race. Marks it
     * done, so it happens exactly once.
     */
    boolean normalFinish(boolean counted) {
        return normalFinish(counted, link.normalRun());
    }

    /**
     * {@link #normalFinish(boolean)} with the link's {@link RaceLink#normalRun()} already asked (race
     * mode asks it through its guard, so a link that throws never throws out of a move event).
     */
    boolean normalFinish(boolean counted, boolean normalRun) {
        if (!counted || !normalRun || normalDone == race) {
            return false;
        }
        normalDone = race;
        return true;
    }

    /**
     * Send the racer home on the next tick, reading {@code line}: the coordinator's end, or the line
     * crossed with no stand to wait on. Their session ending then is not a "left".
     */
    void home(EndReason reason, String text) {
        due = Due.HOME;
        why = reason;
        line = text;
    }

    /** Done with this race: on the stand. */
    void parked() {
        state = State.PARKED;
    }

    /** The next race (or the end of the warm-up): back to the grid at {@code spot}. */
    void regrid(Course.Spot spot) {
        if (state != State.WARMUP) {
            race++;
        }
        grid = spot;
        state = State.GRID;
    }

    /** Whether a parked racer at {@code at} has wandered off the stand ({@value #STAND_RADIUS} blocks). */
    boolean offStand(Point at) {
        return offStand(at, STAND_RADIUS);
    }

    /**
     * Whether a parked racer at {@code at} has wandered more than {@code radius} blocks across (or
     * more than {@code radius} + 2 up or down) from the stand: the one rule for every race's stand
     * (Race Night's {@code stand_radius} comes through {@link RaceLink#standRadius()}).
     */
    boolean offStand(Point at, double radius) {
        if (state != State.PARKED || stand == null || at == null) {
            return false;
        }
        double r = radius > 0 ? radius : STAND_RADIUS;
        return Math.hypot(at.x() - stand.x(), at.z() - stand.z()) > r || Math.abs(at.y() - stand.y()) > r + 2;
    }

    /**
     * Whether a racer on the grid at {@code at} has left their spot: more than
     * {@link FairPlay#START_RADIUS} across from it, the solo start's own rule. A boat held at zero
     * speed still creeps while W is held; released there, it would start ahead of its spot.
     */
    boolean offSpot(Point at) {
        return grid != null && at != null
                && Math.hypot(at.x() - grid.x(), at.z() - grid.z()) > FairPlay.START_RADIUS;
    }

    // ---- the decisions RaceMode acts on -----------------------------------------------------------

    /** Where a finish in Time Trials goes: a warm-up lap, a race's line, or a solo run's finish. */
    enum Route {
        /** A warm-up lap (solo or a race's shared warm-up): never judged, recorded, paid or reported. */
        WARMUP_LAP,
        /** A race run's line: the link hears it ({@link RaceMode#finish}). */
        RACE,
        /** A normal run's finish, exactly as it always was. */
        SOLO
    }

    /** Where {@code run}'s finish goes: a warm-up comes first, then a race, else the normal finish. */
    static Route route(TrialRun run) {
        if (run.warmup) {
            return Route.WARMUP_LAP;
        }
        return run.race != null ? Route.RACE : Route.SOLO;
    }

    /** What a race run's tick does. */
    enum Act {
        /** Already on the way home: nothing. */
        ENDED,
        /** Home now: the coordinator ended it, or the line with no stand. */
        HOME,
        /** The race is over (its link is no longer alive, or it failed): home with its called-off line. */
        CALLED_OFF,
        /** Onto the stand now. */
        PARK,
        /** Held on the grid: 3-2-1, then Go on the shared tick. */
        GRID,
        /** Waiting on the stand. */
        STAND,
        /** Racing: the normal tick runs it, and the link hears where the racer is. */
        RACE,
        /** The shared warm-up's free laps: the normal tick runs it, with the warm-up bar. */
        WARMUP
    }

    /**
     * What the tick does with this racer, given whether the race still runs. A trip home the
     * coordinator asked for comes before "called off", so a racer the race sent home reads the
     * coordinator's own line.
     */
    Act next(boolean alive) {
        if (ended) {
            return Act.ENDED;
        }
        if (due == Due.HOME) {
            return Act.HOME;
        }
        if (!alive) {
            return Act.CALLED_OFF;
        }
        if (due == Due.PARK) {
            return Act.PARK;
        }
        return switch (state) {
            case GRID -> Act.GRID;
            case PARKED -> Act.STAND;
            case RACING -> Act.RACE;
            case WARMUP -> Act.WARMUP;
        };
    }

    /**
     * What the grid does at server tick {@code now}.
     *
     * @param hold  still held (the go tick hasn't come)
     * @param count the countdown number to show now (3, 2, 1), or 0 for none this tick
     * @param go    the clock starts now (the go tick has come and the racer is in place)
     * @param nanos the shared start instant, once the go tick has come (0 before)
     * @param back  the racer left their grid spot: put them back on it now (and, at Go, not started:
     *              they start on the shared clock once back, losing the time it took)
     */
    record Release(boolean hold, int count, boolean go, long nanos, boolean back) {

        Release(boolean hold, int count, boolean go, long nanos) {
            this(hold, count, go, nanos, false);
        }
    }

    /**
     * The grid at tick {@code now}: held until the link's go tick, "3", "2", "1" on the last three
     * whole seconds, then released on the shared clock. A racer not in place yet at Go ({@code arrived}
     * false: a slow chunk, a boat not seated) isn't held and isn't started; it starts the moment it is
     * in, on the same shared instant, so its clock already shows the time since Go.
     */
    Release release(long now, boolean arrived, Clock clock, LongSupplier nanos) {
        return release(now, arrived, false, clock, nanos);
    }

    /**
     * {@link #release(long, boolean, Clock, LongSupplier)} for a racer who may have left their grid
     * spot ({@code offSpot}, {@link #offSpot}): while held they are put back on it; at Go they are put
     * back and not started, and start on the shared clock once back on it, so drifting forward never
     * gains a head start (the solo start's "Stay at the start until it says Go!").
     */
    Release release(long now, boolean arrived, boolean offSpot, Clock clock, LongSupplier nanos) {
        boolean back = arrived && offSpot;
        long goTick = link.goTick();
        if (now < goTick) {
            long left = goTick - now;
            int count = left <= TimeTrials.COUNTDOWN_TICKS && left % 20 == 0 ? (int) (left / 20) : 0;
            return new Release(true, count, false, 0, back);
        }
        long go = clock.goNanos(link, goTick, nanos);
        return new Release(false, 0, arrived && !back, go, back);
    }

    /**
     * What crossing the line does.
     *
     * @param report tell the link (false: this race's line was already crossed, nothing at all)
     * @param normal also the course's normal counted finish (a party race), exactly once a race
     * @param e4     a counted race finish that isn't a normal run: tell the quests once (E4 FINISH_COURSE)
     * @param next   then park on the stand, or go home at the line when there is no stand
     */
    record Line(boolean report, boolean normal, boolean e4, Due next) {
    }

    /**
     * The racer crossed the line with a finish that {@code counted} (fair play, the shortest
     * believable time, the speed check; stale only on the base course). A Race Night finish never
     * touches the course's boards or rewards; a party race's also counts as the normal run, once.
     */
    Line line(boolean counted) {
        return line(counted, link.normalRun());
    }

    /**
     * {@link #line(boolean)} with the link's {@link RaceLink#normalRun()} already asked, through race
     * mode's guard ({@code RaceMode.finish}): a link that throws there is over, never a throw out of
     * the move event that saw the line.
     */
    Line line(boolean counted, boolean normalRun) {
        if (!crossed()) {
            return new Line(false, false, false, Due.NONE);
        }
        boolean normal = normalFinish(counted, normalRun);
        return new Line(true, normal, counted && !normal, stand != null ? Due.PARK : Due.HOME);
    }

    /**
     * Whether a finish now is stale: judged on the BASE course (its rev and layout, and the
     * still-standing rule), never on the course derived from the grid spot and the laps, whose
     * layout differs by design.
     */
    boolean stale(Course now, Predicate<GenTag> standing) {
        return FairPlay.stale(base, baseLayout, now, standing);
    }

    /** The shared Go of every race: one {@code System.nanoTime()} per link and go tick. */
    static final class Clock {

        private final Map<RaceLink, long[]> goes = new IdentityHashMap<>();

        /**
         * The instant the race that {@code link} holds at {@code goTick} started: taken from
         * {@code now} the first time it is asked, the same for every racer after that.
         */
        long goNanos(RaceLink link, long goTick, LongSupplier now) {
            long[] g = goes.get(link);
            if (g == null || g[0] != goTick) {
                g = new long[]{goTick, now.getAsLong()};
                goes.put(link, g);
            }
            return g[1];
        }

        /** Forget every link but {@code live} (the races still running). */
        void keepOnly(Set<RaceLink> live) {
            goes.keySet().removeIf(l -> !live.contains(l));
        }

        void clear() {
            goes.clear();
        }

        int size() {
            return goes.size();
        }
    }

    /**
     * The courses held for races ({@code TimeTrials.reserve}): new solo runs on them are refused with
     * the holder's line until it lets them go. A course is held by at most one holder.
     */
    static final class Holds {

        private record Hold(Object holder, String line) {
        }

        private final Map<String, Hold> holds = new LinkedHashMap<>();

        /** Hold {@code courseId} for {@code holder}; false when another holder has it. */
        boolean reserve(String courseId, Object holder, String line) {
            if (courseId == null || holder == null) {
                return false;
            }
            String k = key(courseId);
            Hold h = holds.get(k);
            if (h != null && h.holder() != holder) {
                return false;
            }
            holds.put(k, new Hold(holder, line == null || line.isBlank() ? "&7A race is on this course." : line));
            return true;
        }

        /** Let {@code courseId} go, if {@code holder} is the one holding it. */
        void release(String courseId, Object holder) {
            if (courseId == null) {
                return;
            }
            String k = key(courseId);
            Hold h = holds.get(k);
            if (h != null && h.holder() == holder) {
                holds.remove(k);
            }
        }

        /** What a solo run on {@code courseId} is refused with, or {@code null} when it isn't held. */
        String refusal(String courseId) {
            Hold h = courseId == null ? null : holds.get(key(courseId));
            return h == null ? null : h.line();
        }

        void clear() {
            holds.clear();
        }

        private static String key(String id) {
            return id.trim().toLowerCase(Locale.ROOT);
        }
    }
}
