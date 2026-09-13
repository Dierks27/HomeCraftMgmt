package com.dierks.homecraft.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Data access for {@code quest_progress} — per-player, per-period progress toward a
 * daily/weekly quest, plus a one-shot {@code claimed} flag so a completed quest pays
 * out exactly once (Phase 11, §3.9). The table was provisioned back in the v16
 * migration; this DAO is the first code to use it.
 *
 * <p>{@code period_key} namespaces the reset window (e.g. {@code "d20315"} for a day,
 * {@code "w2902"} for a week), so progress resets naturally when the key rolls over —
 * old rows are simply never read again (same pattern as {@code market_daily_sells}).
 * Quest rewards are in-game tokens only; this table holds no prizes.
 */
public final class QuestDao {

    /**
     * A player's standing on one quest in one period.
     *
     * @param statMark for a statistic-backed quest, the lifetime total last observed — a moving
     *                 watermark, not a fixed start. Progress accumulates from the difference
     *                 between polls, so a stretch the economy sandbox excludes can be stepped
     *                 over rather than banked. -1 means never observed.
     */
    public record Progress(int progress, boolean claimed, long statMark) {
        /** True once a statistic-backed quest has a total to measure the next poll against. */
        public boolean hasMark() {
            return statMark >= 0;
        }
    }

    private final Database database;

    public QuestDao(Database database) {
        this.database = database;
    }

    private Connection conn() {
        return database.connection();
    }

    /** Current progress + claimed flag for a quest/period, or a zeroed state if none yet. */
    public Progress get(UUID player, String questId, String periodKey) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT progress, claimed, stat_mark FROM quest_progress "
                            + "WHERE player=? AND quest_id=? AND period_key=?")) {
                ps.setString(1, player.toString());
                ps.setString(2, questId);
                ps.setString(3, periodKey);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return new Progress(rs.getInt("progress"), rs.getInt("claimed") != 0,
                                rs.getLong("stat_mark"));
                    }
                    return new Progress(0, false, -1);
                }
            }
        }
    }

    /** Add {@code delta} to a player's progress (creating the row if needed); returns the new total. */
    public int addProgress(UUID player, String questId, String periodKey, int delta) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO quest_progress(player, quest_id, period_key, progress, claimed) "
                            + "VALUES(?,?,?,?,0) "
                            + "ON CONFLICT(player, quest_id, period_key) DO UPDATE SET "
                            + "progress = progress + excluded.progress")) {
                ps.setString(1, player.toString());
                ps.setString(2, questId);
                ps.setString(3, periodKey);
                ps.setInt(4, Math.max(0, delta));
                ps.executeUpdate();
            }
            return get(player, questId, periodKey).progress();
        }
    }

    /**
     * Move a statistic-backed quest's watermark to {@code mark} and add {@code delta} to its
     * progress, in one write. Pass {@code delta == 0} to step the watermark without crediting
     * anything — which is both how a quest starts (no prior total to measure from) and how a
     * stretch in an economy-disabled world is skipped.
     *
     * @return the progress now stored
     */
    public int advanceStat(UUID player, String questId, String periodKey, long mark, int delta)
            throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO quest_progress(player, quest_id, period_key, progress, claimed, stat_mark) "
                            + "VALUES(?,?,?,?,0,?) "
                            + "ON CONFLICT(player, quest_id, period_key) DO UPDATE SET "
                            + "progress = progress + excluded.progress, stat_mark = excluded.stat_mark")) {
                ps.setString(1, player.toString());
                ps.setString(2, questId);
                ps.setString(3, periodKey);
                ps.setInt(4, Math.max(0, delta));
                ps.setLong(5, Math.max(0, mark));
                ps.executeUpdate();
            }
            return get(player, questId, periodKey).progress();
        }
    }

    /**
     * Atomically flip {@code claimed} from 0→1 for a quest/period. Returns true only for
     * the caller that actually made the flip, so a completed quest pays out exactly once
     * even if two events land in the same tick.
     */
    public boolean markClaimed(UUID player, String questId, String periodKey) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE quest_progress SET claimed=1 "
                            + "WHERE player=? AND quest_id=? AND period_key=? AND claimed=0")) {
                ps.setString(1, player.toString());
                ps.setString(2, questId);
                ps.setString(3, periodKey);
                return ps.executeUpdate() > 0;
            }
        }
    }
}
