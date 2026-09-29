package com.dierks.homecraft.games.golf;

import com.dierks.homecraft.games.gen.api.GenBoards;
import com.dierks.homecraft.games.gen.api.GenTag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Mini Golf's daily courses (GEN-SPEC §5.4), with no server: they come first on the Golf tab in
 * slot order (Daily Golf, then Tiny Golf), then the hand-built courses by id as before; and their
 * scores go on the layout's own board.
 */
class MiniGolfTest {

    private static GolfCourse course(String id, GenTag gen) {
        return new GolfCourse(id, id, "games", true, 1, List.of(), gen);
    }

    private static GenTag tag(String slot, int reroll) {
        return new GenTag(slot, "golf", 1, 20_725, reroll, 1L, 'B', "abcabcabcabc", 0, 0, 0, List.of(0), List.of(), 1L);
    }

    @Test
    void dailyCoursesComeFirstInSlotOrder() {
        GolfCourse alpine = course("alpine", null);
        GolfCourse meadow = course("meadow", null);
        GolfCourse tiny = course("tiny_golf", tag("tiny_golf", 0));
        GolfCourse daily = course("daily_golf", tag("daily_golf", 0));
        assertEquals(List.of(daily, tiny, alpine, meadow), MiniGolf.sorted(List.of(meadow, tiny, alpine, daily)),
                "Daily Golf, Tiny Golf, then the rest by id");
    }

    @Test
    void aDailyCoursesScoresGoOnItsLayoutsBoard() {
        assertEquals(GenBoards.day("tiny_golf", "20725r1"), MiniGolf.board(course("tiny_golf", tag("tiny_golf", 1))),
                "the layout's own board");
        assertEquals("golf:meadow", MiniGolf.board(course("meadow", null)), "a hand-built course keeps its board");
    }
}
