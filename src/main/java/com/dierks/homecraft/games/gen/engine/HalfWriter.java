package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.api.Box;

import java.util.List;

/**
 * The only thing that writes a block for Daily Courses (GEN-SPEC §0.2 R4, §6 S1).
 *
 * <p>It holds one half and refuses — with an exception, before anything is written — any position
 * outside it. Plans are bounds-checked when they are made and again before a build, but this is
 * the rule that can't be argued with: however a bug got a position here, it can never reach a
 * block outside the area the generator owns.
 */
public final class HalfWriter {

    private final WorldPort port;
    private final Box half;
    private long writes;

    public HalfWriter(WorldPort port, Box half) {
        this.port = port;
        this.half = half;
    }

    /** Set block (x, y, z) to {@code canonical} ({@link WorldPort#AIR} to clear it). */
    public void set(int x, int y, int z, String canonical) {
        check(x, y, z);
        port.set(x, y, z, canonical);
        writes++;
    }

    /** Write a sign's text (the sign block is already there). */
    public void sign(int x, int y, int z, List<String> lines) {
        check(x, y, z);
        port.sign(x, y, z, lines);
    }

    /** The half it writes in. */
    public Box half() {
        return half;
    }

    /** Blocks written so far. */
    public long writes() {
        return writes;
    }

    private void check(int x, int y, int z) {
        if (!half.contains(x, y, z)) {
            throw new IllegalStateException("Daily Courses refused a write at " + x + "," + y + "," + z
                    + ", outside its half (" + half.describe() + ")");
        }
    }
}
