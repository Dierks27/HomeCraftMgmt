package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.RewardKind;
import com.dierks.homecraft.games.ScoreResult;
import com.dierks.homecraft.games.gen.api.DailyStars;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.storage.GamesDao;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a finished run records and pays, with a fake ledger.
 *
 * <p>Pinned here: a test run, a voided run and a run on a changed layout record nothing and pay
 * nothing; a counted run records its time on the course's all-time board and this week's board,
 * announces how it stands between recording and paying, then pays the first clear (the tier's
 * amount, once per course), the week's best only when it IS the week's best, the course of the
 * week only on that course and today's pick only when featured — each under its one spelling of
 * ref — and never a reward of 0; what was earned is what the ledger actually paid; and the
 * lines call it a first finish only when the first clear is about to be paid, not when the
 * board is empty because the course changed.
 *
 * <p>And a daily course (GEN-SPEC §5.2-§5.3): its time goes on its layout's own board only (never
 * the all-time or weekly board); the first finish of its course day pays once, a reroll that day
 * paying no second one, and the next course day paying again; the first clear stays once ever; the
 * week's best is never paid; it earns stars from its layout's star times, keeps the day's best and
 * adds only the difference to the week; a Star Chart goal crossed is paid once, by the daily game;
 * every day it uses is the layout's, while today's pick keeps the calendar day; and a hand-built
 * run behaves exactly as before.
 */
class TrialFinishTest {

    private static final long DAY = 20_100;
    private static final long WEEK = 20_097;

    /** Records every call, answers submits with the given results and pays what is asked (or a cap). */
    private static final class FakeLedger implements TrialFinish.Ledger {
        final List<String> calls = new ArrayList<>();
        ScoreResult course = new ScoreResult(true, null, false, 3);
        ScoreResult week = new ScoreResult(true, null, false, 2);
        int cap = Integer.MAX_VALUE;
        boolean firstPaid;
        Boolean firstFinish;

        @Override
        public ScoreResult submit(String board, long ms) {
            calls.add("submit " + board + " " + ms);
            return board.startsWith("course:") ? course : week;
        }

        @Override
        public void announce(ScoreResult c, ScoreResult w, boolean first) {
            calls.add("announce");
            firstFinish = first;
        }

        @Override
        public boolean firstClearPaid() {
            return firstPaid;
        }

        @Override
        public int pay(RewardKind kind, String ref, int tokens, String detail) {
            calls.add("pay " + kind + " " + ref + " " + tokens + " " + detail);
            int paid = Math.min(tokens, cap);
            cap -= paid;
            return paid;
        }
    }

    private static TrialFinish.Run run(boolean courseOfWeek, boolean featured) {
        return new TrialFinish.Run("river_run", "River Run", 62_345, DAY, WEEK, courseOfWeek, featured, 10, 5, 2, 3);
    }

    private static FairPlay.Verdict counted() {
        return FairPlay.judge(false, null, false, 62_345, 5, -1);
    }

    @Test
    void aTestRunRecordsAndPaysNothing() {
        FakeLedger ledger = new FakeLedger();
        TrialFinish.Summary s = TrialFinish.settle(FairPlay.judge(true, null, false, 62_345, 5, -1),
                run(true, true), ledger);
        assertEquals(List.of(), ledger.calls, "a test run submits no score, record or reward");
        assertSame(TrialFinish.Summary.NONE, s, "and earns nothing");
    }

    @Test
    void aVoidedOrStaleRunRecordsAndPaysNothing() {
        FakeLedger ledger = new FakeLedger();
        TrialFinish.settle(FairPlay.judge(false, FairPlay.FLYING, false, 62_345, 5, -1), run(true, true), ledger);
        TrialFinish.settle(FairPlay.judge(false, null, true, 62_345, 5, -1), run(true, true), ledger);
        TrialFinish.settle(FairPlay.judge(false, null, false, 1_000, 5, -1), run(true, true), ledger);
        assertEquals(List.of(), ledger.calls, "flying, a changed layout and a too-quick run all record nothing");
    }

    @Test
    void aCountedRunRecordsBothBoardsThenAnnouncesThenPays() {
        FakeLedger ledger = new FakeLedger();
        TrialFinish.Summary s = TrialFinish.settle(counted(), run(false, false), ledger);
        assertEquals(List.of(
                "submit course:river_run 62345",
                "submit week:river_run:" + WEEK + " 62345",
                "announce",
                "pay FIRST_CLEAR first_clear:river_run 10 River Run: first finish"), ledger.calls,
                "the all-time board, this week's board, the lines, then the first clear at the tier's amount");
        assertEquals(10, s.earned(), "what the ledger paid");
    }

