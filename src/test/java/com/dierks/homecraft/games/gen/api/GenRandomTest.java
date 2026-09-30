package com.dierks.homecraft.games.gen.api;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The generators' random numbers (GEN-SPEC §4.0).
 *
 * <p>Pinned here: the stream is SplitMix64 bit for bit (the published reference outputs for seeds
 * 0 and 1234567, and golden values of our own that an independent Python port also gives), so a
 * seed makes the same course forever; FNV-1a-64 matches its reference; {@code nextInt} stays in
 * range and is unbiased, for small and huge bounds; and a fork depends only on the stream's seed
 * and its label — never on what was drawn — so re-rolling one hole never changes another.
 */
class GenRandomTest {

    @Test
    void theStreamIsSplitMix64AsPublished() {
        GenRandom zero = new GenRandom(0);
        assertEquals(0xE220A8397B1DCDAFL, zero.nextLong(), "SplitMix64's first output for seed 0");
        assertEquals(0x6E789E6AA1B965F4L, zero.nextLong(), "its second");
        assertEquals(0x06C45D188009454FL, zero.nextLong(), "its third");
        GenRandom r = new GenRandom(1234567);
        long[] expected = {Long.parseUnsignedLong("6457827717110365317"),
                Long.parseUnsignedLong("3203168211198807973"), Long.parseUnsignedLong("9817491932198370423"),
                Long.parseUnsignedLong("4593380528125082431"), Long.parseUnsignedLong("16408922859458223821")};
        long[] got = new long[5];
        for (int i = 0; i < 5; i++) {
            got[i] = r.nextLong();
        }
        assertArrayEquals(expected, got, "the reference vector for seed 1234567");
    }

    @Test
    void goldenValuesOfOurOwnNeverChange() {
        GenRandom r = new GenRandom(42);
        int[] ints = new int[12];
        for (int i = 0; i < ints.length; i++) {
            ints[i] = r.nextInt(10);
        }
        assertArrayEquals(new int[]{7, 1, 2, 3, 0, 8, 2, 8, 3, 6, 2, 4}, ints,
                "nextInt(10) from seed 42, as a Python port of the same algorithm gives it");
        GenRandom big = new GenRandom(42);
        assertEquals(741564883, big.nextInt(1_000_000_007), "a large bound, from the same port");
        assertEquals(159910393, big.nextInt(1_000_000_007), "and the next");
        GenRandom fork = new GenRandom(42).fork("hole:3:try:2");
        assertEquals(6355922777757309523L, fork.seed(), "a fork's seed is mix(seed ^ fnv1a64(label))");
        assertEquals(-928557754634820998L, fork.nextLong(), "and its stream is pinned");
        assertEquals(-5531637617633733323L, fork.nextLong(), "value two");
        GenRandom minusOne = new GenRandom(-1);
        assertEquals(-1956407806741107680L, minusOne.nextLong(), "a negative seed works like any other");
    }

    @Test
    void fnvMatchesItsReference() {
        assertEquals(0xCBF29CE484222325L, GenRandom.fnv1a64(""), "the offset basis for nothing");
        assertEquals(0xAF63DC4C8601EC8CL, GenRandom.fnv1a64("a"), "FNV-1a-64 of 'a'");
        assertEquals(0x85944171F73967E8L, GenRandom.fnv1a64("foobar"), "FNV-1a-64 of 'foobar'");
        assertEquals(GenRandom.fnv1a64(""), GenRandom.fnv1a64(null), "no label is the empty label");
    }

    @Test
    void nextIntStaysInRangeAndReachesEveryValue() {
        GenRandom r = new GenRandom(7);
        for (int bound : new int[]{1, 2, 3, 7, 10, 100}) {
            boolean[] seen = new boolean[bound];
            for (int i = 0; i < 20 * bound + 200; i++) {
                int v = r.nextInt(bound);
                assertTrue(v >= 0 && v < bound, v + " must be in [0, " + bound + ")");
                seen[v] = true;
            }
            for (int v = 0; v < bound; v++) {
                assertTrue(seen[v], "every value below " + bound + " comes up, " + v + " included");
            }
        }
        for (int i = 0; i < 1000; i++) {
            int v = r.nextInt(-3, 3);
            assertTrue(v >= -3 && v <= 3, "a range includes both ends and nothing outside: " + v);
        }
        assertThrows(IllegalArgumentException.class, () -> r.nextInt(0), "an empty bound is a bug");
        assertThrows(IllegalArgumentException.class, () -> r.nextInt(3, 2), "so is an empty range");
    }

