package com.dierks.homecraft.gui.games;

import com.dierks.homecraft.games.Breaks;
import com.dierks.homecraft.games.GameKind;
import com.dierks.homecraft.games.gen.api.Edition;
import com.dierks.homecraft.games.gen.api.GenBoards;
import com.dierks.homecraft.gui.games.daily.DailyText;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How high scores read: times, units and board names (spec §6.2), Fresh Courses' boards included
 * (GEN-SPEC §5.2, the weekly addendum §3, GEN-SPEC-KEEP §3): a set's board is a time (strokes for
 * golf) named by its course and its set ("this week", "week of Mon 28 Sep", a daily set's date,
 * a 3-day set's first and last day), a set recalled into a Classics slot reads as a classic, stars
 * and the Star Chart are stars, and the chart names its week.
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

    // ---- Fresh Courses' boards -------------------------------------------------------------------

    private static final long TODAY = 20_725;      // Tue 29 Sep 2026
    private static final long THIS_WEEK = 20_724;  // Mon 28 Sep 2026
    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");
    /** Tue 29 Sep 2026, 15:00 in Chicago. */
    private static final long NOW = LocalDateTime.of(2026, 9, 29, 15, 0).atZone(CHICAGO).toInstant().toEpochMilli();

    private static Edition schedule(int cadence) {
        return new Edition(CHICAGO, Edition.DEFAULT_ROLLOVER, DayOfWeek.MONDAY, cadence, null);
    }

    private static String label(String board, int cadence) {
        return label(board, cadence, b -> false);
    }

    private static String label(String board, int cadence, Predicate<String> recalled) {
        Function<String, String> names = id -> Map.of("fresh_golf", "Golf of the Week").getOrDefault(id, id);
        return ScoresMenu.boardLabel(board, 999, schedule(cadence), NOW, THIS_WEEK, names, recalled);
    }

    @Test
    void freshBoardsReadAsTheCourseAndItsSet() {
        assertEquals("Easy Parkour · this week",
                label(GenBoards.day("fresh_parkour_easy", Edition.editionKey(7, THIS_WEEK, 0)), 7),
                "the set up now, named by its slot even when it isn't open");
        assertEquals("Easy Parkour · week of Mon 21 Sep",
                label(GenBoards.day("fresh_parkour_easy", Edition.editionKey(7, THIS_WEEK - 7, 0)), 7),
                "an older weekly set names its week");
        assertEquals("Golf of the Week · this week (layout 2)",
                label(GenBoards.day("fresh_golf", Edition.editionKey(7, THIS_WEEK, 1)), 7),
                "a reroll says which layout of the set it is; a live course's own name is used");
        assertEquals("Easy Parkour · today", label(GenBoards.day("fresh_parkour_easy", Edition.editionKey(TODAY, 0)), 1),
                "a daily set up now: today");
        assertEquals("Easy Parkour · Mon 28 Sep",
                label(GenBoards.day("fresh_parkour_easy", Edition.editionKey(TODAY - 1, 0)), 1),
                "an older daily set names its day");
        assertEquals("Easy Parkour · Mon 28 Sep",
                label(GenBoards.day("fresh_parkour_easy", Edition.editionKey(TODAY - 1, 0)), 7),
                "a daily set kept under a weekly schedule still reads as its day");
        String three = GenBoards.day("fresh_rings", Edition.editionKey(3, schedule(3).editionStart(NOW), 0));
        assertEquals("Sky Rings · " + DailyText.setDates(3, schedule(3).editionStart(NOW)), label(three, 3),
                "a 3-day set reads as its first and last day");
        assertTrue(label(three, 3).matches("Sky Rings · \\w{3} \\d+ \\w{3}-\\w{3} \\d+ \\w{3}"),
                "like Mon 28 Sep-Wed 30 Sep: " + label(three, 3));
        assertEquals("Hard Parkour stars · this week",
                label(GenBoards.stars("fresh_parkour_hard", Edition.editionKey(7, THIS_WEEK, 0)), 7),
                "a player's stars in the set up now");
        assertEquals("Hard Parkour stars · today", label(GenBoards.stars("fresh_parkour_hard", TODAY), 7),
                "stars kept by day read as the day");
        assertEquals("Star Chart · this week", label(GenBoards.week(THIS_WEEK), 7), "this week's chart");
        assertEquals("Star Chart · week of Mon 21 Sep", label(GenBoards.week(THIS_WEEK - 7), 7), "last week's");
        assertEquals("mystery · this week", label(GenBoards.day("mystery", Edition.editionKey(7, THIS_WEEK, 0)), 7),
                "an unknown course shows its id");
        assertEquals("River Run", ScoresMenu.boardLabel("course:river", 100, schedule(7), NOW, THIS_WEEK,
                id -> "River Run", b -> false), "every other board reads as before");
    }

    @Test
    void aRecalledSetsBoardReadsAsAClassic() {
        String board = GenBoards.day("fresh_parkour_hard", Edition.editionKey(7, THIS_WEEK + 7, 0));
        assertEquals("Classic: Hard Parkour · week of 5 Oct", label(board, 7, board::equals),
                "the original board, with its old records, named as the classic it is now");
        assertEquals("Hard Parkour · week of Mon 5 Oct", label(board, 7), "and as itself when it isn't recalled");
    }

    @Test
    void dailyBoardsAreTimesStrokesOrStars() {
        assertEquals("ms", ScoresMenu.unitFor(GenBoards.day("fresh_rings", Edition.editionKey(TODAY, 0)), null,
                        GameKind.TRIAL),
                "a trial's layout board is a time");
        assertEquals("strokes", ScoresMenu.unitFor(GenBoards.day("fresh_tiny_golf", Edition.editionKey(TODAY, 0)), null,
                        GameKind.GOLF),
                "a golf layout board is strokes");
        assertEquals("stars", ScoresMenu.unitFor(GenBoards.week(THIS_WEEK), "points", GameKind.TRIAL),
                "the Star Chart counts stars");
        assertEquals("stars", ScoresMenu.unitFor(GenBoards.stars("fresh_tiny_golf", TODAY), null, GameKind.TRIAL),
                "and so does a day's stars board");
        assertEquals("14 stars", ScoresMenu.score("stars", 14), "stars read as stars");
        assertEquals("1 star", ScoresMenu.score("stars", 1), "one is singular");
        assertEquals("apples", ScoresMenu.unitFor("classic", "apples", GameKind.CABINET), "a cabinet's own unit");
    }
}
