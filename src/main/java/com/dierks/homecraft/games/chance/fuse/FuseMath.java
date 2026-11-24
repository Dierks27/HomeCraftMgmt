package com.dierks.homecraft.games.chance.fuse;

import com.dierks.homecraft.games.RtpLimits.Ratio;

import java.math.BigInteger;
import java.util.SplittableRandom;

/**
 * 0.57.0: Creeper Fuse's numbers (pure, no Bukkit): where a fuse goes off, how fast the multiple climbs, and what a
 * cash-out pays.
 *
 * <p><b>The multiple</b> is counted in hundredths: 100 is 1.00x, 234 is 2.34x. It starts at 1.00x when the fuse is lit
 * and doubles every {@code double_seconds} ({@link #climb}), so it creeps at first (1.00, 1.01, 1.02 ...) and races
 * later, up to the top ({@code top}, 20.00x as shipped) where the fuse burns out.
 *
 * <p><b>Where it goes off is drawn before the fuse is lit</b> ({@link #boomAt}), from the round's seed alone, so that
 * for every multiple {@code m} on the way (in hundredths, from 1.00x to the top) the chance the fuse is still
 * burning past it is exactly what it must be for the game to give back {@code rtp} wherever a player cashes out:
 *
 * <pre>
 *     P(boom &gt; m) = rtp / m          so      m x P(boom &gt; m) = rtp, for every m
 * </pre>
 *
 * <p>At {@code m = 1.00x} that is {@code rtp} itself: the rest, {@code 1 - rtp} (6 in 100 at 94), is the fuse that goes
 * off at once, before anyone can cash out. A fuse that would go past the top burns out there instead, and everyone
 * still in is paid at the top: {@code top x P(boom &gt; top) = rtp} too. So cashing out at 1.37x, at 5x or riding to the
 * top all give back the same {@code rtp} on average; only how often and how much differ.
 *
 * <p><b>Exactly.</b> The draw is a whole number {@code u} from 1 to 2^53 ({@link #N}); the fuse is still burning past
 * {@code m} when {@code u * den * m < 100 * num * N} ({@code rtp = num/den}), so {@code P(boom > m)} is a count over
 * {@code N} that {@link #burning} gives exactly, and differs from {@code rtp / m} by less than one part in 2^53.
 * {@code CreeperFuseMathTest} checks every multiple from 1.00x to the top with that count.
 *
 * <p><b>Whole tokens.</b> A cash-out pays {@code floor(stake x m)} ({@link #payout}), never rounded up, so between two
 * whole amounts a cash-out gives back a little under {@code rtp}; the settings leave out any stake where that could
 * go under 85 of every 100 ({@link #lowestReturn}).
 */
public final class FuseMath {

    /** The draw's range: a whole number from 1 to 2^53. */
    public static final long N = 1L << 53;
    /** 1.00x, in hundredths. */
    public static final int ONE = 100;

    private FuseMath() {
    }

    /**
     * Where the fuse drawn from {@code seed} goes off, in hundredths: the first multiple the climb reaches that it
     * doesn't survive ({@link #ONE} is at once), or {@code top + 1} when it would go past the top (it burns out there).
     */
    public static int boomAt(long seed, Ratio rtp, int top) {
        long u = draw(seed);
        return boomFor(u, rtp, top);
    }

    /** The draw {@code u} (1 to 2^53) a seed makes. */
    public static long draw(long seed) {
        return (new SplittableRandom(seed).nextLong() >>> 11) + 1;
    }

    /**
     * {@link #boomAt} for a draw {@code u}: the least {@code m >= 1.00x} with
     * {@code u * den * m >= 100 * num * N}, i.e. {@code ceil(100 * num * N / (u * den))}.
     */
    static int boomFor(long u, Ratio rtp, int top) {
        if (u < 1 || u > N) {
            throw new IllegalArgumentException("a draw is 1 to 2^53: " + u);
        }
        BigInteger target = BigInteger.valueOf(100).multiply(BigInteger.valueOf(rtp.num())).multiply(BigInteger.valueOf(N));
        BigInteger per = BigInteger.valueOf(u).multiply(BigInteger.valueOf(rtp.den()));
        BigInteger[] qr = target.divideAndRemainder(per);
        BigInteger m = qr[1].signum() == 0 ? qr[0] : qr[0].add(BigInteger.ONE);
        if (m.compareTo(BigInteger.valueOf(top)) > 0) {
            return top + 1;
        }
        return Math.max(ONE, m.intValueExact());
    }

