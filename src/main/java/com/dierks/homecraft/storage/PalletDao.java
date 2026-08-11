package com.dierks.homecraft.storage;

import org.bukkit.Location;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Data access for {@code pallet_listings} — one item type per Pallet block at a
 * fixed price with a stock count; active listings surface in the Crate
 * Marketplace and auto-deactivate at zero stock.
 */
public final class PalletDao {

    public record Listing(long id, String world, int x, int y, int z, UUID owner, String itemB64,
                          double price, int stock, String department, boolean active, long listedAt) {
    }

    private final Database database;

    public PalletDao(Database database) {
        this.database = database;
    }

    private Connection conn() {
        return database.connection();
    }

    /** Insert or replace the listing at a Pallet location. */
    public void upsert(Location loc, UUID owner, String itemB64, double price, int stock,
                       String department, long now) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO pallet_listings(world,x,y,z,owner,item_b64,price,stock,department,active,listed_at) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,1,?) "
                            + "ON CONFLICT(world,x,y,z) DO UPDATE SET owner=excluded.owner, item_b64=excluded.item_b64, "
                            + "price=excluded.price, stock=excluded.stock, department=excluded.department, "
                            + "active=1, listed_at=excluded.listed_at")) {
                ps.setString(1, loc.getWorld().getName());
                ps.setInt(2, loc.getBlockX());
                ps.setInt(3, loc.getBlockY());
                ps.setInt(4, loc.getBlockZ());
                ps.setString(5, owner.toString());
                ps.setString(6, itemB64);
                ps.setDouble(7, price);
                ps.setInt(8, stock);
                ps.setString(9, department);
                ps.setLong(10, now);
                ps.executeUpdate();
            }
        }
    }

    public Optional<Listing> at(Location loc) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM pallet_listings WHERE world=? AND x=? AND y=? AND z=?")) {
                ps.setString(1, loc.getWorld().getName());
                ps.setInt(2, loc.getBlockX());
                ps.setInt(3, loc.getBlockY());
                ps.setInt(4, loc.getBlockZ());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                }
            }
        }
    }

    public Optional<Listing> byId(long id) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM pallet_listings WHERE id=?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                }
            }
        }
    }

    /** All active, in-stock listings (optionally filtered by department; null = all). */
    public List<Listing> listActive(String department) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            List<Listing> out = new ArrayList<>();
            String sql = "SELECT * FROM pallet_listings WHERE active=1 AND stock>0"
                    + (department != null ? " AND department=?" : "") + " ORDER BY listed_at DESC";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                if (department != null) {
                    ps.setString(1, department);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(map(rs));
                    }
                }
            }
            return out;
        }
    }

    public void updateStock(long id, int stock) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE pallet_listings SET stock=?, active=CASE WHEN ?>0 THEN active ELSE 0 END WHERE id=?")) {
                ps.setInt(1, stock);
                ps.setInt(2, stock);
                ps.setLong(3, id);
                ps.executeUpdate();
            }
        }
    }

    public void updatePrice(Location loc, double price) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE pallet_listings SET price=? WHERE world=? AND x=? AND y=? AND z=?")) {
                ps.setDouble(1, price);
                ps.setString(2, loc.getWorld().getName());
                ps.setInt(3, loc.getBlockX());
                ps.setInt(4, loc.getBlockY());
                ps.setInt(5, loc.getBlockZ());
                ps.executeUpdate();
            }
        }
    }

    public boolean deleteAt(Location loc) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM pallet_listings WHERE world=? AND x=? AND y=? AND z=?")) {
                ps.setString(1, loc.getWorld().getName());
                ps.setInt(2, loc.getBlockX());
                ps.setInt(3, loc.getBlockY());
                ps.setInt(4, loc.getBlockZ());
                return ps.executeUpdate() > 0;
            }
        }
    }

    private Listing map(ResultSet rs) throws SQLException {
        return new Listing(rs.getLong("id"),
                rs.getString("world"), rs.getInt("x"), rs.getInt("y"), rs.getInt("z"),
                UUID.fromString(rs.getString("owner")), rs.getString("item_b64"),
                rs.getDouble("price"), rs.getInt("stock"), rs.getString("department"),
                rs.getInt("active") != 0, rs.getLong("listed_at"));
    }
}
