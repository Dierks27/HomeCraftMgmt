package com.dierks.homecraft.display;

import com.dierks.homecraft.games.gen.api.GenBoards;
import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.GenTag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hub's leaderboard displays, {@code @board:<id>[:<board>]} (EXTRAS E3), with no server.
 *
 * <p>Pinned here: the target parses in any case, with or without a board, and nothing else is
 * one; a cabinet shows the board it publishes (or one it names), a course and a golf course their
 * all-time board, a Fresh course its current set's board — following the next set as soon as it
 * is up — and a Classic the board of the course it holds; a game of chance, an id nothing has, a
 * malformed target and a second board asked of a course are refused with a message; the lines
 * read "1. Sam 0:42.1" in each unit (times m:ss.t, strokes, points), ties share a rank, a sign
 * holds the title and the best three in 15 characters a line, and an empty board says so.
 */
class BoardDisplayTest {

    /** A world with Snake, Creeper Sweeper, Ore Slots, River Run, Meadow Links and Fresh Courses. */
    private static final class World implements BoardDisplay.Lookup {
        final Map<String, GenTag> live = new HashMap<>();
        int cadence = 7;

        @Override
        public GenTag liveTag(String slotId) {
            return live.get(slotId);
        }

        @Override
        public int cadence() {
            return cadence;
        }

        @Override
        public BoardDisplay.Cabinet cabinet(String id) {
            return switch (id) {
                case "snake" -> new BoardDisplay.Cabinet("Snake", "classic", "apples", false);
                case "creeper_sweeper" -> new BoardDisplay.Cabinet("Creeper Sweeper", "normal", "ms", true);
                default -> null;
            };
        }

        @Override
        public boolean chance(String id) {
            return id.equals("ore_slots");
        }

        @Override
        public String trialCourse(String id) {
            return id.equals("river_run") ? "River Run" : null;
        }

        @Override
        public String golfCourse(String id) {
            return id.equals("meadow") ? "Meadow Links" : null;
        }
    }

    private static GenTag hard(long day) {
        return new GenTag("fresh_parkour_hard", "parkour", 1, day, 0, 1L, 'A', "abc", 1, 2, 3, List.of(), List.of(),
                1L, 7);
    }

    @Test
    void theTargetParsesInAnyCaseWithOrWithoutABoard() {
        assertEquals(new BoardDisplay.Target("snake", null), BoardDisplay.parse("@board:snake"), "a game");
        assertEquals(new BoardDisplay.Target("creeper_sweeper", "hard"), BoardDisplay.parse(" @BOARD:Creeper_Sweeper:Hard "),
                "any case, a named board, trimmed");
        assertEquals("@board:creeper_sweeper:hard", BoardDisplay.parse("@Board:creeper_sweeper:HARD").itemId(),
                "stored in one spelling");
        assertTrue(BoardDisplay.is("@board:x"), "a leaderboard");
        assertFalse(BoardDisplay.is("@news") || BoardDisplay.is("wheat") || BoardDisplay.is(null), "not a leaderboard");
        for (String bad : List.of("@board:", "@board:snake:", "@board:sn ake", "@board:snake:a:b", "@board:<x>",
                "board:snake")) {
            assertNull(BoardDisplay.parse(bad), "not a target: " + bad);
        }
    }

    @Test
    void eachKindShowsItsOwnBoard() {
        World w = new World();
        BoardDisplay.Resolved snake = BoardDisplay.resolve("@board:snake", w).resolved();
        assertEquals(new BoardDisplay.Resolved("snake", "classic", false, "apples", "Snake", "snake"), snake,
                "a cabinet: the board it publishes, in its unit");
        BoardDisplay.Resolved hardSweeper = BoardDisplay.resolve("@board:creeper_sweeper:hard", w).resolved();
        assertEquals("hard", hardSweeper.board(), "or one it names");
        assertEquals("Creeper Sweeper - Hard", hardSweeper.title(), "and says which");
        assertEquals(new BoardDisplay.Resolved("trials", "course:river_run", true, "ms", "River Run", "river_run"),
                BoardDisplay.resolve("@board:river_run", w).resolved(), "a course: its all-time board");
        assertEquals(new BoardDisplay.Resolved("golf", "golf:meadow", true, "strokes", "Meadow Links", "meadow"),
                BoardDisplay.resolve("@board:meadow", w).resolved(), "a golf course: its all-time board");
    }

    @Test
    void aFreshCourseShowsItsCurrentSetAndFollowsTheNext() {
        World w = new World();
        BoardDisplay.Resolved none = BoardDisplay.resolve("@board:fresh_parkour_hard", w).resolved();
        assertNull(none.board(), "no set up yet: nothing to read, but it can be bound");
        assertEquals("Hard Parkour - this week", none.title(), "titled for the cadence");
        w.live.put("fresh_parkour_hard", hard(20_724));
        assertEquals(GenBoards.day(hard(20_724)), BoardDisplay.resolve("@board:fresh_parkour_hard", w).resolved()
                .board(), "the set up now");
        w.live.put("fresh_parkour_hard", hard(20_731));
        assertEquals(GenBoards.day(hard(20_731)), BoardDisplay.resolve("@board:fresh_parkour_hard", w).resolved()
                .board(), "the next set, as soon as it is up: the display follows it");
        w.cadence = 1;
        assertEquals("Golf of the Day - today", BoardDisplay.resolve("@board:fresh_golf", w).resolved().title(),
                "the words follow the cadence");
        w.live.put("fresh_classic_parkour", hard(20_724).withRecall(new GenTag.Recall("fresh_classic_parkour", 1L,
                20_740)));
        BoardDisplay.Resolved classic = BoardDisplay.resolve("@board:fresh_classic_parkour", w).resolved();
        assertEquals(GenBoards.day(hard(20_724)), classic.board(), "a Classic shows the course it holds, old records");
        assertEquals(GenCopy.classicName("Hard Parkour", 7, 20_724, false), classic.title(), "named as the classic");
        assertEquals("fresh_classic_parkour", classic.playId(), "played through its Classics slot");
    }

