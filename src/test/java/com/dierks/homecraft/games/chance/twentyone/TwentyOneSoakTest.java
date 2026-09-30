package com.dierks.homecraft.games.chance.twentyone;

import com.dierks.homecraft.games.RtpLimits;
import com.dierks.homecraft.games.chance.twentyone.TwentyOneMath.Payouts;
import com.dierks.homecraft.games.chance.twentyone.TwentyOneMath.Solution;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Twenty-One soaks (spec §14): hands dealt by the real engine from seeds and played with the
 * Dinkelbach-optimal strategy give back, per token put in, what the exact maths says — within
 * 4·σ/√n, where σ is the exact per-hand spread from the same maths, and below the band's top. The
 * master seed is a constant that is never tuned.
 */
class TwentyOneSoakTest {

    private static final long SEED = 0x21_21_21_21L;

    /** Tokens back and put in over {@code n} hands played the solution's way. */
    private static double play(TwentyOneHand.Terms terms, TwentyOneMath.Strategy s, int n, long seed) {
        SplittableRandom seeds = new SplittableRandom(seed);
        long back = 0;
        long put = 0;
        for (int i = 0; i < n; i++) {
            TwentyOneHand h = TwentyOneHand.deal(seeds.nextLong(), terms);
            if (!h.done()) {
                int up = h.dealerCards().get(0).value();
                List<TwentyOneHand.Card> mine = h.playerCards();
                if (h.canDouble() && s.dbl(up, TwentyOneHand.hardTotal(mine), TwentyOneHand.hasAce(mine))) {
                    h.doubleDown();
                }
                while (!h.done()) {
                    if (s.hit(up, TwentyOneHand.hardTotal(mine), TwentyOneHand.hasAce(mine))) {
                        h.hit();
                    } else {
                        h.stand();
                    }
                }
            }
            back += h.payout();
            put += h.putIn();
        }
        return (double) back / put;
    }

    /**
     * The exact spread of one hand's (back − R·put in), divided by the mean tokens put in: the
     * standard error of the soak's back/put-in ratio is this over √n.
     */
    static double sigma(Solution sol) {
        Payouts p = sol.payouts();
        TwentyOneMath.Strategy s = sol.strategy();
        int in = p.in();
        double scale = new BigDecimal(TwentyOneMath.totalScale()).doubleValue();
        double win = chance(s, new Payouts(in, 1, 0, 0, 0, 0), scale);
        double push = chance(s, new Payouts(in, 0, 1, 0, 0, 0), scale);
        double natural = chance(s, new Payouts(in, 0, 0, 1, 0, 0), scale);
        double dwin = chance(s, new Payouts(in, 0, 0, 0, 1, 0), scale);
        double dpush = chance(s, new Payouts(in, 0, 0, 0, 0, 1), scale);
        BigInteger[] put = TwentyOneMath.evaluate(s, p);
        double doubled = new BigDecimal(put[1]).divide(new BigDecimal(TwentyOneMath.totalScale()
                .multiply(BigInteger.valueOf(in))), MathContext.DECIMAL64).doubleValue() - 1;
        double dloss = doubled - dwin - dpush;
        double loss = 1 - win - push - natural - doubled;
        double r = sol.rtp();
        double m2 = loss * sq(0 - r * in) + win * sq(p.win() - r * in) + push * sq(p.push() - r * in)
                + natural * sq(p.twentyOne() - r * in) + dloss * sq(0 - r * 2 * in)
                + dwin * sq(p.doubleWin() - r * 2 * in) + dpush * sq(p.doublePush() - r * 2 * in);
        double meanPut = in * (1 + doubled);
        assertEquals(1.0, loss + win + push + natural + dloss + dwin + dpush, 1e-12, "the outcomes cover every hand");
        return Math.sqrt(m2) / meanPut;
    }

    private static double chance(TwentyOneMath.Strategy s, Payouts indicator, double scale) {
        return new BigDecimal(TwentyOneMath.evaluate(s, indicator)[0]).doubleValue() / scale;
    }

    private static double sq(double x) {
        return x * x;
    }

    /**
     * @param belowTop also assert the realised value is under the band's top: only where the exact
     *                 value sits well under it (at rtp 95 the exact 94.8% is within a soak's noise of 95,
     *                 so there the exact value is what must be in the band)
     */
    private static void soak(TwentyOneSettings.Odds odds, int minHands, double maxHalfWidth, boolean belowTop,
                             String why) {
        Solution sol = TwentyOneMath.solve(odds.payouts());
        double sigma = sigma(sol);
        int n = (int) Math.max(minHands, Math.ceil(Math.pow(4 * sigma / maxHalfWidth, 2)));
        double tolerance = 4 * sigma / Math.sqrt(n);
        assertTrue(tolerance <= maxHalfWidth, why + ": 4σ/√n = " + tolerance);
        double realised = play(odds.terms(), sol.strategy(), n, SEED + odds.stake());
        assertEquals(sol.rtp(), realised, tolerance, why + " stake " + odds.stake() + ": " + n
                + " hands must give back what the exact maths says");
        assertTrue(sol.inBand(), why + ": the exact value is inside the band");
        if (belowTop) {
            assertTrue(realised < RtpLimits.MAX, why + ": never above the band");
        }
    }

    @Test
    void theShippedTableGivesBackWhatItsExactMathsSays() {
        for (TwentyOneSettings.Odds odds : TwentyOneSettings.defaults().odds()) {
            soak(odds, 1_000_000, 0.005, true, "shipped");
        }
    }

    @Test
    void theClampExtremesGiveBackWhatTheirExactMathsSays() {
        for (double rtp : new double[] {85, 95}) {
            for (TwentyOneSettings.Odds odds : TwentyOneSettings.solve(List.of(5, 10, 20), rtp, 1.5, 250, "k", null,
                    null)) {
                soak(odds, 200_000, 0.01, rtp < 95, "rtp " + rtp);
            }
        }
    }

    @Test
    void theExactSpreadIsAboutOneStakeAHand() {
        double sigma = sigma(TwentyOneMath.solve(TwentyOneSettings.defaults().odds().get(0).payouts()));
        assertTrue(sigma > 0.8 && sigma < 1.3, "a hand's spread is about one stake: " + sigma);
    }
}
