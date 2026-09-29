package com.dierks.homecraft.games.gen.boat;

import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.GenFailed;
import com.dierks.homecraft.games.gen.api.GenRandom;
import com.dierks.homecraft.games.gen.api.GenSeed;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Palette;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlanInput;
import com.dierks.homecraft.games.gen.api.PlannedTrial;
import com.dierks.homecraft.games.gen.api.Planner;
import com.dierks.homecraft.games.gen.api.SignText;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.Tier;
import com.dierks.homecraft.games.trial.TrialKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The planner for the Ice Boat loop (GEN-SPEC §4.4; the slot ships switched off): a walled ice
 * track round a closed loop, raced twice.
 *
 * <p><b>Why a polar curve.</b> The centreline is {@code r(θ) = 40 (1 + Σ a_k cos(kθ + φ_k))} for
 * k = 2 to 4, each {@code a_k} at most 0.12, round the middle of the half. With r always above 0
 * a polar curve is one closed loop that can't cross itself, so a layout never needs a
 * self-crossing check to be a loop. What it does need is gentle bends: every three points 2 blocks
 * apart along it have a circumradius of at least 12 (16 on easy), so the track's edges, offset
 * half its width either side, never fold. A loop that bends too hard is drawn again
 * ({@code fork("loop:" + n)}); after ten tries each one is drawn flatter (the amplitudes shrink),
 * and a plain circle always passes, so a course is never missing.
 *
 * <p><b>The track</b> is ice (packed, or blue on hard) at one height, every block within half the
 * width of the centreline; round it a wall one block high above the ice, which a boat can't
 * climb, and magenta arrows set into the wall every 24 blocks pointing the way. Checkpoints sit
 * on the centreline every ~24 blocks, each wide enough to span the track so none can be missed,
 * repeated for two laps; the finish is on the start line, which the course ignores until every
 * checkpoint is behind (Time Trials' own rule), and the start is 4 blocks before it.
 *
 * <p>Pure: no Bukkit, no clock, no {@code java.util.Random}; {@link BoatValidator} checks every plan
 * before it is returned.
 */
public final class BoatPlanner implements Planner {

    /** Its version; bump it whenever what it makes for a seed changes (golden hashes pin three seeds). */
    public static final int ALGO = 1;

    /** The three tiers: the track's width, the tightest bend, and the ice. */
    public enum Level {
        EASY("easy", 9, 16, false),
        MEDIUM("medium", 7, 12, false),
        HARD("hard", 5, 12, true);

        private final String id;
        private final int width;
        private final double minRadius;
        private final boolean fastIce;

        Level(String id, int width, double minRadius, boolean fastIce) {
            this.id = id;
            this.width = width;
            this.minRadius = minRadius;
            this.fastIce = fastIce;
        }

        public String id() {
            return id;
        }

        /** The track's width, blocks of ice across. */
        public int width() {
            return width;
        }

        /** The tightest bend: the circumradius of any three centreline points 2 blocks apart. */
        public double minRadius() {
            return minRadius;
        }

        /** The ice: blue (faster) on hard, packed otherwise. */
        public String ice() {
            return fastIce ? Palette.TRACK_FAST : Palette.TRACK;
        }

        /** A checkpoint's radius: half the width and a half, so it spans the track. */
        public double checkpointRadius() {
            return width / 2.0 + 0.5;
        }

        /** The tier called {@code word} (any case), or {@code null}. */
        public static Level of(String word) {
            if (word == null) {
                return null;
            }
            String w = word.trim().toLowerCase(Locale.ROOT);
            for (Level l : values()) {
                if (l.id.equals(w)) {
                    return l;
                }
            }
            return null;
        }
    }

    /** The loop's mean radius. */
    public static final double BASE_RADIUS = 40;
    /** The largest any of the three wobbles may be, as a share of the radius. */
    public static final double MAX_AMPLITUDE = 0.12;
    /** The centreline is sampled this often, blocks along it. */
    public static final double STEP = 0.5;
    /** Bends are measured over three points this far apart along the centreline. */
    public static final double BEND_SPAN = 2;
    /** Checkpoints and arrows come about this often along the track. */
    public static final double CHECKPOINT_SPACING = 24;
    public static final double ARROW_SPACING = 24;
    /** Laps a run goes round. */
    public static final int LAPS = 2;
    /** The start is this far before the line. */
    public static final double START_BACK = 4;
    /** The ice lies this far above the half's floor. */
    public static final int ICE_ABOVE_FLOOR = 4;
    /** Loops drawn before giving up (after ten, each is drawn flatter). */
    public static final int TRIES = 20;
    /** The work one plan can take at most (loops drawn). */
    public static final long WORK_BUDGET = TRIES;
    /** Reference speed round the track, blocks a second, and the fastest believable one. */
    public static final double REF_SPEED = 30;
    public static final double MIN_SPEED = 60;

    @Override
    public String id() {
        return Slots.BOAT;
    }

    @Override
    public int algo() {
        return ALGO;
    }

    @Override
    public Plan plan(PlanInput in) throws GenFailed {
        Level level = Level.of(in.slot().normalise(in.tierOrMix()));
        if (level == null) {
            throw new GenFailed("'" + in.tierOrMix() + "' isn't an Ice Boat tier (easy, medium or hard)");
        }
        Box half = in.half();
        double reach = BASE_RADIUS * (1 + 3 * MAX_AMPLITUDE) + level.width() / 2.0 + 2;
        if (half.sizeX() < 2 * reach || half.sizeZ() < 2 * reach || half.sizeY() < ICE_ABOVE_FLOOR + 4) {
            throw new GenFailed("the area " + half.describe() + " is too small for the Ice Boat");
        }
        double cx = half.minX() + half.sizeX() / 2.0;
        double cz = half.minZ() + half.sizeZ() / 2.0;
        GenRandom root = new GenRandom(in.seed());
        for (int t = 0; t < TRIES; t++) {
            in.checkCancelled();
            if (in.workBudget() > 0 && t + 1 > in.workBudget()) {
                throw new GenFailed("the Ice Boat plan went over its work budget (" + in.workBudget() + ")");
            }
            double flatten = t < 10 ? 1 : Math.pow(0.7, t - 9);
            Loop loop = loop(root.fork("loop:" + t), cx, cz, flatten);
            if (minRadius(loop) < level.minRadius()) {
                continue;
            }
            Plan plan = toPlan(in, level, loop, t + 1);
            if (BoatValidator.problems(plan, level.id()).isEmpty()) {
                return plan;
            }
        }
        throw new GenFailed("no " + level.id() + " Ice Boat loop found for seed " + GenSeed.shortHex(in.seed()));
    }

    /** The layout the tag names, made again from its seed (the tier in {@code in} first, then the others). */
    @Override
    public Plan rederive(PlanInput in, GenTag tag) throws GenFailed {
        if (tag == null) {
            return plan(in);
        }
        if (tag.algo() != ALGO) {
            throw new GenFailed("this layout was made by boat planner v" + tag.algo() + ", this is v" + ALGO);
        }
        List<String> tiers = new ArrayList<>();
        tiers.add(in.slot().normalise(in.tierOrMix()));
        for (Level l : Level.values()) {
            if (!tiers.contains(l.id())) {
                tiers.add(l.id());
            }
        }
        boolean anyHash = tag.planHash() == null || tag.planHash().isBlank();
        for (String tier : tiers) {
            PlanInput again = new PlanInput(in.slot(), in.half(), in.halfId(), tag.day(), tag.reroll(), tag.seed(),
                    tier, in.fallDepth(), in.workBudget(), in.cancelled());
            Plan p;
            try {
                p = plan(again);
            } catch (GenFailed e) {
                if (anyHash) {
                    throw e;
                }
                continue;
            }
            if (anyHash || tag.planHash().equals(p.hash())) {
                return p;
            }
        }
        throw new GenFailed("seed " + GenSeed.shortHex(tag.seed()) + " no longer makes layout " + tag.planHash());
    }

    // ---- the loop -------------------------------------------------------------------------------

    /**
     * A closed centreline: points every {@code step} blocks along it (the last joins the first),
     * round (cx, cz), and its length. The direction of travel is the order of the points.
     */
    record Loop(double cx, double cz, double[] xs, double[] zs, double step, double length) {

        int size() {
            return xs.length;
        }

        /** The point {@code s} blocks along the loop from its start (wrapping round). */
        double[] at(double s) {
            double u = ((s % length) + length) % length / step;
            int i = (int) Math.floor(u);
            double f = u - i;
            int j = (i + 1) % xs.length;
            i = i % xs.length;
            return new double[]{xs[i] + (xs[j] - xs[i]) * f, zs[i] + (zs[j] - zs[i]) * f};
        }

        /** The unit direction of travel {@code s} blocks along. */
        double[] tangent(double s) {
            double[] a = at(s - step);
            double[] b = at(s + step);
            double dx = b[0] - a[0];
            double dz = b[1] - a[1];
            double len = Math.sqrt(dx * dx + dz * dz);
            return new double[]{dx / len, dz / len};
        }
    }

    /**
     * A loop drawn from {@code r}: three wobbles on a circle of {@link #BASE_RADIUS}, their sizes
     * times {@code flatten}, sampled every {@link #STEP} blocks along its length.
     */
    static Loop loop(GenRandom r, double cx, double cz, double flatten) {
        double[] a = new double[5];
        double[] phi = new double[5];
        for (int k = 2; k <= 4; k++) {
            a[k] = r.nextDouble(0, MAX_AMPLITUDE) * flatten;
            phi[k] = r.nextDouble(0, 2 * Math.PI);
        }
        int n = 8192;
        double[] px = new double[n];
        double[] pz = new double[n];
        double[] cum = new double[n + 1];
        for (int i = 0; i < n; i++) {
            double th = 2 * Math.PI * i / n;
            double rad = BASE_RADIUS;
            for (int k = 2; k <= 4; k++) {
                rad += BASE_RADIUS * a[k] * StrictMath.cos(k * th + phi[k]);
            }
            px[i] = cx + rad * StrictMath.cos(th);
            pz[i] = cz + rad * StrictMath.sin(th);
            if (i > 0) {
                cum[i] = cum[i - 1] + Math.hypot(px[i] - px[i - 1], pz[i] - pz[i - 1]);
            }
        }
        double length = cum[n - 1] + Math.hypot(px[0] - px[n - 1], pz[0] - pz[n - 1]);
        cum[n] = length;
        int m = (int) Math.round(length / STEP);
        double step = length / m;
        double[] xs = new double[m];
        double[] zs = new double[m];
        int seg = 0;
        for (int j = 0; j < m; j++) {
            double s = j * step;
            while (seg < n - 1 && cum[seg + 1] < s) {
                seg++;
            }
            int next = (seg + 1) % n;
            double span = cum[seg + 1] - cum[seg];
            double f = span <= 0 ? 0 : (s - cum[seg]) / span;
            xs[j] = px[seg] + (px[next] - px[seg]) * f;
            zs[j] = pz[seg] + (pz[next] - pz[seg]) * f;
        }
        return new Loop(cx, cz, xs, zs, step, length);
    }

    /** The tightest bend of a loop: the least circumradius of any three points {@link #BEND_SPAN} apart. */
    static double minRadius(Loop loop) {
        int k = (int) Math.round(BEND_SPAN / loop.step());
        int n = loop.size();
        double min = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            min = Math.min(min, circumradius(loop.xs()[(i - k + n) % n], loop.zs()[(i - k + n) % n], loop.xs()[i],
                    loop.zs()[i], loop.xs()[(i + k) % n], loop.zs()[(i + k) % n]));
        }
        return min;
    }

    /** The radius of the circle through three points (infinite when they are in a line). */
    static double circumradius(double ax, double az, double bx, double bz, double cx, double cz) {
        double a = Math.hypot(bx - cx, bz - cz);
        double b = Math.hypot(ax - cx, az - cz);
        double c = Math.hypot(ax - bx, az - bz);
        double cross = Math.abs((bx - ax) * (cz - az) - (bz - az) * (cx - ax));
        return cross < 1e-12 ? Double.POSITIVE_INFINITY : a * b * c / (2 * cross);
    }

    // ---- from a loop to a plan --------------------------------------------------------------------

    /** What each column of the half holds: nothing, ice, or wall. */
    static final byte EMPTY = 0;
    static final byte ICE = 1;
    static final byte WALL = 2;

    /** The track's footprint: ice within half the width of the centreline, a wall round it. */
    static byte[][] footprint(Loop loop, Box half, double width) {
        int sx = half.sizeX();
        int sz = half.sizeZ();
        double[][] dist = new double[sx][sz];
        for (double[] row : dist) {
            java.util.Arrays.fill(row, Double.MAX_VALUE);
        }
        double reach = width / 2 + 2;
        int n = loop.size();
        for (int i = 0; i < n; i++) {
            double ax = loop.xs()[i];
            double az = loop.zs()[i];
            double bx = loop.xs()[(i + 1) % n];
            double bz = loop.zs()[(i + 1) % n];
            int x0 = Math.max(0, (int) Math.floor(Math.min(ax, bx) - reach) - half.minX());
            int x1 = Math.min(sx - 1, (int) Math.ceil(Math.max(ax, bx) + reach) - half.minX());
            int z0 = Math.max(0, (int) Math.floor(Math.min(az, bz) - reach) - half.minZ());
            int z1 = Math.min(sz - 1, (int) Math.ceil(Math.max(az, bz) + reach) - half.minZ());
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    double d = segment(ax, az, bx, bz, half.minX() + x + 0.5, half.minZ() + z + 0.5);
                    if (d < dist[x][z]) {
                        dist[x][z] = d;
                    }
                }
            }
        }
        byte[][] cells = new byte[sx][sz];
        for (int x = 0; x < sx; x++) {
            for (int z = 0; z < sz; z++) {
                if (dist[x][z] <= width / 2) {
                    cells[x][z] = ICE;
                }
            }
        }
        // the wall: every column next to the ice (sideways or corner to corner) that isn't ice
        for (int x = 0; x < sx; x++) {
            for (int z = 0; z < sz; z++) {
                if (cells[x][z] != EMPTY) {
                    continue;
                }
                boolean touches = false;
                for (int dx = -1; dx <= 1 && !touches; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        int ox = x + dx;
                        int oz = z + dz;
                        if (ox >= 0 && oz >= 0 && ox < sx && oz < sz && cells[ox][oz] == ICE) {
                            touches = true;
                            break;
                        }
                    }
                }
                if (touches) {
                    cells[x][z] = WALL;
                }
            }
        }
        return cells;
    }

    /** The distance from (px, pz) to the segment a–b. */
    static double segment(double ax, double az, double bx, double bz, double px, double pz) {
        double dx = bx - ax;
        double dz = bz - az;
        double len2 = dx * dx + dz * dz;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (pz - az) * dz) / len2));
        double qx = ax + t * dx - px;
        double qz = az + t * dz - pz;
        return Math.sqrt(qx * qx + qz * qz);
    }

    Plan toPlan(PlanInput in, Level level, Loop loop, long work) {
        Box half = in.half();
        int iceY = half.minY() + ICE_ABOVE_FLOOR;
        byte[][] cells = footprint(loop, half, level.width());
        List<String> palette = new ArrayList<>();
        List<BlockOp> ops = new ArrayList<>();
        short ice = index(palette, level.ice());
        short wall = index(palette, Palette.TRACK_WALL);
        // arrows in the wall, both sides, every 24 blocks, pointing the way
        java.util.Map<Long, String> arrows = new java.util.HashMap<>();
        for (double s = 0; s < loop.length() - 1; s += ARROW_SPACING) {
            double[] p = loop.at(s);
            double[] t = loop.tangent(s);
            for (int side = -1; side <= 1; side += 2) {
                double nx = -t[1] * side;
                double nz = t[0] * side;
                int x = (int) Math.floor(p[0] + nx * (level.width() / 2.0 + 0.8));
                int z = (int) Math.floor(p[1] + nz * (level.width() / 2.0 + 0.8));
                int cx = x - half.minX();
                int cz = z - half.minZ();
                if (cx >= 0 && cz >= 0 && cx < cells.length && cz < cells[0].length && cells[cx][cz] == WALL) {
                    arrows.put(key(x, z), arrowToward(t[0], t[1]));
                }
            }
        }
        for (int x = 0; x < cells.length; x++) {
            for (int z = 0; z < cells[0].length; z++) {
                int wx = half.minX() + x;
                int wz = half.minZ() + z;
                if (cells[x][z] == ICE) {
                    ops.add(new BlockOp(wx, iceY, wz, ice));
                } else if (cells[x][z] == WALL) {
                    ops.add(new BlockOp(wx, iceY, wz, wall));
                    String arrow = arrows.get(key(wx, wz));
                    ops.add(new BlockOp(wx, iceY + 1, wz, arrow == null ? wall : index(palette, arrow)));
                }
            }
        }

        // the course: checkpoints every ~24 blocks, two laps; the finish on the start line; the start 4 before it
        int m = Math.max(3, (int) Math.round(loop.length() / CHECKPOINT_SPACING));
        double spacing = loop.length() / m;
        double top = iceY + 1;
        List<Course.Mark> lap = new ArrayList<>();
        for (int k = 1; k < m; k++) {
            double[] p = loop.at(k * spacing);
            lap.add(new Course.Mark(p[0], top, p[1], level.checkpointRadius()));
        }
        List<Course.Mark> checkpoints = new ArrayList<>();
        for (int l = 0; l < LAPS; l++) {
            checkpoints.addAll(lap);
        }
        double[] line = loop.at(0);
        Course.Mark finish = new Course.Mark(line[0], top, line[1], level.checkpointRadius());
        double[] from = loop.at(loop.length() - START_BACK);
        double[] way = loop.tangent(loop.length() - START_BACK);
        Course.Spot start = new Course.Spot(from[0], top, from[1], (float) yaw(way[0], way[1]), 0f);
        double total = LAPS * loop.length();
        long refMs = Math.round(total / REF_SPEED * 1000);
        int minSeconds = (int) Math.floor(total / MIN_SPEED);
        Slots.Def slot = in.slot();
        Course course = new Course(slot.id(), TrialKind.BOAT, slot.name(), Tier.of(level.id()), "", start, checkpoints,
                finish, (double) (iceY - 3), minSeconds, true, false, 1);

        // the sign: on the inner wall by the start, facing the start spot
        List<SignText> signs = new ArrayList<>();
        double[] by = loop.at(loop.length() - START_BACK - 3);
        double[] t = loop.tangent(loop.length() - START_BACK - 3);
        for (int side = -1; side <= 1 && signs.isEmpty(); side += 2) {
            double nx = -t[1] * side;
            double nz = t[0] * side;
            int x = (int) Math.floor(by[0] + nx * (level.width() / 2.0 + 0.8));
            int z = (int) Math.floor(by[1] + nz * (level.width() / 2.0 + 0.8));
            int cx = x - half.minX();
            int cz = z - half.minZ();
            boolean inner = (x + 0.5 - loop.cx()) * (x + 0.5 - loop.cx()) + (z + 0.5 - loop.cz()) * (z + 0.5 - loop.cz())
                    < (by[0] - loop.cx()) * (by[0] - loop.cx()) + (by[1] - loop.cz()) * (by[1] - loop.cz());
            if (inner && cx >= 0 && cz >= 0 && cx < cells.length && cz < cells[0].length && cells[cx][cz] == WALL) {
                signs.add(new SignText(x, iceY + 2, z, Palette.sign(Math.floorMod((int) Math.round(
                        yaw(from[0] - (x + 0.5), from[1] - (z + 0.5)) / 22.5), 16)), GenCopy.boatStart(LAPS)));
            }
        }

        List<String> summary = new ArrayList<>();
        summary.add(slot.name() + " (" + level.id() + "): a " + Math.round(loop.length()) + "-block loop, " + LAPS
                + " laps, " + lap.size() + " checkpoints a lap, reference " + Math.round(refMs / 100.0) / 10.0 + "s");
        summary.add("track " + level.width() + " wide, tightest bend " + Math.round(minRadius(loop))
                + " blocks round, " + Palette.id(level.ice()));
        summary.add("seed " + GenSeed.shortHex(in.seed()) + ", " + work + " loop(s) drawn");
        return Plan.of(slot.id(), ALGO, in.seed(), half, palette, ops, signs,
                List.of(new Box(half.minX(), iceY + 1, half.minZ(), half.maxX(), Math.min(half.maxY(), iceY + 4),
                        half.maxZ())), new PlannedTrial(course, refMs), summary, work);
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    /** The palette index of {@code block}, adding it on first use. */
    static short index(List<String> palette, String block) {
        int i = palette.indexOf(block);
        if (i < 0) {
            palette.add(block);
            i = palette.size() - 1;
        }
        return (short) i;
    }

    /** Minecraft's yaw (0 = south, 90 = west) of a direction across the ground. */
    static double yaw(double dx, double dz) {
        double y = StrictMath.toDegrees(StrictMath.atan2(-dx, dz));
        return y < 0 ? y + 360 : y;
    }

    /**
     * The magenta arrow pointing the side nearest the direction (dx, dz). Glazed terracotta faces
     * the player who placed it and its arrow points away from them, so an arrow pointing east is
     * the block facing west.
     */
    static String arrowToward(double dx, double dz) {
        if (Math.abs(dx) >= Math.abs(dz)) {
            return Palette.arrow(dx > 0 ? "west" : "east");
        }
        return Palette.arrow(dz > 0 ? "north" : "south");
    }
}
