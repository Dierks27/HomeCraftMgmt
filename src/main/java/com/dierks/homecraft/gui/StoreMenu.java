package com.dierks.homecraft.gui;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.market.MarketItem;
import com.dierks.homecraft.market.MarketService;
import com.dierks.homecraft.market.sim.Badge;
import com.dierks.homecraft.market.sim.ItemStatus;
import com.dierks.homecraft.market.sim.MarketEvent;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
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
 *
 * <p><b>Live market (0.33).</b> While it runs, a tile wears its badge ({@link MarketLabels}):
 * the name suffix, the extra lore under the price, and a shimmer for HOT/DEAL; "Sell to Crate"
 * (slot 47) names what is HOT and the latest headline. With it off, every tile is exactly what
 * it was.
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
        // "Hot & Deals" only exists while the live market runs; with it off the grid and the
        // sort button are 0.32's name order.
        boolean hot = MarketNewsMenu.live(plugin) != null;
        state.sort = state.sort.usable(hot);
        List<MarketItem> items = Departments.view(plugin, state.department, state.sort);

        int pages = Math.max(1, (int) Math.ceil(items.size() / (double) Departments.PAGE_SIZE));
        state.page = Math.max(0, Math.min(state.page, pages - 1));

        Departments.paintTabs(this, plugin, state, this::refresh);

        for (int slot = 45; slot < 54; slot++) {
            set(slot, Menus.FILLER, null);
        }

        long now = System.currentTimeMillis();
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
            ItemStatus st = MarketNewsMenu.status(plugin, item.id());
            Badge badge = MarketNewsMenu.badge(st);
            List<String> lore = new ArrayList<>();
            lore.add("&aBuy: &6" + money(market.buyPrice(item.id())) + "&7/ea");
            if (st != null) {
                long cap = buyCap(plugin, item);
                lore.addAll(MarketLabels.storeLore(badge, st.fading(), st.pct(),
                        money(MarketNewsMenu.usualQuote(plugin, item, st.usual(), false)),
                        MarketNewsMenu.left(st, now), cap));
                lore.addAll(MarketNewsMenu.capLine(badge, false, cap));
            }
            lore.add("&7Stock: " + (out ? "&cOUT OF STOCK" : "&f" + stock));
            lore.add("&8—");
            lore.add(out ? "&cUnavailable" : "&eClick to order");
            ItemStack icon = Menus.icon(item.material(), item.label() + MarketLabels.nameSuffix(badge, false),
                    lore.toArray(new String[0]));
            if (MarketLabels.glint(badge)) {
                Menus.glint(icon, true);
            }
            set(slot, icon, e -> {
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
        // Not a branded destination — the thing you do. You sell to Crate; Crate ships to
        // you. Naming it after a place invited the question of why you cannot also buy there.
        set(47, sellButton(), e -> new MarketMenu(plugin, player, this::reopen).open(player));
        set(48, Menus.icon(Material.CHEST, "&6Marketplace",
                "&7Browse what other players list in their Pallets."),
                e -> new com.dierks.homecraft.gui.marketplace.MarketplaceMenu(plugin, player, this::reopen).open(player));
        // The Store used to be the PC's root screen, which is why slot 49 carried a
        // destination rather than an exit. The Site launcher is the root now, so 49 goes
        // back to being what it is everywhere else: the way out. Card Packs moved to the
        // launcher, which is where every Site-to-Site jump belongs.
        set(49, Menus.icon(Material.BARRIER, "&cBack to the PC"),
                e -> com.dierks.homecraft.gui.SiteLauncherMenu.open(plugin, player));
        set(50, Menus.icon(Material.PLAYER_HEAD, "&5Mini Museum",
                "&7Browse every collectible and what you have."),
                e -> new MuseumMenu(plugin, player, this::reopen).open(player));
        set(51, Menus.icon(Material.CHEST_MINECART, "&eMailbox & Orders",
                "&7Track deliveries and collect what has arrived."),
                e -> new MailboxMenu(plugin, player, this::reopen).open(player));
        set(52, Departments.sortButton(state.sort, hot), e -> {
            state.sort = state.sort.next(hot);
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

    /**
     * Slot 47, "Sell to Crate". While the live market runs its lore also names up to three HOT
     * items and the latest headline — the nudge to go and sell.
     */
    private ItemStack sellButton() {
        List<String> lore = new ArrayList<>(List.of(
                "&7Crate buys your goods at the live price.",
                "&7Paid on the spot — you delivered them.",
                "&8—",
                "&8Every sale raises Crate's stock,",
                "&8which is what moves the price."));
        if (MarketNewsMenu.live(plugin) != null) {
            List<MarketEvent> latest = MarketNewsMenu.news(plugin, 1);
            List<String> extra = MarketLabels.sellButtonLore(MarketNewsMenu.names(plugin, Badge.HOT),
                    latest.isEmpty() ? "" : MarketNewsMenu.headline(plugin, latest.get(0)));
            if (!extra.isEmpty()) {
                lore.add("&8—");
                lore.addAll(extra);
            }
        }
        return Menus.icon(Material.EMERALD, "&aSell to Crate", lore.toArray(new String[0]));
    }

    /**
     * The DEAL/DOWN "Limit N a day" to show: the event buy cap while its event is showing its
     * badge, 0 otherwise (a DEAL's silent ramp, a DOWN's tail — the cap binds but must not give
     * the event away) and while the live market is off ({@link MarketNewsMenu#shownCap}). The
     * DEAL/DOWN badge lore says it, and {@link MarketNewsMenu#capLine} says it under any other.
     */
    private static long buyCap(HomeCraftManagement plugin, MarketItem item) {
        return MarketNewsMenu.shownCap(plugin, item, false);
    }

    private void openOrderQuantity(MarketItem item) {
        openOrderFor(plugin, player, item, this::reopen);
    }

    /**
     * Open the order quantity for one item, then checkout (the Market News menu's DOWN/DEAL
     * entries). Back, from either screen, runs {@code onBack}; with none it lands on the Store.
     */
    static void openOrderFor(HomeCraftManagement plugin, Player player, MarketItem item, Runnable onBack) {
        Runnable back = onBack != null ? onBack : () -> new StoreMenu(plugin, player).open(player);
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
                    List<String> lines = new ArrayList<>(List.of(
                            "&7Item cost: &6" + plugin.economy().format(plan.total()),
                            "&8+ shipping chosen at checkout"));
                    ItemStatus st = MarketNewsMenu.status(plugin, item.id());
                    if (st != null) {
                        long cap = buyCap(plugin, item);
                        lines.addAll(MarketLabels.previewLines(st.badge(), false, cap));
                        lines.addAll(MarketNewsMenu.capLine(st.badge(), false, cap));
                    }
                    return lines;
                },
                qty -> new CheckoutMenu(plugin, player, item, qty, back).open(player),
                back).open(player);
    }

    private void reopen() {
        new StoreMenu(plugin, player).open(player);
    }

    private String money(double amount) {
        return plugin.economy().format(amount);
    }
}
