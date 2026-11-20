package com.dierks.homecraft.games.chance.twentyone;

import com.dierks.homecraft.games.chance.twentyone.TwentyOneHand.Card;
import com.dierks.homecraft.games.chance.twentyone.TwentyOneHand.Result;
import com.dierks.homecraft.games.chance.twentyone.TwentyOneHand.Shoe;
import com.dierks.homecraft.games.chance.twentyone.TwentyOneHand.Terms;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.47.0, Twenty-One at a table of up to four (the owner: "play as a group around the table"): the cards come off one
 * shoe in a real table's order, the seats play in turn, the Arcade draws once for everyone, each seat is paid by its
 * own terms exactly as a hand alone would be, and any seat's hand can be finished from its own data.
 */
class TableRoundTest {

    private static Terms terms(int stake) {
        return TwentyOneSettings.defaults().odds(stake).terms();
    }

    private static List<Terms> seats(int... stakes) {
        List<Terms> out = new ArrayList<>();
        for (int s : stakes) {
            out.add(terms(s));
        }
        return out;
    }

    /** The shoe's cards from {@code at}, in order. */
    private static List<Card> shoeCards(long seed, int at, int n) {
        int[] order = Shoe.shuffled(seed);
        List<Card> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(Card.of(order[at + i]));
        }
        return out;
    }

    /** A seed whose deal (to {@code seats} seats from the top of the shoe) satisfies {@code want}. */
    private static long seedWhere(int seats, java.util.function.Predicate<TableRound> want) {
        List<Terms> t = new ArrayList<>();
        for (int i = 0; i < seats; i++) {
            t.add(terms(5));
        }
        for (long seed = 1; seed < 200_000; seed++) {
            if (want.test(new TableRound(new Shoe(seed, 0), t))) {
                return seed;
            }
        }
        throw new AssertionError("no such deal in 200,000 shoes");
    }

    @Test
    void theCardsComeOffTheShoeInATablesOrder() {
        long seed = seedWhere(3, r -> r.turn() == 0 && !r.over());
        TableRound r = new TableRound(new Shoe(seed, 0), seats(5, 10, 20));
        List<Card> shoe = shoeCards(seed, 0, 8);
        assertEquals(List.of(shoe.get(0), shoe.get(4)), r.hand(0).cards(), "seat 1: the 1st and 5th cards");
        assertEquals(List.of(shoe.get(1), shoe.get(5)), r.hand(1).cards(), "seat 2: the 2nd and 6th");
        assertEquals(List.of(shoe.get(2), shoe.get(6)), r.hand(2).cards(), "seat 3: the 3rd and 7th");
        assertEquals(List.of(shoe.get(3), shoe.get(7)), r.dealerCards(), "the Arcade: the 4th (up) and the 8th (hole)");
        assertTrue(r.holeHidden());
        assertEquals(8, r.nextAt(), "eight cards are gone from the shoe");
    }

    @Test
    void aRoundStartsWhereTheShoeIs() {
        long seed = seedWhere(1, r -> !r.over());
        TableRound first = new TableRound(new Shoe(seed, 0), seats(5));
        first.stand(0);
        int at = first.nextAt();
        TableRound second = new TableRound(new Shoe(seed, at), seats(5));
        assertEquals(shoeCards(seed, at, 1).get(0), second.hand(0).cards().get(0),
                "the next round's first card is the shoe's next card");
    }

    @Test
    void theSeatsPlayInTurnAndTheArcadeDrawsOnceForEveryone() {
        long seed = seedWhere(3, r -> {
            for (int i = 0; i < 3; i++) {
                if (!r.hand(i).playing()) {
                    return false;
                }
            }
            return true;
        });
        TableRound r = new TableRound(new Shoe(seed, 0), seats(5, 10, 20));
        assertEquals(0, r.turn());
        assertThrows(IllegalArgumentException.class, () -> r.stand(1), "seat 2 can't act on seat 1's turn");
        r.stand(0);
        assertEquals(1, r.turn(), "then seat 2");
        assertTrue(r.hand(0).stood());
        assertNull(r.hand(0).result(), "a stood hand waits for the Arcade");
        assertTrue(r.holeHidden(), "the hole card stays down while anyone has a move");
        r.stand(1);
        assertEquals(2, r.turn());
        r.stand(2);
        assertEquals(-1, r.turn(), "nobody has a move left");
        assertTrue(r.over());
        assertFalse(r.holeHidden());
        assertTrue(TwentyOneHand.best(r.dealerCards()) >= 17, "the Arcade drew to 17");
        int arcade = TwentyOneHand.best(r.dealerCards());
        for (int i = 0; i < 3; i++) {
            int mine = TwentyOneHand.best(r.hand(i).cards());
            Result want = arcade > 21 || mine > arcade ? Result.WIN : mine == arcade ? Result.PUSH : Result.LOSE;
            assertEquals(want, r.hand(i).result(), "seat " + (i + 1) + " against the one Arcade hand");
        }
    }

    @Test
    void eachSeatIsPaidByItsOwnTermsAsAHandAloneIs() {
        long seed = seedWhere(2, r -> r.hand(0).playing() && r.hand(1).playing());
        TableRound r = new TableRound(new Shoe(seed, 0), seats(5, 20));
        r.stand(0);
        r.stand(1);
        for (int i = 0; i < 2; i++) {
            TableRound.Hand h = r.hand(i);
            TwentyOneMath.Payouts p = h.terms().payouts();
            int want = switch (h.result()) {
                case WIN -> p.win();
                case PUSH -> p.push();
                default -> 0;
            };
            assertEquals(want, h.payout(), "seat " + (i + 1));
            assertEquals(h.terms().in(), h.putIn());
        }
    }

    @Test
    void aHitOverTwentyOneLosesAtOnceAndTheTurnMovesOn() {
        long seed = seedWhere(2, r -> {
            if (!r.hand(0).playing() || !r.hand(1).playing()) {
                return false;
            }
            int total = TwentyOneHand.hardTotal(r.hand(0).cards());
            // seat 1 holds a hard 12 or more with no Ace, and the shoe's next card takes it over 21
            return !TwentyOneHand.hasAce(r.hand(0).cards()) && total >= 12
                    && total + shoeCards(r.shoeSeed(), r.nextAt(), 1).get(0).value() > 21;
        });
        TableRound r = new TableRound(new Shoe(seed, 0), seats(5, 5));
        r.hit(0);
        assertEquals(Result.BUST, r.hand(0).result());
        assertEquals(0, r.hand(0).payout());
        assertEquals(1, r.turn(), "seat 2 plays on");
        assertFalse(r.over());
        r.stand(1);
        assertTrue(r.over());
    }

    @Test
    void whenEveryoneIsOverTheArcadeDrawsNothing() {
        long seed = seedWhere(1, r -> {
            if (!r.hand(0).playing() || TwentyOneHand.hasAce(r.hand(0).cards())) {
                return false;
            }
            int total = TwentyOneHand.hardTotal(r.hand(0).cards());
            return total >= 12 && total + shoeCards(r.shoeSeed(), r.nextAt(), 1).get(0).value() > 21
                    && TwentyOneHand.best(r.dealerCards()) < 17;
        });
        TableRound r = new TableRound(new Shoe(seed, 0), seats(5));
        r.hit(0);
        assertTrue(r.over());
        assertEquals(2, r.dealerCards().size(), "no hand is waiting on it: the Arcade shows its two cards and stops");
        assertFalse(r.holeHidden());
    }

    @Test
    void aDoubleTakesOneCardAndStands() {
        long seed = seedWhere(1, r -> r.hand(0).playing() && TwentyOneHand.hardTotal(r.hand(0).cards()) <= 10
                && !TwentyOneHand.hasAce(r.hand(0).cards()));
        TableRound r = new TableRound(new Shoe(seed, 0), seats(10));
        assertTrue(r.hand(0).canDouble());
        r.doubleDown(0);
        assertEquals(3, r.hand(0).cards().size());
        assertTrue(r.hand(0).doubled());
        assertEquals(20, r.hand(0).putIn());
        assertTrue(r.over(), "nobody else at the table: the Arcade played");
        TwentyOneMath.Payouts p = r.hand(0).pays();
        int want = switch (r.hand(0).result()) {
            case WIN -> p.doubleWin();
            case PUSH -> p.doublePush();
            default -> 0;
        };
        assertEquals(want, r.hand(0).payout());
    }

    @Test
    void aTwoCardTwentyOneIsPaidAtOnceAndItsSeatHasNoTurn() {
        long seed = seedWhere(2, r -> r.hand(0).result() == Result.TWENTY_ONE && r.hand(1).playing());
        TableRound r = new TableRound(new Shoe(seed, 0), seats(5, 5));
        assertEquals(r.hand(0).pays().twentyOne(), r.hand(0).payout());
        assertEquals(1, r.turn(), "the turn starts with the seat that has a move");
        assertTrue(r.holeHidden());
        r.stand(1);
        assertTrue(r.over());
        assertEquals(Result.TWENTY_ONE, r.hand(0).result(), "and the Arcade's hand doesn't change it");
    }

    @Test
    void theArcadesTwoCardTwentyOneEndsTheRoundForEveryone() {
        long seed = seedWhere(3, r -> r.over() && r.dealerCards().size() == 2
                && TwentyOneHand.best(r.dealerCards()) == 21);
        TableRound r = new TableRound(new Shoe(seed, 0), seats(5, 5, 5));
        assertEquals(-1, r.turn());
        assertFalse(r.holeHidden(), "its hand is shown");
        for (int i = 0; i < 3; i++) {
            TableRound.Hand h = r.hand(i);
            boolean natural = TwentyOneHand.best(h.cards()) == 21;
            assertEquals(natural ? Result.BOTH_TWENTY_ONE : Result.ARCADE_TWENTY_ONE, h.result());
            assertEquals(natural ? h.pays().push() : 0, h.payout());
        }
    }

    @Test
    void aTableDealsToOneToFourSeats() {
        assertThrows(IllegalArgumentException.class, () -> new TableRound(new Shoe(1, 0), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new TableRound(new Shoe(1, 0), seats(5, 5, 5, 5, 5)));
        assertNotNull(new TableRound(new Shoe(1, 0), seats(5, 5, 5, 5)));
    }

    // ---- a seat's data: finished from the data alone ----------------------------------------------------------

    @Test
    void aFinishedHandsDataSettlesToWhatTheTablePaid() {
        SplittableRandom r = new SplittableRandom(46);
        for (int n = 0; n < 2_000; n++) {
            int count = 1 + r.nextInt(4);
            int[] stakes = new int[count];
            for (int i = 0; i < count; i++) {
                stakes[i] = new int[] {5, 10, 20}[r.nextInt(3)];
            }
            TableRound round = new TableRound(new Shoe(r.nextLong(), r.nextInt(TwentyOneHand.CUT_CARD)), seats(stakes));
            while (round.turn() >= 0) {
                int seat = round.turn();
                TableRound.Hand h = round.hand(seat);
                int pick = r.nextInt(10);
                if (pick == 0 && h.canDouble()) {
                    round.doubleDown(seat);
                } else if (pick < 5 && TwentyOneHand.best(h.cards()) < 17) {
                    round.hit(seat);
                } else {
                    round.stand(seat);
                }
            }
            assertTrue(round.over());
            for (int i = 0; i < count; i++) {
                TableRound.Hand h = round.hand(i);
                String data = round.data(i);
                assertTrue(TableRound.isTable(data));
                assertEquals(h.payout(), TableRound.settleOnExit(0, h.putIn(), data),
                        "the data says what the table paid: " + data);
                assertEquals(h.payout(), TwentyOneHand.settleOnExit(0, h.putIn(), data),
                        "and Twenty-One's own exit rule reads a table hand");
                assertEquals(h.terms().in(), TableRound.stakeOf(data));
            }
        }
    }

    @Test
    void aHandLeftMidRoundStandsAndTheArcadeDrawsFromTheShoesNextCards() {
        long seed = seedWhere(2, r -> r.hand(0).playing() && r.hand(1).playing()
                && TwentyOneHand.best(r.dealerCards()) < 17);
        TableRound r = new TableRound(new Shoe(seed, 0), seats(5, 5));
        String left = r.data(0); // seat 1 walks away before anyone acts
        List<Card> arcade = new ArrayList<>(r.dealerCards());
        int at = r.nextAt();
        int[] order = Shoe.shuffled(seed);
        while (TwentyOneHand.best(arcade) < 17) {
            arcade.add(Card.of(order[at++]));
        }
        int mine = TwentyOneHand.best(r.hand(0).cards());
        int theirs = TwentyOneHand.best(arcade);
        TwentyOneMath.Payouts p = r.hand(0).pays();
        int want = theirs > 21 || mine > theirs ? p.win() : mine == theirs ? p.push() : 0;
        assertEquals(want, TableRound.settleOnExit(0, 5, left));
    }

    @Test
    void aDoublePaidForButNotRecordedIsTakenAsWithAHandAlone() {
        long seed = seedWhere(1, r -> r.hand(0).playing() && TwentyOneHand.hardTotal(r.hand(0).cards()) <= 10
                && !TwentyOneHand.hasAce(r.hand(0).cards()));
        TableRound r = new TableRound(new Shoe(seed, 0), seats(10));
        String before = r.data(0);
        r.doubleDown(0);
        assertEquals(r.hand(0).payout(), TableRound.settleOnExit(0, 20, before),
                "the stake says the Double went in: one card, then the Arcade, exactly as the table would");
    }

    @Test
    void nonsenseDataIsRefusedNotGuessed() {
        assertThrows(IllegalArgumentException.class, () -> TableRound.settleOnExit(0, 5, "v=3;in=5"));
        assertThrows(IllegalArgumentException.class,
                () -> TableRound.settleOnExit(0, 5, "v=3;in=5;m=4/5;nb=3/2;cap=250;shoe=1;at=0;p=99.1;d=1.2;a="));
        assertFalse(TableRound.isTable("v=2;shoe=1;at=0;in=5;m=4/5;nb=3/2;cap=250;a="));
        assertFalse(TableRound.isTable(null));
    }
}
