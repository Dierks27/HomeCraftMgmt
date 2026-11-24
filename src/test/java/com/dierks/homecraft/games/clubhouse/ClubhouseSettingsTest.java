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
        assertTrue(d.soloFirst() && d.soloAfter() && d.boards(), "0.38.3: through it, back to it, and its boards");
        assertEquals(5, d.firstSeconds(), "on to the course after 5 seconds");
        assertEquals(new ClubhouseSettings(true, ClubhouseSettings.ORIGIN, 30, true, true, true), d,
                "0.38.2's six settings read 0.38.3's as shipped");
        assertEquals(32, d.box().sizeX(), "32 wide");
        assertEquals(16, d.box().sizeY(), "16 high");
        assertEquals(62, d.box().sizeZ(), "plan 13: 62 deep, the side rooms' wing in its last 30 rows (48 before)");
        assertEquals(32, d.mainBox().sizeZ(), "the main room is 0.40.0's 32");
        assertEquals(d.box().minZ() + 32, d.annexBox().minZ(), "the side rooms south of it");
        assertEquals(16, d.annexBox().sizeZ(), "0.41.0's 16 rows, claimed as they were");
        assertEquals(d.annexBox().maxZ() + 1, d.wingBox().minZ(), "plan 13's 14 rows right after them");
        assertEquals(14, d.wingBox().sizeZ());
        assertEquals(d.box().maxZ(), d.wingBox().maxZ(), "to the box's south end");
        assertEquals(48, d.oldBox().sizeZ(), "the box at its old size: the main room and the first 16 rows");
        assertEquals(d.box().minZ(), d.oldBox().minZ());
        assertEquals(d.annexBox().maxZ(), d.oldBox().maxZ());
        assertTrue(d.arcadeRoom() && d.trophyRoom(), "0.41.0: both side rooms ship on");
        assertEquals(ClubhouseSettings.KID_GAMES, d.arcadeGames(), "the kid set");
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
    void theSoloSwitchesAndTheWaitAreReadOverTheShippedValues() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("solo_first", false);
        m.put("solo_after", false);
        m.put("first_seconds", 12);
        m.put("boards", false);
        List<String> warns = new ArrayList<>();
        ClubhouseSettings s = read(m, warns);
        assertFalse(s.soloFirst() || s.soloAfter() || s.boards(), "each switch reads off");
        assertEquals(12, s.firstSeconds(), "12 seconds");
        assertEquals(List.of(), warns, "without a WARN");
        assertEquals(1, read(Map.of("first_seconds", 0), new ArrayList<>()).firstSeconds(), "at least 1 second");
        assertEquals(30, read(Map.of("first_seconds", 300), new ArrayList<>()).firstSeconds(), "at most 30");
        List<String> junk = new ArrayList<>();
        ClubhouseSettings j = read(Map.of("solo_first", "maybe"), junk);
        assertTrue(j.soloFirst(), "a junk switch here is its shipped value: the Clubhouse stays open");
        assertFalse(junk.contains("INVALID"), junk.toString());
    }

    @Test
    void aJunkSwitchFailsClosed() {
        List<String> warns = new ArrayList<>();
        ClubhouseSettings s = read(Map.of("enabled", "perhaps"), warns);
        assertFalse(s.enabled(), "the only reason to touch a switch is to turn something off");
        assertTrue(warns.contains("INVALID"), "and it closes the Clubhouse, as every games switch does: " + warns);
        assertFalse(read(Map.of("enabled", false), new ArrayList<>()).enabled(), "off is off");
    }

    /**
     * 0.41.0: {@code arcade_room.enabled}, {@code arcade_room.games} (lower-cased, each once, at most nine with one
     * WARN for the rest) and {@code trophy_room.enabled} are read from config; a game of chance in the list is the
     * room's to refuse ({@link KidSetTest}).
     */
    @Test
    void theSideRoomsAreReadFromConfig() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("arcade_room", Map.of("enabled", false, "games", List.of("Snake", "snake", "simon_says", "ore_slots",
                "mini_match", "whack_a_zombie", "tic_tac_toe", "connect_four", "creeper_sweeper", "ore_merge",
                "wheel", "coin_flip", "twenty_one", "higher_lower", "farm_slots")));
        m.put("trophy_room", Map.of("enabled", false));
        List<String> warns = new ArrayList<>();
        ClubhouseSettings s = read(m, warns);
        assertFalse(s.arcadeRoom());
        assertFalse(s.trophyRoom());
        assertEquals(13, ClubhousePlanner.CABINETS, "plan 13: nine places and four spares");
        assertEquals(List.of("snake", "simon_says", "ore_slots", "mini_match", "whack_a_zombie", "tic_tac_toe",
                "connect_four", "creeper_sweeper", "ore_merge", "wheel", "coin_flip", "twenty_one", "higher_lower"),
                s.arcadeGames(), "in order, once each, the first thirteen");
        assertEquals(1, warns.size(), "one WARN for the fourteenth: " + warns);
        assertTrue(warns.get(0).contains("games.clubhouse.arcade_room.games"), warns.get(0));
        assertEquals(new ClubhousePlanner.Rooms(false, false), s.rooms());
        assertTrue(ClubhouseSettings.defaults().withRooms(true, false).rooms().arcade());
    }
}
