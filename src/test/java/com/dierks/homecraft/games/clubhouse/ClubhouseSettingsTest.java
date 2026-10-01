package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.config.GamesConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code games.clubhouse} (CLUBHOUSE-SPEC §5): shipped on, at the pinned corner, 30 minutes, back to
 * the Clubhouse after everything; a value that can't be read logs one WARN and uses its shipped value
 * (the Clubhouse stays open); an origin off the grid is rounded down; only {@code enabled} and
 * {@code origin} fail closed (the shipped corner in place of an unreadable origin would be a second
 * room beside the one that stands).
 */
class ClubhouseSettingsTest {

    private static ClubhouseSettings read(Map<String, Object> m, List<String> warns) {
        GamesConfig.Node n = new GamesConfig.Node("games.clubhouse", m, warns::add);
        ClubhouseSettings s = ClubhouseSettings.parse(n, ClubhouseSettings.defaults());
        if (n.invalid()) {
            warns.add("INVALID");
        }
        return s;
    }

    @Test
    void theShippedValues() {
        ClubhouseSettings d = ClubhouseSettings.defaults();
        assertTrue(d.enabled(), "ships on: it builds itself in an empty box");
        assertEquals(30, d.maxMinutes(), "30 minutes");
        assertTrue(d.partyAfter() && d.raceNightAfter() && d.golfAfter(), "back here after everything");
        assertEquals(32, d.box().sizeX(), "32 wide");
        assertEquals(16, d.box().sizeY(), "16 high");
        List<String> warns = new ArrayList<>();
        assertEquals(d, read(Map.of(), warns), "an empty block reads as shipped");
        assertEquals(List.of(), warns, "without a WARN");
    }

    @Test
    void anUnreadableValueWarnsAndUsesItsShippedValue() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("max_minutes", "lots");
        m.put("party_after", "maybe");
        List<String> warns = new ArrayList<>();
        ClubhouseSettings s = read(m, warns);
        assertEquals(ClubhouseSettings.defaults(), s, "every bad value is its shipped one");
        assertFalse(warns.contains("INVALID"), "and the Clubhouse stays open: " + warns);
        assertEquals(2, warns.size(), "one WARN each: " + warns);
        for (String w : warns) {
            assertTrue(w.startsWith("games.clubhouse.") && w.endsWith("using its shipped value"), w);
        }
    }

    @Test
    void anUnreadableOriginClosesTheClubhouseRatherThanBuildASecondRoomAtTheShippedCorner() {
        for (Object bad : new Object[]{"here", List.of(5376, 160), java.util.Arrays.asList(5376, 160, "east")}) {
            List<String> warns = new ArrayList<>();
            read(Map.of("origin", bad), warns);
            assertTrue(warns.contains("INVALID"), bad + ": the Clubhouse is closed until it is fixed: " + warns);
            assertTrue(warns.get(0).startsWith("games.clubhouse.origin") && warns.get(0).endsWith("is off until it is"
                    + " fixed"), bad + ": one WARN says so: " + warns);
        }
        List<String> warns = new ArrayList<>();
        ClubhouseSettings s = read(Map.of("origin", java.util.Arrays.asList(5376, 160, "4448")), warns);
        assertEquals(List.of(5376, 160, 4448), s.origin(), "numbers written as text are read, as before");
        assertEquals(List.of(), warns, "without a WARN");
    }

    @Test
    void anOriginOffTheGridIsRoundedDownAndNumbersAreClamped() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("origin", List.of(5380, 160, 4450));
        m.put("max_minutes", 0);
        List<String> warns = new ArrayList<>();
        ClubhouseSettings s = read(m, warns);
        assertEquals(List.of(5376, 160, 4448), s.origin(), "x and z on the 16-block grid");
        assertEquals(2, s.maxMinutes(), "at least 2 minutes (a warning comes a minute before)");
        assertEquals(2, warns.size(), warns.toString());
    }

    @Test
    void aJunkSwitchFailsClosed() {
        List<String> warns = new ArrayList<>();
        ClubhouseSettings s = read(Map.of("enabled", "perhaps"), warns);
        assertFalse(s.enabled(), "the only reason to touch a switch is to turn something off");
        assertTrue(warns.contains("INVALID"), "and it closes the Clubhouse, as every games switch does: " + warns);
        assertFalse(read(Map.of("enabled", false), new ArrayList<>()).enabled(), "off is off");
    }
}
