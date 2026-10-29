package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.cup.CupEntry;
import com.dierks.homecraft.games.cup.CupKey;
import com.dierks.homecraft.storage.GamesDao;
import com.dierks.homecraft.storage.TokenDao;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What the Clubhouse's boards read from the database (0.38.3), and how: the owner's rule is every database read
 * off the main thread, and cached. So a read has two halves.
 *
 * <ul>
 *   <li>A {@link Snapshot}, made on the main thread from what is already in memory: the open courses and golf
 *       courses with the boards their times go on, the week's first instant, the Weekly Cup's week, today's pick
 *       and its bonus, and whose stats to read.</li>
 *   <li>{@link #read}, on another thread, over the {@link Db} alone (the DAOs: no server, no config): each course's
 *       fastest run this week and its record, each golf course's best round this week and its record, each Weekly
 *       Cup's leader this week, and for each viewer their boards, their place on each course they played and their
 *       token ledger's totals since the week began. The DAOs share the plugin's one connection and lock it for each
 *       statement, so the main thread waits at most for one small query.</li>
 * </ul>
 *
 * <p><b>The week.</b> Each board turns with its own week, as it always has: a hand-built course's week board turns
 * with the weekly quests' {@code week_starts_on} at local midnight (its whole board is this week's); a Fresh course's
 * and a golf course's board keeps each player's best, so "this week" is the best set since the week began (a best
 * set last week and not beaten since is not this week's); the Weekly Cup's week is its own (it turns at 04:00).
 *
 * <p><b>The family week</b> (0.38.4): the week's points board ({@link FamilyWeekRules#boardOf}, higher is
 * better), its race wins, and last week's top three for the ceremony, read when the snapshot carries the week's
 * key; each viewer's own points and place come from the same list (no extra read).
 *
 * <p><b>What is public.</b> The shared boards only ever show names, times and scores the high-score boards already
 * show. A player's own numbers ({@link Personal}) are read for them alone and shown only to them.
 *
 * <p>Pure apart from the {@link Db} it is handed.
 */
public final class ClubData {

    /** The games the boards read: Time Trials' and Mini golf's ids (pinned to their specs by a test). */
    public static final String TRIALS = "trials";
    public static final String GOLF = "golf";
    /** The family week's points boards live under the Clubhouse's own id (0.38.4; pinned to its spec by a test). */
    public static final String CLUB = "clubhouse";
    /** How many of the family week's rows are read (a page shows 8; a viewer beyond them has no place). */
    static final int FAMILY_ROWS = 16;
    /** How deep a board that keeps every best ever is read for the best set this week. */
    static final int DEEP = 100;

    private ClubData() {
    }

    /**
     * A time-trial course and its boards.
     *
     * @param record   the board its times go on (a hand-built course's all-time board, a Fresh course's set board)
     * @param week     this week's board: a hand-built course's week board, a Fresh course's set board again
     * @param weekOnly whether every row of {@code week} is from this week (a hand-built week board; a Fresh set that
     *                 began this week): its counted runs are this week's runs
     */
    public record CourseBoards(String id, String name, String record, String week, boolean weekOnly) {
    }

    /** A golf course, its board (strokes) and its par. */
    public record GolfBoards(String id, String name, String board, int par) {
    }

    /**
     * What to read, from memory, on the main thread.
     *
     * @param weekStart the first instant of this week (epoch ms): the quests' week, local midnight
     * @param cupWeek   the Weekly Cup's week now, or -1 while the Cup is off
     * @param pick      today's pick's name, or {@code null} for none
     * @param weekKey   0.38.4: the family week's key (the local epoch day it began), or -1 for no family page
     * @param lastWeekKey the week before's key (for the ceremony), or -1
     */
    public record Snapshot(List<CourseBoards> courses, List<GolfBoards> golf, long weekStart, long cupWeek,
                           String pick, int pickBonus, Set<UUID> viewers, long weekKey, long lastWeekKey) {

        public Snapshot {
            courses = List.copyOf(courses == null ? List.of() : courses);
            golf = List.copyOf(golf == null ? List.of() : golf);
            viewers = Set.copyOf(viewers == null ? Set.of() : viewers);
        }

        /** Without a family week (0.38.3's shape, kept for the tests). */
        public Snapshot(List<CourseBoards> courses, List<GolfBoards> golf, long weekStart, long cupWeek, String pick,
                        int pickBonus, Set<UUID> viewers) {
            this(courses, golf, weekStart, cupWeek, pick, pickBonus, viewers, -1, -1);
        }
    }

