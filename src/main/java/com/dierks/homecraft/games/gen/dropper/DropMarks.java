package com.dierks.homecraft.games.gen.dropper;

import com.dierks.homecraft.games.trial.Course;

import java.util.ArrayList;
import java.util.List;

/**
 * How a Dropper is written as an ordinary time-trial course (EVENTS-DROPPER-SPEC §B.1.7): no new
 * Course field and no codec change, so boards, stars, recall and keep work unchanged.
 *
 * <pre>
 * start        = level 1's ledge spot, facing the shaft
 * checkpoints  = [pool1, ledge2, pool2, ledge3, ..., pool(n-1), ledge(n)]
 * finish       = pool(n)
 * ledge mark   = the ledge's centre at feet height, radius 2.5
 * pool mark    = (pool x, surfaceY - (r - 0.5), pool z), radius r = h + 1 (h: the pool's half-width)
 * pool box     = [x +- h] x [surfaceY - 3, surfaceY + 0.5] x [z +- h]
 * fallY        = the lowest pool floor - 4
 * </pre>
 *
 * <p><b>Why the pool mark sits so low.</b> Its sphere's top is 0.5 above the water, and everywhere
 * outside the pool's column the sphere is inside solid blocks (the floor round the pool, the
 * walls), so standing on the rim or the floor never reaches it: only the water does.
 *
 * <p>The wiring's {@code trial/DropperLayout} is a thin face on this class (WIRING.md), so the
 * planner, the validator and Time Trials read one encoding.
 */
public final class DropMarks {

    /** The pool sizes a mark may name: Hard, Medium and Easy's whole floor. */
    public static final List<Integer> POOL_SIZES = List.of(5, 7, 11);

    private DropMarks() {
    }

    /** A ledge's mark: its centre (x, z) at the ledge top. */
    public static Course.Mark ledge(double x, double ledgeTop, double z) {
        return new Course.Mark(x, ledgeTop, z, DropperGeometry.LEDGE_RADIUS);
    }

    /** A pool's mark: its centre (x, z), its water surface, its size a side. */
    public static Course.Mark pool(double x, int surfaceY, double z, int size) {
        double r = size / 2.0 + 1;
        return new Course.Mark(x, surfaceY - (r - 0.5), z, r);
    }

    /** A pool mark's half-width h. */
    public static double halfWidth(Course.Mark pool) {
        return pool.radius() - 1;
    }

    /** A pool mark's water surface. */
    public static double surface(Course.Mark pool) {
        return pool.y() + pool.radius() - 0.5;
    }

    /** A pool mark's floor row: the block row under the water. */
    public static int floorRow(Course.Mark pool) {
        return (int) Math.round(surface(pool)) - DropperGeometry.POOL_DEPTH - 1;
    }

    /**
     * The pool box a mark names, {x1, y1, z1, x2, y2, z2}: the water's column from its bottom to half
     * a block over the surface. A splash is the first move that enters it.
     */
    public static double[] poolBox(Course.Mark pool) {
        double h = halfWidth(pool);
        double s = surface(pool);
        return new double[]{pool.x() - h, s - DropperGeometry.POOL_DEPTH, pool.z() - h, pool.x() + h, s + 0.5,
                pool.z() + h};
    }

    /** Whether a point is inside a pool mark's box. */
    public static boolean inPool(Course.Mark pool, double x, double y, double z) {
        double[] b = poolBox(pool);
        return x >= b[0] && x <= b[3] && y >= b[1] && y <= b[4] && z >= b[2] && z <= b[5];
    }

    // ---- reading a course -------------------------------------------------------------------------

    /** How many levels a dropper course has: its checkpoints come in pairs. */
    public static int levels(Course c) {
        return c.checkpoints().size() / 2 + 1;
    }

    /** Level {@code level}'s (0-based) ledge mark; level 0's is made from the start spot. */
    public static Course.Mark ledgeOf(Course c, int level) {
        if (level == 0) {
            Course.Spot s = c.start();
            return s == null ? null : ledge(s.x(), s.y(), s.z());
        }
        int i = 2 * level - 1;
        return i < c.checkpoints().size() ? c.checkpoints().get(i) : null;
    }

    /** Level {@code level}'s (0-based) pool mark; the last level's is the finish. */
    public static Course.Mark poolOf(Course c, int level) {
        int n = levels(c);
        if (level == n - 1) {
            return c.finish();
        }
        int i = 2 * level;
        return i < c.checkpoints().size() ? c.checkpoints().get(i) : null;
    }

    /** Whether target {@code index} of {@link Course#targets()} is a pool (else a ledge). */
    public static boolean isPool(int index) {
        return index % 2 == 0;
    }

    /** The level (0-based) target {@code index} belongs to. */
    public static int levelOf(int index) {
        return isPool(index) ? index / 2 : (index + 1) / 2;
    }

    /**
     * The level a run is on once it has reached target {@code lastReached} (-1: nothing yet): a pool
     * reached means the hop to the next level's ledge.
     */
    public static int currentLevel(int lastReached) {
        return lastReached < 0 ? 0 : (lastReached + 2) / 2;
    }

    /** Where a bonk sends a run that has reached target {@code lastReached}: its current level's ledge. */
    public static Course.Mark backTo(Course c, int lastReached) {
        return ledgeOf(c, Math.min(currentLevel(lastReached), levels(c) - 1));
    }

