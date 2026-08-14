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
 * Data access for {@code displays} — in-game economy displays (sign boards,
 * holograms, map-TVs) bound to a market commodity. Each is anchored to a block
 * position and re-rendered from live market data on a timer; persistence lets a
 * display survive restarts and re-render on chunk load.
 */
public final class DisplayDao {

    public static final String SIGN = "SIGN";
    public static final String HOLOGRAM = "HOLOGRAM";
    /** Legacy: the retired map-on-item-frame board. Kept so old rows stay removable. */
    public static final String MAPTV = "MAPTV";
    /** A flat, wall-mounted {@code TextDisplay} price panel (facing = wall face, data = scale). */
    public static final String TV = "TV";

    public record Display(long id, String kind, String world, int x, int y, int z, String itemId,
                          int cols, int rows, String facing, String data, UUID owner, long createdAt) {

        public boolean matches(Location loc) {
            return loc.getWorld() != null && loc.getWorld().getName().equals(world)
                    && loc.getBlockX() == x && loc.getBlockY() == y && loc.getBlockZ() == z;
        }
    }

    private final Database database;

    public DisplayDao(Database database) {
        this.database = database;
    }

    private Connection conn() {
        return database.connection();
    }

    /** Insert or replace the display of a given kind at a location; returns its id. */
    public long upsert(String kind, Location loc, String itemId, int cols, int rows,
                       String facing, String data, UUID owner, long now) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO displays(kind,world,x,y,z,item_id,cols,rows,facing,data,owner,created_at) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?) "
                            + "ON CONFLICT(world,x,y,z,kind) DO UPDATE SET item_id=excluded.item_id, "
                            + "cols=excluded.cols, rows=excluded.rows, facing=excluded.facing, "
                            + "data=excluded.data, owner=excluded.owner, created_at=excluded.created_at",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, kind);
                ps.setString(2, loc.getWorld().getName());
                ps.setInt(3, loc.getBlockX());
                ps.setInt(4, loc.getBlockY());
                ps.setInt(5, loc.getBlockZ());
                ps.setString(6, itemId);
                ps.setInt(7, cols);
                ps.setInt(8, rows);
                ps.setString(9, facing);
                ps.setString(10, data);
                ps.setString(11, owner != null ? owner.toString() : null);
                ps.setLong(12, now);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    return rs.next() ? rs.getLong(1) : -1;
                }
            }
        }
    }

    public List<Display> all() throws SQLException {
        return query("SELECT * FROM displays", null);
    }

    public List<Display> byKind(String kind) throws SQLException {
        return query("SELECT * FROM displays WHERE kind=?", kind);
    }

    public Optional<Display> at(String kind, Location loc) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM displays WHERE kind=? AND world=? AND x=? AND y=? AND z=?")) {
                ps.setString(1, kind);
                ps.setString(2, loc.getWorld().getName());
                ps.setInt(3, loc.getBlockX());
                ps.setInt(4, loc.getBlockY());
                ps.setInt(5, loc.getBlockZ());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                }
            }
        }
    }

    /** The display row with this id, or empty if it's been removed. */
    public Optional<Display> byId(long id) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM displays WHERE id=?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                }
            }
        }
    }

    public void updateData(long id, String data) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement("UPDATE displays SET data=? WHERE id=?")) {
                ps.setString(1, data);
                ps.setLong(2, id);
                ps.executeUpdate();
            }
        }
    }

    public boolean deleteAt(String kind, Location loc) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM displays WHERE kind=? AND world=? AND x=? AND y=? AND z=?")) {
                ps.setString(1, kind);
                ps.setString(2, loc.getWorld().getName());
                ps.setInt(3, loc.getBlockX());
                ps.setInt(4, loc.getBlockY());
                ps.setInt(5, loc.getBlockZ());
                return ps.executeUpdate() > 0;
            }
        }
    }

    public void deleteById(long id) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM displays WHERE id=?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
        }
    }

    private List<Display> query(String sql, String kindArg) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            List<Display> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                if (kindArg != null) {
                    ps.setString(1, kindArg);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(map(rs));
                    }
                }
            }
            return out;
        }
    }

    private Display map(ResultSet rs) throws SQLException {
        String owner = rs.getString("owner");
        return new Display(rs.getLong("id"), rs.getString("kind"),
                rs.getString("world"), rs.getInt("x"), rs.getInt("y"), rs.getInt("z"),
                rs.getString("item_id"), rs.getInt("cols"), rs.getInt("rows"),
                rs.getString("facing"), rs.getString("data"),
                owner != null ? UUID.fromString(owner) : null, rs.getLong("created_at"));
    }
}
