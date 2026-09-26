package com.dierks.homecraft.gui.arcade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.mini.Rarity;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Card trade-in: put up to nine stacks of Cards in, see what they are worth, and swap them for
 * tokens. Common 3, Uncommon 5, Rare 12, Epic 25, Legendary 50 each (config).
 *
 * <p>Items move only by explicit code — click a Card in your inventory to put it in, click it in
 * the tray to take it back — never by vanilla cursor handling, so nothing can be duplicated
 * between the two inventories. The tray is held in {@link #tray} rather than in the chest itself,
 * because a menu repaints by clearing the chest. Close without confirming and every Card goes
 * back to you.
 */
public final class TradeInMenu extends Menu {

    private static final int FIRST = 18;
    private static final int SLOTS = 9;

    private final Player player;
    private final Runnable back;
    private final ItemStack[] tray = new ItemStack[SLOTS];

    public TradeInMenu(HomeCraftManagement plugin, Player player, Runnable back) {
        super(plugin);
        this.player = player;
        this.back = back;
        init(54, Text.of("&5Trade In Cards"));
    }

    @Override
    protected boolean handlesRawClicks() {
        return true;
    }

    @Override
    protected void build() {
        for (int i = 0; i < 54; i++) {
            if (i < FIRST || i >= FIRST + SLOTS) {
                set(i, Menus.FILLER, null);
            }
        }
        var arc = plugin.config().arcade();
        List<String> lore = new ArrayList<>();
        lore.add("&7Swap Cards you don't need for tokens.");
        lore.add("&7Click a Card in your bag to add it.");
        for (Rarity r : Rarity.values()) {
            lore.add(plugin.miniService().rarityText(r) + " &7Card: &6" + arc.tradeInValue(r) + " tokens");
        }
        set(4, Menus.icon(Material.HOPPER, "&dTrade In Cards", lore.toArray(new String[0])), null);

        for (int i = 0; i < SLOTS; i++) {
            set(FIRST + i, tray[i], null);
        }
        int total = value();
        set(31, Menus.icon(Material.SUNFLOWER, "&eYou'll get &6" + total + " tokens",
                "&7You have &6" + plugin.tokens().balance(player.getUniqueId()) + " tokens&7."), null);
        set(40, Menus.glint(Menus.icon(total > 0 ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_DYE,
                total > 0 ? "&a&lTrade in for " + total + " tokens" : "&7Add some Cards first",
                total > 0 ? "&7Click to swap them." : "&7Click a Card in your bag."), total > 0), e -> confirm());
        set(49, Menus.icon(Material.BARRIER, back != null ? "&cBack" : "&cClose"), e -> {
            if (back != null) {
                back.run(); // opening the next screen closes this one, which hands the tray back
            } else {
                e.getWhoClicked().closeInventory();
            }
        });
    }

    @Override
    protected void onRawClick(InventoryClickEvent event) {
        boolean top = event.getClickedInventory() == event.getView().getTopInventory();
        int raw = event.getRawSlot();
        if (top) {
            if (raw >= FIRST && raw < FIRST + SLOTS) {
                takeBack(raw - FIRST);
            } else {
                super.onRawClick(event); // buttons
            }
            return;
        }
        if (event.getClickedInventory() == null) {
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir()) {
            return;
        }
        if (plugin.arcade().tradeInValue(clicked) <= 0) {
            player.sendMessage(Text.of("&7Only Cards can be traded in."));
            Sounds.refused(player);
            return;
        }
        int free = -1;
        for (int i = 0; i < SLOTS; i++) {
            if (tray[i] == null) {
                free = i;
                break;
            }
        }
        if (free < 0) {
            player.sendMessage(Text.of("&7The tray is full — trade these in first."));
            Sounds.refused(player);
            return;
        }
        tray[free] = clicked.clone();
        event.getClickedInventory().setItem(event.getSlot(), null);
        refresh();
    }

    private void takeBack(int index) {
        ItemStack it = tray[index];
        if (it == null) {
            return;
        }
        if (room(it) < it.getAmount()) {
            // No room: keep it in the tray, where it is visible, rather than drop it on the floor.
            player.sendMessage(Text.of("&7Your bag is full."));
            return;
        }
        player.getInventory().addItem(it.clone());
        tray[index] = null;
        refresh();
    }

    /** How many of {@code it} the player's storage slots can take. */
    private int room(ItemStack it) {
        int room = 0;
        for (ItemStack s : player.getInventory().getStorageContents()) {
            if (s == null || s.getType().isAir()) {
                room += it.getMaxStackSize();
            } else if (s.isSimilar(it)) {
                room += Math.max(0, s.getMaxStackSize() - s.getAmount());
            }
        }
        return room;
    }

    private int value() {
        int total = 0;
        for (ItemStack it : tray) {
            if (it != null) {
                total += plugin.arcade().tradeInValue(it) * it.getAmount();
            }
        }
        return total;
    }

    private void confirm() {
        List<ItemStack> cards = new ArrayList<>();
        for (ItemStack it : tray) {
            if (it != null) {
                cards.add(it);
            }
        }
        if (cards.isEmpty()) {
            return;
        }
        int count = cards.stream().mapToInt(ItemStack::getAmount).sum();
        int got = plugin.arcade().tradeIn(player, cards);
        if (got <= 0) {
            player.sendMessage(Text.of("&cThat didn't work — your Cards are still here."));
            Sounds.refused(player);
            return;
        }
        java.util.Arrays.fill(tray, null);
        player.sendMessage(Text.of("&a✔ Traded " + count + (count == 1 ? " Card" : " Cards") + " for &6" + got
                + " tokens&a!"));
        Sounds.received(player);
        refresh();
    }

    /** Whatever is still in the tray goes back to the player. */
    @Override
    protected void onClose(Player viewer) {
        for (int i = 0; i < SLOTS; i++) {
            ItemStack it = tray[i];
            if (it != null) {
                tray[i] = null;
                viewer.getInventory().addItem(it).values()
                        .forEach(drop -> viewer.getWorld().dropItemNaturally(viewer.getLocation(), drop));
            }
        }
    }
}
