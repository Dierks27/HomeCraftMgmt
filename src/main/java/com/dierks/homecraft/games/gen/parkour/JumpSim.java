package com.dierks.homecraft.games.gen.parkour;

/**
 * How far a player can jump (GEN-SPEC §4.1): vanilla player movement, ported tick by tick, pure.
 *
 * <p><b>Why simulate instead of using a table from a wiki.</b> Every parkour layout is proven
 * solvable before a block is set, and the proof is only as good as its reach numbers. Porting the
 * movement step gives every reach for every height difference (a skip onto a pad 20 blocks lower
 * needs one too), and a test pins it to the spec's table (±0.05) and to vanilla's jump peak.
 *
 * <p><b>The model</b>, per tick, as §4.1 gives it:
 * <ul>
 *   <li>ground: speed 0.10 walking or 0.13 sprinting, times the input's 0.98, added to the
 *       velocity; after the move the velocity keeps 0.6 x 0.91 (block friction times drag);</li>
 *   <li>jump: vertical speed 0.42, plus a 0.2 push forward when sprinting; the jump tick still
 *       moves with ground speed and ground friction (the player was on the ground when it began);</li>
 *   <li>air: 0.02 walking or 0.026 sprinting added, drag 0.91; gravity 0.08 after the move, then
 *       x 0.98 vertical drag;</li>
 *   <li>a hitbox 0.6 wide.</li>
 * </ul>
 *
 * <p><b>Edge to edge.</b> Vanilla resolves a move's vertical part first and sets "on the ground"
 * from it, so the tick that carries a runner off the edge still counts as on the ground: the jump
 * can come one ground step past the last supported position. The landing, also vertical first,
 * happens at the last airborne position still at or above the landing height, where the hitbox
 * must reach over the landing block. So a gap of D blocks is reachable when
 * {@code step + airborne distance + 0.6 > D}: the hitbox overhangs both edges by 0.3. That is the
 * table of §4.1, and why a jump pressed one tick early loses one ground step ({@link #tickLoss}).
 *
 * <p>No clock, no randomness, no Bukkit: the same numbers on every host.
 */
public final class JumpSim {

    /** How the player moves on the run-up and in the air. */
    public enum Mode {
        WALK(0.10, 0.02),
        SPRINT(0.13, 0.026);

        private final double ground;
        private final double air;

        Mode(double ground, double air) {
            this.ground = ground;
            this.air = air;
        }

        /** What one ground tick adds to the velocity (speed times the 0.98 input). */
        double groundAccel() {
            return ground * INPUT;
        }

        /** What one airborne tick adds. */
        double airAccel() {
            return air;
        }
    }

    /** The player's hitbox, wide and deep. */
    public static final double HITBOX = 0.6;
    /** Vanilla's jump speed. */
    public static final double JUMP_SPEED = 0.42;
    /** The sprint jump's push forward. */
    public static final double SPRINT_BOOST = 0.2;
    /** Gravity per tick, and what the vertical speed keeps after it. */
    public static final double GRAVITY = 0.08;
    public static final double VERTICAL_DRAG = 0.98;
    /** Horizontal drag in the air, and on a normal block (0.6 friction x 0.91). */
    public static final double AIR_DRAG = 0.91;
    public static final double GROUND_DRAG = 0.6 * 0.91;
    /** Movement input is scaled by this before it is applied. */
    public static final double INPUT = 0.98;
    /** Ticks of run-up that count as "a full run-up" (the speed has long since settled). */
    public static final int FULL_RUN_UP = 60;
    /** The lowest landing {@link #reach} works out ahead of time (lower ones are simulated when asked). */
    public static final int TABLE_LOW = -64;

    private static final double[] WALK_TABLE = table(Mode.WALK);
    private static final double[] SPRINT_TABLE = table(Mode.SPRINT);
    private static final double PEAK = computePeak();

    private JumpSim() {
    }

