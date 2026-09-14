package com.dierks.homecraft.courier;

import org.bukkit.Material;
import org.bukkit.Tag;

import java.util.Locale;
import java.util.Set;

/**
 * Tells a block somebody put there from a block the world grew.
 *
 * <p>This exists because claim checks are not enough. The waypoint already refuses land that
 * belongs to somebody else, but Towny only knows about <b>claimed</b> land — and the thing a
 * player is most likely to have out in the wild is precisely the base they never got round to
 * claiming. To every check the Courier had, an unclaimed build was empty ground.
 *
 * <p>So the test is not ownership, it is evidence. Planks, a crafting table, a bed, a pane of
 * glass: none of these fall out of terrain generation on their own, and finding one means this
 * field is already somebody's, claim or no claim.
 *
 * <p><b>It also fires near villages, shipwrecks, mineshafts and ruins</b>, which use the same
 * materials. That is deliberate and not a compromise — dropping a delivery house into the
 * middle of a village is its own kind of wrong, and a reroll costs nothing.
 *
 * <p>Suffix-driven rather than a hand-written list of every block, for the same reason
 * {@link Ground} is: a fixed list is one Minecraft release away from being wrong, and every
 * release adds another wood set.
 */
public final class Built {

    private Built() {
    }

    /**
     * True if this material means somebody built here.
     *
     * <p>Errs towards <i>false</i>. A missed build costs one delivery placed somewhere rude; a
     * false positive costs every delivery in a biome, silently, by rerolling away from terrain
     * that was fine. So anything that genuinely generates on its own is excluded by name first,
     * even when it shares a family with things that do not.
     */
    public static boolean isBuilt(Material type) {
        if (type == null || type.isAir()) {
            return false;
        }
        if (isBuiltName(type.name())) {
            return true;
        }
        if (NATURAL.contains(type.name().toUpperCase(Locale.ROOT))) {
            return false;
        }
        try {
            return Tag.BEDS.isTagged(type) || Tag.SIGNS.isTagged(type)
                    || Tag.BANNERS.isTagged(type) || Tag.RAILS.isTagged(type)
                    || Tag.ANVIL.isTagged(type) || Tag.CANDLES.isTagged(type);
        } catch (RuntimeException ignored) {
            // A server that will not answer a tag has already had its say through the names.
            return false;
        }
    }

    /**
     * The name half of the test, split out so it can be checked without a running server.
     *
     * <p>The order is the part worth guarding: <b>the natural list is consulted first and
     * wins.</b> Several entries on it end with a suffix below — terracotta being the one that
     * matters, because getting it backwards does not fail loudly, it just makes every delivery
     * into a badlands reroll away until the attempt budget runs out.
     */
    public static boolean isBuiltName(String materialName) {
        if (materialName == null || materialName.isBlank()) {
            return false;
        }
        String name = materialName.toUpperCase(Locale.ROOT);
        if (NATURAL.contains(name)) {
            return false;
        }
        if (NAMED.contains(name)) {
            return true;
        }
        for (String suffix : SUFFIXES) {
            if (name.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Blocks that DO occur on their own and must never be read as handiwork.
     *
     * <p>Each of these would otherwise be caught by a suffix below, and each would cost every
     * delivery in some biome. Terracotta is the one that matters most: it is the badlands.
     */
    private static final Set<String> NATURAL = Set.of(
            "TERRACOTTA", "WHITE_TERRACOTTA", "ORANGE_TERRACOTTA", "MAGENTA_TERRACOTTA",
            "LIGHT_BLUE_TERRACOTTA", "YELLOW_TERRACOTTA", "LIME_TERRACOTTA", "PINK_TERRACOTTA",
            "GRAY_TERRACOTTA", "LIGHT_GRAY_TERRACOTTA", "CYAN_TERRACOTTA", "PURPLE_TERRACOTTA",
            "BLUE_TERRACOTTA", "BROWN_TERRACOTTA", "GREEN_TERRACOTTA", "RED_TERRACOTTA",
            "BLACK_TERRACOTTA",
            // Grows on stone in lush caves and on the floor of the pale garden.
            "MOSS_CARPET", "PALE_MOSS_CARPET",
            // Cobblestone forms wherever lava meets water, which is not a building.
            "COBBLESTONE", "MOSSY_COBBLESTONE",
            // Icebergs and frozen oceans.
            "BLUE_ICE", "PACKED_ICE", "ICE",
            // Deserts, beaches and the underside of a lot of terrain.
            "SANDSTONE", "RED_SANDSTONE", "SMOOTH_SANDSTONE", "SMOOTH_RED_SANDSTONE");

    /** Families where every member is somebody's work. */
    private static final String[] SUFFIXES = {
            "_PLANKS", "_STAIRS", "_SLAB", "_DOOR", "_TRAPDOOR", "_FENCE", "_FENCE_GATE",
            "_WALL", "_BUTTON", "_PRESSURE_PLATE", "_WOOL", "_CARPET", "_CONCRETE",
            "_CONCRETE_POWDER", "_GLAZED_TERRACOTTA", "_SHULKER_BOX", "_BRICKS", "_GLASS",
            "_GLASS_PANE",
    };

    /** One-offs with no family to belong to. */
    private static final Set<String> NAMED = Set.of(
            "CRAFTING_TABLE", "FURNACE", "BLAST_FURNACE", "SMOKER", "BARREL", "LECTERN", "LOOM",
            "SMITHING_TABLE", "STONECUTTER", "CARTOGRAPHY_TABLE", "FLETCHING_TABLE",
            "GRINDSTONE", "BELL", "BOOKSHELF", "CHISELED_BOOKSHELF", "LADDER",
            "TORCH", "WALL_TORCH", "SOUL_TORCH", "SOUL_WALL_TORCH", "LANTERN", "SOUL_LANTERN",
            "GLASS", "GLASS_PANE", "TINTED_GLASS", "IRON_BARS", "IRON_DOOR", "IRON_TRAPDOOR",
            "CHAIN", "HOPPER", "DISPENSER", "DROPPER", "PISTON", "STICKY_PISTON", "OBSERVER",
            "NOTE_BLOCK", "JUKEBOX", "TNT", "REDSTONE_WIRE", "REDSTONE_TORCH",
            "REDSTONE_WALL_TORCH", "REDSTONE_BLOCK", "REPEATER", "COMPARATOR", "LEVER",
            "TRIPWIRE_HOOK", "TARGET", "DAYLIGHT_DETECTOR", "BEACON", "CONDUIT",
            "ENCHANTING_TABLE", "BREWING_STAND", "CAULDRON", "WATER_CAULDRON", "COMPOSTER",
            "FLOWER_POT", "HAY_BLOCK", "BRICKS", "QUARTZ_BLOCK", "CHISELED_QUARTZ_BLOCK",
            "STONE_BRICKS", "CHISELED_STONE_BRICKS", "CRACKED_STONE_BRICKS",
            "MOSSY_STONE_BRICKS", "SMOOTH_STONE", "POLISHED_ANDESITE", "POLISHED_DIORITE",
            "POLISHED_GRANITE", "LODESTONE", "RESPAWN_ANCHOR", "END_ROD", "SEA_LANTERN");
}
