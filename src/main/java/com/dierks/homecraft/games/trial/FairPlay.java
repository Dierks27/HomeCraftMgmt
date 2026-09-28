package com.dierks.homecraft.games.trial;

import java.util.List;

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
 * r_cur)}, so big spheres close together never trip it; and flying, a changed game mode or a
 * potion effect, which void the run the moment they are seen.
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
            double seconds = (times[i] - prevAt) / 1e9;
            if (tooFast(prev.distance(cur.center()), prevRadius, cur.radius(), seconds, course.kind().maxSpeed())) {
                return i;
            }
            prev = cur.center();
            prevRadius = cur.radius();
            prevAt = times[i];
        }
        return -1;
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
