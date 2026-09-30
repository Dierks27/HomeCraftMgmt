package com.dierks.homecraft.games.gen.rings;

import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Palette;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlanInput;
import com.dierks.homecraft.games.gen.api.PlannedTrial;
import com.dierks.homecraft.games.gen.api.SignText;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.TrialKind;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The independent check of a Sky Rings plan (GEN-SPEC §4.2), run before a single block is set: a
 * plan that fails it is a failed try, never a course.
 *
 * <p>It trusts nothing the planner kept for itself. The rings are read from the blocks round each
 * checkpoint's centre (a whole voxel circle of the right colour, facing x or z, its hole clear);
 * every other block must be the start tower (a 5 x 5 platform under the start, a 1 x 3 board off
 * one side, a pillar to the floor). Then, against the tier's table:
 * <ul>
 *   <li>every leg is inside its length, turn and drop ranges, and descends;</li>
 *   <li>the glide-ratio bound holds: every drop is at least its length over the tier's slow ratio,
 *       and that ratio keeps at least a {@link RingsPlanner#GLIDE_MARGIN}x margin under 10:1;</li>
 *   <li>every flight into and out of a ring is within the tier's angle of the ring's facing;</li>
 *   <li>rings are at least 16 apart, and no frame is within R + 2 of a leg that isn't its own;</li>
 *   <li>the tower is at least 8 from every leg but the first;</li>
 *   <li>every block is inside the half, every ring (and 8 round it) too, every ring over the floor + 16;</li>
 *   <li>the {@link ElytraSim} autopilot passes every ring, touching nothing: from every ring at
 *       0.8, 1.2 and 1.6 blocks a tick with the heading 3° either way, from a standstill at every
 *       ring with at most one rocket, and off the tower;</li>
 *   <li>the fall height is under every ring, and the times are believable.</li>
 * </ul>
 * Pure: no Bukkit. Problems are admin words for {@code /hcm games gen status}.
 */
public final class RingsValidator {

    /** The most blocks a Sky Rings plan may place. */
    public static final int MAX_OPS = 20_000;

    private static final double EPS = 1e-6;

    private RingsValidator() {
    }

    /** {@link #problems(Plan, String)} with the tier of the input. */
    public static List<String> problems(Plan plan, PlanInput in) {
        return problems(plan, in.slot().normalise(in.tierOrMix()));
    }

    /** What is wrong with {@code plan} as a {@code tier} Sky Rings course; empty when nothing is. */
    public static List<String> problems(Plan plan, String tier) {
        List<String> out = new ArrayList<>();
        RingsPlanner.Level level = RingsPlanner.Level.of(tier);
        if (level == null) {
            out.add("'" + tier + "' isn't a Sky Rings tier");
            return out;
        }
        if (!(plan.course() instanceof PlannedTrial trial) || trial.course().kind() != TrialKind.ELYTRA) {
            out.add("the plan isn't an elytra course");
            return out;
        }
        Course course = trial.course();
        if (course.start() == null || course.finish() == null) {
            out.add("the course has no start or no finish");
            return out;
        }
        Box half = plan.half();
        int radius = level.radius();

        // the blocks themselves
        for (String p : Palette.problems(plan.palette())) {
            out.add("'" + p + "' isn't a Fresh Courses block");
        }
        if (plan.ops().size() > MAX_OPS) {
            out.add(plan.ops().size() + " blocks is more than " + MAX_OPS);
        }
        Map<Long, String> blocks = new HashMap<>();
        for (BlockOp op : plan.ops()) {
            if (op.state() < 0 || op.state() >= plan.palette().size()) {
                out.add("a block at " + at(op.x(), op.y(), op.z()) + " has no palette entry");
                return out;
            }
            if (!half.contains(op.x(), op.y(), op.z())) {
                out.add("a block at " + at(op.x(), op.y(), op.z()) + " is outside the half " + half.describe());
            }
            if (blocks.put(RingsPlanner.key(op.x(), op.y(), op.z()), plan.blockOf(op)) != null) {
                out.add("two blocks at " + at(op.x(), op.y(), op.z()));
            }
        }
        if (!out.isEmpty()) {
            return out;
        }

        // the marks: every checkpoint and the finish in the middle of a block, R - 1 round
        List<Course.Mark> marks = new ArrayList<>(course.checkpoints());
        marks.add(course.finish());
        if (marks.size() != level.rings()) {
            out.add(marks.size() + " rings, " + level.id() + " has " + level.rings());
        }
        for (Course.Mark m : marks) {
            if (Math.abs(m.radius() - level.checkpointRadius()) > EPS) {
                out.add("the ring at " + at(m.x(), m.y(), m.z()) + " counts within " + m.radius() + ", not R - 1 = "
                        + level.checkpointRadius());
            }
            if (!centred(m.x()) || !centred(m.y()) || !centred(m.z())) {
                out.add("the ring at " + at(m.x(), m.y(), m.z()) + " isn't centred on a block");
            }
        }
        if (!out.isEmpty()) {
            return out;
        }

        // the rings, from the blocks round each mark
        List<RingsPlanner.Ring> rings = new ArrayList<>();
        Set<Long> ringBlocks = new HashSet<>();
        for (int k = 0; k < marks.size(); k++) {
            Course.Mark m = marks.get(k);
            int cx = (int) Math.floor(m.x());
            int cy = (int) Math.floor(m.y());
            int cz = (int) Math.floor(m.z());
            String colour = k == marks.size() - 1 ? Palette.FINISH : Palette.RAINBOW.get(k % Palette.RAINBOW.size());
            List<RingShape.Normal> found = new ArrayList<>();
            for (RingShape.Normal n : RingShape.Normal.values()) {
                if (complete(blocks, RingShape.voxels(cx, cy, cz, radius, n), colour)) {
                    found.add(n);
                }
            }
            if (found.size() != 1) {
                out.add("ring " + (k + 1) + " at " + at(cx, cy, cz) + " isn't one whole " + Palette.id(colour)
                        + " ring of radius " + radius + " facing x or z");
                continue;
            }
            RingShape.Normal normal = found.get(0);
            List<int[]> voxels = RingShape.voxels(cx, cy, cz, radius, normal);
            for (int[] v : voxels) {
                ringBlocks.add(RingsPlanner.key(v[0], v[1], v[2]));
            }
            for (int u = -radius; u <= radius; u++) {
                for (int v = -radius; v <= radius; v++) {
                    if (Math.sqrt(u * u + v * v) < radius - 0.5) {
                        int x = normal == RingShape.Normal.Z ? cx + u : cx;
                        int z = normal == RingShape.Normal.Z ? cz : cz + u;
                        if (blocks.containsKey(RingsPlanner.key(x, cy + v, z))) {
                            out.add("ring " + (k + 1) + "'s hole has a block at " + at(x, cy + v, z));
                        }
                    }
                }
            }
            rings.add(new RingsPlanner.Ring(cx, cy, cz, 0, normal, voxels));
        }
        if (!out.isEmpty()) {
            return out;
        }

        // the tower: every other block, and nothing else
        Course.Spot start = course.start();
        int tx = (int) Math.floor(start.x());
        int tz = (int) Math.floor(start.z());
        int top = (int) Math.round(start.y());
        RingsPlanner.Tower tower = new RingsPlanner.Tower(tx, tz, top, half.minY());
        List<int[]> boards = new ArrayList<>();
        int[][] sides = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};
        for (int[] s : sides) {
            if (blocks.containsKey(RingsPlanner.key(tx + 3 * s[0], top - 1, tz + 3 * s[1]))) {
                boards.add(s);
            }
        }
        if (boards.size() != 1) {
            out.add("the start tower has " + boards.size() + " diving boards, not one");
            return out;
        }
        int[] toward = boards.get(0);
        Box board = Box.of(tx + 3 * toward[0], top - 1, tz + 3 * toward[1], tx + 5 * toward[0], top - 1,
                tz + 5 * toward[1]);
        Box platform = tower.platform();
        Box pillar = tower.pillar();
        long towerBlocks = 0;
        for (BlockOp op : plan.ops()) {
            if (ringBlocks.contains(RingsPlanner.key(op.x(), op.y(), op.z()))) {
                continue;
            }
            String b = plan.blockOf(op);
            boolean deck = platform.contains(op.x(), op.y(), op.z()) || board.contains(op.x(), op.y(), op.z());
            boolean ok = deck ? b.equals(Palette.TOWER)
                    : pillar.contains(op.x(), op.y(), op.z()) && Palette.id(b).equals(Palette.id(Palette.PILLAR));
            if (!ok) {
                out.add("a stray " + Palette.id(b) + " at " + at(op.x(), op.y(), op.z()));
                if (out.size() > 5) {
                    return out;
                }
            } else {
                towerBlocks++;
            }
        }
        long whole = platform.volume() + board.volume() + pillar.volume();
        if (towerBlocks != whole) {
            out.add("the start tower has " + towerBlocks + " of its " + whole + " blocks");
        }
        for (SignText s : plan.signs()) {
            if (!half.contains(s.x(), s.y(), s.z()) || !platform.contains(s.x(), s.y() - 1, s.z())) {
                out.add("a sign at " + at(s.x(), s.y(), s.z()) + " isn't on the platform");
            }
        }
        if (!out.isEmpty()) {
            return out;
        }
        double boardYaw = RingsPlanner.yaw(toward[0], toward[1]);
        if (Math.abs(RingsPlanner.turn(start.yaw(), boardYaw)) > 5) {
            out.add("the start faces " + Math.round(start.yaw()) + ", not along the board (" + Math.round(boardYaw)
                    + ")");
        }
        double[] launch = {tx + 0.5 + toward[0] * RingsPlanner.LAUNCH, top, tz + 0.5 + toward[1] * RingsPlanner.LAUNCH};

        // the legs: lengths, drops, turns, facing
        List<double[]> from = new ArrayList<>();
        List<double[]> to = new ArrayList<>();
        double[] prev = launch;
        double prevYaw = boardYaw;
        for (int k = 0; k < rings.size(); k++) {
            RingsPlanner.Ring r = rings.get(k);
            double[] c = r.centre();
            from.add(prev);
            to.add(c);
            double dx = c[0] - prev[0];
            double dz = c[2] - prev[2];
            double d = Math.sqrt(dx * dx + dz * dz);
            double yaw = RingsPlanner.yaw(dx, dz);
            double drop = prev[1] - c[1];
            if (k == 0) {
                double ahead = dx * toward[0] + dz * toward[1];
                double side = Math.abs(dx * toward[1] - dz * toward[0]);
                double below = top - c[1];
                if (Math.abs(ahead - RingsPlanner.FIRST_AHEAD) > 1.5 || side > 1
                        || below < RingsPlanner.FIRST_BELOW - 1 || below > RingsPlanner.FIRST_BELOW + 2) {
                    out.add("ring 1 isn't about " + RingsPlanner.FIRST_AHEAD + " ahead of the board and "
                            + RingsPlanner.FIRST_BELOW + " below it");
                }
            } else {
                if (d < level.legMin() - EPS || d > level.legMax() + EPS) {
                    out.add("leg " + (k + 1) + " is " + fmt(d) + " long; " + level.id() + " legs are "
                            + level.legMin() + " to " + level.legMax());
                }
                if (drop <= 0) {
                    out.add("leg " + (k + 1) + " doesn't descend");
                } else if (drop < d / level.slow() - EPS) {
                    out.add("leg " + (k + 1) + " drops " + fmt(drop) + " over " + fmt(d)
                            + ": not far enough under the glide line (at least 1 in " + level.slow() + ")");
                } else if (drop > d / level.fast() + EPS) {
                    out.add("leg " + (k + 1) + " drops " + fmt(drop) + " over " + fmt(d) + ": steeper than 1 in "
                            + level.fast());
                }
                double turn = Math.abs(RingsPlanner.turn(prevYaw, yaw));
                if (turn > level.maxTurn() + EPS) {
                    out.add("the course turns " + Math.round(turn) + " degrees at ring " + k + " (" + level.id()
                            + " turns at most " + level.maxTurn() + ")");
                }
            }
            rings.set(k, new RingsPlanner.Ring(r.cx(), r.cy(), r.cz(), yaw, r.normal(), r.voxels()));
            prev = c;
            prevYaw = yaw;
        }
        if (level.slow() > RingsPlanner.GLIDE / RingsPlanner.GLIDE_MARGIN + EPS) {
            out.add(level.id() + "'s drops keep less than a " + RingsPlanner.GLIDE_MARGIN + "x margin under the glide");
        }
        for (int k = 0; k < rings.size(); k++) {
            RingsPlanner.Ring r = rings.get(k);
            double[] in = RingsPlanner.dir(r.yawIn());
            double offIn = RingShape.offNormal(in[0], in[1], r.normal());
            if (offIn > level.maxOffNormal() + EPS) {
                out.add("ring " + (k + 1) + " is flown into " + Math.round(offIn) + " degrees off its facing");
            }
            if (k + 1 < rings.size()) {
                double[] o = RingsPlanner.dir(rings.get(k + 1).yawIn());
                double offOut = RingShape.offNormal(o[0], o[1], r.normal());
                if (offOut > level.maxOffNormal() + EPS) {
                    out.add("ring " + (k + 1) + " is flown out of " + Math.round(offOut) + " degrees off its facing");
                }
            }
        }

        // spacing, tubes, the tower, the floor
        double tube = radius + RingsPlanner.TUBE;
        for (int a = 0; a < rings.size(); a++) {
            RingsPlanner.Ring r = rings.get(a);
            int m = radius + RingsPlanner.EDGE;
            if (!half.contains(new Box(r.cx() - m, r.cy() - m, r.cz() - m, r.cx() + m, r.cy() + m, r.cz() + m))) {
                out.add("ring " + (a + 1) + " and the " + RingsPlanner.EDGE + " round it don't fit the half");
            }
            if (r.cy() < half.minY() + RingsPlanner.FLOOR) {
                out.add("ring " + (a + 1) + " is under the half's floor + " + RingsPlanner.FLOOR);
            }
            for (int b = a + 1; b < rings.size(); b++) {
                double g = distance(r.centre(), rings.get(b).centre());
                if (g < RingsPlanner.RING_GAP - EPS) {
                    out.add("rings " + (a + 1) + " and " + (b + 1) + " are " + fmt(g) + " apart (at least "
                            + RingsPlanner.RING_GAP + ")");
                }
            }
            for (int leg = 0; leg < rings.size(); leg++) {
                if (leg == a || leg == a + 1) {
                    continue; // the legs into and out of this ring
                }
                for (int[] v : r.voxels()) {
                    if (RingsPlanner.segmentDistance(from.get(leg), to.get(leg), v[0] + 0.5, v[1] + 0.5, v[2] + 0.5)
                            < tube - EPS) {
                        out.add("ring " + (a + 1) + "'s frame is within " + fmt(tube) + " of leg " + (leg + 1));
                        break;
                    }
                }
            }
        }
        for (int leg = 1; leg < rings.size(); leg++) {
            double g = Math.min(RingsPlanner.boxDistance(from.get(leg), to.get(leg), platform), Math.min(
                    RingsPlanner.boxDistance(from.get(leg), to.get(leg), board),
                    RingsPlanner.boxDistance(from.get(leg), to.get(leg), pillar)));
            if (g < RingsPlanner.TOWER_GAP - EPS) {
                out.add("leg " + (leg + 1) + " passes " + fmt(g) + " from the tower (at least "
                        + RingsPlanner.TOWER_GAP + ")");
            }
        }

        // falls and times
        double lowest = Double.MAX_VALUE;
        double length = 0;
        double[] p = {start.x(), start.y(), start.z()};
        for (RingsPlanner.Ring r : rings) {
            lowest = Math.min(lowest, r.y());
            length += distance(p, r.centre());
            p = r.centre();
        }
        if (course.fallY() == null || course.fallY() > lowest - radius - 1 || course.fallY() < half.minY()) {
            out.add("the fall height " + course.fallY() + " isn't under every ring and inside the half");
        }
        if (trial.refMs() <= 0 || course.minSeconds() == null || course.minSeconds() < 0
                || course.minSeconds() > length / RingsPlanner.MIN_SPEED + EPS
                || course.minSeconds() * 1000L > trial.refMs()) {
            out.add("the times don't fit the course (reference " + trial.refMs() + "ms, shortest "
                    + course.minSeconds() + "s, " + Math.round(length) + " blocks)");
        }
        if (!out.isEmpty()) {
            return out;
        }

        // the autopilot
        RingsPlanner.Obstacles solid = new RingsPlanner.Obstacles();
        solid.box(platform);
        solid.box(board);
        solid.box(pillar);
        List<ElytraSim.Target> targets = new ArrayList<>();
        for (int k = 0; k < rings.size(); k++) {
            RingsPlanner.Ring r = rings.get(k);
            solid.ring(r.cx(), r.cy(), r.cz(), radius, r.voxels());
            targets.add(RingsPlanner.target(r, k == rings.size() - 1, level));
        }
        List<ElytraSim.Pilot> off = RingsPlanner.offTheTower(launch, toward[0], toward[1]);
        for (int i = 0; i < off.size(); i++) {
            ElytraSim.Result res = off.get(i).fly(targets, solid);
            if (!res.passed()) {
                out.add("the autopilot off the tower (wings out after " + RingsPlanner.OPEN_AFTER[i] + " ticks) "
                        + res.why());
            }
        }
        String[] names = variantNames();
        for (int k = 0; k + 1 < rings.size(); k++) {
            RingsPlanner.Ring r = rings.get(k);
            List<ElytraSim.Pilot> starts = RingsPlanner.startsAt(r.x(), r.y(), r.z(), r.yawIn());
            List<ElytraSim.Target> rest = targets.subList(k + 1, targets.size());
            for (int i = 0; i < starts.size(); i++) {
                ElytraSim.Result res = starts.get(i).fly(rest, solid);
                if (!res.passed()) {
                    out.add("the autopilot from ring " + (k + 1) + " " + names[i] + ": " + res.why() + " after it");
                }
            }
        }
        return out;
    }

    /** The names of {@link RingsPlanner#startsAt}'s flights, in its order. */
    private static String[] variantNames() {
        List<String> names = new ArrayList<>();
        for (double speed : RingsPlanner.SPEEDS) {
            for (double error : RingsPlanner.ERRORS) {
                names.add("at " + speed + " b/t, " + (error == 0 ? "on course" : (error > 0 ? "+" : "") + (int) error
                        + " degrees off"));
            }
        }
        names.add("from a standstill (one rocket)");
        return names.toArray(new String[0]);
    }

    private static boolean complete(Map<Long, String> blocks, List<int[]> voxels, String colour) {
        for (int[] v : voxels) {
            if (!colour.equals(blocks.get(RingsPlanner.key(v[0], v[1], v[2])))) {
                return false;
            }
        }
        return true;
    }

    private static double distance(double[] a, double[] b) {
        double dx = a[0] - b[0];
        double dy = a[1] - b[1];
        double dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static boolean centred(double v) {
        return Math.abs(v - Math.floor(v) - 0.5) < EPS;
    }

    private static String fmt(double d) {
        return String.format(Locale.ROOT, "%.1f", d);
    }

    private static String at(double x, double y, double z) {
        return Math.round(x) + " " + Math.round(y) + " " + Math.round(z);
    }
}
