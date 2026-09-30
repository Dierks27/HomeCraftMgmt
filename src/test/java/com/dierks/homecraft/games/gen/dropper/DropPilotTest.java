package com.dierks.homecraft.games.gen.dropper;

import com.dierks.homecraft.games.gen.api.Box;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pilots and the fall through real blocks (EVENTS-DROPPER-SPEC §B.1.6 rules 1-2): the pilots
 * leave from along the whole front edge and from every moment of a walking step, a straight stack of
 * openings lets every one through, a wall-to-wall plate stops every one, a late pilot really is
 * late, and the swept box catches the clips a point-by-point check would miss.
 */
class DropPilotTest {

    /** The hand-built Easy shaft's two layers: 13 and 23 below the ledge top. */
    private static final int ROW1 = 56 - 13 - 1;
    private static final int ROW2 = 56 - 23 - 1;

    private static DropperFixtures.Shaft straightStack() {
        // 7 x 7 openings stacked in front of the ledge (x 6-8, z 2-4), where a walk-off falls
        return new DropperFixtures.Shaft().plate(ROW1, 4, 4, 10, 10).plate(ROW2, 4, 4, 10, 10);
    }

    private static List<DropPilot.Target> targets(DropCheck.View v) {
        return List.of(new DropPilot.Target(7.5, 7.5, ROW1), new DropPilot.Target(7.5, 7.5, ROW2),
                new DropPilot.Target(v.pool().x(), v.pool().z(), Double.NaN));
    }

    // ---- where and when they leave ------------------------------------------------------------------

    /** How many pilots a level flies: every exit and timing, walk or jump, every delay; then the sloppy runs. */
    private static int pilots() {
        return DropPilot.EXITS.length * DropPilot.TIMINGS.length * 2 * 3
                + DropPilot.SLOPPY_EXITS.length * DropPilot.TIMINGS.length;
    }

    @Test
    void everyExitAndEveryMomentOfTheStepIsFlownWalkingOrJumpingAtEveryDelayPlusTheSloppyRuns() {
        DropperFixtures.Shaft sh = straightStack();
        DropCheck.View v = sh.view();
        for (DropRules.Level tier : DropRules.Level.values()) {
            List<DropPilot.Variant> all = DropPilot.variants(v.edgeX(), v.edgeZ(), v.fx(), v.fz(), v.ledgeTop(),
                    targets(v), tier);
            assertEquals(pilots(), all.size(), tier + ": exits x timings x walk or jump x 3 delays, + the sloppy runs");
            Set<String> labels = new HashSet<>();
            int jumps = 0;
            int sloppy = 0;
            for (DropPilot.Variant p : all) {
                labels.add(p.label());
                jumps += p.start().vy() > 0 ? 1 : 0;
                sloppy += p.label().startsWith("sloppy") ? 1 : 0;
                assertTrue(Math.abs(p.start().x() - v.edgeX()) <= 1.2 + 1e-9,
                        "every exit is on the ledge's 3-wide front edge: " + p.label());
            }
            assertEquals(all.size(), labels.size(), tier + ": every pilot is a different one");
            assertEquals(DropPilot.EXITS.length * DropPilot.TIMINGS.length * 3, jumps,
                    tier + ": half the rest jump off");
            assertEquals(DropPilot.SLOPPY_EXITS.length * DropPilot.TIMINGS.length, sloppy,
                    tier + ": 3 sloppy runs at each timing");
            assertTrue(all.get(all.size() - 1).label().contains((tier.aimError() > 0 ? "+" : "")
                    + tier.aimError() + " degrees"), tier + ": the sloppy runs hold the tier's aim error: "
                    + all.get(all.size() - 1).label());
        }
    }

    @Test
    void theExitsCoverTheFrontEdgeEveryPointThreeBlocks() {
        double[] exits = DropPilot.EXITS.clone();
        Arrays.sort(exits);
        assertEquals(-1.2, exits[0], 1e-12, "from one end of the edge, the hitbox still on the ledge");
        assertEquals(1.2, exits[exits.length - 1], 1e-12, "to the other");
        for (int i = 1; i < exits.length; i++) {
            assertTrue(exits[i] - exits[i - 1] <= 0.3 + 1e-9, "no two neighbouring exits more than 0.3 apart: "
                    + exits[i - 1] + " and " + exits[i]);
        }
    }

