package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.chance.mobraces.RaceTrack;
import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plan 13, the side rooms' wing grown 14 rows to the south (the owner: the bean bags "just read as blocks, I think we
 * need to make the place larger so all these things can fit comfortably", "Make it large enough that we can add more
 * things, but not so big it looks empty. We want to fill the space"): everything that was there kept where it was;
 * the ball pit walled, padded, lit and walked in and out of; bean bags that read as bean bags; a prize-and-plush corner
 * with shelves, plushies, ribbons, tags and a counter; a viewing area for the kid race; four spare cabinet places; in
 * the Parlor a lounge, room for two more card tables and the sportsbook's wall; and every place, cabinet, standing spot
 * and seat in the rooms, free, lit and reached on foot.
 */
class GrownWingTest {

    private static final ClubhouseSettings SETTINGS = ClubhouseSettings.defaults();
    private static final Box BOX = SETTINGS.box();
    private static final Box OLD = SETTINGS.oldBox();
    private static final ClubhouseSite SITE = ClubhousePlanner.plan(BOX);
    private static final ClubhouseSite OLD_SITE = ClubhousePlanner.plan(OLD);
    private static final Map<Long, String> B = RegularSpots.blocks(SITE.plan());
    private static final Map<Long, Integer> LIGHT = light();
    private static final RegularWays WAYS = RegularWays.of(SITE, ClubhousePlanner.Rooms.BOTH);
    private static final WalkGrid GRID = WAYS.grid();
    private static final int FLOOR = BOX.minY();
    private static final int FEET = FLOOR + 1;
    private static final int UP_FEET = BOX.minY() + ClubhousePlanner.UP_FLOOR + 1;
    private static final int AX = BOX.minX() + ClubhousePlanner.ARCADE_X0;
    private static final int LIT = 4;
    private static final double CARPET = RegularSpots.CARPET;

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

    private static boolean carpetOrAir(String block) {
        return block == null || ClubhousePlanner.id(block).endsWith("_carpet");
    }

    private static int lightAt(int x, int y, int z) {
        return LIGHT.getOrDefault(key(x, y, z), 0);
    }

