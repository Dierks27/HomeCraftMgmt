package com.dierks.homecraft.games.gen.api;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The only blocks a generated course may be built of (GEN-SPEC §4.0), and the names the planners
 * share for them.
 *
 * <p>Why an allowlist: a course is rebuilt from its plan every day and verified block for block,
 * so every block must stay exactly where it was put. That rules out anything that falls (sand,
 * gravel, concrete powder), flows (water, lava), ticks or decays (crops, grass, plain ICE, which
 * melts), carries power (redstone), holds things (chests, hoppers) or is an entity. Soul SOIL, not
 * soul sand, whose top is 0.875 and would not match the golf model. The list is pinned by a test,
 * so adding a block is a decision, not an accident; air is never in a plan (empty space is air).
 *
 * <p><b>Course Variety (§1.1).</b> Smooth sandstone (the "sand" that never falls), its bottom slab,
 * moss, and oak, birch and cherry logs and leaves join the list. Leaves would decay and logs could
 * lie on their side, so for those the block's states matter too: {@link #stateProblems} wants every
 * leaf persistent, dry and at the exact {@code distance} vanilla gives it (so a neighbour update
 * never changes it and the daily verify pass never churns), every log upright and every slab a
 * bottom slab. The planners write them through {@link #log} and {@link #leaves}.
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

    // ---- Course Variety (§1.1): sand, moss and trees ----------------------------------------------

    /**
     * The sand that never falls: a full block of smooth sandstone. A boat's sand (a drive floor,
     * slow), a golf flush bunker (SLOW to the ball on a generated lane of algo 3 or later).
     */
    public static final String SAND = "minecraft:smooth_sandstone";
    /** A golf sunken bunker: its top is half a block below the turf. Bottom slabs only. */
    public static final String SAND_SLAB = "minecraft:smooth_sandstone_slab[type=bottom]";
    /** Grass that never spreads or ticks: terraces, the summit cone, island tops, planters. */
    public static final String MOSS = "minecraft:moss_block";
    /** The woods a tree may be, in the order the planners pick them. */
    public static final List<String> WOODS = List.of("oak", "birch", "cherry");
    /** The farthest a leaf may be from a log ({@code distance=7} is vanilla's cap). */
    public static final int MAX_LEAF_DISTANCE = 7;

    // ---- existing blocks, their Course Variety jobs (§1.1.3) ---------------------------------------

    /** Yellow drop-ahead caps in both walls at a boat track's lip. */
    public static final String LIP_CAP = "minecraft:yellow_concrete";
    /** The Ice Cave's see-through roof, and the golf "glass waterfall" under a terrace's edge. */
    public static final String BLUE_GLASS = "minecraft:light_blue_stained_glass";

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
     * Still water sources, as block-data text: the ONE fluid a plan may place, and only in sealed
     * pools: a Dropper's (EVENTS-DROPPER-SPEC §B.1.9) and golf's ponds (Course Variety §1.2, the
     * slots whose {@code Slots.Def.mayHoldWater()}). A separate set, never in {@link #ALLOWED}: every
     * other generator's lint still refuses water (the ice boat stays dry), and only a sealed-pool
     * rule ({@link Pools}, the Dropper's own) admits it.
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
            "minecraft:red_wool",
            // Course Variety (§1.1): the sand that never falls, moss, and trees (states: stateProblems)
            "minecraft:smooth_sandstone", "minecraft:smooth_sandstone_slab", "minecraft:moss_block",
            "minecraft:oak_log", "minecraft:birch_log", "minecraft:cherry_log", "minecraft:oak_leaves",
            "minecraft:birch_leaves", "minecraft:cherry_leaves"), GLASS_AND_LIGHTS);

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
     * trimmed). Only the sealed-pool rules ask this (the Dropper's validator, {@link Pools}).
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

    /**
     * What is wrong with the STATES of a plan's palette entries (§1.1.1), in order; empty when
     * nothing is. {@link #problems} says which blocks a plan may use; this says how some of them
     * must stand, so they never change on their own:
     * <ul>
     *   <li>every {@code *_leaves} says {@code persistent=true} (it never decays) and
     *       {@code waterlogged=false}, with a {@code distance} of 1 to {@value #MAX_LEAF_DISTANCE}
     *       (the validators check it is the one vanilla would give it);</li>
     *   <li>every {@code *_slab} says {@code type=bottom} (the golf model's half block);</li>
     *   <li>every {@code *_log} says {@code axis=y} (a trunk);</li>
     *   <li>nothing says {@code waterlogged=true}, and no state is given twice or can't be read.</li>
     * </ul>
     * Pure; entries that aren't on the list at all are {@link #problems}' to report.
     */
    public static List<String> stateProblems(List<String> palette) {
        List<String> out = new ArrayList<>();
        if (palette == null) {
            return out;
        }
        for (String p : palette) {
            if (p == null) {
                continue;
            }
            String why = stateProblem(p);
            if (why != null) {
                out.add("'" + p + "' " + why);
            }
        }
        return out;
    }

    /** Why one entry's states break {@link #stateProblems}' rules, or {@code null}. */
    private static String stateProblem(String blockData) {
        Map<String, String> states = states(blockData);
        if (states == null) {
            return "has states that can't be read";
        }
        if ("true".equals(states.get("waterlogged"))) {
            return "holds water (waterlogged=true)";
        }
        String id = id(blockData);
        if (id.endsWith("_leaves")) {
            if (!"true".equals(states.get("persistent"))) {
                return "would decay: leaves must say persistent=true";
            }
            if (!"false".equals(states.get("waterlogged"))) {
                return "must say waterlogged=false";
            }
            int d = leafDistance(states.get("distance"));
            if (d < 1 || d > MAX_LEAF_DISTANCE) {
                return "needs a distance of 1 to " + MAX_LEAF_DISTANCE;
            }
            if (states.size() != 3) {
                return "has states leaves don't have";
            }
        } else if (id.endsWith("_slab")) {
            if (!"bottom".equals(states.get("type"))) {
                return "isn't a bottom slab (type=bottom)";
            }
        } else if (id.endsWith("_log")) {
            if (!"y".equals(states.get("axis"))) {
                return "isn't upright (axis=y)";
            }
        }
        return null;
    }

    private static int leafDistance(String text) {
        if (text == null || text.length() != 1 || !Character.isDigit(text.charAt(0))) {
            return -1;
        }
        return text.charAt(0) - '0';
    }

    /**
     * The states of a block-data text, lower-case ({@code [distance=2,persistent=true]} is
     * {distance=2, persistent=true}); empty with none, {@code null} when they can't be read (a
     * missing bracket, a state without a value, one given twice).
     */
    public static Map<String, String> states(String blockData) {
        Map<String, String> out = new TreeMap<>();
        if (blockData == null) {
            return out;
        }
        String s = blockData.trim().toLowerCase(Locale.ROOT);
        int open = s.indexOf('[');
        if (open < 0) {
            return s.indexOf(']') < 0 ? out : null;
        }
        if (!s.endsWith("]") || s.indexOf('[', open + 1) >= 0) {
            return null;
        }
        String body = s.substring(open + 1, s.length() - 1).trim();
        if (body.isEmpty()) {
            return out;
        }
        for (String part : body.split(",", -1)) {
            int eq = part.indexOf('=');
            if (eq <= 0 || eq == part.length() - 1) {
                return null;
            }
            String k = part.substring(0, eq).trim();
            String v = part.substring(eq + 1).trim();
            if (k.isEmpty() || v.isEmpty() || out.put(k, v) != null) {
                return null;
            }
        }
        return out;
    }

    /**
     * An upright log of {@code wood} (one of {@link #WOODS}): {@code minecraft:oak_log[axis=y]}.
     *
     * @throws IllegalArgumentException for a wood the palette doesn't have
     */
    public static String log(String wood) {
        return "minecraft:" + wood(wood) + "_log[axis=y]";
    }

    /**
     * A persistent, dry leaf of {@code wood} at {@code distance} from its nearest log, as the game
     * itself spells it (states in alphabetical order), so the builder's parse gives back the same
     * text: {@code minecraft:oak_leaves[distance=2,persistent=true,waterlogged=false]}.
     *
     * @throws IllegalArgumentException for a wood the palette doesn't have, or a distance outside 1-7
     */
    public static String leaves(String wood, int distance) {
        if (distance < 1 || distance > MAX_LEAF_DISTANCE) {
            throw new IllegalArgumentException("a leaf's distance is 1 to " + MAX_LEAF_DISTANCE + ": " + distance);
        }
        return "minecraft:" + wood(wood) + "_leaves[distance=" + distance + ",persistent=true,waterlogged=false]";
    }

    private static String wood(String wood) {
        String w = wood == null ? "" : wood.trim().toLowerCase(Locale.ROOT);
        if (!WOODS.contains(w)) {
            throw new IllegalArgumentException("not a wood of the palette: " + wood);
        }
        return w;
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
