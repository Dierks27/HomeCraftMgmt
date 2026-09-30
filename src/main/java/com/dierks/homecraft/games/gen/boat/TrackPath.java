package com.dierks.homecraft.games.gen.boat;

import com.dierks.homecraft.games.gen.api.GenRandom;

import java.util.ArrayList;
import java.util.List;

/**
 * The Mountain Run's centreline (Course Variety §2.3): a rounded-square spiral that starts on the rim
 * at its highest point and winds inward round the viewing stand. Pure geometry in the half's own
 * block columns (x and z from 0 at the half's corner), no Bukkit, no heights: those are
 * {@link TrackProfile}'s.
 *
 * <p><b>Why a rounded square.</b> A jump needs a straight landing strip, and a polar curve bends away
 * under a flying boat. A square spiral has four straights a turn, fills the square half and keeps its
 * rings exactly one pitch p apart: leg k lies along one side of the square at offset
 * {@code a_k = a_0 - floor(k p / 4)} from the stand's centre C, so rings on the same side are p
 * apart and the gap between their walls is {@code p - w - 2} (9 on every tier).
 *
 * <p><b>Exact on whole blocks.</b> C is the middle of the stand's centre column and every offset is a
 * whole number, so a straight's centreline runs down the middle of a column and a lane of odd width
 * w covers exactly w columns; corner arcs have whole radii, so every straight begins and ends in the
 * middle of a column too. A column's nearest centreline point is found on the analytic segments
 * (straights and quarter arcs), never on samples, so the raster is exact and the same everywhere.
 *
 * <p><b>Seeded</b> ({@code fork("track:" + t)}): which side leg 0 lies on and which way round
 * (8 orientations), a_0 from 54 to 56, and each corner's radius in the tier's range. Inner corners
 * keep near the tier's smallest radius, so the inner straights stay long enough for a drop and its
 * landing strip.
 */
final class TrackPath {

    /** The four sides of the square, as the direction from C to the side: north, east, south, west. */
    static final int[][] SIDE = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
    static final String[] SIDE_NAME = {"north", "east", "south", "west"};

    /** One piece of centreline: a straight or a quarter arc, with its arc length from the start. */
    static final class Seg {
        final boolean arc;
        /** Its index in the path, and the leg it belongs to (an arc belongs to the leg it leaves). */
        final int index;
        final int leg;
        final double s0;
        final double len;
        // a straight: from (ax, az) along the unit (tx, tz)
        final double ax;
        final double az;
        final double tx;
        final double tz;
        // an arc: centre, radius, the angle it starts at and which way it turns (+1 or -1)
        final double ox;
        final double oz;
        final double r;
        final double phi0;
        final int turn;

        private Seg(boolean arc, int index, int leg, double s0, double len, double ax, double az, double tx, double tz,
                    double ox, double oz, double r, double phi0, int turn) {
            this.arc = arc;
            this.index = index;
            this.leg = leg;
            this.s0 = s0;
            this.len = len;
            this.ax = ax;
            this.az = az;
            this.tx = tx;
            this.tz = tz;
            this.ox = ox;
            this.oz = oz;
            this.r = r;
            this.phi0 = phi0;
            this.turn = turn;
        }

        double s1() {
            return s0 + len;
        }
    }

    /** Where a column is from the centreline: its distance, the s of its nearest point, and its offset (+ right). */
    static final class Near {
        double dist = Double.MAX_VALUE;
        double s;
        double off;
        Seg seg;
        /** Whether the nearest point is the path's very start or end (the column is beyond an end). */
        boolean end;
    }

    final double cx;
    final double cz;
    final int side0;
    /** +1 clockwise (every corner a right turn, the stand on the right), -1 anticlockwise. */
    final int dir;
    final int a0;
    final int pitch;
    final int[] offsets;
    final int[] radii;
    final List<Seg> segs;
    /** The straights, by leg: {@code straight(k)}. */
    private final Seg[] straights;
    /** The whole centreline's length (the last straight drawn to its farthest). */
    final double length;

