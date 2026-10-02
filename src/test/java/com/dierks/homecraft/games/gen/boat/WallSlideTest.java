package com.dierks.homecraft.games.gen.boat;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenFailed;
import com.dierks.homecraft.games.gen.api.PlanInput;
import com.dierks.homecraft.games.gen.api.Slots;
import org.junit.jupiter.api.Test;

import java.util.function.BiPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Red-team F08: a boat sliding along a wall. Java resolves a move axis by axis and zeroes the speed of any
 * axis it clips, so a straight wall on an axis only takes the small sideways part, but a stepped (diagonal)
 * wall's riser stops the boat's main axis: a "hard stop". The planner lays every straight of 40 or more on an
 * axis; here a boat drifting into each kind of wall shows why, and every long straight of a planned run is
 * slid along, both walls, with no hard stop.
 */
class WallSlideTest {

    /** Half a boat's width (1.375). */
    static final double HALF = 0.6875;

    /**
     * Slide a boat for {@code ticks} from (x, z) heading {@code deg} (the rider holds forward, drifting
     * {@code drift} degrees off it), {@code solid} the wall columns; the number of hard stops: ticks whose
     * speed falls by more than 30%.
     */
    static int hardStops(BiPredicate<Integer, Integer> solid, double x, double z, double deg, double drift, int ticks) {
        double h = Math.toRadians(deg + drift);
        double vx = 1.9 * Math.cos(Math.toRadians(deg));
        double vz = 1.9 * Math.sin(Math.toRadians(deg));
        int stops = 0;
        for (int t = 0; t < ticks; t++) {
            vx = vx * BoatLine.PACKED + BoatLine.THRUST * Math.cos(h);
            vz = vz * BoatLine.PACKED + BoatLine.THRUST * Math.sin(h);
            double before = Math.hypot(vx, vz);
            boolean zFirst = Math.abs(vx) < Math.abs(vz); // vanilla: the larger axis is resolved first
            double[] p = {x, z};
            boolean clipX;
            boolean clipZ;
            if (zFirst) {
                double mz = clip(solid, p, vz, false);
                clipZ = mz != vz;
                p[1] += mz;
                double mx = clip(solid, p, vx, true);
                clipX = mx != vx;
                p[0] += mx;
            } else {
                double mx = clip(solid, p, vx, true);
                clipX = mx != vx;
                p[0] += mx;
                double mz = clip(solid, p, vz, false);
                clipZ = mz != vz;
                p[1] += mz;
            }
            x = p[0];
            z = p[1];
            vx = clipX ? 0 : vx;
            vz = clipZ ? 0 : vz;
            if (Math.hypot(vx, vz) < 0.7 * before) {
                stops++;
            }
        }
        return stops;
    }

    /** How far the boat at {@code p} may move {@code d} along x ({@code alongX}) or z before a solid column. */
    static double clip(BiPredicate<Integer, Integer> solid, double[] p, double d, boolean alongX) {
        double lo = (alongX ? p[1] : p[0]) - HALF;
        double hi = (alongX ? p[1] : p[0]) + HALF;
        double edge = (alongX ? p[0] : p[1]) + (d > 0 ? HALF : -HALF);
        int from = (int) Math.floor(lo + 1e-9);
        int to = (int) Math.ceil(hi - 1e-9) - 1;
        if (d > 0) {
            for (int c = (int) Math.floor(edge + 1e-9); c <= (int) Math.floor(edge + d - 1e-9); c++) {
                for (int k = from; k <= to; k++) {
                    if (alongX ? solid.test(c, k) : solid.test(k, c)) {
                        return Math.max(0, c - edge);
                    }
                }
            }
        } else if (d < 0) {
            for (int c = (int) Math.ceil(edge - 1e-9) - 1; c >= (int) Math.floor(edge + d + 1e-9); c--) {
                for (int k = from; k <= to; k++) {
                    if (alongX ? solid.test(c, k) : solid.test(k, c)) {
                        return Math.min(0, c + 1 - edge);
                    }
                }
            }
        }
        return d;
    }

