package com.dierks.homecraft.arcade.candy;

import com.dierks.homecraft.util.Keys;
import com.dierks.homecraft.util.Text;
import com.dierks.homecraft.util.TokenPrizes;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.FoodProperties;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Candy Apple shower's items (HALLOWEEN-SPEC D13, D15): the Candy Apple, the Golden Candy Apple and the
 * Trick-or-Treat Bag, each with its ids from the event's year.
 *
 * <p>All three carry {@code hcm:token_prize}, so every money, craft, brew and trade surface refuses them
 * (crafting a candy apple into a sellable golden apple included), and none carries {@code prize_kind}, so the
 * prize listener's right-click leaves them alone. The two apples also carry {@code hcm:candy_item} (the year):
 * candy is known by that key and never by an id prefix, so it gets the treat, is meant to despawn, and is the
 * one thing a prize bundle takes. The bag doesn't: it is a collector, kept on death and never despawning.
 *
 * <p>Equal candies stack (the same name, lore and tags), so the stack in the bag shows how many she has.
 */
public final class CandyItems {

    /** The id prefixes; {@code Collectors.reserved} keeps config rows off them. */
    static final String APPLE_PREFIX = "candy_apple_";
    static final String GOLDEN_PREFIX = "candy_golden_";
    static final String BAG_PREFIX = "bag_trick_or_treat_";

    static final String APPLE_NAME = "&cCandy Apple";
    static final String GOLDEN_NAME = "&6Golden Candy Apple";

    /** The red apple's food: an apple's, but edible when full (on Java; Geyser sends no food component). */
    static final int NUTRITION = 4;
    static final float SATURATION = 2.4f;

    private CandyItems() {
    }

    /** {@code candy_apple_<year>}. */
    public static String appleId(int year) {
        return APPLE_PREFIX + year;
    }

    /** {@code candy_golden_<year>}. */
    public static String goldenId(int year) {
        return GOLDEN_PREFIX + year;
    }

    /** {@code bag_trick_or_treat_<year>}. */
    public static String bagId(int year) {
        return BAG_PREFIX + year;
    }

    /** The bag's name, with the year: a Bedrock player sees lore only on a long press. */
    public static String bagName(int year) {
        return "&6Trick-or-Treat Bag " + year;
    }

    /** A candy's lore: the year, "Very rare!" for a golden one, how to use it, then "Arcade prize". */
    static List<String> candyLore(int year, boolean golden) {
        List<String> out = new ArrayList<>();
        out.add("&7Halloween " + year);
        out.add("&7Eat it for a treat!");
        if (golden) {
            out.add("&eVery rare!");
        }
        out.add("&8Arcade prize");
        return out;
    }

    /** The bag's lore: what it was for, the year's collector line, "Yours for good!", the toy, "Arcade prize". */
    static List<String> bagLore(int year, int goal) {
        return List.of(
                "&7You found " + goal + " candy apples!",
                "&6Halloween " + year + " collector",
                "&aYours for good!",
                "&7Right-click: trick or treat!",
                "&8Arcade prize");
    }

    /** A Candy Apple: a glinting apple, edible when full on Java. */
    public static ItemStack apple(int year) {
        return candy(year, false);
    }

    /** A Golden Candy Apple: a glinting golden apple with vanilla's food and effects. */
    public static ItemStack golden(int year) {
        return candy(year, true);
    }

    /** One candy of the year's, golden or red, amount 1. */
    public static ItemStack candy(int year, boolean golden) {
        ItemStack item = new ItemStack(golden ? Material.GOLDEN_APPLE : Material.APPLE);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(Text.of(golden ? GOLDEN_NAME : APPLE_NAME));
        meta.lore(lines(candyLore(year, golden)));
        TokenPrizes.tag(meta, golden ? goldenId(year) : appleId(year));
        meta.getPersistentDataContainer().set(Keys.CANDY_ITEM, PersistentDataType.INTEGER, year);
        meta.setEnchantmentGlintOverride(true);
        item.setItemMeta(meta);
        if (!golden) {
            // On last, after the meta, as the cap's look is: the golden apple keeps vanilla's food.
            item.setData(DataComponentTypes.FOOD, FoodProperties.food().nutrition(NUTRITION)
                    .saturation(SATURATION).canAlwaysEat(true).build());
        }
        return item;
    }

    /**
     * The Trick-or-Treat Bag: an orange bundle (not a head, so Bedrock draws it; not placeable, so the tag
     * isn't lost; not edible). It takes only candy (the prize-bundle insert guard).
     */
    public static ItemStack bag(int year, int goal) {
        ItemStack item = new ItemStack(Material.ORANGE_BUNDLE);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(Text.of(bagName(year)));
        meta.lore(lines(bagLore(year, goal)));
        TokenPrizes.tag(meta, bagId(year));
        meta.setEnchantmentGlintOverride(true);
        item.setItemMeta(meta);
        return item;
    }

    /** Halloween candy, by {@code hcm:candy_item}; reads the data view, so no meta is copied. */
    public static boolean isCandy(ItemStack item) {
        return candyYear(item) != 0;
    }

    /** The year a candy is from, or 0 for anything that isn't candy. */
    public static int candyYear(ItemStack item) {
        if (item == null || Keys.CANDY_ITEM == null || item.getType().isAir()) {
            return 0;
        }
        try {
            Integer year = item.getPersistentDataContainer().get(Keys.CANDY_ITEM, PersistentDataType.INTEGER);
            return year == null ? 0 : year;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** A Golden Candy Apple: candy whose prize id is its year's golden id. */
    public static boolean isGolden(ItemStack item) {
        int year = candyYear(item);
        return year != 0 && goldenId(year).equals(prizeId(item));
    }

    /** A Trick-or-Treat Bag, of any year. */
    public static boolean isBag(ItemStack item) {
        String id = prizeId(item);
        return id != null && id.toLowerCase(Locale.ROOT).startsWith(BAG_PREFIX);
    }

    /** The prize id from the data view, or null. */
    private static String prizeId(ItemStack item) {
        if (item == null || Keys.TOKEN_PRIZE == null || item.getType().isAir()) {
            return null;
        }
        try {
            return item.getPersistentDataContainer().get(Keys.TOKEN_PRIZE, PersistentDataType.STRING);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static List<Component> lines(List<String> legacy) {
        List<Component> out = new ArrayList<>(legacy.size());
        for (String line : legacy) {
            out.add(Text.of(line));
        }
        return out;
    }
}
