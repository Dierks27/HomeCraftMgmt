package com.dierks.homecraft.games.gen.dropper;

import java.util.ArrayList;
import java.util.List;

/**
 * One fall through real blocks (EVENTS-DROPPER-SPEC §B.1.6): {@link DropSim}'s tick, split into
 * {@value #SUB_STEPS} swept sub-steps so nothing tunnels through a one-block plate at two blocks a
 * tick, against a {@link DropWorld}.
 *
 * <p><b>What stops a fall.</b>
 * <ul>
 *   <li>Walls and the ledge collide with the plain hitbox, as vanilla does: a move into one stops at
 *       its face and that part of the speed is lost. A scrape along a wall isn't a landing, but
 *       coming down onto the ledge (or onto a wall's top) is: {@link Outcome#LANDED}.</li>
 *   <li>Every other solid block is an obstacle, tested with the hitbox grown by the clearance
 *       {@code inflate} over the whole sub-step (the box covering where it started and where it
 *       ended, so a corner clip between two positions still counts): {@link Outcome#TOUCHED}.</li>
 *   <li>The hitbox reaching water after a clean sub-step is the splash: {@link Outcome#SPLASH}.</li>
 * </ul>
 * The caller may also stop it when the feet go below a height ({@link Outcome#STOPPED}), which is
 * how the planner follows a witness through a shaft that has no obstacles or water yet.
 *
 * <p>Every sub-step is one unit of counted work for the planner's budget ({@link Result#subSteps}),
 * so a plan costs the same on every host.
 */
public final class DropRun {

    /** The kinds whose rows a quiet sub-step must be clear of: obstacles and water. */
    private static final int QUIET_ROWS = 1 << DropWorld.SOLID | 1 << DropWorld.WATER;

    /** Sub-steps a tick: at 2.2 blocks a tick, each is under 0.6, far less than a plate plus a body. */
    public static final int SUB_STEPS = 4;

    /** How a fall ended. */
    public enum Outcome { SPLASH, TOUCHED, LANDED, STOPPED, FELL, TIMEOUT }

    /** Who is steering: the input for the coming tick (null or {0, 0} for none). */
    public interface Controller {
        double[] input(int tick, DropSim.Body body);
    }

    /**
     * How a fall went.
     *
     * @param outcome  how it ended
     * @param ticks    ticks flown (the last one included)
     * @param body     where it ended
     * @param hit      the block it touched or landed on, {x, y, z}, or {@code null}
     * @param path     the feet at the start and after every sub-step, {x, y, z, tick}, when asked for
     * @param subSteps sub-steps flown: the counted work
     * @param lastBox  the last sub-step's swept box {x1, y1, z1, x2, y2, z2} (plain hitbox)
     */
    public record Result(Outcome outcome, int ticks, DropSim.Body body, int[] hit, List<double[]> path,
                         long subSteps, double[] lastBox) {

        public boolean splashed() {
            return outcome == Outcome.SPLASH;
        }
    }

    private DropRun() {
    }

