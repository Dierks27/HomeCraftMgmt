package com.dierks.homecraft.arcade;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * How a trail puffs (HALLOWEEN-SPEC D3, kid-bedrock #3): leaves fall from the shoulders, one at a time, or
 * they would land at once; every other trail keeps its couple of particles at the feet.
 */
class TrailPuffTest {

    @Test
    void leavesFallFromTheShoulders() {
        assertEquals(new TrailService.Puff(1.3, 1, 0.3, 0.1, 0.3), TrailService.puff("ORANGE_POPLAR_LEAVES"));
        assertEquals(new TrailService.Puff(1.3, 1, 0.3, 0.1, 0.3), TrailService.puff(" cherry_leaves "),
                "any particle ending in _LEAVES, however it is written");
    }

    @Test
    void otherTrailsAreUnchanged() {
        TrailService.Puff feet = new TrailService.Puff(0.15, 2, 0.15, 0.05, 0.15);
        assertEquals(feet, TrailService.puff("HEART"));
        assertEquals(feet, TrailService.puff("NOTE"));
        assertEquals(feet, TrailService.puff("FIREWORK"));
        assertEquals(feet, TrailService.puff("LEAVES_OF_GRASS_X"), "only the end of the name counts");
        assertEquals(feet, TrailService.puff(null));
    }
}
