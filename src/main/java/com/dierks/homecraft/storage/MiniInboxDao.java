package com.dierks.homecraft.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Per-player inbox delivered on login: auction/sale text notifications and any
 * items owed (an auction win, or a Mini returned because its auction got no
 * bids) that couldn't be handed over while the player was offline.
 */
public final class MiniInboxDao {

    private final Database database;

    public MiniInboxDao(Database database) {
        this.database = database;
    }

    private Connection conn() {
        return database.connection();
    }

    public void addNotification(UUID player, String message, long now) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO mini_notifications(player, message, created_at) VALUES(?,?,?)")) {
                ps.setString(1, player.toString());
                ps.setString(2, message);
                ps.setLong(3, now);
                ps.executeUpdate();
            }
        }
    }

    /** Return and clear all queued notifications for a player. */
    public List<String> drainNotifications(UUID player) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            List<String> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT message FROM mini_notifications WHERE player = ? ORDER BY id ASC")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(rs.getString(1));
                    }
                }
            }
            if (!out.isEmpty()) {
                try (PreparedStatement del = c.prepareStatement(
                        "DELETE FROM mini_notifications WHERE player = ?")) {
                    del.setString(1, player.toString());
                    del.executeUpdate();
                }
            }
            return out;
        }
    }

    public void addPending(UUID player, String itemB64, String reason, long now) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO mini_pending(player, item_b64, reason, created_at) VALUES(?,?,?,?)")) {
                ps.setString(1, player.toString());
                ps.setString(2, itemB64);
                ps.setString(3, reason);
                ps.setLong(4, now);
                ps.executeUpdate();
            }
        }
    }

    /** Return and clear all queued item payloads (Base64) for a player. */
    public List<String> drainPending(UUID player) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            List<String> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT item_b64 FROM mini_pending WHERE player = ? ORDER BY id ASC")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(rs.getString(1));
                    }
                }
            }
            if (!out.isEmpty()) {
                try (PreparedStatement del = c.prepareStatement(
                        "DELETE FROM mini_pending WHERE player = ?")) {
                    del.setString(1, player.toString());
                    del.executeUpdate();
                }
            }
            return out;
        }
    }
}
