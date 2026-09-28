package com.dierks.homecraft.games.cabinet;

import com.dierks.homecraft.storage.Database;
import com.dierks.homecraft.storage.GamesDao;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared cabinet rules that don't need a server: which milestones a score reaches, and the
 * daily-board seed.
 *
 * <p>Pinned here: a milestone counts at its threshold (inclusive) in the right direction for the
 * board; a missing or zero threshold never pays; the daily seed is an HMAC-SHA256 of the day and
 * game keyed with the server's secret — stable for a day, different per day, per game and per
 * secret; a player who can't earn where they are, or who deals minutes before a scheduled restart,
 * is dealt practice without using the scored try and told one reason; and a finished daily's
 * "Play again" says what the next deal really is.
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
        assertEquals(CabinetGame.seed(secret, 20_000, "snake"), CabinetGame.seed(secret, 20_000, "snake"),
                "everyone gets the same board all day");
        assertNotEquals(CabinetGame.seed(secret, 20_000, "snake"), CabinetGame.seed(secret, 20_001, "snake"),
                "tomorrow's board is different");
        assertNotEquals(CabinetGame.seed(secret, 20_000, "snake"), CabinetGame.seed(secret, 20_000, "ore_merge"),
                "two games never share a day's seed");
        assertNotEquals(CabinetGame.seed(secret, 20_000, "snake"), CabinetGame.seed(secret + 1, 20_000, "snake"),
                "another server's secret gives another board, so the source can't predict it");
        Set<Long> seen = new HashSet<>();
        for (long day = 0; day < 1000; day++) {
            seen.add(CabinetGame.seed(secret, day, "creeper_sweeper"));
        }
        assertEquals(1000, seen.size(), "a thousand days give a thousand different boards");
    }

    @Test
    void theDailySeedIsAnHmacOfTheDayAndGameKeyedWithTheSecret() {
        // Worked out independently: HMAC-SHA256(key = the secret's 8 bytes big-endian,
        // message = "20000|snake"), its first 8 bytes big-endian as a signed long.
        assertEquals(0xEAB2F21F4C7F732DL, CabinetGame.seed(0x1234_5678_9ABC_DEF0L, 20_000, "snake"),
                "a MAC, not a mixing function: a board a player has seen can't be walked back to the secret");
        assertEquals(0x25B1C7D6B8703C8EL, CabinetGame.seed(-1L, -5, "ore_merge"),
                "negative secrets and days are just bytes and digits to the MAC");
    }

    @Test
    void aPlayerWhoCantEarnHereIsDealtPracticeAndKeepsTheScoredTry() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            GamesDao dao = new GamesDao(Database.open(conn, Logger.getAnonymousLogger()));
            UUID alex = UUID.randomUUID();
            long day = 20_000;
            CabinetGame.Attempt mark = () -> dao.markDailyAttempt(alex, "snake", day, 42, "", 1_000L);

            CabinetGame.DailyStart creative = CabinetGame.deal(day, 42, false, mark);
            assertFalse(creative.scored(), "somewhere nothing can be earned, today's board is practice");
            assertFalse(dao.dailyAttempt(alex, "snake", day), "and the try is NOT written, so it waits for later");

            CabinetGame.DailyStart later = CabinetGame.deal(day, 42, true, mark);
            assertTrue(later.scored(), "back where it counts, the first deal is the scored try after all");
            assertEquals(42, later.seed(), "the same board as everyone's today");
            assertFalse(CabinetGame.deal(day, 42, true, mark).scored(), "and the one after it is practice");
        }
    }

    @Test
    void aDealMinutesBeforeARestartIsPracticeAndSaysOneReason() throws Exception {
        assertNull(CabinetGame.practiceWhy(true, null), "somewhere it can count with no restart near, nothing is said");
        assertEquals("&7The server restarts at 4:00 PM, so today's board is practice for now. "
                        + "Your scored try waits until after.", CabinetGame.practiceWhy(true, "4:00 PM"),
                "minutes before a restart the player reads when, and that the try waits");
        assertEquals(CabinetGame.NOT_HERE_DAILY, CabinetGame.practiceWhy(false, "4:00 PM"),
                "somewhere nothing can be earned that is the reason given, not the restart too");
        boolean[] written = {false};
        CabinetGame.DailyStart held = CabinetGame.deal(20_000, 42, false, () -> written[0] = true);
        assertFalse(held.scored(), "a deal that can't count is practice");
        assertFalse(written[0], "and the attempt row is never written for it");
    }

    @Test
    void aFinishedDailySaysWhatTheNextDealIsWhenTheScreenIsDrawn() {
        assertEquals("Play today's board again &7(practice)", CabinetGame.dailyAgain("board", true),
                "today's scored try is used: the next deal is practice, and says so");
        assertEquals("Play today's pattern &7- your scored try", CabinetGame.dailyAgain("pattern", false),
                "after midnight the next deal is a new day's scored try, so it doesn't say practice");
        assertFalse(CabinetGame.dailyAgain("round", false).contains("practice"),
                "never 'practice' for a deal that will count");
    }

    @Test
    void medalsReadInPlainWords() {
        assertEquals("bronze", CabinetGame.medal(1), "the first milestone");
        assertEquals("gold", CabinetGame.medal(3), "the third milestone");
        assertTrue(CabinetGame.medal(4).contains("4"), "anything past gold still names itself");
    }
}
