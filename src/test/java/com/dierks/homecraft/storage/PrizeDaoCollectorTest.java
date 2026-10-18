package com.dierks.homecraft.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A collector's bookkeeping against a real SQLite (HALLOWEEN-SPEC D3, D8, E7, E23): the one-each
 * claim written before any charge, its undo, and a kept-for-good trail that never overflows.
 */
class PrizeDaoCollectorTest {

    private static final String CAP = "cap_pumpkin_2026";
    private static final String TRAIL = "trail_leaves_2026";
    private static final long DAY = 86_400_000L;

    private Connection conn;
    private PrizeDao dao;
    private final UUID receiver = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();

    @BeforeEach
    void open() throws Exception {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        dao = new PrizeDao(Database.open(conn, Logger.getAnonymousLogger()));
    }

    @AfterEach
    void close() throws Exception {
        conn.close();
    }

    @Test
    void claimOnceIsOnce() throws Exception {
        assertTrue(dao.claimOnce(receiver, CAP), "the first claim writes the row");
        assertFalse(dao.claimOnce(receiver, CAP), "a second confirm finds it");
        assertFalse(dao.claimOnce(receiver, CAP), "and a second giver too");
        assertTrue(dao.claimOnce(other, CAP), "another player has their own");
        assertTrue(dao.claimOnce(receiver, TRAIL), "another collector is its own");
    }

    @Test
    void aClaimUsesUpTheLifetimeLimit() throws Exception {
        assertEquals(0, dao.purchases(receiver, CAP, "life"));
        dao.claimOnce(receiver, CAP);
        assertEquals(1, dao.purchases(receiver, CAP, "life"), "so left() is 0 and a second confirm is refused "
                + "before any charge");
        dao.claimOnce(receiver, CAP);
        assertEquals(1, dao.purchases(receiver, CAP, "life"), "a refused claim never counts twice");
        assertTrue(dao.everBought(receiver, CAP));
    }

    @Test
    void unclaimUndoesOnlyThatClaim() throws Exception {
        dao.claimOnce(receiver, CAP);
        dao.claimOnce(other, CAP);
        dao.addPurchase(receiver, CAP, "d20000"); // a day row is not the claim
        dao.unclaim(receiver, CAP);
        assertEquals(0, dao.purchases(receiver, CAP, "life"));
        assertEquals(1, dao.purchases(receiver, CAP, "d20000"));
        assertEquals(1, dao.purchases(other, CAP, "life"));
        assertTrue(dao.claimOnce(receiver, CAP), "after an unclaim it can be claimed again");
        dao.unclaim(receiver, TRAIL); // nothing to undo is fine
    }

    @Test
    void aKeptForGoodTrailNeverOverflows() throws Exception {
        long now = 1_793_000_000_000L; // October 2026
        long first = dao.grantCosmetic(receiver, TRAIL, 36_500 * DAY, now, true);
        assertEquals(now + 36_500 * DAY, first);
        long again = dao.grantCosmetic(receiver, TRAIL, DAY, now, true);
        assertTrue(again > now + 36_500 * DAY, "one more day on top: still in the future, no overflow");
        assertEquals(first + DAY, again);
        long twice = dao.grantCosmetic(receiver, TRAIL, 36_500 * DAY, now, true);
        assertTrue(twice > again, "a second hundred years (a give-back) is still positive");
    }

    @Test
    void enableOnlyLeavesOneTrailOn() throws Exception {
        long now = 1_793_000_000_000L;
        dao.grantCosmetic(receiver, "trail_heart", 7 * DAY, now, true);
        dao.grantCosmetic(receiver, TRAIL, 36_500 * DAY, now, true);
        dao.enableOnly(receiver, TRAIL);
        int on = 0;
        for (PrizeDao.Cosmetic c : dao.cosmetics(receiver)) {
            if (c.enabled()) {
                on++;
                assertEquals(TRAIL, c.id());
            }
        }
        assertEquals(1, on);
    }
}
