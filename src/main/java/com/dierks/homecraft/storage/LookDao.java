package com.dierks.homecraft.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The wardrobe (SHOP-V2-SPEC §3.2, schema v37): the rights to worn looks, one {@code cosmetics_owned} row per
 * player and look. A trail's row is the whole thing, as it always was; a hat's or cap's row is the right, and the
 * item is only a key to it. The same table as {@link PrizeDao}'s trails, read and written here in full so the
 * looks code never collides with the Prize Counter's.
 *
 * <p>Every call takes the connection's lock, and each joins a caller's {@link Database#transaction}, so a buy
 * writes the right and takes the tokens as one.
 */
public final class LookDao {

    /** {@code kind} of a trail's row (every row written before v37 is one). */
    public static final String TRAIL = "TRAIL";
    /** {@code kind} of a hat's or a cap's right. Its {@code enabled} is always 0. */
    public static final String HAT = "HAT";

    /**
     * One right.
     *
     * @param id          the prize row's id
     * @param kind        {@link #TRAIL} or {@link #HAT}
     * @param expiresAt   when it ends (epoch millis; a collector or Keeper is 36,500 days on)
     * @param since       when the current run started (0 = a row from before v37)
     * @param enabled     a trail that is switched on (never a hat)
     * @param noticeFor   the {@code expiresAt} the last heads-up was sent for
     * @param noticeStage what was sent: 0 none, 7 the 7-day one, 1 the 1-day one, -1 the end
     * @param givenBy     the UUID of whoever paid for the current run as a present, '' when bought for themselves
     */
    public record Look(String id, String kind, long expiresAt, long since, boolean enabled, long noticeFor,
                       int noticeStage, String givenBy) {
    }

    private static final String COLUMNS =
            "cosmetic_id, kind, expires_at, since, enabled, notice_for, notice_stage, given_by";

    private final Database database;

    public LookDao(Database database) {
        this.database = database;
    }

    /** Every right the player has, ended or not, by id. */
    public List<Look> looks(UUID player) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            List<Look> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT " + COLUMNS + " FROM cosmetics_owned WHERE player=? ORDER BY cosmetic_id")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(read(rs));
                    }
                }
            }
            return out;
        }
    }

    /** One right, or null when the player never had it. */
    public Look look(UUID player, String id) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT " + COLUMNS + " FROM cosmetics_owned WHERE player=? AND cosmetic_id=?")) {
                ps.setString(1, player.toString());
                ps.setString(2, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? read(rs) : null;
                }
            }
        }
    }

    /**
     * Upsert one right. A new row starts switched off. An existing one takes the new end, run start, kind and
     * giver, and forgets the heads-up sent for its old end; whether a trail is switched on is kept.
     */
    public void write(UUID player, String id, String kind, long expiresAt, long since, String givenBy)
            throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO cosmetics_owned(player, cosmetic_id, expires_at, enabled, kind, since, notice_for, "
                            + "notice_stage, given_by) VALUES(?,?,?,0,?,?,0,0,?) "
                            + "ON CONFLICT(player, cosmetic_id) DO UPDATE SET expires_at = excluded.expires_at, "
                            + "since = excluded.since, kind = excluded.kind, given_by = excluded.given_by, "
                            + "notice_for = 0, notice_stage = 0")) {
                ps.setString(1, player.toString());
                ps.setString(2, id);
                ps.setLong(3, expiresAt);
                ps.setString(4, kind);
                ps.setLong(5, since);
                ps.setString(6, givenBy == null ? "" : givenBy);
                ps.executeUpdate();
            }
        }
    }

    /**
     * The start that brings v37: every row from before it ({@code since} 0) whose end is already past is marked as
     * having had its end said, so the first 0.38 join doesn't announce ends 0.37 already had (red-team finding F1;
     * 0.37 said its own one line while the player walked). A row still running keeps its end, which is quiet
     * ({@code LookRules.quietEnd}).
     *
     * @return how many rows were marked
     */
    public int endsBeforeUpgrade(long now) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement("UPDATE cosmetics_owned SET notice_stage=-1, "
                    + "notice_for=expires_at WHERE since=0 AND expires_at<=?")) {
                ps.setLong(1, now);
                return ps.executeUpdate();
            }
        }
    }

    /** Record a heads-up as sent: {@code stage} (7, 1 or -1) for the end {@code forExpiresAt}. */
    public void notice(UUID player, String id, int stage, long forExpiresAt) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE cosmetics_owned SET notice_stage=?, notice_for=? WHERE player=? AND cosmetic_id=?")) {
                ps.setInt(1, stage);
                ps.setLong(2, forExpiresAt);
                ps.setString(3, player.toString());
                ps.setString(4, id);
                ps.executeUpdate();
            }
        }
    }

    /**
     * Move a right's end to {@code at} (an admin's end now, or a test end a few days on); its run start and
     * giver stay. A new end makes the heads-up already sent for the old one stale by itself.
     */
    public void end(UUID player, String id, long at) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE cosmetics_owned SET expires_at=? WHERE player=? AND cosmetic_id=?")) {
                ps.setLong(1, at);
                ps.setString(2, player.toString());
                ps.setString(3, id);
                ps.executeUpdate();
            }
        }
    }

    private static Look read(ResultSet rs) throws SQLException {
        String givenBy = rs.getString(8);
        return new Look(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getInt(5) != 0,
                rs.getLong(6), rs.getInt(7), givenBy == null ? "" : givenBy);
    }
}
