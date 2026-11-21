package com.dierks.homecraft.games.cpu;

/**
 * A small personality that shapes how an NPC plays and behaves. It never makes one stronger or weaker: that is its
 * {@link CpuLevel}. It only colours the choices a person would make by taste.
 */
public enum CpuPersonality {
    /** Puts in the least, never doubles a hand basic play only just doubles, waits a little longer before a move. */
    CAREFUL(-1, false, 1800, 0.15),
    /** Puts in the most, doubles on a couple of extra hands, quick to move. */
    BOLD(1, true, 900, 0.25),
    /** Middle of the road at the table; waves and cheers the most. */
    CHATTY(0, false, 1200, 0.6),
    /** Goes by a hunch: always picks its favourite racer, otherwise plays like anyone. */
    HUNCH(0, false, 1400, 0.3);

    /** Which pretend stake it picks: -1 the smallest, 0 the middle, 1 the biggest. */
    public final int stakeLean;
    /** Whether it doubles on a couple of extra hands basic play doesn't. */
    public final boolean doublesMore;
    /** About how long it takes over a move, in milliseconds (a second or two, like a person). */
    public final int thinkMs;
    /** How keen it is to wave and cheer (0-1). */
    public final double friendly;

    CpuPersonality(int stakeLean, boolean doublesMore, int thinkMs, double friendly) {
        this.stakeLean = stakeLean;
        this.doublesMore = doublesMore;
        this.thinkMs = thinkMs;
        this.friendly = friendly;
    }
}