    @Test
    void nextIntIsUnbiasedForSmallBounds() {
        GenRandom r = new GenRandom(2026);
        int bound = 6;
        int n = 60_000;
        int[] counts = new int[bound];
        for (int i = 0; i < n; i++) {
            counts[r.nextInt(bound)]++;
        }
        double expected = (double) n / bound;
        double chi = 0;
        for (int c : counts) {
            chi += (c - expected) * (c - expected) / expected;
        }
        // 5 degrees of freedom: 20.5 is the 0.1% tail.
        assertTrue(chi < 20.5, "six faces come up evenly (chi-squared " + chi + ")");
    }

    @Test
    void nextIntIsUnbiasedForHugeBounds() {
        // 1.5 billion: a plain remainder of 32 random bits would land in the lower half 52% of the time.
        GenRandom r = new GenRandom(99);
        int bound = 1_500_000_000;
        int n = 200_000;
        int low = 0;
        for (int i = 0; i < n; i++) {
            if (r.nextInt(bound) < bound / 2) {
                low++;
            }
        }
        double share = (double) low / n;
        assertTrue(Math.abs(share - 0.5) < 0.005, "each half of a huge range gets half the draws: " + share);
    }

    @Test
    void doublesAndChoicesBehave() {
        GenRandom r = new GenRandom(5);
        double sum = 0;
        for (int i = 0; i < 10_000; i++) {
            double d = r.nextDouble();
            assertTrue(d >= 0 && d < 1, "a double is in [0, 1): " + d);
            sum += d;
        }
        assertTrue(Math.abs(sum / 10_000 - 0.5) < 0.02, "and averages a half");
        int[] hits = new int[3];
        for (int i = 0; i < 10_000; i++) {
            hits[r.weighted(0.6, 0, 0.4)]++;
        }
        assertEquals(0, hits[1], "a zero weight is never drawn");
        assertTrue(Math.abs(hits[0] / 10_000.0 - 0.6) < 0.03, "a 0.6 weight is drawn about 60% of the time");
        assertThrows(IllegalArgumentException.class, () -> r.weighted(0, 0), "all-zero weights are a bug");
        assertThrows(IllegalArgumentException.class, () -> r.weighted(1, -1), "a negative weight is a bug");
        Set<String> picked = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            picked.add(r.pick(List.of("a", "b", "c")));
        }
        assertEquals(Set.of("a", "b", "c"), picked, "pick reaches every option");
    }

    @Test
    void theSameSeedGivesTheSameStream() {
        GenRandom a = new GenRandom(123);
        GenRandom b = new GenRandom(123);
        for (int i = 0; i < 1000; i++) {
            assertEquals(a.nextLong(), b.nextLong(), "draw " + i + " is the same for the same seed");
        }
    }

    @Test
    void aForkDependsOnlyOnTheSeedAndItsLabel() {
        GenRandom parent = new GenRandom(777);
        long first = parent.fork("hole:4").nextLong();
        for (int i = 0; i < 50; i++) {
            parent.nextLong();
        }
        parent.fork("hole:3:try:1").nextLong(); // re-rolling hole 3...
        parent.fork("hole:3:try:2").nextLong();
        assertEquals(first, parent.fork("hole:4").nextLong(), "...never changes hole 4, whatever was drawn");
        assertEquals(new GenRandom(777).fork("x").seed(), new GenRandom(777).fork("x").seed(),
                "the same label gives the same stream");
    }

    @Test
    void forksWithDifferentLabelsAreIndependent() {
        GenRandom root = new GenRandom(31337);
        Set<Long> seeds = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            seeds.add(root.fork("jump:" + i).seed());
        }
        assertEquals(1000, seeds.size(), "a thousand labels give a thousand different streams");
        GenRandom a = root.fork("leg:1");
        GenRandom b = root.fork("leg:2");
        int same = 0;
        int agree = 0;
        for (int i = 0; i < 10_000; i++) {
            long x = a.nextLong();
            long y = b.nextLong();
            if (x == y) {
                same++;
            }
            if ((x < 0) == (y < 0)) {
                agree++;
            }
        }
        assertEquals(0, same, "two forks never draw the same 64 bits");
        assertTrue(Math.abs(agree / 10_000.0 - 0.5) < 0.03, "and their bits don't move together: " + agree);
        assertNotEquals(root.fork("a").fork("b").seed(), root.fork("b").fork("a").seed(),
                "a fork of a fork depends on the order of the labels");
    }
}
