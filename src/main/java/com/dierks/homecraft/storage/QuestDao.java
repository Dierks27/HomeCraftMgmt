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

    /** A player's standing on one quest in one period: how far, and whether paid out. */
    public record Progress(int progress, boolean claimed) {
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
                    "SELECT progress, claimed FROM quest_progress "
                            + "WHERE player=? AND quest_id=? AND period_key=?")) {
                ps.setString(1, player.toString());
                ps.setString(2, questId);
                ps.setString(3, periodKey);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return new Progress(rs.getInt("progress"), rs.getInt("claimed") != 0);
                    }
                    return new Progress(0, false);
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
