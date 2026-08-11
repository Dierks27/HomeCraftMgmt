package com.dierks.homecraft.gui;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.market.MarketItem;
import com.dierks.homecraft.market.MarketService;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * The Amazon Store GUI — opened by right-clicking a placed PC. Browse the live
 * catalog (paginated), then click an item to choose a quantity and a shipping
 * tier at checkout. Also links to the instant Market and to "My Orders". Buying
 * pulls from the finite market stock and moves price (integrated).
 */
public final class StoreMenu extends Menu {

    private static final int PAGE_SIZE = 45;
    private static final int MAX_QTY = 2304;

    private final Player player;
    private int page;

    public StoreMenu(HomeCraftManagement plugin, Player player) {
        super(plugin);
        this.player = player;
        PluginConfig.Store store = plugin.config().store();
        String title = plugin.config().menuTitles().storeFormat()
                .replace("{store}", store.name())
                .replace("{url}", store.displayUrl());
        init(54, Text.of(title));
    }

    @Override
    protected void build() {
        MarketService market = plugin.market();
        List<MarketItem> items = new ArrayList<>(market.catalog());
        page = Math.max(0, Math.min(page, Math.max(0, (int) Math.ceil(items.size() / (double) PAGE_SIZE) - 1)));

        for (int slot = 45; slot < 54; slot++) {
            set(slot, Menus.FILLER, null);
        }

        int start = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE; i++) {
            int idx = start + i;
            if (idx >= items.size()) {
                set(i, null, null);
                continue;
            }
            MarketItem item = items.get(idx);
            long stock = market.state(item.id()).stock();
            boolean out = stock <= 0;
            set(i, Menus.icon(item.material(), item.label(),
                    "&7Buy: &a" + money(market.buyPrice(item.id())) + "&7/ea",
                    "&7Stock: " + (out ? "&cOUT OF STOCK" : "&f" + stock),
                    "&8—",
                    out ? "&cUnavailable" : "&eClick to order"), e -> {
                if (out) {
                    player.sendMessage(Text.of("&c" + item.label() + " &cis out of stock."));
                } else {
                    openOrderQuantity(item);
                }
            });
        }

        if (page > 0) {
            set(45, Menus.icon(Material.ARROW, "&e« Previous"), e -> {
                page--;
                refresh();
            });
        }
        set(46, Menus.icon(Material.CHEST, "&6Marketplace", "&7Buy items other players list", "&7in their Pallets"),
                e -> new com.dierks.homecraft.gui.marketplace.MarketplaceMenu(plugin, player, this::reopen).open(player));
        set(47, Menus.icon(Material.EMERALD, "&aInstant Market", "&7Buy/sell now at market price", "&7(no shipping)"),
                e -> new MarketMenu(plugin, player, this::reopen).open(player));
        set(48, Menus.icon(Material.PLAYER_HEAD, "&5Mini Museum", "&7Browse & collect Minis"),
                e -> new MuseumMenu(plugin, player, this::reopen).open(player));
        set(49, Menus.icon(Material.CHEST_MINECART, "&eMailbox", "&7Track & collect all deliveries"),
                e -> new MailboxMenu(plugin, player, this::reopen).open(player));
        set(50, Menus.balance(plugin, player), null);
        set(51, Menus.icon(Material.BARRIER, "&cClose"), e -> e.getWhoClicked().closeInventory());
        if ((page + 1) * PAGE_SIZE < items.size()) {
            set(53, Menus.icon(Material.ARROW, "&eNext »"), e -> {
                page++;
                refresh();
            });
        }
    }

    private void openOrderQuantity(MarketItem item) {
        MarketService market = plugin.market();
        long stock = market.state(item.id()).stock();
        if (stock <= 0) {
            player.sendMessage(Text.of("&c" + item.label() + " &cis out of stock."));
            return;
        }
        int max = (int) Math.min(stock, MAX_QTY);
        new QuantityMenu(plugin, "Order", item.material(), item.label(), max,
                qty -> {
                    MarketService.Plan plan = market.quoteBuy(item.id(), qty);
                    return List.of(
                            "&7Item cost: &f" + money(plan.total()),
                            "&8+ shipping chosen at checkout");
                },
                qty -> new CheckoutMenu(plugin, player, item, qty, this::reopen).open(player),
                this::reopen).open(player);
    }

    private void reopen() {
        new StoreMenu(plugin, player).open(player);
    }

    private String money(double amount) {
        return plugin.economy().format(amount);
    }
}
