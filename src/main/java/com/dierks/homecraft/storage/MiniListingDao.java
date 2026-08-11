package com.dierks.homecraft.storage;

import org.bukkit.Location;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/**
 * Data access for {@code mini_listings} — a fixed-price Vending Machine sale or a
 * Display Case trophy, keyed by block location. The listed Mini item is stored
 * verbatim (Base64) so it is handed back byte-for-byte on buy/unlist.
 */
public final class MiniListingDao {

    /** One listing. {@code kind} is VENDING or DISPLAY; price is 0 for a Display Case. */
    public record Listing(long id, String world, int x, int y, int z, String kind, UUID owner,
                          String uid, String miniId, long mintNumber, double price,
                          String itemB64, long listedAt) {
    }

    private final Database database;

    public MiniListingDao(Database database) {
        this.database = database;
    }

    private Connection conn() {
        return database.connection();
    }

    public void create(Location loc, String kind, UUID owner, String uid, String miniId,
                       long mintNumber, double price, String itemB64, long now) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO mini_listings(world,x,y,z,kind,owner,uid,mini_id,mint_number,price,item_b64,listed_at) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?) "
                            + "ON CONFLICT(world,x,y,z) DO UPDATE SET "
                            + "kind=excluded.kind, owner=excluded.owner, uid=excluded.uid, mini_id=excluded.mini_id, "
                            + "mint_number=excluded.mint_number, price=excluded.price, item_b64=excluded.item_b64, "
                            + "listed_at=excluded.listed_at")) {
                ps.setString(1, loc.getWorld().getName());
                ps.setInt(2, loc.getBlockX());
                ps.setInt(3, loc.getBlockY());
                ps.setInt(4, loc.getBlockZ());
                ps.setString(5, kind);
                ps.setString(6, owner.toString());
                ps.setString(7, uid);
                ps.setString(8, miniId);
                ps.setLong(9, mintNumber);
                ps.setDouble(10, price);
                ps.setString(11, itemB64);
                ps.setLong(12, now);
                ps.executeUpdate();
            }
        }
    }

    public Optional<Listing> at(Location loc) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM mini_listings WHERE world=? AND x=? AND y=? AND z=?")) {
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

    public void updatePrice(Location loc, double price) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE mini_listings SET price=? WHERE world=? AND x=? AND y=? AND z=?")) {
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
                    "DELETE FROM mini_listings WHERE world=? AND x=? AND y=? AND z=?")) {
                ps.setString(1, loc.getWorld().getName());
                ps.setInt(2, loc.getBlockX());
                ps.setInt(3, loc.getBlockY());
                ps.setInt(4, loc.getBlockZ());
                return ps.executeUpdate() > 0;
            }
        }
    }

    private Listing map(ResultSet rs) throws SQLException {
        return new Listing(
                rs.getLong("id"),
                rs.getString("world"), rs.getInt("x"), rs.getInt("y"), rs.getInt("z"),
                rs.getString("kind"),
                UUID.fromString(rs.getString("owner")),
                rs.getString("uid"), rs.getString("mini_id"), rs.getLong("mint_number"),
                rs.getDouble("price"), rs.getString("item_b64"), rs.getLong("listed_at"));
    }
}
