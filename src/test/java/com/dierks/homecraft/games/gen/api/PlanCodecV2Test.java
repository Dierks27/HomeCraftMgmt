package com.dierks.homecraft.games.gen.api;

import com.dierks.homecraft.games.gen.V2Fixtures;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.SplittableRandom;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PlanCodec version 2, the compact archive format (MOUNTAIN-V2-SPEC §13.5): blocks as columns of runs.
 *
 * <p>Pinned here: the hard rule, {@code decode(encode(plan))} is the plan exactly (the same blocks in
 * the same order, so the same hash), for random plans of every shape (unsorted, sorted, repeated
 * spots, every palette index range, coordinates anywhere in the int range, none at all); golden bytes
 * for version 2 (a hand-worked block section, the payloads of the two plans version 1 pins, and a
 * fixed stored blob); version 1 still read (its golden blob and the frozen algo-2 rows), and written
 * by default only when version 2 would be bigger; a Mountain Run v2-sized plan stored in at most
 * 0.6 MB; and every guard: cut-short, corrupted or crafted bytes fail cleanly, at once, never thrown,
 * never a hang and never a huge allocation.
 */
class PlanCodecV2Test {

    // ---- golden bytes --------------------------------------------------------------------------------

    /**
     * A small block section worked by hand: two runs and a gap in one column, a step to the next column,
     * and back to the first spot (a column of its own: version 2 never reorders).
     */
    @Test
    void aSmallBlockSectionIsTheseBytesWorkedByHand() {
        List<BlockOp> ops = List.of(op(10, 64, 20, 1), op(10, 65, 20, 1), op(10, 66, 20, 2), op(10, 70, 20, 2),
                op(11, 64, 20, 0), op(10, 64, 20, 1));
        String expected = "00000006" // six blocks
                + "14" + "28" + "03" // column x 10 (zigzag 20), z 20 (40), three runs
                + "8001" + "02" + "01" // y 64 from 0 (zigzag 128), two long, palette 1
                + "02" + "01" + "02" // y 66, one above the last run's top (65), one long, palette 2
                + "08" + "01" + "02" // y 70, four above 66, one long, palette 2
                + "02" + "00" + "01" + "00" + "01" + "00" // column x + 1, z + 0, one run: y as the last column's first
                + "01" + "00" + "01" + "00" + "01" + "01"; // back to x 10 (x - 1), one run of palette 1 at 64
        assertEquals(expected, HexFormat.of().formatHex(PlanCodec.columns(ops)),
                "the block section is the count, then each column's deltas and runs, exactly as the class comment says");
    }

    @Test
    void theVersionTwoPayloadsArePinnedByGoldenBytes() throws Exception {
        byte[] trial = PlanCodec.payload(PlanCodecTest.trialPlan());
        assertEquals(PlanCodec.VERSION, trial[4], "a new row is written in version 2");
        assertEquals(GOLDEN_TRIAL_V2_SHA, sha(trial), "the trial payload's version-2 bytes are pinned: a change to"
                + " the format must bump PlanCodec.VERSION and keep reading versions 1 and 2");
        assertEquals(GOLDEN_GOLF_V2_SHA, sha(PlanCodec.payload(PlanCodecTest.golfPlan())), "and the golf payload's");
        assertArrayEquals(trial, PlanCodec.payload(PlanCodecTest.trialPlan(), PlanCodec.VERSION),
                "the default is version 2 asked for by name");
        Plan p = PlanCodecTest.trialPlan();
        byte[] v1 = PlanCodec.payload(p, PlanCodec.V1);
        int head = blocksAt(p);
        assertArrayEquals(Arrays.copyOfRange(v1, 5, head), Arrays.copyOfRange(trial, 5, head),
                "everything before the blocks is as version 1 (slot, algo, seed, half, palette)");
        assertArrayEquals(Arrays.copyOfRange(v1, head + 4 + 14 * p.ops().size(), v1.length),
                Arrays.copyOfRange(trial, head + PlanCodec.columns(p.ops()).length, trial.length),
                "and everything after them too (signs, keep-clear boxes, summary, work, hash, course)");
    }

