package com.dierks.homecraft.games.trial;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Recorded runs for the NPC racers' ghosts (stage 2), pure and tested: where a real player went on a counted solo
 * run, a point every {@value #EVERY} ticks, so an NPC can race the course the way it is really raced
 * ({@link GhostPath.Source#RECORDED}).
 *
 * <p><b>What is kept.</b> Per course, only the fastest clean run since the server started, on the course's layout
 * as it is now ({@link #best}): a run with a jump in it (a reset to a checkpoint, any teleport) is never kept, nor
 * one longer than {@value #MAX_SAMPLES} points. Memory only: nothing is stored, and a restart starts empty (the NPCs
 * race the checkpoint line until someone has run the course again). Nothing about a run changes: Time Trials only
 * hands its position over each tick, and the player's own record is what it always was.
 *
 * <p>Main thread only.
 */
public final class RunTrails {

    /** A point every this many ticks. */
    static final int EVERY = 2;
    /** The most points a run keeps (a ten-minute run). */
    static final int MAX_SAMPLES = 10 * 60 * 20 / EVERY;
    /** Two points further apart than this (blocks) are a jump: a reset or a teleport, never a ride. */
    static final double MAX_STEP = 9.0;

    /**
     * A recorded run.
     *
     * @param course   the course id
     * @param layout   its layout ({@link Course#layoutHash})
     * @param ms       its time
     * @param samples  its points, from Go
     * @param targetMs when it reached each target (ms from Go)
     */
    public record Trail(String course, int layout, long ms, List<GhostPath.Sample> samples, long[] targetMs) {

        public Trail {
            samples = List.copyOf(samples);
            targetMs = targetMs.clone();
        }

        @Override
        public long[] targetMs() {
            return targetMs.clone();
        }
    }

    private static final class Recording {
        final String course;
        final int layout;
        final long startNanos;
        final List<GhostPath.Sample> samples = new ArrayList<>();
        boolean broken;
        int ticks;

        Recording(String course, int layout, long startNanos) {
            this.course = course;
            this.layout = layout;
            this.startNanos = startNanos;
        }
    }

    private final Map<UUID, Recording> live = new HashMap<>();
    private final Map<String, Trail> best = new HashMap<>();

    /**
     * A tick of {@code player}'s counted solo run on {@code course} (layout {@code layout}), started at
     * {@code startNanos} (its Go): they are at (x, y, z) facing (yaw, pitch), {@code ms} after Go.
     */
    public void sample(UUID player, String course, int layout, long startNanos, long ms, double x, double y, double z,
                       float yaw, float pitch) {
        Recording r = live.get(player);
        if (r == null || r.startNanos != startNanos || !r.course.equals(course) || r.layout != layout) {
            r = new Recording(course, layout, startNanos);
            live.put(player, r);
        }
        if (r.broken || r.ticks++ % EVERY != 0) {
            return;
        }
        if (r.samples.size() >= MAX_SAMPLES) {
            r.broken = true;
            return;
        }
        if (!r.samples.isEmpty()) {
            GhostPath.Sample last = r.samples.get(r.samples.size() - 1);
            double dx = x - last.x();
            double dy = y - last.y();
            double dz = z - last.z();
            if (Math.sqrt(dx * dx + dy * dy + dz * dz) > MAX_STEP || ms < last.ms()) {
                r.broken = true;
                return;
            }
        }
        r.samples.add(new GhostPath.Sample(Math.max(0, ms), x, y, z, yaw, pitch));
    }

    /** {@code player}'s run was put back (a checkpoint reset, the start again): it won't be kept. */
    public void broken(UUID player) {
        Recording r = live.get(player);
        if (r != null) {
            r.broken = true;
        }
    }

    /** {@code player}'s run ended without a counted finish (left, voided, a race): forgotten. */
    public void drop(UUID player) {
        live.remove(player);
    }

    /**
     * {@code player}'s run crossed the line and counted, in {@code ms}, reaching each target at {@code targetMs}:
     * kept if it is clean and the fastest on its course and layout since the restart.
     *
     * @param x the finish point (the last tick may be before it)
     * @return whether it was kept
     */
    public boolean finished(UUID player, String course, int layout, long ms, long[] targetMs, double x, double y,
                            double z) {
        Recording r = live.remove(player);
        if (r == null || r.broken || !r.course.equals(course) || r.layout != layout || r.samples.size() < 2
                || targetMs == null || targetMs.length == 0) {
            return false;
        }
        Trail was = best.get(course);
        if (was != null && was.layout() == layout && was.ms() <= ms) {
            return false;
        }
        List<GhostPath.Sample> s = new ArrayList<>(r.samples);
        GhostPath.Sample last = s.get(s.size() - 1);
        if (ms > last.ms()) {
            s.add(new GhostPath.Sample(ms, x, y, z, last.yaw(), last.pitch()));
        }
        best.put(course, new Trail(course, layout, ms, s, targetMs));
        return true;
    }

    /** The fastest clean run on {@code course} as laid out now ({@code layout}), or {@code null}. */
    public Trail best(String course, int layout) {
        Trail t = course == null ? null : best.get(course);
        return t != null && t.layout() == layout ? t : null;
    }

    /** How many courses have a recorded run (for the admins' status). */
    public int size() {
        return best.size();
    }

    /** Everything forgotten (Time Trials stopping). */
    public void clear() {
        live.clear();
        best.clear();
    }
}
