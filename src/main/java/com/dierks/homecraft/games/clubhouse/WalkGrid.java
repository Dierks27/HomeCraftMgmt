package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Plan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Where a body can walk in the generated Clubhouse (pure; the owner: "I don't know if there is a way for them to walk
 * in instead of just disappearing"): a grid of cells read from the plan's own blocks, and the paths the NPC regulars
 * walk on it.
 *
 * <p><b>A cell</b> is a block a body can stand in: under the feet something with a flat top (a full block, a top slab,
 * the carpet's own sixteenth, or a chair's or a stool's half), and the feet and the head free (air, the invisible
 * light block, a banner, an open trapdoor; a carpet at the feet). A chair or a stool is a <b>seat</b> cell: a path
 * may end on one (a regular sits down) or start on one (it gets up), but never goes over one, and a body getting up
 * only steps down off it, so nobody climbs a chair onto a table or a stool onto the bar.
 *
 * <p><b>Neighbours</b> are the eight cells round a cell, a step of at most half a block up or down; a diagonal only
 * where both the cells beside it are free too (no cutting a corner past a column or a table). The cells that join
 * up make a <b>floor</b>: the ground floor (the main room, through the archways into the side rooms), the Game Parlor
 * upstairs, the secret room; a path never leaves its floor.
 *
 * <p><b>Paths</b> are A* over those steps, each step costing a little more beside a wall or a table, so a walk keeps
 * to the middle of an aisle and an archway ({@link #path}); then pulled straight where a body (with a margin) fits
 * the whole way ({@link #smooth}), so a regular walks in lines and turns, not grid zig-zags.
 *
 * <p>Built once per plan ({@link #of}, by the plan's hash) and read only after: some 1,600 cells for the whole
 * Clubhouse, a few tens of milliseconds the first time (cold), and a path a fraction of a millisecond.
 */
final class WalkGrid {

    /** The highest step up or down between two neighbours (blocks). */
    static final double STEP = 0.5;
    /** A carpet's top over its block's bottom. */
    static final double CARPET = RegularSpots.CARPET;
    /** A chair's or a stool's top. */
    static final double HALF = 0.5;
    /** How far a cell may be off a point's height and still be "the cell there". */
    static final double NEAR = 0.6;
    /** Extra cost of a step onto a cell beside something (a wall, a column, a table), and one a block further off. */
    static final double BESIDE = 0.8;
    static final double CLOSE = 0.25;
    /** Extra cost of a step through an armchair's arm (an open trapdoor): only if there's no other way. */
    static final double FURNITURE = 6;
    /** Half a body's width and a margin: a straight line is only taken where this much each side is on cells. */
    static final double BODY = 0.55;
    /** The most cells a path search looks at before it gives up (the whole Clubhouse is a few thousand). */
    static final int MAX_LOOK = 40_000;

    private static final double EPS = 1e-6;
    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    /**
     * A block a body can stand in.
     *
     * @param h         its feet's height (the floor's top: the block's bottom, a carpet's or a seat's top)
     * @param seat      a chair or a stool (a path's end, never its middle)
     * @param furniture an armchair's arm (an open trapdoor) at the feet or the head: dear to go through
     */
    record Cell(int x, int y, int z, double h, boolean seat, boolean furniture) {
        double cx() {
            return x + 0.5;
        }

        double cz() {
            return z + 0.5;
        }

        long key() {
            return RegularSpots.key(x, y, z);
        }
    }

    /** A point a body walks through: its feet. */
    record Point(double x, double y, double z) {
    }

    private static final Map<String, WalkGrid> CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(4, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, WalkGrid> eldest) {
                    return size() > 4;
                }
            });

    private final Box box;
    private final Map<Long, Cell> cells;
    /** The cells of each column (x, z), low to high. */
    private final Map<Long, List<Cell>> columns;
    /** Each cell's floor ({@code -1}: none, a seat nobody can reach). */
    private final Map<Long, Integer> floors;
    /** Each cell's extra cost for being beside something. */
    private final Map<Long, Double> beside;
    private final long buildNanos;

    private WalkGrid(Box box, Map<Long, Cell> cells, long started) {
        this.box = box;
        this.cells = cells;
        this.columns = new HashMap<>();
        for (Cell c : cells.values()) {
            columns.computeIfAbsent(column(c.x(), c.z()), k -> new ArrayList<>()).add(c);
        }
        for (List<Cell> col : columns.values()) {
            col.sort((a, b) -> Double.compare(a.h(), b.h()));
        }
        this.floors = new HashMap<>();
        this.beside = new HashMap<>();
        label();
        for (Cell c : cells.values()) {
            beside.put(c.key(), besideCost(c));
        }
        this.buildNanos = System.nanoTime() - started;
    }

    /** The grid of {@code plan} (its box), built once per plan hash. */
    static WalkGrid of(Plan plan) {
        String key = plan.hash() + "@" + plan.half();
        WalkGrid g = CACHE.get(key);
        if (g == null) {
            g = build(RegularSpots.blocks(plan), plan.half());
            CACHE.put(key, g);
        }
        return g;
    }

    /** The grid of {@code blocks} (by {@link RegularSpots#key}; missing: air) inside {@code box}. */
    static WalkGrid build(Map<Long, String> blocks, Box box) {
        long started = System.nanoTime();
        Map<Long, Cell> cells = new HashMap<>();
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int y = box.minY() + 1; y < box.maxY(); y++) {
                    Cell c = cell(blocks, x, y, z);
                    if (c != null) {
                        cells.put(c.key(), c);
                    }
                }
            }
        }
        return new WalkGrid(box, cells, started);
    }

    private static Cell cell(Map<Long, String> b, int x, int y, int z) {
        String feet = b.get(RegularSpots.key(x, y, z));
        String head = b.get(RegularSpots.key(x, y + 1, z));
        if (!free(head)) {
            return null;
        }
        boolean arm = trapdoor(head);
        if (free(feet)) {
            return flatTop(b.get(RegularSpots.key(x, y - 1, z)))
                    ? new Cell(x, y, z, y, false, arm || trapdoor(feet)) : null;
        }
        if (carpet(feet)) {
            String under = b.get(RegularSpots.key(x, y - 1, z));
            return under != null && !free(under) ? new Cell(x, y, z, y + CARPET, false, arm) : null;
        }
        if (halfStep(feet)) {
            return new Cell(x, y, z, y + HALF, true, arm);
        }
        return null;
    }

    // ---- what the blocks are to a body -----------------------------------------------------------------

    /** Nothing a body bumps into: air, the invisible light block, a banner, an open trapdoor (an armchair's arm). */
    static boolean free(String block) {
        if (block == null) {
            return true;
        }
        String id = ClubhousePlanner.id(block);
        return id.equals(ClubhousePlanner.AIR) || id.equals("minecraft:cave_air") || id.equals("minecraft:void_air")
                || id.equals("minecraft:light") || id.endsWith("_banner") || trapdoor(block);
    }

    private static boolean trapdoor(String block) {
        return block != null && ClubhousePlanner.id(block).endsWith("_trapdoor") && block.contains("open=true");
    }

    static boolean carpet(String block) {
        return block != null && ClubhousePlanner.id(block).endsWith("_carpet");
    }

    /** A chair (bottom stairs) or a stool (a bottom slab): its top is half a block up. */
    static boolean halfStep(String block) {
        if (block == null) {
            return false;
        }
        String id = ClubhousePlanner.id(block);
        return (id.endsWith("_stairs") && !block.contains("half=top"))
                || (id.endsWith("_slab") && block.contains("type=bottom"));
    }

    /**
     * A block with a whole flat top to stand on: anything solid but a carpet, a half step, and what the planner says
     * nothing stands on (glass, panes, fences, lanterns, the barrier over Mob Races' table), and the odd shapes.
     */
    static boolean flatTop(String block) {
        if (free(block) || carpet(block) || halfStep(block) || ClubhousePlanner.noFooting(block)) {
            return false;
        }
        String id = ClubhousePlanner.id(block);
        return !(id.endsWith("campfire") || id.equals("minecraft:brewing_stand") || id.endsWith("_trapdoor")
                || id.endsWith("_door") || id.endsWith("_sign") || id.endsWith("_wall") || id.endsWith("_button")
                || id.endsWith("_pressure_plate") || id.endsWith("_bed") || id.endsWith("_head")
                || id.endsWith("_skull") || id.endsWith("_shelf") || id.equals("minecraft:lever")
                || id.endsWith("_rail") || id.endsWith("anvil"));
    }

    // ---- reading it ---------------------------------------------------------------------------------------

    int size() {
        return cells.size();
    }

    long buildNanos() {
        return buildNanos;
    }

    Box box() {
        return box;
    }

    Cell cell(int x, int y, int z) {
        return cells.get(RegularSpots.key(x, y, z));
    }

    /** The cell a point is in: its column's cell nearest the point's height, if within {@link #NEAR}. */
    Cell at(double x, double y, double z) {
        List<Cell> col = columns.get(column((int) Math.floor(x), (int) Math.floor(z)));
        if (col == null) {
            return null;
        }
        Cell best = null;
        for (Cell c : col) {
            double d = Math.abs(c.h() - y);
            if (d <= NEAR + EPS && (best == null || d < Math.abs(best.h() - y))) {
                best = c;
            }
        }
        return best;
    }

    Cell at(Point p) {
        return p == null ? null : at(p.x(), p.y(), p.z());
    }

    /** The floor a cell is on ({@code -1}: none). */
    int floor(Cell c) {
        return c == null ? -1 : floors.getOrDefault(c.key(), -1);
    }

    /** Every cell of floor {@code f}. */
    List<Cell> cellsOf(int f) {
        List<Cell> out = new ArrayList<>();
        for (Cell c : cells.values()) {
            if (floor(c) == f) {
                out.add(c);
            }
        }
        return out;
    }

    // ---- steps ---------------------------------------------------------------------------------------------

    /** The cell one step (dx, dz) from {@code a}, at most half a block up or down, or {@code null}. */
    Cell stepTo(Cell a, int dx, int dz) {
        Cell best = null;
        for (int dy = -1; dy <= 1; dy++) {
            Cell c = cells.get(RegularSpots.key(a.x() + dx, a.y() + dy, a.z() + dz));
            if (c != null && Math.abs(c.h() - a.h()) <= STEP + EPS
                    && (best == null || Math.abs(c.h() - a.h()) < Math.abs(best.h() - a.h()))) {
                best = c;
            }
        }
        return best;
    }

    /**
     * The cell a body walks to from {@code a} in direction (dx, dz), or {@code null}: no seat on the way, getting up
     * only down or level, and a diagonal only with both cells beside it free.
     *
     * @param start the path's first cell (the only seat a body steps off)
     * @param goal  the path's last cell (the only seat a body steps onto)
     */
    private Cell walkTo(Cell a, int dx, int dz, Cell start, Cell goal) {
        if (a.seat() && !a.equals(start)) {
            return null;
        }
        Cell c = stepTo(a, dx, dz);
        if (c == null || (c.seat() && !c.equals(goal)) || (a.seat() && c.h() > a.h() + EPS)) {
            return null;
        }
        if (dx != 0 && dz != 0) {
            Cell s1 = stepTo(a, dx, 0);
            Cell s2 = stepTo(a, 0, dz);
            if (s1 == null || s2 == null || s1.seat() || s2.seat()
                    || Math.abs(s1.h() - c.h()) > STEP + EPS || Math.abs(s2.h() - c.h()) > STEP + EPS) {
                return null;
            }
        }
        return c;
    }

    /** The floors: walking cells joined by steps; a seat on the floor of a cell it can be stepped onto from. */
    private void label() {
        int next = 0;
        List<Cell> order = new ArrayList<>(cells.values());
        order.sort((a, b) -> a.y() != b.y() ? Integer.compare(a.y(), b.y())
                : a.x() != b.x() ? Integer.compare(a.x(), b.x()) : Integer.compare(a.z(), b.z()));
        for (Cell seed : order) {
            if (seed.seat() || floors.containsKey(seed.key())) {
                continue;
            }
            int f = next++;
            ArrayDeque<Cell> q = new ArrayDeque<>();
            floors.put(seed.key(), f);
            q.add(seed);
            while (!q.isEmpty()) {
                Cell a = q.poll();
                for (int[] d : DIRS) {
                    Cell c = walkTo(a, d[0], d[1], null, null);
                    if (c != null && !floors.containsKey(c.key())) {
                        floors.put(c.key(), f);
                        q.add(c);
                    }
                }
            }
        }
        for (Cell s : order) {
            if (!s.seat()) {
                continue;
            }
            for (int[] d : DIRS) {
                Cell c = stepTo(s, d[0], d[1]);
                if (c != null && !c.seat() && c.h() <= s.h() + EPS && walkTo(c, -d[0], -d[1], null, s) != null) {
                    floors.put(s.key(), floors.getOrDefault(c.key(), -1));
                    break;
                }
            }
        }
    }

    /** Extra cost for a cell beside something: a missing neighbour right beside it, or one two blocks off. */
    private double besideCost(Cell c) {
        if (c.seat()) {
            return 0;
        }
        for (int[] d : DIRS) {
            Cell n = stepTo(c, d[0], d[1]);
            if (n == null || n.seat()) {
                return BESIDE;
            }
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) == 2 && near(c, dx, dz) == null) {
                    return CLOSE;
                }
            }
        }
        return 0;
    }

    private Cell near(Cell a, int dx, int dz) {
        List<Cell> col = columns.get(column(a.x() + dx, a.z() + dz));
        if (col == null) {
            return null;
        }
        for (Cell c : col) {
            if (!c.seat() && Math.abs(c.h() - a.h()) <= STEP + EPS) {
                return c;
            }
        }
        return null;
    }

    // ---- paths ----------------------------------------------------------------------------------------------

    /**
     * The cheapest walk from {@code from} to {@code to} on one floor (A*, eight ways), never through a cell in
     * {@code avoid} (by {@link Cell#key}; a real player standing there) unless it is the walk's end; {@code null}
     * when there is none.
     */
    List<Cell> path(Cell from, Cell to, Set<Long> avoid) {
        if (from == null || to == null) {
            return null;
        }
        if (from.equals(to)) {
            return List.of(from);
        }
        int f = floor(from);
        if (f < 0 || f != floor(to)) {
            return null;
        }
        Map<Long, Double> cost = new HashMap<>();
        Map<Long, Cell> came = new HashMap<>();
        PriorityQueue<Open> open = new PriorityQueue<>((a, b) -> Double.compare(a.f(), b.f()));
        cost.put(from.key(), 0.0);
        open.add(new Open(guess(from, to), 0.0, from));
        int looked = 0;
        while (!open.isEmpty() && looked < MAX_LOOK) {
            Open top = open.poll();
            Cell a = top.cell();
            if (top.g() > cost.getOrDefault(a.key(), Double.MAX_VALUE) + EPS) {
                continue;
            }
            looked++;
            if (a.equals(to)) {
                List<Cell> out = new ArrayList<>();
                for (Cell c = a; c != null; c = came.get(c.key())) {
                    out.add(c);
                }
                Collections.reverse(out);
                return out;
            }
            for (int[] d : DIRS) {
                Cell c = walkTo(a, d[0], d[1], from, to);
                if (c == null || (avoid != null && avoid.contains(c.key()) && !c.equals(to))) {
                    continue;
                }
                boolean diagonal = d[0] != 0 && d[1] != 0;
                double step = (diagonal ? Math.sqrt(2) : 1.0) * (1.0 + beside.getOrDefault(c.key(), 0.0))
                        + (c.furniture() ? FURNITURE : 0) + (Math.abs(c.h() - a.h()) > 0.1 ? 0.3 : 0);
                double g = top.g() + step;
                if (g + EPS < cost.getOrDefault(c.key(), Double.MAX_VALUE)) {
                    cost.put(c.key(), g);
                    came.put(c.key(), a);
                    open.add(new Open(g + guess(c, to), g, c));
                }
            }
        }
        return null;
    }

    /** A cell waiting to be looked at: the cost so far plus the guess on, and the cost so far. */
    private record Open(double f, double g, Cell cell) {
    }

    /** The octile distance: never more than the walk costs. */
    private static double guess(Cell a, Cell b) {
        int dx = Math.abs(a.x() - b.x());
        int dz = Math.abs(a.z() - b.z());
        return Math.max(dx, dz) + (Math.sqrt(2) - 1) * Math.min(dx, dz);
    }

    /**
     * A path's corners: from each one, straight on to the furthest cell a body fits the whole way to (on this floor's
     * walking cells, {@link #BODY} each side, steps of at most half a block, nobody in {@code avoid}); a seat at either
     * end is stepped onto or off from the cell beside it.
     */
    List<Point> smooth(List<Cell> path, Set<Long> avoid) {
        List<Point> out = new ArrayList<>();
        if (path == null || path.isEmpty()) {
            return out;
        }
        out.add(centre(path.getFirst()));
        int i = 0;
        while (i < path.size() - 1) {
            int j = i + 1;
            for (int k = path.size() - 1; k > i + 1; k--) {
                if (straight(path.get(i), path.get(k), avoid)) {
                    j = k;
                    break;
                }
            }
            out.add(centre(path.get(j)));
            i = j;
        }
        return out;
    }

    private static Point centre(Cell c) {
        return new Point(c.cx(), c.h(), c.cz());
    }

    /** Whether a body walks straight from {@code a} to {@code b}: every point on the way, and a body's width round it, on cells. */
    boolean straight(Cell a, Cell b, Set<Long> avoid) {
        if (a.seat() || b.seat()) {
            return false;
        }
        double dx = b.cx() - a.cx();
        double dz = b.cz() - a.cz();
        double len = Math.hypot(dx, dz);
        int n = Math.max(1, (int) Math.ceil(len / 0.2));
        Cell was = a;
        for (int s = 1; s <= n; s++) {
            double x = a.cx() + dx * s / n;
            double z = a.cz() + dz * s / n;
            Cell c = walkable(x, z, was, avoid);
            if (c == null) {
                return false;
            }
            for (double ox : new double[]{-BODY, BODY}) {
                for (double oz : new double[]{-BODY, BODY}) {
                    if (walkable(x + ox, z + oz, c, avoid) == null) {
                        return false;
                    }
                }
            }
            was = c;
        }
        return true;
    }

    private Cell walkable(double x, double z, Cell near, Set<Long> avoid) {
        List<Cell> col = columns.get(column((int) Math.floor(x), (int) Math.floor(z)));
        if (col == null) {
            return null;
        }
        int f = floor(near);
        for (Cell c : col) {
            if (!c.seat() && !c.furniture() && Math.abs(c.h() - near.h()) <= STEP + EPS && floor(c) == f
                    && (avoid == null || !avoid.contains(c.key()))) {
                return c;
            }
        }
        return null;
    }

    private static long column(int x, int z) {
        return RegularSpots.key(x, 0, z);
    }
}
