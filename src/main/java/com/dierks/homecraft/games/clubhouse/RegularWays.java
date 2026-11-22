package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.gen.api.Box;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The regulars' ways about the generated Clubhouse (pure): the {@link WalkGrid} of its plan, its front door (the
 * Foyer's arrival spot a regular walks in at and out by), and the Game Parlor's way up (the nook's spot by the secret
 * book downstairs and the Parlor's arrival spot upstairs: a regular going up walks into the nook and comes out
 * upstairs, as a player does). Every place comes from the planner; nothing here knows a coordinate.
 *
 * <p>A walk the grid can't make (a place off the grid, another floor with no way up) is {@code null}: the regular
 * steps there as it always has.
 */
final class RegularWays {

    private final WalkGrid grid;
    private final WalkGrid.Cell door;
    /** The Parlor's way up: the cell downstairs and the cell upstairs ({@code null}: no Parlor). */
    private final WalkGrid.Cell upFrom;
    private final WalkGrid.Cell upTo;

    RegularWays(WalkGrid grid, WalkGrid.Cell door, WalkGrid.Cell upFrom, WalkGrid.Cell upTo) {
        this.grid = grid;
        this.door = door;
        boolean way = upFrom != null && upTo != null && grid.floor(upFrom) >= 0 && grid.floor(upTo) >= 0
                && grid.floor(upFrom) != grid.floor(upTo);
        this.upFrom = way ? upFrom : null;
        this.upTo = way ? upTo : null;
    }

    /** The ways of {@code site} with {@code rooms} built (the grid is the plan's, built once per plan). */
    static RegularWays of(ClubhouseSite site, ClubhousePlanner.Rooms rooms) {
        WalkGrid g = WalkGrid.of(site.plan());
        Box box = site.box();
        WalkGrid.Cell up = null;
        WalkGrid.Cell from = null;
        if (rooms.parlor()) {
            ClubhouseSite.Spot a = ClubhousePlanner.parlorArrival(box);
            ClubhouseSite.Spot r = ClubhousePlanner.parlorReturn(box);
            up = g.at(a.x(), a.y(), a.z());
            from = g.at(r.x(), r.y(), r.z());
        }
        return new RegularWays(g, door(g, site.spawns()), from, up);
    }

    /**
     * The front door: of the Foyer's arrival spots on the grid, the one at the back of the Foyer nearest its middle
     * (the furthest from the podium), on the floor with the most cells.
     */
    static WalkGrid.Cell door(WalkGrid g, List<ClubhouseSite.Spot> spawns) {
        WalkGrid.Cell best = null;
        double mid = 0;
        for (ClubhouseSite.Spot s : spawns) {
            mid += s.x() / spawns.size();
        }
        ClubhouseSite.Spot bestSpot = null;
        for (ClubhouseSite.Spot s : spawns) {
            WalkGrid.Cell c = g.at(s.x(), s.y(), s.z());
            if (c == null || g.floor(c) < 0 || c.seat()) {
                continue;
            }
            if (best == null || s.z() > bestSpot.z() + 1e-9
                    || (Math.abs(s.z() - bestSpot.z()) < 1e-9 && Math.abs(s.x() - mid) < Math.abs(bestSpot.x() - mid) - 1e-9)) {
                best = c;
                bestSpot = s;
            }
        }
        return best;
    }

    WalkGrid grid() {
        return grid;
    }

    WalkGrid.Cell door() {
        return door;
    }

    /** Whether the Parlor's way up is there. */
    boolean wayUp() {
        return upFrom != null;
    }

    WalkGrid.Cell upFrom() {
        return upFrom;
    }

    WalkGrid.Cell upTo() {
        return upTo;
    }

    boolean upstairs(WalkGrid.Cell c) {
        return upTo != null && c != null && grid.floor(c) == grid.floor(upTo);
    }

    /**
     * The walk from {@code from} to the place {@code to} (its exact feet), round anyone in {@code avoid}; up or down
     * the Parlor's way when they are on its two floors. {@code null}: no walk (it steps there instead).
     */
    List<RegularWalk.Leg> route(WalkGrid.Point from, WalkGrid.Point to, Set<Long> avoid) {
        WalkGrid.Cell a = grid.at(from);
        WalkGrid.Cell b = grid.at(to);
        if (a == null || b == null || grid.floor(a) < 0 || grid.floor(b) < 0) {
            return null;
        }
        if (grid.floor(a) == grid.floor(b)) {
            RegularWalk.Leg leg = leg(from, a, to, b, avoid);
            return leg == null ? null : List.of(leg);
        }
        if (upFrom == null) {
            return null;
        }
        WalkGrid.Cell out;
        WalkGrid.Cell in;
        if (grid.floor(a) == grid.floor(upFrom) && grid.floor(b) == grid.floor(upTo)) {
            out = upFrom;
            in = upTo;
        } else if (grid.floor(a) == grid.floor(upTo) && grid.floor(b) == grid.floor(upFrom)) {
            out = upTo;
            in = upFrom;
        } else {
            return null;
        }
        RegularWalk.Leg first = leg(from, a, centre(out), out, avoid);
        RegularWalk.Leg second = leg(centre(in), in, to, b, avoid);
        return first == null || second == null ? null : List.of(first, second);
    }

    /** The walk in at the door (or, for a place upstairs, from the Parlor's arrival spot) to {@code to}. */
    List<RegularWalk.Leg> walkIn(WalkGrid.Point to) {
        WalkGrid.Cell b = grid.at(to);
        WalkGrid.Cell start = b != null && upstairs(b) ? upTo : door;
        return start == null ? null : route(centre(start), to, null);
    }

    /** The walk out from {@code from} to the door (upstairs: to the Parlor's way out), where it is taken down. */
    List<RegularWalk.Leg> walkOut(WalkGrid.Point from, Set<Long> avoid) {
        WalkGrid.Cell a = grid.at(from);
        WalkGrid.Cell end = a != null && upstairs(a) ? upTo : door;
        return end == null ? null : route(from, centre(end), avoid);
    }

    /** Where the walk in at {@code to} starts (for which floor's door it waits its turn at). */
    WalkGrid.Cell startFor(WalkGrid.Point to) {
        WalkGrid.Cell b = grid.at(to);
        return b != null && upstairs(b) ? upTo : door;
    }

    private RegularWalk.Leg leg(WalkGrid.Point from, WalkGrid.Cell a, WalkGrid.Point to, WalkGrid.Cell b,
                                Set<Long> avoid) {
        List<WalkGrid.Cell> cells = grid.path(a, b, avoid);
        if (cells == null) {
            return null;
        }
        List<WalkGrid.Point> pts = new ArrayList<>(grid.smooth(cells, avoid));
        pts.set(0, new WalkGrid.Point(from.x(), a.h(), from.z()));
        if (pts.size() == 1) {
            pts.add(new WalkGrid.Point(to.x(), b.h(), to.z()));
        } else {
            pts.set(pts.size() - 1, new WalkGrid.Point(to.x(), b.h(), to.z()));
        }
        return new RegularWalk.Leg(pts, upstairs(a));
    }

    static WalkGrid.Point centre(WalkGrid.Cell c) {
        return new WalkGrid.Point(c.cx(), c.h(), c.cz());
    }

    static WalkGrid.Point point(RegularSpots.Spot s) {
        return new WalkGrid.Point(s.x(), s.y(), s.z());
    }
}
