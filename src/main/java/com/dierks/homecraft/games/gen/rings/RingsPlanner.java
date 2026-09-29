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
 * The planner for Sky Rings, the elytra course (GEN-SPEC §4.2): a start tower at the south end of
 * the half, then a chain of rainbow rings, always downhill, the last one gold.
 *
 * <p><b>Why it is always flyable.</b> Vanilla's best glide is about 10:1, and every leg drops at
 * least its length over 5 (easy), 6 or 7: a ring is always below the line a player can glide, by
 * 2x on easy and at least 1.4x on hard, so it can be reached with no rockets, and a player who is
 * high can always dive. There are no climbing legs. The second proof is the {@link ElytraSim}
 * autopilot, which must pass every candidate ring before it is kept: at three speeds with its
 * heading a little off from every ring, from a standstill at every ring with at most one rocket (a
 * fall-reset leaves the player gliding at the ring with no speed), and off the tower.
 *
 * <p><b>How a layout grows.</b> Ring 1 is 20 blocks ahead of the tower's diving board and 8
 * below it. Each further leg ({@code fork("leg:" + k + ":" + n)}) draws a turn, a length and a
 * drop from the tier's ranges, steering by the room ahead so it keeps clear of the half's sides
 * and of the course so far, and winding toward the far side when it must turn round. A candidate
 * is refused if its ring plus R + 8 doesn't fit the half, it is within 16 of another ring, a
 * flight through a ring would be more than {@link Level#maxOffNormal} off the ring's facing, a
 * keep-clear tube of R + 2 round a leg that isn't its own meets a frame, it comes within 8 of the
 * tower, the rest of the course couldn't fit under it, or the autopilot can't fly it: 20 tries a
 * leg, then a fresh start ({@code fork("restart:" + n)}), up to 20.
 *
 * <p><b>Facing.</b> Every ring faces x or z, whichever is nearer the flight (a pixel circle on a
 * diagonal looks broken), so a course can only swing from running north-south to running
 * east-west through a leg that runs exactly diagonally, and only if a ring may be flown 45° off
 * its facing: medium and hard allow that (they are too long to fit the half otherwise), easy keeps
 * every flight within 35° and simply runs north.
 *
 * <p>Pure: no Bukkit, no clock, no {@code java.util.Random}. {@link RingsValidator} checks every
 * plan before it is returned.
 */
public final class RingsPlanner implements Planner {

    /** Its version; bump it whenever what it makes for a seed changes (golden hashes pin three seeds). */
    public static final int ALGO = 2;

    /** The three tiers (the table in §4.2). */
    public enum Level {
        EASY("easy", 10, 6, 20, 28, 20, 5, 3, 35),
        MEDIUM("medium", 12, 5, 24, 32, 30, 6, 3.5, 45),
        HARD("hard", 14, 4, 24, 36, 45, 7, 4, 45);

        private final String id;
        private final int rings;
        private final int radius;
        private final int legMin;
        private final int legMax;
        private final int maxTurn;
        private final double slow;
        private final double fast;
        private final double maxOffNormal;

        Level(String id, int rings, int radius, int legMin, int legMax, int maxTurn, double slow, double fast,
              double maxOffNormal) {
            this.id = id;
            this.rings = rings;
            this.radius = radius;
            this.legMin = legMin;
            this.legMax = legMax;
            this.maxTurn = maxTurn;
            this.slow = slow;
            this.fast = fast;
            this.maxOffNormal = maxOffNormal;
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

        /** The shortest leg, across the ground. */
        public int legMin() {
            return legMin;
        }

        /** The longest leg, across the ground. */
        public int legMax() {
            return legMax;
        }

        /** The most the heading may change at a ring, in degrees. */
        public int maxTurn() {
            return maxTurn;
        }

        /** A leg drops at least its length over this: 10:1 glide over the tier's margin. */
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
    /** The tower stands this far in from the side of the half it is on, and 8 from the south end. */
    public static final int TOWER_INSET = 20;
    public static final int TOWER_SOUTH = 8;
    /** Ring 1 is this far ahead of the diving board's end... */
    public static final int FIRST_AHEAD = 20;
    /** ...and its centre about this far under the platform's top. */
    public static final int FIRST_BELOW = 8;
    /** A ring and R + this all round fit inside the half. */
    public static final int EDGE = 8;
    /** Ring centres are at least this far apart. */
    public static final double RING_GAP = 16;
    /** A keep-clear tube round every leg that isn't a ring's own: R + this. */
    public static final int TUBE = 2;
    /** Legs other than the first keep this far from the tower. */
    public static final double TOWER_GAP = 8;
    /** Rings stay this far above the half's floor. */
    public static final int FLOOR = 16;
    /** Within this of a side of the half, a course leans back toward the middle. */
    public static final double SIDE = 12;
    /** Turns are whole multiples of this, in degrees. */
    public static final int HEADING_STEP = 5;
    /** Tries per leg, and fresh starts. */
    public static final int TRIES_PER_LEG = 20;
    public static final int RESTARTS = 20;
    /** The most work a plan can take (tries and simulated flight ticks). A budget of this never runs out. */
    public static final long WORK_BUDGET = 40_000_000L;
    /** Speeds (blocks a tick) and heading errors (degrees) the autopilot enters every ring at. */
    static final double[] SPEEDS = {0.8, 1.2, 1.6};
    static final double[] ERRORS = {-3, 0, 3};
    /** Ticks a player falls off the board before opening the wings. */
    static final int[] OPEN_AFTER = {3, 10};
    /** From the platform's centre, a flight off the board starts this far out: the board's end and a hitbox. */
    static final double LAUNCH = 5.5 + 0.31;
    /** A walker's speed stepping off the board, blocks a tick. */
    static final double WALK = 0.2159;
    /** Reference speed along the course, blocks a second, and the fastest believable one. */
    public static final double REF_SPEED = 20;
    public static final double MIN_SPEED = 60;

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
        if (half.sizeX() < 2 * TOWER_INSET + 8 || half.sizeZ() < 160 || half.sizeY() < 120) {
            throw new GenFailed("the area " + half.describe() + " is too small for Sky Rings");
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
     * The autopilot's target for a ring: its centre and the tier's pass distance, and the way
     * through it (along its facing, the way the leg into it runs), unless the flight ends there.
     */
    static ElytraSim.Target target(Ring r, boolean last, Level level) {
        double[] u = dir(r.yawIn());
        double nx = r.normal() == RingShape.Normal.X ? Math.signum(u[0]) : 0;
        double nz = r.normal() == RingShape.Normal.Z ? Math.signum(u[1]) : 0;
        return new ElytraSim.Target(r.x(), r.y(), r.z(), level.pass(), nx, nz, last);
    }

    /**
     * The autopilot's flights that start at a ring: one per speed and heading error, entering
     * along the leg into the ring, and one from a standstill with a rocket to spare. They start at
     * the checkpoint's centre, where a fall-reset puts the player's feet.
     */
    static List<ElytraSim.Pilot> startsAt(double x, double y, double z, double yawIn) {
        List<ElytraSim.Pilot> out = new ArrayList<>();
        for (double speed : SPEEDS) {
            for (double error : ERRORS) {
                double[] d = dir(yawIn + error);
                out.add(new ElytraSim.Pilot(new ElytraSim.Flyer(x, y, z, d[0] * speed, 0, d[1] * speed), 0));
            }
        }
        out.add(new ElytraSim.Pilot(new ElytraSim.Flyer(x, y, z, 0, 0, 0), 1));
        return out;
    }

    /**
     * The flights off the tower: walk off the end of the board along (dx, dz), fall a few ticks,
     * open the wings.
     */
    static List<ElytraSim.Pilot> offTheTower(double[] launch, double dx, double dz) {
        List<ElytraSim.Pilot> out = new ArrayList<>();
        for (int open : OPEN_AFTER) {
            ElytraSim.Flyer f = new ElytraSim.Flyer(launch[0], launch[1], launch[2], dx * WALK, 0, dz * WALK);
            for (int i = 0; i < open; i++) {
                ElytraSim.fall(f, dx, dz);
            }
            out.add(new ElytraSim.Pilot(f, 0));
        }
        return out;
    }

    // ---- the search ---------------------------------------------------------------------------

    private static final class Search {
        final Level level;
        final Box half;
        final GenRandom rng;
        final List<Ring> rings = new ArrayList<>();
        Tower tower;
        /** The way the course turns when it must turn round: +1 is toward growing yaw. */
        int winding;
        /** The lane the course runs along now, and a point on its line. */
        double laneYaw = 180;
        double laneX;
        double laneZ;
        Obstacles obstacles;
        List<ElytraSim.Pilot> flights = new ArrayList<>();
        long work;

        Search(Level level, Box half, GenRandom rng) {
            this.level = level;
            this.half = half;
            this.rng = rng;
        }

        boolean run() {
            GenRandom t = rng.fork("tower");
            boolean west = t.nextBoolean();
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
            flights.addAll(offTheTower(tower.launch(), 0, -1));
            if (!advance(flights, first, obstacles, false)) {
                return false;
            }
            flights.addAll(startsAt(first.x(), first.y(), first.z(), first.yawIn()));
            for (int k = 1; k < level.rings(); k++) {
                Ring placed = null;
                for (int n = 0; n < TRIES_PER_LEG && placed == null; n++) {
                    work++;
                    placed = candidate(k, rng.fork("leg:" + k + ":" + n));
                }
                if (placed == null) {
                    return false;
                }
            }
            return true;
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
            List<ElytraSim.Target> target = List.of(target(r, last, level));
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
            double intended = chooseTurn(prev, r);
            double d = r.nextDouble(level.legMin(), level.legMax());
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
            if (actual < level.legMin() - 1e-9 || actual > level.legMax() + 1e-9) {
                return null;
            }
            double yaw = yaw(dx, dz);
            double[] ua = dir(yaw);
            if (Math.abs(turn(prev.yawIn(), yaw)) > level.maxTurn() + 1e-9
                    || RingShape.offNormal(ua[0], ua[1], prev.normal()) > level.maxOffNormal() + 1e-9) {
                return null;
            }
            // the drop: inside the tier's range, leaving room under it for every leg still to come
            int remaining = level.rings() - 1 - k;
            double reserve = remaining * Math.ceil(level.legMin() / level.slow());
            int floor = half.minY() + FLOOR;
            int minDrop = (int) Math.ceil(actual / level.slow() - 1e-9);
            int maxDrop = (int) Math.floor(Math.min(actual / level.fast(), prev.cy() - floor - reserve) + 1e-9);
            if (minDrop > maxDrop) {
                return null;
            }
            int cy = prev.cy() - r.nextInt(minDrop, maxDrop);
            Ring ring = ring(cx, cy, cz, yaw);
            // it faces the way it is flown into
            if (RingShape.offNormal(ua[0], ua[1], ring.normal()) > level.maxOffNormal() + 1e-9) {
                return null;
            }
            // the ring with R + 8 all round fits the half
            int m = level.radius() + EDGE;
            if (!half.contains(new Box(cx - m, cy - m, cz - m, cx + m, cy + m, cz + m))) {
                return null;
            }
            double[] a = prev.centre();
            double[] b = ring.centre();
            double tube = level.radius() + TUBE;
            for (int j = 0; j < rings.size(); j++) {
                Ring o = rings.get(j);
                double gap = Math.sqrt(Math.pow(o.x() - b[0], 2) + Math.pow(o.y() - b[1], 2) + Math.pow(o.z() - b[2], 2));
                if (gap < RING_GAP) {
                    return null;
                }
                // the new leg clears every ring but the one it leaves
                if (j < k - 1) {
                    for (int[] v : o.voxels()) {
                        if (segmentDistance(a, b, v[0] + 0.5, v[1] + 0.5, v[2] + 0.5) < tube) {
                            return null;
                        }
                    }
                }
            }
            // the new ring clears every older leg, the one off the tower too
            for (int j = 0; j < k; j++) {
                double[] la = j == 0 ? tower.launch() : rings.get(j - 1).centre();
                double[] lb = rings.get(j).centre();
                for (int[] v : ring.voxels()) {
                    if (segmentDistance(la, lb, v[0] + 0.5, v[1] + 0.5, v[2] + 0.5) < tube) {
                        return null;
                    }
                }
            }
            // and keeps clear of the tower
            if (boxDistance(a, b, tower.platform()) < TOWER_GAP || boxDistance(a, b, tower.board()) < TOWER_GAP
                    || boxDistance(a, b, tower.pillar()) < TOWER_GAP) {
                return null;
            }
            // the autopilot: every flight so far flies on through it
            Obstacles solid = obstacles.copy();
            solid.ring(ring.cx(), ring.cy(), ring.cz(), level.radius(), ring.voxels());
            List<ElytraSim.Pilot> tried = new ArrayList<>(flights.size());
            for (ElytraSim.Pilot p : flights) {
                tried.add(p.copy());
            }
            if (!advance(tried, ring, solid, k == level.rings() - 1)) {
                return null;
            }
            rings.add(ring);
            obstacles = solid;
            flights = tried;
            if (k < level.rings() - 1) {
                flights.addAll(startsAt(ring.x(), ring.y(), ring.z(), ring.yawIn()));
            }
            return ring;
        }

        /**
         * The heading of the next leg, a whole multiple of {@link #HEADING_STEP}. The course runs
         * in lanes along x or z: along a lane it wanders a little either side of the lane's line
         * and leans away from a side of the half that is near; when the room ahead runs short of
         * what a corner needs, and the course still has further to go than that, it swings round
         * 90° the {@link #winding} way, as sharply as the tier and the ring's facing allow. Of the
         * headings the ring's facing allows, the one nearest the wanted one is taken.
         */
        double chooseTurn(Ring prev, GenRandom r) {
            int max = level.maxTurn();
            double lane = lane(prev);
            // a corner needs this much room ahead to swing round inside the half: the swing's legs
            // run forward by their length times the cosine of their heading off the lane
            double leg = (level.legMin() + level.legMax()) / 2.0;
            double corner = leg * 1.5 + EDGE;
            for (int a = max; a < 90; a += max) {
                corner += leg * StrictMath.cos(StrictMath.toRadians(a));
            }
            // a new lane starts where the course swung into it
            if (lane != laneYaw) {
                laneYaw = lane;
                laneX = prev.x();
                laneZ = prev.z();
            }
            double ahead = room(prev, dir(lane));
            double still = (level.rings() - rings.size()) * leg;
            double target;
            if (level.maxOffNormal() >= 45 - 1e-9 && ahead < corner && ahead < still) {
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
            // the allowed heading (a whole multiple of 5 degrees, within the tier's turn and the
            // ring's facing) nearest the wanted one; a tie goes the winding way
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
            return chosen;
        }

        /** The way the course is running at a ring: along its facing, the way it is flown (a yaw of 0, 90, 180 or 270). */
        double lane(Ring r) {
            double[] u = dir(r.yawIn());
            if (r.normal() == RingShape.Normal.Z) {
                return u[1] < 0 ? 180 : 0;
            }
            return u[0] > 0 ? 270 : 90;
        }

        /** How far a leg could run from ring {@code from} along {@code u}: to the half's inner edge or near another ring. */
        double room(Ring from, double[] u) {
            int m = level.radius() + EDGE;
            double x0 = half.minX() + m;
            double x1 = half.maxX() + 1 - m;
            double z0 = half.minZ() + m;
            double z1 = half.maxZ() + 1 - m;
            double keep = RING_GAP + level.radius() + 2;
            for (double s = 0; s < 200; s += 2) {
                double px = from.x() + u[0] * s;
                double pz = from.z() + u[1] * s;
                if (px < x0 || px > x1 || pz < z0 || pz > z1) {
                    return s;
                }
                for (Ring o : rings) {
                    double ox = o.x() - px;
                    double oz = o.z() - pz;
                    if (o != from && ox * ox + oz * oz < keep * keep) {
                        return s;
                    }
                }
            }
            return 200;
        }

        // ---- from rings to a plan ---------------------------------------------------------------

        Plan toPlan(PlanInput in, long work, int restarts) {
            List<String> palette = new ArrayList<>();
            List<BlockOp> ops = new ArrayList<>();
            addBox(ops, palette, tower.platform(), Palette.TOWER);
            addBox(ops, palette, tower.board(), Palette.TOWER);
            addBox(ops, palette, tower.pillar(), Palette.PILLAR + "[axis=y]");
            for (int k = 0; k < rings.size(); k++) {
                String block = k == rings.size() - 1 ? Palette.FINISH : Palette.RAINBOW.get(k % Palette.RAINBOW.size());
                short s = index(palette, block);
                for (int[] v : rings.get(k).voxels()) {
                    ops.add(new BlockOp(v[0], v[1], v[2], s));
                }
            }
            int top = tower.top();
            double sx = tower.tx() + 0.5;
            double sz = tower.tz() + 0.5;
            // two signs on the platform's front corners, facing the start spot
            List<SignText> signs = List.of(
                    new SignText(tower.tx() - 2, top, tower.tz() - 2,
                            Palette.sign(rotationToward(tower.tx() - 1.5, tower.tz() - 1.5, sx, sz)),
                            GenCopy.ringsStart()),
                    new SignText(tower.tx() + 2, top, tower.tz() - 2,
                            Palette.sign(rotationToward(tower.tx() + 2.5, tower.tz() - 1.5, sx, sz)),
                            GenCopy.ringsHow()));

            List<Course.Mark> checkpoints = new ArrayList<>();
            double length = 0;
            double[] at = {sx, top, sz};
            int lowest = Integer.MAX_VALUE;
            for (int k = 0; k < rings.size(); k++) {
                Ring r = rings.get(k);
                double[] c = r.centre();
                length += Math.sqrt(Math.pow(c[0] - at[0], 2) + Math.pow(c[1] - at[1], 2) + Math.pow(c[2] - at[2], 2));
                at = c;
                lowest = Math.min(lowest, r.cy());
                if (k < rings.size() - 1) {
                    checkpoints.add(new Course.Mark(r.x(), r.y(), r.z(), level.checkpointRadius()));
                }
            }
            Ring last = rings.get(rings.size() - 1);
            Course.Mark finish = new Course.Mark(last.x(), last.y(), last.z(), level.checkpointRadius());
            double fallY = Math.max(in.half().minY() + 2, lowest + 0.5 - level.radius() - 12);
            long refMs = Math.round(length / REF_SPEED * 1000);
            int minSeconds = (int) Math.floor(length / MIN_SPEED);
            Slots.Def slot = in.slot();
            Course course = new Course(slot.id(), TrialKind.ELYTRA, slot.name(), Tier.of(level.id()), "",
                    new Course.Spot(sx, top, sz, 180f, 0f), checkpoints, finish, fallY, minSeconds, true, false, 1);

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
            summary.add(slot.name() + " (" + level.id() + "): " + rings.size() + " rings, " + Math.round(length)
                    + " blocks of flight, reference " + Math.round(refMs / 100.0) / 10.0 + "s, shortest "
                    + minSeconds + "s");
            StringBuilder drops = new StringBuilder("legs (across/down):");
            double steepest = 0;
            for (int k = 1; k < rings.size(); k++) {
                Ring a = rings.get(k - 1);
                Ring b = rings.get(k);
                drops.append(' ').append(Math.round(Math.hypot(b.cx() - a.cx(), b.cz() - a.cz()))).append('/')
                        .append(a.cy() - b.cy());
                steepest = Math.max(steepest, Math.abs(turn(a.yawIn(), b.yawIn())));
            }
            summary.add(drops.toString());
            summary.add("steepest turn " + Math.round(steepest) + " degrees; rings y " + lowest + ".."
                    + rings.get(0).cy() + "; falls back below y " + Math.round(fallY));
            summary.add("seed " + GenSeed.shortHex(in.seed()) + ", " + (restarts + 1) + " start(s), " + work + " work");
            return Plan.of(slot.id(), ALGO, in.seed(), in.half(), palette, ops, signs, keepClear,
                    new PlannedTrial(course, refMs), summary, work);
        }
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
