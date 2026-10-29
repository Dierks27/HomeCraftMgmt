package com.dierks.homecraft.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Checkpoint splits in {@code game_splits} (0.38.4, schema v39), against a real SQLite: a player's best run's
 * times are kept per course AND layout, a new best replaces them, another layout (a Fresh course's next edition)
 * is another row, nothing stored reads as none, and a row keeps at most 128 values.
 */
class GamesDaoSplitsTest {

    private Connection conn;
    private GamesDao dao;
    private final UUID sam = UUID.randomUUID();
    private final UUID ava = UUID.randomUUID();

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
    void aBestRunsSplitsAreKeptPerCourseAndLayoutAndReplacedByTheNextBest() throws Exception {
        assertNull(dao.splits(sam, "sky_rings", 7), "nothing stored yet");
        dao.saveSplits(sam, "sky_rings", 7, new long[]{10_000, 20_000, 31_500}, 1_000);
        assertArrayEquals(new long[]{10_000, 20_000, 31_500}, dao.splits(sam, "sky_rings", 7));
        dao.saveSplits(sam, "sky_rings", 7, new long[]{9_000, 19_000, 30_000}, 2_000);
        assertArrayEquals(new long[]{9_000, 19_000, 30_000}, dao.splits(sam, "sky_rings", 7), "the new best replaces it");
        assertNull(dao.splits(sam, "sky_rings", 8), "another layout (the next edition): its own row, none yet");
        dao.saveSplits(sam, "sky_rings", 8, new long[]{12_000, 24_000}, 3_000);
        assertArrayEquals(new long[]{9_000, 19_000, 30_000}, dao.splits(sam, "sky_rings", 7), "the old layout's stay");
        assertArrayEquals(new long[]{12_000, 24_000}, dao.splits(sam, "sky_rings", 8));
        assertNull(dao.splits(ava, "sky_rings", 7), "someone else's: none");
        assertNull(dao.splits(sam, "river_run", 7), "another course: none");
    }

    @Test
    void aRowKeepsAtMostOneHundredAndTwentyEightValues() throws Exception {
        long[] many = new long[200];
        for (int i = 0; i < many.length; i++) {
            many[i] = (i + 1) * 1_000L;
        }
        dao.saveSplits(sam, "mountain", 1, many, 1_000);
        long[] back = dao.splits(sam, "mountain", 1);
        assertEquals(GamesDao.SPLITS_MAX, back.length, "cut to the most a run has");
        assertEquals(128, GamesDao.SPLITS_MAX, "a Mountain Run v2 has up to 128 targets");
        assertEquals(128_000, back[127]);
        dao.saveSplits(ava, "mountain", 1, new long[0], 1_000);
        assertNull(dao.splits(ava, "mountain", 1), "nothing is written for no times");
        assertNull(GamesDao.decodeSplits("1,x,3"), "an unreadable row reads as none: a split is decoration");
        assertEquals("1,2,3", GamesDao.encodeSplits(new long[]{1, 2, 3}));
    }
}
