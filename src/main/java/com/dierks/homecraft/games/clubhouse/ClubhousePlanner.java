package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.arcade.feast.FeastRules;
import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Plan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Makes the Clubhouse (CLUBHOUSE-SPEC §1): one room in the sky, 32 x 16 x 32, where racers wait before a race and
 * hang out after it.
 *
 * <p><b>A golf club (0.40.0, plan 4; the owner: "more clubhouse-like, like a golf club").</b> Dark-oak panelling to
 * the chair rail, cream plaster (calcite) above, tall paned windows, a dark-oak crown and a coffered ceiling of
 * dark-oak beams under the glass roof. Two rows of tall columns (x 8 and x 23) split it into rooms without hiding the
 * boards, which hang 7 to 12 blocks up on the four walls, each on a framed dark panel like a club's leaderboard:
 * <ul>
 *   <li><b>The Great Hall</b> (the middle, north): the podium on a green under the results board (2 x 2 plinths,
 *   gold, iron and copper, so 1st stands on the room's middle line); a red runner up the aisle; two 3 x 5 banquet
 *   tables, white cloth, chairs with gaps between them, ten plates on each (the Feast's plates on the outer rows,
 *   candles down the middle); a 3 x 3 table south of each, centred on the same line, the Feast Pot (a cauldron)
 *   in the middle of the west one, flowers on the east one; two chandeliers over the tables, green banners on the
 *   columns. The room to dress for a holiday.</li>
 *   <li><b>The 19th Hole</b> (the west wing): a bar with a back bar and stools, a reading nook with the jukebox
 *   in the middle of a wall of books, and armchairs by a brick fireplace.</li>
 *   <li><b>The Trophy Gallery</b> (the east wing): the trophy case on a dark-oak cabinet under the All-time records
 *   board, armchairs to look at it, a putting green with its flag in the north and a card table in the south.</li>
 *   <li><b>The Foyer</b> (the middle, south): sixteen arrival spots on a black and white marble floor under the Now
 *   playing board, each facing the podium.</li>
 * </ul>
 * The room is mirror-symmetric about its middle line (x 16.0, between blocks 15 and 16) where it can be: the hall,
 * the foyer, the windows, the columns and the beams; every odd-sized thing is centred on a line of its own.
 *
 * <p><b>Why it stays dark-mob free.</b> Plan 3 was glass because nothing a mob could spawn on was ever open to
 * the air. A mob only spawns at block light 0 (26.x), so plan 4 is lit instead: every block a mob could stand on
 * has block light over it, at night too ({@code ClubhousePlannerTest} floods the plan's light). Hidden sea
 * lanterns lie under the rugs and the runner; sconces on the columns and the walls, chandeliers, pendants, lamps,
 * candles and the fire do the rest. No way out by walking or jumping: the walls go up to a closed roof.
 *
 * <p><b>Cushions</b> (26.3): every chair, stool and armchair is a {@link #seats} spot; on a server with cushions the
 * room puts one on each ({@link ClubCushions}). On 26.2 the chairs are bare.
 *
 * <p>Pure and deterministic: the same box always gives the same plan (its {@link Plan#hash} is pinned by a test).
 * Every block's state is written whole (fences and panes join what they touch), so a verify never sees one change.
 */
public final class ClubhousePlanner {

    /**
     * The planner's version: part of the plan, so a change of the room is a change of plan. 2 (0.38.4): the
     * jukebox corner. 3 (0.38.6): the two feast tables. 4 (0.40.0): the golf club.
     */
    public static final int ALGO = 4;
    /** The plan's "slot" name. */
    public static final String SLOT = "clubhouse";

    /** The walls stand from the floor up to here (above the floor's own layer); the roof is on top. */
    public static final int WALL_TOP = 14;
    public static final int ROOF = 15;
    /** The pedestals: heights (1st, 2nd, 3rd), their west column (each is 2 x 2), and their north row. */
    public static final int[] PODIUM_HEIGHT = {3, 2, 1};
    public static final int[] PODIUM_X = {15, 12, 18};
    public static final int PODIUM_Z = 2;
    /** The arrival spots: a 4 x 4 grid on the foyer's marble, mirror-symmetric, three or more apart. */
    static final int[] SPAWN_X = {10, 14, 17, 21};
    static final int[] SPAWN_Z = {20, 23, 26, 29};
    /** The results panel on the north wall's inside (x offsets, y offsets): the board hangs on it. */
    static final int PANEL_X0 = 10;
    static final int PANEL_X1 = 21;
    static final int PANEL_Y0 = 7;
    static final int PANEL_Y1 = 11;
    /** The other walls' board panels: along 9..22 (the boards are 14.1 wide, centred on the wall). */
    static final int SIDE_PANEL_0 = 9;
    static final int SIDE_PANEL_1 = 22;
    /** The jukebox (0.38.4): in the 19th Hole's reading nook, in the middle of its wall of books. */
    static final int JUKEBOX_X = 1;
    static final int JUKEBOX_Y = 1;
    static final int JUKEBOX_Z = 16;
    /**
     * The trophy case (0.38.4; the east wall since 0.40.0, under the All-time records board): item frames on the
     * east wall's inner face (x = minX + {@value #TROPHY_X}), over the cabinet, from z = minZ + {@value #TROPHY_Z0}
     * every {@value #TROPHY_STEP}, at most {@link ClubBoard#MAX_ROWS}. They face west.
     */
    static final int TROPHY_X = 30;
    static final int TROPHY_Y = 2;
    static final int TROPHY_Z0 = 9;
    static final int TROPHY_STEP = 2;
    static final int TROPHY_MAX = ClubBoard.MAX_ROWS;
    /**
     * The banquet tables (0.40.0): two 3 x 5 tables on the floor's layer + 1, x 11-13 (west) and 18-20 (east),
     * z 7-11, centred on x 12 and x 19 (mirrors); a plate over each block of the outer rows (x 11 and 13, 18 and
     * 20), {@value FeastRules#CELLS} in all; candles on the middle rows.
     */
    static final int FEAST_WEST_X0 = 11;
    static final int FEAST_EAST_X0 = 18;
    static final int FEAST_Z0 = 7;
    static final int FEAST_LEN = 5;
    static final int FEAST_PLATE_Y = 2;
    /** The 3 x 3 tables south of the banquet tables, on the same middle lines: the Feast Pot on the west one. */
    static final int ROUND_Z0 = 14;
    /** The Feast Pot's block: the middle of the west 3 x 3 table. */
    static final int POT_X = 12;
    static final int POT_Z = 15;
    /** The columns: two rows, the hall's sides, every five blocks. */
    static final int[] COLUMN_X = {8, 23};
    static final int[] COLUMN_Z = {5, 10, 15, 20, 25};
    /** The ceiling's beams (on the walls' top layer): along z at these x, along x at these z. */
    static final int[] BEAM_X = {4, 8, 12, 19, 23, 27};
    static final int[] BEAM_Z = {5, 10, 15, 20, 25};
    /** Windows: two pairs near each corner of every wall (the boards' panels take the middle), tall. */
    static final int[] WINDOW_ALONG = {2, 3, 5, 6, 25, 26, 28, 29};
    static final int WINDOW_Y0 = 5;
    static final int WINDOW_Y1 = 11;
    /** The fireplace's chimney breast on the west wall, z 24-28 (south of the My stats board): no windows behind. */
    static final int CHIMNEY_Z0 = 24;
    static final int CHIMNEY_Z1 = 28;

    // ---- the blocks --------------------------------------------------------------------------------

    public static final String AIR = "minecraft:air";
    public static final String PLANKS = "minecraft:dark_oak_planks";
    public static final String HALL_FLOOR = "minecraft:spruce_planks";
    public static final String MARBLE_WHITE = "minecraft:smooth_quartz";
    public static final String MARBLE_BLACK = "minecraft:polished_deepslate";
    public static final String PLASTER = "minecraft:calcite";
    public static final String PANEL = "minecraft:polished_deepslate";
    public static final String WINDOW = "minecraft:glass_pane";
    public static final String ROOF_GLASS = "minecraft:glass";
    public static final String LIGHT = "minecraft:sea_lantern";
    public static final String HANGING = "minecraft:lantern[hanging=true,waterlogged=false]";
    public static final String STANDING = "minecraft:lantern[hanging=false,waterlogged=false]";
    public static final String CHAIN = "minecraft:iron_chain[axis=y,waterlogged=false]";
    public static final String FENCE = "minecraft:dark_oak_fence";
    public static final String GOLD = "minecraft:gold_block";
    public static final String IRON = "minecraft:iron_block";
    public static final String COPPER = "minecraft:waxed_copper_block";
    public static final String CLOTH = "minecraft:white_wool";
    public static final String CABINET = "minecraft:stripped_dark_oak_wood";
    public static final String BRICKS = "minecraft:bricks";
    public static final String BOOKS = "minecraft:bookshelf";
    public static final String JUKEBOX = "minecraft:jukebox[has_record=false]";
    public static final String POT = "minecraft:water_cauldron[level=3]";
    public static final String FLOWERS = "minecraft:potted_flowering_azalea_bush";
    public static final String GREEN = "minecraft:moss_carpet";
    public static final String STAGE_RUG = "minecraft:green_carpet";
    public static final String RUNNER = "minecraft:red_carpet";
    public static final String RUG = "minecraft:brown_carpet";
    public static final String FELT = "minecraft:green_carpet";
    public static final String CUP = "minecraft:cauldron";
    public static final String PIN = "minecraft:birch_fence";
    public static final String FLAG = "minecraft:red_banner[rotation=4]";
    public static final String PLANTER = "minecraft:moss_block";
    public static final String SHRUB = "minecraft:flowering_azalea";
    public static final String CANDLES = "minecraft:white_candle[candles=3,lit=true,waterlogged=false]";
    public static final String SCREEN = "minecraft:iron_bars[east=false,north=true,south=true,waterlogged=false,west=false]";
    public static final String FIRE = "minecraft:campfire[facing=east,lit=true,signal_fire=false,waterlogged=false]";

    /** A log (or stripped log) along an axis. */
    static String log(String axis) {
        return "minecraft:stripped_dark_oak_log[axis=" + axis + "]";
    }

    /** A chair: dark-oak stairs, bottom half, their back ("facing") away from what the sitter looks at. */
    static String chair(String facing) {
        return "minecraft:dark_oak_stairs[facing=" + facing + ",half=bottom,shape=straight,waterlogged=false]";
    }

    /** A stool or a coffee table: a bottom dark-oak slab. */
    static final String STOOL = "minecraft:dark_oak_slab[type=bottom,waterlogged=false]";
    /** A ledge: a top dark-oak slab. */
    static final String LEDGE = "minecraft:dark_oak_slab[type=top,waterlogged=false]";

    /** An armchair's arm: an open dark-oak trapdoor, its board against the chair ({@code facing} away from it). */
    static String arm(String facing) {
        return "minecraft:dark_oak_trapdoor[facing=" + facing + ",half=bottom,open=true,powered=false,waterlogged=false]";
    }

    /** A wall banner (green, the club's colour) on a block, facing {@code facing}. */
    static String banner(String facing) {
        return "minecraft:green_wall_banner[facing=" + facing + "]";
    }

    /** A back-bar barrel or shelf, facing into the room. */
    static String barrel(String facing) {
        return "minecraft:barrel[facing=" + facing + ",open=false]";
    }

    static String shelf(String facing) {
        return "minecraft:dark_oak_shelf[facing=" + facing + ",powered=false,side_chain=unconnected,waterlogged=false]";
    }

    /**
     * The blocks that give light (26.x levels): what {@code ClubhousePlannerTest} floods the room's light from.
     * Candles give 3 a candle.
     */
    public static int lightOf(String blockData) {
        if (blockData == null) {
            return 0;
        }
        String id = id(blockData);
        return switch (id) {
            case "minecraft:sea_lantern", "minecraft:lantern", "minecraft:glowstone" -> 15;
            case "minecraft:campfire" -> blockData.contains("lit=true") ? 15 : 0;
            default -> {
                if (id.endsWith("candle") && blockData.contains("lit=true")) {
                    int at = blockData.indexOf("candles=");
                    yield at < 0 ? 3 : 3 * (blockData.charAt(at + 8) - '0');
                }
                yield 0;
            }
        };
    }

    /**
     * Whether light passes through a block (for the flood: air, glass, panes, chains, lanterns, fences, carpets,
     * banners, candles, pots and plants). Everything else stops it: slabs, stairs and trapdoors too, though light
     * can slip past some of them in the game, so the flood never counts more light than there is.
     */
    public static boolean letsLight(String blockData) {
        String id = id(blockData);
        return id.equals(AIR) || id.equals("minecraft:glass") || id.endsWith("_pane") || id.endsWith("chain")
                || id.equals("minecraft:lantern") || id.endsWith("_fence") || id.endsWith("_carpet")
                || id.endsWith("_banner") || id.endsWith("candle") || id.startsWith("minecraft:potted_")
                || id.equals(SHRUB) || id.equals("minecraft:iron_bars");
    }

    /**
     * The blocks a mob can never stand on: glass and panes, lanterns, chains, candles, carpets, banners, plants and
     * pots, fences (too narrow). A mob could stand on anything else if it were dark there.
     */
    public static boolean noFooting(String blockData) {
        String id = id(blockData);
        return id.equals("minecraft:glass") || id.endsWith("_pane") || id.equals("minecraft:lantern")
                || id.endsWith("chain") || id.endsWith("candle") || id.endsWith("_carpet") || id.endsWith("_banner")
                || id.startsWith("minecraft:potted_") || id.equals(SHRUB) || id.endsWith("_fence")
                || id.equals("minecraft:iron_bars");
    }

    static String id(String blockData) {
        return blockData.contains("[") ? blockData.substring(0, blockData.indexOf('[')) : blockData;
    }

    /**
     * A cushion's place: on a chair, a stool or an armchair, the way its sitter faces (Minecraft yaw), and its colour
     * (a dye name: "GREEN").
     */
    public record Seat(double x, double y, double z, float yaw, String color) {
    }

    private ClubhousePlanner() {
    }

    /**
     * The Clubhouse in {@code box}.
     *
     * @param box the Clubhouse box, 32 x 16 x 32
     * @throws IllegalArgumentException when the box isn't the Clubhouse's size
     */
    public static ClubhouseSite plan(Box box) {
        if (box == null || box.sizeX() != ClubhouseSettings.SIZE_X || box.sizeY() != ClubhouseSettings.SIZE_Y
                || box.sizeZ() != ClubhouseSettings.SIZE_Z) {
            throw new IllegalArgumentException("the Clubhouse box is " + ClubhouseSettings.SIZE_X + " x "
                    + ClubhouseSettings.SIZE_Y + " x " + ClubhouseSettings.SIZE_Z + ", not "
                    + (box == null ? "missing" : box.sizeX() + " x " + box.sizeY() + " x " + box.sizeZ()));
        }
        Builder b = new Builder(box);
        shell(b);
        ceiling(b);
        floor(b);
        columns(b);
        List<ClubhouseSite.Spot> podium = hall(b);
        bar(b);
        gallery(b);
        foyer(b);
        b.join();

        List<ClubhouseSite.Spot> spawns = new ArrayList<>();
        for (int k : SPAWN_Z) {
            for (int i : SPAWN_X) {
                spawns.add(b.spot(i, 1, k, 180f));
            }
        }
        ClubhouseSite.Spot board = new ClubhouseSite.Spot(box.minX() + (PANEL_X0 + PANEL_X1 + 1) / 2.0,
                box.minY() + (PANEL_Y0 + PANEL_Y1 + 1) / 2.0 - 0.5, box.minZ() + 1.1, 0f);
        Box inside = new Box(box.minX() + 1, box.minY() + 1, box.minZ() + 1, box.maxX() - 1, box.minY() + WALL_TOP,
                box.maxZ() - 1);
        List<String> summary = List.of("the Clubhouse: " + box.describe(), spawns.size() + " arrival spots in the "
                + "foyer, the Great Hall with a podium of 3 and two banquet tables (feast tables, the Feast Pot), "
                + "the 19th Hole with its bar and a jukebox, the Trophy Gallery with a putting green",
                "plan " + ALGO);
        Plan plan = Plan.of(SLOT, ALGO, 0L, box, b.palette, b.ops, List.of(), List.of(), null, summary, 0);
        return new ClubhouseSite(plan, spawns, podium, board, inside);
    }

    // ---- the shell -------------------------------------------------------------------------------

    /** The walls: panelling to the chair rail, plaster, windows, the board panels, the crown; the glass roof. */
    private static void shell(Builder b) {
        int last = ClubhouseSettings.SIZE_X - 1;
        for (int i = 0; i <= last; i++) {
            for (int k = 0; k <= last; k++) {
                boolean edgeX = i == 0 || i == last;
                boolean edgeZ = k == 0 || k == last;
                if (!edgeX && !edgeZ) {
                    continue;
                }
                boolean corner = edgeX && edgeZ;
                int along = edgeZ ? i : k;
                String axis = edgeZ ? "x" : "z";
                for (int j = 0; j <= WALL_TOP; j++) {
                    String block;
                    if (corner) {
                        block = j == 0 || j == WALL_TOP ? PLANKS : log("y");
                    } else if (j <= 3 || j == WALL_TOP) {
                        block = PLANKS;
                    } else if (j == 4 || j == 13) {
                        block = log(axis); // the chair rail and the crown
                    } else if (j >= WINDOW_Y0 && j <= WINDOW_Y1 && window(along) && !(i == 0 && along >= CHIMNEY_Z0)) {
                        block = edgeZ ? WINDOW + "[east=true,north=false,south=false,waterlogged=false,west=true]"
                                : WINDOW + "[east=false,north=true,south=true,waterlogged=false,west=false]";
                    } else {
                        block = panel(k == 0 && !edgeX, along, j, axis);
                    }
                    b.set(i, j, k, block);
                }
            }
        }
        for (int i = 0; i <= last; i++) {
            for (int k = 0; k <= last; k++) {
                b.set(i, ROOF, k, ROOF_GLASS);
            }
        }
    }

    /** The plaster, or a board's dark panel and its dark-oak frame (the north wall's is the results panel). */
    private static String panel(boolean north, int along, int j, String axis) {
        int a0 = north ? PANEL_X0 : SIDE_PANEL_0;
        int a1 = north ? PANEL_X1 : SIDE_PANEL_1;
        boolean inAlong = along >= a0 - 1 && along <= a1 + 1;
        boolean inHeight = j >= PANEL_Y0 - 1 && j <= PANEL_Y1 + 1;
        if (!inAlong || !inHeight) {
            return PLASTER;
        }
        if (j == PANEL_Y0 - 1 || j == PANEL_Y1 + 1) {
            return log(axis);
        }
        if (along == a0 - 1 || along == a1 + 1) {
            return log("y");
        }
        return PANEL;
    }

    /** Whether a wall column {@code along} (1..30) is a window. */
    static boolean window(int along) {
        for (int a : WINDOW_ALONG) {
            if (a == along) {
                return true;
            }
        }
        return false;
    }

    /** The coffered ceiling: dark-oak beams on the walls' top layer, open to the glass roof between them. */
    private static void ceiling(Builder b) {
        for (int i = 1; i <= 30; i++) {
            for (int z : BEAM_Z) {
                b.set(i, WALL_TOP, z, log("x"));
            }
        }
        for (int k = 1; k <= 30; k++) {
            for (int x : BEAM_X) {
                b.set(x, WALL_TOP, k, contains(BEAM_Z, k) ? PLANKS : log("z"));
            }
        }
    }

    // ---- the floor -------------------------------------------------------------------------------

    /** Dark oak in the wings, spruce in the hall, marble in the foyer, stone under the columns. */
    private static void floor(Builder b) {
        for (int i = 1; i <= 30; i++) {
            for (int k = 1; k <= 30; k++) {
                String block;
                if (i == COLUMN_X[0] || i == COLUMN_X[1]) {
                    block = MARBLE_BLACK;
                } else if (i < COLUMN_X[0] || i > COLUMN_X[1]) {
                    block = PLANKS;
                } else if (k <= 18) {
                    block = HALL_FLOOR;
                } else {
                    // 2 x 2 marble tiles, one straddling the middle line so the floor mirrors
                    int ti = Math.floorDiv(i - 15, 2);
                    int tk = Math.floorDiv(k - 19, 2);
                    block = Math.floorMod(ti + tk, 2) == 0 ? MARBLE_WHITE : MARBLE_BLACK;
                }
                b.set(i, 0, k, block);
            }
        }
    }

    /** A rug: carpet over {@code (i0..i1, k0..k1)}, a hidden sea lantern under every {@code every}-th block. */
    private static void rug(Builder b, int i0, int i1, int k0, int k1, String carpet, int every) {
        for (int i = i0; i <= i1; i++) {
            for (int k = k0; k <= k1; k++) {
                b.set(i, 1, k, carpet);
                if (every > 0 && Math.floorMod(i - i0, every) == every / 2
                        && Math.floorMod(k - k0, every) == every / 2) {
                    b.set(i, 0, k, LIGHT);
                }
            }
        }
    }

    // ---- the columns -----------------------------------------------------------------------------

    /** Tall dark-oak columns, a lantern sconce on each side that faces a room. */
    private static void columns(Builder b) {
        for (int x : COLUMN_X) {
            for (int z : COLUMN_Z) {
                b.set(x, 1, z, PLANKS);
                for (int j = 2; j <= 12; j++) {
                    b.set(x, j, z, log("y"));
                }
                b.set(x, 13, z, PLANKS);
                sconce(b, x - 1, 5, z);
                sconce(b, x + 1, 5, z);
            }
        }
    }

    /** A sconce: a fence arm at (i, j, k) out of the block beside it, a lantern hanging under it. */
    private static void sconce(Builder b, int i, int j, int k) {
        b.set(i, j, k, FENCE);
        b.set(i, j - 1, k, HANGING);
    }

    // ---- the Great Hall ----------------------------------------------------------------------------

    /** The podium on its green, the runner, the banquet tables, the 3 x 3 tables, the chandeliers and banners. */
    private static List<ClubhouseSite.Spot> hall(Builder b) {
        // the green under the podium, with the runner up the aisle to it
        rug(b, 11, 20, 1, 4, STAGE_RUG, 0); // lit by the runner and the sconces: the plinths would cover lights
        for (int k = 5; k <= 18; k++) {
            b.set(15, 1, k, RUNNER);
            b.set(16, 1, k, RUNNER);
            if (k % 3 == 0) {
                b.set(15, 0, k, LIGHT);
                b.set(16, 0, k, LIGHT);
            }
        }
        // the podium: 2 x 2 plinths, 1st in the middle (its spot on the middle line) and highest
        String[] top = {GOLD, IRON, COPPER};
        List<ClubhouseSite.Spot> podium = new ArrayList<>();
        for (int n = 0; n < 3; n++) {
            int x0 = PODIUM_X[n];
            int h = PODIUM_HEIGHT[n];
            for (int dx = 0; dx < 2; dx++) {
                for (int dz = 0; dz < 2; dz++) {
                    for (int j = 1; j <= h; j++) {
                        b.set(x0 + dx, j, PODIUM_Z + dz, j < h ? PLANKS : top[n]);
                    }
                }
            }
            podium.add(new ClubhouseSite.Spot(b.box.minX() + x0 + 1.0, b.box.minY() + h + 1,
                    b.box.minZ() + PODIUM_Z + 1.0, 0f));
        }
        // the banquet tables: white cloth, candles down the middle, three chairs a side with gaps
        for (int x0 : new int[]{FEAST_WEST_X0, FEAST_EAST_X0}) {
            for (int dx = 0; dx < 3; dx++) {
                for (int dz = 0; dz < FEAST_LEN; dz++) {
                    b.set(x0 + dx, 1, FEAST_Z0 + dz, CLOTH);
                }
            }
            b.set(x0 + 1, 2, FEAST_Z0, CANDLES);
            b.set(x0 + 1, 2, FEAST_Z0 + FEAST_LEN / 2, CANDLES);
            b.set(x0 + 1, 2, FEAST_Z0 + FEAST_LEN - 1, CANDLES);
            for (int dz = 0; dz < FEAST_LEN; dz += 2) {
                b.seat(x0 - 1, 1, FEAST_Z0 + dz, chair("west"), "GREEN");
                b.seat(x0 + 3, 1, FEAST_Z0 + dz, chair("east"), "GREEN");
            }
            chandelier(b, x0 + 1, FEAST_Z0 + FEAST_LEN / 2);
        }
        // the 3 x 3 tables on the same lines: the Feast Pot in the middle of the west one, flowers on the east one
        for (int x0 : new int[]{FEAST_WEST_X0, FEAST_EAST_X0}) {
            for (int dx = 0; dx < 3; dx++) {
                for (int dz = 0; dz < 3; dz++) {
                    b.set(x0 + dx, 1, ROUND_Z0 + dz, CLOTH);
                }
            }
            int cx = x0 + 1;
            int cz = ROUND_Z0 + 1;
            b.set(cx, 2, cz, x0 == FEAST_WEST_X0 ? POT : FLOWERS);
            b.seat(cx - 2, 1, cz, chair("west"), "GREEN");
            b.seat(cx + 2, 1, cz, chair("east"), "GREEN");
            b.seat(cx, 1, cz - 2, chair("north"), "GREEN");
            b.seat(cx, 1, cz + 2, chair("south"), "GREEN");
            pendant(b, cx, cz, 9);
        }
        // the club's green banners on the hall's side of the columns
        for (int z : new int[]{10, 15}) {
            b.set(COLUMN_X[0] + 1, 10, z, banner("east"));
            b.set(COLUMN_X[1] - 1, 10, z, banner("west"));
        }
        return podium;
    }

    /**
     * A chandelier at (ci, ck): chains down from the beam, a dark-oak hub with four fence arms, candles on the arms,
     * a lantern under the hub and each arm.
     */
    private static void chandelier(Builder b, int ci, int ck) {
        for (int j = 10; j <= 13; j++) {
            b.set(ci, j, ck, CHAIN);
        }
        b.set(ci, 9, ck, PLANKS);
        b.set(ci, 8, ck, HANGING);
        int[][] arms = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] a : arms) {
            b.set(ci + a[0], 9, ck + a[1], FENCE);
            b.set(ci + a[0], 10, ck + a[1], CANDLES);
            b.set(ci + a[0], 8, ck + a[1], HANGING);
        }
    }

    /** A pendant: a chain from the beam down to a lantern at {@code j}. */
    private static void pendant(Builder b, int i, int k, int j) {
        for (int y = j + 1; y <= 13; y++) {
            b.set(i, y, k, CHAIN);
        }
        b.set(i, j, k, HANGING);
    }

    // ---- the 19th Hole (the west wing) -------------------------------------------------------------

    /** The bar, the reading nook with the jukebox, the fireplace and its armchairs. */
    private static void bar(Builder b) {
        // the back bar on the wall, the counter, the stools, pendants over it
        for (int k = 2; k <= 10; k++) {
            b.set(1, 1, k, barrel("east"));
            b.set(1, 2, k, shelf("east"));
            b.set(1, 3, k, shelf("east"));
            b.set(4, 1, k, CABINET);
        }
        for (int k = 3; k <= 9; k += 2) {
            b.seat(5, 1, k, STOOL, "RED");
        }
        for (int k = 3; k <= 9; k += 3) {
            pendant(b, 4, k, 5);
        }
        rug(b, 2, 3, 2, 10, RUG, 3);
        // the reading nook: a wall of books, the jukebox in its middle, two armchairs and a lamp
        for (int k = 13; k <= 19; k++) {
            for (int j = 1; j <= 3; j++) {
                b.set(1, j, k, BOOKS);
            }
        }
        b.set(JUKEBOX_X, JUKEBOX_Y, JUKEBOX_Z, JUKEBOX);
        rug(b, 2, 6, 13, 19, RUG, 3);
        armchair(b, 4, 14, "east", "BROWN");
        armchair(b, 4, 18, "east", "BROWN");
        b.set(4, 1, 16, FENCE);
        b.set(4, 2, 16, STANDING);
        // the fireplace on the wall (its chimney south of the My stats board), armchairs facing it
        for (int k = CHIMNEY_Z0; k <= CHIMNEY_Z1; k++) {
            for (int j = 1; j <= WALL_TOP; j++) { // up to the ceiling: nothing to stand on at its top
                b.set(1, j, k, BRICKS);
            }
            b.set(2, 1, k, BRICKS);
            b.set(2, 2, k, BRICKS);
            b.set(2, 3, k, LEDGE);
        }
        // the firebox: two high, the fire at its back, an iron-bar screen in front so nobody steps on the fire
        b.set(1, 1, 26, FIRE);
        b.set(1, 2, 26, AIR);
        b.set(2, 1, 26, SCREEN);
        b.set(2, 2, 26, AIR);
        b.set(2, 4, 24, CANDLES);
        b.set(2, 4, 28, CANDLES);
        rug(b, 3, 6, 22, 30, RUG, 3);
        armchair(b, 5, 24, "east", "BROWN");
        armchair(b, 5, 28, "east", "BROWN");
        b.set(4, 1, 26, STOOL); // the coffee table
        pendant(b, 4, 21, 6);
        sconce(b, 1, 5, 12);
        sconce(b, 1, 5, 21);
    }

    /** An armchair at (i, k): a chair, its back to {@code facing}, an arm each side (along the other axis). */
    private static void armchair(Builder b, int i, int k, String facing, String color) {
        b.seat(i, 1, k, chair(facing), color);
        if (facing.equals("east") || facing.equals("west")) {
            b.set(i, 1, k - 1, arm("north"));
            b.set(i, 1, k + 1, arm("south"));
        } else {
            b.set(i - 1, 1, k, arm("west"));
            b.set(i + 1, 1, k, arm("east"));
        }
    }

    // ---- the Trophy Gallery (the east wing) --------------------------------------------------------

    /** The putting green, the trophy cabinet, armchairs facing it, the card table. */
    private static void gallery(Builder b) {
        // the putting green: moss, its cup a cauldron in the floor, the pin and its red flag in the cup
        for (int i = 24; i <= 30; i++) {
            for (int k = 1; k <= 7; k++) {
                b.set(i, 1, k, GREEN);
                if ((i == 25 || i == 29) && (k == 2 || k == 6)) {
                    b.set(i, 0, k, LIGHT);
                }
            }
        }
        b.set(27, 0, 4, CUP);
        b.set(27, 1, 4, PIN);
        b.set(27, 2, 4, PIN);
        b.set(27, 3, 4, FLAG);
        // the trophy cabinet under the frames (the frames are entities, not in the plan)
        for (int k = TROPHY_Z0 - 1; k <= TROPHY_Z0 + (TROPHY_MAX - 1) * TROPHY_STEP + 1; k++) {
            b.set(TROPHY_X, 1, k, CABINET);
        }
        rug(b, 25, 28, 9, 23, FELT, 3);
        armchair(b, 26, 12, "west", "BROWN");
        armchair(b, 26, 20, "west", "BROWN");
        for (int k : new int[]{12, 16, 20}) {
            pendant(b, 27, k, 6);
        }
        // the card table: green felt on a post, a chair each side
        rug(b, 25, 29, 26, 30, RUG, 3);
        b.set(27, 1, 28, FENCE);
        b.set(27, 2, 28, FELT);
        b.seat(26, 1, 28, chair("west"), "RED");
        b.seat(28, 1, 28, chair("east"), "RED");
        b.seat(27, 1, 27, chair("north"), "RED");
        b.seat(27, 1, 29, chair("south"), "RED");
        pendant(b, 27, 28, 6);
        sconce(b, 30, 5, 4);
        sconce(b, 30, 5, 27);
    }

    // ---- the Foyer ---------------------------------------------------------------------------------

    /** Planters in its corners, a chandelier over each half, sconces on the south wall. */
    private static void foyer(Builder b) {
        for (int i : new int[]{9, 22}) {
            for (int k : new int[]{19, 30}) {
                b.set(i, 1, k, PLANTER);
                b.set(i, 2, k, SHRUB);
            }
        }
        chandelier(b, 12, 25);
        chandelier(b, 19, 25);
        for (int i : new int[]{12, 19}) {
            sconce(b, i, 5, 30);
        }
        // the hall's south corners and the north wall, either side of the podium
        sconce(b, 9, 5, 1);
        sconce(b, 22, 5, 1);
    }

    private static boolean contains(int[] values, int v) {
        for (int x : values) {
            if (x == v) {
                return true;
            }
        }
        return false;
    }

    // ---- the places the room's entities go -----------------------------------------------------------

    /** The jukebox block of the generated room in {@code box}: {x, y, z}. */
    public static int[] jukebox(Box box) {
        return new int[]{box.minX() + JUKEBOX_X, box.minY() + JUKEBOX_Y, box.minZ() + JUKEBOX_Z};
    }

    /** The Feast Pot's block in {@code box} (show only: the real pot is in the home world): {x, y, z}. */
    public static int[] pot(Box box) {
        return new int[]{box.minX() + POT_X, box.minY() + 2, box.minZ() + POT_Z};
    }

    /**
     * The trophy case's frame positions in {@code box} (0.38.4): {@value #TROPHY_MAX} blocks of air on the east
     * wall's inner face, over the cabinet, under the All-time records board; the frames face west.
     */
    public static List<int[]> trophyFrames(Box box) {
        List<int[]> out = new ArrayList<>();
        for (int n = 0; n < TROPHY_MAX; n++) {
            out.add(new int[]{box.minX() + TROPHY_X, box.minY() + TROPHY_Y,
                    box.minZ() + TROPHY_Z0 + n * TROPHY_STEP});
        }
        return out;
    }

    /**
     * The feast plates in {@code box}: {@value FeastRules#CELLS} blocks of air, one over each block of the banquet
     * tables' outer rows, the west and east table taken in turn so both fill together; each frame faces up.
     */
    public static List<int[]> feastCells(Box box) {
        List<int[]> out = new ArrayList<>();
        for (int n = 0; n < 2 * 2 * FEAST_LEN; n++) {
            int k = n / 2;
            int x0 = n % 2 == 0 ? FEAST_WEST_X0 : FEAST_EAST_X0;
            int side = k / FEAST_LEN == 0 ? 0 : 2; // the table's west row, then its east row
            out.add(new int[]{box.minX() + x0 + side, box.minY() + FEAST_PLATE_Y,
                    box.minZ() + FEAST_Z0 + k % FEAST_LEN});
        }
        return out;
    }

    /**
     * The feast line in {@code box}: over the aisle between the banquet tables, a little above the plates, facing
     * south to the foyer.
     */
    public static WallBoards.Wall feastLine(Box box) {
        double x = box.minX() + 16.0;                                   // the middle line
        double z = box.minZ() + FEAST_Z0 + FEAST_LEN / 2.0;             // the tables' middle
        return new WallBoards.Wall(x, box.minY() + 4.0, z, WallBoards.turn(0f), FEAST_LINE_SCALE, FEAST_LINE_WIDTH);
    }

    /** The feast line's size and wrap: two short lines, readable across the room. */
    static final float FEAST_LINE_SCALE = 1.5f;
    static final int FEAST_LINE_WIDTH = 200;

    /** Where the cushions go in {@code box} (26.3): one on every chair, stool and armchair. */
    public static List<Seat> seats(Box box) {
        Builder b = new Builder(box);
        hall(b);
        bar(b);
        gallery(b);
        return b.seats;
    }

    // ---- the builder -------------------------------------------------------------------------------

    /** The plan being built: one op per block (a later set replaces an earlier one), in box offsets. */
    private static final class Builder {
        final Box box;
        final List<String> palette = new ArrayList<>();
        final List<BlockOp> ops = new ArrayList<>();
        final Map<Long, Integer> at = new HashMap<>();
        final List<Seat> seats = new ArrayList<>();

        Builder(Box box) {
            this.box = box;
        }

        void set(int i, int j, int k, String block) {
            long key = key(i, j, k);
            if (block.equals(AIR)) {
                Integer was = at.remove(key);
                if (was != null) {
                    ops.set(was, null);
                }
                return;
            }
            int state = palette.indexOf(block);
            if (state < 0) {
                palette.add(block);
                state = palette.size() - 1;
            }
            BlockOp op = new BlockOp(box.minX() + i, box.minY() + j, box.minZ() + k, (short) state);
            Integer was = at.get(key);
            if (was != null) {
                ops.set(was, op);
            } else {
                at.put(key, ops.size());
                ops.add(op);
            }
        }

        /** A seat block, and a cushion's place on it: over the seat's low half (a stair's back is "facing"). */
        void seat(int i, int j, int k, String block, String color) {
            set(i, j, k, block);
            double x = i + 0.5;
            double z = k + 0.5;
            float yaw = 90f; // a stool: facing the bar, west
            if (block.contains("_stairs")) {
                String f = block.substring(block.indexOf("facing=") + 7);
                f = f.substring(0, f.indexOf(','));
                switch (f) {
                    case "north" -> {
                        z += 0.25;
                        yaw = 0f;
                    }
                    case "south" -> {
                        z -= 0.25;
                        yaw = 180f;
                    }
                    case "east" -> {
                        x -= 0.25;
                        yaw = 90f;
                    }
                    default -> {
                        x += 0.25;
                        yaw = -90f;
                    }
                }
            }
            seats.add(new Seat(box.minX() + x, box.minY() + j + 0.5, box.minZ() + z, yaw, color));
        }

        String get(int i, int j, int k) {
            Integer n = at.get(key(i, j, k));
            BlockOp op = n == null ? null : ops.get(n);
            return op == null ? AIR : palette.get(op.state());
        }

        /** Fences and panes join what they touch, as the game would, so a verify never finds one changed. */
        void join() {
            List<long[]> fences = new ArrayList<>();
            for (Map.Entry<Long, Integer> e : at.entrySet()) {
                BlockOp op = ops.get(e.getValue());
                if (op == null) {
                    continue;
                }
                String id = id(palette.get(op.state()));
                if (id.endsWith("_fence")) {
                    fences.add(new long[]{op.x() - box.minX(), op.y() - box.minY(), op.z() - box.minZ()});
                }
            }
            fences.sort((p, q) -> Long.compare(key((int) p[0], (int) p[1], (int) p[2]),
                    key((int) q[0], (int) q[1], (int) q[2])));
            for (long[] p : fences) {
                int i = (int) p[0];
                int j = (int) p[1];
                int k = (int) p[2];
                String id = id(get(i, j, k));
                set(i, j, k, id + "[east=" + joins(get(i + 1, j, k)) + ",north=" + joins(get(i, j, k - 1))
                        + ",south=" + joins(get(i, j, k + 1)) + ",waterlogged=false,west=" + joins(get(i - 1, j, k))
                        + "]");
            }
            ops.removeIf(java.util.Objects::isNull);
        }

        /** Whether a fence joins a neighbour: another fence, or a whole solid block. */
        static boolean joins(String n) {
            String id = id(n);
            return id.endsWith("_fence") || SOLID.contains(id);
        }

        ClubhouseSite.Spot spot(int i, int j, int k, float yaw) {
            return new ClubhouseSite.Spot(box.minX() + i + 0.5, box.minY() + j, box.minZ() + k + 0.5, yaw);
        }

        static long key(int i, int j, int k) {
            return ((long) i << 32) | ((long) j << 16) | k;
        }
    }

    /** The whole solid blocks of the plan (a fence joins them). */
    static final Set<String> SOLID = Set.of(PLANKS, HALL_FLOOR, MARBLE_WHITE, MARBLE_BLACK, PLASTER, LIGHT, // PANEL is MARBLE_BLACK
            GOLD, IRON, COPPER, CLOTH, CABINET, BRICKS, BOOKS, "minecraft:jukebox", "minecraft:barrel", PLANTER,
            "minecraft:stripped_dark_oak_log", "minecraft:glass");
}
