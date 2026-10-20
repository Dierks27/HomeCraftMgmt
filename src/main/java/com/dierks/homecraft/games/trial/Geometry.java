package com.dierks.homecraft.games.trial;

/**
 * The checkpoint maths (spec §11, R2.15): does one move pass through a sphere, and where.
 *
 * <p>A move is tested as the straight segment between where the player was and where they are
 * now, not just its end point: an elytra at full speed covers several blocks a tick and would fly
 * straight through a ring that only ever sees the positions either side of it. The segment test
 * says how far along the move the sphere was first touched, which is also what places the finish
 * time between two ticks.
 */
final class Geometry {

    private Geometry() {
    }

    /**
     * The first point of the segment {@code a → b}, as a fraction of the way along it, at or after
     * {@code from}, that lies within {@code r} of {@code c}; {@code NaN} when there is none.
     * A move that starts inside the sphere touches it at {@code from}; a move that doesn't move is
     * inside or it isn't.
     */
    static double firstHit(Point a, Point b, Point c, double r, double from) {
        double lo = Math.max(0, from);
        if (lo > 1) {
            return Double.NaN;
        }
        double dx = b.x() - a.x();
        double dy = b.y() - a.y();
        double dz = b.z() - a.z();
        double fx = a.x() - c.x();
        double fy = a.y() - c.y();
        double fz = a.z() - c.z();
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

    /**
     * {@link #firstHit} no lower than {@code floorY}: the move is cut to its part at or above the floor, then
     * tested as before; {@code NaN} when that part never touches the sphere. A Fresh Courses parkour checkpoint
     * counts with the feet up on its pad, never while they fall past its side or under it
     * ({@link Progress#FEET_BELOW}).
     */
    static double firstHitAbove(Point a, Point b, Point c, double r, double from, double floorY) {
        double lo = Math.max(0, from);
        double hi = 1;
        double dy = b.y() - a.y();
        if (dy == 0) {
            if (a.y() < floorY) {
                return Double.NaN;
            }
        } else if (dy > 0) {
            lo = Math.max(lo, (floorY - a.y()) / dy);
        } else {
            hi = Math.min(hi, (floorY - a.y()) / dy);
        }
        if (lo > hi) {
            return Double.NaN;
        }
        double t = firstHit(a, b, c, r, lo);
        return t > hi ? Double.NaN : t; // NaN > hi is false: a miss stays a miss
    }

    /** The point a fraction {@code t} of the way from {@code a} to {@code b}. */
    static Point along(Point a, Point b, double t) {
        return new Point(a.x() + (b.x() - a.x()) * t, a.y() + (b.y() - a.y()) * t, a.z() + (b.z() - a.z()) * t);
    }

    /**
     * The yaw that faces from {@code a} toward {@code b} across the ground, in Minecraft's
     * convention (0 = south, +z; 90 = west, −x), so a player sent back to a checkpoint looks at the
     * next one.
     */
    static float yawToward(Point a, Point b) {
        double dx = b.x() - a.x();
        double dz = b.z() - a.z();
        if (dx == 0 && dz == 0) {
            return 0f;
        }
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        return yaw < 0 ? yaw + 360f : yaw;
    }
}
