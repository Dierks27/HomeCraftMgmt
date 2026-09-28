package com.dierks.homecraft.games.chance.hilo;

import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.games.FeedWriter;
import com.dierks.homecraft.games.RtpLimits;
import com.dierks.homecraft.games.RtpLimits.Ratio;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Higher or Lower's settings and per-stake solve (spec §5.1, §5.6, R1.1, R1.6): the shipped block
 * reads as the defaults, every shipped stake's DP value lands inside 85-95 by the R1.1 rule at rtp
 * 85, 90 and 95, a stake that can't is left out with one WARN, a table with no stake left closes
 * the game with one WARN, and the published numbers all come from the one settings object.
 */
class HigherLowerSettingsTest {

    private static Map<String, Object> shipped() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", true);
        m.put("stakes", List.of(10, 20, 50));
        m.put("daily_limit", 30);
        m.put("rtp", 90);
        m.put("max_multiplier", 20);
        m.put("max_guesses", 10);
        return m;
    }

    private static HigherLowerSettings parse(Map<String, Object> block, List<String> warns) {
        return HigherLowerSettings.parse(new GamesConfig.Node("games.higher_lower", block, warns::add),
                HigherLowerSettings.defaults());
    }

    @Test
    void theShippedBlockReadsAsTheDefaultsWithoutAWarn() {
        List<String> warns = new ArrayList<>();
        assertEquals(HigherLowerSettings.defaults(), parse(shipped(), warns), "shipped == defaults()");
        assertEquals(List.of(), warns, "and not one WARN");
        assertEquals(HigherLowerSettings.defaults(), parse(new LinkedHashMap<>(), warns), "every key left out");
        assertEquals(List.of(), warns);
    }

    @Test
    void theShippedStakesSolveToTheirExactValues() {
        HigherLowerSettings s = HigherLowerSettings.defaults();
        assertEquals(List.of(10, 20, 50), s.playable(), "every shipped stake is playable");
        assertEquals(new HigherLowerRun.Terms(10, 915, 200, 10), s.odds(10).terms(), "10 in: r 0.915, top pot 200");
        assertEquals(new HigherLowerRun.Terms(20, 907, 250, 10), s.odds(20).terms(),
                "20 in: r 0.907, top pot 250 (max_payout is lower than 20 x 20)");
        assertEquals(new HigherLowerRun.Terms(50, 904, 250, 10), s.odds(50).terms(), "50 in: r 0.904");
        assertEquals(641.0 / 715, s.odds(10).rtp(), 1e-12);
        assertEquals(0.9, s.odds(20).rtp(), 1e-12, "exactly 90%: a value equal to the target counts");
        assertEquals(3208.0 / 3575, s.odds(50).rtp(), 1e-12);
        assertEquals(89, RtpLimits.wholePercent(s.lowestRtp()), "players read about 89 of every 100");
    }

    @Test
    void everyShippedStakeFollowsTheR11RuleAt85And90And95() {
        for (double rtp : new double[] {85, 90, 95}) {
            List<String> warns = new ArrayList<>();
            List<String> infos = new ArrayList<>();
            List<HigherLowerSettings.Odds> odds = HigherLowerSettings.solve(List.of(10, 20, 50), rtp, 20, 10, 250,
                    "games.higher_lower.stakes", warns::add, infos::add);
            assertEquals(List.of(), warns, "rtp " + rtp + ": no stake is left out");
            assertEquals(3, odds.size());
            Ratio target = Ratio.percent(rtp);
            int above = 0;
            for (HigherLowerSettings.Odds o : odds) {
                Ratio chosen = HigherLowerMath.rtp(o.terms());
                assertTrue(RtpLimits.inBand(chosen), "rtp " + rtp + " stake " + o.stake() + ": inside 85-95");
                Ratio bestBelow = null;
                Ratio lowestIn = null;
                int largestRWithBest = -1;
                Ratio[] all = HigherLowerMath.achievable(o.stake(), o.terms().cap(), 10);
                for (Ratio v : all) {
                    if (v == null) {
                        continue;
                    }
                    if (v.compareTo(target) <= 0 && (bestBelow == null || v.compareTo(bestBelow) >= 0)) {
                        bestBelow = v;
                    }
                    if (v.compareTo(RtpLimits.MIN_RATIO) >= 0 && (lowestIn == null || v.compareTo(lowestIn) < 0)) {
                        lowestIn = v;
                    }
                }
                boolean fallback = bestBelow == null || bestBelow.compareTo(RtpLimits.MIN_RATIO) < 0;
                Ratio expect = fallback ? lowestIn : bestBelow;
                assertEquals(0, chosen.compareTo(expect), "rtp " + rtp + " stake " + o.stake()
                        + ": the largest at or below the target, else the smallest from 85 up");
                for (int i = 0; i < all.length; i++) {
                    if (all[i] != null && all[i].compareTo(expect) == 0) {
                        largestRWithBest = Math.max(largestRWithBest, HigherLowerMath.rAt(i));
                    }
                }
                assertEquals(largestRWithBest, o.terms().r(), "of the r giving that value, the largest");
                above += fallback ? 1 : 0;
            }
            assertEquals(above, infos.size(), "rtp " + rtp + ": one INFO per stake that lands above its target");
        }
    }

    @Test
    void aStakeThatCantGrowIsLeftOutWithOneWarnAndNoStakeLeftClosesTheGame() {
        List<String> warns = new ArrayList<>();
        List<HigherLowerSettings.Odds> odds = HigherLowerSettings.solve(List.of(10, 20, 50), 90, 20, 10, 50,
                "games.higher_lower.stakes", warns::add, i -> { });
        assertEquals(1, warns.size(), "a max_payout of 50 leaves 50 in nothing to grow: " + warns);
        assertTrue(warns.get(0).startsWith("games.higher_lower.stakes 50 (nearest "), warns.toString());
        assertEquals(List.of(10, 20), odds.stream().map(HigherLowerSettings.Odds::stake).toList());

        warns.clear();
        List<HigherLowerSettings.Odds> none = HigherLowerSettings.solve(List.of(50), 90, 20, 10, 50,
                "games.higher_lower.stakes", warns::add, i -> { });
        assertTrue(none.isEmpty(), "nothing playable");
        assertEquals(1, warns.size(), "exactly one WARN: " + warns);
        assertTrue(warns.get(0).contains("Higher or Lower is closed until it is fixed"), warns.toString());
    }

    @Test
    void anOutOfRangeMaxGuessesIsClampedWithOneWarnAndStillSolves() {
        List<String> warns = new ArrayList<>();
        Map<String, Object> m = shipped();
        m.put("max_guesses", 99);
        HigherLowerSettings s = parse(m, warns);
        assertEquals(50, s.maxGuesses(), "clamped to 50");
        assertEquals(1, warns.size(), warns.toString());
        assertTrue(warns.get(0).startsWith("games.higher_lower.max_guesses "), warns.toString());
        assertEquals(3, s.odds().size(), "every stake still solves");
        assertEquals(50, s.odds().get(0).terms().maxGuesses(), "and plays with the clamped value");
    }

    @Test
    void theOddsLinesAndTheFeedChangeWithRtpFromTheOneSettingsObject() {
        Map<String, Object> m = shipped();
        m.put("rtp", 87);
        HigherLowerSettings s87 = parse(m, new ArrayList<>());
        HigherLowerSettings s90 = HigherLowerSettings.defaults();
        List<String> l87 = HigherLower.oddsLines(s87);
        List<String> l90 = HigherLower.oddsLines(s90);
        assertNotEquals(l87.get(0), l90.get(0), "the player line follows the computed value");
        assertTrue(l90.get(0).contains("the best play gives back about 89 of every 100 tokens"), l90.get(0));
        assertTrue(l87.get(0).contains("gives back about " + RtpLimits.wholePercent(s87.lowestRtp())), l87.get(0));
        assertEquals(1 + s90.odds().size(), l90.size(), "one player line, then one detail line per stake");
        assertTrue(l90.get(1).contains("89.6%") && l90.get(1).contains("r 0.915"), l90.get(1));

        Captured c87 = new Captured();
        Captured c90 = new Captured();
        HigherLower.feed(c87, s87);
        HigherLower.feed(c90, s90);
        assertNotEquals(c87.rtpByStake, c90.rtpByStake, "the feed follows the computed values too");
        for (HigherLowerSettings.Odds o : s87.odds()) {
            assertEquals(o.rtp(), c87.rtpByStake.get(o.stake()), "the feed's value IS the engine's");
        }
        assertEquals(List.of(), c90.paytable, "Higher or Lower publishes no paytable");
        assertEquals(20, c90.extra.get("maxMultiplier"));
        assertEquals(10, c90.extra.get("maxGuesses"));
        assertEquals(List.of("maxMultiplier", "maxGuesses"), new ArrayList<>(c90.extra.keySet()), "in that order");
        assertFalse(c90.rules.isBlank(), "a rules string");
        assertEquals("0.915", HigherLower.rText(915));
        assertEquals("1.000", HigherLower.rText(1000));
    }

    /** A feed sink that keeps what it was given. */
    static final class Captured implements FeedWriter {
        Map<Integer, Double> rtpByStake;
        List<PayRow> paytable;
        Map<String, ?> extra;
        String rules;

        @Override
        public void chance(String id, String name, List<Integer> stakes, Map<Integer, Double> rtpByStake,
                           Integer dailyLimit, List<PayRow> paytable, String rules, Map<String, ?> extra) {
            this.rtpByStake = rtpByStake;
            this.paytable = paytable;
            this.extra = extra;
            this.rules = rules;
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
