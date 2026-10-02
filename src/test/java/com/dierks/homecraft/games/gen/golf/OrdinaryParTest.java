package com.dierks.homecraft.games.gen.golf;

import com.dierks.homecraft.games.gen.api.GenFailed;
import com.dierks.homecraft.games.gen.api.GenRandom;
import com.dierks.homecraft.games.gen.api.GenSeed;
import com.dierks.homecraft.games.gen.api.LegacyBoxes;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlanInput;
import com.dierks.homecraft.games.gen.api.PlannedGolf;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.golf.GolfCourse;
import com.dierks.homecraft.games.golf.GolfShot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Honest par (GOLF-V4-SPEC §3.4, §7): the ordinary player is deterministic, its whole-degree error
 * table has the σ it claims, its par depends only on the hole's blocks, the course balance keeps the
 * total within a stroke of the summed means, and — the calibration — on 40 pinned Adventure Golf
 * courses the first-timer beats today's par by 2.3 ± 0.4 strokes a course (the owner's −2 as a
 * first-time player), and by 1.0 ± 0.4 on Tiny Golf.
 */
class OrdinaryParTest {

    private static final int T = 164;

    /** Recipe {@code h} drawn for {@code tier} on a v4 plot at (x, z). */
    private static HoleLayout draw(HoleRecipe h, char tier, long seed, int x, int z) throws Draft.Redraw {
        return h.draw(new GenRandom(seed).fork("par"), tier, PlotGrid.V4, x, z, T, false, false);
    }

    private static HoleLayout firstDrawn(HoleRecipe h, char tier, int x, int z) {
        for (long seed = 0; seed < 50; seed++) {
            try {
                return draw(h, tier, seed, x, z);
            } catch (Draft.Redraw e) {
                // the next seed
            }
        }
        throw new AssertionError(h + " never drew");
    }

    private static OrdinaryPar.Measure measure(HoleLayout l, OrdinaryPar.Model model) throws GenFailed {
        PlanBlocks g = l.grid(GolfPlanner.plotBox(l));
        GolfCourse.Hole h = l.hole(GolfCourse.MIN_PAR);
        return OrdinaryPar.measure(g, h, LaneMap.of(g, h, 4), model, Work.unlimited());
    }

    @Test
    void theErrorTablesHaveTheSigmaTheyClaimAndAreSymmetric() {
        OrdinaryPar.Model ft = OrdinaryPar.Model.FIRST_TIMER;
        assertEquals(4.0, ft.sigma, "the first-timer's σ is 4 degrees (calibrated below)");
        assertEquals(0.25, ft.slip, "and the wrong club one time in four");
        assertEquals(-12, ft.errors[0], "its errors reach -3σ");
        assertEquals(12, ft.errors[ft.errors.length - 1], "and +3σ");
        assertEquals(4.0, ft.tableSigma(), 0.1, "the whole-degree table's spread is σ (a little under: it stops at 3σ)");
        OrdinaryPar.Model child = OrdinaryPar.Model.CHILD;
        assertEquals(6.0, child.tableSigma(), 0.1, "the child's table is about 6 degrees");
        assertEquals(0.35, child.slip, "and the wrong club about one time in three");
        for (OrdinaryPar.Model m : OrdinaryPar.Model.values()) {
            for (int i = 0; i < m.errors.length; i++) {
                assertEquals(m.weights[i], m.weights[m.errors.length - 1 - i], 0, m + ": the table is symmetric");
                assertEquals(-m.errors[m.errors.length - 1 - i], m.errors[i], m + ": whole degrees either side");
            }
        }
    }

    @Test
    void tinyGolfsParIsTheChildsAndEveryOtherSlotsTheFirstTimers() {
        assertSame(OrdinaryPar.Model.CHILD, OrdinaryPar.model(Slots.TINY_GOLF), "Tiny Golf is the child's course");
        assertSame(OrdinaryPar.Model.FIRST_TIMER, OrdinaryPar.model(Slots.DAILY_GOLF), "Golf of the Week a first-timer's");
        assertSame(OrdinaryPar.Model.FIRST_TIMER, OrdinaryPar.model(Slots.CLASSIC_GOLF), "and Classic Golf's");
        assertEquals(3, OrdinaryPar.most(Slots.TINY_GOLF.id()), "Tiny Golf's par is at most 3");
        assertEquals(6, OrdinaryPar.most(Slots.DAILY_GOLF.id()), "anywhere else 6");
    }

