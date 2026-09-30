package com.dierks.homecraft.games.gen.golf;

import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.GenFailed;
import com.dierks.homecraft.games.gen.api.Palette;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlannedGolf;
import com.dierks.homecraft.games.gen.api.Pools;
import com.dierks.homecraft.games.gen.api.Putt;
import com.dierks.homecraft.games.gen.api.SignText;
import com.dierks.homecraft.games.golf.BallPhysics;
import com.dierks.homecraft.games.golf.GolfCourse;
import com.dierks.homecraft.games.golf.GolfShot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Adventure Golf's independent check (Course Variety §3.8): a plan of golf planner version 3 or
 * later, read from its blocks alone, as {@link GolfValidator} does for older ones. Ponds, sand
 * bunkers, trees, hills, terraces and a volcano all mean the old "every wall exactly one block
 * above the hole's highest lane" can't hold, so walls are checked where they stand, and a ball
 * leaving a lip is covered by the flight rule instead.
 *
 * <p>Each column of a hole's bounds is lane (the ball rolls there, {@link LaneMap} v3), a pond (a
 * hazard), a ring wall, an obstacle (a solid column not joined to the bounds' edge through other
 * non-lane columns: a trunk, a tree island) or nothing; a canopy is leaves above the ball's layer.
 * Then, numbered as the spec numbers them:
 * <ol>
 *   <li><b>Cup, tee and flag</b> as before, the cup at the turf, one block up or two.</li>
 *   <li><b>Water:</b> every pond sealed ({@link Pools}), one deep at T - 1 over a solid block;
 *       every pond column in a 3 x 3 square of water (a full-power ball skims about 2.7 blocks);
 *       none beside the tee, within 2 of the cup ring, beside a slab or at the bounds' edge; every
 *       pond beside the lane, and every pond cell wades to a T-level edge a child steps out onto.
 *       Water outside every hole is a decorative pond, 2 columns clear of every hole.</li>
 *   <li><b>Leaks:</b> no lane at the bounds' edge, and none beside nothing but a pond.</li>
 *   <li><b>Ring walls, locally:</b> solid from T - 1 up, more than half a block above the highest
 *       lane beside them (diagonals too), and at most two above the lowest. And the rail: where a
 *       ball above the turf can get its edge onto a wall's lower block and ride it over lower lane
 *       or a pond, the wall at the rail's end stands more than half a block above it.</li>
 *   <li><b>The flight rule:</b> a wall or obstacle no more than half a block above a lip's surface
 *       stands further from it than a full-power ball flies before it has dropped enough for that
 *       wall to stop it ({@link LaneMap#flightReach}).</li>
 *   <li><b>Obstacles:</b> solid from T - 1 to at least a block above the highest lane beside them.</li>
 *   <li><b>Hollows</b> only at the cup and in sunken bunkers (sand slabs at T - 0.5, 2 x 2 or more).</li>
 *   <li><b>Lanes</b> from T to T + 2 (bunkers at T - 0.5), no lip over a block, no slime floor.</li>
 *   <li><b>Headroom:</b> nothing over a lane below its surface + 3, only canopy leaves above that,
 *       and no canopy within 2 columns of the tee, the cup ring or the flag.</li>
 *   <li><b>No stranding:</b> every lane cell has a way to the cup.</li>
 *   <li><b>Nobody trapped:</b> {@link GolfValidator#trapped}, unchanged.</li>
 *   <li><b>Play:</b> the witness replays in exactly E putts, par is E + 1 (at most 4); on a hole
 *       with water in play every putt of it is dry 3 degrees either side ({@link SafeExpert}); the
 *       full check runs the sloppy player's tree; and every spot a ball comes to rest, in the
 *       witness and the tree, is on the lane (a backstop to the local wall rule).</li>
 *   <li><b>Scenery</b> (decoration trees, planters, decorative ponds) inside a plot, 2 columns clear
 *       of every hole's bounds (outside the physics grid) and never above T + 6.</li>
 * </ol>
 * The whole plan also keeps to the palette and its state rules, every leaf's distance is the one
 * vanilla gives it, and every canopy is 2 columns inside the half. Pure; the fast check (no kid
 * tree) is cheap enough for the main thread.
 */
final class GolfValidatorV3 {

    /** A canopy starts at least this far above the surface of a lane under it. */
    static final int HEADROOM = 3;
    /** A ring wall's top is at most this far above the lowest lane beside it. */
    static final int WALL_ABOVE_MOST = 2;
    /** An obstacle's top is at least this far above the highest lane beside it. */
    static final int OBSTACLE_ABOVE_LEAST = 1;
    /** No canopy within this many columns (Chebyshev) of the tee, the cup ring or the flag. */
    static final int CANOPY_CLEAR = 2;
    /** No pond within this many columns (Chebyshev) of the cup ring. */
    static final int POND_CLEAR = 2;
    /** Every pond column lies in a square of water this wide. */
    static final int SKIM = 3;
    /** Scenery stands at least this many columns outside every hole's bounds. */
    static final int SCENERY_GAP = 2;
    /** Scenery never stands above T + this. */
    static final int SCENERY_TOP = 6;
    /** A canopy stands at least this many columns inside the half. */
    static final int CANOPY_INSIDE = 2;
    /** How far above T anything is looked for (a golf half's top is T + 11). */
    static final int LOOK_UP = 11;
    /** The sunken bunker: a sand slab's top, this far below T. */
    static final double BUNKER = 0.5;
    private static final double EPS = 1e-6;
    private static final int[][] FOUR = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] EIGHT = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private GolfValidatorV3() {
    }

    /** Every problem with an Adventure Golf plan; with {@code kid}, the sloppy player's tree too. */
    static List<String> problems(Plan plan, boolean kid) {
        List<String> out = new ArrayList<>();
        if (plan == null || !(plan.course() instanceof PlannedGolf golf)) {
            out.add("not a golf plan");
            return out;
        }
        Box half = plan.half();
        for (String p : Palette.problems(plan.palette())) {
            if (!Palette.poolWater(p)) { // a pond's still water: the pool rule below says where it may be
                out.add("block " + p + " isn't allowed");
            }
        }
        for (String p : Palette.stateProblems(plan.palette())) {
            out.add("block " + p);
        }
        if (plan.ops().size() > GolfValidator.MAX_OPS) {
            out.add(plan.ops().size() + " blocks, more than " + GolfValidator.MAX_OPS);
        }
        Set<Long> seen = new HashSet<>();
        List<BlockOp> inside = new ArrayList<>();
        for (BlockOp op : plan.ops()) {
            if (op.state() >= plan.palette().size()) {
                out.add("a block at " + at(op.x(), op.y(), op.z()) + " has no palette entry");
            } else if (!half.contains(op.x(), op.y(), op.z())) {
                out.add("a block at " + at(op.x(), op.y(), op.z()) + " is outside the half");
            } else if (!seen.add(key(op.x(), op.y(), op.z()))) {
                out.add("two blocks at " + at(op.x(), op.y(), op.z()));
            } else {
                inside.add(op);
            }
        }
        for (SignText s : plan.signs()) {
            if (!half.contains(s.x(), s.y(), s.z())) {
                out.add("a sign at " + at(s.x(), s.y(), s.z()) + " is outside the half");
            } else if (seen.contains(key(s.x(), s.y(), s.z()))) {
                out.add("a sign and a block at " + at(s.x(), s.y(), s.z()));
            }
        }
        GolfCourse course = golf.course();
        out.addAll(course.problems(null));
        int n = course.holes().size();
        if (golf.witness().size() != n || golf.attempts().size() != n || golf.expert().size() != n) {
            out.add("the stored lists don't have one entry per hole (" + n + ")");
        }
        List<Box> areas = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            GolfCourse.Hole h = course.holes().get(i);
            if (h.corner1() == null || h.corner2() == null) {
                break; // GolfCourse.problems said so
            }
            Box area = Box.of(h.corner1().x(), h.corner1().y(), h.corner1().z(), h.corner2().x(), h.corner2().y(),
                    h.corner2().z());
            if (!half.contains(area)) {
                out.add("hole " + (i + 1) + "'s bounds leave the half");
            }
            for (int j = 0; j < areas.size(); j++) {
                if (areas.get(j).intersects(area)) {
                    out.add("holes " + (j + 1) + " and " + (i + 1) + " overlap");
                }
            }
            areas.add(area);
        }
        if (!out.isEmpty()) {
            return out;
        }
        PlanBlocks grid = PlanBlocks.of(half, plan.palette(), inside);
        out.addAll(Palette.leafProblems(plan.palette(), inside));
        out.addAll(outsideProblems(plan, inside, grid, areas));
        for (int i = 0; i < n; i++) {
            GolfCourse.Hole h = course.holes().get(i);
            Box area = areas.get(i);
            List<String> hp = holeProblems(grid, h, i + 1);
            out.addAll(hp);
            if (!hp.isEmpty()) {
                continue;
            }
            List<Putt> witness = golf.witness().get(i);
            int e = witness.size();
            if (e < 1 || e > ExpertSearch.MAX_DEPTH) {
                out.add("hole " + (i + 1) + "'s line has " + e + " putts");
                continue;
            }
            if (golf.expert().get(i) != e) {
                out.add("hole " + (i + 1) + "'s E is " + golf.expert().get(i) + " but its line has " + e + " putts");
            }
            if (h.par() != GolfPlanner.par(e) || h.par() > 4) {
                out.add("hole " + (i + 1) + "'s par is " + h.par() + " for an expert line of " + e);
            }
            int number = i + 1;
            if (plan.signs().stream().noneMatch(st -> GenCopy.golfTeeFeature(st.lines(), number, h.par()) != null
                    && area.contains(st.x(), st.y(), st.z()))) {
                out.add("hole " + (i + 1) + " has no tee sign saying its par");
            }
            LaneMap lane = LaneMap.of(grid, h, plan.algo());
            String line = lineProblem(grid, h, lane, witness, i + 1);
            if (line != null) {
                out.add(line);
                continue;
            }
            if (kid) {
                out.addAll(kidProblems(grid, h, lane, i + 1));
            }
        }
        return out;
    }

    // ---- one hole's blocks -------------------------------------------------------------------------

    /**
     * What is wrong with one Adventure Golf hole's blocks (numbered {@code n}), without playing it:
     * rules 1 and 3-11, and the part of rule 2 one hole's blocks show (its ponds). Empty when it is
     * sound. {@code grid} holds at least the hole's plot.
     */
    static List<String> holeProblems(PlanBlocks grid, GolfCourse.Hole h, int n) {
        List<String> out = new ArrayList<>();
        String name = "hole " + n;
        int cx = h.cup().x();
        int cy = h.cup().y();
        int cz = h.cup().z();
        if (BallPhysics.cupShape(grid, cx, cy, cz) != BallPhysics.CupShape.FINE) {
            out.add(name + "'s cup is " + BallPhysics.cupShape(grid, cx, cy, cz));
            return out;
        }
        LaneMap lane = LaneMap.of(grid, h, LaneMap.LAST_V2_ALGO + 1);
        int turf = lane.turfY;
        int tx = (int) Math.floor(h.tee().x());
        int tz = (int) Math.floor(h.tee().z());
        if (!lane.isLane(tx, tz) || Math.abs(lane.surface(tx, tz) - h.tee().y()) > EPS) {
            out.add(name + "'s tee doesn't stand on its lane");
            return out;
        }
        if (!lane.isLane(cx, cz) || Math.abs(lane.surface(cx, cz) - (cy + 1)) > EPS) {
            out.add(name + "'s cup can't be reached from its tee");
            return out;
        }
        int ringUp = cy + 2 - turf;
        if (ringUp < 0 || ringUp > 2) {
            out.add(name + "'s cup is " + ringUp + " blocks above the turf (a cup is at the turf, one up or two)");
        }
        Columns col = new Columns(grid, lane);
        lanes(grid, lane, h, name, out);
        walls(lane, col, name, out);
        rails(lane, col, name, out);
        flights(lane, col, h, name, out);
        ponds(grid, lane, col, h, name, out);
        canopies(grid, lane, h, name, out);
        List<int[]> stranded = lane.stranded();
        if (!stranded.isEmpty()) {
            int[] c = stranded.get(0);
            out.add(name + " has lane the ball can reach but never leave for the cup (" + stranded.size()
                    + " cells, first at " + at(c[0], (int) Math.floor(lane.surface(c[0], c[1])), c[1]) + ")");
        }
        String trapped = GolfValidator.trapped(grid, lane, turf);
        if (trapped != null) {
            out.add(name + ": a player at " + trapped + " can't step out (every wall they can walk to is more"
                    + " than a block above them)");
        }
        return out;
    }

    /**
     * The hole's columns as rules 4-6 see them: each non-lane, non-pond column's wall top (the top
     * of its solid run up from T - 1, leaves above it not counted), and which are obstacles.
     */
    private static final class Columns {

        final int minX;
        final int minZ;
        final int sizeX;
        final int sizeZ;
        /** Per column: the top of its run of solid blocks from T - 1; NaN when T - 1 is open (or lane, or pond). */
        final double[] top;
        /** Per column: a detached block (not leaves) above its run, at this y; MIN_VALUE when none. */
        final int[] loose;
        /** Per column: an obstacle (a solid column not joined to the bounds' edge). */
        final boolean[] obstacle;

        Columns(PlanBlocks grid, LaneMap lane) {
            minX = lane.minX;
            minZ = lane.minZ;
            sizeX = lane.sizeX;
            sizeZ = lane.sizeZ;
            int turf = lane.turfY;
            top = new double[sizeX * sizeZ];
            loose = new int[sizeX * sizeZ];
            obstacle = new boolean[sizeX * sizeZ];
            java.util.Arrays.fill(top, Double.NaN);
            java.util.Arrays.fill(loose, Integer.MIN_VALUE);
            for (int x = 0; x < sizeX; x++) {
                for (int z = 0; z < sizeZ; z++) {
                    int wx = minX + x;
                    int wz = minZ + z;
                    if (lane.isLane(wx, wz) || lane.isHazard(wx, wz) || Double.isNaN(lane.surface(wx, wz))) {
                        continue;
                    }
                    int y = turf - 1;
                    if (!solidWall(grid, wx, y, wz)) {
                        loose[x * sizeZ + z] = y; // open at T - 1: a gap at its foot
                        continue;
                    }
                    while (y + 1 <= turf + LOOK_UP && solidWall(grid, wx, y + 1, wz)) {
                        y++;
                    }
                    top[x * sizeZ + z] = y + PlanBlocks.top(grid.get(wx, y, wz));
                    for (int above = y + 1; above <= turf + LOOK_UP; above++) {
                        byte c = grid.get(wx, above, wz);
                        if (c != PlanBlocks.LEAVES && PlanBlocks.top(c) != BallPhysics.Blocks.NONE) {
                            loose[x * sizeZ + z] = above;
                            break;
                        }
                    }
                }
            }
            // obstacles: solid columns the bounds' edge can't reach through non-lane, non-pond columns
            boolean[] joined = new boolean[sizeX * sizeZ];
            ArrayDeque<int[]> queue = new ArrayDeque<>();
            for (int x = 0; x < sizeX; x++) {
                for (int z = 0; z < sizeZ; z++) {
                    boolean edge = x == 0 || z == 0 || x == sizeX - 1 || z == sizeZ - 1;
                    if (edge && open(lane, x, z)) {
                        joined[x * sizeZ + z] = true;
                        queue.add(new int[]{x, z});
                    }
                }
            }
            while (!queue.isEmpty()) {
                int[] c = queue.poll();
                for (int[] d : FOUR) {
                    int nx = c[0] + d[0];
                    int nz = c[1] + d[1];
                    boolean in = nx >= 0 && nz >= 0 && nx < sizeX && nz < sizeZ;
                    if (in && !joined[nx * sizeZ + nz] && open(lane, nx, nz)) {
                        joined[nx * sizeZ + nz] = true;
                        queue.add(new int[]{nx, nz});
                    }
                }
            }
            for (int i = 0; i < obstacle.length; i++) {
                int wx = minX + i / sizeZ;
                int wz = minZ + i % sizeZ;
                obstacle[i] = !joined[i] && !Double.isNaN(lane.surface(wx, wz)) && !lane.isLane(wx, wz);
            }
        }

        /** Whether local column (x, z) is neither lane nor pond (a wall, an obstacle or nothing). */
        private boolean open(LaneMap lane, int x, int z) {
            return !lane.isLane(minX + x, minZ + z) && !lane.isHazard(minX + x, minZ + z);
        }

        private int index(int x, int z) {
            int lx = x - minX;
            int lz = z - minZ;
            return lx < 0 || lz < 0 || lx >= sizeX || lz >= sizeZ ? -1 : lx * sizeZ + lz;
        }

        /** Column (x, z)'s wall top (NaN: open at its foot, lane, a pond or nothing). */
        double top(int x, int z) {
            int i = index(x, z);
            return i < 0 ? Double.NaN : top[i];
        }

        int loose(int x, int z) {
            int i = index(x, z);
            return i < 0 ? Integer.MIN_VALUE : loose[i];
        }

        boolean obstacle(int x, int z) {
            int i = index(x, z);
            return i >= 0 && obstacle[i];
        }

        /** Whether column (x, z) is solid and not lane or a pond: a wall or an obstacle. */
        boolean solid(LaneMap lane, int x, int z) {
            int i = index(x, z);
            return i >= 0 && !lane.isLane(x, z) && !lane.isHazard(x, z) && !Double.isNaN(lane.surface(x, z));
        }
    }

    /** A block a wall is made of: solid, and not leaves (a canopy resting on a wall isn't wall). */
    private static boolean solidWall(PlanBlocks grid, int x, int y, int z) {
        byte c = grid.get(x, y, z);
        return c != PlanBlocks.LEAVES && PlanBlocks.top(c) != BallPhysics.Blocks.NONE;
    }

    /** Rules 3, 7, 8 and 9's first half: every lane cell's own blocks and its neighbours. */
    private static void lanes(PlanBlocks grid, LaneMap lane, GolfCourse.Hole h, String name, List<String> out) {
        int turf = lane.turfY;
        int cx = h.cup().x();
        int cy = h.cup().y();
        int cz = h.cup().z();
        for (int x = lane.minX; x < lane.minX + lane.sizeX; x++) {
            for (int z = lane.minZ; z < lane.minZ + lane.sizeZ; z++) {
                if (!lane.isLane(x, z)) {
                    continue;
                }
                double s = lane.surface(x, z);
                if (x == lane.minX || z == lane.minZ || x == lane.minX + lane.sizeX - 1
                        || z == lane.minZ + lane.sizeZ - 1) {
                    out.add(name + "'s lane leaks out of its bounds at " + at(x, (int) s, z));
                    continue;
                }
                boolean cup = x == cx && z == cz;
                double lowestNext = Double.POSITIVE_INFINITY;
                for (int[] d : FOUR) {
                    int nx = x + d[0];
                    int nz = z + d[1];
                    if (lane.isHazard(nx, nz)) {
                        continue; // a pond's edge: rule 2's to judge
                    }
                    double ns = lane.surface(nx, nz);
                    if (Double.isNaN(ns)) {
                        out.add(name + "'s lane leaks: it drops into nothing at " + at(nx, turf, nz));
                        continue;
                    }
                    lowestNext = Math.min(lowestNext, ns);
                    if (cup && (!lane.isLane(nx, nz) || Math.abs(ns - s - 1) > EPS)) {
                        out.add(name + "'s cup isn't one block below the lane round it");
                    }
                    if (!cup && lane.isLane(nx, nz) && !(nx == cx && nz == cz) && s - ns > 1 + EPS) {
                        out.add(name + " has a lip of more than a block at " + at(x, (int) Math.floor(s), z));
                    }
                }
                boolean bunker = bunker(grid, lane, x, z);
                if (!cup && !bunker && lowestNext > s + EPS) {
                    out.add(name + " has a hollow at " + at(x, (int) Math.floor(s), z));
                }
                if (!cup && !bunker && s < turf - EPS) {
                    out.add(name + "'s lane dips below the turf at " + at(x, (int) Math.floor(s), z));
                }
                if (s > turf + LaneMap.WINDOW_UP + EPS) {
                    out.add(name + "'s lane rises more than two blocks above the turf at " + at(x, (int) s, z));
                }
                int floorY = (int) Math.floor(s - EPS);
                if (grid.get(x, floorY, z) == PlanBlocks.SLIME) {
                    out.add(name + " has slime in its lane floor at " + at(x, floorY, z));
                }
                for (int y = (int) Math.ceil(s - EPS); y <= turf + LOOK_UP; y++) {
                    byte c = grid.get(x, y, z);
                    if (c == PlanBlocks.AIR || cup && y == cy + 5) {
                        continue; // air, or the flag three above the cup ring
                    }
                    if (y < s + HEADROOM - EPS || c != PlanBlocks.LEAVES) {
                        out.add(name + " has a block over its lane at " + at(x, y, z));
                        break;
                    }
                }
            }
        }
    }

    /**
     * Whether lane cell (x, z) is in a sunken bunker: a sand slab at T - 1 (its top T - 0.5), in a
     * 2 x 2 square of such cells, so it is a patch of sand to putt out of, never a one-block pit.
     */
    private static boolean bunker(PlanBlocks grid, LaneMap lane, int x, int z) {
        if (!sunkenSand(grid, lane, x, z)) {
            return false;
        }
        for (int dx = -1; dx <= 0; dx++) {
            for (int dz = -1; dz <= 0; dz++) {
                int ax = x + dx;
                int az = z + dz;
                if (sunkenSand(grid, lane, ax, az) && sunkenSand(grid, lane, ax + 1, az)
                        && sunkenSand(grid, lane, ax, az + 1) && sunkenSand(grid, lane, ax + 1, az + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean sunkenSand(PlanBlocks grid, LaneMap lane, int x, int z) {
        return lane.isLane(x, z) && Math.abs(lane.surface(x, z) - (lane.turfY - BUNKER)) < EPS
                && grid.get(x, lane.turfY - 1, z) == PlanBlocks.SAND_SLAB;
    }

    /** Rules 4 and 6: every wall and obstacle beside the lane (diagonals too), where it stands. */
    private static void walls(LaneMap lane, Columns col, String name, List<String> out) {
        int turf = lane.turfY;
        for (int x = lane.minX; x < lane.minX + lane.sizeX; x++) {
            for (int z = lane.minZ; z < lane.minZ + lane.sizeZ; z++) {
                if (!col.solid(lane, x, z)) {
                    continue;
                }
                double high = Double.NEGATIVE_INFINITY;
                double low = Double.POSITIVE_INFINITY;
                for (int[] d : EIGHT) {
                    if (lane.isLane(x + d[0], z + d[1])) {
                        double s = lane.surface(x + d[0], z + d[1]);
                        high = Math.max(high, s);
                        low = Math.min(low, s);
                    }
                }
                if (high == Double.NEGATIVE_INFINITY) {
                    continue; // not beside the lane: the ball never meets it rolling
                }
                double top = col.top(x, z);
                if (Double.isNaN(top)) {
                    out.add(name + "'s lane leaks: its wall at " + at(x, turf - 1, z) + " has a gap");
                    continue;
                }
                if (col.loose(x, z) != Integer.MIN_VALUE) {
                    out.add(name + "'s lane leaks: its wall at " + at(x, col.loose(x, z), z) + " has a gap");
                    continue;
                }
                if (col.obstacle(x, z)) {
                    if (top < high + OBSTACLE_ABOVE_LEAST - EPS) {
                        out.add(name + "'s obstacle at " + at(x, (int) Math.floor(top), z)
                                + " isn't a block above the lane beside it");
                    }
                } else if (top - high <= LaneMap.STEP + EPS) {
                    out.add(name + "'s lane leaks: its wall at " + at(x, (int) Math.floor(top), z)
                            + " can be rolled over");
                } else if (top - low > WALL_ABOVE_MOST + EPS) {
                    out.add(name + "'s wall at " + at(x, (int) Math.floor(top), z)
                            + " is more than two blocks above the lane beside it");
                }
            }
        }
    }

    /**
     * Rule 4's rail half (the review of Course Variety: a dogleg's ledge). The ball's physics holds
     * a ball up by the edges of its footprint and reads each column on its own, so a ball whose
     * edge is over a wall's column rests on that wall's block at its own height — a rail it can
     * roll along, held above a lower lane or a pond beside it, and over any ring wall no more than
     * half a block above it. Its edge gets onto a wall only where the wall's line begins, rolling
     * along it from a lane or pond column its edge was over: its edge out over a lower side (a
     * plateau's edge where the approach's wall begins), or its centre out, hanging off a lip with
     * its edge on the upper cell (a ledge that runs into a side wall). Followed along the wall's
     * line while its centre's columns let it on, it rides the wall once its centre is over
     * something lower, to the end of the line: its edge back over lane or a pond, or its centre
     * meeting a wall or a step it bounces off. A wall there no more than half a block above the
     * rail is rolled over, so it is caught. A rail at the turf is harmless (the lane is there;
     * over a pond, a ball that stops has fallen in).
     */
    private static void rails(LaneMap lane, Columns col, String name, List<String> out) {
        int turf = lane.turfY;
        Set<Long> told = new HashSet<>();
        for (int qx = lane.minX; qx < lane.minX + lane.sizeX; qx++) {
            for (int qz = lane.minZ; qz < lane.minZ + lane.sizeZ; qz++) {
                if (!lane.isLane(qx, qz) && !lane.isHazard(qx, qz)) {
                    continue; // the column under the ball's centre
                }
                for (int[] e : FOUR) {
                    int px = qx + e[0];
                    int pz = qz + e[1];
                    if (!lane.isLane(px, pz) && !lane.isHazard(px, pz)) {
                        continue; // the column under its edge
                    }
                    double s = Math.max(lane.isLane(qx, qz) ? lane.surface(qx, qz) : Double.NEGATIVE_INFINITY,
                            lane.isLane(px, pz) ? lane.surface(px, pz) : Double.NEGATIVE_INFINITY);
                    if (s < turf + 1 - EPS) {
                        continue;
                    }
                    for (int[] m : FOUR) {
                        if (m[0] * e[0] + m[1] * e[1] != 0) {
                            continue; // along the wall's line only
                        }
                        double[] over = rolledOver(lane, col, qx, qz, px, pz, m, s);
                        if (over != null && told.add(key((int) over[0], 0, (int) over[1]))) {
                            out.add(name + "'s wall at " + at((int) over[0], (int) Math.floor(over[2]), (int) over[1])
                                    + " is no higher than the rail a ball rides from " + at(px, (int) Math.floor(s), pz)
                                    + " (its edge on the wall's lower block at " + at(px + m[0],
                                    (int) Math.floor(over[3] - EPS), pz + m[1]) + "): riding it, a ball rolls over");
                        }
                    }
                }
            }
        }
    }

    /**
     * The wall a ball at {@code s} (its centre over column q, its edge over column p) rolls over
     * when rolled along {@code m} riding a wall's rail: {x, z, that wall's top, the rail's height},
     * or null when there is none. Its edge goes onto the wall's line (the columns along from p),
     * held no higher than each wall's top; its centre goes on over lane no more than a step up,
     * lower lane, or a pond; once it has been over something lower it is riding the wall, and a
     * solid column its centre meets then that is no more than a step above the rail is rolled
     * onto. Its edge back over lane or a pond, a rail at the turf, or its centre meeting a wall or
     * a step it can't climb, and there is none.
     */
    private static double[] rolledOver(LaneMap lane, Columns col, int qx, int qz, int px, int pz, int[] m,
                                       double s) {
        double r = s;
        boolean riding = false;
        for (int j = 1; ; j++) {
            int wx = px + j * m[0];
            int wz = pz + j * m[1];
            int nx = qx + j * m[0];
            int nz = qz + j * m[1];
            double top = col.top(wx, wz);
            if (!col.solid(lane, wx, wz) || Double.isNaN(top)) {
                return null; // its edge is back over lane or a pond: its own footing again
            }
            r = Math.min(r, top);
            if (r < lane.turfY + 1 - EPS) {
                return null; // at the turf: harmless
            }
            if (lane.isHazard(nx, nz)) {
                riding = true;
                continue;
            }
            double ns = lane.surface(nx, nz);
            if (Double.isNaN(ns) || ns > r + LaneMap.STEP + EPS) {
                return null; // nothing there (a leak, rule 3's), or a wall or step it bounces off
            }
            if (!lane.isLane(nx, nz)) {
                return riding ? new double[]{nx, nz, ns, r} : null; // a wall rule 4 already judges beside the lane
            }
            riding |= ns < r - EPS;
        }
    }

    /**
     * Rule 5, the flight rule: for every lip (a lane cell with a lower lane or a pond beside it; the
     * sunken cup doesn't make its ring one, since a ball can't fly off into a one-block hole) and
     * every wall or obstacle whose top is no more than half a block above the lip's surface, the
     * wall stands further off (edge to edge) than {@link LaneMap#flightReach} of the drop that would
     * put a ball more than half a block below its top.
     */
    private static void flights(LaneMap lane, Columns col, GolfCourse.Hole h, String name, List<String> out) {
        int cx = h.cup().x();
        int cz = h.cup().z();
        List<int[]> lips = new ArrayList<>();
        for (int x = lane.minX; x < lane.minX + lane.sizeX; x++) {
            for (int z = lane.minZ; z < lane.minZ + lane.sizeZ; z++) {
                if (!lane.isLane(x, z)) {
                    continue;
                }
                double s = lane.surface(x, z);
                for (int[] d : FOUR) {
                    int nx = x + d[0];
                    int nz = z + d[1];
                    boolean lower = lane.isLane(nx, nz) && !(nx == cx && nz == cz) && lane.surface(nx, nz) < s - EPS;
                    if (lane.isHazard(nx, nz) || lower) {
                        lips.add(new int[]{x, z});
                        break;
                    }
                }
            }
        }
        if (lips.isEmpty()) {
            return;
        }
        Map<Double, Double> reach = new HashMap<>();
        for (int x = lane.minX; x < lane.minX + lane.sizeX; x++) {
            for (int z = lane.minZ; z < lane.minZ + lane.sizeZ; z++) {
                double top = col.top(x, z);
                if (!col.solid(lane, x, z) || Double.isNaN(top)) {
                    continue;
                }
                for (int[] c : lips) {
                    double s = lane.surface(c[0], c[1]);
                    if (top > s + LaneMap.STEP + EPS) {
                        continue; // more than half a block above the lip: a ball off it can't get over
                    }
                    double drop = s - top + LaneMap.STEP;
                    double far = reach.computeIfAbsent(drop, LaneMap::flightReach);
                    double gap = edgeDistance(c[0], c[1], x, z);
                    if (gap <= far + EPS) {
                        out.add(name + "'s wall at " + at(x, (int) Math.floor(top), z) + " is within a ball's flight"
                                + " of the lip at " + at(c[0], (int) Math.floor(s), c[1]) + String.format(Locale.ROOT,
                                " (%.2f blocks off; a ball flies %.2f before it drops %.1f)", gap, far, drop));
                        break;
                    }
                }
            }
        }
    }

    /** The distance between two columns' nearest edges (0 when they touch). */
    static double edgeDistance(int ax, int az, int bx, int bz) {
        double dx = Math.max(0, Math.abs(ax - bx) - 1);
        double dz = Math.max(0, Math.abs(az - bz) - 1);
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Rule 2 for the hole's own ponds (the hazards inside its bounds). */
    private static void ponds(PlanBlocks grid, LaneMap lane, Columns col, GolfCourse.Hole h, String name,
                              List<String> out) {
        if (lane.hazards() == 0) {
            return;
        }
        int turf = lane.turfY;
        int tx = (int) Math.floor(h.tee().x());
        int tz = (int) Math.floor(h.tee().z());
        int cx = h.cup().x();
        int cz = h.cup().z();
        Set<Long> done = new HashSet<>();
        for (int x = lane.minX; x < lane.minX + lane.sizeX; x++) {
            for (int z = lane.minZ; z < lane.minZ + lane.sizeZ; z++) {
                if (!lane.isHazard(x, z)) {
                    continue;
                }
                String here = at(x, turf - 1, z);
                if (x == lane.minX || z == lane.minZ || x == lane.minX + lane.sizeX - 1
                        || z == lane.minZ + lane.sizeZ - 1) {
                    out.add(name + "'s pond reaches the edge of its bounds at " + here);
                }
                if (grid.get(x, turf - 1, z) != PlanBlocks.WATER || grid.get(x, turf, z) == PlanBlocks.WATER
                        || !seals(grid.get(x, turf - 2, z))) {
                    out.add(name + "'s pond at " + here + " isn't one block deep at T - 1 on a solid floor");
                }
                for (int[] d : FOUR) {
                    byte side = grid.get(x + d[0], turf - 1, z + d[1]);
                    if (side != PlanBlocks.WATER && !seals(side)) {
                        out.add(name + "'s pond at " + here + " isn't sealed: its side is open at "
                                + at(x + d[0], turf - 1, z + d[1]));
                        break;
                    }
                }
                if (!inSquare(lane, x, z)) {
                    out.add(name + "'s pond at " + here + " is less than " + SKIM + " across (a hard putt skims it)");
                }
                if (Math.abs(x - tx) + Math.abs(z - tz) == 1) {
                    out.add(name + " has a pond beside its tee at " + here);
                }
                if (Math.max(Math.abs(x - cx), Math.abs(z - cz)) <= 1 + POND_CLEAR) {
                    out.add(name + " has a pond within " + POND_CLEAR + " of its cup ring at " + here);
                }
                for (int[] d : FOUR) {
                    int nx = x + d[0];
                    int nz = z + d[1];
                    double ns = lane.surface(nx, nz);
                    if (!lane.isHazard(nx, nz) && !Double.isNaN(ns)
                            && PlanBlocks.slab(grid.get(nx, (int) Math.floor(ns - EPS), nz))) {
                        out.add(name + " has a pond beside a slab at " + here);
                        break;
                    }
                }
                if (done.add(key(x, 0, z))) {
                    pond(lane, x, z, done, name, out);
                }
            }
        }
    }

    /**
     * One pond (the ponds' cells joined to (x, z), eight ways): it must lie beside the lane (in
     * play; a pond nobody can reach belongs outside the bounds), and every cell of it must wade,
     * four ways through the water, to a cell beside a T-level column a child steps out onto.
     */
    private static void pond(LaneMap lane, int x0, int z0, Set<Long> done, String name, List<String> out) {
        int turf = lane.turfY;
        List<int[]> cells = new ArrayList<>();
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[]{x0, z0});
        Set<Long> in = new HashSet<>();
        in.add(key(x0, 0, z0));
        boolean inPlay = false;
        while (!queue.isEmpty()) {
            int[] c = queue.poll();
            cells.add(c);
            for (int[] d : EIGHT) {
                int nx = c[0] + d[0];
                int nz = c[1] + d[1];
                inPlay |= lane.isLane(nx, nz);
                if (lane.isHazard(nx, nz) && in.add(key(nx, 0, nz))) {
                    done.add(key(nx, 0, nz));
                    queue.add(new int[]{nx, nz});
                }
            }
        }
        if (!inPlay) {
            out.add(name + " has a pond nowhere beside its lane at " + at(x0, turf - 1, z0));
        }
        // wading: from the cells beside a T-level edge, back through the water four ways
        Set<Long> out4 = new HashSet<>();
        ArrayDeque<int[]> wade = new ArrayDeque<>();
        for (int[] c : cells) {
            for (int[] d : FOUR) {
                int nx = c[0] + d[0];
                int nz = c[1] + d[1];
                if (!lane.isHazard(nx, nz) && Math.abs(lane.surface(nx, nz) - turf) < EPS) {
                    out4.add(key(c[0], 0, c[1]));
                    wade.add(c);
                    break;
                }
            }
        }
        while (!wade.isEmpty()) {
            int[] c = wade.poll();
            for (int[] d : FOUR) {
                int nx = c[0] + d[0];
                int nz = c[1] + d[1];
                if (lane.isHazard(nx, nz) && out4.add(key(nx, 0, nz))) {
                    wade.add(new int[]{nx, nz});
                }
            }
        }
        for (int[] c : cells) {
            if (!out4.contains(key(c[0], 0, c[1]))) {
                out.add(name + "'s pond at " + at(c[0], turf - 1, c[1]) + " has no edge at the turf to step out onto");
                return;
            }
        }
    }

    /** Whether pond column (x, z) lies in some 3 x 3 square of pond columns (the skim rule). */
    private static boolean inSquare(LaneMap lane, int x, int z) {
        for (int ox = x - SKIM + 1; ox <= x; ox++) {
            for (int oz = z - SKIM + 1; oz <= z; oz++) {
                boolean all = true;
                for (int dx = 0; dx < SKIM && all; dx++) {
                    for (int dz = 0; dz < SKIM && all; dz++) {
                        all = lane.isHazard(ox + dx, oz + dz);
                    }
                }
                if (all) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether a block of this code seals a pond's side or floor: a full block, not leaves ({@link Pools#seals}). */
    private static boolean seals(byte code) {
        return code != PlanBlocks.LEAVES && code != PlanBlocks.WATER && PlanBlocks.top(code) == 1.0;
    }

    /**
     * Rule 9's second half: no canopy within 2 columns of the tee, the cup ring or the flag; and no
     * leaves in the ball's layer (up to T + 2) anywhere in the hole: a canopy is above it, or the
     * lane under it reads as a wall (a ball rolls under leaves the lane reading can't see past).
     */
    private static void canopies(PlanBlocks grid, LaneMap lane, GolfCourse.Hole h, String name, List<String> out) {
        int turf = lane.turfY;
        search:
        for (int x = lane.minX; x < lane.minX + lane.sizeX; x++) {
            for (int z = lane.minZ; z < lane.minZ + lane.sizeZ; z++) {
                for (int y = turf - 3; y <= turf + LaneMap.WINDOW_UP; y++) {
                    if (grid.get(x, y, z) == PlanBlocks.LEAVES) {
                        out.add(name + " has leaves in the ball's layer at " + at(x, y, z) + " (a canopy starts "
                                + HEADROOM + " above the lane)");
                        break search;
                    }
                }
            }
        }
        int tx = (int) Math.floor(h.tee().x());
        int tz = (int) Math.floor(h.tee().z());
        int cx = h.cup().x();
        int cz = h.cup().z();
        String tee = leavesNear(grid, tx, tz, CANOPY_CLEAR, turf);
        if (tee != null) {
            out.add(name + " has a canopy within " + CANOPY_CLEAR + " of its tee, at " + tee);
        }
        String cup = leavesNear(grid, cx, cz, 1 + CANOPY_CLEAR, turf);
        if (cup != null) {
            out.add(name + " has a canopy within " + CANOPY_CLEAR + " of its cup ring or flag, at " + cup);
        }
    }

    private static String leavesNear(PlanBlocks grid, int x0, int z0, int r, int turf) {
        for (int x = x0 - r; x <= x0 + r; x++) {
            for (int z = z0 - r; z <= z0 + r; z++) {
                for (int y = turf - 3; y <= turf + LOOK_UP; y++) {
                    if (grid.get(x, y, z) == PlanBlocks.LEAVES) {
                        return at(x, y, z);
                    }
                }
            }
        }
        return null;
    }

    // ---- the plan outside its holes ----------------------------------------------------------------

    /**
     * Rule 2's plan-wide half and rule 13: every pond sealed and one deep at T - 1 in a plot
     * ({@link Pools}); water outside every hole's bounds is a decorative pond that keeps the skim and
     * wading rules; scenery (every block outside every hole's bounds) stands 2 columns clear of every
     * hole, inside a plot, never above T + 6; and every canopy is 2 columns inside the half.
     */
    private static List<String> outsideProblems(Plan plan, List<BlockOp> ops, PlanBlocks grid, List<Box> areas) {
        List<String> out = new ArrayList<>();
        Box half = plan.half();
        List<Box> plots = new ArrayList<>();
        List<Integer> turfs = new ArrayList<>();
        for (int i = 0; i < areas.size(); i++) {
            int[] p = GolfPlanner.plot(half, i);
            int turf = areas.get(i).minY() + LaneMap.BASE_ABOVE_FLOOR;
            turfs.add(turf);
            plots.add(new Box(p[0], turf - 1, p[1], p[0] + HoleTemplate.PLOT_X - 1, turf - 1,
                    p[1] + HoleTemplate.PLOT_Z - 1));
        }
        for (String p : Pools.problems(plan.palette(), ops, plots, Pools.GOLF_DEPTH)) {
            out.add("ponds: " + p);
        }
        int near = 0;
        int stray = 0;
        int high = 0;
        int edge = 0;
        String firstNear = null;
        String firstStray = null;
        String firstHigh = null;
        String firstEdge = null;
        Map<Long, int[]> decorative = new HashMap<>();
        for (BlockOp op : ops) {
            String block = plan.palette().get(op.state());
            if (Palette.isLeaves(block) && (op.x() < half.minX() + CANOPY_INSIDE || op.x() > half.maxX() - CANOPY_INSIDE
                    || op.z() < half.minZ() + CANOPY_INSIDE || op.z() > half.maxZ() - CANOPY_INSIDE)) {
                edge++;
                firstEdge = firstEdge == null ? at(op.x(), op.y(), op.z()) : firstEdge;
            }
            if (inAnyBounds(areas, op.x(), op.z())) {
                continue;
            }
            // scenery
            if (near(areas, op.x(), op.z())) {
                near++;
                firstNear = firstNear == null ? at(op.x(), op.y(), op.z()) : firstNear;
            }
            int plot = -1;
            for (int i = 0; i < plots.size() && plot < 0; i++) {
                Box b = plots.get(i);
                if (op.x() >= b.minX() && op.x() <= b.maxX() && op.z() >= b.minZ() && op.z() <= b.maxZ()) {
                    plot = i;
                }
            }
            if (plot < 0) {
                stray++;
                firstStray = firstStray == null ? at(op.x(), op.y(), op.z()) : firstStray;
            } else if (op.y() > turfs.get(plot) + SCENERY_TOP) {
                high++;
                firstHigh = firstHigh == null ? at(op.x(), op.y(), op.z()) : firstHigh;
            }
            if (Palette.poolWater(block)) {
                decorative.put(key(op.x(), op.y(), op.z()), new int[]{op.x(), op.y(), op.z()});
            }
        }
        if (near > 0) {
            out.add(near + " scenery block" + (near == 1 ? " stands" : "s stand") + " within " + (SCENERY_GAP - 1)
                    + " column of a hole's bounds (first at " + firstNear + "): scenery keeps " + SCENERY_GAP
                    + " columns clear, outside the ball's grid");
        }
        if (stray > 0) {
            out.add(stray + " scenery block" + (stray == 1 ? " is" : "s are") + " outside every plot (first at "
                    + firstStray + ")");
        }
        if (high > 0) {
            out.add(high + " scenery block" + (high == 1 ? " is" : "s are") + " above T + " + SCENERY_TOP
                    + " (first at " + firstHigh + ")");
        }
        if (edge > 0) {
            out.add(edge + " lea" + (edge == 1 ? "f is" : "ves are") + " less than " + CANOPY_INSIDE
                    + " columns inside the half (first at " + firstEdge + "): a neighbour outside could change "
                    + (edge == 1 ? "its" : "their") + " distance");
        }
        out.addAll(decorativeProblems(decorative, grid, turfs.isEmpty() ? 0 : turfs.get(0)));
        return out;
    }

    /** The skim and wading rules for water outside every hole (a pond to look at). */
    private static List<String> decorativeProblems(Map<Long, int[]> water, PlanBlocks grid, int turf) {
        List<String> out = new ArrayList<>();
        int thin = 0;
        String firstThin = null;
        for (int[] w : water.values()) {
            boolean square = false;
            for (int ox = w[0] - SKIM + 1; ox <= w[0] && !square; ox++) {
                for (int oz = w[2] - SKIM + 1; oz <= w[2] && !square; oz++) {
                    boolean all = true;
                    for (int dx = 0; dx < SKIM && all; dx++) {
                        for (int dz = 0; dz < SKIM && all; dz++) {
                            all = water.containsKey(key(ox + dx, w[1], oz + dz));
                        }
                    }
                    square = all;
                }
            }
            if (!square) {
                thin++;
                firstThin = firstThin == null ? at(w[0], w[1], w[2]) : firstThin;
            }
        }
        if (thin > 0) {
            out.add(thin + " decorative pond block" + (thin == 1 ? " is" : "s are") + " in no " + SKIM + " x " + SKIM
                    + " of water (first at " + firstThin + ")");
        }
        // wading: every decorative pond cell reaches, through water, a cell beside a T-level column
        Set<Long> reached = new HashSet<>();
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        for (int[] w : water.values()) {
            for (int[] d : FOUR) {
                if (!water.containsKey(key(w[0] + d[0], w[1], w[2] + d[1]))
                        && Math.abs(windowTop(grid, w[0] + d[0], w[2] + d[1], turf) - turf) < EPS) {
                    reached.add(key(w[0], w[1], w[2]));
                    queue.add(w);
                    break;
                }
            }
        }
        while (!queue.isEmpty()) {
            int[] w = queue.poll();
            for (int[] d : FOUR) {
                long k = key(w[0] + d[0], w[1], w[2] + d[1]);
                int[] next = water.get(k);
                if (next != null && reached.add(k)) {
                    queue.add(next);
                }
            }
        }
        for (int[] w : water.values()) {
            if (!reached.contains(key(w[0], w[1], w[2]))) {
                out.add("a decorative pond at " + at(w[0], w[1], w[2]) + " has no edge at the turf to step out onto");
                break;
            }
        }
        return out;
    }

    /** The top of the highest solid block of column (x, z) from T - 3 to T + 2; NaN when none. */
    private static double windowTop(PlanBlocks grid, int x, int z, int turf) {
        for (int y = turf + LaneMap.WINDOW_UP; y >= turf - 3; y--) {
            double t = PlanBlocks.top(grid.get(x, y, z));
            if (t != BallPhysics.Blocks.NONE) {
                return y + t;
            }
        }
        return Double.NaN;
    }

    private static boolean inAnyBounds(List<Box> areas, int x, int z) {
        for (Box b : areas) {
            if (x >= b.minX() && x <= b.maxX() && z >= b.minZ() && z <= b.maxZ()) {
                return true;
            }
        }
        return false;
    }

    /** Whether column (x, z) is within {@value #SCENERY_GAP} - 1 columns of some hole's bounds. */
    private static boolean near(List<Box> areas, int x, int z) {
        int g = SCENERY_GAP - 1;
        for (Box b : areas) {
            if (x >= b.minX() - g && x <= b.maxX() + g && z >= b.minZ() - g && z <= b.maxZ() + g) {
                return true;
            }
        }
        return false;
    }

    // ---- playing it ----------------------------------------------------------------------------------

    /**
     * Rule 12 on the witness line: it replays from the tee into the cup in exactly its putts, every
     * spot it rests at on the way is on the lane, and on a hole with water in play every putt's twins
     * ({@value SafeExpert#TWIN_DEGREES} degrees either side) stay dry. {@code null} when it does.
     */
    private static String lineProblem(PlanBlocks grid, GolfCourse.Hole h, LaneMap lane, List<Putt> witness, int n) {
        int e = witness.size();
        GolfShot.Replay replay = GolfShot.replay(grid, h, witness);
        if (!replay.holed() || replay.putts() != e || replay.strokes() != e) {
            return "hole " + n + "'s line doesn't hole out in " + e;
        }
        BallPhysics.Hole area = GolfShot.area(grid, h);
        BallPhysics.Ball ball = GolfShot.tee(grid, h);
        for (Putt p : witness) {
            GolfShot.Result r = GolfShot.play(grid, area, ball, p);
            if (r.inCup()) {
                break;
            }
            if (!restsOnLane(lane, r.x(), r.y(), r.z())) {
                return "hole " + n + "'s line leaves the ball off its lane at " + spot(r);
            }
        }
        if (lane.hazards() > 0) {
            int bad = SafeExpert.unsafePutt(grid, h, witness);
            if (bad > 0) {
                return "hole " + n + "'s putt " + bad + " of its line splashes " + SafeExpert.TWIN_DEGREES
                        + " degrees off: on a pond hole par is the safe line";
            }
        }
        return null;
    }

    /** The full check's sloppy player (rule 12): within par + 1, every rest spot of the tree on the lane. */
    private static List<String> kidProblems(PlanBlocks grid, GolfCourse.Hole h, LaneMap lane, int n) {
        List<String> out = new ArrayList<>();
        GolfShot.Result[] off = new GolfShot.Result[1];
        try {
            KidPolicy.Result k = KidPolicy.evaluate(grid, h, lane, h.par() + 1, Work.unlimited(), r -> {
                if (off[0] == null && !r.inCup() && !restsOnLane(lane, r.x(), r.y(), r.z())) {
                    off[0] = r;
                }
            });
            if (!k.within()) {
                out.add("hole " + n + ": a sloppy player can need " + k.worst() + " strokes (par " + h.par() + ")");
            }
        } catch (GenFailed never) {
            // Work.unlimited() is never cancelled
            out.add("hole " + n + ": the sloppy player's check was cancelled");
        }
        if (off[0] != null) {
            out.add("hole " + n + ": a sloppy player's ball can come to rest off the lane at " + spot(off[0]));
        }
        return out;
    }

    /**
     * Whether a ball resting at (x, y, z) rests on the lane: some point of its footprint (its centre
     * or an edge, as {@code BallPhysics} holds a ball up) is over a lane cell whose surface it sits
     * on. A ball resting on a wall's or an obstacle's top is not.
     */
    static boolean restsOnLane(LaneMap lane, double x, double y, double z) {
        double r = BallPhysics.RADIUS * 0.99;
        double[][] at = {{x, z}, {x + r, z}, {x - r, z}, {x, z + r}, {x, z - r}};
        for (double[] p : at) {
            int cx = (int) Math.floor(p[0]);
            int cz = (int) Math.floor(p[1]);
            if (lane.isLane(cx, cz) && Math.abs(lane.surface(cx, cz) - y) < EPS) {
                return true;
            }
        }
        return false;
    }

    private static String spot(GolfShot.Result r) {
        return String.format(Locale.ROOT, "%.2f %.2f %.2f", r.x(), r.y(), r.z());
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    private static String at(int x, int y, int z) {
        return x + " " + y + " " + z;
    }
}
