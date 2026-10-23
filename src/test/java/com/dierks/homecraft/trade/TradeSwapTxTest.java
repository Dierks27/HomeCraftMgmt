package com.dierks.homecraft.trade;

import com.dierks.homecraft.marketplace.DeliveryService;
import com.dierks.homecraft.mini.MiniService;
import com.dierks.homecraft.storage.Database;
import com.dierks.homecraft.storage.DeliveryDao;
import com.dierks.homecraft.storage.MiniDao;
import com.dierks.homecraft.storage.PrizeDao;
import com.dierks.homecraft.storage.TradeDao;
import com.dierks.homecraft.trade.TradeRules.Claim;
import com.dierks.homecraft.trade.TradeSession.Side;
import com.dierks.homecraft.trade.TradeSwap.Party;
import com.dierks.homecraft.trade.TradeSwap.Result;
import com.dierks.homecraft.trade.TradeSwap.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The swap (SHOP-V2-SPEC §4.6, §4.7, §4.11, D41, red team D-M5, D-m1, D-m4, D-m5) over stand-in stacks and a real
 * in-memory database: Mini owners move with a price-0 sale naming the RECORDED owner as seller, venue TRADE or GIFT;
 * a collector's claim moves; overflow is HELD in the Mailbox and READY only after the flip; a claim that changed
 * mid-way rolls back every row and puts every taken stack back in its own slot; a planted divergence ends in the
 * Mailbox, never the ground; a slot emptied between the verify and the take moves nothing; a PENDING trade at
 * enable logs one WARN and releases nothing; and (red-team findings TCS-2, TCS-3, TCS-5) the server killed anywhere
 * in the save step leaves bags whose marks say where they are, so the one verb that fits loses and copies nothing.
 */
class TradeSwapTxTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID HELPER = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID EARLIER = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final String CAP = "cap_pumpkin_2026";
    private static final long T = 1_000_000L;

    /**
     * A stand-in stack: what it is, how many, the stack limit the plan reads ({@code max}), the one the bag really
     * keeps ({@code bagMax}, for a planted divergence), and its facts.
     */
    private record Stack(String kind, int amount, int max, int bagMax, ItemFacts facts) {

        Stack withAmount(int n) {
            ItemFacts f = facts;
            ItemFacts g = new ItemFacts(f.name(), f.material(), n, f.kit(), f.prizeId(), f.prize(), f.bound(),
                    f.courier(), f.displayOnly(), f.candyBag(), f.ghost(), f.keys(), f.miniUid(), f.miniId(),
                    f.unread(), f.inside());
            return new Stack(kind, n, max, bagMax, g);
        }
    }

    private static Stack plain(String name, int amount) {
        return new Stack(name, amount, 64, 64, ItemFacts.plain(name, "STONE", amount));
    }

    private static Stack mini(UUID uid) {
        ItemFacts f = new ItemFacts("Creeper Mini", "PLAYER_HEAD", 1, false, null, false, false, false, false, false,
                false, Set.of(), uid.toString(), "creeper", false, List.of());
        return new Stack("mini:" + uid, 1, 64, 64, f);
    }

    private static Stack cap() {
        ItemFacts f = new ItemFacts("Pumpkin Cap 2026", "LEATHER_HELMET", 1, false, CAP, true, false, false, false,
                false, false, Set.of(), null, null, false, List.of());
        return new Stack("cap:7", 1, 1, 1, f);
    }

    private static Stack shulker(Stack... inside) {
        List<ItemFacts> in = new ArrayList<>();
        for (Stack s : inside) {
            in.add(s.facts());
        }
        return new Stack("shulker:" + Arrays.toString(inside), 1, 1, 1,
                ItemFacts.plain("Shulker box", "SHULKER_BOX", 1).holding(in));
    }

    /** Every kind {@link #ITEMS} stored, so a stored line reads back ({@link #decode}). */
    private static final Map<String, Stack> KINDS = new java.util.HashMap<>();

    /** A stored line ("Diamond x16") back to its stack. */
    private static Stack decode(String line) {
        int x = line.lastIndexOf(" x");
        Stack kind = x < 0 ? null : KINDS.get(line.substring(0, x));
        return kind == null ? null : kind.withAmount(Integer.parseInt(line.substring(x + 2)));
    }

    private static final TradeSwap.Items<Stack> ITEMS = new TradeSwap.Items<>() {
        @Override
        public int amount(Stack item) {
            return item.amount();
        }

        @Override
        public boolean similar(Stack a, Stack b) {
            return a.kind().equals(b.kind());
        }

        @Override
        public int maxStack(Stack item) {
            return item.max();
        }

        @Override
        public Stack withAmount(Stack item, int amount) {
            return item.withAmount(amount);
        }

        @Override
        public String encode(Stack item) {
            KINDS.put(item.kind(), item);
            return item.kind() + " x" + item.amount();
        }

        @Override
        public ItemFacts facts(Stack item) {
            return item == null ? null : item.facts();
        }
    };

    /** Both bags: 36 slots each; {@code add} works the way addItem does, with each stack's real limit. */
    private static class Bags implements TradeSwap.Bags<Stack> {
        final Map<Side, Stack[]> bag = new EnumMap<>(Side.class);
        final List<Side> saved = new ArrayList<>();
        /** Marks and saves, in order ("mark A", "save B"). */
        final List<String> events = new ArrayList<>();
        /** Each bag's {@code hcm:trade_seen} mark (and its time), live. */
        final Map<Side, TradeStore.Mark> seen = new EnumMap<>(Side.class);

        Bags() {
            bag.put(Side.A, new Stack[36]);
            bag.put(Side.B, new Stack[36]);
        }

        @Override
        public Stack get(Side s, int slot) {
            return bag.get(s)[slot];
        }

        @Override
        public void set(Side s, int slot, Stack item) {
            bag.get(s)[slot] = item;
        }

        @Override
        public List<Stack> storage(Side s) {
            return new ArrayList<>(Arrays.asList(bag.get(s)));
        }

        @Override
        public int add(Side s, Stack item) {
            Stack[] b = bag.get(s);
            int left = item.amount();
            for (int i = 0; i < b.length && left > 0; i++) {
                if (b[i] != null && b[i].kind().equals(item.kind()) && b[i].amount() < item.bagMax()) {
                    int n = Math.min(left, item.bagMax() - b[i].amount());
                    b[i] = b[i].withAmount(b[i].amount() + n);
                    left -= n;
                }
            }
            for (int i = 0; i < b.length && left > 0; i++) {
                if (b[i] == null) {
                    int n = Math.min(left, item.bagMax());
                    b[i] = item.withAmount(n);
                    left -= n;
                }
            }
            return left;
        }

        @Override
        public void save(Side s) {
            saved.add(s);
            events.add("save " + s);
        }

        @Override
        public void mark(Side s, long id, long at) {
            seen.put(s, new TradeStore.Mark(id, at));
            events.add("mark " + s);
        }

        void fill(Side s, String kind) {
            Stack[] b = bag.get(s);
            for (int i = 0; i < b.length; i++) {
                if (b[i] == null) {
                    b[i] = plain(kind + i, 1);
                }
            }
        }
    }

    /** Vault: two balances; a payer or payee (or a second payee: a refund) can be told to fail. */
    private static final class Money implements TradeSwap.Money {
        final Map<Side, Double> balance = new EnumMap<>(Map.of(Side.A, 100.0, Side.B, 100.0));
        Side withdrawFails;
        Side depositFails;
        Side alsoFails;

        @Override
        public boolean withdraw(Side s, double amount) {
            if (s == withdrawFails || balance.get(s) < amount) {
                return false;
            }
            balance.merge(s, -amount, Double::sum);
            return true;
        }

        @Override
        public boolean deposit(Side s, double amount) {
            if (s == depositFails || s == alsoFails) {
                return false;
            }
            balance.merge(s, amount, Double::sum);
            return true;
        }

        @Override
        public Double balance(Side s) {
            return balance.get(s);
        }
    }

    private static final class Log implements TradeSwap.Log {
        final List<String> info = new ArrayList<>();
        final List<String> warn = new ArrayList<>();
        final List<String> severe = new ArrayList<>();

        @Override
        public void info(String line) {
            info.add(line);
        }

        @Override
        public void warn(String line) {
            warn.add(line);
        }

        @Override
        public void severe(String line) {
            severe.add(line);
        }
    }

    private Connection conn;
    private Database db;
    private TradeDao trades;
    private DeliveryDao deliveries;
    private MiniDao minis;
    private PrizeDao prizes;
    private TradeStore store;
    private Bags bags;
    private Money money;
    private Log log;

    @BeforeEach
    void open() throws SQLException {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        db = Database.open(conn, Logger.getAnonymousLogger());
        trades = new TradeDao(db);
        deliveries = new DeliveryDao(db);
        minis = new MiniDao(db);
        prizes = new PrizeDao(db);
        store = new TradeStore(db, trades, new DeliveryService(null, deliveries), deliveries,
                new TradeStore.MiniOwners() {
                    @Override
                    public List<UUID> move(List<MiniService.MiniMove> moves, long now) throws SQLException {
                        return MiniService.tradeOwners(minis, moves, now);
                    }

                    @Override
                    public boolean back(String uid, UUID before, UUID receiver, long soldAt) throws SQLException {
                        return MiniService.tradeBack(minis, uid, before, receiver, soldAt);
                    }
                }, prizes);
        bags = new Bags();
        money = new Money();
        log = new Log();
    }

    @AfterEach
    void close() throws SQLException {
        conn.close();
    }

    /** A session at the end of its countdown with this offer on it (put on the table in bag-slot order). */
    private TradeSession<Stack> counted(Map<Integer, Integer> a, Map<Integer, Integer> b, double moneyA,
                                        double moneyB) {
        TradeSession<Stack> s = new TradeSession<>(OWNER, HELPER, 1_500, 3);
        long t = T - 60_000;
        new java.util.TreeMap<>(a).forEach((slot, n) -> s.put(Side.A, slot, bags.get(Side.A, slot), n, t));
        new java.util.TreeMap<>(b).forEach((slot, n) -> s.put(Side.B, slot, bags.get(Side.B, slot), n, t));
        s.setMoney(Side.A, moneyA, t);
        s.setMoney(Side.B, moneyB, t);
        tap(s, Side.A, t + 10_000);
        tap(s, Side.B, t + 10_000);
        return s;
    }

    private static void tap(TradeSession<Stack> s, Side side, long now) {
        if (s.tapReady(side, now, false, true) == TradeSession.Ready.ARMED) {
            s.tapReady(side, now + TradeSession.GIVE_AWAY_SETTLE_MS, false, true);
        }
    }

    private Result<Stack> run(TradeSession<Stack> s, TradeSwap.Verifier v, Claim claimsA, Claim claimsB) {
        return runAt(T, s, v, claimsA, claimsB);
    }

    /** The swap at {@code now} (a later trade swaps later). */
    private Result<Stack> runAt(long now, TradeSession<Stack> s, TradeSwap.Verifier v, Claim claimsA, Claim claimsB) {
        return new TradeSwap<>(bags, ITEMS, money, store, log).run(s, new Party(OWNER, "Owner"),
                new Party(HELPER, "Helper"), "world", now - 60_000, now, v, id -> claimsA, id -> claimsB);
    }

    private Result<Stack> run(TradeSession<Stack> s) {
        return run(s, () -> null, Claim.NONE, Claim.NONE);
    }

    private UUID owner(UUID uid) throws SQLException {
        return minis.individual(uid.toString()).orElseThrow().owner();
    }

    private int count(String sql) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    // ---- Minis -----------------------------------------------------------------------------------------

    @Test
    void ownersMoveAndThreeSalesNameTheRecordedOwnerAsSeller() throws Exception {
        UUID m1 = UUID.randomUUID();
        UUID m2 = UUID.randomUUID();
        UUID m3 = UUID.randomUUID();
        minis.mint("creeper", m1, OWNER, "TEST", 1);
        minis.mint("creeper", m2, EARLIER, "TEST", 2); // the owner holds a copy recorded to someone else
        minis.mint("creeper", m3, HELPER, "TEST", 3);
        bags.set(Side.A, 0, mini(m1));
        bags.set(Side.A, 1, shulker(plain("Diamond", 4), mini(m2)));
        bags.set(Side.B, 9, mini(m3));
        Result<Stack> r = run(counted(Map.of(0, 1, 1, 1), Map.of(9, 1), 0, 0));
        assertEquals(Status.DONE, r.status(), String.valueOf(r.told()));
        assertEquals(HELPER, owner(m1));
        assertEquals(HELPER, owner(m2), "a Mini inside a shulker moves too");
        assertEquals(OWNER, owner(m3));
        List<com.dierks.homecraft.storage.MiniDao.Sale> sales = new ArrayList<>();
        for (UUID u : List.of(m1, m2, m3)) {
            sales.addAll(minis.salesForUid(u.toString()));
        }
        assertEquals(3, sales.size(), "three mini_sales rows");
        for (var sale : sales) {
            assertEquals(0.0, sale.price(), "price 0");
            assertEquals(MiniService.VENUE_TRADE, sale.venue());
        }
        assertEquals(OWNER, sales.get(0).seller());
        assertEquals(EARLIER, sales.get(1).seller(), "the seller is the RECORDED owner, not whoever handed it over");
        assertEquals(HELPER, sales.get(1).buyer());
        assertEquals(HELPER, sales.get(2).seller());
        assertEquals(OWNER, sales.get(2).buyer());
        assertEquals("mini:" + m3, bags.get(Side.A, 0).kind(), "the owner's freed slot took the helper's Mini");
        TradeDao.Trade row = trades.byId(r.id()).orElseThrow();
        assertEquals(TradeDao.DONE, row.outcome());
        assertEquals("Creeper Mini, Shulker box", row.aSummary());
        assertEquals(2, row.aItems().split("\n").length, "one stored line per stack");
        assertEquals(List.of(Side.A, Side.B), bags.saved, "both bags saved before the flip");
        assertEquals(List.of("Owner traded with Helper (#" + r.id() + ")"), log.info);
    }

    @Test
    void aOneSidedTradeIsAGift() throws Exception {
        UUID m = UUID.randomUUID();
        minis.mint("creeper", m, OWNER, "TEST", 1);
        bags.set(Side.A, 3, mini(m));
        Result<Stack> r = run(counted(Map.of(3, 1), Map.of(), 0, 0));
        assertTrue(r.ok());
        var sale = minis.salesForUid(m.toString()).get(0);
        assertEquals(MiniService.VENUE_GIFT, sale.venue(), "the helper gave nothing: GIFT");
        assertEquals(HELPER, owner(m));

        UUID n = UUID.randomUUID();
        minis.mint("creeper", n, OWNER, "TEST", 2);
        bags.set(Side.A, 4, mini(n));
        Result<Stack> paid = run(counted(Map.of(4, 1), Map.of(), 0, 5));
        assertTrue(paid.ok());
        assertEquals(MiniService.VENUE_TRADE, minis.salesForUid(n.toString()).get(0).venue(),
                "money back makes it a trade");
        assertEquals(105.0, money.balance.get(Side.A));
        assertEquals(95.0, money.balance.get(Side.B));
    }

    // ---- collectors -----------------------------------------------------------------------------------

    @Test
    void aCollectorsClaimMovesWithIt() throws Exception {
        prizes.claimOnce(OWNER, CAP);
        bags.set(Side.A, 0, cap());
        bags.set(Side.B, 0, plain("Diamond", 3));
        Result<Stack> r = run(counted(Map.of(0, 1), Map.of(0, 3), 0, 0), () -> null, new Claim(true, false),
                Claim.NONE);
        assertTrue(r.ok(), String.valueOf(r.told()));
        assertEquals(0, prizes.purchases(OWNER, CAP, "life"), "the claim left the giver");
        assertEquals(1, prizes.purchases(HELPER, CAP, "life"), "and is the receiver's");
        assertEquals("cap:7", bags.get(Side.B, 0).kind());
        assertEquals("Diamond", bags.get(Side.A, 0).kind());
    }

    @Test
    void aCapGoingHomeMovesNoClaim() throws Exception {
        prizes.claimOnce(HELPER, CAP);
        bags.set(Side.A, 0, cap());
        Result<Stack> r = run(counted(Map.of(0, 1), Map.of(), 0, 0), () -> null, new Claim(false, true), Claim.NONE);
        assertTrue(r.ok());
        assertEquals(1, prizes.purchases(HELPER, CAP, "life"), "still the helper's, one row");
        assertEquals(0, prizes.purchases(OWNER, CAP, "life"));
    }

    @Test
    void aClaimOnceFalseMidWayRollsBackEveryRowAndPutsEveryStackBack() throws Exception {
        UUID m = UUID.randomUUID();
        minis.mint("creeper", m, OWNER, "TEST", 1);
        prizes.claimOnce(OWNER, CAP);
        prizes.claimOnce(HELPER, CAP); // the helper got one meanwhile; the verify read before that
        Stack capStack = cap();
        Stack miniStack = mini(m);
        Stack diamonds = plain("Diamond", 20);
        bags.set(Side.A, 0, capStack);
        bags.set(Side.A, 1, miniStack);
        bags.set(Side.B, 5, diamonds);
        bags.fill(Side.B, "dirt"); // so something is planned for the Mailbox too
        Stack[] beforeA = bags.bag.get(Side.A).clone();
        Stack[] beforeB = bags.bag.get(Side.B).clone();
        Result<Stack> r = run(counted(Map.of(0, 1, 1, 1), Map.of(5, 7), 10, 0), () -> null, new Claim(true, false),
                Claim.NONE);
        assertEquals(Status.FAILED, r.status());
        assertEquals("That didn't work - nothing changed hands.", r.told().get(Side.A));
        assertArrayEquals(beforeA, bags.bag.get(Side.A), "[X] every taken stack is back in its own slot");
        assertArrayEquals(beforeB, bags.bag.get(Side.B));
        assertSame(diamonds, bags.get(Side.B, 5), "the very stack, not a copy");
        assertEquals(0, count("SELECT COUNT(*) FROM trades"), "no log row");
        assertEquals(0, count("SELECT COUNT(*) FROM deliveries"), "no Mailbox row");
        assertEquals(0, count("SELECT COUNT(*) FROM mini_sales"), "no sale");
        assertEquals(OWNER, owner(m), "no owner change");
        assertEquals(1, prizes.purchases(OWNER, CAP, "life"), "the giver's claim is still there");
        assertEquals(100.0, money.balance.get(Side.A), "the money went back");
        assertEquals(1, log.severe.size());
    }

    @Test
    void anUnclaimOfNoRowsRollsBackToo() throws Exception {
        bags.set(Side.A, 0, cap()); // the verify said the giver held the claim, but no row is there
        bags.set(Side.B, 2, plain("Emerald", 3));
        Stack[] beforeA = bags.bag.get(Side.A).clone();
        Result<Stack> r = run(counted(Map.of(0, 1), Map.of(2, 3), 0, 0), () -> null, new Claim(true, false),
                Claim.NONE);
        assertEquals(Status.FAILED, r.status());
        assertArrayEquals(beforeA, bags.bag.get(Side.A), "[X] put back");
        assertEquals("Emerald", bags.get(Side.B, 2).kind());
        assertEquals(0, count("SELECT COUNT(*) FROM trades"));
        assertEquals(0, prizes.purchases(HELPER, CAP, "life"), "no claim was written");
    }

    // ---- the Mailbox ------------------------------------------------------------------------------------

    @Test
    void overflowIsHeldInTheSwapAndReadyOnlyAfterTheFlip() throws Exception {
        TradeDao.Trade row = new TradeDao.Trade(0, OWNER, "Owner", HELPER, "Helper", "world", T, T, TradeDao.PENDING,
                "", "", "", "", "", 0, 0, 1);
        long id = store.commit(row, List.of(new TradeStore.Held(HELPER, "diamond x3", "From your trade with Owner")),
                List.of(), List.of(), T);
        assertEquals(1, count("SELECT COUNT(*) FROM deliveries WHERE status='HELD' AND source='TRADE#" + id + "'"));
        assertEquals(List.of(), deliveries.activeFor(HELPER), "[X] a HELD row isn't in the Mailbox yet");
        store.finish(id);
        assertEquals(1, deliveries.activeFor(HELPER).size(), "READY after the flip");
        assertEquals(DeliveryDao.READY, deliveries.activeFor(HELPER).get(0).status());
        assertEquals(TradeDao.DONE, trades.byId(id).orElseThrow().outcome());

        // and through a whole swap: the helper's bag is full
        bags.fill(Side.B, "dirt");
        bags.set(Side.A, 0, plain("Diamond", 3));
        bags.set(Side.A, 1, plain("Emerald", 5));
        Result<Stack> r = run(counted(Map.of(0, 3, 1, 5), Map.of(), 0, 0));
        assertTrue(r.ok());
        assertEquals(2, r.mailbox().get(Side.B), "two stacks to the helper's Mailbox");
        assertEquals(3, deliveries.activeFor(HELPER).size());
        List<DeliveryDao.Delivery> mail = deliveries.bySource("TRADE#" + r.id());
        assertEquals(2, mail.size());
        assertEquals("Diamond x3", mail.get(0).itemB64());
        assertEquals("Emerald x5", mail.get(1).itemB64());
        assertEquals("From your trade with Owner", mail.get(0).label());
        assertEquals(DeliveryDao.READY, mail.get(0).status());
        assertEquals(2, trades.byId(r.id()).orElseThrow().mailbox());
    }

    @Test
    void aPlantedDivergenceEndsHeldInTheMailboxNeverTheGround() throws Exception {
        // the plan reads a limit of 64, but the bag keeps these one to a slot (a max_stack_size 1 component)
        Stack odd = new Stack("ticket", 5, 64, 1, ItemFacts.plain("Ticket", "PAPER", 5));
        bags.set(Side.A, 0, odd);
        bags.fill(Side.B, "dirt");
        bags.set(Side.B, 7, new Stack("ticket", 1, 64, 1, ItemFacts.plain("Ticket", "PAPER", 1)));
        bags.set(Side.B, 8, null); // one free slot: the plan thinks 63 more fit on slot 7
        Result<Stack> r = run(counted(Map.of(0, 5), Map.of(), 0, 0));
        assertTrue(r.ok());
        assertEquals("ticket", bags.get(Side.B, 8).kind(), "one went into the free slot");
        List<DeliveryDao.Delivery> mail = deliveries.activeFor(HELPER);
        int mailed = 0;
        for (DeliveryDao.Delivery d : mail) {
            mailed += Integer.parseInt(d.itemB64().substring(d.itemB64().indexOf('x') + 1));
            assertEquals("TRADE#" + r.id(), d.source());
            assertEquals(DeliveryDao.READY, d.status(), "released with the trade once it finished");
        }
        assertEquals(4, mailed, "[X] the four the bag wouldn't take are in the Mailbox, never the ground");
        assertTrue(log.severe.get(0).contains("Trade #" + r.id()), "one SEVERE naming the trade: " + log.severe);
        assertEquals(1, r.mailbox().get(Side.B), "one Mailbox row");
        assertEquals(1, trades.byId(r.id()).orElseThrow().mailbox(), "and the log counts it");
    }

    // ---- the take ---------------------------------------------------------------------------------------

    @Test
    void aSlotEmptiedBetweenTheVerifyAndTheTakeMovesNothing() throws Exception {
        UUID m = UUID.randomUUID();
        minis.mint("creeper", m, HELPER, "TEST", 1);
        bags.set(Side.A, 0, plain("Diamond", 3));
        bags.set(Side.B, 4, mini(m));
        TradeSession<Stack> s = counted(Map.of(0, 3), Map.of(4, 1), 10, 0);
        Stack diamonds = bags.get(Side.A, 0);
        Result<Stack> r = run(s, () -> {
            bags.set(Side.B, 4, null); // gone the instant after the check
            return null;
        }, Claim.NONE, Claim.NONE);
        assertEquals(Status.FAILED, r.status());
        assertEquals("Your Creeper Mini changed - look again.", r.told().get(Side.B));
        assertEquals("Helper's Creeper Mini changed - look again.", r.told().get(Side.A));
        assertSame(diamonds, bags.get(Side.A, 0), "[X] the owner's diamonds never left");
        assertEquals(0, count("SELECT COUNT(*) FROM trades"));
        assertEquals(HELPER, owner(m));
        assertEquals(100.0, money.balance.get(Side.A), "no money moved");
        assertEquals(TradeSession.Phase.OPEN, s.phase(), "open again");
    }

    @Test
    void aFailedVerifyMovesNothingAndCarriesItsWarn() throws Exception {
        bags.set(Side.A, 0, plain("Diamond", 3));
        TradeSession<Stack> s = counted(Map.of(0, 3), Map.of(), 0, 0);
        Result<Stack> r = run(s, () -> new TradeRules.Failure("Nope.", "Mini x is on the table and in a listing"),
                Claim.NONE, Claim.NONE);
        assertEquals(Status.FAILED, r.status());
        assertEquals("Mini x is on the table and in a listing", r.warn());
        assertEquals("Diamond", bags.get(Side.A, 0).kind());
        assertEquals(Status.NOT_NOW, run(s).status(), "OPEN again: no second run without a countdown");
    }

    @Test
    void aSecondWithdrawThatFailsPaysTheFirstBack() throws Exception {
        bags.set(Side.A, 0, plain("Diamond", 3));
        bags.set(Side.B, 0, plain("Emerald", 3));
        money.withdrawFails = Side.B;
        TradeSession<Stack> s = counted(Map.of(0, 3), Map.of(0, 3), 10, 10);
        Result<Stack> r = run(s);
        assertEquals(Status.FAILED, r.status());
        assertEquals(100.0, money.balance.get(Side.A), "paid back");
        assertEquals("Diamond", bags.get(Side.A, 0).kind());
        assertEquals(0, count("SELECT COUNT(*) FROM trades"));
    }

    @Test
    void theSwapRunsOnce() throws Exception {
        bags.set(Side.A, 0, plain("Diamond", 3));
        TradeSession<Stack> s = counted(Map.of(0, 3), Map.of(), 0, 0);
        assertTrue(run(s).ok());
        assertEquals(Status.NOT_NOW, run(s).status(), "[X] a second timer does nothing");
        assertEquals(1, count("SELECT COUNT(*) FROM trades"));
        assertEquals("Diamond", bags.get(Side.B, 0).kind());
        assertEquals(null, bags.get(Side.B, 1), "given once");
    }


    // ---- at enable ------------------------------------------------------------------------------------

    /**
     * Marks as read at a start: the owner's bag holds trade {@code owner} at most, the helper's {@code helper} (0: no
     * mark, saved before every trade here), each with the time every trade here swapped at.
     */
    private static TradeStore.Marks marks(long owner, long helper) {
        return p -> mark(p.equals(OWNER) ? owner : helper);
    }

    private static TradeStore.Mark mark(long id) {
        return id == 0 ? TradeStore.Mark.NONE : new TradeStore.Mark(id, T);
    }

    /** The bags' live marks: both players still online. */
    private TradeStore.Marks live() {
        return p -> bags.seen.getOrDefault(p.equals(OWNER) ? Side.A : Side.B, TradeStore.Mark.NONE);
    }

    private TradeDao.Trade row(long id) throws SQLException {
        return trades.byId(id).orElseThrow();
    }

    private int held() throws SQLException {
        return count("SELECT COUNT(*) FROM deliveries WHERE status='HELD'");
    }

    @Test
    void aPendingTradeAtEnableLogsOneWarnAndReleasesNothing() throws Exception {
        TradeDao.Trade row = new TradeDao.Trade(0, OWNER, "Owner", HELPER, "Helper", "world", T, T, TradeDao.PENDING,
                "", "", "", "3 Diamond", "", 0, 0, 1);
        long id = store.commit(row, List.of(new TradeStore.Held(HELPER, "diamond x3", "From your trade with Owner")),
                List.of(), List.of(), T); // the server died before either bag was saved
        List<String> warned = new ArrayList<>();
        assertEquals(1, store.check(warned::add, marks(0, 0)));
        assertEquals(List.of("Trade #" + id + " between Owner and Helper didn't finish - neither bag was saved with"
                + " it, so both came back from before it, each still holding its own offer: /hcm trades settle " + id
                + " void (release would hand out copies) (check /hcm trades show " + id + ")"), warned,
                "[X] one WARN, and it says void");
        assertEquals(List.of(), deliveries.activeFor(HELPER), "[X] nothing is released on its own");
        assertEquals(1, held());
        assertEquals(TradeDao.PENDING, row(id).outcome());
        assertEquals(TradeStore.Saved.NEITHER.code(), row(id).reason(), "where the bags are is on the row");

        // later both players trade again and their marks go past it: the row's answer stands
        warned.clear();
        store.check(warned::add, marks(id + 5, id + 5));
        assertEquals(TradeStore.Saved.NEITHER, TradeStore.recorded(row(id)),
                "[X] a later trade's mark can't change it: read again, only its own mark counts");
        TradeStore.Settled no = store.release(id, marks(id + 5, id + 5));
        assertEquals(TradeStore.Outcome.REFUSED, no.outcome(), "[X] release would hand out a copy");
        assertTrue(no.why().contains("(release would hand out copies). If you looked at both bags and they say"
                + " otherwise: /hcm trades settle " + id + " release confirm"), no.why());
        assertEquals(List.of(), deliveries.activeFor(HELPER), "the refused release handed out nothing");
        TradeStore.Settled v = store.voidTrade(id, marks(id + 5, id + 5));
        assertEquals(TradeStore.Outcome.VOIDED, v.outcome());
        assertEquals(1, v.rows());
        assertEquals(0, count("SELECT COUNT(*) FROM deliveries"));
        assertEquals(TradeDao.VOID, row(id).outcome());
        assertEquals(TradeStore.Outcome.NOT_PENDING, store.voidTrade(id, marks(0, 0)).outcome(), "once");
    }

    /** Red-team finding TCS-3: each bag is marked with the trade before it is saved, so the mark reaches the disk. */
    @Test
    void theSwapMarksBothBagsBeforeItSavesThem() throws Exception {
        bags.set(Side.A, 0, plain("Diamond", 3));
        bags.set(Side.B, 0, plain("Emerald", 2));
        Result<Stack> r = run(counted(Map.of(0, 3), Map.of(0, 2), 0, 0));
        assertTrue(r.ok());
        assertEquals(List.of("mark A", "mark B", "save A", "save B"), bags.events, "[X] each bag saved with its mark");
        assertEquals(Map.of(Side.A, mark(r.id()), Side.B, mark(r.id())), bags.seen, "the trade's id and time");
        assertFalse(r.held());
        assertEquals("", row(r.id()).reason(), "a finished trade carries no reason");
    }

    /**
     * Red-team findings TCS-3, TCR-FA-3: vanilla only logs a failed save, so the live save checks the player's file and
     * throws when it didn't change ({@code SaveCheckTest}). The trade stays PENDING, its rows HELD, the player is told
     * they are on their way (TCS-5). While the server runs, the bag that wasn't saved still holds the trade, so its
     * live mark can't say where it comes back from: nothing is settled until the next server start reads the disk.
     * Red-team finding R4FA-4: a plugin reload read that bag from live memory as saved and opened release; a reload
     * now reads and writes nothing.
     */
    @Test
    void aSaveThatDidNotReachTheDiskWaitsForTheNextServerStart() throws Exception {
        Disk disk = new Disk();
        bags = disk;
        bags.fill(Side.B, "dirt");
        bags.set(Side.A, 0, plain("Diamond", 3));
        disk.autosave();
        disk.caught = Side.B;
        Result<Stack> r = run(counted(Map.of(0, 3), Map.of(), 0, 0));
        assertTrue(r.ok());
        assertTrue(r.held());
        assertEquals(TradeDao.PENDING, row(r.id()).outcome(), "[X] not DONE over a bag that isn't on disk");
        assertEquals(List.of(), deliveries.activeFor(HELPER), "[X] its row stays HELD");
        assertEquals(1, r.mailbox().get(Side.B));
        assertEquals("&e✉ 1 thing from the trade will reach your Mailbox once the trade is checked.",
                TradeService.mailLine(r, Side.B));
        assertTrue(log.severe.stream().anyMatch(l -> l.contains("couldn't save Helper's things: the save didn't"
                + " reach the disk")), log.severe.toString());
        assertTrue(log.severe.stream().anyMatch(l -> l.contains("stays PENDING with its Mailbox rows held")),
                log.severe.toString());
        assertEquals(TradeStore.Saved.BOTH, store.read(row(r.id()), live()), "both bags hold it live...");
        assertEquals(TradeStore.Saved.UNKNOWN, store.state(row(r.id()), live()), "...which can't be trusted");
        TradeStore.Settled no = store.release(r.id(), live());
        assertEquals(TradeStore.Outcome.REFUSED, no.outcome(), "[X] a release now could hand out copies");
        assertTrue(no.why().startsWith("Helper's bag didn't reach the disk when the swap saved it (the console said"
                + " why): while the server runs it holds the trade, but it would come back from before it, so nothing"
                + " can be settled yet. Put right what stopped the save, then restart the server"), no.why());
        assertEquals(TradeStore.Outcome.REFUSED, store.release(r.id(), live(), true).outcome(), "[X] nor with confirm");
        assertEquals(TradeStore.Outcome.REFUSED, store.voidTrade(r.id(), live()).outcome(), "nor a void");
        assertEquals(null, TradeStore.verb(row(r.id()), TradeStore.Saved.UNKNOWN), "no verb: look first");

        // a plugin reload, both players online: it reads nothing from live memory and writes nothing
        List<String> warned = new ArrayList<>();
        assertEquals(1, store.check(warned::add, live(), false, p -> null));
        assertEquals("bag b unsaved", row(r.id()).reason(), "[X] not \"bags saved\" read from live memory");
        assertTrue(warned.get(0).contains("Helper's bag didn't reach the disk"), warned.get(0));
        assertEquals(TradeStore.Outcome.REFUSED, store.release(r.id(), live()).outcome(), "[X] release stays shut");

        // the next server start reads the disk: the helper's bag came back from before it, but both played on after
        // the swap, so what the owner gave may be anywhere by now (SETTLE-REHELD-COPY-2): look first, no verb named
        warned.clear();
        store.check(warned::add, disk.marks());
        assertEquals(TradeStore.Saved.UNKNOWN, TradeStore.recorded(row(r.id())), warned.toString());
        assertEquals("bags unread, played on", row(r.id()).reason());
        assertTrue(warned.get(0).contains("only one bag reads as saved with it, but they played on after the swap (a"
                + " bag save that didn't reach the disk, or a later trade), so what each gave may have moved on since"
                + " (another bag, a chest, a shelf): look at both bags before you settle it"), warned.get(0));
        assertFalse(warned.get(0).contains("void Owner"), "[X] no verb named: " + warned.get(0));
        assertEquals(null, TradeStore.verb(row(r.id()), TradeStore.recorded(row(r.id()))));
    }

    /** Red-team finding TCS-5: a flip that fails leaves the rows HELD, and the line says so. */
    @Test
    void aFailedFlipSaysTheRowsAreOnTheirWay() throws Exception {
        try (var st = conn.createStatement()) {
            st.execute("CREATE TRIGGER no_flip BEFORE UPDATE OF outcome ON trades WHEN NEW.outcome='DONE'"
                    + " BEGIN SELECT RAISE(ABORT, 'locked'); END");
        }
        bags.fill(Side.B, "dirt");
        bags.set(Side.A, 0, plain("Diamond", 3));
        bags.set(Side.A, 1, plain("Emerald", 5));
        Result<Stack> r = run(counted(Map.of(0, 3, 1, 5), Map.of(), 0, 0));
        assertTrue(r.ok());
        assertTrue(r.held(), "the flip failed");
        assertEquals(TradeDao.PENDING, row(r.id()).outcome());
        assertEquals(List.of(), deliveries.activeFor(HELPER), "nothing in her Mailbox yet");
        assertEquals("&e✉ 2 things from the trade will reach your Mailbox once the trade is checked.",
                TradeService.mailLine(r, Side.B), "[X] not \"are in your Mailbox\"");
        assertEquals("", TradeService.mailLine(r, Side.A), "nothing went to the owner's");
    }

    /** Red-team finding TCS-5: stacks the late write couldn't store aren't counted as Mailbox rows. */
    @Test
    void aFailedLateWriteIsNotCountedAsInTheMailbox() throws Exception {
        try (var st = conn.createStatement()) {
            st.execute("CREATE TRIGGER no_mail BEFORE INSERT ON deliveries"
                    + " BEGIN SELECT RAISE(ABORT, 'disk full'); END");
        }
        Stack odd = new Stack("ticket", 5, 64, 1, ItemFacts.plain("Ticket", "PAPER", 5));
        bags.set(Side.A, 0, odd);
        bags.fill(Side.B, "dirt");
        bags.set(Side.B, 7, new Stack("ticket", 1, 64, 1, ItemFacts.plain("Ticket", "PAPER", 1)));
        bags.set(Side.B, 8, null);
        Result<Stack> r = run(counted(Map.of(0, 5), Map.of(), 0, 0));
        assertTrue(r.ok());
        assertFalse(r.held(), "the flip itself went through");
        assertEquals(0, r.mailbox().getOrDefault(Side.B, 0), "[X] no row was written");
        assertEquals(1, r.unwritten().get(Side.B), "one stack is owed by hand");
        assertEquals("", TradeService.mailLine(r, Side.B), "[X] no \"in your Mailbox\" line");
        assertTrue(log.severe.stream().anyMatch(l -> l.contains("Give them back by hand: ticket x4")),
                log.severe.toString());
    }

    @Test
    void theMailboxLineAfterAFinishedTrade() throws Exception {
        bags.fill(Side.B, "dirt");
        bags.set(Side.A, 0, plain("Diamond", 3));
        bags.set(Side.A, 1, plain("Emerald", 5));
        Result<Stack> r = run(counted(Map.of(0, 3, 1, 5), Map.of(), 0, 0));
        assertEquals("&e✉ 2 things from the trade are in your Mailbox (PC or Mailbox).",
                TradeService.mailLine(r, Side.B));
    }

    @Test
    void aVoidedTradeDeletesItsHeldRows() throws Exception {
        TradeDao.Trade row = new TradeDao.Trade(0, OWNER, "Owner", HELPER, "Helper", "world", T, T, TradeDao.PENDING,
                "", "", "", "", "", 0, 0, 2);
        long id = store.commit(row, List.of(new TradeStore.Held(HELPER, "a x1", "From your trade with Owner"),
                new TradeStore.Held(OWNER, "b x1", "From your trade with Helper")), List.of(), List.of(), T);
        assertEquals(2, store.voidTrade(id, marks(0, 0)).rows());
        assertEquals(0, count("SELECT COUNT(*) FROM deliveries"));
        assertEquals(TradeDao.VOID, row(id).outcome());
        assertEquals(TradeStore.Outcome.NOT_PENDING, store.voidTrade(id, marks(0, 0)).outcome());
    }

    /** A bag that can't be read leaves the choice to the admin, who looked: either verb works. */
    @Test
    void unreadableBagsLeaveTheChoiceToTheAdmin() throws Exception {
        TradeDao.Trade row = new TradeDao.Trade(0, OWNER, "Owner", HELPER, "Helper", "world", T, T, TradeDao.PENDING,
                "", "", "", "3 Diamond", "", 0, 0, 1);
        long id = store.commit(row, List.of(new TradeStore.Held(HELPER, "diamond x3", "From your trade with Owner")),
                List.of(), List.of(), T);
        List<String> warned = new ArrayList<>();
        store.check(warned::add, p -> {
            throw new java.io.IOException("no player file");
        });
        assertTrue(warned.get(0).contains("its bags couldn't be read, so look at both before you settle it: /hcm"
                + " trades settle " + id + " release if both hold the trade, void if neither does, or void <player> if"
                + " only that player's does (take what the other gave out of it first, less what /hcm trades show " + id
                + " lists as held for that player, which goes with the trade; what that player gave goes to their"
                + " Mailbox again, so only when it is nowhere else)"), warned.get(0));
        assertEquals(TradeStore.Saved.UNKNOWN, TradeStore.recorded(row(id)));
        assertEquals(null, TradeStore.verb(row(id), TradeStore.Saved.UNKNOWN), "no verb is advised");
        assertEquals(TradeStore.Outcome.RELEASED, store.release(id, marks(0, 0)).outcome());
    }

    // ---- the crash window (red-team findings TCS-2, TCS-3) -------------------------------------------

    /** Stands for the server being killed: nothing after it runs (the swap catches only RuntimeException). */
    private static final class Killed extends Error {
    }

    /**
     * The bags and what is on disk: a save writes a bag with its mark; a quiet failure writes nothing and says
     * nothing (a write the OS took and a power cut lost); a caught one writes nothing and throws (the live save's
     * check of the player's file, TCR-FA-3); and the server can be killed just before or just after a given save. At
     * the next start the disk is all there is.
     */
    private static final class Disk extends Bags {
        final Map<Side, Stack[]> disk = new EnumMap<>(Side.class);
        final Map<Side, TradeStore.Mark> diskSeen = new EnumMap<>(Side.class);
        Side killBefore;
        Side killAfter;
        Side quiet;
        Side caught;
        /** Sides whose every save is caught failing (both players' files can't be written). */
        final Set<Side> failing = java.util.EnumSet.noneOf(Side.class);

        /** The last autosave: the bags as they are now. */
        void autosave() {
            for (Side s : Side.values()) {
                write(s);
            }
        }

        private void write(Side s) {
            disk.put(s, bag.get(s).clone());
            diskSeen.put(s, seen.getOrDefault(s, TradeStore.Mark.NONE));
        }

        @Override
        public void save(Side s) {
            if (s == killBefore) {
                throw new Killed();
            }
            super.save(s);
            if (s == caught || failing.contains(s)) {
                throw new IllegalStateException("the save didn't reach the disk");
            }
            if (s != quiet) {
                write(s);
            }
            if (s == killAfter) {
                throw new Killed();
            }
        }

        /** The marks as the next start reads them, from the players' saved files. */
        TradeStore.Marks marks() {
            return p -> diskSeen.getOrDefault(p.equals(OWNER) ? Side.A : Side.B, TradeStore.Mark.NONE);
        }

        /** Back to what is on disk (both players joined with their saved bags). */
        void rejoin() {
            for (Side s : Side.values()) {
                bag.put(s, disk.get(s).clone());
                seen.put(s, diskSeen.get(s));
            }
        }
    }

    private List<String> mail(UUID player) throws SQLException {
        List<String> out = new ArrayList<>();
        List<DeliveryDao.Delivery> rows = new ArrayList<>(deliveries.activeFor(player));
        rows.sort(java.util.Comparator.comparingLong(DeliveryDao.Delivery::id));
        for (DeliveryDao.Delivery d : rows) {
            out.add(d.itemB64() + " | " + d.label());
        }
        return out;
    }

    /**
     * Red-team findings TCS-2, TCR4-WORTH-1: killed between the two saves. The owner gave a Mini and 32 diamonds, the
     * helper a Mini; 16 diamonds didn't fit the helper's bag. The owner's bag came back from after the trade, the
     * helper's from before it: the helper's Mini is in both bags, the owner's Mini and diamonds in neither. The marks
     * say only the owner's bag was saved, and the start only says so: no bag and no Mailbox row moves on its own. The
     * admin takes the helper's Mini out of the owner's bag, then {@code void Owner} sends the owner's own offer to
     * their Mailbox from the trade row.
     */
    @Test
    void aKillBetweenTheTwoSavesWaitsForTheAdmin() throws Exception {
        Disk disk = new Disk();
        bags = disk;
        UUID ma = UUID.randomUUID();
        UUID mb = UUID.randomUUID();
        minis.mint("creeper", ma, OWNER, "TEST", 1);
        minis.mint("creeper", mb, HELPER, "TEST", 2);
        bags.set(Side.A, 0, mini(ma));
        bags.set(Side.A, 1, plain("Diamond", 32));
        bags.set(Side.A, 2, plain("Bread", 5));
        bags.fill(Side.B, "dirt");
        bags.set(Side.B, 3, mini(mb));
        bags.set(Side.B, 4, plain("Diamond", 48)); // room for 16 more
        disk.autosave();
        Stack[] beforeB = bags.bag.get(Side.B).clone();
        TradeSession<Stack> s = counted(Map.of(0, 1, 1, 32), Map.of(3, 1), 0, 0);
        disk.killBefore = Side.B;
        assertThrows(Killed.class, () -> run(s));
        long id = trades.pending().get(0).id();
        assertEquals(1, held(), "16 didn't fit: held for the helper");
        Stack[] savedA = disk.disk.get(Side.A).clone();

        // the next start: it says what to do, and does nothing
        List<String> warned = new ArrayList<>();
        assertEquals(1, store.check(warned::add, disk.marks()));
        assertTrue(warned.get(0).contains("only Owner's bag was saved with it (what Owner gave is in no bag, and what"
                + " Helper gave is in both): take what Helper gave (Creeper Mini) out of Owner's bag, less what /hcm"
                + " trades show " + id + " lists as held for Owner (that goes with the trade), then /hcm trades settle "
                + id + " void Owner: what Owner gave goes to their Mailbox"), warned.get(0));
        assertEquals(TradeStore.Saved.A, TradeStore.recorded(row(id)));
        assertEquals("bag a saved", row(id).reason());
        assertArrayEquals(savedA, disk.disk.get(Side.A), "[X] no bag changes on its own");
        assertEquals(1, held(), "[X] no Mailbox row changes on its own");
        assertEquals(List.of(), mail(OWNER));
        TradeStore.Settled no = store.release(id, disk.marks());
        assertEquals(TradeStore.Outcome.REFUSED, no.outcome(), "[X] release would leave the helper's Mini twice");
        assertEquals(TradeStore.Outcome.REFUSED, store.voidTrade(id, disk.marks()).outcome(),
                "[X] a plain void would leave the owner's offer in no bag");
        TradeStore.Settled wrong = store.voidTrade(id, disk.marks(), Side.B, false);
        assertEquals(TradeStore.Outcome.REFUSED, wrong.outcome(), "the read says the owner's bag, not the helper's");
        assertTrue(wrong.why().endsWith("/hcm trades settle " + id + " void Helper confirm"), wrong.why());
        assertEquals(TradeDao.PENDING, row(id).outcome());

        // the admin took the helper's Mini out of the owner's bag; the owner's own things go to their Mailbox
        TradeStore.Settled v = store.voidTrade(id, disk.marks(), Side.A, false);
        assertEquals(TradeStore.Outcome.VOIDED, v.outcome(), String.valueOf(v.why()));
        assertEquals(1, v.rows(), "[X] the 16 held are gone");
        assertEquals(2, v.returned());
        assertEquals(List.of("mini:" + ma + " x1 | Back from your trade with Helper",
                "Diamond x32 | Back from your trade with Helper"), mail(OWNER), "[X] the owner's Mini and diamonds");
        assertArrayEquals(beforeB, disk.disk.get(Side.B), "the helper is as before the trade");
        assertEquals(List.of(), mail(HELPER));
        assertEquals(0, held());
        assertEquals(TradeDao.VOID, row(id).outcome());
        assertEquals("settled: voided, what Owner gave to their Mailbox", row(id).reason());
        assertEquals(OWNER, owner(ma), "[X] the owner's Mini is recorded as the owner's again");
        assertEquals(HELPER, owner(mb));
        assertEquals(List.of(), v.notes());
        assertEquals(TradeStore.Outcome.NOT_PENDING, store.voidTrade(id, disk.marks(), Side.A, false).outcome(),
                "once");
        assertEquals(2, mail(OWNER).size());
    }

    /** Red-team finding TCS-3, case 1: killed after both saves, before the flip. The held rows are owed: release. */
    @Test
    void aKillAfterBothSavesReleasesWhatIsOwed() throws Exception {
        Disk disk = new Disk();
        bags = disk;
        bags.fill(Side.B, "dirt");
        bags.set(Side.A, 0, plain("Diamond", 3));
        bags.set(Side.A, 1, plain("Emerald", 5));
        bags.set(Side.B, 0, plain("Gold", 2));
        disk.autosave();
        TradeSession<Stack> s = counted(Map.of(0, 3, 1, 5), Map.of(0, 2), 0, 0);
        disk.killAfter = Side.B;
        assertThrows(Killed.class, () -> run(s));
        long id = trades.pending().get(0).id();
        List<String> warned = new ArrayList<>();
        store.check(warned::add, disk.marks());
        assertTrue(warned.get(0).contains("both bags were saved with it, so its held Mailbox rows are owed: /hcm"
                + " trades settle " + id + " release"), "[X] not void: " + warned.get(0));
        assertEquals(TradeStore.Outcome.REFUSED, store.voidTrade(id, disk.marks()).outcome(),
                "[X] void would lose what is owed");
        assertEquals(TradeStore.Outcome.REFUSED, store.voidTrade(id, disk.marks(), null, true).outcome(),
                "[X] not even with confirm: both bags hold it");
        assertEquals(TradeStore.Outcome.REFUSED, store.voidTrade(id, disk.marks(), Side.A, true).outcome());
        assertEquals(1, held());
        assertEquals(TradeStore.Outcome.RELEASED, store.release(id, disk.marks()).outcome());
        assertEquals(List.of("Emerald x5 | From your trade with Owner"), mail(HELPER));
        assertEquals(TradeDao.DONE, row(id).outcome());
    }

    /** Killed before either save: both bags are from before it, each with its own offer. Void, and nothing is lost. */
    @Test
    void aKillBeforeTheSavesVoids() throws Exception {
        Disk disk = new Disk();
        bags = disk;
        bags.fill(Side.B, "dirt");
        bags.set(Side.A, 0, plain("Diamond", 3));
        bags.set(Side.A, 1, plain("Emerald", 5));
        disk.autosave();
        Stack[] beforeA = bags.bag.get(Side.A).clone();
        Stack[] beforeB = bags.bag.get(Side.B).clone();
        TradeSession<Stack> s = counted(Map.of(0, 3, 1, 5), Map.of(), 0, 0);
        disk.killBefore = Side.A;
        assertThrows(Killed.class, () -> run(s));
        long id = trades.pending().get(0).id();
        store.check(x -> { }, disk.marks());
        assertEquals(TradeStore.Outcome.REFUSED, store.release(id, disk.marks()).outcome(), "[X] copies");
        assertEquals(TradeStore.Outcome.VOIDED, store.voidTrade(id, disk.marks()).outcome());
        assertArrayEquals(beforeA, disk.disk.get(Side.A), "the owner still has their diamonds and emeralds");
        assertArrayEquals(beforeB, disk.disk.get(Side.B));
        assertEquals(List.of(), mail(HELPER), "and the helper gets no copy of them");
    }

    /**
     * Red-team finding TCR4-3: after a stop between a withdraw and its deposit, the row couldn't say whether the payee
     * was paid. The swap writes each side's balance just before the money moved, and the start's WARN names it beside
     * the balance now, so the admin sets right only what moved.
     */
    @Test
    void theWarnNamesMoneyWithTheBalancesBeforeAndNow() throws Exception {
        TradeDao.Trade row = new TradeDao.Trade(0, OWNER, "Owner", HELPER, "Helper", "world", T, T, TradeDao.PENDING,
                "", "", "", "3 Diamond", "", 0, 50, 0);
        long id = store.commit(row, List.of(), List.of(), List.of(), T, new TradeStore.Balances(120.0, 80.0));
        List<String> warned = new ArrayList<>();
        store.check(warned::add, marks(0, 0), true, p -> p.equals(OWNER) ? 170.0 : 80.0);
        assertTrue(warned.get(0).contains("; money isn't paid back by a settle: Owner paid $0.00 (had $120.00 just"
                + " before, has $170.00 now), Helper paid $50.00 (had $80.00 just before, has $80.00 now) - check both"
                + " balances and set right only what moved (check /hcm trades show " + id + ")"), warned.get(0));
        assertArrayEquals(new Double[] {120.0, 80.0}, trades.before(id));

        // through a whole swap: the helper pays $30, and the stop comes before either bag is saved
        Disk disk = new Disk();
        bags = disk;
        bags.set(Side.A, 0, plain("Diamond", 3));
        disk.autosave();
        TradeSession<Stack> s = counted(Map.of(0, 3), Map.of(), 0, 30);
        disk.killBefore = Side.A;
        assertThrows(Killed.class, () -> run(s));
        long two = trades.pending().get(trades.pending().size() - 1).id();
        assertArrayEquals(new Double[] {100.0, 100.0}, trades.before(two), "[X] read just before the withdraw");
        assertEquals(70.0, money.balance.get(Side.B));
        assertEquals(130.0, money.balance.get(Side.A), "the stop came after step 7: the owner was paid");
        warned.clear();
        store.check(warned::add, disk.marks(), true, p -> money.balance.get(p.equals(OWNER) ? Side.A : Side.B));
        assertTrue(warned.get(1).contains("Owner paid $0.00 (had $100.00 just before, has $130.00 now), Helper paid"
                + " $30.00 (had $100.00 just before, has $70.00 now)"), warned.get(1));
        assertArrayEquals(new Double[2], trades.before(store.commit(row, List.of(), List.of(), List.of(), T)),
                "no money, no balances");
    }

    /**
     * Red-team findings AW-1, AW-2: the start read each finished trade and, when a bag read from before it, opened it
     * again with what nobody had collected HELD. A trade goes DONE only once both player files changed after the swap,
     * so that read fired only on a file or folder the admin put back, where it was a false alarm: the owner gave the
     * helper 32 diamonds, 16 went READY to the helper's Mailbox ("in your Mailbox"), and the owner's file was put back
     * from that morning's backup (a lava death, say). The re-hold took the helper's row away, and its advice ("what
     * the owner gave is in both") destroyed the diamonds when the backup never held them. Now nothing reads a finished
     * trade: it stays DONE, its row stays READY, no start says anything, a start weeks later neither (a file reset in
     * between, then a backup put back), and {@code show} reads each bag now for the admin who looks.
     */
    @Test
    void aPlayerFilePutBackNeverReopensAFinishedTrade() throws Exception {
        Disk disk = new Disk();
        bags = disk;
        bags.fill(Side.B, "dirt");
        bags.set(Side.B, 31, plain("Diamond", 48)); // room for 16 of the 32
        disk.autosave(); // the morning's backup: the owner has no diamonds yet
        bags.set(Side.A, 0, plain("Diamond", 32)); // mined since
        Result<Stack> r = run(counted(Map.of(0, 32), Map.of(), 0, 0));
        assertTrue(r.ok());
        assertFalse(r.held());
        assertEquals(TradeDao.DONE, row(r.id()).outcome());
        assertEquals(List.of("Diamond x16 | From your trade with Owner"), mail(HELPER), "READY: the helper was told");

        // the owner's file is put back from the morning (no mark, the backup's file time)
        disk.diskSeen.put(Side.A, TradeStore.Mark.none(T - 3_600_000L));
        List<String> warned = new ArrayList<>();
        assertEquals(0, store.check(warned::add, disk.marks()), "nothing waits");
        assertEquals(List.of(), warned, "[X] no start reads a finished trade");
        assertEquals(TradeDao.DONE, row(r.id()).outcome(), "[X] not reopened");
        assertEquals(List.of("Diamond x16 | From your trade with Owner"), mail(HELPER), "[X] the helper's row stays");
        assertEquals(0, held());
        assertEquals(Boolean.FALSE, store.holdsNow(row(r.id()), disk.marks(), Side.A),
                "show reads the owner's bag as from before it, for the admin who looks");
        assertEquals(Boolean.TRUE, store.holdsNow(row(r.id()), disk.marks(), Side.B));

        // weeks later: the helper's file was reset, then put back from before the trade; still nothing
        disk.diskSeen.put(Side.B, TradeStore.Mark.none(T + 86_400_000L));
        assertEquals(0, store.check(warned::add, disk.marks()));
        disk.diskSeen.put(Side.B, TradeStore.Mark.none(T - 1));
        assertEquals(0, store.check(warned::add, disk.marks()));
        assertEquals(0, store.check(warned::add, disk.marks(), false, p -> null), "a plugin reload neither");
        assertEquals(List.of(), warned);
        assertEquals(TradeDao.DONE, row(r.id()).outcome());
        assertEquals(1, mail(HELPER).size());

        // a save the swap saw go through that a power cut lost (the file was written, then lost) is the same: the
        // bag is like any other hand-over's, and nothing reopens
        bags.set(Side.A, 1, plain("Emerald", 5));
        disk.quiet = Side.B;
        Result<Stack> two = runAt(T + 60_000, counted(Map.of(1, 5), Map.of(), 0, 0), () -> null, Claim.NONE,
                Claim.NONE);
        assertTrue(two.ok());
        assertEquals(0, store.check(warned::add, disk.marks()));
        assertEquals(TradeDao.DONE, row(two.id()).outcome());
        assertEquals(List.of(), warned);

        String src = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/dierks/homecraft/trade/TradeService.java"));
        String start = src.substring(src.indexOf("public void start()"));
        assertTrue(start.indexOf("store.check(") < start.indexOf("dao.prune("), "[X] the check reads before the prune");
        assertTrue(start.contains("boolean boot = System.getProperty(BOOT_CHECKED) == null;"),
                "a plugin reload is told from a server start by a JVM-wide flag");
    }

    /**
     * Red-team findings R4FA-1, TCR4-RESET-2: a player file reset (deleted, then written again when they rejoined) or
     * a fresh world has no mark. For a trade that didn't finish, a file with no mark written after the trade swapped
     * can't say; one written before it is really from before it (a first trade, killed before its save); live data
     * with no mark can't say.
     */
    @Test
    void aResetPlayerFileCantSay() throws Exception {
        long id = store.commit(new TradeDao.Trade(0, OWNER, "Owner", HELPER, "Helper", "world", T, T,
                TradeDao.PENDING, "", "", "", "3 Diamond", "", 0, 0, 0), List.of(), List.of(), List.of(), T);
        assertEquals(TradeStore.Saved.UNKNOWN, store.read(row(id), p -> TradeStore.Mark.none(T + 5)),
                "[X] a fresh world: both files written since, no mark");
        assertEquals(TradeStore.Saved.UNKNOWN, store.read(row(id),
                p -> p.equals(OWNER) ? mark(id) : TradeStore.Mark.none(T + 86_400_000L)), "[X] a reset file");
        assertEquals(TradeStore.Saved.A, store.read(row(id),
                p -> p.equals(OWNER) ? mark(id) : TradeStore.Mark.none(T - 1)), "saved before it: from before it");
        TradeStore.Mark live = TradeStore.mark(null, null, () -> Long.MAX_VALUE);
        assertEquals(TradeStore.Saved.UNKNOWN, store.read(row(id), p -> p.equals(OWNER) ? mark(id) : live),
                "[X] an online player's live data with no mark can't say");
    }

    /**
     * Red-team finding TCR-FA-1: a start against a world folder with no player files (a restore half done, a test
     * level-name) read every bag as from before every trade, so it reopened a trade that finished normally, held its
     * rows and wrote that answer for good. An offline player with no saved data can't be read now: it stays DONE.
     */
    @Test
    void aMissingPlayerFileNeverReopensADoneTrade() throws Exception {
        bags.fill(Side.B, "dirt");
        bags.set(Side.A, 0, plain("Diamond", 3));
        Result<Stack> r = run(counted(Map.of(0, 3), Map.of(), 0, 0));
        assertEquals(TradeDao.DONE, row(r.id()).outcome());
        assertEquals(1, mail(HELPER).size(), "the helper's overflow is READY");
        TradeStore.Marks noFiles = p -> TradeStore.mark(null, null, () -> null);
        assertThrows(IllegalStateException.class, () -> noFiles.seen(OWNER), "[X] no saved data can't be read");
        assertEquals(TradeStore.Mark.none(7), TradeStore.mark(null, null, () -> 7L), "saved data without a mark");
        assertEquals(new TradeStore.Mark(5, 0), TradeStore.mark(5L, null, () -> null));
        List<String> warned = new ArrayList<>();
        assertEquals(0, store.check(warned::add, noFiles));
        assertEquals(TradeDao.DONE, row(r.id()).outcome(), "[X] not reopened");
        assertEquals(1, mail(HELPER).size(), "[X] its row stays READY");
        TradeStore.Marks oneFile = p -> p.equals(OWNER) ? mark(r.id()) : TradeStore.mark(null, null, () -> null);
        assertEquals(0, store.check(warned::add, oneFile), "[X] one file missing: nothing either");
        assertEquals(TradeDao.DONE, row(r.id()).outcome());
        assertEquals(List.of(), warned);
    }

    /**
     * Red-team findings TCR-FA-1, R4FA-3: a row an earlier start wrote is read again at each start, but exactly (only
     * its own mark holds it). Both bags reading differently (a world folder put back) replace it; a mark a later trade
     * raised never does. One bag reading against it while the other can't say (one player traded again since) makes
     * it unread, so either verb is open to the admin who looks, rather than a wrong answer standing for good.
     */
    @Test
    void aLaterStartReadsTheRowAgainExactly() throws Exception {
        long id = store.commit(new TradeDao.Trade(0, OWNER, "Owner", HELPER, "Helper", "world", T, T,
                TradeDao.PENDING, "", "", "", "3 Diamond", "", 0, 0, 1), List.of(new TradeStore.Held(HELPER,
                "diamond x3", "From your trade with Owner")), List.of(), List.of(), T);
        List<String> warned = new ArrayList<>();
        store.check(warned::add, p -> p.equals(OWNER) ? mark(id) : TradeStore.mark(null, null, () -> null));
        assertEquals(TradeStore.Saved.UNKNOWN, TradeStore.recorded(row(id)), "the helper's file is missing");

        warned.clear();
        store.check(warned::add, marks(id, id)); // the folder is back, and both bags hold it
        assertEquals(TradeStore.Saved.BOTH, TradeStore.recorded(row(id)));
        assertTrue(warned.get(0).contains("both bags were saved with it, so its held Mailbox rows are owed"),
                warned.get(0));
        assertTrue(warned.get(0).contains(" (an earlier start read its bags as \"bags unread\", and they read"
                + " differently now: a world folder put back?)"), warned.get(0));
        assertEquals(TradeStore.Outcome.RELEASED, store.release(id, marks(id, id)).outcome());

        // a row written NEITHER (both bags came back from before it): a later trade's mark never makes it BOTH...
        long two = store.commit(new TradeDao.Trade(0, OWNER, "Owner", HELPER, "Helper", "world", T, T,
                TradeDao.PENDING, "", "", "", "", "", 0, 0, 0), List.of(), List.of(), List.of(), T);
        store.check(x -> { }, marks(0, 0));
        assertEquals(TradeStore.Saved.NEITHER, TradeStore.recorded(row(two)));
        store.check(x -> { }, marks(two + 5, two + 5));
        assertEquals(TradeStore.Saved.NEITHER, TradeStore.recorded(row(two)), "[X] not BOTH over a later trade");
        // ...but its own mark in both bags does (a newer copy of the world folder put back)
        store.check(x -> { }, marks(two, two));
        assertEquals(TradeStore.Saved.BOTH, TradeStore.recorded(row(two)));

        // R4FA-3: written NEITHER from an older folder; the right one is back, but the owner traded again since
        long three = store.commit(new TradeDao.Trade(0, OWNER, "Owner", HELPER, "Helper", "world", T, T,
                TradeDao.PENDING, "", "", "", "", "Mini", 0, 0, 1), List.of(new TradeStore.Held(HELPER, "mini x1",
                "From your trade with Owner")), List.of(), List.of(), T);
        store.check(x -> { }, marks(0, 0));
        assertEquals(TradeStore.Saved.NEITHER, TradeStore.recorded(row(three)));
        warned.clear();
        store.check(warned::add, marks(three + 1, three));
        assertEquals(TradeStore.Saved.UNKNOWN, TradeStore.recorded(row(three)),
                "[X] the helper's bag holds it: \"not saved\" no longer stands");
        String w = warned.get(warned.size() - 1);
        assertTrue(w.contains(" (an earlier start read its bags as \"bags not saved\", and one reads otherwise now"
                + " while the other can't say, so look at both)"), w);
        assertEquals(TradeStore.Outcome.RELEASED, store.release(three, marks(three + 1, three)).outcome(),
                "[X] the admin who looked can release");
        assertEquals(List.of("diamond x3 | From your trade with Owner", "mini x1 | From your trade with Owner"),
                mail(HELPER));
    }

    /**
     * Red-team findings TCR4-OVERRIDE-3, R4FA-3: a start's read the admin knows is wrong (a world folder or a player
     * file from an older copy, read once) left release refused for good, and void deleted what was owed. {@code
     * confirm} goes against the read, and says so on the row; a void never goes against both bags holding it.
     */
    @Test
    void aWrongReadIsOverruledWithConfirm() throws Exception {
        Disk disk = new Disk();
        bags = disk;
        bags.fill(Side.B, "dirt");
        bags.set(Side.A, 0, plain("Diamond", 3));
        disk.autosave();
        disk.killAfter = Side.B; // both bags saved, the stop came before the flip
        assertThrows(Killed.class, () -> run(counted(Map.of(0, 3), Map.of(), 0, 0)));
        TradeDao.Trade r = trades.pending().get(0);
        assertEquals(List.of(), mail(HELPER), "held");
        store.check(x -> { }, marks(0, 0)); // both files read from an older copy of the world folder
        assertEquals(TradeStore.Saved.NEITHER, TradeStore.recorded(row(r.id())));
        TradeStore.Settled no = store.release(r.id(), marks(0, 0));
        assertEquals(TradeStore.Outcome.REFUSED, no.outcome());
        assertTrue(no.why().endsWith("/hcm trades settle " + r.id() + " release confirm"), no.why());
        TradeStore.Settled yes = store.release(r.id(), marks(0, 0), true);
        assertEquals(TradeStore.Outcome.RELEASED, yes.outcome(), "[X] the admin looked: both bags hold it");
        assertEquals(1, yes.rows());
        assertEquals(1, mail(HELPER).size(), "[X] her row is READY");
        assertEquals(TradeDao.DONE, row(r.id()).outcome());
        assertEquals("settled: released, confirmed over \"bags not saved\"", row(r.id()).reason());
        assertEquals(0, store.check(x -> { }, marks(0, 0)), "settled: no start reads it again");
    }

    /**
     * Red-team findings TCR-FA-4, TCR4-2, R4FA-6: a restored database starts its trade ids again, so a bag can carry
     * a mark with this trade's id, or a higher one, from another copy of the database: it can't be read. When only
     * one bag really holds the trade, the admin who looked voids for that player whatever the read, and their own
     * offer goes to their Mailbox from the row; no one needs to be online.
     */
    @Test
    void aMarkFromARestoredDatabaseIsNotThisTrade() throws Exception {
        long id = store.commit(new TradeDao.Trade(0, OWNER, "Owner", HELPER, "Helper", "world", T, T,
                TradeDao.PENDING, "", "", "", "", "", 0, 0, 0), List.of(), List.of(), List.of(), T);
        long yesterday = T - 86_400_000L;
        assertEquals(TradeStore.Saved.UNKNOWN, store.read(row(id),
                p -> p.equals(OWNER) ? mark(id) : new TradeStore.Mark(id + 12, yesterday)),
                "[X] not BOTH: this database has no trade #" + (id + 12));
        assertEquals(TradeStore.Saved.UNKNOWN, store.read(row(id),
                p -> p.equals(OWNER) ? mark(id) : new TradeStore.Mark(id, yesterday)), "[X] its id, another time");
        assertEquals(TradeStore.Saved.A, store.read(row(id), p -> p.equals(OWNER) ? mark(id) : mark(0)));
        long later = store.commit(new TradeDao.Trade(0, OWNER, "Owner", HELPER, "Helper", "world", T + 5_000,
                T + 5_000, TradeDao.PENDING, "", "", "", "", "", 0, 0, 0), List.of(), List.of(), List.of(), T + 5_000);
        assertEquals(TradeStore.Saved.BOTH, store.read(row(id), p -> new TradeStore.Mark(later, T + 5_000)),
                "a later trade of this database: both bags hold it");

        // a whole swap: the owner gives 10 diamonds for the helper's Mini; the helper's file has a lost day's mark
        Disk disk = new Disk();
        bags = disk;
        UUID m = UUID.randomUUID();
        minis.mint("creeper", m, HELPER, "TEST", 1);
        bags.set(Side.A, 0, plain("Diamond", 10));
        bags.set(Side.B, 0, mini(m));
        disk.autosave();
        disk.diskSeen.put(Side.B, new TradeStore.Mark(later + 40, yesterday));
        TradeSession<Stack> s = counted(Map.of(0, 10), Map.of(0, 1), 0, 0);
        disk.killBefore = Side.B;
        assertThrows(Killed.class, () -> run(s));
        long three = trades.pending().get(trades.pending().size() - 1).id();
        List<String> warned = new ArrayList<>();
        store.check(warned::add, disk.marks());
        assertEquals(TradeStore.Saved.UNKNOWN, TradeStore.recorded(row(three)), "the helper's bag can't be read");
        // the admin looks: only the owner's bag holds it. They take the Mini out of it, then:
        TradeStore.Settled v = store.voidTrade(three, disk.marks(), Side.A, false);
        assertEquals(TradeStore.Outcome.VOIDED, v.outcome(), String.valueOf(v.why()));
        assertEquals(List.of("Diamond x10 | Back from your trade with Helper"), mail(OWNER),
                "[X] the owner's diamonds aren't lost");
        assertEquals(HELPER, owner(m), "the Mini is the helper's again");
        assertEquals("settled: voided, what Owner gave to their Mailbox", row(three).reason());
    }

    /**
     * Red-team findings MONEY-REFUND, NT-1: a deposit that failed was paid back to its payer, but the row and both
     * players' lines still named the payment. The row says the payer paid nothing, the result says what really
     * moved, and the payer is told the money came back (or, when that failed too, to ask an admin).
     */
    @Test
    void aPaymentGivenBackIsNotNamedAsPaid() throws Exception {
        try (var st = conn.createStatement()) {
            st.execute("CREATE TRIGGER no_flip BEFORE UPDATE OF outcome ON trades WHEN NEW.outcome='DONE'"
                    + " BEGIN SELECT RAISE(ABORT, 'locked'); END");
        }
        bags.set(Side.A, 0, plain("Diamond", 3));
        money.depositFails = Side.A; // the owner can't be paid
        Result<Stack> r = run(counted(Map.of(0, 3), Map.of(), 0, 50));
        assertTrue(r.ok());
        assertEquals(100.0, money.balance.get(Side.B), "the helper's $50 went back");
        assertTrue(log.severe.stream().anyMatch(l -> l.contains("a deposit failed, so $50.0 went back to Helper")),
                log.severe.toString());
        assertEquals(0.0, row(r.id()).bMoney(), "[X] the row says the helper paid nothing");
        assertEquals(Map.of(), r.paid(), "[X] nothing reached the owner");
        assertEquals(Map.of(), r.stuck());
        java.util.function.DoubleFunction<String> fmt = v -> String.format(java.util.Locale.ROOT, "$%.2f", v);
        assertEquals("&eThe $50.00 didn't reach Owner, so it went back to you.",
                TradeService.moneyBackLine(50, r.paid().getOrDefault(Side.B, 0.0), 0, "Owner", r.id(), fmt));
        assertEquals("", TradeService.moneyBackLine(50, 50, 0, "Owner", r.id(), fmt), "it went through");
        assertEquals("", TradeService.moneyBackLine(0, 0, 0, "Owner", r.id(), fmt), "nothing paid");
        List<String> warned = new ArrayList<>();
        store.check(warned::add, live());
        assertFalse(warned.get(0).contains("money isn't paid back"), "[X] no money to square: " + warned.get(0));

        // the refund fails too: the money is stuck, and the payer is sent to an admin with the trade's number
        money.alsoFails = Side.B;
        bags.set(Side.A, 1, plain("Emerald", 2));
        Result<Stack> two = run(counted(Map.of(1, 2), Map.of(), 0, 20));
        assertEquals(Map.of(Side.B, 20.0), two.stuck());
        assertEquals("&cThe $20.00 you paid didn't reach Owner and couldn't be paid back to you - ask an admin (trade #"
                + two.id() + ").", TradeService.moneyBackLine(20, 0, two.stuck().get(Side.B), "Owner", two.id(), fmt));
        assertEquals(20.0, row(two.id()).bMoney(), "the row still names it, for the admin");

        // and a payment that went through is in both lines
        money.depositFails = null;
        money.alsoFails = null;
        bags.set(Side.A, 2, plain("Gold", 2));
        Result<Stack> three = run(counted(Map.of(2, 2), Map.of(), 0, 5));
        assertEquals(Map.of(Side.B, 5.0), three.paid());
    }

    /**
     * Red-team finding TCR4-5: a side past 64 KB of Base64 was stored as '', so a stop between the two saves left no
     * verb that gave the saved player's offer back. It is kept whole while a start or a settle may need it, and
     * trimmed (the summary says so) once the trade is settled or finishes.
     */
    @Test
    void aBigSideIsKeptWholeUntilNothingNeedsIt() throws Exception {
        Disk disk = new Disk();
        bags = disk;
        Stack books = new Stack("books:" + "x".repeat(TradeStore.ITEMS_LIMIT + 10), 1, 1, 1,
                ItemFacts.plain("Shulker box", "SHULKER_BOX", 1));
        bags.set(Side.A, 0, books);
        bags.set(Side.B, 0, plain("Emerald", 2));
        disk.autosave();
        TradeSession<Stack> s = counted(Map.of(0, 1), Map.of(0, 2), 0, 0);
        disk.killBefore = Side.B;
        assertThrows(Killed.class, () -> run(s));
        long id = trades.pending().get(0).id();
        assertTrue(row(id).aItems().length() > TradeStore.ITEMS_LIMIT, "[X] kept whole while PENDING");
        assertEquals("Shulker box", row(id).aSummary());
        List<String> warned = new ArrayList<>();
        store.check(warned::add, disk.marks());
        assertFalse(warned.get(0).contains("too big"), warned.get(0));
        TradeStore.Settled v = store.voidTrade(id, disk.marks(), Side.A, false);
        assertEquals(1, v.returned());
        assertTrue(mail(OWNER).get(0).startsWith("books:xxx"), "[X] the owner's books come back");
        assertEquals("", row(id).aItems(), "trimmed once settled");
        assertEquals("Shulker box (too big to store)", row(id).aSummary());
        assertEquals("Emerald x2", row(id).bItems(), "a small side stays");

        // a trade that finishes: nothing reads it again (red-team findings AW-1, AW-2), so it is trimmed at once
        disk.rejoin();
        bags.set(Side.A, 1, books);
        disk.killBefore = null;
        Result<Stack> r = runAt(T + 60_000, counted(Map.of(1, 1), Map.of(), 0, 0), () -> null, Claim.NONE,
                Claim.NONE);
        assertTrue(r.ok());
        assertEquals(TradeDao.DONE, row(r.id()).outcome());
        assertEquals("", row(r.id()).aItems(), "done: trimmed");
        assertEquals("Shulker box (too big to store)", row(r.id()).aSummary());
    }

    /**
     * A side an earlier build trimmed (past 64 KB it kept ''): {@code void <player>} can't hand it back, so the advice
     * and the void say to give it back by hand.
     */
    @Test
    void aSideAnEarlierBuildTrimmedIsGivenBackByHand() throws Exception {
        TradeDao.Trade row = new TradeDao.Trade(0, OWNER, "Owner", HELPER, "Helper", "world", T, T, TradeDao.PENDING,
                "", "", "Iron x1", "Books (too big to store)", "Iron", 0, 0, 1);
        long id = store.commit(row, List.of(new TradeStore.Held(HELPER, "Books x1", "From your trade with Owner")),
                List.of(), List.of(), T);
        assertFalse(TradeStore.kept(row(id), Side.A));
        List<String> warned = new ArrayList<>();
        store.check(warned::add, marks(id, 0));
        assertEquals(TradeStore.Saved.A, TradeStore.recorded(row(id)));
        assertTrue(warned.get(0).contains("void Owner: what Owner gave goes to their Mailbox, except what was too big"
                + " to keep in the log: give that back by hand"), warned.get(0));
        TradeStore.Settled v = store.voidTrade(id, marks(id, 0), Side.A, false);
        assertEquals(TradeStore.Outcome.VOIDED, v.outcome());
        assertEquals(0, v.returned());
        assertEquals(List.of("what Owner gave (Books (too big to store)) was too big to keep in the log, so it went to"
                + " no Mailbox: give it back by hand"), v.notes());
        assertEquals(0, count("SELECT COUNT(*) FROM deliveries"));
    }

    /**
     * Red-team finding D4: a one-side void puts the Mini owners and the collector claim the swap moved back (the
     * giver kept a cap with no claim, the receiver a claim with no cap), and the trade's price-0 sale rows.
     */
    @Test
    void aOneSideVoidPutsTheMiniOwnersAndTheClaimBack() throws Exception {
        Disk disk = new Disk();
        bags = disk;
        UUID ma = UUID.randomUUID();
        UUID mb = UUID.randomUUID();
        minis.mint("creeper", ma, OWNER, "TEST", 1);
        minis.mint("creeper", mb, HELPER, "TEST", 2);
        prizes.claimOnce(OWNER, CAP);
        bags.set(Side.A, 0, mini(ma));
        bags.set(Side.A, 1, cap());
        bags.set(Side.B, 0, mini(mb));
        bags.set(Side.B, 1, plain("Diamond", 32));
        disk.autosave();
        TradeSession<Stack> s = counted(Map.of(0, 1, 1, 1), Map.of(0, 1, 1, 32), 0, 0);
        disk.killBefore = Side.B;
        assertThrows(Killed.class, () -> run(s, () -> null, new Claim(true, false), Claim.NONE));
        long id = trades.pending().get(0).id();
        assertEquals(HELPER, owner(ma), "the swap moved them");
        assertEquals(1, prizes.purchases(HELPER, CAP, "life"));
        store.check(x -> { }, disk.marks());
        assertEquals(TradeStore.Saved.A, TradeStore.recorded(row(id)));

        TradeStore.Settled v = store.voidTrade(id, disk.marks(), Side.A, false);
        assertEquals(TradeStore.Outcome.VOIDED, v.outcome());
        assertEquals(OWNER, owner(ma), "[X] the owner's Mini is the owner's again");
        assertEquals(HELPER, owner(mb), "[X] and the helper's the helper's");
        assertEquals(List.of(), minis.salesForUid(ma.toString()), "the trade's sale rows are gone");
        assertEquals(List.of(), minis.salesForUid(mb.toString()));
        assertEquals(1, prizes.purchases(OWNER, CAP, "life"), "[X] the claim is back with the cap");
        assertEquals(0, prizes.purchases(HELPER, CAP, "life"), "[X] the helper keeps no claim without a cap");
        assertEquals(List.of(), v.notes(), "everything was as the swap left it");
    }

    /**
     * Red-team finding D4: a void (neither bag was saved) puts the owners and claims back too, but only what is still
     * as the swap left it: a Mini whose owner moved on since keeps it, and the admin is told.
     */
    @Test
    void aVoidPutsBackWhatHasNotMovedOn() throws Exception {
        Disk disk = new Disk();
        bags = disk;
        UUID ma = UUID.randomUUID();
        UUID m2 = UUID.randomUUID();
        minis.mint("creeper", ma, OWNER, "TEST", 1);
        minis.mint("creeper", m2, OWNER, "TEST", 2);
        prizes.claimOnce(OWNER, CAP);
        bags.set(Side.A, 0, mini(ma));
        bags.set(Side.A, 1, mini(m2));
        bags.set(Side.A, 2, cap());
        disk.autosave();
        TradeSession<Stack> s = counted(Map.of(0, 1, 1, 1, 2, 1), Map.of(), 0, 0);
        disk.killBefore = Side.A;
        assertThrows(Killed.class, () -> run(s, () -> null, new Claim(true, false), Claim.NONE));
        long id = trades.pending().get(0).id();
        store.check(x -> { }, disk.marks());
        minis.updateOwner(m2.toString(), EARLIER); // that copy's record moved on since
        TradeStore.Settled v = store.voidTrade(id, disk.marks());
        assertEquals(TradeStore.Outcome.VOIDED, v.outcome());
        assertEquals(OWNER, owner(ma), "[X] back with the owner, who holds it");
        assertEquals(EARLIER, owner(m2), "moved on since: left as it is");
        assertEquals(List.of("Mini " + m2 + " isn't as the trade left it (Helper's, with no sale since), so its owner"
                + " was left"), v.notes());
        assertEquals(List.of(), minis.salesForUid(ma.toString()));
        assertEquals(1, minis.salesForUid(m2.toString()).size(), "a copy that moved on keeps its history");
        assertEquals(1, prizes.purchases(OWNER, CAP, "life"), "[X] the claim is back with the cap");
        assertEquals(0, prizes.purchases(HELPER, CAP, "life"));
    }

    /**
     * Red-team findings R4FA-2, TCR4-1: a stop left trade #1 "bags not saved" with the Mini's owner and the cap's
     * claim already moved to the helper. They did it again as #2, the same hand-over: #2 left the Mini's owner and the
     * claim exactly where #1 put them (the cap went home, moving no claim). Voiding #1 then took both from the helper,
     * who holds them. A void now leaves what a later trade carried, and names it.
     */
    @Test
    void aVoidLeavesWhatALaterTradeCarried() throws Exception {
        Disk disk = new Disk();
        bags = disk;
        UUID x = UUID.randomUUID();
        minis.mint("creeper", x, OWNER, "TEST", 1);
        prizes.claimOnce(OWNER, CAP);
        bags.set(Side.A, 0, mini(x));
        bags.set(Side.A, 1, cap());
        disk.autosave();
        disk.killBefore = Side.A;
        assertThrows(Killed.class, () -> run(counted(Map.of(0, 1, 1, 1), Map.of(), 0, 0), () -> null,
                new Claim(true, false), Claim.NONE));
        long one = trades.pending().get(0).id();
        store.check(x2 -> { }, disk.marks());
        assertEquals(TradeStore.Saved.NEITHER, TradeStore.recorded(row(one)));

        // both join with their bags from before #1, and do it again: the helper already holds the cap's claim
        disk.rejoin();
        disk.killBefore = null;
        Result<Stack> two = runAt(T + 60_000, counted(Map.of(0, 1, 1, 1), Map.of(), 0, 0), () -> null,
                new Claim(false, true), Claim.NONE);
        assertTrue(two.ok(), String.valueOf(two.told()));
        assertEquals(HELPER, owner(x));
        assertEquals(1, prizes.purchases(HELPER, CAP, "life"));

        // SETTLE-PRUNE-3: the redo is past log_keep_days, but a row after a waiting trade is never pruned, so the
        // void still sees that it carried the claim (a claim has no sale row to say so)
        assertEquals(0, trades.prune(Long.MAX_VALUE), "[X] the redo stays while #" + one + " waits");
        TradeStore.Settled v = store.voidTrade(one, disk.marks());
        assertEquals(TradeStore.Outcome.VOIDED, v.outcome());
        assertEquals(HELPER, owner(x), "[X] the helper holds the Mini: its owner stays hers");
        assertEquals(1, prizes.purchases(HELPER, CAP, "life"), "[X] and the cap's claim");
        assertEquals(0, prizes.purchases(OWNER, CAP, "life"));
        assertEquals(List.of("Mini " + x + " went in trade #" + two.id() + " since, so its owner was left",
                "the claim on " + CAP + " went in trade #" + two.id() + " since, so it was left"), v.notes(),
                "[X] each one left is named");
        assertEquals(2, minis.salesForUid(x.toString()).size(), "both trades' history stays");

        // a later sale of the copy outside a trade (newest sale row not the trade's) leaves it too
        UUID y = UUID.randomUUID();
        minis.mint("creeper", y, OWNER, "TEST", 2);
        TradeDao.Trade row = new TradeDao.Trade(0, OWNER, "Owner", HELPER, "Helper", "world", T, T, TradeDao.PENDING,
                "", "", "", "Creeper Mini", "", 0, 0, 0);
        long three = store.commit(row, List.of(), List.of(new MiniService.MiniMove(y.toString(), "creeper", HELPER,
                MiniService.VENUE_GIFT)), List.of(), T);
        minis.recordSale(y.toString(), "creeper", 25, HELPER, HELPER, "VENDING", T + 120_000);
        assertFalse(MiniService.tradeBack(minis, y.toString(), OWNER, HELPER, T), "[X] a sale since: left");
        assertEquals(HELPER, owner(y));
        assertEquals(TradeStore.Outcome.VOIDED, store.voidTrade(three, marks(0, 0)).outcome());
        assertEquals(HELPER, owner(y));
    }

    /**
     * Red-team findings AW-3, SETTLE-ORDER-1: the owner gave the helper a Mini and a collector cap, and the helper gave
     * them back, while neither player's file could be written; at the restart both bags are from before the first
     * trade, so both trades read "bags not saved" and the WARNs say void, oldest first. Voiding the older first left
     * the Mini's owner and the claim as the newer trade left them, and the newer one's void then put them back to the
     * helper, who doesn't hold them, with no note. The older void now waits for the newer one; voided newest first,
     * each puts back what it moved.
     */
    @Test
    void twoWaitingTradesOfOneMiniAreVoidedNewestFirst() throws Exception {
        Disk disk = new Disk();
        bags = disk;
        UUID x = UUID.randomUUID();
        minis.mint("creeper", x, OWNER, "TEST", 1);
        prizes.claimOnce(OWNER, CAP);
        bags.set(Side.A, 0, mini(x));
        bags.set(Side.A, 1, cap());
        disk.autosave();
        disk.failing.add(Side.A);
        disk.failing.add(Side.B);
        Result<Stack> one = run(counted(Map.of(0, 1, 1, 1), Map.of(), 0, 0), () -> null, new Claim(true, false),
                Claim.NONE);
        assertTrue(one.ok() && one.held());
        assertEquals(HELPER, owner(x));
        Result<Stack> two = runAt(T + 60_000, counted(Map.of(), Map.of(0, 1, 1, 1), 0, 0), () -> null, Claim.NONE,
                new Claim(true, false)); // and back again
        assertTrue(two.ok() && two.held(), String.valueOf(two.told()));
        assertEquals(OWNER, owner(x));
        assertEquals(1, prizes.purchases(OWNER, CAP, "life"));

        List<String> warned = new ArrayList<>();
        assertEquals(2, store.check(warned::add, disk.marks()));
        assertEquals(TradeStore.Saved.NEITHER, TradeStore.recorded(row(one.id())));
        assertEquals(TradeStore.Saved.NEITHER, TradeStore.recorded(row(two.id())));
        assertTrue(warned.get(0).startsWith("Trade #" + one.id()), "the WARNs come oldest first");

        TradeStore.Settled wait = store.voidTrade(one.id(), disk.marks());
        assertEquals(TradeStore.Outcome.REFUSED, wait.outcome(), "[X] the older one waits");
        assertEquals("trade #" + two.id() + " moved Mini " + x + " after this one and is still waiting: settle #"
                + two.id() + " first (the newest first, so each void puts back what it moved)", wait.why());
        assertEquals(TradeStore.Outcome.REFUSED, store.voidTrade(one.id(), disk.marks(), null, true).outcome(),
                "[X] confirm can't skip the order");
        assertEquals(TradeDao.PENDING, row(one.id()).outcome());
        assertEquals(OWNER, owner(x), "nothing moved");

        assertEquals(TradeStore.Outcome.VOIDED, store.voidTrade(two.id(), disk.marks()).outcome());
        TradeStore.Settled v = store.voidTrade(one.id(), disk.marks());
        assertEquals(TradeStore.Outcome.VOIDED, v.outcome(), String.valueOf(v.why()));
        assertEquals(List.of(), v.notes());
        assertEquals(OWNER, owner(x), "[X] the owner holds the Mini, and it is recorded as theirs");
        assertEquals(1, prizes.purchases(OWNER, CAP, "life"), "[X] and the cap's claim is theirs");
        assertEquals(0, prizes.purchases(HELPER, CAP, "life"));
        assertEquals(List.of(), minis.salesForUid(x.toString()), "both trades' sale rows are gone");
    }

    /**
     * Red-team finding SETTLE-REHELD-COPY-2: the helper gave the owner a sword, and the owner gave it back, while the
     * helper's file couldn't be written. At the restart the helper's bag is from before both (it holds the sword),
     * the owner's from after both (it doesn't). Each trade read "only the owner's bag was saved", and the second's
     * advice ("void Owner: what Owner gave goes to their Mailbox") handed the sword out again: a copy. One bag alone
     * read after the players played on is "look first" now, never read again, and names no verb; the admin who looks
     * sees that the sword is where it should be and releases both.
     */
    @Test
    void oneBagReadAfterPlayWentOnIsLookFirst() throws Exception {
        Disk disk = new Disk();
        bags = disk;
        bags.set(Side.B, 0, plain("Sword", 1));
        disk.autosave();
        disk.caught = Side.B;
        Result<Stack> one = run(counted(Map.of(), Map.of(0, 1), 0, 0));
        assertTrue(one.ok() && one.held());
        Result<Stack> two = runAt(T + 60_000, counted(Map.of(0, 1), Map.of(), 0, 0), () -> null, Claim.NONE,
                Claim.NONE);
        assertTrue(two.ok() && two.held());
        assertEquals("bag b unsaved", row(two.id()).reason());

        List<String> warned = new ArrayList<>();
        assertEquals(2, store.check(warned::add, disk.marks()));
        for (long id : List.of(one.id(), two.id())) {
            assertEquals(TradeStore.Saved.UNKNOWN, TradeStore.recorded(row(id)), "[X] not \"bag a saved\"");
            assertEquals("bags unread, played on", row(id).reason());
            assertEquals(null, TradeStore.verb(row(id), TradeStore.Saved.UNKNOWN));
        }
        for (String w : warned) {
            assertTrue(w.contains("only one bag reads as saved with it, but they played on after the swap"), w);
            assertFalse(w.contains("void Owner:"), "[X] no void named: " + w);
            // red-team finding R5-PLAYEDON-HELD-1: void <player> here takes out only what isn't held, as for A/B
            assertTrue(w.contains("take what the other gave out of it first, less what /hcm trades show "), w);
            assertTrue(w.contains(" lists as held for that player, which goes with the trade"), "[X] " + w);
        }
        warned.clear();
        store.check(warned::add, disk.marks());
        assertEquals("bags unread, played on", row(two.id()).reason(), "never read again");

        // a row that went PENDING since the last start, read now: a saved bag whose mark is a later trade's played on
        TradeDao.Trade fresh = new TradeDao.Trade(0, OWNER, "Owner", HELPER, "Helper", "world", T, T,
                TradeDao.PENDING, "", "", "", "", "", 0, 0, 0);
        long three = store.commit(fresh, List.of(), List.of(), List.of(), T);
        long four = store.commit(fresh.withId(0), List.of(), List.of(), List.of(), T + 5);
        trades.finish(four, TradeDao.PENDING, TradeDao.DONE, "");
        TradeStore.Marks later = p -> p.equals(OWNER) ? new TradeStore.Mark(four, T) : TradeStore.Mark.NONE;
        assertEquals(TradeStore.Saved.A, store.read(row(three), later), "the read alone says only the owner's");
        assertEquals(TradeStore.Saved.UNKNOWN, store.state(row(three), later), "[X] but the owner traded since");
        assertEquals(TradeStore.Saved.A, store.state(row(three), marks(three, 0)), "its own mark: exact");

        // the admin looks: the sword is in the helper's bag, as after both trades; release both, nothing is made
        assertEquals(TradeStore.Outcome.RELEASED, store.release(two.id(), disk.marks()).outcome());
        assertEquals(TradeStore.Outcome.RELEASED, store.release(one.id(), disk.marks()).outcome());
        assertEquals(List.of(), mail(OWNER));
        assertEquals(List.of(), mail(HELPER));
    }
}
