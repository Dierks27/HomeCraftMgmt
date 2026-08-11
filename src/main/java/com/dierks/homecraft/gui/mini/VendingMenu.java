package com.dierks.homecraft.gui.mini;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.gui.ConfirmMenu;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.mini.MiniDef;
import com.dierks.homecraft.storage.MiniDao;
import com.dierks.homecraft.storage.MiniListingDao;
import com.dierks.homecraft.trade.VendingService;
import com.dierks.homecraft.util.Items;
import com.dierks.homecraft.util.Text;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The Mini Vending Machine GUI: the block owner loads a Mini (held in hand) at
 * their own price; any other player browses the listing and buys it (confirmed).
 * Shows the Mini's series/rarity/Mint #/live circulation at point of sale.
 */
public final class VendingMenu extends Menu {

    private final Player player;
    private final Location loc;
    private final boolean blockOwner;

    public VendingMenu(HomeCraftManagement plugin, Player player, Location loc, boolean blockOwner) {
        super(plugin);
        this.player = player;
        this.loc = loc;
        this.blockOwner = blockOwner;
        init(27, Text.of("&dMini Vending Machine"));
    }

    @Override
    protected void build() {
        for (int i = 0; i < 27; i++) {
            set(i, Menus.FILLER, null);
        }
        VendingService vending = plugin.vending();
        Optional<MiniListingDao.Listing> opt = vending.at(loc);

        if (opt.isEmpty()) {
            buildEmpty();
            return;
        }
        MiniListingDao.Listing listing = opt.get();
        ItemStack mini = Items.fromBase64(listing.itemB64());
        set(13, decorate(mini, listing), null);
        set(10, Menus.icon(Material.BOOK, "&5Info card", "&7View this Mini's details"),
                e -> new MiniInfoMenu(plugin, player,
                        new com.dierks.homecraft.mini.MiniService.MiniRef(
                                listing.uid(), listing.miniId(), listing.mintNumber()),
                        mini, this::reopen).open(player));

        boolean isSeller = listing.owner().equals(player.getUniqueId());
        if (isSeller) {
            set(11, Menus.icon(Material.GOLD_INGOT, "&eChange price",
                    "&7Current: &f" + plugin.economy().format(listing.price())),
                    e -> plugin.chatPrompts().prompt(player, "Enter a new price:", input -> {
                        setPrice(vending, input);
                    }));
            set(15, Menus.icon(Material.CHEST, "&aUnlist (take it back)"), e -> {
                report(vending.reclaim(player, loc));
                reopen();
            });
        } else {
            set(15, Menus.icon(Material.EMERALD, "&aBuy for " + plugin.economy().format(listing.price()),
                    "&7Click to review & confirm"), e -> confirmBuy(vending, listing, mini));
        }
        set(22, Menus.icon(Material.BARRIER, "&cClose"), e -> e.getWhoClicked().closeInventory());
    }

    private void buildEmpty() {
        if (blockOwner) {
            set(13, Menus.icon(Material.NAME_TAG, "&eList a Mini",
                    "&7Hold the Mini in your main hand,",
                    "&7then click to set a price & list it."),
                    e -> plugin.chatPrompts().prompt(player, "Enter a sale price for the held Mini:", input -> {
                        double price = parse(input);
                        if (price <= 0) {
                            player.sendMessage(Text.of("&cEnter a number above 0."));
                            reopen();
                            return;
                        }
                        report(plugin.vending().load(player, loc, VendingService.VENDING, price));
                        reopen();
                    }));
        } else {
            set(13, Menus.icon(Material.BARRIER, "&7Empty",
                    "&8The owner hasn't listed a Mini yet."), null);
        }
        set(22, Menus.icon(Material.BARRIER, "&cClose"), e -> e.getWhoClicked().closeInventory());
    }

    private void confirmBuy(VendingService vending, MiniListingDao.Listing listing, ItemStack mini) {
        List<String> lore = List.of(
                "&7Price: &6" + plugin.economy().format(listing.price()),
                "&7Your balance: &f" + plugin.economy().format(plugin.economy().balance(player)));
        new ConfirmMenu(plugin, "&dBuy this Mini?", mini == null ? Menus.FILLER : mini, lore,
                () -> {
                    report(vending.buy(player, loc));
                    reopen();
                },
                this::reopen).open(player);
    }

    private void setPrice(VendingService vending, String input) {
        double price = parse(input);
        if (price <= 0) {
            player.sendMessage(Text.of("&cEnter a number above 0."));
        } else {
            report(vending.setPrice(player, loc, price));
        }
        reopen();
    }

    /** Add provenance lore (series/rarity/Mint #/circulation) to the displayed Mini. */
    private ItemStack decorate(ItemStack mini, MiniListingDao.Listing listing) {
        if (mini == null) {
            return Menus.icon(Material.BARRIER, "&cUnreadable Mini");
        }
        ItemStack icon = mini.clone();
        var meta = icon.getItemMeta();
        if (meta != null) {
            List<net.kyori.adventure.text.Component> lore = meta.hasLore()
                    ? new ArrayList<>(meta.lore()) : new ArrayList<>();
            MiniDef def = plugin.miniService().def(listing.miniId());
            MiniDao.Counts c = plugin.miniService().counts(listing.miniId());
            lore.add(Text.of("&8—"));
            if (def != null) {
                lore.add(Text.of("&7Series: &f" + def.series()));
                lore.add(Text.of("&7Rarity: &f" + def.rarity().name()));
                lore.add(Text.of("&7Mint #&f" + listing.mintNumber()
                        + (def.uncapped() ? "" : " &7of &f" + def.cap())));
            }
            lore.add(Text.of("&7In circulation: &f" + c.circulation()));
            lore.add(Text.of("&6Price: &f" + plugin.economy().format(listing.price())));
            meta.lore(lore);
            icon.setItemMeta(meta);
        }
        return icon;
    }

    private void report(VendingService.Result r) {
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
        new VendingMenu(plugin, player, loc, blockOwner).open(player);
    }
}
