package com.dierks.homecraft.games.golf;

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
 * What a finished golf round records and pays (spec §12, §6.1), pure behind a {@link Ledger} so a
 * test can prove every board and ref: the score goes on the course's board and the standing is
 * announced, then the rewards are paid — the first finish (once ever, {@code first_clear:<id>}),
 * par or better (once per course a day, {@code par:<id>:<day>}), each hole-in-one of the round
 * (once per hole a day, {@code hio:<id>:<hole>:<day>}) and today's pick (once a day across
 * every game).
 *
 * <p><b>A Fresh Courses course</b> (GEN-SPEC §5.2-§5.3, the weekly addendum §4): the score goes on
 * that set's own board ({@code gfresh:<slot>:<edition>}) instead of the all-time one; the round
 * earns 1 to 3 stars (3 at or under par) for the Star Chart of the week the round is in; its first
 * finish of the set pays FRESH_CLEAR ({@code fresh:<slot>:<edition>}, a reroll pays no second
 * one), all or nothing; and par and each hole-in-one are paid once per set
 * ({@code par:<slot>:<edition>}, {@code hio:<slot>:<hole>:<edition>}), not per calendar day. Each
 * Star Chart goal the week has reached pays its own tokens, all or nothing, through Fresh Courses.
 * Today's pick stays on the calendar day, like every game's, and the caps are {@code SkillRewards}'
 * as always. A course recalled into a Classics slot carries its original set's tag, so its board,
 * its first finish, par and holes-in-one are the original set's; its stars are its own board's.
 */
final class GolfFinish {

    private GolfFinish() {
    }

    /** Where a finished round is recorded and paid (the game's scores and rewards, or a test's fake). */
    interface Ledger {

        /** Record {@code strokes} on {@code board} (lower is better). */
        ScoreResult submit(String board, int strokes);

        /**
         * Tell the player how the round stands on its board.
         *
         * @param daily it is a Fresh set's own board ("this week"), not an all-time one
         */
        void announce(ScoreResult result, boolean daily);

        /** Pay a reward; the tokens actually paid. */
        int pay(RewardKind kind, String ref, int tokens, String detail);

        /** Pay a reward all or nothing (a Fresh Courses set's first finish): all of it, or 0 and nothing recorded. */
        default int payWhole(RewardKind kind, String ref, int tokens, String detail) {
            return pay(kind, ref, tokens, detail);
        }

        /** A Fresh course: keep the round's stars as the set's best and add the difference to the week. */
        default GamesDao.StarsAdded addStars(String dayBoard, String weekBoard, int stars) {
            return null;
        }

        /** A Fresh course: tell the player their stars, the next one and the week. */
        default void stars(int stars, GamesDao.StarsAdded added) {
        }

        /** A Fresh course: pay a Star Chart goal through Fresh Courses, all or nothing; the tokens paid. */
        default int payGoal(String ref, int tokens, String detail) {
            return 0;
        }

        /** The round was recorded and paid: tell the quests and achievements (EXTRAS E4). Once, last. */
        default void finished(Round round, Summary summary) {
        }
    }

    /**
     * What a Fresh course's round needs besides.
     *
     * @param tag        the layout the round started on
     * @param weekKey    the Star Chart week the round counts in (the week of its own course day)
     * @param freshClear what the set's first finish pays, by the set's own cadence
     * @param goals      that week's Star Chart goals, each with its own tokens
     */
    record Daily(GenTag tag, long weekKey, int freshClear, List<DailyStars.Goal> goals) {

        Daily {
            goals = goals == null ? List.of() : List.copyOf(goals);
        }

        /** Every goal paying the same {@code goalReward}. */
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
     * The finished round and what it could earn.
     *
     * @param course          the course id
     * @param name            what the reward lines call it ("Mini Golf: Meadow Links")
     * @param strokes         its total
     * @param par             the course's par
     * @param holes           how many holes
     * @param parOrBetter     finished at par or better
     * @param holesInOne      the holes (1-based) played in one
     * @param day             today's calendar day key
     * @param featured        it (or golf) is today's pick
     * @param firstClear      {@code first_clear}
     * @param parReward       {@code par_reward}
     * @param holeInOneReward {@code hole_in_one_reward}
     * @param featuredBonus   {@code games.featured_bonus}
     * @param daily           a Fresh course's part, or {@code null} for a hand-built course
     */
    record Round(String course, String name, int strokes, int par, int holes, boolean parOrBetter,
                 List<Integer> holesInOne, long day, boolean featured, int firstClear, int parReward,
                 int holeInOneReward, int featuredBonus, Daily daily) {

        Round {
            holesInOne = holesInOne == null ? List.of() : List.copyOf(holesInOne);
        }
    }