    /** A lane {@code w} wide from (0, 0) heading {@code deg}: its walls are every column not on it. */
    static BiPredicate<Integer, Integer> lane(double deg, double w) {
        double tx = Math.cos(Math.toRadians(deg));
        double tz = Math.sin(Math.toRadians(deg));
        return (x, z) -> Math.abs((x + 0.5) * -tz + (z + 0.5) * tx) > w / 2.0 + 1e-9;
    }

    @Test
    void aStraightWallOnAnAxisLetsTheBoatSlide() {
        assertEquals(0, hardStops(lane(0, 7), 0, 2.6, 0, 3, 400), "along x, drifting into the right wall");
        assertEquals(0, hardStops(lane(0, 7), 0, -2.6, 0, -3, 400), "along x, into the left wall");
        assertEquals(0, hardStops(lane(90, 7), -2.6, 0, 90, 3, 400), "along z, into one wall");
        assertEquals(0, hardStops(lane(180, 9), 0, 3.6, 180, -3, 400), "back along x on the easy road's 9");
    }

    @Test
    void aSteppedDiagonalWallStopsIt() {
        int stops = 0;
        for (int side = -1; side <= 1; side += 2) {
            double off = side * 2.6;
            stops += hardStops(lane(20, 7), -off * Math.sin(Math.toRadians(20)), off * Math.cos(Math.toRadians(20)), 20,
                    side * 3, 400);
        }
        assertTrue(stops > 0, "a 20-degree straight's stepped walls stop a drifting boat: " + stops + " hard stops");
    }

    @Test
    void everyLongStraightOfAPlannedRunIsSlidAlongWithNoHardStop() throws GenFailed {
        Box half = new Box(6080, 96, 2880, 6559, 271, 3519);
        for (long seed : new long[]{2, 3}) {
            PlanInput in = new PlanInput(Slots.ICE_BOAT, half, 'A', 20725, 0, seed, "medium", 6, 0, null);
            MountainPlanner.Made m = MountainPlanner.made(in);
            RasterV4 t = m.raster;
            BiPredicate<Integer, Integer> wall = (x, z) -> !t.drive(x, z);
            int stretches = 0;
            Skeleton sk = m.cand.sk();
            for (Centreline.Element e : sk.line.elements()) {
                if (e.arc() || e.length < Skeleton.AXIS_LONG) {
                    continue;
                }
                // the plain lane: no piece, neck or gate (their tapers and fences are walls across the way, by
                // design), the pit's back wall and the finish's end wall aside
                double from = Math.max(e.s0, Frame.PIT) + 2;
                for (double u = from; u <= Math.min(e.s1(), sk.finish); u += 1) {
                    boolean plain = u < Math.min(e.s1(), sk.finish) - 1 && m.pieces.at(u) == null
                            && m.cand.drops().width(u) == sk.width(u) && t.gateAt(u, sk.line.at(u)[0], sk.line.at(u)[1]) == 0
                            && !t.inGates(u, 4);
                    if (plain) {
                        continue;
                    }
                    if (u - from >= 20) {
                        stretches++;
                        double deg = Math.toDegrees(e.h0);
                        double tx = Math.cos(e.h0);
                        double tz = Math.sin(e.h0);
                        double[] p = sk.line.at(from + 1);
                        for (int side = -1; side <= 1; side += 2) {
                            // the boat's side just off the lane's own edge there (the blocks', not the line's)
                            double edge = 0;
                            while (t.drive((int) Math.floor(p[0] - tz * side * (edge + 0.05)),
                                    (int) Math.floor(p[1] + tx * side * (edge + 0.05)))) {
                                edge += 0.05;
                            }
                            double w = edge - HALF - 0.02;
                            double x = p[0] - tz * side * w;
                            double z = p[1] + tx * side * w;
                            int ticks = (int) ((u - from - 4) / 1.9);
                            int stops = hardStops(wall, x, z, deg, side * 3, Math.max(1, ticks));
                            assertEquals(0, stops, BoatStyle.of(seed) + " seed " + seed + ": " + Math.round(u - from)
                                    + " blocks of straight from s " + Math.round(from) + " (heading " + deg
                                    + "), side " + side);
                        }
                    }
                    from = u + 1;
                }
            }
            assertTrue(stretches > 5, "seed " + seed + ": " + stretches + " stretches of long straight slid along");
        }
    }
}
