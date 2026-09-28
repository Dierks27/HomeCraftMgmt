package com.dierks.homecraft.games.cabinet.whack;

import com.dierks.homecraft.games.ScoreResult;
import com.dierks.homecraft.games.Scores;
import com.dierks.homecraft.games.cabinet.CabinetGame;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whack-a-Zombie's settings and finish lines, without a server.
 *
 * <p>Pinned here: the milestones belong to the {@code classic} board only; the daily goal (20)
 * sits between bronze and silver; the shipped round is 30 seconds; a best says what it beat and a
 * scored daily try shows its place today instead.
 */
class WhackAZombieRulesTest {

    @Test
    void onlyTheClassicBoardHasMilestones() {
        WhackAZombieSettings s = WhackAZombieSettings.defaults();
        assertEquals(List.of(15, 25, 35), s.milestonesFor(Scores.CLASSIC), "bronze, silver, gold in points");
        assertTrue(s.milestonesFor(Scores.daily(20_000)).isEmpty(), "daily boards have no milestones");
        assertEquals(30, s.seconds(), "a shipped round is 30 seconds");
    }

    @Test
    void theDailyGoalIsPastBronze() {
        List<Integer> ladder = WhackAZombieSettings.defaults().milestonesFor(Scores.CLASSIC);
        assertEquals(List.of(1), CabinetGame.milestonesReached(ladder, WhackAZombie.DAILY_GOAL,
                WhackAZombie.LOWER_IS_BETTER), "20 points is bronze, not yet silver");
    }

    @Test
    void bestsAndDailyPlacesReadDifferently() {
        assertTrue(WhackAZombie.standing(new ScoreResult(true, 22L, false, 2)).contains("was 22"),
                "a best names the old one");
        assertTrue(WhackAZombie.dayStanding(new ScoreResult(true, null, false, 4)).contains("#4"), "fourth today");
        assertEquals("", WhackAZombie.dayStanding(ScoreResult.NONE), "nothing recorded, nothing to add");
    }
}
