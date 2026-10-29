package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.storage.GamesDao;
import com.dierks.homecraft.storage.TokenDao;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * My stats (0.38.3): a player's own personal bests and places, counted runs this week, best round of golf and the
 * tokens won from games this week (never from games of chance, never a Cup entry or its refund); nine lines on the
 * wall, "Only you can see this" at the bottom.
 */
class StatsCardTest {

    private static final UUID ME = UUID.randomUUID();

    private static TokenDao.SourceTotal earned(TokenService.Source s, long earned, long spent) {
        return new TokenDao.SourceTotal(ME, s.name(), earned, spent);
    }

    @Test
    void tokensFromGamesAreTheSkillGamesCoursesAndCupPrizesOnly() {
        assertTrue(StatsCard.GAME_SOURCES.contains("GAMES_SNAKE") && StatsCard.GAME_SOURCES.contains("GAMES_PARKOUR")
                && StatsCard.GAME_SOURCES.contains("GAMES_CUP_PRIZE") && StatsCard.GAME_SOURCES.contains("GAMES_DAILY")
                && StatsCard.GAME_SOURCES.contains("GAMES_FLOORS"), StatsCard.GAME_SOURCES.toString());
        assertFalse(StatsCard.GAME_SOURCES.contains("GAMES_CUP_ENTRY"), "a Cup entry is the player's own tokens");
        assertFalse(StatsCard.GAME_SOURCES.contains("GAMES_CUP_REFUND"), "and so is its refund");
        for (String s : StatsCard.GAME_SOURCES) {
            assertTrue(s.startsWith("GAMES_"), "never a game of chance, a crate or the ticket: " + s);
        }
        ClubData.Snapshot snap = new ClubData.Snapshot(List.of(), List.of(), 0, -1, null, 0, Set.of(ME));
        ClubData.Personal p = new ClubData.Personal(List.of(), List.of(), Map.of(), List.of(
                earned(TokenService.Source.GAMES_SNAKE, 12, 0), earned(TokenService.Source.GAMES_BOAT, 20, 0),
                earned(TokenService.Source.GAMES_CUP_PRIZE, 30, 0), earned(TokenService.Source.GAMES_CUP_ENTRY, 0, 10),
                earned(TokenService.Source.GAMES_CUP_REFUND, 10, 0), earned(TokenService.Source.ARCADE_SLOTS, 50, 40),
                earned(TokenService.Source.QUEST, 25, 0), earned(TokenService.Source.LOTTO, 100, 5)));
        assertEquals(62, StatsCard.build(snap, p).tokensWon(), "12 + 20 + 30: the games' winnings only");
    }

    @Test
    void bestsWithTheirPlacesRunsThisWeekAndTheBestRound() {
        ClubData.Snapshot snap = new ClubData.Snapshot(List.of(
                new ClubData.CourseBoards("sky", "Sky Rings", "course:sky", "week:sky:9", true),
                new ClubData.CourseBoards("river", "River Run", "course:river", "week:river:9", true),
                new ClubData.CourseBoards("fresh", "Hard Parkour", "gfresh:fresh:7:9", "gfresh:fresh:7:9", true),
                new ClubData.CourseBoards("old", "Old Fresh", "gfresh:old:7:2", "gfresh:old:7:2", false),
                new ClubData.CourseBoards("ice", "Ice Boat", "course:ice", "week:ice:9", true)),
                List.of(new ClubData.GolfBoards("tiny", "Tiny Golf", "golf:tiny", 26),
                        new ClubData.GolfBoards("big", "Big Golf", "golf:big", 40)), 0, -1, null, 0, Set.of(ME));
        ClubData.Personal p = new ClubData.Personal(List.of(
                new GamesDao.PlayerBoard("course:sky", 62_400, 1, 9),
                new GamesDao.PlayerBoard("week:sky:9", 64_000, 2, 4),
                new GamesDao.PlayerBoard("week:sky:8", 60_000, 1, 30),
                new GamesDao.PlayerBoard("course:river", 70_000, 1, 2),
                new GamesDao.PlayerBoard("gfresh:fresh:7:9", 40_000, 3, 3),
                new GamesDao.PlayerBoard("gfresh:old:7:2", 41_000, 3, 7)),
                List.of(new GamesDao.PlayerBoard("golf:tiny", 27, 5, 1), new GamesDao.PlayerBoard("golf:big", 39, 4, 1),
                        new GamesDao.PlayerBoard("golf:gone", 10, 1, 1)),
                Map.of("course:sky", 2, "course:river", 1, "gfresh:fresh:7:9", 3), List.of());
        StatsCard.Mine m = StatsCard.build(snap, p);
        assertEquals(List.of(new StatsCard.Best("River Run", 70_000, 1), new StatsCard.Best("Sky Rings", 62_400, 2),
                new StatsCard.Best("Hard Parkour", 40_000, 3), new StatsCard.Best("Old Fresh", 41_000, 0)), m.bests(),
                "a best on each course played, best place first (a place not read: last)");
        assertEquals(4 + 3, m.runsThisWeek(), "this week's boards only: not last week's, not a set from before it");
        assertEquals(new FameBoard.GolfRound("Big Golf", null, 39, -1), m.bestGolf(),
                "the best round against par (-1 beats +1); a course that isn't open now isn't counted");
    }

    @Test
    void theWallCardIsNineLinesOnlyYouSee() {
        StatsCard.Mine m = new StatsCard.Mine(List.of(new StatsCard.Best("River Run", 70_000, 1),
                new StatsCard.Best("Sky Rings", 62_400, 2), new StatsCard.Best("A", 1_000, 3),
                new StatsCard.Best("B", 2_000, 4), new StatsCard.Best("C", 3_000, 5)), 12,
                new FameBoard.GolfRound("Tiny Golf", null, 24, -2), 45);
        String card = StatsCard.board("Sam", m);
        String[] lines = card.split("\n");
        assertEquals(WallBoards.LINES, lines.length, card);
        assertEquals("&d&lMy stats &7- Sam", lines[0]);
        assertEquals("&7Runs this week: &f12", lines[1]);
        assertEquals("&7Tokens from games this week: &f45", lines[2]);
        assertEquals("&7Best golf: &fTiny Golf 24 (-2)", lines[3]);
        assertEquals("&fRiver Run &71:10.0 &e#1", lines[4], "best place first");
        assertEquals(ClubBoardText.ONLY_YOU, lines[8], "only they see it");
        assertFalse(card.contains("&fC "), "four bests on the wall (the screen has them all)");
        String none = StatsCard.board("Sam", new StatsCard.Mine(List.of(), 0, null, 0));
        assertTrue(none.contains("&7Best golf: &fnone yet") && none.contains(ClubBoardText.NO_BESTS), none);
        String counting = StatsCard.board("Sam", null);
        assertEquals("&d&lMy stats &7- Sam\n" + ClubBoardText.COUNTING + "\n" + ClubBoardText.ONLY_YOU, counting,
                "not read yet");
        assertNull(StatsCard.build(null, null), "nothing read: nothing");
    }
}
