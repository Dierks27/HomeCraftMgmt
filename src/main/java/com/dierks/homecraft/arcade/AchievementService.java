package com.dierks.homecraft.arcade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.config.PluginConfig.AchievementDef;
import com.dierks.homecraft.config.PluginConfig.AchievementType;
import com.dierks.homecraft.mini.Grade;
import com.dierks.homecraft.storage.AchievementDao;
import com.dierks.homecraft.util.Text;
import net.kyori.adventure.title.Title;
import org.bukkit.Sound;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * One-time achievements (§3.9), v2: a config list of milestones in groups, each paying tokens
 * the first time it is reached.
 *
 * <p>Seven kinds: EVENT (a first-time moment, fired where it happens), STAT (a lifetime vanilla
 * statistic), COUNTER (plugin counters — wild finds, quests finished, deliveries, crates,
 * jackpots, biomes), COLLECTION (distinct Minis owned), GRADE / FINISH (print a Mint, own a
 * Shiny), STREAK and BALANCE. Each is evaluated when its thing happens, on join, and — for the
 * ones nothing pushes (STAT, BALANCE, COLLECTION, FINISH) — on the five-minute token tick.
 *
 * <p>The six original ids are kept, so every unlock a player already has still counts.
 */
public final class AchievementService {

    private final HomeCraftManagement plugin;
    private final AchievementDao dao;

    public AchievementService(HomeCraftManagement plugin, AchievementDao dao) {
        this.plugin = plugin;
        this.dao = dao;
    }

    private Collection<AchievementDef> defs() {
        return plugin.config().achievements().values();
    }

    // ---- firing -------------------------------------------------------------------------

