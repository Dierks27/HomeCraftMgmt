package com.dierks.homecraft.games.trial;

import java.util.ArrayList;
import java.util.List;

/**
 * The starting grid of a race (EVENTS-DROPPER-SPEC §A.4.3), pure and tested on a fake
 * {@link Surface}.
 *
 * <p><b>Why it reads the track's blocks.</b> A course stores its start and checkpoints, not its
 * walls, so the only way to know where a boat fits beside another is to look. {@link #plan} walks
 * back from the start along the way the racers came ({@link #path}: on a loop, a smooth curve back
 * through the last checkpoints; on a point-to-point course, straight back along the start's facing)
 * and tries spots in rows of two, {@value #SIDE} blocks either side of it, {@value #ROW_GAP} blocks
 * apart. A spot needs a floor a boat sits on (ice, water or a solid top), two blocks of air, at
 * least {@value #WALL_GAP} block to any wall, and nothing solid between it and the start. A spot
 * that fails is nudged sideways, up to {@value #NUDGE} blocks in {@value #NUDGE_STEP} steps. When
 * rows of two can't seat everyone the grid is single file, {@value #SINGLE_GAP} apart. About 60
 * block reads, on the main thread.
 *
 * <p><b>Runners share the start.</b> Players on foot or with wings can't push each other (the
 * session guard stops knockback), and a parkour start is often a small platform, so on a parkour
 * or elytra course everyone starts on the start spot itself ({@link Mode#SHARED}).
 *
 * <p><b>The order is published, never drawn.</b> The first spot is pole; the caller decides who
 * gets it (Race Night: fewest points first; a party race: join order).
 *
 * <p>An admin may also store a grid by hand; {@link #addProblem} says whether a spot may be added,
 * and {@link #stored} drops a stored grid once the course's layout changes.
 */
public final class RaceGrid {

    /** Each spot of a row of two is this far either side of the path. */
    public static final double SIDE = 1.5;
    /** Rows of two are this far apart along the path. */
    public static final double ROW_GAP = 4;
    /** Single file: this far apart. */
    public static final double SINGLE_GAP = 5;
    /** A spot keeps at least this much room to any wall. */
    public static final double WALL_GAP = 1.0;
    /** A failing spot is nudged sideways up to this far... */
    public static final double NUDGE = 2.0;
    /** ...in steps this big. */
    public static final double NUDGE_STEP = 0.5;
    /** Two spots are at least this far apart. */
    public static final double MIN_APART = 2.5;
    /** The grid looks no further back than this along the path. */
    public static final double REACH = 48;
    /** A hand-set spot is within this far of the start. */
    public static final double HAND_REACH = 24;
    /** The most spots a hand-set grid may have. */
    public static final int HAND_MAX = 8;
    /** The most spots any grid has: Race Night's and a party's hardest limit. */
    public static final int MAX_SPOTS = 12;
    /** The smooth path back is sampled this often. */
    static final double SAMPLE = 0.5;

    private RaceGrid() {
    }

    /** What a block is to a boat or a runner. */
    public enum Cell {
        /** Nothing in the way (air, and anything you can pass through). */
        AIR,
        /** Water: a boat floats on it and through it. */
        WATER,
        /** Anything solid: ice to race on at the floor, a wall at boat height. */
        SOLID
    }

    /** The live blocks the grid reads (the main thread reads the world; a test reads a map). */
    @FunctionalInterface
    public interface Surface {

        /** What block (x, y, z) of the course's world is. */
        Cell at(int x, int y, int z);
    }

    /** How the grid seats its racers. */
    public enum Mode {
        /** Rows of two either side of the path. */
        DOUBLE,
        /** Single file along the path. */
        SINGLE,
        /** Everyone on the start spot itself (runners and flyers). */
        SHARED,
        /** Set by an admin. */
        HAND
    }

    /**
     * A grid.
     *
     * @param spots every spot, pole first, each facing along the path
     * @param mode  how it was laid out
     * @param notes why spots failed (for {@code event grid <course> auto}), in admin words
     */
    public record Grid(List<Course.Spot> spots, Mode mode, List<String> notes) {

        public Grid {
            spots = List.copyOf(spots);
            notes = List.copyOf(notes);
        }

        /** How many racers it seats. */
        public int size() {
            return spots.size();
        }

        /** Spot {@code i} (0 = pole). */
        public Course.Spot spot(int i) {
            return spots.get(i);
        }
    }

    // ---- the grid for a course ----------------------------------------------------------------

