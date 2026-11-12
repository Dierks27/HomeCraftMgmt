package com.dierks.homecraft.graves;

import java.util.List;
import java.util.UUID;

/**
 * One row of {@code graves}: whose it is, where it stands, when the death was, and what is still in it. The world
 * block is only a marker: everything a grave holds lives here, so a restart, an unloaded chunk or a lost head block
 * loses nothing.
 *
 * @param state     {@link #OPEN} while it holds anything; once emptied, how it ended
 * @param notice    the owner has a message waiting about it (an expiry while they were away)
 */
public record Grave(long id, UUID owner, String ownerName, String world, int x, int y, int z, long createdAt,
                    int xp, List<GraveItems.Entry> items, String state, long closedAt, String closedBy,
                    boolean notice) {

    /** Standing, with something in it. */
    public static final String OPEN = "OPEN";
    /** Emptied by its owner, an admin or (once unlocked) anyone, in the world or the grave screen. */
    public static final String LOOTED = "LOOTED";
    /** Given straight to a player by {@code /hcm graves restore}. */
    public static final String RESTORED = "RESTORED";
    /** Expired: its things were dropped on the ground where it stood. */
    public static final String EXPIRED = "EXPIRED";
    /** Taken away by {@code /hcm graves remove} (dropped where it stood, or deleted with {@code --delete}). */
    public static final String REMOVED = "REMOVED";

    public Grave {
        items = List.copyOf(items);
    }

    public boolean open() {
        return OPEN.equals(state);
    }

    /** How many stacks are still in it. */
    public int stacks() {
        return items.size();
    }
}
