package com.dierks.homecraft.games.chance.poker;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 0.57.0: the one five-card evaluator Video Poker deals, draws, settles and counts with. Every one of the 2,598,960
 * hands is evaluated and the totals must be the textbook counts; then the hands that trip evaluators up, one by one:
 * the Ace low and high, no straight round the corner, a low pair, Jacks exactly, the Royal Flush, and the order of the
 * cards never mattering.
 */
class PokerHandTest {

    /** "As", "Th", "2c": rank (2-9, T, J, Q, K, A) then suit (s, h, d, c). */
    static int c(String s) {
        int rank = "23456789TJQKA".indexOf(s.charAt(0));
        int suit = "shdc".indexOf(s.charAt(1));
        return PokerHand.card(rank, suit);
    }

    static int[] hand(String cards) {
        String[] p = cards.split(" ");
        int[] out = new int[p.length];
        for (int i = 0; i < p.length; i++) {
            out[i] = c(p[i]);
        }
        return out;
    }

    static PokerHand of(String cards) {
        return PokerHand.of(hand(cards));
    }

    @Test
    void everyHandFromOneDeckCountsAsTheTextbookSays() {
        long[] n = new long[PokerHand.COUNT];
        for (int a = 0; a < 48; a++) {
            for (int b = a + 1; b < 49; b++) {
                for (int c = b + 1; c < 50; c++) {
                    for (int d = c + 1; d < 51; d++) {
                        for (int e = d + 1; e < 52; e++) {
                            n[PokerHand.ordinalOf(a, b, c, d, e)]++;
                        }
                    }
                }
            }
        }
        assertEquals(4, n[PokerHand.ROYAL_FLUSH.ordinal()]);
        assertEquals(36, n[PokerHand.STRAIGHT_FLUSH.ordinal()]);
        assertEquals(624, n[PokerHand.FOUR_OF_A_KIND.ordinal()]);
        assertEquals(3_744, n[PokerHand.FULL_HOUSE.ordinal()]);
        assertEquals(5_108, n[PokerHand.FLUSH.ordinal()]);
        assertEquals(10_200, n[PokerHand.STRAIGHT.ordinal()]);
        assertEquals(54_912, n[PokerHand.THREE_OF_A_KIND.ordinal()]);
        assertEquals(123_552, n[PokerHand.TWO_PAIR.ordinal()]);
        assertEquals(4 * 84_480, n[PokerHand.JACKS_OR_BETTER.ordinal()], "a pair of J, Q, K or A: 4 of 13 ranks");
        long all = 0;
        for (long x : n) {
            all += x;
        }
        assertEquals(PokerMath.HANDS, all);
        assertEquals(1_302_540 + 9 * 84_480, n[PokerHand.NOTHING.ordinal()], "no pair, or a pair of 2s to 10s");
        assertEquals(java.util.Arrays.toString(n), java.util.Arrays.toString(PokerMath.Tallies.get().handCounts()),
                "the exact count's own tally of every hand agrees");
    }

    @Test
    void theHandsThatTripEvaluatorsUp() {
        assertEquals(PokerHand.ROYAL_FLUSH, of("Ts Js Qs Ks As"));
        assertEquals(PokerHand.ROYAL_FLUSH, of("Ah Kh Th Qh Jh"), "in any order");
        assertEquals(PokerHand.STRAIGHT_FLUSH, of("9d Td Jd Qd Kd"), "king-high: not royal");
        assertEquals(PokerHand.STRAIGHT_FLUSH, of("Ac 2c 3c 4c 5c"), "the steel wheel: the Ace low");
        assertEquals(PokerHand.STRAIGHT, of("Ac 2d 3c 4h 5s"), "the wheel");
        assertEquals(PokerHand.STRAIGHT, of("Th Jd Qc Ks Ah"), "Ace high");
        assertEquals(PokerHand.NOTHING, of("Qh Kd Ac 2s 3h"), "never round the corner");
        assertEquals(PokerHand.NOTHING, of("Kh Ad 2c 3s 4h"), "never round the corner");
        assertEquals(PokerHand.FLUSH, of("2h 7h 9h Jh Kh"));
        assertEquals(PokerHand.FULL_HOUSE, of("Kh Kd Ks 2c 2h"));
        assertEquals(PokerHand.FULL_HOUSE, of("2c Kh 2h Kd Ks"), "in any order");
        assertEquals(PokerHand.FOUR_OF_A_KIND, of("7h 7d 7c 7s Ah"));
        assertEquals(PokerHand.FOUR_OF_A_KIND, of("Ah 7d 7c 7s 7h"), "the odd card first");
        assertEquals(PokerHand.THREE_OF_A_KIND, of("4h 4d 4c Ks 2h"));
        assertEquals(PokerHand.TWO_PAIR, of("4h 4d 9c 9s 2h"));
        assertEquals(PokerHand.TWO_PAIR, of("2h 2d 3c 3s Ah"), "two low pairs still pay");
        assertEquals(PokerHand.JACKS_OR_BETTER, of("Jh Jd 9c 5s 2h"), "Jacks exactly");
        assertEquals(PokerHand.JACKS_OR_BETTER, of("2h Ad 9c As 5h"), "Aces");
        assertEquals(PokerHand.NOTHING, of("Th Td 9c 5s 2h"), "a pair of 10s pays nothing");
        assertEquals(PokerHand.NOTHING, of("2h 2d 9c 5s Kh"), "nor 2s");
        assertEquals(PokerHand.NOTHING, of("2h 4d 6c 8s Th"));
        assertEquals(PokerHand.NOTHING, of("2h 3h 4h 5h 7d"), "four to a straight flush is nothing");
    }

    @Test
    void cardsHaveNamesAndRefuseNonsense() {
        assertEquals("Queen of Hearts", PokerHand.name(c("Qh")));
        assertEquals("10 of Spades", PokerHand.name(c("Ts")));
        assertEquals("A♣", PokerHand.shortName(c("Ac")));
        assertEquals("10♦", PokerHand.shortName(c("Td")));
        assertEquals(14, PokerHand.count(c("As")), "an Ace's stack reads 14");
        assertEquals(2, PokerHand.count(c("2s")));
        assertEquals(true, PokerHand.red(c("2d")));
        assertEquals(false, PokerHand.red(c("2c")));
        assertThrows(IllegalArgumentException.class, () -> PokerHand.card(13, 0));
        assertThrows(IllegalArgumentException.class, () -> PokerHand.of(new int[4]));
        for (PokerHand h : PokerHand.values()) {
            assertEquals(h, PokerHand.byOrdinal(h.ordinal()));
        }
    }
}
