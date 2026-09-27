package com.dierks.homecraft.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The website's long market charts against a real SQLite. {@code sampled} keeps the latest
 * snapshot of each epoch-aligned bucket (its own price, stock and exact time), oldest first, from
 * {@code fromInclusive} on and for one item only; a row exactly on a bucket boundary opens the new
 * bucket. Thinning 8 days of half-hourly snapshots to the 7-day window gives at most 168 hourly
 * points. {@code keepCutoff}/{@code pruneBefore} delete only rows strictly older than the cutoff,
 * across all items, a bounded batch at a time, oldest first, and say how many went.
 */
class PriceHistoryDaoTest {

    private static final long MIN = 60_000L;
    private static final long HOUR = 3_600_000L;
    private static final long DAY = 86_400_000L;
    /** An hour boundary in 2026: 1 790 000 000 000 ms rounded down to the hour. */
    private static final long H0 = Math.floorDiv(1_790_000_000_000L, HOUR) * HOUR;

    private Connection conn;
    private PriceHistoryDao dao;

    @BeforeEach
    void open() throws Exception {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        dao = new PriceHistoryDao(Database.open(conn, Logger.getAnonymousLogger()));
    }

    @AfterEach
    void close() throws Exception {
        conn.close();
    }

    /** Mirrors the feed's window: the start of the oldest of the last {@code buckets} buckets. */
    private static long windowStart(long now, long bucketMs, int buckets) {
        return (Math.floorDiv(now, bucketMs) - (buckets - 1)) * bucketMs;
    }

    private void assertPoint(PriceHistoryDao.Snapshot s, String item, long t, double price, long stock) {
        assertEquals(item, s.itemId());
        assertEquals(t, s.recordedAt());
        assertEquals(price, s.price(), 1e-9);
        assertEquals(stock, s.stock());
    }

    private long rows() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM market_price_history")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private List<Long> times() throws SQLException {
        List<Long> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT recorded_at FROM market_price_history ORDER BY recorded_at");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(rs.getLong(1));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- sampled

    @Test
    void sampledKeepsTheLastSnapshotOfEachHourOldestFirst() throws Exception {
        // Written out of order on purpose: the result is ordered by time, not by insertion.
        dao.record("iron_ingot", 2.60, 4600, H0 + 3 * HOUR + 30 * MIN);
        dao.record("iron_ingot", 2.10, 4100, H0 + 5 * MIN);
        dao.record("iron_ingot", 2.30, 4300, H0 + HOUR - 1);       // last ms of hour 0: the keeper
        dao.record("iron_ingot", 2.20, 4200, H0 + 40 * MIN);
        dao.record("iron_ingot", 2.40, 4400, H0 + HOUR + 10 * MIN); // hour 1's only row
        // hour 2: nothing — the bucket is simply absent
        dao.record("iron_ingot", 2.50, 4500, H0 + 3 * HOUR + 1);

        List<PriceHistoryDao.Snapshot> points = dao.sampled("iron_ingot", H0, HOUR);

        assertEquals(3, points.size());
        assertPoint(points.get(0), "iron_ingot", H0 + HOUR - 1, 2.30, 4300);
        assertPoint(points.get(1), "iron_ingot", H0 + HOUR + 10 * MIN, 2.40, 4400);
        assertPoint(points.get(2), "iron_ingot", H0 + 3 * HOUR + 30 * MIN, 2.60, 4600);
    }

    @Test
    void aRowExactlyOnABucketBoundaryOpensTheNewBucket() throws Exception {
        dao.record("iron_ingot", 1.0, 10, H0 + HOUR - 1);
        dao.record("iron_ingot", 2.0, 20, H0 + HOUR);

        List<PriceHistoryDao.Snapshot> points = dao.sampled("iron_ingot", H0, HOUR);
        assertEquals(2, points.size(), "k*bucket belongs to bucket k, not k-1");
        assertPoint(points.get(0), "iron_ingot", H0 + HOUR - 1, 1.0, 10);
        assertPoint(points.get(1), "iron_ingot", H0 + HOUR, 2.0, 20);

        dao.record("iron_ingot", 3.0, 30, H0 + 2 * HOUR - 1);
        points = dao.sampled("iron_ingot", H0, HOUR);
        assertEquals(2, points.size());
        assertPoint(points.get(1), "iron_ingot", H0 + 2 * HOUR - 1, 3.0, 30);
    }

    @Test
    void sampledStartsExactlyAtFromInclusive() throws Exception {
        dao.record("iron_ingot", 1.0, 10, H0 - 1);
        dao.record("iron_ingot", 2.0, 20, H0);

        List<PriceHistoryDao.Snapshot> points = dao.sampled("iron_ingot", H0, HOUR);
        assertEquals(1, points.size(), "one ms before from is out");
        assertPoint(points.get(0), "iron_ingot", H0, 2.0, 20);

        assertEquals(2, dao.sampled("iron_ingot", H0 - 1, HOUR).size(), "a row exactly at from is in");
        assertTrue(dao.sampled("iron_ingot", H0 + 1, HOUR).isEmpty());
    }

