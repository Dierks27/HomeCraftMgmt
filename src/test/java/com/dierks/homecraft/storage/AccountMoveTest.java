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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /hcm account move} (SHOP-V2-SPEC §3.17, D40) against a real SQLite: every listed table moves in one
 * transaction, an overlap refuses and changes nothing, the Crown keeps its number, the tokens add up with one ledger
 * line each side, and every table in the schema with a player or owner column is either moved or named as not moved.
 */
class AccountMoveTest {

    private Connection conn;
    private Database db;
    private AccountMoveDao moves;
    private final UUID from = UUID.fromString("0b0a0c0d-1e1f-4a2b-8c3d-4e5f6a7b8c9d");
    private final UUID to = UUID.fromString("1c2d3e4f-5a6b-4c7d-8e9f-0a1b2c3d4e5f");

    @BeforeEach
    void open() throws Exception {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        db = Database.open(conn, Logger.getAnonymousLogger());
        moves = new AccountMoveDao(db);
        String f = from.toString();
        String t = to.toString();
        exec("INSERT INTO arcade_tokens(player, tokens, streak, last_streak_day, playtime_tokens) VALUES(?,120,5,20000,30)", f);
        exec("INSERT INTO arcade_tokens(player, tokens, streak, last_streak_day, playtime_tokens) VALUES(?,10,2,20010,3)", t);
        exec("INSERT INTO token_ledger(player, delta, balance_after, source, detail, at) VALUES(?,100,100,'QUEST','a',1)", f);
        exec("INSERT INTO token_ledger(player, delta, balance_after, source, detail, at) VALUES(?,20,120,'LOGIN_STREAK','b',2)", f);
        exec("INSERT INTO prize_purchases(player, prize_id, period_key, count) VALUES(?,'ball_pink','life',1)", f);
        exec("INSERT INTO prize_purchases(player, prize_id, period_key, count) VALUES(?,'speed_boost','d20000',2)", f);
        exec("INSERT INTO prize_purchases(player, prize_id, period_key, count) VALUES(?,'speed_boost','d20000',1)", t);
        exec("INSERT INTO cosmetics_owned(player, cosmetic_id, expires_at, enabled, kind) VALUES(?,'trail_snow',99,1,'TRAIL')", f);
        exec("INSERT INTO cosmetics_owned(player, cosmetic_id, expires_at, enabled, kind) VALUES(?,'cap_lime',99,0,'HAT')", f);
        exec("INSERT INTO cosmetics_owned(player, cosmetic_id, expires_at, enabled, kind) VALUES(?,'trail_hearts',99,1,'TRAIL')", t);
        exec("INSERT INTO arcade_state(key, value) VALUES(?,7)", "keeper_no:keeper_legend_crown:" + f);
        exec("INSERT INTO game_prefs(player, pref, value) VALUES(?,'golf.ball','ball:ball_pink')", f);
        exec("INSERT INTO game_prefs(player, pref, value) VALUES(?,'golf.ball','white')", t);
        exec("INSERT INTO achievements_unlocked(player, achievement, unlocked_at) VALUES(?,'first_crate',100)", f);
        exec("INSERT INTO achievements_unlocked(player, achievement, unlocked_at) VALUES(?,'jackpot',200)", f);
        exec("INSERT INTO achievements_unlocked(player, achievement, unlocked_at) VALUES(?,'first_crate',50)", t);
        exec("INSERT INTO quest_progress(player, quest_id, period_key, progress, claimed, stat_mark) VALUES(?,'q1','p1',3,0,900)", f);
        exec("INSERT INTO quest_progress(player, quest_id, period_key, progress, claimed, stat_mark) VALUES(?,'q1','p1',5,1,40)", t);
        exec("INSERT INTO quest_progress(player, quest_id, period_key, progress, claimed, stat_mark) VALUES(?,'q2','p1',2,0,900)", f);
        exec("INSERT INTO player_counters(player, counter, value) VALUES(?,'candy',10)", f);
        exec("INSERT INTO player_counters(player, counter, value) VALUES(?,'candy',4)", t);
        exec("INSERT INTO binder_cards(player, card_id, count) VALUES(?,'c1',2)", f);
        exec("INSERT INTO binder_cards(player, card_id, count) VALUES(?,'c1',1)", t);
        exec("INSERT INTO mini_individuals(uid, mini_id, mint_number, owner, minted_at) VALUES('u1','m',1,?,1)", f);
        exec("INSERT INTO mini_individuals(uid, mini_id, mint_number, owner, minted_at) VALUES('u2','m',2,?,1)", f);
    }

    @AfterEach
    void close() throws Exception {
        conn.close();
    }

