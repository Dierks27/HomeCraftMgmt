package com.dierks.homecraft.games;

import com.dierks.homecraft.HomeCraftManagement;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.logging.Level;

/**
 * The framework's own join, quit and world-change hooks (spec §3.2, R3.11): a join finishes any
 * round a crash left open and delivers the lines kept for the player; a quit finishes their OPEN
 * rounds by the exit rule and cancels their invites; a world change cancels their invites. Each
 * open game also hears about joins and quits ({@link Game#onJoin}, {@link Game#onQuit}).
 *
 * <p>Registered once at enable and never switched with {@code games.enabled}: rounds must be
 * finished and invites cancelled whatever the switches say. It never throws — a join or a quit is
 * never the place for a games bug to surface. Saved things of a player in a world game are not
 * handled here but by the recovery listener, which works even without this service.
 */
public final class GamesListener implements Listener {

    private final HomeCraftManagement plugin;

    public GamesListener(HomeCraftManagement plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        GamesService games = plugin.games();
        if (games == null) {
            return;
        }
        try {
            games.onJoin(event.getPlayer());
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Games: a join could not be handled", e);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        GamesService games = plugin.games();
        if (games == null) {
            return;
        }
        try {
            games.onQuit(event.getPlayer());
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Games: a quit could not be handled", e);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        GamesService games = plugin.games();
        if (games == null) {
            return;
        }
        try {
            games.invites().cancel(event.getPlayer().getUniqueId());
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Games: a world change could not be handled", e);
        }
    }
}
