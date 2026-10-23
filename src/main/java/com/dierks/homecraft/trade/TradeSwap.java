package com.dierks.homecraft.trade;

import com.dierks.homecraft.mini.MiniService.MiniMove;
import com.dierks.homecraft.storage.TradeDao;
import com.dierks.homecraft.trade.TradeSession.Entry;
import com.dierks.homecraft.trade.TradeSession.Side;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntFunction;

/**
 * The swap (SHOP-V2-SPEC §4.6, red team D-M5, D-m1), over any kind of item: Bukkit's in the live trade window
 * ({@link TradeService}), a test's stand-ins over a real in-memory database in {@code TradeSwapTxTest}. Main thread,
 * one tick, in this pinned order:
 *
 * <ol>
 *   <li><b>Verify</b> ({@link TradeRules#verify}, read now). Nothing else runs between it and the take.</li>
 *   <li><b>Plan room</b> ({@link TradeRoom#plan}): what fits each bag; the rest is the planned overflow.</li>
 *   <li><b>Take</b> the REAL stacks out of their slots into memory (a slot keeps the rest, or is emptied). A slot
 *       that gives less than its entry puts every taken stack back. From here the receiver gets these exact stacks,
 *       never clones of the snapshot, so a slot that changed can't make a copy.</li>
 *   <li><b>Money out</b>: withdraw each payer (a second failure deposits the first back).</li>
 *   <li><b>One transaction</b> ({@link TradeStore#commit}): the trade row PENDING (both sides' stacks kept whole
 *       until a start or a settle no longer needs them, red-team finding TCR4-5; each side's balance just before
 *       step 4, red-team finding TCR4-3), the overflow HELD in the Mailbox, every Mini's owner and sale (at any
 *       depth), every collector claim (D41). Any exception puts every taken stack back and pays both back: nothing
 *       changed hands.</li>
 *   <li><b>Give</b> the taken stacks that fit. What {@code addItem} still won't take goes HELD to the Mailbox in a
 *       second transaction, with a SEVERE naming the trade; if even that fails, its Base64 is in the SEVERE line
 *       and in the trade row. Never the ground. Then each bag is <b>marked</b> with the trade's id and time
 *       ({@code hcm:trade_seen}, {@code hcm:trade_seen_at}), in the data it is saved with.</li>
 *   <li><b>Money in</b>: deposit each payee; a failed deposit is refunded to its payer (SEVERE), and the row then
 *       says that payer paid nothing; the result says what really moved, so the players' lines do too (red-team
 *       finding NT-1).</li>
 *   <li><b>Save</b> both bags, then flip the trade DONE and its HELD rows READY ({@link TradeStore#finish}). A save
 *       that didn't reach the disk (the live save checks the player's file: vanilla only logs a failure) leaves the
 *       trade PENDING instead, until the next start reads the disk.</li>
 * </ol>
 *
 * <p>A hard kill between 5 and 8 leaves the trade PENDING with its Mailbox rows HELD, and nothing HELD reaches anyone
 * until an admin settles it ({@code /hcm trades settle}). Each bag comes back either from before the trade or from
 * after it, and its mark says which (red-team findings TCS-2, TCS-3): at the next server start
 * {@link TradeStore#check} writes that on the row with one WARN, and an admin settles it (release when both were
 * saved, void when neither was, and, when only one was, takes the other side's offer out of that bag by hand and
 * voids for that player, whose own offer goes to their Mailbox). Nothing is settled on its own (red-team finding
 * TCR4-WORTH-1).
 *
 * @param <I> the item type
 */
public final class TradeSwap<I> {

    /** The two bags as the swap touches them. */
    public interface Bags<I> {
        I get(Side s, int slot);

        void set(Side s, int slot, I item);

        /** A copy of the bag's storage slots as they are now, for the plan. */
        List<I> storage(Side s);

        /** Add one stack the way {@code addItem} does; returns how many it wouldn't take. */
        int add(Side s, I item);

        /**
         * Save the player's inventory ({@code Player.saveData}); throws when it didn't reach the disk (red-team
         * finding TCR-FA-3).
         */
        void save(Side s);

