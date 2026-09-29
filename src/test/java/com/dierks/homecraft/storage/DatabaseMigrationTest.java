package com.dierks.homecraft.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The schema's migrations as a server upgrade runs them.
 *
 * <p>Pinned here: every migration splits on {@code ;} into whole statements (a {@code ;} inside a
 * statement, a default or a comment would cut it in two, and the half would fail on a live server);
 * v36 (Race Night and the Weekly Cup, EVENTS-DROPPER-SPEC §A.9, EVENTS-OWNER-DECISIONS D2) is the
 * newest and applies on top of a v35 database without touching its rows; and its keys refuse a second
 * entry, race row or settlement, which is what makes each of those once-only.
 */
class DatabaseMigrationTest {

    private Connection conn;

    @BeforeEach
    void open() throws SQLException {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
    }

    @AfterEach
    void close() throws SQLException {
        conn.close();
    }

    private int version() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT value FROM hcm_meta WHERE key = 'schema_version'")) {
            return rs.next() ? Integer.parseInt(rs.getString(1)) : 0;
        }
    }

    private boolean table(String name) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) == 1;
            }
        }
    }

    private List<String> columns(String table) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                out.add(rs.getString("name"));
            }
        }
        return out;
    }

    private void exec(String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    @Test
    void everyMigrationSplitsIntoWholeStatements() {
        for (int v = 1; v <= Database.latestVersion(); v++) {
            for (String stmt : Database.migration(v).split(";")) {
                if (stmt.isBlank()) {
                    continue;
                }
                String s = stmt.strip();
                String head = s.split("\\s+")[0].toUpperCase(Locale.ROOT);
                assertTrue(List.of("CREATE", "INSERT", "UPDATE", "DELETE", "ALTER", "DROP").contains(head),
                        "v" + v + ": every piece starts a statement, not \"" + s.substring(0, Math.min(40, s.length()))
                                + "\" (a ';' inside a statement splits it)");
                long open = s.chars().filter(ch -> ch == '(').count();
                long shut = s.chars().filter(ch -> ch == ')').count();
                assertEquals(open, shut, "v" + v + ": its brackets close inside the piece");
                assertEquals(0, s.chars().filter(ch -> ch == '\'').count() % 2,
                        "v" + v + ": its quotes close inside the piece");
            }
        }
    }

    @Test
    void v36IsTheNewestAndComesAfterFreshCoursesArchive() {
        assertEquals(36, Database.latestVersion(), "v36 is this batch's migration (after gen-keep's v35)");
        assertTrue(Database.migration(35).contains("gen_editions"), "v35 is Fresh Courses' archive, unchanged");
        String v36 = Database.migration(36);
        for (String t : List.of("game_events", "game_event_entries", "game_event_races", "cup_entries",
                "cup_settlements")) {
            assertTrue(v36.contains("CREATE TABLE IF NOT EXISTS " + t + " "), "v36 makes " + t);
        }
    }

    @Test
    void v36AppliesOnAV35DatabaseAndLeavesItsRowsAlone() throws SQLException {
        Database.openAt(conn, Logger.getAnonymousLogger(), 35);
        assertEquals(35, version(), "a database as the last build left it");
        assertFalse(table("game_events"), "no Race Night tables yet");
        assertFalse(table("cup_entries"), "no Cup tables yet");
        exec("INSERT INTO gen_editions(slot, edition, code, seq, day, seed, algo, kind, name, starts_at, built_at)"
                + " VALUES('fresh_parkour', '7:40', 'PARK-1', 1, 20000, 1, 'parkour/1', 'parkour', 'Parkour', 5, 5)");
        exec("INSERT INTO game_scores(player, game, board, score, at, runs) VALUES('p', 'trials', 'course:x', 61000, 1, 1)");

        Database.open(conn, Logger.getAnonymousLogger());
        assertEquals(36, version(), "the upgrade ran v36");
        for (String t : List.of("game_events", "game_event_entries", "game_event_races", "cup_entries",
                "cup_settlements")) {
            assertTrue(table(t), t + " exists");
        }
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT code FROM gen_editions WHERE slot = 'fresh_parkour'")) {
            assertTrue(rs.next(), "the archive row is still there");
            assertEquals("PARK-1", rs.getString(1), "unchanged");
        }
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT score FROM game_scores")) {
            assertTrue(rs.next(), "the score is still there");
            assertEquals(61000, rs.getLong(1), "unchanged");
        }
        Database.open(conn, Logger.getAnonymousLogger());
        assertEquals(36, version(), "opening again runs nothing twice");
    }

    @Test
    void theNewTablesHaveTheColumnsTheSpecsName() throws SQLException {
        Database.open(conn, Logger.getAnonymousLogger());
        assertEquals(List.of("id", "course", "join_at", "starts_at", "state", "settings", "races_done", "prized",
                "week", "made_by", "created_at", "ended_at", "note"), columns("game_events"), "§A.9 game_events");
        assertEquals(List.of("event_id", "player", "name", "joined_at", "status", "points", "place", "prize",
                "paid_at"), columns("game_event_entries"), "§A.9 game_event_entries");
        assertEquals(List.of("event_id", "race", "player", "place", "ms", "targets", "points", "result"),
                columns("game_event_races"), "§A.9 game_event_races");
        assertEquals(List.of("course", "week", "player", "paid", "entered_at", "best_ms", "best_at"),
                columns("cup_entries"), "the Cup's entries, with best_at for the tie-break");
        assertEquals(List.of("course", "week", "settled_at", "outcome", "pool", "payouts"),
                columns("cup_settlements"), "the Cup's settlements, with the plan's outcome");
    }

    @Test
    void theKeysMakeEntriesRacesAndSettlementsOnceOnly() throws SQLException {
        Database.open(conn, Logger.getAnonymousLogger());
        exec("INSERT INTO cup_entries(course, week, player, paid, entered_at) VALUES('river_run', 20724, 'p', 5, 1)");
        assertThrows(SQLException.class, () -> exec("INSERT INTO cup_entries(course, week, player, paid, entered_at)"
                + " VALUES('river_run', 20724, 'p', 5, 2)"), "one entry per player per course per week");
        exec("INSERT INTO cup_entries(course, week, player, paid, entered_at) VALUES('river_run', 20731, 'p', 5, 3)");
        exec("INSERT INTO cup_settlements(course, week, settled_at, outcome, pool, payouts)"
                + " VALUES('river_run', 20724, 9, 'REFUND_ALONE', 5, '[]')");
        assertThrows(SQLException.class, () -> exec("INSERT INTO cup_settlements(course, week, settled_at, outcome,"
                + " pool, payouts) VALUES('river_run', 20724, 10, 'PRIZES', 5, '[]')"), "a Cup is settled once");
        exec("INSERT INTO game_events(id, course, join_at, starts_at, state, settings, created_at)"
                + " VALUES('rn-20261002-1900', 'fresh_boat', 1, 2, 'OPEN', 'races=3', 1)");
        exec("INSERT INTO game_event_entries(event_id, player, name, joined_at, status)"
                + " VALUES('rn-20261002-1900', 'p', 'Sam', 1, 'IN')");
        assertThrows(SQLException.class, () -> exec("INSERT INTO game_event_entries(event_id, player, name, joined_at,"
                + " status) VALUES('rn-20261002-1900', 'p', 'Sam', 2, 'IN')"), "one entry per racer per night");
        exec("INSERT INTO game_event_races(event_id, race, player, points, result)"
                + " VALUES('rn-20261002-1900', 1, 'p', 10, 'FINISHED')");
        assertThrows(SQLException.class, () -> exec("INSERT INTO game_event_races(event_id, race, player, points,"
                + " result) VALUES('rn-20261002-1900', 1, 'p', 10, 'FINISHED')"), "one row per racer per race");
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(
                "SELECT races_done, prized, week, made_by, note, ended_at FROM game_events")) {
            assertTrue(rs.next(), "the event row");
            assertEquals(0, rs.getInt(1), "races_done starts at 0");
            assertEquals(0, rs.getInt(2), "not a prize night until it claims a slot");
            assertEquals("", rs.getString(3), "week defaults to ''");
            assertEquals("", rs.getString(4), "made_by defaults to ''");
            assertEquals("", rs.getString(5), "note defaults to ''");
            rs.getLong(6);
            assertTrue(rs.wasNull(), "ended_at is NULL while it runs");
        }
    }
}
