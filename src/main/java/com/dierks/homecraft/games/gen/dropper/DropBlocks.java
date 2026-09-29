package com.dierks.homecraft.games.gen.dropper;

import com.dierks.homecraft.games.gen.api.Palette;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The blocks a Dropper is built of (EVENTS-DROPPER-SPEC §B.1.3, §B.1.9) and what each does to a
 * falling player ({@link DropWorld}).
 *
 * <p><b>Colours mean what they always mean:</b> lime is the start (every ledge), light blue a
 * checkpoint (the floor round a pool), gold the finish (the floor round the last pool), glowing
 * sea lanterns "go here". So obstacles never use lime, light blue or gold. Each level is its own
 * colour, the rainbow across levels: red, orange, yellow, blue, purple glass walls with plates of
 * the same colour's concrete.
 *
 * <p><b>From C1.</b> Glass, stained glass and sea lanterns are in {@link Palette#ALLOWED}
 * ({@link Palette#GLASS_AND_LIGHTS}), and water has its own {@link Palette#POOL_WATER}; the two sets
 * here are those, under the names this package already used. Water is never in {@code ALLOWED}:
 * only the dropper validator's sealed-pool rule admits it.
 */
public final class DropBlocks {

    /** Each level's colour, in level order. */
    public static final List<String> COLOURS = List.of("red", "orange", "yellow", "blue", "purple");

    /** The ledge: the start colour. */
    public static final String LEDGE = Palette.START;
    /** The floor round a pool: a checkpoint... */
    public static final String RIM = Palette.CHECKPOINT;
    /** ...or, round the last pool, the finish. */
    public static final String RIM_LAST = Palette.FINISH;
    /** Guide lights round a path opening, and the floor under an Easy pool. */
    public static final String LIGHT = "minecraft:sea_lantern";
    /** A still water source: the only fluid a plan may place. */
    public static final String WATER = "minecraft:water[level=0]";

    /** The blocks C1 added to {@link Palette#ALLOWED} for the Dropper: {@link Palette#GLASS_AND_LIGHTS}. */
    public static final Set<String> PENDING_C1 = Palette.GLASS_AND_LIGHTS;
    /** Water sources only, and only in sealed pools: {@link Palette#POOL_WATER}. */
    public static final Set<String> POOL_WATER = Palette.POOL_WATER;

    private DropBlocks() {
    }

    /** Level {@code i}'s (0-based) wall glass. */
    public static String glass(int level) {
        return "minecraft:" + COLOURS.get(Math.floorMod(level, COLOURS.size())) + "_stained_glass";
    }

    /** Level {@code i}'s obstacle concrete. */
    public static String plate(int level) {
        return "minecraft:" + COLOURS.get(Math.floorMod(level, COLOURS.size())) + "_concrete";
    }

    /** Whether a block-data text is glass of any colour. */
    public static boolean isGlass(String blockData) {
        String id = Palette.id(blockData);
        return id.equals("minecraft:glass") || id.endsWith("_stained_glass");
    }

    /** Whether a block-data text is water (of any level: the lint refuses anything but a source). */
    public static boolean isWater(String blockData) {
        return Palette.id(blockData).equals("minecraft:water");
    }

    /**
     * What a block does to a falling player: glass is a {@link DropWorld#WALL}, lime concrete the
     * {@link DropWorld#LEDGE}, water {@link DropWorld#WATER}, anything else solid.
     */
    public static byte kind(String blockData) {
        if (isWater(blockData)) {
            return DropWorld.WATER;
        }
        if (isGlass(blockData)) {
            return DropWorld.WALL;
        }
        if (Palette.id(blockData).equals(LEDGE)) {
            return DropWorld.LEDGE;
        }
        return DropWorld.SOLID;
    }

    /**
     * Whether a Dropper may place this block (§B.1.6 rule 8): a full cube from {@link Palette#ALLOWED}
     * or {@link #PENDING_C1}, or exactly a water source. Signs are placed as signs, never as ops;
     * slabs, arrows and the other non-cubes of the palette aren't a Dropper's.
     */
    public static boolean allowed(String blockData) {
        if (isWater(blockData)) {
            return POOL_WATER.contains(blockData.trim().toLowerCase(Locale.ROOT));
        }
        String id = Palette.id(blockData);
        if (blockData.indexOf('[') >= 0) {
            return false; // every Dropper cube is plain: no states
        }
        if (PENDING_C1.contains(id)) {
            return true;
        }
        return Palette.ALLOWED.contains(id) && CUBES.contains(id);
    }

    /** The full cubes of {@link Palette#ALLOWED} a Dropper uses (no signs, slabs or glazed arrows). */
    static final Set<String> CUBES = Set.of("minecraft:lime_concrete", "minecraft:light_blue_concrete",
            "minecraft:red_concrete", "minecraft:orange_concrete", "minecraft:yellow_concrete",
            "minecraft:blue_concrete", "minecraft:purple_concrete", "minecraft:gold_block");
}
