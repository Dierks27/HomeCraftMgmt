package com.dierks.homecraft.games.golf;

import com.dierks.homecraft.games.gen.api.GenTag;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A mini golf course (spec §12): a name, a world in {@code games.worlds}, and holes in order.
 * Pure and immutable — every edit returns a new course — so the admin commands, the run snapshot
 * and the checks are all tested without a server.
 *
 * <p>A course is <b>playable</b> when it is enabled and nothing is missing: at least one hole, and
 * every hole with a tee, a cup, both bound corners, and its tee and cup inside its bounds (a ball
 * teed outside its bounds would be out on the first putt). {@link #problems} says what is missing
 * in words an admin can act on.
 *
 * @param id      the {@code /hcm play} id (shared with every game id, alias and course)
 * @param name    the player-facing name
 * @param world   the world it is built in
 * @param enabled the admin's switch
 * @param rev     goes up with every change to a hole's layout, so a round can tell the course
 *                changed under it
 * @param holes   the holes in playing order
 * @param gen     Fresh Courses' tag when the course was generated (GEN-SPEC §5.1), or {@code null}
 *                for a hand-built one, which behaves exactly as it always did
 */
public record GolfCourse(String id, String name, String world, boolean enabled, int rev, List<Hole> holes,
                         GenTag gen) {

    /** A course has at most this many holes (a scorecard fits 18). */
    public static final int MAX_HOLES = 18;
    /** Par range. */
    public static final int MIN_PAR = 2;
    public static final int MAX_PAR = 6;
    /** Course ids: lower-case, a letter first, up to 32. */
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_]{0,31}");

    public GolfCourse {
        holes = List.copyOf(holes);
    }

    /** A hand-built course (no {@code gen} tag): the constructor every course had before Fresh Courses. */
    public GolfCourse(String id, String name, String world, boolean enabled, int rev, List<Hole> holes) {
        this(id, name, world, enabled, rev, holes, null);
    }

    /** Where a hole starts: the admin's feet and the way they faced. */
    public record Tee(double x, double y, double z, float yaw) {
    }

    /** A block. */
    public record Spot(int x, int y, int z) {
    }

    /**
     * One hole.
     *
     * @param tee     where the ball starts ({@code null} until set)
     * @param cup     the cup block ({@code null} until set)
     * @param par     its par, 2-6
     * @param corner1 one bound corner ({@code null} until set)
     * @param corner2 the other
     */
    public record Hole(Tee tee, Spot cup, int par, Spot corner1, Spot corner2) {

        public Hole withTee(Tee t) {
            return new Hole(t, cup, par, corner1, corner2);
        }

        public Hole withCup(Spot c) {
            return new Hole(tee, c, par, corner1, corner2);
        }

        public Hole withPar(int p) {
            return new Hole(tee, cup, p, corner1, corner2);
        }

        /** Set bound corner 1 or 2. */
        public Hole withCorner(int which, Spot corner) {
            return which == 1 ? new Hole(tee, cup, par, corner, corner2) : new Hole(tee, cup, par, corner1, corner);
        }

        /** Whether both bound corners are set. */
        public boolean bounded() {
            return corner1 != null && corner2 != null;
        }

        /** The hole as the ball sees it, its cup a full block (only when complete; for the checks). */
        public BallPhysics.Hole physics() {
            return BallPhysics.Hole.of(cup.x(), cup.y(), cup.z(), corner1.x(), corner1.y(), corner1.z(),
                    corner2.x(), corner2.y(), corner2.z());
        }

        /** The hole to play, its cup as high as the cup block in {@code w} really is ({@link BallPhysics#cupTop}). */
        public BallPhysics.Hole physics(BallPhysics.Blocks w) {
            return BallPhysics.Hole.of(cup.x(), cup.y(), cup.z(), BallPhysics.cupTop(w, cup.x(), cup.y(), cup.z()),
                    corner1.x(), corner1.y(), corner1.z(), corner2.x(), corner2.y(), corner2.z());
        }

        /** What this hole still needs, numbered {@code n}; empty when it's ready. */
        List<String> problems(int n) {
            List<String> out = new ArrayList<>();
            if (tee == null) {
                out.add("hole " + n + " has no tee");
            }
            if (cup == null) {
                out.add("hole " + n + " has no cup");
            }
            if (!bounded()) {
                out.add("hole " + n + " needs both bound corners");
            }
            if (par < MIN_PAR || par > MAX_PAR) {
                out.add("hole " + n + "'s par must be " + MIN_PAR + "-" + MAX_PAR);
            }
            if (out.isEmpty()) {
                BallPhysics.Hole h = physics();
                if (!h.over(tee.x(), tee.z())) {
                    out.add("hole " + n + "'s tee is outside its bounds");
                }
                if (!h.over(cup.x() + 0.5, cup.z() + 0.5)) {
                    out.add("hole " + n + "'s cup is outside its bounds");
                }
            }
            return out;
        }
    }

    /** A new, empty, disabled course. */
    public static GolfCourse create(String id, String name, String world) {
        return new GolfCourse(id, name, world, false, 1, List.of(), null);
    }

    /** Why {@code id} can't be a course id as written, or {@code null} if it can. */
    public static String idProblem(String id) {
        if (id == null || !ID.matcher(id).matches()) {
            return "A course id is lower-case letters, digits and _, starting with a letter (up to 32).";
        }
        return null;
    }

    /** An id as typed, lower-cased. */
    public static String normalise(String id) {
        return id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
    }

    /** Whether Fresh Courses made it (it carries a {@code gen} tag). */
    public boolean generated() {
        return gen != null;
    }

    /** Par for the whole course. */
    public int par() {
        int t = 0;
        for (Hole h : holes) {
            t += h.par();
        }
        return t;
    }

    /** Each hole's par, in order. */
    public List<Integer> pars() {
        List<Integer> out = new ArrayList<>(holes.size());
        for (Hole h : holes) {
            out.add(h.par());
        }
        return out;
    }

    /**
     * What stops the course being played, in words; empty when it's ready.
     *
     * @param gameWorlds {@code games.worlds} (the course's world must be one)
     */
    public List<String> problems(Collection<String> gameWorlds) {
        List<String> out = new ArrayList<>();
        if (gameWorlds != null && gameWorlds.stream().noneMatch(w -> w.equalsIgnoreCase(world))) {
            out.add("its world " + world + " is not in games.worlds");
        }
        if (holes.isEmpty()) {
            out.add("it has no holes yet");
        }
        for (int i = 0; i < holes.size(); i++) {
            out.addAll(holes.get(i).problems(i + 1));
        }
        return out;
    }

    /** Enabled and ready. */
    public boolean playable(Collection<String> gameWorlds) {
        return enabled && problems(gameWorlds).isEmpty();
    }

    // ---- edits ------------------------------------------------------------------------------------

    public GolfCourse withName(String n) {
        return new GolfCourse(id, n, world, enabled, rev, holes, gen);
    }

    public GolfCourse withEnabled(boolean on) {
        return new GolfCourse(id, name, world, on, rev, holes, gen);
    }

    public GolfCourse withRev(int r) {
        return new GolfCourse(id, name, world, enabled, r, holes, gen);
    }

    /** The same course with Fresh Courses' tag ({@code null}: hand-built). */
    public GolfCourse withGen(GenTag g) {
        return new GolfCourse(id, name, world, enabled, rev, holes, g);
    }

    /** Add a hole at the end. */
    public GolfCourse addHole(Hole h) {
        List<Hole> list = new ArrayList<>(holes);
        list.add(h);
        return new GolfCourse(id, name, world, enabled, rev, list, gen);
    }

    /** Replace hole {@code n} (1-based). */
    public GolfCourse withHole(int n, Hole h) {
        List<Hole> list = new ArrayList<>(holes);
        list.set(n - 1, h);
        return new GolfCourse(id, name, world, enabled, rev, list, gen);
    }

    /** Remove hole {@code n} (1-based); the ones after it move up. */
    public GolfCourse removeHole(int n) {
        List<Hole> list = new ArrayList<>(holes);
        list.remove(n - 1);
        return new GolfCourse(id, name, world, enabled, rev, list, gen);
    }

    /** Hole {@code n} (1-based), or {@code null} if there is none. */
    public Hole hole(int n) {
        return n < 1 || n > holes.size() ? null : holes.get(n - 1);
    }
}
