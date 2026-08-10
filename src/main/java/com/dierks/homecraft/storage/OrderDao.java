package com.dierks.homecraft.storage;

import com.dierks.homecraft.order.Order;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.UUID;

/** Data access for {@code market_orders} — Amazon orders and their delivery timers. */
public final class OrderDao {

    private final Database database;

    public OrderDao(Database database) {
        this.database = database;
    }

    private Connection conn() {
        return database.connection();
    }

    /** Insert an order and return it with its generated id. */
    public Order insert(Order order) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO market_orders(player_uuid, item_id, qty, item_cost, shipping_cost, tier, "
                            + "placed_at, deliver_at, status) VALUES(?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, order.player().toString());
                ps.setString(2, order.itemId());
                ps.setInt(3, order.qty());
                ps.setDouble(4, order.itemCost());
                ps.setDouble(5, order.shippingCost());
                ps.setString(6, order.tier());
                ps.setLong(7, order.placedAt());
                ps.setLong(8, order.deliverAt());
                ps.setString(9, order.status().name());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    long id = keys.next() ? keys.getLong(1) : 0L;
                    return new Order(id, order.player(), order.itemId(), order.qty(), order.itemCost(),
                            order.shippingCost(), order.tier(), order.placedAt(), order.deliverAt(), order.status());
                }
            }
        }
    }

    public List<Order> listByPlayer(UUID player, Order.Status... statuses) throws SQLException {
        StringJoiner in = new StringJoiner(",", "(", ")");
        for (Order.Status s : statuses) {
            in.add("'" + s.name() + "'");
        }
        String sql = "SELECT * FROM market_orders WHERE player_uuid = ?"
                + (statuses.length > 0 ? " AND status IN " + in : "")
                + " ORDER BY deliver_at ASC";
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, player.toString());
                return readAll(ps);
            }
        }
    }

    /** In-transit orders whose delivery time has arrived. */
    public List<Order> findDue(long now) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM market_orders WHERE status = 'IN_TRANSIT' AND deliver_at <= ?")) {
                ps.setLong(1, now);
                return readAll(ps);
            }
        }
    }

    public Optional<Order> findById(long id) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM market_orders WHERE id = ?")) {
                ps.setLong(1, id);
                List<Order> rows = readAll(ps);
                return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
            }
        }
    }

    public void updateStatus(long id, Order.Status status) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement("UPDATE market_orders SET status = ? WHERE id = ?")) {
                ps.setString(1, status.name());
                ps.setLong(2, id);
                ps.executeUpdate();
            }
        }
    }

    private List<Order> readAll(PreparedStatement ps) throws SQLException {
        List<Order> out = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Order.Status status;
                try {
                    status = Order.Status.valueOf(rs.getString("status"));
                } catch (IllegalArgumentException e) {
                    status = Order.Status.IN_TRANSIT;
                }
                out.add(new Order(
                        rs.getLong("id"),
                        UUID.fromString(rs.getString("player_uuid")),
                        rs.getString("item_id"),
                        rs.getInt("qty"),
                        rs.getDouble("item_cost"),
                        rs.getDouble("shipping_cost"),
                        rs.getString("tier"),
                        rs.getLong("placed_at"),
                        rs.getLong("deliver_at"),
                        status));
            }
        }
        return out;
    }
}