    /**
     * The widest gap, edge to edge, a player can clear onto a landing {@code dy} blocks above the
     * take-off (negative: below), from a full run-up and a jump at the last possible tick; 0 when
     * the landing is higher than the jump's peak. Whole-block drops come from a table.
     */
    public static double reach(double dy, Mode mode) {
        if (dy > PEAK) {
            return 0;
        }
        if (dy == Math.rint(dy) && dy >= TABLE_LOW && dy <= 2) {
            double[] t = mode == Mode.WALK ? WALK_TABLE : SPRINT_TABLE;
            return t[(int) dy - TABLE_LOW];
        }
        return reachAfter(dy, mode, FULL_RUN_UP);
    }

    /** Sprint {@link #reach}. */
    public static double sprintReach(double dy) {
        return reach(dy, Mode.SPRINT);
    }

    /** Walk {@link #reach}. */
    public static double walkReach(double dy) {
        return reach(dy, Mode.WALK);
    }

    /**
     * {@link #reach} after {@code runUpTicks} ground ticks from standing still (at least one: the
     * tick that leaves the edge).
     */
    public static double reachAfter(double dy, Mode mode, int runUpTicks) {
        double v = 0;
        double step = 0;
        for (int i = 0; i < Math.max(1, runUpTicks); i++) {
            step = v + mode.groundAccel();
            v = step * GROUND_DRAG;
        }
        return jump(dy, mode, v, step);
    }

    /**
     * {@link #reach} from standing still with only {@code runUp} blocks to run: the centre may move
     * that far before the last tick that starts on the take-off block (a pad of length L, standing
     * at its back edge, gives L + 0.6). The player starts where that last tick begins exactly at
     * the edge; what the run-up is too short for is lost.
     */
    public static double reachFromRest(double dy, Mode mode, double runUp) {
        double v = 0;
        double moved = 0;
        double best = 0;
        for (int tick = 0; tick < FULL_RUN_UP && moved <= runUp; tick++) {
            double step = v + mode.groundAccel();
            double after = step * GROUND_DRAG;
            best = jump(dy, mode, after, step);
            moved += step;
            v = after;
        }
        return best;
    }

    /** What pressing jump one tick early costs: one ground step at full speed. */
    public static double tickLoss(Mode mode) {
        return mode.groundAccel() / (1 - GROUND_DRAG);
    }

    /** The highest a jump lifts the feet: 1.2522 in vanilla. */
    public static double peak() {
        return PEAK;
    }

    /**
     * One jump: the run-up left the velocity at {@code v} after a last ground step of
     * {@code step}; returns step + the airborne distance to the last position at or above
     * {@code dy} + the hitbox, or 0 when it never gets that high.
     */
    private static double jump(double dy, Mode mode, double v, double step) {
        double x = 0;
        double y = 0;
        double vy = JUMP_SPEED;
        double vx = v + (mode == Mode.SPRINT ? SPRINT_BOOST : 0) + mode.groundAccel();
        x += vx;
        y += vy;
        vx *= GROUND_DRAG;
        vy = (vy - GRAVITY) * VERTICAL_DRAG;
        double last = y >= dy - 1e-9 ? x : Double.NaN;
        for (int tick = 0; tick < 2_000; tick++) {
            vx += mode.airAccel();
            x += vx;
            y += vy;
            vx *= AIR_DRAG;
            vy = (vy - GRAVITY) * VERTICAL_DRAG;
            if (y >= dy - 1e-9) {
                last = x;
            } else if (vy < 0) {
                break;
            }
        }
        return Double.isNaN(last) ? 0 : step + last + HITBOX;
    }

    private static double[] table(Mode mode) {
        double[] t = new double[2 - TABLE_LOW + 1];
        for (int dy = TABLE_LOW; dy <= 2; dy++) {
            t[dy - TABLE_LOW] = reachAfter(dy, mode, FULL_RUN_UP);
        }
        return t;
    }

    private static double computePeak() {
        double y = 0;
        double vy = JUMP_SPEED;
        double peak = 0;
        while (vy > 0) {
            y += vy;
            peak = Math.max(peak, y);
            vy = (vy - GRAVITY) * VERTICAL_DRAG;
        }
        return peak;
    }
}
