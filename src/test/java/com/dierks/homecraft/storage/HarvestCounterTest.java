package com.dierks.homecraft.storage;

import com.dierks.homecraft.arcade.candy.CandyRules;
import com.dierks.homecraft.arcade.candy.CandyRules.HandOver;
import com.dierks.homecraft.arcade.candy.ShowerProfile;
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
 * The Harvest Basket's counting path against a real SQLite (0.38.6), the twin of {@code CandyCounterTest}: its
 * count, golden-seen mark and hat mark live in {@code player_counters} under {@code harvest:<year>}, apart from
 * candy's, and the hat's mark is written before the hat is given.
 */
class HarvestCounterTest {

    private static final int YEAR = 2026;
    private static final ShowerProfile HARVEST = ShowerProfile.harvest();

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

    private boolean firstTime(String marker) throws SQLException {
        return dao.addCounter(player, marker, 1) == 1;
    }

    @Test
    void theCountIsARunningTotalApartFromCandys() throws Exception {
        String key = HARVEST.counterKey(YEAR);
        assertEquals("harvest:2026", key);
        assertEquals(1, dao.addCounter(player, key, 1));
        assertEquals(3, dao.addCounter(player, key, 2));
        dao.addCounter(player, CandyRules.counterKey(YEAR), 30);
        assertEquals(3, dao.counter(player, key), "candy apples never count toward the hat");
        assertEquals(30, dao.counter(player, CandyRules.counterKey(YEAR)), "and treats never toward the bag");
        assertEquals(0, dao.counter(player, HARVEST.counterKey(2027)), "next year starts from 0");
    }

    @Test
    void theGoldenSeenMarkIsItsOwn() throws Exception {
        String seen = HARVEST.goldenSeenKey(YEAR);
        assertEquals("harvest:2026:golden_seen", seen);
        assertTrue(firstTime(CandyRules.goldenSeenKey(YEAR)), "her first golden candy apple");
        assertEquals(0, dao.counter(player, seen), "her first basket shower still has a Golden Pie");
        assertTrue(firstTime(seen));
        assertFalse(firstTime(seen), "true the first time only");
    }

    @Test
    void theHatMarkIsWrittenBeforeTheHatIsGiven() throws Exception {
        String hat = HARVEST.rewardKey(YEAR);
        assertEquals("harvest:2026:hat", hat);
        HandOver first = CandyRules.handOver(() -> {
            calls.add("mark");
            return firstTime(hat);
        }, () -> {
            try {
                calls.add("give, mark=" + dao.counter(player, hat));
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            return true;
        });
        assertEquals(HandOver.GIVEN, first);
        assertEquals(List.of("mark", "give, mark=1"), calls, "the mark is on disk when the hat is handed over");
        assertEquals(HandOver.ALREADY, CandyRules.handOver(() -> firstTime(hat), () -> true), "never a second hat");
    }

    @Test
    void aMarkedHatThatWasntGivenIsLostNotGivenTwice() throws Exception {
        String hat = HARVEST.rewardKey(YEAR);
        assertEquals(HandOver.LOST, CandyRules.handOver(() -> firstTime(hat), () -> false));
        assertEquals(1, dao.counter(player, hat), "the mark stays: /hcm harvest hat is the restore");
        assertEquals(HandOver.NOT_SAVED, CandyRules.handOver(() -> {
            throw new SQLException("disk full");
        }, () -> {
            calls.add("give");
            return true;
        }));
        assertTrue(calls.isEmpty(), "no hat without its mark");
    }
}
