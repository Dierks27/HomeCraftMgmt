package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.gen.api.GenTag;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The fair-play rules of a run (spec §11, R2.15), pure and tested: when a fall sends you back,
 * when a landing doesn't, and whether a finished run counts.
 *
 * <p><b>Why these checks and no others.</b> A run happens in a world session, so the player
 * can't carry anything in, can't be hurt, and a teleport by anyone else is caught (a far one ends
 * the game, a short one sends the run back to its last checkpoint). What's left for the run to
 * check is what the session can't see: a run quicker than the course could ever be done
 * ({@code min_seconds}); a leg between two checkpoints covered faster than the kind of course
 * allows, counting only the distance that must really be travelled, {@code max(0, d − r_prev −
 * r_cur)}, so big spheres close together never trip it; and flying, a changed game mode, a
 * potion effect, a changed walk speed or a movement attribute boosted past an honest run's (judged
 * on its value, never a modifier's key), which void the run the moment they are seen.
 *
 * <p><b>Server stalls.</b> A move is timed when the server handles it. While the server stalls
 * (an autosave, new chunks, a GC pause) the client's moves queue up and are then handled
 * microseconds apart, so two checkpoints crossed during the stall look a hair apart. Lag only
 * ever lengthens a time, so it is never a cheat — but it would look like one. So a gap between
 * two trial ticks longer than {@link #STALL_NANOS} is kept as a {@link Stall}, and the speed check
 * skips any leg whose time overlaps one; every other leg gets {@link #LEG_SLACK} of slack.
 *
 * <p>A void is not a punishment: the player finishes the run, sees their time, and reads "That run
 * didn't count." with why. A test run is judged the same way but records nothing, and says
 * whether it would have counted.
 *
 * <p>Public (EVENTS-DROPPER-SPEC C1), so the race mode and the Dropper's pure rules judge a race or
 * a drop with these same checks instead of copies of them. The one Dropper rule: the speed check
 * skips the legs that end at a ledge, which are the game's own hops ({@link #ownTeleport}).
 */
public final class FairPlay {

    /** How far from the start a run may begin (the countdown restarts beyond it). */
    public static final double START_RADIUS = 1.0;
    /** Around the start, a landing is fine (an elytra run takes off from there). */
    public static final double START_ZONE = 2.0;

    public static final String FLYING = "flying";
    public static final String GAME_MODE = "your game mode changed";
    public static final String EFFECT = "a potion effect";
    public static final String TOO_QUICK = "quicker than this course allows";
    public static final String TOO_FAST = "too fast between two checkpoints";
    public static final String CHANGED = "the course changed during your run";
    public static final String WALK_SPEED = "your walk speed changed";
    public static final String MOVEMENT = "your movement changed";

    /** A gap between two trial ticks longer than this is a server stall. */
    public static final long STALL_NANOS = 250_000_000L;
    /** Every leg gets this much more time before it counts as too fast (one tick of jitter). */
    public static final double LEG_SLACK = 0.05;

    /** A player's walk speed when nothing has changed it. */
    public static final float DEFAULT_WALK_SPEED = 0.2f;
    /**
     * Vanilla's own sprint modifier, on the movement speed. Never judged by its key (0.37.3): the watch judges the
     * value, and a sprint under any key is no faster than this one.
     */
    public static final String SPRINTING = "minecraft:sprinting";
    /** Vanilla's own powder-snow slow-down: it only slows, so it never voids, under this key or any other. */
    public static final String POWDER_SNOW = "minecraft:powder_snow";
    public static final String MOVEMENT_SPEED = "minecraft:movement_speed";
    /**
     * The one watched attribute judged both ways: less gravity is a longer, slower fall (a parkour jump, an elytra's
     * glide), more a faster one (a Dropper's fall, an elytra's dive).
     */
    public static final String GRAVITY = "minecraft:gravity";
    /** The movement attributes a run watches, and a player's own base value of each. */
    public static final Map<String, Double> PLAYER_BASES = Map.of(
            MOVEMENT_SPEED, 0.1,
            "minecraft:jump_strength", 0.42,
            "minecraft:step_height", 0.6,
            GRAVITY, 0.08,
            "minecraft:safe_fall_distance", 3.0);
    /** The fastest an honest player moves on foot: their own 0.1 with vanilla sprinting's +30% of the total. */
    public static final double SPRINT_SPEED = 0.1 * 1.3;
    /** A value within this share of its mark is on it: a float's rounding, never a boost. */
    public static final double BOOST_SLACK = 1e-4;

    private FairPlay() {
    }

    /** How a finished run is taken. */
    public enum Kind {
        /** Recorded and rewarded. */
        COUNTED,
        /** Seen breaking a rule: shown, not recorded. */
        VOID,
        /** The course was changed (or deleted) mid-run: its time belongs to a layout that's gone. */
        STALE,
        /** An admin's try-out: nothing is ever recorded. */
        TEST
    }

    /**
     * The verdict on a finished run.
     *
     * @param kind   how it is taken
     * @param reason why it doesn't count (for a test: why it wouldn't have), or {@code null}
     */
    public record Verdict(Kind kind, String reason) {

        /** Whether it is recorded and rewarded. */
        public boolean counts() {
            return kind == Kind.COUNTED;
        }
    }

    /**
     * A time the server stood still, in {@code System.nanoTime()}: from the last trial tick before
     * it to a little after the first tick after it, when the moves that queued up are handled.
     */
    public record Stall(long from, long to) {

        /** Whether it overlaps {@code start}..{@code end}. */
        public boolean overlaps(long start, long end) {
            return start <= to && end >= from;
        }
    }

    /**
     * The stall between two trial ticks at {@code prevTick} and {@code tick}, or {@code null} when
     * the gap was an ordinary one. It runs on {@link #STALL_NANOS} past the second tick: the moves
     * that queued up during it are handled in a burst around then. A {@code prevTick} of 0 means
     * there was no tick before this one.
     */
    public static Stall stall(long prevTick, long tick) {
        if (prevTick == 0 || tick - prevTick <= STALL_NANOS) {
            return null;
        }
        return new Stall(prevTick, tick + STALL_NANOS);
    }

    /**
     * Judge a finished run.
     *
     * @param test       an admin's test run (records nothing, whatever else)
     * @param voided     why the run was voided while it ran, or {@code null}
     * @param stale      the course's layout changed (or it was deleted) since the run started
     * @param ms         the run's time
     * @param minSeconds the course's shortest believable time
     * @param tooFastAt  the first target reached too fast ({@link #tooFast}), or -1
     */
    public static Verdict judge(boolean test, String voided, boolean stale, long ms, int minSeconds, int tooFastAt) {
        String reason = voided;
        if (reason == null && tooQuick(ms, minSeconds)) {
            reason = TOO_QUICK;
        }
        if (reason == null && tooFastAt >= 0) {
            reason = TOO_FAST;
        }
        if (test) {
            return new Verdict(Kind.TEST, reason);
        }
        if (stale) {
            return new Verdict(Kind.STALE, CHANGED);
        }
        return reason == null ? new Verdict(Kind.COUNTED, null) : new Verdict(Kind.VOID, reason);
    }

    /**
     * {@code v} with what tripped a finish-time rule, for the player's line and the log: a too-quick run's
     * time against the shortest, or the too-fast leg ({@code tooFastAt}, as {@link #judge} got it) with its
     * gap, time and speed against the kind's limit. Any other verdict comes back as it is.
     */
    public static Verdict explained(Verdict v, Course course, long startNanos, long[] times, int tooFastAt,
                                    long ms, int minSeconds) {
        if (v == null || v.reason() == null || course == null) {
            return v;
        }
        if (TOO_QUICK.equals(v.reason())) {
            return new Verdict(v.kind(), TOO_QUICK + " (" + TrialText.time(ms) + ", shortest " + minSeconds + " s)");
        }
        List<Course.Mark> targets = course.targets();
        if (!TOO_FAST.equals(v.reason()) || tooFastAt < 0 || course.start() == null || times == null
                || tooFastAt >= times.length || tooFastAt >= targets.size()) {
            return v;
        }
        Course.Mark cur = targets.get(tooFastAt);
        Point prev = tooFastAt == 0 ? course.start().point() : targets.get(tooFastAt - 1).center();
        double rPrev = tooFastAt == 0 ? START_RADIUS : targets.get(tooFastAt - 1).radius();
        long prevAt = tooFastAt == 0 ? startNanos : times[tooFastAt - 1];
        double gap = Math.max(0, prev.distance(cur.center()) - rPrev - cur.radius());
        double seconds = (times[tooFastAt] - prevAt) / 1e9;
        String to = tooFastAt == targets.size() - 1 ? "the finish" : "checkpoint " + (tooFastAt + 1);
        return new Verdict(v.kind(), TOO_FAST + " (to " + to + ": " + num(Math.round(gap * 10) / 10.0) + " blocks in "
                + num(Math.round(seconds * 100) / 100.0) + " s, limit " + num(course.kind().maxSpeed()) + " a second)");
    }

    /**
     * When a watch void fired, for its log line: "at Go" within the first tick of the clock ({@code ms} under 50,
     * the watch's own call at Go), else "3.2 s in". At Go while standing still points to something the player
     * carried in; the moment they started to run points to sprinting.
     */
    public static String when(long ms) {
        return ms < 50 ? "at Go" : num(Math.round(ms / 100.0) / 10.0) + " s in";
    }

    /** Whether a run of {@code ms} beats the shortest believable time. */
    public static boolean tooQuick(long ms, int minSeconds) {
        return minSeconds > 0 && ms < minSeconds * 1000L;
    }

    /**
     * Whether a leg was covered faster than {@code maxSpeed} allows: the distance that must really
     * be travelled between the two spheres, {@code max(0, d − rPrev − rCur)}, over the time taken.
     */
    public static boolean tooFast(double d, double rPrev, double rCur, double seconds, double maxSpeed) {
        double gap = Math.max(0, d - rPrev - rCur);
        if (gap == 0) {
            return false;
        }
        return seconds <= 0 || gap / seconds > maxSpeed;
    }

    /**
     * The first target (0-based: checkpoints, then the finish) reached faster than the course's
     * kind allows, or -1 when every leg is believable. The first leg is from the start, which a run
     * may begin up to {@link #START_RADIUS} away from.
     *
     * @param startNanos when the run started
     * @param times      when each target was reached ({@link Progress#times()})
     * @param reached    how many targets were reached
     */
    public static int tooFast(Course course, long startNanos, long[] times, int reached) {
        return tooFast(course, startNanos, times, reached, List.of());
    }

    /**
     * {@link #tooFast(Course, long, long[], int)} for a run that saw server stalls: a leg whose
     * time overlaps a stall isn't checked (its times are when the server caught up, not when the
     * player got there), and every other leg gets {@link #LEG_SLACK} more time.
     *
     * @param stalls the stalls seen while the run ran
     */
    public static int tooFast(Course course, long startNanos, long[] times, int reached, List<Stall> stalls) {
        if (course.start() == null) {
            return -1;
        }
        List<Course.Mark> targets = course.targets();
        Point prev = course.start().point();
        double prevRadius = START_RADIUS;
        long prevAt = startNanos;
        int n = Math.min(reached, Math.min(times.length, targets.size()));
        for (int i = 0; i < n; i++) {
            Course.Mark cur = targets.get(i);
            double seconds = (times[i] - prevAt) / 1e9 + LEG_SLACK;
            double d = prev.distance(cur.center());
            if (!stalled(stalls, prevAt, times[i]) && !ownTeleport(course, i)
                    && tooFast(d, prevRadius, cur.radius(), seconds, course.kind().maxSpeed())) {
                return i;
            }
            prev = cur.center();
            prevRadius = cur.radius();
            prevAt = times[i];
        }
        return -1;
    }

    /**
     * Whether the leg ending at target {@code i} is the game's own teleport, never a player's move: a
     * Dropper's hop from a pool up to the next level's ledge (EVENTS-DROPPER-SPEC §B.1.7), some 30
     * to 50 blocks in a quarter of a second. The legs that end at a pool are real falls, still checked
     * (a Long Drop's 129-block fall averages about 36 blocks a second, under the kind's 80).
     */
    static boolean ownTeleport(Course course, int i) {
        return course.kind() == TrialKind.DROPPER && !DropperLayout.isPool(i);
    }

    private static boolean stalled(List<Stall> stalls, long start, long end) {
        if (stalls != null) {
            for (Stall st : stalls) {
                if (st.overlaps(start, end)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * One modifier on an attribute, as the player has it.
     *
     * @param key       its key ({@code minecraft:sprinting})
     * @param operation its Bukkit operation's name ({@code MULTIPLY_SCALAR_1})
     * @param amount    its amount
     */
    public record Modifier(String key, String operation, double amount) {
    }

    /**
     * One movement attribute as the player has it.
     *
     * @param key       the attribute's key ({@code minecraft:gravity})
     * @param base      its base value
     * @param value     its value with every modifier applied (Paper's {@code getValue()})
     * @param modifiers every modifier on it
     */
    public record Stat(String key, double base, double value, List<Modifier> modifiers) {
    }

    /**
     * Why the player's movement voids a run, or {@code null} when it is a player's own: a walk
     * speed other than {@value #DEFAULT_WALK_SPEED} ({@code /speed}), or a watched attribute
     * ({@link #PLAYER_BASES}) boosted past an honest run's ({@link #boost}) — another plugin's speed, jump, step,
     * gravity or safe-fall boost. Judged on the values, never a modifier's key (0.37.3), so vanilla's sprint under
     * any key, powder snow and anything that only slows never void; gravity, either way, still does. A value that
     * isn't a number counts as changed.
     */
    public static String movement(float walkSpeed, Collection<Stat> stats) {
        Finding f = finding(walkSpeed, stats);
        return f == null ? null : f.reason();
    }

    /**
     * What tripped {@link #movement}: its reason ({@link #WALK_SPEED} or {@link #MOVEMENT}) and exactly
     * what, so the player's line and the log name it.
     *
     * @param reason {@link #WALK_SPEED} or {@link #MOVEMENT}
     * @param detail "walk speed 0.4, not 0.2", "minecraft:jump_strength base 0.6, over 0.42; value 0.6; no
     *               modifiers" or "minecraft:gravity 0.04, under 0.08; base 0.08; someplugin:floaty -0.5
     *               multiply_scalar_1"
     */
    public record Finding(String reason, String detail) {

        /** "your movement changed (minecraft:jump_strength base 0.6, over 0.42; value 0.6; no modifiers)". */
        public String line() {
            return detail == null || detail.isBlank() ? reason : reason + " (" + detail + ")";
        }
    }

    /** {@link #movement}, with what tripped it; {@code null} exactly when {@link #movement} is. */
    public static Finding finding(float walkSpeed, Collection<Stat> stats) {
        if (!(Math.abs(walkSpeed - DEFAULT_WALK_SPEED) <= 1e-4)) {
            return new Finding(WALK_SPEED, "walk speed " + num(walkSpeed) + ", not " + num(DEFAULT_WALK_SPEED));
        }
        for (Stat st : stats) {
            String b = boost(st);
            if (b != null) {
                return new Finding(MOVEMENT, b);
            }
        }
        return null;
    }

    /**
     * The most an honest run has of watched attribute {@code key} (exactly that, for {@link #GRAVITY}): {@link
     * #SPRINT_SPEED} for the movement speed, the player's own base ({@link #PLAYER_BASES}) for the others;
     * {@code NaN} for an attribute the watch doesn't read.
     */
    public static double mark(String key) {
        if (MOVEMENT_SPEED.equals(key)) {
            return SPRINT_SPEED;
        }
        Double own = PLAYER_BASES.get(key);
        return own == null ? Double.NaN : own;
    }

    /** Whether watched attribute {@code key} is judged both ways, so any change is a boost: gravity only. */
    public static boolean bothWays(String key) {
        return GRAVITY.equals(key);
    }

    /**
     * How {@code st} is boosted past an honest run's, for the void's line, or {@code null} when it isn't: its base
     * past the player's own (a raised movement speed base would otherwise hide under the sprint's headroom while
     * walking), or its value past its {@link #mark}, by more than {@link #BOOST_SLACK} of it, upward or, for
     * gravity, either way ({@link #bothWays}). A base or value that isn't a number can't be judged, so it is a
     * boost. Anything that only slows or lowers (gravity aside) is never one, and nor is any modifier, under any
     * key, that leaves the value within the mark: vanilla's sprint renamed, a 0 amount, another plugin's
     * slow-down. Every modifier is listed ({@link #listed}). {@code null} for an attribute the watch doesn't
     * read.
     */
    public static String boost(Stat st) {
        Double own = PLAYER_BASES.get(st.key());
        if (own == null) {
            return null;
        }
        String mods = listed(st.modifiers());
        if (!Double.isFinite(st.base()) || !Double.isFinite(st.value())) {
            return st.key() + " base " + num(st.base()) + ", value " + num(st.value()) + "; " + mods;
        }
        boolean both = bothWays(st.key());
        if (past(st.base(), own, both)) {
            return st.key() + " base " + num(st.base()) + ", " + side(st.base(), own) + num(own) + "; value "
                    + num(st.value()) + "; " + mods;
        }
        double mark = mark(st.key());
        if (past(st.value(), mark, both)) {
            return st.key() + " " + num(st.value()) + ", " + side(st.value(), mark) + num(mark) + "; base "
                    + num(st.base()) + "; " + mods;
        }
        return null;
    }

    private static boolean past(double v, double mark, boolean both) {
        return v > mark * (1 + BOOST_SLACK) || (both && v < mark * (1 - BOOST_SLACK));
    }

    private static String side(double v, double mark) {
        return v > mark ? "over " : "under ";
    }

    /** "minecraft:sprinting 0.3 multiply_scalar_1; someplugin:boost 0.2 multiply_scalar_1", or "no modifiers". */
    public static String listed(List<Modifier> modifiers) {
        if (modifiers == null || modifiers.isEmpty()) {
            return "no modifiers";
        }
        List<String> out = new ArrayList<>(modifiers.size());
        for (Modifier m : modifiers) {
            out.add(m.key() + " " + num(m.amount()) + " " + m.operation().toLowerCase(Locale.ROOT));
        }
        return String.join("; ", out);
    }

    /**
     * The modifiers on {@code st} under any key but vanilla's sprint and powder snow ({@link #SPRINTING}, {@link
     * #POWDER_SNOW}): what the console notes once a key, boost or not, so whatever another plugin or a datapack
     * adds is named.
     */
    public static List<Modifier> foreign(Stat st) {
        List<Modifier> out = new ArrayList<>();
        for (Modifier m : st.modifiers()) {
            if (!SPRINTING.equals(m.key()) && !POWDER_SNOW.equals(m.key())) {
                out.add(m);
            }
        }
        return out;
    }

    /** A value for a line: at most 6 decimals, no trailing zeros ("0.42" for a stored 0.41999998688697815). */
    public static String num(double v) {
        if (!Double.isFinite(v)) {
            return String.valueOf(v);
        }
        String s = String.format(Locale.ROOT, "%.6f", v).replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    /**
     * Whether a run that started on {@code then} records nothing because of {@code now}: the
     * course was deleted, or its layout is not the one the run started on — a new {@code rev}, or
     * the same {@code rev} on a different layout (a course deleted and made again starts at
     * layout 1, so the rev alone can come round to the same number).
     *
     * @param rev    the course's layout version when the run started
     * @param layout {@link Course#layoutHash()} when the run started
     * @param now    the course as it is now, or {@code null} for deleted
     */
    public static boolean stale(int rev, int layout, Course now) {
        return now == null || now.rev() != rev || now.layoutHash() != layout;
    }

    /**
     * {@link #stale(int, int, Course)} with Fresh Courses' "still standing" rule (GEN-SPEC §3.4):
     * a run on a generated course is stale only if the old rule says so AND the layout it started
     * on no longer stands. The next layout going live doesn't void a run on the previous one while
     * its blocks are still there; once that half starts being cleared, it does. A hand-built
     * course keeps the old rule exactly.
     *
     * @param then     the course as the run started (its snapshot, with its tag)
     * @param layout   {@link Course#layoutHash()} when the run started
     * @param now      the course as it is now, or {@code null} for deleted
     * @param standing whether a layout still stands ({@code GeneratedCourses#standing})
     */
    public static boolean stale(Course then, int layout, Course now, Predicate<GenTag> standing) {
        boolean changed = stale(then.rev(), layout, now);
        if (!changed || then.gen() == null) {
            return changed;
        }
        return standing == null || !standing.test(then.gen());
    }

    /**
     * Below this height a run goes back to its last checkpoint, or {@code NaN} for no such rule.
     * A course's own {@code fall_y} applies to any kind; without one, a parkour course falls at
     * {@code fallDepth} below the lower of the last checkpoint reached (or the start) and the next
     * one (or the finish) — so a course that climbs or drops still has a sensible floor on every
     * leg. Elytra and boat courses have no default: their resets are landing and leaving the boat.
     * Sky Rings (an elytra course with a rocket budget) also has a floor on every leg,
     * {@link #elytraFloor}, never under the course's own.
     *
     * @param lastCheckpoint the last checkpoint reached, 0-based, or -1 for none
     */
    public static double fallY(Course course, int lastCheckpoint, int fallDepth) {
        if (course.kind() == TrialKind.ELYTRA && course.elytra() != null && course.start() != null) {
            return elytraFloor(course, lastCheckpoint);
        }
        if (course.fallY() != null) {
            return course.fallY();
        }
        if (course.kind() != TrialKind.PARKOUR || course.start() == null) {
            return Double.NaN;
        }
        List<Course.Mark> cps = course.checkpoints();
        double lastY = lastCheckpoint >= 0 && lastCheckpoint < cps.size() ? cps.get(lastCheckpoint).y()
                : course.start().y();
        int nextIndex = lastCheckpoint + 1;
        Double nextY = nextIndex < cps.size() ? Double.valueOf(cps.get(nextIndex).y())
                : course.finish() != null ? Double.valueOf(course.finish().y()) : null;
        double floor = nextY == null ? lastY : Math.min(lastY, nextY);
        return floor - fallDepth;
    }

    /**
     * Sky Rings: a flyer this far under the lower of a leg's two rings is going nowhere. The autopilot's
     * proven lines never go more than 24 under it ({@code ElytraSim.LOST}), so 32 never resets one, and with
     * climbs in the course one floor under the lowest ring would leave a missed climb sinking for a minute.
     */
    public static final double ELYTRA_FLOOR_DEPTH = 32;

    /**
     * Below this height a Sky Rings run goes back to its last ring: {@value #ELYTRA_FLOOR_DEPTH} under the
     * lower of the last ring reached (the start before ring 1) and the next one (the gold ring after the
     * last), and never under the course's own fall height.
     *
     * @param lastCheckpoint the last checkpoint reached, 0-based, or -1 for none
     */
    static double elytraFloor(Course course, int lastCheckpoint) {
        List<Course.Mark> targets = course.targets();
        double lastY = lastCheckpoint >= 0 && lastCheckpoint < course.checkpoints().size()
                ? course.checkpoints().get(lastCheckpoint).y() : course.start().y();
        int next = lastCheckpoint + 1;
        double low = next >= 0 && next < targets.size() ? Math.min(lastY, targets.get(next).y()) : lastY;
        double floor = low - ELYTRA_FLOOR_DEPTH;
        return course.fallY() == null ? floor : Math.max(course.fallY(), floor);
    }

    /** Mountain Run v2: a boat this far under the lower of a leg's two marks is off the track (§12). */
    public static final double BOAT_FLOOR_DEPTH = 4;

    /**
     * Below this height a boat run goes back to its last checkpoint, or {@code NaN} for no such rule
     * (MOUNTAIN-V2-SPEC §12). On a Mountain Run v2 it is the leg's own floor: {@value #BOAT_FLOOR_DEPTH} under
     * the lower of the last mark reached (the start before the first checkpoint) and the next target (the
     * finish after the last checkpoint). A v2 leg holds at most one drop and every mark sits on the ice, so
     * the ice never goes under the next mark and a boat that left the track is caught within about 4 blocks,
     * not 70. Never under the course's own fall height. Any other course: its fall height, or none.
     *
     * @param lastCheckpoint the last checkpoint reached, 0-based, or -1 for none
     */
    public static double boatFloor(Course course, int lastCheckpoint) {
        double own = course.fallY() == null ? Double.NaN : course.fallY();
        if (!BoatHype.mountainV2(course) || course.start() == null) {
            return own;
        }
        List<Course.Mark> targets = course.targets();
        double lastY = lastCheckpoint >= 0 && lastCheckpoint < course.checkpoints().size()
                ? course.checkpoints().get(lastCheckpoint).y() : course.start().y();
        int next = lastCheckpoint + 1;
        double floor = (next >= 0 && next < targets.size() ? Math.min(lastY, targets.get(next).y()) : lastY)
                - BOAT_FLOOR_DEPTH;
        return Double.isNaN(own) ? floor : Math.max(floor, own);
    }

    /**
     * The fastest leg of a run, in blocks a second (MOUNTAIN-V2-SPEC §12, §14 R1: the admin's test-run readout),
     * for a run that saw no stall: {@link #topLegSpeed(Course, long, long[], int, List)} with none.
     */
    public static double topLegSpeed(Course course, long startNanos, long[] times, int reached) {
        return topLegSpeed(course, startNanos, times, reached, List.of());
    }

    /**
     * The fastest leg of a run, in blocks a second (MOUNTAIN-V2-SPEC §12, §14 R1: the admin's test-run readout):
     * over every leg reached (the start to the first target, then target to target), the distance from where
     * the boat entered one sphere to where it entered the next, on the straight line between their middles
     * ({@code d + rPrev - rCur}; the start is a point), over the time the leg took. A leg whose time overlaps a
     * server stall isn't counted, as in {@link #tooFast(Course, long, long[], int, List)}: its time is when the
     * server caught up, so it would read far too fast (audit M01). 0 when no leg was reached or none counts.
     *
     * @param startNanos when the run started
     * @param times      when each target was reached ({@link Progress#times()})
     * @param reached    how many targets were reached
     * @param stalls     the stalls seen while the run ran
     */
    public static double topLegSpeed(Course course, long startNanos, long[] times, int reached, List<Stall> stalls) {
        if (course == null || course.start() == null || times == null) {
            return 0;
        }
        List<Course.Mark> targets = course.targets();
        Point prev = course.start().point();
        double prevRadius = 0; // the run leaves from the start point itself
        long prevAt = startNanos;
        double top = 0;
        for (int i = 0; i < Math.min(reached, Math.min(times.length, targets.size())); i++) {
            Course.Mark cur = targets.get(i);
            long nanos = times[i] - prevAt;
            if (nanos > 0 && !stalled(stalls, prevAt, times[i])) {
                double d = Math.max(0, prev.distance(cur.center()) + prevRadius - cur.radius());
                top = Math.max(top, d / (nanos / 1e9));
            }
            prev = cur.center();
            prevRadius = cur.radius();
            prevAt = times[i];
        }
        return top;
    }

    /**
     * Whether {@code p} is somewhere a landing is fine: inside any checkpoint, the finish, or near
     * the start. An elytra run that lands anywhere else goes back to its last checkpoint.
     */
    public static boolean safeLanding(Course course, Point p) {
        if (course.start() != null && course.start().point().distance(p) <= START_ZONE) {
            return true;
        }
        for (Course.Mark m : course.targets()) {
            if (m.contains(p)) {
                return true;
            }
        }
        return false;
    }
}