    /** The plan's block light, flooded as {@code ClubhousePlannerTest} floods it. */
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
        Map<Long, String> b = RegularSpots.blocks(SITE.plan());
        while (!q.isEmpty()) {
            int[] c = q.poll();
            if (light.getOrDefault(key(c[0], c[1], c[2]), 0) > c[3] || c[3] <= 1) {
                continue;
            }
            for (int[] d : dirs) {
                int x = c[0] + d[0];
                int y = c[1] + d[1];
                int z = c[2] + d[2];
                String n = b.get(key(x, y, z));
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

    /** Where the Arcade Room's archway lets out into the room, on the grid. */
    private static WalkGrid.Cell archway() {
        WalkGrid.Cell c = GRID.at(BOX.minX() + ClubhousePlanner.ARCH_ARCADE_X0 + 1.5, FEET + CARPET,
                BOX.minZ() + ClubhousePlanner.SIDE_Z0 + 0.5);
        assertNotNull(c, "the archway's end is on the grid");
        return c;
    }

    /** A cell someone stands in at (x, y, z) (on its floor or a carpet): free, lit, and walked to from {@code from}. */
    private static void standsAndIsReached(int x, int y, int z, WalkGrid.Cell from, String what) {
        assertTrue(carpetOrAir(at(x, y, z)), what + ": feet free at " + where(x, y, z) + ", not " + at(x, y, z));
        assertNull(at(x, y + 1, z), what + ": head free at " + where(x, y, z));
        String below = at(x, y - 1, z);
        assertTrue(below != null && !ClubhousePlanner.noFooting(below), what + ": a floor under it, not " + below);
        assertTrue(lightAt(x, y, z) >= LIT, what + ": lit at " + where(x, y, z) + " (" + lightAt(x, y, z) + ")");
        WalkGrid.Cell c = GRID.at(x + 0.5, y + (at(x, y, z) == null ? 0 : CARPET), z + 0.5);
        assertNotNull(c, what + ": on the walk grid at " + where(x, y, z));
        assertNotNull(GRID.path(from, c, Set.of()), what + ": walked to from " + from);
    }

    private static String where(int x, int y, int z) {
        return (x - BOX.minX()) + "," + (y - BOX.minY()) + "," + (z - BOX.minZ());
    }

    // ---- the box and what was there --------------------------------------------------------------------------------

    @Test
    void theWingGrewFourteenRowsAndKeptEverythingThatWasThere() {
        assertEquals(62, BOX.sizeZ(), "32 rows of main room, 30 of side rooms (16 before)");
        assertEquals(14, ClubhousePlanner.WING_Z);
        assertEquals(14, ClubhousePlanner.arcadeInside(BOX).maxZ() - ClubhousePlanner.arcadeInside(OLD).maxZ(),
                "the Arcade Room is 14 rows deeper");
        assertEquals(29, ClubhousePlanner.arcadeInside(BOX).maxZ() - ClubhousePlanner.arcadeInside(BOX).minZ() + 1,
                "29 rows of air (15 before)");
        for (Box[] room : new Box[][]{{ClubhousePlanner.trophyInside(BOX), ClubhousePlanner.trophyInside(OLD)},
                {ClubhousePlanner.parlorInside(BOX), ClubhousePlanner.parlorInside(OLD)},
                {ClubhousePlanner.secretInside(BOX), ClubhousePlanner.secretInside(OLD)}}) {
            assertEquals(room[1].minZ(), room[0].minZ(), "from the same north wall");
            assertEquals(14, room[0].maxZ() - room[1].maxZ(), "and grown the same: " + room[0]);
        }
        // every place there was is where it was, in the same order
        assertEquals(ClubhousePlanner.cabinets(OLD), ClubhousePlanner.cabinets(BOX).subList(0,
                ClubhousePlanner.OLD_CABINETS), "the nine cabinet places, in their game order");
        assertEquals(ClubhousePlanner.arcadeTable(OLD), ClubhousePlanner.arcadeTable(BOX), "the kid race's table");
        assertEquals(ClubhousePlanner.raceTable(OLD), ClubhousePlanner.raceTable(BOX), "Mob Races' table");
        assertEquals(ClubhousePlanner.parlorMachines(OLD), ClubhousePlanner.parlorMachines(BOX), "the Parlor's machines");
        assertEquals(ClubhousePlanner.parlorArrival(OLD), ClubhousePlanner.parlorArrival(BOX));
        assertEquals(ClubhousePlanner.secretArrival(OLD), ClubhousePlanner.secretArrival(BOX));
        assertEquals(ClubhousePlanner.door(OLD, true), ClubhousePlanner.door(BOX, true), "the archways' signs");
        List<ClubhousePlanner.Place3> row = ClubhousePlanner.parlorRow(BOX);
        List<ClubhousePlanner.Place3> oldRow = ClubhousePlanner.parlorRow(OLD);
        for (int n = 0; n < row.size(); n++) {
            assertEquals(oldRow.get(n).x(), row.get(n).x(), "the back row's machines in their order");
            assertEquals(oldRow.get(n).z() + 14, row.get(n).z(), 1e-9, "against the back wall, 14 rows back");
        }
        assertEquals(ClubhousePlanner.parlorTicket(OLD).z() + 14, ClubhousePlanner.parlorTicket(BOX).z(), 1e-9);
        List<ClubhousePlanner.Pedestal> ps = ClubhousePlanner.pedestals(BOX);
        List<ClubhousePlanner.Pedestal> oldPs = ClubhousePlanner.pedestals(OLD);
        for (int n = 0; n < ps.size(); n++) {
            double dz = n < 3 ? 14 : 0;
            assertEquals(oldPs.get(n).trophy().z() + dz, ps.get(n).trophy().z(), 1e-9, "the podium at the back, the"
                    + " records on the side walls where they were: " + n);
        }
        // the main room, and the Arcade Room's old north part (its cabinets, table and rug), block for block
        Map<Long, String> old = RegularSpots.blocks(OLD_SITE.plan());
        int same = 0;
        for (int i = 0; i < 32; i++) {
            for (int j = 0; j <= ClubhousePlanner.ROOF; j++) {
                for (int k = 0; k < ClubhousePlanner.MAIN_Z; k++) {
                    long p = key(BOX.minX() + i, BOX.minY() + j, BOX.minZ() + k);
                    assertEquals(old.get(p), B.get(p), "the main room is as it was at " + i + "," + j + "," + k);
                    same++;
                }
            }
        }
        for (int i = 1; i <= 14; i++) {
            for (int j = 0; j <= ClubhousePlanner.SIDE_TOP; j++) {
                for (int k = ClubhousePlanner.SIDE_Z0; k <= ClubhousePlanner.KID_TRACK_Z0 + RaceTrack.LENGTH + 1; k++) {
                    long p = key(AX + i, BOX.minY() + j, BOX.minZ() + k);
                    if (old.get(p) != null && List.of(ClubhousePlanner.BEAN_BAG).contains(old.get(p))) {
                        assertNull(B.get(p), "plan 12's wool bean bags left the west wall for the nook");
                        continue;
                    }
                    assertEquals(old.get(p), B.get(p), "the Arcade Room's north part as it was at " + i + "," + j
                            + "," + k);
                    same++;
                }
            }
        }
        assertTrue(same > 15_000, "checked " + same);
    }

    // ---- the Arcade Room ------------------------------------------------------------------------------------------

    @Test
    void fourSpareCabinetPlacesWaitOnTheirMats() {
        List<ClubhousePlanner.Place3> cabs = ClubhousePlanner.cabinets(BOX);
        assertEquals(ClubhousePlanner.OLD_CABINETS + 4, cabs.size());
        for (ClubhousePlanner.Place3 c : cabs.subList(ClubhousePlanner.OLD_CABINETS, cabs.size())) {
            int x = (int) Math.floor(c.x());
            int z = (int) Math.floor(c.z());
            assertTrue(z - BOX.minZ() > ClubhousePlanner.OLD_SIDE_Z1, "in the new rows: " + c);
            assertEquals(ClubhousePlanner.MAT, at(x, FLOOR, z), "a mat waits there");
            assertNull(at(x, FEET, z), "plain floor over it until a game takes it");
        }
        // with today's games every one of them is filled before a spare is
        List<String> room = KidSet.fill(SETTINGS.arcadeGames(), KidSet.CABINET_GAMES, SideRooms.LAVA_GAME, cabs.size());
        assertEquals(new HashSet<>(room).size(), room.size(), "never a game twice");
        assertTrue(room.size() <= ClubhousePlanner.OLD_CABINETS, "the spares are spare: " + room);
    }

    @Test
    void theBallPitIsWalledPaddedLitAndWalkedInAndOutOf() {
        WalkGrid.Cell door = archway();
        int z0 = BOX.minZ();
        int inside = 0;
        for (int i = ClubhousePlanner.PIT_X0; i <= ClubhousePlanner.PIT_X1; i++) {
            for (int k = ClubhousePlanner.PIT_Z0; k <= ClubhousePlanner.PIT_Z1; k++) {
                int x = AX + i;
                int z = z0 + k;
                boolean wall = i == ClubhousePlanner.PIT_X0 || i == ClubhousePlanner.PIT_X1
                        || k == ClubhousePlanner.PIT_Z0 || k == ClubhousePlanner.PIT_Z1;
                boolean gap = k == ClubhousePlanner.PIT_Z0 && i == ClubhousePlanner.PIT_GAP_X;
                if (wall && !gap) {
                    assertTrue(at(x, FEET, z).endsWith("_wool"), "a low padded wall at " + i + "," + k);
                    assertEquals(ClubhousePlanner.PIT_RIM, at(x, FEET + 1, z), "its soft rim");
                    assertNull(at(x, FEET + 2, z), "one block high: nothing to be stuck under");
                    assertTrue(lightAt(x, FEET + 1, z) >= LIT, "the rim's top is lit at " + i + "," + k);
                    continue;
                }
                assertEquals(ClubhousePlanner.PIT_FLOOR, at(x, FEET, z), "its floor at " + i + "," + k);
                standsAndIsReached(x, FEET, z, door, "in the pit at " + i + "," + k);
                assertNull(at(x, FEET + 2, z), "open over it");
                WalkGrid.Cell c = GRID.at(x + 0.5, FEET + CARPET, z + 0.5);
                assertNotNull(GRID.path(c, door, Set.of()), "and walked out of again from " + i + "," + k);
                inside++;
            }
        }
        assertEquals(26, inside, "a five by five pit and its gap");
        // the gap is the way in: a walk from the middle of the pit to the archway goes through it
        WalkGrid.Cell middle = GRID.at(AX + ClubhousePlanner.PIT_GAP_X + 0.5, FEET + CARPET,
                z0 + (ClubhousePlanner.PIT_Z0 + ClubhousePlanner.PIT_Z1) / 2 + 0.5);
        List<WalkGrid.Cell> out = GRID.path(middle, door, Set.of());
        assertTrue(out.stream().anyMatch(c -> c.x() == AX + ClubhousePlanner.PIT_GAP_X
                && c.z() == z0 + ClubhousePlanner.PIT_Z0), "out through the gap");
        // the rim isn't a floor anyone walks onto
        WalkGrid.Cell rim = GRID.at(AX + ClubhousePlanner.PIT_X0 + 0.5, FEET + 1 + CARPET,
                z0 + ClubhousePlanner.PIT_Z0 + 2.5);
        assertTrue(rim == null || GRID.floor(rim) != GRID.floor(door), "no walk along the wall");
        // full of balls: two layers over every block of its floor and a heap in the middle, round from every side
        List<ClubhousePlanner.Prop> balls = ClubhousePlanner.decor(BOX, ClubhousePlanner.Rooms.BOTH).stream()
                .filter(p -> p.key().startsWith("arcade.ball.")).toList();
        assertTrue(balls.size() >= 100, "full: " + balls.size());
        Set<String> colours = new HashSet<>();
        for (ClubhousePlanner.Prop p : balls) {
            assertFalse(p.block(), "an item display");
            assertEquals("ball", p.look(), "turned wholly to whoever looks");
            assertNotNull(Material.matchMaterial(p.what()), p.what());
            colours.add(p.what());
            assertTrue(p.x() > AX + ClubhousePlanner.PIT_X0 + 1 && p.x() < AX + ClubhousePlanner.PIT_X1, "in the pit");
            assertTrue(p.z() > z0 + ClubhousePlanner.PIT_Z0 + 1 && p.z() < z0 + ClubhousePlanner.PIT_Z1, "in the pit");
            assertTrue(p.y() > FEET + CARPET && p.y() + p.sy() / 2 < FEET + 1 + CARPET, "under the rim: " + p);
        }
        assertTrue(colours.size() >= 6, "every colour: " + colours);
        assertTrue(balls.stream().mapToDouble(ClubhousePlanner.Prop::y).max().orElse(0) - FEET > 0.6,
                "deep enough to look full");
    }

    @Test
    void theBeanBagsAreBrightRoundedSeatsRoundALowTable() {
        List<ClubhousePlanner.Seat> seats = ClubhousePlanner.arcadeSeats(BOX);
        List<ClubhousePlanner.Prop> decor = ClubhousePlanner.decor(BOX, ClubhousePlanner.Rooms.BOTH);
        WalkGrid.Cell door = archway();
        int tx = AX + ClubhousePlanner.BEAN_TABLE[0];
        int tz = BOX.minZ() + ClubhousePlanner.BEAN_TABLE[1];
        assertEquals(ClubhousePlanner.BEAN_TABLE_BLOCK, at(tx, FEET, tz), "a low table (half a block high)");
        Set<String> woods = new HashSet<>();
        for (int n = 0; n < ClubhousePlanner.BEAN_BAGS.length; n++) {
            int[] bag = ClubhousePlanner.BEAN_BAGS[n];
            int x = AX + bag[0];
            int z = BOX.minZ() + bag[1];
            String block = at(x, FEET, z);
            assertTrue(block.contains("_stairs") && block.contains("half=bottom") && block.contains("shape=straight"),
                    "a low seat with a back: " + block);
            woods.add(ClubhousePlanner.id(block));
            assertEquals(1, Math.abs(x - tx) + Math.abs(z - tz), "beside the table");
            int bx = Integer.signum(x - tx);
            int bz = Integer.signum(z - tz);
            String facing = bx < 0 ? "west" : bx > 0 ? "east" : bz < 0 ? "north" : "south";
            assertTrue(block.contains("facing=" + facing), "its back away from the table: " + block);
            assertNull(at(x, FEET + 1, z), "nothing over it");
            assertTrue(lightAt(x, FEET + 1, z) >= LIT, "lit");
            // a child sits in it (a cushion's place, facing the table), on foot from the archway
            ClubhousePlanner.Seat s = seats.stream().filter(t -> Math.floor(t.x()) == x && Math.floor(t.z()) == z)
                    .findFirst().orElseThrow();
            assertEquals(ClubhousePlanner.BAG_DYE[bag[2]], s.color());
            assertEquals(WallBoards.snap((float) Math.toDegrees(Math.atan2(-(tx - x), tz - z))), WallBoards.snap(s.yaw()),
                    "facing the table: " + s);
            WalkGrid.Cell c = GRID.at(s.x(), s.y(), s.z());
            assertTrue(c != null && c.seat(), "a seat on the grid");
            assertNotNull(GRID.path(door, c, Set.of()), "sat in from the archway");
            // rounded off: wool round its foot a little wider than its block, a padded back and a roll on top
            int bag0 = n;
            List<ClubhousePlanner.Prop> soft = decor.stream().filter(p -> p.key().startsWith("arcade.bag." + bag0 + "."))
                    .toList();
            assertEquals(3, soft.size(), "base, back and roll: " + soft);
            for (ClubhousePlanner.Prop p : soft) {
                assertTrue(p.block() && p.what().equals(ClubhousePlanner.BAG_WOOL[bag[2]]), "its own wool: " + p);
                assertTrue(p.x() >= x - 0.1 && p.x() + p.sx() <= x + 1.1 && p.z() >= z - 0.1 && p.z() + p.sz() <= z + 1.1,
                        "round its block: " + p);
                assertTrue(p.y() + p.sy() <= FEET + 1.1, "low: " + p);
            }
            ClubhousePlanner.Prop base = soft.get(0);
            assertTrue(base.sx() > 1 && base.sz() > 1 && base.y() + base.sy() < FEET + 0.5, "it bulges, its seat"
                    + " showing: " + base);
        }
        assertEquals(4, woods.size(), "four bright colours: " + woods);
        // no two side by side
        for (int[] a : ClubhousePlanner.BEAN_BAGS) {
            for (int[] b : ClubhousePlanner.BEAN_BAGS) {
                assertNotEquals(1, Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]), "never a bench");
            }
        }
        // the nook: a pink rug, two lamps on the wall over the bags and a coloured one over the table, and the
        // corner behind the bags open to walk round (every rug cell on the Arcade Room's floor)
        for (int k : ClubhousePlanner.NOOK_LAMP_Z) {
            int z = BOX.minZ() + k;
            assertTrue(at(AX + ClubhousePlanner.NOOK_X0, FLOOR + ClubhousePlanner.NOOK_LAMP_Y, z).contains("_fence"),
                    "a lamp's arm out of the wall");
            assertEquals(ClubhousePlanner.HANGING, at(AX + ClubhousePlanner.NOOK_X0,
                    FLOOR + ClubhousePlanner.NOOK_LAMP_Y - 1, z), "and its lantern");
        }
        for (int i = ClubhousePlanner.NOOK_X0; i <= ClubhousePlanner.NOOK_X1; i++) {
            for (int k = ClubhousePlanner.NOOK_Z0; k <= ClubhousePlanner.SIDE_Z1; k++) {
                if (ClubhousePlanner.NOOK_RUG.equals(at(AX + i, FEET, BOX.minZ() + k))) {
                    standsAndIsReached(AX + i, FEET, BOX.minZ() + k, door, "the nook's rug");
                }
            }
        }
        assertEquals(15, ClubhousePlanner.lightOf(at(tx, FLOOR + ClubhousePlanner.SIDE_TOP - 1, tz)), "over the table");
        assertEquals(ClubhousePlanner.NOOK_RUG, at(AX + 1, FEET, BOX.minZ() + ClubhousePlanner.NOOK_Z0));
        assertEquals(7, seats.size(), "the four bags and the three viewing stools");
        assertTrue(ClubhousePlanner.arcadeSeats(OLD).isEmpty(), "none at the old size");
    }

