package com.dierks.homecraft.market.sim;

import com.dierks.homecraft.HomeCraftManagement;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Market news presence (spec §6.3): a join starts the player's session (announcements wait for
 * someone who has been on a few minutes) and schedules their "While you were away" catch-up 60
 * ticks later; a quit forgets the session. All the work is {@link MarketNewsService}'s.
 */
public final class MarketNewsListener implements Listener {

    private final HomeCraftManagement plugin;

    public MarketNewsListener(HomeCraftManagement plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        MarketNewsService news = plugin.marketNews();
        if (news != null) {
            news.onJoin(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        MarketNewsService news = plugin.marketNews();
        if (news != null) {
            news.onQuit(event.getPlayer());
        }
    }
}
