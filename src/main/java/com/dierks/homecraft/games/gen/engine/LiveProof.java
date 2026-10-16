package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Putt;
import com.dierks.homecraft.games.gen.dropper.DropMarks;
import com.dierks.homecraft.games.gen.golf.PlotGrid;
import com.dierks.homecraft.games.golf.BallPhysics;
import com.dierks.homecraft.games.golf.GolfCourse;
import com.dierks.homecraft.games.golf.GolfShot;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.TrialKind;

import java.util.ArrayList;
import java.util.List;

/**
 * The last word on a built course, on the real blocks (GEN-SPEC §3.3 step 6, §3.5, §4.3).
 *
 * <p><b>Golf: the witness replay.</b> The planner proved every hole on a model of the blocks and
 * kept the expert's line. After the blocks are built (and at every boot), that same line is played
 * with {@link GolfShot} on the real blocks — {@code LiveBlocks}, their true collision shapes — and
 * must hole out in exactly as many strokes as it has putts. A miss fails the build and the old
 * course stays up: it means the model and the real blocks disagree (a Paper update changed a
 * block's shape), and nobody should play a hole whose par was proven on a different world.
 *
 * <p><b>The structural check.</b> A layout made by an older planner version can't be derived again
 * after an update. Instead of the full proof it gets a quick one: a solid block under every place
 * a player or a ball must stand (and every boat checkpoint: they sit on the ice), and on golf every
 * pond still sealed ({@link #pools}) — in play or to look at, anywhere in a hole's plot. It opens
 * with a WARN, and the new version builds from the next day.
 */
public final class LiveProof {

    private LiveProof() {
    }

    /**
     * What is wrong when each hole's witness line is played on {@code blocks}: every line must hole
     * out with exactly its own number of putts and no penalty. Empty = every hole is proven.
     */
    public static List<String> replay(BallPhysics.Blocks blocks, List<GolfCourse.Hole> holes,
                                      List<List<Putt>> witness) {
        List<String> out = new ArrayList<>();
        if (witness == null || witness.size() != holes.size()) {
            out.add("the course has " + holes.size() + " holes but " + (witness == null ? 0 : witness.size())
                    + " witness lines");
            return out;
        }
        for (int i = 0; i < holes.size(); i++) {
            List<Putt> line = witness.get(i);
            if (line.isEmpty()) {
                out.add("hole " + (i + 1) + " has no witness line");
                continue;
            }
            GolfShot.Replay r;
            try {
                r = GolfShot.replay(blocks, holes.get(i), line);
            } catch (RuntimeException e) {
                out.add("hole " + (i + 1) + " can't be replayed (" + e + ")");
                continue;
            }
            if (!r.holed() || r.putts() != line.size() || r.strokes() != line.size()) {
                out.add("hole " + (i + 1) + "'s witness line " + (r.holed() ? "took " + r.strokes() + " strokes"
                        : "didn't hole out") + " on the real blocks, not " + line.size());
            }
        }
        return out;
    }

    /** Whether block (x, y, z) is solid, for {@link #structure}. */
    @FunctionalInterface
    public interface Solid {
        boolean at(int x, int y, int z);
    }

    /**
     * The quick check for a trial built by an older planner: a solid block under the start, and
     * under every checkpoint and the finish of a parkour or an ice boat course (a boat's marks sit
     * on the ice, on every algo: Course Variety §2.12). Rings float, so only the tower is checked.
     * Empty = fine.
     */
    public static List<String> structure(Course c, Solid solid) {
        return structure(c, solid, null);
    }

