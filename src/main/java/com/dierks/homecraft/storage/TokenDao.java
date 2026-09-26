package com.dierks.homecraft.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Data access for {@code arcade_tokens} — a player's Arcade token balance plus the login-streak
 * and playtime-milestone state used to grant them (§3.9) — and for {@code token_ledger}, the
 * record of every change to that balance.
 *
 * <p>Balances used to be read-modify-write: read the row, add in Java, write the row back. Two
 * changes that interleaved could each read the same starting balance, and the second write would
 * silently erase the first. Every change now goes through one guarded statement,
 * {@code UPDATE … SET tokens = tokens + ? WHERE … AND tokens + ? >= 0}, so the database does the
 * arithmetic and refuses a spend that would go below zero, and the ledger row that explains the
 * change is written in the same transaction. A balance can therefore never move without a line
 * saying why, and never go negative.
 */
public final class TokenDao {

    public record TokenState(UUID player, int tokens, int streak, long lastStreakDay, int playtimeTokens) {
    }

    /** One ledger line: what moved, what it left behind, and why. */
    public record LedgerRow(long id, UUID player, int delta, int balanceAfter, String source,
                            String detail, long at) {
    }

    /** Earned (positive deltas) and spent (negative deltas, as a positive number) per source. */
    public record SourceTotal(UUID player, String source, long earned, long spent) {
    }

    /** Returned by the guarded writes when nothing changed. */
    public static final int REFUSED = -1;

    private final Database database;

    public TokenDao(Database database) {
        this.database = database;
    }

