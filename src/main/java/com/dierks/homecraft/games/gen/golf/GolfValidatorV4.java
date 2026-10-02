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
import java.util.Locale;
import java.util.Set;

/**
 * Golf v4's independent check (GOLF-V4-SPEC §6.1): a plan of golf planner version 4 or later, read
 * from its blocks alone. The blocks are judged by Adventure Golf's rules, unchanged
 * ({@link GolfValidatorV3}: the whole plan's palette and ponds, scenery clear of the holes, and per
 * hole rules 1-11 and 13), on the plan's own plots ({@link PlotGrid}); the play is Golf v4's:
 * <ul>
 *   <li><b>Par</b> is worked out again from the blocks: the ordinary player
 *       ({@link OrdinaryPar}, the child on Tiny Golf) plays every hole 64 times on the full plan's
 *       grid, and the course is balanced as the planner balanced it; every hole's par must be that,
 *       2-6, and its tee sign must say it. Off Tiny Golf a hole's path along the lane is its par's
 *       length (V4-DECISIONS D1: par 2 8-12, 3 16-25, 4 27-38, 5 40-52, give or take a block).</li>
 *   <li><b>The witness</b> replays from the tee into the cup in exactly its E putts with no penalty,
 *       1 ≤ E ≤ par, every spot it rests at on the lane, and on a hole with water in play every putt's
 *       twins three degrees either side stay dry.</li>
 *   <li><b>The sloppy player</b> (the full check only): {@link KidPolicy}'s tree finishes within par + 2
 *       (Tiny Golf: par + 1), never wet, every spot it rests at on the lane.</li>
 * </ul>
 * The quick check (no kid tree) costs about 64 rollouts a hole: a few hundred milliseconds a course,
 * on the planner thread. Pure.
 */
final class GolfValidatorV4 {

    private GolfValidatorV4() {
    }

