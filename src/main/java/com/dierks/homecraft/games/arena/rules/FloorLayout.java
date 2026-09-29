package com.dierks.homecraft.games.arena.rules;

import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Plan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The week's floors as the round rules see them (EVENTS-DROPPER-SPEC §B.3.2): which cells each
 * floor has, where players start, how low is "out", and the sudden-death rings.
 *
 * <p>It is built from the week's {@link Plan} ({@link #fromPlan}), the same plan the reset
 * converges the box to, so the cells a round may turn red are exactly the cells the reset puts
 * back. Immutable and pure; a new week makes a new layout.
 *
 * <p><b>Rings.</b> Sudden death takes "the outer ring of every floor, one ring every 2 s". A
 * floor's rings are its cells grouped by whole-block distance from the middle of that floor's own
 * footprint, numbered from the outside in, with empty distances skipped: so every 2 s something
 * really falls (a ring-with-island floor loses its ring, then its island) and a floor of {@code n}
 * rings is gone after {@code n} steps. That is what makes every round end.
 *
 * <p><b>Why floors must be at least 5 apart.</b> A jump lifts the feet about 1.25 and a player is
 * 1.8 tall, so from a floor top a head reaches about 3.05 higher; the floor above starts
 * {@code gap - 1} above this top. At 5 or more nobody can bump the floor above, so a player on a
 * floor only ever touches that floor (the site uses 8).
 */
public final class FloorLayout {

    /** The least whole-block distance between two floors' y (see the class comment). */
    public static final int MIN_GAP = 5;
    /** The widest a layout may be along x or z (the arena box is 48). */
    public static final int MAX_SPAN = 256;

    /**
     * One floor: its block height and its cells, sorted and without repeats.
     *
     * @param y     the floor blocks' y; players stand at {@code y + 1}
     * @param cells the floor's cells
     */
    public record Layer(int y, List<Cell> cells) {

        public Layer {
            if (cells == null || cells.isEmpty()) {
                throw new IllegalArgumentException("a floor at y " + y + " has no cells");
            }
            TreeSet<Cell> sorted = new TreeSet<>();
            for (Cell c : cells) {
                if (c == null) {
                    throw new IllegalArgumentException("a floor at y " + y + " has a missing cell");
                }
                if (!sorted.add(c)) {
                    throw new IllegalArgumentException("a floor at y " + y + " has the cell " + c.x() + ","
                            + c.z() + " twice");
                }
            }
            cells = List.copyOf(sorted);
        }

        /** Where players stand on it: the top of its blocks. */
        public int top() {
            return y + 1;
        }
    }

    private final List<Layer> layers;
    private final int outY;
    private final List<Cell> spawns;
    private final int minX;
    private final int minZ;
    private final int sizeX;
    private final int sizeZ;
    /** Per layer: grid index to cell ordinal, or -1. */
    private final int[][] grid;
    /** Per layer, per cell ordinal: its ring counted from the outside (0 = the outermost). */
    private final int[][] rings;
    /** Per layer: how many rings. */
    private final int[] ringCount;

