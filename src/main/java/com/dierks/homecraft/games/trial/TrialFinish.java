package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.RewardKind;
import com.dierks.homecraft.games.ScoreResult;
import com.dierks.homecraft.games.Scores;
import com.dierks.homecraft.games.SkillRewards;
import com.dierks.homecraft.games.gen.api.DailyStars;
import com.dierks.homecraft.games.gen.api.GenBoards;
import com.dierks.homecraft.games.gen.api.GenCopy;
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
 * <p><b>A Fresh Courses course</b> (GEN-SPEC §5.2-§5.3, the weekly addendum §4, GEN-SPEC-KEEP §3)
 * changes layout every set (a week as shipped), so an all-time or a weekly time on it means
 * nothing. Its time goes on that set's own board ({@code gfresh:<slot>:<edition>}) only; the run
 * earns 1 to 3 stars, kept as the player's best in that set ({@code gstars:<slot>:<edition>}) and
 * added to the Star Chart of the week the run is in by the difference; and "best this week" is
 * replaced by the first finish of that course in that set (FRESH_CLEAR, {@code fresh:<slot>:<edition>},
 * so an admin's reroll pays no second one), paid <b>all or nothing</b>: a day whose caps can't pay
 * all of it pays none and records none, so another day of the set still can. The first clear stays
 * once ever per slot ({@code first_clear:<slot>}), and the course of the week and today's pick pay
 * as for any course. Each Star Chart goal the week has reached pays its own tokens, all or nothing,
 * through Fresh Courses. Every set here is the one of the layout the run started on, never the
 * calendar's (only the caps use the calendar day, inside {@code SkillRewards}).
 *
 * <p>A course recalled into a Classics slot carries its ORIGINAL set's tag: its time goes on the
 * original board (its old records are the ones to beat), and its first-finish refs are the
 * original's, so whoever cleared it back then isn't paid again; its stars are its own board's
 * ({@link GenBoards#stars(GenTag)}), so they count toward this week's chart.
 */
final class TrialFinish {

    private TrialFinish() {
    }

    /** Where a counted run is recorded and paid (the game's scores and rewards, or a test's fake). */
    interface Ledger {

        /** Record {@code ms} on {@code board} (lower is better). */
        ScoreResult submit(String board, long ms);

        /**
         * Tell the player how the time stands (between recording and paying). For a Fresh course
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
         * Pay a reward all or nothing (a Fresh Courses set's first finish): all of {@code tokens},
         * or 0 with nothing recorded when today's caps can't pay all of it.
         */
        default int payWhole(RewardKind kind, String ref, int tokens, String detail) {
            return pay(kind, ref, tokens, detail);
        }

        /**
         * A Fresh course: keep the run's {@code stars} as the best in its set and add the
         * difference to the week ({@code GamesDao.addStars}, one transaction). {@code null} when
         * it couldn't be recorded.
         */
        default GamesDao.StarsAdded addStars(String dayBoard, String weekBoard, int stars) {
            return null;
        }

        /** A Fresh course: tell the player their stars, what the next one needs, and the week. */
        default void stars(int stars, GamesDao.StarsAdded added) {
        }

        /**
         * A Fresh course: pay a Star Chart goal through Fresh Courses, all or nothing (nothing when
         * it isn't there). The tokens actually paid.
         */
        default int payGoal(String ref, int tokens, String detail) {
            return 0;
        }

        /**
         * The run was recorded and paid: tell the quests and achievements (EXTRAS E4). Once per
         * counted run, last; never for a test, voided or stale run.
         */
        default void finished(Run run, Summary summary) {
        }
    }

    /**
     * What a Fresh course's finish needs beyond a course's (GEN-SPEC §5.2-§5.3, weekly addendum §4).
     *
     * @param tag        the layout the run started on (its slot, set, reroll and star times)
     * @param weekKey    the Star Chart week the run counts in: the week of the run's own course day
     *                   (not of its set's first day)
     * @param freshClear what the set's first finish pays, by the set's own cadence
     *                   ({@code dailyClear(tag.slot(), tag.cadence())})
     * @param goals      that week's Star Chart goals, each with its own tokens (fixed for the week)
     */
    record Daily(GenTag tag, long weekKey, int freshClear, List<DailyStars.Goal> goals) {

        Daily {
            goals = goals == null ? List.of() : List.copyOf(goals);
        }

        /** Every goal paying the same {@code goalReward} (the shape before each goal had its own tokens). */
        Daily(GenTag tag, long weekKey, int freshClear, List<Integer> goals, int goalReward) {
            this(tag, weekKey, freshClear, sameTokens(goals, goalReward));
        }

        private static List<DailyStars.Goal> sameTokens(List<Integer> goals, int tokens) {
            List<DailyStars.Goal> out = new java.util.ArrayList<>();
            for (Integer g : goals == null ? List.<Integer>of() : goals) {
                if (g != null && g > 0) {
                    out.add(new DailyStars.Goal(g, Math.max(0, tokens)));
                }
            }
            return out;
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
     * @param daily        what a Fresh course adds, or {@code null} for a hand-built course
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
     * @param course    its standing on the all-time board, or a Fresh course's set board
     *                  ({@link ScoreResult#NONE} if not recorded)
     * @param week      its standing on this week's board (NONE for a Fresh course)
     * @param earned    tokens paid
     * @param stars     a Fresh course's stars (0 otherwise)
     * @param weekStars the Star Chart total after it, or -1 (not a Fresh course, or not recorded)
     * @param added     what recording the stars did, or {@code null} (not a Fresh course, or not recorded)
     */
    record Summary(ScoreResult course, ScoreResult week, int earned, int stars, long weekStars,
                   GamesDao.StarsAdded added) {

        static final Summary NONE = new Summary(ScoreResult.NONE, ScoreResult.NONE, 0, 0, -1, null);

        Summary(ScoreResult course, ScoreResult week, int earned) {
            this(course, week, earned, 0, -1, null);
        }
    }

    /**
     * Record and pay {@code run} if the verdict says it counts, then tell the quests
     * ({@link Ledger#finished}); otherwise touch nothing.
     */
    static Summary settle(FairPlay.Verdict verdict, Run run, Ledger ledger) {
        if (verdict == null || !verdict.counts()) {
            return Summary.NONE;
        }
        Summary summary = run.daily() != null && run.daily().tag() != null ? settleDaily(run, run.daily(), ledger)
                : settleCourse(run, ledger);
        ledger.finished(run, summary);
        return summary;
    }

    /** A hand-built course: its all-time and week boards, then the rewards. */
    private static Summary settleCourse(Run run, Ledger ledger) {
        ScoreResult course = orNone(ledger.submit(Scores.course(run.course()), run.ms()));
        ScoreResult week = orNone(ledger.submit(Scores.week(run.course(), run.weekKey()), run.ms()));
        boolean firstFinish = run.firstClear() > 0 && !ledger.firstClearPaid();
        ledger.announce(course, week, firstFinish);
        int earned = firstClear(run, run.course(), ledger);
        if (week.record() && run.weeklyBest() > 0) {
            earned += ledger.pay(RewardKind.WEEKLY_BEST, SkillRewards.weeklyRef(run.course(), run.weekKey()),
                    run.weeklyBest(), run.name() + ": best time this week");
        }
        earned += acrossGames(run, ledger);
        return new Summary(course, week, earned);
    }

    /** A Fresh course: its set's board, its stars, then the rewards and any Star Chart goal reached. */
    private static Summary settleDaily(Run run, Daily daily, Ledger ledger) {
        GenTag tag = daily.tag();
        ScoreResult set = orNone(ledger.submit(GenBoards.day(tag), run.ms()));
        boolean firstFinish = run.firstClear() > 0 && !ledger.firstClearPaid();
        ledger.announce(set, ScoreResult.NONE, firstFinish);
        int stars = Stars.trial(run.ms(), tag.goldMs(), tag.silverMs());
        String weekBoard = GenBoards.week(daily.weekKey());
        GamesDao.StarsAdded added = ledger.addStars(GenBoards.stars(tag), weekBoard, stars);
        ledger.stars(stars, added);
        int earned = firstClear(run, tag.slot(), ledger);
        if (daily.freshClear() > 0) {
            earned += ledger.payWhole(RewardKind.DAILY_CLEAR, SkillRewards.freshClearRef(tag.slot(), tag.edition()),
                    daily.freshClear(), run.name() + ": " + GenCopy.firstFinishReason(GenCopy.words(tag)));
        }
        earned += acrossGames(run, ledger);
        earned += goals(added, daily.goals(), weekBoard, ledger::payGoal);
        return new Summary(set, ScoreResult.NONE, earned, stars, added == null ? -1 : added.weekTotal(), added);
    }

    /** Pays one goal: its ref, its tokens and its line; the tokens actually paid. */
    interface GoalPay {
        int pay(String ref, int tokens, String detail);
    }

    /**
     * Offer every Star Chart goal the week has reached ({@link DailyStars#reached}), smallest
     * first, each its own tokens under {@code ms:gweek:<week>:<goal>}, so it is paid once a week
     * whatever order the goals are listed in and whichever run pays it: a goal reached while the
     * day's caps couldn't pay it whole pays nothing and records nothing, so the next counted run
     * that week pays it. Nothing when the stars weren't recorded; a goal paying 0 is skipped.
     *
     * @return the tokens paid
     */
    static int goals(GamesDao.StarsAdded added, List<DailyStars.Goal> goals, String weekBoard, GoalPay pay) {
        if (added == null || goals == null) {
            return 0;
        }
        int earned = 0;
        for (int goal : DailyStars.reached(added.weekTotal(), DailyStars.stars(goals))) {
            int tokens = DailyStars.tokens(goals, goal);
            if (tokens > 0) {
                earned += pay.pay(SkillRewards.milestoneRef(weekBoard, goal), tokens,
                        "Star Chart: " + goal + "★ this week");
            }
        }
        return earned;
    }

    /**
     * The first clear, once ever per {@code id}: the course's own, or a Fresh course's slot (so a
     * recalled course never adds a first clear beyond its slot's usual one).
     */
    private static int firstClear(Run run, String id, Ledger ledger) {
        if (run.firstClear() <= 0) {
            return 0;
        }
        return ledger.pay(RewardKind.FIRST_CLEAR, SkillRewards.firstClearRef(id), run.firstClear(),
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