    private TrackPath(double cx, double cz, int side0, int dir, int a0, int pitch, int[] offsets, int[] radii,
                      List<Seg> segs, Seg[] straights) {
        this.cx = cx;
        this.cz = cz;
        this.side0 = side0;
        this.dir = dir;
        this.a0 = a0;
        this.pitch = pitch;
        this.offsets = offsets;
        this.radii = radii;
        this.segs = segs;
        this.straights = straights;
        Seg last = segs.get(segs.size() - 1);
        this.length = last.s1();
    }

    /** The side leg {@code k} lies on (0 north, 1 east, 2 south, 3 west). */
    int side(int k) {
        return Math.floorMod(side0 + dir * k, 4);
    }

    /** How many legs: 0 (the launch pit's) to the last (the finish's). */
    int legs() {
        return straights.length;
    }

    /** Leg {@code k}'s straight. */
    Seg straight(int k) {
        return straights[k];
    }

    /** "clockwise from north" and so on, for the summary. */
    String describe() {
        return (dir > 0 ? "clockwise" : "anticlockwise") + " from " + SIDE_NAME[side0];
    }

    // ---- drawing ------------------------------------------------------------------------------------

    /**
     * A spiral drawn from {@code r} for {@code level}, centred on (cx, cz) (the middle of the stand's
     * centre column, in half columns).
     */
    static TrackPath draw(GenRandom r, BoatPlanner.Level level, double cx, double cz) {
        int side0 = r.nextInt(4);
        int dir = r.nextBoolean() ? 1 : -1;
        int a0 = r.nextInt(BoatPlanner.A0_MIN, BoatPlanner.A0_MAX);
        int[] radii = new int[level.lastLeg()];
        for (int k = 0; k < radii.length; k++) {
            radii[k] = r.nextInt(level.minRadius(), level.radiusCap(k));
        }
        return of(cx, cz, side0, dir, a0, level, radii);
    }

    /** The {@code SAFE_SPIRAL}'s path: a_0 = 55 and every corner the tier's smallest radius. */
    static TrackPath safe(int side0, int dir, BoatPlanner.Level level, double cx, double cz) {
        int[] radii = new int[level.lastLeg()];
        java.util.Arrays.fill(radii, level.minRadius());
        return of(cx, cz, side0, dir, BoatPlanner.A0_SAFE, level, radii);
    }

