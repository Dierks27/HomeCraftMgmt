package com.dierks.homecraft.games.gen.boat;

import java.util.Locale;

/**
 * What a Mountain Run v2 of one style and tier is made of (MOUNTAIN-V2-SPEC §3.3, §3.4, §5.3, §5.4): the
 * lane, the model-time window, the bands, the radii, the beats the flow score asks for, the drops and
 * the pieces. The planner's tables in one place, so {@code Frame}, {@code Skeleton}, {@code DropPlan},
 * {@code FlowScore} and {@code PiecesV4} read the same numbers and a test can print them.
 *
 * <p>Fun constants, not proof: what the proof checks of a tier (P, B, the drop and descent caps, the
 * descent floor) is restated here only so the planner aims inside it; the validator owns it.
 *
 * <p><b>Where the tables bend.</b> A slalom 2-block drop needs the checkpoint before its lip within
 * 60 across of the one after its flight zone; with a checkpoint radius of W / 2 + 0.5 (7-8 on a 13-15
 * wide corridor) that leg is 61-62 long, so a slalom's 2-block drop sits in a 9-wide neck
 * ({@link #NECK}); easy slaloms keep 1-block drops. Pure, immutable.
 */
public final class MountainTier {

    /** A slalom 2-block drop's lip stands in a neck this wide (§5.4, see above). */
    public static final int NECK = 9;
    /** The finish band's design z (§4.2: 572 ± 4), half-local: the finish mark 24.5 from the stand's north edge. */
    public static final int FINISH_BAND = 572;

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
    /** Pieces (fewest, most): sand pits, splits, caves, tunnels, forests. */
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

    private MountainTier(BoatStyle style, String id, int width, int pitWidth, double tMin, double tMax, int bandsMin,
                         int bandsMax, double sweepRMin, double sweepRMax, double sweepDegMin, double sweepDegMax,
                         double bendRMin, double bendRMax, double hairRMin, double hairRMax, int hairMost,
                         int chicMin, int chicMax, int sCurvesMin, int longMin, int dropsMin, int dropsMax,
                         int bigMost, double bigShare, int descentLo, int descentHi, int descentCap, int descentMin,
                         int stairsMin, int stairsMax, int proofP, int proofB, int boostMin, int boostMax, int blueMin,
                         int blueMax, int[] pits, int[] splits, int[] caves, int[] tunnels, int[] forests, int gate,
                         double gapMin, double gapMax, int gatesMin, int gatesMax, double vEstimate) {
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

    /** The Winding Road's three tiers (§5.3) and the Slalom's (§5.4). */
    private static final MountainTier ROAD_EASY = new MountainTier(BoatStyle.ROAD, "easy", 9, 9, 90, 120, 5, 6,
            70, 160, 15, 45, 40, 55, 38, 46, 2, 0, 0, 2, 2, 16, 22, 4, 1, 18, 26, 28, 16, 1, 1, 5, 5, 0, 0, 0, 0,
            new int[]{1, 1}, new int[]{1, 2}, new int[]{1, 1}, NONE, NONE, 0, 0, 0, 0, 0, 25.5);
    private static final MountainTier ROAD_MEDIUM = new MountainTier(BoatStyle.ROAD, "medium", 7, 7, 105, 135, 6, 7,
            55, 150, 15, 50, 32, 55, 32, 40, 3, 1, 2, 3, 2, 22, 30, 30, 2.0 / 3, 36, 50, 52, 32, 1, 2, 4, 4, 1, 3,
            0, 0, new int[]{2, 2}, new int[]{1, 2}, new int[]{0, 1}, new int[]{0, 1}, new int[]{1, 1}, 0, 0, 0, 0, 0,
            24.5);
    private static final MountainTier ROAD_HARD = new MountainTier(BoatStyle.ROAD, "hard", 7, 7, 105, 135, 6, 8,
            60, 180, 15, 50, 30, 55, 30, 38, 4, 1, 3, 3, 3, 26, 34, 34, 4.0 / 5, 46, 61, 64, 44, 2, 2, 4, 3, 0, 0,
            2, 4, new int[]{2, 3}, new int[]{2, 2}, NONE, new int[]{2, 2}, new int[]{1, 2}, 0, 0, 0, 0, 0, 25.5);
    private static final MountainTier SLALOM_EASY = new MountainTier(BoatStyle.SLALOM, "easy", 15, 15, 90, 120, 5, 6,
            80, 160, 12, 40, 34, 60, 34, 44, 3, 0, 0, 0, 0, 8, 12, 0, 0, 10, 14, 16, 8, 0, 0, 7, 7, 0, 0, 0, 0,
            NONE, NONE, NONE, NONE, NONE, 8, 30, 36, 4, 6, 26.0);
    private static final MountainTier SLALOM_MEDIUM = new MountainTier(BoatStyle.SLALOM, "medium", 13, 13, 105, 135,
            5, 6, 80, 160, 12, 40, 34, 60, 34, 44, 3, 0, 0, 0, 0, 12, 18, 12, 2.0 / 3, 18, 30, 34, 16, 0, 1, 5, 5,
            0, 0, 0, 0, NONE, NONE, NONE, NONE, NONE, 6, 24, 30, 6, 9, 24.5);
    private static final MountainTier SLALOM_HARD = new MountainTier(BoatStyle.SLALOM, "hard", 11, 11, 105, 135, 5,
            6, 80, 160, 12, 40, 34, 60, 34, 44, 3, 0, 0, 0, 0, 14, 20, 16, 4.0 / 5, 22, 36, 40, 20, 1, 2, 4, 4, 0,
            0, 0, 0, NONE, NONE, NONE, NONE, NONE, 5, 20, 26, 7, 11, 24.0);

    /** The tier {@code id} ({@code easy}, {@code medium}, {@code hard}, any case) of {@code style}, or {@code null}. */
    public static MountainTier of(BoatStyle style, String id) {
        if (style == null || id == null) {
            return null;
        }
        String w = id.trim().toLowerCase(Locale.ROOT);
        boolean road = style == BoatStyle.ROAD;
        return switch (w) {
            case "easy" -> road ? ROAD_EASY : SLALOM_EASY;
            case "medium" -> road ? ROAD_MEDIUM : SLALOM_MEDIUM;
            case "hard" -> road ? ROAD_HARD : SLALOM_HARD;
            default -> null;
        };
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

    /** A drop's flat straight run-up: at least 8, and room for the checkpoint before it (v3's rule). */
    public static int runUp(double width) {
        return (int) Math.max(8, Math.ceil(width) + 3);
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
