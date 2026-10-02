package com.dierks.homecraft.games.gen.boat;

import java.util.ArrayList;
import java.util.List;

/**
 * A Mountain Run v2's centreline (MOUNTAIN-V2-SPEC §4.2): a chain of straights and circular arcs of any
 * heading, in half-local doubles, parameterised by s (blocks along it from the pit's back wall), with a
 * bucket index so "which point of the line is nearest this column" costs about ten tests a column.
 *
 * <p><b>Headings.</b> A heading θ points along (cos θ, sin θ) in (x, z). Minecraft's z runs south, so θ
 * growing turns a rider facing east toward the south: to the right. An arc's {@code turn} is +1 for a
 * right-hand bend, -1 for a left-hand one; its centre is on that side.
 *
 * <p><b>Why its own index.</b> The v3 spiral's {@code TrackPath} knew its four sides; a serpentine of 150
 * elements over 480 x 640 columns needs a spatial index: every element is cut into pieces of at most
 * {@value #PIECE} blocks, and each 16 x 16 bucket lists the pieces whose box, grown by the query reach,
 * touches it. {@link #nearest} then tests only those. Translation-exact: everything is half-local.
 * Pure, immutable once built.
 */
public final class Centreline {

    /** Index pieces are at most this long, blocks along s. */
    static final double PIECE = 8;
    /** Bucket size, columns. */
    static final int BUCKET = 16;
    private static final double EPS = 1e-9;

    /** One element: a straight ({@code turn} 0) or an arc. */
    public static final class Element {
        public final int index;
        public final double s0;
        public final double length;
        public final double x0;
        public final double z0;
        /** The heading at the start, radians. */
        public final double h0;
        /** The radius (arcs), else 0. */
        public final double radius;
        /** +1 a right-hand arc, -1 a left-hand one, 0 a straight. */
        public final int turn;
        /** An arc's centre (NaN for a straight). */
        public final double cx;
        public final double cz;

        Element(int index, double s0, double length, double x0, double z0, double h0, double radius, int turn) {
            this.index = index;
            this.s0 = s0;
            this.length = length;
            this.x0 = x0;
            this.z0 = z0;
            this.h0 = h0;
            this.radius = radius;
            this.turn = turn;
            if (turn != 0) {
                cx = x0 + turn * radius * -Math.sin(h0);
                cz = z0 + turn * radius * Math.cos(h0);
            } else {
                cx = Double.NaN;
                cz = Double.NaN;
            }
        }

        public boolean arc() {
            return turn != 0;
        }

        public double s1() {
            return s0 + length;
        }

        /** The signed turn, radians (right positive). */
        public double angle() {
            return arc() ? turn * length / radius : 0;
        }

        /** The heading {@code t} blocks in. */
        public double heading(double t) {
            return arc() ? h0 + turn * t / radius : h0;
        }

        /** The heading at the end. */
        public double h1() {
            return heading(length);
        }

        /** The point {@code t} blocks in. */
        public double[] at(double t) {
            if (!arc()) {
                return new double[]{x0 + t * Math.cos(h0), z0 + t * Math.sin(h0)};
            }
            double h = heading(t);
            return new double[]{cx + turn * radius * Math.sin(h), cz - turn * radius * Math.cos(h)};
        }

        /** The end point. */
        public double[] end() {
            return at(length);
        }

