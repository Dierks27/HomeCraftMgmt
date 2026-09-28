package com.dierks.homecraft.games.cabinet.match;

import com.dierks.homecraft.games.ScoreResult;
import com.dierks.homecraft.games.Scores;
import com.dierks.homecraft.games.cabinet.CabinetGame;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mini Match's settings and finish lines, without a server.
 *
 * <p>Pinned here: the milestones belong to the {@code classic} board only (daily boards have none,
 * spec §10b); fewer flips reach more of them; the daily goal (24) is the silver line, reachable by
 * a careful player; a Classic best says what it beat; and a scored daily try shows its place on
 * today's board rather than "New best!" (every day's board is new, so every first try would be a
 * best).
 */
class MiniMatchRulesTest {

    @Test
    void onlyTheClassicBoardHasMilestones() {
        MiniMatchSettings s = MiniMatchSettings.defaults();
        assertEquals(List.of(30, 24, 20), s.milestonesFor(Scores.CLASSIC), "bronze, silver, gold in flips");
        assertTrue(s.milestonesFor(Scores.daily(20_000)).isEmpty(), "daily boards have no milestones");
        assertEquals(1, s.milestoneReward(), "each milestone pays once ever");
    }

    @Test
    void fewerFlipsReachMoreMilestones() {
        List<Integer> ladder = MiniMatchSettings.defaults().milestonesFor(Scores.CLASSIC);
        assertEquals(List.of(), CabinetGame.milestonesReached(ladder, 31, MiniMatch.LOWER_IS_BETTER),
                "31 flips reaches nothing");
        assertEquals(List.of(1, 2), CabinetGame.milestonesReached(ladder, MiniMatch.DAILY_GOAL, true),
                "the daily goal is exactly silver");
        assertEquals(List.of(1, 2, 3), CabinetGame.milestonesReached(ladder, 15, true),
                "perfect memory (15 at most) reaches gold");
    }

    @Test
    void aClassicBestSaysWhatItBeat() {
        String line = MiniMatch.standing(new ScoreResult(true, 16L, false, 3));
        assertTrue(line.contains("New best") && line.contains("was 16"), "a better score names the old one: " + line);
        assertTrue(MiniMatch.standing(new ScoreResult(true, 16L, true, 1)).contains("Server record"),
                "the top score on the server says so");
        assertEquals("", MiniMatch.standing(new ScoreResult(false, 12L, false, 5)), "no best, nothing to add");
        assertEquals("", MiniMatch.standing(ScoreResult.NONE), "nothing recorded, nothing to add");
    }

    @Test
    void aDailyTryShowsItsPlaceToday() {
        String top = MiniMatch.dayStanding(new ScoreResult(true, null, true, 1));
        assertTrue(top.contains("Top score today"), "first place today: " + top);
        assertFalse(top.contains("New best"), "a daily board is new every day, so 'best' would always be true");
        assertTrue(MiniMatch.dayStanding(new ScoreResult(true, null, false, 3)).contains("#3"), "third today");
        assertEquals("", MiniMatch.dayStanding(ScoreResult.NONE), "nothing recorded, nothing to add");
    }
}
