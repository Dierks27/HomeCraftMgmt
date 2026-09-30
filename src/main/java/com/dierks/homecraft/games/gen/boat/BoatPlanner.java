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
import com.dierks.homecraft.games.trial.RaceStand;
import com.dierks.homecraft.games.trial.Tier;
import com.dierks.homecraft.games.trial.TrialKind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The planner for Ice Boat's "Mountain Run" (Course Variety §2; the slot ships switched off): a
 * downhill sprint on a rounded-square spiral that winds from the rim of the half down round the
 * viewing stand, with real drops of 1-2 blocks, sand, pick-a-path splits, an ice cave, a forest and
 * the Final Drop, and the finish under the stand.
 *
 * <p><b>Why a sprint.</b> A boat can't climb (fact F1), so a closed loop can never lose height.
 * Everything the races use already handles a sprint: one lap, the grid straight back from the start,
 * the stand at the half's middle.
 *
 * <p><b>How a plan is made</b> (§2.9). For up to {@value #TRIES} tries, each from its own seeded
 * streams: {@link TrackPath} draws the spiral, {@link TrackProfile} the drops and the finish,
 * {@link TrackPieces} the pieces (kept only while the checkpoints still fit round them),
 * {@link TrackRaster} lays it on whole blocks with its checkpoints and walls, {@link BoatScenery}
 * adds the mountain; then {@link DownhillValidator} (V1-V13) must prove it, or the next try comes.
 * Tries 1-10 have every piece of the tier, 11-15 fewer (no forest, one split, one sand pit), 16-20
 * only the drops, the bends' sand and the scenery. If none is proven, {@code SAFE_SPIRAL}: the
 * tier's smallest bends, its fewest drops at fixed places, no pieces, whose track depends only on
 * the orientation (a test proves all 24 in both halves), so a course is never missing.
 *
 * <p><b>No simulator yet</b> (the Course Variety decisions, §11's schedule valve): this is algo 3
 * without V14. The reference time is the centreline from the start to the finish at
 * {@value #REF_SPEED} blocks a second (the loop's rule), the landing strips are the spec's constants,
 * and the proof is unaffected (rule R1). {@code BoatSim}'s fun gates come as algo 4 after the owner's
 * boat test strip (Gate 0).
 *
 * <p><b>Nothing configurable shapes a layout</b> (rule R6): it comes only from (algo, seed, day,
 * reroll, tier, half), so a pin, an admin's pick, a rederive and the Weekly Cup's plan hash always
 * name the same blocks.
 *
 * <p>Pure: no Bukkit, no clock, no {@code java.util.Random}.
 */
public final class BoatPlanner implements Planner {

    /**
     * Its version; bump it whenever what it makes for a seed changes (golden hashes pin three seeds
     * a tier). 2 added the viewing stand; 3 is the Mountain Run (a layout of algo 2 keeps its stored
     * plan, judged by {@link LoopValidatorV2}, until its set ends).
     */
    public static final int ALGO = 3;

    /** How much of the tier's deck a try asks for (§2.9). */
    public enum Richness {
        /** The tier's whole deck of pieces. */
        FULL,
        /** No forest, one split, one sand pit. */
        REDUCED,
        /** Drops, the bends' sand and the scenery. */
        BASIC
    }

    /**
     * The three tiers (§2.4): lane, pit, bends, pitch, how far round, ice, drops, the first drop's
     * distance, sand, and the deck of pieces. What the proof checks of a tier (the narrowest passage,
     * the ice line, the drops' limits) is {@link DownhillValidator.Tier}'s, read through
     * {@link #proof()}.
     *
     * <p>Where §2.4's table and its fixed constants (the landing strips, Z(d) + 12 between drops, a
     * checkpoint at most 60 on) can't both hold on a 128-block half, the constants win and the table
     * bends: the inner corners keep near the smallest radius so the inner straights hold a drop and its
     * landing; easy goes round 1.5 turns (not 1.25) so its last flat leg has room for a piece; easy
     * gets 4 drops, medium 5-6, hard 4-5 (its blue landing strips fit the outer legs only, and a
     * Final Drop on the finish's own side would be too near it). Sand pits and caves need a flat
     * stretch outside every flight zone, so they are common on hard and rare on easy and medium.
     */
    public enum Level {
        EASY("easy", 9, 9, 16, new int[]{32, 30, 24, 16, 17, 17}, 20, 6, false, 4, 5, 70, 1, 0,
                new int[]{1, 1}, new int[]{1, 1}, new int[]{0, 1}, 0, new int[]{0, 0}, 4, false),
        MEDIUM("medium", 7, 7, 12, new int[]{24, 24, 20, 18, 16, 13, 13}, 18, 7, false, 5, 6, 60, 2, 1,
                new int[]{1, 1}, new int[]{1, 1}, new int[]{1, 1}, 1, new int[]{1, 2}, 4, true),
        HARD("hard", 5, 7, 12, new int[]{20, 20, 18, 16, 14, 13, 13, 13, 13}, 16, 9, true, 4, 6, 50, 2, 2,
                new int[]{1, 2}, new int[]{1, 2}, new int[]{1, 2}, 1, new int[]{0, 0}, 3, true);

        private final String id;
        private final int width;
        private final int pitWidth;
        private final int minRadius;
        private final int[] radiusCaps;
        private final int pitch;
        private final int lastLeg;
        private final boolean blue;
        private final int minDrops;
        private final int maxDrops;
        private final int firstLip;
        private final int runoff;
        private final int kerb;
        private final int[] pits;
        private final int[] splits;
        private final int[] caves;
        private final int forests;
        private final int[] boosts;
        private final int sandPit;
        private final boolean splitSand;

        Level(String id, int width, int pitWidth, int minRadius, int[] radiusCaps, int pitch, int lastLeg,
              boolean blue, int minDrops, int maxDrops, int firstLip, int runoff, int kerb, int[] pits, int[] splits,
              int[] caves, int forests, int[] boosts, int sandPit, boolean splitSand) {
            this.id = id;
            this.width = width;
            this.pitWidth = pitWidth;
            this.minRadius = minRadius;
            this.radiusCaps = radiusCaps;
            this.pitch = pitch;
            this.lastLeg = lastLeg;
            this.blue = blue;
            this.minDrops = minDrops;
            this.maxDrops = maxDrops;
            this.firstLip = firstLip;
            this.runoff = runoff;
            this.kerb = kerb;
            this.pits = pits;
            this.splits = splits;
            this.caves = caves;
            this.forests = forests;
            this.boosts = boosts;
            this.sandPit = sandPit;
            this.splitSand = splitSand;
        }

        public String id() {
            return id;
        }

        /** The lane's width w (9, 7, 5), blocks of ice across. */
        public int width() {
            return width;
        }

        /** The launch pit's width: max(w, 7). */
        public int pitWidth() {
            return pitWidth;
        }

        /** The tightest bend: R 16 (easy) or 12. */
        public int minRadius() {
            return minRadius;
        }

        /**
         * The largest radius corner k may have: the tier's largest on the outer corners, near the
         * smallest on the inner ones, so the inner straights keep room for a drop and its landing.
         */
        public int radiusCap(int corner) {
            return radiusCaps[Math.min(corner, radiusCaps.length - 1)];
        }

        /** The pitch p: rings on the same side are p apart (20, 18, 16). */
        public int pitch() {
            return pitch;
        }

        /** The last leg (the finish's): 1.25, 1.75 and 2.25 turns round. */
        public int lastLeg() {
            return lastLeg;
        }

        /** Blue ice (hard), else packed. */
        public boolean blue() {
            return blue;
        }

        /** The fewest and most drops a try may have. */
        public int minDrops() {
            return minDrops;
        }

        public int maxDrops() {
            return maxDrops;
        }

        /** How far after the start the first drop may come, at least. */
        public int firstLip() {
            return firstLip;
        }

        /** Sand columns on the outside of a bend tighter than R 24, and on its inside at the apex. */
        public int runoff() {
            return runoff;
        }

        public int kerb() {
            return kerb;
        }

        /** The deck: sand pits, splits, caves (each {least, most}), forests, boost strips. */
        public int[] pits() {
            return pits.clone();
        }

        public int[] splits() {
            return splits.clone();
        }

        public int[] caves() {
            return caves.clone();
        }

        public int forests() {
            return forests;
        }

        public int[] boosts() {
            return boosts.clone();
        }

        /** A sand pit's width: 4 (3 on hard, so both ways round stay P wide). */
        public int sandPit() {
            return sandPit;
        }

        /** Whether a split's branch has a sand patch (medium, hard). */
        public boolean splitSand() {
            return splitSand;
        }

        /** How many trunks a forest has: 3 on medium, 4 on hard. */
        public int trunks() {
            return this == HARD ? 4 : 3;
        }

        /** What the proof asks of this tier. */
        public DownhillValidator.Tier proof() {
            return DownhillValidator.Tier.of(id);
        }

        /** A checkpoint's radius on the lane: w / 2 + 0.5, so it spans it. */
        public double checkpointRadius() {
            return width / 2.0 + 0.5;
        }

        /** The finish's radius: w / 2 + 1.5. */
        public double finishRadius() {
            return width / 2.0 + 1.5;
        }

        /** A drop's flat straight run-up: at least 8, and room for the checkpoint before it. */
        public int runUp() {
            return Math.max(8, width + 3);
        }

        /** The straight landing strip after a drop of {@code d} (§2.5, fun constants). */
        public int landing(int d) {
            if (blue) {
                return d >= 2 ? 41 : 33;
            }
            return d >= 2 ? 26 : 22;
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

    /** a_0: the outermost leg's offset from the stand's centre, seeded from 54 to 56; the safe spiral's. */
    static final int A0_MIN = 54;
    static final int A0_MAX = 56;
    static final int A0_SAFE = 55;
    /** The start's ice: H0 + 8, the highest start the stand allows (its rail at the half's top). */
    static final int TOP_ABOVE = 8;
    /** The last leg ends this far inside the ring outside it on its side (its end wall, a gap, that ring's wall). */
    static final int END_ROOM = 3;
    /** Hard's pit narrows from 7 to 5 this far after the pit's end: the first corner's funnel. */
    static final int FUNNEL = 4;
    /** Drops are at least Z(d) + this apart along the track (§2.6). */
    static final int LIP_GAP = 12;
    /** Each drop but the Final Drop becomes a 2 with this chance, while the tier allows. */
    static final double BIG_CHANCE = 0.6;
    /** Bends tighter than this have sand on their outside (§2.4). */
    static final int SANDY_BEND = 24;
    /** No track, kerb or widening comes nearer the stand's centre than this, along an axis. */
    static final double STAND_ROOM = 16;
    /** The most a piece widens one side of the lane, and the lane's farthest edge from the centre. */
    static final int MAX_EXTRA = 3;
    static final double OUTER_EDGE = 62;
    /** A piece keeps this far from its straight's ends and this far from the next piece. */
    static final int PIECE_END_GAP = 2;
    static final int PIECE_GAP = 4;
    /** Places tried for a piece before it is given up. */
    static final int PIECE_ATTEMPTS = 6;
    /** The sand pit's widening, and the forest's, blocks long at each end. */
    static final int PIT_TAPER = 8;
    static final int FOREST_TAPER = 4;
    /** A split's island is this long (seeded), with this much wide lane before and after it. */
    static final int ISLAND_MIN = 8;
    static final int ISLAND_MAX = 16;
    static final int SPLIT_APPROACH = 5;
    /** A cave's ice is at most H0 + this (its roof under the cap), a forest's H0 + this; a trunk's top over its ice. */
    static final int CAVE_TOP = 7;
    static final int FOREST_TOP = 6;
    static final int TRUNK_TOP = 6;
    /** A cave keeps this far from any drop; a boost strip ends this far before one. */
    static final int CAVE_LIP = 10;
    static final int BOOST_LIP = 30;
    /** Two targets are at most this far apart across the ground (60, with a margin), and normally this far along. */
    static final double LEG_MAX = 59.5;
    /** A checkpoint on a bend (one with no sand) is this much over half the lane wide, so its sphere cuts the lane. */
    static final double ARC_SPOT = 1.5;
    static final double SPACING = 32;
    /** Arrows in the walls this often along the track. */
    static final double ARROW_SPACING = 24;
    /** Signs stand this far before their piece (and no nearer than {@link #SIGN_NEAR}). */
    static final int SIGN_BEFORE = 10;
    static final int SIGN_NEAR = 3;

    /** Tries before {@code SAFE_SPIRAL}; after ten, fewer pieces, after fifteen only the basics. */
    public static final int TRIES = 20;
    /**
     * The work one plan may take: one per try (and, once {@code BoatSim} is ported as algo 4, one per
     * simulated tick). {@link #SAFE_RESERVE} of it is kept for {@code SAFE_SPIRAL}.
     */
    public static final long WORK_BUDGET = 2_500_000;
    public static final long SAFE_RESERVE = 100_000;
    /** The reference speed along the centreline, blocks a second (the schedule valve's rule). */
    public static final double REF_SPEED = 30;

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
        if (half.sizeX() < 128 || half.sizeZ() < 128 || half.sizeY() < 16) {
            throw new GenFailed("the area " + half.describe() + " is too small for the Mountain Run");
        }
        GenRandom root = new GenRandom(in.seed());
        long budget = in.workBudget();
        for (int t = 0; t < TRIES; t++) {
            in.checkCancelled();
            if (budget > 0 && t + 1 > budget - SAFE_RESERVE) {
                break;
            }
            Richness rich = t < 10 ? Richness.FULL : t < 15 ? Richness.REDUCED : Richness.BASIC;
            Made m;
            try {
                m = attempt(in, level, root, t, rich);
            } catch (RuntimeException e) {
                m = null; // a try that can't come together is just a failed try: the next one, or the safe spiral
            }
            if (m != null && DownhillValidator.problems(m.plan, level.id()).isEmpty()) {
                return m.finished(t + 1, TRIES);
            }
        }
        in.checkCancelled();
        Made safe = safe(in, level, root);
        if (safe == null || !DownhillValidator.problems(safe.plan, level.id()).isEmpty()) {
            throw new GenFailed("no " + level.id() + " Mountain Run found for seed " + GenSeed.shortHex(in.seed()));
        }
        return safe.finished(TRIES + 1, TRIES);
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

    // ---- one try -----------------------------------------------------------------------------------------

    /** Try {@code t}: its own streams, its richness; {@code null} when it doesn't come together. */
    static Made attempt(PlanInput in, Level level, GenRandom root, int t, Richness rich) {
        Box half = in.half();
        int standX = RaceStand.centreX(half) - half.minX();
        int standZ = RaceStand.centreZ(half) - half.minZ();
        TrackPath path = TrackPath.draw(root.fork("track:" + t), level, standX + 0.5, standZ + 0.5);
        int top = half.minY() + TOP_ABOVE;
        TrackProfile profile = TrackProfile.draw(root.fork("drops:" + t), path, level, top, standX, standZ);
        if (profile == null) {
            return null;
        }
        int[] runoff = new int[path.segs.size()];
        int[] kerb = new int[path.segs.size()];
        TrackPieces.bends(path, level, runoff, kerb);
        TrackRaster base = new TrackRaster(half, path, profile, new TrackPieces(List.of(), runoff, kerb), level);
        if (base.problem() != null) {
            return null;
        }
        List<TrackRaster.Spot> baseSpots = base.spots();
        if (chain(base, baseSpots, List.of()) == null) {
            return null;
        }
        TrackProfile exact = profile.withZones(base.zoneEnds());
        TrackPieces pieces = TrackPieces.draw(root.fork("pieces:" + t), path, exact, level, rich, half.minY(),
                blocked -> chain(base, baseSpots, blocked) != null);
        return build(in, level, root.fork("scenery:" + t), path, profile, pieces);
    }

    /** The checkpoints' chain: legs of at most {@link #SPACING} where no drop is between, else any that keep the rules. */
    static List<TrackRaster.Spot> chain(TrackRaster r, List<TrackRaster.Spot> spots, List<double[]> blocked) {
        return r.chain(spots, blocked, SPACING);
    }

    /**
     * {@code SAFE_SPIRAL} (§2.9): a_0 = 55, every corner the tier's smallest radius (§2.9 says 1.5
     * times it, but then the inner straights are too short for the fixed drops, their landing strips
     * and their checkpoints), the orientation from the seed; the tier's fewest drops at the first place
     * each may go (easy 4 x 1, medium 5 x 1, hard 1, 1, 2, 1); no pieces, no sand but the finish's
     * paddock; the scenery seeded. Its track depends only on the orientation.
     */
    static Made safe(PlanInput in, Level level, GenRandom root) {
        GenRandom r = root.fork("safe");
        int side0 = r.nextInt(4);
        int dir = r.nextBoolean() ? 1 : -1;
        return safe(in, level, side0, dir, root.fork("scenery:safe"));
    }

    /** {@code SAFE_SPIRAL} with this orientation. */
    static Made safe(PlanInput in, Level level, int side0, int dir, GenRandom scenery) {
        Box half = in.half();
        int standX = RaceStand.centreX(half) - half.minX();
        int standZ = RaceStand.centreZ(half) - half.minZ();
        TrackPath path = TrackPath.safe(side0, dir, level, standX + 0.5, standZ + 0.5);
        int top = half.minY() + TOP_ABOVE;
        double finish = TrackProfile.finish(null, path, level, standX, standZ);
        if (Double.isNaN(finish)) {
            return null;
        }
        int[] drops = switch (level) {
            case EASY -> new int[]{1, 1, 1, 1};
            case MEDIUM -> new int[]{1, 1, 1, 1, 1};
            case HARD -> new int[]{1, 1, 2, 1};
        };
        List<TrackProfile.Lip> lips = TrackProfile.fixed(path, level, drops, finish);
        if (lips == null) {
            return null;
        }
        TrackProfile profile = TrackProfile.of(path, level, top, lips, finish);
        return build(in, level, scenery, path, profile, TrackPieces.none(path));
    }

    /** The plan from a path, its drops and its pieces: the raster, the checkpoints, every block. */
    static Made build(PlanInput in, Level level, GenRandom scenery, TrackPath path, TrackProfile profile,
                      TrackPieces pieces) {
        Box half = in.half();
        TrackRaster raster = new TrackRaster(half, path, profile, pieces, level);
        if (raster.problem() != null) {
            return null;
        }
        List<TrackRaster.Spot> cps = chain(raster, raster.spots(), TrackPieces.blocked(pieces.list));
        if (cps == null) {
            return null;
        }
        raster.terrace = BoatScenery.terraces(raster);
        raster.blocks();
        raster.walls();
        raster.structures(scenery.fork("islands"));
        raster.markers(cps);
        int trees = BoatScenery.draw(scenery, raster);
        List<SignText> signs = signs(raster, pieces);
        raster.stand();
        List<BlockOp> ops = raster.ops();
        if (ops.size() > DownhillValidator.MAX_OPS) {
            return null;
        }

        // the course: a sprint from the pit to the gold finish under the stand
        int wx = half.minX();
        int wz = half.minZ();
        double[] st = path.at(TrackProfile.START);
        double[] tan = path.tangent(TrackProfile.START);
        Course.Spot start = new Course.Spot(wx + st[0], profile.top + 1, wz + st[1],
                (float) TrackRaster.yaw(tan[0], tan[1]), 0f);
        List<Course.Mark> marks = new ArrayList<>();
        for (TrackRaster.Spot c : cps) {
            marks.add(new Course.Mark(wx + c.x(), c.ice() + 1, wz + c.z(), c.r()));
        }
        double[] fp = path.at(profile.finish);
        Course.Mark finish = new Course.Mark(wx + fp[0], profile.bottom() + 1, wz + fp[1], level.finishRadius());
        Slots.Def slot = in.slot();
        int lowest = raster.lowest();
        Course draft = new Course(slot.id(), TrialKind.BOAT, slot.name(), Tier.of(level.id()), "", start, marks,
                finish, (double) (lowest - 3), null, true, false, 1);
        int min = DownhillValidator.minSeconds(draft);
        Course course = draft.withMinSeconds(min);
        double length = profile.finish - TrackProfile.START;
        long refMs = Math.max(Math.round(length / REF_SPEED * 1000), min * 1000L + 1000);
        List<Box> keep = keepClear(raster);
        Plan plan = Plan.of(slot.id(), ALGO, in.seed(), half, raster.palette, ops, signs, keep,
                new PlannedTrial(course, refMs), List.of(), 0);
        return new Made(in, level, path, profile, pieces, plan, cps.size(), trees, length);
    }

    /** A plan made, and what its summary says. */
    static final class Made {
        final PlanInput in;
        final Level level;
        final TrackPath path;
        final TrackProfile profile;
        final TrackPieces pieces;
        final Plan plan;
        final int checkpoints;
        final int trees;
        final double length;

        Made(PlanInput in, Level level, TrackPath path, TrackProfile profile, TrackPieces pieces, Plan plan,
             int checkpoints, int trees, double length) {
            this.in = in;
            this.level = level;
            this.path = path;
            this.profile = profile;
            this.pieces = pieces;
            this.plan = plan;
            this.checkpoints = checkpoints;
            this.trees = trees;
            this.length = length;
        }

        /** The plan with its admin summary and the work it took (neither is in its hash). */
        Plan finished(long work, int tries) {
            Plan p = plan;
            return new Plan(p.slot(), p.algo(), p.seed(), p.half(), p.palette(), p.ops(), p.signs(), p.keepClear(),
                    p.course(), summary(work, tries), work, p.hash());
        }

        List<String> summary(long work, int tries) {
            List<String> out = new ArrayList<>();
            String how = work > tries ? "the safe spiral after " + tries + " tries" : "try " + work + "/" + tries;
            out.add(in.slot().name() + " v" + ALGO + " " + level.id() + ": Mountain Run, " + Math.round(length)
                    + " blocks, " + path.describe() + ", " + how);
            StringBuilder drops = new StringBuilder();
            for (TrackProfile.Lip l : profile.lips) {
                drops.append(drops.length() == 0 ? "" : ",").append(l.drop());
            }
            TrackProfile.Lip last = profile.last();
            out.add("drops " + drops + " (" + profile.descent() + " down), Final Drop "
                    + (last == null ? 0 : last.drop()) + " - sand: run-offs " + pieces.runoffs() + ", kerbs "
                    + pieces.kerbs() + ", pit " + pieces.count(TrackPieces.Kind.SAND_PIT) + " - split "
                    + pieces.count(TrackPieces.Kind.SPLIT) + " - cave " + pieces.count(TrackPieces.Kind.CAVE)
                    + " - forest " + pieces.count(TrackPieces.Kind.FOREST) + " - boost "
                    + pieces.count(TrackPieces.Kind.BOOST) + " - trees " + trees);
            out.add("grid " + com.dierks.homecraft.games.trial.RaceGrid.MAX_SPOTS + " (double) - checkpoints "
                    + checkpoints + " - stand ok - " + String.format(Locale.ROOT, "%,d", plan.ops().size())
                    + " blocks");
            Course c = ((PlannedTrial) plan.course()).course();
            long ref = ((PlannedTrial) plan.course()).refMs();
            out.add("reference " + String.format(Locale.ROOT, "%.1f", ref / 1000.0) + " s (the centreline at "
                    + Math.round(REF_SPEED) + " blocks a second), shortest " + c.minSeconds() + " s - seed "
                    + GenSeed.shortHex(in.seed()));
            return out;
        }
    }

    /** One box per leg and level (its ice + 1 to + 4) and one per flight zone (lower ice + 1 to the lip + 3). */
    static List<Box> keepClear(TrackRaster t) {
        Map<Long, int[]> runs = new LinkedHashMap<>();
        Map<Integer, int[]> zones = new LinkedHashMap<>();
        for (int x = 0; x < t.sx; x++) {
            for (int z = 0; z < t.sz; z++) {
                if (t.h[x][z] == TrackRaster.NONE) {
                    continue;
                }
                long key = ((long) t.segAt[x][z].leg << 20) | (t.h[x][z] & 0xFFFFF);
                grow(runs.computeIfAbsent(key, k -> box()), x, z, t.h[x][z], t.h[x][z]);
                if (t.zoneLip[x][z] != TrackRaster.NONE) {
                    grow(zones.computeIfAbsent(t.zoneLip[x][z] * 1024 + t.h[x][z], k -> box()), x, z, t.h[x][z],
                            t.zoneLip[x][z]);
                }
            }
        }
        List<Box> out = new ArrayList<>();
        int wx = t.half.minX();
        int wz = t.half.minZ();
        for (int[] b : runs.values()) {
            out.add(new Box(wx + b[0], b[4] + 1, wz + b[1], wx + b[2], Math.min(t.top, b[4] + 4), wz + b[3]));
        }
        for (int[] b : zones.values()) {
            out.add(new Box(wx + b[0], b[4] + 1, wz + b[1], wx + b[2], Math.min(t.top, b[5] + 3), wz + b[3]));
        }
        return out.size() <= DownhillValidator.MAX_BOXES ? out : List.copyOf(out.subList(0, DownhillValidator.MAX_BOXES));
    }

    private static int[] box() {
        return new int[]{Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, 0, 0};
    }

    private static void grow(int[] b, int x, int z, int lo, int hi) {
        b[0] = Math.min(b[0], x);
        b[1] = Math.min(b[1], z);
        b[2] = Math.max(b[2], x);
        b[3] = Math.max(b[3], z);
        b[4] = lo;
        b[5] = hi;
    }

    /** The signs: the start's, one before each drop, piece and sandy bend, and the stand's. */
    static List<SignText> signs(TrackRaster t, TrackPieces pieces) {
        List<SignText> out = new ArrayList<>();
        List<double[]> used = new ArrayList<>();
        sign(t, out, used, TrackProfile.START - 2, TrackProfile.START - 2, GenCopy.boatRun());
        for (int i = 0; i < t.profile.lips.size(); i++) {
            TrackProfile.Lip l = t.profile.lips.get(i);
            boolean last = i == t.profile.lips.size() - 1;
            sign(t, out, used, l.s() - SIGN_BEFORE, l.s() - SIGN_NEAR, last ? GenCopy.boatFinalDrop()
                    : GenCopy.boatDrop(l.drop()));
        }
        for (TrackPieces.Piece p : pieces.list) {
            List<String> lines = switch (p.kind) {
                case SAND_PIT -> GenCopy.boatSandPit();
                case SPLIT -> GenCopy.boatSplit();
                case CAVE -> GenCopy.boatIceCave();
                case FOREST -> GenCopy.boatForest();
                case BOOST -> null;
            };
            if (lines != null) {
                sign(t, out, used, p.s1 - SIGN_BEFORE, p.s1 - SIGN_NEAR, lines);
            }
        }
        for (TrackPath.Seg g : t.path.segs) {
            if (g.arc && pieces.runoff[g.index] > 0 && g.s0 < t.profile.finish) {
                sign(t, out, used, g.s0 - SIGN_BEFORE, g.s0 - SIGN_NEAR, GenCopy.boatSandyBend());
            }
        }
        int wx = t.half.minX();
        int wz = t.half.minZ();
        out.add(new SignText(wx + t.standX, t.top + 2, wz + t.standZ - 2, Palette.sign(0), RaceStand.SIGN));
        return out;
    }

    /** A sign between {@code from} and {@code to} along the track, on a wall top, apart from the others. */
    private static void sign(TrackRaster t, List<SignText> out, List<double[]> used, double from, double to,
                             List<String> lines) {
        for (double s = from; s <= to; s += 1) {
            if (s <= 0) {
                continue;
            }
            boolean crowded = false;
            for (double[] u : used) {
                crowded |= Math.abs(u[0] - s) < 6;
            }
            if (crowded) {
                return;
            }
            int[] spot = t.signSpot(s);
            if (spot == null) {
                continue;
            }
            out.add(new SignText(t.half.minX() + spot[0], spot[1], t.half.minZ() + spot[2], Palette.sign(spot[3]),
                    lines));
            used.add(new double[]{s});
            return;
        }
    }
}
