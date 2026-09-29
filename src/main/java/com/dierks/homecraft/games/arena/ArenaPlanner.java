package com.dierks.homecraft.games.arena;

import com.dierks.homecraft.games.arena.rules.Cell;
import com.dierks.homecraft.games.arena.rules.FloorLayout;
import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenRandom;
import com.dierks.homecraft.games.gen.api.GenSeed;
import com.dierks.homecraft.games.gen.api.Palette;
import com.dierks.homecraft.games.gen.api.Plan;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * Makes a week's Falling Floors arena (EVENTS-DROPPER-SPEC §B.3.2) from (the box, a seed, the week):
 * three stained-glass floors 8 apart, each a shape of about 450 cells inside a 32 x 32 footprint,
 * spawns spread over the top floor, and a railed gallery round the box's edge.
 *
 * <p><b>Why these shapes.</b> A disc, a rounded square, a ring with an island, a plus and a diamond
 * all hold roughly the same number of cells, so every week is about as long a game, but they play
 * very differently: the ring's island is a refuge you have to jump to, the plus has dead ends, the
 * diamond's corners are narrow. Each floor draws a different one, with a small change in size, so
 * the three floors look different from each other and from last week.
 *
 * <p><b>Why no walls.</b> A wall's top would be a safe spot to stand on for ever. The plan holds only
 * the floors and the gallery; everything else in the box is air, which the reset makes sure of.
 *
 * <p><b>Why a gallery on the edge.</b> It is the lobby, the stand, and where you go when you are out:
 * a 4-wide ring 6 above the top floor and at least 4 blocks sideways from any floor cell, with a
 * 2-high glass rail on both sides, so nobody can jump in or fall out. The only way out of it is the
 * kit's "Leave game".
 *
 * <p>Pure and deterministic: the same box, seed and week give the same plan on every host (the
 * {@link Plan#hash} is pinned by a test). {@link ArenaValidator} checks the result independently.
 */
public final class ArenaPlanner {

    /** The planner's version: part of the plan, so a change of algorithm is a change of plan. */
    public static final int ALGO = 1;
    /** The plan's "slot" name, and the seed's label. */
    public static final String SLOT = "falling_floors";

    /** The floors' footprint: 32 x 32 in the middle of the 48 x 48 box. */
    public static final int FOOTPRINT = 32;
    public static final int FOOTPRINT_OFFSET = 8;
    /** The floors' heights above the box's floor, top first: y 200, 192, 184 in the shipped box. */
    public static final List<Integer> FLOOR_OFFSETS = List.of(24, 16, 8);
    /** Feet below this (above the box's floor) are out: y 181, three below the bottom floor. */
    public static final int OUT_OFFSET = 5;
    /** The gallery walk's height above the box's floor: y 206, six above the top floor. */
    public static final int GALLERY_OFFSET = 30;
    /** The gallery's width, and its rails' height. */
    public static final int GALLERY_WIDTH = 4;
    public static final int RAIL_HEIGHT = 2;
    /** The least sideways gap between the gallery and any floor cell. */
    public static final int GALLERY_GAP = 4;

    /** Each floor's cells: 450, give or take 10%. */
    public static final int TARGET_CELLS = 450;
    public static final int MIN_CELLS = 405;
    public static final int MAX_CELLS = 495;
    /** No piece of a floor is smaller than this (a lone block is no place to stand). */
    public static final int MIN_PIECE = 9;
    /** A floor is one piece, or a ring and its island. */
    public static final int MAX_PIECES = 2;
    /** Spawns on the top floor: one per player at the most a round can have, at least 3 apart. */
    public static final int SPAWNS = 16;
    public static final double SPAWN_APART = 3.0;
    /** The most blocks a plan may have (a reset writes only what differs). */
    public static final int MAX_OPS = 8_000;

    /** The floors' colours, top to bottom (the colour language: red is "about to fall"). */
    public static final List<String> FLOOR_COLOURS = List.of("yellow", "pink", "light_blue");
    /** The gallery's walk and rails. */
    public static final String WALK = "minecraft:white_concrete";
    public static final String RAIL = Palette.GLASS;

    /** The shapes a floor can take, each with a few sizes that keep it near 450 cells. */
    public enum Shape {
        DISC("disc", "disc", new double[][]{{11.8}, {12.0}, {12.2}}),
        SQUARE("square", "rounded square", new double[][]{{22, 3}, {22, 4}, {22, 5}, {22, 6}}),
        RING("ring", "ring with an island", new double[][]{{14.0, 9.0, 5.0}, {14.0, 9.0, 5.5}, {14.5, 9.5, 5.0},
                {14.5, 9.0, 4.5}, {14.5, 9.5, 5.5}}),
        PLUS("plus", "plus", new double[][]{{8, 32}, {10, 28}, {8, 30}}),
        DIAMOND("diamond", "diamond", new double[][]{{14.0}, {15.0}});

        private final String id;
        private final String label;
        private final double[][] sizes;

        Shape(String id, String label, double[][] sizes) {
            this.id = id;
            this.label = label;
            this.sizes = sizes;
        }

        /** The shape's id, as the website's feed and the plan's summary read it. */
        public String id() {
            return id;
        }

        /** The shape as players read it ("ring with an island"). */
        public String label() {
            return label;
        }

        /** The shape with this id, or {@code null}. */
        public static Shape of(String id) {
            for (Shape s : values()) {
                if (s.id.equalsIgnoreCase(id == null ? "" : id.trim())) {
                    return s;
                }
            }
            return null;
        }

        /** How many sizes it comes in. */
        public int sizes() {
            return sizes.length;
        }

        /**
         * Whether a cell whose centre is (dx, dz) from the footprint's middle belongs to the shape in
         * size {@code n}.
         */
        boolean holds(int n, double dx, double dz) {
            double[] p = sizes[n];
            double ax = Math.abs(dx);
            double az = Math.abs(dz);
            double d2 = dx * dx + dz * dz;
            return switch (this) {
                case DISC -> d2 <= p[0] * p[0];
                case SQUARE -> {
                    double h = p[0] / 2;
                    double c = p[1];
                    if (ax > h || az > h) {
                        yield false;
                    }
                    double kx = ax - (h - c);
                    double kz = az - (h - c);
                    yield kx <= 0 || kz <= 0 || kx * kx + kz * kz <= c * c;
                }
                case RING -> (d2 > p[1] * p[1] && d2 <= p[0] * p[0]) || d2 <= p[2] * p[2];
                case PLUS -> (ax <= p[0] / 2 && az <= p[1] / 2) || (az <= p[0] / 2 && ax <= p[1] / 2);
                case DIAMOND -> ax + az <= p[0];
            };
        }

        /** The shape's cells in size {@code n}, the footprint's min corner at (x0, z0). */
        List<Cell> cells(int n, int x0, int z0) {
            List<Cell> out = new ArrayList<>();
            double mid = FOOTPRINT / 2.0;
            for (int i = 0; i < FOOTPRINT; i++) {
                for (int j = 0; j < FOOTPRINT; j++) {
                    if (holds(n, i + 0.5 - mid, j + 0.5 - mid)) {
                        out.add(new Cell(x0 + i, z0 + j));
                    }
                }
            }
            return out;
        }
    }

    private ArenaPlanner() {
    }

    /** The seed of the week's arena: the server's secret, the week key and {@code floors}. */
    public static long seed(long secret, long week) {
        return GenSeed.seed(secret, 7, week, "floors", 0);
    }

    /**
     * The week's arena in {@code box}.
     *
     * @param box  the arena box, 48 x 40 x 48
     * @param seed {@link #seed(long, long)}
     * @param week the week key (a local epoch day), kept on the site for its boards
     * @throws IllegalArgumentException when the box isn't the arena's size
     */
    public static ArenaSite plan(Box box, long seed, long week) {
        if (box == null || box.sizeX() != FallingFloorsSettings.SIZE_X || box.sizeY() != FallingFloorsSettings.SIZE_Y
                || box.sizeZ() != FallingFloorsSettings.SIZE_Z) {
            throw new IllegalArgumentException("the Falling Floors box is " + FallingFloorsSettings.SIZE_X + " x "
                    + FallingFloorsSettings.SIZE_Y + " x " + FallingFloorsSettings.SIZE_Z + ", not "
                    + (box == null ? "missing" : box.sizeX() + " x " + box.sizeY() + " x " + box.sizeZ()));
        }
        GenRandom rnd = new GenRandom(seed);
        List<Shape> order = new ArrayList<>(List.of(Shape.values()));
        for (int i = order.size() - 1; i > 0; i--) {
            Collections.swap(order, i, rnd.nextInt(i + 1));
        }
        List<String> palette = new ArrayList<>();
        for (String colour : FLOOR_COLOURS) {
            palette.add(Palette.stainedGlass(colour));
        }
        palette.add(RAIL);
        palette.add(WALK);
        short railState = (short) FLOOR_COLOURS.size();
        short walkState = (short) (FLOOR_COLOURS.size() + 1);

        int x0 = box.minX() + FOOTPRINT_OFFSET;
        int z0 = box.minZ() + FOOTPRINT_OFFSET;
        List<BlockOp> ops = new ArrayList<>();
        List<String> shapes = new ArrayList<>();
        List<Integer> floorYs = new ArrayList<>();
        List<List<Cell>> floors = new ArrayList<>();
        List<String> summary = new ArrayList<>();
        for (int f = 0; f < FLOOR_OFFSETS.size(); f++) {
            Shape shape = order.get(f);
            GenRandom fr = rnd.fork("floor:" + f);
            List<Cell> cells = shape.cells(fr.nextInt(shape.sizes()), x0, z0);
            int y = box.minY() + FLOOR_OFFSETS.get(f);
            for (Cell c : cells) {
                ops.add(new BlockOp(c.x(), y, c.z(), (short) f));
            }
            shapes.add(shape.id());
            floorYs.add(y);
            floors.add(cells);
            summary.add("floor " + (f + 1) + " (y " + y + ", " + FLOOR_COLOURS.get(f).replace('_', ' ') + "): "
                    + shape.id() + ", " + cells.size() + " blocks");
        }

        int gy = box.minY() + GALLERY_OFFSET;
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                int depth = ringDepth(box, x, z);
                if (depth >= GALLERY_WIDTH) {
                    continue;
                }
                ops.add(new BlockOp(x, gy, z, walkState));
                if (depth == 0 || depth == GALLERY_WIDTH - 1) {
                    for (int h = 1; h <= RAIL_HEIGHT; h++) {
                        ops.add(new BlockOp(x, gy + h, z, railState));
                    }
                }
            }
        }

        List<Cell> spawns = spawns(floors.get(0));
        int outY = box.minY() + OUT_OFFSET;
        summary.add(spawns.size() + " spawns on the top floor; out below y " + outY + "; the gallery at y " + gy);
        summary.add("week of " + LocalDate.ofEpochDay(week) + ", seed " + GenSeed.shortHex(seed) + "...");
        Plan plan = Plan.of(SLOT, ALGO, seed, box, palette, ops, List.of(), List.of(), null, summary, 0);
        FloorLayout layout = FloorLayout.fromPlan(plan, floorYs, outY, spawns);
        List<String> blocks = new ArrayList<>(palette.subList(0, FLOOR_COLOURS.size()));
        return new ArenaSite(plan, layout, week, shapes, blocks, gy, gallerySpots(box, gy));
    }

    /**
     * How far (x, z) is inside the box's edge, in whole blocks: 0 on the edge. The gallery is the
     * ring of depth 0 to {@value #GALLERY_WIDTH} - 1.
     */
    public static int ringDepth(Box box, int x, int z) {
        return Math.min(Math.min(x - box.minX(), box.maxX() - x), Math.min(z - box.minZ(), box.maxZ() - z));
    }

    /**
     * Up to {@value #SPAWNS} spawns spread evenly over the top floor: each on a cell whose eight
     * neighbours are floor too (nobody starts on an edge), and each as far as it can be from the ones
     * before it (farthest-point picking, the first the farthest from the middle), never nearer than
     * {@value #SPAWN_APART}. Ties go to the lower cell, so every host picks the same.
     */
    static List<Cell> spawns(List<Cell> top) {
        TreeSet<Cell> floor = new TreeSet<>(top);
        List<Cell> candidates = new ArrayList<>();
        for (Cell c : floor) {
            boolean inner = true;
            for (int dx = -1; dx <= 1 && inner; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (!floor.contains(new Cell(c.x() + dx, c.z() + dz))) {
                        inner = false;
                        break;
                    }
                }
            }
            if (inner) {
                candidates.add(c);
            }
        }
        List<Cell> out = new ArrayList<>();
        if (candidates.isEmpty()) {
            return out;
        }
        double mx = 0;
        double mz = 0;
        for (Cell c : floor) {
            mx += c.centerX();
            mz += c.centerZ();
        }
        mx /= floor.size();
        mz /= floor.size();
        double[] nearest = new double[candidates.size()];
        int first = 0;
        double far = -1;
        for (int i = 0; i < candidates.size(); i++) {
            double d = dist2(candidates.get(i).centerX(), candidates.get(i).centerZ(), mx, mz);
            if (d > far + 1e-9) {
                far = d;
                first = i;
            }
        }
        java.util.Arrays.fill(nearest, Double.MAX_VALUE);
        int pick = first;
        while (out.size() < SPAWNS) {
            Cell chosen = candidates.get(pick);
            out.add(chosen);
            int next = -1;
            double best = -1;
            for (int i = 0; i < candidates.size(); i++) {
                Cell c = candidates.get(i);
                nearest[i] = Math.min(nearest[i], dist2(c.centerX(), c.centerZ(), chosen.centerX(), chosen.centerZ()));
                if (nearest[i] > best + 1e-9) {
                    best = nearest[i];
                    next = i;
                }
            }
            if (next < 0 || best < SPAWN_APART * SPAWN_APART) {
                break;
            }
            pick = next;
        }
        return out;
    }

    /**
     * Sixteen places in the gallery, four on each side, on the walk between its rails, each facing
     * the middle; in the order north, east, south, west, then round again, so players put there one
     * after another are spread round it.
     */
    static List<ArenaSite.Spot> gallerySpots(Box box, int galleryY) {
        double y = galleryY + 1;
        double mid = GALLERY_WIDTH / 2.0;
        int[] along = {10, 19, 28, 37};
        double cx = (box.minX() + box.maxX() + 1) / 2.0;
        double cz = (box.minZ() + box.maxZ() + 1) / 2.0;
        List<ArenaSite.Spot> out = new ArrayList<>();
        for (int t : along) {
            double[][] sides = {
                    {box.minX() + t + 0.5, box.minZ() + mid},
                    {box.maxX() + 1 - mid, box.minZ() + t + 0.5},
                    {box.maxX() + 1 - t - 0.5, box.maxZ() + 1 - mid},
                    {box.minX() + mid, box.maxZ() + 1 - t - 0.5}};
            for (double[] s : sides) {
                out.add(new ArenaSite.Spot(s[0], y, s[1], ArenaSite.yaw(cx - s[0], cz - s[1])));
            }
        }
        return out;
    }

    /** "Ring with an island, disc and plus" for players (the week's shapes, top first). */
    public static String shapesText(List<String> shapes) {
        if (shapes == null || shapes.isEmpty()) {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (String s : shapes) {
            Shape shape = Shape.of(s);
            names.add(shape == null ? String.valueOf(s).toLowerCase(Locale.ROOT) : shape.label());
        }
        String first = names.get(0);
        names.set(0, Character.toUpperCase(first.charAt(0)) + first.substring(1));
        if (names.size() == 1) {
            return names.get(0);
        }
        return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.get(names.size() - 1);
    }

    private static double dist2(double ax, double az, double bx, double bz) {
        double dx = ax - bx;
        double dz = az - bz;
        return dx * dx + dz * dz;
    }
}
