package com.dierks.homecraft.trade;

import com.dierks.homecraft.block.CustomBlockGuard;
import com.dierks.homecraft.block.CustomBlockListener;
import com.dierks.homecraft.block.CustomBlockType;
import com.dierks.homecraft.marketplace.DeliveryService;
import com.dierks.homecraft.storage.Database;
import com.dierks.homecraft.storage.DeliveryDao;
import com.dierks.homecraft.storage.MiniListingDao;
import com.dierks.homecraft.storage.MiniVendingDao;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The break fix (0.37.3, spec §4.15 (1)-(2), D22): only whoever placed a Vending Machine, Display Case or Pallet, or
 * an admin, may break it; everything in it goes back to whoever stocked it, always into their Mailbox, in one
 * transaction with the row deletes (never the breaker, never the ground); and a break another plugin cancels after
 * HIGH changes nothing at all. From 0.38.1 the Firework Launcher is placer-or-admin too, and holds no stock.
 */
class VendingBreakTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID HELPER = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final Set<CustomBlockType> STOCK = EnumSet.of(CustomBlockType.MINI_VENDING_MACHINE,
            CustomBlockType.DISPLAY_CASE, CustomBlockType.PALLET);
    /** Broken only by whoever placed it or an admin: the three that hold stock, and the Firework Launcher (0.38.1). */
    private static final Set<CustomBlockType> OWNER_ONLY = EnumSet.of(CustomBlockType.MINI_VENDING_MACHINE,
            CustomBlockType.DISPLAY_CASE, CustomBlockType.PALLET, CustomBlockType.FIREWORK_LAUNCHER);

    private Connection conn;
    private Database db;
    private MiniListingDao displays;
    private MiniVendingDao vending;
    private DeliveryDao deliveries;
    private Location at;

    @BeforeEach
    void open() throws SQLException {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        db = Database.open(conn, Logger.getAnonymousLogger());
        displays = new MiniListingDao(db);
        vending = new MiniVendingDao(db);
        deliveries = new DeliveryDao(db);
        at = new Location(world("world"), 10, 64, -3);
    }

    @AfterEach
    void close() throws SQLException {
        conn.close();
    }

    static World world(String name) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
                (proxy, m, args) -> switch (m.getName()) {
                    case "getName" -> name;
                    case "hashCode" -> name.hashCode();
                    case "equals" -> proxy == args[0];
                    case "toString" -> "World[" + name + "]";
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    @Test
    void onlyTheOwnerOrAnAdminBreaksABlockThatHoldsStock() {
        for (CustomBlockType type : CustomBlockType.values()) {
            boolean ownerOnly = OWNER_ONLY.contains(type);
            assertFalse(CustomBlockGuard.breakRefusal(type, true, false), type + ": its owner may break it");
            assertFalse(CustomBlockGuard.breakRefusal(type, false, true), type + ": an admin may break it");
            assertFalse(CustomBlockGuard.breakRefusal(type, true, true), type + ": an owner who is an admin too");
            assertEquals(ownerOnly, CustomBlockGuard.breakRefusal(type, false, false), type + (ownerOnly
                    ? ": anyone else is refused, empty or not" : ": every other block keeps the old rule"));
            assertEquals(ownerOnly, CustomBlockGuard.ownerOnly(type), type + ": owner-only");
            assertEquals(STOCK.contains(type), CustomBlockGuard.holdsStock(type),
                    type + ": only the three hold stock (a launcher holds nothing)");
        }
        assertEquals("&cThis is Steve's Vending Machine. Only they can take it down.",
                CustomBlockGuard.refusalLine("Steve", CustomBlockType.MINI_VENDING_MACHINE));
        assertEquals("&cThis is Steve's Display Case. Only they can take it down.",
                CustomBlockGuard.refusalLine("Steve", CustomBlockType.DISPLAY_CASE));
        assertEquals("&cThis is Steve's Pallet. Only they can take it down.",
                CustomBlockGuard.refusalLine("Steve", CustomBlockType.PALLET));
        assertEquals("&cThis is Owner's Firework Launcher. Only they can take it down.",
                CustomBlockGuard.refusalLine("Owner", CustomBlockType.FIREWORK_LAUNCHER),
                "0.38.1: the daughter's launchers stay where she set them up");
    }

    @Test
    void everyMiniGoesToTheOwnerOfItsListingNeverTheBreaker() {
        MiniListingDao.Listing display = new MiniListingDao.Listing(1, "world", 10, 64, -3, VendingService.DISPLAY,
                HELPER, "uid-d", "fox", 3, 0, "b64:display", 1);
        List<MiniVendingDao.Listing> listings = List.of(
                new MiniVendingDao.Listing(7, "world", 10, 64, -3, OWNER, "uid-1", "fox", 1, 50, "b64:one", 1),
                new MiniVendingDao.Listing(8, "world", 10, 64, -3, HELPER, "uid-2", "owl", 2, 80, "b64:two", 2));
        List<VendingService.Return> plan = VendingService.returnPlan(VendingService.VENDING_MACHINE,
                Optional.of(display), listings);
        assertEquals(List.of(
                new VendingService.Return(HELPER, "b64:display", "Vending Machine"),
                new VendingService.Return(OWNER, "b64:one", "Vending Machine"),
                new VendingService.Return(HELPER, "b64:two", "Vending Machine")), plan,
                "each Mini, exactly as stored, to whoever stocked it, named after the block that was broken");
        assertEquals("From your Vending Machine", plan.get(1).label());
        assertEquals("From your Display Case", VendingService.returnPlan(VendingService.DISPLAY_CASE,
                Optional.of(display), List.of()).get(0).label());
        assertEquals(List.of(), VendingService.returnPlan(VendingService.DISPLAY_CASE, Optional.empty(), List.of()),
                "an empty block gives nothing");
        // The plan has no breaker in it at all: whoever breaks the block (its owner, an admin) changes nothing.
        assertTrue(java.util.Arrays.stream(VendingService.class.getDeclaredMethods())
                        .filter(m -> m.getName().equals("returnPlan") || m.getName().equals("returnStock"))
                        .noneMatch(m -> List.of(m.getParameterTypes()).contains(org.bukkit.entity.Player.class)),
                "no return path even takes the breaker");
        assertTrue(java.util.Arrays.stream(VendingService.class.getDeclaredMethods())
                        .noneMatch(m -> m.getName().equals("onBlockBroken") || m.getName().equals("dropTo")
                                || m.getName().equals("giveOrDrop")),
                "the old give-to-the-breaker and drop-on-the-ground paths are gone");
    }

    @Test
    void theRowsAndTheMailboxRowsLandInOneTransaction() throws Exception {
        displays.create(at, VendingService.DISPLAY, HELPER, "uid-d", "fox", 3, 0, "b64:display", 1);
        vending.add(at, OWNER, "uid-1", "fox", 1, 50, "b64:one", 1);
        vending.add(at, OWNER, "uid-2", "owl", 2, 80, "b64:two", 2);
        Location elsewhere = new Location(at.getWorld(), 11, 64, -3);
        vending.add(elsewhere, OWNER, "uid-3", "elk", 1, 90, "b64:three", 3);

        DeliveryService mail = new DeliveryService(null, deliveries);
        List<VendingService.Return> back = VendingService.returnStock(db, displays, vending, mail::queueReady, at,
                VendingService.VENDING_MACHINE);
        assertEquals(3, back.size(), "the Display Case's Mini and both listings");
        assertTrue(displays.at(at).isEmpty(), "the display row is gone");
        assertEquals(List.of(), vending.listingsAt(at), "every listing at the block is gone");
        assertEquals(1, vending.listingsAt(elsewhere).size(), "another block's listing is untouched");

        List<DeliveryDao.Delivery> owner = deliveries.activeFor(OWNER);
        List<DeliveryDao.Delivery> helper = deliveries.activeFor(HELPER);
        assertEquals(List.of("b64:one", "b64:two"), owner.stream().map(DeliveryDao.Delivery::itemB64).toList());
        assertEquals(List.of("b64:display"), helper.stream().map(DeliveryDao.Delivery::itemB64).toList());
        for (DeliveryDao.Delivery d : concat(owner, helper)) {
            assertEquals(DeliveryDao.READY, d.status(), "ready to collect straight away");
            assertEquals(VendingService.VENDING, d.source());
            assertEquals("From your Vending Machine", d.label());
        }
    }

    @Test
    void aFailingMailboxInsertLeavesEveryListingInPlace() throws Exception {
        displays.create(at, VendingService.DISPLAY, HELPER, "uid-d", "fox", 3, 0, "b64:display", 1);
        vending.add(at, OWNER, "uid-1", "fox", 1, 50, "b64:one", 1);
        vending.add(at, OWNER, "uid-2", "owl", 2, 80, "b64:two", 2);
        DeliveryService mail = new DeliveryService(null, deliveries);
        int[] calls = {0};
        DeliveryService.Mailbox failsSecond = (player, b64, label, source) -> {
            if (++calls[0] == 2) {
                throw new SQLException("disk full");
            }
            mail.queueReady(player, b64, label, source);
        };
        assertThrows(SQLException.class, () -> VendingService.returnStock(db, displays, vending, failsSecond, at,
                VendingService.VENDING_MACHINE));
        assertTrue(displays.at(at).isPresent(), "the Display Case still holds its Mini");
        assertEquals(2, vending.listingsAt(at).size(), "both listings are still in the machine");
        assertEquals(List.of(), deliveries.activeFor(OWNER), "and the first Mailbox row rolled back with them");
        assertEquals(List.of(), deliveries.activeFor(HELPER));
    }

    @Test
    void theBreakDecidesAtHighAndActsOnlyOnceItIsFinal() throws Exception {
        EventHandler decide = handler("onBreak");
        EventHandler act = handler("onBreakFinal");
        assertEquals(EventPriority.HIGH, decide.priority());
        assertTrue(decide.ignoreCancelled());
        assertEquals(EventPriority.MONITOR, act.priority(), "nothing changes until no plugin can cancel the break");
        assertTrue(act.ignoreCancelled(), "a break cancelled at any priority never reaches it");
    }

    /**
     * A protection plugin at HIGHEST cancels the break after our HIGH decision. Bukkit skips an
     * {@code ignoreCancelled} MONITOR handler for a cancelled event, so nothing is returned, no record removed
     * and no item dropped. Without the canceller the final break runs in its order.
     */
    @Test
    void aBreakCancelledAfterHighReturnsNothingRemovesNothingDropsNothing() throws Exception {
        EventHandler act = handler("onBreakFinal");
        for (CustomBlockType type : STOCK) {
            List<String> done = new ArrayList<>();
            boolean cancelled = CustomBlockGuard.breakRefusal(type, true, false); // HIGH: the owner may
            assertFalse(cancelled);
            cancelled = true;                                                     // HIGHEST: another plugin
            if (!(cancelled && act.ignoreCancelled())) {
                CustomBlockGuard.finish(type, recorder(done, true));
            }
            assertEquals(List.of(), done, type + ": no stock returned, no record removed, no item dropped");

            List<String> fine = new ArrayList<>();
            assertTrue(CustomBlockGuard.finish(type, recorder(fine, true)));
            assertEquals(List.of("returnStock", "forget", "drop"), fine, type + ": the stock first, then the block");

            List<String> failed = new ArrayList<>();
            assertFalse(CustomBlockGuard.finish(type, recorder(failed, false)));
            assertEquals(List.of("returnStock"), failed,
                    type + ": the stock couldn't be written, so the block stays with everything in it");
        }
        List<String> mailbox = new ArrayList<>();
        assertTrue(CustomBlockGuard.finish(CustomBlockType.MAILBOX, recorder(mailbox, false)));
        assertEquals(List.of("forget", "drop"), mailbox, "a block that holds no stock returns none");
        List<String> launcher = new ArrayList<>();
        assertTrue(CustomBlockGuard.finish(CustomBlockType.FIREWORK_LAUNCHER, recorder(launcher, false)));
        assertEquals(List.of("forget", "drop"), launcher,
                "a Firework Launcher is owner-only but holds nothing: no stock is ever returned, one item drops");
    }

    private static CustomBlockGuard.Finish recorder(List<String> done, boolean written) {
        return new CustomBlockGuard.Finish() {
            @Override
            public boolean returnStock() {
                done.add("returnStock");
                return written;
            }

            @Override
            public void forget() {
                done.add("forget");
            }

            @Override
            public void drop() {
                done.add("drop");
            }
        };
    }

    private static EventHandler handler(String name) throws Exception {
        EventHandler h = CustomBlockListener.class.getMethod(name, BlockBreakEvent.class)
                .getAnnotation(EventHandler.class);
        assertNotNull(h, name + " is a handler");
        return h;
    }

    private static <T> List<T> concat(List<T> a, List<T> b) {
        List<T> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }
}
