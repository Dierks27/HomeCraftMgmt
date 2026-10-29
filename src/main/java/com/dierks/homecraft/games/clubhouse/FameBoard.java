package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.cup.CupEntry;
import com.dierks.homecraft.storage.GamesDao;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * This week's best, the Clubhouse's east wall (0.38.3; the owner: "display the top runs of the week and such"):
 * pages that turn every {@value #PAGE_MS} ms.
 *
 * <ol>
 *   <li><b>This week's best</b>: each course's fastest run this week and who ran it, {@value #ROWS} a page
 *       ("Sky Rings - Sam 1:02.4"), or "No runs yet this week - be the first!".</li>
 *   <li><b>This week</b>: the best golf round of the week (fewest strokes against par), today's pick and its
 *       bonus, and each Weekly Cup's leader.</li>
 *   <li><b>All-time records</b>: each course's record, then each golf course's.</li>
 * </ol>
 * It starts again with the weekly boards (each course's own week: {@link ClubData}). Names, times and scores only,
 * as on the high-score boards.
 *
 * <p>Pure: the snapshot, what was read and the names in, lines out.
 */
public final class FameBoard {

    /** How long a page stays up. */
    public static final long PAGE_MS = 8_000L;
    /** Rows a page (under its title). */
    static final int ROWS = ClubBoard.MAX_ROWS;

    private FameBoard() {
    }

    /** A course's fastest run (names resolved). */
    public record CourseTime(String course, String name, long ms) {
    }

    /** A round of golf: the course, who (or {@code null} for the viewer's own), the strokes and against par. */
    public record GolfRound(String course, String name, int strokes, int vsPar) {
    }

    /** A Weekly Cup's leader this week. */
    public record CupLead(String course, String name, long ms) {
    }

    /** Everything the wall shows. */
    public record Fame(List<CourseTime> week, GolfRound golfWeek, String pick, int pickBonus, List<CupLead> cups,
                       List<CourseTime> records, List<GolfRound> golfRecords) {

        public Fame {
            week = List.copyOf(week == null ? List.of() : week);
            cups = List.copyOf(cups == null ? List.of() : cups);
            records = List.copyOf(records == null ? List.of() : records);
            golfRecords = List.copyOf(golfRecords == null ? List.of() : golfRecords);
        }
    }

    /**
     * What was read, as the wall shows it: courses and golf courses in the snapshot's order (the Games screen's),
     * each holder's name through {@code names}, the week's best round the fewest strokes against par (a tie to
     * whoever set it first). A Cup on a course that isn't open now isn't shown.
     */
    public static Fame fame(ClubData.Snapshot s, ClubData.Raw raw, Function<UUID, String> names) {
        List<CourseTime> week = new ArrayList<>();
        List<CourseTime> records = new ArrayList<>();
        List<CupLead> cups = new ArrayList<>();
        for (ClubData.CourseBoards c : s.courses()) {
            GamesDao.ScoreRow w = raw.week().get(c.id());
            if (w != null) {
                week.add(new CourseTime(c.name(), names.apply(w.player()), w.score()));
            }
            GamesDao.ScoreRow r = raw.records().get(c.id());
            if (r != null) {
                records.add(new CourseTime(c.name(), names.apply(r.player()), r.score()));
            }
            CupEntry lead = raw.cups().get(c.id());
            if (lead != null) {
                cups.add(new CupLead(c.name(), names.apply(lead.player()), lead.bestMs()));
            }
        }
        GolfRound golfWeek = null;
        long golfWeekAt = 0;
        List<GolfRound> golfRecords = new ArrayList<>();
        for (ClubData.GolfBoards g : s.golf()) {
            GamesDao.ScoreRow w = raw.golfWeek().get(g.id());
            if (w != null) {
                int vs = (int) w.score() - g.par();
                if (golfWeek == null || vs < golfWeek.vsPar() || (vs == golfWeek.vsPar() && w.at() < golfWeekAt)) {
                    golfWeek = new GolfRound(g.name(), names.apply(w.player()), (int) w.score(), vs);
                    golfWeekAt = w.at();
                }
            }
            GamesDao.ScoreRow r = raw.golfRecords().get(g.id());
            if (r != null) {
                golfRecords.add(new GolfRound(g.name(), names.apply(r.player()), (int) r.score(),
                        (int) r.score() - g.par()));
            }
        }
        return new Fame(week, golfWeek, s.pick(), s.pickBonus(), cups, records, golfRecords);
    }

    /**
     * The wall's pages, each its title and up to {@value #ROWS} rows: this week's best (as many pages as it
     * takes), this week (golf, the pick, the Cups), the all-time records. {@code null}: one page saying the
     * numbers are on their way.
     */
    public static List<String> pages(Fame f) {
        List<String> out = new ArrayList<>();
        if (f == null) {
            out.add(ClubBoardText.FAME_TITLE + "\n" + ClubBoardText.READING);
            return out;
        }
        List<String> week = new ArrayList<>();
        for (CourseTime t : f.week()) {
            week.add(ClubBoardText.courseTime(t.course(), t.name(), t.ms()));
        }
        paged(out, ClubBoardText.FAME_TITLE, week, ClubBoardText.NO_RUNS);

        List<String> more = new ArrayList<>();
        GolfRound g = f.golfWeek();
        more.add(g == null ? ClubBoardText.NO_GOLF : ClubBoardText.golfWeek(g.course(), g.name(), g.strokes(),
                g.vsPar()));
        more.add(ClubBoardText.pick(f.pick(), f.pickBonus()));
        for (CupLead c : f.cups()) {
            if (more.size() >= ROWS) {
                break;
            }
            more.add(ClubBoardText.cup(c.course(), c.name(), c.ms()));
        }
        out.add(ClubBoardText.WEEK_TITLE + "\n" + String.join("\n", more));

        List<String> records = new ArrayList<>();
        for (CourseTime t : f.records()) {
            records.add(ClubBoardText.courseTime(t.course(), t.name(), t.ms()));
        }
        for (GolfRound r : f.golfRecords()) {
            records.add(ClubBoardText.golfRecord(r.course(), r.name(), r.strokes(), r.vsPar()));
        }
        paged(out, ClubBoardText.RECORDS_TITLE, records, ClubBoardText.NO_RECORDS);
        return out;
    }

    /** {@code rows} as pages of {@value #ROWS} under {@code title} ("(1/2)" when more than one), or {@code none}. */
    private static void paged(List<String> out, String title, List<String> rows, String none) {
        if (rows.isEmpty()) {
            out.add(title + "\n" + none);
            return;
        }
        int pages = (rows.size() + ROWS - 1) / ROWS;
        for (int p = 0; p < pages; p++) {
            List<String> page = rows.subList(p * ROWS, Math.min(rows.size(), (p + 1) * ROWS));
            out.add(ClubBoardText.paged(title, p + 1, pages) + "\n" + String.join("\n", page));
        }
    }

    /** Which of {@code pages} shows at {@code now}: the next one every {@value #PAGE_MS} ms, round and round. */
    public static int page(long now, int pages) {
        return pages <= 1 ? 0 : (int) Math.floorMod(now / PAGE_MS, (long) pages);
    }

    /** The page up at {@code now}. */
    public static String text(Fame f, long now) {
        List<String> pages = pages(f);
        return pages.get(page(now, pages.size()));
    }
}