        /**
         * Mark the bag as holding trade {@code id}'s result, and when it swapped ({@code hcm:trade_seen},
         * {@code hcm:trade_seen_at}, in the player's own saved data, so the mark and the bag always reach the disk
         * together; red-team findings TCS-2, TCS-3, TCR-FA-4).
         */
        void mark(Side s, long id, long at);
    }

    /** What the swap asks of an item. */
    public interface Items<I> extends TradeRoom.Stacks<I> {
        /** A copy of {@code item} with this many. */
        I withAmount(I item, int amount);

        /** The item as stored ({@code Items.toBase64}). */
        String encode(I item);

        /** Its facts, inside included ({@link ItemFacts#of}). */
        ItemFacts facts(I item);
    }

    /** Vault, as the swap uses it. */
    public interface Money {
        boolean withdraw(Side s, double amount);

        boolean deposit(Side s, double amount);

        /** The side's balance now, or null when Vault can't say (red-team finding TCR4-3). */
        default Double balance(Side s) {
            return null;
        }
    }

    /** The console. */
    public interface Log {
        void info(String line);

        void warn(String line);

        void severe(String line);
    }

    /** The swap's step 1, read in its own tick: null to go ahead. */
    @FunctionalInterface
    public interface Verifier {
        TradeRules.Failure verify();
    }

    /** How a swap ended. */
    public enum Status {
        /** The swap went through. */
        DONE,
        /** It didn't: nothing changed hands, the trade is OPEN again. */
        FAILED,
        /** Not now: the countdown isn't over, or the swap already ran. */
        NOT_NOW
    }

    /**
     * @param told      what each side is told when it failed (null when it didn't)
     * @param warn      a console WARN the verify asked for (a Mini copy), or null
     * @param got       what each side received, the real stacks, in order
     * @param mailbox   how many stacks each side has in the Mailbox from this trade: rows really written
     * @param held      those rows are still HELD, the trade PENDING (a bag wasn't saved, or the flip failed), until
     *                  an admin settles it (red-team finding TCS-5)
     * @param unwritten how many stacks each side is owed that no Mailbox row holds (the late write failed: they are
     *                  in a SEVERE line and in the trade row)
     * @param paid      what each side paid that reached the other (a deposit that failed is not here; red-team
     *                  finding NT-1)
     * @param stuck     what each side paid that neither reached the other nor came back (the refund failed too)
     */
    public record Result<I>(Status status, long id, Map<Side, String> told, String warn, Map<Side, List<I>> got,
                            Map<Side, Integer> mailbox, boolean held, Map<Side, Integer> unwritten,
                            Map<Side, Double> paid, Map<Side, Double> stuck) {

        static <I> Result<I> notNow() {
            return new Result<>(Status.NOT_NOW, -1, Map.of(), null, Map.of(), Map.of(), false, Map.of(), Map.of(),
                    Map.of());
        }

        static <I> Result<I> failed(String a, String b, String warn) {
            Map<Side, String> told = new EnumMap<>(Side.class);
            told.put(Side.A, a);
            told.put(Side.B, b);
            return new Result<>(Status.FAILED, -1, told, warn, Map.of(), Map.of(), false, Map.of(), Map.of(),
                    Map.of());
        }

        static <I> Result<I> failed(String both, String warn) {
            return failed(both, both, warn);
        }

        public boolean ok() {
            return status == Status.DONE;
        }
    }

    /** One player of the trade. */
    public record Party(UUID id, String name) {
    }

    /** What a trade's Mailbox rows are labelled. */
    static String label(String from) {
        return "From your trade with " + from;
    }

    private final Bags<I> bags;
    private final Items<I> items;
    private final Money money;
    private final TradeStore store;
    private final Log log;

    public TradeSwap(Bags<I> bags, Items<I> items, Money money, TradeStore store, Log log) {
        this.bags = bags;
        this.items = items;
        this.money = money;
        this.store = store;
        this.log = log;
    }

    /** One stack taken out of its slot: the slot, what it held before, and what came out. */
    private record Taken<I>(Side side, int slot, I before, I taken) {
    }