    @Test
    void rowsBeforeFromDoNotSpeakForTheirBucket() throws Exception {
        dao.record("iron_ingot", 1.0, 10, H0 + 10 * MIN);
        dao.record("iron_ingot", 2.0, 20, H0 + 40 * MIN);

        List<PriceHistoryDao.Snapshot> points = dao.sampled("iron_ingot", H0 + 30 * MIN, HOUR);
        assertEquals(1, points.size());
        assertPoint(points.get(0), "iron_ingot", H0 + 40 * MIN, 2.0, 20);
        assertTrue(dao.sampled("iron_ingot", H0 + 45 * MIN, HOUR).isEmpty(),
                "a bucket whose rows are all before from is empty, not filled from before it");
    }

    @Test
    void sampledSeparatesItems() throws Exception {
        dao.record("iron_ingot", 2.0, 100, H0 + 10 * MIN);
        dao.record("gold_ingot", 9.0, 900, H0 + 20 * MIN);
        dao.record("iron_ingot", 2.1, 110, H0 + HOUR + 10 * MIN);
        dao.record("gold_ingot", 9.1, 910, H0 + HOUR + 50 * MIN);

        List<PriceHistoryDao.Snapshot> iron = dao.sampled("iron_ingot", H0, HOUR);
        assertEquals(2, iron.size());
        assertPoint(iron.get(0), "iron_ingot", H0 + 10 * MIN, 2.0, 100);
        assertPoint(iron.get(1), "iron_ingot", H0 + HOUR + 10 * MIN, 2.1, 110);

        List<PriceHistoryDao.Snapshot> gold = dao.sampled("gold_ingot", H0, HOUR);
        assertEquals(2, gold.size());
        assertPoint(gold.get(0), "gold_ingot", H0 + 20 * MIN, 9.0, 900);
        assertPoint(gold.get(1), "gold_ingot", H0 + HOUR + 50 * MIN, 9.1, 910);
    }

    @Test
    void anItemWithNoHistorySamplesToNothing() throws Exception {
        dao.record("iron_ingot", 2.0, 100, H0);
        assertTrue(dao.sampled("diamond", 0, HOUR).isEmpty());
        assertTrue(dao.sampled("iron_ingot", H0 + 1, HOUR).isEmpty(), "…and nothing since from is the same");
    }

    @Test
    void sixHourBucketsKeepTheLastSnapshotOfEachQuarterDay() throws Exception {
        long h6 = 6 * HOUR;
        long d0 = Math.floorDiv(H0, h6) * h6; // a UTC 00/06/12/18 boundary
        int n = 2 * 48;                        // two days of half-hourly snapshots
        for (int i = 0; i < n; i++) {
            dao.record("iron_ingot", 2.0 + i / 1000.0, 4000 + i, d0 + i * 30 * MIN);
        }

        List<PriceHistoryDao.Snapshot> points = dao.sampled("iron_ingot", d0, h6);

        assertEquals(8, points.size(), "two days = eight quarter-days");
        for (int b = 0; b < points.size(); b++) {
            int last = b * 12 + 11; // twelve snapshots per 6 h; the last one speaks for it
            assertPoint(points.get(b), "iron_ingot", d0 + last * 30 * MIN, 2.0 + last / 1000.0, 4000 + last);
        }
    }

    /**
     * What the feed actually does: 8 days of snapshots every 30 minutes (with a little scheduler
     * drift), thinned to the 7-day window of hourly buckets and the 30-day window of 6-hourly ones.
     */
    @Test
    void eightDaysOfHalfHourSnapshotsThinToTheFeedsWindows() throws Exception {
        long start = H0 + 7 * MIN + 13_000;
        int n = 8 * 48;
        List<Long> written = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            long t = start + i * 30 * MIN + (i * 137L) % 2_000; // up to 2 s late
            dao.record("iron_ingot", 2.0 + i / 1000.0, 4000 + i, t);
            written.add(t);
        }
        long now = written.get(n - 1) + 5 * MIN;

        long from7 = windowStart(now, HOUR, 168);
        List<PriceHistoryDao.Snapshot> h7 = dao.sampled("iron_ingot", from7, HOUR);
        assertEquals(168, h7.size(), "half-hourly snapshots fill every hour of the window");
        long prev = Long.MIN_VALUE;
        for (PriceHistoryDao.Snapshot s : h7) {
            assertTrue(s.recordedAt() > prev, "strictly increasing t");
            assertTrue(s.recordedAt() >= from7);
            if (prev != Long.MIN_VALUE) {
                assertEquals(prev / HOUR + 1, s.recordedAt() / HOUR, "one point per hour, none skipped");
            }
            for (long t : written) {
                if (t / HOUR == s.recordedAt() / HOUR) {
                    assertTrue(t <= s.recordedAt(), "the point is the hour's latest snapshot");
                }
            }
            int i = written.indexOf(s.recordedAt());
            assertTrue(i >= 0, "a point is a real row");
            assertEquals(2.0 + i / 1000.0, s.price(), 1e-9);
            assertEquals(4000 + i, s.stock());
            prev = s.recordedAt();
        }
        assertEquals(written.get(n - 1).longValue(), h7.get(h7.size() - 1).recordedAt(), "ends at the newest snapshot");

