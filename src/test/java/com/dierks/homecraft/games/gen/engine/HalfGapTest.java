package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.DailySettings.SlotConfig;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.LegacyBoxes;
import com.dierks.homecraft.games.gen.api.Sight;
import com.dierks.homecraft.games.gen.api.Slots;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Each slot's own gap between its halves (LAYOUT-SPEC §1.5, §3.1-§3.2), as pure geometry: half B
 * stands {@code sizeX + gap} from the origin; the build rule {@link Regions#APART} is its own 32,
 * whatever the gap; every rule is asked of each half, never of the air between them; a claim names
 * the gap whenever it isn't 0.35's, so a changed gap reads as a moved region; and the kept plots are
 * checked against the world (its border, height, spawn and safe spot) plot by plot.
 */
class HalfGapTest {

    private static final Slots.Def EASY = Slots.DAILY_PARKOUR_EASY; // 64 x 48 x 64
    private static final int[] FAR = {16_384, 160, 16_384};

    private static SlotConfig at(Slots.Def def, int[] origin, int gap) {
        return SlotConfig.shipped(def).withOrigin(origin).withHalfGap(gap).withEnabled(true);
    }

    private static Regions.WorldFacts world(Box border, int[] spawn, double[] safe) {
        return new Regions.WorldFacts("games", true, -64, 320, border, spawn, safe);
    }

    @Test
    void theGapConstantsAreTheSpecsAndTheBuildRuleStandsAlone() {
        assertEquals(32, Slots.LEGACY_HALF_GAP, "0.35's gap between a slot's halves");
        assertEquals(576, Slots.SIGHT_HALF_GAP, "the gap that keeps the spare half out of sight");
        assertEquals(Sight.GAP, Slots.SIGHT_HALF_GAP, "one number for both");
        assertTrue(Slots.HALF_GAP == Slots.LEGACY_HALF_GAP || Slots.HALF_GAP == Slots.SIGHT_HALF_GAP,
                "the default gap is one of the two");
        assertEquals(32, Regions.APART, "the build rule is its own 32, split from the gap");
        assertEquals(0, KeepArea.LEGACY_GAP, "0.35's plots touch");
        assertEquals(576, KeepArea.SIGHT_GAP, "the gap that keeps kept courses out of each other's sight");
    }

    @Test
    void halfBStandsSizeXPlusTheGapFromTheOrigin() {
        for (int gap : new int[]{0, 32, 576, 4096}) {
            Box a = EASY.half(100, 160, 200, 'A', gap);
            Box b = EASY.half(100, 160, 200, 'B', gap);
            assertEquals(Box.sized(100, 160, 200, 64, 48, 64), a, "gap " + gap + ": A is at the origin");
            assertEquals(Box.sized(100 + 64 + gap, 160, 200, 64, 48, 64), b, "gap " + gap + ": B is sizeX + gap on");
            assertEquals(gap, a.gap(b), "gap " + gap + ": exactly that many blocks between them");
            assertEquals(Box.sized(100, 160, 200, 128 + gap, 48, 64), EASY.region(100, 160, 200, gap),
                    "gap " + gap + ": the region is both halves and the gap");
        }
        assertEquals(EASY.half(1, 2, 3, 'B', Slots.HALF_GAP), EASY.half(1, 2, 3, 'B'), "the old form is the default gap");
        assertEquals(EASY.region(1, 2, 3, Slots.HALF_GAP), EASY.region(1, 2, 3), "for the region too");
        assertEquals(EASY.half('b'), EASY.half(EASY.originX(), EASY.originY(), EASY.originZ(), 'B'),
                "the shipped origin, any case");
        assertThrows(IllegalArgumentException.class, () -> EASY.half(0, 0, 0, 'A', -1), "a negative gap is refused");
        assertThrows(IllegalArgumentException.class, () -> EASY.region(0, 0, 0, -16), "for a region too");
        assertThrows(IllegalArgumentException.class, () -> EASY.half(0, 0, 0, 'C', 32), "a half is A or B");
    }

    @Test
    void aSlotConfigCarriesItsGapThroughEveryWith() {
        SlotConfig c = at(EASY, FAR, 576);
        assertEquals(576, c.halfGap(), "the gap given");
        assertEquals(576, c.withEnabled(false).withOrigin(new int[]{0, 160, 0}).withTierOrMix("hard")
                .withDailyClear(9).halfGap(), "kept by every with");
        assertEquals(Slots.HALF_GAP, new SlotConfig("fresh_parkour_easy", true, "easy", FAR, 1).halfGap(),
                "the five-part form means the default gap");
        assertEquals(Slots.HALF_GAP, SlotConfig.shipped(EASY).halfGap(), "and so does the shipped slot");
        assertFalse(c.equals(c.withHalfGap(32)), "two gaps are two different places");
        assertNotNull(c.toString(), "it prints");
        assertTrue(c.toString().contains("gap 576"), "with its gap: " + c);
        assertThrows(IllegalArgumentException.class, () -> c.withHalfGap(-1), "never negative");
        assertEquals(List.of(EASY.half(FAR[0], FAR[1], FAR[2], 'A', 576), EASY.half(FAR[0], FAR[1], FAR[2], 'B', 576)),
                Regions.halves(c), "its halves, at its own gap");
    }

    @Test
    void theAirBetweenASlotsHalvesIsNobodysAndEachHalfKeepsThirtyTwoApart() {
        SlotConfig wide = at(EASY, FAR, 576);
        int[] between = {FAR[0] + 64 + 32, 160, FAR[2]}; // 32 past half A; its own half B 32 further on
        SlotConfig inTheGap = at(Slots.DAILY_PARKOUR_MEDIUM, between, 32);
        List<String> warns = new ArrayList<>();
        List<SlotConfig> out = Regions.validate(List.of(wide, inTheGap), warns::add, "games.fresh.slots");
        assertEquals(List.of(), warns, "a course standing in the gap, 32 from each of the wide slot's halves, is fine");
        assertTrue(out.get(1).enabled(), "and stays on");

        int[] onB = {FAR[0] + 64 + 576, 160, FAR[2]};
        warns.clear();
        out = Regions.validate(List.of(wide, at(Slots.DAILY_PARKOUR_MEDIUM, onB, 32)), warns::add, "games.fresh.slots");
        assertEquals(1, warns.size(), "one WARN: " + warns);
        assertTrue(warns.get(0).contains("on top of fresh_parkour_easy"), "on the wide slot's half B: " + warns);
        assertFalse(out.get(1).enabled(), "the later one is off");
        assertTrue(out.get(0).enabled(), "the first keeps its place");

        int[] tooClose = {FAR[0] + 64 + 576 + 64 + 16, 160, FAR[2]};
        String p = Regions.apartProblem(at(Slots.DAILY_PARKOUR_MEDIUM, tooClose, 32), List.of(wide));
        assertNotNull(p, "16 past the wide slot's half B is too close");
        assertTrue(p.contains("only 16 blocks") && p.contains("they must be " + Regions.APART + " apart"),
                "said with the build rule's 32: " + p);
    }

    @Test
    void theRangeHeightBorderSpawnAndSafeSpotAreAskedOfEachHalf() {
        SlotConfig nearTheEdge = at(EASY, new int[]{Regions.MAX_XZ - 64 - 576 - 30, 160, 0}, 576);
        assertTrue(Regions.problem(EASY, nearTheEdge).contains("half B"), "half B past +-29,000,000 is named: "
                + Regions.problem(EASY, nearTheEdge));
        assertNull(Regions.problem(EASY, nearTheEdge.withHalfGap(32)), "at 32 both halves fit");

        Box border = new Box(-20_000, -64, -20_000, FAR[0] + 64 + 100, 320, 20_000);
        List<String> w = Regions.worldProblems(EASY, FAR, 576, world(border, null, null));
        assertEquals(1, w.size(), "one problem: " + w);
        assertTrue(w.get(0).contains("world border") && w.get(0).contains("half B"), "half B is past it: " + w);
        assertEquals(List.of(), Regions.worldProblems(EASY, FAR, 32, world(border, null, null)),
                "at 32 both halves are inside");

        int[] spawnInTheGap = {FAR[0] + 64 + 288, 170, FAR[2] + 20};
        assertEquals(List.of(), Regions.worldProblems(EASY, FAR, 576, world(null, spawnInTheGap, null)),
                "a spawn in the gap, 288 from each half, is nobody's business");
        double[] safeOnB = {FAR[0] + 64 + 576 + 5.5, 170, FAR[2] + 5.5};
        List<String> safe = Regions.worldProblems(EASY, FAR, 576, world(null, null, safeOnB));
        assertEquals(1, safe.size(), "the safe spot inside half B: " + safe);
        assertTrue(safe.get(0).contains("safe_spot is inside half B"), "said so: " + safe);
        List<String> low = Regions.worldProblems(EASY, FAR, 576, new Regions.WorldFacts("games", true, 0, 200,
                null, null, null));
        assertTrue(low.get(0).contains("160..207"), "the height is still checked: " + low);
    }

    @Test
    void anExtraBoxIsApartFromEachHalfAtItsOwnGapAndMayStandBetweenThem() {
        SlotConfig wide = at(EASY, FAR, 576);
        Box between = Box.sized(FAR[0] + 64 + 32, 160, FAR[2], 48, 40, 48);
        assertNull(Regions.extraProblem(new Regions.Extra("arena", between), List.of(wide), null),
                "an arena in the gap, 32 from half A and far from half B, is fine");
        Box onB = Box.sized(FAR[0] + 64 + 576, 160, FAR[2], 48, 40, 48);
        String p = Regions.extraProblem(new Regions.Extra("arena", onB), List.of(wide), null);
        assertNotNull(p, "one on half B is refused");
        assertTrue(p.contains("half B"), "naming half B: " + p);
        assertNotNull(Regions.extrasProblem(EASY, FAR, 576, List.of(new Regions.Extra("arena", onB))),
                "the slot's side of the same rule, at its gap");
        assertNull(Regions.extrasProblem(EASY, FAR, 32, List.of(new Regions.Extra("arena", onB))),
                "at 32 half B stands elsewhere");
    }

    @Test
    void aHandBuiltCourseIsMeasuredFromEachHalfAtTheSlotsGap() {
        Box nextToB = Box.sized(FAR[0] + 64 + 576 + 64 + 3, 160, FAR[2], 4, 4, 4);
        List<Regions.Area> areas = List.of(new Regions.Area("games", nextToB, "my_course"));
        String p = Regions.handBuiltProblem(EASY, FAR, 576, "games", areas);
        assertNotNull(p, "3 blocks from the wide slot's half B is too close");
        assertTrue(p.contains("half B"), "said so: " + p);
        assertNull(Regions.handBuiltProblem(EASY, FAR, 32, "games", areas), "at 32 it is far from both halves");
    }

    @Test
    void aClaimNamesItsGapUnlessItIs035sSoAnOldClaimStillMatches() {
        String old = Regions.claim(EASY, "Games", new int[]{4096, 160, 4096}, 32);
        assertEquals("games,4096,160,4096,64,48,64", old, "at 32: the 7 fields every 0.35 claim has");
        String wide = Regions.claim(EASY, "games", FAR, 576);
        assertEquals("games,16384,160,16384,64,48,64,576", wide, "any other gap: an 8th field");
        assertFalse(wide.equals(Regions.claim(EASY, "games", FAR, 32)), "so a changed gap is a changed claim,"
                + " exactly like a changed origin");
        assertEquals(Slots.LEGACY_HALF_GAP, Regions.claimGap(old), "7 fields read as 0.35's gap, whatever the default");
        assertEquals(576, Regions.claimGap(wide), "8 fields read as the gap they name");
        assertArrayEquals(FAR, Regions.claimOrigin(wide), "its origin");
        assertEquals("games", Regions.claimWorld(wide), "its world");
        for (String junk : new String[]{null, "", "games,1,2,3,4,5", "games,1,2,3,4,5,6,wide", "games,1,2,3,4,5,6,-32",
                "games,1,2,3,4,5,6,32,9", "games,x,2,3,4,5,6"}) {
            assertNull(Regions.claimOrigin(junk), "unreadable: " + junk);
            assertNull(Regions.claimGap(junk), "no gap either: " + junk);
        }
        assertEquals(List.of(old, wide), Regions.wetClaims(old + ";" + wide + ";junk"),
                "an old region list keeps both kinds of claim");
    }

    @Test
    void a035ClaimParsesBackTo035sBoxes() {
        for (Slots.Def d : Slots.CLASSICS) {
            int[] o = LegacyBoxes.origin(d);
            String claim = "games," + o[0] + "," + o[1] + "," + o[2] + "," + d.sizeX() + "," + d.sizeY() + ","
                    + d.sizeZ();
            List<Box> back = Regions.halves(d, Regions.claimOrigin(claim), Regions.claimGap(claim));
            assertEquals(List.of(LegacyBoxes.half(d, 'A'), LegacyBoxes.half(d, 'B')), back,
                    d.id() + ": the halves a 0.35 claim names are exactly where 0.35 built them");
        }
    }

    @Test
    void eachKeptPlotIsCheckedAgainstTheWorld() {
        KeepArea keep = new KeepArea(1760, 128, 7296, 24, 576);
        Box border = new Box(-10_000, -64, -10_000, 9_999, 320, 9_999); // a 20,000 border: rows 3 and 4 are past it
        Map<Integer, String> bad = Regions.keepWorldProblems(keep, world(border, new int[]{0, 64, 0}, null));
        assertEquals(List.of(19, 20, 21, 22, 23, 24), new ArrayList<>(bad.keySet()),
                "the last row, z 10032..10367, is past a border 20,000 across; the rest fit");
        assertTrue(bad.get(19).contains("plot 19 reaches past the world border"), "said per plot: " + bad);
        assertEquals(Map.of(), Regions.keepWorldProblems(keep, world(new Box(-10_500, -64, -10_500, 10_499, 320,
                10_499), new int[]{0, 64, 0}, null)), "a border 21,000 across holds all 24");

        Box plot = keep.plot(1);
        int[] spawnNear = {plot.minX() - 10, 150, plot.minZ()};
        List<String> near = Regions.plotWorldProblems(1, plot, world(null, spawnNear, null));
        assertEquals(1, near.size(), "the spawn 9 blocks from plot 1: " + near);
        assertTrue(near.get(0).contains("the world's spawn is only 9 blocks from plot 1"), "said so: " + near);
        double[] safeInside = {plot.minX() + 20.5, 150, plot.minZ() + 20.5};
        assertTrue(Regions.plotWorldProblems(1, plot, world(null, null, safeInside)).get(0)
                .contains("safe_spot is inside plot 1"), "the safe spot inside it");
        List<String> low = Regions.plotWorldProblems(1, plot, new Regions.WorldFacts("games", true, 0, 256, null,
                null, null));
        assertTrue(low.get(0).contains("plot 1 needs y 128..303"), "a world too low for a plot: " + low);
        assertEquals(List.of(), Regions.plotWorldProblems(1, plot, world(null, null, null)), "nothing in the way");
        assertEquals(Map.of(), Regions.keepWorldProblems(null, world(border, null, null)), "no area, nothing to say");

        // each problem says what it is about, so the check gives the fix that goes with it
        assertEquals(Regions.PlotIssue.BORDER, Regions.PlotIssue.of(bad.get(19)), "past the border: " + bad.get(19));
        assertEquals(Regions.PlotIssue.SPAWN, Regions.PlotIssue.of(near.get(0)), "the spawn: " + near.get(0));
        assertEquals(Regions.PlotIssue.SAFE_SPOT, Regions.PlotIssue.of(Regions.plotWorldProblems(1, plot,
                world(null, null, safeInside)).get(0)), "the safe spot");
        assertEquals(Regions.PlotIssue.HEIGHT, Regions.PlotIssue.of(low.get(0)), "the height: " + low.get(0));
        assertEquals(Regions.PlotIssue.OTHER, Regions.PlotIssue.of("something else"), "anything else");
    }
}
