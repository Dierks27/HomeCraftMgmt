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

/**
 * The Harvest Basket's treats (0.38.6): Cornbread, Pumpkin Pie, Baked Potato, and the rare Golden Pie, each with
 * its ids and name from the event's year.
 *
 * <p>Every treat carries {@code hcm:token_prize}, so crafting, furnaces, trades and Crate refuse it, and
 * {@code hcm:harvest_item} (the year), by which it is known: it gets the treat, is meant to despawn, and is
 * welcome at the Feast Pot. Each can always be eaten (on Java; Geyser sends no food component, so a full Bedrock
 * player eats one with a tap instead).
 *
 * <p><b>Nothing here can be placed or planted.</b> A placed block loses its tag, and a planted crop grows plain
 * ones, either of which could then be sold at Crate. So there is no pumpkin, carrot, potato or wheat, and vanilla
 * has no corn: Cornbread stands in for it. {@link #MATERIALS} is pinned by name in a test.
 *
 * <p>The year goes in the name, because Bedrock shows lore only on a long press.
 */
public final class HarvestItems {

    /**
     * The Golden Pie's item: a pumpkin pie with the glint on. If Bedrock doesn't draw the glint, change this one
     * constant to {@code GOLDEN_CARROT}; the tap rule follows it.
     */
    public static final Material GOLDEN_MATERIAL = Material.PUMPKIN_PIE;

    /** The three everyday treats, in the order they fall (the 3rd is golden until the first golden is found). */
    public enum Kind {
        CORNBREAD(Material.BREAD, "&eCornbread", "harvest_cornbread_", 5, 6.0f),
        PIE(Material.PUMPKIN_PIE, "&6Pumpkin Pie", "harvest_pie_", 8, 4.8f),
        POTATO(Material.BAKED_POTATO, "&6Baked Potato", "harvest_potato_", 5, 6.0f);

        private final Material material;
        private final String name;
        private final String prefix;
        private final int nutrition;
        private final float saturation;

        Kind(Material material, String name, String prefix, int nutrition, float saturation) {
            this.material = material;
            this.name = name;
            this.prefix = prefix;
            this.nutrition = nutrition;
            this.saturation = saturation;
        }

        /** The vanilla item it is made from. */
        public Material material() {
            return material;
        }

        /** Its prize id for the year: {@code harvest_cornbread_2026}. */
        public String id(int year) {
            return prefix + year;
        }

        /** Its name for the year: {@code &eCornbread 2026}. */
        public String name(int year) {
            return name + " " + year;
        }
    }

    /** The Golden Pie's id prefix, name and food (a pumpkin pie's). */
    static final String GOLDEN_PREFIX = "harvest_golden_pie_";
    static final String GOLDEN_NAME = "&6&lGolden Pie";
    static final int GOLDEN_NUTRITION = 8;
    static final float GOLDEN_SATURATION = 4.8f;

    /** Every material a treat can be: none of them can be placed or planted. */
    public static final List<Material> MATERIALS = List.of(Material.BREAD, Material.PUMPKIN_PIE,
            Material.BAKED_POTATO, GOLDEN_MATERIAL);

    private HarvestItems() {
    }

    /** The kind a shower's {@code index}-th treat is: cornbread, pie, potato, then round again. */
    public static Kind kindAt(int index) {
        Kind[] kinds = Kind.values();
        return kinds[Math.floorMod(index, kinds.length)];
    }

    /** {@code harvest_golden_pie_<year>}. */
    public static String goldenId(int year) {
        return GOLDEN_PREFIX + year;
    }

    /** The Golden Pie's name for the year: {@code &6&lGolden Pie 2026}. */
    public static String goldenName(int year) {
        return GOLDEN_NAME + " " + year;
    }

    /** A treat's lore: the year, how to use it, "Very rare!" for the Golden Pie, then "Arcade prize". */
    static List<String> lore(int year, boolean golden) {
        List<String> out = new ArrayList<>();
        out.add("&7Thanksgiving " + year);
        out.add("&7Eat it for a treat!");
        if (golden) {
            out.add("&eVery rare!");
        }
        out.add("&8Arcade prize");
        return out;
    }

    /** One treat of the year's, amount 1: the Golden Pie when {@code golden}, else the {@code index}-th kind. */
    public static ItemStack treat(int year, boolean golden, int index) {
        Kind kind = kindAt(index);
        ItemStack item = new ItemStack(golden ? GOLDEN_MATERIAL : kind.material());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(Text.of(golden ? goldenName(year) : kind.name(year)));
        meta.lore(lines(lore(year, golden)));
        TokenPrizes.tag(meta, golden ? goldenId(year) : kind.id(year));
        meta.getPersistentDataContainer().set(Keys.HARVEST_ITEM, PersistentDataType.INTEGER, year);
        if (golden) {
            meta.setEnchantmentGlintOverride(true);
        }
        item.setItemMeta(meta);
        // On last, after the meta, as the candy apple's is: edible when full, on Java.
        item.setData(DataComponentTypes.FOOD, FoodProperties.food()
                .nutrition(golden ? GOLDEN_NUTRITION : kind.nutrition)
                .saturation(golden ? GOLDEN_SATURATION : kind.saturation).canAlwaysEat(true).build());
        return item;
    }

    /** A Harvest Basket treat, by {@code hcm:harvest_item}; reads the data view, so no meta is copied. */
    public static boolean isHarvest(ItemStack item) {
        return year(item) != 0;
    }

    /** The year a treat is from, or 0 for anything that isn't one. */
    public static int year(ItemStack item) {
        if (item == null || Keys.HARVEST_ITEM == null || item.getType().isAir()) {
            return 0;
        }
        try {
            Integer year = item.getPersistentDataContainer().get(Keys.HARVEST_ITEM, PersistentDataType.INTEGER);
            return year == null ? 0 : year;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** The Golden Pie: a treat whose prize id is its year's golden id. */
    public static boolean isGolden(ItemStack item) {
        int year = year(item);
        if (year == 0 || Keys.TOKEN_PRIZE == null) {
            return false;
        }
        try {
            return goldenId(year).equals(item.getPersistentDataContainer().get(Keys.TOKEN_PRIZE,
                    PersistentDataType.STRING));
        } catch (RuntimeException e) {
            return false;
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
