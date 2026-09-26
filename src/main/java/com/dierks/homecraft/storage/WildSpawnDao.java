package com.dierks.homecraft.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Live wild-hunt spawns ({@code wild_spawns}) and armed Mini Lures ({@code hunt_lures}).
 *
 * <p>A row is a <b>blueprint</b>, not a Mini: which Mini, the grade and finish it will be minted
 * with, who it spawned near and where they stood, when it goes, and how many hints have been
 * sent. Nothing is minted until somebody catches it, so a spawn that gets away burns no mint
 * number and no cap slot. Persisted so a hunt's timer and hints survive a restart.
 */
public final class WildSpawnDao {

    /** One live wild spawn. */
    public record Blueprint(long id, String world, int x, int y, int z, String miniId, String grade,
                            String finish, UUID target, int anchorX, int anchorZ, long spawnedAt,
                            long expiresAt, int hintStage) {
    }

    private final Database database;

    public WildSpawnDao(Database database) {
        this.database = database;
    }

    /** Store a new spawn; returns its row id. */
    public long insert(Blueprint b) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO wild_spawns(world, x, y, z, mini_id, grade, finish, target, anchor_x, anchor_z, "
                            + "spawned_at, expires_at, hint_stage) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?) "
                            + "ON CONFLICT(world, x, y, z) DO UPDATE SET mini_id = excluded.mini_id, "
                            + "grade = excluded.grade, finish = excluded.finish, target = excluded.target, "
                            + "anchor_x = excluded.anchor_x, anchor_z = excluded.anchor_z, "
                            + "spawned_at = excluded.spawned_at, expires_at = excluded.expires_at, "
                            + "hint_stage = excluded.hint_stage",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, b.world());
                ps.setInt(2, b.x());
                ps.setInt(3, b.y());
                ps.setInt(4, b.z());
                ps.setString(5, b.miniId());
                ps.setString(6, b.grade());
                ps.setString(7, b.finish());
                ps.setString(8, b.target() == null ? null : b.target().toString());
                ps.setInt(9, b.anchorX());
                ps.setInt(10, b.anchorZ());
                ps.setLong(11, b.spawnedAt());
                ps.setLong(12, b.expiresAt());
                ps.setInt(13, b.hintStage());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    return keys.next() ? keys.getLong(1) : 0;
                }
            }
        }
    }

    public List<Blueprint> all() throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            List<Blueprint> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM wild_spawns ORDER BY spawned_at");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String target = rs.getString("target");
                    UUID t = null;
                    try {
                        t = target == null ? null : UUID.fromString(target);
                    } catch (IllegalArgumentException ignored) {
                        // a malformed target is just "nobody in particular"
                    }
                    out.add(new Blueprint(rs.getLong("id"), rs.getString("world"), rs.getInt("x"), rs.getInt("y"),
                            rs.getInt("z"), rs.getString("mini_id"), rs.getString("grade"), rs.getString("finish"),
                            t, rs.getInt("anchor_x"), rs.getInt("anchor_z"), rs.getLong("spawned_at"),
                            rs.getLong("expires_at"), rs.getInt("hint_stage")));
                }
            }
            return out;
        }
    }

    /** @return true if a row was there to delete — so of two racing removals only one wins */
    public boolean delete(String world, int x, int y, int z) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM wild_spawns WHERE world = ? AND x = ? AND y = ? AND z = ?")) {
                ps.setString(1, world);
                ps.setInt(2, x);
                ps.setInt(3, y);
                ps.setInt(4, z);
                return ps.executeUpdate() > 0;
            }
        }
    }

    public void setHintStage(long id, int stage) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement("UPDATE wild_spawns SET hint_stage = ? WHERE id = ?")) {
                ps.setInt(1, stage);
                ps.setLong(2, id);
                ps.executeUpdate();
            }
        }
    }

    // ---- lures -----------------------------------------------------------------------

    /** Arm a lure for {@code player}; false if they already have one armed. */
    public boolean armLure(UUID player, long now) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT OR IGNORE INTO hunt_lures(player, armed_at) VALUES(?, ?)")) {
                ps.setString(1, player.toString());
                ps.setLong(2, now);
                return ps.executeUpdate() > 0;
            }
        }
    }

    /** Spend (or cancel) a player's armed lure; false if they had none. */
    public boolean disarmLure(UUID player) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM hunt_lures WHERE player = ?")) {
                ps.setString(1, player.toString());
                return ps.executeUpdate() > 0;
            }
        }
    }

    /** Every armed lure, earliest first: player → when it was armed. */
    public Map<UUID, Long> lures() throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            Map<UUID, Long> out = new LinkedHashMap<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT player, armed_at FROM hunt_lures ORDER BY armed_at, player");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        out.put(UUID.fromString(rs.getString(1)), rs.getLong(2));
                    } catch (IllegalArgumentException ignored) {
                        // skip a malformed row
                    }
                }
            }
            return out;
        }
    }
}
