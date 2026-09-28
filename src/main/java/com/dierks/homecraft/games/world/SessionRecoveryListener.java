package com.dierks.homecraft.games.world;

import com.dierks.homecraft.HomeCraftManagement;
import org.bukkit.event.Listener;

/**
 * Saved-state recovery that never switches off (spec §7.6, R2.7): the join, respawn and quit
 * hooks that bring a player's things back after a crash, a stop or a failed return, and the
 * stray-kit sweep. Built from the plugin alone and registered once at enable, even when the
 * games service is missing or the games are off.
 */
public final class SessionRecoveryListener implements Listener {

    private final HomeCraftManagement plugin;

    public SessionRecoveryListener(HomeCraftManagement plugin) {
        this.plugin = plugin;
        // F3: load the Set<UUID> of players with a live saved-state row; the hooks.
    }
}
