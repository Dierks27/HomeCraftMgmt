package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.pack.ClubModels;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Floor Is Lava's cabinet in the Arcade Room: it takes the first cabinet place that only repeats a game (no game
 * loses its cabinet and no Clubhouse block changes), it has its own model with a no-pack stand-in, and its tap is the
 * Clubhouse's own tap kind.
 */
class LavaCabinetTest {

    @Test
    void itTakesTheFirstRepeatedPlaceAndNoGameLosesItsCabinet() {
        assertEquals(4, SideRooms.lavaIndex(4, ClubhousePlanner.CABINETS), "the shipped four games: the fifth place,"
                + " where it always stood (plan 11 gives the places after it to the other kid games)");
        assertEquals(1, SideRooms.lavaIndex(1, ClubhousePlanner.CABINETS));
        assertEquals(-1, SideRooms.lavaIndex(ClubhousePlanner.CABINETS, ClubhousePlanner.CABINETS),
                "every place has a game of its own: no lava cabinet (the check says so)");
        assertEquals(-1, SideRooms.lavaIndex(2, 0), "no Arcade Room built");
        assertEquals(0, SideRooms.lavaIndex(0, 3), "no games: the first place");
    }

    @Test
    void theShippedRoomHasAPlaceForIt() {
        Box box = ClubhouseSettings.defaults().box();
        List<ClubhousePlanner.Place3> places = ClubhousePlanner.cabinets(box);
        assertEquals(ClubhousePlanner.CABINETS, places.size());
        int shipped = ClubhouseSettings.defaults().arcadeGames().size();
        int n = SideRooms.lavaIndex(shipped, places.size());
        assertTrue(n >= shipped && n < places.size(), "a repeated place: " + n);
    }

    @Test
    void itHasItsOwnModelAndAStandInWithNoWords() {
        ClubModels.Model m = ClubModels.cabinetFor(SideRooms.LAVA_GAME);
        assertNotNull(m);
        assertEquals("cabinet_lava", m.name());
        assertEquals(m, ClubModels.named("cabinet_lava"), "registered by name for the pack");
        assertEquals(Material.LAVA_BUCKET, m.fallback(), "the stand-in floats a lava bucket over a plain cabinet");
        assertEquals("floor_is_lava", SideRooms.LAVA_GAME);
        assertEquals(com.dierks.homecraft.games.lava.FloorIsLava.SPEC.id(), SideRooms.LAVA_GAME,
                "the cabinet starts the real game");
    }
}
