package com.dierks.homecraft.games.gen.api;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The generators' random numbers (GEN-SPEC §4.0): SplitMix64, written out here and pinned by golden
 * vectors, so a seed makes the same course on every host, JVM and version, for good.
 *
 * <p>Why not {@code java.util.Random} or {@code SplittableRandom}: their algorithms are the JDK's to
 * change, and a course is a promise — the same seed must give the same blocks tomorrow and after a
 * plugin update, or the boot check can't re-derive the live layout. For the same reason the
 * generators never use {@code Math.random} or a {@code HashMap}'s iteration order.
 *
 * <p><b>Forks.</b> {@link #fork} gives an independent stream per jump, leg or hole
 * ({@code "hole:3:try:2"}), derived from this stream's seed and the label only — never from how
 * many numbers were drawn — so re-rolling hole 3 never changes hole 4.
 *
 * <p>Not thread-safe; a plan runs on one thread.
 */
public final class GenRandom {

    private static final long GOLDEN = 0x9E3779B97F4A7C15L;
    private static final long FNV_OFFSET = 0xCBF29CE484222325L;
    private static final long FNV_PRIME = 0x100000001B3L;

    /** The seed this stream started from (what {@link #fork} derives from). */
    private final long seed;
    private long state;

    public GenRandom(long seed) {
        this.seed = seed;
        this.state = seed;
    }

    /** The seed this stream started from. */
    public long seed() {
        return seed;
    }

    /** The next 64 random bits (SplitMix64). */
    public long nextLong() {
        state += GOLDEN;
        return mix(state);
    }

    /**
     * A uniform whole number in {@code [0, bound)}, with no bias (Lemire's multiply-and-reject over
     * the next 32 random bits).
     */
    public int nextInt(int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be positive: " + bound);
        }
        long b = bound;
        long m = (nextLong() >>> 32) * b;
        long low = m & 0xFFFFFFFFL;
        if (low < b) {
            long threshold = (0x1_0000_0000L - b) % b;
            while (low < threshold) {
                m = (nextLong() >>> 32) * b;
                low = m & 0xFFFFFFFFL;
            }
        }
        return (int) (m >>> 32);
    }

    /** A uniform whole number from {@code lo} to {@code hi}, both included. */
    public int nextInt(int lo, int hi) {
        if (hi < lo) {
            throw new IllegalArgumentException("empty range: " + lo + ".." + hi);
        }
        return lo + nextInt(hi - lo + 1);
    }

    /** A uniform number in {@code [0, 1)}, 53 random bits. */
    public double nextDouble() {
        return (nextLong() >>> 11) * 0x1.0p-53;
    }

    /** A uniform number in {@code [lo, hi)}. */
    public double nextDouble(double lo, double hi) {
        return lo + (hi - lo) * nextDouble();
    }

    public boolean nextBoolean() {
        return nextLong() < 0;
    }

    /** True with probability {@code p}. */
    public boolean chance(double p) {
        return nextDouble() < p;
    }

    /** One of {@code options}, uniformly. */
    public <T> T pick(List<T> options) {
        if (options == null || options.isEmpty()) {
            throw new IllegalArgumentException("nothing to pick from");
        }
        return options.get(nextInt(options.size()));
    }

    /**
     * An index drawn by weight: {@code weights[i] / sum}. Zero weights are never drawn; at least one
     * weight must be positive, and none negative.
     */
    public int weighted(double... weights) {
        double sum = 0;
        for (double w : weights) {
            if (!(w >= 0) || Double.isInfinite(w)) {
                throw new IllegalArgumentException("a weight is a finite number of at least 0: " + w);
            }
            sum += w;
        }
        if (sum <= 0) {
            throw new IllegalArgumentException("at least one weight must be positive");
        }
        double r = nextDouble() * sum;
        int last = -1;
        for (int i = 0; i < weights.length; i++) {
            if (weights[i] <= 0) {
                continue;
            }
            last = i;
            if (r < weights[i]) {
                return i;
            }
            r -= weights[i];
        }
        return last; // rounding at the very top of the range
    }

    /** An independent stream named {@code label}: the same for the same seed and label, whatever was drawn. */
    public GenRandom fork(String label) {
        return new GenRandom(mix(seed ^ fnv1a64(label)));
    }

    /** FNV-1a, 64-bit, over the label's UTF-8 bytes. */
    public static long fnv1a64(String label) {
        long h = FNV_OFFSET;
        for (byte b : (label == null ? "" : label).getBytes(StandardCharsets.UTF_8)) {
            h ^= b & 0xFF;
            h *= FNV_PRIME;
        }
        return h;
    }

    /** SplitMix64's output function. */
    static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