    /** Unlock one achievement if it is enabled and not unlocked yet (EVENT, or any reached one). */
    public void tryAward(Player player, String id) {
        AchievementDef def = plugin.config().achievements().get(id);
        if (def == null || !def.enabled() || player == null) {
            return;
        }
        // An unlock fires once per player, ever, and the payout can be refused by the world
        // sandbox (§11 #1) without saying so. Unlocking first would spend the only chance at this
        // reward and pay nothing, so do not unlock where it cannot pay; it fires next time its
        // trigger runs in an economy-enabled world. A reward of 0 unlocks anywhere.
        if (def.reward() > 0 && (plugin.tokens() == null || !plugin.tokens().canEarn(player, "achievement " + id))) {
            return;
        }
        try {
            if (dao.unlock(player.getUniqueId(), id, System.currentTimeMillis())) {
                celebrate(player, def);
                if (plugin.tokens() != null) {
                    plugin.tokens().award(player, def.reward(), TokenService.Source.ACHIEVEMENT, def.display());
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to record achievement '" + id + "': " + e.getMessage());
        }
    }

    private void celebrate(Player player, AchievementDef def) {
        player.sendMessage(Text.of("&6★ Achievement! &e" + def.display()));
        try {
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.0f);
            player.showTitle(Title.title(Text.of("&6Achievement!"), Text.of("&e" + def.display()),
                    Title.Times.times(Duration.ofMillis(250), Duration.ofMillis(2000), Duration.ofMillis(500))));
        } catch (Throwable ignored) {
            // cosmetic
        }
    }

    /** Add to a plugin counter and unlock whatever COUNTER achievements it now reaches. */
    public void increment(Player player, String counter, long by) {
        if (player == null || by <= 0) {
            return;
        }
        try {
            long value = dao.addCounter(player.getUniqueId(), counter, by);
            checkCounter(player, counter, value);
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to count '" + counter + "': " + e.getMessage());
        }
    }

    /** A biome the player is standing in; a new one ever counts toward the biome achievements. */
    public void visitBiome(Player player, String biome) {
        try {
            if (dao.addBiome(player.getUniqueId(), biome, System.currentTimeMillis())) {
                checkCounter(player, "biomes", dao.biomeCount(player.getUniqueId()));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to record a biome for achievements: " + e.getMessage());
        }
    }

    private void checkCounter(Player player, String counter, long value) {
        for (AchievementDef def : defs()) {
            if (def.type() == AchievementType.COUNTER && counter.equalsIgnoreCase(def.key()) && value >= def.target()) {
                tryAward(player, def.id());
            }
        }
    }

    /**
     * A Mini has just been minted to the player: the first-Mini moment, a Mint-grade print, a
     * Shiny, and the collection count.
     *
     * @param printed true when it came off a Printer (GRADE achievements are about printing)
     */
    public void onMint(Player player, ItemStack item, boolean printed) {
        tryAward(player, "first_mini");
        if (printed) {
            tryAward(player, "first_print");
        }
        checkItem(player, item, printed);
        checkCollection(player);
    }

    private void checkItem(Player player, ItemStack item, boolean printed) {
        if (item == null) {
            return;
        }
        Grade grade = plugin.miniService().gradeOf(item);
        boolean shiny = plugin.miniService().isShiny(item);
        for (AchievementDef def : defs()) {
            if (def.type() == AchievementType.GRADE && printed && grade.name().equalsIgnoreCase(def.key())) {
                tryAward(player, def.id());
            }
            if (def.type() == AchievementType.FINISH && shiny && "SHINY".equalsIgnoreCase(def.key())) {
                tryAward(player, def.id());
            }
        }
    }

    private void checkCollection(Player player) {
        int owned = plugin.miniService().ownedIds(player.getUniqueId()).size();
        for (AchievementDef def : defs()) {
            if (def.type() == AchievementType.COLLECTION && owned >= def.target()) {
                tryAward(player, def.id());
            }
        }
    }

    /** The login streak just moved. */
    public void checkStreak(Player player, int streak) {
        for (AchievementDef def : defs()) {
            if (def.type() == AchievementType.STREAK && streak >= def.target()) {
                tryAward(player, def.id());
            }
        }
    }

    /** Check the money-balance achievements. Call after money changes. */
    public void checkBalance(Player player) {
        if (!plugin.economy().isEnabled()) {
            return;
        }
        double bal = plugin.economy().balance(player);
        for (AchievementDef def : defs()) {
            if (def.type() == AchievementType.BALANCE && bal >= def.target()) {
                tryAward(player, def.id());
            }
        }
    }

    /**
     * Everything nothing pushes: statistics, balance, collection, a Shiny sitting in the
     * inventory, the streak. Run on join and on the five-minute tick.
     */
    public void sweep(Player player) {
        Set<String> have = unlocked(player.getUniqueId());
        for (AchievementDef def : defs()) {
            if (!def.enabled() || have.contains(def.id())) {
                continue;
            }
            switch (def.type()) {
                case STAT, STREAK, COLLECTION, BALANCE, COUNTER -> {
                    if (progress(player, def) >= def.target()) {
                        tryAward(player, def.id());
                    }
                }
                case FINISH -> {
                    for (ItemStack it : player.getInventory().getContents()) {
                        if (it != null && plugin.miniService().isMini(it) && plugin.miniService().isShiny(it)) {
                            tryAward(player, def.id());
                            break;
                        }
                    }
                }
                default -> {
                    // EVENT and GRADE fire where they happen
                }
            }
        }
    }

    // ---- reading (for the Achievements screen) ---------------------------------------

    /** Every achievement id the player has unlocked. */
    public Set<String> unlocked(UUID player) {
        try {
            return dao.unlocked(player);
        } catch (SQLException e) {
            return Set.of();
        }
    }

    /**
     * How far the player is toward one achievement, in the unit of its target (a count, days,
     * money). An EVENT, GRADE or FINISH is 0 until it happens and 1 after.
     */
    public long progress(Player player, AchievementDef def) {
        UUID id = player.getUniqueId();
        try {
            return switch (def.type()) {
                case STAT -> stat(player, def.key());
                case COUNTER -> "biomes".equalsIgnoreCase(def.key()) ? dao.biomeCount(id) : dao.counter(id, def.key());
                case COLLECTION -> plugin.miniService().ownedIds(id).size();
                case STREAK -> plugin.tokens() != null ? plugin.tokens().streak(id) : 0;
                case BALANCE -> plugin.economy().isEnabled() ? (long) plugin.economy().balance(player) : 0;
                default -> unlocked(id).contains(def.id()) ? 1 : 0;
            };
        } catch (SQLException e) {
            return 0;
        }
    }

    /** A STAT key: one of the pulled quest measures, or a vanilla Statistic name. */
    private long stat(Player player, String key) {
        if (key == null) {
            return 0;
        }
        String k = key.trim().toUpperCase(Locale.ROOT);
        try {
            PluginConfig.QuestType qt = PluginConfig.QuestType.valueOf(k);
            if (QuestStats.isPulled(qt)) {
                return QuestStats.read(player, qt);
            }
        } catch (IllegalArgumentException ignored) {
            // not a quest measure — try a raw statistic
        }
        try {
            return player.getStatistic(Statistic.valueOf(k));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Achievements in display order. */
    public List<AchievementDef> all() {
        return List.copyOf(defs());
    }
}