    @Test
    void theTimingsCoverTheWholeWalkingStepNotOneMomentOfIt() {
        // where the back of the hitbox is, behind the front edge, as the walk-off tick begins
        DropperFixtures.Shaft sh = straightStack();
        DropCheck.View v = sh.view();
        List<Double> behind = new ArrayList<>();
        for (DropPilot.Variant p : DropPilot.variants(v.edgeX(), v.edgeZ(), v.fx(), v.fz(), v.ledgeTop(), targets(v),
                DropRules.Level.EASY)) {
            if (p.label().startsWith("walk off at 0.0,")) {
                behind.add(v.edgeZ() - (p.start().z() - DropSim.HALF_WIDTH));
            }
        }
        Collections.sort(behind);
        double step = DropSim.GROUND_WALK;
        double reach = step / (2 * DropPilot.TIMINGS.length);
        assertEquals(DropPilot.TIMINGS.length * 3, behind.size(), "each timing, at each delay");
        assertTrue(behind.get(0) > 0 && behind.get(0) <= reach + 1e-9,
                "the earliest leaves with its heels a hair behind the edge: " + behind.get(0));
        assertTrue(behind.get(behind.size() - 1) >= step - reach - 1e-9 && behind.get(behind.size() - 1) < step,
                "the latest nearly a whole step behind it: " + behind.get(behind.size() - 1));
        for (int i = 1; i < behind.size(); i++) {
            assertTrue(behind.get(i) - behind.get(i - 1) <= 2 * reach + 1e-9, "no gap in the step wider than "
                    + 2 * reach + ": " + behind.get(i - 1) + " to " + behind.get(i));
        }
        assertTrue(reach < 0.04, "so any moment of a real step is within " + reach + " blocks of a flown one");
    }

    @Test
    void aPilotLeavesTheLedgeOnItsFirstTickFromAnyMomentOfItsStep() {
        DropperFixtures.Shaft sh = straightStack();
        DropCheck.View v = sh.view();
        List<Double> timings = new ArrayList<>();
        for (double t : DropPilot.TIMINGS) {
            timings.add(t);
        }
        timings.addAll(List.of(DropPilot.WITNESS_TIMING, 0.001, 0.999));
        for (double t : timings) {
            DropSim.Body start = DropPilot.start(v.edgeX(), v.edgeZ(), v.fx(), v.fz(), -v.fz(), v.fx(), 0, t,
                    v.ledgeTop(), false);
            assertTrue(start.z() - DropSim.HALF_WIDTH < v.edgeZ(), "timing " + t + ": it starts with its heels still"
                    + " over the ledge");
            assertEquals(DropSim.WALK_OFF_LEDGE_TICKS, start.ground(), "timing " + t + ": on the ground, two ledge"
                    + " ticks to come");
            DropSim.Body after = start.tick(v.fx(), v.fz());
            assertTrue(after.z() - DropSim.HALF_WIDTH > v.edgeZ(), "timing " + t + ": one step of walking carries it"
                    + " past the front edge");
            assertEquals(start.y(), after.y(), 1e-12, "timing " + t + ": without dropping, the ledge held it up");
        }
        assertEquals(v.witnessStart(), DropPilot.start(v.edgeX(), v.edgeZ(), v.fx(), v.fz(), -v.fz(), v.fx(), 0,
                DropPilot.WITNESS_TIMING, v.ledgeTop(), false), "the witness walks off the middle exit mid-step");
    }

    // ---- through the blocks -------------------------------------------------------------------------

    @Test
    void aStraightStackOfOpeningsLetsEveryPilotThrough() {
        DropperFixtures.Shaft sh = straightStack();
        DropCheck.View v = sh.view();
        long[] work = {0};
        List<DropCheck.Miss> misses = DropCheck.pilots(sh.w, v, targets(v), false, work);
        assertTrue(misses.isEmpty(), "all " + pilots() + " reach the water through 7 x 7 openings: "
                + labels(misses));
        assertTrue(work[0] > pilots() * 30L, "and every tick they flew is counted work: " + work[0]);
        DropCheck.Flight f = DropCheck.witness(sh.w, v, DropProgram.parse("-*"), sh.tier.tube());
        assertTrue(f.result().splashed(), "walking off and letting go splashes with Easy's clearance: "
                + f.result().outcome());
        assertEquals(2, f.crossings().size(), "crossing both layers");
    }

