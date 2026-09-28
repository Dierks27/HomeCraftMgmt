package com.dierks.homecraft.games.chance.twentyone;

import com.dierks.homecraft.games.RtpLimits.Ratio;
import com.dierks.homecraft.games.chance.twentyone.TwentyOneHand.Card;
import com.dierks.homecraft.games.chance.twentyone.TwentyOneHand.Result;
import com.dierks.homecraft.games.chance.twentyone.TwentyOneHand.Terms;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;
import java.util.function.LongPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One hand of Twenty-One as pure state (spec §5.4, §5.2): cards come from the seed in a fixed
 * order, the hand replays exactly from its seed and actions, the peek, Twenty-One!, Double and
 * the Arcade's draw-to-17, what each result pays, the round's data, and the exit rule (stand).
 */
class TwentyOneHandTest {

    /** The shipped stake 5 at m = 0.8: win 9, Twenty-One! 11, double win 18. */
    private static final Terms FIVE = new Terms(5, new Ratio(4, 5), new Ratio(3, 2), 250);

    /** The first seed (counting from 1) whose fresh deal passes {@code test}. */
    private static long seedWhere(LongPredicate test) {
        for (long seed = 1; seed < 1_000_000; seed++) {
            if (test.test(seed)) {
                return seed;
            }
        }
        throw new AssertionError("no seed found");
    }

    @Test
    void everyCardIsTheSeedsNextDrawInTheFixedDealingOrder() {
        long seed = 42;
        TwentyOneHand h = TwentyOneHand.deal(seed, FIVE);
        SplittableRandom r = new SplittableRandom(seed);
        Card you1 = Card.of(r.nextInt(52));
        Card up = Card.of(r.nextInt(52));
        Card you2 = Card.of(r.nextInt(52));
        Card hole = Card.of(r.nextInt(52));
        assertEquals(you1, h.playerCards().get(0), "card 0 is yours");
        assertEquals(up, h.dealerCards().get(0), "card 1 is the Arcade's up card");
        assertEquals(you2, h.playerCards().get(1), "card 2 is yours");
        assertEquals(hole, h.dealerCards().get(1), "card 3 is the hole card");
        assertEquals(new Card(1, 0), Card.of(0), "draw 0 is the Ace of Spades");
        assertEquals(new Card(13, 3), Card.of(51), "draw 51 is the King of Clubs");
        assertEquals(10, new Card(12, 1).value(), "a Queen counts 10");
        assertEquals("Queen of Hearts", new Card(12, 1).name());
    }

    @Test
    void theSameSeedAndActionsAlwaysGiveTheSameHand() {
        for (long seed = 1; seed <= 300; seed++) {
            TwentyOneHand live = TwentyOneHand.deal(seed, FIVE);
            while (!live.done()) {
                if (TwentyOneHand.best(live.playerCards()) < 15) {
                    live.hit();
                } else {
                    live.stand();
                }
            }
            TwentyOneHand again = TwentyOneHand.replay(seed, FIVE, live.actions());
            assertEquals(live.playerCards(), again.playerCards(), "seed " + seed + ": your cards replay");
            assertEquals(live.dealerCards(), again.dealerCards(), "seed " + seed + ": the Arcade's cards replay");
            assertEquals(live.payout(), again.payout(), "seed " + seed + ": the payout replays");
            assertEquals(live.result(), again.result());
        }
    }

    @Test
    void anArcadeTwentyOneEndsTheHandAtOnce() {
        long seed = seedWhere(s -> {
            TwentyOneHand h = TwentyOneHand.deal(s, FIVE);
            return h.result() == Result.ARCADE_TWENTY_ONE;
        });
        TwentyOneHand h = TwentyOneHand.deal(seed, FIVE);
        int up = h.dealerCards().get(0).value();
        assertTrue(up == 1 || up == 10, "the Arcade only checks when it shows an Ace or a ten-count");
        assertTrue(h.done(), "nobody plays after the Arcade's Twenty-One");
        assertEquals(0, h.payout(), "your hand loses");
        assertFalse(h.holeHidden(), "and the hole card is shown");

        long both = seedWhere(s -> TwentyOneHand.deal(s, FIVE).result() == Result.BOTH_TWENTY_ONE);
        assertEquals(5, TwentyOneHand.deal(both, FIVE).payout(), "your own Twenty-One gets your 5 back");
    }

