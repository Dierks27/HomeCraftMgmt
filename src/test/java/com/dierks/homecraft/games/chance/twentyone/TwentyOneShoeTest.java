package com.dierks.homecraft.games.chance.twentyone;

import com.dierks.homecraft.games.RtpLimits;
import com.dierks.homecraft.games.RtpLimits.Ratio;
import com.dierks.homecraft.games.chance.twentyone.TwentyOneHand.Shoe;
import com.dierks.homecraft.games.chance.twentyone.TwentyOneHand.Terms;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.45.0, the owner: "4 Deck Shoe - But reshuffle for a new session". A shoe is four real decks shuffled once; hands
 * carry on through it in order; every hand is still a pure function of its round's data (replayed exactly, settled on
 * exit exactly); a hand saved before 0.45.0 still reads and replays its own infinite deck.
 */
class TwentyOneShoeTest {

    private static final Terms FIVE = new Terms(5, new Ratio(4, 5), new Ratio(3, 2), 250);

    @Test
    void aShoeIsFourRealDecks() {
        int[] order = new Shoe(42L, 0).order();
        assertEquals(TwentyOneHand.SHOE_CARDS, order.length);
        int[] seen = new int[52];
        for (int c : order) {
            seen[c]++;
        }
        for (int c = 0; c < 52; c++) {
            assertEquals(4, seen[c], "each card four times, card " + c);
        }
        assertEquals(java.util.Arrays.toString(order), java.util.Arrays.toString(new Shoe(42L, 7).order()),
                "the same seed, the same order");
        assertNotEquals(java.util.Arrays.toString(order), java.util.Arrays.toString(new Shoe(43L, 0).order()));
        assertThrows(IllegalArgumentException.class, () -> new Shoe(1L, TwentyOneHand.SHOE_CARDS));
    }

