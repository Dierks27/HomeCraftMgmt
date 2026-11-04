package com.dierks.homecraft.courier;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the wild hunt and the Courier may look for ground from the top down ({@link WorldShape}).
 *
 * <p>The Nether's top block is its bedrock roof, so a wild Mini or a delivery house found that
 * way would sit where nobody can reach it.
 */
class WorldShapeTest {

    @Test
    void anOpenSkyHasItsGroundOnTop() {
        assertTrue(WorldShape.groundFromTop(false), "the Overworld and the End: the top block is the ground");
    }

    @Test
    void aWorldWithACeilingDoesNot() {
        assertFalse(WorldShape.groundFromTop(true), "the Nether: the top block is the bedrock roof");
    }

    @Test
    void theCourierRefusalIsOnePlainLine() {
        String line = WorldShape.COURIER_REFUSAL;
        assertFalse(line.contains("&") || line.contains("§"), "no colour codes: the board colours it");
        assertFalse(line.contains("\n"), "one line");
        assertTrue(line.contains("Nether"), "says where it does not go");
    }
}
