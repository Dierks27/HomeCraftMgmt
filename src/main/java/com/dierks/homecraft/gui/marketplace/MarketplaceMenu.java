package com.dierks.homecraft.gui.marketplace;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.storage.PalletDao;
import com.dierks.homecraft.util.Items;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * The Crate Marketplace: browse every player Pallet listing as a visual grid
 * (item + price + seller), filter by department tabs, and buy — shipping the item
 * to your Mailbox. Read-only browsing is public; buying needs funds.
 */
public final class MarketplaceMenu extends Menu {

    private static final int GRID_START = 9;
    private static final int GRID_SIZE = 36; // slots 9..44

    private final Player player;
    private final Runnable onBack;
    private String department; // null = All
    private int page;

    public MarketplaceMenu(HomeCraftManagement plugin, Player player, Runnable onBack) {
        super(plugin);
        this.player = player;
        this.onBack = onBack;
        init(54, Text.of("&6Crate Marketplace"));
    }

    @Override
    protected void build() {
        for (int i = 0; i < 54; i++) {
            set(i, i >= 45 ? Menus.FILLER : null, null);
        }
        buildTabs();

        List<PalletDao.Listing> listings = plugin.pallets().listActive(department);
        int pages = Math.max(1, (int) Math.ceil(listings.size() / (double) GRID_SIZE));
        page = Math.max(0, Math.min(page, pages - 1));

        if (listings.isEmpty()) {
            set(22, Menus.icon(Material.PAPER, "&7Nothing listed here",
                    "&8Check another department, or list something in a Pallet."), null);
        }
        int start = page * GRID_SIZE;
        for (int i = 0; i < GRID_SIZE; i++) {
            int idx = start + i;
            if (idx >= listings.size()) {
                continue;
            }
            PalletDao.Listing l = listings.get(idx);
            set(GRID_START + i, listingIcon(l),
                    e -> new MarketplaceCheckoutMenu(plugin, player, l, this::reopen).open(player));
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
        set(50, Menus.balance(plugin, player), null);
        if ((page + 1) * GRID_SIZE < listings.size()) {
            set(53, Menus.icon(Material.ARROW, "&eNext »"), e -> {
                page++;
                refresh();
            });
        }
    }

    private void buildTabs() {
        set(0, tab(Material.NETHER_STAR, "All", department == null), e -> {
            department = null;
            page = 0;
            refresh();
        });
        List<String> depts = plugin.config().marketplace().departments();
        for (int i = 0; i < depts.size() && i < 8; i++) {
            String d = depts.get(i);
            set(1 + i, tab(Material.BOOKSHELF, d, d.equals(department)), e -> {
                department = d;
                page = 0;
                refresh();
            });
        }
    }

    private ItemStack tab(Material mat, String name, boolean active) {
        return Menus.icon(active ? Material.ENCHANTED_BOOK : mat,
                (active ? "&a» " : "&e") + name, active ? "&8showing" : "&7Click to view");
    }

    private ItemStack listingIcon(PalletDao.Listing l) {
        ItemStack item = Items.fromBase64(l.itemB64());
        if (item == null) {
            return Menus.icon(Material.BARRIER, "&cUnreadable listing");
        }
        ItemStack icon = item.clone();
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            List<net.kyori.adventure.text.Component> lore = meta.hasLore()
                    ? new ArrayList<>(meta.lore()) : new ArrayList<>();
            lore.add(Text.of("&8—"));
            lore.add(Text.of("&7Price: &6" + plugin.economy().format(l.price())));
            lore.add(Text.of("&7Seller: &f" + sellerName(l)));
            lore.add(Text.of("&7In stock: &f" + l.stock()));
            lore.add(Text.of("&eClick to buy"));
            meta.lore(lore);
            icon.setItemMeta(meta);
        }
        return icon;
    }

    private String sellerName(PalletDao.Listing l) {
        String n = Bukkit.getOfflinePlayer(l.owner()).getName();
        return n != null ? n : l.owner().toString().substring(0, 8);
    }

    private void reopen() {
        new MarketplaceMenu(plugin, player, onBack).open(player);
    }
}
