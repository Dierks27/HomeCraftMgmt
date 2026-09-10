package com.dierks.homecraft.marketplace;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig;
import org.bukkit.Material;

import java.util.List;

/**
 * Sorts an item into a department — the same classification the Marketplace uses for
 * Pallet listings and the Store/Market use for their department tabs, so one item lands
 * in the same place everywhere. An admin override ({@code marketplace.category_overrides})
 * always wins; an unknown result falls back to the last configured department (Misc).
 *
 * <p>Classification is name-first on purpose. {@link #byName(String)} decides from the
 * material's NAME alone and is a pure function — no Bukkit registry, so it is unit-testable
 * and deterministic. Only when the name says nothing do the property flags
 * ({@code isEdible}/{@code isBlock}) get a vote, and those need a running server: on
 * Paper 26.2 every Material predicate resolves through the registry.
 */
public final class Categorizer {

    private final HomeCraftManagement plugin;

    public Categorizer(HomeCraftManagement plugin) {
        this.plugin = plugin;
    }

    public String department(Material m) {
        PluginConfig.Marketplace mp = plugin.config().marketplace();
        String override = mp.override(m);
        if (override != null && !override.isBlank()) {
            return canonical(override, mp.departments());
        }
        String byName = byName(m.name());
        if (byName != null) {
            return canonical(byName, mp.departments());
        }
        return canonical(byFlags(m), mp.departments());
    }

    /** The property-flag fallback for names no rule recognises. Needs a running server. */
    private String byFlags(Material m) {
        if (m.isEdible()) {
            return "Food";
        }
        if (m.isBlock()) {
            return "Blocks";
        }
        return "Misc";
    }

    /**
     * Classify from the material name alone, or null when the name does not decide.
     *
     * <p>Order matters and is deliberately specific-before-general: {@code DIAMOND_SWORD}
     * is Combat before {@code DIAMOND} is a material, and {@code REDSTONE} is Redstone
     * before it is a Material.
     */
    static String byName(String n) {
        // Armour and weapons share one department: the tab row holds "All" plus eight
        // departments and no more, so gear is one tab rather than two.
        if (isArmor(n) || isWeapon(n)) {
            return "Combat";
        }
        if (isTool(n)) {
            return "Tools";
        }
        if (isRedstone(n)) {
            return "Redstone";
        }
        if (isCollectible(n)) {
            return "Collectibles";
        }
        if (isFood(n)) {
            return "Food";
        }
        if (isMaterial(n)) {
            return "Materials";
        }
        if (isBlockName(n)) {
            return "Blocks";
        }
        return null;
    }

    private static boolean isArmor(String n) {
        return n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE") || n.endsWith("_LEGGINGS")
                || n.endsWith("_BOOTS") || n.endsWith("_HORSE_ARMOR") || n.equals("SHIELD")
                || n.equals("ELYTRA") || n.equals("TURTLE_HELMET") || n.endsWith("_ARMOR_TRIM_SMITHING_TEMPLATE")
                || n.equals("WOLF_ARMOR");
    }

    private static boolean isWeapon(String n) {
        return n.endsWith("_SWORD") || n.endsWith("_AXE") || n.equals("BOW") || n.equals("CROSSBOW")
                || n.equals("TRIDENT") || n.equals("MACE") || n.equals("ARROW") || n.endsWith("_ARROW")
                || n.equals("TNT") || n.equals("FIREWORK_ROCKET");
    }

    private static boolean isTool(String n) {
        return n.endsWith("_PICKAXE") || n.endsWith("_SHOVEL") || n.endsWith("_HOE") || n.equals("SHEARS")
                || n.equals("FLINT_AND_STEEL") || n.equals("FISHING_ROD") || n.endsWith("_BUCKET")
                || n.equals("BUCKET") || n.equals("COMPASS") || n.equals("RECOVERY_COMPASS")
                || n.equals("CLOCK") || n.equals("SPYGLASS") || n.equals("BRUSH") || n.equals("LEAD")
                || n.equals("NAME_TAG") || n.equals("SADDLE") || n.equals("MAP") || n.equals("FILLED_MAP")
                || n.equals("WRITABLE_BOOK") || n.endsWith("_MINECART") || n.equals("MINECART")
                || n.endsWith("_BOAT") || n.endsWith("_RAFT") || n.equals("ENDER_EYE");
    }

