package com.dierks.homecraft.games.arena.rules;

import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Plan;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.dierks.homecraft.games.arena.rules.ArenaFixtures.CX;
import static com.dierks.homecraft.games.arena.rules.ArenaFixtures.CZ;
import static com.dierks.homecraft.games.arena.rules.ArenaFixtures.LOW_Y;
import static com.dierks.homecraft.games.arena.rules.ArenaFixtures.MID_Y;
import static com.dierks.homecraft.games.arena.rules.ArenaFixtures.OUT_Y;
import static com.dierks.homecraft.games.arena.rules.ArenaFixtures.TOP_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The week's floors as the rules read them (EVENTS-DROPPER-SPEC §B.3.2): built from the plan the
 * reset converges to, top first, with rings for sudden death, and refusing a layout the rules
 * could not run fairly.
 */
class FloorLayoutTest {

    @Test
    void theSitesDiscIsAboutFourHundredAndFiftyCells() {
        FloorLayout l = ArenaFixtures.discs();
        int n = l.cellCount(0);
        assertTrue(n >= 405 && n <= 495, "a disc of radius 12 is within 10% of 450 cells, as the planner's "
                + "shapes must be: " + n);
        assertEquals(3 * n, l.totalCells(), "three floors of the same disc");
    }

    @Test
    void floorsAreKeptTopFirstWhateverOrderTheyCameIn() {
        List<Cell> sq = ArenaFixtures.square(CX, CZ, 3);
        FloorLayout l = new FloorLayout(List.of(new FloorLayout.Layer(LOW_Y, sq), new FloorLayout.Layer(TOP_Y, sq),
                new FloorLayout.Layer(MID_Y, sq)), OUT_Y, List.of(new Cell(CX, CZ)));
        assertEquals(TOP_Y, l.layer(0).y(), "layer 0 is the top, where rounds start");
        assertEquals(MID_Y, l.layer(1).y(), "then the middle");
        assertEquals(LOW_Y, l.layer(2).y(), "then the bottom");
        assertEquals(TOP_Y + 1, l.topY(0), "players stand on top of the blocks");
        assertEquals(1, l.layerAtY(MID_Y), "a height finds its floor");
        assertEquals(-1, l.layerAtY(MID_Y + 1), "the air above a floor is not a floor");
    }

    @Test
    void onlyPlannedFloorCellsAreFloorCells() {
        FloorLayout l = ArenaFixtures.small();
        assertTrue(l.isFloorCell(CX, TOP_Y, CZ), "a planned cell of the top floor");
        assertTrue(l.isFloorCell(CX + 5, LOW_Y, CZ + 5), "a planned cell of the bottom floor");
        assertFalse(l.isFloorCell(CX + 6, TOP_Y, CZ), "one past the edge is not a cell");
        assertFalse(l.isFloorCell(CX, TOP_Y + 1, CZ), "above a cell is not a cell");
        assertFalse(l.isFloorCell(CX, 206, CZ), "the gallery is never a floor cell");
        assertEquals(-1, l.ordinal(0, CX - 1000, CZ), "far outside the footprint is no cell (and doesn't throw)");
        assertEquals(-1, l.ordinal(7, CX, CZ), "a floor that doesn't exist has no cells");
    }

    @Test
    void theLayoutOfAPlanIsItsOpsAtTheFloorHeightsAndNothingElse() {
        List<String> palette = List.of("minecraft:yellow_stained_glass", "minecraft:pink_stained_glass",
                "minecraft:light_blue_stained_glass", "minecraft:glass", "minecraft:white_concrete");
        List<BlockOp> ops = new ArrayList<>();
        for (Cell c : ArenaFixtures.square(CX, CZ, 4)) {
            ops.add(new BlockOp(c.x(), TOP_Y, c.z(), (short) 0));
            ops.add(new BlockOp(c.x(), MID_Y, c.z(), (short) 1));
        }
        ops.add(new BlockOp(CX + 1, LOW_Y, CZ + 1, (short) 2));
        ops.add(new BlockOp(CX - 10, 206, CZ, (short) 4)); // the gallery walk
        ops.add(new BlockOp(CX - 10, 207, CZ, (short) 3)); // its rail
        Plan plan = Plan.of("falling_floors", 1, 42L, Box.sized(5376, 176, 4352, 48, 40, 48), palette, ops, List.of(),
                List.of(), null, List.of(), 0);
        FloorLayout l = FloorLayout.fromPlan(plan, List.of(TOP_Y, MID_Y, LOW_Y), OUT_Y, List.of(new Cell(CX, CZ)));
        assertEquals(16, l.cellCount(0), "the top floor is the plan's 16 ops at y 200");
        assertEquals(16, l.cellCount(1), "the middle floor is the 16 at y 192");
        assertEquals(1, l.cellCount(2), "the bottom floor is the one op at y 184");
        assertEquals(33, l.totalCells(), "the gallery and its rail are not floors");
        assertTrue(l.isFloorCell(CX + 1, LOW_Y, CZ + 1), "the bottom's cell is a floor cell");
    }

