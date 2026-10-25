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
 * owned cosmetics with their expiry ({@code cosmetics_owned}), the Arcade's single numbers
 * ({@code arcade_state}: the Scratch Ticket pot, the trophy serial, the Keepers' numbers) and the trail switch
 * kept as a player pref ({@code game_prefs}).
 *
 * <p>Since schema v37 {@code cosmetics_owned} also holds the rights to worn hats and caps ({@code kind = 'HAT'},
 * {@link LookDao}). Everything here that switches trails on and off reads and writes only {@code kind = 'TRAIL'}
 * rows, so a hat's right is never "the trail that's on" and never switched (SHOP-V2-SPEC §3.2, D28).
 */
public final class PrizeDao {

    /** One owned cosmetic: a trail, or (since v37) a hat's right ({@code kind} TRAIL or HAT). */
    public record Cosmetic(String id, long expiresAt, boolean enabled, String kind) {
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

    /**
     * Take back one counted purchase ({@link #addPurchase}): a buy that committed but whose hand-over failed (a
     * command that didn't run). The row goes when its count reaches 0.
     */
    public void removePurchase(UUID player, String prizeId, String periodKey) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE prize_purchases SET count = count - 1 WHERE player=? AND prize_id=? AND period_key=?");
                 PreparedStatement gone = c.prepareStatement(
                         "DELETE FROM prize_purchases WHERE player=? AND prize_id=? AND period_key=? AND count <= 0")) {
                for (PreparedStatement st : List.of(ps, gone)) {
                    st.setString(1, player.toString());
                    st.setString(2, prizeId);
                    st.setString(3, periodKey);
                    st.executeUpdate();
                }
            }
        }
    }

    /**
     * Claim a lifetime prize for the player, once: insert its {@code 'life'} row, or do nothing when
     * one is already there. This is a collector's one gate, written BEFORE any token moves, so a
     * second confirm (or a second giver) finds the row and is refused with nothing charged.
     *
     * @return true when this call inserted the row
     */
    public boolean claimOnce(UUID player, String prizeId) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO prize_purchases(player, prize_id, period_key, count) VALUES(?, ?, 'life', 1) "
                            + "ON CONFLICT(player, prize_id, period_key) DO NOTHING")) {
                ps.setString(1, player.toString());
                ps.setString(2, prizeId);
                return ps.executeUpdate() == 1;
            }
        }
    }

    /**
     * Undo a {@link #claimOnce}: the charge or the hand-over after it failed. Also how a collector cap's claim leaves
     * its giver in a trade (SHOP-V2-SPEC D41), which needs exactly one row to have gone.
     *
     * @return how many rows it deleted (0 or 1)
     */
    public int unclaim(UUID player, String prizeId) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM prize_purchases WHERE player=? AND prize_id=? AND period_key='life'")) {
                ps.setString(1, player.toString());
                ps.setString(2, prizeId);
                return ps.executeUpdate();
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
     * Grant a trail for {@code millis}: extends an unexpired one, restarts an expired one (the crate's and the
     * collectors' path). A restart starts a new run ({@code since} = now) and a new end clears the heads-up
     * already sent for the old one.
     *
     * @param enableIfFirst switch it on when the player has no other trail switched on
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
                        "SELECT 1 FROM cosmetics_owned WHERE player=? AND enabled=1 AND expires_at > ? "
                                + "AND kind='TRAIL' LIMIT 1")) {
                    ps.setString(1, player.toString());
                    ps.setLong(2, now);
                    try (ResultSet rs = ps.executeQuery()) {
                        anyOn = rs.next();
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO cosmetics_owned(player, cosmetic_id, expires_at, enabled, kind, since) "
                            + "VALUES(?,?,?,?,'TRAIL',?) "
                            + "ON CONFLICT(player, cosmetic_id) DO UPDATE SET expires_at = excluded.expires_at, "
                            + "enabled = CASE WHEN ? = 1 THEN 1 ELSE enabled END, "
                            + "since = CASE WHEN cosmetics_owned.expires_at <= ? THEN ? ELSE cosmetics_owned.since END, "
                            + "notice_for = CASE WHEN cosmetics_owned.expires_at <> excluded.expires_at THEN 0 "
                            + "ELSE cosmetics_owned.notice_for END, "
                            + "notice_stage = CASE WHEN cosmetics_owned.expires_at <> excluded.expires_at THEN 0 "
                            + "ELSE cosmetics_owned.notice_stage END")) {
                ps.setString(1, player.toString());
                ps.setString(2, id);
                ps.setLong(3, expires);
                ps.setInt(4, enableIfFirst && !anyOn ? 1 : 0);
                ps.setLong(5, now);
                ps.setInt(6, enableIfFirst && !anyOn ? 1 : 0);
                ps.setLong(7, now);
                ps.setLong(8, now);
                ps.executeUpdate();
            }
            return expires;
        });
    }

    /** Every cosmetic the player has, expired or not, trails and hats' rights alike ({@link Cosmetic#kind}). */
    public List<Cosmetic> cosmetics(UUID player) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            List<Cosmetic> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT cosmetic_id, expires_at, enabled, kind FROM cosmetics_owned WHERE player=? "
                            + "ORDER BY cosmetic_id")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Cosmetic(rs.getString(1), rs.getLong(2), rs.getInt(3) != 0, rs.getString(4)));
                    }
                }
            }
            return out;
        }
    }

    /**
     * Switch on exactly one trail (or none, with {@code id == null}). Only trail rows: a hat's right is never
     * switched on or off ({@code enabled} stays 0 on a HAT row, so a 0.37 jar never sees one as a trail).
     */
    public void enableOnly(UUID player, String id) throws SQLException {
        database.transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE cosmetics_owned SET enabled = 0 WHERE player=? AND kind='TRAIL'")) {
                ps.setString(1, player.toString());
                ps.executeUpdate();
            }
            if (id != null) {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE cosmetics_owned SET enabled = 1 WHERE player=? AND cosmetic_id=? AND kind='TRAIL'")) {
                    ps.setString(1, player.toString());
                    ps.setString(2, id);
                    ps.executeUpdate();
                }
            }
            return null;
        });
    }

    // ---- preferences (game_prefs) ------------------------------------------------------------

    /**
     * A player's preference, or {@code null} when unset: the hide-others switch ({@code arcade.trails.hide_others}).
     * The same table and shape as {@code GamesDao.pref}.
     */
    public String pref(UUID player, String key) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT value FROM game_prefs WHERE player = ? AND pref = ?")) {
                ps.setString(1, player.toString());
                ps.setString(2, key);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        }
    }

    /** Set a player's preference; {@code null} removes it. The same table and shape as {@code GamesDao.setPref}. */
    public void setPref(UUID player, String key, String value) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            if (value == null) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM game_prefs WHERE player = ? AND pref = ?")) {
                    ps.setString(1, player.toString());
                    ps.setString(2, key);
                    ps.executeUpdate();
                }
                return;
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO game_prefs(player, pref, value) VALUES(?,?,?) "
                            + "ON CONFLICT(player, pref) DO UPDATE SET value = excluded.value")) {
                ps.setString(1, player.toString());
                ps.setString(2, key);
                ps.setString(3, value);
                ps.executeUpdate();
            }
        }
    }

    /**
     * Every player's value of one preference (0.38.2: the Firework Remote's sets, when a launcher breaks, and the Show
     * Maps' ids at start), by player. A row whose player isn't a UUID is skipped. A read, no schema change: the table
     * is a handful of rows a player.
     */
    public java.util.Map<UUID, String> prefsNamed(String key) throws SQLException {
        java.util.Map<UUID, String> out = new java.util.LinkedHashMap<>();
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement("SELECT player, value FROM game_prefs WHERE pref = ?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        try {
                            out.put(UUID.fromString(rs.getString(1)), rs.getString(2));
                        } catch (IllegalArgumentException | NullPointerException e) {
                            // not a player's row
                        }
                    }
                }
            }
        }
        return out;
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