        /**
         * The nearest point to (x, z) within [{@code from}, {@code to}] blocks in: {t, distance, signed
         * offset (right of the line positive)}.
         */
        double[] nearest(double x, double z, double from, double to) {
            if (!arc()) {
                double dx = Math.cos(h0);
                double dz = Math.sin(h0);
                double t = (x - x0) * dx + (z - z0) * dz;
                t = Math.max(from, Math.min(to, t));
                double px = x0 + t * dx;
                double pz = z0 + t * dz;
                double off = (x - x0) * -dz + (z - z0) * dx;
                return new double[]{t, Math.hypot(x - px, z - pz), off};
            }
            double qx = x - cx;
            double qz = z - cz;
            double rq = Math.hypot(qx, qz);
            // the angle round the centre of the start, and of q; the arc sweeps turn * t / r from it
            double phi0 = Math.atan2(z0 - cz, x0 - cx);
            double phiQ = Math.atan2(qz, qx);
            double d = turn * (phiQ - phi0);
            d = d - 2 * Math.PI * Math.floor(d / (2 * Math.PI));
            double t = d * radius;
            double best;
            double bestDist;
            if (t >= from - EPS && t <= to + EPS && rq > EPS) {
                best = Math.max(from, Math.min(to, t));
                bestDist = Math.abs(rq - radius);
            } else {
                double[] a = at(from);
                double[] b = at(to);
                double da = Math.hypot(x - a[0], z - a[1]);
                double db = Math.hypot(x - b[0], z - b[1]);
                // the far side of the circle: whichever end is nearer
                best = da <= db ? from : to;
                bestDist = Math.min(da, db);
            }
            double off = turn * (radius - rq);
            if (best != t) {
                double[] p = at(best);
                double h = heading(best);
                off = (x - p[0]) * -Math.sin(h) + (z - p[1]) * Math.cos(h);
            }
            return new double[]{best, bestDist, off};
        }
    }

    /** The nearest point of the line to a column. */
    public record Near(Element element, double s, double distance, double offset) {
    }

    private final List<Element> elements;
    private final double length;
    private final int bx;
    private final int bz;
    private final double reach;
    /** Per bucket: the pieces {element, from, to} listed there. */
    private final List<List<double[]>> buckets;

    /**
     * The line through {@code elements} (in order, joined end to start), indexed over a {@code sizeX} x
     * {@code sizeZ} area for queries within {@code reach} of it.
     */
    public Centreline(List<Element> elements, int sizeX, int sizeZ, double reach) {
        this.elements = List.copyOf(elements);
        Element last = elements.isEmpty() ? null : elements.get(elements.size() - 1);
        this.length = last == null ? 0 : last.s1();
        this.reach = reach;
        this.bx = Math.max(1, (sizeX + BUCKET - 1) / BUCKET);
        this.bz = Math.max(1, (sizeZ + BUCKET - 1) / BUCKET);
        buckets = new ArrayList<>(bx * bz);
        for (int i = 0; i < bx * bz; i++) {
            buckets.add(null);
        }
        for (Element e : elements) {
            int pieces = Math.max(1, (int) Math.ceil(e.length / PIECE));
            for (int p = 0; p < pieces; p++) {
                double from = e.length * p / pieces;
                double to = e.length * (p + 1) / pieces;
                double[] a = e.at(from);
                double[] b = e.at(to);
                double[] m = e.at((from + to) / 2);
                double grow = reach + 1.5;
                double minX = Math.min(a[0], Math.min(b[0], m[0])) - grow;
                double maxX = Math.max(a[0], Math.max(b[0], m[0])) + grow;
                double minZ = Math.min(a[1], Math.min(b[1], m[1])) - grow;
                double maxZ = Math.max(a[1], Math.max(b[1], m[1])) + grow;
                int x0 = Math.max(0, (int) Math.floor(minX / BUCKET));
                int x1 = Math.min(bx - 1, (int) Math.floor(maxX / BUCKET));
                int z0 = Math.max(0, (int) Math.floor(minZ / BUCKET));
                int z1 = Math.min(bz - 1, (int) Math.floor(maxZ / BUCKET));
                for (int x = x0; x <= x1; x++) {
                    for (int z = z0; z <= z1; z++) {
                        List<double[]> list = buckets.get(x * bz + z);
                        if (list == null) {
                            list = new ArrayList<>();
                            buckets.set(x * bz + z, list);
                        }
                        list.add(new double[]{e.index, from, to});
                    }
                }
            }
        }
    }

