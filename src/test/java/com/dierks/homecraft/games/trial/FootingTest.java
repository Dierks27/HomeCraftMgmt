package com.dierks.homecraft.games.trial;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether a hand-built parkour point stands on something (0.37.1, review FP-1): feet on a block, or within half
 * a block over one, under the player's width; a point set while flying higher than that is listed, in order, for
 * {@code /hcm games check} and warned about when it is set. Only parkour is asked.
 */
class FootingTest {

    /** One pad: the 3x3 of blocks x 9..11, z -1..1 at y 63, so its top is y 64. */
    private static final Footing.Solid PAD = (x, y, z) -> y == 63 && x >= 9 && x <= 11 && z >= -1 && z <= 1;

    @Test
    void feetOnTheBlockOrJustOverItAreOnGround() {
        assertTrue(Footing.onGround(10.5, 64, 0.5, PAD), "standing on the pad");
        assertTrue(Footing.onGround(10.5, 64.3, 0.5, PAD), "a hair over it (a carpet, a hop)");
        assertTrue(Footing.onGround(10.5, 64.5, 0.5, PAD), "half a block over it");
        assertTrue(Footing.onGround(10.5, 63.5, 0.5, PAD), "on a slab: the feet half-way up its own block");
    }

    @Test
    void feetInTheAirAreNot() {
        assertFalse(Footing.onGround(10.5, 64.6, 0.5, PAD), "more than half a block over the pad");
        assertFalse(Footing.onGround(10.5, 65, 0.5, PAD), "a block over it: set while flying");
        assertFalse(Footing.onGround(5, 64, 0, PAD), "beside the pad");
    }

    @Test
    void thePlayersWidthStandsOnAnEdge() {
        assertTrue(Footing.onGround(12.2, 64, 0.5, PAD), "the middle 0.2 past the pad's edge: still on it");
        assertFalse(Footing.onGround(12.4, 64, 0.5, PAD), "0.4 past it: off");
    }

    @Test
    void everyParkourPointInTheAirIsListedInOrder() {
        Course c = new Course("cliffs", TrialKind.PARKOUR, "Cliffs", Tier.EASY, "games",
                new Course.Spot(14, 64, 0, 0, 0),
                List.of(new Course.Mark(10.5, 64, 0.5, 1.5), new Course.Mark(10.5, 65.2, 0.5, 1.5)),
                new Course.Mark(20, 70, 0, 1.5), null, null, true, false, 1);
        assertEquals(List.of("checkpoint 2", "the finish"), Footing.offGround(c, PAD));
        Course on = c.withCheckpoints(List.of(c.checkpoints().get(0))).withFinish(new Course.Mark(10, 64, 0, 1.5));
        assertEquals(List.of(), Footing.offGround(on, PAD), "every point on the pad");
    }

    @Test
    void onlyParkourIsAsked() {
        for (TrialKind kind : List.of(TrialKind.ELYTRA, TrialKind.BOAT, TrialKind.DROPPER)) {
            Course c = new Course("x", kind, "X", Tier.EASY, "games", new Course.Spot(0, 64, 0, 0, 0),
                    List.of(new Course.Mark(0, 90, 0, 4)), new Course.Mark(0, 95, 0, 4), null, null, true, false, 1);
            assertEquals(List.of(), Footing.offGround(c, PAD), kind + ": a ring is flown through, a mark is on water");
        }
        assertEquals(List.of(), Footing.offGround(null, PAD));
    }
}
