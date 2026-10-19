package com.dierks.homecraft.games.gen.boat;

import java.util.Locale;

/**
 * What a Mountain Run v2 of one style and tier is made of (MOUNTAIN-V2-SPEC §3.3, §3.4, §5.3, §5.4): the
 * lane, the model-time window, the bands, the radii, the beats the flow score asks for, the drops and
 * the pieces. The planner's tables in one place, so {@code Frame}, {@code Skeleton}, {@code DropPlan},
 * {@code FlowScore} and {@code PiecesV4} read the same numbers and a test can print them.
 *
 * <p>Fun constants, not proof: what the proof checks of a tier (P, B, the drop and descent caps, the
 * descent floor) is restated here only so the planner aims inside it; the validator owns it
 * ({@code MountainValidator.Rules.of(tier, slalom, algo)}).
 *
 * <p><b>Per boat planner version</b> ({@link #algo}): {@link #of(BoatStyle, String)} is today's planner's
 * ({@code MountainPlanner.ALGO}); {@link #of(BoatStyle, String, int)} keeps algo 4's tables (0.37.0, the
 * 7-9 wide road) exactly, so a layout of either version comes out of the same code block for block.
 *
 * <p><b>The wide road</b> (algo {@value MountainValidator#WIDE_ALGO}, 0.37.1, MOUNTAIN-V21-SPEC D1-D3): the
 * Winding Road is 18 / 15 / 14 wide and keeps that width everywhere, every drop may be a 2-block drop, and the
 * pieces stand in the lane (only a split and a snow drift widen it, by 2 a side). A 2-block drop needs the
 * checkpoint before its lip within {@value MountainValidator#MAX_LEG_BIG_DROP} across of the one after its
 * flight zone (D2): its set-back ({@link #setBack}) + Z(2) 51 + 3 + a block's rounding is 60.5-64 on these
 * lanes, so a Big Drop's leg may be 66 (every other leg keeps 60), and it stands only on an axis-aligned
 * straight. What these tables made in the release soak (300 plans a style and tier, the median plan; algo 4's
 * 40-plan soak in brackets): the road falls 33 / 47 / 50 on Easy / Medium / Hard (22 / 36 / 45) in 23 / 30 /
 * 31 drops (18 / 25 / 29), 10 / 18 / 19 of them 2-block (4 / 12 / 16), at T_m 116 / 138 / 141 s; the Slalom
 * falls 20 / 27 / 31 (10 / 18 / 22) in 13 / 16 / 17 drops. Three times algo 4's fall doesn't fit at this
 * length: two 2-block lips stand at least Z(2) + {@code DropPlan.LIP_GAP} = 63 apart, and the straights are full
 * (D3, D15).
 *
 * <p><b>Algo 4's necks.</b> There the leg across a 2-block drop stays within 60, which is over 60 for any lane
 * wider than 7 (W / 2 + 0.5 is 5 on the easy road's 9, 7-8 on a 13-15 wide slalom), so a 2-block drop on a
 * wider lane sits in a {@value #NECK}-wide neck ({@link #NECK}); the wide road has none. Pure, immutable.
 */
public final class MountainTier {

    /** Algo 4: a 2-block drop's lip on a wider lane stands in a neck this wide (§5.4, see above); not in algo 5. */
    public static final int NECK = 7;
    /** The finish band's design z (§4.2: 572 ± 4), half-local: the finish mark 24.5 from the stand's north edge. */
    public static final int FINISH_BAND = 572;

