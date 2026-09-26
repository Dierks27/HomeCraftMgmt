package com.dierks.homecraft.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Data access for {@code achievements_unlocked} — one-time milestone unlocks per
 * player (Phase 9, §3.9). Each (player, achievement) fires at most once.
 */
public final class AchievementDao {

    private final Database database;

    public AchievementDao(Database database) {
        this.database = database;
    }

    private Connection conn() {
        return database.connection();
    }

    /**
     * Record an unlock. Returns true only if this is the first time (a new row was
     * inserted), so callers pay out the reward exactly once.
     */
    public boolean unlock(UUID player, String achievement, long now) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT OR IGNORE INTO achievements_unlocked(player, achievement, unlocked_at) "
                            + "VALUES(?,?,?)")) {
                ps.setString(1, player.toString());
                ps.setString(2, achievement);
                ps.setLong(3, now);
                return ps.executeUpdate() > 0;
            }
        }
    }

    /** Every achievement id the player has unlocked. */
    public java.util.Set<String> unlocked(UUID player) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            java.util.Set<String> out = new java.util.HashSet<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT achievement FROM achievements_unlocked WHERE player=?")) {
                ps.setString(1, player.toString());
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(rs.getString(1));
                    }
                }
            }
            return out;
        }
    }

    /** A plugin counter's value (0 when unset). */
    public long counter(UUID player, String counter) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT value FROM player_counters WHERE player=? AND counter=?")) {
                ps.setString(1, player.toString());
                ps.setString(2, counter);
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0;
                }
            }
        }
    }

    /** Add to a plugin counter; returns the new value. */
    public long addCounter(UUID player, String counter, long by) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO player_counters(player, counter, value) VALUES(?,?,?) "
                            + "ON CONFLICT(player, counter) DO UPDATE SET value = value + excluded.value")) {
                ps.setString(1, player.toString());
                ps.setString(2, counter);
                ps.setLong(3, by);
                ps.executeUpdate();
            }
            return counter(player, counter);
        }
    }

    /** Record a biome the player has entered; true the first time ever. */
    public boolean addBiome(UUID player, String biome, long now) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT OR IGNORE INTO player_biomes(player, biome, first_at) VALUES(?,?,?)")) {
                ps.setString(1, player.toString());
                ps.setString(2, biome);
                ps.setLong(3, now);
                return ps.executeUpdate() > 0;
            }
        }
    }

    /** Distinct biomes the player has ever entered. */
    public long biomeCount(UUID player) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM player_biomes WHERE player=?")) {
                ps.setString(1, player.toString());
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0;
                }
            }
        }
    }
}
