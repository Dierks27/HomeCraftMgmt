package com.dierks.homecraft.arcade.looks;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * When the wardrobe looks at a player (SHOP-V2-SPEC §3.8): their rows are read at join, and their keys swept 2 ticks
 * later (after the session recovery's +1 tick and Multiverse-Inventories' profile load) and 2 ticks after a world
 * change (after its LOW swap); their heads-up waits 100 ticks after the join. Dropped at quit. A click or a drag in
 * a player's own inventory (where the head slot is) reads their Keeper mark again a tick later, so a key dragged
 * onto the head starts its mark at once (§5.3a); the minute's sweep stays the guarantee.
 */
public final class LookListener implements Listener {

    private final LookService looks;

    public LookListener(LookService looks) {
        this.looks = looks;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        looks.joined(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        looks.quit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorld(PlayerChangedWorldEvent event) {
        looks.changedWorld(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player p && showsHead(event.getView().getTopInventory().getType().name())) {
            looks.marksSoon(p);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player p && showsHead(event.getView().getTopInventory().getType().name())) {
            looks.marksSoon(p);
        }
    }

    /**
     * Whether a view with this top (an {@code InventoryType}'s name) shows the player's head slot: their own
     * inventory (the 2x2 crafting grid on top) or the Creative one. A chest's view has no armour slots, so a click
     * there can't change the head.
     */
    static boolean showsHead(String top) {
        return "CRAFTING".equals(top) || "CREATIVE".equals(top);
    }
}
