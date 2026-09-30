package com.dierks.homecraft.games.golf;

/**
 * How a golf ball moves (spec §12): pure, deterministic, and free of Bukkit types, so every rule
 * below is tested against a fake block grid.
 *
 * <p><b>Why it is its own physics.</b> A real item entity drifts, snags on slab edges, gets pushed
 * by players and water, and differs a little between servers; a putt has to go where it was aimed
 * and do the same thing every time for every player. So the ball is a point with a small radius
 * that this class moves, and the entities that show it ({@link BallView}) just follow.
 *
 * <p><b>One tick.</b> Gravity when nothing holds the ball up; then the move, cut into sub-steps
 * short enough (a tenth of a block) that a fast ball can't jump through a wall or over the cup:
 * <ul>
 *   <li>sideways, each axis on its own: a solid block ahead reflects that axis's speed (0.6 of it
 *       back; slime 0.95), so a ball meets a wall at the angle it came in; a step up of half a
 *       block (a slab, a stair's low half) is climbed if the ball has the speed for it, losing
 *       what the climb costs; a full block always bounces; a sliver (a carpet, a path edge) is
 *       simply rolled over;</li>
 *   <li>up and down: it falls off edges and down steps, lands, and bounces on slime (keeping its
 *       sideways speed); rolling from another block onto slime pops it up;</li>
 *   <li>after every sub-step: in the cup (its centre passes within {@value #CUP_RADIUS} of the
 *       cup's top centre while moving slower than {@value #CUP_SPEED} a tick — faster, it lips out
 *       and keeps going; or it lands with its centre over the cup block and its bottom no higher
 *       than the cup's top), in water or lava, or out of the hole's bounds.</li>
 * </ul>
 * Then rolling friction by the block underneath (normal 0.90 a tick, ice 0.98, soul sand, soul
 * soil and honey 0.80); below {@value #STOP} blocks a tick it stops — in the cup if it comes to
 * rest over the cup block no higher than its top, or shut in a hollow block sitting on it.
 *
 * <p><b>The cup's top</b> is where the cup block's real collision shape is at its centre (a slab's
 * is half a block up), read at the start of the hole, so a ball resting on the floor of a sunken
 * hole or in a slab is where the cup is. A block with nothing a ball can rest on at its centre, a
 * sliver like a carpet, or a hollow one like a cauldron can't be a cup ({@link #cupShape}).
 */
public final class BallPhysics {

    /** The ball's radius: the size it is drawn at (a head at half scale). */
    public static final double RADIUS = 0.125;
    /** Its height, for what it bumps into. */
    static final double BODY = 2 * RADIUS;
    /** Blocks per tick² downward, like a dropped item. */
    static final double GRAVITY = 0.04;
    /** Vertical speed kept per tick in the air. */
    static final double VERTICAL_DRAG = 0.98;
    /** Sideways speed kept per tick in the air. */
    static final double AIR_DRAG = 0.98;
    /** Rolling friction: speed kept per tick. */
    static final double NORMAL = 0.90;
    static final double ICE = 0.98;
    static final double SLOW = 0.80;
    /** Of the speed into a wall, how much comes back. */
    static final double WALL = 0.6;
    static final double SLIME_WALL = 0.95;
    /** Of the speed into slime from above, how much comes back up. */
    static final double SLIME_BOUNCE = 0.75;
    /** Landing slower than this doesn't bounce, even on slime. */
    static final double MIN_BOUNCE = 0.1;
    /** The hop when the ball rolls onto slime. */
    static final double SLIME_POP = 0.25;
    /** A rise this small is rolled over at any speed. */
    static final double SLIVER = 0.13;
    /** The highest step the ball can climb (half a block). */
    static final double STEP = 0.5;
    /** Slower than this (blocks a tick) and the ball stops. */
    public static final double STOP = 0.01;
    /** In the cup when the centre passes this close to the cup's top centre... */
    public static final double CUP_RADIUS = 0.3;
    /** ...going no faster than this (blocks a tick); faster, it lips out. */
    public static final double CUP_SPEED = 0.35;
    /** A cup block's top must be at least this high above its bottom (a carpet is a sliver, not a cup). */
    public static final double CUP_MIN_TOP = 0.2;
    /** How far down {@link #settle} looks for ground. */
    static final int SETTLE = 4;
    /** Below the hole's lowest corner by this much, the ball is out of bounds (it fell off). */
    static final double FALL_MARGIN = 2;
    /** No sub-step moves the ball further than this. */
    static final double STEP_LENGTH = 0.1;
    static final int MAX_STEPS = 40;
    /** No speed above this, on any axis. */
    static final double MAX_SPEED = 3.0;
    private static final double EPS = 1e-6;
    private static final double FREE = Double.NEGATIVE_INFINITY;

