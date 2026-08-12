package com.dierks.homecraft.mini;

import com.dierks.homecraft.util.Keys;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.Locale;

/**
 * Colored filament (Phase 9, §3.5): the Printer's running cost. Each filament is a
 * tagged item mapped to a {@link DyeColor}; the Printer consumes the exact per-colour
 * amounts a card requires. Filament is craftable (data-driven recipes) and buyable.
 */
public final class FilamentItems {

    /** Build a filament item stack of a colour. */
    public ItemStack filament(DyeColor color, int amount) {
        Material base = baseMaterial(color);
        ItemStack item = new ItemStack(base, Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(pretty(color.name()) + " Filament", NamedTextColor.LIGHT_PURPLE)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(java.util.List.of(Component.text("Printer filament", NamedTextColor.DARK_GRAY)
                    .decoration(TextDecoration.ITALIC, false)));
            meta.getPersistentDataContainer().set(Keys.FILAMENT_COLOR, PersistentDataType.STRING, color.name());
            item.setItemMeta(meta);
        }
        return item;
    }

    /** @return the filament colour of an item, or null if it isn't filament. */
    public DyeColor colorOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        String c = item.getItemMeta().getPersistentDataContainer().get(Keys.FILAMENT_COLOR, PersistentDataType.STRING);
        if (c == null) {
            return null;
        }
        try {
            return DyeColor.valueOf(c);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** How many filament units of a colour the player is holding across their inventory. */
    public int count(Player player, DyeColor color) {
        int total = 0;
        for (ItemStack it : player.getInventory().getContents()) {
            if (it != null && color == colorOf(it)) {
                total += it.getAmount();
            }
        }
        return total;
    }

    /** Consume {@code amount} filament of a colour from the player. Returns true if fully consumed. */
    public boolean consume(Player player, DyeColor color, int amount) {
        int remaining = amount;
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack it = contents[i];
            if (it == null || color != colorOf(it)) {
                continue;
            }
            int take = Math.min(remaining, it.getAmount());
            it.setAmount(it.getAmount() - take);
            remaining -= take;
        }
        return remaining <= 0;
    }

    /** The base item a filament colour uses (its matching dye). */
    public Material baseMaterial(DyeColor color) {
        Material m = Material.matchMaterial(color.name() + "_DYE");
        return m != null ? m : Material.GRAY_DYE;
    }

    private String pretty(String enumName) {
        String n = enumName.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }
}
