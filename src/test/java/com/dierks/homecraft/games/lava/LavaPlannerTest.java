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
 * The Floor Is Lava's playroom: the room as built pinned by its hash; and for it and for many of the layouts the
 * furniture moves to (0.57.0): shut in; lit everywhere a mob could stand (the Clubhouse's own light flood); built only
 * of blocks that can't burn, each a whole block or a bottom slab; every piece with steps a walk of half steps from
 * the floor and every stool a jump; dry, standable ground for a full room at every lava level up to the last, and
 * none at the flood; and a gallery nothing leads to.
 */
class LavaPlannerTest {

    private static final Box BOX = LavaSettings.defaults().box();
    private static final LavaSite SITE = LavaPlanner.plan(BOX);
    private static final int Y0 = BOX.minY();
    /** The least block light over anything a mob could stand on: spawning needs 0; a margin, and it reads lit. */
    private static final int LIT = 4;
    /** The room as built and a dozen of the layouts a game moves to. */
    private static final List<LavaSite> ROOMS = rooms();

    private static List<LavaSite> rooms() {
        List<LavaSite> out = new ArrayList<>();
        out.add(SITE);
        for (long seed = 1; seed <= 12; seed++) {
            out.add(LavaPlanner.site(BOX, seed * 7_919L));
        }
        return out;
    }

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
    void theGoldenHashPinsTheRoomAsBuilt() {
        assertEquals("bfa76b5644c6", SITE.plan().hash(), "the playroom as built is pinned: a change is a decision"
                + " (and a new LavaPlanner.ALGO)");
        assertEquals(SITE.plan().hash(), LavaPlanner.plan(BOX).plan().hash(), "the same box always gives the same room");
        assertEquals(2, LavaPlanner.ALGO, "0.57.0: the bigger room whose furniture moves");
        assertEquals(LavaPlanner.SLOT, SITE.plan().slot());
        Box moved = BOX.translate(16, 0, 0);
        assertNotEquals(SITE.plan().hash(), LavaPlanner.plan(moved).plan().hash(), "a hash names the blocks where"
                + " they stand");
        assertEquals(SITE.plan().ops().size(), LavaPlanner.plan(moved).plan().ops().size(), "the same room, moved");
        assertEquals(List.of(48, 16, 48), List.of(BOX.sizeX(), BOX.sizeY(), BOX.sizeZ()), "48 x 16 x 48");
        assertTrue(SITE.plan().ops().size() < 15000, "a modest build: " + SITE.plan().ops().size() + " blocks");
        assertThrows(IllegalArgumentException.class, () -> LavaPlanner.plan(Box.sized(0, 64, 0, 32, 16, 32)),
                "the room is made only for its own size");
        assertThrows(IllegalArgumentException.class, () -> LavaPlanner.plan(null));
    }

    @Test
    void everyBlockIsInsideTheBoxOncePerPlace() {
        for (LavaSite s : ROOMS) {
            Set<Long> seen = new HashSet<>();
            for (BlockOp op : s.plan().ops()) {
                assertTrue(BOX.contains(op.x(), op.y(), op.z()), "block " + op + " is inside the box");
                assertTrue(seen.add(key(op.x(), op.y(), op.z())), "one op per block: " + op);
            }
            assertEquals(BOX, s.box(), "the plan's half is the box");
            assertEquals(List.of(), s.plan().signs(), "no signs: nothing in the room needs reading");
        }
    }

    /**
     * Harmless lava needs a room that can't burn: every block of every layout (a bouncy cushion's slime too) is on
     * the planner's fireproof list, none is wood, wool, carpet, leaves, a bookshelf or anything else lava sets alight,
     * and each is a block this jar's API knows.
     */
    @Test
    void nothingInTheRoomCanBurnAndEveryBlockIsABlockOfThisApi() {
        Set<String> used = new TreeSet<>();
        List<Plan> plans = new ArrayList<>();
        for (LavaSite s : ROOMS) {
            plans.add(s.plan());
        }
        int cushion = SITE.layout().indexOf(SITE.layout().stream().filter(p -> p.kind() == LavaLayout.Kind.CUSHION)
                .findFirst().orElseThrow());
        plans.add(LavaPlanner.plan(BOX, LavaLayout.bouncy(SITE.layout(), cushion)).plan());
        for (Plan plan : plans) {
            for (String state : plan.palette()) {
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
                    assertFalse((name + "[").contains(thin) && !name.equals("sea_lantern"), "everything but a slab is"
                            + " a whole block: " + state);
                }
                assertFalse(name.contains("lava") || name.contains("water") || name.contains("magma")
                        || name.contains("fire"), "no fluids, magma or fire in the plan itself: " + state);
            }
        }
        assertEquals(LavaPlanner.FIREPROOF, used, "the list is exactly what the rooms are built of");
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
            for (int n = 0; n < 48; n++) {
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
     * more than the game gives), and every place a mob could stand has at least {@value #LIT}, in the room as built
     * and wherever the furniture moves: the floor, under the table, the shelves, every step, every top, the gallery.
     */
    @Test
    void everyPlaceAMobCouldStandIsLitWhereverTheFurnitureIs() {
        for (LavaSite s : ROOMS) {
            Map<Long, String> b = blocks(s.plan());
            Map<Long, Integer> light = new HashMap<>();
            ArrayDeque<int[]> q = new ArrayDeque<>();
            for (BlockOp op : s.plan().ops()) {
                int l = ClubhousePlanner.lightOf(s.plan().blockOf(op));
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
                        if (l < LIT) {
                            dark.add(where(x, y, z) + "=" + l);
                        }
                    }
                }
            }
            assertEquals(List.of(), dark, "every place lit in " + s.layout());
            assertTrue(places > 2000, "the whole floor, every step and every top is checked: " + places);
        }
        assertEquals(15, ClubhousePlanner.lightOf(LavaPlanner.LAMP));
        assertEquals(15, ClubhousePlanner.lightOf(LavaPlanner.BUTTON));
        assertFalse(ClubhousePlanner.letsLight(LavaPlanner.PAGE), "a slab stops the flood");
        assertTrue(ClubhousePlanner.noFooting(LavaPlanner.ROOF_GLASS) && ClubhousePlanner.noFooting(LavaPlanner.RAIL));
    }

    // ---- the layouts ----------------------------------------------------------------------------------

    /** The owner: "You don't even need to change the furniture per se, just move it around". */
    @Test
    void aLayoutIsTheSameFurnitureSomewhereElseTheSameForTheSameSeed() {
        Set<List<LavaLayout.Placed>> seen = new HashSet<>();
        Set<Integer> turns = new HashSet<>();
        for (long seed = 1; seed <= 40; seed++) {
            List<LavaLayout.Placed> l = LavaPlanner.layout(BOX, seed);
            assertEquals(l, LavaPlanner.layout(BOX, seed), "the same seed, the same layout");
            assertEquals(l, LavaPlanner.layout(BOX.translate(160, 0, -320), seed), "in any box of the room's size");
            assertEquals(LavaLayout.CATALOGUE.size(), l.size());
            for (int i = 0; i < l.size(); i++) {
                LavaLayout.Placed p = l.get(i);
                assertNotNull(p, "every piece is there");
                assertEquals(LavaLayout.CATALOGUE.get(i), p.kind(), "in its own slot");
                turns.add(p.turns());
                assertTrue(p.x() >= LavaLayout.EDGE && p.z() >= LavaLayout.EDGE
                        && p.x() + p.sizeX() <= LavaPlanner.SIZE_X - LavaLayout.EDGE
                        && p.z() + p.sizeZ() <= LavaPlanner.SIZE_Z - LavaLayout.EDGE, "on the open floor: " + p);
                int[] a = LavaPlanner.BOOKCASE_AREA;
                assertFalse(p.x() <= a[2] && p.x() + p.sizeX() - 1 >= a[0] && p.z() <= a[3]
                        && p.z() + p.sizeZ() - 1 >= a[1], "clear of the bookcase: " + p);
                for (int j = 0; j < i; j++) {
                    assertFalse(l.get(j).near(p.x(), p.z(), p.sizeX(), p.sizeZ(), LavaLayout.APART),
                            "a block of floor between every two pieces: " + p + " and " + l.get(j));
                }
            }
            seen.add(l);
        }
        assertEquals(40, seen.size(), "forty seeds, forty rooms");
        assertEquals(Set.of(0, 1, 2, 3), turns, "turned every way");
        assertEquals(SITE.layout(), LavaPlanner.layout(BOX, LavaPlanner.HOME_SEED), "the room as built is a layout");
    }

    /**
     * Every layout is playable: the bookcase's second shelf is the highest ground, there is a seat on the sofa for
     * everyone, every piece with steps is a walk of half steps from the floor (Lilah never needs a jump), every stool
     * a jump, and most pieces are a hop from another.
     */
    @Test
    void everyLayoutIsPlayable() {
        int hopsBetween = 0;
        for (long seed = 1; seed <= 60; seed++) {
            LavaSite s = LavaPlanner.site(BOX, seed * 104_729L);
            assertTrue(LavaPlanner.playable(s), "seed " + seed);
            assertEquals(LavaPlanner.FLOOD, s.flood(), "the same highest ground in every layout");
            assertEquals(LavaPlanner.SEAT.length, s.spawns().size());
            Set<LavaSite.Stand> reach = new HashSet<>(s.reach());
            Set<LavaSite.Stand> hop = new HashSet<>(s.hopReach());
            assertTrue(hop.containsAll(reach), "a walk is a hop");
            for (LavaSite.Spot sp : s.spawns()) {
                LavaSite.Stand at = new LavaSite.Stand((int) Math.floor(sp.x()), (int) Math.floor(sp.z()), sp.y());
                assertTrue(reach.contains(at), "an arrival spot is a stand of the play area: " + sp);
                assertFalse(s.collides(sp.x(), sp.y(), sp.z()), "nobody arrives inside a block: " + sp);
                assertEquals(0, s.pieceOf(at), "on the sofa");
            }
            for (int i = 0; i < s.layout().size(); i++) {
                LavaLayout.Placed p = s.layout().get(i);
                boolean walked = false;
                boolean hopped = false;
                for (LavaSite.Stand st : s.hopReach()) {
                    if (s.pieceOf(st) == i) {
                        walked |= reach.contains(st);
                        hopped = true;
                    }
                }
                assertTrue(p.kind().stepped ? walked : hopped, "seed " + seed + ": " + p + " can be got onto");
            }
            // hops from piece to piece while the floor is lava
            Map<Integer, Set<Integer>> links = new HashMap<>();
            for (LavaSite.Stand a : s.hopReach()) {
                int pa = s.pieceOf(a);
                if (pa < 0 || !s.dry(a, 1)) {
                    continue;
                }
                for (LavaSite.Stand b : s.hopsFrom(a)) {
                    int pb = s.pieceOf(b);
                    if (pb >= 0 && pb != pa && s.dry(b, 1)) {
                        links.computeIfAbsent(pa, k -> new HashSet<>()).add(pb);
                    }
                }
            }
            hopsBetween += links.size();
            assertTrue(links.size() >= 1, "seed " + seed + ": pieces a hop from another over the lava: " + links);
        }
        assertTrue(hopsBetween >= 60 * 2, "hops from piece to piece: " + hopsBetween);
    }

    @Test
    void aPieceTurnsAndMovesCellForCell() {
        for (int t = 0; t < 4; t++) {
            LavaLayout.Placed p = new LavaLayout.Placed(LavaLayout.Kind.SOFA, 10, 12, t, 0, false);
            Set<String> cells = new HashSet<>();
            for (int i = 0; i < LavaLayout.Kind.SOFA.w; i++) {
                for (int k = 0; k < LavaLayout.Kind.SOFA.d; k++) {
                    int[] o = p.at(i, k);
                    assertTrue(o[0] >= p.x() && o[0] < p.x() + p.sizeX() && o[1] >= p.z() && o[1] < p.z() + p.sizeZ(),
                            "on its footprint");
                    assertTrue(cells.add(o[0] + "," + o[1]), "one cell each");
                    int[] back = p.local(o[0], o[1]);
                    assertEquals(i, back[0]);
                    assertEquals(k, back[1]);
                }
            }
            assertNull(p.local(p.x() - 1, p.z()), "off its footprint");
        }
        LavaLayout.Placed south = new LavaLayout.Placed(LavaLayout.Kind.SOFA, 10, 12, 0, 0, false);
        assertEquals(0f, south.frontYaw());
        assertEquals(90f, south.movedTo(10, 12, 1).frontYaw(), "a quarter turn: facing west");
        List<LavaLayout.Placed> l = SITE.layout();
        assertEquals(List.of(3), LavaLayout.moved(l, LavaLayout.without(l, 3)));
        assertNull(LavaLayout.without(l, 3).get(3));
        assertTrue(LavaLayout.bouncy(l, 5).get(5).bouncy());
        assertEquals(List.of(), LavaLayout.moved(l, l));
        assertNotEquals(LavaLayout.mix(1, 1), LavaLayout.mix(1, 2));
    }

    // ---- standing, climbing, staying dry ------------------------------------------------------------------

    @Test
    void everyStandHasItsBlockUnderItAndTwoBlocksOfAirOverIt() {
        for (LavaSite s : ROOMS) {
            Map<Long, String> b = blocks(s.plan());
            assertTrue(s.stands().size() > 2000, "stands: " + s.stands().size());
            for (LavaSite.Stand st : s.stands()) {
                boolean half = st.y() % 1 != 0;
                int under = (int) Math.floor(st.y()) - (half ? 0 : 1);
                String block = b.get(key(st.x(), under, st.z()));
                assertNotNull(block, "something to stand on at " + st);
                assertEquals(half, block.contains("_slab"), "a half height is a slab's top: " + st + " on " + block);
                assertNull(b.get(key(st.x(), under + 1, st.z())), "air at the feet or knees: " + st);
                assertNull(b.get(key(st.x(), under + 2, st.z())), "air at the head: " + st);
                assertTrue(s.inside().contains(st.x(), (int) Math.floor(st.y()), st.z()), "in the room: " + st);
            }
        }
    }

    /**
     * The low route, for a four-year-old with a controller: from anywhere on the floor she can walk (never a step of
     * more than half a block, never a jump) onto dry ground, and on up to the highest ground there is, in every room.
     */
    @Test
    void aWalkFromTheFloorToDryGroundAndToTheTopNeverStepsUpMoreThanHalfABlock() {
        for (LavaSite s : ROOMS) {
            double top = Y0 + 1;
            Set<Double> heights = new TreeSet<>();
            for (LavaSite.Stand st : s.reach()) {
                top = Math.max(top, st.y());
                heights.add(st.y() - Y0);
            }
            assertEquals(Y0 + LavaPlanner.FLOOD, top, "the walk reaches the highest ground: the bookcase's second shelf");
            for (double h = 1.0; h <= LavaPlanner.FLOOD; h += 0.5) {
                assertTrue(heights.contains(h), "a step at every half block from the floor to the top: " + h);
            }
            // from every star's place on the floor, dry ground (feet a block up) is a short walk away
            for (LavaSite.Stand st : s.starStands()) {
                LavaSite.Spot safe = s.nearestSafe(1, st.x() + 0.5, st.y(), st.z() + 0.5);
                assertNotNull(safe);
                assertTrue(Math.hypot(safe.x() - st.x() - 0.5, safe.z() - st.z() - 0.5) <= LavaPlanner.FAR,
                        "dry ground is never far: " + st + " to " + safe);
            }
            // the steps are wide: a step of half a block always has another of its height beside it
            Map<Long, List<LavaSite.Stand>> columns = new HashMap<>();
            for (LavaSite.Stand st : s.reach()) {
                columns.computeIfAbsent(key(st.x(), 0, st.z()), k -> new ArrayList<>()).add(st);
            }
            for (LavaSite.Stand st : s.reach()) {
                if (st.y() % 1 == 0 || st.y() - Y0 > LavaPlanner.FLOOD) {
                    continue;
                }
                boolean pair = false;
                for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    for (LavaSite.Stand n : columns.getOrDefault(key(st.x() + d[0], 0, st.z() + d[1]), List.of())) {
                        pair |= n.y() == st.y();
                    }
                }
                assertTrue(pair, "a half step is at least two blocks wide: " + where(st.x(), (int) Math.floor(st.y()),
                        st.z()));
            }
        }
    }

    @Test
    void thePlayAreaIsEverythingButTheGalleryTheBookcasesTopAndTheStoolsWhichAreAJump() {
        for (LavaSite s : ROOMS) {
            Set<LavaSite.Stand> reach = new HashSet<>(s.reach());
            Set<LavaSite.Stand> hop = new HashSet<>(s.hopReach());
            int gallery = 0;
            for (LavaSite.Stand st : s.stands()) {
                if (reach.contains(st)) {
                    assertFalse(s.inGallery(st.x() + 0.5, st.y(), st.z() + 0.5), "the gallery is no part of the play area");
                    assertTrue(st.y() <= Y0 + LavaPlanner.FLOOD, "nothing a player can reach is over the flood: " + st);
                    continue;
                }
                int piece = s.pieceOf(st);
                if (piece >= 0) {
                    assertEquals(LavaLayout.Kind.STOOL, s.layout().get(piece).kind(), "only a stool needs a jump: " + st);
                    assertTrue(hop.contains(st), "and a jump gets there: " + st);
                    continue;
                }
                boolean inGallery = s.inGallery(st.x() + 0.5, st.y(), st.z() + 0.5);
                gallery += inGallery ? 1 : 0;
                assertTrue(inGallery || st.y() >= Y0 + LavaPlanner.CASE_TOP + 1, "the only places nobody can get to"
                        + " are the gallery and the bookcase's top: " + where(st.x(), (int) Math.floor(st.y()), st.z()));
                assertFalse(hop.contains(st), "not even with a jump: " + st);
            }
            assertEquals(3 * 18, gallery, "the gallery's walk: three wide, eighteen long");
            assertEquals(LavaPlanner.FLOOD, s.flood(), "the flood level is the play area's highest ground");
        }
    }

    @Test
    void dryStandableGroundForAFullRoomAtEveryLavaLevelAndNoneAtTheFlood() {
        for (LavaSite s : ROOMS) {
            Map<Long, String> b = blocks(s.plan());
            int before = Integer.MAX_VALUE;
            for (int level = 0; level <= LavaPlanner.SAFE_TOP; level++) {
                List<LavaSite.Stand> safe = s.safe(level);
                assertTrue(safe.size() >= LavaSettings.MAX_PLAYERS, "room for the most players config allows ("
                        + LavaSettings.MAX_PLAYERS + ") at level " + level + ": " + safe.size());
                assertTrue(level == 0 || safe.size() < before, "each level takes some ground: level " + level);
                before = safe.size();
                for (LavaSite.Stand st : safe) {
                    assertTrue(st.y() >= Y0 + 1 + level, "dry: feet a whole block over the lava's top layer: " + st);
                    assertFalse(s.touches(level, st.x() + 0.5, st.y(), st.z() + 0.5), "standing there is not in the"
                            + " lava: " + st + " at level " + level);
                    assertFalse(s.touches(level, st.x() + 0.31, st.y(), st.z() + 0.69), "nor at its edge: " + st);
                    int under = (int) Math.floor(st.y()) - (st.y() % 1 != 0 ? 0 : 1);
                    assertNotNull(b.get(key(st.x(), under, st.z())), "standable: " + st);
                }
            }
            assertEquals(List.of(), s.safe(LavaPlanner.FLOOD), "at the flood nothing is dry: Challenge always ends");
            assertNull(s.nearestSafe(LavaPlanner.FLOOD, BOX.minX() + 24, Y0 + 1, BOX.minZ() + 24));
            Set<Double> heights = new TreeSet<>();
            for (LavaSite.Stand st : s.safe(1)) {
                if (st.y() % 1 == 0) {
                    heights.add(st.y() - Y0);
                }
            }
            assertEquals(new TreeSet<>(List.of(2.0, 3.0, 4.0, 5.0, 6.0, 7.0)), heights, "six whole heights of dry"
                    + " ground, in every layout");
        }
    }

    @Test
    void theNearestSafeSpotIsARealDryStandFacingIntoTheRoom() {
        for (int level = 0; level <= LavaPlanner.SAFE_TOP; level++) {
            Set<String> safe = new HashSet<>();
            for (LavaSite.Stand s : SITE.safe(level)) {
                safe.add((s.x() + 0.5) + "," + s.y() + "," + (s.z() + 0.5));
            }
            for (double[] from : new double[][]{{24, 1, 24}, {2.5, 1, 2.5}, {45.5, 1, 45.5}, {15.5, 1, 12.5},
                    {40.3, 1, 3.7}, {7.2, 1, 40.9}, {20, 4, 30.5}}) {
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
                    double d = Math.hypot(s.x() + 0.5 - x, s.z() + 0.5 - z);
                    assertTrue(d + 4 >= best, "the nearest, give or take its height: " + spot + " against " + s);
                }
            }
        }
        assertEquals(0f, SITE.facing(BOX.minX() + 24, BOX.minZ() + 2), 0.01f);
        assertEquals(-90f, SITE.facing(BOX.minX() + 2, BOX.minZ() + 24), 0.01f);
        assertEquals(180f, Math.abs(SITE.facing(BOX.minX() + 24, BOX.minZ() + 46)), 0.01f);
    }

    @Test
    void whereTheLavaGoesAndWhoIsInIt() {
        for (LavaSite s : ROOMS) {
            Map<Long, String> b = blocks(s.plan());
            Set<Long> all = new HashSet<>();
            for (int level = 1; level <= LavaPlanner.FLOOD; level++) {
                List<int[]> cells = s.layer(level);
                int open = 0;
                for (int x = BOX.minX() + 1; x < BOX.maxX(); x++) {
                    for (int z = BOX.minZ() + 1; z < BOX.maxZ(); z++) {
                        open += b.get(key(x, Y0 + level, z)) == null ? 1 : 0;
                    }
                }
                assertEquals(open, cells.size(), "every air cell of the layer, so the lava has nowhere to flow");
                for (int[] c : cells) {
                    assertEquals(Y0 + level, c[1]);
                    assertNull(b.get(key(c[0], c[1], c[2])), "lava only where the plan is air");
                    assertFalse(s.galleryBox().contains(c[0], c[1], c[2]), "never in the gallery");
                    assertTrue(all.add(key(c[0], c[1], c[2])));
                }
            }
            assertEquals(List.of(), s.layer(LavaPlanner.FLOOD + 1), "the lava never goes higher than the flood");
        }
        assertTrue(Y0 + LavaPlanner.FLOOD < SITE.galleryBox().minY() - 1, "the gallery is well over the highest lava");

        LavaSite.Stand open = SITE.starStands().get(SITE.starStands().size() / 2);
        double cx = open.x() + 0.5;
        double cz = open.z() + 0.5;
        assertFalse(SITE.touches(0, cx, Y0 + 1, cz), "no lava, no touch");
        assertTrue(SITE.touches(1, cx, Y0 + 1, cz), "on the floor when the lava comes");
        assertTrue(SITE.touches(1, cx, Y0 + 1.5, cz), "a little hop doesn't clear it");
        assertFalse(SITE.touches(1, cx, Y0 + 1.95, cz), "over its surface is dry");
        assertTrue(SITE.touches(3, cx, Y0 + 2, cz), "but not when it is three deep");
        // the sofa's footstool (feet 2): dry in the middle at level 1, wet at level 2
        LavaLayout.Placed sofa = SITE.layout().get(0);
        int[] stool = sofa.at(6, 7);
        double fx = BOX.minX() + stool[0] + 0.5;
        double fz = BOX.minZ() + stool[1] + 0.5;
        assertFalse(SITE.touches(1, fx, Y0 + 2, fz));
        assertTrue(SITE.touches(2, fx, Y0 + 2, fz), "the low furniture goes under as the lava rises");
        // a floor cushion (a slab, feet 1.5): an island in its middle
        LavaLayout.Placed cushion = SITE.layout().get(5);
        int[] mid = cushion.at(1, 1);
        assertFalse(SITE.touches(1, BOX.minX() + mid[0] + 0.5, Y0 + 1.5, BOX.minZ() + mid[1] + 0.5),
                "the middle of a big cushion is an island");
        // the lava's own say (one side first): only its cells count
        assertFalse(SITE.touches(cx, Y0 + 1, cz, (x, y, z) -> false), "no lava there yet");
        assertTrue(SITE.touches(cx, Y0 + 1, cz, (x, y, z) -> SITE.air(x, y, z) && y == Y0 + 1));
        LavaSite.Spot g = SITE.gallery(0);
        assertFalse(SITE.touches(LavaPlanner.FLOOD, g.x(), g.y(), g.z()));
        assertFalse(SITE.touches(3, BOX.minX() + 0.5, Y0 + 1, BOX.minZ() + 0.5), "no lava outside the room's air");
    }

    @Test
    void theFloorThatGlowsIsTheBareFloorAndNeverALamp() {
        for (LavaSite s : ROOMS) {
            Map<Long, String> b = blocks(s.plan());
            List<int[]> glow = s.glowCells();
            Set<Long> seen = new HashSet<>();
            int lamps = 0;
            for (int x = BOX.minX() + 1; x < BOX.maxX(); x++) {
                for (int z = BOX.minZ() + 1; z < BOX.maxZ(); z++) {
                    lamps += LavaPlanner.LAMP.equals(b.get(key(x, Y0, z))) && b.get(key(x, Y0 + 1, z)) == null ? 1 : 0;
                }
            }
            for (int[] c : glow) {
                assertEquals(Y0, c[1], "the floor's own layer");
                String floor = b.get(key(c[0], c[1], c[2]));
                assertNotNull(floor);
                assertNotEquals(LavaPlanner.LAMP, floor, "a lamp stays a lamp: the room is never dark");
                assertNull(b.get(key(c[0], c[1] + 1, c[2])), "bare floor only: nothing stands on it");
                assertTrue(seen.add(key(c[0], c[1], c[2])));
            }
            assertEquals(s.layer(1).size() - lamps, glow.size(), "every bare floor block but the lamps");
        }
    }

    // ---- arriving, watching, stars ------------------------------------------------------------------------

    @Test
    void playersArriveOnTheSofaOnDryGround() {
        for (LavaSite s : ROOMS) {
            Set<LavaSite.Stand> reach = new HashSet<>(s.reach());
            assertTrue(s.spawns().size() >= LavaSettings.defaults().maxPlayers(), "a spot each for a shipped room");
            Set<String> seen = new HashSet<>();
            for (LavaSite.Spot sp : s.spawns()) {
                LavaSite.Stand at = new LavaSite.Stand((int) Math.floor(sp.x()), (int) Math.floor(sp.z()), sp.y());
                assertTrue(reach.contains(at), "an arrival spot is a stand of the play area: " + sp);
                assertEquals(Y0 + LavaPlanner.SEAT_FEET, sp.y(), "on the sofa's seat");
                assertTrue(s.dry(at, 1) && s.dry(at, 2), "safe when the lava is one or two deep");
                assertEquals(s.layout().get(0).frontYaw(), sp.yaw(), "looking out of the sofa");
                assertTrue(seen.add(sp.x() + "," + sp.z()), "no two on one block");
            }
            assertEquals(s.spawn(0), s.spawn(s.spawns().size()), "round and round");
            assertEquals(s.spawn(3), s.entry(0, 3), "a dry sofa: the arrival spot");
            assertEquals(s.spawn(3), s.entry(2, 3));
            LavaSite.Spot high = s.entry(4, 3);
            assertTrue(high.y() >= Y0 + 5, "with the lava over the seat, a joiner arrives on dry ground: " + high);
            assertEquals(s.gallery(3), s.entry(LavaPlanner.FLOOD, 3), "and at the flood, in the gallery");
        }
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
        assertTrue(SITE.starStands().size() >= 400, "plenty of open floor: " + SITE.starStands().size());
        for (LavaSite.Stand s : SITE.starStands()) {
            assertEquals(Y0 + 1, s.y(), "on the floor");
            for (int y = Y0 + 1; y <= Y0 + 4; y++) {
                assertNull(b.get(key(s.x(), y, s.z())), "open over it: never under the table or a shelf");
            }
            int i = s.x() - BOX.minX();
            int k = s.z() - BOX.minZ();
            assertTrue(i >= 3 && i <= 44 && k >= 3 && k <= 44, "away from the walls");
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
        // Little's: the low furniture, a walk from the floor
        List<LavaSite.Stand> low = SITE.lowStands(3.5);
        assertTrue(low.size() > 30, "low tops and steps: " + low.size());
        Set<LavaSite.Stand> reach = new HashSet<>(SITE.reach());
        for (LavaSite.Stand s : low) {
            assertTrue(reach.contains(s), "a walk: " + s);
            assertTrue(s.y() - Y0 >= 1.5 && s.y() - Y0 <= 3.5);
            assertTrue(SITE.pieceOf(s) >= 0, "furniture, not the bookcase");
        }
    }

    // ---- hops and the moving furniture ---------------------------------------------------------------------

    @Test
    void aHopIsAShortJumpWithNothingInTheWay() {
        LavaSite.Stand floorA = SITE.starStands().get(0);
        for (LavaSite.Stand b : SITE.hopsFrom(floorA)) {
            assertTrue(Math.hypot(b.x() - floorA.x(), b.z() - floorA.z()) <= LavaSite.HOP_REACH + 1e-9, "near: " + b);
            assertTrue(b.y() <= floorA.y() + LavaSite.JUMP_UP + 1e-9, "a jump up of a block at most: " + b);
        }
        // from the floor nobody jumps onto the sofa's back (feet 6) or the chest (5)
        for (LavaSite.Stand st : SITE.hopReach()) {
            if (st.y() == Y0 + 1) {
                for (LavaSite.Stand b : SITE.hopsFrom(st)) {
                    assertTrue(b.y() <= Y0 + 2 + 1e-9, "from the floor at most a block up: " + b);
                }
            }
        }
        // what a player on dry furniture can get to without touching the lava is all dry
        LavaSite.Stand seat = SITE.standUnder(SITE.spawn(0).x(), SITE.spawn(0).y(), SITE.spawn(0).z());
        Set<LavaSite.Stand> dry = SITE.hopComponent(2, List.of(seat));
        assertTrue(dry.size() > 20, "the sofa and what is a hop from it: " + dry.size());
        for (LavaSite.Stand s : dry) {
            assertTrue(SITE.dry(s, 2));
        }
        assertEquals(Set.of(), SITE.hopComponent(5, List.of(seat)), "a wet start gets nowhere");
    }

    /**
     * When the furniture moves: whoever is on a piece rides along to the same spot of it; whoever a piece lands on is
     * lifted onto it; whoever stood on a piece that sank goes down; everyone else stays put.
     */
    @Test
    void whoStandsWhereGoesWhereTheFurnitureTakesThem() {
        for (long seed = 1; seed <= 15; seed++) {
            LavaSite from = LavaPlanner.site(BOX, seed);
            LavaSite to = LavaPlanner.site(BOX, seed + 1000);
            int rode = 0;
            int lifted = 0;
            for (LavaSite.Stand st : from.hopReach()) {
                double x = st.x() + 0.5;
                double z = st.z() + 0.5;
                LavaSite.Spot spot = LavaShift.carry(from, to, x, st.y(), z, 0);
                int piece = from.pieceOf(st);
                if (piece >= 0) {
                    assertNotNull(spot, "a rider moves with its piece: " + st);
                    LavaSite.Stand now = to.standUnder(spot.x(), spot.y(), spot.z());
                    assertNotNull(now, "onto a place to stand");
                    assertEquals(piece, to.pieceOf(now), "the same piece");
                    if (from.layout().get(piece).kind() != LavaLayout.Kind.STOOL) {
                        assertEquals(st.y(), now.y(), 1e-9, "the same height on it (a stool's may change)");
                    }
                    rode++;
                } else if (spot != null) {
                    assertTrue(to.collides(x, st.y(), z), "only someone a piece landed on is moved: " + st);
                    lifted++;
                } else {
                    assertFalse(to.collides(x, st.y(), z), "the rest stay: " + st);
                }
                if (spot != null) {
                    assertFalse(to.collides(spot.x(), spot.y(), spot.z()), "never left inside a block: " + spot);
                }
            }
            assertTrue(rode > 50 && lifted > 10, "rode " + rode + ", lifted " + lifted);
        }
        // a piece that sinks: down to the floor under it
        LavaSite home = SITE;
        LavaSite sunk = LavaPlanner.plan(BOX, LavaLayout.without(home.layout(), 2));
        LavaLayout.Placed chest = home.layout().get(2);
        int[] c = chest.at(5, 3);
        List<LavaSite.Stand> col = home.column(BOX.minX() + c[0], BOX.minZ() + c[1]);
        LavaSite.Stand top = col.get(col.size() - 1);
        assertEquals(Y0 + 5, top.y(), 1e-9, "the chest's lid");
        LavaSite.Spot down = LavaShift.carry(home, sunk, top.x() + 0.5, top.y(), top.z() + 0.5, 1);
        assertNotNull(down);
        assertEquals(Y0 + 1, down.y(), 1e-9, "down on the floor (the lava): the wobble was the warning");
    }
}