    /**
     * The grid for {@code n} racers on {@code c}: on a boat course read from {@code surface} (rows of
     * two, else single file, as many spots as fit up to {@code n}); on any other course, everyone on
     * the start ({@link Mode#SHARED}).
     */
    public static Grid forCourse(Course c, Surface surface, int n) {
        int want = Math.max(0, Math.min(MAX_SPOTS, n));
        if (c.start() == null) {
            return new Grid(List.of(), Mode.SHARED, List.of("the course has no start"));
        }
        if (c.kind() != TrialKind.BOAT) {
            List<Course.Spot> all = new ArrayList<>(want);
            for (int i = 0; i < want; i++) {
                all.add(c.start());
            }
            return new Grid(all, Mode.SHARED, List.of());
        }
        return plan(path(c), c.start().y(), surface, want);
    }

    /**
     * The path the racers came along, going backwards from the start (first point = the start),
     * sampled every {@value #SAMPLE} blocks. On a {@link Laps#loop}: a smooth curve (Catmull-Rom)
     * back through the lap's checkpoints, last first. Otherwise straight back along the start's
     * facing, {@value #REACH} blocks. All at the start's height.
     */
    public static List<Point> path(Course c) {
        Course.Spot s = c.start();
        double y = s.y();
        if (Laps.loop(c)) {
            List<Point> keys = new ArrayList<>();
            keys.add(flat(c.finish().center(), y));
            keys.add(flat(s.point(), y));
            List<Course.Mark> lap = Laps.lap(c);
            for (int i = lap.size() - 1; i >= 0; i--) {
                keys.add(flat(lap.get(i).center(), y));
            }
            keys.add(flat(c.finish().center(), y));
            return spline(keys, y);
        }
        double rad = Math.toRadians(s.yaw());
        // Minecraft's yaw: facing (-sin, cos); backwards is the opposite
        double bx = Math.sin(rad);
        double bz = -Math.cos(rad);
        List<Point> out = new ArrayList<>();
        for (double d = 0; d <= REACH + 1e-9; d += SAMPLE) {
            out.add(new Point(s.x() + bx * d, y, s.z() + bz * d));
        }
        return out;
    }

    /**
     * Rows of two along {@code path}, else single file: at most {@code n} spots at height {@code y},
     * each facing forward along the path (toward the start), read from {@code surface}.
     */
    public static Grid plan(List<Point> path, double y, Surface surface, int n) {
        int want = Math.max(0, Math.min(MAX_SPOTS, n));
        List<String> notes = new ArrayList<>();
        Along along = new Along(path);
        List<Course.Spot> two = new ArrayList<>();
        for (int row = 0; two.size() < want && row * ROW_GAP <= Math.min(REACH, along.length()); row++) {
            for (int side = -1; side <= 1 && two.size() < want; side += 2) {
                Course.Spot spot = fit(along, row * ROW_GAP, side * SIDE, y, surface, two, notes);
                if (spot != null) {
                    two.add(spot);
                }
            }
        }
        if (two.size() >= want) {
            return new Grid(two, Mode.DOUBLE, notes);
        }
        List<Course.Spot> one = new ArrayList<>();
        List<String> singleNotes = new ArrayList<>();
        for (int k = 0; one.size() < want && k * SINGLE_GAP <= Math.min(REACH, along.length()); k++) {
            Course.Spot spot = fit(along, k * SINGLE_GAP, 0, y, surface, one, singleNotes);
            if (spot != null) {
                one.add(spot);
            }
        }
        notes.addAll(singleNotes);
        return one.size() > two.size() ? new Grid(one, Mode.SINGLE, notes) : new Grid(two, Mode.DOUBLE, notes);
    }

    /**
     * The spot {@code back} blocks behind the start and {@code side} blocks to its side, nudged
     * sideways until it fits and is {@value #MIN_APART} from every spot already taken; {@code null}
     * (and a note) when no nudge fits.
     */
    private static Course.Spot fit(Along along, double back, double side, double y, Surface surface,
                                   List<Course.Spot> taken, List<String> notes) {
        Point base = along.at(back);
        double[] t = along.forward(back);
        // right of forward, across the ground
        double rx = -t[1];
        double rz = t[0];
        float yaw = Geometry.yawToward(base, new Point(base.x() + t[0], y, base.z() + t[1]));
        String why = null;
        for (double nudge : nudges()) {
            double off = side + nudge;
            Point p = new Point(base.x() + rx * off, y, base.z() + rz * off);
            String problem = problem(p, surface);
            if (problem == null && !clearToStart(p, base, along, back, y, surface)) {
                problem = "something is in the way to the start";
            }
            if (problem == null && tooClose(p, taken)) {
                problem = "too close to another spot";
            }
            if (problem == null) {
                return new Course.Spot(p.x(), y, p.z(), yaw, 0f);
            }
            if (why == null) {
                why = problem;
            }
        }
        notes.add(String.format(java.util.Locale.ROOT, "%.0f back, %s: %s", back,
                side == 0 ? "middle" : side < 0 ? "left" : "right", why));
        return null;
    }

