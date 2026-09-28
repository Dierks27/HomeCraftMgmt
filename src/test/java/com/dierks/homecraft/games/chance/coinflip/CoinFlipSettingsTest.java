package com.dierks.homecraft.games.chance.coinflip;

import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.games.FeedWriter;
import com.dierks.homecraft.games.RtpLimits;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coin Flip's odds (spec §5.7, R1.8, R1.1): the winner's share of the {@code 2 · stake} put in,
 * solved per stake in whole tokens, with no separate fee — what the winner doesn't get is gone.
 * Pinned at rtp 85, 90 and 95 for every shipped stake; the winner always gets more than they put
 * in; a stake too small for the band, or held down by the cap, is dropped with one WARN. The flip
 * itself is fair, decided by the seed alone, and its show is the same length whoever wins.
 */
class CoinFlipSettingsTest {

    private static CoinFlipSettings withRtp(double rtp) {
        return CoinFlipSettings.parse(new GamesConfig.Node("games.coin_flip", Map.of("rtp", rtp), w -> { }),
                CoinFlipSettings.defaults());
    }

    @Test
    void theWinnerGetsTheCheckedShareAtEveryShippedStake() {
        CoinFlipSettings s = CoinFlipSettings.defaults();
        assertFalse(s.enabled(), "Coin Flip ships off");
        assertEquals(9, s.odds(5).pays(), "9 of the 10 put in at rtp 90");
        assertEquals(18, s.odds(10).pays(), "18 of 20");
        assertEquals(45, s.odds(25).pays(), "45 of 50");
        for (CoinFlipOdds o : s.odds()) {
            assertEquals(0, new RtpLimits.Ratio(9, 10).compareTo(o.ratio()), "each gives back exactly 90%");
            assertEquals(o.pot() / 10, o.pot() - o.pays(), "a tenth of the pot is simply gone");
        }
        assertEquals("The winner gets 18 of the 20 tokens put in", s.odds(10).winnerGets());
        assertEquals("Gives back about 90 of every 100 tokens", s.odds(10).giveBack());
    }

    @Test
    void theSolveRuleHoldsAtRtp85And95() {
        CoinFlipSettings at85 = withRtp(85);
        assertEquals(9, at85.odds(5).pays(), "8 of 10 is 80%, under the band: the smallest in it is 9 (90%)");
        assertEquals(17, at85.odds(10).pays(), "17 of 20 is exactly 85%, and exactly equal counts");
        assertEquals(43, at85.odds(25).pays(), "42 of 50 is 84%: the smallest in the band is 43 (86%)");
        CoinFlipSettings at95 = withRtp(95);
        assertEquals(9, at95.odds(5).pays(), "9.5 isn't whole: 9 (90%) is the largest under 95");
        assertEquals(19, at95.odds(10).pays(), "19 of 20 is exactly 95%");
        assertEquals(47, at95.odds(25).pays(), "47 of 50 is 94%");

        List<String> infos = new ArrayList<>();
        List<String> warns = new ArrayList<>();
        CoinFlipSettings.solve("games.coin_flip", List.of(5, 10, 25), 85, 250, warns::add, infos::add);
        assertEquals(List.of(), warns);
        assertEquals(List.of("coin_flip stake 5: 90.0% (target 85 not reachable in whole tokens)",
                "coin_flip stake 25: 86.0% (target 85 not reachable in whole tokens)"), infos,
                "one INFO per stake that had to land above its target");

        for (double rtp : new double[]{85, 87.5, 90, 92, 95}) {
            RtpLimits.Ratio target = RtpLimits.Ratio.percent(rtp);
            for (int stake = 1; stake <= 200; stake++) {
                CoinFlipOdds o = CoinFlipSettings.solve("x", List.of(stake), rtp, 1000, w -> { }, i -> { })
                        .stream().findFirst().orElse(null);
                if (o == null) {
                    continue;
                }
                assertTrue(RtpLimits.inBand(o.ratio()), "stake " + stake + " at " + rtp + " is inside the band");
                boolean underTarget = o.ratio().compareTo(target) <= 0;
                RtpLimits.Ratio next = new RtpLimits.Ratio(o.pays() + 1, o.pot());
                RtpLimits.Ratio prev = new RtpLimits.Ratio(o.pays() - 1, o.pot());
                if (underTarget) {
                    assertTrue(next.compareTo(target) > 0, "stake " + stake + ": the largest at or under the target");
                } else {
                    assertTrue(prev.compareTo(RtpLimits.MIN_RATIO) < 0,
                            "stake " + stake + ": above the target only when nothing under it is in the band");
                }
                assertTrue(o.pays() > stake, "stake " + stake + ": the winner always gets more than they put in");
            }
        }
    }

    @Test
    void aStakeTooSmallForTheBandIsDroppedWithOneWarn() {
        List<String> warns = new ArrayList<>();
        List<String> infos = new ArrayList<>();
        List<CoinFlipOdds> odds = CoinFlipSettings.solve("games.coin_flip", List.of(1, 10), 90, 250,
                warns::add, infos::add);
        assertEquals(List.of(10), odds.stream().map(CoinFlipOdds::stake).toList(),
                "1 each can only give back 50% or 100% (1 or 2 of 2): it is dropped");
        assertEquals(1, warns.size(), warns.toString());
        assertTrue(warns.get(0).startsWith("games.coin_flip.stakes 1 ") && warns.get(0).contains("50.0% or 100.0%"),
                "the WARN names the stake and the nearest values: " + warns);
        assertEquals(List.of(), infos);

        warns.clear();
        List<CoinFlipOdds> none = CoinFlipSettings.solve("games.coin_flip", List.of(1), 90, 250, warns::add, i -> { });
        assertTrue(none.isEmpty(), "no stake left: the game is closed");
        assertEquals(1, warns.size(), "with one WARN: " + warns);
        assertTrue(warns.get(0).contains("Coin Flip is closed until it is fixed"), warns.toString());
    }

