package com.dierks.homecraft.gui.admin;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.market.MarketDraft;
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
 * Browser for {@code market.catalog} — the commodities the market trades and the Crate
 * store sells. Click a commodity to edit it; removal lives on the edit screen, where the
 * live stock and order counts that gate it are visible.
 */
public final class MarketAdminMenu extends Menu {

    private static final int PAGE_SIZE = 45;

    private final Player player;
    private final Runnable onBack;
    private int page;

    public MarketAdminMenu(HomeCraftManagement plugin, Player player, Runnable onBack) {
        super(plugin);
        this.player = player;
        this.onBack = onBack;
        init(54, Text.of("&aMarket Catalog"));
    }

    @Override
    protected void build() {
        // catalog() is a Collection, not a List — copy before paging.
        List<MarketItem> list = new ArrayList<>(plugin.market().catalog());
        page = Math.max(0, Math.min(page, Math.max(0, (int) Math.ceil(list.size() / (double) PAGE_SIZE) - 1)));

        for (int slot = 45; slot < 54; slot++) {
            set(slot, Menus.FILLER, null);
        }

        int start = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE; i++) {
            int idx = start + i;
            if (idx >= list.size()) {
                set(i, null, null);
                continue;
            }
            MarketItem item = list.get(idx);
            set(i, icon(item), e ->
                    new MarketEditMenu(plugin, player, MarketDraft.from(item), true, this::reopen).open(player));
        }

        if (page > 0) {
            set(45, Menus.icon(Material.ARROW, "&e« Previous"), e -> {
                page--;
                refresh();
            });
        }
        set(48, Menus.icon(Material.LIME_DYE, "&a+ New commodity",
                "&7Add a tradable item to the market."), e ->
                new MarketEditMenu(plugin, player, new MarketDraft(), false, this::reopen).open(player));
        set(49, Menus.icon(Material.BARRIER, "&cBack"), e -> {
            if (onBack != null) {
                onBack.run();
            } else {
                e.getWhoClicked().closeInventory();
            }
        });
        set(51, Menus.icon(Material.PAPER, "&8" + list.size() + " commodity(ies)",
                "&7Edited here, stored in &fconfig.yml&7.",
                "&7Stock lives in the database and is",
                "&7kept even when a row is removed."), null);
        if ((page + 1) * PAGE_SIZE < list.size()) {
            set(53, Menus.icon(Material.ARROW, "&eNext »"), e -> {
                page++;
                refresh();
            });
        }
    }

    /** One catalog row, with the live figures an admin needs to judge it. */
    private ItemStack icon(MarketItem item) {
        MarketState st = plugin.market().state(item.id());
        long stock = st == null ? 0 : st.stock();
        List<String> lore = new ArrayList<>();
        lore.add("&7id: &f" + item.id());
        lore.add("&7Stock: &f" + stock + " &8/ max " + MarketService.maxStock(item));
        lore.add("&7Band: &6" + plugin.economy().format(item.floor())
                + " &7– &6" + plugin.economy().format(item.ceiling()));
        lore.add("&7Now: &6" + plugin.economy().format(plugin.market().price(item.id())));
        if (item.maxDailySell() > 0 || item.maxDailyBuy() > 0) {
            lore.add("&8Daily caps: sell " + item.maxDailySell() + " / buy " + item.maxDailyBuy());
        }
        lore.add("&eClick to edit.");
        return Menus.icon(item.material(), "&e" + item.label(), lore.toArray(new String[0]));
    }

    private void reopen() {
        new MarketAdminMenu(plugin, player, onBack).open(player);
    }
}
