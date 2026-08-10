package com.dierks.homecraft.gui;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.market.MarketItem;
import com.dierks.homecraft.market.MarketService;
import com.dierks.homecraft.market.MarketState;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The instant Market GUI: browse every commodity's live buy/sell price and
 * stock, then left-click to buy or right-click to sell (quantity picker). Trades
 * hit the same finite-stock engine as the Amazon store — no shipping, no
 * commands. Reachable from the PC's store.
 */
public final class MarketMenu extends Menu {

    private static final int PAGE_SIZE = 45;
    private static final int MAX_QTY = 2304;

    private final Player player;
    private final Runnable onBack;
    private int page;

    public MarketMenu(HomeCraftManagement plugin, Player player, Runnable onBack) {
        super(plugin);
        this.player = player;
        this.onBack = onBack;
        init(54, Text.of("&1Market — instant buy/sell"));
    }

    @Override
    protected void build() {
        MarketService market = plugin.market();
        List<MarketItem> items = new ArrayList<>(market.catalog());
        int pages = Math.max(1, (int) Math.ceil(items.size() / (double) PAGE_SIZE));
        page = Math.max(0, Math.min(page, pages - 1));

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
            MarketState st = market.state(item.id());
            long stock = st.stock();
            set(i, Menus.icon(item.material(), item.label(),
                    "&7Buy: &a" + money(market.buyPrice(item.id())),
                    "&7Sell: &c" + money(market.sellPrice(item.id())),
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

        if (page > 0) {
            set(45, Menus.icon(Material.ARROW, "&e« Previous"), e -> {
                page--;
                refresh();
            });
        }
        set(49, Menus.icon(Material.BARRIER, "&cBack"), e -> {
            if (onBack != null) {
                onBack.run();
            } else {
                e.getWhoClicked().closeInventory();
            }
        });
        if ((page + 1) * PAGE_SIZE < items.size()) {
            set(53, Menus.icon(Material.ARROW, "&eNext »"), e -> {
                page++;
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
                            "&7Total cost: &f" + money(plan.total()),
                            "&7Stock after: &f" + plan.endStock(),
                            "&7New price: &f" + money(plan.endPrice()));
                },
                qty -> {
                    MarketService.TradeResult r = market.buy(player, item.id(), qty);
                    player.sendMessage(r.ok()
                            ? Text.of("&aBought &f" + r.qty() + " &afor &f" + money(r.amount()))
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
                            "&7You receive: &a" + money(plan.total()),
                            "&7Stock after: &f" + plan.endStock(),
                            "&7New price: &f" + money(plan.endPrice()));
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
