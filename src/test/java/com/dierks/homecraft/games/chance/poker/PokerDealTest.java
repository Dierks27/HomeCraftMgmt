package com.dierks.homecraft.games.chance.poker;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.57.0: one hand of Video Poker as pure state: a fresh 52-card deck per hand, held cards stay, the others are
 * replaced in order from the 47 left, the hand pays its row of its own prizes, and the round's data carries all of it,
 * so a hand left open is finished from the seed and the data alone, with the best hold, exactly once.
 */
class PokerDealTest {

    private static final int[] PRIZES = {0, 5, 10, 15, 20, 25, 30, 125, 250, 250};

    @Test
    void everyHandIsAFreshShuffleOfFiftyTwoCards() {
        for (long seed = 0; seed < 50; seed++) {
            int[] d = PokerDeal.deck(seed);
            Set<Integer> seen = new HashSet<>();
            for (int c : d) {
                assertTrue(c >= 0 && c < 52);
                seen.add(c);
            }
            assertEquals(52, seen.size(), "every card once");
            assertArrayEquals(d, PokerDeal.deck(seed), "the same seed, the same deck");
        }
        assertFalse(Arrays.equals(PokerDeal.deck(1), PokerDeal.deck(2)));
        // each card is as likely to be the first dealt (52,000 decks: about 1,000 each)
        int[] first = new int[52];
        for (long seed = 0; seed < 52_000; seed++) {
            first[PokerDeal.deck(seed * 0x9E3779B97F4A7C15L)[0]]++;
        }
        double chi = 0;
        for (int n : first) {
            chi += (n - 1000.0) * (n - 1000.0) / 1000.0;
        }
        assertTrue(chi < 100, "chi-squared on 51 degrees of freedom stays well under 100: " + chi);
    }

    @Test
    void heldCardsStayAndTheRestAreReplacedInOrder() {
        PokerDeal d = PokerDeal.deal(42, 5, PRIZES);
        int[] deck = PokerDeal.deck(42);
        assertArrayEquals(Arrays.copyOf(deck, 5), d.dealt());
        d.toggle(0);
        d.toggle(3);
        d.toggle(3);
        d.toggle(4);
        assertTrue(d.held(0) && d.held(4) && !d.held(3), "a second tap lets a card go again");
        assertEquals(0b10001, d.heldMask());
        d.draw();
        int[] after = d.cards();
        assertEquals(deck[0], after[0]);
        assertEquals(deck[5], after[1], "the first card thrown away gets the sixth");
        assertEquals(deck[6], after[2]);
        assertEquals(deck[7], after[3]);
        assertEquals(deck[4], after[4]);
        d.toggle(1);
        d.draw();
        assertArrayEquals(after, d.cards(), "one draw only; holding after it changes nothing");
        assertEquals(PRIZES[d.hand().ordinal()], d.payout());
    }

    @Test
    void theRoundsDataCarriesEveryPayoutParameter() {
        String open = PokerDeal.openData(5, PRIZES);
        assertEquals("v=1;in=5;p=0,5,10,15,20,25,30,125,250,250", open);
        PokerDeal.Saved s = PokerDeal.read(open);
        assertEquals(5, s.stake());
        assertArrayEquals(PRIZES, s.prizes());
        assertEquals(-1, s.held(), "not drawn yet");
        PokerDeal d = PokerDeal.deal(7, 5, PRIZES);
        d.toggle(2);
        d.draw();
        assertTrue(d.data().startsWith(open + ";held=00100;hand="), d.data());
        PokerDeal again = PokerDeal.resume(7, d.data());
        assertTrue(again.drawn());
        assertArrayEquals(d.cards(), again.cards(), "a drawn hand reads back as it was");
        assertThrows(IllegalArgumentException.class, () -> PokerDeal.read("v=2;in=5;p=0,1"));
        assertThrows(IllegalArgumentException.class, () -> PokerDeal.read("v=1;in=5;p=0,1,2"));
        assertThrows(IllegalArgumentException.class, () -> PokerDeal.read(null));
    }

    @Test
    void aHandLeftOpenIsDrawnWithTheBestHoldFromItsDataAlone() {
        for (long seed = 100; seed < 106; seed++) {
            String open = PokerDeal.openData(5, PRIZES);
            int pay = PokerDeal.settleOnExit(seed, 5, open);
            PokerDeal d = PokerDeal.deal(seed, 5, PRIZES);
            int best = PokerMath.bestHold(d.dealt(), PRIZES);
            int[] after = d.after(best);
            assertEquals(PRIZES[PokerHand.of(after).ordinal()], pay, "seed " + seed);
        }
        // a hand already drawn (the draw was written, the round not closed) pays what it made
        PokerDeal d = PokerDeal.deal(9, 5, PRIZES);
        d.toggle(1);
        d.draw();
        assertEquals(d.payout(), PokerDeal.settleOnExit(9, 5, d.data()));
    }

    @Test
    void aLineOfCardsReadsShort() {
        assertEquals("A♠ K♥ 10♦ 2♣ 9♠", PokerDeal.line(PokerHandTest.hand("As Kh Td 2c 9s")));
    }
}
