package com.dierks.homecraft.games.event;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.trial.BoatHype;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.Point;
import com.dierks.homecraft.games.trial.RaceStand;
import com.dierks.homecraft.games.trial.TrialKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A track as Race Night sees it (EVENTS-DROPPER-SPEC §A.4.1-§A.4.4): its laps, the course each
 * race is judged on, an admin's grid spots and its viewing stand. Pure: no Bukkit.
 *
 * <p><b>Laps.</b> A <i>loop</i> has its finish within max(finish radius, 6) of its start and at
 * least 3 checkpoints; its natural laps are how many times its checkpoint list repeats (Fresh Boat
 * stores two). {@code laps: 0} races the course's own laps; another count (1-5) needs a loop and at
 * most {@value Course#MAX_CHECKPOINTS} targets. The raced course starts at the racer's grid spot,
 * and its shortest time scales with the laps.
 *
 * <p><b>The grid.</b> An admin's stored grid (at most {@value #MAX_GRID} spots, {@value #SPACING}
 * apart, behind the start and within {@value #GRID_REACH} of it) wins while the course's layout is
 * the one it was set for; otherwise the automatic grid, which is Time Trials' own
 * ({@code trial/RaceGrid}: rows of two behind the start, nudged round a wall, single file when rows
 * of two can't seat enough), the same one party races use.
 *
 * <p><b>The stand.</b> A Fresh Boat layout of algo 2 or later has one built in, where Time Trials'
 * {@code RaceStand} says (the half's centre block, 5 above the start; on a Mountain Run v2, at the bottom,
 * 5 above the finish). A hand-built track needs an
 * admin's ({@value #STAND_CLEAR} or more from the racing line). Without a stand a night has one race
 * and finishers go home at the line.
 */
public final class RaceTrack {

    /** The most grid spots an admin may set. */
    public static final int MAX_GRID = 8;
    /** Grid spots at least this far apart. */
    public static final double SPACING = 2.5;
    /** An admin's grid spot within this far of the start. */
    public static final double GRID_REACH = 24;
    /** A stand at least this far from the racing line. */
    public static final double STAND_CLEAR = 10;
    /** A loop's finish is within this (or its radius) of its start. */
    static final double LOOP_GAP = 6;

    private RaceTrack() {
    }

    // ---- laps -----------------------------------------------------------------------------------

    /** Whether a course is a loop: its finish near its start, and at least 3 checkpoints. */
    public static boolean loop(Course c) {
        if (c == null || c.start() == null || c.finish() == null || c.checkpoints().size() < 3) {
            return false;
        }
        double gap = Math.max(c.finish().radius(), LOOP_GAP);
        return c.start().point().distance(c.finish().center()) <= gap;
    }

    /** The smallest p for which the checkpoints are their first p repeated (within 0.01 blocks). */
    public static int period(List<Course.Mark> marks) {
        int n = marks.size();
        for (int p = 1; p < n; p++) {
            if (n % p != 0) {
                continue;
            }
            boolean repeats = true;
            for (int i = p; i < n && repeats; i++) {
                repeats = same(marks.get(i), marks.get(i % p));
            }
            if (repeats) {
                return p;
            }
        }
        return n;
    }

    /** The course's own laps: how often a loop's checkpoints repeat; 1 for anything else. */
    public static int naturalLaps(Course c) {
        if (!loop(c)) {
            return 1;
        }
        return Math.max(1, c.checkpoints().size() / Math.max(1, period(c.checkpoints())));
    }

    /** The laps a night on {@code c} races with {@code laps} configured (0 = the course's own). */
    public static int laps(Course c, int laps) {
        return laps <= 0 || lapsProblem(c, laps) != null ? naturalLaps(c) : laps;
    }

    /** Why {@code c} can't be raced over {@code laps} laps, or {@code null}. */
    public static String lapsProblem(Course c, int laps) {
        if (laps <= 0 || laps == naturalLaps(c)) {
            return null;
        }
        if (!loop(c)) {
            return c.name() + " isn't a loop, so it can't have laps";
        }
        int perLap = period(c.checkpoints());
        if (perLap * laps + 1 > Course.MAX_CHECKPOINTS) {
            return laps + " laps of " + c.name() + " would be more than " + Course.MAX_CHECKPOINTS + " checkpoints";
        }
        return null;
    }

    /**
     * The course a racer races: the base course from their grid spot, over {@code laps} laps (0 = its
     * own), with its shortest time scaled to the laps.
     */
    public static Course raced(Course base, Course.Spot grid, int laps) {
        Course c = grid == null ? base : base.withStart(grid);
        int natural = naturalLaps(base);
        int want = laps(base, laps);
        if (want == natural) {
            return c;
        }
        List<Course.Mark> one = base.checkpoints().subList(0, period(base.checkpoints()));
        List<Course.Mark> marks = new ArrayList<>();
        for (int i = 0; i < want; i++) {
            marks.addAll(one);
        }
        Course out = c.withCheckpoints(marks);
        if (base.minSeconds() != null) {
            out = out.withMinSeconds(Math.max(1, base.minSeconds() * want / Math.max(1, natural)));
        }
        return out;
    }

    /** The horizontal unit vector a Minecraft yaw faces (0 is +z, 90 is -x). */
    static double[] facing(float yaw) {
        double r = Math.toRadians(yaw);
        return new double[]{-Math.sin(r), Math.cos(r)};
    }

    // ---- raceable ------------------------------------------------------------------------------

    /**
     * What a night says of a Mountain Run v2 Slalom, after the course's name (red-team F05: racing is the
     * Winding Road, the Slalom is for solo runs; a Slalom week with Race Night on comes only from an admin's
     * seed, {@code style: slalom}, or Race Night switched on after the week's course was made).
     */
    public static final String SLALOM = "is the Slalom this week, and Race Night races only on the Winding Road";

    /**
     * Why a night can't race on {@code c} with {@code spots} grid spots, or {@code null} (§A.4.1): it
     * must be a boat course with a start, not a Mountain Run v2 Slalom ({@link #SLALOM}), and seat at least
     * {@code minRacers}.
     */
    public static String raceProblem(Course c, int spots, int minRacers) {
        if (c == null) {
            return "there's no such course";
        }
        if (c.kind() != TrialKind.BOAT) {
            return c.name() + " isn't a boat course";
        }
        if (c.start() == null) {
            return c.name() + " has no start";
        }
        if (BoatHype.slalom(c)) {
            return c.name() + " " + SLALOM + " - a reroll makes it a Winding Road (/hcm games gen reroll "
                    + c.id() + " confirm)";
        }
        if (spots < minRacers) {
            return c.name() + "'s starting grid seats " + spots + " - Race Night needs " + minRacers
                    + " (try /hcm games event grid " + c.id() + " auto)";
        }
        return null;
    }

    /**
     * Whether someone this far from the stand's spot has wandered off it (§A.4.2): more than
     * {@code radius} away sideways, or more than {@code radius} + 2 up or down.
     */
    public static boolean offStand(double dx, double dy, double dz, int radius) {
        return Math.hypot(dx, dz) > radius || Math.abs(dy) > radius + 2;
    }

    /** The races a night holds on a track: as asked with a stand, else 1 (finishers go home at the line). */
    public static int races(int want, Point stand) {
        return stand == null ? 1 : Math.max(1, want);
    }

    // ---- an admin's grid and stand --------------------------------------------------------------

    /** Why {@code spot} can't join the admin grid {@code grid} of {@code c}, or {@code null}. */
    public static String gridProblem(Course c, List<Course.Spot> grid, Course.Spot spot) {
        if (c.start() == null) {
            return c.name() + " has no start yet";
        }
        if (grid.size() >= MAX_GRID) {
            return "a grid has at most " + MAX_GRID + " spots";
        }
        Point p = spot.point();
        Point start = c.start().point();
        if (p.flatDistance(start) > GRID_REACH) {
            return "that spot is more than " + (int) GRID_REACH + " blocks from the start";
        }
        double[] f = facing(c.start().yaw());
        double ahead = (p.x() - start.x()) * f[0] + (p.z() - start.z()) * f[1];
        if (ahead > 0.5) {
            return "that spot is in front of the start - the grid goes behind it";
        }
        for (Course.Spot s : grid) {
            if (s.point().flatDistance(p) < SPACING) {
                return "that spot is closer than " + SPACING + " blocks to another grid spot";
            }
        }
        return null;
    }

    /** The racing line: the start, the checkpoints and the finish, in order. */
    static List<Point> line(Course c, List<Course.Spot> grid) {
        List<Point> out = new ArrayList<>();
        for (Course.Spot s : grid) {
            out.add(s.point());
        }
        if (c.start() != null) {
            out.add(c.start().point());
        }
        for (Course.Mark m : c.targets()) {
            out.add(m.center());
        }
        return out;
    }

    /** Why a stand at {@code p} is too near the racing line of {@code c}, or {@code null}. */
    public static String standProblem(Course c, List<Course.Spot> grid, Point p) {
        List<Point> line = line(c, grid);
        double nearest = Double.MAX_VALUE;
        for (int i = 0; i < line.size(); i++) {
            nearest = Math.min(nearest, line.get(i).flatDistance(p));
            if (i + 1 < line.size()) {
                nearest = Math.min(nearest, segment(p, line.get(i), line.get(i + 1)));
            }
        }
        return nearest < STAND_CLEAR ? "the stand must be at least " + (int) STAND_CLEAR
                + " blocks from the racing line (it is " + Math.round(nearest * 10) / 10.0 + ")" : null;
    }

    /** The horizontal distance from {@code p} to the segment {@code a}-{@code b}. */
    static double segment(Point p, Point a, Point b) {
        double dx = b.x() - a.x();
        double dz = b.z() - a.z();
        double len2 = dx * dx + dz * dz;
        double t = len2 <= 1e-9 ? 0 : ((p.x() - a.x()) * dx + (p.z() - a.z()) * dz) / len2;
        t = Math.max(0, Math.min(1, t));
        double cx = a.x() + dx * t;
        double cz = a.z() + dz * t;
        return Math.hypot(p.x() - cx, p.z() - cz);
    }

    /**
     * A Fresh Boat layout's built-in stand, where the boat planner builds it ({@code RaceStand.of}:
     * the half's centre block, 5 above the start, for algo 2 or 3; at the bottom, 5 above the finish, for a
     * Mountain Run v2); {@code null} for anything else. Whether it really stands in the world is the
     * caller's check ({@code RaceStand.standable}).
     */
    public static Point freshStand(Course c, Box half) {
        return RaceStand.of(c, half);
    }

    // ---- how an admin's grid and stand are kept (hcm_meta race.grid.<course>, race.stand.<course>) ------

    /** A stored grid: {@code <layoutHash>|x,y,z,yaw;x,y,z,yaw}. */
    public static String encodeGrid(int layout, List<Course.Spot> spots) {
        StringBuilder b = new StringBuilder().append(layout).append('|');
        for (int i = 0; i < spots.size(); i++) {
            Course.Spot s = spots.get(i);
            if (i > 0) {
                b.append(';');
            }
            b.append(num(s.x())).append(',').append(num(s.y())).append(',').append(num(s.z())).append(',')
                    .append(num(s.yaw()));
        }
        return b.toString();
    }

    /** A stored grid's spots while {@code layout} is still the one it was set for; else {@code null}. */
    public static List<Course.Spot> decodeGrid(String stored, int layout) {
        String body = body(stored, layout);
        if (body == null) {
            return null;
        }
        List<Course.Spot> out = new ArrayList<>();
        if (body.isBlank()) {
            return out;
        }
        try {
            for (String part : body.split(";")) {
                String[] v = part.split(",");
                out.add(new Course.Spot(Double.parseDouble(v[0]), Double.parseDouble(v[1]), Double.parseDouble(v[2]),
                        Float.parseFloat(v[3]), 0));
            }
        } catch (RuntimeException e) {
            return null;
        }
        return out;
    }

    /** A stored stand: {@code <layoutHash>|x,y,z}. */
    public static String encodeStand(int layout, Point p) {
        return layout + "|" + num(p.x()) + "," + num(p.y()) + "," + num(p.z());
    }

    /** A stored stand while {@code layout} is still the one it was set for; else {@code null}. */
    public static Point decodeStand(String stored, int layout) {
        String body = body(stored, layout);
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            String[] v = body.split(",");
            return new Point(Double.parseDouble(v[0]), Double.parseDouble(v[1]), Double.parseDouble(v[2]));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Whether a stored value was set for another layout (so it is dropped, with a WARN). */
    public static boolean stale(String stored, int layout) {
        return stored != null && body(stored, layout) == null;
    }

    private static String body(String stored, int layout) {
        if (stored == null) {
            return null;
        }
        int bar = stored.indexOf('|');
        if (bar < 0) {
            return null;
        }
        try {
            return Integer.parseInt(stored.substring(0, bar)) == layout ? stored.substring(bar + 1) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String num(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static boolean same(Course.Mark a, Course.Mark b) {
        return Math.abs(a.x() - b.x()) <= 0.01 && Math.abs(a.y() - b.y()) <= 0.01 && Math.abs(a.z() - b.z()) <= 0.01
                && Math.abs(a.radius() - b.radius()) <= 0.01;
    }

    /**
     * Pick a track for {@code course: auto}: taking turns through the raceable ids (sorted), chosen
     * by the night's id so it is stable and never random at the moment of the night.
     */
    public static String pick(List<String> raceable, String eventId) {
        if (raceable == null || raceable.isEmpty()) {
            return null;
        }
        List<String> sorted = new ArrayList<>(raceable);
        sorted.sort(null);
        int h = eventId == null ? 0 : eventId.hashCode();
        return sorted.get(Math.floorMod(h, sorted.size()));
    }
}
