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
        assertEquals(new HubSettings(true, "fairyhub"), HubSettings.defaults());
        assertEquals(List.of("enabled", "world"), HubSettings.KEYS);
        assertTrue(GameCatalog.SPECS.contains(Hub.SPEC), "the games know it");
        assertEquals("hub", Hub.SPEC.id());
    }

    @Test
    void itsSettingsRead() {
        List<String> warns = new ArrayList<>();
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("enabled", false);
        raw.put("world", " lobby ");
        assertEquals(new HubSettings(false, "lobby"), parse(raw, warns));
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
        assertEquals(List.of("status", "here"), admin.tab(null, new String[]{""}));
        assertEquals(List.of("here"), admin.tab(null, new String[]{"h"}));
        assertEquals(List.of("clear"), admin.tab(null, new String[]{"here", "c"}));
        assertEquals(2, admin.help().size());
    }
}
