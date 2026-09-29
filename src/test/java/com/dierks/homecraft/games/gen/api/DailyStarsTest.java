package com.dierks.homecraft.games.gen.api;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Star Chart's arithmetic (GEN-SPEC §5.2, §5.3): a run adds only how far it beat the best so
 * far; each weekly goal is crossed exactly once however the week's total rises — one star at a
 * time or several goals in one jump — and never by a total that didn't rise.
 *
 * <p>And the cadence (weekly addendum §4): the first-finish reward is
 * {@code round(daily + (weekly - daily) * (N - 1) / 6)}, the weekly table from N = 7, pinned for
 * every N from 1 to 28; the goals scale the same way with the nearer end's tokens; and no goal is
 * ever above 80% of what the week can give.
 */
class DailyStarsTest {

    private static final List<Integer> GOALS = List.of(10, 25);

    @Test
    void aRunAddsOnlyHowFarItBeatTheDaysBest() {
        assertEquals(2, DailyStars.delta(null, 2), "the first run adds all its stars");
        assertEquals(1, DailyStars.delta(2L, 3), "a better run adds the difference");
        assertEquals(0, DailyStars.delta(3L, 3), "the same stars add nothing");
        assertEquals(0, DailyStars.delta(3L, 1), "a worse run adds nothing");
        assertEquals(3, DailyStars.delta(null, 7), "never more than three stars a course-day");
    }

    @Test
    void eachGoalIsCrossedOnceWhenTheWeekRisesPastIt() {
        assertEquals(List.of(10), DailyStars.crossed(9, 10, GOALS), "9 to 10 crosses 10");
        assertEquals(List.of(), DailyStars.crossed(10, 11, GOALS), "10 to 11 crosses nothing new");
        assertEquals(List.of(10, 25), DailyStars.crossed(8, 26, GOALS), "one jump can cross both");
        assertEquals(List.of(), DailyStars.crossed(12, 12, GOALS), "a total that didn't rise crosses nothing");
        assertEquals(List.of(), DailyStars.crossed(26, 30, GOALS), "past every goal, nothing more");
        assertEquals(List.of(10, 25), DailyStars.crossed(0, 30, List.of(25, 10, 25)),
                "the list's order and repeats don't matter");
    }

    @Test
    void aWholeWeekOfRunsCrossesEachGoalExactlyOnce() {
        List<Integer> fired = new ArrayList<>();
        long week = 0;
        GenRandom r = new GenRandom(3);
        for (int day = 0; day < 7; day++) {
            for (int course = 0; course < 6; course++) {
                Long best = null;
                for (int run = 0; run < 3; run++) {
                    int stars = r.nextInt(1, 3);
                    int added = DailyStars.delta(best, stars);
                    fired.addAll(DailyStars.crossed(week, week + added, GOALS));
                    week += added;
                    best = best == null ? (long) stars : Math.max(best, stars);
                }
            }
        }
        assertEquals(List.of(10, 25), fired, "a week of play (" + week + " stars) crosses 10 and 25 once each");
    }

    // ---- the cadence (weekly addendum §4) --------------------------------------------------------

    @Test
    void theFirstFinishRewardScalesWithTheCadenceForEveryNFrom1To28() {
        int[][] table = {{1, 2}, {2, 3}, {3, 5}, {2, 3}, {2, 3}, {1, 2}}; // the addendum's daily, weekly
        for (int[] row : table) {
            int daily = row[0];
            int weekly = row[1];
            for (int n = 1; n <= 28; n++) {
                double exact = daily + (weekly - daily) * (Math.min(n, 7) - 1) / 6.0;
                int expected = (int) Math.floor(exact + 0.5);
                assertEquals(expected, DailyStars.scaled(n, daily, weekly), "N=" + n + " between " + daily + " and "
                        + weekly + ": round(" + exact + ")");
                if (n >= 7) {
                    assertEquals(weekly, DailyStars.scaled(n, daily, weekly), "N=" + n + " uses the weekly table");
                }
            }
            assertEquals(daily, DailyStars.scaled(1, daily, weekly), "N=1 is the daily table");
        }
        assertEquals(List.of(3, 3, 4, 4, 4, 5, 5), java.util.stream.IntStream.rangeClosed(1, 7)
                .mapToObj(n -> DailyStars.scaled(n, 3, 5)).toList(), "Hard Parkour from 3 a day to 5 a week");
        assertEquals(List.of(1, 1, 1, 2, 2, 2, 2), java.util.stream.IntStream.rangeClosed(1, 7)
                .mapToObj(n -> DailyStars.scaled(n, 1, 2)).toList(), "Easy Parkour: halfway (N=4) rounds up");
        assertEquals(3, DailyStars.scaled(4, 5, 1), "a daily end above the weekly one works too");
        assertEquals(1, DailyStars.scaled(0, 1, 2), "a cadence below 1 reads as daily");
        assertEquals(2, DailyStars.scaled(99, 1, 2), "and above 28 as 28");
    }

