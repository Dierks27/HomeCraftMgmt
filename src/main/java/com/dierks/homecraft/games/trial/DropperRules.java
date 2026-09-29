package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.gen.dropper.DropperGeometry;

/**
 * The Dropper's splash and bonk rules (EVENTS-DROPPER-SPEC §B.1.7), pure and tested: when a fall
 * reaches the water, and when it went wrong and goes back to the top of its level.
 *
 * <p><b>A splash</b> is the first move segment that enters a pool's box ({@link DropperLayout#poolBox}):
 * the water's column from its floor to half a block over the surface. The pool mark's own sphere
 * agrees in the middle of the pool, but a splash near a corner of a big pool is outside the sphere
 * and still in the water, so the box decides. Where the feet stand on the rim round a pool is
 * outside the box, so a rim landing is never a splash.
 *
 * <p><b>A bonk</b> is any of: a fall-damage event (already cancelled by the kit guard, seen anyway),
 * a landing ({@link Landing}: two ticks in a row the feet didn't drop, well below the ledge, in the
 * shaft and not in water; it catches landings too short to hurt), falling under the course's fall
 * height, the void, or the kit's "Back to the top". A bonk sends the run back to the top of the
 * level it is on, counts one, and the clock keeps running. Bonks are ignored while the run is on its
 * way somewhere by its own teleport: during the hop after a splash, and just after a bonk.
 */
public final class DropperRules {

    /** Ticks from a splash to the hop onto the next level's ledge (the title plays meanwhile). */
    public static final int HOP_TICKS = 5;
    /** Two bonks closer than this (ticks) are one: one fall seen twice, a double click. */
    public static final long BONK_GAP = 10;
    /** A tick in which the feet dropped no more than this is a still one... */
    public static final double STILL = 0.01;
    /** ...and this many still ticks in a row are a landing... */
    public static final int STILL_TICKS = 2;
    /** ...when the feet are at least this far below the ledge top (a jump off the ledge peaks above it). */
    public static final double BELOW_LEDGE = 1.5;

    /** What set a bonk off. */
    public enum Why {
        /** A fall-damage event (the kit guard cancelled it; the landing still happened). */
        FALL_DAMAGE,
        /** Two still ticks below the ledge, in the shaft, out of the water. */
        LANDING,
        /** Under the course's fall height. */
        FALL_Y,
        /** The void (a sealed shaft makes it impossible, but a bug shouldn't trap anyone). */
        VOID,
        /** The kit's "Back to the top". */
        KIT
    }

    private DropperRules() {
    }

    /**
     * The fraction (0..1) along the move {@code from} → {@code to} where it first enters the box
     * {x1, y1, z1, x2, y2, z2}, or {@code NaN} when it never does. A move that starts inside enters
     * at 0.
     */
    public static double enter(Point from, Point to, double[] box) {
        double lo = 0;
        double hi = 1;
        double[] a = {from.x(), from.y(), from.z()};
        double[] d = {to.x() - from.x(), to.y() - from.y(), to.z() - from.z()};
        for (int axis = 0; axis < 3; axis++) {
            double min = box[axis];
            double max = box[axis + 3];
            if (d[axis] == 0) {
                if (a[axis] < min || a[axis] > max) {
                    return Double.NaN;
                }
                continue;
            }
            double t0 = (min - a[axis]) / d[axis];
            double t1 = (max - a[axis]) / d[axis];
            if (t0 > t1) {
                double t = t0;
                t0 = t1;
                t1 = t;
            }
            lo = Math.max(lo, t0);
            hi = Math.min(hi, t1);
            if (lo > hi) {
                return Double.NaN;
            }
        }
        return lo;
    }

    /** Where along a move a splash into {@code pool} happens ({@link #enter} its box), or {@code NaN}. */
    public static double splash(Point from, Point to, Course.Mark pool) {
        if (from == null || to == null || pool == null) {
            return Double.NaN;
        }
        return enter(from, to, DropperLayout.poolBox(pool));
    }

    /**
     * When a splash {@code t} of the way along a move happened, between the move's two times: where the
     * feet crossed into the water.
     */
    public static long splashNanos(long fromNanos, long toNanos, double t) {
        return fromNanos + Math.round(t * (toNanos - fromNanos));
    }

    /**
     * Whether a bonk counts now: the clock is running, the run isn't on its way somewhere by its own
     * teleport (the hop after a splash, a bonk's own trip back), and it isn't the same bonk seen again
     * ({@link #BONK_GAP}).
     */
    public static boolean counts(boolean running, boolean hopping, boolean suspended, long tick, long lastBonk) {
        return running && !hopping && !suspended && tick - lastBonk >= BONK_GAP;
    }

    /**
     * Whether a point is in the shaft of the level whose ledge is {@code ledge}: within a shaft's
     * width of the ledge (the ledge stands against one wall, so this covers the whole shaft).
     */
    public static boolean inShaft(Course.Mark ledge, Point p) {
        return ledge != null && p != null && Math.abs(p.x() - ledge.x()) <= DropperGeometry.INSIDE
                && Math.abs(p.z() - ledge.z()) <= DropperGeometry.INSIDE;
    }

    /**
     * The landing watch, fed once a tick: {@link #STILL_TICKS} still ticks in a row, at least
     * {@link #BELOW_LEDGE} under the ledge top, in the shaft and out of the water, is a landing. A
     * jump's apex has one still tick (and is above the ledge anyway); standing on the ledge is above
     * the line; floating in a pool is in the water.
     */
    public static final class Landing {
        private double lastY = Double.NaN;
        private int still;

        /**
         * One tick.
         *
         * @param y        the feet's height now
         * @param ledgeTop the level's ledge top
         * @param inShaft  in the level's shaft ({@link DropperRules#inShaft})
         * @param inWater  in water (a pool)
         * @return whether this tick completes a landing (the watch starts over after one)
         */
        public boolean tick(double y, double ledgeTop, boolean inShaft, boolean inWater) {
            boolean counts = !Double.isNaN(lastY) && lastY - y <= STILL && y <= ledgeTop - BELOW_LEDGE && inShaft
                    && !inWater;
            lastY = y;
            still = counts ? still + 1 : 0;
            if (still >= STILL_TICKS) {
                reset();
                return true;
            }
            return false;
        }

        /** Start over (after a teleport: the height before it says nothing about after). */
        public void reset() {
            lastY = Double.NaN;
            still = 0;
        }
    }
}
