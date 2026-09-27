package com.dierks.homecraft.market.sim;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Config can make the live market calmer, never wilder: two ways a "calmer-looking" edit used to
 * hand players money, each pinned on the 90-day soak ({@link MarketSimulatorSoakTest#soak}) of
 * the shipped catalog.
 *
 * <ul>
 *   <li><b>A one-sided band</b> ({@code max_down_percent: 0}, review #4) clipped every dip and
 *       kept every rise: the mean multiplier sat at 1.021-1.025, about +2.3% on every sale, and
 *       no DEAL ever ran. The band is now symmetric (the narrower side for both), so the mean
 *       stays at 1.</li>
 *   <li><b>A fast drift</b> ({@code half_life_hours: 0..6}, or a big {@code lively_percent},
 *       review #5) moved prices by several percent within a day, so a buy at the day's low and a
 *       sale at its high beat the 10.5% round trip (at 6 h on 55 of 445 iron-days; at 0 on most
 *       days), with no event cap to stop ops. The half-life is now at least 24 h and the drift's
 *       speed is locked to the shipped settings at their liveliest.</li>
 * </ul>
 */
class CalmerNeverWilderSoakTest {

    private static final long[] SEEDS = {1L, 2L, 3L};
    /** Buy at the ask, sell at the bid: {@code (1 + 0.05) / (1 - 0.05)}. */
    private static final double ROUND_TRIP = 1.05 / 0.95;

    private static SimSettings band(double maxUp, double maxDown) {
        SimSettings d = SimSettings.defaults();
        return new SimSettings(true, d.tickMinutes(), d.maxCatchupHours(), maxUp, maxDown, d.keepDays(), d.drift(),
                d.hot(), d.deal(), d.slotsPerItems(), d.cooldownDays(), d.popularWeight(), d.news(), d.announce(),
                d.headlines(), d.samePlural(), d.seasons(), d.real());
    }

    /** Drift alone (no events, no seasons), with these drift sizes and half-life. */
    private static SimSettings drift(double calm, double lively, double halfLife) {
        SimSettings q = MarketSimulatorSoakTest.driftOnly();
        return new SimSettings(true, q.tickMinutes(), q.maxCatchupHours(), q.maxUpPercent(), q.maxDownPercent(),
                q.keepDays(), new SimSettings.Drift(8, calm, lively, 10, halfLife), q.hot(), q.deal(),
                q.slotsPerItems(), q.cooldownDays(), q.popularWeight(), q.news(), q.announce(), q.headlines(),
                q.samePlural(), q.seasons(), q.real());
    }

    @Test
    void narrowingOneSideNeverMakesTheMoodLeanOneWay() {
        for (double[] b : new double[][] {{25, 0}, {0, 25}}) {
            SimSettings s = band(b[0], b[1]);
            for (long seed : SEEDS) {
                MarketSimulatorSoakTest.Soak r = MarketSimulatorSoakTest.soak(seed, s);
                String at = "max_up " + b[0] + " max_down " + b[1] + " seed " + seed;
                assertEquals(1.0, r.minM(), at + ": no dip");
                assertEquals(1.0, r.maxM(), at + ": and no one-way rise either");
                assertEquals(0, r.hotStarts() + r.dealStarts(), at + ": nothing fits a zero band");
            }
        }
        for (double[] b : new double[][] {{25, 10}, {10, 25}}) {
            SimSettings s = band(b[0], b[1]);
            for (long seed : SEEDS) {
                MarketSimulatorSoakTest.Soak r = MarketSimulatorSoakTest.soak(seed, s);
                String at = "max_up " + b[0] + " max_down " + b[1] + " seed " + seed;
                assertTrue(r.minM() >= 0.90 - 1e-12 && r.maxM() <= 1.10 + 1e-12,
                        at + ": inside [0.90, 1.10], M " + r.minM() + ".." + r.maxM());
                assertTrue(r.meanM() >= 0.985 && r.meanM() <= 1.015, at + ": mean M " + r.meanM());
                assertTrue(r.hotStarts() > 0 && r.dealStarts() > 0, at + ": both ways still run");
            }
        }
    }

    @Test
    void noDriftSettingBringsBackSameDayScalping() {
        // {calm_percent, lively_percent, half_life_hours}: the finding's settings and wilder ones.
        List<double[]> wild = List.of(
                new double[] {1.5, 3.0, 0},
                new double[] {1.5, 3.0, 1},
                new double[] {1.5, 3.0, 6},
                new double[] {30, 30, 66},
                new double[] {30, 30, 0});
        for (double[] c : wild) {
            SimSettings s = drift(c[0], c[1], c[2]);
            int days = 0;
            int beaten = 0;
            double biggestTick = 0;
            for (long seed : SEEDS) {
                MarketSimulatorSoakTest.Soak r = MarketSimulatorSoakTest.soak(seed, s);
                int perDay = (int) (SimMath.DAY_MS / s.tickMs());
                for (String id : MarketSimulatorSoakTest.TRADABLE) {
                    double[] p = r.prices().get(id);
                    for (int k = 1; k < p.length; k++) {
                        biggestTick = Math.max(biggestTick, Math.abs(p[k] / p[k - 1] - 1));
                    }
                    for (int start = 0; start + perDay <= p.length; start += perDay) {
                        days++;
                        double low = Double.MAX_VALUE;
                        for (int k = start; k < start + perDay; k++) {
                            low = Math.min(low, p[k]);
                            if (p[k] / low > ROUND_TRIP) {
                                beaten++;
                                break;
                            }
                        }
                    }
                }
            }
            String at = String.format(Locale.ROOT, "calm %.1f lively %.1f half-life %.0fh", c[0], c[1], c[2]);
            System.out.printf(Locale.ROOT, "%s: biggest tick move %.3f%%, days beating the round trip %d/%d%n",
                    at, biggestTick * 100, beaten, days);
            assertTrue(biggestTick < 0.01, at + ": the drift never jumps 1% in a tick (" + biggestTick + ")");
            assertTrue(beaten * 100 <= days, at + ": a same-day buy-low/sell-high beat the round trip on "
                    + beaten + " of " + days + " item-days");
        }
    }
}
