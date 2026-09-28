package com.dierks.homecraft.games.cabinet.simon;

import com.dierks.homecraft.games.ScoreResult;
import com.dierks.homecraft.games.Scores;
import com.dierks.homecraft.games.cabinet.CabinetGame;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Simon Says's settings and finish lines, without a server.
 *
 * <p>Pinned here: the milestones belong to the {@code classic} board only; longer patterns reach
 * more of them; the daily goal (8) sits between bronze and silver; a best says what it beat and a
 * scored daily try shows its place today instead.
 */
class SimonSaysRulesTest {

    @Test
    void onlyTheClassicBoardHasMilestones() {
        SimonSaysSettings s = SimonSaysSettings.defaults();
        assertEquals(List.of(5, 10, 15), s.milestonesFor(Scores.CLASSIC), "bronze, silver, gold in pads");
        assertTrue(s.milestonesFor(Scores.daily(20_000)).isEmpty(), "daily boards have no milestones");
    }

    @Test
    void longerPatternsReachMoreMilestones() {
        List<Integer> ladder = SimonSaysSettings.defaults().milestonesFor(Scores.CLASSIC);
        assertEquals(List.of(1), CabinetGame.milestonesReached(ladder, SimonSays.DAILY_GOAL, SimonSays.LOWER_IS_BETTER),
                "the daily goal is past bronze, short of silver");
        assertEquals(List.of(1, 2, 3), CabinetGame.milestonesReached(ladder, 15, false), "15 reaches gold");
        assertTrue(SimonSays.DAILY_GOAL < SimonEngine.MAX, "the daily goal can be reached");
    }

    @Test
    void bestsAndDailyPlacesReadDifferently() {
        assertTrue(SimonSays.standing(new ScoreResult(true, 7L, false, 2)).contains("was 7"), "a best names the old one");
        String today = SimonSays.dayStanding(new ScoreResult(true, null, true, 1));
        assertTrue(today.contains("Top score today") && !today.contains("New best"), "a daily try's place: " + today);
        assertFalse(SimonSays.standing(ScoreResult.NONE).contains("best"), "nothing recorded, nothing to add");
    }
}
