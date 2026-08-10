package com.dierks.homecraft.storage;

import com.dierks.homecraft.market.MarketState;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Data access for the {@code market_state} table — durable per-item current
 * (mid) price + held stock, so the finite-stock market survives restarts.
 */
public final class MarketStateDao {

    private final Database database;

    public MarketStateDao(Database database) {
        this.database = database;
    }

    private Connection conn() {
        return database.connection();
    }

    /** Load every persisted market state, keyed by item id. */
    public Map<String, MarketState> loadAll() throws SQLException {
        Map<String, MarketState> out = new LinkedHashMap<>();
        Connection c = conn();
        synchronized (c) {
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT item_id, current_price, stock, updated_at FROM market_state")) {
                while (rs.next()) {
                    out.put(rs.getString("item_id"), new MarketState(
                            rs.getString("item_id"),
                            rs.getDouble("current_price"),
                            rs.getLong("stock"),
                            rs.getLong("updated_at")));
                }
            }
        }
        return out;
    }

    /** Insert or update a single item's state. */
    public void save(MarketState state) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO market_state(item_id, current_price, stock, updated_at) "
                            + "VALUES(?,?,?,?) "
                            + "ON CONFLICT(item_id) DO UPDATE SET "
                            + "current_price = excluded.current_price, "
                            + "stock = excluded.stock, "
                            + "updated_at = excluded.updated_at")) {
                ps.setString(1, state.itemId());
                ps.setDouble(2, state.currentPrice());
                ps.setLong(3, state.stock());
                ps.setLong(4, state.updatedAt());
                ps.executeUpdate();
            }
        }
    }
}
