package com.dierks.homecraft.storage;

import com.dierks.homecraft.games.event.PayLoop;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Race Night's rows (EVENTS-DROPPER-SPEC §A.9) on a real migrated database. Pinned here: a race is
 * stored in ONE transaction (its rows, the night's points, the season board and {@code races_done}
 * all land or none do); storing a race again adds nothing; the pay loop pays exactly once across a
 * crash between the payment and {@code paid_at}; an owed prize is paid at the next join, once; the
 * week's prize slots stop at the limit; settling never changes a prize already paid; and the
 * admin's settings stay under {@code race.}.
 */
class EventDaoTest {

    private static final long NOW = 1_790_967_600_000L;
    private static final String ID = "rn-20261002-1900";
    private static final String SEASON = "rnseason:2026-10";
    private static final UUID A = new UUID(0, 1);
    private static final UUID B = new UUID(0, 2);

    private Connection conn;
    private GamesDao games;
    private EventDao dao;

    @BeforeEach
    void setUp() throws Exception {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        Database db = Database.open(conn, Logger.getAnonymousLogger());
        games = new GamesDao(db);
        dao = new EventDao(db, games);
        assertTrue(dao.open(row(ID)), "the night's row is written when its window opens");
        assertTrue(dao.join(ID, A, "Ava", NOW), "Ava joins");
        assertTrue(dao.join(ID, B, "Ben", NOW + 1), "Ben joins");
    }

    @AfterEach
    void tearDown() throws Exception {
        conn.close();
    }

    private static EventDao.EventRow row(String id) {
        return new EventDao.EventRow(id, "ice", NOW - 600_000, NOW, EventDao.OPEN, "races=3\n", 0, false, "", "",
                NOW - 600_000, null, "");
    }

    private static EventDao.RaceRow finish(int race, UUID who, int place, int points) {
        return new EventDao.RaceRow(ID, race, who, place, 40_000L + place, 8, points, "FINISHED");
    }

    private int entryPoints(UUID who) throws SQLException {
        for (EventDao.EntryRow e : dao.entries(ID)) {
            if (e.player().equals(who)) {
                return e.points();
            }
        }
        return -1;
    }

    @Test
    void aRaceIsOneTransactionRowsPointsSeasonAndRacesDone() throws Exception {
        // the season board can't be written (its table is gone): the failure comes after the race rows
        try (var st = conn.createStatement()) {
            st.execute("ALTER TABLE game_scores RENAME TO game_scores_away");
        }
        assertThrows(SQLException.class, () -> dao.storeRace(ID, 1, List.of(finish(1, A, 1, 10), finish(1, B, 2, 8)),
                SEASON, NOW), "the season write fails the race");
        try (var st = conn.createStatement()) {
            st.execute("ALTER TABLE game_scores_away RENAME TO game_scores");
        }
        assertTrue(dao.races(ID).isEmpty(), "rolled back: no race rows");
        assertEquals(0, entryPoints(A), "rolled back: Ava's night points untouched");
        assertNull(games.best(A, EventDao.GAME, SEASON), "rolled back: no season points");
        assertEquals(0, dao.event(ID).racesDone(), "rolled back: races_done untouched");

        assertEquals(2, dao.storeRace(ID, 1, List.of(finish(1, A, 1, 10), finish(1, B, 2, 8)), SEASON, NOW),
                "both rows are new");
        assertEquals(10, entryPoints(A), "Ava's night points");
        assertEquals(10L, games.best(A, EventDao.GAME, SEASON), "the season board, in the same transaction");
        assertEquals(1, dao.event(ID).racesDone(), "races_done moved on");
    }

    @Test
    void storingARaceAgainAddsNothing() throws Exception {
        List<EventDao.RaceRow> rows = List.of(finish(1, A, 1, 10), finish(1, B, 2, 8));
        dao.storeRace(ID, 1, rows, SEASON, NOW);
        assertEquals(0, dao.storeRace(ID, 1, rows, SEASON, NOW + 5), "a crash after the commit stores it again: nothing new");
        assertEquals(10, entryPoints(A), "the night's points were added once");
        assertEquals(10L, games.best(A, EventDao.GAME, SEASON), "the season delta was added once");
        dao.storeRace(ID, 2, List.of(finish(2, A, 2, 8)), SEASON, NOW + 10);
        assertEquals(18L, games.best(A, EventDao.GAME, SEASON), "a new race adds its points");
        assertEquals(2, dao.event(ID).racesDone(), "two races");
    }

    /** A rewards table that pays each ref once, like game_rewards. */
    private static final class Payer implements PayLoop.Payer {
        final Map<String, Integer> paid = new HashMap<>();
        boolean online = true;

        @Override
        public int pay(UUID player, String ref, int tokens, String detail) {
            if (!online) {
                return -1;
            }
            return paid.putIfAbsent(player + ref, tokens) == null ? tokens : 0;
        }

