package com.dierks.homecraft.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The trade log (SHOP-V2-SPEC §4.12, schema v38): insert, the newest trades of either side, one by id, PENDING
 * flipped only once, the prune by days that never takes a PENDING row, a write that rolls back with its caller, and
 * the Mailbox's HELD rows released or voided by their trade's tag.
 */
class TradeDaoTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID HELPER = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final long DAY = 86_400_000L;

    private Connection conn;
    private Database db;
    private TradeDao dao;

    @BeforeEach
    void open() throws SQLException {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        db = Database.open(conn, Logger.getAnonymousLogger());
        dao = new TradeDao(db);
    }

    @AfterEach
    void close() throws SQLException {
        conn.close();
    }

    private static TradeDao.Trade row(UUID a, UUID b, long closedAt, String outcome) {
        return new TradeDao.Trade(0, a, "A-name", b, "B-name", "world", closedAt - 5_000, closedAt, outcome, "",
                "b64-one\nb64-two", "", "3 Diamond, Creeper Mini", "", 0, 50.0, 1);
    }

    @Test
    void insertAndReadBack() throws Exception {
        long id = dao.insert(row(OWNER, HELPER, 10 * DAY, TradeDao.DONE));
        TradeDao.Trade t = dao.byId(id).orElseThrow();
        assertEquals(OWNER, t.a());
        assertEquals(HELPER, t.b());
        assertEquals("b64-one\nb64-two", t.aItems());
        assertEquals("3 Diamond, Creeper Mini", t.aSummary());
        assertEquals(50.0, t.bMoney());
        assertEquals(1, t.mailbox());
        assertTrue(dao.byId(id + 1).isEmpty());
    }

    @Test
    void theNewestOfEitherSide() throws Exception {
        long first = dao.insert(row(OWNER, HELPER, DAY, TradeDao.DONE));
        long second = dao.insert(row(HELPER, OTHER, 2 * DAY, TradeDao.CANCELLED));
        long third = dao.insert(row(OTHER, OWNER, 3 * DAY, TradeDao.DONE));
        assertEquals(List.of(third, first), ids(dao.recent(OWNER, 10)), "as a or b, newest first");
        assertEquals(List.of(second, first), ids(dao.recent(HELPER, 10)));
        assertEquals(List.of(third, second), ids(dao.recent(null, 2)), "everyone's, the last n");
    }

    @Test
    void pendingIsFlippedOnce() throws Exception {
        long id = dao.insert(row(OWNER, HELPER, DAY, TradeDao.PENDING));
        assertEquals(1, dao.pending().size());
        assertTrue(dao.finish(id, TradeDao.PENDING, TradeDao.DONE, ""));
        assertFalse(dao.finish(id, TradeDao.PENDING, TradeDao.VOID, "settled"), "only from PENDING");
        assertEquals(TradeDao.DONE, dao.byId(id).orElseThrow().outcome());
        assertEquals(List.of(), dao.pending());
        dao.addMailbox(id, 2);
        assertEquals(3, dao.byId(id).orElseThrow().mailbox());
    }

    @Test
    void thePruneKeepsPendingRows() throws Exception {
        long old = dao.insert(row(OWNER, HELPER, DAY, TradeDao.DONE));
        long oldPending = dao.insert(row(OWNER, HELPER, DAY, TradeDao.PENDING));
        long recent = dao.insert(row(OWNER, HELPER, 100 * DAY, TradeDao.DONE));
        assertEquals(1, dao.prune(10 * DAY));
        assertTrue(dao.byId(old).isEmpty());
        assertTrue(dao.byId(oldPending).isPresent(), "a PENDING row is never pruned");
        assertTrue(dao.byId(recent).isPresent());
    }

    /**
     * Red-team finding SETTLE-PRUNE-3: an old PENDING trade's void reads what later trades moved, and a collector claim
     * has nothing else to say a later trade carried it. A redo pruned past {@code log_keep_days} let the void take the
     * claim from the player who holds the cap. No row after the oldest PENDING one is pruned.
     */
    @Test
    void thePruneKeepsEveryRowAfterAWaitingTrade() throws Exception {
        long before = dao.insert(row(OWNER, HELPER, DAY, TradeDao.DONE));
        long waiting = dao.insert(row(OWNER, HELPER, 2 * DAY, TradeDao.PENDING));
        long redo = dao.insert(row(OWNER, HELPER, 3 * DAY, TradeDao.DONE));
        long cancelled = dao.insert(row(OWNER, OTHER, 4 * DAY, TradeDao.CANCELLED));
        assertEquals(1, dao.prune(200 * DAY));
        assertTrue(dao.byId(before).isEmpty(), "a row before every waiting trade goes");
        assertTrue(dao.byId(waiting).isPresent());
        assertTrue(dao.byId(redo).isPresent(), "[X] the redo after it stays while it waits");
        assertTrue(dao.byId(cancelled).isPresent());
        assertTrue(dao.finish(waiting, TradeDao.PENDING, TradeDao.VOID, "settled: voided"));
        assertEquals(3, dao.prune(200 * DAY), "settled: they all go");
    }

    @Test
    void aWriteRollsBackWithItsCaller() {
        assertThrows(SQLException.class, () -> db.transaction(c -> {
            dao.insert(row(OWNER, HELPER, DAY, TradeDao.PENDING));
            throw new SQLException("a later write failed");
        }));
        assertEquals(0, ids(recentAll()).size());
    }

    @Test
    void heldMailboxRowsAreReleasedOrVoidedByTheirTradesTag() throws Exception {
        DeliveryDao mail = new DeliveryDao(db);
        mail.insert(HELPER, "x", "From your trade with A-name", "TRADE#7", 1, 1, DeliveryDao.HELD);
        mail.insert(HELPER, "y", "From your trade with A-name", "TRADE#8", 1, 1, DeliveryDao.HELD);
        assertEquals(List.of(), mail.activeFor(HELPER), "HELD rows aren't in the Mailbox");
        assertEquals(List.of(), mail.findDue(Long.MAX_VALUE), "nor does the delivery tick touch them");
        assertEquals(1, mail.release("TRADE#7", 99));
        assertEquals(1, mail.activeFor(HELPER).size());
        assertEquals(DeliveryDao.READY, mail.activeFor(HELPER).get(0).status());
        assertEquals(0, mail.release("TRADE#7", 99), "once");
        assertEquals(1, mail.deleteHeld("TRADE#8"));
        assertEquals(1, mail.bySource("TRADE#7").size());
        assertEquals(List.of(), mail.bySource("TRADE#8"));
    }

    /**
     * Red-team findings AW-1, AW-2: a DONE trade is never moved back to PENDING (the start's re-hold of a finished
     * trade whose bag read from before it is gone), so nothing can hold a Mailbox row a player was told is theirs.
     */
    @Test
    void aFinishedTradeNeverGoesBackToPending() throws Exception {
        long id = dao.insert(row(OWNER, HELPER, DAY, TradeDao.DONE));
        String src = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/dierks/homecraft/storage/DeliveryDao.java"));
        assertFalse(src.contains("public int hold("), "[X] no way to turn a READY row back to HELD");
        String store = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/dierks/homecraft/trade/TradeStore.java"));
        assertFalse(store.contains("TradeDao.DONE, TradeDao.PENDING"), "[X] no DONE to PENDING move");
        assertEquals(TradeDao.DONE, dao.byId(id).orElseThrow().outcome());
        assertEquals(List.of(), dao.pending());
    }

    /**
     * Red-team finding TCR4-5: a side is kept whole until nothing needs it, then trimmed past the limit (its summary
     * says so); a side under it stays.
     */
    @Test
    void aSidePastTheLimitIsTrimmedOnlyWhenAsked() throws Exception {
        String big = "z".repeat(200);
        long id = dao.insert(new TradeDao.Trade(0, OWNER, "A-name", HELPER, "B-name", "world", 0, DAY, TradeDao.DONE,
                "", big, "small", "Books", "Iron", 0, 0, 0));
        assertEquals(big, dao.byId(id).orElseThrow().aItems(), "kept whole");
        dao.trim(id, 100);
        TradeDao.Trade t = dao.byId(id).orElseThrow();
        assertEquals("", t.aItems());
        assertEquals("Books (too big to store)", t.aSummary());
        assertEquals("small", t.bItems());
        assertEquals("Iron", t.bSummary());
        dao.trim(id, 100);
        assertEquals("Books (too big to store)", dao.byId(id).orElseThrow().aSummary(), "once");
    }

    /** Red-team finding TCR4-3: each side's balance just before the swap's money moved, null when unknown. */
    @Test
    void theBalancesBeforeTheMoneyMoved() throws Exception {
        long id = dao.insert(row(OWNER, HELPER, DAY, TradeDao.PENDING));
        assertEquals(List.of(), java.util.Arrays.stream(dao.before(id)).filter(java.util.Objects::nonNull).toList());
        dao.before(id, 12.5, null);
        assertEquals(12.5, dao.before(id)[0]);
        assertEquals(null, dao.before(id)[1]);
    }

    /** Red-team findings R4FA-2, TCR4-1: what later trades that still stand carried, for an older trade's void. */
    @Test
    void whatLaterTradesCarried() throws Exception {
        long one = dao.insert(row(OWNER, HELPER, DAY, TradeDao.PENDING));
        long two = dao.insert(row(OWNER, HELPER, 2 * DAY, TradeDao.DONE));
        long three = dao.insert(row(OWNER, HELPER, 3 * DAY, TradeDao.VOID));
        long four = dao.insert(row(OWNER, HELPER, 4 * DAY, TradeDao.PENDING));
        dao.insert(row(OWNER, HELPER, 5 * DAY, TradeDao.DONE)); // moved nothing
        dao.moved(one, "cap\tc\ta\tb");
        dao.moved(two, "mini\tu\ta\tb");
        dao.moved(three, "claim\tc\ta\tb");
        dao.moved(four, "cap\tc\tb\ta");
        assertEquals(List.of(new TradeDao.Moved(two, TradeDao.DONE, "mini\tu\ta\tb"),
                new TradeDao.Moved(four, TradeDao.PENDING, "cap\tc\tb\ta")), dao.movedAfter(one),
                "[X] a voided trade put its own moves back: it doesn't count; oldest first, with whether it waits");
    }

    private List<TradeDao.Trade> recentAll() {
        try {
            return dao.recent(null, 100);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<Long> ids(List<TradeDao.Trade> rows) {
        return rows.stream().map(TradeDao.Trade::id).toList();
    }
}