    /**
     * Run the swap, once (§4.6). The session must have finished its countdown: it is set SWAPPING first, so a
     * second call (another timer, a click, a close) does nothing.
     *
     * @param claimsA the collector claims for what A gives (giver A, receiver B); {@code claimsB} the other way
     * @param world   where it happened, for the log
     */
    public Result<I> run(TradeSession<I> session, Party a, Party b, String world, long openedAt, long now,
                         Verifier verifier, TradeRules.Claims claimsA, TradeRules.Claims claimsB) {
        if (!session.beginSwap(now)) {
            return Result.notNow();
        }
        // 1. verify, in this tick, on the real stacks
        TradeRules.Failure failure;
        try {
            failure = verifier.verify();
        } catch (RuntimeException e) {
            failure = new TradeRules.Failure("That didn't work - nothing changed hands.", null);
            log.severe("Trade between " + a.name() + " and " + b.name() + ": the last check failed: " + e);
        }
        if (failure != null) {
            session.swapFailed(now);
            return Result.failed(failure.message(), failure.warn());
        }
        Map<Side, Party> party = new EnumMap<>(Side.class);
        party.put(Side.A, a);
        party.put(Side.B, b);

        // 2. plan room: each receiver's bag with their own offer out and the other side's real stacks in
        Map<Side, TradeRoom.Plan> plan = new EnumMap<>(Side.class);
        try {
            for (Side r : Side.values()) {
                List<I> incoming = new ArrayList<>();
                for (Map.Entry<Integer, Entry<I>> e : session.offer(r.other()).entrySet()) {
                    I real = bags.get(r.other(), e.getKey());
                    incoming.add(items.withAmount(real == null ? e.getValue().snapshot() : real,
                            e.getValue().amount()));
                }
                plan.put(r, TradeRoom.plan(bags.storage(r), amounts(session.offer(r)), incoming, items));
            }
        } catch (RuntimeException e) {
            session.swapFailed(now);
            log.severe("Trade between " + a.name() + " and " + b.name() + ": the room plan failed: " + e);
            return Result.failed("That didn't work - nothing changed hands.", null);
        }

        // 3. take the real stacks out
        List<Taken<I>> taken = new ArrayList<>();
        try {
            for (Side s : Side.values()) {
                for (Map.Entry<Integer, Entry<I>> e : session.offer(s).entrySet()) {
                    int slot = e.getKey();
                    Entry<I> entry = e.getValue();
                    I real = bags.get(s, slot);
                    if (real == null || !items.similar(real, entry.snapshot())
                            || items.amount(real) < entry.amount()) {
                        putBack(taken);
                        session.swapFailed(now);
                        ItemFacts f = items.facts(entry.snapshot());
                        String what = f == null ? "things" : f.name();
                        String mine = "Your " + what + " changed - look again.";
                        String theirs = party.get(s).name() + "'s " + what + " changed - look again.";
                        return s == Side.A ? Result.failed(mine, theirs, null) : Result.failed(theirs, mine, null);
                    }
                    int rest = items.amount(real) - entry.amount();
                    I out = items.withAmount(real, entry.amount());
                    bags.set(s, slot, rest > 0 ? items.withAmount(real, rest) : null);
                    taken.add(new Taken<>(s, slot, real, out));
                }
            }
        } catch (RuntimeException e) {
            putBack(taken);
            session.swapFailed(now);
            log.severe("Trade between " + a.name() + " and " + b.name() + ": taking the stacks out failed, so they"
                    + " went back: " + e);
            return Result.failed("That didn't work - nothing changed hands.", null);
        }

        // 4. money out, with each balance just before it (TCR4-3: a stop between a withdraw and its deposit leaves
        // the admin the numbers to tell what moved)
        double payA = session.money(Side.A);
        double payB = session.money(Side.B);
        TradeStore.Balances before = payA > 0 || payB > 0 ? new TradeStore.Balances(balance(Side.A), balance(Side.B))
                : null;
        if (payA > 0 && !withdraw(Side.A, payA)) {
            putBack(taken);
            session.swapFailed(now);
            return Result.failed("The money couldn't be taken - nothing changed hands.", null);
        }
        if (payB > 0 && !withdraw(Side.B, payB)) {
            if (payA > 0) {
                refund(Side.A, payA, -1, a, b);
            }
            putBack(taken);
            session.swapFailed(now);
            return Result.failed("The money couldn't be taken - nothing changed hands.", null);
        }

        // 5. one transaction: the log PENDING, the overflow HELD, the Minis, the claims
        Map<Side, List<I>> got = new EnumMap<>(Side.class);
        Map<Side, List<Integer>> fits = new EnumMap<>(Side.class);
        List<TradeStore.Held> held = new ArrayList<>();
        List<MiniMove> minis = new ArrayList<>();
        List<TradeRules.ClaimMove> claims = new ArrayList<>();
        Map<Side, Integer> mailbox = new EnumMap<>(Side.class);
        long id;
        try {
            for (Side r : Side.values()) {
                Side g = r.other();
                List<I> in = new ArrayList<>();
                List<Integer> fit = new ArrayList<>();
                List<ItemFacts> collectors = new ArrayList<>();
                String venue = TradeRules.venue(!session.gives(r));
                int k = 0;
                int rows = 0;
                for (Taken<I> t : taken) {
                    if (t.side() != g) {
                        continue;
                    }
                    in.add(t.taken());
                    int f = plan.get(r).fits()[k++];
                    fit.add(f);
                    int over = items.amount(t.taken()) - f;
                    for (int n : stacks(over, items.maxStack(t.taken()))) {
                        held.add(new TradeStore.Held(party.get(r).id(), items.encode(items.withAmount(t.taken(), n)),
                                label(party.get(g).name())));
                        rows++;
                    }
                    ItemFacts facts = items.facts(t.taken());
                    if (facts != null) {
                        for (ItemFacts m : facts.minis()) {
                            minis.add(new MiniMove(m.miniUid(), m.miniId(), party.get(r).id(), venue));
                        }
                        collectors.addAll(facts.collectors());
                    }
                }
                claims.addAll(TradeRules.claimMoves(party.get(g).id(), party.get(r).id(), collectors,
                        g == Side.A ? claimsA : claimsB));
                got.put(r, in);
                fits.put(r, fit);
                mailbox.put(r, rows);
            }
            TradeDao.Trade row = new TradeDao.Trade(0, a.id(), a.name(), b.id(), b.name(), world == null ? "" : world,
                    openedAt, now, TradeDao.PENDING, "", stored(got.get(Side.B)), stored(got.get(Side.A)),
                    summary(got.get(Side.B)), summary(got.get(Side.A)), payA, payB, held.size());
            id = store.commit(row, held, minis, claims, now, before);
        } catch (SQLException | RuntimeException e) {
            putBack(taken);
            if (payA > 0) {
                refund(Side.A, payA, -1, a, b);
            }
            if (payB > 0) {
                refund(Side.B, payB, -1, a, b);
            }
            session.swapFailed(now);
            log.severe("Trade between " + a.name() + " and " + b.name() + " couldn't be saved, so nothing changed"
                    + " hands: " + e.getMessage());
            return Result.failed("That didn't work - nothing changed hands.", null);
        }

        // 6. give the taken stacks that fit; anything addItem still won't take goes HELD too, never the ground
        List<TradeStore.Held> later = new ArrayList<>();
        Map<Side, Integer> laterRows = new EnumMap<>(Side.class);
        for (Side r : Side.values()) {
            List<I> in = got.get(r);
            for (int i = 0; i < in.size(); i++) {
                I t = in.get(i);
                int f = fits.get(r).get(i);
                if (f <= 0) {
                    continue;
                }
                int refused;
                try {
                    refused = Math.max(0, Math.min(f, bags.add(r, items.withAmount(t, f))));
                } catch (RuntimeException e) {
                    refused = f; // not handed over: it goes to the Mailbox with the rest
                    log.severe("Trade #" + id + ": handing a stack to " + party.get(r).name() + " failed (" + e
                            + "); it goes to their Mailbox.");
                }
                for (int n : stacks(refused, items.maxStack(t))) {
                    later.add(new TradeStore.Held(party.get(r).id(), items.encode(items.withAmount(t, n)),
                            label(party.get(r.other()).name())));
                    laterRows.merge(r, 1, Integer::sum);
                }
            }
        }
        Map<Side, Integer> unwritten = new EnumMap<>(Side.class);
        if (!later.isEmpty()) {
            List<String> lines = new ArrayList<>();
            for (TradeStore.Held h : later) {
                lines.add(h.itemB64());
            }
            try {
                store.heldLater(id, later);
                laterRows.forEach((r, n) -> mailbox.merge(r, n, Integer::sum)); // counted once they are written
                log.severe("Trade #" + id + ": " + later.size() + " stack(s) didn't fit a bag after all (the plan said"
                        + " they would); they are held in the Mailbox with the rest of the trade.");
            } catch (SQLException | RuntimeException e) {
                unwritten.putAll(laterRows);
                log.severe("Trade #" + id + ": " + later.size() + " stack(s) didn't fit a bag and couldn't be written"
                        + " to the Mailbox (" + e.getMessage() + "). Give them back by hand: "
                        + String.join(" ", lines));
            }
        }
        // the mark (TCS-2, TCS-3): from here each bag holds this trade's result, and says so in the data it is saved
        // with. Nothing saved either player since the take, so a bag on disk without it is from before the trade.
        boolean marked = true;
        for (Side s : Side.values()) {
            try {
                bags.mark(s, id, now);
            } catch (RuntimeException e) {
                marked = false;
                log.severe("Trade #" + id + ": couldn't mark " + party.get(s).name() + "'s bag (" + e + "); if it"
                        + " doesn't finish, look at both bags before you settle it.");
                try {
                    store.unmarked(id);
                } catch (SQLException | RuntimeException f) {
                    log.severe("Trade #" + id + ": couldn't note that either: " + f.getMessage());
                }
            }
        }

        // 7. money in; a payment given back is 0 on the row, so no later line names it as paid (MONEY-REFUND), and
        // the result says what really moved, so neither player's line does either (NT-1)
        Map<Side, Double> paid = new EnumMap<>(Side.class);
        Map<Side, Double> stuck = new EnumMap<>(Side.class);
        for (Side payer : Side.values()) {
            double amount = payer == Side.A ? payA : payB;
            if (amount <= 0) {
                continue;
            }
            if (deposit(payer.other(), amount)) {
                paid.put(payer, amount);
            } else if (refund(payer, amount, id, a, b)) {
                paidBack(id, payer);
            } else {
                stuck.put(payer, amount);
            }
        }

        // 8. save both bags (each with its mark), then DONE and the HELD rows READY. A save that didn't reach the disk
        // leaves the trade PENDING: its rows stay HELD, and the next start reads from the marks where the bags are
        // (TCS-3, TCR-FA-3)
        boolean saved = true;
        Map<Side, Boolean> unsaved = new EnumMap<>(Side.class);
        for (Side s : Side.values()) {
            try {
                bags.save(s);
            } catch (RuntimeException e) {
                saved = false;
                unsaved.put(s, true);
                log.severe("Trade #" + id + ": couldn't save " + party.get(s).name() + "'s things: " + e.getMessage());
            }
        }
        if (!saved && marked) {
            try {
                store.unsaved(id, unsaved.containsKey(Side.A), unsaved.containsKey(Side.B));
            } catch (SQLException | RuntimeException e) {
                log.severe("Trade #" + id + ": couldn't note which bag wasn't saved: " + e.getMessage());
            }
        }
        boolean pending = true;
        if (saved) {
            try {
                store.finish(id);
                pending = false;
            } catch (SQLException | RuntimeException e) {
                log.severe("Trade #" + id + " is saved but couldn't be marked done (" + e.getMessage() + "); it stays"
                        + " PENDING with its Mailbox rows held - /hcm trades show " + id + " says how to settle it.");
            }
        } else {
            log.severe("Trade #" + id + " stays PENDING with its Mailbox rows held, because a bag couldn't be saved -"
                    + " /hcm trades show " + id + " says how to settle it.");
        }
        session.done();
        log.info(a.name() + " traded with " + b.name() + " (#" + id + ")");
        return new Result<>(Status.DONE, id, Map.of(), null, got, mailbox, pending, unwritten, paid, stuck);
    }