    @Test
    void aWalkOffThroughRealBlocksDriftsAsFarAsVanillaSaysAndNoFurther() {
        DropperFixtures.Shaft sh = new DropperFixtures.Shaft();
        DropCheck.View v = sh.view();
        DropSim.Body start = v.witnessStart();
        DropRun.Result r = DropRun.fly(sh.w, start, DropProgram.parse("S2 -*").controller(), 0, DropSim.MAX_TICKS,
                Double.NaN, false);
        assertEquals(DropRun.Outcome.SPLASH, r.outcome(), "walking off the ledge and letting go at the edge splashes");
        double drift = r.body().z() - start.z();
        assertEquals(2 * DropSim.GROUND_WALK + 1.23, drift, 0.03, "two walking steps over the ledge ticks, then the"
                + " 0.118 the ground leaves it with, kept 0.91 a tick for the 30 ticks of the fall: " + drift);
        assertTrue(drift < 1.8, "not the 2.5 blocks a body leaving with air speed would drift: " + drift);
    }

    @Test
    void aWallToWallPlateStopsEveryPilot() {
        DropperFixtures.Shaft sh = straightStack().plate(ROW2, 99, 99, 99, 99);
        DropCheck.View v = sh.view();
        List<DropCheck.Miss> misses = DropCheck.pilots(sh.w, v, targets(v), false, null);
        assertEquals(pilots(), misses.size(), "nobody gets through a plate with no hole");
        for (DropCheck.Miss m : misses) {
            assertEquals(DropRun.Outcome.TOUCHED, m.result().outcome(), m.label() + " bonks on it");
            assertEquals(ROW2, m.result().hit()[1], m.label() + " on the second layer's row");
        }
        List<DropCheck.Miss> first = DropCheck.pilots(sh.w, v, targets(v), true, null);
        assertEquals(1, first.size(), "the planner's retry asks only for the first miss");
    }

    @Test
    void aPilotHoldsForwardThroughItsReactionDelayThenSteers() {
        DropPilot p = new DropPilot(0, 1, List.of(new DropPilot.Target(10, 5, Double.NaN)), 3, 0);
        DropSim.Body at = new DropSim.Body(5, 50, 5, 0, 0, 0);
        for (int t = 0; t <= 3; t++) {
            assertArrayEquals(new double[]{0, 1}, p.input(t, at), 1e-12,
                    "tick " + t + ": the walk-off, then 3 ticks late, still walking forward");
        }
        assertArrayEquals(new double[]{1, 0}, p.input(4, at), 1e-12, "tick 4: it turns toward the opening, east");
        DropPilot quick = new DropPilot(0, 1, List.of(new DropPilot.Target(10, 5, Double.NaN)), 0, 0);
        assertArrayEquals(new double[]{0, 1}, quick.input(0, at), 1e-12, "even with no delay tick 0 is the walk-off");
        assertArrayEquals(new double[]{1, 0}, quick.input(1, at), 1e-12, "and it steers from tick 1");
    }

    @Test
    void afterEachLayerAPilotKeepsItsOldKeyForItsDelayBeforeTurningToTheNext() {
        List<DropPilot.Target> two = List.of(new DropPilot.Target(10, 5, 40), new DropPilot.Target(0, 5, Double.NaN));
        DropPilot p = new DropPilot(0, 1, two, 2, 0);
        DropSim.Body high = new DropSim.Body(5, 45, 5, 0, 0, 0);
        DropSim.Body low = new DropSim.Body(5, 39, 5, 0, 0, 0);
        for (int t = 0; t < 10; t++) {
            p.input(t, high);
        }
        assertArrayEquals(new double[]{1, 0}, p.input(9, high), 1e-12, "above the layer it aims east, at its opening");
        assertArrayEquals(new double[]{1, 0}, p.input(10, low), 1e-12, "tick 10: through the layer, still east");
        assertArrayEquals(new double[]{1, 0}, p.input(11, low), 1e-12, "tick 11: 2 ticks late, still east");
        assertArrayEquals(new double[]{-1, 0}, p.input(12, low), 1e-12, "tick 12: now west, at the next target");
        DropPilot fresh = p.fresh();
        assertArrayEquals(new double[]{0, 1}, fresh.input(0, high), 1e-12, "a fresh copy starts over at the ledge");
    }

