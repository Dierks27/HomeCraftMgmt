package com.dierks.homecraft.games.golf;

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
 * What a finished golf round records and pays (spec §12, §6.1), pure behind a {@link Ledger} so a
 * test can prove every board and ref: the score goes on the course's board and the standing is
 * announced, then the rewards are paid — the first finish (once ever, {@code first_clear:<id>}),
 * par or better (once per course a day, {@code par:<id>:<day>}), each hole-in-one of the round
 * (once per hole a day, {@code hio:<id>:<hole>:<day>}) and today's pick (once a day across
 * every game).
 *
 * <p><b>A daily course</b> (Daily Courses made it, GEN-SPEC §5.2-§5.3): the score goes on that
 * layout's own board ({@code gday:<id>:<edition>}) instead of the all-time one; the round earns 1 to
 * 3 stars (3 at or under par) for the Star Chart; its first finish of the course day pays
 * {@code DAILY_CLEAR} ({@code dclear:<id>:<day>}, a reroll pays no second one); and par and a
 * hole-in-one are paid on the layout's course day, not the calendar's. A Star Chart goal the round
 * crosses is paid by the {@code daily} game. Today's pick stays on the calendar day, like every
 * game's, and the caps are {@code SkillRewards}' as always.
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
         * @param daily it is a daily course's layout board ("today"), not an all-time one
         */
        void announce(ScoreResult result, boolean daily);

        /** Pay a reward; the tokens actually paid. */
        int pay(RewardKind kind, String ref, int tokens, String detail);

        /** A daily course: keep the round's stars as the day's best and add the difference to the week. */
        default GamesDao.StarsAdded addStars(String dayBoard, String weekBoard, int stars) {
            return null;
        }

        /** A daily course: tell the player their stars, the next one and the week. */
        default void stars(int stars, GamesDao.StarsAdded added) {
        }

        /** A daily course: pay a Star Chart goal through the {@code daily} game; the tokens paid. */
        default int payGoal(String ref, int tokens, String detail) {
            return 0;
        }
    }

    /**
     * What a daily course's round needs besides.
     *
     * @param tag        the layout the round started on
     * @param weekKey    the Star Chart week of its day
     * @param dailyClear the slot's {@code daily_clear}
     * @param goals      the week's star goals
     * @param goalReward tokens per goal
     */
    record Daily(GenTag tag, long weekKey, int dailyClear, List<Integer> goals, int goalReward) {

        Daily {
            goals = goals == null ? List.of() : List.copyOf(goals);
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
     * @param daily           a daily course's part, or {@code null} for a hand-built course
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
     * @param stars     a daily course's stars (0 otherwise)
     * @param weekStars the Star Chart total after it, or -1
     */
    record Summary(ScoreResult result, int earned, int stars, long weekStars) {
    }

    /** The board a round on {@code c} goes on: a daily layout's own, else the course's all-time board. */
    static String board(String courseId, GenTag tag) {
        return tag == null ? Scores.golf(courseId) : GenBoards.day(courseId, tag.editionKey());
    }

    /** Record and pay a round that counts. */
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
            added = ledger.addStars(GenBoards.stars(r.course(), tag.day()), weekBoard, stars);
            ledger.stars(stars, added);
        }
        long rewardDay = tag == null ? r.day() : tag.day();
        int earned = pay(ledger, RewardKind.FIRST_CLEAR, SkillRewards.firstClearRef(r.course()), r.firstClear(),
                r.name() + " first finish");
        if (daily != null) {
            earned += pay(ledger, RewardKind.DAILY_CLEAR, SkillRewards.dailyClearRef(r.course(), tag.day()),
                    daily.dailyClear(), r.name() + " first finish today");
        }
        if (r.parOrBetter()) {
            earned += pay(ledger, RewardKind.PAR, SkillRewards.parRef(r.course(), rewardDay), r.parReward(),
                    r.name() + " at par or better");
        }
        for (int hole : r.holesInOne()) {
            earned += pay(ledger, RewardKind.HOLE_IN_ONE, SkillRewards.holeInOneRef(r.course(), hole, rewardDay),
                    r.holeInOneReward(), r.name() + " hole-in-one on hole " + hole);
        }
        if (r.featured()) {
            earned += pay(ledger, RewardKind.FEATURED, SkillRewards.featuredRef(r.day()), r.featuredBonus(),
                    r.name() + " is today's pick");
        }
        if (daily != null && added != null && daily.goalReward() > 0) {
            // every goal reached, not only the one just crossed: one the caps held back pays now
            for (int goal : DailyStars.reached(added.weekTotal(), daily.goals())) {
                earned += ledger.payGoal(SkillRewards.milestoneRef(weekBoard, goal), daily.goalReward(),
                        "Star Chart: " + goal + "★ this week");
            }
        }
        return new Summary(result, earned, stars, added == null ? -1 : added.weekTotal());
    }

    private static int pay(Ledger ledger, RewardKind kind, String ref, int tokens, String detail) {
        return tokens > 0 ? ledger.pay(kind, ref, tokens, detail) : 0;
    }

    private static ScoreResult orNone(ScoreResult r) {
        return r == null ? ScoreResult.NONE : r;
    }
}