    @Test
    void thePrizeAndPlushCornerHasACaseWithShelvesPlushiesRibbonsTagsAndACounter() {
        List<ClubhousePlanner.Prop> decor = ClubhousePlanner.decor(BOX, ClubhousePlanner.Rooms.BOTH);
        WalkGrid.Cell door = archway();
        for (int n = 0; n < ClubhousePlanner.PLUSH_CASE.length; n++) {
            int[] c = ClubhousePlanner.PLUSH_CASE[n];
            int x = AX + c[0];
            int z = BOX.minZ() + c[1];
            assertEquals(ClubhousePlanner.CABINET, at(x, FEET, z), "a dark-wood base");
            assertEquals(ClubhousePlanner.ROOF_GLASS, at(x, FEET + 1, z), "glass");
            assertEquals(ClubhousePlanner.ROOF_GLASS, at(x, FEET + 2, z), "two high");
            assertEquals(15, ClubhousePlanner.lightOf(at(x, FEET + 3, z)), "lit from its cap");
            assertTrue(lightAt(x, FEET + 2, z) >= 13 && lightAt(x, FEET + 1, z) >= 12, "light inside the case");
            int n0 = n;
            ClubhousePlanner.Prop low = one(decor, "arcade.plush." + n0 + ".low");
            ClubhousePlanner.Prop high = one(decor, "arcade.plush." + n0 + ".high");
            ClubhousePlanner.Prop shelf = one(decor, "arcade.shelf." + n0);
            ClubhousePlanner.Prop ribbon = one(decor, "arcade.ribbon." + n0);
            for (ClubhousePlanner.Prop p : List.of(low, high)) {
                assertTrue(p.x() > x && p.x() < x + 1 && p.z() > z && p.z() < z + 1, "inside its case: " + p);
                assertNotNull(Material.matchMaterial(p.what()), p.what());
            }
            assertTrue(low.y() > FEET + 1 && low.y() < FEET + 2, "the lower shelf: " + low);
            assertTrue(high.y() > FEET + 2 && high.y() < FEET + 3, "the upper shelf: " + high);
            assertTrue(low.what().endsWith("_HEAD") || low.what().endsWith("_SKULL"), "a mob-head plush below");
            assertTrue(shelf.block() && Math.abs(shelf.y() - (FEET + 2)) < 1e-9 && shelf.sy() < 0.1, "a thin shelf");
            assertTrue(ribbon.block() && ribbon.what().endsWith("_WOOL") && ribbon.sy() < 0.1, "a ribbon: " + ribbon);
            assertTrue(Math.abs(ribbon.y() - (FEET + 1)) < 1e-9, "along the base's front edge");
        }
        assertTrue(decor.stream().filter(p -> p.key().startsWith("arcade.tag.")).count() >= 2, "price tags");
        int cz = BOX.minZ() + ClubhousePlanner.PLUSH_COUNTER_Z;
        for (int i : ClubhousePlanner.PLUSH_COUNTER_X) {
            assertEquals(ClubhousePlanner.CABINET, at(AX + i, FEET, cz), "the counter");
        }
        ClubhousePlanner.Prop bell = one(decor, "arcade.bell");
        assertTrue(bell.y() > FEET + 1 && bell.y() < FEET + 1.5 && Math.floor(bell.z()) == cz, "a bell on the counter");
        // the case is looked at from the room: in front of its side part, and behind the counter
        for (int k = ClubhousePlanner.PLUSH_CASE[0][1]; k < ClubhousePlanner.PLUSH_COUNTER_Z; k++) {
            standsAndIsReached(AX + 13, FEET, BOX.minZ() + k, door, "in front of the case");
        }
        standsAndIsReached(AX + 12, FEET, cz + 1, door, "behind the counter");
    }