    /**
     * What it came to.
     *
     * @param result    its standing on the board it went on
     * @param earned    tokens paid
     * @param stars     a Fresh course's stars (0 otherwise)
     * @param weekStars the Star Chart total after it, or -1
     * @param added     what recording the stars did, or {@code null}
     */
    record Summary(ScoreResult result, int earned, int stars, long weekStars, GamesDao.StarsAdded added) {

        Summary(ScoreResult result, int earned, int stars, long weekStars) {
            this(result, earned, stars, weekStars, null);
        }
    }

    /**
     * The board a round on {@code c} goes on: a Fresh set's own ({@link GenBoards#day(GenTag)}: a
     * recalled course's is its original set's), else the course's all-time board.
     */
    static String board(String courseId, GenTag tag) {
        return tag == null ? Scores.golf(courseId) : GenBoards.day(tag);
    }

    /** Record and pay a round that counts, then tell the quests ({@link Ledger#finished}). */
    static Summary settle(Round r, Ledger ledger) {
        Daily daily = r.daily() != null && r.daily().tag() != null ? r.daily() : null;
        GenTag tag = daily == null ? null : daily.tag();
        ScoreResult result = orNone(ledger.submit(board(r.course(), tag), r.strokes()));
        ledger.announce(result, daily != null);
        int stars = 0;
        GamesDao.StarsAdded added = null;
        String weekBoard = daily == null ? null : GenBoards.week(daily.weekKey());
        if (daily != null) {
            stars = Stars.golf(r.strokes(), r.par(), r.holes());
            added = ledger.addStars(GenBoards.stars(tag), weekBoard, stars);
            ledger.stars(stars, added);
        }
        String firstId = tag == null ? r.course() : tag.slot();
        int earned = pay(ledger, RewardKind.FIRST_CLEAR, SkillRewards.firstClearRef(firstId), r.firstClear(),
                r.name() + " first finish");
        if (daily != null && daily.freshClear() > 0) {
            earned += ledger.payWhole(RewardKind.DAILY_CLEAR, SkillRewards.freshClearRef(tag.slot(), tag.edition()),
                    daily.freshClear(), r.name() + " " + GenCopy.firstFinishReason(GenCopy.words(tag)));
        }
        if (r.parOrBetter()) {
            earned += pay(ledger, RewardKind.PAR, tag == null ? SkillRewards.parRef(r.course(), r.day())
                    : SkillRewards.parRef(tag.slot(), tag.edition()), r.parReward(), r.name() + " at par or better");
        }
        for (int hole : r.holesInOne()) {
            String ref = tag == null ? SkillRewards.holeInOneRef(r.course(), hole, r.day())
                    : SkillRewards.holeInOneRef(tag.slot(), hole, tag.edition());
            earned += pay(ledger, RewardKind.HOLE_IN_ONE, ref, r.holeInOneReward(),
                    r.name() + " hole-in-one on hole " + hole);
        }
        if (r.featured()) {
            earned += pay(ledger, RewardKind.FEATURED, SkillRewards.featuredRef(r.day()), r.featuredBonus(),
                    r.name() + " is today's pick");
        }
        if (daily != null && added != null) {
            // every goal reached, not only the one just crossed: one the caps held back pays now
            for (int goal : DailyStars.reached(added.weekTotal(), DailyStars.stars(daily.goals()))) {
                int tokens = DailyStars.tokens(daily.goals(), goal);
                if (tokens > 0) {
                    earned += ledger.payGoal(SkillRewards.milestoneRef(weekBoard, goal), tokens,
                            "Star Chart: " + goal + "★ this week");
                }
            }
        }
        Summary summary = new Summary(result, earned, stars, added == null ? -1 : added.weekTotal(), added);
        ledger.finished(r, summary);
        return summary;
    }

    private static int pay(Ledger ledger, RewardKind kind, String ref, int tokens, String detail) {
        return tokens > 0 ? ledger.pay(kind, ref, tokens, detail) : 0;
    }

    private static ScoreResult orNone(ScoreResult r) {
        return r == null ? ScoreResult.NONE : r;
    }
}