    @Test
    void aCapThatHoldsAStakeDownDropsItWithOneWarnSayingSo() {
        List<String> warns = new ArrayList<>();
        List<String> infos = new ArrayList<>();
        List<CoinFlipOdds> odds = CoinFlipSettings.solve("games.coin_flip", List.of(5, 10, 25), 90, 25,
                warns::add, infos::add);
        assertEquals(List.of(5, 10), odds.stream().map(CoinFlipOdds::stake).toList(),
                "with max_payout 25 the winner of a 50-token pot could get at most half");
        assertEquals(1, warns.size(), warns.toString());
        assertTrue(warns.get(0).startsWith("games.coin_flip.stakes 25 is off: max_payout 25"),
                "it names the stake and the cap: " + warns);
        assertEquals(List.of(), infos);
        assertTrue(odds.stream().allMatch(o -> o.pays() <= 25), "no share passes the cap");
    }

    @Test
    void theShippedBlockReadsAsTheDefaultsWithoutAWord() {
        List<String> warns = new ArrayList<>();
        GamesConfig.Node n = new GamesConfig.Node("games.coin_flip", Map.of("enabled", false,
                "stakes", List.of(5, 10, 25), "daily_limit", 5, "pair_daily_limit", 2, "rtp", 90,
                "max_distance", 32, "invite_seconds", 60), warns::add);
        assertEquals(CoinFlipSettings.defaults(), CoinFlipSettings.parse(n, CoinFlipSettings.defaults()));
        assertEquals(List.of(), warns);
    }

    @Test
    void theFlipIsFairAndDecidedByTheSeedAlone() {
        SplittableRandom seeds = new SplittableRandom(0xC01F_11B5L);
        int n = 400_000;
        int inviter = 0;
        for (int i = 0; i < n; i++) {
            long seed = seeds.nextLong();
            boolean w = CoinFlipRules.inviterWins(seed);
            assertEquals(w, CoinFlipRules.inviterWins(seed), "the same seed, the same winner");
            if (w) {
                inviter++;
            }
        }
        double share = (double) inviter / n;
        double bound = 4 * 0.5 / Math.sqrt(n);
        assertTrue(Math.abs(share - 0.5) <= bound, "each player has a 1 in 2 chance: the inviter won " + share);
    }

    @Test
    void theShowIsTheSameLengthWhoeverWinsAndLandsOnTheWinner() {
        for (int frames : new int[]{1, 4, 12}) {
            boolean[] a = CoinFlipRules.faces(true, frames);
            boolean[] b = CoinFlipRules.faces(false, frames);
            assertEquals(frames, a.length, "the same number of turns whoever wins");
            assertEquals(frames, b.length);
            assertTrue(a[frames - 1], "it ends on the inviter's side when they won");
            assertFalse(b[frames - 1], "and on the other side when they didn't");
            for (int i = 1; i < frames; i++) {
                assertTrue(a[i] != a[i - 1], "it turns over every frame, nothing lingers");
            }
        }
        for (int[] plan : new int[][]{{12, 30}, {4, 28}}) {
            long[] at = CoinFlipRules.schedule(plan[0], plan[1]);
            assertEquals(plan[0], at.length, "one tick per turn");
            assertEquals(plan[1], at[at.length - 1], "a fixed stop tick, under two seconds");
            long prev = 0;
            long gap = 1;
            for (long t : at) {
                assertTrue(t - prev >= gap, "the turns only slow down: " + java.util.Arrays.toString(at));
                gap = t - prev;
                prev = t;
            }
        }
    }

    @Test
    void theSameSettingsObjectFeedsTheOddsLineAndTheWebsite() {
        CoinFlipSettings s = withRtp(87);
        List<String> lines = CoinFlip.oddsLines(s);
        CoinFlipOdds low = s.lowest();
        assertEquals("Coin Flip — gives back about " + RtpLimits.wholePercent(low.rtp()) + " of every 100 tokens"
                + " · 5 plays a day", lines.get(0));
        assertEquals("Coin Flip at 10 tokens each: 85.0% - the winner gets 17 of 20, a 1 in 2 chance", lines.get(2),
                "admins get each stake to one decimal");
        List<FeedWriter.PayRow> rows = new ArrayList<>();
        List<Map<Integer, Double>> rtps = new ArrayList<>();
        CoinFlip.feed(s, new FeedWriter() {
            @Override
            public void chance(String id, String name, List<Integer> stakes, Map<Integer, Double> rtpByStake,
                               Integer dailyLimit, List<PayRow> paytable, String rules, Map<String, ?> extra) {
                rows.addAll(paytable);
                rtps.add(rtpByStake);
                assertEquals("coin_flip", id);
                assertEquals(5, dailyLimit);
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
        });
        assertEquals(3, rows.size(), "one row per stake");
        for (FeedWriter.PayRow row : rows) {
            CoinFlipOdds o = s.odds(row.stake());
            assertEquals(o.pays(), row.pays(), "the feed pays what the flip pays");
            assertEquals(0.5, row.chance());
            assertEquals("win the flip", row.combo());
            assertNull(row.spaces());
            assertEquals(o.rtp(), rtps.get(0).get(row.stake()));
        }
        assertEquals("5-25", CoinFlip.range(s.open()));
    }
}