    /**
     * @param layers the floors, in any order (kept top first)
     * @param outY   a player whose feet are below this is out; at or below the bottom floor's blocks
     * @param spawns where players start: cells of the top floor, at least one
     */
    public FloorLayout(List<Layer> layers, int outY, List<Cell> spawns) {
        if (layers == null || layers.isEmpty()) {
            throw new IllegalArgumentException("a layout has at least one floor");
        }
        List<Layer> sorted = new ArrayList<>(layers);
        sorted.sort(Comparator.comparingInt(Layer::y).reversed());
        for (int i = 1; i < sorted.size(); i++) {
            int gap = sorted.get(i - 1).y() - sorted.get(i).y();
            if (gap < MIN_GAP) {
                throw new IllegalArgumentException("floors at y " + sorted.get(i - 1).y() + " and " + sorted.get(i).y()
                        + " are " + gap + " apart; they must be at least " + MIN_GAP + " apart");
            }
        }
        this.layers = List.copyOf(sorted);
        int bottom = this.layers.get(this.layers.size() - 1).y();
        if (outY > bottom) {
            throw new IllegalArgumentException("out (y " + outY + ") must be at or below the bottom floor (y "
                    + bottom + "), or a player could be out while standing on it");
        }
        this.outY = outY;

        int loX = Integer.MAX_VALUE;
        int loZ = Integer.MAX_VALUE;
        int hiX = Integer.MIN_VALUE;
        int hiZ = Integer.MIN_VALUE;
        for (Layer l : this.layers) {
            for (Cell c : l.cells()) {
                loX = Math.min(loX, c.x());
                loZ = Math.min(loZ, c.z());
                hiX = Math.max(hiX, c.x());
                hiZ = Math.max(hiZ, c.z());
            }
        }
        if ((long) hiX - loX + 1 > MAX_SPAN || (long) hiZ - loZ + 1 > MAX_SPAN) {
            throw new IllegalArgumentException("a layout is at most " + MAX_SPAN + " blocks across");
        }
        this.minX = loX;
        this.minZ = loZ;
        this.sizeX = hiX - loX + 1;
        this.sizeZ = hiZ - loZ + 1;

        this.grid = new int[this.layers.size()][];
        this.rings = new int[this.layers.size()][];
        this.ringCount = new int[this.layers.size()];
        for (int i = 0; i < this.layers.size(); i++) {
            List<Cell> cells = this.layers.get(i).cells();
            int[] g = new int[sizeX * sizeZ];
            Arrays.fill(g, -1);
            for (int n = 0; n < cells.size(); n++) {
                g[slot(cells.get(n).x(), cells.get(n).z())] = n;
            }
            grid[i] = g;
            rings(i, cells);
        }

        if (spawns == null || spawns.isEmpty()) {
            throw new IllegalArgumentException("a layout has at least one spawn");
        }
        for (Cell s : spawns) {
            if (s == null || !isCell(0, s.x(), s.z())) {
                throw new IllegalArgumentException("a spawn must be a cell of the top floor: " + s);
            }
        }
        this.spawns = List.copyOf(spawns);
    }

    /**
     * The layout of a week's plan: every op at one of {@code floorYs} is a floor cell (the site puts
     * nothing else at those heights: no walls, the gallery is higher). {@code spawns} come from the
     * planner, which knows where it put them.
     */
    public static FloorLayout fromPlan(Plan plan, List<Integer> floorYs, int outY, List<Cell> spawns) {
        if (plan == null || floorYs == null || floorYs.isEmpty()) {
            throw new IllegalArgumentException("a layout needs a plan and its floor heights");
        }
        TreeMap<Integer, List<Cell>> byY = new TreeMap<>();
        for (Integer y : floorYs) {
            if (y == null || byY.put(y, new ArrayList<>()) != null) {
                throw new IllegalArgumentException("floor heights are distinct numbers: " + floorYs);
            }
        }
        for (BlockOp op : plan.ops()) {
            List<Cell> cells = byY.get(op.y());
            if (cells != null) {
                cells.add(new Cell(op.x(), op.z()));
            }
        }
        List<Layer> layers = new ArrayList<>();
        byY.forEach((y, cells) -> layers.add(new Layer(y, cells)));
        return new FloorLayout(layers, outY, spawns);
    }

    // ---- reading it -----------------------------------------------------------------------------

    /** The floors, top first. */
    public List<Layer> layers() {
        return layers;
    }

    public int layerCount() {
        return layers.size();
    }

    public Layer layer(int i) {
        return layers.get(i);
    }

    /** Where players stand on floor {@code i}. */
    public int topY(int i) {
        return layers.get(i).top();
    }

    /** A player whose feet are below this is out. */
    public int outY() {
        return outY;
    }

    /** Where players start, on the top floor. */
    public List<Cell> spawns() {
        return spawns;
    }

