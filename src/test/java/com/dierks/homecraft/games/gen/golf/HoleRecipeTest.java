package com.dierks.homecraft.games.gen.golf;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.GenRandom;
import com.dierks.homecraft.games.gen.api.Palette;
import com.dierks.homecraft.games.golf.GolfCourse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golf v4's recipes and deal (GOLF-V4-SPEC §3.3, §3.6, §3.7), before any solving: every recipe in
 * every tier it serves (and on Tiny Golf's plots, the dry ones) draws, for 40 seeds, a hole that is
 * inside its plot, has the features it declares, passes Adventure Golf's per-hole rules (all but a
 * few draws), is drawn both ways round, and says its class; layups do their job; the length deal
 * gives each tier its pattern; and Tiny Golf never deals water, L or X.
 */
class HoleRecipeTest {

    private static final int SEEDS = 40;
    private static final int X0 = 9000;
    private static final int Z0 = 4100;
    private static final int T = 164;

    /** Every (recipe, tier, dry) a course can deal. */
    private static List<Object[]> dealt() {
        List<Object[]> out = new ArrayList<>();
        for (HoleRecipe h : HoleRecipe.values()) {
            for (char tier : "EMH".toCharArray()) {
                if (!h.fits(tier)) {
                    continue;
                }
                out.add(new Object[]{h, tier, false});
                if (h.cls.par <= 3 && !h.wet(tier)) {
                    out.add(new Object[]{h, tier, true});
                }
            }
        }
        return out;
    }

    @Test
    void everyRecipeDrawsASoundHoleOfItsClassAndFeatures() {
        Map<String, String> rates = new TreeMap<>();
        List<String> bad = dealt().parallelStream().flatMap(j -> {
            HoleRecipe h = (HoleRecipe) j[0];
            char tier = (Character) j[1];
            boolean dry = (Boolean) j[2];
            PlotGrid grid = dry ? PlotGrid.V3 : PlotGrid.V4;
            Box plot = new Box(X0, T - 4, Z0, X0 + grid.plotX() - 1, T + 11, Z0 + grid.plotZ() - 1);
            String name = h + " " + tier + (dry ? " (Tiny Golf)" : "");
            List<String> out = new ArrayList<>();
            int redraws = 0;
            int unsound = 0;
            Set<Boolean> mirrors = new HashSet<>();
            for (long seed = 0; seed < SEEDS; seed++) {
                HoleLayout l;
                try {
                    l = h.draw(new GenRandom(seed).fork("recipe"), tier, grid, X0, Z0, T, seed % 3 == 0, dry);
                } catch (Draft.Redraw e) {
                    redraws++;
                    continue;
                }
                String what = name + " seed " + seed + " (" + l.describe() + ")";
                mirrors.add(l.mirrored());
                if (!l.features().equals(h.features(tier, dry))) {
                    out.add(what + ": has " + l.features() + ", declares " + h.features(tier, dry));
                }
                if (!l.describe().startsWith(h.cls.letter() + " " + h.name())) {
                    out.add(what + ": says its class and recipe first");
                }
                for (HoleLayout.Placed p : l.blocks()) {
                    if (!plot.contains(p.x(), p.y(), p.z())) {
                        out.add(what + ": a block outside its plot at " + p);
                        break;
                    }
                    if (!Palette.allowed(p.blockData()) && !Palette.poolWater(p.blockData())) {
                        out.add(what + ": " + p.blockData() + " isn't allowed");
                        break;
                    }
                }
                for (HoleLayout.Placed p : l.scenery()) {
                    if (!plot.contains(p.x(), p.y(), p.z())) {
                        out.add(what + ": scenery outside its plot at " + p);
                        break;
                    }
                }
                PlanBlocks g = l.grid(GolfPlanner.plotBox(l));
                GolfCourse.Hole hole = l.hole(GolfCourse.MIN_PAR);
                List<String> hp = GolfValidatorV3.holeProblems(g, hole, 1);
                if (!hp.isEmpty()) {
                    unsound++;
                    if (unsound * 10 > SEEDS) {
                        out.add(what + ": " + hp);
                    }
                }
                if (dry && LaneMap.of(g, hole, 4).hazards() > 0) {
                    out.add(what + ": water in play on Tiny Golf");
                }
            }
            if (redraws * 4 > SEEDS) {
                out.add(name + ": " + redraws + " of " + SEEDS + " draws don't fit (a quarter at most)");
            }
            if (SEEDS - redraws > 4 && !mirrors.equals(Set.of(true, false))) {
                out.add(name + " isn't drawn both ways round");
            }
            synchronized (rates) {
                rates.put(name, redraws + " redrawn, " + unsound + " unsound");
            }
            return out.stream();
        }).toList();
        System.out.println("Golf v4 recipes over " + SEEDS + " seeds: " + rates);
        assertEquals(List.of(), bad, "every recipe draws sound holes of its class with the features it declares");
    }

