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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The schema's migrations as a server upgrade runs them.
 *
 * <p>Pinned here: every migration splits on {@code ;} into whole statements (a {@code ;} inside a
 * statement, a default or a comment would cut it in two, and the half would fail on a live server);
 * v36 (Race Night and the Weekly Cup, EVENTS-DROPPER-SPEC §A.9, EVENTS-OWNER-DECISIONS D2) applies on
 * top of a v35 database without touching its rows, and its keys refuse a second entry, race row or
 * settlement, which is what makes each of those once-only; v37 (the wardrobe) and v38 (the trade log,
 * SHOP-V2-SPEC §3.2, §4.12) apply on an empty and on a v36 database, and leave 0.37's own
 * trail queries giving the same answers; v39 (checkpoint splits, 0.38.4) is the newest and applies on a v38
 * database without touching its rows; and a block lands whole with its version or not at all (D39).
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
    void v36ComesAfterFreshCoursesArchive() {
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

        Database.openAt(conn, Logger.getAnonymousLogger(), 36);
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
        Database.openAt(conn, Logger.getAnonymousLogger(), 36);
        assertEquals(36, version(), "opening again runs nothing twice");
    }

    /**
     * A database a newer HomeCraft wrote (a later build's v40, with this jar rolled back under it): one WARN naming
     * it, no migration, and the stored version stays, so the re-upgrade finds it as it left it (SHOP-V2-SPEC §7.9).
     */
    @Test
    void aNewerDatabaseIsNamedOnceAndLeftAlone() throws SQLException {
        Database.open(conn, Logger.getAnonymousLogger());
        exec("UPDATE hcm_meta SET value = '40' WHERE key = 'schema_version'");
        exec("CREATE TABLE later_things (id INTEGER PRIMARY KEY)");

        List<java.util.logging.LogRecord> records = new ArrayList<>();
        Logger log = Logger.getAnonymousLogger();
        log.setUseParentHandlers(false);
        log.addHandler(new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord r) {
                records.add(r);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        Database.open(conn, log);

        assertEquals(40, version(), "never lowered");
        assertTrue(table("later_things"), "the newer table is left alone");
        assertEquals(1, records.size(), "one line, and no migration ran");
        assertEquals(java.util.logging.Level.WARNING, records.get(0).getLevel());
        assertEquals("This database was written by a newer HomeCraft (schema v40); rows this version doesn't know"
                + " are skipped.", records.get(0).getMessage());
        assertNull(Database.newerSchemaWarning(Database.latestVersion()), "this build's own: nothing");
        assertNull(Database.newerSchemaWarning(0), "an empty database: nothing");
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

    // ---- v37 (the wardrobe) and v38 (the trade log), SHOP-V2-SPEC §3.2, §4.12, §7.8 ------------------------

    private static final List<String> COSMETICS_V36 = List.of("player", "cosmetic_id", "expires_at", "enabled");
    private static final List<String> COSMETICS_V37 = List.of("player", "cosmetic_id", "expires_at", "enabled", "kind",
            "since", "notice_for", "notice_stage", "given_by");
    private static final List<String> TRADES = List.of("id", "a", "a_name", "b", "b_name", "world", "opened_at",
            "closed_at", "outcome", "reason", "a_items", "b_items", "a_summary", "b_summary", "a_money", "b_money",
            "mailbox", "moved", "a_before", "b_before");

    private boolean index(String name) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) == 1;
            }
        }
    }

    @Test
    void v38IsTheNewestAndV37IsTheWardrobe() {
        assertEquals(39, Database.latestVersion(), "v37 the wardrobe, v38 the trade log (SHOP-V2-SPEC §7.8), v39 splits");
        assertTrue(Database.migration(36).contains("cup_settlements"), "v36 is Race Night and the Cup, unchanged");
        String v37 = Database.migration(37);
        for (String column : List.of("kind TEXT NOT NULL DEFAULT 'TRAIL'", "since INTEGER NOT NULL DEFAULT 0",
                "notice_for INTEGER NOT NULL DEFAULT 0", "notice_stage INTEGER NOT NULL DEFAULT 0",
                "given_by TEXT NOT NULL DEFAULT ''")) {
            assertTrue(v37.contains("ALTER TABLE cosmetics_owned ADD COLUMN " + column), "v37 adds " + column);
        }
        assertEquals(5, v37.split(";").length, "five columns and nothing else: no new table");
        String v38 = Database.migration(38);
        assertTrue(v38.contains("CREATE TABLE IF NOT EXISTS trades ("), "v38 makes trades");
        assertTrue(v38.contains("CREATE INDEX IF NOT EXISTS idx_trades_a ON trades (a, closed_at)"));
        assertTrue(v38.contains("CREATE INDEX IF NOT EXISTS idx_trades_b ON trades (b, closed_at)"));
        assertTrue(v38.contains("moved      TEXT    NOT NULL DEFAULT ''"),
                "red-team finding D4: what the swap moved besides the stacks, so a void puts it back");
        assertFalse(v38.contains("checked    INTEGER"),
                "red-team findings AW-1, AW-2: no start reads a finished trade, so nothing marks one read");
        assertTrue(v38.contains("a_before   REAL,") && v38.contains("b_before   REAL"),
                "red-team finding TCR4-3: the balances just before the money moved");
    }

    @Test
    void v37AndV38OnAnEmptyDatabase() throws SQLException {
        Database.open(conn, Logger.getAnonymousLogger());
        assertEquals(39, version());
        assertEquals(COSMETICS_V37, columns("cosmetics_owned"), "§3.2: the five columns after the four of v30");
        assertEquals(TRADES, columns("trades"), "§4.12");
        assertTrue(index("idx_trades_a"));
        assertTrue(index("idx_trades_b"));
        exec("INSERT INTO trades(a, a_name, b, b_name, world, opened_at, closed_at, outcome)"
                + " VALUES('a', 'A', 'b', 'B', 'world', 1, 2, 'CANCELLED')");
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(
                "SELECT id, reason, a_items, b_summary, a_money, mailbox, moved, a_before FROM trades")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getLong(1), "the id counts from 1");
            assertEquals("", rs.getString(2));
            assertEquals("", rs.getString(3));
            assertEquals("", rs.getString(4));
            assertEquals(0.0, rs.getDouble(5));
            assertEquals(0, rs.getInt(6));
            assertEquals("", rs.getString(7), "nothing moved besides the stacks");
            assertEquals(null, rs.getObject(8), "no balance known");
        }
    }

    @Test
    void v37AndV38OnAV36DatabaseKeepEveryTrail() throws SQLException {
        Database.openAt(conn, Logger.getAnonymousLogger(), 36);
        assertEquals(36, version());
        assertEquals(COSMETICS_V36, columns("cosmetics_owned"));
        assertFalse(table("trades"));
        exec("INSERT INTO cosmetics_owned(player, cosmetic_id, expires_at, enabled) VALUES('p', 'trail_hearts', 500, 1)");
        exec("INSERT INTO cosmetics_owned(player, cosmetic_id, expires_at, enabled) VALUES('p', 'trail_leaves_2026', 900, 0)");

        Database.open(conn, Logger.getAnonymousLogger());
        assertEquals(39, version(), "the upgrade ran v37, v38 and v39");
        assertEquals(COSMETICS_V37, columns("cosmetics_owned"));
        assertTrue(table("trades"));
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT cosmetic_id, expires_at,"
                + " enabled, kind, since, notice_for, notice_stage, given_by FROM cosmetics_owned ORDER BY cosmetic_id")) {
            assertTrue(rs.next());
            assertEquals("trail_hearts", rs.getString(1));
            assertEquals(500, rs.getLong(2), "its end is kept");
            assertEquals(1, rs.getInt(3), "still the one switched on");
            assertEquals("TRAIL", rs.getString(4), "every row before v37 is a trail");
            assertEquals(0, rs.getLong(5), "since 0: a row from before v37");
            assertEquals(0, rs.getLong(6));
            assertEquals(0, rs.getInt(7));
            assertEquals("", rs.getString(8));
            assertTrue(rs.next());
            assertEquals("trail_leaves_2026", rs.getString(1));
            assertEquals(900, rs.getLong(2));
            assertEquals("TRAIL", rs.getString(4));
            assertFalse(rs.next());
        }
        Database.open(conn, Logger.getAnonymousLogger());
        assertEquals(39, version(), "opening again runs nothing twice");
    }

    // ---- v39 (checkpoint splits, 0.38.4) -------------------------------------------------------------------

    @Test
    void v39AppliesOnAV38DatabaseAndLeavesItsRowsAlone() throws SQLException {
        Database.openAt(conn, Logger.getAnonymousLogger(), 38);
        assertEquals(38, version(), "a database as 0.38.3 left it");
        assertFalse(table("game_splits"), "no splits yet");
        exec("INSERT INTO trades(a, a_name, b, b_name, world, opened_at, closed_at, outcome)"
                + " VALUES('a', 'A', 'b', 'B', 'world', 1, 2, 'CANCELLED')");
        exec("INSERT INTO game_scores(player, game, board, score, at, runs) VALUES('p', 'trials', 'course:x', 61000, 1, 1)");

        Database.open(conn, Logger.getAnonymousLogger());
        assertEquals(39, version(), "the upgrade ran v39");
        assertEquals(List.of("player", "course", "layout", "times", "at"), columns("game_splits"));
        assertTrue(Database.migration(39).contains("PRIMARY KEY (player, course, layout)"),
                "one row per player, course and layout: a Fresh course's next edition is another row");
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT outcome FROM trades")) {
            assertTrue(rs.next(), "the trade is still there");
            assertEquals("CANCELLED", rs.getString(1), "unchanged");
        }
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT score FROM game_scores")) {
            assertTrue(rs.next(), "the score is still there");
            assertEquals(61000, rs.getLong(1), "unchanged");
        }
        Database.open(conn, Logger.getAnonymousLogger());
        assertEquals(39, version(), "opening again runs nothing twice");
    }

    /** What 0.37's own trail queries answer for {@code player}, in their exact shapes (PrizeDao at 0.37.2). */
    private static List<String> answers037(Connection c, String player, List<String> trails, long now)
            throws SQLException {
        List<String> out = new ArrayList<>();
        for (String id : trails) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT expires_at FROM cosmetics_owned WHERE player=? AND cosmetic_id=?")) {
                ps.setString(1, player);
                ps.setString(2, id);
                try (ResultSet rs = ps.executeQuery()) {
                    out.add("expires " + id + "=" + (rs.next() ? rs.getLong(1) : "none"));
                }
            }
        }
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM cosmetics_owned WHERE player=? AND enabled=1 AND expires_at > ? LIMIT 1")) {
            ps.setString(1, player);
            ps.setLong(2, now);
            try (ResultSet rs = ps.executeQuery()) {
                out.add("any on=" + rs.next());
            }
        }
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT cosmetic_id, expires_at, enabled FROM cosmetics_owned WHERE player=? ORDER BY cosmetic_id")) {
            ps.setString(1, player);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (trails.contains(rs.getString(1))) { // 0.37's TrailService keeps only its trail rows' ids
                        out.add("row " + rs.getString(1) + " " + rs.getLong(2) + " " + rs.getInt(3));
                    }
                }
            }
        }
        return out;
    }

    /** 0.37's {@code enableOnly(player, null)}, in its exact shape. */
    private static void allOff037(Connection c, String player) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE cosmetics_owned SET enabled = 0 WHERE player=?")) {
            ps.setString(1, player);
            ps.executeUpdate();
        }
    }

    /**
     * [X] A v37 {@code cosmetics_owned} read with 0.37's exact SQL shapes gives the same answers for trails with HAT
     * rows present (SHOP-V2-SPEC §9 C0, §7.9): a rollback to 0.37.3 on a v38 database sees its trails as it left
     * them, because a HAT row's {@code enabled} is always 0 and its id is never a trail row's.
     */
    @Test
    void zeroThirtySevensQueriesGiveTheSameAnswersWithHatRowsThere() throws SQLException {
        long now = 1_000;
        java.util.UUID player = java.util.UUID.randomUUID();
        String p = player.toString();
        List<String> trails = List.of("trail_hearts", "trail_notes", "trail_leaves_2026");
        String seed = "INSERT INTO cosmetics_owned(player, cosmetic_id, expires_at, enabled) VALUES"
                + " ('" + p + "', 'trail_hearts', 5000, 1), ('" + p + "', 'trail_notes', 500, 0),"
                + " ('" + p + "', 'trail_leaves_2026', 9000, 0)";

        Database.openAt(conn, Logger.getAnonymousLogger(), 36);
        exec(seed);
        List<String> before = answers037(conn, p, trails, now);
        allOff037(conn, p);
        List<String> beforeOff = answers037(conn, p, trails, now);

        try (Connection v38 = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            LookDao looks = new LookDao(Database.open(v38, Logger.getAnonymousLogger()));
            try (Statement st = v38.createStatement()) {
                st.execute(seed);
            }
            looks.write(player, "hat_creeper", LookDao.HAT, 99_000, now, "");
            looks.write(player, "cap_pink", LookDao.HAT, 400, 100, "");
            assertEquals(before, answers037(v38, p, trails, now), "the same trails, ends and switch");
            allOff037(v38, p);
            assertEquals(beforeOff, answers037(v38, p, trails, now), "0.37's enableOnly(null) as before");
            try (Statement st = v38.createStatement(); ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM cosmetics_owned WHERE kind='HAT' AND enabled=0")) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1), "the hats' rights are still there, switched off");
            }
        }
    }

    /**
     * [X] A block lands whole with its version, or not at all (D39, red team D-m13): a statement failing after an
     * ALTER rolls the ALTER back and leaves the version where it was, so the next start runs the block again from
     * the start instead of refusing with "duplicate column name".
     */
    @Test
    void aFailingBlockLeavesNoHalf() throws SQLException {
        Database db = Database.openAt(conn, Logger.getAnonymousLogger(), 36);
        assertThrows(SQLException.class, () -> db.apply(37,
                "ALTER TABLE cosmetics_owned ADD COLUMN kind TEXT NOT NULL DEFAULT 'TRAIL';"
                        + "ALTER TABLE cosmetics_owned ADD COLUMN since INTEGER NOT NULL DEFAULT 0;"
                        + "ALTER TABLE no_such_table ADD COLUMN x INTEGER"));
        assertEquals(COSMETICS_V36, columns("cosmetics_owned"), "neither ALTER stayed");
        assertEquals(36, version(), "the version wasn't moved");
        assertTrue(conn.getAutoCommit(), "the connection is back to normal");

        Database.open(conn, Logger.getAnonymousLogger());
        assertEquals(39, version(), "the real v37, v38 and v39 run cleanly afterwards");
        assertEquals(COSMETICS_V37, columns("cosmetics_owned"));
    }

    /** [X] The same on the real upgrade path: v37 failing part-way through leaves v36 exactly as it was. */
    @Test
    void aV37ThatFailsPartWayLeavesTheV36DatabaseAsItWas() throws SQLException {
        Database.openAt(conn, Logger.getAnonymousLogger(), 36);
        exec("INSERT INTO cosmetics_owned(player, cosmetic_id, expires_at, enabled) VALUES('p', 'trail_hearts', 500, 1)");
        // a column of that name already there makes v37's fourth ALTER fail ("duplicate column name")
        exec("ALTER TABLE cosmetics_owned ADD COLUMN notice_stage INTEGER NOT NULL DEFAULT 0");
        List<String> was = columns("cosmetics_owned");
        assertThrows(SQLException.class, () -> Database.open(conn, Logger.getAnonymousLogger()));
        assertEquals(was, columns("cosmetics_owned"), "kind, since and notice_for were rolled back with it");
        assertEquals(36, version());
        assertFalse(table("trades"), "and v38 never ran");
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(
                "SELECT expires_at, enabled FROM cosmetics_owned WHERE cosmetic_id='trail_hearts'")) {
            assertTrue(rs.next(), "the trail is still there");
            assertEquals(500, rs.getLong(1));
            assertEquals(1, rs.getInt(2));
        }
    }
}