        @Override
        public boolean paid(UUID player, String ref) {
            return paid.containsKey(player + ref);
        }
    }

    @Test
    void thePayLoopPaysExactlyOnceAcrossACrashBetweenPayAndPaidAt() throws Exception {
        dao.storeRace(ID, 1, List.of(finish(1, A, 1, 10), finish(1, B, 2, 8)), SEASON, NOW);
        dao.settle(ID, List.of(new EventDao.Placed(A, 1, 10, 5), new EventDao.Placed(B, 2, 8, 1)),
                "rnnight:" + ID, NOW);
        Payer payer = new Payer();
        // the crash: Ava's prize was paid, then the server died before paid_at was written
        payer.pay(A, "event:" + ID, 5, "Race Night: 1st place");
        assertEquals(2, dao.unpaid(ID).size(), "both rows still look unpaid");

        PayLoop loop = new PayLoop(dao, payer, () -> NOW, Logger.getAnonymousLogger());
        assertEquals(0, loop.payNight(ID), "the next pass leaves nothing owed");
        assertEquals(2, payer.paid.size(), "Ava was not paid twice, Ben once");
        assertTrue(dao.unpaid(ID).isEmpty(), "both are recorded as paid now");
        assertEquals(0, loop.payNight(ID), "a third pass does nothing");
        assertEquals(2, payer.paid.size(), "still two payments");
    }

    @Test
    void anOwedPrizeIsPaidAtTheNextJoinOnce() throws Exception {
        dao.settle(ID, List.of(new EventDao.Placed(A, 1, 10, 5)), "rnnight:" + ID, NOW);
        dao.setState(ID, EventDao.DONE, "", NOW);
        Payer payer = new Payer();
        payer.online = false;
        PayLoop loop = new PayLoop(dao, payer, () -> NOW, Logger.getAnonymousLogger());
        assertEquals(1, loop.payNight(ID), "offline at the settle: owed");
        assertEquals(1, dao.owed(A).size(), "Ava is owed one prize");
        payer.online = true;
        assertEquals(0, loop.payOwed(A), "paid at the next join");
        assertEquals(5, payer.paid.get(A + "event:" + ID), "5 tokens");
        assertTrue(dao.owed(A).isEmpty(), "nothing owed now");
        assertEquals(0, loop.payOwed(A), "the join after that pays nothing");
        assertEquals(1, payer.paid.size(), "once");
    }

    @Test
    void settlingAgainNeverChangesAPrizeAlreadyPaid() throws Exception {
        dao.settle(ID, List.of(new EventDao.Placed(A, 1, 10, 5)), "rnnight:" + ID, NOW);
        dao.markPaid(ID, A, NOW);
        dao.settle(ID, List.of(new EventDao.Placed(A, 1, 10, 3)), "rnnight:" + ID, NOW + 1);
        EventDao.EntryRow a = dao.entries(ID).get(0);
        assertEquals(5, a.prize(), "a paid prize stands");
        assertNotNull(a.paidAt(), "still paid");
        assertEquals(10L, games.best(A, EventDao.GAME, "rnnight:" + ID), "the night's board written once");
    }

    @Test
    void theWeeksPrizeSlotsStopAtTheLimit() throws Exception {
        for (int i = 1; i <= 4; i++) {
            String id = "rn-2026100" + i + "-1900";
            dao.open(row(id));
            boolean got = dao.claimPrizeSlot(id, "2920", 3);
            assertEquals(i <= 3, got, "night " + i + (i <= 3 ? " gets a slot" : " is just for fun"));
        }
        assertTrue(dao.claimPrizeSlot("rn-20261001-1900", "2920", 3), "a night that holds one keeps it");
        assertEquals(3, dao.prizedIn("2920"), "3 prize nights this week");
        assertFalse(dao.claimPrizeSlot("rn-20260101-1900", "2920", 3), "no such night: no slot");
    }

    @Test
    void leavingTheListBeforeTheRacingNeverHappened() throws Exception {
        dao.unjoin(ID, B);
        assertEquals(1, dao.entries(ID).size(), "only Ava is left on the list");
        dao.setStatus(ID, A, EventDao.LEFT);
        assertEquals(EventDao.LEFT, dao.entries(ID).get(0).status(), "Leave game once racing: LEFT");
        assertFalse(dao.join("rn-20990101-1900", A, "Ava", NOW), "a night that doesn't exist can't be joined");
    }

    @Test
    void theAdminsSettingsStayUnderRace() throws Exception {
        dao.setMeta("race.skip." + ID, "1");
        dao.setMeta("race.paused", "1");
        assertEquals(Map.of("race.skip." + ID, "1"), dao.metaLike("race.skip."), "skips by prefix");
        dao.setMeta("race.paused", null);
        assertNull(dao.meta("race.paused"), "removed");
        assertThrows(IllegalArgumentException.class, () -> dao.setMeta("gen.slot.x", "1"),
                "Race Night never writes another module's keys");
    }
}
