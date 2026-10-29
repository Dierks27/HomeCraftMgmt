package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.Scores;
import com.dierks.homecraft.games.cup.CupEntry;
import com.dierks.homecraft.games.cup.CupKey;
import com.dierks.homecraft.storage.Database;
import com.dierks.homecraft.storage.GamesDao;
import com.dierks.homecraft.storage.TokenDao;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the boards read (0.38.3), on the real tables: a hand-built course's fastest run this week is its week
 * board's best (the week the boards already use); a Fresh course's and a golf course's this-week best is the best
 * set since the week began (a best from last week isn't); records are the boards' bests; the Weekly Cup's leader is
 * this Cup week's fastest entry with a time; each viewer gets their own boards, places and ledger totals, and only
 * viewers are read for.
 */
class ClubDataTest {

    private static final long WEEK_START = 1_790_000_000_000L;
    private final UUID sam = UUID.randomUUID();
    private final UUID lee = UUID.randomUUID();
    private final UUID ana = UUID.randomUUID();

    /** The live DAOs, as ClubBoards wires them, over a fake Cup table. */
    private static ClubData.Db db(GamesDao games, TokenDao tokens, Map<CupKey, List<CupEntry>> cups) {
        return new ClubData.Db() {
            @Override
            public List<GamesDao.ScoreRow> top(String game, String board, int limit) throws SQLException {
                return games.top(game, board, true, limit);
            }

            @Override
            public List<CupKey> openCups() {
                return new ArrayList<>(cups.keySet());
            }

            @Override
            public List<CupEntry> cupEntries(CupKey key) {
                return cups.getOrDefault(key, List.of());
            }

            @Override
            public List<GamesDao.PlayerBoard> boardsOf(UUID player, String game) throws SQLException {
                return games.boardsOf(player, game);
            }

            @Override
            public int rank(UUID player, String game, String board) throws SQLException {
                return games.rank(player, game, board, true);
            }

            @Override
            public List<TokenDao.SourceTotal> totals(long since, UUID player) throws SQLException {
                return tokens.totals(since, player);
            }
        };
    }

    @Test
    void theWeeksBestTheRecordsTheCupAndEachViewersOwn() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            Database database = Database.open(conn, Logger.getAnonymousLogger());
            GamesDao games = new GamesDao(database);
            TokenDao tokens = new TokenDao(database);
            String week = Scores.week("sky", 20_700);
            games.submit(sam, "trials", Scores.course("sky"), 58_000, true, WEEK_START - 86_400_000L);
            games.submit(lee, "trials", Scores.course("sky"), 61_000, true, WEEK_START + 1_000);
            games.submit(lee, "trials", week, 61_000, true, WEEK_START + 1_000);
            String fresh = "gfresh:fresh_parkour:7:20700";
            games.submit(sam, "trials", fresh, 40_000, true, WEEK_START - 1); // last week: not this week's
            games.submit(lee, "trials", fresh, 43_000, true, WEEK_START + 5);
            games.submit(ana, "golf", Scores.golf("tiny"), 25, true, WEEK_START - 10);
            games.submit(sam, "golf", Scores.golf("tiny"), 27, true, WEEK_START + 10);
            tokens.change(sam, 12, "GAMES_SNAKE", "Snake", WEEK_START + 20);
            tokens.change(sam, 7, "GAMES_SNAKE", "Snake", WEEK_START - 20);

            Map<CupKey, List<CupEntry>> cups = new HashMap<>();
            cups.put(new CupKey("sky", 42), List.of(new CupEntry(sam, 10, 1, CupEntry.NO_TIME, 0),
                    new CupEntry(lee, 10, 2, 62_000, 500), new CupEntry(ana, 10, 3, 62_000, 400)));
            cups.put(new CupKey("sky", 41), List.of(new CupEntry(sam, 10, 1, 50_000, 1)));

            ClubData.Snapshot snap = new ClubData.Snapshot(List.of(
                    new ClubData.CourseBoards("sky", "Sky Rings", Scores.course("sky"), week, true),
                    new ClubData.CourseBoards("fresh", "Hard Parkour", fresh, fresh, false)),
                    List.of(new ClubData.GolfBoards("tiny", "Tiny Golf", Scores.golf("tiny"), 26)), WEEK_START, 42,
                    null, 0, Set.of(sam));
            ClubData.Raw raw = ClubData.read(snap, db(games, tokens, cups));

            assertEquals(lee, raw.week().get("sky").player(), "this week's board: Lee's run this week");
            assertEquals(sam, raw.records().get("sky").player(), "the record: Sam's, from before the week");
            assertEquals(lee, raw.week().get("fresh").player(), "a Fresh set: the best set since the week began");
            assertEquals(sam, raw.records().get("fresh").player(), "and its best ever");
            assertEquals(sam, raw.golfWeek().get("tiny").player(), "golf: this week's best round");
            assertEquals(ana, raw.golfRecords().get("tiny").player(), "golf's record");
            assertEquals(ana, raw.cups().get("sky").player(), "the Cup: a tie to whoever set it first");
            assertEquals(1, raw.cups().size(), "only this Cup week's");
            assertEquals(Set.of(sam), raw.personal().keySet(), "only the viewers are read for");
            ClubData.Personal mine = raw.personal().get(sam);
            assertEquals(2, mine.trials().size(), "Sam's course board and the Fresh set's: " + mine.trials());
            assertEquals(Map.of(Scores.course("sky"), 1, fresh, 1), mine.ranks(), "1st on both");
            assertEquals(12, mine.totals().get(0).earned(), "the ledger since the week began");

            StatsCard.Mine card = StatsCard.build(snap, mine);
            assertEquals(12, card.tokensWon(), "12 from Snake this week");
            assertEquals(0, card.runsThisWeek(), "no run on this week's boards (the old set isn't this week's)");
            assertEquals(new FameBoard.GolfRound("Tiny Golf", null, 27, 1), card.bestGolf(), "their round");
        }
    }

    @Test
    void nothingIsReadForAnOffCupAndALeaderNeedsATime() throws Exception {
        List<String> asked = new ArrayList<>();
        ClubData.Db none = new ClubData.Db() {
            @Override
            public List<GamesDao.ScoreRow> top(String game, String board, int limit) {
                asked.add(board);
                return List.of();
            }

            @Override
            public List<CupKey> openCups() {
                asked.add("cups");
                return List.of();
            }

            @Override
            public List<CupEntry> cupEntries(CupKey key) {
                return List.of();
            }

            @Override
            public List<GamesDao.PlayerBoard> boardsOf(UUID player, String game) {
                return List.of();
            }

            @Override
            public int rank(UUID player, String game, String board) {
                asked.add("rank");
                return 0;
            }

            @Override
            public List<TokenDao.SourceTotal> totals(long since, UUID player) {
                return List.of();
            }
        };
        ClubData.Raw raw = ClubData.read(new ClubData.Snapshot(List.of(), List.of(), 0, -1, null, 0, Set.of(sam)),
                none);
        assertFalse(asked.contains("cups"), "the Cup off: not read");
        assertFalse(asked.contains("rank"), "no course played: no place read");
        assertTrue(raw.week().isEmpty() && raw.cups().isEmpty(), "nothing");
        assertNull(ClubData.leader(List.of(new CupEntry(sam, 10, 1, CupEntry.NO_TIME, 0))), "no time yet: no leader");
        assertNull(ClubData.firstSince(List.of(new GamesDao.ScoreRow(sam, 1, 5)), 6), "older than the week");
    }

    /**
     * 0.38.4: with the family week's key in the snapshot the week's points board, its race wins and last week's
     * top three are read (higher is better), and each viewer's own points and place come from the same list; a
     * {@code Db} without {@code topPoints} (the default) reads nothing and no page follows; without the key
     * nothing is asked at all.
     */
    @Test
    void theFamilyWeekIsReadWithTheWeeksKey() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            Database database = Database.open(conn, Logger.getAnonymousLogger());
            GamesDao games = new GamesDao(database);
            TokenDao tokens = new TokenDao(database);
            games.addPoints(sam, ClubData.CLUB, FamilyWeekRules.boardOf(20_700), 9, WEEK_START + 1);
            games.addPoints(lee, ClubData.CLUB, FamilyWeekRules.boardOf(20_700), 14, WEEK_START + 2);
            games.addPoints(lee, ClubData.CLUB, FamilyWeekRules.winsBoard(20_700), 3, WEEK_START + 2);
            games.addPoints(ana, ClubData.CLUB, FamilyWeekRules.boardOf(20_693), 20, WEEK_START - 2);
            games.addPoints(sam, ClubData.CLUB, FamilyWeekRules.boardOf(20_693), 11, WEEK_START - 3);
            List<String> asked = new ArrayList<>();
            ClubData.Db db = new ClubData.Db() {
                @Override
                public List<GamesDao.ScoreRow> top(String game, String board, int limit) {
                    return List.of();
                }

                @Override
                public List<CupKey> openCups() {
                    return List.of();
                }

                @Override
                public List<CupEntry> cupEntries(CupKey key) {
                    return List.of();
                }

                @Override
                public List<GamesDao.PlayerBoard> boardsOf(UUID player, String game) {
                    return List.of();
                }

                @Override
                public int rank(UUID player, String game, String board) {
                    return 0;
                }

                @Override
                public List<TokenDao.SourceTotal> totals(long since, UUID player) throws SQLException {
                    return tokens.totals(since, player);
                }

                @Override
                public List<GamesDao.ScoreRow> topPoints(String game, String board, int limit) throws SQLException {
                    asked.add(board);
                    return games.top(game, board, false, limit);
                }
            };
            ClubData.Snapshot snap = new ClubData.Snapshot(List.of(), List.of(), WEEK_START, -1, null, 0, Set.of(sam),
                    20_700, 20_693);
            ClubData.Raw raw = ClubData.read(snap, db);
            assertEquals(List.of(lee, sam), raw.family().stream().map(GamesDao.ScoreRow::player).toList(),
                    "most points first");
            assertEquals(Map.of(lee, 3L), raw.wins(), "the wins");
            assertEquals(List.of(ana, sam), raw.lastWeek().stream().map(GamesDao.ScoreRow::player).toList(),
                    "last week's top");
            assertEquals(9, raw.personal().get(sam).weekPoints(), "Sam's points");
            assertEquals(2, raw.personal().get(sam).weekRank(), "and place");
            assertEquals(List.of("club:20700", "clubwins:20700", "club:20693"), asked, "three reads");

            ClubData.Raw plain = ClubData.read(new ClubData.Snapshot(List.of(), List.of(), WEEK_START, -1, null, 0,
                    Set.of(sam)), db);
            assertTrue(plain.family().isEmpty() && plain.lastWeek().isEmpty(), "no key: nothing read");
            assertEquals(3, asked.size(), "and nothing asked");
            assertEquals(0, plain.personal().get(sam).weekRank(), "no place");

            ClubData.Raw none = ClubData.read(snap, db(games, tokens, new HashMap<>()));
            assertTrue(none.family().isEmpty(), "a Db without topPoints reads nothing");
            List<String> pages = FameBoard.pages(FameBoard.fame(snap, none, id -> "x"));
            assertEquals(4, pages.size(), "the key is set: the page is there");
            assertTrue(pages.get(3).endsWith(ClubBoardText.NO_FAMILY), "and says no points yet");
        }
    }
}
