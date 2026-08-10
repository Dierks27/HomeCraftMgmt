package com.dierks.homecraft.storage;

import com.dierks.homecraft.block.CustomBlockType;
import org.bukkit.Location;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/**
 * Data access for the {@code placed_blocks} table — the source of truth for
 * "is this block one of ours, and who owns it?".
 */
public final class PlacedBlockDao {

    private final Database database;

    public PlacedBlockDao(Database database) {
        this.database = database;
    }

    private Connection conn() {
        return database.connection();
    }

    /** Insert or replace the placement at a location. */
    public void save(PlacedBlock block) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO placed_blocks(world, x, y, z, type, owner, created_at) "
                            + "VALUES(?,?,?,?,?,?,?) "
                            + "ON CONFLICT(world, x, y, z) DO UPDATE SET "
                            + "type = excluded.type, owner = excluded.owner, created_at = excluded.created_at")) {
                ps.setString(1, block.world());
                ps.setInt(2, block.x());
                ps.setInt(3, block.y());
                ps.setInt(4, block.z());
                ps.setString(5, block.type().name());
                ps.setString(6, block.owner().toString());
                ps.setLong(7, block.createdAt());
                ps.executeUpdate();
            }
        }
    }

    public Optional<PlacedBlock> findAt(Location loc) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT world, x, y, z, type, owner, created_at FROM placed_blocks "
                            + "WHERE world = ? AND x = ? AND y = ? AND z = ?")) {
                ps.setString(1, loc.getWorld().getName());
                ps.setInt(2, loc.getBlockX());
                ps.setInt(3, loc.getBlockY());
                ps.setInt(4, loc.getBlockZ());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(map(rs));
                }
            }
        }
    }

    public boolean deleteAt(Location loc) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM placed_blocks WHERE world = ? AND x = ? AND y = ? AND z = ?")) {
                ps.setString(1, loc.getWorld().getName());
                ps.setInt(2, loc.getBlockX());
                ps.setInt(3, loc.getBlockY());
                ps.setInt(4, loc.getBlockZ());
                return ps.executeUpdate() > 0;
            }
        }
    }

    private PlacedBlock map(ResultSet rs) throws SQLException {
        CustomBlockType type;
        try {
            type = CustomBlockType.valueOf(rs.getString("type"));
        } catch (IllegalArgumentException e) {
            // Unknown/removed type — treat row defensively as a workbench marker.
            type = CustomBlockType.MINI_WORKBENCH;
        }
        return new PlacedBlock(
                rs.getString("world"),
                rs.getInt("x"),
                rs.getInt("y"),
                rs.getInt("z"),
                type,
                UUID.fromString(rs.getString("owner")),
                rs.getLong("created_at"));
    }
}
