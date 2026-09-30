package com.dierks.homecraft.games.gen.golf;

import com.dierks.homecraft.games.golf.BallPhysics;
import com.dierks.homecraft.games.golf.GolfCourse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;

/**
 * A hole's lane read back from its blocks (GEN-SPEC §4.3): which columns the ball can roll on, how
 * far each is from the cup along the lane, and the lane's centre line from the tee to the cup.
 *
 * <p>Everything here comes from the blocks alone — never from the template that drew them — so
 * the planner and the independent {@link GolfValidator} read the same lane from the same voxels
 * and reach the same answers.
 *
 * <ul>
 *   <li><b>Surface:</b> per column inside the hole's bounds, the top of the highest solid block in
 *       the ball's layer (T - 3 up to T + 1, so the flag floating at T + 3 isn't a surface).</li>
 *   <li><b>Lane:</b> the columns reached from the tee by steps the ball can take: down any amount,
 *       up at most half a block (a slab), four ways.</li>
 *   <li><b>Path distance:</b> the shortest way to the cup over the lane (eight ways, no corner
 *       cutting, never up more than half a block), in blocks. The expert search keeps the rest
 *       spots nearest the cup by it; the kid policy scores a rest spot by it.</li>
 *   <li><b>Centre line:</b> the cheapest way from the tee to the cup when a cell costs more the
 *       nearer it is to a wall (counting diagonals), so it runs down the middle of every leg and
 *       keeps clear of the walls round every bend. Its cells are the waypoints of the kid policy:
 *       the kid aims at the furthest one it can see.</li>
 * </ul>
 *
 * <p><b>Two readings, by the layout's golf planner version</b> ({@link #of(BallPhysics.Blocks,
 * GolfCourse.Hole, int)}). A layout of version 2 or older is read exactly as it always was
 * ({@link #of(BallPhysics.Blocks, GolfCourse.Hole)}, frozen: its witness lines and par were worked
 * out on this reading). Adventure Golf (version 3, Course Variety §3.6) reads more:
 * <ul>
 *   <li><b>Base:</b> T is the hole's bounds' floor + 3, not the tee's height, so a tee may stand on
 *       a raised terrace.</li>
 *   <li><b>Window:</b> the ball's layer is T - 3 up to T + 2 (a volcano's summit, a terrace), so a
 *       wall beside a T + 2 lane reads as a wall; a canopy over a T lane starts at T + 3, above it.</li>
 *   <li><b>Hazards:</b> a column whose highest thing in the window is water is a pond: not lane,
 *       not a wall, not "nothing". The lane, the path distances, the centre line and every sight line
 *       stop at it, so the kid aims round water and its path distance goes round it.</li>
 *   <li><b>Stranded</b> lane cells ({@link #stranded}): lane the ball can reach but never leave
 *       for the cup.</li>
 * </ul>
 * {@link #flightReach} is the flight rule's reach: how far a ball flies off a lip before it has
 * fallen far enough that a wall stops it.
 */
final class LaneMap {

    /** Heights the ball can step up (a bottom slab); more bounces off. */
    static final double STEP = 0.5;
    /** The last golf planner version whose lanes are read the old way (frozen). */
    static final int LAST_V2_ALGO = 2;
    /** v3: the ball's layer reaches this far above T (a terrace or a volcano's summit is T + 2). */
    static final int WINDOW_UP = 2;
    /** v3: T is this far above the bounds' floor (the bounds reach T - 3, as a hole's always did). */
    static final int BASE_ABOVE_FLOOR = 3;
    private static final double EPS = 1e-6;
    private static final double SQRT2 = Math.sqrt(2);
    /** Sampling step along a sight line, blocks. */
    private static final double SIGHT_STEP = 0.1;

    final int minX;
    final int minZ;
    final int sizeX;
    final int sizeZ;
    /** T: the tee's top (v2), or the bounds' floor + 3 (v3). */
    final int turfY;
    final int cupX;
    final int cupZ;
    final int teeX;
    final int teeZ;
    /** Per column: the surface, or NaN for nothing in the ball's layer (and for a pond). */
    private final double[] surface;
    private final boolean[] lane;
    /** Per column: a pond (v3; never on an older layout). */
    private final boolean[] hazard;
    private final double[] toCup;
    private final double[] pathX;
    private final double[] pathZ;

