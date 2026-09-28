package com.dierks.homecraft.games.chance.hilo;

import com.dierks.homecraft.games.chance.hilo.HigherLowerRun.Card;
import com.dierks.homecraft.games.chance.hilo.HigherLowerRun.End;
import com.dierks.homecraft.games.chance.hilo.HigherLowerRun.Side;
import com.dierks.homecraft.games.chance.hilo.HigherLowerRun.Terms;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One run of Higher or Lower as pure state (spec §5.6, R1.6, §5.2): cards from the seed, the
 * first-card swap, whole-token pots, the offer rule, every way a run cashes out by itself, the
 * round's data, and the exit rule.
 */
class HigherLowerRunTest {

    /** The shipped stake 10 (r = 0.915, top pot 200, 10 guesses). */
    private static final Terms TEN = new Terms(10, 915, 200, 10);

    private static long seedWhere(Terms t, Predicate<HigherLowerRun> test) {
        for (long seed = 1; seed < 2_000_000; seed++) {
            if (test.test(HigherLowerRun.start(seed, t))) {
                return seed;
            }
        }
        throw new AssertionError("no seed found");
    }

    @Test
    void everyCardIsTheSeedsNextDrawAndAFirstCardWithNoSideIsSwapped() {
        for (long seed = 1; seed <= 400; seed++) {
            HigherLowerRun run = HigherLowerRun.start(seed, TEN);
            SplittableRandom r = new SplittableRandom(seed);
            Card first = Card.of(r.nextInt(52));
            for (int i = 0; i < run.replaced(); i++) {
                assertFalse(HigherLowerRun.anySide(10, first.rank(), 915, 200),
                        "seed " + seed + ": only a card with no side is swapped");
                first = Card.of(r.nextInt(52));
            }
            assertEquals(first, run.shown(), "seed " + seed + ": the first playable card is the next draw");
            assertTrue(run.offered(Side.HIGHER) || run.offered(Side.LOWER), "a shown first card always has a side");
        }
        long swapped = seedWhere(TEN, run -> run.replaced() > 0);
        int firstDrawn = Card.of(new SplittableRandom(swapped).nextInt(52)).rank();
        assertTrue(firstDrawn == 2 || firstDrawn == 14, "at 10 in only a 2 or an Ace first is swapped: " + firstDrawn);
        assertEquals(14, Card.of(12).rank(), "draw 12 is an Ace (high)");
        assertEquals(2, Card.of(13).rank(), "draw 13 is the 2 of Hearts");
        assertEquals("an Ace", new Card(14, 0).withArticle());
        assertEquals("an 8", new Card(8, 0).withArticle());
    }

    @Test
    void aRightGuessSetsThePotToFloorPotTimesROverPAndAWrongOneEndsWithNothing() {
        long seed = seedWhere(TEN, run -> {
            Side s = run.likelierSide();
            return run.guess(s) && !run.done();
        });
        HigherLowerRun run = HigherLowerRun.start(seed, TEN);
        Side side = run.likelierSide();
        long expect = HigherLowerRun.potIfRight(10, HigherLowerRun.winners(run.shown().rank(), side), 915, 200);
        assertTrue(run.guess(side));
        assertEquals(expect, run.pot(), "the pot after a right guess");
        assertTrue(run.pot() > 10, "and it is more than was put in");
        assertTrue(run.canCashOut(), "cash out opens after a right guess");

        long wrong = seedWhere(TEN, r -> !r.guess(r.likelierSide()));
        HigherLowerRun lost = HigherLowerRun.start(wrong, TEN);
        assertFalse(lost.guess(lost.likelierSide()));
        assertEquals(End.WRONG, lost.end());
        assertEquals(0, lost.payout(), "a wrong guess gets nothing back");

        long same = seedWhere(TEN, r -> {
            int shown = r.shown().rank();
            r.guess(r.likelierSide());
            return r.cards().get(1).rank() == shown;
        });
        HigherLowerRun tie = HigherLowerRun.start(same, TEN);
        tie.guess(tie.likelierSide());
        assertEquals(End.WRONG, tie.end(), "the same rank loses");
    }

    @Test
    void youCantCashOutBeforeARightGuessOrGuessASideThatIsntOffered() {
        HigherLowerRun run = HigherLowerRun.start(7, TEN);
        assertFalse(run.canCashOut(), "the tokens in aren't a pot to cash out yet");
        assertThrows(IllegalArgumentException.class, run::cashOut);
        Terms rich = new Terms(20, 1000, 400, 10);
        long ace = seedWhere(rich, r -> r.shown().rank() == 14);
        HigherLowerRun onAce = HigherLowerRun.start(ace, rich);
        assertTrue(onAce.offered(Side.LOWER), "at r 1.000 an Ace first can go Lower (12 of 13)");
        assertFalse(onAce.offered(Side.HIGHER), "nothing is higher than an Ace");
        assertThrows(IllegalArgumentException.class, () -> onAce.guess(Side.HIGHER));
    }

