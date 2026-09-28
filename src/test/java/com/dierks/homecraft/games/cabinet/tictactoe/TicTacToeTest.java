package com.dierks.homecraft.games.cabinet.tictactoe;

import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tic-Tac-Toe's board, the Arcade's play and one game's outcomes, without a server.
 *
 * <p>Pinned here: every line of three wins and nothing is played after; a full board with no line
 * is a draw; the empty board is a draw with perfect play; the hard Arcade never loses — against
 * every possible sequence of opponent moves, as X and as O, whichever of its equal best moves it
 * picks — and from every reachable position it keeps the best result there is; easy can be
 * beaten; and only a win on easy, or a draw or better on hard, earns the day's reward (never a
 * friend game).
 */
class TicTacToeTest {

    private static final UUID ANNA = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BEN = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @Test
    void everyLineOfThreeWinsAndEndsTheGame() {
        for (int[] line : TicTacToeBoard.LINES) {
            TicTacToeBoard b = new TicTacToeBoard();
            int filler = 0;
            for (int k = 0; k < 3; k++) {
                assertTrue(b.play(line[k]), "X plays " + line[k]);
                if (k < 2) {
                    while (contains(line, filler) || !b.canPlay(filler)) {
                        filler++;
                    }
                    b.play(filler);
                }
            }
            assertEquals(0, b.winner(), "X wins with " + java.util.Arrays.toString(line));
            for (int k = 0; k < 3; k++) {
                assertTrue(b.inWinLine(line[k]), "the winning cells are marked");
            }
            for (int i = 0; i < 9; i++) {
                assertFalse(b.canPlay(i), "nothing is played after a win");
            }
        }
    }

    @Test
    void aFullBoardWithNoLineIsADraw() {
        TicTacToeBoard b = new TicTacToeBoard();
        for (int cell : new int[] {0, 1, 2, 4, 3, 5, 7, 6, 8}) {
            assertTrue(b.play(cell), "cell " + cell + " is free");
        }
        assertEquals(-1, b.winner(), "X O X / X O O / O X X has no line of three");
        assertTrue(b.full() && b.over(), "a full board ends the game");
    }

    @Test
    void undoTakesBackTheLastMark() {
        TicTacToeBoard b = new TicTacToeBoard();
        b.play(4);
        b.play(0);
        b.undo();
        assertEquals(-1, b.at(0), "the O is gone");
        assertEquals(0, b.at(4), "the X stays");
        assertEquals(1, b.turn(), "and it's O's turn again");
    }

    @Test
    void perfectPlayFromTheStartIsADraw() {
        assertEquals(0, TicTacToeAI.value(new TicTacToeBoard()), "Tic-Tac-Toe is a draw when both play perfectly");
    }

    @Test
    void theHardArcadeNeverLosesToAnyOpponent() {
        for (int seat = 0; seat < 2; seat++) {
            int[] results = new int[3];
            explore(new TicTacToeBoard(), seat, results);
            assertEquals(0, results[2], "the hard Arcade as " + (seat == 0 ? "X" : "O")
                    + " lost " + results[2] + " games against some line of play");
            assertTrue(results[0] + results[1] > 0, "the whole tree was explored");
        }
    }

    @Test
    void theHardArcadeKeepsTheBestResultFromEveryReachablePosition() {
        int[] checked = new int[1];
        everyPosition(new TicTacToeBoard(), checked);
        assertTrue(checked[0] > 4_000, "every reachable position was checked, got " + checked[0]);
    }

    @Test
    void theHardArcadeTakesAWinAndBlocksALine() {
        TicTacToeBoard win = board(0, 3, 1, 4);
        assertEquals(2, TicTacToeAI.choose(win, TicTacToeAI.Level.HARD, new SplittableRandom(1)),
                "X completes the top row");
        TicTacToeBoard block = board(0, 4, 1);
        assertEquals(2, TicTacToeAI.choose(block, TicTacToeAI.Level.HARD, new SplittableRandom(1)),
                "O must block the top row");
    }

    @Test
    void theEasyArcadeCanBeBeaten() {
        int wins = 0;
        for (long seed = 0; seed < 100; seed++) {
            TicTacToeMatch m = TicTacToeMatch.vsArcade(ANNA, TicTacToeAI.Level.EASY, seed);
            while (!m.over()) {
                m.play(ANNA, TicTacToeAI.bestMoves(m.board()).get(0));
                m.arcadeMove();
            }
            if (m.outcome(ANNA) == TicTacToeMatch.Outcome.WON) {
                wins++;
            }
        }
        assertTrue(wins > 30, "a careful player beats easy often, won " + wins + " of 100");
    }

