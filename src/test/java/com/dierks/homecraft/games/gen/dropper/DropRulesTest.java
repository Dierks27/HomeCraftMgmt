package com.dierks.homecraft.games.gen.dropper;

import com.dierks.homecraft.games.gen.DailySettings;
import com.dierks.homecraft.games.gen.api.Stars;
import com.dierks.homecraft.games.trial.Tier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tier table (EVENTS-DROPPER-SPEC §B.1.3) and the arithmetic built on it: the relations the
 * proof leans on (an opening always fits the body and its clearance; the clearance is more than a
 * tick of drift; Easy asks for one early choice), the mix a slot is set to, and the pure parts of
 * §B.1.8: the reference time, the star times and the shortest honest time.
 */
class DropRulesTest {

    private static final DropRules.Level E = DropRules.Level.EASY;
    private static final DropRules.Level M = DropRules.Level.MEDIUM;
    private static final DropRules.Level H = DropRules.Level.HARD;

    // ---- the table, row by row --------------------------------------------------------------------

    @Test
    void theDropsLayersAndPoolsAreTheSpecsTable() {
        assertEquals(List.of(32, 40, 48), List.of(E.drop(), M.drop(), H.drop()), "ledge top to water: 32, 40, 48");
        assertEquals(List.of(2, 3, 4), List.of(E.layers(), M.layers(), H.layers()), "layers: 2, 3, 4");
        assertEquals(List.of(11, 7, 5), List.of(E.pool(), M.pool(), H.pool()), "pools: the whole floor, 7, 5");
        assertTrue(E.wholeFloor() && !M.wholeFloor() && !H.wholeFloor(), "only Easy's pool is the whole floor");
        assertEquals(List.of(32, 37, 40), List.of(E.fallTicks(), M.fallTicks(), H.fallTicks()),
                "the clean walk-off falls: 32, 37, 40 ticks");
    }

    @Test
    void theLayerDepthsAndSpacingsAreTheSpecsTable() {
        assertEquals(List.of(12, 14), List.of(E.firstMin(), E.firstMax()), "Easy's first layer 12-14 down");
        assertEquals(List.of(11, 13), List.of(M.firstMin(), M.firstMax()), "Medium's 11-13");
        assertEquals(List.of(10, 12), List.of(H.firstMin(), H.firstMax()), "Hard's 10-12");
        assertEquals(List.of(9, 11), List.of(E.gapMin(), E.gapMax()), "Easy's spacing 9-11");
        assertEquals(List.of(8, 10), List.of(M.gapMin(), M.gapMax()), "Medium's 8-10");
        assertEquals(List.of(7, 9), List.of(H.gapMin(), H.gapMax()), "Hard's 7-9");
    }

    @Test
    void evenTheDeepestDrawOfLayersLeavesSixBlocksOfClearAirOverTheWater() {
        for (DropRules.Level l : DropRules.Level.values()) {
            int deepest = l.firstMax() + (l.layers() - 1) * l.gapMax();
            assertTrue(deepest <= l.deepestLayer(), l + ": the deepest layer drawn (" + deepest
                    + ") is no deeper than the water less 6 clear rows less the plate (" + l.deepestLayer() + ")");
            assertTrue(l.drop() - deepest - 1 >= DropperGeometry.CLEAR_AIR,
                    l + ": at least " + DropperGeometry.CLEAR_AIR
                            + " rows of air between the last plate and the water");
        }
    }

    @Test
    void theOpeningsClearancesAndInputLimitsAreTheSpecsTable() {
        assertEquals(List.of(5, 3, 2), List.of(E.minOpening(), M.minOpening(), H.minOpening()),
                "the smallest path openings: 5, 3, 2");
        assertEquals(0.6, E.tube(), 0, "Easy's clearance r");
        assertEquals(0.4, M.tube(), 0, "Medium's");
        assertEquals(0.25, H.tube(), 0, "Hard's");
        assertEquals(List.of(1, 2, 4), List.of(E.maxChanges(), M.maxChanges(), H.maxChanges()),
                "the witness's input changes: at most 1, 2, 4");
        assertEquals(List.of(1, 2, 0), List.of(E.changesBefore(), M.changesBefore(), H.changesBefore()),
                "all before layer 1 on Easy, before layer 2 on Medium, anywhere on Hard");
    }

