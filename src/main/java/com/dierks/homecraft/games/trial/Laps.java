package com.dierks.homecraft.games.trial;

import java.util.ArrayList;
import java.util.List;

/**
 * The laps of a race (EVENTS-DROPPER-SPEC §A.4.4), pure and tested.
 *
 * <p><b>Why laps are read off the checkpoints.</b> A course stores no lap count: Fresh Ice Boat
 * stores its two laps as the same checkpoints twice, and a hand-built loop might store one lap or
 * three. So the natural lap is the shortest run of checkpoints that the whole list repeats
 * ({@link #period}), and a race asking for a different number of laps just repeats that run. A
 * point-to-point course has no laps to repeat: it is always 1.
 *
 * <p><b>The raced course.</b> A racer starts from their grid spot, not the course's start, and goes
 * round the laps asked for: {@link #raced} makes that course from the base one, with the shortest
 * believable time scaled by the laps, so the existing fair-play checks judge a race exactly as a
 * solo run. The base course stays the one staleness is judged on.
 */
public final class Laps {

    /** Two marks this close in every coordinate and radius are the same mark (a stored repeat). */
    public static final double SAME = 0.01;
    /** A loop's finish is at most this far from its start (or its own radius, if larger). */
    public static final double LOOP_GAP = 6;
    /** A loop has at least this many checkpoints a lap. */
    public static final int LOOP_CHECKPOINTS = 3;
    /** The most laps a race may ask for (1-5, spec §A.10). */
    public static final int MAX_LAPS = 5;

    private Laps() {
    }

    /**
     * The shortest {@code p} for which {@code checkpoints} is its first {@code p} marks repeated
     * (every coordinate and radius within {@value #SAME}); the list's size when nothing repeats,
     * and 0 for no checkpoints.
     */
    public static int period(List<Course.Mark> checkpoints) {
        int n = checkpoints == null ? 0 : checkpoints.size();
        for (int p = 1; p < n; p++) {
            if (n % p != 0) {
                continue;
            }
            boolean repeats = true;
            for (int i = p; i < n && repeats; i++) {
                repeats = same(checkpoints.get(i), checkpoints.get(i % p));
            }
            if (repeats) {
                return p;
            }
        }
        return n;
    }

    /** How many laps the course stores: its checkpoints over their {@link #period}; 1 for none. */
    public static int natural(Course c) {
        int n = c.checkpoints().size();
        int p = period(c.checkpoints());
        return p == 0 ? 1 : n / p;
    }

    /**
     * Whether the course is a loop: its finish within {@code max(finish radius, }{@value #LOOP_GAP}{@code )}
     * of its start, and at least {@value #LOOP_CHECKPOINTS} checkpoints a lap. Only a loop may be
     * raced for a different number of laps, and only on a loop does the grid follow the track behind
     * the start.
     */
    public static boolean loop(Course c) {
        if (c.start() == null || c.finish() == null) {
            return false;
        }
        double gap = c.start().point().distance(c.finish().center());
        return gap <= Math.max(c.finish().radius(), LOOP_GAP) && period(c.checkpoints()) >= LOOP_CHECKPOINTS;
    }

    /** One lap's checkpoints: the first {@link #period} of them. */
    public static List<Course.Mark> lap(Course c) {
        return List.copyOf(c.checkpoints().subList(0, period(c.checkpoints())));
    }

    /**
     * What a race on {@code base} from {@code grid} for {@code laps} laps runs on.
     *
     * @param course  the raced course, or {@code null} when it can't be raced that way
     * @param laps    the laps it goes round
     * @param problem why not, in admin words, or {@code null}
     */
    public record Raced(Course course, int laps, String problem) {
    }

    /**
     * The course a racer runs: the base course from {@code grid} (the start when {@code null}),
     * for {@code laps} laps (0 = the course's own). A different number of laps is allowed only on a
     * {@link #loop}, 1 to {@value #MAX_LAPS}, and only while the targets stay at most
     * {@link Course#MAX_CHECKPOINTS}; its shortest believable time is scaled by the laps over the
     * course's own. A point-to-point course is always 1 lap.
     */
    public static Raced raced(Course base, Course.Spot grid, int laps) {
        if (base == null || base.start() == null || base.finish() == null) {
            return new Raced(null, 0, "the course has no start or finish");
        }
        Course from = grid == null ? base : base.withStart(grid);
        int own = natural(base);
        if (laps <= 0 || laps == own) {
            return new Raced(from, own, null);
        }
        if (laps > MAX_LAPS) {
            return new Raced(null, 0, "a race is at most " + MAX_LAPS + " laps");
        }
        if (!loop(base)) {
            return new Raced(null, 0, "only a loop can be raced for " + laps + " laps (this course is "
                    + own + ")");
        }
        List<Course.Mark> lap = lap(base);
        if (lap.size() * laps + 1 > Course.MAX_CHECKPOINTS) {
            return new Raced(null, 0, laps + " laps would be " + (lap.size() * laps + 1) + " targets, more than "
                    + Course.MAX_CHECKPOINTS);
        }
        List<Course.Mark> marks = new ArrayList<>(lap.size() * laps);
        for (int i = 0; i < laps; i++) {
            marks.addAll(lap);
        }
        Integer min = base.minSeconds() == null ? null
                : (int) Math.round(base.minSeconds() * (double) laps / own);
        return new Raced(from.withCheckpoints(marks).withMinSeconds(min), laps, null);
    }

    /**
     * The lap a racer is on (1-based, at most {@code laps}) after reaching {@code reached} targets
     * of a course whose laps are {@code perLap} checkpoints each: "Lap 1/2".
     */
    public static int lapOf(int reached, int perLap, int laps) {
        if (perLap <= 0 || laps <= 1) {
            return 1;
        }
        return Math.max(1, Math.min(laps, reached / perLap + 1));
    }

    private static boolean same(Course.Mark a, Course.Mark b) {
        return Math.abs(a.x() - b.x()) <= SAME && Math.abs(a.y() - b.y()) <= SAME && Math.abs(a.z() - b.z()) <= SAME
                && Math.abs(a.radius() - b.radius()) <= SAME;
    }
}
