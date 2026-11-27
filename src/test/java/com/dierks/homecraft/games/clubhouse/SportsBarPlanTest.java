package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.LegacyBoxes;
import com.dierks.homecraft.games.gen.api.Plan;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plan 16 (0.60.0), the sports corner's TVs and the video wall: where their blocks are, that they took only bare wall
 * and empty air (put those back and the room is plan 15's, hash for hash), that nothing of the room is in front of the
 * video wall or under a TV's foot, that the ticker hangs in clear air, and that a wing kept at its old size has none.
 */
class SportsBarPlanTest {

    private static final Box MAIN = LegacyBoxes.clubhouse();
    private static final Box BOX = Box.sized(MAIN.minX(), MAIN.minY(), MAIN.minZ(), 32, 16, ClubhouseSettings.SIZE_Z);
    private static final Box OLD = Box.sized(MAIN.minX(), MAIN.minY(), MAIN.minZ(), 32, 16,
            ClubhouseSettings.OLD_SIZE_Z);
    private static final ClubhouseSite SITE = ClubhousePlanner.plan(BOX);
    /** Plan 15's room, pinned before plan 16 (ClubhousePlannerTest's HASH until 0.60.0). */
    static final String PLAN_15 = "8785b0ad0218";

    private static final int F = ClubhousePlanner.UP_FLOOR;

    /** Every block of the plan by box offset "i,j,k". */
    private static Map<String, String> blocks(ClubhouseSite site) {
        Map<String, String> out = new HashMap<>();
        Plan p = site.plan();
        for (BlockOp op : p.ops()) {
            out.put(key(op.x() - BOX.minX(), op.y() - BOX.minY(), op.z() - BOX.minZ()), p.blockOf(op));
        }
        return out;
    }

    private static String key(int i, int j, int k) {
        return i + "," + j + "," + k;
    }

    private static String id(String state) {
        return state == null ? "minecraft:air" : ClubhousePlanner.id(state);
    }

    /** The TVs' and the video wall's blocks (box offsets), with what plan 15 had at each: the same column's wall, or air. */
    private static Map<String, int[]> tvBlocks() {
        Map<String, int[]> out = new HashMap<>(); // offset -> the offset of a block that is as plan 15 had it here
        for (int k = 59; k <= 62; k++) {
            for (int j = F + 1; j <= F + 4; j++) {
                out.put(key(15, j, k), new int[]{15, j, 46});
            }
        }
        for (int k : new int[]{52, 60}) {
            for (int i = 5; i <= 9; i++) {
                for (int j = F + 3; j <= F + 4; j++) {
                    out.put(key(i, j, k), new int[]{i, j, 47});
                }
            }
        }
        for (int k = 33; k <= 41; k++) {
            for (int j = F + 1; j <= F + 5; j++) {
                out.put(key(15, j, k), new int[]{15, j, 46});
            }
        }
        return out;
    }

    @Test
    void theTvsAndTheVideoWallAreWhereThePlanSays() {
        Map<String, String> b = blocks(SITE);
        String frame = ClubhousePlanner.id(ClubhousePlanner.TV_FRAME);
        String screen = ClubhousePlanner.id(ClubhousePlanner.TV_SCREEN);
        assertEquals("minecraft:polished_blackstone", frame);
        assertEquals("minecraft:black_concrete", screen);
        // the wall TV: a 2 x 2 screen in the east wall, z 60-61, y 11-12 over the floor, in a frame
        for (int k = 59; k <= 62; k++) {
            for (int j = F + 1; j <= F + 4; j++) {
                boolean face = k >= 60 && k <= 61 && j >= F + 2 && j <= F + 3;
                assertEquals(face ? screen : frame, id(b.get(key(15, j, k))), "wall TV at z " + k + " j " + j);
            }
        }
        // the hanging TVs: a 3 x 2 screen between two posts under each beam either end of the lounge
        for (int k : new int[]{52, 60}) {
            assertTrue(id(b.get(key(7, ClubhousePlanner.UP_TOP, k))).contains("log"), "hung from the beam at z " + k);
            for (int i = 5; i <= 9; i++) {
                for (int j = F + 3; j <= F + 4; j++) {
                    assertEquals(i == 5 || i == 9 ? frame : screen, id(b.get(key(i, j, k))));
                }
                for (int j = F + 1; j <= F + 2; j++) {
                    String under = id(b.get(key(i, j, k)));
                    assertFalse(under.contains("blackstone") || under.contains("concrete"),
                            "two clear blocks under a hanging TV: everyone walks under it");
                }
            }
            for (int i : new int[]{4, 11}) {
                assertTrue(id(b.get(key(i, ClubhousePlanner.UP_TOP - 1, k))).contains("lantern"),
                        "the beam's lanterns are where they were");
            }
        }
        // the video wall: a 7 x 4 screen in the east wall's north end, from the floor, in a frame
        for (int k = 33; k <= 41; k++) {
            for (int j = F + 1; j <= F + 5; j++) {
                boolean face = k >= 34 && k <= 40 && j <= F + 4;
                assertEquals(face ? screen : frame, id(b.get(key(15, j, k))), "video wall at z " + k + " j " + j);
            }
        }
        int[] v = ClubhousePlanner.videoWall(BOX);
        assertArrayEquals(new int[]{BOX.minX() + 15, BOX.minY() + F + 1, BOX.minZ() + 34, BOX.minX() + 15,
                BOX.minY() + F + 4, BOX.minZ() + 40}, v, "its corner blocks, for /collins pos1 and pos2");
        assertEquals(7, v[5] - v[2] + 1);
        assertEquals(4, v[4] - v[1] + 1);
        assertEquals(0, v[3] - v[0], "flat: one block thick, which is what a Collins screen is");
    }

    @Test
    void theyTookOnlyBareWallAndEmptyAirPutThoseBackAndItIsPlan15() {
        Plan p = SITE.plan();
        Map<String, String> b = blocks(SITE);
        Map<String, int[]> tv = tvBlocks();
        for (Map.Entry<String, int[]> e : tv.entrySet()) {
            int[] r = e.getValue();
            String was = id(b.get(key(r[0], r[1], r[2])));
            assertTrue(was.equals("minecraft:air") || was.contains("planks") || was.contains("log")
                    || was.contains("calcite"), e.getKey() + " was " + was + ": bare wall or air only");
        }
        List<String> palette = new ArrayList<>(p.palette());
        List<BlockOp> ops = new ArrayList<>();
        Set<String> done = new HashSet<>();
        Map<String, BlockOp> byKey = new HashMap<>();
        for (BlockOp op : p.ops()) {
            byKey.put(key(op.x() - BOX.minX(), op.y() - BOX.minY(), op.z() - BOX.minZ()), op);
        }
        for (BlockOp op : p.ops()) {
            String k = key(op.x() - BOX.minX(), op.y() - BOX.minY(), op.z() - BOX.minZ());
            int[] r = tv.get(k);
            if (r == null) {
                ops.add(op);
                continue;
            }
            done.add(k);
            BlockOp ref = byKey.get(key(r[0], r[1], r[2]));
            if (ref != null) {
                ops.add(new BlockOp(op.x(), op.y(), op.z(), ref.state()));
            }
        }
        assertEquals(tv.keySet(), done, "every TV block is in the plan");
        assertEquals(PLAN_15, Plan.hash(palette, ops, p.signs(), p.course()),
                "with the TVs' and the video wall's blocks as they were, the room is plan 15's, block for block:"
                        + " the Sports Board, its counter and stools, the armchairs, every machine and Mob Races'"
                        + " table are where they were");
    }

    @Test
    void nothingOfTheRoomIsInFrontOfTheVideoWallOrTheWallTv() {
        Map<String, String> b = blocks(SITE);
        for (int k = 34; k <= 40; k++) {
            for (int j = F + 1; j <= F + 4; j++) {
                String front = id(b.get(key(14, j, k)));
                assertFalse(front.contains("lantern") || front.contains("planks") || front.contains("chain")
                        || front.contains("concrete") || front.contains("blackstone") || front.contains("wool"),
                        "nothing in front of the video wall at z " + k + " j " + j + ": " + front);
            }
        }
        List<ClubhousePlanner.Place3> places = new ArrayList<>(ClubhousePlanner.parlorMachines(BOX));
        places.addAll(ClubhousePlanner.parlorRow(BOX));
        places.add(ClubhousePlanner.parlorTicket(BOX));
        places.add(ClubhousePlanner.parlorExit(BOX));
        for (ClubhousePlanner.Board bd : ClubhousePlanner.parlorBoards(BOX)) {
            places.add(bd.at());
            double z = bd.at().z() - BOX.minZ();
            assertTrue(z + bd.width() / 2.0 <= 59.0, "the Sports Board ends before the wall TV's frame");
            assertTrue(z - bd.width() / 2.0 >= 42.0, "and well south of the video wall");
        }
        for (ClubhousePlanner.Seat s : ClubhousePlanner.parlorSeats(BOX)) {
            places.add(new ClubhousePlanner.Place3(s.x(), s.y(), s.z(), s.yaw()));
        }
        for (ClubhousePlanner.Place3 p : places) {
            int i = (int) Math.floor(p.x() - BOX.minX());
            int k = (int) Math.floor(p.z() - BOX.minZ());
            assertFalse(i == 14 && k >= 34 && k <= 40, "nothing of HCM's stands in front of the video wall: " + p);
            assertFalse(i == 14 && k >= 60 && k <= 61, "nor in front of the wall TV: " + p);
            assertFalse(i >= 5 && i <= 9 && (k == 52 || k == 60) && p.y() - BOX.minY() >= F + 3,
                    "nor in a hanging TV: " + p);
        }
    }

    @Test
    void theTvScreensAndTheTickerAreWhereTheirBlocksAndClearAirAre() {
        List<ClubhousePlanner.Tv> tvs = ClubhousePlanner.parlorTvs(BOX);
        assertEquals(5, tvs.size(), "the wall TV and two faces of each hanging TV");
        ClubhousePlanner.Tv wall = tvs.get(0);
        assertEquals(BOX.minX() + 15.0, wall.at().x(), "on the wall's face");
        assertEquals(BOX.minZ() + 61.0, wall.at().z(), "the middle of z 60-61");
        assertEquals(BOX.minY() + F + 3.0, wall.at().y(), "the middle of its two rows");
        assertEquals(90f, wall.at().yaw(), "facing west, into the room");
        assertEquals(2.0, wall.width());
        assertEquals(0f, tvs.get(1).at().yaw(), "the first hanging TV's south face");
        assertEquals(BOX.minZ() + 53.0, tvs.get(1).at().z());
        assertEquals(180f, tvs.get(2).at().yaw(), "and its north face");
        assertEquals(BOX.minZ() + 52.0, tvs.get(2).at().z());
        assertEquals(BOX.minX() + 7.5, tvs.get(1).at().x());

        ClubhousePlanner.Ticker t = ClubhousePlanner.parlorTicker(BOX);
        assertNotNull(t);
        assertEquals(90f, t.at().yaw(), "facing west to the stools and the lounge");
        assertEquals(8.0, t.length());
        assertEquals(BOX.minZ() + 56.5, t.at().z(), "from under one beam to under the next");
        assertEquals(BOX.minY() + ClubhousePlanner.UP_TOP, t.at().y() + t.height(), 1e-9, "its top at the beams'");
        Map<String, String> b = blocks(SITE);
        int i = (int) Math.floor(t.at().x() - BOX.minX());
        assertEquals(ClubhousePlanner.BOOK_COUNTER_X, i, "over the counter");
        for (int k = 53; k <= 59; k++) {
            assertTrue(Set.of("minecraft:air", "minecraft:light").contains(id(b.get(key(i, ClubhousePlanner.UP_TOP
                    - 1, k)))), "the ticker hangs in clear air at z " + k);
        }
        assertTrue(SportsBar.textHigh(4, (float) (wall.width() * SportsBar.TV_SCALE_PER_BLOCK)) < wall.height(),
                "a game's four lines fit the wall TV's screen");
        assertTrue(SportsBar.textHigh(1 + 2 * com.dierks.homecraft.games.chance.sportsbook.ScoreScreens.TV_NEXT,
                (float) (3 * SportsBar.TV_SCALE_PER_BLOCK)) < 2.0, "and the next games' lines a hanging TV's");
        assertTrue(SportsBar.LINE_HIGH * SportsBar.TICKER_SCALE < t.height(), "the ticker's line fits its strip");
    }

    @Test
    void aWingKeptAtItsOldSizeHasNoTvsNoTickerAndNoVideoWall() {
        assertTrue(ClubhousePlanner.parlorTvs(OLD).isEmpty());
        assertNull(ClubhousePlanner.parlorTicker(OLD));
        assertNull(ClubhousePlanner.videoWall(OLD));
        assertEquals(ClubhousePlannerTest.OLD_HASH, ClubhousePlanner.plan(OLD).plan().hash(), "plan 12's room still");
    }
}
