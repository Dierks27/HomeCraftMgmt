package com.dierks.homecraft.games.chance.wheel;

import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.games.ChanceRounds;
import com.dierks.homecraft.games.FeedWriter;
import com.dierks.homecraft.games.RtpLimits;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Wheel's play path (spec §5.2, §5.5, R1.2, R1.23): a spin is a pure function of its seed,
 * lands on every space equally often, and over a long seeded soak gives back what the solve
 * published; and the screen's words, the {@code /hcm arcade odds} line and the website's numbers
 * all come from the one settings object the spin plays with.
 *
 * <p>Soak seeds are constants, never tuned; each soak is sized so {@code 4σ/√n} is at most half a
 * point (a point at the clamp extremes), with σ the exact per-spin spread from the prizes.
 */
class WheelEngineTest {

    private static final long SOAK_SEED = 0x5EED_1E57_2026L;

    private static WheelSettings withRtp(double rtp) {
        return WheelSettings.parse(new GamesConfig.Node("games.wheel", Map.of("rtp", rtp), w -> { }),
                WheelSettings.defaults());
    }

    @Test
    void aSpinIsDecidedByItsSeedAlone() {
        WheelSettings s = WheelSettings.defaults();
        for (long seed : new long[]{0, 1, -7, 123_456_789L, Long.MAX_VALUE}) {
            WheelSpin a = s.decide(seed, 10);
            WheelSpin b = s.decide(seed, 10);
            assertEquals(a, b, "the same seed lands on the same space with the same prize");
            assertEquals(s.odds(10).prize(a.space()), a.prize(), "the prize is the tile's prize for that space");
            assertEquals(a.prize(), s.payout(a), "what is paid is the prize");
            assertEquals(a.data(), s.data(a), "and the round stores the spin's own data");
            assertTrue(a.data().startsWith("v=1;space=" + a.space() + ";"), a.data());
        }
        assertThrows(IllegalArgumentException.class, () -> s.decide(1, 7), "a stake the wheel doesn't offer");
    }

    @Test
    void everySpaceIsJustAsLikely() {
        WheelSettings s = WheelSettings.defaults();
        SplittableRandom seeds = new SplittableRandom(SOAK_SEED);
        int n = 240_000;
        int[] counts = new int[24];
        for (int i = 0; i < n; i++) {
            counts[s.decide(seeds.nextLong(), 5).space()]++;
        }
        double expected = n / 24.0;
        double chi = 0;
        for (int c : counts) {
            chi += (c - expected) * (c - expected) / expected;
        }
        // 23 degrees of freedom: 60 is far out in the tail (p < 0.0001) for a fair draw.
        assertTrue(chi < 60, "the landing space is uniform over the 24 spaces (chi-square " + chi + ")");
    }

    @Test
    void aLongSoakGivesBackWhatTheSolvePublished() {
        soak(withRtp(90), 0.005);
        soak(withRtp(85), 0.01);
        soak(withRtp(95), 0.01);
    }

    private static void soak(WheelSettings s, double tolerance) {
        SplittableRandom seeds = new SplittableRandom(SOAK_SEED);
        for (WheelOdds o : s.odds()) {
            double mean = 0;
            double sq = 0;
            for (int p : o.prizes()) {
                double x = (double) p / o.stake();
                mean += x / 24;
                sq += x * x / 24;
            }
            double sd = Math.sqrt(sq - mean * mean);
            long floor = tolerance <= 0.005 ? 1_000_000 : 200_000;
            long n = Math.max(floor, (long) Math.ceil(Math.pow(4 * sd / tolerance, 2)));
            long back = 0;
            for (long i = 0; i < n; i++) {
                back += s.payout(s.decide(seeds.nextLong(), o.stake()));
            }
            double realised = (double) back / ((double) n * o.stake());
            double bound = 4 * sd / Math.sqrt(n);
            assertEquals(o.rtp(), mean, 1e-12, "the exact return is the mean prize over the ring");
            assertTrue(Math.abs(realised - o.rtp()) <= bound, "stake " + o.stake() + " at rtp " + s.rtp()
                    + ": realised " + realised + " vs exact " + o.rtp() + " over " + n + " spins (bound " + bound + ")");
            // A stake solved to exactly 95.0% lands either side of the band's top by noise alone, so
            // the check is that no soak is ever significantly above it.
            assertTrue(realised < RtpLimits.MAX + bound, "stake " + o.stake() + " never gives back more than the "
                    + "band allows: " + realised);
        }
    }