    @Test
    void handsCarryOnThroughTheShoeInOrder() {
        long seed = 99L;
        int[] order = new Shoe(seed, 0).order();
        int at = 0;
        List<Integer> dealt = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            TwentyOneHand h = TwentyOneHand.deal(0L, FIVE, new Shoe(seed, at));
            while (!h.done()) {
                if (TwentyOneHand.best(h.playerCards()) < 15) {
                    h.hit();
                } else {
                    h.stand();
                }
            }
            // the cards used, in the deal's order: you, its up card, you, its hole card, then your hits, then its draws
            List<TwentyOneHand.Card> mine = h.playerCards();
            List<TwentyOneHand.Card> its = h.dealerCards();
            int used = mine.size() + its.size();
            assertEquals(at + used, h.nextAt(), "the next hand starts where this one stopped");
            assertEquals(order[at] % 52, index(mine.get(0)));
            assertEquals(order[at + 1] % 52, index(its.get(0)));
            assertEquals(order[at + 2] % 52, index(mine.get(1)));
            assertEquals(order[at + 3] % 52, index(its.get(1)));
            for (TwentyOneHand.Card c : mine) {
                dealt.add(index(c));
            }
            for (TwentyOneHand.Card c : its) {
                dealt.add(index(c));
            }
            at = h.nextAt();
        }
        int[] count = new int[52];
        for (int c : dealt) {
            assertTrue(++count[c] <= 4, "never a fifth of any card in one shoe");
        }
    }

    private static int index(TwentyOneHand.Card c) {
        return c.suit() * 13 + (c.rank() - 1);
    }

    @Test
    void aShoeHandIsAPureFunctionOfItsData() {
        SplittableRandom r = new SplittableRandom(7L);
        for (int i = 0; i < 300; i++) {
            Shoe shoe = new Shoe(r.nextLong(), r.nextInt(TwentyOneHand.CUT_CARD));
            TwentyOneHand live = TwentyOneHand.deal(123L, FIVE, shoe);
            while (!live.done()) {
                if (live.canDouble() && r.nextInt(5) == 0) {
                    live.doubleDown();
                } else if (r.nextBoolean()) {
                    live.hit();
                } else {
                    live.stand();
                }
            }
            String data = live.data();
            assertTrue(data.startsWith("v=2;shoe=" + shoe.seed() + ";at=" + shoe.at() + ";"), data);
            TwentyOneHand.Saved saved = TwentyOneHand.read(data);
            assertEquals(shoe, saved.shoe());
            TwentyOneHand again = TwentyOneHand.replay(123L, saved);
            assertEquals(live.playerCards(), again.playerCards());
            assertEquals(live.dealerCards(), again.dealerCards());
            assertEquals(live.payout(), again.payout());
            assertEquals(live.payout(), TwentyOneHand.settleOnExit(123L, live.putIn(), data), "settled the same");
        }
    }

    @Test
    void aHandSavedBefore045StillReadsAndReplaysItsOwnDeck() {
        String old = TwentyOneHand.data(FIVE, "S");
        assertTrue(old.startsWith("v=1;"), old);
        TwentyOneHand.Saved saved = TwentyOneHand.read(old);
        assertNull(saved.shoe(), "no shoe: the infinite deck");
        assertEquals(TwentyOneHand.replay(5L, FIVE, "S").payout(), TwentyOneHand.replay(5L, saved).payout());
        assertEquals(-1, TwentyOneHand.deal(5L, FIVE).nextAt());
        assertThrows(IllegalArgumentException.class, () -> TwentyOneHand.read("v=2;in=5;m=4/5;nb=3/2;cap=250;a="),
                "a shoe hand without its shoe can't be read");
    }

    /**
     * The shoe's average return under the solved best play stays within a token per 100 of the exact (infinite-deck)
     * value the screen and the website show, and under the band's top: a 4-deck shoe moves it by about a tenth of one.
     */
    @Test
    void theShippedTableFromAShoeGivesBackAboutWhatItsMathsSays() {
        TwentyOneSettings.Odds odds = TwentyOneSettings.defaults().odds().get(0);
        TwentyOneMath.Solution sol = TwentyOneMath.solve(odds.payouts());
        TwentyOneMath.Strategy s = sol.strategy();
        SplittableRandom seeds = new SplittableRandom(0x5110EL);
        long back = 0;
        long put = 0;
        long shoeSeed = seeds.nextLong();
        int at = 0;
        for (int i = 0; i < 400_000; i++) {
            if (at >= TwentyOneHand.CUT_CARD) {
                shoeSeed = seeds.nextLong();
                at = 0;
            }
            TwentyOneHand h = TwentyOneHand.deal(0L, odds.terms(), new Shoe(shoeSeed, at));
            if (!h.done()) {
                int up = h.dealerCards().get(0).value();
                List<TwentyOneHand.Card> mine = h.playerCards();
                if (h.canDouble() && s.dbl(up, TwentyOneHand.hardTotal(mine), TwentyOneHand.hasAce(mine))) {
                    h.doubleDown();
                }
                while (!h.done()) {
                    if (s.hit(up, TwentyOneHand.hardTotal(mine), TwentyOneHand.hasAce(mine))) {
                        h.hit();
                    } else {
                        h.stand();
                    }
                }
            }
            back += h.payout();
            put += h.putIn();
            at = h.nextAt();
        }
        double realised = (double) back / put;
        assertEquals(sol.rtp(), realised, 0.01, "within a token per 100 of the shown value");
        assertTrue(realised < RtpLimits.MAX, "under the band's top: " + realised);
    }

    /**
     * The review's find: a hand that ended anywhere but on its own screen (the player left, the sweep) never told the
     * shoe where it stopped, so the next Deal showed the same cards to a player who had already seen them. A shoe with
     * a hand still out is thrown away.
     */
    @Test
    void anAbandonedHandNeverDealsTheSameCardsAgain() {
        TwentyOne game = new TwentyOne(null);
        java.util.UUID id = java.util.UUID.randomUUID();
        Shoe first = game.shoeFor(id, 1_000L, 111L);
        assertEquals(new Shoe(111L, 0), first, "a session's first hand: a fresh shoe");
        // the hand is abandoned (no dealt): the next one is from a new shoe, never the same cards
        Shoe second = game.shoeFor(id, 2_000L, 222L);
        assertEquals(new Shoe(222L, 0), second, "a hand was still out: a fresh shoe");
        // played to its end on the screen: the shoe carries on where it stopped
        TwentyOneHand h = TwentyOneHand.deal(0L, FIVE, second);
        while (!h.done()) {
            h.stand();
        }
        game.dealt(id, h);
        Shoe third = game.shoeFor(id, System.currentTimeMillis(), 333L);
        assertEquals(new Shoe(222L, h.nextAt()), third, "the same shoe, from where the last hand stopped");
        // and a long break is a new session
        game.dealt(id, TwentyOneHand.deal(0L, FIVE, third));
        Shoe later = game.shoeFor(id, System.currentTimeMillis() + TwentyOne.SESSION_GAP_MS + 1, 444L);
        assertEquals(new Shoe(444L, 0), later, "a new session: reshuffled");
    }
}
