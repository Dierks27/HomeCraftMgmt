package com.dierks.homecraft.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The family week's capped write (0.38.4), against a real SQLite: points land on the week's board and the day's
 * board together, never more than the cap a day, the next day starts again, the week's total is the sum of what
 * was added, and a full day adds nothing to either board.
 */
class GamesDaoPointsCapTest {

    private static final long NOW = 1_790_000_000_000L;
    private static final String WEEK = "club:20700";

    private Connection conn;
    private GamesDao dao;
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @BeforeEach
    void open() throws Exception {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        dao = new GamesDao(Database.open(conn, Logger.getAnonymousLogger()));
    }

    @AfterEach
    void close() throws Exception {
        conn.close();
    }

    @Test
    void pointsAreCappedADayAndTheWeekIsTheirSum() throws Exception {
        String monday = "clubday:20700";
        assertEquals(4, dao.addPointsCapped(alice, "clubhouse", WEEK, monday, 4, 10, NOW), "the first 4");
        assertEquals(4, dao.addPointsCapped(alice, "clubhouse", WEEK, monday, 4, 10, NOW + 1), "4 more");
        assertEquals(2, dao.addPointsCapped(alice, "clubhouse", WEEK, monday, 4, 10, NOW + 2), "only 2 fit");
        assertEquals(0, dao.addPointsCapped(alice, "clubhouse", WEEK, monday, 4, 10, NOW + 3), "the day is full");
        assertEquals(10L, dao.best(alice, "clubhouse", monday), "the day's board holds the cap");
        assertEquals(10L, dao.best(alice, "clubhouse", WEEK), "and the week's the sum");
        assertEquals(NOW + 2, dao.top("clubhouse", WEEK, false, 1).get(0).at(), "a write that added nothing moved nothing");

        String tuesday = "clubday:20701";
        assertEquals(3, dao.addPointsCapped(alice, "clubhouse", WEEK, tuesday, 3, 10, NOW + 100), "a new day starts again");
        assertEquals(13L, dao.best(alice, "clubhouse", WEEK), "the week keeps adding up");
        assertEquals(3L, dao.best(alice, "clubhouse", tuesday), "the new day's count");
        assertEquals(10L, dao.best(alice, "clubhouse", monday), "the old day's stays");

        assertEquals(5, dao.addPointsCapped(bob, "clubhouse", WEEK, tuesday, 5, 10, NOW + 101), "each player's own cap");
        assertEquals(alice, dao.top("clubhouse", WEEK, false, 2).get(0).player(), "higher is better");
        assertNull(dao.best(bob, "clubhouse", monday), "Bob has no Monday row");
        assertEquals(0, dao.addPointsCapped(bob, "clubhouse", WEEK, tuesday, 0, 10, NOW + 102), "0 adds nothing");
        assertThrows(IllegalArgumentException.class, () -> dao.addPointsCapped(bob, "clubhouse", WEEK, tuesday, -1,
                10, NOW), "a points board never falls");
    }
}
