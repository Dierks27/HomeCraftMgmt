package com.dierks.homecraft.games.chance.slots;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * How the reels move on screen (spec §2 "decide first, then show", §5.3): pure timing and frames,
 * so the rules that keep the show honest are tested without a server.
 *
 * <p>The spin is already decided and paid when the reels start. Its timing never depends on the
 * result: each reel stops on a FIXED tick (Java 8/14/20, repainted every 2 ticks; Bedrock three
 * frames, one every 10 ticks, because fast inventory updates stutter through Geyser). A reel still
 * spinning shows symbols from a separate cosmetic random, seeded independently of the outcome, and
 * a frame is never allowed to show a paying line before the last reel stops — when the stopped
 * reels already make a pair, whatever the spinning one showed would pay, so it shows a blank
 * instead. There are no rigged "almost" frames: what stops is exactly what was drawn.
 */
public final class ReelShow {

    /** Java: a frame every 2 ticks, reels stopping on ticks 8, 14 and 20 (one second in all). */
    public static final int JAVA_PERIOD = 2;
    /** Bedrock: three frames, one every 10 ticks, each stopping one reel. */
    public static final int BEDROCK_PERIOD = 10;

    private static final int[] JAVA_STOPS = {8, 14, 20};
    private static final int[] BEDROCK_STOPS = {10, 20, 30};
    /** Cosmetic draws tried before a spinning reel falls back to Stone or a blank. */
    private static final int TRIES = 12;

    private ReelShow() {
    }

    /** Ticks between frames. */
    public static int period(boolean bedrock) {
        return bedrock ? BEDROCK_PERIOD : JAVA_PERIOD;
    }

    /** The tick each reel stops on, left to right. */
    public static int[] stops(boolean bedrock) {
        return (bedrock ? BEDROCK_STOPS : JAVA_STOPS).clone();
    }

    /** How many reels have stopped once {@code tick} ticks have passed. */
    public static int stopped(boolean bedrock, int tick) {
        int n = 0;
        for (int stop : bedrock ? BEDROCK_STOPS : JAVA_STOPS) {
            if (tick >= stop) {
                n++;
            }
        }
        return n;
    }

    /**
     * One frame of the line: the first {@code stopped} reels show the result, the rest show
     * cosmetic symbols that never make a paying line with them, or {@code null} (a blank) when no
     * symbol could avoid it.
     *
     * @param engine   the engine the spin was played with (for what pays at this stake)
     * @param stake    the spin's stake
     * @param result   the three drawn symbols
     * @param stopped  how many reels have stopped (0-3)
     * @param cosmetic the show's own random, never the spin's seed
     */
    public static List<Symbol> frame(SlotsEngine engine, int stake, List<Symbol> result, int stopped,
                                     SplittableRandom cosmetic) {
        int done = Math.max(0, Math.min(3, stopped));
        if (done == 3) {
            return List.copyOf(result);
        }
        List<Symbol> pool = new ArrayList<>();
        for (Symbol s : Symbol.values()) {
            if (engine.weight(stake, s) > 0) {
                pool.add(s);
            }
        }
        if (pool.isEmpty()) {
            pool.add(Symbol.STONE);
        }
        Symbol[] shown = new Symbol[3];
        for (int i = 0; i < done; i++) {
            shown[i] = result.get(i);
        }
        for (int attempt = 0; attempt < TRIES; attempt++) {
            for (int i = done; i < 3; i++) {
                shown[i] = pool.get(cosmetic.nextInt(pool.size()));
            }
            if (engine.line(stake, shown[0], shown[1], shown[2]) == null) {
                return listOf(shown);
            }
        }
        for (int i = done; i < 3; i++) {
            shown[i] = Symbol.STONE;
        }
        if (engine.line(stake, shown[0], shown[1], shown[2]) == null) {
            return listOf(shown);
        }
        for (int i = done; i < 3; i++) {
            shown[i] = null;
        }
        return listOf(shown);
    }

    /** A list that may hold {@code null} (a blank reel). */
    private static List<Symbol> listOf(Symbol[] shown) {
        List<Symbol> out = new ArrayList<>(3);
        for (Symbol s : shown) {
            out.add(s);
        }
        return out;
    }
}
