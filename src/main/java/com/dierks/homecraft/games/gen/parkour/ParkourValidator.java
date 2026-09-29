package com.dierks.homecraft.games.gen.parkour;

import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Palette;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlanInput;
import com.dierks.homecraft.games.gen.api.PlannedTrial;
import com.dierks.homecraft.games.gen.api.SignText;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.TrialKind;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The independent check of a Daily Parkour plan (GEN-SPEC §4.1), run before a single block is
 * set: a plan that fails it is a failed try, never a course.
 *
 * <p><b>Independent</b> means it trusts nothing the planner kept for itself. It rebuilds the pads
 * from the plan's blocks (every solid block belongs to one flat rectangular pad), finds the path
 * by walking from the start pad to the nearest pad not yet visited, and then checks the result
 * against the rules, which it shares with the planner only as {@link JumpRules} and {@link
 * JumpSim}:
 * <ol>
 *   <li>every jump is an allowed row for the tier (height step, gap, side offset, turns, pad sizes);</li>
 *   <li>every pad's headroom and every jump's flight is air;</li>
 *   <li>no skips: pads that aren't neighbours are out of jumping reach of each other;</li>
 *   <li>fall consistency: no pad sits within 2 of the height that sends a run back, using the live
 *       {@code trials.fall_depth};</li>
 *   <li>a checkpoint after exactly every N jumps, the finish after the last; every mark sits on
 *       its pad and reaches no other pad; a checkpoint's covers every spot of its pad where feet
 *       can stand (a half-width past its edges), so any landing on it counts;</li>
 *   <li>every block and sign inside the half;</li>
 *   <li>no enclosed walkable cell (the air over every pad reaches open sky);</li>
 *   <li>at most {@value #MAX_OPS} blocks;</li>
 *   <li>only {@link Palette#ALLOWED} blocks, in the colour language (green start, light-blue
 *       checkpoints, gold finish, the tier's colour between).</li>
 * </ol>
 * Pure: no Bukkit. Problems are admin words for {@code /hcm games gen status}.
 */
public final class ParkourValidator {

    /** The most blocks a parkour plan may place. */
    public static final int MAX_OPS = 40_000;

    private static final double EPS = 1e-9;

    private ParkourValidator() {
    }

    /** {@link #problems(Plan, String, int)} with the tier and live {@code fall_depth} of the input. */
    public static List<String> problems(Plan plan, PlanInput in) {
        return problems(plan, in.slot().normalise(in.tierOrMix()), in.fallDepth());
    }

    /**
     * What is wrong with {@code plan} as a {@code tier} parkour course under a live
     * {@code trials.fall_depth} of {@code fallDepth}; empty when nothing is.
     */
    public static List<String> problems(Plan plan, String tier, int fallDepth) {
        List<String> out = new ArrayList<>();
        JumpRules.Level level = JumpRules.Level.of(tier);
        if (level == null) {
            out.add("'" + tier + "' isn't a parkour tier");
            return out;
        }
        if (!(plan.course() instanceof PlannedTrial trial) || trial.course().kind() != TrialKind.PARKOUR) {
            out.add("the plan isn't a parkour course");
            return out;
        }
        Course course = trial.course();
        if (course.start() == null || course.finish() == null) {
            out.add("the course has no start or no finish");
            return out;
        }
        int depth = ParkourPlanner.clampDepth(fallDepth);
        Box half = plan.half();

        // 9, 8, 6: the blocks themselves
        for (String p : Palette.problems(plan.palette())) {
            out.add("'" + p + "' isn't a Daily Courses block");
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
            if (blocks.put(key(op.x(), op.y(), op.z()), Palette.id(plan.blockOf(op))) != null) {
                out.add("two blocks at " + at(op.x(), op.y(), op.z()));
            }
        }
        for (SignText s : plan.signs()) {
            if (!half.contains(s.x(), s.y(), s.z())) {
                out.add("a sign at " + at(s.x(), s.y(), s.z()) + " is outside the half");
            }
            if (!Palette.allowed(s.blockData())) {
                out.add("a sign at " + at(s.x(), s.y(), s.z()) + " is '" + s.blockData() + "'");
            }
            if (!blocks.containsKey(key(s.x(), s.y() - 1, s.z()))) {
                out.add("the sign at " + at(s.x(), s.y(), s.z()) + " stands on nothing");
            }
            if (blocks.containsKey(key(s.x(), s.y(), s.z()))) {
                out.add("the sign at " + at(s.x(), s.y(), s.z()) + " is inside a block");
            }
        }
        if (!out.isEmpty()) {
            return out;
        }

        // the pads, from the blocks alone
        List<Pad> pads = pads(plan, blocks, out);
        if (!out.isEmpty()) {
            return out;
        }
        Pad start = padUnder(pads, course.start().x(), course.start().y(), course.start().z());
        Pad finish = padUnder(pads, course.finish().x(), course.finish().y(), course.finish().z());
        if (start == null) {
            out.add("the start isn't standing on a pad");
        }
        if (finish == null) {
            out.add("the finish isn't on a pad");
        }
        List<Pad> cps = new ArrayList<>();
        for (Course.Mark m : course.checkpoints()) {
            Pad p = padUnder(pads, m.x(), m.y(), m.z());
            if (p == null) {
                out.add("checkpoint at " + at(m.x(), m.y(), m.z()) + " isn't on a pad");
            }
            cps.add(p);
        }
        if (!out.isEmpty()) {
            return out;
        }

        List<Pad> path = path(pads, start);
        if (path.size() < 2) {
            out.add("the course has only one pad");
            return out;
        }

        // 5: the marks in their places
        int n = level.checkpointEvery();
        if (path.size() - 1 != level.jumps()) {
            out.add("the path has " + (path.size() - 1) + " jumps, " + level.id() + " has " + level.jumps());
        }
        if (path.get(path.size() - 1) != finish) {
            out.add("the path doesn't end at the finish pad");
        }
        if (cps.size() != level.checkpoints()) {
            out.add(cps.size() + " checkpoints, " + level.id() + " has " + level.checkpoints());
        }
        List<Integer> legEnds = new ArrayList<>();
        legEnds.add(0);
        for (int k = 0; k < cps.size(); k++) {
            int at = path.indexOf(cps.get(k));
            if (at != (k + 1) * n) {
                out.add("checkpoint " + (k + 1) + " is the landing of jump " + at + ", not " + ((k + 1) * n));
            }
            if (at <= legEnds.get(legEnds.size() - 1)) {
                out.add("checkpoint " + (k + 1) + " comes before checkpoint " + k + " on the path");
            }
            legEnds.add(at);
        }
        legEnds.add(path.indexOf(finish));
        checkMark(out, "checkpoint", course.checkpoints(), cps, pads, true);
        checkMark(out, "finish", List.of(course.finish()), List.of(finish), pads, false);

        // 1: every jump, turn, pad size and colour
        String pathColour = Palette.id(switch (level) {
            case EASY -> Palette.PATH_EASY;
            case MEDIUM -> Palette.PATH_MEDIUM;
            case HARD -> Palette.PATH_HARD;
        });
        int[] headings = new int[path.size()];
        for (int k = 0; k < path.size(); k++) {
            Pad p = path.get(k);
            boolean cp = cps.contains(p);
            String kind = k == 0 ? "start" : p == finish ? "finish" : cp ? "checkpoint" : "path";
            checkPad(out, level, p, k, kind, pathColour, blocks);
            if (k == 0) {
                continue;
            }
            Pad a = path.get(k - 1);
            int gx = ParkourPlanner.axisGap(a.x1, a.x2, p.x1, p.x2);
            int gz = ParkourPlanner.axisGap(a.z1, a.z2, p.z1, p.z2);
            int overlap = gx > gz ? ParkourPlanner.overlap(a.z1, a.z2, p.z1, p.z2)
                    : ParkourPlanner.overlap(a.x1, a.x2, p.x1, p.x2);
            String why = JumpRules.problem(level, p.top - a.top, gx, gz, overlap);
            if (why != null) {
                out.add("jump " + k + ": " + why);
            }
            headings[k] = heading(a, p, gx, gz);
            if (k >= 2) {
                int turn = Math.floorMod(headings[k] - headings[k - 1] + 4, 8) - 4;
                if (!JumpRules.turnAllowed(level, turn, cps.contains(a), a.sx(), a.sz())) {
                    out.add("the path turns " + (45 * Math.abs(turn)) + "° on pad " + (k - 1) + " ("
                            + a.sx() + "x" + a.sz() + "), which " + level.id() + " doesn't allow there");
                }
            }
        }
        double yaw = ParkourPlanner.yawToward(start.cx(), start.cz(), path.get(1).cx(), path.get(1).cz());
        double off = Math.abs(Math.floorMod((long) Math.round(course.start().yaw() - yaw) + 180, 360) - 180);
        if (off > 45) {
            out.add("the start faces " + Math.round(off) + "° away from the first jump");
        }

        // 2: headroom and flight
        for (int k = 0; k < path.size(); k++) {
            Pad p = path.get(k);
            Box head = new Box(p.x1 - 1, p.top, p.z1 - 1, p.x2 + 1, p.top + ParkourPlanner.HEADROOM, p.z2 + 1);
            Box flight = k == 0 ? null : flight(path.get(k - 1), p);
            for (int j = 0; j < path.size(); j++) {
                // a neighbour's blocks may stand at the top's own height (a +1 landing), never higher
                Pad o = path.get(j);
                if (j != k && o.blocks().intersects(head) && (Math.abs(j - k) > 1 || o.top - 1 > p.top)) {
                    out.add("pad " + j + " is in the headroom over pad " + k);
                }
                if (flight != null && j != k && j != k - 1 && path.get(j).blocks().intersects(flight)) {
                    out.add("pad " + j + " is in the way of jump " + k);
                }
            }
        }

        // 3: no skips
        for (int i = 0; i < path.size(); i++) {
            for (int j = i + 2; j < path.size(); j++) {
                Pad a = path.get(i);
                Pad b = path.get(j);
                boolean aroundTurn = j == i + 2 && headings[i + 2] != headings[i + 1];
                double dIn = gap(a, path.get(i + 1));
                double need = JumpRules.minSkipGap(level, b.top - a.top, aroundTurn, dIn);
                double g = gap(a, b);
                if (g < need - EPS) {
                    out.add("pads " + i + " and " + j + " are " + JumpRules.fmt(g) + " apart: a player could skip"
                            + " (they need " + JumpRules.fmt(need) + ")");
                }
            }
        }

        // 4: nobody standing on a pad is ever sent back
        for (int leg = 0; leg + 1 < legEnds.size(); leg++) {
            int a = legEnds.get(leg);
            int b = legEnds.get(leg + 1);
            if (a < 0 || b <= a) {
                continue; // out of order: already a problem
            }
            double floor = course.fallY() != null ? course.fallY()
                    : Math.min(path.get(a).top, path.get(b).top) - depth;
            for (int k = a; k <= b; k++) {
                if (course.fallY() == null && (k == a || k == b)) {
                    continue; // a leg's own ends are fall_depth above its floor
                }
                if (path.get(k).top < floor + 2 - EPS) {
                    out.add("pad " + k + " (top " + path.get(k).top + ") is within 2 of the fall height "
                            + JumpRules.fmt(floor));
                }
            }
        }
        if (level.fixedFall() && course.fallY() == null) {
            out.add("easy parkour falls back at a fixed height");
        }

        // 7: no enclosed walkable cell
        for (Pad p : path) {
            if (!openToSky(p, pads, blocks, half)) {
                out.add("the air over pad at " + at(p.x1, p.top, p.z1) + " is closed in");
            }
        }

        // the course's times
        if (course.minSeconds() == null || course.minSeconds() < 5 || trial.refMs() <= 0
                || course.minSeconds() * 1000L > trial.refMs()) {
            out.add("the shortest time " + course.minSeconds() + "s doesn't fit the reference " + trial.refMs() + "ms");
        }
        return out;
    }

    // ---- pads --------------------------------------------------------------------------------

    /** A flat rectangle of blocks: its footprint, inclusive, and its top (one above its blocks). */
    static final class Pad {
        final int x1;
        final int z1;
        final int x2;
        final int z2;
        final int top;

        Pad(int x1, int z1, int x2, int z2, int top) {
            this.x1 = x1;
            this.z1 = z1;
            this.x2 = x2;
            this.z2 = z2;
            this.top = top;
        }

        int sx() {
            return x2 - x1 + 1;
        }

        int sz() {
            return z2 - z1 + 1;
        }

        double cx() {
            return (x1 + x2 + 1) / 2.0;
        }

        double cz() {
            return (z1 + z2 + 1) / 2.0;
        }

        Box blocks() {
            return new Box(x1, top - 1, z1, x2, top - 1, z2);
        }

        boolean over(double x, double z) {
            return x >= x1 && x < x2 + 1 && z >= z1 && z < z2 + 1;
        }
    }

    /** Every solid block grouped into flat pads (side by side at one height); anything else is a problem. */
    private static List<Pad> pads(Plan plan, Map<Long, String> blocks, List<String> out) {
        List<Pad> pads = new ArrayList<>();
        Set<Long> done = new HashSet<>();
        for (BlockOp op : plan.ops()) {
            long k0 = key(op.x(), op.y(), op.z());
            if (!done.add(k0)) {
                continue;
            }
            int x1 = op.x();
            int x2 = op.x();
            int z1 = op.z();
            int z2 = op.z();
            int count = 0;
            ArrayDeque<long[]> queue = new ArrayDeque<>();
            queue.add(new long[]{op.x(), op.z()});
            while (!queue.isEmpty()) {
                long[] c = queue.poll();
                int x = (int) c[0];
                int z = (int) c[1];
                count++;
                x1 = Math.min(x1, x);
                x2 = Math.max(x2, x);
                z1 = Math.min(z1, z);
                z2 = Math.max(z2, z);
                int[][] steps = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                for (int[] s : steps) {
                    long k = key(x + s[0], op.y(), z + s[1]);
                    if (blocks.containsKey(k) && done.add(k)) {
                        queue.add(new long[]{x + s[0], z + s[1]});
                    }
                }
            }
            if (count != (x2 - x1 + 1) * (z2 - z1 + 1)) {
                out.add("the blocks around " + at(op.x(), op.y(), op.z()) + " aren't a flat rectangular pad");
            }
            // a block straight above or below another is part of no flat pad
            for (int x = x1; x <= x2; x++) {
                for (int z = z1; z <= z2; z++) {
                    if (blocks.containsKey(key(x, op.y() + 1, z))) {
                        out.add("the pad at " + at(x1, op.y(), z1) + " has a block on top of it");
                        x = x2;
                        break;
                    }
                }
            }
            pads.add(new Pad(x1, z1, x2, z2, op.y() + 1));
        }
        return pads;
    }

    /** The path: from the start, always on to the nearest pad not yet visited (ties: the first found). */
    private static List<Pad> path(List<Pad> pads, Pad start) {
        List<Pad> path = new ArrayList<>();
        path.add(start);
        Set<Pad> seen = new HashSet<>(path);
        while (path.size() < pads.size()) {
            Pad cur = path.get(path.size() - 1);
            Pad next = null;
            double best = Double.MAX_VALUE;
            for (Pad p : pads) {
                if (!seen.contains(p)) {
                    double g = gap(cur, p);
                    if (g < best - EPS) {
                        best = g;
                        next = p;
                    }
                }
            }
            path.add(next);
            seen.add(next);
        }
        return path;
    }

    /**
     * The jumps of a parkour plan as this validator reads them, in path order: {dy, gx, gz} for
     * each, or an empty list when its pads can't be read. For tests.
     */
    static List<int[]> jumps(Plan plan) {
        List<String> out = new ArrayList<>();
        Map<Long, String> blocks = new HashMap<>();
        for (BlockOp op : plan.ops()) {
            blocks.put(key(op.x(), op.y(), op.z()), Palette.id(plan.blockOf(op)));
        }
        List<Pad> pads = pads(plan, blocks, out);
        if (!(plan.course() instanceof PlannedTrial trial) || trial.course().start() == null) {
            return List.of();
        }
        Course.Spot s = trial.course().start();
        Pad start = padUnder(pads, s.x(), s.y(), s.z());
        if (start == null || !out.isEmpty()) {
            return List.of();
        }
        List<int[]> jumps = new ArrayList<>();
        List<Pad> path = path(pads, start);
        for (int k = 1; k < path.size(); k++) {
            Pad a = path.get(k - 1);
            Pad b = path.get(k);
            jumps.add(new int[]{b.top - a.top, ParkourPlanner.axisGap(a.x1, a.x2, b.x1, b.x2),
                    ParkourPlanner.axisGap(a.z1, a.z2, b.z1, b.z2)});
        }
        return jumps;
    }

    private static Pad padUnder(List<Pad> pads, double x, double y, double z) {
        for (Pad p : pads) {
            if (Math.abs(p.top - y) < EPS && p.over(x, z)) {
                return p;
            }
        }
        return null;
    }

    private static void checkMark(List<String> out, String what, List<Course.Mark> marks, List<Pad> on,
                                  List<Pad> pads, boolean wholePad) {
        for (int k = 0; k < marks.size(); k++) {
            Course.Mark m = marks.get(k);
            Pad p = on.get(k);
            double halfSide = Math.min(p.sx(), p.sz()) / 2.0;
            // where feet can stand: the pad and a half-width past its edges
            double corner = Math.hypot(Math.max(m.x() - p.x1, p.x2 + 1 - m.x()) + ParkourPlanner.STAND,
                    Math.max(m.z() - p.z1, p.z2 + 1 - m.z()) + ParkourPlanner.STAND);
            if (wholePad ? m.radius() < corner - EPS : m.radius() < halfSide - EPS) {
                out.add("the " + what + " at " + at(m.x(), m.y(), m.z()) + " doesn't cover its pad (radius "
                        + JumpRules.fmt(m.radius()) + ")");
            }
            for (Pad other : pads) {
                if (other == p) {
                    continue;
                }
                double ex = Math.max(0, Math.max(other.x1 - m.x(), m.x() - (other.x2 + 1)));
                double ez = Math.max(0, Math.max(other.z1 - m.z(), m.z() - (other.z2 + 1)));
                double ey = other.top - m.y();
                if (ex * ex + ey * ey + ez * ez <= m.radius() * m.radius() + EPS) {
                    out.add("the " + what + " at " + at(m.x(), m.y(), m.z()) + " reaches another pad");
                }
            }
        }
    }

    private static void checkPad(List<String> out, JumpRules.Level level, Pad p, int k, String kind, String pathColour,
                                 Map<Long, String> blocks) {
        boolean sizeOk = switch (kind) {
            case "start", "finish" -> p.sx() == 5 && p.sz() == 5;
            case "checkpoint" -> p.sx() == 3 && p.sz() == 3;
            default -> switch (level) {
                case EASY -> p.sx() == 3 && p.sz() == 3;
                case MEDIUM -> p.sx() == p.sz() && p.sx() <= 2;
                case HARD -> p.sx() * p.sz() <= 2;
            };
        };
        if (!sizeOk) {
            out.add("pad " + k + " (" + kind + ") is " + p.sx() + "x" + p.sz() + ", not a " + level.id() + " " + kind
                    + " pad");
        }
        String colour = switch (kind) {
            case "start" -> Palette.id(Palette.START);
            case "checkpoint" -> Palette.id(Palette.CHECKPOINT);
            case "finish" -> Palette.id(Palette.FINISH);
            default -> pathColour;
        };
        String arrow = Palette.id(Palette.ARROW);
        for (int x = p.x1; x <= p.x2; x++) {
            for (int z = p.z1; z <= p.z2; z++) {
                String b = blocks.get(key(x, p.top - 1, z));
                boolean arrowOk = arrow.equals(b) && ("start".equals(kind)
                        || ("checkpoint".equals(kind) && level == JumpRules.Level.EASY));
                if (!colour.equals(b) && !arrowOk) {
                    out.add("pad " + k + " (" + kind + ") has " + b + " at " + at(x, p.top - 1, z));
                    return;
                }
            }
        }
    }

    // ---- geometry ----------------------------------------------------------------------------

    static double gap(Pad a, Pad b) {
        return JumpRules.gap(ParkourPlanner.axisGap(a.x1, a.x2, b.x1, b.x2),
                ParkourPlanner.axisGap(a.z1, a.z2, b.z1, b.z2));
    }

    private static Box flight(Pad a, Pad b) {
        return new Box(Math.min(a.x1, b.x1) - 1, Math.min(a.top, b.top), Math.min(a.z1, b.z1) - 1,
                Math.max(a.x2, b.x2) + 1, Math.max(a.top, b.top) + ParkourPlanner.HEADROOM, Math.max(a.z2, b.z2) + 1);
    }

    /** The heading of the jump from a to b (0 east ... 7 north-east), read from the gaps between them. */
    private static int heading(Pad a, Pad b, int gx, int gz) {
        int sx = b.x1 > a.x2 ? 1 : b.x2 < a.x1 ? -1 : 0;
        int sz = b.z1 > a.z2 ? 1 : b.z2 < a.z1 ? -1 : 0;
        if (gx > gz) {
            sz = 0;
        } else if (gz > gx) {
            sx = 0;
        }
        for (int h = 0; h < 8; h++) {
            if (ParkourPlanner.DIRS[h][0] == sx && ParkourPlanner.DIRS[h][1] == sz) {
                return h;
            }
        }
        return 0;
    }

    /**
     * Whether the air over a pad reaches open sky: straight up when no other block is overhead,
     * else by a flood fill through the air of the half.
     */
    static boolean openToSky(Pad p, List<Pad> pads, Map<Long, String> blocks, Box half) {
        boolean covered = false;
        for (Pad o : pads) {
            if (o != p && o.top > p.top && o.x1 <= p.x2 && o.x2 >= p.x1 && o.z1 <= p.z2 && o.z2 >= p.z1) {
                covered = true;
                break;
            }
        }
        if (!covered) {
            return true;
        }
        for (int x = p.x1; x <= p.x2; x++) {
            for (int z = p.z1; z <= p.z2; z++) {
                if (!floodsOut(x, p.top, z, blocks, half)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean floodsOut(int x0, int y0, int z0, Map<Long, String> blocks, Box half) {
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        queue.add(new int[]{x0, y0, z0});
        seen.add(key(x0, y0, z0));
        int[][] steps = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (!queue.isEmpty()) {
            int[] c = queue.poll();
            if (c[1] >= half.maxY() || c[0] <= half.minX() || c[0] >= half.maxX() || c[2] <= half.minZ()
                    || c[2] >= half.maxZ()) {
                return true;
            }
            for (int[] s : steps) {
                int x = c[0] + s[0];
                int y = c[1] + s[1];
                int z = c[2] + s[2];
                long k = key(x, y, z);
                if (y >= half.minY() && !blocks.containsKey(k) && seen.add(k)) {
                    queue.add(new int[]{x, y, z});
                }
            }
        }
        return false;
    }

    /**
     * A block position as one long: packed (x and z in 26 bits, y in 12), then scrambled by
     * SplitMix64's finaliser, which is one-to-one, so keys stay unique and spread well in a hash map.
     */
    static long key(int x, int y, int z) {
        long v = ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
        v = (v ^ (v >>> 30)) * 0xBF58476D1CE4E5B9L;
        v = (v ^ (v >>> 27)) * 0x94D049BB133111EBL;
        return v ^ (v >>> 31);
    }

    private static String at(double x, double y, double z) {
        return fmtCoord(x) + " " + fmtCoord(y) + " " + fmtCoord(z);
    }

    private static String fmtCoord(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : JumpRules.fmt(v);
    }
}
