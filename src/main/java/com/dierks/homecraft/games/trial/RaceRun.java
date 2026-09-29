package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.EndReason;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

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
    /** The stand's collisions were switched off for this racer (restored when the run ends). */
    boolean standing;
    /** What the next trial tick does ({@link Due}). */
    Due due = Due.NONE;
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
        if (!counted || !link.normalRun() || normalDone == race) {
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

    /** Whether a parked racer at {@code at} has wandered off the stand. */
    boolean offStand(Point at) {
        return state == State.PARKED && stand != null && at != null && stand.distance(at) > STAND_RADIUS;
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
