package com.dierks.homecraft.market.sim;

import com.dierks.homecraft.storage.Database;
import com.dierks.homecraft.storage.MarketSimDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The §15 determinism case that goes through the database: a market whose every tick is written
 * through {@link MarketSimDao} (one transaction per tick, as the service does), thrown away at
 * tick 97 and read back from the in-memory SQLite, continues exactly like the same market kept in
 * memory — every multiplier, status, breakdown, row started or changed, working set and broadcast,
 * for three days (row ids aside: the in-memory run never gets any).
 *
 * <p>Also pins: what is read back at the restart (per-item rows, {@link Schedule},
 * {@link BroadcastState}, the seed, {@code last_tick_at} and {@code loadLive(last_tick_at)}) equals
 * what was in memory; rows inserted before the restart are the ones updated after it; and a
 * restart after 9 hours of downtime replays from the stored {@code last_tick_at} exactly like the
 * in-memory market catching up over the same gap. The service should load the working set with
 * {@code loadLive(last_tick_at)}: it is the set the last tick left, so the replay starts from it.
 */
class MarketSimulatorPersistTest {

    private static final long H = SimMath.HOUR_MS;
    private static final SimSettings S = SimSettings.defaults();
    private static final long T = S.tickMs();
    private static final long B0 = Math.floorDiv(MarketSimulatorSoakTest.START, T) * T;
    /** High bit set, so the unsigned-hex seed round trip matters. */
    private static final long SEED = 0x9E3779B97F4A7C15L;
    private static final SortedMap<String, ItemParams> ITEMS = MarketSimulatorSoakTest.params(S);
    private static final Map<String, MarketSimulator.Quote> MARKET = MarketSimulatorSoakTest.shippedMarket();

    private Connection conn;
    private Database db;
    private MarketSimDao dao;

    @BeforeEach
    void open() throws Exception {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        db = Database.open(conn, Logger.getAnonymousLogger());
        dao = new MarketSimDao(db);
    }

    @AfterEach
    void close() throws Exception {
        conn.close();
    }

    /** One copy of the market. With a DAO, every tick is written through it, as the service does. */
    private static final class Market {
        long seed;
        final Database db;
        final MarketSimDao dao;
        Map<String, ItemSimState> state = new HashMap<>();
        Schedule sched;
        BroadcastState bs = new BroadcastState();
        List<MarketEvent> events = List.of();
        Map<String, Double> prev;

        Market(long seed, Database db, MarketSimDao dao) {
            this.seed = seed;
            this.db = db;
            this.dao = dao;
            this.sched = Schedule.firstEnable(B0, new SimRandom(seed));
        }

        MarketSimulator.TickInput input(long b) {
            return new MarketSimulator.TickInput(b, true, S, new SimRandom(seed), ITEMS, MARKET, state, events, sched,
                    MarketSimulatorSoakTest.online(b), MarketSimulatorSoakTest.ZONE, MarketSimulatorSoakTest.SPREAD,
                    List.of(), bs, Set.of("oak_log"), prev);
        }

        MarketSimulator.TickResult tick(long b) throws SQLException {
            return after(MarketSimulator.tick(input(b)));
        }

        MarketSimulator.TickResult catchUp(long lastTickAt, long now) throws SQLException {
            return after(MarketSimulator.run(input(now), lastTickAt, now));
        }

        private MarketSimulator.TickResult after(MarketSimulator.TickResult r) throws SQLException {
            events = dao == null ? r.events() : persist(r);
            prev = r.multipliers();
            return r;
        }

