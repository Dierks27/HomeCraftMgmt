package com.dierks.homecraft.games.gen.dropper;

import java.util.ArrayList;
import java.util.List;

/**
 * A sloppy, late, walk-only player (EVENTS-DROPPER-SPEC §B.1.6 rule 2): the robustness half of the
 * proof. Where the witness shows one way down exists, the pilots show a child who doesn't fly it
 * exactly still gets down.
 *
 * <p><b>How it steers.</b> It looks at the centre of the next opening and holds the key that brings
 * it to rest over it: it works out where it would drift to if it let go now ({@code pos + v / (1 -
 * 0.91)}) and walks toward the opening from there, or lets go when that is already within a
 * {@value #DEADBAND}-block of it. Once its feet are through a layer it looks at the next opening,
 * and after the last at the pool's centre. Walking acceleration only, never a sprint.
 *
 * <p><b>How it is late and sloppy.</b> The first tick is the walk-off itself (the last tick on the
 * ledge), so it walks forward; for its reaction delay after that it keeps walking forward, and after
 * passing each layer it keeps its old input that long before it turns to the next opening. A sloppy
 * pilot also holds every key a constant few degrees off.
 *
 * <p><b>The 33</b> ({@link #variants}): 5 exit points along the ledge's front edge, walking off or
 * jumping off, with each of the tier's 3 reaction delays (30), plus 3 sloppy runs.
 */
public final class DropPilot implements DropRun.Controller {

    /** Let go when the drift would stop this close to the target. */
    public static final double DEADBAND = 0.1;
    /** Where the pilots leave the ledge, across its 3-wide front edge (the hitbox stays on it). */
    public static final double[] EXITS = {-1.2, -0.6, 0, 0.6, 1.2};
    /** How far a body drifts per unit of speed once it lets go. */
    static final double COAST = 1 / (1 - DropSim.AIR_DRAG);

    /**
     * Where a pilot aims: the centre (x, z) of an opening, until its feet are below {@code belowY}
     * (the plate's underside); the pool's centre with {@code belowY} NaN.
     */
    public record Target(double x, double z, double belowY) {
    }

    /**
     * One of the 33 pilots: its label (for admin lines and test messages), where it starts and how it
     * steers.
     */
    public record Variant(String label, DropSim.Body start, DropPilot pilot) {
    }

    private final double fx;
    private final double fz;
    private final List<Target> targets;
    private final int delay;
    private final double aimDegrees;
    private final double cos;
    private final double sin;

    private int current;
    private int turnedAt;
    private double[] held;

    /**
     * A pilot that walked off heading (fx, fz), aims at {@code targets} in turn, reacts
     * {@code delay} ticks late and holds every key {@code aimDegrees} off.
     */
    public DropPilot(double fx, double fz, List<Target> targets, int delay, double aimDegrees) {
        this.fx = fx;
        this.fz = fz;
        this.targets = List.copyOf(targets);
        this.delay = Math.max(0, delay);
        this.aimDegrees = aimDegrees;
        double a = StrictMath.toRadians(aimDegrees);
        this.cos = StrictMath.cos(a);
        this.sin = StrictMath.sin(a);
        this.current = 0;
        this.turnedAt = 0;
        this.held = new double[]{fx, fz};
    }

    @Override
    public double[] input(int tick, DropSim.Body body) {
        while (current < targets.size() - 1 && body.y() < targets.get(current).belowY()) {
            current++;
            turnedAt = tick;
        }
        // tick 0 is the walk-off itself: still walking forward, still on the ledge; then the delay
        if (tick == 0 || (turnedAt == 0 ? tick <= delay : tick < turnedAt + delay)) {
            return held;
        }
        Target t = targets.get(current);
        double px = body.x() + body.vx() * COAST;
        double pz = body.z() + body.vz() * COAST;
        double ex = t.x() - px;
        double ez = t.z() - pz;
        double len = Math.sqrt(ex * ex + ez * ez);
        if (len <= DEADBAND) {
            held = new double[]{0, 0};
        } else {
            double ux = ex / len;
            double uz = ez / len;
            held = new double[]{ux * cos - uz * sin, ux * sin + uz * cos};
        }
        return held;
    }

    /** The same pilot, back at the top: a pilot remembers what it was doing, so each fall needs a fresh one. */
    public DropPilot fresh() {
        return new DropPilot(fx, fz, targets, delay, aimDegrees);
    }

    /**
     * The 33 pilots for a level: its ledge's front edge (the point on its centre line, and the
     * direction the shaft is, a unit vector along an axis), the ledge top, the targets and the tier.
     */
    public static List<Variant> variants(double edgeX, double edgeZ, double fx, double fz, double ledgeTop,
                                         List<Target> targets, DropRules.Level tier) {
        List<Variant> out = new ArrayList<>();
        int[] delays = tier.delays();
        // the lateral axis: forward turned a quarter
        double lx = -fz;
        double lz = fx;
        for (int d : delays) {
            for (double exit : EXITS) {
                for (boolean jump : new boolean[]{false, true}) {
                    DropSim.Body start = start(edgeX, edgeZ, fx, fz, lx, lz, exit, ledgeTop, jump);
                    out.add(new Variant((jump ? "jump" : "walk") + " off at " + exit + ", " + d + " ticks late", start,
                            new DropPilot(fx, fz, targets, d, 0)));
                }
            }
        }
        double err = tier.aimError();
        int mid = delays[delays.length / 2];
        double[][] sloppy = {{-0.6, err}, {0, -err}, {0.6, err}};
        for (double[] s : sloppy) {
            DropSim.Body start = start(edgeX, edgeZ, fx, fz, lx, lz, s[0], ledgeTop, false);
            out.add(new Variant("sloppy " + (s[1] > 0 ? "+" : "") + s[1] + " degrees off at " + s[0] + ", " + mid
                    + " ticks late", start, new DropPilot(fx, fz, targets, mid, s[1])));
        }
        return out;
    }

    /**
     * Where a fall off the ledge starts: on the last tick on it, placed so that one tick at walking
     * speed carries the hitbox just past the front edge.
     */
    public static DropSim.Body start(double edgeX, double edgeZ, double fx, double fz, double lx, double lz,
                                     double exit, double ledgeTop, boolean jump) {
        double back = DropSim.HALF_WIDTH + 1e-3 - DropSim.WALK_OFF_SPEED;
        double x = edgeX + fx * back + lx * exit;
        double z = edgeZ + fz * back + lz * exit;
        return jump ? DropSim.jumpOff(x, ledgeTop, z, fx, fz) : DropSim.walkOff(x, ledgeTop, z, fx, fz);
    }
}
