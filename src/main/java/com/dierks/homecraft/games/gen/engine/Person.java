package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.api.Box;

import java.util.UUID;

/**
 * Someone online, as the engine sees them: where their feet are, and which world-game session
 * they are in (GEN-SPEC §6.3). Read once per check on the main thread.
 *
 * @param id          the player
 * @param name        their name, for logs
 * @param world       the world they are in
 * @param x           feet
 * @param y           ...
 * @param z           ...
 * @param sessionGame the game of their world session, or {@code null}
 * @param sessionRef  its course, or {@code null}
 */
public record Person(UUID id, String name, String world, double x, double y, double z, String sessionGame,
                     String sessionRef) {

    /** Half a player's width. */
    static final double HALF_WIDTH = 0.3;
    /** A player's height. */
    static final double HEIGHT = 1.8;

    /** Whether they are in a session on course {@code courseId}. */
    public boolean playing(String courseId) {
        return sessionRef != null && sessionRef.equalsIgnoreCase(courseId);
    }

    /** Whether their feet are inside {@code box} (a point test, block corners inclusive of the whole block). */
    public boolean in(String worldName, Box box) {
        return world != null && world.equalsIgnoreCase(worldName) && box.contains(x, y, z);
    }

    /**
     * Whether block (bx, by, bz) meets their body grown by {@code grow} on every side (S5: no
     * block is placed into a person, or right next to one).
     */
    public boolean touches(int bx, int by, int bz, double grow) {
        return bx < x + HALF_WIDTH + grow && bx + 1 > x - HALF_WIDTH - grow
                && by < y + HEIGHT + grow && by + 1 > y - grow
                && bz < z + HALF_WIDTH + grow && bz + 1 > z - HALF_WIDTH - grow;
    }
}
