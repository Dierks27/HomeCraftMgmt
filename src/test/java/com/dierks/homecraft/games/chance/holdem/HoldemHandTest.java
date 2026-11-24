package com.dierks.homecraft.games.chance.holdem;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.57.0: one hand of no-limit Texas Hold'em, as pure state: the blinds and who acts when (two players and more), the
 * big blind's option, the least raise, an all-in for less that doesn't reopen the raising, everyone folding to one,
 * side pots layered by what each put in, chips nobody matched going back, split pots with the odd chip left of the
 * button, and over thousands of random hands, never a chip made or lost.
 */
class HoldemHandTest {

    private static HoldemHand hand(int[] stacks, int button, List<String> holes, String board) {
        return new HoldemHand(stacks, button, 1, 2, Decks.deck(stacks.length, button, holes, board));
    }

    private static int sum(HoldemHand h) {
        int s = 0;
        for (int i = 0; i < h.size(); i++) {
            s += h.player(i).stack();
        }
        return s;
    }

    @Test
    void headsUpTheButtonIsTheSmallBlindAndActsFirstOnlyBeforeTheFlop() {
        HoldemHand h = hand(new int[] {100, 100}, 0, List.of("As Ad", "Ks Kd"), "2c 7d 9h Jc 3s");
        assertEquals(0, h.smallBlindAt(), "two players: the button posts the small blind");
        assertEquals(1, h.bigBlindAt());
        assertEquals(99, h.player(0).stack());
        assertEquals(98, h.player(1).stack());
        assertEquals(0, h.toAct(), "the button acts first before the flop");
        assertEquals(1, h.toCall(0));
        assertTrue(h.checkCall(0));
        assertEquals(1, h.toAct(), "the big blind has its option");
        assertTrue(h.canCheck(1));
        assertTrue(h.canRaise(1));
        assertTrue(h.checkCall(1));
        assertEquals(HoldemHand.Street.FLOP, h.street());
        assertEquals(3, h.board().size());
        assertEquals(1, h.toAct(), "after the flop the big blind acts first, the button last");
        assertEquals("[A♠, A♦]", h.player(0).hole().toString(), "the cards dealt are the deck's");
    }

    @Test
    void threeOrMoreTheBlindsAreLeftOfTheButtonAndTheNextSeatStarts() {
        HoldemHand h = hand(new int[] {100, 100, 100, 100}, 1, List.of(), "");
        assertEquals(2, h.smallBlindAt());
        assertEquals(3, h.bigBlindAt());
        assertEquals(0, h.toAct(), "the seat after the big blind (round the table) acts first");
        assertEquals(3, h.pot());
        assertTrue(h.checkCall(0));
        assertTrue(h.checkCall(1));
        assertTrue(h.checkCall(2), "the small blind completes");
        assertEquals(3, h.toAct(), "the big blind's option");
        assertTrue(h.checkCall(3));
        assertEquals(HoldemHand.Street.FLOP, h.street());
        assertEquals(2, h.toAct(), "after the flop the first seat left of the button");
        assertFalse(h.checkCall(0), "nobody acts out of turn");
    }

    @Test
    void aRaiseIsAtLeastTheLastRaiseAndReopensTheRound() {
        HoldemHand h = hand(new int[] {100, 100, 100}, 0, List.of(), "");
        // button 0: SB 1, BB 2, the button acts first
        assertEquals(0, h.toAct());
        assertEquals(4, h.minRaiseTo(0), "the least raise is one big blind more");
        assertFalse(h.raiseTo(0, 3), "a raise below the least is refused");
        assertTrue(h.raiseTo(0, 6)); // by 4
        assertEquals(10, h.minRaiseTo(1), "now the least is 6 + 4");
        assertTrue(h.raiseTo(1, 10));
        assertTrue(h.checkCall(2));
        assertEquals(0, h.toAct(), "the first raiser has to answer the re-raise");
        assertTrue(h.canRaise(0));
        assertTrue(h.checkCall(0));
        assertEquals(HoldemHand.Street.FLOP, h.street());
        assertEquals(30, h.pot());
        assertEquals(2, h.minRaiseTo(1), "after the flop: the least is the big blind again");
    }