    @Test
    void theWeeksBestPaysOnlyWhenItIsTheWeeksBest() {
        FakeLedger ledger = new FakeLedger();
        TrialFinish.settle(counted(), run(false, false), ledger);
        assertTrue(ledger.calls.stream().noneMatch(c -> c.contains("WEEKLY_BEST")), "a personal best for the week isn't the week's best");
        FakeLedger best = new FakeLedger();
        best.week = new ScoreResult(true, 70_000L, true, 1);
        TrialFinish.settle(counted(), run(false, false), best);
        assertTrue(best.calls.contains("pay WEEKLY_BEST weekly:river_run:" + WEEK + " 5 River Run: best time this week"),
                "the week's best time pays the bonus, once per course per week by its ref");
    }

    @Test
    void theCourseOfTheWeekAndTodaysPickPayOnlyWhenTheyAreThat() {
        FakeLedger ledger = new FakeLedger();
        TrialFinish.settle(counted(), run(true, true), ledger);
        assertTrue(ledger.calls.contains("pay COURSE_OF_WEEK cotw:" + DAY + " 2 River Run: course of the week"),
                "the course of the week, once a day");
        assertTrue(ledger.calls.contains("pay FEATURED featured:" + DAY + " 3 River Run: today's pick"),
                "today's pick, once a day");
        FakeLedger plain = new FakeLedger();
        TrialFinish.settle(counted(), run(false, false), plain);
        assertTrue(plain.calls.stream().noneMatch(c -> c.contains("COURSE_OF_WEEK") || c.contains("FEATURED")),
                "neither on an ordinary course");
    }

    @Test
    void aRewardOfNothingIsNeverPaid() {
        FakeLedger ledger = new FakeLedger();
        ledger.week = new ScoreResult(true, null, true, 1);
        TrialFinish.settle(counted(), new TrialFinish.Run("river_run", "River Run", 62_345, DAY, WEEK, true, true,
                0, 0, 0, 0), ledger);
        assertTrue(ledger.calls.stream().noneMatch(c -> c.startsWith("pay")), "every amount 0: nothing to pay");
    }

    @Test
    void whatWasEarnedIsWhatTheCapsLetThrough() {
        FakeLedger ledger = new FakeLedger();
        ledger.week = new ScoreResult(true, null, true, 1);
        ledger.cap = 12;
        TrialFinish.Summary s = TrialFinish.settle(counted(), run(true, true), ledger);
        assertEquals(12, s.earned(), "10 + 5 + 2 + 3 asked, 12 let through");
        assertEquals(new ScoreResult(true, null, true, 1), s.week(), "the week's standing is kept for the screen");
    }

    // ---- a daily course -------------------------------------------------------------------------

    private static final long LAYOUT_DAY = 20_725;
    private static final long STAR_WEEK = 20_720;

    /**
     * A ledger that behaves like the database: a once-only ref pays once, and stars are kept as
     * each day board's best with the week moved by the difference (DailyStars, like addStars).
     */
    private static class DailyLedger implements TrialFinish.Ledger {
        final List<String> calls = new ArrayList<>();
        final Set<String> paidRefs = new HashSet<>();
        final Map<String, Long> boards = new HashMap<>();
        final List<Integer> starsTold = new ArrayList<>();
        ScoreResult today = new ScoreResult(true, null, true, 1);

        @Override
        public ScoreResult submit(String board, long ms) {
            calls.add("submit " + board + " " + ms);
            return today;
        }

        @Override
        public void announce(ScoreResult course, ScoreResult week, boolean firstFinish) {
            calls.add("announce " + (week == ScoreResult.NONE ? "no week" : "week"));
        }

        @Override
        public boolean firstClearPaid() {
            return paidRefs.contains("first_clear:daily_parkour_easy");
        }

        @Override
        public int pay(RewardKind kind, String ref, int tokens, String detail) {
            calls.add("pay " + kind + " " + ref + " " + tokens);
            return paidRefs.add(ref) ? tokens : 0;
        }

        @Override
        public GamesDao.StarsAdded addStars(String dayBoard, String weekBoard, int stars) {
            calls.add("stars " + dayBoard + " " + weekBoard + " " + stars);
            Long best = boards.get(dayBoard);
            long week = boards.getOrDefault(weekBoard, 0L);
            int added = DailyStars.delta(best, stars);
            boards.put(dayBoard, Math.max(best == null ? 0 : best, stars));
            boards.put(weekBoard, week + added);
            return new GamesDao.StarsAdded((int) Math.max(best == null ? 0 : best, stars), added, week + added);
        }

