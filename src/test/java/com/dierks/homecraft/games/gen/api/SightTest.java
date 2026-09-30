package com.dierks.homecraft.games.gen.api;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a player can see (LAYOUT-SPEC §1.1-§1.3): the chunk distance between a place grown by its
 * reach and another place, x and z only, negative coordinates flooring to their chunk; "in sight at
 * V" exactly when that distance is at most V + 1. Pinned by hand cases and by 20,000 seeded random
 * pairs against a brute force that walks every chunk a player can stand in and every chunk sent to
 * them, and against vanilla's own send rule, which the square bound must always cover.
 */
class SightTest {

    private static Box chunkRow(int fromChunk, int toChunk) {
        return new Box(fromChunk * 16, 100, 0, toChunk * 16 + 15, 110, 15);
    }

    @Test
    void theConstantsAreTheSpecs() {
        assertEquals(32, Sight.DESIGN_VIEW, "Paper's largest view distance");
        assertEquals(16, Sight.REACH, "a spectator's 8 and a kept course's 16 are both inside it");
        assertEquals(576, Sight.GAP, "36 chunk columns");
        assertEquals(Sight.DESIGN_VIEW + 2, Sight.GAP / 16 - 2, "clear up to 34: two chunks to spare at 32");
    }

    @Test
    void chunkAlignedBoxesWithGEmptyBlocksBetweenAreGOver16Apart() {
        Box a = chunkRow(0, 3);
        for (int g : new int[]{0, 16, 32, 160, 544, 576}) {
            Box b = chunkRow(4 + g / 16, 7 + g / 16);
            assertEquals(g / 16, Sight.chunksApart(a, Sight.REACH, b), g + " blocks between: reach ends one chunk"
                    + " out, so the gap in chunks is G / 16");
            assertEquals(g / 16, Sight.chunksApart(b, Sight.REACH, a), "and the same from the other side");
        }
        assertEquals(36, Sight.chunksApart(a, Sight.REACH, chunkRow(40, 41)), "the layout's gap is 36 chunks");
    }

    @Test
    void touchingOverlappingAndStackedBoxesAreZeroApart() {
        Box a = chunkRow(0, 1);
        assertEquals(1, Sight.chunksApart(a, 0, chunkRow(2, 3)), "touching: neighbouring chunks are one apart");
        assertEquals(0, Sight.chunksApart(a, 16, a), "a box and itself");
        Box above = new Box(0, 300, 0, 31, 310, 15);
        assertEquals(0, Sight.chunksApart(a, 0, above), "stacked straight above: the same columns, in sight");
        assertTrue(Sight.inSight(a, 0, above, 2), "height never hides anything: whole columns are sent");
    }

    @Test
    void negativeCoordinatesFloorToTheirChunk() {
        Box minusOne = new Box(-1, 64, -1, -1, 64, -1);
        Box zero = new Box(0, 64, 0, 0, 64, 0);
        assertEquals(1, Sight.chunksApart(minusOne, 0, zero), "block -1 is chunk -1, next to chunk 0");
        Box minus17 = new Box(-17, 64, 0, -17, 64, 0);
        assertEquals(2, Sight.chunksApart(minus17, 0, zero), "block -17 is chunk -2, two from chunk 0");
        Box farWest = new Box(-600, 64, 0, -576, 64, 0);
        assertEquals(36, Sight.chunksApart(farWest, 0, zero), "block -576 is chunk -36 (and -600 chunk -38)");
        assertEquals(37, Sight.chunksApart(zero, 16, farWest.translate(0, 0, -600)),
                "600 blocks north as well: 35 chunks along x and 37 along z after the reach; the larger counts");
    }

    @Test
    void theBoundaryIsExactlyViewPlusOne() {
        Box a = chunkRow(0, 0);
        Box b = chunkRow(11, 11); // the reach ends in chunk 1: chunk 11 is 10 further
        assertEquals(10, Sight.chunksApart(a, Sight.REACH, b), "10 chunk columns apart");
        assertTrue(Sight.inSight(a, Sight.REACH, b, 9), "V = 9 sends up to 10 away");
        assertFalse(Sight.inSight(a, Sight.REACH, b, 8), "V = 8 sends up to 9 away");
        assertEquals(8, Sight.clearUpTo(a, Sight.REACH, b), "clear up to view distance 8");
        assertEquals(-2, Sight.clearUpTo(a, 0, a), "a box is in sight of itself at every view distance");
    }

