package com.dierks.homecraft.games.chance.bingo;

import java.util.Arrays;
import java.util.SplittableRandom;

/**
 * One Bingo Night card (0.57.0, pure): classic 75-ball, five columns B I N G O of five squares each, the middle square
 * free. Column B holds numbers 1-15, I 16-30, N 31-45, G 46-60 and O 61-75, five different ones each (N four, around
 * the free square), in a random order down the column.
 *
 * <p><b>Every card is dealt the same way</b> ({@link #deal}): from its own seed, whoever holds it. A real player's
 * cards, an NPC's and the room's own are drawn from one rule and are not told apart by it, which is what lets Bingo
 * Night's prize be shared fairly (see {@link BingoPrize}): no card is ever more likely than another to make a line
 * first.
 *
 * <p><b>Patterns</b>: a line is any full row, column or diagonal ({@link #LINES}, twelve); four corners is the four
 * corner squares. The free square counts as daubed from the start. Which call a pattern is complete on depends only on
 * the order the numbers are called in ({@code rank[n]}: on which call, from 0, number {@code n} is called).
 *
 * <p>Squares are numbered 0-24 by row, left to right ({@code row * 5 + column}); the free one is {@value #FREE}.
 */
public final class BingoCard {

    /** Squares across and down. */
    public static final int SIZE = 5;
    /** Squares on a card. */
    public static final int SQUARES = SIZE * SIZE;
    /** The free middle square. */
    public static final int FREE = 12;
    /** Numbers called in all: 1 to 75. */
    public static final int BALLS = 75;
    /** Numbers in each column. */
    public static final int PER_COLUMN = BALLS / SIZE;
    /** The columns' letters. */
    public static final String LETTERS = "BINGO";

    /** The twelve lines: five rows, five columns, the two diagonals. */
    public static final int[][] LINES = lines();
    /** The four corners. */
    public static final int[] CORNERS = {0, SIZE - 1, SQUARES - SIZE, SQUARES - 1};

    private final int[] numbers;

    private BingoCard(int[] numbers) {
        this.numbers = numbers;
    }

    /** The card dealt from {@code seed}: the same seed, the same card. */
    public static BingoCard deal(long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        int[] n = new int[SQUARES];
        for (int col = 0; col < SIZE; col++) {
            int[] pool = new int[PER_COLUMN];
            for (int i = 0; i < PER_COLUMN; i++) {
                pool[i] = col * PER_COLUMN + i + 1;
            }
            for (int row = 0; row < SIZE; row++) { // a partial shuffle: the first five of a random order
                int j = row + r.nextInt(PER_COLUMN - row);
                int t = pool[row];
                pool[row] = pool[j];
                pool[j] = t;
                n[row * SIZE + col] = pool[row];
            }
        }
        n[FREE] = 0;
        return new BingoCard(n);
    }

    /** A card from its 25 numbers, 0 in the middle (tests). */
    static BingoCard of(int... numbers) {
        if (numbers.length != SQUARES) {
            throw new IllegalArgumentException("a card has " + SQUARES + " squares");
        }
        return new BingoCard(numbers.clone());
    }

    private static int[][] lines() {
        int[][] out = new int[2 * SIZE + 2][SIZE];
        for (int i = 0; i < SIZE; i++) {
            for (int j = 0; j < SIZE; j++) {
                out[i][j] = i * SIZE + j;        // row i
                out[SIZE + i][j] = j * SIZE + i; // column i
            }
            out[2 * SIZE][i] = i * SIZE + i;                    // top-left to bottom-right
            out[2 * SIZE + 1][i] = i * SIZE + (SIZE - 1 - i);   // top-right to bottom-left
        }
        return out;
    }

    /** The number on {@code square}, 0 for the free one. */
    public int number(int square) {
        return numbers[square];
    }

    /** Whether {@code number} is on this card. */
    public boolean has(int number) {
        return square(number) >= 0;
    }

    /** The square {@code number} is on, or -1. */
    public int square(int number) {
        if (number <= 0) {
            return -1;
        }
        for (int i = 0; i < SQUARES; i++) {
            if (numbers[i] == number) {
                return i;
            }
        }
        return -1;
    }

    /** The column letter of a number: B for 1-15 ... O for 61-75. */
    public static char letter(int number) {
        return LETTERS.charAt(Math.max(0, Math.min(SIZE - 1, (number - 1) / PER_COLUMN)));
    }

    /** A ball as it is called: "B 12". */
    public static String ball(int number) {
        return letter(number) + " " + number;
    }

    /** On which call (from 0) {@code square} is daubed: -1 for the free one, which is daubed from the start. */
    int callOf(int square, int[] rank) {
        return square == FREE ? -1 : rank[numbers[square]];
    }

    /** Whether {@code square} is daubed once {@code called} numbers have been called. */
    public boolean daubed(int square, int[] rank, int called) {
        return callOf(square, rank) < called;
    }

    /** On which call (from 0) {@code squares} are all daubed. */
    int doneAt(int[] squares, int[] rank) {
        int last = -1;
        for (int s : squares) {
            last = Math.max(last, callOf(s, rank));
        }
        return last;
    }

    /** On which call (from 0) this card's first line is complete. */
    public int lineAt(int[] rank) {
        int first = Integer.MAX_VALUE;
        for (int[] line : LINES) {
            first = Math.min(first, doneAt(line, rank));
        }
        return first;
    }

    /** The squares of the first line complete on call {@link #lineAt} (the first of them, if several). */
    public int[] line(int[] rank) {
        int at = lineAt(rank);
        for (int[] line : LINES) {
            if (doneAt(line, rank) == at) {
                return line.clone();
            }
        }
        return new int[0];
    }

    /** On which call (from 0) this card's four corners are complete. */
    public int cornersAt(int[] rank) {
        return doneAt(CORNERS, rank);
    }

    /** How many squares are still to daub for this card's nearest line, once {@code called} numbers are called. */
    public int toGo(int[] rank, int called) {
        int best = SIZE;
        for (int[] line : LINES) {
            int left = 0;
            for (int s : line) {
                left += daubed(s, rank, called) ? 0 : 1;
            }
            best = Math.min(best, left);
        }
        return best;
    }

    /** The 25 numbers, 0 in the middle (a copy). */
    public int[] numbers() {
        return numbers.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof BingoCard c && Arrays.equals(c.numbers, numbers);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(numbers);
    }

    @Override
    public String toString() {
        return Arrays.toString(numbers);
    }
}