    private LaneMap(int minX, int minZ, int sizeX, int sizeZ, int turfY, int cupX, int cupZ, int teeX, int teeZ,
                    double[] surface, boolean[] lane, boolean[] hazard, double[] toCup, double[] pathX,
                    double[] pathZ) {
        this.minX = minX;
        this.minZ = minZ;
        this.sizeX = sizeX;
        this.sizeZ = sizeZ;
        this.turfY = turfY;
        this.cupX = cupX;
        this.cupZ = cupZ;
        this.teeX = teeX;
        this.teeZ = teeZ;
        this.surface = surface;
        this.lane = lane;
        this.hazard = hazard;
        this.toCup = toCup;
        this.pathX = pathX;
        this.pathZ = pathZ;
    }

    /**
     * Read the lane of {@code hole} from {@code blocks} as a layout of golf planner version
     * {@code algo} is read: version {@value #LAST_V2_ALGO} or older exactly as it always was
     * ({@link #of(BallPhysics.Blocks, GolfCourse.Hole)}), a later one with Adventure Golf's base,
     * window and hazards.
     */
    static LaneMap of(BallPhysics.Blocks blocks, GolfCourse.Hole hole, int algo) {
        return algo <= LAST_V2_ALGO ? of(blocks, hole) : v3(blocks, hole);
    }

    /**
     * Read the lane of {@code hole} (complete: tee, cup and bounds set) from {@code blocks}, as a
     * layout of golf planner version {@value #LAST_V2_ALGO} or older (frozen).
     */
    static LaneMap of(BallPhysics.Blocks blocks, GolfCourse.Hole hole) {
        int minX = Math.min(hole.corner1().x(), hole.corner2().x());
        int maxX = Math.max(hole.corner1().x(), hole.corner2().x());
        int minZ = Math.min(hole.corner1().z(), hole.corner2().z());
        int maxZ = Math.max(hole.corner1().z(), hole.corner2().z());
        int sx = maxX - minX + 1;
        int sz = maxZ - minZ + 1;
        int turfY = (int) Math.floor(hole.tee().y() + EPS);
        double[] surface = new double[sx * sz];
        for (int x = 0; x < sx; x++) {
            for (int z = 0; z < sz; z++) {
                surface[x * sz + z] = columnTop(blocks, minX + x, minZ + z, turfY);
            }
        }
        int teeX = (int) Math.floor(hole.tee().x());
        int teeZ = (int) Math.floor(hole.tee().z());
        boolean[] lane = new boolean[sx * sz];
        int start = index(teeX - minX, teeZ - minZ, sx, sz);
        if (start >= 0 && !Double.isNaN(surface[start])) {
            int[] queue = new int[sx * sz];
            int head = 0;
            int tail = 0;
            queue[tail++] = start;
            lane[start] = true;
            while (head < tail) {
                int c = queue[head++];
                int cx = c / sz;
                int cz = c % sz;
                for (int[] d : FOUR) {
                    int n = index(cx + d[0], cz + d[1], sx, sz);
                    if (n >= 0 && !lane[n] && !Double.isNaN(surface[n]) && surface[n] - surface[c] <= STEP + EPS) {
                        lane[n] = true;
                        queue[tail++] = n;
                    }
                }
            }
        }
        int cupX = hole.cup().x();
        int cupZ = hole.cup().z();
        double[] toCup = distances(surface, lane, sx, sz, index(cupX - minX, cupZ - minZ, sx, sz));
        List<Integer> path = centreLine(surface, lane, sx, sz, start, index(cupX - minX, cupZ - minZ, sx, sz));
        double[] px = new double[path.size()];
        double[] pz = new double[path.size()];
        for (int i = 0; i < path.size(); i++) {
            px[i] = minX + path.get(i) / sz + 0.5;
            pz[i] = minZ + path.get(i) % sz + 0.5;
        }
        return new LaneMap(minX, minZ, sx, sz, turfY, cupX, cupZ, teeX, teeZ, surface, lane, new boolean[sx * sz],
                toCup, px, pz);
    }