        /**
         * The service's tick transaction: insert what started, update what changed, every
         * per-item row, the schedule and counters, {@code last_tick_at}. Then the working set
         * carries the new rows' ids.
         */
        private List<MarketEvent> persist(MarketSimulator.TickResult r) throws SQLException {
            Map<String, Long> ids = db.transaction(c -> {
                Map<String, Long> out = new HashMap<>();
                for (MarketEvent e : r.started()) {
                    long id = dao.insertEvent(c, e);
                    assertTrue(id > 0, "inserted: " + e);
                    out.put(key(e), id);
                }
                for (MarketEvent e : r.changed()) {
                    assertTrue(dao.updateEvent(c, e), "a stored row: " + e);
                }
                dao.saveStates(c, state.values());
                Map<String, String> meta = new LinkedHashMap<>(MarketSimDao.Meta.of(sched));
                meta.putAll(MarketSimDao.Meta.of(bs));
                meta.put(MarketSimDao.Meta.LAST_TICK_AT, Long.toString(r.boundary()));
                meta.put(MarketSimDao.Meta.SEED, MarketSimDao.Meta.seedHex(seed));
                dao.saveMeta(c, meta);
                return out;
            });
            List<MarketEvent> out = new ArrayList<>(r.events().size());
            for (MarketEvent e : r.events()) {
                out.add(e.id() == 0 ? e.withId(ids.get(key(e))) : e);
            }
            return out;
        }

        /** A restart: everything in memory is gone and read back. Returns {@code last_tick_at}. */
        long restart() throws SQLException {
            Map<String, String> meta = dao.loadMeta();
            seed = MarketSimDao.Meta.seed(meta).orElseThrow();
            long lastTickAt = MarketSimDao.Meta.longOf(meta, MarketSimDao.Meta.LAST_TICK_AT, 0L);
            state = new HashMap<>(dao.loadStates());
            sched = MarketSimDao.Meta.schedule(meta);
            bs = MarketSimDao.Meta.broadcast(meta);
            events = dao.loadLive(lastTickAt);
            prev = null; // not persisted: the first tick after a restart reports no jumps
            return lastTickAt;
        }
    }

    private static String key(MarketEvent e) {
        return e.kind() + "|" + e.itemId() + "|" + e.startedAt() + "|" + e.tag();
    }

    private static MarketEvent strip(MarketEvent e) {
        return e == null ? null : e.withId(0);
    }

    private static List<MarketEvent> strip(List<MarketEvent> es) {
        return es.stream().map(MarketSimulatorPersistTest::strip).toList();
    }

    private static Optional<AnnounceGate.Pending> stripPending(Optional<AnnounceGate.Pending> p) {
        return p.map(x -> x.withEvent(strip(x.event())));
    }

    private static Map<String, ItemStatus> stripStatus(Map<String, ItemStatus> m) {
        Map<String, ItemStatus> out = new LinkedHashMap<>();
        m.forEach((id, s) -> out.put(id, new ItemStatus(s.badge(), s.endsAt(), s.multiplier(), s.usual(), s.price(),
                s.pct(), strip(s.event()), s.phase())));
        return out;
    }

    private static void assertSameTick(MarketSimulator.TickResult want, MarketSimulator.TickResult got,
                                       boolean compareJumps, String where) {
        assertEquals(want.boundary(), got.boundary(), where);
        assertEquals(want.ticks(), got.ticks(), where);
        assertEquals(want.multipliers(), got.multipliers(), where);
        assertEquals(stripStatus(want.status()), stripStatus(got.status()), where);
        assertEquals(want.breakdowns(), got.breakdowns(), where);
        assertEquals(strip(want.started()), strip(got.started()), where);
        assertEquals(strip(want.changed()), strip(got.changed()), where);
        assertEquals(strip(want.events()), strip(got.events()), where);
        assertEquals(stripPending(want.broadcast()), stripPending(got.broadcast()), where);
        assertEquals(want.newsHeld(), got.newsHeld(), where);
        if (compareJumps) {
            assertEquals(want.jumped(), got.jumped(), where);
        }
    }

    private static void assertSameMemory(Market want, Market got) {
        assertEquals(want.seed, got.seed);
        assertEquals(want.state, got.state, "per-item rows");
        assertEquals(want.sched, got.sched);
        assertEquals(want.bs, got.bs);
        assertEquals(strip(want.events), strip(got.events), "loadLive(last_tick_at) is the working set");
    }

