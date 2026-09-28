package com.dierks.homecraft.games.trial;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * One time-trial course (spec §11, R2.15): where it starts, the checkpoints in the order they must
 * be reached, where it ends, and its rules. Immutable — every admin edit makes a new one — so a
 * run can keep the course exactly as it was when it started (the "snapshot"), and {@link #rev}
 * says whether the layout has changed since.
 *
 * <p>Everything lives in one world ({@link #world}); points carry no world of their own, so a
 * course can't straddle two. {@link CourseCodec} stores it as YAML in {@code game_courses.data}.
 *
 * @param id          its {@code /hcm play} id
 * @param kind        parkour, elytra or boat
 * @param name        its player-facing name, no colour codes
 * @param tier        how hard it is
 * @param world       the world it is built in ({@code ""} until the first point is set)
 * @param start       where a run starts, facing the way it goes, or {@code null} for not set yet
 * @param checkpoints the checkpoints, in the order they must be reached
 * @param finish      where it ends, or {@code null} for not set yet
 * @param fallY       below this height a run goes back to its last checkpoint, or {@code null} for
 *                    the default (parkour only: {@code fall_depth} under the checkpoints either side)
 * @param minSeconds  a run faster than this doesn't count, or {@code null} for {@code min_seconds}
 * @param enabled     open to players (needs a start and a finish)
 * @param pinned      pinned as the course of the week
 * @param rev         the layout version: every geometry edit bumps it (R2.15)
 */
public record Course(String id, TrialKind kind, String name, Tier tier, String world, Spot start,
                     List<Mark> checkpoints, Mark finish, Double fallY, Integer minSeconds, boolean enabled,
                     boolean pinned, int rev) {

    /** Radii are kept in this range whatever the builder types. */
    public static final double MIN_RADIUS = 0.5;
    public static final double MAX_RADIUS = 16;
    /** The most checkpoints a course may have. */
    public static final int MAX_CHECKPOINTS = 64;

    /** A place and the way to face there: a course's start. */
    public record Spot(double x, double y, double z, float yaw, float pitch) {

        public Point point() {
            return new Point(x, y, z);
        }
    }

    /** A sphere a run must pass through: a checkpoint or the finish. */
    public record Mark(double x, double y, double z, double radius) {

        public Point center() {
            return new Point(x, y, z);
        }

        /** Whether {@code p} is inside (or on) the sphere. */
        public boolean contains(Point p) {
            return center().distance(p) <= radius;
        }
    }

    public Course {
        name = name == null ? "" : name;
        world = world == null ? "" : world;
        tier = tier == null ? Tier.EASY : tier;
        checkpoints = List.copyOf(checkpoints == null ? List.of() : checkpoints);
        rev = Math.max(1, rev);
    }

    /** A brand-new course: a name from its id, nothing placed yet, closed. */
    public static Course create(String id, TrialKind kind, Tier tier) {
        return new Course(id, kind, TrialText.defaultName(id), tier, "", null, List.of(), null, null, null, false,
                false, 1);
    }

    /** A radius inside the allowed range. */
    public static double radius(double r) {
        if (Double.isNaN(r)) {
            return MIN_RADIUS;
        }
        return Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, r));
    }

    // ---- what it is -------------------------------------------------------------------------

    /** Whether a run can happen on it: a world, a start and a finish. */
    public boolean ready() {
        return start != null && finish != null && !world.isBlank();
    }

    /**
     * What stops it being opened, in plain admin words (empty = it can be enabled): a start, a
     * finish, and a world listed in {@code games.worlds}.
     */
    public List<String> problems(Collection<String> gamesWorlds) {
        List<String> out = new ArrayList<>();
        if (start == null) {
            out.add("it has no start yet (/hcm games course " + id + " start)");
        }
        if (finish == null) {
            out.add("it has no finish yet (/hcm games course " + id + " finish)");
        }
        if (!world.isBlank() && !listed(gamesWorlds, world)) {
            out.add("its world '" + world + "' isn't in games.worlds");
        }
        return out;
    }

    /** The checkpoints, then the finish: everything a run must reach, in order. */
    public List<Mark> targets() {
        List<Mark> out = new ArrayList<>(checkpoints);
        if (finish != null) {
            out.add(finish);
        }
        return out;
    }

    /**
     * A fingerprint of the layout — the world, the start, the checkpoints, the finish and the fall
     * height — so a run can tell its course from a different one with the same {@link #rev} (a
     * course deleted and made again starts at layout 1 again). Kept in memory only, never stored.
     */
    public int layoutHash() {
        return Objects.hash(world.toLowerCase(Locale.ROOT), start, targets(), fallY);
    }

    /**
     * The lowest point a run must be at — the start, a checkpoint or the finish — or {@code null}
     * when nothing is placed yet. A fall height must be under it.
     */
    public Double lowestY() {
        Double low = start == null ? null : start.y();
        for (Mark m : targets()) {
            low = low == null ? m.y() : Math.min(low, m.y());
        }
        return low;
    }

    /** Its own shortest time, or the server's when it has none. */
    public int minSecondsOr(int serverDefault) {
        return minSeconds != null ? minSeconds : serverDefault;
    }

    // ---- edits (each a new course) ------------------------------------------------------------

    public Course withName(String n) {
        return new Course(id, kind, n, tier, world, start, checkpoints, finish, fallY, minSeconds, enabled, pinned,
                rev);
    }

    public Course withTier(Tier t) {
        return new Course(id, kind, name, t, world, start, checkpoints, finish, fallY, minSeconds, enabled, pinned,
                rev);
    }

    public Course withWorld(String w) {
        return new Course(id, kind, name, tier, w, start, checkpoints, finish, fallY, minSeconds, enabled, pinned,
                rev);
    }

    public Course withStart(Spot s) {
        return new Course(id, kind, name, tier, world, s, checkpoints, finish, fallY, minSeconds, enabled, pinned,
                rev);
    }

    public Course withCheckpoints(List<Mark> list) {
        return new Course(id, kind, name, tier, world, start, list, finish, fallY, minSeconds, enabled, pinned, rev);
    }

    /** One more checkpoint at the end. */
    public Course plusCheckpoint(Mark m) {
        List<Mark> list = new ArrayList<>(checkpoints);
        list.add(m);
        return withCheckpoints(list);
    }

    /** Without checkpoint {@code n} (1-based); unchanged when there is no such checkpoint. */
    public Course minusCheckpoint(int n) {
        if (n < 1 || n > checkpoints.size()) {
            return this;
        }
        List<Mark> list = new ArrayList<>(checkpoints);
        list.remove(n - 1);
        return withCheckpoints(list);
    }

    public Course withFinish(Mark f) {
        return new Course(id, kind, name, tier, world, start, checkpoints, f, fallY, minSeconds, enabled, pinned, rev);
    }

    public Course withFallY(Double y) {
        return new Course(id, kind, name, tier, world, start, checkpoints, finish, y, minSeconds, enabled, pinned, rev);
    }

    public Course withMinSeconds(Integer s) {
        return new Course(id, kind, name, tier, world, start, checkpoints, finish, fallY, s, enabled, pinned, rev);
    }

    public Course withEnabled(boolean on) {
        return new Course(id, kind, name, tier, world, start, checkpoints, finish, fallY, minSeconds, on, pinned, rev);
    }

    public Course withPinned(boolean on) {
        return new Course(id, kind, name, tier, world, start, checkpoints, finish, fallY, minSeconds, enabled, on, rev);
    }

    public Course withRev(int r) {
        return new Course(id, kind, name, tier, world, start, checkpoints, finish, fallY, minSeconds, enabled, pinned,
                r);
    }

    private static boolean listed(Collection<String> worlds, String world) {
        if (worlds == null) {
            return false;
        }
        for (String w : worlds) {
            if (w != null && w.equalsIgnoreCase(world)) {
                return true;
            }
        }
        return false;
    }
}