    private static boolean isRedstone(String n) {
        return n.equals("REDSTONE") || n.equals("REDSTONE_TORCH") || n.equals("REDSTONE_BLOCK")
                || n.equals("REPEATER") || n.equals("COMPARATOR") || n.equals("OBSERVER")
                || n.equals("PISTON") || n.equals("STICKY_PISTON") || n.equals("HOPPER")
                || n.equals("DISPENSER") || n.equals("DROPPER") || n.equals("LEVER")
                || n.endsWith("_BUTTON") || n.endsWith("_PRESSURE_PLATE") || n.equals("TRIPWIRE_HOOK")
                || n.equals("DAYLIGHT_DETECTOR") || n.equals("TARGET") || n.equals("SLIME_BLOCK")
                || n.equals("HONEY_BLOCK") || n.equals("REDSTONE_LAMP") || n.equals("NOTE_BLOCK")
                || n.equals("POWERED_RAIL") || n.equals("DETECTOR_RAIL") || n.equals("ACTIVATOR_RAIL")
                || n.equals("RAIL") || n.equals("SCULK_SENSOR") || n.equals("CALIBRATED_SCULK_SENSOR")
                || n.equals("LIGHTNING_ROD") || n.equals("CRAFTER");
    }

    private static boolean isCollectible(String n) {
        return n.endsWith("_HEAD") || n.endsWith("_SKULL") || n.endsWith("_BANNER")
                || n.equals("PAINTING") || n.equals("ITEM_FRAME") || n.equals("GLOW_ITEM_FRAME")
                || n.startsWith("MUSIC_DISC") || n.equals("TOTEM_OF_UNDYING")
                || n.equals("ENCHANTED_BOOK") || n.equals("NETHER_STAR") || n.equals("DRAGON_EGG")
                || n.equals("HEART_OF_THE_SEA") || n.equals("ECHO_SHARD")
                || n.endsWith("_POTTERY_SHERD") || n.endsWith("_BANNER_PATTERN");
    }

    private static boolean isFood(String n) {
        return n.endsWith("_STEW") || n.equals("CAKE") || n.equals("MILK_BUCKET")
                || n.equals("HONEY_BOTTLE") || n.endsWith("_APPLE") || n.equals("BREAD")
                || n.startsWith("COOKED_") || n.equals("BEEF") || n.equals("PORKCHOP")
                || n.equals("CHICKEN") || n.equals("MUTTON") || n.equals("RABBIT")
                || n.equals("COD") || n.equals("SALMON") || n.equals("TROPICAL_FISH")
                || n.equals("PUFFERFISH") || n.equals("CARROT") || n.equals("POTATO")
                || n.equals("BAKED_POTATO") || n.equals("BEETROOT") || n.equals("MELON_SLICE")
                || n.equals("SWEET_BERRIES") || n.equals("GLOW_BERRIES") || n.equals("CHORUS_FRUIT")
                || n.equals("DRIED_KELP") || n.equals("COOKIE") || n.equals("PUMPKIN_PIE");
    }

