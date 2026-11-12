package com.dierks.homecraft.graves;

/**
 * Where a grave goes (HCM-GRAVES §2). Pure: it reads the world through a {@link Grid}, so every case is tested
 * without a server ({@code GravePlacementTest}).
 *
 * <ol>
 *   <li>The spot is brought inside the world: above the build limit it comes down to the top row. A death in the
 *       void never reaches here; the caller passes the last safe spot the player stood on, or the world's spawn.</li>
 *   <li>Inside a block (suffocation, a glitch into a wall): up first, to the first cell a grave can take.</li>
 *   <li>In mid-air (an arrow while jumping, an elytra crash that didn't reach the ground): straight down through
 *       air and plants to whatever is under it.</li>
 *   <li>That cell, if it is good: a cell a grave can take, not lava, with something to stand on under it (or in
 *       water, which a player can swim to). A 2-high tunnel is good as it is.</li>
 *   <li>Otherwise the nearest good cell in a box around it ({@value #RADIUS} out, {@value #UP} up,
 *       {@value #DOWN} down), up counting for less than down: "searching up first". This is the lava case: the
 *       grave goes on the shore, never in the pool.</li>
 *   <li>Otherwise the nearest cell a grave can take at all, lava last, so a grave is still made (it can't be
 *       reached easily, but {@code /hcm graves restore} can).</li>
 * </ol>
 *
 * <p>A cell is never one marked {@link Cell#NEVER}: a container, a sign, a bed, a portal, a block HCM placed, a
 * Courier delivery building, another grave, or a chunk that isn't loaded. Those are the caller's to mark.
 */
public final class GravePlacement {

    /** How far out the search goes, each way. */
    static final int RADIUS = 6;
    /** How far up the search goes, and how far up "inside a block" climbs. */
    static final int UP = 16;
    /** How far down the search goes. */
    static final int DOWN = 8;

    /** What a cell is, as far as a grave cares. */
    public enum Cell {
        /** Air (and cave air, void air). */
        AIR,
        /** A plant, a snow layer, short grass: replaced by the grave. */
        SOFT,
        /** Water, source or flowing. */
        WATER,
        /** Lava, source or flowing. */
        LAVA,
        /** Anything else: something to stand on, never replaced. */
        SOLID,
        /** Never replaced, and also something to stand on: see the class comment. */
        NEVER
    }

    /** The world, one cell at a time. */
    @FunctionalInterface
    public interface Grid {
        Cell at(int x, int y, int z);
    }

    /** A chosen cell. */
    public record Spot(int x, int y, int z) {
    }

    private GravePlacement() {
    }

    /** Whether a grave may replace the cell. */
    static boolean takes(Cell c) {
        return c == Cell.AIR || c == Cell.SOFT || c == Cell.WATER || c == Cell.LAVA;
    }

    /** Whether a player can stand on the cell. */
    static boolean stands(Cell c) {
        return c == Cell.SOLID || c == Cell.NEVER;
    }

    /**
     * The cell for a grave for a death at {@code x, y, z}, or null when there is nowhere at all (the caller then
     * leaves the drops on the ground, as without graves).
     *
     * @param minY the world's lowest row
     * @param maxY the world's height limit (exclusive: the top row is {@code maxY - 1})
     */
    public static Spot find(Grid g, int x, int y, int z, int minY, int maxY) {
        if (maxY <= minY) {
            return null;
        }
        int y0 = Math.max(minY, Math.min(maxY - 1, y));
        // Inside a block: up first.
        if (!takes(g.at(x, y0, z))) {
            for (int dy = 1; dy <= UP && y0 + dy < maxY; dy++) {
                Cell c = g.at(x, y0 + dy, z);
                if (takes(c) && c != Cell.LAVA) {
                    y0 += dy;
                    break;
                }
            }
        }
        // In mid-air: down through air and plants to whatever is under it (not into the void).
        Cell here = g.at(x, y0, z);
        if (here == Cell.AIR || here == Cell.SOFT) {
            int yy = y0;
            while (yy - 1 >= minY) {
                Cell below = g.at(x, yy - 1, z);
                if (below != Cell.AIR && below != Cell.SOFT) {
                    break;
                }
                yy--;
            }
            if (yy - 1 >= minY) {
                y0 = yy; // landed on something; with only the void under it, the search below looks around
            }
        }
        if (good(g, x, y0, z, minY)) {
            return new Spot(x, y0, z);
        }
        Spot best = null;
        long bestScore = Long.MAX_VALUE;
        Spot fallback = null;
        long fallbackScore = Long.MAX_VALUE;
        for (int dy = -DOWN; dy <= UP; dy++) {
            int yy = y0 + dy;
            if (yy < minY || yy >= maxY) {
                continue;
            }
            for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                    Cell c = g.at(x + dx, yy, z + dz);
                    if (!takes(c)) {
                        continue;
                    }
                    long score = score(dx, dy, dz);
                    if (good(g, x + dx, yy, z + dz, minY)) {
                        if (score < bestScore) {
                            bestScore = score;
                            best = new Spot(x + dx, yy, z + dz);
                        }
                    } else {
                        long s = score + (c == Cell.LAVA ? 1_000_000L : 0L);
                        if (s < fallbackScore) {
                            fallbackScore = s;
                            fallback = new Spot(x + dx, yy, z + dz);
                        }
                    }
                }
            }
        }
        return best != null ? best : fallback;
    }

    /** A cell a grave can take, not lava, with ground under it, or in water. */
    static boolean good(Grid g, int x, int y, int z, int minY) {
        Cell c = g.at(x, y, z);
        if (!takes(c) || c == Cell.LAVA) {
            return false;
        }
        if (c == Cell.WATER) {
            return true;
        }
        return y - 1 >= minY && stands(g.at(x, y - 1, z));
    }

    /** Distance squared, a step down counting twice a step up: up is searched first. */
    static long score(int dx, int dy, int dz) {
        long v = dy > 0 ? (long) dy * dy : 2L * dy * dy;
        return (long) dx * dx + (long) dz * dz + v;
    }
}
