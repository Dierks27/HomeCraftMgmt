package com.dierks.homecraft.games.gen.parkour;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Which jumps each Daily Parkour tier may use (GEN-SPEC §4.1, the rule table), shared by the
 * planner that picks them and the validator that re-checks them.
 *
 * <p><b>A jump</b> is measured between the two pads' footprints: {@code dy} is the landing top
 * minus the take-off top, and D the edge-to-edge gap {@code sqrt(gx² + gz²)}, where gx and gz are
 * the whole blocks of air between the footprints along x and z. A straight jump has one of them
 * 0 (the pads overlap or touch sideways) or a side gap of at most 1 block; a diagonal one (after a
 * 45° turn) has both equal. Everything else is refused, so every jump reads as "straight ahead"
 * or "straight across the corner".
 *
 * <p><b>Why these rows are safe.</b> Every allowed row keeps {@code reach(dy, mode) − D} at least
 * the tier's margin ({@link JumpSim}), and every margin is larger than the one ground step a jump
 * pressed a tick early loses. Easy never needs a sprint (good on touch screens); Hard never has a
 * flat 4-gap, because Bedrock movement is close to Java's but not identical. A test checks the
 * whole table against the simulation.
 *
 * <p><b>No shortcuts.</b> Pads that aren't next to each other on the path are kept apart by at
 * least {@code max(6, 1 + sprintReach(dy))} ({@link #minSkipGap}), so nobody can jump past a pad,
 * and a player who lands anywhere is on the pad they aimed for. Around a turn the pads either side
 * of the turn pad are necessarily closer (a 3 x 3 checkpoint pad can't hold two pads 6 apart), so
 * there the 6 is waived but the physics isn't: they must still be out of jumping reach, with a
 * block to spare. Easy turns only on checkpoint pads, where a skip would miss the checkpoint (and
 * then nothing after it counts, the finish included). Easy needs no sprint, but a child may
 * sprint, so there the pads must be out of a perfect sprint jump's reach too: with Easy's short
 * gaps that takes a +1 step in or out of a turning checkpoint, which the planner finds.
 */
public final class JumpRules {

    /** The three tiers and everything that differs between them. */
    public enum Level {
        EASY("easy", JumpSim.Mode.WALK, 0.6, new double[]{0.6, 0.2, 0.2, 0.0}, 12, 3, 6,
                new Row(1, 1, 1), new Row(0, 1, 2), new Row(-1, 1, 2)),
        MEDIUM("medium", JumpSim.Mode.SPRINT, 0.9, new double[]{0.4, 0.3, 0.2, 0.1}, 20, 4, 14,
                new Row(1, 1, 2), new Row(0, 2, 3), new Row(-1, 2, 3), new Row(-2, 2, 3)),
        HARD("hard", JumpSim.Mode.SPRINT, 0.35, new double[]{0.3, 0.4, 0.2, 0.1}, 28, 5, 24,
                new Row(1, 1, 3), new Row(0, 2, 3), new Row(-1, 1, 4), new Row(-2, 1, 4));

        private final String id;
        private final JumpSim.Mode mode;
        private final double margin;
        private final double[] dyWeights;
        private final int jumps;
        private final int checkpointEvery;
        private final int band;
        private final List<Row> rows;

        Level(String id, JumpSim.Mode mode, double margin, double[] dyWeights, int jumps, int checkpointEvery,
              int band, Row... rows) {
            this.id = id;
            this.mode = mode;
            this.margin = margin;
            this.dyWeights = dyWeights;
            this.jumps = jumps;
            this.checkpointEvery = checkpointEvery;
            this.band = band;
            this.rows = List.of(rows);
        }

        /** The config word ({@code easy}). */
        public String id() {
            return id;
        }

        /** How the jumps are made: Easy walks, the others sprint. */
        public JumpSim.Mode mode() {
            return mode;
        }

        /** The least {@code reach − D} any allowed jump keeps. */
        public double margin() {
            return margin;
        }

        /** How likely each height step is, in {@link #DYS} order. */
        public double[] dyWeights() {
            return dyWeights.clone();
        }

        /** Jumps from the start pad to the finish pad. */
        public int jumps() {
            return jumps;
        }

        /** Every this many jumps the landing is a checkpoint pad. */
        public int checkpointEvery() {
            return checkpointEvery;
        }

        /** The checkpoints between the start and the finish. */
        public int checkpoints() {
            return (jumps - 1) / checkpointEvery;
        }

        /** How far apart the lowest and the highest pad top may be. */
        public int band() {
            return band;
        }

        /** The allowed rows. */
        public List<Row> rows() {
            return rows;
        }

        /** The row for {@code dy}, or {@code null} when that height step isn't allowed. */
        public Row row(int dy) {
            for (Row r : rows) {
                if (r.dy() == dy) {
                    return r;
                }
            }
            return null;
        }

        /** Whether the course falls back at a fixed height (Easy) rather than {@code trials.fall_depth} per leg. */
        public boolean fixedFall() {
            return this == EASY;
        }

        /** Whether its jumps may go diagonally (after a 45° turn). */
        public boolean diagonals() {
            return this != EASY;
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

    /**
     * One allowed height step and the gaps allowed with it, both ends included.
     *
     * @param dy   landing top minus take-off top
     * @param minD the smallest edge-to-edge gap
     * @param maxD the largest
     */
    public record Row(int dy, double minD, double maxD) {
    }

    /** The height steps, in the order {@link Level#dyWeights()} weighs them. */
    public static final int[] DYS = {0, 1, -1, -2};

    /** Pads not next to each other on the path are at least this far apart (edge to edge)... */
    public static final double SKIP_FLOOR = 6;
    /** ...except either side of a turn pad, which need only this (and be out of reach). */
    public static final double TURN_SKIP_FLOOR = 3;
    /**
     * Around an Easy turn the pads either side of the checkpoint are kept this much beyond a
     * perfect sprint jump's reach (a 3 x 3 checkpoint pad leaves no room for a whole block).
     */
    public static final double EASY_TURN_SKIP_MARGIN = 0.15;
    /** Easy lands on a pad that overlaps the one it left by at least this many rows. */
    public static final int EASY_OVERLAP = 2;

    private static final double EPS = 1e-9;

    private JumpRules() {
    }

    /** The edge-to-edge gap for whole-block gaps gx and gz. */
    public static double gap(int gx, int gz) {
        return Math.sqrt((double) gx * gx + (double) gz * gz);
    }

    /**
     * Whether a jump of this shape is one {@code level} allows.
     *
     * @param dy      landing top minus take-off top
     * @param gx      whole blocks of air between the footprints along x (0 when they overlap or touch)
     * @param gz      the same along z
     * @param overlap how many rows the footprints share sideways (0 when they don't)
     */
    public static boolean allowed(Level level, int dy, int gx, int gz, int overlap) {
        return check(level, dy, gx, gz, overlap) == OK;
    }

    /** Why a jump of this shape isn't allowed, or {@code null} when it is. */
    public static String problem(Level level, int dy, int gx, int gz, int overlap) {
        return switch (check(level, dy, gx, gz, overlap)) {
            case OK -> null;
            case TOUCH -> "the pads touch";
            case NO_ROW -> "a height step of " + signed(dy) + " isn't allowed in " + level.id();
            case GAP -> {
                Row row = level.row(dy);
                yield signed(dy) + " over " + fmt(gap(gx, gz)) + " isn't allowed in " + level.id() + " (" + signed(dy)
                        + " allows " + fmt(row.minD()) + "-" + fmt(row.maxD()) + ")";
            }
            case DIAGONAL -> "a diagonal jump isn't allowed in " + level.id();
            case SIDE -> "a side gap of " + Math.min(gx, gz) + " is more than 1";
            default -> "an easy jump lands on a pad straight ahead (sharing " + EASY_OVERLAP + " rows)";
        };
    }

    private static final int OK = 0;
    private static final int TOUCH = 1;
    private static final int NO_ROW = 2;
    private static final int GAP = 3;
    private static final int DIAGONAL = 4;
    private static final int SIDE = 5;
    private static final int STRAIGHT = 6;

    private static int check(Level level, int dy, int gx, int gz, int overlap) {
        if (gx < 0 || gz < 0 || gx + gz == 0) {
            return TOUCH;
        }
        Row row = level.row(dy);
        if (row == null) {
            return NO_ROW;
        }
        double d = gap(gx, gz);
        if (d < row.minD() - EPS || d > row.maxD() + EPS) {
            return GAP;
        }
        if (gx == gz) {
            return level.diagonals() ? OK : DIAGONAL;
        }
        int side = Math.min(gx, gz);
        if (side > 1) {
            return SIDE;
        }
        if (level == Level.EASY && (side > 0 || overlap < EASY_OVERLAP)) {
            return STRAIGHT;
        }
        return OK;
    }

    /**
     * The margin a jump keeps: its tier's reach for {@code dy} minus the gap. Negative when it
     * can't be made at all.
     */
    public static double margin(Level level, int dy, double d) {
        return JumpSim.reach(dy, level.mode()) - d;
    }

    /**
     * How far apart (edge to edge) two pads that aren't next to each other on the path must be.
     *
     * @param dy         the later pad's top minus the earlier pad's
     * @param aroundTurn the two pads are either side of one turn pad
     * @param dIn        around a turn: the gap of the jump onto the turn pad (the skip must be longer)
     */
    public static double minSkipGap(Level level, double dy, boolean aroundTurn, double dIn) {
        if (!aroundTurn) {
            return Math.max(SKIP_FLOOR, 1 + JumpSim.sprintReach(dy));
        }
        double floor = Math.max(TURN_SKIP_FLOOR, dIn + 0.5);
        if (level == Level.EASY) {
            // Easy needs no sprint, but a child may sprint: out of a perfect sprint jump's reach
            return Math.max(floor, Math.max(1 + JumpSim.walkReach(dy), JumpSim.sprintReach(dy) + EASY_TURN_SKIP_MARGIN));
        }
        return Math.max(floor, 1 + JumpSim.sprintReach(dy));
    }

    /**
     * Whether {@code level} may turn by {@code eighths} x 45° on a pad: straight on always; Easy
     * turns 90° only on checkpoints; Medium turns 45° or 90° on pads at least 2 x 2; Hard turns
     * 45° anywhere and 90° on checkpoints. Nothing turns more than 90°.
     */
    public static boolean turnAllowed(Level level, int eighths, boolean checkpoint, int sizeA, int sizeB) {
        int t = Math.abs(eighths);
        if (t == 0) {
            return true;
        }
        if (t > 2) {
            return false;
        }
        return switch (level) {
            case EASY -> t == 2 && checkpoint;
            case MEDIUM -> Math.min(sizeA, sizeB) >= 2;
            case HARD -> t == 1 || checkpoint;
        };
    }

    /**
     * Every gap shape {@code level} allows for {@code dy}, as {along, side} ({along, along} for a
     * diagonal jump), shortest first.
     */
    public static List<int[]> shapes(Level level, int dy, boolean diagonal) {
        if (dy < -2 || dy > 1) {
            return List.of();
        }
        return SHAPES[level.ordinal()][dy + 2][diagonal ? 1 : 0];
    }

    private static final List<int[]>[][][] SHAPES = buildShapes();

    @SuppressWarnings("unchecked")
    private static List<int[]>[][][] buildShapes() {
        List<int[]>[][][] out = new List[Level.values().length][4][2];
        for (Level level : Level.values()) {
            for (int dy = -2; dy <= 1; dy++) {
                for (int diag = 0; diag < 2; diag++) {
                    List<int[]> list = new ArrayList<>();
                    for (int a = 1; a <= 5; a++) {
                        if (diag == 1) {
                            if (allowed(level, dy, a, a, 0)) {
                                list.add(new int[]{a, a});
                            }
                            continue;
                        }
                        for (int side = 0; side <= 1 && side < a; side++) {
                            if (allowed(level, dy, a, side, side == 0 ? EASY_OVERLAP : 0)) {
                                list.add(new int[]{a, side});
                            }
                        }
                    }
                    out[level.ordinal()][dy + 2][diag] = List.copyOf(list);
                }
            }
        }
        return out;
    }

    static String signed(int dy) {
        return dy > 0 ? "+" + dy : Integer.toString(dy);
    }

    static String fmt(double d) {
        return String.format(Locale.ROOT, "%.2f", d);
    }
}