    /**
     * Minecraft's yaw (0 = south, 90 = west) facing from a ledge toward its pool, for the hop onto a
     * later level's ledge (its mark carries no facing).
     */
    public static float facing(Course.Mark ledge, Course.Mark pool) {
        return yaw(pool.x() - ledge.x(), pool.z() - ledge.z());
    }

    /** Minecraft's yaw for a heading (dx, dz). */
    public static float yaw(double dx, double dz) {
        double y = StrictMath.toDegrees(StrictMath.atan2(-dx, dz));
        return (float) (y < 0 ? y + 360 : y);
    }

    /**
     * One block the live check looks at (§B.1.9's {@code LiveProof.structure} for a dropper): the
     * lime block under a ledge mark, or the water block at a pool's surface centre.
     *
     * @param water true for a pool's water, false for a ledge's solid block
     */
    public record Probe(int x, int y, int z, boolean water) {
    }

    /**
     * The blocks that prove a dropper still stands, level by level: a solid block under every ledge
     * and water at every pool's surface centre. Empty for a course that has no start or finish.
     */
    public static List<Probe> probes(Course c) {
        List<Probe> out = new ArrayList<>();
        if (c == null || c.start() == null || c.finish() == null) {
            return out;
        }
        for (int level = 0; level < levels(c); level++) {
            Course.Mark ledge = ledgeOf(c, level);
            Course.Mark pool = poolOf(c, level);
            if (ledge != null) {
                out.add(new Probe((int) Math.floor(ledge.x()), (int) Math.rint(ledge.y()) - 1,
                        (int) Math.floor(ledge.z()), false));
            }
            if (pool != null) {
                out.add(new Probe((int) Math.floor(pool.x()), (int) Math.rint(surface(pool)) - 1,
                        (int) Math.floor(pool.z()), true));
            }
        }
        return out;
    }

    /**
     * The mix a dropper course was made from, read back from its pools (11 a side is Easy, 7 Medium,
     * 5 Hard): what the validator needs for a plan that arrives without its slot's mix, a recalled or
     * kept one. {@code null} when a pool names no tier or the course isn't a dropper.
     */
    public static String mix(Course c) {
        if (c == null || c.start() == null || c.finish() == null || c.checkpoints().size() % 2 != 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int level = 0; level < levels(c); level++) {
            Course.Mark pool = poolOf(c, level);
            double side = 2 * halfWidth(pool);
            DropRules.Level tier = null;
            for (DropRules.Level l : DropRules.Level.values()) {
                if (Math.abs(side - l.pool()) < 1e-9) {
                    tier = l;
                }
            }
            if (tier == null) {
                return null;
            }
            sb.append(tier.letter());
        }
        return DropRules.mixProblem(sb.toString()) == null ? sb.toString() : null;
    }

    /**
     * What is wrong with {@code c} as a dropper course, in admin words; empty when nothing is. It
     * joins {@code Course.problems} for a dropper row (WIRING.md), so a malformed row never opens.
     */
    public static List<String> problems(Course c) {
        List<String> out = new ArrayList<>();
        if (c.start() == null || c.finish() == null) {
            out.add("a dropper needs its first ledge (the start) and its last pool (the finish)");
            return out;
        }
        int size = c.checkpoints().size();
        if (size % 2 != 0) {
            out.add("a dropper's checkpoints come in pairs (a pool, then the next ledge): it has " + size);
            return out;
        }
        int n = levels(c);
        if (n > DropRules.MAX_LEVELS) {
            out.add("a dropper has at most " + DropRules.MAX_LEVELS + " levels: this one has " + n);
        }
        Double lowestFloor = null;
        for (int level = 0; level < n; level++) {
            Course.Mark ledge = ledgeOf(c, level);
            Course.Mark pool = poolOf(c, level);
            String name = "level " + (level + 1);
            if (level > 0 && Math.abs(ledge.radius() - DropperGeometry.LEDGE_RADIUS) > 1e-9) {
                out.add(name + "'s ledge mark has radius " + ledge.radius() + ", not "
                        + DropperGeometry.LEDGE_RADIUS + " (marks out of order?)");
            }
            double h2 = 2 * halfWidth(pool);
            if (Math.abs(h2 - Math.rint(h2)) > 1e-9 || !POOL_SIZES.contains((int) Math.rint(h2))) {
                out.add(name + "'s pool mark has radius " + pool.radius() + ", which names no pool size "
                        + POOL_SIZES + " (marks out of order?)");
                continue;
            }
            double s = surface(pool);
            if (Math.abs(s - Math.rint(s)) > 1e-9) {
                out.add(name + "'s pool mark puts the water at y " + s + ", not on a block");
            }
            if (ledge.y() - s < DropRules.MIN_DROP) {
                out.add(name + "'s pool is " + (ledge.y() - s) + " below its ledge; at least " + DropRules.MIN_DROP);
            }
            if (Math.abs(pool.x() - ledge.x()) > DropperGeometry.INSIDE
                    || Math.abs(pool.z() - ledge.z()) > DropperGeometry.INSIDE) {
                out.add(name + "'s pool isn't under its ledge");
            }
            double floor = floorRow(pool);
            lowestFloor = lowestFloor == null ? floor : Math.min(lowestFloor, floor);
        }
        if (c.fallY() == null) {
            out.add("a dropper needs its fall height (under every pool)");
        } else if (lowestFloor != null && c.fallY() >= lowestFloor) {
            out.add("the fall height " + c.fallY() + " isn't under every pool's floor (the lowest is at "
                    + lowestFloor + ")");
        }
        return out;
    }
}