    private static ClubhousePlanner.Prop one(List<ClubhousePlanner.Prop> decor, String key) {
        return decor.stream().filter(p -> p.key().equals(key)).findFirst().orElseThrow(() -> new AssertionError(key));
    }

    @Test
    void theKidRaceHasAViewingArea() {
        WalkGrid.Cell door = archway();
        int south = BOX.minZ() + ClubhousePlanner.KID_TRACK_Z0 + RaceTrack.LENGTH - 1;
        int row = BOX.minZ() + ClubhousePlanner.VIEW_Z0 + 1;
        List<ClubhousePlanner.Seat> seats = ClubhousePlanner.arcadeSeats(BOX);
        for (int i : ClubhousePlanner.VIEW_STOOL_X) {
            int x = AX + i;
            assertEquals(ClubhousePlanner.VIEW_STOOL, at(x, FEET, row), "a stool");
            ClubhousePlanner.Seat s = seats.stream().filter(t -> Math.floor(t.x()) == x && Math.floor(t.z()) == row)
                    .findFirst().orElseThrow();
            assertEquals(180f, s.yaw(), "facing north, at the table");
            assertTrue(lightAt(x, FEET + 1, row) >= LIT);
            assertNotNull(GRID.path(door, GRID.at(s.x(), s.y(), s.z()), Set.of()), "sat on from the archway");
            for (int z = south + 1; z < row; z++) {
                assertTrue(carpetOrAir(at(x, FEET, z)) && at(x, FEET + 1, z) == null, "nothing between it and the"
                        + " table at " + where(x, FEET, z));
            }
        }
        for (int i = ClubhousePlanner.KID_TRACK_X0 - 2; i <= ClubhousePlanner.KID_TRACK_X0 + RaceTrack.DEPTH + 1; i++) {
            if (at(AX + i, FEET, row - 1) != null) {
                assertEquals(ClubhousePlanner.VIEW_RUG, at(AX + i, FEET, row - 1), "its rug");
            }
            standsAndIsReached(AX + i, FEET, row - 1, door, "the viewing area's front row");
        }
    }

