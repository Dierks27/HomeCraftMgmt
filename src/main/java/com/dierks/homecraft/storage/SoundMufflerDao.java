package com.dierks.homecraft.storage;

import com.dierks.homecraft.muffler.Muffler;
import com.dierks.homecraft.muffler.MufflerPos;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The {@code sound_mufflers} table: each placed muffler's settings. What the block IS (and who
 * owns it) stays in {@code placed_blocks}, like every other HomeCraft block; this is only what it
 * has been told to hush.
 */
public final class SoundMufflerDao {

    private final Database database;

    public SoundMufflerDao(Database database) {
        this.database = database;
    }

    private Connection conn() {
        return database.connection();
    }

    /** Insert or replace one muffler's settings. */
    public void save(Muffler m, long now) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO sound_mufflers(world, x, y, z, owner, enabled, radius, quiet_percent, rules, updated_at) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,?) "
                            + "ON CONFLICT(world, x, y, z) DO UPDATE SET owner = excluded.owner, "
                            + "enabled = excluded.enabled, radius = excluded.radius, "
                            + "quiet_percent = excluded.quiet_percent, rules = excluded.rules, "
                            + "updated_at = excluded.updated_at")) {
                MufflerPos p = m.pos();
                ps.setString(1, p.world());
                ps.setInt(2, p.x());
                ps.setInt(3, p.y());
                ps.setInt(4, p.z());
                ps.setString(5, m.owner().toString());
                ps.setInt(6, m.enabled() ? 1 : 0);
                ps.setInt(7, m.radius());
                ps.setInt(8, m.quietPercent());
                ps.setString(9, m.encodeRules());
                ps.setLong(10, now);
                ps.executeUpdate();
            }
        }
    }

    /** Every muffler on the server. A row that can't be read is skipped, not fatal. */
    public List<Muffler> all() throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT world, x, y, z, owner, enabled, radius, quiet_percent, rules FROM sound_mufflers");
                 ResultSet rs = ps.executeQuery()) {
                List<Muffler> out = new ArrayList<>();
                while (rs.next()) {
                    UUID owner;
                    try {
                        owner = UUID.fromString(rs.getString("owner"));
                    } catch (IllegalArgumentException | NullPointerException e) {
                        continue;
                    }
                    Muffler.Rules rules = Muffler.decodeRules(rs.getString("rules"));
                    out.add(new Muffler(
                            new MufflerPos(rs.getString("world"), rs.getInt("x"), rs.getInt("y"), rs.getInt("z")),
                            owner, rs.getInt("enabled") != 0, rs.getInt("radius"), rs.getInt("quiet_percent"),
                            rules.groups(), rules.sounds()));
                }
                return out;
            }
        }
    }

    /** Forget the muffler at a position. */
    public boolean delete(MufflerPos p) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM sound_mufflers WHERE world = ? AND x = ? AND y = ? AND z = ?")) {
                ps.setString(1, p.world());
                ps.setInt(2, p.x());
                ps.setInt(3, p.y());
                ps.setInt(4, p.z());
                return ps.executeUpdate() > 0;
            }
        }
    }
}