    /** The boat planner version these tables are (4: 0.37.0; {@value MountainValidator#WIDE_ALGO}: the wide road). */
    public final int algo;
    public final BoatStyle style;
    /** {@code easy}, {@code medium} or {@code hard}. */
    public final String id;
    /** The lane (road) or corridor (slalom) width, and the pit's. */
    public final int width;
    public final int pitWidth;
    /** The model-time window, seconds (F-T). */
    public final double tMin;
    public final double tMax;
    /** Bands (traverses) N. */
    public final int bandsMin;
    public final int bandsMax;
    /** Sweepers: radius and deflection (degrees). */
    public final double sweepRMin;
    public final double sweepRMax;
    public final double sweepDegMin;
    public final double sweepDegMax;
    /** Bends (tighter arcs, only after a brake drop, a piece or a low-speed exit). */
    public final double bendRMin;
    public final double bendRMax;
    /** Hairpin links: radius range and the most a run may have (D11). */
    public final double hairRMin;
    public final double hairRMax;
    public final int hairMost;
    /** Chicanes, S-curves and long straights (>= 120) a run must have (F-F), and chicanes at most. */
    public final int chicMin;
    public final int chicMax;
    public final int sCurvesMin;
    public final int longMin;
    /** Drops: count range, the most 2-block drops, the descent target range, the cap and the proof's floor. */
    public final int dropsMin;
    public final int dropsMax;
    public final int bigMost;
    /** The most 2-block drops as a share of all drops (the tables' "<= 2/3", "<= 4/5"; 1 when only {@link #bigMost} counts). */
    public final double bigShare;
    public final int descentLo;
    public final int descentHi;
    public final int descentCap;
    public final int descentMin;
    /** Staircases (2-3 lips on one straight): fewest and most. */
    public final int stairsMin;
    public final int stairsMax;
    /** The proof's narrowest passage P and ice line B. */
    public final int proofP;
    public final int proofB;
    /** Road medium: boost strips; road hard: blue straights (fewest, most). */
    public final int boostMin;
    public final int boostMax;
    public final int blueMin;
    public final int blueMax;
    /** Pieces (fewest, most): sand pits (the wide road's snow drifts), splits, caves, tunnels, forests. */
    public final int[] pits;
    public final int[] splits;
    public final int[] caves;
    public final int[] tunnels;
    public final int[] forests;
    /** Slalom: the gate opening, the spacing range and gates a set. */
    public final int gate;
    public final double gapMin;
    public final double gapMax;
    public final int gatesMin;
    public final int gatesMax;
    /** The model's typical average, blocks a second, for aiming a frame's length (§3.3). */
    public final double vEstimate;

    private MountainTier(int algo, BoatStyle style, String id, int width, int pitWidth, double tMin, double tMax,
                         int bandsMin, int bandsMax, double sweepRMin, double sweepRMax, double sweepDegMin,
                         double sweepDegMax, double bendRMin, double bendRMax, double hairRMin, double hairRMax,
                         int hairMost, int chicMin, int chicMax, int sCurvesMin, int longMin, int dropsMin,
                         int dropsMax, int bigMost, double bigShare, int descentLo, int descentHi, int descentCap,
                         int descentMin, int stairsMin, int stairsMax, int proofP, int proofB, int boostMin,
                         int boostMax, int blueMin, int blueMax, int[] pits, int[] splits, int[] caves, int[] tunnels,
                         int[] forests, int gate, double gapMin, double gapMax, int gatesMin, int gatesMax,
                         double vEstimate) {
        this.algo = algo;
        this.style = style;
        this.id = id;
        this.width = width;
        this.pitWidth = pitWidth;
        this.tMin = tMin;
        this.tMax = tMax;
        this.bandsMin = bandsMin;
        this.bandsMax = bandsMax;
        this.sweepRMin = sweepRMin;
        this.sweepRMax = sweepRMax;
        this.sweepDegMin = sweepDegMin;
        this.sweepDegMax = sweepDegMax;
        this.bendRMin = bendRMin;
        this.bendRMax = bendRMax;
        this.hairRMin = hairRMin;
        this.hairRMax = hairRMax;
        this.hairMost = hairMost;
        this.chicMin = chicMin;
        this.chicMax = chicMax;
        this.sCurvesMin = sCurvesMin;
        this.longMin = longMin;
        this.dropsMin = dropsMin;
        this.dropsMax = dropsMax;
        this.bigMost = bigMost;
        this.bigShare = bigShare;
        this.descentLo = descentLo;
        this.descentHi = descentHi;
        this.descentCap = descentCap;
        this.descentMin = descentMin;
        this.stairsMin = stairsMin;
        this.stairsMax = stairsMax;
        this.proofP = proofP;
        this.proofB = proofB;
        this.boostMin = boostMin;
        this.boostMax = boostMax;
        this.blueMin = blueMin;
        this.blueMax = blueMax;
        this.pits = pits;
        this.splits = splits;
        this.caves = caves;
        this.tunnels = tunnels;
        this.forests = forests;
        this.gate = gate;
        this.gapMin = gapMin;
        this.gapMax = gapMax;
        this.gatesMin = gatesMin;
        this.gatesMax = gatesMax;
        this.vEstimate = vEstimate;
    }

