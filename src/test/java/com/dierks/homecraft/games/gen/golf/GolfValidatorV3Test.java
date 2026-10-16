package com.dierks.homecraft.games.gen.golf;

import com.dierks.homecraft.games.gen.V2Fixtures;
import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.GenFailed;
import com.dierks.homecraft.games.gen.api.Palette;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlannedGolf;
import com.dierks.homecraft.games.gen.api.Putt;
import com.dierks.homecraft.games.gen.api.SignText;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.golf.BallPhysics;
import com.dierks.homecraft.games.golf.GolfCourse;
import com.dierks.homecraft.games.golf.GolfShot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Adventure Golf's independent check (Course Variety §3.8): the older layouts' rules are frozen and
 * an older plan still goes to them; today's templates pass the new rules unchanged; a sound hole of
 * every new kind passes (a pond beside the lane and in its middle, a sunken and a flush bunker, a
 * tree in play, terraces, a dogleg that drops at the elbow); and one hand-broken hole per rule is
 * caught from its blocks alone (§9's golf mutations, and more).
 */
class GolfValidatorV3Test {

    private static final int T = AdventureKit.TURF;

    // ---- the holes ------------------------------------------------------------------------------------

    /** A 7-wide lane with a 3 x 4 pond beside it, walled round its far side (a POND_SIDE on Medium). */
    static AdventureKit.Drawn pondBeside() {
        return AdventureKit.draw(0,
                "#########",
                "#0000000#",
                "#000t000#",
                "#0000000#",
                "#0000000####",
                "#0000000~~~#",
                "#0000000~~~#",
                "#0000000~~~#",
                "#0000000~~~#",
                "#0000000####",
                "#0000000#",
                "#0000000#",
                "#0000000#",
                "#000c000#",
                "#0000000#",
                "#########");
    }

    /** A 7-wide lane with a sunken 3 x 3 bunker across its middle, 2 of turf either side. */
    static AdventureKit.Drawn bunker() {
        return AdventureKit.draw(0,
                "#########",
                "#0000000#",
                "#000t000#",
                "#0000000#",
                "#0000000#",
                "#0000000#",
                "#00kkk00#",
                "#00kkk00#",
                "#00kkk00#",
                "#0000000#",
                "#0000000#",
                "#0000000#",
                "#000c000#",
                "#0000000#",
                "#########");
    }

    /** A 5-wide lane with a flush 3 x 3 patch of sand before the cup. */
    static AdventureKit.Drawn flush() {
        return AdventureKit.draw(0,
                "#######",
                "#00000#",
                "#00t00#",
                "#00000#",
                "#00000#",
                "#00000#",
                "#00000#",
                "#0sss0#",
                "#0sss0#",
                "#0sss0#",
                "#00000#",
                "#00000#",
                "#00c00#",
                "#00000#",
                "#######");
    }

    /** A 9-wide lane with an oak in play halfway (a TREE_GARDEN). */
    static AdventureKit.Drawn tree() {
        return AdventureKit.draw(0,
                "###########",
                "#000000000#",
                "#0000t0000#",
                "#000000000#",
                "#000000000#",
                "#000000000#",
                "#000000000#",
                "#000000000#",
                "#000000000#",
                "#000000000#",
                "#000000000#",
                "#000000000#",
                "#000000000#",
                "#000000000#",
                "#0000c0000#",
                "#000000000#",
                "###########").tree(5, 8, "oak");
    }

    /**
     * Terraces: the tee two blocks up, a step to one up, then the turf. Walls beside the middle
     * terrace stand a block higher (T + 3) while a ball off the top lip could still reach them (the
     * flight rule); past that, and round the bottom, one lower.
     */
    static AdventureKit.Drawn terraces() {
        return AdventureKit.draw(0,
                "%%%%%%%",
                "%44444%",
                "%44444%",
                "%44444%",
                "%44444%",
                "%22222%",
                "%22222%",
                "%22222%",
                "%22222%",
                "%22222%",
                "%22222%",
                "%22222%",
                "%22222%",
                "=22222=",
                "=00000=",
                "=00000=",
                "=00c00=",
                "=00000=",
                "=======").tee(3, 2);
    }

    /** A dogleg whose first leg is a block up and drops at the elbow (DOGLEG_DOWN), its lower walls raised. */
    static AdventureKit.Drawn doglegDown() {
        return AdventureKit.draw(0,
                "=======",
                "=22222=",
                "=22222=",
                "=22222=",
                "=22222=",
                "=22222=",
                "=22222=",
                "=22222============",
                "=2222200000000000=",
                "=2222200000000000=",
                "=22222000000000c0=",
                "=2222200000000000=",
                "=2222200000000000=",
                "==================").tee(3, 2);
    }

    /** A 9-wide lane with a pond by the wall that a hole-in-one skims past (the brave line). */
    static AdventureKit.Drawn brave() {
        return AdventureKit.draw(0,
                "###########",
                "#000000000#",
                "#0000t0000#",
                "#000000000#",
                "#000000000#",
                "#~~~000000#",
                "#~~~000000#",
                "#~~~000000#",
                "#000000000#",
                "#000000000#",
                "#000000000#",
                "#000000000#",
                "#000000000#",
                "#000000000#",
                "#000000000#",
                "#0c0000000#",
                "#000000000#",
                "#000000000#",
                "###########");
    }

    // ---- helpers -------------------------------------------------------------------------------------

    private static List<String> hole(AdventureKit.Drawn d) {
        return GolfValidator.holeProblems(d.grid(), d.hole(2), 1, 3);
    }

    private static void holeSays(AdventureKit.Drawn d, String words, String why) {
        List<String> p = hole(d);
        assertTrue(p.stream().anyMatch(s -> s.contains(words)), why + ": " + p);
    }

    private static Plan plan(AdventureKit.Drawn d) {
        List<Putt> line = d.line();
        assertNotNull(line, "the hole has a par line");
        return AdventureKit.plan(3, List.of(d), List.of(line));
    }

    private static void planSays(Plan p, String words, String why) {
        List<String> problems = GolfValidator.problems(p);
        assertTrue(problems.stream().anyMatch(s -> s.contains(words)), why + ": " + problems);
    }

    /** {@code plan} at another golf planner version. */
    static Plan as(Plan p, int algo) {
        return Plan.of(p.slot(), algo, p.seed(), p.half(), p.palette(), p.ops(), p.signs(), p.keepClear(), p.course(),
                p.summary(), p.work());
    }

    private static Plan withOps(Plan p, List<BlockOp> ops, List<String> palette) {
        return Plan.of(p.slot(), p.algo(), p.seed(), p.half(), palette, ops, p.signs(), p.keepClear(), p.course(),
                p.summary(), p.work());
    }

    // ---- versions --------------------------------------------------------------------------------------

    @Test
    void anOlderPlanStillGoesToTheFrozenRulesAndANewOneToTheNew() {
        V2Fixtures.Fixture f = V2Fixtures.golf().get(0);
        Plan old = f.plan();
        assertEquals(2, old.algo(), "the fixture is a version-2 layout");
        assertFalse(GolfValidator.adventure(old), "judged by the frozen rules");
        assertTrue(GolfValidator.adventure(as(old, 3)), "the same blocks as version 3 by Adventure Golf's");
        assertFalse(GolfValidator.adventure(null), "(and nothing is no plan at all)");
        // A wall on the approach far from the island green, one block above the approach: the old rule ties
        // every wall to the hole's highest lane, the new one checks it where it stands.
        HoleLayout island = GolfKit.island(8, 9, 9);
        PlanBlocks g = GolfKit.grid(island);
        GolfCourse.Hole h = GolfKit.hole(island);
        ExpertSearch.Result es;
        try {
            es = ExpertSearch.search(g, h, LaneMap.of(g, h), ExpertSearch.MAX_DEPTH, Work.unlimited());
        } catch (GenFailed never) {
            throw new IllegalStateException(never);
        }
        assertTrue(es.found(), "the island hole has a par line");
        GolfPlanner.Solved s = new GolfPlanner.Solved(0, island, es.strokes(), GolfPlanner.par(es.strokes()), -1,
                es.witness());
        Plan v2 = as(GolfPlanner.assemble(GolfKit.input(Slots.DAILY_GOLF, 1), List.of(s), 0), 2);
        int wx = island.laneMinX() - 1;
        int wz = island.teeZ() - 1; // beside the lane behind the tee: 8 from the green's lip
        List<BlockOp> ops = new ArrayList<>(v2.ops());
        assertTrue(ops.removeIf(op -> op.x() == wx && op.y() == T + 1 && op.z() == wz), "the wall's top block");
        Plan lowered = withOps(v2, ops, v2.palette());
        assertTrue(GolfValidator.quickProblems(lowered).stream().anyMatch(p -> p.contains("rolled over")),
                "version 2: every wall stands a block above the green, so this one is refused, as it always was");
        assertEquals(List.of(), GolfValidator.quickProblems(as(lowered, 3)),
                "version 3: beside the approach and far from any lip, a wall a block above the approach is enough");
    }

    @Test
    void onlyANewPlanMayHaveAPondAndATeeSignWithAFeature() {
        Plan pond = plan(pondBeside());
        assertEquals(List.of(), GolfValidator.problems(pond), "a sealed pond in a version-3 plan is sound");
        assertTrue(GolfValidator.problems(as(pond, 2)).stream().anyMatch(p -> p.contains("isn't allowed")),
                "the frozen rules never allowed water: an older plan with a pond is refused");
        List<SignText> signs = List.of(new SignText(pond.signs().get(0).x(), pond.signs().get(0).y(),
                pond.signs().get(0).z(), Palette.sign(0), GenCopy.golfTee(1,
                ((PlannedGolf) pond.course()).course().holes().get(0).par(), GenCopy.TeeFeature.WATER)));
        Plan warned = Plan.of(pond.slot(), 3, pond.seed(), pond.half(), pond.palette(), pond.ops(), signs,
                pond.keepClear(), pond.course(), pond.summary(), pond.work());
        assertEquals(List.of(), GolfValidator.problems(warned), "\"Mind the pond!\" is a tee sign saying its par");
        List<SignText> wrong = List.of(new SignText(signs.get(0).x(), signs.get(0).y(), signs.get(0).z(),
                Palette.sign(0), GenCopy.golfTee(1, 6, GenCopy.TeeFeature.WATER)));
        planSays(Plan.of(pond.slot(), 3, pond.seed(), pond.half(), pond.palette(), pond.ops(), wrong,
                pond.keepClear(), pond.course(), pond.summary(), pond.work()), "no tee sign saying its par",
                "but it must say the hole's own par");
    }

    @Test
    void todaysTemplatesAndEveryFrozenCoursePassTheNewRules() throws GenFailed {
        for (HoleTemplate t : HoleTemplate.values()) {
            for (char tier : new char[]{'E', 'M', 'H'}) {
                if (!t.fits(tier) && t != HoleTemplate.SAFE_STRAIGHT) {
                    continue;
                }
                for (long seed = 0; seed < 12; seed++) {
                    HoleLayout l = GolfKit.draw(t, tier, seed);
                    assertEquals(List.of(), GolfValidator.holeProblems(GolfKit.grid(l), GolfKit.hole(l), 1, 3),
                            t + " " + tier + " seed " + seed + ": uniform walls pass the local wall and flight rules");
                }
            }
        }
        for (V2Fixtures.Fixture f : V2Fixtures.golf()) {
            assertEquals(List.of(), GolfValidator.problems(as(f.plan(), 3)),
                    f + ": the whole course passes Adventure Golf's full check too, kid tree and rest spots included");
        }
        Plan fresh = GolfPlanner.v3().plan(GolfKit.input(Slots.TINY_GOLF, 5));
        assertEquals(List.of(), GolfValidator.problems(as(fresh, 3)), "and so does a fresh Tiny Golf");
    }

    // ---- sound Adventure holes -------------------------------------------------------------------------

    @Test
    void aSoundHoleOfEveryNewKindPasses() {
        Map<String, AdventureKit.Drawn> holes = Map.of("a pond beside the lane", pondBeside(),
                "a pond in the middle", AdventureKit.draw(0, LaneMapV3Test.MIDDLE_POND),
                "a sunken bunker", bunker(), "a flush bunker", flush(), "a tree in play", tree(),
                "terraces", terraces(), "a dogleg that drops at the elbow", doglegDown());
        for (Map.Entry<String, AdventureKit.Drawn> e : holes.entrySet()) {
            assertEquals(List.of(), hole(e.getValue()), e.getKey() + ": its blocks are sound");
            Plan p = plan(e.getValue());
            assertEquals(List.of(), GolfValidator.problems(p), e.getKey() + ": the full check, kid tree included");
            assertEquals(List.of(), GolfValidator.quickProblems(p), e.getKey() + ": and the quick one");
        }
    }

    // ---- water (rule 2) ---------------------------------------------------------------------------------

    @Test
    void aPondBlockWithAirBesideItIsCaught() {
        AdventureKit.Drawn d = pondBeside();
        d.set(d.x(11), T - 1, d.z(6), null).set(d.x(11), T, d.z(6), null);
        holeSays(d, "isn't sealed", "the pond's far wall knocked out: it would flow");
        planSays(AdventureKit.plan(3, List.of(d), List.of(pondBeside().line())), "pools must be sealed",
                "and the shared pool rule says so for the plan");
    }

    @Test
    void aPondTwoWideIsCaught() {
        AdventureKit.Drawn d = AdventureKit.draw(0,
                "#########",
                "#0000000#",
                "#000t000#",
                "#0000000#",
                "#0000000###",
                "#0000000~~#",
                "#0000000~~#",
                "#0000000~~#",
                "#0000000~~#",
                "#0000000###",
                "#0000000#",
                "#0000000#",
                "#0000000#",
                "#000c000#",
                "#0000000#",
                "#########");
        holeSays(d, "less than 3 across", "a hard putt skims about 2.7 blocks: a 2-wide pond can be skimmed");
    }

    @Test
    void aPondBesideTheTeeOrNearTheCupIsCaught() {
        AdventureKit.Drawn tee = AdventureKit.draw(0,
                "#########",
                "#0000~~~#",
                "#000t~~~#",
                "#0000~~~#",
                "#0000000#",
                "#0000000#",
                "#0000000#",
                "#0000000#",
                "#0000000#",
                "#0000000#",
                "#0000000#",
                "#000c000#",
                "#0000000#",
                "#########");
        holeSays(tee, "pond beside its tee", "nobody tees off beside the water");
        AdventureKit.Drawn cup = AdventureKit.draw(0,
                "###########",
                "#000000000#",
                "#0000t0000#",
                "#000000000#",
                "#000000000#",
                "#000000000#",
                "#000000000#",
                "#000000000#",
                "#000000000#",
                "#000000~~~#",
                "#000000~~~#",
                "#000000~~~#",
                "#000c00000#",
                "#000000000#",
                "###########");
        holeSays(cup, "within 2 of its cup ring", "the cup ring keeps 2 columns clear of water");
    }

    @Test
    void aPondThatIsntOneDeepAtTheTurfOrBesideASlabOrAtTheEdgeIsCaught() {
        AdventureKit.Drawn deep = pondBeside();
        deep.set(deep.x(9), T - 2, deep.z(6), "minecraft:water[level=0]").set(deep.x(9), T - 3, deep.z(6),
                "minecraft:blue_concrete");
        holeSays(deep, "isn't one block deep", "a pond is one deep, on a solid floor");
        AdventureKit.Drawn high = pondBeside();
        high.set(high.x(9), T, high.z(6), "minecraft:water[level=0]").set(high.x(9), T - 1, high.z(6),
                "minecraft:blue_concrete");
        holeSays(high, "isn't one block deep at T - 1", "a pond's water is at T - 1, below the turf");
        AdventureKit.Drawn slab = pondBeside();
        slab.set(slab.x(7), T, slab.z(6), Palette.RAMP);
        holeSays(slab, "pond beside a slab", "a slab never touches water");
        AdventureKit.Drawn edge = AdventureKit.draw(0,
                "#########",
                "#0000000#",
                "#000t000#",
                "#0000000#",
                "#0000000###",
                "#0000000~~~",
                "#0000000~~~",
                "#0000000~~~",
                "#0000000###",
                "#0000000#",
                "#0000000#",
                "#0000000#",
                "#000c000#",
                "#0000000#",
                "#########");
        holeSays(edge, "edge of its bounds", "a pond is walled inside the hole's bounds");
    }

    @Test
    void aPondOutOfPlayOrWithNoTurfEdgeToStepOutOntoIsCaught() {
        AdventureKit.Drawn walled = AdventureKit.draw(0,
                "#############",
                "#0000000#####",
                "#000t000#####",
                "#0000000#~~~#",
                "#0000000#~~~#",
                "#0000000#~~~#",
                "#0000000#####",
                "#0000000#",
                "#0000000#",
                "#0000000#",
                "#000c000#",
                "#0000000#",
                "#########");
        holeSays(walled, "nowhere beside its lane", "a pond inside the bounds is in play: beside the lane");
        holeSays(walled, "no edge at the turf", "and a child who walks into one can step out");
        AdventureKit.Drawn raised = AdventureKit.draw(0,
                "=========",
                "=2222222=",
                "=2222222=",
                "=2222222====",
                "=2222222~~~=",
                "=2222222~~~=",
                "=2222222~~~=",
                "=2222222====",
                "=2222222=",
                "=2222222=",
                "=2222222=",
                "=2222222=",
                "=========").tee(4, 2).cup(4, 10);
        holeSays(raised, "no edge at the turf", "a pond a block below the lane round it has no edge to step out onto");
    }

    // ---- walls, lips and flights (rules 3-6, 8) -------------------------------------------------------

    @Test
    void aWallInsideTheFlightReachThatIsOneTooLowIsCaught() {
        AdventureKit.Drawn d = terraces();
        d.set(d.x(0), T + 2, d.z(8), null);
        holeSays(d, "within a ball's flight", "one block lower, a ball off the top terrace can fly onto it");
        AdventureKit.Drawn near = terraces();
        near.set(near.x(0), T + 1, near.z(15), null);
        holeSays(near, "within a ball's flight", "beside the turf 10 from the top lip: a ball off it drops a block and"
                + " a half in 11.6, so a wall a block above the turf there is still in reach");
        AdventureKit.Drawn far = doglegDown();
        far.set(far.x(17), T + 1, far.z(10), null);
        assertEquals(List.of(), hole(far), "at the far end of the lower leg, 11 from the lip, a block above the turf"
                + " is enough");
    }

    /**
     * The rail rule (the review's dogleg): a ball can rest hanging off a lip, its centre over the
     * lower lane and its edge on the upper one; rolled along the ledge into a wall the ledge runs
     * into, its edge rides that wall's lower block at the upper height, over the lower lane, and a
     * ring wall no higher than that rail is rolled over. So once a rail exists, every ring wall
     * stands a block above it — checked from the blocks, never trusted to the template.
     */
    @Test
    void aRingWallNoHigherThanARailABallCanRideIsCaught() {
        HoleLayout l = GolfKit.draw(HoleTemplate.DOGLEG_DOWN, 'H', 13);
        PlanBlocks g = GolfKit.grid(l);
        GolfCourse.Hole h = GolfKit.hole(l);
        assertEquals(List.of(), GolfValidator.holeProblems(g, h, 1, 3), l.describe() + " is sound as drawn");
        BallPhysics.Ball ledge = new BallPhysics.Ball(4870.755364587491, T + 1.0, 4111.041776318689);
        assertEquals(BallPhysics.Outcome.STOPPED, GolfShot.play(g, GolfShot.area(g, h), ledge,
                new Putt(269.802f, 5)).outcome(), "as drawn, a ball riding the rail bounces off the end wall");
        g.set(4879, T + 1, 4111, PlanBlocks.AIR); // the lower leg's end wall, a block above the turf
        List<String> p = GolfValidator.holeProblems(g, h, 1, 3);
        assertTrue(p.stream().anyMatch(s -> s.contains("rail")), "a block lower, the end wall is no higher than the"
                + " rail a ball hanging off the ledge rides: " + p);
        GolfShot.Result out = GolfShot.play(g, GolfShot.area(g, h), new BallPhysics.Ball(4870.755364587491, T + 1.0,
                4111.041776318689), new Putt(269.802f, 5));
        assertEquals(BallPhysics.Outcome.OUT, out.outcome(), "and it is a real way out: the ball rides the rail over"
                + " it (" + out + ")");
    }

    @Test
    void aThreeHighRingWallBesideATurfLaneIsCaught() {
        AdventureKit.Drawn d = pondBeside();
        d.set(d.x(0), T + 1, d.z(11), Palette.GOLF_WALL).set(d.x(0), T + 2, d.z(11), Palette.GOLF_WALL);
        holeSays(d, "more than two blocks above", "a wall three above the turf beside it is too tall");
    }

    @Test
    void aWallLowEnoughToRollOverOrWithAGapIsALeak() {
        HoleLayout island = GolfKit.island(6, 8, 10);
        PlanBlocks g = GolfKit.grid(island);
        g.set(GolfKit.PLOT_X + 6, GolfKit.TURF + 1, GolfKit.PLOT_Z + 8, PlanBlocks.AIR);
        assertTrue(GolfValidator.holeProblems(g, GolfKit.hole(island), 1, 3).stream()
                        .anyMatch(p -> p.contains("can be rolled over")),
                "the island's corner wall as high as the green beside it (diagonally): a ball rides its top out");
        AdventureKit.Drawn low = terraces();
        low.set(low.x(0), T + 2, low.z(2), null);
        holeSays(low, "leaks", "a wall as high as the top terrace is lane, at the bounds' edge: it leaks");
        AdventureKit.Drawn gap = pondBeside();
        gap.set(gap.x(0), T - 1, gap.z(11), null);
        holeSays(gap, "has a gap", "a wall open at its foot");
        AdventureKit.Drawn none = pondBeside();
        none.set(none.x(0), T - 1, none.z(11), null).set(none.x(0), T, none.z(11), null);
        holeSays(none, "drops into nothing", "no wall and no floor");
    }

    @Test
    void aLipOfABlockAndAHalfIsCaught() {
        AdventureKit.Drawn d = terraces();
        d.set(d.x(3), T + 1, d.z(13), Palette.RAMP);
        holeSays(d, "lip of more than a block", "a slab on the middle terrace's edge: a drop of a block and a half");
    }

    @Test
    void slimeInALaneFloorIsCaught() {
        AdventureKit.Drawn d = pondBeside();
        d.set(d.x(3), T - 1, d.z(11), Palette.BUMPER);
        holeSays(d, "slime in its lane floor", "slime would lift the ball above its lane (the flight rule's premise)");
    }

    @Test
    void aLaneMoreThanTwoBlocksUpIsCaught() {
        AdventureKit.Drawn d = terraces();
        d.set(d.x(3), T + 2, d.z(3), Palette.RAMP);
        holeSays(d, "rises more than two blocks", "the ball's layer ends at T + 2");
    }

    @Test
    void anObstacleLowerThanABlockAboveTheLaneBesideItIsCaught() {
        AdventureKit.Drawn d = AdventureKit.draw(0,
                "#########",
                "#0000000#",
                "#000t000#",
                "#0000000#",
                "#0000000#",
                "#0110000#",
                "#0110000#",
                "#000x000#",
                "#0000000#",
                "#0000000#",
                "#0000000#",
                "#000c000#",
                "#0000000#",
                "#########");
        holeSays(d, "obstacle", "a rock a block above the turf, diagonal to a slab: a ball on the slab climbs onto it");
    }

    // ---- hollows and stranding (rules 7, 10) ----------------------------------------------------------

    @Test
    void aBunkerHollowOfOneByOneIsCaught() {
        AdventureKit.Drawn d = AdventureKit.draw(0,
                "#########",
                "#0000000#",
                "#000t000#",
                "#0000000#",
                "#0000000#",
                "#0000000#",
                "#000k000#",
                "#0000000#",
                "#0000000#",
                "#0000000#",
                "#000c000#",
                "#0000000#",
                "#########");
        holeSays(d, "hollow", "a one-block sand pit is a hollow, not a bunker");
        AdventureKit.Drawn strip = AdventureKit.draw(0,
                "#########",
                "#0000000#",
                "#000t000#",
                "#0000000#",
                "#0000000#",
                "#0000000#",
                "#0kkkkk0#",
                "#0000000#",
                "#0000000#",
                "#0000000#",
                "#000c000#",
                "#0000000#",
                "#########");
        holeSays(strip, "dips below the turf", "and a bunker one row deep isn't 2 x 2 anywhere");
    }

    @Test
    void aStrandedLaneCellIsCaught() {
        AdventureKit.Drawn d = AdventureKit.draw(0,
                "%%%%%%%%%%%",
                "%22222%%%%%",
                "%22222%%%%%",
                "%22222%%%%%",
                "%222222000=",
                "%222222000=",
                "%222222000=",
                "%22222%%%%%",
                "%22222%%%%%",
                "%22222%%%%%",
                "%%%%%%%%%%%").tee(3, 2).cup(3, 8);
        holeSays(d, "never leave for the cup", "a bay a block down: a ball that drops in can't get back up");
    }

    // ---- headroom and canopies (rule 9) --------------------------------------------------------------

    @Test
    void aCanopyAtSurfacePlusTwoIsCaught() {
        AdventureKit.Drawn d = tree();
        d.leaf(d.x(2), T + 2, d.z(5), "oak");
        holeSays(d, "leaves in the ball's layer", "leaves two above the turf: in the ball's layer, a kid bumps them");
        AdventureKit.Drawn up = terraces();
        up.leaf(up.x(3), T + 3, up.z(8), "oak");
        holeSays(up, "block over its lane", "leaves two above the middle terrace: over the lane, below surface + 3");
    }

    @Test
    void aCanopyOverTheFlagOrNearTheTeeIsCaught() {
        AdventureKit.Drawn flag = tree();
        flag.leaf(flag.x(5), T + 6, flag.z(14), "oak");
        holeSays(flag, "canopy within 2 of its cup ring or flag", "the flag must be seen from the tee");
        AdventureKit.Drawn tee = AdventureKit.draw(0,
                "###########",
                "#000000000#",
                "#0000t0000#",
                "#000000000#",
                "#000000000#",
                "#000000000#",
                "#000000000#",
                "#000000000#",
                "#000000000#",
                "#000000000#",
                "#000000000#",
                "#0000c0000#",
                "#000000000#",
                "###########").tree(5, 5, "birch");
        holeSays(tee, "canopy within 2 of its tee", "a tree 3 from the tee hangs its leaves within 2 of it");
    }

    // ---- scenery and leaves (rule 13) ----------------------------------------------------------------

    private static List<Map.Entry<int[], String>> decoration(AdventureKit.Drawn d, int x, int z) {
        List<Map.Entry<int[], String>> out = new ArrayList<>();
        out.add(Map.entry(new int[]{x, T - 1, z}, Palette.MOSS));
        for (int y = T; y <= T + 2; y++) {
            out.add(Map.entry(new int[]{x, y, z}, Palette.log("cherry")));
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx != 0 || dz != 0) { // beside the trunk 1 from a log, at a corner 2
                    out.add(Map.entry(new int[]{x + dx, T + 2, z + dz}, Palette.leaves("cherry",
                            dx == 0 || dz == 0 ? 1 : 2)));
                }
            }
        }
        out.add(Map.entry(new int[]{x, T + 3, z}, Palette.leaves("cherry", 1)));
        return out;
    }

    @Test
    void sceneryTwoColumnsClearOfTheHoleIsFineAndInsideItsGridIsCaught() {
        AdventureKit.Drawn d = flush();
        List<Putt> line = d.line();
        int clear = d.bounds().maxX() + 3; // the canopy reaches one further: 2 clear
        Plan fine = AdventureKit.plan(3, List.of(d), List.of(line), decoration(d, clear, d.z(6)));
        assertEquals(List.of(), GolfValidator.problems(fine), "a tree on a planter, 2 columns clear of the hole");
        Plan close = AdventureKit.plan(3, List.of(d), List.of(line), decoration(d, clear - 1, d.z(6)));
        planSays(close, "within 1 column of a hole's bounds", "scenery inside the ball's grid (the bounds + 1)");
        List<Map.Entry<int[], String>> high = new ArrayList<>(decoration(d, clear, d.z(6)));
        high.add(Map.entry(new int[]{clear, T + 7, d.z(6)}, Palette.MOSS));
        planSays(AdventureKit.plan(3, List.of(d), List.of(line), high), "above T + 6", "scenery stays low");
        List<Map.Entry<int[], String>> stray = List.of(Map.entry(new int[]{AdventureKit.HALF.minX() + 21, T - 1,
                d.z(3)}, Palette.MOSS));
        planSays(AdventureKit.plan(3, List.of(d), List.of(line), stray), "outside every plot",
                "scenery in the gap between plots belongs to no hole");
    }

    @Test
    void leavesTooNearTheHalfsEdgeOrOfTheWrongDistanceAreCaught() {
        AdventureKit.Drawn d = flush();
        List<Putt> line = d.line();
        List<Map.Entry<int[], String>> edge = List.of(
                Map.entry(new int[]{AdventureKit.HALF.minX(), T - 1, d.z(20)}, Palette.MOSS),
                Map.entry(new int[]{AdventureKit.HALF.minX(), T, d.z(20)}, Palette.log("oak")),
                Map.entry(new int[]{AdventureKit.HALF.minX() + 1, T, d.z(20)}, Palette.leaves("oak", 1)));
        planSays(AdventureKit.plan(3, List.of(d), List.of(line), edge), "columns inside the half",
                "a leaf at the half's edge: a block outside could change its distance");
        List<Map.Entry<int[], String>> wrong = new ArrayList<>(decoration(d, d.bounds().maxX() + 3, d.z(6)));
        wrong.set(wrong.size() - 1, Map.entry(wrong.get(wrong.size() - 1).getKey(), Palette.leaves("cherry", 3)));
        planSays(AdventureKit.plan(3, List.of(d), List.of(line), wrong), "wrong distance",
                "a leaf whose distance isn't vanilla's would change in the verify pass");
    }

    @Test
    void aDecorativePondIsSoundOrCaught() {
        AdventureKit.Drawn d = flush();
        List<Putt> line = d.line();
        int x0 = d.bounds().maxX() + 3;
        assertEquals(List.of(), GolfValidator.problems(AdventureKit.plan(3, List.of(d), List.of(line),
                decorativePond(x0, d.z(3), 3, 6))), "a 3 x 6 pond to look at, 2 clear, rimmed with moss at the turf");
        planSays(AdventureKit.plan(3, List.of(d), List.of(line), decorativePond(x0, d.z(3), 2, 6)),
                "decorative pond block", "a decorative pond keeps the 3-across rule too");
    }

    /** A pond {@code w} x {@code l} of still water at T - 1 on blue concrete, rimmed with moss (top T). */
    private static List<Map.Entry<int[], String>> decorativePond(int x0, int z0, int w, int l) {
        List<Map.Entry<int[], String>> out = new ArrayList<>();
        for (int x = x0 - 1; x <= x0 + w; x++) {
            for (int z = z0 - 1; z <= z0 + l; z++) {
                boolean water = x >= x0 && x < x0 + w && z >= z0 && z < z0 + l;
                if (water) {
                    out.add(Map.entry(new int[]{x, T - 2, z}, "minecraft:blue_concrete"));
                    out.add(Map.entry(new int[]{x, T - 1, z}, "minecraft:water[level=0]"));
                } else {
                    out.add(Map.entry(new int[]{x, T - 1, z}, Palette.MOSS));
                }
            }
        }
        return out;
    }

    // ---- play (rule 12) --------------------------------------------------------------------------------

    @Test
    void aWitnessTwinThatEndsInWaterOnAPondHoleIsCaught() throws GenFailed {
        AdventureKit.Drawn d = brave();
        PlanBlocks g = d.grid();
        GolfCourse.Hole h = d.hole(2);
        LaneMap lane = LaneMap.of(g, h, 3);
        ExpertSearch.Result plain = ExpertSearch.search(g, h, lane, ExpertSearch.MAX_DEPTH, Work.unlimited());
        assertTrue(plain.found() && SafeExpert.unsafePutt(g, h, plain.witness()) > 0,
                "the fewest putts skim past the pond: 3 degrees off, it splashes " + plain.witness());
        planSays(AdventureKit.plan(3, List.of(d), List.of(plain.witness())), "splashes",
                "so a plan whose par is that brave line is refused");
        assertEquals(List.of(), GolfValidator.problems(plan(d)), "the safe line (SafeExpert's) passes");
    }

    @Test
    void aRestSpotIsOnTheLaneOnlyWhenTheBallSitsOnALaneCell() {
        AdventureKit.Drawn d = terraces();
        PlanBlocks g = d.grid();
        LaneMap lane = LaneMap.of(g, d.hole(3), 3);
        assertTrue(GolfValidatorV3.restsOnLane(lane, d.x(3) + 0.5, T + 1, d.z(8) + 0.5), "on the middle terrace");
        assertFalse(GolfValidatorV3.restsOnLane(lane, d.x(0) + 0.5, T + 3, d.z(8) + 0.5), "on a wall's top");
        assertFalse(GolfValidatorV3.restsOnLane(lane, d.x(3) + 0.5, T + 2, d.z(8) + 0.5),
                "in the air over the lane isn't resting on it");
        assertTrue(GolfValidatorV3.restsOnLane(lane, d.x(3) + 0.5, T + 2, d.z(5) + 0.1),
                "held up by the top terrace's edge, its centre just over the step: resting on the lane");
    }

    // ---- risk 10: the old ISLAND and RAMP holes -------------------------------------------------------

    /**
     * Whether any putt (every 5 degrees, every power) from a ball resting anywhere on the hole's
     * raised lane (each cell's middle, and out toward every lower neighbour) ends out of bounds or at
     * rest off the lane.
     */
    private static boolean escapes(PlanBlocks g, GolfCourse.Hole h) {
        LaneMap lane = LaneMap.of(g, h, 3);
        BallPhysics.Hole area = GolfShot.area(g, h);
        double[][] offs = {{0, 0}, {0.35, 0}, {-0.35, 0}, {0, 0.35}, {0, -0.35}};
        for (int x = lane.minX; x < lane.minX + lane.sizeX; x++) {
            for (int z = lane.minZ; z < lane.minZ + lane.sizeZ; z++) {
                double s = lane.surface(x, z);
                if (!lane.isLane(x, z) || s <= lane.turfY + 1e-6) {
                    continue;
                }
                for (double[] o : offs) {
                    int nx = x + (int) Math.signum(o[0]);
                    int nz = z + (int) Math.signum(o[1]);
                    if ((o[0] != 0 || o[1] != 0) && (!lane.isLane(nx, nz) || lane.surface(nx, nz) > s + 1e-6)) {
                        continue; // a ball rests only where its footprint fits
                    }
                    for (int yaw = 0; yaw < 360; yaw += 5) {
                        for (int p = 1; p <= BallPhysics.clubs(); p++) {
                            GolfShot.Result r = GolfShot.play(g, area, new BallPhysics.Ball(x + 0.5 + o[0], s,
                                    z + 0.5 + o[1]), new Putt(yaw, p));
                            if (r.outcome() == BallPhysics.Outcome.OUT || !r.inCup() && !r.penalty()
                                    && !GolfValidatorV3.restsOnLane(lane, r.x(), r.y(), r.z())) {
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    @Test
    void loweringAnyWallOfAnOldIslandOrRampHoleIsCaughtUnlessNoBallCanUseIt() {
        for (HoleLayout l : List.of(GolfKit.island(8, 9, 9), GolfKit.draw(HoleTemplate.RAMP, 'M', 1))) {
            PlanBlocks base = GolfKit.grid(l);
            GolfCourse.Hole h = GolfKit.hole(l);
            assertEquals(List.of(), GolfValidator.holeProblems(base, h, 1, 3), l.describe() + " is sound as drawn");
            assertFalse(escapes(base, h), l.describe() + ": no ball leaves it as drawn");
            LaneMap lane = LaneMap.of(base, h, 3);
            int flights = 0;
            int allowed = 0;
            int realChecked = 0;
            for (int x = lane.minX; x < lane.minX + lane.sizeX; x++) {
                for (int z = lane.minZ; z < lane.minZ + lane.sizeZ; z++) {
                    if (lane.isLane(x, z) || !base.solid(x, T + 1, z)) {
                        continue;
                    }
                    PlanBlocks m = GolfKit.grid(l);
                    m.set(x, T + 1, z, PlanBlocks.AIR); // this wall one block lower: a block above the approach
                    List<String> p = GolfValidator.holeProblems(m, h, 1, 3);
                    String where = l.describe() + ", the wall at " + (x - l.laneMinX()) + "," + (z - l.laneMinZ());
                    if (p.isEmpty()) {
                        allowed++;
                        assertFalse(escapes(m, h), where + " is allowed lower, and no putt from anywhere uses it");
                    } else if (p.stream().allMatch(s -> s.contains("flight"))) {
                        flights++; // only the flight rule caught it: is it a real way out?
                        if (realChecked == 0 && escapes(m, h)) {
                            realChecked++;
                        }
                    }
                }
            }
            assertTrue(flights > 0, l.describe() + ": the flight rule catches walls the local rule would allow");
            assertEquals(1, realChecked, l.describe() + ": and at least one of those is a real way out, by play");
            if (l.template() == HoleTemplate.ISLAND) {
                assertTrue(allowed > 0, l.describe() + ": and walls far from the green may stand lower");
            }
        }
    }
}
