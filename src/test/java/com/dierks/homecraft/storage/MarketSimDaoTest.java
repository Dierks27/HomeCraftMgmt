package com.dierks.homecraft.storage;

import com.dierks.homecraft.market.sim.BroadcastState;
import com.dierks.homecraft.market.sim.EventKind;
import com.dierks.homecraft.market.sim.ItemSimState;
import com.dierks.homecraft.market.sim.MarketEvent;
import com.dierks.homecraft.market.sim.MarketSimulator;
import com.dierks.homecraft.market.sim.RealQuotes;
import com.dierks.homecraft.market.sim.Schedule;
import com.dierks.homecraft.market.sim.Source;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The live market's tables against a real in-memory SQLite (schema v32, spec §11.1, §15).
 *
 * <p>Pins: {@code Database.open} reaches v32 with all six tables and the four event indexes, and
 * running the migrations again changes nothing. Meta, per-item state, every event column
 * (nullable ones both ways), seen/mute and cached quotes read back exactly as written, doubles
 * bit for bit. {@code loadLive} is the simulator's working set: {@code ends_at} later than one
 * {@link MarketSimulator#RETAIN_MS} before now, oldest id first. The partial unique index rejects
 * a second {@code (SEASON, tag)} (and {@code insertEvent} then writes nothing and returns 0) but
 * allows any number of NULL tags. Ledger upserts accumulate. {@code markApplied} flips a quote
 * once and only once. Prune deletes by {@code ends_at}, so an event that started long ago but is
 * still active stays. {@code tradedSince} is the union of both daily tallies. The tick's writes
 * share the caller's transaction and roll back together. The {@link MarketSimDao.Meta} codec
 * round-trips {@link Schedule} and {@link BroadcastState}.
 */
class MarketSimDaoTest {

    private static final long MIN = 60_000L;
    private static final long HOUR = 3_600_000L;
    private static final long DAY = 86_400_000L;
    /** 2026-09-27 12:00 local-ish; any fixed ms works. */
    private static final long NOW = 1_790_528_400_000L;
    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BEA = UUID.fromString("00000000-0000-0000-0000-00000000000b");

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

    private <T> T tx(Database.SqlWork<T> work) throws SQLException {
        return db.transaction(work);
    }

    private long count(String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private long insert(MarketEvent e) throws SQLException {
        return tx(c -> dao.insertEvent(c, e));
    }

    private boolean update(MarketEvent e) throws SQLException {
        return tx(c -> dao.updateEvent(c, e));
    }

    private int upsertQuotes(String symbol, List<RealQuotes.DailyClose> closes, long at) throws SQLException {
        return tx(c -> dao.upsertQuotes(c, symbol, closes, at));
    }

    private boolean markApplied(String symbol, LocalDate day) throws SQLException {
        return tx(c -> dao.markApplied(c, symbol, day.toEpochDay()));
    }

    /** A HOT with every column set, so a round trip checks all 24. */
    private static MarketEvent fullHot() {
        return MarketEvent.story(EventKind.HOT, Source.SIM, "oak_log", 0.1234567890123, NOW - 10 * HOUR, 4 * HOUR,
                        30 * HOUR, 10 * HOUR).toBuilder()
                .halfLifeMs(123).lastsMs(456)
                .stoppedAt(NOW - HOUR).stopReason("stopped").endsAt(NOW)
                .pct(12.3).priceBefore(4.4721359549995796).priceAfter(5.0204)
                .headline("&fThe builders want &eOak Logs&f!").line("&6Oak Log: Crate pays about +12%")
                .announcedAt(NOW - 6 * HOUR).lastCallAt(NOW - 2 * HOUR).endLineAt(NOW - 30 * MIN)
                .build();
    }

    // ---------------------------------------------------------------- schema

    @Test
    void openReachesV32WithAllSixTablesAndTheEventIndexes() throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT value FROM hcm_meta WHERE key = 'schema_version'")) {
            assertTrue(rs.next());
            assertEquals("32", rs.getString(1));
        }
        Set<String> tables = new TreeSet<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'")) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        for (String t : List.of("market_sim_state", "market_events", "market_sim_meta", "market_sim_ledger",
                "market_news_seen", "market_real_quotes")) {
            assertTrue(tables.contains(t), t);
            assertEquals(0, count("SELECT COUNT(*) FROM " + t), t + " starts empty");
        }
        Map<String, String> indexes = new HashMap<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT name, sql FROM sqlite_master WHERE type = 'index' AND tbl_name = 'market_events'")) {
            while (rs.next()) {
                indexes.put(rs.getString(1), rs.getString(2));
            }
        }
        assertTrue(indexes.containsKey("idx_market_events_ends"));
        assertTrue(indexes.containsKey("idx_market_events_started"));
        assertTrue(indexes.containsKey("idx_market_events_item"));
        String tag = indexes.get("idx_market_events_tag");
        assertTrue(tag.startsWith("CREATE UNIQUE INDEX"), tag);
        assertTrue(tag.endsWith("WHERE tag IS NOT NULL"), tag);
        assertEquals(24, count("SELECT COUNT(*) FROM pragma_table_info('market_events')"));

        // Opening again runs nothing: the schema stays at 32 and the data survives.
        tx(c -> {
            dao.saveMeta(c, Map.of("k", "v"));
            return null;
        });
        Database.open(conn, Logger.getAnonymousLogger());
        assertEquals(Map.of("k", "v"), dao.loadMeta());
    }

    // ---------------------------------------------------------------- meta

    @Test
    void metaRoundTripsOverwritesAndDeletesOnNull() throws Exception {
        assertTrue(dao.loadMeta().isEmpty());
        tx(c -> {
            dao.saveMeta(c, Map.of("seed", "9e3779b97f4a7c15", "last_tick_at", "1790528400000", "paused", "0"));
            return null;
        });
        assertEquals(Map.of("seed", "9e3779b97f4a7c15", "last_tick_at", "1790528400000", "paused", "0"),
                dao.loadMeta());

        Map<String, String> change = new HashMap<>();
        change.put("paused", "1");
        change.put("last_tick_at", null); // deletes
        change.put("recent_up", "");      // an empty value is a value
        dao.saveMeta(change);
        assertEquals(Map.of("seed", "9e3779b97f4a7c15", "paused", "1", "recent_up", ""), dao.loadMeta());

        dao.saveMeta(Map.of());
        assertEquals(3, dao.loadMeta().size(), "an empty map changes nothing");
    }

    @Test
    void theMetaCodecRoundTripsScheduleAndBroadcastState() throws Exception {
        Map<String, List<Integer>> recent = new LinkedHashMap<>();
        recent.put("up", List.of(3, 7, 1, 0));
        recent.put("wanted", List.of(2));
        Schedule s = new Schedule(NOW + 17 * MIN, NOW + 95 * MIN, NOW + 40 * MIN, 20_723L, 2, recent);
        BroadcastState b = new BroadcastState(20_723L, 5, NOW - 25 * MIN, true);

        Map<String, String> rows = new LinkedHashMap<>(MarketSimDao.Meta.of(s));
        rows.putAll(MarketSimDao.Meta.of(b));
        assertEquals("3,7,1,0", rows.get("recent_up"));
        assertEquals("", rows.get("recent_down"), "every list is written, so a cleared memory overwrites");
        assertEquals("1", rows.get("intro_done"));
        dao.saveMeta(rows);

        Map<String, String> back = dao.loadMeta();
        assertEquals(s, MarketSimDao.Meta.schedule(back));
        assertEquals(b, MarketSimDao.Meta.broadcast(back));

        // Nothing counted yet: NO_DAY is stored as "" and reads back as NO_DAY.
        Schedule fresh = new Schedule(NOW, NOW, NOW, Schedule.NO_DAY, 0, null);
        BroadcastState none = new BroadcastState();
        dao.saveMeta(MarketSimDao.Meta.of(fresh));
        dao.saveMeta(MarketSimDao.Meta.of(none));
        back = dao.loadMeta();
        assertEquals("", back.get("news_day"));
        assertEquals(fresh, MarketSimDao.Meta.schedule(back));
        assertEquals(none, MarketSimDao.Meta.broadcast(back));

        // An empty table (first start) and hand-edited junk both read as the defaults.
        assertEquals(new Schedule(0, 0, 0, Schedule.NO_DAY, 0, null), MarketSimDao.Meta.schedule(Map.of()));
        assertEquals(new BroadcastState(), MarketSimDao.Meta.broadcast(Map.of()));
        assertEquals(new BroadcastState(Schedule.NO_DAY, 0, 0L, true), MarketSimDao.Meta.broadcast(
                Map.of("broadcast_day", "x", "broadcasts_today", "-4", "intro_done", "true")));
    }

    @Test
    void theSeedIsStoredAsUnsignedHex() {
        for (long seed : new long[]{0L, 1L, -1L, Long.MIN_VALUE, 0x9E3779B97F4A7C15L}) {
            String hex = MarketSimDao.Meta.seedHex(seed);
            assertTrue(hex.matches("[0-9a-f]{1,16}"), hex);
            assertEquals(OptionalLong.of(seed), MarketSimDao.Meta.seed(Map.of("seed", hex)));
        }
        assertEquals(OptionalLong.of(0x9E3779B97F4A7C15L), MarketSimDao.Meta.seed(Map.of("seed", " 9E3779B97F4A7C15 ")));
        assertEquals(OptionalLong.empty(), MarketSimDao.Meta.seed(Map.of()));
        assertEquals(OptionalLong.empty(), MarketSimDao.Meta.seed(Map.of("seed", "not hex")));
        assertEquals(OptionalLong.empty(), MarketSimDao.Meta.seed(Map.of("seed", "1ffffffffffffffff")));
    }

    // ---------------------------------------------------------------- states

    @Test
    void statesRoundTripExactlyAndUpsert() throws Exception {
        assertTrue(dao.loadStates().isEmpty(), "no rows: the caller treats every item as fresh");
        ItemSimState oak = new ItemSimState("oak_log", 0.012345678901234567, NOW - 3 * DAY, NOW + 2 * DAY,
                0L, NOW);
        ItemSimState iron = new ItemSimState("iron_ingot", -0.08, 0L, 0L, NOW - 8 * DAY, NOW);
        tx(c -> {
            dao.saveStates(c, List.of(oak, iron));
            return null;
        });
        Map<String, ItemSimState> back = dao.loadStates();
        assertEquals(Map.of("oak_log", oak, "iron_ingot", iron), back);
        assertEquals(Double.doubleToRawLongBits(oak.drift()), Double.doubleToRawLongBits(back.get("oak_log").drift()));

        ItemSimState oak2 = oak.withDrift(-0.0031, NOW + 5 * MIN).withLastNewsAt(NOW);
        tx(c -> {
            dao.saveStates(c, List.of(oak2));
            return null;
        });
        assertEquals(Map.of("oak_log", oak2, "iron_ingot", iron), dao.loadStates());
        assertEquals(2, count("SELECT COUNT(*) FROM market_sim_state"));
    }

    // ---------------------------------------------------------------- events

    @Test
    void everyEventColumnRoundTrips() throws Exception {
        MarketEvent hot = fullHot();
        MarketEvent season = MarketEvent.info(EventKind.SEASON, Source.CALENDAR, null, "harvest_time:2026",
                NOW - DAY, NOW + 30 * DAY).withPrices(-4.0, 0.0, 0.0).withText("Harvest time!", null);
        MarketEvent real = MarketEvent.shock(EventKind.REAL, Source.REAL, "gold_ingot", -0.03, NOW - HOUR,
                24 * HOUR, 96 * HOUR).toBuilder().tag("gold_ingot:gc.f:20723").announceDueAt(null).build();

        long a = insert(hot);
        long b = insert(season);
        long c = insert(real);
        assertTrue(a > 0 && b > a && c > b, "fresh ids in insert order");

        List<MarketEvent> live = dao.loadLive(NOW);
        assertEquals(List.of(hot.withId(a), season.withId(b), real.withId(c)), live);
        MarketEvent back = live.get(0);
        assertEquals(Double.doubleToRawLongBits(hot.strength()), Double.doubleToRawLongBits(back.strength()));
        assertEquals(Double.doubleToRawLongBits(hot.priceBefore()), Double.doubleToRawLongBits(back.priceBefore()));
        assertNull(live.get(1).itemId(), "a SEASON has no item");
        assertNull(live.get(1).stoppedAt());
        assertNull(live.get(1).line());
        assertNull(live.get(2).announceDueAt(), "a REAL without a headline is never due");
        assertEquals(c, dao.maxEventId());

        // An update rewrites every column but the id.
        MarketEvent shown = real.withId(c).withAnnouncedAt(NOW).withLastCallAt(null).withEnd(NOW + HOUR, "reset");
        assertTrue(update(shown));
        assertEquals(shown, dao.loadLive(NOW).get(2));
        assertFalse(update(shown.withId(999)), "no such row");
        assertFalse(update(shown.withId(0)), "never inserted");
        assertEquals(3, count("SELECT COUNT(*) FROM market_events"));
    }

    @Test
    void loadLiveIsTheSimulatorsWorkingSet() throws Exception {
        long retain = MarketSimulator.RETAIN_MS;
        MarketEvent up = MarketEvent.shock(EventKind.UP, Source.SIM, "wheat", 0.2, NOW - 40 * HOUR, 6 * HOUR,
                30 * HOUR);                                               // closed 10 h ago
        MarketEvent justGone = up.toBuilder().itemId("iron_ingot").endsAt(NOW - retain).build();
        MarketEvent lingering = up.toBuilder().itemId("oak_log").endsAt(NOW - retain + 1).build();
        MarketEvent running = MarketEvent.shock(EventKind.DOWN, Source.ADMIN, "cobblestone", 0.15, NOW - HOUR,
                6 * HOUR, 30 * HOUR);
        long idRunning = insert(running);  // inserted first, so it comes first
        insert(up);
        insert(justGone);
        long idLingering = insert(lingering);

        List<MarketEvent> live = dao.loadLive(NOW);
        assertEquals(List.of(running.withId(idRunning), lingering.withId(idLingering)), live,
                "ends_at > now - 1h, oldest id first; exactly an hour gone is out");
        assertTrue(dao.loadLive(NOW + 40 * HOUR).isEmpty());
    }

    @Test
    void recentNewsIsNewestFirstWithinTheWindowAndLimit() throws Exception {
        MarketEvent old = MarketEvent.shock(EventKind.UP, Source.SIM, "wheat", 0.2, NOW - 31 * DAY, 6 * HOUR, 30 * HOUR);
        insert(old);
        long w1 = insert(MarketEvent.info(EventKind.WANTED, Source.SIM, "diamond", null, NOW - 3 * DAY, NOW + 4 * DAY));
        long h = insert(MarketEvent.story(EventKind.HOT, Source.SIM, "oak_log", 0.1, NOW - 2 * DAY, 4 * HOUR,
                30 * HOUR, 10 * HOUR));
        long d = insert(MarketEvent.shock(EventKind.DOWN, Source.SIM, "iron_ingot", 0.2, NOW - 2 * DAY, 6 * HOUR,
                30 * HOUR));
        long w2 = insert(MarketEvent.info(EventKind.WANTED, Source.ADMIN, "gold_ingot", null, NOW - HOUR, NOW + DAY));

        List<Long> ids = dao.loadRecentNews(NOW - 30 * DAY, 300).stream().map(MarketEvent::id).toList();
        assertEquals(List.of(w2, d, h, w1), ids, "newest first; same start: highest id first; 31 days ago is out");
        assertEquals(List.of(w2, d), dao.loadRecentNews(NOW - 30 * DAY, 2).stream().map(MarketEvent::id).toList());
        assertTrue(dao.loadRecentNews(NOW - 30 * DAY, 0).isEmpty());
        assertEquals(List.of(w2), dao.loadRecentNews(NOW - HOUR, 10).stream().map(MarketEvent::id).toList(),
                "started exactly at since is in");
    }

    @Test
    void theTagIndexRejectsADuplicateSeasonButAllowsNullTags() throws Exception {
        MarketEvent harvest = MarketEvent.info(EventKind.SEASON, Source.CALENDAR, null, "harvest_time:2026",
                NOW, NOW + 30 * DAY);
        long first = insert(harvest);
        assertTrue(first > 0);
        assertEquals(0L, insert(harvest.toBuilder().startedAt(NOW + 5 * MIN).build()),
                "the same season and year again: nothing written");
        assertEquals(1, count("SELECT COUNT(*) FROM market_events WHERE kind = 'SEASON'"));
        assertEquals(NOW, dao.loadLive(NOW).get(0).startedAt(), "the stored row is untouched");

        // The index itself refuses a raw duplicate too.
        assertThrows(SQLException.class, () -> {
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("INSERT INTO market_events(kind, source, tag, started_at, ends_at) "
                        + "VALUES('SEASON', 'CALENDAR', 'harvest_time:2026', 1, 2)");
            }
        });

        // Another year, and the same text under another kind, are different rows.
        assertTrue(insert(harvest.toBuilder().tag("harvest_time:2027").build()) > 0);
        assertTrue(insert(MarketEvent.shock(EventKind.REAL, Source.REAL, "wheat", -0.02, NOW, 24 * HOUR, 96 * HOUR)
                .toBuilder().tag("harvest_time:2026").build()) > 0);
        // NULL tags never collide.
        for (int i = 0; i < 3; i++) {
            assertTrue(insert(MarketEvent.info(EventKind.WANTED, Source.SIM, "diamond", null, NOW, NOW + DAY)) > 0);
            assertTrue(insert(MarketEvent.shock(EventKind.UP, Source.SIM, "wheat", 0.2, NOW, 6 * HOUR, 30 * HOUR)) > 0);
        }
        assertEquals(9, count("SELECT COUNT(*) FROM market_events"));
    }

    @Test
    void rowsOfAnUnknownKindAreSkippedNotFatal() throws Exception {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("INSERT INTO market_events(kind, source, item_id, started_at, ends_at) "
                    + "VALUES('CRASH', 'SIM', 'wheat', " + NOW + ", " + (NOW + DAY) + ")");
            st.executeUpdate("INSERT INTO market_events(kind, source, item_id, started_at, ends_at) "
                    + "VALUES('UP', 'ALIENS', 'wheat', " + NOW + ", " + (NOW + DAY) + ")");
        }
        long ok = insert(MarketEvent.info(EventKind.WANTED, Source.SIM, "diamond", null, NOW, NOW + DAY));
        assertEquals(List.of(ok), dao.loadLive(NOW).stream().map(MarketEvent::id).toList());
        assertEquals(List.of(ok), dao.loadRecentNews(0, 10).stream().map(MarketEvent::id).toList());
    }

    @Test
    void outOfLimitRowsComeBackClamped() throws Exception {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("INSERT INTO market_events(kind, source, item_id, strength, started_at, ends_at) "
                    + "VALUES('HOT', 'ADMIN', 'wheat', 0.9, " + NOW + ", " + (NOW + DAY) + ")");
            st.executeUpdate("INSERT INTO market_sim_state(item_id, drift, updated_at) VALUES('wheat', -0.5, 0)");
        }
        assertEquals(0.15, dao.loadLive(NOW).get(0).strength(), "a hand-edited HOT is still at most +15%");
        assertEquals(-0.08, dao.loadStates().get("wheat").drift(), "hand-edited drift is still at most 8%");
    }

    @Test
    void theTicksWritesRollBackTogether() throws Exception {
        MarketEvent hot = fullHot();
        assertThrows(SQLException.class, () -> db.transaction(c -> {
            dao.insertEvent(c, hot);
            dao.saveStates(c, List.of(ItemSimState.fresh("oak_log")));
            dao.saveMeta(c, Map.of(MarketSimDao.Meta.LAST_TICK_AT, "123"));
            dao.addLedger(c, 20_723, "oak_log", 1.0, 0.0, 1, 0);
            dao.upsertQuotes(c, "gc.f", List.of(new RealQuotes.DailyClose(LocalDate.of(2026, 9, 25), 2650.0)), NOW);
            throw new SQLException("the tick failed");
        }));
        assertEquals(0, count("SELECT COUNT(*) FROM market_events"));
        assertEquals(0, count("SELECT COUNT(*) FROM market_sim_state"));
        assertEquals(0, count("SELECT COUNT(*) FROM market_sim_meta"));
        assertEquals(0, count("SELECT COUNT(*) FROM market_sim_ledger"));
        assertEquals(0, count("SELECT COUNT(*) FROM market_real_quotes"));
        assertTrue(conn.getAutoCommit(), "the connection is back in auto-commit");
    }

    // ---------------------------------------------------------------- ledger

    @Test
    void theLedgerUpsertAccumulates() throws Exception {
        tx(c -> {
            dao.addLedger(c, 20_723, "iron_ingot", 12.5, 0.0, 16, 0);
            dao.addLedger(c, 20_723, "iron_ingot", -0.25, 3.75, 4, 10);
            dao.addLedger(c, 20_723, "oak_log", 0.0, 1.5, 0, 64);
            dao.addLedger(c, 20_724, "iron_ingot", 2.0, 0.0, 1, 0);
            dao.addLedger(c, 20_700, "wheat", 9.0, 9.0, 9, 9);
            return null;
        });
        tx(c -> {
            dao.addLedger(c, 20_723, "iron_ingot", 0.75, 0.25, 0, 2);
            return null;
        });
        List<MarketSimDao.LedgerRow> rows = dao.ledgerSince(20_723);
        assertEquals(List.of(
                new MarketSimDao.LedgerRow(20_723, "iron_ingot", 13.0, 4.0, 20, 12),
                new MarketSimDao.LedgerRow(20_723, "oak_log", 0.0, 1.5, 0, 64),
                new MarketSimDao.LedgerRow(20_724, "iron_ingot", 2.0, 0.0, 1, 0)), rows);
        assertEquals(4, dao.ledgerSince(0).size());
        assertTrue(dao.ledgerSince(20_725).isEmpty());
    }

    // ---------------------------------------------------------------- seen and mute

    @Test
    void seenAndMuteRoundTrip() throws Exception {
        assertTrue(dao.seen(ALEX).isEmpty(), "no row: only the Right now line");
        dao.setSeen(ALEX, 42, NOW);
        assertEquals(new MarketSimDao.Seen(ALEX, 42, false, NOW), dao.seen(ALEX).orElseThrow());

        dao.setSeen(ALEX, 57, NOW + 20 * MIN);
        assertEquals(new MarketSimDao.Seen(ALEX, 57, false, NOW + 20 * MIN), dao.seen(ALEX).orElseThrow());
        dao.setSeen(ALEX, 40, NOW + 40 * MIN);
        assertEquals(new MarketSimDao.Seen(ALEX, 57, false, NOW + 40 * MIN), dao.seen(ALEX).orElseThrow(),
                "the mark never goes back");

        dao.setMuted(ALEX, true);
        assertEquals(new MarketSimDao.Seen(ALEX, 57, true, NOW + 40 * MIN), dao.seen(ALEX).orElseThrow());
        dao.setSeen(ALEX, 60, NOW + HOUR);
        assertTrue(dao.seen(ALEX).orElseThrow().muted(), "a catch-up write keeps the mute");
        dao.setMuted(ALEX, false);
        assertFalse(dao.seen(ALEX).orElseThrow().muted());
    }

    @Test
    void aNewRowFromMuteStartsAtTheNewestEvent() throws Exception {
        dao.setMuted(BEA, true);
        assertEquals(new MarketSimDao.Seen(BEA, 0, true, 0), dao.seen(BEA).orElseThrow(), "no events yet");
        UUID cam = UUID.fromString("00000000-0000-0000-0000-00000000000c");
        insert(MarketEvent.info(EventKind.WANTED, Source.SIM, "diamond", null, NOW, NOW + DAY));
        long newest = insert(MarketEvent.shock(EventKind.UP, Source.SIM, "wheat", 0.2, NOW, 6 * HOUR, 30 * HOUR));
        dao.setMuted(cam, false);
        assertEquals(new MarketSimDao.Seen(cam, newest, false, 0), dao.seen(cam).orElseThrow(),
                "unmuting later does not replay old news");
    }

    @Test
    void aBroadcastAdvancesEveryoneItReached() throws Exception {
        dao.setSeen(ALEX, 70, NOW);
        dao.setMuted(BEA, true);
        UUID cam = UUID.fromString("00000000-0000-0000-0000-00000000000c");
        dao.advanceSeen(List.of(ALEX, cam), 64);
        assertEquals(new MarketSimDao.Seen(ALEX, 70, false, NOW), dao.seen(ALEX).orElseThrow(), "never back");
        assertEquals(new MarketSimDao.Seen(cam, 64, false, 0), dao.seen(cam).orElseThrow());
        dao.advanceSeen(List.of(ALEX, cam), 80);
        assertEquals(80, dao.seen(ALEX).orElseThrow().lastEventId());
        assertEquals(NOW, dao.seen(ALEX).orElseThrow().seenAt(), "seen_at is the catch-up clock: untouched");
        assertEquals(80, dao.seen(cam).orElseThrow().lastEventId());
        assertEquals(0, dao.seen(BEA).orElseThrow().lastEventId(), "not reached, not moved");
        dao.advanceSeen(List.of(), 99);
    }

    // ---------------------------------------------------------------- quotes

    @Test
    void quotesRoundTripAndMarkAppliedIsIdempotent() throws Exception {
        LocalDate d1 = LocalDate.of(2026, 9, 24);
        LocalDate d2 = LocalDate.of(2026, 9, 25);
        List<RealQuotes.DailyClose> closes = List.of(
                new RealQuotes.DailyClose(d2, 2688.25),
                new RealQuotes.DailyClose(d1, 2648.5),
                new RealQuotes.DailyClose(LocalDate.of(2026, 9, 23), Double.NaN),
                new RealQuotes.DailyClose(LocalDate.of(2026, 9, 22), 0.0));
        assertEquals(2, upsertQuotes("gc.f", closes, NOW), "bad closes are skipped");
        upsertQuotes("zw.f", List.of(new RealQuotes.DailyClose(d2, 5.4125)), NOW);

        List<MarketSimDao.StoredQuote> gold = dao.quotes("gc.f", 0);
        assertEquals(List.of(
                new MarketSimDao.StoredQuote("gc.f", d1.toEpochDay(), 2648.5, NOW, false),
                new MarketSimDao.StoredQuote("gc.f", d2.toEpochDay(), 2688.25, NOW, false)), gold, "oldest first");
        assertEquals(new RealQuotes.DailyClose(d2, 2688.25), gold.get(1).dailyClose());
        assertEquals(1, dao.quotes("gc.f", d2.toEpochDay()).size());
        assertTrue(dao.quotes("hg.f", 0).isEmpty());

        assertTrue(markApplied("gc.f", d2), "the first call flips it");
        assertFalse(markApplied("gc.f", d2), "…and only the first");
        assertFalse(markApplied("gc.f", LocalDate.of(2026, 9, 26)), "no such quote");
        assertTrue(dao.quotes("gc.f", d2.toEpochDay()).get(0).applied());
        assertFalse(dao.quotes("zw.f", 0).get(0).applied(), "per symbol");

        // A re-fetch updates the close and time but never un-applies a day.
        upsertQuotes("gc.f", List.of(new RealQuotes.DailyClose(d2, 2690.0)), NOW + HOUR);
        assertEquals(new MarketSimDao.StoredQuote("gc.f", d2.toEpochDay(), 2690.0, NOW + HOUR, true),
                dao.quotes("gc.f", d2.toEpochDay()).get(0));
        assertFalse(markApplied("gc.f", d2));
        assertEquals(3, count("SELECT COUNT(*) FROM market_real_quotes"));
    }

    // ---------------------------------------------------------------- prune

    @Test
    void pruneDeletesByEndSoActiveEventsStay() throws Exception {
        long cutoff = NOW - 60 * DAY;
        long gone = insert(MarketEvent.shock(EventKind.UP, Source.SIM, "wheat", 0.2, NOW - 90 * DAY, 6 * HOUR,
                30 * HOUR));
        long edge = insert(MarketEvent.info(EventKind.WANTED, Source.SIM, "diamond", null, NOW - 70 * DAY, cutoff));
        long recent = insert(MarketEvent.shock(EventKind.DOWN, Source.SIM, "oak_log", 0.2, NOW - 59 * DAY,
                6 * HOUR, 30 * HOUR));
        // Started 80 days ago and still running (a long season): active, so it stays.
        long active = insert(MarketEvent.info(EventKind.SEASON, Source.CALENDAR, null, "cozy_winter:2026",
                NOW - 80 * DAY, NOW + 10 * DAY));
        long justBefore = insert(MarketEvent.info(EventKind.WANTED, Source.SIM, "gold_ingot", null, NOW - 70 * DAY,
                cutoff - 1));

        long today = 20_723;
        tx(c -> {
            dao.addLedger(c, today - 400, "iron_ingot", 1, 0, 1, 0);
            dao.addLedger(c, today - 401, "iron_ingot", 1, 0, 1, 0);
            dao.addLedger(c, today, "iron_ingot", 1, 0, 1, 0);
            dao.upsertQuotes(c, "gc.f", List.of(
                    new RealQuotes.DailyClose(LocalDate.ofEpochDay(today - 31), 2500.0),
                    new RealQuotes.DailyClose(LocalDate.ofEpochDay(today - 30), 2510.0),
                    new RealQuotes.DailyClose(LocalDate.ofEpochDay(today - 1), 2600.0)), NOW);
            return null;
        });

        MarketSimDao.Pruned p = dao.prune(cutoff, today - MarketSimDao.LEDGER_KEEP_DAYS,
                today - MarketSimDao.QUOTE_KEEP_DAYS);
        assertEquals(new MarketSimDao.Pruned(2, 1, 1), p);

        Set<Long> left = new TreeSet<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM market_events");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                left.add(rs.getLong(1));
            }
        }
        assertEquals(new TreeSet<>(List.of(edge, recent, active)), left, "ended exactly at the cutoff stays");
        assertFalse(left.contains(gone));
        assertFalse(left.contains(justBefore));
        assertEquals(List.of(today - 400, today),
                dao.ledgerSince(0).stream().map(MarketSimDao.LedgerRow::day).toList());
        assertEquals(List.of(today - 30, today - 1), dao.quotes("gc.f", 0).stream()
                .map(MarketSimDao.StoredQuote::tradeDay).toList());
        assertEquals(new MarketSimDao.Pruned(0, 0, 0), dao.prune(cutoff, today - 400, today - 30), "idempotent");
    }

    // ---------------------------------------------------------------- popular

    @Test
    void tradedSinceIsTheUnionOfSellsAndBuys() throws Exception {
        DailySellDao sells = new DailySellDao(db);
        DailyBuyDao buys = new DailyBuyDao(db);
        long today = 20_723;
        sells.record(ALEX, today, "wheat", 64, 200.0);
        sells.record(BEA, today - 13, "oak_log", 16, 60.0);
        buys.record(ALEX, today - 2, "iron_ingot", 4, 90.0);
        buys.record(BEA, today - 1, "wheat", 8, 30.0);          // on both sides: listed once
        buys.record(ALEX, today - 14, "diamond", 1, 400.0);     // too old
        sells.record(BEA, today, "cobblestone", 0, 0.0);        // a zero tally is not a trade

        assertEquals(new TreeSet<>(List.of("iron_ingot", "oak_log", "wheat")), dao.tradedSince(today - 13));
        assertEquals(new TreeSet<>(List.of("diamond", "iron_ingot", "oak_log", "wheat")), dao.tradedSince(today - 14));
        assertEquals(new TreeSet<>(List.of("wheat")), dao.tradedSince(today));
        assertTrue(dao.tradedSince(today + 1).isEmpty());
    }
}