    /**
     * {@link #structure(Course, Solid)} with a way to see water (EVENTS-DROPPER-SPEC §B.1.9, the C1
     * hook): a dropper is proven by a solid block under every ledge and still water at every pool's
     * surface centre ({@link DropMarks#probes}). Without {@code water} (a caller that can't see it) a
     * dropper gets the start check only, as every other kind. For every other kind {@code water} is
     * ignored.
     *
     * @param water whether block (x, y, z) is water, or {@code null}
     */
    public static List<String> structure(Course c, Solid solid, Solid water) {
        List<String> out = new ArrayList<>();
        if (c == null || c.start() == null) {
            out.add("it has no start");
            return out;
        }
        if (!under(solid, c.start().x(), c.start().y(), c.start().z())) {
            out.add("nothing solid under the start");
        }
        if (c.kind() == TrialKind.PARKOUR || c.kind() == TrialKind.BOAT) {
            int i = 0;
            for (Course.Mark m : c.targets()) {
                i++;
                if (!under(solid, m.x(), m.y(), m.z())) {
                    out.add("nothing solid under " + (i > c.checkpoints().size() ? "the finish" : "checkpoint " + i));
                }
            }
        }
        if (c.kind() == TrialKind.DROPPER && water != null) {
            int level = 0;
            for (DropMarks.Probe p : DropMarks.probes(c)) {
                if (p.water()) {
                    level++;
                    if (!water.at(p.x(), p.y(), p.z())) {
                        out.add("level " + level + "'s pool has no water at its centre");
                    }
                } else if (!solid.at(p.x(), p.y(), p.z())) {
                    out.add("nothing solid under level " + (level + 1) + "'s ledge");
                }
            }
        }
        return out;
    }

    /** The quick check for golf: a solid block under every tee, and a solid cup block. Empty = fine. */
    public static List<String> structure(GolfCourse g, Solid solid) {
        return structure(g, null, solid, null, null);
    }

    /**
     * {@link #structure(GolfCourse, Solid)} with a way to see water and what seals it (Course
     * Variety §1.3): each hole's whole plot is scanned too ({@link #scanBox}: its bounds and one more,
     * and the planner's plot it stands in, where an Easy hole's decorative pond lies outside the
     * bounds), and any water in it must be sealed ({@link #pools}). Without {@code water} (or
     * {@code seals}) only the tees and cups are checked.
     *
     * @param half  the half the course stands in, whose plots its holes take in order (on the grid
     *              its own tag's slot and version give, {@link PlotGrid#of(GolfCourse)}: Golf v4's
     *              40 x 64, or Adventure Golf's 20 x 40), or {@code null} to scan only round each
     *              hole's bounds
     * @param seals whether block (x, y, z) seals a pond: a full block ({@code Pools.seals} on the real
     *              block), not a slab, a sign, leaves or air
     * @param water whether block (x, y, z) is water, or {@code null}
     */
    public static List<String> structure(GolfCourse g, Box half, Solid solid, Solid seals, Solid water) {
        List<String> out = new ArrayList<>();
        PlotGrid geometry = PlotGrid.of(g);
        int i = 0;
        for (GolfCourse.Hole h : g.holes()) {
            i++;
            if (h.tee() == null || !under(solid, h.tee().x(), h.tee().y(), h.tee().z())) {
                out.add("nothing solid under hole " + i + "'s tee");
            }
            if (h.cup() == null || !solid.at(h.cup().x(), h.cup().y(), h.cup().z())) {
                out.add("hole " + i + "'s cup block is missing");
            }
            Box plot = scanBox(h, i - 1, half, geometry);
            if (water != null && seals != null && plot != null) {
                for (String p : pools(plot, seals, water)) {
                    out.add("hole " + i + ": " + p);
                }
            }
        }
        return out;
    }

    /** The most blocks {@link #pools} scans in one box: a golf hole's whole plot is about twelve thousand. */
    public static final long MAX_SCAN = 1L << 18;

