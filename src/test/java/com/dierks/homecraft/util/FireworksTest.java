package com.dierks.homecraft.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The present's burst in view (HALLOWEEN-SPEC D8; review BK-2): 4 blocks ahead in the open, and on this side
 * of a nearer wall, so a child indoors or in a tunnel still sees it instead of a rocket going off inside the
 * wall or behind it.
 */
class FireworksTest {

    @Test
    void inTheOpenItBurstsFourBlocksAhead() {
        assertEquals(4.0, Fireworks.burstDistance(-1), 1e-9, "nothing in the way");
        assertEquals(4.0, Fireworks.burstDistance(4.0), 1e-9, "a wall just at 4 is no nearer");
    }

    @Test
    void aNearerWallBringsItCloser() {
        assertEquals(2.3, Fireworks.burstDistance(3.0), 1e-9, "0.7 short of a wall 3 blocks away");
        assertEquals(1.5, Fireworks.burstDistance(2.2), 1e-9);
        assertEquals(1.5, Fireworks.burstDistance(0.4), 1e-9, "never nearer than 1.5, in her face");
    }
}
