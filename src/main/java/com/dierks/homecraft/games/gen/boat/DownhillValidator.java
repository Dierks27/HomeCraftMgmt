package com.dierks.homecraft.games.gen.boat;

import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Palette;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlannedTrial;
import com.dierks.homecraft.games.gen.api.SignText;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.FairPlay;
import com.dierks.homecraft.games.trial.Laps;
import com.dierks.homecraft.games.trial.Point;
import com.dierks.homecraft.games.trial.RaceGrid;
import com.dierks.homecraft.games.trial.RaceSeat;
import com.dierks.homecraft.games.trial.RaceStand;
import com.dierks.homecraft.games.trial.TrialKind;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The independent check of a Mountain Run plan, Ice Boat algo 3 on (Course Variety §2.10, §4.1): it
 * reads the plan's blocks alone and proves, before a single block is set, that the course is
 * finishable, that no boat can leave it, that its checkpoints can't be skipped, that 12 boats fit on
 * its grid and that the stand is out of reach. {@link BoatValidator} sends it every plan of algo 3
 * or later; the planner runs it on every try, and {@code PlanCheck.generator} again before a build.
 *
 * <p><b>The proof rests on facts, not on driving.</b> F1: a boat never climbs, not even 0.125.
 * F2: nothing on the track lifts it (no slime, water, soul sand or honey). F3 and F4: it flies at
 * most {@link BoatEnvelope#flight L(d)} off a drop of d ({@link BoatEnvelope}). F5: a boat against a
 * wall can always turn. Each is measured live on Java and Bedrock (Gate 0). From them, the rules:
 * <ul>
 *   <li><b>V1 palette.</b> Only the Mountain Run's blocks (§2.7), with the state rules
 *       ({@link Palette#stateProblems}), leaves at vanilla's own distance
 *       ({@link Palette#leafProblems}); no slime or water (W5); easy is packed ice only. Every
 *       block has a class (drive, wall, riser, roof, scenery, stand, sign); anything else is a
 *       stray.</li>
 *   <li><b>V2 floors.</b> A drive cell is ice or sand with no ice or sand on it; one per column,
 *       so no track runs over another.</li>
 *   <li><b>V3 downhill only.</b> Side by side, drive cells step down at most the tier's drop (1 on
 *       easy, 2 on medium and hard); a diagonal step is square to its sides or walled. Every block
 *       of track is reachable from the start without climbing, and every deck ({@link DeckGraph}) a
 *       boat can reach reaches the finish.</li>
 *   <li><b>V4 containment.</b> W1: every column beside the track (8 ways) is solid wall from the
 *       lowest ice beside it to 2 above the highest surface. W2: round every drop's flight zone
 *       ({@link BoatEnvelope#zone Z(d)}: the lower track within Z of the lip, reachable from it
 *       without climbing), walls reach 2 above the lip's surface, and no other drop lies inside
 *       it (one flight never clears two drops, so no fall is ever more than one drop). W3: under
 *       the high side of a 2-block drop a riser fills the slot. W4: no open edge.</li>
 *   <li><b>V5 widths.</b> A path P wide (its cells at least (P - 1)/2 from every wall) and an
 *       all-ice line B wide run from the start to the finish, so sand is never forced; every block
 *       of track is within 3 of the wide path; every gap between walls is at least P, or 0; both
 *       ways round every island are wide.</li>
 *   <li><b>V6 checkpoints.</b> Each is at least 3 in radius, on flat open track in a single lane
 *       (ice where a reset puts the boat down, {@value #RESET_ROOM} round its middle; no roof, trees
 *       or island in reach), at least 3 from any drop and any flight zone, and a cut: the blocks
 *       wholly inside its sphere separate the start from the finish, with the one before on the
 *       start side. Its sphere never reaches another ring. At most one drop between two targets,
 *       and at most {@value #MAX_LEG} blocks across the ground; with no drop between, at most
 *       {@value #MAX_ALONG} along the track. A reset at each faces the next target within
 *       {@value #MAX_FACING} degrees of the lane. Stored once (a sprint).</li>
 *   <li><b>V7 headroom.</b> Four blocks of air over every drive cell; a cave roof exactly 5 over
 *       the ice; canopies 5 or more; nothing over a landing up to 2 above the lip's surface; scenery
 *       at least 2 columns from the track unless it is 5 or more over it.</li>
 *   <li><b>V8 falls.</b> The tier's drops: easy only 1s, medium at most two 2s, hard at most three,
 *       and the whole fall at most 5 (easy) or 7; the fall height under the lowest ice.</li>
 *   <li><b>V9 the stand.</b> The loop's own stand rule ({@code LoopValidatorV2.standProblems}),
 *       with the drive cells as its ice, and the scenery cap: nothing but the stand at or above
 *       its floor.</li>
 *   <li><b>V10 the grid.</b> The live {@link RaceGrid#plan} run on the plan's own blocks
 *       ({@link PlanSurface}) seats 12 in rows of two, the start faces down the track (the grid
 *       lines up behind it), and the stand can be stood on.</li>
 *   <li><b>V11 the finish.</b> On the lowest deck, a cut, at least Z(d) + 3 from the Final Drop, 12
 *       to 30 from the stand's edge, with a flat run-out whose sand paddock starts at least 14
 *       on.</li>
 *   <li><b>V12 times.</b> {@link #minSeconds} is the course's shortest time exactly, and the
 *       reference time is at least a second more.</li>
 *   <li><b>V13 sizes.</b> At most {@value #MAX_OPS} blocks and {@value #MAX_BOXES} keep-clear boxes,
 *       everything inside the half.</li>
 * </ul>
 * Where the spec leaves a reading open, this takes the one its numbers allow: medium's 2-block
 * drops (§2.4's table, over V3's "hard only") are allowed; a checkpoint's "3 from a zone" is its
 * centre's distance, which is what lets a leg across a 2-block drop stay within 60; the cut uses the
 * blocks wholly inside the sphere, so any boat crossing them is inside it; "at least 14 drive cells
 * follow the finish" is its sand paddock starting at least 14 past its centre. What only shapes the
 * fun is the planner's to keep and isn't checked here: the first lip's distance from the start, the
 * landing strips, the pieces' placement, checkpoints "normally 20-40" apart (the "never more" half,
 * 60 along a leg with no drop, is proven) and exactly one between two drops (this proves the safety
 * half: never two drops in one leg), and keep-clear boxes covering the runs (only their count, the
 * half and the stand are checked, their one use).
 *
 * <p>Pure: no Bukkit. Milliseconds a plan. It never throws: a plan it can't read is refused.
 */
public final class DownhillValidator {

    /** The first boat planner version this judges: the Mountain Run. */
    public static final int FIRST_ALGO = 3;
    /** The most blocks a Mountain Run plan may place (V13). */
    public static final int MAX_OPS = 30_000;
    /** The most keep-clear boxes a plan may carry (V13). */
    public static final int MAX_BOXES = 64;
    /** The most checkpoints a course may have. */
    public static final int MAX_CHECKPOINTS = Course.MAX_CHECKPOINTS;
    /** Blocks of air over every drive cell: ice + 1 to ice + 4 (V7). */
    public static final int HEADROOM = 4;
    /** A cave roof sits exactly this far over the ice, and a canopy at least (V7). */
    public static final int ROOF = 5;
    /** A wall's top face stands this far over the surface beside it: its top block at ice + 2 (W1). */
    public static final int WALL_ABOVE = 2;
    /** The least radius of a checkpoint or finish (B-T5: the leg bound never voids a clean run). */
    public static final double MIN_RADIUS = 3;
    /** The farthest two targets may be apart, across the ground (§2.6). */
    public static final double MAX_LEG = 60;
    /**
     * The longest a leg with no drop in it may run along the track (§2.6: "normally every 20-40 along
     * s ... never more"; review CV gate), so a reset never sends a boat far back: the shortest way
     * from one mark to the next through the track's own columns (its drive cells at the leg's level
     * and the islands standing in it), sixteen ways ({@code along}). A bend's inside is shorter than
     * its centreline, so this reads a little under the planner's own measure along the centreline,
     * which it holds to {@link BoatPlanner#FLAT_LEG} (over thousands of legs this read at most 0.3
     * over it).
     */
    public static final double MAX_ALONG = 60;
    /**
     * From every checkpoint, the way a reset faces ({@link Course#resetYaw}: toward the next target,
     * as {@code TimeTrials.backTo} turns the boat) is within this many degrees of the lane's direction
     * there, read off the blocks as the line from the track just before the checkpoint's sphere to the
     * track just past it (review CV gate: a reset faced 146 degrees off, back up the track).
     */
    public static final double MAX_FACING = 60;
    /**
     * A reset puts the boat down within this of a checkpoint's middle ({@link RaceSeat#SIDEWAYS} to a
     * side, and a block for the hull): every drive cell there is ice. Sand farther out, a bend's
     * run-off or kerb at the rim of its sphere, is allowed: the sphere spans it, so a boat going round
     * on the sand still meets it, and V5b keeps an all-ice line through it.
     */
    public static final double RESET_ROOM = RaceSeat.SIDEWAYS + 1;
    /** A checkpoint's centre keeps at least this far from a drop and from a flight zone (§2.6). */
    public static final double DROP_CLEAR = 3;
    /** Blocks of ice after the finish before its sand paddock (§2.8). */
    public static final double RUN_OUT = 14;
    /** The finish is this far (across the ground) from the stand's platform, at least and at most (V11). */
    public static final double FINISH_NEAR = 12;
    public static final double FINISH_FAR = 30;
    /** Every block of track is within this of the wide path (V5c: no deep side pockets). */
    public static final double POCKET = 3;
    /** Scenery keeps this many columns inside the half. */
    public static final int SCENERY_INSET = 2;
    /** A checkpoint's sphere must not reach another ring: none within its radius + this (V6). */
    public static final double RING_REACH = 1;
    /** ...unless the track joins it within this many radii along the ground. */
    public static final int RING_STEPS = 3;
    /** The start faces down the track: the track this far ahead of it is nearer the finish than behind. */
    public static final double FACING = 3;

    /** The three tiers, as the proof sees them (§2.4). */
    public enum Tier {
        EASY("easy", 5, 5, 1, 0, 5, false),
        MEDIUM("medium", 4, 4, 2, 2, 7, true),
        HARD("hard", 4, 3, 2, 3, 7, true);

        private final String id;
        private final int narrowest;
        private final int iceLine;
        private final int maxDrop;
        private final int bigDrops;
        private final int descent;
        private final boolean blueIce;

        Tier(String id, int narrowest, int iceLine, int maxDrop, int bigDrops, int descent, boolean blueIce) {
            this.id = id;
            this.narrowest = narrowest;
            this.iceLine = iceLine;
            this.maxDrop = maxDrop;
            this.bigDrops = bigDrops;
            this.descent = descent;
            this.blueIce = blueIce;
        }

        public String id() {
            return id;
        }

        /** P: the narrowest passage, and the least gap between walls. */
        public int narrowest() {
            return narrowest;
        }

        /** B: the width of the always-open ice line past every sand piece. */
        public int iceLine() {
            return iceLine;
        }

        /** The biggest drop: 1 on easy, 2 otherwise. */
        public int maxDrop() {
            return maxDrop;
        }

        /** The most 2-block drops a track may have. */
        public int bigDrops() {
            return bigDrops;
        }

        /** The most the whole track may fall, start to bottom. */
        public int descent() {
            return descent;
        }

        /** Whether blue ice may be used (not on easy). */
        public boolean blueIce() {
            return blueIce;
        }

        /** The tier called {@code word} (any case), or {@code null}. */
        public static Tier of(String word) {
            if (word == null) {
                return null;
            }
            String w = word.trim().toLowerCase(Locale.ROOT);
            for (Tier t : values()) {
                if (t.id.equals(w)) {
                    return t;
                }
            }
            return null;
        }
    }

    private DownhillValidator() {
    }

    /** What is wrong with {@code plan} as a {@code tier} Mountain Run; empty when nothing is. */
    public static List<String> problems(Plan plan, String tier) {
        if (plan == null) {
            return List.of("there is no plan");
        }
        Tier t = Tier.of(tier);
        if (t == null) {
            return List.of("'" + tier + "' isn't an Ice Boat tier");
        }
        if (!(plan.course() instanceof PlannedTrial trial) || trial.course().kind() != TrialKind.BOAT) {
            return List.of("the plan isn't a boat course");
        }
        if (trial.course().start() == null || trial.course().finish() == null || plan.half() == null) {
            return List.of("the course has no start or no finish");
        }
        try {
            return new Survey(plan, t, trial).run();
        } catch (RuntimeException e) {
            // a refusal, never a pass: a plan this can't read is never built
            return List.of("the Mountain Run check couldn't read this plan (" + e + ")");
        }
    }

    /**
     * The shortest believable time of a course, whole seconds (§2.8, V12): the leg bound
     * {@code FairPlay.tooFast} judges each leg by, {@code max(0, |c_i - c_(i-1)| - r_(i-1) - r_i)},
     * summed over the legs from the start (radius {@link FairPlay#START_RADIUS}) through every
     * checkpoint to the finish, over the boat's cap of 75 blocks a second, rounded down. It can
     * never void a clean run (theorem B-T5). The planner stores exactly this.
     */
    public static int minSeconds(Course course) {
        if (course == null || course.start() == null) {
            return 0;
        }
        double sum = 0;
        Point prev = course.start().point();
        double prevRadius = FairPlay.START_RADIUS;
        for (Course.Mark m : course.targets()) {
            sum += Math.max(0, prev.distance(m.center()) - prevRadius - m.radius());
            prev = m.center();
            prevRadius = m.radius();
        }
        return (int) Math.floor(sum / TrialKind.BOAT.maxSpeed());
    }

    // ---- the blocks -------------------------------------------------------------------------------

    /** What each block of the half is, as one byte (0 is air). */
    static final byte AIR = 0;
    static final byte PACKED = 1;
    static final byte BLUE = 2;
    static final byte SAND = 3;
    static final byte SPRUCE = 4;
    static final byte GLASS = 5;
    static final byte ARROW = 6;
    static final byte YELLOW = 7;
    static final byte LIGHT_BLUE = 8;
    static final byte LIME = 9;
    static final byte GOLD = 10;
    static final byte LANTERN = 11;
    static final byte LOG = 12;
    static final byte ROOF_GLASS = 13;
    static final byte LEAVES = 14;
    static final byte MOSS = 15;
    static final byte WHITE = 16;

    /** The Mountain Run's blocks (§2.7), by id. White concrete is the stand's alone. */
    static final Map<String, Byte> CODES = Map.ofEntries(Map.entry("minecraft:packed_ice", PACKED),
            Map.entry("minecraft:blue_ice", BLUE), Map.entry("minecraft:smooth_sandstone", SAND),
            Map.entry("minecraft:stripped_spruce_wood", SPRUCE), Map.entry("minecraft:glass", GLASS),
            Map.entry("minecraft:magenta_glazed_terracotta", ARROW), Map.entry("minecraft:yellow_concrete", YELLOW),
            Map.entry("minecraft:light_blue_concrete", LIGHT_BLUE), Map.entry("minecraft:lime_concrete", LIME),
            Map.entry("minecraft:gold_block", GOLD), Map.entry("minecraft:sea_lantern", LANTERN),
            Map.entry("minecraft:oak_log", LOG), Map.entry("minecraft:birch_log", LOG),
            Map.entry("minecraft:cherry_log", LOG), Map.entry("minecraft:light_blue_stained_glass", ROOF_GLASS),
            Map.entry("minecraft:oak_leaves", LEAVES), Map.entry("minecraft:birch_leaves", LEAVES),
            Map.entry("minecraft:cherry_leaves", LEAVES), Map.entry("minecraft:moss_block", MOSS),
            Map.entry("minecraft:white_concrete", WHITE));

    /** Ice or sand: what a boat drives on. */
    static boolean drive(byte m) {
        return m == PACKED || m == BLUE || m == SAND;
    }

    /** What a wall, an obstacle, a riser or a roof may be built of: any full block but drive, leaves and the stand's. */
    static boolean wall(byte m) {
        return m >= SPRUCE && m <= MOSS && m != LEAVES;
    }

    /** What may stand away from the track (and over it, 5 up): moss, trees, and wood for cliffs. */
    static boolean scenery(byte m) {
        return m == MOSS || m == LOG || m == LEAVES || m == SPRUCE;
    }

    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    /** The moves the along-the-track measure takes: sixteen ways (a knight's too), so a curve reads nearly its true length. */
    private static final int[][] MOVES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1},
            {2, 1}, {2, -1}, {-2, 1}, {-2, -1}, {1, 2}, {1, -2}, {-1, 2}, {-1, -2}};
    private static final double EPS = 1e-9;

    /** One drop: its lip cells (the high side's edge), its height, and how far it falls. */
    private record Lip(List<int[]> cells, int upper, int drop) {

        int[] first() {
            return cells.get(0);
        }
    }

    /** A checkpoint's or the finish's footprint on the track. */
    private record Disk(Course.Mark mark, String name, double px, double pz, int cx, int cz, boolean[][] near,
                        List<int[]> nearCells, boolean[][] inside) {
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
        final Tier tier;
        final PlannedTrial trial;
        final Course course;
        final Box half;
        final int sx;
        final int sy;
        final int sz;
        final Found found = new Found();

        byte[] codes;
        String[] ids;
        boolean blue;
        byte[] grid;
        int floorY;
        int top;
        int standX;
        int standZ;
        final Map<Long, String> stand = new HashMap<>();
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

        Survey(Plan plan, Tier tier, PlannedTrial trial) {
            this.plan = plan;
            this.tier = tier;
            this.trial = trial;
            this.course = trial.course();
            this.half = plan.half();
            this.sx = half.sizeX();
            this.sy = half.sizeY();
            this.sz = half.sizeZ();
        }

        List<String> run() {
            if ((long) sx * sy * sz > 4_000_000L) {
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

        // ---- V1, V13: the palette, the size, inside the half ---------------------------------------

        boolean palette() {
            List<String> palette = plan.palette();
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
                } else if (!CODES.containsKey(id)) {
                    found.add("'" + p + "' isn't an Ice Boat block");
                } else {
                    codes[i] = CODES.get(id);
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
            if (plan.ops().size() > MAX_OPS) {
                found.add(plan.ops().size() + " blocks is more than " + MAX_OPS);
            }
            if (plan.keepClear().size() > MAX_BOXES) {
                found.add(plan.keepClear().size() + " keep-clear boxes is more than " + MAX_BOXES);
            }
            return found.empty();
        }

        boolean blocks() {
            grid = new byte[sx * sy * sz];
            Course.Spot start = course.start();
            floorY = RaceStand.floorY(start.y());
            top = Math.min(half.maxY(), floorY - 1);
            standX = RaceStand.centreX(half);
            standZ = RaceStand.centreZ(half);
            for (BlockOp op : plan.ops()) {
                if (op.state() >= codes.length) {
                    found.add("a block at " + op.x() + " " + op.y() + " " + op.z() + " has no palette entry");
                    continue;
                }
                if (!half.contains(op.x(), op.y(), op.z())) {
                    found.add("outside", "a block at " + op.x() + " " + op.y() + " " + op.z() + " is outside the half");
                    continue;
                }
                int i = index(op.x() - half.minX(), op.y() - half.minY(), op.z() - half.minZ());
                if (grid[i] != AIR) {
                    found.add("twice", "two blocks at " + op.x() + " " + op.y() + " " + op.z());
                    continue;
                }
                grid[i] = codes[op.state()];
                if (op.y() >= floorY) {
                    // the scenery cap (V9): at the stand's heights only the stand, over its own 7 x 7
                    if (RaceStand.onPlatform(op.x(), op.z(), standX, standZ) && op.y() <= floorY + RaceStand.RAIL) {
                        stand.put(standKey(op.x(), op.y(), op.z()), ids[op.state()]);
                    } else {
                        found.add("cap", "a block at " + op.x() + " " + op.y() + " " + op.z()
                                + " is at or over the stand's floor (y " + floorY + "): only the stand stands there");
                    }
                }
            }
            for (SignText s : plan.signs()) {
                if (!half.contains(s.x(), s.y(), s.z())) {
                    found.add("outside", "a sign at " + s.x() + " " + s.y() + " " + s.z() + " is outside the half");
                }
            }
            return found.empty();
        }

        // ---- V2: one floor a column ----------------------------------------------------------------

        boolean floors() {
            h = new int[sx][sz];
            for (int[] row : h) {
                java.util.Arrays.fill(row, DeckGraph.NONE);
            }
            boolean stacked = false;
            for (int x = 0; x < sx; x++) {
                for (int z = 0; z < sz; z++) {
                    for (int y = half.minY(); y <= top; y++) {
                        if (drive(at(x, y, z)) && (y == top || !drive(at(x, y + 1, z)))) {
                            if (h[x][z] != DeckGraph.NONE) {
                                stacked = true;
                                found.add("stacked", "two floors in one column at " + wx(x) + " " + wz(z) + " (ice at "
                                        + h[x][z] + " and " + y + "): no track runs over another");
                            }
                            h[x][z] = y;
                        }
                    }
                    if (h[x][z] != DeckGraph.NONE) {
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

        /** Whether local column (x, z) has a drive cell whose surface is at {@code y}. */
        boolean onSurface(int x, int z, double y) {
            return inside(x, z) && h[x][z] != DeckGraph.NONE && Math.abs(y - (h[x][z] + 1)) < 1e-6;
        }

        // ---- V3: downhill only -----------------------------------------------------------------------

        void steps() {
            for (int x = 0; x < sx; x++) {
                for (int z = 0; z < sz; z++) {
                    if (h[x][z] == DeckGraph.NONE) {
                        continue;
                    }
                    for (int[] s : new int[][]{{1, 0}, {0, 1}}) {
                        int nx = x + s[0];
                        int nz = z + s[1];
                        if (driveAt(nx, nz) && Math.abs(h[nx][nz] - h[x][z]) > tier.maxDrop()) {
                            int hi = Math.max(h[nx][nz], h[x][z]);
                            found.add("step", "the track drops " + Math.abs(h[nx][nz] - h[x][z]) + " blocks at "
                                    + wx(x) + " " + (hi + 1) + " " + wz(z) + "; " + tier.id() + " drops at most "
                                    + tier.maxDrop());
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
                            continue; // walled both sides: they only touch at a corner no hull passes
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
                    if (h[x][z] == DeckGraph.NONE) {
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
                    if (h[x][z] != DeckGraph.NONE && !reach[x][z]) {
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
                java.util.Arrays.fill(row, DeckGraph.NONE);
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
                                // §2.6: no drop inside another's zone, or one flight clears both
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
                    if (h[x][z] != DeckGraph.NONE) {
                        driveColumn(x, z);
                    } else if (nearTrack(x, z)) {
                        wallColumn(x, z);
                    } else {
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
            for (int y = half.minY(); y <= top; y++) {
                byte m = at(x, y, z);
                if (m == AIR || y == ice) {
                    continue;
                }
                String where = wx(x) + " " + y + " " + wz(z);
                if (y < ice) {
                    if (m == LEAVES) {
                        stray(x, y, z);
                    }
                } else if (y <= ice + HEADROOM) {
                    found.add("headroom", "something in the headroom over the track at " + where
                            + ": the 4 blocks over the ice stay clear");
                } else if (zoneLip[x][z] != DeckGraph.NONE && y <= zoneLip[x][z] + 1 + WALL_ABOVE) {
                    found.add("landing", "something over the landing at " + where
                            + ": nothing within 2 over the top of the drop");
                } else if (m == ROOF_GLASS) {
                    if (y != ice + ROOF) {
                        found.add("roof", "the cave roof at " + where + " isn't 5 over the ice");
                    }
                } else if (m != MOSS && m != LOG && m != LEAVES) {
                    found.add("over", "a block over the track at " + where
                            + ": over the ice only a cave roof, trees and moss, 5 up or more");
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
                    if (zoneLip[nx][nz] != DeckGraph.NONE && zoneLip[nx][nz] + WALL_ABOVE > zoneNeed) {
                        zoneNeed = zoneLip[nx][nz] + WALL_ABOVE;
                        zoneFrom = new int[]{nx, nz};
                    }
                }
            }
            int need = Math.min(top, hiIce + WALL_ABOVE);
            String col = wx(x) + " " + wz(z);
            boolean any = false;
            for (int y = lo; y <= need; y++) {
                any |= at(x, y, z) != AIR;
            }
            if (!any) {
                found.add("open", "an open edge beside the track at " + col + " (y " + (lo + 1)
                        + "): a wall stands beside every block of track");
            } else {
                for (int y = lo; y <= need; y++) {
                    if (!wall(at(x, y, z))) {
                        found.add("wall", "the wall at " + col + " isn't solid at y " + y
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
                        found.add("flight", "the wall at " + col + " is too low beside the landing at "
                                + wx(zoneFrom[0]) + " " + (h[zoneFrom[0]][zoneFrom[1]] + 1) + " " + wz(zoneFrom[1])
                                + ": a boat flying off the drop needs it up to y " + want);
                        break;
                    }
                }
                covered = want;
            }
            int run = lo;
            while (run <= top && wall(at(x, run, z))) {
                run++;
            }
            int from = Math.max(run, covered + 1);
            for (int y = half.minY(); y <= top; y++) {
                byte m = at(x, y, z);
                if (m == AIR || (y >= lo && y < from)) {
                    continue;
                }
                if (y < lo) {
                    if (m == LEAVES) {
                        stray(x, y, z);
                    }
                } else if (m == ROOF_GLASS || scenery(m)) {
                    if (y < hiIce + ROOF) {
                        found.add("close", "scenery at " + wx(x) + " " + y + " " + wz(z)
                                + " is too close to the track: 2 columns away, or 5 over the ice");
                    } else if (atEdge(x, z)) {
                        found.add("inset", "scenery at " + wx(x) + " " + y + " " + wz(z) + " is within "
                                + SCENERY_INSET + " of the area's edge");
                    }
                } else {
                    stray(x, y, z);
                }
            }
        }

        boolean atEdge(int x, int z) {
            return x < SCENERY_INSET || z < SCENERY_INSET || x >= sx - SCENERY_INSET || z >= sz - SCENERY_INSET;
        }

        void farColumn(int x, int z) {
            boolean edge = atEdge(x, z);
            for (int y = half.minY(); y <= top; y++) {
                byte m = at(x, y, z);
                if (m == AIR) {
                    continue;
                }
                if (!scenery(m)) {
                    stray(x, y, z);
                } else if (edge) {
                    found.add("inset", "scenery at " + wx(x) + " " + y + " " + wz(z) + " is within "
                            + SCENERY_INSET + " of the area's edge");
                }
            }
        }

        void stray(int x, int y, int z) {
            byte m = at(x, y, z);
            String name = "a block";
            for (Map.Entry<String, Byte> e : CODES.entrySet()) {
                if (e.getValue() == m) {
                    name = e.getKey();
                    break;
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
                if (s.y() >= floorY) {
                    boolean standSign = s.y() == floorY + 1 && RaceStand.onPlatform(s.x(), s.z(), standX, standZ)
                            && !RaceStand.onRail(s.x(), s.z(), standX, standZ);
                    if (!standSign) {
                        found.add("cap", "a sign at " + where + " is at or over the stand's floor (y " + floorY
                                + "): only the stand stands there");
                    }
                    continue;
                }
                int x = s.x() - half.minX();
                int y = s.y();
                int z = s.z() - half.minZ();
                if (at(x, y, z) != AIR) {
                    found.add("signblock", "a sign inside a block at " + where);
                } else if (h[x][z] != DeckGraph.NONE) {
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
            // a wall sign faces away from the block it hangs on
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
            double coreClear = (tier.narrowest() - 1) / 2.0;
            double iceClear = (tier.iceLine() - 1) / 2.0;
            core = new boolean[sx][sz];
            boolean[][] iceCore = new boolean[sx][sz];
            for (int x = 0; x < sx; x++) {
                for (int z = 0; z < sz; z++) {
                    if (h[x][z] == DeckGraph.NONE) {
                        continue;
                    }
                    core[x][z] = clearance(x, z, false) >= coreClear - EPS;
                    iceCore[x][z] = at(x, h[x][z], z) != SAND && clearance(x, z, true) >= iceClear - EPS;
                }
            }
            Disk finish = disk(course.finish(), "the finish");
            if (!core[startX][startZ] || !reaches(core, finish)) {
                found.add("no path " + tier.narrowest() + " wide runs from the start to the finish");
            }
            if (!iceCore[startX][startZ] || !reaches(iceCore, finish)) {
                found.add("the sand can't be avoided: no all-ice line " + tier.iceLine()
                        + " wide runs from the start to the finish");
            }
            int r = (int) Math.ceil(POCKET);
            for (int x = 0; x < sx; x++) {
                for (int z = 0; z < sz; z++) {
                    if (h[x][z] == DeckGraph.NONE || core[x][z]) {
                        continue;
                    }
                    boolean close = false;
                    for (int dx = -r; dx <= r && !close; dx++) {
                        for (int dz = -r; dz <= r; dz++) {
                            if (inside(x + dx, z + dz) && core[x + dx][z + dz]
                                    && dx * dx + dz * dz <= POCKET * POCKET + EPS) {
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

        /**
         * From the middle of cell (x, z), the distance to the nearest column that isn't track
         * (with {@code iceOnly}: that isn't ice), looked for within 3; 3.5 when there is none that
         * close, which is more than any tier asks.
         */
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

        /** Whether a boat can go from the start to the finish through {@code ok} cells, never climbing. */
        boolean reaches(boolean[][] ok, Disk finish) {
            boolean[][] seen = new boolean[sx][sz];
            ArrayDeque<int[]> queue = new ArrayDeque<>();
            seen[startX][startZ] = true;
            queue.add(new int[]{startX, startZ});
            while (!queue.isEmpty()) {
                int[] c = queue.poll();
                if (finish.near()[c[0]][c[1]]) {
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

        /**
         * V5d: along x, z and both diagonals, every run of track between two walls is at least P
         * long, unless the two walls touch round it (a corner, not a gap).
         */
        void gaps() {
            int[][] dirs = {{1, 0}, {0, 1}, {1, 1}, {1, -1}};
            for (int[] d : dirs) {
                double unit = d[0] != 0 && d[1] != 0 ? Math.sqrt(2) : 1;
                int longest = (int) Math.ceil(tier.narrowest() / unit);
                for (int x = 0; x < sx; x++) {
                    for (int z = 0; z < sz; z++) {
                        if (h[x][z] == DeckGraph.NONE || passes(x - d[0], z - d[1], d)) {
                            continue; // not the first cell of a run
                        }
                        int n = 1;
                        while (n < longest && passes(x + (n - 1) * d[0], z + (n - 1) * d[1], d)) {
                            n++;
                        }
                        if (n >= longest || n * unit >= tier.narrowest() - EPS) {
                            continue;
                        }
                        // the run is n long: bounded before (x, z) and after its last cell
                        int ax = x - d[0];
                        int az = z - d[1];
                        int lx = x + (n - 1) * d[0];
                        int lz = z + (n - 1) * d[1];
                        int[] b = end(lx, lz, d);
                        int[] a = driveAt(ax, az) ? new int[]{x - d[0], z} : new int[]{ax, az};
                        if (!touching(a, b)) {
                            found.add("gap", "a gap of " + fmt(n * unit) + " between walls at " + wx(x) + " "
                                    + (h[x][z] + 1) + " " + wz(z) + "; the narrowest passage is "
                                    + tier.narrowest());
                        }
                    }
                }
            }
        }

        /** Whether the run goes on from track cell (x, z) to the next along {@code d}. */
        boolean passes(int x, int z, int[] d) {
            int nx = x + d[0];
            int nz = z + d[1];
            if (!driveAt(x, z) || !driveAt(nx, nz)) {
                return false;
            }
            // corner to corner: at least one side open, or the walls there touch
            return d[0] == 0 || d[1] == 0 || driveAt(nx, z) || driveAt(x, nz);
        }

        /** The wall column that ends a run whose last cell is (x, z). */
        int[] end(int x, int z, int[] d) {
            int nx = x + d[0];
            int nz = z + d[1];
            return driveAt(nx, nz) ? new int[]{nx, z} : new int[]{nx, nz};
        }

        /** Whether wall columns a and b join (8 ways) through walls near them: a corner, not a gap. */
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

        /**
         * The islands (8-joined columns that aren't track and don't reach the area's edge: a split's
         * tree island, a forest trunk), and for each, both ways round it wide: the wide middle of the
         * track rings it, so the columns that aren't wide middle can't get from it to the edge.
         */
        void islands() {
            island = new boolean[sx][sz];
            int[][] part = new int[sx][sz];
            int next = 0;
            ArrayDeque<int[]> queue = new ArrayDeque<>();
            for (int x = 0; x < sx; x++) {
                for (int z = 0; z < sz; z++) {
                    if (h[x][z] != DeckGraph.NONE || part[x][z] != 0) {
                        continue;
                    }
                    next++;
                    List<int[]> cells = new ArrayList<>();
                    boolean edge = false;
                    part[x][z] = next;
                    queue.add(new int[]{x, z});
                    while (!queue.isEmpty()) {
                        int[] c = queue.poll();
                        cells.add(c);
                        for (int dx = -1; dx <= 1; dx++) {
                            for (int dz = -1; dz <= 1; dz++) {
                                int nx = c[0] + dx;
                                int nz = c[1] + dz;
                                if (!inside(nx, nz)) {
                                    edge = true;
                                } else if (h[nx][nz] == DeckGraph.NONE && part[nx][nz] == 0) {
                                    part[nx][nz] = next;
                                    queue.add(new int[]{nx, nz});
                                }
                            }
                        }
                    }
                    if (edge) {
                        continue;
                    }
                    for (int[] c : cells) {
                        island[c[0]][c[1]] = true;
                    }
                    if (ringed(cells)) {
                        continue;
                    }
                    int[] c = cells.get(0);
                    found.add("island", "a way round the island at " + wx(c[0]) + " " + wz(c[1])
                            + " is narrower than " + tier.narrowest() + ": both ways round are wide");
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
            boolean[][] near = new boolean[sx][sz];
            boolean[][] in = new boolean[sx][sz];
            List<int[]> cells = new ArrayList<>();
            int x0 = Math.max(0, (int) Math.floor(px - r) - 1);
            int x1 = Math.min(sx - 1, (int) Math.floor(px + r) + 1);
            int z0 = Math.max(0, (int) Math.floor(pz - r) - 1);
            int z1 = Math.min(sz - 1, (int) Math.floor(pz + r) + 1);
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    if (h[x][z] == DeckGraph.NONE) {
                        continue;
                    }
                    if (sq(x + 0.5 - px) + sq(z + 0.5 - pz) <= r * r + EPS) {
                        near[x][z] = true;
                        cells.add(new int[]{x, z});
                    }
                    double fx = Math.max(Math.abs(x - px), Math.abs(x + 1 - px));
                    double fz = Math.max(Math.abs(z - pz), Math.abs(z + 1 - pz));
                    in[x][z] = fx * fx + fz * fz <= r * r + EPS;
                }
            }
            return new Disk(m, name, px, pz, (int) Math.floor(px), (int) Math.floor(pz), near, cells, in);
        }

        void targets() {
            List<Course.Mark> cps = course.checkpoints();
            if (cps.size() > MAX_CHECKPOINTS) {
                found.add(cps.size() + " checkpoints is more than " + MAX_CHECKPOINTS);
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
                return; // a mark off the track: its cut means nothing
            }
            // the cuts, in order
            int[] lipLeg = new int[lips.size()];
            boolean[][] after = null;
            for (int i = 0; i <= n; i++) {
                Disk d = disks.get(i);
                if (d.inside()[startX][startZ]) {
                    found.add("the start is inside " + d.name());
                    continue;
                }
                boolean[][] reach = graph.reach(startX, startZ, d.inside());
                if (i < n) {
                    boolean round = false;
                    for (int[] c : finish.nearCells()) {
                        round |= reach[c[0]][c[1]];
                    }
                    if (round) {
                        found.add(d.name() + " doesn't span the track: a boat can go round it");
                    } else {
                        facing(i, d, reach);
                    }
                }
                if (i > 0) {
                    Disk prev = disks.get(i - 1);
                    boolean overlap = false;
                    boolean before = true;
                    for (int[] c : prev.nearCells()) {
                        overlap |= d.near()[c[0]][c[1]];
                        before &= d.inside()[c[0]][c[1]] || reach[c[0]][c[1]];
                    }
                    if (overlap) {
                        found.add(prev.name() + " and " + d.name() + " overlap");
                    } else if (!before) {
                        found.add(d.name() + " comes before " + prev.name());
                    }
                }
                for (int k = 0; k < lips.size(); k++) {
                    int[] u = lips.get(k).first();
                    if (!reach[u[0]][u[1]] && !d.inside()[u[0]][u[1]]) {
                        lipLeg[k]++;
                    }
                }
                if (i == n) {
                    after = new boolean[sx][sz];
                    for (int x = 0; x < sx; x++) {
                        for (int z = 0; z < sz; z++) {
                            after[x][z] = h[x][z] != DeckGraph.NONE && !reach[x][z] && !d.inside()[x][z];
                        }
                    }
                }
            }
            // at most one drop a leg, and every leg at most MAX_LEG across the ground
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
                if (apart > MAX_LEG + EPS) {
                    found.add(a + " and " + b + " are " + fmt(apart) + " apart; at most " + fmt(MAX_LEG));
                }
                if (perLeg[leg] == 0) {
                    double along = along(ax - half.minX(), az - half.minZ(), disks.get(leg).px(), disks.get(leg).pz());
                    if (along == Double.MAX_VALUE) {
                        found.add("no way along the track at one level from " + a + " to " + b
                                + ", though no drop is between them");
                    } else if (along > MAX_ALONG + EPS) {
                        found.add(a + " and " + b + " are " + fmt(along) + " apart along the track with no drop"
                                + " between; at most " + fmt(MAX_ALONG) + ", so a reset never sends a boat far back");
                    }
                }
            }
            finish(finish, after);
        }

        /**
         * A reset at checkpoint {@code i} faces on down the track: the yaw a race turns the boat to
         * ({@link Course#resetYaw}) is within {@link #MAX_FACING} degrees of the lane's direction,
         * the line from the middle of the track just before the checkpoint's sphere (reached from the
         * start without crossing it, {@code reach}) to the middle of the track just past it.
         */
        void facing(int i, Disk d, boolean[][] reach) {
            double[] in = new double[3];
            double[] out = new double[3];
            int span = (int) Math.ceil(d.mark().radius()) + 2;
            for (int x = Math.max(0, d.cx() - span); x <= Math.min(sx - 1, d.cx() + span); x++) {
                for (int z = Math.max(0, d.cz() - span); z <= Math.min(sz - 1, d.cz() + span); z++) {
                    if (h[x][z] == DeckGraph.NONE || d.inside()[x][z] || at(x, h[x][z], z) == SAND) {
                        continue;
                    }
                    boolean edge = false;
                    for (int[] s : SIDES) {
                        edge |= inside(x + s[0], z + s[1]) && d.inside()[x + s[0]][z + s[1]];
                    }
                    if (!edge) {
                        continue;
                    }
                    double[] side = reach[x][z] ? in : out;
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
            if (off > MAX_FACING + EPS) {
                found.add(d.name() + "'s reset faces " + fmt(off) + " degrees off the lane (toward the next target);"
                        + " at most " + fmt(MAX_FACING));
            }
        }

        /**
         * How far it is along the track from (ax, az) to (bx, bz) (half columns) at the same level, at
         * least: the shortest way from the middle of the one's column to the middle of the other's
         * through that level's drive cells and the islands standing in the lane, sixteen ways (a move
         * only over open columns), less how far each point is from its column's middle;
         * {@code Double.MAX_VALUE} when there is no such way.
         */
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
            double[] dist = new double[sx * sz];
            java.util.Arrays.fill(dist, Double.MAX_VALUE);
            java.util.PriorityQueue<double[]> queue = new java.util.PriorityQueue<>((p, q) -> Double.compare(p[0], q[0]));
            dist[x0 * sz + z0] = 0;
            queue.add(new double[]{0, x0, z0});
            while (!queue.isEmpty()) {
                double[] q = queue.poll();
                int x = (int) q[1];
                int z = (int) q[2];
                if (q[0] > dist[x * sz + z]) {
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
                    if (nd < dist[k]) {
                        dist[k] = nd;
                        queue.add(new double[]{nd, x + m[0], z + m[1]});
                    }
                }
            }
            return Double.MAX_VALUE;
        }

        /** Whether the straight move from (x, z) by (dx, dz) crosses only open columns (the cells its line runs through). */
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

        /** Whether column (x, z) is the track's own at {@code level}: a drive cell there, or an island in the lane. */
        private boolean open(int x, int z, int level) {
            return inside(x, z) && (h[x][z] == level || (h[x][z] == DeckGraph.NONE && island[x][z]));
        }

        /** One mark's own rules; false when it isn't on the track at all. */
        boolean mark(Disk d, boolean checkpoint) {
            Course.Mark m = d.mark();
            if (m.radius() < MIN_RADIUS - EPS) {
                found.add(d.name() + "'s radius " + fmt(m.radius()) + " is under " + fmt(MIN_RADIUS));
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
                        && sq(c[0] + 0.5 - d.px()) + sq(c[1] + 0.5 - d.pz()) <= RESET_ROOM * RESET_ROOM + EPS;
                for (int y = h[c[0]][c[1]] + 1; y <= top && !covered; y++) {
                    covered = at(c[0], y, c[1]) != AIR;
                }
            }
            if (!flat) {
                found.add(d.name() + " isn't on flat track");
            }
            // the sphere never reaches another ring: every track block within r + 1 is near it along the track
            int limit = (int) Math.ceil(RING_STEPS * m.radius());
            int[][] steps = graph.steps(d.nearCells(), limit);
            double reach = m.radius() + RING_REACH;
            outer:
            for (int x = Math.max(0, (int) Math.floor(d.px() - reach) - 1);
                 x <= Math.min(sx - 1, (int) Math.floor(d.px() + reach) + 1); x++) {
                for (int z = Math.max(0, (int) Math.floor(d.pz() - reach) - 1);
                     z <= Math.min(sz - 1, (int) Math.floor(d.pz() + reach) + 1); z++) {
                    if (h[x][z] != DeckGraph.NONE && steps[x][z] < 0
                            && sq(x + 0.5 - d.px()) + sq(z + 0.5 - d.pz()) <= reach * reach + EPS) {
                        found.add(d.name() + " reaches another part of the track at " + wx(x) + " " + (h[x][z] + 1)
                                + " " + wz(z));
                        break outer;
                    }
                }
            }
            if (!checkpoint) {
                return true;
            }
            if (sand) {
                found.add(d.name() + " is on sand: a reset puts the boat down on ice, " + fmt(RESET_ROOM)
                        + " round its middle");
            }
            if (covered) {
                found.add(d.name() + " is under a roof or trees: a checkpoint is in the open");
            }
            // single lane: no island (a split's, a forest's trunk) within its reach
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
            // at least 3 from any drop's edge, and from any flight zone
            double nearestLip = Double.MAX_VALUE;
            int[] lipAt = null;
            double nearestZone = Double.MAX_VALUE;
            int[] zoneAt = null;
            int span = (int) Math.ceil(DROP_CLEAR) + 1;
            for (int x = Math.max(0, d.cx() - span); x <= Math.min(sx - 1, d.cx() + span); x++) {
                for (int z = Math.max(0, d.cz() - span); z <= Math.min(sz - 1, d.cz() + span); z++) {
                    double dist = Math.hypot(x + 0.5 - d.px(), z + 0.5 - d.pz());
                    if (lipDrop[x][z] > 0 && dist < nearestLip) {
                        nearestLip = dist;
                        lipAt = new int[]{x, z};
                    }
                    if (zoneLip[x][z] != DeckGraph.NONE && dist < nearestZone) {
                        nearestZone = dist;
                        zoneAt = new int[]{x, z};
                    }
                }
            }
            if (lipAt != null && nearestLip < DROP_CLEAR - EPS) {
                found.add(d.name() + " is " + fmt(nearestLip) + " from the drop at " + wx(lipAt[0]) + " "
                        + (h[lipAt[0]][lipAt[1]] + 1) + " " + wz(lipAt[1]) + "; at least " + fmt(DROP_CLEAR));
            }
            if (zoneAt != null && nearestZone < DROP_CLEAR - EPS) {
                found.add(d.name() + " is in a flight zone: " + fmt(nearestZone) + " from the landing at "
                        + wx(zoneAt[0]) + " " + (h[zoneAt[0]][zoneAt[1]] + 1) + " " + wz(zoneAt[1]) + "; at least "
                        + fmt(DROP_CLEAR));
            }
            return true;
        }

        /** V11: the finish's own rules, with {@code after} the track past it (the run-out). */
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
                double want = BoatEnvelope.zone(lip.drop()) + DROP_CLEAR;
                for (int[] c : lip.cells()) {
                    double dist = Math.hypot(c[0] + 0.5 - d.px(), c[1] + 0.5 - d.pz());
                    if (dist < want - EPS) {
                        found.add("the finish is " + fmt(dist) + " from the Final Drop at " + wx(c[0]) + " "
                                + (lip.upper() + 1) + " " + wz(c[1]) + "; at least " + fmt(want));
                        break;
                    }
                }
            }
            int r = RaceStand.SIZE / 2;
            double ex = Math.max(0, Math.max((standX - r) - f.x(), f.x() - (standX + r + 1)));
            double ez = Math.max(0, Math.max((standZ - r) - f.z(), f.z() - (standZ + r + 1)));
            double toStand = Math.hypot(ex, ez);
            if (toStand < FINISH_NEAR - EPS || toStand > FINISH_FAR + EPS) {
                found.add("the finish is " + fmt(toStand) + " from the viewing stand; " + fmt(FINISH_NEAR) + " to "
                        + fmt(FINISH_FAR) + ", so finishers watch the rest come in");
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
            } else if (nearestSand < RUN_OUT - EPS) {
                found.add("sand " + fmt(nearestSand) + " after the finish: the run-out is at least "
                        + fmt(RUN_OUT) + " of ice");
            }
        }

        // ---- V8: falls -------------------------------------------------------------------------------

        void falls() {
            if (blue && !tier.blueIce()) {
                found.add("blue ice on an easy track: easy races on packed ice");
            }
            int big = 0;
            for (Lip lip : lips) {
                big += lip.drop() == 2 ? 1 : 0;
            }
            if (big > tier.bigDrops()) {
                found.add(big + " drops of 2 blocks; " + tier.id() + " has at most " + tier.bigDrops());
            }
            int descent = h[startX][startZ] - minH;
            if (descent > tier.descent()) {
                found.add("the track falls " + descent + " blocks from the start to the bottom; " + tier.id()
                        + " falls at most " + tier.descent());
            }
            Double fall = course.fallY();
            if (fall == null || fall > minH - 1 + EPS) {
                found.add("the fall height " + fall + " isn't under the lowest ice (" + minH + ")");
            }
        }

        // ---- V9, V10, V12, V13 -----------------------------------------------------------------------

        void stand() {
            byte[][] low = new byte[sx][sz];
            for (int x = 0; x < sx; x++) {
                for (int z = 0; z < sz; z++) {
                    low[x][z] = h[x][z] != DeckGraph.NONE ? LoopValidatorV2.ICE : 0;
                }
            }
            for (String p : LoopValidatorV2.standProblems(plan, low, stand, standX, standZ, floorY)) {
                found.add(p);
            }
        }

        void grid() {
            PlanSurface surface = new PlanSurface(plan);
            RaceGrid.Grid g = RaceGrid.plan(RaceGrid.path(course), course.start().y(), surface, RaceGrid.MAX_SPOTS);
            if (g.size() < RaceGrid.MAX_SPOTS || g.mode() != RaceGrid.Mode.DOUBLE) {
                found.add("the starting grid seats " + g.size() + " (" + g.mode().name().toLowerCase(Locale.ROOT)
                        + "): a race needs " + RaceGrid.MAX_SPOTS + " in rows of two");
            }
            if (!RaceStand.standable(surface, RaceStand.spot(half, course.start().y()))) {
                found.add("the viewing stand can't be stood on");
            }
            // the grid lines up behind the start's facing, so the start faces down the track
            Course.Spot s = course.start();
            double yaw = Math.toRadians(s.yaw());
            double fx = -Math.sin(yaw);
            double fz = Math.cos(yaw);
            int[][] toFinish = graph.steps(disk(course.finish(), "the finish").nearCells(), sx * sz);
            int ax = (int) Math.floor(s.x() + fx * FACING) - half.minX();
            int az = (int) Math.floor(s.z() + fz * FACING) - half.minZ();
            int bx = (int) Math.floor(s.x() - fx * FACING) - half.minX();
            int bz = (int) Math.floor(s.z() - fz * FACING) - half.minZ();
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

        // ---- the grid of blocks ----------------------------------------------------------------------

        int index(int x, int y, int z) {
            return (x * sz + z) * sy + y;
        }

        /** The block at local column (x, z), world height y: air outside the half. */
        byte at(int x, int y, int z) {
            int ly = y - half.minY();
            if (!inside(x, z) || ly < 0 || ly >= sy) {
                return AIR;
            }
            return grid[index(x, ly, z)];
        }

        boolean inside(int x, int z) {
            return x >= 0 && z >= 0 && x < sx && z < sz;
        }

        boolean driveAt(int x, int z) {
            return inside(x, z) && h[x][z] != DeckGraph.NONE;
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

    /** The loop's stand key ({@code LoopValidatorV2}'s, frozen), so its stand rule reads this map. */
    static long standKey(int x, int y, int z) {
        return ((long) (y & 0xFFF) << 52) ^ ((long) (x & 0x3FFFFFF) << 26) ^ (z & 0x3FFFFFFL);
    }

    private static double sq(double v) {
        return v * v;
    }

    /** From (px, pz) to the nearest point of column (x, z)'s square. */
    private static double squareDistance(int x, int z, double px, double pz) {
        double ex = Math.max(0, Math.max(x - px, px - (x + 1)));
        double ez = Math.max(0, Math.max(z - pz, pz - (z + 1)));
        return Math.hypot(ex, ez);
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }
}