    @Test
    void savedThroughTheDaoAtTick97AndReloadedTheMarketContinuesExactly() throws Exception {
        int total = 3 * 288;
        int cut = 97;
        Market mem = new Market(SEED, null, null);
        Market disk = new Market(SEED, db, dao);
        Set<Long> storedBeforeRestart = new HashSet<>();
        int startedAfter = 0;
        int reloadedRowsUpdated = 0;
        int broadcastsAfter = 0;
        for (int k = 1; k <= total; k++) {
            long b = B0 + k * T;
            MarketSimulator.TickResult want = mem.tick(b);
            boolean freshStart = disk.prev == null;
            MarketSimulator.TickResult got = disk.tick(b);
            assertSameTick(want, got, !freshStart, "tick " + k);
            if (k == cut) {
                long lastTickAt = disk.restart();
                assertEquals(b, lastTickAt);
                assertSameMemory(mem, disk);
                disk.events.forEach(e -> storedBeforeRestart.add(e.id()));
                assertTrue(disk.events.stream().anyMatch(e -> e.kind().story()),
                        "a HOT or DEAL is running across the restart");
            } else if (k > cut) {
                startedAfter += got.started().size();
                reloadedRowsUpdated += (int) got.changed().stream()
                        .filter(e -> storedBeforeRestart.contains(e.id())).count();
                broadcastsAfter += got.broadcast().isPresent() ? 1 : 0;
            }
        }
        assertTrue(startedAfter > 0, "events started after the restart");
        assertTrue(reloadedRowsUpdated > 0, "rows read back were stopped, announced or ended later");
        assertTrue(broadcastsAfter > 0, "broadcasts after the restart");
        // What the database holds at the end is what the in-memory market holds.
        long last = B0 + total * T;
        Market again = new Market(0L, db, dao);
        assertEquals(last, again.restart());
        assertSameMemory(mem, again);
    }

    @Test
    void aRestartJustAfterAnEventClosedStillHasItForTheHour() throws Exception {
        Market mem = new Market(SEED, null, null);
        Market disk = new Market(SEED, db, dao);
        long b = B0;
        MarketEvent closed = null;
        for (int k = 1; k <= 5 * 288 && closed == null; k++) {
            b += T;
            assertSameTick(mem.tick(b), disk.tick(b), true, "tick " + k);
            for (MarketEvent e : mem.events) {
                if (e.endsAt() <= b && e.kind() != EventKind.SEASON) {
                    closed = e;
                }
            }
        }
        assertTrue(closed != null, "an event closed within five days");
        assertEquals(b, disk.restart());
        assertSameMemory(mem, disk);
        MarketEvent gone = strip(closed);
        assertTrue(closed.endsAt() <= b && b < closed.endsAt() + MarketSimulator.RETAIN_MS);
        assertTrue(disk.events.stream().anyMatch(e -> strip(e).equals(gone)),
                "the closed row is read back for its last hour (ending line, caches)");
        for (int k = 1; k <= 288; k++) {
            b += T;
            assertSameTick(mem.tick(b), disk.tick(b), k > 1, "after the restart, tick " + k);
        }
    }

    @Test
    void aRestartAfterDowntimeReplaysFromTheStoredLastTick() throws Exception {
        int cut = 150;
        Market mem = new Market(SEED, null, null);
        Market disk = new Market(SEED, db, dao);
        for (int k = 1; k <= cut; k++) {
            long b = B0 + k * T;
            assertSameTick(mem.tick(b), disk.tick(b), true, "tick " + k);
        }
        long lastTickAt = disk.restart();
        assertEquals(B0 + cut * T, lastTickAt);
        assertSameMemory(mem, disk);
        assertFalse(disk.events.isEmpty());

        // Down for 9 hours and 40 seconds: both catch up from the same boundary.
        long now = lastTickAt + 9 * H + 40_000;
        MarketSimulator.TickResult want = mem.catchUp(B0 + cut * T, now);
        MarketSimulator.TickResult got = disk.catchUp(lastTickAt, now);
        assertEquals(108, got.ticks(), "107 replays and the live boundary");
        assertTrue(got.live());
        assertSameTick(want, got, false, "catch-up");

        long b = got.boundary();
        for (int k = 1; k <= 2 * 288; k++) {
            b += T;
            assertSameTick(mem.tick(b), disk.tick(b), true, "after the catch-up, tick " + k);
        }
        Market again = new Market(0L, db, dao);
        assertEquals(b, again.restart());
        assertSameMemory(mem, again);
    }
}
