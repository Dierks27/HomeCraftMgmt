package com.dierks.homecraft.games.gen.rings;

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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The planner for Sky Rings, the elytra course (GEN-SPEC §4.2, Sky Rings v3 "Sky Loop"): a start
 * tower at the south end of a 512-wide square of sky, then a sweeping loop of 20 to 26 rings, 80
 * to 130 blocks apart, that spirals inward to a gold ring, with a fixed stack of rockets for the
 * whole run and none at the rings.
 *
 * <p><b>Three kinds of leg.</b> After ring 1 every leg is a <em>glide</em> (a rainbow ring well
 * below the last, 1 in 7 to 1 in 4.5, flown with no rocket), a <em>climb</em> (a white ring 25 to
 * 55 blocks <em>above</em> the last: one rocket, fired early, nose up) or a <em>level leg</em> (a
 * ring 0 to 3 below, 90 to 100 ahead: a fast flyer makes it with nothing, a slow one burns one).
 * The order is a fixed template ({@link #profile}): three glides, then a climb after every block of
 * three glides, a glide to finish, and the tier's level legs dropped into some of the blocks
 * ({@code G G F G}) by the seed's dice. Three glides before every climb is what keeps every climb
 * under the roof: they drop at least 36, more than any climb, so the next white ring always fits.
 *
 * <p><b>The rocket proof is greedy.</b> {@code need[k]} is the number of rocket legs from ring k to
 * the finish ({@code need[0]} from the tower). Every autopilot flight that starts at ring k flies
 * with exactly {@code need[k]} rockets, fires exactly one on each climb and level leg and never on
 * a glide ({@link ElytraSim.Target#rocketLeg}), and must still pass every ring: so a glide only
 * makeable with a rocket is refused, and the stack handed out, {@code need[0] + 2}, always leaves
 * two to spare for speed or mistakes. A reset at ring k tops a player up to {@code need[k]}, never
 * above, so nobody is stranded and nobody farms.
 *
 * <p><b>The floor.</b> A glide's drop leaves room under it for the rest of the course: every later
 * glide's least drop and level leg's most, less every later climb's least rise ({@code reserve}),
 * so rings always stay above the half's floor + 16 whatever the dice; a glide's length is drawn
 * short enough for that too, so the altitude never refuses a try.
 *
 * <p><b>The oval.</b> The course runs in lanes along x or z and corners the way it winds when the
 * room ahead runs short of what a corner needs (and the course still has further to go than that):
 * at 30 degrees a ring with the 45-degree facing snap an easy corner is 180, 210, 225 (a diagonal
 * leg, so the facing swaps), 255, 270: four rings and some 300 blocks of sideways swing, so the
 * course is a sweeping oval that spirals inward, not a square. {@link Search#room} stops within
 * {@link #LANE_GAP} of any older leg, so each lane runs at least 80 from the last; near the end the
 * finale runs straight, so the gold ring lands on an inner lane in sight of the platform.
 *
 * <p><b>Why it is flyable.</b> Vanilla's best glide is about 10:1 and every glide drops at least
 * 1 in 7. The proof is the {@link ElytraSim} autopilot: a candidate ring is flown by the pilots that
 * started at the last {@link #PILOT_WINDOW} rings (at three speeds with the heading a little off,
 * from a standstill, and, after a climb or a level leg, three slow sinking exits) and by the two
 * flights off the tower, all with the rockets the rest of the course needs; on a level leg a
 * 1.6-blocks-a-tick pilot with no rockets proves "fast or rocket". {@link RingsValidator} then flies
 * every pilot through the whole remaining course before a plan is returned.
 *
 * <p><b>How a layout grows.</b> Ring 1 is 20 blocks ahead of the tower's diving board and 8
 * below it. Each further leg ({@code fork("leg:" + k + ":" + n)}) draws a turn, a length and a
 * height from its kind's ranges, steering by the room ahead. A candidate is refused if its ring
 * plus R + 8 doesn't fit the half, it is within 48 of another ring, a flight through a ring would
 * be more than 45 degrees off the ring's facing, a keep-clear tube of R + 2 round a leg that isn't
 * its own meets a frame, it comes within 8 of the tower, within 80 across the ground of an older
 * lane, or the autopilot can't fly it: 20 tries a leg, then a fresh start
 * ({@code fork("restart:" + n)}), up to 20.
 *
 * <p><b>Facing.</b> Every ring faces x or z, whichever is nearer the flight (a pixel circle on a
 * diagonal looks broken), so a course can only swing from running north-south to running
 * east-west through a leg that runs exactly diagonally, flown 45 degrees off both facings.
 *
 * <p>Pure: no Bukkit, no clock, no {@code java.util.Random}. {@link RingsValidator} checks every
 * plan before it is returned.
 */
public final class RingsPlanner implements Planner {

    /** Its version; bump it whenever what it makes for a seed changes (golden hashes pin three seeds). */
    public static final int ALGO = 3;

    /** The kinds of leg after ring 1: the letter is what the course's {@code legs} text carries. */
    public enum LegKind {
        /** A rainbow ring well below the last: no rocket. */
        GLIDE('G'),
        /** A white ring above the last: one rocket, nose up. */
        LIFT('L'),
        /** A ring dead ahead: fast or one rocket. */
        FLAT('F');

        private final char letter;

        LegKind(char letter) {
            this.letter = letter;
        }

        /** Its letter in a course's {@code legs} text. */
        public char letter() {
            return letter;
        }

        /** Whether a pilot spends a rocket on it. */
        public boolean rocket() {
            return this != GLIDE;
        }

        /** The kind with this letter, or {@code null}. */
        public static LegKind of(char letter) {
            for (LegKind k : values()) {
                if (k.letter == letter) {
                    return k;
                }
            }
            return null;
        }
    }

    /** The three tiers (the table in §4.2, Sky Rings v3). */
    public enum Level {
        EASY("easy", 20, 8, 80, 120, 45, 7, 4.5, 45, 4, 2, 25, 45),
        MEDIUM("medium", 24, 7, 90, 130, 45, 7, 4.5, 45, 5, 2, 30, 50),
        HARD("hard", 26, 6, 96, 128, 45, 7, 4.5, 45, 5, 4, 35, 55);

        private final String id;
        private final int rings;
        private final int radius;
        private final int legMin;
        private final int legMax;
        private final int maxTurn;
        private final double slow;
        private final double fast;
        private final double maxOffNormal;
        private final int lifts;
        private final int flats;
        private final int upMin;
        private final int upMax;

        Level(String id, int rings, int radius, int legMin, int legMax, int maxTurn, double slow, double fast,
              double maxOffNormal, int lifts, int flats, int upMin, int upMax) {
            this.id = id;
            this.rings = rings;
            this.radius = radius;
            this.legMin = legMin;
            this.legMax = legMax;
            this.maxTurn = maxTurn;
            this.slow = slow;
            this.fast = fast;
            this.maxOffNormal = maxOffNormal;
            this.lifts = lifts;
            this.flats = flats;
            this.upMin = upMin;
            this.upMax = upMax;
        }

        /** The config word ({@code easy}). */
        public String id() {
            return id;
        }

        /** Rings, the gold finish ring included. */
        public int rings() {
            return rings;
        }

        /** The frame's radius R, to block centres: the hole is 2R − 1 blocks wide. */
        public int radius() {
            return radius;
        }

        /** A checkpoint's radius: R − 1, so only a flight through the hole counts. */
        public double checkpointRadius() {
            return radius - 1;
        }

        /** How close to a ring's centre the autopilot must pass: the checkpoint's radius less half a block. */
        public double pass() {
            return radius - 1.5;
        }

        /** The shortest glide leg, across the ground. */
        public int legMin() {
            return legMin;
        }

        /** The longest glide leg, across the ground. */
        public int legMax() {
            return legMax;
        }

        /** The most the heading may change at a ring, in degrees (half that into a climb). */
        public int maxTurn() {
            return maxTurn;
        }

        /** A glide drops at least its length over this: 10:1 glide over the tier's margin. */
        public double slow() {
            return slow;
        }

        /** ...and at most its length over this. */
        public double fast() {
            return fast;
        }

        /** The most a flight through a ring may be off the ring's facing, in degrees. */
        public double maxOffNormal() {
            return maxOffNormal;
        }

        /** Climbs (white rings) after ring 1. */
        public int lifts() {
            return lifts;
        }

        /** Level legs after ring 1. */
        public int flats() {
            return flats;
        }

        /** Glides after ring 1: the rest of the legs. */
        public int glides() {
            return rings - 1 - lifts - flats;
        }

        /** A climb's ring is at least this much higher than the last... */
        public int upMin() {
            return upMin;
        }

        /** ...and at most this much. */
        public int upMax() {
            return upMax;
        }

        /** The rockets the autopilot proves the course with: one a climb, one a level leg. */
        public int need() {
            return lifts + flats;
        }

        /** The stack handed out: {@link #need} plus {@link #MARGIN} to spare. */
        public int rockets() {
            return need() + MARGIN;
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

    /** Vanilla's best glide, forward over down. */
    public static final double GLIDE = 10;
    /** Every tier's drops keep at least this margin under the glide line. */
    public static final double GLIDE_MARGIN = 1.4;
    /** The platform's top is this far under the half's roof. */
    public static final int TOWER_TOP = 16;
    /**
     * The tower stands this far in from the side of the half it is on (R + 8 + {@link #SIDE}, so the first
     * lane sits exactly {@link #SIDE} inside the ring-centre inset and the side nudge never fires), and 8 from
     * the south end.
     */
    public static final int TOWER_INSET = 40;
    public static final int TOWER_SOUTH = 8;
    /** Ring 1 is this far ahead of the diving board's end... */
    public static final int FIRST_AHEAD = 20;
    /** ...and its centre about this far under the platform's top. */
    public static final int FIRST_BELOW = 8;
    /** A ring and R + this all round fit inside the half. */
    public static final int EDGE = 8;
    /** Ring centres are at least this far apart: a returning lane never passes beside an old ring. */
    public static final double RING_GAP = 48;
    /** A keep-clear tube round every leg that isn't a ring's own: R + this. */
    public static final int TUBE = 2;
    /** Legs other than the first keep this far from the tower. */
    public static final double TOWER_GAP = 8;
    /** Rings stay this far above the half's floor. */
    public static final int FLOOR = 16;
    /** Within this of a side of the half, a course leans back toward the middle. */
    public static final double SIDE = 24;
    /** Lanes keep this far apart across the ground: a ring and an older leg, or a leg and an older ring. */
    public static final double LANE_GAP = 80;
    /** How far {@link Search#room} looks ahead. */
    public static final double ROOM_WALK = 600;
    /** A corner's legs run the tier's shortest length, plus up to this: a 90-degree swing then fits the square. */
    public static final int CORNER_SLACK = 4;
    /** Turns are whole multiples of this, in degrees. */
    public static final int HEADING_STEP = 5;
    /** Tries per leg, and fresh starts. */
    public static final int TRIES_PER_LEG = 20;
    public static final int RESTARTS = 20;
    /** The most work a plan can take (tries and simulated flight ticks). A budget of this never runs out. */
    public static final long WORK_BUDGET = 40_000_000L;
    /** A candidate ring is flown by the pilots that started at the last this many rings (the validator flies all). */
    public static final int PILOT_WINDOW = 3;
    /** Speeds (blocks a tick) and heading errors (degrees) the autopilot enters every ring at. */
    static final double[] SPEEDS = {0.8, 1.2, 1.6};
    static final double[] ERRORS = {-3, 0, 3};
    /** The slow, sinking exits a climb or a level leg leaves a player with: more pilots after one. */
    static final double SLOW_SPEED = 0.6;
    static final double SLOW_VY = -0.5;
    /** The speed-line pilot on a level leg: this fast, no rockets, so "fast or rocket" is true. */
    static final double EXPERT_SPEED = 1.6;
    /** A climb runs this far across the ground... */
    public static final int LIFT_LEG_MIN = 90;
    public static final int LIFT_LEG_MAX = 120;
    /** ...a level leg this far, its ring 0 to this much below the last (never above). */
    public static final int FLAT_MIN = 90;
    public static final int FLAT_MAX = 100;
    public static final int FLAT_DROP = 3;
    /** Rockets handed out over what the autopilot proves the course needs. */
    public static final int MARGIN = 2;
    /** Reference time = this times the autopilot's reference lap: humans dive steeper and pitch up less. */
    public static final double REF_FACTOR = 0.8;
    /** Ticks a player falls off the board before opening the wings. */
    static final int[] OPEN_AFTER = {3, 10};
    /** From the platform's centre, a flight off the board starts this far out: the board's end and a hitbox. */
    static final double LAUNCH = 5.5 + 0.31;
    /** A walker's speed stepping off the board, blocks a tick. */
    static final double WALK = 0.2159;
    /** The fastest believable speed along the course, blocks a second. */
    public static final double MIN_SPEED = 60;
    /** The smallest half the planner lays a loop in: legacy 128 x 320 boxes are refused plainly. */
    public static final int MIN_SIZE_XZ = 384;
    public static final int MIN_SIZE_Y = 160;

    @Override
    public String id() {
        return Slots.RINGS;
    }

    @Override
    public int algo() {
        return ALGO;
    }

    @Override
    public Plan plan(PlanInput in) throws GenFailed {
        return build(in).plan();
    }

    /** A plan and what it took, for tests. */
    record Build(Plan plan, int restarts, int validatorRejections, long work) {
    }

    Build build(PlanInput in) throws GenFailed {
        Level level = Level.of(in.slot().normalise(in.tierOrMix()));
        if (level == null) {
            throw new GenFailed("'" + in.tierOrMix() + "' isn't a Sky Rings tier (easy, medium or hard)");
        }
        Box half = in.half();
        if (half.sizeX() < MIN_SIZE_XZ || half.sizeZ() < MIN_SIZE_XZ || half.sizeY() < MIN_SIZE_Y) {
            throw new GenFailed("the area " + half.describe() + " is too small for Sky Rings (at least " + MIN_SIZE_XZ
                    + " x " + MIN_SIZE_Y + " x " + MIN_SIZE_XZ + ")");
        }
        GenRandom root = new GenRandom(in.seed());
        long work = 0;
        int rejected = 0;
        for (int restart = 0; restart < RESTARTS; restart++) {
            in.checkCancelled();
            Search s = new Search(level, half, root.fork("restart:" + restart));
            boolean ok = s.run();
            work += s.work;
            if (in.workBudget() > 0 && work > in.workBudget()) {
                throw new GenFailed("the Sky Rings plan went over its work budget (" + in.workBudget() + ")");
            }
            if (!ok) {
                continue;
            }
            Plan plan = s.toPlan(in, work, restart);
            if (RingsValidator.problems(plan, level.id()).isEmpty()) {
                return new Build(plan, restart, rejected, work);
            }
            rejected++;
        }
        throw new GenFailed("no " + level.id() + " Sky Rings layout found for seed " + GenSeed.shortHex(in.seed())
                + " in " + RESTARTS + " starts");
    }

    /** The layout the tag names, made again from its seed (the tier in {@code in} first, then the others). */
    @Override
    public Plan rederive(PlanInput in, GenTag tag) throws GenFailed {
        if (tag == null) {
            return plan(in);
        }
        if (tag.algo() != ALGO) {
            throw new GenFailed("this layout was made by rings planner v" + tag.algo() + ", this is v" + ALGO);
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

    // ---- the profile and the rocket count the planner and the validator share ---------------------

    /**
     * The kinds of the legs after ring 1, in order (one per checkpoint after the first): three glides,
     * a climb after every block of three glides, a glide to finish, and the tier's level legs put into
     * {@code flats} of the blocks, each as {@code G G F G}, chosen by {@code rng}.
     */
    static LegKind[] profile(Level level, GenRandom rng) {
        int blocks = level.lifts();
        boolean[] flat = new boolean[blocks];
        int left = level.flats();
        for (int b = 0; b < blocks; b++) {
            if (rng.nextInt(blocks - b) < left) {
                flat[b] = true;
                left--;
            }
        }
        List<LegKind> out = new ArrayList<>();
        for (int b = 0; b < blocks; b++) {
            out.add(LegKind.GLIDE);
            out.add(LegKind.GLIDE);
            if (flat[b]) {
                out.add(LegKind.FLAT);
            }
            out.add(LegKind.GLIDE);
            out.add(LegKind.LIFT);
        }
        out.add(LegKind.GLIDE);
        return out.toArray(new LegKind[0]);
    }

    /** The kinds as the course's {@code legs} text. */
    static String legs(LegKind[] kinds) {
        StringBuilder sb = new StringBuilder();
        for (LegKind k : kinds) {
            sb.append(k.letter());
        }
        return sb.toString();
    }

    /** The kinds a course's {@code legs} text names, or {@code null} when a letter is none of them. */
    static LegKind[] kinds(String legs) {
        LegKind[] out = new LegKind[legs.length()];
        for (int i = 0; i < out.length; i++) {
            out[i] = LegKind.of(legs.charAt(i));
            if (out[i] == null) {
                return null;
            }
        }
        return out;
    }

    /**
     * How many rockets the rest of the course needs: {@code need[0]} from the tower, {@code need[k]} from
     * ring k (1-based; the finish's 0 is left out), one for every climb or level leg still to come.
     * {@code kinds[j]} is the leg into ring j + 2 (1-based), so {@code need[k]} counts {@code kinds[k - 1..]}.
     */
    static int[] need(LegKind[] kinds) {
        int[] need = new int[kinds.length + 1];
        for (int k = kinds.length; k >= 1; k--) {
            need[k] = (k < kinds.length ? need[k + 1] : 0) + (kinds[k - 1].rocket() ? 1 : 0);
        }
        need[0] = need[1];
        return need;
    }

    /** The {@code need} list as the course stores it. */
    static List<Integer> needList(int[] need) {
        List<Integer> out = new ArrayList<>(need.length);
        for (int n : need) {
            out.add(n);
        }
        return out;
    }

    // ---- the geometry the planner and the validator share -----------------------------------------

    /** A ring: its centre block, the heading of the leg into it (Minecraft yaw), its facing and its blocks. */
    record Ring(int cx, int cy, int cz, double yawIn, RingShape.Normal normal, List<int[]> voxels) {

        double x() {
            return cx + 0.5;
        }

        double y() {
            return cy + 0.5;
        }

        double z() {
            return cz + 0.5;
        }

        double[] centre() {
            return new double[]{x(), y(), z()};
        }
    }

    /** The tower: the platform's centre block (tx, tz), its top (feet level) and the half's floor. */
    record Tower(int tx, int tz, int top, int minY) {

        /** The 5 x 5 platform. */
        Box platform() {
            return new Box(tx - 2, top - 1, tz - 2, tx + 2, top - 1, tz + 2);
        }

        /** The 1 x 3 diving board, toward -z (north). */
        Box board() {
            return new Box(tx, top - 1, tz - 5, tx, top - 1, tz - 3);
        }

        /** The 1 x 1 pillar under the platform's middle, down to the half's floor. */
        Box pillar() {
            return new Box(tx, minY, tz, tx, top - 2, tz);
        }

        /** Where a flight off the board starts: the player's feet just past its end. */
        double[] launch() {
            return new double[]{tx + 0.5, top, tz + 0.5 - LAUNCH};
        }
    }

    /** The unit direction across the ground for a Minecraft yaw (0 = south, 90 = west). */
    static double[] dir(double yaw) {
        double r = StrictMath.toRadians(yaw);
        return new double[]{-StrictMath.sin(r), StrictMath.cos(r)};
    }

    /** The yaw of a direction across the ground, 0 to 360. */
    static double yaw(double dx, double dz) {
        double y = StrictMath.toDegrees(StrictMath.atan2(-dx, dz));
        return y < 0 ? y + 360 : y;
    }

    /** The signed turn from yaw a to yaw b, in (−180, 180]. */
    static double turn(double a, double b) {
        double d = ((b - a) % 360 + 540) % 360 - 180;
        return d == -180 ? 180 : d;
    }

    /** The distance from point p to the segment a–b. */
    static double segmentDistance(double[] a, double[] b, double px, double py, double pz) {
        double dx = b[0] - a[0];
        double dy = b[1] - a[1];
        double dz = b[2] - a[2];
        double len2 = dx * dx + dy * dy + dz * dz;
        double t = len2 == 0 ? 0 : ((px - a[0]) * dx + (py - a[1]) * dy + (pz - a[2]) * dz) / len2;
        t = Math.max(0, Math.min(1, t));
        double qx = a[0] + t * dx - px;
        double qy = a[1] + t * dy - py;
        double qz = a[2] + t * dz - pz;
        return Math.sqrt(qx * qx + qy * qy + qz * qz);
    }

    /** The distance across the ground from (px, pz) to the segment a–b (heights ignored). */
    static double groundDistance(double[] a, double[] b, double px, double pz) {
        double dx = b[0] - a[0];
        double dz = b[2] - a[2];
        double len2 = dx * dx + dz * dz;
        double t = len2 == 0 ? 0 : ((px - a[0]) * dx + (pz - a[2]) * dz) / len2;
        t = Math.max(0, Math.min(1, t));
        double qx = a[0] + t * dx - px;
        double qz = a[2] + t * dz - pz;
        return Math.sqrt(qx * qx + qz * qz);
    }

    /** The distance from the segment a–b to a box of blocks, sampled every half block along the segment. */
    static double boxDistance(double[] a, double[] b, Box box) {
        double len = Math.sqrt(Math.pow(b[0] - a[0], 2) + Math.pow(b[1] - a[1], 2) + Math.pow(b[2] - a[2], 2));
        int steps = Math.max(1, (int) Math.ceil(len * 2));
        double best = Double.MAX_VALUE;
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            double px = a[0] + (b[0] - a[0]) * t;
            double py = a[1] + (b[1] - a[1]) * t;
            double pz = a[2] + (b[2] - a[2]) * t;
            double ex = Math.max(0, Math.max(box.minX() - px, px - (box.maxX() + 1)));
            double ey = Math.max(0, Math.max(box.minY() - py, py - (box.maxY() + 1)));
            double ez = Math.max(0, Math.max(box.minZ() - pz, pz - (box.maxZ() + 1)));
            best = Math.min(best, Math.sqrt(ex * ex + ey * ey + ez * ez));
        }
        return best;
    }

    /** The distance across the ground from (px, pz) to a box's footprint. */
    static double groundBoxDistance(double px, double pz, Box box) {
        double ex = Math.max(0, Math.max(box.minX() - px, px - (box.maxX() + 1)));
        double ez = Math.max(0, Math.max(box.minZ() - pz, pz - (box.maxZ() + 1)));
        return Math.sqrt(ex * ex + ez * ez);
    }

    /**
     * The solid blocks a flight can hit: the rings' frames and the tower's boxes, with a cheap
     * distance test first so most ticks look nothing up.
     */
    static final class Obstacles implements ElytraSim.Solid {
        private final Set<Long> blocks = new HashSet<>();
        private final List<double[]> spheres = new ArrayList<>();
        private final List<Box> boxes = new ArrayList<>();

        void ring(int cx, int cy, int cz, int radius, List<int[]> voxels) {
            for (int[] v : voxels) {
                blocks.add(key(v[0], v[1], v[2]));
            }
            double r = radius + 2.5;
            spheres.add(new double[]{cx + 0.5, cy + 0.5, cz + 0.5, r * r});
        }

        void box(Box b) {
            boxes.add(b);
        }

        Obstacles copy() {
            Obstacles o = new Obstacles();
            o.blocks.addAll(blocks);
            o.spheres.addAll(spheres);
            o.boxes.addAll(boxes);
            return o;
        }

        @Override
        public boolean near(double x, double y, double z) {
            for (Box b : boxes) {
                if (x >= b.minX() - 2 && x < b.maxX() + 3 && y >= b.minY() - 2 && y < b.maxY() + 3
                        && z >= b.minZ() - 2 && z < b.maxZ() + 3) {
                    return true;
                }
            }
            for (double[] s : spheres) {
                double dx = x - s[0];
                double dy = y - s[1];
                double dz = z - s[2];
                if (dx * dx + dy * dy + dz * dz < s[3]) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean at(int x, int y, int z) {
            for (Box b : boxes) {
                if (b.contains(x, y, z)) {
                    return true;
                }
            }
            return blocks.contains(key(x, y, z));
        }
    }

    /** A block position as a well-spread, one-to-one long. */
    static long key(int x, int y, int z) {
        long v = ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
        v = (v ^ (v >>> 30)) * 0xBF58476D1CE4E5B9L;
        v = (v ^ (v >>> 27)) * 0x94D049BB133111EBL;
        return v ^ (v >>> 31);
    }

    /**
     * The autopilot's target for a ring: its centre and the tier's pass distance, the way through
     * it (along its facing, the way the leg into it runs), unless the flight ends there, and whether
     * the leg into it is a rocket leg.
     */
    static ElytraSim.Target target(Ring r, boolean last, Level level, boolean rocketLeg) {
        double[] u = dir(r.yawIn());
        double nx = r.normal() == RingShape.Normal.X ? Math.signum(u[0]) : 0;
        double nz = r.normal() == RingShape.Normal.Z ? Math.signum(u[1]) : 0;
        return new ElytraSim.Target(r.x(), r.y(), r.z(), level.pass(), nx, nz, last, rocketLeg);
    }

    /**
     * The autopilot's flights that start at a ring, each with {@code rockets} (what the rest of the
     * course needs from there): one per speed and heading error, entering along the leg into the
     * ring; one from a standstill (a fall-reset puts the player's feet at the checkpoint's centre with
     * no speed); and, when the leg into the ring ({@code kindIn}) was a climb or a level leg, three
     * slow, sinking movers, the exits those legs leave a player with.
     */
    static List<ElytraSim.Pilot> startsAt(double x, double y, double z, double yawIn, int rockets, LegKind kindIn) {
        List<ElytraSim.Pilot> out = new ArrayList<>();
        for (double speed : SPEEDS) {
            for (double error : ERRORS) {
                double[] d = dir(yawIn + error);
                out.add(new ElytraSim.Pilot(new ElytraSim.Flyer(x, y, z, d[0] * speed, 0, d[1] * speed), rockets));
            }
        }
        out.add(new ElytraSim.Pilot(new ElytraSim.Flyer(x, y, z, 0, 0, 0), rockets));
        if (kindIn != null && kindIn.rocket()) {
            for (double error : ERRORS) {
                double[] d = dir(yawIn + error);
                out.add(new ElytraSim.Pilot(new ElytraSim.Flyer(x, y, z, d[0] * SLOW_SPEED, SLOW_VY, d[1] * SLOW_SPEED),
                        rockets));
            }
        }
        return out;
    }

    /** The names of {@link #startsAt}'s flights, in its order, for the validator's words. */
    static List<String> startNames(LegKind kindIn) {
        List<String> names = new ArrayList<>();
        for (double speed : SPEEDS) {
            for (double error : ERRORS) {
                names.add("at " + speed + " b/t, " + error(error));
            }
        }
        names.add("from a standstill");
        if (kindIn != null && kindIn.rocket()) {
            for (double error : ERRORS) {
                names.add("at " + SLOW_SPEED + " b/t sinking, " + error(error));
            }
        }
        return names;
    }

    private static String error(double error) {
        return error == 0 ? "on course" : (error > 0 ? "+" : "") + (int) error + " degrees off";
    }

    /** The speed-line flight on a level leg out of a ring: {@link #EXPERT_SPEED} along the leg in, no rockets. */
    static ElytraSim.Pilot speedLine(double x, double y, double z, double yawIn) {
        double[] d = dir(yawIn);
        return new ElytraSim.Pilot(new ElytraSim.Flyer(x, y, z, d[0] * EXPERT_SPEED, 0, d[1] * EXPERT_SPEED), 0);
    }

    /**
     * The flights off the tower, each with {@code rockets}: walk off the end of the board along
     * (dx, dz), fall a few ticks, open the wings.
     */
    static List<ElytraSim.Pilot> offTheTower(double[] launch, double dx, double dz, int rockets) {
        List<ElytraSim.Pilot> out = new ArrayList<>();
        for (int open : OPEN_AFTER) {
            ElytraSim.Flyer f = new ElytraSim.Flyer(launch[0], launch[1], launch[2], dx * WALK, 0, dz * WALK);
            for (int i = 0; i < open; i++) {
                ElytraSim.fall(f, dx, dz);
            }
            out.add(new ElytraSim.Pilot(f, rockets));
        }
        return out;
    }

    // ---- the search ---------------------------------------------------------------------------

    /** Pilots that fly the candidate rings {@code from} to {@code until} (ring indexes, 0-based). */
    private static final class Group {
        List<ElytraSim.Pilot> pilots;
        final int from;
        final int until;

        Group(List<ElytraSim.Pilot> pilots, int from, int until) {
            this.pilots = pilots;
            this.from = from;
            this.until = until;
        }
    }

    static final java.util.concurrent.ConcurrentHashMap<String, Integer> DIAG = new java.util.concurrent.ConcurrentHashMap<>();
    private static final class Search {
        final Level level;
        final Box half;
        final GenRandom rng;
        final List<Ring> rings = new ArrayList<>();
        Tower tower;
        /** The kind of the leg into each ring after the first: {@code kinds[k - 1]} for ring k (0-based). */
        LegKind[] kinds;
        /** Rockets the rest of the course needs from the tower and from each ring ({@link RingsPlanner#need}). */
        int[] need;
        /** Height to keep in hand under ring k for the legs after it. */
        int[] reserveAfter;
        /** The highest and lowest a ring's centre block may be. */
        int roofC;
        int floorC;
        /** The way the course turns when it must turn round: +1 is toward growing yaw. */
        int winding;
        /** The lane the course runs along now, and a point on its line. */
        double laneYaw = 180;
        double laneX;
        double laneZ;
        Obstacles obstacles;
        final List<Group> groups = new ArrayList<>();
        long work;

        Search(Level level, Box half, GenRandom rng) {
            this.level = level;
            this.half = half;
            this.rng = rng;
        }

        boolean run() {
            GenRandom t = rng.fork("tower");
            boolean west = t.nextBoolean();
            // the profile first: it decides every leg's kind before any geometry is drawn
            kinds = profile(level, rng.fork("profile"));
            need = need(kinds);
            // the height to keep in hand under each ring: every later glide's least drop and level leg's most,
            // less every later climb's least rise, never below 0 (a climb ahead pays for the drops after it)
            reserveAfter = new int[level.rings()];
            for (int k = level.rings() - 2; k >= 0; k--) {
                reserveAfter[k] = Math.max(0, reserveAfter[k + 1] + switch (kinds[k]) {
                    case GLIDE -> (int) Math.ceil(level.legMin() / level.slow() - 1e-9);
                    case FLAT -> FLAT_DROP;
                    case LIFT -> -level.upMin();
                });
            }
            roofC = half.maxY() - (level.radius() + EDGE);
            floorC = half.minY() + FLOOR;
            int tx = west ? half.minX() + TOWER_INSET : half.maxX() - TOWER_INSET;
            int tz = half.maxZ() - TOWER_SOUTH;
            tower = new Tower(tx, tz, half.maxY() - TOWER_TOP, half.minY());
            // north is yaw 180; east is 270: a tower on the west side turns round toward the east
            winding = west ? 1 : -1;
            laneX = tx + 0.5;
            laneZ = tz + 0.5;
            obstacles = new Obstacles();
            obstacles.box(tower.platform());
            obstacles.box(tower.board());
            obstacles.box(tower.pillar());
            Ring first = ring(tx, tower.top() - FIRST_BELOW - 1, tz - 5 - FIRST_AHEAD, 180);
            rings.add(first);
            obstacles.ring(first.cx(), first.cy(), first.cz(), level.radius(), first.voxels());
            Group off = new Group(offTheTower(tower.launch(), 0, -1, need[0]), 0, Integer.MAX_VALUE);
            groups.add(off);
            if (!advance(off.pilots, first, obstacles, false)) {
                DIAG.merge("first", 1, Integer::sum); return false;
            }
            startFrom(0, first, LegKind.GLIDE);
            for (int k = 1; k < level.rings(); k++) {
                Ring placed = null;
                for (int n = 0; n < TRIES_PER_LEG && placed == null; n++) {
                    work++;
                    placed = candidate(k, rng.fork("leg:" + k + ":" + n));
                }
                if (placed == null) {
                    DIAG.merge("leg" + k, 1, Integer::sum); return false;
                }
            }
            return true;
        }

        /** The pilots that start at ring {@code k} (0-based), and the speed-line pilot when the leg out of it is level. */
        void startFrom(int k, Ring r, LegKind kindIn) {
            if (k >= level.rings() - 1) {
                return;
            }
            groups.add(new Group(startsAt(r.x(), r.y(), r.z(), r.yawIn(), need[k + 1], kindIn), k + 1, k + PILOT_WINDOW));
            if (kinds[k] == LegKind.FLAT) {
                groups.add(new Group(List.of(speedLine(r.x(), r.y(), r.z(), r.yawIn())), k + 1, k + 2));
            }
        }

        /** A ring at (cx, cy, cz) entered along {@code yawIn}, facing the axis nearer that way (a tie: the way it winds). */
        Ring ring(int cx, int cy, int cz, double yawIn) {
            double[] d = dir(yawIn);
            RingShape.Normal normal = RingShape.normalFor(d[0], d[1]);
            if (Math.abs(Math.abs(d[0]) - Math.abs(d[1])) < 1e-9) {
                double[] next = dir(yawIn + winding * 45.0);
                normal = RingShape.normalFor(next[0], next[1]);
            }
            return new Ring(cx, cy, cz, yawIn, normal, RingShape.voxels(cx, cy, cz, level.radius(), normal));
        }

        /** Every flight passes ring {@code r} without touching anything (the flights move on). */
        boolean advance(List<ElytraSim.Pilot> pilots, Ring r, Obstacles solid, boolean last) {
            return advance(pilots, r, solid, last, false);
        }

        boolean advance(List<ElytraSim.Pilot> pilots, Ring r, Obstacles solid, boolean last, boolean rocketLeg) {
            List<ElytraSim.Target> target = List.of(target(r, last, level, rocketLeg));
            for (ElytraSim.Pilot p : pilots) {
                ElytraSim.Result res = p.fly(target, solid);
                work += res.ticks();
                if (!res.passed()) {
                    return false;
                }
            }
            return true;
        }

        /** One try at ring k (0-based, k ≥ 1): placed and kept, or {@code null}. */
        Ring candidate(int k, GenRandom r) {
            Ring prev = rings.get(k - 1);
            LegKind kind = kinds[k - 1];
            // a climb is flown straight: half the tier's turn into it
            int max = kind == LegKind.LIFT ? level.maxTurn() / 2 : level.maxTurn();
            Heading heading = chooseTurn(prev, r, max, k);
            double intended = heading.yaw();
            // the leg's length band by kind; a glide's is cut so its least drop fits the room under it; a
            // corner's legs are the shortest, so the swing fits the square
            int available = prev.cy() - floorC - reserveAfter[k];
            double lo;
            double hi;
            switch (kind) {
                case LIFT -> {
                    lo = Math.max(level.legMin(), LIFT_LEG_MIN);
                    hi = Math.min(level.legMax(), LIFT_LEG_MAX);
                }
                case FLAT -> {
                    lo = Math.max(level.legMin(), FLAT_MIN);
                    hi = Math.min(level.legMax(), FLAT_MAX);
                }
                default -> {
                    lo = level.legMin();
                    hi = Math.min(level.legMax(), Math.floor(available * level.slow() + 1e-9));
                }
            }
            hi = Math.min(hi, heading.swing() ? lo + CORNER_SLACK : heading.most());
            if (hi < lo) {
                { DIAG.merge("c1", 1, Integer::sum); return null; }
            }
            double d = r.nextDouble(lo, hi);
            double[] u = dir(intended);
            int dx = (int) Math.round(u[0] * d);
            int dz = (int) Math.round(u[1] * d);
            double off = Math.abs(((intended % 90) + 90) % 90 - 45);
            if (off < HEADING_STEP / 2.0) {
                // a diagonal leg runs exactly diagonally, so the next ring may face the other way
                int g = (int) Math.round(d / Math.sqrt(2));
                dx = (int) Math.signum(u[0]) * g;
                dz = (int) Math.signum(u[1]) * g;
            }
            int cx = prev.cx() + dx;
            int cz = prev.cz() + dz;
            double actual = Math.hypot(dx, dz);
            double bandLo = kind == LegKind.GLIDE ? level.legMin() : lo;
            double bandHi = kind == LegKind.GLIDE ? level.legMax() : hi;
            if (actual < bandLo - 1e-9 || actual > bandHi + 1e-9) {
                { DIAG.merge("c2", 1, Integer::sum); return null; }
            }
            double yaw = yaw(dx, dz);
            double[] ua = dir(yaw);
            if (Math.abs(turn(prev.yawIn(), yaw)) > max + 1e-9
                    || RingShape.offNormal(ua[0], ua[1], prev.normal()) > level.maxOffNormal() + 1e-9) {
                { DIAG.merge("c3", 1, Integer::sum); return null; }
            }
            // the height by kind
            int cy;
            switch (kind) {
                case LIFT -> {
                    int upHi = Math.min(level.upMax(), roofC - prev.cy());
                    if (upHi < level.upMin()) {
                        { DIAG.merge("c4", 1, Integer::sum); return null; }
                    }
                    cy = prev.cy() + r.nextInt(level.upMin(), upHi);
                }
                case FLAT -> cy = prev.cy() - r.nextInt(0, FLAT_DROP);
                default -> {
                    // the drop: inside the tier's range, leaving room under it for every leg still to come
                    int minDrop = (int) Math.ceil(actual / level.slow() - 1e-9);
                    int maxDrop = (int) Math.floor(Math.min(actual / level.fast(), available) + 1e-9);
                    if (minDrop > maxDrop) {
                        { DIAG.merge("c5", 1, Integer::sum); return null; }
                    }
                    cy = prev.cy() - r.nextInt(minDrop, maxDrop);
                }
            }
            Ring ring = ring(cx, cy, cz, yaw);
            // it faces the way it is flown into
            if (RingShape.offNormal(ua[0], ua[1], ring.normal()) > level.maxOffNormal() + 1e-9) {
                { DIAG.merge("c6", 1, Integer::sum); return null; }
            }
            // the ring with R + 8 all round fits the half
            int m = level.radius() + EDGE;
            if (!half.contains(new Box(cx - m, cy - m, cz - m, cx + m, cy + m, cz + m))) {
                { DIAG.merge("c7 k=" + k + " at " + (cx - half.minX()) + "," + (cz - half.minZ()) + " y" + cy + " yaw " + Math.round(yaw) + " lane " + Math.round(laneYaw) + " prev " + (prev.cx() - half.minX()) + "," + (prev.cz() - half.minZ()) + " kind " + kind, 1, Integer::sum); return null; }
            }
            double[] a = prev.centre();
            double[] b = ring.centre();
            double tube = level.radius() + TUBE;
            for (int j = 0; j < rings.size(); j++) {
                Ring o = rings.get(j);
                double gap = Math.sqrt(Math.pow(o.x() - b[0], 2) + Math.pow(o.y() - b[1], 2) + Math.pow(o.z() - b[2], 2));
                if (gap < RING_GAP) {
                    { DIAG.merge("c8", 1, Integer::sum); return null; }
                }
                // the new leg clears every ring but the one it leaves
                if (j < k - 1) {
                    for (int[] v : o.voxels()) {
                        if (segmentDistance(a, b, v[0] + 0.5, v[1] + 0.5, v[2] + 0.5) < tube) {
                            { DIAG.merge("c9", 1, Integer::sum); return null; }
                        }
                    }
                }
                // and keeps a lane's width from every ring but the two at its ends
                if (j < k - 2 && groundDistance(a, b, o.x(), o.z()) < LANE_GAP) {
                    { DIAG.merge("c10 k=" + k + " leg " + (prev.cx() - half.minX()) + "," + (prev.cz() - half.minZ()) + "->" + (cx - half.minX()) + "," + (cz - half.minZ()) + " yaw " + Math.round(yaw) + " vs ring " + j + " at " + (o.cx() - half.minX()) + "," + (o.cz() - half.minZ()) + " d " + Math.round(groundDistance(a, b, o.x(), o.z())) + " " + kind, 1, Integer::sum); return null; }
                }
            }
            // the new ring clears every older leg, the one off the tower too, and keeps a lane's width from
            // every leg but the one into it and the one before that
            for (int j = 0; j < k; j++) {
                double[] la = j == 0 ? tower.launch() : rings.get(j - 1).centre();
                double[] lb = rings.get(j).centre();
                for (int[] v : ring.voxels()) {
                    if (segmentDistance(la, lb, v[0] + 0.5, v[1] + 0.5, v[2] + 0.5) < tube) {
                        { DIAG.merge("c11", 1, Integer::sum); return null; }
                    }
                }
                if (j < k - 1 && groundDistance(la, lb, b[0], b[2]) < LANE_GAP) {
                    { DIAG.merge("c12 k=" + k + " ring " + (cx - half.minX()) + "," + (cz - half.minZ()) + " yaw " + Math.round(yaw) + " prev " + (prev.cx() - half.minX()) + "," + (prev.cz() - half.minZ()) + " vs leg " + j + " " + Math.round(la[0] - half.minX()) + "," + Math.round(la[2] - half.minZ()) + "->" + Math.round(lb[0] - half.minX()) + "," + Math.round(lb[2] - half.minZ()) + " d " + Math.round(groundDistance(la, lb, b[0], b[2])) + " " + kind, 1, Integer::sum); return null; }
                }
            }
            // and keeps clear of the tower
            if (boxDistance(a, b, tower.platform()) < TOWER_GAP || boxDistance(a, b, tower.board()) < TOWER_GAP
                    || boxDistance(a, b, tower.pillar()) < TOWER_GAP) {
                { DIAG.merge("c13", 1, Integer::sum); return null; }
            }
            // the autopilot: the pilots in the window fly on through it
            Obstacles solid = obstacles.copy();
            solid.ring(ring.cx(), ring.cy(), ring.cz(), level.radius(), ring.voxels());
            groups.removeIf(g -> g.until < k);
            List<Group> active = new ArrayList<>();
            List<List<ElytraSim.Pilot>> tried = new ArrayList<>();
            for (Group g : groups) {
                if (g.from > k) {
                    continue;
                }
                List<ElytraSim.Pilot> copies = new ArrayList<>(g.pilots.size());
                for (ElytraSim.Pilot p : g.pilots) {
                    copies.add(p.copy());
                }
                if (!advance(copies, ring, solid, k == level.rings() - 1, kind.rocket())) {
                    { DIAG.merge("c14", 1, Integer::sum); return null; }
                }
                active.add(g);
                tried.add(copies);
            }
            rings.add(ring);
            obstacles = solid;
            for (int i = 0; i < active.size(); i++) {
                active.get(i).pilots = tried.get(i);
            }
            startFrom(k, ring, kind);
            return ring;
        }

        /** A leg's heading, the most it may run across the ground, and whether it is a corner's (then it runs its kind's shortest). */
        record Heading(double yaw, double most, boolean swing) {
        }

        /** The shortest leg of a kind: a climb's and a level leg's never run under 90. */
        int shortest(LegKind kind) {
            return switch (kind) {
                case LIFT -> Math.max(level.legMin(), LIFT_LEG_MIN);
                case FLAT -> Math.max(level.legMin(), FLAT_MIN);
                default -> level.legMin();
            };
        }

        /**
         * What a corner starting with ring {@code k}'s leg takes: {@code [forward, sideways, legs]}
         * across the ground, the swing's legs at their kinds' shortest length (none when the course ends
         * first). The swing's headings follow the facing rule: up to 45° off the lane until the diagonal
         * leg swaps the facing, then up to 90°, each leg turning the tier's most, or half that into a
         * climb; the leg that reaches 90° runs along the new lane, so it counts sideways.
         */
        double[] corner(int k) {
            double forward = 0;
            double sideways = 0;
            int legs = 0;
            double a = 0;
            for (int j = k; j < level.rings() && a < 90 - 1e-9; j++) {
                int step = kinds[j - 1] == LegKind.LIFT ? level.maxTurn() / 2 : level.maxTurn();
                a = a < 45 - 1e-9 ? Math.min(45, a + step) : Math.min(90, a + step);
                forward += shortest(kinds[j - 1]) * StrictMath.cos(StrictMath.toRadians(a));
                sideways += shortest(kinds[j - 1]) * StrictMath.sin(StrictMath.toRadians(a));
                legs++;
            }
            return new double[]{forward, sideways, legs};
        }

        /**
         * The heading of the next leg (ring {@code k}'s), a whole multiple of {@link #HEADING_STEP}
         * within {@code max} degrees of the last, and the most it may run. The course runs in lanes
         * along x or z: along a lane it wanders a little either side of the lane's line and leans away
         * from a side of the half that is near, running no further than leaves the room a corner takes
         * ({@link #corner}); once a straight leg no longer fits in front of that, and the course still
         * has further to go than the room ahead, it swings round 90° the {@link #winding} way, as
         * sharply as the tier and the ring's facing allow, if the room that way holds the swing and the
         * next corner too. Near the end the course runs straight to the finish. Of the headings the
         * ring's facing allows, the one nearest the wanted one is taken.
         */
        Heading chooseTurn(Ring prev, GenRandom r, int max, int k) {
            double lane = lane(prev);
            double leg = (level.legMin() + level.legMax()) / 2.0;
            double[] corner = corner(k);
            // a new lane starts where the course swung into it
            if (lane != laneYaw) {
                laneYaw = lane;
                laneX = prev.x();
                laneZ = prev.z();
            }
            double ahead = room(prev, dir(lane));
            double still = (level.rings() - rings.size()) * leg;
            boolean finale = still <= ahead;
            // a straight leg runs no further than leaves room for a corner starting with the leg after it
            double[] next = k + 1 < level.rings() ? corner(k + 1) : new double[]{0, 0, 0};
            double most = finale ? ahead - EDGE : ahead - next[0] - EDGE;
            boolean swing = !finale && most < shortest(kinds[k - 1]);
            if (swing) {
                // the swing must fit ahead, and leave its new lane the room for the corner after it
                int after = k + (int) corner[2];
                double then = after < level.rings() ? corner(after)[0] : 0;
                if (corner[0] + EDGE > ahead
                        || room(prev, dir(lane + winding * 90)) < corner[1] + then + EDGE) {
                    // boxed in: run on and let the checks decide
                    swing = false;
                    most = ahead - EDGE;
                }
            }
            double target;
            if (swing) {
                target = lane + winding * 90;
            } else {
                // along the lane: a little wander, pulled back toward the lane's line, away from a side that is near
                double[] left = dir(lane + 90);
                double offset = (prev.x() - laneX) * left[0] + (prev.z() - laneZ) * left[1];
                target = lane + r.nextDouble(-max / 3.0, max / 3.0) - Math.max(-max / 2.0, Math.min(max / 2.0, offset));
                double toLeft = room(prev, left);
                double toRight = room(prev, dir(lane - 90));
                if (toLeft < SIDE && toLeft < toRight) {
                    target -= max / 2.0;
                } else if (toRight < SIDE && toRight < toLeft) {
                    target += max / 2.0;
                }
            }
            double want = turn(prev.yawIn(), target);
            // the allowed heading (a whole multiple of 5 degrees, within the turn and the ring's
            // facing) nearest the wanted one; a tie goes the winding way
            double best = Double.MAX_VALUE;
            double chosen = Math.round(prev.yawIn() / HEADING_STEP) * (double) HEADING_STEP;
            double lo = Math.ceil((prev.yawIn() - max) / HEADING_STEP - 1e-9) * HEADING_STEP;
            for (double yaw = lo; yaw <= prev.yawIn() + max + 1e-9; yaw += HEADING_STEP) {
                double[] u = dir(yaw);
                if (RingShape.offNormal(u[0], u[1], prev.normal()) > level.maxOffNormal() + 1e-9) {
                    continue;
                }
                double turn = turn(prev.yawIn(), yaw);
                double miss = Math.abs(turn - want) - (Math.signum(turn) == winding ? 1e-6 : 0);
                if (miss < best) {
                    best = miss;
                    chosen = yaw;
                }
            }
            return new Heading(chosen, most, swing);
        }

        /** The way the course is running at a ring: along its facing, the way it is flown (a yaw of 0, 90, 180 or 270). */
        double lane(Ring r) {
            double[] u = dir(r.yawIn());
            if (r.normal() == RingShape.Normal.Z) {
                return u[1] < 0 ? 180 : 0;
            }
            return u[0] > 0 ? 270 : 90;
        }

        /**
         * How far a leg could run from ring {@code from} along {@code u}: to the half's inner edge, near
         * another ring, within {@link #LANE_GAP} of an older ring or leg (not the one into {@code from}
         * nor the one before it) or within {@link #TOWER_GAP} of the tower, up to {@link #ROOM_WALK}.
         */
        double room(Ring from, double[] u) {
            int m = level.radius() + EDGE;
            double x0 = half.minX() + m;
            double x1 = half.maxX() + 1 - m;
            double z0 = half.minZ() + m;
            double z1 = half.maxZ() + 1 - m;
            double keep = RING_GAP + level.radius() + 2;
            int older = rings.size() - 2;
            for (double s = 0; s < ROOM_WALK; s += 2) {
                double px = from.x() + u[0] * s;
                double pz = from.z() + u[1] * s;
                if (px < x0 || px > x1 || pz < z0 || pz > z1) {
                    return s;
                }
                for (int j = 0; j < rings.size(); j++) {
                    Ring o = rings.get(j);
                    double ox = o.x() - px;
                    double oz = o.z() - pz;
                    double near = j < older ? LANE_GAP : keep;
                    if (o != from && ox * ox + oz * oz < near * near) {
                        return s;
                    }
                }
                for (int j = 0; j < older; j++) {
                    double[] la = j == 0 ? tower.launch() : rings.get(j - 1).centre();
                    if (groundDistance(la, rings.get(j).centre(), px, pz) < LANE_GAP) {
                        return s;
                    }
                }
                if (groundBoxDistance(px, pz, tower.platform()) < TOWER_GAP
                        || groundBoxDistance(px, pz, tower.board()) < TOWER_GAP) {
                    return s;
                }
            }
            return ROOM_WALK;
        }

        // ---- from rings to a plan ---------------------------------------------------------------

        Plan toPlan(PlanInput in, long work, int restarts) {
            List<String> palette = new ArrayList<>();
            List<BlockOp> ops = new ArrayList<>();
            addBox(ops, palette, tower.platform(), Palette.TOWER);
            addBox(ops, palette, tower.board(), Palette.TOWER);
            addBox(ops, palette, tower.pillar(), Palette.PILLAR + "[axis=y]");
            for (int k = 0; k < rings.size(); k++) {
                String block = colour(k, rings.size(), kinds);
                short s = index(palette, block);
                for (int[] v : rings.get(k).voxels()) {
                    ops.add(new BlockOp(v[0], v[1], v[2], s));
                }
            }
            int top = tower.top();
            double sx = tower.tx() + 0.5;
            double sz = tower.tz() + 0.5;
            int rockets = need[0] + MARGIN;
            // two signs on the platform's front corners, facing the start spot
            List<SignText> signs = List.of(
                    new SignText(tower.tx() - 2, top, tower.tz() - 2,
                            Palette.sign(rotationToward(tower.tx() - 1.5, tower.tz() - 1.5, sx, sz)),
                            GenCopy.ringsStart()),
                    new SignText(tower.tx() + 2, top, tower.tz() - 2,
                            Palette.sign(rotationToward(tower.tx() + 2.5, tower.tz() - 1.5, sx, sz)),
                            GenCopy.ringsHow(rockets)));

            List<Course.Mark> checkpoints = new ArrayList<>();
            double length = 0;
            double[] at = {sx, top, sz};
            int lowest = Integer.MAX_VALUE;
            int highest = Integer.MIN_VALUE;
            for (int k = 0; k < rings.size(); k++) {
                Ring r = rings.get(k);
                double[] c = r.centre();
                length += Math.sqrt(Math.pow(c[0] - at[0], 2) + Math.pow(c[1] - at[1], 2) + Math.pow(c[2] - at[2], 2));
                at = c;
                lowest = Math.min(lowest, r.cy());
                highest = Math.max(highest, r.cy());
                if (k < rings.size() - 1) {
                    checkpoints.add(new Course.Mark(r.x(), r.y(), r.z(), level.checkpointRadius()));
                }
            }
            Ring last = rings.get(rings.size() - 1);
            Course.Mark finish = new Course.Mark(last.x(), last.y(), last.z(), level.checkpointRadius());
            double fallY = Math.max(in.half().minY() + 2, lowest + 0.5 - level.radius() - 12);
            // the reference lap: the first flight off the tower round the whole course with the rockets it needs
            List<ElytraSim.Target> targets = new ArrayList<>();
            for (int k = 0; k < rings.size(); k++) {
                targets.add(target(rings.get(k), k == rings.size() - 1, level, k > 0 && kinds[k - 1].rocket()));
            }
            ElytraSim.Pilot reference = offTheTower(tower.launch(), 0, -1, need[0]).get(0);
            ElytraSim.Result lap = reference.fly(targets, obstacles);
            long refTicks = lap.ticks();
            long refMs = Math.round(refTicks * 50 * REF_FACTOR);
            int minSeconds = (int) Math.floor(length / MIN_SPEED);
            Slots.Def slot = in.slot();
            String legs = legs(kinds);
            Course course = new Course(slot.id(), TrialKind.ELYTRA, slot.name(), Tier.of(level.id()), "",
                    new Course.Spot(sx, top, sz, 180f, 0f), checkpoints, finish, fallY, minSeconds, true, false, 1, null,
                    new Course.Elytra(rockets, needList(need), legs));

            // keep clear: the tube round every leg, as boxes
            List<Box> keepClear = new ArrayList<>();
            double tube = level.radius() + TUBE;
            double[] from = tower.launch();
            for (Ring r : rings) {
                double[] to = r.centre();
                Box b = new Box((int) Math.floor(Math.min(from[0], to[0]) - tube),
                        (int) Math.floor(Math.min(from[1], to[1]) - tube),
                        (int) Math.floor(Math.min(from[2], to[2]) - tube),
                        (int) Math.ceil(Math.max(from[0], to[0]) + tube),
                        (int) Math.ceil(Math.max(from[1], to[1]) + tube),
                        (int) Math.ceil(Math.max(from[2], to[2]) + tube));
                Box h = in.half();
                if (b.intersects(h)) {
                    keepClear.add(new Box(Math.max(b.minX(), h.minX()), Math.max(b.minY(), h.minY()),
                            Math.max(b.minZ(), h.minZ()), Math.min(b.maxX(), h.maxX()), Math.min(b.maxY(), h.maxY()),
                            Math.min(b.maxZ(), h.maxZ())));
                }
                from = to;
            }

            List<String> summary = new ArrayList<>();
            summary.add(slot.name() + " (" + level.id() + "): " + rings.size() + " rings, "
                    + String.format(Locale.ROOT, "%,d", Math.round(length)) + " blocks of flight, reference "
                    + Math.round(refMs / 100.0) / 10.0 + "s, shortest " + minSeconds + "s");
            StringBuilder drops = new StringBuilder("legs (across/down, L = climb, F = level):");
            double steepest = 0;
            for (int k = 1; k < rings.size(); k++) {
                Ring a = rings.get(k - 1);
                Ring b = rings.get(k);
                drops.append(' ').append(Math.round(Math.hypot(b.cx() - a.cx(), b.cz() - a.cz()))).append('/')
                        .append(a.cy() - b.cy());
                if (kinds[k - 1] != LegKind.GLIDE) {
                    drops.append(kinds[k - 1].letter());
                }
                steepest = Math.max(steepest, Math.abs(turn(a.yawIn(), b.yawIn())));
            }
            summary.add(drops.toString());
            summary.add("steepest turn " + Math.round(steepest) + " degrees; rings y " + lowest + ".." + highest
                    + "; falls back below y " + Math.round(fallY));
            summary.add("rockets " + rockets + " (" + need[0] + " needed), autopilot " + Math.round(refTicks * 5 / 10.0) / 10.0
                    + "s" + (lap.passed() ? "" : " (" + lap.why() + ")") + ", profile " + legs);
            summary.add("seed " + GenSeed.shortHex(in.seed()) + ", " + (restarts + 1) + " start(s), " + work + " work");
            return Plan.of(slot.id(), ALGO, in.seed(), in.half(), palette, ops, signs, keepClear,
                    new PlannedTrial(course, refMs), summary, work);
        }
    }

    /** Ring k's block (0-based, of {@code count}): white for a climb's ring, gold for the last, else the rainbow. */
    static String colour(int k, int count, LegKind[] kinds) {
        if (k == count - 1) {
            return Palette.FINISH;
        }
        if (k > 0 && kinds[k - 1] == LegKind.LIFT) {
            return Palette.TOWER;
        }
        return Palette.RAINBOW.get(k % Palette.RAINBOW.size());
    }

    private static void addBox(List<BlockOp> ops, List<String> palette, Box b, String block) {
        short s = index(palette, block);
        for (int x = b.minX(); x <= b.maxX(); x++) {
            for (int y = b.minY(); y <= b.maxY(); y++) {
                for (int z = b.minZ(); z <= b.maxZ(); z++) {
                    ops.add(new BlockOp(x, y, z, s));
                }
            }
        }
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

    /** A standing sign's rotation (sixteenths; 0 = its text faces south) so its text faces (tx, tz). */
    static int rotationToward(double x, double z, double tx, double tz) {
        return Math.floorMod((int) Math.round(yaw(tx - x, tz - z) / 22.5), 16);
    }
}
