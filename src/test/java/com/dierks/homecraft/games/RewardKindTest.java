package com.dierks.homecraft.games;

import com.dierks.homecraft.arcade.TokenService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Daily Courses' new reward kind and ledger source (GEN-SPEC §5.3): the first counted finish of a
 * course day is capped like every skill reward, kept per game (not once across games), and paid
 * once per course and course day by its ref — a reroll the same day pays no second one. Star Chart
 * goals are paid under their own source.
 */
class RewardKindTest {

    @Test
    void aDailyClearIsCappedPerGameAndOneTime() {
        assertTrue(RewardKind.DAILY_CLEAR.capped(), "it counts toward the game's cap and the server's");
        assertFalse(RewardKind.DAILY_CLEAR.acrossGames(), "each game keeps its own");
        assertFalse(RewardKind.DAILY_CLEAR.repeatable(), "it is one-time, by its ref");
        assertSame(RewardKind.DAILY_CLEAR, RewardKind.valueOf("DAILY_CLEAR"), "stored by that name");
    }

    @Test
    void theDailyClearRefIsPerCourseAndCourseDay() {
        assertEquals("dclear:fresh_golf:20725", SkillRewards.dailyClearRef("fresh_golf", 20725),
                "dclear:<course>:<day>");
        assertEquals(SkillRewards.dailyClearRef("fresh_golf", 20725), SkillRewards.dailyClearRef("fresh_golf", 20725),
                "a reroll the same day has the same ref, so it pays no second one");
        assertFalse(SkillRewards.dailyClearRef("fresh_golf", 20725).equals(SkillRewards.dailyClearRef("fresh_tiny_golf",
                20725)), "another course has its own");
        assertEquals("ms:gweek:20720:10", SkillRewards.milestoneRef("gweek:20720", 10),
                "a Star Chart goal is a milestone on the week's board");
    }

    @Test
    void starChartGoalsHaveTheirOwnLedgerSource() {
        TokenService.Source s = TokenService.Source.of("GAMES_DAILY");
        assertSame(TokenService.Source.GAMES_DAILY, s, "stored by name");
        assertEquals("Daily Courses", s.label(), "what the token history says");
    }
}
