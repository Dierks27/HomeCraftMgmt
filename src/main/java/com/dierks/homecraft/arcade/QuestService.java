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
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Daily and weekly quests (§3.9), v2: each player DRAWS their own few from a pool.
 *
 * <p>The old board was the same four dailies and five weeklies for everybody, every day. Now the
 * config holds a pool per period ({@code daily_pool} / {@code weekly_pool}) and each player gets
 * {@code daily_draw} / {@code weekly_draw} of them the first time they are needed in a period — on
 * joining or opening the board — stored in {@code quest_assignments}, so a restart does not
 * reshuffle anyone. No two quests in one draw share a type ("catch fish" and "catch 40 fish"
 * would be one quest twice). A Quest Reroll from the Prize Counter swaps one unfinished daily for
 * one they were not dealt.
 *
 * <p>Only a player's own draw counts: fishing does nothing for a fish quest you were not dealt.
 * Pulled types are read from vanilla statistics as before (see {@link QuestStats}); pushed types
 * come from gameplay hooks and {@code QuestListener}. Every type respects the economy sandbox.
 */
public final class QuestService {

    /** How often statistic-backed quests and biomes are re-read for online players. */
    private static final long POLL_SECONDS = 30L;

    private final HomeCraftManagement plugin;
    private final QuestDao dao;
    private BukkitTask pollTask;

    public QuestService(HomeCraftManagement plugin, QuestDao dao) {
        this.plugin = plugin;
        this.dao = dao;
    }

