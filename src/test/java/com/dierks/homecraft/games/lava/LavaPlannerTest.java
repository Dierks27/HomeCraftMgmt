package com.dierks.homecraft.games.lava;

import com.dierks.homecraft.games.clubhouse.ClubhousePlanner;
import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Plan;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Floor Is Lava's playroom: pinned by its hash; shut in; lit everywhere a mob could stand (the Clubhouse's own
 * light flood); built only of blocks that can't burn, each a whole block or a bottom slab; a walk from the floor to
 * dry ground and on to the top that never steps up more than half a block; dry, standable ground for a full room at
 * every lava level up to the last, and none at the flood; and a gallery nothing leads to.
 */
class LavaPlannerTest {

    private static final Box BOX = LavaSettings.defaults().box();
    private static final LavaSite SITE = LavaPlanner.plan(BOX);
    private static final int Y0 = BOX.minY();
    /** The least block light over anything a mob could stand on: spawning needs 0; a margin, and it reads lit. */
    private static final int LIT = 4;

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

    private static String id(String state) {
        return state.contains("[") ? state.substring(0, state.indexOf('[')) : state;
    }

    private static String where(int x, int y, int z) {
        return (x - BOX.minX()) + "," + (y - Y0) + "," + (z - BOX.minZ());
    }

    // ---- the plan -----------------------------------------------------------------------------------

    @Test
    void theGoldenHashPinsTheRoom() {
        assertEquals("a0e14412e8a5", SITE.plan().hash(), "the playroom's layout is pinned: a change is a decision"
                + " (and a new LavaPlanner.ALGO)");
        assertEquals(SITE.plan().hash(), LavaPlanner.plan(BOX).plan().hash(), "the same box always gives the same room");
        assertEquals(1, LavaPlanner.ALGO);
        assertEquals(LavaPlanner.SLOT, SITE.plan().slot());
        Box moved = BOX.translate(16, 0, 0);
        assertNotEquals(SITE.plan().hash(), LavaPlanner.plan(moved).plan().hash(), "a hash names the blocks where"
                + " they stand");
        assertEquals(SITE.plan().ops().size(), LavaPlanner.plan(moved).plan().ops().size(), "the same room, moved");
        assertEquals(List.of(32, 16, 32), List.of(BOX.sizeX(), BOX.sizeY(), BOX.sizeZ()), "32 x 16 x 32: it builds fast");
        assertTrue(SITE.plan().ops().size() < 9000, "a modest build: " + SITE.plan().ops().size() + " blocks");
        assertThrows(IllegalArgumentException.class, () -> LavaPlanner.plan(Box.sized(0, 64, 0, 48, 40, 48)),
                "the room is made only for its own size");
        assertThrows(IllegalArgumentException.class, () -> LavaPlanner.plan(null));
    }

    @Test
    void everyBlockIsInsideTheBoxOncePerPlace() {
        Set<Long> seen = new HashSet<>();
        for (BlockOp op : SITE.plan().ops()) {
            assertTrue(BOX.contains(op.x(), op.y(), op.z()), "block " + op + " is inside the box");
            assertTrue(seen.add(key(op.x(), op.y(), op.z())), "one op per block: " + op);
        }
        assertEquals(BOX, SITE.box(), "the plan's half is the box");
        assertEquals(List.of(), SITE.plan().signs(), "no signs: nothing in the room needs reading");
    }