    /**
     * Adventure Golf's reading (Course Variety §3.6): T from the bounds, the window to T + 2, and
     * ponds as hazards the lane never enters. The flood fill steps down any amount and up at most
     * half a block, four ways, never into a pond.
     */
    private static LaneMap v3(BallPhysics.Blocks blocks, GolfCourse.Hole hole) {
        int minX = Math.min(hole.corner1().x(), hole.corner2().x());
        int maxX = Math.max(hole.corner1().x(), hole.corner2().x());
        int minZ = Math.min(hole.corner1().z(), hole.corner2().z());
        int maxZ = Math.max(hole.corner1().z(), hole.corner2().z());
        int sx = maxX - minX + 1;
        int sz = maxZ - minZ + 1;
        int turfY = Math.min(hole.corner1().y(), hole.corner2().y()) + BASE_ABOVE_FLOOR;
        double[] surface = new double[sx * sz];
        boolean[] hazard = new boolean[sx * sz];
        for (int x = 0; x < sx; x++) {
            for (int z = 0; z < sz; z++) {
                double top = Double.NaN;
                for (int y = turfY + WINDOW_UP; y >= turfY - 3; y--) {
                    double t = blocks.top(minX + x, y, minZ + z, minX + x + 0.5, minZ + z + 0.5);
                    if (t != BallPhysics.Blocks.NONE) {
                        top = y + t;
                        break;
                    }
                    BallPhysics.Surface wet = blocks.surface(minX + x, y, minZ + z);
                    if (wet == BallPhysics.Surface.WATER || wet == BallPhysics.Surface.LAVA) {
                        hazard[x * sz + z] = true;
                        break;
                    }
                }
                surface[x * sz + z] = top;
            }
        }
        int teeX = (int) Math.floor(hole.tee().x());
        int teeZ = (int) Math.floor(hole.tee().z());
        boolean[] lane = new boolean[sx * sz];
        int start = index(teeX - minX, teeZ - minZ, sx, sz);
        if (start >= 0 && !Double.isNaN(surface[start])) {
            int[] queue = new int[sx * sz];
            int head = 0;
            int tail = 0;
            queue[tail++] = start;
            lane[start] = true;
            while (head < tail) {
                int c = queue[head++];
                int cx = c / sz;
                int cz = c % sz;
                for (int[] d : FOUR) {
                    int n = index(cx + d[0], cz + d[1], sx, sz);
                    if (n >= 0 && !lane[n] && !hazard[n] && !Double.isNaN(surface[n])
                            && surface[n] - surface[c] <= STEP + EPS) {
                        lane[n] = true;
                        queue[tail++] = n;
                    }
                }
            }
        }
        int cupX = hole.cup().x();
        int cupZ = hole.cup().z();
        int cup = index(cupX - minX, cupZ - minZ, sx, sz);
        double[] toCup = distances(surface, lane, sx, sz, cup);
        List<Integer> path = centreLine(surface, lane, sx, sz, start, cup);
        double[] px = new double[path.size()];
        double[] pz = new double[path.size()];
        for (int i = 0; i < path.size(); i++) {
            px[i] = minX + path.get(i) / sz + 0.5;
            pz[i] = minZ + path.get(i) % sz + 0.5;
        }
        return new LaneMap(minX, minZ, sx, sz, turfY, cupX, cupZ, teeX, teeZ, surface, lane, hazard, toCup, px, pz);
    }

