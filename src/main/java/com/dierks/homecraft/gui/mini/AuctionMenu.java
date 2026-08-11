package com.dierks.homecraft.gui.mini;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.gui.ConfirmMenu;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.storage.MiniAuctionDao;
import com.dierks.homecraft.trade.AuctionService;
import com.dierks.homecraft.util.Items;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The Mini Auction House GUI. In list mode it shows active auctions as a grid of
 * the actual Mini heads (current bid + time left); focusing one opens a detail
 * view to bid, Buy-It-Now, or (for the seller, pre-bid) cancel.
 */
public final class AuctionMenu extends Menu {

    private static final int PAGE_SIZE = 45;

    private final Player player;
    private final Runnable onBack;
    private Long focusId; // null = list mode
    private int page;

    public AuctionMenu(HomeCraftManagement plugin, Player player, Runnable onBack) {
        super(plugin);
        this.player = player;
        this.onBack = onBack;
        init(54, Text.of("&6Mini Auction House"));
    }

    @Override
    protected void build() {
        for (int i = 0; i < 54; i++) {
            set(i, i >= 45 ? Menus.FILLER : null, null);
        }
        if (focusId != null) {
            buildDetail();
        } else {
            buildList();
        }
    }

    private void buildList() {
        List<MiniAuctionDao.Auction> list = plugin.auctions().active();
        int pages = Math.max(1, (int) Math.ceil(list.size() / (double) PAGE_SIZE));
        page = Math.max(0, Math.min(page, pages - 1));

        if (list.isEmpty()) {
            set(22, Menus.icon(Material.PAPER, "&7No active auctions",
                    "&8Hold a Mini and click 'Sell a Mini'."), null);
        }
        int start = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE; i++) {
            int idx = start + i;
            if (idx >= list.size()) {
                continue;
            }
            MiniAuctionDao.Auction a = list.get(idx);
            set(i, listIcon(a), e -> {
                focusId = a.id();
                refresh();
            });
        }

        if (page > 0) {
            set(45, Menus.icon(Material.ARROW, "&e« Previous"), e -> {
                page--;
                refresh();
            });
        }
        set(48, Menus.icon(Material.GOLD_INGOT, "&aSell a Mini",
                "&7Auction the Mini in your main hand"),
                e -> new AuctionCreateMenu(plugin, player, this::reopen).open(player));
        set(49, Menus.icon(Material.BARRIER, "&cBack"), e -> back());
        if ((page + 1) * PAGE_SIZE < list.size()) {
            set(53, Menus.icon(Material.ARROW, "&eNext »"), e -> {
                page++;
                refresh();
            });
        }
    }

    private void buildDetail() {
        Optional<MiniAuctionDao.Auction> opt = plugin.auctions().byId(focusId);
        if (opt.isEmpty()) {
            focusId = null;
            refresh();
            return;
        }
        MiniAuctionDao.Auction a = opt.get();
        set(13, detailIcon(a), null);

        set(29, Menus.icon(Material.EMERALD, "&aPlace a bid",
                "&7Minimum: &f" + plugin.economy().format(a.hasBid()
                        ? a.currentBid() + 1 : a.startBid())),
                e -> plugin.chatPrompts().prompt(player, "Enter your bid amount:", input -> {
                    double amount = parse(input);
                    if (amount <= 0) {
                        player.sendMessage(Text.of("&cEnter a number."));
                    } else {
                        AuctionService.Result r = plugin.auctions().bid(player, a.id(), amount);
                        player.sendMessage(r.ok() ? Text.of("&aBid placed.") : Text.of("&c" + r.error()));
                    }
                    reopenDetail();
                }));

        if (a.buyNow() > 0) {
            set(31, Menus.icon(Material.DIAMOND, "&bBuy It Now",
                    "&7" + plugin.economy().format(a.buyNow())), e -> {
                ItemStack mini = Items.fromBase64(a.itemB64());
                new ConfirmMenu(plugin, "&bBuy now for " + plugin.economy().format(a.buyNow()) + "?",
                        mini == null ? Menus.FILLER : mini,
                        List.of("&7Your balance: &f" + plugin.economy().format(plugin.economy().balance(player))),
                        () -> {
                            AuctionService.Result r = plugin.auctions().buyNow(player, a.id());
                            player.sendMessage(r.ok() ? Text.of("&aBought!") : Text.of("&c" + r.error()));
                            reopenDetail();
                        }, this::reopenDetail).open(player);
            });
        }

        if (a.seller().equals(player.getUniqueId()) && !a.hasBid()) {
            set(33, Menus.icon(Material.CHEST, "&cCancel & reclaim"), e -> {
                AuctionService.Result r = plugin.auctions().cancel(player, a.id());
                player.sendMessage(r.ok() ? Text.of("&eCancelled.") : Text.of("&c" + r.error()));
                focusId = null;
                refresh();
            });
        }

        set(49, Menus.icon(Material.ARROW, "&cBack to auctions"), e -> {
            focusId = null;
            refresh();
        });
    }

    private ItemStack listIcon(MiniAuctionDao.Auction a) {
        ItemStack icon = miniOr(a);
        addLore(icon,
                "&8—",
                "&7Current: &f" + (a.hasBid() ? plugin.economy().format(a.currentBid()) : "no bids"),
                "&7Start: &f" + plugin.economy().format(a.startBid()),
                a.buyNow() > 0 ? "&7Buy now: &b" + plugin.economy().format(a.buyNow()) : "&8no buy-now",
                "&7Ends in: &f" + Menus.duration(a.endAt() - System.currentTimeMillis()),
                "&eClick to view");
        return icon;
    }

    private ItemStack detailIcon(MiniAuctionDao.Auction a) {
        ItemStack icon = miniOr(a);
        String leader = a.hasBid() ? nameOf(a.currentBidder()) : "—";
        addLore(icon,
                "&8—",
                "&7Seller: &f" + nameOf(a.seller()),
                "&7Current bid: &f" + (a.hasBid() ? plugin.economy().format(a.currentBid()) : "none"),
                "&7Top bidder: &f" + leader,
                a.buyNow() > 0 ? "&7Buy now: &b" + plugin.economy().format(a.buyNow()) : "&8no buy-now",
                "&7Ends in: &f" + Menus.duration(a.endAt() - System.currentTimeMillis()));
        return icon;
    }

    private ItemStack miniOr(MiniAuctionDao.Auction a) {
        ItemStack item = Items.fromBase64(a.itemB64());
        return item != null ? item.clone() : Menus.icon(Material.BARRIER, "&cUnreadable Mini");
    }

    private void addLore(ItemStack icon, String... lines) {
        ItemMeta meta = icon.getItemMeta();
        if (meta == null) {
            return;
        }
        List<net.kyori.adventure.text.Component> lore = meta.hasLore()
                ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        for (String l : lines) {
            lore.add(Text.of(l));
        }
        meta.lore(lore);
        icon.setItemMeta(meta);
    }

    private String nameOf(java.util.UUID id) {
        if (id == null) {
            return "—";
        }
        String name = Bukkit.getOfflinePlayer(id).getName();
        return name != null ? name : id.toString().substring(0, 8);
    }

    private double parse(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void back() {
        if (onBack != null) {
            onBack.run();
        } else {
            player.closeInventory();
        }
    }

    private void reopen() {
        new AuctionMenu(plugin, player, onBack).open(player);
    }

    private void reopenDetail() {
        open(player);
    }
}
