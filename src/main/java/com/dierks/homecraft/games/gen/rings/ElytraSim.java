package com.dierks.homecraft.games.gen.rings;

import java.util.List;

/**
 * Elytra flight, ported tick by tick from vanilla (GEN-SPEC §4.2), and the autopilot that proves a
 * Sky Rings layout can be flown.
 *
 * <p><b>The glide step</b> (vanilla's fall-flying update, then the move): gravity pulls down, less
 * the lift of a level look ({@code 0.08 x (-1 + 0.75 cos²pitch)}); falling speed turns into
 * forward speed along the look; looking up trades forward speed for climb; the horizontal speed
 * swings 10% of the way toward the look each tick; then drag keeps 0.99 / 0.98 / 0.99. An attached
 * rocket pulls the velocity half-way toward 1.5 x the look (plus 0.1 x the look) every tick it
 * burns. The best steady glide comes out at about 10:1, the number every layout's drops are built
 * against with a wide margin ({@link #glideRatio}).
 *
 * <p><b>The autopilot</b> is the second proof, after the glide ratio. Each tick it aims its yaw at
 * the next ring, following the straight leg to it from the last ring ({@value #CARROT} blocks ahead
 * along it, so it arrives lined up rather than curving in from the side), and sets its pitch from
 * the height error: twice the angle down (or up) to the ring's centre, clamped to
 * {@value #MAX_CLIMB}° up and {@value #MAX_DIVE}° down. Twice, because an elytra's fall answers the
 * stick slowly, and a pilot who only points at the ring arrives above it. Once a ring counts it
 * keeps flying straight through it until it is a block past its plane, as a player does, and only
 * then turns for the next one (turning inside the hole clips the frame). It fires a rocket, if it
 * has one, only when it is falling short: once the ring is no longer below its glide line. It must
 * pass every ring's centre closely enough to count, touching nothing. {@link RingsValidator} flies
 * it from every ring at three speeds with the heading a little off, from a standstill at every ring
 * (a fall-reset leaves the player gliding at the ring with no speed) with at most one rocket, and
 * off the start tower.
 *
 * <p>Look vectors come from {@link StrictMath} or plain arithmetic; nothing here reads a clock or
 * a random number, so a layout is proven the same way on every host.
 */
public final class ElytraSim {

    /** Gravity per tick. */
    public static final double GRAVITY = 0.08;
    /** How many ticks a plain rocket burns (flight 1 burns 20 to 31; the shortest counts). */
    public static final int ROCKET_TICKS = 20;
    /** The steepest the autopilot looks up, in degrees (any more and an elytra stalls). */
    public static final double MAX_CLIMB = 30;
    /** The steepest it looks down, in degrees. */
    public static final double MAX_DIVE = 60;
    /** A flight gives up after this many ticks without reaching its next ring. */
    public static final int LEG_TICKS = 400;
    /** A gliding player's hitbox: 0.6 wide and 0.6 tall. */
    public static final double BOX = 0.6;
    /** A rocket fires once the ring is less than one block down for this many across. */
    public static final double SHORT_SLOPE = 8;
    /** A flight has missed its ring once it is this much further from it, across the ground, than its closest. */
    public static final double MISS = 4;
    /** ...or once it is this far below it. */
    public static final double LOST = 24;
    /** The autopilot follows the straight leg from the last ring: it aims this far ahead along it. */
    public static final double CARROT = 6;

    private static final double TAN_HALF_CLIMB = StrictMath.tan(StrictMath.toRadians(MAX_CLIMB / 2));
    private static final double TAN_HALF_DIVE = StrictMath.tan(StrictMath.toRadians(MAX_DIVE / 2));
    private static final double COS_CLIMB = StrictMath.cos(StrictMath.toRadians(MAX_CLIMB));
    private static final double SIN_CLIMB = StrictMath.sin(StrictMath.toRadians(MAX_CLIMB));
    private static final double COS_DIVE = StrictMath.cos(StrictMath.toRadians(MAX_DIVE));
    private static final double SIN_DIVE = StrictMath.sin(StrictMath.toRadians(MAX_DIVE));

    private ElytraSim() {
    }

    /** A flying player: position (feet, middle of the hitbox across), velocity, and rocket burn left. */
    public static final class Flyer {
        public double x;
        public double y;
        public double z;
        public double vx;
        public double vy;
        public double vz;
        /** Ticks of rocket left (0: none burning). */
        public int rocket;

        public Flyer(double x, double y, double z, double vx, double vy, double vz) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.vx = vx;
            this.vy = vy;
            this.vz = vz;
        }

