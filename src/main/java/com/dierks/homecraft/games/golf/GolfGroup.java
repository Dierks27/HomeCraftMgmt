package com.dierks.homecraft.games.golf;

import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.trial.PartyLobby;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Golf together (EVENTS-OWNER-DECISIONS D4): a group of up to {@value #MAX} playing one course at
 * the same time. Pure: no Bukkit, no clock; {@link GolfRounds} plays it on the server.
 *
 * <p><b>The turn flow.</b> Everyone plays the same hole at once, each with their own ball (balls
 * never meet: each round's ball is its own). A player whose ball is in the cup (or picked up) waits;
 * the hole ends when every ball still in the group is in or picked up, and then everyone moves to
 * the next tee together ({@link #advance}). After the last hole every round finishes as a normal
 * round (its own board, rewards and achievements, exactly as alone), and the group ranking shows.
 *
 * <p><b>Leaving</b> is fine at any time: the player's row stays on the shared scorecard as "left",
 * and the others carry on. If everyone still playing has finished the hole when someone leaves, the
 * hole ends there and then. A group with nobody left in it is over.
 *
 * <p><b>The hole clock</b> keeps one slow or away player from holding everyone up: the first ball of
 * a hole in starts it while any ball is still out, and when it runs out ({@link #second}) every ball
 * still out is picked up. Moving on to the next hole stops it. It gives {@value #HOLE_CLOCK_SECONDS}
 * seconds, or on a Golf v4 hole (a generated course whose tag is golf planner version
 * {@value #FIRST_LONG_CLOCK_ALGO} or later) max({@value #HOLE_CLOCK_SECONDS},
 * {@value #CLOCK_PER_STROKE} x (par + 1)): 120, 120, 150, 180 and 210 seconds for par 2-6
 * (GOLF-V4-SPEC §6.4). A v4 hole is longer: a stroke takes at most the roll (2.4 s on turf, 12 s on an
 * ice express) and about 10 s for a player to walk, aim and click, so about 22 s; once the first ball
 * is in, a kid-policy player needs at most par + 1 more strokes, and 22 (par + 1) is under 30 (par + 1).
 * A course kept from a v4 plan keeps it ({@link GolfCourse#keptAlgo}). Hand-built courses and older
 * layouts (algo 2 and 3, their recalls and the courses kept from them) keep 120 s ({@link #clocks}).
 *
 * <p><b>The shared scorecard</b> ({@link #card}) shows every player's holes, and the ranking
 * ({@link #ranking}) orders the players who finished by total strokes (fewest first), level totals
 * sharing a place; anyone who left is listed after them, unplaced.
 */
public final class GolfGroup {

    /** The most in a group: golf's party limit. */
    public static final int MAX = PartyLobby.Kind.GOLF.limit();
    /** How long the balls still out have once the first ball of a hole is in (seconds), at least. */
    public static final int HOLE_CLOCK_SECONDS = 120;
    /** The first golf planner version whose holes get a clock by par (Golf v4, GOLF-V4-SPEC §6.4). */
    public static final int FIRST_LONG_CLOCK_ALGO = 4;
    /** Seconds the v4 clock gives per stroke of par + 1. */
    public static final int CLOCK_PER_STROKE = 30;

    /** Where a player is on the hole being played. */
    public enum Seat {
        /** Their ball is still out. */
        PLAYING,
        /** In the cup or picked up: waiting for the others. */
        WAITING,
        /** Left the group (Leave game, a quit, a session that ended). */
        LEFT
    }

    /** One player's line of the shared scorecard. */
    public record Row(UUID player, String name, List<GolfRun.HoleScore> scores, int strokes, Seat seat) {

        public Row {
            scores = List.copyOf(scores);
        }

        /** Strokes over the holes finished. */
        public int total() {
            int t = 0;
            for (GolfRun.HoleScore s : scores) {
                t += s.strokes();
            }
            return t;
        }

        /** Strokes against par over the holes finished. */
        public int vsPar() {
            int par = 0;
            for (GolfRun.HoleScore s : scores) {
                par += s.par();
            }
            return total() - par;
        }
    }

    /**
     * One player in the group ranking.
     *
     * @param place    their place (level totals share one), or 0 for someone who left
     * @param finished they played every hole
     */
    public record Standing(int place, UUID player, String name, int total, int vsPar, boolean finished) {
    }

    /**
     * A snapshot of the shared scorecard.
     *
     * @param clock seconds left on the hole clock, or -1 when it isn't running
     */
    public record Card(String courseId, String courseName, List<Integer> pars, int hole, List<Row> rows,
                       boolean over, int clock) {

        public Card {
            pars = List.copyOf(pars);
            rows = List.copyOf(rows);
        }

        /** Par for the course. */
        public int par() {
            int t = 0;
            for (int p : pars) {
                t += p;
            }
            return t;
        }
    }

    private static final class Member {
        final UUID id;
        final String name;
        final List<GolfRun.HoleScore> scores = new ArrayList<>();
        Seat seat = Seat.PLAYING;
        int strokes;

        Member(UUID id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private final long id;
    private final String courseId;
    private final String courseName;
    private final List<Integer> pars;
    /** Each hole's clock (seconds). */
    private final List<Integer> clocks;
    private final Map<UUID, Member> members = new LinkedHashMap<>();
    private int hole;
    /** Seconds left on the hole clock, or -1 when it isn't running. */
    private int clock = -1;

    /**
     * A group starting hole 1 together.
     *
     * @param id      its party's id
     * @param players who plays, in the order they joined the party, with their names
     */
    public GolfGroup(long id, String courseId, String courseName, List<Integer> pars, Map<UUID, String> players) {
        this(id, courseId, courseName, pars, null, players);
    }

    /**
     * A group starting hole 1 together, each hole with its own clock ({@link #clocks(GolfCourse)},
     * worked out from the course when the group starts).
     *
     * @param clocks each hole's clock in seconds, or {@code null} for {@value #HOLE_CLOCK_SECONDS}
     *               on every hole
     */
    public GolfGroup(long id, String courseId, String courseName, List<Integer> pars, List<Integer> clocks,
                     Map<UUID, String> players) {
        if (pars == null || pars.isEmpty()) {
            throw new IllegalArgumentException("a round needs at least one hole");
        }
        if (clocks != null && clocks.size() != pars.size()) {
            throw new IllegalArgumentException("a clock per hole: " + clocks.size() + " for " + pars.size());
        }
        if (players == null || players.isEmpty() || players.size() > MAX) {
            throw new IllegalArgumentException("a group is 1 to " + MAX + " players");
        }
        this.id = id;
        this.courseId = courseId;
        this.courseName = courseName;
        this.pars = List.copyOf(pars);
        List<Integer> c = new ArrayList<>();
        for (int i = 0; i < pars.size(); i++) {
            c.add(clocks == null ? HOLE_CLOCK_SECONDS : Math.max(1, clocks.get(i)));
        }
        this.clocks = List.copyOf(c);
        for (Map.Entry<UUID, String> e : players.entrySet()) {
            members.put(e.getKey(), new Member(e.getKey(), e.getValue()));
        }
    }

    public long id() {
        return id;
    }

    public String courseId() {
        return courseId;
    }

    /** The hole everyone is on, 0-based ({@link #holes()} once the round is over). */
    public int hole() {
        return hole;
    }

    /** How many holes the course has. */
    public int holes() {
        return pars.size();
    }

    /** Whether the player is in the group (and hasn't left). */
    public boolean has(UUID player) {
        Member m = members.get(player);
        return m != null && m.seat != Seat.LEFT;
    }

    /** Where the player is on this hole, or {@code null} when they were never in the group. */
    public Seat seat(UUID player) {
        Member m = members.get(player);
        return m == null ? null : m.seat;
    }

    /** Everyone still in the group, in join order. */
    public List<UUID> active() {
        List<UUID> out = new ArrayList<>();
        for (Member m : members.values()) {
            if (m.seat != Seat.LEFT) {
                out.add(m.id);
            }
        }
        return out;
    }

    /** The names of those whose ball is still out on this hole. */
    public List<String> waitingFor() {
        List<String> out = new ArrayList<>();
        for (Member m : members.values()) {
            if (m.seat == Seat.PLAYING) {
                out.add(m.name);
            }
        }
        return out;
    }

    /** Those whose ball is still out on this hole, in join order. */
    public List<UUID> out() {
        List<UUID> out = new ArrayList<>();
        for (Member m : members.values()) {
            if (m.seat == Seat.PLAYING) {
                out.add(m.id);
            }
        }
        return out;
    }

    /** The strokes a player has on the hole being played (for the live card). */
    public void strokes(UUID player, int strokes) {
        Member m = members.get(player);
        if (m != null && m.seat == Seat.PLAYING) {
            m.strokes = Math.max(0, strokes);
        }
    }

    /**
     * The player's ball is in the cup (or picked up) on the hole being played.
     *
     * @return whether the hole is over for the whole group now (everyone still in has finished it)
     */
    public boolean holeDone(UUID player, GolfRun.HoleScore score) {
        if (over()) {
            return false;
        }
        Member m = members.get(player);
        if (m == null || m.seat != Seat.PLAYING || score == null) {
            return allDone();
        }
        m.scores.add(score);
        m.strokes = 0;
        m.seat = Seat.WAITING;
        boolean all = allDone();
        if (!all && clock < 0) {
            clock = holeClock(); // the first ball in: the others have the hole clock
        }
        return all;
    }

    /** Seconds left on the hole clock, or -1 when it isn't running. */
    public int clock() {
        return clock;
    }

    /** The clock the hole being played gives once its first ball is in (seconds). */
    public int holeClock() {
        return clocks.get(Math.min(hole, clocks.size() - 1));
    }

    /**
     * Each hole's clock (seconds) on {@code course}: on a Golf v4 hole — a generated course whose tag
     * is golf planner version {@value #FIRST_LONG_CLOCK_ALGO} or later, or a course kept from such a
     * plan ({@link GolfCourse#keptAlgo}, red-team F02) — max({@value #HOLE_CLOCK_SECONDS},
     * {@value #CLOCK_PER_STROKE} x (par + 1)); on a hand-built course and every older layout (its
     * recall, or a course kept from it), {@value #HOLE_CLOCK_SECONDS}.
     */
    public static List<Integer> clocks(GolfCourse course) {
        List<Integer> out = new ArrayList<>();
        boolean v4 = course != null && plannedAlgo(course) >= FIRST_LONG_CLOCK_ALGO;
        if (course != null) {
            for (GolfCourse.Hole h : course.holes()) {
                out.add(v4 ? holeClock(h.par()) : HOLE_CLOCK_SECONDS);
            }
        }
        return out;
    }

    /**
     * The golf planner version {@code course}'s layout came from: its tag's for a generated golf
     * course, the one it was kept from for a kept course, else 0 (hand-built).
     */
    static int plannedAlgo(GolfCourse course) {
        if (course.generated()) {
            return Slots.GOLF.equals(course.gen().generator()) ? course.gen().algo() : 0;
        }
        return course.keptAlgo();
    }

    /**
     * A Golf v4 hole's clock for its {@code par}: max({@value #HOLE_CLOCK_SECONDS},
     * {@value #CLOCK_PER_STROKE} x (par + 1)).
     */
    public static int holeClock(int par) {
        return Math.max(HOLE_CLOCK_SECONDS, CLOCK_PER_STROKE * (par + 1));
    }

    /**
     * A second passes: the hole clock, if it is running, counts down.
     *
     * @return whether it has run out with a ball still out: every ball still out is picked up now
     *         (and it keeps saying so until none is)
     */
    public boolean second() {
        if (clock > 0) {
            clock--;
        }
        return clock == 0 && !allDone() && !over();
    }

    /**
     * The player leaves the group: their row stays on the card as "left", and they are never waited
     * for again.
     *
     * @return whether the hole is over for the whole group now (the others had all finished it)
     */
    public boolean left(UUID player) {
        Member m = members.get(player);
        if (m == null || m.seat == Seat.LEFT) {
            return false;
        }
        m.seat = Seat.LEFT;
        m.strokes = 0;
        return allDone();
    }

    /** Whether every ball still in the group is in or picked up (and someone is still in it). */
    public boolean allDone() {
        boolean anyone = false;
        for (Member m : members.values()) {
            if (m.seat == Seat.PLAYING) {
                return false;
            }
            anyone |= m.seat == Seat.WAITING;
        }
        return anyone;
    }

    /**
     * Everyone moves on together: the next hole, every player still in back to PLAYING. Only once the
     * hole is over for everyone ({@link #allDone}); otherwise nothing changes and it says false.
     *
     * @return whether everyone moved to a next hole (false: a ball is still out, or that was the
     *         last hole and the round is over for the group, {@link #over})
     */
    public boolean advance() {
        if (!allDone() || over()) {
            return false;
        }
        hole++;
        clock = -1;
        if (hole < pars.size()) {
            for (Member m : members.values()) {
                if (m.seat == Seat.WAITING) {
                    m.seat = Seat.PLAYING;
                }
            }
            return true;
        }
        return false;
    }

    /** Whether the round is over for the group: every hole played, or nobody left in it. */
    public boolean over() {
        return hole >= pars.size() || active().isEmpty();
    }

    /** The shared scorecard as it stands. */
    public Card card() {
        List<Row> rows = new ArrayList<>();
        for (Member m : members.values()) {
            rows.add(new Row(m.id, m.name, m.scores, m.seat == Seat.PLAYING ? m.strokes : 0, m.seat));
        }
        boolean over = over();
        return new Card(courseId, courseName, pars, Math.min(hole, pars.size() - 1), rows, over, over ? -1 : clock);
    }

    /**
     * The group ranking: players who played every hole by total strokes (fewest first; level totals
     * share a place, the next place is skipped), then those still playing by strokes so far, then
     * anyone who left, unplaced.
     */
    public List<Standing> ranking() {
        return ranking(card().rows(), pars.size());
    }

    /** {@link #ranking()} over a card's rows. */
    public static List<Standing> ranking(List<Row> rows, int holes) {
        List<Row> in = new ArrayList<>();
        List<Row> out = new ArrayList<>();
        for (Row r : rows) {
            (r.seat() == Seat.LEFT ? out : in).add(r);
        }
        Comparator<Row> byTotal = Comparator.comparingInt((Row r) -> r.scores().size() >= holes ? 0 : 1)
                .thenComparing(Comparator.comparingInt((Row r) -> r.scores().size()).reversed())
                .thenComparingInt(Row::total);
        in.sort(byTotal);
        List<Standing> result = new ArrayList<>();
        for (int i = 0; i < in.size(); i++) {
            Row r = in.get(i);
            int place = i > 0 && byTotal.compare(in.get(i - 1), r) == 0 ? result.get(i - 1).place() : i + 1;
            result.add(new Standing(place, r.player(), r.name(), r.total(), r.vsPar(), r.scores().size() >= holes));
        }
        for (Row r : out) {
            result.add(new Standing(0, r.player(), r.name(), r.total(), r.vsPar(), false));
        }
        return result;
    }

    /** A clock's time left: "2:00", "0:09". */
    public static String clockText(int seconds) {
        int s = Math.max(0, seconds);
        return s / 60 + ":" + (s % 60 < 10 ? "0" : "") + s % 60;
    }

    /** "1st", "2nd", "3rd", "4th". */
    public static String ordinal(int n) {
        int mod100 = n % 100;
        if (mod100 >= 11 && mod100 <= 13) {
            return n + "th";
        }
        return switch (n % 10) {
            case 1 -> n + "st";
            case 2 -> n + "nd";
            case 3 -> n + "rd";
            default -> n + "th";
        };
    }
}