    @Test
    void thePilotsDelaysAndAimErrorsAreTheSpecsTable() {
        assertArrayEquals(new int[]{0, 3, 5}, E.delays(), "Easy's reaction delays");
        assertArrayEquals(new int[]{0, 2, 4}, M.delays(), "Medium's");
        assertArrayEquals(new int[]{0, 1, 2}, H.delays(), "Hard's");
        assertEquals(List.of(10.0, 10.0, 5.0), List.of(E.aimError(), M.aimError(), H.aimError()),
                "the sloppy pilots' aim error: 10, 10 and 5 degrees");
        int[] copy = E.delays();
        copy[0] = 99;
        assertEquals(0, E.delays()[0], "the table hands out copies: a caller can't change it");
    }

    @Test
    void theTemplatesAndLightsGrowWithTheTier() {
        assertEquals(List.of(LayerKit.Template.PLATE, LayerKit.Template.RING), E.templates(),
                "Easy: a plate with one hole, or a ring");
        assertEquals(List.of(LayerKit.Template.PLATE, LayerKit.Template.RING, LayerKit.Template.BARS,
                LayerKit.Template.CROSS, LayerKit.Template.TWIN), M.templates(), "Medium adds bars, cross and twin");
        assertTrue(H.templates().containsAll(M.templates()) && H.templates().contains(LayerKit.Template.CHECKER)
                && H.templates().contains(LayerKit.Template.DECOY), "Hard adds checker and decoy");
        assertFalse(E.templates().contains(LayerKit.Template.DECOY) || M.templates().contains(LayerKit.Template.DECOY),
                "a decoy (a hole that leads nowhere) is Hard's only");
        assertEquals(E.layers(), E.litLayers(), "Easy lights every layer: follow the light");
        assertEquals(1, M.litLayers(), "Medium lights layer 1");
        assertEquals(0, H.litLayers(), "Hard lights none");
    }

    // ---- the relations the proof leans on ------------------------------------------------------------

    @Test
    void everyTiersOpeningIsAtLeastTheBodyPlusTwiceItsClearance() {
        for (DropRules.Level l : DropRules.Level.values()) {
            assertTrue(l.opening() >= DropSim.WIDTH + 2 * l.tube(),
                    l + ": an opening of " + l.opening() + " fits the 0.6 body with r = " + l.tube() + " each side");
            assertTrue(l.opening() >= l.minOpening(), l + ": and is never under the table's minimum");
        }
    }

    @Test
    void theClearanceIsMoreThanOneTickOfSidewaysTravelAtTopAirSpeed() {
        for (DropRules.Level l : DropRules.Level.values()) {
            assertTrue(l.tube() > DropSim.topSpeed(false), l + ": r = " + l.tube()
                    + " is more than a tick's drift at top walking air speed (" + DropSim.topSpeed(false)
                    + "), so a one-tick-late reaction never costs the whole margin");
        }
    }

    @Test
    void easyIsWalkOnlyOneEarlyChoiceAndASafeSplash() {
        assertEquals(DropSim.INPUT * DropSim.AIR_SPEED, DropSim.WALK_ACCEL, 1e-12,
                "the witness and the pilots steer with walking acceleration: nobody needs to sprint");
        assertEquals(1, E.maxChanges(), "Easy's proof is one change of walking keys at most");
        assertEquals(1, E.changesBefore(), "made before the first layer: after that you just fall");
        assertTrue(E.wholeFloor(), "and the whole floor is water, so no landing can miss it");
        DropProgram letGo = DropProgram.parse("S6 -*");
        assertNotNull(letGo, "a walk-off then letting go is a program");
        assertEquals(1, letGo.changes(DropProgram.Dir.S), "walking off south then letting go is one change");
    }