    private void exec(String sql, String arg) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, arg);
            ps.executeUpdate();
        }
    }

    private long one(String sql, String arg) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, arg);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        }
    }

    /** Every row of every moved table, as text: a before/after picture. */
    private List<String> snapshot() throws SQLException {
        List<String> out = new ArrayList<>();
        for (String table : AccountMoveDao.MOVED) {
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM " + table)) {
                int n = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    StringBuilder sb = new StringBuilder(table);
                    for (int i = 1; i <= n; i++) {
                        sb.append('|').append(rs.getString(i));
                    }
                    out.add(sb.toString());
                }
            }
        }
        out.sort(null);
        return out;
    }

    @Test
    void everyListedTableMovesInOneTransaction() throws Exception {
        String f = from.toString();
        String t = to.toString();
        AccountMoveDao.Plan plan = moves.move(from, to, 5_000, "Old", "New");
        assertFalse(plan.refused(), plan.overlap().toString());
        assertEquals(1, one("SELECT count FROM prize_purchases WHERE player=? AND prize_id='ball_pink'", t),
                "her golf ball");
        assertEquals(3, one("SELECT count FROM prize_purchases WHERE player=? AND prize_id='speed_boost'", t),
                "a limit period both used counts both");
        assertEquals(0, one("SELECT COUNT(*) FROM prize_purchases WHERE player=?", f));
        assertEquals(3, one("SELECT COUNT(*) FROM cosmetics_owned WHERE player=?", t), "her looks");
        assertEquals(1, one("SELECT COUNT(*) FROM cosmetics_owned WHERE player=? AND enabled=1", t),
                "one trail on at a time: the new account's stays the one on");
        assertEquals(0, one("SELECT COUNT(*) FROM cosmetics_owned WHERE player=?", f));
        assertEquals("ball:ball_pink", text("SELECT value FROM game_prefs WHERE player=? AND pref='golf.ball'", t),
                "the old account's setting wins");
        assertEquals(50, one("SELECT unlocked_at FROM achievements_unlocked WHERE player=? AND achievement='first_crate'", t),
                "the earlier unlock stays");
        assertEquals(2, one("SELECT COUNT(*) FROM achievements_unlocked WHERE player=?", t));
        assertEquals(5, one("SELECT progress FROM quest_progress WHERE player=? AND quest_id='q1'", t), "the larger");
        assertEquals(1, one("SELECT claimed FROM quest_progress WHERE player=? AND quest_id='q1'", t),
                "claimed by either: never paid twice");
        assertEquals(-1, one("SELECT stat_mark FROM quest_progress WHERE player=? AND quest_id='q2'", t),
                "a moved statistic quest watches the new account's own statistic from now");
        assertEquals(10, one("SELECT value FROM player_counters WHERE player=? AND counter='candy'", t));
        assertEquals(3, one("SELECT count FROM binder_cards WHERE player=? AND card_id='c1'", t),
                "Cards are real things: both binders add up");
        assertEquals(2, one("SELECT COUNT(*) FROM mini_individuals WHERE owner=?", t), "every Mini she owns");
        for (String table : List.of("game_prefs", "achievements_unlocked", "quest_progress", "player_counters",
                "binder_cards")) {
            assertEquals(0, one("SELECT COUNT(*) FROM " + table + " WHERE player=?", f), table + ": nothing left behind");
        }
        assertEquals(0, one("SELECT COUNT(*) FROM mini_individuals WHERE owner=?", f));
    }

    private String text(String sql, String arg) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, arg);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    @Test
    void aFailureHalfwayMovesNothing() throws Exception {
        List<String> before = snapshot();
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TRIGGER no_minis BEFORE UPDATE ON mini_individuals BEGIN SELECT RAISE(ABORT, 'boom'); END");
        }
        assertThrows(SQLException.class, () -> moves.move(from, to, 5_000, "Old", "New"),
                "the last table fails, after every other has moved");
        assertEquals(before, snapshot(), "one transaction: not a token, a look or a ledger line moved");
    }

    @Test
    void anOverlapRefusesAndChangesNothing() throws Exception {
        exec("INSERT INTO prize_purchases(player, prize_id, period_key, count) VALUES(?,'ball_pink','life',1)",
                to.toString());
        List<String> before = snapshot();
        AccountMoveDao.Plan plan = moves.move(from, to, 5_000, "Old", "New");
        assertTrue(plan.refused());
        assertTrue(plan.overlap().get(0).contains("ball_pink"), plan.overlap().toString());
        assertEquals(before, snapshot(), "refused: nothing changes");

        AccountMoveDao.Plan same = moves.plan(from, from);
        assertTrue(same.refused(), "an account can't move onto itself");
    }

    @Test
    void aLookTheyBothHaveOrAMiniOrAKeeperNumberRefuses() throws Exception {
        exec("INSERT INTO cosmetics_owned(player, cosmetic_id, expires_at, enabled, kind) VALUES(?,'cap_lime',5,0,'HAT')",
                to.toString());
        assertTrue(moves.plan(from, to).overlap().stream().anyMatch(s -> s.contains("cap_lime")));
        exec("DELETE FROM cosmetics_owned WHERE player=? AND cosmetic_id='cap_lime'", to.toString());
        exec("INSERT INTO arcade_state(key, value) VALUES(?,3)", "keeper_no:keeper_legend_crown:" + to);
        assertTrue(moves.plan(from, to).overlap().stream().anyMatch(s -> s.contains("keeper_legend_crown")));
        exec("DELETE FROM arcade_state WHERE key=?", "keeper_no:keeper_legend_crown:" + to);
        exec("INSERT INTO mini_individuals(uid, mini_id, mint_number, owner, minted_at) VALUES('u9','m',9,?,1)",
                to.toString());
        assertTrue(moves.plan(from, to).overlap().stream().anyMatch(s -> s.contains("Mini")));
        // red-team finding CMD-2: Minis traded over before the move are why it refuses; the line says the way out
        assertTrue(moves.plan(from, to).overlap().contains("the new account owns 1 Mini already (trade them back to"
                + " the old account, move, then trade them over)"), moves.plan(from, to).overlap().toString());
    }

    @Test
    void theCrownKeepsItsNumber() throws Exception {
        moves.move(from, to, 5_000, "Old", "New");
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT key, value FROM arcade_state WHERE key LIKE 'keeper_no:%'")) {
            assertTrue(rs.next());
            assertEquals("keeper_no:keeper_legend_crown:" + to, rs.getString(1));
            assertEquals(7, rs.getLong(2), "No. 7 on the new account too");
            assertFalse(rs.next(), "and none left on the old one");
        }
    }

    @Test
    void tokensAddUpAndTheLedgerGainsOneLineEachSide() throws Exception {
        String f = from.toString();
        String t = to.toString();
        AccountMoveDao.Plan plan = moves.move(from, to, 5_000, "Old", "New");
        assertEquals(120, plan.tokens());
        assertEquals(130, one("SELECT tokens FROM arcade_tokens WHERE player=?", t), "10 + 120");
        assertEquals(5, one("SELECT streak FROM arcade_tokens WHERE player=?", t), "the larger streak");
        assertEquals(20010, one("SELECT last_streak_day FROM arcade_tokens WHERE player=?", t));
        assertEquals(3, one("SELECT playtime_tokens FROM arcade_tokens WHERE player=?", t),
                "playtime is paid against each account's own statistic: it doesn't move");
        assertEquals(0, one("SELECT tokens FROM arcade_tokens WHERE player=?", f), "the old account is emptied");
        assertEquals(30, one("SELECT playtime_tokens FROM arcade_tokens WHERE player=?", f),
                "and keeps what its playtime was paid, so it is never paid again");
        assertEquals(20000, one("SELECT last_streak_day FROM arcade_tokens WHERE player=?", f),
                "and today's streak claim, so it is never claimed twice");
        assertEquals(1, one("SELECT COUNT(*) FROM token_ledger WHERE player=?", f), "one ADMIN line on the old side");
        assertEquals(-120, one("SELECT delta FROM token_ledger WHERE player=? AND source='ADMIN'", f));
        assertEquals(3, one("SELECT COUNT(*) FROM token_ledger WHERE player=?", t), "the history moved, plus one line");
        assertEquals(130, one("SELECT balance_after FROM token_ledger WHERE player=? AND source='ADMIN'", t));
    }

    @Test
    void everyTableWithAPlayerOrOwnerColumnIsMovedOrNamed() throws Exception {
        Set<String> tables = new TreeSet<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        Set<String> listed = new HashSet<>(AccountMoveDao.MOVED);
        for (String moved : AccountMoveDao.MOVED) {
            assertFalse(AccountMoveDao.NOT_MOVED.containsKey(moved), moved + " is in both lists");
        }
        listed.addAll(AccountMoveDao.NOT_MOVED.keySet());
        for (String t : listed) {
            assertTrue(tables.contains(t), t + " is listed but isn't a table");
        }
        List<String> missing = new ArrayList<>();
        for (String table : tables) {
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
                while (rs.next()) {
                    String col = rs.getString("name").toLowerCase(Locale.ROOT);
                    if ((col.contains("player") || col.contains("owner")) && !listed.contains(table)) {
                        missing.add(table + "." + col);
                    }
                }
            }
        }
        assertTrue(missing.isEmpty(), "a table with a player or owner column must be moved or named as not moved "
                + "(AccountMoveDao.MOVED / NOT_MOVED): " + missing);
        assertTrue(listed.contains("arcade_state"), "the Keeper numbers live in arcade_state");
    }
}
