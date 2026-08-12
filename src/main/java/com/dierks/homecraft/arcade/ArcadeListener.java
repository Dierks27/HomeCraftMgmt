package com.dierks.homecraft.arcade;

import com.dierks.homecraft.HomeCraftManagement;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Grants login-streak and playtime tokens when a player joins (§3.9). One streak
 * reward per real day (anti-abuse); playtime tokens catch up to time played.
 */
public final class ArcadeListener implements Listener {

    private final HomeCraftManagement plugin;

    public ArcadeListener(HomeCraftManagement plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (plugin.arcade() != null) {
            plugin.arcade().onJoin(event.getPlayer());
        }
    }
}
