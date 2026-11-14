package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.util.Text;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The trophy case's rows (0.38.4): the courses' records first, then golf's, cut to the board's rows; names and
 * courses cut as the boards cut them; the label over each, three short lines that never reach the next frame's
 * (Java: narrower than the step; Bedrock: every other label higher, all under My stats); and the way a frame hangs
 * for each yaw.
 */
class TrophyWallTest {

    @Test
    void coursesThenGolfCutToTheRows() {
        List<FameBoard.CourseTime> records = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            records.add(new FameBoard.CourseTime("Course " + i, "Sam", 60_000 + i * 1_000));
        }
        List<FameBoard.GolfRound> golf = List.of(new FameBoard.GolfRound("Tiny Golf", "A Very Long Player Name", 24, -2),
                new FameBoard.GolfRound("Big Golf", "Lee", 40, 0), new FameBoard.GolfRound("Three", "Ana", 41, 1));
        FameBoard.Fame f = new FameBoard.Fame(List.of(), null, null, 0, List.of(), records, golf);
        List<TrophyWall.Trophy> rows = TrophyWall.rows(f);
        assertEquals(ClubBoard.MAX_ROWS, rows.size(), "at most the board's rows");
        assertEquals(new TrophyWall.Trophy("Course 0", "Sam", "1:00.0", false), rows.get(0), "a course's record");
        assertFalse(rows.get(5).golf(), "the sixth course");
        assertEquals(new TrophyWall.Trophy("Tiny Golf", "A Very Long P...", "24 (-2)", true), rows.get(6),
                "then golf, the name cut as the boards cut it");
        assertEquals("Big Golf", rows.get(7).course(), "the eighth is golf's second; the third is cut off");
        assertEquals("&6Course 0\n&f1:00.0\n&7Sam", TrophyWall.line(rows.get(0)), "the label over it: three lines");
        assertEquals("Tiny Golf\n24 (-2)\nA Very Long P...", Text.plain(TrophyWall.line(rows.get(6))));
        assertTrue(TrophyWall.rows(null).isEmpty(), "nothing read yet: no trophies");
        assertTrue(TrophyWall.rows(new FameBoard.Fame(List.of(), null, null, 0, List.of(), List.of(), List.of()))
                .isEmpty(), "no records: no trophies");
    }

    @Test
    void aLabelNeverReachesItsNeighbour() {
        assertTrue(TrophyWall.width() < ClubhousePlanner.TROPHY_STEP,
                "Java: the widest line wraps before the next frame: " + TrophyWall.width());
        FameBoard.Fame worst = new FameBoard.Fame(List.of(), null, null, 0, List.of(),
                List.of(new FameBoard.CourseTime("W".repeat(40), "W".repeat(40), 3_599_900)), List.of());
        String[] lines = Text.plain(TrophyWall.line(TrophyWall.rows(worst).get(0))).split("\n");
        assertEquals(TrophyWall.LINES, lines.length, "course, score, holder");
        // Bedrock: a name tag at full size, about 7 px for the widest letter; labels at one height are two steps apart
        for (String l : lines) {
            assertTrue(l.length() <= ClubBoardText.COURSE_MAX, "short: " + l);
            assertTrue(l.length() * 7 * WallBoards.PX < 2 * ClubhousePlanner.TROPHY_STEP, "a name tag clears: " + l);
        }
        assertEquals(TrophyWall.lift(0), TrophyWall.lift(2), 1e-9, "the even slots at one height");
        assertEquals(TrophyWall.lift(1), TrophyWall.lift(7), 1e-9, "the odd slots at another");
        assertEquals(TrophyWall.LINE_UP + TrophyWall.STAGGER, TrophyWall.lift(1), 1e-9, "every other label higher");
        double tag = TrophyWall.LINES * WallBoards.LINE_PX * WallBoards.PX; // three lines at full size
        assertTrue(TrophyWall.lift(0) + tag < TrophyWall.lift(1), "a raised label clears the one under it");
        assertTrue(ClubhousePlanner.TROPHY_Y + TrophyWall.lift(1) + tag < WallBoards.BOTTOM,
                "and ends under the My stats board");
        assertTrue(TrophyWall.lift(0) > 1, "over the frame's block");
    }

    @Test
    void aLabelHangsFlatOnTheWallNotOutInTheRoom() {
        // 0.39.1, the owner's screenshot of 14 Nov: the labels stood a block out from the wall
        double fromWall = 0.5 + TrophyWall.LINE_OUT; // the frame block's middle is half a block out from the wall
        assertTrue(fromWall > 0, "in front of the wall, never inside it");
        assertTrue(fromWall < 0.1, "close enough to read as on the wall: " + fromWall);
    }

    @Test
    void aFrameIsMadeAgainWhenItsSlotMovedOrTurned() {
        int[] at = {10, 64, -5};
        assertFalse(TrophyWall.moved(at, -90f, new int[]{10, 64, -5}, -90f), "the same slot, the same way: as it is");
        assertTrue(TrophyWall.moved(at, -90f, new int[]{11, 64, -5}, -90f), "an owner set the trophies spot again");
        assertTrue(TrophyWall.moved(at, -90f, new int[]{10, 64, -5}, 90f), "or faced the other way");
        assertFalse(TrophyWall.moved(at, -90f, new int[]{10, 64, -5}, -100f), "a yaw squares to the same wall");
    }

    @Test
    void aFrameHangsTheWayItsYawFaces() {
        assertEquals(BlockFace.EAST, TrophyWall.facing(-90f), "the generated room's west wall: facing east");
        assertEquals(BlockFace.SOUTH, TrophyWall.facing(0f));
        assertEquals(BlockFace.WEST, TrophyWall.facing(90f));
        assertEquals(BlockFace.NORTH, TrophyWall.facing(180f));
        assertEquals(BlockFace.NORTH, TrophyWall.facing(-170f), "squared");
    }
}