    /** The spiral with these choices. */
    static TrackPath of(double cx, double cz, int side0, int dir, int a0, BoatPlanner.Level level, int[] radii) {
        int legs = level.lastLeg() + 1;
        int p = level.pitch();
        int[] a = new int[legs + 1];
        for (int k = 0; k <= legs; k++) {
            a[k] = a0 - (k * p) / 4;
        }
        int before = a0 + (p + 3) / 4; // the side behind the pit's back wall
        List<Seg> segs = new ArrayList<>();
        Seg[] straights = new Seg[legs];
        // leg k runs along side(k) at offset a[k]; its direction is toward side(k + 1)
        double s = 0;
        double[] from = null;
        for (int k = 0; k < legs; k++) {
            int sd = Math.floorMod(side0 + dir * k, 4);
            int next = Math.floorMod(side0 + dir * (k + 1), 4);
            int prev = Math.floorMod(side0 + dir * (k - 1), 4);
            double tx = SIDE[next][0];
            double tz = SIDE[next][1];
            if (k == 0) {
                // the pit's back: where leg 0 meets the side behind it, half a block on (a column boundary)
                double px = cx + SIDE[sd][0] * a[0] + SIDE[prev][0] * before;
                double pz = cz + SIDE[sd][1] * a[0] + SIDE[prev][1] * before;
                from = new double[]{px + tx * 0.5, pz + tz * 0.5};
            }
            double endX;
            double endZ;
            if (k < legs - 1) {
                // corner k: where this leg meets the next, rounded with radius radii[k]
                double qx = cx + SIDE[sd][0] * a[k] + SIDE[next][0] * a[k + 1];
                double qz = cz + SIDE[sd][1] * a[k] + SIDE[next][1] * a[k + 1];
                int rad = radii[k];
                endX = qx - tx * rad;
                endZ = qz - tz * rad;
                double len = Math.abs(endX - from[0]) + Math.abs(endZ - from[1]);
                Seg st = new Seg(false, segs.size(), k, s, len, from[0], from[1], tx, tz, 0, 0, 0, 0, 0);
                segs.add(st);
                straights[k] = st;
                s += len;
                // the arc: its centre is rad in from the corner along both legs
                int after = Math.floorMod(side0 + dir * (k + 2), 4);
                double ux = SIDE[after][0];
                double uz = SIDE[after][1];
                double ox = qx - tx * rad + ux * rad;
                double oz = qz - tz * rad + uz * rad;
                double phi0 = StrictMath.atan2(endZ - oz, endX - ox);
                // the turn's sense in (x, z): cross of the two directions
                int turn = tx * uz - tz * ux > 0 ? 1 : -1;
                double alen = rad * StrictMath.PI / 2;
                segs.add(new Seg(true, segs.size(), k, s, alen, 0, 0, 0, 0, ox, oz, rad, phi0, turn));
                s += alen;
                from = new double[]{qx + ux * rad, qz + uz * rad};
            } else {
                // the last leg: drawn as far as the ring outside on the side it heads for allows
                int outer = Math.max(0, k - 3);
                double limit = a[outer] - level.width() / 2.0 - BoatPlanner.END_ROOM;
                double qx = cx + SIDE[sd][0] * a[k] + SIDE[next][0] * limit;
                double qz = cz + SIDE[sd][1] * a[k] + SIDE[next][1] * limit;
                double len = Math.max(0, (qx - from[0]) * tx + (qz - from[1]) * tz);
                len = Math.floor(len);
                Seg st = new Seg(false, segs.size(), k, s, len, from[0], from[1], tx, tz, 0, 0, 0, 0, 0);
                segs.add(st);
                straights[k] = st;
                s += len;
            }
        }
        return new TrackPath(cx, cz, side0, dir, a0, p, a, radii, segs, straights);
    }

    // ---- reading ------------------------------------------------------------------------------------

    /** The point {@code s} along the centreline, {x, z}. */
    double[] at(double s) {
        Seg g = segAt(s);
        double u = Math.max(0, Math.min(g.len, s - g.s0));
        if (!g.arc) {
            return new double[]{g.ax + g.tx * u, g.az + g.tz * u};
        }
        double phi = g.phi0 + g.turn * u / g.r;
        return new double[]{g.ox + g.r * StrictMath.cos(phi), g.oz + g.r * StrictMath.sin(phi)};
    }

    /** The unit direction of travel {@code s} along, {x, z}. */
    double[] tangent(double s) {
        Seg g = segAt(s);
        if (!g.arc) {
            return new double[]{g.tx, g.tz};
        }
        double u = Math.max(0, Math.min(g.len, s - g.s0));
        double phi = g.phi0 + g.turn * u / g.r;
        return new double[]{-g.turn * StrictMath.sin(phi), g.turn * StrictMath.cos(phi)};
    }

    /** The segment {@code s} lies in (the first at or before 0, the last past the end). */
    Seg segAt(double s) {
        for (Seg g : segs) {
            if (s < g.s1()) {
                return g;
            }
        }
        return segs.get(segs.size() - 1);
    }

    /**
     * The centreline point nearest the middle of column (x, z), searched only among segments within
     * {@code reach}, and only up to {@code sEnd} along; {@code null} when none is that close.
     */
    Near nearest(int x, int z, double reach, double sEnd) {
        double px = x + 0.5;
        double pz = z + 0.5;
        Near best = null;
        for (Seg g : segs) {
            if (g.s0 >= sEnd) {
                break;
            }
            double maxLen = Math.min(g.len, sEnd - g.s0);
            Near n = near(g, px, pz, maxLen, reach);
            if (n != null && (best == null || n.dist < best.dist - 1e-12)) {
                best = n;
            }
        }
        if (best != null) {
            // beyond the path's very start or end: not beside it
            best.end = (best.seg == segs.get(0) && best.s <= 1e-9) || best.s >= sEnd - 1e-9;
        }
        return best;
    }

