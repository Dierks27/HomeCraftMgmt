package com.dierks.homecraft.games.gen.dropper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
 * <p><b>How it is late and sloppy.</b> The first tick is the walk-off itself (the last tick that
 * begins over the ledge), so it walks forward; for its reaction delay after that it keeps walking
 * forward, and after passing each layer it keeps its old input that long before it turns to the
 * next opening. A sloppy pilot also holds every key a constant few degrees off.
 *
 * <p><b>Where and when it leaves.</b> A child steps off anywhere along the ledge's front edge, and at
 * any moment of a walking step: when the walk-off tick begins, the back of the hitbox can be
 * anywhere from a hair to a whole step ({@link DropSim#GROUND_WALK}, 0.216) behind the edge, and
 * where it is shifts the whole fall. A proof flown from one exact moment can pass a level that bonks
 * a child who steps off two hundredths of a block later, so the pilots are flown from
 * {@link #EXITS} every 0.3 blocks along the edge and {@link #TIMINGS} across the whole step.
 *
 * <p><b>The pilots</b> ({@link #variants}): every exit and every timing, walking off or jumping off,
 * with each of the tier's 3 reaction delays, plus the sloppy runs (3 exits, each timing).
 */
public final class DropPilot implements DropRun.Controller {

    /**
     * Let go when the drift would stop this close to the target. One tick of pushing moves where the
     * drift stops by a whole {@link DropSim#TOP_WALK} (0.218), so a band narrower than half of that
     * can be jumped clean across: the pilot then swings from one side to the other every tick, for
     * ever, and which key it holds as it passes a layer (and keeps through its reaction delay) is a
     * coin toss that two starts a hundredth of a block apart call differently. Wider than half a
     * push, it settles: it pushes until the drift ends inside the band, then lets go.
     */
    public static final double DEADBAND = 0.12;
    /** Where the pilots leave the ledge, every 0.3 across its 3-wide front edge (the hitbox stays on it). */
    public static final double[] EXITS = {-1.2, -0.9, -0.6, -0.3, 0, 0.3, 0.6, 0.9, 1.2};
    /** Where the sloppy pilots leave it. */
    public static final double[] SLOPPY_EXITS = {-0.6, 0, 0.6};
    /**
     * When in its step a pilot walks off: as the walk-off tick begins, the back of its hitbox is this
     * share of a ground step ({@link DropSim#GROUND_WALK}) behind the front edge. A real walk-off is
     * anywhere in (0, 1]; these are the middles of three equal parts of that window, 0.072 blocks
     * apart, so a real one is never more than 0.036 blocks from one of them.
     */
    public static final double[] TIMINGS = {1.0 / 6, 0.5, 5.0 / 6};
    /** The witness's timing: the middle of the step. */
    public static final double WITNESS_TIMING = 0.5;
    /** How far a body drifts per unit of speed once it lets go. */
    static final double COAST = 1 / (1 - DropSim.AIR_DRAG);

    /**
     * Where a pilot aims: the centre (x, z) of an opening, until its feet are below {@code belowY}
     * (the plate's underside); the pool's centre with {@code belowY} NaN.
     */
    public record Target(double x, double z, double belowY) {
    }

    /**
     * One of a level's pilots: its label (for admin lines and test messages), where it starts and how
     * it steers.
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
     * The pilots for a level: its ledge's front edge (the point on its centre line, and the direction
     * the shaft is, a unit vector along an axis), the ledge top, the targets and the tier. Every exit
     * and timing, walking off or jumping off, with each reaction delay; then the sloppy runs, the
     * middle delay and each timing, the hardest last.
     */
    public static List<Variant> variants(double edgeX, double edgeZ, double fx, double fz, double ledgeTop,
                                         List<Target> targets, DropRules.Level tier) {
        List<Variant> out = new ArrayList<>();
        int[] delays = tier.delays();
        // the lateral axis: forward turned a quarter
        double lx = -fz;
        double lz = fx;
        for (int d : delays) {
            for (double timing : TIMINGS) {
                for (double exit : EXITS) {
                    for (boolean jump : new boolean[]{false, true}) {
                        DropSim.Body start = start(edgeX, edgeZ, fx, fz, lx, lz, exit, timing, ledgeTop, jump);
                        out.add(new Variant((jump ? "jump" : "walk") + " off at " + exit + ", " + step(timing) + ", "
                                + d + " ticks late", start, new DropPilot(fx, fz, targets, d, 0)));
                    }
                }
            }
        }
        double err = tier.aimError();
        int mid = delays[delays.length / 2];
        for (double timing : TIMINGS) {
            for (int k = 0; k < SLOPPY_EXITS.length; k++) {
                double exit = SLOPPY_EXITS[k];
                double aim = k % 2 == 0 ? err : -err;
                DropSim.Body start = start(edgeX, edgeZ, fx, fz, lx, lz, exit, timing, ledgeTop, false);
                out.add(new Variant("sloppy " + (aim > 0 ? "+" : "") + aim + " degrees off at " + exit + ", "
                        + step(timing) + ", " + mid + " ticks late", start, new DropPilot(fx, fz, targets, mid, aim)));
            }
        }
        return out;
    }

    /** A timing for a label: "heels 0.17 of a step back". */
    static String step(double timing) {
        return String.format(Locale.ROOT, "heels %.2f of a step back", timing);
    }

    /**
     * Where a fall off the ledge starts: at the start of the last tick that begins over it, at
     * {@code exit} along the front edge, with the back of the hitbox {@code timing} of a ground step
     * behind the edge, so that one step of walking (the pilot's first tick) carries it
     * {@code 1 - timing} of a step past.
     */
    public static DropSim.Body start(double edgeX, double edgeZ, double fx, double fz, double lx, double lz,
                                     double exit, double timing, double ledgeTop, boolean jump) {
        // the feet's centre is half a hitbox ahead of its back
        double ahead = DropSim.HALF_WIDTH - timing * DropSim.GROUND_WALK;
        double x = edgeX + fx * ahead + lx * exit;
        double z = edgeZ + fz * ahead + lz * exit;
        return jump ? DropSim.jumpOff(x, ledgeTop, z, fx, fz) : DropSim.walkOff(x, ledgeTop, z, fx, fz);
    }
}
