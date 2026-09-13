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

import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
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

    /** How often statistic-backed quests are re-read for online players. */
    private static final long POLL_SECONDS = 30L;

    private final HomeCraftManagement plugin;
    private final QuestDao dao;
    private BukkitTask pollTask;

    public QuestService(HomeCraftManagement plugin, QuestDao dao) {
        this.plugin = plugin;
        this.dao = dao;
    }

    /**
     * Begin polling statistic-backed quests. Pushed quests need nothing here — their gameplay
     * hooks already call {@link #record}. If no configured quest is statistic-backed, no task
     * is started at all.
     */
    public void start() {
        stop();
        Quests quests = plugin.config().quests();
        if (quests == null || !quests.enabled() || quests.all().stream().noneMatch(q -> QuestStats.isPulled(q.type()))) {
            return;
        }
        pollTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                pollStats(p);
            }
        }, 20L * 10L, 20L * POLL_SECONDS);
    }

    public void stop() {
        if (pollTask != null) {
            pollTask.cancel();
            pollTask = null;
        }
    }

    /**
     * Read every statistic-backed quest for one player and bank what they have done since the
     * last poll.
     *
     * <p>Progress accumulates from the difference between polls rather than from a fixed start,
     * which is what makes the sandbox (§11 #1) work here: time in a world the economy is
     * disabled in steps the watermark forward without crediting anything, so a creative-world
     * detour neither counts nor retroactively counts when the player walks back.
     */
    private void pollStats(Player player) {
        Quests quests = plugin.config().quests();
        if (quests == null || !quests.enabled()) {
            return;
        }
        boolean counts = plugin.sandbox().allowed(player.getWorld());
        UUID id = player.getUniqueId();
        for (Quest q : quests.all()) {
            if (!QuestStats.isPulled(q.type())) {
                continue;
            }
            try {
                String period = periodKey(q.period());
                QuestDao.Progress row = dao.get(id, q.id(), period);
                if (row.claimed()) {
                    continue;
                }
                long current = QuestStats.read(player, q.type());
                if (!row.hasMark()) {
                    // First sighting this period: there is no earlier total to measure from, so
                    // set the watermark and credit nothing. Without this, a player's whole
                    // lifetime of fishing would complete a daily the moment it opened.
                    dao.advanceStat(id, q.id(), period, current, 0);
                    continue;
                }
                long delta = Math.max(0, current - row.statMark());
                int credit = counts ? (int) Math.min(Integer.MAX_VALUE, delta) : 0;
                if (delta == 0 && row.progress() < q.target()) {
                    continue; // nothing moved; skip the write entirely
                }
                int progress = dao.advanceStat(id, q.id(), period, current, credit);
                if (progress >= q.target()) {
                    if (q.reward() > 0 && (plugin.arcade() == null
                            || !plugin.arcade().canEarn(player, "quest " + q.id()))) {
                        continue;
                    }
                    if (dao.markClaimed(id, q.id(), period)) {
                        complete(player, q);
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to poll quest '" + q.id() + "': " + e.getMessage());
            }
        }
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
                if (progress < q.target()) {
                    continue;
                }
                // The claim is one-way, and the token payout can be refused by the world
                // sandbox (§11 #1) without saying so. Claiming first would burn the quest
                // for the period and pay nothing, so refuse to claim where it cannot pay.
                // Progress is already banked, so the quest claims on the next qualifying
                // action in a world where the reward actually lands.
                if (q.reward() > 0
                        && (plugin.arcade() == null || !plugin.arcade().canEarn(player, "quest " + q.id()))) {
                    continue;
                }
                if (dao.markClaimed(id, q.id(), period)) {
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
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate next = period == QuestPeriod.WEEKLY
                ? today.with(TemporalAdjusters.next(weekStartsOn()))
                : today.plusDays(1);
        return next.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() - System.currentTimeMillis();
    }

    /**
     * The reset-window key for a period: {@code d<epochDay>}, or {@code w<epochDay of the week's
     * first day>}.
     *
     * <p>The weekly key used to be {@code epochDay / 7}, which looks like a week and is one —
     * but it starts on a <b>Thursday</b>, because epoch day 0 was 1 January 1970 and that was a
     * Thursday. Nobody would choose that, and nobody noticed. Keying on the actual first day of
     * the configured week makes the rollover a date you can name.
     */
    private String periodKey(QuestPeriod period) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        return period == QuestPeriod.WEEKLY
                ? "w" + today.with(TemporalAdjusters.previousOrSame(weekStartsOn())).toEpochDay()
                : "d" + today.toEpochDay();
    }

    private DayOfWeek weekStartsOn() {
        Quests quests = plugin.config().quests();
        return quests == null || quests.weekStartsOn() == null
                ? DayOfWeek.MONDAY : quests.weekStartsOn();
    }
}
