package com.dierks.homecraft.mini;

import com.dierks.homecraft.util.Keys;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/**
 * Builds the Card Binder item (Round 3a): a single-slot album that stores many Cards
 * and doubles as a set tracker. Right-click to open. Marked in PDC
 * ({@link Keys#BINDER_ITEM}); the stored cards live per-player in the DB, not on the
 * item.
 */
public final class BinderItems {

    /** A Card Binder item. */
    public ItemStack binder() {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(Component.text("📕 Card Binder", NamedTextColor.LIGHT_PURPLE)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                line("Stores your Cards in one place.", NamedTextColor.GRAY),
                line("Right-click to open, deposit,", NamedTextColor.GRAY),
                line("withdraw, and track your sets.", NamedTextColor.GRAY)));
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(Keys.BINDER_ITEM, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    /** @return true if the item is a Card Binder. */
    public boolean isBinder(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        Byte b = item.getItemMeta().getPersistentDataContainer().get(Keys.BINDER_ITEM, PersistentDataType.BYTE);
        return b != null && b == 1;
    }

    private Component line(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }
}