    /**
     * Fly {@code start} through {@code world} under {@code steer} until it splashes, touches an
     * obstacle (with the hitbox grown by {@code inflate}), lands, falls out of the half, runs out of
     * {@code maxTicks} or, when {@code stopBelow} isn't NaN, its feet go below {@code stopBelow}.
     */
    public static Result fly(DropWorld world, DropSim.Body start, Controller steer, double inflate, int maxTicks,
                             double stopBelow, boolean keepPath) {
        List<double[]> path = keepPath ? new ArrayList<>() : null;
        DropSim.Body b = start;
        if (keepPath) {
            path.add(new double[]{b.x(), b.y(), b.z(), 0});
        }
        long work = 0;
        double h = DropSim.HALF_WIDTH;
        double[] lastBox = null;
        for (int tick = 1; tick <= maxTicks; tick++) {
            double[] u = steer == null ? null : steer.input(tick - 1, b);
            b = b.settled();
            if (u != null) {
                b = b.pushed(u[0], u[1]);
            }
            double vx = b.vx();
            double vy = b.vy();
            double vz = b.vz();
            double x = b.x();
            double y = b.y();
            double z = b.z();
            boolean xFirst = Math.abs(vx) >= Math.abs(vz);
            for (int s = 0; s < SUB_STEPS; s++) {
                work++;
                double x0 = x;
                double y0 = y;
                double z0 = z;
                double dy = vy / SUB_STEPS;
                double dx = vx / SUB_STEPS;
                double dz = vz / SUB_STEPS;
                double reachOut = Math.max(inflate, 0) + 1e-6;
                if (world.quiet(Math.min(x0, x0 + dx) - h - reachOut, Math.min(y0, y0 + dy) - reachOut,
                        Math.min(z0, z0 + dz) - h - reachOut, Math.max(x0, x0 + dx) + h + reachOut,
                        Math.max(y0, y0 + dy) + DropSim.HEIGHT + reachOut, Math.max(z0, z0 + dz) + h + reachOut,
                        QUIET_ROWS, DropWorld.COLLIDES)) {
                    // open air: nothing to hit, land on or splash into
                    y += dy;
                    x += dx;
                    z += dz;
                    if (path != null) {
                        path.add(new double[]{x, y, z, tick});
                    }
                    Outcome end = !Double.isNaN(stopBelow) && y < stopBelow ? Outcome.STOPPED
                            : y < world.half().minY() - 1 ? Outcome.FELL : null;
                    if (end != null || s == SUB_STEPS - 1) {
                        lastBox = box(x0, y0, z0, x, y, z);
                    }
                    if (end != null) {
                        return new Result(end, tick, new DropSim.Body(x, y, z, vx, vy, vz), null, path, work,
                                lastBox);
                    }
                    continue;
                }
                // vertical first, as vanilla resolves a move
                double my = clampY(world, x0, y0, z0, dy);
                if (my != dy) {
                    if (dy < 0) {
                        y += my;
                        int[] under = world.overlapAny(x - h, y - 0.01, z - h, x + h, y, z + h, DropWorld.COLLIDES);
                        DropSim.Body at = new DropSim.Body(x, y, z, vx, 0, vz);
                        record(path, at, tick);
                        return new Result(Outcome.LANDED, tick, at, under, path, work, box(x0, y0, z0, x, y, z));
                    }
                    vy = 0;
                }
                y += my;
                if (xFirst) {
                    double mx = clampX(world, x, y, z, dx);
                    if (mx != dx) {
                        vx = 0;
                    }
                    x += mx;
                    double mz = clampZ(world, x, y, z, dz);
                    if (mz != dz) {
                        vz = 0;
                    }
                    z += mz;
                } else {
                    double mz = clampZ(world, x, y, z, dz);
                    if (mz != dz) {
                        vz = 0;
                    }
                    z += mz;
                    double mx = clampX(world, x, y, z, dx);
                    if (mx != dx) {
                        vx = 0;
                    }
                    x += mx;
                }
                if (path != null) {
                    path.add(new double[]{x, y, z, tick});
                }
                double sx1 = Math.min(x0, x) - h;
                double sy1 = Math.min(y0, y);
                double sz1 = Math.min(z0, z) - h;
                double sx2 = Math.max(x0, x) + h;
                double sy2 = Math.max(y0, y) + DropSim.HEIGHT;
                double sz2 = Math.max(z0, z) + h;
                int[] touched = world.overlapAny(sx1 - inflate, sy1 - inflate, sz1 - inflate, sx2 + inflate,
                        sy2 + inflate, sz2 + inflate, 1 << DropWorld.SOLID);
                Outcome end = null;
                if (touched != null) {
                    end = Outcome.TOUCHED;
                } else if (world.overlapAny(x - h, y, z - h, x + h, y + DropSim.HEIGHT, z + h, 1 << DropWorld.WATER)
                        != null) {
                    end = Outcome.SPLASH;
                } else if (!Double.isNaN(stopBelow) && y < stopBelow) {
                    end = Outcome.STOPPED;
                } else if (y < world.half().minY() - 1) {
                    end = Outcome.FELL;
                }
                if (end != null || s == SUB_STEPS - 1) {
                    lastBox = new double[]{sx1, sy1, sz1, sx2, sy2, sz2};
                }
                if (end != null) {
                    return new Result(end, tick, new DropSim.Body(x, y, z, vx, vy, vz), touched, path, work, lastBox);
                }
            }
            b = new DropSim.Body(x, y, z, vx, vy, vz).dragged();
        }
        return new Result(Outcome.TIMEOUT, maxTicks, b, null, path, work, lastBox);
    }

    /** The box a sub-step swept: where the hitbox started and where it ended, together. */
    static double[] box(double x0, double y0, double z0, double x, double y, double z) {
        double h = DropSim.HALF_WIDTH;
        return new double[]{Math.min(x0, x) - h, Math.min(y0, y), Math.min(z0, z) - h, Math.max(x0, x) + h,
                Math.max(y0, y) + DropSim.HEIGHT, Math.max(z0, z) + h};
    }

    private static void record(List<double[]> path, DropSim.Body b, int tick) {
        if (path != null) {
            path.add(new double[]{b.x(), b.y(), b.z(), tick});
        }
    }

