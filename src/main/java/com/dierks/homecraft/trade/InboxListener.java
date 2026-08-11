package com.dierks.homecraft.trade;

import com.dierks.homecraft.HomeCraftManagement;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/** Delivers queued auction notifications + owed Minis when a player logs in. */
public final class InboxListener implements Listener {

    private final HomeCraftManagement plugin;

    public InboxListener(HomeCraftManagement plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // Slight delay so the player is fully in before we drop items/messages.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            plugin.auctions().deliverInbox(event.getPlayer());
            if (plugin.deliveries() != null) {
                plugin.deliveries().notifyOnJoin(event.getPlayer());
            }
        }, 20L);
    }
}
