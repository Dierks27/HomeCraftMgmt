package com.dierks.homecraft.games.trial;

import java.util.Collection;
import java.util.function.Predicate;

/**
 * Where a racer's boat is put back after a reset (EVENTS-DROPPER-SPEC §A.4.7), pure and tested.
 *
 * <p>Boats bump: the server checks every boat's move against the others' boxes, and nothing in
 * Paper switches that off. So a racer sent back to a checkpoint mustn't land in a boat that is
 * already there. The checkpoint spans the track, so the reseat looks sideways across it, up to
 * {@value #SIDEWAYS} blocks either way in {@value #STEP} steps, for the first spot with no race boat
 * within {@value #CLEAR} blocks (and that a boat fits on, when the caller can tell). When there is
 * none it uses the checkpoint itself: better a bump than no way back.
 */
public final class RaceSeat {

    /** How far sideways the reseat looks. */
    public static final double SIDEWAYS = 1.5;
    /** In steps this big. */
    public static final double STEP = 0.5;
    /** No other race boat within this far. */
    public static final double CLEAR = 2.0;

    private RaceSeat() {
    }

    /**
     * The spot to reseat at: {@code at}, else the first of +0.5, -0.5, +1, -1, +1.5, -1.5 blocks to
     * its side (right of {@code yaw}, the way the racer faces) that has no boat of {@code boats}
     * within {@value #CLEAR} blocks across the ground and that {@code fits} accepts; {@code at} when
     * none does.
     *
     * @param fits whether a boat fits at a point (the live blocks), or {@code null} for any
     */
    public static Point clear(Point at, float yaw, Collection<Point> boats, Predicate<Point> fits) {
        double rad = Math.toRadians(yaw);
        double fx = -Math.sin(rad);
        double fz = Math.cos(rad);
        double rx = -fz;
        double rz = fx;
        int steps = (int) Math.round(SIDEWAYS / STEP);
        for (int i = 0; i <= 2 * steps; i++) {
            double off = i == 0 ? 0 : (i % 2 == 1 ? 1 : -1) * ((i + 1) / 2) * STEP;
            Point p = new Point(at.x() + rx * off, at.y(), at.z() + rz * off);
            if (free(p, boats) && (fits == null || fits.test(p))) {
                return p;
            }
        }
        return at;
    }

    /** Whether no boat of {@code boats} is within {@value #CLEAR} blocks of {@code p} across the ground. */
    public static boolean free(Point p, Collection<Point> boats) {
        if (boats != null) {
            for (Point b : boats) {
                if (b.flatDistance(p) < CLEAR) {
                    return false;
                }
            }
        }
        return true;
    }
}