    @Test
    void everyoneFoldingToOneGivesThemEveryChipUnshown() {
        HoldemHand h = hand(new int[] {100, 100, 100}, 0, List.of(), "");
        assertTrue(h.raiseTo(0, 8));
        assertTrue(h.fold(1));
        assertTrue(h.fold(2));
        assertTrue(h.over());
        assertEquals(HoldemHand.Street.FOLDED, h.street());
        assertEquals(103, h.player(0).stack(), "the blinds and their own raise back");
        assertEquals(11, h.player(0).won());
        assertEquals(99, h.player(1).stack());
        assertEquals(98, h.player(2).stack());
        assertEquals(0, h.player(0).value() == null ? 0 : 1, "nothing shown");
        assertEquals(300, sum(h));
    }

    @Test
    void sidePotsAreLayeredByWhatEachPutIn() {
        // the big stack (the button) goes all in first; the short stack has the best hand, the middle the next best
        HoldemHand h = hand(new int[] {200, 50, 100}, 0, List.of("Qs Qd", "As Ad", "Ks Kd"), "2c 7d 9h Jc 3s");
        assertTrue(h.allIn(0));
        assertFalse(h.canRaise(1), "the short stack can only call, all in");
        assertTrue(h.allIn(1));
        assertTrue(h.allIn(2));
        assertTrue(h.over());
        assertEquals(5, h.board().size(), "the board is dealt out");
        List<HoldemHand.Pot> pots = h.pots();
        assertEquals(3, pots.size(), "the main pot, a side pot, and the big stack's unmatched chips");
        assertEquals(150, pots.get(0).amount());
        assertEquals(List.of(0, 1, 2), pots.get(0).eligible());
        assertEquals(List.of(1), pots.get(0).winners(), "the Aces take the main pot");
        assertEquals(100, pots.get(1).amount());
        assertEquals(List.of(0, 2), pots.get(1).eligible());
        assertEquals(List.of(2), pots.get(1).winners(), "the Kings beat the Queens for the side pot");
        assertEquals(100, pots.get(2).amount());
        assertEquals(List.of(0), pots.get(2).winners(), "nobody matched the last 100: they go back");
        assertEquals(100, h.player(0).stack());
        assertEquals(150, h.player(1).stack());
        assertEquals(100, h.player(2).stack());
        assertEquals("a Pair of Aces", h.player(1).value().name());
    }

    @Test
    void theLastPlayerAbleToActCallsRatherThanRaisesNobody() {
        HoldemHand h = hand(new int[] {50, 100, 200}, 0, List.of(), "");
        assertTrue(h.allIn(0));
        assertTrue(h.allIn(1));
        assertFalse(h.canRaise(2), "everyone else is all in: there is nobody to raise against");
        assertTrue(h.allIn(2), "so All in is a call");
        assertEquals(100, h.player(2).total());
        assertTrue(h.over());
        assertEquals(350, sum(h));
    }

    @Test
    void aFoldedPlayersChipsStayInThePotsButTheyCantWinThem() {
        HoldemHand h = hand(new int[] {30, 100, 100}, 0, List.of("2s 7d", "Ks Kd", "Qs Qd"), "2c 8d 9h Jc 3s");
        assertTrue(h.allIn(0)); // 30, all in
        assertTrue(h.raiseTo(1, 60));
        assertTrue(h.checkCall(2));
        // flop: the middle stack puts in, the last folds
        assertEquals(1, h.toAct());
        assertTrue(h.raiseTo(1, 20));
        assertTrue(h.fold(2));
        assertTrue(h.over());
        List<HoldemHand.Pot> pots = h.pots();
        assertEquals(90, pots.get(0).amount(), "the main pot: 30 from each");
        assertEquals(List.of(0, 1), pots.get(0).eligible(), "the folded player isn't in it");
        assertEquals(List.of(1), pots.get(0).winners());
        assertEquals(60 + 20, pots.get(1).amount(), "the side pot: 30 + 30 before the flop and 20 after");
        assertEquals(190, h.player(1).stack(), "Kings win both: 20 behind, 90 and 80 from the pots");
        assertEquals(0, h.player(0).stack());
        assertEquals(40, h.player(2).stack());
        assertEquals(230, sum(h));
    }

