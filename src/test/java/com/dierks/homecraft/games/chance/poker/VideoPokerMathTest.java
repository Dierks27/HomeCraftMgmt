package com.dierks.homecraft.games.chance.poker;

import com.dierks.homecraft.games.RtpLimits;
import com.dierks.homecraft.games.RtpLimits.Ratio;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.57.0: Video Poker's return is proved, not estimated. The exact count ({@link PokerMath}) over every deal, hold and
 * draw:
 *
 * <ul>
 *   <li>reproduces the published figures of the well-known tables (9/6 Jacks or Better: 99.5439%; 6/5: 94.9961%),
 *       so the counting itself is right;</li>
 *   <li>agrees, hold by hold, with counting each draw one by one ({@link PokerMath#holdSums}) on random hands, so the
 *       shortcut (tallies and inclusion-exclusion over the discards) is exact;</li>
 *   <li>gives the shipped table exactly the return the game publishes ({@link VideoPokerSettings#KNOWN}): 93.476...
 *       of every 100 at 1 and 2 tokens, 93.390... at 5 (the Royal Flush held to the 250-token cap), all inside 85-95.</li>
 * </ul>
 */
class VideoPokerMathTest {

    private static final int[] NINE_SIX = {0, 1, 2, 3, 4, 6, 9, 25, 50, 800};
    private static final int[] SIX_FIVE = {0, 1, 2, 3, 4, 5, 6, 25, 50, 800};
    private static final int[] SHIPPED = {0, 1, 2, 3, 4, 5, 6, 25, 50, 100};

    @Test
    void theCountReproducesThePublishedFiguresOfTheWellKnownTables() {
        Ratio nineSix = PokerMath.exactReturn(NINE_SIX, 1);
        assertEquals(0.995439, nineSix.value(), 0.0000005, "9/6 Jacks or Better: 99.5439% (the published figure)");
        Ratio sixFive = PokerMath.exactReturn(SIX_FIVE, 1);
        assertEquals(0.949961, sixFive.value(), 0.0000005, "6/5 Jacks or Better: 94.9961%");
        assertEquals(nineSix, PokerMath.exactReturn(new int[] {0, 5, 10, 15, 20, 30, 45, 125, 250, 4000}, 5),
                "five coins paying five times as much: the same return");
    }

    @Test
    void theShortcutAgreesWithCountingEveryDrawOneByOne() {
        SplittableRandom r = new SplittableRandom(57);
        PokerMath.Tallies t = PokerMath.Tallies.get();
        for (int n = 0; n < 12; n++) {
            int[] deck = PokerDeal.deck(r.nextLong());
            int[] hand = Arrays.copyOf(deck, 5);
            Arrays.sort(hand);
            long[] direct = PokerMath.holdSums(hand, SHIPPED);
            long[] counted = t.holdTotals(hand, SHIPPED);
            assertArrayEquals(direct, counted, "every hold of " + Arrays.toString(hand));
        }
        int[] royalDraw = {PokerHandTest.c("Ts"), PokerHandTest.c("Js"), PokerHandTest.c("Qs"), PokerHandTest.c("Ks"),
                PokerHandTest.c("2d")};
        Arrays.sort(royalDraw);
        assertArrayEquals(PokerMath.holdSums(royalDraw, SHIPPED), t.holdTotals(royalDraw, SHIPPED),
                "four to a Royal Flush");
    }

    @Test
    void theBestHoldIsWhatAnyPlayerWouldCheck() {
        // a dealt Royal Flush is kept whole
        int[] royal = PokerHandTest.hand("Ts Js Qs Ks As");
        assertEquals(31, PokerMath.bestHold(royal, SHIPPED));
        // a pair of Kings with junk: keep the Kings
        int[] kings = PokerHandTest.hand("Kh 4d Kc 9s 2h");
        assertEquals(0b00101, PokerMath.bestHold(kings, SHIPPED));
        // four to a Royal beats a made flush... only with a big enough Royal: at 800 it does, at 100 the flush stays
        int[] fourToRoyal = PokerHandTest.hand("Ts Js Qs Ks 3s");
        assertEquals(0b01111, PokerMath.bestHold(fourToRoyal, NINE_SIX), "9/6: break the flush for the Royal");
        assertEquals(0b11111, PokerMath.bestHold(fourToRoyal, SHIPPED), "a Royal of 100: keep the flush");
    }

    @Test
    void theShippedTableGivesBackExactlyWhatTheGamePublishes() {
        for (Map.Entry<String, Ratio> known : VideoPokerSettings.KNOWN.entrySet()) {
            String[] parts = known.getKey().split("/");
            int[] prizes = Arrays.stream(parts[0].split(",")).mapToInt(Integer::parseInt).toArray();
            assertEquals(known.getValue(), PokerMath.exactReturn(prizes, Integer.parseInt(parts[1])),
                    known.getKey() + ": the published number is the count");
            assertTrue(RtpLimits.inBand(known.getValue()), known.getKey() + " is inside 85-95");
        }
        VideoPokerSettings d = VideoPokerSettings.defaults();
        assertEquals(List.of(1, 2, 5), d.open(), "every shipped stake is offered");
        assertEquals(new Ratio(11_763_161_728L, 12_584_110_175L), d.odds(1).exact());
        assertEquals(new Ratio(11_763_161_728L, 12_584_110_175L), d.odds(2).exact());
        assertEquals(new Ratio(310_261_386_467L, 332_220_508_620L), d.odds(5).exact(),
                "5 tokens: the Royal Flush is held to the 250-token cap");
        assertEquals(250, d.odds(5).prize(PokerHand.ROYAL_FLUSH));
        assertEquals(0.93476, d.odds(1).rtp(), 0.00001);
        assertEquals(93, RtpLimits.wholePercent(d.lowestRtp()), "the screen says about 93 of every 100");
        // and a table that isn't known is counted to the same answer
        assertEquals(d.odds(1).exact(), PokerMath.exactReturn(SHIPPED, 1));
    }

    @Test
    void aStakeOrTableOutsideTheBandIsNeverOffered() {
        java.util.List<String> warns = new java.util.ArrayList<>();
        Map<String, Integer> nineSix = new java.util.LinkedHashMap<>(VideoPokerSettings.shippedPays());
        nineSix.put("royal_flush", 800);
        nineSix.put("full_house", 9);
        nineSix.put("flush", 6);
        List<VideoPokerSettings.Odds> odds = VideoPokerSettings.solve("games.video_poker", List.of(1),
                nineSix, 100_000, warns::add);
        assertTrue(odds.isEmpty(), "9/6 gives back 99.5: past the band");
        assertEquals(1, warns.size(), warns.toString());
        assertTrue(warns.get(0).contains("closed"), warns.get(0));
        // the shipped table at $50 under the Parlor's $500 cap: four of a kind is held to 10 times, still in the band
        VideoPokerSettings.Odds fifty = VideoPokerSettings.odds(VideoPokerSettings.shippedPays(), 50, 500);
        assertEquals(500, fifty.prize(PokerHand.FOUR_OF_A_KIND));
        assertTrue(fifty.capped(PokerHand.FOUR_OF_A_KIND, 25));
        assertEquals(0.893886, fifty.rtp(), 0.000001);
        assertTrue(RtpLimits.inBand(fifty.exact()));
    }

    @Test
    void aTableIsKeyedInLowestTerms() {
        assertEquals("0,1,2,3,4,5,6,25,50,100/1", VideoPokerSettings.key(new int[] {0, 5, 10, 15, 20, 25, 30, 125,
                250, 500}, 5));
        assertEquals("0,1,2,3,4,5,6,25,50,50/1", VideoPokerSettings.key(new int[] {0, 5, 10, 15, 20, 25, 30, 125,
                250, 250}, 5));
    }
}