    /**
     * How many of the {@link #N} draws leave the fuse burning past {@code m} (in hundredths, at least 1.00x): those
     * with {@code u * den * m < 100 * num * N}. Over {@code N}, that is {@code P(boom > m)} exactly.
     */
    public static BigInteger burning(Ratio rtp, int m) {
        if (m < ONE) {
            throw new IllegalArgumentException("the fuse starts at 1.00x: " + m);
        }
        BigInteger target = BigInteger.valueOf(100).multiply(BigInteger.valueOf(rtp.num())).multiply(BigInteger.valueOf(N));
        BigInteger per = BigInteger.valueOf(rtp.den()).multiply(BigInteger.valueOf(m));
        // u < target / per, u >= 1: ceil(target / per) - 1 of them, never more than N
        BigInteger[] qr = target.divideAndRemainder(per);
        BigInteger below = qr[1].signum() == 0 ? qr[0].subtract(BigInteger.ONE) : qr[0];
        return below.min(BigInteger.valueOf(N)).max(BigInteger.ZERO);
    }

    /**
     * The exact return of cashing out at {@code m} (hundredths) were the pay not rounded: {@code m/100 x P(boom > m)},
     * as a fraction over {@code 100 * N}.
     */
    public static BigInteger[] returnAt(Ratio rtp, int m) {
        return new BigInteger[] {burning(rtp, m).multiply(BigInteger.valueOf(m)),
                BigInteger.valueOf(ONE).multiply(BigInteger.valueOf(N))};
    }

    /**
     * The multiple (hundredths) {@code ms} after the fuse was lit: 1.00x doubling every {@code doubleMs}, floored to
     * the hundredth, never past {@code top}.
     */
    public static int climb(long ms, long doubleMs, int top) {
        if (ms <= 0) {
            return ONE;
        }
        double m = ONE * Math.pow(2.0, (double) ms / Math.max(1, doubleMs));
        if (!(m < top)) {
            return top;
        }
        return Math.max(ONE, (int) Math.floor(m + 1e-9));
    }

    /** How long after the fuse was lit the climb reaches {@code m} (hundredths), in ms: when it will show it. */
    public static long reachesAt(int m, long doubleMs) {
        if (m <= ONE) {
            return 0;
        }
        return (long) Math.ceil(Math.log((double) m / ONE) / Math.log(2.0) * doubleMs);
    }

    /** What cashing out at {@code m} (hundredths) pays for {@code stake}: {@code floor(stake x m)}, held to {@code cap}. */
    public static int payout(int stake, int m, int cap) {
        long p = (long) stake * m / ONE;
        return (int) Math.min(p, Math.max(cap, stake));
    }

    /**
     * The highest multiple (hundredths) a stake can cash out at without passing the payout cap, never past
     * {@code top}: the fuse cashes that stake out by itself there.
     */
    public static int topFor(int stake, int cap, int top) {
        // the largest m with floor(stake * m / 100) <= cap
        long most = (((long) Math.max(cap, stake) + 1) * ONE - 1) / stake;
        return (int) Math.max(ONE, Math.min(top, Math.min(most, Integer.MAX_VALUE)));
    }

    /**
     * The least any cash-out gives back at this stake, as a fraction: over every multiple a player can cash out at
     * (1.00x to its own top), {@code payout / (stake x m/100)} times the exact chance the fuse is still burning
     * there. With the pay rounded down to whole tokens this is a little under {@code rtp}; the settings keep a stake
     * only when it is 85 or more.
     *
     * @return {num, den} of the lowest
     */
    public static BigInteger[] lowestReturn(Ratio rtp, int stake, int cap, int top) {
        int most = topFor(stake, cap, top);
        BigInteger[] low = null;
        BigInteger nn = BigInteger.valueOf(N);
        for (int m = ONE; m <= most; m++) {
            // back per token in: payout / stake x P(boom > m)
            BigInteger num = BigInteger.valueOf(payout(stake, m, cap)).multiply(burning(rtp, m));
            BigInteger den = BigInteger.valueOf(stake).multiply(nn);
            if (low == null || num.multiply(low[1]).compareTo(low[0].multiply(den)) < 0) {
                low = new BigInteger[] {num, den};
            }
        }
        return low;
    }

    /** "2.34×" for 234. */
    public static String times(int m) {
        return m / ONE + "." + String.format(java.util.Locale.ROOT, "%02d", m % ONE) + "×";
    }
}
