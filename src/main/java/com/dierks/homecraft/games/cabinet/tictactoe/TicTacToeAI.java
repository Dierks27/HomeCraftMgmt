package com.dierks.homecraft.games.cabinet.tictactoe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

/**
 * The Arcade's Tic-Tac-Toe player (spec §10b).
 *
 * <ul>
 *   <li><b>Easy</b> is random-ish: two moves in three it picks any free cell, the third it plays
 *       its best. A child can win.</li>
 *   <li><b>Hard</b> is perfect: a full minimax over every position (there are fewer than 20,000),
 *       remembered as it goes. It can't be beaten, so a draw is the best anyone can do — and the
 *       screen says so up front.</li>
 * </ul>
 *
 * <p>Among equally good moves it picks with the game's own random generator, so the Arcade
 * doesn't play the same game every time; the same seed replays exactly.
 */
public final class TicTacToeAI {

    /** How strong the Arcade plays. */
    public enum Level {
        EASY,
        HARD
    }

    private static final int UNKNOWN = Integer.MIN_VALUE;
    /** Each position's value for the player to move, by {@link TicTacToeBoard#key()}. */
    private static final int[] MEMO = new int[19_683];

    static {
        Arrays.fill(MEMO, UNKNOWN);
    }

    private TicTacToeAI() {
    }

    /** The cell the Arcade plays for the player whose turn it is, or -1 if the game is over. */
    public static int choose(TicTacToeBoard board, Level level, SplittableRandom rng) {
        List<Integer> legal = new ArrayList<>();
        for (int i = 0; i < TicTacToeBoard.CELLS; i++) {
            if (board.canPlay(i)) {
                legal.add(i);
            }
        }
        if (legal.isEmpty()) {
            return -1;
        }
        if (level == Level.EASY && rng.nextInt(3) != 0) {
            return legal.get(rng.nextInt(legal.size()));
        }
        List<Integer> best = bestMoves(board);
        return best.get(rng.nextInt(best.size()));
    }

    /** Every move that keeps the best result for the player to move (win soonest, lose latest). */
    static List<Integer> bestMoves(TicTacToeBoard board) {
        int top = Integer.MIN_VALUE;
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < TicTacToeBoard.CELLS; i++) {
            if (!board.canPlay(i)) {
                continue;
            }
            board.play(i);
            int v = -value(board);
            board.undo();
            if (v > top) {
                top = v;
                out.clear();
                out.add(i);
            } else if (v == top) {
                out.add(i);
            }
        }
        return out;
    }

    /**
     * The value of the position for the player to move with perfect play on both sides: positive
     * = a win (10 minus the moves it takes to get there, so sooner is more), 0 = a draw, negative
     * = a loss (later is less bad).
     */
    static int value(TicTacToeBoard board) {
        int key = board.key();
        int known = MEMO[key];
        if (known != UNKNOWN) {
            return known;
        }
        int v;
        if (board.winner() >= 0) {
            v = -(10 - board.moves());
        } else if (board.full()) {
            v = 0;
        } else {
            v = Integer.MIN_VALUE;
            for (int i = 0; i < TicTacToeBoard.CELLS; i++) {
                if (board.canPlay(i)) {
                    board.play(i);
                    v = Math.max(v, -value(board));
                    board.undo();
                }
            }
        }
        MEMO[key] = v;
        return v;
    }
}