    /**
     * Harmless lava needs a room that can't burn: every block is on the planner's fireproof list (stone, concrete,
     * terracotta, quartz, prismarine, sandstone, copper, gold, glass, lamps), none is wood, wool, carpet, leaves, a
     * bookshelf or anything else lava sets alight, and each is a block this jar's API knows.
     */
    @Test
    void nothingInTheRoomCanBurnAndEveryBlockIsABlockOfThisApi() {
        Set<String> used = new TreeSet<>();
        for (String state : SITE.plan().palette()) {
            String id = id(state);
            used.add(id);
            assertTrue(LavaPlanner.FIREPROOF.contains(id), "on the fireproof list: " + state);
            String name = id.substring("minecraft:".length());
            for (String burns : List.of("planks", "log", "wood", "wool", "carpet", "leaves", "bookshelf", "bamboo",
                    "hay", "banner", "bed", "fence", "door", "coal", "tnt", "scaffold", "vine", "moss", "azalea")) {
                assertFalse(name.contains(burns), "nothing that burns: " + state);
            }
            Material m = Material.getMaterial(name.toUpperCase(Locale.ROOT));
            assertNotNull(m, "a block of this API: " + state);
            if (name.endsWith("_slab")) {
                assertTrue(state.contains("type=bottom"), "every slab is a bottom slab (half a block up): " + state);
            }
            for (String thin : List.of("stairs", "wall", "pane", "trapdoor", "lantern[", "chain", "torch", "candle",
                    "button", "pressure", "sign", "rod", "bars", "layer", "pot")) {
                assertFalse((name + "[").contains(thin) && !name.equals("sea_lantern"), "everything but a slab is a"
                        + " whole block: " + state);
            }
            assertFalse(name.contains("lava") || name.contains("water") || name.contains("magma")
                    || name.contains("fire"), "no fluids, magma or fire in the plan itself: " + state);
        }
        assertEquals(LavaPlanner.FIREPROOF, used, "the list is exactly what the room is built of");
    }

    // ---- shut in ------------------------------------------------------------------------------------

    @Test
    void theRoomHasNoWayOut() {
        Map<Long, String> b = blocks(SITE.plan());
        for (int x = BOX.minX(); x <= BOX.maxX(); x++) {
            for (int z = BOX.minZ(); z <= BOX.maxZ(); z++) {
                assertNotNull(b.get(key(x, Y0, z)), "the floor is whole at " + where(x, Y0, z));
                String roof = b.get(key(x, Y0 + LavaPlanner.ROOF, z));
                assertNotNull(roof, "the roof is closed over " + where(x, Y0 + LavaPlanner.ROOF, z));
                assertTrue(ClubhousePlanner.noFooting(roof) || ClubhousePlanner.lightOf(roof) == 15,
                        "the roof is glass (nothing stands on it) or a lamp (never dark on top): " + roof);
            }
        }
        for (int y = Y0; y <= BOX.maxY(); y++) {
            for (int n = 0; n < 32; n++) {
                assertNotNull(b.get(key(BOX.minX() + n, y, BOX.minZ())), "north wall at " + n + "," + (y - Y0));
                assertNotNull(b.get(key(BOX.minX() + n, y, BOX.maxZ())), "south wall at " + n + "," + (y - Y0));
                assertNotNull(b.get(key(BOX.minX(), y, BOX.minZ() + n)), "west wall at " + n + "," + (y - Y0));
                assertNotNull(b.get(key(BOX.maxX(), y, BOX.minZ() + n)), "east wall at " + n + "," + (y - Y0));
            }
        }
        int panes = 0;
        for (int z = BOX.minZ(); z <= BOX.maxZ(); z++) {
            for (int y = Y0; y <= BOX.maxY(); y++) {
                panes += LavaPlanner.WINDOW.equals(b.get(key(BOX.maxX(), y, z))) ? 1 : 0;
            }
        }
        assertTrue(panes >= 50, "a big window with daylight in the east wall: " + panes + " panes");
        assertEquals(new Box(BOX.minX() + 1, Y0 + 1, BOX.minZ() + 1, BOX.maxX() - 1, BOX.maxY() - 1, BOX.maxZ() - 1),
                SITE.inside(), "the room's air is everything inside the shell");
    }

