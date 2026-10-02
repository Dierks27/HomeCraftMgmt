package com.dierks.homecraft.games.gen.boat;

import java.util.ArrayList;
import java.util.List;

/**
 * The Java boat's line through a Mountain Run (MOUNTAIN-V2-SPEC §3.1): a deterministic, tick by tick
 * model of a rider holding forward, which sets the run's model time (and so its length and reference
 * time) and feeds the flow score. A port of {@code S/v4/mtn/line.py} and {@code sim.py}, number for
 * number, so the spec's tables and samples pin it.
 *
 * <p><b>The physics</b> (vanilla {@code AbstractBoat}): on land {@code v <- f v + 0.04} a tick, f = 0.98 on
 * packed ice, 0.989 on blue, 0.6 on sand; in the air f = 0.9. Friction scales the whole velocity, so
 * the only turning force is the thrust: round a bend of radius R the boat holds at most the steady
 * cornering speed v_c(R), where {@code (v (1 - f))^2 + (v^2 / R)^2 = 0.04^2}. Off a drop of d blocks it
 * flies {@link BoatEnvelope#fallTicks t(d)} ticks under air drag, so a drop is a brake.
 *
 * <p><b>The run.</b> A list of {@link Seg segments} (straights with their lips, arcs), each started
 * afresh at position 0 as {@code sim.py} does (the overshoot past a segment's end is dropped: a hair
 * slow, and exactly what the spec's numbers were made with). On a straight: drive to each lip, fly,
 * drive on. On an arc: {@code v <- min(f v + 0.04, v_c)}; the speed it is entered at is kept, for the
 * flow score's brake and carry measures. Model only: it gates fun and sets {@code refMs}; nothing about
 * safety rests on it (that is {@link BoatEnvelope}'s). Pure, StrictMath-free arithmetic of plain doubles
 * in a fixed order, so every host gets the same ticks.
 */
public final class BoatLine {

    /** The thrust a tick, blocks a tick². */
    public static final double THRUST = 0.04;
    /** Friction a tick on packed ice, blue ice, in the air and on sand. */
    public static final double PACKED = 0.98;
    public static final double BLUE = 0.989;
    public static final double AIR = 0.9;
    public static final double SAND = 0.6;
    /** Ticks a second. */
    public static final int TICKS = 20;
    /** Bisection steps for v_c (line.py's 60). */
    private static final int BISECT = 60;

    private BoatLine() {
    }

    /**
     * The steady cornering speed v_c(R) on friction f, blocks a tick: the most v with
     * {@code (v (1 - f))^2 + (v^2 / R)^2 <= 0.04^2}, by 60 halvings of [0, 4] (line.py exactly).
     */
    public static double cornerSpeed(double radius, double f) {
        double lo = 0;
        double hi = 4;
        for (int i = 0; i < BISECT; i++) {
            double v = (lo + hi) / 2;
            double a = v * (1 - f);
            double b = v * v / radius;
            if (a * a + b * b <= THRUST * THRUST) {
                lo = v;
            } else {
                hi = v;
            }
        }
        return lo;
    }

    /** v_c(R) in blocks a second. */
    public static double cornerBps(double radius, double f) {
        return cornerSpeed(radius, f) * TICKS;
    }

    /** The top speed on friction f, blocks a second (40 on packed ice, 72.7 on blue). */
    public static double topBps(double f) {
        return THRUST / (1 - f) * TICKS;
    }

    /** A lip on a straight: {@code at} blocks into it, falling {@code drop} blocks. */
    public record Lip(double at, int drop) {
    }

    /**
     * A stretch of friction {@code f} on a straight, from {@code from} to {@code to} blocks into it (a
     * boost strip, a blue straight, a sand patch the line drives over).
     */
    public record Zone(double from, double to, double f) {
    }

    /**
     * One segment of the line: a straight ({@code radius} 0) or an arc of radius {@code radius},
     * {@code length} blocks long, on friction {@code f}, with its lips (straights only, in order) and its
     * friction zones (straights only).
     */
    public record Seg(double length, double radius, double f, List<Lip> lips, List<Zone> zones) {

