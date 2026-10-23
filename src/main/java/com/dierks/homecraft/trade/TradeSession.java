package com.dierks.homecraft.trade;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One trade's state (SHOP-V2-SPEC §4.4, §4.5), pure: two offers, two Ready flags, the change lock, the give-away
 * second tap and the countdown. {@code OPEN → COUNTDOWN → SWAPPING → DONE}, or {@code CANCELLED} from any state
 * before SWAPPING.
 *
 * <p><b>Escrow by reference.</b> An offer is a map from a bag slot to what was in it ({@link Entry}: a snapshot and
 * how many). Nothing here holds a real item: the real stacks stay in their owners' bags until the swap takes them
 * out, so a close, a quit or a crash has nothing to give back.
 *
 * <p><b>Any change</b> (an item on or off, an amount, money, a stack the sync took off) clears both Ready flags,
 * stops a countdown and starts the change lock, during which neither Ready works. At the countdown's end the phase
 * becomes SWAPPING before the swap runs, so a second timer, a click or a close in the same tick can't run it again
 * or cancel it ({@link #beginSwap}).
 *
 * @param <I> the item type: Bukkit's in the window, a test's stand-in in the tests
 */
public final class TradeSession<I> {

    /** Where a trade is. */
    public enum Phase { OPEN, COUNTDOWN, SWAPPING, DONE, CANCELLED }

    /** The two sides: A asked, B answered. */
    public enum Side {
        A, B;

        public Side other() {
            return this == A ? B : A;
        }
    }

    /**
     * Why a trade was called off (§4.5): each just cancels, because nothing has left a bag. The log row and the
     * message say which.
     */
    public enum Cause {
        /** A window closed (Esc, Cancel, another screen opened). */
        CLOSED("closed the trade"),
        QUIT("left the server"),
        KICKED("left the server"),
        DEATH("died"),
        TELEPORT("teleported away"),
        WORLD("changed world"),
        GAME_MODE("changed game mode"),
        /** The server stopped, reloaded, or Multiverse-Inventories disabled ({@code Menu.closeAll}). */
        STOP("the server stopped"),
        /** A window that didn't open (another plugin cancelled it). */
        NOT_OPENED("couldn't open the trade window");

        private final String words;

        Cause(String words) {
            this.words = words;
        }

        /** "closed the trade", "died": what the other player reads after the name. */
        public String words() {
            return words;
        }
    }

    /** One offered stack: what the bag slot held when it went on the table, and how many of it are offered. */
    public record Entry<I>(I snapshot, int amount) {
    }

    /** What a tap on Ready did. */
    public enum Ready {
        /** Not now: the trade is swapping or over. */
        CLOSED,
        /** The change lock is running. */
        LOCKED,
        /** Both tables are empty and nobody gives money. */
        EMPTY,
        /** Something on a table can't be traded (or T3). */
        PROBLEM,
        /** The other player's window isn't open. */
        OTHER_GONE,
        /** A give-away's first tap: tap again within the window to mean it. */
        ARMED,
        /** Ready; waiting for the other side. */
        READY,
        /** Both ready: the countdown started. */
        COUNTDOWN,
        /** Was ready, now isn't (stops a countdown). */
        UNREADY
    }

    /** The most stacks on one table (a 4 x 4 grid). */
    public static final int TABLE = 16;
    /** How long a give-away's first tap waits for the second (§4.4, red team O11). */
    public static final long GIVE_AWAY_MS = 5_000L;
    /**
     * How long after the first tap the second starts to count (red-team finding BR-1): a quick double tap, or a
     * Bedrock double that spans two ticks, lands before the yellow "Give it away? Tap again" tile has even been seen,
     * so it only asks again.
     */
    public static final long GIVE_AWAY_SETTLE_MS = 600L;

    private final UUID a;
    private final UUID b;
    private final long lockMs;
    private final int countdownSeconds;
    private final Map<Side, LinkedHashMap<Integer, Entry<I>>> offers = new EnumMap<>(Side.class);
    private final Map<Side, Double> money = new EnumMap<>(Side.class);
    private final Map<Side, Boolean> ready = new EnumMap<>(Side.class);
    private final Map<Side, Long> armedUntil = new EnumMap<>(Side.class);
    private final Map<Side, Long> armedAt = new EnumMap<>(Side.class);
    private Phase phase = Phase.OPEN;
    private Cause cause;
    private long lockUntil;
    private long countdownFrom;
    private int version;

    /**
     * @param lockMs           {@code trade.change_lock_ms}
     * @param countdownSeconds {@code trade.countdown_seconds}
     */
    public TradeSession(UUID a, UUID b, long lockMs, int countdownSeconds) {
        this.a = a;
        this.b = b;
        this.lockMs = Math.max(0, lockMs);
        this.countdownSeconds = Math.max(1, countdownSeconds);
        for (Side s : Side.values()) {
            offers.put(s, new LinkedHashMap<>());
            money.put(s, 0.0);
            ready.put(s, false);
            armedUntil.put(s, 0L);
            armedAt.put(s, 0L);
        }
    }

    public UUID player(Side s) {
        return s == Side.A ? a : b;
    }

    /** The side this player trades on, or null. */
    public Side side(UUID player) {
        return a.equals(player) ? Side.A : b.equals(player) ? Side.B : null;
    }

    public Phase phase() {
        return phase;
    }

    /** Open or counting down: things can still go on and off the table. */
    public boolean active() {
        return phase == Phase.OPEN || phase == Phase.COUNTDOWN;
    }

    /** Bumped by every change, so a window knows to repaint. */
    public int version() {
        return version;
    }

    // ---- the offers ----------------------------------------------------------------------------------

    /** One side's offer, in the order it went on the table (bag slot → entry). */
    public Map<Integer, Entry<I>> offer(Side s) {
        return Collections.unmodifiableMap(offers.get(s));
    }

    public Entry<I> entry(Side s, int slot) {
        return offers.get(s).get(slot);
    }

    public double money(Side s) {
        return money.get(s);
    }

    /** Whether this side gives anything: items or money. */
    public boolean gives(Side s) {
        return !offers.get(s).isEmpty() || money.get(s) > 0;
    }

    /** Nothing on either table and no money. */
    public boolean empty() {
        return !gives(Side.A) && !gives(Side.B);
    }

    /**
     * Put {@code amount} of what bag slot {@code slot} holds on the table (or change how many): a change. False when
     * the trade is past changing, the amount is under one, or the table is full and the slot isn't on it yet.
     */
    public boolean put(Side s, int slot, I snapshot, int amount, long now) {
        if (!active() || amount < 1 || snapshot == null) {
            return false;
        }
        LinkedHashMap<Integer, Entry<I>> offer = offers.get(s);
        Entry<I> old = offer.get(slot);
        if (old == null && offer.size() >= TABLE) {
            return false;
        }
        if (old != null && old.amount() == amount) {
            return false; // nothing changed
        }
        offer.put(slot, new Entry<>(snapshot, amount));
        changed(now);
        return true;
    }

    /** Take bag slot {@code slot}'s stack off the table: a change. False when it wasn't on it. */
    public boolean remove(Side s, int slot, long now) {
        if (!active() || offers.get(s).remove(slot) == null) {
            return false;
        }
        changed(now);
        return true;
    }

    /** Set how many of bag slot {@code slot}'s stack are on the table; 0 or less takes it off. */
    public boolean setAmount(Side s, int slot, int amount, long now) {
        Entry<I> e = offers.get(s).get(slot);
        if (e == null) {
            return false;
        }
        return amount < 1 ? remove(s, slot, now) : put(s, slot, e.snapshot(), amount, now);
    }

    /** Set this side's money (dollars, never below 0): a change when it moved. */
    public boolean setMoney(Side s, double amount, long now) {
        double v = Math.max(0, amount);
        if (!active() || v == money.get(s)) {
            return false;
        }
        money.put(s, v);
        changed(now);
        return true;
    }

    /**
     * A change to either side (§4.4): both Ready flags clear, a countdown stops, a give-away's first tap is
     * forgotten, and the change lock starts. Called by every edit, and by the window when the sync took something
     * off or the countdown's check failed.
     */
    public void changed(long now) {
        version++;
        for (Side s : Side.values()) {
            ready.put(s, false);
            armedUntil.put(s, 0L);
            armedAt.put(s, 0L);
        }
        if (phase == Phase.COUNTDOWN) {
            phase = Phase.OPEN;
        }
        lockUntil = now + lockMs;
    }

    // ---- Ready ---------------------------------------------------------------------------------------

    public boolean ready(Side s) {
        return ready.get(s);
    }

    /** The change lock is running: neither Ready works. */
    public boolean locked(long now) {
        return now < lockUntil;
    }

    /** This side's first give-away tap is waiting for the second. */
    public boolean armed(Side s, long now) {
        return now < armedUntil.get(s);
    }

    /** Whether this side would be giving something away: it gives, the other side gives nothing. */
    public boolean givingAway(Side s) {
        return gives(s) && !gives(s.other());
    }

    /**
     * A tap on this side's Ready (§4.4). Tapping it while ready un-readies (and stops a countdown). Otherwise it is
     * refused while the lock runs, with both tables empty, with a problem on a table, or with the other window
     * shut; a give-away needs a second tap within {@value #GIVE_AWAY_MS} ms, at least {@value #GIVE_AWAY_SETTLE_MS} ms
     * after the first (one sooner only asks again); and both ready starts the countdown.
     *
     * @param problem   something on a table can't be traded right now (a refusal, or T3)
     * @param otherOpen the other player's window is open
     */
    public Ready tapReady(Side s, long now, boolean problem, boolean otherOpen) {
        if (!active()) {
            return Ready.CLOSED;
        }
        if (ready.get(s)) {
            ready.put(s, false);
            if (phase == Phase.COUNTDOWN) {
                phase = Phase.OPEN;
            }
            version++;
            return Ready.UNREADY;
        }
        if (locked(now)) {
            return Ready.LOCKED;
        }
        if (empty()) {
            return Ready.EMPTY;
        }
        if (problem) {
            return Ready.PROBLEM;
        }
        if (!otherOpen) {
            return Ready.OTHER_GONE;
        }
        if (givingAway(s) && !armed(s, now)) {
            armedUntil.put(s, now + GIVE_AWAY_MS);
            armedAt.put(s, now);
            version++;
            return Ready.ARMED;
        }
        if (givingAway(s) && now - armedAt.get(s) < GIVE_AWAY_SETTLE_MS) {
            return Ready.ARMED; // the second half of a double tap: the question stands, nothing readies
        }
        armedUntil.put(s, 0L);
        armedAt.put(s, 0L);
        ready.put(s, true);
        version++;
        if (ready.get(s.other())) {
            phase = Phase.COUNTDOWN;
            countdownFrom = now;
            return Ready.COUNTDOWN;
        }
        return Ready.READY;
    }

    // ---- the countdown and the swap ----------------------------------------------------------------------

    /** Whole seconds left of the countdown (its first value is {@code countdown_seconds}); 0 once it is over. */
    public int secondsLeft(long now) {
        if (phase != Phase.COUNTDOWN) {
            return 0;
        }
        long left = countdownSeconds * 1000L - (now - countdownFrom);
        return left <= 0 ? 0 : (int) ((left + 999) / 1000);
    }

    /**
     * The swap may start: the countdown is over. Sets SWAPPING and answers true exactly once; every later call (a
     * second timer, a click, a close in the same tick) gets false.
     */
    public boolean beginSwap(long now) {
        if (phase != Phase.COUNTDOWN || secondsLeft(now) > 0) {
            return false;
        }
        phase = Phase.SWAPPING;
        version++;
        return true;
    }

    /** The swap couldn't happen: back to OPEN, both un-readied, and the change lock (it is a change). */
    public void swapFailed(long now) {
        if (phase == Phase.SWAPPING) {
            phase = Phase.OPEN;
            changed(now);
        }
    }

    /** The swap went through. */
    public void done() {
        if (phase == Phase.SWAPPING) {
            phase = Phase.DONE;
            version++;
        }
    }

    /**
     * Cancel (a close, a quit, a death, a teleport, a world or game-mode change, a stop): only before SWAPPING.
     * Nothing has left a bag, so there is nothing to give back.
     *
     * @return true when this call cancelled it
     */
    public boolean cancel(Cause why) {
        if (!active()) {
            return false;
        }
        phase = Phase.CANCELLED;
        cause = why;
        version++;
        return true;
    }

    /** Why it was cancelled, or null. */
    public Cause cause() {
        return cause;
    }
}