    // ---- the Game Parlor ------------------------------------------------------------------------------------------

    @Test
    void theParlorHasALoungeAndRoomForTwoMoreCardTables() {
        WalkGrid.Cell up = WAYS.upTo();
        assertNotNull(up);
        int x0 = BOX.minX() + ClubhousePlanner.ARCADE_X0;
        for (int k : ClubhousePlanner.LOUNGE_CHAIR_Z) {
            String chair = at(x0 + ClubhousePlanner.LOUNGE_CHAIR_X, UP_FEET, BOX.minZ() + k);
            assertEquals(ClubhousePlanner.chair("west"), chair, "an armchair facing east, across to the board");
            assertTrue(at(x0 + ClubhousePlanner.LOUNGE_CHAIR_X, UP_FEET, BOX.minZ() + k - 1).contains("_trapdoor"));
            assertTrue(at(x0 + ClubhousePlanner.LOUNGE_CHAIR_X, UP_FEET, BOX.minZ() + k + 1).contains("_trapdoor"));
        }
        for (int[] t : ClubhousePlanner.LOUNGE_TABLES) {
            assertTrue(at(x0 + t[0], UP_FEET, BOX.minZ() + t[1]).contains("_fence"), "a side table's post");
            assertEquals(ClubhousePlanner.SIDE_TABLE_TOP, at(x0 + t[0], UP_FEET + 1, BOX.minZ() + t[1]), "its top");
        }
        for (int[] l : ClubhousePlanner.LOUNGE_LAMPS) {
            assertEquals(ClubhousePlanner.STANDING, at(x0 + l[0], UP_FEET + 1, BOX.minZ() + l[1]), "a floor lamp");
        }
        // room for two more card tables: their felt's and their chairs' places plain carpet, lit, walked to
        for (int[] t : ClubhousePlanner.PARLOR_SPARE_TABLE) {
            for (int k = t[1]; k < t[1] + ClubhousePlanner.PARLOR_TABLE_D; k++) {
                for (int i = t[0] - 1; i <= t[0] + ClubhousePlanner.PARLOR_TABLE_W; i++) {
                    int x = x0 + i;
                    int z = BOX.minZ() + k;
                    assertEquals(ClubhousePlanner.PARLOR_CARPET, at(x, UP_FEET, z), "plain carpet at " + where(x, 0, z));
                    standsAndIsReached(x, UP_FEET, z, up, "a spare table's place");
                }
            }
            for (ClubhousePlanner.Place3 m : ClubhousePlanner.parlorMachines(BOX)) {
                assertTrue(Math.abs(m.z() - (BOX.minZ() + t[1])) >= 3 || Math.abs(m.x() - (x0 + t[0])) >= 3,
                        "clear of the tables there are: " + m);
            }
        }
        assertEquals(ClubhousePlanner.PARLOR_TABLE[0][0], ClubhousePlanner.PARLOR_SPARE_TABLE[0][0], "in line with them");
        assertEquals(ClubhousePlanner.PARLOR_TABLE[1][0], ClubhousePlanner.PARLOR_SPARE_TABLE[1][0]);
    }

