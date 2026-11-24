package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.arcade.feast.FeastRules;
import com.dierks.homecraft.games.chance.mobraces.RaceTrack;
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
     * jukebox corner. 3 (0.38.6): the two feast tables. 4 (0.40.0): the golf club. 5 (0.41.0): the side rooms, the
     * Arcade Room and the Trophy Room, through two archways in the Foyer's south wall. 6 (0.41.1): carpet runners to the
     * side rooms in place of the stripes, dark cabinet mats, a whole floor under every armchair arm, cushions centred. 7
     * (0.42.0): the podium to the Trophy Room, the bar, the green, the jukebox alcove, the Arcade Room made over. 10:
     * Mob Races' table in the middle of the Game Parlor, the Parlor's red carpet and its bigger card tables. 11: the
     * Arcade Room filled out (the owner: "it looks very empty"): a second race table for the kid race, nine cabinet
     * mats (one for every kid game and The Floor Is Lava's), a bright rug round the table, a ball pit, a plush case,
     * bean bags and coloured lamps. 12: no ball pit (the owner: it "just sits on the floor ... a weird thing kind of
     * sitting in the hallway"). 13: the side rooms' wing grown 16 rows to the south (the owner: "make the place larger
     * so all these things can fit comfortably ... We want to fill the space"): a walled ball pit, a bean-bag nook, a
     * prize-and-plush corner, four spare cabinet places and a viewing area for the kid race downstairs; a lounge, room
     * for two more card tables and the sportsbook's wall upstairs; the Trophy Room and the secret room longer.
     */
    public static final int ALGO = 13;
    /** The plan's "slot" name. */
    public static final String SLOT = "clubhouse";

    /** The main room's depth (z): the side rooms are south of it, in the box's last {@value #ANNEX_Z} rows. */
    public static final int MAIN_Z = 32;
    /** Plan 13: the side rooms' wing, 32 rows deep (16 from 0.41.0 to plan 12). */
    public static final int ANNEX_Z = 32;
    /**
     * The wing's depth before plan 13. A server whose 16 new rows can't be claimed (another games area too near, or
     * blocks that aren't the Clubhouse's in them) keeps its side rooms at this depth: the same rooms as plan 12.
     */
    public static final int OLD_ANNEX_Z = 16;
    /** The rows plan 13 added to the wing, claimed on their own ({@code ClubhouseRoom#WING_CLAIM_KEY}). */
    public static final int WING_Z = ANNEX_Z - OLD_ANNEX_Z;
    /** The side rooms' walls stand up to here; their glass roof is on top (they are lower than the main room). */
    public static final int SIDE_TOP = 7;
    public static final int SIDE_ROOF = 8;
    /**
     * The archways (0.41.0) in the Foyer's south wall (z = minZ + 31): three wide, three high, open (no door). The
     * Arcade Room's at x 11-13, the Trophy Room's at x 18-20 (mirrors); the colour path to each runs up its middle.
     */
    static final int ARCH_ARCADE_X0 = 11;
    static final int ARCH_TROPHY_X0 = 18;
    static final int ARCH_W = 3;
    static final int ARCH_H = 3;
    /** The first row of the Foyer (the path starts there). */
    static final int FOYER_Z0 = 19;
    /** The side rooms' west walls (x offsets): the Arcade Room's at 0, the Trophy Room's at 16; each 16 wide. */
    static final int ARCADE_X0 = 0;
    static final int TROPHY_X0 = 16;
    /** The side rooms' first and last rows of air (z offsets): plan 13's wing; {@link #z1} for a given box. */
    static final int SIDE_Z0 = MAIN_Z;
    static final int SIDE_Z1 = MAIN_Z + ANNEX_Z - 2;
    /** The last row of air of a wing kept at its old size (plan 12's {@code SIDE_Z1}). */
    static final int OLD_SIDE_Z1 = MAIN_Z + OLD_ANNEX_Z - 2;

    /** The side rooms' last row of air in {@code box} (z offset): its south wall is the row after. */
    static int z1(Box box) {
        return box.sizeZ() - 2;
    }

    /** Whether {@code box} has plan 13's grown wing (a box of the old depth keeps plan 12's rooms). */
    static boolean grown(Box box) {
        return box.sizeZ() == MAIN_Z + ANNEX_Z;
    }

    /**
     * The Arcade Room's cabinet places (x, z offsets, yaw), {@value #CABINETS} (0.42.0): two facing rows down the side
     * walls, the east row facing west and the west row facing east, taken in turn from the archway's end. Nothing of a
     * cabinet is in the plan but its coloured mat in the floor: the cabinet is a model (an entity) you can walk up to.
     * Plan 11 adds three places after the first six (whose order and places stay as they were): the west wall's
     * north end and both walls' south ends, so every kid game has a cabinet of its own and The Floor Is Lava one more.
     * The east wall's north end stays free: it is the archway's way in. Plan 13 adds four spare places after those
     * nine, down the grown wing's side walls (a mat waits on each until a game takes it); a wing kept at its old size
     * has the first {@value #OLD_CABINETS}.
     */
    static final int[][] CABINET_AT = {{14, 36, 90}, {1, 36, -90}, {14, 39, 90}, {1, 39, -90}, {14, 42, 90},
            {1, 42, -90}, {1, 33, -90}, {14, 45, 90}, {1, 45, -90}, {14, 48, 90}, {1, 48, -90}, {14, 51, 90},
            {1, 51, -90}};
    /** 0.42.0: the Arcade Room's prize counter (x 5-10) and claw machines (x 2 and 13) on its back row. */
    static final int PRIZE_X0 = 5;
    static final int PRIZE_X1 = 10;
    static final int[] CLAW_X = {2, 13};
    /** Plan 13: in the grown wing the claw machines flank the prize counter (the corners are the nooks'). */
    static final int[] GROWN_CLAW_X = {4, 11};
    /** 0.42.0: the Trophy Room's beams (across, at these rows) and banners (on the side walls); plan 13's longer room. */
    static final int[] TROPHY_BEAM_Z = {38, 44};
    static final int[] TROPHY_BANNER_Z = {35, 41};
    static final int[] GROWN_TROPHY_BEAM_Z = {38, 44, 50, 56};
    static final int[] GROWN_TROPHY_BANNER_Z = {35, 41, 47, 53, 59};
    /** Every cabinet place there can be (plan 13: nine and four spares), and a wing at its old size's. */
    public static final int CABINETS = 13;
    public static final int OLD_CABINETS = 9;

    /** The claw machines' columns in {@code box}. */
    static int[] clawX(Box box) {
        return grown(box) ? GROWN_CLAW_X : CLAW_X;
    }
    /**
     * The Trophy Room's pedestals: the podium for last week's top three at the back (2 x 1 plinths, 1st in the middle
     * and highest, gold, iron and copper as the hall's; the trophy on the west block, the holder's head on the east),
     * then {@value #RECORD_PEDESTALS} record pedestals (quartz, one high), four on each side wall, each a trophy block
     * and, south of it, its head block.
     */
    static final int[] ROOM_PODIUM_X = {23, 26, 20};
    static final int[] ROOM_PODIUM_HEIGHT = {3, 2, 1};
    static final int ROOM_PODIUM_Z = OLD_SIDE_Z1;
    static final int[] RECORD_Z = {33, 36, 39, 42};

    /** The podium's row in {@code box}: the Trophy Room's back row (plan 13: it moved back with the wall). */
    static int podiumZ(Box box) {
        return z1(box);
    }
    static final int RECORD_WEST_X = 17;
    static final int RECORD_EAST_X = 30;
    public static final int RECORD_PEDESTALS = 8;

    /** The walls stand from the floor up to here (above the floor's own layer); the roof is on top. */
    public static final int WALL_TOP = 14;
    public static final int ROOF = 15;
    /** The pedestals: heights (1st, 2nd, 3rd), their west column (each is 2 x 2), and their north row. */
    public static final int[] PODIUM_X = {15, 12, 18};
    public static final int PODIUM_Z = 2;
    /** 0.42.0: the hedge under the results board, x 13 to 18 (mirrors). */
    static final int HEDGE_X0 = 13;
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
    /** 0.42.0: the brewing stand at the bar counter's south end. */
    static final int BREWING_Z = 10;
    /**
     * The trophy case (0.38.4; the east wall since 0.40.0, under the All-time records board): item frames on the
     * east wall's inner face (x = minX + {@value #TROPHY_X}), over the cabinet, from z = minZ + {@value #TROPHY_Z0}
     * every {@value #TROPHY_STEP}, at most {@link ClubBoard#MAX_ROWS}. They face west.
     */
    static final int TROPHY_X = 30;
    /** The putting green's cup (0.42.0: its pole, flag, ball and putter around it). */
    static final int GREEN_CUP_X = 27;
    static final int GREEN_CUP_Z = 4;
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
    public static final String PLANTER = "minecraft:moss_block";
    public static final String SHRUB = "minecraft:flowering_azalea";
    public static final String CANDLES = "minecraft:white_candle[candles=3,lit=true,waterlogged=false]";
    public static final String SCREEN = "minecraft:iron_bars[east=false,north=true,south=true,waterlogged=false,west=false]";
    public static final String FIRE = "minecraft:campfire[facing=east,lit=true,signal_fire=false,waterlogged=false]";
    /** 0.41.0: the colour paths (the Arcade Room's blue, the Trophy Room's gold), the Arcade Room's floor and mats. */
    /**
     * 0.41.1: the runners to the side rooms, carpet three wide like the hall's red runner, square with the archways:
     * navy to the Arcade Room, gold to the Trophy Room (0.41.0's one-block concrete stripes looked "kinda weird").
     */
    public static final String PATH_ARCADE = "minecraft:blue_carpet";
    public static final String PATH_TROPHY = "minecraft:yellow_carpet";
    public static final String ARCADE_FLOOR = "minecraft:black_concrete";
    /** Where a cabinet stands (0.41.1: dark, never purple: 0.41.0's magenta read as the missing texture). */
    public static final String MAT = "minecraft:polished_blackstone";
    public static final String TROPHY_RUG = "minecraft:red_carpet";
    public static final String PEDESTAL = "minecraft:quartz_pillar[axis=y]";
    /** 0.42.0: the putting green's pole (a thin end rod, lit), the jukebox alcove's light, the arcade's roof and neon. */
    public static final String POLE = "minecraft:end_rod[facing=up]";
    public static final String ALCOVE = "minecraft:ochre_froglight[axis=y]";
    public static final String BREWING = "minecraft:brewing_stand[has_bottle_0=true,has_bottle_1=true,has_bottle_2=false]";
    public static final String TINTED = "minecraft:tinted_glass";
    static final String[] NEON = {"minecraft:ochre_froglight[axis=y]", "minecraft:verdant_froglight[axis=y]",
            "minecraft:pearlescent_froglight[axis=y]"};
    static final String[] CONFETTI = {"minecraft:cyan_concrete", "minecraft:lime_concrete", "minecraft:orange_concrete",
            "minecraft:light_blue_concrete"};
    public static final String CLAW = "minecraft:red_concrete";

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
            case "minecraft:sea_lantern", "minecraft:lantern", "minecraft:glowstone", "minecraft:ochre_froglight",
                 "minecraft:verdant_froglight", "minecraft:pearlescent_froglight", "minecraft:shroomlight" -> 15;
            case "minecraft:end_rod" -> 14;
            case "minecraft:light" -> { // the invisible light over Mob Races' table: its level is its state
                int at = blockData.indexOf("level=");
                int end = at < 0 ? -1 : at + 6;
                while (end >= 0 && end < blockData.length() && Character.isDigit(blockData.charAt(end))) {
                    end++;
                }
                yield at < 0 ? 15 : Integer.parseInt(blockData.substring(at + 6, end));
            }
            case "minecraft:brewing_stand" -> 1;
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
     * banners, candles, pots and plants, and the invisible barrier and light blocks over Mob Races' table, which
     * light passes as it does air). Everything else stops it: slabs, stairs and trapdoors too, though light can slip
     * past some of them in the game, so the flood never counts more light than there is.
     */
    public static boolean letsLight(String blockData) {
        String id = id(blockData);
        return id.equals(AIR) || id.equals(BARRIER) || id.equals("minecraft:light") || id.equals("minecraft:glass")
                || id.endsWith("_pane") || id.endsWith("chain")
                || id.equals("minecraft:lantern") || id.endsWith("_fence") || id.endsWith("_carpet")
                || id.endsWith("_banner") || id.endsWith("candle") || id.startsWith("minecraft:potted_")
                || id.equals(SHRUB) || id.equals("minecraft:iron_bars") || id.equals("minecraft:end_rod")
                || id.endsWith("_stained_glass");
    }

    /**
     * The blocks a mob can never stand on: glass and panes, lanterns, chains, candles, carpets, banners, plants and
     * pots, fences (too narrow), a barrier (the game spawns nothing on one) and a light block (it is air to stand
     * on). A mob could stand on anything else if it were dark there.
     */
    public static boolean noFooting(String blockData) {
        String id = id(blockData);
        return id.equals(BARRIER) || id.equals("minecraft:light") || id.equals("minecraft:glass")
                || id.endsWith("_pane") || id.equals("minecraft:lantern")
                || id.endsWith("chain") || id.endsWith("candle") || id.endsWith("_carpet") || id.endsWith("_banner")
                || id.startsWith("minecraft:potted_") || id.equals(SHRUB) || id.endsWith("_fence")
                || id.equals("minecraft:iron_bars") || id.equals("minecraft:end_rod") || id.equals(TINTED)
                || id.endsWith("_stained_glass");
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

    /** The 26.3 cushion's width (blocks): its footprint must stay on its seat block. */
    public static final double CUSHION_WIDTH = 1.0;

    /**
     * Which side rooms the plan has (0.41.0). A room that is off is not built at all: its half of the annex is air
     * and its archway stays wall, so the main room is exactly as it was on that side.
     */
    public record Rooms(boolean arcade, boolean trophy, boolean secret, boolean parlor) {
        public static final Rooms NONE = new Rooms(false, false, false, false);
        /** Every room: the two side rooms and (0.43.0) the two upstairs. */
        public static final Rooms BOTH = new Rooms(true, true, true, true);

        public Rooms {
            secret = secret && trophy; // the secret room's way in is in the Trophy Room
            parlor = parlor && arcade; // the Game Parlor stands on the Arcade Room's roof
        }

        /** 0.41.0's two side rooms alone. */
        public Rooms(boolean arcade, boolean trophy) {
            this(arcade, trophy, false, false);
        }

        public boolean any() {
            return arcade || trophy || secret || parlor;
        }
    }

    /** A place in the room: the block's middle at the floor's top ({@code y}), and the way it faces (yaw). */
    public record Place3(double x, double y, double z, float yaw) {
    }

    /**
     * A trophy pedestal (0.41.0): where the trophy stands (its feet: the pedestal's top), where its holder's head
     * stands (the block beside it), and the way both face.
     */
    public record Pedestal(Place3 trophy, Place3 head) {
    }

    private ClubhousePlanner() {
    }

    /**
     * The Clubhouse in {@code box}: 32 x 16 x 64 with both side rooms (plan 13; 32 x 16 x 48, their old size, is
     * plan 12's rooms), or (0.41.0) 32 x 16 x 32, the main room alone.
     *
     * @throws IllegalArgumentException when the box isn't one of the Clubhouse's sizes
     */
    public static ClubhouseSite plan(Box box) {
        return plan(box, box != null && box.sizeZ() > MAIN_Z ? Rooms.BOTH : Rooms.NONE);
    }

    /** Whether {@code depth} is one the Clubhouse's box can have: the main room alone, or with its wing (either size). */
    static boolean depthOk(int depth) {
        return depth == MAIN_Z || depth == MAIN_Z + OLD_ANNEX_Z || depth == MAIN_Z + ANNEX_Z;
    }

    /**
     * The Clubhouse in {@code box} with the side rooms {@code rooms} (0.41.0). The main room is the box's first
     * {@value #MAIN_Z} rows; with a room, the box is {@value #ANNEX_Z} deeper (the wing; {@value #OLD_ANNEX_Z} where
     * the wing keeps its old size). A deeper box with no room leaves the wing air (a room switched off is cleared).
     *
     * @param box the Clubhouse box: 32 x 16 x 64 (plan 13), 32 x 16 x 48 (its wing at the old size), or 32 x 16 x 32
     *            with no side room
     * @throws IllegalArgumentException when the box isn't the Clubhouse's size, or a 32-deep box is given a room
     */
    public static ClubhouseSite plan(Box box, Rooms rooms) {
        Rooms r = rooms == null ? Rooms.NONE : rooms;
        boolean annex = box != null && box.sizeZ() > MAIN_Z;
        if (box == null || box.sizeX() != ClubhouseSettings.SIZE_X || box.sizeY() != ClubhouseSettings.SIZE_Y
                || !depthOk(box.sizeZ())) {
            throw new IllegalArgumentException("the Clubhouse box is " + ClubhouseSettings.SIZE_X + " x "
                    + ClubhouseSettings.SIZE_Y + " x " + ClubhouseSettings.SIZE_Z + " (or "
                    + (MAIN_Z + OLD_ANNEX_Z) + " deep with its side rooms at their old size, or " + MAIN_Z
                    + " deep without them), not "
                    + (box == null ? "missing" : box.sizeX() + " x " + box.sizeY() + " x " + box.sizeZ()));
        }
        if (!annex && r.any()) {
            throw new IllegalArgumentException("the side rooms need the box's last " + ANNEX_Z + " rows");
        }
        Builder b = new Builder(box);
        shell(b);
        ceiling(b);
        floor(b);
        columns(b);
        List<ClubhouseSite.Spot> podium = hall(b);
        bar(b);
        gallery(b);
        foyer(b, r);
        if (r.arcade()) {
            arcadeRoom(b);
        }
        if (r.trophy()) {
            trophyRoom(b);
        }
        if (r.secret()) {
            secretRoom(b);
        }
        if (r.parlor()) {
            parlor(b);
        }
        b.join();

        List<ClubhouseSite.Spot> spawns = new ArrayList<>();
        for (int k : SPAWN_Z) {
            for (int i : SPAWN_X) {
                spawns.add(b.spot(i, 1, k, 180f));
            }
        }
        if (r.trophy()) {
            podium = roomPodium(box); // 0.42.0: "Photo time!" on the Trophy Room's podium
        }
        ClubhouseSite.Spot board = new ClubhouseSite.Spot(box.minX() + (PANEL_X0 + PANEL_X1 + 1) / 2.0,
                box.minY() + (PANEL_Y0 + PANEL_Y1 + 1) / 2.0 - 0.5, box.minZ() + 1.1, 0f);
        Box inside = new Box(box.minX() + 1, box.minY() + 1, box.minZ() + 1, box.maxX() - 1, box.minY() + WALL_TOP,
                box.minZ() + MAIN_Z - 2);
        List<Box> more = new ArrayList<>();
        if (r.arcade()) {
            more.add(arch(box, ARCH_ARCADE_X0));
            more.add(arcadeInside(box));
        }
        if (r.trophy()) {
            more.add(arch(box, ARCH_TROPHY_X0));
            more.add(trophyInside(box));
        }
        if (r.secret()) {
            more.add(upInside(box, TROPHY_X0));
        }
        if (r.parlor()) {
            more.add(upInside(box, ARCADE_X0));
        }
        List<String> summary = List.of("the Clubhouse: " + box.describe(), spawns.size() + " arrival spots in the "
                + "foyer, the Great Hall with a podium of 3 and two banquet tables (feast tables, the Feast Pot), "
                + "the 19th Hole with its bar and a jukebox, the Trophy Gallery with a putting green"
                + (r.arcade() ? ", the Arcade Room" : "") + (r.trophy() ? ", the Trophy Room" : "")
                + (r.secret() ? ", a secret room" : "") + (r.parlor() ? ", the Game Parlor" : ""),
                "plan " + ALGO);
        Plan plan = Plan.of(SLOT, ALGO, 0L, box, b.palette, b.ops, List.of(), List.of(), null, summary, 0);
        return new ClubhouseSite(plan, spawns, podium, board, inside, more);
    }

    /** An archway's air (the three blocks through the wall, three high): part of the room for "in the Clubhouse". */
    static Box arch(Box box, int x0) {
        return new Box(box.minX() + x0, box.minY() + 1, box.minZ() + MAIN_Z - 1, box.minX() + x0 + ARCH_W - 1,
                box.minY() + ARCH_H, box.minZ() + MAIN_Z - 1);
    }

    /** The Arcade Room's air in {@code box} (a box with its wing). */
    public static Box arcadeInside(Box box) {
        return new Box(box.minX() + ARCADE_X0 + 1, box.minY() + 1, box.minZ() + SIDE_Z0, box.minX() + ARCADE_X0 + 14,
                box.minY() + SIDE_TOP, box.minZ() + z1(box));
    }

    /** The Trophy Room's air in {@code box} (a box with its wing). */
    public static Box trophyInside(Box box) {
        return new Box(box.minX() + TROPHY_X0 + 1, box.minY() + 1, box.minZ() + SIDE_Z0, box.minX() + TROPHY_X0 + 14,
                box.minY() + SIDE_TOP, box.minZ() + z1(box));
    }

    // ---- the shell -------------------------------------------------------------------------------

    /** The walls: panelling to the chair rail, plaster, windows, the board panels, the crown; the glass roof. */
    private static void shell(Builder b) {
        int last = ClubhouseSettings.SIZE_X - 1; // the main room is square: 32 x 32
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

    /**
     * The green under the results board, the runner, the banquet tables, the 3 x 3 tables, the chandeliers and banners.
     * 0.42.0: the podium left the Great Hall (the owner: it "feels very out of place"); a hedge of flowering azalea
     * runs under the results board instead, and "Photo time!" is on the Trophy Room's podium. The spots returned are
     * the green's three, only for a room with no Trophy Room.
     */
    private static List<ClubhouseSite.Spot> hall(Builder b) {
        // the green under the results board, with the runner up the aisle to it
        rug(b, 11, 20, 1, 4, STAGE_RUG, 0); // lit by the runner and the sconces
        for (int k = 5; k <= 18; k++) {
            b.set(15, 1, k, RUNNER);
            b.set(16, 1, k, RUNNER);
            if (k % 3 == 0) {
                b.set(15, 0, k, LIGHT);
                b.set(16, 0, k, LIGHT);
            }
        }
        // a hedge of flowering azalea on moss under the results board, centred on the middle line
        for (int i = HEDGE_X0; i <= 31 - HEDGE_X0; i++) {
            b.set(i, 1, 1, PLANTER);
            b.set(i, 2, 1, SHRUB);
        }
        // with no Trophy Room, "Photo time!" is on the green: three places, 1st on the middle line
        List<ClubhouseSite.Spot> podium = new ArrayList<>();
        for (int n = 0; n < 3; n++) {
            podium.add(new ClubhouseSite.Spot(b.box.minX() + PODIUM_X[n] + 1.0, b.box.minY() + 1,
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
            if (x0 == FEAST_EAST_X0) {
                b.set(cx, 2, cz, FLOWERS); // 0.42.0: the Feast Pot on the west one is a pot on the cloth (a display)
            }
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
        b.set(4, 2, BREWING_Z, BREWING); // 0.42.0: a brewing stand at the counter's end (bottles and glasses: displays)
        rug(b, 2, 3, 2, 10, RUG, 3);
        // the reading nook: a wall of books, the jukebox in its middle, two armchairs and a lamp
        for (int k = 13; k <= NOOK_Z1; k++) {
            for (int j = 1; j <= 3; j++) {
                b.set(1, j, k, BOOKS);
            }
        }
        b.set(JUKEBOX_X, JUKEBOX_Y, JUKEBOX_Z, JUKEBOX);
        // 0.42.0: a lit alcove over the jukebox and a record shelf either side, so it shows across the wing
        b.set(JUKEBOX_X, JUKEBOX_Y + 1, JUKEBOX_Z, ALCOVE);
        b.set(JUKEBOX_X, JUKEBOX_Y, JUKEBOX_Z - 1, shelf("east"));
        b.set(JUKEBOX_X, JUKEBOX_Y, JUKEBOX_Z + 1, shelf("east"));
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
        armchair(b, i, 1, k, facing, color);
    }

    /** An armchair standing on the floor whose top is {@code j} (plan 13: the Parlor's lounge has them too). */
    private static void armchair(Builder b, int i, int j, int k, String facing, String color) {
        b.seat(i, j, k, chair(facing), color);
        if (facing.equals("east") || facing.equals("west")) {
            armOver(b, i, j, k - 1, arm("north"));
            armOver(b, i, j, k + 1, arm("south"));
        } else {
            armOver(b, i - 1, j, k, arm("west"));
            armOver(b, i + 1, j, k, arm("east"));
        }
    }

    /**
     * An armchair's arm at (i, 1, k) (0.41.1): an open trapdoor is thin, so the floor under it shows. Where it stands on
     * a rug, the floor under it becomes the rug's colour as a whole block (and a hidden light there goes: it showed as
     * a white strip beside the east wing's armchairs).
     */
    private static void armOver(Builder b, int i, int j, int k, String arm) {
        String under = b.get(i, j, k);
        if (id(under).endsWith("_carpet")) {
            b.set(i, j - 1, k, underCarpet(id(under)));
        }
        b.set(i, j, k, arm);
    }

    /** The whole block that looks like a carpet from above: its wool (moss carpet: moss). */
    static String underCarpet(String carpet) {
        return carpet.equals("minecraft:moss_carpet") ? PLANTER : carpet.replace("_carpet", "_wool");
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
        // 0.42.0: the cup open, a thin pole standing in it (the flag, the ball and a putter are displays)
        b.set(GREEN_CUP_X, 0, GREEN_CUP_Z, CUP);
        b.set(GREEN_CUP_X, 1, GREEN_CUP_Z, POLE);
        b.set(GREEN_CUP_X, 2, GREEN_CUP_Z, POLE);
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

    /**
     * Planters in its corners, a chandelier over each half, sconces on the south wall. With a side room (0.41.0) its
     * archway is cut in the south wall, the sconce over it moves to either side of it (its sign hangs there), and a
     * colour path runs from the Foyer's first row to the archway down its middle.
     */
    private static void foyer(Builder b, Rooms rooms) {
        for (int i : new int[]{9, 22}) {
            for (int k : new int[]{19, 30}) {
                b.set(i, 1, k, PLANTER);
                b.set(i, 2, k, SHRUB);
            }
        }
        chandelier(b, 12, 25);
        chandelier(b, 19, 25);
        boolean[] on = {rooms.arcade(), rooms.trophy()};
        int[] x0 = {ARCH_ARCADE_X0, ARCH_TROPHY_X0};
        String[] path = {PATH_ARCADE, PATH_TROPHY};
        for (int n = 0; n < 2; n++) {
            int mid = x0[n] + ARCH_W / 2;
            if (!on[n]) {
                sconce(b, mid, 5, 30);
                continue;
            }
            sconce(b, x0[n] - 1, 5, 30);
            sconce(b, x0[n] + ARCH_W, 5, 30);
            for (int i = x0[n]; i < x0[n] + ARCH_W; i++) {
                for (int j = 2; j <= ARCH_H; j++) {
                    b.set(i, j, MAIN_Z - 1, AIR);
                }
                // the runner: three wide, from the Foyer's first row through the archway, ending at its far side
                for (int k = FOYER_Z0; k <= MAIN_Z - 1; k++) {
                    b.set(i, 1, k, path[n]);
                }
            }
        }
        // the hall's south corners and the north wall, either side of the podium
        sconce(b, 9, 5, 1);
        sconce(b, 22, 5, 1);
    }

    // ---- the side rooms (0.41.0) -------------------------------------------------------------------

    /**
     * A side room's shell, west wall at {@code x0}: panelling to the chair rail and plaster above (the main room's
     * walls, lower), its floor, and a glass roof at {@value #SIDE_ROOF}. Its north wall is the main room's south
     * wall, so the only way in or out is the archway.
     */
    private static void sideShell(Builder b, int x0, String floor) {
        sideShell(b, x0, floor, ROOF_GLASS, false);
    }

    /**
     * A side room's shell with its roof ({@code roof}), and (0.42.0) a neon strip of coloured froglights along its
     * own walls at y 6 when {@code neon}.
     */
    private static void sideShell(Builder b, int x0, String floor, String roof, boolean neon) {
        int z1 = b.z1 + 1; // the south wall
        for (int i = x0; i <= x0 + 15; i++) {
            for (int k = MAIN_Z; k <= z1; k++) {
                boolean edgeX = i == x0 || i == x0 + 15;
                boolean edgeZ = k == z1;
                b.set(i, SIDE_ROOF, k, roof);
                if (!edgeX && !edgeZ) {
                    b.set(i, 0, k, floor);
                    continue;
                }
                String axis = edgeZ && !edgeX ? "x" : "z";
                for (int j = 0; j <= SIDE_TOP; j++) {
                    String block;
                    if (edgeX && edgeZ) {
                        block = j == 0 || j == SIDE_TOP ? PLANKS : log("y");
                    } else if (j <= 3 || j == SIDE_TOP) {
                        block = PLANKS;
                    } else if (j == 4) {
                        block = log(axis);
                    } else if (neon && j == 6) {
                        block = NEON[Math.floorMod(i + k, NEON.length)];
                    } else {
                        block = PLASTER;
                    }
                    b.set(i, j, k, block);
                }
            }
        }
    }

    /**
     * The Arcade Room (the south-west; 0.42.0, the owner: "this thing needs to look much more like an arcade"): dim
     * under a tinted-glass roof, a neon strip of coloured froglights round its walls, an arcade carpet (black with
     * coloured confetti and lit squares), cabinets in two facing rows down the walls on dark mats, a prize counter
     * of glass cases along the back wall and a claw machine either end of it (their prizes are displays), lanterns
     * hanging low. A soft arcade chime plays now and then while anyone is in.
     *
     * <p>Plan 13, the grown wing (x 1-14, z 32-62): the old room's north part as it was (the nine cabinet places, the
     * kid race's table and its rug), then a viewing area for the table, four spare cabinet places down the side walls,
     * a walled ball pit in the middle, a bean-bag nook in the south-west corner, the prize counter between its two claw
     * machines on the back wall and a prize-and-plush corner in the south-east, a bright tiled stripe across the floor
     * and coloured lamps over it all. The east side (x 11-13) stays open from the archway to the plush counter, and a
     * lane runs round the pit.
     */
    private static void arcadeRoom(Builder b) {
        int x0 = ARCADE_X0;
        sideShell(b, x0, ARCADE_FLOOR, TINTED, true);
        for (int i = x0 + 1; i <= x0 + 14; i++) {
            for (int k = SIDE_Z0; k <= b.z1; k++) {
                if (Math.floorMod(i * 5 + k * 3, 7) == 0) {
                    b.set(i, 0, k, CONFETTI[Math.floorMod(i + 2 * k, CONFETTI.length)]);
                }
            }
        }
        if (b.big) {
            // plan 13: a bright tiled stripe across the floor north of the pit and in front of the back row
            for (int k : TILE_Z) {
                for (int i = x0 + 2; i <= x0 + 13; i++) {
                    b.set(i, 0, k, CONFETTI[Math.floorMod(i, CONFETTI.length)]);
                }
            }
        }
        for (int i = x0 + 2; i <= x0 + 13; i += 4) {
            for (int k = SIDE_Z0 + 1; k <= b.z1; k += 4) {
                b.set(i, 0, k, LIGHT);
            }
        }
        int places = b.big ? CABINETS : OLD_CABINETS;
        for (int n = 0; n < places; n++) {
            b.set(CABINET_AT[n][0], 0, CABINET_AT[n][1], MAT);
        }
        // the prize counter along the back wall: a counter with glass cases on it
        for (int i = PRIZE_X0; i <= PRIZE_X1; i++) {
            b.set(i, 1, b.z1, CABINET);
            b.set(i, 2, b.z1, ROOF_GLASS);
        }
        // a claw machine either end of the back row: a red base and top, a glass box between
        for (int i : b.big ? GROWN_CLAW_X : CLAW_X) {
            b.set(i, 1, b.z1, CLAW);
            b.set(i, 2, b.z1, ROOF_GLASS);
            b.set(i, 3, b.z1, ROOF_GLASS);
            b.set(i, 4, b.z1, CLAW);
        }
        for (int i : new int[]{x0 + 4, x0 + 11}) {
            for (int k : new int[]{SIDE_Z0 + 3, SIDE_Z0 + 9}) {
                b.set(i, SIDE_TOP, k, HANGING);
            }
        }
        if (b.big) {
            // coloured lamps on chains over the new floor
            int n = 0;
            for (int k : GROWN_LAMP_Z) {
                for (int i : new int[]{x0 + 4, x0 + 11}) {
                    b.set(i, SIDE_TOP, k, CHAIN);
                    b.set(i, SIDE_TOP - 1, k, NEON[n++ % NEON.length]);
                }
            }
        }
        kidTable(b);
        playThings(b);
    }

    /**
     * Plan 11: the kid race's table in the Arcade Room (the owner: "we need to take the mob race and plop it into the
     * arcade!", "A second track in the Arcade Room"): the Parlor's table turned to run north-south, x {@value
     * #KID_TRACK_X0} to 8 and z {@value #KID_TRACK_Z0} to 42, west of the archway's way in, so the way from the
     * archway down the east side stays wide open. Three columns of floor lie between it and the west cabinets' standing
     * places, four between it and the east ones' (the archway's lane among them), two rows between its north end and the
     * archway's row and two between its south end and the prize counter: it can be walked all the way round, and a
     * bright rug in two rings lies on that way round.
     *
     * <p>The same treatment as the Parlor's: a plain dark-wood table one block high, an invisible light where the racers
     * run, an invisible barrier over that (nobody stands or jumps onto the track), nothing in the way of seeing it.
     *
     * <p>Plan 13 gives it a viewing area south of its rug (the grown wing): a red carpet two rows deep with three bright
     * stools facing the table, and a coloured lamp over each end.
     */
    private static void kidTable(Builder b) {
        for (int i = KID_TRACK_X0; i < KID_TRACK_X0 + RaceTrack.DEPTH; i++) {
            for (int k = KID_TRACK_Z0; k < KID_TRACK_Z0 + RaceTrack.LENGTH; k++) {
                b.set(ARCADE_X0 + i, 1, k, TRACK_TABLE);
                b.set(ARCADE_X0 + i, 2, k, TRACK_LIGHT);
                b.set(ARCADE_X0 + i, 3, k, BARRIER);
            }
        }
        // the bright rug on the way round: a yellow ring next to the table and a light blue one round that
        for (int i = KID_TRACK_X0 - 2; i <= KID_TRACK_X0 + RaceTrack.DEPTH + 1; i++) {
            for (int k = KID_TRACK_Z0 - 2; k <= KID_TRACK_Z0 + RaceTrack.LENGTH + 1; k++) {
                int out = Math.max(Math.max(KID_TRACK_X0 - i, i - (KID_TRACK_X0 + RaceTrack.DEPTH - 1)),
                        Math.max(KID_TRACK_Z0 - k, k - (KID_TRACK_Z0 + RaceTrack.LENGTH - 1)));
                if (out == 1 || out == 2) {
                    b.set(ARCADE_X0 + i, 1, k, KID_RUG[out - 1]);
                }
            }
        }
        // coloured lamps on chains over the rug's four corners
        int n = 0;
        for (int k : new int[]{KID_TRACK_Z0 - 1, KID_TRACK_Z0 + RaceTrack.LENGTH}) {
            for (int i : new int[]{KID_TRACK_X0 - 1, KID_TRACK_X0 + RaceTrack.DEPTH}) {
                b.set(ARCADE_X0 + i, SIDE_TOP, k, CHAIN);
                b.set(ARCADE_X0 + i, SIDE_TOP - 1, k, KID_LAMPS[n++]);
            }
        }
        if (!b.big) {
            return;
        }
        // plan 13: the viewing area, the rug's width, two rows south of it, stools in the second row facing the table
        for (int i = KID_TRACK_X0 - 2; i <= KID_TRACK_X0 + RaceTrack.DEPTH + 1; i++) {
            for (int k = VIEW_Z0; k <= VIEW_Z0 + 1; k++) {
                b.set(ARCADE_X0 + i, 1, k, VIEW_RUG);
            }
        }
        for (int i : VIEW_STOOL_X) {
            b.seat(ARCADE_X0 + i, 1, VIEW_Z0 + 1, VIEW_STOOL, "YELLOW", 180f);
        }
        for (int i : new int[]{KID_TRACK_X0 - 2, KID_TRACK_X0 + RaceTrack.DEPTH + 1}) {
            b.set(ARCADE_X0 + i, SIDE_TOP, VIEW_Z0, CHAIN);
            b.set(ARCADE_X0 + i, SIDE_TOP - 1, VIEW_Z0, KID_LAMPS[n++ % KID_LAMPS.length]);
        }
    }

    /**
     * The Arcade Room's play things, all on floor nobody needs to walk on. Plan 11/12 (a wing at its old size): a glass
     * plush case on the back row and four wool bean bags. Plan 13 (the grown wing): {@link #ballPit}, {@link #beanNook}
     * and {@link #plushCorner}. Nothing to climb into: a case is glass to its top, and every top is lit.
     */
    private static void playThings(Builder b) {
        if (b.big) {
            ballPit(b);
            beanNook(b);
            plushCorner(b);
            return;
        }
        // plan 12 (the owner: "The 'Ball Pit' just sits on the floor and doesn't read like a ball pit ... just a weird
        // thing kind of sitting in the hallway ... you need a bigger space"): no ball pit in the room at its old size
        for (int i : PLUSH_X) {
            b.set(ARCADE_X0 + i, 1, b.z1, CABINET);
            b.set(ARCADE_X0 + i, 2, b.z1, ROOF_GLASS);
        }
        for (int[] bag : OLD_BEAN_BAGS) {
            b.set(ARCADE_X0 + bag[0], 1, b.z1 - bag[1], BEAN_BAG[bag[2]]);
        }
    }

    /**
     * Plan 13, the ball pit (the owner: "doesn't read like a ball pit ... you need a bigger space"): in the middle of
     * the grown wing, x {@value #PIT_X0}-{@value #PIT_X1} and z {@value #PIT_Z0}-{@value #PIT_Z1}, a low padded wall one
     * block high (wool in four colours, a white carpet rim on top) with a gap in the middle of its north side to walk
     * in, a light blue carpet floor over hidden lights, and full of coloured balls ({@link #decor}: small displays two
     * and three deep, nothing to stand on or be stuck in). A lane goes all round it.
     */
    private static void ballPit(Builder b) {
        for (int i = PIT_X0; i <= PIT_X1; i++) {
            for (int k = PIT_Z0; k <= PIT_Z1; k++) {
                boolean wall = i == PIT_X0 || i == PIT_X1 || k == PIT_Z0 || k == PIT_Z1;
                if (wall && !(k == PIT_Z0 && i == PIT_GAP_X)) {
                    b.set(ARCADE_X0 + i, 1, k, PIT_WALL[Math.floorMod(i + k, PIT_WALL.length)]);
                    b.set(ARCADE_X0 + i, 2, k, PIT_RIM);
                } else {
                    b.set(ARCADE_X0 + i, 1, k, PIT_FLOOR);
                }
            }
        }
        for (int[] l : PIT_LIGHTS) {
            b.set(ARCADE_X0 + l[0], 0, l[1], LIGHT);
        }
    }

    /**
     * Plan 13, the bean-bag nook (the owner: the bean bags "just read as blocks"): the south-west corner, x 1-3 and z
     * 58-62, on a pink rug. Four bean bags round a low table, each a bright stair (pink, teal, yellow, orange) a child
     * sits in, its back away from the table, rounded off by wool displays ({@link #decor}); a cushion on each seat. Two
     * lamps on the wall over the bags and a coloured one hanging over the table; the corner behind the bags stays
     * open to walk round.
     */
    private static void beanNook(Builder b) {
        for (int i = NOOK_X0; i <= NOOK_X1; i++) {
            for (int k = NOOK_Z0; k <= b.z1; k++) {
                b.set(ARCADE_X0 + i, 1, k, NOOK_RUG);
            }
        }
        b.set(ARCADE_X0 + BEAN_TABLE[0], 1, BEAN_TABLE[1], BEAN_TABLE_BLOCK);
        for (int[] bag : BEAN_BAGS) {
            b.seat(ARCADE_X0 + bag[0], 1, bag[1], beanBag(bag), BAG_DYE[bag[2]]);
        }
        for (int k : NOOK_LAMP_Z) { // lamps on the west wall over the bags (on the floor they shut the corner in)
            sconce(b, ARCADE_X0 + NOOK_X0, NOOK_LAMP_Y, k);
        }
        b.set(ARCADE_X0 + BEAN_TABLE[0], SIDE_TOP, BEAN_TABLE[1], CHAIN);
        b.set(ARCADE_X0 + BEAN_TABLE[0], SIDE_TOP - 1, BEAN_TABLE[1], "minecraft:pearlescent_froglight[axis=y]");
    }

    /** A bean bag's block: a bright bottom stair, its back away from the table ("facing" the way its back is). */
    static String beanBag(int[] bag) {
        String facing = bag[0] < BEAN_TABLE[0] ? "west" : bag[0] > BEAN_TABLE[0] ? "east"
                : bag[1] < BEAN_TABLE[1] ? "north" : "south";
        return "minecraft:" + BAG_WOOD[bag[2]] + "_stairs[facing=" + facing
                + ",half=bottom,shape=straight,waterlogged=false]";
    }

    /**
     * Plan 13, the prize-and-plush corner (the owner: "Case is cool, but could certainly be a bit more detail"): the
     * south-east corner. A glass display case in an L along the east wall and the back wall, on a dark-wood base, two
     * glass high, a lit froglight cap on every block of it: the plushies (mob heads below, soft toys on a shelf above),
     * each over a ribbon of its prize colour, are displays ({@link #decor}). A counter stands in front of the case's
     * back part, with price tags and a bell on it.
     */
    private static void plushCorner(Builder b) {
        for (int[] c : PLUSH_CASE) {
            b.set(ARCADE_X0 + c[0], 1, c[1], CABINET);
            b.set(ARCADE_X0 + c[0], 2, c[1], ROOF_GLASS);
            b.set(ARCADE_X0 + c[0], 3, c[1], ROOF_GLASS);
            b.set(ARCADE_X0 + c[0], 4, c[1], PLUSH_LIGHT);
        }
        for (int i : PLUSH_COUNTER_X) {
            b.set(ARCADE_X0 + i, 1, PLUSH_COUNTER_Z, CABINET);
        }
    }

    /** Plan 11: the kid race's table, its north-west corner (x, z offsets): 3 wide (x) and 8 long (z). */
    static final int KID_TRACK_X0 = 6;
    static final int KID_TRACK_Z0 = MAIN_Z + 3;
    /** The rug's two rings round it, the inner first; the lamps over its corners (north-west, north-east, ...). */
    static final String[] KID_RUG = {"minecraft:yellow_carpet", "minecraft:light_blue_carpet"};
    static final String[] KID_LAMPS = {"minecraft:pearlescent_froglight[axis=y]",
            "minecraft:verdant_froglight[axis=y]", "minecraft:ochre_froglight[axis=y]", "minecraft:shroomlight"};
    /** Plan 13: the kid table's viewing area (its first row, two deep), its rug, and its stools (x) in the second row. */
    static final int VIEW_Z0 = KID_TRACK_Z0 + RaceTrack.LENGTH + 2;
    static final String VIEW_RUG = "minecraft:red_carpet";
    static final int[] VIEW_STOOL_X = {5, 7, 9};
    static final String VIEW_STOOL = "minecraft:bamboo_slab[type=bottom,waterlogged=false]";
    /** Plan 13: the rows of the bright tiled stripe, and of the coloured lamps over the grown wing. */
    static final int[] TILE_Z = {MAIN_Z + 15, MAIN_Z + 27};
    static final int[] GROWN_LAMP_Z = {MAIN_Z + 15, MAIN_Z + 22};
    /** The plush case on the back row (x), between the prize counter and the east claw machine (a wing at its old size). */
    static final int[] PLUSH_X = {11, 12};
    /**
     * The old size's bean bags (x, rows in from the back row, colour): either side of the west claw's corner, and on
     * the west wall.
     */
    static final int[][] OLD_BEAN_BAGS = {{3, 0, 0}, {4, 0, 1}, {1, 3, 2}, {1, 2, 3}};
    static final String[] BEAN_BAG = {"minecraft:lime_wool", "minecraft:light_blue_wool", "minecraft:pink_wool",
            "minecraft:yellow_wool"};
    /** Plan 13, the ball pit: its wall's corners (x, z offsets), the gap in its north side, its blocks and lights. */
    static final int PIT_X0 = 4;
    static final int PIT_X1 = 10;
    static final int PIT_Z0 = MAIN_Z + 18;
    static final int PIT_Z1 = MAIN_Z + 24;
    static final int PIT_GAP_X = 7;
    static final String[] PIT_WALL = {"minecraft:red_wool", "minecraft:yellow_wool", "minecraft:blue_wool",
            "minecraft:lime_wool"};
    static final String PIT_RIM = "minecraft:white_carpet";
    static final String PIT_FLOOR = "minecraft:light_blue_carpet";
    static final int[][] PIT_LIGHTS = {{5, PIT_Z0 + 1}, {9, PIT_Z0 + 1}, {7, PIT_Z0 + 3}, {5, PIT_Z1 - 1},
            {9, PIT_Z1 - 1}};
    /** Plan 13, the bean-bag nook: its corner (x 1-3 from z 58 to the back row), rug, low table and bags. */
    static final int NOOK_X0 = 1;
    static final int NOOK_X1 = 3;
    static final int NOOK_Z0 = MAIN_Z + 26;
    static final String NOOK_RUG = "minecraft:pink_carpet";
    /** The nook's two wall lamps (sconces on the west wall: their rows, and the arm's height). */
    static final int[] NOOK_LAMP_Z = {MAIN_Z + 27, MAIN_Z + 29};
    static final int NOOK_LAMP_Y = 4;
    static final int[] BEAN_TABLE = {2, MAIN_Z + 28};
    static final String BEAN_TABLE_BLOCK = "minecraft:birch_slab[type=bottom,waterlogged=false]";
    /** The bean bags (x, z, colour), round the low table: west, east, north, south. */
    static final int[][] BEAN_BAGS = {{1, MAIN_Z + 28, 0}, {3, MAIN_Z + 28, 1}, {2, MAIN_Z + 27, 2},
            {2, MAIN_Z + 29, 3}};
    static final String[] BAG_WOOD = {"cherry", "warped", "bamboo", "acacia"};
    static final String[] BAG_WOOL = {"PINK_WOOL", "CYAN_WOOL", "YELLOW_WOOL", "ORANGE_WOOL"};
    static final String[] BAG_DYE = {"PINK", "CYAN", "YELLOW", "ORANGE"};
    /**
     * Plan 13, the prize-and-plush corner: the display case's blocks (x, z: up the east wall, then along the back wall),
     * its lit cap, and the counter (x, at one row) in front of its back part.
     */
    static final int[][] PLUSH_CASE = {{14, MAIN_Z + 25}, {14, MAIN_Z + 26}, {14, MAIN_Z + 27}, {14, MAIN_Z + 28},
            {14, MAIN_Z + 29}, {14, MAIN_Z + 30}, {13, MAIN_Z + 30}, {12, MAIN_Z + 30}};
    static final String PLUSH_LIGHT = "minecraft:pearlescent_froglight[axis=y]";
    static final int[] PLUSH_COUNTER_X = {12, 13};
    static final int PLUSH_COUNTER_Z = MAIN_Z + 28;

    /**
     * Plan 11: where the kid race's table stands in {@code box}: its north-east corner at the height of its top,
     * running south along its length and west across it (as if someone stood on that corner block facing south).
     */
    static RaceTable.Frame arcadeTable(Box box) {
        return new RaceTable.Frame(box.minX() + ARCADE_X0 + KID_TRACK_X0 + RaceTrack.DEPTH, box.minY() + 2,
                box.minZ() + KID_TRACK_Z0, 0, 1, -1, 0);
    }

    /**
     * The Trophy Room (the south-east): a red rug over hidden lights (0.41.1: no stripe across it), the podium at the
     * back (last week's top three, and from 0.42.0 every "Photo time!"), a record pedestal for each all-time record on
     * each side wall, lanterns hanging from the glass roof, and (0.42.0) the club's look: dark-oak beams across the
     * ceiling and green banners on the side walls. The trophies and the heads are entities, not in the plan.
     */
    private static void trophyRoom(Builder b) {
        int x0 = TROPHY_X0;
        sideShell(b, x0, PLANKS);
        rug(b, x0 + 1, x0 + 14, SIDE_Z0, b.z1, TROPHY_RUG, 3);
        String[] top = {GOLD, IRON, COPPER};
        for (int n = 0; n < 3; n++) {
            for (int dx = 0; dx < 2; dx++) {
                for (int j = 1; j <= ROOM_PODIUM_HEIGHT[n]; j++) {
                    b.set(ROOM_PODIUM_X[n] + dx, j, b.z1, j < ROOM_PODIUM_HEIGHT[n] ? PLANKS : top[n]);
                }
            }
        }
        for (int z : RECORD_Z) {
            for (int x : new int[]{RECORD_WEST_X, RECORD_EAST_X}) {
                b.set(x, 1, z, PEDESTAL);
                b.set(x, 1, z + 1, PEDESTAL);
            }
        }
        int[] banners = b.big ? GROWN_TROPHY_BANNER_Z : TROPHY_BANNER_Z;
        for (int i : new int[]{x0 + 4, x0 + 11}) {
            for (int k : banners) { // the lanterns hang on the banners' rows (plan 12: z 35 and 41)
                b.set(i, SIDE_TOP, k, HANGING);
            }
        }
        for (int k : b.big ? GROWN_TROPHY_BEAM_Z : TROPHY_BEAM_Z) {
            for (int i = x0 + 1; i <= x0 + 14; i++) {
                b.set(i, SIDE_TOP, k, log("x"));
            }
        }
        for (int k : banners) {
            b.set(x0 + 1, 5, k, banner("east"));
            b.set(x0 + 14, 5, k, banner("west"));
        }
        if (b.big) {
            // plan 13, the longer room: two armchairs facing the podium, and a planter in each back corner
            for (int i : TROPHY_ARMCHAIR_X) {
                armchair(b, i, 1, TROPHY_ARMCHAIR_Z, "north", "RED");
            }
            for (int i : new int[]{x0 + 1, x0 + 14}) {
                b.set(i, 1, b.z1, PLANTER);
                b.set(i, 2, b.z1, SHRUB);
            }
        }
    }

    /** Plan 13: the Trophy Room's armchairs (x; at one row), facing the podium from the room's new middle. */
    static final int[] TROPHY_ARMCHAIR_X = {20, 27};
    static final int TROPHY_ARMCHAIR_Z = MAIN_Z + 20;

    // ---- 0.43.0: upstairs, reached by a tap (a teleport), never by a passage --------------------------------

    /** The upstairs rooms' floor (over the side rooms' roofs), the walls' top row, and their glass roof (the box's top). */
    static final int UP_FLOOR = SIDE_ROOF + 1;
    static final int UP_TOP = 14;
    /** The secret room's trophy ledges (top slabs) at these heights, and its centre pedestal. */
    static final int[] LEDGE_Y = {11, 13};
    static final int SECRET_CX = 23;
    static final int SECRET_CZ = 40;
    /** Plan 13: the centre pedestal in the middle of the longer room. */
    static final int GROWN_SECRET_CZ = MAIN_Z + 15;
    /** The upstairs rooms' beams (rows clear of the secret room's trophy places, which are on odd rows). */
    static final int[] UP_BEAM_Z = {MAIN_Z + 4, MAIN_Z + 12};
    static final int[] GROWN_UP_BEAM_Z = {MAIN_Z + 4, MAIN_Z + 12, MAIN_Z + 20, MAIN_Z + 28};

    /** The secret room's centre pedestal's row in {@code box}. */
    static int secretCz(Box box) {
        return grown(box) ? GROWN_SECRET_CZ : SECRET_CZ;
    }

    /** The upstairs beams' rows in a room of this size. */
    private static int[] upBeams(Builder b) {
        return b.big ? GROWN_UP_BEAM_Z : UP_BEAM_Z;
    }
    /**
     * The Game Parlor's two card tables (x, z offsets of each table's north-west felt block): Twenty-One and Higher or
     * Lower. Since plan 10 (the owner: "maybe we can make the 4 person tables a bit larger") each is
     * {@value #PARLOR_TABLE_W} felt wide (x) and {@value #PARLOR_TABLE_D} long (z), with a chair at each of its four
     * corners, two down each side with a gap between (two chairs side by side would be a bench).
     */
    static final int[][] PARLOR_TABLE = {{4, 41}, {10, 41}};
    static final int PARLOR_TABLE_W = 2;
    static final int PARLOR_TABLE_D = 3;
    /**
     * The Game Parlor's floor (plan 10; the owner: "if you are going to do tables in green we shouldnt use that as the
     * carpet"): a red casino carpet, so the green felt tables stand out. (Before plan 10: green carpet.)
     */
    public static final String PARLOR_CARPET = "minecraft:red_carpet";
    /** The parlor's machines on its walls (x, z, yaw): two Ore Slots, the Wheel, Coin Flip. */
    static final int[][] PARLOR_WALL = {{1, 37, -90}, {1, 40, -90}, {14, 37, 90}, {14, 40, 90}};
    /** The games those stand for, in order: the walls' four, then the two tables. */
    public static final List<String> PARLOR_GAMES = List.of("ore_slots", "ore_slots", "wheel", "coin_flip",
            "twenty_one", "higher_lower");
    public static final String FELT_BLOCK = "minecraft:green_wool";
    public static final String BRASS = "minecraft:waxed_cut_copper";
    public static final String LEDGE_SLAB = "minecraft:dark_oak_slab[type=top,waterlogged=false]";

    /** An upstairs room's air in {@code box}: x0 + 1 .. x0 + 14, over the side room. */
    static Box upInside(Box box, int x0) {
        return new Box(box.minX() + x0 + 1, box.minY() + UP_FLOOR + 1, box.minZ() + MAIN_Z + 1, box.minX() + x0 + 14,
                box.minY() + UP_TOP, box.minZ() + z1(box));
    }

    /** The secret room's air in {@code box}. */
    public static Box secretInside(Box box) {
        return upInside(box, TROPHY_X0);
    }

    /** The Game Parlor's air in {@code box}. */
    public static Box parlorInside(Box box) {
        return upInside(box, ARCADE_X0);
    }

    /**
     * An upstairs room's shell, west wall at {@code x0}: a whole floor at {@value #UP_FLOOR} (over the side room's
     * roof), walls of their own on every side (the main room's south windows look onto a wall), panelling to a chair
     * rail and plaster above, beams under a glass roof.
     */
    private static void upShell(Builder b, int x0, String floor) {
        int z0 = MAIN_Z;
        int z1 = b.z1 + 1;
        for (int i = x0; i <= x0 + 15; i++) {
            for (int k = z0; k <= z1; k++) {
                b.set(i, UP_FLOOR, k, (i == x0 || i == x0 + 15 || k == z0 || k == z1) ? PLANKS : floor);
                b.set(i, ROOF, k, ROOF_GLASS);
                boolean edgeX = i == x0 || i == x0 + 15;
                boolean edgeZ = k == z0 || k == z1;
                if (!edgeX && !edgeZ) {
                    continue;
                }
                String axis = edgeZ && !edgeX ? "x" : "z";
                for (int j = UP_FLOOR + 1; j <= UP_TOP; j++) {
                    String block;
                    if (edgeX && edgeZ) {
                        block = log("y");
                    } else if (j <= UP_FLOOR + 2) {
                        block = PLANKS;
                    } else if (j == UP_FLOOR + 3) {
                        block = log(axis);
                    } else {
                        block = PLASTER;
                    }
                    b.set(i, j, k, block);
                }
            }
        }
        for (int k : upBeams(b)) {
            for (int i = x0 + 1; i <= x0 + 14; i++) {
                b.set(i, UP_TOP, k, log("x"));
            }
        }
    }

    /**
     * The secret trophy room (0.43.0; the owner: "her own little trophy room (make it a secret room) that shows all
     * her high scores"), over the Trophy Room: cosy dark oak, a brown rug over hidden lights, two rows of trophy ledges
     * round three walls, a quartz pedestal in the middle (the visitor's own head and name stand on it), lanterns. Its
     * trophies are each visitor's own, drawn for them alone; the way in is a little gold star in the Trophy Room.
     */
    private static void secretRoom(Builder b) {
        int x0 = TROPHY_X0;
        upShell(b, x0, PLANKS);
        for (int i = x0 + 1; i <= x0 + 14; i++) {
            for (int k = SIDE_Z0 + 1; k <= b.z1; k++) {
                b.set(i, UP_FLOOR + 1, k, RUG);
                if (Math.floorMod(i - x0, 3) == 2 && Math.floorMod(k - SIDE_Z0, 3) == 2) {
                    b.set(i, UP_FLOOR, k, LIGHT);
                }
            }
        }
        for (int y : LEDGE_Y) {
            for (int k = SIDE_Z0 + 2; k <= b.z1 - 1; k++) {
                b.set(x0 + 1, y, k, LEDGE_SLAB);
                b.set(x0 + 14, y, k, LEDGE_SLAB);
            }
            for (int i = x0 + 2; i <= x0 + 13; i++) {
                b.set(i, y, b.z1, LEDGE_SLAB);
            }
        }
        b.set(SECRET_CX, UP_FLOOR + 1, secretCz(b.box), PEDESTAL);
        for (int i : new int[]{x0 + 4, x0 + 11}) {
            for (int k : upBeams(b)) {
                b.set(i, UP_TOP - 1, k, HANGING); // hung from the beams
            }
        }
    }

    /**
     * The Game Parlor (0.43.0; the owner: "maybe that would be fun since we have a bunch of chance games"), over the
     * Arcade Room and reached from the 19th Hole, never the Foyer: a red carpet, green felt tables, brass trim, warm lamps; two
     * Ore Slots machines and Coin Flip and the Wheel on its walls, a card table each for Twenty-One and Higher or Lower.
     * The machines are displays you tap (the games of chance's own gate: {@code hcm.games.chance}, the day's token
     * limit, Take a break, the click cooldown).
     */
    private static void parlor(Builder b) {
        int x0 = ARCADE_X0;
        upShell(b, x0, PLANKS);
        for (int i = x0 + 2; i <= x0 + 13; i++) {
            for (int k = SIDE_Z0 + 2; k <= b.z1 - 1; k++) {
                b.set(i, UP_FLOOR + 1, k, PARLOR_CARPET);
                if (Math.floorMod(i - x0, 3) == 2 && Math.floorMod(k - SIDE_Z0, 3) == 2) {
                    b.set(i, UP_FLOOR, k, LIGHT);
                }
            }
        }
        for (int i = x0 + 1; i <= x0 + 14; i++) {
            b.set(i, UP_FLOOR + 3, b.z1 + 1, BRASS); // a brass rail along the back wall
        }
        for (int[] t : PARLOR_TABLE) {
            // a card table one block high (felt on the floor: no jumping), the cards in its middle, a chair either
            // side of each row (0.44.2-0.49.0: one block of felt and two chairs)
            int east = t[0] + PARLOR_TABLE_W;
            int south = t[1] + PARLOR_TABLE_D - 1;
            for (int k = t[1]; k <= south; k++) {
                for (int i = t[0]; i < east; i++) {
                    b.set(i, UP_FLOOR + 1, k, FELT_BLOCK);
                }
                if (k == t[1] || k == south) {
                    b.seat(t[0] - 1, UP_FLOOR + 1, k, chair("west"), "GREEN");
                    b.seat(east, UP_FLOOR + 1, k, chair("east"), "GREEN");
                }
            }
        }
        for (int[] m : PARLOR_WALL) {
            b.set(m[0], UP_FLOOR, m[1], MAT);
        }
        for (int i : new int[]{x0 + 4, x0 + 11}) {
            for (int k : upBeams(b)) {
                b.set(i, UP_TOP - 1, k, HANGING); // hung from the beams
            }
        }
        raceTable(b);
        if (b.big) {
            lounge(b);
            sportsbook(b);
        }
    }

    /**
     * Plan 13, the Parlor's lounge (the south-west of its grown floor): a black velvet rug, two armchairs facing east
     * across the room to the sportsbook's board, a side table beside each (a dark-oak post with a white top), and a
     * floor lamp at either end. The two spare card tables' places ({@link #PARLOR_SPARE_TABLE}) north of it are left
     * as plain carpet.
     */
    private static void lounge(Builder b) {
        int floor = UP_FLOOR + 1;
        for (int i = LOUNGE_X0; i <= LOUNGE_X1; i++) {
            for (int k = LOUNGE_Z0; k <= LOUNGE_Z1; k++) {
                b.set(ARCADE_X0 + i, floor, k, LOUNGE_RUG);
            }
        }
        for (int k : LOUNGE_CHAIR_Z) {
            armchair(b, ARCADE_X0 + LOUNGE_CHAIR_X, floor, k, "west", "RED");
        }
        for (int[] t : LOUNGE_TABLES) {
            b.set(ARCADE_X0 + t[0], floor, t[1], FENCE);
            b.set(ARCADE_X0 + t[0], floor + 1, t[1], SIDE_TABLE_TOP);
        }
        for (int[] l : LOUNGE_LAMPS) {
            b.set(ARCADE_X0 + l[0], floor, l[1], FENCE);
            b.set(ARCADE_X0 + l[0], floor + 1, l[1], STANDING);
        }
    }

    /**
     * Plan 13, the sportsbook's place ({@value #SPORTSBOOK_GAME}): its big board is a display on the Parlor's east
     * wall ({@link #parlorBoards}, drawn by {@code SideRooms} only while that game is there and open), {@value
     * #BOARD_W} wide over z {@value #BOARD_Z0} to 57; in front of it a counter one block high and three stools facing
     * it, an aisle between the counter and the wall.
     */
    private static void sportsbook(Builder b) {
        int floor = UP_FLOOR + 1;
        for (int k = BOOK_COUNTER_Z0; k <= BOOK_COUNTER_Z1; k++) {
            b.set(ARCADE_X0 + BOOK_COUNTER_X, floor, k, CABINET);
        }
        for (int k : BOOK_STOOL_Z) {
            b.seat(ARCADE_X0 + BOOK_COUNTER_X - 1, floor, k, STOOL, "RED", -90f);
        }
    }

    /** Plan 13: the lounge's rug (x 2-6, z 50-57), its armchairs (x; rows), side tables and floor lamps (x, z). */
    static final int LOUNGE_X0 = 2;
    static final int LOUNGE_X1 = 6;
    static final int LOUNGE_Z0 = MAIN_Z + 18;
    static final int LOUNGE_Z1 = MAIN_Z + 25;
    static final String LOUNGE_RUG = "minecraft:black_carpet";
    static final int LOUNGE_CHAIR_X = 3;
    static final int[] LOUNGE_CHAIR_Z = {MAIN_Z + 19, MAIN_Z + 23};
    static final int[][] LOUNGE_TABLES = {{3, MAIN_Z + 21}, {3, MAIN_Z + 25}};
    static final int[][] LOUNGE_LAMPS = {{2, MAIN_Z + 18}, {2, MAIN_Z + 25}};
    static final String SIDE_TABLE_TOP = "minecraft:white_carpet";
    /**
     * Plan 13: the places for two more card tables later (x, z of each one's north-west felt block, as {@link
     * #PARLOR_TABLE}): the same size, south of the two there are, their chairs' places kept free.
     */
    static final int[][] PARLOR_SPARE_TABLE = {{4, MAIN_Z + 14}, {10, MAIN_Z + 14}};
    /** Plan 13, the sportsbook: its game, its board's place (x, z, yaw: the board's middle at the wall's foot). */
    public static final String SPORTSBOOK_GAME = "sportsbook";
    static final int[] SPORTSBOOK = {14, MAIN_Z + 22, 90};
    /** The board's first row (z) and its size (blocks): it hangs on the wall over the counter. */
    static final int BOARD_Z0 = MAIN_Z + 19;
    public static final int BOARD_W = 7;
    public static final int BOARD_H = 3;
    /** The counter in front of the board (x; from row to row) and its stools' rows (one column further in). */
    static final int BOOK_COUNTER_X = 13;
    static final int BOOK_COUNTER_Z0 = MAIN_Z + 20;
    static final int BOOK_COUNTER_Z1 = MAIN_Z + 24;
    static final int[] BOOK_STOOL_Z = {MAIN_Z + 20, MAIN_Z + 22, MAIN_Z + 24};

    /**
     * Mob Races' table (plan 10; the owner: "I hope you make a mini track and you literally see the tiny mobs running
     * on the track!", "make the track as small as you can", "it would be fun to be able to walk around it"): in the
     * open middle of the Game Parlor, {@value RaceTrack#LENGTH} blocks long (x {@value #TRACK_X0} to 11) and
     * {@value RaceTrack#DEPTH} deep (z 36 to 38), one block high, centred on the room's middle line. A clear row of
     * felt lies between it and the arrival row to its north, two between it and the card tables to its south, and two
     * columns between each end and the machines' mats on the side walls: it can be walked all the way round.
     *
     * <p>Its blocks are a plain dark-wood table and nothing else that is seen; the racetrack on it is drawn
     * ({@link RaceTable}), as the racers are. The block over the table is where they run: it is air to them and to
     * the eye, an invisible light block ({@link #TRACK_LIGHT}) so that the track and the racers are lit as brightly as
     * anything in the room, all over. An invisible barrier lies over that: nobody can step or jump onto the track, and
     * nothing is in the way of seeing it or of walking round it. Two lanterns already hang from the beam over its
     * north corners; two more hang on chains over its south ones.
     */
    private static void raceTable(Builder b) {
        for (int i = TRACK_X0; i < TRACK_X0 + RaceTrack.LENGTH; i++) {
            for (int k = TRACK_Z0; k < TRACK_Z0 + RaceTrack.DEPTH; k++) {
                b.set(ARCADE_X0 + i, UP_FLOOR + 1, k, TRACK_TABLE); // in place of the felt there
                b.set(ARCADE_X0 + i, UP_FLOOR + 2, k, TRACK_LIGHT);
                b.set(ARCADE_X0 + i, UP_FLOOR + 3, k, BARRIER);
            }
        }
        for (int i : new int[]{TRACK_X0, TRACK_X0 + RaceTrack.LENGTH - 1}) {
            b.set(ARCADE_X0 + i, UP_TOP, TRACK_Z0 + RaceTrack.DEPTH - 1, CHAIN);
            b.set(ARCADE_X0 + i, UP_TOP - 1, TRACK_Z0 + RaceTrack.DEPTH - 1, HANGING);
        }
    }

    /**
     * Mob Races' table: its north-west corner (x, z offsets), the block it is made of, the invisible light the racers
     * run in and the barrier over it.
     */
    static final int TRACK_X0 = 4;
    static final int TRACK_Z0 = MAIN_Z + 4;
    public static final String TRACK_TABLE = "minecraft:stripped_dark_oak_wood";
    public static final String TRACK_LIGHT = "minecraft:light[level=15,waterlogged=false]";
    public static final String BARRIER = "minecraft:barrier";

    /**
     * Where Mob Races' table stands in {@code box}: its north-west corner at the height of its top, running east
     * along its length and south across it (as if someone stood on that corner block facing east).
     */
    static RaceTable.Frame raceTable(Box box) {
        return new RaceTable.Frame(box.minX() + ARCADE_X0 + TRACK_X0, box.minY() + UP_FLOOR + 2,
                box.minZ() + TRACK_Z0, 1, 0, 0, 1);
    }

    /** Where the secret room's visitor arrives (its north end, facing in), and the door back out (on its north wall). */
    public static ClubhouseSite.Spot secretArrival(Box box) {
        return new ClubhouseSite.Spot(box.minX() + SECRET_CX + 0.5, box.minY() + UP_FLOOR + 1, box.minZ() + SIDE_Z0 + 2.5,
                0f);
    }

    public static Place3 secretExit(Box box) {
        return new Place3(box.minX() + SECRET_CX + 0.5, box.minY() + UP_FLOOR + 1, box.minZ() + SIDE_Z0 + 1.15, 0f);
    }

    /** The secret room's centre pedestal top (the visitor's head stands there). */
    public static Place3 secretCentre(Box box) {
        return new Place3(box.minX() + SECRET_CX + 0.5, box.minY() + UP_FLOOR + 2, box.minZ() + secretCz(box) + 0.5,
                180f);
    }

    /**
     * The secret room's trophy places, on its ledges (each a ledge top's middle, facing into the room): the lower row
     * round the three walls first, then the upper row.
     */
    public static List<Place3> secretShelf(Box box) {
        List<Place3> out = new ArrayList<>();
        for (int y : LEDGE_Y) {
            double top = box.minY() + y + 1;
            for (int k = SIDE_Z0 + 3; k <= z1(box) - 1; k += 2) {
                out.add(new Place3(box.minX() + TROPHY_X0 + 1.5, top, box.minZ() + k + 0.5, -90f));
            }
            for (int i = TROPHY_X0 + 3; i <= TROPHY_X0 + 12; i += 2) {
                out.add(new Place3(box.minX() + i + 0.5, top, box.minZ() + z1(box) + 0.5, 180f));
            }
            for (int k = z1(box) - 1; k >= SIDE_Z0 + 3; k -= 2) {
                out.add(new Place3(box.minX() + TROPHY_X0 + 14.5, top, box.minZ() + k + 0.5, 90f));
            }
        }
        return out;
    }

    /**
     * The way into the secret room (0.45.0, the owner: "Make the dark door the secret room and the star the casino"):
     * a dark-oak door standing against the Trophy Room's back wall, east of the podium, facing into the room; and
     * where its visitor returns. (0.43.0-0.44.2: a gold star on that wall.)
     */
    public static Place3 secretDoor(Box box) {
        return new Place3(box.minX() + TROPHY_X0 + 13, box.minY() + 1, box.minZ() + z1(box), 180f);
    }

    public static ClubhouseSite.Spot secretReturn(Box box) {
        return new ClubhouseSite.Spot(box.minX() + TROPHY_X0 + 13.5, box.minY() + 1, box.minZ() + z1(box) - 1.5, 0f);
    }

    /**
     * The Game Parlor's way in (0.52.1; the owner, after their four-year-old walked straight up to the lever and into the
     * Parlor: "lets make it much harder to find the secret parlor"): one book in the reading nook's wall of books, the
     * top row, second from its south end. Nothing marks it, and only a crouched tap opens it (a plain tap does
     * nothing), the way a secret passage opens when you know which book to pull. The place is the book block itself.
     * (0.50.0-0.52.0: a small lever beside the books; 0.45.0-0.49.0: a glowing gold star.)
     */
    public static Place3 parlorStar(Box box) {
        return new Place3(box.minX() + 1.5, box.minY() + PARLOR_BOOK[1], box.minZ() + PARLOR_BOOK[2] + 0.5, -90f);
    }

    /** The secret book (x, y, z offsets): the nook's top row, second from its south end. */
    static final int[] PARLOR_BOOK = {1, 3, 18};

    /** The reading nook's wall of books runs along the west wall from z 13 to here. */
    static final int NOOK_Z1 = 19;

    static final int PARLOR_DOOR_Z = 21;

    public static ClubhouseSite.Spot parlorReturn(Box box) {
        return new ClubhouseSite.Spot(box.minX() + 2.5, box.minY() + 1, box.minZ() + PARLOR_DOOR_Z + 0.5, -90f);
    }

    public static ClubhouseSite.Spot parlorArrival(Box box) {
        return new ClubhouseSite.Spot(box.minX() + ARCADE_X0 + 7.5, box.minY() + UP_FLOOR + 1, box.minZ() + SIDE_Z0 + 2.5,
                0f);
    }

    public static Place3 parlorExit(Box box) {
        return new Place3(box.minX() + ARCADE_X0 + 7.5, box.minY() + UP_FLOOR + 1, box.minZ() + SIDE_Z0 + 1.15, 0f);
    }

    /** The Scratch Ticket's booth (0.46.1): what a Parlor machine is called when it is the ticket's, not a game's. */
    public static final String TICKET = "ticket";
    /**
     * The booth's place (x, z, yaw): the middle of the Parlor's back wall, facing the room. No block is its own. Its z
     * is the grown wing's back row: the place is always the box's back row ({@link #z1}), as the slot machines' are.
     */
    static final int[] PARLOR_TICKET = {7, SIDE_Z1, 180};

    /** Where the Scratch Ticket's booth stands in the Game Parlor (0.46.1): a display on the floor, like a machine. */
    public static Place3 parlorTicket(Box box) {
        return new Place3(box.minX() + PARLOR_TICKET[0] + 0.5, box.minY() + UP_FLOOR + 1,
                box.minZ() + z1(box) + 0.5, PARLOR_TICKET[2]);
    }

    /**
     * The slot machines along the Parlor's back row (x, z, yaw): Nether Slots, Farm Slots and The Motherlode, then
     * Deep Mine (x 11) and Mob Slots (x 13), standing against the back wall and facing the room, the Scratch Ticket's
     * booth (x 7) between them. Like the booth, each is a display with NO block of its own, so adding them changes no
     * block and {@link #ALGO} stays as it is. New machines go at the END of this list: a machine's place in it is how
     * its display is kept.
     */
    static final int[][] PARLOR_ROW = {{3, SIDE_Z1, 180}, {5, SIDE_Z1, 180}, {9, SIDE_Z1, 180},
            {11, SIDE_Z1, 180}, {13, SIDE_Z1, 180}};
    /** The games those stand for, in order. Each is drawn only while its game is there and open. */
    public static final List<String> PARLOR_ROW_GAMES = List.of("nether_slots", "farm_slots", "motherlode",
            "deep_mine", "mob_slots");

    /** Where the back row's slot machines stand in the Game Parlor: {@link #PARLOR_ROW_GAMES}' places. */
    public static List<Place3> parlorRow(Box box) {
        List<Place3> out = new ArrayList<>();
        for (int[] m : PARLOR_ROW) {
            out.add(new Place3(box.minX() + ARCADE_X0 + m[0] + 0.5, box.minY() + UP_FLOOR + 1,
                    box.minZ() + z1(box) + 0.5, m[2]));
        }
        return out;
    }

    /** The Game Parlor's machines: {@link #PARLOR_GAMES}' places, the walls' four then the two tables' tops. */
    public static List<Place3> parlorMachines(Box box) {
        List<Place3> out = new ArrayList<>();
        for (int[] m : PARLOR_WALL) {
            out.add(new Place3(box.minX() + m[0] + 0.5, box.minY() + UP_FLOOR + 1, box.minZ() + m[1] + 0.5, m[2]));
        }
        for (int[] t : PARLOR_TABLE) { // the cards lie in the middle of the table
            out.add(new Place3(box.minX() + t[0] + PARLOR_TABLE_W / 2.0, box.minY() + UP_FLOOR + 2,
                    box.minZ() + t[1] + PARLOR_TABLE_D / 2.0, 0f));
        }
        return out;
    }

    /**
     * A big board on a Parlor wall that a game is played at (plan 13): its game, its place (the middle of the board's
     * foot, on the floor against the wall, facing the room, as a machine's place is), and its size in blocks (along the
     * wall, and up). {@code SideRooms} draws it from this alone: the game's model if there is one, a plain dark board
     * if not, and a tap on it opens {@code machine:<game>}.
     */
    public record Board(String game, Place3 at, int width, int height) {
    }

    /** The games with a board on a Parlor wall, in order (new ones at the END, as the back row's). */
    public static final List<String> PARLOR_BOARD_GAMES = List.of(SPORTSBOOK_GAME);

    /**
     * The Parlor's wall boards in {@code box} (plan 13): the sportsbook's on the east wall. None in a wing kept at its
     * old size (there is no room for one there).
     */
    public static List<Board> parlorBoards(Box box) {
        if (!grown(box)) {
            return List.of();
        }
        return List.of(new Board(SPORTSBOOK_GAME, new Place3(box.minX() + ARCADE_X0 + SPORTSBOOK[0] + 0.5,
                box.minY() + UP_FLOOR + 1, box.minZ() + SPORTSBOOK[1] + 0.5, SPORTSBOOK[2]), BOARD_W, BOARD_H));
    }

    /**
     * Where "Photo time!" stands on the Trophy Room's podium (0.42.0): 1st, 2nd, 3rd, each in the middle of its
     * 2 x 1 plinth (its cup and head are taken down for the photo), facing the archway.
     */
    static List<ClubhouseSite.Spot> roomPodium(Box box) {
        List<ClubhouseSite.Spot> out = new ArrayList<>();
        for (int n = 0; n < 3; n++) {
            out.add(new ClubhouseSite.Spot(box.minX() + ROOM_PODIUM_X[n] + 1.0, box.minY() + ROOM_PODIUM_HEIGHT[n] + 1,
                    box.minZ() + podiumZ(box) + 0.5, 180f));
        }
        return out;
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

    /**
     * The Arcade Room's cabinet places in {@code box} (0.41.0), in the order the kid set fills them: each the middle of
     * its mat at the floor's top, facing into the room. Plan 13's grown wing has {@value #CABINETS} (four of them
     * spares, after the nine there were); any other box the first {@value #OLD_CABINETS}.
     */
    public static List<Place3> cabinets(Box box) {
        List<Place3> out = new ArrayList<>();
        int places = grown(box) ? CABINETS : OLD_CABINETS;
        for (int n = 0; n < places; n++) {
            int[] c = CABINET_AT[n];
            out.add(new Place3(box.minX() + c[0] + 0.5, box.minY() + 1, box.minZ() + c[1] + 0.5, c[2]));
        }
        return out;
    }

    /**
     * The Trophy Room's pedestals in {@code box} (0.41.0): the podium's three (1st, 2nd, 3rd, facing north to the
     * archway) and then the {@value #RECORD_PEDESTALS} record pedestals (the west wall's north to south facing east,
     * then the east wall's facing west).
     */
    public static List<Pedestal> pedestals(Box box) {
        List<Pedestal> out = new ArrayList<>();
        for (int n = 0; n < 3; n++) {
            double y = box.minY() + ROOM_PODIUM_HEIGHT[n] + 1;
            double z = box.minZ() + podiumZ(box) + 0.5;
            out.add(new Pedestal(new Place3(box.minX() + ROOM_PODIUM_X[n] + 0.5, y, z, 180f),
                    new Place3(box.minX() + ROOM_PODIUM_X[n] + 1.5, y, z, 180f)));
        }
        for (int[] side : new int[][]{{RECORD_WEST_X, -90}, {RECORD_EAST_X, 90}}) {
            for (int z : RECORD_Z) {
                double x = box.minX() + side[0] + 0.5;
                out.add(new Pedestal(new Place3(x, box.minY() + 2, box.minZ() + z + 0.5, side[1]),
                        new Place3(x, box.minY() + 2, box.minZ() + z + 1.5, side[1])));
            }
        }
        return out;
    }

    /**
     * Where the sign over an archway hangs in {@code box} (0.41.0): the model's middle, a little out from the Foyer's
     * south wall over the archway, facing north into the Foyer.
     */
    public static Place3 door(Box box, boolean arcade) {
        int x0 = arcade ? ARCH_ARCADE_X0 : ARCH_TROPHY_X0;
        return new Place3(box.minX() + x0 + ARCH_W / 2.0, box.minY() + DOOR_Y, box.minZ() + MAIN_Z - 1 - DOOR_OUT,
                180f);
    }

    /** The door sign's middle: this high over the floor's layer, and this far out from the wall's face. */
    static final double DOOR_Y = 5.25;
    static final double DOOR_OUT = 0.75;

    /**
     * A piece of decoration that isn't a block (0.42.0): an item or a block drawn by a display, nothing to take or
     * break (ClubDecor draws them).
     *
     * @param key   a stable name ("bar.bottle.3")
     * @param what  the item's or block's material name ({@code GLASS_BOTTLE}, {@code RED_WOOL})
     * @param block a block display (its corner at x, y, z) rather than an item display (its middle at x, y, z)
     * @param look  for an item: {@code face} (turns to whoever looks), {@code flat} (lying on the table), {@code
     *              wall} (flat against the wall, facing {@code yaw}), {@code lean} (leaning, facing {@code yaw}), {@code
     *              ball} (plan 13: turned wholly to whoever looks, from above too)
     */
    public record Prop(String key, String what, boolean block, double x, double y, double z, float yaw, float sx,
                       float sy, float sz, String look) {

        static Prop item(String key, String what, double x, double y, double z, float yaw, float size, String look) {
            return new Prop(key, what, false, x, y, z, yaw, size, size, size, look);
        }
    }

    /**
     * The room's decoration (0.42.0) in {@code box} with {@code rooms}: the Feast Pot on the west 3 x 3 table's cloth
     * with bread and a pie beside it; bottles on the back bar and glasses on the counter; a big record over the
     * jukebox's alcove and a record on each shelf beside it; the putting green's flag, ball and putter; and, with the
     * Arcade Room, prizes in its counter's glass cases and its claw machines.
     */
    public static List<Prop> decor(Box box, Rooms rooms) {
        List<Prop> out = new ArrayList<>();
        double x0 = box.minX();
        double y0 = box.minY();
        double z0 = box.minZ();
        // the Feast Pot: a small pot of food on the cloth (its bottom on the cloth's top), bread and a pie by it
        out.add(Prop.item("feast.pot", "CAULDRON", x0 + POT_X + 0.5, y0 + 2 + POT_SIZE / 2, z0 + POT_Z + 0.5, 0f,
                POT_SIZE, "fixed"));
        out.add(Prop.item("feast.bread", "BREAD", x0 + POT_X - 0.1, y0 + 2.02, z0 + POT_Z + 1.2, 0f, 0.4f, "flat"));
        out.add(Prop.item("feast.pie", "PUMPKIN_PIE", x0 + POT_X + 1.1, y0 + 2.02, z0 + POT_Z - 0.2, 0f, 0.4f, "flat"));
        // the back bar's bottles stand ON its shelves (0.44.2: the shelves' own items, ClubDecor.stockBar); glasses
        // on the counter
        for (int k : new int[]{3, 6, 9}) {
            out.add(Prop.item("bar.glass." + k, "GLASS_BOTTLE", x0 + 4.5, y0 + 2.2, z0 + k + 0.5, -90f, 0.4f, "face"));
        }
        // the jukebox: a big record over its alcove, a record on each shelf beside it
        out.add(Prop.item("jukebox.sign", "MUSIC_DISC_CAT", x0 + JUKEBOX_X + 1.04, y0 + JUKEBOX_Y + 2.5,
                z0 + JUKEBOX_Z + 0.5, -90f, 1.3f, "wall"));
        out.add(Prop.item("jukebox.shelf.0", "MUSIC_DISC_13", x0 + JUKEBOX_X + 1.04, y0 + JUKEBOX_Y + 0.5,
                z0 + JUKEBOX_Z - 0.5, -90f, 0.6f, "wall"));
        out.add(Prop.item("jukebox.shelf.1", "MUSIC_DISC_BLOCKS", x0 + JUKEBOX_X + 1.04, y0 + JUKEBOX_Y + 0.5,
                z0 + JUKEBOX_Z + 1.5, -90f, 0.6f, "wall"));
        // the putting green: a small red flag on the pole, a ball near the cup, a putter by the edge
        out.add(new Prop("green.flag", "RED_WOOL", true, x0 + GREEN_CUP_X + 0.5, y0 + 2.55, z0 + GREEN_CUP_Z + 0.48, 0f,
                0.55f, 0.32f, 0.04f, "block"));
        out.add(Prop.item("green.ball", "SNOWBALL", x0 + GREEN_CUP_X - 0.7, y0 + 1.18, z0 + GREEN_CUP_Z + 0.9, 0f,
                0.3f, "face"));
        out.add(Prop.item("green.putter", "IRON_HOE", x0 + 29.6, y0 + 1.55, z0 + 1.8, 90f, 0.9f, "lean"));
        if (rooms != null && rooms.arcade() && box.sizeZ() > MAIN_Z) {
            int back = z1(box);
            String[] prizes = {"CAKE", "GOLDEN_APPLE", "DIAMOND", "TOTEM_OF_UNDYING", "MUSIC_DISC_PIGSTEP",
                    "FIREWORK_ROCKET"};
            for (int i = PRIZE_X0; i <= PRIZE_X1; i++) {
                out.add(Prop.item("arcade.prize." + i, prizes[(i - PRIZE_X0) % prizes.length], x0 + i + 0.5,
                        y0 + 2.5, z0 + back + 0.5, 0f, 0.55f, "face"));
            }
            String[] plush = {"SLIME_BALL", "COOKIE", "EMERALD", "PUFFERFISH"};
            int[] claws = clawX(box);
            for (int c = 0; c < claws.length; c++) {
                for (int m = 0; m < 3; m++) {
                    out.add(Prop.item("arcade.claw." + c + "." + m, plush[(c + m) % plush.length],
                            x0 + claws[c] + 0.3 + m * 0.2, y0 + 2.2 + (m % 2) * 0.15, z0 + back + 0.35 + m * 0.15,
                            0f, 0.35f, "face"));
                }
                out.add(Prop.item("arcade.claw." + c + ".hook", "CHAIN", x0 + claws[c] + 0.5, y0 + 3.5,
                        z0 + back + 0.5, 0f, 0.6f, "face"));
            }
            if (grown(box)) {
                ballPit(out, box);
                beanBags(out, box);
                plushCase(out, box);
            } else {
                // plan 11: the plush case's plushies, two to a glass case
                String[] plushies = {"CREEPER_HEAD", "PIGLIN_HEAD", "ZOMBIE_HEAD", "DRAGON_HEAD"};
                for (int c = 0; c < PLUSH_X.length; c++) {
                    for (int m = 0; m < 2; m++) {
                        out.add(Prop.item("arcade.plush." + c + "." + m, plushies[(2 * c + m) % plushies.length],
                                x0 + ARCADE_X0 + PLUSH_X[c] + 0.3 + 0.4 * m, y0 + 2.35, z0 + back + 0.5, 0f, 0.4f,
                                "face"));
                    }
                }
            }
        }
        return out;
    }

    /** Plan 13: what the balls in the pit are (round, bright items, each drawn turned to whoever looks). */
    static final String[] BALLS = {"SNOWBALL", "SLIME_BALL", "MAGMA_CREAM", "HEART_OF_THE_SEA", "CHORUS_FRUIT",
            "APPLE", "ENDER_PEARL"};
    /** A ball's size (its display's scale), and where the four of each block lie in it (x, z, a layer). */
    static final float BALL_SIZE = 0.5f;
    static final double[][] BALL_AT = {{0.27, 0.3, 0}, {0.74, 0.27, 1}, {0.3, 0.73, 1}, {0.72, 0.7, 0}};
    /** The height of each layer of balls over the pit's floor block (its carpet is a sixteenth). */
    static final double[] BALL_LAYER = {0.24, 0.44, 0.64};

    /**
     * Plan 13, the ball pit's balls: four over each block of its floor, two layers deep,
     * and a third layer heaped in its middle, every colour mixed. Item displays turned to whoever looks, so each
     * reads as a ball from any side.
     */
    private static void ballPit(List<Prop> out, Box box) {
        int n = 0;
        for (int i = PIT_X0 + 1; i <= PIT_X1 - 1; i++) {
            for (int k = PIT_Z0 + 1; k <= PIT_Z1 - 1; k++) {
                boolean middle = Math.abs(i - (PIT_X0 + PIT_X1) / 2) <= 1 && Math.abs(k - (PIT_Z0 + PIT_Z1) / 2) <= 1;
                for (int m = 0; m < BALL_AT.length; m++) {
                    double[] at = BALL_AT[m];
                    int layer = (int) at[2];
                    String what = BALLS[Math.floorMod(i * 3 + k * 5 + m * 2, BALLS.length)];
                    out.add(Prop.item("arcade.ball." + n++, what, box.minX() + ARCADE_X0 + i + at[0],
                            box.minY() + 1 + BALL_LAYER[layer], box.minZ() + k + at[1], 0f, BALL_SIZE, "ball"));
                }
                if (middle) { // the heap in the middle
                    String what = BALLS[Math.floorMod(i + k, BALLS.length)];
                    out.add(Prop.item("arcade.ball." + n++, what, box.minX() + ARCADE_X0 + i + 0.5,
                            box.minY() + 1 + BALL_LAYER[2], box.minZ() + k + 0.5, 0f, BALL_SIZE, "ball"));
                }
            }
        }
    }

    /**
     * Plan 13, the bean bags' soft sides: each bag's stair wrapped in its wool, a little wider than its block so it
     * bulges (its seat's top left showing for the cushion and the sitter), a padded back, and a rolled top on the back.
     */
    private static void beanBags(List<Prop> out, Box box) {
        for (int n = 0; n < BEAN_BAGS.length; n++) {
            int[] bag = BEAN_BAGS[n];
            String wool = BAG_WOOL[bag[2]];
            double x = box.minX() + ARCADE_X0 + bag[0];
            double y = box.minY() + 1;
            double z = box.minZ() + bag[1];
            out.add(new Prop("arcade.bag." + n + ".base", wool, true, x - 0.06, y, z - 0.06, 0f, 1.12f, 0.42f, 1.12f,
                    "block"));
            // its back: the half of the block away from the table (the stair's "facing" side)
            double bx = bag[0] < BEAN_TABLE[0] ? x - 0.06 : bag[0] > BEAN_TABLE[0] ? x + 0.46 : x - 0.06;
            double bz = bag[1] < BEAN_TABLE[1] ? z - 0.06 : bag[1] > BEAN_TABLE[1] ? z + 0.46 : z - 0.06;
            boolean alongX = bag[0] != BEAN_TABLE[0]; // a bag west or east of the table has its back along z
            float sx = alongX ? 0.6f : 1.12f;
            float sz = alongX ? 1.12f : 0.6f;
            out.add(new Prop("arcade.bag." + n + ".back", wool, true, bx, y + 0.42, bz, 0f, sx, 0.5f, sz, "block"));
            out.add(new Prop("arcade.bag." + n + ".roll", wool, true, bx + (alongX ? 0.06 : 0.1), y + 0.92,
                    bz + (alongX ? 0.1 : 0.06), 0f, alongX ? 0.48f : 0.92f, 0.12f, alongX ? 0.92f : 0.48f, "block"));
        }
    }

    /** Plan 13: the plush case's toys, below (mob heads) and above (soft toys), and their ribbons' colours. */
    static final String[] PLUSH_LOW = {"CREEPER_HEAD", "PIGLIN_HEAD", "ZOMBIE_HEAD", "DRAGON_HEAD", "SKELETON_SKULL",
            "PLAYER_HEAD"};
    static final String[] PLUSH_HIGH = {"PUFFERFISH", "TROPICAL_FISH", "TURTLE_EGG", "RABBIT_FOOT", "SLIME_BALL",
            "COOKIE", "HONEYCOMB", "CAKE"};
    static final String[] RIBBONS = {"BLUE_WOOL", "RED_WOOL", "YELLOW_WOOL", "LIME_WOOL", "MAGENTA_WOOL"};

    /**
     * Plan 13, the prize-and-plush case's insides: in each of its glass blocks a mob-head plush on the base, a thin
     * white shelf a block up with a soft toy on it, and a ribbon of its prize colour along the base's front edge; on
     * the counter, price tags lying flat and a bell.
     */
    private static void plushCase(List<Prop> out, Box box) {
        double x0 = box.minX() + ARCADE_X0;
        double y0 = box.minY();
        double z0 = box.minZ();
        for (int n = 0; n < PLUSH_CASE.length; n++) {
            int[] c = PLUSH_CASE[n];
            boolean side = c[0] == 14 && c[1] < PLUSH_CASE[PLUSH_CASE.length - 1][1]; // on the east wall, facing west
            float yaw = side ? 90f : 180f;
            double cx = x0 + c[0] + 0.5;
            double cz = z0 + c[1] + 0.5;
            out.add(Prop.item("arcade.plush." + n + ".low", PLUSH_LOW[n % PLUSH_LOW.length], cx, y0 + 2.25, cz, yaw,
                    0.5f, "face"));
            out.add(new Prop("arcade.shelf." + n, "WHITE_CONCRETE", true, x0 + c[0] + 0.04, y0 + 3.0, z0 + c[1] + 0.04,
                    0f, 0.92f, 0.05f, 0.92f, "block"));
            out.add(Prop.item("arcade.plush." + n + ".high", PLUSH_HIGH[n % PLUSH_HIGH.length], cx, y0 + 3.3, cz, yaw,
                    0.45f, "face"));
            // the ribbon along the front edge of the base: the west face on the east wall, the north face at the back
            double rx = side ? x0 + c[0] + 0.02 : x0 + c[0] + 0.05;
            double rz = side ? z0 + c[1] + 0.05 : z0 + c[1] + 0.02;
            out.add(new Prop("arcade.ribbon." + n, RIBBONS[n % RIBBONS.length], true, rx, y0 + 2.0, rz, 0f,
                    side ? 0.05f : 0.9f, 0.08f, side ? 0.9f : 0.05f, "block"));
        }
        double top = y0 + 2.02;
        double cz = z0 + PLUSH_COUNTER_Z + 0.5;
        out.add(Prop.item("arcade.tag.0", "NAME_TAG", x0 + PLUSH_COUNTER_X[0] + 0.45, top, cz - 0.1, 30f, 0.4f, "flat"));
        out.add(Prop.item("arcade.tag.1", "NAME_TAG", x0 + PLUSH_COUNTER_X[0] + 0.85, top, cz + 0.15, -20f, 0.4f,
                "flat"));
        out.add(Prop.item("arcade.bell", "BELL", x0 + PLUSH_COUNTER_X[1] + 0.5, y0 + 2.2, cz, 180f, 0.45f, "fixed"));
    }

    /** The back bar's shelves in {@code box} (0.44.2), {x, y, z} each: two rows along the bar's wall. */
    public static List<int[]> barShelves(Box box) {
        List<int[]> out = new ArrayList<>();
        for (int k = 2; k <= 10; k++) {
            for (int j = 2; j <= 3; j++) {
                out.add(new int[]{box.minX() + 1, box.minY() + j, box.minZ() + k});
            }
        }
        return out;
    }

    /** The Feast Pot's size on the cloth (blocks). */
    static final float POT_SIZE = 0.6f;

    /**
     * The planner's regions in {@code box} with {@code rooms} (0.42.0), by name: what a save says it covers, so a room
     * added in a later release (a region the save doesn't name) is built from HCM's plan into the owner's saved room
     * ({@link ClubSave#merged}). The main room, then each side room's half of the wing (16 wide, its whole depth).
     */
    public static Map<String, Box> regions(Box box, Rooms rooms) {
        Map<String, Box> out = new java.util.LinkedHashMap<>();
        out.put("main", new Box(box.minX(), box.minY(), box.minZ(), box.minX() + 31, box.maxY(), box.minZ() + MAIN_Z - 1));
        if (box.sizeZ() > MAIN_Z) {
            if (rooms.arcade()) {
                out.put("arcade", new Box(box.minX() + ARCADE_X0, box.minY(), box.minZ() + MAIN_Z,
                        box.minX() + ARCADE_X0 + 15, box.minY() + SIDE_ROOF, box.maxZ()));
            }
            if (rooms.trophy()) {
                out.put("trophy", new Box(box.minX() + TROPHY_X0, box.minY(), box.minZ() + MAIN_Z,
                        box.minX() + TROPHY_X0 + 15, box.minY() + SIDE_ROOF, box.maxZ()));
            }
            if (rooms.secret()) {
                out.put("secret", new Box(box.minX() + TROPHY_X0, box.minY() + UP_FLOOR, box.minZ() + MAIN_Z,
                        box.minX() + TROPHY_X0 + 15, box.maxY(), box.maxZ()));
            }
            if (rooms.parlor()) {
                out.put("parlor", new Box(box.minX() + ARCADE_X0, box.minY() + UP_FLOOR, box.minZ() + MAIN_Z,
                        box.minX() + ARCADE_X0 + 15, box.maxY(), box.maxZ()));
            }
        }
        return out;
    }

    /** Where the cushions go in {@code box} (26.3): one on every chair, stool and armchair. */
    public static List<Seat> seats(Box box) {
        Builder b = new Builder(box);
        hall(b);
        bar(b);
        gallery(b);
        return b.seats;
    }

    /**
     * The Game Parlor's chairs' cushions (0.50.0; the owner: "put some cushions on these ... seats would ya"): the card
     * tables' chairs, only while the Parlor is built ({@link #seats} is the rooms downstairs).
     */
    public static List<Seat> parlorSeats(Box box) {
        Builder b = new Builder(box);
        parlor(b);
        return b.seats; // the card tables' first (Twenty-One's four, Higher or Lower's), then the lounge's, the stools
    }

    /**
     * Plan 13: the Arcade Room's seats' cushions, only while it is built: the kid race's viewing stools and the bean
     * bags. None in a wing at its old size.
     */
    public static List<Seat> arcadeSeats(Box box) {
        Builder b = new Builder(box);
        arcadeRoom(b);
        return b.seats;
    }

    /** Plan 13: the Trophy Room's armchairs' cushions, only while it is built. None in a wing at its old size. */
    public static List<Seat> trophySeats(Box box) {
        Builder b = new Builder(box);
        trophyRoom(b);
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
        /** The side rooms' last row of air (z offset), and whether the wing is plan 13's grown one. */
        final int z1;
        final boolean big;

        Builder(Box box) {
            this.box = box;
            this.z1 = z1(box);
            this.big = grown(box);
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

        /**
         * A seat block, and a cushion's place on it (0.41.1): centred on the block, on the seat's top (half a block up),
         * facing the way the sitter faces. The 26.3 cushion is about a block wide and centred on its position, so 0.40.0's
         * quarter-block nudge toward the seat's front left it hanging over the edge ("a bit wonky"); centred, its whole
         * footprint is on its seat block ({@link #CUSHION_WIDTH}).
         */
        void seat(int i, int j, int k, String block, String color) {
            set(i, j, k, block);
            float yaw = 90f; // a stool: facing the bar, west
            if (block.contains("_stairs")) {
                String f = block.substring(block.indexOf("facing=") + 7);
                f = f.substring(0, f.indexOf(','));
                yaw = switch (f) {
                    case "north" -> 0f;
                    case "south" -> 180f;
                    case "east" -> 90f;
                    default -> -90f;
                };
            }
            seats.add(new Seat(box.minX() + i + 0.5, box.minY() + j + 0.5, box.minZ() + k + 0.5, yaw, color));
        }

        /** A stool facing {@code yaw} (plan 13: the kid race's viewing stools, the sportsbook's). */
        void seat(int i, int j, int k, String block, String color, float yaw) {
            set(i, j, k, block);
            seats.add(new Seat(box.minX() + i + 0.5, box.minY() + j + 0.5, box.minZ() + k + 0.5, yaw, color));
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
            "minecraft:stripped_dark_oak_log", "minecraft:glass", ARCADE_FLOOR, MAT,
            "minecraft:quartz_pillar", "minecraft:green_wool", "minecraft:brown_wool", "minecraft:red_wool",
            "minecraft:ochre_froglight", "minecraft:verdant_froglight", "minecraft:pearlescent_froglight", CLAW,
            "minecraft:cyan_concrete", "minecraft:lime_concrete", "minecraft:orange_concrete",
            "minecraft:light_blue_concrete", "minecraft:lime_wool", "minecraft:light_blue_wool", "minecraft:pink_wool",
            "minecraft:yellow_wool", "minecraft:shroomlight", "minecraft:blue_wool", "minecraft:black_wool");
}
