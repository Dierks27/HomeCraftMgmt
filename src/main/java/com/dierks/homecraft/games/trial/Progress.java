package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.gen.parkour.ParkourPlanner;

import java.util.ArrayList;
import java.util.List;

/**
 * One run's way round a course (spec §11, R2.15): which checkpoint is next, when each was reached,
 * and the finish time, from the moves the run reports. No Bukkit, no clock of its own — the run
 * passes {@code System.nanoTime()} in — so every rule here is tested.
 *
 * <p><b>In order, and nothing skipped.</b> Only the NEXT target is ever tested; the finish is the
 * last target, so it can't count before every checkpoint has. One move is tested against the next
 * target, and if it got there, against the one after that from the same point on, and so on: a
 * fast move clears every checkpoint it really passes through, in order, and never one it missed.
 *
 * <p><b>Times between ticks.</b> A target is reached at the fraction of the move where the move
 * first touched it, and the time is interpolated the same fraction of the way between the two
 * moves' times. So the finish time doesn't depend on where the tick happened to fall.
 *
 * <p><b>On Fresh Courses' parkour, up on the pad.</b> A target of a parkour course Fresh Courses made
 * (it has a {@code gen} tag) counts only with the feet over its pad: inside the same sphere, at or above its
 * mark's height, and in the pad's column, half the pad ({@link ParkourPlanner#CHECKPOINT_PAD} 3 for a
 * checkpoint, {@link ParkourPlanner#FINISH_PAD} 5 for the finish) and half a player's width ({@link
 * ParkourPlanner#STAND}) either side of the mark across (1.8, and 2.8 for the finish); or where the move ends,
 * standing on the pad within {@link #PAD_HAIR} (a Bedrock player's rounded stand on its very edge). Such a mark
 * sits at its pad's top middle (its layout's proof needs a solid block under every one, and the mark at the
 * middle), and its sphere reaches past the pad on every side. 0.37.1 cut only what was more than {@link
 * #FEET_BELOW} under the top, which left three ways to count a pad never landed on (0.37.3): a jump that fell
 * short past the pad's side above its top, one that hit the pad's side and slid down it, and on Easy, feet
 * standing on the edge of the pad before a checkpoint a step lower. The column closes all three, the pad before
 * included: pads are at least a block apart, and feet standing on one reach only {@link ParkourPlanner#STAND}
 * past it. Along a move the column has no hair at all, across or under the top: Java and Bedrock move a player
 * down before across, so a jump that falls past the pad's side and is stopped flat against it has its feet
 * {@link ParkourPlanner#STAND} past the edge only once they are under the top, but the straight line from the
 * tick before crosses the top's height a little way out from there, so any band past the edge would count it.
 * The time stays where the move first touched the sphere above {@link #FEET_BELOW} (so a landing's time is what
 * it was), and the next target is searched from where the move got over the pad (or from its end, for a stand
 * within the hair). A hand-built parkour course (a kept one too: it has no tag) keeps the whole
 * sphere, as it always did: its marks are wherever the builder stood, maybe in the air above the pad, and a
 * floor under such a mark could make it need a jump, or never count at all. Elytra rings, boat marks and a
 * Dropper's pools keep the whole sphere: rings are flown through from any side, and a pool's mark is under
 * its water.
 *
 * <p>A teleport of the run's own (back to a checkpoint) is {@link #jump}: the next move starts from
 * there, and nothing between the two places counts. A game whose target isn't a sphere (the Dropper's
 * pool box) marks it reached with {@link #reachNext}.
 */
public final class Progress {

    /**
     * How far under a Fresh Courses parkour mark the feet may be where its time is read (0.37.1), and the depth
     * {@link Footing} looks under a hand-built point for a block.
     */
    public static final double FEET_BELOW = 0.5;
    /**
     * How far off where Java puts the feet a stand on a Fresh Courses parkour pad may be and still count its mark
     * (0.37.3): past the pad's edge (beyond half a player's width) and either side of its top. A Bedrock player's
     * position is the client's floats, a float step or two off (a step is 0.0005 across where the parkour slots
     * stand, x and z 4096..8191, and 0.00002 up). Only where a move ends, standing at the top's height ({@link
     * Geometry#standsOn}), never along a move: a jump stopped flat against the pad's side crosses any band past
     * the edge above the top on its way there. Java's jump arcs from a block's top never end a tick this close
     * over or under another block's top, so a fall past the side never stands within it.
     */
    public static final double PAD_HAIR = 0.001;

    /**
     * A target reached by a move.
     *
     * @param index  its place in the course's targets (checkpoints first, the finish last)
     * @param finish whether it was the finish
     * @param nanos  when, interpolated along the move
     */
    public record Reached(int index, boolean finish, long nanos) {
    }