    /**
     * One viewer's own numbers, as read: their boards in each game, their place on each course's board they have a
     * time on, and their token ledger's totals by source since the week began.
     */
    public record Personal(List<GamesDao.PlayerBoard> trials, List<GamesDao.PlayerBoard> golf,
                           Map<String, Integer> ranks, List<TokenDao.SourceTotal> totals, long weekPoints,
                           int weekRank) {

        public Personal {
            trials = List.copyOf(trials == null ? List.of() : trials);
            golf = List.copyOf(golf == null ? List.of() : golf);
            ranks = Map.copyOf(ranks == null ? Map.of() : ranks);
            totals = List.copyOf(totals == null ? List.of() : totals);
        }

        /** Without the family week's points (0.38.3's shape, kept for the tests). */
        public Personal(List<GamesDao.PlayerBoard> trials, List<GamesDao.PlayerBoard> golf, Map<String, Integer> ranks,
                        List<TokenDao.SourceTotal> totals) {
            this(trials, golf, ranks, totals, 0, 0);
        }
    }

    /**
     * What was read, by course id (and viewer): this week's fastest run and the record of each course, the best
     * round this week and the record of each golf course, and each course's Weekly Cup leader this week.
     */
    public record Raw(Map<String, GamesDao.ScoreRow> week, Map<String, GamesDao.ScoreRow> records,
                      Map<String, GamesDao.ScoreRow> golfWeek, Map<String, GamesDao.ScoreRow> golfRecords,
                      Map<String, CupEntry> cups, Map<UUID, Personal> personal, List<GamesDao.ScoreRow> family,
                      Map<UUID, Long> wins, List<GamesDao.ScoreRow> lastWeek) {

        public Raw {
            week = Map.copyOf(week == null ? Map.of() : week);
            records = Map.copyOf(records == null ? Map.of() : records);
            golfWeek = Map.copyOf(golfWeek == null ? Map.of() : golfWeek);
            golfRecords = Map.copyOf(golfRecords == null ? Map.of() : golfRecords);
            cups = Map.copyOf(cups == null ? Map.of() : cups);
            personal = Map.copyOf(personal == null ? Map.of() : personal);
            family = List.copyOf(family == null ? List.of() : family);
            wins = Map.copyOf(wins == null ? Map.of() : wins);
            lastWeek = List.copyOf(lastWeek == null ? List.of() : lastWeek);
        }

        /** Without the family week (0.38.3's shape, kept for the tests). */
        public Raw(Map<String, GamesDao.ScoreRow> week, Map<String, GamesDao.ScoreRow> records,
                   Map<String, GamesDao.ScoreRow> golfWeek, Map<String, GamesDao.ScoreRow> golfRecords,
                   Map<String, CupEntry> cups, Map<UUID, Personal> personal) {
            this(week, records, golfWeek, golfRecords, cups, personal, List.of(), Map.of(), List.of());
        }
    }

    /** The database as the boards read it: the DAOs' read methods, and nothing that writes. */
    public interface Db {
        /** A board's top {@code limit} rows, lower is better, ties to whoever got there first. */
        List<GamesDao.ScoreRow> top(String game, String board, int limit) throws SQLException;

        /** Every Weekly Cup with entries not yet settled. */
        List<CupKey> openCups() throws SQLException;

        /** A Cup's entries. */
        List<CupEntry> cupEntries(CupKey key) throws SQLException;

        /** Every board {@code player} has a score on in {@code game}. */
        List<GamesDao.PlayerBoard> boardsOf(UUID player, String game) throws SQLException;

        /** {@code player}'s place on a board (lower is better), 0 with no score. */
        int rank(UUID player, String game, String board) throws SQLException;

        /** {@code player}'s ledger totals by source since {@code since}. */
        List<TokenDao.SourceTotal> totals(long since, UUID player) throws SQLException;

        /** A points board's top {@code limit} rows, higher is better (0.38.4: the family week); none by default. */
        default List<GamesDao.ScoreRow> topPoints(String game, String board, int limit) throws SQLException {
            return List.of();
        }
    }

