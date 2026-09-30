package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.gen.dropper.DropMarks;
import com.dierks.homecraft.games.gen.dropper.DropperPlanner;

import java.util.List;
import java.util.Locale;

/**
 * A Dropper as Time Trials reads it (EVENTS-DROPPER-SPEC §B.1.7): a thin face on
 * {@link DropMarks}, so the planner, the validator and the game read ONE encoding of the levels in
 * an ordinary course's marks, with no new {@link Course} field and no codec change.
 *
 * <pre>
 * start        = level 1's ledge spot, facing the shaft
 * checkpoints  = [pool1, ledge2, pool2, ledge3, ..., pool(n-1), ledge(n)]
 * finish       = pool(n)
 * </pre>
 *
 * <p>So a run's targets alternate pool, ledge, pool...: target {@code i} is a pool when {@code i} is
 * even. A splash is reaching a pool; the hop to the next level is the run's own teleport onto that
 * level's ledge, which the next move reaches at once. A bonk sends a run back to the ledge of the
 * level it is on ({@link #backTo}).
 *
 * <p>Also here: which admin verbs a kept dropper refuses. A kept dropper is an ordinary {@code trials}
 * row (no {@code gen:} tag), so the Fresh Courses refusal doesn't cover it; its ledges and pools are
 * the planner's proven layout, so the geometry verbs are refused ({@link #editRefusal}).
 */
public final class DropperLayout {

    /**
     * The course-tool verbs that would move a dropper's proven marks (or its physics-derived shortest
     * time): a kept dropper refuses them. {@code info}, {@code tp}, {@code test}, {@code name},
     * {@code tier}, {@code enable}, {@code disable}, {@code feature} and {@code delete} still work.
     */
    public static final List<String> GEOMETRY_VERBS = List.of("start", "checkpoint", "checkpoints", "cp", "finish",
            "fall", "minseconds");

    /** What an admin reads for a refused geometry verb on a dropper. */
    public static final String GEOMETRY_REFUSED = "&7A dropper's ledges and pools were made and proven by Fresh"
            + " Courses, so they can't be moved. &eName, tier, enable, test and feature &7still work.";

    private DropperLayout() {
    }

    /** Whether {@code c} is a dropper (its kind is {@link TrialKind#DROPPER}). */
    public static boolean is(Course c) {
        return c != null && c.kind() == TrialKind.DROPPER;
    }

    /** How many levels a dropper has (its checkpoints come in pairs). */
    public static int levels(Course c) {
        return DropMarks.levels(c);
    }

    /** The level (0-based) target {@code index} of {@link Course#targets()} belongs to. */
    public static int levelOf(int index) {
        return DropMarks.levelOf(index);
    }

    /** Whether target {@code index} is a pool (a splash), not a ledge (a hop's landing). */
    public static boolean isPool(int index) {
        return DropMarks.isPool(index);
    }

    /**
     * The level (0-based) a run is on once it has reached target {@code lastReached} (-1 for none
     * yet): a pool reached means the next level, whose ledge the hop is taking it to.
     */
    public static int currentLevel(int lastReached) {
        return DropMarks.currentLevel(lastReached);
    }

    /** The level a run with {@code reachedTargets} targets reached is on, never past the last one. */
    public static int levelNow(Course c, int reachedTargets) {
        return Math.min(currentLevel(reachedTargets - 1), levels(c) - 1);
    }

    /** Level {@code level}'s (0-based) ledge mark; level 0's is made from the start spot. */
    public static Course.Mark ledgeOf(Course c, int level) {
        return DropMarks.ledgeOf(c, level);
    }

    /** Level {@code level}'s (0-based) pool mark; the last level's is the finish. */
    public static Course.Mark poolOf(Course c, int level) {
        return DropMarks.poolOf(c, level);
    }

    /** Where a bonk sends a run that has reached target {@code lastReached}: its current level's ledge. */
    public static Course.Mark backTo(Course c, int lastReached) {
        return DropMarks.backTo(c, lastReached);
    }

    /** A pool mark's box {x1, y1, z1, x2, y2, z2}: the water's column, up to half a block over it. */
    public static double[] poolBox(Course.Mark pool) {
        return DropMarks.poolBox(pool);
    }

    /**
     * A pool mark's splash box: {@link #poolBox} with x and z pulled in by half a player's width, so
     * the feet of a body resting on the rim are never in it ({@link DropMarks#splashBox}).
     */
    public static double[] splashBox(Course.Mark pool) {
        return DropMarks.splashBox(pool);
    }

    /** Whether the feet at a point are inside a pool mark's {@link #splashBox}. */
    public static boolean inPool(Course.Mark pool, Point p) {
        return pool != null && p != null && DropMarks.inPool(pool, p.x(), p.y(), p.z());
    }

    /** The yaw that faces from a ledge toward its pool (a later ledge's mark carries no facing). */
    public static float facing(Course.Mark ledge, Course.Mark pool) {
        return DropMarks.facing(ledge, pool);
    }

    /**
     * Where a run stands on level {@code level}'s ledge: level 1's is the course's start spot (its own
     * facing); a later one's is its ledge mark, facing its pool and looking down into the shaft as
     * the start does.
     *
     * @return {x, y, z, yaw, pitch}, or {@code null} when the course has no such level
     */
    public static double[] stand(Course c, int level) {
        if (level <= 0) {
            Course.Spot s = c.start();
            return s == null ? null : new double[]{s.x(), s.y(), s.z(), s.yaw(), s.pitch()};
        }
        Course.Mark ledge = ledgeOf(c, level);
        Course.Mark pool = poolOf(c, level);
        if (ledge == null || pool == null) {
            return null;
        }
        return new double[]{ledge.x(), ledge.y(), ledge.z(), facing(ledge, pool), DropperPlanner.START_PITCH};
    }

    /**
     * Everything wrong with {@code c} as a dropper ({@link DropMarks#problems}); empty for any other
     * kind. It joins {@link Course#problems}, so a malformed dropper row never opens.
     */
    public static List<String> problems(Course c) {
        if (!is(c)) {
            return List.of();
        }
        return DropMarks.problems(c);
    }

    /**
     * Why course-tool {@code verb} is refused on {@code c}, or {@code null} when it isn't: a dropper
     * refuses {@link #GEOMETRY_VERBS} (EVENTS-DROPPER-SPEC §B.1.2: a kept dropper is renamed, tiered,
     * enabled, tested and featured, but its layout stays the proven one).
     */
    public static String editRefusal(Course c, String verb) {
        if (!is(c) || verb == null) {
            return null;
        }
        return GEOMETRY_VERBS.contains(verb.trim().toLowerCase(Locale.ROOT)) ? GEOMETRY_REFUSED : null;
    }
}
