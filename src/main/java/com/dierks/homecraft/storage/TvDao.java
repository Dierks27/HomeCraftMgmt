package com.dierks.homecraft.storage;

import org.bukkit.Location;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Data access for TV blocks (Round 3a): the stream URL bound to each placed TV,
 * keyed by block location.
 */
public final class TvDao {

    private final Database database;

    public TvDao(Database database) {
        this.database = database;
    }

    private Connection conn() {
        return database.connection();
    }

    /** The stream URL bound to a TV block, or null if none is set. */
    public String getUrl(Location loc) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT url FROM tv_urls WHERE world=? AND x=? AND y=? AND z=?")) {
                bindLoc(ps, loc);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        }
    }

    /** Bind (or replace) the stream URL for a TV block. */
    public void setUrl(Location loc, String url) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO tv_urls(world,x,y,z,url) VALUES(?,?,?,?,?) "
                            + "ON CONFLICT(world,x,y,z) DO UPDATE SET url=excluded.url")) {
                bindLoc(ps, loc);
                ps.setString(5, url);
                ps.executeUpdate();
            }
        }
    }

    /** Remove a TV's URL binding (on break). */
    public void deleteAt(Location loc) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM tv_urls WHERE world=? AND x=? AND y=? AND z=?")) {
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
