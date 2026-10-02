package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Palette;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.SignText;
import com.dierks.homecraft.games.gen.api.SyntheticMountain;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BuildJob's lean index (MOUNTAIN-V2-SPEC §13.6): per chunk one sorted {@code long[]}, a block in each long.
 *
 * <p>Pinned here: every planned block comes back out of its cell exactly (negative chunks too), each
 * in its own chunk; a chunk's cells run bottom-up, then along x, then z, with two blocks at one spot in
 * plan order; "is this spot planned?" answers the same by binary search, by the forward cursor a scan
 * uses, and by a plain set, signs included; the palette is held once in the world's spelling and water
 * is noticed; bad plans are refused as the old index refused them (the first bad block named); and the
 * memory bound: a Mountain Run v2-sized plan is held in about 8 bytes a block (16 at most), where the
 * object index took about 120.
 */
class BuildIndexTest {

    /** A half over negative and positive chunks, 64 high. */
    private static final Box HALF = Box.sized(-40, -20, -24, 72, 64, 56);

    private static Plan plan(Box half, List<String> palette, List<BlockOp> ops, List<SignText> signs) {
        Plan like = GenKit.plan(com.dierks.homecraft.games.gen.api.Slots.DAILY_PARKOUR_EASY,
                com.dierks.homecraft.games.gen.api.LegacyBoxes.half(
                        com.dierks.homecraft.games.gen.api.Slots.DAILY_PARKOUR_EASY, 'A'), 1, 1);
        return Plan.of(like.slot(), 1, 1, half, palette, ops, signs, List.of(), like.course(), List.of(), 0);
    }

    private static BlockOp op(int x, int y, int z, int state) {
        return new BlockOp(x, y, z, (short) state);
    }