    /** The floor whose blocks are at {@code y}, or -1. */
    public int layerAtY(int y) {
        for (int i = 0; i < layers.size(); i++) {
            if (layers.get(i).y() == y) {
                return i;
            }
        }
        return -1;
    }

    /** Whether (x, z) is a cell of floor {@code layer}. */
    public boolean isCell(int layer, int x, int z) {
        return ordinal(layer, x, z) >= 0;
    }

    /** Whether block (x, y, z) is a planned floor cell: the only blocks a round may change. */
    public boolean isFloorCell(int x, int y, int z) {
        int layer = layerAtY(y);
        return layer >= 0 && isCell(layer, x, z);
    }

    /** Cell (x, z)'s index in {@code layer(layer).cells()}, or -1 when it isn't one. */
    public int ordinal(int layer, int x, int z) {
        if (layer < 0 || layer >= layers.size()) {
            return -1;
        }
        int dx = x - minX;
        int dz = z - minZ;
        if (dx < 0 || dz < 0 || dx >= sizeX || dz >= sizeZ) {
            return -1;
        }
        return grid[layer][dx * sizeZ + dz];
    }

    /** How many cells floor {@code layer} has. */
    public int cellCount(int layer) {
        return layers.get(layer).cells().size();
    }

    /** How many cells all the floors have. */
    public int totalCells() {
        int n = 0;
        for (Layer l : layers) {
            n += l.cells().size();
        }
        return n;
    }

    /** Which sudden-death ring a cell is in, 0 being the outermost. */
    public int ring(int layer, int ordinal) {
        return rings[layer][ordinal];
    }

    /** How many sudden-death rings floor {@code layer} has. */
    public int ringCount(int layer) {
        return ringCount[layer];
    }

    /** The most rings any floor has: sudden death is over after this many steps. */
    public int maxRings() {
        int m = 0;
        for (int r : ringCount) {
            m = Math.max(m, r);
        }
        return m;
    }

    // ---- building it ----------------------------------------------------------------------------

    private int slot(int x, int z) {
        return (x - minX) * sizeZ + (z - minZ);
    }

    /**
     * Rings from the outside in: each cell's whole-block distance from the middle of its floor's
     * footprint, the distinct distances ranked from the largest. Integer maths throughout (twice the
     * coordinates, an exact square root), so every host ranks every cell the same.
     */
    private void rings(int layer, List<Cell> cells) {
        int loX = Integer.MAX_VALUE;
        int loZ = Integer.MAX_VALUE;
        int hiX = Integer.MIN_VALUE;
        int hiZ = Integer.MIN_VALUE;
        for (Cell c : cells) {
            loX = Math.min(loX, c.x());
            loZ = Math.min(loZ, c.z());
            hiX = Math.max(hiX, c.x());
            hiZ = Math.max(hiZ, c.z());
        }
        long cx2 = (long) loX + hiX + 1;
        long cz2 = (long) loZ + hiZ + 1;
        int[] dist = new int[cells.size()];
        TreeSet<Integer> distinct = new TreeSet<>(Comparator.reverseOrder());
        for (int n = 0; n < cells.size(); n++) {
            long dx = 2L * cells.get(n).x() + 1 - cx2;
            long dz = 2L * cells.get(n).z() + 1 - cz2;
            dist[n] = isqrt((dx * dx + dz * dz) / 4);
            distinct.add(dist[n]);
        }
        List<Integer> order = new ArrayList<>(distinct);
        int[] r = new int[cells.size()];
        for (int n = 0; n < cells.size(); n++) {
            r[n] = order.indexOf(dist[n]);
        }
        rings[layer] = r;
        ringCount[layer] = order.size();
    }

    /** The whole square root, exactly: the largest k with k * k &lt;= n. */
    static int isqrt(long n) {
        if (n < 0) {
            throw new IllegalArgumentException("no square root of " + n);
        }
        long k = (long) Math.sqrt((double) n);
        while (k * k > n) {
            k--;
        }
        while ((k + 1) * (k + 1) <= n) {
            k++;
        }
        return (int) k;
    }
}
