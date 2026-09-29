package com.dierks.homecraft.gui.games;

import com.dierks.homecraft.games.Breaks;
import com.dierks.homecraft.games.GameKind;
import com.dierks.homecraft.games.gen.api.GenBoards;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * How high scores read: times, units and board names (spec §6.2), Daily Courses' boards included
 * (GEN-SPEC §5.2): a layout's board is a time (strokes for golf) named by its course and day, a
 * day's stars and the Star Chart are stars, and the chart names its week.
 */
class ScoresMenuTest {

    @Test
    void timesReadAsMinutesSecondsAndTenths() {
        assertEquals("1:23.4", ScoresMenu.time(83_456), "tenths are floored, not rounded");
        assertEquals("1:02.3", ScoresMenu.time(62_300), "seconds keep their leading zero");
        assertEquals("0:05.0", ScoresMenu.time(5_000), "under a minute still shows 0:");
        assertEquals("0:00.0", ScoresMenu.time(-50), "a bad value never prints a minus sign");
    }

    @Test
    void scoresReadInTheirOwnUnit() {
        assertEquals("1:23.4", ScoresMenu.score("ms", 83_456), "milliseconds are a time");
        assertEquals("12 apples", ScoresMenu.score("apples", 12), "apples");
        assertEquals("1 apple", ScoresMenu.score("apples", 1), "one apple is singular");
        assertEquals("20 flips", ScoresMenu.score("flips", 20), "flips");
        assertEquals("1 point", ScoresMenu.score("points", 1), "one point is singular");
        assertEquals("27 strokes", ScoresMenu.score("strokes", 27), "golf strokes");
        assertEquals("5 points", ScoresMenu.score(null, 5), "no unit known: points");
    }

    @Test
    void theUnitComesFromTheBoardKindOrTheCabinet() {
        assertEquals("ms", ScoresMenu.unitFor("course:river", "apples"), "a course board is a time");
        assertEquals("ms", ScoresMenu.unitFor("week:river:2900", null), "a weekly course board is a time");
        assertEquals("strokes", ScoresMenu.unitFor("golf:meadow", null), "a golf board is strokes");
        assertEquals("apples", ScoresMenu.unitFor("classic", "apples"), "a cabinet board is in the cabinet's unit");
        assertEquals("ms", ScoresMenu.unitFor("easy", "ms"), "Creeper Sweeper's easy board is a time like normal");
        assertEquals("points", ScoresMenu.unitFor("classic", null), "unknown: points");
    }

    @Test
    void boardsReadAsWordsNotKeys() {
        Function<String, String> names = id -> Map.of("river", "River Run", "meadow", "Meadow Links")
                .getOrDefault(id, id);
        assertEquals("Classic", ScoresMenu.boardLabel("classic", 100, names), "classic");
        assertEquals("Hard", ScoresMenu.boardLabel("hard", 100, names), "a difficulty");
        assertEquals("Today's challenge", ScoresMenu.boardLabel("daily:100", 100, names), "today's daily board");
        assertEquals("Challenge of " + Breaks.dateText(99), ScoresMenu.boardLabel("daily:99", 100, names),
                "an older daily board names its day");
        assertEquals("Challenge of a past day", ScoresMenu.boardLabel("daily:junk", 100, names),
                "a board key that isn't a day still reads");
        assertEquals("River Run", ScoresMenu.boardLabel("course:river", 100, names), "a course by its name");
        assertEquals("River Run this week", ScoresMenu.boardLabel("week:river:2900", 100, names), "a course's week");
        assertEquals("Meadow Links", ScoresMenu.boardLabel("golf:meadow", 100, names), "a golf course by its name");
        assertEquals("gone", ScoresMenu.boardLabel("course:gone", 100, names), "an unknown course shows its id");
    }

    // ---- Daily Courses' boards -------------------------------------------------------------------

    private static final long TODAY = 20_725;      // Tue 29 Sep 2026
    private static final long THIS_WEEK = 20_724;  // Mon 28 Sep 2026

    private static String label(String board) {
        Function<String, String> names = id -> Map.of("daily_golf", "Daily Golf").getOrDefault(id, id);
        return ScoresMenu.boardLabel(board, 999, TODAY, THIS_WEEK, names);
    }

    @Test
    void dailyBoardsReadAsTheCourseAndItsDay() {
        assertEquals("Easy Parkour · today", label(GenBoards.day("daily_parkour_easy", "20725")),
                "today's layout, named by its slot even when it isn't open");
        assertEquals("Easy Parkour · Mon 28 Sep", label(GenBoards.day("daily_parkour_easy", "20724")),
                "an older layout names its day");
        assertEquals("Daily Golf · today (layout 2)", label(GenBoards.day("daily_golf", "20725r1")),
                "a reroll says which layout of the day it is; a live course's own name is used");
        assertEquals("Hard Parkour stars · today", label(GenBoards.stars("daily_parkour_hard", TODAY)),
                "a player's stars that day");
        assertEquals("Star Chart · this week", label(GenBoards.week(THIS_WEEK)), "this week's chart");
        assertEquals("Star Chart · week of Mon 21 Sep", label(GenBoards.week(THIS_WEEK - 7)), "last week's");
        assertEquals("mystery · today", label(GenBoards.day("mystery", "20725")),
                "an unknown course shows its id");
        assertEquals("River Run", ScoresMenu.boardLabel("course:river", 100, TODAY, THIS_WEEK, id -> "River Run"),
                "every other board reads as before");
    }

    @Test
    void dailyBoardsAreTimesStrokesOrStars() {
        assertEquals("ms", ScoresMenu.unitFor(GenBoards.day("sky_rings", "20725"), null, GameKind.TRIAL),
                "a trial's layout board is a time");
        assertEquals("strokes", ScoresMenu.unitFor(GenBoards.day("tiny_golf", "20725"), null, GameKind.GOLF),
                "a golf layout board is strokes");
        assertEquals("stars", ScoresMenu.unitFor(GenBoards.week(THIS_WEEK), "points", GameKind.TRIAL),
                "the Star Chart counts stars");
        assertEquals("stars", ScoresMenu.unitFor(GenBoards.stars("tiny_golf", TODAY), null, GameKind.TRIAL),
                "and so does a day's stars board");
        assertEquals("14 stars", ScoresMenu.score("stars", 14), "stars read as stars");
        assertEquals("1 star", ScoresMenu.score("stars", 1), "one is singular");
        assertEquals("apples", ScoresMenu.unitFor("classic", "apples", GameKind.CABINET), "a cabinet's own unit");
    }
}
