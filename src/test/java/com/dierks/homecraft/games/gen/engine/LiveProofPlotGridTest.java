package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.api.V4Boxes;
import com.dierks.homecraft.games.gen.engine.GenKit.FakeWorld;
import com.dierks.homecraft.games.gen.golf.GolfPlanner;
import com.dierks.homecraft.games.gen.golf.PlotGrid;
import com.dierks.homecraft.games.golf.GolfCourse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The live proof's pond scan on Golf v4's plots (GOLF-V4-SPEC §4.1): {@link LiveProof#scanBox} spreads
 * a hole's scan over the plot its course's own tag gives it ({@link PlotGrid#of(GolfCourse)}) — 40 x 64
 * on a Golf v4 layout, 20 x 40 on Adventure Golf's and on a course with no tag — so water anywhere in a
 * v4 plot is proven sealed, and an older layout is scanned exactly as before.
 */
class LiveProofPlotGridTest {

    private static final Box HALF = V4Boxes.half(Slots.DAILY_GOLF, 'A');
    private static final int T = HALF.minY() + GolfPlanner.TURF_ABOVE_FLOOR;

    /** A small hole in the half's first plot: tee, cup and bounds near its corner. */
    private static GolfCourse.Hole hole() {
        int x = HALF.minX();
        int z = HALF.minZ();
        return new GolfCourse.Hole(new GolfCourse.Tee(x + 4.5, T, z + 3.5, 0f), new GolfCourse.Spot(x + 4, T - 2, z + 10),
                3, new GolfCourse.Spot(x + 1, T - 3, z + 1), new GolfCourse.Spot(x + 7, T + 4, z + 12));
    }

    private static GolfCourse course(GenTag tag) {
        return new GolfCourse("fresh_golf", "Golf of the Week", "games", true, 1, List.of(hole()), tag);
    }

    private static GenTag tag(int algo) {
        return new GenTag("fresh_golf", Slots.GOLF, algo, 20725, 0, 1, 'A', "", 0, 0, 0, List.of(), List.of(), 0);
    }

    @Test
    void aV4HolesScanCoversItsWholeFortyBySixtyFourPlot() {
        GolfCourse.Hole h = hole();
        Box v4 = LiveProof.scanBox(h, 0, HALF, PlotGrid.V4);
        Box v3 = LiveProof.scanBox(h, 0, HALF, PlotGrid.V3);
        assertTrue(v4.contains(HALF.minX() + 39, T, HALF.minZ() + 63), "the v4 scan reaches the 40 x 64 plot's far corner");
        assertFalse(v3.contains(HALF.minX() + 39, T, HALF.minZ() + 63), "the v3 one stops at 20 x 40");
        assertEquals(v3, LiveProof.scanBox(h, 0, HALF), "the old three-argument scan is the v3 one");
        Box second = LiveProof.scanBox(h, 1, HALF, PlotGrid.V4);
        assertTrue(second.contains(HALF.minX() + 44, T, HALF.minZ()), "hole 2's v4 plot starts 44 along");
    }

    @Test
    void anUnsealedPondInAV4PlotIsFoundOnlyOnAV4Layout() {
        FakeWorld w = new FakeWorld("games");
        GolfCourse.Hole h = hole();
        w.put((int) Math.floor(h.tee().x()), T - 1, (int) Math.floor(h.tee().z()), "minecraft:white_concrete");
        w.put(h.cup().x(), h.cup().y(), h.cup().z(), "minecraft:black_concrete");
        int wx = HALF.minX() + 30; // inside the v4 plot, outside the v3 one and two clear of the bounds
        int wz = HALF.minZ() + 50;
        w.put(wx, T - 1, wz, "minecraft:water[level=0]");
        List<String> v4 = check(course(tag(4)), w);
        assertEquals(1, v4.size(), "the v4 layout's scan finds the pond: " + v4);
        assertTrue(v4.get(0).endsWith("a pond isn't sealed"), "and says it isn't sealed: " + v4);
        assertEquals(List.of(), check(course(tag(3)), w), "an Adventure Golf layout scans its 20 x 40 plot, as before");
        assertEquals(List.of(), check(course(null), w), "a course with no tag too");
        GenTag tiny = new GenTag("fresh_tiny_golf", Slots.GOLF, 4, 20725, 0, 1, 'A', "", 0, 0, 0, List.of(), List.of(), 0);
        assertEquals(List.of(), check(course(tiny), w), "and Tiny Golf at v4 keeps its 20 x 40 plots");
    }

    private static List<String> check(GolfCourse golf, FakeWorld w) {
        WorldPort.ChunkView v = new WorldPort.ChunkView() {
            @Override
            public boolean sectionEmpty(int y) {
                return false;
            }

            @Override
            public boolean air(int x, int y, int z) {
                return w.at(x, y, z) == null;
            }

            @Override
            public String block(int x, int y, int z) {
                return w.at(x, y, z);
            }
        };
        return LiveProof.structure(golf, HALF, (x, y, z) -> !v.air(x, y, z), (x, y, z) -> GenService.seals(v, x, y, z),
                (x, y, z) -> GenService.water(v, x, y, z));
    }
}
