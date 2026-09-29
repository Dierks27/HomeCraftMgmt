package com.dierks.homecraft.games.gen.dropper;

/**
 * How a player falls and steers in the air (EVENTS-DROPPER-SPEC §B.1.4): vanilla's player movement,
 * ported tick by tick, pure, with no blocks in the way ({@link DropRun} adds the blocks).
 *
 * <p><b>Why simulate.</b> Every Dropper level is proven before a block is set: a witness input
 * program is drawn first and obstacles go only outside its path, then sloppy pilots are flown
 * through the real voxels. The proof is only as good as these numbers, so they are vanilla's own,
 * and {@code DropSimTest} pins every value of §B.1.4's table (ticks exactly, blocks to ±0.02). The
 * table's coast row (2.19 blocks) left out the ledge ticks below; the real coast is 1.28
 * ({@link #coast}).
 *
 * <p><b>The model</b> (26.2's {@code LivingEntity.aiStep}, {@code travel} and {@code travelInAir},
 * for a player not sprinting, with no effects):
 * <ul>
 *   <li>at the start of a tick, a horizontal speed under 0.003 (vanilla: {@code horizontalDistanceSqr
 *       < 9.0E-6}) is zeroed, and so is a vertical speed under 0.003;</li>
 *   <li>the input (|u| at most 1: the keys' vector, which vanilla normalises on the diagonal) is
 *       scaled by 0.98 and by the speed for where the player stands: in the air the flying speed
 *       0.02 (0.026 sprinting), so walking adds <b>0.0196</b> a tick; on the ground the movement
 *       speed 0.1 (times {@code 0.216 / f^3}, which is 1 for every block but ice and slime), so it
 *       adds <b>0.098</b>: {@code v += a·u};</li>
 *   <li>then the move: {@code pos += v};</li>
 *   <li>then gravity and drag: {@code vy = (vy - 0.08) * 0.98}, and sideways {@code v *= f * 0.91},
 *       where f is the friction of the block under the player (0.6) if it was on the ground when the
 *       tick began, and 1 in the air: {@code v *= 0.546} on the ground, {@code v *= 0.91} in the
 *       air.</li>
 * </ul>
 * The drag factors are vanilla's float literals ({@code 0.98F}, {@code 0.91F}, {@code 0.6F * 0.91F})
 * promoted to double, exactly as the game promotes them. The hitbox is 0.6 wide and 1.8 tall.
 *
 * <p><b>Walking off, jumping off: the ledge ticks.</b> Vanilla decides "on the ground" at the end of
 * each move, and the move resolves the vertical part first. So a walk-off takes two ground ticks
 * ({@link #WALK_OFF_LEDGE_TICKS}): the last tick that starts over the ledge (its downward step meets
 * the ledge, so it drops nothing and still counts as standing), and the edge tick after it, which
 * starts off the ledge but was begun on the ground, so it drops vanilla's 0.0784 with ground
 * acceleration and ground drag. Only then does air drag begin. A player walking steadily carries
 * {@link #WALK_OFF_SPEED} (0.118) into every tick and moves {@link #GROUND_WALK} (0.216, the familiar
 * 4.317 blocks a second); holding the key through both ledge ticks, it leaves the edge tick with
 * 0.118, not the 0.198 a body that was already in the air would keep. A jump-off is one ground tick
 * ({@link #JUMP_OFF_LEDGE_TICKS}) with the jump's 0.42 upward. {@link Body#ground} counts the ledge
 * ticks still to come. A sprint is never modelled for pilots or witnesses: the smaller acceleration
 * is the safe side.
 *
 * <p><b>What it means</b> (§B.1.4): steering happens near the top. From rest a player can move about
 * 2.4 blocks sideways before the first layer, but only 0.26-0.46 between later layers, so every
 * level asks for one real choice and then a smooth line.
 *
 * <p>No clock, no randomness, no Bukkit: the same numbers on every host.
 */
public final class DropSim {

