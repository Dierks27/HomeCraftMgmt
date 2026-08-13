package com.dierks.homecraft.arcade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig.Quest;
import com.dierks.homecraft.config.PluginConfig.QuestPeriod;
import com.dierks.homecraft.config.PluginConfig.QuestType;
import com.dierks.homecraft.config.PluginConfig.Quests;
import com.dierks.homecraft.storage.QuestDao;
import com.dierks.homecraft.util.Text;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.UUID;

/**
 * Daily/weekly quests (Phase 11, §3.9): a small, admin-configurable set of repeatable
 * objectives ("sell $X to the market", "print a Mini", "open a crate") that award
 * tokens on completion, resetting each day/week. A completed quest pays out exactly
 * once per period (the {@code claimed} guard), routing the reward through the shared
 * {@link ArcadeService#award} path so every earn gets the same "+N token" feedback.
 *
 * <p>Progress is recorded from the existing gameplay hooks — no new listener — so a
 * disabled quest system (or an unmatched event) costs nothing. All in-game currency.
 */
public final class QuestService {

    private static final long MS_PER_DAY = 86_400_000L;

    private final HomeCraftManagement plugin;
    private final QuestDao dao;

    public QuestService(HomeCraftManagement plugin, QuestDao dao) {
        this.plugin = plugin;
        this.dao = dao;
    }

    /**
     * Record {@code amount} of progress toward every enabled quest of {@code type} for
     * this player. Count-style objectives pass {@code amount == 1}; value-style ones
     * (e.g. money sold) pass the value. Completing a quest grants its tokens once.
     */
    public void record(Player player, QuestType type, long amount) {
        if (player == null || amount <= 0) {
            return;
        }
        Quests quests = plugin.config().quests();
        if (quests == null || !quests.enabled()) {
            return;
        }
        int delta = (int) Math.min(Integer.MAX_VALUE, amount);
        UUID id = player.getUniqueId();
        for (Quest q : quests.all()) {
            if (q.type() != type) {
                continue;
            }
            try {
                String period = periodKey(q.period());
                if (dao.get(id, q.id(), period).claimed()) {
                    continue; // already earned this period — don't even bump progress
                }
                int progress = dao.addProgress(id, q.id(), period, delta);
                if (progress >= q.target() && dao.markClaimed(id, q.id(), period)) {
                    complete(player, q);
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to record quest '" + q.id() + "': " + e.getMessage());
            }
        }
    }

    private void complete(Player player, Quest q) {
        player.sendMessage(Text.of("&a✔ Quest complete: &f" + q.display()));
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.2f);
        if (plugin.arcade() != null) {
            plugin.arcade().award(player, q.reward(), "quest"); // "+N token" feedback lives here
        }
    }

    // ---- read side (for the Quests GUI) --------------------------------------

    /** How far the player is on a quest this period (clamped to the target for display). */
    public int progress(UUID player, Quest q) {
        try {
            return (int) Math.min(q.target(), dao.get(player, q.id(), periodKey(q.period())).progress());
        } catch (SQLException e) {
            return 0;
        }
    }

    /** True once the player has completed (and been paid for) this quest this period. */
    public boolean done(UUID player, Quest q) {
        try {
            return dao.get(player, q.id(), periodKey(q.period())).claimed();
        } catch (SQLException e) {
            return false;
        }
    }

    /** Milliseconds until this period rolls over (for a "resets in …" countdown). */
    public long msToReset(QuestPeriod period) {
        long now = System.currentTimeMillis();
        long day = now / MS_PER_DAY;
        return period == QuestPeriod.WEEKLY
                ? ((day / 7) + 1) * 7 * MS_PER_DAY - now
                : (day + 1) * MS_PER_DAY - now;
    }

    /** The reset-window key for a period: {@code d<epochDay>} or {@code w<epochWeek>}. */
    private String periodKey(QuestPeriod period) {
        long day = System.currentTimeMillis() / MS_PER_DAY;
        return period == QuestPeriod.WEEKLY ? "w" + (day / 7) : "d" + day;
    }
}
