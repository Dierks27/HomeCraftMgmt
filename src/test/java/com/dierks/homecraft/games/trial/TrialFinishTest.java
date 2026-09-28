package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.RewardKind;
import com.dierks.homecraft.games.ScoreResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * ref — and never a reward of 0; and what was earned is what the ledger actually paid.
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

        @Override
        public ScoreResult submit(String board, long ms) {
            calls.add("submit " + board + " " + ms);
            return board.startsWith("course:") ? course : week;
        }

        @Override
        public void announce(ScoreResult c, ScoreResult w) {
            calls.add("announce");
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
}
