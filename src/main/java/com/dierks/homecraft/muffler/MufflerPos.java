package com.dierks.homecraft.muffler;

/** Where a Sound Muffler stands: a world name and a block position. */
public record MufflerPos(String world, int x, int y, int z) {

    @Override
    public String toString() {
        return world + " " + x + "," + y + "," + z;
    }
}