    @Test
    void aFixedVersionTwoBlobDecodesToTheSamePlan() {
        PlanCodec.Read read = PlanCodec.decode(Base64.getDecoder().decode(GOLDEN_TRIAL_V2_GZIP));
        assertTrue(read.ok(), "a blob stored by version 2 reads: " + read.problem());
        assertEquals(PlanCodecTest.trialPlan(), read.plan(), "to exactly the plan it was written from");
    }

    // ---- the hard rule: exact round trips ------------------------------------------------------------

    @Test
    void randomPlansOfEveryShapeComeBackExactlyInBothVersions() {
        SplittableRandom rnd = new SplittableRandom(20261002L);
        int[] paletteSizes = {1, 2, 127, 128, 129, 16_383, 16_384, 16_385, 32_768};
        for (int i = 0; i < 300; i++) {
            Shape shape = Shape.values()[i % Shape.values().length];
            int palette = paletteSizes[rnd.nextInt(paletteSizes.length)];
            Plan p = randomPlan(rnd, shape, palette, rnd.nextInt(i % 10 == 0 ? 3 : 400));
            String what = shape + " plan " + i + " (" + p.ops().size() + " blocks, palette " + palette + ")";
            assertExact(p, what);
        }
    }

    @Test
    void edgeCasesComeBackExactly() {
        assertExact(plan(List.of(), 1), "a plan with no blocks");
        assertExact(plan(List.of(op(0, 0, 0, 0)), 1), "one block at the origin");
        assertExact(plan(List.of(op(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, 0),
                op(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, 0),
                op(Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, 0)), 1), "the int range's corners");
        assertExact(plan(List.of(op(5, Integer.MAX_VALUE - 1, 5, 0), op(5, Integer.MAX_VALUE, 5, 0),
                op(5, Integer.MIN_VALUE, 5, 0)), 1), "a run up to the top of the int range, then the bottom (no wrap)");
        assertExact(plan(List.of(op(-1, -64, -1, 3), op(-1, -63, -1, 3), op(-1, -63, -1, 3), op(-1, -62, -1, 2),
                op(-1, -63, -1, 1)), 4), "repeated spots with other blocks, going back down");
        assertExact(plan(List.of(op(7, 100, 7, 32_767), op(7, 101, 7, 32_767), op(7, 102, 7, 0)), 32_768),
                "the highest palette index a block can have");
        List<BlockOp> tall = new ArrayList<>();
        for (int y = -2_032; y < 2_032; y++) {
            tall.add(op(3, y, 3, 1));
        }
        assertExact(plan(tall, 2), "one run the height of the tallest world");
        Plan p = plan(tall, 2);
        assertTrue(PlanCodec.columns(p.ops()).length < 20, "and it is a single run: a few bytes for 4,064 blocks");
    }