    @Test
    void aLayupCatchesADriveAndLetsASwingThrough() throws Exception {
        int held = 0;
        int drawn = 0;
        for (HoleRecipe h : List.of(HoleRecipe.L_LAYUP, HoleRecipe.L_LAYUP_SAND, HoleRecipe.X_LAYUP)) {
            char tier = h.fits('M') ? 'M' : 'H';
            for (long seed = 0; seed < 20; seed++) {
                HoleLayout l;
                try {
                    l = h.draw(new GenRandom(seed).fork("layup"), tier, PlotGrid.V4, X0, Z0, T, false, false);
                } catch (Draft.Redraw e) {
                    continue;
                }
                drawn++;
                assertTrue(l.features().contains(Quota.Feature.LAYUP), h + " is a layup");
                GenCopy.TeeFeature want = h == HoleRecipe.L_LAYUP_SAND ? GenCopy.TeeFeature.LAYUP_SAND
                        : GenCopy.TeeFeature.LAYUP_WATER;
                assertEquals(want, l.teeFeature(), h + "'s tee sign says lay up short");
                PlanBlocks g = l.grid(GolfPlanner.plotBox(l));
                if (GolfPlannerV4.layupHolds(g, l.hole(4), Work.unlimited())) {
                    held++;
                }
            }
        }
        assertTrue(held * 10 >= drawn * 8, "a Drive off the tee ends in the hazard and a Swing short of it on most"
                + " layups as drawn: " + held + " of " + drawn);
    }

    @Test
    void aStraightOnHoleIsNoLayup() throws Exception {
        HoleLayout l = HoleRecipe.S_STRAIGHT.draw(new GenRandom(1), 'E', PlotGrid.V4, X0, Z0, T, false, false);
        assertFalse(GolfPlannerV4.layupHolds(l.grid(GolfPlanner.plotBox(l)), l.hole(2), Work.unlimited()),
                "a Drive straight up a 12-long straight isn't caught by any hazard");
    }

    @Test
    void theRecipeListsDealOnlyWhatATierAndClassAndATinyCourseMayHave() {
        for (char tier : "EMH".toCharArray()) {
            for (LengthClass c : LengthClass.values()) {
                for (HoleRecipe h : HoleRecipe.list(tier, c, true)) {
                    assertFalse(h.wet(tier), h + " on Tiny Golf: never water in play");
                    assertEquals(c, h.cls, h + " is its class's");
                }
            }
        }
        for (char tier : "EMH".toCharArray()) {
            assertFalse(HoleRecipe.list(tier, LengthClass.M, true).isEmpty(), "Tiny Golf has M recipes for " + tier);
        }
        assertFalse(HoleRecipe.list('E', LengthClass.S, true).isEmpty(), "and S for Easy");
        assertFalse(HoleRecipe.list('M', LengthClass.L, false).isEmpty(), "Medium has L");
        assertFalse(HoleRecipe.list('H', LengthClass.X, false).isEmpty(), "Hard has X");
        assertTrue(HoleRecipe.list('H', LengthClass.X, false).stream().allMatch(h -> h.cls == LengthClass.X),
                "and only X there");
        Set<HoleRecipe> layups = EnumSet.noneOf(HoleRecipe.class);
        for (HoleRecipe h : HoleRecipe.values()) {
            if (h.features('M', false).contains(Quota.Feature.LAYUP) || h.features('H', false).contains(Quota.Feature.LAYUP)) {
                layups.add(h);
            }
        }
        assertEquals(EnumSet.of(HoleRecipe.L_LAYUP, HoleRecipe.L_LAYUP_SAND, HoleRecipe.X_LAYUP), layups,
                "the layups: L's pond and sand, X's pond");
    }

