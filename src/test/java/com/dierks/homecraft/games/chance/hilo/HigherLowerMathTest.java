package com.dierks.homecraft.games.chance.hilo;

import com.dierks.homecraft.games.RtpLimits.Ratio;
import com.dierks.homecraft.games.chance.hilo.HigherLowerRun.Side;
import com.dierks.homecraft.games.chance.hilo.HigherLowerRun.Terms;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Higher or Lower's exact maths (spec §5.6, R1.6): the offer rule never lets a right guess pay
 * less than you have, the optimal-stopping DP matches an independent implementation (the
 * orchestrator-style prototype, and a plain recursive one here), and the exit rule never beats
 * the best play.
 */
class HigherLowerMathTest {

    @Test
    void aRightGuessAlwaysRaisesThePotAndNeverPassesTheTop() {
        for (int r = HigherLowerMath.R_LOW; r <= HigherLowerMath.R_HIGH; r += 7) {
            for (long pot = 1; pot <= 300; pot++) {
                for (int k = 0; k <= 12; k++) {
                    long raised = HigherLowerRun.potIfRight(pot, k, r, 250);
                    if (raised >= 0) {
                        assertTrue(raised > pot, "r " + r + " pot " + pot + " k " + k
                                + ": an offered side must raise the pot (" + raised + ")");
                        assertTrue(raised <= 250, "and never past the top pot");
                        assertEquals(Math.min(250, pot * 13 * r / (k * 1000L)), raised, "floor(pot x r / p)");
                    }
                }
            }
        }
        assertEquals(-1, HigherLowerRun.potIfRight(10, HigherLowerRun.winners(14, Side.HIGHER), 1000, 250),
                "Higher on an Ace can never win, so it is never offered");
        assertEquals(-1, HigherLowerRun.potIfRight(10, HigherLowerRun.winners(2, Side.LOWER), 1000, 250),
                "nor Lower on a 2");
        assertEquals(-1, HigherLowerRun.potIfRight(10, 12, 900, 250),
                "Higher on a 2 at r 0.9 would pay 9 for 10: not offered");
        assertEquals(-1, HigherLowerRun.potIfRight(250, 1, 1000, 250), "at the top pot nothing is offered");
    }

    @Test
    void theBestPlayMatchesAnIndependentExactSolve() {
        // (in, r, cap, max guesses) -> the prototype's exact value
        Object[][] cases = {
                {10, 915, 200, 10, new Ratio(641, 715)},
                {20, 907, 250, 10, new Ratio(9, 10)},
                {50, 904, 250, 10, new Ratio(3208, 3575)},
                {10, 862, 200, 10, new Ratio(613, 715)},
                {5, 950, 40, 3, new Ratio(124, 143)},
                {2, 1000, 9, 2, new Ratio(112, 143)},
        };
        for (Object[] c : cases) {
            Terms t = new Terms((int) c[0], (int) c[1], (int) c[2], (int) c[3]);
            Ratio v = HigherLowerMath.rtp(t);
            assertNotNull(v, t.toString());
            assertEquals(0, v.compareTo((Ratio) c[4]), t + ": exactly " + c[4] + ", not " + v.value());
            assertEquals(0, HigherLowerMath.policy(t).rtp().compareTo(v), "the policy's value is the same number");
        }
    }

    @Test
    void theTopDownSolveEqualsTheFullTable() {
        for (int in : new int[] {1, 2, 5, 10, 20, 50}) {
            for (int cap : new int[] {in + 1, 3 * in, Math.min(20 * in, 250)}) {
                for (int g : new int[] {1, 2, 5, 10}) {
                    for (int r = HigherLowerMath.R_LOW; r <= HigherLowerMath.R_HIGH; r += 13) {
                        Terms t = new Terms(in, r, Math.max(cap, in), g);
                        Ratio fast = HigherLowerMath.rtp(t);
                        Ratio full = HigherLowerMath.fullRtp(t);
                        assertEquals(full == null, fast == null, t + ": both see the same playable first cards");
                        if (full != null) {
                            assertEquals(0, fast.compareTo(full), t + ": the bound-pruned solve is exact");
                        }
                    }
                }
            }
        }
    }

    @Test
    void theDpAgreesWithAPlainRecursiveSolveOnSmallTables() {
        int[][] cases = {{3, 930, 30, 4}, {7, 880, 60, 5}, {1, 1000, 20, 6}, {12, 850, 100, 3}};
        for (int[] c : cases) {
            Terms t = new Terms(c[0], c[1], c[2], c[3]);
            double sum = 0;
            int playable = 0;
            for (int rank = 2; rank <= 14; rank++) {
                if (!HigherLowerRun.anySide(t.in(), rank, t.r(), t.cap())) {
                    continue;
                }
                sum += recurse(t, 0, t.in(), rank);
                playable++;
            }
            Ratio exact = HigherLowerMath.rtp(t);
            assertEquals(sum / playable / t.in(), exact.value(), 1e-12, t + ": the DP and the plain recursion agree");
        }
    }