    /** Gravity a tick (the {@code gravity} attribute's default). */
    public static final double GRAVITY = 0.08;
    /** What the vertical speed keeps each tick ({@code 0.98F}). */
    public static final double VERTICAL_DRAG = 0.98F;
    /** What the sideways speed keeps each tick in the air ({@code 0.91F}). */
    public static final double AIR_DRAG = 0.91F;
    /** Movement input is scaled by this ({@code 0.98F}). */
    public static final double INPUT = 0.98F;
    /** A player's flying speed in the air, walking and sprinting ({@code Player.getFlyingSpeed}). */
    public static final double AIR_SPEED = 0.02F;
    public static final double AIR_SPEED_SPRINT = 0.025999999F;
    /** What full walking input adds to the sideways speed each tick: 0.0196. */
    public static final double WALK_ACCEL = INPUT * AIR_SPEED;
    /** The same, sprinting: 0.02548 (never used by a pilot or a witness). */
    public static final double SPRINT_ACCEL = INPUT * AIR_SPEED_SPRINT;
    /** The jump's upward speed ({@code 0.42F}). */
    public static final double JUMP_POWER = 0.42F;
    /** A speed under this is zeroed at the start of a tick. */
    public static final double MIN_SPEED = 0.003;
    /**
     * What the sideways speed keeps after a tick begun on the ground: the friction of the block
     * under the player (0.6 for the lime concrete ledge, as for every block but ice and slime) times
     * 0.91, a float ({@code 0.6F * 0.91F}).
     */
    public static final double GROUND_DRAG = 0.6F * 0.91F;
    /**
     * A walking player's speed on the ground: the movement speed attribute 0.1 times
     * {@code 0.21600002F / (f * f * f)}, which is 1 at friction 0.6 (the float sum vanilla does).
     */
    public static final double GROUND_SPEED = 0.1F * (0.21600002F / (0.6F * 0.6F * 0.6F));
    /** What full walking input adds to the sideways speed on a ground tick: 0.098. */
    public static final double GROUND_ACCEL = INPUT * GROUND_SPEED;
    /** Blocks a walking player moves a tick on the ground: 0.216 (4.317 blocks a second). */
    public static final double GROUND_WALK = GROUND_ACCEL / (1 - GROUND_DRAG);
    /** Ground ticks in a walk-off: the last one that starts over the ledge, and the edge tick after it. */
    public static final int WALK_OFF_LEDGE_TICKS = 2;
    /** Ground ticks in a jump-off: the jump itself. */
    public static final int JUMP_OFF_LEDGE_TICKS = 1;
    /** The player's hitbox: wide and deep, and tall. */
    public static final double WIDTH = 0.6;
    public static final double HEIGHT = 1.8;
    /** Half the width: how far the hitbox reaches from the feet's centre. */
    public static final double HALF_WIDTH = WIDTH / 2;
    /** Blocks moved a tick at top walking air speed: 0.218. */
    public static final double TOP_WALK = WALK_ACCEL / (1 - AIR_DRAG);
    /**
     * The sideways speed a walking player carries into each tick, and so into a walk-off's first
     * ledge tick and out of its last: the ground walk after the ground drag (0.118).
     */
    public static final double WALK_OFF_SPEED = GROUND_WALK * GROUND_DRAG;
    /** The most ticks any fall here is followed (a 48-block drop takes 40). */
    public static final int MAX_TICKS = 200;

    private DropSim() {
    }

    /**
     * A falling player: the feet's centre (x, y, z), the speed it will move next tick (vanilla's
     * {@code deltaMovement}) and how many of its next ticks still begin on the ground ({@code ground}:
     * a walk-off's or a jump-off's ledge ticks, 0 in the air), which take ground acceleration and
     * ground drag.
     */
    public record Body(double x, double y, double z, double vx, double vy, double vz, int ground) {

        public Body {
            ground = Math.max(0, ground);
        }

        /** A body in the air. */
        public Body(double x, double y, double z, double vx, double vy, double vz) {
            this(x, y, z, vx, vy, vz, 0);
        }

        /** Its speed at the start of a tick, with vanilla's tiny-speed cut applied. */
        public Body settled() {
            double hx = vx;
            double hz = vz;
            if (hx * hx + hz * hz < MIN_SPEED * MIN_SPEED) {
                hx = 0;
                hz = 0;
            }
            double y1 = Math.abs(vy) < MIN_SPEED ? 0 : vy;
            return hx == vx && hz == vz && y1 == vy ? this : new Body(x, y, z, hx, y1, hz, ground);
        }

        /**
         * Its speed plus walking input (ux, uz): ground acceleration on a ledge tick, air acceleration
         * after; an input longer than 1 is shortened to 1.
         */
        public Body pushed(double ux, double uz) {
            double[] u = input(ux, uz);
            double a = ground > 0 ? GROUND_ACCEL : WALK_ACCEL;
            return new Body(x, y, z, vx + a * u[0], vy, vz + a * u[1], ground);
        }

        /** Moved by (dx, dy, dz), speed unchanged. */
        public Body moved(double dx, double dy, double dz) {
            return new Body(x + dx, y + dy, z + dz, vx, vy, vz, ground);
        }

        /**
         * After the move: gravity and drag, ground drag when the tick began on the ground; one ledge
         * tick fewer to come.
         */
        public Body dragged() {
            double d = ground > 0 ? GROUND_DRAG : AIR_DRAG;
            return new Body(x, y, z, vx * d, (vy - GRAVITY) * VERTICAL_DRAG, vz * d, ground - 1);
        }

        /**
         * One whole tick with nothing in the way and walking input (ux, uz): a ledge tick while
         * {@code ground} lasts, an air tick after.
         */
        public Body tick(double ux, double uz) {
            Body b = settled().pushed(ux, uz);
            return b.moved(b.vx, b.vy, b.vz).dragged();
        }

        /** The same body with the given speed. */
        public Body withSpeed(double nvx, double nvy, double nvz) {
            return new Body(x, y, z, nvx, nvy, nvz, ground);
        }
    }

