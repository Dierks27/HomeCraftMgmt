package com.dierks.homecraft.arcade;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who sees a puff (SHOP-V2-SPEC §5.5, D14, D15): never where a trail can't show (a game session, a Games world,
 * spectator mode, invisible: a vanished admin's trail would give them away); the wearer always sees their own; anyone
 * else only when they don't hide other people's trails, can see the wearer and are within 32 blocks.
 */
class TrailViewersTest {

    @Test
    void aTrailShowsOnlyWhereItMay() {
        assertTrue(TrailService.showsHere(false, false, false, false), "out and about");
        assertFalse(TrailService.showsHere(true, false, false, false), "in a game session");
        assertFalse(TrailService.showsHere(false, true, false, false), "in a Games world");
        assertFalse(TrailService.showsHere(false, false, true, false), "in spectator mode");
        assertFalse(TrailService.showsHere(false, false, false, true), "invisible");
    }

    @Test
    void theWearerAlwaysSeesTheirOwn() {
        assertTrue(TrailService.sees(true, true, false, 10_000), "hiding others never hides your own");
    }

    @Test
    void anotherViewerSeesItOnlyWhenTheyMay() {
        assertTrue(TrailService.sees(false, false, true, 0), "next to them");
        assertTrue(TrailService.sees(false, false, true, 32 * 32), "32 blocks away");
        assertFalse(TrailService.sees(false, false, true, 32 * 32 + 1), "further than 32 blocks");
        assertFalse(TrailService.sees(false, true, true, 4), "a viewer who hides others' trails");
        assertFalse(TrailService.sees(false, false, false, 4), "a viewer who can't see the wearer (vanished)");
    }
}
