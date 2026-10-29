package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Plan;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Clubhouse's boards locked in place (0.38.3; the owner: "Is there a way to lock it in place?"): in the
 * generated room each hangs flat on its own wall, in the room's air just off the wall's face, facing into the room,
 * over the windows and under the trim, in front of solid wall (the results board in front of its black panel), and
 * nothing about the room's blocks changes. An owner-built room's board faces the way the admin faced, squared.
 */
class WallBoardsTest {

    private static final Box BOX = ClubhouseSettings.defaults().box();
    private static final ClubhouseSite SITE = ClubhousePlanner.plan(BOX);
    private static final List<String> ALL = List.of(WallBoards.RESULTS, WallBoards.FAME, WallBoards.PLAYING,
            WallBoards.STATS);

    /** Every block of the plan by position ({@code null}: air). */
    private static Map<String, String> blocks(Plan plan) {
        Map<String, String> out = new HashMap<>();
        for (BlockOp op : plan.ops()) {
            out.put(op.x() + "," + op.y() + "," + op.z(), plan.blockOf(op));
        }
        return out;
    }

    /** The way a yaw faces, as (x, z): 0 south (+z), 90 west (-x), 180 north (-z), -90 east (+x). */
    private static double[] facing(float yaw) {
        double r = Math.toRadians(yaw);
        return new double[]{-Math.sin(r), Math.cos(r)};
    }

    /** Whether a board runs along x (the north and south walls) or along z (the east and west ones). */
    private static boolean alongX(WallBoards.Wall w) {
        return Math.abs(facing(w.yaw())[1]) > 0.5;
    }

    @Test
    void everyBoardHangsInTheRoomsAirJustOffItsWallFacingIn() {
        double midX = BOX.minX() + 16.0;
        double midZ = BOX.minZ() + 16.0;
        Map<String, Float> faces = Map.of(WallBoards.RESULTS, 0f, WallBoards.FAME, 90f, WallBoards.PLAYING, 180f,
                WallBoards.STATS, -90f);
        Map<String, Double> wallFace = Map.of(WallBoards.RESULTS, BOX.minZ() + 1.0, WallBoards.FAME,
                (double) BOX.maxX(), WallBoards.PLAYING, (double) BOX.maxZ(), WallBoards.STATS, BOX.minX() + 1.0);
        for (String which : ALL) {
            WallBoards.Wall w = WallBoards.generated(which, BOX);
            assertNotNull(w, which);
            assertTrue(SITE.in(w.x(), w.y() + 0.5, w.z()), which + " hangs in the room's air, never in a wall: " + w);
            assertEquals(faces.get(which), w.yaw(), 0.001f, which + " faces into the room");
            double off = alongX(w) ? Math.abs(w.z() - wallFace.get(which)) : Math.abs(w.x() - wallFace.get(which));
            assertTrue(off > 0.01 && off <= 0.1, which + " is just off its wall's face (never in it, never far): " + off);
            double[] f = facing(w.yaw());
            assertTrue(f[0] * (midX - w.x()) + f[1] * (midZ - w.z()) > 10, which + " faces the room's middle");
            assertEquals(WallBoards.SCALE, w.scale(), 0.001f, which + " is drawn big, to read across the room");
        }
        assertNull(WallBoards.generated("podium", BOX), "only the four boards");
        assertNull(WallBoards.generated(null, BOX), "no word, no board");
    }

    @Test
    void everyBoardFitsItsWallOverTheWindowsUnderTheTrimInFrontOfSolidWall() {
        Map<String, String> b = blocks(SITE.plan());
        for (String which : ALL) {
            WallBoards.Wall w = WallBoards.generated(which, BOX);
            double bottom = w.y() - BOX.minY();
            double top = bottom + w.height();
            assertTrue(bottom >= 7.0, which + " is over the windows (3-6): " + bottom);
            assertTrue(top <= ClubhousePlanner.WALL_TOP, which + " is under the trim (14): " + top);
            double along = alongX(w) ? w.x() : w.z();
            double from = along - w.width() / 2;
            double to = along + w.width() / 2;
            double lo = alongX(w) ? BOX.minX() + 1 : BOX.minZ() + 1;
            double hi = alongX(w) ? BOX.maxX() : BOX.maxZ();
            assertTrue(from >= lo + 1 && to <= hi - 1, which + " fits between the corners: " + from + ".." + to);
            // the wall right behind it is solid all over: no window, no hole
            int wallX = which.equals(WallBoards.FAME) ? BOX.maxX() : BOX.minX();
            int wallZ = which.equals(WallBoards.PLAYING) ? BOX.maxZ() : BOX.minZ();
            for (int y = (int) Math.floor(w.y()); y < Math.ceil(w.y() + w.height()); y++) {
                for (int a = (int) Math.floor(from); a < Math.ceil(to); a++) {
                    String block = alongX(w) ? b.get(a + "," + y + "," + wallZ) : b.get(wallX + "," + y + "," + a);
                    assertTrue(block != null && !block.equals(ClubhousePlanner.WINDOW),
                            which + " has solid wall behind it at " + a + "," + y + ": " + block);
                }
            }
        }
        WallBoards.Wall results = WallBoards.generated(WallBoards.RESULTS, BOX);
        assertTrue(results.x() - results.width() / 2 >= BOX.minX() + ClubhousePlanner.PANEL_X0
                        && results.x() + results.width() / 2 <= BOX.minX() + ClubhousePlanner.PANEL_X1 + 1,
                "the results board is in front of its black panel, side to side");
        assertTrue(results.y() >= BOX.minY() + ClubhousePlanner.PANEL_Y0
                        && results.y() + results.height() <= BOX.minY() + ClubhousePlanner.PANEL_Y1 + 1,
                "and top to bottom");
        assertEquals(10.1, results.width(), 0.001, "10 blocks of text at 200 px, scale 2 (and its background's edge)");
        assertEquals(4.5, results.height(), 0.001, "9 lines");
    }