    /**
     * The nearest centreline point that column (x, z) lies inside of (on the stand's side of the
     * track, square across from it: not past a segment's end), within {@code reach} and before
     * {@code sEnd}; {@code null} when none. For a column between two rings that is the outer ring.
     */
    Near nearestInside(int x, int z, double reach, double sEnd) {
        double px = x + 0.5;
        double pz = z + 0.5;
        Near best = null;
        for (Seg g : segs) {
            if (g.s0 >= sEnd) {
                break;
            }
            double maxLen = Math.min(g.len, sEnd - g.s0);
            Near n = near(g, px, pz, maxLen, reach);
            if (n == null || n.off * inside() <= 0 || Math.abs(n.dist - Math.abs(n.off)) > 1e-9) {
                continue; // outside it, or past one of its ends
            }
            if (best == null || n.dist < best.dist - 1e-12) {
                best = n;
            }
        }
        return best;
    }

    private static Near near(Seg g, double px, double pz, double maxLen, double reach) {
        if (!g.arc) {
            double dx = px - g.ax;
            double dz = pz - g.az;
            double along = dx * g.tx + dz * g.tz;
            double across = dx * -g.tz + dz * g.tx;
            if (Math.abs(across) > reach || along < -reach || along > maxLen + reach) {
                return null;
            }
            double u = Math.max(0, Math.min(maxLen, along));
            Near n = new Near();
            n.seg = g;
            n.s = g.s0 + u;
            double qx = g.ax + g.tx * u;
            double qz = g.az + g.tz * u;
            n.dist = Math.hypot(px - qx, pz - qz);
            n.off = (px - qx) * -g.tz + (pz - qz) * g.tx;
            return n;
        }
        double dx = px - g.ox;
        double dz = pz - g.oz;
        double rho = Math.hypot(dx, dz);
        if (Math.abs(rho - g.r) > reach) {
            return null; // every point of the arc is farther than that
        }
        double psi = StrictMath.atan2(dz, dx);
        double rel = (psi - g.phi0) * g.turn;
        rel = rel - 2 * Math.PI * Math.floor(rel / (2 * Math.PI));
        double sweep = maxLen / g.r;
        Near n = new Near();
        n.seg = g;
        if (rel <= sweep) {
            n.s = g.s0 + rel * g.r;
            n.dist = Math.abs(rho - g.r);
            // right of travel: toward the centre on a right turn (+1), away on a left
            n.off = g.turn > 0 ? g.r - rho : rho - g.r;
        } else {
            // nearer one end: take the nearer end point
            double phiA = g.phi0;
            double phiB = g.phi0 + g.turn * sweep;
            double ax = g.ox + g.r * StrictMath.cos(phiA);
            double az = g.oz + g.r * StrictMath.sin(phiA);
            double bx = g.ox + g.r * StrictMath.cos(phiB);
            double bz = g.oz + g.r * StrictMath.sin(phiB);
            double da = Math.hypot(px - ax, pz - az);
            double db = Math.hypot(px - bx, pz - bz);
            boolean atA = da <= db;
            double qx = atA ? ax : bx;
            double qz = atA ? az : bz;
            n.s = atA ? g.s0 : g.s0 + maxLen;
            n.dist = atA ? da : db;
            double phi = atA ? phiA : phiB;
            double tx = -g.turn * StrictMath.sin(phi);
            double tz = g.turn * StrictMath.cos(phi);
            n.off = (px - qx) * -tz + (pz - qz) * tx;
        }
        if (n.dist > reach) {
            return null;
        }
        return n;
    }

    /** The offset sign that points toward C (the inside of every bend): +1 clockwise, -1 anticlockwise. */
    int inside() {
        return dir;
    }
}
