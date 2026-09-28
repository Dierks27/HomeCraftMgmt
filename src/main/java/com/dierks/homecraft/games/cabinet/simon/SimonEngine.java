package com.dierks.homecraft.games.cabinet.simon;

import java.util.SplittableRandom;

/**
 * Simon Says's rules, with no Bukkit in sight (spec §10b): four pads, a pattern that grows by one
 * every round, and the player plays it back.
 *
 * <p>The whole pattern is drawn from the seed up front, so round 5 of today's daily board is the
 * same five pads for everyone, and a round only ever ADDS a pad to the end — nothing earlier
 * changes under a player who has learnt it. The score is the longest pattern played back in full.
 *
 * <p>The game moves through {@link Phase#SHOWING} (the screen plays the pattern; presses are
 * ignored), {@link Phase#INPUT} and {@link Phase#OVER}. How long each light stays on is
 * {@link Tempo}'s business, so the Bedrock pace is a different tempo over the same rules.
 */
public final class SimonEngine {

    /** Pads on the board. */
    public static final int PADS = 4;
    /** The longest pattern: playing back this many ends the game as complete. */
    public static final int MAX = 99;

    /** Where the game is. */
    public enum Phase {
        /** The pattern is being played to the player. */
        SHOWING,
        /** The player is playing it back. */
        INPUT,
        /** A wrong pad, or the whole pattern done. */
        OVER
    }

    /** What pressing a pad did. */
    public enum Press {
        /** Not the player's turn: nothing happens. */
        IGNORED,
        /** The right pad, and there's more to play back. */
        RIGHT,
        /** The right pad, and the whole pattern is played back: next round. */
        ROUND_DONE,
        /** The wrong pad: game over. */
        WRONG,
        /** The right pad, and it was the longest pattern there is: game over, all done. */
        COMPLETE
    }

    private final int[] pattern;
    private int length = 1;
    private int pos;
    private int repeated;
    private Phase phase = Phase.SHOWING;

    public SimonEngine(long seed) {
        this.pattern = pattern(seed, MAX);
    }

    /** The first {@code n} pads of the pattern for {@code seed} (each 0-3). */
    public static int[] pattern(long seed, int n) {
        SplittableRandom rng = new SplittableRandom(seed);
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = rng.nextInt(PADS);
        }
        return out;
    }

    /** The screen finished playing the pattern: the player's turn. */
    public void shown() {
        if (phase == Phase.SHOWING) {
            phase = Phase.INPUT;
            pos = 0;
        }
    }

    /** The player pressed {@code pad}. */
    public Press press(int pad) {
        if (phase != Phase.INPUT) {
            return Press.IGNORED;
        }
        if (pad != pattern[pos]) {
            phase = Phase.OVER;
            return Press.WRONG;
        }
        pos++;
        if (pos < length) {
            return Press.RIGHT;
        }
        repeated = length;
        if (length == MAX) {
            phase = Phase.OVER;
            return Press.COMPLETE;
        }
        length++;
        pos = 0;
        phase = Phase.SHOWING;
        return Press.ROUND_DONE;
    }

    public Phase phase() {
        return phase;
    }

    public boolean over() {
        return phase == Phase.OVER;
    }

    /** How long the current pattern is. */
    public int length() {
        return length;
    }

    /** The pad at step {@code i} of the current pattern ({@code i < length()}). */
    public int step(int i) {
        if (i < 0 || i >= length) {
            throw new IndexOutOfBoundsException("step " + i + " of " + length);
        }
        return pattern[i];
    }

    /** How many pads of the current pattern the player has played back this round. */
    public int progress() {
        return phase == Phase.INPUT ? pos : 0;
    }

    /** The score: the longest pattern played back in full. */
    public int score() {
        return repeated;
    }

    /**
     * How the pattern is played to the player, in screen ticks (one screen tick is two server
     * ticks): a pause, then each pad lit for {@code on} with {@code gap} dark between. It gets a
     * little quicker as the pattern grows (never below {@code minOn}); Bedrock plays slower.
     */
    public record Tempo(int lead, int on, int gap, int minOn) {

        /** Java: half a second lit, a fifth dark. */
        public static final Tempo JAVA = new Tempo(6, 5, 2, 3);
        /** Bedrock: slower, so a busy connection doesn't swallow a light. */
        public static final Tempo BEDROCK = new Tempo(8, 8, 3, 5);

        /** How long each pad stays lit in a pattern this long: one tick quicker every five pads. */
        public int onFor(int length) {
            return Math.max(minOn, on - (length - 1) / 5);
        }

        /** Screen ticks to play a whole pattern of {@code length}, lead included. */
        public int total(int length) {
            return lead + length * (onFor(length) + gap);
        }

        /**
         * Which step of the pattern is lit at screen tick {@code tick} (0 = the start of the
         * show): its index, or -1 while no pad is lit. Past {@link #total} it stays -1.
         */
        public int litAt(int tick, int length) {
            if (tick < lead || tick >= total(length)) {
                return -1;
            }
            int on = onFor(length);
            int t = tick - lead;
            int step = t / (on + gap);
            return t % (on + gap) < on ? step : -1;
        }
    }
}
