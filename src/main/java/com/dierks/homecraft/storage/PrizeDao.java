package com.dierks.homecraft.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Prize Counter bookkeeping: purchases counted against a row's limit ({@code prize_purchases}),
 * owned cosmetics with their expiry ({@code cosmetics_owned}), and the Arcade's single numbers
 * ({@code arcade_state}: the Scratch Ticket pot, the trophy serial).
 */
public final class PrizeDao {

    /** One owned cosmetic. */
    public record Cosmetic(String id, long expiresAt, boolean enabled) {
    }

    private final Database database;

    public PrizeDao(Database database) {
        this.database = database;
    }

    /** How many of a prize the player has bought in one period. */
    public int purchases(UUID player, String prizeId, String periodKey) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT count FROM prize_purchases WHERE player=? AND prize_id=? AND period_key=?")) {
                ps.setString(1, player.toString());
                ps.setString(2, prizeId);
                ps.setString(3, periodKey);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        }
    }

    /** Count one purchase. */
    public void addPurchase(UUID player, String prizeId, String periodKey) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO prize_purchases(player, prize_id, period_key, count) VALUES(?,?,?,1) "
                            + "ON CONFLICT(player, prize_id, period_key) DO UPDATE SET count = count + 1")) {
                ps.setString(1, player.toString());
                ps.setString(2, prizeId);
                ps.setString(3, periodKey);
                ps.executeUpdate();
            }
        }
    }

    /** Whether the player has ever bought this prize (any period). */
    public boolean everBought(UUID player, String prizeId) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT 1 FROM prize_purchases WHERE player=? AND prize_id=? AND count > 0 LIMIT 1")) {
                ps.setString(1, player.toString());
                ps.setString(2, prizeId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        }
    }

    // ---- cosmetics -----------------------------------------------------------------

    /**
     * Grant a cosmetic for {@code millis}: extends an unexpired one, restarts an expired one.
     *
     * @param enableIfFirst switch it on when the player has no other cosmetic switched on
     * @return the new expiry
     */
    public long grantCosmetic(UUID player, String id, long millis, long now, boolean enableIfFirst)
            throws SQLException {
        return database.transaction(c -> {
            long current = 0;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT expires_at FROM cosmetics_owned WHERE player=? AND cosmetic_id=?")) {
                ps.setString(1, player.toString());
                ps.setString(2, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        current = rs.getLong(1);
                    }
                }
            }
            long expires = Math.max(current, now) + millis;
            boolean anyOn = false;
            if (enableIfFirst) {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT 1 FROM cosmetics_owned WHERE player=? AND enabled=1 AND expires_at > ? LIMIT 1")) {
                    ps.setString(1, player.toString());
                    ps.setLong(2, now);
                    try (ResultSet rs = ps.executeQuery()) {
                        anyOn = rs.next();
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO cosmetics_owned(player, cosmetic_id, expires_at, enabled) VALUES(?,?,?,?) "
                            + "ON CONFLICT(player, cosmetic_id) DO UPDATE SET expires_at = excluded.expires_at, "
                            + "enabled = CASE WHEN ? = 1 THEN 1 ELSE enabled END")) {
                ps.setString(1, player.toString());
                ps.setString(2, id);
                ps.setLong(3, expires);
                ps.setInt(4, enableIfFirst && !anyOn ? 1 : 0);
                ps.setInt(5, enableIfFirst && !anyOn ? 1 : 0);
                ps.executeUpdate();
            }
            return expires;
        });
    }

    /** Every cosmetic the player has, expired or not. */
    public List<Cosmetic> cosmetics(UUID player) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            List<Cosmetic> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT cosmetic_id, expires_at, enabled FROM cosmetics_owned WHERE player=? ORDER BY cosmetic_id")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Cosmetic(rs.getString(1), rs.getLong(2), rs.getInt(3) != 0));
                    }
                }
            }
            return out;
        }
    }

    /** Switch on exactly one cosmetic (or none, with {@code id == null}). */
    public void enableOnly(UUID player, String id) throws SQLException {
        database.transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE cosmetics_owned SET enabled = 0 WHERE player=?")) {
                ps.setString(1, player.toString());
                ps.executeUpdate();
            }
            if (id != null) {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE cosmetics_owned SET enabled = 1 WHERE player=? AND cosmetic_id=?")) {
                    ps.setString(1, player.toString());
                    ps.setString(2, id);
                    ps.executeUpdate();
                }
            }
            return null;
        });
    }

    // ---- arcade_state --------------------------------------------------------------

    /** A stored number, or {@code fallback} when it has never been written. */
    public long state(String key, long fallback) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement("SELECT value FROM arcade_state WHERE key=?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : fallback;
                }
            }
        }
    }

    public void setState(String key, long value) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO arcade_state(key, value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
                ps.setString(1, key);
                ps.setLong(2, value);
                ps.executeUpdate();
            }
        }
    }

    /** Add to a stored number (starting from {@code start} if unset); returns the new value. */
    public long addState(String key, long delta, long start) throws SQLException {
        return database.transaction(c -> {
            long v;
            try (PreparedStatement ps = c.prepareStatement("SELECT value FROM arcade_state WHERE key=?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    v = rs.next() ? rs.getLong(1) : start;
                }
            }
            v += delta;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO arcade_state(key, value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
                ps.setString(1, key);
                ps.setLong(2, v);
                ps.executeUpdate();
            }
            return v;
        });
    }
}
