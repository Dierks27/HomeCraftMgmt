package com.dierks.homecraft.games.chance.slots;

import com.dierks.homecraft.games.RtpLimits;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;

/**
 * An exact fraction of whole numbers, as big as they need to be: what Deep Mine and Mob Slots publish is worked out
 * in these, never in doubles, so "inside the band" and "at most the target" are compared exactly.
 *
 * @param num the numerator, never negative
 * @param den the denominator, always above 0
 */
public record Frac(BigInteger num, BigInteger den) implements Comparable<Frac> {

    public static final Frac ZERO = new Frac(BigInteger.ZERO, BigInteger.ONE);
    public static final Frac MIN = of(RtpLimits.MIN_RATIO);
    public static final Frac MAX = of(RtpLimits.MAX_RATIO);

    public Frac {
        if (num == null || den == null || den.signum() <= 0 || num.signum() < 0) {
            throw new IllegalArgumentException("not a fraction: " + num + " / " + den);
        }
    }

    public static Frac of(long num, long den) {
        return new Frac(BigInteger.valueOf(num), BigInteger.valueOf(den));
    }

    public static Frac of(RtpLimits.Ratio ratio) {
        return of(ratio.num(), ratio.den());
    }

    /** A configured percent (87.5) as the exact fraction it means (7/8). */
    public static Frac percent(double percent) {
        return of(RtpLimits.Ratio.percent(percent));
    }

    public Frac times(Frac o) {
        return new Frac(num.multiply(o.num), den.multiply(o.den));
    }

    public Frac plus(Frac o) {
        return new Frac(num.multiply(o.den).add(o.num.multiply(den)), den.multiply(o.den));
    }

    /** The value as a double (for display; every comparison is {@link #compareTo}). */
    public double value() {
        return new BigDecimal(num).divide(new BigDecimal(den), MathContext.DECIMAL64).doubleValue();
    }

    /** Whether it is inside the 85-95 band, edges included. */
    public boolean inBand() {
        return compareTo(MIN) >= 0 && compareTo(MAX) <= 0;
    }

    @Override
    public int compareTo(Frac o) {
        return num.multiply(o.den).compareTo(o.num.multiply(den));
    }

    /** The same value: 1/2 and 2/4 are equal here, though they are different records. */
    public boolean same(Frac o) {
        return compareTo(o) == 0;
    }
}