        public Seg {
            lips = List.copyOf(lips == null ? List.of() : lips);
            zones = List.copyOf(zones == null ? List.of() : zones);
        }

        public boolean arc() {
            return radius > 0;
        }

        /** A packed straight. */
        public static Seg straight(double length) {
            return new Seg(length, 0, PACKED, List.of(), List.of());
        }

        /** A packed straight with lips. */
        public static Seg straight(double length, List<Lip> lips) {
            return new Seg(length, 0, PACKED, lips, List.of());
        }

        /** A packed arc of radius r turning {@code degrees}. */
        public static Seg arcDegrees(double r, double degrees) {
            return new Seg(r * Math.toRadians(degrees), r, PACKED, List.of(), List.of());
        }
    }

    /**
     * What a run of the line gives.
     *
     * @param ticks    ticks from the first segment's start to the last one's end
     * @param length   the segments' length, blocks
     * @param entry    each segment's entry speed, blocks a tick
     * @param exit     each segment's exit speed, blocks a tick
     * @param corner   each arc's v_c (0 for a straight), blocks a tick
     * @param landing  each lip's touchdown speed, blocks a tick, in segment and lip order
     * @param startTick each segment's first tick
     */
    public record Result(int ticks, double length, double[] entry, double[] exit, double[] corner, double[] landing,
                         int[] startTick) {

        /** The model time, seconds. */
        public double seconds() {
            return ticks / (double) TICKS;
        }

        /** The model's average speed, blocks a second. */
        public double average() {
            return ticks == 0 ? 0 : length / seconds();
        }

        /** Ticks from segment {@code from}'s start to segment {@code to}'s start. */
        public int ticksBetween(int from, int to) {
            int a = from >= startTick.length ? ticks : startTick[from];
            int b = to >= startTick.length ? ticks : startTick[to];
            return b - a;
        }
    }

    /** {@link #run(List, double)} from a standstill. */
    public static Result run(List<Seg> segs) {
        return run(segs, 0);
    }

    /**
     * Drive the line through {@code segs} from speed {@code v0} (blocks a tick), as {@code sim.py}'s
     * {@code run} does: each segment from position 0, lips flown for t(d) ticks of air drag.
     */
    public static Result run(List<Seg> segs, double v0) {
        int n = segs.size();
        double[] entry = new double[n];
        double[] exit = new double[n];
        double[] corner = new double[n];
        int[] startTick = new int[n];
        List<Double> landings = new ArrayList<>();
        double v = v0;
        int t = 0;
        double length = 0;
        for (int i = 0; i < n; i++) {
            Seg seg = segs.get(i);
            entry[i] = v;
            startTick[i] = t;
            double len = seg.length();
            length += len;
            double pos = 0;
            if (seg.arc()) {
                double vc = cornerSpeed(seg.radius(), seg.f());
                corner[i] = vc;
                while (pos < len) {
                    v = Math.min(v * seg.f() + THRUST, vc);
                    pos += v;
                    t++;
                }
            } else {
                for (Lip lip : seg.lips()) {
                    while (pos < lip.at()) {
                        v = v * friction(seg, pos) + THRUST;
                        pos += v;
                        t++;
                    }
                    int air = BoatEnvelope.fallTicks(lip.drop());
                    for (int k = 0; k < air; k++) {
                        v = v * AIR + THRUST;
                        pos += v;
                        t++;
                    }
                    landings.add(v);
                }
                while (pos < len) {
                    v = v * friction(seg, pos) + THRUST;
                    pos += v;
                    t++;
                }
            }
            exit[i] = v;
        }
        double[] landing = new double[landings.size()];
        for (int i = 0; i < landing.length; i++) {
            landing[i] = landings.get(i);
        }
        return new Result(t, length, entry, exit, corner, landing, startTick);
    }

    /** The friction at {@code pos} blocks into a straight: its zone's there, else its own. */
    private static double friction(Seg seg, double pos) {
        for (Zone z : seg.zones()) {
            if (pos >= z.from() && pos < z.to()) {
                return z.f();
            }
        }
        return seg.f();
    }
}
