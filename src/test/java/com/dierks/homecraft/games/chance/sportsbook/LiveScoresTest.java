package com.dierks.homecraft.games.chance.sportsbook;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.60.0, the live scores ({@link Scores}, {@link LiveScores}, {@link ScoreScreens}): Kalshi's real {@code /live_data}
 * replies, saved under {@code src/test/resources/kalshi/} on 2026-11-27 (06:35-06:37 UTC), read into scores and words;
 * the started games kept from a real {@code /events} reply; the games followed, read in turn and kept; what the TVs
 * and the ticker show.
 */
class LiveScoresTest {

    /** When the college basketball reply was saved: Baylor at Gonzaga and Texas Tech at Oregon were in their 2nd half. */
    static final long NOW = Instant.parse("2026-11-27T06:37:00Z").toEpochMilli();
    static final long HOUR = 3_600_000L;

    private static String fixture(String name) {
        try (InputStream in = LiveScoresTest.class.getResourceAsStream("/kalshi/" + name)) {
            assertNotNull(in, name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- reading a score ---------------------------------------------------------------------------------

    @Test
    void aFinishedHockeyGameReadsAsItsFinalScore() {
        Scores.Live l = Scores.parse(fixture("live_hockey_final.json"));
        assertEquals(6, l.away());
        assertEquals(3, l.home());
        assertEquals(3, l.period());
        assertEquals(Scores.Phase.FINAL, l.phase(), "widget finished, status closed");
        assertFalse(l.overtime(), "three periods: no overtime");
        assertEquals("Final", l.when());
        assertEquals("Final: CGY 6  PIT 3", Scores.line("CGY", "PIT", l));
        assertEquals("Final CGY 6-3 PIT", Scores.ticker("CGY", "PIT", l));
    }

    @Test
    void aCollegeGameInItsSecondHalfReadsWithItsClock() {
        Scores.Live l = Scores.parse(fixture("live_basketball_half.json"));
        assertEquals(Scores.Phase.LIVE, l.phase());
        assertEquals("2nd half 5:55", l.when(), "halves, not quarters; the clock's leading 0 is dropped");
        assertEquals("BAY 47  GONZ 61 · 2nd half 5:55", Scores.line("BAY", "GONZ", l));
        assertEquals("BAY 47-61 GONZ 2nd half 5:55", Scores.ticker("BAY", "GONZ", l));
        Scores.Live other = Scores.parse(fixture("live_basketball_half2.json"));
        assertEquals("TTU 49  ORE 54 · 2nd half 7:22", Scores.line("TTU", "ORE", other));
    }

    @Test
    void aFootballGameReadsItsQuarterAndClockFieldsAndItsTeamIdWinnerIsIgnored() {
        Scores.Live l = Scores.parse(fixture("live_football_final.json"));
        assertEquals(39, l.away());
        assertEquals(35, l.home());
        assertEquals(4, l.period(), "football's period is its quarter field");
        assertEquals(Scores.Phase.FINAL, l.phase());
        assertEquals("Final: CHI 39  DET 35", Scores.line("CHI", "DET", l));
    }

    @Test
    void aGameNotStartedHasNoScoreToShow() {
        Scores.Live l = Scores.parse(fixture("live_basketball_scheduled.json"));
        assertEquals(Scores.Phase.UPCOMING, l.phase());
        assertEquals("", l.when());
        assertEquals("ATL at BOS", Scores.line("ATL", "BOS", l));
    }

    @Test
    void anErrorOrNonsenseIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> Scores.parse(fixture("error_too_many.json")));
        assertThrows(IllegalArgumentException.class, () -> Scores.parse("{\"live_datas\":null}"));
        assertThrows(IllegalArgumentException.class, () -> Scores.parse("<html>"));
    }

    @Test
    void theClockReadsAsAPersonWouldSayIt() {
        assertEquals("Q4 2:31", live(98, 101, 4, "quarter", "02:31", "inprogress", "basketball_game").when());
        assertEquals("Halftime", live(50, 48, 2, "quarter", "00:00", "inprogress", "basketball_game").when());
        assertEquals("Halftime", live(30, 28, 1, "half", "0:00", "inprogress", "basketball_game").when());
        assertEquals("End of Q3", live(70, 72, 3, "quarter", "00:00", "inprogress", "basketball_game").when());
        assertEquals("OT 3:10", live(100, 100, 5, "quarter", "03:10", "inprogress", "basketball_game").when());
        assertEquals("2OT 1:00", live(110, 110, 6, "quarter", "01:00", "inprogress", "basketball_game").when());
        assertEquals("P2 12:01", live(1, 0, 2, "", "12:01", "in_progress", "hockey_match").when());
        assertEquals("End of P1", live(1, 0, 1, "", "00:00", "inprogress", "hockey_match").when());
        assertEquals("Q3 8:00", live(14, 10, 3, "", "08:00", "inprogress", "football_game").when(),
                "football has no period type: quarters");
        assertEquals("Final/OT", live(3, 2, 4, "", "00:00", "closed", "hockey_match").when());
        assertEquals("Final/OT: CGY 3  PIT 2", Scores.line("CGY", "PIT",
                live(3, 2, 4, "", "00:00", "closed", "hockey_match")));
        assertEquals(Scores.Phase.LIVE, live(3, 0, 2, "", "10:00", "scheduled", "hockey_match").phase(),
                "a stale \"scheduled\" with points on the board is under way");
    }

    private static Scores.Live live(int a, int h, int p, String type, String clock, String status, String kind) {
        return new Scores.Live(a, h, p, type, clock, status, status.equals("closed") ? "finished" : "", kind, "");
    }

    @Test
    void theTwoSidesAreReadFromTheEventVisitorsFirst() {
        assertArrayEquals(new String[]{"MIA", "NYK"}, Scores.teams("MIA vs NYK (Nov 27)", "Miami vs New York"));
        assertArrayEquals(new String[]{"Calgary", "Pittsburgh"}, Scores.teams("", "Calgary at Pittsburgh"));
        assertArrayEquals(new String[]{"Texas A&M", "LSU"}, Scores.teams(null, "Texas A&M vs LSU"));
        assertArrayEquals(new String[]{"", ""}, Scores.teams("", "Who wins?"));
        assertEquals("/live_data/hockey_match/milestone/1f18da45-07de-59a3-9fcb-0a15b56fbe0a",
                Scores.path("hockey_match", "1f18da45-07de-59a3-9fcb-0a15b56fbe0a"));
    }

    // ---- the games under way, from the board's own reading --------------------------------------------------

    @Test
    void theBoardsReadingKeepsTheGamesUnderWayWithTheirMilestones() {
        Kalshi.Page p = Kalshi.events(fixture("events_ncaamb_live.json"), "ncaamb", NOW, NOW + 24 * HOUR,
                NOW - LiveScores.KEEP_MS);
        assertEquals(2, p.started().size(), "Baylor at Gonzaga and Texas Tech at Oregon started at 4:30");
        Board.Game bay = p.started().stream().filter(g -> g.event().endsWith("BAYGONZ")).findFirst().orElseThrow();
        assertEquals("0552e355-1f58-5e4c-9150-6981bf25c8c5", bay.milestone());
        assertEquals("basketball_game", bay.kind());
        assertEquals("BAY", bay.away());
        assertEquals("GONZ", bay.home());
        for (Board.Game g : p.games()) {
            assertTrue(g.startsAt() > NOW, "the board itself still holds only games to come: " + g.title());
        }
        assertTrue(Kalshi.events(fixture("events_ncaamb_live.json"), "ncaamb", NOW, NOW + 24 * HOUR).started()
                .isEmpty(), "asked without a since: none kept");

        Board kept = Board.fromJson(new Board(NOW, p.started()).toJson());
        assertEquals(bay, kept.games().stream().filter(g -> g.event().equals(bay.event())).findFirst().orElseThrow(),
                "the milestone and short names are kept with the board");
        Board old = Board.fromJson(new Board(NOW, List.of(new Board.Game("E", "nba", "A vs B", NOW, "", List.of())))
                .toJson());
        assertEquals("", old.games().get(0).milestone(), "a game kept before 0.60.0 reads with none");
    }

    // ---- following games ------------------------------------------------------------------------------------

    private static Board.Game game(String event, long start) {
        return new Board.Game(event, "nba", event + " vs X", start, "", List.of(), "ms-" + event, "basketball_game",
                event, "X");
    }

    @Test
    void startedGamesAreFollowedOpenPicksFirstAndTheLeastLatelyReadNext() {
        LiveScores s = new LiveScores();
        s.seen(List.of(game("A", NOW - HOUR), game("B", NOW - 2 * HOUR), game("C", NOW + HOUR),
                game("OLD", NOW - 13 * HOUR)), NOW);
        assertEquals(2, s.size(), "C hasn't started and OLD is over 12 hours old");
        assertEquals(List.of("B", "A"), events(s.due(NOW, 16, Set.of())), "earliest started first");
        assertEquals(List.of("A"), events(s.due(NOW, 1, Set.of("A"))), "a game with an open pick goes first");
        s.read("B", new Scores.Live(10, 8, 1, "quarter", "05:00", "inprogress", "live", "basketball_game", ""), NOW);
        assertEquals(List.of("A", "B"), events(s.due(NOW + 60_000, 16, Set.of())), "then the one read longest ago");
        assertTrue(s.read("A", Scores.parse(fixture("live_hockey_final.json")), NOW), "first read as over");
        assertFalse(s.read("A", Scores.parse(fixture("live_hockey_final.json")), NOW), "only the first time");
        assertEquals(List.of("B"), events(s.due(NOW, 16, Set.of())), "an ended game is never read again");
        assertEquals("Final: A 6  X 3", s.finalFor("A-A"), "found by its market's ticker");
        assertNull(s.finalFor("B-B"), "not over yet");
        assertNull(s.finalFor("Z-Z"));
        assertEquals(List.of("B", "A"), shownEvents(s), "the game under way first, then the one over");

        LiveScores back = LiveScores.fromJson(s.toJson());
        assertEquals(shownEvents(s), shownEvents(back), "kept for a restart");
        assertEquals("Final: A 6  X 3", back.finalFor("A-A"));
        assertFalse(s.prune(NOW + 9 * HOUR));
        assertTrue(s.prune(NOW + 10 * HOUR + HOUR / 2), "B is 12 and a half hours past its start");
        assertEquals(List.of("A"), shownEvents(s));
        assertEquals(0, LiveScores.fromJson("nonsense").size());
    }

    private static List<String> events(List<LiveScores.Game> games) {
        List<String> out = new ArrayList<>();
        for (LiveScores.Game g : games) {
            out.add(g.event());
        }
        return out;
    }

    private static List<String> shownEvents(LiveScores s) {
        List<String> out = new ArrayList<>();
        for (LiveScores.Entry e : s.shown()) {
            out.add(e.game().event());
        }
        return out;
    }

    @Test
    void aRoundOfScoresSkipsAGameThatFailsAndStopsWhenKalshiWantsARest() throws Exception {
        Map<String, String[]> games = new LinkedHashMap<>();
        games.put("A", new String[]{"hockey_match", "a"});
        games.put("B", new String[]{"basketball_game", "b"});
        games.put("C", new String[]{"basketball_game", "c"});
        List<String> asked = new ArrayList<>();
        KalshiClient.ScoresRead r = KalshiClient.scores(path -> {
            asked.add(path);
            if (path.endsWith("/a")) {
                return fixture("live_hockey_final.json");
            }
            if (path.endsWith("/b")) {
                throw new IOException("HTTP 500");
            }
            return fixture("live_basketball_half.json");
        }, ms -> { }, games);
        assertEquals(Set.of("A", "C"), r.scores().keySet(), "B couldn't be read; C still was");
        assertEquals("HTTP 500", r.error());
        assertEquals(3, asked.size());

        asked.clear();
        KalshiClient.ScoresRead rest = KalshiClient.scores(path -> {
            asked.add(path);
            return path.endsWith("/a") ? fixture("live_hockey_final.json") : fixture("error_too_many.json");
        }, ms -> { }, games);
        assertEquals(Set.of("A"), rest.scores().keySet());
        assertEquals(1 + 1 + KalshiClient.RETRIES, asked.size(), "B asked again twice, then the round ends");
    }

    // ---- the TVs and the ticker ------------------------------------------------------------------------------

    private static ScoreScreens.Item item(String league, String a, String h, int ap, int hp, String status) {
        return new ScoreScreens.Item(league, a, h, new Scores.Live(ap, hp, 4, "quarter", "02:31", status,
                status.equals("closed") ? "finished" : "live", "basketball_game", ""));
    }

    @Test
    void aTvShowsOneGameBigAndTurnsToTheNextEveryEightSeconds() {
        List<ScoreScreens.Item> items = List.of(item("NBA", "MIL", "TOR", 98, 101, "inprogress"),
                item("NHL", "CGY", "PIT", 6, 3, "closed"), item("NBA", "MIA", "NYK", 50, 40, "inprogress"));
        List<ScoreScreens.Line> first = ScoreScreens.tv(items, List.of(), 0, 0);
        assertEquals("NBA", first.get(0).text());
        assertEquals(ScoreScreens.Kind.HEAD, first.get(0).kind());
        assertEquals("Q4 2:31", first.get(1).text(), "the clock on a line of its own: it fits a small screen");
        assertEquals("MIL  98", first.get(2).text());
        assertEquals("TOR  101", first.get(3).text());
        assertEquals(ScoreScreens.Kind.TEAM, first.get(3).kind(), "no winner while it's being played");
        assertEquals("1 of 3", first.get(4).text());
        assertEquals(first, ScoreScreens.tv(items, List.of(), ScoreScreens.TV_SECONDS - 1, 0), "still the same game");
        List<ScoreScreens.Line> next = ScoreScreens.tv(items, List.of(), ScoreScreens.TV_SECONDS, 0);
        assertEquals("NHL", next.get(0).text());
        assertEquals("Final", next.get(1).text());
        assertEquals(ScoreScreens.Kind.LEAD, next.get(2).kind(), "Calgary won: green");
        assertEquals(ScoreScreens.Kind.TEAM, next.get(3).kind());
        assertEquals("MIL  98", ScoreScreens.tv(items, List.of(), 3 * ScoreScreens.TV_SECONDS, 0).get(2).text(),
                "round and round");
        assertEquals("CGY  6", ScoreScreens.tv(items, List.of(), 0, 1).get(2).text(),
                "the next TV starts one game on");
        assertEquals("MIA  50", ScoreScreens.tv(items, List.of(), 0, 2).get(2).text());
        assertEquals(4, ScoreScreens.tv(items.subList(0, 1), List.of(), 0, 0).size(), "one game: no \"1 of 1\"");
    }

    @Test
    void withNoGameUnderWayATvListsTheNextOnesOrSaysThereAreNone() {
        List<ScoreScreens.Upcoming> next = List.of(new ScoreScreens.Upcoming("NBA", "ATL", "BOS", "7:00 PM"),
                new ScoreScreens.Upcoming("NBA", "LAL", "SAS", "8:30 PM"),
                new ScoreScreens.Upcoming("NHL", "PIT", "BUF", "7:00 PM"),
                new ScoreScreens.Upcoming("NHL", "SEA", "EDM", "7:00 PM"));
        List<ScoreScreens.Line> page = ScoreScreens.tv(List.of(), next, 0, 0);
        assertEquals("Next up", page.get(0).text());
        assertEquals("ATL at BOS", page.get(1).text());
        assertEquals("NBA · 7:00 PM", page.get(2).text());
        assertEquals(1 + 2 * ScoreScreens.TV_NEXT, page.size());
        assertEquals("PIT at BUF", ScoreScreens.tv(List.of(), next, ScoreScreens.TV_SECONDS, 0).get(1).text(),
                "then the next page");
        assertEquals(List.of(new ScoreScreens.Line(ScoreScreens.NONE, ScoreScreens.Kind.HEAD)),
                ScoreScreens.tv(List.of(), List.of(), 5, 0));
        assertEquals("No games on right now", ScoreScreens.tickerText(List.of(), List.of()));
        assertEquals("Next up: NBA ATL at BOS 7:00 PM  ·  NBA LAL at SAS 8:30 PM",
                ScoreScreens.tickerText(List.of(), next.subList(0, 2)));
    }

    @Test
    void theTickerRunsEveryScoreRoundAndRoundAFewLettersAtATime() {
        String text = ScoreScreens.tickerText(List.of(item("NBA", "MIL", "TOR", 98, 101, "inprogress"),
                item("NHL", "CGY", "PIT", 6, 3, "closed")), List.of());
        assertEquals("NBA MIL 98-101 TOR Q4 2:31  ·  NHL Final CGY 6-3 PIT", text);
        assertEquals("NBA MIL 98", ScoreScreens.window(text, 0, 10));
        assertEquals("A MIL 98-1", ScoreScreens.window(text, ScoreScreens.TICKER_STEP, 10), "moved two letters");
        int loop = text.length() + ScoreScreens.TICKER_GAP.length();
        assertEquals(ScoreScreens.window(text, 0, 10), ScoreScreens.window(text, loop, 10), "round and round");
        assertEquals("3 PIT" + ScoreScreens.TICKER_GAP.substring(0, 5),
                ScoreScreens.window(text, text.length() - 5, 10), "its end runs into the gap");
        assertTrue(ScoreScreens.window(text, loop - 3, 10).endsWith("NBA MIL"), "and then its start again");
        assertEquals("short", ScoreScreens.window("short", 7, 10), "a line that fits stands still");
        assertFalse(ScoreScreens.scrolls("short", 10));
        assertEquals(4, ScoreScreens.TICKER_TICKS, "the ticker moves every 4 ticks: 5 times a second, no more");
        assertEquals("College BB", Sportsbook.screenTab(SportsbookSettings.league("ncaamb")), "short on a small screen");
        assertEquals("NBA", Sportsbook.screenTab(SportsbookSettings.league("nba")));
    }
}