    /** Vault's balance, or null when it can't say (or throws). */
    private Double balance(Side s) {
        try {
            return money.balance(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Vault's withdraw; one that throws took nothing. */
    private boolean withdraw(Side s, double amount) {
        try {
            return money.withdraw(s, amount);
        } catch (RuntimeException e) {
            log.severe("A trade's withdraw of $" + amount + " threw: " + e);
            return false;
        }
    }

    /** Vault's deposit; one that throws gave nothing. */
    private boolean deposit(Side s, double amount) {
        try {
            return money.deposit(s, amount);
        } catch (RuntimeException e) {
            log.severe("A trade's deposit of $" + amount + " threw: " + e);
            return false;
        }
    }

    /** Every taken stack back into its own slot, as it was. */
    private void putBack(List<Taken<I>> taken) {
        for (Taken<I> t : taken) {
            bags.set(t.side(), t.slot(), t.before());
        }
        taken.clear();
    }

    /**
     * Pay {@code payer} back; a refund that fails too is a SEVERE with the amount (and the trade, if there is one).
     *
     * @return whether the money went back
     */
    private boolean refund(Side payer, double amount, long id, Party a, Party b) {
        if (!deposit(payer, amount)) {
            log.severe("Trade " + (id > 0 ? "#" + id + " " : "") + "between " + a.name() + " and " + b.name()
                    + ": $" + amount + " couldn't be paid back to " + (payer == Side.A ? a : b).name()
                    + " - pay it by hand.");
            return false;
        }
        if (id > 0) {
            log.severe("Trade #" + id + ": a deposit failed, so $" + amount + " went back to "
                    + (payer == Side.A ? a : b).name() + ".");
        }
        return true;
    }

    /** The row says {@code payer} paid nothing: the money went back. */
    private void paidBack(long id, Side payer) {
        try {
            store.paidBack(id, payer);
        } catch (SQLException | RuntimeException e) {
            log.severe("Trade #" + id + ": its log still names the money that went back (" + e.getMessage() + ").");
        }
    }

    /** An offer's bag slots and amounts. */
    static <I> Map<Integer, Integer> amounts(Map<Integer, Entry<I>> offer) {
        Map<Integer, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<Integer, Entry<I>> e : offer.entrySet()) {
            out.put(e.getKey(), e.getValue().amount());
        }
        return out;
    }

    /** {@code amount} split into stacks of at most {@code max}. */
    static List<Integer> stacks(int amount, int max) {
        List<Integer> out = new ArrayList<>();
        int m = Math.max(1, max);
        for (int left = amount; left > 0; left -= m) {
            out.add(Math.min(left, m));
        }
        return out;
    }

    /**
     * One side's stacks as stored: one Base64 per line, whole (red-team finding TCR4-5: a settle may need to hand
     * them back; {@code TradeDao.trim} cuts a side past {@link TradeStore#ITEMS_LIMIT} once nothing does).
     */
    private String stored(List<I> stacks) {
        StringBuilder sb = new StringBuilder();
        for (I s : stacks) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(items.encode(s));
        }
        return sb.toString();
    }

    /** "3 Diamond, Pumpkin Cap 2026, Creeper Mini". */
    private String summary(List<I> stacks) {
        List<String> parts = new ArrayList<>();
        for (I s : stacks) {
            ItemFacts f = items.facts(s);
            String name = f == null ? "something" : f.name();
            int n = items.amount(s);
            parts.add(n > 1 ? n + " " + name : name);
        }
        return String.join(", ", parts);
    }

    /**
     * The swap's step 1 facts for one side's entries: what the table says against what each slot holds now
     * ({@code bag}), the similarity and facts read with {@code items}.
     */
    public static <I> List<TradeRules.Held> held(Map<Integer, Entry<I>> offer, IntFunction<I> bag, Items<I> items) {
        List<TradeRules.Held> out = new ArrayList<>();
        for (Map.Entry<Integer, Entry<I>> e : offer.entrySet()) {
            I real = bag.apply(e.getKey());
            Entry<I> entry = e.getValue();
            ItemFacts snap = items.facts(entry.snapshot());
            out.add(new TradeRules.Held(e.getKey(), snap == null ? "things" : snap.name(), entry.amount(),
                    real != null && items.similar(real, entry.snapshot()), real == null ? 0 : items.amount(real),
                    real == null ? null : items.facts(real)));
        }
        return out;
    }
}
