package com.dierks.homecraft.gui;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.market.MarketItem;
import com.dierks.homecraft.market.MarketService;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * The Amazon Store GUI — opened by right-clicking a placed PC. Click an item to choose a
 * quantity and a shipping tier at checkout. Buying pulls from the finite market stock and
 * moves price (integrated).
 *
 * <p>Laid out as row 0 = department tabs, rows 1–4 = the item grid, row 5 = navigation.
 * Items are classified by the same {@link com.dierks.homecraft.marketplace.Categorizer}
 * that sorts Pallet listings, so a material sits in the same department everywhere. The
 * selected department, page and sort live in {@link BrowseState}, so stepping into checkout
 * and back returns to the same view rather than page 1 of "All".
 */
public final class StoreMenu extends Menu {

    private static final int MAX_QTY = 2304;

    private final Player player;

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
        BrowseState.Shop state = plugin.browseState().store(player);
        List<MarketItem> items = Departments.view(plugin, state.department, state.sort);

        int pages = Math.max(1, (int) Math.ceil(items.size() / (double) Departments.PAGE_SIZE));
        state.page = Math.max(0, Math.min(state.page, pages - 1));

        Departments.paintTabs(this, plugin, state, this::refresh);

        for (int slot = 45; slot < 54; slot++) {
            set(slot, Menus.FILLER, null);
        }

        int start = state.page * Departments.PAGE_SIZE;
        for (int i = 0; i < Departments.PAGE_SIZE; i++) {
            int slot = Departments.GRID_START + i;
            int idx = start + i;
            if (idx >= items.size()) {
                set(slot, null, null);
                continue;
            }
            MarketItem item = items.get(idx);
            long stock = Departments.stock(market, item.id());
            boolean out = stock <= 0;
            set(slot, Menus.icon(item.material(), item.label(),
                    "&aBuy: &6" + money(market.buyPrice(item.id())) + "&7/ea",
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

        if (state.page > 0) {
            set(45, Menus.icon(Material.ARROW, "&e« Previous"), e -> {
                state.page--;
                refresh();
            });
        }
        // Nine slots, and the Store is the hub: balance, the five destinations and Sort fill
        // them between the page arrows. No Close tile — Esc shuts any inventory, and the
        // balance readout earns the slot more than a second way to do what Esc already does.
        set(46, Menus.icon(Material.GOLD_INGOT, "&6Your balance",
                "&6" + money(plugin.economy().balance(player)),
                "&8—",
                "&8Press Esc to close the store."), null);
        set(47, Menus.icon(Material.EMERALD, "&aInstant Market",
                "&7Buy and sell now at market price — no shipping."),
                e -> new MarketMenu(plugin, player, this::reopen).open(player));
        set(48, Menus.icon(Material.CHEST, "&6Marketplace",
                "&7Browse what other players list in their Pallets."),
                e -> new com.dierks.homecraft.gui.marketplace.MarketplaceMenu(plugin, player, this::reopen).open(player));
        set(49, Menus.icon(Material.PAPER, "&bCard Packs",
                "&7Buy booster packs and open them for Cards."),
                e -> new com.dierks.homecraft.gui.mini.PackShopMenu(plugin, player, this::reopen).open(player));
        set(50, Menus.icon(Material.PLAYER_HEAD, "&5Mini Museum",
                "&7Browse every collectible and what you have."),
                e -> new MuseumMenu(plugin, player, this::reopen).open(player));
        set(51, Menus.icon(Material.CHEST_MINECART, "&eMailbox & Orders",
                "&7Track deliveries and collect what has arrived."),
                e -> new MailboxMenu(plugin, player, this::reopen).open(player));
        set(52, Departments.sortButton(state.sort), e -> {
            state.sort = state.sort.next();
            state.page = 0;
            refresh();
        });
        if ((state.page + 1) * Departments.PAGE_SIZE < items.size()) {
            set(53, Menus.icon(Material.ARROW, "&eNext »",
                    "&8Page " + (state.page + 1) + " of " + pages), e -> {
                state.page++;
                refresh();
            });
        }
    }

    private void openOrderQuantity(MarketItem item) {
        MarketService market = plugin.market();
        long stock = Departments.stock(market, item.id());
        if (stock <= 0) {
            player.sendMessage(Text.of("&c" + item.label() + " &cis out of stock."));
            return;
        }
        int max = (int) Math.min(stock, MAX_QTY);
        new QuantityMenu(plugin, "Order", item.material(), item.label(), max,
                qty -> {
                    MarketService.Plan plan = market.quoteBuy(item.id(), qty);
                    return List.of(
                            "&7Item cost: &6" + money(plan.total()),
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