    /** The player's token state, or a fresh zeroed state if they have none yet. */
    public TokenState get(UUID player) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            return read(c, player);
        }
    }

    /**
     * Apply {@code delta} to a balance and write the ledger line, atomically.
     *
     * @return the new balance, or {@link #REFUSED} when a spend would take it below zero
     *         (nothing is written in that case)
     */
    public int change(UUID player, int delta, String source, String detail, long now) throws SQLException {
        return database.transaction(c -> {
            ensureRow(c, player);
            if (delta == 0) {
                return read(c, player).tokens();
            }
            if (!applyDelta(c, player, delta)) {
                return REFUSED;
            }
            int after = read(c, player).tokens();
            ledger(c, player, delta, after, source, detail, now);
            return after;
        });
    }

    /**
     * Set a balance to an exact value (admin), recording the difference in the ledger.
     *
     * @return the new balance
     */
    public int setBalance(UUID player, int target, String source, String detail, long now) throws SQLException {
        return database.transaction(c -> {
            ensureRow(c, player);
            int current = read(c, player).tokens();
            int delta = Math.max(0, target) - current;
            if (delta == 0) {
                return current;
            }
            applyDelta(c, player, delta);
            int after = read(c, player).tokens();
            ledger(c, player, delta, after, source, detail, now);
            return after;
        });
    }

    /**
     * Claim a login-streak reward: record the new streak and day, and pay {@code reward}.
     *
     * <p>Guarded on {@code last_streak_day < day}, so of two claims racing for the same day
     * exactly one pays.
     *
     * @return the new balance, or {@link #REFUSED} when today was already claimed
     */
    public int claimStreak(UUID player, int streak, long day, int reward, String source, String detail,
                           long now) throws SQLException {
        return database.transaction(c -> {
            ensureRow(c, player);
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE arcade_tokens SET streak=?, last_streak_day=? "
                            + "WHERE player=? AND last_streak_day < ?")) {
                ps.setInt(1, Math.max(0, streak));
                ps.setLong(2, day);
                ps.setString(3, player.toString());
                ps.setLong(4, day);
                if (ps.executeUpdate() == 0) {
                    return REFUSED;
                }
            }
            if (reward > 0) {
                applyDelta(c, player, reward);
                int after = read(c, player).tokens();
                ledger(c, player, reward, after, source, detail, now);
                return after;
            }
            return read(c, player).tokens();
        });
    }

    /**
     * Pull a stored streak day back to {@code day} without paying anything. Used once, for a
     * day key written as a UTC day that is now ahead of the local calendar.
     */
    public void rewindStreakDay(UUID player, long day) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE arcade_tokens SET last_streak_day=? WHERE player=? AND last_streak_day > ?")) {
                ps.setLong(1, day);
                ps.setString(2, player.toString());
                ps.setLong(3, day);
                ps.executeUpdate();
            }
        }
    }

    /**
     * Pay the playtime milestones between what was already paid and {@code earnedTotal}.
     *
     * @return how many tokens were paid (0 when nothing new was earned)
     */
    public int claimPlaytime(UUID player, int earnedTotal, String source, String detail, long now)
            throws SQLException {
        return database.transaction(c -> {
            ensureRow(c, player);
            int paid = read(c, player).playtimeTokens();
            if (earnedTotal <= paid) {
                return 0;
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE arcade_tokens SET playtime_tokens=? WHERE player=? AND playtime_tokens=?")) {
                ps.setInt(1, earnedTotal);
                ps.setString(2, player.toString());
                ps.setInt(3, paid);
                if (ps.executeUpdate() == 0) {
                    return 0;
                }
            }
            int diff = earnedTotal - paid;
            applyDelta(c, player, diff);
            ledger(c, player, diff, read(c, player).tokens(), source, detail, now);
            return diff;
        });
    }

    /** The player's most recent ledger lines, newest first. */
    public List<LedgerRow> history(UUID player, int limit) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM token_ledger WHERE player=? ORDER BY at DESC, id DESC LIMIT ?")) {
                ps.setString(1, player.toString());
                ps.setInt(2, Math.max(1, limit));
                return rows(ps);
            }
        }
    }

    /**
     * Earned and spent per player per source since {@code since}.
     *
     * @param player one player, or null for everyone
     */
    public List<SourceTotal> totals(long since, UUID player) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            String sql = "SELECT player, source, "
                    + "SUM(CASE WHEN delta > 0 THEN delta ELSE 0 END) AS earned, "
                    + "SUM(CASE WHEN delta < 0 THEN -delta ELSE 0 END) AS spent "
                    + "FROM token_ledger WHERE at >= ?" + (player != null ? " AND player = ?" : "")
                    + " GROUP BY player, source ORDER BY player, source";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, since);
                if (player != null) {
                    ps.setString(2, player.toString());
                }
                List<SourceTotal> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new SourceTotal(UUID.fromString(rs.getString("player")),
                                rs.getString("source"), rs.getLong("earned"), rs.getLong("spent")));
                    }
                }
                return out;
            }
        }
    }

    // ---- internals (caller holds the connection lock) ---------------------------

    private static TokenState read(Connection c, UUID player) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM arcade_tokens WHERE player=?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new TokenState(player, rs.getInt("tokens"), rs.getInt("streak"),
                            rs.getLong("last_streak_day"), rs.getInt("playtime_tokens"));
                }
                return new TokenState(player, 0, 0, 0, 0);
            }
        }
    }

    private static void ensureRow(Connection c, UUID player) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT OR IGNORE INTO arcade_tokens(player) VALUES(?)")) {
            ps.setString(1, player.toString());
            ps.executeUpdate();
        }
    }

    /** The guarded arithmetic. False when the change would leave the balance negative. */
    private static boolean applyDelta(Connection c, UUID player, int delta) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE arcade_tokens SET tokens = tokens + ? WHERE player = ? AND tokens + ? >= 0")) {
            ps.setInt(1, delta);
            ps.setString(2, player.toString());
            ps.setInt(3, delta);
            return ps.executeUpdate() > 0;
        }
    }

    private static void ledger(Connection c, UUID player, int delta, int after, String source,
                               String detail, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO token_ledger(player, delta, balance_after, source, detail, at) "
                        + "VALUES(?,?,?,?,?,?)")) {
            ps.setString(1, player.toString());
            ps.setInt(2, delta);
            ps.setInt(3, after);
            ps.setString(4, source);
            ps.setString(5, detail);
            ps.setLong(6, now);
            ps.executeUpdate();
        }
    }

    private static List<LedgerRow> rows(PreparedStatement ps) throws SQLException {
        List<LedgerRow> out = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new LedgerRow(rs.getLong("id"), UUID.fromString(rs.getString("player")),
                        rs.getInt("delta"), rs.getInt("balance_after"), rs.getString("source"),
                        rs.getString("detail"), rs.getLong("at")));
            }
        }
        return out;
    }
}