    /** Each club's speed, power 1 ("Tap") to 5 ("Drive"), in blocks a tick. */
    private static final double[] POWER = {0.20, 0.38, 0.60, 0.90, 1.30};

    private BallPhysics() {
    }

    /** What a block is to the ball. Anything not listed is {@link #NORMAL}. */
    public enum Surface {
        NORMAL, ICE, SLOW, SLIME, WATER, LAVA;

        /** Speed kept per tick rolling on it. */
        double friction() {
            return switch (this) {
                case ICE -> BallPhysics.ICE;
                case SLOW -> BallPhysics.SLOW;
                default -> BallPhysics.NORMAL;
            };
        }

        /** Of the speed into its side, how much bounces back. */
        double wall() {
            return this == SLIME ? SLIME_WALL : WALL;
        }

        /** Back to the last spot. */
        boolean wet() {
            return this == WATER || this == LAVA;
        }
    }

    /** The blocks the ball rolls among (the world, or a test's grid). */
    public interface Blocks {

        /** {@link #top} for a block with nothing solid at that point (air, water, a flower). */
        double NONE = -1;

        /**
         * How high the solid part of block ({@code x}, {@code y}, {@code z}) reaches above the block's
         * own bottom at the point ({@code px}, {@code pz}) inside it: 1 for a full block, 0.5 for a
         * bottom slab or a stair's low half, 1.5 for a fence, {@link #NONE} for nothing solid there.
         */
        double top(int x, int y, int z, double px, double pz);

        /** What the block is, for friction, bounces and water. */
        Surface surface(int x, int y, int z);
    }

    /** What a block would be as a cup ({@link #cupShape}). */
    public enum CupShape {
        /** Something a ball can rest on, at its centre. */
        FINE,
        /** Nothing solid at its centre (air, a flower, a pressure plate). */
        NOTHING,
        /** Lower than {@value #CUP_MIN_TOP} of a block (a carpet, a trapdoor laid flat). */
        THIN,
        /** Higher round the edge than at the centre (a cauldron, a composter, a hopper). */
        HOLLOW
    }

    /**
     * One hole as the ball sees it: the cup's top centre (its top where the cup block's collision
     * shape is at its centre) and the bounds (block corners, inclusive).
     */
    public record Hole(double cupX, double cupY, double cupZ, int minX, int minY, int minZ, int maxX, int maxY,
                       int maxZ) {

        /** The hole for a full cup block and two bound corners, in any order. */
        public static Hole of(int cupX, int cupY, int cupZ, int ax, int ay, int az, int bx, int by, int bz) {
            return of(cupX, cupY, cupZ, 1.0, ax, ay, az, bx, by, bz);
        }

        /** The same, the cup block's top {@code top} above its bottom ({@link #cupTop}). */
        public static Hole of(int cupX, int cupY, int cupZ, double top, int ax, int ay, int az, int bx, int by,
                              int bz) {
            return new Hole(cupX + 0.5, cupY + top, cupZ + 0.5, Math.min(ax, bx), Math.min(ay, by), Math.min(az, bz),
                    Math.max(ax, bx), Math.max(ay, by), Math.max(az, bz));
        }

        /** Whether a point is over the hole (the bounds' columns); any height counts. */
        public boolean over(double x, double z) {
            return x >= minX && x < maxX + 1 && z >= minZ && z < maxZ + 1;
        }

        /** Whether a point is over the cup block (its column); any height counts. */
        public boolean overCup(double x, double z) {
            return Math.abs(x - cupX) < 0.5 && Math.abs(z - cupZ) < 0.5;
        }

        /**
         * Whether a ball standing at ({@code x}, {@code y}, {@code z}) is down on the cup: its centre
         * over the cup block and its bottom no higher than the cup's top — nor below the cup block
         * itself (a ball in a tunnel under the cup isn't in it).
         */
        public boolean onCup(double x, double y, double z) {
            return overCup(x, z) && y <= cupY + EPS && y >= Math.floor(cupY - EPS) - EPS;
        }

        /** Whether the ball, standing at {@code (x, y, z)}, is out of bounds. */
        boolean out(double x, double y, double z) {
            return !over(x, z) || y < minY - FALL_MARGIN;
        }
    }

