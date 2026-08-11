package com.dierks.homecraft.storage;

import org.bukkit.Location;

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
 * Data access for {@code mini_vending_listings} — a Vending Machine holds many
 * Minis, each its own priced listing (keyed by id, not by location). The listed
 * Mini item is stored verbatim (Base64) so it hands back byte-for-byte.
 */
public final class MiniVendingDao {

    public record Listing(long id, String world, int x, int y, int z, UUID owner, String uid,
                          String miniId, long mintNumber, double price, String itemB64, long listedAt) {
    }

    private final Database database;

    public MiniVendingDao(Database database) {
        this.database = database;
    }

    private Connection conn() {
        return database.connection();
    }

    public long add(Location loc, UUID owner, String uid, String miniId, long mintNumber,
                    double price, String itemB64, long now) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO mini_vending_listings(world,x,y,z,owner,uid,mini_id,mint_number,price,item_b64,listed_at) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, loc.getWorld().getName());
                ps.setInt(2, loc.getBlockX());
                ps.setInt(3, loc.getBlockY());
                ps.setInt(4, loc.getBlockZ());
                ps.setString(5, owner.toString());
                ps.setString(6, uid);
                ps.setString(7, miniId);
                ps.setLong(8, mintNumber);
                ps.setDouble(9, price);
                ps.setString(10, itemB64);
                ps.setLong(11, now);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    return rs.next() ? rs.getLong(1) : -1;
                }
            }
        }
    }

    public List<Listing> listingsAt(Location loc) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            List<Listing> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM mini_vending_listings WHERE world=? AND x=? AND y=? AND z=? ORDER BY id ASC")) {
                ps.setString(1, loc.getWorld().getName());
                ps.setInt(2, loc.getBlockX());
                ps.setInt(3, loc.getBlockY());
                ps.setInt(4, loc.getBlockZ());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(map(rs));
                    }
                }
            }
            return out;
        }
    }

    public Optional<Listing> byId(long id) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM mini_vending_listings WHERE id=?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                }
            }
        }
    }

    public void updatePrice(long id, double price) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement("UPDATE mini_vending_listings SET price=? WHERE id=?")) {
                ps.setDouble(1, price);
                ps.setLong(2, id);
                ps.executeUpdate();
            }
        }
    }

    public boolean remove(long id) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM mini_vending_listings WHERE id=?")) {
                ps.setLong(1, id);
                return ps.executeUpdate() > 0;
            }
        }
    }

    private Listing map(ResultSet rs) throws SQLException {
        return new Listing(
                rs.getLong("id"),
                rs.getString("world"), rs.getInt("x"), rs.getInt("y"), rs.getInt("z"),
                UUID.fromString(rs.getString("owner")),
                rs.getString("uid"), rs.getString("mini_id"), rs.getLong("mint_number"),
                rs.getDouble("price"), rs.getString("item_b64"), rs.getLong("listed_at"));
    }
}