    /**
     * Crafting stock: ingots, gems, dusts, rods and the drops players actually build with.
     *
     * <p>Mob drops live here rather than in Misc. Bone, string, gunpowder and leather are
     * things a player goes shopping FOR; Misc is where things go when nothing better fits,
     * and burying the crafting staples there is the flat-grid problem in miniature.
     */
    private static boolean isMaterial(String n) {
        return n.endsWith("_INGOT") || n.endsWith("_NUGGET") || n.startsWith("RAW_")
                || n.equals("DIAMOND") || n.equals("EMERALD") || n.equals("LAPIS_LAZULI")
                || n.equals("QUARTZ") || n.equals("AMETHYST_SHARD") || n.equals("NETHERITE_SCRAP")
                || n.equals("COAL") || n.equals("CHARCOAL") || n.equals("GLOWSTONE_DUST")
                || n.equals("BLAZE_ROD") || n.equals("BLAZE_POWDER") || n.equals("GUNPOWDER")
                || n.equals("STICK") || n.equals("FLINT") || n.equals("CLAY_BALL")
                || n.equals("BRICK") || n.equals("NETHER_BRICK") || n.equals("PAPER")
                || n.equals("BOOK") || n.equals("LEATHER") || n.equals("RABBIT_HIDE")
                || n.equals("STRING") || n.equals("FEATHER") || n.equals("BONE")
                || n.equals("BONE_MEAL") || n.equals("SLIME_BALL") || n.equals("ENDER_PEARL")
                || n.equals("GHAST_TEAR") || n.equals("MAGMA_CREAM") || n.equals("SPIDER_EYE")
                || n.equals("FERMENTED_SPIDER_EYE") || n.equals("ROTTEN_FLESH")
                || n.equals("PRISMARINE_SHARD") || n.equals("PRISMARINE_CRYSTALS")
                || n.equals("NAUTILUS_SHELL") || n.equals("PHANTOM_MEMBRANE")
                || n.equals("INK_SAC") || n.equals("GLOW_INK_SAC") || n.endsWith("_DYE")
                || n.equals("HONEYCOMB") || n.equals("TURTLE_SCUTE") || n.equals("SCUTE")
                || n.equals("SUGAR") || n.equals("EGG") || n.equals("WHEAT")
                || n.endsWith("_SEEDS") || n.equals("NETHER_WART") || n.equals("GLASS_BOTTLE")
                || n.equals("COPPER_INGOT") || n.equals("AMETHYST_CLUSTER")
                || n.equals("BREEZE_ROD") || n.equals("HEAVY_CORE") || n.equals("RESIN_CLUMP");
    }

    /**
     * Names that are unmistakably placeable. Explicit rules keep the common cases off the
     * registry-backed {@code isBlock()} fallback, which makes the result deterministic (and
     * testable) for the bulk of a large catalog.
     */
    private static boolean isBlockName(String n) {
        return n.endsWith("_LOG") || n.endsWith("_WOOD") || n.endsWith("_PLANKS")
                || n.endsWith("_SLAB") || n.endsWith("_STAIRS") || n.endsWith("_FENCE")
                || n.endsWith("_FENCE_GATE") || n.endsWith("_WALL") || n.endsWith("_DOOR")
                || n.endsWith("_TRAPDOOR") || n.endsWith("_LEAVES") || n.endsWith("_SAPLING")
                || n.endsWith("_ORE") || n.endsWith("_BLOCK") || n.endsWith("_BRICKS")
                || n.endsWith("_TERRACOTTA") || n.endsWith("_CONCRETE") || n.endsWith("_CONCRETE_POWDER")
                || n.endsWith("_WOOL") || n.endsWith("_CARPET") || n.endsWith("_GLASS")
                || n.endsWith("_GLASS_PANE") || n.endsWith("_SHULKER_BOX") || n.endsWith("_BED")
                || n.endsWith("_SIGN") || n.endsWith("_HANGING_SIGN") || n.endsWith("_ROOTS")
                || n.endsWith("_FUNGUS") || n.endsWith("_MUSHROOM") || n.endsWith("_CORAL")
                || n.endsWith("_CORAL_BLOCK") || n.endsWith("_CORAL_FAN") || n.endsWith("_FLOWER")
                || n.endsWith("_TULIP") || n.endsWith("_ORCHID") || n.endsWith("_BUSH")
                || n.equals("STONE") || n.equals("COBBLESTONE") || n.equals("DIRT")
                || n.equals("GRASS_BLOCK") || n.equals("SAND") || n.equals("GRAVEL")
                || n.equals("OBSIDIAN") || n.equals("NETHERRACK") || n.equals("END_STONE")
                || n.equals("GLASS") || n.equals("ICE") || n.equals("SNOW") || n.equals("SNOW_BLOCK")
                || n.equals("CLAY") || n.equals("BAMBOO") || n.equals("SUGAR_CANE")
                || n.equals("KELP") || n.equals("VINE") || n.equals("CACTUS")
                || n.equals("PUMPKIN") || n.equals("MELON") || n.equals("TORCH")
                || n.equals("LANTERN") || n.equals("CHEST") || n.equals("BARREL")
                || n.equals("FURNACE") || n.equals("CRAFTING_TABLE") || n.equals("SCAFFOLDING");
    }

    /** Map a guessed/override name to the exact configured department (case-insensitive). */
    private String canonical(String dept, List<String> departments) {
        for (String d : departments) {
            if (d.equalsIgnoreCase(dept)) {
                return d;
            }
        }
        return departments.isEmpty() ? "Misc" : departments.get(departments.size() - 1);
    }
}
