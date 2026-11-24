package com.dierks.homecraft.games.chance.fuse;

import com.dierks.homecraft.games.RtpLimits;
import com.dierks.homecraft.games.RtpLimits.Ratio;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.57.0: Creeper Fuse gives back what it says, wherever a player cashes out. Proved exactly, not sampled:
 *
 * <ul>
 *   <li>for EVERY multiple from 1.00x to the top, the exact count of draws that leave the fuse burning past it makes
 *       {@code m x P(boom > m)} equal {@code rtp} to within one part in 2^53, and never above it;</li>
 *   <li>the share that goes off at once is {@code 1 - rtp};</li>
 *   <li>where a draw goes off ({@link FuseMath#boomFor}) is exactly what that count counts, at its edges too;</li>
 *   <li>and a large seeded run of fuses lands where the count says (the sampler is the formula);</li>
 *   <li>whole-token pay, rounded down, keeps every shipped stake's every cash-out at 85 or more of every 100, and
 *       leaves 5 tokens out.</li>
 * </ul>
 */
class CreeperFuseMathTest {

    private static final Ratio RTP = Ratio.percent(94);
    private static final BigInteger N = BigInteger.valueOf(FuseMath.N);

    @Test
    void everyCashOutPointGivesBackTheRtpExactly() {
        for (double percent : new double[] {85, 90, 94, 95, 87.5}) {
            Ratio rtp = Ratio.percent(percent);
            BigInteger num = BigInteger.valueOf(rtp.num());
            BigInteger den = BigInteger.valueOf(rtp.den());
            BigInteger target = BigInteger.valueOf(100).multiply(num).multiply(N);
            for (int m = FuseMath.ONE; m <= 10_000; m++) {
                BigInteger burning = FuseMath.burning(rtp, m);
                BigInteger dm = den.multiply(BigInteger.valueOf(m));
                // m/100 x burning/N <= num/den   <=>   burning x den x m <= 100 x num x N
                assertTrue(burning.multiply(dm).compareTo(target) < 0, percent + " at " + m + ": never above rtp");
                // and short of it by less than m/(100 N): one draw's worth
                assertTrue(target.subtract(burning.multiply(dm)).compareTo(dm) <= 0,
                        percent + " at " + m + ": within one part in 2^53");
                BigInteger[] r = FuseMath.returnAt(rtp, m);
                assertEquals(rtp.value(), new java.math.BigDecimal(r[0]).divide(new java.math.BigDecimal(r[1]),
                        java.math.MathContext.DECIMAL64).doubleValue(), 1e-12);
            }
        }
    }

    @Test
    void theFuseGoesOffAtOnceOneMinusRtpOfTheTime() {
        BigInteger burning = FuseMath.burning(RTP, FuseMath.ONE);
        double instant = 1 - burning.doubleValue() / FuseMath.N;
        assertEquals(0.06, instant, 1e-15, "94: it goes off at once 6 times in 100");
        assertEquals(0.53, 1 - FuseMath.burning(RTP, 200).doubleValue() / FuseMath.N, 1e-15,
                "past 2.00x it is still burning 47 times in 100");
    }

    @Test
    void whereADrawGoesOffIsExactlyWhatTheCountCounts() {
        SplittableRandom r = new SplittableRandom(5);
        BigInteger target = BigInteger.valueOf(100).multiply(BigInteger.valueOf(RTP.num())).multiply(N);
        for (int i = 0; i < 20_000; i++) {
            long u = (r.nextLong() >>> 11) + 1;
            int m = FuseMath.ONE + r.nextInt(1_900);
            int boom = FuseMath.boomFor(u, RTP, 2000);
            boolean burning = BigInteger.valueOf(u).multiply(BigInteger.valueOf(RTP.den()))
                    .multiply(BigInteger.valueOf(m)).compareTo(target) < 0;
            assertEquals(burning, boom > m, "u " + u + " at " + m);
        }
        for (int m : new int[] {100, 101, 150, 199, 200, 777, 1999}) {
            long edge = FuseMath.burning(RTP, m).longValueExact();
            assertTrue(FuseMath.boomFor(edge, RTP, 2000) > m, "the last draw counted is still burning past " + m);
            assertTrue(FuseMath.boomFor(edge + 1, RTP, 2000) <= m, "the next one is not");
        }
        assertEquals(FuseMath.ONE, FuseMath.boomFor(FuseMath.N, RTP, 2000), "the highest draw goes off at once");
        assertEquals(2001, FuseMath.boomFor(1, RTP, 2000), "the lowest burns out at the top");
    }

    @Test
    void aLargeSeededRunLandsWhereTheCountSays() {
        int fuses = 400_000;
        int[] targets = {100, 150, 200, 500, 2000};
        double[] back = new double[targets.length];
        int instant = 0;
        SplittableRandom seeds = new SplittableRandom(2026_11_24L);
        for (int i = 0; i < fuses; i++) {
            int boom = FuseMath.boomAt(seeds.nextLong(), RTP, 2000);
            if (boom == FuseMath.ONE) {
                instant++;
            }
            for (int t = 0; t < targets.length; t++) {
                if (boom > targets[t]) {
                    back[t] += targets[t] / 100.0; // per token in, unrounded
                }
            }
        }
        assertEquals(0.06, instant / (double) fuses, 4 * Math.sqrt(0.06 * 0.94 / fuses));
        for (int t = 0; t < targets.length; t++) {
            double m = targets[t] / 100.0;
            double p = 0.94 / m;
            double sd = m * Math.sqrt(p * (1 - p) / fuses);
            assertEquals(0.94, back[t] / fuses, 4 * sd, "cashing out at " + FuseMath.times(targets[t]));
        }
    }

    @Test
    void wholeTokenPayKeepsEveryShippedCashOutInTheBand() {
        CreeperFuseSettings d = CreeperFuseSettings.defaults();
        assertEquals(List.of(10, 20, 50), d.open());
        for (CreeperFuseSettings.Odds o : d.odds()) {
            assertTrue(o.lowest() >= RtpLimits.MIN, o.stake() + ": every cash-out gives back 85 or more: " + o.lowest());
            assertTrue(o.lowest() <= 0.94, "and never more than the rtp");
        }
        assertEquals(0.94 * 10 / 10.9, d.odds(10).lowest(), 1e-12, "10 tokens at 1.09x still pays 10");
        assertEquals(2000, d.odds(10).top(), "20.00x x 10 = 200: under the 250 cap");
        assertEquals(501, d.odds(50).top(), "50 tokens reach the 250 cap at 5.01x and cash out there");
        assertEquals(250, FuseMath.payout(50, 501, 250));
        List<String> warns = new ArrayList<>();
        List<CreeperFuseSettings.Odds> five = CreeperFuseSettings.solve("games.creeper_fuse", List.of(5, 10), 94, 2000,
                250, warns::add);
        assertEquals(1, five.size(), "5 tokens is off: 5 x 1.19 pays 5, under 85 of every 100");
        assertEquals(10, five.get(0).stake());
        assertEquals(1, warns.size(), warns.toString());
        assertTrue(warns.get(0).contains("stakes 5"), warns.get(0));
    }

    @Test
    void theMultipleClimbsFasterAndFaster() {
        assertEquals(100, FuseMath.climb(0, 6000, 2000));
        assertEquals(100, FuseMath.climb(50, 6000, 2000), "a tick in: still 1.00x");
        assertEquals(200, FuseMath.climb(6000, 6000, 2000));
        assertEquals(400, FuseMath.climb(12_000, 6000, 2000));
        assertEquals(2000, FuseMath.climb(60_000, 6000, 2000), "never past the top");
        int last = 100;
        int firstStep = -1;
        int lastStep = -1;
        for (long ms = 50; ms <= 25_000; ms += 50) {
            int m = FuseMath.climb(ms, 6000, 2000);
            assertTrue(m >= last, "it never goes down");
            if (firstStep < 0) {
                firstStep = m - last;
            }
            lastStep = m - last;
            last = m;
        }
        assertTrue(lastStep > firstStep * 10, "a tick late on climbs many times more than one early on");
        assertEquals(6000, FuseMath.reachesAt(200, 6000));
        assertEquals("2.34×", FuseMath.times(234));
        assertEquals("1.00×", FuseMath.times(100));
    }

    @Test
    void aPlacesDataReadsBackAndALeftRoundGivesTheStakeBack() {
        FuseTicket t = FuseTicket.entry(10, 250, 2000, 200);
        assertEquals("v=1;in=10;cap=250;top=2000;auto=200", t.data());
        FuseTicket lit = t.lit(99L, 187);
        assertEquals("v=1;in=10;cap=250;top=2000;auto=200;fuse=99;boom=187", lit.data());
        assertEquals(lit, FuseTicket.read(lit.data()));
        assertEquals(10, FuseTicket.settleOnExit(1, 10, t.data()), "open and never cashed out: the stake back");
        assertEquals(10, FuseTicket.settleOnExit(1, 10, lit.data()), "lit, never cashed out: the stake back");
        assertEquals(15, FuseTicket.settleOnExit(1, 10, lit.ended(150).data()), "cashed at 1.50x: 15");
        assertEquals(0, FuseTicket.settleOnExit(1, 10, lit.ended(0).data()), "caught: nothing");
        assertEquals(10, lit.ended(FuseMath.ONE).payout(), "put out: exactly the stake back");
    }
}
