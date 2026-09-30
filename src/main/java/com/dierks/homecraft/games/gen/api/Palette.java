package com.dierks.homecraft.games.gen.api;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The only blocks a generated course may be built of (GEN-SPEC §4.0), and the names the planners
 * share for them.
 *
 * <p>Why an allowlist: a course is rebuilt from its plan every day and verified block for block,
 * so every block must stay exactly where it was put. That rules out anything that falls (sand,
 * gravel, concrete powder), flows (water, lava), ticks or decays (leaves, crops, plain ICE, which
 * melts), carries power (redstone), holds things (chests, hoppers) or is an entity. Soul SOIL, not
 * soul sand, whose top is 0.875 and would not match the golf model. The list is pinned by a test,
 * so adding a block is a decision, not an accident; air is never in a plan (empty space is air).
 *
 * <p>Plans carry blocks as block-data text ({@code minecraft:smooth_stone_slab[type=bottom]}), so
 * nothing here needs a server; the builder parses each entry once on the main thread.
 */
public final class Palette {

    // ---- the colour language (§7): the same everywhere ----------------------------------------

    /** Green: where a course starts. */
    public static final String START = "minecraft:lime_concrete";
    /** Light blue: a checkpoint ("you'll come back here"). */
    public static final String CHECKPOINT = "minecraft:light_blue_concrete";
    /** Gold: the finish (pads, the last ring). */
    public static final String FINISH = "minecraft:gold_block";
    /** Parkour path pads by tier. */
    public static final String PATH_EASY = "minecraft:white_concrete";
    public static final String PATH_MEDIUM = "minecraft:yellow_concrete";
    public static final String PATH_HARD = "minecraft:orange_concrete";
    /** Magenta arrows point the way ({@link #arrow}). */
    public static final String ARROW = "minecraft:magenta_glazed_terracotta";
    /** Signs: standing ({@link #sign}) or on a wall ({@link #wallSign}). */
    public static final String SIGN = "minecraft:oak_sign";
    public static final String WALL_SIGN = "minecraft:oak_wall_sign";

    // ---- Sky Rings --------------------------------------------------------------------------------

    /** The start tower's platform and diving board. */
    public static final String TOWER = "minecraft:white_concrete";
    /** The tower's pillar down to the half's floor ({@code [axis=y]}). */
    public static final String PILLAR = "minecraft:quartz_pillar";
    /** The rings, in rainbow order along the course, then round again; the last ring is {@link #FINISH}. */
    public static final List<String> RAINBOW = List.of("minecraft:red_concrete", "minecraft:orange_concrete",
            "minecraft:yellow_concrete", "minecraft:lime_concrete", "minecraft:light_blue_concrete",
            "minecraft:blue_concrete", "minecraft:purple_concrete", "minecraft:magenta_concrete");

    // ---- golf (§4.3: full blocks and bottom slabs only, so the model matches LiveBlocks) --------

    public static final String TURF_LIGHT = "minecraft:lime_concrete";
    public static final String TURF_DARK = "minecraft:green_concrete";
    public static final String GOLF_WALL = "minecraft:stripped_spruce_wood";
    /** In walls only, never in the floor. */
    public static final String BUMPER = "minecraft:slime_block";
    public static final String GOLF_ICE = "minecraft:packed_ice";
    public static final String BRAKE = "minecraft:soul_soil";
    public static final String RAMP = "minecraft:smooth_stone_slab[type=bottom]";
    /** The sunken cup's floor. */
    public static final String CUP = "minecraft:black_concrete";
    public static final String CUP_RING = "minecraft:white_concrete";
    public static final String TEE = "minecraft:white_concrete";
    /** Floats over the cup, above any ball or player. */
    public static final String FLAG = "minecraft:red_wool";

    // ---- the ice boat ---------------------------------------------------------------------------

    public static final String TRACK = "minecraft:packed_ice";
    public static final String TRACK_FAST = "minecraft:blue_ice";
    public static final String TRACK_WALL = "minecraft:stripped_spruce_wood";

    // ---- glass and lights (EVENTS-DROPPER-SPEC C1: the Dropper's shafts, Falling Floors' floors) ----

