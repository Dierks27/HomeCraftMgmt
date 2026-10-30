package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.cup.CupEntry;
import com.dierks.homecraft.storage.GamesDao;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This week's best (0.38.3; the owner: "display the top runs of the week and such"): each course's fastest run this
 * week and who ran it, eight a page; then golf, today's pick and the Weekly Cup leaders; then the all-time records.
 * The pages turn every 8 seconds, each fits the board's 9 lines, and before the first read it says so.
 */
class FameBoardTest {

    private static final UUID SAM = UUID.randomUUID();
    private static final UUID LEE = UUID.randomUUID();
    private static final Map<UUID, String> NAMES = Map.of(SAM, "Sam", LEE, "Lee");

    private static ClubData.CourseBoards course(String id, String name) {
        return new ClubData.CourseBoards(id, name, "course:" + id, "week:" + id + ":1", true);
    }

    private static ClubData.Snapshot snap(List<ClubData.CourseBoards> courses, List<ClubData.GolfBoards> golf) {
        return new ClubData.Snapshot(courses, golf, 1_000L, 7, "Sky Rings", 5, Set.of());
    }

    @Test
    void theWeeksFastestRunOnEachCourseWithWhoRanIt() {
        ClubData.Snapshot s = snap(List.of(course("sky", "Sky Rings"), course("river", "River Run"),
                course("ice", "Ice Boat")), List.of(new ClubData.GolfBoards("tiny", "Tiny Golf", "golf:tiny", 26),
                new ClubData.GolfBoards("big", "Big Golf", "golf:big", 40)));
        ClubData.Raw raw = new ClubData.Raw(
                Map.of("sky", new GamesDao.ScoreRow(SAM, 62_400, 5_000), "ice", new GamesDao.ScoreRow(LEE, 95_000, 6_000)),
                Map.of("sky", new GamesDao.ScoreRow(LEE, 58_000, 10), "river", new GamesDao.ScoreRow(SAM, 70_000, 10)),
                Map.of("tiny", new GamesDao.ScoreRow(SAM, 24, 9_000), "big", new GamesDao.ScoreRow(LEE, 39, 8_000)),
                Map.of("tiny", new GamesDao.ScoreRow(LEE, 22, 10)),
                Map.of("river", new CupEntry(LEE, 10, 1, 71_500, 2_000)), Map.of());
        FameBoard.Fame f = FameBoard.fame(s, raw, NAMES::get);
        assertEquals(List.of(new FameBoard.CourseTime("Sky Rings", "Sam", 62_400),
                new FameBoard.CourseTime("Ice Boat", "Lee", 95_000)), f.week(), "in the Games screen's order");
        assertEquals(new FameBoard.GolfRound("Tiny Golf", "Sam", 24, -2), f.golfWeek(),
                "the week's best round: fewest strokes against par (-2 beats -1)");
        assertEquals(List.of(new FameBoard.CupLead("River Run", "Lee", 71_500)), f.cups(), "the Cup's leader");
        assertEquals(2, f.records().size(), "the records");
        assertEquals(new FameBoard.GolfRound("Tiny Golf", "Lee", 22, -4), f.golfRecords().get(0), "golf's record");

        List<String> pages = FameBoard.pages(f);
        assertEquals(3, pages.size(), "this week's best, this week, the records (no family week): " + pages);
        assertEquals("&6&lThis week's best\n&fSky Rings &7- &eSam 1:02.4\n&fIce Boat &7- &eLee 1:35.0", pages.get(0));
        assertTrue(pages.get(1).startsWith(ClubBoardText.WEEK_TITLE), pages.get(1));
        assertTrue(pages.get(1).contains("&dGolf: &fSam &7- Tiny Golf 24 (-2)"), pages.get(1));
        assertTrue(pages.get(1).contains("&eToday's pick: &fSky Rings &7(+5 tokens)"), pages.get(1));
        assertTrue(pages.get(1).contains("&bWeekly Cup: &fRiver Run &7- Lee 1:11.5"), pages.get(1));
        assertTrue(pages.get(2).startsWith(ClubBoardText.RECORDS_TITLE) && pages.get(2).contains("Tiny Golf &7- &eLee 22 (-4)"),
                pages.get(2));
    }

    @Test
    void aTieForTheWeeksBestRoundGoesToWhoeverSetItFirst() {
        ClubData.Snapshot s = snap(List.of(), List.of(new ClubData.GolfBoards("a", "A", "golf:a", 20),
                new ClubData.GolfBoards("b", "B", "golf:b", 30)));
        ClubData.Raw raw = new ClubData.Raw(Map.of(), Map.of(), Map.of("a", new GamesDao.ScoreRow(SAM, 19, 9_000),
                "b", new GamesDao.ScoreRow(LEE, 29, 8_000)), Map.of(), Map.of(), Map.of());
        assertEquals("Lee", FameBoard.fame(s, raw, NAMES::get).golfWeek().name(), "both -1: the earlier one");
    }

