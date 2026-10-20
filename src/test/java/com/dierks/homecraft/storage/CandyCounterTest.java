package com.dierks.homecraft.storage;

import com.dierks.homecraft.arcade.candy.CandyRules;
import com.dierks.homecraft.arcade.candy.CandyRules.HandOver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Candy Apple shower's counting path against a real SQLite (HALLOWEEN-SPEC D14, D15, §4.2, E17): the count
 * toward the Trick-or-Treat Bag is a running total in {@code player_counters} with the year in its key, the
 * golden-seen and bag marks are first-time counters, and the bag's mark is written before the bag is given, so a
 * failed write never hands out two.
 */
class CandyCounterTest {

    private static final int YEAR = 2026;

    private Connection conn;
    private AchievementDao dao;
    private final UUID player = UUID.randomUUID();
    private final List<String> calls = new ArrayList<>();

    @BeforeEach
    void open() throws Exception {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        dao = new AchievementDao(Database.open(conn, Logger.getAnonymousLogger()));
    }

    @AfterEach
    void close() throws Exception {
        conn.close();
    }

    /** {@code AchievementService.firstTime}: {@code addCounter(..., 1) == 1}. */
    private boolean firstTime(String marker) throws SQLException {
        return dao.addCounter(player, marker, 1) == 1;
    }

    @Test
    void theCountIsARunningTotal() throws Exception {
        String key = CandyRules.counterKey(YEAR);
        assertEquals("candy:2026", key);
        assertEquals(0, dao.counter(player, key), "nothing found yet");
        assertEquals(1, dao.addCounter(player, key, 1));
        assertEquals(2, dao.addCounter(player, key, 1));
        assertEquals(4, dao.addCounter(player, key, 2), "a stack of 2 counts 2");
        assertEquals(4, dao.counter(player, key));
        assertEquals(0, dao.counter(UUID.randomUUID(), key), "each player has their own");
        assertEquals(0, dao.counter(player, CandyRules.counterKey(2027)), "next October starts from 0");
    }

    @Test
    void theGoldenSeenMarkIsReadOnceAndWrittenOnce() throws Exception {
        String seen = CandyRules.goldenSeenKey(YEAR);
        assertEquals("candy:2026:golden_seen", seen);
        assertEquals(0, dao.counter(player, seen), "never picked one up: the 3rd apple is golden");
        assertTrue(firstTime(seen), "the first golden pickup");
        assertEquals(1, dao.counter(player, seen), "from then on, no extra golden");
        assertFalse(firstTime(seen), "true the first time only");
    }

    @Test
    void theBagMarkIsWrittenBeforeTheBagIsGiven() throws Exception {
        String bag = CandyRules.bagKey(YEAR);
        assertEquals("candy:2026:bag", bag);
        HandOver first = CandyRules.handOver(() -> {
            calls.add("mark");
            return firstTime(bag);
        }, () -> {
            try {
                calls.add("give, mark=" + dao.counter(player, bag));
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            return true;
        });
        assertEquals(HandOver.GIVEN, first);
        assertEquals(List.of("mark", "give, mark=1"), calls, "the mark is on disk when the bag is handed over");

        calls.clear();
        HandOver second = CandyRules.handOver(() -> firstTime(bag), () -> {
            calls.add("give");
            return true;
        });
        assertEquals(HandOver.ALREADY, second, "a second try (a join, /hcm candy) finds the mark");
        assertTrue(calls.isEmpty(), "and gives nothing");
    }

    /** E17: the mark can't be written, so nothing is given (tried again on the next join or /hcm candy). */
    @Test
    void aMarkThatCantBeWrittenGivesNothing() {
        HandOver r = CandyRules.handOver(() -> {
            throw new SQLException("disk full");
        }, () -> {
            calls.add("give");
            return true;
        });
        assertEquals(HandOver.NOT_SAVED, r);
        assertTrue(calls.isEmpty(), "no bag without its mark");
    }

    /** A bag that is marked but doesn't fit is LOST (an admin restores it), never a second chance at a second bag. */
    @Test
    void aMarkedBagThatDidntFitIsNotTriedAgain() throws Exception {
        String bag = CandyRules.bagKey(YEAR);
        assertEquals(HandOver.LOST, CandyRules.handOver(() -> firstTime(bag), () -> false));
        assertEquals(1, dao.counter(player, bag), "the mark stays");
        assertEquals(HandOver.ALREADY, CandyRules.handOver(() -> firstTime(bag), () -> true),
                "so no later try hands one out: /hcm candy bag is the restore");
    }
}
