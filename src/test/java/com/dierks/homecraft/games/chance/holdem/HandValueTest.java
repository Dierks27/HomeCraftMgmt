package com.dierks.homecraft.games.chance.holdem;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.57.0: Texas Hold'em's hand evaluator. Every category in order, the kickers that break a tie, the wheel (the Ace
 * low only in A-2-3-4-5), the best five of seven when there are several ways to make one, a tie that is a split, and
 * the fast score the NPCs use held to exactly the same answer as the slow one on tens of thousands of random hands.
 */
class HandValueTest {

    private static HandValue v(String cards) {
        return HandValue.of(PokerCard.parse(cards));
    }

    private static long fast(String cards) {
        List<PokerCard> list = PokerCard.parse(cards);
        int[] codes = new int[list.size()];
        for (int i = 0; i < codes.length; i++) {
            codes[i] = list.get(i).code();
        }
        return HandValue.score(codes, codes.length);
    }

    private static void beats(String better, String worse) {
        assertTrue(v(better).compareTo(v(worse)) > 0, better + " beats " + worse + ": " + v(better) + " vs " + v(worse));
        assertTrue(fast(better) > fast(worse), "and the fast score agrees: " + better + " / " + worse);
    }

    private static void ties(String a, String b) {
        assertEquals(v(a).score(), v(b).score(), a + " ties " + b + ": " + v(a) + " vs " + v(b));
        assertEquals(fast(a), fast(b), "the fast score agrees: " + a + " / " + b);
    }

    @Test
    void everyCategoryIsFoundAndNamed() {
        assertEquals(HandValue.Category.STRAIGHT_FLUSH, v("As Ks Qs Js Ts 2d 3c").category());
        assertEquals("a Royal Flush", v("As Ks Qs Js Ts 2d 3c").name());
        assertEquals("a Straight Flush, 9 high", v("9h 8h 7h 6h 5h Kd Kc").name());
        assertEquals("Four Jacks", v("Jh Jd Jc Js 2h 3d 9c").name());
        assertEquals("a Full House, Queens over Twos", v("Qh Qd Qc 2s 2h 5d 9c").name());
        assertEquals("a Flush, Ace high", v("Ah 9h 7h 4h 2h Kd Qc").name());
        assertEquals("a Straight, 10 high", v("Th 9d 8c 7s 6h 2d 2c").name());
        assertEquals("Three Sevens", v("7h 7d 7c As Kh 4d 2c").name());
        assertEquals("Two Pair, Kings and Fives", v("Kh Kd 5c 5s 9h 4d 2c").name());
        assertEquals("a Pair of Kings", v("Kh Kd 9c 7s 5h 4d 2c").name());
        assertEquals("Ace high", v("Ah Jd 9c 7s 5h 4d 2c").name());
    }

    @Test
    void theCategoriesRankInPokersOrder() {
        String[] weakestFirst = {
                "Ah Jd 9c 7s 5h 4d 2c", // high card
                "2h 2d 9c 7s 5h 4d 3c", // a pair
                "3h 3d 2c 2s 9h 7d 5c", // two pair
                "2h 2d 2c 9s 7h 5d 4c", // three of a kind
                "Ah 2d 3c 4s 5h 9d Jc", // the lowest straight (the wheel)
                "2h 4h 6h 8h Th Kd Qc", // a flush
                "2h 2d 2c 3s 3h 9d Jc", // a full house
                "2h 2d 2c 2s 3h 9d Jc", // four of a kind
                "Ah 2h 3h 4h 5h 9d Jc"}; // the lowest straight flush (the steel wheel)
        for (int i = 1; i < weakestFirst.length; i++) {
            beats(weakestFirst[i], weakestFirst[i - 1]);
        }
    }