    @Test
    void theSameSettingsObjectFeedsTheScreenTheOddsLineAndTheWebsite() {
        for (double rtp : new double[]{87, 90}) {
            WheelSettings s = withRtp(rtp);
            WheelOdds low = s.lowest();
            List<String> lines = Wheel.oddsLines(s);
            assertEquals("The Wheel — gives back about " + RtpLimits.wholePercent(low.rtp()) + " of every 100 tokens"
                    + " · 30 plays a day", lines.get(0), "the player's line is the lowest stake's computed number");
            for (WheelOdds o : s.odds()) {
                assertEquals("Gives back about " + RtpLimits.wholePercent(o.rtp()) + " of every 100 tokens",
                        o.giveBack(), "the screen shows the computed number for the chosen stake, floored");
                assertTrue(lines.contains("The Wheel at " + o.stake() + " tokens: " + RtpLimits.tenthPercent(o.rtp())
                        + "% - " + String.join(", ", o.distinct().stream().map(p -> o.plain(p) + " on " + o.spaces(p))
                        .toList()) + " (of 24 spaces)"), "admins get each stake to one decimal: " + lines);
            }
            Capture feed = new Capture();
            Wheel.feed(s, feed);
            assertEquals("wheel", feed.id);
            assertEquals(s.open(), feed.stakes);
            for (WheelOdds o : s.odds()) {
                assertEquals(o.rtp(), feed.rtpByStake.get(o.stake()), "the feed's number is the engine's own");
                int spaces = 0;
                for (FeedWriter.PayRow row : feed.rows) {
                    if (row.stake() == o.stake()) {
                        spaces += row.spaces();
                        assertNull(row.chance(), "the Wheel counts spaces, not chances");
                        assertEquals(24, row.of());
                        assertEquals(o.spaces((int) row.pays()), row.spaces(), "each row counts its spaces exactly");
                        assertEquals(o.plain((int) row.pays()), row.combo());
                    }
                }
                assertEquals(24, spaces, "each stake's rows add up to the whole ring, nothing and tokens back included");
            }
            assertEquals(30, feed.dailyLimit);
        }
        WheelSettings at87 = withRtp(87);
        WheelSettings at90 = withRtp(90);
        assertTrue(at87.lowest().rtp() < at90.lowest().rtp(), "changing rtp changes the one object everything reads");
    }

    @Test
    void theLedgerNeverCallsTokensBackAWin() {
        WheelOdds o = WheelSettings.defaults().odds(10);
        assertEquals("The Wheel: 10 back", ChanceRounds.payoutDetail(Wheel.NAME, 10, 10));
        assertEquals("The Wheel: won 17", ChanceRounds.payoutDetail(Wheel.NAME, 10, 17));
        assertEquals(WheelOdds.Result.NOTHING, o.result(0));
        assertEquals("&7Nothing", o.label(0));
        assertEquals("&a17 tokens", o.label(17));
        assertEquals("3 of 24 spaces", o.odds(17));
        List<String> how = o.howItPays();
        assertEquals("&a44 tokens &8- &71 of 24 spaces", how.get(0), "biggest first");
        assertEquals("&7Nothing &8- &713 of 24 spaces", how.get(4));
        assertEquals("&7Gives back about 89 of every 100 tokens.", how.get(5));
    }

    @Test
    void theHelpersSayTheDayPlainly() {
        assertEquals("Today: 35 of 100 tokens", Wheel.todayLine(35, 100));
        assertEquals("Today: 35 tokens", Wheel.todayLine(35, -1), "no limit at all");
        assertEquals("no spins left today", Wheel.blocker(10, 500, 0, 0, 100), "the day's spins come first");
        assertEquals("over your limit today", Wheel.blocker(10, 500, 3, 95, 100));
        assertEquals("need 3 more tokens", Wheel.blocker(10, 7, 3, 0, -1));
        assertNull(Wheel.blocker(10, 10, 1, 90, 100), "exactly at the limit and the balance is fine");
        assertEquals("5-20", Wheel.range(List.of(5, 10, 20)));
        assertEquals("10", Wheel.range(List.of(10)));
    }

    /** Records what a game writes to the feed. */
    static final class Capture implements FeedWriter {
        String id;
        List<Integer> stakes;
        Map<Integer, Double> rtpByStake;
        Integer dailyLimit;
        List<PayRow> rows = new ArrayList<>();

        @Override
        public void chance(String id, String name, List<Integer> stakes, Map<Integer, Double> rtpByStake,
                           Integer dailyLimit, List<PayRow> paytable, String rules, Map<String, ?> extra) {
            this.id = id;
            this.stakes = stakes;
            this.rtpByStake = rtpByStake;
            this.dailyLimit = dailyLimit;
            this.rows = paytable;
        }

        @Override
        public void cabinet(String id, String name, String board, String unit, boolean lowerIsBetter, Long best,
                            String holder) {
        }

        @Override
        public void course(String id, String name, String kind, String tier, Long recordMs, Long recordAt,
                           String holder) {
        }

        @Override
        public void golf(String id, String name, int holes, int par, Integer recordStrokes, Long recordAt,
                         String holder) {
        }
    }
}
