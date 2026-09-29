package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Slots;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Nobody changes a generated course (GEN-SPEC §6 S6), as pure decisions: inside a half every kind
 * of change is refused for everyone, admins included; outside it nothing is; a piston is refused
 * when it, a block it moves, or where that block lands is inside; an explosion spares exactly the
 * blocks inside.
 */
class GenRegionGuardTest {

    private static final Box HALF = Slots.DAILY_PARKOUR_EASY.half('A');
    private static final GenRegionGuard.Area AREA = (w, x, y, z) -> "games".equalsIgnoreCase(w)
            && HALF.contains(x, y, z);

    @Test
    void everyChangeInsideIsRefusedForEveryoneAdminsIncluded() {
        for (GenRegionGuard.Change c : GenRegionGuard.Change.values()) {
            assertTrue(GenRegionGuard.refused(c, true, false), c + " inside is refused for a player");
            assertTrue(GenRegionGuard.refused(c, true, true), c + " inside is refused for an admin too");
            assertFalse(GenRegionGuard.refused(c, false, false), c + " outside is none of the guard's business");
            assertFalse(GenRegionGuard.refused(c, false, true), c + " outside, for an admin, neither");
        }
        assertTrue(AREA.in("GAMES", HALF.minX(), HALF.minY(), HALF.minZ()), "the area's own corner is in it");
        assertFalse(AREA.in("games", HALF.minX() - 1, HALF.minY(), HALF.minZ()), "one block out is not");
        assertFalse(AREA.in("world", HALF.minX(), HALF.minY(), HALF.minZ()), "another world is not");
    }

    @Test
    void aPistonTouchingAHalfIsRefused() {
        int x = HALF.minX() - 3;
        int y = HALF.minY() + 5;
        int z = HALF.minZ() + 5;
        assertFalse(GenRegionGuard.pistonRefused(AREA, "games", new int[]{x, y, z},
                List.of(new int[]{x + 1, y, z}), 1, 0, 0), "a push that ends a block short of the half is fine");
        assertTrue(GenRegionGuard.pistonRefused(AREA, "games", new int[]{x, y, z},
                List.of(new int[]{x + 1, y, z}, new int[]{x + 2, y, z}), 1, 0, 0),
                "a push that moves a block into the half is refused");
        assertTrue(GenRegionGuard.pistonRefused(AREA, "games", new int[]{HALF.minX() - 1, y, z}, List.of(), 1, 0, 0),
                "a piston head reaching into the half is refused");
        assertTrue(GenRegionGuard.pistonRefused(AREA, "games", new int[]{x, y, z},
                List.of(new int[]{HALF.minX(), y, z}), -1, 0, 0), "pulling a block out of the half is refused");
        assertTrue(GenRegionGuard.pistonRefused(AREA, "games", new int[]{HALF.minX() + 2, y, z}, List.of(), 0, 1, 0),
                "a piston inside the half is refused");
    }

    @Test
    void anExplosionSparesExactlyTheBlocksInsideAHalf() {
        int y = HALF.minY() + 2;
        List<int[]> blocks = List.of(new int[]{HALF.minX() - 2, y, HALF.minZ()},
                new int[]{HALF.minX(), y, HALF.minZ()}, new int[]{HALF.minX() + 1, y, HALF.minZ() + 1});
        List<int[]> spared = GenRegionGuard.spared(AREA, "games", blocks);
        assertEquals(2, spared.size(), "the two inside are spared");
        assertEquals(HALF.minX(), spared.get(0)[0], "in order");
        assertEquals(0, GenRegionGuard.spared(AREA, "world", blocks).size(), "in another world none are");
    }
}
