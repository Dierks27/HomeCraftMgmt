package com.dierks.homecraft.storage;

import com.dierks.homecraft.games.ScoreResult;
import com.dierks.homecraft.games.Scores;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two reads the Clubhouse's My stats makes (0.38.3), against a real SQLite: every board a player has a score
 * on, with their best, when it was set and how many counted runs; and their place on a board, exactly as a submit
 * ranks it (0 with no score). They are read off the main thread, so a read on another thread waits for the one
 * connection like every DAO call, and never sees half a submit.
 */
class GamesDaoClubStatsTest {

    private static final long NOW = 1_790_000_000_000L;

    private Connection conn;
    private GamesDao dao;
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID carol = UUID.randomUUID();

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
    void aPlayersBoardsCarryTheirBestWhenAndHowManyRuns() throws Exception {
        String course = Scores.course("sky_rings");
        String week = Scores.week("sky_rings", 20_750);
        dao.submit(alice, "trials", course, 62_000, true, NOW);
        dao.submit(alice, "trials", course, 58_000, true, NOW + 10);
        dao.submit(alice, "trials", course, 70_000, true, NOW + 20);
        dao.submit(alice, "trials", week, 58_000, true, NOW + 10);
        dao.submit(alice, "golf", Scores.golf("tiny"), 24, true, NOW + 30);
        dao.submit(bob, "trials", course, 50_000, true, NOW);

        List<GamesDao.PlayerBoard> mine = dao.boardsOf(alice, "trials");
        assertEquals(List.of(new GamesDao.PlayerBoard(course, 58_000, NOW + 10, 3),
                new GamesDao.PlayerBoard(week, 58_000, NOW + 10, 1)), mine,
                "each board once, by name: the best, when it was set, and every counted run");
        assertEquals(List.of(new GamesDao.PlayerBoard(Scores.golf("tiny"), 24, NOW + 30, 1)),
                dao.boardsOf(alice, "golf"), "one game at a time");
        assertEquals(List.of(), dao.boardsOf(carol, "trials"), "nothing played: nothing");
    }

    @Test
    void aPlaceIsTheSameAsASubmitsAndZeroWithNoScore() throws Exception {
        String board = Scores.course("river");
        ScoreResult a = dao.submit(alice, "trials", board, 60_000, true, NOW);
        ScoreResult b = dao.submit(bob, "trials", board, 55_000, true, NOW + 1);
        ScoreResult c = dao.submit(carol, "trials", board, 60_000, true, NOW + 2);
        assertEquals(b.rank(), dao.rank(bob, "trials", board, true), "the record");
        assertEquals(1, dao.rank(bob, "trials", board, true), "1st");
        assertEquals(2, dao.rank(alice, "trials", board, true), "a tie ranks whoever got there first");
        assertEquals(c.rank(), dao.rank(carol, "trials", board, true), "as the submit said");
        assertEquals(3, dao.rank(carol, "trials", board, true), "after the tie");
        assertTrue(a.rank() >= 1, "ranked when it was set");
        assertEquals(0, dao.rank(UUID.randomUUID(), "trials", board, true), "no score: no place");
        assertEquals(0, dao.rank(alice, "trials", Scores.course("nowhere"), true), "a board they never played");
        dao.submit(alice, "golf", Scores.golf("tiny"), 30, true, NOW);
        dao.submit(bob, "golf", Scores.golf("tiny"), 26, true, NOW);
        assertEquals(2, dao.rank(alice, "golf", Scores.golf("tiny"), true), "golf: fewer strokes is better");
        assertEquals(1, dao.rank(alice, "golf", Scores.golf("tiny"), false), "and the other way round");
    }

    @Test
    void aReadOnAnotherThreadWaitsForTheOneConnection() throws Exception {
        String board = Scores.course("river");
        dao.submit(alice, "trials", board, 60_000, true, NOW);
        AtomicReference<Object> got = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Thread t = new Thread(() -> {
                try {
                    for (int n = 0; n < 50; n++) {
                        dao.boardsOf(alice, "trials");
                        dao.rank(alice, "trials", board, true);
                    }
                } catch (Exception e) {
                    got.set(e);
                }
            });
            threads.add(t);
            t.start();
        }
        for (int n = 0; n < 50; n++) {
            dao.submit(bob, "trials", board, 70_000 - n, true, NOW + n);
        }
        for (Thread t : threads) {
            t.join(10_000);
        }
        assertNull(got.get(), "no read failed alongside the submits");
        assertEquals(50, dao.boardsOf(bob, "trials").get(0).runs(), "and every submit landed");
    }
}
