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
 *
 * <p><b>The game quests</b> (EXTRAS E4: {@link QuestType#game()}) are pushed by the skill games
 * through {@link GamesProgress}. Courses are played in the Games world, which is not an economy
 * world, so these count where the games pay tokens instead ({@link GamesProgress#countsHere}: an
 * economy, Games or play world, never creative), and one finished there is banked and paid by
 * {@link #settle} once the player is back where tokens can be paid (every poll, and on a world
 * change). They are dealt only while a game that can push them is open ({@link GamesProgress#dealable}),
 * so nobody draws "Play 3 arcade cabinets" with the games switched off.
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
                settle(p);
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
        return draw(pool, n, random, q -> true);
    }

    /**
     * {@link #draw(List, int, Random)} from only the rows {@code dealable} accepts: a game quest
     * whose games are closed is left in the pool but never dealt. Pure, so it can be tested.
     */
    public static List<Quest> draw(List<Quest> pool, int n, Random random, java.util.function.Predicate<Quest> dealable) {
        List<Quest> shuffled = new ArrayList<>();
        for (Quest q : pool) {
            if (dealable == null || dealable.test(q)) {
                shuffled.add(q);
            }
        }
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
                List<Quest> drawn = draw(quests.byPeriod(period), quests.draw(period), ThreadLocalRandom.current(),
                        this::dealable);
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
            if (!dealt.contains(q.id()) && !otherTypes.contains(q.type()) && dealable(q)) {
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
        if (!countsHere(player, type)) {
            return; // the sandbox: nothing counts in a world the economy is off in (a game quest: see countsHere)
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
                // where it cannot pay. Progress is banked, so it claims on the next action (a game
                // quest reached in the Games world: quietly, and settle() pays it back home).
                if (q.reward() > 0 && type.game() && !plugin.sandbox().allowed(player.getWorld())) {
                    continue;
                }
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

    /**
     * Pay every pushed quest the player has already reached but that couldn't be paid where it was
     * reached (a course finished in the Games world, say): now, if tokens can be paid here. Run on
     * every poll and on a world change; one that is paid is claimed first, so it pays once.
     *
     * <p>A game quest reached in the Games world stays owed after its day or week is over: a player
     * who finished "Finish a course" there at 8 PM and logged off is paid the next time they are
     * back home, whenever that is ({@link #due}).
     */
    public void settle(Player player) {
        Quests quests = plugin.config().quests();
        if (player == null || quests == null || !quests.enabled()) {
            return;
        }
        if (plugin.tokens() == null || !plugin.sandbox().allowed(player.getWorld())) {
            return; // quietly: canEarn would log a refusal every 30 seconds
        }
        UUID id = player.getUniqueId();
        Set<String> dealtNow = new HashSet<>();
        Set<String> ids = new HashSet<>();
        for (Quest q : assignedAll(id)) {
            dealtNow.add(q.id());
            ids.add(q.id());
        }
        for (Quest q : quests.all()) {
            if (q.type().game()) {
                ids.add(q.id()); // one reached in an earlier day or week, still owed
            }
        }
        List<Due> due;
        try {
            due = due(dao.unclaimed(id, ids), periodKey(QuestPeriod.DAILY), periodKey(QuestPeriod.WEEKLY), dealtNow,
                    quests::byId, QuestStats::isPulled);
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to read the quests to settle: " + e.getMessage());
            return;
        }
        for (Due d : due) {
            Quest q = d.quest();
            try {
                if (q.reward() > 0 && !plugin.tokens().canEarn(player, "quest " + q.id())) {
                    continue;
                }
                if (dao.markClaimed(id, q.id(), d.periodKey())) {
                    complete(player, q);
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to settle quest '" + q.id() + "': " + e.getMessage());
            }
        }
    }

    /**
     * A reached, unpaid quest {@link #settle} pays now, and the period it was reached in.
     *
     * @param periodKey the row's {@code d<day>} or {@code w<week>}: the one {@code markClaimed} flips
     */
    public record Due(Quest quest, String periodKey) {
    }

    /**
     * Which unpaid rows {@link #settle} pays: a pushed quest (never one the poll reads from the
     * statistics) that has reached its target, dealt now in the current day or week, or a GAME
     * quest from an earlier one (reached in the Games world and still owed; any other kind is paid
     * where it is reached, in its own period). A row of an unknown period or a quest no longer in
     * the pool is left alone. Pure, so it is tested.
     *
     * @param dayKey   today's period key
     * @param weekKey  this week's period key
     * @param dealtNow the ids of the player's quests today and this week
     * @param byId     the pool: a period's quest by id, or {@code null}
     * @param pulled   whether a type is read from the statistics (the poll pays those)
     */
    public static List<Due> due(List<QuestDao.Held> rows, String dayKey, String weekKey, Set<String> dealtNow,
                                java.util.function.BiFunction<QuestPeriod, String, Quest> byId,
                                java.util.function.Predicate<QuestType> pulled) {
        List<Due> out = new ArrayList<>();
        if (rows == null) {
            return out;
        }
        for (QuestDao.Held row : rows) {
            String key = row.periodKey() == null ? "" : row.periodKey();
            QuestPeriod period = key.startsWith("w") ? QuestPeriod.WEEKLY : key.startsWith("d") ? QuestPeriod.DAILY
                    : null;
            Quest q = period == null ? null : byId.apply(period, row.questId());
            if (q == null || pulled.test(q.type()) || row.progress() < q.target()) {
                continue;
            }
            boolean current = key.equals(period == QuestPeriod.WEEKLY ? weekKey : dayKey);
            if (current ? dealtNow != null && dealtNow.contains(q.id()) : q.type().game()) {
                out.add(new Due(q, key));
            }
        }
        return out;
    }

    /**
     * Whether {@code type} counts for the player where they are: a game quest where the games pay
     * tokens ({@link GamesProgress#countsHere}), everything else only where the economy runs.
     */
    private boolean countsHere(Player player, QuestType type) {
        if (type != null && type.game()) {
            return GamesProgress.countsHere(plugin.games(), player);
        }
        return plugin.sandbox().allowed(player.getWorld());
    }

    /** Whether a quest may be dealt now: a game quest only while a game that pushes it is open. */
    private boolean dealable(Quest q) {
        return q == null || !q.type().game() || GamesProgress.dealable(plugin.games(), q.type());
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
    /**
     * Everyone draws today's and this week's quests again from the current pools (after the pools
     * change). Progress and claimed rows stay.
     *
     * @return draw rows removed
     */
    public int redrawCurrent() {
        try {
            return dao.clearAssignments(periodKey(QuestPeriod.DAILY)) + dao.clearAssignments(periodKey(QuestPeriod.WEEKLY));
        } catch (java.sql.SQLException e) {
            plugin.getLogger().warning("Could not clear this period's quest draws: " + e.getMessage());
            return 0;
        }
    }

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