        long h6 = 6 * HOUR;
        List<PriceHistoryDao.Snapshot> h30 = dao.sampled("iron_ingot", windowStart(now, h6, 120), h6);
        assertTrue(h30.size() <= 120);
        assertTrue(h30.size() >= 8 * 4, "eight days of quarter-days, give or take the partial ends");
        for (int k = 1; k < h30.size(); k++) {
            assertEquals(h30.get(k - 1).recordedAt() / h6 + 1, h30.get(k).recordedAt() / h6);
        }
    }

    // ---------------------------------------------------------------- pruning

    @Test
    void keepCutoffIsKeepDaysBeforeNowAndZeroKeepsEverything() {
        long now = 1_790_000_000_000L;
        assertEquals(now - 30 * DAY, PriceHistoryDao.keepCutoff(now, 30));
        assertEquals(now - DAY, PriceHistoryDao.keepCutoff(now, 1));
        assertEquals(Long.MIN_VALUE, PriceHistoryDao.keepCutoff(now, 0));
        assertEquals(Long.MIN_VALUE, PriceHistoryDao.keepCutoff(now, -5));
    }

    @Test
    void pruneDeletesOnlyRowsStrictlyOlderThanTheCutoffAcrossAllItems() throws Exception {
        long now = H0 + 17 * MIN;
        long cutoff = PriceHistoryDao.keepCutoff(now, 30);
        dao.record("iron_ingot", 1.0, 1, cutoff - 40 * DAY);
        dao.record("gold_ingot", 1.0, 1, cutoff - 3 * HOUR);
        dao.record("iron_ingot", 1.0, 1, cutoff - 1);
        dao.record("gold_ingot", 1.0, 1, cutoff - 1);
        dao.record("iron_ingot", 2.0, 2, cutoff);        // exactly at the cutoff: kept
        dao.record("gold_ingot", 2.0, 2, cutoff + 1);
        dao.record("iron_ingot", 2.0, 2, now);

        assertEquals(4, dao.pruneBefore(cutoff, PriceHistoryDao.PRUNE_BATCH));
        assertEquals(List.of(cutoff, cutoff + 1, now), times());
        assertEquals(0, dao.pruneBefore(cutoff, PriceHistoryDao.PRUNE_BATCH), "nothing left to prune");
        assertEquals(1, dao.sampled("iron_ingot", 0, HOUR).stream()
                .filter(s -> s.recordedAt() == cutoff).count(), "the row at the cutoff still reads back");
    }

    @Test
    void pruneRespectsTheLimitOldestFirstAndTheNextCallFinishes() throws Exception {
        long cutoff = H0;
        List<Long> old = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            long t = cutoff - (10 - i) * HOUR;          // oldest written first
            dao.record(i % 2 == 0 ? "iron_ingot" : "gold_ingot", 1.0, i, t);
            old.add(t);
        }
        dao.record("iron_ingot", 2.0, 99, cutoff + HOUR);
        dao.record("gold_ingot", 2.0, 99, cutoff + 2 * HOUR);

        assertEquals(6, dao.pruneBefore(cutoff, 6), "a limit below the old-row count deletes exactly limit");
        List<Long> left = times();
        assertEquals(6, left.size());
        assertEquals(old.subList(6, 10), left.subList(0, 4), "the six oldest went first");

        assertEquals(4, dao.pruneBefore(cutoff, 6), "the next call finishes the job");
        assertEquals(List.of(cutoff + HOUR, cutoff + 2 * HOUR), times());
        assertEquals(0, dao.pruneBefore(cutoff, 6));
    }

    @Test
    void keepDaysZeroOrANonPositiveLimitPrunesNothing() throws Exception {
        long now = H0;
        dao.record("iron_ingot", 1.0, 1, 1);
        dao.record("iron_ingot", 1.0, 1, now - 400 * DAY);

        assertEquals(0, dao.pruneBefore(PriceHistoryDao.keepCutoff(now, 0), PriceHistoryDao.PRUNE_BATCH));
        assertEquals(0, dao.pruneBefore(PriceHistoryDao.keepCutoff(now, 30), 0));
        assertEquals(0, dao.pruneBefore(PriceHistoryDao.keepCutoff(now, 30), -1),
                "a negative limit is not SQLite's 'no limit'");
        assertEquals(2, rows());
    }
}
