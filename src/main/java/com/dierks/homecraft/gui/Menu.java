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

    /**
     * Whether {@code player} is looking at this menu right now. False after an open another plugin
     * cancelled (combat tag, vanish, anticheat) — in which case no close event will ever come for
     * it, so anything started on open must not rely on {@link #onClose} to stop it.
     */
    protected boolean isOpenFor(org.bukkit.entity.Player player) {
        return player != null && player.isOnline()
                && player.getOpenInventory().getTopInventory().getHolder(false) == this;
    }

    /** Called when the viewer closes this menu — the place to hand back anything held in it. */
    protected void onClose(org.bukkit.entity.Player player) {
    }

    /**
     * How a close reaches {@link #onClose}: from {@link MenuListener} and from {@link #closeNow}.
     * A family of menus that must contain what its close handlers throw (the games screens run
     * them inside the game's guard) overrides this rather than every {@code onClose}.
     */
    protected void handleClose(org.bukkit.entity.Player player) {
        onClose(player);
    }

    /**
     * Run this menu's close handling and close it, for when no close event will reach
     * {@link MenuListener} (the plugin is shutting down), or before one would come too late
     * (Multiverse-Inventories saves every player's things as it disables, before this plugin does).
     *
     * <p>Once per open, however many ways a shutdown reaches it: nothing happens when the player
     * isn't looking at this menu any more (an earlier close already ran it), and the close event
     * the close itself fires, while {@link MenuListener} still hears them, is this same close, not
     * a second one. A menu that hands items back also empties itself as it does.
     */
    public void closeNow(org.bukkit.entity.Player player) {
        if (!isOpenFor(player)) {
            return;
        }
        handleClose(player);
        closingNow = player.getUniqueId();
        try {
            player.closeInventory();
        } finally {
            closingNow = null;
        }
    }

    /** The player {@link #closeNow} is closing this menu for, while it does, or {@code null}. */
    private java.util.UUID closingNow;

    /** A close event ({@link MenuListener}): the close handling, unless {@link #closeNow} is running it already. */
    void closed(org.bukkit.entity.Player player) {
        if (!player.getUniqueId().equals(closingNow)) {
            handleClose(player);
        }
    }

    /**
     * Close the menu each of {@code players} is looking at, if it is one of these
     * ({@link #closeNow}): at a shutdown, so a menu holding a player's items (the Card trade-in
     * tray) hands them back while the player's inventory is still going to be saved. A menu whose
     * close throws is logged and the rest still close.
     */
    public static void closeAll(Iterable<? extends org.bukkit.entity.Player> players, java.util.logging.Logger log) {
        for (org.bukkit.entity.Player p : players) {
            try {
                if (p.getOpenInventory().getTopInventory().getHolder(false) instanceof Menu menu) {
                    menu.closeNow(p);
                }
            } catch (RuntimeException e) {
                log.warning("Could not close " + p.getName() + "'s menu on shutdown: " + e.getMessage());
            }
        }
    }

    void handleClick(InventoryClickEvent event) {
        Consumer<InventoryClickEvent> handler = handlers.get(event.getRawSlot());
        if (handler != null) {
            handler.accept(event);
        }
    }
}