    @Test
    void theLengthDealGivesEachTierItsPattern() {
        int twoX = 0;
        for (long seed = 0; seed < 300; seed++) {
            List<LengthClass> c = DealV4.classes(new GenRandom(seed), "EEEMMMMHH", false);
            assertEquals(List.of(LengthClass.S, LengthClass.S, LengthClass.M), sorted(c.subList(0, 3)), "Easy: S, S, M");
            assertEquals(List.of(LengthClass.M, LengthClass.M, LengthClass.L, LengthClass.L), sorted(c.subList(3, 7)),
                    "Medium: M, M, L, L");
            List<LengthClass> hard = sorted(c.subList(7, 9));
            assertTrue(hard.equals(List.of(LengthClass.L, LengthClass.X)) || hard.equals(List.of(LengthClass.X, LengthClass.X)),
                    "Hard: L and X, or X and X: " + hard);
            twoX += hard.get(0) == LengthClass.X ? 1 : 0;
            assertEquals(c, DealV4.classes(new GenRandom(seed), "EEEMMMMHH", false), "the same seed, the same deal");
            List<LengthClass> tiny = sorted(DealV4.classes(new GenRandom(seed), "EEE", true));
            assertTrue(tiny.equals(List.of(LengthClass.S, LengthClass.S, LengthClass.M))
                    || tiny.equals(List.of(LengthClass.S, LengthClass.M, LengthClass.M)), "Tiny Golf: S, S, M or S, M, M");
            assertTrue(DealV4.classes(new GenRandom(seed), "MHH", true).stream().allMatch(x -> x == LengthClass.M),
                    "Tiny Golf at Medium and Hard: M holes");
        }
        assertTrue(twoX > 60 && twoX < 140, "two X holes about one time in three: " + twoX + " of 300");
    }

    @Test
    void theDealsRecipesAreItsGroupsAndALaterAttemptTakesTheNext() {
        String mix = "EEEMMMMHH";
        DealV4.Deal d = DealV4.deal(new GenRandom(42), mix, false);
        for (int i = 0; i < mix.length(); i++) {
            HoleRecipe h = d.recipe(mix, i, false);
            assertEquals(d.classes().get(i), h.cls, "hole " + (i + 1) + " takes a recipe of its class");
            assertTrue(h.fits(mix.charAt(i)), "and of its tier");
            assertTrue(d.recipe(mix, i, true).cls == h.cls, "its later attempts too");
        }
        assertEquals(d.k(), DealV4.deal(new GenRandom(42), mix, false).k(), "the same seed deals the same");
        Map<Quota.Feature, Integer> t = DealV4.targets(mix, d.classes(), false);
        assertEquals(2, t.get(Quota.Feature.WATER), "Golf of the Week: water 2");
        assertEquals(1, t.get(Quota.Feature.LAYUP), "a layup");
        Map<Quota.Feature, Integer> tiny = DealV4.targets("EEE", List.of(LengthClass.S, LengthClass.S, LengthClass.M), true);
        assertEquals(0, tiny.get(Quota.Feature.WATER), "Tiny Golf: no water");
        assertEquals(1, tiny.get(Quota.Feature.SAND), "sand 1");
        assertEquals(1, tiny.get(Quota.Feature.TREES), "a tree hole or a pond to look at");
    }

    @Test
    void golfV4sTeeSignsSayTheirWordsAndReadBack() {
        assertEquals(List.of("HOLE 4", "Par 4", "Lay up short", "of the water"),
                GenCopy.golfTee(4, 4, GenCopy.TeeFeature.LAYUP_WATER), "a pond layup");
        assertEquals(List.of("HOLE 4", "Par 4", "Lay up short", "of the sand"),
                GenCopy.golfTee(4, 4, GenCopy.TeeFeature.LAYUP_SAND), "a sand layup");
        assertEquals(List.of("HOLE 2", "Par 3", "Dogleg", "left"), GenCopy.golfTee(2, 3, GenCopy.TeeFeature.DOGLEG_LEFT),
                "a dogleg says which way");
        assertEquals(List.of("HOLE 2", "Par 3", "Dogleg", "right"), GenCopy.golfTee(2, 3, GenCopy.TeeFeature.DOGLEG_RIGHT),
                "both ways");
        assertEquals(List.of("HOLE 9", "Par 5", "Three legs", "use every club"),
                GenCopy.golfTee(9, 5, GenCopy.TeeFeature.THREE_LEGS), "three legs");
        assertEquals(List.of("HOLE 7", "Par 4", "Fly the pond!", "Chip or more"),
                GenCopy.golfTee(7, 4, GenCopy.TeeFeature.CARRY), "the carry's words exist");
        for (GenCopy.TeeFeature f : GenCopy.TeeFeature.values()) {
            assertEquals(f, GenCopy.golfTeeFeature(GenCopy.golfTee(5, 4, f), 5, 4), f + " reads back off its sign");
            assertTrue(GenCopy.signProblems(GenCopy.golfTee(18, 6, f)).isEmpty(), f + " fits a sign");
        }
    }

    @Test
    void aFallbackThatDoesntFitItsPlotIsABug() {
        assertThrows(IllegalStateException.class, () -> HoleRecipe.fallback(LengthClass.X, new PlotGrid(20, 30, 2, 4, 3),
                X0, Z0, T), "an S-bend can't stand on a 20 x 30 plot");
    }

    private static List<LengthClass> sorted(List<LengthClass> c) {
        List<LengthClass> out = new ArrayList<>(c);
        out.sort(null);
        return out;
    }
}