    public void start() {
        stop();
        Quests quests = plugin.config().quests();
        if (quests == null || !quests.enabled()) {
            return;
        }
        pollTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                pollStats(p);
                pollBiome(p);
            }
        }, 20L * 10L, 20L * POLL_SECONDS);
    }

    public void stop() {
        if (pollTask != null) {
            pollTask.cancel();
            pollTask = null;
        }
    }

    // ---- draws -----------------------------------------------------------------------

    /**
     * Draw {@code n} quests from a pool with no two sharing a type. Fewer when the pool has fewer
     * distinct types. Pure, so it can be tested.
     */
    public static List<Quest> draw(List<Quest> pool, int n, Random random) {
        List<Quest> shuffled = new ArrayList<>(pool);
        Collections.shuffle(shuffled, random);
        List<Quest> out = new ArrayList<>();
        Set<QuestType> types = EnumSet.noneOf(QuestType.class);
        for (Quest q : shuffled) {
            if (out.size() >= n) {
                break;
            }
            if (types.add(q.type())) {
                out.add(q);
            }
        }
        return out;
    }

    /** The player's quests for a period, drawing them now if this is the first time they're needed. */
    public List<Quest> assigned(UUID player, QuestPeriod period) {
        Quests quests = plugin.config().quests();
        if (quests == null || !quests.enabled()) {
            return List.of();
        }
        String key = periodKey(period);
        try {
            List<String> ids = dao.assignments(player, key);
            if (ids.isEmpty()) {
                List<Quest> drawn = draw(quests.byPeriod(period), quests.draw(period), ThreadLocalRandom.current());
                if (drawn.isEmpty()) {
                    return List.of();
                }
                List<String> newIds = new ArrayList<>();
                for (Quest q : drawn) {
                    newIds.add(q.id());
                }
                ids = dao.assign(player, key, newIds);
            }
            List<Quest> out = new ArrayList<>();
            for (String id : ids) {
                Quest q = quests.byId(period, id);
                if (q != null) {
                    out.add(q); // a quest an admin removed from the pool just drops off
                }
            }
            return out;
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to read quest draw: " + e.getMessage());
            return List.of();
        }
    }

    /** Both periods' quests for a player (drawn on first need). */
    private List<Quest> assignedAll(UUID player) {
        List<Quest> out = new ArrayList<>(assigned(player, QuestPeriod.DAILY));
        out.addAll(assigned(player, QuestPeriod.WEEKLY));
        return out;
    }

    /** On join: make sure today's and this week's draws exist, so the board is ready. */
    public void onJoin(Player player) {
        assignedAll(player.getUniqueId());
    }

    /**
     * Quest Reroll: swap one of today's unfinished dailies for a daily the player was not dealt,
     * keeping the no-shared-type rule.
     *
     * @return the new quest, or null if there is nothing to swap it for (nothing is charged then)
     */
    public Quest reroll(Player player, String questId) {
        UUID id = player.getUniqueId();
        List<Quest> current = assigned(id, QuestPeriod.DAILY);
        Quest old = null;
        for (Quest q : current) {
            if (q.id().equalsIgnoreCase(questId)) {
                old = q;
            }
        }
        if (old == null || done(id, old)) {
            return null;
        }
        Quest next = rerollCandidate(player, old);
        if (next == null) {
            return null;
        }
        try {
            if (!dao.replace(id, periodKey(QuestPeriod.DAILY), old.id(), next.id())) {
                return null;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Quest reroll failed: " + e.getMessage());
            return null;
        }
        return next;
    }

    /** A daily the player could get instead of {@code old}, or null when there is none. */
    public Quest rerollCandidate(Player player, Quest old) {
        List<Quest> current = assigned(player.getUniqueId(), QuestPeriod.DAILY);
        Set<String> dealt = new HashSet<>();
        Set<QuestType> otherTypes = EnumSet.noneOf(QuestType.class);
        for (Quest q : current) {
            dealt.add(q.id());
            if (!q.id().equals(old.id())) {
                otherTypes.add(q.type());
            }
        }
        List<Quest> candidates = new ArrayList<>();
        for (Quest q : plugin.config().quests().byPeriod(QuestPeriod.DAILY)) {
            if (!dealt.contains(q.id()) && !otherTypes.contains(q.type())) {
                candidates.add(q);
            }
        }
        return candidates.isEmpty() ? null : candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    }

    /** Dailies that could be rerolled right now (dealt today, not finished). */
    public List<Quest> rerollable(Player player) {
        List<Quest> out = new ArrayList<>();
        for (Quest q : assigned(player.getUniqueId(), QuestPeriod.DAILY)) {
            if (!done(player.getUniqueId(), q)) {
                out.add(q);
            }
        }
        return out;
    }

    // ---- progress --------------------------------------------------------------------

    /**
     * Read the player's statistic-backed quests and bank what they have done since the last poll.
     *
     * <p>Progress accumulates from the difference between polls rather than from a fixed start,
     * which is what makes the sandbox (§11 #1) work here: time in a world the economy is disabled
     * in steps the watermark forward without crediting anything.
     */
    private void pollStats(Player player) {
        Quests quests = plugin.config().quests();
        if (quests == null || !quests.enabled()) {
            return;
        }
        boolean counts = plugin.sandbox().allowed(player.getWorld());
        UUID id = player.getUniqueId();
        for (Quest q : assignedAll(id)) {
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
                    // First sighting this period: set the watermark and credit nothing, or a
                    // player's whole lifetime of fishing would complete a daily the moment it opened.
                    dao.advanceStat(id, q.id(), period, current, 0);
                    continue;
                }
                long delta = Math.max(0, current - row.statMark());
                int credit = counts ? (int) Math.min(Integer.MAX_VALUE, delta) : 0;
                if (delta == 0 && row.progress() < q.target()) {
                    continue;
                }
                int progress = dao.advanceStat(id, q.id(), period, current, credit);
                if (progress >= q.target()) {
                    if (q.reward() > 0 && (plugin.tokens() == null
                            || !plugin.tokens().canEarn(player, "quest " + q.id()))) {
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
     * Note the biome a player is standing in: a new one this day or week counts toward
     * VISIT_BIOMES for that period, and a new one ever counts toward the biome achievement.
     */
    private void pollBiome(Player player) {
        if (!plugin.sandbox().allowed(player.getWorld())) {
            return;
        }
        String biome;
        try {
            biome = player.getLocation().getBlock().getBiome().getKey().toString();
        } catch (Throwable t) {
            return;
        }
        UUID id = player.getUniqueId();
        try {
            if (dao.addPeriodBiome(id, periodKey(QuestPeriod.DAILY), biome)) {
                record(player, QuestType.VISIT_BIOMES, 1, QuestPeriod.DAILY);
            }
            if (dao.addPeriodBiome(id, periodKey(QuestPeriod.WEEKLY), biome)) {
                record(player, QuestType.VISIT_BIOMES, 1, QuestPeriod.WEEKLY);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to record a biome: " + e.getMessage());
        }
        if (plugin.achievements() != null) {
            plugin.achievements().visitBiome(player, biome);
        }
    }

    /**
     * Record {@code amount} of progress toward the player's dealt quests of {@code type}.
     * Count-style objectives pass 1; value-style ones (money sold) pass the value.
     */
    public void record(Player player, QuestType type, long amount) {
        record(player, type, amount, null);
    }

    /** As {@link #record(Player, QuestType, long)}, limited to one period when {@code only} is set. */
    public void record(Player player, QuestType type, long amount, QuestPeriod only) {
        if (player == null || amount <= 0) {
            return;
        }
        Quests quests = plugin.config().quests();
        if (quests == null || !quests.enabled()) {
            return;
        }
        if (!plugin.sandbox().allowed(player.getWorld())) {
            return; // the sandbox: nothing counts in a world the economy is off in
        }
        int delta = (int) Math.min(Integer.MAX_VALUE, amount);
        UUID id = player.getUniqueId();
        for (Quest q : assignedAll(id)) {
            if (q.type() != type || (only != null && q.period() != only)) {
                continue;
            }
            try {
                String period = periodKey(q.period());
                if (dao.get(id, q.id(), period).claimed()) {
                    continue;
                }
                int progress = dao.addProgress(id, q.id(), period, delta);
                if (progress < q.target()) {
                    continue;
                }
                // The claim is one-way and the payout can be refused by the sandbox; do not claim
                // where it cannot pay. Progress is banked, so it claims on the next action.
                if (q.reward() > 0 && (plugin.tokens() == null || !plugin.tokens().canEarn(player, "quest " + q.id()))) {
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
        if (plugin.tokens() != null) {
            plugin.tokens().award(player, q.reward(), TokenService.Source.QUEST, q.display());
        }
        if (plugin.achievements() != null) {
            plugin.achievements().increment(player, "quests_done", 1);
        }
    }

    // ---- read side (for the Quests GUI) --------------------------------------------

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

    /** Milliseconds until this period rolls over (for a "resets in …" countdown), local time. */
    public long msToReset(QuestPeriod period) {
        return period == QuestPeriod.WEEKLY
                ? plugin.clock().msUntilNextWeek(weekStartsOn())
                : plugin.clock().msUntilNextDay();
    }

    /**
     * The reset-window key for a period: {@code d<local epochDay>}, or {@code w<local epochDay of
     * the week's first day>}. Local, not UTC: a UTC day ended at 7 PM in Minnesota, in the middle
     * of family play time.
     */
    private String periodKey(QuestPeriod period) {
        return period == QuestPeriod.WEEKLY
                ? "w" + plugin.clock().weekKey(weekStartsOn())
                : "d" + plugin.clock().dayKey();
    }

    private DayOfWeek weekStartsOn() {
        Quests quests = plugin.config().quests();
        return quests == null || quests.weekStartsOn() == null
                ? DayOfWeek.MONDAY : quests.weekStartsOn();
    }
}
