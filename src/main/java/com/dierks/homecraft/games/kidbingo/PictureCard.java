package com.dierks.homecraft.games.kidbingo;

import java.util.Arrays;
import java.util.Collection;
import java.util.SplittableRandom;

/**
 * One Picture Bingo card (0.57.0, pure): three by three, eight different animals ({@link Picture}) round a free star in
 * the middle. Three in a row (any row, column or diagonal, the star counting) wins. Squares are numbered 0-8 by row;
 * the star is {@value #FREE}.
 */
public final class PictureCard {

    public static final int SIZE = 3;
    public static final int SQUARES = SIZE * SIZE;
    /** The free star in the middle. */
    public static final int FREE = 4;
    /** The eight lines: three rows, three columns, two diagonals. */
    public static final int[][] LINES = {{0, 1, 2}, {3, 4, 5}, {6, 7, 8}, {0, 3, 6}, {1, 4, 7}, {2, 5, 8}, {0, 4, 8},
            {2, 4, 6}};

    /** Each square's picture (by {@link Picture#ordinal()}), -1 for the star. */
    private final int[] pictures;

    private PictureCard(int[] pictures) {
        this.pictures = pictures;
    }

    /** The card dealt from {@code seed}: eight different pictures in a random order. */
    public static PictureCard deal(long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        int[] pool = new int[Picture.COUNT];
        for (int i = 0; i < pool.length; i++) {
            pool[i] = i;
        }
        int[] out = new int[SQUARES];
        int k = 0;
        for (int sq = 0; sq < SQUARES; sq++) {
            if (sq == FREE) {
                out[sq] = -1;
                continue;
            }
            int j = k + r.nextInt(pool.length - k);
            int t = pool[k];
            pool[k] = pool[j];
            pool[j] = t;
            out[sq] = pool[k++];
        }
        return new PictureCard(out);
    }

    /** A card from its nine pictures' ordinals, -1 in the middle (tests). */
    static PictureCard of(int... pictures) {
        if (pictures.length != SQUARES || pictures[FREE] != -1) {
            throw new IllegalArgumentException("a card is nine squares with the star in the middle");
        }
        return new PictureCard(pictures.clone());
    }

    /** The picture on {@code square}, or {@code null} for the star. */
    public Picture at(int square) {
        return pictures[square] < 0 ? null : Picture.of(pictures[square]);
    }

    /** Whether {@code p} is on this card. */
    public boolean has(Picture p) {
        for (int v : pictures) {
            if (p != null && v == p.ordinal()) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code square} is lit: the star always, a picture once it is caught. */
    public boolean lit(int square, Collection<Picture> caught) {
        return square == FREE || caught.contains(at(square));
    }

    /** How many squares are lit (the star included). */
    public int litCount(Collection<Picture> caught) {
        int n = 0;
        for (int sq = 0; sq < SQUARES; sq++) {
            n += lit(sq, caught) ? 1 : 0;
        }
        return n;
    }

    /** The first line that is all lit, or {@code null}. */
    public int[] line(Collection<Picture> caught) {
        for (int[] line : LINES) {
            boolean all = true;
            for (int sq : line) {
                all &= lit(sq, caught);
            }
            if (all) {
                return line.clone();
            }
        }
        return null;
    }

    /** Whether three in a row are lit. */
    public boolean won(Collection<Picture> caught) {
        return line(caught) != null;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PictureCard c && Arrays.equals(c.pictures, pictures);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(pictures);
    }
}