    /** Clear glass: Falling Floors' gallery rails. */
    public static final String GLASS = "minecraft:glass";
    /** A glowing guide light ("go here"): round a Dropper's path openings, and under an Easy pool. */
    public static final String SEA_LANTERN = "minecraft:sea_lantern";
    /**
     * The stained glass colours a plan may use: each Dropper level's walls in rainbow order (red,
     * orange, yellow, blue, purple), and Falling Floors' floors (yellow, pink, light blue) with its
     * "about to fall" red. Glass is see-through, lets the sky light in and changes no light when it
     * goes, and it never falls, flows, melts or ticks.
     */
    public static final List<String> GLASS_COLOURS = List.of("red", "orange", "yellow", "blue", "purple", "pink",
            "light_blue");
    /** Glass, the stained glass of {@link #GLASS_COLOURS} and the sea lantern: what C1 adds to {@link #ALLOWED}. */
    public static final Set<String> GLASS_AND_LIGHTS = Set.of(GLASS, "minecraft:red_stained_glass",
            "minecraft:orange_stained_glass", "minecraft:yellow_stained_glass", "minecraft:blue_stained_glass",
            "minecraft:purple_stained_glass", "minecraft:pink_stained_glass", "minecraft:light_blue_stained_glass",
            SEA_LANTERN);

    /**
     * Still water sources, as block-data text: the ONE fluid a plan may place, and only in a Dropper's
     * sealed pools (EVENTS-DROPPER-SPEC §B.1.9). A separate set, never in {@link #ALLOWED}: every
     * other generator's lint still refuses water, and only the dropper validator's sealed-pool rule
     * admits it.
     */
    public static final Set<String> POOL_WATER = Set.of("minecraft:water[level=0]");

    /** Every block id a plan may use, without block states. Pinned by a test. */
    public static final Set<String> ALLOWED = union(Set.of(
            "minecraft:lime_concrete", "minecraft:green_concrete", "minecraft:light_blue_concrete",
            "minecraft:white_concrete", "minecraft:yellow_concrete", "minecraft:orange_concrete",
            "minecraft:red_concrete", "minecraft:blue_concrete", "minecraft:purple_concrete",
            "minecraft:magenta_concrete", "minecraft:black_concrete", "minecraft:gold_block",
            "minecraft:magenta_glazed_terracotta", "minecraft:oak_sign", "minecraft:oak_wall_sign",
            "minecraft:quartz_pillar", "minecraft:stripped_spruce_wood", "minecraft:slime_block",
            "minecraft:packed_ice", "minecraft:blue_ice", "minecraft:soul_soil", "minecraft:smooth_stone_slab",
            "minecraft:red_wool"), GLASS_AND_LIGHTS);

    private Palette() {
    }

    /**
     * The block id of a block-data text, lower-case and namespaced, without its states:
     * {@code "Smooth_Stone_Slab[type=bottom]"} is {@code minecraft:smooth_stone_slab}.
     */
    public static String id(String blockData) {
        if (blockData == null) {
            return "";
        }
        String s = blockData.trim().toLowerCase(Locale.ROOT);
        int bracket = s.indexOf('[');
        if (bracket >= 0) {
            s = s.substring(0, bracket);
        }
        return s.indexOf(':') < 0 ? "minecraft:" + s : s;
    }

    /** Whether a plan may use this block. Water never: see {@link #poolWater}. */
    public static boolean allowed(String blockData) {
        return ALLOWED.contains(id(blockData));
    }

    /**
     * Whether a block-data text is exactly a still water source of {@link #POOL_WATER} (any case,
     * trimmed). Only the dropper validator asks this, for its sealed pools.
     */
    public static boolean poolWater(String blockData) {
        return blockData != null && POOL_WATER.contains(blockData.trim().toLowerCase(Locale.ROOT));
    }

    /** The stained glass of one of {@link #GLASS_COLOURS} ({@code minecraft:blue_stained_glass}). */
    public static String stainedGlass(String colour) {
        String c = colour == null ? "" : colour.trim().toLowerCase(Locale.ROOT);
        if (!GLASS_COLOURS.contains(c)) {
            throw new IllegalArgumentException("not a glass colour of the palette: " + colour);
        }
        return "minecraft:" + c + "_stained_glass";
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> out = new HashSet<>(a);
        out.addAll(b);
        return Set.copyOf(out);
    }

    /** The palette entries a plan may not use, in order; empty when every one is allowed. */
    public static List<String> problems(List<String> palette) {
        List<String> out = new ArrayList<>();
        if (palette != null) {
            for (String p : palette) {
                if (!allowed(p)) {
                    out.add(String.valueOf(p));
                }
            }
        }
        return out;
    }

    /** A magenta arrow pointing {@code facing} ({@code north}, {@code south}, {@code east}, {@code west}). */
    public static String arrow(String facing) {
        return ARROW + "[facing=" + facing + "]";
    }

    /** A standing sign turned {@code rotation} sixteenths (0 faces south). */
    public static String sign(int rotation) {
        return SIGN + "[rotation=" + Math.floorMod(rotation, 16) + "]";
    }

    /** A sign on the wall behind it, facing {@code facing}. */
    public static String wallSign(String facing) {
        return WALL_SIGN + "[facing=" + facing + "]";
    }
}