    @Test
    void aPointHasNoReachAndNonAlignedBoxesCountTheirChunks() {
        Box spawn = new Box(0, 100, 0, 0, 100, 0);
        Box course = new Box(30, 160, 5, 40, 170, 9); // chunk 1
        assertEquals(1, Sight.chunksApart(spawn, 0, course), "chunk 0 to chunks 1-2: one apart");
        Box far = new Box(6080, 160, 6592, 6143, 207, 6655);
        assertEquals(6592 / 16, Sight.chunksApart(spawn, 0, far), "the spawn is 412 chunks from a far course");
        assertEquals(Sight.chunksApart(spawn, 0, far) - 1, Sight.chunksApart(far, Sight.REACH, spawn),
                "the course's own reach brings it one chunk nearer");
    }

    @Test
    void aNegativeReachIsRefused() {
        Box a = chunkRow(0, 0);
        assertThrows(IllegalArgumentException.class, () -> Sight.chunksApart(a, -1, a), "a reach can't be negative");
        assertThrows(IllegalArgumentException.class, () -> new Sight.Spot("x", "w", a, -1),
                "nor can a spot's");
    }

    @Test
    void inSightMatchesABruteForceOfEveryChunkAPlayerCanStandInAndEverySentChunk() {
        Random r = new Random(0x516417L);
        int checked = 0;
        int inSight = 0;
        for (int i = 0; i < 20_000; i++) {
            Box on = randomBox(r);
            Box seen = randomBox(r);
            int reach = r.nextInt(4) == 0 ? 0 : r.nextInt(33);
            int view = 2 + r.nextInt(31);
            boolean brute = brute(on, reach, seen, view);
            assertEquals(brute, Sight.inSight(on, reach, seen, view), "on " + on.describe() + " reach " + reach
                    + ", seen " + seen.describe() + ", view " + view + ": the brute force says " + brute);
            assertTrue(!vanilla(on, reach, seen, view) || Sight.inSight(on, reach, seen, view),
                    "vanilla never sends a chunk the square bound calls out of sight (" + on.describe() + " / "
                            + seen.describe() + ", view " + view + ")");
            checked++;
            inSight += brute ? 1 : 0;
        }
        assertEquals(20_000, checked, "every pair checked");
        assertTrue(inSight > 1_000 && inSight < 19_000, "both answers were exercised: " + inSight + " in sight");
    }

    /** Boxes of 1..400 blocks each way, anywhere within +-20,000 (so near, far and across 0). */
    private static Box randomBox(Random r) {
        int x = r.nextInt(40_001) - 20_000;
        int z = r.nextInt(40_001) - 20_000;
        if (r.nextBoolean()) {
            x = r.nextInt(2_001) - 1_000; // many close pairs too
            z = r.nextInt(2_001) - 1_000;
        }
        return Box.sized(x, 64, z, 1 + r.nextInt(400), 1 + r.nextInt(50), 1 + r.nextInt(400));
    }