    /** Everything {@code s} asks for, read through {@code db} (any thread: the database only). */
    public static Raw read(Snapshot s, Db db) throws SQLException {
        Map<String, GamesDao.ScoreRow> week = new LinkedHashMap<>();
        Map<String, GamesDao.ScoreRow> records = new LinkedHashMap<>();
        for (CourseBoards c : s.courses()) {
            if (c.week().equals(c.record())) {
                List<GamesDao.ScoreRow> rows = db.top(TRIALS, c.record(), DEEP); // one read: its best, and this week's
                putFirst(records, c.id(), rows);
                putIfNotNull(week, c.id(), c.weekOnly() ? first(rows) : firstSince(rows, s.weekStart()));
            } else {
                putFirst(records, c.id(), db.top(TRIALS, c.record(), 1));
                putFirst(week, c.id(), db.top(TRIALS, c.week(), 1));
            }
        }
        Map<String, GamesDao.ScoreRow> golfWeek = new LinkedHashMap<>();
        Map<String, GamesDao.ScoreRow> golfRecords = new LinkedHashMap<>();
        for (GolfBoards g : s.golf()) {
            List<GamesDao.ScoreRow> rows = db.top(GOLF, g.board(), DEEP);
            putFirst(golfRecords, g.id(), rows);
            putIfNotNull(golfWeek, g.id(), firstSince(rows, s.weekStart()));
        }
        Map<String, CupEntry> cups = new LinkedHashMap<>();
        if (s.cupWeek() >= 0) {
            for (CupKey k : db.openCups()) {
                if (k.week() == s.cupWeek()) {
                    putIfNotNull(cups, k.course(), leader(db.cupEntries(k)));
                }
            }
        }
        List<GamesDao.ScoreRow> family = List.of();
        Map<UUID, Long> wins = new LinkedHashMap<>();
        List<GamesDao.ScoreRow> lastWeek = List.of();
        if (s.weekKey() >= 0) {
            family = db.topPoints(CLUB, FamilyWeekRules.boardOf(s.weekKey()), FAMILY_ROWS);
            for (GamesDao.ScoreRow w : db.topPoints(CLUB, FamilyWeekRules.winsBoard(s.weekKey()), FAMILY_ROWS)) {
                wins.put(w.player(), w.score());
            }
            if (s.lastWeekKey() >= 0) {
                lastWeek = db.topPoints(CLUB, FamilyWeekRules.boardOf(s.lastWeekKey()), 3);
            }
        }
        Map<UUID, Personal> personal = new HashMap<>();
        for (UUID v : s.viewers()) {
            List<GamesDao.PlayerBoard> trials = db.boardsOf(v, TRIALS);
            Set<String> played = new LinkedHashSet<>();
            for (GamesDao.PlayerBoard b : trials) {
                played.add(b.board());
            }
            Map<String, Integer> ranks = new HashMap<>();
            for (CourseBoards c : s.courses()) {
                if (played.contains(c.record()) && !ranks.containsKey(c.record())) {
                    ranks.put(c.record(), db.rank(v, TRIALS, c.record()));
                }
            }
            long points = 0;
            int place = 0;
            for (int i = 0; i < family.size(); i++) {
                if (family.get(i).player().equals(v)) {
                    points = family.get(i).score();
                    place = i + 1;
                    break;
                }
            }
            personal.put(v, new Personal(trials, db.boardsOf(v, GOLF), ranks, db.totals(s.weekStart(), v), points,
                    place));
        }
        return new Raw(week, records, golfWeek, golfRecords, cups, personal, family, wins, lastWeek);
    }

    /** The first row (the best) set at or after {@code since}, or {@code null}: rows come best first. */
    static GamesDao.ScoreRow firstSince(List<GamesDao.ScoreRow> rows, long since) {
        for (GamesDao.ScoreRow r : rows) {
            if (r.at() >= since) {
                return r;
            }
        }
        return null;
    }

    /**
     * A Cup's leader: the fastest time (an entry with no time yet isn't one), a tie to whoever set it first, or
     * {@code null} while nobody has a time.
     */
    static CupEntry leader(List<CupEntry> entries) {
        CupEntry best = null;
        for (CupEntry e : entries) {
            if (e.hasTime() && (best == null || e.bestMs() < best.bestMs()
                    || (e.bestMs() == best.bestMs() && e.bestAt() < best.bestAt()))) {
                best = e;
            }
        }
        return best;
    }

    private static GamesDao.ScoreRow first(List<GamesDao.ScoreRow> rows) {
        return rows == null || rows.isEmpty() ? null : rows.get(0);
    }

    private static void putFirst(Map<String, GamesDao.ScoreRow> into, String id, List<GamesDao.ScoreRow> rows) {
        putIfNotNull(into, id, first(rows));
    }

    private static <V> void putIfNotNull(Map<String, V> into, String id, V value) {
        if (value != null) {
            into.put(id, value);
        }
    }
}