    @Test
    void aSloppyPilotHoldsEveryKeyAFewDegreesOff() {
        DropPilot p = new DropPilot(0, 1, List.of(new DropPilot.Target(10, 5, Double.NaN)), 0, 10);
        double[] u = p.input(1, new DropSim.Body(5, 50, 5, 0, 0, 0));
        assertEquals(10, Math.toDegrees(Math.atan2(u[1], u[0])), 1e-9, "aiming east, it holds a key 10 degrees off");
        assertEquals(1, Math.hypot(u[0], u[1]), 1e-12, "at full input");
    }

    @Test
    void aPilotLetsGoWhenItsDriftAlreadyEndsOverTheOpening() {
        DropPilot p = new DropPilot(0, 1, List.of(new DropPilot.Target(5 + 0.1 * DropPilot.COAST, 5, Double.NaN)),
                0, 0);
        DropSim.Body drifting = new DropSim.Body(5, 50, 5, 0.1, 0, 0);
        double[] u = p.input(1, drifting);
        assertArrayEquals(new double[]{0, 0}, u, 1e-12, "a drift that stops on the target needs no key");
    }

    @Test
    void aPilotSettlesOverItsTargetInsteadOfSwingingAcrossItEveryTick() {
        assertTrue(DropPilot.DEADBAND > DropSim.TOP_WALK / 2, "the band is wider than half of what one push moves"
                + " the drift's end (" + DropSim.TOP_WALK / 2 + "), so no push can jump clean across it");
        int swings = 0;
        int most = 0;
        for (int k = -300; k <= 300; k++) {
            for (double v0 : new double[]{-0.15, -0.05, 0, 0.05, 0.15}) {
                DropPilot p = new DropPilot(1, 0, List.of(new DropPilot.Target(20, 5, Double.NaN)), 0, 0);
                DropSim.Body b = new DropSim.Body(20 + k * 0.005, 150, 5, v0, 0, 0);
                // the pushes it needs: where its drift ends, over what one push moves that
                int needed = (int) Math.ceil(Math.abs(b.x() + b.vx() * DropPilot.COAST - 20) / DropSim.TOP_WALK);
                double[] last = {0, 0};
                int pushes = 0;
                for (int t = 1; t <= 60; t++) {
                    double[] u = p.input(t, b);
                    if (u[0] * last[0] + u[1] * last[1] < 0) {
                        swings++;
                    }
                    pushes += u[0] != 0 || u[1] != 0 ? 1 : 0;
                    last = u;
                    b = b.tick(u[0], u[1]);
                }
                most = Math.max(most, pushes - needed);
            }
        }
        assertEquals(0, swings, "a pilot never pushes one way and then straight back the other: with a band"
                + " narrower than half a push it would swing across its target every tick");
        assertTrue(most <= 2, "it pushes about as often as the distance needs, then settles (at most "
                + most + " pushes more, when vanilla's tiny-speed cut nudges where its drift ends)");
    }

    // ---- the swept fall -----------------------------------------------------------------------------

    @Test
    void theSweptBoxCatchesACornerClipThatBothEndsMiss() {
        DropWorld w = new DropWorld(Box.sized(0, 0, 0, 32, 64, 32));
        w.set(10, 50, 10, DropWorld.SOLID);
        DropSim.Body start = new DropSim.Body(9.6, 50, 10.5, 0.9 * DropRun.SUB_STEPS, 0, -0.9 * DropRun.SUB_STEPS);
        assertNull(w.overlap(9.3, 50, 10.2, 9.9, 51.8, 10.8, DropWorld.SOLID), "where it starts is clear of the block");
        assertNull(w.overlap(10.2, 50, 9.3, 10.8, 51.8, 9.9, DropWorld.SOLID), "where the sub-step ends is clear too");
        DropRun.Result r = DropRun.fly(w, start, null, 0, 1, Double.NaN, false);
        assertEquals(DropRun.Outcome.TOUCHED, r.outcome(), "but it passed across the corner in between");
        assertArrayEquals(new int[]{10, 50, 10}, r.hit(), "the block it clipped");
        assertEquals(1, r.subSteps(), "caught on the first sub-step, not after it had passed");
    }