    @Test
    void kickersDecideWithinACategory() {
        beats("Kh Kd Ac 7s 5h 4d 2c", "Kh Kd Qc 7s 5h 4d 2c"); // a pair of Kings: the Ace kicker
        beats("Kh Kd Ac 8s 5h 4d 2c", "Kh Kd Ac 7s 5h 4d 2c"); // the second kicker
        beats("Kh Kd Ac 8s 6h 4d 2c", "Kh Kd Ac 8s 5h 4d 2c"); // the third kicker
        ties("Kh Kd Ac 8s 6h 4d 2c", "Ks Kc Ad 8h 6c 3d 2s"); // the fourth card down never plays: a tie
        beats("Ah Ad 3c 3s Kh 4d 2c", "Ah Ad 3c 3s Qh 4d 2c"); // two pair: the kicker
        beats("Ah Ad 4c 4s 2h 6d 7c", "Ah Ad 3c 3s Kh 6d 7c"); // two pair: the second pair before any kicker
        beats("7h 7d 7c As 2h 4d 3c", "7h 7d 7c Ks Qh 4d 3c"); // three of a kind: the kicker
        beats("Jh Jd Jc Js Ah 2d 3c", "Jh Jd Jc Js Kh 2d 3c"); // four of a kind: the kicker
        beats("Qh Qd Qc 3s 3h", "Jh Jd Jc As Ah"); // a full house: the three first
        beats("Qh Qd Qc 4s 4h", "Qh Qd Qc 3s 3h"); // then the pair
        beats("Ah Th 7h 4h 3h", "Ah Th 7h 4h 2h"); // a flush: every card counts, down to the fifth
        beats("Ah Kd Qc 8s 7h", "Ah Kd Qc 8s 6h"); // high card: the same
    }

    @Test
    void theWheelIsTheLowestStraightAndAnAceHighOneTheHighest() {
        HandValue wheel = v("Ah 2d 3c 4s 5h Kd Kc");
        assertEquals(HandValue.Category.STRAIGHT, wheel.category());
        assertEquals("a Straight, 5 high", wheel.name());
        assertEquals("5♥ 4♠ 3♣ 2♦ A♥", wheel.cardsText(), "the Ace plays low, last");
        beats("2h 3d 4c 5s 6h Kd Kc", "Ah 2d 3c 4s 5h Kd Kc"); // six high beats the wheel
        beats("Ah Kd Qc Js Th 2d 2c", "Kh Qd Jc Ts 9h 2d 2c"); // Broadway is the top
        assertEquals(HandValue.Category.HIGH_CARD, v("Kh Ad 2c 3s 4h 9d 8c").category(), "no wrap-around K-A-2-3-4");
        assertEquals("a Straight Flush, 5 high", v("Ah 2h 3h 4h 5h 9d Jc").name(), "the steel wheel");
        beats("2h 3h 4h 5h 6h 9d Jc", "Ah 2h 3h 4h 5h 9d Jc");
        // a 6-high straight on six connected cards: the best five, not the wheel
        assertEquals("a Straight, 6 high", v("Ah 2d 3c 4s 5h 6d Kc").name());
    }

    @Test
    void theBestFiveOfSevenWhenThereAreSeveralWays() {
        assertEquals("a Full House, Kings over Queens", v("Kh Kd Kc Qs Qh Qd 2c").name(),
                "two threes: the higher is the three, the lower the pair");
        assertEquals("a Full House, Kings over Queens", v("Kh Kd Kc Qs Qh 2d 2c").name(), "the higher pair");
        HandValue threePair = v("Kh Kd Qc Qs 2h 2d Ac");
        assertEquals("Two Pair, Kings and Queens", threePair.name());
        assertEquals(14, threePair.best().get(4).rank(), "the Ace plays as the kicker over the third pair");
        assertEquals(11, v("Kh Kd Qc Qs Jh Jd 2c").best().get(4).rank(), "the third pair's card is the kicker");
        HandValue sixHearts = v("Ah Kh 9h 7h 5h 2h Kd");
        assertEquals("a Flush, Ace high", sixHearts.name());
        assertEquals(5, sixHearts.best().get(4).rank(), "the top five hearts: the 2 doesn't play");
        assertEquals(HandValue.Category.FLUSH, v("9h 8h 7h 6h 2h Td Jc").category(), "a flush over a straight");
        assertEquals(HandValue.Category.FULL_HOUSE, v("9h 9d 9c 8h 8d 7h 6h").category(),
                "a full house, though four of the cards are hearts");
        assertEquals(HandValue.Category.FOUR, v("9h 9d 9c 9s Ah Kh Qh").category());
        assertEquals(14, v("9h 9d 9c 9s 2h Ah Kh").best().get(4).rank(), "four of a kind's kicker is the best left");
        HandValue sfOverFlush = v("9h 8h 7h 6h 5h Ah Kh");
        assertEquals("a Straight Flush, 9 high", sfOverFlush.name(), "the straight flush, not the Ace-high flush");
    }