    private static final int[] NONE = {0, 0};

    /** The Winding Road's three tiers and the Slalom's, the wide road's (algo 5, MOUNTAIN-V21-SPEC §2.1). */
    private static final MountainTier ROAD_EASY = new MountainTier(5, BoatStyle.ROAD, "easy", 18, 18, 100, 130, 5, 6,
            70, 160, 15, 45, 40, 55, 38, 46, 2, 0, 0, 2, 2, 18, 28, 28, 1, 32, 44, 48, 30, 1, 1, 5, 5, 0, 0, 0, 0,
            new int[]{1, 1}, new int[]{1, 2}, new int[]{1, 1}, NONE, NONE, 0, 0, 0, 0, 0, 25.5);
    private static final MountainTier ROAD_MEDIUM = new MountainTier(5, BoatStyle.ROAD, "medium", 15, 15, 120, 150, 6,
            7, 55, 150, 15, 50, 32, 55, 32, 40, 3, 1, 2, 3, 2, 24, 38, 38, 1, 48, 64, 70, 46, 1, 2, 4, 4, 0, 1,
            0, 0, new int[]{2, 2}, new int[]{1, 2}, new int[]{0, 1}, new int[]{0, 1}, new int[]{1, 1}, 0, 0, 0, 0, 0,
            24.5);
    private static final MountainTier ROAD_HARD = new MountainTier(5, BoatStyle.ROAD, "hard", 14, 14, 120, 150, 6, 8,
            60, 180, 15, 50, 30, 55, 30, 38, 4, 1, 3, 3, 3, 28, 40, 40, 1, 52, 68, 74, 48, 2, 2, 4, 3, 0, 0,
            1, 2, new int[]{2, 3}, new int[]{2, 2}, NONE, new int[]{2, 2}, new int[]{1, 2}, 0, 0, 0, 0, 0, 25.5);
    private static final MountainTier SLALOM_EASY = new MountainTier(5, BoatStyle.SLALOM, "easy", 15, 15, 90, 120, 5,
            6, 80, 160, 12, 40, 34, 60, 34, 44, 3, 0, 0, 0, 0, 8, 14, 14, 1, 18, 26, 28, 18, 0, 0, 7, 7, 0, 0, 0, 0,
            NONE, NONE, NONE, NONE, NONE, 8, 30, 36, 4, 6, 26.0);
    private static final MountainTier SLALOM_MEDIUM = new MountainTier(5, BoatStyle.SLALOM, "medium", 13, 13, 105,
            135, 5, 6, 80, 160, 12, 40, 34, 60, 34, 44, 3, 0, 0, 0, 0, 12, 20, 20, 1, 26, 38, 40, 26, 0, 1, 5, 5,
            0, 0, 0, 0, NONE, NONE, NONE, NONE, NONE, 6, 24, 30, 6, 9, 24.5);
    private static final MountainTier SLALOM_HARD = new MountainTier(5, BoatStyle.SLALOM, "hard", 11, 11, 105, 135,
            5, 6, 80, 160, 12, 40, 34, 60, 34, 44, 3, 0, 0, 0, 0, 14, 22, 22, 1, 30, 42, 44, 30, 1, 2, 4, 4, 0,
            0, 0, 0, NONE, NONE, NONE, NONE, NONE, 5, 20, 26, 7, 11, 24.0);