    @Test
    void theRunCashesOutByItselfAtTheTopAtTheLastGuessAndWhenNoSideIsLeft() {
        Terms small = new Terms(10, 1000, 15, 10);
        long top = seedWhere(small, r -> r.guess(r.likelierSide()) && r.end() == End.TOP);
        HigherLowerRun atTop = HigherLowerRun.start(top, small);
        atTop.guess(atTop.likelierSide());
        assertEquals(End.TOP, atTop.end());
        assertEquals(15, atTop.payout(), "the top pot is the most a run pays");

        Terms one = new Terms(10, 915, 200, 1);
        long last = seedWhere(one, r -> r.guess(r.likelierSide()));
        HigherLowerRun oneGuess = HigherLowerRun.start(last, one);
        oneGuess.guess(oneGuess.likelierSide());
        assertEquals(End.LAST_GUESS, oneGuess.end(), "after the last allowed guess it cashes out");
        assertTrue(oneGuess.payout() > 10);

        long stuck = seedWhere(TEN, r -> r.guess(r.likelierSide()) && r.end() == End.NO_SIDE);
        HigherLowerRun noSide = HigherLowerRun.start(stuck, TEN);
        noSide.guess(noSide.likelierSide());
        int rank = noSide.shown().rank();
        assertTrue(rank == 2 || rank == 14 || !HigherLowerRun.anySide(noSide.pot(), rank, 915, 200),
                "a card with no side to offer");
        assertEquals((int) noSide.pot(), noSide.payout(), "cashed out for you, with what you had");
    }

    @Test
    void theSameSeedAndGuessesAlwaysGiveTheSameRun() {
        for (long seed = 1; seed <= 300; seed++) {
            HigherLowerRun live = HigherLowerRun.start(seed, TEN);
            while (!live.done()) {
                if (live.guesses() >= 2) {
                    live.cashOut();
                } else {
                    live.guess(live.likelierSide());
                }
            }
            HigherLowerRun again = HigherLowerRun.replay(seed, TEN, live.actions());
            assertEquals(live.cards(), again.cards(), "seed " + seed + ": the cards replay");
            assertEquals(live.payout(), again.payout(), "seed " + seed + ": the payout replays");
            assertEquals(live.end(), again.end());
        }
    }

    @Test
    void theRoundsDataHoldsEveryParameterAndEveryGuess() {
        String data = HigherLowerRun.data(TEN, "HLC");
        assertEquals("v=1;in=10;r=915;cap=200;g=10;a=HLC", data);
        assertEquals(TEN, HigherLowerRun.read(data).terms());
        assertEquals("HLC", HigherLowerRun.read(data).actions());
        assertThrows(IllegalArgumentException.class, () -> HigherLowerRun.read("v=9;in=10;r=915;cap=200;g=10;a="));
        assertThrows(IllegalArgumentException.class, () -> HigherLowerRun.read("v=1;in=x;r=915;cap=200;g=10;a="));
    }

    @Test
    void leavingBeforeAGuessTakesTheLikelierSideAndCashesOutIfRight() {
        for (long seed = 1; seed <= 500; seed++) {
            HigherLowerRun run = HigherLowerRun.start(seed, TEN);
            Side side = run.likelierSide();
            int shown = run.shown().rank();
            if (shown == 8) {
                assertEquals(Side.HIGHER, side, "an 8 is a tie (6 each way): Higher");
            } else if (run.offered(Side.HIGHER) && run.offered(Side.LOWER)) {
                assertEquals(shown < 8 ? Side.HIGHER : Side.LOWER, side, "seed " + seed + ": the likelier side");
            }
            int settled = HigherLowerRun.settleOnExit(seed, 10, HigherLowerRun.data(TEN, ""));
            boolean right = run.guess(side);
            assertEquals(right ? (int) run.pot() : 0, settled, "seed " + seed + ": one guess, cash out if right");
        }
    }

    @Test
    void leavingAfterARightGuessCashesOut() {
        long seed = seedWhere(TEN, r -> r.guess(r.likelierSide()) && !r.done());
        HigherLowerRun run = HigherLowerRun.start(seed, TEN);
        run.guess(run.likelierSide());
        assertEquals((int) run.pot(), HigherLowerRun.settleOnExit(seed, 10, HigherLowerRun.data(TEN, run.actions())),
                "the pot you had");
        assertNull(HigherLowerRun.start(seed, new Terms(10, 915, 200, 10)).lastGuess(), "no guess yet");
    }
}
