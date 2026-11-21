package com.dierks.homecraft.games.cpu;

import com.dierks.homecraft.games.cabinet.connect.ConnectFourAI;
import com.dierks.homecraft.games.cabinet.connect.ConnectFourBoard;
import com.dierks.homecraft.games.cabinet.tictactoe.TicTacToeAI;
import com.dierks.homecraft.games.cabinet.tictactoe.TicTacToeBoard;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * An NPC's move in Connect Four and Tic-Tac-Toe, by the level it plays at in that game. Pure; the generator is the
 * game's own, seeded once per game.
 *
 * <ul>
 *   <li><b>Connect Four</b> uses the Arcade's own player ({@link ConnectFourAI}): easy looks two moves ahead and one
 *       move in four drops a piece anywhere (it blunders, so a child can win), medium looks four ahead, hard seven.
 *       Medium and hard always take a four they can see and block one they must.</li>
 *   <li><b>Tic-Tac-Toe</b>: easy plays anywhere two moves in three (it can be beaten); medium always takes three in a
 *       row and blocks one, and otherwise plays its best only half the time, so a fork beats it; hard plays perfectly
 *       ({@link TicTacToeAI}), as the hard Arcade does.</li>
 * </ul>
 */
public final class CpuMoves {

    private CpuMoves() {
    }

    /** The Arcade's Connect Four strength an NPC at {@code level} plays with. */
    public static ConnectFourAI.Level connectFourLevel(CpuLevel level) {
        return switch (level) {
            case EASY -> ConnectFourAI.Level.EASY;
            case MEDIUM -> ConnectFourAI.Level.NORMAL;
            case HARD -> ConnectFourAI.Level.HARD;
        };
    }

    /** The column an NPC at {@code level} drops into, or -1 when there is none. */
    public static int connectFour(ConnectFourBoard board, CpuLevel level, SplittableRandom rng) {
        return ConnectFourAI.choose(board, connectFourLevel(level), rng);
    }

    /** The cell an NPC at {@code level} plays, or -1 when the game is over. */
    public static int ticTacToe(TicTacToeBoard board, CpuLevel level, SplittableRandom rng) {
        List<Integer> legal = new ArrayList<>();
        for (int i = 0; i < TicTacToeBoard.CELLS; i++) {
            if (board.canPlay(i)) {
                legal.add(i);
            }
        }
        if (legal.isEmpty()) {
            return -1;
        }
        return switch (level) {
            case EASY -> TicTacToeAI.choose(board, TicTacToeAI.Level.EASY, rng);
            case HARD -> TicTacToeAI.choose(board, TicTacToeAI.Level.HARD, rng);
            case MEDIUM -> {
                int me = board.turn();
                int win = completes(board, legal, me);
                if (win >= 0) {
                    yield win;
                }
                int block = completes(board, legal, 1 - me);
                if (block >= 0) {
                    yield block;
                }
                yield rng.nextBoolean() ? TicTacToeAI.choose(board, TicTacToeAI.Level.HARD, rng)
                        : legal.get(rng.nextInt(legal.size()));
            }
        };
    }

    /** A free cell that gives {@code who} three in a row, or -1. */
    static int completes(TicTacToeBoard board, List<Integer> legal, int who) {
        for (int cell : legal) {
            if (wins(board, cell, who)) {
                return cell;
            }
        }
        return -1;
    }

    /** Whether {@code who} taking {@code cell} makes three in a row. */
    static boolean wins(TicTacToeBoard board, int cell, int who) {
        for (int[] line : LINES) {
            boolean in = false;
            int mine = 0;
            for (int c : line) {
                if (c == cell) {
                    in = true;
                } else if (board.at(c) == who) {
                    mine++;
                }
            }
            if (in && mine == 2) {
                return true;
            }
        }
        return false;
    }

    private static final int[][] LINES = {{0, 1, 2}, {3, 4, 5}, {6, 7, 8}, {0, 3, 6}, {1, 4, 7}, {2, 5, 8},
            {0, 4, 8}, {2, 4, 6}};
}
