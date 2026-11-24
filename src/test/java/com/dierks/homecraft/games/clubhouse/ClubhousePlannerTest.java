package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.arcade.feast.FeastRules;
import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.LegacyBoxes;
import com.dierks.homecraft.games.gen.api.Plan;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The generated Clubhouse, plan 4 (0.40.0, the golf club; CLUBHOUSE-SPEC §1, §8): pinned by its hash, every block
 * inside its box, a podium of three distinct heights with 1st on the middle line, sixteen arrival spots on the foyer's
 * marble, every place a mob could stand lit (the light flood), no way out, chairs with gaps (never a bench), the hall
 * and the foyer mirror-symmetric, the banquet tables with their twenty plates, the 3 x 3 tables with the Feast Pot in
 * the middle of one, the jukebox in its nook, the trophy case on its cabinet, a cushion's place on every seat, and
 * every block a block of the 26.2 API.
 */
class ClubhousePlannerTest {

    /**
     * The 0.35 box's corner, plan 13's depth (the side rooms' grown wing): the golden hash names blocks where they
     * stand, so it never follows the shipped origin.
     */
    private static final Box MAIN = LegacyBoxes.clubhouse();
    private static final Box BOX = Box.sized(MAIN.minX(), MAIN.minY(), MAIN.minZ(), 32, 16, ClubhouseSettings.SIZE_Z);
    private static final ClubhouseSite SITE = ClubhousePlanner.plan(BOX);
    /** Plan 13's room, pinned; and plan 12's, which a wing kept at its old size (48 deep) still is, block for block. */
    static final String HASH = "4ec2e9c88197";
    static final String OLD_HASH = "f483b6018e6f";
    /** The box at its old size: the main room and the wing's first 16 rows. */
    static final Box OLD = Box.sized(MAIN.minX(), MAIN.minY(), MAIN.minZ(), 32, 16, ClubhouseSettings.OLD_SIZE_Z);
    /** The least block light over anything a mob could stand on: spawning needs 0; a margin, and it reads lit. */
    private static final int LIT = 4;

    /** Every block of the plan by position ({@code null}: air). */
    private static Map<Long, String> blocks(Plan plan) {
        Map<Long, String> out = new HashMap<>();
        for (BlockOp op : plan.ops()) {
            out.put(key(op.x(), op.y(), op.z()), plan.blockOf(op));
        }
        return out;
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    private static String at(Map<Long, String> b, double x, double y, double z) {
        return b.get(key((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)));
    }

    /** The block at box offset (i, j, k). */
    private static String rel(Map<Long, String> b, int i, int j, int k) {
        return b.get(key(BOX.minX() + i, BOX.minY() + j, BOX.minZ() + k));
    }

    private static int x(long key) {
        return (int) (key >> 38);
    }

    private static int y(long key) {
        return (int) ((key << 52) >> 52);
    }

    private static int z(long key) {
        return (int) ((key << 26) >> 38);
    }

    private static String id(String state) {
        return state == null ? "minecraft:air" : ClubhousePlanner.id(state);
    }

    @Test
    void theGoldenHashPinsTheRoom() {
        assertEquals(HASH, SITE.plan().hash(), "the Clubhouse's layout is pinned: a change is a decision"
                + " (and then ClubhousePlanner.ALGO goes up, so every server rebuilds it once)");
        assertEquals(SITE.plan().hash(), ClubhousePlanner.plan(BOX).plan().hash(), "the same box always gives the same room");
        Box moved = BOX.translate(160, 0, -64);
        assertEquals(SITE.plan().ops().size(), ClubhousePlanner.plan(moved).plan().ops().size(),
                "moving the box moves the same room");
        assertNotEquals(SITE.plan().hash(), ClubhousePlanner.plan(moved).plan().hash(), "a hash names the blocks where"
                + " they are");
        assertEquals(13, ClubhousePlanner.ALGO, "plan 13, the side rooms' wing grown and filled: every server builds"
                + " it once");
        assertEquals(OLD_HASH, ClubhousePlanner.plan(OLD).plan().hash(), "a wing kept at its old size is plan 12's"
                + " room, block for block");
    }

    @Test
    void everyBlockAndSpotIsInsideTheBox() {
        Set<Long> seen = new HashSet<>();
        for (BlockOp op : SITE.plan().ops()) {
            assertTrue(BOX.contains(op.x(), op.y(), op.z()), "block " + op + " is inside the box");
            assertTrue(seen.add(key(op.x(), op.y(), op.z())), "one op per block: " + op);
        }
        assertEquals(BOX, SITE.box(), "the plan's half is the box");
        for (ClubhouseSite.Spot s : SITE.spawns()) {
            assertTrue(SITE.in(s.x(), s.y(), s.z()), "arrival " + s + " is in the room's air");
        }
        for (ClubhouseSite.Spot s : SITE.podium()) {
            assertTrue(SITE.in(s.x(), s.y(), s.z()), "podium " + s + " is in the room");
        }
        assertTrue(SITE.in(SITE.board().x(), SITE.board().y(), SITE.board().z()), "the board floats in the room");
        assertThrows(IllegalArgumentException.class, () -> ClubhousePlanner.plan(Box.sized(0, 64, 0, 48, 40, 48)),
                "the room is made only for its own size");
    }

    /** Every block id is a block this jar's API (26.2) knows, so the room builds on 26.2 and 26.3 alike. */
    @Test
    void everyBlockIsABlockOfThisApi() {
        for (String state : SITE.plan().palette()) {
            String name = id(state).substring("minecraft:".length()).toUpperCase(Locale.ROOT);
            Material m = Material.getMaterial(name);
            assertNotNull(m, "a block of the 26.2 API: " + state);
            assertFalse(name.contains("CUSHION") || name.contains("POPLAR") || name.endsWith("WOOL_STAIRS"),
                    "nothing only 26.3 has: " + state);
        }
    }

    /**
     * 0.42.0: the podium left the Great Hall (a hedge stands under the results board now) and "Photo time!" is on the
     * Trophy Room's podium: three heights, 1st in the room's middle and highest, each place in the middle of its plinth
     * (gold, iron, copper), with air for a player, facing the archway. Without a Trophy Room it is the hall's green.
     */
    @Test
    void thePodiumIsInTheTrophyRoomThreeHeightsFirstHighest() {
        Map<Long, String> b = blocks(SITE.plan());
        List<ClubhouseSite.Spot> p = SITE.podium();
        assertEquals(3, p.size(), "1st, 2nd and 3rd");
        assertTrue(p.get(0).y() > p.get(1).y() && p.get(1).y() > p.get(2).y(), "three heights, 1st highest: " + p);
        Box room = ClubhousePlanner.trophyInside(BOX);
        assertEquals((room.minX() + room.maxX() + 1) / 2.0, p.get(0).x(), 1e-9, "1st in the Trophy Room's middle");
        String[] tops = {ClubhousePlanner.GOLD, ClubhousePlanner.IRON, ClubhousePlanner.COPPER};
        for (int n = 0; n < 3; n++) {
            ClubhouseSite.Spot s = p.get(n);
            assertTrue(room.contains(s.x(), s.y(), s.z()), "in the Trophy Room: " + s);
            for (double dx : new double[]{-0.5, 0.5}) {
                assertEquals(tops[n], at(b, s.x() + dx, s.y() - 1, s.z()), "on its plinth: " + s);
                assertNull(at(b, s.x() + dx, s.y(), s.z()), "air at the feet");
                assertNull(at(b, s.x() + dx, s.y() + 1, s.z()), "and at the head");
            }
            assertEquals(180f, s.yaw(), "facing the archway");
        }
        // the Great Hall has no podium now: the hedge under the board, the green's floor clear
        for (int i = 11; i <= 20; i++) {
            for (int k = 2; k <= 4; k++) {
                String g = rel(b, i, 1, k);
                assertTrue(g != null && g.equals(ClubhousePlanner.STAGE_RUG), "the green, nothing on it at " + i + "," + k);
                assertNull(rel(b, i, 2, k), "nothing standing on the green");
            }
        }
        for (int i = ClubhousePlanner.HEDGE_X0; i <= 31 - ClubhousePlanner.HEDGE_X0; i++) {
            assertEquals(ClubhousePlanner.PLANTER, rel(b, i, 1, 1), "the hedge under the results board");
            assertEquals(ClubhousePlanner.SHRUB, rel(b, i, 2, 1));
        }
        for (String state : SITE.plan().palette()) {
            if (state.equals(ClubhousePlanner.GOLD)) {
                for (BlockOp op : SITE.plan().ops()) {
                    if (SITE.plan().blockOf(op).equals(ClubhousePlanner.GOLD)) {
                        assertTrue(op.z() - BOX.minZ() >= ClubhousePlanner.MAIN_Z, "gold only in the Trophy Room: " + op);
                    }
                }
            }
        }
        List<ClubhouseSite.Spot> alone = ClubhousePlanner.plan(MAIN).podium();
        assertEquals(BOX.minX() + 16.0, alone.get(0).x(), 1e-9, "no Trophy Room: 1st on the green's middle line");
        assertEquals(BOX.minY() + 1.0, alone.get(0).y(), 1e-9, "on the floor");
    }

    @Test
    void sixteenArrivalSpotsOnTheFoyersMarbleEachWithTwoAirAboveAndNoneStacked() {
        Map<Long, String> b = blocks(SITE.plan());
        assertEquals(16, SITE.spawns().size(), "sixteen players arrive without stacking");
        Set<String> seen = new HashSet<>();
        for (ClubhouseSite.Spot s : SITE.spawns()) {
            assertTrue(seen.add((int) Math.floor(s.x()) + "," + (int) Math.floor(s.z())), "no two spots share a block");
            String floor = at(b, s.x(), s.y() - 1, s.z());
            assertTrue(ClubhousePlanner.MARBLE_WHITE.equals(floor) || ClubhousePlanner.MARBLE_BLACK.equals(floor),
                    "the foyer's marble under " + s + ": " + floor);
            assertNull(at(b, s.x(), s.y(), s.z()), "air at the feet of " + s);
            assertNull(at(b, s.x(), s.y() + 1, s.z()), "air at the head of " + s);
            assertEquals(180f, s.yaw(), "facing the podium");
            for (ClubhouseSite.Spot o : SITE.spawns()) {
                if (o != s) {
                    assertTrue(Math.hypot(o.x() - s.x(), o.z() - s.z()) >= 3, "spots are at least 3 apart: " + s + " "
                            + o);
                }
            }
            double mirror = 2 * (BOX.minX() + 16.0) - s.x();
            assertTrue(SITE.spawns().stream().anyMatch(o -> Math.abs(o.x() - mirror) < 1e-9 && o.z() == s.z()),
                    "the spots mirror about the middle line: " + s);
        }
    }

    /**
     * Plan 3 was glass so that nothing a mob could stand on was open to the air. Plan 4 is lit instead: block light is
     * flooded from every lantern, sea lantern, candle and fire through the blocks light passes (never through a slab, a
     * stair or a trapdoor, so it never counts more than the game gives), and every place a mob could stand in the room
     * has at least {@value #LIT}. Mobs spawn only at block light 0.
     */
    @Test
    void everyPlaceAMobCouldStandIsLitDayAndNight() {
        Map<Long, String> b = blocks(SITE.plan());
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
            if (light.getOrDefault(key(c[0], c[1], c[2]), 0) > c[3]) {
                continue;
            }
            for (int[] d : dirs) {
                int x = c[0] + d[0];
                int y = c[1] + d[1];
                int z = c[2] + d[2];
                if (!BOX.contains(x, y, z) || c[3] <= 1) {
                    continue;
                }
                String n = b.get(key(x, y, z));
                if (n != null && !ClubhousePlanner.letsLight(n)) {
                    continue;
                }
                if (light.getOrDefault(key(x, y, z), 0) < c[3] - 1) {
                    light.put(key(x, y, z), c[3] - 1);
                    q.add(new int[]{x, y, z, c[3] - 1});
                }
            }
        }
        int places = 0;
        int least = 15;
        for (int x = BOX.minX() + 1; x < BOX.maxX(); x++) {
            for (int z = BOX.minZ() + 1; z < BOX.maxZ(); z++) {
                for (int y = BOX.minY() + 1; y <= BOX.minY() + ClubhousePlanner.WALL_TOP; y++) {
                    String here = b.get(key(x, y, z));
                    String below = b.get(key(x, y - 1, z));
                    if (below == null || ClubhousePlanner.noFooting(below)) {
                        continue; // nothing to stand on
                    }
                    if (here != null && !id(here).endsWith("_carpet") && !id(here).equals("minecraft:light")) {
                        continue; // no room to stand (a light block is air: Mob Races' table top is a place too)
                    }
                    places++;
                    int l = light.getOrDefault(key(x, y, z), 0);
                    least = Math.min(least, l);
                    assertTrue(l >= LIT, "lit at " + (x - BOX.minX()) + "," + (y - BOX.minY()) + "," + (z - BOX.minZ())
                            + " over " + below + ": " + l);
                }
            }
        }
        assertTrue(places > 800, "the whole floor and every table top is checked: " + places);
        assertTrue(least >= LIT, "least light " + least);
        // the roofs' tops, outside, are glass: nothing stands on them
        for (int x = BOX.minX(); x <= BOX.maxX(); x++) {
            for (int z = BOX.minZ(); z <= BOX.maxZ(); z++) {
                int roof = z - BOX.minZ() < ClubhousePlanner.MAIN_Z ? ClubhousePlanner.ROOF : ClubhousePlanner.SIDE_ROOF;
                assertTrue(ClubhousePlanner.noFooting(b.get(key(x, BOX.minY() + roof, z))), "glass");
            }
        }
        assertEquals(15, ClubhousePlanner.lightOf(ClubhousePlanner.HANGING));
        assertEquals(9, ClubhousePlanner.lightOf(ClubhousePlanner.CANDLES), "three candles, 3 each");
        assertEquals(0, ClubhousePlanner.lightOf("minecraft:campfire[lit=false]"));
        assertFalse(ClubhousePlanner.letsLight(ClubhousePlanner.STOOL), "a slab stops the flood");
        assertTrue(ClubhousePlanner.noFooting(ClubhousePlanner.HANGING) && !ClubhousePlanner.noFooting(ClubhousePlanner.CLOTH));
    }

