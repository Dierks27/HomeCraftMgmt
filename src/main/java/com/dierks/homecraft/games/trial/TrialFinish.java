package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.RewardKind;
import com.dierks.homecraft.games.ScoreResult;
import com.dierks.homecraft.games.Scores;
import com.dierks.homecraft.games.SkillRewards;

/**
 * What a finished run records and pays (spec §11, §6.1, R2.15), in the order a player reads it:
 * the time goes onto the course's all-time board and this week's board, the standing is announced
 * (time, best, record), and then the rewards are paid.
 *
 * <p>Only a run that counts gets here at all: a test run, a voided run and a run on a course that
 * changed under it record nothing and pay nothing — which is why the recording and paying are
 * behind a {@link Ledger}, so a test can prove it. The rewards, each through {@code SkillRewards}
 * (the caps and the once-only refs are there):
 * <ul>
 *   <li><b>First clear</b> — the tier's amount, once per course ever ({@code first_clear:<id>});
 *       no cap, and changing the tier later pays nothing more;</li>
 *   <li><b>Best this week</b> — setting the week's best time on the course, once per course per
 *       week ({@code weekly:<id>:<week>});</li>
 *   <li><b>Course of the week</b> — finishing it, once a day across every game ({@code cotw:<day>});</li>
 *   <li><b>Today's pick</b> — the featured bonus, once a day across every game.</li>
 * </ul>
 * A personal best is announced and recorded but pays nothing (§6.1).
 */
final class TrialFinish {

    private TrialFinish() {
    }

    /** Where a counted run is recorded and paid (the game's scores and rewards, or a test's fake). */
    interface Ledger {

        /** Record {@code ms} on {@code board} (lower is better). */
        ScoreResult submit(String board, long ms);

        /** Tell the player how the time stands (between recording and paying). */
        void announce(ScoreResult course, ScoreResult week);

        /** Pay a reward; the tokens actually paid (the caps may hold some back). */
        int pay(RewardKind kind, String ref, int tokens, String detail);
    }

    /**
     * The finished run and what it could earn.
     *
     * @param course       the course's id
     * @param name         the course's name (for the reward lines)
     * @param ms           the run's time
     * @param day          today's local day key
     * @param weekKey      this week's key
     * @param courseOfWeek whether it is this week's course
     * @param featured     whether it is today's featured pick
     * @param firstClear   the tier's first-clear reward
     * @param weeklyBest   {@code weekly_best_bonus}
     * @param weekBonus    {@code course_of_week_bonus}
     * @param featuredBonus {@code games.featured_bonus}
     */
    record Run(String course, String name, long ms, long day, long weekKey, boolean courseOfWeek, boolean featured,
               int firstClear, int weeklyBest, int weekBonus, int featuredBonus) {
    }

    /**
     * What it came to.
     *
     * @param course its standing on the all-time board ({@link ScoreResult#NONE} if not recorded)
     * @param week   its standing on this week's board
     * @param earned tokens paid
     */
    record Summary(ScoreResult course, ScoreResult week, int earned) {

        static final Summary NONE = new Summary(ScoreResult.NONE, ScoreResult.NONE, 0);
    }

    /** Record and pay {@code run} if the verdict says it counts; otherwise touch nothing. */
    static Summary settle(FairPlay.Verdict verdict, Run run, Ledger ledger) {
        if (verdict == null || !verdict.counts()) {
            return Summary.NONE;
        }
        ScoreResult course = orNone(ledger.submit(Scores.course(run.course()), run.ms()));
        ScoreResult week = orNone(ledger.submit(Scores.week(run.course(), run.weekKey()), run.ms()));
        ledger.announce(course, week);
        int earned = 0;
        if (run.firstClear() > 0) {
            earned += ledger.pay(RewardKind.FIRST_CLEAR, SkillRewards.firstClearRef(run.course()), run.firstClear(),
                    run.name() + ": first finish");
        }
        if (week.record() && run.weeklyBest() > 0) {
            earned += ledger.pay(RewardKind.WEEKLY_BEST, SkillRewards.weeklyRef(run.course(), run.weekKey()),
                    run.weeklyBest(), run.name() + ": best time this week");
        }
        if (run.courseOfWeek() && run.weekBonus() > 0) {
            earned += ledger.pay(RewardKind.COURSE_OF_WEEK, SkillRewards.courseOfWeekRef(run.day()), run.weekBonus(),
                    run.name() + ": course of the week");
        }
        if (run.featured() && run.featuredBonus() > 0) {
            earned += ledger.pay(RewardKind.FEATURED, SkillRewards.featuredRef(run.day()), run.featuredBonus(),
                    run.name() + ": today's pick");
        }
        return new Summary(course, week, earned);
    }

    private static ScoreResult orNone(ScoreResult r) {
        return r == null ? ScoreResult.NONE : r;
    }
}
