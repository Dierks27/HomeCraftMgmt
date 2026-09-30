package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.LegacyBoxes;
import com.dierks.homecraft.games.gen.api.Plan;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The generated Clubhouse (CLUBHOUSE-SPEC §1, §8): pinned by its hash, every block inside its box, a
 * podium of three distinct heights with 1st in the middle, sixteen arrival spots with a floor and two
 * air above each, nothing a mob could spawn on left open to the air, and no way out.
 */
class ClubhousePlannerTest {

    /** The 0.35 box: the golden hash names blocks where they stand, so it never follows the shipped origin. */
    private static final Box BOX = LegacyBoxes.clubhouse();
    private static final ClubhouseSite SITE = ClubhousePlanner.plan(BOX);

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

    @Test
    void theGoldenHashPinsTheRoom() {
        assertEquals("f0f30283fb3d", SITE.plan().hash(), "the Clubhouse's layout is pinned: a change is a decision"
                + " (and then ClubhousePlanner.ALGO goes up, so every server rebuilds it once)");
        assertEquals(SITE.plan().hash(), ClubhousePlanner.plan(BOX).plan().hash(), "the same box always gives the same room");
        Box moved = BOX.translate(160, 0, -64);
        assertEquals(SITE.plan().ops().size(), ClubhousePlanner.plan(moved).plan().ops().size(),
                "moving the box moves the same room");
        assertNotEquals(SITE.plan().hash(), ClubhousePlanner.plan(moved).plan().hash(), "a hash names the blocks where"
                + " they are");
    }

    @Test
    void everyBlockAndSpotIsInsideTheBox() {
        for (BlockOp op : SITE.plan().ops()) {
            assertTrue(BOX.contains(op.x(), op.y(), op.z()), "block " + op + " is inside the box");
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

    @Test
    void thePodiumHasThreeHeightsFirstInTheMiddleAndHighest() {
        Map<Long, String> b = blocks(SITE.plan());
        List<ClubhouseSite.Spot> p = SITE.podium();
        assertEquals(3, p.size(), "1st, 2nd and 3rd");
        assertTrue(p.get(0).y() > p.get(1).y() && p.get(1).y() > p.get(2).y(), "three distinct heights, 1st highest: "
                + p);
        assertTrue(Math.min(p.get(1).x(), p.get(2).x()) < p.get(0).x() && p.get(0).x() < Math.max(p.get(1).x(),
                p.get(2).x()), "1st stands in the middle");
        for (ClubhouseSite.Spot s : p) {
            assertTrue(at(b, s.x(), s.y() - 1, s.z()) != null, "each place stands on its pedestal: " + s);
            assertNull(at(b, s.x(), s.y(), s.z()), "air at the feet");
            assertNull(at(b, s.x(), s.y() + 1, s.z()), "and at the head");
            assertEquals(0f, s.yaw(), "the podium faces the room (south)");
        }
    }

    @Test
    void sixteenArrivalSpotsEachWithAFloorAndTwoAirAboveAndNoneStacked() {
        Map<Long, String> b = blocks(SITE.plan());
        assertEquals(16, SITE.spawns().size(), "sixteen players arrive without stacking");
        Set<String> seen = new HashSet<>();
        for (ClubhouseSite.Spot s : SITE.spawns()) {
            assertTrue(seen.add((int) Math.floor(s.x()) + "," + (int) Math.floor(s.z())), "no two spots share a block");
            assertTrue(ClubhousePlanner.spawnProof(at(b, s.x(), s.y() - 1, s.z())), "a floor under " + s);
            assertNull(at(b, s.x(), s.y(), s.z()), "air at the feet of " + s);
            assertNull(at(b, s.x(), s.y() + 1, s.z()), "air at the head of " + s);
            for (ClubhouseSite.Spot o : SITE.spawns()) {
                if (o != s) {
                    assertTrue(Math.hypot(o.x() - s.x(), o.z() - s.z()) >= 3, "spots are at least 3 apart: " + s + " "
                            + o);
                }
            }
        }
    }

    @Test
    void nothingAMobCouldSpawnOnIsEverOpenToTheAir() {
        Map<Long, String> b = blocks(SITE.plan());
        int open = 0;
        for (BlockOp op : SITE.plan().ops()) {
            String above = b.get(key(op.x(), op.y() + 1, op.z()));
            if (above != null) {
                continue; // covered
            }
            open++;
            String block = SITE.plan().blockOf(op);
            assertTrue(ClubhousePlanner.spawnProof(block), "a top open to the air (inside or on the roof) is glass, a"
                    + " bench or a lantern, never " + block + " at " + op);
        }
        assertTrue(open > 900, "the whole floor and the roof are open tops, all spawn-proof: " + open);
        assertFalse(ClubhousePlanner.spawnProof("minecraft:white_concrete"), "a full block would be spawnable");
        assertFalse(ClubhousePlanner.spawnProof("minecraft:birch_stairs[facing=north,half=top,shape=straight,"
                + "waterlogged=false]"), "an upside-down stair would be");
        assertTrue(ClubhousePlanner.spawnProof(ClubhousePlanner.HANGING), "a lantern isn't");
    }

    @Test
    void theRoomIsLitAndHasNoWayOut() {
        Map<Long, String> b = blocks(SITE.plan());
        // the roof is whole
        for (int x = BOX.minX(); x <= BOX.maxX(); x++) {
            for (int z = BOX.minZ(); z <= BOX.maxZ(); z++) {
                assertEquals(ClubhousePlanner.ROOF_GLASS, b.get(key(x, BOX.minY() + ClubhousePlanner.ROOF, z)),
                        "the roof is closed over " + x + "," + z);
                assertTrue(b.get(key(x, BOX.minY(), z)) != null, "the floor is whole at " + x + "," + z);
            }
        }
        // the walls go all the way up to it
        for (int y = BOX.minY(); y <= BOX.minY() + ClubhousePlanner.WALL_TOP; y++) {
            for (int i = 0; i < ClubhouseSettings.SIZE_X; i++) {
                assertTrue(b.get(key(BOX.minX() + i, y, BOX.minZ())) != null, "north wall");
                assertTrue(b.get(key(BOX.minX() + i, y, BOX.maxZ())) != null, "south wall");
                assertTrue(b.get(key(BOX.minX(), y, BOX.minZ() + i)) != null, "west wall");
                assertTrue(b.get(key(BOX.maxX(), y, BOX.minZ() + i)) != null, "east wall");
            }
        }
        long lights = SITE.plan().ops().stream().filter(op -> {
            String s = SITE.plan().blockOf(op);
            return s.equals(ClubhousePlanner.LIGHT) || s.startsWith("minecraft:lantern");
        }).count();
        assertTrue(lights >= 20, "lit day and night: " + lights + " lights");
    }

    @Test
    void theBenchesAreStraightStairsThatFaceTheirTables() {
        Map<Long, String> b = blocks(SITE.plan());
        int benches = 0;
        for (BlockOp op : SITE.plan().ops()) {
            String s = SITE.plan().blockOf(op);
            if (!s.contains("_stairs")) {
                continue;
            }
            benches++;
            assertTrue(s.contains("half=bottom") && s.contains("shape=straight"), "a bench is a straight bottom stair: "
                    + s);
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
            assertTrue(front != null && front.endsWith("_stained_glass"), "a bench's seat faces a table: " + op + " "
                    + s);
        }
        assertEquals(16, benches, "two tables, four sides, two seats each");
    }
}