    /**
     * Every chunk a player within {@code reach} of {@code on} can stand in, and every chunk within
     * V + 1 of it on both axes: does any of them hold a column of {@code seen}?
     */
    private static boolean brute(Box on, int reach, Box seen, int view) {
        int cx0 = Math.floorDiv(on.minX() - reach, 16);
        int cx1 = Math.floorDiv(on.maxX() + reach, 16);
        int cz0 = Math.floorDiv(on.minZ() - reach, 16);
        int cz1 = Math.floorDiv(on.maxZ() + reach, 16);
        int sx0 = Math.floorDiv(seen.minX(), 16);
        int sx1 = Math.floorDiv(seen.maxX(), 16);
        int sz0 = Math.floorDiv(seen.minZ(), 16);
        int sz1 = Math.floorDiv(seen.maxZ(), 16);
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                // the chunks sent to a player in chunk (cx, cz): |dx|, |dz| <= V + 1
                int lx = Math.max(sx0, cx - view - 1);
                int hx = Math.min(sx1, cx + view + 1);
                int lz = Math.max(sz0, cz - view - 1);
                int hz = Math.min(sz1, cz + view + 1);
                if (lx <= hx && lz <= hz) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Vanilla's own send rules, re-implemented: the spec's reading
     * {@code max(0,|dx|-2)^2 + max(0,|dz|-2)^2 < V^2}, and ChunkTrackingView's circle with the outer
     * ring included. True when some chunk of {@code seen} is sent to some player within reach.
     */
    private static boolean vanilla(Box on, int reach, Box seen, int view) {
        int cx0 = Math.floorDiv(on.minX() - reach, 16);
        int cx1 = Math.floorDiv(on.maxX() + reach, 16);
        int cz0 = Math.floorDiv(on.minZ() - reach, 16);
        int cz1 = Math.floorDiv(on.maxZ() + reach, 16);
        int sx0 = Math.floorDiv(seen.minX(), 16);
        int sx1 = Math.floorDiv(seen.maxX(), 16);
        int sz0 = Math.floorDiv(seen.minZ(), 16);
        int sz1 = Math.floorDiv(seen.maxZ(), 16);
        // the nearest pair of chunks is enough: both rules only grow with distance
        long dx = Math.max(0, Math.max(sx0 - cx1, cx0 - sx1));
        long dz = Math.max(0, Math.max(sz0 - cz1, cz0 - sz1));
        long a = Math.max(0, dx - 2);
        long b = Math.max(0, dz - 2);
        boolean spec = a * a + b * b < (long) view * view;
        long i = Math.max(0, dx - 1);
        long j = Math.max(0, dz - 1);
        long l = Math.max(0, Math.max(i, j) - 1);
        long m = Math.min(i, j);
        boolean tracking = m * m + l * l < (long) view * view;
        return spec || tracking;
    }

    @Test
    void pairsListsOnlyTheOnesInSightNearestFirstAndOnlyInOneWorld() {
        Sight.Spot a = new Sight.Spot("a", "games", chunkRow(0, 3), Sight.REACH);
        Sight.Spot near = new Sight.Spot("near", "games", chunkRow(6, 7), Sight.REACH); // 2 chunks from a
        Sight.Spot mid = new Sight.Spot("mid", "GAMES", chunkRow(18, 19), Sight.REACH); // 10 from near, 14 from a
        Sight.Spot far = new Sight.Spot("far", "games", chunkRow(100, 101), Sight.REACH);
        Sight.Spot other = new Sight.Spot("other", "sky", chunkRow(0, 3), Sight.REACH);
        List<Sight.Pair> pairs = Sight.pairs(List.of(a, near, mid, far, other), 10);
        List<String> names = new ArrayList<>();
        for (Sight.Pair p : pairs) {
            names.add(p.a().name() + "-" + p.b().name() + ":" + p.chunks());
        }
        assertEquals(List.of("a-near:2", "near-mid:10"), names, "only pairs within V + 1, nearest first, one world"
                + " (matched ignoring case); a spot in another world never sees one here");
        assertEquals(List.of(), Sight.pairs(List.of(a, far), 32), "96 chunks apart: out of sight at 32");
        assertEquals(2, Sight.nearest(List.of(far, mid, near, a)).chunks(), "the nearest pair, however listed");
        assertNull(Sight.nearest(List.of(a, other)), "no world holds two spots: no nearest pair");
        assertEquals(0, Sight.pairs(List.of(a, other), 32).size(), "and none in sight");
    }

    @Test
    void aPairIsJudgedTheNearerWayRound() {
        Sight.Spot place = new Sight.Spot("course", "games", chunkRow(10, 11), Sight.REACH);
        Sight.Spot point = new Sight.Spot("spawn", "games", new Box(0, 100, 0, 0, 100, 0), 0);
        assertEquals(Math.min(Sight.chunksApart(place.box(), 16, point.box()), Sight.chunksApart(point.box(), 0,
                place.box())), Sight.apart(place, point), "the smaller of the two ways round");
        assertEquals(9, Sight.apart(place, point), "the course's reach brings it to chunk 9, 9 from chunk 0");
        assertEquals(Sight.apart(place, point), Sight.apart(point, place), "and it doesn't matter which is first");
    }
}
