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

    /** The 0.35 box: the golden hash names blocks where they stand, so it never follows the shipped origin. */
    private static final Box BOX = LegacyBoxes.clubhouse();
    private static final ClubhouseSite SITE = ClubhousePlanner.plan(BOX);
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

    private static String id(String state) {
        return state == null ? "minecraft:air" : ClubhousePlanner.id(state);
    }

    @Test
    void theGoldenHashPinsTheRoom() {
        assertEquals("fd748114a533", SITE.plan().hash(), "the Clubhouse's layout is pinned: a change is a decision"
                + " (and then ClubhousePlanner.ALGO goes up, so every server rebuilds it once)");
        assertEquals(SITE.plan().hash(), ClubhousePlanner.plan(BOX).plan().hash(), "the same box always gives the same room");
        Box moved = BOX.translate(160, 0, -64);
        assertEquals(SITE.plan().ops().size(), ClubhousePlanner.plan(moved).plan().ops().size(),
                "moving the box moves the same room");
        assertNotEquals(SITE.plan().hash(), ClubhousePlanner.plan(moved).plan().hash(), "a hash names the blocks where"
                + " they are");
        assertEquals(4, ClubhousePlanner.ALGO, "the golf club: every server builds it once");
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

    @Test
    void thePodiumHasThreeHeightsFirstOnTheMiddleLineAndHighest() {
        Map<Long, String> b = blocks(SITE.plan());
        List<ClubhouseSite.Spot> p = SITE.podium();
        assertEquals(3, p.size(), "1st, 2nd and 3rd");
        assertTrue(p.get(0).y() > p.get(1).y() && p.get(1).y() > p.get(2).y(), "three distinct heights, 1st highest: "
                + p);
        assertEquals(BOX.minX() + 16.0, p.get(0).x(), 1e-9, "1st on the room's middle line, between blocks 15 and 16");
        assertEquals(p.get(0).x() - p.get(1).x(), p.get(2).x() - p.get(0).x(), 1e-9, "2nd and 3rd mirror each other");
        for (ClubhouseSite.Spot s : p) {
            // a 2 x 2 plinth: the spot is its middle, so all four blocks under the feet are the plinth's top
            for (double dx : new double[]{-0.5, 0.5}) {
                for (double dz : new double[]{-0.5, 0.5}) {
                    assertNotNull(at(b, s.x() + dx, s.y() - 1, s.z() + dz), "each place stands on its plinth: " + s);
                    assertNull(at(b, s.x() + dx, s.y(), s.z() + dz), "air at the feet");
                    assertNull(at(b, s.x() + dx, s.y() + 1, s.z() + dz), "and at the head");
                }
            }
            assertEquals(0f, s.yaw(), "the podium faces the room (south)");
        }
        assertEquals(ClubhousePlanner.GOLD, at(b, p.get(0).x(), p.get(0).y() - 1, p.get(0).z()), "1st on gold");
        assertEquals(ClubhousePlanner.IRON, at(b, p.get(1).x(), p.get(1).y() - 1, p.get(1).z()), "2nd on iron");
        assertEquals(ClubhousePlanner.COPPER, at(b, p.get(2).x(), p.get(2).y() - 1, p.get(2).z()), "3rd on copper");
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
                    if (here != null && !id(here).endsWith("_carpet")) {
                        continue; // no room to stand
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
        // the roof's top, outside, is glass: nothing stands on it
        for (int x = BOX.minX(); x <= BOX.maxX(); x++) {
            for (int z = BOX.minZ(); z <= BOX.maxZ(); z++) {
                assertTrue(ClubhousePlanner.noFooting(b.get(key(x, BOX.minY() + ClubhousePlanner.ROOF, z))), "glass");
            }
        }
        assertEquals(15, ClubhousePlanner.lightOf(ClubhousePlanner.HANGING));
        assertEquals(9, ClubhousePlanner.lightOf(ClubhousePlanner.CANDLES), "three candles, 3 each");
        assertEquals(0, ClubhousePlanner.lightOf("minecraft:campfire[lit=false]"));
        assertFalse(ClubhousePlanner.letsLight(ClubhousePlanner.STOOL), "a slab stops the flood");
        assertTrue(ClubhousePlanner.noFooting(ClubhousePlanner.HANGING) && !ClubhousePlanner.noFooting(ClubhousePlanner.CLOTH));
    }

    @Test
    void theRoomHasNoWayOut() {
        Map<Long, String> b = blocks(SITE.plan());
        for (int x = BOX.minX(); x <= BOX.maxX(); x++) {
            for (int z = BOX.minZ(); z <= BOX.maxZ(); z++) {
                assertEquals(ClubhousePlanner.ROOF_GLASS, b.get(key(x, BOX.minY() + ClubhousePlanner.ROOF, z)),
                        "the roof is closed over " + x + "," + z);
                assertNotNull(b.get(key(x, BOX.minY(), z)), "the floor is whole at " + x + "," + z);
            }
        }
        for (int y = BOX.minY(); y <= BOX.minY() + ClubhousePlanner.WALL_TOP; y++) {
            for (int i = 0; i < ClubhouseSettings.SIZE_X; i++) {
                assertNotNull(b.get(key(BOX.minX() + i, y, BOX.minZ())), "north wall");
                assertNotNull(b.get(key(BOX.minX() + i, y, BOX.maxZ())), "south wall");
                assertNotNull(b.get(key(BOX.minX(), y, BOX.minZ() + i)), "west wall");
                assertNotNull(b.get(key(BOX.maxX(), y, BOX.minZ() + i)), "east wall");
            }
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
            if (ClubhousePlanner.CLOTH.equals(front) || (front != null && id(front).endsWith("_fence"))) {
                atTables++;
                continue;
            }
            String left = b.get(key(op.x() + dz, op.y(), op.z() + dx));
            String right = b.get(key(op.x() - dz, op.y(), op.z() - dx));
            assertTrue(left != null && left.contains("_trapdoor") && left.contains("open=true") && right != null
                    && right.contains("_trapdoor") && right.contains("open=true"), "an armchair has two arms: " + op);
            armchairs++;
        }
        assertEquals(2 * 6 + 2 * 4 + 4, atTables, "three a side at each banquet table, four at each 3 x 3 table, four at"
                + " the card table");
        assertEquals(6, armchairs, "two in the reading nook, two by the fire, two facing the trophies");
    }

    /**
     * The hall and the foyer (x 8-23, the columns included) mirror about the middle line (x 16.0): every block's
     * mirror is the same block turned (east for west), but the podium's iron and copper and the Feast Pot's flowers.
     */
    @Test
    void theHallAndTheFoyerMirrorAboutTheMiddleLine() {
        Map<Long, String> b = blocks(SITE.plan());
        Set<String> pairs = Set.of(ClubhousePlanner.IRON + "|" + ClubhousePlanner.COPPER,
                ClubhousePlanner.POT + "|" + ClubhousePlanner.FLOWERS);
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
        assertEquals(ClubhousePlanner.POT, b.get(key(pot[0], pot[1], pot[2])), "the Feast Pot");
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
        assertEquals(ClubhousePlanner.BOOKS, b.get(key(j[0], j[1] + 1, j[2])), "books over it");
        for (int d = 1; d <= 3; d++) {
            assertEquals(ClubhousePlanner.BOOKS, b.get(key(j[0], j[1], j[2] - d)), "books either side");
            assertEquals(ClubhousePlanner.BOOKS, b.get(key(j[0], j[1], j[2] + d)), "books either side");
        }
        String front = b.get(key(j[0] + 1, j[1], j[2]));
        assertTrue(front == null || id(front).endsWith("_carpet"), "room to tap it: " + front);
        assertNull(b.get(key(j[0] + 1, j[1] + 1, j[2])), "and to stand there");
        List<int[]> frames = ClubhousePlanner.trophyFrames(BOX);
        assertEquals(ClubBoard.MAX_ROWS, frames.size(), "one frame per record row, at most");
        Set<String> seen = new HashSet<>();
        WallBoards.Wall fame = WallBoards.generated(WallBoards.FAME, BOX);
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
}
