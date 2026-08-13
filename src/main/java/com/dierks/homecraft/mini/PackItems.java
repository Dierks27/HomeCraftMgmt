package com.dierks.homecraft.mini;

import com.dierks.homecraft.util.Keys;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the physical <b>Card Pack</b> item (Round 3a): a sealed booster you get from
 * the store or {@code /hcm give pack}, then right-click to open into a reveal of N
 * random Cards. Distinct from a Card (a Card is a player-head; a Pack is a sealed
 * paper booster). The pack id lives in the item PDC ({@link Keys#PACK_ID}).
 */
public final class PackItems {

    /** A sealed Card Pack item for a pack type. */
    public ItemStack pack(Pack.PackDef def, String priceText) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(Component.text("❒ " + def.displayName(), NamedTextColor.LIGHT_PURPLE)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line("Card Pack", NamedTextColor.DARK_GRAY));
        lore.add(line("Contains " + def.cardCount() + " random Card"
                + (def.cardCount() == 1 ? "" : "s") + ".", NamedTextColor.GRAY));
        if (priceText != null && !priceText.isBlank()) {
            lore.add(line("Value: " + priceText, NamedTextColor.GRAY));
        }
        lore.add(Component.empty());
        lore.add(Component.text("Right-click to open.", NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(Keys.PACK_ID, PersistentDataType.STRING, def.id());
        item.setItemMeta(meta);
        return item;
    }

    /** @return the pack id an item opens, or null if it isn't a Card Pack. */
    public String packIdOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(Keys.PACK_ID, PersistentDataType.STRING);
    }

    private Component line(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }
}
