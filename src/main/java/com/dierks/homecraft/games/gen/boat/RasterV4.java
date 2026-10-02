package com.dierks.homecraft.games.gen.boat;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenRandom;
import com.dierks.homecraft.games.gen.api.Palette;
import com.dierks.homecraft.games.gen.api.SignText;
import com.dierks.homecraft.games.trial.RaceStand;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A Mountain Run v2 drawn on whole blocks (MOUNTAIN-V2-SPEC §4.3, §7): which columns are track and at
 * what height, the drops' flight zones exactly as {@link MountainValidator} floods them, where the
 * checkpoints go, and then the track's blocks (walls, gate fences, risers, the pieces' structures, the
 * markers, the stand) and its signs. {@link MountainScenery} adds the mountain round it.
 *
 * <p><b>The lane.</b> A column is track when its middle is within the lane's half width of its nearest
 * centreline point ({@link Centreline#nearest}); its height is the level there, so every lip is square
 * to the centreline and runs wall to wall, on straights of any heading (V3's corner rule holds: a
 * diagonal pair at two levels has its side cells at one of them). The finish ice is {@value #FINISH_LY}
 * over the half's floor; every drop still to come lifts the track by its height.
 *
 * <p><b>The proof's own view.</b> The lips and the zones are worked out cell for cell as the validator
 * does (every lip cell's disc of Z(d), flooded along the track at or below it), so the walls are raised
 * where it looks and the checkpoints kept where it allows: flat, ice round the reset, the open sky over
 * them, 3 from every lip and zone, no other ring within reach, a cut of the lane ({@link #cuts}); laid by
 * a fewest-checkpoints chain ({@link #chain}) whose legs keep the rules (60 across, one drop, 60 along with
 * none, a reset facing within 60 degrees of the lane) and whose last leg holds the Final Drop.
 *
 * <p><b>Walls</b> stand in every column beside the track from the lowest ice beside it to 2 over the
 * highest (stripped spruce, glass on top), raised to 2 over the lip round every flight zone; a slalom's
 * gate fences are red or blue concrete with a lantern pole at the opening. Risers fill under every
 * 2-block edge. Pure: no Bukkit.
 */
final class RasterV4 {

    static final int NONE = DeckGraph.NONE;
    static final byte PACKED = 1;
    static final byte BLUE = 2;
    static final byte SAND = 3;
    /** The finish ice stands this far over the half's floor (§4.1). */
    static final int FINISH_LY = 14;
    /** A gate fence is this far either side of its gate along the centreline (thick enough on a sweep's inside). */
    static final double FENCE_HALF = 0.6;
    /** Checkpoint spacing the chain aims at along the track (§3.5, §7.3). */
    static final double SPACING = 45;
    /** A leg's bounds with a margin: across, and along with no drop. */
    static final double LEG_MAX = DownhillValidator.MAX_LEG - 0.01; // the marks are kept on a 1/4096 grid
    static final double FLAT_LEG = 58.5;
    /** A reset faces within this of the lane (§7.3). */
    static final double FACING = 60;
    /** A checkpoint off an axis-aligned straight is this much wider than half the lane, so it spans it. */
    static final double ARC_SPOT = 1.5;
    /** Arrows in the walls this often (§7.1), and signs this far before what they announce. */
    static final double ARROW_EVERY = 40;
    static final int SIGN_BEFORE = 10;
    static final int SIGN_NEAR = 3;
    static final int SIGN_APART = 6;
    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final double EPS = 1e-9;
    /** What a chain's checkpoint costs against a missed one, and a gate opening's extra. */
    private static final long LONG = 1_000;

    final Box half;
    final int sx;
    final int sy;
    final int sz;
    final int y0;
    final Skeleton sk;
    final DropPlan dp;
    final PiecesV4 pieces;
    final MountainTier tier;
    final int finishIce;
    final int topIce;
    /** The stand's centre column, half-local (§4.1: x 240, z 600). */
    final int standX;
    final int standZ;

    final int[] h;
    final byte[] mat;
    final double[] sAt;
    final double[] vAt;
    final int[] elAt;
    final boolean[] near;
    final boolean[] obstacle;
    /** A gate fence's colour: 0 none, 1 red (its opening against the left wall), 2 blue (right); +2 its pole. */
    final byte[] fence;
    int[] lipDrop;
    int[] zoneLip;
    String problem;
    Voxels vox;
    final List<SignText> signs = new ArrayList<>();
    /**
     * The planner's cancel check (audit MTN04, §5.2): run every {@value #TICK_ROWS} x-rows of a pass over the
     * half, here and in {@link MountainScenery}, so a stop lands within a few milliseconds and GenService's
     * online throttle gets its turn. It only throws or sleeps: the raster is the same with it or without.
     */
    final Runnable tick;

    /**
     * How many x-rows of a pass run between two {@link #tick} calls: §5.2 asks for every 32 at most; 8 keeps a
     * stretch near GenService's 10 ms throttle step on the slowest pass (the lane's nearest-point search).
     */
    static final int TICK_ROWS = 8;
    /** Checkpoint places tried, or chain spots joined, between two {@link #tick} calls. */
    static final int TICK_SPOTS = 64;

    RasterV4(Box half, Skeleton sk, DropPlan dp, PiecesV4 pieces) {
        this(half, sk, dp, pieces, () -> {
        });
    }

    RasterV4(Box half, Skeleton sk, DropPlan dp, PiecesV4 pieces, Runnable tick) {
        this.tick = tick;
        this.half = half;
        this.sx = half.sizeX();
        this.sy = half.sizeY();
        this.sz = half.sizeZ();
        this.y0 = half.minY();
        this.sk = sk;
        this.dp = dp;
        this.pieces = pieces;
        this.tier = sk.tier;
        this.finishIce = y0 + FINISH_LY;
        this.topIce = finishIce + dp.descent();
        this.standX = sx / 2;
        this.standZ = sz - MountainValidator.STAND_BACK;
        int n = sx * sz;
        h = new int[n];
        Arrays.fill(h, NONE);
        mat = new byte[n];
        sAt = new double[n];
        vAt = new double[n];
        elAt = new int[n];
        near = new boolean[n];
        obstacle = new boolean[n];
        fence = new byte[n];
        lane();
        seal();
        lips();
        zones();
        check();
    }

    int idx(int x, int z) {
        return x * sz + z;
    }

    /** At the head of x-row {@code x} of a pass over the half: the {@link #tick} every {@value #TICK_ROWS} rows. */
    void row(int x) {
        if (x % TICK_ROWS == 0) {
            tick.run();
        }
    }

    boolean inside(int x, int z) {
        return x >= 0 && z >= 0 && x < sx && z < sz;
    }

    boolean drive(int x, int z) {
        return inside(x, z) && h[idx(x, z)] != NONE;
    }

    /** The track's level {@code s} along: the finish ice, lifted by every drop still to come. */
    int level(double s) {
        return finishIce + dp.levelAbove(s);
    }

    /** Half the lane's width {@code s} along, before the pieces widen it. */
    double baseHalf(double s) {
        return (s < Frame.PIT ? tier.pitWidth : dp.width(s)) / 2.0;
    }

    // ---- the lane ----------------------------------------------------------------------------------------

    private void lane() {
        Centreline line = sk.line;
        double end = line.length();
        double[] p0 = line.at(0);
        double[] t0 = line.tangent(0);
        double[] p1 = line.at(end);
        double[] t1 = line.tangent(end);
        for (int x = 0; x < sx; x++) {
            row(x);
            for (int z = 0; z < sz; z++) {
                if (!line.indexed(x, z)) {
                    continue;
                }
                double qx = x + 0.5;
                double qz = z + 0.5;
                Centreline.Near n = line.nearest(qx, qz);
                if (n == null) {
                    continue;
                }
                double s = n.s();
                if (s <= 1e-9 && (qx - p0[0]) * t0[0] + (qz - p0[1]) * t0[1] < 0) {
                    continue; // behind the pit's back wall
                }
                if (s >= end - 1e-9 && (qx - p1[0]) * t1[0] + (qz - p1[1]) * t1[1] > 0) {
                    continue; // past the end wall
                }
                int i = idx(x, z);
                near[i] = true;
                sAt[i] = s;
                vAt[i] = n.offset();
                elAt[i] = n.element().index;
                double v = n.offset();
                double hw = baseHalf(s) + pieces.extra(s);
                Centreline.Element e = n.element();
                int runoff = pieces.runoff[e.index];
                boolean outside = e.arc() && v * e.turn < 0;
                double limit = hw + (runoff > 0 && outside ? runoff : 0);
                if (n.distance() > limit + EPS) {
                    continue;
                }
                byte gate = gateAt(s, qx, qz);
                if (gate != 0) {
                    fence[i] = gate;
                    obstacle[i] = true;
                    continue;
                }
                if (pieces.obstacle(s, v)) {
                    obstacle[i] = true;
                    continue;
                }
                h[i] = level(s);
                boolean sand = n.distance() > hw + EPS || pieces.sandPit(s, v)
                        || s > sk.finish + Frame.RUN_OUT + 0.5;
                boolean fast = !tier.easy() && (dp.blueAt(s) || pieces.boost(s, v));
                mat[i] = sand ? SAND : fast ? BLUE : PACKED;
            }
        }
    }

    /**
     * Close the wedges where a fence meets a wall at a slant: a run of lane cells along x, z or a diagonal
     * between a fence (not near its pole) and anything else, shorter than the proof's narrowest passage,
     * becomes fence, so the wedge is wall to the proof (its gap rule excuses walls that touch) and never a
     * passage narrower than P. Repeated until nothing changes (a few passes).
     */
    private void seal() {
        if (sk.gates.isEmpty()) {
            return;
        }
        int[][] dirs = {{1, 0}, {0, 1}, {1, 1}, {1, -1}};
        boolean[] nearPole = new boolean[sx * sz];
        for (int x = 0; x < sx; x++) {
            row(x);
            for (int z = 0; z < sz; z++) {
                if (fence[idx(x, z)] < 3) {
                    continue;
                }
                for (int dx = -POLE_KEEP; dx <= POLE_KEEP; dx++) {
                    for (int dz = -POLE_KEEP; dz <= POLE_KEEP; dz++) {
                        if (inside(x + dx, z + dz)) {
                            nearPole[idx(x + dx, z + dz)] = true;
                        }
                    }
                }
            }
        }
        for (int pass = 0; pass < 8; pass++) {
            boolean changed = false;
            for (int x = 1; x < sx - 1; x++) {
                row(x);
                for (int z = 1; z < sz - 1; z++) {
                    int i = idx(x, z);
                    if (fence[i] != 1 && fence[i] != 2) {
                        continue;
                    }
                    for (int[] d : dirs) {
                        for (int sign = -1; sign <= 1; sign += 2) {
                            int ex = d[0] * sign;
                            int ez = d[1] * sign;
                            double unit = ex != 0 && ez != 0 ? Math.sqrt(2) : 1;
                            int n = 0;
                            boolean pole = false;
                            while (drive(x + (n + 1) * ex, z + (n + 1) * ez) && (n + 1) * unit < tier.proofP + 0.5) {
                                n++;
                                pole |= nearPole[idx(x + n * ex, z + n * ez)];
                            }
                            int fx = x + (n + 1) * ex;
                            int fz = z + (n + 1) * ez;
                            if (n == 0 || pole || drive(fx, fz) || (n + 0) * unit >= tier.proofP - 1e-9) {
                                continue;
                            }
                            for (int k = 1; k <= n; k++) {
                                int c = idx(x + k * ex, z + k * ez);
                                h[c] = NONE;
                                fence[c] = fence[i];
                                obstacle[c] = true;
                                mat[c] = 0;
                            }
                            changed = true;
                        }
                    }
                }
            }
            if (!changed) {
                break;
            }
        }
    }

    /** No wedge is closed this near a gate's pole (its opening stays as wide as drawn). */
    static final int POLE_KEEP = 2;

    /**
     * The gate fence at column middle (qx, qz), {@code s} along: 0 none, 1 red, 2 blue, 3 a red pole, 4 a
     * blue pole. A fence is a straight band {@value #FENCE_HALF} either side of the line across the lane at
     * its gate (not a band of s, which a bend squeezes on its inside), so it meets the wall with no leak.
     */
    byte gateAt(double s, double qx, double qz) {
        for (Skeleton.GateSet g : sk.gates) {
            if (s < g.from() - 3 || s > g.to() + 3) {
                continue;
            }
            for (int i = 0; i < g.gates(); i++) {
                double at = g.at()[i];
                if (Math.abs(s - at) > 3) {
                    continue;
                }
                double[] p = sk.line.at(at);
                double hd = sk.line.heading(at);
                double tx = Math.cos(hd);
                double tz = Math.sin(hd);
                if (Math.abs((qx - p[0]) * tx + (qz - p[1]) * tz) >= FENCE_HALF) {
                    continue;
                }
                double v = (qx - p[0]) * -tz + (qz - p[1]) * tx;
                int side = g.side(i);
                double edge = tier.width / 2.0 - tier.gate; // the opening runs from here to the wall
                if (side * v >= edge - 0.5) {
                    return 0; // the opening
                }
                boolean pole = side * v >= edge - 1.5;
                byte colour = (byte) (side > 0 ? 2 : 1);
                return (byte) (pole ? colour + 2 : colour);
            }
        }
        return 0;
    }

    /** Whether column (x, z) isn't track but is beside some (8 ways): a wall, a fence, a rim, a trunk. */
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
        for (int v : h) {
            if (v != NONE) {
                m = Math.min(m, v);
            }
        }
        return m;
    }

    // ---- the proof's view: lips and zones -----------------------------------------------------------------

    private void lips() {
        lipDrop = new int[sx * sz];
        for (int x = 0; x < sx; x++) {
            row(x);
            for (int z = 0; z < sz; z++) {
                int i = idx(x, z);
                if (h[i] == NONE) {
                    continue;
                }
                for (int[] s : SIDES) {
                    int nx = x + s[0];
                    int nz = z + s[1];
                    if (drive(nx, nz) && h[idx(nx, nz)] < h[i]) {
                        lipDrop[i] = Math.max(lipDrop[i], h[i] - h[idx(nx, nz)]);
                    }
                }
            }
        }
    }

    /** {@link MountainValidator}'s flood, cell for cell: each lip cell's disc of Z(d), along the track at or below it. */
    private void zones() {
        zoneLip = new int[sx * sz];
        Arrays.fill(zoneLip, NONE);
        int[] stamp = new int[sx * sz];
        int flood = 0;
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        for (int x = 0; x < sx; x++) {
            row(x);
            for (int z = 0; z < sz; z++) {
                int i = idx(x, z);
                if (lipDrop[i] == 0) {
                    continue;
                }
                flood++;
                int lip = h[i];
                double reach = BoatEnvelope.zone(lipDrop[i]);
                stamp[i] = flood;
                queue.add(new int[]{x, z});
                while (!queue.isEmpty()) {
                    int[] c = queue.poll();
                    int ci = idx(c[0], c[1]);
                    if (h[ci] < lip) {
                        zoneLip[ci] = Math.max(zoneLip[ci], lip);
                        if (lipDrop[ci] > 0 && problem == null) {
                            problem = "a drop at " + c[0] + " " + c[1] + " is in the flight zone of the drop at " + x
                                    + " " + z;
                        }
                    }
                    for (int[] s : SIDES) {
                        int nx = c[0] + s[0];
                        int nz = c[1] + s[1];
                        if (!drive(nx, nz)) {
                            continue;
                        }
                        int ni = idx(nx, nz);
                        if (stamp[ni] != flood && h[ni] <= lip
                                && (nx - x) * (nx - x) + (nz - z) * (nz - z) <= reach * reach + EPS) {
                            stamp[ni] = flood;
                            queue.add(new int[]{nx, nz});
                        }
                    }
                }
            }
        }
    }

    /** What keeps this raster from being a Mountain Run before a block is placed: the rim, the stand. */
    private void check() {
        if (problem != null) {
            return;
        }
        int r = RaceStand.SIZE / 2;
        for (int x = 0; x < sx; x++) {
            row(x);
            for (int z = 0; z < sz; z++) {
                if (h[idx(x, z)] == NONE) {
                    continue;
                }
                if (x < 2 || z < 2 || x >= sx - 2 || z >= sz - 2) {
                    problem = "the track reaches the half's rim at " + x + " " + z;
                    return;
                }
                int dx = Math.max(0, Math.abs(x - standX) - r);
                int dz = Math.max(0, Math.abs(z - standZ) - r);
                if (Math.hypot(dx, dz) < RaceStand.LANE_CLEARANCE) {
                    problem = "the track at " + x + " " + z + " is too near the stand";
                    return;
                }
            }
        }
    }

    // ---- checkpoints --------------------------------------------------------------------------------------

    /**
     * A place a checkpoint may go: {@code s} along, its middle (half-local), its radius, its ice, and the
     * lane's direction there as the proof reads it off the blocks ({@link #laneRead}).
     */
    record Spot(double s, double x, double z, double r, int ice, double lx, double lz, boolean gate) {
    }

    /** A checkpoint spans the widest of the lane this far either side of it (a neck's taper). */
    static final double SPOT_LOOK = 8;

    /** The radius a checkpoint {@code s} along needs to span the lane (a gate opening's aside). */
    double spotRadius(double s) {
        Centreline.Element e = sk.line.elementAt(s);
        double half = 0;
        for (double u = s - SPOT_LOOK; u <= s + SPOT_LOOK; u += 1) {
            half = Math.max(half, baseHalf(Math.max(0, Math.min(sk.line.length(), u))));
        }
        boolean axis = !e.arc() && Math.abs(Math.sin(2 * e.h0)) < 1e-6;
        double r = axis ? half + 0.5 : half + ARC_SPOT + 0.5;
        if (e.arc() && pieces.runoff[e.index] > 0) {
            r += pieces.runoff[e.index];
        }
        return r;
    }

    /** Whether {@code s} lies in a gate field (a fence less 3 to a fence plus 3). */
    boolean inGates(double s, double r) {
        for (Skeleton.GateSet g : sk.gates) {
            if (s + r + 3 >= g.from() && s - r - 3 <= g.to()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Every place a checkpoint may go before the Final Drop's lip (its leg runs on to the finish), in order
     * along the track: on the lane every block where the rules allow, and in every gate opening.
     */
    List<Spot> spots(List<double[]> blocked) {
        List<Spot> out = new ArrayList<>();
        DropPlan.Drop last = dp.last();
        double until = last == null ? sk.finish : last.s();
        int tried = 0;
        for (double s = sk.start + 6; s < until; s += nearLip(s) ? 0.5 : 1) {
            if (++tried % TICK_SPOTS == 0) {
                tick.run();
            }
            double r = spotRadius(s);
            if (s - r < sk.start + 1.5 || inGates(s, r + GROW) || isBlocked(blocked, s, r + GROW)) {
                continue;
            }
            double[] p = sk.line.at(s);
            Spot spot = spot(s, p[0], p[1], r, false);
            if (spot != null) {
                out.add(spot);
            }
        }
        for (Skeleton.GateSet g : sk.gates) {
            for (int i = 0; i < g.gates(); i++) {
                double s = g.at()[i];
                if (s >= until || isBlocked(blocked, s, 1)) {
                    continue;
                }
                double[] p = sk.line.at(s);
                double hd = sk.line.heading(s);
                double off = g.side(i) * (tier.width / 2.0 - tier.gate / 2.0);
                double x = p[0] + -Math.sin(hd) * off;
                double z = p[1] + Math.cos(hd) * off;
                Spot spot = spot(s, x, z, tier.gate / 2.0 + 0.5, true);
                if (spot != null) {
                    out.add(spot);
                }
            }
        }
        out.sort((a, b) -> Double.compare(a.s(), b.s()));
        return out;
    }

    /**
     * How much a checkpoint's sphere may grow past its rule radius, in half blocks, when the lane's
     * cells don't sit square on its centreline (a lane whose edge cell's far corner is just past
     * {@code w/2 + 0.5} isn't cut by the smaller sphere).
     */
    static final double GROW = 1.5;

    /** The spot at (px, pz): the smallest sphere from {@code r} up by {@link #GROW} that keeps the rules, or {@code null}. */
    /** Whether {@code s} is just before a lip or about where its flight zone ends (checkpoints are tried every half block there). */
    private boolean nearLip(double s) {
        for (DropPlan.Drop d : dp.drops) {
            double z = BoatEnvelope.zone(d.drop());
            if ((s > d.s() - 16 && s < d.s()) || (s > d.s() + z && s < d.s() + z + 12)) {
                return true;
            }
        }
        return false;
    }

    private Spot spot(double s, double px, double pz, double r, boolean gate) {
        for (double grow = 0; grow <= GROW + 1e-9; grow += 0.5) {
            double rr = r + grow;
            if (!ok(px, pz, rr) || !cuts(s, px, pz, rr)) {
                continue;
            }
            double[] lane = laneRead(s, px, pz, rr);
            if (lane == null) {
                continue;
            }
            return new Spot(s, px, pz, rr, h[idx((int) Math.floor(px), (int) Math.floor(pz))], lane[0], lane[1], gate);
        }
        return null;
    }

    /**
     * Whether a blocked stretch covers spot {@code s} by the rule {@link #spots} drops it by: its rule radius
     * grown by {@link #GROW}, a gate's 1 (audit MTN-R3-00: the pieces' trial read the spot's own radius and let
     * pieces through that left their own raster no chain).
     */
    boolean blocks(List<double[]> blocked, Spot s) {
        return isBlocked(blocked, s.s(), s.gate() ? 1 : spotRadius(s.s()) + GROW);
    }

    static boolean isBlocked(List<double[]> blocked, double s, double r) {
        for (double[] b : blocked) {
            if (s + r + 1.5 > b[0] && s - r - 1.5 < b[1]) {
                return true;
            }
        }
        return false;
    }

    /**
     * The validator's checkpoint rules at (px, pz), radius r (V6): on the track, flat, ice round the reset,
     * nothing over it, no island within reach, 3 from every lip and zone, no other part of the track in
     * reach of its sphere unless joined to it within 3 radii.
     */
    boolean ok(double px, double pz, double r) {
        int cx = (int) Math.floor(px);
        int cz = (int) Math.floor(pz);
        if (!drive(cx, cz)) {
            return false;
        }
        int ice = h[idx(cx, cz)];
        List<int[]> nearCells = new ArrayList<>();
        int span = (int) Math.ceil(r) + 1;
        for (int x = cx - span; x <= cx + span; x++) {
            for (int z = cz - span; z <= cz + span; z++) {
                if (inside(x, z) && obstacle[idx(x, z)] && fence[idx(x, z)] == 0
                        && squareDistance(x, z, px, pz) <= r + EPS) {
                    return false; // an island (a split's, a trunk) within its reach
                }
                if (!drive(x, z)) {
                    continue;
                }
                double d2 = sq(x + 0.5 - px) + sq(z + 0.5 - pz);
                if (d2 <= r * r + EPS) {
                    int i = idx(x, z);
                    if (h[i] != ice || (mat[i] == SAND
                            && d2 <= DownhillValidator.RESET_ROOM * DownhillValidator.RESET_ROOM + EPS)) {
                        return false;
                    }
                    nearCells.add(new int[]{x, z});
                }
            }
        }
        int clear = (int) Math.ceil(DownhillValidator.DROP_CLEAR) + 1;
        for (int x = cx - clear; x <= cx + clear; x++) {
            for (int z = cz - clear; z <= cz + clear; z++) {
                if (!inside(x, z)) {
                    continue;
                }
                double d = Math.hypot(x + 0.5 - px, z + 0.5 - pz);
                int i = idx(x, z);
                if (d < DownhillValidator.DROP_CLEAR - 1e-6 && (lipDrop[i] > 0 || zoneLip[i] != NONE)) {
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
        int[] steps = new int[n * n];
        Arrays.fill(steps, -1);
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        for (int[] c : nearCells) {
            steps[(c[0] - x0) * n + c[1] - z0] = 0;
            queue.add(c);
        }
        while (!queue.isEmpty()) {
            int[] c = queue.poll();
            int d = steps[(c[0] - x0) * n + c[1] - z0];
            if (d >= limit) {
                continue;
            }
            for (int[] s : SIDES) {
                int nx = c[0] + s[0];
                int nz = c[1] + s[1];
                if (nx < x0 || nz < z0 || nx >= x0 + n || nz >= z0 + n || !drive(nx, nz)
                        || steps[(nx - x0) * n + nz - z0] >= 0) {
                    continue;
                }
                steps[(nx - x0) * n + nz - z0] = d + 1;
                queue.add(new int[]{nx, nz});
            }
        }
        int rr = (int) Math.ceil(reach) + 1;
        for (int x = cx - rr; x <= cx + rr; x++) {
            for (int z = cz - rr; z <= cz + rr; z++) {
                if (drive(x, z) && steps[(x - x0) * n + z - z0] < 0
                        && sq(x + 0.5 - px) + sq(z + 0.5 - pz) <= reach * reach + EPS) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Whether the blocks wholly inside the sphere at (px, pz) cut the lane {@code s} along: no way from the
     * track just before it to the track just after it goes round them (the validator's cut, looked at where
     * it matters).
     */
    boolean cuts(double s, double px, double pz, double r) {
        int span = (int) Math.ceil(r) + 8;
        int cx = (int) Math.floor(px);
        int cz = (int) Math.floor(pz);
        int n = 2 * span + 1;
        boolean[] seen = new boolean[n * n];
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        for (int x = cx - span; x <= cx + span; x++) {
            for (int z = cz - span; z <= cz + span; z++) {
                if (drive(x, z) && band(x, z, s, r) && sAt[idx(x, z)] < s - r - 1.5 && !whollyIn(x, z, px, pz, r)) {
                    seen[(x - cx + span) * n + z - cz + span] = true;
                    queue.add(new int[]{x, z});
                }
            }
        }
        while (!queue.isEmpty()) {
            int[] c = queue.poll();
            if (sAt[idx(c[0], c[1])] > s + r + 1.5) {
                return false;
            }
            for (int[] d : SIDES) {
                int x = c[0] + d[0];
                int z = c[1] + d[1];
                if (x < cx - span || z < cz - span || x > cx + span || z > cz + span || !drive(x, z)
                        || seen[(x - cx + span) * n + z - cz + span] || !band(x, z, s, r) || whollyIn(x, z, px, pz, r)) {
                    continue;
                }
                seen[(x - cx + span) * n + z - cz + span] = true;
                queue.add(new int[]{x, z});
            }
        }
        return true;
    }

    private boolean band(int x, int z, double s, double r) {
        return Math.abs(sAt[idx(x, z)] - s) <= r + 6;
    }

    /** Whether column (x, z) lies wholly within r of (px, pz) (the validator's "inside"). */
    static boolean whollyIn(int x, int z, double px, double pz, double r) {
        double fx = Math.max(Math.abs(x - px), Math.abs(x + 1 - px));
        double fz = Math.max(Math.abs(z - pz), Math.abs(z + 1 - pz));
        return fx * fx + fz * fz <= r * r + EPS;
    }

    /**
     * The lane's direction at a checkpoint, read off the blocks as the validator reads it for its facing
     * rule: from the middle of the ice cells just before the blocks wholly inside the sphere to the middle
     * of those just past them; a unit {x, z}, or {@code null}.
     */
    double[] laneRead(double s, double px, double pz, double r) {
        int cx = (int) Math.floor(px);
        int cz = (int) Math.floor(pz);
        int span = (int) Math.ceil(r) + 2;
        int w = span + 2;
        int n = 2 * w + 1;
        boolean[] in = new boolean[n * n];
        for (int x = cx - w; x <= cx + w; x++) {
            for (int z = cz - w; z <= cz + w; z++) {
                in[(x - cx + w) * n + z - cz + w] = drive(x, z) && whollyIn(x, z, px, pz, r);
            }
        }
        boolean[] before = new boolean[n * n];
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        for (int x = cx - w; x <= cx + w; x++) {
            for (int z = cz - w; z <= cz + w; z++) {
                int k = (x - cx + w) * n + z - cz + w;
                if (drive(x, z) && !in[k] && sAt[idx(x, z)] < s - r - 1.5) {
                    before[k] = true;
                    queue.add(new int[]{x, z});
                }
            }
        }
        while (!queue.isEmpty()) {
            int[] c = queue.poll();
            for (int[] d : SIDES) {
                int x = c[0] + d[0];
                int z = c[1] + d[1];
                if (x < cx - w || z < cz - w || x > cx + w || z > cz + w || !drive(x, z)) {
                    continue;
                }
                int k = (x - cx + w) * n + z - cz + w;
                if (in[k] || before[k] || h[idx(x, z)] > h[idx(c[0], c[1])]) {
                    continue;
                }
                before[k] = true;
                queue.add(new int[]{x, z});
            }
        }
        double[] a = new double[3];
        double[] b = new double[3];
        for (int x = cx - span; x <= cx + span; x++) {
            for (int z = cz - span; z <= cz + span; z++) {
                if (!drive(x, z) || mat[idx(x, z)] == SAND) {
                    continue;
                }
                int k = (x - cx + w) * n + z - cz + w;
                if (in[k]) {
                    continue;
                }
                boolean edge = false;
                for (int[] d : SIDES) {
                    int ex = x + d[0] - cx + w;
                    int ez = z + d[1] - cz + w;
                    edge |= ex >= 0 && ez >= 0 && ex < n && ez < n && in[ex * n + ez];
                }
                if (!edge) {
                    continue;
                }
                double[] side = before[k] ? a : b;
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

    /** The finish's radius: the lane's half width + 1.5. */
    double finishRadius() {
        return tier.width / 2.0 + 1.5;
    }

    /**
     * The checkpoints from the start to the finish through {@code spots}: legs at most {@value #LEG_MAX}
     * across with at most one drop each, at most {@value #FLAT_LEG} along with none, every reset facing the
     * next target within {@value #FACING} degrees of the lane, the finish's own leg holding the Final Drop;
     * the fewest checkpoints that keep a checkpoint about every {@value #SPACING} along. {@code null} when
     * no chain keeps the rules.
     */
    List<Spot> chain(List<Spot> spots) {
        double[] st = sk.line.at(sk.start);
        double[] fp = sk.line.at(sk.finish);
        Spot start = new Spot(sk.start, st[0], st[1], 0, topIce, 0, 0, false);
        Spot finish = new Spot(sk.finish, fp[0], fp[1], finishRadius(), finishIce, 0, 0, false);
        int n = spots.size();
        long[] best = new long[n + 1];
        int[] prev = new int[n + 1];
        Arrays.fill(best, Long.MAX_VALUE);
        int lo = 0;
        for (int i = 0; i <= n; i++) {
            if (i % TICK_SPOTS == 0) {
                tick.run();
            }
            Spot b = i < n ? spots.get(i) : finish;
            long add = i < n ? 1 : 0;
            if (leg(start, b, true, i == n)) {
                best[i] = add + LONG * missed(start, b);
                prev[i] = -1;
            }
            while (lo < i && b.s() - spots.get(lo).s() > 3 * LEG_MAX) {
                lo++;
            }
            for (int j = lo; j < Math.min(i, n); j++) {
                if (best[j] == Long.MAX_VALUE) {
                    continue;
                }
                Spot a = spots.get(j);
                long c = best[j] + add;
                if (c > best[i]) {
                    continue;
                }
                if (!leg(a, b, false, i == n)) {
                    continue;
                }
                c += LONG * missed(a, b);
                if (c < best[i] || (c == best[i] && prev[i] < j)) {
                    best[i] = c;
                    prev[i] = j;
                }
            }
        }
        if (best[n] == Long.MAX_VALUE) {
            return null;
        }
        List<Spot> out = new ArrayList<>();
        for (int i = prev[n]; i >= 0; i = prev[i]) {
            out.add(0, spots.get(i));
        }
        return out;
    }

    /** How many checkpoints leg a-b misses at {@value #SPACING} apart (past the zone where a drop is in it). */
    private long missed(Spot a, Spot b) {
        double along = b.s() - a.s();
        if (dp.lipsBetween(a.s(), b.s()) > 0) {
            along -= LEG_MAX;
        }
        return along <= SPACING ? 0 : (long) Math.ceil(along / SPACING) - 1;
    }

    /** Whether leg a-b keeps the rules (a the start when {@code fromStart}, b the finish when {@code toFinish}). */
    boolean leg(Spot a, Spot b, boolean fromStart, boolean toFinish) {
        if (b.s() <= a.s()) {
            return false;
        }
        double across = Math.hypot(b.x() - a.x(), b.z() - a.z());
        if (across > LEG_MAX) {
            return false;
        }
        if (fromStart) {
            if (across <= b.r() + 1.5) {
                return false;
            }
        } else if (across <= a.r() + b.r() + 0.5) {
            return false;
        }
        int drops = dp.lipsBetween(a.s(), b.s());
        if (drops > 1 || (toFinish && drops != 1)) {
            return false;
        }
        if (drops == 0 && b.s() - a.s() > FLAT_LEG) {
            return false;
        }
        return fromStart || faces(a, b);
    }

    /** Whether a reset at {@code a} faces on down the track toward {@code b}: within the facing rule of both the tangent and the lane read off the blocks. */
    boolean faces(Spot a, Spot b) {
        double[] t = sk.line.tangent(a.s());
        return offLane(a, b, t[0], t[1]) <= FACING + 1e-6 && offLane(a, b, a.lx(), a.lz()) <= FACING - 1e-3;
    }

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

    // ---- the blocks ----------------------------------------------------------------------------------------

    /** Start the blocks: the drive cells. */
    void blocks() {
        vox = new Voxels(sx, sy, sz, y0);
        for (int x = 0; x < sx; x++) {
            row(x);
            for (int z = 0; z < sz; z++) {
                int i = idx(x, z);
                if (h[i] == NONE) {
                    continue;
                }
                vox.put(x, h[i], z, mat[i] == SAND ? Palette.SAND : mat[i] == BLUE ? Palette.TRACK_FAST : Palette.TRACK);
            }
        }
    }

    /**
     * Every column beside the track: solid from the lowest ice beside it to 2 over the highest, raised to 2
     * over the lip round flight zones; stripped spruce to the ice + 1, glass on top (and glass on the
     * stand's side of the finish straight, so watchers see the finish); gate fences in their colour with a
     * lantern pole; islands with a spruce rim, trunks of logs. Risers under every 2-block edge.
     */
    void walls() {
        for (int x = 0; x < sx; x++) {
            row(x);
            for (int z = 0; z < sz; z++) {
                if (!beside(x, z)) {
                    continue;
                }
                int lo = Integer.MAX_VALUE;
                int hi = Integer.MIN_VALUE;
                int need = Integer.MIN_VALUE;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (!drive(x + dx, z + dz)) {
                            continue;
                        }
                        int ni = idx(x + dx, z + dz);
                        lo = Math.min(lo, h[ni]);
                        hi = Math.max(hi, h[ni]);
                        need = Math.max(need, h[ni] + DownhillValidator.WALL_ABOVE);
                        if (zoneLip[ni] != NONE) {
                            need = Math.max(need, zoneLip[ni] + DownhillValidator.WALL_ABOVE);
                        }
                    }
                }
                int i = idx(x, z);
                if (fence[i] != 0) {
                    String colour = (fence[i] - 1) % 2 == 0 ? GATE_LEFT : GATE_RIGHT;
                    boolean pole = fence[i] > 2;
                    for (int y = lo; y <= need; y++) {
                        vox.put(x, y, z, colour);
                    }
                    if (pole) {
                        vox.put(x, need, z, Palette.SEA_LANTERN);
                        vox.put(x, need + 1, z, colour);
                    }
                    continue;
                }
                PiecesV4.Piece p = near[i] && obstacle[i] ? pieces.at(sAt[i]) : null;
                if (p != null && p.kind == PiecesV4.Kind.FOREST) {
                    String log = Palette.log(Palette.WOODS.get(Math.floorMod((int) Math.floor(p.s1), 3)));
                    for (int y = lo; y <= Math.max(need, hi + TRUNK_TOP); y++) {
                        vox.put(x, y, z, log);
                    }
                    continue;
                }
                boolean glassSide = standSide(x, z);
                for (int y = lo; y <= need; y++) {
                    boolean wood = y <= hi + 1 && !(glassSide && y > lo);
                    vox.put(x, y, z, wood || (p != null && y <= hi + 2) ? Palette.TRACK_WALL : Palette.GLASS);
                }
            }
        }
        // W3: under the high side of a 2-block edge, a riser from the low ice up
        for (int x = 0; x < sx; x++) {
            row(x);
            for (int z = 0; z < sz; z++) {
                int i = idx(x, z);
                if (h[i] == NONE) {
                    continue;
                }
                for (int[] s : SIDES) {
                    if (drive(x + s[0], z + s[1]) && h[i] - h[idx(x + s[0], z + s[1])] >= 2) {
                        for (int y = h[idx(x + s[0], z + s[1])] + 1; y < h[i]; y++) {
                            vox.put(x, y, z, Palette.TRACK_WALL);
                        }
                    }
                }
            }
        }
    }

    /** A trunk's logs reach this far over the ice (its canopy 5 and 6 up). */
    static final int TRUNK_TOP = 6;
    static final String GATE_LEFT = Palette.GATE_LEFT;
    static final String GATE_RIGHT = Palette.GATE_RIGHT;

    /** Whether wall column (x, z) is on the stand's side of the finish straight, in front of it: glass, for the view. */
    boolean standSide(int x, int z) {
        return Math.abs(x - standX) <= 4 && z > standZ - 40 && z < standZ - RaceStand.SIZE / 2;
    }

    /** The caves' glass roofs, the tunnels' rock, the islands' moss and trees, the forests' canopies. */
    void structures(GenRandom r) {
        for (PiecesV4.Piece p : pieces.list) {
            switch (p.kind) {
                case CAVE -> roofed(p, false);
                case TUNNEL -> roofed(p, true);
                case SPLIT -> island(r, p);
                case FOREST -> forest(p);
                default -> {
                }
            }
        }
    }

    /** Columns whose nearest centreline point is in (from, to) on piece {@code p}'s stretch (straight or bend). */
    private List<int[]> columnsOf(PiecesV4.Piece p, double from, double to) {
        List<int[]> out = new ArrayList<>();
        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        for (double s = p.s1; ; s = Math.min(p.s2, s + 2)) {
            double[] a = sk.line.at(s);
            minX = Math.min(minX, a[0]);
            maxX = Math.max(maxX, a[0]);
            minZ = Math.min(minZ, a[1]);
            maxZ = Math.max(maxZ, a[1]);
            if (s >= p.s2) {
                break;
            }
        }
        double reach = tier.width / 2.0 + PiecesV4.WIDEN + 3;
        int x0 = Math.max(0, (int) Math.floor(minX - reach));
        int x1 = Math.min(sx - 1, (int) Math.ceil(maxX + reach));
        int z0 = Math.max(0, (int) Math.floor(minZ - reach));
        int z1 = Math.min(sz - 1, (int) Math.ceil(maxZ + reach));
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                int i = idx(x, z);
                if (near[i] && sAt[i] > from && sAt[i] < to) {
                    out.add(new int[]{x, z});
                }
            }
        }
        return out;
    }

    /** A cave (glass roof exactly 5 over the ice, glass walls up to it) or a tunnel (stone, lanterns every 6). */
    private void roofed(PiecesV4.Piece p, boolean rock) {
        int ice = level((p.s1 + p.s2) / 2);
        String roof = rock ? Palette.STONE : Palette.BLUE_GLASS;
        String side = rock ? Palette.STONE : Palette.GLASS;
        for (int[] c : columnsOf(p, p.s1, p.s2)) {
            int x = c[0];
            int z = c[1];
            int i = idx(x, z);
            boolean lane = h[i] == ice;
            boolean wall = beside(x, z) && Math.abs(vAt[i]) <= baseHalf(sAt[i]) + 1.01;
            if (!lane && !wall) {
                continue;
            }
            if (wall) {
                for (int y = ice + 3; y < ice + DownhillValidator.ROOF; y++) {
                    vox.put(x, y, z, side);
                }
                long along = Math.round(Math.floor(sAt[i] - p.s1));
                if (along % (rock ? PiecesV4.LANTERN_EVERY : 5) == 2) {
                    vox.put(x, ice + 1, z, Palette.SEA_LANTERN);
                }
            }
            vox.put(x, ice + DownhillValidator.ROOF, z, roof);
            if (rock) {
                vox.put(x, ice + DownhillValidator.ROOF + 1, z, Palette.STONE);
            }
        }
    }

    /** A split's island: moss inside a spruce rim, a tree in the middle. */
    private void island(GenRandom r, PiecesV4.Piece p) {
        int ice = level((p.s1 + p.s2) / 2);
        List<int[]> inner = new ArrayList<>();
        for (int[] c : columnsOf(p, p.a1 - 1, p.a2 + 1)) {
            int i = idx(c[0], c[1]);
            if (!obstacle[i] || fence[i] != 0 || beside(c[0], c[1])) {
                continue;
            }
            for (int y = ice; y <= ice + 2; y++) {
                vox.put(c[0], y, c[1], Palette.MOSS);
            }
            inner.add(c);
        }
        if (inner.isEmpty()) {
            return;
        }
        String wood = Palette.WOODS.get(r.nextInt(Palette.WOODS.size()));
        double mid = (p.a1 + p.a2) / 2;
        int[] trunk = inner.get(0);
        double bestD = Double.MAX_VALUE;
        for (int[] c : inner) {
            double d = Math.abs(sAt[idx(c[0], c[1])] - mid) + Math.abs(vAt[idx(c[0], c[1])]);
            if (d < bestD) {
                bestD = d;
                trunk = c;
            }
        }
        for (int y = ice + 3; y <= ice + DownhillValidator.ROOF; y++) {
            vox.put(trunk[0], y, trunk[1], Palette.log(wood));
        }
        int y = ice + DownhillValidator.ROOF;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if ((dx == 0 && dz == 0) || (Math.abs(dx) == 2 && Math.abs(dz) == 2)) {
                    continue;
                }
                canopy(trunk[0] + dx, y, trunk[1] + dz, wood);
            }
        }
        canopy(trunk[0], y + 1, trunk[1], wood);
    }

    /** A forest's canopy: leaves 5 and 6 over the lane and its walls. */
    private void forest(PiecesV4.Piece p) {
        int ice = level((p.s1 + p.s2) / 2);
        String wood = Palette.WOODS.get(Math.floorMod((int) Math.floor(p.s1), 3));
        for (int[] c : columnsOf(p, p.a1, p.a2)) {
            int i = idx(c[0], c[1]);
            if (obstacle[i]) {
                continue;
            }
            if (h[i] != ice && !beside(c[0], c[1])) {
                continue;
            }
            for (int y = ice + DownhillValidator.ROOF; y <= ice + DownhillValidator.ROOF + 1; y++) {
                canopy(c[0], y, c[1], wood);
            }
        }
    }

    /** Leaves waiting for their distance: {x, y, z} and wood. */
    final List<int[]> leafAt = new ArrayList<>();
    final List<String> leafWood = new ArrayList<>();

    /** A leaf of {@code wood} at (x, y, z) where the rules allow one: 5 or more over the track, out of every landing. */
    void canopy(int x, int y, int z, String wood) {
        if (!vox.in(x, y, z) || !vox.empty(x, y, z) || x < 2 || z < 2 || x >= sx - 2 || z >= sz - 2) {
            return;
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!drive(x + dx, z + dz)) {
                    continue;
                }
                int ni = idx(x + dx, z + dz);
                if (y < h[ni] + DownhillValidator.ROOF) {
                    return;
                }
                if (dx == 0 && dz == 0 && zoneLip[ni] != NONE && y <= zoneLip[ni] + 1 + DownhillValidator.WALL_ABOVE) {
                    return;
                }
            }
        }
        leaf(x, y, z, wood);
    }

    /** A leaf at (x, y, z), its distance worked out when every block is down. */
    void leaf(int x, int y, int z, String wood) {
        vox.put(x, y, z, LEAF);
        leafAt.add(new int[]{x, y, z});
        leafWood.add(wood);
    }

    /** The placeholder a leaf stands as until its distance is known. */
    static final String LEAF = "minecraft:air[leaf]";

    /**
     * The wall's colour language (§7.1): the pit's lime back wall and grid rows, yellow caps and lanterns
     * at every lip, light blue beside every checkpoint, gold at the finish and the end wall, magenta
     * arrows every {@value #ARROW_EVERY} and at every bend's entry.
     */
    void markers(List<Spot> checkpoints) {
        for (int x = 0; x < sx; x++) {
            row(x);
            for (int z = 0; z < sz; z++) {
                int i = idx(x, z);
                if (near[i] && sAt[i] <= 1e-9 && beside(x, z)) {
                    for (int y = topIce; y <= topIce + 1; y++) {
                        if (!vox.empty(x, y, z)) {
                            vox.put(x, y, z, Palette.START);
                        }
                    }
                }
            }
        }
        for (int row = 0; row <= 5; row++) {
            mark(sk.start - 4 * row, Palette.START, topIce + 1, topIce + 1);
        }
        for (DropPlan.Drop l : dp.drops) {
            int upper = level(l.s() - 0.5);
            mark(l.s() - 0.5, Palette.LIP_CAP, upper + 1, upper + 1);
            mark(l.s() - 0.5, Palette.SEA_LANTERN, upper + 2, upper + 2);
        }
        for (Spot c : checkpoints) {
            if (!c.gate()) {
                mark(c.s(), Palette.CHECKPOINT, c.ice() + 1, c.ice() + 1);
            }
        }
        mark(sk.finish, Palette.FINISH, finishIce + 1, finishIce + 2);
        double[] e = sk.line.at(sk.end + 0.6);
        int ex = (int) Math.floor(e[0]);
        int ez = (int) Math.floor(e[1]);
        if (inside(ex, ez) && beside(ex, ez) && !vox.empty(ex, finishIce + 1, ez)) {
            vox.put(ex, finishIce + 1, ez, Palette.FINISH);
        }
        List<Double> arrows = new ArrayList<>();
        for (double s = sk.start + ARROW_EVERY; s < sk.finish - 8; s += ARROW_EVERY) {
            arrows.add(s);
        }
        for (Centreline.Element el : sk.line.elements()) {
            if (el.arc() && el.s0 > sk.start + 4 && el.s0 < sk.finish - 8) {
                arrows.add(el.s0 - 2);
            }
        }
        for (double s : arrows) {
            if (inGates(s, 1)) {
                continue;
            }
            double[] t = sk.line.tangent(s);
            String arrow = TrackRaster.arrowToward(t[0], t[1]);
            int ice = level(s);
            for (int side = -1; side <= 1; side += 2) {
                int[] w = wallAt(s, side);
                if (w != null && Palette.TRACK_WALL.equals(vox.at(w[0], ice + 1, w[1]))) {
                    vox.put(w[0], ice + 1, w[1], arrow);
                }
            }
        }
    }

    /** {@code block} from y0 to y1 in both walls beside the lane {@code s} along (only over wall blocks). */
    private void mark(double s, String block, int from, int to) {
        for (int side = -1; side <= 1; side += 2) {
            int[] w = wallAt(s, side);
            if (w == null) {
                continue;
            }
            for (int y = from; y <= to; y++) {
                if (!vox.empty(w[0], y, w[1])) {
                    vox.put(w[0], y, w[1], block);
                }
            }
        }
    }

    /** The wall column beside the lane {@code s} along, right (+1) or left (-1) of it; {@code null} if none. */
    int[] wallAt(double s, int side) {
        double[] p = sk.line.at(s);
        double hd = sk.line.heading(s);
        double hw = baseHalf(s) + pieces.extra(s);
        for (double o = Math.floor(hw) + 1; o <= hw + 3; o += 0.5) {
            int x = (int) Math.floor(p[0] - Math.sin(hd) * o * side);
            int z = (int) Math.floor(p[1] + Math.cos(hd) * o * side);
            if (inside(x, z) && beside(x, z) && !obstacle[idx(x, z)]) {
                return new int[]{x, z};
            }
        }
        return null;
    }

    /** The viewing stand at the bottom (§4.1, §7.4): the 7 x 7 platform at finish ice + 5, its two-high glass rail, its sign. */
    void stand() {
        int floorY = standFloor();
        int r = RaceStand.SIZE / 2;
        for (int x = standX - r; x <= standX + r; x++) {
            for (int z = standZ - r; z <= standZ + r; z++) {
                vox.put(x, floorY, z, RaceStand.FLOOR);
                if (RaceStand.onRail(x, z, standX, standZ)) {
                    for (int y = 1; y <= RaceStand.RAIL; y++) {
                        vox.put(x, floorY + y, z, RaceStand.RAIL_BLOCK);
                    }
                }
            }
        }
        signs.add(new SignText(half.minX() + standX, floorY + 1, half.minZ() + standZ + 2, Palette.sign(8),
                RaceStand.SIGN));
    }

    /** The platform's block height: {@code RaceStand.floorY} of the finish mark's height. */
    int standFloor() {
        return RaceStand.floorY(finishIce + 1);
    }

    /**
     * A standing sign on the wall top beside the lane between {@code from} and {@code to} along, facing the
     * boats coming, at least {@value #SIGN_APART} from every other sign; whether one went up.
     */
    boolean sign(double from, double to, List<String> lines, List<Double> used) {
        for (double s = Math.max(from, 1); s <= to; s += 1) {
            boolean crowded = false;
            for (double u : used) {
                crowded |= Math.abs(u - s) < SIGN_APART;
            }
            if (crowded) {
                continue;
            }
            double[] t = sk.line.tangent(s);
            for (int side = 1; side >= -1; side -= 2) {
                int[] w = wallAt(s, side);
                if (w == null) {
                    continue;
                }
                int y = vox.top(w[0], w[1]) + 1;
                if (y <= y0 || y >= y0 + sy || !vox.empty(w[0], y, w[1]) || nearStand(w[0], w[1])) {
                    continue;
                }
                int rot = Math.floorMod((int) Math.round(TrackRaster.yaw(-t[0], -t[1]) / 22.5), 16);
                signs.add(new SignText(half.minX() + w[0], y, half.minZ() + w[1], Palette.sign(rot), lines));
                used.add(s);
                return true;
            }
        }
        return false;
    }

    /** Whether column (x, z) is within the stand's clear ring (V9). */
    boolean nearStand(int x, int z) {
        return Math.hypot(x - standX, z - standZ) <= MountainValidator.STAND_CLEAR + 1;
    }

    private static double sq(double v) {
        return v * v;
    }

    private static double squareDistance(int x, int z, double px, double pz) {
        double ex = Math.max(0, Math.max(x - px, px - (x + 1)));
        double ez = Math.max(0, Math.max(z - pz, pz - (z + 1)));
        return Math.hypot(ex, ez);
    }
}
