package com.dierks.homecraft.games.arena.rules;

/**
 * One column of a floor: the block at (x, z) on whichever floor it belongs to. Floors are flat, so
 * a floor's y is its {@link FloorLayout.Layer}'s, and a cell is only ever two numbers.
 *
 * <p>Ordered by x, then z, so every list of cells here comes out in the same order on every host
 * (the rules never iterate a hash set).
 */
public record Cell(int x, int z) implements Comparable<Cell> {

    @Override
    public int compareTo(Cell o) {
        int c = Integer.compare(x, o.x);
        return c != 0 ? c : Integer.compare(z, o.z);
    }

    /** The middle of the cell, where a player standing on it is placed. */
    public double centerX() {
        return x + 0.5;
    }

    /** The middle of the cell along z. */
    public double centerZ() {
        return z + 0.5;
    }
}