    @Test
    void anEqualHandSplitsAndTheOddChipGoesLeftOfTheButton() {
        // everyone plays the board's straight; the small blind folded, so the pot is odd
        HoldemHand h = hand(new int[] {100, 100, 100, 100}, 0, List.of("2s 3d", "4s 4d", "2h 3c", "2d 3h"),
                "Tc Jd Qh Kc Ad");
        assertTrue(h.checkCall(3)); // after the big blind (seat 2), round to seat 3
        assertTrue(h.checkCall(0));
        assertTrue(h.fold(1)); // the small blind folds its 1
        assertTrue(h.checkCall(2));
        for (int street = 0; street < 3; street++) {
            for (int k = 0; k < 3 && !h.over(); k++) {
                h.checkCall(h.toAct());
            }
        }
        assertTrue(h.over());
        assertEquals(HoldemHand.Street.SHOWDOWN, h.street());
        HoldemHand.Pot pot = h.pots().get(0);
        assertEquals(7, pot.amount());
        assertEquals(List.of(2, 3, 0), pot.winners(), "in order from the first seat left of the button");
        assertEquals(List.of(3, 2, 2), pot.shares(), "the odd chip to the first of them");
        assertEquals(99, h.player(1).stack());
        assertEquals(400, sum(h));
    }

    @Test
    void theKickerDecidesTheShowdown() {
        HoldemHand h = hand(new int[] {100, 100}, 0, List.of("Kh Qd", "Kd Jh"), "Ks 7c 4d 2s 9h");
        for (int k = 0; k < 8 && !h.over(); k++) {
            h.checkCall(h.toAct());
        }
        assertTrue(h.over());
        assertEquals(List.of(0), h.pots().get(0).winners(), "Kings with a Queen beat Kings with a Jack");
        assertEquals(102, h.player(0).stack());
    }

    @Test
    void anAllInForLessDoesntReopenTheRaising() {
        HoldemHand h = hand(new int[] {100, 100, 13}, 0, List.of(), "");
        // the button opens to 10 (by 8); the small blind calls; the big blind is all in for 13 (3 more: not a raise)
        assertTrue(h.raiseTo(0, 10));
        assertTrue(h.checkCall(1));
        assertEquals(2, h.toAct());
        assertTrue(h.allIn(2));
        assertEquals(13, h.currentBet());
        assertEquals(0, h.toAct());
        assertFalse(h.canRaise(0), "the opener already acted: only call or fold");
        assertTrue(h.checkCall(0));
        assertFalse(h.canRaise(1), "nor the small blind");
        assertTrue(h.checkCall(1));
        assertEquals(HoldemHand.Street.FLOP, h.street());
    }

    @Test
    void aFullRaiseAllInDoesReopenIt() {
        HoldemHand h = hand(new int[] {100, 100, 30}, 0, List.of(), "");
        assertTrue(h.raiseTo(0, 6));
        assertTrue(h.checkCall(1));
        assertTrue(h.allIn(2)); // to 30: by 24, a full raise
        assertTrue(h.canRaise(0), "a full raise reopens it");
    }

    @Test
    void aBlindBiggerThanTheStackIsAllInAndTheOthersStillMatchTheFullBlind() {
        HoldemHand h = hand(new int[] {100, 100, 1}, 0, List.of("As Ad", "Ks Kd", "2s 7d"), "3c 8d 9h Jc 4s");
        assertTrue(h.player(2).allIn(), "the big blind had 1 chip");
        assertEquals(2, h.toCall(0), "the others still put in the full big blind");
        assertTrue(h.checkCall(0));
        assertTrue(h.checkCall(1));
        for (int k = 0; k < 10 && !h.over(); k++) {
            h.checkCall(h.toAct());
        }
        assertTrue(h.over());
        assertEquals(201, sum(h));
        assertEquals(0, h.player(2).stack());
    }