    /**
     * The sealed-pool test on the real blocks of {@code box} (the live twin of {@code Pools}, §1.3):
     * every water block in it has water or a block that {@code seals} on all four sides and below, so
     * none can flow — the same seal test as the plan's: a full block, never a slab, a sign or leaves
     * (they can hold water). Its neighbours outside the box are looked at too. Empty = fine (or no
     * water). A box bigger than {@link #MAX_SCAN} blocks isn't scanned, and says so.
     */
    public static List<String> pools(Box box, Solid seals, Solid water) {
        List<String> out = new ArrayList<>();
        if (box == null || seals == null || water == null) {
            return out;
        }
        if (box.volume() > MAX_SCAN) {
            out.add("the area " + box.describe() + " is too big to scan for ponds");
            return out;
        }
        int open = 0;
        String first = null;
        int[][] around = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, -1, 0}};
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int y = box.minY(); y <= box.maxY(); y++) {
                    if (!water.at(x, y, z)) {
                        continue;
                    }
                    for (int[] d : around) {
                        int nx = x + d[0];
                        int ny = y + d[1];
                        int nz = z + d[2];
                        if (!water.at(nx, ny, nz) && !seals.at(nx, ny, nz)) {
                            open++;
                            if (first == null) {
                                first = x + " " + y + " " + z;
                            }
                            break;
                        }
                    }
                }
            }
        }
        if (open > 0) {
            out.add(open + " water block" + (open == 1 ? " has" : "s have") + " air, a slab, a sign or leaves beside"
                    + " or under it (first at " + first + "): a pond isn't sealed");
        }
        return out;
    }

    /**
     * A hole's plot box: its bounds (walls included) one block wider on every side and one lower, and
     * two higher (as the golf planner's own {@code plotBox}); {@code null} for a hole without bounds.
     */
    static Box plotBox(GolfCourse.Hole h) {
        if (h == null || h.corner1() == null || h.corner2() == null) {
            return null;
        }
        Box b = Box.of(h.corner1().x(), h.corner1().y(), h.corner1().z(), h.corner2().x(), h.corner2().y(),
                h.corner2().z());
        return new Box(b.minX() - 1, b.minY() - 1, b.minZ() - 1, b.maxX() + 1, b.maxY() + 2, b.maxZ() + 1);
    }

    /**
     * {@link #scanBox(GolfCourse.Hole, int, Box, PlotGrid)} on Adventure Golf's 20 x 40 plots
     * ({@link PlotGrid#V3}).
     */
    static Box scanBox(GolfCourse.Hole h, int i, Box half) {
        return scanBox(h, i, half, PlotGrid.V3);
    }

    /**
     * What {@link #structure(GolfCourse, Box, Solid, Solid, Solid)} scans for hole {@code i} (from 0)
     * of a course in {@code half}: its {@link #plotBox}, spread over the whole plot the golf planner
     * gave it on {@code grid} (the course's own: {@link PlotGrid#of(GolfCourse)}; Adventure Golf's
     * 20 x 40, Golf v4's 40 x 64), clipped to the half, at the plot box's heights — so an Easy hole's
     * decorative pond, two or more columns outside its bounds, is scanned too (Course Variety §3.8
     * rule 13). Just the plot box without a half; {@code null} for a hole without bounds.
     */
    static Box scanBox(GolfCourse.Hole h, int i, Box half, PlotGrid grid) {
        Box b = plotBox(h);
        if (b == null || half == null) {
            return b;
        }
        PlotGrid g = grid == null ? PlotGrid.V3 : grid;
        int[] p = g.plot(half, i);
        int minX = Math.max(half.minX(), p[0]);
        int minZ = Math.max(half.minZ(), p[1]);
        int maxX = Math.min(half.maxX(), p[0] + g.plotX() - 1);
        int maxZ = Math.min(half.maxZ(), p[1] + g.plotZ() - 1);
        if (minX > maxX || minZ > maxZ) {
            return b;
        }
        return new Box(Math.min(b.minX(), minX), b.minY(), Math.min(b.minZ(), minZ), Math.max(b.maxX(), maxX),
                b.maxY(), Math.max(b.maxZ(), maxZ));
    }

    /** A solid block right under feet at (x, y, z). */
    private static boolean under(Solid solid, double x, double y, double z) {
        return solid.at((int) Math.floor(x), (int) Math.floor(y - 0.01), (int) Math.floor(z));
    }
}
