package com.dierks.homecraft.games.trial;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sky Rings' chunks held during a run, with no server: every chunk column a ring's frame touches and the
 * tower's are held when a run is armed and let go when it ends; two runs on one course share a chunk, which
 * goes with the last of them; a load that comes in after its run ended takes no ticket; and a course without
 * a rocket budget (a hand-built elytra course, any other kind) holds nothing.
 */
class RingChunksTest {

    private static final UUID SAM = new UUID(0, 1);
    private static final UUID LEE = new UUID(0, 2);

    /** Records holds and releases; a hold's {@code still} is kept to ask later, as the async load would. */
    private static final class Fake implements RingChunks.Tickets {
        final List<String> held = new ArrayList<>();
        final List<String> released = new ArrayList<>();
        final List<BooleanSupplier> still = new ArrayList<>();

        @Override
        public void hold(String world, int cx, int cz, BooleanSupplier stillWanted) {
            held.add(world + " " + cx + "," + cz);
            still.add(stillWanted);
        }

        @Override
        public void release(String world, int cx, int cz) {
            released.add(world + " " + cx + "," + cz);
        }
    }

    private static Course skyRings(Course.Elytra budget) {
        return new Course("fresh_rings", TrialKind.ELYTRA, "Sky Rings", Tier.EASY, "games",
                new Course.Spot(6100.5, 287, 4599.5, 180, 0),
                List.of(new Course.Mark(6100.5, 278.5, 4574.5, 7), new Course.Mark(6100.5, 260.5, 4474.5, 7)),
                new Course.Mark(6200.5, 250.5, 4400.5, 7), 130.0, 30, true, false, 1, null, budget);
    }

    private static final Course.Elytra BUDGET = new Course.Elytra(4, List.of(2, 2, 0), "LG");

    @Test
    void theRingsAndTheTowerAreHeldUntilTheRunEnds() {
        Fake f = new Fake();
        RingChunks chunks = new RingChunks(f);
        Course c = skyRings(BUDGET);
        chunks.arm(SAM, c);
        List<int[]> columns = RingChunks.columns(c);
        assertEquals(columns.size(), f.held.size(), "each column once");
        assertTrue(f.held.contains("games 381,287"), "the tower (x 6100, z 4599)");
        assertTrue(f.held.contains("games 381,279"), "ring 2's centre (z 4474)");
        assertTrue(f.held.contains("games 387,274") && f.held.contains("games 388,274"),
                "the gold ring's frame reaches x 6209, over the chunk edge at 6208: " + f.held);
        assertTrue(f.still.get(0).getAsBoolean(), "a load that comes in mid-run is held");
        chunks.release(SAM);
        assertEquals(f.held.size(), f.released.size(), "every one let go");
        assertFalse(f.still.get(0).getAsBoolean(), "a load that comes in after the run ended takes no ticket");
        assertEquals(0, chunks.holding());
        chunks.release(SAM);
        assertEquals(f.held.size(), f.released.size(), "letting go twice is nothing");
    }

    @Test
    void twoRunsShareAChunkAndItGoesWithTheLast() {
        Fake f = new Fake();
        RingChunks chunks = new RingChunks(f);
        Course c = skyRings(BUDGET);
        chunks.arm(SAM, c);
        int once = f.held.size();
        chunks.arm(LEE, c);
        assertEquals(once, f.held.size(), "the second run asks for nothing more");
        chunks.release(SAM);
        assertTrue(f.released.isEmpty(), "Lee still flies: nothing let go");
        chunks.release(LEE);
        assertEquals(once, f.released.size(), "the last one out lets go");
    }

    @Test
    void armingAgainLetsGoOfTheLastRunsFirst() {
        Fake f = new Fake();
        RingChunks chunks = new RingChunks(f);
        chunks.arm(SAM, skyRings(BUDGET));
        int once = f.held.size();
        chunks.arm(SAM, skyRings(BUDGET)); // Play again
        assertEquals(once, f.released.size(), "the old run's let go");
        assertEquals(once, chunks.holding(), "and the new run's held");
        chunks.releaseAll();
        assertEquals(0, chunks.holding(), "the game stopped: everything let go");
    }

    @Test
    void onlySkyRingsHoldsChunks() {
        Fake f = new Fake();
        RingChunks chunks = new RingChunks(f);
        chunks.arm(SAM, skyRings(null));
        Course parkour = new Course("steps", TrialKind.PARKOUR, "Steps", Tier.EASY, "games",
                new Course.Spot(0, 64, 0, 0, 0), List.of(), new Course.Mark(10, 64, 0, 2), null, null, true, false, 1);
        chunks.arm(LEE, parkour);
        assertTrue(f.held.isEmpty(), "a hand-built elytra course and parkour: as before");
        chunks.release(SAM);
        assertTrue(f.released.isEmpty());
    }
}