    /**
     * No way out: the main room's walls to its glass roof (the Foyer's south wall open only at the two archways, which
     * lead into the side rooms), and each side room closed on its other three sides up to its own glass roof.
     */
    @Test
    void theRoomHasNoWayOut() {
        Map<Long, String> b = blocks(SITE.plan());
        for (int x = BOX.minX(); x <= BOX.maxX(); x++) {
            for (int z = BOX.minZ(); z <= BOX.maxZ(); z++) {
                boolean main = z - BOX.minZ() < ClubhousePlanner.MAIN_Z;
                int roof = main ? ClubhousePlanner.ROOF : ClubhousePlanner.SIDE_ROOF;
                String r = b.get(key(x, BOX.minY() + roof, z));
                assertTrue(ClubhousePlanner.ROOF_GLASS.equals(r) || ClubhousePlanner.TINTED.equals(r),
                        "the roof is closed over " + x + "," + z + " (the Arcade Room's tinted, 0.42.0): " + r);
                assertNotNull(b.get(key(x, BOX.minY(), z)), "the floor is whole at " + x + "," + z);
            }
        }
        int south = BOX.minZ() + ClubhousePlanner.MAIN_Z - 1;
        for (int y = BOX.minY(); y <= BOX.minY() + ClubhousePlanner.WALL_TOP; y++) {
            for (int i = 0; i < ClubhouseSettings.SIZE_X; i++) {
                assertNotNull(b.get(key(BOX.minX() + i, y, BOX.minZ())), "north wall");
                boolean arch = y - BOX.minY() >= 1 && y - BOX.minY() <= ClubhousePlanner.ARCH_H
                        && (i >= ClubhousePlanner.ARCH_ARCADE_X0 && i < ClubhousePlanner.ARCH_ARCADE_X0 + 3
                        || i >= ClubhousePlanner.ARCH_TROPHY_X0 && i < ClubhousePlanner.ARCH_TROPHY_X0 + 3);
                String w = b.get(key(BOX.minX() + i, y, south));
                assertEquals(arch, w == null || id(w).endsWith("_carpet"), "the Foyer's south wall, open only"
                        + " at the archways (a runner on their floor): " + i + "," + (y - BOX.minY()));
                assertNotNull(b.get(key(BOX.minX(), y, BOX.minZ() + i)), "west wall");
                assertNotNull(b.get(key(BOX.maxX(), y, BOX.minZ() + i)), "east wall");
            }
        }
        // the side rooms: the west, middle (two walls) and east walls, and the south wall, up to their roof
        for (int y = BOX.minY(); y <= BOX.minY() + ClubhousePlanner.SIDE_TOP; y++) {
            for (int k = ClubhousePlanner.MAIN_Z; k < BOX.sizeZ(); k++) {
                for (int i : new int[]{0, 15, 16, 31}) {
                    assertNotNull(b.get(key(BOX.minX() + i, y, BOX.minZ() + k)), "side wall " + i + "," + k);
                }
            }
            for (int i = 0; i < 32; i++) {
                assertNotNull(b.get(key(BOX.minX() + i, y, BOX.maxZ())), "the side rooms' south wall");
            }
        }
        for (Box room : new Box[]{ClubhousePlanner.arcadeInside(BOX), ClubhousePlanner.trophyInside(BOX)}) {
            assertTrue(SITE.in(room.minX() + 0.5, room.minY(), room.minZ() + 0.5), "a side room is in the Clubhouse");
        }
    }

