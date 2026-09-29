package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.RewardKind;
import com.dierks.homecraft.games.ScoreResult;
import com.dierks.homecraft.games.SkillRewards;
import com.dierks.homecraft.games.gen.api.DailyStars;
import com.dierks.homecraft.games.gen.api.GenBoards;
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
 * <p>And a Fresh course (GEN-SPEC §5.2-§5.3, the weekly addendum §4, GEN-SPEC-KEEP §3): its time
 * goes on its set's own board only (never the all-time or weekly board); the first finish of its
 * set pays once, all or nothing, under {@code fresh:<slot>:<edition>} — a second finish a day
 * later, or a reroll, paying no second one, one the day's caps couldn't pay whole waiting for
 * another day of the set, and the next set paying again; the first clear stays once ever per slot;
 * the week's best is never paid; it earns stars from its layout's star times, keeps the set's best
 * and adds only the difference to the Star Chart of the run's own week; each goal reached pays its
 * own tokens once, all or nothing, by Fresh Courses; a recalled course plays on its original board
 * and first-finish ref with its own stars; every set it uses is the layout's, while today's pick
 * keeps the calendar day; and a hand-built run behaves exactly as before.
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

    // ---- a Fresh course --------------------------------------------------------------------------

    /** Mon 28 Sep 2026: the weekly set's first day. */
    private static final long SET_DAY = 20_724;
    private static final long STAR_WEEK = 20_724;

    /**
     * A ledger that behaves like the database: a once-only ref pays once; an all-or-nothing reward
     * pays nothing and records nothing when the day's caps ({@link #capLeft}) can't pay all of it;
     * and stars are kept as each set board's best with the week moved by the difference
     * (DailyStars, like addStars).
     */
    private static class DailyLedger implements TrialFinish.Ledger {
        final List<String> calls = new ArrayList<>();
        final Set<String> paidRefs = new HashSet<>();
        final Map<String, Long> boards = new HashMap<>();
        final List<Integer> starsTold = new ArrayList<>();
        ScoreResult today = new ScoreResult(true, null, true, 1);
        int capLeft = Integer.MAX_VALUE;

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
            return paidRefs.contains("first_clear:fresh_parkour_easy");
        }

        @Override
        public int pay(RewardKind kind, String ref, int tokens, String detail) {
            calls.add("pay " + kind + " " + ref + " " + tokens);
            return paidRefs.add(ref) ? tokens : 0;
        }

        @Override
        public int payWhole(RewardKind kind, String ref, int tokens, String detail) {
            calls.add("whole " + kind + " " + ref + " " + tokens + " " + detail);
            return whole(ref, tokens);
        }

        int whole(String ref, int tokens) {
            if (tokens > capLeft || paidRefs.contains(ref)) {
                return 0; // all or nothing: nothing paid, nothing recorded
            }
            paidRefs.add(ref);
            capLeft -= tokens;
            return tokens;
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
            return whole(ref, tokens);
        }
    }

    /** Easy Parkour's layout of the weekly set starting {@code day}: 3 stars at 45 s or less, 2 at 70 s or less. */
    private static GenTag tag(long day, int reroll) {
        return new GenTag("fresh_parkour_easy", "parkour", 1, day, reroll, 0x3f2aL, 'B', "3c9e51aa07b2", 22_500,
                45_000, 70_000, List.of(), List.of(), 1L, 7);
    }

    private static final List<DailyStars.Goal> GOALS = List.of(new DailyStars.Goal(6, 1), new DailyStars.Goal(12, 2));

    /** A run on that layout, finished on calendar day {@code day}, at {@code ms}, paying 2 for the set's first finish. */
    private static TrialFinish.Run dailyRun(GenTag tag, long ms, long day, boolean courseOfWeek, boolean featured) {
        return new TrialFinish.Run("fresh_parkour_easy", "Easy Parkour", ms, day, WEEK, courseOfWeek, featured, 5, 7,
                2, 3, new TrialFinish.Daily(tag, STAR_WEEK, 2, GOALS));
    }

    private static TrialFinish.Run dailyRun(GenTag tag, long ms, boolean courseOfWeek, boolean featured) {
        return dailyRun(tag, ms, DAY, courseOfWeek, featured);
    }

    private static FairPlay.Verdict counted(long ms) {
        return FairPlay.judge(false, null, false, ms, 5, -1);
    }

    @Test
    void aFreshCourseWritesItsSetsBoardOnly() {
        DailyLedger ledger = new DailyLedger();
        TrialFinish.settle(counted(60_000), dailyRun(tag(SET_DAY, 0), 60_000, false, false), ledger);
        List<String> submits = ledger.calls.stream().filter(c -> c.startsWith("submit")).toList();
        assertEquals(List.of("submit " + GenBoards.day(tag(SET_DAY, 0)) + " 60000"), submits,
                "one board: the set's own; never the all-time or the weekly board");
        assertTrue(submits.get(0).startsWith("submit gfresh:fresh_parkour_easy:7:"), "a weekly set's board");
        assertTrue(ledger.calls.contains("announce no week"), "announced without a week's standing");

        DailyLedger rerolled = new DailyLedger();
        TrialFinish.settle(counted(60_000), dailyRun(tag(SET_DAY, 2), 60_000, false, false), rerolled);
        assertTrue(rerolled.calls.contains("submit " + GenBoards.day(tag(SET_DAY, 2)) + " 60000"),
                "a reroll is a fresh board: " + rerolled.calls);
    }

    @Test
    void theFirstFinishOfASetPaysOnceAndNotAgainOnARerollOrAnotherDayOfIt() {
        // acceptance 4: FRESH_CLEAR once per edition; a second finish in the same edition pays nothing
        String ref = SkillRewards.freshClearRef("fresh_parkour_easy", tag(SET_DAY, 0).edition());
        assertEquals(GenBoards.clearRef(tag(SET_DAY, 0)), ref, "the one spelling of the ref, the engine's too");
        DailyLedger ledger = new DailyLedger();
        TrialFinish.Summary first = TrialFinish.settle(counted(60_000), dailyRun(tag(SET_DAY, 0), 60_000, DAY, false,
                false), ledger);
        TrialFinish.Summary nextDay = TrialFinish.settle(counted(50_000), dailyRun(tag(SET_DAY, 0), 50_000, DAY + 1,
                false, false), ledger);
        TrialFinish.Summary reroll = TrialFinish.settle(counted(50_000), dailyRun(tag(SET_DAY, 1), 50_000, DAY + 2,
                false, false), ledger);
        long asked = ledger.calls.stream().filter(c -> c.startsWith("whole DAILY_CLEAR " + ref + " 2 ")).count();
        assertEquals(3, asked, "every counted finish asks, all or nothing, under the one ref of its set: "
                + ledger.calls);
        assertTrue(ledger.calls.contains("whole DAILY_CLEAR " + ref + " 2 Easy Parkour: first finish this week"),
                "the ledger says 'this week', not 'today'");
        assertEquals(2 + 5, first.earned(), "the first pays the set's 2 and the very first finish's 5");
        assertEquals(0, nextDay.earned(), "a second finish a day later in the same set pays nothing more");
        assertEquals(0, reroll.earned(), "a reroll of the set has the same ref: no second one");

        TrialFinish.Summary nextSet = TrialFinish.settle(counted(60_000), dailyRun(tag(SET_DAY + 7, 0), 60_000,
                DAY + 7, false, false), ledger);
        assertTrue(ledger.calls.stream().anyMatch(c -> c.startsWith("whole DAILY_CLEAR "
                + GenBoards.clearRef(tag(SET_DAY + 7, 0)) + " 2")), "the next set has its own ref");
        assertEquals(2, nextSet.earned(), "and pays again (the first clear doesn't)");
    }

    @Test
    void theFirstFinishIsAllOrNothingAndStaysThereForAnotherDayOfTheSet() {
        DailyLedger ledger = new DailyLedger();
        ledger.paidRefs.add("first_clear:fresh_parkour_easy");
        ledger.capLeft = 1;
        TrialFinish.Summary capped = TrialFinish.settle(counted(60_000), dailyRun(tag(SET_DAY, 0), 60_000, DAY, false,
                false), ledger);
        assertEquals(0, capped.earned(), "1 left of today's caps and 2 asked: nothing is paid");
        assertFalse(ledger.paidRefs.contains(GenBoards.clearRef(tag(SET_DAY, 0))), "and nothing recorded");
        ledger.capLeft = 4; // the next day
        TrialFinish.Summary later = TrialFinish.settle(counted(60_000), dailyRun(tag(SET_DAY, 0), 60_000, DAY + 1,
                false, false), ledger);
        assertEquals(2, later.earned(), "another day of the same set pays it whole");
    }

    @Test
    void theFirstClearOfAFreshCourseIsOnceEverPerSlotARecallIncluded() {
        DailyLedger ledger = new DailyLedger();
        for (int w = 0; w < 3; w++) {
            TrialFinish.settle(counted(60_000), dailyRun(tag(SET_DAY + 7L * w, 0), 60_000, false, false), ledger);
        }
        GenTag recalled = tag(SET_DAY, 0).withRecall(new GenTag.Recall("fresh_classic_parkour", 1L, SET_DAY + 14));
        TrialFinish.settle(counted(60_000), new TrialFinish.Run("fresh_classic_parkour", "Classic: Easy Parkour",
                60_000, DAY, WEEK, false, false, 5, 7, 2, 3, new TrialFinish.Daily(recalled, STAR_WEEK, 2, GOALS)),
                ledger);
        long asked = ledger.calls.stream().filter(c -> c.startsWith("pay FIRST_CLEAR first_clear:fresh_parkour_easy "))
                .count();
        assertEquals(4, asked, "the same once-ever ref every set, whatever the layout: " + ledger.calls);
        assertTrue(ledger.calls.stream().noneMatch(c -> c.contains("first_clear:fresh_classic_parkour")),
                "a recall never has a first clear of its own");
    }

    @Test
    void aRecalledCoursePlaysOnItsOriginalBoardAndFirstFinishButItsOwnStars() {
        GenTag original = tag(SET_DAY, 0);
        GenTag recalled = original.withRecall(new GenTag.Recall("fresh_classic_parkour", 1L, SET_DAY + 14));
        DailyLedger ledger = new DailyLedger();
        ledger.paidRefs.add(GenBoards.clearRef(original)); // this player cleared it back then
        TrialFinish.Summary s = TrialFinish.settle(counted(60_000), new TrialFinish.Run("fresh_classic_parkour",
                "Classic: Easy Parkour", 60_000, DAY, WEEK, false, false, 0, 7, 2, 3,
                new TrialFinish.Daily(recalled, STAR_WEEK, 2, GOALS)), ledger);
        assertTrue(ledger.calls.contains("submit " + GenBoards.day(original) + " 60000"),
                "the original board, with its old records: " + ledger.calls);
        assertTrue(ledger.calls.stream().anyMatch(c -> c.startsWith("stars gstars:fresh_classic_parkour:"
                + (SET_DAY + 14) + " ")), "its own stars board, so this week's chart counts them: " + ledger.calls);
        assertEquals(0, s.earned(), "whoever cleared the original isn't paid its first finish again");
        DailyLedger newcomer = new DailyLedger();
        TrialFinish.Summary n = TrialFinish.settle(counted(60_000), new TrialFinish.Run("fresh_classic_parkour",
                "Classic: Easy Parkour", 60_000, DAY, WEEK, false, false, 0, 7, 2, 3,
                new TrialFinish.Daily(recalled, STAR_WEEK, 2, GOALS)), newcomer);
        assertEquals(2, n.earned(), "a new player is paid once, under the original's ref");
    }

    @Test
    void aFreshCourseNeverPaysTheWeeksBest() {
        DailyLedger ledger = new DailyLedger();
        ledger.today = new ScoreResult(true, 70_000L, true, 1);
        TrialFinish.settle(counted(40_000), dailyRun(tag(SET_DAY, 0), 40_000, true, true), ledger);
        assertTrue(ledger.calls.stream().noneMatch(c -> c.contains("WEEKLY_BEST") || c.contains("weekly:")),
                "the Star Chart replaces the week's best on a course that changes every set: " + ledger.calls);
        assertTrue(ledger.calls.contains("pay COURSE_OF_WEEK cotw:" + DAY + " 2"),
                "the course of the week still pays, on the calendar day");
        assertTrue(ledger.calls.contains("pay FEATURED featured:" + DAY + " 3"),
                "and today's pick, on the calendar day");
    }

    @Test
    void starsComeFromTheLayoutsTimesAndOnlyTheDifferenceGoesOnTheWeek() {
        DailyLedger ledger = new DailyLedger();
        TrialFinish.Summary one = TrialFinish.settle(counted(90_000), dailyRun(tag(SET_DAY, 0), 90_000, false,
                false), ledger);
        assertEquals(1, one.stars(), "slower than silver: 1 star for finishing");
        assertEquals(1, one.weekStars(), "the week has 1");
        assertTrue(ledger.calls.contains("stars " + GenBoards.stars(tag(SET_DAY, 0)) + " gweek:" + STAR_WEEK + " 1"),
                "kept on the set's stars board and the run's week: " + ledger.calls);

        TrialFinish.Summary three = TrialFinish.settle(counted(45_000), dailyRun(tag(SET_DAY, 0), 45_000, false,
                false), ledger);
        assertEquals(3, three.stars(), "at the gold time exactly: 3 stars");
        assertEquals(3, three.weekStars(), "the week moves by the difference, 1 to 3");
        assertEquals(2, three.added().added(), "and the summary says what it added");

        TrialFinish.Summary two = TrialFinish.settle(counted(60_000), dailyRun(tag(SET_DAY, 1), 60_000, false,
                false), ledger);
        assertEquals(2, two.stars(), "a slower run on the reroll earns 2");
        assertEquals(3, two.weekStars(), "but the set's best is still 3, so the week doesn't move");
        assertEquals(List.of(1, 3, 2), ledger.starsTold, "each run is told its own stars");
    }

    @Test
    void theStarsGoToTheWeekTheRunIsIn() {
        DailyLedger ledger = new DailyLedger();
        long runWeek = SET_DAY + 7; // a set that began before the week boundary, run after it
        TrialFinish.settle(counted(60_000), new TrialFinish.Run("fresh_parkour_easy", "Easy Parkour", 60_000, DAY,
                WEEK, false, false, 5, 7, 2, 3, new TrialFinish.Daily(tag(SET_DAY, 0), runWeek, 2, GOALS)), ledger);
        assertTrue(ledger.calls.stream().anyMatch(c -> c.startsWith("stars ") && c.contains(" gweek:" + runWeek + " ")),
                "the Star Chart of the run's own week, not of the set's first day: " + ledger.calls);
    }

    @Test
    void eachStarChartGoalPaysItsOwnTokensOnceThroughFreshCourses() {
        DailyLedger ledger = new DailyLedger();
        ledger.boards.put("gweek:" + STAR_WEEK, 4L);
        TrialFinish.Summary s = TrialFinish.settle(counted(40_000), dailyRun(tag(SET_DAY, 0), 40_000, false,
                false), ledger);
        assertTrue(ledger.calls.contains("goal ms:gweek:" + STAR_WEEK + ":6 1 Star Chart: 6★ this week"),
                "4 to 7 reaches 6: paid 1 under the goal's own ref: " + ledger.calls);
        assertEquals(7, s.weekStars(), "the week after the run");
        assertEquals(2 + 5 + 1, s.earned(), "the set's first finish, the very first finish and the goal");

        ledger.boards.put("gweek:" + STAR_WEEK, 10L);
        TrialFinish.Summary top = TrialFinish.settle(counted(40_000), dailyRun(tag(SET_DAY + 7, 0), 40_000, false,
                false), ledger);
        assertTrue(ledger.calls.contains("goal ms:gweek:" + STAR_WEEK + ":12 2 Star Chart: 12★ this week"),
                "10 to 13 reaches 12, which pays its own 2: " + ledger.calls);
        assertEquals(2 + 2, top.earned(), "the new set's first finish and the top goal; the 6 is never paid twice");
    }

    @Test
    void aStarChartGoalTheCapsHeldBackIsPaidWholeByTheNextRunThatWeek() {
        DailyLedger ledger = new DailyLedger();
        ledger.paidRefs.add("first_clear:fresh_parkour_easy");
        ledger.boards.put("gweek:" + STAR_WEEK, 10L);
        ledger.capLeft = 3; // the set's 2 is paid, then 1 left: the 12's 2 can't be paid whole
        TrialFinish.Summary capped = TrialFinish.settle(counted(40_000), dailyRun(tag(SET_DAY, 0), 40_000, false,
                false), ledger);
        assertEquals(2 + 1, capped.earned(), "the set's 2 and the 6's 1; the 12 waits (nothing recorded)");
        assertFalse(ledger.paidRefs.contains("ms:gweek:" + STAR_WEEK + ":12"), "the top goal wasn't used up");
        ledger.capLeft = 4; // the next day
        TrialFinish.Summary later = TrialFinish.settle(counted(40_000), dailyRun(tag(SET_DAY, 0), 40_000, DAY + 1,
                false, false), ledger);
        assertEquals(2, later.earned(), "the next run that week pays the 12 it had reached, whole: " + ledger.calls);
        TrialFinish.Summary third = TrialFinish.settle(counted(40_000), dailyRun(tag(SET_DAY, 0), 40_000, DAY + 2,
                false, false), ledger);
        assertEquals(0, third.earned(), "and only once");
    }

    @Test
    void everySetADailyRunUsesIsItsLayoutsNotTheCalendars() {
        DailyLedger ledger = new DailyLedger();
        TrialFinish.settle(counted(60_000), dailyRun(tag(SET_DAY, 0), 60_000, true, true), ledger);
        assertTrue(ledger.calls.stream().anyMatch(c -> c.startsWith("whole DAILY_CLEAR "
                + GenBoards.clearRef(tag(SET_DAY, 0)) + " 2")),
                "the first-finish reward is the layout's set, even when the calendar has moved on");
        assertTrue(ledger.calls.stream().anyMatch(c -> c.startsWith("stars " + GenBoards.stars(tag(SET_DAY, 0)))),
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
        assertFalse(now.calls.stream().anyMatch(c -> c.contains(GenBoards.DAY_PREFIX) || c.contains("DAILY_CLEAR")),
                "nothing of Fresh Courses");
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
