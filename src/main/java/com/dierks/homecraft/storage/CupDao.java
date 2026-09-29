package com.dierks.homecraft.storage;

import com.dierks.homecraft.games.ChanceRounds;
import com.dierks.homecraft.games.cup.CupEntry;
import com.dierks.homecraft.games.cup.CupKey;
import com.dierks.homecraft.games.cup.CupPayout;
import com.dierks.homecraft.games.cup.CupPlan;
import com.dierks.homecraft.games.cup.CupRefusal;
import com.dierks.homecraft.games.cup.CupRules;
import com.dierks.homecraft.games.cup.CupSource;
import com.dierks.homecraft.games.cup.CupText;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The Weekly Cup's tables (schema v36: {@code cup_entries}, {@code cup_settlements}) and its few
 * values in {@code hcm_meta} (EVENTS-OWNER-DECISIONS §D2; the rules are {@code games.cup}).
 *
 * <p><b>Every token the Cup moves lands in ONE transaction with the row that explains it</b>, as in
 * {@link GamesDao}: an entry is the debit ({@code GAMES_CUP_ENTRY}) plus its entry row; a settlement
 * or a void is its settlement row plus every prize ({@code GAMES_CUP_PRIZE}) or refund
 * ({@code GAMES_CUP_REFUND}) plus the line each entrant reads at their next join. The settlement
 * row's primary key makes a Cup settle exactly once: a second call finds it and pays nothing, and a
 * crash before the commit leaves nothing behind, so the next boot settles it whole. Every check is
 * made again inside the transaction: nothing read before it is trusted.
 *
 * <p>The payouts go straight to the balance through {@link TokenDao#change}, never through the skill
 * rewards: it is the players' own pool plus a small fixed top-up, so no daily cap applies (a cap
 * would destroy tokens), and a player who is offline or somewhere that earns nothing is paid all
 * the same.
 *
 * <p><b>{@code hcm_meta}</b> holds, under {@value #META}: each course's Cup switch an admin set
 * ({@code cup.course.<id>} = {@code on}/{@code off}), and the layout each running Cup is raced on
 * ({@code cup.layout.<course>.<week>}, written with the first entry and removed with the
 * settlement). Every method refuses a key outside {@value #META}, so nothing here can touch the
 * schema version or another feature's keys.
 *
 * <p>Runs on the main thread against the plugin's one connection, like every DAO; {@code now} is
 * always the caller's.
 */
public final class CupDao {

    /** Every {@code hcm_meta} key this DAO reads or writes starts with this. */
    public static final String META = "cup.";

    private static final Pattern COLOUR = Pattern.compile("(?i)[&§][0-9a-fk-or]");

    /** The line an entrant reads when their Cup ends, or {@code null} for none. */
    @FunctionalInterface
    public interface Words {
        String line(CupPlan plan, CupPayout line);
    }

    /**
     * A line kept for a player's next join ({@code game_prefs}, the games' queued notices), written in
     * the settlement's own transaction.
     *
     * @param player who reads it
     * @param key    its {@code game_prefs} key (delete it once the player has read it)
     * @param line   what they read, colour codes and all
     */
    public record Notice(UUID player, String key, String line) {
    }

    /** A settlement or void that happened: the plan it paid, and the lines it left for the players. */
    public record Settled(CupPlan plan, List<Notice> notices) {
    }

    /**
     * A settlement row.
     *
     * @param payouts the plan's JSON ({@link CupPlan#json()})
     */
    public record Settlement(CupKey key, long settledAt, CupPlan.Outcome outcome, int pool, String payouts) {
    }

    /** Thrown inside a transaction to roll it back: the plan broke a rule, so nothing may be paid. */
    public static final class Unsound extends RuntimeException {
        public Unsound(String message) {
            super(message);
        }
    }

    private final Database database;
    private final TokenDao tokens;

    public CupDao(Database database) {
        this.database = database;
        this.tokens = new TokenDao(database);
    }

    // ---- entering -------------------------------------------------------------------------------

    /**
     * Enter {@code player} in {@code key}'s Cup, in one transaction: the checks again, the entry
     * debit, the entry row, and (for the Cup's first entrant) the layout it is raced on.
     *
     * @param currentWeek the Cup week now; a key of any other week is refused (a screen opened before
     *                    the rollover and clicked after it)
     * @param layout      the course's layout now ({@code CupLayout.encode()}), kept with the first entry
     * @return {@code null} when they are in, or why not (nothing is written then)
     */
    public CupRefusal enter(CupKey key, UUID player, int fee, String courseName, long now, long currentWeek,
                            String layout) throws SQLException {
        if (fee < CupRules.MIN_ENTRY) {
            return CupRefusal.OFF;
        }
        return database.transaction(c -> {
            if (key.week() != currentWeek) {
                return CupRefusal.WEEK_OVER;
            }
            CupPlan.Outcome settled = settledAs(c, key);
            if (settled != null) {
                return settled == CupPlan.Outcome.VOIDED ? CupRefusal.CALLED_OFF : CupRefusal.WEEK_OVER;
            }
            if (entry(c, key, player) != null) {
                return CupRefusal.ALREADY_IN;
            }
            if (tokens.change(player, -fee, CupSource.GAMES_CUP_ENTRY.name(), plain(CupText.entryDetail(courseName)), now)
                    == TokenDao.REFUSED) {
                return CupRefusal.NOT_ENOUGH_TOKENS;
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO cup_entries(course, week, player, paid, "
                    + "entered_at, best_ms, best_at) VALUES(?,?,?,?,?,NULL,0)")) {
                ps.setString(1, key.course());
                ps.setLong(2, key.week());
                ps.setString(3, player.toString());
                ps.setInt(4, fee);
                ps.setLong(5, now);
                ps.executeUpdate();
            }
            if (layout != null && !layout.isBlank()) {
                try (PreparedStatement ps = c.prepareStatement("INSERT OR IGNORE INTO hcm_meta(key, value) VALUES(?, ?)")) {
                    ps.setString(1, layoutKey(key));
                    ps.setString(2, layout);
                    ps.executeUpdate();
                }
            }
            return null;
        });
    }

    // ---- Cup times ------------------------------------------------------------------------------

    /**
     * A counted run of {@code ms}, finished at {@code finishedAt}: it becomes the player's Cup time in
     * every open Cup they are in on {@code course} whose week is in {@code weeks}
     * ({@link CupRules#runWeeks}), when it is strictly faster than the one they have and STARTED
     * ({@code finishedAt - ms}) at or after they entered: exactly {@code CupEntry.withRun}.
     *
     * @return how many Cup times changed
     */
    public int run(String course, UUID player, long ms, long finishedAt, CupRules.Weeks weeks) throws SQLException {
        if (ms <= 0 || weeks == null || weeks.isEmpty() || course == null) {
            return 0;
        }
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement("UPDATE cup_entries SET best_ms = ?, best_at = ? "
                    + "WHERE course = ? AND player = ? AND week BETWEEN ? AND ? AND entered_at <= ? "
                    + "AND (best_ms IS NULL OR best_ms > ?) AND NOT EXISTS (SELECT 1 FROM cup_settlements s "
                    + "WHERE s.course = cup_entries.course AND s.week = cup_entries.week)")) {
                ps.setLong(1, ms);
                ps.setLong(2, finishedAt);
                ps.setString(3, course);
                ps.setString(4, player.toString());
                ps.setLong(5, weeks.first());
                ps.setLong(6, weeks.last());
                ps.setLong(7, finishedAt - ms);
                ps.setLong(8, ms);
                return ps.executeUpdate();
            }
        }
    }

    // ---- settling -------------------------------------------------------------------------------

    /**
     * Settle {@code key}'s Cup ({@link CupRules#settle}) with a top-up of {@code topup}, in ONE
     * transaction: the entries read inside it, the plan proved sound ({@link CupRules#problems}), the
     * settlement row, every prize or refund, and every entrant's line for their next join.
     *
     * @return what happened, or {@code null} when the Cup was already settled or voided (nothing is
     *         paid then)
     * @throws Unsound when the plan breaks a rule: everything is rolled back and the Cup stays
     *                 unsettled, to be tried again
     */
    public Settled settle(CupKey key, int topup, String courseName, long now, Words words) throws SQLException {
        return close(key, courseName, now, words, entries -> CupRules.settle(key, entries, topup), Math.max(0, topup));
    }

    /**
     * Call {@code key}'s Cup off ({@link CupRules#voided}): every entry back in full, in one
     * transaction, with the reason each entrant reads. The settlement row closes the week: nobody can
     * enter it again, and it is never settled.
     *
     * @return what happened, or {@code null} when it was already settled or voided, or nobody entered
     *         (a Cup nobody is in has nothing to give back and stays open)
     */
    public Settled voidCup(CupKey key, CupPlan.VoidReason reason, String courseName, long now, Words words)
            throws SQLException {
        return close(key, courseName, now, words, entries -> entries.isEmpty() ? null
                : CupRules.voided(key, entries, reason), 0);
    }

    private interface Planner {
        CupPlan plan(List<CupEntry> entries);
    }

    private Settled close(CupKey key, String courseName, long now, Words words, Planner planner, int topup)
            throws SQLException {
        return database.transaction(c -> {
            if (settledAs(c, key) != null) {
                return null;
            }
            List<CupEntry> entries = entries(c, key);
            CupPlan plan = planner.plan(entries);
            if (plan == null) {
                return null;
            }
            List<String> problems = CupRules.problems(entries, topup, plan);
            if (!problems.isEmpty()) {
                throw new Unsound("the Weekly Cup " + key.ref() + " would be paid wrongly: " + String.join("; ", problems));
            }
            // Every word is worked out before the first write, so nothing but SQL runs between the
            // first write and the commit.
            List<Notice> notices = notices(key, plan, words, now);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO cup_settlements(course, week, settled_at, "
                    + "outcome, pool, payouts) VALUES(?,?,?,?,?,?)")) {
                ps.setString(1, key.course());
                ps.setLong(2, key.week());
                ps.setLong(3, now);
                ps.setString(4, plan.outcome().name());
                ps.setInt(5, plan.pool());
                ps.setString(6, plan.json());
                ps.executeUpdate();
            }
            for (CupPayout l : plan.payouts()) {
                String detail = CupText.payoutDetail(l, courseName);
                tokens.change(l.player(), l.tokens(), l.kind().source().name(), plain(detail), now);
            }
            for (Notice n : notices) {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO game_prefs(player, pref, value) "
                        + "VALUES(?,?,?) ON CONFLICT(player, pref) DO UPDATE SET value = excluded.value")) {
                    ps.setString(1, n.player().toString());
                    ps.setString(2, n.key());
                    ps.setString(3, n.line());
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM hcm_meta WHERE key = ?")) {
                ps.setString(1, layoutKey(key));
                ps.executeUpdate();
            }
            return new Settled(plan, notices);
        });
    }

    /**
     * Each entrant's line for {@code plan} ({@code words}), with the {@code game_prefs} key it is kept
     * under: the games' queued-notice prefix, so {@code GamesService} says it at their next join.
     */
    private static List<Notice> notices(CupKey key, CupPlan plan, Words words, long now) {
        List<Notice> out = new ArrayList<>();
        if (words == null) {
            return out;
        }
        for (CupPayout l : plan.lines()) {
            String line = words.line(plan, l);
            if (line == null || line.isBlank()) {
                continue;
            }
            String pref = ChanceRounds.NOTICE + now + "." + key.week() + "."
                    + Integer.toHexString((key.course() + line).hashCode());
            out.add(new Notice(l.player(), pref, line));
        }
        return List.copyOf(out);
    }

    /** Forget a queued line the player has now read ({@link Notice#key()}). */
    public void read(Notice notice) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM game_prefs WHERE player = ? AND pref = ?")) {
                ps.setString(1, notice.player().toString());
                ps.setString(2, notice.key());
                ps.executeUpdate();
            }
        }
    }

    // ---- reading --------------------------------------------------------------------------------

    /** Every Cup with entries and no settlement row (running, or waiting to be settled), oldest first. */
    public List<CupKey> openKeys() throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement("SELECT DISTINCT e.course, e.week FROM cup_entries e "
                    + "WHERE NOT EXISTS (SELECT 1 FROM cup_settlements s WHERE s.course = e.course AND s.week = e.week) "
                    + "ORDER BY e.week, e.course")) {
                List<CupKey> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new CupKey(rs.getString(1), rs.getLong(2)));
                    }
                }
                return out;
            }
        }
    }

    /** {@code key}'s entries, in entry order. */
    public List<CupEntry> entries(CupKey key) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            return entries(c, key);
        }
    }

    /** {@code player}'s entry in {@code key}'s Cup, or {@code null}. */
    public CupEntry entry(CupKey key, UUID player) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            return entry(c, key, player);
        }
    }

    /** How {@code key}'s Cup ended, or {@code null} while it has no settlement row. */
    public CupPlan.Outcome settledAs(CupKey key) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            return settledAs(c, key);
        }
    }

    /** {@code key}'s settlement row, or {@code null}. */
    public Settlement settlement(CupKey key) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement("SELECT settled_at, outcome, pool, payouts FROM "
                    + "cup_settlements WHERE course = ? AND week = ?")) {
                ps.setString(1, key.course());
                ps.setLong(2, key.week());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new Settlement(key, rs.getLong(1), outcome(rs.getString(2)), rs.getInt(3),
                            rs.getString(4)) : null;
                }
            }
        }
    }

    /** The courses' last settlements, newest first ({@code course} null: every course's). */
    public List<Settlement> settlements(String course, int limit) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement("SELECT course, week, settled_at, outcome, pool, payouts "
                    + "FROM cup_settlements" + (course == null ? "" : " WHERE course = ?")
                    + " ORDER BY week DESC, settled_at DESC LIMIT ?")) {
                int i = 1;
                if (course != null) {
                    ps.setString(i++, course);
                }
                ps.setInt(i, Math.max(1, limit));
                List<Settlement> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Settlement(new CupKey(rs.getString(1), rs.getLong(2)), rs.getLong(3),
                                outcome(rs.getString(4)), rs.getInt(5), rs.getString(6)));
                    }
                }
                return out;
            }
        }
    }

    /** The courses whose Cup of {@code week} {@code player} is in. */
    public List<String> entered(UUID player, long week) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT course FROM cup_entries WHERE player = ? AND week = ? ORDER BY entered_at, course")) {
                ps.setString(1, player.toString());
                ps.setLong(2, week);
                List<String> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(rs.getString(1));
                    }
                }
                return out;
            }
        }
    }

    /** {@code player}'s tokens. */
    public int balance(UUID player) throws SQLException {
        return tokens.get(player).tokens();
    }

    // ---- hcm_meta: the per-course switch and the running Cups' layouts --------------------------

    /** The admin's Cup switch for {@code course}: {@code true} on, {@code false} off, {@code null} the default. */
    public Boolean chosen(String course) throws SQLException {
        String v = meta(courseKey(course));
        return v == null ? null : "on".equals(v);
    }

    /** Set the admin's switch for {@code course}; {@code null} forgets it (the default again). */
    public void choose(String course, Boolean on) throws SQLException {
        setMeta(courseKey(course), on == null ? null : on ? "on" : "off");
    }

    /** Every course with an admin's switch, by id. */
    public Map<String, Boolean> chosen() throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            String prefix = META + "course.";
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT key, value FROM hcm_meta WHERE substr(key, 1, ?) = ? ORDER BY key")) {
                ps.setInt(1, prefix.length());
                ps.setString(2, prefix);
                Map<String, Boolean> out = new LinkedHashMap<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.put(rs.getString(1).substring(prefix.length()), "on".equals(rs.getString(2)));
                    }
                }
                return out;
            }
        }
    }

    /** The layout {@code key}'s Cup is raced on ({@code CupLayout.encode()}), or {@code null}. */
    public String layout(CupKey key) throws SQLException {
        return meta(layoutKey(key));
    }

    /** Remember the layout {@code key}'s Cup is raced on (the week's own layout went up). */
    public void layout(CupKey key, String layout) throws SQLException {
        setMeta(layoutKey(key), layout);
    }

    static String courseKey(String course) {
        return META + "course." + course;
    }

    static String layoutKey(CupKey key) {
        return META + "layout." + key.course() + "." + key.week();
    }

    private String meta(String key) throws SQLException {
        check(key);
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement("SELECT value FROM hcm_meta WHERE key = ?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        }
    }

    private void setMeta(String key, String value) throws SQLException {
        check(key);
        Connection c = database.connection();
        synchronized (c) {
            if (value == null) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM hcm_meta WHERE key = ?")) {
                    ps.setString(1, key);
                    ps.executeUpdate();
                }
                return;
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO hcm_meta(key, value) VALUES(?, ?) "
                    + "ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
                ps.setString(1, key);
                ps.setString(2, value);
                ps.executeUpdate();
            }
        }
    }

    /** Refuses any key outside {@value #META}. */
    static void check(String key) {
        if (key == null || !key.startsWith(META) || key.length() == META.length()) {
            throw new IllegalArgumentException("the Weekly Cup only keeps keys under " + META + ": " + key);
        }
    }

    // ---- internals (the caller holds the connection lock) ---------------------------------------

    private static List<CupEntry> entries(Connection c, CupKey key) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT player, paid, entered_at, best_ms, best_at FROM "
                + "cup_entries WHERE course = ? AND week = ? ORDER BY entered_at, player")) {
            ps.setString(1, key.course());
            ps.setLong(2, key.week());
            List<CupEntry> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(entry(rs));
                }
            }
            return out;
        }
    }

    private static CupEntry entry(Connection c, CupKey key, UUID player) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT player, paid, entered_at, best_ms, best_at FROM "
                + "cup_entries WHERE course = ? AND week = ? AND player = ?")) {
            ps.setString(1, key.course());
            ps.setLong(2, key.week());
            ps.setString(3, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? entry(rs) : null;
            }
        }
    }

    /** A row as the rules read it: a NULL Cup time is {@link CupEntry#NO_TIME}. */
    private static CupEntry entry(ResultSet rs) throws SQLException {
        long best = rs.getLong(4);
        if (rs.wasNull()) {
            best = CupEntry.NO_TIME;
        }
        return new CupEntry(UUID.fromString(rs.getString(1)), rs.getInt(2), rs.getLong(3), best, rs.getLong(5));
    }

    private static CupPlan.Outcome settledAs(Connection c, CupKey key) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT outcome FROM cup_settlements WHERE course = ? AND week = ?")) {
            ps.setString(1, key.course());
            ps.setLong(2, key.week());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? outcome(rs.getString(1)) : null;
            }
        }
    }

    /** A stored outcome; an unknown word still reads as settled (a row is a row: never paid twice). */
    private static CupPlan.Outcome outcome(String name) {
        try {
            return CupPlan.Outcome.valueOf(name);
        } catch (RuntimeException e) {
            return CupPlan.Outcome.EMPTY;
        }
    }

    private static String plain(String detail) {
        return detail == null ? null : COLOUR.matcher(detail).replaceAll("");
    }
}
