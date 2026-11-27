package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import org.bukkit.Material;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plan 15 (0.59.0), the Kids' Party Room in the grown wing (the owner: "The trophy room is massive now and very empty",
 * and their daughter: "maybe a trampoline in there, and a place you can turn mini and run around in a maze or mini
 * world? Or a mini dollhouse"): a dance floor kept open for party games, a trampoline that bounces, the Mini World
 * (hedged in, two little doorways, a maze with a prize at its middle, a doll's house of two floors) and every cup at the
 * far end; and the live maths of the bounce, the size and the ball pit's balls.
 *
 * <p>Where a body can be is worked out here from the blocks' own shapes ({@link #surfaces}): every top a body could stand
 * on and how much room is over it. A body of full size needs 1.8 blocks, a small one ({@link PartyRoom#MINI}) 0.54;
 * walking climbs a step of 0.6 at most and a hop a block and a quarter. Jumping and stepping are the same at any size
 * (26.2 reads both attributes as they are).
 */
class PartyRoomTest {

    private static final ClubhouseSettings SETTINGS = ClubhouseSettings.defaults();
    private static final Box BOX = SETTINGS.box();
    private static final Box OLD = SETTINGS.oldBox();
    private static final ClubhouseSite SITE = ClubhousePlanner.plan(BOX);
    private static final Map<Long, String> B = RegularSpots.blocks(SITE.plan());
    private static final Map<Long, Integer> LIGHT = light();
    private static final int FLOOR = BOX.minY();
    private static final int FEET = FLOOR + 1;
    private static final double TALL = 1.8;
    private static final double SMALL = TALL * PartyRoom.MINI;
    private static final double STEP = 0.6;
    private static final double HOP = 1.25;
    private static final Box ROOM = ClubhousePlanner.trophyInside(BOX);
    private static final Box MINI = ClubhousePlanner.miniWorld(BOX);

    private static long key(int x, int y, int z) {
        return RegularSpots.key(x, y, z);
    }

    private static String at(int x, int y, int z) {
        return B.get(key(x, y, z));
    }

    /** The block at box offset (i, j, k). */
    private static String rel(int i, int j, int k) {
        return at(BOX.minX() + i, BOX.minY() + j, BOX.minZ() + k);
    }

    private static Map<Long, Integer> light() {
        Map<Long, Integer> light = new HashMap<>();
        ArrayDeque<int[]> q = new ArrayDeque<>();
        for (BlockOp op : SITE.plan().ops()) {
            int l = ClubhousePlanner.lightOf(SITE.plan().blockOf(op));
            if (l > 0) {
                light.put(key(op.x(), op.y(), op.z()), l);
                q.add(new int[]{op.x(), op.y(), op.z(), l});
            }
        }
        int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (!q.isEmpty()) {
            int[] c = q.poll();
            if (light.getOrDefault(key(c[0], c[1], c[2]), 0) > c[3] || c[3] <= 1) {
                continue;
            }
            for (int[] d : dirs) {
                int x = c[0] + d[0];
                int y = c[1] + d[1];
                int z = c[2] + d[2];
                String n = B.get(key(x, y, z));
                if (!BOX.contains(x, y, z) || (n != null && !ClubhousePlanner.letsLight(n))) {
                    continue;
                }
                if (light.getOrDefault(key(x, y, z), 0) < c[3] - 1) {
                    light.put(key(x, y, z), c[3] - 1);
                    q.add(new int[]{x, y, z, c[3] - 1});
                }
            }
        }
        return light;
    }

    // ---- where a body can be ---------------------------------------------------------------------------------------

    /** Nothing to bump into: air, the invisible light, a banner, an open trapdoor (an armchair's thin arm). */
    private static boolean passable(String b) {
        if (b == null) {
            return true;
        }
        String id = ClubhousePlanner.id(b);
        return id.equals("minecraft:light") || id.endsWith("_banner") || (id.endsWith("_trapdoor") && b.contains("open=true"));
    }

    /** How high the top of what is in a block is (a carpet a sixteenth, a bottom slab a half, a fence 1.5, else 1). */
    private static double top(String b) {
        if (passable(b)) {
            return 0;
        }
        String id = ClubhousePlanner.id(b);
        if (id.endsWith("_carpet")) {
            return 1.0 / 16;
        }
        if (id.endsWith("_slab") && b.contains("type=bottom")) {
            return 0.5;
        }
        return id.endsWith("_fence") ? 1.5 : 1;
    }

    /** A top to stand on: its column, its height, and the room over it (to the next thing above, or the box's top). */
    record Surface(int x, int z, double h, double room) {
    }

    /** Every top in the column (x, z) from the floor up to the side rooms' roof, and the room over each. */
    private static List<Surface> column(int x, int z) {
        List<Surface> out = new ArrayList<>();
        for (int y = FLOOR; y <= FLOOR + ClubhousePlanner.SIDE_TOP; y++) {
            String b = at(x, y, z);
            double t = top(b);
            if (t <= 0) {
                continue;
            }
            double h = y + t;
            double over = FLOOR + ClubhousePlanner.SIDE_ROOF + 1 - h;
            for (int y2 = (int) Math.floor(h + 1e-9); y2 <= FLOOR + ClubhousePlanner.SIDE_ROOF; y2++) {
                if (y2 == y) {
                    continue; // the rest of its own block (over a carpet, a slab)
                }
                if (!passable(at(x, y2, z))) {
                    over = y2 - h;
                    break;
                }
            }
            if (over > 1e-9) {
                out.add(new Surface(x, z, h, over));
            }
        }
        return out;
    }

    private static Map<Long, List<Surface>> surfaces(Box area) {
        Map<Long, List<Surface>> out = new HashMap<>();
        for (int x = area.minX(); x <= area.maxX(); x++) {
            for (int z = area.minZ(); z <= area.maxZ(); z++) {
                out.put(key(x, 0, z), column(x, z));
            }
        }
        return out;
    }

    /** Every top a full-size body walks to from {@code from} (steps of {@value #STEP}, any drop it fits through). */
    private static Set<Surface> walked(Map<Long, List<Surface>> all, Surface from) {
        return reached(all, from, TALL, STEP);
    }

    /**
     * Every top a body {@code body} high gets to from {@code from}, climbing at most {@code rise} at a time: a move onto
     * the next column's top is only where the body fits over it from the height it comes from (nobody walks through
     * the wall over a lower floor), and a hop only with room over the top it hops from.
     */
    /** How each top was last reached (a failure's message says the way there). */
    private static final Map<Surface, Surface> CAME = new HashMap<>();

    private static String way(Surface s) {
        StringBuilder b = new StringBuilder();
        for (Surface c = s; c != null && b.length() < 1500; c = CAME.get(c)) {
            b.append(where(c.x(), c.z())).append('@').append(c.h() - FLOOR).append(" < ");
        }
        return b.toString();
    }

    private static Set<Surface> reached(Map<Long, List<Surface>> all, Surface from, double body, double rise) {
        Set<Surface> seen = new HashSet<>();
        ArrayDeque<Surface> q = new ArrayDeque<>();
        seen.add(from);
        q.add(from);
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!q.isEmpty()) {
            Surface s = q.poll();
            for (int[] d : dirs) {
                for (Surface n : all.getOrDefault(key(s.x() + d[0], 0, s.z() + d[1]), List.of())) {
                    double up = n.h() - s.h();
                    boolean fits = n.room() >= Math.max(0, -up) + body && s.room() >= Math.max(0, up) + body;
                    // a stair has a half step in front: a body walks up a block onto one
                    String b = at(n.x(), (int) Math.round(n.h()) - 1, n.z());
                    double climb = b != null && b.contains("_stairs") && b.contains("half=bottom") ? Math.max(rise, 1.0)
                            : rise;
                    if (fits && up <= climb + 1e-9 && seen.add(n)) {
                        q.add(n);
                        CAME.put(n, s);
                    }
                }
            }
        }
        return seen;
    }

    /** The block a body standing on {@code s} has its feet in (a carpet's own block; over a slab, the next one up). */
    private static int feet(Surface s) {
        int y = (int) Math.floor(s.h() + 1e-9);
        String b = at(s.x(), y, s.z());
        return b == null || passable(b) || ClubhousePlanner.id(b).endsWith("_carpet") ? y : y + 1;
    }

    private static Surface floorAt(Map<Long, List<Surface>> all, int x, int z, double near) {
        for (Surface s : all.get(key(x, 0, z))) {
            if (Math.abs(s.h() - near) < 0.2) {
                return s;
            }
        }
        throw new AssertionError("no floor at " + (x - BOX.minX()) + "," + (z - BOX.minZ()) + " near " + near);
    }

    private static String where(int x, int z) {
        return (x - BOX.minX()) + "," + (z - BOX.minZ());
    }

    // ---- the room --------------------------------------------------------------------------------------------------

    @Test
    void theGrownWingsTrophyRoomIsThePartyRoomAndAWingAtItsOldSizeKeepsItsTrophyRoom() {
        assertTrue(SITE.plan().summary().get(1).contains("Kids' Party Room"), SITE.plan().summary().toString());
        ClubhouseSite old = ClubhousePlanner.plan(OLD);
        assertTrue(old.plan().summary().get(1).contains("the Trophy Room"));
        assertEquals(ClubhousePlannerTest.OLD_HASH, ClubhousePlanner.plan(ClubhousePlannerTest.OLD).plan().hash(),
                "a wing at its old size is plan 12's room, block for block");
        assertTrue(ClubhousePlanner.trophySeats(OLD).isEmpty(), "no seats in the old Trophy Room");
        for (Box b : List.of(ClubhousePlanner.miniWorld(BOX), ClubhousePlanner.trampoline(BOX))) {
            assertTrue(ROOM.contains(b.minX(), FEET, b.minZ()) && ROOM.contains(b.maxX(), FEET, b.maxZ()), "in the room: " + b);
        }
        assertTrue(ClubhousePlanner.keepOut(OLD).isEmpty(), "nothing kept from the regulars at the old size");
        // the main room and the Arcade Room are what they were (GrownWingTest and ClubhousePlannerTest pin them)
        Map<Long, String> arcadeOld = RegularSpots.blocks(ClubhousePlanner.plan(BOX, new ClubhousePlanner.Rooms(true,
                false)).plan());
        for (int i = 0; i <= 15; i++) {
            for (int j = 0; j <= ClubhousePlanner.SIDE_ROOF; j++) {
                for (int k = ClubhousePlanner.MAIN_Z; k < BOX.sizeZ(); k++) {
                    long p = key(BOX.minX() + i, BOX.minY() + j, BOX.minZ() + k);
                    assertEquals(arcadeOld.get(p), B.get(p), "the Arcade Room is its own");
                }
            }
        }
    }

    /**
     * The dance floor: light-up tiles and coloured ones set in the floor, nothing standing on any of it, every tile walked
     * on from the archway; balloons at its corners and bunting over it.
     */
    @Test
    void theDanceFloorIsOpenLitAndLightsUp() {
        Map<Long, List<Surface>> all = surfaces(ROOM);
        Surface in = floorAt(all, BOX.minX() + ClubhousePlanner.ARCH_TROPHY_X0 + 1, ROOM.minZ(), FEET);
        Set<Surface> walked = walked(all, in);
        int lit = 0;
        int tiles = 0;
        for (int i = ClubhousePlanner.DANCE_X0; i <= ClubhousePlanner.DANCE_X1; i++) {
            for (int k = ClubhousePlanner.DANCE_Z0; k <= ClubhousePlanner.DANCE_Z1; k++) {
                String tile = rel(i, 0, k);
                assertTrue(tile.endsWith("_concrete") || tile.contains("froglight"), "a tile: " + tile);
                lit += ClubhousePlanner.lightOf(tile) == 15 ? 1 : 0;
                tiles++;
                for (int j = 1; j <= 3; j++) {
                    assertNull(rel(i, j, k), "nothing on the dance floor at " + i + "," + j + "," + k);
                }
                assertTrue(walked.contains(floorAt(all, BOX.minX() + i, BOX.minZ() + k, FEET)), "danced on: " + i + "," + k);
            }
        }
        assertTrue(lit * 3 >= tiles, "a light-up floor: " + lit + " of " + tiles);
        assertTrue(tiles >= 40, "room for the party games: " + tiles);
        List<ClubhousePlanner.Prop> decor = ClubhousePlanner.decor(BOX, ClubhousePlanner.Rooms.BOTH);
        assertTrue(decor.stream().filter(p -> p.key().startsWith("party.balloon.") && !p.block()).count() >= 12,
                "balloons");
        assertTrue(decor.stream().filter(p -> p.key().startsWith("party.bunting.") && "diamond".equals(p.look())).count()
                >= 40, "bunting");
        for (ClubhousePlanner.Prop p : decor) {
            if (p.key().startsWith("party.")) {
                assertTrue(ROOM.contains(p.x(), p.y(), p.z()), "in the room: " + p);
                assertNotNull(Material.matchMaterial(p.what()), p.what());
            }
        }
        assertTrue(ClubhousePlanner.decor(OLD, ClubhousePlanner.Rooms.BOTH).stream()
                .noneMatch(p -> p.key().startsWith("party.")), "no party at the old size");
    }

    /**
     * The trampoline: slime against the walls behind a padded rim with a gap, nothing over it up to above the highest
     * bounce, lit, stools facing it; and too far from the Mini World's hedge for a bounce to reach it.
     */
    @Test
    void theTrampolineIsSlimeBehindAPaddedRimWithRoomToBounce() {
        Box t = ClubhousePlanner.trampoline(BOX);
        double peak = PartyRoom.height(PartyRoom.BOUNCE);
        assertTrue(peak > 2.5 && peak < PartyRoom.CAP, "about three blocks, under the cap: " + peak);
        for (int x = t.minX(); x <= t.maxX(); x++) {
            for (int z = t.minZ(); z <= t.maxZ(); z++) {
                assertEquals(ClubhousePlanner.SLIME, at(x, FLOOR, z), "slime at " + where(x, z));
                for (int y = FEET; y < FEET + PartyRoom.CAP + TALL; y++) {
                    assertTrue(passable(at(x, y, z)), "room to bounce at " + where(x, z) + " y " + (y - FLOOR));
                }
                assertTrue(LIGHT.getOrDefault(key(x, FEET, z), 0) >= 4, "lit");
            }
        }
        int gaps = 0;
        for (int k = ClubhousePlanner.TRAMP_Z0; k <= ClubhousePlanner.TRAMP_RIM_Z; k++) {
            String rim = rel(ClubhousePlanner.TRAMP_RIM_X, 1, k);
            if (rim == null) {
                gaps++;
                continue;
            }
            assertTrue(rim.endsWith("_wool"), "a padded rim: " + rim);
            assertEquals(ClubhousePlanner.PIT_RIM, rel(ClubhousePlanner.TRAMP_RIM_X, 2, k));
        }
        assertEquals(ClubhousePlanner.TRAMP_GAP_Z.length, gaps, "a way in");
        for (int i = ClubhousePlanner.TRAMP_RIM_X; i <= ClubhousePlanner.TRAMP_X1; i++) {
            assertTrue(rel(i, 1, ClubhousePlanner.TRAMP_RIM_Z).endsWith("_wool"), "the south rim");
        }
        // a bounce never carries anyone onto the hedge: the furthest it goes while over the hedge's top
        double hedgeOver = 2.0; // the hedge's top over the slime's
        int ticks = 0;
        double y = 0;
        double v = PartyRoom.BOUNCE;
        while (v > 0 || y > hedgeOver) {
            y += v;
            v = (v - 0.08) * 0.98;
            ticks++;
        }
        double reach = ticks * 0.29; // sprinting in the air
        assertTrue(ClubhousePlanner.MINI_Z0 - (ClubhousePlanner.TRAMP_Z1 + 1) > reach, "out of a bounce's reach: "
                + reach);
        // stools facing it
        List<ClubhousePlanner.Seat> seats = ClubhousePlanner.trophySeats(BOX);
        assertEquals(ClubhousePlanner.PARTY_STOOLS.length + 2, seats.size(), "the four stools and the two armchairs");
        for (int[] s : ClubhousePlanner.PARTY_STOOLS) {
            assertEquals(ClubhousePlanner.VIEW_STOOL, rel(s[0], 1, s[1]));
        }
    }

    /**
     * The Mini World: hedged in two high all round (or the room's wall), nobody hops in or out but by its two doorways;
     * every floor of it walked on at full size from the way in; its doll's house's two floors walked on, up the steps;
     * every piece of furniture a hop up from a floor with room over it; no hedge climbed; and nowhere in the whole Party
     * Room is a space a small visitor fits in but a full-size one doesn't, so growing back anywhere sticks nobody.
     */
    @Test
    void theMiniWorldIsHedgedInWalkedAtFullSizeAndHasNoSmallOnlySpace() {
        // hedged in: its edge is hedge (two high), a doorway or the room's wall
        int doors = 0;
        for (int x = MINI.minX(); x <= MINI.maxX(); x++) {
            for (int z = MINI.minZ(); z <= MINI.maxZ(); z++) {
                boolean edge = x == MINI.minX() || z == MINI.minZ() || z == MINI.maxZ();
                if (!edge) {
                    continue;
                }
                boolean door = false;
                for (ClubhousePlanner.Place3 d : ClubhousePlanner.miniDoors(BOX)) {
                    door |= Math.floor(d.x()) == x && Math.floor(d.z()) == z;
                }
                if (door) {
                    doors++;
                    assertEquals(ClubhousePlanner.ARCH_SILL, at(x, FLOOR, z), "its sill, in the floor");
                    assertNull(at(x, FEET, z), "a flat floor under the lintel: no step up");
                    assertNull(at(x, FEET + 1, z), "two high to walk through");
                    assertEquals(ClubhousePlanner.ARCH, at(x, FEET + 2, z), "under its lintel");
                    continue;
                }
                assertFalse(passable(at(x, FEET, z)) || passable(at(x, FEET + 1, z)), "two high at " + where(x, z));
            }
        }
        assertEquals(2, doors);
        int[] lintel = ClubhousePlanner.miniLintel(BOX);
        assertEquals(ClubhousePlanner.ARCH, at(lintel[0], lintel[1], lintel[2]), "the lintel says the Mini World is there");
        assertEquals(Material.PURPLE_CONCRETE, Material.matchMaterial(ClubhousePlanner.ARCH));
        // walked at full size from the party floor, through the way in
        Map<Long, List<Surface>> all = surfaces(ROOM);
        ClubhousePlanner.Place3 in = ClubhousePlanner.miniDoors(BOX).get(0);
        Surface start = floorAt(all, (int) Math.floor(in.x()), (int) Math.floor(in.z()) - 1, FEET);
        Set<Surface> walked = walked(all, start);
        double upFloor = FLOOR + ClubhousePlanner.HOUSE_UP + 1;
        int floors = 0;
        int ups = 0;
        for (List<Surface> col : all.values()) {
            for (Surface s : col) {
                boolean inMini = MINI.contains(s.x(), FEET, s.z());
                boolean ground = s.h() < FEET + 0.1 && s.room() >= TALL;
                int hx = s.x() - BOX.minX();
                int hz = s.z() - BOX.minZ();
                boolean house = hx >= ClubhousePlanner.HOUSE_X0 && hx <= ClubhousePlanner.HOUSE_X1
                        && hz > ClubhousePlanner.HOUSE_Z0 && hz < ClubhousePlanner.HOUSE_Z1;
                boolean upstairs = house && s.h() >= upFloor - 1e-9 && s.h() < upFloor + 0.1 && s.room() >= TALL;
                if (ground || upstairs) {
                    assertTrue(walked.contains(s), "walked to at full size: " + where(s.x(), s.z()) + " at " + (s.h()
                            - FLOOR));
                    floors += inMini ? 1 : 0;
                    ups += upstairs ? 1 : 0;
                }
            }
        }
        assertTrue(floors > 60, "the Mini World's floors: " + floors);
        assertTrue(ups >= 8, "the doll's house's upper floor: " + ups);
        // everywhere a small visitor gets to (hopping too), a full-size one fits: growing back there sticks nobody; and
        // nobody, small or not, gets onto a hedge or a doorway's top
        Set<Surface> small = reached(all, start, SMALL, HOP);
        assertTrue(small.containsAll(walked));
        int furniture = 0;
        for (Surface s : small) {
            assertTrue(s.room() >= TALL, "a space only a small visitor fits at " + where(s.x(), s.z()) + " at "
                    + (s.h() - FLOOR) + " (" + s.room() + " high)");
            String under = at(s.x(), (int) Math.round(s.h()) - 1, s.z());
            boolean raised = s.h() > FEET + 0.5; // (the doorways' sills and the dance floor's tiles are in the floor)
            assertFalse(ClubhousePlanner.SHRUB.equals(under) || ClubhousePlanner.PLANTER.equals(under)
                    || (raised && (ClubhousePlanner.ARCH.equals(under) || ClubhousePlanner.ARCH_CAP.equals(under))),
                    "never on a hedge's or a doorway's top: " + way(s));
            String feetIn = at(s.x(), feet(s), s.z()); // (in an armchair's arm is no place: ClubhousePlannerTest's rule)
            assertTrue((feetIn != null && feetIn.contains("_trapdoor"))
                    || LIGHT.getOrDefault(key(s.x(), feet(s), s.z()), 0) >= 4, "lit wherever anyone gets: "
                    + where(s.x(), s.z()) + " at " + (s.h() - FLOOR));
            furniture += MINI.contains(s.x(), FEET, s.z()) && !walked.contains(s) ? 1 : 0;
        }
        assertTrue(furniture >= 8, "chunky furniture to climb onto: " + furniture);
    }

    /** The maze: three by four little rooms, in at the doorway, out by the doll's house, its gold middle off the way. */
    @Test
    void theMazeHasAWayThroughAndItsPrizeAtItsMiddleOffTheWay() {
        int cells = 0;
        for (int i = ClubhousePlanner.MINI_X0; i <= ClubhousePlanner.MAZE_X1; i++) {
            for (int k = ClubhousePlanner.MINI_Z0; k <= ClubhousePlanner.MAZE_Z1; k++) {
                if (ClubhousePlanner.mazeCell(i, k)) {
                    cells++;
                    assertEquals(ClubhousePlanner.LIGHT, rel(i, 0, k), "lit from under its grass");
                }
            }
        }
        assertEquals(12, cells);
        assertEquals(11, ClubhousePlanner.MAZE_OPEN.length, "a tree: every room joined, one way between any two");
        int[] g = ClubhousePlanner.MAZE_GOAL;
        assertTrue(ClubhousePlanner.mazeCell(g[0], g[1]));
        assertEquals(ClubhousePlanner.GOAL_CARPET, rel(g[0], 1, g[1]), "its middle is gold");
        int ways = 0;
        for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            ways += ClubhousePlanner.mazeOpen(g[0] + d[0], g[1] + d[1]) ? 1 : 0;
        }
        assertEquals(1, ways, "a dead end: off the way through");
        // the way through: from the doorway's room to the way out, not through the gold room
        Set<Long> seen = new HashSet<>();
        ArrayDeque<int[]> q = new ArrayDeque<>();
        q.add(new int[]{ClubhousePlanner.MINI_DOOR[0], ClubhousePlanner.MINI_DOOR[1] + 1});
        boolean out = false;
        while (!q.isEmpty()) {
            int[] c = q.poll();
            if (!seen.add(key(c[0], 0, c[1]))) {
                continue;
            }
            if (c[0] == ClubhousePlanner.MAZE_EXIT[0] && c[1] == ClubhousePlanner.MAZE_EXIT[1]) {
                out = true;
            }
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int x = c[0] + d[0];
                int z = c[1] + d[1];
                if ((ClubhousePlanner.mazeCell(x, z) || ClubhousePlanner.mazeOpen(x, z)) && !(x == g[0] && z == g[1])) {
                    q.add(new int[]{x, z});
                }
            }
        }
        assertTrue(out, "a way through");
        assertEquals(ClubhousePlanner.MAZE_X1, ClubhousePlanner.MAZE_EXIT[0], "out on its east side");
        // the prize on it: a cake and a star
        List<ClubhousePlanner.Prop> decor = ClubhousePlanner.decor(BOX, ClubhousePlanner.Rooms.BOTH);
        ClubhousePlanner.Place3 goal = ClubhousePlanner.mazeGoal(BOX);
        for (String k : List.of("party.goal.cake", "party.goal.star")) {
            ClubhousePlanner.Prop p = decor.stream().filter(x -> x.key().equals(k)).findFirst().orElseThrow();
            assertTrue(Math.floor(p.x()) == Math.floor(goal.x()) && Math.floor(p.z()) == Math.floor(goal.z()), k);
        }
        assertTrue(PartyRoom.atGoal(goal, goal.x(), goal.y() + RegularSpots.CARPET, goal.z()));
        assertFalse(PartyRoom.atGoal(goal, goal.x() + 1, goal.y(), goal.z()));
        assertFalse(PartyRoom.atGoal(goal, goal.x(), goal.y() + 3, goal.z()), "not over it");
    }

    /** The doll's house: two floors, the steps up walled on the maze's side, a railing upstairs, lamps in its walls. */
    @Test
    void theDollsHouseHasTwoFloorsAndChunkyFurniture() {
        int x0 = ClubhousePlanner.HOUSE_X0;
        int up = ClubhousePlanner.HOUSE_UP;
        for (int i = x0; i <= ClubhousePlanner.HOUSE_X1; i++) {
            for (int k = ClubhousePlanner.HOUSE_Z0 + 1; k < ClubhousePlanner.HOUSE_Z1; k++) {
                String f = rel(i, up, k);
                assertTrue(ClubhousePlanner.DOLL_FLOOR.equals(f) || ClubhousePlanner.LIGHT.equals(f), "an upper floor: " + f);
                for (int j = 1; j < up; j++) {
                    String b = rel(i, j, k);
                    assertTrue(b == null || !passable(b), "nothing thin downstairs to squeeze under");
                }
            }
        }
        for (int n = 0; n < ClubhousePlanner.STEP_Z.length; n++) {
            int k = ClubhousePlanner.STEP_Z[n];
            assertEquals(ClubhousePlanner.DOLL_STAIRS, rel(ClubhousePlanner.PORCH_X, n + 1, k), "a stair");
            for (int j = 1; j <= ClubhousePlanner.SIDE_TOP; j++) {
                assertEquals(ClubhousePlanner.DOLL_WALL, rel(ClubhousePlanner.PORCH_X - 1, j, k), "walled on the maze's side");
            }
        }
        assertEquals(ClubhousePlanner.CLOTH, rel(ClubhousePlanner.DOLL_TABLE[0], 1, ClubhousePlanner.DOLL_TABLE[1]),
                "a kitchen table");
        assertTrue(rel(ClubhousePlanner.DOLL_TABLE[0], 1, ClubhousePlanner.DOLL_TABLE[1] - 1).contains("_stairs"),
                "and its chair");
        int lamps = 0;
        for (int[] l : ClubhousePlanner.HOUSE_LAMPS) {
            assertEquals(15, ClubhousePlanner.lightOf(rel(l[0], l[1], l[2])), "a lamp in its wall");
            lamps++;
        }
        assertTrue(lamps >= 4);
        int rails = 0;
        for (int k = ClubhousePlanner.HOUSE_Z0 + 1; k < ClubhousePlanner.HOUSE_Z1; k++) {
            String r = rel(x0, up + 1, k);
            if (k == ClubhousePlanner.HOUSE_MID_Z) {
                assertNull(r, "the landing is open");
            } else {
                assertTrue(r.contains("_fence"), "a railing: " + r);
                rails++;
            }
        }
        assertEquals(5, rails);
    }

    /** The trophy end: every cup there, the podium where it was, the dark door where it was, armchairs facing it all. */
    @Test
    void everyCupIsAtTheTrophyEnd() {
        List<ClubhousePlanner.Pedestal> ps = ClubhousePlanner.pedestals(BOX);
        assertEquals(3 + ClubhousePlanner.RECORD_PEDESTALS, ps.size());
        for (ClubhousePlanner.Pedestal p : ps) {
            for (ClubhousePlanner.Place3 c : List.of(p.trophy(), p.head())) {
                assertTrue(c.z() >= BOX.minZ() + ClubhousePlanner.TROPHY_END_Z0, "at the trophy end: " + c);
                assertTrue(ROOM.contains(c.x(), c.y(), c.z()), "in the room: " + c);
            }
        }
        ClubhousePlanner.Place3 door = ClubhousePlanner.secretDoor(BOX);
        assertTrue(door.z() >= BOX.minZ() + ClubhousePlanner.TROPHY_END_Z0, "the secret room's door too");
        for (ClubhouseSite.Spot s : SITE.podium()) {
            assertTrue(s.z() >= BOX.minZ() + ClubhousePlanner.TROPHY_END_Z0, "Photo time! there");
        }
        for (int i : ClubhousePlanner.PARTY_ARMCHAIR_X) {
            assertEquals(ClubhousePlanner.chair("north"), rel(i, 1, ClubhousePlanner.PARTY_ARMCHAIR_Z), "facing the podium");
        }
        // reached from the archway on foot, past the Mini World by the garden path (never through it)
        RegularWays ways = RegularWays.of(SITE, ClubhousePlanner.Rooms.BOTH);
        WalkGrid g = ways.grid();
        WalkGrid.Cell arch = g.at(BOX.minX() + ClubhousePlanner.ARCH_TROPHY_X0 + 1.5, FEET, BOX.minZ() + 32.5);
        WalkGrid.Cell front = g.at(door.x(), FEET + RegularSpots.CARPET, door.z() - 1);
        assertNotNull(arch);
        assertNotNull(front);
        List<WalkGrid.Cell> path = g.path(arch, front, Set.of());
        assertNotNull(path, "the dark door reached");
        assertTrue(path.stream().noneMatch(c -> MINI.contains(c.x(), c.y(), c.z())), "never through the Mini World");
    }

    /** The regulars never go into the Mini World: no place of theirs there, no cell of their grid. */
    @Test
    void theRegularsKeepOutOfTheMiniWorld() {
        WalkGrid g = RegularWays.of(SITE, ClubhousePlanner.Rooms.BOTH).grid();
        for (int x = MINI.minX(); x <= MINI.maxX(); x++) {
            for (int z = MINI.minZ(); z <= MINI.maxZ(); z++) {
                for (int y = MINI.minY(); y <= MINI.maxY(); y++) {
                    assertNull(g.cell(x, y, z), "no regular's cell at " + where(x, z));
                }
            }
        }
        for (RegularSpots.Spot s : RegularSpots.of(SITE, ClubhousePlanner.Rooms.BOTH)) {
            assertFalse(MINI.contains(s.x(), s.y(), s.z()), "no place there: " + s);
        }
        // and it is a grid of the room without it all the same: the whole grid has the cells there
        WalkGrid whole = WalkGrid.build(B, SITE.plan().half());
        assertNotNull(whole.at(BOX.minX() + ClubhousePlanner.MAZE_GOAL[0] + 0.5, FEET + RegularSpots.CARPET,
                BOX.minZ() + ClubhousePlanner.MAZE_GOAL[1] + 0.5), "the maze is walkable, only not theirs");
    }

    // ---- the live parts --------------------------------------------------------------------------------------------

    @Test
    void aVisitorIsSmallOnlyInTheMiniWorld() {
        ClubhousePlanner.Place3 door = ClubhousePlanner.miniDoors(BOX).get(0);
        assertTrue(PartyRoom.smallAt(MINI, true, false, door.x(), door.y(), door.z()), "in its doorway");
        assertFalse(PartyRoom.smallAt(MINI, true, false, door.x(), door.y(), door.z() - 1), "a step out");
        assertFalse(PartyRoom.smallAt(MINI, false, false, door.x(), door.y(), door.z() + 1), "not a visitor");
        assertFalse(PartyRoom.smallAt(MINI, true, true, door.x(), door.y(), door.z() + 1), "a spectator watching");
        assertFalse(PartyRoom.smallAt(null, true, false, door.x(), door.y(), door.z() + 1), "no Mini World");
        ClubhousePlanner.Place3 goal = ClubhousePlanner.mazeGoal(BOX);
        assertTrue(PartyRoom.smallAt(MINI, true, false, goal.x(), goal.y(), goal.z()));
        assertTrue(PartyRoom.MINI >= 0.25 && PartyRoom.MINI <= 0.35);
        // small enough to feel tiny, too big to fit where only an azalea's stem is (half a block under its top)
        assertTrue(SMALL > 0.5, "a small visitor is still taller than half a block: " + SMALL);
    }

    @Test
    void theTrampolineBouncesALandingStopsAtTheCapAndACrouchStops() {
        assertEquals(PartyRoom.Jump.BOUNCE, PartyRoom.jump(0, true, false, false, Integer.MAX_VALUE));
        assertEquals(PartyRoom.Jump.BOUNCE, PartyRoom.jump(0.03, true, false, false, PartyRoom.BOUNCE_TICKS));
        assertEquals(PartyRoom.Jump.NONE, PartyRoom.jump(0, true, true, false, 100), "a crouch: no bounce");
        assertEquals(PartyRoom.Jump.NONE, PartyRoom.jump(0, false, false, false, 100), "no slime: no bounce");
        assertEquals(PartyRoom.Jump.NONE, PartyRoom.jump(0, true, false, true, 100), "flying: no bounce");
        assertEquals(PartyRoom.Jump.NONE, PartyRoom.jump(0, true, false, false, 2), "a landing is one bounce");
        assertEquals(PartyRoom.Jump.NONE, PartyRoom.jump(1.5, true, false, false, 100), "in the air");
        assertEquals(PartyRoom.Jump.STOP, PartyRoom.jump(PartyRoom.CAP + 0.1, true, false, false, 100), "too high");
        assertTrue(PartyRoom.height(0.42) > 1.0 && PartyRoom.height(0.42) < 1.3, "a plain jump: "
                + PartyRoom.height(0.42));
    }

    /**
     * The ball pit's balls (the owner: "The ball pit in the arcade would be amazing if the balls moved!!"): pushed away
     * from whoever is in it, harder the nearer and the faster, and never out of the pit, whatever the push.
     */
    @Test
    void theBallsArePushedAwayAndNeverLeaveThePit() {
        Box pit = ClubhousePlanner.ballPitInside(BOX);
        List<ClubhousePlanner.Prop> balls = ClubhousePlanner.decor(BOX, ClubhousePlanner.Rooms.BOTH).stream()
                .filter(p -> p.key().startsWith("arcade.ball.")).toList();
        assertTrue(balls.size() >= 100);
        Random r = new Random(15);
        int pushed = 0;
        for (int n = 0; n < 5_000; n++) {
            ClubhousePlanner.Prop b = balls.get(r.nextInt(balls.size()));
            double fx = pit.minX() - 1 + r.nextDouble() * (pit.maxX() - pit.minX() + 3);
            double fz = pit.minZ() - 1 + r.nextDouble() * (pit.maxZ() - pit.minZ() + 3);
            double[] to = BallPit.pushed(pit, b.sx() / 2.0, b.x(), b.y(), b.z(), fx, fz, r.nextDouble() * 0.8,
                    r.nextDouble());
            if (to == null) {
                assertTrue(Math.hypot(b.x() - fx, b.z() - fz) > BallPit.REACH, "only the far ones are left");
                continue;
            }
            pushed++;
            double half = b.sx() / 2.0;
            assertTrue(to[0] - half >= pit.minX() - 1e-9 && to[0] + half <= pit.maxX() + 1 + 1e-9, "inside the walls");
            assertTrue(to[2] - half >= pit.minZ() - 1e-9 && to[2] + half <= pit.maxZ() + 1 + 1e-9, "inside the walls");
            assertTrue(to[1] > pit.minY() && to[1] <= pit.maxY() + 0.6 + 1e-9, "on the floor to a little over the rim");
            assertTrue(Math.hypot(to[0] - fx, to[2] - fz) >= Math.hypot(b.x() - fx, b.z() - fz) - 0.3, "away, or at"
                    + " the wall");
        }
        assertTrue(pushed > 500, "pushed: " + pushed);
        // every ball's own place is inside the walls already (so going back is never out)
        for (ClubhousePlanner.Prop b : balls) {
            double[] k = BallPit.keep(pit, b.sx() / 2.0, b.x(), b.y(), b.z());
            assertEquals(b.x(), k[0], 1e-9);
            assertEquals(b.z(), k[2], 1e-9);
            assertEquals(b.y(), k[1], 1e-9, "its place is a place it may be");
        }
        // a jump in pushes further than a step
        ClubhousePlanner.Prop b = balls.get(0);
        double[] slow = BallPit.pushed(pit, 0.25, b.x(), b.y(), b.z(), b.x() - 0.6, b.z(), 0.0, 0.5);
        double[] fast = BallPit.pushed(pit, 0.25, b.x(), b.y(), b.z(), b.x() - 0.6, b.z(), 0.6, 0.5);
        assertTrue(fast[1] >= slow[1], "higher");
        assertTrue(Math.abs(fast[0] - b.x()) >= Math.abs(slow[0] - b.x()) - 1e-9, "further");
        assertNull(ClubhousePlanner.decor(OLD, ClubhousePlanner.Rooms.BOTH).stream()
                .filter(p -> p.key().startsWith("arcade.ball.")).findFirst().orElse(null), "no pit at the old size");
    }

    /** A bunting flag is turned on its corner about its own middle: its middle stays on its place. */
    @Test
    void aBuntingFlagTurnsAboutItsMiddle() {
        Transformation t = ClubDecor.diamond(0.3f, 0.3f, 0.03f);
        Vector3f middle = new Vector3f(0.15f, 0.15f, 0.015f);
        Vector3f drawn = t.getLeftRotation().transform(new Vector3f(middle)).add(t.getTranslation());
        assertEquals(0, drawn.length(), 1e-6, "its middle on its place");
        Vector3f corner = t.getLeftRotation().transform(new Vector3f(0.3f, 0.3f, 0f)).add(t.getTranslation());
        assertEquals(0, corner.x(), 1e-6, "a corner straight up: a diamond");
        assertTrue(corner.y() > 0.2);
    }

    /** Plan 15, the secret room tidied to match: its trophies in its far half, a runner from the door to its big cup. */
    @Test
    void theSecretRoomsTrophiesAreAtItsFarEndWithARunnerToItsCup() {
        List<ClubhousePlanner.Place3> shelf = ClubhousePlanner.secretShelf(BOX);
        assertTrue(shelf.size() >= 30, "room for many trophies: " + shelf.size());
        for (ClubhousePlanner.Place3 t : shelf) {
            assertTrue(t.z() >= BOX.minZ() + ClubhousePlanner.SECRET_LEDGE_Z0, "in the far half: " + t);
        }
        ClubhousePlanner.Place3 cup = ClubhousePlanner.secretCentre(BOX);
        assertEquals(BOX.minZ() + ClubhousePlanner.GROWN_SECRET_CZ + 0.5, cup.z(), 1e-9);
        assertEquals(ClubhousePlanner.PEDESTAL, at((int) Math.floor(cup.x()), (int) cup.y() - 1, (int) Math.floor(cup.z())));
        int up = BOX.minY() + ClubhousePlanner.UP_FLOOR + 1;
        ClubhouseSite.Spot in = ClubhousePlanner.secretArrival(BOX);
        for (int k = (int) Math.floor(in.z()); k < (int) Math.floor(cup.z()); k++) {
            assertEquals(ClubhousePlanner.TROPHY_RUG, at((int) Math.floor(cup.x()), up, k), "the runner to the cup");
        }
        // the old size's room is as it was
        List<ClubhousePlanner.Place3> oldShelf = ClubhousePlanner.secretShelf(OLD);
        assertEquals(34, oldShelf.size(), "seventeen a row, as before");
    }
}
