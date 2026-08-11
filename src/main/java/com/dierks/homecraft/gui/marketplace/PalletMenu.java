package com.dierks.homecraft.gui.marketplace;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.marketplace.PalletService;
import com.dierks.homecraft.storage.PalletDao;
import com.dierks.homecraft.util.Items;
import com.dierks.homecraft.util.Text;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The Pallet management GUI. The owner loads an item + sets a price to list it on
 * the Crate Marketplace, restocks, reprices, or takes it back. Non-owners see a
 * read-only view (they buy from the Marketplace at a PC, not here).
 */
public final class PalletMenu extends Menu {

    private final Player player;
    private final Location loc;
    private final boolean owner;

    public PalletMenu(HomeCraftManagement plugin, Player player, Location loc, boolean owner) {
        super(plugin);
        this.player = player;
        this.loc = loc;
        this.owner = owner;
        init(27, Text.of("&6Pallet"));
    }

    @Override
    protected void build() {
        for (int i = 0; i < 27; i++) {
            set(i, Menus.FILLER, null);
        }
        PalletService pallets = plugin.pallets();
        Optional<PalletDao.Listing> opt = pallets.at(loc);

        if (opt.isEmpty()) {
            if (owner) {
                set(13, Menus.icon(Material.NAME_TAG, "&eList an item",
                        "&7Hold the item in your main hand,",
                        "&7then click to set a price & list it."),
                        e -> plugin.chatPrompts().prompt(player, "Enter a price per item:", input -> {
                            double p = parse(input);
                            if (p <= 0) {
                                player.sendMessage(Text.of("&cEnter a number above 0."));
                            } else {
                                report(pallets.stock(player, loc, p));
                            }
                            reopen();
                        }));
            } else {
                set(13, Menus.icon(Material.BARRIER, "&7Empty Pallet"), null);
            }
            set(22, Menus.icon(Material.BARRIER, "&cClose"), e -> e.getWhoClicked().closeInventory());
            return;
        }

        PalletDao.Listing l = opt.get();
        set(13, decorate(l), null);

        if (owner || player.hasPermission("hcm.admin")) {
            set(10, Menus.icon(Material.HOPPER, "&aStock more",
                    "&7Hold more of the same item,",
                    "&7then click to add to the stock."),
                    e -> plugin.chatPrompts().prompt(player, "Price (blank/0 to keep current):", input -> {
                        report(pallets.stock(player, loc, parse(input)));
                        reopen();
                    }));
            set(11, Menus.icon(Material.GOLD_INGOT, "&eChange price",
                    "&7Current: &f" + plugin.economy().format(l.price())),
                    e -> plugin.chatPrompts().prompt(player, "Enter a new price:", input -> {
                        double p = parse(input);
                        if (p <= 0) {
                            player.sendMessage(Text.of("&cEnter a number above 0."));
                        } else {
                            report(pallets.setPrice(player, loc, p));
                        }
                        reopen();
                    }));
            set(15, Menus.icon(Material.CHEST, "&aTake it all back",
                    "&7Empties the Pallet & delists it."), e -> {
                report(pallets.takeBack(player, loc));
                reopen();
            });
        } else {
            set(11, Menus.icon(Material.COMPASS, "&7Buy from the Marketplace",
                    "&7Open a PC → Crate → Marketplace", "&7to buy this."), null);
        }
        set(22, Menus.icon(Material.BARRIER, "&cClose"), e -> e.getWhoClicked().closeInventory());
    }

    private ItemStack decorate(PalletDao.Listing l) {
        ItemStack item = Items.fromBase64(l.itemB64());
        if (item == null) {
            return Menus.icon(Material.BARRIER, "&cUnreadable item");
        }
        ItemStack icon = item.clone();
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            List<net.kyori.adventure.text.Component> lore = meta.hasLore()
                    ? new ArrayList<>(meta.lore()) : new ArrayList<>();
            lore.add(Text.of("&8—"));
            lore.add(Text.of("&7Price: &6" + plugin.economy().format(l.price()) + " &7each"));
            lore.add(Text.of("&7In stock: &f" + l.stock()));
            lore.add(Text.of("&7Department: &f" + l.department()));
            lore.add(Text.of(l.active() && l.stock() > 0 ? "&aListed on the Marketplace" : "&cInactive (restock)"));
            meta.lore(lore);
            icon.setItemMeta(meta);
        }
        return icon;
    }

    private void report(PalletService.Result r) {
        player.sendMessage(r.ok() ? Text.of("&aDone.") : Text.of("&c" + r.error()));
    }

    private double parse(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void reopen() {
        new PalletMenu(plugin, player, loc, owner).open(player);
    }
}
