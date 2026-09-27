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
    /**
     * {@code drift.half_life_hours} is never shorter than a day. A drift that fades within hours
     * is redrawn within the day, so the same day's low and high drift apart by more than the
     * day-to-day move: at a 6 h half-life iron beat the 10.5% buy-then-sell round trip on about
     * one day in ten, and at 0 the drift was fresh noise every tick. At 24 h or more a day is
     * at most one half-life and the wander builds over days, not hours.
     */
    public static final double DRIFT_MIN_HALF_LIFE_HOURS = 24.0;
    /**
     * The widest drift size (the drift's stationary sd {@code sigma}) any item can have: the
     * liveliest the shipped settings allow, a lively item (3%) at volatility 1.5. Paired with
     * the shipped half-life ({@link #DRIFT_SPEED_HALF_LIFE_HOURS}) that is about 3% a day.
     */
    public static final double DRIFT_SIGMA_MAX = 0.045;
    /**
     * The half-life {@link #DRIFT_SIGMA_MAX} is paired with (the shipped 66 h). A shorter
     * half-life with the same {@code sigma} would move prices faster, so below it the drift size
     * is scaled down to keep the same speed ({@link #driftSigmaCap}).
     */
    public static final double DRIFT_SPEED_HALF_LIFE_HOURS = 66.0;
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

    /**
     * {@code drift.half_life_hours} held to at least {@link #DRIFT_MIN_HALF_LIFE_HOURS} (24);
     * longer is calmer and always allowed (+infinity freezes the drift). NaN gives 24.
     */
    public static double clampDriftHalfLife(double hours) {
        return clamp(hours, DRIFT_MIN_HALF_LIFE_HOURS, Double.POSITIVE_INFINITY);
    }

    /**
     * The largest drift size {@code sigma} (a fraction) an item may have at this half-life: the
     * drift's speed lock. How fast the drift moves prices over hours is
     * {@code sigma x sqrt(2 ln2 / halfLife)} (its sd per square-root hour), so this keeps it at or
     * under the shipped settings at their liveliest (4.5% at 66 h):
     * {@code 0.045 x sqrt(min(halfLife, 66) / 66)}, exactly 0.045 from 66 h up. With the half-life
     * floor that is at most about 3% a day and 0.19% a 5-minute tick, far under the 10.5% round
     * trip, so no setting of {@code calm_percent}, {@code lively_percent}, {@code volatility} or
     * {@code half_life_hours} brings back same-day scalping of the drift. The half-life is first
     * held to its floor ({@link #clampDriftHalfLife}).
     */
    public static double driftSigmaCap(double halfLifeHours) {
        double h = clampDriftHalfLife(halfLifeHours);
        if (h >= DRIFT_SPEED_HALF_LIFE_HOURS) {
            return DRIFT_SIGMA_MAX;
        }
        return DRIFT_SIGMA_MAX * Math.sqrt(h / DRIFT_SPEED_HALF_LIFE_HOURS);
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
