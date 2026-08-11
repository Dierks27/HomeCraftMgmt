package com.dierks.homecraft.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Data access for Minis: the mint tally per type (the single source of truth for
 * caps) and per-copy provenance rows. Minted count is exact; circulation is
 * {@code minted - destroyed} (best-effort).
 */
public final class MiniDao {

    /** Mint tally for one Mini type. */
    public record Counts(long minted, long destroyed) {
        public long circulation() {
            return Math.max(0, minted - destroyed);
        }
    }

    /** Per-copy provenance row. */
    public record Individual(String uid, String miniId, long mintNumber, UUID owner, long mintedAt) {
    }

    /** One logged secondary-market sale. */
    public record Sale(String uid, String miniId, double price, UUID seller, UUID buyer,
                       String venue, long soldAt) {
    }

    private final Database database;

    public MiniDao(Database database) {
        this.database = database;
    }

    private Connection conn() {
        return database.connection();
    }

    public Counts counts(String miniId) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT minted, destroyed FROM mini_counts WHERE mini_id = ?")) {
                ps.setString(1, miniId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return new Counts(rs.getLong("minted"), rs.getLong("destroyed"));
                    }
                    return new Counts(0, 0);
                }
            }
        }
    }

    /** Atomically bump the minted tally and return the new mint number. */
    public long mintNext(String miniId) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO mini_counts(mini_id, minted) VALUES(?, 1) "
                            + "ON CONFLICT(mini_id) DO UPDATE SET minted = minted + 1")) {
                ps.setString(1, miniId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT minted FROM mini_counts WHERE mini_id = ?")) {
                ps.setString(1, miniId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 1;
                }
            }
        }
    }

    public void recordIndividual(UUID uid, String miniId, long mintNumber, UUID owner, long mintedAt)
            throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO mini_individuals(uid, mini_id, mint_number, owner, minted_at) VALUES(?,?,?,?,?)")) {
                ps.setString(1, uid.toString());
                ps.setString(2, miniId);
                ps.setLong(3, mintNumber);
                ps.setString(4, owner != null ? owner.toString() : null);
                ps.setLong(5, mintedAt);
                ps.executeUpdate();
            }
        }
    }

    /** @return the provenance row for a minted copy, if known. */
    public java.util.Optional<Individual> individual(String uid) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT uid, mini_id, mint_number, owner, minted_at FROM mini_individuals WHERE uid = ?")) {
                ps.setString(1, uid);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return java.util.Optional.empty();
                    }
                    String owner = rs.getString("owner");
                    return java.util.Optional.of(new Individual(rs.getString("uid"), rs.getString("mini_id"),
                            rs.getLong("mint_number"), owner != null ? UUID.fromString(owner) : null,
                            rs.getLong("minted_at")));
                }
            }
        }
    }

    /** Sales history for one copy, oldest first (the ownership trail). */
    public java.util.List<Sale> salesForUid(String uid) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            java.util.List<Sale> out = new java.util.ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT uid, mini_id, price, seller, buyer, venue, sold_at FROM mini_sales "
                            + "WHERE uid = ? ORDER BY sold_at ASC")) {
                ps.setString(1, uid);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(mapSale(rs));
                    }
                }
            }
            return out;
        }
    }

    /** @return the most recent sale price for a Mini type (any copy), or null if never sold. */
    public Double lastSalePrice(String miniId) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT price FROM mini_sales WHERE mini_id = ? ORDER BY sold_at DESC LIMIT 1")) {
                ps.setString(1, miniId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getDouble(1) : null;
                }
            }
        }
    }

    private Sale mapSale(ResultSet rs) throws SQLException {
        String seller = rs.getString("seller");
        String buyer = rs.getString("buyer");
        return new Sale(rs.getString("uid"), rs.getString("mini_id"), rs.getDouble("price"),
                seller != null ? UUID.fromString(seller) : null,
                buyer != null ? UUID.fromString(buyer) : null,
                rs.getString("venue"), rs.getLong("sold_at"));
    }

    /** Transfer provenance ownership of one minted copy (secondary-market sale/auction). */
    public void updateOwner(String uid, UUID newOwner) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE mini_individuals SET owner = ? WHERE uid = ?")) {
                ps.setString(1, newOwner != null ? newOwner.toString() : null);
                ps.setString(2, uid);
                ps.executeUpdate();
            }
        }
    }

    /** Log a secondary-market sale (Vending Machine or Auction) for price history + provenance. */
    public void recordSale(String uid, String miniId, double price, UUID seller, UUID buyer,
                           String venue, long soldAt) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO mini_sales(uid, mini_id, price, seller, buyer, venue, sold_at) "
                            + "VALUES(?,?,?,?,?,?,?)")) {
                ps.setString(1, uid);
                ps.setString(2, miniId);
                ps.setDouble(3, price);
                ps.setString(4, seller != null ? seller.toString() : null);
                ps.setString(5, buyer != null ? buyer.toString() : null);
                ps.setString(6, venue);
                ps.setLong(7, soldAt);
                ps.executeUpdate();
            }
        }
    }
}
