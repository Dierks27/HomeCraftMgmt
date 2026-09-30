package com.dierks.homecraft.command;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Sight;

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
 * config puts them, each kept course's stored plot, the Clubhouse's and Falling Floors' boxes, and
 * the world's spawn and {@code games.fresh.safe_spot} as points (reach 0). Moving a place is done by
 * hand (there is no automatic move): the fix says how for each kind.
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
        SAFE_SPOT
    }

    /**
     * One place.
     *
     * @param id   the slot id, the plot number, or the extra's name
     * @param name what an admin reads: "Parkour", "the Clubhouse", "the kept course "cliff_hop""
     * @param box  its blocks (a point is a 1 x 1 x 1 box)
     */
    public record Place(Kind kind, String id, String name, Box box) {

        /** How far outside it a player can be: none for a point, {@link Sight#REACH} for a place the games build. */
        public int reach() {
            return point() ? 0 : Sight.REACH;
        }

        boolean point() {
            return kind == Kind.SPAWN || kind == Kind.SAFE_SPOT;
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
        List<Sight.Pair> pairs = Sight.pairs(spots, view);
        if (pairs.isEmpty()) {
            Sight.Pair near = Sight.nearest(spots);
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

    /** Which of two places to suggest moving: a course first (its clear is clean), a kept course last. */
    private static int rank(Place p) {
        return switch (p.kind()) {
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