    /** How a tick ended. */
    public enum Outcome {
        /** Still moving. */
        ROLLING,
        /** At rest (or was already). */
        STOPPED,
        /** In the cup. */
        IN_CUP,
        /** In water or lava: back to the last spot. */
        WATER,
        /** Left the hole's bounds: back to the last spot. */
        OUT
    }

    /**
     * The ball: where it stands (the bottom of it, like an entity's feet) and how fast it goes, in
     * blocks per tick.
     */
    public static final class Ball {
        double x;
        double y;
        double z;
        double vx;
        double vy;
        double vz;
        boolean moving;
        /** What held it up after the last sub-step, or null in the air (for the slime pop). */
        Surface support;

        public Ball(double x, double y, double z) {
            place(x, y, z);
        }

        public double x() {
            return x;
        }

        public double y() {
            return y;
        }

        public double z() {
            return z;
        }

        public double vx() {
            return vx;
        }

        public double vy() {
            return vy;
        }

        public double vz() {
            return vz;
        }

        /** Whether it is on the move (a still ball can be putted). */
        public boolean moving() {
            return moving;
        }

        /** Sideways speed, blocks a tick. */
        public double speed() {
            return Math.hypot(vx, vz);
        }

        /** Put it down here, still. */
        public void place(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
            stop();
            support = null;
        }

        /**
         * Send it off along the horizontal direction ({@code dx}, {@code dz}) — normalised here — at
         * {@code speed} blocks a tick.
         */
        public void putt(double dx, double dz, double speed) {
            double len = Math.hypot(dx, dz);
            if (len < EPS || speed <= 0) {
                return;
            }
            double s = Math.min(speed, MAX_SPEED);
            vx = dx / len * s;
            vz = dz / len * s;
            vy = 0;
            moving = true;
        }

        void stop() {
            vx = 0;
            vy = 0;
            vz = 0;
            moving = false;
        }
    }

    /** A club's speed: power 1 ("Tap") to 5 ("Drive"); out-of-range powers are clamped. */
    public static double speed(int power) {
        return POWER[Math.max(1, Math.min(POWER.length, power)) - 1];
    }

    /** How many clubs there are. */
    public static int clubs() {
        return POWER.length;
    }

    /**
     * Move the ball one tick. A still ball stays put ({@link Outcome#STOPPED}). After
     * {@link Outcome#IN_CUP}, {@link Outcome#WATER} or {@link Outcome#OUT} the ball is left still
     * where it was; putting it back is the caller's.
     */
    public static Outcome tick(Ball b, Blocks w, Hole h) {
        if (!b.moving) {
            return Outcome.STOPPED;
        }
        if (!supported(b, w) || b.vy > 0) {
            b.vy = (b.vy - GRAVITY) * VERTICAL_DRAG;
        } else {
            b.vy = 0;
        }
        b.vx = clamp(b.vx);
        b.vy = clamp(b.vy);
        b.vz = clamp(b.vz);
        double reach = Math.max(Math.hypot(b.vx, b.vz), Math.abs(b.vy));
        int n = (int) Math.min(MAX_STEPS, Math.max(1, Math.ceil(reach / STEP_LENGTH - EPS)));
        for (int i = 0; i < n; i++) {
            double px = b.x;
            double py = b.y + RADIUS;
            double pz = b.z;
            moveAcross(b, w, n, true);
            moveAcross(b, w, n, false);
            moveUpDown(b, w, n);
            Outcome o = check(b, w, h, px, py, pz);
            if (o != null) {
                b.stop();
                return o;
            }
        }
        if (supported(b, w) && b.vy == 0) {
            double f = under(b, w).friction();
            b.vx *= f;
            b.vz *= f;
            if (b.speed() < STOP) {
                b.stop();
                return restsInCup(b, w, h) ? Outcome.IN_CUP : Outcome.STOPPED;
            }
        } else {
            b.vx *= AIR_DRAG;
            b.vz *= AIR_DRAG;
        }
        return Outcome.ROLLING;
    }