    @Test
    void aTwoCardTwentyOnePaysTheBonus() {
        long seed = seedWhere(s -> TwentyOneHand.deal(s, FIVE).result() == Result.TWENTY_ONE);
        TwentyOneHand h = TwentyOneHand.deal(seed, FIVE);
        assertEquals(21, TwentyOneHand.best(h.playerCards()));
        assertEquals(11, h.payout(), "5 in + floor(5 x 0.8 x 1.5) = 11");
        assertFalse(h.canPlay(), "a Twenty-One! needs nothing more");
    }

    @Test
    void theArcadeDrawsToSeventeenAndStandsOnEverySeventeen() {
        for (long seed = 1; seed <= 2000; seed++) {
            TwentyOneHand h = TwentyOneHand.deal(seed, FIVE);
            if (h.done()) {
                continue;
            }
            h.stand();
            int theirs = TwentyOneHand.best(h.dealerCards());
            assertTrue(theirs >= 17, "seed " + seed + ": the Arcade never stops under 17");
            int before = TwentyOneHand.best(h.dealerCards().subList(0, h.dealerCards().size() - 1));
            if (h.dealerCards().size() > 2) {
                assertTrue(before < 17, "seed " + seed + ": it never draws on 17 or more (soft 17 included)");
            }
            int mine = TwentyOneHand.best(h.playerCards());
            int expect = theirs > 21 || mine > theirs ? 9 : mine == theirs ? 5 : 0;
            assertEquals(expect, h.payout(), "seed " + seed + ": win 9, same total 5, lower 0");
        }
    }

    @Test
    void overTwentyOneLosesAndTwentyOneStandsForYou() {
        long seed = seedWhere(s -> {
            TwentyOneHand h = TwentyOneHand.deal(s, FIVE);
            if (h.done()) {
                return false;
            }
            while (!h.done()) {
                h.hit();
            }
            return h.result() == Result.BUST;
        });
        TwentyOneHand h = TwentyOneHand.deal(seed, FIVE);
        while (!h.done()) {
            h.hit();
        }
        assertTrue(TwentyOneHand.best(h.playerCards()) > 21);
        assertEquals(0, h.payout(), "over 21 gets nothing back");
        assertThrows(IllegalArgumentException.class, h::hit, "no card after the hand is over");

        long exact = seedWhere(s -> {
            TwentyOneHand x = TwentyOneHand.deal(s, FIVE);
            if (x.done()) {
                return false;
            }
            x.hit();
            return !x.playerCards().isEmpty() && TwentyOneHand.best(x.playerCards()) == 21;
        });
        TwentyOneHand x = TwentyOneHand.deal(exact, FIVE);
        x.hit();
        assertTrue(x.done(), "reaching 21 stands for you");
        assertEquals("H", x.actions(), "without an extra action recorded");
    }

    @Test
    void aDoublePutsTheSameInAgainTakesOneCardAndStands() {
        long seed = seedWhere(s -> {
            TwentyOneHand h = TwentyOneHand.deal(s, FIVE);
            if (!h.canDouble()) {
                return false;
            }
            h.doubleDown();
            return h.result() == Result.WIN;
        });
        TwentyOneHand h = TwentyOneHand.deal(seed, FIVE);
        h.doubleDown();
        assertEquals(3, h.playerCards().size(), "exactly one more card");
        assertTrue(h.done(), "then it stands");
        assertEquals(10, h.putIn(), "the same in again");
        assertEquals(18, h.payout(), "10 in + floor(10 x 0.8) = 18");

        TwentyOneHand later = TwentyOneHand.deal(seedWhere(s -> {
            TwentyOneHand y = TwentyOneHand.deal(s, FIVE);
            if (y.done()) {
                return false;
            }
            y.hit();
            return !y.done();
        }), FIVE);
        later.hit();
        assertFalse(later.canDouble(), "only on your first two cards");
        assertThrows(IllegalArgumentException.class, later::doubleDown);
        assertThrows(IllegalArgumentException.class, () -> TwentyOneHand.replay(1, FIVE, "X"), "unknown actions");
    }