    // ---- collisions with walls and the ledge (plain hitbox) -------------------------------------------

    private static final double EPS = 1e-7;

    /** The part of a vertical move {@code d} the body can make before a wall or the ledge stops it. */
    static double clampY(DropWorld w, double x, double y, double z, double d) {
        double h = DropSim.HALF_WIDTH;
        if (d == 0) {
            return 0;
        }
        double lo = d < 0 ? y + d : y + DropSim.HEIGHT;
        double hi = d < 0 ? y : y + DropSim.HEIGHT + d;
        if (!w.blocks(x - h, lo, z - h, x + h, hi, z + h)) {
            return d;
        }
        // find the nearest blocking face
        int bx1 = (int) Math.floor(x - h);
        int bx2 = (int) Math.ceil(x + h) - 1;
        int bz1 = (int) Math.floor(z - h);
        int bz2 = (int) Math.ceil(z + h) - 1;
        double best = d;
        int by1 = (int) Math.floor(lo);
        int by2 = (int) Math.ceil(hi) - 1;
        for (int by = by1; by <= by2; by++) {
            for (int bx = bx1; bx <= bx2; bx++) {
                for (int bz = bz1; bz <= bz2; bz++) {
                    byte k = w.get(bx, by, bz);
                    if (k != DropWorld.WALL && k != DropWorld.LEDGE) {
                        continue;
                    }
                    if (d < 0) {
                        best = Math.max(best, Math.min(0, by + 1 - y + EPS));
                    } else {
                        best = Math.min(best, Math.max(0, by - (y + DropSim.HEIGHT) - EPS));
                    }
                }
            }
        }
        return best;
    }

    /** The part of a move {@code d} along x before a wall or the ledge stops it. */
    static double clampX(DropWorld w, double x, double y, double z, double d) {
        double h = DropSim.HALF_WIDTH;
        if (d == 0) {
            return 0;
        }
        double lo = d < 0 ? x - h + d : x + h;
        double hi = d < 0 ? x - h : x + h + d;
        if (!w.blocks(lo, y, z - h, hi, y + DropSim.HEIGHT, z + h)) {
            return d;
        }
        double best = d;
        int bx1 = (int) Math.floor(lo);
        int bx2 = (int) Math.ceil(hi) - 1;
        int by1 = (int) Math.floor(y);
        int by2 = (int) Math.ceil(y + DropSim.HEIGHT) - 1;
        int bz1 = (int) Math.floor(z - h);
        int bz2 = (int) Math.ceil(z + h) - 1;
        for (int bx = bx1; bx <= bx2; bx++) {
            for (int by = by1; by <= by2; by++) {
                for (int bz = bz1; bz <= bz2; bz++) {
                    byte k = w.get(bx, by, bz);
                    if (k != DropWorld.WALL && k != DropWorld.LEDGE) {
                        continue;
                    }
                    if (d > 0) {
                        best = Math.min(best, Math.max(0, bx - (x + h) - EPS));
                    } else {
                        best = Math.max(best, Math.min(0, bx + 1 - (x - h) + EPS));
                    }
                }
            }
        }
        return best;
    }

    /** The part of a move {@code d} along z before a wall or the ledge stops it. */
    static double clampZ(DropWorld w, double x, double y, double z, double d) {
        double h = DropSim.HALF_WIDTH;
        if (d == 0) {
            return 0;
        }
        double lo = d < 0 ? z - h + d : z + h;
        double hi = d < 0 ? z - h : z + h + d;
        if (!w.blocks(x - h, y, lo, x + h, y + DropSim.HEIGHT, hi)) {
            return d;
        }
        double best = d;
        int bz1 = (int) Math.floor(lo);
        int bz2 = (int) Math.ceil(hi) - 1;
        int by1 = (int) Math.floor(y);
        int by2 = (int) Math.ceil(y + DropSim.HEIGHT) - 1;
        int bx1 = (int) Math.floor(x - h);
        int bx2 = (int) Math.ceil(x + h) - 1;
        for (int bz = bz1; bz <= bz2; bz++) {
            for (int by = by1; by <= by2; by++) {
                for (int bx = bx1; bx <= bx2; bx++) {
                    byte k = w.get(bx, by, bz);
                    if (k != DropWorld.WALL && k != DropWorld.LEDGE) {
                        continue;
                    }
                    if (d > 0) {
                        best = Math.min(best, Math.max(0, bz - (z + h) - EPS));
                    } else {
                        best = Math.max(best, Math.min(0, bz + 1 - (z - h) + EPS));
                    }
                }
            }
        }
        return best;
    }
}
