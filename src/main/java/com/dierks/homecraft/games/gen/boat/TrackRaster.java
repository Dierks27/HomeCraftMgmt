package com.dierks.homecraft.games.gen.boat;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Palette;
import com.dierks.homecraft.games.trial.RaceStand;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * The Mountain Run drawn on whole blocks (Course Variety §2.5, §2.6): which columns are track and
 * at what height, the drops' flight zones as {@link DownhillValidator} will see them, where the
 * checkpoints go, and then every block: walls, markers, the pieces' structures, the stand, the signs'
 * places, and the leaves at vanilla's own distance.
 *
 * <p><b>The lane.</b> A column is track when its middle is within the lane's half width of its
 * nearest centreline point (wider where a piece or a bend's sand widens it), and its height is the
 * level at that point: every drop edge is square to the centreline and runs wall to wall.
 *
 * <p><b>The proof's own view.</b> The drop edges and their flight zones are worked out here exactly
 * as the validator floods them (every lip cell's disc of Z(d), along the track at or below it), so
 * the walls are raised where it will look and the checkpoints kept where it will allow: flat, off
 * sand where a reset puts the boat down, in the open, at least 3 from every drop and every zone, no
 * other ring within reach of their sphere; on a straight (radius w / 2 + 0.5, its middle row spans
 * the lane) or on a bend (a block wider, where its sphere is shown to cut the lane; on a bend with sand
 * as much wider again as its run-off or kerb, the sand at the sphere's rim, the middle on ice). They
 * are laid by a shortest chain ({@link #chain}): legs at most 60 across the ground with at most one
 * drop each, a leg with no drop at most 60 along the track, a reset at every checkpoint facing the
 * next target within 60 degrees of the lane, and the Final Drop's leg running on to the finish
 * exactly when the Final Drop is in front of the stand (the rules); then as few checkpoints on a sandy
 * bend as that allows, a checkpoint every 32 along the track wherever one can go, and no more than
 * that. It is also how a piece is kept or given up: only while a chain still exists round it.
 *
 * <p><b>Walls</b> stand in every column beside the track, from the lowest ice beside it to 2 over
 * the highest (stripped spruce at the ice and the ice + 1, glass above), raised to 2 over the lip
 * round every flight zone and carried up as a spruce cliff to a terrace beside it; an island's rim is
 * spruce, a forest's trunk logs. Risers fill under every 2-block edge. Pure: no Bukkit.
 */
final class TrackRaster {

    static final int NONE = DeckGraph.NONE;
    static final byte PACKED = 1;
    static final byte BLUE = 2;
    static final byte SAND = 3;
    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final double EPS = 1e-9;

    final Box half;
    final int sx;
    final int sy;
    final int sz;
    final int h0;
    final TrackPath path;
    final TrackProfile profile;
    final TrackPieces pieces;
    final BoatPlanner.Level level;
    /** The stand's centre column, in half columns. */
    final int standX;
    final int standZ;
    /** The highest a block other than the stand's may be (the scenery cap, V9). */
    final int top;

    final int[][] h;
    final byte[][] mat;
    final double[][] sAt;
    final double[][] vAt;
    final boolean[][] obstacle;
    /** Whether a column's nearest centreline point was found (within reach of the track). */
    final boolean[][] near;
    final TrackPath.Seg[][] segAt;
    int[][] lipDrop;
    int[][] zoneLip;
    /** A drop inside another's flight zone, or {@code null}. */
    String chained;

    TrackRaster(Box half, TrackPath path, TrackProfile profile, TrackPieces pieces, BoatPlanner.Level level) {
        this.half = half;
        this.sx = half.sizeX();
        this.sy = half.sizeY();
        this.sz = half.sizeZ();
        this.h0 = half.minY();
        this.path = path;
        this.profile = profile;
        this.pieces = pieces;
        this.level = level;
        this.standX = RaceStand.centreX(half) - half.minX();
        this.standZ = RaceStand.centreZ(half) - half.minZ();
        this.top = RaceStand.floorY(profile.top + 1) - 1;
        h = new int[sx][sz];
        mat = new byte[sx][sz];
        sAt = new double[sx][sz];
        vAt = new double[sx][sz];
        obstacle = new boolean[sx][sz];
        near = new boolean[sx][sz];
        segAt = new TrackPath.Seg[sx][sz];
        lane();
        lips();
        zones();
    }

    // ---- the lane -------------------------------------------------------------------------------------

    /** The lane's base half width {@code s} along: the pit's, then the tier's (hard's pit narrows after it). */
    double baseHalf(double s) {
        if (s < TrackProfile.PIT + BoatPlanner.FUNNEL) {
            return level.pitWidth() / 2.0;
        }
        return level.width() / 2.0;
    }

    private void lane() {
        double reach = Math.max(level.pitWidth(), level.width()) / 2.0 + BoatPlanner.MAX_EXTRA * 2 + 2;
        int in = path.inside();
        for (int x = 0; x < sx; x++) {
            for (int z = 0; z < sz; z++) {
                h[x][z] = NONE;
                TrackPath.Near n = path.nearest(x, z, reach, profile.end);
                if (n == null) {
                    continue;
                }
                near[x][z] = true;
                sAt[x][z] = n.s;
                double v = n.off * in;
                vAt[x][z] = v;
                segAt[x][z] = n.seg;
                if (n.end) {
                    continue;
                }
                double hw = baseHalf(n.s);
                double limit = hw + pieces.extra(n.seg, n.s, v >= 0);
                if (n.dist > limit + EPS) {
                    continue;
                }
                if (!n.seg.arc && pieces.obstacle(n.s, v)) {
                    obstacle[x][z] = true;
                    continue;
                }
                h[x][z] = profile.level(n.s);
                if (pieces.sand(n.seg, n.s, v, hw) || n.s > profile.finish + TrackProfile.RUN_OUT + 0.5) {
                    mat[x][z] = SAND;
                } else if (level.blue() || pieces.boost(n.s, v)) {
                    mat[x][z] = BLUE;
                } else {
                    mat[x][z] = PACKED;
                }
            }
        }
    }

    boolean drive(int x, int z) {
        return x >= 0 && z >= 0 && x < sx && z < sz && h[x][z] != NONE;
    }

    boolean inside(int x, int z) {
        return x >= 0 && z >= 0 && x < sx && z < sz;
    }

    /** Whether column (x, z) isn't track but is beside some (8 ways): a wall, a rim, a trunk. */
    boolean beside(int x, int z) {
        if (drive(x, z)) {
            return false;
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if ((dx != 0 || dz != 0) && drive(x + dx, z + dz)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The lowest drive cell's height. */
    int lowest() {
        int m = Integer.MAX_VALUE;
        for (int x = 0; x < sx; x++) {
            for (int z = 0; z < sz; z++) {
                if (h[x][z] != NONE) {
                    m = Math.min(m, h[x][z]);
                }
            }
        }
        return m;
    }

    // ---- the proof's view: drops, zones ---------------------------------------------------------------

    private void lips() {
        lipDrop = new int[sx][sz];
        for (int x = 0; x < sx; x++) {
            for (int z = 0; z < sz; z++) {
                if (h[x][z] == NONE) {
                    continue;
                }
                for (int[] s : SIDES) {
                    int nx = x + s[0];
                    int nz = z + s[1];
                    if (drive(nx, nz) && h[nx][nz] < h[x][z]) {
                        lipDrop[x][z] = Math.max(lipDrop[x][z], h[x][z] - h[nx][nz]);
                    }
                }
            }
        }
    }

    /** DownhillValidator's flood, cell for cell: each lip cell's disc of Z(d), along the track at or below it. */
    private void zones() {
        zoneLip = new int[sx][sz];
        for (int[] row : zoneLip) {
            java.util.Arrays.fill(row, NONE);
        }
        int[][] stamp = new int[sx][sz];
        int flood = 0;
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        for (int x = 0; x < sx; x++) {
            for (int z = 0; z < sz; z++) {
                if (lipDrop[x][z] == 0) {
                    continue;
                }
                flood++;
                int lip = h[x][z];
                double reach = BoatEnvelope.zone(lipDrop[x][z]);
                stamp[x][z] = flood;
                queue.add(new int[]{x, z});
                while (!queue.isEmpty()) {
                    int[] c = queue.poll();
                    if (h[c[0]][c[1]] < lip) {
                        zoneLip[c[0]][c[1]] = Math.max(zoneLip[c[0]][c[1]], lip);
                        if (lipDrop[c[0]][c[1]] > 0 && chained == null) {
                            chained = "a drop at " + c[0] + " " + c[1] + " is in the zone of the drop at " + x + " " + z;
                        }
                    }
                    for (int[] s : SIDES) {
                        int nx = c[0] + s[0];
                        int nz = c[1] + s[1];
                        if (drive(nx, nz) && stamp[nx][nz] != flood && h[nx][nz] <= lip
                                && (nx - x) * (nx - x) + (nz - z) * (nz - z) <= reach * reach + EPS) {
                            stamp[nx][nz] = flood;
                            queue.add(new int[]{nx, nz});
                        }
                    }
                }
            }
        }
    }

    /**
     * Where each drop's flight zone ends along the track, as the blocks have it: the farthest s of a
     * cell flooded from that drop's lip (by its level), plus a block; {@code NaN} for none.
     */
    double[] zoneEnds() {
        double[] out = new double[profile.lips.size()];
        java.util.Arrays.fill(out, Double.NaN);
        for (int i = 0; i < out.length; i++) {
            TrackProfile.Lip l = profile.lips.get(i);
            int upper = profile.level(l.s() - 0.5);
            double end = Double.NaN;
            for (int x = 0; x < sx; x++) {
                for (int z = 0; z < sz; z++) {
                    if (zoneLip[x][z] == upper && sAt[x][z] > l.s()) {
                        end = Double.isNaN(end) ? sAt[x][z] : Math.max(end, sAt[x][z]);
                    }
                }
            }
            out[i] = Double.isNaN(end) ? Double.NaN : end + 1;
        }
        return out;
    }

    /** What keeps this raster from being a Mountain Run before a block is placed; {@code null} when nothing. */
    String problem() {
        if (chained != null) {
            return chained;
        }
        for (int x = 0; x < sx; x++) {
            for (int z = 0; z < sz; z++) {
                if (h[x][z] == NONE) {
                    continue;
                }
                if (x == 0 || z == 0 || x == sx - 1 || z == sz - 1) {
                    return "the track reaches the edge at " + x + " " + z;
                }
                int dx = Math.max(0, Math.abs(x - standX) - RaceStand.SIZE / 2);
                int dz = Math.max(0, Math.abs(z - standZ) - RaceStand.SIZE / 2);
                if (Math.hypot(dx, dz) < RaceStand.LANE_CLEARANCE) {
                    return "the track at " + x + " " + z + " is too near the stand";
                }
            }
        }
        return null;
    }

    // ---- checkpoints ------------------------------------------------------------------------------------

    /**
     * A place a checkpoint may go: {@code s} along, its middle (half columns), its radius and ice,
     * whether it is on a bend with sand (the chain takes those only where the rules need them), and
     * the lane's direction there as the proof reads it off the blocks ({@link #laneRead}; a unit
     * vector, 0 for the start and the finish).
     */
    record Spot(double s, double x, double z, double r, int ice, boolean sandy, double lx, double lz) {

        Spot(double s, double x, double z, double r, int ice) {
            this(s, x, z, r, ice, false, 0, 0);
        }
    }

    /**
     * Every place a checkpoint may go, in order along the track (pieces aside: {@link #chain}
     * leaves those out): on the straights, radius w / 2 + 0.5 (its middle row spans the lane); on a
     * bend, radius w / 2 + 1.5 where its sphere is shown to cut the lane there, and on a bend with a
     * sand run-off or kerb that much more than the sand's width, so the sphere spans the sand too (a
     * boat going round on the sand still meets it) while its middle, where a reset puts the boat
     * down, is ice (review CV gate: on hard every bend has sand, and a checkpoint on one is what keeps
     * a leg past a piece within the rules).
     */
    List<Spot> spots() {
        List<Spot> out = new ArrayList<>();
        for (TrackPath.Seg g : path.segs) {
            int sand = g.arc ? Math.max(pieces.runoff[g.index], pieces.kerb[g.index]) : 0;
            double first = g.arc ? g.s0 + 0.5 : TrackProfile.spotStart(g);
            for (double s = first; s <= g.s1(); s += 1) {
                double r = baseHalf(s) + (g.arc ? BoatPlanner.ARC_SPOT + sand : 0.5);
                if (!g.arc && (s - r < g.s0 - 1e-9 || s + r > g.s1() + 1e-9)) {
                    continue; // the whole disk along the straight
                }
                if (baseHalf(s - r - 1.5) != baseHalf(s + r + 1.5)) {
                    continue; // the pit's funnel
                }
                if (s < TrackProfile.START + r + 1 || s + r + 1 > profile.finish - level.finishRadius() - 1) {
                    continue;
                }
                if (profile.level(s - r - 0.5) != profile.level(s + r + 0.5)) {
                    continue;
                }
                if (pieces.blocks(s - r - 1.5) != null || pieces.blocks(s + r + 1.5) != null || pieces.blocks(s) != null) {
                    continue;
                }
                double[] p = path.at(s);
                if (ok(p[0], p[1], r) && (!g.arc || cuts(s, p[0], p[1], r))) {
                    double[] lane = laneRead(s, p[0], p[1], r);
                    if (lane != null) {
                        out.add(new Spot(s, p[0], p[1], r, profile.level(s), sand > 0, lane[0], lane[1]));
                    }
                }
            }
        }
        return out;
    }

    /**
     * Whether the blocks wholly inside the sphere at (px, pz) cut the lane {@code s} along: no way
     * from the track just before it to the track just after it goes round them (the validator's
     * cut, looked at where it matters: the lane is one band there).
     */
    private boolean cuts(double s, double px, double pz, double r) {
        int span = (int) Math.ceil(r) + 7;
        int cx = (int) Math.floor(px);
        int cz = (int) Math.floor(pz);
        int n = 2 * span + 1;
        boolean[][] seen = new boolean[n][n];
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        for (int x = cx - span; x <= cx + span; x++) {
            for (int z = cz - span; z <= cz + span; z++) {
                if (drive(x, z) && band(x, z, s, r) && sAt[x][z] < s - r - 1.5 && !whollyIn(x, z, px, pz, r)) {
                    seen[x - cx + span][z - cz + span] = true;
                    queue.add(new int[]{x, z});
                }
            }
        }
        while (!queue.isEmpty()) {
            int[] c = queue.poll();
            if (sAt[c[0]][c[1]] > s + r + 1.5) {
                return false;
            }
            for (int[] d : SIDES) {
                int x = c[0] + d[0];
                int z = c[1] + d[1];
                if (x < cx - span || z < cz - span || x > cx + span || z > cz + span || !drive(x, z)
                        || seen[x - cx + span][z - cz + span] || !band(x, z, s, r) || whollyIn(x, z, px, pz, r)) {
                    continue;
                }
                seen[x - cx + span][z - cz + span] = true;
                queue.add(new int[]{x, z});
            }
        }
        return true;
    }

    /**
     * The lane's direction at the checkpoint (px, pz) of radius r, {@code s} along, read off the blocks
     * exactly as {@link DownhillValidator} reads it for its facing rule: from the middle of the ice
     * cells just before the blocks wholly inside the sphere (those a boat reaches from before it
     * without crossing them) to the middle of the ice cells just past them; a unit {x, z}, or
     * {@code null} when either side has none. On a bend it lags or leads the centreline's own
     * tangent by a few degrees near the bend's ends, so the chain holds a reset to both.
     */
    double[] laneRead(double s, double px, double pz, double r) {
        int cx = (int) Math.floor(px);
        int cz = (int) Math.floor(pz);
        int span = (int) Math.ceil(r) + 2;
        int w = span + 2;
        int n = 2 * w + 1;
        boolean[][] in = new boolean[n][n];
        for (int x = cx - w; x <= cx + w; x++) {
            for (int z = cz - w; z <= cz + w; z++) {
                in[x - cx + w][z - cz + w] = drive(x, z) && whollyIn(x, z, px, pz, r);
            }
        }
        // the side a boat comes from: the track before the sphere, flooded round it but never through it
        boolean[][] before = new boolean[n][n];
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        for (int x = cx - w; x <= cx + w; x++) {
            for (int z = cz - w; z <= cz + w; z++) {
                if (drive(x, z) && !in[x - cx + w][z - cz + w] && sAt[x][z] < s - r - 1.5) {
                    before[x - cx + w][z - cz + w] = true;
                    queue.add(new int[]{x, z});
                }
            }
        }
        while (!queue.isEmpty()) {
            int[] c = queue.poll();
            for (int[] d : SIDES) {
                int x = c[0] + d[0];
                int z = c[1] + d[1];
                if (x < cx - w || z < cz - w || x > cx + w || z > cz + w || !drive(x, z)
                        || in[x - cx + w][z - cz + w] || before[x - cx + w][z - cz + w]
                        || h[x][z] > h[c[0]][c[1]]) {
                    continue;
                }
                before[x - cx + w][z - cz + w] = true;
                queue.add(new int[]{x, z});
            }
        }
        double[] a = new double[3];
        double[] b = new double[3];
        for (int x = cx - span; x <= cx + span; x++) {
            for (int z = cz - span; z <= cz + span; z++) {
                if (!drive(x, z) || in[x - cx + w][z - cz + w] || mat[x][z] == SAND) {
                    continue;
                }
                boolean edge = false;
                for (int[] d : SIDES) {
                    int ex = x + d[0] - cx + w;
                    int ez = z + d[1] - cz + w;
                    edge |= ex >= 0 && ez >= 0 && ex < n && ez < n && in[ex][ez];
                }
                if (!edge) {
                    continue;
                }
                double[] side = before[x - cx + w][z - cz + w] ? a : b;
                side[0] += x + 0.5;
                side[1] += z + 0.5;
                side[2]++;
            }
        }
        if (a[2] == 0 || b[2] == 0) {
            return null;
        }
        double lx = b[0] / b[2] - a[0] / a[2];
        double lz = b[1] / b[2] - a[1] / a[2];
        double len = Math.hypot(lx, lz);
        return len < EPS ? null : new double[]{lx / len, lz / len};
    }

    private boolean band(int x, int z, double s, double r) {
        return Math.abs(sAt[x][z] - s) <= r + 5;
    }

    /** Whether column (x, z) lies wholly within r of (px, pz) across the ground (the validator's "inside"). */
    private static boolean whollyIn(int x, int z, double px, double pz, double r) {
        double fx = Math.max(Math.abs(x - px), Math.abs(x + 1 - px));
        double fz = Math.max(Math.abs(z - pz), Math.abs(z + 1 - pz));
        return fx * fx + fz * fz <= r * r + EPS;
    }

    /**
     * DownhillValidator's checkpoint rules at (px, pz), radius r (§2.6, V6): flat, no sand within
     * {@link DownhillValidator#RESET_ROOM} of its middle, clear of drops and zones, no other ring in reach.
     */
    private boolean ok(double px, double pz, double r) {
        int cx = (int) Math.floor(px);
        int cz = (int) Math.floor(pz);
        if (!drive(cx, cz)) {
            return false;
        }
        int ice = h[cx][cz];
        List<int[]> nearCells = new ArrayList<>();
        int span = (int) Math.ceil(r) + 1;
        for (int x = cx - span; x <= cx + span; x++) {
            for (int z = cz - span; z <= cz + span; z++) {
                if (!drive(x, z)) {
                    continue;
                }
                double d2 = sq(x + 0.5 - px) + sq(z + 0.5 - pz);
                if (d2 <= r * r + EPS) {
                    if (h[x][z] != ice || (mat[x][z] == SAND
                            && d2 <= DownhillValidator.RESET_ROOM * DownhillValidator.RESET_ROOM + EPS)) {
                        return false;
                    }
                    nearCells.add(new int[]{x, z});
                }
            }
        }
        // at least 3 from every drop and every flight zone
        int clear = (int) Math.ceil(DownhillValidator.DROP_CLEAR) + 1;
        for (int x = cx - clear; x <= cx + clear; x++) {
            for (int z = cz - clear; z <= cz + clear; z++) {
                if (!inside(x, z)) {
                    continue;
                }
                double d = Math.hypot(x + 0.5 - px, z + 0.5 - pz);
                if (d < DownhillValidator.DROP_CLEAR - 1e-6
                        && (lipDrop[x][z] > 0 || zoneLip[x][z] != NONE)) {
                    return false;
                }
            }
        }
        // its sphere never reaches another ring: every track block within r + 1 is near it along the track
        int limit = (int) Math.ceil(DownhillValidator.RING_STEPS * r);
        double reach = r + DownhillValidator.RING_REACH;
        int w = (int) Math.ceil(reach) + limit + 2;
        int x0 = cx - w;
        int z0 = cz - w;
        int n = 2 * w + 1;
        int[][] steps = new int[n][n];
        for (int[] row : steps) {
            java.util.Arrays.fill(row, -1);
        }
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        for (int[] c : nearCells) {
            steps[c[0] - x0][c[1] - z0] = 0;
            queue.add(c);
        }
        while (!queue.isEmpty()) {
            int[] c = queue.poll();
            int d = steps[c[0] - x0][c[1] - z0];
            if (d >= limit) {
                continue;
            }
            for (int[] s : SIDES) {
                int nx = c[0] + s[0];
                int nz = c[1] + s[1];
                if (nx < x0 || nz < z0 || nx >= x0 + n || nz >= z0 + n || !drive(nx, nz)
                        || steps[nx - x0][nz - z0] >= 0) {
                    continue;
                }
                steps[nx - x0][nz - z0] = d + 1;
                queue.add(new int[]{nx, nz});
            }
        }
        int rr = (int) Math.ceil(reach) + 1;
        for (int x = cx - rr; x <= cx + rr; x++) {
            for (int z = cz - rr; z <= cz + rr; z++) {
                if (drive(x, z) && steps[x - x0][z - z0] < 0
                        && sq(x + 0.5 - px) + sq(z + 0.5 - pz) <= reach * reach + EPS) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * The checkpoints from the start to the finish through {@code spots}, none within a block of a
     * {@code blocked} stretch (a piece): legs at most {@link BoatPlanner#LEG_MAX} across the ground
     * and at most one drop each, a leg with no drop at most {@link BoatPlanner#FLAT_LEG} along the
     * track, and from every checkpoint a reset facing the next target within
     * {@link BoatPlanner#FACING} degrees of the lane ({@link #faces}; the rules); the Final Drop's leg
     * runs to the finish exactly when the Final Drop is in front of the stand
     * ({@link TrackProfile#finalInFront}, where its FINAL DROP! sign stands), so the race copy's
     * "Final drop!" title, read off the marks ({@code BoatHype.finalDrop}), shows only there; then
     * as few checkpoints on a sandy bend as that allows, then a checkpoint at least every
     * {@code spacing} along the track wherever one can go, then as few as that allows. {@code null}
     * when no chain keeps the rules.
     */
    List<Spot> chain(List<Spot> spots, List<double[]> blocked, double spacing) {
        TrackProfile.Lip last = profile.last();
        boolean front = profile.finalInFront();
        List<Spot> use = new ArrayList<>();
        for (Spot sp : spots) {
            if (front && sp.s() > last.s()) {
                continue; // the gold finish comes next after a Final Drop in front of the stand
            }
            boolean free = true;
            for (double[] b : blocked) {
                if (sp.s() + sp.r() + 1.5 > b[0] && sp.s() - sp.r() - 1.5 < b[1]) {
                    free = false;
                    break;
                }
            }
            if (free) {
                use.add(sp);
            }
        }
        double[] st = path.at(TrackProfile.START);
        double[] fp = path.at(profile.finish);
        Spot start = new Spot(TrackProfile.START, st[0], st[1], 0, profile.top);
        Spot finish = new Spot(profile.finish, fp[0], fp[1], level.finishRadius(), profile.bottom());
        int n = use.size();
        // fewest checkpoints on sandy bends first, then fewest legs longer than the spacing, then fewest checkpoints
        long[] best = new long[n + 1];
        int[] prev = new int[n + 1];
        java.util.Arrays.fill(best, Long.MAX_VALUE);
        for (int i = 0; i <= n; i++) {
            Spot b = i < n ? use.get(i) : finish;
            long add = i < n ? (b.sandy() ? SANDY : 1) : 0;
            if (leg(start, b, true, i == n)) {
                best[i] = add + LONG * missed(start, b, spacing);
                prev[i] = -1;
            }
            for (int j = 0; j < Math.min(i, n); j++) {
                if (best[j] == Long.MAX_VALUE) {
                    continue;
                }
                Spot a = use.get(j);
                if (b.s() - a.s() > 3 * BoatPlanner.LEG_MAX) {
                    continue;
                }
                long c = best[j] + add;
                if (c <= best[i] && leg(a, b, false, i == n)) {
                    c += LONG * missed(a, b, spacing);
                    if (c < best[i] || (c == best[i] && prev[i] < j)) {
                        best[i] = c;
                        prev[i] = j;
                    }
                }
            }
        }
        if (best[n] == Long.MAX_VALUE) {
            return null;
        }
        List<Spot> out = new ArrayList<>();
        for (int i = prev[n]; i >= 0; i = prev[i]) {
            out.add(0, use.get(i));
        }
        return out;
    }

    /** What a checkpoint missed costs against one more checkpoint: many. */
    private static final long LONG = 1_000;
    /** What a checkpoint on a sandy bend costs: more than any spacing it could save, so it is there only for the rules. */
    private static final long SANDY = 1_000_000;

    /**
     * How many checkpoints leg a-b misses: one for every {@code spacing} along the track past the
     * first (past {@link BoatPlanner#LEG_MAX} when a drop is in it, whose zone keeps them off).
     */
    private long missed(Spot a, Spot b, double spacing) {
        double along = b.s() - a.s();
        if (profile.lipsBetween(a.s(), b.s()) > 0) {
            along -= BoatPlanner.LEG_MAX;
        }
        return along <= spacing ? 0 : (long) Math.ceil(along / spacing) - 1;
    }

    /**
     * Whether a checkpoint leg from a to b keeps the rules (a the start when {@code fromStart}, b the
     * finish when {@code toFinish}): at most {@link BoatPlanner#LEG_MAX} across the ground, the disks
     * apart, at most one drop, at most {@link BoatPlanner#FLAT_LEG} along the track when no drop is in
     * it, a Final Drop far from the finish not in the finish's leg, and a reset at {@code a} facing on
     * down the lane ({@link #faces}; a reset before the first checkpoint goes to the start, facing
     * along the pit).
     */
    boolean leg(Spot a, Spot b, boolean fromStart, boolean toFinish) {
        if (b.s() <= a.s()) {
            return false;
        }
        double across = Math.hypot(b.x() - a.x(), b.z() - a.z());
        if (across > BoatPlanner.LEG_MAX) {
            return false;
        }
        if (fromStart) {
            if (across <= b.r() + 1.5) {
                return false; // the start inside the disk
            }
        } else if (across <= a.r() + b.r() + 0.5) {
            return false; // the disks would touch
        }
        int drops = profile.lipsBetween(a.s(), b.s());
        if (drops > 1) {
            return false;
        }
        if (drops == 0 && b.s() - a.s() > BoatPlanner.FLAT_LEG) {
            return false; // review CV gate: a reset never sends a boat far back
        }
        if (drops == 1 && toFinish && !profile.finalInFront()) {
            return false; // a Final Drop far from the finish has its own HOP! or BIG DROP!, and a checkpoint after it
        }
        return fromStart || faces(a, b);
    }

    /**
     * Whether a reset at checkpoint {@code a} faces on down the track: {@code TimeTrials.backTo} turns
     * the boat toward the next target {@code b} ({@link com.dierks.homecraft.games.trial.Course#resetYaw}),
     * and that line is within {@link BoatPlanner#FACING} degrees of the lane's direction at {@code a},
     * both the centreline's tangent there and the lane as the proof reads it off the blocks
     * ({@link #laneRead}; a hair inside, for the stored yaw's float), so the proof never refuses what
     * this lays (review CV gate: a leg round two bends had a kid reset facing back up the track).
     */
    boolean faces(Spot a, Spot b) {
        double[] t = path.tangent(a.s());
        return offLane(a, b, t[0], t[1]) <= BoatPlanner.FACING + 1e-6
                && offLane(a, b, a.lx(), a.lz()) <= BoatPlanner.FACING - 1e-3;
    }

    /** The angle, in degrees, between the line from {@code a} to {@code b} and the unit direction (tx, tz). */
    private static double offLane(Spot a, Spot b, double tx, double tz) {
        double dx = b.x() - a.x();
        double dz = b.z() - a.z();
        double len = Math.hypot(dx, dz);
        if (len < EPS) {
            return 180;
        }
        double cos = Math.max(-1, Math.min(1, (dx * tx + dz * tz) / len));
        return Math.toDegrees(Math.acos(cos));
    }

    private static double sq(double v) {
        return v * v;
    }

    // ---- the blocks -------------------------------------------------------------------------------------

    /** The block texts the ops index, and every block as palette index + 1 (0 is air). */
    final List<String> palette = new ArrayList<>();
    private short[] grid;
    /** Leaves waiting for their distance: {x, y, z} and wood, written last. */
    private final List<int[]> leafAt = new ArrayList<>();
    private final List<String> leafWood = new ArrayList<>();
    /** Each far column's terrace level ({@link BoatScenery}), or NONE. */
    int[][] terrace;

    /** The block index of (x, y, z): half columns, world height. */
    private int index(int x, int y, int z) {
        return (x * sz + z) * sy + (y - h0);
    }

    /** Whether (x, y, z) is inside the half. */
    boolean in(int x, int y, int z) {
        return inside(x, z) && y >= h0 && y < h0 + sy;
    }

    /** Put {@code block} at half column (x, z), world height y (outside the half: nothing). */
    void put(int x, int y, int z, String block) {
        if (!in(x, y, z)) {
            return;
        }
        int i = palette.indexOf(block);
        if (i < 0) {
            palette.add(block);
            i = palette.size() - 1;
        }
        grid[index(x, y, z)] = (short) (i + 1);
    }

    /** The block at (x, y, z), or {@code null} for air (or outside). */
    String at(int x, int y, int z) {
        if (!in(x, y, z)) {
            return null;
        }
        short v = grid[index(x, y, z)];
        return v == 0 ? null : palette.get(v - 1);
    }

    boolean empty(int x, int y, int z) {
        return in(x, y, z) && grid[index(x, y, z)] == 0;
    }

    /** A leaf of {@code wood} at (x, y, z), its distance worked out when every block is down. */
    void leaf(int x, int y, int z, String wood) {
        if (!in(x, y, z) || !empty(x, y, z)) {
            return;
        }
        put(x, y, z, "LEAF");
        leafAt.add(new int[]{x, y, z});
        leafWood.add(wood);
    }

    /** The highest block in column (x, z), or {@code h0 - 1}. */
    int topOf(int x, int z) {
        for (int y = h0 + sy - 1; y >= h0; y--) {
            if (!empty(x, y, z)) {
                return y;
            }
        }
        return h0 - 1;
    }

    /** Start the blocks: the drive cells. */
    void blocks() {
        grid = new short[sx * sz * sy];
        for (int x = 0; x < sx; x++) {
            for (int z = 0; z < sz; z++) {
                if (h[x][z] == NONE) {
                    continue;
                }
                put(x, h[x][z], z, mat[x][z] == SAND ? Palette.SAND
                        : mat[x][z] == BLUE ? Palette.TRACK_FAST : Palette.TRACK);
            }
        }
    }

    /**
     * Every column beside the track: solid from the lowest ice beside it to 2 over the highest,
     * raised round flight zones, carried up to a terrace beside it; stripped spruce to the ice + 1
     * (all the way on a cliff and an island's rim), glass above. Risers under every 2-block edge.
     */
    void walls() {
        for (int x = 0; x < sx; x++) {
            for (int z = 0; z < sz; z++) {
                if (!beside(x, z)) {
                    continue;
                }
                int lo = Integer.MAX_VALUE;
                int hi = Integer.MIN_VALUE;
                int need = Integer.MIN_VALUE;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        int nx = x + dx;
                        int nz = z + dz;
                        if (!drive(nx, nz)) {
                            continue;
                        }
                        lo = Math.min(lo, h[nx][nz]);
                        hi = Math.max(hi, h[nx][nz]);
                        need = Math.max(need, h[nx][nz] + DownhillValidator.WALL_ABOVE);
                        if (zoneLip[nx][nz] != NONE) {
                            need = Math.max(need, zoneLip[nx][nz] + DownhillValidator.WALL_ABOVE);
                        }
                    }
                }
                need = Math.min(need, top);
                int cliff = hi + 1;
                if (terrace != null) {
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            int nx = x + dx;
                            int nz = z + dz;
                            if (inside(nx, nz) && terrace[nx][nz] != NONE) {
                                cliff = Math.max(cliff, Math.min(terrace[nx][nz], top));
                            }
                        }
                    }
                }
                boolean rim = obstacle[x][z];
                String log = null;
                if (rim) {
                    TrackPieces.Piece p = pieces.at(sAt[x][z]);
                    if (p != null && p.kind == TrackPieces.Kind.FOREST) {
                        log = Palette.log(Palette.WOODS.get(Math.floorMod((int) Math.floor(p.s1), 3)));
                        need = Math.min(top, Math.max(need, hi + BoatPlanner.TRUNK_TOP));
                    }
                }
                for (int y = lo; y <= Math.max(need, cliff); y++) {
                    String b = log != null ? log : rim || y <= cliff ? Palette.TRACK_WALL : Palette.GLASS;
                    put(x, y, z, b);
                }
            }
        }
        // W3: under the high side of a 2-block edge, a riser from the low ice up
        for (int x = 0; x < sx; x++) {
            for (int z = 0; z < sz; z++) {
                if (h[x][z] == NONE) {
                    continue;
                }
                for (int[] s : SIDES) {
                    int nx = x + s[0];
                    int nz = z + s[1];
                    if (drive(nx, nz) && h[x][z] - h[nx][nz] >= 2) {
                        for (int y = h[nx][nz] + 1; y < h[x][z]; y++) {
                            put(x, y, z, Palette.TRACK_WALL);
                        }
                    }
                }
            }
        }
    }

    /** The wall column beside the lane {@code s} along, on the inward (+1) or outward (-1) side; null if none. */
    int[] wallAt(double s, int side) {
        TrackPath.Seg g = path.segAt(s);
        double hw = baseHalf(s) + pieces.extra(g, s, side > 0);
        double[] p = path.at(s);
        double[] t = path.tangent(s);
        // inward is the right-hand side on a clockwise run
        double sign = side * path.inside();
        double o = (Math.floor(hw) + 1) * sign;
        int x = (int) Math.floor(p[0] + -t[1] * o);
        int z = (int) Math.floor(p[1] + t[0] * o);
        if (!inside(x, z) || !beside(x, z) || obstacle[x][z]) {
            return null;
        }
        return new int[]{x, z};
    }

    /** The drive cell on the centreline {@code s} along, or null. */
    int[] cellAt(double s) {
        double[] p = path.at(s);
        int x = (int) Math.floor(p[0]);
        int z = (int) Math.floor(p[1]);
        return drive(x, z) ? new int[]{x, z} : null;
    }

    /** The caves' roofs and lights, the islands' moss and trees, the forests' canopies. */
    void structures(com.dierks.homecraft.games.gen.api.GenRandom r) {
        for (TrackPieces.Piece p : pieces.list) {
            switch (p.kind) {
                case CAVE -> cave(p);
                case SPLIT -> island(r, p);
                case FOREST -> forest(p);
                default -> {
                }
            }
        }
    }

    private void cave(TrackPieces.Piece p) {
        int ice = profile.level((p.s1 + p.s2) / 2);
        for (int x = 0; x < sx; x++) {
            for (int z = 0; z < sz; z++) {
                if (!near[x][z] || sAt[x][z] <= p.s1 || sAt[x][z] >= p.s2 || segAt[x][z].leg != p.leg
                        || segAt[x][z].arc) {
                    continue;
                }
                boolean lane = h[x][z] == ice;
                boolean wall = beside(x, z) && Math.abs(vAt[x][z]) <= level.width() / 2.0 + 1.01;
                if (!lane && !wall) {
                    continue;
                }
                boolean portal = sAt[x][z] < p.s1 + 1 || sAt[x][z] > p.s2 - 1;
                if (wall) {
                    for (int y = ice + 3; y <= ice + 4; y++) {
                        put(x, y, z, portal ? Palette.MOSS : Palette.GLASS);
                    }
                    long along = Math.round(Math.floor(sAt[x][z] - p.s1));
                    if (along % 5 == 2 && !portal) {
                        put(x, ice + 1, z, Palette.SEA_LANTERN);
                    }
                }
                put(x, ice + DownhillValidator.ROOF, z, portal ? Palette.MOSS : Palette.BLUE_GLASS);
            }
        }
    }

    private void island(com.dierks.homecraft.games.gen.api.GenRandom r, TrackPieces.Piece p) {
        int ice = profile.level((p.s1 + p.s2) / 2);
        List<int[]> middle = new ArrayList<>();
        for (int x = 0; x < sx; x++) {
            for (int z = 0; z < sz; z++) {
                if (!obstacle[x][z] || sAt[x][z] <= p.s1 || sAt[x][z] >= p.s2 || beside(x, z)) {
                    continue;
                }
                // the island's inside: moss to the rim's top
                for (int y = ice; y <= ice + 2; y++) {
                    put(x, y, z, Palette.MOSS);
                }
                middle.add(new int[]{x, z});
            }
        }
        if (middle.isEmpty() || ice + DownhillValidator.ROOF > top) {
            return; // no room for a tree under the scenery cap: a moss island
        }
        String wood = Palette.WOODS.get(r.nextInt(Palette.WOODS.size()));
        double mid = (p.a1 + p.a2) / 2;
        int[] trunk = middle.get(0);
        double bestD = Double.MAX_VALUE;
        for (int[] c : middle) {
            double d = Math.abs(sAt[c[0]][c[1]] - mid) + Math.abs(vAt[c[0]][c[1]] - (p.lo + p.hi) / 2.0);
            if (d < bestD) {
                bestD = d;
                trunk = c;
            }
        }
        for (int y = ice + 3; y <= ice + DownhillValidator.ROOF; y++) {
            put(trunk[0], y, trunk[1], Palette.log(wood));
        }
        int y = ice + DownhillValidator.ROOF;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if ((dx == 0 && dz == 0) || (Math.abs(dx) == 2 && Math.abs(dz) == 2)) {
                    continue;
                }
                int x = trunk[0] + dx;
                int z = trunk[1] + dz;
                if (inside(x, z) && canopyOk(x, y, z)) {
                    leaf(x, y, z, wood);
                }
            }
        }
        if (y + 1 <= top) {
            leaf(trunk[0], y + 1, trunk[1], wood);
        }
    }

    /** Whether a leaf at (x, y, z) keeps the rules: 5 or more over the track, out of every zone's headroom. */
    private boolean canopyOk(int x, int y, int z) {
        if (atEdge(x, z) || y > top) {
            return false;
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int nx = x + dx;
                int nz = z + dz;
                if (!drive(nx, nz)) {
                    continue;
                }
                boolean over = dx == 0 && dz == 0;
                if (y < h[nx][nz] + DownhillValidator.ROOF) {
                    return false;
                }
                if (over && zoneLip[nx][nz] != NONE && y <= zoneLip[nx][nz] + 1 + DownhillValidator.WALL_ABOVE) {
                    return false;
                }
            }
        }
        return true;
    }

    boolean atEdge(int x, int z) {
        int in = DownhillValidator.SCENERY_INSET;
        return x < in || z < in || x >= sx - in || z >= sz - in;
    }

    private void forest(TrackPieces.Piece p) {
        int ice = profile.level((p.s1 + p.s2) / 2);
        String wood = Palette.WOODS.get(Math.floorMod((int) Math.floor(p.s1), 3));
        for (int x = 0; x < sx; x++) {
            for (int z = 0; z < sz; z++) {
                if (!near[x][z] || sAt[x][z] <= p.s1 + 1 || sAt[x][z] >= p.s2 - 1 || segAt[x][z].leg != p.leg
                        || segAt[x][z].arc || obstacle[x][z]) {
                    continue;
                }
                boolean lane = h[x][z] == ice;
                boolean wall = beside(x, z);
                if (!lane && !wall) {
                    continue;
                }
                for (int y = ice + DownhillValidator.ROOF; y <= ice + DownhillValidator.ROOF + 1; y++) {
                    if (empty(x, y, z) && canopyOk(x, y, z)) {
                        leaf(x, y, z, wood);
                    }
                }
            }
        }
    }

    /**
     * The wall's colour language: the pit's lime back wall and grid rows, yellow caps and lights at
     * every drop, light blue beside every checkpoint, gold at the finish and the end wall, magenta
     * arrows every 24 blocks.
     */
    void markers(List<Spot> checkpoints) {
        // the pit's back wall
        for (int x = 0; x < sx; x++) {
            for (int z = 0; z < sz; z++) {
                if (near[x][z] && sAt[x][z] <= 1e-9 && beside(x, z)) {
                    for (int y = profile.top; y <= profile.top + 1; y++) {
                        if (!empty(x, y, z)) {
                            put(x, y, z, Palette.START);
                        }
                    }
                }
            }
        }
        // grid rows: lime in both walls every 4 back from the start
        for (int row = 0; row <= 5; row++) {
            double s = TrackProfile.START - 4 * row;
            mark(s, Palette.START, profile.top + 1, profile.top + 1);
        }
        // drops: yellow caps at the upper ice + 1, a light over them
        for (TrackProfile.Lip l : profile.lips) {
            int upper = profile.level(l.s() - 0.5);
            mark(l.s() - 0.5, Palette.LIP_CAP, upper + 1, upper + 1);
            mark(l.s() - 0.5, Palette.SEA_LANTERN, upper + 2, upper + 2);
        }
        for (Spot c : checkpoints) {
            mark(c.s(), Palette.CHECKPOINT, c.ice() + 1, c.ice() + 1);
        }
        int bottom = profile.bottom();
        mark(profile.finish, Palette.FINISH, bottom + 1, bottom + 2);
        // the end wall: gold in its middle
        double[] e = path.at(profile.end + 0.5);
        int ex = (int) Math.floor(e[0]);
        int ez = (int) Math.floor(e[1]);
        if (inside(ex, ez) && beside(ex, ez) && !empty(ex, bottom + 1, ez)) {
            put(ex, bottom + 1, ez, Palette.FINISH);
        }
        // arrows every 24 blocks on the straights, pointing the way
        for (double s = TrackProfile.START + BoatPlanner.ARROW_SPACING; s < profile.finish - 8;
             s += BoatPlanner.ARROW_SPACING) {
            TrackPath.Seg g = path.segAt(s);
            if (g.arc) {
                continue;
            }
            String arrow = arrowToward(g.tx, g.tz);
            for (int side = -1; side <= 1; side += 2) {
                int[] w = wallAt(s, side);
                if (w == null) {
                    continue;
                }
                int ice = profile.level(s);
                String here = at(w[0], ice + 1, w[1]);
                if (Palette.TRACK_WALL.equals(here)) {
                    put(w[0], ice + 1, w[1], arrow);
                }
            }
        }
        // both branches of every split: an arrow on each side
        for (TrackPieces.Piece p : pieces.list) {
            if (p.kind != TrackPieces.Kind.SPLIT) {
                continue;
            }
            double s = p.a1 + 1;
            TrackPath.Seg g = path.segAt(s);
            String arrow = arrowToward(g.tx, g.tz);
            for (int side = -1; side <= 1; side += 2) {
                int[] w = wallAt(s, side);
                int ice = profile.level(s);
                if (w != null && Palette.TRACK_WALL.equals(at(w[0], ice + 1, w[1]))) {
                    put(w[0], ice + 1, w[1], arrow);
                }
            }
        }
    }

    /** {@code block} from y0 to y1 in both walls beside the lane {@code s} along (only over existing wall blocks). */
    private void mark(double s, String block, int y0, int y1) {
        for (int side = -1; side <= 1; side += 2) {
            int[] w = wallAt(s, side);
            if (w == null) {
                continue;
            }
            for (int y = y0; y <= y1; y++) {
                if (!empty(w[0], y, w[1])) {
                    put(w[0], y, w[1], block);
                }
            }
        }
    }

    /**
     * The magenta arrow pointing the way (dx, dz). Glazed terracotta faces the player who placed it and
     * its arrow points away from them, so an arrow pointing east is the block facing west.
     */
    static String arrowToward(double dx, double dz) {
        if (Math.abs(dx) >= Math.abs(dz)) {
            return Palette.arrow(dx > 0 ? "west" : "east");
        }
        return Palette.arrow(dz > 0 ? "north" : "south");
    }

    /** Minecraft's yaw (0 = south, 90 = west) of a direction across the ground. */
    static double yaw(double dx, double dz) {
        double y = StrictMath.toDegrees(StrictMath.atan2(-dx, dz));
        return y < 0 ? y + 360 : y;
    }

    /** The viewing stand: the 7 x 7 platform at the half's middle, its two-high glass rail. */
    void stand() {
        int floorY = top + 1;
        int r = RaceStand.SIZE / 2;
        for (int x = standX - r; x <= standX + r; x++) {
            for (int z = standZ - r; z <= standZ + r; z++) {
                put(x, floorY, z, RaceStand.FLOOR);
                if (RaceStand.onRail(x, z, standX, standZ)) {
                    for (int y = 1; y <= RaceStand.RAIL; y++) {
                        put(x, floorY + y, z, RaceStand.RAIL_BLOCK);
                    }
                }
            }
        }
    }

    /**
     * A standing sign on the wall top beside the lane {@code s} along, facing the boats coming; the
     * outward wall first. Its (x, y, z) and rotation, or null when neither wall has room.
     *
     * <p>On a bend too (review B2): a piece may start a few blocks into its straight, so the 3-10
     * blocks before it are on the bend, and it would get no sign. The wall is found the same way
     * (the column one past the lane's edge along the normal, which on an arc points at its centre),
     * {@link #wallAt} keeps it off every drive cell, and the sign faces back along the tangent there.
     */
    int[] signSpot(double s) {
        double[] t = path.tangent(s);
        for (int side = -1; side <= 1; side += 2) {
            int[] w = wallAt(s, side);
            if (w == null) {
                continue;
            }
            int y = topOf(w[0], w[1]) + 1;
            if (y > top || !empty(w[0], y, w[1]) || y <= h0) {
                continue;
            }
            int rot = Math.floorMod((int) Math.round(yaw(-t[0], -t[1]) / 22.5), 16);
            return new int[]{w[0], y, w[1], rot};
        }
        return null;
    }

    /** Every leaf written with vanilla's own distance over the plan's own blocks; then the ops. */
    List<com.dierks.homecraft.games.gen.api.BlockOp> ops() {
        List<int[]> logs = new ArrayList<>();
        boolean[] holds = new boolean[palette.size()];
        for (int i = 0; i < palette.size(); i++) {
            holds[i] = Palette.holdsLeaves(palette.get(i));
        }
        for (int x = 0; x < sx; x++) {
            for (int z = 0; z < sz; z++) {
                for (int y = h0; y < h0 + sy; y++) {
                    short v = grid[index(x, y, z)];
                    if (v != 0 && holds[v - 1]) {
                        logs.add(new int[]{x, y, z});
                    }
                }
            }
        }
        java.util.Map<Long, Integer> d = Palette.leafDistances(logs, leafAt);
        for (int i = 0; i < leafAt.size(); i++) {
            int[] l = leafAt.get(i);
            grid[index(l[0], l[1], l[2])] = 0;
            put(l[0], l[1], l[2], Palette.leaves(leafWood.get(i), d.get(Palette.blockKey(l[0], l[1], l[2]))));
        }
        // the placeholder is gone from every block: drop it from the palette
        int placeholder = palette.indexOf("LEAF");
        List<com.dierks.homecraft.games.gen.api.BlockOp> ops = new ArrayList<>();
        List<String> used = new ArrayList<>();
        int[] remap = new int[palette.size()];
        java.util.Arrays.fill(remap, -1);
        for (int x = 0; x < sx; x++) {
            for (int z = 0; z < sz; z++) {
                for (int y = h0; y < h0 + sy; y++) {
                    short v = grid[index(x, y, z)];
                    if (v == 0 || v - 1 == placeholder) {
                        continue;
                    }
                    int p = v - 1;
                    if (remap[p] < 0) {
                        used.add(palette.get(p));
                        remap[p] = used.size() - 1;
                    }
                    ops.add(new com.dierks.homecraft.games.gen.api.BlockOp(half.minX() + x, y, half.minZ() + z,
                            (short) remap[p]));
                }
            }
        }
        palette.clear();
        palette.addAll(used);
        return ops;
    }
}
