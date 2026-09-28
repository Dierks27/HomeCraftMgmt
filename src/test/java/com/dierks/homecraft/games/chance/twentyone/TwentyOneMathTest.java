package com.dierks.homecraft.games.chance.twentyone;

import com.dierks.homecraft.games.RtpLimits;
import com.dierks.homecraft.games.RtpLimits.Ratio;
import com.dierks.homecraft.games.chance.twentyone.TwentyOneMath.Payouts;
import com.dierks.homecraft.games.chance.twentyone.TwentyOneMath.Solution;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Twenty-One's exact maths (spec §5.4, R1.5): the Arcade's hand, the best play, the per-token
 * return that Dinkelbach's method maximises, and the candidates the per-stake solve chooses from.
 * The reference numbers come from an independent floating-point implementation (the
 * orchestrator's review scripts) and textbook basic strategy.
 */
class TwentyOneMathTest {

    /** Even money and 3:2 for a two-card 21, at 2 tokens in so every payout is whole. */
    private static final Payouts EVEN = new Payouts(2, 4, 2, 5, 8, 4);

    private static double ratio(BigInteger a, BigInteger b) {
        return new BigDecimal(a).divide(new BigDecimal(b), MathContext.DECIMAL64).doubleValue();
    }

    @Test
    void theArcadesFinalsAddUpToEveryHandWithoutItsTwentyOne() {
        for (int up = 1; up <= 10; up++) {
            BigInteger sum = BigInteger.ZERO;
            for (int f = 17; f <= 22; f++) {
                sum = sum.add(TwentyOneMath.dealerFinal(up, f));
            }
            assertEquals(TwentyOneMath.noTwentyOne(up), sum,
                    "showing " + up + ", the Arcade's finals must cover exactly the hands where it has no Twenty-One");
        }
        assertEquals(TwentyOneMath.ONE.multiply(BigInteger.valueOf(9)).divide(BigInteger.valueOf(13)),
                TwentyOneMath.noTwentyOne(1), "showing an Ace, the hole card is a ten-count 4 times in 13");
        assertEquals(TwentyOneMath.ONE, TwentyOneMath.noTwentyOne(6), "showing a 6 it can't have Twenty-One");
    }

    @Test
    void evenMoneyWithThreeToTwoGivesBackAbout98Point9PerStartingStake() {
        BigInteger[] net = TwentyOneMath.bestNet(EVEN);
        BigInteger stakes = TwentyOneMath.totalScale().multiply(BigInteger.valueOf(EVEN.in()));
        double perStake = ratio(net[0].subtract(net[1]).add(stakes), stakes);
        assertEquals(0.98913, perStake, 5e-5,
                "standard rules with no split must give back about 98.9% per starting stake (the sanity check)");
        Solution best = TwentyOneMath.solve(EVEN);
        assertEquals(0.99008, best.rtp(), 5e-5, "and about 99.0% per token put in with the best play for that");
        assertTrue(best.rtp() >= ratio(net[0], net[1]),
                "the Dinkelbach play can't give back less per token than the best-net play");
    }

    @Test
    void theShippedPayoutsMatchAnIndependentFloatingPointSolve() {
        // stake, win, twenty-one, double win (tokens back) -> the review scripts' per-token value
        Object[][] cases = {
                {5, 9, 11, 18, 0.89751}, {5, 9, 11, 19, 0.90904}, {5, 9, 12, 19, 0.91699},
                {10, 18, 23, 37, 0.90696}, {10, 17, 21, 34, 0.85329},
                {20, 36, 44, 72, 0.89751}, {20, 36, 45, 73, 0.90207}, {20, 38, 48, 77, 0.94835},
        };
        for (Object[] c : cases) {
            int in = (int) c[0];
            Payouts p = new Payouts(in, (int) c[1], in, (int) c[2], (int) c[3], 2 * in);
            assertEquals((double) c[4], TwentyOneMath.solve(p).rtp(), 5e-5, "the best-play return of " + p);
        }
    }

    @Test
    void dinkelbachConvergesToAPlayNoSingleDoubleChangeCanBeat() {
        Payouts p = new Payouts(5, 9, 5, 11, 18, 10);
        Solution s = TwentyOneMath.solve(p);
        assertTrue(s.passes() <= 1 + TwentyOneMath.MAX_PASSES, "at most the best-net pass plus 6: " + s.passes());
        assertTrue(s.passes() >= 2, "it always checks that λ stopped moving");
        BigInteger[] own = TwentyOneMath.evaluate(s.strategy(), p);
        assertEquals(s.back().multiply(own[1]), own[0].multiply(s.putIn()),
                "the published value must be exactly what its own strategy earns");
        for (int up = 1; up <= 10; up++) {
            for (int total = 2; total <= 20; total++) {
                for (boolean soft : new boolean[] {false, true}) {
                    BigInteger[] other = TwentyOneMath.evaluate(s.strategy().flipDouble(up, total, soft), p);
                    assertTrue(other[0].multiply(s.putIn()).compareTo(s.back().multiply(other[1])) <= 0,
                            "flipping the Double on " + total + (soft ? " soft" : "") + " against " + up
                                    + " must not give back more per token");
                }
            }
        }
    }

    @Test
    void theBestPlayIsBasicStrategyWhereItShouldBe() {
        TwentyOneMath.Strategy s = TwentyOneMath.solve(EVEN).strategy();
        for (int up = 1; up <= 10; up++) {
            assertFalse(s.hit(up, 17, false), "never hit a hard 17 (against " + up + ")");
            assertFalse(s.hit(up, 20, false), "never hit a hard 20");
            assertTrue(s.hit(up, 8, false), "always hit a hard 8");
        }
        assertTrue(s.hit(10, 16, false), "hit a hard 16 against a 10");
        assertFalse(s.hit(6, 13, false), "stand on 13 against a 6");
        assertTrue(s.hit(2, 12, false), "hit 12 against a 2");
        assertTrue(s.dbl(6, 11, false), "double 11 against a 6");
        assertTrue(s.dbl(5, 10, false), "double 10 against a 5");
        assertFalse(s.dbl(10, 9, false), "don't double 9 against a 10");
        assertTrue(s.hit(9, 8, true), "hit a soft 18 (Ace + 7) against a 9");
    }

    @Test
    void standingNeverBeatsTheBestPlayFromAnyHand() {
        Payouts p = new Payouts(10, 18, 10, 22, 36, 20);
        for (int up = 1; up <= 10; up++) {
            for (int total = 2; total <= 21; total++) {
                for (boolean soft : new boolean[] {false, true}) {
                    BigInteger[] v = TwentyOneMath.handValues(p, up, total, soft);
                    assertTrue(v[0].compareTo(v[1]) <= 0, "the exit rule (stand) can't beat the best play on "
                            + total + (soft ? " soft" : "") + " against " + up);
                }
            }
        }
    }

    @Test
    void aDoubleIsOnlyOfferedWhenADoubledWinPaysMoreThanItTookIn() {
        assertTrue(new Payouts(5, 9, 5, 11, 18, 10).canDouble(), "18 back for 10 in is a real win");
        assertFalse(new Payouts(20, 36, 20, 44, 40, 40).canDouble(), "a cap of 40 would make a doubled win pay 40 for 40");
        Solution s = TwentyOneMath.solve(new Payouts(20, 36, 20, 44, 40, 40));
        for (int up = 1; up <= 10; up++) {
            for (int total = 2; total <= 21; total++) {
                assertFalse(s.strategy().dbl(up, total, false), "no Double where it can't win anything");
            }
        }
    }

    @Test
    void theCandidatesAreEveryDistinctPayoutWithItsSmallestMultiplier() {
        Ratio bonus = new Ratio(3, 2);
        List<TwentyOneMath.Candidate> c = TwentyOneMath.candidates(5, bonus, 250);
        assertEquals(TwentyOneMath.M_LOW, c.get(0).m(), "the range starts at m = 0.5");
        List<Payouts> seen = new ArrayList<>();
        for (int i = 0; i < c.size(); i++) {
            TwentyOneMath.Candidate k = c.get(i);
            assertTrue(k.m().compareTo(TwentyOneMath.M_LOW) >= 0 && k.m().compareTo(TwentyOneMath.M_HIGH) <= 0,
                    "every m lies in [0.5, 1]: " + k.m());
            assertFalse(seen.contains(k.payouts()), "each payout set appears once: " + k.payouts());
            seen.add(k.payouts());
            if (i > 0) {
                assertTrue(k.m().compareTo(c.get(i - 1).m()) > 0, "in order of m");
                // just below this m, the payouts are still the previous candidate's
                Ratio before = new Ratio(k.m().num() * 1000 - 1, k.m().den() * 1000);
                assertEquals(c.get(i - 1).payouts(), TwentyOneHand.Terms.payouts(5, before, bonus, 250),
                        "m = " + k.m() + " is the smallest m of its payouts");
            }
        }
        assertEquals(new Payouts(5, 10, 5, 12, 20, 10), c.get(c.size() - 1).payouts(),
                "m = 1 is even money with a 1.5x Twenty-One");
        assertEquals(new Payouts(5, 7, 5, 8, 15, 10), c.get(0).payouts(), "m = 0.5 adds 2, 3 and 5");
    }

    @Test
    void theCapBringsEveryPayoutDownAndTheMathsUsesTheCappedValues() {
        Payouts capped = TwentyOneHand.Terms.payouts(20, new Ratio(1, 1), new Ratio(3, 2), 40);
        assertEquals(new Payouts(20, 40, 20, 40, 40, 40), capped, "nothing above the cap of 40");
        Solution s = TwentyOneMath.solve(capped);
        Solution open = TwentyOneMath.solve(TwentyOneHand.Terms.payouts(20, new Ratio(1, 1), new Ratio(3, 2), 250));
        assertTrue(s.compareTo(open) < 0, "a binding cap must lower the return");
    }

    @Test
    void theReturnNeverFallsAsMRisesSoTheBinarySearchChoosesWhatTheFullRuleWould() {
        double[] targets = {85, 87.5, 90, 92.3, 95};
        for (int stake : new int[] {1, 2, 3, 5, 8, 13, 20}) {
            for (Ratio bonus : new Ratio[] {new Ratio(3, 2), new Ratio(2, 1)}) {
                for (int cap : new int[] {250, 2 * stake}) {
                    List<TwentyOneMath.Candidate> cands = TwentyOneMath.candidates(stake, bonus, cap);
                    List<Solution> all = new ArrayList<>();
                    for (TwentyOneMath.Candidate c : cands) {
                        all.add(TwentyOneMath.solve(c.payouts()));
                    }
                    for (int i = 1; i < all.size(); i++) {
                        assertTrue(all.get(i).compareTo(all.get(i - 1)) >= 0, "stake " + stake + " bonus " + bonus
                                + " cap " + cap + ": a larger m never gives back less");
                    }
                    for (double t : targets) {
                        Ratio target = Ratio.percent(t);
                        TwentyOneMath.Choice c = TwentyOneMath.choose(target, cands);
                        assertEquals(TwentyOneMath.pick(target, all), c.index(), "stake " + stake + " bonus " + bonus
                                + " cap " + cap + " target " + t + ": the binary search and the full rule agree");
                    }
                }
            }
        }
    }

    @Test
    void thePickIsTheR11RuleExactlyAndAValueEqualToTheTargetCounts() {
        Payouts any = new Payouts(1, 2, 1, 2, 4, 2);
        List<Solution> values = List.of(sol(any, 84, 100), sol(any, 9, 10), sol(any, 91, 100), sol(any, 89, 100));
        assertEquals(1, TwentyOneMath.pick(new Ratio(9, 10), values), "exactly 90% is at or below a 90 target");
        assertEquals(3, TwentyOneMath.pick(new Ratio(8999, 10000), values), "89% is the largest under 89.99");
        assertEquals(3, TwentyOneMath.pick(new Ratio(85, 100), values),
                "84% is under the band, so the smallest from 85 up");
        assertEquals(-1, TwentyOneMath.pick(new Ratio(9, 10), List.of(sol(any, 5, 10), sol(any, 97, 100))),
                "nothing inside 85-95: the stake is left out");
        assertEquals(0, TwentyOneMath.pick(RtpLimits.MAX_RATIO, List.of(sol(any, 95, 100))), "95% itself is in");
    }

    private static Solution sol(Payouts p, long back, long put) {
        return new Solution(p, BigInteger.valueOf(back), BigInteger.valueOf(put), null, 1);
    }
}
