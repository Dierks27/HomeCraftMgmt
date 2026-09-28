package com.dierks.homecraft.games.cabinet.connect;

import java.util.ArrayList;
import java.util.List;

/**
 * A Connect Four board of any size up to 63 cells with their sentinel row (spec §10b, R3.14: the
 * cabinet plays 7 wide by 5 tall), with no Bukkit in sight.
 *
 * <p><b>Why bitboards.</b> The hard Arcade looks seven moves ahead, which is tens of thousands of
 * positions per move on the main thread. Each player's pieces are one {@code long}: column
 * {@code c}, row {@code r} (0 = the bottom) is bit {@code c·(rows+1)+r}, and the always-empty
 * sentinel bit on top of every column stops a line from wrapping into the next one. Four in a row
 * in any direction is then two shifts and two ANDs, and a move is one OR.
 *
 * <p>Player 0 moves first. Moves can be undone (the search plays and takes back), and the board
 * remembers the order they were made in.
 */
public final class ConnectFourBoard {

    /** The cabinet's board: 7 columns. */
    public static final int COLS = 7;
    /** The cabinet's board: 5 rows. */
    public static final int ROWS = 5;
    /** Pieces in a row that win. */
    public static final int CONNECT = 4;

    private final int cols;
    private final int rows;
    private final int h1;
    private final long[] bits = new long[2];
    private final int[] height;
    private final int[] history;
    private final long[] lines;
    /** The four directions as bit shifts: up, across, and the two diagonals. */
    private final int[] shifts;
    private int moves;

    /** The cabinet's 7 by 5 board. */
    public ConnectFourBoard() {
        this(COLS, ROWS);
    }

    public ConnectFourBoard(int cols, int rows) {
        if (cols < 1 || rows < 1 || cols * (rows + 1) > 63 || (cols < CONNECT && rows < CONNECT)) {
            throw new IllegalArgumentException("no room for four in a row on " + cols + "x" + rows);
        }
        this.cols = cols;
        this.rows = rows;
        this.h1 = rows + 1;
        this.height = new int[cols];
        this.history = new int[cols * rows];
        this.lines = lines(cols, rows);
        this.shifts = new int[] {1, h1, h1 - 1, h1 + 1};
    }

    /**
     * A board from a picture, top row first: {@code R} (player 0), {@code Y} (player 1) or
     * {@code .}, one string per row. For tests: pieces must rest on something, the move count is
     * the number of pieces (so red is to move when the counts are equal), and {@link #undo()} only
     * takes back moves made after this.
     */
    static ConnectFourBoard parse(String... picture) {
        int rows = picture.length;
        int cols = picture[0].length();
        ConnectFourBoard b = new ConnectFourBoard(cols, rows);
        for (int col = 0; col < cols; col++) {
            for (int row = 0; row < rows; row++) {
                char ch = picture[rows - 1 - row].charAt(col);
                if (ch == '.') {
                    continue;
                }
                if (b.height[col] != row) {
                    throw new IllegalArgumentException("a floating piece at column " + col + ", row " + row);
                }
                b.bits[ch == 'R' ? 0 : 1] |= b.bit(col, row);
                b.height[col]++;
                b.history[b.moves++] = col;
            }
        }
        return b;
    }

    /** Every line of four cells on a board this size, as masks (the evaluation and the win line use them). */
    private static long[] lines(int cols, int rows) {
        int h1 = rows + 1;
        int[][] dirs = {{1, 0}, {0, 1}, {1, 1}, {1, -1}};
        List<Long> out = new ArrayList<>();
        for (int c = 0; c < cols; c++) {
            for (int r = 0; r < rows; r++) {
                for (int[] d : dirs) {
                    int ec = c + d[0] * (CONNECT - 1);
                    int er = r + d[1] * (CONNECT - 1);
                    if (ec < 0 || ec >= cols || er < 0 || er >= rows) {
                        continue;
                    }
                    long mask = 0;
                    for (int k = 0; k < CONNECT; k++) {
                        mask |= 1L << ((c + d[0] * k) * h1 + (r + d[1] * k));
                    }
                    out.add(mask);
                }
            }
        }
        long[] arr = new long[out.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = out.get(i);
        }
        return arr;
    }

    public int cols() {
        return cols;
    }

    public int rows() {
        return rows;
    }

    /** Moves made so far. */
    public int moves() {
        return moves;
    }

    /** Whose move it is: 0 (moved first) or 1. */
    public int turn() {
        return moves & 1;
    }

    /** Whether a piece can go in {@code col}: on the board, not full, and nobody has won. */
    public boolean canPlay(int col) {
        return col >= 0 && col < cols && height[col] < rows && winner() < 0;
    }

    /**
     * Drop the next piece (the player whose turn it is) into {@code col}.
     *
     * @return the row it landed in (0 = the bottom), or -1 if it can't go there
     */
    public int play(int col) {
        if (!canPlay(col)) {
            return -1;
        }
        int row = height[col]++;
        bits[moves & 1] |= bit(col, row);
        history[moves++] = col;
        return row;
    }

    /** Take the last move back. */
    public void undo() {
        if (moves == 0) {
            return;
        }
        int col = history[--moves];
        int row = --height[col];
        bits[moves & 1] &= ~bit(col, row);
    }

    /** Who has a piece at {@code (col, row)}: 0, 1, or -1 for nobody. Row 0 is the bottom. */
    public int at(int col, int row) {
        long b = bit(col, row);
        if ((bits[0] & b) != 0) {
            return 0;
        }
        return (bits[1] & b) != 0 ? 1 : -1;
    }

    /** How many pieces are in {@code col}. */
    public int height(int col) {
        return height[col];
    }

    /** The winner, 0 or 1, or -1 while nobody has four in a row. */
    public int winner() {
        if (won(bits[0])) {
            return 0;
        }
        return won(bits[1]) ? 1 : -1;
    }

    /** Whether every cell is taken. */
    public boolean full() {
        return moves == cols * rows;
    }

    /** Whether the game is finished: a winner or a full board. */
    public boolean over() {
        return winner() >= 0 || full();
    }

    /** Whether {@code player} would win by dropping into {@code col} now (the board is unchanged). */
    public boolean winsWith(int col, int player) {
        if (col < 0 || col >= cols || height[col] >= rows) {
            return false;
        }
        return won(bits[player] | bit(col, height[col]));
    }

    /** Whether {@code (col, row)} is part of a winning four. */
    public boolean inWinLine(int col, int row) {
        int w = winner();
        if (w < 0) {
            return false;
        }
        long b = bit(col, row);
        for (long line : lines) {
            if ((line & b) != 0 && (line & bits[w]) == line) {
                return true;
            }
        }
        return false;
    }

    /** Player {@code p}'s pieces as a mask (for the Arcade's evaluation). */
    long bits(int p) {
        return bits[p];
    }

    /** Every line of four as a mask. */
    long[] lines() {
        return lines;
    }

    /** The mask of column {@code col}'s playable cells. */
    long column(int col) {
        return ((1L << rows) - 1) << (col * h1);
    }

    /** Whether {@code col} has room, without asking whether someone has already won (the search's fast path). */
    boolean room(int col) {
        return height[col] < rows;
    }

    private long bit(int col, int row) {
        return 1L << (col * h1 + row);
    }

    /** Whether the pieces in {@code b} hold four in a row in any direction. */
    boolean won(long b) {
        for (int s : shifts) {
            long m = b & (b >>> s);
            if ((m & (m >>> (2 * s))) != 0) {
                return true;
            }
        }
        return false;
    }
}