    /**
     * Rest a still ball on whatever is under it, up to {@value #SETTLE} blocks down (a tee set
     * mid-jump or on a slab's edge floats otherwise); left where it is if nothing is.
     */
    public static void settle(Ball b, Blocks w) {
        double g = ground(w, b.x, b.z, b.y, b.y - SETTLE, null);
        if (g != FREE) {
            b.place(b.x, g, b.z);
        }
    }

    // ---- the cup ----------------------------------------------------------------------------------

    /**
     * How high the cup block ({@code x}, {@code y}, {@code z}) really is: its collision top at its
     * centre, above its bottom. 1 (a full block) when nothing is there now — the block was broken,
     * or its chunk isn't loaded — which is what the cup was before it was read.
     */
    public static double cupTop(Blocks w, int x, int y, int z) {
        double top = w.top(x, y, z, x + 0.5, z + 0.5);
        return top == Blocks.NONE ? 1.0 : top;
    }

    /**
     * Whether block ({@code x}, {@code y}, {@code z}) can be a cup: a ball must be able to rest on it
     * at its centre, on something thicker than a carpet, and not shut inside it — no part of the
     * block may rise more than a sliver above its centre.
     */
    public static CupShape cupShape(Blocks w, int x, int y, int z) {
        double centre = w.top(x, y, z, x + 0.5, z + 0.5);
        if (centre == Blocks.NONE) {
            return CupShape.NOTHING;
        }
        double[] at = {1.0 / 32, 0.25, 0.5, 0.75, 31.0 / 32};
        for (double fx : at) {
            for (double fz : at) {
                if (w.top(x, y, z, x + fx, z + fz) > centre + SLIVER) {
                    return CupShape.HOLLOW;
                }
            }
        }
        return centre < CUP_MIN_TOP ? CupShape.THIN : CupShape.FINE;
    }

    /**
     * Whether a ball that has come to rest is in the cup: its centre over the cup block, and either
     * its bottom no higher than the cup's top (on the floor of a sunken hole, in a slab, anywhere
     * on a flush cup), or shut in a hollow block sitting on the cup ({@link #trapped}). A ball shut
     * in a hollow block anywhere else stays where it is ("Reset ball" gets it out).
     */
    public static boolean restsInCup(Ball b, Blocks w, Hole h) {
        if (b.moving || !h.overCup(b.x, b.z)) {
            return false;
        }
        return h.onCup(b.x, b.y, b.z) || (b.y > h.cupY() && b.y < h.cupY() + 1 && trapped(b, w));
    }

    /**
     * Whether a still ball sits inside a hollow block (a cauldron, a composter) whose sides all
     * rise more than a climbable step above it, so no putt can get it out.
     */
    static boolean trapped(Ball b, Blocks w) {
        int bx = floor(b.x);
        int by = floor(b.y + EPS);
        int bz = floor(b.z);
        double in = 1.0 / 32;
        return wall(w, bx, by, bz, bx + in, b.z, b.y) && wall(w, bx, by, bz, bx + 1 - in, b.z, b.y)
                && wall(w, bx, by, bz, b.x, bz + in, b.y) && wall(w, bx, by, bz, b.x, bz + 1 - in, b.y);
    }