    /**
     * Algo 4's tiers (0.37.0, MOUNTAIN-V2-SPEC §5.3, §5.4), frozen: the tables its layouts were made from, so the
     * same code still makes them block for block.
     */
    private static final MountainTier ROAD_EASY_V4 = new MountainTier(4, BoatStyle.ROAD, "easy", 9, 9, 90, 120, 5, 6,
            70, 160, 15, 45, 40, 55, 38, 46, 2, 0, 0, 2, 2, 16, 22, 4, 1, 18, 26, 28, 16, 1, 1, 5, 5, 0, 0, 0, 0,
            new int[]{1, 1}, new int[]{1, 2}, new int[]{1, 1}, NONE, NONE, 0, 0, 0, 0, 0, 25.5);
    private static final MountainTier ROAD_MEDIUM_V4 = new MountainTier(4, BoatStyle.ROAD, "medium", 7, 7, 105, 135,
            6, 7, 55, 150, 15, 50, 32, 55, 32, 40, 3, 1, 2, 3, 2, 22, 30, 30, 2.0 / 3, 36, 50, 52, 32, 1, 2, 4, 4,
            1, 3, 0, 0, new int[]{2, 2}, new int[]{1, 2}, new int[]{0, 1}, new int[]{0, 1}, new int[]{1, 1}, 0, 0, 0,
            0, 0, 24.5);
    private static final MountainTier ROAD_HARD_V4 = new MountainTier(4, BoatStyle.ROAD, "hard", 7, 7, 105, 135, 6, 8,
            60, 180, 15, 50, 30, 55, 30, 38, 4, 1, 3, 3, 3, 26, 34, 34, 4.0 / 5, 46, 61, 64, 44, 2, 2, 4, 3, 0, 0,
            2, 4, new int[]{2, 3}, new int[]{2, 2}, NONE, new int[]{2, 2}, new int[]{1, 2}, 0, 0, 0, 0, 0, 25.5);
    private static final MountainTier SLALOM_EASY_V4 = new MountainTier(4, BoatStyle.SLALOM, "easy", 15, 15, 90, 120,
            5, 6, 80, 160, 12, 40, 34, 60, 34, 44, 3, 0, 0, 0, 0, 8, 12, 0, 0, 10, 14, 16, 8, 0, 0, 7, 7, 0, 0, 0,
            0, NONE, NONE, NONE, NONE, NONE, 8, 30, 36, 4, 6, 26.0);
    private static final MountainTier SLALOM_MEDIUM_V4 = new MountainTier(4, BoatStyle.SLALOM, "medium", 13, 13, 105,
            135, 5, 6, 80, 160, 12, 40, 34, 60, 34, 44, 3, 0, 0, 0, 0, 12, 18, 12, 2.0 / 3, 18, 30, 34, 16, 0, 1,
            5, 5, 0, 0, 0, 0, NONE, NONE, NONE, NONE, NONE, 6, 24, 30, 6, 9, 24.5);
    private static final MountainTier SLALOM_HARD_V4 = new MountainTier(4, BoatStyle.SLALOM, "hard", 11, 11, 105, 135,
            5, 6, 80, 160, 12, 40, 34, 60, 34, 44, 3, 0, 0, 0, 0, 14, 20, 16, 4.0 / 5, 22, 36, 40, 20, 1, 2, 4, 4,
            0, 0, 0, 0, NONE, NONE, NONE, NONE, NONE, 5, 20, 26, 7, 11, 24.0);

    /**
     * The tier {@code id} ({@code easy}, {@code medium}, {@code hard}, any case) of {@code style} as today's planner
     * makes it ({@code MountainPlanner.ALGO}), or {@code null}.
     */
    public static MountainTier of(BoatStyle style, String id) {
        return of(style, id, MountainPlanner.ALGO);
    }

    /**
     * The tier {@code id} of {@code style} as boat planner {@code algo} made it: from
     * {@value MountainValidator#WIDE_ALGO} the wide road's tables, before it algo 4's; {@code null} for a word
     * that isn't a tier.
     */
    public static MountainTier of(BoatStyle style, String id, int algo) {
        if (style == null || id == null) {
            return null;
        }
        String w = id.trim().toLowerCase(Locale.ROOT);
        boolean road = style == BoatStyle.ROAD;
        if (algo < MountainValidator.WIDE_ALGO) {
            return switch (w) {
                case "easy" -> road ? ROAD_EASY_V4 : SLALOM_EASY_V4;
                case "medium" -> road ? ROAD_MEDIUM_V4 : SLALOM_MEDIUM_V4;
                case "hard" -> road ? ROAD_HARD_V4 : SLALOM_HARD_V4;
                default -> null;
            };
        }
        return switch (w) {
            case "easy" -> road ? ROAD_EASY : SLALOM_EASY;
            case "medium" -> road ? ROAD_MEDIUM : SLALOM_MEDIUM;
            case "hard" -> road ? ROAD_HARD : SLALOM_HARD;
            default -> null;
        };
    }

