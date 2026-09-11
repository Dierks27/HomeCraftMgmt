package com.dierks.homecraft.gui;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.market.MarketItem;
import com.dierks.homecraft.market.MarketService;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * The instant Market GUI: browse every commodity's live buy/sell price and
 * stock, then left-click to buy or right-click to sell (quantity picker). Trades
 * hit the same finite-stock engine as the Amazon store — no shipping, no
 * commands. Reachable from the PC's store.
 */
public final class MarketMenu extends Menu {

    private static final int MAX_QTY = 2304;

    private final Player player;
    private final Runnable onBack;

    public MarketMenu(HomeCraftManagement plugin, Player player, Runnable onBack) {
        super(plugin);
        this.player = player;
        this.onBack = onBack;
        init(54, Text.of(plugin.config().menuTitles().market()));
    }

    @Override
    protected void build() {
        MarketService market = plugin.market();
        BrowseState.Shop state = plugin.browseState().market(player);
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
            set(slot, Menus.icon(item.material(), item.label(),
                    "&7Buy: &6" + money(market.buyPrice(item.id())),
                    "&7Sell: &6" + money(market.sellPrice(item.id())),
                    "&7Stock: " + (stock <= 0 ? "&cOUT OF STOCK" : "&f" + stock),
                    "&8—",
                    "&eLeft-click &7to buy",
                    "&eRight-click &7to sell"), e -> {
                if (e.getClick().isRightClick()) {
                    openSell(item);
                } else {
                    openBuy(item);
                }
            });
        }

        if (state.page > 0) {
            set(45, Menus.icon(Material.ARROW, "&e« Previous"), e -> {
                state.page--;
                refresh();
            });
        }
        set(47, Menus.balance(plugin, player), null);
        set(49, Menus.icon(Material.BARRIER, "&cBack"), e -> {
            if (onBack != null) {
                onBack.run();
            } else {
                e.getWhoClicked().closeInventory();
            }
        });
        set(51, Departments.sortButton(state.sort), e -> {
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

    private void openBuy(MarketItem item) {
        MarketService market = plugin.market();
        long stock = market.state(item.id()).stock();
        if (stock <= 0) {
            player.sendMessage(Text.of("&c" + item.label() + " &cis out of stock."));
            return;
        }
        int max = (int) Math.min(stock, MAX_QTY);
        new QuantityMenu(plugin, "Buy", item.material(), item.label(), max,
                qty -> {
                    MarketService.Plan plan = market.quoteBuy(item.id(), qty);
                    return List.of(
                            "&7Total cost: &6" + money(plan.total()),
                            "&7Stock after: &f" + plan.endStock(),
                            "&7New price: &6" + money(plan.endPrice()));
                },
                qty -> {
                    MarketService.TradeResult r = market.buy(player, item.id(), qty);
                    player.sendMessage(r.ok()
                            ? Text.of("&aBought &f" + r.qty() + " &afor &6" + money(r.amount()))
                            : Text.of("&c" + r.error()));
                    reopen();
                },
                this::reopen).open(player);
    }

    private void openSell(MarketItem item) {
        MarketService market = plugin.market();
        int have = countHeld(item.material());
        if (have <= 0) {
            player.sendMessage(Text.of("&cYou have no " + item.label() + " &cto sell."));
            return;
        }
        int max = Math.min(have, MAX_QTY);
        new QuantityMenu(plugin, "Sell", item.material(), item.label(), max,
                qty -> {
                    MarketService.Plan plan = market.quoteSell(item.id(), qty);
                    return List.of(
                            "&7You receive: &6" + money(plan.total()),
                            "&7Stock after: &f" + plan.endStock(),
                            "&7New price: &6" + money(plan.endPrice()));
                },
                qty -> {
                    MarketService.TradeResult r = market.sell(player, item.id(), qty);
                    player.sendMessage(r.ok()
                            ? Text.of("&aSold &f" + r.qty() + " &afor &a+" + money(r.amount()))
                            : Text.of("&c" + r.error()));
                    reopen();
                },
                this::reopen).open(player);
    }

    private void reopen() {
        // The department/page/sort ride in BrowseState, so a fresh instance lands back on
        // the same view the player left.
        new MarketMenu(plugin, player, onBack).open(player);
    }

    private int countHeld(Material material) {
        int count = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack != null && stack.getType() == material) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    private String money(double amount) {
        return plugin.economy().format(amount);
    }
}
