package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.gen.api.GenTag;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The fair-play rules of a run (spec §11, R2.15), pure and tested: when a fall sends you back,
 * when a landing doesn't, and whether a finished run counts.
 *
 * <p><b>Why these checks and no others.</b> A run happens in a world session, so the player
 * can't carry anything in, can't be hurt, and a teleport by anyone else is caught (a far one ends
 * the game, a short one sends the run back to its last checkpoint). What's left for the run to
 * check is what the session can't see: a run quicker than the course could ever be done
 * ({@code min_seconds}); a leg between two checkpoints covered faster than the kind of course
 * allows, counting only the distance that must really be travelled, {@code max(0, d − r_prev −
 * r_cur)}, so big spheres close together never trip it; and flying, a changed game mode, a
 * potion effect or a changed walk speed or movement attribute, which void the run the moment
 * they are seen.
 *
 * <p><b>Server stalls.</b> A move is timed when the server handles it. While the server stalls
 * (an autosave, new chunks, a GC pause) the client's moves queue up and are then handled
 * microseconds apart, so two checkpoints crossed during the stall look a hair apart. Lag only
 * ever lengthens a time, so it is never a cheat — but it would look like one. So a gap between
 * two trial ticks longer than {@link #STALL_NANOS} is kept as a {@link Stall}, and the speed check
 * skips any leg whose time overlaps one; every other leg gets {@link #LEG_SLACK} of slack.
 *
 * <p>A void is not a punishment: the player finishes the run, sees their time, and reads "That run
 * didn't count." with why. A test run is judged the same way but records nothing, and says
 * whether it would have counted.
 */
final class FairPlay {

    /** How far from the start a run may begin (the countdown restarts beyond it). */
    static final double START_RADIUS = 1.0;
    /** Around the start, a landing is fine (an elytra run takes off from there). */
    static final double START_ZONE = 2.0;

    static final String FLYING = "flying";
    static final String GAME_MODE = "your game mode changed";
    static final String EFFECT = "a potion effect";
    static final String TOO_QUICK = "quicker than this course allows";
    static final String TOO_FAST = "too fast between two checkpoints";
    static final String CHANGED = "the course changed during your run";
    static final String WALK_SPEED = "your walk speed changed";
    static final String MOVEMENT = "your movement changed";

    /** A gap between two trial ticks longer than this is a server stall. */
    static final long STALL_NANOS = 250_000_000L;
    /** Every leg gets this much more time before it counts as too fast (one tick of jitter). */
    static final double LEG_SLACK = 0.05;

    /** A player's walk speed when nothing has changed it. */
    static final float DEFAULT_WALK_SPEED = 0.2f;
    /** The movement modifiers an honest run has: vanilla sprinting, on the movement speed. */
    static final String SPRINTING = "minecraft:sprinting";
    /** Vanilla's powder-snow slow-down: it only slows, so a course built with powder snow is fair. */
    static final String POWDER_SNOW = "minecraft:powder_snow";
    static final String MOVEMENT_SPEED = "minecraft:movement_speed";
    /** The movement attributes a run watches, and a player's own base value of each. */
    static final Map<String, Double> PLAYER_BASES = Map.of(
            MOVEMENT_SPEED, 0.1,
            "minecraft:jump_strength", 0.42,
            "minecraft:step_height", 0.6,
            "minecraft:gravity", 0.08,
            "minecraft:safe_fall_distance", 3.0);

    private FairPlay() {
    }

    /** How a finished run is taken. */
    enum Kind {
        /** Recorded and rewarded. */
        COUNTED,
        /** Seen breaking a rule: shown, not recorded. */
        VOID,
        /** The course was changed (or deleted) mid-run: its time belongs to a layout that's gone. */
        STALE,
        /** An admin's try-out: nothing is ever recorded. */
        TEST
    }

    /**
     * The verdict on a finished run.
     *
     * @param kind   how it is taken
     * @param reason why it doesn't count (for a test: why it wouldn't have), or {@code null}
     */
    record Verdict(Kind kind, String reason) {

        /** Whether it is recorded and rewarded. */
        boolean counts() {
            return kind == Kind.COUNTED;
        }
    }

    /**
     * A time the server stood still, in {@code System.nanoTime()}: from the last trial tick before
     * it to a little after the first tick after it, when the moves that queued up are handled.
     */
    record Stall(long from, long to) {

        /** Whether it overlaps {@code start}..{@code end}. */
        boolean overlaps(long start, long end) {
            return start <= to && end >= from;
        }
    }

    /**
     * The stall between two trial ticks at {@code prevTick} and {@code tick}, or {@code null} when
     * the gap was an ordinary one. It runs on {@link #STALL_NANOS} past the second tick: the moves
     * that queued up during it are handled in a burst around then. A {@code prevTick} of 0 means
     * there was no tick before this one.
     */
    static Stall stall(long prevTick, long tick) {
        if (prevTick == 0 || tick - prevTick <= STALL_NANOS) {
            return null;
        }
        return new Stall(prevTick, tick + STALL_NANOS);
    }

    /**
     * Judge a finished run.
     *
     * @param test       an admin's test run (records nothing, whatever else)
     * @param voided     why the run was voided while it ran, or {@code null}
     * @param stale      the course's layout changed (or it was deleted) since the run started
     * @param ms         the run's time
     * @param minSeconds the course's shortest believable time
     * @param tooFastAt  the first target reached too fast ({@link #tooFast}), or -1
     */
    static Verdict judge(boolean test, String voided, boolean stale, long ms, int minSeconds, int tooFastAt) {
        String reason = voided;
        if (reason == null && tooQuick(ms, minSeconds)) {
            reason = TOO_QUICK;
        }
        if (reason == null && tooFastAt >= 0) {
            reason = TOO_FAST;
        }
        if (test) {
            return new Verdict(Kind.TEST, reason);
        }
        if (stale) {
            return new Verdict(Kind.STALE, CHANGED);
        }
        return reason == null ? new Verdict(Kind.COUNTED, null) : new Verdict(Kind.VOID, reason);
    }

    /** Whether a run of {@code ms} beats the shortest believable time. */
    static boolean tooQuick(long ms, int minSeconds) {
        return minSeconds > 0 && ms < minSeconds * 1000L;
    }

    /**
     * Whether a leg was covered faster than {@code maxSpeed} allows: the distance that must really
     * be travelled between the two spheres, {@code max(0, d − rPrev − rCur)}, over the time taken.
     */
    static boolean tooFast(double d, double rPrev, double rCur, double seconds, double maxSpeed) {
        double gap = Math.max(0, d - rPrev - rCur);
        if (gap == 0) {
            return false;
        }
        return seconds <= 0 || gap / seconds > maxSpeed;
    }

    /**
     * The first target (0-based: checkpoints, then the finish) reached faster than the course's
     * kind allows, or -1 when every leg is believable. The first leg is from the start, which a run
     * may begin up to {@link #START_RADIUS} away from.
     *
     * @param startNanos when the run started
     * @param times      when each target was reached ({@link Progress#times()})
     * @param reached    how many targets were reached
     */
    static int tooFast(Course course, long startNanos, long[] times, int reached) {
        return tooFast(course, startNanos, times, reached, List.of());
    }

    /**
     * {@link #tooFast(Course, long, long[], int)} for a run that saw server stalls: a leg whose
     * time overlaps a stall isn't checked (its times are when the server caught up, not when the
     * player got there), and every other leg gets {@link #LEG_SLACK} more time.
     *
     * @param stalls the stalls seen while the run ran
     */
    static int tooFast(Course course, long startNanos, long[] times, int reached, List<Stall> stalls) {
        if (course.start() == null) {
            return -1;
        }
        List<Course.Mark> targets = course.targets();
        Point prev = course.start().point();
        double prevRadius = START_RADIUS;
        long prevAt = startNanos;
        int n = Math.min(reached, Math.min(times.length, targets.size()));
        for (int i = 0; i < n; i++) {
            Course.Mark cur = targets.get(i);
            double seconds = (times[i] - prevAt) / 1e9 + LEG_SLACK;
            double d = prev.distance(cur.center());
            if (!stalled(stalls, prevAt, times[i])
                    && tooFast(d, prevRadius, cur.radius(), seconds, course.kind().maxSpeed())) {
                return i;
            }
            prev = cur.center();
            prevRadius = cur.radius();
            prevAt = times[i];
        }
        return -1;
    }

    private static boolean stalled(List<Stall> stalls, long start, long end) {
        if (stalls != null) {
            for (Stall st : stalls) {
                if (st.overlaps(start, end)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * One movement attribute as the player has it.
     *
     * @param key       the attribute's key ({@code minecraft:gravity})
     * @param base      its base value
     * @param modifiers the keys of the modifiers on it
     */
    record Stat(String key, double base, Collection<String> modifiers) {
    }

    /**
     * Why the player's movement voids a run, or {@code null} when it is a player's own: a walk
     * speed other than {@value #DEFAULT_WALK_SPEED} ({@code /speed}), or a watched attribute
     * ({@link #PLAYER_BASES}) with its base value changed or any modifier on it but vanilla
     * sprinting or powder snow's slow-down — another plugin's speed, jump, step, gravity or safe-fall boost. A value that
     * isn't a number counts as changed.
     */
    static String movement(float walkSpeed, Collection<Stat> stats) {
        if (!(Math.abs(walkSpeed - DEFAULT_WALK_SPEED) <= 1e-4)) {
            return WALK_SPEED;
        }
        for (Stat st : stats) {
            Double base = PLAYER_BASES.get(st.key());
            if (base == null) {
                continue;
            }
            if (!(Math.abs(st.base() - base) <= 1e-6)) {
                return MOVEMENT;
            }
            for (String m : st.modifiers()) {
                if (!((SPRINTING.equals(m) || POWDER_SNOW.equals(m)) && MOVEMENT_SPEED.equals(st.key()))) {
                    return MOVEMENT;
                }
            }
        }
        return null;
    }

    /**
     * Whether a run that started on {@code then} records nothing because of {@code now}: the
     * course was deleted, or its layout is not the one the run started on — a new {@code rev}, or
     * the same {@code rev} on a different layout (a course deleted and made again starts at
     * layout 1, so the rev alone can come round to the same number).
     *
     * @param rev    the course's layout version when the run started
     * @param layout {@link Course#layoutHash()} when the run started
     * @param now    the course as it is now, or {@code null} for deleted
     */
    static boolean stale(int rev, int layout, Course now) {
        return now == null || now.rev() != rev || now.layoutHash() != layout;
    }

    /**
     * {@link #stale(int, int, Course)} with Fresh Courses' "still standing" rule (GEN-SPEC §3.4):
     * a run on a generated course is stale only if the old rule says so AND the layout it started
     * on no longer stands. The next layout going live doesn't void a run on the previous one while
     * its blocks are still there; once that half starts being cleared, it does. A hand-built
     * course keeps the old rule exactly.
     *
     * @param then     the course as the run started (its snapshot, with its tag)
     * @param layout   {@link Course#layoutHash()} when the run started
     * @param now      the course as it is now, or {@code null} for deleted
     * @param standing whether a layout still stands ({@code GeneratedCourses#standing})
     */
    static boolean stale(Course then, int layout, Course now, Predicate<GenTag> standing) {
        boolean changed = stale(then.rev(), layout, now);
        if (!changed || then.gen() == null) {
            return changed;
        }
        return standing == null || !standing.test(then.gen());
    }

    /**
     * Below this height a run goes back to its last checkpoint, or {@code NaN} for no such rule.
     * A course's own {@code fall_y} applies to any kind; without one, a parkour course falls at
     * {@code fallDepth} below the lower of the last checkpoint reached (or the start) and the next
     * one (or the finish) — so a course that climbs or drops still has a sensible floor on every
     * leg. Elytra and boat courses have no default: their resets are landing and leaving the boat.
     *
     * @param lastCheckpoint the last checkpoint reached, 0-based, or -1 for none
     */
    static double fallY(Course course, int lastCheckpoint, int fallDepth) {
        if (course.fallY() != null) {
            return course.fallY();
        }
        if (course.kind() != TrialKind.PARKOUR || course.start() == null) {
            return Double.NaN;
        }
        List<Course.Mark> cps = course.checkpoints();
        double lastY = lastCheckpoint >= 0 && lastCheckpoint < cps.size() ? cps.get(lastCheckpoint).y()
                : course.start().y();
        int nextIndex = lastCheckpoint + 1;
        Double nextY = nextIndex < cps.size() ? Double.valueOf(cps.get(nextIndex).y())
                : course.finish() != null ? Double.valueOf(course.finish().y()) : null;
        double floor = nextY == null ? lastY : Math.min(lastY, nextY);
        return floor - fallDepth;
    }

    /**
     * Whether {@code p} is somewhere a landing is fine: inside any checkpoint, the finish, or near
     * the start. An elytra run that lands anywhere else goes back to its last checkpoint.
     */
    static boolean safeLanding(Course course, Point p) {
        if (course.start() != null && course.start().point().distance(p) <= START_ZONE) {
            return true;
        }
        for (Course.Mark m : course.targets()) {
            if (m.contains(p)) {
                return true;
            }
        }
        return false;
    }
}