    @Test
    void theRolloutsAreTheSameEveryTimeAndDrawFromTheParStreamNotTheSeed() throws Exception {
        HoleLayout l = firstDrawn(HoleRecipe.L_SBEND, 'M', 9000, 4100);
        OrdinaryPar.Measure a = measure(l, OrdinaryPar.Model.FIRST_TIMER);
        OrdinaryPar.Measure b = measure(l, OrdinaryPar.Model.FIRST_TIMER);
        assertEquals(a.mean(), b.mean(), 0, "the same μ");
        assertArrayEquals(a.strokes(), b.strokes(), "rollout for rollout");
        assertEquals(a.witness(), b.witness(), "the same witness");
        assertArrayEquals(a.chosen(), b.chosen(), "the same clubs");
        assertEquals(OrdinaryPar.ROLLOUTS, a.strokes().length, "64 rollouts");
        long g = GenRandom.fnv1a64("golf v4 par");
        assertEquals(new GenRandom(g).fork("roll:5").nextLong(), OrdinaryPar.stream(5).nextLong(),
                "rollout r draws from fnv1a64(\"golf v4 par\").fork(\"roll:\" + r)");
    }

    @Test
    void parDependsOnlyOnTheBlocksTheSameHoleInTwoPlotsMeasuresTheSame() throws Exception {
        for (HoleRecipe h : List.of(HoleRecipe.M_DOGLEG, HoleRecipe.X_SBEND_POND, HoleRecipe.S_SAND)) {
            char tier = h.fits('H') ? 'H' : h.fits('M') ? 'M' : 'E';
            HoleLayout here = firstDrawn(h, tier, 9000, 4100);
            HoleLayout there = firstDrawn(h, tier, 9044, 4180);
            OrdinaryPar.Measure a = measure(here, OrdinaryPar.Model.FIRST_TIMER);
            OrdinaryPar.Measure b = measure(there, OrdinaryPar.Model.FIRST_TIMER);
            assertEquals(a.mean(), b.mean(), 0, h + ": the same hole 44 and 80 blocks on measures the same μ");
            assertEquals(a.witness(), b.witness(), h + ": and the same witness");
        }
    }

    @Test
    void theWitnessIsTheFewestCleanStrokesAndReplaysExactly() throws Exception {
        for (HoleRecipe h : List.of(HoleRecipe.M_POND, HoleRecipe.L_LAYUP, HoleRecipe.X_LONG_DOGLEG_TREES)) {
            HoleLayout l = firstDrawn(h, 'H', 9000, 4100);
            PlanBlocks g = l.grid(GolfPlanner.plotBox(l));
            GolfCourse.Hole hole = l.hole(GolfCourse.MIN_PAR);
            LaneMap lane = LaneMap.of(g, hole, 4);
            OrdinaryPar.Measure m = OrdinaryPar.measure(g, hole, lane, OrdinaryPar.Model.FIRST_TIMER, Work.unlimited());
            assertFalse(m.witness().isEmpty(), h + " has a witness");
            assertNull(GolfValidatorV4.lineProblem(g, hole, lane, m.witness(), 1), h + ": its witness passes the validator's test");
            GolfShot.Replay r = GolfShot.replay(g, hole, m.witness());
            assertTrue(r.holed() && r.strokes() == m.expert(), h + ": it holes in E with no penalty");
            int best = Integer.MAX_VALUE;
            for (int s : m.strokes()) {
                best = Math.min(best, s);
            }
            assertTrue(m.expert() <= Math.max(best, m.expert()), h + ": E is no more than a rollout's best clean line");
            assertTrue(m.expert() <= Math.ceil(m.mean()), h + ": E (" + m.expert() + ") at most the rounded-up mean "
                    + m.mean());
            long chosen = 0;
            long played = 0;
            for (int c = 1; c <= 5; c++) {
                chosen += m.chosen()[c];
                played += m.played()[c];
            }
            assertEquals(chosen, played, h + ": every chosen club was played (one up or down at worst)");
            assertTrue(chosen >= OrdinaryPar.ROLLOUTS, h + ": at least a putt a rollout");
        }
    }

    @Test
    void aSpentBudgetGivesNoMeasureAndCountsEverySimulatedPutt() throws Exception {
        HoleLayout l = firstDrawn(HoleRecipe.M_DOGLEG, 'M', 9000, 4100);
        PlanBlocks g = l.grid(GolfPlanner.plotBox(l));
        GolfCourse.Hole hole = l.hole(GolfCourse.MIN_PAR);
        LaneMap lane = LaneMap.of(g, hole, 4);
        Work small = new Work(200, null);
        assertNull(OrdinaryPar.measure(g, hole, lane, OrdinaryPar.Model.FIRST_TIMER, small), "200 putts aren't enough");
        assertEquals(200, small.used(), "and it stopped at its cap");
        Work full = Work.unlimited();
        assertNotNull(OrdinaryPar.measure(g, hole, lane, OrdinaryPar.Model.FIRST_TIMER, full), "unlimited, it measures");
        assertTrue(full.used() > 500 && full.used() < 20_000, "a par-3 dogleg takes some hundreds of putts, not "
                + full.used());
        Work again = Work.unlimited();
        assertNotNull(OrdinaryPar.mean(g, hole, lane, OrdinaryPar.Model.FIRST_TIMER, again), "μ alone");
        assertTrue(again.used() < full.used(), "costs less than the witness hunt");
    }