        @Override
        public void stars(int stars, GamesDao.StarsAdded added) {
            starsTold.add(stars);
        }

        @Override
        public int payGoal(String ref, int tokens, String detail) {
            calls.add("goal " + ref + " " + tokens + " " + detail);
            return paidRefs.add(ref) ? tokens : 0;
        }
    }

    /** Easy Parkour's layout for {@code day}: 3 stars at 45 s or less, 2 at 70 s or less. */
    private static GenTag tag(long day, int reroll) {
        return new GenTag("daily_parkour_easy", "parkour", 1, day, reroll, 0x3f2aL, 'B', "3c9e51aa07b2", 22_500,
                45_000, 70_000, List.of(), List.of(), 1L);
    }

    /** A run on that layout, finished on calendar day {@link #DAY} (5 days after the layout's), at {@code ms}. */
    private static TrialFinish.Run dailyRun(GenTag tag, long ms, boolean courseOfWeek, boolean featured) {
        return new TrialFinish.Run("daily_parkour_easy", "Easy Parkour", ms, DAY, WEEK, courseOfWeek, featured, 5, 7,
                2, 3, new TrialFinish.Daily(tag, STAR_WEEK, 1, List.of(10, 25), 1));
    }

    private static FairPlay.Verdict counted(long ms) {
        return FairPlay.judge(false, null, false, ms, 5, -1);
    }

    @Test
    void aDailyCourseWritesItsLayoutsBoardOnly() {
        DailyLedger ledger = new DailyLedger();
        TrialFinish.settle(counted(60_000), dailyRun(tag(LAYOUT_DAY, 0), 60_000, false, false), ledger);
        List<String> submits = ledger.calls.stream().filter(c -> c.startsWith("submit")).toList();
        assertEquals(List.of("submit gday:daily_parkour_easy:" + LAYOUT_DAY + " 60000"), submits,
                "one board: the layout's own; never the all-time or the weekly board");
        assertTrue(ledger.calls.contains("announce no week"), "announced without a week's standing");

        DailyLedger rerolled = new DailyLedger();
        TrialFinish.settle(counted(60_000), dailyRun(tag(LAYOUT_DAY, 2), 60_000, false, false), rerolled);
        assertTrue(rerolled.calls.contains("submit gday:daily_parkour_easy:" + LAYOUT_DAY + "r2 60000"),
                "a reroll is a fresh board: " + rerolled.calls);
    }

    @Test
    void theFirstFinishOfACourseDayPaysOnceAndNotAgainOnAReroll() {
        DailyLedger ledger = new DailyLedger();
        TrialFinish.Summary first = TrialFinish.settle(counted(60_000), dailyRun(tag(LAYOUT_DAY, 0), 60_000, false,
                false), ledger);
        TrialFinish.Summary again = TrialFinish.settle(counted(50_000), dailyRun(tag(LAYOUT_DAY, 0), 50_000, false,
                false), ledger);
        TrialFinish.Summary reroll = TrialFinish.settle(counted(50_000), dailyRun(tag(LAYOUT_DAY, 1), 50_000, false,
                false), ledger);
        long asked = ledger.calls.stream().filter(c -> c.equals("pay DAILY_CLEAR dclear:daily_parkour_easy:"
                + LAYOUT_DAY + " 1")).count();
        assertEquals(3, asked, "every counted finish asks under the one ref of its course day: " + ledger.calls);
        assertEquals(1 + 5, first.earned(), "the first pays the day's 1 and the very first finish's 5");
        assertEquals(0, again.earned(), "a second finish that day pays nothing more");
        assertEquals(0, reroll.earned(), "a reroll the same day has the same ref: no second daily reward");

        TrialFinish.Summary tomorrow = TrialFinish.settle(counted(60_000), dailyRun(tag(LAYOUT_DAY + 1, 0), 60_000,
                false, false), ledger);
        assertTrue(ledger.calls.contains("pay DAILY_CLEAR dclear:daily_parkour_easy:" + (LAYOUT_DAY + 1) + " 1"),
                "the next course day has its own ref");
        assertEquals(1, tomorrow.earned(), "and pays again (the first clear doesn't)");
    }

