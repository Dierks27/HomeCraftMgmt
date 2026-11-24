package com.dierks.homecraft.games.chance.bingo;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * One Bingo Night round's calls and who made what (0.57.0, pure), all from the round's seed and its cards: the order
 * the 75 numbers are called in ({@link #calls}), the call each pattern is first complete on, and which cards complete
 * it then. Everything is decided here before the first number is called, as every game of chance here is decided
 * before it is shown; the show then only calls what is already written.
 *
 * <p>The seed also shuffles the cards in play ({@link #order}), for the leftover units of a shared prize
 * ({@link BingoPrize#shares}). Both are drawn from the seed alone, never from a card.
 *
 * @param seed           the round's seed
 * @param calls          the numbers in the order they are called, 75 of them
 * @param lineCall       the call (from 0) the first line is made on
 * @param lineWinners    the cards (by place in the round) that make a line on that call
 * @param cornersCall    the call four corners is first made on, or -1 when the round plays for the line only
 * @param cornersWinners the cards that make four corners on that call (empty for none)
 * @param order          every card's place in the round, shuffled
 */
public record BingoDraw(long seed, int[] calls, int lineCall, int[] lineWinners, int cornersCall,
                        int[] cornersWinners, int[] order) {

    /** Mixed into the seed for the cards' shuffle, so it is not the calls' own sequence. */
    private static final long ORDER_SALT = 0x5DEECE66DL * 31 + 0x9E3779B97F4A7C15L;

    /** The 75 numbers in the order {@code seed} calls them. */
    public static int[] calls(long seed) {
        int[] out = new int[BingoCard.BALLS];
        for (int i = 0; i < out.length; i++) {
            out[i] = i + 1;
        }
        SplittableRandom r = new SplittableRandom(seed);
        for (int i = out.length - 1; i > 0; i--) {
            int j = r.nextInt(i + 1);
            int t = out[i];
            out[i] = out[j];
            out[j] = t;
        }
        return out;
    }

    /** {@code rank[n]}: the call (from 0) number {@code n} is called on. */
    public static int[] rank(int[] calls) {
        int[] rank = new int[BingoCard.BALLS + 1];
        for (int i = 0; i < calls.length; i++) {
            rank[calls[i]] = i;
        }
        return rank;
    }

    /** A shuffle of {@code n} places, from {@code seed}. */
    static int[] order(long seed, int n) {
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = i;
        }
        SplittableRandom r = new SplittableRandom(seed ^ ORDER_SALT);
        for (int i = n - 1; i > 0; i--) {
            int j = r.nextInt(i + 1);
            int t = out[i];
            out[i] = out[j];
            out[j] = t;
        }
        return out;
    }

    /**
     * The round drawn from {@code seed} for these cards.
     *
     * @param corners whether four corners is played for too (after, or with, the line)
     */
    public static BingoDraw of(List<BingoCard> cards, long seed, boolean corners) {
        if (cards.isEmpty()) {
            throw new IllegalArgumentException("a round has at least one card");
        }
        int[] calls = calls(seed);
        int[] rank = rank(calls);
        int line = Integer.MAX_VALUE;
        int four = Integer.MAX_VALUE;
        for (BingoCard c : cards) {
            line = Math.min(line, c.lineAt(rank));
            four = Math.min(four, c.cornersAt(rank));
        }
        List<Integer> lineWon = new ArrayList<>();
        List<Integer> fourWon = new ArrayList<>();
        for (int i = 0; i < cards.size(); i++) {
            if (cards.get(i).lineAt(rank) == line) {
                lineWon.add(i);
            }
            if (corners && cards.get(i).cornersAt(rank) == four) {
                fourWon.add(i);
            }
        }
        return new BingoDraw(seed, calls, line, ints(lineWon), corners ? four : -1, ints(fourWon),
                order(seed, cards.size()));
    }

    private static int[] ints(List<Integer> list) {
        int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.get(i);
        }
        return out;
    }

    /** {@code rank[n]}: the call number {@code n} is called on. */
    public int[] rank() {
        return rank(calls);
    }

    /** The last call the round needs: its last pattern's. */
    public int lastCall() {
        return Math.max(lineCall, cornersCall);
    }

    /** Whether the round plays for four corners too. */
    public boolean corners() {
        return cornersCall >= 0;
    }

    /** Whether card {@code i} makes the line. */
    public boolean madeLine(int i) {
        return contains(lineWinners, i);
    }

    /** Whether card {@code i} makes four corners. */
    public boolean madeCorners(int i) {
        return contains(cornersWinners, i);
    }

    private static boolean contains(int[] a, int v) {
        for (int x : a) {
            if (x == v) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int[] calls() {
        return calls.clone();
    }

    @Override
    public int[] lineWinners() {
        return lineWinners.clone();
    }

    @Override
    public int[] cornersWinners() {
        return cornersWinners.clone();
    }

    @Override
    public int[] order() {
        return order.clone();
    }
}
