package com.dierks.homecraft.gui;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.market.MarketItem;
import com.dierks.homecraft.market.MarketService;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Selling to Crate: browse what Crate pays for each commodity and sell into it.
 *
 * <p><b>Sell only, and the asymmetry is the point.</b> When you sell, you are the one
 * delivering the goods — there is nothing to ship, so the money is instant. When goods come
 * TO you they have to get there, which is what the store's shipping tiers are: a real cost and
 * a real wait.
 *
 * <p>This screen used to buy as well, at the live price with no shipping and no delay. Nobody
 * would ever choose a tier over that, which made the entire shipping system — the tiers, the
 * Locker, in-transit orders — dead content that existed and was never used. Express already
 * covers "I want it now" at roughly five minutes for a fifth more; instant buying was
 * undercutting a tier that already did the job.
 *
 * <p>Selling ADDS to Crate's stock, which is what makes the market dynamic: more stock, lower
 * price, exactly as if the goods had been brought into a warehouse. See
 * {@code MarketService.sell}.
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
                    "&bCrate pays: &6" + money(market.sellPrice(item.id())),
                    "&7Crate's stock: &f" + stock,
                    "&8—",
                    "&8The more Crate holds, the less it pays.",
                    "&eClick to sell"), e -> openSell(item));
        }

        if (state.page > 0) {
            set(45, Menus.icon(Material.ARROW, "&e« Previous"), e -> {
                state.page--;
                refresh();
            });
        }
        set(47, Menus.balance(plugin, player), null);
        // Buying used to be the left-click on every tile above. Somebody will go looking for
        // it, and a screen that silently stopped doing half of what it did reads as broken
        // rather than changed — so it says where it went, and takes you there.
        set(48, Menus.icon(Material.MINECART, "&7Looking to buy?",
                "&7Orders go through the store, so they",
                "&7can be shipped to your Mailbox.",
                "&8—",
                "&8Express is about five minutes.",
                "&eClick to browse the store"), e -> {
            if (onBack != null) {
                onBack.run();
            } else {
                e.getWhoClicked().closeInventory();
            }
        });
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
                            "&bYou receive: &6" + money(plan.total()),
                            "&7Stock after: &f" + plan.endStock(),
                            "&7New price: &6" + money(plan.endPrice()));
                },
                qty -> {
                    MarketService.TradeResult r = market.sell(player, item.id(), qty);
                    if (r.ok()) {
                        Sounds.received(player);
                    } else {
                        Sounds.refused(player);
                    }
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
