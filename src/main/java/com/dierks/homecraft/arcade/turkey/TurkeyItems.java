package com.dierks.homecraft.arcade.turkey;

import com.dierks.homecraft.util.Text;
import com.dierks.homecraft.util.TokenPrizes;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Catch the Turkey's keepsake (0.38.6): the Turkey Feather, one for each catch, with the event's year in its name.
 *
 * <p>It carries {@code hcm:token_prize}, so crafting it into arrows, a brush or a book is refused, and so is
 * selling it; it carries no {@code prize_kind}, so a right-click does nothing. A feather can't be placed, so the tag
 * is never lost. {@code Collectors.reserved} keeps config rows off its id prefix.
 */
public final class TurkeyItems {

    /** The id prefix: {@code turkey_feather_<year>}. */
    public static final String FEATHER_PREFIX = "turkey_feather_";

    private TurkeyItems() {
    }

    /** {@code turkey_feather_<year>}. */
    public static String featherId(int year) {
        return FEATHER_PREFIX + year;
    }

    /** The feather's name, with the year: a Bedrock player sees lore only on a long press. */
    public static String featherName(int year) {
        return "&6Turkey Feather " + year;
    }

    /** The feather's lore: the holiday and year, what it was for, then "Arcade prize". */
    static List<String> featherLore(int year) {
        return List.of("&7Thanksgiving " + year, "&7You caught the turkey!", "&8Arcade prize");
    }

    /** One Turkey Feather of the year's, glinting. Equal feathers stack. */
    public static ItemStack feather(int year) {
        ItemStack item = new ItemStack(Material.FEATHER);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(Text.of(featherName(year)));
        List<Component> lore = new ArrayList<>();
        for (String line : featherLore(year)) {
            lore.add(Text.of(line));
        }
        meta.lore(lore);
        TokenPrizes.tag(meta, featherId(year));
        meta.setEnchantmentGlintOverride(true);
        item.setItemMeta(meta);
        return item;
    }
}