    @Test
    void aOneBlockPlateCantBeTunnelledAtTheFastestFall() {
        DropperFixtures.Shaft sh = new DropperFixtures.Shaft().plate(ROW2, 99, 99, 99, 99);
        DropSim.Body fast = new DropSim.Body(7.5, ROW2 + 3, 7.5, 0, -2.2, 0);
        DropRun.Result r = DropRun.fly(sh.w, fast, null, 0, 5, Double.NaN, false);
        assertEquals(DropRun.Outcome.TOUCHED, r.outcome(), "2.2 blocks a tick in 4 sub-steps still meets the plate");
        assertEquals(ROW2, r.hit()[1], "the plate's own row");
    }

    @Test
    void theTubeIsExactAPointOneBreachTouchesAndPointOneSpareDoesnt() {
        for (DropRules.Level tier : DropRules.Level.values()) {
            double r = tier.tube();
            assertEquals(DropRun.Outcome.TOUCHED, pastABlock(r - 0.1, r).outcome(),
                    tier + ": a block " + (r - 0.1) + " from the body is inside its clearance r = " + r);
            assertEquals(DropRun.Outcome.SPLASH, pastABlock(r + 0.1, r).outcome(),
                    tier + ": a block " + (r + 0.1) + " away is outside it");
            assertEquals(DropRun.Outcome.SPLASH, pastABlock(r - 0.1, 0).outcome(),
                    tier + ": the plain body never touches it at all");
        }
    }

    /** A straight fall past a block at (11, 40, 10) with the body's side {@code gap} from its face. */
    private static DropRun.Result pastABlock(double gap, double inflate) {
        DropWorld w = new DropWorld(Box.sized(0, 0, 0, 32, 64, 32));
        w.set(11, 40, 10, DropWorld.SOLID);
        for (int x = 0; x < 32; x++) {
            for (int z = 0; z < 32; z++) {
                w.set(x, 20, z, DropWorld.WATER);
            }
        }
        DropSim.Body start = new DropSim.Body(11 - gap - DropSim.HALF_WIDTH, 50, 10.5, 0, 0, 0);
        return DropRun.fly(w, start, null, inflate, DropSim.MAX_TICKS, Double.NaN, false);
    }

    @Test
    void aFaceTouchingABlockIsNotOverlappingIt() {
        DropWorld w = new DropWorld(Box.sized(0, 0, 0, 16, 16, 16));
        w.set(5, 5, 5, DropWorld.SOLID);
        assertNull(w.overlap(4, 5, 5, 5, 6, 6, DropWorld.SOLID), "a box ending exactly at the block's face");
        assertNotNull(w.overlap(4, 5, 5, 5.001, 6, 6, DropWorld.SOLID), "a hair further is inside it");
        assertNull(w.overlap(5, 6, 5, 6, 8, 6, DropWorld.SOLID), "standing on its top face isn't inside it");
        assertEquals(DropWorld.AIR, w.get(-1, 5, 5), "outside the half is air");
    }

    @Test
    void aScrapeAlongTheGlassIsNotALandingButComingDownOnTheLedgeIs() {
        DropperFixtures.Shaft sh = new DropperFixtures.Shaft();
        // pressed against the west wall, walking into it, all the way down
        DropSim.Body hugging = new DropSim.Body(sh.s.x1() + DropSim.HALF_WIDTH + 0.01, 50, 8.5, -0.2, 0, 0);
        DropRun.Result r = DropRun.fly(sh.w, hugging, (t, b) -> new double[]{-1, 0}, 0, DropSim.MAX_TICKS,
                Double.NaN, false);
        assertEquals(DropRun.Outcome.SPLASH, r.outcome(), "the wall stops it sideways, never from falling");
        DropSim.Body over = new DropSim.Body(sh.ledgeX + 1.5, sh.s.ledgeTop() + 1, sh.s.z1() + 1.5, 0, 0, 0);
        DropRun.Result land = DropRun.fly(sh.w, over, null, 0, DropSim.MAX_TICKS, Double.NaN, false);
        assertEquals(DropRun.Outcome.LANDED, land.outcome(), "dropping onto the ledge is a landing");
        assertEquals(sh.s.ledgeTop(), land.body().y(), 1e-6, "on its top");
        assertNotNull(land.hit(), "on a block it names");
    }

    private static List<String> labels(List<DropCheck.Miss> misses) {
        List<String> out = new ArrayList<>();
        for (DropCheck.Miss m : misses) {
            out.add(m.label() + " " + m.result().outcome());
        }
        return out;
    }
}