    @Test
    void aTieIsASplitAndSuitsNeverBreakIt() {
        ties("Ah Kd Qc Js 9h 3d 2c", "Ad Kh Qs Jc 9d 3c 2h");
        ties("2h 3d Ts Js Qh Kd Ac", "4c 5c Ts Js Qh Kd Ac"); // both play the board's straight
        ties("As Ks Qs Js Ts 2d 3c", "Ah Kh Qh Jh Th 4d 5c"); // two royal flushes
    }

    @Test
    void fiveAndSixCardsWorkAndNonsenseIsRefused() {
        assertEquals("a Pair of Aces", v("Ah Ad 7c 5s 2h").name());
        assertEquals("Two Pair, Aces and Sevens", v("Ah Ad 7c 7s 2h 3d").name());
        assertThrows(IllegalArgumentException.class, () -> v("Ah Ad 7c 7s"));
        assertThrows(IllegalArgumentException.class, () -> v("Ah Ad 7c 7s 2h 3d 4c 5c"));
        assertThrows(IllegalArgumentException.class, () -> v("Ah Ah 7c 7s 2h"), "a card twice");
    }

    @Test
    void theFastScoreIsTheSlowScoreOnEveryHandTried() {
        SplittableRandom r = new SplittableRandom(57);
        int[] counts = new int[HandValue.Category.values().length];
        for (int n = 0; n < 30_000; n++) {
            int size = 5 + r.nextInt(3);
            int[] deck = PokerCard.shuffled(r);
            List<PokerCard> cards = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                cards.add(PokerCard.of(deck[i]));
            }
            HandValue slow = HandValue.of(cards);
            assertEquals(slow.score(), HandValue.score(deck, size), cards + ": " + slow);
            counts[slow.category().ordinal()]++;
        }
        for (HandValue.Category c : HandValue.Category.values()) {
            if (c != HandValue.Category.STRAIGHT_FLUSH) {
                assertTrue(counts[c.ordinal()] > 0, "random hands reached " + c);
            }
        }
        // the rarest ones, by hand
        for (String sf : List.of("Ts Js Qs Ks As 2h 2d", "As 2s 3s 4s 5s Kh Kd", "6d 7d 8d 9d Td Jd 2c")) {
            List<PokerCard> cards = PokerCard.parse(sf);
            int[] codes = cards.stream().mapToInt(PokerCard::code).toArray();
            assertEquals(HandValue.of(cards).score(), HandValue.score(codes, codes.length), sf);
        }
    }

    @Test
    void cardsReadAndWriteTheirNames() {
        PokerCard q = PokerCard.parse("Qh").get(0);
        assertEquals("Queen of Hearts", q.name());
        assertEquals("Q♥", q.shortName());
        assertTrue(q.red());
        assertEquals(q, PokerCard.of(q.code()));
        assertEquals("10♣", PokerCard.parse("10c").get(0).shortName());
        int[] deck = PokerCard.shuffled(new SplittableRandom(1));
        assertEquals(52, java.util.Arrays.stream(deck).distinct().count(), "a shuffle is the 52 cards once each");
    }
}
