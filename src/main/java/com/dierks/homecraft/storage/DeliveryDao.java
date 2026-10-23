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
    /**
     * Held for a trade that hasn't finished (0.38, SHOP-V2-SPEC §4.6, §4.7): what a trade sends to a Mailbox is
     * written HELD in the swap's own transaction and turned READY ({@link #release}) only once both bags are saved.
     * Neither the Mailbox list nor the delivery tick reads it (they name IN_TRANSIT and READY), so a server that
     * stopped mid-swap hands nothing out twice; and a 0.37 jar, which knows only those two, can't release it either.
     */
    public static final String HELD = "HELD";

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

    /**
     * {@link #updateStatus}, only while the row still has status {@code from}: false when it didn't (already
     * collected), so a hand-over that reads one state and writes another never acts twice.
     */
    public boolean updateStatusIf(long id, String from, String to) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement("UPDATE deliveries SET status=? WHERE id=? AND status=?")) {
                ps.setString(1, to);
                ps.setLong(2, id);
                ps.setString(3, from);
                return ps.executeUpdate() > 0;
            }
        }
    }

    /** {@link #updateItem}, only while the row still has status {@code status}: false when it didn't. */
    public boolean updateItemIf(long id, String status, String itemB64) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE deliveries SET item_b64=? WHERE id=? AND status=?")) {
                ps.setString(1, itemB64);
                ps.setLong(2, id);
                ps.setString(3, status);
                return ps.executeUpdate() > 0;
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

    /**
     * Turn every HELD row of {@code source} (one trade's, {@code TRADE#<id>}) READY, ready to collect now. Joins a
     * caller's transaction.
     *
     * @return how many rows it released
     */
    public int release(String source, long now) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE deliveries SET status=?, deliver_at=? WHERE status=? AND source=?")) {
                ps.setString(1, READY);
                ps.setLong(2, now);
                ps.setString(3, HELD);
                ps.setString(4, source);
                return ps.executeUpdate();
            }
        }
    }

    /** Delete every HELD row of {@code source} (an admin voiding a trade that didn't finish). */
    public int deleteHeld(String source) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM deliveries WHERE status=? AND source=?")) {
                ps.setString(1, HELD);
                ps.setString(2, source);
                return ps.executeUpdate();
            }
        }
    }

    /** Every row of {@code source}, whatever its status, oldest first (for {@code /hcm trades show}). */
    public List<Delivery> bySource(String source) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            List<Delivery> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM deliveries WHERE source=? ORDER BY id ASC")) {
                ps.setString(1, source);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(map(rs));
                    }
                }
            }
            return out;
        }
    }

    private Delivery map(ResultSet rs) throws SQLException {
        return new Delivery(rs.getLong("id"), UUID.fromString(rs.getString("player")),
                rs.getString("item_b64"), rs.getString("label"), rs.getString("source"),
                rs.getLong("placed_at"), rs.getLong("deliver_at"), rs.getString("status"));
    }
}
