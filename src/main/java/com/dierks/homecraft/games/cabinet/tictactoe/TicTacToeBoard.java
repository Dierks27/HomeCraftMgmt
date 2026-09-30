package com.dierks.homecraft.games.cabinet.tictactoe;

/**
 * A Tic-Tac-Toe board, with no Bukkit in sight (spec §10b). Cells 0-8 row by row; player 0 is X
 * and moves first, player 1 is O. Moves can be undone (the Arcade's search plays and takes back).
 */
public final class TicTacToeBoard {

    /** Cells on the board. */
    public static final int CELLS = 9;
    /** Every line of three. */
    static final int[][] LINES = {
            {0, 1, 2}, {3, 4, 5}, {6, 7, 8},
            {0, 3, 6}, {1, 4, 7}, {2, 5, 8},
            {0, 4, 8}, {2, 4, 6}
    };

    private final int[] cells = new int[CELLS];
    private final int[] history = new int[CELLS];
    private int moves;

    public TicTacToeBoard() {
        java.util.Arrays.fill(cells, -1);
    }

    /** Moves made so far. */
    public int moves() {
        return moves;
    }

    /** Whose move it is: 0 (X) or 1 (O). */
    public int turn() {
        return moves & 1;
    }

    /** Who has cell {@code i}: 0, 1, or -1 for nobody. */
    public int at(int i) {
        return cells[i];
    }

    /** Whether the next move can go in {@code i}: an empty cell, and nobody has won. */
    public boolean canPlay(int i) {
        return i >= 0 && i < CELLS && cells[i] < 0 && winner() < 0;
    }

    /** Put the next mark in {@code i}. @return false if it can't go there */
    public boolean play(int i) {
        if (!canPlay(i)) {
            return false;
        }
        cells[i] = moves & 1;
        history[moves++] = i;
        return true;
    }

    /** Take the last move back. */
    public void undo() {
        if (moves > 0) {
            cells[history[--moves]] = -1;
        }
    }

    /** The winner, 0 or 1, or -1 while nobody has three in a row. */
    public int winner() {
        for (int[] l : LINES) {
            int a = cells[l[0]];
            if (a >= 0 && a == cells[l[1]] && a == cells[l[2]]) {
                return a;
            }
        }
        return -1;
    }

    /** Whether every cell is taken. */
    public boolean full() {
        return moves == CELLS;
    }

    /** Whether the game is finished. */
    public boolean over() {
        return winner() >= 0 || full();
    }

    /** Whether cell {@code i} is part of a winning three. */
    public boolean inWinLine(int i) {
        for (int[] l : LINES) {
            int a = cells[l[0]];
            if (a >= 0 && a == cells[l[1]] && a == cells[l[2]] && (l[0] == i || l[1] == i || l[2] == i)) {
                return true;
            }
        }
        return false;
    }

    /** A number for this position, unique among positions (base 3: empty, X, O per cell). */
    int key() {
        int k = 0;
        for (int c : cells) {
            k = k * 3 + (c + 1);
        }
        return k;
    }
}
