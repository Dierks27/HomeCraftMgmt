package com.dierks.homecraft.games.cabinet;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared cabinet rules that don't need a server: which milestones a score reaches, and the
 * daily-board seed.
 *
 * <p>Pinned here: a milestone counts at its threshold (inclusive) in the right direction for the
 * board; a missing or zero threshold never pays; the daily seed is stable for a day, different per
 * day and per game, and depends on the server's secret.
 */
class CabinetGameTest {

    @Test
    void aHigherIsBetterBoardReachesEveryThresholdAtOrUnderTheScore() {
        assertEquals(List.of(), CabinetGame.milestonesReached(List.of(10, 20, 30), 9, false),
                "nine apples reaches no milestone of 10/20/30");
        assertEquals(List.of(1), CabinetGame.milestonesReached(List.of(10, 20, 30), 10, false),
                "exactly the bronze threshold counts");
        assertEquals(List.of(1, 2, 3), CabinetGame.milestonesReached(List.of(10, 20, 30), 45, false),
                "a big score reaches all three at once, so a player skipping ahead is paid each once");
    }

    @Test
    void aLowerIsBetterBoardReachesEveryThresholdAtOrAboveTheScore() {
        assertEquals(List.of(), CabinetGame.milestonesReached(List.of(300, 180, 90), 301, true),
                "a slower time than bronze reaches nothing");
        assertEquals(List.of(1, 2), CabinetGame.milestonesReached(List.of(300, 180, 90), 180, true),
                "exactly the silver time counts, and bronze with it");
    }

    @Test
    void missingOrZeroThresholdsNeverPay() {
        assertEquals(List.of(), CabinetGame.milestonesReached(null, 100, false), "no milestones configured");
        assertEquals(List.of(2), CabinetGame.milestonesReached(List.of(0, 5), 5, false),
                "a zero threshold would pay every run, so it is skipped");
    }

    @Test
    void theDailySeedIsStableForADayAndDifferentPerDayAndPerGame() {
        long secret = 0x1234_5678_9ABC_DEF0L;
        assertEquals(CabinetGame.mix(secret, 20_000, "snake"), CabinetGame.mix(secret, 20_000, "snake"),
                "everyone gets the same board all day");
        assertNotEquals(CabinetGame.mix(secret, 20_000, "snake"), CabinetGame.mix(secret, 20_001, "snake"),
                "tomorrow's board is different");
        assertNotEquals(CabinetGame.mix(secret, 20_000, "snake"), CabinetGame.mix(secret, 20_000, "ore_merge"),
                "two games never share a day's seed");
        assertNotEquals(CabinetGame.mix(secret, 20_000, "snake"), CabinetGame.mix(secret + 1, 20_000, "snake"),
                "another server's secret gives another board, so the source can't predict it");
        Set<Long> seen = new HashSet<>();
        for (long day = 0; day < 1000; day++) {
            seen.add(CabinetGame.mix(secret, day, "creeper_sweeper"));
        }
        assertEquals(1000, seen.size(), "a thousand days give a thousand different boards");
    }

    @Test
    void medalsReadInPlainWords() {
        assertEquals("bronze", CabinetGame.medal(1), "the first milestone");
        assertEquals("gold", CabinetGame.medal(3), "the third milestone");
        assertTrue(CabinetGame.medal(4).contains("4"), "anything past gold still names itself");
    }
}
