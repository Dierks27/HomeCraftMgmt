package com.dierks.homecraft.games.arena.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Floors for the tests, on the real site (EVENTS-DROPPER-SPEC §B.3.2): the box's 32 x 32 footprint
 * at x 5384-5415, z 4360-4391, floors at y 200, 192 and 184, out below y 181.
 */
final class ArenaFixtures {

    static final int TOP_Y = 200;
    static final int MID_Y = 192;
    static final int LOW_Y = 184;
    static final int OUT_Y = 181;
    /** The middle of the footprint (a block corner). */
    static final int CX = 5400;
    static final int CZ = 4376;

    /** Twelve spawns about 8 from the middle, at least 3 apart. */
    static final int[][] SPAWN_OFFSETS = {{8, 0}, {7, 4}, {4, 7}, {0, 8}, {-4, 7}, {-7, 4}, {-8, 0}, {-7, -4},
            {-4, -7}, {0, -8}, {4, -7}, {7, -4}};

    private ArenaFixtures() {
    }

    /** A disc of radius {@code r} round the middle: every cell whose centre is within r. */
    static List<Cell> disc(double r) {
        List<Cell> cells = new ArrayList<>();
        for (int x = CX - 16; x < CX + 16; x++) {
            for (int z = CZ - 16; z < CZ + 16; z++) {
                double dx = x + 0.5 - CX;
                double dz = z + 0.5 - CZ;
                if (dx * dx + dz * dz <= r * r) {
                    cells.add(new Cell(x, z));
                }
            }
        }
        return cells;
    }

    /** A square of {@code side} cells from (x0, z0). */
    static List<Cell> square(int x0, int z0, int side) {
        List<Cell> cells = new ArrayList<>();
        for (int x = x0; x < x0 + side; x++) {
            for (int z = z0; z < z0 + side; z++) {
                cells.add(new Cell(x, z));
            }
        }
        return cells;
    }

    static List<Cell> spawns() {
        List<Cell> out = new ArrayList<>();
        for (int[] o : SPAWN_OFFSETS) {
            out.add(new Cell(CX + o[0], CZ + o[1]));
        }
        return out;
    }

    /** The shipped shape family's disc r 12 on all three floors, with twelve spawns. */
    static FloorLayout discs() {
        return new FloorLayout(List.of(new FloorLayout.Layer(TOP_Y, disc(12)), new FloorLayout.Layer(MID_Y, disc(12)),
                new FloorLayout.Layer(LOW_Y, disc(12))), OUT_Y, spawns());
    }

    /** Three small 6 x 6 floors (quick rounds), spawns in two corners of the top. */
    static FloorLayout small() {
        List<Cell> sq = square(CX, CZ, 6);
        return new FloorLayout(List.of(new FloorLayout.Layer(TOP_Y, sq), new FloorLayout.Layer(MID_Y, sq),
                new FloorLayout.Layer(LOW_Y, sq)), OUT_Y, List.of(new Cell(CX, CZ), new Cell(CX + 5, CZ + 5)));
    }

    /** A player's id for the tests: p(1), p(2) ... */
    static UUID p(int n) {
        return new UUID(0xABCD, n);
    }

    /** Feet standing still at the middle of cell (x, z) on floor top {@code top}. */
    static Feet on(int x, int z, int top) {
        return new Feet(x + 0.5, top, z + 0.5, 0);
    }
}