    @Test
    void blocksScatteredAcrossTheIntRangeAreWrittenInVersionOneBecauseVersionTwoWouldBeBigger() {
        SplittableRandom rnd = new SplittableRandom(99);
        List<BlockOp> ops = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            ops.add(op(rnd.nextInt(), rnd.nextInt(), rnd.nextInt(), rnd.nextInt(30_000)));
        }
        Plan p = plan(ops, 30_000);
        assertTrue(PlanCodec.columns(p.ops()).length > 4 + 14 * ops.size(),
                "these blocks are bigger as columns than as version 1's 14 bytes each");
        byte[] payload = PlanCodec.payload(p);
        assertEquals(PlanCodec.V1, payload[4], "so the row is written in version 1: never bigger than it would have been");
        assertExact(p, "and it still comes back exactly");
        Plan near = plan(ops.stream().map(o -> op(o.x() & 0xFFF, o.y() & 0x1FF, o.z() & 0xFFF, o.state())).toList(),
                30_000);
        assertEquals(PlanCodec.VERSION, PlanCodec.payload(near)[4],
                "while the same count of unsorted blocks inside a 4096-wide area is written in version 2");
    }

    @Test
    void everyRowVersionOneWroteStillReadsAndComesBackTheSameInVersionTwo() {
        PlanCodec.Read golden = PlanCodec.decode(Base64.getDecoder().decode(PlanCodecTest.GOLDEN_TRIAL_GZIP));
        assertTrue(golden.ok(), "the version-1 golden blob still reads: " + golden.problem());
        assertEquals(PlanCodecTest.trialPlan(), golden.plan(), "as exactly the plan it was written from");
        List<V2Fixtures.Fixture> fixtures = V2Fixtures.all();
        assertEquals(6, fixtures.size(), "the six frozen algo-2 rows (three boats, three golf courses)");
        for (V2Fixtures.Fixture f : fixtures) {
            assertEquals(f.hash(), f.plan().hash(), f.name() + ": the version-1 row reads with its pinned hash");
            byte[] v2 = PlanCodec.encode(f.plan());
            PlanCodec.Read back = PlanCodec.decode(v2);
            assertTrue(back.ok(), f.name() + " written again in version 2 reads: " + back.problem());
            assertEquals(f.plan(), back.plan(), f.name() + ": the same plan, every block in the same order");
            assertEquals(f.hash(), back.plan().hash(), f.name() + ": the same layout hash");
            assertTrue(v2.length <= PlanCodec.encode(f.plan(), PlanCodec.V1).length,
                    f.name() + ": and never bigger stored (" + v2.length + " bytes)");
        }
    }

    // ---- the Mountain Run v2 ----------------------------------------------------------------------------

    @Test
    void aMountainRunSizedPlanIsStoredInAtMostSixTenthsOfAMegabyte() {
        Plan p = SyntheticMountain.plan(41);
        int n = p.ops().size();
        assertTrue(n >= 300_000 && n <= 400_000, "the synthetic mountain is Mountain Run v2-sized: " + n + " blocks");
        byte[] v1 = PlanCodec.encode(p, PlanCodec.V1);
        byte[] v2 = PlanCodec.encode(p);
        byte[] raw = PlanCodec.payload(p);
        String sizes = n + " blocks: version 1 " + v1.length + " bytes stored, version 2 " + v2.length
                + " stored (" + raw.length + " raw)";
        System.out.println("PlanCodec v2 on a synthetic Mountain Run: " + sizes);
        assertEquals(PlanCodec.VERSION, raw[4], "it is written in version 2: " + sizes);
        assertTrue(v2.length <= 600_000, "a Mountain Run v2 is stored in at most 0.6 MB: " + sizes);
        assertTrue(raw.length < PlanCodec.MAX_BYTES / 2, "and is far under the inflated cap: " + sizes);
        assertTrue(v2.length * 2 < v1.length, "at most half version 1's size: " + sizes);
        PlanCodec.Read back = PlanCodec.decode(v2);
        assertTrue(back.ok(), "it reads back: " + back.problem());
        assertEquals(p.ops(), back.plan().ops(), "every block, in order");
        assertEquals(p, back.plan(), "the whole plan");
        assertEquals(p.hash(), back.plan().hash(), "so the same hash");
    }

    @Test
    void theSameMountainInAnyOtherOrderIsStillExactOnlyLessCompact() {
        Plan sorted = SyntheticMountain.plan(41);
        List<BlockOp> shuffled = new ArrayList<>(sorted.ops());
        java.util.Collections.shuffle(shuffled, new java.util.Random(5));
        Plan p = plan(sorted, shuffled);
        assertEquals(sorted.hash(), p.hash(), "the order is free for the hash (F30)");
        byte[] stored = PlanCodec.encode(p);
        PlanCodec.Read back = PlanCodec.decode(stored);
        assertTrue(back.ok(), "a shuffled mountain reads back: " + back.problem());
        assertEquals(shuffled, back.plan().ops(), "in exactly its shuffled order: version 2 never sorts");
        assertTrue(stored.length > PlanCodec.encode(sorted).length,
                "and only sorting (the planner's (x, z, y) order) makes it compact: " + stored.length + " bytes");
    }

    // ---- the guards -------------------------------------------------------------------------------------

    @Test
    void cutShortOrCorruptedVersionTwoBytesFailCleanlyAndQuickly() {
        Plan p = randomPlan(new SplittableRandom(7), Shape.MOUNTAIN, 40, 600);
        byte[] payload = PlanCodec.payload(p, PlanCodec.VERSION);
        byte[] stored = PlanCodec.encode(p, PlanCodec.VERSION);
        int from = blocksAt(p);
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            for (int len = 0; len < payload.length; len++) {
                PlanCodec.Read r = PlanCodec.decodePayload(Arrays.copyOf(payload, len));
                assertFalse(r.ok(), "a payload cut to " + len + " of " + payload.length + " bytes is unreadable");
                assertNotNull(r.problem(), "and says why (" + len + ")");
            }
            for (int len = 0; len < stored.length; len++) {
                assertFalse(PlanCodec.decode(Arrays.copyOf(stored, len)).ok(), "a blob cut to " + len + " bytes");
            }
            int to = from + PlanCodec.columns(p.ops()).length;
            SplittableRandom rnd = new SplittableRandom(11);
            for (int i = 0; i < 3_000; i++) {
                byte[] bad = payload.clone();
                int flips = 1 + rnd.nextInt(3);
                for (int f = 0; f < flips; f++) {
                    bad[from + rnd.nextInt(to - from)] ^= (byte) (1 + rnd.nextInt(255));
                }
                PlanCodec.Read r = PlanCodec.decodePayload(bad);
                assertFalse(r.ok(), "a block section with " + flips + " bytes changed is never read (" + i + ")");
                assertNotNull(r.problem(), "and says why (" + i + ")");
            }
            for (int i = 0; i < 3_000; i++) {
                byte[] bad = payload.clone();
                bad[rnd.nextInt(payload.length)] ^= (byte) (1 + rnd.nextInt(255));
                PlanCodec.Read r = PlanCodec.decodePayload(bad);
                if (r.ok()) { // a byte the hash doesn't cover: a summary line, the work count, the course's name
                    assertEquals(p.hash(), r.plan().hash(), "a changed row that reads is still the same layout");
                    assertEquals(p.ops(), r.plan().ops(), "with the same blocks in the same order");
                }
            }
        }, "decoding junk never hangs");
    }

    @Test
    void craftedBlockSectionsAreRefusedBeforeAnythingIsMadeForThem() {
        Plan p = PlanCodecTest.trialPlan();
        assertCrafted(p, ints(PlanCodec.MAX_COUNT + 1), "a count past MAX_COUNT");
        assertCrafted(p, ints(-1), "a negative count");
        assertCrafted(p, cat(ints(10), varints(0, 0, 0)), "a column of no runs");
        assertCrafted(p, cat(ints(10), varints(0, 0, 1, 0, 0, 1)), "a run of no blocks");
        assertCrafted(p, cat(ints(10), varints(0, 0, 1, 0, 1L << 34, 1)), "a run longer than the blocks left");
        assertCrafted(p, cat(ints(10), varints(0, 0, 11, 0, 1, 1)), "a column of more runs than blocks left");
        assertCrafted(p, cat(ints(1), varints(0, 0, 1, 0, 1, 32_768)), "a palette index past a short");
        assertCrafted(p, cat(ints(1), varints((long) Integer.MAX_VALUE * 2 + 2, 0, 1, 0, 1, 0)), "an x past the int range");
        assertCrafted(p, cat(ints(2), varints(0, 0, 1, (long) Integer.MAX_VALUE * 2, 2, 0)),
                "a run whose top is past the int range");
        assertCrafted(p, cat(ints(1), new byte[]{(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0}),
                "a number longer than five bytes");
        assertCrafted(p, cat(ints(3), varints(0, 0, 1, 0, 1, 0)), "fewer blocks than the count");
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            // A million blocks in a dozen bytes: bounded by MAX_COUNT, made, then refused by the hash.
            byte[] million = cat(ints(PlanCodec.MAX_COUNT), varints(0, 0, 1, 0, PlanCodec.MAX_COUNT, 0));
            assertCrafted(p, million, "a million blocks that aren't the plan's");
        }, "even the biggest block section a row may claim is read and refused in moments");
    }

    @Test
    void aGzipBombIsRefusedAtTheInflatedCap() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            byte[] zeros = new byte[1 << 20];
            for (int i = 0; i < 9; i++) {
                gz.write(zeros);
            }
        }
        PlanCodec.Read r = PlanCodec.decode(out.toByteArray());
        assertFalse(r.ok(), "nine megabytes of zeros are never read as a plan");
        assertTrue(r.problem().contains("too big"), "they are refused at MAX_BYTES: " + r.problem());
    }

    // ---- helpers ----------------------------------------------------------------------------------------

    private enum Shape {
        /** Anywhere in a half, any order. */
        UNSORTED,
        /** In (x, z, y) column order, as the v4 planner emits. */
        SORTED,
        /** Columns with stacked runs and repeated spots, then shuffled in places. */
        MOUNTAIN,
        /** The same few spots again and again, with other blocks. */
        REPEATS,
        /** Coordinates anywhere in the int range, negative ones too. */
        WIDE
    }

    private static Plan randomPlan(SplittableRandom rnd, Shape shape, int palette, int count) {
        List<BlockOp> ops = new ArrayList<>(count);
        int bx = rnd.nextInt(-30_000_000, 30_000_000);
        int by = rnd.nextInt(-64, 200);
        int bz = rnd.nextInt(-30_000_000, 30_000_000);
        switch (shape) {
            case UNSORTED, SORTED -> {
                for (int i = 0; i < count; i++) {
                    ops.add(op(bx + rnd.nextInt(480), by + rnd.nextInt(176), bz + rnd.nextInt(640),
                            rnd.nextInt(palette)));
                }
                if (shape == Shape.SORTED) {
                    ops.sort(Comparator.comparingInt(BlockOp::x).thenComparingInt(BlockOp::z)
                            .thenComparingInt(BlockOp::y));
                }
            }
            case MOUNTAIN -> {
                int x = bx;
                int z = bz;
                while (ops.size() < count) {
                    int y = by + rnd.nextInt(40);
                    int runs = 1 + rnd.nextInt(4);
                    for (int r = 0; r < runs && ops.size() < count; r++) {
                        int state = rnd.nextInt(palette);
                        int len = 1 + rnd.nextInt(6);
                        for (int k = 0; k < len && ops.size() < count; k++) {
                            ops.add(op(x, y++, z, state));
                        }
                        y += rnd.nextInt(-2, 4); // a gap, the same spot again, or back down
                    }
                    z += rnd.nextInt(3) == 0 ? -rnd.nextInt(3) : 1 + rnd.nextInt(2);
                    if (rnd.nextInt(30) == 0) {
                        x += 1;
                        z = bz;
                    }
                }
            }
            case REPEATS -> {
                for (int i = 0; i < count; i++) {
                    ops.add(op(bx + rnd.nextInt(3), by + rnd.nextInt(3), bz + rnd.nextInt(3), rnd.nextInt(palette)));
                }
            }
            case WIDE -> {
                for (int i = 0; i < count; i++) {
                    ops.add(op(rnd.nextInt(), rnd.nextBoolean() ? rnd.nextInt() : rnd.nextInt(-64, 320), rnd.nextInt(),
                            rnd.nextInt(palette)));
                }
            }
            default -> throw new IllegalStateException();
        }
        return plan(ops, palette);
    }

    /** {@code PlanCodecTest.trialPlan()} with these blocks and a palette of {@code size} entries. */
    private static Plan plan(List<BlockOp> ops, int size) {
        Plan t = PlanCodecTest.trialPlan();
        List<String> palette = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            palette.add(i < t.palette().size() ? t.palette().get(i) : "minecraft:block_" + i);
        }
        return Plan.of(t.slot(), t.algo(), t.seed(), t.half(), palette, ops, t.signs(), t.keepClear(), t.course(),
                t.summary(), t.work());
    }

    private static Plan plan(Plan like, List<BlockOp> ops) {
        return Plan.of(like.slot(), like.algo(), like.seed(), like.half(), like.palette(), ops, like.signs(),
                like.keepClear(), like.course(), like.summary(), like.work());
    }

    private static void assertExact(Plan p, String what) {
        for (int version : new int[]{PlanCodec.V1, PlanCodec.VERSION}) {
            byte[] payload = PlanCodec.payload(p, version);
            PlanCodec.Read read = PlanCodec.decodePayload(payload);
            assertTrue(read.ok(), what + " in version " + version + " reads back: " + read.problem());
            assertEquals(p.ops(), read.plan().ops(), what + " in version " + version
                    + ": the same blocks in the same order");
            assertEquals(p, read.plan(), what + " in version " + version + ": the whole plan");
            assertEquals(p.hash(), read.plan().hash(), what + " in version " + version + ": the same hash");
            assertArrayEquals(payload, PlanCodec.payload(read.plan(), version),
                    what + " in version " + version + ": written again, byte for byte");
        }
        PlanCodec.Read stored = PlanCodec.decode(PlanCodec.encode(p));
        assertTrue(stored.ok(), what + " as stored reads back: " + stored.problem());
        assertEquals(p, stored.plan(), what + " as stored: the whole plan");
    }

    /** Where the block section starts in {@code p}'s payload. */
    private static int blocksAt(Plan p) {
        int n = 4 + 1 + 2 + p.slot().length() + 4 + 8 + 24 + 4;
        for (String s : p.palette()) {
            n += 2 + s.length();
        }
        return n;
    }

    /** {@code p}'s version-2 payload with its block section replaced by {@code blocks}: refused, quickly. */
    private static void assertCrafted(Plan p, byte[] blocks, String what) {
        byte[] payload = PlanCodec.payload(p, PlanCodec.VERSION);
        int at = blocksAt(p);
        int end = at + PlanCodec.columns(p.ops()).length;
        byte[] crafted = cat(Arrays.copyOf(payload, at), blocks, Arrays.copyOfRange(payload, end, payload.length));
        PlanCodec.Read r = PlanCodec.decodePayload(crafted);
        assertFalse(r.ok(), what + " is unreadable");
        assertNull(r.plan(), what + " gives no plan");
        assertNotNull(r.problem(), what + " says why");
    }

    private static byte[] ints(int... values) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream o = new DataOutputStream(bytes)) {
            for (int v : values) {
                o.writeInt(v);
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        return bytes.toByteArray();
    }

    /** Raw unsigned varints (the zigzag ones given already zigzagged). */
    private static byte[] varints(long... values) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (long v : values) {
            while ((v & ~0x7FL) != 0) {
                out.write((int) ((v & 0x7F) | 0x80));
                v >>>= 7;
            }
            out.write((int) v);
        }
        return out.toByteArray();
    }

    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] b : parts) {
            out.writeBytes(b);
        }
        return out.toByteArray();
    }

    private static BlockOp op(int x, int y, int z, int state) {
        return new BlockOp(x, y, z, (short) state);
    }

    private static String sha(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    /** The SHA-256 of the version-2 payloads of {@code PlanCodecTest}'s trial and golf plans. */
    static final String GOLDEN_TRIAL_V2_SHA = "b84e78e148815800d01114cfe9344f8c0a09ee50ba83fd7869c369a31911aa7a";
    static final String GOLDEN_GOLF_V2_SHA = "0b71dabe7c33b2546f4ba2a8adb3a4ce1701793b983c576a90e5e09aea73c277";
    /** {@code PlanCodecTest.trialPlan()} as version 2 stored it (base64 of the gzip blob). */
    static final String GOLDEN_TRIAL_V2_GZIP = "H4sIAAAAAAAA//Nw9g1gYhBKK0otzogvSCzKzi8tis9ILEphYGBgtNeaeKBWLnQDA4MQkMuwgIFBAEgJ2QOJ80A2iGZm"
            + "kMjNzEtNLkpMK7Eqz8gsSY1Pzs9LLkotSWWQRcjkZKZnlMQn5ZQiSYsgpNPzc1KAsvnJ2SAj2zzaHBivMDEyCAkxMjEy"
            + "iogwMjIygdwDtFscSK8G2i3OIIfQnp+YHV+cmZ4XXZRfkliSmZ9naxLLyMDu5unnGeyhCNXJBNUJpIVYgex1QDYrWI7d"
            + "WCGrNLegmAEMWC4x8KQmWViaJhoYpZmnpDFiDR12KI+BxwPIVQiA8lggQcfosIm5AWSYQ2oC2FCHDRC+kwkDDADV8EDV"
            + "NEDVQPmMM0FgFlCBGJohUD4HzIRUKAOIeeAsIJhSAABi36n82AEAAA==";
}