    /**
     * Block light is flooded from every lamp through the blocks light passes (never through a slab, so it never counts
     * more than the game gives), and every place a mob could stand has at least {@value #LIT}: the floor, under the
     * table, the shelves, every step, every top, the gallery. Mobs spawn only at block light 0.
     */
    @Test
    void everyPlaceAMobCouldStandIsLit() {
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
        assertTrue(q.size() > 100, "lamps in the floor, the walls, the roof and the furniture: " + q.size());
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
        List<String> dark = new ArrayList<>();
        for (int x = BOX.minX() + 1; x < BOX.maxX(); x++) {
            for (int z = BOX.minZ() + 1; z < BOX.maxZ(); z++) {
                for (int y = Y0 + 1; y < BOX.maxY(); y++) {
                    String below = b.get(key(x, y - 1, z));
                    if (below == null || ClubhousePlanner.noFooting(below) || b.get(key(x, y, z)) != null) {
                        continue; // nothing to stand on, or no room to stand
                    }
                    places++;
                    int l = light.getOrDefault(key(x, y, z), 0);
                    least = Math.min(least, l);
                    if (l < LIT) {
                        dark.add(where(x, y, z) + "=" + l);
                    }
                }
            }
        }
        assertEquals(List.of(), dark, "every place lit");
        assertTrue(places > 1000, "the whole floor, every step and every top is checked: " + places);
        assertTrue(least >= LIT, "least light " + least);
        assertEquals(15, ClubhousePlanner.lightOf(LavaPlanner.LAMP));
        assertEquals(15, ClubhousePlanner.lightOf(LavaPlanner.BUTTON));
        assertFalse(ClubhousePlanner.letsLight(LavaPlanner.PAGE), "a slab stops the flood");
        assertTrue(ClubhousePlanner.noFooting(LavaPlanner.ROOF_GLASS) && ClubhousePlanner.noFooting(LavaPlanner.RAIL));
    }

    // ---- standing, climbing, staying dry ------------------------------------------------------------------

    private static Map<Long, List<LavaSite.Stand>> byColumn(List<LavaSite.Stand> stands) {
        Map<Long, List<LavaSite.Stand>> out = new HashMap<>();
        for (LavaSite.Stand s : stands) {
            out.computeIfAbsent(key(s.x(), 0, s.z()), k -> new ArrayList<>()).add(s);
        }
        return out;
    }

