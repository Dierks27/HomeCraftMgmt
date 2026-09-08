package com.dierks.homecraft.storage;

import org.bukkit.Location;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Naturally spawned wild-Mini heads (the NATURAL_SPAWN trigger): where each one
 * sits, the exact minted item it holds, and when it despawns. Persisted so an
 * untouched spawn still expires (and retires its copy) across a restart, and so
 * nothing leaks after a crash.
 */
public final class MiniSpawnDao {

    /** One live natural spawn. */
    public record Spawn(long id, String world, int x, int y, int z, String miniId, String uid,
                        long mintNumber, String itemB64, long spawnedAt, long expiresAt) {
    }

    private final Database database;

    public MiniSpawnDao(Database database) {
        this.database = database;
    }

    private Connection conn() {
        return database.connection();
    }

    public void insert(Location loc, String miniId, String uid, long mintNumber, String itemB64,
                       long spawnedAt, long expiresAt) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO mini_spawns(world, x, y, z, mini_id, uid, mint_number, item_b64, spawned_at, expires_at) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(world, x, y, z) DO UPDATE SET "
                            + "mini_id = excluded.mini_id, uid = excluded.uid, mint_number = excluded.mint_number, "
                            + "item_b64 = excluded.item_b64, spawned_at = excluded.spawned_at, expires_at = excluded.expires_at")) {
                ps.setString(1, loc.getWorld().getName());
                ps.setInt(2, loc.getBlockX());
                ps.setInt(3, loc.getBlockY());
                ps.setInt(4, loc.getBlockZ());
                ps.setString(5, miniId);
                ps.setString(6, uid);
                ps.setLong(7, mintNumber);
                ps.setString(8, itemB64);
                ps.setLong(9, spawnedAt);
                ps.setLong(10, expiresAt);
                ps.executeUpdate();
            }
        }
    }

    public List<Spawn> all() throws SQLException {
        Connection c = conn();
        synchronized (c) {
            List<Spawn> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, world, x, y, z, mini_id, uid, mint_number, item_b64, spawned_at, expires_at FROM mini_spawns");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(map(rs));
                }
            }
            return out;
        }
    }

    public Optional<Spawn> at(Location loc) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, world, x, y, z, mini_id, uid, mint_number, item_b64, spawned_at, expires_at "
                            + "FROM mini_spawns WHERE world = ? AND x = ? AND y = ? AND z = ?")) {
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

    public boolean deleteAt(Location loc) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM mini_spawns WHERE world = ? AND x = ? AND y = ? AND z = ?")) {
                ps.setString(1, loc.getWorld().getName());
                ps.setInt(2, loc.getBlockX());
                ps.setInt(3, loc.getBlockY());
                ps.setInt(4, loc.getBlockZ());
                return ps.executeUpdate() > 0;
            }
        }
    }

    private Spawn map(ResultSet rs) throws SQLException {
        return new Spawn(rs.getLong("id"), rs.getString("world"), rs.getInt("x"), rs.getInt("y"), rs.getInt("z"),
                rs.getString("mini_id"), rs.getString("uid"), rs.getLong("mint_number"), rs.getString("item_b64"),
                rs.getLong("spawned_at"), rs.getLong("expires_at"));
    }
}
