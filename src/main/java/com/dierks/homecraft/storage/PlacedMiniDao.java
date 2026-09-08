package com.dierks.homecraft.storage;

import org.bukkit.Location;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Mini heads a player placed as ordinary blocks ({@code placed_minis}): where each
 * one sits and which copy (uid) it is, so effects can be rebuilt on load and a copy
 * can never be lost silently. The identity itself also lives in the skull's PDC.
 */
public final class PlacedMiniDao {

    public record Row(String world, int x, int y, int z, String uid, String miniId) {
    }

    private final Database database;

    public PlacedMiniDao(Database database) {
        this.database = database;
    }

    private Connection conn() {
        return database.connection();
    }

    public void save(Location loc, String uid, String miniId) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO placed_minis(world, x, y, z, uid, mini_id, placed_at) VALUES(?,?,?,?,?,?,?) "
                            + "ON CONFLICT(world, x, y, z) DO UPDATE SET uid = excluded.uid, mini_id = excluded.mini_id, "
                            + "placed_at = excluded.placed_at")) {
                ps.setString(1, loc.getWorld().getName());
                ps.setInt(2, loc.getBlockX());
                ps.setInt(3, loc.getBlockY());
                ps.setInt(4, loc.getBlockZ());
                ps.setString(5, uid == null ? "" : uid);
                ps.setString(6, miniId);
                ps.setLong(7, System.currentTimeMillis());
                ps.executeUpdate();
            }
        }
    }

    public boolean deleteAt(Location loc) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM placed_minis WHERE world = ? AND x = ? AND y = ? AND z = ?")) {
                ps.setString(1, loc.getWorld().getName());
                ps.setInt(2, loc.getBlockX());
                ps.setInt(3, loc.getBlockY());
                ps.setInt(4, loc.getBlockZ());
                return ps.executeUpdate() > 0;
            }
        }
    }

    public List<Row> all() throws SQLException {
        Connection c = conn();
        synchronized (c) {
            List<Row> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT world, x, y, z, uid, mini_id FROM placed_minis");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Row(rs.getString("world"), rs.getInt("x"), rs.getInt("y"), rs.getInt("z"),
                            rs.getString("uid"), rs.getString("mini_id")));
                }
            }
            return out;
        }
    }
}