    @Test
    void theFirstClearOfADailyCourseIsOnceEver() {
        DailyLedger ledger = new DailyLedger();
        for (int d = 0; d < 3; d++) {
            TrialFinish.settle(counted(60_000), dailyRun(tag(LAYOUT_DAY + d, 0), 60_000, false, false), ledger);
        }
        long asked = ledger.calls.stream().filter(c -> c.startsWith("pay FIRST_CLEAR first_clear:daily_parkour_easy "))
                .count();
        assertEquals(3, asked, "the same once-ever ref every day, whatever the layout: " + ledger.calls);
        assertTrue(ledger.paidRefs.contains("first_clear:daily_parkour_easy"), "paid the first time");
    }

    @Test
    void aDailyCourseNeverPaysTheWeeksBest() {
        DailyLedger ledger = new DailyLedger();
        ledger.today = new ScoreResult(true, 70_000L, true, 1);
        TrialFinish.settle(counted(40_000), dailyRun(tag(LAYOUT_DAY, 0), 40_000, true, true), ledger);
        assertTrue(ledger.calls.stream().noneMatch(c -> c.contains("WEEKLY_BEST") || c.contains("weekly:")),
                "the Star Chart replaces the week's best on a course that changes every day: " + ledger.calls);
        assertTrue(ledger.calls.contains("pay COURSE_OF_WEEK cotw:" + DAY + " 2"),
                "the course of the week still pays, on the calendar day");
        assertTrue(ledger.calls.contains("pay FEATURED featured:" + DAY + " 3"),
                "and today's pick, on the calendar day");
    }

    @Test
    void starsComeFromTheLayoutsTimesAndOnlyTheDifferenceGoesOnTheWeek() {
        DailyLedger ledger = new DailyLedger();
        TrialFinish.Summary one = TrialFinish.settle(counted(90_000), dailyRun(tag(LAYOUT_DAY, 0), 90_000, false,
                false), ledger);
        assertEquals(1, one.stars(), "slower than silver: 1 star for finishing");
        assertEquals(1, one.weekStars(), "the week has 1");
        assertTrue(ledger.calls.contains("stars gstars:daily_parkour_easy:" + LAYOUT_DAY + " gweek:" + STAR_WEEK + " 1"),
                "kept on the layout's day and its week: " + ledger.calls);

        TrialFinish.Summary three = TrialFinish.settle(counted(45_000), dailyRun(tag(LAYOUT_DAY, 0), 45_000, false,
                false), ledger);
        assertEquals(3, three.stars(), "at the gold time exactly: 3 stars");
        assertEquals(3, three.weekStars(), "the week moves by the difference, 1 to 3");

        TrialFinish.Summary two = TrialFinish.settle(counted(60_000), dailyRun(tag(LAYOUT_DAY, 1), 60_000, false,
                false), ledger);
        assertEquals(2, two.stars(), "a slower run on the reroll earns 2");
        assertEquals(3, two.weekStars(), "but the day's best is still 3, so the week doesn't move");
        assertEquals(List.of(1, 3, 2), ledger.starsTold, "each run is told its own stars");
    }

    @Test
    void aStarChartGoalCrossedIsPaidOnceThroughTheDailyGame() {
        DailyLedger ledger = new DailyLedger();
        ledger.boards.put("gweek:" + STAR_WEEK, 8L);
        TrialFinish.Summary s = TrialFinish.settle(counted(40_000), dailyRun(tag(LAYOUT_DAY, 0), 40_000, false,
                false), ledger);
        assertTrue(ledger.calls.contains("goal ms:gweek:" + STAR_WEEK + ":10 1 Star Chart: 10★ this week"),
                "8 to 11 crosses 10: paid by the daily game under the goal's own ref: " + ledger.calls);
        assertEquals(11, s.weekStars(), "the week after the run");
        assertEquals(1 + 5 + 1, s.earned(), "the day's first finish, the very first finish and the goal");

        TrialFinish.Summary again = TrialFinish.settle(counted(40_000), dailyRun(tag(LAYOUT_DAY + 1, 0), 40_000, false,
                false), ledger);
        assertEquals(1, again.earned(), "11 to 14: only the day's first finish; the 10 is never paid twice");
    }

