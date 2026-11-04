package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.arcade.feast.FeastRules;
import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Makes the Clubhouse (CLUBHOUSE-SPEC §1): one bright room in the sky, 32 x 16 x 32, where racers
 * wait before a race and hang out after it.
 *
 * <p><b>The room.</b> A floor of white and light-blue stained glass; white walls with a light-blue
 * skirting, windows all round and a yellow trim; a glass roof, lit from inside by sea lanterns and
 * hanging lanterns. Along the north wall a podium (1st in the middle and highest, 2nd on its left,
 * 3rd on its right) under a dark results panel where the board floats. Two glass tables with birch
 * benches round them, a lantern on each. The south half is open floor: sixteen arrival spots, three
 * or more blocks apart, each facing the podium.
 *
 * <p><b>The feast tables</b> (0.38.6): two long 5 x 2 tables of orange glass between the podium and the arrival
 * spots, benches all round, with an aisle between them that runs to the podium. They stay all year as furniture; only
 * the food on them is seasonal ({@link FeastTable}: item frames on the glass, entities, not in the plan).
 *
 * <p><b>The jukebox corner</b> (0.38.4): a small pink glass stage in the south-west corner with a jukebox on it
 * and a glass block on top ({@link JukeboxCorner}). The trophy case ({@link TrophyWall}) is a row of item frames,
 * entities, not blocks: nothing of it is in the plan.
 *
 * <p><b>Why glass.</b> Nothing a mob could spawn on is ever open to the air, inside or on the roof:
 * every top face a mob could stand on is glass, a bench (bottom stairs) or a lantern, and the rest
 * (the walls, the gold pedestal, the sea lanterns) is covered. The room is lit day and night. No
 * way out by walking or jumping: the walls go up to a closed roof.
 *
 * <p>Pure and deterministic: the same box always gives the same plan (its {@link Plan#hash} is
 * pinned by a test). There is no seed: the room never changes.
 */
public final class ClubhousePlanner {

    /**
     * The planner's version: part of the plan, so a change of the room is a change of plan. 2 (0.38.4): the
     * jukebox corner. 3 (0.38.6): the two feast tables.
     */
    public static final int ALGO = 3;
    /** The plan's "slot" name. */
    public static final String SLOT = "clubhouse";

    /** The walls stand from the floor up to here (above the floor's own layer); the roof is on top. */
    public static final int WALL_TOP = 14;
    public static final int ROOF = 15;
    /** The pedestals' heights (1st, 2nd, 3rd) and where they stand (x offset, z offset). */
    public static final int[] PODIUM_HEIGHT = {3, 2, 1};
    public static final int[] PODIUM_X = {15, 13, 17};
    public static final int PODIUM_Z = 4;
    /** The arrival spots: a 4 x 4 grid in the open south half. */
    static final int[] SPAWN_X = {9, 13, 18, 22};
    static final int[] SPAWN_Z = {19, 22, 25, 28};
    /** The results panel on the north wall's inside (x offsets, y offsets). */
    static final int PANEL_X0 = 10;
    static final int PANEL_X1 = 21;
    static final int PANEL_Y0 = 7;
    static final int PANEL_Y1 = 11;
    /** The jukebox corner (0.38.4): a 3 x 3 stage at (1-3, 1, 28-30), the jukebox in its middle, glass on top. */
    static final int STAGE_X0 = 1;
    static final int STAGE_Z0 = 28;
    static final int JUKEBOX_X = 2;
    static final int JUKEBOX_Y = 2;
    static final int JUKEBOX_Z = 29;
    /**
     * The trophy case (0.38.4): item frames on the west wall's inner face (x = minX + 1), on the skirting row,
     * from z = minZ + {@value #TROPHY_Z0} every {@value #TROPHY_STEP}, under the My stats board, at most
     * {@link ClubBoard#MAX_ROWS}.
     */
    static final int TROPHY_Y = 2;
    static final int TROPHY_Z0 = 9;
    static final int TROPHY_STEP = 2;
    static final int TROPHY_MAX = ClubBoard.MAX_ROWS;
    /**
     * The feast tables (0.38.6): two 5 x 2 glass tables on the floor's layer + 1, at x 10-14 (west) and 17-21 (east),
     * z 8-9, the aisle x 15-16 between them running north to the podium; a plate (an item frame) over each glass block.
     */
    static final int FEAST_WEST_X0 = 10;
    static final int FEAST_EAST_X0 = 17;
    static final int FEAST_Z0 = 8;
    static final int FEAST_LEN = 5;
    static final int FEAST_PLATE_Y = 2;

    // ---- the blocks --------------------------------------------------------------------------------

    public static final String FLOOR_A = "minecraft:white_stained_glass";
    public static final String FLOOR_B = "minecraft:light_blue_stained_glass";
    public static final String WALL = "minecraft:white_concrete";
    public static final String SKIRTING = "minecraft:light_blue_concrete";
    public static final String TRIM = "minecraft:yellow_concrete";
    public static final String WINDOW = "minecraft:glass";
    public static final String ROOF_GLASS = "minecraft:glass";
    public static final String PANEL = "minecraft:black_concrete";
    public static final String LIGHT = "minecraft:sea_lantern";
    public static final String HANGING = "minecraft:lantern[hanging=true,waterlogged=false]";
    public static final String STANDING = "minecraft:lantern[hanging=false,waterlogged=false]";
    public static final String GOLD = "minecraft:gold_block";
    public static final String IRON = "minecraft:iron_block";
    public static final List<String> PODIUM_TOP = List.of("minecraft:yellow_stained_glass",
            "minecraft:light_gray_stained_glass", "minecraft:orange_stained_glass");
    public static final String TABLE_WEST = "minecraft:yellow_stained_glass";
    public static final String TABLE_EAST = "minecraft:lime_stained_glass";
    public static final String STAGE = "minecraft:pink_stained_glass";
    public static final String JUKEBOX = "minecraft:jukebox[has_record=false]";
    public static final String FEAST_GLASS = "minecraft:orange_stained_glass";

    /** A bench: birch stairs, bottom half, their back ("facing") away from the table. */
    static String bench(String facing) {
        return "minecraft:birch_stairs[facing=" + facing + ",half=bottom,shape=straight,waterlogged=false]";
    }

    /**
     * The top faces nothing can spawn on: glass of every colour, bottom stairs and lanterns. Every
     * block of the plan with air (or the sky) right above it is one of these ({@code ClubhousePlannerTest}).
     */
    public static boolean spawnProof(String blockData) {
        if (blockData == null) {
            return false;
        }
        String id = blockData.contains("[") ? blockData.substring(0, blockData.indexOf('[')) : blockData;
        if (id.equals("minecraft:glass") || id.endsWith("_stained_glass") || id.equals("minecraft:lantern")) {
            return true;
        }
        return id.endsWith("_stairs") && blockData.contains("half=bottom");
    }

    /** The blocks a mob could stand on if open to the air: never left uncovered. */
    static final Set<String> COVERED_ONLY = Set.of(WALL, SKIRTING, TRIM, PANEL, LIGHT, GOLD, IRON, JUKEBOX);

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
        int last = ClubhouseSettings.SIZE_X - 1;

        // the floor
        for (int i = 1; i < last; i++) {
            for (int k = 1; k < last; k++) {
                b.set(i, 0, k, ((i / 2) + (k / 2)) % 2 == 0 ? FLOOR_A : FLOOR_B);
            }
        }
        // the walls: skirting, windows, the panel, the trim
        for (int i = 0; i <= last; i++) {
            for (int k = 0; k <= last; k++) {
                boolean edgeX = i == 0 || i == last;
                boolean edgeZ = k == 0 || k == last;
                if (!edgeX && !edgeZ) {
                    continue;
                }
                int along = edgeZ ? i : k;
                boolean corner = edgeX && edgeZ;
                for (int j = 0; j <= WALL_TOP; j++) {
                    String block;
                    if (j == WALL_TOP) {
                        block = TRIM;
                    } else if (j == 1 || j == 2) {
                        block = SKIRTING;
                    } else if (j >= 3 && j <= 6 && !corner && window(along)) {
                        block = WINDOW;
                    } else if (k == 0 && !edgeX && i >= PANEL_X0 && i <= PANEL_X1 && j >= PANEL_Y0 && j <= PANEL_Y1) {
                        block = PANEL;
                    } else {
                        block = WALL;
                    }
                    b.set(i, j, k, block);
                }
            }
        }
        // the roof, and the lights under it
        for (int i = 0; i <= last; i++) {
            for (int k = 0; k <= last; k++) {
                b.set(i, ROOF, k, ROOF_GLASS);
            }
        }
        int[] lights = {6, 12, 19, 25};
        for (int i : lights) {
            for (int k : lights) {
                b.set(i, WALL_TOP, k, LIGHT);
            }
        }
        for (int i : new int[]{9, 22}) {
            for (int k : new int[]{9, 22}) {
                b.set(i, WALL_TOP, k, HANGING);
            }
        }
        // the podium: 1st in the middle and highest
        List<ClubhouseSite.Spot> podium = new ArrayList<>();
        for (int n = 0; n < 3; n++) {
            int x = PODIUM_X[n];
            int h = PODIUM_HEIGHT[n];
            for (int j = 1; j <= h; j++) {
                String block = j < h ? (n == 0 ? GOLD : IRON) : PODIUM_TOP.get(n);
                b.set(x, j, PODIUM_Z, block);
            }
            podium.add(b.spot(x, h + 1, PODIUM_Z, 0f));
        }
        // two tables, each with a lantern and four benches round it
        table(b, 5, 13, TABLE_WEST);
        table(b, 25, 13, TABLE_EAST);
        // the feast tables (0.38.6): two long tables with an aisle to the podium, benches facing the glass
        longTable(b, FEAST_WEST_X0, FEAST_Z0, FEAST_LEN, FEAST_GLASS);
        longTable(b, FEAST_EAST_X0, FEAST_Z0, FEAST_LEN, FEAST_GLASS);
        // the jukebox corner (0.38.4): a small stage in the south-west corner, the jukebox on it, glass on top
        for (int dx = 0; dx < 3; dx++) {
            for (int dz = 0; dz < 3; dz++) {
                b.set(STAGE_X0 + dx, 1, STAGE_Z0 + dz, STAGE);
            }
        }
        b.set(JUKEBOX_X, JUKEBOX_Y, JUKEBOX_Z, JUKEBOX);
        b.set(JUKEBOX_X, JUKEBOX_Y + 1, JUKEBOX_Z, ROOF_GLASS); // spawn-proof: nothing stands on a jukebox
        // the arrival spots, facing the podium
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
        List<String> summary = List.of("the Clubhouse: " + box.describe(), spawns.size() + " arrival spots, a podium "
                + "of 3, the results board on the north wall, two long feast tables, a jukebox in the south-west corner",
                "plan " + ALGO);
        Plan plan = Plan.of(SLOT, ALGO, 0L, box, b.palette, b.ops, List.of(), List.of(), null, summary, 0);
        return new ClubhouseSite(plan, spawns, podium, board, inside);
    }

    /** The jukebox block of the generated room in {@code box}: {x, y, z}. */
    public static int[] jukebox(Box box) {
        return new int[]{box.minX() + JUKEBOX_X, box.minY() + JUKEBOX_Y, box.minZ() + JUKEBOX_Z};
    }

    /**
     * The trophy case's frame positions in {@code box} (0.38.4): {@value #TROPHY_MAX} blocks of air on the west
     * wall's inner face, the wall's skirting behind each, under the My stats board; the frames face east.
     */
    public static List<int[]> trophyFrames(Box box) {
        List<int[]> out = new ArrayList<>();
        for (int n = 0; n < TROPHY_MAX; n++) {
            out.add(new int[]{box.minX() + 1, box.minY() + TROPHY_Y, box.minZ() + TROPHY_Z0 + n * TROPHY_STEP});
        }
        return out;
    }

    /**
     * The feast tables' plates in {@code box} (0.38.6): {@value FeastRules#CELLS} blocks of air, one over each glass
     * block, west and east table taken in turn so both fill together; each frame faces up.
     */
    public static List<int[]> feastCells(Box box) {
        List<int[]> out = new ArrayList<>();
        for (int n = 0; n < 2 * 2 * FEAST_LEN; n++) {
            int k = n / 2;
            int x0 = n % 2 == 0 ? FEAST_WEST_X0 : FEAST_EAST_X0;
            out.add(new int[]{box.minX() + x0 + k % FEAST_LEN, box.minY() + FEAST_PLATE_Y,
                    box.minZ() + FEAST_Z0 + k / FEAST_LEN});
        }
        return out;
    }

    /**
     * The feast tables' line in {@code box} (0.38.6): over the aisle between them, a little above the plates, facing
     * south to the arrival spots.
     */
    public static WallBoards.Wall feastLine(Box box) {
        double x = box.minX() + FEAST_EAST_X0 - 1;              // the aisle's middle: between x 15 and x 16
        double z = box.minZ() + FEAST_Z0 + 1;                   // the tables' middle: between z 8 and z 9
        return new WallBoards.Wall(x, box.minY() + 4.0, z, WallBoards.turn(0f), FEAST_LINE_SCALE, FEAST_LINE_WIDTH);
    }

    /** The feast line's size and wrap: two short lines, readable across the room. */
    static final float FEAST_LINE_SCALE = 1.5f;
    static final int FEAST_LINE_WIDTH = 200;

    /** Windows: three of every six blocks along a wall (away from the corners). */
    static boolean window(int along) {
        int m = along % 6;
        return m >= 2 && m <= 4;
    }

    /** A 2 x 2 glass table at (x0..x0+1, z0..z0+1), a lantern on it, benches facing it on every side. */
    private static void table(Builder b, int x0, int z0, String glass) {
        for (int dx = 0; dx < 2; dx++) {
            for (int dz = 0; dz < 2; dz++) {
                b.set(x0 + dx, 1, z0 + dz, glass);
            }
        }
        b.set(x0, 2, z0, STANDING);
        for (int d = 0; d < 2; d++) {
            b.set(x0 - 1, 1, z0 + d, bench("west"));
            b.set(x0 + 2, 1, z0 + d, bench("east"));
            b.set(x0 + d, 1, z0 - 1, bench("north"));
            b.set(x0 + d, 1, z0 + 2, bench("south"));
        }
    }

    /**
     * A long glass table, {@code len} x 2, at (x0..x0+len-1, z0..z0+1), with no lantern (its glass carries the plates),
     * a bench facing it at every side block: {@code 2 * len + 4} seats.
     */
    private static void longTable(Builder b, int x0, int z0, int len, String glass) {
        for (int dx = 0; dx < len; dx++) {
            for (int dz = 0; dz < 2; dz++) {
                b.set(x0 + dx, 1, z0 + dz, glass);
            }
            b.set(x0 + dx, 1, z0 - 1, bench("north"));
            b.set(x0 + dx, 1, z0 + 2, bench("south"));
        }
        for (int d = 0; d < 2; d++) {
            b.set(x0 - 1, 1, z0 + d, bench("west"));
            b.set(x0 + len, 1, z0 + d, bench("east"));
        }
    }

    /** The plan being built: one op per block (a later set replaces an earlier one), in box offsets. */
    private static final class Builder {
        final Box box;
        final List<String> palette = new ArrayList<>();
        final List<BlockOp> ops = new ArrayList<>();
        final java.util.Map<Long, Integer> at = new java.util.HashMap<>();

        Builder(Box box) {
            this.box = box;
        }

        void set(int i, int j, int k, String block) {
            int state = palette.indexOf(block);
            if (state < 0) {
                palette.add(block);
                state = palette.size() - 1;
            }
            BlockOp op = new BlockOp(box.minX() + i, box.minY() + j, box.minZ() + k, (short) state);
            long key = ((long) i << 32) | ((long) j << 16) | k;
            Integer was = at.put(key, ops.size());
            if (was != null) {
                ops.set(was, op);
                at.put(key, was);
            } else {
                ops.add(op);
            }
        }

        ClubhouseSite.Spot spot(int i, int j, int k, float yaw) {
            return new ClubhouseSite.Spot(box.minX() + i + 0.5, box.minY() + j, box.minZ() + k + 0.5, yaw);
        }
    }
}
