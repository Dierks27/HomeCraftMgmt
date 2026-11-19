package com.dierks.homecraft.games.hub;

import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.games.GameCatalog;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Hub (0.44.0): its settings, its arrival spot as kept, its admin's words, and that the games know it. */
class HubTest {

    private static HubSettings parse(Map<String, Object> raw, List<String> warns) {
        return HubSettings.parse(new GamesConfig.Node("games.hub", raw, warns::add), HubSettings.defaults());
    }

    @Test
    void itShipsOnInTheFirstJoinSpawnWorld() {
        assertEquals(new HubSettings(true, "fairyhub", "world"), HubSettings.defaults());
        assertEquals(List.of("enabled", "world", "home_world"), HubSettings.KEYS);
        assertTrue(GameCatalog.SPECS.contains(Hub.SPEC), "the games know it");
        assertEquals("hub", Hub.SPEC.id());
    }

    @Test
    void itsSettingsRead() {
        List<String> warns = new ArrayList<>();
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("enabled", false);
        raw.put("world", " lobby ");
        assertEquals(new HubSettings(false, "lobby", "world"), parse(raw, warns));
        assertTrue(warns.isEmpty());
        raw.put("world", "");
        assertEquals("fairyhub", parse(raw, warns).world(), "an empty world is the shipped one");
        assertEquals(1, warns.size(), "with one WARN");
    }

    @Test
    void theArrivalSpotIsKeptAndReadBack() {
        Hub.Spot s = new Hub.Spot("fairyhub", 10.5, 64.0, -3.25, 90f);
        assertEquals(s, Hub.Spot.of(s.text()));
        assertNull(Hub.Spot.of(null));
        assertNull(Hub.Spot.of("fairyhub,1,2"), "too few parts");
        assertNull(Hub.Spot.of("fairyhub,a,2,3,0"), "not a number");
    }

    @Test
    void theAdminsWords() {
        HubAdmin admin = new HubAdmin(null);
        assertEquals("hub", admin.name());
        assertEquals(List.of("status", "here", "exit"), admin.tab(null, new String[]{""}));
        assertEquals(List.of("here"), admin.tab(null, new String[]{"h"}));
        assertEquals(List.of("clear"), admin.tab(null, new String[]{"here", "c"}));
        assertEquals(List.of("clear"), admin.tab(null, new String[]{"exit", ""}));
        assertEquals(3, admin.help().size());
    }

    @Test
    void itIsNeverTheFirstGamesWorld() {
        assertTrue(Hub.firstGamesWorld(List.of("fairyhub", "games"), "fairyhub") != null, "first: closed, and why");
        assertNull(Hub.firstGamesWorld(List.of("games", "fairyhub"), "FairyHub"));
        assertNull(Hub.firstGamesWorld(List.of(), "fairyhub"));
    }

    /** The exit pad (0.44.1): feet on its block (within 0.9 sideways, 1.5 up or down) and nowhere else. */
    @Test
    void theExitPad() {
        assertTrue(Hub.onPad(10.5, 64, 10.5, 10.5, 64, 10.5), "on it");
        assertTrue(Hub.onPad(11.2, 64.5, 10.5, 10.5, 64, 10.5), "near the edge");
        assertTrue(!Hub.onPad(11.6, 64, 10.5, 10.5, 64, 10.5), "the next block over");
        assertTrue(!Hub.onPad(10.5, 66, 10.5, 10.5, 64, 10.5), "far above");
    }
}
