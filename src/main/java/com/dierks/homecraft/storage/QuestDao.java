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

    /**
     * A quest the player has progress on and hasn't been paid for, in one period.
     *
     * @param periodKey {@code d<day>} or {@code w<week>}
     */
    public record Held(String questId, String periodKey, int progress) {
    }

    /**
     * The player's unpaid rows with some progress, of these quests, in ANY period: where
     * {@code QuestService.settle} finds a game quest reached where tokens couldn't be paid (the
     * Games world), even once its day or week is over.
     */
    public java.util.List<Held> unclaimed(UUID player, java.util.Collection<String> questIds) throws SQLException {
        java.util.List<Held> out = new java.util.ArrayList<>();
        if (player == null || questIds == null || questIds.isEmpty()) {
            return out;
        }
        java.util.List<String> ids = new java.util.ArrayList<>(new java.util.LinkedHashSet<>(questIds));
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            in.append(i == 0 ? "?" : ",?");
        }
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT quest_id, period_key, progress FROM quest_progress "
                            + "WHERE player=? AND claimed=0 AND progress>0 AND quest_id IN (" + in + ") "
                            + "ORDER BY period_key, quest_id")) {
                ps.setString(1, player.toString());
                for (int i = 0; i < ids.size(); i++) {
                    ps.setString(i + 2, ids.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Held(rs.getString(1), rs.getString(2), rs.getInt(3)));
                    }
                }
            }
        }
        return out;
    }

    // ---- draws (quests v2) ----------------------------------------------------------

    /**
     * Forget every player's draw for one period, so each draws again from the pool on next use.
     * Progress and claimed rows are left alone.
     *
     * @return how many draw rows were removed
     */
    public int clearAssignments(String periodKey) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM quest_assignments WHERE period_key=?")) {
                ps.setString(1, periodKey);
                return ps.executeUpdate();
            }
        }
    }

    /** The player's drawn quest ids for one period, by slot; empty if nothing is drawn yet. */
    public java.util.List<String> assignments(UUID player, String periodKey) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            java.util.List<String> out = new java.util.ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT quest_id FROM quest_assignments WHERE player=? AND period_key=? ORDER BY slot")) {
                ps.setString(1, player.toString());
                ps.setString(2, periodKey);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(rs.getString(1));
                    }
                }
            }
            return out;
        }
    }

    /**
     * Store a fresh draw, unless one already exists (a second caller racing the first keeps the
     * first draw).
     *
     * @return the draw now stored
     */
    public java.util.List<String> assign(UUID player, String periodKey, java.util.List<String> questIds)
            throws SQLException {
        return database.transaction(c -> {
            java.util.List<String> existing = assignments(player, periodKey);
            if (!existing.isEmpty()) {
                return existing;
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO quest_assignments(player, period_key, slot, quest_id) VALUES(?,?,?,?)")) {
                for (int i = 0; i < questIds.size(); i++) {
                    ps.setString(1, player.toString());
                    ps.setString(2, periodKey);
                    ps.setInt(3, i);
                    ps.setString(4, questIds.get(i));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return new java.util.ArrayList<>(questIds);
        });
    }

    /** Swap the quest in one slot of a draw (Quest Reroll). */
    public boolean replace(UUID player, String periodKey, String oldId, String newId) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE quest_assignments SET quest_id=? WHERE player=? AND period_key=? AND quest_id=?")) {
                ps.setString(1, newId);
                ps.setString(2, player.toString());
                ps.setString(3, periodKey);
                ps.setString(4, oldId);
                return ps.executeUpdate() > 0;
            }
        }
    }

    /** Record a biome entered in a quest period; true the first time this period. */
    public boolean addPeriodBiome(UUID player, String periodKey, String biome) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT OR IGNORE INTO quest_biomes(player, period_key, biome) VALUES(?,?,?)")) {
                ps.setString(1, player.toString());
                ps.setString(2, periodKey);
                ps.setString(3, biome);
                return ps.executeUpdate() > 0;
            }
        }
    }
}