    @Test
    void ringsCountFromTheOutsideAndSkipEmptyDistances() {
        FloorLayout discs = ArenaFixtures.discs();
        int rings = discs.ringCount(0);
        assertTrue(rings >= 10 && rings <= 13, "a disc of radius 12 has about 12 one-block rings: " + rings);
        FloorLayout.Layer top = discs.layer(0);
        int outer = discs.ring(0, top.cells().indexOf(new Cell(CX - 12, CZ)));
        int inner = discs.ring(0, top.cells().indexOf(new Cell(CX, CZ)));
        assertEquals(0, outer, "a cell on the rim is in ring 0, the first to fall");
        assertEquals(rings - 1, inner, "a middle cell is in the last ring");

        // A ring with an island: the rim, a gap, then the middle. The gap is not a ring of its own.
        List<Cell> ringAndIsland = new ArrayList<>();
        for (Cell c : ArenaFixtures.disc(12)) {
            double dx = c.x() + 0.5 - CX;
            double dz = c.z() + 0.5 - CZ;
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d >= 10 || d <= 3) {
                ringAndIsland.add(c);
            }
        }
        FloorLayout l = new FloorLayout(List.of(new FloorLayout.Layer(TOP_Y, ringAndIsland)), OUT_Y,
                List.of(new Cell(CX, CZ)));
        int islandRings = 0;
        for (int n = 0; n < l.cellCount(0); n++) {
            islandRings = Math.max(islandRings, l.ring(0, n));
        }
        assertEquals(l.ringCount(0) - 1, islandRings, "rings are numbered without gaps");
        assertEquals(5, l.ringCount(0), "only the distances that have cells are rings (2 for the rim, 3 for the "
                + "island, none for the gap), so every 2 s something falls");
    }

    @Test
    void theExactSquareRootIsExact() {
        assertEquals(0, FloorLayout.isqrt(0), "0");
        assertEquals(1, FloorLayout.isqrt(3), "3 rounds down to 1");
        assertEquals(2, FloorLayout.isqrt(4), "4 is 2");
        assertEquals(46340, FloorLayout.isqrt(2147395600L), "a big perfect square");
        assertEquals(46339, FloorLayout.isqrt(2147395599L), "just below it");
    }

    @Test
    void aLayoutTheRulesCouldNotRunIsRefused() {
        List<Cell> sq = ArenaFixtures.square(CX, CZ, 3);
        List<Cell> spawn = List.of(new Cell(CX, CZ));
        assertThrows(IllegalArgumentException.class, () -> new FloorLayout(List.of(), OUT_Y, spawn),
                "no floors");
        assertThrows(IllegalArgumentException.class, () -> new FloorLayout(List.of(new FloorLayout.Layer(TOP_Y,
                        sq), new FloorLayout.Layer(TOP_Y - 4, sq)), OUT_Y, spawn),
                "floors 4 apart: a jump could bump the floor above");
        assertThrows(IllegalArgumentException.class, () -> new FloorLayout(List.of(new FloorLayout.Layer(LOW_Y,
                        sq)), LOW_Y + 1, spawn),
                "out above the bottom floor would put a standing player out");
        assertThrows(IllegalArgumentException.class, () -> new FloorLayout(List.of(new FloorLayout.Layer(TOP_Y,
                        sq)), OUT_Y, List.of(new Cell(CX + 9, CZ))),
                "a spawn off the top floor");
        assertThrows(IllegalArgumentException.class, () -> new FloorLayout(List.of(new FloorLayout.Layer(TOP_Y,
                        sq)), OUT_Y, List.of()),
                "no spawns");
        assertThrows(IllegalArgumentException.class, () -> new FloorLayout.Layer(TOP_Y,
                        List.of(new Cell(CX, CZ), new Cell(CX, CZ))),
                "a cell twice");
        assertThrows(IllegalArgumentException.class, () -> new FloorLayout(List.of(new FloorLayout.Layer(TOP_Y,
                        List.of(new Cell(CX, CZ), new Cell(CX + 300, CZ)))), OUT_Y, spawn),
                "wider than any arena");
    }
}
