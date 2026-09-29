package com.dierks.homecraft.games.gen.boat;

import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Palette;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlanInput;
import com.dierks.homecraft.games.gen.api.PlannedTrial;
import com.dierks.homecraft.games.gen.api.SignText;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.TrialKind;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The independent check of an Ice Boat plan (GEN-SPEC §4.4), run before a single block is set.
 *
 * <p>It reads the track from the blocks alone: the ice at one height, a wall of two blocks over
 * every column round it, arrows set in the wall. From there:
 * <ul>
 *   <li><b>closed and simple</b>: every ray out from the middle of the ice crosses exactly one run
 *       of ice, walled at both ends, and no ice touches open air, so the track is one walled
 *       loop round the middle;</li>
 *   <li><b>width</b>: a lane of blocks at least half the width less half a block from every wall
 *       block (middle to middle) runs unbroken all the way round: the walls never come that close
 *       to the centreline;</li>
 *   <li><b>curvature</b>: the centreline, read back as the middle of each ray's ice, never bends
 *       tighter than the tier allows (with a margin for reading it off whole blocks);</li>
 *   <li><b>checkpoints</b>: two identical laps of them in loop order, each on the ice and wide
 *       enough to span it, at most {@value #MAX_CHECKPOINTS} in all; the finish on the start line
 *       and the start a few blocks before it, facing the way round.</li>
 * </ul>
 * Pure: no Bukkit.
 */
public final class BoatValidator {

    /** The most blocks an Ice Boat plan may place, and checkpoints a course may have. */
    public static final int MAX_OPS = 20_000;
    public static final int MAX_CHECKPOINTS = Course.MAX_CHECKPOINTS;
    /** The lane is read round the hole in this many slices. */
    static final int BINS = 180;
    /** Read off whole blocks, a bend may look this much tighter than it is. */
    static final double BEND_SLACK = 0.6;

    private BoatValidator() {
    }

    /** {@link #problems(Plan, String)} with the tier of the input. */
    public static List<String> problems(Plan plan, PlanInput in) {
        return problems(plan, in.slot().normalise(in.tierOrMix()));
    }

    /** What is wrong with {@code plan} as a {@code tier} Ice Boat course; empty when nothing is. */
    public static List<String> problems(Plan plan, String tier) {
        List<String> out = new ArrayList<>();
        BoatPlanner.Level level = BoatPlanner.Level.of(tier);
        if (level == null) {
            out.add("'" + tier + "' isn't an Ice Boat tier");
            return out;
        }
        if (!(plan.course() instanceof PlannedTrial trial) || trial.course().kind() != TrialKind.BOAT) {
            out.add("the plan isn't a boat course");
            return out;
        }
        Course course = trial.course();
        if (course.start() == null || course.finish() == null) {
            out.add("the course has no start or no finish");
            return out;
        }
        Box half = plan.half();
        for (String p : Palette.problems(plan.palette())) {
            out.add("'" + p + "' isn't a Fresh Courses block");
        }
        if (plan.ops().size() > MAX_OPS) {
            out.add(plan.ops().size() + " blocks is more than " + MAX_OPS);
        }
        if (!out.isEmpty()) {
            return out;
        }

        // the layers: ice at one height, walls two high round it, arrows in the walls' top
        int iceY = Integer.MIN_VALUE;
        String ice = Palette.id(level.ice());
        for (BlockOp op : plan.ops()) {
            if (Palette.id(plan.blockOf(op)).equals(ice)) {
                iceY = op.y();
                break;
            }
        }
        if (iceY == Integer.MIN_VALUE) {
            out.add("there is no " + ice + " track");
            return out;
        }
        int sx = half.sizeX();
        int sz = half.sizeZ();
        byte[][] low = new byte[sx][sz];
        byte[][] high = new byte[sx][sz];
        String wall = Palette.id(Palette.TRACK_WALL);
        String arrow = Palette.id(Palette.ARROW);
        for (BlockOp op : plan.ops()) {
            if (!half.contains(op.x(), op.y(), op.z())) {
                out.add("a block at " + op.x() + " " + op.y() + " " + op.z() + " is outside the half");
                return out;
            }
            String b = Palette.id(plan.blockOf(op));
            int x = op.x() - half.minX();
            int z = op.z() - half.minZ();
            byte[][] layer = op.y() == iceY ? low : op.y() == iceY + 1 ? high : null;
            byte kind = b.equals(ice) ? BoatPlanner.ICE : b.equals(wall) || b.equals(arrow) ? BoatPlanner.WALL : 0;
            if (layer == null || kind == 0 || (kind == BoatPlanner.ICE && layer == high)
                    || (b.equals(arrow) && layer == low)) {
                out.add("a stray " + b + " at " + op.x() + " " + op.y() + " " + op.z());
                return out;
            }
            if (layer[x][z] != 0) {
                out.add("two blocks at " + op.x() + " " + op.y() + " " + op.z());
                return out;
            }
            layer[x][z] = kind;
        }
        for (int x = 0; x < sx; x++) {
            for (int z = 0; z < sz; z++) {
                boolean wallBelow = low[x][z] == BoatPlanner.WALL;
                boolean wallAbove = high[x][z] == BoatPlanner.WALL;
                if (wallBelow != wallAbove) {
                    out.add("the wall at " + (half.minX() + x) + " " + (half.minZ() + z) + " isn't one block over the ice");
                    return out;
                }
                if (low[x][z] == BoatPlanner.ICE) {
                    int[][] steps = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                    for (int[] s : steps) {
                        int ox = x + s[0];
                        int oz = z + s[1];
                        if (ox < 0 || oz < 0 || ox >= sx || oz >= sz || low[ox][oz] == 0) {
                            out.add("the ice at " + (half.minX() + x) + " " + (half.minZ() + z) + " has no wall beside it");
                            return out;
                        }
                    }
                }
            }
        }

        // closed and simple: the ice is one piece, and the columns that are neither ice nor wall
        // fall into exactly two pieces, the hole in the middle and the world outside
        Pieces pieces = pieces(low);
        if (pieces.ice() != 1) {
            out.add("the ice is in " + pieces.ice() + " pieces, not one loop");
            return out;
        }
        if (pieces.open() != 2) {
            out.add("the track " + (pieces.open() < 2 ? "doesn't close round a middle" : "crosses itself or leaves an"
                    + " island") + " (" + pieces.open() + " open areas, not 2)");
            return out;
        }
        // the middle of the hole, which the lane goes round
        double cx = 0;
        double cz = 0;
        int n = 0;
        for (int x = 0; x < sx; x++) {
            for (int z = 0; z < sz; z++) {
                if (low[x][z] == 0 && pieces.id()[x][z] == pieces.hole()) {
                    cx += x + 0.5;
                    cz += z + 0.5;
                    n++;
                }
            }
        }
        cx /= n;
        cz /= n;
        // width: the blocks at least half the width less half a block from every wall block
        // (middle to middle) make one unbroken lane all the way round the hole
        double lane = level.width() / 2.0 - 0.5;
        boolean[][] clear = new boolean[sx][sz];
        int reach = level.width() + 2;
        int laneCells = 0;
        int sx0 = -1;
        int sz0 = -1;
        for (int x = 0; x < sx; x++) {
            for (int z = 0; z < sz; z++) {
                if (low[x][z] != BoatPlanner.ICE) {
                    continue;
                }
                double best = Double.MAX_VALUE;
                for (int dx = -reach; dx <= reach; dx++) {
                    for (int dz = -reach; dz <= reach; dz++) {
                        int ox = x + dx;
                        int oz = z + dz;
                        if (ox >= 0 && oz >= 0 && ox < sx && oz < sz && low[ox][oz] == BoatPlanner.WALL) {
                            best = Math.min(best, Math.sqrt(dx * dx + dz * dz));
                        }
                    }
                }
                if (best >= lane - 1e-9) {
                    clear[x][z] = true;
                    laneCells++;
                    sx0 = x;
                    sz0 = z;
                }
            }
        }
        // the lane, binned by its angle round the hole: every bin has some, and it is one piece
        double[] binX = new double[BINS];
        double[] binZ = new double[BINS];
        int[] binN = new int[BINS];
        for (int x = 0; x < sx; x++) {
            for (int z = 0; z < sz; z++) {
                if (clear[x][z]) {
                    double a = Math.atan2(z + 0.5 - cz, x + 0.5 - cx);
                    int bin = Math.floorMod((int) Math.floor(a / (2 * Math.PI) * BINS), BINS);
                    binX[bin] += x + 0.5;
                    binZ[bin] += z + 0.5;
                    binN[bin]++;
                }
            }
        }
        int empty = 0;
        for (int n2 : binN) {
            empty += n2 == 0 ? 1 : 0;
        }
        if (laneCells == 0 || empty > 0 || reached(clear, sx0, sz0) != laneCells) {
            out.add("the track narrows: no unbroken lane " + lane + " from the walls all the way round");
            return out;
        }

        // curvature: the lane's middle, bin by bin, never bends too tight
        double[] midX = new double[BINS];
        double[] midZ = new double[BINS];
        for (int b = 0; b < BINS; b++) {
            midX[b] = binX[b] / binN[b];
            midZ[b] = binZ[b] / binN[b];
        }
        double tightest = tightestBend(midX, midZ);
        if (tightest < BEND_SLACK * level.minRadius()) {
            out.add("the track bends " + Math.round(tightest) + " blocks round; " + level.id() + " bends no tighter than "
                    + Math.round(level.minRadius()));
        }

        // checkpoints: two identical laps in loop order, on the ice, spanning it
        List<Course.Mark> cps = course.checkpoints();
        if (cps.size() > MAX_CHECKPOINTS) {
            out.add(cps.size() + " checkpoints is more than " + MAX_CHECKPOINTS);
        }
        if (cps.size() % BoatPlanner.LAPS != 0 || cps.isEmpty()) {
            out.add(cps.size() + " checkpoints aren't " + BoatPlanner.LAPS + " equal laps");
            return out;
        }
        int perLap = cps.size() / BoatPlanner.LAPS;
        for (int k = 0; k < cps.size(); k++) {
            if (!cps.get(k).equals(cps.get(k % perLap))) {
                out.add("lap " + (k / perLap + 1) + " doesn't repeat lap 1's checkpoints");
                break;
            }
        }
        List<Course.Mark> marks = new ArrayList<>(cps.subList(0, perLap));
        marks.add(course.finish());
        double wideEnough = level.checkpointRadius();
        double previous = Double.NaN;
        double turned = 0;
        double sense = 0;
        for (Course.Mark m : marks) {
            int x = (int) Math.floor(m.x()) - half.minX();
            int z = (int) Math.floor(m.z()) - half.minZ();
            if (x < 0 || z < 0 || x >= sx || z >= sz || low[x][z] != BoatPlanner.ICE || m.y() != iceY + 1) {
                out.add("the checkpoint at " + fmt(m.x()) + " " + fmt(m.z()) + " isn't on the ice");
                continue;
            }
            if (m.radius() + 1e-9 < level.width() / 2.0 + 0.5) {
                out.add("the checkpoint at " + fmt(m.x()) + " " + fmt(m.z()) + " doesn't span the track");
            }
            if (Math.abs(m.radius() - wideEnough) > 1e-9 || nearestWall(low, x, z, m.x() - half.minX(),
                    m.z() - half.minZ()) > m.radius() + 0.5) {
                out.add("the checkpoint at " + fmt(m.x()) + " " + fmt(m.z()) + " doesn't reach both walls");
            }
            double angle = Math.atan2(m.z() - half.minZ() - cz, m.x() - half.minX() - cx);
            if (!Double.isNaN(previous)) {
                double step = Math.atan2(Math.sin(angle - previous), Math.cos(angle - previous));
                if (sense == 0) {
                    sense = Math.signum(step);
                }
                if (Math.signum(step) != sense || Math.abs(step) < 1e-6) {
                    out.add("the checkpoints aren't in loop order at " + fmt(m.x()) + " " + fmt(m.z()));
                }
                turned += step;
            }
            previous = angle;
        }
        double closing = Math.atan2(Math.sin(Math.atan2(marks.get(0).z() - half.minZ() - cz, marks.get(0).x()
                - half.minX() - cx) - previous), Math.cos(Math.atan2(marks.get(0).z() - half.minZ() - cz,
                marks.get(0).x() - half.minX() - cx) - previous));
        if (Math.abs(Math.abs(turned + closing) - 2 * Math.PI) > 0.01) {
            out.add("the checkpoints don't go round the loop once a lap");
        }

        // the start: on the ice, a few blocks before the line, facing the way round
        Course.Spot s = course.start();
        int stx = (int) Math.floor(s.x()) - half.minX();
        int stz = (int) Math.floor(s.z()) - half.minZ();
        Course.Mark f = course.finish();
        double toLine = Math.hypot(f.x() - s.x(), f.z() - s.z());
        if (stx < 0 || stz < 0 || stx >= sx || stz >= sz || low[stx][stz] != BoatPlanner.ICE || toLine < 2
                || toLine > 7) {
            out.add("the start isn't on the ice a few blocks before the line");
        } else {
            double want = BoatPlanner.yaw(f.x() - s.x(), f.z() - s.z());
            double off = Math.abs(((s.yaw() - want) % 360 + 540) % 360 - 180);
            if (off > 45) {
                out.add("the start faces " + Math.round(off) + " degrees away from the line");
            }
        }
        if (course.fallY() == null || course.fallY() > iceY - 1) {
            out.add("the fall height " + course.fallY() + " isn't under the ice");
        }
        if (trial.refMs() <= 0 || course.minSeconds() == null || course.minSeconds() * 1000L > trial.refMs()) {
            out.add("the times don't fit (reference " + trial.refMs() + "ms, shortest " + course.minSeconds() + "s)");
        }
        for (SignText sign : plan.signs()) {
            int x = sign.x() - half.minX();
            int z = sign.z() - half.minZ();
            if (!half.contains(sign.x(), sign.y(), sign.z()) || sign.y() != iceY + 2 || high[x][z] != BoatPlanner.WALL) {
                out.add("the sign at " + sign.x() + " " + sign.y() + " " + sign.z() + " isn't on the wall");
            }
        }
        return out;
    }

    /**
     * The 4-way pieces of the ice layer: how many the ice makes, how many the open columns
     * (neither ice nor wall) make, counting every one that touches the half's side as the one
     * world outside, which piece each column is in, and which open piece is the hole.
     */
    private record Pieces(int ice, int open, int[][] id, int hole) {
    }

    private static Pieces pieces(byte[][] low) {
        int sx = low.length;
        int sz = low[0].length;
        int[][] id = new int[sx][sz];
        int iceParts = 0;
        int openParts = 0;
        int next = 1;
        int outside = -1;
        int hole = -1;
        for (int x = 0; x < sx; x++) {
            for (int z = 0; z < sz; z++) {
                if (id[x][z] != 0 || low[x][z] == BoatPlanner.WALL) {
                    continue;
                }
                boolean isIce = low[x][z] == BoatPlanner.ICE;
                int me = next++;
                boolean edge = false;
                ArrayDeque<int[]> queue = new ArrayDeque<>();
                queue.add(new int[]{x, z});
                id[x][z] = me;
                while (!queue.isEmpty()) {
                    int[] c = queue.poll();
                    if (c[0] == 0 || c[1] == 0 || c[0] == sx - 1 || c[1] == sz - 1) {
                        edge = true;
                    }
                    int[][] steps = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                    for (int[] st : steps) {
                        int ox = c[0] + st[0];
                        int oz = c[1] + st[1];
                        if (ox >= 0 && oz >= 0 && ox < sx && oz < sz && id[ox][oz] == 0
                                && (low[ox][oz] == BoatPlanner.ICE) == isIce && low[ox][oz] != BoatPlanner.WALL) {
                            id[ox][oz] = me;
                            queue.add(new int[]{ox, oz});
                        }
                    }
                }
                if (isIce) {
                    iceParts++;
                } else if (edge) {
                    if (outside < 0) {
                        outside = me;
                        openParts++;
                    } else {
                        // another piece touching the edge is the same outside world
                        for (int ax = 0; ax < sx; ax++) {
                            for (int az = 0; az < sz; az++) {
                                if (id[ax][az] == me) {
                                    id[ax][az] = outside;
                                }
                            }
                        }
                    }
                } else {
                    openParts++;
                    hole = me;
                }
            }
        }
        return new Pieces(iceParts, openParts, id, hole);
    }

    /** How many cells of {@code clear} are joined (8-way) to (x, z). */
    private static int reached(boolean[][] clear, int x0, int z0) {
        int sx = clear.length;
        int sz = clear[0].length;
        boolean[][] seen = new boolean[sx][sz];
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[]{x0, z0});
        seen[x0][z0] = true;
        int count = 0;
        while (!queue.isEmpty()) {
            int[] c = queue.poll();
            count++;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int x = c[0] + dx;
                    int z = c[1] + dz;
                    if (x >= 0 && z >= 0 && x < sx && z < sz && clear[x][z] && !seen[x][z]) {
                        seen[x][z] = true;
                        queue.add(new int[]{x, z});
                    }
                }
            }
        }
        return count;
    }

    /** From (px, pz) in the half's cells, the distance to the nearest wall block. */
    private static double nearestWall(byte[][] low, int x0, int z0, double px, double pz) {
        double best = Double.MAX_VALUE;
        for (int dx = -12; dx <= 12; dx++) {
            for (int dz = -12; dz <= 12; dz++) {
                int x = x0 + dx;
                int z = z0 + dz;
                if (x >= 0 && z >= 0 && x < low.length && z < low[0].length && low[x][z] == BoatPlanner.WALL) {
                    double ex = Math.max(0, Math.max(x - px, px - (x + 1)));
                    double ez = Math.max(0, Math.max(z - pz, pz - (z + 1)));
                    best = Math.min(best, Math.sqrt(ex * ex + ez * ez));
                }
            }
        }
        return best;
    }

    /**
     * The tightest bend of a closed centreline given as points round it: resampled every block,
     * smoothed over a few blocks, then the least circumradius of three points 6 blocks apart.
     */
    static double tightestBend(double[] xs, double[] zs) {
        int n = xs.length;
        double length = 0;
        for (int i = 0; i < n; i++) {
            length += Math.hypot(xs[(i + 1) % n] - xs[i], zs[(i + 1) % n] - zs[i]);
        }
        int m = Math.max(12, (int) Math.round(length));
        double step = length / m;
        double[] rx = new double[m];
        double[] rz = new double[m];
        int seg = 0;
        double at = 0;
        for (int j = 0; j < m; j++) {
            double s = j * step;
            double segLen = Math.hypot(xs[(seg + 1) % n] - xs[seg], zs[(seg + 1) % n] - zs[seg]);
            while (at + segLen < s && seg < n - 1) {
                at += segLen;
                seg++;
                segLen = Math.hypot(xs[(seg + 1) % n] - xs[seg], zs[(seg + 1) % n] - zs[seg]);
            }
            double f = segLen <= 0 ? 0 : (s - at) / segLen;
            rx[j] = xs[seg] + (xs[(seg + 1) % n] - xs[seg]) * f;
            rz[j] = zs[seg] + (zs[(seg + 1) % n] - zs[seg]) * f;
        }
        double[] sx = new double[m];
        double[] sz = new double[m];
        int w = 3;
        for (int j = 0; j < m; j++) {
            for (int k = -w; k <= w; k++) {
                sx[j] += rx[Math.floorMod(j + k, m)];
                sz[j] += rz[Math.floorMod(j + k, m)];
            }
            sx[j] /= 2 * w + 1;
            sz[j] /= 2 * w + 1;
        }
        int k = 6;
        double min = Double.MAX_VALUE;
        for (int j = 0; j < m; j++) {
            min = Math.min(min, BoatPlanner.circumradius(sx[Math.floorMod(j - k, m)], sz[Math.floorMod(j - k, m)],
                    sx[j], sz[j], sx[(j + k) % m], sz[(j + k) % m]));
        }
        return min;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }
}