    private final List<Course.Mark> targets;
    private final int checkpoints;
    private final long[] reached;
    private final long startNanos;
    /** A parkour course Fresh Courses made: a target counts only with the feet over its pad ({@link #PAD_HAIR}). */
    private final boolean parkour;
    /** The half-width of each target's column (to {@link ParkourPlanner#STAND} past its pad), or NaN for the sphere. */
    private final double[] over;
    private int next;
    private Point last;
    private long lastNanos;

    /** A run on {@code course} that started at {@code origin} at {@code nanos}. */
    public Progress(Course course, Point origin, long nanos) {
        this.targets = course.targets();
        this.checkpoints = course.checkpoints().size();
        this.reached = new long[targets.size()];
        this.startNanos = nanos;
        this.parkour = course.kind() == TrialKind.PARKOUR && course.gen() != null; // hand-built: the whole sphere
        this.over = new double[targets.size()];
        for (int i = 0; i < over.length; i++) {
            over[i] = parkour ? (i < checkpoints ? ParkourPlanner.CHECKPOINT_PAD : ParkourPlanner.FINISH_PAD) / 2.0
                    + ParkourPlanner.STAND : Double.NaN;
        }
        this.last = origin;
        this.lastNanos = nanos;
    }

    /**
     * The run moved to {@code to} at {@code nanos}: whatever it reached on the way, in order (the
     * finish, if reached, is last). Nothing more is reached once finished.
     */
    public List<Reached> move(Point to, long nanos) {
        List<Reached> out = new ArrayList<>(1);
        double from = 0;
        long span = nanos - lastNanos;
        while (next < targets.size()) {
            Course.Mark target = targets.get(next);
            double t;
            double on; // where the move is over the pad: the next target is searched from there
            if (parkour) {
                t = Geometry.firstHitAbove(last, to, target.center(), target.radius(), from, target.y() - FEET_BELOW);
                on = Double.isNaN(t) ? Double.NaN : Geometry.firstHitOver(last, to, target.center(), target.radius(),
                        t, over[next], target.y());
                if (Double.isNaN(on) && !Double.isNaN(t)
                        && Geometry.standsOn(to, target.center(), target.radius(), over[next], PAD_HAIR)) {
                    on = 1; // where the move ended, a hair off the pad: a Bedrock stand on its very edge
                }
            } else {
                t = Geometry.firstHit(last, to, target.center(), target.radius(), from);
                on = t;
            }
            if (Double.isNaN(on)) {
                break;
            }
            long at = lastNanos + Math.round(t * span);
            reached[next] = at;
            out.add(new Reached(next, next == targets.size() - 1, at));
            next++;
            from = on;
        }
        last = to;
        lastNanos = nanos;
        return out;
    }

    /** The run was moved by the game itself: the next move starts at {@code to}. */
    public void jump(Point to, long nanos) {
        last = to;
        lastNanos = nanos;
    }

    /**
     * The game's own rule saw the NEXT target reached at {@code nanos}, where the sphere test can't
     * (a Dropper's pool box, whose corners a splash reaches outside the pool mark's sphere,
     * EVENTS-DROPPER-SPEC §B.1.7). Still in order, still one target: {@code null} once finished.
     * Where the run is ({@link #last}) doesn't change.
     */
    public Reached reachNext(long nanos) {
        if (next >= targets.size()) {
            return null;
        }
        reached[next] = nanos;
        Reached r = new Reached(next, next == targets.size() - 1, nanos);
        next++;
        return r;
    }

    /** When the last move (or jump) was. */
    public long lastNanos() {
        return lastNanos;
    }

    /** When the run started. */
    public long startNanos() {
        return startNanos;
    }

    /** How many checkpoints it has reached. */
    public int reachedCheckpoints() {
        return Math.min(next, checkpoints);
    }

    /** The last checkpoint reached, 0-based, or -1 for none yet (back to the start). */
    public int lastCheckpoint() {
        return reachedCheckpoints() - 1;
    }

    /** How many checkpoints the course has. */
    public int checkpoints() {
        return checkpoints;
    }

    /** Whether it has reached the finish. */
    public boolean finished() {
        return !targets.isEmpty() && next >= targets.size();
    }

    /** How many targets (checkpoints, then the finish) it has reached. */
    public int reachedTargets() {
        return next;
    }

    /** When each target was reached (0 for not yet), checkpoints first, the finish last. */
    public long[] times() {
        return reached.clone();
    }

    /** Where the last move ended. */
    public Point last() {
        return last;
    }
}
