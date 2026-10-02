package com.dierks.homecraft.games.gen.golf;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenFailed;
import com.dierks.homecraft.games.gen.api.LegacyBoxes;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.api.V4Boxes;
import com.dierks.homecraft.games.golf.GolfCourse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The proven fallbacks (GOLF-V4-SPEC §3.6): SAFE_S (a straight of 10), SAFE_M (a straight of 20), SAFE_L
 * (a dogleg of 20 and 16) and SAFE_X (an S-bend of 18, 14 and 16), plain
 * and 5 wide, each measure their class's par and keep the kid within its bound in every plot of every
 * golf half — at Col G, at the legacy boxes (where a v4 plot fits) and far out — at a cost well under
 * what is kept back for them, and never with a μ under their par, so the course balance never lowers
 * one. A course is therefore never missing a hole.
 */
class GolfFallbackV4Test {

    private record Where(String what, Box half, PlotGrid grid, OrdinaryPar.Model model, int kidOver, int most,
                         List<LengthClass> classes) {
    }

    private static List<Where> halves() {
        List<Where> out = new ArrayList<>();
        List<LengthClass> all = List.of(LengthClass.values());
        List<LengthClass> small = List.of(LengthClass.S, LengthClass.M);
        for (char h : new char[]{'A', 'B'}) {
            out.add(new Where("Golf of the Week " + h, V4Boxes.half(Slots.DAILY_GOLF, h), PlotGrid.V4,
                    OrdinaryPar.Model.FIRST_TIMER, 2, 6, all));
            out.add(new Where("Classic Golf " + h, V4Boxes.half(Slots.CLASSIC_GOLF, h), PlotGrid.V4,
                    OrdinaryPar.Model.FIRST_TIMER, 2, 6, all));
            out.add(new Where("Tiny Golf " + h, V4Boxes.half(Slots.TINY_GOLF, h), PlotGrid.V3, OrdinaryPar.Model.CHILD,
                    1, 3, small));
            out.add(new Where("legacy Golf of the Week " + h, LegacyBoxes.half(Slots.DAILY_GOLF, h), PlotGrid.V4,
                    OrdinaryPar.Model.FIRST_TIMER, 2, 6, all));
            out.add(new Where("legacy Tiny Golf " + h, LegacyBoxes.half(Slots.TINY_GOLF, h), PlotGrid.V3,
                    OrdinaryPar.Model.CHILD, 1, 3, small));
        }
        for (int[] o : new int[][]{{0, 64, 0}, {-4096, 200, 8192}, {1_048_576, 160, 1_048_576}}) {
            out.add(new Where("a half at " + o[0] + "," + o[1] + "," + o[2], Box.sized(o[0], o[1], o[2], 128, 16, 224),
                    PlotGrid.V4, OrdinaryPar.Model.FIRST_TIMER, 2, 6, all));
        }
        return out;
    }

    @Test
    void everyFallbackMeasuresItsClassesParAndKidBoundInEveryPlotOfEveryHalf() {
        List<String> bad = halves().parallelStream().flatMap(w -> {
            List<String> problems = new ArrayList<>();
            int plots = 0;
            for (int i = 0; i < 9; i++) {
                if (!w.grid().fits(w.half(), i)) {
                    continue;
                }
                plots++;
                int[] p = w.grid().plot(w.half(), i);
                for (LengthClass c : w.classes()) {
                    String what = w.what() + " plot " + (i + 1) + " SAFE_" + c;
                    HoleLayout l = HoleRecipe.fallback(c, w.grid(), p[0], p[1], w.half().minY() + GolfPlanner.TURF_ABOVE_FLOOR);
                    Work work = new Work(GolfPlannerV4.FALLBACK_RESERVE, null);
                    try {
                        GolfPlannerV4.Solved s = GolfPlannerV4.solve(l, c, w.model(), w.kidOver(), w.most(),
                                w.model() == OrdinaryPar.Model.CHILD, GolfPlannerV4.ATTEMPTS, true, work);
                        if (s == null) {
                            problems.add(what + ": doesn't solve within " + GolfPlannerV4.FALLBACK_RESERVE + " putts");
                            continue;
                        }
                        int par = OrdinaryPar.par(s.mean(), w.most());
                        if (par != c.par) {
                            problems.add(String.format(Locale.ROOT, "%s: measures par %d (μ %.2f), not %d", what, par,
                                    s.mean(), c.par));
                        }
                        if (s.mean() < c.par) {
                            problems.add(String.format(Locale.ROOT, "%s: μ %.2f is under its par: the balance could"
                                    + " lower it", what, s.mean()));
                        }
                        if (work.used() * 10 > GolfPlannerV4.FALLBACK_RESERVE * 9) {
                            problems.add(what + " takes " + work.used() + " putts, too near the "
                                    + GolfPlannerV4.FALLBACK_RESERVE + " kept back");
                        }
                    } catch (GenFailed e) {
                        problems.add(what + ": " + e.getMessage());
                    }
                }
            }
            if (plots == 0) {
                problems.add(w.what() + " has no plot");
            }
            return problems.stream();
        }).toList();
        assertEquals(List.of(), bad, "every fallback is a sound hole of its class wherever it stands");
    }

    @Test
    void theFallbacksAreTheSpecsShapes() {
        Box half = V4Boxes.half(Slots.DAILY_GOLF, 'A');
        int t = half.minY() + GolfPlanner.TURF_ABOVE_FLOOR;
        assertTrue(HoleRecipe.fallback(LengthClass.S, PlotGrid.V4, 8768, 4096, t).describe().contains("straight 10"),
                "SAFE_S is a straight of 10");
        assertTrue(HoleRecipe.fallback(LengthClass.M, PlotGrid.V4, 8768, 4096, t).describe().contains("straight 20"),
                "SAFE_M a straight of 20");
        assertTrue(HoleRecipe.fallback(LengthClass.M, PlotGrid.V3, 8768, 4096, t).describe().contains("straight 20"),
                "on a Tiny Golf plot too");
        HoleLayout l = HoleRecipe.fallback(LengthClass.L, PlotGrid.V4, 8768, 4096, t);
        assertTrue(l.describe().contains("dogleg 20 up, 16 across") && l.features().contains(Quota.Feature.TWO_LEGS),
                "SAFE_L a dogleg of 20 and 16");
        HoleLayout x = HoleRecipe.fallback(LengthClass.X, PlotGrid.V4, 8768, 4096, t);
        assertTrue(x.describe().contains("S-bend 18 up, 14 across, 16 up") && x.features().contains(Quota.Feature.THREE_LEGS),
                "SAFE_X an S-bend of 18, 14 and 16");
        assertEquals(x.blocks(), HoleRecipe.fallback(LengthClass.X, PlotGrid.V4, 8768, 4096, t).blocks(),
                "nothing random: the same blocks every time");
        GolfCourse.Hole h = x.hole(5);
        assertEquals(5, h.par(), "a hole of any par");
    }
}