    /** Whether block (bx, by, bz) at (px, pz) is higher than a ball standing at y could climb. */
    private static boolean wall(Blocks w, int bx, int by, int bz, double px, double pz, double y) {
        double top = w.top(bx, by, bz, px, pz);
        return top != Blocks.NONE && by + top > y + STEP + EPS;
    }

    // ---- moving ---------------------------------------------------------------------------------

    /** One sub-step along x ({@code alongX}) or z: roll on, step up, or bounce off what's there. */
    private static void moveAcross(Ball b, Blocks w, int n, boolean alongX) {
        double v = alongX ? b.vx : b.vz;
        if (v == 0) {
            return;
        }
        double d = v / n;
        double nx = alongX ? b.x + d : b.x;
        double nz = alongX ? b.z : b.z + d;
        double edgeX = alongX ? nx + Math.signum(d) * RADIUS : nx;
        double edgeZ = alongX ? nz : nz + Math.signum(d) * RADIUS;
        Surface[] hit = new Surface[1];
        double top = obstacle(w, edgeX, edgeZ, b.y, hit);
        if (top == FREE) {
            b.x = nx;
            b.z = nz;
            return;
        }
        double rise = top - b.y;
        double speed = b.speed();
        boolean sliver = rise <= SLIVER;
        boolean climbable = rise <= STEP + EPS && speed * speed > 2 * GRAVITY * rise;
        if ((sliver || climbable) && obstacle(w, edgeX, edgeZ, top, null) == FREE) {
            b.y = top;
            b.x = nx;
            b.z = nz;
            if (!sliver) {
                double kept = Math.sqrt(speed * speed - 2 * GRAVITY * rise) / speed;
                b.vx *= kept;
                b.vz *= kept;
            }
            return;
        }
        double back = -v * (hit[0] == null ? WALL : hit[0].wall());
        if (alongX) {
            b.vx = back;
        } else {
            b.vz = back;
        }
    }

    /** One sub-step up or down: fall, land (bouncing on slime), or bump a ceiling. */
    private static void moveUpDown(Ball b, Blocks w, int n) {
        boolean held = supported(b, w);
        if (held && b.vy <= 0) {
            b.vy = 0;
            Surface s = under(b, w);
            if (s == Surface.SLIME && b.support != null && b.support != Surface.SLIME && b.speed() > STOP) {
                b.vy = SLIME_POP; // rolled onto slime from something else: it pops up
                b.support = null;
                return;
            }
            b.support = s;
            return;
        }
        b.support = null;
        double d = b.vy / n;
        double ny = b.y + d;
        if (d > 0) {
            if (obstacle(w, b.x, b.z, ny, null) != FREE) {
                b.vy = 0; // bumped its head
            } else {
                b.y = ny;
            }
            return;
        }
        Surface[] on = new Surface[1];
        double ground = ground(w, b.x, b.z, b.y, ny, on);
        if (ground != FREE && ny <= ground) {
            b.y = ground;
            if (on[0] == Surface.SLIME && b.vy < -MIN_BOUNCE) {
                b.vy = -b.vy * SLIME_BOUNCE; // bounces up, keeping its sideways speed
            } else {
                b.vy = 0;
                b.support = on[0];
            }
        } else {
            b.y = ny;
        }
    }

    /** In the cup, in water, or out of bounds after a sub-step from {@code (px, py, pz)} (the old centre)? */
    private static Outcome check(Ball b, Blocks w, Hole h, double px, double py, double pz) {
        double cx = b.x;
        double cy = b.y + RADIUS;
        double cz = b.z;
        if (b.speed() <= CUP_SPEED && distanceToSegment(h.cupX(), h.cupY(), h.cupZ(), px, py, pz, cx, cy, cz)
                <= CUP_RADIUS) {
            return Outcome.IN_CUP;
        }
        boolean landed = cy < py - EPS && supported(b, w); // it came down onto something this sub-step
        if (landed && h.onCup(cx, b.y, cz)) {
            return Outcome.IN_CUP;
        }
        if (w.surface(floor(cx), floor(cy), floor(cz)).wet()) {
            return Outcome.WATER;
        }
        if (h.out(cx, b.y, cz)) {
            return Outcome.OUT;
        }
        return null;
    }

