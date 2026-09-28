package com.dierks.homecraft.games.cabinet.match;

import java.util.SplittableRandom;

/**
 * Mini Match's rules, with no Bukkit in sight (spec §10b): 16 cards, 8 pairs, dealt face down from
 * a seed.
 *
 * <p>A <b>flip</b> is one go: turn a card, then a second. A pair stays face up; a miss stays
 * showing until {@link #hideMiss()} (the screen calls it about a second later) or until the next
 * card is turned, whichever comes first — so a quick player never waits, and a slow one gets time
 * to look. The score is the number of flips, lower is better: a perfect memory needs at most 15.
 *
 * <p>The deal is a seeded shuffle, so the daily board is the same layout for everyone, and the
 * screen only ever learns a card's face through {@link #face(int)}, which answers for face-up
 * cards alone. A face-down card has no face to leak.
 */
public final class MatchEngine {

    /** Pairs on the board. */
    public static final int PAIRS = 8;
    /** Cards on the board (4 by 4). */
    public static final int CARDS = PAIRS * 2;

    /** What turning a card did. */
    public enum Flip {
        /** Nothing: that card is already face up, or the board is finished. */
        IGNORED,
        /** The first card of a go is face up. */
        FIRST,
        /** The second card matched the first: both stay face up. */
        MATCH,
        /** The second card didn't match: both show until they're hidden again. */
        MISS,
        /** The last pair was found. */
        DONE
    }

    private final int[] layout;
    private final boolean[] matched = new boolean[CARDS];
    /** The first card of the current go, face up; -1 when none. */
    private int first = -1;
    /** A missed pair still showing; -1 when none. */
    private int missA = -1;
    private int missB = -1;
    private int flips;
    private int pairsFound;

    public MatchEngine(long seed) {
        this.layout = deal(seed);
    }

    /**
     * The pair id (0-7) on each of the 16 cards: two of each, in a seeded Fisher-Yates shuffle.
     * The same seed always deals the same board.
     */
    public static int[] deal(long seed) {
        int[] cards = new int[CARDS];
        for (int i = 0; i < CARDS; i++) {
            cards[i] = i / 2;
        }
        SplittableRandom rng = new SplittableRandom(seed);
        for (int i = CARDS - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            int t = cards[i];
            cards[i] = cards[j];
            cards[j] = t;
        }
        return cards;
    }

    /**
     * Which {@code count} of {@code available} things to use as faces (say, 8 of the server's
     * textured Minis), as distinct indexes in a seeded order. Fewer than {@code count} available
     * gives an empty array: the screen then uses its plain faces.
     */
    public static int[] pick(int available, int count, long seed) {
        if (count <= 0 || available < count) {
            return new int[0];
        }
        int[] all = new int[available];
        for (int i = 0; i < available; i++) {
            all[i] = i;
        }
        SplittableRandom rng = new SplittableRandom(seed ^ 0x5DEECE66DL);
        for (int i = 0; i < count; i++) {
            int j = i + rng.nextInt(available - i);
            int t = all[i];
            all[i] = all[j];
            all[j] = t;
        }
        int[] out = new int[count];
        System.arraycopy(all, 0, out, 0, count);
        return out;
    }

    /** Turn card {@code card} (0-15, row by row). */
    public Flip flip(int card) {
        if (card < 0 || card >= CARDS || done()) {
            return Flip.IGNORED;
        }
        hideMiss();
        if (matched[card] || card == first) {
            return Flip.IGNORED;
        }
        if (first < 0) {
            first = card;
            return Flip.FIRST;
        }
        flips++;
        int a = first;
        first = -1;
        if (layout[a] == layout[card]) {
            matched[a] = true;
            matched[card] = true;
            pairsFound++;
            return done() ? Flip.DONE : Flip.MATCH;
        }
        missA = a;
        missB = card;
        return Flip.MISS;
    }

    /** Turn a showing miss face down again. False if there was none. */
    public boolean hideMiss() {
        if (missA < 0) {
            return false;
        }
        missA = -1;
        missB = -1;
        return true;
    }

    /** Whether a missed pair is still showing. */
    public boolean missShowing() {
        return missA >= 0;
    }

    /** Whether the card shows its face. */
    public boolean faceUp(int card) {
        return matched[card] || card == first || card == missA || card == missB;
    }

    /** Whether the card's pair has been found. */
    public boolean matched(int card) {
        return matched[card];
    }

    /** The card's pair id (0-7) when it is face up, else -1: all the screen may know. */
    public int face(int card) {
        return faceUp(card) ? layout[card] : -1;
    }

    /** Flips so far (one per go of two cards). */
    public int flips() {
        return flips;
    }

    /** Pairs found so far. */
    public int pairsFound() {
        return pairsFound;
    }

    /** Whether every pair is found. */
    public boolean done() {
        return pairsFound == PAIRS;
    }
}
