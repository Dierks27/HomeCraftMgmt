package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.RewardKind;
import com.dierks.homecraft.games.ScoreResult;
import com.dierks.homecraft.games.Scores;
import com.dierks.homecraft.games.SkillRewards;
import com.dierks.homecraft.games.gen.api.DailyStars;
import com.dierks.homecraft.games.gen.api.GenBoards;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Stars;
import com.dierks.homecraft.storage.GamesDao;

import java.util.List;

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
 *
 * <p><b>A daily course</b> (Daily Courses made it, GEN-SPEC §5.2-§5.3) changes layout every day,
 * so an all-time or a weekly time on it means nothing. Its time goes on that layout's own board
 * ({@code gday:<id>:<edition>}) only; the run earns 1 to 3 stars, kept as the player's best that
 * course day and added to the week's Star Chart by the difference; and "best this week" is
 * replaced by the first finish of that course day ({@code DAILY_CLEAR}, {@code dclear:<id>:<day>},
 * so an admin's reroll pays no second one). The first clear stays once ever, and the course of
 * the week and today's pick pay as for any course. A Star Chart goal the run crosses is paid by
 * the {@code daily} game. Every day here is the day of the layout the run started on, never the
 * calendar's (only the caps use the calendar day, inside {@code SkillRewards}).
 */
final class TrialFinish {

    private TrialFinish() {
    }

    /** Where a counted run is recorded and paid (the game's scores and rewards, or a test's fake). */
    interface Ledger {

        /** Record {@code ms} on {@code board} (lower is better). */
        ScoreResult submit(String board, long ms);

        /**
         * Tell the player how the time stands (between recording and paying). For a daily course
         * {@code course} is its standing on the layout's own board and {@code week} is
         * {@link ScoreResult#NONE}.
         *
         * @param firstFinish the course's first-clear reward is about to be paid for this run
         */
        void announce(ScoreResult course, ScoreResult week, boolean firstFinish);

        /** Whether the player had the course's first-clear reward before this run. */
        boolean firstClearPaid();

        /** Pay a reward; the tokens actually paid (the caps may hold some back). */
        int pay(RewardKind kind, String ref, int tokens, String detail);

        /**
         * A daily course: keep the run's {@code stars} as the best that course day and add the
         * difference to the week ({@code GamesDao.addStars}, one transaction). {@code null} when
         * it couldn't be recorded.
         */
        default GamesDao.StarsAdded addStars(String dayBoard, String weekBoard, int stars) {
            return null;
        }

        /** A daily course: tell the player their stars, what the next one needs, and the week. */
        default void stars(int stars, GamesDao.StarsAdded added) {
        }

        /**
         * A daily course: pay a Star Chart goal through the {@code daily} game (nothing when it
         * isn't there). The tokens actually paid.
         */
        default int payGoal(String ref, int tokens, String detail) {
            return 0;
        }
    }

    /**
     * What a daily course's finish needs beyond a course's (GEN-SPEC §5.2-§5.3).
     *
     * @param tag        the layout the run started on (its day, reroll and star times)
     * @param weekKey    the Star Chart week of the layout's day
     * @param dailyClear the slot's {@code daily_clear}
     * @param goals      the week's star goals ({@code star_goals})
     * @param goalReward tokens per goal ({@code star_goal_reward})
     */
    record Daily(GenTag tag, long weekKey, int dailyClear, List<Integer> goals, int goalReward) {

        Daily {
            goals = goals == null ? List.of() : List.copyOf(goals);
        }
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
     * @param daily        what a daily course adds, or {@code null} for a hand-built course
     */
    record Run(String course, String name, long ms, long day, long weekKey, boolean courseOfWeek, boolean featured,
               int firstClear, int weeklyBest, int weekBonus, int featuredBonus, Daily daily) {

        /** A hand-built course's run. */
        Run(String course, String name, long ms, long day, long weekKey, boolean courseOfWeek, boolean featured,
            int firstClear, int weeklyBest, int weekBonus, int featuredBonus) {
            this(course, name, ms, day, weekKey, courseOfWeek, featured, firstClear, weeklyBest, weekBonus,
                    featuredBonus, null);
        }
    }

    /**
     * What it came to.
     *
     * @param course    its standing on the all-time board, or a daily course's day board
     *                  ({@link ScoreResult#NONE} if not recorded)
     * @param week      its standing on this week's board (NONE for a daily course)
     * @param earned    tokens paid
     * @param stars     a daily course's stars (0 otherwise)
     * @param weekStars the Star Chart total after it, or -1 (not a daily course, or not recorded)
     */
    record Summary(ScoreResult course, ScoreResult week, int earned, int stars, long weekStars) {

        static final Summary NONE = new Summary(ScoreResult.NONE, ScoreResult.NONE, 0, 0, -1);

        Summary(ScoreResult course, ScoreResult week, int earned) {
            this(course, week, earned, 0, -1);
        }
    }

    /** Record and pay {@code run} if the verdict says it counts; otherwise touch nothing. */
    static Summary settle(FairPlay.Verdict verdict, Run run, Ledger ledger) {
        if (verdict == null || !verdict.counts()) {
            return Summary.NONE;
        }
        if (run.daily() != null && run.daily().tag() != null) {
            return settleDaily(run, run.daily(), ledger);
        }
        ScoreResult course = orNone(ledger.submit(Scores.course(run.course()), run.ms()));
        ScoreResult week = orNone(ledger.submit(Scores.week(run.course(), run.weekKey()), run.ms()));
        boolean firstFinish = run.firstClear() > 0 && !ledger.firstClearPaid();
        ledger.announce(course, week, firstFinish);
        int earned = firstClear(run, ledger);
        if (week.record() && run.weeklyBest() > 0) {
            earned += ledger.pay(RewardKind.WEEKLY_BEST, SkillRewards.weeklyRef(run.course(), run.weekKey()),
                    run.weeklyBest(), run.name() + ": best time this week");
        }
        earned += acrossGames(run, ledger);
        return new Summary(course, week, earned);
    }

    /** A daily course: its layout's board, its stars, then the rewards and any Star Chart goal crossed. */
    private static Summary settleDaily(Run run, Daily daily, Ledger ledger) {
        GenTag tag = daily.tag();
        ScoreResult today = orNone(ledger.submit(GenBoards.day(run.course(), tag.editionKey()), run.ms()));
        boolean firstFinish = run.firstClear() > 0 && !ledger.firstClearPaid();
        ledger.announce(today, ScoreResult.NONE, firstFinish);
        int stars = Stars.trial(run.ms(), tag.goldMs(), tag.silverMs());
        String weekBoard = GenBoards.week(daily.weekKey());
        GamesDao.StarsAdded added = ledger.addStars(GenBoards.stars(run.course(), tag.day()), weekBoard, stars);
        ledger.stars(stars, added);
        int earned = firstClear(run, ledger);
        if (daily.dailyClear() > 0) {
            earned += ledger.pay(RewardKind.DAILY_CLEAR, SkillRewards.dailyClearRef(run.course(), tag.day()),
                    daily.dailyClear(), run.name() + ": first finish today");
        }
        earned += acrossGames(run, ledger);
        earned += goals(added, daily.goals(), daily.goalReward(), weekBoard, ledger::payGoal);
        return new Summary(today, ScoreResult.NONE, earned, stars, added == null ? -1 : added.weekTotal());
    }

    /** Pays one goal: its ref, its tokens and its line; the tokens actually paid. */
    interface GoalPay {
        int pay(String ref, int tokens, String detail);
    }

    /**
     * Offer every Star Chart goal the week has reached ({@link DailyStars#reached}), smallest
     * first, each under {@code ms:gweek:<week>:<goal>} so it is paid once a week whatever order the
     * goals are listed in and whichever run pays it: a goal crossed while the day's caps were full
     * pays nothing and records nothing, so the next counted run that week pays it. Nothing when the
     * stars weren't recorded or a goal pays nothing.
     *
     * @return the tokens paid
     */
    static int goals(GamesDao.StarsAdded added, List<Integer> goals, int reward, String weekBoard, GoalPay pay) {
        if (added == null || reward <= 0) {
            return 0;
        }
        int earned = 0;
        for (int goal : DailyStars.reached(added.weekTotal(), goals)) {
            earned += pay.pay(SkillRewards.milestoneRef(weekBoard, goal), reward, "Star Chart: " + goal + "★ this week");
        }
        return earned;
    }

    private static int firstClear(Run run, Ledger ledger) {
        if (run.firstClear() <= 0) {
            return 0;
        }
        return ledger.pay(RewardKind.FIRST_CLEAR, SkillRewards.firstClearRef(run.course()), run.firstClear(),
                run.name() + ": first finish");
    }

    /** The course of the week and today's pick: once a day across every game, on the calendar day. */
    private static int acrossGames(Run run, Ledger ledger) {
        int earned = 0;
        if (run.courseOfWeek() && run.weekBonus() > 0) {
            earned += ledger.pay(RewardKind.COURSE_OF_WEEK, SkillRewards.courseOfWeekRef(run.day()), run.weekBonus(),
                    run.name() + ": course of the week");
        }
        if (run.featured() && run.featuredBonus() > 0) {
            earned += ledger.pay(RewardKind.FEATURED, SkillRewards.featuredRef(run.day()), run.featuredBonus(),
                    run.name() + ": today's pick");
        }
        return earned;
    }

    private static ScoreResult orNone(ScoreResult r) {
        return r == null ? ScoreResult.NONE : r;
    }
}
