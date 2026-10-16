package com.dierks.homecraft.games.gen.golf;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.LegacyBoxes;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.api.V4Boxes;
import com.dierks.homecraft.games.golf.GolfCourse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PlotGrid}, the one plot geometry (GOLF-V4-SPEC §4.1): algo-3 (and older) plots are exactly
 * the 20 x 40 grid {@code GolfPlanner.plot} always gave, byte for byte; Golf v4's 40 x 64 plots fill a
 * 128 x 224 half 3 x 3; Tiny Golf keeps its 20 x 40 plots at every version; and the grid comes from
 * the plan's (or tag's) slot and version, never from the half's size.
 */
class PlotGridTest {

    /** The 0.36 GolfPlanner.plot, written out: three 20 x 40 plots a row, gaps 2 and 4, snake order. */
    private static int[] oldPlot(Box half, int i) {
        int row = i / 3;
        int col = row % 2 == 0 ? i % 3 : 2 - i % 3;
        return new int[]{half.minX() + col * (20 + 2), half.minZ() + row * (40 + 4)};
    }

    @Test
    void algoThreeAndOlderPlotsAreByteForByteTheOldGridInEveryHalf() {
        List<Box> halves = new ArrayList<>();
        for (Slots.Def def : List.of(Slots.DAILY_GOLF, Slots.TINY_GOLF, Slots.CLASSIC_GOLF)) {
            halves.add(LegacyBoxes.half(def, 'A'));
            halves.add(LegacyBoxes.half(def, 'B'));
            halves.add(def.half('A'));
        }
        halves.add(V4Boxes.half(Slots.DAILY_GOLF, 'B'));
        halves.add(Box.sized(-28_999_936, -48, 28_999_808, 64, 16, 128));
        for (Box half : halves) {
            for (int algo = 1; algo <= 3; algo++) {
                for (String slot : List.of("fresh_golf", "fresh_tiny_golf", "fresh_classic_golf", "anything")) {
                    PlotGrid g = PlotGrid.of(slot, algo);
                    assertSame(PlotGrid.V3, g, slot + " at algo " + algo + " stands on the v3 grid");
                    for (int i = 0; i < 18; i++) {
                        assertArrayEquals(oldPlot(half, i), g.plot(half, i), "hole " + (i + 1) + " of " + slot
                                + " at algo " + algo + " in " + half.describe() + " stands where it always did");
                        assertArrayEquals(oldPlot(half, i), GolfPlanner.plot(half, i),
                                "and GolfPlanner.plot still says so");
                    }
                }
            }
        }
    }

