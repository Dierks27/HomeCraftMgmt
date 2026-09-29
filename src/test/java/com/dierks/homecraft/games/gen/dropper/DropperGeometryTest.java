package com.dierks.homecraft.games.gen.dropper;

import com.dierks.homecraft.games.gen.api.Box;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the shafts stand in a half (EVENTS-DROPPER-SPEC §B.1.2, §B.1.3): side by side along x with
 * shared walls, 11 x 11 inside, every ledge top at 56, walls to 60, and each tier's water and floor
 * at the heights the spec gives.
 */
class DropperGeometryTest {

    private static final Box HALF = DropperSlots.DROPPER_SLOT.half('A');

    @Test
    void fiveShaftsShareWallsAndFillSixtyOneOfTheSixtyFourBlocks() {
        for (int i = 0; i < 5; i++) {
            DropperGeometry.Shaft s = DropperGeometry.shaft(HALF, i);
            assertEquals(HALF.minX() + 2 + 12 * i, s.x1(), "shaft " + i + "'s inside starts 12 after the last");
            assertEquals(DropperGeometry.INSIDE, s.x2() - s.x1() + 1, "11 inside along x");
            assertEquals(DropperGeometry.INSIDE, s.z2() - s.z1() + 1, "and along z");
            if (i > 0) {
                assertEquals(DropperGeometry.shaft(HALF, i - 1).x2() + 2, s.x1(), "one wall block between neighbours");
            }
        }
        DropperGeometry.Shaft last = DropperGeometry.shaft(HALF, 4);
        assertEquals(61, last.x2() + 1 - HALF.minX(), "the last outer wall is at x 61 of 0-63");
        DropperGeometry.Shaft first = DropperGeometry.shaft(HALF, 0);
        assertEquals(1, first.x1() - 1 - HALF.minX(), "the first outer wall at x 1");
        assertEquals(1, first.z1() - 1 - HALF.minZ(), "the walls along z at 1...");
        assertEquals(13, first.z2() + 1 - HALF.minZ(), "...and 13 of 0-15");
    }

    @Test
    void everyLedgeIsAt56AndTheWallsReach60() {
        DropperGeometry.Shaft s = DropperGeometry.shaft(HALF, 2);
        assertEquals(HALF.minY() + 56, s.ledgeTop(), "the feet stand at 56");
        assertEquals(HALF.minY() + 55, s.ledgeRow(), "on the row below");
        assertEquals(HALF.minY() + 60, s.wallTop() + 1, "the walls' top face is at 60, 4 above");
    }

    @Test
    void eachTiersWaterAndFloorAreWhereTheSpecSays() {
        DropperGeometry.Shaft s = DropperGeometry.shaft(HALF, 0);
        int y0 = HALF.minY();
        assertEquals(List.of(24, 16, 8), List.of(DropperGeometry.surface(s, DropRules.Level.EASY) - y0,
                DropperGeometry.surface(s, DropRules.Level.MEDIUM) - y0,
                DropperGeometry.surface(s, DropRules.Level.HARD) - y0), "the water at 56 less the drop");
        assertEquals(4, DropperGeometry.floorRow(s, DropRules.Level.HARD) - y0, "the deepest floor, Hard's, at 4");
        assertTrue(DropperGeometry.floorRow(s, DropRules.Level.HARD) > HALF.minY(), "above the half's bottom");
    }

    @Test
    void aHalfFitsItsShaftsOrSaysSo() {
        assertTrue(DropperGeometry.fits(HALF, 5), "a 64 x 64 x 16 half fits five");
        assertFalse(DropperGeometry.fits(Box.sized(0, 0, 0, 60, 64, 16), 5), "60 blocks along x don't");
        assertTrue(DropperGeometry.fits(Box.sized(0, 0, 0, 16, 64, 16), 1), "one shaft needs 15 x 60 x 15");
        assertFalse(DropperGeometry.fits(Box.sized(0, 0, 0, 64, 59, 16), 1),
                "a half under 60 tall has no room for the walls");
    }
}
