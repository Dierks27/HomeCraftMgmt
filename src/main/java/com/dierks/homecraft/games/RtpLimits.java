package com.dierks.homecraft.games;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The return-to-player band, locked in code (spec §5.1, R1.1, R1.2).
 *
 * <p>Every game of chance gives back LESS than is put in, on average, and never much less: its
 * RTP must sit inside [{@link #MIN}, {@link #MAX}] — 85 to 95 tokens back for every 100 put in.
 * Config can choose a target inside that band ({@code rtp: 90}); it can never move the band. A
 * target outside it is clamped here with one WARN naming the key, and an engine that cannot reach
 * any value inside the band with whole-token payouts drops that stake (or closes the game) rather
 * than run outside it.
 *
 * <p>This is also the one place the published number is formatted, so the player's screen, the
 * admin's {@code /hcm arcade odds} and the website all floor the same computed value the same way
 * — the player sees "about 89 of every 100" for an engine that computed 89.7%, never the
 * config's 90.
 */
public final class RtpLimits {

    /** The band, as percents (what config and players read). */
    public static final double MIN_PERCENT = 85.0;
    public static final double MAX_PERCENT = 95.0;
    public static final double DEFAULT_PERCENT = 90.0;

    /** The band, as fractions (what engines compute). */
    public static final double MIN = MIN_PERCENT / 100.0;
    public static final double MAX = MAX_PERCENT / 100.0;
    public static final double DEFAULT = DEFAULT_PERCENT / 100.0;

    /** Exact fractions computed in doubles differ in the last bits; this absorbs that, nothing more. */
    private static final double EPS = 1e-9;

    private RtpLimits() {
    }

    /**
     * A configured {@code rtp} (a percent) as a fraction inside the band. A value outside it is
     * clamped with one WARN naming {@code key}; a value that is not a number is the default.
     */
    public static double clampPercent(double percent, String key, Consumer<String> warn) {
        Consumer<String> w = warn == null ? s -> { } : warn;
        if (!Double.isFinite(percent)) {
            w.accept(key + " is not a number - using " + fmt(DEFAULT_PERCENT));
            return DEFAULT;
        }
        if (percent > MAX_PERCENT) {
            w.accept(key + " " + fmt(percent) + " goes past the limit locked in code (" + fmt(MAX_PERCENT)
                    + ") - using " + fmt(MAX_PERCENT));
            return MAX;
        }
        if (percent < MIN_PERCENT) {
            w.accept(key + " " + fmt(percent) + " is below the lowest allowed (" + fmt(MIN_PERCENT)
                    + ") - using " + fmt(MIN_PERCENT));
            return MIN;
        }
        return percent / 100.0;
    }

    /** Whether a computed RTP is inside the band. */
    public static boolean inBand(double fraction) {
        return fraction >= MIN - EPS && fraction <= MAX + EPS;
    }

    /**
     * The value an engine publishes and plays, picked from the RTPs it can actually achieve (spec
     * R1.1): the largest one at or below the target; if that is under {@link #MIN} (or there is
     * none), the smallest one at or above {@link #MIN}, as long as it is not over {@link #MAX}.
     *
     * @param target     the clamped target, a fraction
     * @param achievable every exact RTP the engine can reach with whole-token payouts, one per
     *                   candidate setting of its free parameter
     * @return the pick, or {@code null} when nothing achievable is inside the band (the caller
     *         drops the stake with one WARN, see {@link #nearest})
     */
    public static Pick pick(double target, double[] achievable) {
        int below = -1;
        int floor = -1;
        for (int i = 0; i < achievable.length; i++) {
            double v = achievable[i];
            if (!Double.isFinite(v)) {
                continue;
            }
            if (v <= target + EPS && (below < 0 || v > achievable[below])) {
                below = i;
            }
            if (v >= MIN - EPS && (floor < 0 || v < achievable[floor])) {
                floor = i;
            }
        }
        if (below >= 0 && inBand(achievable[below])) {
            return new Pick(achievable[below], below, false);
        }
        if (floor >= 0 && inBand(achievable[floor])) {
            return new Pick(achievable[floor], floor, achievable[floor] > target + EPS);
        }
        return null;
    }

    /**
     * The same rule with EXACT fractions (spec §5.1: "compare exactly, so 17/20 counts as ≤ 85%").
     * Engines whose payouts are whole tokens can express every achievable RTP as a fraction; use
     * this and a value exactly equal to the target (or to a band edge) always counts.
     *
     * @return the pick ({@link Pick#rtp()} is {@code achievable[index].value()}), or {@code null}
     *         when nothing achievable is inside the band
     */
    public static Pick pick(Ratio target, Ratio[] achievable) {
        int below = -1;
        int floor = -1;
        for (int i = 0; i < achievable.length; i++) {
            Ratio v = achievable[i];
            if (v == null) {
                continue;
            }
            if (v.compareTo(target) <= 0 && (below < 0 || v.compareTo(achievable[below]) > 0)) {
                below = i;
            }
            if (v.compareTo(MIN_RATIO) >= 0 && (floor < 0 || v.compareTo(achievable[floor]) < 0)) {
                floor = i;
            }
        }
        if (below >= 0 && inBand(achievable[below])) {
            return new Pick(achievable[below].value(), below, false);
        }
        if (floor >= 0 && inBand(achievable[floor])) {
            return new Pick(achievable[floor].value(), floor, achievable[floor].compareTo(target) > 0);
        }
        return null;
    }

    /** Whether an exact RTP is inside the band (edges included). */
    public static boolean inBand(Ratio r) {
        return r.compareTo(MIN_RATIO) >= 0 && r.compareTo(MAX_RATIO) <= 0;
    }

    /** The band's edges as exact fractions. */
    public static final Ratio MIN_RATIO = new Ratio(85, 100);
    public static final Ratio MAX_RATIO = new Ratio(95, 100);

    /**
     * An exact fraction {@code num / den}, compared without rounding.
     *
     * @param num the numerator
     * @param den the denominator, never 0 (a negative one is moved to the numerator)
     */
    public record Ratio(long num, long den) implements Comparable<Ratio> {

        public Ratio {
            if (den == 0) {
                throw new IllegalArgumentException("a ratio needs a denominator");
            }
            if (den < 0) {
                num = -num;
                den = -den;
            }
        }

        /** A configured percent (87.5) as the exact fraction it means (7/8), from its decimal text. */
        public static Ratio percent(double percent) {
            BigDecimal f = BigDecimal.valueOf(percent).movePointLeft(2).stripTrailingZeros();
            BigInteger n = f.unscaledValue();
            BigInteger d = BigInteger.TEN.pow(Math.max(0, f.scale()));
            if (f.scale() < 0) {
                n = n.multiply(BigInteger.TEN.pow(-f.scale()));
            }
            BigInteger g = n.gcd(d);
            return new Ratio(n.divide(g).longValueExact(), d.divide(g).longValueExact());
        }

        /** The value as a double (for display and for {@link Pick#rtp()}). */
        public double value() {
            return (double) num / (double) den;
        }

        @Override
        public int compareTo(Ratio o) {
            return BigInteger.valueOf(num).multiply(BigInteger.valueOf(o.den))
                    .compareTo(BigInteger.valueOf(o.num).multiply(BigInteger.valueOf(den)));
        }
    }

    /**
     * The chosen RTP.
     *
     * @param rtp         the exact value, a fraction: publish and play THIS, never the target
     * @param index       its index in the array passed to {@link #pick}
     * @param aboveTarget true when nothing at or below the target was in the band, so a higher
     *                    value (still inside it) was taken — worth one INFO line
     */
    public record Pick(double rtp, int index, boolean aboveTarget) {
    }

    /**
     * For the WARN when a stake is dropped: the achievable values closest to the band from below
     * and from above ("84.2% or 96.1%"), or "nothing" when there are none.
     */
    public static String nearest(double[] achievable) {
        double under = Double.NaN;
        double over = Double.NaN;
        for (double v : achievable) {
            if (!Double.isFinite(v)) {
                continue;
            }
            if (v < MIN && (Double.isNaN(under) || v > under)) {
                under = v;
            }
            if (v > MAX && (Double.isNaN(over) || v < over)) {
                over = v;
            }
        }
        List<String> parts = new ArrayList<>(2);
        if (!Double.isNaN(under)) {
            parts.add(tenthPercent(under) + "%");
        }
        if (!Double.isNaN(over)) {
            parts.add(tenthPercent(over) + "%");
        }
        return parts.isEmpty() ? "nothing" : String.join(" or ", parts);
    }

    /** The computed RTP as players read it: a whole percent, floored (89.7% reads 89). */
    public static int wholePercent(double fraction) {
        return (int) Math.floor(fraction * 100.0 + EPS);
    }

    /** The computed RTP as admins and the website read it: floored to one decimal (89.76% reads 89.7). */
    public static double tenthPercent(double fraction) {
        return Math.floor(fraction * 1000.0 + EPS) / 10.0;
    }

    /** The one way players are told: "gives back about 89 of every 100 tokens". */
    public static String playerLine(double fraction) {
        return "gives back about " + wholePercent(fraction) + " of every 100 tokens";
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : String.valueOf(v);
    }
}
