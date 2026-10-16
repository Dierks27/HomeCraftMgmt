package com.dierks.homecraft.games.gen.boat;

import com.dierks.homecraft.games.gen.api.GenRandom;
import com.dierks.homecraft.games.gen.api.Palette;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * The mountain round a Mountain Run v2 (MOUNTAIN-V2-SPEC §8): a heightfield the road folds down, a snowy
 * stone summit behind the start, ridges along the east and west margins, a cliff rim behind the summit,
 * a moss valley round the stand, and trees, spruce high up and oak and birch lower down.
 *
 * <p><b>The heightfield.</b> Every column off the track takes the level of its nearest track column (a
 * multi-source search over the whole half), dropped a block below the road on its downhill side and
 * falling away gently, or raised on its uphill side as a road cut 3-6 over the road; then smoothed away
 * from the roads, so the shelves of the road blend into one face, and given a low relief. Under the ice is
 * a block of support, under a wall the ground its base stands on; where neighbours differ by 2 or more the
 * skin is filled down into a cliff of stone.
 *
 * <p><b>What the proof allows, kept here</b> (V4, V7, V9): scenery only in columns two or more from the
 * track (or under a wall's base and the ice), never within 2 of the half's edge, nothing at or over the
 * stand's floor within its clear ring, trees whose leaves stay off the walls' and the track's columns;
 * and the valley under the view from the stand to the finish. Blocks: snow on the top quarter of the
 * mountain, stone on faces of 2 or more and cliffs, moss elsewhere ({@code Palette.SNOW}, {@code STONE}).
 *
 * <p><b>The op budget</b> ({@value #BUDGET}): skin far from every road is left out first, then trees.
 * Pure: no Bukkit.
 */
final class MountainScenery {

    /** The most blocks the mountain may add (§8). */
    static final int BUDGET = 280_000;
    /** The valley floor's height over the half's floor (§4.1). */
    static final int VALLEY_LY = 6;
    /** A road cut: the ground this many columns uphill of a road rises by up to {@value #BANK_HIGH}. */
    static final int BANK_COLUMNS = 6;
    static final int BANK_HIGH = 5;
    /** Away from the roads the ground falls this much a column, and is smoothed over this radius. */
    static final double FALL = 0.12;
    static final int SMOOTH = 6;
    /** The summit stands this high over the start's ice (§8: 30-40). */
    static final int SUMMIT_LOW = 30;
    static final int SUMMIT_HIGH = 40;
    /** Trees: one in about this many moss columns, at most {@value #TREES}, this far from any track. */
    static final int TREE_EVERY = 500;
    static final int TREES = 320;
    static final int TREE_CLEAR = 5;
    /** Ground farther than this from every road is the first left out over budget. */
    static final int FAR_SKIN = 48;
    private static final int UNSEEN = Integer.MAX_VALUE;

    private MountainScenery() {
    }

    /** What a column is to the scenery. */
    private static final byte FAR = 0;
    private static final byte TRACK = 1;
    private static final byte WALL = 2;

    /**
     * The mountain round raster {@code t} from stream {@code r}; how many trees it grew. The raster's blocks
     * (the track, walls, pieces, stand) are already down; the mountain fills only what the rules allow. The
     * raster's {@link RasterV4#tick} runs between the passes and every {@value RasterV4#TICK_ROWS} rows of
     * each (audit MTN04).
     */
    static int draw(GenRandom r, RasterV4 t) {
        int sx = t.sx;
        int sz = t.sz;
        int y0 = t.y0;
        int n = sx * sz;
        byte[] kind = new byte[n];
        int[] lo = new int[n];
        for (int x = 0; x < sx; x++) {
            t.row(x);
            for (int z = 0; z < sz; z++) {
                int i = x * sz + z;
                if (t.h[i] != RasterV4.NONE) {
                    kind[i] = TRACK;
                    lo[i] = t.h[i];
                } else if (t.beside(x, z)) {
                    kind[i] = WALL;
                    int low = Integer.MAX_VALUE;
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if (t.drive(x + dx, z + dz)) {
                                low = Math.min(low, t.h[(x + dx) * sz + z + dz]);
                            }
                        }
                    }
                    lo[i] = low;
                }
            }
        }
        // the nearest track column to every column: its level, distance and where it is
        int[] dist = new int[n];
        int[] seed = new int[n];
        Arrays.fill(dist, UNSEEN);
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int i = 0; i < n; i++) {
            if (kind[i] == TRACK) {
                dist[i] = 0;
                seed[i] = i;
                queue.add(i);
            }
        }
        int polled = 0;
        while (!queue.isEmpty()) {
            if (++polled % (sz * RasterV4.TICK_ROWS) == 0) {
                t.tick.run(); // as often as a pass's rows: TICK_ROWS rows' worth of cells
            }
            int i = queue.poll();
            int x = i / sz;
            int z = i % sz;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int nx = x + dx;
                    int nz = z + dz;
                    if ((dx == 0 && dz == 0) || nx < 0 || nz < 0 || nx >= sx || nz >= sz) {
                        continue;
                    }
                    int ni = nx * sz + nz;
                    if (dist[ni] == UNSEEN) {
                        dist[ni] = dist[i] + 1;
                        seed[ni] = seed[i];
                        queue.add(ni);
                    }
                }
            }
        }
        // the ground: a shelf below the road downhill, a cut above it uphill, falling away from it
        double[] g = new double[n];
        t.tick.run();
        double[] relief = relief(r.fork("relief"), sx, sz);
        for (int x = 0; x < sx; x++) {
            t.row(x);
            for (int z = 0; z < sz; z++) {
                int i = x * sz + z;
                int s = seed[i];
                int ice = t.h[s];
                int d = dist[i];
                double uphill = s % sz - z; // north of the road (toward the summit) is up
                double base;
                if (uphill > 0.5 && d > 1) {
                    base = ice + Math.min(BANK_HIGH, (d - 1) * BANK_HIGH / (double) BANK_COLUMNS);
                } else {
                    base = ice - 1 - FALL * Math.max(0, d - 2);
                }
                g[i] = base + (d > 6 ? relief[i] : 0);
            }
        }
        t.tick.run();
        double[] smooth = blur(g, sx, sz, SMOOTH);
        t.tick.run();
        int[] top = new int[n];
        int topIce = t.topIce;
        double[] pit = t.sk.line.at(0);
        double[] pitDir = t.sk.line.tangent(0);
        GenRandom sr = r.fork("summit");
        double summitH = topIce + sr.nextInt(SUMMIT_LOW, SUMMIT_HIGH);
        // the summit: behind and beside the pit's back wall, never over the track
        double cx = pit[0] - pitDir[0] * 26 + sr.nextDouble(-6, 6);
        double cz = Math.max(18, pit[1] - 18 + sr.nextDouble(-4, 4));
        double ridgeE = sr.nextInt(8, 20);
        double ridgeW = sr.nextInt(8, 20);
        int valley = y0 + VALLEY_LY;
        double finishZ = t.sk.frame.zFinish;
        for (int x = 0; x < sx; x++) {
            t.row(x);
            for (int z = 0; z < sz; z++) {
                int i = x * sz + z;
                int d = dist[i];
                double blend = Math.min(1, Math.max(0, (d - 3) / 6.0));
                double v = g[i] * (1 - blend) + smooth[i] * blend;
                // the summit cone
                double dc = Math.hypot(x - cx, z - cz);
                v = Math.max(v, Math.min(summitH, summitH - 0.75 * Math.max(0, dc - 6)));
                // the ridges along the east and west margins
                double edge = Math.min(x, sx - 1 - x);
                if (edge < 22) {
                    double rise = (x < sx / 2 ? ridgeW : ridgeE) * (1 - edge / 22.0);
                    v = Math.max(v, smooth[i] + rise);
                }
                // the north rim: a stone cliff behind the summit
                if (z < 7) {
                    v = Math.max(v, summitH - 10);
                }
                // the valley round the stand and under the finish straight
                if (z > finishZ + t.tier.width / 2.0 + 3) {
                    v = Math.min(v, valley + (d > 8 ? relief[i] * 0.5 : 0));
                }
                if (d <= 1 && kind[i] == FAR) {
                    v = Math.min(v, t.h[seed[i]] - 1); // never over a wall's ground
                }
                top[i] = (int) Math.round(Math.max(y0 + 2, Math.min(y0 + t.sy - 3, v)));
            }
        }
        // the skin: one block a column, filled down where a neighbour is 2 or more lower
        int lowest = Integer.MAX_VALUE;
        int highest = Integer.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            if (kind[i] == FAR) {
                lowest = Math.min(lowest, top[i]);
                highest = Math.max(highest, top[i]);
            }
        }
        int snowline = highest - (highest - lowest) / 4;
        int room = Math.max(0, MountainValidator.MAX_OPS - 8_000 - t.vox.size());
        int budget = Math.min(BUDGET, room);
        int farLimit = Integer.MAX_VALUE;
        int planned = skinCount(t, kind, top, lo, dist, farLimit);
        while (planned > budget && farLimit > 12) {
            farLimit = farLimit == Integer.MAX_VALUE ? FAR_SKIN * 3 : farLimit - 12;
            planned = skinCount(t, kind, top, lo, dist, farLimit);
        }
        int standFloor = t.standFloor();
        for (int x = 2; x < sx - 2; x++) {
            t.row(x);
            for (int z = 2; z < sz - 2; z++) {
                int i = x * sz + z;
                if (dist[i] > farLimit) {
                    continue;
                }
                int low = lowestNeighbour(top, kind, lo, t, x, z);
                switch (kind[i]) {
                    case TRACK -> {
                        int y = t.h[i] - 1;
                        fill(t, x, z, Math.min(y, low + 1), y, Palette.STONE, Palette.STONE);
                    }
                    case WALL -> {
                        int y = lo[i] - 1;
                        fill(t, x, z, Math.min(y, low + 1), y, Palette.STONE, Palette.STONE);
                    }
                    default -> {
                        int y = top[i];
                        if (t.nearStand(x, z) && y >= standFloor) {
                            y = standFloor - 1;
                        }
                        int grad = Math.max(0, y - low);
                        String skin = y >= snowline ? Palette.SNOW : grad >= 2 ? Palette.STONE : Palette.MOSS;
                        fill(t, x, z, Math.min(y, low + 1), y, skin, Palette.STONE);
                    }
                }
            }
        }
        t.tick.run();
        return trees(r.fork("trees"), t, kind, top, dist, snowline, lowest, highest, budget - planned);
    }

    /** How many blocks the skin would place with columns farther than {@code farLimit} left out. */
    private static int skinCount(RasterV4 t, byte[] kind, int[] top, int[] lo, int[] dist, int farLimit) {
        int count = 0;
        int sz = t.sz;
        for (int x = 2; x < t.sx - 2; x++) {
            t.row(x);
            for (int z = 2; z < sz - 2; z++) {
                int i = x * sz + z;
                if (dist[i] > farLimit) {
                    continue;
                }
                int low = lowestNeighbour(top, kind, lo, t, x, z);
                int y = kind[i] == TRACK ? t.h[i] - 1 : kind[i] == WALL ? lo[i] - 1 : top[i];
                count += Math.max(1, y - Math.min(y, low + 1) + 1);
            }
        }
        return count;
    }

    /** The lowest ground of column (x, z)'s four neighbours (its own when they are all higher). */
    private static int lowestNeighbour(int[] top, byte[] kind, int[] lo, RasterV4 t, int x, int z) {
        int sz = t.sz;
        int i = x * sz + z;
        int own = kind[i] == TRACK ? t.h[i] - 1 : kind[i] == WALL ? lo[i] - 1 : top[i];
        int low = own;
        int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] s : sides) {
            int nx = x + s[0];
            int nz = z + s[1];
            if (nx < 2 || nz < 2 || nx >= t.sx - 2 || nz >= sz - 2) {
                continue;
            }
            int ni = nx * sz + nz;
            int v = kind[ni] == TRACK ? t.h[ni] - 1 : kind[ni] == WALL ? lo[ni] - 1 : top[ni];
            low = Math.min(low, v);
        }
        return low;
    }

    /** {@code skin} at {@code to}, {@code face} below it down to {@code from}, into empty cells only. */
    private static void fill(RasterV4 t, int x, int z, int from, int to, String skin, String face) {
        for (int y = Math.max(t.y0, from); y <= to; y++) {
            if (t.vox.empty(x, y, z)) {
                t.vox.put(x, y, z, y == to ? skin : face);
            }
        }
    }

    /** Low-frequency relief of about ±2: seeded values on a 32-column grid, smoothly interpolated. */
    static double[] relief(GenRandom r, int sx, int sz) {
        int step = 32;
        int gx = sx / step + 2;
        int gz = sz / step + 2;
        double[] knots = new double[gx * gz];
        for (int i = 0; i < knots.length; i++) {
            knots[i] = r.nextDouble(-2, 2);
        }
        double[] out = new double[sx * sz];
        for (int x = 0; x < sx; x++) {
            for (int z = 0; z < sz; z++) {
                double fx = x / (double) step;
                double fz = z / (double) step;
                int ix = (int) fx;
                int iz = (int) fz;
                double ux = smoothstep(fx - ix);
                double uz = smoothstep(fz - iz);
                double a = knots[ix * gz + iz];
                double b = knots[(ix + 1) * gz + iz];
                double c = knots[ix * gz + iz + 1];
                double d = knots[(ix + 1) * gz + iz + 1];
                out[x * sz + z] = (a * (1 - ux) + b * ux) * (1 - uz) + (c * (1 - ux) + d * ux) * uz;
            }
        }
        return out;
    }

    private static double smoothstep(double u) {
        return u * u * (3 - 2 * u);
    }

    /** A box blur of radius {@code k}, by rows then columns (exact sums, so every host gets the same). */
    static double[] blur(double[] v, int sx, int sz, int k) {
        double[] a = new double[v.length];
        for (int x = 0; x < sx; x++) {
            double sum = 0;
            int cnt = 0;
            for (int z = -k; z < sz + k; z++) {
                int add = z + k;
                if (add >= 0 && add < sz) {
                    sum += v[x * sz + add];
                    cnt++;
                }
                int drop = z - k - 1;
                if (drop >= 0 && drop < sz) {
                    sum -= v[x * sz + drop];
                    cnt--;
                }
                if (z >= 0 && z < sz) {
                    a[x * sz + z] = sum / cnt;
                }
            }
        }
        double[] out = new double[v.length];
        for (int z = 0; z < sz; z++) {
            double sum = 0;
            int cnt = 0;
            for (int x = -k; x < sx + k; x++) {
                int add = x + k;
                if (add >= 0 && add < sx) {
                    sum += a[add * sz + z];
                    cnt++;
                }
                int drop = x - k - 1;
                if (drop >= 0 && drop < sx) {
                    sum -= a[drop * sz + z];
                    cnt--;
                }
                if (x >= 0 && x < sx) {
                    out[x * sz + z] = sum / cnt;
                }
            }
        }
        return out;
    }

    /**
     * Trees on moss (§8): about one in {@value #TREE_EVERY} moss columns, at least {@value #TREE_CLEAR}
     * from every track column (so no leaf reaches a wall's column), never near the stand or the half's
     * edge; spruce above the middle of the mountain, oak and birch below. How many grew.
     */
    private static int trees(GenRandom r, RasterV4 t, byte[] kind, int[] top, int[] dist, int snowline, int lowest,
                             int highest, int budget) {
        int sz = t.sz;
        int mid = (lowest + highest) / 2;
        int grown = 0;
        int ops = 0;
        for (int x = 6; x < t.sx - 6 && grown < TREES; x += 3) {
            for (int z = 6; z < sz - 6 && grown < TREES; z += 3) {
                int i = x * sz + z;
                if (kind[i] != FAR || dist[i] < TREE_CLEAR || top[i] >= snowline || !r.chance(9.0 / TREE_EVERY)) {
                    continue;
                }
                if (Math.hypot(x - t.standX, z - t.standZ) < MountainValidator.STAND_CLEAR + 8) {
                    continue;
                }
                if (!Palette.MOSS.equals(t.vox.at(x, top[i], z))) {
                    continue;
                }
                boolean spruce = top[i] >= mid;
                String wood = spruce ? "spruce" : r.nextBoolean() ? "oak" : "birch";
                int added = tree(t, x, top[i] + 1, z, wood, spruce, r.nextInt(4, 6));
                if (added > 0) {
                    grown++;
                    ops += added;
                    if (ops > budget) {
                        return grown;
                    }
                }
            }
        }
        return grown;
    }

    /** One tree standing on (x, y - 1, z): its trunk and leaves (distances worked out later); blocks added. */
    private static int tree(RasterV4 t, int x, int y, int z, String wood, boolean spruce, int height) {
        for (int k = 0; k < height; k++) {
            if (!t.vox.empty(x, y + k, z)) {
                return 0;
            }
        }
        if (y + height + 2 >= t.y0 + t.sy) {
            return 0;
        }
        int added = 0;
        String log = Palette.log(wood);
        for (int k = 0; k < height; k++) {
            t.vox.put(x, y + k, z, log);
            added++;
        }
        int crown = y + height;
        if (spruce) {
            // a cone: radius 2, 2, 1, 1, then the tip
            int[] radii = {2, 1, 2, 1, 0};
            for (int k = 0; k < radii.length; k++) {
                int ly = crown - 3 + k;
                int rad = radii[k];
                for (int dx = -rad; dx <= rad; dx++) {
                    for (int dz = -rad; dz <= rad; dz++) {
                        if ((dx != 0 || dz != 0 || ly >= crown) && Math.abs(dx) + Math.abs(dz) <= rad + 1
                                && t.vox.empty(x + dx, ly, z + dz)) {
                            t.leaf(x + dx, ly, z + dz, wood);
                            added++;
                        }
                    }
                }
            }
        } else {
            for (int ly = crown - 2; ly <= crown + 1; ly++) {
                int rad = ly >= crown ? 1 : 2;
                for (int dx = -rad; dx <= rad; dx++) {
                    for (int dz = -rad; dz <= rad; dz++) {
                        if ((Math.abs(dx) == 2 && Math.abs(dz) == 2) || (dx == 0 && dz == 0 && ly < crown)) {
                            continue;
                        }
                        if (t.vox.empty(x + dx, ly, z + dz)) {
                            t.leaf(x + dx, ly, z + dz, wood);
                            added++;
                        }
                    }
                }
            }
        }
        return added;
    }
}
