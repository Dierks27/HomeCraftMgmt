package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.cpu.CpuLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * The way an NPC racer goes round a course (stage 2: NPCs "racing parkour or Rings"), pure and tested: a path with a
 * reference time at every point, and ({@link Run}) one NPC's go along it at its level.
 *
 * <p><b>Where the path comes from</b>, the best there is, in this order ({@link Source}):
 * <ol>
 *   <li>{@link Source#RECORDED}: a real player's counted solo run on this course and layout, as it was ridden,
 *       flown or run ({@link RunTrails}: the fastest since the server started, kept in memory only). A ghost of it
 *       never goes anywhere a player didn't.</li>
 *   <li>{@link Source#SPLITS}: the course's own checkpoints in order, start to finish, timed by a real run's
 *       checkpoint times (the course record's splits on this layout, from {@code game_splits}): straight from
 *       checkpoint to checkpoint at that run's pace.</li>
 *   <li>{@link Source#CHECKPOINTS}: the same line, at a set pace for the kind of course ({@link #pace}), never
 *       quicker than the course's shortest believable time.</li>
 * </ol>
 * A racer who starts from a grid spot first goes straight from it to the course's start (a grid is laid out behind
 * the start on the track itself).
 *
 * <p>Every point an NPC is ever put on lies on this path: between two of its points, on the straight line joining
 * them ({@link #at}). Nothing here reads a block.
 */
public final class GhostPath {

    /** Where a path came from, best first. */
    public enum Source {
        RECORDED("a recorded run"),
        SPLITS("the checkpoints, at a real run's pace"),
        CHECKPOINTS("the checkpoints");

        private final String words;

        Source(String words) {
            this.words = words;
        }

        /** For the admins' status. */
        public String words() {
            return words;
        }
    }

    /** One point of a path: where, which way, and the reference time (ms from Go). */
    public record Sample(long ms, double x, double y, double z, float yaw, float pitch) {
    }

    /**
     * Where an NPC is at one moment.
     *
     * @param reached targets reached so far (as a racer's progress counts them)
     * @param toNext  how far to the next target (0 once finished)
     * @param slip    it is slipping now (a stop, or back to its last checkpoint)
     * @param done    it has crossed the line
     */
    public record Pose(double x, double y, double z, float yaw, float pitch, int reached, double toNext, boolean slip,
                       boolean done) {
    }

    /** Blocks a second along the checkpoint line, by kind, for a good run (the line is shorter than any real way). */
    static final double PARKOUR_PACE = 3.5;
    static final double ELYTRA_PACE = 18;
    static final double BOAT_PACE = 16;
    /** A grid spot this close to the course's start is the start. */
    static final double SAME_SPOT = 1.5;

    private final Source source;
    private final List<Sample> samples;
    private final long[] targetMs;
    private final List<Course.Mark> targets;

    private GhostPath(Source source, List<Sample> samples, long[] targetMs, List<Course.Mark> targets) {
        this.source = source;
        this.samples = List.copyOf(samples);
        this.targetMs = targetMs.clone();
        this.targets = List.copyOf(targets);
    }

    /** The pace along the checkpoint line for a course of {@code kind} (blocks a second). */
    static double pace(TrialKind kind) {
        return switch (kind == null ? TrialKind.PARKOUR : kind) {
            case ELYTRA -> ELYTRA_PACE;
            case BOAT -> BOAT_PACE;
            default -> PARKOUR_PACE;
        };
    }

    /**
     * The best path round {@code raced} (the course as raced: its targets, laps and all) from {@code from} (a grid
     * spot, or the course's start).
     *
     * @param recorded  a recorded run on the base course and layout, or {@code null}
     * @param splitsMs  a real run's checkpoint times from Go (one per target, the finish last), or {@code null}
     * @param minMs     the course's shortest believable time (ms): a checkpoint pace is never quicker
     * @return the path, or {@code null} when the course has no start or finish
     */
    public static GhostPath of(Course raced, Course.Spot from, RunTrails.Trail recorded, long[] splitsMs, long minMs) {
        if (raced == null || raced.start() == null || raced.finish() == null) {
            return null;
        }
        Course.Spot start = from == null ? raced.start() : from;
        List<Course.Mark> targets = raced.targets();
        double pace = pace(raced.kind());
        if (recorded != null && fits(recorded, targets)) {
            return fromTrail(recorded, start, targets, pace);
        }
        if (splitsMs != null && splitsFit(splitsMs, targets.size())) {
            return fromSplits(raced, start, targets, splitsMs, pace);
        }
        return fromPace(raced, start, targets, pace, minMs);
    }

    /** Whether a recorded run went through these targets (the same marks, in order). */
    static boolean fits(RunTrails.Trail t, List<Course.Mark> targets) {
        if (t.samples().size() < 2 || t.targetMs().length != targets.size()) {
            return false;
        }
        long last = -1;
        for (long ms : t.targetMs()) {
            if (ms <= last) {
                return false;
            }
            last = ms;
        }
        return true;
    }

    private static boolean splitsFit(long[] splits, int targets) {
        if (splits.length != targets || targets == 0) {
            return false;
        }
        long last = 0;
        for (long ms : splits) {
            if (ms <= last) {
                return false;
            }
            last = ms;
        }
        return true;
    }

    private static GhostPath fromTrail(RunTrails.Trail t, Course.Spot from, List<Course.Mark> targets, double pace) {
        List<Sample> out = new ArrayList<>();
        Sample first = t.samples().get(0);
        double lead = dist(from.x(), from.y(), from.z(), first.x(), first.y(), first.z());
        long shift = lead <= SAME_SPOT ? 0 : Math.round(lead / pace * 1000);
        if (shift > 0) {
            out.add(new Sample(0, from.x(), from.y(), from.z(), from.yaw(), 0f));
        }
        for (Sample s : t.samples()) {
            out.add(new Sample(s.ms() + shift, s.x(), s.y(), s.z(), s.yaw(), s.pitch()));
        }
        long[] at = new long[t.targetMs().length];
        for (int i = 0; i < at.length; i++) {
            at[i] = t.targetMs()[i] + shift;
        }
        // the line is crossed at the last sample at the latest
        at[at.length - 1] = Math.min(at[at.length - 1], out.get(out.size() - 1).ms());
        for (int i = at.length - 2; i >= 0; i--) {
            at[i] = Math.min(at[i], at[i + 1] - 1);
        }
        return new GhostPath(Source.RECORDED, out, at, targets);
    }

    private static GhostPath fromSplits(Course raced, Course.Spot from, List<Course.Mark> targets, long[] splits,
                                        double pace) {
        Course.Spot start = raced.start();
        double lead = dist(from.x(), from.y(), from.z(), start.x(), start.y(), start.z());
        long shift = lead <= SAME_SPOT ? 0 : Math.round(lead / pace * 1000);
        List<Sample> out = new ArrayList<>();
        out.add(new Sample(0, from.x(), from.y(), from.z(), from.yaw(), 0f));
        if (shift > 0) {
            out.add(new Sample(shift, start.x(), start.y(), start.z(), start.yaw(), 0f));
        }
        long[] at = new long[targets.size()];
        for (int i = 0; i < targets.size(); i++) {
            at[i] = splits[i] + shift;
            Course.Mark m = targets.get(i);
            out.add(new Sample(at[i], m.x(), m.y(), m.z(), 0f, 0f));
        }
        return new GhostPath(Source.SPLITS, faced(out), at, targets);
    }

    private static GhostPath fromPace(Course raced, Course.Spot from, List<Course.Mark> targets, double pace,
                                      long minMs) {
        List<Sample> out = new ArrayList<>();
        out.add(new Sample(0, from.x(), from.y(), from.z(), from.yaw(), 0f));
        Course.Spot start = raced.start();
        double lead = dist(from.x(), from.y(), from.z(), start.x(), start.y(), start.z());
        double ms = 0;
        double px = from.x();
        double py = from.y();
        double pz = from.z();
        if (lead > SAME_SPOT) {
            ms += lead / pace * 1000;
            out.add(new Sample(Math.round(ms), start.x(), start.y(), start.z(), start.yaw(), 0f));
            px = start.x();
            py = start.y();
            pz = start.z();
        }
        long leadMs = Math.round(ms);
        double course = 0;
        double[] legs = new double[targets.size()];
        for (int i = 0; i < targets.size(); i++) {
            Course.Mark m = targets.get(i);
            legs[i] = dist(px, py, pz, m.x(), m.y(), m.z());
            course += legs[i];
            px = m.x();
            py = m.y();
            pz = m.z();
        }
        double courseMs = Math.max(course / pace * 1000, Math.max(0, minMs) * 1.1);
        double scale = course <= 0 ? 0 : courseMs / course;
        long[] at = new long[targets.size()];
        double t = leadMs;
        for (int i = 0; i < targets.size(); i++) {
            t += course <= 0 ? courseMs / targets.size() : legs[i] * scale;
            at[i] = Math.max(i == 0 ? leadMs + 1 : at[i - 1] + 1, Math.round(t));
            Course.Mark m = targets.get(i);
            out.add(new Sample(at[i], m.x(), m.y(), m.z(), 0f, 0f));
        }
        return new GhostPath(Source.CHECKPOINTS, faced(out), at, targets);
    }

    /** The line's points, each facing the next one (the last facing as the one before it). */
    private static List<Sample> faced(List<Sample> in) {
        List<Sample> out = new ArrayList<>(in.size());
        float yaw = in.isEmpty() ? 0f : in.get(0).yaw();
        for (int i = 0; i < in.size(); i++) {
            Sample s = in.get(i);
            if (i + 1 < in.size()) {
                Sample n = in.get(i + 1);
                if (Math.abs(n.x() - s.x()) + Math.abs(n.z() - s.z()) > 1e-6) {
                    yaw = Geometry.yawToward(new Point(s.x(), s.y(), s.z()), new Point(n.x(), n.y(), n.z()));
                }
            }
            out.add(new Sample(s.ms(), s.x(), s.y(), s.z(), yaw, 0f));
        }
        return out;
    }

    private static double dist(double ax, double ay, double az, double bx, double by, double bz) {
        double dx = bx - ax;
        double dy = by - ay;
        double dz = bz - az;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public Source source() {
        return source;
    }

    /** The path's points, in order. */
    public List<Sample> samples() {
        return samples;
    }

    /** The reference time each target is reached at (ms from Go). */
    public long[] targetMs() {
        return targetMs.clone();
    }

    /** The reference time of the finish (ms from Go). */
    public long totalMs() {
        return targetMs.length == 0 ? 0 : targetMs[targetMs.length - 1];
    }

    /** The point at reference time {@code ms} (clamped to the path): on the line between the two points around it. */
    public Sample at(long ms) {
        if (samples.size() == 1 || ms <= samples.get(0).ms()) {
            return samples.get(0);
        }
        Sample last = samples.get(samples.size() - 1);
        if (ms >= last.ms()) {
            return last;
        }
        int lo = 0;
        int hi = samples.size() - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (samples.get(mid).ms() <= ms) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        Sample a = samples.get(lo);
        Sample b = samples.get(hi);
        double f = b.ms() == a.ms() ? 1 : (double) (ms - a.ms()) / (b.ms() - a.ms());
        return new Sample(ms, a.x() + (b.x() - a.x()) * f, a.y() + (b.y() - a.y()) * f, a.z() + (b.z() - a.z()) * f,
                source == Source.RECORDED ? lerpYaw(a.yaw(), b.yaw(), f) : a.yaw(),
                (float) (a.pitch() + (b.pitch() - a.pitch()) * f));
    }

    private static float lerpYaw(float a, float b, double f) {
        double d = ((b - a) % 360 + 540) % 360 - 180;
        return (float) (a + d * f);
    }

    /** How many targets are reached by reference time {@code ms}. */
    int reachedBy(long ms) {
        int n = 0;
        while (n < targetMs.length && targetMs[n] <= ms) {
            n++;
        }
        return n;
    }

    /** How far {@code s} is from target {@code index} (0 past the last). */
    double toTarget(Sample s, int index) {
        if (index >= targets.size()) {
            return 0;
        }
        Course.Mark m = targets.get(index);
        return dist(s.x(), s.y(), s.z(), m.x(), m.y(), m.z());
    }

    /**
     * One NPC's go along a path at its level, decided once at Go (pure: its own generator): the path's reference
     * time is stretched by the level (hard close to it, easy well behind), each leg between targets a little
     * differently, and now and then a slip, more often the easier it plays: a short stop, or (where a racer can fall
     * or crash) back to the checkpoint it last reached, to go that leg again. It never moves anywhere but along
     * the path.
     */
    public static final class Run {

        /** How far behind the reference an NPC plays, by level: [least, most]. */
        static final double[] HARD = {1.03, 1.08};
        static final double[] MEDIUM = {1.15, 1.30};
        static final double[] EASY = {1.45, 1.75};
        /** The chance of a slip on each leg between targets, by level. */
        static final double SLIP_EASY = 0.22;
        static final double SLIP_MEDIUM = 0.07;
        static final double SLIP_HARD = 0.02;
        /** A slip is back to the checkpoint this share of the time (the rest are stops). */
        static final double BACK_SHARE = 0.4;

        /**
         * A stretch of the NPC's time mapped onto the path's: from {@code npcFrom} to {@code npcTo} it goes from
         * {@code refFrom} to {@code refTo} (the same for a stop).
         */
        public record Leg(long npcFrom, long npcTo, long refFrom, long refTo, boolean slip) {
        }

        private final GhostPath path;
        private final CpuLevel level;
        private final List<Leg> legs = new ArrayList<>();
        /** The furthest reference time reached before each leg starts. */
        private final List<Long> bestBefore = new ArrayList<>();

        /**
         * @param canFall whether a racer can be sent back to a checkpoint on this course (a slip may be one)
         */
        public Run(GhostPath path, CpuLevel level, boolean canFall, RandomGenerator rng) {
            this.path = path;
            this.level = level == null ? CpuLevel.MEDIUM : level;
            double[] band = switch (this.level) {
                case HARD -> HARD;
                case MEDIUM -> MEDIUM;
                case EASY -> EASY;
            };
            double slipChance = switch (this.level) {
                case HARD -> SLIP_HARD;
                case MEDIUM -> SLIP_MEDIUM;
                case EASY -> SLIP_EASY;
            };
            double base = band[0] + rng.nextDouble() * (band[1] - band[0]);
            long[] at = path.targetMs;
            long npc = 0;
            long from = 0;
            long best = 0;
            for (long to : at) {
                double f = base * (0.96 + rng.nextDouble() * 0.08);
                long span = Math.max(1, to - from);
                if (rng.nextDouble() < slipChance) {
                    long cut = from + Math.max(1, Math.round(span * (0.2 + rng.nextDouble() * 0.6)));
                    npc = leg(npc, from, cut, f, false, best);
                    best = Math.max(best, cut);
                    if (canFall && rng.nextDouble() < BACK_SHARE) {
                        // back to the checkpoint: a short wait there, then the whole leg again
                        npc = stop(npc, from, 600 + rng.nextInt(600), best);
                        npc = leg(npc, from, to, f, true, best);
                    } else {
                        long stopFor = switch (this.level) {
                            case EASY -> 1_000 + rng.nextInt(2_000);
                            case MEDIUM -> 700 + rng.nextInt(800);
                            case HARD -> 300 + rng.nextInt(500);
                        };
                        npc = stop(npc, cut, stopFor, best);
                        npc = leg(npc, cut, to, f, false, best);
                    }
                } else {
                    npc = leg(npc, from, to, f, false, best);
                }
                best = Math.max(best, to);
                from = to;
            }
        }

        private long leg(long npc, long refFrom, long refTo, double f, boolean slip, long best) {
            long dur = Math.max(1, Math.round((refTo - refFrom) * f));
            legs.add(new Leg(npc, npc + dur, refFrom, refTo, slip));
            bestBefore.add(best);
            return npc + dur;
        }

        private long stop(long npc, long ref, long dur, long best) {
            legs.add(new Leg(npc, npc + dur, ref, ref, true));
            bestBefore.add(best);
            return npc + dur;
        }

        public GhostPath path() {
            return path;
        }

        public CpuLevel level() {
            return level;
        }

        /** The legs, in order (for the tests). */
        public List<Leg> legs() {
            return List.copyOf(legs);
        }

        /** When it crosses the line (ms from Go). */
        public long finishMs() {
            return legs.isEmpty() ? 0 : legs.get(legs.size() - 1).npcTo();
        }

        /** Where it is {@code ms} after Go (before Go: on its start; after the line: on the line). */
        public Pose at(long ms) {
            if (legs.isEmpty() || ms >= finishMs()) {
                Sample s = path.at(path.totalMs());
                return new Pose(s.x(), s.y(), s.z(), s.yaw(), s.pitch(), path.targetMs.length, 0, false, true);
            }
            long t = Math.max(0, ms);
            int i = 0;
            while (i < legs.size() - 1 && legs.get(i).npcTo() <= t) {
                i++;
            }
            Leg l = legs.get(i);
            double f = l.npcTo() == l.npcFrom() ? 1 : (double) (t - l.npcFrom()) / (l.npcTo() - l.npcFrom());
            long ref = l.refFrom() + Math.round((l.refTo() - l.refFrom()) * f);
            Sample s = path.at(ref);
            int reached = path.reachedBy(Math.max(ref, bestBefore.get(i)));
            return new Pose(s.x(), s.y(), s.z(), s.yaw(), s.pitch(), reached, path.toTarget(s, reached), l.slip(),
                    false);
        }
    }
}
