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
     * ({@link Progress#FEET_BELOW}; from 0.37.3 {@link #firstHitOver} keeps them over the pad too).
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

    /**
     * {@link #firstHitAbove} with the feet over a pad: the move is cut to its part inside the column
     * {@code half} either side of {@code c} across (x and z), then tested as before against the sphere and the
     * floor; {@code NaN} when that part never touches them. A Fresh Courses parkour mark counts with the feet over
     * its pad, never beside it ({@link Progress}: the column and the floor are exact along a move).
     */
    static double firstHitOver(Point a, Point b, Point c, double r, double from, double half, double floorY) {
        double[] cut = {Math.max(0, from), 1};
        if (!within(cut, a.x(), b.x(), c.x() - half, c.x() + half)
                || !within(cut, a.z(), b.z(), c.z() - half, c.z() + half)) {
            return Double.NaN;
        }
        double t = firstHitAbove(a, b, c, r, cut[0], floorY);
        return t > cut[1] ? Double.NaN : t; // NaN > cut[1] is false: a miss stays a miss
    }

    /**
     * Whether feet at {@code p} stand on a pad: within {@code r} of {@code c}, within {@code half} and {@code hair}
     * of it across (x and z), and within {@code hair} of its height. A point's test, for where a move ended, never a
     * move's: a reported position may be a float step or two off (a Bedrock player's), but the straight line of a
     * move through a band past the pad's side can be above its top while the feet never were ({@link
     * Progress#PAD_HAIR}).
     */
    static boolean standsOn(Point p, Point c, double r, double half, double hair) {
        return Math.abs(p.y() - c.y()) <= hair && Math.abs(p.x() - c.x()) <= half + hair
                && Math.abs(p.z() - c.z()) <= half + hair && firstHit(p, p, c, r, 0) == 0;
    }

    /**
     * Narrows {@code cut} (a fraction of the move, from and to) to where the move from {@code p0} to {@code p1}
     * along one axis is within {@code min}..{@code max}; whether any of it is left.
     */
    private static boolean within(double[] cut, double p0, double p1, double min, double max) {
        double d = p1 - p0;
        if (d == 0) {
            return min <= p0 && p0 <= max && cut[0] <= cut[1];
        }
        double t1 = (min - p0) / d;
        double t2 = (max - p0) / d;
        cut[0] = Math.max(cut[0], Math.min(t1, t2));
        cut[1] = Math.min(cut[1], Math.max(t1, t2));
        return cut[0] <= cut[1];
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
