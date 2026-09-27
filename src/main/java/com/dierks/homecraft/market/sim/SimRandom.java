package com.dierks.homecraft.market.sim;

import java.nio.charset.StandardCharsets;

/**
 * The live market's randomness: a <b>counter-based</b> generator. Every draw is a pure
 * function of {@code (seed, stream, counter)} — there is no hidden state to advance — so a
 * tick replayed after a restart draws exactly the numbers it drew the first time, and a
 * restart can never "re-roll" an outcome.
 *
 * <pre>u(stream, c) = (mix(seed ^ mix(key(stream) + c * 0x9E3779B97F4A7C15)) &gt;&gt;&gt; 11) * 2^-53</pre>
 *
 * <ul>
 *   <li>{@link #mix} is the SplitMix64 finaliser; {@link #key} is FNV-1a 64 over UTF-8.</li>
 *   <li>{@link #gaussian} is Box–Muller on {@code u(stream, 2c)} (floored at 2^-53) and
 *       {@code u(stream, 2c + 1)}, so a stream used for gaussians must not also be used for
 *       uniforms.</li>
 *   <li>Tick draws use {@code c = boundary / 60000} (the minute index, stable if
 *       {@code tick_minutes} changes); admin draws use streams named {@code admin.*}.</li>
 * </ul>
 *
 * <p>The seed is 64 bits from {@code SecureRandom}, stored only in the database. This class
 * never exposes it and {@link #toString()} never prints it.
 *
 * <p>Plain Java, no Bukkit: unit-tested without a server ({@code SimRandomTest}).
 */
public final class SimRandom {

    /** 2^64 / golden ratio: the SplitMix64 increment. */
    private static final long GOLDEN = 0x9E3779B97F4A7C15L;
    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;
    private static final double TWO_POW_M53 = 0x1.0p-53;

    private final long seed;

    public SimRandom(long seed) {
        this.seed = seed;
    }

    /** FNV-1a 64 of the stream name's UTF-8 bytes. */
    public static long key(String stream) {
        long h = FNV_OFFSET;
        byte[] bytes = (stream == null ? "" : stream).getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            h ^= (b & 0xffL);
            h *= FNV_PRIME;
        }
        return h;
    }

    /** The SplitMix64 finaliser: a bijective 64-bit mix. */
    public static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    /** The raw 64 bits for {@code (stream key, counter)}. */
    public long bits(long streamKey, long counter) {
        return mix(seed ^ mix(streamKey + counter * GOLDEN));
    }

    /** A uniform double in {@code [0, 1)} for a precomputed stream key. */
    public double uniform(long streamKey, long counter) {
        return (bits(streamKey, counter) >>> 11) * TWO_POW_M53;
    }

    /** A uniform double in {@code [0, 1)}: the spec's {@code u(stream, c)}. */
    public double uniform(String stream, long counter) {
        return uniform(key(stream), counter);
    }

    /** A standard normal (mean 0, sd 1) for a precomputed stream key. */
    public double gaussian(long streamKey, long counter) {
        double u1 = Math.max(uniform(streamKey, 2 * counter), TWO_POW_M53);
        double u2 = uniform(streamKey, 2 * counter + 1);
        return Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2);
    }

    /** A standard normal (mean 0, sd 1): Box–Muller on {@code u(stream, 2c)}, {@code u(stream, 2c+1)}. */
    public double gaussian(String stream, long counter) {
        return gaussian(key(stream), counter);
    }

    /** Uniform in {@code [lo, hi)}: {@code lo + (hi - lo) * u(stream, c)}. */
    public double between(double lo, double hi, String stream, long counter) {
        return lo + (hi - lo) * uniform(stream, counter);
    }

    /** Exponential with the given mean: {@code -mean * ln(1 - u)}, always finite and {@code >= 0}. */
    public double exponential(double mean, String stream, long counter) {
        return -mean * Math.log(1.0 - uniform(stream, counter));
    }

    /**
     * Pick an index with probability proportional to its weight, using one uniform {@code u}
     * in {@code [0, 1)}. Zero, negative, NaN and infinite weights are never picked. Returns -1 when no
     * weight is positive. Callers pass ids sorted ascending so the same {@code u} picks the
     * same item on every server.
     */
    public static int pickWeighted(double[] w, double u) {
        if (w == null || w.length == 0) {
            return -1;
        }
        double total = 0.0;
        int last = -1;
        for (int i = 0; i < w.length; i++) {
            if (w[i] > 0.0 && !Double.isInfinite(w[i])) {
                total += w[i];
                last = i;
            }
        }
        if (last < 0 || !(total > 0.0)) {
            return -1;
        }
        double target = Math.max(0.0, Math.min(1.0, Double.isNaN(u) ? 0.0 : u)) * total;
        double cum = 0.0;
        for (int i = 0; i < w.length; i++) {
            if (w[i] > 0.0 && !Double.isInfinite(w[i])) {
                cum += w[i];
                if (target < cum) {
                    return i;
                }
            }
        }
        return last; // u rounded onto the very top of the total
    }

    /** Deliberately says nothing about the seed. */
    @Override
    public String toString() {
        return "SimRandom[seed hidden]";
    }
}
