package com.dierks.homecraft.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only price/stock history for each commodity. Periodic snapshots feed
 * the Phase 5 web dashboard's charts; a small recent-rows query is exposed so
 * the test commands can show that history is accumulating.
 */
public final class PriceHistoryDao {

    /** One historical snapshot. */
    public record Snapshot(String itemId, double price, long stock, long recordedAt) {
    }

    private final Database database;

    public PriceHistoryDao(Database database) {
        this.database = database;
    }

    private Connection conn() {
        return database.connection();
    }

    public void record(String itemId, double price, long stock, long recordedAt) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO market_price_history(item_id, price, stock, recorded_at) VALUES(?,?,?,?)")) {
                ps.setString(1, itemId);
                ps.setDouble(2, price);
                ps.setLong(3, stock);
                ps.setLong(4, recordedAt);
                ps.executeUpdate();
            }
        }
    }

    /** Most recent snapshots for an item, newest first. */
    public List<Snapshot> recent(String itemId, int limit) throws SQLException {
        List<Snapshot> out = new ArrayList<>();
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT item_id, price, stock, recorded_at FROM market_price_history "
                            + "WHERE item_id = ? ORDER BY recorded_at DESC LIMIT ?")) {
                ps.setString(1, itemId);
                ps.setInt(2, Math.max(1, limit));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Snapshot(
                                rs.getString("item_id"),
                                rs.getDouble("price"),
                                rs.getLong("stock"),
                                rs.getLong("recorded_at")));
                    }
                }
            }
        }
        return out;
    }
}
