package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.golf.GolfCourse;
import com.dierks.homecraft.games.trial.Course;

/**
 * Where a watcher may fly while watching a race or a golf group live (CLUBHOUSE-SPEC §10): the course
 * and a little room round it, and nowhere else. It works on every course with nothing built:
 *
 * <ul>
 *   <li>a Fresh, Classic or kept course: its slot's (or plot's) box, grown by {@value #GEN_GROW};</li>
 *   <li>a hand-built course (and a Race Night track that is one): the box round its start, its
 *       checkpoints and its finish, grown by {@value #HAND_GROW};</li>
 *   <li>a hand-built golf course: the box round every hole's tee, cup and bounds, grown by
 *       {@value #HAND_GROW}.</li>
 * </ul>
 * A Dropper's area holds its shafts, so a watcher can look in; a boat track's holds its whole loop.
 * Pure: made from values, tested for every kind of course.
 *
 * @param box   the area (feet anywhere inside it)
 * @param viewX where a watcher is first put: above the start, looking along it
 */
public record WatchArea(Box box, double viewX, double viewY, double viewZ, float viewYaw) {

    /** How much a generated course's slot or plot box is grown. */
    public static final int GEN_GROW = 8;
    /** How much a hand-built course's box is grown. */
    public static final int HAND_GROW = 16;
    /** A watcher is first put this high above the start. */
    public static final int VIEW_UP = 4;

    public WatchArea {
        if (box == null) {
            throw new IllegalArgumentException("a watch area needs its box");
        }
    }

    /**
     * A time-trial course's area (any kind: parkour, Sky Rings, a boat track, the Dropper).
     *
     * @param half its Fresh Courses slot's (or plot's) box when it is generated and the engine knows it,
     *             else {@code null}
     */
    public static WatchArea forCourse(Course c, Box half) {
        if (c == null || c.start() == null) {
            throw new IllegalArgumentException("a course with a start");
        }
        Box area;
        if (half != null) {
            area = half.expand(GEN_GROW);
        } else {
            int[] b = bounds(c.start().x(), c.start().y(), c.start().z());
            for (Course.Mark m : c.checkpoints()) {
                grow(b, m.x(), m.y(), m.z());
            }
            if (c.finish() != null) {
                grow(b, c.finish().x(), c.finish().y(), c.finish().z());
            }
            area = new Box(b[0], b[1], b[2], b[3], b[4], b[5]).expand(HAND_GROW);
        }
        return view(area, c.start().x(), c.start().y(), c.start().z(), c.start().yaw());
    }

    /** A golf course's area: its slot's box when generated, else round every hole. */
    public static WatchArea forGolf(GolfCourse g, Box half) {
        if (g == null || g.holes().isEmpty()) {
            throw new IllegalArgumentException("a golf course with a hole");
        }
        GolfCourse.Tee first = g.holes().get(0).tee();
        Box area;
        if (half != null) {
            area = half.expand(GEN_GROW);
        } else {
            int[] b = bounds(first.x(), first.y(), first.z());
            for (GolfCourse.Hole h : g.holes()) {
                grow(b, h.tee().x(), h.tee().y(), h.tee().z());
                if (h.cup() != null) {
                    grow(b, h.cup().x(), h.cup().y(), h.cup().z());
                }
                if (h.corner1() != null) {
                    grow(b, h.corner1().x(), h.corner1().y(), h.corner1().z());
                }
                if (h.corner2() != null) {
                    grow(b, h.corner2().x(), h.corner2().y(), h.corner2().z());
                }
            }
            area = new Box(b[0], b[1], b[2], b[3], b[4], b[5]).expand(HAND_GROW);
        }
        return view(area, first.x(), first.y(), first.z(), first.yaw());
    }

    /** An area with its first view above (x, y, z), kept inside the area. */
    static WatchArea view(Box area, double x, double y, double z, float yaw) {
        double[] v = nearest(area, x, y + VIEW_UP, z);
        return new WatchArea(area, v[0], v[1], v[2], yaw);
    }

    /** Whether feet at (x, y, z) are inside. */
    public boolean contains(double x, double y, double z) {
        return box.contains(x, y, z);
    }

    /** The nearest point inside the area to (x, y, z) (itself when it is inside). */
    public double[] nearestInside(double x, double y, double z) {
        return nearest(box, x, y, z);
    }

    private static double[] nearest(Box b, double x, double y, double z) {
        double eps = 0.3;
        return new double[]{clamp(x, b.minX() + eps, b.maxX() + 1 - eps), clamp(y, b.minY(), b.maxY() + 1 - 2),
                clamp(z, b.minZ() + eps, b.maxZ() + 1 - eps)};
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static int[] bounds(double x, double y, double z) {
        int bx = (int) Math.floor(x);
        int by = (int) Math.floor(y);
        int bz = (int) Math.floor(z);
        return new int[]{bx, by, bz, bx, by, bz};
    }

    private static void grow(int[] b, double x, double y, double z) {
        int bx = (int) Math.floor(x);
        int by = (int) Math.floor(y);
        int bz = (int) Math.floor(z);
        b[0] = Math.min(b[0], bx);
        b[1] = Math.min(b[1], by);
        b[2] = Math.min(b[2], bz);
        b[3] = Math.max(b[3], bx);
        b[4] = Math.max(b[4], by);
        b[5] = Math.max(b[5], bz);
    }
}