    @Test
    void onlyAnEasyWinOrAHardDrawOrBetterEarnsTheDay() {
        assertTrue(finished(TicTacToeAI.Level.EASY, 0, 3, 1, 4, 2).earnsDaily(ANNA), "an easy win earns it");
        assertFalse(finished(TicTacToeAI.Level.EASY, 0, 1, 2, 4, 3, 5, 7, 6, 8).earnsDaily(ANNA),
                "an easy draw doesn't");
        assertTrue(finished(TicTacToeAI.Level.HARD, 0, 1, 2, 4, 3, 5, 7, 6, 8).earnsDaily(ANNA),
                "a hard draw does: it's the best anyone can do");
        assertFalse(finished(TicTacToeAI.Level.HARD, 0, 3, 1, 4, 8, 5).earnsDaily(ANNA), "a hard loss doesn't");
        TicTacToeMatch friends = TicTacToeMatch.friends(ANNA, BEN);
        for (int cell : new int[] {0, 3, 1, 4, 2}) {
            UUID who = friends.board().turn() == 0 ? ANNA : BEN;
            assertTrue(friends.play(who, cell), "a friend game move");
        }
        assertEquals(TicTacToeMatch.Outcome.WON, friends.outcome(ANNA), "Anna won the friend game");
        assertFalse(friends.earnsDaily(ANNA), "friend games pay nothing");
    }

    @Test
    void theDailyResultIsTheOnlyReward() {
        TicTacToeSettings s = TicTacToeSettings.defaults();
        assertEquals(0, s.milestoneReward(), "Tic-Tac-Toe has no milestones (spec §10b)");
        assertTrue(s.milestonesFor(TicTacToe.BOARD).isEmpty(), "not even on the wins board");
        assertEquals(1, s.dailyReward(), "the day's first easy win or hard draw pays one token");
        assertEquals(1, s.dailyCap(), "and that's all it pays in a day");
    }

    @Test
    void turnsAndLeavingInAFriendGame() {
        TicTacToeMatch m = TicTacToeMatch.friends(ANNA, BEN);
        assertFalse(m.play(BEN, 4), "O can't start");
        assertTrue(m.play(ANNA, 4), "X starts");
        assertFalse(m.play(BEN, 4), "a taken cell can't be played");
        assertEquals(1, m.version(), "one change so far");
        assertTrue(m.leave(ANNA), "Anna leaves");
        assertEquals(TicTacToeMatch.Outcome.OTHER_LEFT, m.outcome(BEN), "Ben sees she left");
        assertFalse(m.play(BEN, 0), "and nobody moves after");
    }

    /** Arcade plays every best move, the opponent every legal move; results = {wins, draws, losses}. */
    private static void explore(TicTacToeBoard b, int arcade, int[] results) {
        if (b.over()) {
            int w = b.winner();
            results[w < 0 ? 1 : (w == arcade ? 0 : 2)]++;
            return;
        }
        if (b.turn() == arcade) {
            for (int cell : TicTacToeAI.bestMoves(b)) {
                b.play(cell);
                explore(b, arcade, results);
                b.undo();
            }
        } else {
            for (int i = 0; i < 9; i++) {
                if (b.canPlay(i)) {
                    b.play(i);
                    explore(b, arcade, results);
                    b.undo();
                }
            }
        }
    }

    /** Every reachable position, any history: the hard Arcade's pick never gives away the result. */
    private static void everyPosition(TicTacToeBoard b, int[] checked) {
        if (b.over()) {
            return;
        }
        int value = TicTacToeAI.value(b);
        for (long seed = 0; seed < 2; seed++) {
            int cell = TicTacToeAI.choose(b, TicTacToeAI.Level.HARD, new SplittableRandom(seed));
            b.play(cell);
            int after = -TicTacToeAI.value(b);
            b.undo();
            assertEquals(value, after, "the hard Arcade's move keeps the position's best result");
        }
        checked[0]++;
        for (int i = 0; i < 9; i++) {
            if (b.canPlay(i)) {
                b.play(i);
                everyPosition(b, checked);
                b.undo();
            }
        }
    }

    private static TicTacToeBoard board(int... cells) {
        TicTacToeBoard b = new TicTacToeBoard();
        for (int c : cells) {
            b.play(c);
        }
        return b;
    }

    /** A game against the Arcade played out move by move from the given cells (X = Anna). */
    private static TicTacToeMatch finished(TicTacToeAI.Level level, int... cells) {
        TicTacToeMatch m = TicTacToeMatch.vsArcade(ANNA, level, 0);
        for (int c : cells) {
            m.board().play(c);
        }
        assertTrue(m.over(), "the scripted game finished");
        return m;
    }

    private static boolean contains(int[] line, int cell) {
        return line[0] == cell || line[1] == cell || line[2] == cell;
    }
}