    @Test
    void theRoundsDataHoldsEveryParameterAndEveryAction() {
        String data = TwentyOneHand.data(FIVE, "HS");
        assertEquals("v=1;in=5;m=4/5;nb=3/2;cap=250;a=HS", data);
        TwentyOneHand.Saved s = TwentyOneHand.read(data);
        assertEquals(FIVE, s.terms(), "the terms read back exactly");
        assertEquals("HS", s.actions());
        assertEquals("", TwentyOneHand.read(TwentyOneHand.data(FIVE, "")).actions(), "no action yet");
        assertThrows(IllegalArgumentException.class, () -> TwentyOneHand.read("v=2;in=5;m=4/5;nb=3/2;cap=250;a="),
                "another version is never guessed at");
        assertThrows(IllegalArgumentException.class, () -> TwentyOneHand.read("v=1;in=5"), "missing parameters");
    }

    @Test
    void aHandLeftAsItIsStandsFromItsDataAlone() {
        for (long seed = 1; seed <= 500; seed++) {
            TwentyOneHand h = TwentyOneHand.deal(seed, FIVE);
            String actions = "";
            if (!h.done() && TwentyOneHand.best(h.playerCards()) < 12) {
                h.hit();
                actions = h.actions();
            }
            int settled = TwentyOneHand.settleOnExit(seed, 5, TwentyOneHand.data(FIVE, actions));
            if (!h.done()) {
                h.stand();
            }
            assertEquals(h.payout(), settled, "seed " + seed + ": leaving is exactly standing");
        }
    }

    @Test
    void aDoublePaidForButNotYetRecordedIsTaken() {
        long seed = seedWhere(s -> TwentyOneHand.deal(s, FIVE).canDouble());
        TwentyOneHand h = TwentyOneHand.deal(seed, FIVE);
        h.doubleDown();
        assertEquals(h.payout(), TwentyOneHand.settleOnExit(seed, 10, TwentyOneHand.data(FIVE, "")),
                "10 in with no D recorded: the Double happened (it was paid for)");
        assertEquals(h.payout(), TwentyOneHand.settleOnExit(seed, 10, TwentyOneHand.data(FIVE, "D")),
                "and a recorded Double settles the same");
    }

    @Test
    void settlingNeverReadsConfigSoARetuneChangesNothing() {
        Terms rich = new Terms(5, new Ratio(1, 1), new Ratio(3, 2), 250);
        long seed = seedWhere(s -> {
            TwentyOneHand h = TwentyOneHand.deal(s, FIVE);
            if (h.done()) {
                return false;
            }
            h.stand();
            return h.result() == Result.WIN;
        });
        assertEquals(9, TwentyOneHand.settleOnExit(seed, 5, TwentyOneHand.data(FIVE, "")),
                "the round pays what it was opened with (win 9)");
        assertEquals(10, TwentyOneHand.settleOnExit(seed, 5, TwentyOneHand.data(rich, "")),
                "only data changes it (win 10 at m = 1)");
    }

    @Test
    void theCapHoldsForEveryResult() {
        Terms capped = new Terms(20, new Ratio(1, 1), new Ratio(3, 2), 30);
        for (long seed = 1; seed <= 3000; seed++) {
            TwentyOneHand h = TwentyOneHand.deal(seed, capped);
            if (h.canDouble()) {
                fail("a doubled win capped at 30 pays less than the 40 put in, so no Double is offered");
            }
            if (!h.done()) {
                h.stand();
            }
            assertTrue(h.payout() <= 30, "seed " + seed + ": nothing pays past the cap");
        }
    }

    private static void fail(String why) {
        throw new AssertionError(why);
    }
}