    @Test
    void nothingYetSaysSoAndEveryPageFitsTheBoard() {
        FameBoard.Fame empty = FameBoard.fame(snap(List.of(), List.of()), new ClubData.Raw(Map.of(), Map.of(), Map.of(),
                Map.of(), Map.of(), Map.of()), NAMES::get);
        List<String> pages = FameBoard.pages(empty);
        assertEquals(ClubBoardText.FAME_TITLE + "\n" + ClubBoardText.NO_RUNS, pages.get(0), "be the first!");
        assertTrue(pages.get(1).contains(ClubBoardText.NO_GOLF), pages.get(1));
        assertEquals(ClubBoardText.RECORDS_TITLE + "\n" + ClubBoardText.NO_RECORDS, pages.get(2));
        assertNull(empty.golfWeek(), "no round");
        List<String> before = FameBoard.pages(null);
        assertEquals(List.of(ClubBoardText.FAME_TITLE + "\n" + ClubBoardText.READING), before,
                "before the first read: one moment");
        FameBoard.Fame noPick = new FameBoard.Fame(List.of(), null, null, 0, List.of(), List.of(), List.of());
        assertTrue(FameBoard.pages(noPick).get(1).contains(ClubBoardText.NO_PICK), "no pick today");
    }

    @Test
    void eightRowsAPageAndThePagesTurnEveryEightSeconds() {
        List<FameBoard.CourseTime> week = new ArrayList<>();
        List<FameBoard.CupLead> cups = new ArrayList<>();
        for (int i = 0; i < 19; i++) {
            week.add(new FameBoard.CourseTime("Course " + i, "Sam", 60_000 + i));
            cups.add(new FameBoard.CupLead("Course " + i, "Lee", 70_000));
        }
        FameBoard.Fame f = new FameBoard.Fame(week, null, "Snake", 0, cups, week, List.of());
        List<String> pages = FameBoard.pages(f);
        assertEquals(3 + 1 + 3, pages.size(), "19 rows: 3 pages each, and this week's");
        assertTrue(pages.get(0).startsWith("&6&lThis week's best &7(1/3)\n"), pages.get(0));
        assertTrue(pages.get(2).startsWith("&6&lThis week's best &7(3/3)\n"), pages.get(2));
        assertTrue(pages.get(4).startsWith("&6&lAll-time records &7(1/3)\n"), pages.get(4));
        for (String page : pages) {
            assertTrue(page.split("\n").length <= WallBoards.LINES, "a title and at most 8 rows: " + page);
        }
        assertEquals(8, pages.get(3).split("\n").length - 1, "golf, the pick and six Cups: the page is full");
        assertFalse(pages.get(3).contains("+0"), "no bonus: no tokens line");
        assertEquals(0, FameBoard.page(0, 7), "the first page");
        assertEquals(0, FameBoard.page(7_999, 7), "for 8 seconds");
        assertEquals(1, FameBoard.page(8_000, 7), "then the next");
        assertEquals(0, FameBoard.page(7 * 8_000, 7), "round and round");
        assertEquals(0, FameBoard.page(123_456, 1), "one page stays");
        assertEquals(pages.get(1), FameBoard.text(f, 8_000), "the page up at a time");
    }

    /**
     * 0.38.4: with the family week on (the snapshot carries the week's key) a fourth page lists each player's
     * points and race wins, most points first, cut to the board's rows, or says there are no points yet; last
     * week's top three come through with their names; without the week there is no page at all.
     */
    @Test
    void theFamilyWeekPage() {
        ClubData.Snapshot s = new ClubData.Snapshot(List.of(), List.of(), 1_000L, -1, null, 0, Set.of(), 20_700, 20_693);
        List<GamesDao.ScoreRow> family = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            family.add(new GamesDao.ScoreRow(i == 0 ? SAM : i == 1 ? LEE : UUID.randomUUID(), 20 - i, i));
        }
        ClubData.Raw raw = new ClubData.Raw(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), family,
                Map.of(SAM, 3L), List.of(new GamesDao.ScoreRow(LEE, 30, 1), new GamesDao.ScoreRow(SAM, 25, 2)));
        FameBoard.Fame f = FameBoard.fame(s, raw, id -> NAMES.getOrDefault(id, "Ana"));
        assertEquals(20_700, f.weekKey(), "the week the rows are for");
        assertEquals(new FameBoard.FamilyRow(SAM, "Sam", 20, 3), f.family().get(0), "most points first, with the wins");
        assertEquals(new FameBoard.FamilyRow(LEE, "Lee", 19, 0), f.family().get(1), "no wins: 0");
        assertEquals(List.of(new FameBoard.FamilyRow(LEE, "Lee", 30, 0), new FameBoard.FamilyRow(SAM, "Sam", 25, 0)),
                f.lastWeek(), "last week's top, named");
        List<String> pages = FameBoard.pages(f);
        assertEquals(4, pages.size(), "...and the family week: " + pages);
        String page = pages.get(3);
        assertTrue(page.startsWith(ClubBoardText.FAMILY_TITLE + "\n&f1. Sam &e20 pts &7- 3 wins\n&f2. Lee &e19 pts\n"),
                page);
        assertEquals(1 + ClubBoard.MAX_ROWS, page.split("\n").length, "cut to the board's rows");

        FameBoard.Fame none = new FameBoard.Fame(List.of(), null, null, 0, List.of(), List.of(), List.of(), List.of(),
                List.of(), 20_700);
        assertTrue(FameBoard.pages(none).get(3).endsWith(ClubBoardText.NO_FAMILY), "no points yet");
        FameBoard.Fame off = new FameBoard.Fame(List.of(), null, null, 0, List.of(), List.of(), List.of());
        assertEquals(3, FameBoard.pages(off).size(), "no week key: no family page");
        assertEquals(-1, off.weekKey());
    }
}
