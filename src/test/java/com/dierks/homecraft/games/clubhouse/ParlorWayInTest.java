package com.dierks.homecraft.games.clubhouse;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.45.0, the owner: "make the dark door the secret room and the star the casino so you must know how to get into it
 * so no kiddos can play. Also maybe make a permission that I can set for folks." The Parlor's star needs its own
 * permission AND the games of chance's; the secret room's door only its own.
 */
class ParlorWayInTest {

    @Test
    void theParlorsStarNeedsBothPermissions() {
        assertEquals("hcm.clubhouse.parlor+hcm.games.chance", SideRooms.PARLOR_WAY_IN);
        assertTrue(SideRooms.allOf(SideRooms.PARLOR_WAY_IN, Set.of("hcm.clubhouse.parlor", "hcm.games.chance")::contains));
        assertFalse(SideRooms.allOf(SideRooms.PARLOR_WAY_IN, Set.of("hcm.games.chance")::contains),
                "locked down: the Parlor's permission taken away");
        assertFalse(SideRooms.allOf(SideRooms.PARLOR_WAY_IN, Set.of("hcm.clubhouse.parlor")::contains),
                "a child without the games of chance never sees the star");
        assertTrue(SideRooms.allOf(SideRooms.SECRET_PERMISSION, Set.of("hcm.clubhouse.secretroom")::contains));
        assertFalse(SideRooms.allOf(SideRooms.SECRET_PERMISSION, Set.<String>of()::contains));
    }
}
