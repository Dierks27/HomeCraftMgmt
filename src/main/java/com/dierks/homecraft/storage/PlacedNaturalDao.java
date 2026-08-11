package com.dierks.homecraft.storage;

import org.bukkit.Location;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Records coordinates of player-placed blocks of a drop-eligible material, so a
 * Wild Drop can't be farmed by placing and re-breaking the same block (or
 * silk-touch-and-replace). Bounded — only drop-source materials are tracked.
 */
public final class PlacedNaturalDao {

    private final Database database;

    public PlacedNaturalDao(Database database) {
        this.database = database;
    }

    private Connection conn() {
        return database.connection();
    }

    public void mark(Location loc) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT OR IGNORE INTO player_placed(world, x, y, z) VALUES(?,?,?,?)")) {
                ps.setString(1, loc.getWorld().getName());
                ps.setInt(2, loc.getBlockX());
                ps.setInt(3, loc.getBlockY());
                ps.setInt(4, loc.getBlockZ());
                ps.executeUpdate();
            }
        }
    }

    /** @return true if this block was player-placed (and clears the record). */
    public boolean consume(Location loc) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM player_placed WHERE world=? AND x=? AND y=? AND z=?")) {
                ps.setString(1, loc.getWorld().getName());
                ps.setInt(2, loc.getBlockX());
                ps.setInt(3, loc.getBlockY());
                ps.setInt(4, loc.getBlockZ());
                return ps.executeUpdate() > 0;
            }
        }
    }

    public boolean contains(Location loc) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT 1 FROM player_placed WHERE world=? AND x=? AND y=? AND z=?")) {
                ps.setString(1, loc.getWorld().getName());
                ps.setInt(2, loc.getBlockX());
                ps.setInt(3, loc.getBlockY());
                ps.setInt(4, loc.getBlockZ());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        }
    }
}
