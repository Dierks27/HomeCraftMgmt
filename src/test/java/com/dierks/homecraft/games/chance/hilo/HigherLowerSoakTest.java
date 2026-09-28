package com.dierks.homecraft.games.chance.hilo;

import com.dierks.homecraft.games.RtpLimits;
import com.dierks.homecraft.games.chance.hilo.HigherLowerRun.Side;
import com.dierks.homecraft.games.chance.hilo.HigherLowerRun.Terms;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Higher or Lower soaks (spec §14): runs dealt by the real engine from seeds and played by the
 * DP's own policy give back what the exact DP says — within 4·σ/√n, where σ is the exact per-run
 * spread of that same policy, and below the band's top. The master seed is a constant that is never
 * tuned.
 */
class HigherLowerSoakTest {

    private static final long SEED = 0x4849_4c4fL;

    /** Tokens back over tokens in for {@code n} runs played the DP's way. */
    private static double play(HigherLowerMath.Policy p, int n, long seed) {
        Terms t = p.terms();
        SplittableRandom seeds = new SplittableRandom(seed);
        long back = 0;
        for (int i = 0; i < n; i++) {
            HigherLowerRun run = HigherLowerRun.start(seeds.nextLong(), t);
            while (!run.done()) {
                Side s = p.choose(run.guesses(), run.pot(), run.shown().rank());
                if (s == null) {
                    run.cashOut();
                } else {
                    run.guess(s);
                }
            }
            back += run.payout();
        }
        return (double) back / ((long) n * t.in());
    }

    /** {E[back], E[back²]} of the policy from a state, in tokens. */
    private static double[] moments(HigherLowerMath.Policy p, int guesses, long pot, int rank,
                                    Map<String, double[]> memo) {
        Terms t = p.terms();
        if (guesses > 0 && (pot >= t.cap() || guesses >= t.maxGuesses())) {
            return new double[] {pot, (double) pot * pot};
        }
        String key = guesses + ":" + pot + ":" + rank;
        double[] known = memo.get(key);
        if (known != null) {
            return known;
        }
        Side s = p.choose(guesses, pot, rank);
        double[] out;
        if (s == null) {
            out = new double[] {pot, (double) pot * pot};
        } else {
            long raised = HigherLowerRun.potIfRight(pot, HigherLowerRun.winners(rank, s), t.r(), t.cap());
            double m1 = 0;
            double m2 = 0;
            for (int next = 2; next <= 14; next++) {
                if (s == Side.HIGHER ? next > rank : next < rank) {
                    double[] m = moments(p, guesses + 1, raised, next, memo);
                    m1 += m[0] / 13;
                    m2 += m[1] / 13;
                }
            }
            out = new double[] {m1, m2};
        }
        memo.put(key, out);
        return out;
    }

    /** The exact per-run spread of back ÷ tokens in, checking the policy earns the DP's value on the way. */
    static double sigma(HigherLowerMath.Policy p) {
        Terms t = p.terms();
        Map<String, double[]> memo = new HashMap<>();
        double m1 = 0;
        double m2 = 0;
        int playable = 0;
        for (int rank = 2; rank <= 14; rank++) {
            if (!HigherLowerRun.anySide(t.in(), rank, t.r(), t.cap())) {
                continue;
            }
            double[] m = moments(p, 0, t.in(), rank, memo);
            m1 += m[0];
            m2 += m[1];
            playable++;
        }
        m1 /= playable;
        m2 /= playable;
        assertEquals(p.rtp().value(), m1 / t.in(), 1e-12, t + ": the policy earns exactly the DP's value");
        return Math.sqrt(m2 - m1 * m1) / t.in();
    }

    private static void soak(HigherLowerSettings.Odds odds, int minRuns, double maxHalfWidth, boolean belowTop,
                             String why) {
        HigherLowerMath.Policy p = HigherLowerMath.policy(odds.terms());
        double sigma = sigma(p);
        int n = (int) Math.max(minRuns, Math.ceil(Math.pow(4 * sigma / maxHalfWidth, 2)));
        double tolerance = 4 * sigma / Math.sqrt(n);
        assertTrue(tolerance <= maxHalfWidth, why + ": 4σ/√n = " + tolerance);
        double realised = play(p, n, SEED + odds.stake());
        assertEquals(p.rtp().value(), realised, tolerance, why + " stake " + odds.stake() + ": " + n
                + " runs must give back what the DP says");
        assertTrue(RtpLimits.inBand(p.rtp()), why + ": the exact value is inside the band");
        if (belowTop) {
            assertTrue(realised < RtpLimits.MAX, why + ": never above the band");
        }
    }

    @Test
    void theShippedTableGivesBackWhatItsDpSays() {
        for (HigherLowerSettings.Odds odds : HigherLowerSettings.defaults().odds()) {
            soak(odds, 1_000_000, 0.005, true, "shipped");
        }
    }

    @Test
    void theClampExtremesGiveBackWhatTheirDpSays() {
        for (double rtp : new double[] {85, 95}) {
            for (HigherLowerSettings.Odds odds : HigherLowerSettings.solve(List.of(10, 20, 50), rtp, 20, 10, 250,
                    "k", null, null)) {
                soak(odds, 200_000, 0.01, rtp < 95, "rtp " + rtp);
            }
        }
    }
}
