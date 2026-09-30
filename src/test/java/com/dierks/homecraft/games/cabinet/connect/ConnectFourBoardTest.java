package com.dierks.homecraft.games.cabinet.connect;

import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Connect Four board on the cabinet's 7 by 5 (spec R3.14) without a server.
 *
 * <p>Pinned here: pieces stack from the bottom and a full column refuses more; four in a row wins
 * in all four directions, including against every edge and the top row; three doesn't, and a line
 * never wraps from the top of one column to the bottom of the next or from the right edge to the
 * left; the win line is marked; undo puts a board back exactly; and a board can fill up with
 * nobody winning. Boards are drawn top row first: R = red (moves first), Y = yellow.
 */
class ConnectFourBoardTest {

    @Test
    void theCabinetBoardIsSevenWideAndFiveTall() {
        ConnectFourBoard b = new ConnectFourBoard();
        assertEquals(7, b.cols(), "seven columns (R3.14)");
        assertEquals(5, b.rows(), "five rows (R3.14)");
        assertThrows(IllegalArgumentException.class, () -> new ConnectFourBoard(9, 7),
                "a board too big for one long is refused, not silently wrong");
    }

    @Test
    void piecesStackFromTheBottomAndAFullColumnRefusesMore() {
        ConnectFourBoard b = new ConnectFourBoard();
        for (int row = 0; row < 5; row++) {
            assertEquals(row, b.play(0), "piece " + row + " lands on top of the last");
        }
        assertFalse(b.canPlay(0), "five pieces fill a column");
        assertEquals(-1, b.play(0), "a sixth is refused");
        assertEquals(5, b.moves(), "and not counted");
        assertEquals(0, b.at(0, 0), "red moved first, into the bottom cell");
        assertEquals(1, b.at(0, 1), "yellow next, on top");
        assertEquals(-1, b.at(1, 0), "an empty cell belongs to nobody");
        assertEquals(-1, b.play(7), "off the board");
        assertEquals(-1, b.play(-1), "off the board");
    }

    @Test
    void playedMovesWinAcrossTheBottomAgainstTheRightEdge() {
        ConnectFourBoard b = new ConnectFourBoard();
        int[] moves = {3, 3, 4, 4, 5, 5};
        for (int col : moves) {
            b.play(col);
            assertEquals(-1, b.winner(), "no four yet");
        }
        b.play(6);
        assertEquals(0, b.winner(), "red's fourth piece across the bottom, at the right edge, wins");
        assertTrue(b.over(), "and ends the game");
    }

    @Test
    void fourAcrossWinsOnTheTopRow() {
        assertEquals(1, ConnectFourBoard.parse(
                "YYYY...",
                "RRRY...",
                "YYYR...",
                "RRRY...",
                "RYRR...").winner(), "four across the top row");
    }

    @Test
    void fourUpWinsInTheFirstAndLastColumns() {
        assertEquals(0, ConnectFourBoard.parse(
                "R......",
                "R......",
                "R......",
                "R......",
                "Y.....Y").winner(), "four up the first column, rows 1 to 4");
        assertEquals(1, ConnectFourBoard.parse(
                ".......",
                "......Y",
                "......Y",
                "......Y",
                "RRR...Y").winner(), "four up the last column from the bottom");
    }

    @Test
    void fourDiagonallyUpAndDownWins() {
        assertEquals(0, ConnectFourBoard.parse(
                ".......",
                "...R...",
                "..RY...",
                ".RYY...",
                "RYYR...").winner(), "a rising diagonal from the bottom-left corner");
        assertEquals(0, ConnectFourBoard.parse(
                "...R...",
                "...YR..",
                "...YYR.",
                "...RYYR",
                "...YRRY").winner(), "a falling diagonal into the right edge, starting on the top row");
        ConnectFourBoard high = ConnectFourBoard.parse(
                "......R",
                ".....RY",
                "....RYY",
                "...RYYR",
                "...YRRY");
        assertEquals(0, high.winner(), "a rising diagonal ending in the top-right corner");
        assertTrue(high.inWinLine(6, 4) && high.inWinLine(5, 3) && high.inWinLine(4, 2) && high.inWinLine(3, 1),
                "the winning four is marked for the screen");
        assertFalse(high.inWinLine(3, 0), "nothing else is");
    }

    @Test
    void threeInARowAndWrappedLinesDontWin() {
        assertEquals(-1, ConnectFourBoard.parse(
                ".......",
                ".......",
                ".......",
                "YYY....",
                "RRR....").winner(), "three is not four");
        assertEquals(-1, ConnectFourBoard.parse(
                "R......",
                "R......",
                "Y......",
                "YR.....",
                "YR.....").winner(), "the top of one column and the bottom of the next are not a line");
        assertEquals(-1, ConnectFourBoard.parse(
                ".......",
                ".......",
                ".......",
                "RR...YY",
                "YY...RR").winner(), "the right edge doesn't join the left edge");
    }

    @Test
    void nothingCanBePlayedAfterAWin() {
        ConnectFourBoard b = ConnectFourBoard.parse(
                ".......",
                ".......",
                ".......",
                "YYY....",
                "RRRR...");
        assertTrue(b.over(), "four in a row ends the game");
        for (int c = 0; c < 7; c++) {
            assertFalse(b.canPlay(c), "column " + c + " is closed once someone has won");
        }
    }

    @Test
    void winsWithLooksWithoutTouching() {
        ConnectFourBoard b = ConnectFourBoard.parse(
                ".......",
                ".......",
                ".......",
                "YYY....",
                "RRR....");
        int moves = b.moves();
        assertTrue(b.winsWith(3, 0), "red would win in column 3");
        assertFalse(b.winsWith(3, 1), "yellow's three are on row 1; a yellow piece in column 3 lands on row 0");
        assertEquals(moves, b.moves(), "looking changes nothing");
        assertEquals(-1, b.at(3, 0), "the cell is still empty");
    }

    @Test
    void undoPutsTheBoardBackExactly() {
        ConnectFourBoard b = new ConnectFourBoard();
        SplittableRandom rng = new SplittableRandom(4);
        for (int i = 0; i < 12; i++) {
            b.play(rng.nextInt(7));
        }
        String before = snapshot(b);
        int moves = b.moves();
        int turn = b.turn();
        b.play(3);
        b.play(2);
        b.undo();
        b.undo();
        assertEquals(before, snapshot(b), "two moves and two undos leave the same board");
        assertEquals(moves, b.moves(), "and the same move count");
        assertEquals(turn, b.turn(), "and the same player to move");
    }

    @Test
    void aBoardCanFillUpWithNobodyWinning() {
        SplittableRandom rng = new SplittableRandom(2026);
        for (int game = 0; game < 10_000; game++) {
            ConnectFourBoard b = new ConnectFourBoard();
            while (!b.over()) {
                int col = rng.nextInt(7);
                if (b.canPlay(col)) {
                    b.play(col);
                }
            }
            if (b.winner() < 0) {
                assertTrue(b.full(), "a finished game with no winner is a full board");
                assertEquals(35, b.moves(), "every cell of 7x5 taken");
                return;
            }
        }
        throw new AssertionError("ten thousand random games and not one draw: the full-board check is suspect");
    }

    private static String snapshot(ConnectFourBoard b) {
        StringBuilder s = new StringBuilder();
        for (int c = 0; c < b.cols(); c++) {
            for (int r = 0; r < b.rows(); r++) {
                s.append(b.at(c, r) + 1);
            }
        }
        return s.toString();
    }
}