    private static final List<DailyStars.Goal> WEEKLY = List.of(new DailyStars.Goal(6, 1), new DailyStars.Goal(12, 2));
    private static final List<DailyStars.Goal> DAILY = List.of(new DailyStars.Goal(10, 1), new DailyStars.Goal(25, 1));

    private static List<DailyStars.Goal> goals(int... starsThenTokens) {
        List<DailyStars.Goal> out = new ArrayList<>();
        for (int i = 0; i < starsThenTokens.length; i += 2) {
            out.add(new DailyStars.Goal(starsThenTokens[i], starsThenTokens[i + 1]));
        }
        return out;
    }

    @Test
    void theStarGoalsScaleLikeTheRewardsWithTheNearerEndsTokens() {
        assertEquals(WEEKLY, DailyStars.goals(7, DAILY, WEEKLY), "weekly: 6 (+1) and 12 (+2)");
        assertEquals(WEEKLY, DailyStars.goals(14, DAILY, WEEKLY), "longer than a week: the weekly end");
        assertEquals(DAILY, DailyStars.goals(1, DAILY, WEEKLY), "daily: 10 and 25 (+1 each)");
        assertEquals(goals(9, 1, 23, 1), DailyStars.goals(2, DAILY, WEEKLY), "N=2: 9.3 and 22.8, daily tokens");
        assertEquals(goals(9, 1, 21, 1), DailyStars.goals(3, DAILY, WEEKLY), "N=3: 8.7 and 20.7, daily tokens");
        assertEquals(goals(8, 1, 19, 2), DailyStars.goals(4, DAILY, WEEKLY),
                "N=4: 8 and 18.5 (up), halfway takes the weekly tokens");
        assertEquals(goals(7, 1, 16, 2), DailyStars.goals(5, DAILY, WEEKLY), "N=5: weekly tokens");
        assertEquals(goals(7, 1, 14, 2), DailyStars.goals(6, DAILY, WEEKLY), "N=6: weekly tokens");
        assertEquals(DAILY, DailyStars.goals(2, DAILY, List.of(new DailyStars.Goal(6, 1))),
                "ends of different lengths: the nearer end's list as it is");
        assertTrue(DailyStars.nearerWeekly(4) && !DailyStars.nearerWeekly(3), "4 is halfway and counts as weekly");
    }

    @Test
    void goalsAreNeverAbove80PercentOfWhatTheWeekCanGive() {
        assertEquals(18, DailyStars.weekMax(6, 1), "six courses, one weekly edition: 18 stars");
        assertEquals(126, DailyStars.weekMax(6, 7), "daily: 126");
        assertEquals(WEEKLY, DailyStars.clamp(WEEKLY, 18), "weekly goals fit a weekly week (at most 14)");
        assertEquals(DAILY, DailyStars.clamp(DAILY, 126), "daily goals fit a daily week (at most 100)");
        assertEquals(goals(4, 2), DailyStars.clamp(WEEKLY, 6), "two courses on: both goals land on 4, paying the more");
        assertEquals(goals(10, 1, 16, 1), DailyStars.clamp(DAILY, 21), "one course daily: 25 is lowered to 16");
        assertEquals(List.of(), DailyStars.clamp(WEEKLY, 0), "a week that can give nothing has no goals");
        for (int n = 1; n <= 28; n++) {
            for (int courses = 0; courses <= 7; courses++) {
                for (int editions = 0; editions <= 7; editions++) {
                    int max = DailyStars.weekMax(courses, editions);
                    for (DailyStars.Goal g : DailyStars.clamp(DailyStars.goals(n, DAILY, WEEKLY), max)) {
                        assertTrue(g.stars() > 0 && g.stars() * 5 <= max * 4, "N=" + n + ", " + courses + " courses, "
                                + editions + " editions: " + g + " is within 80% of " + max);
                    }
                }
            }
        }
        List<DailyStars.Goal> week = DailyStars.clamp(WEEKLY, 18);
        assertEquals(List.of(6, 12), DailyStars.stars(week), "the stars of each goal, for crossed and nextGoal");
        assertEquals(2, DailyStars.tokens(week, 12), "what 12 pays");
        assertEquals(0, DailyStars.tokens(week, 7), "a number that isn't a goal pays nothing");
    }

    @Test
    void theNextGoalIsTheOneAbove() {
        assertEquals(10, DailyStars.nextGoal(0, GOALS), "from nothing: 10");
        assertEquals(25, DailyStars.nextGoal(10, GOALS), "at 10: 25");
        assertEquals(-1, DailyStars.nextGoal(25, GOALS), "at 25: none left");
        assertEquals(-1, DailyStars.nextGoal(0, null), "no goals: none");
    }
}