    @Test
    void badTargetsAreRefusedWithAMessage() {
        World w = new World();
        assertEquals("Games of chance have no leaderboard.", BoardDisplay.resolve("@board:ore_slots", w).error(),
                "never a game of chance");
        assertEquals("No game or course is called 'nothing'.", BoardDisplay.resolve("@board:nothing", w).error(),
                "an id nothing has");
        assertTrue(BoardDisplay.resolve("@board:", w).error().startsWith("A leaderboard is @board:<id>"),
                "a malformed one says how it goes");
        assertEquals("River Run has one board: use @board:river_run.",
                BoardDisplay.resolve("@board:river_run:week", w).error(), "a second board of a course");
        assertTrue(BoardDisplay.resolve("@board:fresh_parkour:7", w).error().contains("current set"),
                "and of a Fresh course");
        for (String line : List.of(BoardDisplay.resolve("@board:ore_slots", w).error(),
                BoardDisplay.resolve("@board:nothing", w).error())) {
            assertEquals(List.of(), GenCopy.copyProblems(line), "kid-safe: " + line);
        }
    }

    @Test
    void theLinesReadInTheirUnitsAndTiesShareARank() {
        List<BoardDisplay.Row> rows = BoardDisplay.ranked(List.of("Sam", "Alex", "Jo"), List.of(42_100L, 42_100L,
                45_000L));
        assertEquals(List.of(1, 1, 3), rows.stream().map(BoardDisplay.Row::rank).toList(), "a tie shares a rank");
        assertEquals(List.of("&6&lHard Parkour - this week", "&61. &fSam &70:42.1", "&61. &fAlex &70:42.1",
                "&e3. &fJo &70:45.0", "&8/hcm play fresh_parkour_hard"),
                BoardDisplay.screen("Hard Parkour - this week", rows, "ms", "fresh_parkour_hard"),
                "the title, the rows, and how to play it");
        assertEquals("0:42.1", BoardDisplay.value("ms", 42_199), "a time is m:ss.t, tenths floored");
        assertEquals("1:05.0", BoardDisplay.value("ms", 65_000), "a minute and more");
        assertEquals("27 strokes", BoardDisplay.value("strokes", 27), "golf in strokes");
        assertEquals("30 points", BoardDisplay.value("points", 30), "points");
        assertEquals("1 apple", BoardDisplay.value("apples", 1), "one is singular");
        List<BoardDisplay.Row> six = BoardDisplay.ranked(List.of("a", "b", "c", "d", "e", "f"),
                List.of(1L, 2L, 3L, 4L, 5L, 6L));
        assertEquals(7, BoardDisplay.screen("T", six, "points", "x").size(), "a title, five rows and the footer");
    }

    @Test
    void aSignHoldsTheTitleAndTheBestThreeIn15Characters() {
        List<BoardDisplay.Row> rows = BoardDisplay.ranked(List.of("Sam", "Maximilian_The_Great", "Jo", "Kim"),
                List.of(42_100L, 43_000L, 45_000L, 46_000L));
        List<String> sign = BoardDisplay.sign("Hard Parkour - this week", rows, "ms");
        assertEquals("Hard Parkour -", sign.get(0), "the title, cut to fit");
        assertEquals("1 Sam 0:42.1", sign.get(1), "a row: rank, name, time");
        assertEquals(4, sign.size(), "four lines");
        for (String line : sign) {
            assertTrue(line.length() <= BoardDisplay.SIGN_CHARS, "fits a sign: '" + line + "'");
        }
        assertTrue(sign.get(2).endsWith("0:43.0") && sign.get(2).startsWith("2 Max"),
                "a long name is cut, never the time: " + sign.get(2));
        assertEquals("1 Sam 27", BoardDisplay.sign("Meadow Links", BoardDisplay.ranked(List.of("Sam"), List.of(27L)),
                "strokes").get(1), "golf: the bare strokes");
    }

    @Test
    void anEmptyBoardSaysSo() {
        assertEquals(List.of("&6&lRiver Run", "&7No times yet - be the first!", "&8/hcm play river_run"),
                BoardDisplay.screen("River Run", List.of(), "ms", "river_run"), "a time board");
        assertEquals("&7No scores yet - be the first!", BoardDisplay.screen("Snake", List.of(), "apples", "snake")
                .get(1), "anything else");
        assertEquals(List.of("River Run", "No times yet", "be the first!", ""), BoardDisplay.sign("River Run",
                List.of(), "ms"), "a sign, in two lines");
        for (String line : List.of(BoardDisplay.EMPTY, BoardDisplay.EMPTY_SCORES)) {
            assertEquals(List.of(), GenCopy.copyProblems(line), "kid-safe: " + line);
        }
    }
}
