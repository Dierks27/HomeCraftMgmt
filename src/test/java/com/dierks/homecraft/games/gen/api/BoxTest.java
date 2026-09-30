package com.dierks.homecraft.games.gen.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boxes of whole blocks, corners inclusive: sizes, what is inside, and the "n blocks apart"
 * question the regions are checked with (GEN-SPEC §2.2, §2.4).
 */
class BoxTest {

    private static final Box HALF_A = Box.sized(4096, 160, 4096, 64, 48, 64);

    @Test
    void aBoxCountsItsBlocks() {
        assertEquals(new Box(4096, 160, 4096, 4159, 207, 4159), HALF_A, "64 x 48 x 64 from its min corner");
        assertEquals(64, HALF_A.sizeX(), "64 wide");
        assertEquals(48, HALF_A.sizeY(), "48 high");
        assertEquals(64L * 48 * 64, HALF_A.volume(), "its volume in blocks");
        assertEquals(16, HALF_A.chunkCount(), "a 64 x 64 half on chunk lines is 16 chunks");
        assertEquals(Box.of(1, 2, 3, 4, 5, 6), Box.of(4, 5, 6, 1, 2, 3), "corners in any order");
        assertThrows(IllegalArgumentException.class, () -> new Box(1, 1, 1, 0, 1, 1), "max below min is refused");
        assertThrows(IllegalArgumentException.class, () -> Box.sized(0, 0, 0, 0, 1, 1), "so is an empty size");
        assertEquals("x 4096..4159, y 160..207, z 4096..4159", HALF_A.describe(), "admins read it like this");
    }

    @Test
    void blocksAndPointsAreInsideOrNot() {
        assertTrue(HALF_A.contains(4159, 207, 4159), "the max corner block is inside");
        assertFalse(HALF_A.contains(4160, 207, 4159), "one past it isn't");
        assertTrue(HALF_A.contains(4159.99, 160.0, 4096.0), "a point anywhere in the last block is inside");
        assertFalse(HALF_A.contains(4160.0, 160.0, 4096.0), "a point on the far face isn't");
        assertTrue(HALF_A.contains(Box.of(4100, 170, 4100, 4110, 180, 4110)), "a box within it");
        assertFalse(HALF_A.contains(HALF_A.expand(1)), "a bigger box isn't");
    }

    @Test
    void theGapCountsTheBlocksBetween() {
        Box halfB = Box.sized(4192, 160, 4096, 64, 48, 64);
        assertEquals(32, HALF_A.gap(halfB), "the spec's halves have 32 blocks between them");
        assertEquals(32, halfB.gap(HALF_A), "either way round");
        assertEquals(0, HALF_A.gap(HALF_A.translate(64, 0, 0)), "touching boxes have none between");
        assertEquals(-1, HALF_A.gap(HALF_A.translate(63, 0, 0)), "overlapping boxes share a block");
        assertEquals(40, HALF_A.gap(HALF_A.translate(80, 0, 104)), "diagonal: the axis that separates them most");
        for (int dx = 60; dx <= 100; dx++) {
            Box other = HALF_A.translate(dx, 0, 0);
            assertEquals(HALF_A.gap(other) >= 32, !HALF_A.expand(32).intersects(other),
                    "'32 apart' is the same as 'outside the box grown by 32' (dx " + dx + ")");
        }
    }
}
