package com.dierks.homecraft.trade;

import com.dierks.homecraft.marketplace.DeliveryService;
import com.dierks.homecraft.mini.MiniService.MiniMove;
import com.dierks.homecraft.storage.Database;
import com.dierks.homecraft.storage.DeliveryDao;
import com.dierks.homecraft.storage.PrizeDao;
import com.dierks.homecraft.storage.TradeDao;
import com.dierks.homecraft.trade.TradeSession.Side;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * What a trade writes (SHOP-V2-SPEC §4.6 steps 5, 6 and 8, §4.7, §4.12): the swap's one transaction, a late
 * Mailbox row, the flip once both bags are saved, and an admin's settle of a trade that didn't finish. Every Mailbox
 * row a trade writes carries {@link #source} ({@code TRADE#<id>}), so its rows are released or voided together, and
 * the row keeps what else the swap moved (Mini owners, collector claims), so a void puts that back too (red-team
 * finding D4).
 *
 * <p><b>A trade that didn't finish is only reported</b> (§4.6, red team D-m1; red-team finding TCR4-WORTH-1): the
 * next server start reads where its bags are from the mark each one is saved with ({@link Saved}) and writes that on
 * the row with one WARN, and an admin looks and settles it. Nothing moves an item or a Mailbox row on its own, and a
 * finished trade is never read or reopened (red-team findings AW-1, AW-2); a read the admin knows is wrong is
 * overruled with {@code confirm}.
 */
public final class TradeStore {

    /** One stack for a receiver's Mailbox, HELD until the trade finishes. */
    public record Held(UUID player, String itemB64, String label) {
    }

    /** Where Mini owners move (the live {@code MiniService}, or a test's over its own dao). */
    public interface MiniOwners {
        /** Move them ({@code MiniService.tradeOwners}); returns each copy's recorded owner before (null for none). */
        List<UUID> move(List<MiniMove> moves, long now) throws SQLException;

        /**
         * Put one back to {@code before} while it is still as the swap left it ({@code receiver}'s, with the trade's
         * sale row still its newest), with that sale row gone ({@code MiniService.tradeBack}); false when it has
         * moved on since.
         */
        boolean back(String uid, UUID before, UUID receiver, long soldAt) throws SQLException;
    }

    /** Each side's balance just before the swap's money moved (null: Vault couldn't say; red-team finding TCR4-3). */
    public record Balances(Double a, Double b) {
    }

    /**
     * A side's stored stacks are kept whole while a start or a settle may need them, and trimmed to '' past this much
     * Base64 after (§4.12; red-team finding TCR4-5).
     */
    public static final int ITEMS_LIMIT = 64 * 1024;

    private final Database db;
    private final TradeDao trades;
    private final DeliveryService mailbox;
    private final DeliveryDao deliveries;
    private final MiniOwners minis;
    private final PrizeDao prizes;

    public TradeStore(Database db, TradeDao trades, DeliveryService mailbox, DeliveryDao deliveries, MiniOwners minis,
                      PrizeDao prizes) {
        this.db = db;
        this.trades = trades;
        this.mailbox = mailbox;
        this.deliveries = deliveries;
        this.minis = minis;
        this.prizes = prizes;
    }

    public TradeDao trades() {
        return trades;
    }

    /** The tag on every Mailbox row of one trade. */
    public static String source(long id) {
        return "TRADE#" + id;
    }

    /** {@link #commit(TradeDao.Trade, List, List, List, long, Balances)} with no money on the table. */
    public long commit(TradeDao.Trade row, List<Held> held, List<MiniMove> moves, List<TradeRules.ClaimMove> claims,
                       long now) throws SQLException {
        return commit(row, held, moves, claims, now, null);
    }

    /**
     * The swap's one transaction (§4.6 step 5): the trade row PENDING, each planned overflow stack HELD, each Mini's
     * owner and its price-0 sale, and each collector claim by D41: a moving claim needs {@code unclaim} to delete
     * exactly one row and {@code claimOnce} to insert one; a cap going home needs its claim still at home and none
     * with the giver. Anything else throws, and everything rolls back. Each owner and claim that moved, and each cap
     * that went home, is written on the row ({@code moved}, one line each), so a void can put it back and an older
     * trade's void can see that a later one carried it ({@link #putBack}); and {@code before}, each side's balance
     * just before the money moved, when money was on the table.
     *
     * @return the trade's id
     */
    public long commit(TradeDao.Trade row, List<Held> held, List<MiniMove> moves, List<TradeRules.ClaimMove> claims,
                       long now, Balances before) throws SQLException {
        return db.transaction(c -> {
            long id = trades.insert(row);
            for (Held h : held) {
                mailbox.queueHeld(h.player(), h.itemB64(), h.label(), source(id));
            }
            List<String> moved = new ArrayList<>();
            List<UUID> was = minis.move(moves, now);
            for (int i = 0; i < moves.size(); i++) {
                UUID w = was == null || i >= was.size() ? null : was.get(i);
                moved.add(String.join("\t", MINI, moves.get(i).uid(), w == null ? "" : w.toString(),
                        moves.get(i).receiver().toString()));
            }
            for (TradeRules.ClaimMove m : claims) {
                if (m.moves()) {
                    if (prizes.unclaim(m.giver(), m.id()) != 1) {
                        throw new SQLException("the giver's claim on " + m.id() + " was not there to move");
                    }
                    if (!prizes.claimOnce(m.receiver(), m.id())) {
                        throw new SQLException("the receiver already has " + m.id());
                    }
                    moved.add(String.join("\t", CLAIM, m.id(), m.giver().toString(), m.receiver().toString()));
                } else if (prizes.purchases(m.receiver(), m.id(), "life") <= 0
                        || prizes.purchases(m.giver(), m.id(), "life") > 0) {
                    throw new SQLException("the claim on " + m.id() + " changed");
                } else {
                    moved.add(String.join("\t", CAP, m.id(), m.giver().toString(), m.receiver().toString()));
                }
            }
            if (!moved.isEmpty()) {
                trades.moved(id, String.join("\n", moved));
            }
            if (before != null && (before.a() != null || before.b() != null)) {
                trades.before(id, before.a(), before.b());
            }
            return id;
        });
    }

    /** A {@code moved} line's kind: a Mini's owner ({@code mini uid before receiver}). */
    static final String MINI = "mini";
    /** A {@code moved} line's kind: a collector claim ({@code claim id giver receiver}). */
    static final String CLAIM = "claim";
    /** A {@code moved} line's kind: a collector cap going home, no claim moved ({@code cap id giver receiver}). */
    static final String CAP = "cap";

    /**
     * What the swap moved besides the stacks, put back (red-team finding D4) inside a void's transaction: each Mini's
     * owner (and the trade's price-0 sale row), and each collector claim, only while it is still as the swap left it
     * and no later trade that stands carried it (a redo of the same hand-over leaves the same owner and claim, so
     * "still the receiver's" alone can't tell; red-team findings R4FA-2, TCR4-1). A later trade that carried one and
     * still waits refuses the void first ({@link #waitingAfter}), so the one that carried it here went through. Each
     * one left as it is is named, for the admin.
     */
    private List<String> putBack(TradeDao.Trade t) throws SQLException {
        List<String> notes = new ArrayList<>();
        List<TradeDao.Moved> later = trades.movedAfter(t.id());
        for (String line : trades.moved(t.id()).split("\n")) {
            String[] f = line.split("\t", -1);
            if (f.length != 4) {
                continue;
            }
            UUID receiver = UUID.fromString(f[3]);
            if (f[0].equals(MINI)) {
                UUID before = f[2].isEmpty() ? null : UUID.fromString(f[2]);
                Long by = carried(later, f[1], MINI);
                if (by != null) {
                    notes.add("Mini " + f[1] + " went in trade #" + by + " since, so its owner was left");
                } else if (!minis.back(f[1], before, receiver, t.closedAt()) && before != null) { // no record: none
                    notes.add("Mini " + f[1] + " isn't as the trade left it (" + who(t, receiver) + "'s, with no sale"
                            + " since), so its owner was left");
                }
            } else if (f[0].equals(CLAIM)) {
                UUID giver = UUID.fromString(f[2]);
                Long by = carried(later, f[1], CLAIM, CAP);
                if (by != null) {
                    notes.add("the claim on " + f[1] + " went in trade #" + by + " since, so it was left");
                } else if (prizes.unclaim(receiver, f[1]) != 1) {
                    notes.add(who(t, receiver) + " has no claim on " + f[1] + " any more, so " + who(t, giver)
                            + " didn't get it back");
                } else if (!prizes.claimOnce(giver, f[1])) {
                    notes.add(who(t, giver) + " already had a claim on " + f[1] + " again, so " + who(t, receiver)
                            + "'s went and theirs stays");
                }
            }
        }
        return notes;
    }

    /** The first later trade whose {@code moved} names {@code key} with one of {@code kinds}, or null. */
    private static Long carried(List<TradeDao.Moved> later, String key, String... kinds) {
        for (TradeDao.Moved m : later) {
            for (String line : m.moved().split("\n")) {
                String[] f = line.split("\t", -1);
                if (f.length == 4 && f[1].equals(key) && List.of(kinds).contains(f[0])) {
                    return m.id();
                }
            }
        }
        return null;
    }

    /**
     * Why a void of {@code t} must wait, or null (red-team findings AW-3, SETTLE-ORDER-1): a later trade that still
     * waits to be settled moved one of the Minis or collector claims {@code t} moved (they traded it back, say).
     * Voided first, {@code t} would leave it as that trade left it, and that trade's own void would then put it back
     * to what {@code t} left: the wrong player. Settled newest first, each void puts back what it moved, so the void
     * is refused until the later one is settled, whatever {@code confirm} says.
     */
    private String waitingAfter(TradeDao.Trade t) throws SQLException {
        List<TradeDao.Moved> waiting = new ArrayList<>();
        for (TradeDao.Moved m : trades.movedAfter(t.id())) {
            if (TradeDao.PENDING.equals(m.outcome())) {
                waiting.add(m);
            }
        }
        if (waiting.isEmpty()) {
            return null;
        }
        for (String line : trades.moved(t.id()).split("\n")) {
            String[] f = line.split("\t", -1);
            if (f.length != 4) {
                continue;
            }
            Long by = f[0].equals(MINI) ? carried(waiting, f[1], MINI)
                    : f[0].equals(CLAIM) ? carried(waiting, f[1], CLAIM, CAP) : null;
            if (by != null) {
                String what = f[0].equals(MINI) ? "Mini " + f[1] : "the claim on " + f[1];
                return "trade #" + by + " moved " + what + " after this one and is still waiting: settle #" + by
                        + " first (the newest first, so each void puts back what it moved)";
            }
        }
        return null;
    }

    private static String who(TradeDao.Trade t, UUID player) {
        return player.equals(t.a()) ? t.aName() : player.equals(t.b()) ? t.bName() : player.toString();
    }

    /** A stack {@code addItem} wouldn't take after all (§4.6 step 6): HELD for the same trade, in a second one. */
    public void heldLater(long id, List<Held> held) throws SQLException {
        db.transaction(c -> {
            for (Held h : held) {
                mailbox.queueHeld(h.player(), h.itemB64(), h.label(), source(id));
            }
            trades.addMailbox(id, held.size());
            return null;
        });
    }

    /**
     * Both bags are saved (§4.6 step 8): the trade DONE and its HELD rows READY, in one small transaction. Nothing
     * reads a finished trade again (a player file or world folder put back later never reopens it: red-team findings
     * AW-1, AW-2), so a side past {@link #ITEMS_LIMIT} is trimmed now.
     */
    public void finish(long id) throws SQLException {
        db.transaction(c -> {
            trades.finish(id, TradeDao.PENDING, TradeDao.DONE, "");
            mailbox.release(source(id));
            trades.trim(id, ITEMS_LIMIT);
            return null;
        });
    }

    /**
     * The swap couldn't mark a bag (red-team finding TCS-3): written on the row at once, so a trade that stays
     * PENDING reads as {@link Saved#UNKNOWN} (look at both bags) rather than as a bag that was never saved.
     */
    public void unmarked(long id) throws SQLException {
        trades.pendingReason(id, UNMARKED);
    }

    /**
     * A bag the swap saved didn't reach the disk (red-team findings TCS-3, TCR-FA-3: vanilla only logs a failed save,
     * so the live save checks the player's file). While the server runs that bag still holds the trade, so its live
     * mark can't say where it will come back from: nothing is settled, and nothing reads it again, until the next
     * server start reads the disk (a plugin reload doesn't: red-team finding R4FA-4).
     */
    public void unsaved(long id, boolean a, boolean b) throws SQLException {
        trades.pendingReason(id, a && b ? "bags unsaved" : a ? "bag a unsaved" : "bag b unsaved");
    }

    /** The sides whose save didn't reach the disk, as {@link #unsaved} wrote them, or none. */
    static List<Side> unsaved(TradeDao.Trade t) {
        String r = t.reason() == null ? "" : t.reason();
        return switch (r) {
            case "bags unsaved" -> List.of(Side.A, Side.B);
            case "bag a unsaved" -> List.of(Side.A);
            case "bag b unsaved" -> List.of(Side.B);
            default -> List.of();
        };
    }

    /** A deposit failed and its payer got the money back (step 7): the row names what really moved. */
    public void paidBack(long id, Side payer) throws SQLException {
        trades.paidBack(id, payer == Side.A);
    }

    // ---- where the bags are (red-team findings TCS-2, TCS-3, R4FA-1) -----------------------------------------

    /** The reason a PENDING row carries when the swap couldn't mark a bag ({@link #unmarked}). */
    static final String UNMARKED = "bags unmarked";

    /**
     * The reason a start writes on a PENDING row when one bag alone reads as saved with it but the players played on
     * after the swap ({@link #playedOn}): read as {@link Saved#UNKNOWN}, and never read again (red-team finding
     * SETTLE-REHELD-COPY-2).
     */
    static final String PLAYED_ON = "bags unread, played on";

    /**
     * Where a trade's two bags are, read from the mark each one is saved with ({@link Mark}: the newest trade the bag
     * holds the result of, and when it swapped; trade ids only grow within one database). The swap sets it in both
     * bags as it hands the stacks over, and nothing saves a player between the take and that, so a bag the server
     * saved is either from before the trade (a lower mark, or none and saved before it swapped) or from after it.
     * Read at a server start and written on the row ({@link #code()}), so a later trade that raises a mark can't
     * change the answer; a later start reads it again only exactly ({@link #check}). It only advises: an admin
     * settles, and may overrule it ({@code confirm}).
     */
    public enum Saved {
        /** Both bags were saved with the trade: its held Mailbox rows are owed (release). */
        BOTH("bags saved"),
        /** Neither was: both came back from before it, each holding its own offer (void). */
        NEITHER("bags not saved"),
        /**
         * Only A's was: A holds B's offer while B still holds it too, and A's own is in no bag. The admin takes B's
         * offer out of A's bag, and {@code void <A>} sends A's own offer to A's Mailbox from the trade row.
         */
        A("bag a saved"),
        /** Only B's was (the same, the other way). */
        B("bag b saved"),
        /**
         * A bag couldn't be read, or wasn't marked, or one bag alone reads as saved but they played on after the swap
         * ({@link #PLAYED_ON}): an admin looks at both.
         */
        UNKNOWN("bags unread");

        private final String code;

        Saved(String code) {
            this.code = code;
        }

        /** What the row's reason says. */
        public String code() {
            return code;
        }

        /** The side whose bag alone was saved, or null. */
        public Side only() {
            return this == A ? Side.A : this == B ? Side.B : null;
        }

        /** Whether this answer says {@code s}'s bag holds the trade (null: it doesn't say). */
        Boolean holds(Side s) {
            return switch (this) {
                case BOTH -> true;
                case NEITHER -> false;
                case A -> s == Side.A;
                case B -> s == Side.B;
                case UNKNOWN -> null;
            };
        }

        static Saved of(boolean a, boolean b) {
            return a ? (b ? BOTH : A) : (b ? B : NEITHER);
        }
    }

    /**
     * A bag's mark ({@code hcm:trade_seen} and {@code hcm:trade_seen_at}): the newest trade id it holds the result of,
     * and when that trade swapped (its {@code closed_at}). A restored database starts its ids again, so the time tells
     * a trade of this database from one of another copy that had the same id (red-team finding TCR-FA-4). A bag with
     * no mark ({@code id} 0) carries {@code saved} instead: when its data was saved (its player file's time), so a
     * file written after a trade swapped (a reset or a fresh world) is never taken for a bag from before it (red-team
     * finding R4FA-1); {@link Long#MAX_VALUE} when that can't be said (live data).
     */
    public record Mark(long id, long at, long saved) {
        /** No trade mark at all, saved before every trade (a bag from before its first trade). */
        public static final Mark NONE = new Mark(0, 0, 0);

        /** A trade's mark. */
        public Mark(long id, long at) {
            this(id, at, 0);
        }

        /** No mark, in data saved at {@code saved}. */
        public static Mark none(long saved) {
            return new Mark(0, 0, saved);
        }
    }

    /** Reads a player's saved mark: the live data while they are online, their saved file while they aren't. */
    @FunctionalInterface
    public interface Marks {
        /** {@code player}'s mark; throws when it can't be read. */
        Mark seen(UUID player) throws Exception;
    }

    /**
     * A mark as a player's data holds it ({@code id} and {@code at} null when it has none). With no mark,
     * {@code saved} says when the data was saved ({@link Long#MAX_VALUE}: it can't be said, as for an online player's
     * live data), or null when there is no saved data at all (a missing or unreadable file, the wrong world folder):
     * that can't be read, so it is never taken for a bag from before the trade (red-team finding TCR-FA-1).
     */
    public static Mark mark(Long id, Long at, Supplier<Long> saved) {
        if (id != null) {
            return new Mark(id, at == null ? 0 : at);
        }
        Long when = saved.get();
        if (when == null) {
            throw new IllegalStateException("no saved data");
        }
        return Mark.none(when);
    }

    /**
     * The state written on a PENDING row, or null when none is (a trade that went PENDING since the last start). A row
     * an earlier build left rolling one bag back reads as that bag alone saved.
     */
    public static Saved recorded(TradeDao.Trade t) {
        String r = t.reason() == null ? "" : t.reason();
        switch (r) {
            case UNMARKED, PLAYED_ON:
                return Saved.UNKNOWN;
            case "rolling back a":
                return Saved.A;
            case "rolling back b":
                return Saved.B;
            default:
                break;
        }
        for (Saved s : Saved.values()) {
            if (s.code.equals(r)) {
                return s;
            }
        }
        return null;
    }

    /** Where the bags are, read from their marks now; {@link Saved#UNKNOWN} when one can't be read. */
    public Saved read(TradeDao.Trade t, Marks marks) {
        return read(t, marks, false);
    }

    /**
     * Where the bags are; {@code exact} takes a mark as holding the trade only when it is this trade's own, for a
     * later start reading a row again (players may have joined since with a bag from before it and traded again).
     */
    Saved read(TradeDao.Trade t, Marks marks, boolean exact) {
        Boolean a = holds(marks, t.a(), t, exact);
        Boolean b = holds(marks, t.b(), t, exact);
        return a == null || b == null ? Saved.UNKNOWN : Saved.of(a, b);
    }

    /**
     * Whether {@code s}'s bag holds trade {@code t} as its mark reads now, exactly (only the trade's own mark holds
     * it), or null when it can't say: a line of {@code /hcm trades show} for the admin who looks.
     */
    public Boolean holdsNow(TradeDao.Trade t, Marks marks, Side s) {
        return holds(marks, s == Side.A ? t.a() : t.b(), t, true);
    }

    private Boolean holds(Marks marks, UUID player, TradeDao.Trade t, boolean exact) {
        try {
            return holds(marks.seen(player), player, t, exact);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Whether a bag marked {@code m} holds trade {@code t}: no mark is from before it only when the bag was saved
     * before it swapped (red-team finding R4FA-1); a lower id is from before it; its own id holds it only with its
     * own time (else the mark is from another copy of the database); a higher id holds it only when that is a trade
     * of this database with this player at that time. Null when the mark can't say.
     */
    private Boolean holds(Mark m, UUID player, TradeDao.Trade t, boolean exact) throws SQLException {
        if (m.id() == 0) {
            return m.saved() < t.closedAt() ? Boolean.FALSE : null;
        }
        if (m.id() < t.id()) {
            return false;
        }
        if (m.id() == t.id()) {
            return m.at() == t.closedAt() ? Boolean.TRUE : null;
        }
        if (exact) {
            return null;
        }
        TradeDao.Trade later = trades.byId(m.id()).orElse(null);
        return later != null && later.closedAt() == m.at() && (player.equals(later.a()) || player.equals(later.b()))
                ? Boolean.TRUE : null;
    }

    /**
     * Where a PENDING trade's bags are: what the last start wrote on the row, else (it went PENDING since) read now; a
     * row whose bag save failed is {@link Saved#UNKNOWN} until the next start reads the disk (a live mark holds it
     * either way), and so is one bag alone read after play went on ({@link #playedOn}).
     */
    public Saved state(TradeDao.Trade t, Marks marks) {
        Saved s = recorded(t);
        if (s != null) {
            return s;
        }
        if (!unsaved(t).isEmpty()) {
            return Saved.UNKNOWN;
        }
        Saved now = read(t, marks);
        return playedOn(t, now, marks) ? Saved.UNKNOWN : now;
    }

    /**
     * Whether a first read that says one bag alone holds the trade comes after the players played on (red-team finding
     * SETTLE-REHELD-COPY-2): a bag save that didn't reach the disk while both kept playing (the row says so), or a
     * saved bag whose mark is a later trade's. {@code void <player>} hands that player's offer out again from the log,
     * which is exact only when nobody played since the swap (a stop between the two saves): after play, what they
     * gave may be in the other's bag (a later trade), a chest or on a shelf, so the admin looks first.
     */
    private boolean playedOn(TradeDao.Trade t, Saved s, Marks marks) {
        return s.only() != null && (!unsaved(t).isEmpty() || read(t, marks, true) != s);
    }

    private static String name(TradeDao.Trade t, Side s) {
        return s == Side.A ? t.aName() : t.bName();
    }

    /** What {@code s} gave, as the row's summary says it ("" for nothing but money). */
    private static String gave(TradeDao.Trade t, Side s) {
        String g = s == Side.A ? t.aSummary() : t.bSummary();
        return g == null ? "" : g;
    }

    /** What to do with a PENDING trade, in one line for the WARN at a start and for {@code /hcm trades show}. */
    public static String settleAdvice(TradeDao.Trade t, Saved s) {
        long id = t.id();
        List<Side> unsaved = unsaved(t);
        if (!unsaved.isEmpty()) {
            boolean both = unsaved.size() == 2;
            return (both ? "neither bag" : name(t, unsaved.get(0)) + "'s bag didn't") + " reach the disk when the swap"
                    + " saved it (the console said why): while the server runs " + (both ? "they hold" : "it holds")
                    + " the trade, but " + (both ? "they" : "it") + " would come back from before it, so nothing can"
                    + " be settled yet. Put right what stopped the save, then restart the server: the start reads"
                    + " where both bags are and says what to do";
        }
        return switch (s) {
            case BOTH -> "both bags were saved with it, so its held Mailbox rows are owed: /hcm trades settle " + id
                    + " release";
            case NEITHER -> "neither bag was saved with it, so both came back from before it, each still holding its"
                    + " own offer: /hcm trades settle " + id + " void (release would hand out copies)";
            case A, B -> {
                Side saved = s.only();
                String sn = name(t, saved);
                String on = name(t, saved.other());
                String took = gave(t, saved.other());
                // red-team finding SETTLE-HELD-AMOUNTS-4: show lists what each held row holds, so the admin takes out
                // exactly what reached the bag
                yield onlyOne(sn, on) + (took.isEmpty() ? "" : "take what " + on + " gave (" + took + ") out of "
                        + sn + "'s bag, less what /hcm trades show " + id + " lists as held for " + sn + " (that goes"
                        + " with the trade), then ")
                        + "/hcm trades settle " + id + " void " + sn + ": what " + sn + " gave goes to their Mailbox"
                        + (kept(t, saved) ? "" : ", except what was too big to keep in the log: give that back by"
                        + " hand");
            }
            case UNKNOWN -> (PLAYED_ON.equals(t.reason())
                    ? "only one bag reads as saved with it, but they played on after the swap (a bag save that didn't"
                    + " reach the disk, or a later trade), so what each gave may have moved on since (another bag, a"
                    + " chest, a shelf): look at both bags before you settle it"
                    : "its bags couldn't be read, so look at both before you settle it")
                    // red-team finding R5-PLAYEDON-HELD-1: the same subtraction as only one bag saved
                    + ": /hcm trades settle " + id + " release if both hold the trade, void if neither does, or void"
                    + " <player> if only that player's does (take what the other gave out of it first, less what"
                    + " /hcm trades show " + id + " lists as held for that player, which goes with the trade; what that"
                    + " player gave goes to their Mailbox again, so only when it is nowhere else)";
        };
    }

    private static String onlyOne(String saved, String other) {
        return "only " + saved + "'s bag was saved with it (what " + saved + " gave is in no bag, and what " + other
                + " gave is in both): ";
    }

    /** The verb {@link #settleAdvice} names ("release", "void" or "void <name>"), or null when the admin chooses. */
    public static String verb(TradeDao.Trade t, Saved s) {
        if (!unsaved(t).isEmpty()) {
            return null;
        }
        return switch (s) {
            case BOTH -> "release";
            case NEITHER -> "void";
            case A, B -> "void " + name(t, s.only());
            case UNKNOWN -> null;
        };
    }

    /** How a settle went. */
    public enum Outcome {
        /** The held rows are READY, the trade DONE. */
        RELEASED,
        /** The held rows are deleted, the trade VOID (and, for {@code void <player>}, their own offer READY). */
        VOIDED,
        /** That verb is wrong for where the bags are: {@link Settled#why()}. */
        REFUSED,
        /** No such trade, or it isn't PENDING. */
        NOT_PENDING
    }

    /**
     * A settle's result: how many held Mailbox rows it touched, how many stacks of their own went to one side's
     * Mailbox ({@code void <player>}), or why it was refused; and, for a void, what it couldn't put back
     * ({@link #putBack}).
     */
    public record Settled(Outcome outcome, int rows, int returned, String why, List<String> notes) {

        static Settled of(Outcome o, int rows) {
            return new Settled(o, rows, 0, null, List.of());
        }

        static Settled refused(String why) {
            return new Settled(Outcome.REFUSED, 0, 0, why, List.of());
        }
    }

    /** What a refusal adds when the admin may overrule the read (red-team finding TCR4-OVERRIDE-3). */
    private static String overrule(long id, String verb) {
        return ". If you looked at both bags and they say otherwise: /hcm trades settle " + id + " " + verb
                + " confirm";
    }

    /** {@link #release(long, Marks, boolean)} without {@code confirm}. */
    public Settled release(long id, Marks marks) throws SQLException {
        return release(id, marks, false);
    }

    /**
     * {@code /hcm trades settle <id> release [confirm]}: a PENDING trade's HELD rows READY and the trade DONE, when
     * both bags were saved with it (or they couldn't be read, and the admin looked); otherwise the rows are copies,
     * unless the admin looked at both bags and {@code confirm}s that the read is wrong (red-team finding
     * TCR4-OVERRIDE-3). A bag whose save didn't reach the disk refuses it until the next start reads the disk.
     */
    public Settled release(long id, Marks marks, boolean confirm) throws SQLException {
        TradeDao.Trade t = trades.byId(id).orElse(null);
        if (t == null || !TradeDao.PENDING.equals(t.outcome())) {
            return Settled.of(Outcome.NOT_PENDING, 0);
        }
        Saved s = state(t, marks);
        if (!unsaved(t).isEmpty()) {
            return Settled.refused(settleAdvice(t, s));
        }
        boolean fits = s == Saved.BOTH || s == Saved.UNKNOWN;
        if (!fits && !confirm) {
            return Settled.refused(settleAdvice(t, s) + overrule(id, "release"));
        }
        String reason = fits ? "settled: released" : "settled: released, confirmed over \"" + s.code() + "\"";
        return db.transaction(c -> {
            if (!trades.finish(id, TradeDao.PENDING, TradeDao.DONE, reason)) {
                return Settled.of(Outcome.NOT_PENDING, 0);
            }
            int rows = mailbox.release(source(id));
            trades.trim(id, ITEMS_LIMIT);
            return Settled.of(Outcome.RELEASED, rows);
        });
    }

    /** {@link #voidTrade(long, Marks, Side, boolean)}: a plain void, without {@code confirm}. */
    public Settled voidTrade(long id, Marks marks) throws SQLException {
        return voidTrade(id, marks, null, false);
    }

    /**
     * {@code /hcm trades settle <id> void [<player>] [confirm]}: its HELD rows deleted, the trade VOID, and the Mini
     * owners and collector claims the swap moved put back ({@link #putBack}), in one transaction. A plain void fits
     * when neither bag was saved with it (or they couldn't be read, and the admin looked). {@code side} says only
     * that side's bag holds the trade (the admin has taken the other side's offer out of it): its own offer goes READY
     * to its Mailbox from the trade row too; it fits when the start read that (or couldn't read them). Another read
     * needs {@code confirm} (the admin looked at both bags). When both bags were saved with it, a void is always
     * refused: its held rows are owed. A bag whose save didn't reach the disk refuses it until the next start, and a
     * later trade still waiting that moved one of its Minis or claims refuses it until that one is settled
     * ({@link #waitingAfter}).
     */
    public Settled voidTrade(long id, Marks marks, Side side, boolean confirm) throws SQLException {
        TradeDao.Trade t = trades.byId(id).orElse(null);
        if (t == null || !TradeDao.PENDING.equals(t.outcome())) {
            return Settled.of(Outcome.NOT_PENDING, 0);
        }
        Saved s = state(t, marks);
        if (!unsaved(t).isEmpty() || s == Saved.BOTH) {
            return Settled.refused(settleAdvice(t, s));
        }
        String wait = waitingAfter(t);
        if (wait != null) {
            return Settled.refused(wait);
        }
        boolean fits = s == Saved.UNKNOWN || (side == null ? s == Saved.NEITHER : s.only() == side);
        if (!fits && !confirm) {
            return Settled.refused(settleAdvice(t, s) + overrule(id, side == null ? "void" : "void " + name(t, side)));
        }
        String reason = "settled: voided" + (side == null ? "" : ", what " + name(t, side) + " gave to their Mailbox")
                + (fits ? "" : ", confirmed over \"" + s.code() + "\"");
        return db.transaction(c -> {
            if (!trades.finish(id, TradeDao.PENDING, TradeDao.VOID, reason)) {
                return Settled.of(Outcome.NOT_PENDING, 0);
            }
            int rows = deliveries.deleteHeld(source(id));
            List<String> notes = new ArrayList<>();
            int returned = 0;
            if (side != null) {
                UUID player = side == Side.A ? t.a() : t.b();
                for (String b64 : lines(side == Side.A ? t.aItems() : t.bItems())) {
                    mailbox.queueReady(player, b64, "Back from your trade with " + name(t, side.other()), source(id));
                    returned++;
                }
                if (!kept(t, side)) {
                    notes.add("what " + name(t, side) + " gave (" + gave(t, side) + ") was too big to keep in the log,"
                            + " so it went to no Mailbox: give it back by hand");
                }
            }
            notes.addAll(putBack(t));
            trades.trim(id, ITEMS_LIMIT);
            return new Settled(Outcome.VOIDED, rows, returned, null, notes);
        });
    }

    /** Whether {@code s}'s offer is in the row (a side an earlier build trimmed past the limit keeps ''). */
    static boolean kept(TradeDao.Trade t, Side s) {
        String items = s == Side.A ? t.aItems() : t.bItems();
        return (items != null && !items.isBlank()) || gave(t, s).isBlank();
    }

    /** One stored side: one stack per line. */
    static List<String> lines(String stored) {
        List<String> out = new ArrayList<>();
        if (stored == null || stored.isBlank()) {
            return out;
        }
        for (String l : stored.split("\n")) {
            if (!l.isBlank()) {
                out.add(l);
            }
        }
        return out;
    }

    /** {@link #check(Consumer, Marks, boolean, Function)} at a server start, with no balances to read. */
    int check(Consumer<String> warn, Marks marks) throws SQLException {
        return check(warn, marks, true, p -> null);
    }

    /**
     * At enable (§4.6, red team D-m1, red-team findings TCS-2, TCS-3, R4FA-4). At a server start ({@code boot}),
     * before anyone can join, each PENDING trade gets where its bags are written on its row (a later trade that raises
     * a mark can't change it), and one WARN saying how to settle it. One bag alone read as saved after the players
     * played on is written {@link #PLAYED_ON}: look first (red-team finding SETTLE-REHELD-COPY-2). A row an earlier
     * start wrote is read again, exactly (its own mark or a lower one: red-team finding TCR-FA-1): both bags read and
     * different (a world folder put back) replace it; one bag reading against it makes it unread, so either verb is
     * open to the admin who looks (red-team finding R4FA-3).
     *
     * <p>A finished (DONE) trade is never read: a trade goes DONE only once both player files changed after the swap,
     * so a bag that reads from before it later is a file or folder put back (red-team findings AW-1, AW-2), and that
     * reopens nothing. A plugin reload ({@code boot} false) can't tell the disk from the live bags, so it reads and
     * writes nothing: one WARN per PENDING trade, from what its row says. Nothing is released, voided or taken on its
     * own: an admin looks ({@code /hcm trades show}) and settles it. Money is named, with each side's balance just
     * before the swap and, from {@code balance}, now (null when Vault can't say).
     *
     * @return how many trades are waiting
     */
    public int check(Consumer<String> warn, Marks marks, boolean boot, Function<UUID, Double> balance)
            throws SQLException {
        List<TradeDao.Trade> pending = trades.pending();
        for (TradeDao.Trade t : pending) {
            TradeDao.Trade row = t;
            Saved s = boot ? recorded(t) : state(t, marks);
            String again = "";
            if (boot && !UNMARKED.equals(t.reason()) && !PLAYED_ON.equals(t.reason())) {
                Saved now = s;
                String code;
                if (s == null) {
                    now = read(t, marks, false);
                    code = now.code();
                    if (playedOn(t, now, marks)) {
                        now = Saved.UNKNOWN;
                        code = PLAYED_ON;
                    }
                } else {
                    Boolean a = holds(marks, t.a(), t, true);
                    Boolean b = holds(marks, t.b(), t, true);
                    if (a != null && b != null) {
                        now = Saved.of(a, b);
                    } else if (against(s, Side.A, a) || against(s, Side.B, b)) {
                        now = Saved.UNKNOWN;
                    }
                    if (now != s) {
                        again = " (an earlier start read its bags as \"" + s.code() + "\", and " + (now == Saved.UNKNOWN
                                ? "one reads otherwise now while the other can't say, so look at both"
                                : "they read differently now: a world folder put back?") + ")";
                    }
                    code = now.code();
                }
                if (!code.equals(t.reason())) {
                    s = now;
                    try {
                        trades.pendingReason(t.id(), code);
                        row = trades.byId(t.id()).orElse(t);
                    } catch (SQLException e) {
                        warn.accept("Trade #" + t.id() + ": couldn't write where its bags are (" + e.getMessage()
                                + ").");
                    }
                }
            }
            warn.accept("Trade #" + t.id() + " between " + t.aName() + " and " + t.bName() + " didn't finish - "
                    + settleAdvice(row, s == null ? Saved.UNKNOWN : s) + money(t, trades.before(t.id()), balance)
                    + again + " (check /hcm trades show " + t.id() + ")");
        }
        return pending.size();
    }

    /** Whether one side's exact read ({@code now}, null for can't say) goes against what {@code s} says of it. */
    private static boolean against(Saved s, Side side, Boolean now) {
        Boolean said = s.holds(side);
        return now != null && said != null && !now.equals(said);
    }

    /**
     * Money a trade moved, which no settle pays back: what each side paid, with its balance just before the swap
     * ({@code before}) and now ({@code balance}), where they are known, so the admin can tell what really moved (a
     * stop can fall between a withdraw and its deposit; red-team finding TCR4-3).
     */
    static String money(TradeDao.Trade t, Double[] before, Function<UUID, Double> balance) {
        if (t.aMoney() <= 0 && t.bMoney() <= 0) {
            return "";
        }
        return "; money isn't paid back by a settle: " + paid(t.aName(), t.aMoney(), before == null ? null : before[0],
                balance.apply(t.a())) + ", " + paid(t.bName(), t.bMoney(), before == null ? null : before[1],
                balance.apply(t.b())) + " - check both balances and set right only what moved";
    }

    private static String paid(String name, double paid, Double before, Double now) {
        String had = before == null ? "" : "had " + dollars(before) + " just before";
        String has = now == null ? "" : "has " + dollars(now) + " now";
        String both = had.isEmpty() ? has : has.isEmpty() ? had : had + ", " + has;
        return name + " paid " + dollars(paid) + (both.isEmpty() ? "" : " (" + both + ")");
    }

    private static String dollars(double v) {
        return String.format(Locale.ROOT, "$%.2f", v);
    }

    /** A trade that was called off with something on the table (§4.12): its summaries, nothing moved. */
    public long cancelled(TradeDao.Trade row) throws SQLException {
        return trades.insert(row);
    }
}
