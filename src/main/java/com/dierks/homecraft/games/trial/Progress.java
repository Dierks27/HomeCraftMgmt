package com.dierks.homecraft.games.trial;

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
 * <p>A teleport of the run's own (back to a checkpoint) is {@link #jump}: the next move starts from
 * there, and nothing between the two places counts.
 */
public final class Progress {

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
    private int next;
    private Point last;
    private long lastNanos;

    /** A run on {@code course} that started at {@code origin} at {@code nanos}. */
    public Progress(Course course, Point origin, long nanos) {
        this.targets = course.targets();
        this.checkpoints = course.checkpoints().size();
        this.reached = new long[targets.size()];
        this.startNanos = nanos;
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
            double t = Geometry.firstHit(last, to, target.center(), target.radius(), from);
            if (Double.isNaN(t)) {
                break;
            }
            long at = lastNanos + Math.round(t * span);
            reached[next] = at;
            out.add(new Reached(next, next == targets.size() - 1, at));
            next++;
            from = t;
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