    private static List<BlockOp> randomOps(SplittableRandom rnd, Box half, int n, int states) {
        List<BlockOp> ops = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            ops.add(op(rnd.nextInt(half.minX(), half.maxX() + 1), rnd.nextInt(half.minY(), half.maxY() + 1),
                    rnd.nextInt(half.minZ(), half.maxZ() + 1), rnd.nextInt(states)));
        }
        for (int i = 0; i < n / 20; i++) { // and some spots twice, with the same or another block
            BlockOp o = ops.get(rnd.nextInt(ops.size()));
            ops.add(rnd.nextInt(ops.size() + 1), op(o.x(), o.y(), o.z(), rnd.nextInt(states)));
        }
        return ops;
    }

    @Test
    void everyBlockComesBackOutOfItsCellInItsChunkSortedBottomUpWithRepeatsInPlanOrder() {
        SplittableRandom rnd = new SplittableRandom(3);
        List<String> palette = List.of("minecraft:stone", "minecraft:white_concrete", "minecraft:packed_ice");
        List<BlockOp> ops = randomOps(rnd, HALF, 4_000, palette.size());
        BuildIndex index = BuildIndex.of(HALF, plan(HALF, palette, ops, List.of()), GenKit.FakeWorld::canonicalOf);
        assertEquals(ops.size(), index.blocks(), "every planned block is held");
        Map<Long, List<long[]>> expected = new HashMap<>(); // chunk -> {y, x, z, ord, state} in plan order
        Map<Long, Integer> ords = new HashMap<>();
        for (BlockOp o : ops) {
            long chunk = GenKit.FakeWorld.chunk(o.x() >> 4, o.z() >> 4);
            int ord = ords.merge(chunk, 1, Integer::sum) - 1;
            expected.computeIfAbsent(chunk, k -> new ArrayList<>()).add(new long[]{o.y(), o.x(), o.z(), ord, o.state()});
        }
        int seen = 0;
        for (int cx = HALF.minX() >> 4; cx <= HALF.maxX() >> 4; cx++) {
            for (int cz = HALF.minZ() >> 4; cz <= HALF.maxZ() >> 4; cz++) {
                BuildIndex.Chunk c = index.chunk(cx, cz);
                List<long[]> want = expected.getOrDefault(GenKit.FakeWorld.chunk(cx, cz), List.of());
                if (want.isEmpty()) {
                    assertNull(c, "a chunk with nothing planned holds nothing: " + cx + "," + cz);
                    continue;
                }
                want = new ArrayList<>(want);
                want.sort((a, b) -> {
                    for (int k = 0; k < 4; k++) {
                        if (a[k] != b[k]) {
                            return Long.compare(a[k], b[k]);
                        }
                    }
                    return 0;
                });
                assertEquals(want.size(), c.size(), "chunk " + cx + "," + cz + " holds its own blocks");
                for (int i = 0; i < c.size(); i++) {
                    long[] w = want.get(i);
                    String what = "chunk " + cx + "," + cz + " cell " + i;
                    assertEquals(w[0], index.floor() + c.dy(i), what + ": its height, in rising order");
                    assertEquals(w[1], c.x(i), what + ": its x, then along x");
                    assertEquals(w[2], c.z(i), what + ": its z, then along z");
                    assertEquals(w[3], c.ord(i), what + ": its place in the plan (two at one spot in plan order)");
                    assertEquals(w[4], c.state(i), what + ": its palette index");
                    assertEquals(cx, c.x(i) >> 4, what + ": inside its chunk along x (negative chunks too)");
                    assertEquals(cz, c.z(i) >> 4, what + ": and along z");
                }
                seen += c.size();
            }
        }
        assertEquals(ops.size(), seen, "every block is in exactly one chunk");
        assertNull(index.chunk((HALF.maxX() >> 4) + 1, HALF.minZ() >> 4), "no chunk past the half");
        assertNull(index.chunk(HALF.minX() >> 4, (HALF.minZ() >> 4) - 1), "nor before it");
    }

    @Test
    void isThisSpotPlannedAnswersTheSameBySearchByCursorAndByASetSignsIncluded() {
        SplittableRandom rnd = new SplittableRandom(8);
        Box half = Box.sized(4096, 160, 4096, 48, 32, 48);
        List<BlockOp> ops = randomOps(rnd, half, 3_000, 2);
        List<SignText> signs = List.of(new SignText(4100, 170, 4100, Palette.sign(4), List.of("A")),
                new SignText(4130, 161, 4140, Palette.sign(0), List.of("B", "C")),
                new SignText(4130, 191, 4141, Palette.sign(8), List.of("")));
        BuildIndex index = BuildIndex.of(half, plan(half, List.of("minecraft:stone", "minecraft:dirt"), ops, signs),
                GenKit.FakeWorld::canonicalOf);
        Set<Long> planned = new HashSet<>();
        for (BlockOp o : ops) {
            planned.add(GenKit.pos(o.x(), o.y(), o.z()));
        }
        for (SignText s : signs) {
            planned.add(GenKit.pos(s.x(), s.y(), s.z()));
        }
        int hits = 0;
        for (int cx = half.minX() >> 4; cx <= half.maxX() >> 4; cx++) {
            for (int cz = half.minZ() >> 4; cz <= half.maxZ() >> 4; cz++) {
                BuildIndex.Chunk c = index.chunk(cx, cz);
                BuildIndex.Cursor cursor = c == null ? null : c.cursor();
                for (int y = half.minY(); y <= half.maxY(); y++) {
                    if (y % 16 == 3) {
                        continue; // a scan skips empty sections: the cursor only ever moves forward
                    }
                    for (int x = cx << 4; x < (cx << 4) + 16; x++) {
                        for (int z = cz << 4; z < (cz << 4) + 16; z++) {
                            boolean want = planned.contains(GenKit.pos(x, y, z));
                            int dy = y - half.minY();
                            String at = x + "," + y + "," + z;
                            assertEquals(want, c != null && c.names(dy, x & 15, z & 15), "binary search at " + at);
                            assertEquals(want, cursor != null && cursor.names(dy, x & 15, z & 15), "cursor at " + at);
                            hits += want ? 1 : 0;
                        }
                    }
                }
            }
        }
        assertTrue(hits > 2_000, "most planned spots were asked about: " + hits);
        BuildIndex.Chunk withSigns = index.chunk(4130 >> 4, 4140 >> 4);
        assertEquals(List.of("B", "C", "", ""), withSigns.signs().get(0).lines(), "a sign's lines are four, as the world shows");
        assertEquals("minecraft:" + Palette.sign(0).substring("minecraft:".length()), withSigns.signs().get(0).state(),
                "in the world's spelling");
    }

    @Test
    void thePaletteIsHeldOnceInTheWorldsSpellingAndWaterIsNoticed() {
        Box half = Box.sized(0, 0, 0, 16, 16, 16);
        BuildIndex dry = BuildIndex.of(half, plan(half, List.of("STONE", "minecraft:Dirt"),
                List.of(op(1, 1, 1, 0), op(2, 2, 2, 1)), List.of()), GenKit.FakeWorld::canonicalOf);
        assertEquals("minecraft:stone", dry.state(0), "each entry in the world's spelling");
        assertEquals("minecraft:dirt", dry.state(1), "each entry once");
        assertFalse(dry.water(), "no water");
        BuildIndex wet = BuildIndex.of(half, plan(half, List.of("minecraft:stone", "minecraft:water[level=0]"),
                List.of(op(1, 1, 1, 0), op(2, 2, 2, 1)), List.of()), GenKit.FakeWorld::canonicalOf);
        assertTrue(wet.water(), "a planned water block makes the build staged");
        BuildIndex unused = BuildIndex.of(half, plan(half, List.of("minecraft:stone", "minecraft:water[level=0]"),
                List.of(op(1, 1, 1, 0)), List.of()), GenKit.FakeWorld::canonicalOf);
        assertFalse(unused.water(), "water in the palette that no block uses is not water in the plan");
    }

    @Test
    void badPlansAreRefusedAsTheObjectIndexRefusedThem() {
        Box half = Box.sized(0, 0, 0, 32, 16, 32);
        List<String> palette = List.of("minecraft:stone");
        IllegalArgumentException outside = assertThrows(IllegalArgumentException.class, () -> BuildIndex.of(half,
                plan(half, palette, List.of(op(1, 1, 1, 0), op(40, 1, 1, 0), op(-1, 1, 1, 0)), List.of()),
                GenKit.FakeWorld::canonicalOf), "a block outside the half");
        assertTrue(outside.getMessage().contains("40,1,1"), "names the first one in plan order: " + outside.getMessage());
        assertThrows(IllegalArgumentException.class, () -> BuildIndex.of(half, plan(half, palette, List.of(),
                        List.of(new SignText(1, 99, 1, Palette.sign(0), List.of()))), GenKit.FakeWorld::canonicalOf),
                "a sign outside the half");
        assertThrows(IllegalArgumentException.class, () -> BuildIndex.of(half, plan(half,
                List.of("minecraft:bogus"), List.of(op(1, 1, 1, 0)), List.of()), GenKit.FakeWorld::canonicalOf),
                "a palette entry that isn't a block");
        assertThrows(ArrayIndexOutOfBoundsException.class, () -> BuildIndex.of(half, plan(half, palette,
                List.of(op(1, 1, 1, 1), op(99, 1, 1, 0)), List.of()), GenKit.FakeWorld::canonicalOf),
                "a block with no palette entry, before a later one outside (plan order, as before)");
        Box tall = Box.sized(0, -2_100, 0, 16, BuildIndex.MAX_HEIGHT + 1, 16);
        assertThrows(IllegalArgumentException.class, () -> BuildIndex.of(tall, plan(tall, palette,
                List.of(op(1, 1, 1, 0)), List.of()), GenKit.FakeWorld::canonicalOf),
                "a half taller than any world can't be packed");
        Box tallest = Box.sized(0, -2_032, 0, 16, BuildIndex.MAX_HEIGHT, 16);
        BuildIndex top = BuildIndex.of(tallest, plan(tallest, palette, List.of(op(1, tallest.maxY(), 1, 0),
                op(1, tallest.minY(), 1, 0)), List.of()), GenKit.FakeWorld::canonicalOf);
        assertEquals(tallest.minY(), top.floor() + top.chunk(0, 0).dy(0), "the floor packs");
        assertEquals(tallest.maxY(), top.floor() + top.chunk(0, 0).dy(1), "and so does the ceiling of the tallest half");
    }

    @Test
    void aMountainRunSizedPlanIsHeldInAboutEightBytesABlock() {
        Plan p = SyntheticMountain.plan(41);
        BuildIndex index = BuildIndex.of(SyntheticMountain.HALF, p, GenKit.FakeWorld::canonicalOf);
        long n = p.ops().size();
        long bytes = index.bytes();
        double perBlock = (double) bytes / n;
        assertTrue(n > 300_000, "a Mountain Run v2-sized plan: " + n + " blocks");
        assertTrue(perBlock <= 16.0, "at most 16 bytes a block (the target; ~120 for the object index): " + perBlock);
        assertTrue(perBlock <= 9.0, "in fact about 8, a long a block plus each chunk's few dozen bytes: " + perBlock);
        long cells = 0;
        int chunks = 0;
        for (int cx = SyntheticMountain.HALF.minX() >> 4; cx <= SyntheticMountain.HALF.maxX() >> 4; cx++) {
            for (int cz = SyntheticMountain.HALF.minZ() >> 4; cz <= SyntheticMountain.HALF.maxZ() >> 4; cz++) {
                BuildIndex.Chunk c = index.chunk(cx, cz);
                if (c != null) {
                    cells += c.size();
                    chunks++;
                }
            }
        }
        assertEquals(n, cells, "one cell (one long) a block, and no object for any block");
        assertTrue(chunks <= SyntheticMountain.HALF.chunkCount(), "and at most one array a chunk: " + chunks);
        System.out.println("BuildIndex on a synthetic Mountain Run: " + n + " blocks in " + bytes + " bytes ("
                + String.format("%.2f", perBlock) + " a block)");
    }
}
