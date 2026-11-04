package com.dierks.homecraft.courier;

/**
 * Whether "the ground" can be found by looking down from the top of a world.
 *
 * <p>The wild hunt and the Courier both find ground the same way: start at the highest block
 * in a column and walk down ({@link Ground#solid}). In the Overworld that lands on a field. In
 * the Nether the highest block is the bedrock <b>roof</b> at y 127, so the walk stops on top of
 * it — a wild Mini, a waypoint or a delivery house would end up somewhere nobody can climb to.
 * The End needs nothing here: a column over the void finds no ground and is already refused,
 * and the islands are ordinary ground.
 *
 * <p>So a world with a ceiling is simply not somewhere either feature goes. Wild drops from
 * mobs, blocks and fishing are not affected — they come out where the thing died, not from the
 * sky.
 */
public final class WorldShape {

    /** What a player is told when they try to take a run in a world with a ceiling. */
    public static final String COURIER_REFUSAL = "The Courier only delivers above ground, not in the Nether.";

    private WorldShape() {
    }

    /**
     * True when the highest block in a column is the ground (or what grows on it), so a walk
     * down from the top finds somewhere a player can stand.
     *
     * @param hasCeiling the world has a roof over it ({@code World#hasCeiling}): the Nether
     */
    public static boolean groundFromTop(boolean hasCeiling) {
        return !hasCeiling;
    }
}
