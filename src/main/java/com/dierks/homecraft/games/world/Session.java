package com.dierks.homecraft.games.world;

import java.util.UUID;

/**
 * A player's live world session, as the games see it (spec §7.2, R2.2, R3.7): which game, which
 * course, in which world, and where in its life it is. A snapshot — {@link WorldSessions} owns the
 * real thing and hands out a fresh one on every {@link WorldSessions#session} call.
 *
 * @param player    the player
 * @param gameId    the game running it
 * @param ref       the course (or other) it is for, {@code ""} for none
 * @param id        the session id (guards every write to its saved-state row)
 * @param world     the session's world
 * @param phase     where it is in its life
 * @param startedAt when it started (epoch ms)
 */
public record Session(UUID player, String gameId, String ref, String id, String world, Phase phase, long startedAt) {

    /** The in-memory life of a session; the database row has its own phases (see {@link SavedState}). */
    public enum Phase {
        /** Teleporting in: set before the teleport, cleared if it fails. */
        ENTERING,
        /** Playing. */
        ACTIVE,
        /** On the way out: restoring and sending back. */
        LEAVING
    }
}
