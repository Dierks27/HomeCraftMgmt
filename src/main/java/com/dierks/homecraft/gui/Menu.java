package com.dierks.homecraft.gui;

import com.dierks.homecraft.HomeCraftManagement;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Minimal click-driven inventory-GUI base. All HomeCraft player interaction is
 * GUI-first — every menu is one of these, dispatched by a single
 * {@link MenuListener}. Slots carry click handlers; unhandled clicks do nothing
 * (the listener cancels every click, so items can never be dragged in or out).
 */
public abstract class Menu implements InventoryHolder {

    protected final HomeCraftManagement plugin;
    private Inventory inventory;
    private final Map<Integer, Consumer<InventoryClickEvent>> handlers = new HashMap<>();

    protected Menu(HomeCraftManagement plugin) {
        this.plugin = plugin;
    }

    protected void init(int size, Component title) {
        this.inventory = Bukkit.createInventory(this, size, title);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /** Populate items + handlers. Called on open and on {@link #refresh()}. */
    protected abstract void build();

    /** Place an item, optionally with a click handler (null = decorative/locked). */
    protected void set(int slot, ItemStack item, Consumer<InventoryClickEvent> onClick) {
        inventory.setItem(slot, item);
        if (onClick != null) {
            handlers.put(slot, onClick);
        } else {
            handlers.remove(slot);
        }
    }

    /** Rebuild the menu in place (contents update live for anyone viewing it). */
    protected void refresh() {
        handlers.clear();
        inventory.clear();
        build();
    }

    public void open(Player player) {
        build();
        player.openInventory(inventory);
    }

    /**
     * A menu that moves real items in and out (the Card trade-in) takes every click itself:
     * {@link MenuListener} still cancels the click, then hands it to {@link #onRawClick}, which
     * moves items explicitly. Nothing is ever left to vanilla cursor handling, so nothing can be
     * duplicated or lost between the two inventories.
     */
    protected boolean handlesRawClicks() {
        return false;
    }

    /** Every click in the view, top or bottom, when {@link #handlesRawClicks()} (already cancelled). */
    protected void onRawClick(InventoryClickEvent event) {
        handleClick(event);
    }

    /** Called when the viewer closes this menu — the place to hand back anything held in it. */
    protected void onClose(org.bukkit.entity.Player player) {
    }

    void handleClick(InventoryClickEvent event) {
        Consumer<InventoryClickEvent> handler = handlers.get(event.getRawSlot());
        if (handler != null) {
            handler.accept(event);
        }
    }
}