        /** An exact copy. */
        public Flyer copy() {
            Flyer f = new Flyer(x, y, z, vx, vy, vz);
            f.rocket = rocket;
            return f;
        }

        /** The horizontal speed, blocks a tick. */
        public double horizontalSpeed() {
            return Math.sqrt(vx * vx + vz * vz);
        }

        /** The speed, blocks a tick. */
        public double speed() {
            return Math.sqrt(vx * vx + vy * vy + vz * vz);
        }
    }

    /**
     * The look vector for Minecraft's yaw (0 = south, 90 = west) and pitch (positive = down), in
     * degrees.
     */
    public static double[] look(double yaw, double pitch) {
        double y = StrictMath.toRadians(yaw);
        double p = StrictMath.toRadians(pitch);
        double cp = StrictMath.cos(p);
        return new double[]{-StrictMath.sin(y) * cp, -StrictMath.sin(p), StrictMath.cos(y) * cp};
    }

    /** One tick of gliding while looking along the unit vector (lx, ly, lz): a rocket's pull, the glide, the move. */
    public static void glide(Flyer f, double lx, double ly, double lz) {
        if (f.rocket > 0) {
            f.vx += lx * 0.1 + (lx * 1.5 - f.vx) * 0.5;
            f.vy += ly * 0.1 + (ly * 1.5 - f.vy) * 0.5;
            f.vz += lz * 0.1 + (lz * 1.5 - f.vz) * 0.5;
            f.rocket--;
        }
        double horizontalLook = Math.sqrt(lx * lx + lz * lz);
        double horizontalSpeed = f.horizontalSpeed();
        // the look is a unit vector: its horizontal length is cos(pitch), its y is -sin(pitch)
        double lift = horizontalLook * horizontalLook;
        f.vy += GRAVITY * (-1.0 + lift * 0.75);
        if (f.vy < 0 && horizontalLook > 0) {
            double convert = f.vy * -0.1 * lift;
            f.vx += lx * convert / horizontalLook;
            f.vy += convert;
            f.vz += lz * convert / horizontalLook;
        }
        if (ly > 0 && horizontalLook > 0) { // looking up (a negative pitch)
            double convert = horizontalSpeed * ly * 0.04;
            f.vx += -lx * convert / horizontalLook;
            f.vy += convert * 3.2;
            f.vz += -lz * convert / horizontalLook;
        }
        if (horizontalLook > 0) {
            f.vx += (lx / horizontalLook * horizontalSpeed - f.vx) * 0.1;
            f.vz += (lz / horizontalLook * horizontalSpeed - f.vz) * 0.1;
        }
        f.vx *= 0.99;
        f.vy *= 0.98;
        f.vz *= 0.99;
        f.x += f.vx;
        f.y += f.vy;
        f.z += f.vz;
    }

    /**
     * One tick of plain falling with the wings shut (walking off the tower): a walker's small push
     * along the unit (fx, fz), the move, then air drag and gravity.
     */
    public static void fall(Flyer f, double fx, double fz) {
        f.vx += fx * 0.02;
        f.vz += fz * 0.02;
        f.x += f.vx;
        f.y += f.vy;
        f.z += f.vz;
        f.vx *= 0.91;
        f.vz *= 0.91;
        f.vy = (f.vy - GRAVITY) * 0.98;
    }

    /**
     * The steady glide ratio (forward over down) at a fixed pitch in degrees, after the speed has
     * long settled: about 10 at a level look.
     */
    public static double glideRatio(double pitch) {
        double[] l = look(180, pitch);
        Flyer f = new Flyer(0, 0, 0, 0, 0, -1);
        for (int i = 0; i < 3_000; i++) {
            glide(f, l[0], l[1], l[2]);
        }
        return f.vy >= 0 ? Double.POSITIVE_INFINITY : f.horizontalSpeed() / -f.vy;
    }

    // ---- the autopilot ---------------------------------------------------------------------------

    /**
     * A ring to fly through: its centre, how close to it a pass must come (the checkpoint's
     * radius less half a block), and the way through it across the ground (nx, nz), a unit vector
     * along its facing, or (0, 0) for a ring the flight ends in. A pilot keeps aiming through a
     * ring until it is a block past its plane, then turns for the next one, as a player does; one
     * that turns inside the hole clips the frame.
     */
    public record Target(double x, double y, double z, double pass, double nx, double nz, boolean last) {

        /** A point to pass near, with no facing (the flight ends there). */
        public Target(double x, double y, double z, double pass) {
            this(x, y, z, pass, 0, 0, true);
        }

        /** How far (px, pz) is in front of the ring's plane, along its facing (negative: past it). */
        double ahead(double px, double pz) {
            return (x - px) * nx + (z - pz) * nz;
        }

        /** Whether (px, pz) is a block past the ring's plane (or the flight ends at this ring). */
        boolean through(double px, double pz) {
            return last || ahead(px, pz) <= -1;
        }
    }

    /** What a flight can hit: whether a block is solid. */
    @FunctionalInterface
    public interface Solid {
        boolean at(int x, int y, int z);

        /** Whether anything solid could be within a couple of blocks of (x, y, z); a cheap first test. */
        default boolean near(double x, double y, double z) {
            return true;
        }
    }

    /**
     * How a flight went.
     *
     * @param passed  every ring passed, in order
     * @param reached how many rings it passed
     * @param ticks   how long it flew
     * @param why     why it stopped short ({@code null} when it passed)
     */
    public record Result(boolean passed, int reached, int ticks, String why) {
    }

    /** A flyer under the autopilot, with its rockets, that can fly on from where it got to. */
    public static final class Pilot {
        public final Flyer flyer;
        private int rockets;
        /** A ring counted but not yet flown through. */
        private Target through;
        /** Where the leg being flown starts (the start, or the last ring passed), across the ground. */
        private double fromX;
        private double fromZ;

        public Pilot(Flyer flyer, int rockets) {
            this.flyer = flyer;
            this.rockets = rockets;
            this.fromX = flyer.x;
            this.fromZ = flyer.z;
        }

        /** An exact copy, to try a leg without committing to it. */
        public Pilot copy() {
            Pilot p = new Pilot(flyer.copy(), rockets);
            p.through = through;
            p.fromX = fromX;
            p.fromZ = fromZ;
            return p;
        }

        /** Rockets not fired yet. */
        public int rockets() {
            return rockets;
        }

        /**
         * Fly on through {@code targets} in order. A ring counts when a tick's move comes within
         * its pass distance of the centre; a flight fails when it touches anything {@code solid},
         * runs {@value #LEG_TICKS} ticks without reaching its next ring, moves {@value #MISS} further
         * from it across the ground than its closest, or sinks {@value #LOST} under it.
         */
        public Result fly(List<Target> targets, Solid solid) {
            Flyer f = flyer;
            int next = 0;
            int ticks = 0;
            int sinceRing = 0;
            double closest = Double.MAX_VALUE;
            while (next < targets.size()) {
                // until a counted ring is behind it, keep flying through it (aim a little past its centre)
                if (through != null && through.through(f.x, f.z)) {
                    through = null;
                }
                Target t = targets.get(next);
                double aimX = t.x();
                double aimY = t.y();
                double aimZ = t.z();
                if (through != null) {
                    aimX = through.x() + through.nx() * 3;
                    aimY = through.y();
                    aimZ = through.z() + through.nz() * 3;
                } else {
                    // follow the leg: aim at a point on the straight line from the last ring, a little ahead
                    double lx0 = t.x() - fromX;
                    double lz0 = t.z() - fromZ;
                    double len = Math.sqrt(lx0 * lx0 + lz0 * lz0);
                    if (len > 1e-9) {
                        double ux = lx0 / len;
                        double uz = lz0 / len;
                        double along = (f.x - fromX) * ux + (f.z - fromZ) * uz + CARROT;
                        if (along < len) {
                            aimX = fromX + ux * along;
                            aimZ = fromZ + uz * along;
                        }
                    }
                }
                // yaw toward the aim point; pitch from the height and distance of the ring itself
                double dx = aimX - f.x;
                double dz = aimZ - f.z;
                double hAim = Math.sqrt(dx * dx + dz * dz);
                double dy = aimY - (f.y + BOX / 2);
                double tx = t.x() - f.x;
                double tz = t.z() - f.z;
                double toRing = Math.sqrt(tx * tx + tz * tz);
                double h = through != null ? hAim : toRing;
                closest = Math.min(closest, toRing);
                if (rockets > 0 && f.rocket == 0 && -dy * SHORT_SLOPE < h) {
                    f.rocket = ROCKET_TICKS;
                    rockets--;
                }
                double lx;
                double ly;
                double lz;
                if (h < 1e-9 || hAim < 1e-9) {
                    lx = 0;
                    lz = 0;
                    ly = dy >= 0 ? 1 : -1;
                } else {
                    // the angle down to the ring is atan(s); twice it has cos (1-s²)/(1+s²), sin 2s/(1+s²)
                    double s = -dy / h;
                    double cos;
                    double sin;
                    if (s > TAN_HALF_DIVE) {
                        cos = COS_DIVE;
                        sin = SIN_DIVE;
                    } else if (s < -TAN_HALF_CLIMB) {
                        cos = COS_CLIMB;
                        sin = -SIN_CLIMB;
                    } else {
                        double q = 1 + s * s;
                        cos = (1 - s * s) / q;
                        sin = 2 * s / q;
                    }
                    lx = dx / hAim * cos;
                    lz = dz / hAim * cos;
                    ly = -sin;
                }
                double ox = f.x;
                double oy = f.y + BOX / 2;
                double oz = f.z;
                glide(f, lx, ly, lz);
                ticks++;
                sinceRing++;
                if (solid != null && touches(f, solid)) {
                    return new Result(false, next, ticks, "touched a block at " + (int) Math.floor(f.x) + " "
                            + (int) Math.floor(f.y) + " " + (int) Math.floor(f.z) + " before ring " + (next + 1));
                }
                double from = 0;
                while (next < targets.size()) {
                    Target r = targets.get(next);
                    double hit = firstHit(ox, oy, oz, f.x, f.y + BOX / 2, f.z, r.x(), r.y(), r.z(), r.pass(), from);
                    if (Double.isNaN(hit)) {
                        break;
                    }
                    from = hit;
                    through = r.through(f.x, f.z) ? null : r;
                    fromX = r.x();
                    fromZ = r.z();
                    next++;
                    sinceRing = 0;
                    closest = Double.MAX_VALUE;
                }
                if (next < targets.size()) {
                    Target r = targets.get(next);
                    double nx = r.x() - f.x;
                    double nz = r.z() - f.z;
                    double nh = Math.sqrt(nx * nx + nz * nz);
                    if (sinceRing > LEG_TICKS) {
                        return new Result(false, next, ticks, "never reached ring " + (next + 1));
                    }
                    if (nh > closest + MISS) {
                        return new Result(false, next, ticks, "missed ring " + (next + 1) + " ("
                                + Math.round(f.y + BOX / 2 - r.y()) + " off in height)");
                    }
                    if (f.y + BOX / 2 < r.y() - LOST) {
                        return new Result(false, next, ticks, "fell far below ring " + (next + 1));
                    }
                }
            }
            return new Result(true, next, ticks, null);
        }
    }

    /** Fly {@code f} with {@code rockets} through {@code targets} ({@link Pilot#fly}). */
    public static Result fly(Flyer f, List<Target> targets, int rockets, Solid solid) {
        return new Pilot(f, rockets).fly(targets, solid);
    }

    /** Whether the gliding hitbox at the flyer's position overlaps a solid block. */
    static boolean touches(Flyer f, Solid solid) {
        if (!solid.near(f.x, f.y + BOX / 2, f.z)) {
            return false;
        }
        int x0 = (int) Math.floor(f.x - BOX / 2);
        int x1 = (int) Math.floor(f.x + BOX / 2);
        int y0 = (int) Math.floor(f.y);
        int y1 = (int) Math.floor(f.y + BOX);
        int z0 = (int) Math.floor(f.z - BOX / 2);
        int z1 = (int) Math.floor(f.z + BOX / 2);
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    if (solid.at(x, y, z)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * The first point of the move a → b, as a fraction of the way along it at or after
     * {@code from}, within {@code r} of c; {@code NaN} when there is none. The same sphere test
     * the course's checkpoints use.
     */
    static double firstHit(double ax, double ay, double az, double bx, double by, double bz, double cx, double cy,
                           double cz, double r, double from) {
        double lo = Math.max(0, from);
        double dx = bx - ax;
        double dy = by - ay;
        double dz = bz - az;
        double fx = ax - cx;
        double fy = ay - cy;
        double fz = az - cz;
        double qa = dx * dx + dy * dy + dz * dz;
        double qb = 2 * (fx * dx + fy * dy + fz * dz);
        double qc = fx * fx + fy * fy + fz * fz - r * r;
        if (qa == 0) {
            return qc <= 0 ? lo : Double.NaN;
        }
        double disc = qb * qb - 4 * qa * qc;
        if (disc < 0) {
            return Double.NaN;
        }
        double root = Math.sqrt(disc);
        double enter = (-qb - root) / (2 * qa);
        double exit = (-qb + root) / (2 * qa);
        if (exit < lo || enter > 1) {
            return Double.NaN;
        }
        return Math.max(enter, lo);
    }
}
