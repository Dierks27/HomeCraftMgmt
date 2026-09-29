package com.dierks.homecraft.games.gen.golf;

import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.GenFailed;
import com.dierks.homecraft.games.gen.api.Palette;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlannedGolf;
import com.dierks.homecraft.games.gen.api.Putt;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.golf.BallPhysics;
import com.dierks.homecraft.games.golf.GolfCourse;
import com.dierks.homecraft.games.golf.GolfShot;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The independent golf check (GEN-SPEC §4.3): a sound plan passes, and one hand-broken plan per
 * rule is caught from its blocks alone — a missing wall (the lane drops into nothing), a wall
 * knocked down to the turf (a ball rolls over it), a wall too high to step over, a block over the
 * lane, a filled cup, a pit, a line that doesn't drop, a par that isn't E + 1, a disallowed
 * block, a block outside the half, two blocks in one spot, overlapping holes, and a hole a sloppy
 * player can't finish within par + 1 (which only the full check, with the kid tree, catches).
 */
class GolfValidatorTest {

    private static Plan good;
    private static GolfCourse.Hole first;
    private static int turf;

    @BeforeAll
    static void plan() throws GenFailed {
        good = new GolfPlanner().plan(GolfKit.input(Slots.TINY_GOLF, 11));
        first = ((PlannedGolf) good.course()).course().holes().get(0);
        turf = (int) first.tee().y();
    }

    private static Plan withOps(UnaryOperator<List<BlockOp>> edit) {
        List<BlockOp> ops = edit.apply(new ArrayList<>(good.ops()));
        return Plan.of(good.slot(), good.algo(), good.seed(), good.half(), good.palette(), ops, good.signs(),
                good.keepClear(), good.course(), good.summary(), good.work());
    }

    private static Plan withCourse(PlannedGolf course) {
        return Plan.of(good.slot(), good.algo(), good.seed(), good.half(), good.palette(), good.ops(), good.signs(),
                good.keepClear(), course, good.summary(), good.work());
    }

    private static short state(String block) {
        return (short) good.palette().indexOf(block);
    }

    private static List<BlockOp> without(List<BlockOp> ops, int x, int y, int z) {
        ops.removeIf(op -> op.x() == x && op.y() == y && op.z() == z);
        return ops;
    }

    private static void caught(Plan bad, String words, String why) {
        List<String> problems = GolfValidator.problems(bad);
        assertTrue(problems.stream().anyMatch(p -> p.contains(words)), why + ": " + problems);
    }

    /** The wall column right beside the tee (inside the lane's bounds, one out from the lane). */
    private static int wallX() {
        return Math.min(first.corner1().x(), first.corner2().x());
    }

    private static int teeZ() {
        return (int) Math.floor(first.tee().z());
    }

    @Test
    void aSoundPlanPasses() {
        assertEquals(List.of(), GolfValidator.problems(good), "the planner's own plan is sound");
        assertEquals(List.of(), GolfValidator.quickProblems(good), "the quick check agrees");
        assertEquals(List.of("not a golf plan"), GolfValidator.problems(null), "and nothing is not a golf plan");
    }

    @Test
    void aMissingWallIsALeak() {
        caught(withOps(ops -> without(without(ops, wallX(), turf, teeZ()), wallX(), turf - 1, teeZ())),
                "leaks", "no wall and no floor: the ball would fall off");
        caught(withOps(ops -> without(ops, wallX(), turf, teeZ())), "leaks",
                "a wall knocked down to the floor is rolled over");
    }

    @Test
    void aWallNoHigherThanARaisedGreenIsALeakAlongItsTop() {
        HoleLayout l = GolfKit.island(6, 8, 10);
        PlanBlocks g = GolfKit.grid(l);
        GolfCourse.Hole h = GolfKit.hole(l);
        assertEquals(List.of(), GolfValidator.holeProblems(g, h, 1), "the island is sound as drawn");
        int wx = GolfKit.PLOT_X + 6;
        int wz = GolfKit.PLOT_Z + 8;
        g.set(wx, GolfKit.TURF + 1, wz, PlanBlocks.AIR); // the wall by the approach, at the green's corner
        assertTrue(GolfValidator.holeProblems(g, h, 1).stream().anyMatch(p -> p.contains("leaks")),
                "a wall only one above the turf there is caught, though it is one above the turf beside it");
        BallPhysics.Hole area = GolfShot.area(g, h);
        boolean out = false;
        for (int yaw = 90; yaw <= 270 && !out; yaw += 5) {
            for (int power = 1; power <= 5 && !out; power++) {
                BallPhysics.Ball ball = new BallPhysics.Ball(GolfKit.PLOT_X + 7.15, GolfKit.TURF + 1,
                        GolfKit.PLOT_Z + 9.05);
                out = GolfShot.play(g, area, ball, new Putt(yaw, power)).penalty();
            }
        }
        assertTrue(out, "and it is a real leak: a ball on the green's corner rolls along the wall's top, out");
    }

