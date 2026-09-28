package com.dierks.homecraft.muffler;

import java.util.concurrent.ThreadLocalRandom;

/**
 * The mark on a sound a muffler put back quieter.
 *
 * <p>"Quieter" never edits a sound on its way out: the game hands the SAME packet to every player
 * in earshot, so turning it down in place would turn it down again for each of them. Instead the
 * sound is dropped for that one player and a quieter copy is played to them. The copy carries a
 * seed whose low 24 bits are {@link #MARK}, so when it comes back through the listener it is let
 * straight through instead of being hushed a second time. The high bits stay random, because the
 * seed also picks which variant of a sound plays (which of the chicken's clucks), and one fixed
 * seed would make every chicken cluck the same way.
 *
 * <p>An ordinary sound's seed is random, so one in 16.7 million carries the mark by chance and
 * slips past a muffler once. That is fine.
 */
public final class Replay {

    /** "MUF". */
    static final long MARK = 0x4D5546L;
    private static final long MASK = 0xFFFFFFL;

    private Replay() {
    }

    /** A fresh random seed carrying the mark. */
    public static long seed() {
        return (ThreadLocalRandom.current().nextLong() & ~MASK) | MARK;
    }

    /** Whether a sound's seed says a muffler already put it back. */
    public static boolean isReplay(long seed) {
        return (seed & MASK) == MARK;
    }
}
