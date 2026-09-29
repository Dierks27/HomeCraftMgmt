package com.dierks.homecraft.games.gen.api;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The Star Chart's arithmetic (GEN-SPEC §5.2, §5.3): a run adds only how far it beat the day's
 * best; each weekly goal (10 and 25) is crossed exactly once however the week's total rises —
 * one star at a time or several goals in one jump — and never by a total that didn't rise.
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

    @Test
    void theNextGoalIsTheOneAbove() {
        assertEquals(10, DailyStars.nextGoal(0, GOALS), "from nothing: 10");
        assertEquals(25, DailyStars.nextGoal(10, GOALS), "at 10: 25");
        assertEquals(-1, DailyStars.nextGoal(25, GOALS), "at 25: none left");
        assertEquals(-1, DailyStars.nextGoal(0, null), "no goals: none");
    }

    @Test
    void everyGoalTheWeekHasReachedIsOfferedSmallestFirst() {
        assertEquals(List.of(), DailyStars.reached(9, GOALS), "under 10: none");
        assertEquals(List.of(10), DailyStars.reached(10, GOALS), "at 10: the 10");
        assertEquals(List.of(10, 25), DailyStars.reached(30, List.of(25, 10, 25)), "past both: each once, smallest first");
        assertEquals(List.of(), DailyStars.reached(30, null), "no goals: none");
    }
}
