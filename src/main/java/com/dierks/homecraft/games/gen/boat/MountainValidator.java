package com.dierks.homecraft.games.gen.boat;

import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Palette;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlannedTrial;
import com.dierks.homecraft.games.gen.api.SignText;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.Laps;
import com.dierks.homecraft.games.trial.Point;
import com.dierks.homecraft.games.trial.RaceGrid;
import com.dierks.homecraft.games.trial.RaceStand;
import com.dierks.homecraft.games.trial.TrialKind;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * The independent check of a Mountain Run v2 plan, Ice Boat algo {@value #FIRST_ALGO} on
 * (MOUNTAIN-V2-SPEC §9): the proof of {@link DownhillValidator} (V1-V13) scaled to a 480 × 176 × 640
 * mountain, read from the plan's blocks alone, before a single block is set. {@link BoatValidator}
 * sends it every plan of algo {@value #FIRST_ALGO} or later; the planner runs it on every Stage-B
 * attempt, and {@code PlanCheck.generator} again before a build and at a heal.
 *
 * <h2>CONTRACT (for the planner, package MA, and the engine and runtime, packages E1 and MD)</h2>
 * <ul>
 *   <li>{@code List<String> MountainValidator.problems(Plan plan, String tier)}: what is wrong with
 *       {@code plan} as a {@code tier} ({@code easy}, {@code medium}, {@code hard}) Mountain Run v2;
 *       empty when it is proven. Never throws; a plan it can't read is refused. The style comes from
 *       the plan's own seed ({@link #slalom(long)}).</li>
 *   <li>{@code List<String> MountainValidator.problems(Plan plan, Rules rules)}: the same with the
 *       style and tier given ({@link Rules#of(String, boolean)}).</li>
 *   <li>{@code boolean MountainValidator.slalom(long seed)}: the style it judges a seed's plan by:
 *       the low bit of the SplitMix64 finaliser of {@code seed ^ 0x5EED57E1E00DL}, 1 = SLALOM,
 *       0 = ROAD (MOUNTAIN-V2-SPEC §5.1). {@code BoatStyle.of(seed) == SLALOM} must agree; at the
 *       merge this delegates to it.</li>
 *   <li>{@code Rules MountainValidator.Rules.of(String tier, boolean slalom)}: the numbers proven per
 *       style and tier (P, B, the 2-block drop cap, the descent window, blue ice), so the planner
 *       aims at the same ones.</li>
 *   <li>{@code int MountainValidator.minSeconds(Course c)}: the shortest time the course must store
 *       (V12; the same leg bound as v3).</li>
 *   <li>{@code Point MountainValidator.standSpot(Box half, double finishY)}: where a player stands on
 *       the viewing stand, {@code RaceStand.spotV4}'s contract: centre column
 *       ({@code minX + sizeX/2}, {@code minZ + sizeZ - }{@value #STAND_BACK}), at
 *       {@code finishY + RaceStand.ABOVE}; the platform's block height is
 *       {@code RaceStand.floorY(finishY)}.</li>
 *   <li>Its caps: {@value #MAX_OPS} blocks, {@value #MAX_BOXES} keep-clear boxes,
 *       {@value #MAX_CHECKPOINTS} checkpoints ({@code Course.MAX_CHECKPOINTS} becomes 128 with v4,
 *       package MD; this class keeps its own copy so the proof never moves with it), the half exactly
 *       {@value #SIZE_X} × {@value #SIZE_Y} × {@value #SIZE_Z} and 16-aligned.</li>
 * </ul>
 *
 * <h2>The rules (MOUNTAIN-V2-SPEC §9.1; the deltas from {@link DownhillValidator})</h2>
 * <ul>
 *   <li><b>V1 palette.</b> v3's blocks, plus {@code snow_block} and {@code stone} (wall and
 *       scenery), {@code spruce_log} and {@code spruce_leaves} (scenery, a log and leaves like any
 *       other wood's) and {@code red_concrete} and {@code blue_concrete} (the slalom's gate fences:
 *       walls). No slime or water; every block has a class (drive, wall, gate, riser, roof, scenery,
 *       stand, sign), or it is a stray; leaves at vanilla's distance. Easy is packed ice only.</li>
 *   <li><b>V2-V4</b> as v3, every tier dropping at most {@link BoatEnvelope#MAX_DROP}; W4 adds that
 *       nothing stands on the half's outermost ring of columns.</li>
 *   <li><b>V5</b> P and B per style and tier ({@link Rules}); the side-pocket rule is waived within
 *       {@value #GATE_FIELD} columns of a gate fence on a SLALOM plan (the corners behind a fence are
 *       drivable pockets by design), and on a slalom it measures from a road-wide middle
 *       ({@link Rules#pocketWidth}).</li>
 *   <li><b>V6</b> as v3, at most {@value #MAX_CHECKPOINTS} checkpoints.</li>
 *   <li><b>V7</b> as v3; stone and snow may stand over the track 5 up or more (a tunnel's roof and
 *       the rock over it).</li>
 *   <li><b>V8</b> at most 2 a drop; the 2-block drops and the whole fall capped per style and tier,
 *       and the fall at least the tier's minimum (a real downhill).</li>
 *   <li><b>V9 the stand at the bottom.</b> The 7 × 7 platform at {@link #standSpot}: whole, railed,
 *       signed, standable; nothing but the stand at or over its floor within {@value #STAND_CLEAR}
 *       of its centre column; at least {@code RaceStand.LANE_CLEARANCE} from any track; and a clear
 *       sight line: every block the segment from the platform's centre at floor +
 *       {@value #EYE} (a standing player's eyes) to the finish mark's centre raised {@value #RIDER}
 *       (a rider's head, inside the mark's sphere) passes through is air or glass.</li>
 *   <li><b>V10</b> as v3, the stand at {@link #standSpot}.</li>
 *   <li><b>V11</b> as v3, the stand at the bottom; and the Final Drop is the drop of the finish's own
 *       leg, at most {@value #FINAL_FAR} before the finish (its least, Z(d) + 3 ≥ 43, is v3's
 *       rule).</li>
 *   <li><b>V12</b> as v3. <b>V13</b> at most {@value #MAX_OPS} blocks and {@value #MAX_BOXES} boxes,
 *       everything inside the half, the half 16-aligned and exactly 480 × 176 × 640.</li>
 * </ul>
 *
 * <p><b>Column-sparse.</b> The blocks are kept per column: an offset table over the half's columns
 * into the column's (y, block) entries sorted by y, so every check walks a column's blocks and
 * never its air (no loops over 176 levels). The checks themselves are v3's, ported line for line,
 * and a test runs this engine with v3's rules ({@code v3}) over the frozen algo-3 fixtures and the
 * hand-made v3 runs' mutations: it says exactly what {@link DownhillValidator} says.
 *
 * <p>Pure: no Bukkit. About a second a plan.
 */
public final class MountainValidator {

    /** The first boat planner version this judges: Mountain Run v2. */
    public static final int FIRST_ALGO = 4;
    /** The most blocks a Mountain Run v2 plan may place (V13). */
    public static final int MAX_OPS = 400_000;
    /** The most keep-clear boxes a plan may carry (V13). */
    public static final int MAX_BOXES = 64;
    /**
     * The most checkpoints (V6, §3.5): {@code Course.MAX_CHECKPOINTS} becomes this with v4 (package
     * MD); the proof keeps its own number, as the frozen validators keep their 64.
     */
    public static final int MAX_CHECKPOINTS = 128;
    /** The half, exactly (V13, §4.1). */
    public static final int SIZE_X = 480;
    public static final int SIZE_Y = 176;
    public static final int SIZE_Z = 640;
    /** The half's corner sits on a chunk corner. */
    public static final int ALIGN = 16;
    /** The stand's centre row is this far in from the half's south (high-z) edge ({@code RaceStand.STAND_BACK}). */
    public static final int STAND_BACK = 40;
    /** Nothing but the stand at or over its floor within this of its centre column (V9). */
    public static final double STAND_CLEAR = 16;
    /** The sight line starts this far over the platform's block: a standing player's eyes (V9). */
    public static final double EYE = 2.6;
    /** ...and ends this far over the finish mark's centre: a rider's head, inside the sphere (V9). */
    public static final double RIDER = 1.5;
    /** The Final Drop is at most this far before the finish (V11, §4.2). */
    public static final double FINAL_FAR = 70;
    /** On a slalom, the pocket rule is waived this many columns round a gate fence (V5, §5.4). */
    public static final int GATE_FIELD = 18;

    static final byte STONE = 17;
    static final byte SNOW = 18;
    static final byte RED_GATE = 19;
    static final byte BLUE_GATE = 20;
    private static final int CODE_COUNT = 21;

    /** v3's blocks ({@link DownhillValidator#CODES}) and v2's additions (§8): snow, stone, spruce, the gates. */
    static final Map<String, Byte> CODES_V4;

    static {
        Map<String, Byte> m = new LinkedHashMap<>(new java.util.TreeMap<>(DownhillValidator.CODES));
        m.put("minecraft:stone", STONE);
        m.put("minecraft:snow_block", SNOW);
        m.put("minecraft:spruce_log", DownhillValidator.LOG);
        m.put("minecraft:spruce_leaves", DownhillValidator.LEAVES);
        m.put("minecraft:red_concrete", RED_GATE);
        m.put("minecraft:blue_concrete", BLUE_GATE);
        CODES_V4 = Collections.unmodifiableMap(m);
    }

    private static final boolean[] DRIVE = new boolean[CODE_COUNT];
    private static final boolean[] WALL_V3 = new boolean[CODE_COUNT];
    private static final boolean[] WALL_V4 = new boolean[CODE_COUNT];
    private static final boolean[] SCENERY_V3 = new boolean[CODE_COUNT];
    private static final boolean[] SCENERY_V4 = new boolean[CODE_COUNT];
    private static final boolean[] OVER_V3 = new boolean[CODE_COUNT];
    private static final boolean[] OVER_V4 = new boolean[CODE_COUNT];

    static {
        for (byte c = 0; c < CODE_COUNT; c++) {
            boolean v3 = c <= DownhillValidator.WHITE;
            DRIVE[c] = v3 && DownhillValidator.drive(c);
            WALL_V3[c] = v3 && DownhillValidator.wall(c);
            SCENERY_V3[c] = v3 && DownhillValidator.scenery(c);
            OVER_V3[c] = c == DownhillValidator.MOSS || c == DownhillValidator.LOG || c == DownhillValidator.LEAVES;
            WALL_V4[c] = WALL_V3[c] || c == STONE || c == SNOW || c == RED_GATE || c == BLUE_GATE;
            SCENERY_V4[c] = SCENERY_V3[c] || c == STONE || c == SNOW;
            OVER_V4[c] = OVER_V3[c] || c == STONE || c == SNOW;
        }
    }

    /**
     * What the proof asks of one style and tier (MOUNTAIN-V2-SPEC §3.4, §5.3, §5.4).
     *
     * @param tier        {@code easy}, {@code medium} or {@code hard}
     * @param slalom      the Slalom (else the Winding Road)
     * @param narrowest   P: the narrowest passage, and the least gap between walls
     * @param iceLine     B: the width of the always-open ice line past every sand piece
     * @param bigDrops    the most 2-block drops, when {@code bigShareDen} is 0
     * @param bigShareNum the most 2-block drops as a share of all drops, numerator (else 0)
     * @param bigShareDen ... and denominator (0: {@code bigDrops} is a count)
     * @param descentMin  the least the track falls from the start to the bottom: a real downhill
     * @param descentCap  the most it falls
     * @param blueIce     whether blue ice may be used (not on easy)
     * @param pocketWidth the width of the wide middle every block of track keeps within 3 of (V5c):
     *                    P on a road; on a slalom the road's P of its tier (5, 4, 4), since a
     *                    corridor 15 wide has no slack round its 9-wide P-7 middle on whole-block
     *                    arcs, and its P is the gates' narrowest opening
     */
    public record Rules(String tier, boolean slalom, int narrowest, int iceLine, int bigDrops, int bigShareNum,
                        int bigShareDen, int descentMin, int descentCap, boolean blueIce, int pocketWidth) {

        /** The biggest drop: {@link BoatEnvelope#MAX_DROP} on every tier. */
        public int maxDrop() {
            return BoatEnvelope.MAX_DROP;
        }

        /** The most 2-block drops a track with {@code drops} drops may have. */
        public int bigDrops(int drops) {
            return bigShareDen == 0 ? bigDrops : drops * bigShareNum / bigShareDen;
        }

        /** "road" or "slalom". */
        public String style() {
            return slalom ? "slalom" : "road";
        }

        /** "a medium road", "an easy slalom": how the proof's lines name it. */
        public String label() {
            return (tier.equals("easy") ? "an " : "a ") + tier + " " + style();
        }

        /** The rules of {@code tier} (any case) in a style; {@code null} for a word that isn't a tier. */
        public static Rules of(String tier, boolean slalom) {
            String t = tier == null ? "" : tier.trim().toLowerCase(Locale.ROOT);
            return switch (t) {
                case "easy" -> slalom ? new Rules(t, true, 7, 5, 2, 0, 0, 8, 16, false, 5)
                        : new Rules(t, false, 5, 5, 4, 0, 0, 16, 28, false, 5);
                case "medium" -> slalom ? new Rules(t, true, 5, 4, 0, 2, 3, 16, 34, true, 4)
                        : new Rules(t, false, 4, 4, 0, 2, 3, 32, 52, true, 4);
                case "hard" -> slalom ? new Rules(t, true, 4, 3, 0, 4, 5, 20, 40, true, 4)
                        : new Rules(t, false, 4, 3, 0, 4, 5, 44, 64, true, 4);
                default -> null;
            };
        }
    }

    private MountainValidator() {
    }

    // ---- the contract --------------------------------------------------------------------------------

    /** What is wrong with {@code plan} as a {@code tier} Mountain Run v2 in its seed's style; empty when nothing is. */
    public static List<String> problems(Plan plan, String tier) {
        if (plan == null) {
            return List.of("there is no plan");
        }
        Rules rules = Rules.of(tier, slalom(plan.seed()));
        if (rules == null) {
            return List.of("'" + tier + "' isn't an Ice Boat tier");
        }
        return problems(plan, rules);
    }

    /** What is wrong with {@code plan} as a Mountain Run v2 of this style and tier; empty when nothing is. */
    public static List<String> problems(Plan plan, Rules rules) {
        if (plan == null) {
            return List.of("there is no plan");
        }
        if (rules == null) {
            return List.of("no rules to judge the plan by");
        }
        return survey(plan, Limits.v4(rules));
    }

    /**
     * Whether a seed's Mountain Run v2 is the Slalom (else the Winding Road): the low bit of the
     * SplitMix64 finaliser of {@code seed ^ 0x5EED57E1E00DL} (§5.1). {@code BoatStyle.of} must agree.
     */
    public static boolean slalom(long seed) {
        long z = seed ^ 0x5EED57E1E00DL;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z = z ^ (z >>> 31);
        return (z & 1L) == 1L;
    }

    /** The shortest believable time of a course, whole seconds (V12): v3's leg bound, {@link DownhillValidator#minSeconds}. */
    public static int minSeconds(Course course) {
        return DownhillValidator.minSeconds(course);
    }

    /** The stand's centre column x for {@code half}: its middle. */
    public static int standX(Box half) {
        return (int) Math.floor(half.minX() + half.sizeX() / 2.0);
    }

    /** The stand's centre row z: {@value #STAND_BACK} in from the half's south edge. */
    public static int standZ(Box half) {
        return half.minZ() + half.sizeZ() - STAND_BACK;
    }

    /** Where a player stands on the stand of a v2 course whose finish is at height {@code finishY} ({@code RaceStand.spotV4}). */
    public static Point standSpot(Box half, double finishY) {
        return new Point(standX(half) + 0.5, finishY + RaceStand.ABOVE, standZ(half) + 0.5);
    }

    /**
     * The sparse engine with v3's own rules (the Mountain Run, algo 3): for the parity test, which
     * holds it to {@link DownhillValidator}'s verdicts word for word. Never used to judge a plan.
     */
    static List<String> v3(Plan plan, String tier) {
        if (plan == null) {
            return List.of("there is no plan");
        }
        DownhillValidator.Tier t = DownhillValidator.Tier.of(tier);
        if (t == null) {
            return List.of("'" + tier + "' isn't an Ice Boat tier");
        }
        return survey(plan, Limits.v3(t));
    }

    private static List<String> survey(Plan plan, Limits limits) {
        if (!(plan.course() instanceof PlannedTrial trial) || trial.course().kind() != TrialKind.BOAT) {
            return List.of("the plan isn't a boat course");
        }
        if (trial.course().start() == null || trial.course().finish() == null || plan.half() == null) {
            return List.of("the course has no start or no finish");
        }
        try {
            return new Survey(plan, limits, trial).run();
        } catch (RuntimeException e) {
            // a refusal, never a pass: a plan this can't read is never built
            return List.of((limits.v4 ? "the Mountain Run v2 check" : "the Mountain Run check")
                    + " couldn't read this plan (" + e + ")");
        }
    }

    // ---- what one survey asks ------------------------------------------------------------------------

    /** The numbers and switches of one survey: v2's rules, or v3's for the parity test. */
    private record Limits(boolean v4, String tierId, String label, int narrowest, int iceLine, int maxDrop,
                          Rules rules, DownhillValidator.Tier v3Tier, int maxOps, int maxCheckpoints, boolean slalom,
                          boolean blueIce, int pocketWidth) {

        static Limits v4(Rules r) {
            return new Limits(true, r.tier(), r.label(), r.narrowest(), r.iceLine(), r.maxDrop(), r, null, MAX_OPS,
                    MAX_CHECKPOINTS, r.slalom(), r.blueIce(), r.pocketWidth());
        }

        static Limits v3(DownhillValidator.Tier t) {
            return new Limits(false, t.id(), t.id(), t.narrowest(), t.iceLine(), t.maxDrop(), null, t,
                    DownhillValidator.MAX_OPS, DownhillValidator.MAX_CHECKPOINTS, false, t.blueIce(), t.narrowest());
        }
    }

    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] MOVES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1},
            {2, 1}, {2, -1}, {-2, 1}, {-2, -1}, {1, 2}, {1, -2}, {-1, 2}, {-1, -2}};
    private static final double EPS = 1e-9;
    private static final int NONE = DeckGraph.NONE;

    private static final byte AIR = DownhillValidator.AIR;
    private static final byte SAND = DownhillValidator.SAND;
    private static final byte BLUE = DownhillValidator.BLUE;
    private static final byte GLASS = DownhillValidator.GLASS;
    private static final byte ROOF_GLASS = DownhillValidator.ROOF_GLASS;
    private static final byte LEAVES = DownhillValidator.LEAVES;

    /** One drop: its lip cells (the high side's edge), its height, and how far it falls. */
    private record Lip(List<int[]> cells, int upper, int drop) {

        int[] first() {
            return cells.get(0);
        }
    }

    /**
     * A checkpoint's or the finish's footprint on the track, in a window round it: {@code near}
     * (its middle within the radius) and {@code inside} (wholly inside the sphere) per column.
     */
    private record Disk(Course.Mark mark, String name, double px, double pz, int cx, int cz, int x0, int z0, int w,
                        int d, boolean[] nearW, List<int[]> nearCells, boolean[] insideW) {

        boolean near(int x, int z) {
            int ox = x - x0;
            int oz = z - z0;
            return ox >= 0 && oz >= 0 && ox < w && oz < d && nearW[ox * d + oz];
        }

        boolean inside(int x, int z) {
            int ox = x - x0;
            int oz = z - z0;
            return ox >= 0 && oz >= 0 && ox < w && oz < d && insideW[ox * d + oz];
        }
    }

    /** Problems said once each, with how many more there are like the first. */
    private static final class Found {

        private final Map<String, Integer> counts = new LinkedHashMap<>();
        private final Map<String, String> firsts = new HashMap<>();

        void add(String line) {
            add(line, line);
        }

        void add(String key, String line) {
            counts.merge(key, 1, Integer::sum);
            firsts.putIfAbsent(key, line);
        }

        boolean empty() {
            return counts.isEmpty();
        }

        List<String> lines() {
            List<String> out = new ArrayList<>();
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                int n = e.getValue();
                String first = firsts.get(e.getKey());
                out.add(n == 1 ? first : first + " (and " + (n - 1) + " more like it)");
            }
            return out;
        }
    }

    /** One plan, read. */
    private static final class Survey {

        final Plan plan;
        final Limits lim;
        final PlannedTrial trial;
        final Course course;
        final Box half;
        final int sx;
        final int sy;
        final int sz;
        final Found found = new Found();
        final boolean[] wallOk;
        final boolean[] sceneryOk;
        final boolean[] overOk;

        byte[] codes;
        String[] ids;
        boolean blue;
        int floorY;
        int top;
        int standX;
        int standZ;
        final Map<Long, String> stand = new HashMap<>();

        // the column-sparse blocks: column (x, z) is entries start[c] .. start[c + 1] - 1, by y
        int[] start;
        short[] ly;
        short[] st;

        int[][] h;
        DeckGraph graph;
        int startX;
        int startZ;
        int minH = Integer.MAX_VALUE;
        int[][] lipDrop;
        final List<Lip> lips = new ArrayList<>();
        int[][] zoneLip;
        boolean[][] island;
        boolean[][] core;
        boolean[][] gateField;

        // scratch, reused by every checkpoint
        int[] reachStamp;
        int reachGen;
        double[] dist;
        int[] distStamp;
        int distGen;

        Survey(Plan plan, Limits lim, PlannedTrial trial) {
            this.plan = plan;
            this.lim = lim;
            this.trial = trial;
            this.course = trial.course();
            this.half = plan.half();
            this.sx = half.sizeX();
            this.sy = half.sizeY();
            this.sz = half.sizeZ();
            this.wallOk = lim.v4 ? WALL_V4 : WALL_V3;
            this.sceneryOk = lim.v4 ? SCENERY_V4 : SCENERY_V3;
            this.overOk = lim.v4 ? OVER_V4 : OVER_V3;
        }

        List<String> run() {
            if (lim.v4) {
                if (sx != SIZE_X || sy != SIZE_Y || sz != SIZE_Z) {
                    return List.of("the area " + half.describe() + " isn't a Mountain Run v2 half: it is " + SIZE_X
                            + " x " + SIZE_Y + " x " + SIZE_Z);
                }
                if (Math.floorMod(half.minX(), ALIGN) != 0 || Math.floorMod(half.minZ(), ALIGN) != 0) {
                    return List.of("the area " + half.describe() + " isn't on chunk corners: a Mountain Run v2 half"
                            + " starts on a multiple of " + ALIGN + " in x and z");
                }
            } else if ((long) sx * sy * sz > 4_000_000L) {
                return List.of("the area " + half.describe() + " is too big for an Ice Boat course");
            }
            if (!palette() || !blocks() || !floors()) {
                return found.lines();
            }
            graph = DeckGraph.of(h);
            steps();
            lips();
            reachable();
            zones();
            columns();
            signs();
            widths();
            targets();
            falls();
            stand();
            grid();
            times();
            boxes();
            for (String p : Palette.leafProblems(plan.palette(), plan.ops())) {
                found.add(p);
            }
            return found.lines();
        }

        boolean wall(byte m) {
            return wallOk[m];
        }

        boolean scenery(byte m) {
            return sceneryOk[m];
        }

        static boolean drive(byte m) {
            return DRIVE[m];
        }

        // ---- V1, V13: the palette, the size, inside the half -----------------------------------------

        boolean palette() {
            List<String> palette = plan.palette();
            Map<String, Byte> table = lim.v4 ? CODES_V4 : DownhillValidator.CODES;
            codes = new byte[palette.size()];
            ids = new String[palette.size()];
            for (int i = 0; i < palette.size(); i++) {
                String p = palette.get(i);
                String id = Palette.id(p);
                ids[i] = id;
                if (id.equals("minecraft:slime_block")) {
                    found.add("slime ('" + p + "'): nothing on the Mountain Run may lift a boat");
                } else if (Palette.poolWater(p) || id.equals("minecraft:water")) {
                    found.add("water ('" + p + "'): the Mountain Run stays dry");
                } else if (!Palette.allowed(p)) {
                    found.add("'" + p + "' isn't a Fresh Courses block");
                } else if (!table.containsKey(id)) {
                    found.add("'" + p + "' isn't an Ice Boat block");
                } else {
                    codes[i] = table.get(id);
                    blue |= codes[i] == BLUE;
                }
            }
            for (String p : Palette.stateProblems(palette)) {
                found.add("the palette's " + p);
            }
            for (SignText s : plan.signs()) {
                String id = Palette.id(s.blockData());
                if (!id.equals(Palette.SIGN) && !id.equals(Palette.WALL_SIGN)) {
                    found.add("sign", "'" + s.blockData() + "' at " + s.x() + " " + s.y() + " " + s.z()
                            + " isn't a sign an Ice Boat course puts up");
                }
            }
            if (plan.ops().size() > lim.maxOps) {
                found.add(plan.ops().size() + " blocks is more than " + lim.maxOps);
            }
            if (plan.keepClear().size() > MAX_BOXES) {
                found.add(plan.keepClear().size() + " keep-clear boxes is more than " + MAX_BOXES);
            }
            return found.empty();
        }

        boolean blocks() {
            Course.Spot startSpot = course.start();
            if (lim.v4) {
                floorY = RaceStand.floorY(course.finish().y());
                top = half.maxY();
                standX = MountainValidator.standX(half);
                standZ = MountainValidator.standZ(half);
            } else {
                floorY = RaceStand.floorY(startSpot.y());
                top = Math.min(half.maxY(), floorY - 1);
                standX = RaceStand.centreX(half);
                standZ = RaceStand.centreZ(half);
            }
            long volume = (long) sx * sy * sz;
            long[] taken = new long[(int) ((volume + 63) >>> 6)];
            List<BlockOp> ops = plan.ops();
            int[] column = new int[ops.size()];
            int[] count = new int[sx * sz + 1];
            int kept = 0;
            for (int k = 0; k < ops.size(); k++) {
                BlockOp op = ops.get(k);
                column[k] = -1;
                if (op.state() >= codes.length) {
                    found.add("a block at " + op.x() + " " + op.y() + " " + op.z() + " has no palette entry");
                    continue;
                }
                if (!half.contains(op.x(), op.y(), op.z())) {
                    found.add("outside", "a block at " + op.x() + " " + op.y() + " " + op.z() + " is outside the half");
                    continue;
                }
                int x = op.x() - half.minX();
                int z = op.z() - half.minZ();
                long bit = ((long) x * sz + z) * sy + (op.y() - half.minY());
                if ((taken[(int) (bit >>> 6)] & (1L << (bit & 63))) != 0) {
                    found.add("twice", "two blocks at " + op.x() + " " + op.y() + " " + op.z());
                    continue;
                }
                taken[(int) (bit >>> 6)] |= 1L << (bit & 63);
                if (lim.v4 && (x == 0 || z == 0 || x == sx - 1 || z == sz - 1)) {
                    found.add("rim", "a block at " + op.x() + " " + op.y() + " " + op.z()
                            + " is on the half's edge: nothing stands within 1 of it");
                }
                if (op.y() >= floorY) {
                    boolean onStand = RaceStand.onPlatform(op.x(), op.z(), standX, standZ)
                            && op.y() <= floorY + RaceStand.RAIL;
                    if (onStand) {
                        // the stand's blocks: its own rule reads them, the column rules never do
                        stand.put(DownhillValidator.standKey(op.x(), op.y(), op.z()), ids[op.state()]);
                        continue;
                    }
                    if (!lim.v4) {
                        found.add("cap", "a block at " + op.x() + " " + op.y() + " " + op.z()
                                + " is at or over the stand's floor (y " + floorY + "): only the stand stands there");
                    } else if (nearStand(op.x(), op.z())) {
                        found.add("cap", "a block at " + op.x() + " " + op.y() + " " + op.z() + " is within "
                                + fmt(STAND_CLEAR) + " of the viewing stand at or over its floor (y " + floorY
                                + "): only the stand stands there");
                    }
                }
                column[k] = x * sz + z;
                count[column[k] + 1]++;
                kept++;
            }
            for (SignText s : plan.signs()) {
                if (!half.contains(s.x(), s.y(), s.z())) {
                    found.add("outside", "a sign at " + s.x() + " " + s.y() + " " + s.z() + " is outside the half");
                }
            }
            // the column-sparse table: offsets, then each column's entries sorted by y
            start = new int[sx * sz + 1];
            for (int c = 0; c < sx * sz; c++) {
                start[c + 1] = start[c] + count[c + 1];
            }
            ly = new short[kept];
            st = new short[kept];
            int[] fill = Arrays.copyOf(start, sx * sz);
            for (int k = 0; k < ops.size(); k++) {
                int c = column[k];
                if (c < 0) {
                    continue;
                }
                BlockOp op = ops.get(k);
                int i = fill[c]++;
                short y = (short) (op.y() - half.minY());
                short s = op.state();
                // insertion into the column's sorted run (columns hold a handful of blocks)
                while (i > start[c] && ly[i - 1] > y) {
                    ly[i] = ly[i - 1];
                    st[i] = st[i - 1];
                    i--;
                }
                ly[i] = y;
                st[i] = s;
            }
            return found.empty();
        }

        /** Whether column (x, z) (world) is within {@link #STAND_CLEAR} of the stand's centre column (v4). */
        boolean nearStand(int x, int z) {
            return Math.hypot(x - standX, z - standZ) <= STAND_CLEAR + EPS;
        }

        // ---- the column-sparse blocks ----------------------------------------------------------------

        int col(int x, int z) {
            return x * sz + z;
        }

        /** The block at local column (x, z), world height y: air outside the half (and the stand's own blocks). */
        byte at(int x, int y, int z) {
            int k = entry(x, y, z);
            return k < 0 ? AIR : codes[st[k]];
        }

        /** The entry at local column (x, z), world height y, or -1. */
        int entry(int x, int y, int z) {
            int l = y - half.minY();
            if (!inside(x, z) || l < 0 || l >= sy) {
                return -1;
            }
            int c = col(x, z);
            int lo = start[c];
            int hi = start[c + 1] - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                int v = ly[mid];
                if (v < l) {
                    lo = mid + 1;
                } else if (v > l) {
                    hi = mid - 1;
                } else {
                    return mid;
                }
            }
            return -1;
        }

        int yOf(int k) {
            return ly[k] + half.minY();
        }

        byte codeOf(int k) {
            return codes[st[k]];
        }

        /** The plan's blocks as a race grid reads the world ({@link PlanSurface}'s mapping), world coordinates. */
        RaceGrid.Cell surfaceAt(int x, int y, int z) {
            // what PlanSurface says: every block the plan places is solid (no water reaches here)
            int k = entry(x - half.minX(), y, z - half.minZ());
            if (k >= 0 || stand.containsKey(DownhillValidator.standKey(x, y, z))) {
                return RaceGrid.Cell.SOLID;
            }
            return RaceGrid.Cell.AIR;
        }

        // ---- V2: one floor a column ----------------------------------------------------------------

        boolean floors() {
            h = new int[sx][sz];
            for (int[] row : h) {
                Arrays.fill(row, NONE);
            }
            boolean stacked = false;
            for (int x = 0; x < sx; x++) {
                for (int z = 0; z < sz; z++) {
                    int c = col(x, z);
                    for (int k = start[c]; k < start[c + 1]; k++) {
                        int y = yOf(k);
                        if (y > top) {
                            break;
                        }
                        if (!drive(codeOf(k))) {
                            continue;
                        }
                        boolean covered = y != top && k + 1 < start[c + 1] && ly[k + 1] == ly[k] + 1
                                && drive(codeOf(k + 1));
                        if (covered) {
                            continue;
                        }
                        if (h[x][z] != NONE) {
                            stacked = true;
                            found.add("stacked", "two floors in one column at " + wx(x) + " " + wz(z) + " (ice at "
                                    + h[x][z] + " and " + y + "): no track runs over another");
                        }
                        h[x][z] = y;
                    }
                    if (h[x][z] != NONE) {
                        minH = Math.min(minH, h[x][z]);
                    }
                }
            }
            if (stacked) {
                return false;
            }
            if (minH == Integer.MAX_VALUE) {
                found.add("there is no track: no ice or sand to drive on");
                return false;
            }
            Course.Spot s = course.start();
            startX = (int) Math.floor(s.x()) - half.minX();
            startZ = (int) Math.floor(s.z()) - half.minZ();
            if (!onSurface(startX, startZ, s.y())) {
                found.add("the start (" + fmt(s.x()) + " " + fmt(s.y()) + " " + fmt(s.z())
                        + ") isn't on the track's surface");
                return false;
            }
            if (at(startX, h[startX][startZ], startZ) == SAND) {
                found.add("the start is on sand");
            }
            Course.Mark f = course.finish();
            if (!onSurface(cell(f.x(), half.minX()), cell(f.z(), half.minZ()), f.y())) {
                found.add("the finish (" + fmt(f.x()) + " " + fmt(f.y()) + " " + fmt(f.z())
                        + ") isn't on the track's surface");
                return false;
            }
            return true;
        }

        boolean onSurface(int x, int z, double y) {
            return inside(x, z) && h[x][z] != NONE && Math.abs(y - (h[x][z] + 1)) < 1e-6;
        }

        // ---- V3: downhill only -----------------------------------------------------------------------

        void steps() {
            for (int x = 0; x < sx; x++) {
                for (int z = 0; z < sz; z++) {
                    if (h[x][z] == NONE) {
                        continue;
                    }
                    for (int[] s : new int[][]{{1, 0}, {0, 1}}) {
                        int nx = x + s[0];
                        int nz = z + s[1];
                        if (driveAt(nx, nz) && Math.abs(h[nx][nz] - h[x][z]) > lim.maxDrop) {
                            int hi = Math.max(h[nx][nz], h[x][z]);
                            found.add("step", "the track drops " + Math.abs(h[nx][nz] - h[x][z]) + " blocks at "
                                    + wx(x) + " " + (hi + 1) + " " + wz(z) + "; "
                                    + (lim.v4 ? "a Mountain Run" : lim.tierId) + " drops at most " + lim.maxDrop);
                        }
                    }
                    for (int dz : new int[]{1, -1}) {
                        int nx = x + 1;
                        int nz = z + dz;
                        if (!driveAt(nx, nz) || h[nx][nz] == h[x][z]) {
                            continue;
                        }
                        boolean sideA = driveAt(nx, z);
                        boolean sideB = driveAt(x, nz);
                        if (!sideA && !sideB) {
                            continue;
                        }
                        boolean square = (!sideA || h[nx][z] == h[x][z] || h[nx][z] == h[nx][nz])
                                && (!sideB || h[x][nz] == h[x][z] || h[x][nz] == h[nx][nz]);
                        if (!square) {
                            found.add("corner", "a corner step at " + wx(x) + " " + (h[x][z] + 1) + " " + wz(z)
                                    + ": track blocks corner to corner differ in height with nothing square between");
                        }
                    }
                }
            }
        }

        void lips() {
            lipDrop = new int[sx][sz];
            for (int x = 0; x < sx; x++) {
                for (int z = 0; z < sz; z++) {
                    if (h[x][z] == NONE) {
                        continue;
                    }
                    for (int[] s : SIDES) {
                        int nx = x + s[0];
                        int nz = z + s[1];
                        if (driveAt(nx, nz) && h[nx][nz] < h[x][z]) {
                            lipDrop[x][z] = Math.max(lipDrop[x][z], h[x][z] - h[nx][nz]);
                        }
                    }
                }
            }
            boolean[][] seen = new boolean[sx][sz];
            for (int x = 0; x < sx; x++) {
                for (int z = 0; z < sz; z++) {
                    if (lipDrop[x][z] == 0 || seen[x][z]) {
                        continue;
                    }
                    List<int[]> cells = new ArrayList<>();
                    int drop = 0;
                    ArrayDeque<int[]> queue = new ArrayDeque<>();
                    seen[x][z] = true;
                    queue.add(new int[]{x, z});
                    while (!queue.isEmpty()) {
                        int[] c = queue.poll();
                        cells.add(c);
                        drop = Math.max(drop, lipDrop[c[0]][c[1]]);
                        for (int dx = -1; dx <= 1; dx++) {
                            for (int dz = -1; dz <= 1; dz++) {
                                int nx = c[0] + dx;
                                int nz = c[1] + dz;
                                if (inside(nx, nz) && !seen[nx][nz] && lipDrop[nx][nz] > 0 && h[nx][nz] == h[x][z]) {
                                    seen[nx][nz] = true;
                                    queue.add(new int[]{nx, nz});
                                }
                            }
                        }
                    }
                    lips.add(new Lip(cells, h[x][z], drop));
                }
            }
        }

        void reachable() {
            boolean[][] reach = graph.reach(startX, startZ, null);
            for (int x = 0; x < sx; x++) {
                for (int z = 0; z < sz; z++) {
                    if (h[x][z] != NONE && !reach[x][z]) {
                        found.add("unreached", "the track at " + wx(x) + " " + (h[x][z] + 1) + " " + wz(z)
                                + " can't be reached from the start: a boat can't climb");
                    }
                }
            }
            int from = graph.deck(startX, startZ);
            int fx = cell(course.finish().x(), half.minX());
            int fz = cell(course.finish().z(), half.minZ());
            int to = graph.deck(fx, fz);
            if (from < 0) {
                found.add("a boat doesn't fit at the start: it needs 2 x 2 of level track");
            }
            if (to < 0) {
                found.add("a boat doesn't fit at the finish: it needs 2 x 2 of level track");
            }
            if (from < 0 || to < 0) {
                return;
            }
            boolean[] down = graph.decksFrom(from);
            boolean[] home = graph.decksTo(to);
            for (int d = 0; d < graph.decks(); d++) {
                if (down[d] && !home[d]) {
                    int[] c = graph.deckCell(d);
                    found.add("deadend", "the track at " + wx(c[0]) + " " + (graph.deckHeight(d) + 1) + " "
                            + wz(c[1]) + " is a dead end: a boat that gets there can't reach the finish");
                }
            }
        }

        // ---- the flight zones (W2, V6, V7) -----------------------------------------------------------

        void zones() {
            zoneLip = new int[sx][sz];
            for (int[] row : zoneLip) {
                Arrays.fill(row, NONE);
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
                            if (lipDrop[c[0]][c[1]] > 0) {
                                found.add("chained", "the drop at " + wx(c[0]) + " " + (h[c[0]][c[1]] + 1) + " "
                                        + wz(c[1]) + " is inside the flight zone of the drop at " + wx(x) + " "
                                        + (lip + 1) + " " + wz(z) + ": one flight could clear both");
                            }
                        }
                        for (int[] s : SIDES) {
                            int nx = c[0] + s[0];
                            int nz = c[1] + s[1];
                            if (driveAt(nx, nz) && stamp[nx][nz] != flood && h[nx][nz] <= lip
                                    && sq(nx - x) + sq(nz - z) <= reach * reach + EPS) {
                                stamp[nx][nz] = flood;
                                queue.add(new int[]{nx, nz});
                            }
                        }
                    }
                }
            }
        }

        // ---- V4, V7: every column beside, over and away from the track -------------------------------

        void columns() {
            for (int x = 0; x < sx; x++) {
                for (int z = 0; z < sz; z++) {
                    if (h[x][z] != NONE) {
                        driveColumn(x, z);
                    } else if (nearTrack(x, z)) {
                        wallColumn(x, z);
                    } else if (start[col(x, z)] != start[col(x, z) + 1]) {
                        farColumn(x, z);
                    }
                }
            }
        }

        void driveColumn(int x, int z) {
            int ice = h[x][z];
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (!inside(x + dx, z + dz)) {
                        found.add("edge", "the track at " + wx(x) + " " + (ice + 1) + " " + wz(z)
                                + " runs to the edge of the area");
                    }
                }
            }
            int c = col(x, z);
            for (int k = start[c]; k < start[c + 1]; k++) {
                int y = yOf(k);
                if (y > top) {
                    break;
                }
                byte m = codeOf(k);
                if (y == ice) {
                    continue;
                }
                String where = wx(x) + " " + y + " " + wz(z);
                if (y < ice) {
                    if (m == LEAVES) {
                        stray(x, y, z);
                    }
                } else if (y <= ice + DownhillValidator.HEADROOM) {
                    found.add("headroom", "something in the headroom over the track at " + where
                            + ": the 4 blocks over the ice stay clear");
                } else if (zoneLip[x][z] != NONE && y <= zoneLip[x][z] + 1 + DownhillValidator.WALL_ABOVE) {
                    found.add("landing", "something over the landing at " + where
                            + ": nothing within 2 over the top of the drop");
                } else if (m == ROOF_GLASS) {
                    if (y != ice + DownhillValidator.ROOF) {
                        found.add("roof", "the cave roof at " + where + " isn't 5 over the ice");
                    }
                } else if (!overOk[m]) {
                    found.add("over", "a block over the track at " + where + (lim.v4
                            ? ": over the ice only a cave roof, rock, trees and moss, 5 up or more"
                            : ": over the ice only a cave roof, trees and moss, 5 up or more"));
                }
            }
            // W3: under the high side of a drop of 2, a riser from the low surface up to the ice
            for (int[] s : SIDES) {
                int nx = x + s[0];
                int nz = z + s[1];
                if (!driveAt(nx, nz) || ice - h[nx][nz] < 2) {
                    continue;
                }
                for (int y = h[nx][nz] + 1; y < ice; y++) {
                    byte m = at(x, y, z);
                    if (m == AIR || m == LEAVES) {
                        found.add("riser", "no riser under the drop at " + wx(x) + " " + (ice + 1) + " " + wz(z)
                                + ": the column under the high ice is open at y " + y);
                        break;
                    }
                }
            }
        }

        void wallColumn(int x, int z) {
            int lo = Integer.MAX_VALUE;
            int hiIce = Integer.MIN_VALUE;
            int zoneNeed = Integer.MIN_VALUE;
            int[] zoneFrom = null;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int nx = x + dx;
                    int nz = z + dz;
                    if (!driveAt(nx, nz)) {
                        continue;
                    }
                    lo = Math.min(lo, h[nx][nz]);
                    hiIce = Math.max(hiIce, h[nx][nz]);
                    if (zoneLip[nx][nz] != NONE && zoneLip[nx][nz] + DownhillValidator.WALL_ABOVE > zoneNeed) {
                        zoneNeed = zoneLip[nx][nz] + DownhillValidator.WALL_ABOVE;
                        zoneFrom = new int[]{nx, nz};
                    }
                }
            }
            int need = Math.min(top, hiIce + DownhillValidator.WALL_ABOVE);
            String colName = wx(x) + " " + wz(z);
            int c = col(x, z);
            boolean any = false;
            for (int k = start[c]; k < start[c + 1] && !any; k++) {
                int y = yOf(k);
                any = y >= lo && y <= need;
            }
            if (!any) {
                found.add("open", "an open edge beside the track at " + colName + " (y " + (lo + 1)
                        + "): a wall stands beside every block of track");
            } else {
                for (int y = lo; y <= need; y++) {
                    if (!wall(at(x, y, z))) {
                        found.add("wall", "the wall at " + colName + " isn't solid at y " + y
                                + ": walls stand from the ice to 2 over the track beside them");
                        break;
                    }
                }
            }
            int covered = need;
            if (zoneNeed > need) {
                int want = Math.min(top, zoneNeed);
                for (int y = need + 1; y <= want; y++) {
                    if (!wall(at(x, y, z))) {
                        found.add("flight", "the wall at " + colName + " is too low beside the landing at "
                                + wx(zoneFrom[0]) + " " + (h[zoneFrom[0]][zoneFrom[1]] + 1) + " " + wz(zoneFrom[1])
                                + ": a boat flying off the drop needs it up to y " + want);
                        break;
                    }
                }
                covered = want;
            }
            // the wall's own run: every wall block from lo up without a gap
            int run = lo;
            int k0 = start[c];
            while (k0 < start[c + 1] && yOf(k0) < lo) {
                k0++;
            }
            for (int k = k0; k < start[c + 1] && run <= top && yOf(k) == run && wall(codeOf(k)); k++) {
                run++;
            }
            int from = Math.max(run, covered + 1);
            for (int k = start[c]; k < start[c + 1]; k++) {
                int y = yOf(k);
                if (y > top) {
                    break;
                }
                byte m = codeOf(k);
                if (y >= lo && y < from) {
                    continue;
                }
                if (y < lo) {
                    if (m == LEAVES) {
                        stray(x, y, z);
                    }
                } else if (m == ROOF_GLASS || scenery(m)) {
                    if (y < hiIce + DownhillValidator.ROOF) {
                        found.add("close", "scenery at " + wx(x) + " " + y + " " + wz(z)
                                + " is too close to the track: 2 columns away, or 5 over the ice");
                    } else if (atEdge(x, z)) {
                        found.add("inset", "scenery at " + wx(x) + " " + y + " " + wz(z) + " is within "
                                + DownhillValidator.SCENERY_INSET + " of the area's edge");
                    }
                } else {
                    stray(x, y, z);
                }
            }
        }

        boolean atEdge(int x, int z) {
            int in = DownhillValidator.SCENERY_INSET;
            return x < in || z < in || x >= sx - in || z >= sz - in;
        }

        void farColumn(int x, int z) {
            boolean edge = atEdge(x, z);
            int c = col(x, z);
            for (int k = start[c]; k < start[c + 1]; k++) {
                int y = yOf(k);
                if (y > top) {
                    break;
                }
                byte m = codeOf(k);
                if (!scenery(m)) {
                    stray(x, y, z);
                } else if (edge) {
                    found.add("inset", "scenery at " + wx(x) + " " + y + " " + wz(z) + " is within "
                            + DownhillValidator.SCENERY_INSET + " of the area's edge");
                }
            }
        }

        void stray(int x, int y, int z) {
            String name = "a block";
            if (lim.v4) {
                int k = entry(x, y, z);
                if (k >= 0) {
                    name = ids[st[k]];
                }
            } else {
                byte m = at(x, y, z);
                for (Map.Entry<String, Byte> e : DownhillValidator.CODES.entrySet()) {
                    if (e.getValue() == m) {
                        name = e.getKey();
                        break;
                    }
                }
            }
            found.add("stray", "a stray " + name + " at " + wx(x) + " " + y + " " + wz(z)
                    + ": it is no wall, riser, roof, tree or stand");
        }

        boolean nearTrack(int x, int z) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if ((dx != 0 || dz != 0) && driveAt(x + dx, z + dz)) {
                        return true;
                    }
                }
            }
            return false;
        }

        // ---- signs -----------------------------------------------------------------------------------

        void signs() {
            for (SignText s : plan.signs()) {
                if (!half.contains(s.x(), s.y(), s.z())) {
                    continue; // said already
                }
                String where = s.x() + " " + s.y() + " " + s.z();
                if (s.y() >= floorY && (!lim.v4 || nearStand(s.x(), s.z()))) {
                    boolean standSign = s.y() == floorY + 1 && RaceStand.onPlatform(s.x(), s.z(), standX, standZ)
                            && !RaceStand.onRail(s.x(), s.z(), standX, standZ);
                    if (!standSign) {
                        found.add("cap", lim.v4 ? "a sign at " + where + " is within " + fmt(STAND_CLEAR)
                                + " of the viewing stand at or over its floor (y " + floorY
                                + "): only the stand stands there"
                                : "a sign at " + where + " is at or over the stand's floor (y " + floorY
                                + "): only the stand stands there");
                    }
                    continue;
                }
                int x = s.x() - half.minX();
                int y = s.y();
                int z = s.z() - half.minZ();
                if (at(x, y, z) != AIR) {
                    found.add("signblock", "a sign inside a block at " + where);
                } else if (h[x][z] != NONE) {
                    found.add("signover", "a sign over the track at " + where);
                } else if (!held(s, x, y, z)) {
                    found.add("signheld", "the sign at " + where + " has nothing to stand or hang on");
                }
            }
        }

        boolean held(SignText s, int x, int y, int z) {
            if (Palette.id(s.blockData()).equals(Palette.SIGN)) {
                return y > half.minY() && at(x, y - 1, z) != AIR;
            }
            String facing = Palette.states(s.blockData()) == null ? null
                    : Palette.states(s.blockData()).get("facing");
            if (facing == null) {
                return false;
            }
            return switch (facing) {
                case "north" -> inside(x, z + 1) && at(x, y, z + 1) != AIR;
                case "south" -> inside(x, z - 1) && at(x, y, z - 1) != AIR;
                case "east" -> inside(x - 1, z) && at(x - 1, y, z) != AIR;
                case "west" -> inside(x + 1, z) && at(x + 1, y, z) != AIR;
                default -> false;
            };
        }

        // ---- V5: widths ------------------------------------------------------------------------------

        void widths() {
            double coreClear = (lim.narrowest - 1) / 2.0;
            double iceClear = (lim.iceLine - 1) / 2.0;
            double pocketClear = (lim.pocketWidth - 1) / 2.0;
            core = new boolean[sx][sz];
            boolean[][] iceCore = new boolean[sx][sz];
            boolean[][] middle = lim.pocketWidth == lim.narrowest ? core : new boolean[sx][sz];
            for (int x = 0; x < sx; x++) {
                for (int z = 0; z < sz; z++) {
                    if (h[x][z] == NONE) {
                        continue;
                    }
                    double clear = clearance(x, z, false);
                    core[x][z] = clear >= coreClear - EPS;
                    middle[x][z] = clear >= pocketClear - EPS;
                    iceCore[x][z] = at(x, h[x][z], z) != SAND && clearance(x, z, true) >= iceClear - EPS;
                }
            }
            Disk finish = disk(course.finish(), "the finish");
            if (!core[startX][startZ] || !reaches(core, finish)) {
                found.add("no path " + lim.narrowest + " wide runs from the start to the finish");
            }
            if (!iceCore[startX][startZ] || !reaches(iceCore, finish)) {
                found.add("the sand can't be avoided: no all-ice line " + lim.iceLine
                        + " wide runs from the start to the finish");
            }
            if (lim.slalom) {
                gateFields();
            }
            int r = (int) Math.ceil(DownhillValidator.POCKET);
            for (int x = 0; x < sx; x++) {
                for (int z = 0; z < sz; z++) {
                    if (h[x][z] == NONE || middle[x][z] || (gateField != null && gateField[x][z])) {
                        continue;
                    }
                    boolean close = false;
                    for (int dx = -r; dx <= r && !close; dx++) {
                        for (int dz = -r; dz <= r; dz++) {
                            if (inside(x + dx, z + dz) && middle[x + dx][z + dz]
                                    && dx * dx + dz * dz <= DownhillValidator.POCKET * DownhillValidator.POCKET + EPS) {
                                close = true;
                                break;
                            }
                        }
                    }
                    if (!close) {
                        found.add("pocket", "a side pocket at " + wx(x) + " " + (h[x][z] + 1) + " " + wz(z)
                                + ": every block of track is within 3 of the track's wide middle");
                    }
                }
            }
            gaps();
            islands();
        }

        /** The slalom's gate fields: every column within {@link #GATE_FIELD} of a gate fence's (red or blue concrete). */
        void gateFields() {
            gateField = new boolean[sx][sz];
            for (int x = 0; x < sx; x++) {
                for (int z = 0; z < sz; z++) {
                    if (h[x][z] != NONE) {
                        continue;
                    }
                    boolean gate = false;
                    int c = col(x, z);
                    for (int k = start[c]; k < start[c + 1] && !gate; k++) {
                        byte m = codeOf(k);
                        gate = m == RED_GATE || m == BLUE_GATE;
                    }
                    if (!gate) {
                        continue;
                    }
                    for (int ox = Math.max(0, x - GATE_FIELD); ox <= Math.min(sx - 1, x + GATE_FIELD); ox++) {
                        Arrays.fill(gateField[ox], Math.max(0, z - GATE_FIELD), Math.min(sz, z + GATE_FIELD + 1), true);
                    }
                }
            }
        }

        double clearance(int x, int z, boolean iceOnly) {
            double best = 3.5;
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    int nx = x + dx;
                    int nz = z + dz;
                    boolean open = driveAt(nx, nz) && !(iceOnly && at(nx, h[nx][nz], nz) == SAND);
                    if (open) {
                        continue;
                    }
                    double ex = Math.max(0, Math.max(nx - (x + 0.5), (x + 0.5) - (nx + 1)));
                    double ez = Math.max(0, Math.max(nz - (z + 0.5), (z + 0.5) - (nz + 1)));
                    best = Math.min(best, Math.sqrt(ex * ex + ez * ez));
                }
            }
            return best;
        }

        boolean reaches(boolean[][] ok, Disk finish) {
            boolean[][] seen = new boolean[sx][sz];
            ArrayDeque<int[]> queue = new ArrayDeque<>();
            seen[startX][startZ] = true;
            queue.add(new int[]{startX, startZ});
            while (!queue.isEmpty()) {
                int[] c = queue.poll();
                if (finish.near(c[0], c[1])) {
                    return true;
                }
                for (int[] s : SIDES) {
                    int nx = c[0] + s[0];
                    int nz = c[1] + s[1];
                    if (driveAt(nx, nz) && ok[nx][nz] && !seen[nx][nz] && h[nx][nz] <= h[c[0]][c[1]]) {
                        seen[nx][nz] = true;
                        queue.add(new int[]{nx, nz});
                    }
                }
            }
            return false;
        }

        void gaps() {
            int[][] dirs = {{1, 0}, {0, 1}, {1, 1}, {1, -1}};
            for (int[] d : dirs) {
                double unit = d[0] != 0 && d[1] != 0 ? Math.sqrt(2) : 1;
                int longest = (int) Math.ceil(lim.narrowest / unit);
                for (int x = 0; x < sx; x++) {
                    for (int z = 0; z < sz; z++) {
                        if (h[x][z] == NONE || passes(x - d[0], z - d[1], d)) {
                            continue;
                        }
                        int n = 1;
                        while (n < longest && passes(x + (n - 1) * d[0], z + (n - 1) * d[1], d)) {
                            n++;
                        }
                        if (n >= longest || n * unit >= lim.narrowest - EPS) {
                            continue;
                        }
                        int ax = x - d[0];
                        int az = z - d[1];
                        int lx = x + (n - 1) * d[0];
                        int lz = z + (n - 1) * d[1];
                        int[] b = end(lx, lz, d);
                        int[] a = driveAt(ax, az) ? new int[]{x - d[0], z} : new int[]{ax, az};
                        if (!touching(a, b)) {
                            found.add("gap", "a gap of " + fmt(n * unit) + " between walls at " + wx(x) + " "
                                    + (h[x][z] + 1) + " " + wz(z) + "; the narrowest passage is " + lim.narrowest);
                        }
                    }
                }
            }
        }

        boolean passes(int x, int z, int[] d) {
            int nx = x + d[0];
            int nz = z + d[1];
            if (!driveAt(x, z) || !driveAt(nx, nz)) {
                return false;
            }
            return d[0] == 0 || d[1] == 0 || driveAt(nx, z) || driveAt(x, nz);
        }

        int[] end(int x, int z, int[] d) {
            int nx = x + d[0];
            int nz = z + d[1];
            return driveAt(nx, nz) ? new int[]{nx, z} : new int[]{nx, nz};
        }

        boolean touching(int[] a, int[] b) {
            int x0 = Math.min(a[0], b[0]) - 1;
            int x1 = Math.max(a[0], b[0]) + 1;
            int z0 = Math.min(a[1], b[1]) - 1;
            int z1 = Math.max(a[1], b[1]) + 1;
            boolean[][] seen = new boolean[x1 - x0 + 1][z1 - z0 + 1];
            ArrayDeque<int[]> queue = new ArrayDeque<>();
            queue.add(a);
            seen[a[0] - x0][a[1] - z0] = true;
            while (!queue.isEmpty()) {
                int[] c = queue.poll();
                if (c[0] == b[0] && c[1] == b[1]) {
                    return true;
                }
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        int nx = c[0] + dx;
                        int nz = c[1] + dz;
                        if (nx < x0 || nz < z0 || nx > x1 || nz > z1 || seen[nx - x0][nz - z0] || driveAt(nx, nz)) {
                            continue;
                        }
                        seen[nx - x0][nz - z0] = true;
                        queue.add(new int[]{nx, nz});
                    }
                }
            }
            return false;
        }

        void islands() {
            island = new boolean[sx][sz];
            int[][] part = new int[sx][sz];
            int next = 0;
            int[] cells = new int[64];
            for (int x = 0; x < sx; x++) {
                for (int z = 0; z < sz; z++) {
                    if (h[x][z] != NONE || part[x][z] != 0) {
                        continue;
                    }
                    next++;
                    int n = 0;
                    int head = 0;
                    boolean edge = false;
                    part[x][z] = next;
                    cells[n++] = col(x, z);
                    while (head < n) {
                        int c = cells[head++];
                        int cx = c / sz;
                        int cz = c % sz;
                        for (int dx = -1; dx <= 1; dx++) {
                            for (int dz = -1; dz <= 1; dz++) {
                                int nx = cx + dx;
                                int nz = cz + dz;
                                if (!inside(nx, nz)) {
                                    edge = true;
                                } else if (h[nx][nz] == NONE && part[nx][nz] == 0) {
                                    part[nx][nz] = next;
                                    if (n == cells.length) {
                                        cells = Arrays.copyOf(cells, n * 2);
                                    }
                                    cells[n++] = col(nx, nz);
                                }
                            }
                        }
                    }
                    if (edge) {
                        continue;
                    }
                    List<int[]> list = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) {
                        list.add(new int[]{cells[i] / sz, cells[i] % sz});
                        island[cells[i] / sz][cells[i] % sz] = true;
                    }
                    if (ringed(list)) {
                        continue;
                    }
                    int[] c = list.get(0);
                    found.add("island", "a way round the island at " + wx(c[0]) + " " + wz(c[1])
                            + " is narrower than " + lim.narrowest + ": both ways round are wide");
                }
            }
        }

        boolean ringed(List<int[]> cells) {
            boolean[][] seen = new boolean[sx][sz];
            ArrayDeque<int[]> queue = new ArrayDeque<>();
            for (int[] c : cells) {
                seen[c[0]][c[1]] = true;
                queue.add(c);
            }
            while (!queue.isEmpty()) {
                int[] c = queue.poll();
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        int nx = c[0] + dx;
                        int nz = c[1] + dz;
                        if (!inside(nx, nz)) {
                            return false;
                        }
                        if (!seen[nx][nz] && !core[nx][nz]) {
                            seen[nx][nz] = true;
                            queue.add(new int[]{nx, nz});
                        }
                    }
                }
            }
            return true;
        }

        // ---- V6, V11: the checkpoints and the finish -------------------------------------------------

        Disk disk(Course.Mark m, String name) {
            double px = m.x() - half.minX();
            double pz = m.z() - half.minZ();
            double r = m.radius();
            List<int[]> cells = new ArrayList<>();
            int x0 = Math.max(0, (int) Math.floor(px - r) - 1);
            int x1 = Math.min(sx - 1, (int) Math.floor(px + r) + 1);
            int z0 = Math.max(0, (int) Math.floor(pz - r) - 1);
            int z1 = Math.min(sz - 1, (int) Math.floor(pz + r) + 1);
            int w = Math.max(0, x1 - x0 + 1);
            int d = Math.max(0, z1 - z0 + 1);
            boolean[] near = new boolean[w * d];
            boolean[] in = new boolean[w * d];
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    if (h[x][z] == NONE) {
                        continue;
                    }
                    if (sq(x + 0.5 - px) + sq(z + 0.5 - pz) <= r * r + EPS) {
                        near[(x - x0) * d + (z - z0)] = true;
                        cells.add(new int[]{x, z});
                    }
                    double fx = Math.max(Math.abs(x - px), Math.abs(x + 1 - px));
                    double fz = Math.max(Math.abs(z - pz), Math.abs(z + 1 - pz));
                    in[(x - x0) * d + (z - z0)] = fx * fx + fz * fz <= r * r + EPS;
                }
            }
            return new Disk(m, name, px, pz, (int) Math.floor(px), (int) Math.floor(pz), x0, z0, w, d, near, cells, in);
        }

        /**
         * Where a boat starting at the start can get to without passing through {@code blocked}'s
         * inside, as {@link DeckGraph#reach} reads it; afterwards {@link #reached} answers per column.
         */
        void reachFromStart(Disk blocked) {
            if (reachStamp == null) {
                reachStamp = new int[sx * sz];
            }
            reachGen++;
            if (!driveAt(startX, startZ) || blocked.inside(startX, startZ)) {
                return;
            }
            ArrayDeque<int[]> queue = new ArrayDeque<>();
            reachStamp[col(startX, startZ)] = reachGen;
            queue.add(new int[]{startX, startZ});
            while (!queue.isEmpty()) {
                int[] c = queue.poll();
                int hh = h[c[0]][c[1]];
                for (int[] s : SIDES) {
                    int nx = c[0] + s[0];
                    int nz = c[1] + s[1];
                    if (driveAt(nx, nz) && reachStamp[col(nx, nz)] != reachGen && h[nx][nz] <= hh
                            && !blocked.inside(nx, nz)) {
                        reachStamp[col(nx, nz)] = reachGen;
                        queue.add(new int[]{nx, nz});
                    }
                }
            }
        }

        boolean reached(int x, int z) {
            return reachStamp[col(x, z)] == reachGen;
        }

        void targets() {
            List<Course.Mark> cps = course.checkpoints();
            if (cps.size() > lim.maxCheckpoints) {
                found.add(cps.size() + " checkpoints is more than " + lim.maxCheckpoints);
            }
            if (Laps.loop(course)) {
                found.add("the course is a loop: a Mountain Run is a sprint from the top to the bottom");
            }
            if (Laps.natural(course) != 1) {
                found.add("the checkpoints are stored for " + Laps.natural(course) + " laps: a sprint stores them once");
            }
            int n = cps.size();
            List<Disk> disks = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                disks.add(disk(cps.get(i), "checkpoint " + (i + 1)));
            }
            Disk finish = disk(course.finish(), "the finish");
            disks.add(finish);
            boolean readable = true;
            for (Disk d : disks) {
                readable &= mark(d, d != finish);
            }
            if (!readable) {
                return;
            }
            int[] lipLeg = new int[lips.size()];
            boolean[][] after = null;
            for (int i = 0; i <= n; i++) {
                Disk d = disks.get(i);
                if (d.inside(startX, startZ)) {
                    found.add("the start is inside " + d.name());
                    continue;
                }
                reachFromStart(d);
                if (i < n) {
                    boolean round = false;
                    for (int[] c : finish.nearCells()) {
                        round |= reached(c[0], c[1]);
                    }
                    if (round) {
                        found.add(d.name() + " doesn't span the track: a boat can go round it");
                    } else {
                        facing(i, d);
                    }
                }
                if (i > 0) {
                    Disk prev = disks.get(i - 1);
                    boolean overlap = false;
                    boolean before = true;
                    for (int[] c : prev.nearCells()) {
                        overlap |= d.near(c[0], c[1]);
                        before &= d.inside(c[0], c[1]) || reached(c[0], c[1]);
                    }
                    if (overlap) {
                        found.add(prev.name() + " and " + d.name() + " overlap");
                    } else if (!before) {
                        found.add(d.name() + " comes before " + prev.name());
                    }
                }
                for (int k = 0; k < lips.size(); k++) {
                    int[] u = lips.get(k).first();
                    if (!reached(u[0], u[1]) && !d.inside(u[0], u[1])) {
                        lipLeg[k]++;
                    }
                }
                if (i == n) {
                    after = new boolean[sx][sz];
                    for (int x = 0; x < sx; x++) {
                        for (int z = 0; z < sz; z++) {
                            after[x][z] = h[x][z] != NONE && !reached(x, z) && !d.inside(x, z);
                        }
                    }
                }
            }
            int[] perLeg = new int[n + 2];
            for (int k = 0; k < lips.size(); k++) {
                perLeg[lipLeg[k]]++;
                if (lipLeg[k] == n + 1) {
                    int[] u = lips.get(k).first();
                    found.add("a drop after the finish at " + wx(u[0]) + " " + (lips.get(k).upper() + 1) + " "
                            + wz(u[1]));
                }
            }
            for (int leg = 0; leg <= n; leg++) {
                String a = leg == 0 ? "the start" : disks.get(leg - 1).name();
                String b = disks.get(leg).name();
                if (perLeg[leg] > 1) {
                    found.add(perLeg[leg] + " drops between " + a + " and " + b
                            + ": one checkpoint between every two drops");
                }
                double ax = leg == 0 ? course.start().x() : cps.get(leg - 1).x();
                double az = leg == 0 ? course.start().z() : cps.get(leg - 1).z();
                Course.Mark to = disks.get(leg).mark();
                double apart = Math.hypot(to.x() - ax, to.z() - az);
                if (apart > DownhillValidator.MAX_LEG + EPS) {
                    found.add(a + " and " + b + " are " + fmt(apart) + " apart; at most "
                            + fmt(DownhillValidator.MAX_LEG));
                }
                if (perLeg[leg] == 0) {
                    double along = along(ax - half.minX(), az - half.minZ(), disks.get(leg).px(), disks.get(leg).pz());
                    if (along == Double.MAX_VALUE) {
                        found.add("no way along the track at one level from " + a + " to " + b
                                + ", though no drop is between them");
                    } else if (along > DownhillValidator.MAX_ALONG + EPS) {
                        found.add(a + " and " + b + " are " + fmt(along) + " apart along the track with no drop"
                                + " between; at most " + fmt(DownhillValidator.MAX_ALONG)
                                + ", so a reset never sends a boat far back");
                    }
                }
            }
            if (lim.v4) {
                finalDrop(finish, n, perLeg, lipLeg, n == 0 ? "the start" : disks.get(n - 1).name());
            }
            finish(finish, after);
        }

        /**
         * V11 (v2): the Final Drop is the one drop of the finish's own leg, in front of the stand, at
         * most {@link #FINAL_FAR} before the finish (its nearest lip block; at least Z(d) + 3 is
         * {@link #finish}'s rule).
         */
        void finalDrop(Disk finish, int n, int[] perLeg, int[] lipLeg, String last) {
            if (perLeg[n] == 0) {
                found.add("no Final Drop between " + last + " and the finish: the last drop is in front of the stand,"
                        + " at most " + fmt(FINAL_FAR) + " before the finish");
                return;
            }
            for (int k = 0; k < lips.size(); k++) {
                if (lipLeg[k] != n) {
                    continue;
                }
                double nearest = Double.MAX_VALUE;
                for (int[] c : lips.get(k).cells()) {
                    nearest = Math.min(nearest, Math.hypot(c[0] + 0.5 - finish.px(), c[1] + 0.5 - finish.pz()));
                }
                if (nearest > FINAL_FAR + EPS) {
                    int[] u = lips.get(k).first();
                    found.add("the Final Drop at " + wx(u[0]) + " " + (lips.get(k).upper() + 1) + " " + wz(u[1])
                            + " is " + fmt(nearest) + " before the finish; at most " + fmt(FINAL_FAR)
                            + ", in front of the stand");
                }
            }
        }

        void facing(int i, Disk d) {
            double[] in = new double[3];
            double[] out = new double[3];
            int span = (int) Math.ceil(d.mark().radius()) + 2;
            for (int x = Math.max(0, d.cx() - span); x <= Math.min(sx - 1, d.cx() + span); x++) {
                for (int z = Math.max(0, d.cz() - span); z <= Math.min(sz - 1, d.cz() + span); z++) {
                    if (h[x][z] == NONE || d.inside(x, z) || at(x, h[x][z], z) == SAND) {
                        continue;
                    }
                    boolean edge = false;
                    for (int[] s : SIDES) {
                        edge |= inside(x + s[0], z + s[1]) && d.inside(x + s[0], z + s[1]);
                    }
                    if (!edge) {
                        continue;
                    }
                    double[] side = reached(x, z) ? in : out;
                    side[0] += x + 0.5;
                    side[1] += z + 0.5;
                    side[2]++;
                }
            }
            double lx = in[2] == 0 || out[2] == 0 ? 0 : out[0] / out[2] - in[0] / in[2];
            double lz = in[2] == 0 || out[2] == 0 ? 0 : out[1] / out[2] - in[1] / in[2];
            double len = Math.hypot(lx, lz);
            if (len < EPS) {
                found.add(d.name() + ": the lane's direction can't be read off the ice either side of it, so a"
                        + " reset there can't be shown to face down the track");
                return;
            }
            double yaw = Math.toRadians(course.resetYaw(i));
            double fx = -Math.sin(yaw);
            double fz = Math.cos(yaw);
            double off = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, (fx * lx + fz * lz) / len))));
            if (off > DownhillValidator.MAX_FACING + EPS) {
                found.add(d.name() + "'s reset faces " + fmt(off) + " degrees off the lane (toward the next target);"
                        + " at most " + fmt(DownhillValidator.MAX_FACING));
            }
        }

        double along(double ax, double az, double bx, double bz) {
            int x0 = (int) Math.floor(ax);
            int z0 = (int) Math.floor(az);
            int x1 = (int) Math.floor(bx);
            int z1 = (int) Math.floor(bz);
            if (!driveAt(x0, z0) || !driveAt(x1, z1) || h[x0][z0] != h[x1][z1]) {
                return Double.MAX_VALUE;
            }
            double slack = Math.hypot(ax - x0 - 0.5, az - z0 - 0.5) + Math.hypot(bx - x1 - 0.5, bz - z1 - 0.5);
            int level = h[x0][z0];
            if (dist == null) {
                dist = new double[sx * sz];
                distStamp = new int[sx * sz];
            }
            distGen++;
            PriorityQueue<double[]> queue = new PriorityQueue<>((p, q) -> Double.compare(p[0], q[0]));
            setDist(x0 * sz + z0, 0);
            queue.add(new double[]{0, x0, z0});
            while (!queue.isEmpty()) {
                double[] q = queue.poll();
                int x = (int) q[1];
                int z = (int) q[2];
                if (q[0] > dist(x * sz + z)) {
                    continue;
                }
                if (x == x1 && z == z1) {
                    return Math.max(0, q[0] - slack);
                }
                for (int[] m : MOVES) {
                    if (!open(x + m[0], z + m[1], level) || !passes(x, z, m[0], m[1], level)) {
                        continue;
                    }
                    double nd = q[0] + Math.sqrt(m[0] * m[0] + m[1] * m[1]);
                    int k = (x + m[0]) * sz + z + m[1];
                    if (nd < dist(k)) {
                        setDist(k, nd);
                        queue.add(new double[]{nd, x + m[0], z + m[1]});
                    }
                }
            }
            return Double.MAX_VALUE;
        }

        double dist(int k) {
            return distStamp[k] == distGen ? dist[k] : Double.MAX_VALUE;
        }

        void setDist(int k, double v) {
            distStamp[k] = distGen;
            dist[k] = v;
        }

        private boolean passes(int x, int z, int dx, int dz, int level) {
            if (Math.abs(dx) == 1 && Math.abs(dz) == 1) {
                return open(x + dx, z, level) && open(x, z + dz, level);
            }
            if (Math.abs(dx) == 2) {
                return open(x + dx / 2, z, level) && open(x + dx / 2, z + dz, level);
            }
            if (Math.abs(dz) == 2) {
                return open(x, z + dz / 2, level) && open(x + dx, z + dz / 2, level);
            }
            return true;
        }

        private boolean open(int x, int z, int level) {
            return inside(x, z) && (h[x][z] == level || (h[x][z] == NONE && island[x][z]));
        }

        /**
         * How many side steps each drive cell within {@code reach} of {@code d}'s middle is from its
         * nearest near cell, either way, up to {@code limit} ({@link DeckGraph#steps}, in a window);
         * the first column within reach that is farther (or not joined) is said.
         */
        boolean ringClear(Disk d, int limit, double reach) {
            int rx0 = Math.max(0, (int) Math.floor(d.px() - reach) - 1);
            int rx1 = Math.min(sx - 1, (int) Math.floor(d.px() + reach) + 1);
            int rz0 = Math.max(0, (int) Math.floor(d.pz() - reach) - 1);
            int rz1 = Math.min(sz - 1, (int) Math.floor(d.pz() + reach) + 1);
            int wx0 = Math.max(0, Math.min(rx0, d.x0()) - limit - 1);
            int wx1 = Math.min(sx - 1, Math.max(rx1, d.x0() + d.w() - 1) + limit + 1);
            int wz0 = Math.max(0, Math.min(rz0, d.z0()) - limit - 1);
            int wz1 = Math.min(sz - 1, Math.max(rz1, d.z0() + d.d() - 1) + limit + 1);
            int ww = wx1 - wx0 + 1;
            int wd = wz1 - wz0 + 1;
            int[] stepsW = new int[ww * wd];
            Arrays.fill(stepsW, -1);
            ArrayDeque<int[]> queue = new ArrayDeque<>();
            for (int[] c : d.nearCells()) {
                int k = (c[0] - wx0) * wd + (c[1] - wz0);
                if (driveAt(c[0], c[1]) && stepsW[k] < 0) {
                    stepsW[k] = 0;
                    queue.add(c);
                }
            }
            while (!queue.isEmpty()) {
                int[] c = queue.poll();
                int dd = stepsW[(c[0] - wx0) * wd + (c[1] - wz0)];
                if (dd >= limit) {
                    continue;
                }
                for (int[] s : SIDES) {
                    int nx = c[0] + s[0];
                    int nz = c[1] + s[1];
                    if (nx < wx0 || nz < wz0 || nx > wx1 || nz > wz1) {
                        continue;
                    }
                    int k = (nx - wx0) * wd + (nz - wz0);
                    if (driveAt(nx, nz) && stepsW[k] < 0) {
                        stepsW[k] = dd + 1;
                        queue.add(new int[]{nx, nz});
                    }
                }
            }
            for (int x = rx0; x <= rx1; x++) {
                for (int z = rz0; z <= rz1; z++) {
                    if (h[x][z] != NONE && stepsW[(x - wx0) * wd + (z - wz0)] < 0
                            && sq(x + 0.5 - d.px()) + sq(z + 0.5 - d.pz()) <= reach * reach + EPS) {
                        found.add(d.name() + " reaches another part of the track at " + wx(x) + " " + (h[x][z] + 1)
                                + " " + wz(z));
                        return false;
                    }
                }
            }
            return true;
        }

        boolean mark(Disk d, boolean checkpoint) {
            Course.Mark m = d.mark();
            if (m.radius() < DownhillValidator.MIN_RADIUS - EPS) {
                found.add(d.name() + "'s radius " + fmt(m.radius()) + " is under " + fmt(DownhillValidator.MIN_RADIUS));
            }
            if (m.radius() > Course.MAX_RADIUS + EPS) {
                found.add(d.name() + "'s radius " + fmt(m.radius()) + " is over " + fmt(Course.MAX_RADIUS));
            }
            if (!onSurface(d.cx(), d.cz(), m.y())) {
                found.add(d.name() + " (" + fmt(m.x()) + " " + fmt(m.y()) + " " + fmt(m.z())
                        + ") isn't on the track's surface");
                return false;
            }
            int level = h[d.cx()][d.cz()];
            boolean flat = true;
            boolean sand = false;
            boolean covered = false;
            for (int[] c : d.nearCells()) {
                flat &= h[c[0]][c[1]] == level;
                sand |= at(c[0], h[c[0]][c[1]], c[1]) == SAND
                        && sq(c[0] + 0.5 - d.px()) + sq(c[1] + 0.5 - d.pz())
                        <= DownhillValidator.RESET_ROOM * DownhillValidator.RESET_ROOM + EPS;
                if (!covered) {
                    int k = start[col(c[0], c[1])];
                    int end = start[col(c[0], c[1]) + 1];
                    for (; k < end && !covered; k++) {
                        int y = yOf(k);
                        covered = y > h[c[0]][c[1]] && y <= top;
                    }
                }
            }
            if (!flat) {
                found.add(d.name() + " isn't on flat track");
            }
            int limit = (int) Math.ceil(DownhillValidator.RING_STEPS * m.radius());
            ringClear(d, limit, m.radius() + DownhillValidator.RING_REACH);
            if (!checkpoint) {
                return true;
            }
            if (sand) {
                found.add(d.name() + " is on sand: a reset puts the boat down on ice, "
                        + fmt(DownhillValidator.RESET_ROOM) + " round its middle");
            }
            if (covered) {
                found.add(d.name() + " is under a roof or trees: a checkpoint is in the open");
            }
            outer:
            for (int x = Math.max(0, d.cx() - (int) Math.ceil(m.radius()) - 1);
                 x <= Math.min(sx - 1, d.cx() + (int) Math.ceil(m.radius()) + 1); x++) {
                for (int z = Math.max(0, d.cz() - (int) Math.ceil(m.radius()) - 1);
                     z <= Math.min(sz - 1, d.cz() + (int) Math.ceil(m.radius()) + 1); z++) {
                    if (island[x][z] && squareDistance(x, z, d.px(), d.pz()) <= m.radius() + EPS) {
                        found.add(d.name() + " is in a split or a forest: an island at " + wx(x) + " " + wz(z)
                                + " is within its reach");
                        break outer;
                    }
                }
            }
            double nearestLip = Double.MAX_VALUE;
            int[] lipAt = null;
            double nearestZone = Double.MAX_VALUE;
            int[] zoneAt = null;
            int span = (int) Math.ceil(DownhillValidator.DROP_CLEAR) + 1;
            for (int x = Math.max(0, d.cx() - span); x <= Math.min(sx - 1, d.cx() + span); x++) {
                for (int z = Math.max(0, d.cz() - span); z <= Math.min(sz - 1, d.cz() + span); z++) {
                    double dd = Math.hypot(x + 0.5 - d.px(), z + 0.5 - d.pz());
                    if (lipDrop[x][z] > 0 && dd < nearestLip) {
                        nearestLip = dd;
                        lipAt = new int[]{x, z};
                    }
                    if (zoneLip[x][z] != NONE && dd < nearestZone) {
                        nearestZone = dd;
                        zoneAt = new int[]{x, z};
                    }
                }
            }
            if (lipAt != null && nearestLip < DownhillValidator.DROP_CLEAR - EPS) {
                found.add(d.name() + " is " + fmt(nearestLip) + " from the drop at " + wx(lipAt[0]) + " "
                        + (h[lipAt[0]][lipAt[1]] + 1) + " " + wz(lipAt[1]) + "; at least "
                        + fmt(DownhillValidator.DROP_CLEAR));
            }
            if (zoneAt != null && nearestZone < DownhillValidator.DROP_CLEAR - EPS) {
                found.add(d.name() + " is in a flight zone: " + fmt(nearestZone) + " from the landing at "
                        + wx(zoneAt[0]) + " " + (h[zoneAt[0]][zoneAt[1]] + 1) + " " + wz(zoneAt[1]) + "; at least "
                        + fmt(DownhillValidator.DROP_CLEAR));
            }
            return true;
        }

        void finish(Disk d, boolean[][] after) {
            Course.Mark f = d.mark();
            int level = h[d.cx()][d.cz()];
            if (level != minH) {
                found.add("the finish isn't on the lowest track (its ice at " + level + ", the lowest at " + minH + ")");
            }
            for (Lip lip : lips) {
                boolean finalDrop = false;
                for (int[] c : lip.cells()) {
                    for (int[] s : SIDES) {
                        int nx = c[0] + s[0];
                        int nz = c[1] + s[1];
                        finalDrop |= driveAt(nx, nz) && h[nx][nz] == level && h[nx][nz] < lip.upper();
                    }
                }
                if (!finalDrop) {
                    continue;
                }
                double want = BoatEnvelope.zone(lip.drop()) + DownhillValidator.DROP_CLEAR;
                for (int[] c : lip.cells()) {
                    double dd = Math.hypot(c[0] + 0.5 - d.px(), c[1] + 0.5 - d.pz());
                    if (dd < want - EPS) {
                        found.add("the finish is " + fmt(dd) + " from the Final Drop at " + wx(c[0]) + " "
                                + (lip.upper() + 1) + " " + wz(c[1]) + "; at least " + fmt(want));
                        break;
                    }
                }
            }
            int r = RaceStand.SIZE / 2;
            double ex = Math.max(0, Math.max((standX - r) - f.x(), f.x() - (standX + r + 1)));
            double ez = Math.max(0, Math.max((standZ - r) - f.z(), f.z() - (standZ + r + 1)));
            double toStand = Math.hypot(ex, ez);
            if (toStand < DownhillValidator.FINISH_NEAR - EPS || toStand > DownhillValidator.FINISH_FAR + EPS) {
                found.add("the finish is " + fmt(toStand) + " from the viewing stand; "
                        + fmt(DownhillValidator.FINISH_NEAR) + " to " + fmt(DownhillValidator.FINISH_FAR)
                        + ", so finishers watch the rest come in");
            }
            if (after == null) {
                return;
            }
            boolean any = false;
            boolean flat = true;
            boolean sand = false;
            double nearestSand = Double.MAX_VALUE;
            for (int x = 0; x < sx; x++) {
                for (int z = 0; z < sz; z++) {
                    if (!after[x][z]) {
                        continue;
                    }
                    any = true;
                    flat &= h[x][z] == level;
                    if (at(x, h[x][z], z) == SAND) {
                        sand = true;
                        nearestSand = Math.min(nearestSand, Math.hypot(x + 0.5 - d.px(), z + 0.5 - d.pz()));
                    }
                }
            }
            if (!any) {
                found.add("nothing lies past the finish: it doesn't span the track, or no run-out and sand paddock"
                        + " follow it");
                return;
            }
            if (!flat) {
                found.add("the run-out after the finish isn't flat");
            }
            if (!sand) {
                found.add("no sand paddock after the finish");
            } else if (nearestSand < DownhillValidator.RUN_OUT - EPS) {
                found.add("sand " + fmt(nearestSand) + " after the finish: the run-out is at least "
                        + fmt(DownhillValidator.RUN_OUT) + " of ice");
            }
        }

        // ---- V8: falls -------------------------------------------------------------------------------

        void falls() {
            if (blue && !lim.blueIce) {
                found.add("blue ice on an easy track: easy races on packed ice");
            }
            int big = 0;
            for (Lip lip : lips) {
                big += lip.drop() == 2 ? 1 : 0;
            }
            int descent = h[startX][startZ] - minH;
            if (lim.v4) {
                Rules r = lim.rules;
                int cap = r.bigDrops(lips.size());
                if (big > cap) {
                    found.add(big + " drops of 2 blocks; " + r.label() + " with " + lips.size() + " drops has at most "
                            + cap);
                }
                if (descent > r.descentCap()) {
                    found.add("the track falls " + descent + " blocks from the start to the bottom; " + r.label()
                            + " falls at most " + r.descentCap());
                } else if (descent < r.descentMin()) {
                    found.add("the track falls only " + descent + " blocks from the start to the bottom; " + r.label()
                            + " falls at least " + r.descentMin() + ": a real downhill");
                }
            } else {
                DownhillValidator.Tier t = lim.v3Tier;
                if (big > t.bigDrops()) {
                    found.add(big + " drops of 2 blocks; " + t.id() + " has at most " + t.bigDrops());
                }
                if (descent > t.descent()) {
                    found.add("the track falls " + descent + " blocks from the start to the bottom; " + t.id()
                            + " falls at most " + t.descent());
                }
            }
            Double fall = course.fallY();
            if (fall == null || fall > minH - 1 + EPS) {
                found.add("the fall height " + fall + " isn't under the lowest ice (" + minH + ")");
            }
        }

        // ---- V9, V10, V12, V13 -----------------------------------------------------------------------

        void stand() {
            if (!lim.v4) {
                byte[][] low = new byte[sx][sz];
                for (int x = 0; x < sx; x++) {
                    for (int z = 0; z < sz; z++) {
                        low[x][z] = h[x][z] != NONE ? LoopValidatorV2.ICE : 0;
                    }
                }
                for (String p : LoopValidatorV2.standProblems(plan, low, stand, standX, standZ, floorY)) {
                    found.add(p);
                }
                return;
            }
            for (String p : standV4()) {
                found.add(p);
            }
        }

        /**
         * V9 (v2): the platform whole at {@link #standSpot}'s floor, its two-high glass rail unbroken,
         * nothing over its inner 5 × 5, its sign; at least {@code RaceStand.LANE_CLEARANCE} from any
         * track; and the view from it to the finish clear.
         */
        List<String> standV4() {
            List<String> out = new ArrayList<>();
            String floorBlock = Palette.id(RaceStand.FLOOR);
            String rail = Palette.id(RaceStand.RAIL_BLOCK);
            int r = RaceStand.SIZE / 2;
            if (floorY + RaceStand.RAIL > half.maxY()) {
                out.add("the stand's rail reaches over the half");
                return out;
            }
            int expected = 0;
            for (int x = standX - r; x <= standX + r; x++) {
                for (int z = standZ - r; z <= standZ + r; z++) {
                    expected++;
                    if (!floorBlock.equals(stand.get(DownhillValidator.standKey(x, floorY, z)))) {
                        out.add("the stand's platform has a hole at " + x + " " + floorY + " " + z);
                        return out;
                    }
                    boolean edge = RaceStand.onRail(x, z, standX, standZ);
                    for (int dy = 1; dy <= RaceStand.RAIL; dy++) {
                        String b = stand.get(DownhillValidator.standKey(x, floorY + dy, z));
                        if (edge && !rail.equals(b)) {
                            out.add("the stand's rail has a gap at " + x + " " + (floorY + dy) + " " + z);
                            return out;
                        }
                        if (!edge && b != null) {
                            out.add("no headroom on the stand at " + x + " " + (floorY + dy) + " " + z);
                            return out;
                        }
                        expected += edge ? 1 : 0;
                    }
                }
            }
            if (stand.size() != expected) {
                out.add("a stray block at the stand's height, off the stand");
                return out;
            }
            double nearest = Double.MAX_VALUE;
            for (int x = 0; x < sx; x++) {
                for (int z = 0; z < sz; z++) {
                    if (h[x][z] == NONE) {
                        continue;
                    }
                    int dx = Math.max(0, Math.abs(half.minX() + x - standX) - r);
                    int dz = Math.max(0, Math.abs(half.minZ() + z - standZ) - r);
                    nearest = Math.min(nearest, Math.hypot(dx, dz));
                }
            }
            if (nearest < RaceStand.LANE_CLEARANCE) {
                out.add("the stand is " + fmt(nearest) + " blocks from the ice; at least "
                        + fmt(RaceStand.LANE_CLEARANCE));
            }
            boolean sign = false;
            for (SignText s : plan.signs()) {
                sign |= s.y() == floorY + 1 && RaceStand.onPlatform(s.x(), s.z(), standX, standZ)
                        && !RaceStand.onRail(s.x(), s.z(), standX, standZ);
            }
            if (!sign) {
                out.add("the stand has no sign");
            }
            String blocked = sightLine();
            if (blocked != null) {
                out.add("the view from the stand to the finish is blocked at " + blocked
                        + ": only air and glass stand between, so watchers see the finishers come in");
            }
            return out;
        }

        /**
         * The first block that isn't air or glass on the segment from the platform's centre at floor
         * + {@link #EYE} to the finish mark's centre raised {@link #RIDER}, voxel by voxel
         * (Amanatides-Woo; where it passes exactly through an edge, both blocks beside it count), as
         * "x y z (id)"; {@code null} when the view is clear.
         */
        String sightLine() {
            Course.Mark f = course.finish();
            double ax = standX + 0.5;
            double ay = floorY + EYE;
            double az = standZ + 0.5;
            double bx = f.x();
            double by = f.y() + RIDER;
            double bz = f.z();
            double dx = bx - ax;
            double dy = by - ay;
            double dz = bz - az;
            int x = (int) Math.floor(ax);
            int y = (int) Math.floor(ay);
            int z = (int) Math.floor(az);
            int ex = (int) Math.floor(bx);
            int ey = (int) Math.floor(by);
            int ez = (int) Math.floor(bz);
            int stepX = dx > 0 ? 1 : dx < 0 ? -1 : 0;
            int stepY = dy > 0 ? 1 : dy < 0 ? -1 : 0;
            int stepZ = dz > 0 ? 1 : dz < 0 ? -1 : 0;
            double tMaxX = stepX == 0 ? Double.MAX_VALUE : ((stepX > 0 ? x + 1 - ax : ax - x) / Math.abs(dx));
            double tMaxY = stepY == 0 ? Double.MAX_VALUE : ((stepY > 0 ? y + 1 - ay : ay - y) / Math.abs(dy));
            double tMaxZ = stepZ == 0 ? Double.MAX_VALUE : ((stepZ > 0 ? z + 1 - az : az - z) / Math.abs(dz));
            double tDX = stepX == 0 ? Double.MAX_VALUE : 1 / Math.abs(dx);
            double tDY = stepY == 0 ? Double.MAX_VALUE : 1 / Math.abs(dy);
            double tDZ = stepZ == 0 ? Double.MAX_VALUE : 1 / Math.abs(dz);
            int guard = Math.abs(ex - x) + Math.abs(ey - y) + Math.abs(ez - z) + 4;
            for (int i = 0; i <= guard; i++) {
                String b = sightBlock(x, y, z);
                if (b != null) {
                    return x + " " + y + " " + z + " (" + b + ")";
                }
                if (x == ex && y == ey && z == ez) {
                    return null;
                }
                double t = Math.min(tMaxX, Math.min(tMaxY, tMaxZ));
                if (t > 1 + EPS) {
                    return null;
                }
                boolean sxStep = tMaxX - t <= 1e-12;
                boolean syStep = tMaxY - t <= 1e-12;
                boolean szStep = tMaxZ - t <= 1e-12;
                if ((sxStep ? 1 : 0) + (syStep ? 1 : 0) + (szStep ? 1 : 0) > 1) {
                    // through an edge or a corner: every block it touches there counts
                    for (int mask = 1; mask < 8; mask++) {
                        int cx = x + ((mask & 1) != 0 && sxStep ? stepX : 0);
                        int cy = y + ((mask & 2) != 0 && syStep ? stepY : 0);
                        int cz = z + ((mask & 4) != 0 && szStep ? stepZ : 0);
                        String c = sightBlock(cx, cy, cz);
                        if (c != null) {
                            return cx + " " + cy + " " + cz + " (" + c + ")";
                        }
                    }
                }
                if (sxStep) {
                    x += stepX;
                    tMaxX += tDX;
                }
                if (syStep) {
                    y += stepY;
                    tMaxY += tDY;
                }
                if (szStep) {
                    z += stepZ;
                    tMaxZ += tDZ;
                }
            }
            return null;
        }

        /** The block at world (x, y, z) that blocks the view, or {@code null} for air or glass. */
        String sightBlock(int x, int y, int z) {
            String s = stand.get(DownhillValidator.standKey(x, y, z));
            if (s != null) {
                return s.equals(Palette.GLASS) || s.endsWith("_stained_glass") ? null : s;
            }
            int k = entry(x - half.minX(), y, z - half.minZ());
            if (k < 0) {
                return null;
            }
            byte m = codeOf(k);
            return m == GLASS || m == ROOF_GLASS ? null : ids[st[k]];
        }

        void grid() {
            RaceGrid.Grid g = RaceGrid.plan(RaceGrid.path(course), course.start().y(), this::surfaceAt, RaceGrid.MAX_SPOTS);
            if (g.size() < RaceGrid.MAX_SPOTS || g.mode() != RaceGrid.Mode.DOUBLE) {
                found.add("the starting grid seats " + g.size() + " (" + g.mode().name().toLowerCase(Locale.ROOT)
                        + "): a race needs " + RaceGrid.MAX_SPOTS + " in rows of two");
            }
            Point spot = lim.v4 ? standSpot(half, course.finish().y()) : RaceStand.spot(half, course.start().y());
            if (!RaceStand.standable(this::surfaceAt, spot)) {
                found.add("the viewing stand can't be stood on");
            }
            Course.Spot s = course.start();
            double yaw = Math.toRadians(s.yaw());
            double fx = -Math.sin(yaw);
            double fz = Math.cos(yaw);
            int[][] toFinish = graph.steps(disk(course.finish(), "the finish").nearCells(), sx * sz);
            int ax = (int) Math.floor(s.x() + fx * DownhillValidator.FACING) - half.minX();
            int az = (int) Math.floor(s.z() + fz * DownhillValidator.FACING) - half.minZ();
            int bx = (int) Math.floor(s.x() - fx * DownhillValidator.FACING) - half.minX();
            int bz = (int) Math.floor(s.z() - fz * DownhillValidator.FACING) - half.minZ();
            int ahead = driveAt(ax, az) ? toFinish[ax][az] : -1;
            int behind = driveAt(bx, bz) ? toFinish[bx][bz] : -1;
            if (ahead < 0 || (behind >= 0 && ahead >= behind)) {
                found.add("the start doesn't face down the track toward the finish");
            }
        }

        void times() {
            int want = minSeconds(course);
            Integer has = course.minSeconds();
            if (has == null || has != want) {
                found.add("the shortest time is " + has + "s; its legs say " + want + "s");
            }
            if (trial.refMs() < want * 1000L + 1000) {
                found.add("the reference time " + trial.refMs() + "ms is under the shortest time and a second");
            }
        }

        void boxes() {
            for (Box k : plan.keepClear()) {
                if (!half.contains(k)) {
                    found.add("box", "a keep-clear box (" + k.describe() + ") reaches outside the half");
                }
            }
        }

        // ---- small things ----------------------------------------------------------------------------

        boolean inside(int x, int z) {
            return x >= 0 && z >= 0 && x < sx && z < sz;
        }

        boolean driveAt(int x, int z) {
            return inside(x, z) && h[x][z] != NONE;
        }

        int cell(double v, int min) {
            return (int) Math.floor(v) - min;
        }

        int wx(int x) {
            return x + half.minX();
        }

        int wz(int z) {
            return z + half.minZ();
        }
    }

    private static double sq(double v) {
        return v * v;
    }

    private static double squareDistance(int x, int z, double px, double pz) {
        double ex = Math.max(0, Math.max(x - px, px - (x + 1)));
        double ez = Math.max(0, Math.max(z - pz, pz - (z + 1)));
        return Math.hypot(ex, ez);
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }
}