    /**
     * The owner: chairs, not benches. Every stair is a straight bottom stair; no two stand side by side; each faces
     * a table (white cloth, or the card table's post) or, an armchair, has an open trapdoor arm either side.
     */
    @Test
    void chairsNeverBenchesEachFacingItsTableOrAnArmchair() {
        Map<Long, String> b = blocks(SITE.plan());
        int atTables = 0;
        int armchairs = 0;
        for (BlockOp op : SITE.plan().ops()) {
            String s = SITE.plan().blockOf(op);
            if (!s.contains("_stairs")) {
                continue;
            }
            assertTrue(s.contains("half=bottom") && s.contains("shape=straight"), "a chair is a straight bottom stair: "
                    + s);
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                String n = b.get(key(op.x() + d[0], op.y(), op.z() + d[1]));
                assertFalse(n != null && n.contains("_stairs"), "no two chairs side by side (a bench): " + op);
            }
            String facing = s.replaceAll(".*facing=([a-z]+).*", "$1");
            int dx = switch (facing) {
                case "east" -> -1;
                case "west" -> 1;
                default -> 0;
            };
            int dz = switch (facing) {
                case "south" -> -1;
                case "north" -> 1;
                default -> 0;
            };
            String front = b.get(key(op.x() + dx, op.y(), op.z() + dz));
            if (ClubhousePlanner.CLOTH.equals(front) || ClubhousePlanner.FELT_BLOCK.equals(front)
                    || (front != null && id(front).endsWith("_fence")) // 0.44.2: the Parlor's felt card tables
                    || ClubhousePlanner.BEAN_TABLE_BLOCK.equals(front)) { // plan 13: a bean bag at the nook's table
                atTables++;
                continue;
            }
            String left = b.get(key(op.x() + dz, op.y(), op.z() + dx));
            String right = b.get(key(op.x() - dz, op.y(), op.z() - dx));
            assertTrue(left != null && left.contains("_trapdoor") && left.contains("open=true") && right != null
                    && right.contains("_trapdoor") && right.contains("open=true"), "an armchair has two arms: " + op);
            armchairs++;
        }
        assertEquals(2 * 6 + 2 * 4 + 4 + 2 * 4 + 4, atTables, "three a side at each banquet table, four at each 3 x 3"
                + " table, four at the card table, four at each of the Game Parlor's two tables (0.50.0; two before),"
                + " and plan 13's four bean bags round their low table");
        assertEquals(6 + 2 + 2, armchairs, "two in the reading nook, two by the fire, two facing the trophies; plan 13's"
                + " two facing the Trophy Room's podium and two in the Parlor's lounge");
    }

    /**
     * The hall and the foyer (x 8-23, the columns included) mirror about the middle line (x 16.0): every block's
     * mirror is the same block turned (east for west), but the podium's iron and copper and the Feast Pot's flowers.
     */
    @Test
    void theHallAndTheFoyerMirrorAboutTheMiddleLine() {
        Map<Long, String> b = blocks(SITE.plan());
        Set<String> pairs = Set.of(ClubhousePlanner.IRON + "|" + ClubhousePlanner.COPPER,
                ClubhousePlanner.POT + "|" + ClubhousePlanner.FLOWERS,
                ClubhousePlanner.PATH_ARCADE + "|" + ClubhousePlanner.PATH_TROPHY); // each room's own colour
        int checked = 0;
        for (int i = 8; i <= 15; i++) {
            for (int j = 0; j <= ClubhousePlanner.ROOF; j++) {
                for (int k = 1; k <= 30; k++) {
                    String w = rel(b, i, j, k);
                    String e = rel(b, 31 - i, j, k);
                    String turned = w == null ? null : w.replace("east", "@").replace("west", "east").replace("@", "west");
                    if (turned != null && w != null && w.contains("east=") && w.contains("west=")) {
                        // a fence's or a pane's sides swap too: east=a,...,west=b -> east=b,...,west=a
                        String ea = w.replaceAll(".*east=([a-z]+).*", "$1");
                        String wa = w.replaceAll(".*west=([a-z]+).*", "$1");
                        turned = w.replaceAll("east=[a-z]+", "east=" + wa).replaceAll("west=[a-z]+", "west=" + ea);
                    }
                    if (w != null && e != null && (pairs.contains(w + "|" + e) || pairs.contains(e + "|" + w))) {
                        continue;
                    }
                    if (w == null && ClubhousePlanner.FLOWERS.equals(e)) {
                        continue; // 0.42.0: the Feast Pot on the west table is a display; its mirror keeps its flowers
                    }
                    if (j >= 1 && j <= 3 && k >= ClubhousePlanner.PODIUM_Z && k <= ClubhousePlanner.PODIUM_Z + 1
                            && (i == 12 || i == 13)) {
                        continue; // 2nd's plinth is a block higher than 3rd's: the podium's one asymmetry
                    }
                    assertEquals(turned, e, "mirror of " + i + "," + j + "," + k + " (" + w + ") at " + (31 - i));
                    checked++;
                }
            }
        }
        assertTrue(checked > 3000, "checked " + checked);
    }

    /**
     * The banquet tables: 3 x 5 white cloth either side of the runner, a plate over each block of their outer rows
     * (twenty, west and east in turn so both fill together), candles down the middle, the aisle a red runner from the
     * podium to the foyer. The 3 x 3 tables south of them share their middle lines; the Feast Pot is the middle of the
     * west one (show only: the real pot is in the home world).
     */
    @Test
    void theBanquetTablesThePlatesAndTheFeastPot() {
        Map<Long, String> b = blocks(SITE.plan());
        List<int[]> cells = ClubhousePlanner.feastCells(BOX);
        assertEquals(FeastRules.CELLS, cells.size(), "twenty plates");
        Set<String> seen = new HashSet<>();
        int west = 0;
        for (int[] c : cells) {
            assertNull(b.get(key(c[0], c[1], c[2])), "a plate hangs in air: " + c[0] + "," + c[1] + "," + c[2]);
            assertEquals(ClubhousePlanner.CLOTH, b.get(key(c[0], c[1] - 1, c[2])), "over the cloth");
            assertTrue(SITE.in(c[0] + 0.5, c[1], c[2] + 0.5), "in the room's air");
            assertTrue(seen.add(c[0] + "," + c[2]), "no two plates share a block");
            west += c[0] - BOX.minX() <= 14 ? 1 : 0;
        }
        assertEquals(10, west, "ten on each table");
        assertTrue(cells.get(0)[0] - BOX.minX() <= 14 && cells.get(1)[0] - BOX.minX() >= 17,
                "west and east in turn: both fill together");
        for (int k = 5; k <= 18; k++) {
            for (int x : new int[]{15, 16}) {
                assertEquals(ClubhousePlanner.RUNNER, rel(b, x, 1, k), "the runner up the aisle at " + x + "," + k);
                assertNull(rel(b, x, 2, k), "and nothing over it");
            }
        }
        WallBoards.Wall line = ClubhousePlanner.feastLine(BOX);
        assertTrue(SITE.in(line.x(), line.y(), line.z()), "the line floats in the room");
        assertEquals(BOX.minX() + 16.0, line.x(), 1e-9, "over the middle line");
        assertEquals(WallBoards.turn(0f), line.yaw(), 0.001f, "facing south, to the arrivals");
        // the 3 x 3 tables
        int[] pot = ClubhousePlanner.pot(BOX);
        assertNull(b.get(key(pot[0], pot[1], pot[2])), "0.42.0: no cauldron block on the table");
        ClubhousePlanner.Prop potProp = ClubhousePlanner.decor(BOX, ClubhousePlanner.Rooms.BOTH).stream()
                .filter(x -> x.key().equals("feast.pot")).findFirst().orElseThrow();
        assertEquals(pot[0] + 0.5, potProp.x(), 1e-9, "the Feast Pot, a small pot on the cloth's middle");
        assertEquals(pot[1], potProp.y() - potProp.sy() / 2, 1e-6, "its bottom on the cloth");
        assertTrue(potProp.sx() < 1f, "smaller than a block");
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                assertEquals(ClubhousePlanner.CLOTH, b.get(key(pot[0] + dx, pot[1] - 1, pot[2] + dz)),
                        "the pot is the middle of a 3 x 3 table");
            }
        }
        int middle = ClubhousePlanner.FEAST_WEST_X0 + 1;
        assertEquals(middle, pot[0] - BOX.minX(), "on the west banquet table's middle line");
        assertEquals(ClubhousePlanner.FLOWERS, rel(b, 31 - middle, 2, pot[2] - BOX.minZ()), "flowers on its mirror");
        assertTrue(SITE.plan().summary().get(1).contains("feast tables") && SITE.plan().summary().get(1).contains(
                "Feast Pot"), SITE.plan().summary().toString());
    }

    /**
     * The jukebox is in the middle of the reading nook's wall of books; the trophy case's frame positions are air on
     * the east wall's inner face, the wall behind each and the cabinet under it, under the All-time records board;
     * nothing of the frames is in the plan (they are entities).
     */
    @Test
    void theJukeboxNookAndTheTrophyCabinet() {
        Map<Long, String> b = blocks(SITE.plan());
        int[] j = ClubhousePlanner.jukebox(BOX);
        assertEquals(ClubhousePlanner.JUKEBOX, b.get(key(j[0], j[1], j[2])), "the jukebox, empty");
        // 0.42.0: a lit alcove over it and a record shelf either side, books beyond
        assertEquals(ClubhousePlanner.ALCOVE, b.get(key(j[0], j[1] + 1, j[2])), "a lit alcove over it");
        assertTrue(b.get(key(j[0], j[1], j[2] - 1)).contains("_shelf"), "a record shelf beside it");
        assertTrue(b.get(key(j[0], j[1], j[2] + 1)).contains("_shelf"), "and on the other side");
        for (int d = 2; d <= 3; d++) {
            assertEquals(ClubhousePlanner.BOOKS, b.get(key(j[0], j[1], j[2] - d)), "books beyond");
            assertEquals(ClubhousePlanner.BOOKS, b.get(key(j[0], j[1], j[2] + d)), "books beyond");
        }
        String front = b.get(key(j[0] + 1, j[1], j[2]));
        assertTrue(front == null || id(front).endsWith("_carpet"), "room to tap it: " + front);
        assertNull(b.get(key(j[0] + 1, j[1] + 1, j[2])), "and to stand there");
        List<int[]> frames = ClubhousePlanner.trophyFrames(BOX);
        assertEquals(ClubBoard.MAX_ROWS, frames.size(), "one frame per record row, at most");
        Set<String> seen = new HashSet<>();
        WallBoards.Wall fame = WallBoards.generated(WallBoards.FAME, MAIN); // the boards hang in the main room
        for (int[] f : frames) {
            assertNull(b.get(key(f[0], f[1], f[2])), "a frame hangs in air: " + f[0] + "," + f[1] + "," + f[2]);
            assertNotNull(b.get(key(f[0] + 1, f[1], f[2])), "on the east wall");
            assertEquals(ClubhousePlanner.CABINET, b.get(key(f[0], f[1] - 1, f[2])), "over the cabinet");
            assertEquals(BOX.maxX() - 1, f[0], "on the wall's inner face");
            assertTrue(f[2] < fame.z() + fame.width() / 2 && f[2] + 1 > fame.z() - fame.width() / 2,
                    "under the All-time records board");
            assertTrue(seen.add(f[2] + ""), "no two frames share a block");
            assertTrue(SITE.in(f[0] + 0.5, f[1] + 0.5, f[2] + 0.5), "in the room");
        }
        assertTrue(SITE.plan().summary().get(1).contains("jukebox"), SITE.plan().summary().toString());
    }

    /** A cushion's place over every chair, stool and armchair (26.3): on its seat, facing the way its sitter does. */
    @Test
    void aCushionsPlaceOnEverySeat() {
        Map<Long, String> b = blocks(SITE.plan());
        List<ClubhousePlanner.Seat> seats = ClubhousePlanner.seats(BOX);
        assertEquals(2 * 6 + 2 * 4 + 4 + 6 + 4, seats.size(), "every chair, armchair and bar stool");
        Set<String> seen = new HashSet<>();
        for (ClubhousePlanner.Seat s : seats) {
            String under = at(b, s.x(), s.y() - 0.25, s.z());
            assertTrue(under != null && (under.contains("_stairs") || under.contains("_slab[type=bottom")),
                    "on a seat: " + s + " " + under);
            assertEquals(0.5, s.y() - Math.floor(s.y()), 1e-9, "on the seat's top (half a block up)");
            // 0.41.1: the cushion's whole footprint is on its seat block (0.40.0's hung a quarter block over the front)
            double half = ClubhousePlanner.CUSHION_WIDTH / 2;
            assertTrue(s.x() - half >= Math.floor(s.x()) - 1e-9 && s.x() + half <= Math.floor(s.x()) + 1 + 1e-9
                    && s.z() - half >= Math.floor(s.z()) - 1e-9 && s.z() + half <= Math.floor(s.z()) + 1 + 1e-9,
                    "on its seat, never over the edge: " + s);
            assertTrue(seen.add(Math.floor(s.x()) + "," + Math.floor(s.z())), "one a seat");
            assertTrue(Set.of("GREEN", "RED", "BROWN").contains(s.color()), s.color());
            assertTrue(SITE.in(s.x(), s.y(), s.z()), "in the room");
            if (under.contains("_stairs")) {
                String facing = under.replaceAll(".*facing=([a-z]+).*", "$1");
                float yaw = switch (facing) {
                    case "north" -> 0f;
                    case "south" -> 180f;
                    case "east" -> 90f;
                    default -> -90f;
                };
                assertEquals(yaw, s.yaw(), "facing away from the chair's back: " + s);
            }
        }
        assertEquals(seats, ClubhousePlanner.seats(BOX), "the same every time");
    }

    /** A fence joins what it touches (another fence, a whole block), as the game would: a verify never finds one off. */
    @Test
    void fencesAndPanesAreWrittenJoined() {
        Map<Long, String> b = blocks(SITE.plan());
        int fences = 0;
        for (BlockOp op : SITE.plan().ops()) {
            String s = SITE.plan().blockOf(op);
            if (!id(s).endsWith("_fence")) {
                continue;
            }
            fences++;
            String[][] sides = {{"east", "1", "0"}, {"west", "-1", "0"}, {"south", "0", "1"}, {"north", "0", "-1"}};
            for (String[] side : sides) {
                String n = b.get(key(op.x() + Integer.parseInt(side[1]), op.y(), op.z() + Integer.parseInt(side[2])));
                boolean joins = n != null && (id(n).endsWith("_fence") || ClubhousePlanner.SOLID.contains(id(n)));
                assertTrue(s.contains(side[0] + "=" + joins), s + " at " + op + " " + side[0] + " of " + n);
            }
        }
        assertTrue(fences > 30, "the sconces, the chandeliers, the lamp, the pin and the card table: " + fences);
        for (BlockOp op : SITE.plan().ops()) {
            String s = SITE.plan().blockOf(op);
            if (id(s).endsWith("_pane")) {
                assertTrue(s.contains("east=true") && s.contains("west=true") || s.contains("north=true")
                        && s.contains("south=true"), "a window pane joins along its wall: " + s);
            }
        }
    }

    // ---- 0.41.0: the side rooms ----------------------------------------------------------------------

    /**
     * Without its side rooms (a 32-deep box, or both switched off) the main room is plan 4's golf club block for block;
     * with them, the only changes to it are the two archways, their floors, the two colour paths and the two sconces
     * that move to either side of each archway's sign.
     */
    @Test
    void theMainRoomChangesOnlyAtTheArchways() {
        ClubhouseSite alone = ClubhousePlanner.plan(MAIN);
        assertTrue(alone.more().isEmpty(), "no side rooms in a 32-deep box");
        assertThrows(IllegalArgumentException.class, () -> ClubhousePlanner.plan(MAIN, ClubhousePlanner.Rooms.BOTH),
                "a side room needs the annex");
        Map<Long, String> a = blocks(alone.plan());
        Map<Long, String> b = blocks(SITE.plan());
        Map<Long, String> off = blocks(ClubhousePlanner.plan(BOX, ClubhousePlanner.Rooms.NONE).plan());
        assertEquals(a, off, "a 62-deep box with both rooms off is the main room alone (the wing is air)");
        assertEquals(a, blocks(ClubhousePlanner.plan(OLD, ClubhousePlanner.Rooms.NONE).plan()), "and a 48-deep one");
        Set<String> changed = new HashSet<>();
        for (int i = 0; i < 32; i++) {
            for (int j = 0; j <= ClubhousePlanner.ROOF; j++) {
                for (int k = 0; k < ClubhousePlanner.MAIN_Z; k++) {
                    long at = key(BOX.minX() + i, BOX.minY() + j, BOX.minZ() + k);
                    if (!java.util.Objects.equals(a.get(at), b.get(at))) {
                        changed.add(i + "," + j + "," + k);
                    }
                }
            }
        }
        Set<String> expected = new HashSet<>();
        for (int x0 : new int[]{ClubhousePlanner.ARCH_ARCADE_X0, ClubhousePlanner.ARCH_TROPHY_X0}) {
            for (int i = x0; i < x0 + 3; i++) {
                for (int j = 1; j <= ClubhousePlanner.ARCH_H; j++) {
                    expected.add(i + "," + j + ",31"); // the archway (its runner at j 1)
                }
                for (int k = ClubhousePlanner.FOYER_Z0; k <= 30; k++) {
                    expected.add(i + ",1," + k); // the runner, three wide
                }
            }
            for (int j : new int[]{4, 5}) {
                expected.add((x0 + 1) + "," + j + ",30"); // the old sconce over the archway
                expected.add((x0 - 1) + "," + j + ",30"); // and its two new places either side
                expected.add((x0 + 3) + "," + j + ",30");
            }
        }
        assertEquals(new java.util.TreeSet<>(expected), new java.util.TreeSet<>(changed),
                "only the archways, the paths and the sconces");
    }

    /** One room off: its half of the annex is air and its archway stays wall; the other room stands as it was. */
    @Test
    void aRoomSwitchedOffIsNotBuilt() {
        ClubhouseSite arcadeOnly = ClubhousePlanner.plan(BOX, new ClubhousePlanner.Rooms(true, false));
        Map<Long, String> b = blocks(arcadeOnly.plan());
        Map<Long, String> both = blocks(SITE.plan());
        for (BlockOp op : arcadeOnly.plan().ops()) {
            assertTrue(op.x() - BOX.minX() < ClubhousePlanner.TROPHY_X0 || op.z() - BOX.minZ() < ClubhousePlanner.MAIN_Z,
                    "nothing of the Trophy Room: " + op);
        }
        assertNotNull(rel(b, ClubhousePlanner.ARCH_TROPHY_X0 + 1, 1, 31), "its archway stays wall");
        assertNull(rel(b, ClubhousePlanner.ARCH_ARCADE_X0 + 1, 2, 31), "the other archway is open");
        assertEquals(ClubhousePlanner.PATH_ARCADE, rel(b, ClubhousePlanner.ARCH_ARCADE_X0 + 1, 1, 31), "its runner");
        for (int i = 0; i < 16; i++) {
            for (int k = 32; k < BOX.sizeZ(); k++) {
                for (int j = 0; j <= ClubhousePlanner.SIDE_ROOF; j++) {
                    assertEquals(rel(both, i, j, k), rel(b, i, j, k), "the Arcade Room as it was");
                }
            }
        }
        assertFalse(arcadeOnly.in(BOX.minX() + 20.5, BOX.minY() + 1, BOX.minZ() + 40.5), "no Trophy Room to be in");
    }

    /**
     * The archways are walk-in (three wide, three high, nothing to open), each with its colour path from the Foyer's
     * first row up its middle and on into its room, and a door sign over it in front of the wall; standing in an
     * archway is being in the Clubhouse.
     */
    @Test
    void walkInArchwaysWithAPathAndASign() {
        Map<Long, String> b = blocks(SITE.plan());
        Object[][] rooms = {{ClubhousePlanner.ARCH_ARCADE_X0, ClubhousePlanner.PATH_ARCADE, true},
                {ClubhousePlanner.ARCH_TROPHY_X0, ClubhousePlanner.PATH_TROPHY, false}};
        for (Object[] r : rooms) {
            int x0 = (int) r[0];
            int mid = x0 + 1;
            for (int i = x0; i < x0 + 3; i++) {
                for (int j = 2; j <= 3; j++) {
                    assertNull(rel(b, i, j, 31), "open archway at " + i + "," + j);
                }
                for (int j = 1; j <= 3; j++) {
                    assertTrue(SITE.in(BOX.minX() + i + 0.5, BOX.minY() + j, BOX.minZ() + 31.5), "in the Clubhouse");
                }
                assertNotNull(rel(b, i, 4, 31), "a lintel over it");
                // 0.41.1: a carpet runner three wide, square with the archway, from the Foyer's first row to its far side
                for (int k = ClubhousePlanner.FOYER_Z0; k <= 31; k++) {
                    assertEquals(r[1], rel(b, i, 1, k), "the runner at " + i + "," + k);
                    assertNull(rel(b, i, 2, k), "nothing on the runner at " + i + "," + k);
                    assertNotNull(rel(b, i, 0, k), "a floor under it");
                }
                assertNotEquals(r[1], rel(b, i, 1, 32), "it ends at the archway: no stripe into the room");
            }
            ClubhousePlanner.Place3 sign = ClubhousePlanner.door(BOX, (boolean) r[2]);
            assertEquals(BOX.minX() + mid + 0.5, sign.x(), 1e-9, "over the archway's middle");
            assertEquals(180f, sign.yaw(), "facing the Foyer");
            assertTrue(sign.z() < BOX.minZ() + 31 && sign.z() > BOX.minZ() + 30, "just out from the wall");
            assertTrue(sign.y() - 0.75 >= BOX.minY() + 4 && sign.y() + 0.75 <= BOX.minY() + ClubhousePlanner.PANEL_Y0 - 1,
                    "between the archway's top and the board's panel");
            for (int dx : new int[]{-1, 0, 1}) {
                for (int j = 4; j <= 6; j++) {
                    assertNull(rel(b, mid + dx, j, 30), "the sign hangs clear of the sconces: " + (mid + dx) + "," + j);
                }
            }
        }
        // the sconces either side of each sign still light the archway
        assertEquals(ClubhousePlanner.HANGING, rel(b, ClubhousePlanner.ARCH_ARCADE_X0 - 1, 4, 30));
        assertEquals(ClubhousePlanner.HANGING, rel(b, ClubhousePlanner.ARCH_TROPHY_X0 + 3, 4, 30));
    }

    /**
     * The Arcade Room's nine cabinet places (plan 11; six before) stand on their mats, in the room, with room to walk up (two blocks of air
     * over each and one in front), the first four on the south wall facing the archway; the path reaches them.
     */
    @Test
    void theArcadeRoomsCabinetPlaces() {
        Map<Long, String> b = blocks(SITE.plan());
        List<ClubhousePlanner.Place3> cabs = ClubhousePlanner.cabinets(BOX);
        assertEquals(ClubhousePlanner.CABINETS, cabs.size());
        Box room = ClubhousePlanner.arcadeInside(BOX);
        Set<String> seen = new HashSet<>();
        for (ClubhousePlanner.Place3 c : cabs) {
            assertTrue(room.contains(c.x(), c.y(), c.z()), "in the Arcade Room: " + c);
            assertEquals(ClubhousePlanner.MAT, at(b, c.x(), c.y() - 1, c.z()), "on its mat");
            assertNull(at(b, c.x(), c.y(), c.z()), "air for the cabinet");
            assertNull(at(b, c.x(), c.y() + 1, c.z()), "two high");
            double fx = c.yaw() == -90f ? 1 : c.yaw() == 90f ? -1 : 0;
            double fz = c.yaw() == 180f ? -1 : c.yaw() == 0f ? 1 : 0;
            assertTrue(room.contains(c.x() + fx, c.y(), c.z() + fz), "it faces into the room: " + c);
            assertNull(at(b, c.x() + fx, c.y(), c.z() + fz), "room to walk up");
            assertTrue(seen.add(Math.floor(c.x()) + "," + Math.floor(c.z())), "one a place");
            for (ClubhousePlanner.Place3 o : cabs) {
                if (o != c) {
                    assertTrue(Math.hypot(o.x() - c.x(), o.z() - c.z()) >= 3, "three apart: easy to tap the right one");
                }
            }
        }
        for (ClubhousePlanner.Place3 c : cabs) {
            boolean east = c.x() - BOX.minX() > 7.5;
            assertEquals(east ? 90f : -90f, c.yaw(), "0.42.0: two facing rows down the side walls: " + c);
        }
        assertFalse(ClubhousePlanner.MAT.contains("magenta") || ClubhousePlanner.MAT.contains("purple"),
                "never a purple mat: it read as the missing texture");
    }

    /**
     * The Trophy Room: the podium's three pedestals at the back (1st in the room's middle and highest, gold, iron,
     * copper), eight record pedestals on the side walls; each trophy and its head stand on top, side by side, facing
     * into the room.
     */
    @Test
    void theTrophyRoomsPedestals() {
        Map<Long, String> b = blocks(SITE.plan());
        List<ClubhousePlanner.Pedestal> ps = ClubhousePlanner.pedestals(BOX);
        assertEquals(3 + ClubhousePlanner.RECORD_PEDESTALS, ps.size());
        Box room = ClubhousePlanner.trophyInside(BOX);
        String[] tops = {ClubhousePlanner.GOLD, ClubhousePlanner.IRON, ClubhousePlanner.COPPER};
        Set<String> seen = new HashSet<>();
        for (int n = 0; n < ps.size(); n++) {
            ClubhousePlanner.Pedestal p = ps.get(n);
            for (ClubhousePlanner.Place3 c : new ClubhousePlanner.Place3[]{p.trophy(), p.head()}) {
                assertTrue(room.contains(c.x(), c.y(), c.z()), "in the Trophy Room: " + c);
                assertNotNull(at(b, c.x(), c.y() - 1, c.z()), "on its pedestal");
                assertNull(at(b, c.x(), c.y(), c.z()), "air over it");
                assertTrue(seen.add(Math.floor(c.x()) + "," + Math.floor(c.z())), "one a block");
            }
            assertEquals(1.0, Math.abs(p.head().x() - p.trophy().x()) + Math.abs(p.head().z() - p.trophy().z()), 1e-9,
                    "the head is next to its trophy");
            assertEquals(p.trophy().yaw(), p.head().yaw(), "both face the same way");
            if (n < 3) {
                assertEquals(tops[n], at(b, p.trophy().x(), p.trophy().y() - 1, p.trophy().z()), "podium " + (n + 1));
            } else {
                assertEquals(ClubhousePlanner.PEDESTAL, at(b, p.trophy().x(), p.trophy().y() - 1, p.trophy().z()));
            }
        }
        assertTrue(ps.get(0).trophy().y() > ps.get(1).trophy().y() && ps.get(1).trophy().y() > ps.get(2).trophy().y(),
                "1st highest");
        double middle = (room.minX() + room.maxX() + 1) / 2.0;
        assertEquals(middle, (ps.get(0).trophy().x() + ps.get(0).head().x()) / 2, 1e-9, "1st in the room's middle");
    }

    /**
     * 0.41.1: an armchair's arm (an open trapdoor, thin) never stands over a floor that shows through: on a rug its
     * floor is the rug's colour as a whole block, and no hidden light is ever under an arm or anything else thin.
     */
    @Test
    void noHiddenLightOrBareFloorShowsUnderAnArm() {
        Map<Long, String> b = blocks(SITE.plan());
        int arms = 0;
        for (BlockOp op : SITE.plan().ops()) {
            String s = SITE.plan().blockOf(op);
            if (!s.contains("_trapdoor")) {
                continue;
            }
            arms++;
            String under = b.get(key(op.x(), op.y() - 1, op.z()));
            assertNotEquals(ClubhousePlanner.LIGHT, under, "no hidden light under an arm at " + op);
            assertTrue(under != null && (under.endsWith("_wool") || under.equals(ClubhousePlanner.PLANTER)
                    || under.equals(ClubhousePlanner.PLANKS)), "a whole floor block under the arm: " + under);
        }
        assertEquals(20, arms, "ten armchairs (plan 13: the Trophy Room's two and the lounge's two), two arms each");
        for (BlockOp op : SITE.plan().ops()) {
            if (ClubhousePlanner.LIGHT.equals(SITE.plan().blockOf(op)) && op.y() == BOX.minY()) {
                String over = b.get(key(op.x(), op.y() + 1, op.z()));
                assertTrue(over == null || id(over).endsWith("_carpet") || ClubhousePlanner.SOLID.contains(id(over))
                        || over.contains("_stairs") || over.contains("_slab"),
                        "a hidden light is under a carpet or a whole block, never a thin one: " + over);
                assertFalse(over != null && (over.contains("_trapdoor") || over.contains("_fence")), "at " + op);
            }
        }
    }

    // ---- 0.42.0 ------------------------------------------------------------------------------------

    /**
     * The Arcade Room made over: a tinted roof (dim), a neon strip of froglights round its walls, a confetti floor,
     * a prize counter of glass cases and two claw machines on its back row, the six cabinets clear of them.
     */
    @Test
    void theArcadeRoomLooksLikeAnArcade() {
        Map<Long, String> b = blocks(SITE.plan());
        int neon = 0;
        int confetti = 0;
        for (int i = 0; i <= 15; i++) {
            for (int k = ClubhousePlanner.MAIN_Z; k < BOX.sizeZ(); k++) {
                assertEquals(ClubhousePlanner.TINTED, rel(b, i, ClubhousePlanner.SIDE_ROOF, k), "a tinted roof");
                String w = rel(b, i, 6, k);
                neon += w != null && w.contains("froglight") ? 1 : 0;
                String f = rel(b, i, 0, k);
                confetti += f != null && f.endsWith("_concrete") && !f.equals(ClubhousePlanner.ARCADE_FLOOR) ? 1 : 0;
            }
        }
        assertTrue(neon > 40, "a neon strip round its walls: " + neon);
        assertTrue(confetti > 10, "a colourful arcade carpet: " + confetti);
        for (int i = ClubhousePlanner.PRIZE_X0; i <= ClubhousePlanner.PRIZE_X1; i++) {
            assertEquals(ClubhousePlanner.CABINET, rel(b, i, 1, ClubhousePlanner.SIDE_Z1), "the prize counter");
            assertEquals(ClubhousePlanner.ROOF_GLASS, rel(b, i, 2, ClubhousePlanner.SIDE_Z1), "its glass cases");
        }
        for (int i : ClubhousePlanner.clawX(BOX)) {
            assertEquals(ClubhousePlanner.CLAW, rel(b, i, 1, ClubhousePlanner.SIDE_Z1), "a claw machine");
        }
        assertArrayEquals(ClubhousePlanner.GROWN_CLAW_X, ClubhousePlanner.clawX(BOX), "plan 13: either end of the"
                + " prize counter (the corners are the nooks')");
        assertArrayEquals(ClubhousePlanner.CLAW_X, ClubhousePlanner.clawX(OLD), "a wing at its old size: the corners");
        assertEquals(ClubhousePlanner.CABINETS, ClubhousePlanner.cabinets(BOX).size(), "all thirteen places");
        assertEquals(ClubhousePlanner.OLD_CABINETS, ClubhousePlanner.cabinets(OLD).size(), "nine at the old size");
        long prizes = ClubhousePlanner.decor(BOX, ClubhousePlanner.Rooms.BOTH).stream()
                .filter(x -> x.key().startsWith("arcade.")).count();
        assertTrue(prizes >= 6, "prizes on show: " + prizes);
        assertEquals(0, ClubhousePlanner.decor(MAIN, ClubhousePlanner.Rooms.NONE).stream()
                .filter(x -> x.key().startsWith("arcade.")).count(), "no Arcade Room: no prizes");
    }

    /**
     * The putting green reads as one: a thin pole standing in the open cup (lit), a small flag, a ball by the hole and a
     * putter; the bar's brewing stand on the counter and its bottles and glasses; the Trophy Room's beams and banners.
     */
    @Test
    void theGreenTheBarAndTheTrophyRoomAreDressed() {
        Map<Long, String> b = blocks(SITE.plan());
        int cx = ClubhousePlanner.GREEN_CUP_X;
        int cz = ClubhousePlanner.GREEN_CUP_Z;
        assertEquals(ClubhousePlanner.CUP, rel(b, cx, 0, cz), "the cup in the floor");
        assertEquals(ClubhousePlanner.POLE, rel(b, cx, 1, cz), "a thin pole in it");
        assertEquals(ClubhousePlanner.POLE, rel(b, cx, 2, cz));
        assertNull(rel(b, cx, 3, cz), "no block flag on top any more");
        List<String> keys = ClubhousePlanner.decor(BOX, ClubhousePlanner.Rooms.BOTH).stream()
                .map(ClubhousePlanner.Prop::key).toList();
        assertTrue(keys.containsAll(List.of("green.flag", "green.ball", "green.putter", "jukebox.sign", "feast.pot")),
                keys.toString());
        assertEquals(0, keys.stream().filter(k -> k.startsWith("bar.bottle.")).count(),
                "0.44.2: no floating bottles: the back bar's shelves hold them (ClubDecor.stockBar)");
        List<int[]> shelves = ClubhousePlanner.barShelves(BOX);
        assertEquals(18, shelves.size(), "two rows of nine shelves");
        for (int[] at : shelves) {
            assertTrue(id(b.get(key(at[0], at[1], at[2]))).endsWith("_shelf"), "a shelf block at each: "
                    + java.util.Arrays.toString(at));
        }
        assertTrue(keys.stream().filter(k -> k.startsWith("bar.glass.")).count() >= 3, "glasses on the counter");
        assertEquals(keys.size(), new java.util.HashSet<>(keys).size(), "one key each");
        assertEquals(ClubhousePlanner.BREWING, rel(b, 4, 2, ClubhousePlanner.BREWING_Z), "a brewing stand on the counter");
        for (int k : ClubhousePlanner.GROWN_TROPHY_BEAM_Z) {
            assertEquals("minecraft:stripped_dark_oak_log[axis=x]", rel(b, 20, ClubhousePlanner.SIDE_TOP, k), "a beam");
        }
        for (int k : ClubhousePlanner.GROWN_TROPHY_BANNER_Z) {
            assertTrue(rel(b, ClubhousePlanner.TROPHY_X0 + 1, 5, k).contains("_wall_banner"), "a banner");
        }
        // every decoration stands in the room's air, never inside a block
        for (ClubhousePlanner.Prop p : ClubhousePlanner.decor(BOX, ClubhousePlanner.Rooms.BOTH)) {
            if (!p.block()) {
                assertTrue(SITE.in(p.x(), p.y(), p.z()), "in the room: " + p);
            }
        }
    }

    /** A save: snapshot, file and back give the same blocks; the light check finds the room's dark corners. */
    @Test
    void aSaveRoundTripsAndItsChecksFindProblems() {
        Map<Long, String> b = blocks(SITE.plan());
        Plan snap = ClubSave.snapshot(BOX, at -> b.get(key(at[0], at[1], at[2])));
        assertEquals(SITE.plan().ops().size(), snap.ops().size(), "every block of the room");
        assertEquals(b, blocks(snap), "the same blocks");
        java.util.Set<String> regions = ClubhousePlanner.regions(BOX, ClubhousePlanner.Rooms.BOTH).keySet();
        ClubSave.Saved back = ClubSave.read(ClubSave.text(snap, "abc", regions), BOX);
        assertEquals(b, blocks(back.plan()), "the file reads back the same blocks");
        assertEquals("abc", back.generatedHash());
        assertEquals(java.util.Set.of("main", "arcade", "trophy", "secret", "parlor"), back.regions());
        Box moved = BOX.translate(160, 0, -64);
        assertEquals(snap.ops().size(), ClubSave.read(ClubSave.text(snap, null, regions), moved).plan().ops().size(),
                "offsets: a moved origin moves the save");
        // 0.45.0: a save of another DEPTH still fits (the main room is the first rows of both boxes): refusing it built
        // HCM's room over the owner's. The 48-deep save read into the 32-deep box keeps the main room's blocks...
        ClubSave.Saved shallow = ClubSave.read(ClubSave.text(snap, null, regions), MAIN);
        Map<Long, String> mainOnly = blocks(shallow.plan());
        for (Map.Entry<Long, String> e : b.entrySet()) {
            boolean inMain = MAIN.contains(x(e.getKey()), y(e.getKey()), z(e.getKey()));
            assertEquals(inMain ? e.getValue() : null, mainOnly.get(e.getKey()), "the main room's blocks, no annex");
        }
        // ...and a save of the main room alone read into the full box covers only "main": the rest is HCM's (merged)
        Plan small = ClubSave.snapshot(MAIN, at -> b.get(key(at[0], at[1], at[2])));
        ClubSave.Saved deep = ClubSave.read(ClubSave.text(small, null, regions), BOX);
        assertEquals(java.util.Set.of("main"), deep.regions(), "whatever it claimed, a main-room save covers main");
        assertEquals(b, blocks(ClubSave.merged(deep, SITE.plan(), ClubhousePlanner.regions(BOX,
                ClubhousePlanner.Rooms.BOTH))), "the saved main room with HCM's side rooms");
        Box wide = Box.sized(MAIN.minX(), MAIN.minY(), MAIN.minZ(), 48, 16, 48);
        assertThrows(IllegalArgumentException.class, () -> ClubSave.read(ClubSave.text(snap, null, regions), wide),
                "a save of another width doesn't fit");
        assertEquals(List.of(), ClubSave.dark(snap, SITE, 10), "the generated room has no dark spot");
        // take every light out of the Arcade Room: its floor goes dark
        Map<Long, String> dim = new java.util.HashMap<>(b);
        dim.replaceAll((k, v) -> ClubhousePlanner.lightOf(v) > 0 && ClubhousePlanner.arcadeInside(BOX).contains(
                (int) (k >> 38), BOX.minY() + 2, (int) ((k << 26) >> 38)) ? "minecraft:stone" : v);
        Plan dark = ClubSave.snapshot(BOX, at -> dim.get(key(at[0], at[1], at[2])));
        assertFalse(ClubSave.dark(dark, SITE, 10).isEmpty(), "the dark is found");
        assertTrue(ClubSave.dark(dark, SITE, 3).size() <= 3, "at most the number asked");
    }

    /**
     * A save made before a release that adds a room: the new room's region comes from HCM's plan, the owner's blocks
     * everywhere else stay.
     */
    @Test
    void aRoomAddedLaterAppearsInASavedRoom() {
        Map<Long, String> b = blocks(SITE.plan());
        Map<Long, String> mine = new java.util.HashMap<>(b);
        long gold = key(BOX.minX() + 5, BOX.minY() + 1, BOX.minZ() + 20);
        mine.put(gold, "minecraft:gold_block"); // the owner's own block in the 19th Hole
        Plan snap = ClubSave.snapshot(BOX, at -> mine.get(key(at[0], at[1], at[2])));
        // a save that knew only the main room: the side rooms come from HCM's plan
        ClubSave.Saved old = ClubSave.read(ClubSave.text(snap, null, java.util.Set.of("main")), BOX);
        Map<Long, String> merged = blocks(ClubSave.merged(old, SITE.plan(),
                ClubhousePlanner.regions(BOX, ClubhousePlanner.Rooms.BOTH)));
        assertEquals("minecraft:gold_block", merged.get(gold), "the owner's block stays");
        long arcade = key(BOX.minX() + 1, BOX.minY() + 6, BOX.minZ() + 40);
        assertEquals(b.get(arcade), merged.get(arcade), "the new room is HCM's");
        ClubSave.Saved all = ClubSave.read(ClubSave.text(snap, null,
                ClubhousePlanner.regions(BOX, ClubhousePlanner.Rooms.BOTH).keySet()), BOX);
        assertEquals(all.plan(), ClubSave.merged(all, SITE.plan(), ClubhousePlanner.regions(BOX,
                ClubhousePlanner.Rooms.BOTH)), "a save that knows every region is used as it is");
    }

    // ---- 0.43.0: the two rooms upstairs ----------------------------------------------------------------------

    /** Where a person stands: feet and head free (a carpet is fine), something solid under them. */
    private static void standsAt(Map<Long, String> b, double x, double y, double z, String what) {
        int bx = (int) Math.floor(x);
        int by = (int) Math.floor(y);
        int bz = (int) Math.floor(z);
        String feet = b.get(key(bx, by, bz));
        String head = b.get(key(bx, by + 1, bz));
        String below = b.get(key(bx, by - 1, bz));
        assertTrue(feet == null || id(feet).endsWith("_carpet"), what + ": feet free, not " + feet);
        assertNull(head, what + ": head free");
        assertTrue(below != null && !ClubhousePlanner.noFooting(below), what + ": a floor under it, not " + below);
        assertTrue(BOX.contains(bx, by, bz), what + ": inside the box");
    }

    @Test
    void theUpstairsRoomsHaveRoomToArriveAndWalkOut() {
        Map<Long, String> b = blocks(SITE.plan());
        ClubhouseSite.Spot s = ClubhousePlanner.secretArrival(BOX);
        standsAt(b, s.x(), s.y(), s.z(), "the secret room's arrival");
        s = ClubhousePlanner.secretReturn(BOX);
        standsAt(b, s.x(), s.y(), s.z(), "back in the Trophy Room");
        s = ClubhousePlanner.parlorArrival(BOX);
        standsAt(b, s.x(), s.y(), s.z(), "the Game Parlor's arrival");
        s = ClubhousePlanner.parlorReturn(BOX);
        standsAt(b, s.x(), s.y(), s.z(), "back in the 19th Hole");
        assertTrue(ClubhousePlanner.secretInside(BOX).contains((int) Math.floor(ClubhousePlanner.secretCentre(BOX).x()),
                (int) Math.floor(ClubhousePlanner.secretCentre(BOX).y()), (int) Math.floor(ClubhousePlanner
                        .secretCentre(BOX).z())), "the pedestal is in the secret room");
        // 0.45.0: the dark door (in the Trophy Room) is the secret room's, the little star (in the 19th Hole) the Parlor's
        ClubhousePlanner.Place3 door = ClubhousePlanner.secretDoor(BOX);
        assertTrue(ClubhousePlanner.trophyInside(BOX).contains((int) Math.floor(door.x()), (int) door.y(),
                (int) Math.floor(door.z())), "the secret room's door is in the Trophy Room");
        for (int j = 0; j < 2; j++) {
            String at = b.get(key((int) Math.floor(door.x()), (int) door.y() + j, (int) Math.floor(door.z())));
            assertTrue(at == null || id(at).endsWith("_carpet"), "the door stands in air (over the rug): " + at);
        }
        assertNotNull(b.get(key((int) Math.floor(door.x()), (int) door.y(), (int) Math.floor(door.z()) + 1)),
                "against the Trophy Room's back wall");
        assertEquals(180f, door.yaw(), "facing into the room");
        // 0.52.1: the Parlor's way in is a secret book: the reading nook's top row, nothing drawn, a crouched tap
        ClubhousePlanner.Place3 book = ClubhousePlanner.parlorStar(BOX);
        int lx = (int) Math.floor(book.x());
        int ly = (int) Math.floor(book.y());
        int lz = (int) Math.floor(book.z());
        assertEquals(ClubhousePlanner.BOOKS, b.get(key(lx, ly, lz)), "it is one of the nook's books");
        assertEquals(ClubhousePlanner.BOOKS, b.get(key(lx, ly - 2, lz)), "in the wall of books, top row");
        assertNull(b.get(key(lx, ly + 1, lz)) == null ? null : (ClubhousePlanner.BOOKS.equals(b.get(key(lx, ly + 1, lz)))
                ? "a book over it" : null), "the top row");
        assertNull(b.get(key(lx + 1, ly, lz)), "its face is open to the room, to be tapped");
        assertEquals(ClubhousePlanner.PARLOR_GAMES.size(), ClubhousePlanner.parlorMachines(BOX).size(),
                "a place for every machine");
        for (ClubhousePlanner.Place3 m : ClubhousePlanner.parlorMachines(BOX)) {
            assertTrue(ClubhousePlanner.parlorInside(BOX).contains((int) Math.floor(m.x()), (int) Math.floor(m.y()),
                    (int) Math.floor(m.z())), "a machine stands in the Parlor: " + m);
            assertNull(b.get(key((int) Math.floor(m.x()), (int) Math.floor(m.y()), (int) Math.floor(m.z()))),
                    "and in air: " + m);
        }
        // 0.46.1: the Scratch Ticket's booth, a display like a machine, against the Parlor's back wall
        ClubhousePlanner.Place3 booth = ClubhousePlanner.parlorTicket(BOX);
        int bx = (int) Math.floor(booth.x());
        int by = (int) Math.floor(booth.y());
        int bz = (int) Math.floor(booth.z());
        assertTrue(ClubhousePlanner.parlorInside(BOX).contains(bx, by, bz), "the ticket booth stands in the Parlor");
        for (int j = 0; j < 2; j++) {
            assertNull(b.get(key(bx, by + j, bz)), "in two blocks of air (it is a cabinet's size)");
        }
        assertNotNull(b.get(key(bx, by - 1, bz)), "on the floor");
        assertNotNull(b.get(key(bx, by, bz + 1)), "its back to the Parlor's back wall");
        String front = b.get(key(bx, by, bz - 1));
        assertTrue(front == null || id(front).endsWith("_carpet"), "and room to stand in front of it (on the felt): "
                + front);
        assertNull(b.get(key(bx, by + 1, bz - 1)), "head room there");
        assertEquals(180f, booth.yaw(), "facing the room");
        for (ClubhousePlanner.Place3 m : ClubhousePlanner.parlorMachines(BOX)) {
            assertTrue(Math.abs(m.x() - booth.x()) + Math.abs(m.z() - booth.z()) >= 2, "clear of every machine: " + m);
        }
        List<ClubhousePlanner.Place3> shelf = ClubhousePlanner.secretShelf(BOX);
        assertTrue(shelf.size() >= 30, "room for many trophies: " + shelf.size());
        for (ClubhousePlanner.Place3 t : shelf) {
            int x = (int) Math.floor(t.x());
            int y = (int) Math.floor(t.y());
            int z = (int) Math.floor(t.z());
            assertNull(b.get(key(x, y, z)), "a trophy stands in air: " + t);
            assertNotNull(b.get(key(x, y - 1, z)), "on a ledge: " + t);
        }
    }

    /**
     * The Parlor's slot machines along the back row (Nether Slots, Farm Slots, The Motherlode, then Deep Mine and Mob
     * Slots): displays with the Scratch Ticket's booth between them, each on the floor with its back to the wall, in
     * two blocks of air with room to stand in front, clear of every other machine and of the booth. They are displays
     * only: not one block is theirs. (The plan's hash and {@link ClubhousePlanner#ALGO} are plan 10's: Mob Races'
     * table, which is blocks, came after them.)
     */
    @Test
    void theParlorsBackRowHasAPlaceForEachNewSlotMachine() {
        Map<Long, String> b = blocks(SITE.plan());
        List<ClubhousePlanner.Place3> row = ClubhousePlanner.parlorRow(BOX);
        assertEquals(List.of("nether_slots", "farm_slots", "motherlode", "deep_mine", "mob_slots"),
                ClubhousePlanner.PARLOR_ROW_GAMES, "the first three keep their places in the list: a machine's place"
                        + " in it is how its display is kept");
        assertEquals(ClubhousePlanner.PARLOR_ROW_GAMES.size(), row.size(), "a place for each");
        int[] xs = {3, 5, 9, 11, 13};
        ClubhousePlanner.Place3 booth = ClubhousePlanner.parlorTicket(BOX);
        List<ClubhousePlanner.Place3> others = new java.util.ArrayList<>(ClubhousePlanner.parlorMachines(BOX));
        others.add(booth);
        Set<Long> taken = new HashSet<>();
        for (int n = 0; n < row.size(); n++) {
            ClubhousePlanner.Place3 m = row.get(n);
            String game = ClubhousePlanner.PARLOR_ROW_GAMES.get(n);
            int x = (int) Math.floor(m.x());
            int y = (int) Math.floor(m.y());
            int z = (int) Math.floor(m.z());
            assertEquals(BOX.minX() + xs[n], x, game + " stands at x " + xs[n]);
            assertEquals(BOX.minZ() + ClubhousePlanner.SIDE_Z1, z, game + " is on the back row");
            assertEquals(booth.y(), m.y(), game + " stands on the booth's floor");
            assertEquals(booth.z(), m.z(), game + " is in line with the booth");
            assertEquals(180f, m.yaw(), game + " faces the room");
            assertEquals(0.5, m.x() - x, 1e-9, game + " is in the middle of its block");
            assertTrue(ClubhousePlanner.parlorInside(BOX).contains(x, y, z), game + " stands in the Parlor");
            for (int j = 0; j < 2; j++) {
                assertNull(b.get(key(x, y + j, z)), game + " stands in two blocks of air (a cabinet's size)");
            }
            assertNotNull(b.get(key(x, y - 1, z)), game + " is on the floor");
            assertNotNull(b.get(key(x, y, z + 1)), game + " has its back to the Parlor's back wall");
            assertNotNull(b.get(key(x, y + 1, z + 1)), game + ": the wall is behind all of it");
            String front = b.get(key(x, y, z - 1));
            assertTrue(front == null || id(front).endsWith("_carpet"), game + ": room to stand in front (on the felt): "
                    + front);
            assertNull(b.get(key(x, y + 1, z - 1)), game + ": head room there");
            for (ClubhousePlanner.Place3 o : others) {
                assertTrue(Math.abs(o.x() - m.x()) + Math.abs(o.z() - m.z()) >= 2, game + " is clear of " + o);
            }
            assertTrue(taken.add(key(x, y, z)), game + " has a place of its own");
        }
        // each stands clear of the others on the row too
        for (int n = 0; n < row.size(); n++) {
            for (int o = n + 1; o < row.size(); o++) {
                assertTrue(Math.abs(row.get(n).x() - row.get(o).x()) >= 2, ClubhousePlanner.PARLOR_ROW_GAMES.get(n)
                        + " is clear of " + ClubhousePlanner.PARLOR_ROW_GAMES.get(o));
            }
        }
        // displays only: no block is theirs (the plan is the planner's alone, pinned)
        assertEquals(HASH, SITE.plan().hash(), "no block is a slot machine's");
        assertEquals(13, ClubhousePlanner.ALGO, "plan 10 was Mob Races' table, 11 the Arcade Room's, 13 the grown wing;"
                + " the machines changed no plan");
        assertEquals(6, ClubhousePlanner.parlorMachines(BOX).size(), "the first six machines are where they were");
        for (String game : ClubhousePlanner.PARLOR_ROW_GAMES) {
            assertNotNull(com.dierks.homecraft.pack.ClubModels.machineFor(game), game + " has a model name");
            assertEquals(com.dierks.homecraft.pack.ClubModels.Kind.CABINET,
                    com.dierks.homecraft.pack.ClubModels.machineFor(game).kind(), game + " is a cabinet's size");
            assertEquals(com.dierks.homecraft.games.GameKind.CHANCE,
                    com.dierks.homecraft.games.GameCatalog.spec(game).kind(), game + " is a game of chance, so an"
                            + " owner-built room places it with /hcm games clubhouse parlor " + game);
        }
        assertEquals("machine_nether", com.dierks.homecraft.pack.ClubModels.machineFor("nether_slots").name());
        assertEquals("machine_farm", com.dierks.homecraft.pack.ClubModels.machineFor("farm_slots").name());
        assertEquals("machine_motherlode", com.dierks.homecraft.pack.ClubModels.machineFor("motherlode").name());
    }

    /**
     * Deep Mine and Mob Slots, the Parlor's last two slot machines: at x 11 and x 13 on the back row, the two places
     * kept free for them. Each is in the Parlor's air (two blocks of it, a cabinet's size), on the floor, with its
     * back to the back wall and room to stand in front, facing the room, and clear of every other machine in the
     * Parlor and of the Scratch Ticket's booth. Displays only: no block is theirs (the plan's hash and
     * {@link ClubhousePlanner#ALGO} are plan 10's, Mob Races' table), and the machines that were there before stand
     * exactly where they did.
     */
    @Test
    void deepMineAndMobSlotsStandAtTheBackRowsLastTwoPlaces() {
        Map<Long, String> b = blocks(SITE.plan());
        List<ClubhousePlanner.Place3> row = ClubhousePlanner.parlorRow(BOX);
        List<String> games = ClubhousePlanner.PARLOR_ROW_GAMES;
        assertEquals(3, games.indexOf("deep_mine"), "after the three that were there");
        assertEquals(4, games.indexOf("mob_slots"));
        // the three that were there have not moved
        int[] were = {3, 5, 9};
        for (int n = 0; n < were.length; n++) {
            assertEquals(BOX.minX() + were[n] + 0.5, row.get(n).x(), 1e-9, games.get(n) + " stands where it did");
        }
        ClubhousePlanner.Place3 booth = ClubhousePlanner.parlorTicket(BOX);
        int floorY = BOX.minY() + ClubhousePlanner.UP_FLOOR + 1;
        int backZ = BOX.minZ() + ClubhousePlanner.SIDE_Z1;
        int[][] mine = {{3, 11}, {4, 13}};
        for (int[] at : mine) {
            String game = games.get(at[0]);
            ClubhousePlanner.Place3 m = row.get(at[0]);
            int x = BOX.minX() + at[1];
            assertEquals(x + 0.5, m.x(), 1e-9, game + " stands in the middle of x " + at[1]);
            assertEquals(floorY, m.y(), 1e-9, game + " stands on the Parlor's floor");
            assertEquals(backZ + 0.5, m.z(), 1e-9, game + " is on the back row");
            assertEquals(180f, m.yaw(), game + " faces the room");
            assertTrue(ClubhousePlanner.parlorInside(BOX).contains(x, floorY, backZ), game + " stands in the Parlor");
            assertTrue(ClubhousePlanner.parlorInside(BOX).contains(x, floorY + 1, backZ), game + ": all of it");
            assertNull(b.get(key(x, floorY, backZ)), game + " stands in air");
            assertNull(b.get(key(x, floorY + 1, backZ)), game + ": two blocks of it");
            assertNotNull(b.get(key(x, floorY - 1, backZ)), game + " is on the floor");
            assertNotNull(b.get(key(x, floorY, backZ + 1)), game + " has its back to the wall");
            assertNotNull(b.get(key(x, floorY + 1, backZ + 1)), game + ": the wall is behind all of it");
            String front = b.get(key(x, floorY, backZ - 1));
            assertTrue(front == null || id(front).endsWith("_carpet"), game + ": room to stand in front: " + front);
            assertNull(b.get(key(x, floorY + 1, backZ - 1)), game + ": head room there");
            // clear of every other machine in the room, the booth, and the rest of the row
            List<ClubhousePlanner.Place3> others = new java.util.ArrayList<>(ClubhousePlanner.parlorMachines(BOX));
            others.add(booth);
            for (int n = 0; n < row.size(); n++) {
                if (n != at[0]) {
                    others.add(row.get(n));
                }
            }
            for (ClubhousePlanner.Place3 o : others) {
                assertTrue(Math.abs(o.x() - m.x()) + Math.abs(o.z() - m.z()) >= 2, game + " is clear of " + o);
            }
            assertEquals(com.dierks.homecraft.games.GameKind.CHANCE,
                    com.dierks.homecraft.games.GameCatalog.spec(game).kind(), game + " is a game of chance");
            assertEquals(com.dierks.homecraft.pack.ClubModels.Kind.CABINET,
                    com.dierks.homecraft.pack.ClubModels.machineFor(game).kind(), game + " is a cabinet's size");
        }
        assertEquals("machine_deepmine", com.dierks.homecraft.pack.ClubModels.machineFor("deep_mine").name());
        assertEquals("machine_mobs", com.dierks.homecraft.pack.ClubModels.machineFor("mob_slots").name());
        assertEquals(HASH, SITE.plan().hash(), "no block is theirs: they are displays only");
        assertEquals(13, ClubhousePlanner.ALGO, "plan 10 was Mob Races' table, 11 the Arcade Room's, 13 the grown wing;"
                + " the machines changed no plan");
        assertEquals(6, ClubhousePlanner.parlorMachines(BOX).size(), "the first six machines are where they were");
    }

    /** Upstairs is shut in: walls round both rooms, a floor under them, the glass roof over them. */
    @Test
    void theUpstairsRoomsAreShutIn() {
        Map<Long, String> b = blocks(SITE.plan());
        for (int x = BOX.minX(); x <= BOX.maxX(); x++) {
            for (int z = BOX.minZ() + ClubhousePlanner.MAIN_Z; z <= BOX.maxZ(); z++) {
                assertNotNull(b.get(key(x, BOX.minY() + ClubhousePlanner.UP_FLOOR, z)), "a floor upstairs");
                assertTrue(ClubhousePlanner.noFooting(b.get(key(x, BOX.minY() + ClubhousePlanner.ROOF, z))),
                        "glass over upstairs");
                boolean edge = x == BOX.minX() || x == BOX.maxX() || x == BOX.minX() + 15 || x == BOX.minX() + 16
                        || z == BOX.minZ() + ClubhousePlanner.MAIN_Z || z == BOX.maxZ();
                for (int y = BOX.minY() + ClubhousePlanner.UP_FLOOR + 1; edge && y <= BOX.minY()
                        + ClubhousePlanner.UP_TOP; y++) {
                    assertNotNull(b.get(key(x, y, z)), "a wall upstairs at " + (x - BOX.minX()) + "," + (y - BOX.minY())
                            + "," + (z - BOX.minZ()));
                }
            }
        }
        // a box without the upstairs rooms leaves that space air
        Map<Long, String> two = blocks(ClubhousePlanner.plan(BOX, new ClubhousePlanner.Rooms(true, true)).plan());
        assertNull(two.get(key(BOX.minX() + 5, BOX.minY() + ClubhousePlanner.UP_FLOOR, BOX.minZ() + 40)),
                "the side rooms alone: nothing upstairs");
        assertFalse(new ClubhousePlanner.Rooms(true, false, true, true).secret(),
                "no secret room without the Trophy Room (its way in)");
    }

    /** A 0.42.0 save (main, arcade, trophy) gains the two rooms upstairs from HCM's plan. */
    @Test
    void aSaveFrom042GainsTheUpstairsRooms() {
        Map<Long, String> b = blocks(SITE.plan());
        Map<Long, String> old = new java.util.HashMap<>(b);
        for (long k : List.copyOf(old.keySet())) {
            // what 0.42.0 built: nothing above the side rooms' roof
            if (ClubhousePlanner.regions(BOX, ClubhousePlanner.Rooms.BOTH).get("secret").contains(x(k), y(k), z(k))
                    || ClubhousePlanner.regions(BOX, ClubhousePlanner.Rooms.BOTH).get("parlor").contains(x(k), y(k),
                    z(k))) {
                old.remove(k);
            }
        }
        Plan snap = ClubSave.snapshot(BOX, at -> old.get(key(at[0], at[1], at[2])));
        ClubSave.Saved saved = ClubSave.read(ClubSave.text(snap, null, Set.of("main", "arcade", "trophy")), BOX);
        Map<Long, String> merged = blocks(ClubSave.merged(saved, SITE.plan(),
                ClubhousePlanner.regions(BOX, ClubhousePlanner.Rooms.BOTH)));
        assertEquals(b, merged, "the saved room, with the upstairs rooms added");
    }

    @Test
    void theParlorsCardTableChairsHaveCushions() {
        // 0.50.0 (the owner: "put some cushions on these ... seats"): four chairs at each of the two card tables
        List<ClubhousePlanner.Seat> up = ClubhousePlanner.parlorSeats(BOX);
        assertEquals(8 + 2 + 3, up.size(), "the card tables' eight, plan 13's lounge armchairs and sportsbook stools");
        assertEquals(8, ClubhousePlanner.parlorSeats(OLD).size(), "a Parlor at its old size: the card tables' eight");
        assertEquals(ClubhousePlanner.parlorSeats(OLD), up.subList(0, 8), "the card tables' chairs first, where they"
                + " were (Twenty-One's four are the regulars' table)");
        for (ClubhousePlanner.Seat s : up) {
            assertTrue(ClubhousePlanner.parlorInside(BOX).contains((int) Math.floor(s.x()), (int) Math.floor(s.y()),
                    (int) Math.floor(s.z())), "a cushion in the Parlor: " + s);
        }
        for (ClubhousePlanner.Seat s : ClubhousePlanner.seats(BOX)) {
            assertFalse(up.contains(s), "and not one of the rooms downstairs' twice");
        }
    }
}
