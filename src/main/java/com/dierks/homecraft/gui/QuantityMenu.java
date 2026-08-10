package com.dierks.homecraft.gui;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;

/**
 * Reusable quantity picker: −/＋ buttons, a live preview of the item + the
 * cost/proceeds for the chosen amount, and confirm/back. Shared by the instant
 * market (buy/sell) and the Amazon order flow.
 */
public final class QuantityMenu extends Menu {

    private final String title;
    private final Material iconMaterial;
    private final String itemName;
    private final int max;
    private final IntFunction<List<String>> preview;
    private final IntConsumer onConfirm;
    private final Runnable onBack;

    private int qty = 1;

    public QuantityMenu(HomeCraftManagement plugin, String title, Material iconMaterial, String itemName,
                        int max, IntFunction<List<String>> preview, IntConsumer onConfirm, Runnable onBack) {
        super(plugin);
        this.title = title;
        this.iconMaterial = iconMaterial;
        this.itemName = itemName;
        this.max = Math.max(1, max);
        this.preview = preview;
        this.onConfirm = onConfirm;
        this.onBack = onBack;
        init(27, Text.of(title));
    }

    private void changeQty(int delta) {
        qty = Math.max(1, Math.min(max, qty + delta));
        refresh();
    }

    @Override
    protected void build() {
        for (int i = 0; i < 27; i++) {
            set(i, Menus.FILLER, null);
        }

        set(10, Menus.icon(Material.RED_STAINED_GLASS_PANE, "&c-64"), e -> changeQty(-64));
        set(11, Menus.icon(Material.RED_STAINED_GLASS_PANE, "&c-16"), e -> changeQty(-16));
        set(12, Menus.icon(Material.RED_STAINED_GLASS_PANE, "&c-1"), e -> changeQty(-1));

        set(13, previewIcon(), null);

        set(14, Menus.icon(Material.LIME_STAINED_GLASS_PANE, "&a+1"), e -> changeQty(1));
        set(15, Menus.icon(Material.LIME_STAINED_GLASS_PANE, "&a+16"), e -> changeQty(16));
        set(16, Menus.icon(Material.LIME_STAINED_GLASS_PANE, "&a+64"), e -> changeQty(64));

        set(21, Menus.icon(Material.EMERALD_BLOCK, "&aConfirm", "&7Quantity: &f" + qty), e -> onConfirm.accept(qty));
        set(23, Menus.icon(Material.BARRIER, "&cBack"), e -> {
            if (onBack != null) {
                onBack.run();
            } else {
                e.getWhoClicked().closeInventory();
            }
        });
    }

    private ItemStack previewIcon() {
        ItemStack item = new ItemStack(iconMaterial, Math.max(1, Math.min(64, qty)));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.of("&f" + itemName + " &7x" + qty));
            List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
            for (String line : preview.apply(qty)) {
                lore.add(Text.of(line));
            }
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }
}
