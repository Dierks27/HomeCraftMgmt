package com.dierks.homecraft.games.gen.dropper;

/**
 * How a player falls and steers in the air (EVENTS-DROPPER-SPEC §B.1.4): vanilla's player movement,
 * ported tick by tick, pure, with no blocks in the way ({@link DropRun} adds the blocks).
 *
 * <p><b>Why simulate.</b> Every Dropper level is proven before a block is set: a witness input
 * program is drawn first and obstacles go only outside its path, then sloppy pilots are flown
 * through the real voxels. The proof is only as good as these numbers, so they are vanilla's own,
 * and {@code DropSimTest} pins every value of §B.1.4's table (ticks exactly, blocks to ±0.02).
 *
 * <p><b>The model</b> (26.2's {@code LivingEntity.aiStep} and {@code travel}, for a player in the
 * air, not sprinting, no effects):
 * <ul>
 *   <li>at the start of a tick, a horizontal speed under 0.003 (vanilla: {@code horizontalDistanceSqr
 *       < 9.0E-6}) is zeroed, and so is a vertical speed under 0.003;</li>
 *   <li>the input (|u| at most 1: the keys' vector, which vanilla normalises on the diagonal) is
 *       scaled by 0.98 and by the flying speed 0.02 (0.026 sprinting), so walking adds
 *       <b>0.0196</b> a tick: {@code v += a·u};</li>
 *   <li>then the move: {@code pos += v};</li>
 *   <li>then gravity and drag: {@code vy = (vy - 0.08) * 0.98}, {@code v *= 0.91} sideways.</li>
 * </ul>
 * The drag factors are vanilla's float literals ({@code 0.98F}, {@code 0.91F}) promoted to double,
 * exactly as the game promotes them. The hitbox is 0.6 wide and 1.8 tall.
 *
 * <p><b>Walking off, jumping off.</b> A walk-off starts on the last tick on the ledge, at walking
 * speed ({@link #WALK_OFF_SPEED}: the top walking air speed, 0.218 a tick, after one drag; vanilla's
 * ground walk is 0.216) and with no vertical speed, so the first tick drops nothing and the second
 * drops vanilla's 0.0784. A jump-off is the same with the jump's 0.42 upward. A sprint is never
 * modelled for pilots or witnesses: the smaller acceleration is the safe side.
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
    /** The player's hitbox: wide and deep, and tall. */
    public static final double WIDTH = 0.6;
    public static final double HEIGHT = 1.8;
    /** Half the width: how far the hitbox reaches from the feet's centre. */
    public static final double HALF_WIDTH = WIDTH / 2;
    /** Blocks moved a tick at top walking air speed: 0.218. */
    public static final double TOP_WALK = WALK_ACCEL / (1 - AIR_DRAG);
    /** The sideways speed a walk-off starts with: the top walking air speed after one drag (0.198). */
    public static final double WALK_OFF_SPEED = TOP_WALK * AIR_DRAG;
    /** The most ticks any fall here is followed (a 48-block drop takes 40). */
    public static final int MAX_TICKS = 200;

    private DropSim() {
    }

    /**
     * A falling player: the feet's centre (x, y, z) and the speed it will move next tick
     * (vanilla's {@code deltaMovement}).
     */
    public record Body(double x, double y, double z, double vx, double vy, double vz) {

        /** Its speed at the start of a tick, with vanilla's tiny-speed cut applied. */
        public Body settled() {
            double hx = vx;
            double hz = vz;
            if (hx * hx + hz * hz < MIN_SPEED * MIN_SPEED) {
                hx = 0;
                hz = 0;
            }
            double y1 = Math.abs(vy) < MIN_SPEED ? 0 : vy;
            return hx == vx && hz == vz && y1 == vy ? this : new Body(x, y, z, hx, y1, hz);
        }

        /** Its speed plus walking input (ux, uz); an input longer than 1 is shortened to 1. */
        public Body pushed(double ux, double uz) {
            double[] u = input(ux, uz);
            return new Body(x, y, z, vx + WALK_ACCEL * u[0], vy, vz + WALK_ACCEL * u[1]);
        }

        /** Moved by (dx, dy, dz), speed unchanged. */
        public Body moved(double dx, double dy, double dz) {
            return new Body(x + dx, y + dy, z + dz, vx, vy, vz);
        }

        /** After the move: gravity and drag. */
        public Body dragged() {
            return new Body(x, y, z, vx * AIR_DRAG, (vy - GRAVITY) * VERTICAL_DRAG, vz * AIR_DRAG);
        }

        /** One whole tick in free air with walking input (ux, uz). */
        public Body tick(double ux, double uz) {
            Body b = settled().pushed(ux, uz);
            return b.moved(b.vx, b.vy, b.vz).dragged();
        }

        /** The same body with the given speed. */
        public Body withSpeed(double nvx, double nvy, double nvz) {
            return new Body(x, y, z, nvx, nvy, nvz);
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

    /** A walk-off from (x, y, z) heading (fx, fz) (a unit vector): walking speed, no vertical speed. */
    public static Body walkOff(double x, double y, double z, double fx, double fz) {
        return new Body(x, y, z, fx * WALK_OFF_SPEED, 0, fz * WALK_OFF_SPEED);
    }

    /** A jump-off: a walk-off with the jump's upward speed. */
    public static Body jumpOff(double x, double y, double z, double fx, double fz) {
        return new Body(x, y, z, fx * WALK_OFF_SPEED, JUMP_POWER, fz * WALK_OFF_SPEED);
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

    /** How far a walk-off drifts with no input at all, until vanilla's tiny-speed cut stops it. */
    public static double coast() {
        Body b = walkOff(0, 0, 0, 1, 0);
        for (int t = 0; t < MAX_TICKS; t++) {
            b = b.tick(0, 0);
        }
        return b.x();
    }

    /**
     * Stopping from top walking speed with full reverse input: {ticks, blocks moved on the way}. It
     * counts the ticks that start still moving forward, the last of which turns the speed round.
     */
    public static double[] stop() {
        Body b = walkOff(0, 0, 0, 1, 0);
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
