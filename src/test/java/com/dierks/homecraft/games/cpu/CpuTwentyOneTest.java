package com.dierks.homecraft.games.cpu;

import com.dierks.homecraft.games.chance.twentyone.TwentyOneHand.Card;
import com.dierks.homecraft.games.cpu.CpuTwentyOne.Move;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How an NPC plays a hand at the Twenty-One table: basic play (hard and soft totals, no splits), hard always by the
 * book, medium off it now and then, easy much more often; a careful one never doubles, and nobody doubles when a
 * Double isn't open.
 */
class CpuTwentyOneTest {

    private static Card c(int rank) {
        return new Card(rank, 0);
    }

    private static List<Card> hand(int... ranks) {
        return java.util.Arrays.stream(ranks).mapToObj(CpuTwentyOneTest::c).toList();
    }

    @Test
    void theBookOnHardTotals() {
        assertEquals(Move.HIT, CpuTwentyOne.book(hand(3, 5), c(6), true, false), "8: hit");
        assertEquals(Move.DOUBLE, CpuTwentyOne.book(hand(4, 5), c(5), true, false), "9 against a 5: double");
        assertEquals(Move.HIT, CpuTwentyOne.book(hand(4, 5), c(2), true, false), "9 against a 2: hit");
        assertEquals(Move.DOUBLE, CpuTwentyOne.book(hand(6, 4), c(9), true, false), "10 against a 9: double");
        assertEquals(Move.HIT, CpuTwentyOne.book(hand(6, 4), c(10), true, false), "10 against a ten: hit");
        assertEquals(Move.DOUBLE, CpuTwentyOne.book(hand(6, 5), c(10), true, false), "11 against a ten: double");
        assertEquals(Move.HIT, CpuTwentyOne.book(hand(6, 5), c(1), true, false), "11 against an Ace: hit");
        assertEquals(Move.DOUBLE, CpuTwentyOne.book(hand(6, 5), c(1), true, true), "a bold one doubles it");
        assertEquals(Move.HIT, CpuTwentyOne.book(hand(10, 2), c(3), true, false), "12 against a 3: hit");
        assertEquals(Move.STAND, CpuTwentyOne.book(hand(10, 2), c(4), true, false), "12 against a 4: stand");
        assertEquals(Move.STAND, CpuTwentyOne.book(hand(10, 6), c(6), true, false), "16 against a 6: stand");
        assertEquals(Move.HIT, CpuTwentyOne.book(hand(10, 6), c(7), true, false), "16 against a 7: hit");
        assertEquals(Move.STAND, CpuTwentyOne.book(hand(10, 7), c(1), true, false), "17: stand");
        assertEquals(Move.HIT, CpuTwentyOne.book(hand(6, 5), c(5), false, false), "no Double open: hit instead");
    }

    @Test
    void theBookOnSoftTotals() {
        assertEquals(Move.DOUBLE, CpuTwentyOne.book(hand(1, 2), c(5), true, false), "soft 13 against a 5: double");
        assertEquals(Move.HIT, CpuTwentyOne.book(hand(1, 2), c(4), true, false), "soft 13 against a 4: hit");
        assertEquals(Move.DOUBLE, CpuTwentyOne.book(hand(1, 7), c(4), true, false), "soft 18 against a 4: double");
        assertEquals(Move.STAND, CpuTwentyOne.book(hand(1, 7), c(4), false, false), "and stands when it can't");
        assertEquals(Move.STAND, CpuTwentyOne.book(hand(1, 7), c(8), true, false), "soft 18 against an 8: stand");
        assertEquals(Move.HIT, CpuTwentyOne.book(hand(1, 7), c(10), true, false), "soft 18 against a ten: hit");
        assertEquals(Move.STAND, CpuTwentyOne.book(hand(1, 8), c(10), true, false), "soft 19: stand");
        assertEquals(Move.HIT, CpuTwentyOne.book(hand(1, 2, 3), c(10), false, false), "soft 16, three cards: hit");
    }

    /** How often each level leaves the book over many random hands (out of {@code n}). */
    private static int offBook(CpuLevel level, int n) {
        SplittableRandom deal = new SplittableRandom(42);
        SplittableRandom rng = new SplittableRandom(7);
        int off = 0;
        for (int i = 0; i < n; i++) {
            List<Card> cards = hand(1 + deal.nextInt(13), 1 + deal.nextInt(13));
            Card up = c(1 + deal.nextInt(13));
            Move book = CpuTwentyOne.book(cards, up, true, false);
            Move m = CpuTwentyOne.decide(cards, up, true, level, CpuPersonality.CHATTY, rng);
            if (m != book) {
                off++;
            }
        }
        return off;
    }

    @Test
    void eachLevelLeavesTheBookAsOftenAsItShould() {
        int n = 10_000;
        int easy = offBook(CpuLevel.EASY, n);
        int medium = offBook(CpuLevel.MEDIUM, n);
        int hard = offBook(CpuLevel.HARD, n);
        assertEquals(0, hard, "hard always plays the book");
        assertTrue(medium > 0 && medium < easy, "medium leaves it less often than easy: " + medium + " vs " + easy);
        assertTrue(easy > n / 5, "easy leaves it often: " + easy);
    }

    @Test
    void aCarefulOneNeverDoublesAndNobodyDoublesWhenItIsntOpen() {
        SplittableRandom rng = new SplittableRandom(3);
        SplittableRandom deal = new SplittableRandom(4);
        for (int i = 0; i < 5_000; i++) {
            List<Card> cards = hand(1 + deal.nextInt(13), 1 + deal.nextInt(13));
            Card up = c(1 + deal.nextInt(13));
            for (CpuLevel level : CpuLevel.values()) {
                assertNotEquals(Move.DOUBLE, CpuTwentyOne.decide(cards, up, true, level, CpuPersonality.CAREFUL, rng));
                for (CpuPersonality p : CpuPersonality.values()) {
                    assertNotEquals(Move.DOUBLE, CpuTwentyOne.decide(cards, up, false, level, p, rng));
                }
            }
        }
    }

    @Test
    void thePretendStakeFollowsThePersonality() {
        List<Integer> stakes = List.of(5, 10, 25);
        assertEquals(5, CpuTwentyOne.stake(stakes, CpuPersonality.CAREFUL));
        assertEquals(25, CpuTwentyOne.stake(stakes, CpuPersonality.BOLD));
        assertEquals(10, CpuTwentyOne.stake(stakes, CpuPersonality.CHATTY));
        assertEquals(0, CpuTwentyOne.stake(List.of(), CpuPersonality.BOLD), "nothing on offer");
    }
}
