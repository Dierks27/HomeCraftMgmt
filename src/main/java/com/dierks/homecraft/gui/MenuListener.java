package com.dierks.homecraft.gui;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/**
 * Single dispatcher for every {@link Menu}. Cancels all clicks/drags in a menu
 * (they're click-to-act, never item transfers) and routes top-inventory clicks
 * to the menu's per-slot handlers.
 */
public final class MenuListener implements Listener {

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Menu menu)) {
            return;
        }
        event.setCancelled(true);
        if (menu.handlesRawClicks()) {
            menu.onRawClick(event);
            return;
        }
        if (event.getClickedInventory() == event.getView().getTopInventory()) {
            menu.handleClick(event);
        }
    }

    /**
     * 0.50.0 (the owner, at the Scratch Ticket: "I can grab the things out of the menu"): the last word on every click
     * and drag in a menu. Whatever ran after {@link #onClick} (another plugin, a game's own listener), the click stays
     * cancelled; and a tick later the player's view is sent again, so nothing the client only shows on its cursor (a
     * screen opened by the click itself, a fast double click) stays there looking taken.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClickLast(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Menu) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player) {
                resync(player);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDragLast(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Menu) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player) {
                resync(player);
            }
        }
    }

    /** The player's inventory and cursor sent again next tick, from what the server holds. */
    private static void resync(Player player) {
        try {
            org.bukkit.plugin.Plugin plugin = org.bukkit.plugin.java.JavaPlugin.getProvidingPlugin(MenuListener.class);
            org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    player.updateInventory();
                }
            });
        } catch (RuntimeException ignored) {
            // no scheduler (a test): the click is cancelled all the same
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Menu menu && event.getPlayer() instanceof Player player) {
            menu.closed(player);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Menu) {
            event.setCancelled(true);
        }
    }
}