    private static final int[][] FOUR = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] EIGHT = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    /** The top of the highest solid block at column (x, z) from T - 3 to T + 1; NaN if none. */
    static double columnTop(BallPhysics.Blocks blocks, int x, int z, int turfY) {
        for (int y = turfY + 1; y >= turfY - 3; y--) {
            double top = blocks.top(x, y, z, x + 0.5, z + 0.5);
            if (top != BallPhysics.Blocks.NONE) {
                return y + top;
            }
        }
        return Double.NaN;
    }

    private static int index(int x, int z, int sx, int sz) {
        return x < 0 || z < 0 || x >= sx || z >= sz ? -1 : x * sz + z;
    }

    /** Whether the ball can roll from cell a to its neighbour b (b not more than a step up). */
    private static boolean rolls(double[] surface, boolean[] lane, int a, int b) {
        return b >= 0 && lane[b] && surface[b] - surface[a] <= STEP + EPS;
    }

    /** Path distance to the cup from every lane cell (Dijkstra, eight ways, no corner cutting). */
    private static double[] distances(double[] surface, boolean[] lane, int sx, int sz, int cup) {
        double[] dist = new double[sx * sz];
        Arrays.fill(dist, Double.POSITIVE_INFINITY);
        if (cup < 0 || !lane[cup]) {
            return dist;
        }
        dist[cup] = 0;
        PriorityQueue<double[]> open = new PriorityQueue<>((p, q) -> p[0] != q[0] ? Double.compare(p[0], q[0])
                : Double.compare(p[1], q[1]));
        open.add(new double[]{0, cup});
        while (!open.isEmpty()) {
            double[] top = open.poll();
            int u = (int) top[1];
            if (top[0] > dist[u]) {
                continue;
            }
            int ux = u / sz;
            int uz = u % sz;
            for (int[] d : EIGHT) {
                int v = index(ux + d[0], uz + d[1], sx, sz);
                if (v < 0 || !lane[v] || !rolls(surface, lane, v, u)) {
                    continue; // the ball must be able to go v -> u
                }
                double step = 1;
                if (d[0] != 0 && d[1] != 0) {
                    int i1 = index(ux + d[0], uz, sx, sz);
                    int i2 = index(ux, uz + d[1], sx, sz);
                    if (!rolls(surface, lane, v, i1) || !rolls(surface, lane, i1, u) || !rolls(surface, lane, v, i2)
                            || !rolls(surface, lane, i2, u)) {
                        continue;
                    }
                    step = SQRT2;
                }
                double nd = dist[u] + step;
                if (nd < dist[v] - EPS) {
                    dist[v] = nd;
                    open.add(new double[]{nd, v});
                }
            }
        }
        return dist;
    }

    /**
     * The centre line from the tee cell to the cup cell: the cheapest forward path when a cell
     * costs more the nearer it is to a wall, diagonals counted. Just the cup when there is no way.
     */
    private static List<Integer> centreLine(double[] surface, boolean[] lane, int sx, int sz, int tee, int cup) {
        List<Integer> out = new ArrayList<>();
        if (tee < 0 || cup < 0 || !lane[tee] || !lane[cup]) {
            if (cup >= 0) {
                out.add(cup);
            }
            return out;
        }
        int[] clearance = new int[sx * sz];
        Arrays.fill(clearance, Integer.MAX_VALUE);
        int[] queue = new int[sx * sz];
        int head = 0;
        int tail = 0;
        for (int c = 0; c < sx * sz; c++) {
            if (!lane[c]) {
                continue;
            }
            int cx = c / sz;
            int cz = c % sz;
            for (int[] d : EIGHT) {
                int n = index(cx + d[0], cz + d[1], sx, sz);
                if (n < 0 || !lane[n]) {
                    clearance[c] = 1;
                    queue[tail++] = c;
                    break;
                }
            }
        }
        while (head < tail) {
            int c = queue[head++];
            int cx = c / sz;
            int cz = c % sz;
            for (int[] d : EIGHT) {
                int n = index(cx + d[0], cz + d[1], sx, sz);
                if (n >= 0 && lane[n] && clearance[n] > clearance[c] + 1) {
                    clearance[n] = clearance[c] + 1;
                    queue[tail++] = n;
                }
            }
        }
        double[] cost = new double[sx * sz];
        int[] from = new int[sx * sz];
        Arrays.fill(cost, Double.POSITIVE_INFINITY);
        Arrays.fill(from, -1);
        cost[tee] = 0;
        PriorityQueue<double[]> open = new PriorityQueue<>((p, q) -> p[0] != q[0] ? Double.compare(p[0], q[0])
                : Double.compare(p[1], q[1]));
        open.add(new double[]{0, tee});
        while (!open.isEmpty()) {
            double[] top = open.poll();
            int u = (int) top[1];
            if (top[0] > cost[u]) {
                continue;
            }
            if (u == cup) {
                break;
            }
            int ux = u / sz;
            int uz = u % sz;
            for (int[] d : EIGHT) {
                int v = index(ux + d[0], uz + d[1], sx, sz);
                if (!rolls(surface, lane, u, v)) {
                    continue;
                }
                double step = 1;
                if (d[0] != 0 && d[1] != 0) {
                    int i1 = index(ux + d[0], uz, sx, sz);
                    int i2 = index(ux, uz + d[1], sx, sz);
                    if (!rolls(surface, lane, u, i1) || !rolls(surface, lane, i1, v) || !rolls(surface, lane, u, i2)
                            || !rolls(surface, lane, i2, v)) {
                        continue;
                    }
                    step = SQRT2;
                }
                double c = clearance[v] == Integer.MAX_VALUE ? 1 : clearance[v];
                double nc = cost[u] + step * (1 + 4.0 / (c * c));
                if (nc < cost[v] - EPS) {
                    cost[v] = nc;
                    from[v] = u;
                    open.add(new double[]{nc, v});
                }
            }
        }
        if (from[cup] < 0 && cup != tee) {
            out.add(cup);
            return out;
        }
        for (int c = cup; c >= 0; c = from[c]) {
            out.add(0, c);
        }
        return out;
    }

    // ---- reading it --------------------------------------------------------------------------------

    private int cell(double x, double z) {
        return index((int) Math.floor(x) - minX, (int) Math.floor(z) - minZ, sizeX, sizeZ);
    }

    /** Whether block column (x, z) is lane. */
    boolean isLane(int x, int z) {
        int c = index(x - minX, z - minZ, sizeX, sizeZ);
        return c >= 0 && lane[c];
    }

    /** The surface of column (x, z): NaN outside the bounds, for nothing there, or for a pond. */
    double surface(int x, int z) {
        int c = index(x - minX, z - minZ, sizeX, sizeZ);
        return c < 0 ? Double.NaN : surface[c];
    }

    /** Whether column (x, z) is a pond (a hazard: v3 only). */
    boolean isHazard(int x, int z) {
        int c = index(x - minX, z - minZ, sizeX, sizeZ);
        return c >= 0 && hazard[c];
    }

    /** How many pond columns the hole's bounds hold: water in play when more than none. */
    int hazards() {
        int n = 0;
        for (boolean b : hazard) {
            if (b) {
                n++;
            }
        }
        return n;
    }

    /**
     * The lane cells ({x, z}) with no way to the cup: the ball can get there but never roll on to
     * the cup from there (a pocket behind a one-way drop). Empty on a sound hole.
     */
    List<int[]> stranded() {
        List<int[]> out = new ArrayList<>();
        for (int c = 0; c < lane.length; c++) {
            if (lane[c] && toCup[c] == Double.POSITIVE_INFINITY) {
                out.add(new int[]{minX + c / sizeZ, minZ + c % sizeZ});
            }
        }
        return out;
    }

    /** How many lane cells there are. */
    int laneCells() {
        int n = 0;
        for (boolean b : lane) {
            if (b) {
                n++;
            }
        }
        return n;
    }

    /** Path distance to the cup from a point on the lane (infinite off it, or where the cup can't be reached). */
    double pathDistance(double x, double z) {
        int c = cell(x, z);
        return c < 0 ? Double.POSITIVE_INFINITY : toCup[c];
    }

    /** How many waypoints the centre line has (the cup is the last). */
    int waypoints() {
        return pathX.length;
    }

    double waypointX(int i) {
        return i == pathX.length - 1 ? cupX + 0.5 : pathX[i];
    }

    double waypointZ(int i) {
        return i == pathZ.length - 1 ? cupZ + 0.5 : pathZ[i];
    }

    /**
     * The waypoint to aim at from (x, z): the furthest one along the centre line whose straight
     * segment from here is clear at the ball's radius; the cup when none is.
     */
    int target(double x, double z) {
        for (int i = pathX.length - 1; i >= 0; i--) {
            if (clear(x, z, waypointX(i), waypointZ(i))) {
                return i;
            }
        }
        return pathX.length - 1;
    }

    /**
     * Whether a ball could roll straight from (ax, az) to (bx, bz): every point of the segment,
     * and the two lines a ball's radius either side of it, is over the lane, and the surface never
     * rises more than a step between samples.
     */
    boolean clear(double ax, double az, double bx, double bz) {
        double dx = bx - ax;
        double dz = bz - az;
        double len = Math.sqrt(dx * dx + dz * dz);
        int n = Math.max(1, (int) Math.ceil(len / SIGHT_STEP));
        double ox = 0;
        double oz = 0;
        if (len > EPS) {
            ox = -dz / len * BallPhysics.RADIUS;
            oz = dx / len * BallPhysics.RADIUS;
        }
        int prev = cell(ax, az);
        if (prev < 0 || !lane[prev]) {
            return false;
        }
        for (int k = 0; k <= n; k++) {
            double t = (double) k / n;
            double px = ax + dx * t;
            double pz = az + dz * t;
            int c = cell(px, pz);
            if (c < 0 || !lane[c] || surface[c] - surface[prev] > STEP + EPS) {
                return false;
            }
            int l = cell(px + ox, pz + oz);
            int r = cell(px - ox, pz - oz);
            if (l < 0 || !lane[l] || r < 0 || !lane[r]) {
                return false;
            }
            prev = c;
        }
        return true;
    }

    // ---- the flight rule's reach (Course Variety §3.7) -----------------------------------------------

    /** The hardest putt: every reach is worked out at it. */
    static final double FASTEST = BallPhysics.speed(BallPhysics.clubs());
    /** {@link #flightReach} worked out once for every whole half block of fall, 0 to 6. */
    private static final double[] REACH = new double[13];

    static {
        for (int i = 0; i < REACH.length; i++) {
            REACH[i] = reach(i * 0.5);
        }
    }

    /**
     * How far, in blocks along the ground, a ball can travel beyond the edge of a lip while it falls
     * less than {@code delta}: from the lip cell's edge to the ball's leading edge, at the hardest
     * putt (1.3 blocks a tick, the fastest a ball ever goes: walls only take speed away and no lane
     * floor is slime). A wall whose top is within half a block of the lip ({@code delta} = the lip's
     * surface - the wall's top + 0.5) and no further than this from the lip could be climbed or flown
     * over by a ball off that lip; further away, the ball has fallen enough that the wall stops it.
     *
     * <p>Worked out by the real {@link BallPhysics#tick} on an empty grid past a ledge, so it is the
     * physics' own number, pinned by a test (§3.7): the worst case, a ball putted from as far out
     * over the edge as still holds it up (its footprint's reach), which flies level for its first
     * tick (gravity starts only once nothing holds it) and then falls. Every tick moves in straight
     * sub-steps, so the moment it has fallen {@code delta} is found exactly between two ticks.
     * About 1.55 for no fall at all, 7.22 for half a block, 9.71 for one and 11.56 for one and a
     * half: more than the spec's first estimate (6.2 / 8.6 / 10.8), which left out the level first
     * tick and the ball's size (without them the physics gives 5.78 / 8.33 / 10.22).
     */
    static double flightReach(double delta) {
        double halves = delta * 2;
        int k = (int) Math.round(halves);
        if (Math.abs(halves - k) < 1e-9 && k >= 0 && k < REACH.length) {
            return REACH[k];
        }
        return reach(delta);
    }

    private static double reach(double delta) {
        double fall = Math.max(0, delta);
        BallPhysics.Blocks ledge = new BallPhysics.Blocks() {
            @Override
            public double top(int x, int y, int z, double px, double pz) {
                return y == -1 && x < 0 ? 1.0 : NONE;
            }

            @Override
            public BallPhysics.Surface surface(int x, int y, int z) {
                return BallPhysics.Surface.NORMAL;
            }
        };
        BallPhysics.Hole nowhere = new BallPhysics.Hole(1e6, 1e6, 1e6, -100_000, -100_000, -100_000, 100_000,
                100_000, 100_000);
        // as far out over the edge as the ball's footprint still rests on it (BallPhysics.ground: 0.99 r)
        double start = BallPhysics.RADIUS * 0.99 - 1e-9;
        BallPhysics.Ball ball = new BallPhysics.Ball(start, 0, 0.5);
        ball.putt(1, 0, FASTEST);
        double x = ball.x();
        double y = ball.y();
        for (int tick = 0; tick < 10_000; tick++) {
            BallPhysics.tick(ball, ledge, nowhere);
            if (-ball.y() > fall) {
                double part = (y + fall) / (y - ball.y()); // the straight sub-steps of this tick
                return x + (ball.x() - x) * part + BallPhysics.RADIUS;
            }
            x = ball.x();
            y = ball.y();
        }
        throw new IllegalStateException("a ball that never falls " + delta);
    }

    /**
     * The yaw (Minecraft: 0 is +Z, 90 is -X) from (ax, az) towards (bx, bz), in degrees,
     * worked out with {@link StrictMath} so it is the same on every JVM.
     */
    static double bearing(double ax, double az, double bx, double bz) {
        return StrictMath.toDegrees(StrictMath.atan2(-(bx - ax), bz - az));
    }
}
