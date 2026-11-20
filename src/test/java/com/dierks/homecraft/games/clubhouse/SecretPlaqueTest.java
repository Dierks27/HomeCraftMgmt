package com.dierks.homecraft.games.clubhouse;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 0.44.2: the secret room's trophy plaques go out onto the ledge's front, the way each place faces (into the room);
 * the heads are turned so their face, not the back of the head, looks at whoever looks.
 */
class SecretPlaqueTest {

    @Test
    void aPlaqueGoesOutTheWayItsPlaceFaces() {
        Location east = SideRooms.out(new Location(null, 10.5, 64, 10.5, -90f, 0f), 0.5);
        assertEquals(11.0, east.getX(), 1e-9, "a west wall's place faces east (+x)");
        assertEquals(10.5, east.getZ(), 1e-9);
        Location north = SideRooms.out(new Location(null, 10.5, 64, 10.5, 180f, 0f), 0.5);
        assertEquals(10.0, north.getZ(), 1e-9, "the back wall's faces north (-z)");
        Location west = SideRooms.out(new Location(null, 10.5, 64, 10.5, 90f, 0f), 0.5);
        assertEquals(10.0, west.getX(), 1e-9, "an east wall's faces west (-x)");
        assertEquals(64.0, west.getY(), 1e-9, "never up or down");
        assertEquals(0.52, SideRooms.SECRET_LABEL_OUT, 1e-9, "just out from the ledge's face (0.5 from the middle)");
    }

    @Test
    void aHeadIsTurnedHalfRound() {
        var t = SideRooms.headTurn(1.0f);
        assertEquals(1.0f, t.getScale().x(), 1e-6f);
        assertEquals(Math.PI, 2 * Math.acos(Math.abs(t.getLeftRotation().w())), 1e-4, "half a turn about y");
    }
}
