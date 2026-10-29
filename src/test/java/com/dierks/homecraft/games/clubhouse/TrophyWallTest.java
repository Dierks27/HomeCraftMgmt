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
 * courses cut as the boards cut them; the line over each; and the way a frame hangs for each yaw.
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
        assertEquals("&6Course 0 &f1:00.0 &7- Sam", TrophyWall.line(rows.get(0)), "the line over it");
        assertEquals("Tiny Golf 24 (-2) - A Very Long P...", Text.plain(TrophyWall.line(rows.get(6))));
        assertTrue(TrophyWall.rows(null).isEmpty(), "nothing read yet: no trophies");
        assertTrue(TrophyWall.rows(new FameBoard.Fame(List.of(), null, null, 0, List.of(), List.of(), List.of()))
                .isEmpty(), "no records: no trophies");
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
