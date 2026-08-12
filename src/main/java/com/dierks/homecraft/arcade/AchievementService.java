package com.dierks.homecraft.arcade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig.AchievementDef;
import com.dierks.homecraft.storage.AchievementDao;
import com.dierks.homecraft.util.Text;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.sql.SQLException;

/**
 * One-time achievements (Phase 9, §3.9): the first time a player hits a milestone
 * (first Mini, first market sale, first PC, first crate, reaching a balance), they
 * get a token payout — fired exactly once per player. In-game tokens only.
 */
public final class AchievementService {

    private final HomeCraftManagement plugin;
    private final AchievementDao dao;

    public AchievementService(HomeCraftManagement plugin, AchievementDao dao) {
        this.plugin = plugin;
        this.dao = dao;
    }

    /** Fire an achievement for a player if it's enabled and not already unlocked. */
    public void tryAward(Player player, String key) {
        AchievementDef def = plugin.config().achievements().get(key);
        if (def == null || !def.enabled() || player == null) {
            return;
        }
        try {
            if (dao.unlock(player.getUniqueId(), key, System.currentTimeMillis())) {
                player.sendMessage(Text.of("&6★ Achievement unlocked: &e" + def.display() + "&6!"));
                player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.0f);
                if (plugin.arcade() != null) {
                    plugin.arcade().award(player, def.reward(), "achievement");
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to record achievement '" + key + "': " + e.getMessage());
        }
    }

    /** Check the balance-threshold achievement (e.g. reach $10k). Call after money changes. */
    public void checkBalance(Player player) {
        AchievementDef def = plugin.config().achievements().get("rich_10k");
        if (def == null || !def.enabled()) {
            return;
        }
        if (plugin.economy().isEnabled() && plugin.economy().balance(player) >= def.threshold()) {
            tryAward(player, "rich_10k");
        }
    }
}
