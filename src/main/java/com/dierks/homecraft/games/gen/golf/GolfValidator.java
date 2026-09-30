package com.dierks.homecraft.games.gen.golf;

import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.GenFailed;
import com.dierks.homecraft.games.gen.api.Palette;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlannedGolf;
import com.dierks.homecraft.games.gen.api.Putt;
import com.dierks.homecraft.games.gen.api.SignText;
import com.dierks.homecraft.games.golf.BallPhysics;
import com.dierks.homecraft.games.golf.GolfCourse;
import com.dierks.homecraft.games.golf.GolfShot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The independent check of a golf plan (GEN-SPEC §4.3), made from the plan's blocks alone — never
 * from the planner's bookkeeping. A plan with any problem is never built (a failed try).
 *
 * <p>For the whole plan: the course is a golf course with nothing missing
 * ({@code GolfCourse.problems}), every block is an allowed golf block inside the half, no two
 * blocks share a spot, the holes' areas don't overlap, and the lists the row stores (attempts,
 * witness lines, E) have one entry per hole. Then, per hole, on a grid of every block of the plan:
 * <ul>
 *   <li>the cup block is a fine cup ({@code BallPhysics.cupShape == FINE});</li>
 *   <li>the tee stands on the lane, and the lane (flood-filled from the tee) never leaves the
 *       hole's bounds and never meets a drop into nothing;</li>
 *   <li>every wall beside the lane is solid from T - 1 up and stands exactly one block above the
 *       hole's highest lane cell: no ball rolls over it (not even one riding the top of a wall's
 *       lower block beside a raised green);</li>
 *   <li>nobody is trapped: from every lane cell a player can walk to a wall at most one block
 *       above the lane beside it and step out ({@link #trapped});</li>
 *   <li>nothing is above the lane except the flag, three over the cup;</li>
 *   <li>no hollow but the cup, which is exactly one block deep;</li>
 *   <li>the witness line replays from the tee into the cup in exactly E strokes, par is
 *       E + 1 (2-4), and the hole's tee sign says that par;</li>
 *   <li>and, in the full check, the sloppy player of {@link KidPolicy} always finishes within
 *       par + 1.</li>
 * </ul>
 *
 * <p>The fast check (everything but the sloppy player) costs a few dozen simulated putts and is
 * safe on the main thread; the full check replays the kid tree too (a few thousand putts a hole)
 * and belongs on the planner thread.
 *
 * <p><b>By version</b> (Course Variety §1.4, §3.8). The rules above are the ones a layout of golf
 * planner version 2 or older was made and stored under, and such a plan (a live layout until its
 * set ends, an archived one recalled into Classics) is judged by exactly them, frozen: the private
 * check, {@link #holeProblems(BallPhysics.Blocks, GolfCourse.Hole, int)}, {@link #trapped} and the
 * wall rule below are today's, byte for byte. A plan of version 3 or later
 * (Adventure Golf: ponds, sand, trees, terraces) goes to {@link GolfValidatorV3}, whose walls are
 * checked locally with the flight rule, whose ponds are sealed and never beside the tee, and whose
 * witness line has room to miss on a pond hole ({@link SafeExpert}).
 */
public final class GolfValidator {

    /** Most blocks a golf plan may have. */
    public static final int MAX_OPS = 40_000;
    private static final double EPS = 1e-6;

    private GolfValidator() {
    }

    /** Every problem, the sloppy player included (planner thread). Empty when the plan is fine. */
    public static List<String> problems(Plan plan) {
        return adventure(plan) ? GolfValidatorV3.problems(plan, true) : problems(plan, true);
    }

    /** Every problem but the sloppy player's: cheap enough for the main thread. */
    public static List<String> quickProblems(Plan plan) {
        return adventure(plan) ? GolfValidatorV3.problems(plan, false) : problems(plan, false);
    }

    /** Whether {@code plan} is judged by Adventure Golf's rules: made by golf planner version 3 or later. */
    static boolean adventure(Plan plan) {
        return plan != null && plan.algo() > LaneMap.LAST_V2_ALGO;
    }

    /**
     * What is wrong with one hole's blocks (numbered {@code n}), without playing it, as a layout of
     * golf planner version {@code algo} is judged: {@link #holeProblems(BallPhysics.Blocks,
     * GolfCourse.Hole, int)} for version 2 or older, Adventure Golf's per-hole rules
     * ({@link GolfValidatorV3#holeProblems}) after. Empty when it is sound. For a planner that wants
     * to refuse one hole before the whole course is checked.
     */
    static List<String> holeProblems(PlanBlocks grid, GolfCourse.Hole h, int n, int algo) {
        return algo > LaneMap.LAST_V2_ALGO ? GolfValidatorV3.holeProblems(grid, h, n) : holeProblems(grid, h, n);
    }

    private static List<String> problems(Plan plan, boolean kid) {
        List<String> out = new ArrayList<>();
        if (plan == null || !(plan.course() instanceof PlannedGolf golf)) {
            out.add("not a golf plan");
            return out;
        }
        Box half = plan.half();
        for (String p : Palette.problems(plan.palette())) {
            out.add("block " + p + " isn't allowed");
        }
        if (plan.ops().size() > MAX_OPS) {
            out.add(plan.ops().size() + " blocks, more than " + MAX_OPS);
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
            List<String> sign = GenCopy.golfTee(i + 1, h.par());
            if (plan.signs().stream().noneMatch(st -> st.lines().equals(sign)
                    && area.contains(st.x(), st.y(), st.z()))) {
                out.add("hole " + (i + 1) + " has no tee sign saying its par");
            }
            GolfShot.Replay replay = GolfShot.replay(grid, h, witness);
            if (!replay.holed() || replay.putts() != e || replay.strokes() != e) {
                out.add("hole " + (i + 1) + "'s line doesn't hole out in " + e);
                continue;
            }
            if (kid) {
                try {
                    KidPolicy.Result k = KidPolicy.evaluate(grid, h, LaneMap.of(grid, h), h.par() + 1,
                            Work.unlimited());
                    if (!k.within()) {
                        out.add("hole " + (i + 1) + ": a sloppy player can need " + k.worst() + " strokes (par "
                                + h.par() + ")");
                    }
                } catch (GenFailed never) {
                    // Work.unlimited() is never cancelled
                    out.add("hole " + (i + 1) + ": the sloppy player's check was cancelled");
                }
            }
        }
        return out;
    }

    /**
     * What is wrong with one hole's blocks (numbered {@code n}), without playing it: cup, lane,
     * walls, headroom, hollows. Empty when it is sound.
     */
    static List<String> holeProblems(BallPhysics.Blocks grid, GolfCourse.Hole h, int n) {
        List<String> out = new ArrayList<>();
        String name = "hole " + n;
        int cx = h.cup().x();
        int cy = h.cup().y();
        int cz = h.cup().z();
        if (BallPhysics.cupShape(grid, cx, cy, cz) != BallPhysics.CupShape.FINE) {
            out.add(name + "'s cup is " + BallPhysics.cupShape(grid, cx, cy, cz));
            return out;
        }
        LaneMap lane = LaneMap.of(grid, h);
        int tx = (int) Math.floor(h.tee().x());
        int tz = (int) Math.floor(h.tee().z());
        int turf = lane.turfY;
        if (!lane.isLane(tx, tz) || Math.abs(lane.surface(tx, tz) - h.tee().y()) > EPS) {
            out.add(name + "'s tee doesn't stand on its lane");
            return out;
        }
        if (!lane.isLane(cx, cz) || Math.abs(lane.surface(cx, cz) - (cy + 1)) > EPS) {
            out.add(name + "'s cup can't be reached from its tee");
            return out;
        }
        double high = turf;
        for (int x = lane.minX; x < lane.minX + lane.sizeX; x++) {
            for (int z = lane.minZ; z < lane.minZ + lane.sizeZ; z++) {
                if (lane.isLane(x, z)) {
                    high = Math.max(high, lane.surface(x, z));
                }
            }
        }
        int[][] four = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        Set<Long> walls = new HashSet<>();
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
                for (int[] d : four) {
                    int nx = x + d[0];
                    int nz = z + d[1];
                    double ns = lane.surface(nx, nz);
                    if (Double.isNaN(ns)) {
                        out.add(name + "'s lane leaks: it drops into nothing at " + at(nx, turf, nz));
                        continue;
                    }
                    lowestNext = Math.min(lowestNext, ns);
                    if (cup && (!lane.isLane(nx, nz) || Math.abs(ns - s - 1) > EPS)) {
                        out.add(name + "'s cup isn't one block below the lane round it");
                    }
                    if (!lane.isLane(nx, nz) && walls.add(key(nx, 0, nz))) {
                        wall(grid, nx, nz, turf, high, name, out);
                    }
                }
                if (!cup && lowestNext > s + EPS) {
                    out.add(name + " has a hollow at " + at(x, (int) s, z));
                }
                if (!cup && s < turf - EPS) {
                    out.add(name + "'s lane dips below the turf at " + at(x, (int) s, z));
                }
                int above = (int) Math.ceil(s - EPS);
                for (int y = above; y <= turf + 8; y++) {
                    boolean flag = cup && y == cy + 5;
                    if (grid.top(x, y, z, x + 0.5, z + 0.5) != BallPhysics.Blocks.NONE && !flag) {
                        out.add(name + " has a block over its lane at " + at(x, y, z));
                        break;
                    }
                }
            }
        }
        String trapped = trapped(grid, lane, turf);
        if (trapped != null) {
            out.add(name + ": a player at " + trapped + " can't step out (every wall they can walk to is more"
                    + " than a block above them)");
        }
        return out;
    }

    /**
     * Nobody is trapped (S4): from every lane cell a player can walk, a step of at most one block
     * at a time, to a cell beside a wall at most one block above it, and step out over that wall.
     * Walls may stand two above the approach of a raised green (so a ball on the green can't ride
     * a wall's lower block out), but then the green's edge is where a player steps out.
     *
     * @return the first cell with no way out ("x y z"), or {@code null} when every cell has one
     */
    static String trapped(BallPhysics.Blocks grid, LaneMap lane, int turf) {
        int[][] four = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        java.util.ArrayDeque<int[]> queue = new java.util.ArrayDeque<>();
        Set<Long> out = new HashSet<>();
        for (int x = lane.minX; x < lane.minX + lane.sizeX; x++) {
            for (int z = lane.minZ; z < lane.minZ + lane.sizeZ; z++) {
                if (!lane.isLane(x, z)) {
                    continue;
                }
                double s = lane.surface(x, z);
                for (int[] d : four) {
                    if (lane.isLane(x + d[0], z + d[1])) {
                        continue;
                    }
                    double wall = highest(grid, x + d[0], z + d[1], turf);
                    if (!Double.isNaN(wall) && wall - s <= 1 + EPS && out.add(key(x, 0, z))) {
                        queue.add(new int[]{x, z});
                        break;
                    }
                }
            }
        }
        while (!queue.isEmpty()) {
            int[] c = queue.poll();
            double s = lane.surface(c[0], c[1]);
            for (int[] d : four) {
                int nx = c[0] + d[0];
                int nz = c[1] + d[1];
                if (lane.isLane(nx, nz) && Math.abs(lane.surface(nx, nz) - s) <= 1 + EPS && out.add(key(nx, 0, nz))) {
                    queue.add(new int[]{nx, nz});
                }
            }
        }
        for (int x = lane.minX; x < lane.minX + lane.sizeX; x++) {
            for (int z = lane.minZ; z < lane.minZ + lane.sizeZ; z++) {
                if (lane.isLane(x, z) && !out.contains(key(x, 0, z))) {
                    return at(x, (int) Math.floor(lane.surface(x, z)), z);
                }
            }
        }
        return null;
    }

    /**
     * One wall column beside the lane: solid from T - 1 to its top, too high for the ball to roll
     * over from the hole's highest lane cell ({@code high}), and at most one block above it so a
     * player can step out. The whole ring must clear the highest lane, not just the lane beside
     * it: the ball's physics reads each block of a column on its own, so a ball on a raised green
     * rests on the top of a wall's lower block beside it and can roll along that like a rail.
     */
    private static void wall(BallPhysics.Blocks grid, int x, int z, int turf, double high, String name,
                             List<String> out) {
        double top = highest(grid, x, z, turf);
        for (int y = turf - 1; y < top - EPS; y++) {
            if (grid.top(x, y, z, x + 0.5, z + 0.5) == BallPhysics.Blocks.NONE) {
                out.add(name + "'s lane leaks: its wall at " + at(x, y, z) + " has a gap");
                return;
            }
        }
        if (top - high <= LaneMap.STEP + EPS) {
            out.add(name + "'s lane leaks: its wall at " + at(x, (int) top, z) + " can be rolled over");
        } else if (top - high > 1 + EPS) {
            out.add(name + "'s wall at " + at(x, (int) top, z) + " is more than one block high");
        }
    }

    /** The top of the highest block of column (x, z), from T - 3 to T + 8. */
    private static double highest(BallPhysics.Blocks grid, int x, int z, int turf) {
        for (int y = turf + 8; y >= turf - 3; y--) {
            double top = grid.top(x, y, z, x + 0.5, z + 0.5);
            if (top != BallPhysics.Blocks.NONE) {
                return y + top;
            }
        }
        return Double.NaN;
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    private static String at(int x, int y, int z) {
        return x + " " + y + " " + z;
    }
}