    @Test
    void aForcedFoldOutOfTurnMovesTheHandOnOrEndsIt() {
        HoldemHand h = hand(new int[] {100, 100, 100}, 0, List.of(), "");
        h.forceFold(2); // the big blind leaves while the button is to act
        assertFalse(h.over());
        assertEquals(0, h.toAct());
        h.forceFold(0); // the button leaves on their own turn: the small blind is the last one in
        assertTrue(h.over());
        assertEquals(102, h.player(1).stack(), "the small blind takes the big blind's chips");
        assertEquals(300, sum(h));
    }

    @Test
    void theAwayMoveIsACheckWhenFreeElseAFold() {
        HoldemHand h = hand(new int[] {100, 100}, 0, List.of(), "");
        h.autoAct(0); // facing the big blind: a fold
        assertTrue(h.over());
        HoldemHand g = hand(new int[] {100, 100}, 0, List.of(), "");
        g.checkCall(0);
        g.autoAct(1); // the big blind's option: a check
        assertEquals(HoldemHand.Street.FLOP, g.street());
    }

    @Test
    void neverAChipMadeOrLostOverThousandsOfRandomHands() {
        SplittableRandom r = new SplittableRandom(2026);
        for (int n = 0; n < 4000; n++) {
            int players = 2 + r.nextInt(5);
            int[] stacks = new int[players];
            int total = 0;
            for (int i = 0; i < players; i++) {
                stacks[i] = 1 + r.nextInt(r.nextBoolean() ? 20 : 300);
                total += stacks[i];
            }
            HoldemHand h = new HoldemHand(stacks, r.nextInt(players), 1, 2, PokerCard.shuffled(r));
            int guard = 0;
            while (!h.over()) {
                assertTrue(guard++ < 500, "a hand ends");
                int i = h.toAct();
                assertTrue(i >= 0 && !h.player(i).folded() && !h.player(i).allIn(), "the turn is someone who can act");
                int pick = r.nextInt(10);
                if (pick == 0 && r.nextInt(3) == 0) {
                    h.forceFold(r.nextInt(players));
                } else if (pick < 2 && !h.canCheck(i)) {
                    assertTrue(h.fold(i));
                } else if (pick < 6) {
                    assertTrue(h.checkCall(i));
                } else if (pick < 8 && h.canRaise(i)) {
                    int to = h.minRaiseTo(i) + r.nextInt(Math.max(1, h.maxRaiseTo(i) - h.minRaiseTo(i) + 1));
                    assertTrue(h.raiseTo(i, to), "a raise between the least and everything goes");
                } else {
                    assertTrue(h.allIn(i));
                }
                if (!h.over()) {
                    assertEquals(total, sum(h) + h.pot(), "chips behind and in the pot add up, every move");
                }
            }
            int after = sum(h);
            assertEquals(total, after, "every chip went back to someone: " + n);
            int won = 0;
            for (int i = 0; i < players; i++) {
                assertTrue(h.player(i).stack() >= 0);
                won += h.player(i).won();
                if (h.player(i).folded()) {
                    assertEquals(0, h.player(i).won(), "a folded player never gets chips from the pot");
                }
            }
            assertEquals(h.pot(), won, "the pots were shared out exactly");
        }
    }

    @Test
    void aHandNeedsTwoPlayersWithChipsAndAWholeDeck() {
        assertThrows(IllegalArgumentException.class, () -> new HoldemHand(new int[] {100}, 0, 1, 2,
                PokerCard.shuffled(new SplittableRandom(1))));
        assertThrows(IllegalArgumentException.class, () -> new HoldemHand(new int[] {100, 0}, 0, 1, 2,
                PokerCard.shuffled(new SplittableRandom(1))));
        assertThrows(IllegalArgumentException.class, () -> new HoldemHand(new int[] {100, 100}, 0, 1, 2,
                new int[52]));
    }
}
