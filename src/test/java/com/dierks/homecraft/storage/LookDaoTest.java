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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wardrobe's rows against a real SQLite (SHOP-V2-SPEC §3.2): a right is written whole, read back as it was
 * written, its heads-up recorded, its end moved; a rewrite forgets the old heads-up and keeps a trail's switch;
 * and a write inside a caller's transaction rolls back with it.
 */
class LookDaoTest {

    private static final long DAY = 86_400_000L;

    private Connection conn;
    private Database db;
    private LookDao looks;
    private final UUID player = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();

    @BeforeEach
    void open() throws Exception {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        db = Database.open(conn, Logger.getAnonymousLogger());
        looks = new LookDao(db);
    }

    @AfterEach
    void close() throws Exception {
        conn.close();
    }

    @Test
    void writeThenLook() throws Exception {
        assertNull(looks.look(player, "hat_creeper"), "never had it");
        looks.write(player, "hat_creeper", LookDao.HAT, 90 * DAY, 5, "");
        assertEquals(new LookDao.Look("hat_creeper", LookDao.HAT, 90 * DAY, 5, false, 0, 0, ""),
                looks.look(player, "hat_creeper"), "a new right starts switched off, with no heads-up sent");
        assertNull(looks.look(other, "hat_creeper"), "another player's is their own");
    }

    @Test
    void looksListsEveryRowEndedOrNot() throws Exception {
        looks.write(player, "trail_snow", LookDao.TRAIL, 30 * DAY, 1, "");
        looks.write(player, "cap_pink", LookDao.HAT, 10, 1, other.toString()); // ended long ago
        looks.write(other, "cap_sky", LookDao.HAT, 90 * DAY, 1, "");
        List<LookDao.Look> mine = looks.looks(player);
        assertEquals(List.of("cap_pink", "trail_snow"), mine.stream().map(LookDao.Look::id).toList());
        assertEquals(other.toString(), mine.get(0).givenBy(), "a present remembers who paid");
        assertEquals(List.of(), looks.looks(UUID.randomUUID()));
    }

    @Test
    void aRewriteTakesTheNewRunAndForgetsTheOldHeadsUp() throws Exception {
        looks.write(player, "trail_snow", LookDao.TRAIL, 30 * DAY, 1, "");
        new PrizeDao(db).enableOnly(player, "trail_snow");
        looks.notice(player, "trail_snow", 7, 30 * DAY);
        assertEquals(7, looks.look(player, "trail_snow").noticeStage());
        assertEquals(30 * DAY, looks.look(player, "trail_snow").noticeFor());

        looks.write(player, "trail_snow", LookDao.TRAIL, 60 * DAY, 1, other.toString());
        LookDao.Look row = looks.look(player, "trail_snow");
        assertEquals(new LookDao.Look("trail_snow", LookDao.TRAIL, 60 * DAY, 1, true, 0, 0, other.toString()), row,
                "the new end and giver, the heads-up forgotten, the switch kept");
    }

    @Test
    void noticeRecordsWhatWasSentAndForWhichEnd() throws Exception {
        looks.write(player, "cap_lime", LookDao.HAT, 90 * DAY, 1, "");
        looks.notice(player, "cap_lime", 1, 90 * DAY);
        assertEquals(1, looks.look(player, "cap_lime").noticeStage());
        looks.notice(player, "cap_lime", -1, 90 * DAY);
        LookDao.Look row = looks.look(player, "cap_lime");
        assertEquals(-1, row.noticeStage(), "the end");
        assertEquals(90 * DAY, row.noticeFor());
        looks.notice(player, "nothing_here", 7, 1); // no row: nothing to record, and no error
        assertNull(looks.look(player, "nothing_here"));
    }

    @Test
    void endMovesOnlyTheEnd() throws Exception {
        looks.write(player, "hat_dragon", LookDao.HAT, 90 * DAY, 3, other.toString());
        looks.notice(player, "hat_dragon", 7, 90 * DAY);
        looks.end(player, "hat_dragon", 5 * DAY);
        assertEquals(new LookDao.Look("hat_dragon", LookDao.HAT, 5 * DAY, 3, false, 90 * DAY, 7, other.toString()),
                looks.look(player, "hat_dragon"), "the run's start, the giver and the old heads-up stay; the new end "
                        + "differs from notice_for, which is what resets the heads-up");
        looks.end(other, "hat_dragon", 1); // another player's: no row, nothing happens
        assertEquals(5 * DAY, looks.look(player, "hat_dragon").expiresAt());
    }

    @Test
    void aWriteRollsBackWithItsCallersTransaction() throws Exception {
        assertThrows(SQLException.class, () -> db.transaction(c -> {
            looks.write(player, "cap_purple", LookDao.HAT, 90 * DAY, 1, "");
            assertNotNull(looks.look(player, "cap_purple"), "seen inside the transaction");
            throw new SQLException("the spend failed");
        }));
        assertNull(looks.look(player, "cap_purple"), "nothing landed");
        assertTrue(conn.getAutoCommit(), "the connection is back to normal");
    }
}
