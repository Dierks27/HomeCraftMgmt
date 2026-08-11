package com.dierks.homecraft.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Data access for {@code deliveries} — items owed to a player (Marketplace
 * purchases) that ship to their Mailbox after a real-time delay. The exact item
 * is stored verbatim (Base64) so any item/NBT survives.
 */
public final class DeliveryDao {

    public static final String IN_TRANSIT = "IN_TRANSIT";
    public static final String READY = "READY";
    public static final String COLLECTED = "COLLECTED";

    public record Delivery(long id, UUID player, String itemB64, String label, String source,
                           long placedAt, long deliverAt, String status) {
    }

    private final Database database;

    public DeliveryDao(Database database) {
        this.database = database;
    }

    private Connection conn() {
        return database.connection();
    }

    public long insert(UUID player, String itemB64, String label, String source,
                       long placedAt, long deliverAt, String status) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO deliveries(player,item_b64,label,source,placed_at,deliver_at,status) "
                            + "VALUES(?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, player.toString());
                ps.setString(2, itemB64);
                ps.setString(3, label);
                ps.setString(4, source);
                ps.setLong(5, placedAt);
                ps.setLong(6, deliverAt);
                ps.setString(7, status);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    return rs.next() ? rs.getLong(1) : -1;
                }
            }
        }
    }

    public List<Delivery> findDue(long now) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            List<Delivery> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM deliveries WHERE status=? AND deliver_at<=?")) {
                ps.setString(1, IN_TRANSIT);
                ps.setLong(2, now);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(map(rs));
                    }
                }
            }
            return out;
        }
    }

    public List<Delivery> activeFor(UUID player) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            List<Delivery> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM deliveries WHERE player=? AND status IN (?,?) ORDER BY deliver_at ASC")) {
                ps.setString(1, player.toString());
                ps.setString(2, IN_TRANSIT);
                ps.setString(3, READY);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(map(rs));
                    }
                }
            }
            return out;
        }
    }

    public Optional<Delivery> byId(long id) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM deliveries WHERE id=?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                }
            }
        }
    }

    public void updateStatus(long id, String status) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement("UPDATE deliveries SET status=? WHERE id=?")) {
                ps.setString(1, status);
                ps.setLong(2, id);
                ps.executeUpdate();
            }
        }
    }

    /** Replace the stored item (used when only part of a stack fit into the inventory). */
    public void updateItem(long id, String itemB64) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement("UPDATE deliveries SET item_b64=? WHERE id=?")) {
                ps.setString(1, itemB64);
                ps.setLong(2, id);
                ps.executeUpdate();
            }
        }
    }

    private Delivery map(ResultSet rs) throws SQLException {
        return new Delivery(rs.getLong("id"), UUID.fromString(rs.getString("player")),
                rs.getString("item_b64"), rs.getString("label"), rs.getString("source"),
                rs.getLong("placed_at"), rs.getLong("deliver_at"), rs.getString("status"));
    }
}
