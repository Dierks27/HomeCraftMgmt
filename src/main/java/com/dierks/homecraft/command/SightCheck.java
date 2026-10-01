package com.dierks.homecraft.command;

import com.dierks.homecraft.games.event.RaceTrack;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Sight;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.Point;
import com.dierks.homecraft.storage.EventDao;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * "What players can see" in {@code /hcm games check} (LAYOUT-SPEC §5.1, LAYOUT-VOID-ADDENDUM item 3),
 * as pure rules and words: whether each Games world is void and has something under its spawn, the
 * view distance the server really uses, and every pair of the games' places close enough for a
 * player on one to see the other ({@link Sight}).
 *
 * <p><b>WARN, never FAIL.</b> A course that can see another still works; it only looks odd ("the
 * other stuff floating"). So nothing here closes anything, and nothing here is one of an area's
 * {@code problems} (those are FAILs that say "move it").
 *
 * <p>The places are what stands or will stand: each Fresh course's and Classic's two halves where its
 * config puts them, each kept course's stored plot, the Clubhouse's and Falling Floors' boxes, the
 * Race Night stand an admin set for a kept or hand-built course (a point with a spectator's reach:
 * it can be set anywhere, LAYOUT-SPEC §1.1), and the world's spawn and {@code games.fresh.safe_spot} as
 * points (reach 0). Moving a place is done by hand (there is no automatic move): the fix says how for
 * each kind.
 *
 * <p>Only what the games build is worth a WARN: a pair of two points (the spawn, the safe spot, a
 * stand) has nothing built at either end, so it is never listed, nor named as the closest two places;
 * nor is a stand and its own kept course, which it is set beside on purpose.
 */
public final class SightCheck {

    /** At most this many pairs get a line of their own; the rest are counted in one more line. */
    static final int MAX_PAIRS = 8;

    /** What a place is, which decides its words and how it is moved. */
    public enum Kind {
        /** A Fresh course's half ({@code id}: the slot). */
        SLOT,
        /** A Classics slot's half ({@code id}: the slot). */
        CLASSIC,
        /** A kept course's plot ({@code id}: the plot number). */
        KEPT,
        CLUBHOUSE,
        ARENA,
        /** The Games world's spawn: a point. */
        SPAWN,
        /** {@code games.fresh.safe_spot}: a point. */
        SAFE_SPOT,
        /**
         * The Race Night stand an admin set for a kept or hand-built course ({@code race.stand.<course>};
         * {@code id}: the course): a point where spectators stand, with a spectator's reach.
         */
        STAND
    }

    /**
     * One place.
     *
     * @param id     the slot id, the plot number, the extra's name, or a stand's course
     * @param name   what an admin reads: "Parkour", "the Clubhouse", "the kept course "cliff_hop""
     * @param box    its blocks (a point is a 1 x 1 x 1 box)
     * @param course the course a kept plot holds or a stand belongs to, or {@code null}
     */
    public record Place(Kind kind, String id, String name, Box box, String course) {

        /** A place that belongs to no course. */
        public Place(Kind kind, String id, String name, Box box) {
            this(kind, id, name, box, null);
        }

        /**
         * How far outside it a player can be: none at the spawn or the safe spot, {@link Sight#REACH} for a
         * place the games build and for a stand (spectators move about on it).
         */
        public int reach() {
            return kind == Kind.SPAWN || kind == Kind.SAFE_SPOT ? 0 : Sight.REACH;
        }

        /** A spot where players are put and nothing is built: the spawn, the safe spot, a stand. */
        boolean point() {
            return kind == Kind.SPAWN || kind == Kind.SAFE_SPOT || kind == Kind.STAND;
        }

        boolean half() {
            return kind == Kind.SLOT || kind == Kind.CLASSIC;
        }
    }

    /**
     * A Games world's ground.
     *
     * @param loaded      it is loaded (an unloaded one is already a FAIL of its own)
     * @param isVoid      its generator is this plugin's void one
     * @param spawnFooted there is a block somewhere under its spawn; {@code null} when it can't be told
     */
    public record Ground(String world, boolean loaded, boolean isVoid, Boolean spawnFooted) {
    }

    /**
     * Everything the section reads.
     *
     * @param world      the world the games build in ({@code ""}: none)
     * @param view       the view distance used: the largest of the world's view and send distances and
     *                   every online player's send distance there
     * @param viewWhy    where it came from, for admins
     * @param places     every place in {@code world}
     * @param grounds    each Games world
     * @param keptSince  the day the layout check kept 0.35's spots and shapes because this server had built
     *                   there ({@code LayoutGuard}), or {@code null} (the new layout)
     */
    public record Facts(String world, int view, String viewWhy, List<Place> places, List<Ground> grounds,
                        String keptSince) {

        public Facts {
            places = places == null ? List.of() : List.copyOf(places);
            grounds = grounds == null ? List.of() : List.copyOf(grounds);
        }

        /** On the new layout. */
        public Facts(String world, int view, String viewWhy, List<Place> places, List<Ground> grounds) {
            this(world, view, viewWhy, places, grounds, null);
        }
    }

    private SightCheck() {
    }

    /** The section's lines: each Games world's ground, then the view distance and the pairs in sight. */
    static void rows(Facts f, List<GamesCheck.Line> out) {
        if (f == null) {
            return;
        }
        for (Ground g : f.grounds()) {
            if (!g.loaded()) {
                continue;
            }
            out.add(GamesCheck.Line.ok(g.isVoid() ? "Games world '" + g.world() + "' is void (nothing below the courses)"
                    : "Games world '" + g.world() + "' isn't a void world: you'll see the ground far below the courses."
                    + " See README 'A void world'"));
            if (Boolean.FALSE.equals(g.spawnFooted())) {
                out.add(GamesCheck.Line.warn("The spawn of '" + g.world() + "' has nothing under it - someone arriving"
                        + " there would fall", "stand at the spawn and put a block under it, or make the world with /mv"
                        + " create <name> normal -g HomeCraftManagement, which adds a platform at its spawn"));
            }
        }
        if (f.keptSince() != null) {
            out.add(GamesCheck.Line.ok("This server had built Games places before the update, so on " + f.keptSince()
                    + " every one kept its 0.35 spot and shape (half_gap: 32, keep.plot_gap: 0). README \"Moving an"
                    + " area by hand\" moves one to its new spot, out of sight"));
        }
        if (f.world() == null || f.world().isBlank()) {
            return; // Fresh Courses' own section says there is no world
        }
        int view = f.view();
        out.add(GamesCheck.Line.ok("View distance used: " + view + " (" + f.viewWhy() + ")"));
        Map<Sight.Spot, Place> of = new IdentityHashMap<>();
        List<Sight.Spot> spots = new ArrayList<>();
        for (Place p : f.places()) {
            Sight.Spot s = new Sight.Spot(p.name(), f.world(), p.box(), p.reach());
            of.put(s, p);
            spots.add(s);
        }
        List<Sight.Pair> every = new ArrayList<>();
        for (Sight.Pair p : Sight.every(spots)) {
            if (counts(of.get(p.a()), of.get(p.b()))) {
                every.add(p);
            }
        }
        List<Sight.Pair> pairs = new ArrayList<>();
        for (Sight.Pair p : every) {
            if (Sight.within(p.chunks(), view)) {
                pairs.add(p);
            }
        }
        if (pairs.isEmpty()) {
            Sight.Pair near = every.isEmpty() ? null : every.get(0);
            out.add(GamesCheck.Line.ok("Nothing else built by the games can be seen from any course, the Clubhouse or"
                    + " the arena (view distance " + view + (near == null ? ")" : "; the closest two places are "
                    + near.chunks() + " chunks apart, clear up to view distance " + near.clearUpTo() + ")")));
            return;
        }
        for (int i = 0; i < pairs.size() && i < MAX_PAIRS; i++) {
            Sight.Pair p = pairs.get(i);
            out.add(line(of.get(p.a()), of.get(p.b()), p.chunks(), view));
        }
        if (pairs.size() > MAX_PAIRS) {
            int more = pairs.size() - MAX_PAIRS;
            out.add(GamesCheck.Line.warn("...and " + more + " more pair" + (more == 1 ? "" : "s")
                    + " of places that can see each other", "the same fixes; run the check again after the ones above"));
        }
    }

    /**
     * Whether a pair is worth a line: something the games build at one end at least (two points hold
     * nothing built), and not a stand beside its own kept course.
     */
    static boolean counts(Place a, Place b) {
        if (a.point() && b.point()) {
            return false;
        }
        return !(a.course() != null && a.course().equalsIgnoreCase(b.course() == null ? "" : b.course())
                && (a.kind() == Kind.STAND || b.kind() == Kind.STAND));
    }

    /**
     * The Race Night stands an admin set ({@code race.stand.<course>}, {@code meta}) for the kept and
     * hand-built courses in {@code world}: each a point with a spectator's reach. A stand set for another
     * layout of its course (dropped when it is next used), one of a Fresh course (built in, inside its
     * half) or of a course that is gone isn't one.
     *
     * @param courses every time-trial course by id
     */
    static List<Place> stands(Map<String, String> meta, Map<String, Course> courses, String world) {
        List<Place> out = new ArrayList<>();
        String prefix = EventDao.META + "stand.";
        for (Map.Entry<String, String> e : meta.entrySet()) {
            if (!e.getKey().startsWith(prefix)) {
                continue;
            }
            String id = e.getKey().substring(prefix.length());
            Course c = courses.get(id);
            if (c == null || c.generated() || c.world() == null || !c.world().equalsIgnoreCase(world)) {
                continue;
            }
            Point p = RaceTrack.decodeStand(e.getValue(), c.layoutHash());
            if (p == null) {
                continue;
            }
            int x = (int) Math.floor(p.x());
            int y = (int) Math.floor(p.y());
            int z = (int) Math.floor(p.z());
            out.add(new Place(Kind.STAND, c.id(), "the Race Night stand of \"" + c.name() + "\"",
                    new Box(x, y, z, x, y, z), c.id()));
        }
        return out;
    }

    /** One pair in sight, {@code chunks} apart at view distance {@code view}. */
    static GamesCheck.Line line(Place a, Place b, int chunks, int view) {
        if (b.point() && !a.point()) {
            Place t = a; // a point is where players stand: it is the one that sees
            a = b;
            b = t;
        }
        String away = " (" + chunks + " chunk" + (chunks == 1 ? "" : "s") + " away; view distance " + view + ")";
        int clear = chunks - 2;
        String lower = clear >= 2 ? ", or lower view-distance to " + clear : "";
        if (a.half() && b.half() && a.kind() == b.kind() && a.id().equals(b.id())) {
            return GamesCheck.Line.warn("From " + a.name() + ", players can see its own spare half, where the next"
                    + " course is built" + away, "move it by hand to a spot with " + Sight.GAP + " free blocks along x: "
                    + clearFirst(a) + ", then set half_gap: " + Sight.GAP + " under " + section(a) + lower);
        }
        return GamesCheck.Line.warn("From " + a.name() + ", players can see " + b.name() + away, move(a, b) + lower);
    }

    /** How to move one of the two out of the other's sight: the one that moves most simply. */
    private static String move(Place a, Place b) {
        Place m = rank(b) < rank(a) ? b : a;
        Place other = m == a ? b : a;
        String apart = " (" + Sight.GAP + " blocks from everything else keeps it out of sight)";
        return switch (m.kind()) {
            case STAND -> "set the stand nearer its course: /hcm games event stand " + m.id() + " set";
            case SLOT, CLASSIC -> "move " + m.name() + " by hand: " + clearFirst(m) + ", then change " + section(m)
                    + ".origin" + apart;
            case CLUBHOUSE -> "move the Clubhouse with games.clubhouse.origin (the old room's blocks stay where they"
                    + " are)" + apart;
            case ARENA -> "move Falling Floors with games.falling_floors.origin (the old arena's blocks stay where they"
                    + " are)" + apart;
            case SPAWN -> "move the world's spawn away from " + other.name() + " (/setworldspawn)";
            case SAFE_SPOT -> "move games.fresh.safe_spot away from " + other.name();
            case KEPT -> "a kept course stays where it was kept; set games.fresh.keep.plot_gap: " + Sight.GAP
                    + " so the courses kept from now on stand out of each other's sight";
        };
    }

    /**
     * Which of two places to suggest moving: a stand first (one command, nothing is built there), then a
     * course (its clear is clean), a kept course last.
     */
    private static int rank(Place p) {
        return switch (p.kind()) {
            case STAND -> -1;
            case SLOT, CLASSIC -> 0;
            case CLUBHOUSE, ARENA -> 1;
            case SPAWN, SAFE_SPOT -> 2;
            case KEPT -> 3;
        };
    }

    /**
     * How to empty a course before it moves: {@code clear} for a Fresh course (both halves emptied, water
     * first, its claim given up); a Classic isn't cleared but closed ({@code unrecall}, which empties
     * both halves once nobody is on them).
     */
    private static String clearFirst(Place p) {
        return p.kind() == Kind.CLASSIC ? "/hcm games gen unrecall " + p.id() + " confirm (wait until its halves"
                + " are empty)" : "/hcm games gen clear " + p.id() + " confirm";
    }

    private static String section(Place p) {
        return (p.kind() == Kind.CLASSIC ? "games.fresh.classics.slots." : "games.fresh.slots.") + p.id();
    }
}