    @Test
    void aWallTooHighToStepOverIsCaught() {
        caught(withOps(ops -> {
            ops.add(new BlockOp(wallX(), turf + 1, teeZ(), state(Palette.GOLF_WALL)));
            return ops;
        }), "more than one block high", "a wall two above the turf can't be stepped over");
    }

    @Test
    void aBlockOverTheLaneIsCaught() {
        caught(withOps(ops -> {
            ops.add(new BlockOp((int) Math.floor(first.tee().x()), turf + 2, teeZ() + 2, state(Palette.GOLF_WALL)));
            return ops;
        }), "over its lane", "nothing may hang over the lane but the flag");
    }

    @Test
    void aFilledCupOrAPitIsCaught() {
        GolfCourse.Spot cup = first.cup();
        caught(withOps(ops -> {
            ops.add(new BlockOp(cup.x(), cup.y() + 1, cup.z(), state(Palette.CUP)));
            return ops;
        }), "cup", "a cup filled to the turf isn't a sunken cup");
        int x = (int) Math.floor(first.tee().x());
        int z = teeZ() + 1;
        caught(withOps(ops -> {
            short turfBlock = ops.stream().filter(op -> op.x() == x && op.y() == turf - 1 && op.z() == z)
                    .findFirst().orElseThrow().state();
            without(ops, x, turf - 1, z).add(new BlockOp(x, turf - 2, z, turfBlock));
            return ops;
        }), "hollow", "a pit in the lane is a hollow that isn't the cup");
    }

    @Test
    void aLineThatDoesntDropOrAWrongParIsCaught() {
        PlannedGolf g = (PlannedGolf) good.course();
        List<List<Putt>> witness = new ArrayList<>(g.witness());
        witness.set(0, List.of(new Putt(180, 1)));
        caught(withCourse(new PlannedGolf(g.course(), g.attempts(), witness, List.of(1, g.expert().get(1),
                g.expert().get(2)), g.kid())), "doesn't hole out", "a witness that goes backwards doesn't drop");
        GolfCourse wrongPar = g.course().withHole(1, first.withPar(first.par() + 1));
        caught(withCourse(new PlannedGolf(wrongPar, g.attempts(), g.witness(), g.expert(), g.kid())), "par is",
                "par must be E + 1");
        caught(withCourse(new PlannedGolf(g.course(), g.attempts(), g.witness().subList(0, 2), g.expert(),
                g.kid())), "one entry per hole", "the row stores one line per hole");
    }

    @Test
    void badBlocksAreCaught() {
        Plan sand = Plan.of(good.slot(), good.algo(), good.seed(), good.half(),
                List.of(Palette.TURF_LIGHT, "minecraft:sand"), List.of(new BlockOp(good.half().minX(),
                        good.half().minY(), good.half().minZ(), (short) 1)), List.of(), List.of(), good.course(),
                List.of(), 0);
        caught(sand, "isn't allowed", "sand falls: never in a plan");
        caught(withOps(ops -> {
            ops.add(new BlockOp(good.half().maxX() + 1, turf, good.half().minZ(), (short) 0));
            return ops;
        }), "outside the half", "nothing outside the half");
        caught(withOps(ops -> {
            ops.add(ops.get(0));
            return ops;
        }), "two blocks", "one block per spot");
    }

    @Test
    void overlappingHolesAreCaught() throws GenFailed {
        HoleLayout l = GolfKit.straight(13, 0);
        GolfPlanner.Solved s = GolfPlanner.solve(l, 'E', 0, Work.unlimited());
        Plan twice = GolfPlanner.assemble(GolfKit.input(Slots.DAILY_GOLF, 1), List.of(s, s), 0);
        caught(twice, "overlap", "two holes can't share their area");
    }

    @Test
    void onlyTheFullCheckCatchesAHoleTheSloppyPlayerCantFinish() throws GenFailed {
        GolfPlanner.Solved hard = null;
        for (long seed = 0; seed < 200 && hard == null; seed++) {
            HoleLayout l = GolfKit.draw(HoleTemplate.S_BEND, 'H', seed);
            PlanBlocks g = GolfKit.grid(l);
            GolfCourse.Hole h = GolfKit.hole(l);
            LaneMap lane = LaneMap.of(g, h);
            ExpertSearch.Result es = ExpertSearch.search(g, h, lane, 3, Work.unlimited());
            if (es.found()) {
                int par = GolfPlanner.par(es.strokes());
                if (!KidPolicy.evaluate(g, h, lane, par + 1, Work.unlimited()).within()) {
                    hard = new GolfPlanner.Solved(0, l, es.strokes(), par, par + 1, es.witness());
                }
            }
        }
        assertTrue(hard != null, "some S-bend is too hard for the sloppy player");
        Plan plan = GolfPlanner.assemble(GolfKit.input(Slots.DAILY_GOLF, 1), List.of(hard), 0);
        assertEquals(List.of(), GolfValidator.quickProblems(plan), "its blocks and line are sound");
        caught(plan, "sloppy player", "but the full check replays the kid tree and refuses it");
    }
}
