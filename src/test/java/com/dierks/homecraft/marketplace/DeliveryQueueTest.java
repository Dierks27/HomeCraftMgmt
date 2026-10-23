package com.dierks.homecraft.marketplace;

import com.dierks.homecraft.storage.Database;
import com.dierks.homecraft.storage.DeliveryDao;
import com.dierks.homecraft.storage.PalletDao;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
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
 * The Mailbox as the break fix and the hand-overs use it (0.37.3, spec §4.7, §4.15): {@code queueReady} writes a
 * READY row inside a caller's transaction and rolls back with it; a collect's writes act once; and a broken
 * Pallet's stock goes to its owner's Mailbox, one row per full stack, in one transaction with the listing's delete.
 */
class DeliveryQueueTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BREAKER = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private Connection conn;
    private Database db;
    private DeliveryDao dao;
    private DeliveryService mail;

    @BeforeEach
    void open() throws SQLException {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        db = Database.open(conn, Logger.getAnonymousLogger());
        dao = new DeliveryDao(db);
        mail = new DeliveryService(null, dao);
    }

    @AfterEach
    void close() throws SQLException {
        conn.close();
    }

    @Test
    void queueReadyWritesReadyInsideTheCallersTransactionAndRollsBackWithIt() throws Exception {
        assertThrows(SQLException.class, () -> db.transaction(c -> {
            mail.queueReady(OWNER, "b64:fox", "From your Vending Machine", "VENDING");
            assertEquals(1, dao.activeFor(OWNER).size(), "visible inside the transaction");
            throw new SQLException("a later write failed");
        }));
        assertEquals(List.of(), dao.activeFor(OWNER), "rolled back with the caller's transaction");

        long before = System.currentTimeMillis();
        db.transaction(c -> {
            mail.queueReady(OWNER, "b64:fox", "From your Vending Machine", "VENDING");
            return null;
        });
        List<DeliveryDao.Delivery> rows = dao.activeFor(OWNER);
        assertEquals(1, rows.size(), "committed with it");
        DeliveryDao.Delivery d = rows.get(0);
        assertEquals(DeliveryDao.READY, d.status(), "ready to collect, not in transit");
        assertEquals("b64:fox", d.itemB64());
        assertEquals("From your Vending Machine", d.label());
        assertEquals("VENDING", d.source());
        assertTrue(d.deliverAt() >= before && d.deliverAt() == d.placedAt(), "arrived the moment it was written");
        assertEquals(List.of(), dao.findDue(Long.MAX_VALUE), "the delivery tick never touches it (it isn't in transit)");
    }

    @Test
    void aCollectsWritesActOnlyOnce() throws Exception {
        mail.queueReady(OWNER, "b64:40", "From your Pallet", PalletService.SOURCE);
        long id = dao.activeFor(OWNER).get(0).id();
        assertTrue(dao.updateItemIf(id, DeliveryDao.READY, "b64:30"), "the part that stays, rewritten");
        assertEquals("b64:30", dao.byId(id).orElseThrow().itemB64());
        assertTrue(dao.updateStatusIf(id, DeliveryDao.READY, DeliveryDao.COLLECTED), "collected");
        assertFalse(dao.updateStatusIf(id, DeliveryDao.READY, DeliveryDao.COLLECTED),
                "a second collect of the same row finds it gone and gives nothing");
        assertFalse(dao.updateItemIf(id, DeliveryDao.READY, "b64:1"), "nor rewrites a collected row");
        assertEquals(List.of(), dao.activeFor(OWNER));
    }

    @Test
    void aBrokenPalletsStockGoesToItsOwnersMailboxOneRowPerStack() throws Exception {
        PalletDao pallets = new PalletDao(db);
        Location at = new Location(world(), 4, 70, 9);
        pallets.upsert(at, OWNER, "b64:template", 2.5, 150, "BLOCKS", 1);
        int back = PalletService.returnStock(db, pallets, mail::queueReady, at, 64, n -> "b64:x" + n);
        assertEquals(150, back);
        assertTrue(pallets.at(at).isEmpty(), "the listing is gone");
        List<DeliveryDao.Delivery> rows = dao.activeFor(OWNER);
        assertEquals(List.of("b64:x64", "b64:x64", "b64:x22"), rows.stream().map(DeliveryDao.Delivery::itemB64).toList(),
                "one row per full stack, the last one the rest");
        assertTrue(rows.stream().allMatch(d -> d.status().equals(DeliveryDao.READY) && d.source().equals("PALLET")
                && d.label().equals("From your Pallet")));
        assertEquals(List.of(), dao.activeFor(BREAKER), "never to whoever broke it");
        assertEquals(0, PalletService.returnStock(db, pallets, mail::queueReady, at, 64, n -> "b64:x" + n),
                "an empty Pallet gives nothing");
    }

    @Test
    void aFailingInsertLeavesThePalletsListingInPlace() throws Exception {
        PalletDao pallets = new PalletDao(db);
        Location at = new Location(world(), 4, 70, 9);
        pallets.upsert(at, OWNER, "b64:template", 2.5, 150, "BLOCKS", 1);
        int[] calls = {0};
        DeliveryService.Mailbox failsThird = (player, b64, label, source) -> {
            if (++calls[0] == 3) {
                throw new SQLException("disk full");
            }
            mail.queueReady(player, b64, label, source);
        };
        assertThrows(SQLException.class,
                () -> PalletService.returnStock(db, pallets, failsThird, at, 64, n -> "b64:x" + n));
        assertEquals(150, pallets.at(at).orElseThrow().stock(), "the listing and its stock are still there");
        assertEquals(List.of(), dao.activeFor(OWNER), "and no Mailbox row was kept");
    }

    @Test
    void aPalletWhoseItemCantBeReadIsOnlyDeletedAsBefore() throws Exception {
        PalletDao pallets = new PalletDao(db);
        Location at = new Location(world(), 4, 70, 9);
        pallets.upsert(at, OWNER, "not an item", 2.5, 10, "BLOCKS", 1);
        assertEquals(0, PalletService.returnStock(db, pallets, mail::queueReady, at, 64, null));
        assertTrue(pallets.at(at).isEmpty());
        assertEquals(List.of(), dao.activeFor(OWNER));
    }

    private static World world() {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
                (proxy, m, args) -> switch (m.getName()) {
                    case "getName" -> "world";
                    case "hashCode" -> 1;
                    case "equals" -> proxy == args[0];
                    case "toString" -> "World[world]";
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }
}