    /** 0, then +0.5, -0.5, +1, -1 ... up to ±{@value #NUDGE}. */
    static double[] nudges() {
        int steps = (int) Math.round(NUDGE / NUDGE_STEP);
        double[] out = new double[1 + 2 * steps];
        for (int i = 1; i <= steps; i++) {
            out[2 * i - 1] = i * NUDGE_STEP;
            out[2 * i] = -i * NUDGE_STEP;
        }
        return out;
    }

    /**
     * Why a boat can't sit at {@code p}, or {@code null} when it can: a floor (ice, water or a solid
     * top) under it, two blocks of air (or water, then air) where it sits, and at least
     * {@value #WALL_GAP} block to anything solid at boat height.
     */
    public static String problem(Point p, Surface surface) {
        int bx = (int) Math.floor(p.x());
        int by = (int) Math.floor(p.y());
        int bz = (int) Math.floor(p.z());
        Cell here = surface.at(bx, by, bz);
        Cell below = surface.at(bx, by - 1, bz);
        boolean floor = below == Cell.SOLID || below == Cell.WATER || here == Cell.WATER;
        if (!floor) {
            return "no ice, water or floor under it";
        }
        if (here == Cell.SOLID || surface.at(bx, by + 1, bz) != Cell.AIR) {
            return "no room for a boat";
        }
        int reach = (int) Math.ceil(WALL_GAP) + 1;
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                int x = bx + dx;
                int z = bz + dz;
                double ex = Math.max(0, Math.max(x - p.x(), p.x() - (x + 1)));
                double ez = Math.max(0, Math.max(z - p.z(), p.z() - (z + 1)));
                if (Math.sqrt(ex * ex + ez * ez) >= WALL_GAP) {
                    continue;
                }
                if (surface.at(x, by, z) == Cell.SOLID || surface.at(x, by + 1, z) == Cell.SOLID) {
                    return "too close to a wall";
                }
            }
        }
        return null;
    }

    /** Nothing solid at boat height from {@code p} across to the path, then along it to the start. */
    private static boolean clearToStart(Point p, Point base, Along along, double back, double y, Surface surface) {
        double across = p.distance(base);
        for (double d = 0; d <= across; d += SAMPLE) {
            Point q = Geometry.along(p, base, across == 0 ? 0 : d / across);
            if (blocked(q, y, surface)) {
                return false;
            }
        }
        for (double d = back; d >= 0; d -= SAMPLE) {
            if (blocked(along.at(d), y, surface)) {
                return false;
            }
        }
        return true;
    }

    private static boolean blocked(Point q, double y, Surface surface) {
        return surface.at((int) Math.floor(q.x()), (int) Math.floor(y), (int) Math.floor(q.z())) == Cell.SOLID;
    }

    private static boolean tooClose(Point p, List<Course.Spot> taken) {
        for (Course.Spot s : taken) {
            if (s.point().flatDistance(p) < MIN_APART - 1e-9) {
                return true;
            }
        }
        return false;
    }

    // ---- a grid set by hand (Race Night's `event grid <course> add|remove|clear`) ---------------

    /**
     * Why {@code spot} can't be added to a hand-set grid of {@code c} that already has {@code spots},
     * or {@code null} when it can: at most {@value #HAND_MAX} spots, each {@value #MIN_APART} from
     * the others, behind the start (not ahead of it along its facing) and within
     * {@value #HAND_REACH} blocks of it, in the course's world.
     */
    public static String addProblem(Course c, List<Course.Spot> spots, Course.Spot spot) {
        if (c.start() == null) {
            return "the course has no start";
        }
        if (spots.size() >= HAND_MAX) {
            return "a grid has at most " + HAND_MAX + " spots";
        }
        Point start = c.start().point();
        if (spot.point().distance(start) > HAND_REACH) {
            return "a spot is within " + (int) HAND_REACH + " blocks of the start";
        }
        double rad = Math.toRadians(c.start().yaw());
        double ahead = (spot.x() - start.x()) * -Math.sin(rad) + (spot.z() - start.z()) * Math.cos(rad);
        if (ahead > 0.5) {
            return "spots go behind the start, not ahead of it";
        }
        for (Course.Spot s : spots) {
            if (s.point().distance(spot.point()) < MIN_APART) {
                return "spots are at least " + MIN_APART + " blocks apart";
            }
        }
        return null;
    }

    /**
     * A grid an admin stored while the course had layout {@code layout}, as it applies to the course
     * as it is {@code now}: the spots, or {@code null} once the layout changed (or the course went),
     * so the automatic grid applies again.
     */
    public static List<Course.Spot> stored(List<Course.Spot> spots, int layout, Course now) {
        if (now == null || spots == null || spots.isEmpty() || now.layoutHash() != layout) {
            return null;
        }
        return List.copyOf(spots);
    }

    // ---- the path as a curve ------------------------------------------------------------------

    private static Point flat(Point p, double y) {
        return new Point(p.x(), y, p.z());
    }

    /**
     * A Catmull-Rom curve through {@code keys[1..n-2]} ({@code keys[0]} and the last one only shape
     * its ends), sampled every {@value #SAMPLE} blocks: close to the arc a loop's centreline really
     * follows between two checkpoints, where a straight chord would cut the corner.
     */
    static List<Point> spline(List<Point> keys, double y) {
        List<Point> dense = new ArrayList<>();
        for (int i = 1; i + 2 < keys.size(); i++) {
            Point p0 = keys.get(i - 1);
            Point p1 = keys.get(i);
            Point p2 = keys.get(i + 1);
            Point p3 = keys.get(i + 2);
            int steps = Math.max(1, (int) Math.ceil(p1.flatDistance(p2) / (SAMPLE / 4)));
            for (int k = 0; k < steps; k++) {
                double t = (double) k / steps;
                dense.add(catmull(p0, p1, p2, p3, t, y));
            }
        }
        dense.add(keys.get(keys.size() - 2));
        // resample at an even SAMPLE spacing
        List<Point> out = new ArrayList<>();
        out.add(dense.get(0));
        double carried = 0;
        for (int i = 1; i < dense.size(); i++) {
            Point a = dense.get(i - 1);
            Point b = dense.get(i);
            double seg = a.flatDistance(b);
            double pos = SAMPLE - carried;
            while (pos <= seg) {
                out.add(Geometry.along(a, b, seg == 0 ? 0 : pos / seg));
                pos += SAMPLE;
            }
            carried = seg - (pos - SAMPLE);
        }
        return out;
    }

    private static Point catmull(Point p0, Point p1, Point p2, Point p3, double t, double y) {
        double t2 = t * t;
        double t3 = t2 * t;
        double x = 0.5 * (2 * p1.x() + (-p0.x() + p2.x()) * t + (2 * p0.x() - 5 * p1.x() + 4 * p2.x() - p3.x()) * t2
                + (-p0.x() + 3 * p1.x() - 3 * p2.x() + p3.x()) * t3);
        double z = 0.5 * (2 * p1.z() + (-p0.z() + p2.z()) * t + (2 * p0.z() - 5 * p1.z() + 4 * p2.z() - p3.z()) * t2
                + (-p0.z() + 3 * p1.z() - 3 * p2.z() + p3.z()) * t3);
        return new Point(x, y, z);
    }

    /** A polyline walked by distance: where it is {@code d} along, and which way is forward there. */
    private static final class Along {

        private final List<Point> points;
        private final double[] cum;

        Along(List<Point> path) {
            this.points = path.isEmpty() ? List.of(new Point(0, 0, 0)) : List.copyOf(path);
            this.cum = new double[points.size()];
            for (int i = 1; i < points.size(); i++) {
                cum[i] = cum[i - 1] + points.get(i - 1).flatDistance(points.get(i));
            }
        }

        double length() {
            return cum[cum.length - 1];
        }

        Point at(double d) {
            if (points.size() == 1 || d <= 0) {
                return points.get(0);
            }
            for (int i = 1; i < points.size(); i++) {
                if (cum[i] >= d) {
                    double seg = cum[i] - cum[i - 1];
                    return Geometry.along(points.get(i - 1), points.get(i), seg == 0 ? 0 : (d - cum[i - 1]) / seg);
                }
            }
            return points.get(points.size() - 1);
        }

        /** The unit direction toward the start (the way racers go) at {@code d} back, across the ground. */
        double[] forward(double d) {
            Point behind = at(d + 1);
            Point ahead = at(Math.max(0, d - 1));
            double dx = ahead.x() - behind.x();
            double dz = ahead.z() - behind.z();
            double len = Math.hypot(dx, dz);
            if (len < 1e-9) {
                return new double[]{0, 1};
            }
            return new double[]{dx / len, dz / len};
        }
    }
}
