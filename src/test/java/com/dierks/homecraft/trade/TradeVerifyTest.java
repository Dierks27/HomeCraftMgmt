package com.dierks.homecraft.trade;

import com.dierks.homecraft.storage.Database;
import com.dierks.homecraft.storage.TradeDao;
import com.dierks.homecraft.trade.TradeRules.Claim;
import com.dierks.homecraft.trade.TradeRules.Failure;
import com.dierks.homecraft.trade.TradeRules.Held;
import com.dierks.homecraft.trade.TradeRules.Refuse;
import com.dierks.homecraft.trade.TradeRules.SideNow;
import com.dierks.homecraft.trade.TradeRules.Where;
import com.dierks.homecraft.trade.TradeSession.Side;
import org.bukkit.GameMode;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The swap's last check (SHOP-V2-SPEC §4.6 step 1, §4.8, red team D-m4), on the real stacks read in the swap's own
 * tick: the same item with at least its amount passes; an amount that fell, a similar-looking Mini with another uid
 * and a stack moved to another slot fail; one uid on both tables, or on a table and in a Vending listing, is refused
 * with a console WARN (a copy exists somewhere); and the money and the windows are checked last.
 */
class TradeVerifyTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final Where OK = new Where(true, false, true, true, GameMode.SURVIVAL);

    /** A stand-in stack whose similarity is its kind AND its Mini uid, as Bukkit's isSimilar reads a Mini's data. */
    private record S(String kind, int amount, String uid) {
    }

    private static final TradeSwap.Items<S> ITEMS = new TradeSwap.Items<>() {
        @Override
        public int amount(S item) {
            return item.amount();
        }

        @Override
        public boolean similar(S a, S b) {
            return a.kind().equals(b.kind()) && java.util.Objects.equals(a.uid(), b.uid());
        }

        @Override
        public int maxStack(S item) {
            return 64;
        }

        @Override
        public S withAmount(S item, int amount) {
            return new S(item.kind(), amount, item.uid());
        }

        @Override
        public String encode(S item) {
            return item.kind();
        }

        @Override
        public ItemFacts facts(S item) {
            return new ItemFacts(item.kind(), "STONE", item.amount(), false, null, false, false, false, false, false,
                    false, Set.of(), item.uid(), item.uid() == null ? null : "creeper", false, List.of());
        }
    };

    private static List<Held> held(Map<Integer, S> table, S[] bag) {
        TradeSession<S> s = new TradeSession<>(A, B, 0, 3);
        table.forEach((slot, item) -> s.put(Side.A, slot, item, item.amount(), 0));
        return TradeSwap.held(s.offer(Side.A), i -> bag[i], ITEMS);
    }

    private static SideNow side(String name, List<Held> entries, double money, boolean canPay) {
        return new SideNow(name.equals("Owner") ? A : B, name, true, true, "world", OK, entries, money, canPay,
                id -> new Claim(true, false));
    }

    private static Failure verify(List<Held> a, List<Held> b, Set<String> listed) {
        return TradeRules.verify(side("Owner", a, 0, true), side("Helper", b, 0, true), true, Refuse.NONE, listed);
    }

    @Test
    void theSameItemWithItsAmountPasses() {
        S[] bag = new S[36];
        bag[3] = new S("Diamond", 10, null);
        assertNull(verify(held(Map.of(3, new S("Diamond", 10, null)), bag), List.of(), Set.of()));
        bag[3] = new S("Diamond", 64, null);
        assertNull(verify(held(Map.of(3, new S("Diamond", 10, null)), bag), List.of(), Set.of()),
                "more than offered is fine (a pickup merged in): only the offer moves");
    }

    @Test
    void anAmountThatFellFails() {
        S[] bag = new S[36];
        bag[3] = new S("Diamond", 9, null);
        Failure f = verify(held(Map.of(3, new S("Diamond", 10, null)), bag), List.of(), Set.of());
        assertEquals("Owner's Diamond changed - look again.", f.message());
    }

    @Test
    void aSimilarLookingMiniWithAnotherUidFails() {
        S[] bag = new S[36];
        bag[0] = new S("Creeper Mini", 1, "uid-copy");
        Failure f = verify(held(Map.of(0, new S("Creeper Mini", 1, "uid-real")), bag), List.of(), Set.of());
        assertEquals("Owner's Creeper Mini changed - look again.", f.message(), "[X] another copy is not similar");
    }

    @Test
    void aStackMovedToAnotherSlotFails() {
        S[] bag = new S[36];
        bag[7] = new S("Diamond", 10, null); // moved from 3 to 7
        Failure f = verify(held(Map.of(3, new S("Diamond", 10, null)), bag), List.of(), Set.of());
        assertEquals("Owner's Diamond changed - look again.", f.message(), "[X] the slot is what is checked");
        bag[3] = new S("Emerald", 10, null);
        assertEquals("Owner's Diamond changed - look again.",
                verify(held(Map.of(3, new S("Diamond", 10, null)), bag), List.of(), Set.of()).message(),
                "and something else in that slot is not it");
    }

    @Test
    void oneUidOnBothTablesIsRefusedWithAWarn() {
        S[] bagA = new S[36];
        S[] bagB = new S[36];
        bagA[0] = new S("Creeper Mini", 1, "uid-7");
        bagB[2] = new S("Creeper Mini", 1, "uid-7");
        Failure f = verify(held(Map.of(0, bagA[0]), bagA), held(Map.of(2, bagB[2]), bagB), Set.of());
        assertEquals("&7Creeper Mini is on the table twice - it can't be traded.", f.message());
        assertEquals("Mini uid-7 is on both tables at once - a copy?", f.warn(), "[X] a WARN: a copy exists");
    }

    @Test
    void aUidOnATableAndInAVendingListingIsRefusedWithAWarn() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            Database db = Database.open(conn, Logger.getAnonymousLogger());
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("INSERT INTO mini_vending_listings (world,x,y,z,owner,uid,mini_id,mint_number,price,"
                        + "item_b64,listed_at) VALUES ('world',1,2,3,'" + B + "','uid-9','creeper',4,50.0,'b64',1)");
                st.executeUpdate("INSERT INTO mini_auctions (uid,mini_id,mint_number,seller,start_bid,item_b64,end_at,"
                        + "status,created_at) VALUES ('uid-old','creeper',5,'" + B + "',1,'b64',1,'CLOSED',1)");
            }
            TradeDao dao = new TradeDao(db);
            Set<String> listed = dao.listedUids(List.of("uid-9", "uid-old", "uid-free"));
            assertEquals(Set.of("uid-9"), listed, "an active listing counts, a closed auction doesn't");
            S[] bag = new S[36];
            bag[0] = new S("Creeper Mini", 1, "uid-9");
            Failure f = verify(held(Map.of(0, bag[0]), bag), List.of(), listed);
            assertEquals("&7Creeper Mini is in a shop too - it can't be traded.", f.message());
            assertEquals("Mini uid-9 is on the table and in a listing - a copy?", f.warn(), "[X]");
        }
    }

    @Test
    void theWindowsTheWorldAndTheMoney() {
        List<Held> none = List.of();
        SideNow gone = new SideNow(B, "Helper", true, false, "world", OK, none, 0, true, id -> Claim.NONE);
        assertEquals("Helper left the trade.",
                TradeRules.verify(side("Owner", none, 0, true), gone, true, Refuse.NONE, Set.of()).message());
        SideNow away = new SideNow(B, "Helper", true, true, "nether", OK, none, 0, true, id -> Claim.NONE);
        assertEquals("You're not in the same world any more.",
                TradeRules.verify(side("Owner", none, 0, true), away, true, Refuse.NONE, Set.of()).message());
        SideNow creative = new SideNow(B, "Helper", true, true, "world",
                new Where(true, false, true, true, GameMode.CREATIVE), none, 0, true, id -> Claim.NONE);
        assertTrue(TradeRules.verify(side("Owner", none, 0, true), creative, true, Refuse.NONE, Set.of()).message()
                .startsWith("Helper can't trade right now"));
        assertEquals("Owner doesn't have that much money any more.",
                TradeRules.verify(side("Owner", none, 50, false), side("Helper", none, 0, true), true, Refuse.NONE,
                        Set.of()).message());
        assertEquals("Money can't be traded right now.",
                TradeRules.verify(side("Owner", none, 50, true), side("Helper", none, 0, true), false, Refuse.NONE,
                        Set.of()).message(), "Vault went away");
    }

    @Test
    void theRefusalScanAndT3RunOnTheRealStacks() {
        S[] bag = new S[36];
        bag[0] = new S("Diamond", 3, null);
        List<Held> entries = held(Map.of(0, bag[0]), bag);
        // the same entries, but the real stack is now known to be a kit item
        Held h = entries.get(0);
        ItemFacts kit = new ItemFacts("Diamond", "DIAMOND", 3, true, null, true, false, false, false, false, false,
                Set.of(), null, null, false, List.of());
        List<Held> real = List.of(new Held(h.slot(), h.name(), h.want(), true, 3, kit));
        assertEquals(TradeRules.KIT, verify(real, List.of(), Set.of()).message());
        ItemFacts boost = new ItemFacts("Speed Boost", "SUGAR", 1, false, "speed_boost", true, false, false, false,
                false, false, Set.of(), null, null, false, List.of());
        List<Held> prize = List.of(new Held(0, "Speed Boost", 1, true, 1, boost));
        assertEquals(TradeRules.NO_MONEY_WITH_PRIZES, TradeRules.verify(side("Owner", prize, 0, true),
                side("Helper", List.of(), 20, true), true, Refuse.NONE, Set.of()).message(), "T3 on the real stacks");
    }
}
