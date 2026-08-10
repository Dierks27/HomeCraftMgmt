package com.dierks.homecraft.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Per-player, per-day sell tallies backing the anti-whale daily sell limit.
 * {@code day} is the UTC epoch-day, so tallies reset naturally at midnight UTC.
 */
public final class DailySellDao {

    private final Database database;

    public DailySellDao(Database database) {
        this.database = database;
    }

    private Connection conn() {
        return database.connection();
    }

    /** Total money a player has earned selling to the market on the given day. */
    public double moneyEarned(UUID player, long day) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COALESCE(SUM(money), 0) FROM market_daily_sells WHERE player_uuid = ? AND day = ?")) {
                ps.setString(1, player.toString());
                ps.setLong(2, day);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getDouble(1) : 0.0;
                }
            }
        }
    }

    /** Units of one item a player has sold on the given day. */
    public long unitsSold(UUID player, long day, String itemId) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT units FROM market_daily_sells WHERE player_uuid = ? AND day = ? AND item_id = ?")) {
                ps.setString(1, player.toString());
                ps.setLong(2, day);
                ps.setString(3, itemId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            }
        }
    }

    /** Add to a player's daily tally for an item (creates the row if needed). */
    public void record(UUID player, long day, String itemId, long units, double money) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO market_daily_sells(player_uuid, day, item_id, units, money) "
                            + "VALUES(?,?,?,?,?) "
                            + "ON CONFLICT(player_uuid, day, item_id) DO UPDATE SET "
                            + "units = units + excluded.units, money = money + excluded.money")) {
                ps.setString(1, player.toString());
                ps.setLong(2, day);
                ps.setString(3, itemId);
                ps.setLong(4, units);
                ps.setDouble(5, money);
                ps.executeUpdate();
            }
        }
    }
}