    public List<Element> elements() {
        return elements;
    }

    public double length() {
        return length;
    }

    /** The element {@code s} along (the first at or before the start, the last past the end). */
    public Element elementAt(double s) {
        int lo = 0;
        int hi = elements.size() - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (elements.get(mid).s0 <= s) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return elements.get(lo);
    }

    /** The point {@code s} along (clamped to the line). */
    public double[] at(double s) {
        Element e = elementAt(s);
        return e.at(Math.max(0, Math.min(e.length, s - e.s0)));
    }

    /** The heading {@code s} along, radians. */
    public double heading(double s) {
        Element e = elementAt(s);
        return e.heading(Math.max(0, Math.min(e.length, s - e.s0)));
    }

    /** The unit direction {@code s} along. */
    public double[] tangent(double s) {
        double h = heading(s);
        return new double[]{Math.cos(h), Math.sin(h)};
    }

    /** Whether the bucket of column (x, z) lists anything at all. */
    public boolean indexed(int x, int z) {
        int i = bucket(x + 0.5, z + 0.5);
        return i >= 0 && buckets.get(i) != null;
    }

    private int bucket(double x, double z) {
        int ix = (int) Math.floor(x / BUCKET);
        int iz = (int) Math.floor(z / BUCKET);
        if (ix < 0 || iz < 0 || ix >= bx || iz >= bz) {
            return -1;
        }
        return ix * bz + iz;
    }

    /**
     * The nearest point of the line to (x, z) within the index's reach, or {@code null}: ties go to the
     * smaller s, so the answer never depends on the order the pieces were listed.
     */
    public Near nearest(double x, double z) {
        int i = bucket(x, z);
        if (i < 0 || buckets.get(i) == null) {
            return null;
        }
        Element bestE = null;
        double bestT = 0;
        double bestD = Double.MAX_VALUE;
        double bestOff = 0;
        for (double[] piece : buckets.get(i)) {
            Element e = elements.get((int) piece[0]);
            double[] n = e.nearest(x, z, piece[1], piece[2]);
            double s = e.s0 + n[0];
            if (n[1] < bestD - EPS || (Math.abs(n[1] - bestD) <= EPS && bestE != null && s < bestE.s0 + bestT)) {
                bestD = n[1];
                bestE = e;
                bestT = n[0];
                bestOff = n[2];
            }
        }
        if (bestE == null || bestD > reach) {
            return null;
        }
        return new Near(bestE, bestE.s0 + bestT, bestD, bestOff);
    }

    /** Builds a line element by element, each starting where the last ended. */
    public static final class Builder {
        private final List<Element> out = new ArrayList<>();
        private double x;
        private double z;
        private double h;
        private double s;

        /** A line starting at (x, z), heading {@code h} radians. */
        public Builder(double x, double z, double h) {
            this.x = x;
            this.z = z;
            this.h = h;
        }

        public Builder straight(double length) {
            if (length > EPS) {
                add(new Element(out.size(), s, length, x, z, h, 0, 0));
            }
            return this;
        }

        /** An arc of radius r turning {@code angle} radians (right positive). */
        public Builder arc(double r, double angle) {
            if (Math.abs(angle) > EPS) {
                add(new Element(out.size(), s, r * Math.abs(angle), x, z, h, r, angle > 0 ? 1 : -1));
            }
            return this;
        }

        private void add(Element e) {
            out.add(e);
            double[] p = e.end();
            x = p[0];
            z = p[1];
            h = e.h1();
            s = e.s1();
        }

        public double x() {
            return x;
        }

        public double z() {
            return z;
        }

        public double heading() {
            return h;
        }

        public double s() {
            return s;
        }

        public List<Element> elements() {
            return List.copyOf(out);
        }

        public Centreline build(int sizeX, int sizeZ, double reach) {
            return new Centreline(out, sizeX, sizeZ, reach);
        }
    }
}
