package com.dierks.homecraft.games.gen.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Board names (GEN-SPEC §5.2, weekly addendum §3): one spelling each — {@code gfresh:<slot>:<edition>}
 * with the cadence in the edition key, stars per edition, the Star Chart per week — and each name
 * reads back to what it was made from (slot ids with underscores, negative indexes and reroll
 * editions included), while anything else reads as not a Fresh Courses board.
 */
class GenBoardsTest {

    private static final GenTag WEEKLY = new GenTag("fresh_golf", "golf", 1, 20724, 2, 1, 'A', "", 0, 0, 0,
            List.of(), List.of(), 0, 7);

    @Test
    void boardsHaveOneSpelling() {
        assertEquals("gfresh:fresh_parkour_easy:7:38", GenBoards.day("fresh_parkour_easy", "7:38"), "an edition board");
        assertEquals("gfresh:fresh_golf:7:38r2", GenBoards.day(WEEKLY), "a tag's board is its edition's, reroll too");
        assertEquals("gfresh:fresh_golf:1:267", GenBoards.day(new GenTag("fresh_golf", "golf", 1, 20725, 0, 1, 'A',
                "", 0, 0, 0, List.of(), List.of(), 0)), "a daily edition's board carries its cadence too");
        assertEquals("gstars:fresh_golf:7:38", GenBoards.stars(WEEKLY), "stars are per edition, any reroll");
        assertEquals("gstars:fresh_rings:7:38", GenBoards.stars("fresh_rings", "7:38"), "by edition key");
        assertEquals("gstars:fresh_rings:20725", GenBoards.stars("fresh_rings", 20725), "or by course day");
        assertEquals("gweek:20720", GenBoards.week(20720), "the Star Chart is per week, whatever the cadence");
        assertEquals("fresh_courses", GenBoards.GAME, "the star boards live under the fresh_courses game");
        assertFalse(GenBoards.day("fresh_golf", "1:38").equals(GenBoards.day("fresh_golf", "7:38")),
                "a daily and a weekly edition never share a board");
    }

    @Test
    void namesReadBackToWhatTheyWereMadeFrom() {
        assertEquals(new GenBoards.Board(GenBoards.Kind.DAY, "fresh_parkour_easy", 20724, 0, "7:38"),
                GenBoards.parse("gfresh:fresh_parkour_easy:7:38"), "an edition board");
        assertEquals(new GenBoards.Board(GenBoards.Kind.DAY, "fresh_tiny_golf", 20725, 3, "1:267"),
                GenBoards.parse("gfresh:fresh_tiny_golf:1:267r3"), "a reroll's board");
        assertEquals(7, GenBoards.parse("gfresh:fresh_golf:7:38r1").cadence(), "its cadence");
        assertEquals(new GenBoards.Board(GenBoards.Kind.STARS, "fresh_rings", 20724, 0, "7:38"),
                GenBoards.parse("gstars:fresh_rings:7:38"), "a stars board per edition");
        assertEquals(new GenBoards.Board(GenBoards.Kind.STARS, "fresh_rings", 20725, 0),
                GenBoards.parse("gstars:fresh_rings:20725"), "a stars board per course day");
        assertEquals(new GenBoards.Board(GenBoards.Kind.WEEK, null, 20720, 0), GenBoards.parse("gweek:20720"),
                "the Star Chart");
        assertEquals(new GenBoards.Board(GenBoards.Kind.DAY, "fresh_golf", 20458 - 14, 0, "7:-2"),
                GenBoards.parse("gfresh:fresh_golf:7:-2"), "an edition before the epoch");
        for (Slots.Def slot : Slots.ALL) {
            assertEquals(slot.id(), GenBoards.parse(GenBoards.day(slot.id(), "3:89r1")).courseId(),
                    "every slot id survives the trip: " + slot.id());
        }
    }

    @Test
    void anythingElseIsNotAFreshCoursesBoard() {
        for (String other : new String[]{null, "", "classic", "course:river_run", "week:river_run:20720",
                "golf:meadow", "daily:20725", "gday:fresh_golf:20725", "gfresh:", "gfresh:x", "gfresh::7:38",
                "gfresh:x:abc", "gfresh:x:20725", "gfresh:x:7:38r", "gfresh:x:7:38r0", "gfresh:x:7:38rx",
                "gfresh:x:0:1", "gfresh:x:29:1", "gfresh:x:07:38", "gstars:x:7:38r1", "gstars:x:abc", "gweek:",
                "gweek:x"}) {
            assertNull(GenBoards.parse(other), "'" + other + "' is not ours");
            assertFalse(GenBoards.generated(other), "'" + other + "' is not generated");
        }
        assertTrue(GenBoards.generated("gweek:1"), "a real one is");
    }
}
