package com.dierks.homecraft.market.sim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The live market's counter-based randomness.
 *
 * <p>Pins what the anti-exploit story rests on: a draw is a pure function of
 * {@code (seed, stream, counter)}, so a replayed tick draws the same numbers and a restart
 * cannot re-roll anything; and the numbers are good enough that the drift and the planner
 * behave as designed. The FNV-1a and SplitMix64 vectors are the published reference values
 * (FNV of "" and "a"; SplitMix64 seeded with 0 yields {@code mix(G)}, {@code mix(2G)}), plus
 * values computed independently of this code, so a refactor that changes a single bit — and
 * with it every live server's future — fails here.
 */
class SimRandomTest {

    private static final long SEED = 0x0123456789abcdefL;
    private static final long GOLDEN = 0x9E3779B97F4A7C15L;
    private static final int N = 200_000;

    @Test
    void fnvAndSplitMixMatchTheirReferenceVectors() {
        assertEquals(0xcbf29ce484222325L, SimRandom.key(""), "FNV-1a 64 offset basis");
        assertEquals(0xaf63dc4c8601ec8cL, SimRandom.key("a"));
        assertEquals(0xd87f381a52e9b004L, SimRandom.key("drift|diamond"));
        assertEquals(0x3dff7937faa518cbL, SimRandom.key("drift|wheat"));
        assertEquals(SimRandom.key(""), SimRandom.key(null));

        assertEquals(0xe220a8397b1dcdafL, SimRandom.mix(GOLDEN), "SplitMix64(0), first output");
        assertEquals(0x6e789e6aa1b965f4L, SimRandom.mix(2 * GOLDEN), "SplitMix64(0), second output");
        assertEquals(0L, SimRandom.mix(0L));
        assertEquals(0x5692161d100b05e5L, SimRandom.mix(1L));
        assertEquals(0xb4d055fcf2cbbd7bL, SimRandom.mix(-1L));
    }

    @Test
    void drawsArePinnedToTheSpecFormula() {
        SimRandom r = new SimRandom(SEED);
        // u(stream, c) = (mix(seed ^ mix(key(stream) + c * G)) >>> 11) * 2^-53
        long c = 29_000_000L;
        long bits = SimRandom.mix(SEED ^ SimRandom.mix(SimRandom.key("drift|diamond") + c * GOLDEN));
        assertEquals(bits, r.bits(SimRandom.key("drift|diamond"), c));
        assertEquals((bits >>> 11) * 0x1.0p-53, r.uniform("drift|diamond", c));
        assertEquals(0.2192278754969369, r.uniform("drift|diamond", c), "computed independently");
        assertEquals(0.8519520927531694, r.uniform("news.kind", 0));
        assertEquals(-0.34806255061559793, r.gaussian("drift|diamond", c), 1e-12,
                "Box-Muller on u(stream, 2c) and u(stream, 2c + 1)");
        assertEquals(r.uniform("x", 7), r.uniform(SimRandom.key("x"), 7));
        assertEquals(r.gaussian("x", 7), r.gaussian(SimRandom.key("x"), 7));
    }

    @Test
    void sameSeedStreamAndCounterGiveTheSameBits() {
        SimRandom a = new SimRandom(SEED);
        SimRandom b = new SimRandom(SEED);
        SimRandom other = new SimRandom(SEED + 1);
        int differ = 0;
        for (long c = 0; c < 10_000; c++) {
            assertEquals(a.uniform("hot.item", c), b.uniform("hot.item", c));
            assertEquals(a.gaussian("drift|oak_log", c), b.gaussian("drift|oak_log", c));
            if (a.uniform("hot.item", c) != other.uniform("hot.item", c)) {
                differ++;
            }
        }
        assertEquals(10_000, differ, "another seed is another market");
        assertNotEquals(a.uniform("hot.item", 5), a.uniform("deal.item", 5), "streams are independent");
        assertNotEquals(a.uniform("hot.item", 5), a.uniform("hot.item", 6));
    }

    @Test
    void uniformsLieInTheHalfOpenUnitInterval() {
        SimRandom r = new SimRandom(SEED);
        double min = 1;
        double max = 0;
        double sum = 0;
        for (long c = 0; c < N; c++) {
            double u = r.uniform("u", c);
            assertTrue(u >= 0.0 && u < 1.0, "u = " + u);
            min = Math.min(min, u);
            max = Math.max(max, u);
            sum += u;
        }
        assertTrue(min < 1e-4 && max > 1 - 1e-4, "covers the interval");
        assertEquals(0.5, sum / N, 0.005);
        double v = r.between(10, 15, "b", 3);
        assertTrue(v >= 10 && v < 15, "between stays inside");
        assertEquals(10 + 5 * r.uniform("b", 3), v);
    }