    @Test
    void theSportsbookHasABigBoardOnTheEastWallWithACounterAndStools() {
        assertEquals(List.of("sportsbook"), ClubhousePlanner.PARLOR_BOARD_GAMES);
        List<ClubhousePlanner.Board> boards = ClubhousePlanner.parlorBoards(BOX);
        assertEquals(1, boards.size());
        assertTrue(ClubhousePlanner.parlorBoards(OLD).isEmpty(), "no room for it at the old size");
        ClubhousePlanner.Board bd = boards.get(0);
        assertEquals("sportsbook", bd.game());
        assertEquals(ClubhousePlanner.BOARD_W, bd.width());
        assertTrue(bd.width() >= 5 && bd.height() >= 3, "a big board: " + bd);
        ClubhousePlanner.Place3 at = bd.at();
        int x = (int) Math.floor(at.x());
        int z = (int) Math.floor(at.z());
        assertEquals(90f, at.yaw(), "facing west, into the room");
        assertEquals(UP_FEET, at.y(), 1e-9, "on the Parlor's floor");
        assertTrue(ClubhousePlanner.parlorInside(BOX).contains(x, UP_FEET, z), "in the Parlor");
        // the wall behind it, its whole width and height, with nothing in front of it to hide it but air
        double[] g = SideRooms.boardBox(at, bd.width(), bd.height());
        assertEquals(x + 1.0, g[0] + g[3], 1e-9, "flat on the east wall's face");
        assertTrue(g[3] < 0.1, "thin");
        assertEquals(bd.width(), g[5], 1e-9, "its width along the wall");
        assertEquals(z + 0.5, g[2] + g[5] / 2, 1e-9, "centred on its place");
        assertTrue(g[1] >= UP_FEET + 1 - 1e-9 && g[1] + g[4] <= BOX.minY() + ClubhousePlanner.UP_TOP + 1 - 1e-9,
                "between the counter's top and the ceiling");
        for (int zz = (int) Math.floor(g[2]); zz < g[2] + g[5]; zz++) {
            for (int y = UP_FEET; y <= BOX.minY() + ClubhousePlanner.UP_TOP; y++) {
                assertNotNull(at(x + 1, y, zz), "the wall behind the board at " + where(x + 1, y, zz));
                if (y >= g[1] && y < g[1] + g[4]) {
                    assertNull(at(x, y, zz), "air where the board hangs at " + where(x, y, zz));
                }
            }
        }
        for (int j = 0; j < 2; j++) {
            assertNull(at(x, UP_FEET + j, z), "its place is air (a display: no block is the board's)");
        }
        // the counter in front, the stools in front of that facing the board, sat on from the Parlor's way in
        List<ClubhousePlanner.Seat> seats = ClubhousePlanner.parlorSeats(BOX);
        for (int k = ClubhousePlanner.BOOK_COUNTER_Z0; k <= ClubhousePlanner.BOOK_COUNTER_Z1; k++) {
            assertEquals(ClubhousePlanner.CABINET, at(x - 1, UP_FEET, BOX.minZ() + k), "the counter");
            assertTrue(Math.abs(BOX.minZ() + k + 0.5 - at.z()) <= bd.width() / 2.0, "in front of the board");
        }
        int stools = 0;
        for (int k : ClubhousePlanner.BOOK_STOOL_Z) {
            int sz = BOX.minZ() + k;
            assertEquals(ClubhousePlanner.STOOL, at(x - 2, UP_FEET, sz), "a stool");
            ClubhousePlanner.Seat s = seats.stream().filter(t -> Math.floor(t.x()) == x - 2 && Math.floor(t.z()) == sz)
                    .findFirst().orElseThrow();
            assertEquals(-90f, s.yaw(), "facing east, at the board");
            assertTrue(lightAt(x - 2, UP_FEET + 1, sz) >= LIT);
            assertNotNull(GRID.path(WAYS.upTo(), GRID.at(s.x(), s.y(), s.z()), Set.of()), "reached");
            stools++;
        }
        assertEquals(3, stools);
        // clear of every other machine in the Parlor
        List<ClubhousePlanner.Place3> others = new ArrayList<>(ClubhousePlanner.parlorMachines(BOX));
        others.addAll(ClubhousePlanner.parlorRow(BOX));
        others.add(ClubhousePlanner.parlorTicket(BOX));
        for (ClubhousePlanner.Place3 o : others) {
            assertTrue(Math.abs(o.z() - at.z()) > bd.width() / 2.0 + 1 || Math.abs(o.x() - at.x()) >= 3,
                    "clear of " + o);
        }
        // the board's geometry from any wall (the same rule turned): flat on the wall behind its place, centred
        for (float yaw : new float[]{0f, 90f, 180f, -90f}) {
            ClubhousePlanner.Place3 p = new ClubhousePlanner.Place3(10.5, 70, 20.5, yaw);
            double[] b = SideRooms.boardBox(p, 5, 3);
            double fx = -Math.round(Math.sin(Math.toRadians(yaw)));
            double fz = Math.round(Math.cos(Math.toRadians(yaw)));
            double behindX = 10.5 - fx * 0.5;
            double behindZ = 20.5 - fz * 0.5;
            assertTrue(b[0] <= behindX + 1e-9 && b[0] + b[3] >= behindX - 1e-9 && b[2] <= behindZ + 1e-9
                    && b[2] + b[5] >= behindZ - 1e-9, "on the wall behind it, yaw " + yaw);
            assertEquals(5 + SideRooms.BOARD_THICK, b[3] + b[5], 1e-9, "five wide, thin, yaw " + yaw);
            assertEquals(fx, b[10], 1e-9);
            assertEquals(fz, b[11], 1e-9);
        }
    }

