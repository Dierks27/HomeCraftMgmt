package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.LegacyBoxes;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The regulars' walks (the owner: "I don't know if there is a way for them to walk in instead of just
 * disappearing"): the walkable grid read from the shipped plan joins the rooms through the archways and has every
 * regular's place on it; a path joins every two places on a floor and never goes through a block or up a step of more
 * than half a block; a walk goes at a person's pace (slower on a step); a regular walks in from the Foyer's arrival
 * spot (upstairs, from the Parlor's); and where there is no walk, there is none (the regular steps there as before).
 */
class RegularWalkTest {

    private static final Box MAIN = LegacyBoxes.clubhouse();
    private static final Box BOX = Box.sized(MAIN.minX(), MAIN.minY(), MAIN.minZ(), 32, 16, ClubhouseSettings.SIZE_Z);
    private static final ClubhouseSite SITE = ClubhousePlanner.plan(BOX);
    private static final Map<Long, String> BLOCKS = RegularSpots.blocks(SITE.plan());
    private static final RegularWays WAYS = RegularWays.of(SITE, ClubhousePlanner.Rooms.BOTH);
    private static final WalkGrid GRID = WAYS.grid();
    private static final List<RegularSpots.Spot> SPOTS = RegularSpots.of(SITE, ClubhousePlanner.Rooms.BOTH);

    private static String at(int x, int y, int z) {
        return BLOCKS.get(RegularSpots.key(x, y, z));
    }

    private static int ground() {
        return GRID.floor(WAYS.door());
    }

    @Test
    void theGridIsBuiltOncePerPlanAndQuickly() {
        assertSame(GRID, WalkGrid.of(SITE.plan(), ClubhousePlanner.keepOut(BOX)), "cached by the plan's hash (and"
                + " what is kept out of it: 0.59.0, the Party Room's Mini World)");
        assertTrue(GRID.size() > 1000, "a floor's worth of cells: " + GRID.size());
        long ms = GRID.buildNanos() / 1_000_000;
        System.out.println("walk grid: " + GRID.size() + " cells in " + ms + " ms");
        assertTrue(ms < 2_000, "built in " + ms + " ms");
    }

    @Test
    void theArchwaysJoinTheRoomsOnTheGroundFloor() {
        assertNotNull(WAYS.door(), "a front door");
        int g = ground();
        assertTrue(g >= 0);
        for (Box room : List.of(SITE.inside(), ClubhousePlanner.arcadeInside(BOX), ClubhousePlanner.trophyInside(BOX),
                ClubhousePlanner.arch(BOX, ClubhousePlanner.ARCH_ARCADE_X0),
                ClubhousePlanner.arch(BOX, ClubhousePlanner.ARCH_TROPHY_X0))) {
            boolean joined = false;
            for (WalkGrid.Cell c : GRID.cellsOf(g)) {
                joined |= room.contains(c.x(), c.y(), c.z());
            }
            assertTrue(joined, "the ground floor reaches into " + room);
        }
        // the Parlor is its own floor, reached by its way up
        assertTrue(WAYS.wayUp(), "the Parlor's way up");
        assertEquals(g, GRID.floor(WAYS.upFrom()), "the way up starts downstairs");
        assertNotEquals(g, GRID.floor(WAYS.upTo()), "and comes out on the Parlor's own floor");
        assertTrue(ClubhousePlanner.parlorInside(BOX).contains(WAYS.upTo().x(), WAYS.upTo().y(), WAYS.upTo().z()));
    }

    @Test
    void everyRegularsPlaceIsOnTheGrid() {
        for (RegularSpots.Spot s : SPOTS) {
            WalkGrid.Cell c = GRID.at(RegularWays.point(s));
            assertNotNull(c, "on the grid: " + s);
            assertEquals(s.y(), c.h(), 1e-9, "at the place's own height: " + s);
            assertEquals(s.sit(), c.seat(), "a seat is a seat cell: " + s);
            if (s.parlor()) {
                assertTrue(WAYS.upstairs(c), "a Parlor place is on the Parlor's floor: " + s);
            } else {
                assertEquals(ground(), GRID.floor(c), "a place downstairs is on the ground floor: " + s);
            }
        }
    }

    @Test
    void everyTwoPlacesOnAFloorAreJoinedByAWalkThroughNothingSolid() {
        for (boolean up : new boolean[]{false, true}) {
            List<RegularSpots.Spot> floor = new ArrayList<>();
            for (RegularSpots.Spot s : SPOTS) {
                if (s.parlor() == up) {
                    floor.add(s);
                }
            }
            assertFalse(floor.isEmpty());
            for (RegularSpots.Spot a : floor) {
                for (RegularSpots.Spot b : floor) {
                    WalkGrid.Cell ca = GRID.at(RegularWays.point(a));
                    WalkGrid.Cell cb = GRID.at(RegularWays.point(b));
                    List<WalkGrid.Cell> path = GRID.path(ca, cb, Set.of());
                    assertNotNull(path, "a walk from " + a + " to " + b);
                    checkCells(path);
                    List<RegularWalk.Leg> legs = WAYS.route(RegularWays.point(a), RegularWays.point(b), Set.of());
                    assertNotNull(legs);
                    assertEquals(1, legs.size(), "one floor, one leg");
                    checkLine(legs.getFirst());
                }
            }
        }
    }

    /** Each cell is free to stand in (never a block, the bar, Mob Races' table), each step at most half a block. */
    private static void checkCells(List<WalkGrid.Cell> path) {
        for (int i = 0; i < path.size(); i++) {
            WalkGrid.Cell c = path.get(i);
            String feet = at(c.x(), c.y(), c.z());
            String head = at(c.x(), c.y() + 1, c.z());
            assertTrue(WalkGrid.free(head), "the head free at " + c + ": " + head);
            assertTrue(WalkGrid.free(feet) || WalkGrid.carpet(feet) || (c.seat() && WalkGrid.halfStep(feet)),
                    "the feet free at " + c + ": " + feet);
            assertFalse(feet != null && ClubhousePlanner.id(feet).equals(ClubhousePlanner.TRACK_TABLE)
                    && !c.seat(), "never on Mob Races' table");
            assertTrue(i == 0 || i == path.size() - 1 || !c.seat(), "never over a seat on the way: " + c);
            if (i > 0) {
                WalkGrid.Cell p = path.get(i - 1);
                assertTrue(Math.abs(c.h() - p.h()) <= WalkGrid.STEP + 1e-9, "a step of half a block at most");
                assertTrue(Math.abs(c.x() - p.x()) <= 1 && Math.abs(c.z() - p.z()) <= 1, "neighbours");
            }
        }
    }

    /** Every point of the straightened walk, a tenth of a block apart, is over a cell, a half step at most apart. */
    private static void checkLine(RegularWalk.Leg leg) {
        List<WalkGrid.Point> ps = leg.points();
        WalkGrid.Cell was = GRID.at(ps.getFirst());
        assertNotNull(was);
        for (int i = 1; i < ps.size(); i++) {
            WalkGrid.Point a = ps.get(i - 1);
            WalkGrid.Point b = ps.get(i);
            int n = Math.max(1, (int) Math.ceil(Math.hypot(b.x() - a.x(), b.z() - a.z()) / 0.1));
            for (int s = 1; s <= n; s++) {
                double x = a.x() + (b.x() - a.x()) * s / n;
                double y = a.y() + (b.y() - a.y()) * s / n;
                double z = a.z() + (b.z() - a.z()) * s / n;
                WalkGrid.Cell c = GRID.at(x, y, z);
                assertNotNull(c, "over a cell at " + x + ", " + y + ", " + z);
                assertTrue(Math.abs(c.h() - was.h()) <= WalkGrid.STEP + 1e-9, "never up more than half a block");
                was = c;
            }
        }
    }

    @Test
    void aWalkGoesRoundAPlayerStandingInTheWay() {
        WalkGrid.Cell door = WAYS.door();
        RegularSpots.Spot bar = SPOTS.stream().filter(s -> s.kind() == RegularSpots.Kind.BAR).findFirst().orElseThrow();
        List<WalkGrid.Cell> path = GRID.path(door, GRID.at(RegularWays.point(bar)), Set.of());
        assertNotNull(path);
        WalkGrid.Cell mid = path.get(path.size() / 2);
        List<WalkGrid.Cell> round = GRID.path(door, GRID.at(RegularWays.point(bar)), Set.of(mid.key()));
        assertNotNull(round, "a way round");
        assertFalse(round.contains(mid), "not through the player");
    }

    @Test
    void aWalkInTheMiddleOfAnArchwayKeepsToItsMiddle() {
        Box arch = ClubhousePlanner.arch(BOX, ClubhousePlanner.ARCH_ARCADE_X0);
        RegularSpots.Spot arcade = SPOTS.stream().filter(s -> s.kind() == RegularSpots.Kind.ARCADE).findFirst()
                .orElseThrow();
        List<WalkGrid.Cell> path = GRID.path(WAYS.door(), GRID.at(RegularWays.point(arcade)), Set.of());
        assertNotNull(path);
        int middle = arch.minX() + ClubhousePlanner.ARCH_W / 2;
        boolean through = false;
        for (WalkGrid.Cell c : path) {
            if (arch.contains(c.x(), c.y(), c.z())) {
                assertEquals(middle, c.x(), "through the archway's middle");
                through = true;
            }
        }
        assertTrue(through, "the Arcade Room is through its archway");
    }

    @Test
    void aRegularWalksAtAPersonsPaceAndALittleSlowerOnAStep() {
        RegularWalk.Leg flat = new RegularWalk.Leg(List.of(new WalkGrid.Point(0, 0, 0), new WalkGrid.Point(0, 0, 10),
                new WalkGrid.Point(5, 0, 10)), false);
        RegularWalk w = new RegularWalk(List.of(flat), 0f);
        int ticks = w.ticks();
        double perSecond = flat.length() / (ticks / 20.0);
        assertTrue(perSecond >= 3.0 && perSecond <= 4.0, "a person's pace: " + perSecond + " blocks a second");
        // facing the way it walks: south first (yaw 0), then west... east here (+x is yaw -90)
        RegularWalk w2 = new RegularWalk(List.of(flat), 180f);
        assertEquals(0f, w2.yaw(), 1e-3, "it turns to face the way it goes");
        for (int i = 0; i < 40; i++) {
            w2.step();
        }
        assertTrue(w2.z() > 5 && Math.abs(w2.x()) < 1e-9, "on its way south");
        while (!w2.done()) {
            w2.step();
        }
        assertEquals(-90f, w2.yaw(), 1e-3, "facing east at the end");
        assertEquals(5.0, w2.x(), 1e-9);
        assertEquals(10.0, w2.z(), 1e-9);
        // a step up: slower
        RegularWalk.Leg stairs = new RegularWalk.Leg(List.of(new WalkGrid.Point(0, 0, 0),
                new WalkGrid.Point(0, 0.5, 1), new WalkGrid.Point(0, 1.0, 2), new WalkGrid.Point(0, 1.5, 3)), false);
        RegularWalk.Leg level = new RegularWalk.Leg(List.of(new WalkGrid.Point(0, 0, 0),
                new WalkGrid.Point(0, 0, 3)), false);
        int up = new RegularWalk(List.of(stairs), 0f).ticks();
        int along = new RegularWalk(List.of(level), 0f).ticks();
        assertTrue(up > along, "slower on stairs: " + up + " ticks against " + along);
        assertTrue(up <= along * 1.6, "but only a little: " + up + " against " + along);
    }

    @Test
    void aRegularWalksInFromTheFoyersArrivalSpot() {
        WalkGrid.Cell door = WAYS.door();
        boolean arrival = false;
        for (ClubhouseSite.Spot s : SITE.spawns()) {
            arrival |= GRID.at(s.x(), s.y(), s.z()) == door || door.equals(GRID.at(s.x(), s.y(), s.z()));
        }
        assertTrue(arrival, "the door is one of the Foyer's arrival spots");
        for (RegularSpots.Spot s : SPOTS) {
            List<RegularWalk.Leg> in = WAYS.walkIn(RegularWays.point(s));
            assertNotNull(in, "a walk in to " + s);
            WalkGrid.Point first = in.getFirst().points().getFirst();
            WalkGrid.Point last = in.getLast().points().getLast();
            WalkGrid.Cell start = s.parlor() ? WAYS.upTo() : door;
            assertEquals(start.cx(), first.x(), 1e-9, "from the door: " + s);
            assertEquals(start.cz(), first.z(), 1e-9, "from the door: " + s);
            assertEquals(s.x(), last.x(), 1e-9);
            assertEquals(s.y(), last.y(), 1e-9);
            assertEquals(s.z(), last.z(), 1e-9);
            assertEquals(s.parlor(), in.getFirst().upstairs(), "upstairs from the Parlor's arrival spot");
            List<RegularWalk.Leg> out = WAYS.walkOut(RegularWays.point(s), Set.of());
            assertNotNull(out, "and out again from " + s);
            WalkGrid.Point end = out.getLast().points().getLast();
            assertEquals(start.cx(), end.x(), 1e-9);
            assertEquals(start.cz(), end.z(), 1e-9);
        }
    }

    @Test
    void upAndDownstairsByTheParlorsWay() {
        RegularSpots.Spot bar = SPOTS.stream().filter(s -> s.kind() == RegularSpots.Kind.BAR).findFirst().orElseThrow();
        RegularSpots.Spot slots = SPOTS.stream().filter(s -> s.kind() == RegularSpots.Kind.SLOTS).findFirst()
                .orElseThrow();
        List<RegularWalk.Leg> up = WAYS.route(RegularWays.point(bar), RegularWays.point(slots), Set.of());
        assertNotNull(up);
        assertEquals(2, up.size(), "into the nook, out upstairs");
        assertFalse(up.get(0).upstairs());
        assertTrue(up.get(1).upstairs());
        WalkGrid.Point nook = up.get(0).points().getLast();
        assertEquals(WAYS.upFrom().cx(), nook.x(), 1e-9);
        RegularWalk w = new RegularWalk(up, 0f);
        boolean jumped = false;
        while (!w.done()) {
            w.step();
            jumped |= w.jumped();
            if (w.jumped()) {
                assertTrue(w.upstairs());
                assertEquals(WAYS.upTo().cx(), w.x(), 1e-9, "comes out at the Parlor's arrival spot");
            }
        }
        assertTrue(jumped);
        assertNotNull(WAYS.route(RegularWays.point(slots), RegularWays.point(bar), Set.of()), "and back down");
    }

    @Test
    void noWalkWhereThereIsNoneSoTheRegularStepsThereAsBefore() {
        // off the grid: in a wall, in the air
        RegularSpots.Spot bar = SPOTS.stream().filter(s -> s.kind() == RegularSpots.Kind.BAR).findFirst().orElseThrow();
        assertNull(WAYS.route(new WalkGrid.Point(BOX.minX() + 0.5, BOX.minY() + 1, BOX.minZ() + 0.5),
                RegularWays.point(bar), Set.of()), "from inside the wall: none");
        assertNull(WAYS.route(RegularWays.point(bar), new WalkGrid.Point(bar.x(), bar.y() + 5, bar.z()), Set.of()),
                "to the air: none");
        // another floor with no way up (a plan without the Parlor's way): none
        RegularWays noWay = new RegularWays(GRID, WAYS.door(), null, null);
        RegularSpots.Spot slots = SPOTS.stream().filter(s -> s.kind() == RegularSpots.Kind.SLOTS).findFirst()
                .orElseThrow();
        assertFalse(noWay.wayUp());
        assertNull(noWay.route(RegularWays.point(bar), RegularWays.point(slots), Set.of()), "upstairs: none");
        assertNull(noWay.walkIn(RegularWays.point(slots)), "nor a walk in to it: it is put there");
        // two rooms with no way between them: none
        Box box = Box.of(0, 0, 0, 12, 4, 4);
        Map<Long, String> b = new HashMap<>();
        for (int x = 0; x <= 12; x++) {
            for (int z = 0; z <= 4; z++) {
                b.put(RegularSpots.key(x, 0, z), ClubhousePlanner.PLANKS);
                if (x == 6 || z == 0 || z == 4 || x == 0 || x == 12) {
                    for (int y = 1; y <= 3; y++) {
                        b.put(RegularSpots.key(x, y, z), ClubhousePlanner.PLANKS);
                    }
                }
            }
        }
        WalkGrid walled = WalkGrid.build(b, box);
        WalkGrid.Cell west = walled.cell(3, 1, 2);
        WalkGrid.Cell east = walled.cell(9, 1, 2);
        assertNotNull(west);
        assertNotNull(east);
        assertNotEquals(walled.floor(west), walled.floor(east), "two floors");
        assertNull(walled.path(west, east, Set.of()), "no walk through the wall");
        // the wall opened: a walk, through the gap
        b.remove(RegularSpots.key(6, 1, 2));
        b.remove(RegularSpots.key(6, 2, 2));
        WalkGrid opened = WalkGrid.build(b, box);
        List<WalkGrid.Cell> through = opened.path(opened.cell(3, 1, 2), opened.cell(9, 1, 2), Set.of());
        assertNotNull(through);
        Set<Integer> xs = new HashSet<>();
        for (WalkGrid.Cell c : through) {
            xs.add(c.x());
            if (c.x() == 6) {
                assertEquals(2, c.z(), "through the gap");
            }
        }
        assertTrue(xs.contains(6));
        // a player standing in the only gap: none (it waits, or steps there after a while)
        assertNull(opened.path(opened.cell(3, 1, 2), opened.cell(9, 1, 2), Set.of(opened.cell(6, 1, 2).key())));
    }

    @Test
    void aStepOfMoreThanHalfABlockIsNoStep() {
        Box box = Box.of(0, 0, 0, 4, 4, 2);
        Map<Long, String> b = new HashMap<>();
        for (int x = 0; x <= 4; x++) {
            for (int z = 0; z <= 2; z++) {
                b.put(RegularSpots.key(x, 0, z), ClubhousePlanner.PLANKS);
            }
        }
        b.put(RegularSpots.key(2, 1, 1), ClubhousePlanner.PLANKS); // a whole block up
        b.put(RegularSpots.key(2, 1, 0), ClubhousePlanner.PLANKS);
        b.put(RegularSpots.key(2, 1, 2), ClubhousePlanner.PLANKS);
        WalkGrid g = WalkGrid.build(b, box);
        assertNull(g.path(g.cell(0, 1, 1), g.cell(4, 1, 1), Set.of()), "a whole block is a wall to a walker");
        b.put(RegularSpots.key(1, 1, 1), ClubhousePlanner.STOOL); // a stool before it: still no climbing over seats
        WalkGrid g2 = WalkGrid.build(b, box);
        assertNull(g2.path(g2.cell(0, 1, 1), g2.cell(4, 1, 1), Set.of()), "never up a stool onto the bar");
        assertNotNull(g2.path(g2.cell(0, 1, 1), g2.cell(1, 1, 1), Set.of()), "but onto the stool to sit");
        assertNull(g2.path(g2.cell(1, 1, 1), g2.cell(2, 2, 1), Set.of()), "and never up off it");
    }
}