    /** Every problem with a Golf v4 plan; with {@code kid}, the sloppy player's tree too. */
    static List<String> problems(Plan plan, boolean kid) {
        List<String> out = new ArrayList<>();
        if (plan == null || !(plan.course() instanceof PlannedGolf golf)) {
            out.add("not a golf plan");
            return out;
        }
        Box half = plan.half();
        for (String p : Palette.problems(plan.palette())) {
            if (!Palette.poolWater(p)) { // a pond's still water: the pool rule says where it may be
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
        PlotGrid geometry = PlotGrid.of(plan.slot(), plan.algo());
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
            int[] p = geometry.plot(half, i);
            if (area.minX() < p[0] || area.minZ() < p[1] || area.maxX() > p[0] + geometry.plotX() - 1
                    || area.maxZ() > p[1] + geometry.plotZ() - 1) {
                out.add("hole " + (i + 1) + "'s bounds overrun its plot (" + geometry.plotX() + " x "
                        + geometry.plotZ() + " at " + p[0] + " " + p[1] + ")");
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
        out.addAll(GolfValidatorV3.outsideProblems(plan, inside, grid, areas));
        OrdinaryPar.Model model = OrdinaryPar.model(plan.slot());
        int kidOver = GolfPlannerV4.kidOver(plan.slot());
        double[] means = new double[n];
        double[] paths = new double[n];
        boolean sound = true;
        List<LaneMap> lanes = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            GolfCourse.Hole h = course.holes().get(i);
            List<String> hp = GolfValidatorV3.holeProblems(grid, h, i + 1);
            out.addAll(hp);
            LaneMap lane = LaneMap.of(grid, h, plan.algo());
            lanes.add(lane);
            if (!hp.isEmpty()) {
                sound = false;
                continue;
            }
            paths[i] = GolfPlannerV4.path(grid, h, lane);
            try {
                means[i] = OrdinaryPar.mean(grid, h, lane, model, Work.unlimited());
            } catch (GenFailed never) {
                out.add("hole " + (i + 1) + ": the ordinary player's check was cancelled");
                sound = false;
            }
        }
        if (!sound) {
            return out; // par is the course's: it can't be worked out while a hole's blocks are wrong
        }
        boolean banded = !GolfPlanner.dry(plan.slot());
        int[] par = OrdinaryPar.balance(means, banded ? paths : null, OrdinaryPar.most(plan.slot()));
        double off = OrdinaryPar.off(par, means);
        if (Math.abs(off) > 1 + 1e-9) {
            out.add(String.format(Locale.ROOT, "the course's par is %+.2f from what its holes measure (more than one"
                    + " stroke)", off));
        }
        for (int i = 0; i < n; i++) {
            GolfCourse.Hole h = course.holes().get(i);
            Box area = areas.get(i);
            int number = i + 1;
            if (banded && !GolfPlannerV4.inBand(paths[i], LengthClass.ofPar(h.par()))) {
                out.add(String.format(Locale.ROOT, "hole %d is %.1f blocks: not a par %d's length", number, paths[i],
                        h.par()));
            }
            if (h.par() != par[i]) {
                out.add(String.format(Locale.ROOT, "hole %d's par is %d, but its blocks measure %d (the %s's mean %.2f,"
                        + " the course balanced)", number, h.par(), par[i], model == OrdinaryPar.Model.CHILD ? "child"
                        : "first-timer", means[i]));
            }
            if (plan.signs().stream().noneMatch(st -> GenCopy.golfTeeFeature(st.lines(), number, h.par()) != null
                    && area.contains(st.x(), st.y(), st.z()))) {
                out.add("hole " + number + " has no tee sign saying its par");
            }
            List<Putt> witness = golf.witness().get(i);
            int e = witness.size();
            if (e < 1 || e > OrdinaryPar.CAP) {
                out.add("hole " + number + "'s line has " + e + " putts");
                continue;
            }
            if (golf.expert().get(i) != e) {
                out.add("hole " + number + "'s E is " + golf.expert().get(i) + " but its line has " + e + " putts");
            }
            if (e > h.par()) {
                out.add("hole " + number + "'s line takes " + e + " putts, over its par " + h.par());
            }
            String line = lineProblem(grid, h, lanes.get(i), witness, number);
            if (line != null) {
                out.add(line);
                continue;
            }
            if (kid) {
                out.addAll(kidProblems(grid, h, lanes.get(i), h.par() + kidOver, number));
            }
        }
        return out;
    }

    /**
     * The witness line (§3.5): it replays from the tee into the cup in exactly its putts, no penalty,
     * every spot it rests at on the way on the lane, and on a hole with water in play every putt's
     * twins ({@value SafeExpert#TWIN_DEGREES} degrees either side) stay dry. {@code null} when it does.
     */
    static String lineProblem(BallPhysics.Blocks grid, GolfCourse.Hole h, LaneMap lane, List<Putt> witness, int n) {
        int e = witness.size();
        GolfShot.Replay replay = GolfShot.replay(grid, h, witness);
        if (!replay.holed() || replay.putts() != e) {
            return "hole " + n + "'s line doesn't hole out in " + e;
        }
        if (replay.strokes() != e) {
            return "hole " + n + "'s line takes a penalty on the way";
        }
        BallPhysics.Hole area = GolfShot.area(grid, h);
        BallPhysics.Ball ball = GolfShot.tee(grid, h);
        for (Putt p : witness) {
            GolfShot.Result r = GolfShot.play(grid, area, ball, p);
            if (r.inCup()) {
                break;
            }
            if (!GolfValidatorV3.restsOnLane(lane, r.x(), r.y(), r.z())) {
                return "hole " + n + "'s line leaves the ball off its lane at " + spot(r);
            }
        }
        if (lane.hazards() > 0) {
            int bad = SafeExpert.unsafePutt(grid, h, witness);
            if (bad > 0) {
                return "hole " + n + "'s putt " + bad + " of its line splashes " + SafeExpert.TWIN_DEGREES
                        + " degrees off: a witness on a pond hole has room to miss";
            }
        }
        return null;
    }

    /** The sloppy player's tree: within {@code limit}, never wet, every rest spot on the lane. */
    static List<String> kidProblems(PlanBlocks grid, GolfCourse.Hole h, LaneMap lane, int limit, int n) {
        List<String> out = new ArrayList<>();
        GolfShot.Result[] off = new GolfShot.Result[1];
        GolfShot.Result[] wet = new GolfShot.Result[1];
        try {
            KidPolicy.Result k = KidPolicy.evaluate(grid, h, lane, limit, Work.unlimited(), r -> {
                if (wet[0] == null && r.penalty()) {
                    wet[0] = r;
                }
                if (off[0] == null && !r.inCup() && !r.penalty() && !GolfValidatorV3.restsOnLane(lane, r.x(), r.y(),
                        r.z())) {
                    off[0] = r;
                }
            });
            if (!k.within()) {
                out.add("hole " + n + ": a sloppy player can need " + k.worst() + " strokes (par " + h.par()
                        + ", at most " + limit + ")");
            }
        } catch (GenFailed never) {
            out.add("hole " + n + ": the sloppy player's check was cancelled");
        }
        if (wet[0] != null) {
            out.add("hole " + n + ": a sloppy player's ball can splash or leave the bounds");
        }
        if (off[0] != null) {
            out.add("hole " + n + ": a sloppy player's ball can come to rest off the lane at " + spot(off[0]));
        }
        return out;
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
