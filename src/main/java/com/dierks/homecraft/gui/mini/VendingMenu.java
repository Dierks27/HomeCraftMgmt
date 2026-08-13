package com.dierks.homecraft.gui.mini;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.gui.ConfirmMenu;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.mini.MiniDef;
import com.dierks.homecraft.mini.MiniService;
import com.dierks.homecraft.storage.MiniDao;
import com.dierks.homecraft.storage.MiniVendingDao;
import com.dierks.homecraft.trade.VendingService;
import com.dierks.homecraft.util.Items;
import com.dierks.homecraft.util.Text;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The multi-slot Mini Vending Machine GUI. The block owner stocks several Minis,
 * each at its own price; anyone browses the grid of what's for sale and clicks
 * one to buy (confirmed). The owner left-clicks a listing to reprice it and
 * right-clicks to take it back. Every sale is fully tracked (never QuickShop).
 */
public final class VendingMenu extends Menu {

    private static final int PAGE_SIZE = 45;

    private final Player player;
    private final Location loc;
    private final boolean blockOwner;
    private int page;

    public VendingMenu(HomeCraftManagement plugin, Player player, Location loc, boolean blockOwner) {
        super(plugin);
        this.player = player;
        this.loc = loc;
        this.blockOwner = blockOwner;
        init(54, Text.of("&dMini Vending Machine"));
    }

    @Override
    protected void build() {
        for (int slot = 45; slot < 54; slot++) {
            set(slot, Menus.FILLER, null);
        }
        List<MiniVendingDao.Listing> listings = plugin.vending().vendingAt(loc);
        int pages = Math.max(1, (int) Math.ceil(listings.size() / (double) PAGE_SIZE));
        page = Math.max(0, Math.min(page, pages - 1));

        if (listings.isEmpty()) {
            set(22, Menus.icon(Material.BARRIER, "&7Nothing for sale yet",
                    blockOwner ? "&8Stock a Mini with the button below." : "&8Check back later."), null);
        }

        int start = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE; i++) {
            int idx = start + i;
            if (idx >= listings.size()) {
                set(i, null, null);
                continue;
            }
            MiniVendingDao.Listing l = listings.get(idx);
            boolean isSeller = l.owner().equals(player.getUniqueId());
            set(i, icon(l, isSeller), e -> {
                if (isSeller || (blockOwner && player.hasPermission("hcm.admin"))) {
                    if (e.getClick().isRightClick()) {
                        report(plugin.vending().unlistVending(player, l.id()));
                        refresh();
                    } else {
                        plugin.chatPrompts().prompt(player, "Enter a new price:", input -> {
                            double p = parse(input);
                            if (p <= 0) {
                                player.sendMessage(Text.of("&cEnter a number above 0."));
                            } else {
                                report(plugin.vending().setVendingPrice(player, l.id(), p));
                            }
                            reopen();
                        });
                    }
                } else {
                    confirmBuy(l);
                }
            });
        }

        if (page > 0) {
            set(45, Menus.icon(Material.ARROW, "&e« Previous"), e -> {
                page--;
                refresh();
            });
        }
        if (blockOwner) {
            set(48, Menus.icon(Material.NAME_TAG, "&aStock a Mini",
                    "&7Hold a Mini in your main hand,",
                    "&7then click to set a price & list it."),
                    e -> plugin.chatPrompts().prompt(player, "Enter a sale price for the held Mini"
                            + floorHint() + ":", input -> {
                        double price = parse(input);
                        if (price <= 0) {
                            player.sendMessage(Text.of("&cEnter a number above 0."));
                        } else {
                            report(plugin.vending().addVending(player, loc, price));
                        }
                        reopen();
                    }));
        }
        set(49, Menus.icon(Material.BARRIER, "&cClose"), e -> e.getWhoClicked().closeInventory());
        set(50, Menus.balance(plugin, player), null);
        if ((page + 1) * PAGE_SIZE < listings.size()) {
            set(53, Menus.icon(Material.ARROW, "&eNext »"), e -> {
                page++;
                refresh();
            });
        }
    }

    private void confirmBuy(MiniVendingDao.Listing l) {
        ItemStack mini = Items.fromBase64(l.itemB64());
        List<String> lore = List.of(
                "&7Price: &6" + plugin.economy().format(l.price()),
                "&7Your balance: &f" + plugin.economy().format(plugin.economy().balance(player)));
        new ConfirmMenu(plugin, "&dBuy this Mini?", mini == null ? Menus.FILLER : mini, lore,
                () -> {
                    report(plugin.vending().buyVending(player, l.id()));
                    reopen();
                },
                this::reopen).open(player);
    }

    /** The listing's rendered Mini + provenance + price + the right action hint. */
    private ItemStack icon(MiniVendingDao.Listing l, boolean isSeller) {
        ItemStack mini = Items.fromBase64(l.itemB64());
        if (mini == null) {
            return Menus.icon(Material.BARRIER, "&cUnreadable Mini");
        }
        ItemStack icon = mini.clone();
        var meta = icon.getItemMeta();
        if (meta != null) {
            List<net.kyori.adventure.text.Component> lore = meta.hasLore()
                    ? new ArrayList<>(meta.lore()) : new ArrayList<>();
            MiniDef def = plugin.miniService().def(l.miniId());
            MiniDao.Counts c = plugin.miniService().counts(l.miniId());
            lore.add(Text.of("&8—"));
            if (def != null) {
                lore.add(Text.of("&7Series: &f" + def.series()));
                lore.add(Text.of("&7Rarity: &f" + def.rarity().name()));
                lore.add(Text.of("&7Mint #&f" + l.mintNumber()
                        + (def.uncapped() ? "" : " &7of &f" + def.cap())));
            }
            lore.add(Text.of("&7In circulation: &f" + c.circulation()));
            lore.add(Text.of("&6Price: &f" + plugin.economy().format(l.price())));
            lore.add(Text.of("&8—"));
            lore.add(isSeller ? Text.of("&eLeft-click&7: reprice  &eRight-click&7: take back")
                    : Text.of("&aClick to buy"));
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

    /** A " (suggested floor: $X)" hint from the held Mini's computed value, or "" if none. */
    private String floorHint() {
        Double floor = plugin.values() == null ? null
                : plugin.values().suggestedFloorFor(player.getInventory().getItemInMainHand());
        return floor == null ? "" : " (suggested floor: " + plugin.economy().format(floor) + ")";
    }

    private void reopen() {
        new VendingMenu(plugin, player, loc, blockOwner).open(player);
    }
}