    @Test
    void aStarChartGoalTheCapsHeldBackIsPaidByTheNextRunThatWeek() {
        DailyLedger ledger = new DailyLedger() {
            boolean capped = true;

            @Override
            public int payGoal(String ref, int tokens, String detail) {
                calls.add("goal " + ref + " " + tokens + " " + detail);
                if (capped) {
                    capped = false; // today's caps are full: nothing paid, nothing recorded
                    return 0;
                }
                return paidRefs.add(ref) ? tokens : 0;
            }
        };
        ledger.boards.put("gweek:" + STAR_WEEK, 8L);
        TrialFinish.Summary capped = TrialFinish.settle(counted(40_000), dailyRun(tag(LAYOUT_DAY, 0), 40_000, false,
                false), ledger);
        assertEquals(1 + 5, capped.earned(), "8 to 11 crosses 10 on a day the caps are full: the goal pays nothing");
        TrialFinish.Summary later = TrialFinish.settle(counted(40_000), dailyRun(tag(LAYOUT_DAY + 1, 0), 40_000, false,
                false), ledger);
        assertEquals(1 + 1, later.earned(), "the next run that week (11 to 14) pays the 10 it reached: " + ledger.calls);
        TrialFinish.Summary third = TrialFinish.settle(counted(40_000), dailyRun(tag(LAYOUT_DAY + 2, 0), 40_000, false,
                false), ledger);
        assertEquals(1, third.earned(), "and only once");
    }

    @Test
    void everyDayADailyRunUsesIsItsLayoutsNotTheCalendars() {
        DailyLedger ledger = new DailyLedger();
        TrialFinish.settle(counted(60_000), dailyRun(tag(LAYOUT_DAY, 0), 60_000, true, true), ledger);
        assertTrue(ledger.calls.contains("pay DAILY_CLEAR dclear:daily_parkour_easy:" + LAYOUT_DAY + " 1"),
                "the daily reward is the layout's day, even when the calendar has moved on");
        assertTrue(ledger.calls.stream().anyMatch(c -> c.startsWith("stars gstars:daily_parkour_easy:" + LAYOUT_DAY)),
                "and so are the stars");
        assertTrue(ledger.calls.contains("pay FEATURED featured:" + DAY + " 3"), "today's pick is the calendar's");
    }

    @Test
    void aHandBuiltRunIsExactlyAsBefore() {
        FakeLedger old = new FakeLedger();
        FakeLedger now = new FakeLedger();
        now.week = old.week = new ScoreResult(true, 70_000L, true, 1);
        TrialFinish.settle(counted(), run(true, true), old);
        TrialFinish.settle(counted(), new TrialFinish.Run("river_run", "River Run", 62_345, DAY, WEEK, true, true, 10,
                5, 2, 3, null), now);
        assertEquals(old.calls, now.calls, "no daily part: the same boards, lines and rewards in the same order");
        assertEquals(List.of(
                "submit course:river_run 62345",
                "submit week:river_run:" + WEEK + " 62345",
                "announce",
                "pay FIRST_CLEAR first_clear:river_run 10 River Run: first finish",
                "pay WEEKLY_BEST weekly:river_run:" + WEEK + " 5 River Run: best time this week",
                "pay COURSE_OF_WEEK cotw:" + DAY + " 2 River Run: course of the week",
                "pay FEATURED featured:" + DAY + " 3 River Run: today's pick"), now.calls,
                "the hand-built order, pinned");
        TrialFinish.Summary s = TrialFinish.settle(counted(), run(false, false), new FakeLedger());
        assertEquals(0, s.stars(), "no stars on a hand-built course");
        assertEquals(-1, s.weekStars(), "and no Star Chart");
        assertFalse(now.calls.stream().anyMatch(c -> c.contains("gday:") || c.contains("DAILY_CLEAR")),
                "nothing of Daily Courses");
    }

    @Test
    void theLinesCallItAFirstFinishOnlyWhenTheFirstClearIsAboutToBePaid() {
        FakeLedger fresh = new FakeLedger();
        TrialFinish.settle(counted(), run(false, false), fresh);
        assertEquals(Boolean.TRUE, fresh.firstFinish, "never paid before: this finish pays it, so it is the first");
        FakeLedger again = new FakeLedger();
        again.firstPaid = true;
        TrialFinish.settle(counted(), run(false, false), again);
        assertEquals(Boolean.FALSE, again.firstFinish,
                "paid long ago (the board was cleared by a layout change): not called a first finish");
        FakeLedger none = new FakeLedger();
        TrialFinish.settle(counted(), new TrialFinish.Run("river_run", "River Run", 62_345, DAY, WEEK, false, false,
                0, 5, 2, 3), none);
        assertEquals(Boolean.FALSE, none.firstFinish, "a first clear of 0 pays nothing, so it promises nothing");
    }
}