    @Test
    void theBoardsChangeNoBlockOfTheRoom() {
        assertEquals(1, ClubhousePlanner.ALGO, "the room's plan is the same: its built and checked state stays");
        assertEquals(SITE.plan().hash(), ClubhousePlanner.plan(BOX).plan().hash(), "the same blocks");
        assertEquals(SITE.board().x(), BOX.minX() + 16.0, 0.001, "the results board's old spot is kept (the check)");
    }

    @Test
    void anOwnerBuiltBoardFacesTheWayTheAdminFacedSquared() {
        WallBoards.Wall w = WallBoards.hand(new ClubhouseRoom.Place("games", 10.5, 70, -3.25, 44f));
        assertEquals(10.5, w.x(), 0.0001, "where they stood");
        assertEquals(70, w.y(), 0.0001, "at their feet");
        assertEquals(-3.25, w.z(), 0.0001, "where they stood");
        assertEquals(0f, w.yaw(), 0.001f, "44 is south");
        assertEquals(WallBoards.HAND_SCALE, w.scale(), 0.001f, "a size for a room of any size");
        assertEquals(90f, WallBoards.snap(46f), 0.001f, "46 is west");
        assertEquals(180f, WallBoards.snap(-136f), 0.001f, "-136 is north");
        assertEquals(-90f, WallBoards.snap(-134f), 0.001f, "-134 is east");
        assertEquals(-90f, WallBoards.snap(271f), 0.001f, "271 is east");
        assertEquals(180f, WallBoards.snap(190f), 0.001f, "190 is north");
        assertEquals(180f, WallBoards.snap(-180f), 0.001f, "-180 is north");
        assertEquals(0f, WallBoards.snap(-721f), 0.001f, "any turn round");
        assertNull(WallBoards.hand(null), "no spot, no board");
    }

    @Test
    void aBoardIsKeptOnlyWhileItIsStillLockedThere() {
        WallBoards.Wall w = WallBoards.generated(WallBoards.FAME, BOX);
        assertTrue(BoardDisplay.still(true, 90f, 0.01, w), "FIXED, its way, in place");
        assertTrue(BoardDisplay.still(true, -270f, 0.01, w), "the same way read back another way round");
        assertFalse(BoardDisplay.still(false, 90f, 0.01, w), "an old VERTICAL board is made again");
        assertFalse(BoardDisplay.still(true, 0f, 0.01, w), "a board turned another way is made again");
        assertFalse(BoardDisplay.still(true, 90f, 0.5, w), "a board moved (the owner set it again) is made again");
        assertTrue(WallBoards.sameWay(179.5f, -180f), "within a degree");
        assertFalse(WallBoards.sameWay(10f, 12f), "two degrees off");
    }

    @Test
    void aBoardsTextIsPaddedToItsFrameAndNeverCut() {
        assertEquals(WallBoards.LINES, WallBoards.pad("&6Title").split("\n", -1).length, "a title alone: 9 lines");
        assertTrue(WallBoards.pad("&6Title\nrow").startsWith("&6Title\nrow\n "), "the title stays on top");
        String ten = String.join("\n", java.util.Collections.nCopies(10, "x"));
        assertEquals(ten, WallBoards.pad(ten), "never cut");
        assertEquals(WallBoards.LINES, WallBoards.pad(null).split("\n", -1).length, "nothing: a blank frame");
        assertEquals(9, WallBoards.LINES, "a title and 8 rows");
    }
}
