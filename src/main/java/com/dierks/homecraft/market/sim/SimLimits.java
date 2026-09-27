package com.dierks.homecraft.market.sim;

/**
 * The live market's hard limits, locked in code. Config can make the market calmer, never
 * wilder: every configured value is squeezed into these ranges before it is used, and the
 * total multiplier is clamped to {@link #MIN_MULTIPLIER}..{@link #MAX_MULTIPLIER} whatever the
 * parts add up to.
 *
 * <p>All methods take and return <b>fractions</b> ({@code 0.25} = 25%), never whole percents.
 * NaN is treated as "as calm as possible" (0), except in {@link #multiplierLo} and
 * {@link #multiplierHi}, where a NaN band falls back to the hard band itself.
 *
 * <p>Plain Java, no Bukkit: unit-tested without a server ({@code SimLimitsTest}).
 */
public final class SimLimits {

    /** The whole mood never takes a price below 0.75x its balanced price. */
    public static final double MIN_MULTIPLIER = 0.75;
    /** The whole mood never takes a price above 1.25x its balanced price. */
    public static final double MAX_MULTIPLIER = 1.25;
    /** The quiet wander alone: {@code |d| <= 8%}. */
    public static final double DRIFT_MAX = 0.08;
    /** HOT and DEAL strength at full strength: at most 15%. */
    public static final double STORY_MAX = 0.15;
    /** NEWS FLASH (UP/DOWN) jump size: at most 25%. */
    public static final double NEWS_MAX = 0.25;
    /** Seasons + real-world together stay under this share of the buy/sell spread. */
    public static final double PREDICTABLE_SPREAD_SHARE = 0.45;
    /**
     * Seasons + real-world together never pass 4.5%, whatever the spread. With the shipped
     * 10% spread this equals {@code 0.45 x spread}; on a wider spread it still holds the line.
     */
    public static final double PREDICTABLE_MAX = 0.045;
    /** Per-item {@code volatility} override: 0 (no drift) .. 1.5. */
    public static final double VOLATILITY_MAX = 1.5;
    /** {@code tick_minutes} range. */
    public static final int MIN_TICK_MINUTES = 1;
    public static final int MAX_TICK_MINUTES = 60;

    private SimLimits() {
    }

    /**
     * The lowest multiplier config allows: {@code max(0.75, 1 - maxDownFrac)}. A negative
     * fraction gives 1.0 (never below the balanced price); NaN gives the hard 0.75.
     */
    public static double multiplierLo(double maxDownFrac) {
        if (Double.isNaN(maxDownFrac)) {
            return MIN_MULTIPLIER;
        }
        return Math.max(MIN_MULTIPLIER, 1.0 - clamp(maxDownFrac, 0.0, 1.0));
    }

    /**
     * The highest multiplier config allows: {@code min(1.25, 1 + maxUpFrac)}. A negative
     * fraction gives 1.0 (never above the balanced price); NaN gives the hard 1.25.
     */
    public static double multiplierHi(double maxUpFrac) {
        if (Double.isNaN(maxUpFrac)) {
            return MAX_MULTIPLIER;
        }
        return Math.min(MAX_MULTIPLIER, 1.0 + clamp(maxUpFrac, 0.0, 1.0));
    }

    /**
     * Clamp a raw multiplier into {@code [lo, hi]}, where the band itself is first squeezed
     * into the hard {@code [0.75, 1.25]}. NaN gives exactly 1.0.
     */
    public static double clampMultiplier(double raw, double lo, double hi) {
        if (Double.isNaN(raw)) {
            return 1.0;
        }
        double l = Double.isNaN(lo) ? MIN_MULTIPLIER : Math.max(MIN_MULTIPLIER, Math.min(1.0, lo));
        double h = Double.isNaN(hi) ? MAX_MULTIPLIER : Math.min(MAX_MULTIPLIER, Math.max(1.0, hi));
        return Math.max(l, Math.min(h, raw));
    }

    /** Clamp a raw multiplier into the hard {@code [0.75, 1.25]}. NaN gives exactly 1.0. */
    public static double clampMultiplier(double raw) {
        return clampMultiplier(raw, MIN_MULTIPLIER, MAX_MULTIPLIER);
    }

    /**
     * The cap on the predictable layers (season + real):
     * {@code min(configuredFrac, 0.45 x spread, 0.045)}, never negative. A spread of 0 gives 0 —
     * with no spread to hide behind, a known-in-advance move would be free money.
     */
    public static double predictableCap(double configuredFrac, double spread) {
        double bySpread = PREDICTABLE_SPREAD_SHARE * nonNegative(spread);
        return Math.min(PREDICTABLE_MAX, Math.min(nonNegative(configuredFrac), bySpread));
    }

    /** A HOT/DEAL strength (magnitude) squeezed into {@code [0, 0.15]}. */
    public static double clampStory(double frac) {
        return clamp(frac, 0.0, STORY_MAX);
    }

    /** A NEWS FLASH size (magnitude) squeezed into {@code [0, 0.25]}. */
    public static double clampNews(double frac) {
        return clamp(frac, 0.0, NEWS_MAX);
    }

    /** The drift bound {@code D} squeezed into {@code [0, 0.08]}. */
    public static double clampDriftMax(double frac) {
        return clamp(frac, 0.0, DRIFT_MAX);
    }

    /** A per-item volatility squeezed into {@code [0, 1.5]}. */
    public static double clampVolatility(double v) {
        return clamp(v, 0.0, VOLATILITY_MAX);
    }

    /** {@code tick_minutes} squeezed into {@code [1, 60]}. */
    public static int clampTickMinutes(int minutes) {
        return Math.max(MIN_TICK_MINUTES, Math.min(MAX_TICK_MINUTES, minutes));
    }

    /** {@code value} squeezed into {@code [lo, hi]}; NaN gives {@code lo}. */
    static double clamp(double value, double lo, double hi) {
        if (Double.isNaN(value)) {
            return lo;
        }
        return Math.max(lo, Math.min(hi, value));
    }

    private static double nonNegative(double v) {
        return Double.isNaN(v) || v < 0.0 ? 0.0 : v;
    }
}
