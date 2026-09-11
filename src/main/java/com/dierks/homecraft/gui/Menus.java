package com.dierks.homecraft.gui;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/** Small helpers for building GUI icons and formatting. */
public final class Menus {

    public static final ItemStack FILLER = icon(Material.GRAY_STAINED_GLASS_PANE, " ");

    private Menus() {
    }

    /**
     * A "Your balance: $X" info icon for shopping GUIs. Rebuilt each time the menu
     * refreshes, so it reflects the player's live Vault balance after every trade.
     */
    public static ItemStack balance(HomeCraftManagement plugin, Player player) {
        String bal = plugin.economy().format(plugin.economy().balance(player));
        return icon(Material.GOLD_INGOT, "&6Your balance", "&f" + bal);
    }

    /** An icon with an '&amp;'-coded name and lore lines. */
    public static ItemStack icon(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.of(name));
            if (lore.length > 0) {
                List<net.kyori.adventure.text.Component> lines = new ArrayList<>(lore.length);
                for (String line : lore) {
                    lines.add(Text.of(line));
                }
                meta.lore(lines);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * Force the enchantment shimmer on or off.
     *
     * <p>Always pass an explicit value rather than leaving the override unset. Some materials
     * glint on their own — {@link Material#NETHER_STAR} and {@link Material#ENCHANTED_BOOK} among
     * them — so an unset override would leave such a tile shimmering permanently, and a glint used
     * to mean "selected" would be telling a lie on the one tab that is always on screen.
     */
    public static ItemStack glint(ItemStack item, boolean on) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setEnchantmentGlintOverride(on);
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * Put a number on the icon as its stack count.
     *
     * <p>The only text an inventory tile shows without a hover. Useful when a row of otherwise
     * identical buttons differ by a magnitude — three red panes are three red panes until one of
     * them says 64.
     */
    public static ItemStack count(ItemStack item, int amount) {
        item.setAmount(Math.max(1, Math.min(64, amount)));
        return item;
    }

    /** Format a real-time remaining duration (ms) as e.g. "1d 3h", "2h 5m", "45s". */
    public static String duration(long millis) {
        if (millis <= 0) {
            return "now";
        }
        long seconds = millis / 1000;
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        if (days > 0) {
            return days + "d " + hours + "h";
        }
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        if (minutes > 0) {
            return minutes + "m " + secs + "s";
        }
        return secs + "s";
    }
}
