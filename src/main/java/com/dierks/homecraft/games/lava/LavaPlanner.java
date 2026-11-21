package com.dierks.homecraft.games.lava;

import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Plan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Makes The Floor Is Lava's playroom: one closed room in the sky, 32 x 16 x 32, built so big that the players are
 * toy-sized in it (the owner liked: "a giant playroom where you are toy-sized, with sofas, cushions, a coffee table
 * and bookshelves to climb on").
 *
 * <p><b>What is in it</b> (offsets from the box's corner; the floor block is layer 0, feet on it are at 1):
 * <ul>
 *   <li><b>The sofa</b> against the north wall: a footstool in front of it (feet at 2), its seat (3), its arms (4)
 *       and its back (6), each joined to the next by half-block steps. Players arrive on the seat.</li>
 *   <li><b>The coffee table</b> in the middle (4), with a stack of books beside it as its steps; you can walk
 *       under it.</li>
 *   <li><b>The building blocks</b> to the east: four toy cubes, 2, 3, 4 and 5 high.</li>
 *   <li><b>The toy chest</b> in the south-east corner (5), with a staircase of blocks up its side.</li>
 *   <li><b>The bookcase</b> on the west wall: two shelves to walk along (4 and 7) and a long staircase of books
 *       up its front. Its top shelf (10) is glassed in: the gallery, where Challenge's watchers stand. Nothing
 *       leads to it.</li>
 *   <li>Two floor cushions, a rug (in the floor itself), a big window in the east wall with real daylight, and
 *       warm lamps in the floor, the walls, the ceiling and the furniture.</li>
 * </ul>
 *
 * <p><b>Made for a four-year-old with a controller.</b> Every piece is reached by steps half a block high (a slab,
 * then a whole block), two blocks wide or more, so nothing needs a jump; the tops are wide and flat. There are
 * seven heights of ground, so the low pieces go under first and the high ones stay dry for rounds more.
 *
 * <p><b>Nothing here burns.</b> Every block is stone, concrete, terracotta, quartz, prismarine, copper, glass or a
 * lamp ({@link #FIREPROOF}); no wood, wool, carpet or bookshelf. Everything is a whole block or a bottom slab, so the
 * lava (source blocks the game places, never flowing) meets nothing it could wash away, and {@link LavaSite} can
 * tell exactly where a player may stand.
 *
 * <p><b>Why it stays mob free.</b> As the Clubhouse: every block a mob could stand on has block light over it
 * ({@code LavaPlannerTest} floods the plan's light), and the roof is glass. No way out: the walls go up to the roof.
 *
 * <p>Pure and deterministic: the same box always gives the same plan (its {@link Plan#hash} is pinned by a test).
 */
public final class LavaPlanner {

    /** The planner's version: part of the plan, so a change of the room is a change of plan. */
    public static final int ALGO = 1;
    /** The plan's "slot" name. */
    public static final String SLOT = "floor_is_lava";

    public static final int SIZE_X = 32;
    public static final int SIZE_Y = 16;
    public static final int SIZE_Z = 32;
    /** The roof's layer; the walls stand under it. */
    public static final int ROOF = 15;
    /**
     * The lava level that covers every piece of the play area: its highest ground is the bookcase's second shelf,
     * feet at {@value}. Challenge ends there at the latest ({@code LavaPlannerTest} holds it to the plan).
     */
    public static final int FLOOD = 7;
    /** The highest lava with dry ground left for everyone: Group's lava never comes higher. */
    public static final int SAFE_TOP = FLOOD - 1;

    // ---- the blocks --------------------------------------------------------------------------------

    public static final String FLOOR = "minecraft:smooth_sandstone";
    public static final String SKIRTING = "minecraft:orange_terracotta";
    public static final String WALL = "minecraft:white_terracotta";
    public static final String ROOF_GLASS = "minecraft:white_stained_glass";
    /** The warm lamp: in the floor, the walls, the roof and the furniture. */
    public static final String LAMP = "minecraft:ochre_froglight[axis=y]";
    /** The sofa's buttons. */
    public static final String BUTTON = "minecraft:sea_lantern";
    public static final String WINDOW = "minecraft:glass";
    public static final String FRAME = "minecraft:smooth_quartz";
    static final String[] RUG = {"minecraft:red_concrete", "minecraft:white_concrete", "minecraft:light_blue_concrete"};
    public static final String SOFA = "minecraft:prismarine_bricks";
    public static final String SOFA_DARK = "minecraft:dark_prismarine";
    public static final String SOFA_SLAB = "minecraft:prismarine_brick_slab[type=bottom,waterlogged=false]";
    public static final String SOFA_DARK_SLAB = "minecraft:dark_prismarine_slab[type=bottom,waterlogged=false]";
    public static final String TABLE = "minecraft:smooth_quartz";
    public static final String LEG = "minecraft:quartz_pillar[axis=y]";
    /** A book's pages, and every half step on a toy block: a white bottom slab. */
    public static final String PAGE = "minecraft:smooth_quartz_slab[type=bottom,waterlogged=false]";
    static final String[] TOYS = {"minecraft:red_concrete", "minecraft:blue_concrete", "minecraft:yellow_concrete",
            "minecraft:lime_concrete", "minecraft:orange_concrete", "minecraft:light_blue_concrete"};
    public static final String SHELF = "minecraft:brown_concrete";
    static final String[] BOOKS = {"minecraft:red_terracotta", "minecraft:blue_terracotta",
            "minecraft:green_terracotta", "minecraft:yellow_terracotta", "minecraft:cyan_terracotta",
            "minecraft:orange_terracotta"};
    public static final String CHEST = "minecraft:waxed_cut_copper";
    public static final String CHEST_LID = "minecraft:waxed_copper_block";
    public static final String LATCH = "minecraft:gold_block";
    public static final String RAIL = "minecraft:glass";
    public static final String CUSHION_A = "minecraft:smooth_red_sandstone_slab[type=bottom,waterlogged=false]";
    public static final String CUSHION_B = "minecraft:dark_prismarine_slab[type=bottom,waterlogged=false]";

    /**
     * Every block id the room is built of. None of them burns or is set alight by lava (stone, concrete, terracotta,
     * quartz, prismarine, sandstone, copper, gold, glass and lamps; no wood, wool, carpet, leaves or bookshelves):
     * pinned by {@code LavaPlannerTest}, so adding a block is a decision.
     */
    public static final Set<String> FIREPROOF = Set.of("minecraft:smooth_sandstone", "minecraft:orange_terracotta",
            "minecraft:white_terracotta", "minecraft:white_stained_glass", "minecraft:ochre_froglight",
            "minecraft:sea_lantern", "minecraft:glass", "minecraft:smooth_quartz", "minecraft:red_concrete",
            "minecraft:white_concrete", "minecraft:light_blue_concrete", "minecraft:prismarine_bricks",
            "minecraft:dark_prismarine", "minecraft:prismarine_brick_slab", "minecraft:dark_prismarine_slab",
            "minecraft:quartz_pillar", "minecraft:smooth_quartz_slab", "minecraft:blue_concrete",
            "minecraft:yellow_concrete", "minecraft:lime_concrete", "minecraft:orange_concrete",
            "minecraft:brown_concrete", "minecraft:red_terracotta", "minecraft:blue_terracotta",
            "minecraft:green_terracotta", "minecraft:yellow_terracotta", "minecraft:cyan_terracotta",
            "minecraft:waxed_cut_copper", "minecraft:waxed_copper_block", "minecraft:gold_block",
            "minecraft:smooth_red_sandstone_slab");

    // ---- where things are (offsets from the box's corner) ---------------------------------------------

    /** The floor's and the walls' lamps: every fifth block, from the third. */
    static final int LAMP_EVERY = 5;
    static final int LAMP_FROM = 3;
    /** The wall lamps' heights. */
    static final int[] WALL_LAMP_Y = {4, 9, 13};
    /** The window in the east wall: along z, and up. */
    static final int WINDOW_Z0 = 9;
    static final int WINDOW_Z1 = 22;
    static final int WINDOW_Y0 = 4;
    static final int WINDOW_Y1 = 12;
    /** The rug, in the floor's own layer. */
    static final int RUG_X0 = 7;
    static final int RUG_X1 = 24;
    static final int RUG_Z0 = 11;
    static final int RUG_Z1 = 22;
    /** The sofa: its arms' outer columns, its seat between them. */
    static final int SOFA_X0 = 6;
    static final int SOFA_X1 = 19;
    /** Where players arrive: on the sofa's seat (x, z offsets), feet at {@value #SEAT_FEET}. */
    static final int[][] SPAWN_AT = {{9, 4}, {11, 4}, {13, 4}, {16, 4}, {9, 5}, {12, 5}, {14, 5}, {16, 5}};
    static final int SEAT_FEET = 3;
    /** The coffee table. */
    static final int TABLE_X0 = 12;
    static final int TABLE_X1 = 19;
    static final int TABLE_Z0 = 14;
    static final int TABLE_Z1 = 19;
    static final int TABLE_TOP = 3;
    /** The toy chest. */
    static final int CHEST_X0 = 23;
    static final int CHEST_Z0 = 24;
    static final int CHEST_TOP = 4;
    /** The bookcase on the west wall: along z, its shelves' boards, and its gallery. */
    static final int CASE_Z0 = 8;
    static final int CASE_Z1 = 27;
    static final int[] SHELF_Y = {3, 6};
    static final int GALLERY_BOARD = 9;
    static final int CASE_TOP = 12;
    /** The gallery's feet, and where its watchers stand (x offset; along z from {@code CASE_Z0 + 2}). */
    static final int GALLERY_FEET = GALLERY_BOARD + 1;
    static final int GALLERY_X = 3;
    static final int GALLERY_SPOTS = 16;

    private LavaPlanner() {
    }

    /**
     * The playroom in {@code box}.
     *
     * @throws IllegalArgumentException when the box isn't the room's size
     */
    public static LavaSite plan(Box box) {
        if (box == null || box.sizeX() != SIZE_X || box.sizeY() != SIZE_Y || box.sizeZ() != SIZE_Z) {
            throw new IllegalArgumentException("the playroom's box is " + SIZE_X + " x " + SIZE_Y + " x " + SIZE_Z
                    + ", not " + (box == null ? "missing" : box.sizeX() + " x " + box.sizeY() + " x " + box.sizeZ()));
        }
        Builder b = new Builder(box);
        shell(b);
        rug(b);
        sofa(b);
        table(b);
        toyBlocks(b);
        chest(b);
        bookcase(b);
        cushions(b);

        List<LavaSite.Spot> spawns = new ArrayList<>();
        for (int[] at : SPAWN_AT) {
            spawns.add(new LavaSite.Spot(box.minX() + at[0] + 0.5, box.minY() + SEAT_FEET, box.minZ() + at[1] + 0.5, 0f));
        }
        List<LavaSite.Spot> gallery = new ArrayList<>();
        for (int n = 0; n < GALLERY_SPOTS; n++) {
            gallery.add(new LavaSite.Spot(box.minX() + GALLERY_X + 0.5, box.minY() + GALLERY_FEET,
                    box.minZ() + CASE_Z0 + 2 + n + 0.5, -90f));
        }
        Box galleryBox = new Box(box.minX() + 2, box.minY() + GALLERY_FEET, box.minZ() + CASE_Z0 + 1,
                box.minX() + 4, box.minY() + GALLERY_FEET + 1, box.minZ() + CASE_Z1 - 1);
        List<String> summary = List.of("the playroom: " + box.describe(), spawns.size() + " arrival spots on the"
                + " sofa, a coffee table, toy blocks, a toy chest and a bookcase to climb; lava up to " + FLOOD
                + " blocks deep; a gallery of " + gallery.size() + " for watchers", "plan " + ALGO);
        b.ops.removeIf(java.util.Objects::isNull);
        Plan plan = Plan.of(SLOT, ALGO, 0L, box, b.palette, b.ops, List.of(), List.of(), null, summary, 0);
        return new LavaSite(plan, spawns, gallery, galleryBox, LAMP);
    }

    /** Whether offset {@code n} along a wall or the floor has a lamp. */
    static boolean lampAt(int n) {
        return n >= LAMP_FROM && (n - LAMP_FROM) % LAMP_EVERY == 0;
    }

    // ---- the room itself ----------------------------------------------------------------------------

    private static void shell(Builder b) {
        for (int i = 0; i < SIZE_X; i++) {
            for (int k = 0; k < SIZE_Z; k++) {
                boolean edge = i == 0 || k == 0 || i == SIZE_X - 1 || k == SIZE_Z - 1;
                b.set(i, 0, k, !edge && lampAt(i) && lampAt(k) ? LAMP : FLOOR);
                b.set(i, ROOF, k, !edge && i % 4 == 2 && k % 4 == 2 ? LAMP : ROOF_GLASS);
                if (!edge) {
                    continue;
                }
                int along = i == 0 || i == SIZE_X - 1 ? k : i;
                boolean corner = (i == 0 || i == SIZE_X - 1) && (k == 0 || k == SIZE_Z - 1);
                for (int j = 1; j < ROOF; j++) {
                    boolean lamp = !corner && lampAt(along) && contains(WALL_LAMP_Y, j);
                    b.set(i, j, k, lamp ? LAMP : j == 1 ? SKIRTING : WALL);
                }
            }
        }
        // the window: a quartz frame with a cross in it, and real daylight through the glass
        for (int k = WINDOW_Z0; k <= WINDOW_Z1; k++) {
            for (int j = WINDOW_Y0; j <= WINDOW_Y1; j++) {
                boolean frame = k == WINDOW_Z0 || k == WINDOW_Z1 || j == WINDOW_Y0 || j == WINDOW_Y1
                        || k == (WINDOW_Z0 + WINDOW_Z1) / 2 || k == (WINDOW_Z0 + WINDOW_Z1) / 2 + 1
                        || j == (WINDOW_Y0 + WINDOW_Y1) / 2;
                b.set(SIZE_X - 1, j, k, frame ? FRAME : WINDOW);
            }
        }
    }

    /** The rug is the floor's own blocks (no carpet: nothing to burn or wash away), red, white, then blue. */
    private static void rug(Builder b) {
        for (int i = RUG_X0; i <= RUG_X1; i++) {
            for (int k = RUG_Z0; k <= RUG_Z1; k++) {
                if (LAMP.equals(b.get(i, 0, k))) {
                    continue;
                }
                int ring = Math.min(Math.min(i - RUG_X0, RUG_X1 - i), Math.min(k - RUG_Z0, RUG_Z1 - k));
                b.set(i, 0, k, RUG[Math.min(ring, RUG.length - 1)]);
            }
        }
    }

    /**
     * The sofa. From the floor: a slab (feet 1.5), the footstool (2), a slab on it (2.5), the seat (3), a slab at each
     * end of the seat (3.5), the arms (4), a slab at each arm's back (4.5), the back's ends (5), a slab (5.5), the
     * back (6).
     */
    private static void sofa(Builder b) {
        for (int i = SOFA_X0; i <= SOFA_X1; i++) {
            boolean arm = i <= SOFA_X0 + 1 || i >= SOFA_X1 - 1;
            boolean seatEnd = i == SOFA_X0 + 2 || i == SOFA_X1 - 2;
            for (int k = 1; k <= 2; k++) { // the back
                b.column(i, k, arm ? 5 : seatEnd ? 5.5 : 6, SOFA_DARK, SOFA_DARK_SLAB);
            }
            for (int k = 3; k <= 6; k++) { // the arms and the seat
                if (arm) {
                    b.column(i, k, k == 3 ? 4.5 : 4, SOFA_DARK, SOFA_DARK_SLAB);
                } else {
                    b.column(i, k, seatEnd ? 3.5 : 3, SOFA, SOFA_SLAB);
                }
            }
        }
        for (int i = SOFA_X0 + 3; i <= SOFA_X1 - 3; i++) { // the footstool and its steps
            b.column(i, 7, 2.5, SOFA, SOFA_SLAB);
            b.column(i, 8, 2, SOFA, SOFA_SLAB);
            b.column(i, 9, 2, SOFA, SOFA_SLAB);
            b.column(i, 10, 1.5, SOFA, SOFA_SLAB);
        }
        for (int i : new int[]{10, 15}) { // buttons that glow
            b.set(i, 2, 4, BUTTON);
            b.set(i, 5, 1, BUTTON);
            b.set(i, 1, 9, BUTTON);
        }
        for (int i : new int[]{SOFA_X0, SOFA_X1}) {
            b.set(i, 3, 5, BUTTON);
        }
    }

    /** The coffee table, and the stack of books that is its steps (south of it, two wide). */
    private static void table(Builder b) {
        for (int i = TABLE_X0; i <= TABLE_X1; i++) {
            for (int k = TABLE_Z0; k <= TABLE_Z1; k++) {
                b.set(i, TABLE_TOP, k, TABLE);
            }
        }
        for (int i : new int[]{TABLE_X0, TABLE_X1}) {
            for (int k : new int[]{TABLE_Z0, TABLE_Z1}) {
                for (int j = 1; j < TABLE_TOP; j++) {
                    b.set(i, j, k, LEG);
                }
            }
        }
        b.set(15, TABLE_TOP, 16, LAMP);
        b.set(16, TABLE_TOP, 17, LAMP);
        for (int i = TABLE_X0; i <= TABLE_X0 + 5; i++) {
            for (int k = TABLE_Z1 + 1; k <= TABLE_Z1 + 2; k++) {
                b.column(i, k, 1.5 + 0.5 * (i - TABLE_X0), TOYS[(i - TABLE_X0) % TOYS.length], PAGE);
            }
        }
    }

    /** Four toy cubes, 2 by 2: yellow (2 high, feet), red (3), blue (4) and lime (5), with half steps between. */
    private static void toyBlocks(Builder b) {
        for (int k = 12; k <= 13; k++) {
            b.column(26, k, 1.5, TOYS[2], PAGE);
            b.column(25, k, 2, TOYS[2], PAGE);
            b.column(24, k, 2.5, TOYS[2], PAGE);
        }
        b.column(23, 13, 3, TOYS[0], PAGE);
        b.column(22, 13, 3, TOYS[0], PAGE);
        b.column(23, 12, 3.5, TOYS[0], PAGE);
        b.column(22, 12, 3.5, TOYS[0], PAGE);
        for (int k = 10; k <= 11; k++) {
            b.column(22, k, 4, TOYS[1], PAGE);
            b.column(23, k, 4.5, TOYS[1], PAGE);
            b.column(24, k, 5, TOYS[3], PAGE);
            b.column(25, k, 5, TOYS[3], PAGE);
        }
        b.set(25, 4, 10, LAMP);
    }

    /** The toy chest in the south-east corner, and the staircase of blocks up its west side (two wide). */
    private static void chest(Builder b) {
        for (int i = CHEST_X0; i <= SIZE_X - 2; i++) {
            for (int k = CHEST_Z0; k <= SIZE_Z - 2; k++) {
                for (int j = 1; j <= CHEST_TOP; j++) {
                    b.set(i, j, k, j == CHEST_TOP ? CHEST_LID : CHEST);
                }
            }
        }
        b.set(26, 3, CHEST_Z0, LATCH);
        b.set(27, 3, CHEST_Z0, LATCH);
        b.set(25, CHEST_TOP, 26, LAMP);
        b.set(28, CHEST_TOP, 28, LAMP);
        for (int k = CHEST_Z0; k <= SIZE_Z - 2; k++) {
            for (int i = CHEST_X0 - 2; i <= CHEST_X0 - 1; i++) {
                b.column(i, k, 1.5 + 0.5 * (k - CHEST_Z0), TOYS[(k - CHEST_Z0) % TOYS.length], PAGE);
            }
        }
    }

    /**
     * The bookcase: its two sides, a shelf board at 3 and at 6 (ledges two wide in front of the books), the gallery's
     * board at 9 with a glass rail, its top at 12; and up its front a staircase of books, two wide, from the floor
     * to the first shelf (feet 4) and on to the second (feet 7).
     */
    private static void bookcase(Builder b) {
        for (int k : new int[]{CASE_Z0, CASE_Z1}) {
            for (int j = 1; j <= CASE_TOP; j++) {
                for (int i = 1; i <= (j >= GALLERY_BOARD ? 5 : 4); i++) {
                    b.set(i, j, k, SHELF);
                }
            }
        }
        for (int k = CASE_Z0 + 1; k < CASE_Z1; k++) {
            boolean glows = k % 4 == 1;
            for (int j = 1; j <= CASE_TOP; j++) {
                boolean board = contains(SHELF_Y, j);
                if (board) {
                    for (int i = 1; i <= 4; i++) {
                        b.set(i, j, k, SHELF);
                    }
                } else if (j == GALLERY_BOARD || j == CASE_TOP) {
                    for (int i = 1; i <= 5; i++) {
                        b.set(i, j, k, SHELF);
                    }
                } else if (j > GALLERY_BOARD) { // the gallery: books behind, a glass rail in front
                    b.set(1, j, k, glows && j == GALLERY_FEET ? LAMP : BOOKS[k % BOOKS.length]);
                    b.set(5, j, k, RAIL);
                } else { // a shelf: two rows of books, a ledge two wide in front of them
                    boolean low = j == 1 || j == SHELF_Y[0] + 1 || j == SHELF_Y[1] + 1;
                    b.set(1, j, k, BOOKS[k % BOOKS.length]);
                    b.set(2, j, k, glows && low ? LAMP : BOOKS[(k + 2) % BOOKS.length]);
                }
            }
        }
        for (int k = CASE_Z1 - 1; k >= CASE_Z1 - 12; k--) { // 26 down to 15: feet 1.5 up to 7
            for (int i = 5; i <= 6; i++) {
                b.column(i, k, 1.5 + 0.5 * (CASE_Z1 - 1 - k), TOYS[(CASE_Z1 - 1 - k) % TOYS.length], PAGE);
            }
        }
    }

    /** Two big floor cushions (bottom slabs, three by three): little islands. */
    private static void cushions(Builder b) {
        for (int d = 0; d < 3; d++) {
            for (int e = 0; e < 3; e++) {
                b.set(22 + d, 1, 4 + e, CUSHION_A);
                b.set(8 + d, 1, 24 + e, CUSHION_B);
            }
        }
    }

    private static boolean contains(int[] values, int v) {
        for (int x : values) {
            if (x == v) {
                return true;
            }
        }
        return false;
    }

    /** The plan being made: one op per block, the last one set wins. */
    private static final class Builder {
        final Box box;
        final List<String> palette = new ArrayList<>();
        final List<BlockOp> ops = new ArrayList<>();
        final Map<Long, Integer> at = new HashMap<>();

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
            Integer was = at.get(key(i, j, k));
            if (was != null) {
                ops.set(was, op);
            } else {
                at.put(key(i, j, k), ops.size());
                ops.add(op);
            }
        }

        String get(int i, int j, int k) {
            Integer n = at.get(key(i, j, k));
            return n == null ? null : palette.get(ops.get(n).state());
        }

        /**
         * A column on the floor whose top is {@code feet} over the floor block: whole blocks, and a bottom slab on
         * them when {@code feet} ends in a half.
         */
        void column(int i, int k, double feet, String block, String slab) {
            int whole = (int) Math.floor(feet) - 1;
            for (int j = 1; j <= whole; j++) {
                set(i, j, k, block);
            }
            if (feet - Math.floor(feet) >= 0.5) {
                set(i, whole + 1, k, slab);
            }
        }

        static long key(int i, int j, int k) {
            return ((long) i << 32) | ((long) j << 16) | k;
        }
    }
}
