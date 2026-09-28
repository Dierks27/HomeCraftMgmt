package com.dierks.homecraft.games.chance.twentyone;

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
 * Twenty-One's settings and per-stake solve (spec §5.1, §5.4, R1.1, R1.5): the shipped block reads
 * as the defaults, every shipped stake lands inside 85-95 by the R1.1 rule at rtp 85, 90 and 95, a
 * stake that can't is left out with one WARN, a table with no stake left closes the game with one
 * WARN, and the published numbers all come from the one settings object.
 */
class TwentyOneSettingsTest {

    private static Map<String, Object> shipped() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", true);
        m.put("stakes", List.of(5, 10, 20));
        m.put("daily_limit", 30);
        m.put("rtp", 90);
        m.put("natural_bonus", 1.5);
        return m;
    }

    private static TwentyOneSettings parse(Map<String, Object> block, List<String> warns) {
        return TwentyOneSettings.parse(new GamesConfig.Node("games.twenty_one", block, warns::add),
                TwentyOneSettings.defaults());
    }

    @Test
    void theShippedBlockReadsAsTheDefaultsWithoutAWarn() {
        List<String> warns = new ArrayList<>();
        assertEquals(TwentyOneSettings.defaults(), parse(shipped(), warns), "shipped == defaults()");
        assertEquals(List.of(), warns, "and not one WARN");
        assertEquals(TwentyOneSettings.defaults(), parse(new LinkedHashMap<>(), warns), "every key left out");
        assertEquals(List.of(), warns);
    }

    @Test
    void theShippedStakesPayNineElevenAndEighteenForFiveIn() {
        TwentyOneSettings s = TwentyOneSettings.defaults();
        assertEquals(List.of(5, 10, 20), s.playable(), "every shipped stake is playable");
        int[][] expect = {{5, 9, 11, 18}, {10, 18, 22, 36}, {20, 36, 44, 72}};
        for (int[] e : expect) {
            TwentyOneMath.Payouts p = s.odds(e[0]).payouts();
            assertEquals(e[1], p.win(), e[0] + " in: a win");
            assertEquals(e[2], p.twentyOne(), e[0] + " in: a Twenty-One!");
            assertEquals(e[3], p.doubleWin(), e[0] + " in: a doubled win");
            assertEquals(new Ratio(4, 5), s.odds(e[0]).terms().m(), "m = 0.8 at every shipped stake");
            assertEquals(0.8975, s.odds(e[0]).rtp(), 1e-4, "89.75% with the best play");
        }
        assertEquals(89, RtpLimits.wholePercent(s.lowestRtp()), "players read about 89 of every 100");
    }

    @Test
    void everyShippedStakeFollowsTheR11RuleAt85And90And95() {
        for (double rtp : new double[] {85, 90, 95}) {
            List<String> warns = new ArrayList<>();
            List<String> infos = new ArrayList<>();
            List<TwentyOneSettings.Odds> odds = TwentyOneSettings.solve(List.of(5, 10, 20), rtp, 1.5, 250,
                    "games.twenty_one.stakes", warns::add, infos::add);
            assertEquals(List.of(), warns, "rtp " + rtp + ": no stake is left out");
            assertEquals(3, odds.size());
            Ratio target = Ratio.percent(rtp);
            int above = 0;
            for (TwentyOneSettings.Odds o : odds) {
                TwentyOneMath.Solution chosen = TwentyOneMath.solve(o.payouts());
                assertTrue(chosen.inBand(), "rtp " + rtp + " stake " + o.stake() + ": inside 85-95");
                TwentyOneMath.Solution bestBelow = null;
                TwentyOneMath.Solution lowestIn = null;
                for (TwentyOneMath.Candidate c : TwentyOneMath.candidates(o.stake(), new Ratio(3, 2), 250)) {
                    TwentyOneMath.Solution s = TwentyOneMath.solve(c.payouts());
                    if (s.compareTo(target) <= 0 && (bestBelow == null || s.compareTo(bestBelow) > 0)) {
                        bestBelow = s;
                    }
                    if (s.compareTo(RtpLimits.MIN_RATIO) >= 0 && (lowestIn == null || s.compareTo(lowestIn) < 0)) {
                        lowestIn = s;
                    }
                }
                boolean fallback = bestBelow == null || bestBelow.compareTo(RtpLimits.MIN_RATIO) < 0;
                TwentyOneMath.Solution expect = fallback ? lowestIn : bestBelow;
                assertEquals(0, chosen.compareTo(expect), "rtp " + rtp + " stake " + o.stake()
                        + ": the largest at or below the target, else the smallest from 85 up");
                above += fallback ? 1 : 0;
            }
            assertEquals(above, infos.size(), "rtp " + rtp + ": one INFO per stake that lands above its target");
        }
    }

    @Test
    void at85TheSmallestStakeLandsAboveItsTargetWithOneInfoLine() {
        List<String> infos = new ArrayList<>();
        List<TwentyOneSettings.Odds> odds = TwentyOneSettings.solve(List.of(5), 85, 1.5, 250, "k", w -> { },
                infos::add);
        assertEquals(1, infos.size(), infos.toString());
        assertTrue(infos.get(0).startsWith("twenty_one stake 5: 89.7% (target 85 not reachable in whole tokens)"),
                infos.toString());
        assertEquals(0.8975, odds.get(0).rtp(), 1e-4, "5 in can't land between 85 and 89.7");
    }

    @Test
    void aStakeThatCantReachTheBandIsLeftOutWithOneWarn() {
        List<String> warns = new ArrayList<>();
        List<TwentyOneSettings.Odds> odds = TwentyOneSettings.solve(List.of(5, 10, 20), 90, 1.5, 20,
                "games.twenty_one.stakes", warns::add, i -> { });
        assertEquals(1, warns.size(), "a cap of 20 makes a win at 20 in pay only the 20 back: " + warns);
        assertTrue(warns.get(0).startsWith("games.twenty_one.stakes 20 (nearest "), warns.toString());
        assertFalse(odds.stream().anyMatch(o -> o.stake() == 20), "20 in is left out");
        assertFalse(odds.isEmpty(), "the other stakes still play");
        for (TwentyOneSettings.Odds o : odds) {
            assertTrue(o.payouts().win() <= 20 && o.payouts().doubleWin() <= 20, "the cap holds in the solve");
        }
    }

    @Test
    void aTableWithNoStakeLeftClosesTheGameWithOneWarn() {
        List<String> warns = new ArrayList<>();
        List<String> block = new ArrayList<>();
        List<TwentyOneSettings.Odds> odds = TwentyOneSettings.solve(List.of(20), 90, 1.5, 20,
                "games.twenty_one.stakes", warns::add, block::add);
        assertTrue(odds.isEmpty(), "nothing playable");
        assertEquals(1, warns.size(), "exactly one WARN: " + warns);
        assertTrue(warns.get(0).contains("Twenty-One is closed until it is fixed"), warns.toString());

        Map<String, Object> m = shipped();
        m.put("stakes", List.of(20));
        TwentyOneSettings s = TwentyOneSettings.parse(new GamesConfig.Node("games.twenty_one", m, warns::add),
                TwentyOneSettings.defaults());
        assertFalse(s.odds().isEmpty(), "with the shipped max_payout 250, 20 in alone is fine");
    }

    @Test
    void anOutOfRangeBonusIsClampedWithOneWarnAndStillSolves() {
        List<String> warns = new ArrayList<>();
        Map<String, Object> m = shipped();
        m.put("natural_bonus", 5);
        TwentyOneSettings s = parse(m, warns);
        assertEquals(1, warns.size(), warns.toString());
        assertTrue(warns.get(0).startsWith("games.twenty_one.natural_bonus "), warns.toString());
        assertEquals(3.0, s.naturalBonus());
        assertEquals(3, s.odds().size(), "the clamped table still solves");
        assertEquals(new Ratio(3, 1), s.odds().get(0).terms().bonus());
        assertEquals(new Ratio(3, 2), TwentyOneSettings.bonusRatio(1.5));
        assertEquals(new Ratio(5, 4), TwentyOneSettings.bonusRatio(1.25));
    }

    @Test
    void theOddsLinesAndTheFeedChangeWithRtpFromTheOneSettingsObject() {
        Map<String, Object> m = shipped();
        m.put("rtp", 87);
        TwentyOneSettings s87 = parse(m, new ArrayList<>());
        TwentyOneSettings s90 = TwentyOneSettings.defaults();
        List<String> l87 = TwentyOne.oddsLines(s87);
        List<String> l90 = TwentyOne.oddsLines(s90);
        assertNotEquals(l87.get(0), l90.get(0), "the player line follows the computed value");
        assertTrue(l90.get(0).contains("gives back about 89 of every 100 tokens"), l90.get(0));
        assertTrue(l87.get(0).contains("gives back about " + RtpLimits.wholePercent(s87.lowestRtp())), l87.get(0));
        assertEquals(1 + s90.odds().size(), l90.size(), "one player line, then one detail line per stake");
        assertTrue(l90.get(1).contains("89.7%"), "admins read one decimal: " + l90.get(1));

        Captured c87 = new Captured();
        Captured c90 = new Captured();
        TwentyOne.feed(c87, s87);
        TwentyOne.feed(c90, s90);
        assertNotEquals(c87.rtpByStake, c90.rtpByStake, "the feed follows the computed values too");
        for (TwentyOneSettings.Odds o : s87.odds()) {
            assertEquals(o.rtp(), c87.rtpByStake.get(o.stake()), "the feed's value IS the engine's");
        }
        assertEquals(List.of(), c90.paytable, "Twenty-One publishes no paytable");
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Integer>> payouts = (Map<String, Map<String, Integer>>) c90.extra.get("payouts");
        assertEquals(Map.of("win", 9, "twentyOne", 11, "doubleWin", 18), payouts.get("5"),
                "payouts per stake, keyed by the stake");
        assertEquals(List.of("5", "10", "20"), new ArrayList<>(payouts.keySet()), "in stake order");
        assertEquals(30, c90.dailyLimit);
    }

    /** A feed sink that keeps what it was given. */
    static final class Captured implements FeedWriter {
        Map<Integer, Double> rtpByStake;
        List<PayRow> paytable;
        Map<String, ?> extra;
        Integer dailyLimit;
        List<Integer> stakes;

        @Override
        public void chance(String id, String name, List<Integer> stakes, Map<Integer, Double> rtpByStake,
                           Integer dailyLimit, List<PayRow> paytable, String rules, Map<String, ?> extra) {
            this.stakes = stakes;
            this.rtpByStake = rtpByStake;
            this.dailyLimit = dailyLimit;
            this.paytable = paytable;
            this.extra = extra;
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
