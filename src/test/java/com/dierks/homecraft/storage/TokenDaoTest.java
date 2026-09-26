package com.dierks.homecraft.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Token balances against a real SQLite: every change is atomic, a spend never takes a balance
 * below zero, and every change leaves exactly one ledger line.
 */
class TokenDaoTest {

    private Connection conn;
    private TokenDao dao;
    private final UUID player = UUID.randomUUID();

    @BeforeEach
    void open() throws Exception {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        dao = new TokenDao(Database.open(conn, Logger.getAnonymousLogger()));
    }

    @AfterEach
    void close() throws Exception {
        conn.close();
    }

    @Test
    void interleavedSpendsNeverGoNegative() throws Exception {
        assertEquals(100, dao.change(player, 100, "ADMIN", "seed", 1));

        int threads = 8;
        int perThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            results.add(pool.submit(() -> {
                start.await();
                int ok = 0;
                for (int i = 0; i < perThread; i++) {
                    if (dao.change(player, -3, "CRATE", "pull", 2) != TokenDao.REFUSED) {
                        ok++;
                    }
                }
                return ok;
            }));
        }
        start.countDown();
        int spent = 0;
        for (Future<Integer> f : results) {
            spent += f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertEquals(33, spent, "100 tokens buy exactly 33 pulls at 3");
        assertEquals(1, dao.get(player).tokens());

        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*), SUM(delta), MIN(balance_after) FROM token_ledger")) {
            assertTrue(rs.next());
            assertEquals(34, rs.getInt(1), "one ledger line per change that happened, none for refusals");
            assertEquals(1, rs.getInt(2), "the ledger sums to the balance");
            assertTrue(rs.getInt(3) >= 0, "no line ever recorded a negative balance");
        }
    }

    @Test
    void aRefusedSpendChangesNothing() throws Exception {
        dao.change(player, 5, "QUEST", "Catch 8 fish", 1);
        assertEquals(TokenDao.REFUSED, dao.change(player, -6, "PRIZE", "Mini Radar", 2));
        assertEquals(5, dao.get(player).tokens());
        assertEquals(1, dao.history(player, 10).size());
    }

    @Test
    void theLedgerRecordsWhyAndWhatWasLeft() throws Exception {
        dao.change(player, 5, "QUEST", "Catch 8 fish", 10);
        dao.change(player, -2, "CRATE", "Arcade Crate", 20);

        List<TokenDao.LedgerRow> rows = dao.history(player, 10);
        assertEquals(2, rows.size());
        assertEquals(-2, rows.get(0).delta(), "newest first");
        assertEquals(3, rows.get(0).balanceAfter());
        assertEquals("CRATE", rows.get(0).source());
        assertEquals("Catch 8 fish", rows.get(1).detail());

        List<TokenDao.SourceTotal> totals = dao.totals(0, null);
        assertEquals(2, totals.size());
        long earned = totals.stream().mapToLong(TokenDao.SourceTotal::earned).sum();
        long spentTotal = totals.stream().mapToLong(TokenDao.SourceTotal::spent).sum();
        assertEquals(5, earned);
        assertEquals(2, spentTotal);
        assertEquals(1, dao.totals(15, null).size(), "the window excludes older lines");
    }

    @Test
    void aStreakDayPaysOnce() throws Exception {
        assertEquals(2, dao.claimStreak(player, 1, 20_000, 2, "LOGIN_STREAK", "Day 1", 1));
        assertEquals(TokenDao.REFUSED, dao.claimStreak(player, 1, 20_000, 2, "LOGIN_STREAK", "Day 1", 2),
                "a second claim for the same day is refused");
        assertEquals(4, dao.claimStreak(player, 2, 20_001, 2, "LOGIN_STREAK", "Day 2", 3));
        assertEquals(2, dao.get(player).streak());

        // A UTC day ahead of the local calendar is pulled back, never forward.
        dao.claimStreak(player, 3, 20_003, 0, "LOGIN_STREAK", "Day 3", 4);
        dao.rewindStreakDay(player, 20_002);
        assertEquals(20_002, dao.get(player).lastStreakDay());
        dao.rewindStreakDay(player, 20_010);
        assertEquals(20_002, dao.get(player).lastStreakDay(), "rewind never moves a day forward");
    }

    @Test
    void playtimePaysOnlyNewMilestones() throws Exception {
        assertEquals(3, dao.claimPlaytime(player, 3, "PLAYTIME", "Time played", 1));
        assertEquals(0, dao.claimPlaytime(player, 3, "PLAYTIME", "Time played", 2));
        assertEquals(1, dao.claimPlaytime(player, 4, "PLAYTIME", "Time played", 3));
        assertEquals(4, dao.get(player).tokens());
    }

    @Test
    void anAdminSetIsRecordedAsTheDifference() throws Exception {
        dao.change(player, 10, "QUEST", "x", 1);
        assertEquals(3, dao.setBalance(player, 3, "ADMIN", "Set by an admin", 2));
        assertEquals(-7, dao.history(player, 1).get(0).delta());
        assertEquals(0, dao.setBalance(player, -5, "ADMIN", "Set by an admin", 3), "floored at zero");
    }
}