    /** An input vector no longer than 1 (vanilla normalises a longer one). */
    public static double[] input(double ux, double uz) {
        double len2 = ux * ux + uz * uz;
        if (len2 > 1) {
            double len = Math.sqrt(len2);
            return new double[]{ux / len, uz / len};
        }
        return new double[]{ux, uz};
    }

    /**
     * A walk-off from (x, y, z) heading (fx, fz) (a unit vector), at the start of the last tick that
     * begins over the ledge: the speed a walking player carries, no vertical speed (the ledge holds
     * it up that tick), and its two ledge ticks to come.
     */
    public static Body walkOff(double x, double y, double z, double fx, double fz) {
        return new Body(x, y, z, fx * WALK_OFF_SPEED, 0, fz * WALK_OFF_SPEED, WALK_OFF_LEDGE_TICKS);
    }

    /** A jump-off: the jump's tick is its one ground tick, with the jump's upward speed. */
    public static Body jumpOff(double x, double y, double z, double fx, double fz) {
        return new Body(x, y, z, fx * WALK_OFF_SPEED, JUMP_POWER, fz * WALK_OFF_SPEED, JUMP_OFF_LEDGE_TICKS);
    }

    // ---- the vertical fall (input never changes it) ---------------------------------------------------

    /**
     * The feet's height after each tick, relative to the start: index 0 is the start (0), index t is
     * after t ticks. Long enough to fall {@code depth} blocks.
     */
    public static double[] profile(boolean jump, double depth) {
        double[] out = new double[MAX_TICKS + 1];
        Body b = new Body(0, 0, 0, 0, jump ? JUMP_POWER : 0, 0);
        int t = 0;
        while (t < MAX_TICKS && out[t] > -depth - 8) {
            b = b.tick(0, 0);
            out[++t] = b.y();
        }
        double[] trimmed = new double[t + 1];
        System.arraycopy(out, 0, trimmed, 0, t + 1);
        return trimmed;
    }

    /** Ticks until the feet are {@code depth} or more below the start (walk-off, or jump-off). */
    public static int fallTicks(double depth, boolean jump) {
        double[] p = profile(jump, depth);
        for (int t = 0; t < p.length; t++) {
            if (p[t] <= -depth) {
                return t;
            }
        }
        return MAX_TICKS;
    }

    /** Walk-off {@link #fallTicks}. */
    public static int fallTicks(double depth) {
        return fallTicks(depth, false);
    }

    /** How far the feet move in the tick that takes them {@code depth} below a walk-off's start. */
    public static double speedAt(double depth) {
        double[] p = profile(false, depth);
        int t = fallTicks(depth);
        return p[t - 1] - p[t];
    }

    /** The highest a jump-off lifts the feet above the ledge: 1.25. */
    public static double jumpPeak() {
        double[] p = profile(true, 1);
        double peak = 0;
        for (double y : p) {
            peak = Math.max(peak, y);
        }
        return peak;
    }

    // ---- sideways -----------------------------------------------------------------------------------

    /**
     * How far full walking input carries a body from rest in {@code ticks} ticks (in one direction):
     * the reach that decides where openings may be.
     */
    public static double reachFromRest(int ticks) {
        Body b = new Body(0, 0, 0, 0, 0, 0);
        for (int t = 0; t < ticks; t++) {
            b = b.tick(1, 0);
        }
        return b.x();
    }

    /**
     * How far a walk-off drifts once the key is let go: walking forward through both ledge ticks,
     * then no input, until vanilla's tiny-speed cut stops it. Counted from where the edge tick ends
     * (about 1.28 blocks: the 0.118 the ground leaves it with, kept 0.91 a tick).
     */
    public static double coast() {
        Body b = walkOff(0, 0, 0, 1, 0);
        for (int t = 0; t < WALK_OFF_LEDGE_TICKS; t++) {
            b = b.tick(1, 0);
        }
        double from = b.x();
        for (int t = WALK_OFF_LEDGE_TICKS; t < MAX_TICKS; t++) {
            b = b.tick(0, 0);
        }
        return b.x() - from;
    }

    /**
     * Stopping from top walking air speed with full reverse input: {ticks, blocks moved on the way}.
     * It counts the ticks that start still moving forward, the last of which turns the speed round.
     */
    public static double[] stop() {
        Body b = new Body(0, 0, 0, TOP_WALK * AIR_DRAG, 0, 0);
        int ticks = 0;
        while (ticks < MAX_TICKS && b.vx() > 0) {
            b = b.tick(-1, 0);
            ticks++;
        }
        return new double[]{ticks, b.x()};
    }

    /** Blocks moved a tick at top sideways air speed: 0.218 walking, 0.283 sprinting. */
    public static double topSpeed(boolean sprint) {
        return (sprint ? SPRINT_ACCEL : WALK_ACCEL) / (1 - AIR_DRAG);
    }
}
