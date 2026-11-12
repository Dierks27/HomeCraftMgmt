package com.dierks.homecraft.storage;

import com.dierks.homecraft.graves.Grave;
import com.dierks.homecraft.graves.GraveItems.Entry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Graves in a real SQLite (schema v40, HCM-GRAVES §4): every way out of a grave is one guarded write, so no path can
 * hand anything out twice. Each test names the duplication path it closes.
 */
class GraveDaoTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final List<Entry> THREE = List.of(new Entry(0, 0, "c3dvcmQ="), new Entry(1, 39, "aGVsbWV0"),
            new Entry(2, -1, "ZGlydA=="));

    private Connection conn;
    private Database db;
    private GraveDao dao;

    @BeforeEach
    void open() throws SQLException {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        db = Database.open(conn, Logger.getAnonymousLogger());
        dao = new GraveDao(db);
    }

    @AfterEach
    void close() throws SQLException {
        conn.close();
    }

    private long grave(int x) throws SQLException {
        return dao.create(OWNER, "Lilah", "lilahs_world", x, 64, 0, 1_000L, 120, THREE);
    }

    @Test
    void aGraveKeepsWhatWasPutInIt() throws SQLException {
        long id = grave(1);
        Grave g = dao.get(id);
        assertNotNull(g);
        assertTrue(g.open());
        assertEquals(OWNER, g.owner());
        assertEquals("Lilah", g.ownerName());
        assertEquals("lilahs_world", g.world());
        assertEquals(1, g.x());
        assertEquals(120, g.xp());
        assertEquals(THREE, g.items(), "each stack with its slot and number");
        assertEquals(List.of(g), dao.open());
        assertEquals(List.of(g), dao.openFor(OWNER));
        assertEquals(List.of(), dao.openFor(OTHER));
    }

    @Test
    void itSurvivesARestart() throws SQLException {
        long id = grave(1);
        // A restart is a new Database and DAO over the same file; here, the same connection opened again.
        GraveDao after = new GraveDao(Database.open(conn, Logger.getAnonymousLogger()));
        assertEquals(THREE, after.get(id).items());
        assertEquals(1, after.open().size());
    }

    @Test
    void oneOpenGraveABlock() throws SQLException {
        grave(1);
        assertThrows(SQLException.class, () -> grave(1), "the unique index: two graves never share a block");
        assertEquals(1, dao.open().size());
    }

    @Test
    void doubleClickEmptiesItOnce() throws SQLException {
        long id = grave(1);
        Grave first = dao.claimAll(id, Grave.LOOTED, OWNER.toString(), 2_000L, false);
        Grave second = dao.claimAll(id, Grave.LOOTED, OWNER.toString(), 2_001L, false);
        assertNotNull(first);
        assertEquals(THREE, first.items(), "the first click gets everything");
        assertEquals(120, first.xp());
        assertNull(second, "the second gets nothing");
        Grave after = dao.get(id);
        assertEquals(Grave.LOOTED, after.state());
        assertEquals(List.of(), after.items(), "the row is empty: nothing is left to give twice");
        assertEquals(0, after.xp());
        assertEquals(OWNER.toString(), after.closedBy());
        assertEquals(List.of(), dao.open());
    }

    @Test
    void twoOpenersAtOnceGetItOnce() throws Exception {
        long id = grave(1);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Grave>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                String who = (i % 2 == 0 ? OWNER : OTHER).toString();
                results.add(pool.submit(() -> {
                    go.await();
                    return dao.claimAll(id, Grave.LOOTED, who, 2_000L, false);
                }));
            }
            go.countDown();
            int winners = 0;
            for (Future<Grave> f : results) {
                if (f.get(10, TimeUnit.SECONDS) != null) {
                    winners++;
                }
            }
            assertEquals(1, winners, "exactly one of them gets the grave");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void takingOneStackTwiceGivesItOnce() throws SQLException {
        long id = grave(1);
        GraveDao.Taken a = dao.takeOne(id, 1, OWNER.toString(), 2_000L);
        GraveDao.Taken b = dao.takeOne(id, 1, OWNER.toString(), 2_001L);
        assertNotNull(a);
        assertEquals(new Entry(1, 39, "aGVsbWV0"), a.entry());
        assertFalse(a.emptied());
        assertNull(b, "a second click on the same stack (or a stale screen) gets nothing");
        assertEquals(List.of(THREE.get(0), THREE.get(2)), dao.get(id).items());
    }

    @Test
    void twoScreensTakingTheSameStackGetItOnce() throws Exception {
        long id = grave(1);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<GraveDao.Taken>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    return dao.takeOne(id, 0, OTHER.toString(), 2_000L);
                }));
            }
            go.countDown();
            int got = 0;
            for (Future<GraveDao.Taken> f : results) {
                if (f.get(10, TimeUnit.SECONDS) != null) {
                    got++;
                }
            }
            assertEquals(1, got);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(2, dao.get(id).items().size());
    }

    @Test
    void aScreenLeftOpenAfterTheGraveWasEmptiedGetsNothing() throws SQLException {
        // Quit mid-loot, an admin restore, the owner's right-click while someone has the screen open: the screen's
        // stacks are copies, and the row says what is really left.
        long id = grave(1);
        assertNotNull(dao.claimAll(id, Grave.RESTORED, "admin", 2_000L, false));
        assertNull(dao.takeOne(id, 0, OTHER.toString(), 2_001L));
        assertNull(dao.takeXp(id, OTHER.toString(), 2_001L));
    }

    @Test
    void emptyingAfterSomeStacksWereTakenGivesOnlyTheRest() throws SQLException {
        long id = grave(1);
        assertNotNull(dao.takeOne(id, 0, OWNER.toString(), 2_000L));
        Grave rest = dao.claimAll(id, Grave.LOOTED, OWNER.toString(), 2_001L, false);
        assertEquals(List.of(THREE.get(1), THREE.get(2)), rest.items(), "the sword already went");
        assertEquals(120, rest.xp());
    }

    @Test
    void theLastStackAndTheExperienceCloseIt() throws SQLException {
        long id = dao.create(OWNER, "Lilah", "w", 0, 64, 0, 1_000L, 30, List.of(new Entry(0, 0, "QQ==")));
        GraveDao.Taken item = dao.takeOne(id, 0, OWNER.toString(), 2_000L);
        assertFalse(item.emptied(), "the experience is still in it");
        assertTrue(dao.get(id).open());
        GraveDao.Taken xp = dao.takeXp(id, OWNER.toString(), 2_001L);
        assertEquals(30, xp.xp());
        assertTrue(xp.emptied());
        assertEquals(Grave.LOOTED, dao.get(id).state());
        assertNull(dao.takeXp(id, OWNER.toString(), 2_002L), "the experience comes out once");
    }

    @Test
    void aServerStopMidLootLosesNothingAndCopiesNothing() throws SQLException {
        // The screen showed three stacks; one was taken, then the server stopped. After the restart the grave holds
        // exactly the two that weren't: the one taken was written out of the row before it was given.
        long id = grave(1);
        assertNotNull(dao.takeOne(id, 2, OWNER.toString(), 2_000L));
        GraveDao after = new GraveDao(Database.open(conn, Logger.getAnonymousLogger()));
        assertEquals(List.of(THREE.get(0), THREE.get(1)), after.get(id).items());
        assertNull(after.takeOne(id, 2, OWNER.toString(), 3_000L), "the stack taken before the stop is gone for good");
    }

    @Test
    void expiryLeavesTheOwnerAMessage() throws SQLException {
        long id = grave(1);
        long other = grave(2);
        Grave g = dao.claimAll(id, Grave.EXPIRED, "expiry", 9_000L, true);
        assertEquals(THREE, g.items(), "everything comes out, to be dropped where it stood");
        assertEquals(List.of(id), ids(dao.noticesFor(OWNER)));
        assertEquals(List.of(other), ids(dao.open()));
        dao.clearNotices(OWNER);
        assertEquals(List.of(), dao.noticesFor(OWNER), "told once");
    }

    @Test
    void anUnreadableOwnerIsSkippedNotFatal() throws SQLException {
        grave(1);
        try (var st = conn.createStatement()) {
            st.execute("INSERT INTO graves(owner, world, x, y, z, created_at) VALUES('not-a-uuid', 'w', 9, 9, 9, 1)");
        }
        assertEquals(1, dao.open().size());
    }

    private static List<Long> ids(List<Grave> graves) {
        List<Long> out = new ArrayList<>();
        for (Grave g : graves) {
            out.add(g.id());
        }
        Collections.sort(out);
        return out;
    }
}
