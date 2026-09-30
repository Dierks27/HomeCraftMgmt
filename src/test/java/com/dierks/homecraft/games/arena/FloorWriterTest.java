package com.dierks.homecraft.games.arena;

import com.dierks.homecraft.games.arena.rules.Cell;
import com.dierks.homecraft.games.arena.rules.CellState;
import com.dierks.homecraft.games.arena.rules.FloorWrite;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.engine.WorldPort;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The only writer a round has (EVENTS-DROPPER-SPEC §B.3.3, S1): it throws outside the box, refuses
 * any block that isn't a planned floor cell and any state but the floor's own glass, red or air,
 * writes at most 128 a tick in order, and drops what is waiting when a reset starts.
 */
class FloorWriterTest {

    private final ArenaSite site = ArenaPlannerTest.site(42);
    private final Box box = site.box();
    private final FakeWorldPort world = new FakeWorldPort("games");
    private final FloorWriter writer = new FloorWriter(world, box, site::layout, site.floorBlocks());

    private Cell topCell(int n) {
        return site.layout().layer(0).cells().get(n);
    }

    @Test
    void itThrowsOutsideTheBoxBeforeWritingAnything() {
        assertThrows(IllegalStateException.class, () -> writer.write(box.maxX() + 1, 200, box.minZ() + 20,
                FloorWriter.RED), "outside the box is never written");
        assertThrows(IllegalStateException.class, () -> writer.write(box.minX() + 20, box.maxY() + 1, box.minZ() + 20,
                WorldPort.AIR), "above the box neither");
        assertEquals(0, world.writes, "nothing reached the world");
    }

    @Test
    void itRefusesAnythingButAPlannedFloorCell() {
        assertFalse(writer.write(box.minX() + 8, 200, box.minZ() + 8, FloorWriter.RED),
                "the footprint's empty corner isn't a floor cell");
        assertFalse(writer.write(box.minX() + 1, 206, box.minZ() + 20, WorldPort.AIR), "the gallery is not a floor");
        Cell c = topCell(0);
        assertFalse(writer.write(c.x(), 201, c.z(), FloorWriter.RED), "above a floor cell isn't one");
        assertEquals(3, writer.refused(), "each refusal is counted");
        assertEquals(0, world.writes, "and none reached the world");
        assertTrue(writer.write(c.x(), 200, c.z(), FloorWriter.RED), "a floor cell may turn red");
        assertEquals(FloorWriter.RED, world.at(c.x(), 200, c.z()), "and it did");
    }

    @Test
    void itRefusesEveryBlockButTheFloorsOwnRedOrAir() {
        Cell c = topCell(5);
        for (String foreign : List.of("minecraft:tnt", "minecraft:water[level=0]", "minecraft:pink_stained_glass",
                "minecraft:white_concrete", "minecraft:glass")) {
            assertFalse(writer.write(c.x(), 200, c.z(), foreign), foreign + " is refused on the yellow floor");
        }
        assertEquals(0, world.writes, "none of them was written");
        assertTrue(writer.write(c.x(), 200, c.z(), "minecraft:yellow_stained_glass"), "its own glass may go back");
        assertTrue(writer.write(c.x(), 200, c.z(), FloorWriter.RED), "red");
        assertTrue(writer.write(c.x(), 200, c.z(), WorldPort.AIR), "air");
        Cell mid = site.layout().layer(1).cells().get(0);
        assertTrue(writer.write(mid.x(), 192, mid.z(), "minecraft:pink_stained_glass"), "pink is the middle's own");
        assertFalse(writer.write(mid.x(), 192, mid.z(), "minecraft:yellow_stained_glass"), "yellow isn't");
    }

    @Test
    void itWritesAtMostOneHundredAndTwentyEightATickInOrder() {
        List<FloorWrite> writes = new ArrayList<>();
        List<Cell> cells = site.layout().layer(0).cells();
        for (int i = 0; i < 150; i++) {
            Cell c = cells.get(i);
            writes.add(new FloorWrite(0, c.x(), 200, c.z(), CellState.RED));
        }
        for (int i = 0; i < 150; i++) {
            Cell c = cells.get(i);
            writes.add(new FloorWrite(0, c.x(), 200, c.z(), CellState.AIR));
        }
        writer.queue(writes);
        assertEquals(300, writer.waiting(), "all queued");
        assertEquals(FloorWriter.MAX_PER_TICK, writer.flush(), "128 the first tick");
        assertEquals(FloorWriter.MAX_PER_TICK, writer.flush(), "128 the next");
        assertEquals(44, writer.flush(), "the rest the third");
        assertEquals(0, writer.flush(), "nothing left");
        assertEquals(128, writer.maxTick(), "the most in a tick is the cap");
        for (int i = 0; i < 150; i++) {
            Cell c = cells.get(i);
            int red = world.log.indexOf(c.x() + ",200," + c.z() + "=" + FloorWriter.RED);
            int air = world.log.indexOf(c.x() + ",200," + c.z() + "=" + WorldPort.AIR);
            assertTrue(red >= 0 && air > red, "a cell's air is never written before its red");
        }
    }

    @Test
    void clearDropsWhatIsWaitingSoNothingLandsOnAResetFloor() {
        List<FloorWrite> writes = new ArrayList<>();
        for (Cell c : site.layout().layer(1).cells().subList(0, 200)) {
            writes.add(new FloorWrite(1, c.x(), 192, c.z(), CellState.RED));
        }
        writer.queue(writes);
        writer.flush();
        writer.clear();
        assertEquals(0, writer.waiting(), "a reset drops the queue");
        assertEquals(0, writer.flush(), "and nothing is written after it");
        assertEquals(128, world.writes, "only the first tick's writes happened");
    }
}
