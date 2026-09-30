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
        GolfCourse tiny = course("fresh_tiny_golf", tag("fresh_tiny_golf", 0));
        GolfCourse daily = course("fresh_golf", tag("fresh_golf", 0));
        assertEquals(List.of(daily, tiny, alpine, meadow), MiniGolf.sorted(List.of(meadow, tiny, alpine, daily)),
                "Golf of the Week, Tiny Golf, then the rest by id");
    }

    @Test
    void aDailyCoursesScoresGoOnItsLayoutsBoard() {
        GenTag rerolled = tag("fresh_tiny_golf", 1);
        assertEquals(GenBoards.day("fresh_tiny_golf", rerolled.editionKey()), MiniGolf.board(course("fresh_tiny_golf",
                rerolled)), "the layout's own board");
        assertEquals("gfresh:fresh_tiny_golf:1:267r1", MiniGolf.board(course("fresh_tiny_golf", rerolled)),
                "a daily layout's edition key, reroll and all");
        assertEquals("golf:meadow", MiniGolf.board(course("meadow", null)), "a hand-built course keeps its board");
    }
}