    @Test
    void everyLevelDropsAtLeastTheLayoutsMinimum() {
        for (DropRules.Level l : DropRules.Level.values()) {
            assertTrue(l.drop() >= DropRules.MIN_DROP, l + " drops " + l.drop() + ", at least the layout's "
                    + DropRules.MIN_DROP);
        }
    }

    // ---- the mix ------------------------------------------------------------------------------------

    @Test
    void aMixIsOneToFiveOfEMAndH() {
        for (String ok : List.of("E", "EEE", "EEMMH", "hhhhh", " emh ")) {
            assertNull(DropRules.mixProblem(ok), ok + " is a mix (any case, trimmed)");
        }
        for (String bad : List.of("", "EEEEEE", "EXE", "easy", "E E")) {
            assertNotNull(DropRules.mixProblem(bad), "'" + bad + "' is not a mix");
        }
        assertNotNull(DropRules.mixProblem(null), "nor is nothing");
        assertEquals(List.of(E, E, M, M, H), DropRules.levels("eemmh"), "the levels in order");
        assertTrue(DropRules.levels("EXE").isEmpty(), "a bad mix has no levels");
    }

    @Test
    void theTierOfAMixIsTheRoundedMeanOfItsLetters() {
        assertEquals(Tier.EASY, DropRules.tier("EEE"), "EEE: mean 1");
        assertEquals(Tier.MEDIUM, DropRules.tier("EEMMH"), "EEMMH: mean 1.8 rounds to 2");
        assertEquals(Tier.HARD, DropRules.tier("HHHHH"), "HHHHH: mean 3");
        assertEquals(Tier.MEDIUM, DropRules.tier("EM"), "EM: mean 1.5, a half rounds up");
        assertEquals(Tier.EASY, DropRules.tier("EEEEM"), "EEEEM: mean 1.2 rounds down");
        assertEquals(Tier.HARD, DropRules.tier("MH"), "MH: mean 2.5 rounds up");
        assertEquals(Tier.EASY, DropRules.tier("nonsense"), "a bad mix counts as easy, never throws");
    }

    // ---- times (§B.1.7, §B.1.8) ---------------------------------------------------------------------

    @Test
    void theReferenceTimeIsEachFallPlusASecondAndAHalfPlusAQuarterSecondAHop() {
        assertEquals(9_800, DropRules.refMs("EEE"), "EEE: 3 x (32 x 50 + 1500) + 2 x 250 = 9.8 s");
        assertEquals(17_400, DropRules.refMs("EEMMH"), "EEMMH: 17.4 s");
        assertEquals(3_100, DropRules.refMs("E"), "one level has no hop");
        assertEquals(0, DropRules.refMs("bad"), "a bad mix has no reference time");
    }

    @Test
    void theStarTimesComeFromTheReferenceTimeAndTheMixsTier() {
        DailySettings.Stars stars = DailySettings.defaults().stars();
        double easyGold = stars.gold(DropRules.tier("EEE").id());
        double mediumGold = stars.gold(DropRules.tier("EEMMH").id());
        assertEquals(19_600, DropRules.refMs("EEE") * easyGold, 1e-6, "EEE's gold is 19.6 s (about 4 bonks of slack)");
        assertEquals(26_100, DropRules.refMs("EEMMH") * mediumGold, 1e-6, "EEMMH's gold is 26.1 s");
        assertEquals(20_000, Stars.threshold(DropRules.refMs("EEE"), easyGold),
                "shown rounded up to a whole second a player can make");
        assertEquals(27_000, Stars.threshold(DropRules.refMs("EEMMH"), mediumGold), "26.1 s shows as 27 s");
    }

    @Test
    void theShortestHonestTimeIsNinetyPercentOfTheFallsRoundedDown() {
        assertEquals(4, DropRules.minSeconds("EEE"), "EEE: 0.9 x 96 ticks = 4.32 s, so 4");
        assertEquals(8, DropRules.minSeconds("EEMMH"), "EEMMH: 0.9 x 178 ticks = 8.01 s, so 8");
        assertTrue(DropRules.minSeconds("HHHHH") * 1000L < DropRules.refMs("HHHHH"),
                "far under the reference time: it only catches a run faster than gravity");
    }
}
