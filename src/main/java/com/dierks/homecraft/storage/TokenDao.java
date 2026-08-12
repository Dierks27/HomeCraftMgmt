package com.dierks.homecraft.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Data access for {@code arcade_tokens} — a player's Arcade token balance plus the
 * login-streak and playtime-milestone state used to grant them (Phase 8, §3.9).
 * Tokens are a soft, earned-by-playing currency; they are never bought with real
 * money and hold no prizes (Mini prizes go through the mint pipeline).
 */
public final class TokenDao {

    public record TokenState(UUID player, int tokens, int streak, long lastStreakDay, int playtimeTokens) {
    }

    private final Database database;

    public TokenDao(Database database) {
        this.database = database;
    }

    private Connection conn() {
        return database.connection();
    }

    /** The player's token state, or a fresh zeroed state if they have none yet. */
    public TokenState get(UUID player) throws SQLException {
        Connection c = conn();
        synchronized (c) {
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
    }

    public void save(TokenState s) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO arcade_tokens(player,tokens,streak,last_streak_day,playtime_tokens) "
                            + "VALUES(?,?,?,?,?) "
                            + "ON CONFLICT(player) DO UPDATE SET tokens=excluded.tokens, streak=excluded.streak, "
                            + "last_streak_day=excluded.last_streak_day, playtime_tokens=excluded.playtime_tokens")) {
                ps.setString(1, s.player().toString());
                ps.setInt(2, Math.max(0, s.tokens()));
                ps.setInt(3, Math.max(0, s.streak()));
                ps.setLong(4, s.lastStreakDay());
                ps.setInt(5, Math.max(0, s.playtimeTokens()));
                ps.executeUpdate();
            }
        }
    }
}
