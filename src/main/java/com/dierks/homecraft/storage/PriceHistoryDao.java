package com.dierks.homecraft.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Price/stock history for each commodity. Periodic snapshots feed the Phase 5 web
 * dashboard's charts; a small recent-rows query is exposed so the test commands can
 * show that history is accumulating.
 *
 * <p>The website's long charts (7 and 30 days) read it through {@link #sampled}, one
 * point per epoch-aligned bucket, and {@link #pruneBefore} keeps the table to
 * {@code market.price_history.keep_days}, a bounded batch at a time.
 */
public final class PriceHistoryDao {

    /** One historical snapshot. */
    public record Snapshot(String itemId, double price, long stock, long recordedAt) {
    }

    /**
     * Most rows one {@link #pruneBefore} call deletes. A first prune of a table that grew
     * for months finishes over several snapshot ticks instead of stalling one.
     */
    public static final int PRUNE_BATCH = 50_000;

    private static final long MS_PER_DAY = 86_400_000L;

    private final Database database;

    public PriceHistoryDao(Database database) {
        this.database = database;
    }

    private Connection conn() {
        return database.connection();
    }

    public void record(String itemId, double price, long stock, long recordedAt) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO market_price_history(item_id, price, stock, recorded_at) VALUES(?,?,?,?)")) {
                ps.setString(1, itemId);
                ps.setDouble(2, price);
                ps.setLong(3, stock);
                ps.setLong(4, recordedAt);
                ps.executeUpdate();
            }
        }
    }

    /** Most recent snapshots for an item, newest first. */
    public List<Snapshot> recent(String itemId, int limit) throws SQLException {
        List<Snapshot> out = new ArrayList<>();
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT item_id, price, stock, recorded_at FROM market_price_history "
                            + "WHERE item_id = ? ORDER BY recorded_at DESC LIMIT ?")) {
                ps.setString(1, itemId);
                ps.setInt(2, Math.max(1, limit));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Snapshot(
                                rs.getString("item_id"),
                                rs.getDouble("price"),
                                rs.getLong("stock"),
                                rs.getLong("recorded_at")));
                    }
                }
            }
        }
        return out;
    }

    /**
     * One point per epoch-aligned bucket: the latest snapshot in each bucket of
     * {@code bucketMs} (bucket = {@code recorded_at / bucketMs}) with
     * {@code fromInclusive <= recorded_at <= toInclusive}, oldest first. The upper bound is the
     * caller's "now": a row stamped later (the clock was stepped back after it was written) would
     * otherwise add a point past the window, and one more than the window has buckets. Each point is a real row: its
     * price, stock and exact {@code recorded_at}. Buckets with no snapshot are simply absent.
     *
     * <p>Relies on SQLite's bare-column rule: with a single {@code MAX()} aggregate, the other
     * selected columns come from the row holding that maximum. Rides the
     * {@code (item_id, recorded_at)} index.
     *
     * @param bucketMs bucket width in ms; must be positive
     */
    public List<Snapshot> sampled(String itemId, long fromInclusive, long toInclusive, long bucketMs)
            throws SQLException {
        if (bucketMs <= 0) {
            throw new IllegalArgumentException("bucketMs must be positive: " + bucketMs);
        }
        List<Snapshot> out = new ArrayList<>();
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT item_id, price, stock, MAX(recorded_at) AS t FROM market_price_history "
                            + "WHERE item_id = ? AND recorded_at >= ? AND recorded_at <= ? "
                            + "GROUP BY recorded_at / ? ORDER BY t")) {
                ps.setString(1, itemId);
                ps.setLong(2, fromInclusive);
                ps.setLong(3, toInclusive);
                ps.setLong(4, bucketMs); // bound as an integer, so the division is integer division
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Snapshot(
                                rs.getString("item_id"),
                                rs.getDouble("price"),
                                rs.getLong("stock"),
                                rs.getLong("t")));
                    }
                }
            }
        }
        return out;
    }

    /**
     * Delete up to {@code limit} snapshots (all items) recorded strictly before {@code cutoff},
     * oldest rows first. Call again on a later tick if it returns {@code limit}: the rest are
     * still there.
     *
     * @return rows deleted
     */
    public int pruneBefore(long cutoff, int limit) throws SQLException {
        if (limit <= 0 || cutoff == Long.MIN_VALUE) {
            return 0; // nothing asked for, or nothing can be older than the minimum
        }
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM market_price_history WHERE id IN "
                            + "(SELECT id FROM market_price_history WHERE recorded_at < ? ORDER BY id LIMIT ?)")) {
                ps.setLong(1, cutoff);
                ps.setInt(2, limit);
                return ps.executeUpdate();
            }
        }
    }

    /**
     * The prune cutoff for {@code market.price_history.keep_days}: rows recorded before it go,
     * a row exactly at it stays.
     *
     * @return {@code now - keepDays} days, or {@link Long#MIN_VALUE} (prune nothing) when
     *         {@code keepDays <= 0}
     */
    public static long keepCutoff(long now, int keepDays) {
        if (keepDays <= 0) {
            return Long.MIN_VALUE;
        }
        return now - keepDays * MS_PER_DAY;
    }
}
