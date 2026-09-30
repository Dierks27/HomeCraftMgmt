package com.dierks.homecraft.games.gen.golf;

import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.GenFailed;
import com.dierks.homecraft.games.gen.api.Palette;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlannedGolf;
import com.dierks.homecraft.games.gen.api.Putt;
import com.dierks.homecraft.games.gen.api.SignText;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.golf.GolfCourse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adventure Golf holes drawn by hand for the v3 golf tests (Course Variety §3.8), before the
 * templates that draw them exist: a hole is a little map, one character a column, rows running
 * along +Z and characters along +X, drawn into a plot of Golf of the Week's half A.
 *
 * <pre>
 *   ' '        nothing
 *   '0'-'4'    lane at level 0-4 (half blocks above T: 0 turf, 1 a slab, 2 a block up, 4 two up)
 *   't' 'c'    the tee, the cup (on level-0 lane; {@link Drawn#tee}/{@link Drawn#cup} put them on others)
 *   'i' 's'    level-0 lane of ice, of flush sand (smooth sandstone)
 *   'k'        a sunken bunker cell: a sand slab at T - 1 (its top T - 0.5)
 *   '~'        a pond: still water at T - 1 over blue concrete
 *   '#' '=' '%' '&'   a wall of stripped spruce from T - 1, its top at T + 1, 2, 3 or 4
 *   'x'        a slime wall, its top at T + 1
 *   'L'        a log trunk from T - 1, its top at T + 3 (leaves are {@link Drawn#tree}'s)
 * </pre>
 * The hole's bounds are the map's own columns (walls included), from T - 3 to T + 4; a plan puts
 * each tee sign on the column two behind its tee (as the templates do). Public so the
 * engine's tests can build an Adventure plan too.
 */
public final class AdventureKit {

    /** Golf of the Week's half A at the shipped origin, T and the first plot. */
    public static final Box HALF = Slots.DAILY_GOLF.half('A');
    public static final int TURF = HALF.minY() + GolfPlanner.TURF_ABOVE_FLOOR;

    private AdventureKit() {
    }

    /** One hand-drawn hole: its blocks (by position), its tee and cup, its bounds. */
    public static final class Drawn {

        final int plotX;
        final int plotZ;
        final int turf;
        /** Every block, by {@link Palette#blockKey}; a leaf is "LEAF:wood" until {@link #blocks()}. */
        final Map<Long, String> blocks = new LinkedHashMap<>();
        final Map<Long, int[]> at = new HashMap<>();
        /** Per map column: its level (lane), or -1 (not lane). */
        final Map<Long, Integer> level = new HashMap<>();
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        int teeX = Integer.MIN_VALUE;
        int teeZ;
        double teeY;
        int cupX = Integer.MIN_VALUE;
        int cupY;
        int cupZ;
        int maxY;

        Drawn(int plotX, int plotZ, int turf) {
            this.plotX = plotX;
            this.plotZ = plotZ;
            this.turf = turf;
            this.maxY = turf + 4;
        }

        /** World x of map column {@code lx}. */
        public int x(int lx) {
            return plotX + 1 + lx;
        }

        /** World z of map row {@code lz}. */
        public int z(int lz) {
            return plotZ + 1 + lz;
        }

        /** Put a block at world (x, y, z) ({@code null}: take it away). */
        public Drawn set(int x, int y, int z, String blockData) {
            long k = Palette.blockKey(x, y, z);
            if (blockData == null) {
                blocks.remove(k);
                at.remove(k);
            } else {
                blocks.put(k, blockData);
                at.put(k, new int[]{x, y, z});
            }
            return this;
        }

        /** The block at world (x, y, z), or {@code null}. */
        public String get(int x, int y, int z) {
            return blocks.get(Palette.blockKey(x, y, z));
        }

        /** The tee on map cell (lx, lz), standing on its lane's surface. */
        public Drawn tee(int lx, int lz) {
            int lvl = level.getOrDefault(Palette.blockKey(x(lx), 0, z(lz)), -1);
            if (lvl < 0) {
                throw new IllegalStateException("the tee goes on the lane");
            }
            teeX = x(lx);
            teeZ = z(lz);
            teeY = turf + lvl / 2.0;
            int y = (int) Math.ceil(teeY) - 1;
            if (lvl % 2 == 0) {
                set(teeX, y, teeZ, Palette.TEE);
            }
            return this;
        }

        /** The cup on map cell (lx, lz) of an even level: the lane block there sunk one, a flag three up. */
        public Drawn cup(int lx, int lz) {
            int lvl = level.getOrDefault(Palette.blockKey(x(lx), 0, z(lz)), -1);
            if (lvl < 0 || lvl % 2 != 0) {
                throw new IllegalStateException("the cup goes on a whole-block level of the lane");
            }
            cupX = x(lx);
            cupZ = z(lz);
            int ring = turf + lvl / 2;
            set(cupX, ring - 1, cupZ, null);
            cupY = ring - 2;
            set(cupX, cupY, cupZ, Palette.CUP);
            set(cupX, ring + 3, cupZ, Palette.FLAG);
            return this;
        }

        /**
         * A tree on map cell (lx, lz): a log trunk from T - 1 to T + 2 (top T + 3), leaves at T + 3
         * in the 3 x 3 round and over the trunk, and at T + 4 in a plus.
         */
        public Drawn tree(int lx, int lz, String wood) {
            int x = x(lx);
            int z = z(lz);
            for (int y = turf - 1; y <= turf + 2; y++) {
                set(x, y, z, Palette.log(wood));
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    leaf(x + dx, turf + 3, z + dz, wood);
                    if (dx == 0 || dz == 0) {
                        leaf(x + dx, turf + 4, z + dz, wood);
                    }
                }
            }
            return this;
        }

        /** A leaf of {@code wood} at world (x, y, z); its distance is worked out when the blocks are read. */
        public Drawn leaf(int x, int y, int z, String wood) {
            return set(x, y, z, "LEAF:" + wood);
        }

        /** Make the bounds reach up to T + {@code up} (a flag over a high cup). */
        public Drawn top(int up) {
            maxY = turf + up;
            return this;
        }

        /** Every block with its real text: leaves get the distance vanilla gives them. */
        public Map<Long, String> blocks() {
            List<int[]> logs = new ArrayList<>();
            List<int[]> leaves = new ArrayList<>();
            for (Map.Entry<Long, String> e : blocks.entrySet()) {
                int[] p = at.get(e.getKey());
                if (e.getValue().startsWith("LEAF:")) {
                    leaves.add(p);
                } else if (Palette.holdsLeaves(e.getValue())) {
                    logs.add(p);
                }
            }
            Map<Long, Integer> d = Palette.leafDistances(logs, leaves);
            Map<Long, String> out = new LinkedHashMap<>();
            for (Map.Entry<Long, String> e : blocks.entrySet()) {
                String b = e.getValue();
                out.put(e.getKey(), b.startsWith("LEAF:") ? Palette.leaves(b.substring(5), d.get(e.getKey())) : b);
            }
            return out;
        }

        /** Where block {@code key} is. */
        int[] where(long key) {
            return at.get(key);
        }

        /** Every block as ({x, y, z}, its real text), leaves with their distances. */
        public List<Map.Entry<int[], String>> placed() {
            List<Map.Entry<int[], String>> out = new ArrayList<>();
            for (Map.Entry<Long, String> e : blocks().entrySet()) {
                out.add(Map.entry(at.get(e.getKey()), e.getValue()));
            }
            return out;
        }

        /** The hole to play at {@code par}. */
        public GolfCourse.Hole hole(int par) {
            return new GolfCourse.Hole(new GolfCourse.Tee(teeX + 0.5, teeY, teeZ + 0.5, 0f),
                    new GolfCourse.Spot(cupX, cupY, cupZ), par, new GolfCourse.Spot(minX, turf - 3, minZ),
                    new GolfCourse.Spot(maxX, maxY, maxZ));
        }

        /** The hole's bounds as a box. */
        public Box bounds() {
            return new Box(minX, turf - 3, minZ, maxX, maxY, maxZ);
        }

        /** A grid of this hole's blocks over its plot (and 2 more round it), T - 4 to T + 11. */
        public PlanBlocks grid() {
            PlanBlocks g = new PlanBlocks(new Box(plotX - 2, turf - 4, plotZ - 2, plotX + HoleTemplate.PLOT_X + 1,
                    turf + 11, plotZ + HoleTemplate.PLOT_Z + 1));
            for (Map.Entry<Long, String> e : blocks().entrySet()) {
                int[] p = at.get(e.getKey());
                g.set(p[0], p[1], p[2], PlanBlocks.code(e.getValue()));
            }
            return g;
        }

        /** The expert's line on this hole (safe on a pond hole), or {@code null} when there is none. */
        public List<Putt> line() {
            PlanBlocks g = grid();
            GolfCourse.Hole h = hole(GolfCourse.MIN_PAR);
            try {
                ExpertSearch.Result r = SafeExpert.search(g, h, LaneMap.of(g, h, 3), ExpertSearch.MAX_DEPTH,
                        Work.unlimited());
                return r.found() ? r.witness() : null;
            } catch (GenFailed never) {
                throw new IllegalStateException(never);
            }
        }
    }

    /** Draw {@code rows} into Golf of the Week's half A, plot {@code plot} (0-8). */
    public static Drawn draw(int plot, String... rows) {
        int[] p = GolfPlanner.plot(HALF, plot);
        return draw(p[0], p[1], TURF, rows);
    }

    /** Draw {@code rows} into the plot whose corner is (plotX, plotZ), T at {@code turf}. */
    public static Drawn draw(int plotX, int plotZ, int turf, String... rows) {
        Drawn d = new Drawn(plotX, plotZ, turf);
        int tee = -1;
        int teeRow = -1;
        int cup = -1;
        int cupRow = -1;
        for (int lz = 0; lz < rows.length; lz++) {
            String row = rows[lz];
            for (int lx = 0; lx < row.length(); lx++) {
                char ch = row.charAt(lx);
                if (ch == ' ') {
                    continue;
                }
                int x = d.x(lx);
                int z = d.z(lz);
                d.minX = Math.min(d.minX, x);
                d.maxX = Math.max(d.maxX, x);
                d.minZ = Math.min(d.minZ, z);
                d.maxZ = Math.max(d.maxZ, z);
                int t = turf;
                switch (ch) {
                    case '0', 't', 'c' -> {
                        lane(d, x, z, 0, turf(x, z));
                        if (ch == 't') {
                            tee = lx;
                            teeRow = lz;
                        } else if (ch == 'c') {
                            cup = lx;
                            cupRow = lz;
                        }
                    }
                    case '1', '2', '3', '4' -> lane(d, x, z, ch - '0', turf(x, z));
                    case 'i' -> lane(d, x, z, 0, Palette.GOLF_ICE);
                    case 's' -> lane(d, x, z, 0, Palette.SAND);
                    case 'k' -> {
                        d.set(x, t - 1, z, Palette.SAND_SLAB);
                        d.level.put(Palette.blockKey(x, 0, z), -1); // a bunker: lane, but not a level to stand a tee on
                    }
                    case '~' -> {
                        d.set(x, t - 2, z, "minecraft:blue_concrete");
                        d.set(x, t - 1, z, "minecraft:water[level=0]");
                    }
                    case '#' -> wall(d, x, z, 1, Palette.GOLF_WALL);
                    case '=' -> wall(d, x, z, 2, Palette.GOLF_WALL);
                    case '%' -> wall(d, x, z, 3, Palette.GOLF_WALL);
                    case '&' -> wall(d, x, z, 4, Palette.GOLF_WALL);
                    case 'x' -> wall(d, x, z, 1, Palette.BUMPER);
                    case 'L' -> {
                        for (int y = t - 1; y <= t + 2; y++) {
                            d.set(x, y, z, Palette.log("oak"));
                        }
                    }
                    default -> throw new IllegalArgumentException("no such map character: " + ch);
                }
            }
        }
        if (tee >= 0) {
            d.tee(tee, teeRow);
        }
        if (cup >= 0) {
            d.cup(cup, cupRow);
        }
        return d;
    }

    private static void lane(Drawn d, int x, int z, int level, String top) {
        int t = d.turf;
        d.level.put(Palette.blockKey(x, 0, z), level);
        String dark = Palette.TURF_DARK;
        switch (level) {
            case 0 -> d.set(x, t - 1, z, top);
            case 1 -> {
                d.set(x, t - 1, z, dark);
                d.set(x, t, z, Palette.RAMP);
            }
            case 2 -> {
                d.set(x, t - 1, z, dark);
                d.set(x, t, z, top);
            }
            case 3 -> {
                d.set(x, t - 1, z, dark);
                d.set(x, t, z, dark);
                d.set(x, t + 1, z, Palette.RAMP);
            }
            default -> {
                d.set(x, t - 1, z, dark);
                d.set(x, t, z, dark);
                d.set(x, t + 1, z, top);
            }
        }
    }

    private static void wall(Drawn d, int x, int z, int above, String block) {
        for (int y = d.turf - 1; y < d.turf + above; y++) {
            d.set(x, y, z, block);
        }
    }

    private static String turf(int x, int z) {
        return (((x >> 1) + (z >> 1)) & 1) == 0 ? Palette.TURF_LIGHT : Palette.TURF_DARK;
    }

    /**
     * A plan of these holes (in playing order) at golf planner version {@code algo}, as the planner
     * would assemble it: every block, a tee sign behind each tee, par E + 1 from each line.
     *
     * @param lines each hole's witness line
     */
    public static Plan plan(int algo, List<Drawn> holes, List<List<Putt>> lines) {
        return plan(algo, holes, lines, List.of());
    }

    /** {@link #plan(int, List, List)} with scenery: extra blocks {x, y, z} and their block data. */
    public static Plan plan(int algo, List<Drawn> holes, List<List<Putt>> lines, List<Map.Entry<int[], String>> extra) {
        return plan(Slots.DAILY_GOLF, HALF, 7, algo, holes, lines, extra);
    }

    /** The same for {@code slot}'s {@code half}, from {@code seed}. */
    public static Plan plan(Slots.Def slot, Box half, long seed, int algo, List<Drawn> holes, List<List<Putt>> lines,
                            List<Map.Entry<int[], String>> extra) {
        List<String> palette = new ArrayList<>();
        Map<String, Short> index = new HashMap<>();
        List<BlockOp> ops = new ArrayList<>();
        List<SignText> signs = new ArrayList<>();
        List<GolfCourse.Hole> course = new ArrayList<>();
        List<Integer> attempts = new ArrayList<>();
        List<Integer> expert = new ArrayList<>();
        List<Box> keep = new ArrayList<>();
        for (int i = 0; i < holes.size(); i++) {
            Drawn d = holes.get(i);
            for (Map.Entry<Long, String> e : d.blocks().entrySet()) {
                int[] p = d.where(e.getKey());
                ops.add(new BlockOp(p[0], p[1], p[2], state(palette, index, e.getValue())));
            }
            int par = GolfPlanner.par(lines.get(i).size());
            int sy = d.turf;
            while (d.get(d.teeX, sy, d.teeZ - 2) != null) {
                sy++;
            }
            signs.add(new SignText(d.teeX, sy, d.teeZ - 2, Palette.sign(0), GenCopy.golfTee(i + 1, par)));
            course.add(d.hole(par));
            attempts.add(0);
            expert.add(lines.get(i).size());
            keep.add(d.bounds());
        }
        for (Map.Entry<int[], String> e : extra) {
            int[] p = e.getKey();
            ops.add(new BlockOp(p[0], p[1], p[2], state(palette, index, e.getValue())));
        }
        GolfCourse gc = new GolfCourse(slot.id(), slot.name(), "", true, 1, course);
        PlannedGolf planned = new PlannedGolf(gc, attempts, lines, expert, List.of());
        return Plan.of(slot.id(), algo, seed, half, palette, ops, signs, keep, planned, List.of(), 0);
    }

    private static short state(List<String> palette, Map<String, Short> index, String block) {
        Short s = index.get(block);
        if (s == null) {
            s = (short) palette.size();
            palette.add(block);
            index.put(block, s);
        }
        return s;
    }
}
