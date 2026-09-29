package com.dierks.homecraft.games.gen.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Board names (GEN-SPEC §5.2): one spelling each, and each name reads back to what it was made
 * from — slot ids with underscores and reroll editions included — while anything else reads as
 * not a Daily Courses board.
 */
class GenBoardsTest {

    @Test
    void boardsHaveOneSpelling() {
        assertEquals("gday:daily_parkour_easy:20725", GenBoards.day("daily_parkour_easy", "20725"), "a day board");
        assertEquals("gday:daily_golf:20725r2", GenBoards.day(new GenTag("daily_golf", "golf", 1, 20725, 2, 1, 'A',
                "", 0, 0, 0, List.of(), List.of(), 0)), "a tag's board is its edition's");
        assertEquals("gstars:sky_rings:20725", GenBoards.stars("sky_rings", 20725), "a stars board");
        assertEquals("gweek:20720", GenBoards.week(20720), "the Star Chart");
        assertEquals("daily", GenBoards.GAME, "the star boards live under the daily game");
    }

    @Test
    void namesReadBackToWhatTheyWereMadeFrom() {
        assertEquals(new GenBoards.Board(GenBoards.Kind.DAY, "daily_parkour_easy", 20725, 0),
                GenBoards.parse("gday:daily_parkour_easy:20725"), "a day board");
        assertEquals(new GenBoards.Board(GenBoards.Kind.DAY, "tiny_golf", 20725, 3),
                GenBoards.parse("gday:tiny_golf:20725r3"), "a reroll's board");
        assertEquals(new GenBoards.Board(GenBoards.Kind.STARS, "sky_rings", 20725, 0),
                GenBoards.parse("gstars:sky_rings:20725"), "a stars board");
        assertEquals(new GenBoards.Board(GenBoards.Kind.WEEK, null, 20720, 0), GenBoards.parse("gweek:20720"),
                "the Star Chart");
        for (Slots.Def slot : Slots.ALL) {
            assertEquals(slot.id(), GenBoards.parse(GenBoards.day(slot.id(), "20725r1")).courseId(),
                    "every slot id survives the trip: " + slot.id());
        }
    }

    @Test
    void anythingElseIsNotADailyCoursesBoard() {
        for (String other : new String[]{null, "", "classic", "course:river_run", "week:river_run:20720",
                "golf:meadow", "daily:20725", "gday:", "gday:x", "gday::20725", "gday:x:abc", "gday:x:20725r",
                "gday:x:20725r0", "gday:x:20725rx", "gstars:x:20725r1", "gweek:", "gweek:x"}) {
            assertNull(GenBoards.parse(other), "'" + other + "' is not ours");
            assertFalse(GenBoards.generated(other), "'" + other + "' is not generated");
        }
        assertTrue(GenBoards.generated("gweek:1"), "a real one is");
    }
}
