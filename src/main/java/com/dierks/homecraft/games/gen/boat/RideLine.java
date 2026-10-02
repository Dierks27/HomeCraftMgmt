package com.dierks.homecraft.games.gen.boat;

import java.util.ArrayList;
import java.util.List;

/**
 * The line {@link BoatLine} rides and {@link FlowScore} measures (MOUNTAIN-V2-SPEC §3.1, §5.4, §6): the
 * race from the start to the finish mark as parts of signed curvature, with the lips and the fast ice.
 *
 * <p>On a Winding Road it is the centreline, element for element. On a Slalom every gate set is replaced
 * by its <b>gate line</b>, the C¹ curve through the opening centres: a spacing of lead-in from the axis
 * to the first opening, an S between each two openings (two equal arcs of opposite hands, the second
 * undoing the first), and a spacing of lead-out back to the axis. An S that moves {@code Δ} across in
 * {@code L} along has arcs turning φ = 2 atan(Δ / L) of radius (L / 2) / sin φ; on a sweep the corridor's
 * own curvature is added to each arc. The rider swings W - g across between gates, so a set's implied
 * radius is what tells a fair set from a cruel one (§5.4: at least 12, 16 on easy).
 *
 * <p>Line positions ({@code s0}) run along the line itself; each part also keeps the centreline range it
 * stands for, so a lip placed on the centreline finds its place on the line. Pure.
 */
public final class RideLine {

    /**
     * One part: from {@code s0} along the line, {@code len} long, of signed curvature {@code kappa}
     * (right-hand positive, 0 a straight), on friction {@code f}, standing for centreline {@code c0..c1}
     * of element {@code element}; {@code gate} for a gate line's arcs.
     */
    public record Part(double s0, double len, double kappa, double f, double c0, double c1, int element,
                       boolean gate) {

        public boolean arc() {
            return Math.abs(kappa) > 1e-12;
        }

        public double radius() {
            return arc() ? 1 / Math.abs(kappa) : 0;
        }

        public double s1() {
            return s0 + len;
        }

        /** The signed turn, radians. */
        public double angle() {
            return kappa * len;
        }
    }

    /** A lip on the line: where (line s and centreline s) and how far it drops. */
    public record Lip(double s, double c, int drop) {
    }

    /** A gate line's arc flatter than this (radius over 1,000) rides as a straight. */
    static final double FLAT = 1e-3;

    public final List<Part> parts;
    public final List<Lip> lips;
    /** Fast ice on the line: {from, to, f} along the line. */
    public final List<double[]> fast;
    public final double length;

    RideLine(List<Part> parts, List<Lip> lips, List<double[]> fast) {
        this.parts = List.copyOf(parts);
        this.lips = List.copyOf(lips);
        this.fast = List.copyOf(fast);
        this.length = parts.isEmpty() ? 0 : parts.get(parts.size() - 1).s1();
    }

    /**
     * The race line of {@code sk}, from its start to its finish, with lips {@code drops} and fast ice
     * {@code fast} ({from, to, f} along the centreline).
     */
    public static RideLine of(Skeleton sk, List<DropPlan.Drop> drops, List<double[]> fast) {
        List<Part> parts = new ArrayList<>();
        double from = sk.start;
        double to = sk.finish;
        double s = 0;
        double c = from;
        List<Skeleton.GateSet> sets = sk.gates;
        int gi = 0;
        while (c < to - 1e-9) {
            Skeleton.GateSet g = gi < sets.size() ? sets.get(gi) : null;
            double gFrom = g == null ? Double.MAX_VALUE : g.from() - g.spacing();
            if (g != null && c >= gFrom - 1e-9) {
                s = gateLine(sk, g, parts, s);
                c = g.to() + g.spacing();
                gi++;
                continue;
            }
            Centreline.Element e = sk.line.elementAt(c);
            double end = Math.min(Math.min(e.s1(), to), gFrom);
            if (end <= c + 1e-9) {
                end = Math.min(e.s1(), to);
                if (end <= c + 1e-9) {
                    break;
                }
            }
            double kappa = e.arc() ? e.turn / e.radius : 0;
            parts.add(new Part(s, end - c, kappa, BoatLine.PACKED, c, end, e.index, false));
            s += end - c;
            c = end;
        }
        RideLine bare = new RideLine(parts, List.of(), List.of());
        List<Lip> lips = new ArrayList<>();
        for (DropPlan.Drop d : drops) {
            if (d.s() > from && d.s() < to) {
                lips.add(new Lip(bare.toLine(d.s()), d.s(), d.drop()));
            }
        }
        List<double[]> onLine = new ArrayList<>();
        for (double[] f : fast) {
            double a = Math.max(from, f[0]);
            double b = Math.min(to, f[1]);
            if (b > a) {
                onLine.add(new double[]{bare.toLine(a), bare.toLine(b), f[2]});
            }
        }
        return new RideLine(parts, lips, onLine);
    }

