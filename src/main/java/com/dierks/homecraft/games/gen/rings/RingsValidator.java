package com.dierks.homecraft.games.gen.rings;

import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.Palette;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlanInput;
import com.dierks.homecraft.games.gen.api.PlannedTrial;
import com.dierks.homecraft.games.gen.api.SignText;
import com.dierks.homecraft.games.gen.rings.RingsPlanner.LegKind;
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
 * The independent check of a Sky Rings plan (GEN-SPEC §4.2, Sky Rings v3), run before a single
 * block is set: a plan that fails it is a failed try, never a course.
 *
 * <p>It trusts nothing the planner kept for itself. The rings are read from the blocks round each
 * checkpoint's centre (a whole voxel circle of the right colour, white for a climb's ring, facing x
 * or z, its hole clear); every other block must be the start tower (a 5 x 5 platform under the
 * start, a 1 x 3 board off one side, a pillar to the floor) with its two signs. The course's
 * rocket budget ({@link Course#elytra}) names each leg's kind; the need list is worked out again
 * from the kinds and the stack must be the need plus {@link RingsPlanner#MARGIN}. Then, against the
 * tier's table:
 * <ul>
 *   <li>the kinds follow the profile's rules: the first two legs and the last are glides, no two
 *       rocket legs in a row, a level leg after two glides and before one, three glides between
 *       climbs, the tier's counts;</li>
 *   <li>every glide is inside its length and turn ranges and descends at least its length over the
 *       tier's slow ratio (which keeps a {@link RingsPlanner#GLIDE_MARGIN}x margin under 10:1) and at
 *       most over its fast one; every climb runs 90 to 120 across, rises its tier's 25 to 55 and turns
 *       at most half the tier's turn; every level leg runs 90 to 100 and drops 0 to 3;</li>
 *   <li>every flight into and out of a ring is within the tier's angle of the ring's facing;</li>
 *   <li>rings are at least 48 apart, no frame is within R + 2 of a leg that isn't its own, and a
 *       ring keeps a lane's width (80 across the ground) from every leg but the ones at it and
 *       beside it;</li>
 *   <li>the tower is at least 8 from every leg but the first;</li>
 *   <li>every block is inside the half, every ring (and 8 round it) too, every ring over the floor + 16;</li>
 *   <li>the {@link ElytraSim} autopilot passes every ring, touching nothing: both flights off the
 *       tower round the whole course with the rockets it needs, spending exactly those; the
 *       reference lap never faster than 60 blocks a second; from every ring, at 0.8, 1.2 and 1.6
 *       blocks a tick with the heading 3° either way, from a standstill, and after a climb or a
 *       level leg three slow sinking exits, each with the rockets the rest of the course needs,
 *       through every remaining ring; and on every level leg a fast pilot with no rockets through it
 *       and the glide after it;</li>
 *   <li>the fall height is under every ring, and the times fit the reference lap.</li>
 * </ul>
 * A plan an older planner made (algo below 3) is refused plainly before any of that: it stays in
 * the archive. Pure: no Bukkit. Problems are admin words for {@code /hcm games gen status}.
 */
public final class RingsValidator {

    /** The most blocks a Sky Rings plan may place. */
    public static final int MAX_OPS = 20_000;
    /** The reference lap never flies faster than this, blocks a tick (60 a second: fair play's cap of 80 never trips). */
    public static final double PEAK_SPEED = 3.0;

    private static final double EPS = 1e-6;

    private RingsValidator() {
    }

    /** {@link #problems(Plan, String)} with the tier of the input. */
    public static List<String> problems(Plan plan, PlanInput in) {
        return problems(plan, in.slot().normalise(in.tierOrMix()));
    }

    /** What is wrong with {@code plan} as a {@code tier} Sky Rings course; empty when nothing is. */
    public static List<String> problems(Plan plan, String tier) {
        return problems(plan, tier, PEAK_SPEED);
    }

    /** {@link #problems(Plan, String)} with the reference lap's speed cap given, so a test can see it fire. */
    static List<String> problems(Plan plan, String tier, double peakSpeed) {
        List<String> out = new ArrayList<>();
        RingsPlanner.Level level = RingsPlanner.Level.of(tier);
        if (level == null) {
            out.add("'" + tier + "' isn't a Sky Rings tier");
            return out;
        }
        if (plan.algo() < RingsPlanner.ALGO) {
            out.add("made by Sky Rings v2 (planner " + plan.algo() + "); it stays in the archive");
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
        // the rocket budget: a kind per leg after ring 1, the need list made again from them, the stack
        Course.Elytra elytra = course.elytra();
        LegKind[] kinds = null;
        if (elytra == null) {
            out.add("the course has no rocket budget");
        } else {
            kinds = RingsPlanner.kinds(elytra.legs());
            if (kinds == null || kinds.length != course.checkpoints().size()) {
                out.add("the legs '" + elytra.legs() + "' aren't one of G, L or F per ring after the first ("
                        + course.checkpoints().size() + ")");
                kinds = null;
            } else {
                List<Integer> need = RingsPlanner.needList(RingsPlanner.need(kinds));
                if (!need.equals(elytra.need())) {
                    out.add("the rockets still needed " + elytra.need() + " aren't what the legs say, " + need);
                }
                if (elytra.rockets() != need.get(0) + RingsPlanner.MARGIN) {
                    out.add("rockets " + elytra.rockets() + ", not the " + need.get(0) + " needed plus "
                            + RingsPlanner.MARGIN);
                }
            }
        }
        if (!out.isEmpty()) {
            return out;
        }

        // the rings, from the blocks round each mark: white after a climb, gold last, else the rainbow
        List<RingsPlanner.Ring> rings = new ArrayList<>();
        Set<Long> ringBlocks = new HashSet<>();
        for (int k = 0; k < marks.size(); k++) {
            Course.Mark m = marks.get(k);
            int cx = (int) Math.floor(m.x());
            int cy = (int) Math.floor(m.y());
            int cz = (int) Math.floor(m.z());
            String colour = RingsPlanner.colour(k, marks.size(), kinds);
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
        List<List<String>> texts = new ArrayList<>();
        for (SignText s : plan.signs()) {
            if (!half.contains(s.x(), s.y(), s.z()) || !platform.contains(s.x(), s.y() - 1, s.z())) {
                out.add("a sign at " + at(s.x(), s.y(), s.z()) + " isn't on the platform");
            }
            texts.add(s.lines());
        }
        if (!texts.contains(GenCopy.ringsStart()) || !texts.contains(GenCopy.ringsHow(elytra.rockets()))) {
            out.add("the tower's signs don't say how to start and that there are " + elytra.rockets()
                    + " rockets for the whole run");
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

        // the kinds follow the profile's rules
        out.addAll(profileProblems(kinds, level));

        // the legs: lengths, heights, turns, facing, by kind
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
                String leg = "leg " + (k + 1);
                LegKind kind = kinds[k - 1];
                double turn = Math.abs(RingsPlanner.turn(prevYaw, yaw));
                int maxTurn = kind == LegKind.LIFT ? level.maxTurn() / 2 : level.maxTurn();
                switch (kind) {
                    case LIFT -> {
                        int lo = Math.max(level.legMin(), RingsPlanner.LIFT_LEG_MIN);
                        int hi = Math.min(level.legMax(), RingsPlanner.LIFT_LEG_MAX);
                        if (d < lo - EPS || d > hi + EPS) {
                            out.add(leg + " is " + fmt(d) + " long; a climb is " + lo + " to " + hi);
                        }
                        double climb = -drop;
                        if (climb < level.upMin() - EPS || climb > level.upMax() + EPS) {
                            out.add(leg + " climbs " + fmt(climb) + " over " + fmt(d) + ": a climb is " + level.upMin()
                                    + " to " + level.upMax());
                        }
                    }
                    case FLAT -> {
                        int lo = Math.max(level.legMin(), RingsPlanner.FLAT_MIN);
                        int hi = Math.min(level.legMax(), RingsPlanner.FLAT_MAX);
                        if (d < lo - EPS || d > hi + EPS) {
                            out.add(leg + " is " + fmt(d) + " long; a level leg is " + lo + " to " + hi);
                        }
                        if (drop < -EPS || drop > RingsPlanner.FLAT_DROP + EPS) {
                            out.add(leg + " drops " + fmt(drop) + " over " + fmt(d) + ": a level leg is 0 to "
                                    + RingsPlanner.FLAT_DROP + " down");
                        }
                    }
                    default -> {
                        if (d < level.legMin() - EPS || d > level.legMax() + EPS) {
                            out.add(leg + " is " + fmt(d) + " long; " + level.id() + " legs are " + level.legMin()
                                    + " to " + level.legMax());
                        }
                        if (drop <= 0) {
                            out.add(leg + " doesn't descend");
                        } else if (drop < d / level.slow() - EPS) {
                            out.add(leg + " drops " + fmt(drop) + " over " + fmt(d)
                                    + ": not far enough under the glide line (at least 1 in " + level.slow() + ")");
                        } else if (drop > d / level.fast() + EPS) {
                            out.add(leg + " drops " + fmt(drop) + " over " + fmt(d) + ": steeper than 1 in "
                                    + level.fast());
                        }
                    }
                }
                if (turn > maxTurn + EPS) {
                    out.add("the course turns " + Math.round(turn) + " degrees at ring " + k + " (" + level.id()
                            + " turns at most " + maxTurn + (kind == LegKind.LIFT ? " into a climb" : "") + ")");
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

        // spacing, tubes, lanes, the tower, the floor
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
                if (leg >= a - 1 && leg <= a + 2) {
                    continue; // the legs beside those end a leg's length away
                }
                double g = RingsPlanner.groundDistance(from.get(leg), to.get(leg), r.x(), r.z());
                if (g < RingsPlanner.LANE_GAP - EPS) {
                    out.add("ring " + (a + 1) + " is " + fmt(g) + " across the ground from leg " + (leg + 1)
                            + " (lanes keep " + RingsPlanner.LANE_GAP + " apart)");
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
            targets.add(RingsPlanner.target(r, k == rings.size() - 1, level, k > 0 && kinds[k - 1].rocket()));
        }
        int[] need = RingsPlanner.need(kinds);
        List<ElytraSim.Pilot> off = RingsPlanner.offTheTower(launch, toward[0], toward[1], need[0]);
        for (int i = 0; i < off.size(); i++) {
            ElytraSim.Pilot pilot = off.get(i);
            ElytraSim.Result res = pilot.fly(targets, solid);
            String who = "the autopilot off the tower (wings out after " + RingsPlanner.OPEN_AFTER[i] + " ticks) ";
            if (!res.passed()) {
                out.add(who + res.why());
            } else if (pilot.rocketsUsed() != need[0]) {
                out.add(who + "used " + pilot.rocketsUsed() + " rockets, not the " + need[0] + " the course needs");
            }
            if (i == 0) {
                // the reference lap: its time and its speed
                long lapMs = res.ticks() * 50L;
                if (trial.refMs() < 0.5 * lapMs - EPS || trial.refMs() > lapMs + EPS) {
                    out.add("the reference " + trial.refMs() + "ms isn't half to all of the autopilot's lap (" + lapMs
                            + "ms)");
                }
                if (pilot.peakSpeed() > peakSpeed + EPS) {
                    out.add("the autopilot's lap peaks at " + fmt(pilot.peakSpeed()) + " blocks a tick ("
                            + Math.round(peakSpeed * 20) + " blocks a second is the most)");
                }
            }
        }
        for (int k = 0; k + 1 < rings.size(); k++) {
            RingsPlanner.Ring r = rings.get(k);
            LegKind kindIn = k == 0 ? LegKind.GLIDE : kinds[k - 1];
            List<ElytraSim.Pilot> starts = RingsPlanner.startsAt(r.x(), r.y(), r.z(), r.yawIn(), need[k + 1], kindIn);
            List<String> names = RingsPlanner.startNames(kindIn);
            List<ElytraSim.Target> rest = targets.subList(k + 1, targets.size());
            for (int i = 0; i < starts.size(); i++) {
                ElytraSim.Result res = starts.get(i).fly(rest, solid);
                if (!res.passed()) {
                    out.add("the autopilot from ring " + (k + 1) + " " + names.get(i) + " with " + need[k + 1]
                            + " rockets: " + res.why() + " after it");
                }
            }
            if (kinds[k] == LegKind.FLAT) {
                // the speed line: fast and with no rockets through the level ring and the glide after it
                ElytraSim.Pilot fast = RingsPlanner.speedLine(r.x(), r.y(), r.z(), r.yawIn());
                ElytraSim.Result res = fast.fly(targets.subList(k + 1, Math.min(k + 3, targets.size())), solid);
                if (!res.passed()) {
                    out.add("the autopilot from ring " + (k + 1) + " at " + RingsPlanner.EXPERT_SPEED
                            + " b/t with no rockets: " + res.why() + " after it (a level leg is fast or rocket)");
                }
            }
        }
        return out;
    }

    /** What is wrong with the legs' kinds against the profile's rules, in admin words. */
    static List<String> profileProblems(LegKind[] kinds, RingsPlanner.Level level) {
        List<String> out = new ArrayList<>();
        int n = kinds.length;
        int glides = 0;
        int lifts = 0;
        int flats = 0;
        for (LegKind k : kinds) {
            switch (k) {
                case GLIDE -> glides++;
                case LIFT -> lifts++;
                case FLAT -> flats++;
            }
        }
        if (glides != level.glides() || lifts != level.lifts() || flats != level.flats()) {
            out.add("the legs are " + glides + " glides, " + lifts + " climbs and " + flats + " level legs; " + level.id()
                    + " has " + level.glides() + ", " + level.lifts() + " and " + level.flats());
        }
        if (n < 3 || kinds[0] != LegKind.GLIDE || kinds[1] != LegKind.GLIDE || kinds[n - 1] != LegKind.GLIDE) {
            out.add("the first two legs and the last must be glides");
        }
        int sinceLift = Integer.MAX_VALUE / 2;
        int glidesInARow = 0;
        for (int i = 0; i < n; i++) {
            // leg i is the leg into ring i + 2 (1-based); words say that ring's number
            String leg = "leg " + (i + 2);
            LegKind k = kinds[i];
            if (i > 0 && k.rocket() && kinds[i - 1].rocket()) {
                out.add(leg + " is the second of two rocket legs in a row");
            }
            if (k == LegKind.FLAT && (glidesInARow < 2 || i + 1 >= n || kinds[i + 1] != LegKind.GLIDE)) {
                out.add(leg + " is a level leg that isn't after two glides and before one");
            }
            if (k == LegKind.LIFT && sinceLift < 3) {
                out.add(leg + " is a climb with fewer than three glides since the last");
            }
            if (k == LegKind.LIFT) {
                sinceLift = 0;
            } else if (k == LegKind.GLIDE) {
                sinceLift++;
            }
            glidesInARow = k == LegKind.GLIDE ? glidesInARow + 1 : 0;
        }
        return out;
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
