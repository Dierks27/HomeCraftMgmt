package com.dierks.homecraft.arcade;

import com.dierks.homecraft.HomeCraftManagement;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Grants login-streak and playtime tokens when a player joins (§3.9). One streak
 * reward per local day (anti-abuse); playtime tokens catch up to time played.
 *
 * <p>Coming back into a world where tokens are paid (home from the Games world, say) pays the game
 * quests and unlocks the game achievements finished where tokens aren't paid (EXTRAS E4), at once
 * instead of at the next poll.
 */
public final class ArcadeListener implements Listener {

    private final HomeCraftManagement plugin;

    public ArcadeListener(HomeCraftManagement plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (plugin.tokens() != null) {
            plugin.tokens().onJoin(event.getPlayer());
        }
        if (plugin.quests() != null) {
            plugin.quests().onJoin(event.getPlayer()); // deal today's and this week's quests
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        org.bukkit.entity.Player player = event.getPlayer();
        if (!plugin.sandbox().allowed(player.getWorld())) {
            return;
        }
        try {
            if (plugin.quests() != null) {
                plugin.quests().settle(player);
            }
            if (plugin.achievements() != null) {
                plugin.achievements().sweep(player);
            }
        } catch (RuntimeException e) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Could not pay game quests after a world change", e);
        }
    }
}
