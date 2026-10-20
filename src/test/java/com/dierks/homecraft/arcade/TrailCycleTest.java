package com.dierks.homecraft.arcade;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The Wallet's trail tile steps through the trails you own, then off (HALLOWEEN-SPEC §3.1 #21), and
 * switching one on shows its burst in front of your eyes, not behind a first-person camera (D3).
 */
class TrailCycleTest {

    private static final List<String> OWNED = List.of("trail_leaves_2026", "trail_hearts", "trail_notes");

    @Test
    void offThenEachTrailThenOff() {
        assertEquals("trail_leaves_2026", TrailService.next(OWNED, null), "none on: the first");
        assertEquals("trail_hearts", TrailService.next(OWNED, "trail_leaves_2026"), "the first: the second");
        assertEquals("trail_notes", TrailService.next(OWNED, "TRAIL_HEARTS"), "ids match however they are written");
        assertNull(TrailService.next(OWNED, "trail_notes"), "the last: off");
    }

    @Test
    void oneNoLongerOwnedStartsAgainAndNoneIsOff() {
        assertEquals("trail_leaves_2026", TrailService.next(OWNED, "trail_expired"), "unknown: the first");
        assertNull(TrailService.next(List.of(), null), "no trails: off");
        assertNull(TrailService.next(null, "trail_hearts"));
    }

    @Test
    void withOneTrailItIsOnAndOff() {
        List<String> one = List.of("trail_leaves_2026");
        assertEquals("trail_leaves_2026", TrailService.next(one, null));
        assertNull(TrailService.next(one, "trail_leaves_2026"));
    }

    @Test
    void theBurstIsInFrontOfTheEyes() {
        Location eye = new Location(null, 10, 70, -5, 0f, 0f); // yaw 0 looks along +z
        Location at = TrailService.burstAt(eye);
        assertEquals(10, at.getX(), 1e-9);
        assertEquals(70.8, at.getY(), 1e-9);
        assertEquals(-3.5, at.getZ(), 1e-9);
        assertEquals(70, eye.getY(), 1e-9, "the eye itself is not moved");

        Location down = TrailService.burstAt(new Location(null, 0, 64, 0, 90f, 90f)); // straight down
        assertEquals(0, down.getX(), 1e-9);
        assertEquals(64 - 1.5 + 0.8, down.getY(), 1e-9);
    }
}
