package com.dierks.homecraft.games;

import com.dierks.homecraft.arcade.TokenService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fresh Courses' reward kind and ledger source (GEN-SPEC §5.3, the weekly addendum §4): FRESH_CLEAR,
 * the first counted finish of a course in a set, is capped like every skill reward, kept per game
 * (not once across games), and paid once per course and set by its ref {@code fresh:<slot>:<edition>}
 * — a reroll of the set pays no second one; golf's par and holes-in-one on a Fresh course are once
 * per set too. Star Chart goals are paid under their own source, "Fresh Courses".
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
    void theFreshClearRefIsPerSlotAndSet() {
        assertEquals("fresh:fresh_parkour_hard:7:38", SkillRewards.freshClearRef("fresh_parkour_hard", "7:38"),
                "fresh:<slot>:<edition>, the edition without its reroll");
        assertFalse(SkillRewards.freshClearRef("fresh_golf", "7:38").equals(SkillRewards.freshClearRef("fresh_golf",
                "1:268")), "a weekly and a daily set that begin the same day never share one");
        assertEquals("par:fresh_golf:7:38", SkillRewards.parRef("fresh_golf", "7:38"), "par once per set");
        assertEquals("hio:fresh_golf:3:7:38", SkillRewards.holeInOneRef("fresh_golf", 3, "7:38"),
                "a hole-in-one once per hole per set");
        assertEquals("par:meadow:20725", SkillRewards.parRef("meadow", 20725L), "a hand-built course's are by day");
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
        assertEquals("Fresh Courses", s.label(), "what the token history says");
    }
}