    // ---- every place -----------------------------------------------------------------------------------------------

    /**
     * Every cabinet place and its standing spot, every Parlor machine's and booth's and the board's, every pedestal's
     * trophy and head, and every seat the room has (downstairs, the side rooms and upstairs) is in the room, free, lit,
     * and reached on foot: downstairs from the Arcade Room's archway (and so the Foyer), upstairs from the Parlor's way
     * in.
     */
    @Test
    void everyPlaceStandingSpotAndSeatIsInTheRoomFreeLitAndWalkedTo() {
        WalkGrid.Cell door = archway();
        WalkGrid.Cell up = WAYS.upTo();
        assertEquals(GRID.floor(WAYS.door()), GRID.floor(door), "the Arcade Room is on the Foyer's floor");
        Box arcade = ClubhousePlanner.arcadeInside(BOX);
        int checked = 0;
        for (ClubhousePlanner.Place3 c : ClubhousePlanner.cabinets(BOX)) {
            int x = (int) Math.floor(c.x());
            int z = (int) Math.floor(c.z());
            assertTrue(arcade.contains(x, FEET, z) && arcade.contains(x, FEET + 1, z), "in the Arcade Room: " + c);
            assertNull(at(x, FEET, z));
            assertNull(at(x, FEET + 1, z));
            assertTrue(lightAt(x, FEET, z) >= LIT, "lit: " + c);
            double rad = Math.toRadians(c.yaw());
            standsAndIsReached((int) Math.floor(c.x() - Math.sin(rad)), FEET, (int) Math.floor(c.z() + Math.cos(rad)),
                    door, "in front of the cabinet at " + c);
            checked++;
        }
        Box parlor = ClubhousePlanner.parlorInside(BOX);
        List<ClubhousePlanner.Place3> machines = new ArrayList<>(ClubhousePlanner.parlorMachines(BOX).subList(0,
                ClubhousePlanner.PARLOR_WALL.length));
        machines.addAll(ClubhousePlanner.parlorRow(BOX));
        machines.add(ClubhousePlanner.parlorTicket(BOX));
        for (ClubhousePlanner.Board bd : ClubhousePlanner.parlorBoards(BOX)) {
            machines.add(bd.at());
        }
        for (ClubhousePlanner.Place3 m : machines) {
            int x = (int) Math.floor(m.x());
            int z = (int) Math.floor(m.z());
            assertTrue(parlor.contains(x, UP_FEET, z), "in the Parlor: " + m);
            assertNull(at(x, UP_FEET, z), "air: " + m);
            assertTrue(lightAt(x, UP_FEET, z) >= LIT, "lit: " + m);
            double rad = Math.toRadians(m.yaw());
            int fx = (int) Math.floor(m.x() - Math.sin(rad));
            int fz = (int) Math.floor(m.z() + Math.cos(rad));
            if (ClubhousePlanner.parlorBoards(BOX).stream().anyMatch(b -> b.at().equals(m))) {
                standsAndIsReached(x, UP_FEET, z - 3, up, "the board's aisle"); // the counter is in front of it
            } else {
                standsAndIsReached(fx, UP_FEET, fz, up, "in front of " + m);
            }
            checked++;
        }
        Box trophy = ClubhousePlanner.trophyInside(BOX);
        for (ClubhousePlanner.Pedestal p : ClubhousePlanner.pedestals(BOX)) {
            for (ClubhousePlanner.Place3 c : List.of(p.trophy(), p.head())) {
                int x = (int) Math.floor(c.x());
                int y = (int) Math.floor(c.y());
                int z = (int) Math.floor(c.z());
                assertTrue(trophy.contains(x, y, z) && at(x, y, z) == null, "in the Trophy Room's air: " + c);
                assertTrue(lightAt(x, y, z) >= LIT, "lit: " + c);
            }
            checked++;
        }
        List<ClubhousePlanner.Seat> down = new ArrayList<>(ClubhousePlanner.seats(BOX));
        down.addAll(ClubhousePlanner.arcadeSeats(BOX));
        down.addAll(ClubhousePlanner.trophySeats(BOX));
        for (boolean upstairs : new boolean[]{false, true}) {
            for (ClubhousePlanner.Seat s : upstairs ? ClubhousePlanner.parlorSeats(BOX) : down) {
                int x = (int) Math.floor(s.x());
                int y = (int) Math.floor(s.y());
                int z = (int) Math.floor(s.z());
                assertTrue(SITE.in(s.x(), s.y(), s.z()), "in the room: " + s);
                assertTrue(RegularSpots.seatBlock(at(x, y, z)), "on a seat: " + s);
                assertNull(at(x, y + 1, z), "nothing over it: " + s);
                assertTrue(lightAt(x, y + 1, z) >= LIT, "lit: " + s);
                WalkGrid.Cell c = GRID.at(s.x(), s.y(), s.z());
                assertTrue(c != null && c.seat(), "a seat on the grid: " + s);
                assertNotNull(GRID.path(upstairs ? up : door, c, Set.of()), "sat on, on foot: " + s);
                checked++;
            }
        }
        assertTrue(checked > 80, "checked " + checked);
    }
}
