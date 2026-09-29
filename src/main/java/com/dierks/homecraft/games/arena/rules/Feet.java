package com.dierks.homecraft.games.arena.rules;

/**
 * Where a player's feet are this tick, and which way they are moving up or down: everything the
 * floor rules need to know about a player (EVENTS-DROPPER-SPEC §B.3.3 step 4).
 *
 * <p>{@code vy} is blocks a tick, positive going up. On a server it is best read as this tick's y
 * minus last tick's (players move client-side, so the server's velocity for a player is only an
 * estimate); standing still is then 0 and a landing is negative, which is all "vy &lt;= 0" asks.
 *
 * @param x  the feet's x
 * @param y  the feet's y (the bottom of the player)
 * @param z  the feet's z
 * @param vy vertical movement, blocks a tick (up is positive)
 */
public record Feet(double x, double y, double z, double vy) {

    public Feet {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || !Double.isFinite(vy)) {
            throw new IllegalArgumentException("feet are finite numbers: " + x + "," + y + "," + z + " vy " + vy);
        }
    }
}
