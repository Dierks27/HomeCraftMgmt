package com.dierks.homecraft.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Data access for {@code achievements_unlocked} — one-time milestone unlocks per
 * player (Phase 9, §3.9). Each (player, achievement) fires at most once.
 */
public final class AchievementDao {

    private final Database database;

    public AchievementDao(Database database) {
        this.database = database;
    }

    private Connection conn() {
        return database.connection();
    }

    /**
     * Record an unlock. Returns true only if this is the first time (a new row was
     * inserted), so callers pay out the reward exactly once.
     */
    public boolean unlock(UUID player, String achievement, long now) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT OR IGNORE INTO achievements_unlocked(player, achievement, unlocked_at) "
                            + "VALUES(?,?,?)")) {
                ps.setString(1, player.toString());
                ps.setString(2, achievement);
                ps.setLong(3, now);
                return ps.executeUpdate() > 0;
            }
        }
    }
}