    /** Best value with no memo and no scaling: cash out (after a right guess) or the better offered side. */
    private static double recurse(Terms t, int guesses, long pot, int rank) {
        if (guesses > 0 && (pot >= t.cap() || guesses >= t.maxGuesses())) {
            return pot;
        }
        double best = guesses > 0 ? pot : Double.NEGATIVE_INFINITY;
        for (Side side : Side.values()) {
            long raised = HigherLowerRun.potIfRight(pot, HigherLowerRun.winners(rank, side), t.r(), t.cap());
            if (raised < 0) {
                continue;
            }
            double ev = 0;
            for (int next = 2; next <= 14; next++) {
                boolean right = side == Side.HIGHER ? next > rank : next < rank;
                if (right) {
                    ev += recurse(t, guesses + 1, raised, next) / 13.0;
                }
            }
            best = Math.max(best, ev);
        }
        return best;
    }

    @Test
    void theExitRuleNeverBeatsTheBestPlay() {
        for (Terms t : new Terms[] {new Terms(10, 915, 200, 10), new Terms(50, 904, 250, 10), new Terms(20, 830, 400, 6)}) {
            HigherLowerMath.Policy p = HigherLowerMath.policy(t);
            for (int rank = 2; rank <= 14; rank++) {
                if (!HigherLowerRun.anySide(t.in(), rank, t.r(), t.cap())) {
                    continue;
                }
                HigherLowerRun probe = probe(t, rank);
                Side exit = probe.likelierSide();
                assertTrue(p.guessThenCash(0, t.in(), rank, exit) <= p.value(0, t.in(), rank),
                        t + ": on " + rank + " guessing " + exit + " then cashing out can't beat the best play");
            }
            // after a right guess the exit rule cashes out: never more than the best play from there
            for (int rank = 2; rank <= 14; rank++) {
                for (Side side : Side.values()) {
                    long raised = HigherLowerRun.potIfRight(t.in(), HigherLowerRun.winners(rank, side), t.r(), t.cap());
                    if (raised < 0 || raised >= t.cap()) {
                        continue;
                    }
                    for (int next = 2; next <= 14; next++) {
                        assertTrue(p.cash(1, raised) <= p.value(1, raised, next),
                                t + ": cashing out " + raised + " can't beat the best play on a " + next);
                    }
                }
            }
        }
    }

    /** A run whose first card is {@code rank} (the likelier-side rule only needs the card and terms). */
    private static HigherLowerRun probe(Terms t, int rank) {
        for (long seed = 1; seed < 100_000; seed++) {
            HigherLowerRun run = HigherLowerRun.start(seed, t);
            if (run.shown().rank() == rank) {
                return run;
            }
        }
        throw new AssertionError("no seed shows a " + rank);
    }

    @Test
    void afterARightGuessTheBestPlayIsToCashOut() {
        // r <= 1: a guess on a side that wins with chance p makes the pot floor(pot x r / p), so its
        // expected pot is at most r x pot <= pot. That is why the screen says every extra guess
        // risks what you have — and the DP must find it, state by state, for any table.
        for (Terms t : new Terms[] {new Terms(10, 915, 200, 10), new Terms(20, 1000, 400, 10),
                new Terms(50, 850, 1000, 10)}) {
            HigherLowerMath.Policy p = HigherLowerMath.policy(t);
            for (int rank = 2; rank <= 14; rank++) {
                for (Side side : Side.values()) {
                    long raised = HigherLowerRun.potIfRight(t.in(), HigherLowerRun.winners(rank, side), t.r(), t.cap());
                    if (raised < 0 || raised >= t.cap()) {
                        continue;
                    }
                    for (int next = 2; next <= 14; next++) {
                        assertNull(p.choose(1, raised, next), t + ": with " + raised + " on a " + next
                                + " the best play cashes out");
                        assertEquals(p.cash(1, raised), p.value(1, raised, next), "and is worth exactly the pot");
                    }
                }
            }
        }
        assertEquals(Side.LOWER, HigherLowerMath.policy(new Terms(10, 915, 200, 10)).choose(0, 10, 7),
                "on a 7 the less likely Lower is the better first guess once pots are whole tokens:"
                        + " 5/13 x 23 beats 7/13 x 16");
    }

    @Test
    void noPlayableFirstCardMeansNoValue() {
        assertNull(HigherLowerMath.rtp(new Terms(10, 900, 10, 10)), "a top pot equal to the stake can't grow");
        Ratio[] all = HigherLowerMath.achievable(10, 200, 10);
        assertEquals(HigherLowerMath.R_HIGH - HigherLowerMath.R_LOW + 1, all.length, "one value per thousandth");
        assertEquals(1000, HigherLowerMath.rAt(0), "from r = 1.000 down");
        assertEquals(800, HigherLowerMath.rAt(all.length - 1), "to r = 0.800");
    }
}
