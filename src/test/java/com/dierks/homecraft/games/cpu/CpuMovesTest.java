package com.dierks.homecraft.games.cpu;

import com.dierks.homecraft.games.cabinet.connect.ConnectFourBoard;
import com.dierks.homecraft.games.cabinet.tictactoe.TicTacToeAI;
import com.dierks.homecraft.games.cabinet.tictactoe.TicTacToeBoard;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An NPC's moves in Connect Four and Tic-Tac-Toe: always legal; medium and hard take a win they can see and block one
 * they must; easy blunders (so a child can win); and Tic-Tac-Toe's easier NPCs can be beaten.
 */
class CpuMovesTest {

    private static ConnectFourBoard c4(int... cols) {
        ConnectFourBoard b = new ConnectFourBoard();
        for (int c : cols) {
            b.play(c);
        }
        return b;
    }

    private static TicTacToeBoard ttt(int... cells) {
        TicTacToeBoard b = new TicTacToeBoard();
        for (int c : cells) {
            b.play(c);
        }
        return b;
    }

    @Test
    void connectFourMediumAndHardTakeTheWinAndBlockTheThreat() {
        for (CpuLevel level : new CpuLevel[]{CpuLevel.MEDIUM, CpuLevel.HARD}) {
            for (long seed = 0; seed < 20; seed++) {
                // red has 0, 1, 2 on the bottom row and moves: 3 wins
                assertEquals(3, CpuMoves.connectFour(c4(0, 0, 1, 1, 2, 2), level, new SplittableRandom(seed)),
                        level + " takes the four");
                // red threatens 3 on the bottom row; yellow (the NPC) has nothing: it must block
                assertEquals(3, CpuMoves.connectFour(c4(0, 6, 1, 6, 2), level, new SplittableRandom(seed)),
                        level + " blocks the four");
            }
        }
    }

    @Test
    void connectFourEasyBlundersMoreThanMediumAndHardNever() {
        int[] blocks = new int[3];
        for (CpuLevel level : CpuLevel.values()) {
            for (long seed = 0; seed < 300; seed++) {
                ConnectFourBoard b = c4(0, 6, 1, 6, 2);
                int col = CpuMoves.connectFour(b, level, new SplittableRandom(seed));
                assertTrue(b.canPlay(col), "always a legal column");
                if (col == 3) {
                    blocks[level.ordinal()]++;
                }
            }
        }
        assertTrue(blocks[0] < blocks[1], "easy misses a block now and then: " + blocks[0]);
        assertEquals(300, blocks[1], "medium always blocks");
        assertEquals(300, blocks[2], "hard always blocks");
    }

    @Test
    void ticTacToeMediumAndHardTakeTheWinAndBlockTheThreat() {
        for (CpuLevel level : new CpuLevel[]{CpuLevel.MEDIUM, CpuLevel.HARD}) {
            for (long seed = 0; seed < 50; seed++) {
                assertEquals(2, CpuMoves.ticTacToe(ttt(0, 3, 1, 4), level, new SplittableRandom(seed)),
                        level + ": X has 0 and 1, and takes 2");
                assertEquals(2, CpuMoves.ticTacToe(ttt(0, 3, 1), level, new SplittableRandom(seed)),
                        level + ": O must block 2");
            }
        }
    }

    @Test
    void ticTacToeEasyBlocksLessOftenAndEveryMoveIsLegal() {
        int[] blocks = new int[3];
        for (CpuLevel level : CpuLevel.values()) {
            for (long seed = 0; seed < 300; seed++) {
                TicTacToeBoard b = ttt(0, 3, 1);
                int cell = CpuMoves.ticTacToe(b, level, new SplittableRandom(seed));
                assertTrue(b.canPlay(cell), "always a free cell");
                if (cell == 2) {
                    blocks[level.ordinal()]++;
                }
            }
        }
        assertTrue(blocks[0] < 300 && blocks[0] < blocks[1], "easy blunders: " + blocks[0]);
        assertEquals(300, blocks[1]);
        assertEquals(300, blocks[2]);
        assertEquals(-1, CpuMoves.ticTacToe(ttt(0, 3, 1, 4, 2), CpuLevel.HARD, new SplittableRandom(1)),
                "a finished game: no move");
    }

    /** Perfect X against an NPC as O, from an empty board: who won (0 X, 1 O, -1 a draw). */
    private static int game(CpuLevel npc, long seed) {
        TicTacToeBoard b = new TicTacToeBoard();
        SplittableRandom xs = new SplittableRandom(seed * 31 + 7);
        SplittableRandom os = new SplittableRandom(seed);
        while (!b.over()) {
            int cell = b.turn() == 0 ? TicTacToeAI.choose(b, TicTacToeAI.Level.HARD, xs) : CpuMoves.ticTacToe(b, npc, os);
            b.play(cell);
        }
        return b.winner();
    }

    @Test
    void ticTacToesEasierNpcsCanBeBeatenAndHardCant() {
        int easyLosses = 0;
        int mediumLosses = 0;
        for (long seed = 0; seed < 300; seed++) {
            easyLosses += game(CpuLevel.EASY, seed) == 0 ? 1 : 0;
            mediumLosses += game(CpuLevel.MEDIUM, seed) == 0 ? 1 : 0;
            assertTrue(game(CpuLevel.HARD, seed) != 0, "hard never loses");
        }
        assertTrue(easyLosses > 0, "easy can be beaten");
        assertTrue(mediumLosses > 0, "medium can be beaten (a fork)");
        assertTrue(easyLosses > mediumLosses, "easy is beaten more often than medium: " + easyLosses + " vs "
                + mediumLosses);
    }
}
