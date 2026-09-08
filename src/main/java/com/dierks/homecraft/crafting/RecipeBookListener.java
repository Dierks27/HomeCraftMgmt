package com.dierks.homecraft.crafting;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Unlocks every HomeCraft recipe in the joining player's vanilla recipe book, so
 * the blocks show up there with click-to-fill. Discovering an already-known
 * recipe is a no-op, so this is safe to run on every join and after a reload.
 */
public final class RecipeBookListener implements Listener {

    private final RecipeManager recipes;

    public RecipeBookListener(RecipeManager recipes) {
        this.recipes = recipes;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        recipes.unlockFor(event.getPlayer());
    }
}
