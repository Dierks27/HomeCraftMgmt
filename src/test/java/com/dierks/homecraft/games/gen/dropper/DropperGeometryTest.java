package com.dierks.homecraft.games.gen.dropper;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.LegacyBoxes;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the shafts stand in a half (EVENTS-DROPPER-SPEC §B.1.2, §B.1.3): side by side along x with
 * shared walls, 11 x 11 inside, every ledge top 8 under the half's top (56 in the 64-tall half, 136
 * in the 144-tall one that holds the Long Drop), walls 4 above it, and each tier's water and floor
 * at the heights the spec gives.
 */
class DropperGeometryTest {

    private static final Box HALF = LegacyBoxes.half(DropperSlots.DROPPER_SLOT, 'A');

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
        assertEquals(8, DropperGeometry.LEDGE_BELOW_TOP, "the ledge hangs 8 under the half's top: 4 of wall, 4 spare");
        assertEquals(HALF.minY() + 56, DropperGeometry.ledgeTop(HALF), "so a 64-tall half's ledges are at 56");
    }

    @Test
    void aTallHalfLiftsEveryLedgeTo136AndHoldsTheLongDrop() {
        Box tall = LegacyBoxes.tall(DropperSlots.DROPPER_SLOT, 'A');
        int y0 = tall.minY();
        assertEquals(144, tall.sizeY(), "the Dropper's half is 144 tall");
        for (int i = 0; i < 5; i++) {
            DropperGeometry.Shaft s = DropperGeometry.shaft(tall, i);
            assertEquals(y0 + 136, s.ledgeTop(), "shaft " + i + "'s ledge top is at 136: every shaft shares one height");
            assertEquals(y0 + 140, s.wallTop() + 1, "walls to 140, 4 spare rows of air above");
        }
        DropperGeometry.Shaft s = DropperGeometry.shaft(tall, 4);
        assertEquals(List.of(104, 96, 88, 8), List.of(DropperGeometry.surface(s, DropRules.Level.EASY) - y0,
                DropperGeometry.surface(s, DropRules.Level.MEDIUM) - y0,
                DropperGeometry.surface(s, DropRules.Level.HARD) - y0,
                DropperGeometry.surface(s, DropRules.Level.EXTREME) - y0),
                "the water at 136 less the drop: E, M and H hang in the top 56 rows, the Long Drop's is at 8");
        assertEquals(4, DropperGeometry.floorRow(s, DropRules.Level.EXTREME) - y0, "the Long Drop's floor at 4");
        assertTrue(DropperGeometry.floorRow(s, DropRules.Level.EXTREME) > tall.minY(), "strictly above the bottom");
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
        List<DropRules.Level> eemmh = DropRules.levels("EEMMH");
        assertTrue(DropperGeometry.fits(HALF, eemmh), "a 64 x 64 x 16 half fits five of E, M and H");
        assertFalse(DropperGeometry.fits(HALF, DropRules.levels("EEX")), "but not a Long Drop");
        assertFalse(DropperGeometry.fits(Box.sized(0, 0, 0, 60, 64, 16), eemmh), "60 blocks along x don't fit five");
        assertTrue(DropperGeometry.fits(Box.sized(0, 0, 0, 16, 64, 16), DropRules.levels("H")),
                "one Hard shaft needs 15 x 61 x 15");
        assertFalse(DropperGeometry.fits(Box.sized(0, 0, 0, 16, 60, 16), DropRules.levels("H")),
                "a half 60 tall has no row under a Hard floor");
        assertEquals(List.of(45, 53, 61, 141), List.of(DropperGeometry.minSizeY(DropRules.Level.EASY),
                DropperGeometry.minSizeY(DropRules.Level.MEDIUM), DropperGeometry.minSizeY(DropRules.Level.HARD),
                DropperGeometry.minSizeY(DropRules.Level.EXTREME)),
                "a level needs 8 over its ledge, its drop, 3 of pool, a floor and a row under it");
        assertFalse(DropperGeometry.fits(Box.sized(0, 0, 0, 64, 140, 16), DropRules.levels("EEMHX")),
                "64 x 140 x 16 refuses the Long Drop");
        assertTrue(DropperGeometry.fits(Box.sized(0, 0, 0, 64, 141, 16), DropRules.levels("EEMHX")),
                "64 x 141 x 16 takes it");
        assertTrue(DropperGeometry.fits(LegacyBoxes.tall(DropperSlots.DROPPER_SLOT, 'B'), DropRules.levels("XXXXX")),
                "and the 144-tall half holds five of them");
    }
}
