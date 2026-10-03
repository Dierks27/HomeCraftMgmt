package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.golf.GolfCourse;
import com.dierks.homecraft.games.trial.BoatHype;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.Point;
import com.dierks.homecraft.games.trial.RaceStand;

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
 * <p><b>Mountain Run v2</b> (MOUNTAIN-V2-SPEC §12, red-team F07): a watcher is first put {@value #STAND_UP}
 * over its viewing stand at the bottom, by the finish, looking north up the mountain, where the riders come
 * down to the finish. The summit is 500+ blocks away, beyond any view distance, so nothing here (or in
 * any copy) promises the whole run; a watcher can fly anywhere in the area.
 *
 * @param box   the area (feet anywhere inside it)
 * @param viewX where a watcher is first put: above the start, looking along it (a Mountain Run v2: over
 *              the stand, looking north)
 */
public record WatchArea(Box box, double viewX, double viewY, double viewZ, float viewYaw) {

    /** How much a generated course's slot or plot box is grown. */
    public static final int GEN_GROW = 8;
    /** How much a hand-built course's box is grown. */
    public static final int HAND_GROW = 16;
    /** A watcher is first put this high above the start. */
    public static final int VIEW_UP = 4;
    /** Mountain Run v2: a watcher is first put this high over the stand (where its players stand). */
    public static final int STAND_UP = 6;

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
        Point stand = half != null && BoatHype.mountainV2(c) ? RaceStand.of(c, half) : null;
        if (stand != null) {
            double[] v = nearest(area, stand.x(), stand.y() + STAND_UP, stand.z());
            return new WatchArea(area, v[0], v[1], v[2], RaceStand.FACING_V4);
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

    /**
     * This area inside a world's heights: feet from {@code minHeight}, head below {@code maxHeight}
     * (review #1: a hand-built course's box grown by 16 can reach below the world's floor, and a
     * watcher held there would be in the void). Itself when it fits already, or when the world can't
     * hold two blocks of it at all (a course outside its world, which a course never is).
     */
    public WatchArea within(int minHeight, int maxHeight) {
        int lo = Math.max(box.minY(), minHeight);
        int hi = Math.min(box.maxY(), maxHeight - 1);
        if ((lo == box.minY() && hi == box.maxY()) || hi < lo + 1) {
            return this;
        }
        Box b = new Box(box.minX(), lo, box.minZ(), box.maxX(), hi, box.maxZ());
        double[] v = nearest(b, viewX, viewY, viewZ);
        return new WatchArea(b, v[0], v[1], v[2], viewYaw);
    }

    /** What the keeper does with a watcher's move (review #1). */
    public enum Keep {
        /** Inside: the move goes ahead. */
        LET,
        /** Out of the area from inside it: the move is cancelled (they stay where they were, inside). */
        CANCEL,
        /**
         * Out of the area from outside it too (the area changed under them, say): cancelled, and the
         * session's own (armed) teleport puts them at the nearest point inside, next tick.
         */
        PULL
    }

    /**
     * The keeper's rule for a move from (fx, fy, fz) to (tx, ty, tz). Never a changed destination: on
     * the server a move event's new {@code to} is an unarmed PLUGIN teleport, which the world session
     * takes as someone else's (a short hop voids, a long one ends the session).
     */
    public Keep keep(double fx, double fy, double fz, double tx, double ty, double tz) {
        if (contains(tx, ty, tz)) {
            return Keep.LET;
        }
        return contains(fx, fy, fz) ? Keep.CANCEL : Keep.PULL;
    }

    /** Where a watcher at (x, y, z) must be put back to, or {@code null} when they are inside already. */
    public double[] putBack(double x, double y, double z) {
        return contains(x, y, z) ? null : nearestInside(x, y, z);
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
