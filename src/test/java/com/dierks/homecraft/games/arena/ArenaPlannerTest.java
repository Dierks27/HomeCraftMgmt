package com.dierks.homecraft.games.arena;

import com.dierks.homecraft.games.arena.rules.Cell;
import com.dierks.homecraft.games.arena.rules.FloorLayout;
import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The week's arena (EVENTS-DROPPER-SPEC §B.3.2): a thousand seeds and the independent validator
 * never fires; every floor is 450 give or take 10%, in one piece (or a ring and its island) with no
 * piece under 9; the spawns are on inner top-floor cells, 3 or more apart and spread all round; the
 * gallery is a full 4-wide ring with both rails, 4 or more from any floor; and the same week always
 * makes the same arena, pinned by its hash.
 */
class ArenaPlannerTest {

    static final Box BOX = Box.sized(5376, 176, 4352, 48, 40, 48);
    static final long WEEK = 20_724L; // Monday 2026-09-28

    static ArenaSite site(long seed) {
        return ArenaPlanner.plan(BOX, seed, WEEK);
    }

    @Test
    void aThousandSeedsNeverFireTheValidator() {
        Map<String, Integer> shapes = new TreeMap<>();
        for (long seed = 0; seed < 1000; seed++) {
            ArenaSite s = site(seed * 0x9E3779B97F4A7C15L + 17);
            List<String> problems = ArenaValidator.problems(s);
            assertEquals(List.of(), problems, "seed " + seed + " made a plan the validator refuses");
            assertEquals(3, new HashSet<>(s.shapes()).size(), "seed " + seed + ": the three floors are three shapes");
            for (String shape : s.shapes()) {
                shapes.merge(shape, 1, Integer::sum);
            }
        }
        assertEquals(5, shapes.size(), "every shape comes up: " + shapes);
        for (int n : shapes.values()) {
            assertTrue(n > 450, "each shape comes up often (about 600 of 3,000 floors): " + shapes);
        }
    }

    @Test
    void everyShapeInEverySizeIsNearFourHundredAndFiftyCells() {
        for (ArenaPlanner.Shape shape : ArenaPlanner.Shape.values()) {
            for (int n = 0; n < shape.sizes(); n++) {
                int cells = shape.cells(n, 0, 0).size();
                assertTrue(cells >= ArenaPlanner.MIN_CELLS && cells <= ArenaPlanner.MAX_CELLS,
                        shape + " size " + n + " has " + cells + " cells; a floor holds 405-495 (450 +- 10%)");
            }
        }
        for (long seed = 1; seed <= 200; seed++) {
            FloorLayout l = site(seed).layout();
            for (int i = 0; i < l.layerCount(); i++) {
                int n = l.cellCount(i);
                assertTrue(Math.abs(n - ArenaPlanner.TARGET_CELLS) <= ArenaPlanner.TARGET_CELLS / 10,
                        "seed " + seed + " floor " + i + " has " + n + " cells");
            }
        }
    }

    @Test
    void floorsAreConnectedPiecesWithNoIslandUnderNineCells() {
        for (long seed = 1; seed <= 300; seed++) {
            ArenaSite s = site(seed);
            for (int i = 0; i < 3; i++) {
                List<Integer> pieces = ArenaValidator.pieces(s.layout().layer(i).cells());
                boolean ring = s.shapes().get(i).equals("ring");
                assertEquals(ring ? 2 : 1, pieces.size(), "seed " + seed + " floor " + i + " (" + s.shapes().get(i)
                        + "): one piece, or a ring and its island: " + pieces);
                for (int p : pieces) {
                    assertTrue(p >= ArenaPlanner.MIN_PIECE, "no piece under 9 cells: " + pieces);
                }
            }
        }
    }

    @Test
    void floorsAreEightApartInTheFootprintWithOutThreeBelowTheBottom() {
        ArenaSite s = site(42);
        FloorLayout l = s.layout();
        assertEquals(List.of(200, 192, 184), List.of(l.layer(0).y(), l.layer(1).y(), l.layer(2).y()),
                "three floors 8 apart, top first");
        assertEquals(181, l.outY(), "out is below y 181");
        for (FloorLayout.Layer layer : l.layers()) {
            for (Cell c : layer.cells()) {
                assertTrue(c.x() >= 5384 && c.x() <= 5415 && c.z() >= 4360 && c.z() <= 4391,
                        "every floor cell is in the 32 x 32 footprint: " + c);
            }
        }
        assertEquals(List.of("minecraft:yellow_stained_glass", "minecraft:pink_stained_glass",
                "minecraft:light_blue_stained_glass"), s.floorBlocks(), "yellow on top, pink, light blue at the bottom");
        for (BlockOp op : s.plan().ops()) {
            boolean floor = op.y() == 200 || op.y() == 192 || op.y() == 184;
            boolean gallery = op.y() >= 206 && op.y() <= 208;
            assertTrue(floor || gallery, "nothing but the floors and the gallery (no walls): " + op);
        }
    }