    @Test
    void gaussiansHaveMeanZeroAndSdOne() {
        SimRandom r = new SimRandom(SEED);
        double sum = 0;
        double sq = 0;
        for (long c = 0; c < N; c++) {
            double z = r.gaussian("drift|test", c);
            assertTrue(Double.isFinite(z));
            sum += z;
            sq += z * z;
        }
        double mean = sum / N;
        double sd = Math.sqrt(sq / N - mean * mean);
        assertTrue(Math.abs(mean) < 0.01, "mean " + mean);
        assertEquals(1.0, sd, 0.01, "sd " + sd);
    }

    @Test
    void drawsAreUncorrelatedAcrossStreamsAndCounters() {
        SimRandom r = new SimRandom(SEED);
        double[] a = new double[N];
        double[] b = new double[N];
        double[] z = new double[N];
        double[] y = new double[N];
        for (int c = 0; c < N; c++) {
            a[c] = r.uniform("hot.item", c);
            b[c] = r.uniform("deal.item", c);
            z[c] = r.gaussian("drift|iron_ingot", c);
            y[c] = r.gaussian("drift|diamond", c);
        }
        assertUncorrelated(a, 0, b, 0, "two streams, same counter");
        assertUncorrelated(a, 0, a, 1, "one stream, next counter");
        assertUncorrelated(z, 0, z, 1, "gaussian, next counter");
        assertUncorrelated(z, 0, y, 0, "two drift streams, same tick");
        assertUncorrelated(a, 0, z, 0, "uniform vs gaussian");
    }

    private static void assertUncorrelated(double[] x, int dx, double[] y, int dy, String what) {
        int n = Math.min(x.length - dx, y.length - dy);
        double mx = 0;
        double my = 0;
        for (int i = 0; i < n; i++) {
            mx += x[i + dx];
            my += y[i + dy];
        }
        mx /= n;
        my /= n;
        double sxy = 0;
        double sxx = 0;
        double syy = 0;
        for (int i = 0; i < n; i++) {
            double p = x[i + dx] - mx;
            double q = y[i + dy] - my;
            sxy += p * q;
            sxx += p * p;
            syy += q * q;
        }
        double corr = sxy / Math.sqrt(sxx * syy);
        assertTrue(Math.abs(corr) < 0.01, what + ": corr " + corr);
    }

    @Test
    void pickWeightedFollowsTheWeights() {
        SimRandom r = new SimRandom(SEED);
        double[] w = {1, 2, 0, 3, -5, Double.NaN, 4};
        int[] hits = new int[w.length];
        for (long c = 0; c < N; c++) {
            hits[SimRandom.pickWeighted(w, r.uniform("pick", c))]++;
        }
        double[] expected = {0.1, 0.2, 0, 0.3, 0, 0, 0.4};
        for (int i = 0; i < w.length; i++) {
            assertEquals(expected[i], hits[i] / (double) N, 0.01, "index " + i);
        }
        assertEquals(0, hits[2] + hits[4] + hits[5], "zero, negative and NaN weights are never picked");

        assertEquals(0, SimRandom.pickWeighted(w, 0.0));
        assertEquals(6, SimRandom.pickWeighted(w, Math.nextDown(1.0)));
        assertEquals(6, SimRandom.pickWeighted(w, 1.0), "u at the very top still picks a real index");
        assertEquals(-1, SimRandom.pickWeighted(new double[]{0, -1}, 0.5), "nothing to pick");
        assertEquals(-1, SimRandom.pickWeighted(new double[0], 0.5));
        assertEquals(-1, SimRandom.pickWeighted(null, 0.5));
        assertEquals(1, SimRandom.pickWeighted(new double[]{0, 7}, 0.0));
    }

    @Test
    void exponentialIsFiniteNonNegativeWithTheRightMean() {
        SimRandom r = new SimRandom(SEED);
        double sum = 0;
        for (long c = 0; c < N; c++) {
            double x = r.exponential(14.0, "news.gap", c);
            assertTrue(x >= 0 && Double.isFinite(x));
            sum += x;
        }
        assertEquals(14.0, sum / N, 0.2);
    }

    @Test
    void theSeedIsNeverPrinted() {
        String s = new SimRandom(SEED).toString();
        assertFalse(s.contains(Long.toHexString(SEED)));
        assertFalse(s.contains(Long.toString(SEED)));
    }
}