    /** Every stand reachable from {@code from} by stepping to a neighbouring column, up at most {@code up} at a time. */
    private static Set<LavaSite.Stand> climb(Map<Long, List<LavaSite.Stand>> columns, LavaSite.Stand from, double up) {
        Set<LavaSite.Stand> seen = new HashSet<>(Set.of(from));
        ArrayDeque<LavaSite.Stand> q = new ArrayDeque<>(List.of(from));
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!q.isEmpty()) {
            LavaSite.Stand a = q.poll();
            for (int[] d : dirs) {
                for (LavaSite.Stand n : columns.getOrDefault(key(a.x() + d[0], 0, a.z() + d[1]), List.of())) {
                    // up a step, level, or down no more than a step either: a walk, never a drop
                    if (Math.abs(n.y() - a.y()) <= up + 1e-9 && seen.add(n)) {
                        q.add(n);
                    }
                }
            }
        }
        return seen;
    }

    @Test
    void everyStandHasItsBlockUnderItAndTwoBlocksOfAirOverIt() {
        Map<Long, String> b = blocks(SITE.plan());
        assertTrue(SITE.stands().size() > 1000, "stands: " + SITE.stands().size());
        for (LavaSite.Stand s : SITE.stands()) {
            boolean half = s.y() % 1 != 0;
            int under = (int) Math.floor(s.y()) - (half ? 0 : 1);
            String block = b.get(key(s.x(), under, s.z()));
            assertNotNull(block, "something to stand on at " + s);
            assertEquals(half, block.contains("_slab"), "a half height is a slab's top: " + s + " on " + block);
            assertNull(b.get(key(s.x(), under + 1, s.z())), "air at the feet or knees: " + s);
            assertNull(b.get(key(s.x(), under + 2, s.z())), "air at the head: " + s);
            assertTrue(SITE.inside().contains(s.x(), (int) Math.floor(s.y()), s.z()), "in the room: " + s);
        }
    }

    /**
     * The low route, for a four-year-old with a controller: from anywhere on the floor she can walk (never a step of
     * more than half a block, never a jump, never a drop) onto dry ground, and on up to the highest ground there is.
     */
    @Test
    void aWalkFromTheFloorToDryGroundAndToTheTopNeverStepsUpMoreThanHalfABlock() {
        Map<Long, List<LavaSite.Stand>> columns = byColumn(SITE.reach());
        LavaSite.Stand floor = null;
        for (LavaSite.Stand s : SITE.starStands()) {
            floor = s;
            break;
        }
        assertNotNull(floor, "open floor");
        Set<LavaSite.Stand> walk = climb(columns, floor, LavaSite.STEP);
        double top = Y0 + 1;
        Set<Double> heights = new TreeSet<>();
        for (LavaSite.Stand s : walk) {
            top = Math.max(top, s.y());
            heights.add(s.y() - Y0);
        }
        assertEquals(Y0 + LavaPlanner.FLOOD, top, "the walk reaches the highest ground: the bookcase's second shelf");
        for (double h = 1.0; h <= LavaPlanner.FLOOD; h += 0.5) {
            assertTrue(heights.contains(h), "a step at every half block from the floor to the top: " + h + " of "
                    + heights);
        }
        for (LavaSite.Stand s : SITE.reach()) {
            assertTrue(walk.contains(s), "every place a player can reach is on a walk of half steps: " + s + " ("
                    + where(s.x(), (int) Math.floor(s.y()), s.z()) + ")");
        }
        // and from every star's place on the floor, dry ground (feet a block up) is a short walk away
        for (LavaSite.Stand s : SITE.starStands()) {
            assertTrue(walk.contains(s), "the floor is one room: " + s);
            LavaSite.Spot safe = SITE.nearestSafe(1, s.x() + 0.5, s.y(), s.z() + 0.5);
            assertNotNull(safe);
            assertTrue(Math.hypot(safe.x() - s.x() - 0.5, safe.z() - s.z() - 0.5) <= 12, "dry ground is never far: "
                    + s + " to " + safe);
        }
        // the steps are wide: a step of half a block always has another of its height beside it
        for (LavaSite.Stand s : SITE.reach()) {
            if (s.y() % 1 == 0 || s.y() - Y0 > LavaPlanner.FLOOD) {
                continue;
            }
            boolean pair = false;
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                for (LavaSite.Stand n : columns.getOrDefault(key(s.x() + d[0], 0, s.z() + d[1]), List.of())) {
                    pair |= n.y() == s.y();
                }
            }
            assertTrue(pair, "a half step is at least two blocks wide: " + where(s.x(), (int) Math.floor(s.y()), s.z()));
        }
    }

    @Test
    void thePlayAreaIsEverythingButTheGalleryAndTheBookcasesTop() {
        Set<LavaSite.Stand> reach = new HashSet<>(SITE.reach());
        int gallery = 0;
        for (LavaSite.Stand s : SITE.stands()) {
            if (reach.contains(s)) {
                assertFalse(SITE.inGallery(s.x() + 0.5, s.y(), s.z() + 0.5), "the gallery is no part of the play area");
                assertTrue(s.y() <= Y0 + LavaPlanner.FLOOD, "nothing a player can reach is over the flood: " + s);
                continue;
            }
            boolean inGallery = SITE.inGallery(s.x() + 0.5, s.y(), s.z() + 0.5);
            gallery += inGallery ? 1 : 0;
            assertTrue(inGallery || s.y() >= Y0 + LavaPlanner.CASE_TOP + 1, "the only places nobody can walk to are"
                    + " the gallery and the bookcase's top: " + where(s.x(), (int) Math.floor(s.y()), s.z()));
            // and no jump gets there either: the nearest reachable ground is more than a jump below
            assertTrue(s.y() - (Y0 + LavaPlanner.FLOOD) >= 3, "out of a jump's reach: " + s);
        }
        assertEquals(3 * 18, gallery, "the gallery's walk: three wide, eighteen long");
        assertEquals(LavaPlanner.FLOOD, SITE.flood(), "the flood level is the play area's highest ground");
        assertEquals(LavaPlanner.SAFE_TOP, SITE.flood() - 1);
    }

    @Test
    void dryStandableGroundForAFullRoomAtEveryLavaLevelAndNoneAtTheFlood() {
        Map<Long, String> b = blocks(SITE.plan());
        int before = Integer.MAX_VALUE;
        for (int level = 0; level <= LavaPlanner.SAFE_TOP; level++) {
            List<LavaSite.Stand> safe = SITE.safe(level);
            assertTrue(safe.size() >= LavaSettings.MAX_PLAYERS, "room for the most players config allows ("
                    + LavaSettings.MAX_PLAYERS + ") at level " + level + ": " + safe.size());
            assertTrue(safe.size() <= before, "less dry ground as the lava rises");
            assertTrue(level == 0 || safe.size() < before, "each level takes some ground: level " + level);
            before = safe.size();
            for (LavaSite.Stand s : safe) {
                assertTrue(s.y() >= Y0 + 1 + level, "dry: feet a whole block over the lava's top layer: " + s);
                assertFalse(SITE.touches(level, s.x() + 0.5, s.y(), s.z() + 0.5), "standing there is not in the lava: "
                        + s + " at level " + level);
                assertFalse(SITE.touches(level, s.x() + 0.31, s.y(), s.z() + 0.69), "nor at its edge: " + s);
                assertFalse(SITE.inGallery(s.x() + 0.5, s.y(), s.z() + 0.5), "never the gallery");
                int under = (int) Math.floor(s.y()) - (s.y() % 1 != 0 ? 0 : 1);
                assertNotNull(b.get(key(s.x(), under, s.z())), "standable: " + s);
                assertTrue(under >= Y0 + level, "what it stands on is no lower than the lava's top layer: " + s);
            }
        }
        assertEquals(List.of(), SITE.safe(LavaPlanner.FLOOD), "at the flood nothing is dry: Challenge always ends");
        assertNull(SITE.nearestSafe(LavaPlanner.FLOOD, BOX.minX() + 16, Y0 + 1, BOX.minZ() + 16));
        // at least three heights of safe ground, so low furniture goes under while higher pieces stay dry
        Set<Double> heights = new TreeSet<>();
        for (LavaSite.Stand s : SITE.safe(1)) {
            if (s.y() % 1 == 0) {
                heights.add(s.y() - Y0);
            }
        }
        assertEquals(new TreeSet<>(List.of(2.0, 3.0, 4.0, 5.0, 6.0, 7.0)), heights, "six whole heights of dry ground");
        // the wide flat tops: each whole height has a generous place to stand
        for (int h = 2; h <= 7; h++) {
            int n = 0;
            for (LavaSite.Stand s : SITE.reach()) {
                n += s.y() == Y0 + h ? 1 : 0;
            }
            assertTrue(n >= 8, "plenty of room at height " + h + ": " + n + " blocks");
        }
    }

    @Test
    void theNearestSafeSpotIsARealDryStandFacingIntoTheRoom() {
        for (int level = 0; level <= LavaPlanner.SAFE_TOP; level++) {
            Set<String> safe = new HashSet<>();
            for (LavaSite.Stand s : SITE.safe(level)) {
                safe.add((s.x() + 0.5) + "," + s.y() + "," + (s.z() + 0.5));
            }
            for (double[] from : new double[][]{{16, 1, 16}, {2.5, 1, 2.5}, {29.5, 1, 29.5}, {15.5, 1, 12.5},
                    {28.3, 1, 3.7}, {7.2, 1, 28.9}, {16, 4, 16.5}}) {
                double x = BOX.minX() + from[0];
                double y = Y0 + from[1];
                double z = BOX.minZ() + from[2];
                LavaSite.Spot spot = SITE.nearestSafe(level, x, y, z);
                assertNotNull(spot, "there is dry ground at level " + level);
                assertTrue(safe.contains(spot.x() + "," + spot.y() + "," + spot.z()), "a dry stand of the play area: "
                        + spot + " at level " + level);
                assertEquals(SITE.facing(spot.x(), spot.z()), spot.yaw(), "facing the middle of the room");
                double best = Math.hypot(spot.x() - x, spot.z() - z);
                for (LavaSite.Stand s : SITE.safe(level)) {
                    // nothing dry is much nearer on the ground (height counts for half)
                    double d = Math.hypot(s.x() + 0.5 - x, s.z() + 0.5 - z);
                    assertTrue(d + 4 >= best, "the nearest, give or take its height: " + spot + " against " + s);
                }
            }
        }
        // the yaw looks at the middle: from the north it looks south (0), from the west it looks east (-90)
        assertEquals(0f, SITE.facing(BOX.minX() + 16, BOX.minZ() + 2), 0.01f);
        assertEquals(-90f, SITE.facing(BOX.minX() + 2, BOX.minZ() + 16), 0.01f);
        assertEquals(180f, Math.abs(SITE.facing(BOX.minX() + 16, BOX.minZ() + 30)), 0.01f);
    }

    @Test
    void whereTheLavaGoesAndWhoIsInIt() {
        Map<Long, String> b = blocks(SITE.plan());
        Set<Long> all = new HashSet<>();
        for (int level = 1; level <= LavaPlanner.FLOOD; level++) {
            List<int[]> cells = SITE.layer(level);
            assertTrue(cells.size() > 400, "a layer of lava wall to wall, less the furniture: " + cells.size());
            int open = 0;
            for (int x = BOX.minX() + 1; x < BOX.maxX(); x++) {
                for (int z = BOX.minZ() + 1; z < BOX.maxZ(); z++) {
                    open += b.get(key(x, Y0 + level, z)) == null ? 1 : 0;
                }
            }
            assertEquals(open, cells.size(), "every air cell of the layer, so the lava has nowhere to flow: " + level);
            for (int[] c : cells) {
                assertEquals(Y0 + level, c[1]);
                assertNull(b.get(key(c[0], c[1], c[2])), "lava only where the plan is air");
                assertTrue(SITE.inside().contains(c[0], c[1], c[2]), "inside the walls");
                assertFalse(SITE.galleryBox().contains(c[0], c[1], c[2]), "never in the gallery");
                assertTrue(all.add(key(c[0], c[1], c[2])));
            }
        }
        assertEquals(List.of(), SITE.layer(0));
        assertEquals(List.of(), SITE.layer(LavaPlanner.FLOOD + 1), "the lava never goes higher than the flood");
        assertTrue(Y0 + LavaPlanner.FLOOD < SITE.galleryBox().minY() - 1, "the gallery is well over the highest lava");

        double cx = BOX.minX() + 16.5;
        double cz = BOX.minZ() + 12.5; // open floor between the footstool and the table
        assertFalse(SITE.touches(0, cx, Y0 + 1, cz), "no lava, no touch");
        assertTrue(SITE.touches(1, cx, Y0 + 1, cz), "on the floor when the lava comes");
        assertTrue(SITE.touches(1, cx, Y0 + 1.5, cz), "a little hop doesn't clear it");
        assertFalse(SITE.touches(1, cx, Y0 + 1.95, cz), "over its surface is dry");
        assertFalse(SITE.touches(1, cx, Y0 + 2, cz), "a whole block up is dry");
        assertTrue(SITE.touches(3, cx, Y0 + 2, cz), "but not when it is three deep");
        // the footstool (feet 2): dry in the middle at level 1, wet at level 2
        double fx = BOX.minX() + 12.5;
        double fz = BOX.minZ() + 8.5;
        assertFalse(SITE.touches(1, fx, Y0 + 2, fz));
        assertTrue(SITE.touches(2, fx, Y0 + 2, fz), "the low furniture goes under as the lava rises");
        // a floor cushion (a slab, feet 1.5): an island in its middle, wet at its edge
        double ux = BOX.minX() + 23.5;
        double uz = BOX.minZ() + 5.5;
        assertFalse(SITE.touches(1, ux, Y0 + 1.5, uz), "the middle of a big cushion is an island");
        assertTrue(SITE.touches(1, ux - 1.3, Y0 + 1.5, uz), "its edge is in the lava beside it");
        // the gallery is never touched
        LavaSite.Spot g = SITE.gallery(0);
        assertFalse(SITE.touches(LavaPlanner.FLOOD, g.x(), g.y(), g.z()));
        // outside the room's air there is no lava at all
        assertFalse(SITE.touches(3, BOX.minX() + 0.5, Y0 + 1, BOX.minZ() + 0.5));
    }

    @Test
    void theFloorThatGlowsIsTheBareFloorAndNeverALamp() {
        Map<Long, String> b = blocks(SITE.plan());
        List<int[]> glow = SITE.glowCells();
        assertTrue(glow.size() > 400, "most of the floor glows before the lava comes: " + glow.size());
        Set<Long> seen = new HashSet<>();
        for (int[] c : glow) {
            assertEquals(Y0, c[1], "the floor's own layer");
            String floor = b.get(key(c[0], c[1], c[2]));
            assertNotNull(floor);
            assertNotEquals(LavaPlanner.LAMP, floor, "a lamp stays a lamp: the room is never dark");
            assertNull(b.get(key(c[0], c[1] + 1, c[2])), "bare floor only: nothing stands on it");
            assertTrue(seen.add(key(c[0], c[1], c[2])));
        }
        assertEquals(SITE.layer(1).size() - 36 + countLampsUnderFurniture(b), glow.size(),
                "every bare floor block but the 36 lamps");
    }

    /** The floor's lamps that have furniture standing on them (they are no part of layer 1, nor of the glow). */
    private static int countLampsUnderFurniture(Map<Long, String> b) {
        int n = 0;
        for (int x = BOX.minX() + 1; x < BOX.maxX(); x++) {
            for (int z = BOX.minZ() + 1; z < BOX.maxZ(); z++) {
                if (LavaPlanner.LAMP.equals(b.get(key(x, Y0, z))) && b.get(key(x, Y0 + 1, z)) != null) {
                    n++;
                }
            }
        }
        return n;
    }

    // ---- arriving, watching, stars ------------------------------------------------------------------------

    @Test
    void playersArriveOnTheSofaOnDryGround() {
        Set<LavaSite.Stand> reach = new HashSet<>(SITE.reach());
        assertTrue(SITE.spawns().size() >= LavaSettings.defaults().maxPlayers(), "a spot each for a shipped room");
        Set<String> seen = new HashSet<>();
        for (LavaSite.Spot s : SITE.spawns()) {
            LavaSite.Stand at = new LavaSite.Stand((int) Math.floor(s.x()), (int) Math.floor(s.z()), s.y());
            assertTrue(reach.contains(at), "an arrival spot is a stand of the play area: " + s);
            assertEquals(Y0 + LavaPlanner.SEAT_FEET, s.y(), "on the sofa's seat");
            assertTrue(SITE.dry(at, 1) && SITE.dry(at, 2), "safe when the lava is one or two deep");
            assertEquals(0f, s.yaw(), "looking into the room (south)");
            assertTrue(seen.add(s.x() + "," + s.z()), "no two on one block");
        }
        assertEquals(SITE.spawn(0), SITE.spawn(SITE.spawns().size()), "round and round");
        assertEquals(SITE.spawn(3), SITE.entry(0, 3), "a dry sofa: the arrival spot");
        assertEquals(SITE.spawn(3), SITE.entry(2, 3));
        LavaSite.Spot high = SITE.entry(4, 3);
        assertTrue(high.y() >= Y0 + 5, "with the lava over the sofa's seat, a joiner arrives on dry ground: " + high);
        assertEquals(SITE.gallery(3), SITE.entry(LavaPlanner.FLOOD, 3), "and at the flood, in the gallery");
    }

    @Test
    void theGalleryIsGlassedInAndNothingLeadsToIt() {
        Map<Long, String> b = blocks(SITE.plan());
        Box g = SITE.galleryBox();
        assertEquals(LavaPlanner.GALLERY_SPOTS, SITE.gallerySpots().size());
        assertTrue(SITE.gallerySpots().size() >= LavaSettings.MAX_PLAYERS, "room for everyone who is out");
        for (LavaSite.Spot s : SITE.gallerySpots()) {
            assertTrue(SITE.inGallery(s.x(), s.y(), s.z()), "a watcher's spot is in the gallery: " + s);
            assertEquals(Y0 + LavaPlanner.GALLERY_FEET, s.y());
            assertNotNull(b.get(key((int) Math.floor(s.x()), (int) s.y() - 1, (int) Math.floor(s.z()))), "on its board");
            assertEquals(-90f, s.yaw(), "looking out over the room (east)");
        }
        for (int z = g.minZ(); z <= g.maxZ(); z++) {
            for (int y = g.minY(); y <= g.maxY(); y++) {
                assertEquals(LavaPlanner.RAIL, b.get(key(g.maxX() + 1, y, z)), "a glass rail two high: nobody falls in");
                assertNotNull(b.get(key(g.minX() - 1, y, z)), "books behind");
                for (int x = g.minX(); x <= g.maxX(); x++) {
                    assertNull(b.get(key(x, y, z)), "the walk is clear");
                }
            }
            for (int x = g.minX(); x <= g.maxX(); x++) {
                assertNotNull(b.get(key(x, g.minY() - 1, z)), "a floor under the walk");
                assertNotNull(b.get(key(x, g.maxY() + 1, z)), "a roof over it");
            }
        }
        for (int x = g.minX(); x <= g.maxX() + 1; x++) {
            for (int y = g.minY(); y <= g.maxY(); y++) {
                assertNotNull(b.get(key(x, y, g.minZ() - 1)), "closed at its north end");
                assertNotNull(b.get(key(x, y, g.maxZ() + 1)), "closed at its south end");
            }
        }
    }

    @Test
    void starsFloatOverOpenFloorSpreadOutAndTheSameForTheSameSeed() {
        Map<Long, String> b = blocks(SITE.plan());
        assertTrue(SITE.starStands().size() >= 100, "plenty of open floor: " + SITE.starStands().size());
        for (LavaSite.Stand s : SITE.starStands()) {
            assertEquals(Y0 + 1, s.y(), "on the floor");
            for (int y = Y0 + 1; y <= Y0 + 4; y++) {
                assertNull(b.get(key(s.x(), y, s.z())), "open over it: never under the table or a shelf");
            }
            int i = s.x() - BOX.minX();
            int k = s.z() - BOX.minZ();
            assertTrue(i >= 3 && i <= 28 && k >= 3 && k <= 28, "away from the walls");
        }
        for (int count : new int[]{1, 10, LavaSettings.MAX_STARS}) {
            List<LavaSite.Spot> spots = SITE.starSpots(count, 42L);
            assertEquals(count, spots.size(), "as many stars as asked");
            assertEquals(spots, SITE.starSpots(count, 42L), "the same seed, the same places");
            Set<String> seen = new HashSet<>();
            for (LavaSite.Spot a : spots) {
                assertTrue(seen.add(a.x() + "," + a.z()), "no two stars on one block");
                for (LavaSite.Spot o : spots) {
                    if (a != o) {
                        assertTrue(Math.max(Math.abs(a.x() - o.x()), Math.abs(a.z() - o.z())) >= 3,
                                "stars stand apart: " + a + " and " + o);
                    }
                }
            }
        }
        assertNotEquals(SITE.starSpots(10, 42L), SITE.starSpots(10, 43L), "another seed, other places");
    }
}