    @Test
    void spawnsAreSixteenInnerTopCellsAtLeastThreeApartAndSpreadAllRound() {
        for (long seed = 1; seed <= 300; seed++) {
            ArenaSite s = site(seed);
            List<Cell> spawns = s.layout().spawns();
            assertEquals(ArenaPlanner.SPAWNS, spawns.size(), "seed " + seed + ": one spawn for each of up to 16 players");
            Set<Cell> top = new HashSet<>(s.layout().layer(0).cells());
            int[] quadrant = new int[4];
            for (Cell c : spawns) {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        assertTrue(top.contains(new Cell(c.x() + dx, c.z() + dz)),
                                "seed " + seed + ": a spawn is never on an edge: " + c);
                    }
                }
                quadrant[(c.x() >= 5400 ? 1 : 0) + (c.z() >= 4376 ? 2 : 0)]++;
            }
            for (int i = 0; i < spawns.size(); i++) {
                for (int j = i + 1; j < spawns.size(); j++) {
                    double dx = spawns.get(i).x() - spawns.get(j).x();
                    double dz = spawns.get(i).z() - spawns.get(j).z();
                    assertTrue(Math.sqrt(dx * dx + dz * dz) >= 3, "seed " + seed + ": spawns at least 3 apart");
                }
            }
            for (int q = 0; q < 4; q++) {
                assertTrue(quadrant[q] >= 2, "seed " + seed + " (" + s.shapes().get(0) + "): every quarter of the top"
                        + " floor has spawns, not one side only: " + java.util.Arrays.toString(quadrant));
            }
        }
    }

    @Test
    void aSpawnFacesTheMiddle() {
        assertEquals(0f, ArenaSite.yaw(0, 1), 1e-3, "looking south (+z) is yaw 0");
        assertEquals(90f, ArenaSite.yaw(-1, 0), 1e-3, "looking west (-x) is yaw 90");
        assertEquals(-90f, ArenaSite.yaw(1, 0), 1e-3, "looking east (+x) is yaw -90");
        ArenaSite s = site(7);
        for (int i = 0; i < s.layout().spawns().size(); i++) {
            ArenaSite.Spot spot = s.spawn(i);
            assertEquals(201.0, spot.y(), "players stand on the top floor's top");
            double dx = 5400 - spot.x();
            double dz = 4376 - spot.z();
            double len = Math.sqrt(dx * dx + dz * dz);
            double yaw = Math.toRadians(spot.yaw());
            assertEquals(dx / len, -Math.sin(yaw), 1e-6, "spawn " + i + " looks at the middle (x)");
            assertEquals(dz / len, Math.cos(yaw), 1e-6, "spawn " + i + " looks at the middle (z)");
        }
    }

    @Test
    void theGalleryIsAFullRingWithBothRailsAndFourFromEveryFloor() {
        ArenaSite s = site(3);
        Set<String> blocks = new HashSet<>();
        for (BlockOp op : s.plan().ops()) {
            blocks.add(op.x() + "," + op.y() + "," + op.z() + "=" + s.plan().blockOf(op));
        }
        int walk = 0;
        int rail = 0;
        for (int x = BOX.minX(); x <= BOX.maxX(); x++) {
            for (int z = BOX.minZ(); z <= BOX.maxZ(); z++) {
                int depth = ArenaPlanner.ringDepth(BOX, x, z);
                if (depth >= 4) {
                    assertFalse(blocks.contains(x + ",206," + z + "=" + ArenaPlanner.WALK), "the walk is 4 wide");
                    continue;
                }
                assertTrue(blocks.contains(x + ",206," + z + "=" + ArenaPlanner.WALK), "walk at " + x + "," + z);
                walk++;
                for (int h = 207; h <= 208; h++) {
                    boolean railHere = blocks.contains(x + "," + h + "," + z + "=minecraft:glass");
                    assertEquals(depth == 0 || depth == 3, railHere, "a 2-high rail on both sides, only there: " + x
                            + "," + h + "," + z);
                    rail += railHere ? 1 : 0;
                }
            }
        }
        assertEquals(48 * 48 - 40 * 40, walk, "the whole ring");
        assertEquals(2 * (48 * 4 - 4) + 2 * (42 * 4 - 4), rail, "both rails, all the way round");
        for (FloorLayout.Layer layer : s.layout().layers()) {
            for (Cell c : layer.cells()) {
                assertTrue(ArenaPlanner.ringDepth(BOX, c.x(), c.z()) >= 8, "4 blocks between the gallery and "
                        + "any floor cell: " + c);
            }
        }
        assertEquals(16, s.gallerySpots().size(), "sixteen places to stand");
        for (ArenaSite.Spot spot : s.gallerySpots()) {
            assertTrue(s.inGallery(spot.x(), spot.y(), spot.z()), "every gallery spot is in the gallery: " + spot);
            int depth = ArenaPlanner.ringDepth(BOX, (int) Math.floor(spot.x()), (int) Math.floor(spot.z()));
            assertTrue(depth == 1 || depth == 2, "between the rails: " + spot);
        }
        assertFalse(s.inGallery(5400.5, 201, 4376.5), "the top floor is not the gallery");
        assertFalse(s.inGallery(5378.5, 190, 4378.5), "under the gallery is not the gallery");
    }

    /**
     * F review #6: nothing in the arena is a block a mob can spawn on. The floors are stained glass,
     * and the gallery's walk and rails are clear glass (the walk was white concrete, where mobs could
     * spawn at night with Fresh Courses' world rules off).
     */
    @Test
    void noMobCanSpawnAnywhereInTheArena() {
        for (long seed : new long[]{1L, 42L, -7L}) {
            ArenaSite s = site(seed);
            for (BlockOp op : s.plan().ops()) {
                String block = s.plan().blockOf(op);
                assertTrue(block.equals("minecraft:glass") || block.endsWith("_stained_glass"),
                        "glass only (no mob spawns on glass): " + block + " at " + op);
            }
        }
        assertEquals("minecraft:glass", ArenaPlanner.WALK, "the gallery walk is clear glass");
    }

    /**
     * The gallery's rails are taller than any jump: feet on the walk are 2 blocks under the rail's
     * top, and a player jumps about 1.25 (the arena hands out nothing that jumps higher), so nobody
     * hops out of the gallery or down onto the floors.
     */
    @Test
    void theRailsAreTallerThanAnyJump() {
        double jump = 1.2522; // a plain jump's apex, in blocks
        assertTrue(ArenaPlanner.RAIL_HEIGHT > jump, "a " + ArenaPlanner.RAIL_HEIGHT + "-high rail can't be jumped");
        ArenaSite s = site(3);
        for (ArenaSite.Spot spot : s.gallerySpots()) {
            double railTop = s.galleryY() + 1 + ArenaPlanner.RAIL_HEIGHT;
            assertTrue(railTop - spot.y() > jump, "from " + spot + " the rail's top is out of reach");
        }
    }

    @Test
    void theSameWeekAlwaysMakesTheSameArenaAndTheHashesArePinned() {
        for (long seed : new long[]{1L, 42L, -7L}) {
            assertEquals(site(seed).plan().hash(), site(seed).plan().hash(), "the same seed, the same plan");
        }
        List<String> hashes = new ArrayList<>();
        List<String> shapes = new ArrayList<>();
        for (long seed : new long[]{1L, 42L, -7L}) {
            ArenaSite s = site(seed);
            hashes.add(s.plan().hash());
            shapes.add(String.join("/", s.shapes()));
        }
        assertEquals(List.of(GOLDEN_1, GOLDEN_42, GOLDEN_MINUS_7), hashes,
                "the golden hashes (a change here is a change of every week's arena: bump ArenaPlanner.ALGO): "
                        + shapes);
        assertEquals(ArenaPlanner.seed(99L, WEEK), ArenaPlanner.seed(99L, WEEK), "the week's seed is fixed");
        assertNotEquals(ArenaPlanner.seed(99L, WEEK), ArenaPlanner.seed(99L, WEEK + 7), "a new week, a new seed");
        assertNotEquals(ArenaPlanner.seed(99L, WEEK), ArenaPlanner.seed(100L, WEEK), "another server, another seed");
        Set<String> weeks = new HashSet<>();
        for (int w = 0; w < 20; w++) {
            weeks.add(ArenaPlanner.plan(BOX, ArenaPlanner.seed(99L, WEEK + 7L * w), WEEK + 7L * w).plan().hash());
        }
        assertEquals(20, weeks.size(), "twenty weeks, twenty different arenas");
    }

    // ALGO 2: the gallery walk is glass (F review #6)
    static final String GOLDEN_1 = "b7787b375c59";
    static final String GOLDEN_42 = "3dfec1439b0d";
    static final String GOLDEN_MINUS_7 = "907be954ed6a";

    @Test
    void theBoxMustBeTheArenasSizeAndTheShapesReadWell() {
        assertThrows(IllegalArgumentException.class, () -> ArenaPlanner.plan(Box.sized(0, 0, 0, 32, 40, 48), 1, WEEK),
                "a box of the wrong size is refused");
        assertEquals("Ring with an island, disc and plus", ArenaPlanner.shapesText(List.of("ring", "disc", "plus")),
                "the week's shapes as players read them");
        assertEquals("Rounded square", ArenaPlanner.shapesText(List.of("square")), "one shape");
        assertEquals("", ArenaPlanner.shapesText(List.of()), "none");
        ArenaSite s = site(5);
        assertEquals(s.shapes().get(0), s.shape(), "the website's shape is the top floor's");
        assertTrue(s.plan().ops().size() <= ArenaPlanner.MAX_OPS, "under 8,000 blocks");
    }
}