    /**
     * Whether these are the wide road's tables and rules (algo {@value MountainValidator#WIDE_ALGO} on, 0.37.1): no
     * necks, a Big Drop's leg up to {@value MountainValidator#MAX_LEG_BIG_DROP}, the road's tight run-up, snow.
     */
    public boolean wide() {
        return algo >= MountainValidator.WIDE_ALGO;
    }

    public boolean road() {
        return style == BoatStyle.ROAD;
    }

    public boolean slalom() {
        return style == BoatStyle.SLALOM;
    }

    public boolean easy() {
        return id.equals("easy");
    }

    public boolean hard() {
        return id.equals("hard");
    }

    /** The most 2-block drops a run of {@code drops} lips may have. */
    public int bigFor(int drops) {
        return Math.min(bigMost, (int) Math.floor(drops * bigShare + 1e-9));
    }

    /** The middle of the model-time window, seconds. */
    public double tMid() {
        return (tMin + tMax) / 2;
    }

    /** A straight landing strip after a drop of {@code d} (v3's fun constants: packed 22/26, blue 33/41). */
    public static int landing(int d, boolean blue) {
        if (blue) {
            return d >= 2 ? 41 : 33;
        }
        return d >= 2 ? 26 : 22;
    }

    /**
     * How far before a lip the checkpoint ahead of it stands on a lane of {@code width} (DropPlan.before): its
     * sphere's radius (W / 2 + 0.5 on an axis-aligned straight, + ARC_SPOT elsewhere), at least CLEAR + 0.5,
     * plus a block's rounding.
     */
    public static double setBack(double width, boolean axis) {
        double rad = spot(width) + (axis ? 0 : RasterV4.ARC_SPOT);
        return Math.max(rad, DropPlan.CLEAR + 0.5) + DropPlan.ROUNDING;
    }

    /**
     * The room before a lip that a gate field or a piece must leave (0.37.0's run-up, kept): at least 8 and W + 3.
     * It is sized for the checkpoint before the lip (its set-back, its sphere grown by GROW, and the raster's
     * 1.5 or 3 margin), with 0.75 to spare against a piece on an axis-aligned straight at any width. Algo 4's
     * run-up of every lip, on its lane's width there (a neck's for a necked 2-block drop).
     */
    public static int clearBefore(double width) {
        return (int) Math.max(8, Math.ceil(width) + 3);
    }

    /**
     * A lip's flat straight run-up: on the Winding Road at least 8 and 2 past the checkpoint before it (0.37.1);
     * on the Slalom {@link #clearBefore} of its width, as in 0.37.0. Algo 4 ran every lip up
     * {@link #clearBefore} of the lane there (this for an unnecked lip; {@code DropPlan} gives a necked one the
     * neck's).
     */
    public int runUp(double setBack) {
        return wide() && road() ? (int) Math.max(8, Math.ceil(setBack) + 2) : clearBefore(width);
    }

    /**
     * The straight an elbows link's plunge needs to hold a lip (Frame, Skeleton): sized for a Big Drop on an
     * axis-aligned plunge, the only place one may stand on the wide road (D2; a slanted plunge of that length
     * still holds a hop): its run-up, its packed landing strip and 2.
     */
    public int lipPlunge() {
        return runUp(setBack(width, true)) + landing(2, false) + 2;
    }

    /** A checkpoint's radius on a straight of this width: w / 2 + 0.5, so it spans the lane. */
    public static double spot(double width) {
        return width / 2.0 + 0.5;
    }

    @Override
    public String toString() {
        return style.id() + " " + id;
    }
}
