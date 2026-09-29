package com.dierks.homecraft.games.trial;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a race boat is put back after a reset (EVENTS-DROPPER-SPEC §A.4.7): boats bump, so the
 * reseat looks up to 1.5 blocks sideways across the checkpoint for a spot with no other race boat
 * within 2 blocks, and uses the checkpoint itself only when there is none.
 */
class RaceSeatTest {

    private static final Point AT = new Point(10.5, 65, 20.5);

    @Test
    void anEmptyCheckpointIsUsedAsItIs() {
        assertEquals(AT, RaceSeat.clear(AT, 0f, List.of(), null), "nobody there: the checkpoint itself");
        assertEquals(AT, RaceSeat.clear(AT, 0f, List.of(new Point(30, 65, 20.5)), null), "a boat far away is no matter");
    }

    @Test
    void aReseatAvoidsARaceBoatWithinTwoBlocks() {
        Point boat = new Point(11.5, 65, 20.5); // a block to the side of the checkpoint
        Point p = RaceSeat.clear(AT, 0f, List.of(boat), null);
        assertEquals(new Point(9.5, 65, 20.5), p, "the nearest spot clear of it, a block the other way");
        assertTrue(p.flatDistance(boat) >= RaceSeat.CLEAR, "no race boat within 2 blocks: " + p);
        assertEquals(AT.z(), p.z(), 1e-9, "sideways across the track (facing +z, sideways is x)");
        assertTrue(Math.abs(p.x() - AT.x()) <= RaceSeat.SIDEWAYS + 1e-9, "at most 1.5 blocks aside");
        assertEquals(AT.y(), p.y(), 1e-9, "at the checkpoint's height");
    }

    @Test
    void itSkipsSpotsABoatDoesntFit() {
        Point boat = new Point(11.5, 65, 20.5);
        // only the far side fits (a wall nearer, say)
        Point p = RaceSeat.clear(AT, 0f, List.of(boat), q -> q.x() < 9.5);
        assertTrue(p.x() < 9.5 && p.flatDistance(boat) >= RaceSeat.CLEAR, "the first free spot a boat fits: " + p);
    }

    @Test
    void withNowhereClearItUsesTheCheckpointBetterABumpThanNoWayBack() {
        List<Point> jam = List.of(new Point(9, 65, 20.5), new Point(10.5, 65, 20.5), new Point(12, 65, 20.5));
        assertEquals(AT, RaceSeat.clear(AT, 0f, jam, null), "boats all across it: the checkpoint itself");
        assertTrue(RaceSeat.free(AT, List.of(new Point(12.6, 65, 20.5))), "2.1 away is clear");
        assertTrue(!RaceSeat.free(AT, List.of(new Point(12.4, 65, 20.5))), "1.9 away isn't");
        assertTrue(RaceSeat.free(AT, null), "no boats: clear");
    }
}
