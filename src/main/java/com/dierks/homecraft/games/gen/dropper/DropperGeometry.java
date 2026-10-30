package com.dierks.homecraft.games.gen.dropper;

import com.dierks.homecraft.games.gen.api.Box;

import java.util.List;

/**
 * Where a Dropper's shafts stand in a half (EVENTS-DROPPER-SPEC §B.1.2, §B.1.3): the fixed layout
 * every level shares, so the planner, the validator and the tests count the same blocks.
 *
 * <p>A half is 64 wide and 16 deep; its height is the slot's (64 for the Easy Dropper, 144 for the
 * Dropper and the Classic). Shafts stand side by side along x and share walls: 11 x 11 inside,
 * 13 x 13 outside, so 5 levels take 61 of the 64 blocks (x 1-61), and along z they fill 1-13 of
 * 0-15, a block from the half's side at least. Every ledge top hangs {@link #LEDGE_BELOW_TOP} (8)
 * under the half's top: local 56 in a 64-tall half, 136 in a 144-tall one; the walls reach 4 above
 * it, and a level's water is its drop below the ledge top (in a 64-tall half 24, 16 or 8; in a
 * 144-tall half 104, 96, 88, and the Long Drop's 8). A pool is 3 deep over a floor, so the deepest
 * floor is at 4.
 *
 * <p>The walls between two shafts are clear glass (both levels see through into the next, and a
 * shared block can only be one colour); every other wall is the level's own colour.
 */
public final class DropperGeometry {

    /** A shaft's inside, a side. */
    public static final int INSIDE = 11;
    /** From one shaft's wall to the next (walls are shared). */
    public static final int PITCH = 12;
    /** The first shaft's outer wall, local x. */
    public static final int FIRST_X = 1;
    /** The shafts' outer wall toward -z, local z (the other is at 13). */
    public static final int WALL_Z = 1;
    /** Walls reach this far above the ledge top: a jump peaks at 1.25, so nobody climbs out. */
    public static final int WALL_ABOVE = 4;
    /**
     * Every ledge's top (where the feet stand) is this far under the half's top: the 4 wall rows and
     * 4 spare rows of air the no-roof check looks through. So a 64-tall half's ledges are at local
     * 56, as they always were, and a taller half's rise with it, every shaft of a half sharing one
     * height.
     */
    public static final int LEDGE_BELOW_TOP = WALL_ABOVE + 4;
    /** Clear air above the water: no plate this close to the splash. */
    public static final int CLEAR_AIR = 6;
    /** Water is this deep. */
    public static final int POOL_DEPTH = 3;
    /** A ledge is this many blocks a side. */
    public static final int LEDGE = 3;
    /** A ledge mark's radius. */
    public static final double LEDGE_RADIUS = 2.5;
    /** The fall height is this far under the lowest pool's floor. */
    public static final int FALL_BELOW_FLOOR = 4;
    /** The row above the ledge top the level's sign hangs in (eye height). */
    public static final int SIGN_ABOVE = 1;

    private DropperGeometry() {
    }

    /**
     * Shaft {@code index} of a half, in world blocks: the inside's min corner (x1, z1), and the
     * ledge top y.
     */
    public record Shaft(int index, int x1, int z1, int ledgeTop) {

        public int x2() {
            return x1 + INSIDE - 1;
        }

        public int z2() {
            return z1 + INSIDE - 1;
        }

        /** The highest wall block. */
        public int wallTop() {
            return ledgeTop + WALL_ABOVE - 1;
        }

        /** The ledge's row of blocks. */
        public int ledgeRow() {
            return ledgeTop - 1;
        }

        /** Whether block (x, z) is inside. */
        public boolean inside(int x, int z) {
            return x >= x1 && x <= x2() && z >= z1 && z <= z2();
        }

        /** Its inside from row {@code y1} to row {@code y2}. */
        public Box insideRows(int y1, int y2) {
            return new Box(x1, y1, z1, x2(), y2, z2());
        }
    }

    /** The ledge top (world y) of every shaft of {@code half}: {@link #LEDGE_BELOW_TOP} under its top. */
    public static int ledgeTop(Box half) {
        return half.maxY() + 1 - LEDGE_BELOW_TOP;
    }

    /** Shaft {@code index} (0 to 4) of {@code half}. */
    public static Shaft shaft(Box half, int index) {
        return new Shaft(index, half.minX() + FIRST_X + PITCH * index + 1, half.minZ() + WALL_Z + 1, ledgeTop(half));
    }

    /** A level's water surface: the ledge top less the drop. */
    public static int surface(Shaft s, DropRules.Level tier) {
        return s.ledgeTop() - tier.drop();
    }

    /** A level's pool floor row. */
    public static int floorRow(Shaft s, DropRules.Level tier) {
        return surface(s, tier) - POOL_DEPTH - 1;
    }

    /**
     * How tall a half must be for a level of {@code tier}: the ledge's rows under the top, the drop,
     * the pool and its floor, and a row under the floor (the floor stands strictly above the half's
     * bottom): 61 for Hard, 141 for the Long Drop.
     */
    public static int minSizeY(DropRules.Level tier) {
        return LEDGE_BELOW_TOP + tier.drop() + POOL_DEPTH + 2;
    }

    /** Whether a half has room for a shaft per level of {@code tiers}, the deepest of them included. */
    public static boolean fits(Box half, List<DropRules.Level> tiers) {
        int need = 0;
        for (DropRules.Level t : tiers) {
            need = Math.max(need, minSizeY(t));
        }
        return half.sizeX() >= FIRST_X + PITCH * tiers.size() + 1 + 1 && half.sizeZ() >= WALL_Z + INSIDE + 2 + 1
                && half.sizeY() >= need;
    }
}
