package com.dierks.homecraft.courier;

import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.Locale;

/**
 * Finds the actual ground at a coordinate, underneath whatever is growing on it.
 *
 * <p>This exists because every heightmap Minecraft keeps answers a different question from the
 * one a builder is asking. {@code MOTION_BLOCKING} — what {@code getHighestBlockYAt} returns
 * with no arguments — is "the highest block that blocks motion or holds a fluid", so in an oak
 * forest it reports the <b>canopy</b>, and beside a lake it reports the <b>water surface</b>.
 * Measured that way a flat forest floor reads as a ten-block cliff, which is precisely how a
 * delivery came to be refused in a wood with no message to say so.
 *
 * <p>The tempting one-line fix does not work either. {@code OCEAN_FLOOR} is documented as "the
 * highest non-air, solid block", and leaves qualify — vanilla's predicate for it is
 * {@code blocksMotion()}, which leaves satisfy. {@code MOTION_BLOCKING_NO_LEAVES} drops the
 * leaves but still stops on a tree <i>trunk</i> and still stops on water. There is no heightmap
 * that means "the ground", so this walks down and finds it.
 *
 * <p>The walk is also the only place that can tell the difference between "a tree is in the way"
 * (fine — clear it and build) and "this is a lake" (not fine — deliver somewhere else). Those
 * two look identical from a heightmap and need opposite answers.
 */
public final class Ground {

    /** What a column turned out to be. */
    public enum Kind {
        /** Real terrain was found; {@code y} is the surface block's own level. */
        GROUND,
        /** Water or lava on the way down. Somewhere to swim, not somewhere to deliver. */
        LIQUID,
        /** Nothing solid within the scan depth — a deep cave mouth, or the void. */
        NONE,
    }

    /** The result of looking down one column. */
    public record Column(Kind kind, int y) {
        public boolean isGround() {
            return kind == Kind.GROUND;
        }
    }

    private static final Column NO_GROUND = new Column(Kind.NONE, 0);
    private static final Column LIQUID = new Column(Kind.LIQUID, 0);

    private Ground() {
    }

    /**
     * The terrain surface at these coordinates, looking through anything growing on it.
     *
     * <p>Main thread only, and only with the chunk loaded — every block read here would
     * otherwise pull the chunk in synchronously.
     *
     * @param maxDepth how far down to look before giving up, in blocks
     */
    public static Column solid(World world, int x, int z, int maxDepth) {
        if (world == null) {
            return NO_GROUND;
        }
        try {
            // Start below the leaves rather than above them — a free head start of however
            // tall the canopy is, and on open ground it costs nothing.
            int top = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            int floor = Math.max(world.getMinHeight(), top - maxDepth);
            for (int y = top; y >= floor; y--) {
                Block block = world.getBlockAt(x, y, z);
                // Liquid first, and it matters that it is first: the vanilla "replaceable" tag
                // that catches grass and ferns also contains water and lava, so testing
                // clutter first would quietly walk down through a lake and report its bed as
                // buildable ground.
                if (block.isLiquid()) {
                    return LIQUID;
                }
                Material type = block.getType();
                if (type.isAir() || isClutter(type)) {
                    continue;
                }
                if (type.isSolid()) {
                    return new Column(Kind.GROUND, y);
                }
                // Solid-but-not-really (a torch, a sign): keep going down.
            }
            return NO_GROUND;
        } catch (RuntimeException e) {
            return NO_GROUND;
        }
    }

    /**
     * True for things that sit <i>on</i> the ground rather than being it.
     *
     * <p>Tag-driven wherever vanilla ships a tag, because a hand-written material list is one
     * Minecraft release away from being wrong, and name-matched for the rest. Tree trunks count:
     * a trunk is the thing standing on the field, not the field.
     */
    public static boolean isClutter(Material type) {
        if (type == null || type.isAir()) {
            return true;
        }
        try {
            if (Tag.LEAVES.isTagged(type) || Tag.LOGS.isTagged(type)
                    || Tag.SAPLINGS.isTagged(type) || Tag.FLOWERS.isTagged(type)
                    || Tag.CROPS.isTagged(type) || Tag.WOOL_CARPETS.isTagged(type)
                    || Tag.CAVE_VINES.isTagged(type) || Tag.BAMBOO_BLOCKS.isTagged(type)) {
                return true;
            }
            // #minecraft:replaceable is the catch-all for grass, ferns, dead bushes, snow
            // layers, fire and the like. It also contains water and lava, which is why every
            // caller tests for liquid before it asks this.
            if (Tag.REPLACEABLE.isTagged(type)) {
                return true;
            }
        } catch (RuntimeException ignored) {
            // A server that will not answer a tag falls through to the name test below.
        }
        return NAMED_CLUTTER.contains(type.name().toUpperCase(Locale.ROOT)) || matchesName(type);
    }

    /** Things with no tag of their own that are still growing on the ground, not part of it. */
    private static final java.util.Set<String> NAMED_CLUTTER = java.util.Set.of(
            "VINE", "GLOW_LICHEN", "SCULK_VEIN", "HANGING_ROOTS", "LILY_PAD",
            "SWEET_BERRY_BUSH", "CACTUS", "SUGAR_CANE", "COCOA", "COBWEB",
            "BROWN_MUSHROOM", "RED_MUSHROOM", "MUSHROOM_STEM",
            "BROWN_MUSHROOM_BLOCK", "RED_MUSHROOM_BLOCK",
            "AZALEA", "FLOWERING_AZALEA", "BIG_DRIPLEAF", "SMALL_DRIPLEAF",
            "PINK_PETALS", "MOSS_CARPET", "SNOW", "POWDER_SNOW", "BEE_NEST", "BEEHIVE",
            "CHORUS_PLANT", "CHORUS_FLOWER", "PUMPKIN", "MELON", "SCAFFOLDING");

    /** A last-resort suffix match, for blocks a future version adds to families we know. */
    private static boolean matchesName(Material type) {
        String n = type.name().toUpperCase(Locale.ROOT);
        return n.endsWith("_LEAVES") || n.endsWith("_LOG") || n.endsWith("_WOOD")
                || n.endsWith("_SAPLING") || n.endsWith("_PROPAGULE")
                || n.endsWith("_ROOTS") || n.endsWith("_SPROUTS") || n.endsWith("_FUNGUS")
                || n.endsWith("_CARPET") || n.endsWith("_TORCH") || n.endsWith("_BUSH");
    }
}