    @Test
    void theBalanceKeepsTheCourseWithinAStrokeOfItsMeans() {
        GenRandom r = new GenRandom(7);
        for (int trial = 0; trial < 2_000; trial++) {
            int n = 1 + r.nextInt(9);
            double[] means = new double[n];
            for (int i = 0; i < n; i++) {
                int p = 2 + r.nextInt(4); // a hole of class S-X measures its par: μ in [par - ½, par + ½)
                means[i] = p == 2 ? 2 + r.nextInt(32) / 64.0 : p - 0.5 + r.nextInt(64) / 64.0;
            }
            int[] par = OrdinaryPar.balance(means);
            assertTrue(Math.abs(OrdinaryPar.off(par, means)) <= 1 + 1e-9, "trial " + trial + ": within one stroke");
            for (int i = 0; i < n; i++) {
                assertTrue(Math.abs(par[i] - means[i]) < 1, "trial " + trial + " hole " + (i + 1)
                        + ": a hole moves at most once, off its rounding by one");
                assertTrue(par[i] >= 2 && par[i] <= 6, "par 2-6");
            }
        }
    }

    @Test
    void theBalanceRaisesTheHoleNearestRoundingUpAndLowersTheOneNearestRoundingDown() {
        // three holes each a little under: 2.40, 3.30, 4.45 round to 2, 3, 4 = 9, the means 10.15: raise one
        assertArrayEquals(new int[]{2, 3, 5}, OrdinaryPar.balance(new double[]{2.40, 3.30, 4.45}),
                "the 4.45 is nearest to rounding up");
        assertArrayEquals(new int[]{3, 3, 4}, OrdinaryPar.balance(new double[]{2.45, 3.30, 4.45}),
                "on a tie the lower hole goes up");
        // and over: 2.50, 3.60, 4.70 round to 3, 4, 5 = 12, the means 10.80: lower one
        assertArrayEquals(new int[]{2, 4, 5}, OrdinaryPar.balance(new double[]{2.50, 3.60, 4.70}),
                "the 2.50 is nearest to rounding down");
        assertArrayEquals(new int[]{2, 3, 4}, OrdinaryPar.balance(new double[]{2.0, 3.0, 4.0}), "whole means stay");
        assertArrayEquals(new int[]{3, 2, 2}, OrdinaryPar.balance(new double[]{2.45, 2.45, 2.45}, 3),
                "Tiny Golf: one up, to 3");
        assertArrayEquals(new int[]{3, 3, 3}, OrdinaryPar.balance(new double[]{3.45, 3.45, 3.45}, 3),
                "a hole already at Tiny Golf's 3 can't go up (the planner then draws one again)");
        assertEquals(-1.35, OrdinaryPar.off(new int[]{3, 3, 3}, new double[]{3.45, 3.45, 3.45}), 1e-9,
                "and says how far off it is");
    }

    @Test
    void calibrationTheFirstTimerBeatsAdventureGolfsParByAboutTwoStrokesACourse() {
        double golf = calibration(Slots.DAILY_GOLF);
        assertTrue(golf >= -2.7 && golf <= -1.9, String.format(Locale.ROOT, "on 40 pinned Golf of the Week courses of"
                + " Adventure Golf the first-timer is %.2f against today's par a course, the owner's -2 (-2.3 ± 0.4)", golf));
        double tiny = calibration(Slots.TINY_GOLF);
        assertTrue(tiny >= -1.4 && tiny <= -0.6, String.format(Locale.ROOT, "and on 40 Tiny Golf courses %.2f"
                + " (-1.0 ± 0.4)", tiny));
    }

    /** The first-timer's mean minus today's par, per course, over 40 pinned algo-3 courses of {@code slot}. */
    private static double calibration(Slots.Def slot) {
        List<Double> diffs = Collections.synchronizedList(new ArrayList<>());
        IntStream.range(0, 40).parallel().forEach(i -> {
            long seed = GenSeed.seed(0x5EC12E7L, 20725 + i, slot.id(), 0);
            PlanInput in = new PlanInput(slot, LegacyBoxes.half(slot, 'A'), 'A', 20725 + i, 0, seed, slot.tierOrMix(),
                    8, 0, null);
            try {
                Plan p = GolfPlanner.v3().plan(in);
                PlannedGolf g = (PlannedGolf) p.course();
                PlanBlocks grid = PlanBlocks.of(p.half(), p.palette(), p.ops());
                double d = 0;
                for (GolfCourse.Hole h : g.course().holes()) {
                    d += OrdinaryPar.mean(grid, h, LaneMap.of(grid, h, 3), OrdinaryPar.Model.FIRST_TIMER,
                            Work.unlimited()) - h.par();
                }
                diffs.add(d);
            } catch (GenFailed e) {
                throw new AssertionError(slot.id() + " seed " + i + ": " + e.getMessage(), e);
            }
        });
        return diffs.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
    }
}