    @Test
    void golfOfTheWeekAtVersionFourHasNineFortyBySixtyFourPlotsThatFillItsHalf() {
        Box half = V4Boxes.half(Slots.DAILY_GOLF, 'A');
        PlotGrid g = PlotGrid.of(Slots.DAILY_GOLF.id(), 4);
        assertSame(PlotGrid.V4, g, "Golf of the Week at version 4 stands on the v4 grid");
        assertEquals(40, g.plotX(), "40 wide");
        assertEquals(64, g.plotZ(), "64 deep");
        assertArrayEquals(new int[]{128, 224}, g.needs(9), "nine plots need exactly 128 x 224 (3 x 40 + 2 x 4, 3 x 64 + 2 x 16)");
        List<Box> plots = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            assertTrue(g.fits(half, i), "plot " + (i + 1) + " is inside the half");
            Box p = g.plotBox(half, i, half.minY(), half.maxY());
            for (Box other : plots) {
                assertFalse(other.intersects(p), "plot " + (i + 1) + " meets no other");
            }
            plots.add(p);
        }
        assertEquals(half.minX(), plots.get(0).minX(), "the first plot at the half's corner");
        assertEquals(half.maxX(), plots.get(2).maxX(), "the third reaches the half's far side");
        assertEquals(half.maxZ(), plots.get(8).maxZ(), "the last row reaches its far end");
        assertArrayEquals(new int[]{half.minX() + 2 * 44, half.minZ() + 80}, g.plot(half, 3),
                "the second row runs back (snake order): hole 4 is under hole 3");
        assertFalse(g.fits(LegacyBoxes.half(Slots.DAILY_GOLF, 'A'), 1),
                "the old 64 x 128 half can't hold a v4 course's second plot");
        assertSame(PlotGrid.V4, PlotGrid.of(Slots.CLASSIC_GOLF.id(), 4), "Classic Golf's own v4 plans too");
        assertSame(PlotGrid.V4, PlotGrid.of(Slots.DAILY_GOLF, 5), "and later versions");
    }

    @Test
    void tinyGolfKeepsItsTwentyByFortyPlotsAtEveryVersion() {
        Box half = V4Boxes.half(Slots.TINY_GOLF, 'A');
        for (int algo = 1; algo <= 6; algo++) {
            PlotGrid g = PlotGrid.of(Slots.TINY_GOLF, algo);
            assertSame(PlotGrid.V3, g, "Tiny Golf at algo " + algo + " stands on 20 x 40 plots");
            for (int i = 0; i < 3; i++) {
                assertTrue(g.fits(half, i), "its plot " + (i + 1) + " fits its 64 x 48 half");
            }
        }
    }

    @Test
    void theGridComesFromTheSlotAndVersionNeverFromTheHalfsSize() {
        Box big = V4Boxes.half(Slots.CLASSIC_GOLF, 'A');
        // an archived algo-3 Golf of the Week recalled into the bigger v4 Classic keeps its old plots
        PlotGrid old = PlotGrid.of(Slots.DAILY_GOLF.id(), 3);
        assertSame(PlotGrid.V3, old, "an algo-3 plan in a 128 x 224 Classic keeps the 20 x 40 grid");
        assertArrayEquals(oldPlot(big, 4), old.plot(big, 4), "in the Classic's corner, where it was made");
        GenTag v3 = new GenTag("fresh_golf", Slots.GOLF, 3, 20725, 0, 1, 'A', "", 0, 0, 0, List.of(), List.of(), 0);
        GenTag v4 = new GenTag("fresh_golf", Slots.GOLF, 4, 20725, 0, 1, 'A', "", 0, 0, 0, List.of(), List.of(), 0);
        GenTag tiny = new GenTag("fresh_tiny_golf", Slots.GOLF, 4, 20725, 0, 1, 'A', "", 0, 0, 0, List.of(), List.of(), 0);
        GolfCourse.Hole h = new GolfCourse.Hole(null, null, 3, null, null);
        assertSame(PlotGrid.V3, PlotGrid.of(new GolfCourse("c", "C", "w", true, 1, List.of(h), v3)), "a v3 tag");
        assertSame(PlotGrid.V4, PlotGrid.of(new GolfCourse("c", "C", "w", true, 1, List.of(h), v4)), "a v4 tag");
        assertSame(PlotGrid.V3, PlotGrid.of(new GolfCourse("c", "C", "w", true, 1, List.of(h), tiny)),
                "a v4 Tiny Golf tag");
        assertSame(PlotGrid.V3, PlotGrid.of(new GolfCourse("c", "C", "w", true, 1, List.of(h))),
                "a hand-built course (no tag) has no v4 plots");
        assertSame(PlotGrid.V3, PlotGrid.of((GolfCourse) null), "nor does no course");
    }

    @Test
    void aGridRefusesNonsense() {
        assertThrows(IllegalArgumentException.class, () -> new PlotGrid(0, 40, 2, 4, 3), "a plot 0 wide");
        assertThrows(IllegalArgumentException.class, () -> new PlotGrid(20, 40, -1, 4, 3), "a negative gap");
        assertThrows(IllegalArgumentException.class, () -> new PlotGrid(20, 40, 2, 4, 0), "no columns");
    }
}