    // ---- the grid ----------------------------------------------------------------------------------

    /** Whether something solid is right under the ball. */
    static boolean supported(Ball b, Blocks w) {
        double g = ground(w, b.x, b.z, b.y, b.y - EPS, null);
        return g != FREE && g >= b.y - EPS;
    }

    /** What the ball stands on (NORMAL when nothing). */
    static Surface under(Ball b, Blocks w) {
        Surface[] on = new Surface[1];
        ground(w, b.x, b.z, b.y, b.y - EPS, on);
        return on[0] == null ? Surface.NORMAL : on[0];
    }

    /**
     * The highest solid top under the ball's footprint at ({@code x}, {@code z}) — its centre and
     * its four edges, so a ball whose edge is still on a ledge is held up by it, like any entity —
     * no higher than {@code from}, among the blocks down to {@code to}; {@link #FREE} if none.
     * {@code on} (if given) gets that block's surface (the centre's, on a tie).
     */
    private static double ground(Blocks w, double x, double z, double from, double to, Surface[] on) {
        double r = RADIUS * 0.99; // an edge exactly on a block's side doesn't rest on it
        double best = groundAt(w, x, z, from, to, on, FREE);
        best = groundAt(w, x + r, z, from, to, on, best);
        best = groundAt(w, x - r, z, from, to, on, best);
        best = groundAt(w, x, z + r, from, to, on, best);
        return groundAt(w, x, z - r, from, to, on, best);
    }

    private static double groundAt(Blocks w, double x, double z, double from, double to, Surface[] on, double best) {
        int bx = floor(x);
        int bz = floor(z);
        for (int cy = floor(from + EPS); cy >= floor(to) - 1; cy--) {
            double top = w.top(bx, cy, bz, x, z);
            if (top == Blocks.NONE) {
                continue;
            }
            double t = cy + top;
            if (t <= from + EPS && t > best) {
                best = t;
                if (on != null) {
                    on[0] = w.surface(bx, cy, bz);
                }
            }
        }
        return best;
    }

    /**
     * The highest solid top at ({@code x}, {@code z}) that the ball's body, standing at {@code y},
     * would overlap; {@link #FREE} if nothing. {@code hit} (if given) gets that block's surface.
     */
    private static double obstacle(Blocks w, double x, double z, double y, Surface[] hit) {
        int bx = floor(x);
        int bz = floor(z);
        double best = FREE;
        for (int cy = floor(y) - 1; cy <= floor(y + BODY - EPS); cy++) {
            double top = w.top(bx, cy, bz, x, z);
            if (top == Blocks.NONE) {
                continue;
            }
            double t = cy + top;
            if (t > y + EPS && cy < y + BODY && t > best) {
                best = t;
                if (hit != null) {
                    hit[0] = w.surface(bx, cy, bz);
                }
            }
        }
        return best;
    }

    // ---- maths ---------------------------------------------------------------------------------------

    /** The distance from point Q to the segment A-B. */
    static double distanceToSegment(double qx, double qy, double qz, double ax, double ay, double az,
                                    double bx, double by, double bz) {
        double dx = bx - ax;
        double dy = by - ay;
        double dz = bz - az;
        double len2 = dx * dx + dy * dy + dz * dz;
        double t = len2 < EPS * EPS ? 0 : ((qx - ax) * dx + (qy - ay) * dy + (qz - az) * dz) / len2;
        t = Math.max(0, Math.min(1, t));
        double cx = ax + t * dx - qx;
        double cy = ay + t * dy - qy;
        double cz = az + t * dz - qz;
        return Math.sqrt(cx * cx + cy * cy + cz * cz);
    }

    private static double clamp(double v) {
        return Math.max(-MAX_SPEED, Math.min(MAX_SPEED, v));
    }

    private static int floor(double v) {
        return (int) Math.floor(v);
    }
}
