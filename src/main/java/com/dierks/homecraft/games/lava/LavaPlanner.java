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
 * Makes The Floor Is Lava's playroom: one closed room in the sky, built so big that the players are toy-sized in it
 * (the owner liked: "a giant playroom where you are toy-sized, with sofas, cushions, a coffee table and bookshelves
 * to climb on").
 *
 * <p><b>0.57.0: a bigger room whose furniture moves.</b> The owner: "the mid lvl was fun the first time, but then you
 * just know how it works ... if the room could change maybe that would be more fun? You don't even need to change the
 * furniture per se, just move it around ... It being such a small level it feels repetitive". So the room grew from
 * 32 x 16 x 32 to {@value #SIZE_X} x {@value #SIZE_Y} x {@value #SIZE_Z} (the box keeps its corner; a shipped server
 * claims the new ground at its next start, {@code LavaService}), and the room is now two things:
 * <ul>
 *   <li><b>the room itself</b>, which never changes: the floor with its rug and lamps, the walls with the big window,
 *       the glass roof, and the bookcase on the west wall with its staircase of books, its two shelves (feet 4 and 7)
 *       and the glassed-in gallery on top where Challenge's watchers stand. Its second shelf is the room's highest
 *       ground in every layout, so {@link #FLOOD} is the same whatever moves;</li>
 *   <li><b>the furniture</b> ({@link LavaLayout}): the sofa (players arrive on its seat), the coffee table with its
 *       book steps, the toy chest with its staircase, two toy staircases, two floor cushions and four stools. Where
 *       each stands comes from a seed ({@link #layout}); {@link #plan(Box)} is the room as built, with the furniture
 *       where {@link #HOME_SEED} puts it. The game moves the furniture block by block between layouts
 *       ({@link LavaWriter#moveTo}) and always puts it back where it was built.</li>
 * </ul>
 *
 * <p><b>Made for a four-year-old with a controller.</b> Every piece but the stools is reached by its own steps half a
 * block high (a slab, then a whole block), two blocks wide or more, so nothing needs a jump; the tops are wide and
 * flat. The stools are the grown-ups' hops (a jump from the floor or the piece beside them).
 *
 * <p><b>Nothing here burns.</b> Every block is stone, concrete, terracotta, quartz, prismarine, copper, glass, slime
 * or a lamp ({@link #FIREPROOF}); no wood, wool, carpet or bookshelf. Everything is a whole block or a bottom slab, so
 * the lava (source blocks the game places, never flowing) meets nothing it could wash away, and {@link LavaSite} can
 * tell exactly where a player may stand.
 *
 * <p><b>Why it stays mob free.</b> As the Clubhouse: every block a mob could stand on has block light over it
 * ({@code LavaPlannerTest} floods the light of many layouts), and the roof is glass. No way out: the walls go up to the
 * roof.
 *
 * <p>Pure and deterministic: the same box and seed always give the same plan (the room as built is pinned by a test).
 */
public final class LavaPlanner {

    /** The planner's version: part of the plan, so a change of the room is a change of plan (0.57.0: 2). */
    public static final int ALGO = 2;
    /** The plan's "slot" name. */
    public static final String SLOT = "floor_is_lava";

    public static final int SIZE_X = 48;
    public static final int SIZE_Y = 16;
    public static final int SIZE_Z = 48;
    /** 0.57.0: the room's size before it grew (its claim is recognised, and the new ground claimed). */
    public static final int OLD_SIZE = 32;
    /** The roof's layer; the walls stand under it. */
    public static final int ROOF = 15;
    /**
     * The lava level that covers every piece of the play area: its highest ground is the bookcase's second shelf,
     * feet at {@value}. Challenge ends there at the latest ({@code LavaPlannerTest} holds every layout to it).
     */
    public static final int FLOOD = 7;
    /** The highest lava with dry ground left for everyone: Group's lava never comes higher. */
    public static final int SAFE_TOP = FLOOD - 1;
    /** The layout the room is built with (and goes back to after every round). */
    public static final long HOME_SEED = 0x1A7A_2026L;

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
    /** 0.57.0: a bouncy cushion, for one round (a surprise). Slime doesn't burn. */
    public static final String SLIME = "minecraft:slime_block";

    /**
     * Every block id the room is built of, in any layout. None of them burns or is set alight by lava (stone,
     * concrete, terracotta, quartz, prismarine, sandstone, copper, gold, glass, slime and lamps; no wood, wool, carpet,
     * leaves or bookshelves): pinned by {@code LavaPlannerTest}, so adding a block is a decision.
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
            "minecraft:smooth_red_sandstone_slab", "minecraft:slime_block");

    // ---- where things are (offsets from the box's corner) ---------------------------------------------

    /** The floor's and the walls' lamps: every fifth block, from the third. */
    static final int LAMP_EVERY = 5;
    static final int LAMP_FROM = 3;
    /** The wall lamps' heights. */
    static final int[] WALL_LAMP_Y = {4, 9, 13};
    /** The window in the east wall: along z, and up. */
    static final int WINDOW_Z0 = 15;
    static final int WINDOW_Z1 = 32;
    static final int WINDOW_Y0 = 4;
    static final int WINDOW_Y1 = 12;
    /** The rug, in the floor's own layer. */
    static final int RUG_X0 = 12;
    static final int RUG_X1 = 37;
    static final int RUG_Z0 = 12;
    static final int RUG_Z1 = 35;
    /** The sofa's seat, where players arrive (the sofa's own cells {i, k}), feet at {@value #SEAT_FEET}. */
    static final int[][] SEAT = {{3, 3}, {5, 3}, {7, 3}, {10, 3}, {3, 4}, {6, 4}, {8, 4}, {10, 4}};
    static final int SEAT_FEET = 3;
    /** The bookcase on the west wall: along z, its shelves' boards, and its gallery. */
    static final int CASE_Z0 = 14;
    static final int CASE_Z1 = 33;
    static final int[] SHELF_Y = {3, 6};
    static final int GALLERY_BOARD = 9;
    static final int CASE_TOP = 12;
    /** The gallery's feet, and where its watchers stand (x offset; along z from {@code CASE_Z0 + 2}). */
    static final int GALLERY_FEET = GALLERY_BOARD + 1;
    static final int GALLERY_X = 3;
    static final int GALLERY_SPOTS = 16;
    /** Where no furniture stands: the bookcase, its staircase and a walk in front of them {x0, z0, x1, z1}. */
    static final int[] BOOKCASE_AREA = {0, CASE_Z0 - 2, 8, CASE_Z1 + 2};

    private LavaPlanner() {
    }

    /**
     * The playroom in {@code box}, as built: the furniture where {@link #HOME_SEED} puts it.
     *
     * @throws IllegalArgumentException when the box isn't the room's size
     */
    public static LavaSite plan(Box box) {
        checkBox(box);
        return plan(box, layout(box, HOME_SEED));
    }

    /** The playroom in {@code box} with the furniture in {@code seed}'s layout (always a playable one). */
    public static LavaSite site(Box box, long seed) {
        checkBox(box);
        return plan(box, layout(box, seed));
    }

    private static void checkBox(Box box) {
        if (box == null || box.sizeX() != SIZE_X || box.sizeY() != SIZE_Y || box.sizeZ() != SIZE_Z) {
            throw new IllegalArgumentException("the playroom's box is " + SIZE_X + " x " + SIZE_Y + " x " + SIZE_Z
                    + ", not " + (box == null ? "missing" : box.sizeX() + " x " + box.sizeY() + " x " + box.sizeZ()));
        }
    }

    /**
     * A playable layout for {@code seed}: the first of its tries that passes {@link #playable} (the same in any box of
     * the room's size: the check only looks at the room's own offsets), else the room as built.
     */
    public static List<LavaLayout.Placed> layout(Box box, long seed) {
        checkBox(box);
        for (int attempt = 0; attempt < LavaLayout.TRIES; attempt++) {
            List<LavaLayout.Placed> c = LavaLayout.candidate(seed, attempt, SIZE_X, BOOKCASE_AREA);
            if (c == null) {
                continue;
            }
            LavaSite s;
            try {
                s = plan(box, c);
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (playable(s)) {
                return c;
            }
        }
        if (seed == HOME_SEED) {
            throw new IllegalStateException("the room as built has no playable layout");
        }
        return layout(box, HOME_SEED);
    }

    /**
     * Whether a room is fit to play: the bookcase's second shelf is the highest ground ({@link #FLOOD}), there is a
     * seat for everyone on the sofa, plenty of open floor for stars, every piece can be got onto (a piece with steps
     * by a walk of half steps from the floor, a stool by a jump: {@link LavaSite#hopReach}), and no open floor is far
     * from dry ground ({@value #FAR} steps).
     */
    static boolean playable(LavaSite s) {
        if (s.flood() != FLOOD || s.spawns().size() < SEAT.length || s.starStands().size() < 400) {
            return false;
        }
        List<LavaLayout.Placed> layout = s.layout();
        boolean[] walk = new boolean[layout.size()];
        boolean[] hop = new boolean[layout.size()];
        for (LavaSite.Stand st : s.reach()) {
            int p = s.pieceOf(st);
            if (p >= 0) {
                walk[p] = true;
            }
        }
        for (LavaSite.Stand st : s.hopReach()) {
            int p = s.pieceOf(st);
            if (p >= 0) {
                hop[p] = true;
            }
        }
        for (int i = 0; i < layout.size(); i++) {
            LavaLayout.Placed p = layout.get(i);
            if (p != null && !(p.kind().stepped ? walk[i] : hop[i])) {
                return false;
            }
        }
        for (int level = 0; level <= SAFE_TOP; level++) {
            if (s.safe(level).size() < LavaSettings.MAX_PLAYERS) {
                return false;
            }
        }
        return farthestFromDry(s) <= FAR;
    }

    /**
     * 0.57.0: no open floor is more than this many steps (along x and z) from ground that stays dry when the lava
     * comes: furniture is spread over the room, not all in one corner, so a warning of a few seconds is enough.
     */
    static final int FAR = 18;

    /** The most steps from any open floor to a place that is dry with the lava one deep. */
    static int farthestFromDry(LavaSite s) {
        Box b = s.box();
        int[][] dist = new int[SIZE_X][SIZE_Z];
        for (int[] row : dist) {
            java.util.Arrays.fill(row, Integer.MAX_VALUE);
        }
        java.util.ArrayDeque<int[]> q = new java.util.ArrayDeque<>();
        for (LavaSite.Stand st : s.safe(1)) {
            int i = st.x() - b.minX();
            int k = st.z() - b.minZ();
            if (dist[i][k] != 0) {
                dist[i][k] = 0;
                q.add(new int[]{i, k});
            }
        }
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!q.isEmpty()) {
            int[] c = q.poll();
            for (int[] d : dirs) {
                int i = c[0] + d[0];
                int k = c[1] + d[1];
                if (i >= 1 && k >= 1 && i < SIZE_X - 1 && k < SIZE_Z - 1 && dist[i][k] > dist[c[0]][c[1]] + 1) {
                    dist[i][k] = dist[c[0]][c[1]] + 1;
                    q.add(new int[]{i, k});
                }
            }
        }
        int far = 0;
        for (LavaSite.Stand st : s.starStands()) {
            far = Math.max(far, dist[st.x() - b.minX()][st.z() - b.minZ()]);
        }
        return far;
    }

    /** The playroom in {@code box} with the furniture as {@code layout} has it (a {@code null} piece is gone). */
    public static LavaSite plan(Box box, List<LavaLayout.Placed> layout) {
        checkBox(box);
        Builder b = new Builder(box);
        shell(b);
        rug(b);
        bookcase(b);
        List<LavaSite.Spot> spawns = new ArrayList<>();
        for (int slot = 0; slot < layout.size(); slot++) {
            LavaLayout.Placed p = layout.get(slot);
            if (p == null) {
                continue;
            }
            Shape s = new Shape();
            switch (p.kind()) {
                case SOFA -> sofa(s);
                case TABLE -> table(s);
                case CHEST -> chest(s);
                case TOYS -> toys(s, p.style());
                case CUSHION -> cushion(s, p.style(), p.bouncy());
                case STOOL -> stool(s, p.style());
            }
            b.place(p, slot, s);
            if (p.kind() == LavaLayout.Kind.SOFA && spawns.isEmpty()) {
                for (int[] at : SEAT) {
                    int[] o = p.at(at[0], at[1]);
                    spawns.add(new LavaSite.Spot(box.minX() + o[0] + 0.5, box.minY() + SEAT_FEET,
                            box.minZ() + o[1] + 0.5, p.frontYaw()));
                }
            }
        }
        if (spawns.isEmpty()) {
            throw new IllegalArgumentException("a playroom needs its sofa");
        }
        List<LavaSite.Spot> gallery = new ArrayList<>();
        for (int n = 0; n < GALLERY_SPOTS; n++) {
            gallery.add(new LavaSite.Spot(box.minX() + GALLERY_X + 0.5, box.minY() + GALLERY_FEET,
                    box.minZ() + CASE_Z0 + 2 + n + 0.5, -90f));
        }
        Box galleryBox = new Box(box.minX() + 2, box.minY() + GALLERY_FEET, box.minZ() + CASE_Z0 + 1,
                box.minX() + 4, box.minY() + GALLERY_FEET + 1, box.minZ() + CASE_Z1 - 1);
        List<String> summary = List.of("the playroom: " + box.describe(), spawns.size() + " arrival spots on the"
                + " sofa, a coffee table, a toy chest, toy steps, cushions and stools that move about, and a bookcase"
                + " to climb; lava up to " + FLOOD + " blocks deep; a gallery of " + gallery.size() + " for watchers",
                "plan " + ALGO);
        b.ops.removeIf(java.util.Objects::isNull);
        Plan plan = Plan.of(SLOT, ALGO, 0L, box, b.palette, b.ops, List.of(), List.of(), null, summary, 0);
        return new LavaSite(plan, spawns, gallery, galleryBox, LAMP, b.owners, layout);
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
        for (int k = CASE_Z1 - 1; k >= CASE_Z1 - 12; k--) { // feet 1.5 up to 7
            for (int i = 5; i <= 6; i++) {
                b.column(i, k, 1.5 + 0.5 * (CASE_Z1 - 1 - k), TOYS[(CASE_Z1 - 1 - k) % TOYS.length], PAGE);
            }
        }
    }

    // ---- the furniture, each in its own cells (i along its width, k from its back to its front) ----------------

    /**
     * The sofa, 14 by 10. From the floor at its front: a slab (feet 1.5), the footstool (2), a slab on it (2.5), the
     * seat (3), a slab at each end of the seat (3.5), the arms (4), a slab at each arm's back (4.5), the back's ends
     * (5), a slab (5.5), the back (6).
     */
    private static void sofa(Shape s) {
        for (int i = 0; i <= 13; i++) {
            boolean arm = i <= 1 || i >= 12;
            boolean seatEnd = i == 2 || i == 11;
            for (int k = 0; k <= 1; k++) { // the back
                s.column(i, k, arm ? 5 : seatEnd ? 5.5 : 6, SOFA_DARK, SOFA_DARK_SLAB);
            }
            for (int k = 2; k <= 5; k++) { // the arms and the seat
                if (arm) {
                    s.column(i, k, k == 2 ? 4.5 : 4, SOFA_DARK, SOFA_DARK_SLAB);
                } else {
                    s.column(i, k, seatEnd ? 3.5 : 3, SOFA, SOFA_SLAB);
                }
            }
        }
        for (int i = 3; i <= 10; i++) { // the footstool and its steps
            s.column(i, 6, 2.5, SOFA, SOFA_SLAB);
            s.column(i, 7, 2, SOFA, SOFA_SLAB);
            s.column(i, 8, 2, SOFA, SOFA_SLAB);
            s.column(i, 9, 1.5, SOFA, SOFA_SLAB);
        }
        for (int i : new int[]{4, 9}) { // buttons that glow
            s.set(i, 2, 3, BUTTON);
            s.set(i, 5, 0, BUTTON);
            s.set(i, 1, 8, BUTTON);
        }
        s.set(0, 3, 4, BUTTON);
        s.set(13, 3, 4, BUTTON);
    }

    /** The coffee table, 8 by 6 (top at feet 4, walk under it), and the stack of books that is its steps. */
    private static void table(Shape s) {
        for (int i = 0; i <= 7; i++) {
            for (int k = 0; k <= 5; k++) {
                s.set(i, 3, k, TABLE);
            }
        }
        for (int i : new int[]{0, 7}) {
            for (int k : new int[]{0, 5}) {
                for (int j = 1; j < 3; j++) {
                    s.set(i, j, k, LEG);
                }
            }
        }
        s.set(3, 3, 2, LAMP);
        s.set(4, 3, 3, LAMP);
        for (int i = 0; i <= 5; i++) {
            for (int k = 6; k <= 7; k++) {
                s.column(i, k, 1.5 + 0.5 * i, TOYS[i % TOYS.length], PAGE);
            }
        }
    }

    /** The toy chest, 8 by 7 (top at feet 5), and the staircase of blocks up its side (two wide). */
    private static void chest(Shape s) {
        for (int i = 2; i <= 9; i++) {
            for (int k = 0; k <= 6; k++) {
                for (int j = 1; j <= 4; j++) {
                    s.set(i, j, k, j == 4 ? CHEST_LID : CHEST);
                }
            }
        }
        s.set(5, 3, 0, LATCH);
        s.set(6, 3, 0, LATCH);
        s.set(4, 4, 2, LAMP);
        s.set(7, 4, 4, LAMP);
        for (int k = 0; k <= 6; k++) {
            for (int i = 0; i <= 1; i++) {
                s.column(i, k, 1.5 + 0.5 * k, TOYS[k % TOYS.length], PAGE);
            }
        }
    }

    /** A toy staircase, 5 by 4: cubes 2 by 2 with half steps between, from a slab (1.5) up to 5. */
    private static void toys(Shape s, int style) {
        String a = TOYS[style % TOYS.length];
        String b = TOYS[(style + 1) % TOYS.length];
        String c = TOYS[(style + 3) % TOYS.length];
        for (int k = 2; k <= 3; k++) {
            s.column(4, k, 1.5, a, PAGE);
            s.column(3, k, 2, a, PAGE);
            s.column(2, k, 2.5, a, PAGE);
        }
        s.column(1, 3, 3, b, PAGE);
        s.column(0, 3, 3, b, PAGE);
        s.column(1, 2, 3.5, b, PAGE);
        s.column(0, 2, 3.5, b, PAGE);
        for (int k = 0; k <= 1; k++) {
            s.column(0, k, 4, c, PAGE);
            s.column(1, k, 4.5, c, PAGE);
            s.column(2, k, 5, TOYS[(style + 2) % TOYS.length], PAGE);
            s.column(3, k, 5, TOYS[(style + 2) % TOYS.length], PAGE);
        }
        s.set(3, 4, 0, LAMP);
    }

    /** A floor cushion, 3 by 3 (a slab: feet 1.5), a little island; bouncy, three by three of slime (feet 2). */
    private static void cushion(Shape s, int style, boolean bouncy) {
        for (int i = 0; i <= 2; i++) {
            for (int k = 0; k <= 2; k++) {
                s.set(i, 1, k, bouncy ? SLIME : style % 2 == 0 ? CUSHION_A : CUSHION_B);
            }
        }
    }

    /** A toy-block stool, 2 by 2, {@link LavaLayout#STOOL_FEET} high by its style: a hop, never a walk. */
    private static void stool(Shape s, int style) {
        double feet = LavaLayout.STOOL_FEET[Math.floorMod(style, LavaLayout.STOOL_FEET.length)];
        String block = TOYS[(style * 2 + 1) % TOYS.length];
        for (int i = 0; i <= 1; i++) {
            for (int k = 0; k <= 1; k++) {
                s.column(i, k, feet, block, PAGE);
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

    /** A piece's blocks in its own cells, before it is turned and put down. */
    private static final class Shape {
        final Map<Long, String> blocks = new java.util.LinkedHashMap<>();

        void set(int i, int j, int k, String block) {
            blocks.put(Builder.key(i, j, k), block);
        }

        /** A column on the floor whose top is {@code feet} over the floor block (whole blocks, then a slab). */
        void column(int i, int k, double feet, String block, String slab) {
            int whole = (int) Math.floor(feet) - 1;
            for (int j = 1; j <= whole; j++) {
                set(i, j, k, block);
            }
            if (feet - Math.floor(feet) >= 0.5) {
                set(i, whole + 1, k, slab);
            }
        }
    }

    /** The plan being made: one op per block, the last one set wins; and which piece each furniture block is. */
    private static final class Builder {
        final Box box;
        final List<String> palette = new ArrayList<>();
        final List<BlockOp> ops = new ArrayList<>();
        final Map<Long, Integer> at = new HashMap<>();
        /** Furniture blocks' pieces (their layout slots), by {@link LavaSite} key. */
        final Map<Long, Integer> owners = new HashMap<>();

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

        /** A piece's blocks, turned and put where it stands. */
        void place(LavaLayout.Placed p, int slot, Shape s) {
            for (Map.Entry<Long, String> e : s.blocks.entrySet()) {
                long key = e.getKey();
                int i = (int) (key >>> 32);
                int j = (int) ((key >>> 16) & 0xFFFF);
                int k = (int) (key & 0xFFFF);
                int[] o = p.at(i, k);
                set(o[0], j, o[1], e.getValue());
                owners.put(LavaSite.key(box.minX() + o[0], box.minY() + j, box.minZ() + o[1]), slot);
            }
        }

        /** A column on the floor (the bookcase's steps). */
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