    /** The gate line of set {@code g} appended from line position {@code s}; the line position after it. */
    static double gateLine(Skeleton sk, Skeleton.GateSet g, List<Part> parts, double s) {
        double half = (sk.tier.width - sk.tier.gate) / 2.0;
        double prev = 0;
        double c = g.from() - g.spacing();
        int n = g.gates();
        for (int i = 0; i <= n; i++) {
            double target = i < n ? g.side(i) * half : 0;
            s = sCurve(sk, parts, s, c, g.spacing(), target - prev);
            c += g.spacing();
            prev = target;
        }
        return s;
    }

    /** An S moving {@code delta} across (right positive) over {@code along} of centreline from {@code c}. */
    static double sCurve(Skeleton sk, List<Part> parts, double s, double c, double along, double delta) {
        if (Math.abs(delta) < 1e-9) {
            Centreline.Element e = sk.line.elementAt(c + along / 2);
            double kappa = e.arc() ? e.turn / e.radius : 0;
            parts.add(new Part(s, along, kappa, BoatLine.PACKED, c, c + along, e.index, true));
            return s + along;
        }
        double phi = 2 * Math.atan(Math.abs(delta) / along);
        double radius = (along / 2) / Math.sin(phi);
        double arc = radius * phi;
        double hand = Math.signum(delta);
        for (int k = 0; k < 2; k++) {
            double c0 = c + k * along / 2;
            Centreline.Element e = sk.line.elementAt(c0 + along / 4);
            double base = e.arc() ? e.turn / e.radius : 0;
            double kappa = (k == 0 ? hand : -hand) / radius + base;
            if (Math.abs(kappa) < FLAT) {
                kappa = 0; // the corridor's bend cancels the swing: a straight bit of gate line
            }
            parts.add(new Part(s, arc, kappa, BoatLine.PACKED, c0, c0 + along / 2, e.index, true));
            s += arc;
        }
        return s;
    }

    /** The line position standing for centreline position {@code c} (clamped to the race). */
    public double toLine(double c) {
        for (Part p : parts) {
            if (c < p.c1() + 1e-9) {
                if (c <= p.c0()) {
                    return p.s0();
                }
                return p.s0() + (c - p.c0()) / (p.c1() - p.c0()) * p.len();
            }
        }
        return length;
    }

    /** The part at line position {@code s}. */
    public Part partAt(double s) {
        int lo = 0;
        int hi = parts.size() - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (parts.get(mid).s0() <= s) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return parts.get(lo);
    }

    /** The line as {@link BoatLine} segments, one a part, each straight with its lips and fast ice. */
    public List<BoatLine.Seg> segs() {
        List<BoatLine.Seg> out = new ArrayList<>(parts.size());
        for (Part p : parts) {
            if (p.arc()) {
                out.add(new BoatLine.Seg(p.len(), p.radius(), BoatLine.PACKED, List.of(), List.of()));
                continue;
            }
            List<BoatLine.Lip> ls = new ArrayList<>();
            for (Lip l : lips) {
                if (l.s() >= p.s0() && l.s() < p.s1()) {
                    ls.add(new BoatLine.Lip(l.s() - p.s0(), l.drop()));
                }
            }
            List<BoatLine.Zone> zs = new ArrayList<>();
            for (double[] f : fast) {
                double a = Math.max(p.s0(), f[0]);
                double b = Math.min(p.s1(), f[1]);
                if (b > a) {
                    zs.add(new BoatLine.Zone(a - p.s0(), b - p.s0(), f[2]));
                }
            }
            out.add(new BoatLine.Seg(p.len(), 0, BoatLine.PACKED, ls, zs));
        }
        return out;
    }

    /** Ride it from a standstill at the start. */
    public BoatLine.Result ride() {
        return BoatLine.run(segs());
    }
}
