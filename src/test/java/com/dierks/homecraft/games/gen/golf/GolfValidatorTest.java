package com.dierks.homecraft.games.gen.golf;

import com.dierks.homecraft.games.gen.V2Fixtures;
import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
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
 *
 * <p>These are the frozen rules a layout of golf planner version 2 or older is judged by (Course
 * Variety §1.4), so they are tried on a frozen version-2 plan: Tiny Golf's fixture, whose second
 * hole is a flat straight ({@link V2Fixtures}). Today's planner makes version-3 plans, which
 * {@link GolfValidatorV3Test} covers.
 */
class GolfValidatorTest {

    private static Plan good;
    private static GolfCourse.Hole first;
    private static int turf;

    @BeforeAll
    static void plan() {
        good = V2Fixtures.named("golf-3").plan();
        assertEquals(2, good.algo(), "a frozen version-2 plan");
        first = ((PlannedGolf) good.course()).course().holes().get(1);
        turf = (int) first.tee().y();
    }

    /** {@code plan} as a layout of golf planner version 2 (judged by these frozen rules). */
    private static Plan v2(Plan plan) {
        return Plan.of(plan.slot(), 2, plan.seed(), plan.half(), plan.palette(), plan.ops(), plan.signs(),
                plan.keepClear(), plan.course(), plan.summary(), plan.work());
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

    /** The wall column right beside the flat hole's tee (inside the lane's bounds, one out from the lane). */
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

    /**
     * A layout of golf planner version 2 is built and played without Adventure Golf's rules
     * ({@code LiveBlocks} without sand), so this frozen check replays its stored line the same way:
     * a ball wobbling at a step on a version-2 plan's blocks runs to the roll cap exactly as it always
     * did, where a version-3 plan's blocks bring it to rest in a second (Course Variety's final gate).
     * The bunker is drawn in its own box, clear of any slot.
     */
    @Test
    void aVersion2PlanIsPlayedWithoutAdventureGolfsRulesSoAWobbleRunsToTheRollCapAsItAlwaysDid() {
        Box box = new Box(-5, 60, -3, 12, 70, 12);
        List<String> palette = List.of(Palette.TURF_LIGHT, Palette.SAND_SLAB);
        List<BlockOp> ops = new ArrayList<>();
        for (int x = -5; x <= 12; x++) {
            for (int z = -3; z <= 12; z++) {
                boolean sand = x >= 0 && x <= 4 && z >= 6 && z <= 8; // a sunken bunker, its lip at x = 5
                ops.add(new BlockOp(x, 63, z, (short) (sand ? 1 : 0)));
            }
        }
        BallPhysics.Hole area = BallPhysics.Hole.of(10, 63, 0, -5, 62, -3, 12, 67, 12);
        Putt wedge = new Putt(264.827f, 1); // wedges the ball's edge against the lip (the review's tap)
        int[] ticks = new int[4];
        double[] y = new double[4];
        for (int algo = 2; algo <= 3; algo++) {
            Plan plan = Plan.of(good.slot(), algo, good.seed(), box, palette, ops, List.of(), List.of(),
                    good.course(), good.summary(), good.work());
            PlanBlocks grid = GolfValidator.grid(plan, plan.ops());
            assertEquals(algo > 2, GolfShot.adventure(grid), "version " + algo + " plays Adventure Golf's rules: "
                    + (algo > 2));
            GolfShot.Result tap = GolfShot.play(grid, area, new BallPhysics.Ball(4.6764, 63.5, 7.6773), wedge);
            assertEquals(BallPhysics.Outcome.STOPPED, tap.outcome(), "version " + algo + ": it stops");
            ticks[algo] = tap.ticks();
            y[algo] = tap.y();
        }
        assertEquals(GolfShot.MAX_ROLL_TICKS + 1, ticks[2], "version 2: at the roll cap, exactly as it always did");
        assertTrue(y[2] > 63.8 && y[2] < 64, "in the air above the sand, wedged at the lip: " + y[2]);
        assertEquals(GolfShot.WOBBLE_TICKS, ticks[3], "version 3: at rest a second after it began to wobble");
        assertEquals(63.5, y[3], 0.0, "down on the sand");
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
    void aHoleAPlayerCantStepOutOfIsCaught() {
        HoleLayout island = GolfKit.island(6, 8, 10);
        assertEquals(null, GolfValidator.trapped(GolfKit.grid(island), LaneMap.of(GolfKit.grid(island),
                GolfKit.hole(island)), GolfKit.TURF), "walls two above an island's approach are fine: its green's "
                + "edge is one below them, and the ramp leads there");
        HoleTemplate.Sketch s = new HoleTemplate.Sketch();
        s.lane(7, 11, 2, 16, 0);
        s.lane(8, 10, 8, 10, 1); // a slab ring...
        s.lane(9, 9, 9, 9, 2); // ...round a mound one block up, in the middle of the lane
        s.tee(9, 3);
        s.cup(9, 14);
        HoleLayout mound = s.render(HoleTemplate.STRAIGHT, false, GolfKit.PLOT_X, GolfKit.PLOT_Z, GolfKit.TURF,
                "mound");
        List<String> problems = GolfValidator.holeProblems(GolfKit.grid(mound), GolfKit.hole(mound), 1);
        assertTrue(problems.stream().anyMatch(p -> p.contains("can't step out")),
                "walls one above the mound are two above every lane cell beside them: " + problems);
        assertTrue(problems.stream().noneMatch(p -> p.contains("more than one block high") || p.contains("rolled over")),
                "which the wall-height rules alone don't catch: " + problems);
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
        GolfCourse wrongPar = g.course().withHole(2, first.withPar(first.par() + 1));
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
        Plan twice = v2(GolfPlanner.assemble(GolfKit.input(Slots.DAILY_GOLF, 1), List.of(s, s), 0));
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
        Plan plan = v2(GolfPlanner.assemble(GolfKit.input(Slots.DAILY_GOLF, 1), List.of(hard), 0));
        assertEquals(List.of(), GolfValidator.quickProblems(plan), "its blocks and line are sound");
        caught(plan, "sloppy player", "but the full check replays the kid tree and refuses it");
    }
}
