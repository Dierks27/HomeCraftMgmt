package com.dierks.homecraft.games.arena.rules;

/**
 * One block the round changes: a planned floor cell turning red, or red turning to air. The rules
 * decide these; {@code FloorWriter} (WP-F) writes them, refusing anything that isn't a planned floor
 * cell or isn't the plan's own block, red or air, and capping how many it writes a tick.
 *
 * @param layer which floor, 0 = the top
 * @param x     the block's x
 * @param y     the block's y (the floor's own)
 * @param z     the block's z
 * @param state RED or AIR (a round never writes SOLID; only the reset does)
 */
public record FloorWrite(int layer, int x, int y, int z, CellState state) {

    public FloorWrite {
        if (state == null || state == CellState.SOLID) {
            throw new IllegalArgumentException("a round only turns cells red or to air, never " + state);
        }
    }
}
