package com.dierks.homecraft.storage;

import org.bukkit.Location;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Data access for the Card economy (Phase 9): the per-type Card issuance tally
 * (enforces card caps) and the set of printers flagged public (free Mall printers).
 */
public final class CardDao {

    private final Database database;

    public CardDao(Database database) {
        this.database = database;
    }

    private Connection conn() {
        return database.connection();
    }

    /** How many Cards of a type have been issued so far. */
    public long issued(String cardId) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement("SELECT issued FROM card_counts WHERE card_id = ?")) {
                ps.setString(1, cardId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0;
                }
            }
        }
    }

    /** Bump the issuance tally for a type by {@code n}. */
    public void addIssued(String cardId, int n) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO card_counts(card_id, issued) VALUES(?, ?) "
                            + "ON CONFLICT(card_id) DO UPDATE SET issued = issued + ?")) {
                ps.setString(1, cardId);
                ps.setInt(2, n);
                ps.setInt(3, n);
                ps.executeUpdate();
            }
        }
    }

    public boolean isPublicPrinter(Location loc) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT 1 FROM public_printers WHERE world=? AND x=? AND y=? AND z=?")) {
                bindLoc(ps, loc);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        }
    }

    public void setPublicPrinter(Location loc, boolean isPublic) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            String sql = isPublic
                    ? "INSERT OR IGNORE INTO public_printers(world,x,y,z) VALUES(?,?,?,?)"
                    : "DELETE FROM public_printers WHERE world=? AND x=? AND y=? AND z=?";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                bindLoc(ps, loc);
                ps.executeUpdate();
            }
        }
    }

    private void bindLoc(PreparedStatement ps, Location loc) throws SQLException {
        ps.setString(1, loc.getWorld().getName());
        ps.setInt(2, loc.getBlockX());
        ps.setInt(3, loc.getBlockY());
        ps.setInt(4, loc.getBlockZ());
    }
}
