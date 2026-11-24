package com.dierks.homecraft.games.chance.holdem;

import com.dierks.homecraft.games.ChanceRounds;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.Parlor;
import com.dierks.homecraft.games.Refusal;
import com.dierks.homecraft.games.cpu.CpuHoldem;
import com.dierks.homecraft.games.cpu.CpuLevel;
import com.dierks.homecraft.games.cpu.CpuPlayer;
import com.dierks.homecraft.games.cpu.TableNpcs;
import com.dierks.homecraft.util.Text;
import org.bukkit.entity.Player;

import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.random.RandomGenerator;

/**
 * One of the Game Parlor's Texas Hold'em tables (0.57.0): up to {@link #MAX_SEATS} players around one deck, playing
 * each other. The Arcade deals and keeps nothing: every chip put in goes back to someone at the table.
 *
 * <p><b>Three tables, one game.</b> The {@link Kind#FRIENDLY friendly} table plays free play chips: nothing goes in,
 * nothing comes out, and NPC players are welcome there (they sit in when someone has sat with too few to play, the way
 * they join at Twenty-One). The {@link Kind#TOKENS tokens} and {@link Kind#MONEY money} tables are played for stakes:
 * a player buys chips with tokens (one chip, one token) or, with the Parlor's money play, with dollars (one chip, one
 * dollar), through the play gate like any game of chance (the day's limits, Take a break, the buy-ins a day), and gets
 * up with whatever chips they have, paid back the same way. Real players only: an NPC never touches tokens or money, so
 * it never sits at a stakes table. One table is one kind for good, so every chip on it is worth the same.
 *
 * <p><b>Every chip is accounted for.</b> At a stakes table each seat's chips are its own crash-safe round in
 * {@link ChanceRounds}: the buy-in opens it (the tokens or dollars go in), and getting up closes it, paying back the
 * chips. In between, its data ({@link #data}) holds the seat's chips as of the end of the last hand. Chips only ever
 * move between seats, so every write that moves them is one transaction over every seat it touches
 * ({@code GamesDao.updateRounds}): the sum across the table's rounds is the same before and after each one. A hand in
 * play lives only in memory: if the server stops mid-hand, every round still says what its seat had before the hand,
 * so the exit rule ({@link #settleOnExit}) gives everyone exactly that back and the hand is as if it never happened. A
 * stack is never lost and never paid twice: a round pays once, when it closes, whoever closes it.
 *
 * <p>A player who gets up (or leaves the server) mid-hand leaves the chips they put in in the pot, as at any real table.
 * Those chips have to be somewhere a crash would find them, so one write moves them out of the leaver's round (which is
 * then paid what they had behind) and shares them, as a {@code carry}, among the seats still in the hand. If the hand
 * then finishes, its result replaces the carries; if the server stops first, the carries go back with each seat's own
 * chips.
 *
 * <p><b>The pace</b> is Twenty-One's: nobody is on a clock while they are at the screen, until someone taps Hurry Up
 * ({@link #HURRY_MS}); a player who closed the screen mid-hand is away, and the table checks or folds for them after
 * {@code away_seconds}. Away seats get up when the hand is over. The screen's Leave between hands gets you up at once.
 */
public final class HoldemTable {

    /** The most players at a table. */
    public static final int MAX_SEATS = 6;
    /** How long a seat has once someone taps Hurry Up. */
    public static final long HURRY_MS = 30_000;
    /** How long a table with enough players waits for one more before it deals. */
    static final long GATHER_MS = 3_000;
    /** How often a stakes table touches its rounds, so the ten-minute sweep leaves a slow table alone. */
    static final long TOUCH_MS = 60_000;
    /** An NPC whose turn has waited this long (the NPC players stopped) checks or folds. */
    static final long NPC_STALL_MS = 15_000;
    /** The data format of a seat's round. */
    static final int VERSION = 1;
    /** What a seat's round's data starts with. */
    static final String PREFIX = "holdem:" + VERSION;

    /** What a table is played for. */
    public enum Kind {
        FRIENDLY("friendly", "Friendly table"),
        TOKENS("tokens", "Tokens table"),
        MONEY("money", "Money table");

        public final String key;
        public final String label;

        Kind(String key, String label) {
            this.key = key;
            this.label = label;
        }

        /** Played for tokens or money (a buy-in and a cash-out), not free chips. */
        public boolean stakes() {
            return this != FRIENDLY;
        }

        /** The kind with this key, or {@code null}. */
        public static Kind of(String key) {
            for (Kind k : values()) {
                if (k.key.equalsIgnoreCase(key)) {
                    return k;
                }
            }
            return null;
        }
    }

    /** Where the table is. */
    public enum Phase {
        /** No hand: waiting for at least two players with chips. */
        WAITING,
        /** A hand is being played. */
        PLAYING,
        /** A hand just ended: its result stays on show for {@code pause_seconds}. */
        BETWEEN
    }

    /** One seat. */
    public static final class Seat {
        private final UUID id;
        private String name;
        private final int place;
        /** Chips as of the end of the last hand (in a hand, {@link HoldemHand.Player#stack()} is what's behind). */
        private int stack;
        private CpuPlayer npc;
        private CpuLevel plays = CpuLevel.MEDIUM;
        private long actAt;
        /** Closed the screen mid-hand: the table checks or folds for them, and they get up when the hand ends. */
        private boolean away;
        private long awayActAt;
        private boolean sittingOut;
        /** The seat's round at a stakes table, or {@code null} (not bought in, the friendly table, an NPC). */
        private ChanceRounds.Round round;
        /** Chips left in this hand's pot by players who got up, kept in this seat's round until the hand ends. */
        private int carry;
        /** Its place in the hand being played (or the last one, while its result is shown), or -1. */
        private int inHand = -1;

        private Seat(UUID id, String name, int place) {
            this.id = id;
            this.name = name;
            this.place = place;
        }

        public UUID id() {
            return id;
        }

        public String name() {
            return name;
        }

        /** 0-5: where it is around the table. */
        public int place() {
            return place;
        }

        /** Chips as of the end of the last hand. */
        public int stack() {
            return stack;
        }

        public CpuPlayer npc() {
            return npc;
        }

        public CpuLevel plays() {
            return plays;
        }

        public boolean away() {
            return away;
        }

        public boolean sittingOut() {
            return sittingOut;
        }

        /** Bought in at a stakes table (its round is open). */
        public boolean boughtIn() {
            return round != null;
        }

        /** What its round put in (its buy-in), or 0. */
        public int buyIn() {
            return round == null ? 0 : round.stake();
        }

        public int inHand() {
            return inHand;
        }
    }

    private final Holdem game;
    private final Kind kind;
    private final Seat[] places = new Seat[MAX_SEATS];
    private final Map<UUID, Runnable> screens = new LinkedHashMap<>();
    private final Set<UUID> watchers = new HashSet<>();
    private RandomGenerator shuffler = new SecureRandom();
    /** A test's own deck for the next hand, or {@code null} (a fresh shuffle). */
    private int[] nextDeck;
    private HoldemHand hand;
    private Seat[] handSeats = new Seat[0];
    private int handNo;
    private int buttonPlace = -1;
    private Phase phase = Phase.WAITING;
    private long nextAt;
    private long dealAt;
    private long turnAt;
    private long hurryUntil;
    private String hurryBy;
    private long touchedAt;
    private final List<String> lastResult = new ArrayList<>();
    /** Real players who sent an NPC away: no NPC joins them again until they get up. */
    private final Set<UUID> sentAway = new HashSet<>();
    private long npcJoinAt;
    private TableNpcs npcs;

    HoldemTable(Holdem game, Kind kind) {
        this.game = game;
        this.kind = kind;
    }

    /** For tests: the next hand is dealt from {@code deck} (card numbers, 52 of them). */
    public void nextDeck(int[] deck) {
        this.nextDeck = deck.clone();
    }

    /** For tests: shuffle with this generator. */
    public void shuffler(RandomGenerator random) {
        this.shuffler = random;
    }

    private GamesService games() {
        return game.games();
    }

    private HoldemSettings settings() {
        return game.settings();
    }

    private long now() {
        return games().clock().nowMillis();
    }

    // ---- what it shows ------------------------------------------------------------------------------------

    public Kind kind() {
        return kind;
    }

    public Phase phase() {
        return phase;
    }

    /** The hand being played, or the last one (its result on show), or {@code null}. */
    public HoldemHand hand() {
        return hand;
    }

    public int handNo() {
        return handNo;
    }

    /** The seat at each place, {@code null} where it is free. */
    public List<Seat> places() {
        return java.util.Arrays.asList(places.clone());
    }

    /** Every seat taken, by place. */
    public List<Seat> seats() {
        List<Seat> out = new ArrayList<>();
        for (Seat s : places) {
            if (s != null) {
                out.add(s);
            }
        }
        return out;
    }

    public Seat seat(UUID id) {
        for (Seat s : places) {
            if (s != null && s.id.equals(id)) {
                return s;
            }
        }
        return null;
    }

    /** Whether {@code id} is watching (a full table: they see everything but the cards still face down). */
    public boolean watching(UUID id) {
        return watchers.contains(id);
    }

    /** The seat whose turn it is, or {@code null}. */
    public Seat turn() {
        if (phase != Phase.PLAYING || hand == null || hand.over()) {
            return null;
        }
        int t = hand.toAct();
        return t >= 0 && t < handSeats.length ? handSeats[t] : null;
    }

    /** The seat at a hand's index, or {@code null} (they got up). */
    public Seat atHand(int i) {
        return i >= 0 && i < handSeats.length ? handSeats[i] : null;
    }

    /** The seat's player in the hand, or {@code null} (not dealt in). */
    public HoldemHand.Player inHand(Seat s) {
        return s == null || hand == null || s.inHand < 0 || s.inHand >= hand.size() || atHand(s.inHand) != s
                ? null : hand.player(s.inHand);
    }

    /** The seat's chips now: behind, while it plays a hand. */
    public int chips(Seat s) {
        HoldemHand.Player p = phase == Phase.PLAYING ? inHand(s) : null;
        return p != null ? p.stack() : s.stack;
    }

    /** The last hand's result lines, as chat had them. */
    public List<String> lastResult() {
        return List.copyOf(lastResult);
    }

    /** Seconds until the next hand is dealt, or -1 when none is coming. */
    public int nextSeconds() {
        long at = phase == Phase.BETWEEN ? nextAt : phase == Phase.WAITING && dealAt > 0 ? dealAt : 0;
        return at <= 0 ? -1 : (int) Math.max(0, (at - now() + 999) / 1000);
    }

    /** Whether the table is waiting on this real seat's move. */
    public boolean waitingOn(Seat s) {
        return s != null && s.npc == null && s == turn();
    }

    /** Whether {@code s} may tap Hurry Up now: the table waits on another real player at the screen. */
    public boolean canHurry(Seat s) {
        Seat t = turn();
        return s != null && s.npc == null && hurryUntil == 0 && t != null && t != s && t.npc == null && !t.away;
    }

    /** Seconds left on the Hurry Up clock, or -1 when it isn't running. */
    public int hurrySeconds() {
        return hurryUntil <= 0 ? -1 : (int) Math.max(0, (hurryUntil - now() + 999) / 1000);
    }

    /** Who tapped Hurry Up, or {@code null}. */
    public String hurryBy() {
        return hurryUntil <= 0 ? null : hurryBy;
    }

    /** "34 chips", or "$34" at the money table. */
    public String chipsText(int n) {
        return kind == Kind.MONEY ? Parlor.dollars(n) : n + " chip" + (n == 1 ? "" : "s");
    }

    /** "34 tokens", "$34" or "34 play chips": what a number of chips is worth here. */
    public String worth(int n) {
        return switch (kind) {
            case MONEY -> Parlor.dollars(n);
            case TOKENS -> n + " token" + (n == 1 ? "" : "s");
            case FRIENDLY -> n + " play chip" + (n == 1 ? "" : "s");
        };
    }

    // ---- sitting down and getting up ----------------------------------------------------------------------

    /**
     * Sit {@code player} down (or bring them back to their seat), with {@code refresh} run whenever the table changes.
     * At the friendly table they get their play chips at once; at a stakes table they sit with none until they buy in.
     *
     * @return their seat, or {@code null} when every seat is taken (they may watch instead)
     */
    public Seat sit(Player player, Runnable refresh) {
        UUID id = player.getUniqueId();
        Seat s = seat(id);
        if (s == null) {
            int free = freePlace();
            if (free < 0 && giveWay()) {
                free = freePlace();
            }
            if (free < 0) {
                return null;
            }
            if (kind.stakes()) {
                // chips of theirs left from before (a reload in the middle of a sitting) are cashed out first
                ChanceRounds.Round old = games().rounds().openRound(id, game.id());
                if (old != null) {
                    games().rounds().finishNow(old);
                }
            }
            if (!hasReal()) {
                lastResult.clear();
            }
            s = new Seat(id, player.getName(), free);
            if (kind == Kind.FRIENDLY) {
                s.stack = settings().friendlyChips();
            }
            places[free] = s;
        }
        s.name = player.getName();
        s.away = false;
        s.awayActAt = 0;
        watchers.remove(id);
        if (refresh != null) {
            screens.put(id, refresh);
        }
        changed();
        return s;
    }

    /** {@code player} watches (the table is full): they see the table and the chat, not anyone's face-down cards. */
    public void watch(Player player, Runnable refresh) {
        watchers.add(player.getUniqueId());
        if (refresh != null) {
            screens.put(player.getUniqueId(), refresh);
        }
    }

    private int freePlace() {
        for (int i = 0; i < MAX_SEATS; i++) {
            if (places[i] == null) {
                return i;
            }
        }
        return -1;
    }

    /**
     * {@code id}'s screen closed. In a hand they are still in, they keep the seat (away) and the table checks or folds
     * for them; otherwise they get up now, with their chips.
     */
    public void leave(UUID id) {
        screens.remove(id);
        watchers.remove(id);
        Seat s = seat(id);
        if (s == null || s.npc != null) {
            return;
        }
        sentAway.remove(id);
        HoldemHand.Player p = phase == Phase.PLAYING ? inHand(s) : null;
        if (p != null && !p.folded()) {
            s.away = true;
            s.awayActAt = 0;
        } else {
            standUp(s, true);
        }
        changed();
    }

    /** {@code id} left the server: out of the hand (their chips in it stay in the pot) and up, with the rest. */
    public void quit(UUID id) {
        screens.remove(id);
        watchers.remove(id);
        sentAway.remove(id);
        Seat s = seat(id);
        if (s == null || s.npc != null) {
            return;
        }
        standUp(s, false);
        changed();
    }

    /**
     * {@code s} gets up: out of any hand they are in (folded; their chips in it stay in the pot), then paid back what
     * they have behind (a stakes table), and the seat is free.
     */
    private void standUp(Seat s, boolean tell) {
        HoldemHand.Player p = phase == Phase.PLAYING ? inHand(s) : null;
        if (p != null && !hand.over()) {
            hand.forceFold(s.inHand);
            if (hand.over()) {
                finishHand(); // that was the last one against the winner: the hand ends, and every seat is written
                p = null;
            }
        }
        if (kind.stakes() && s.round != null && p != null) {
            leaveChips(s, p);
        }
        if (s.round != null) {
            cashOut(s, tell);
        }
        for (int i = 0; i < handSeats.length; i++) {
            if (handSeats[i] == s) {
                handSeats[i] = null;
            }
        }
        if (places[s.place] == s) {
            places[s.place] = null;
        }
        if (turn() == null && phase == Phase.PLAYING && hand != null && hand.over()) {
            finishHand();
        }
    }

    /**
     * A stakes seat gets up mid-hand: what it put in this hand (and any carry it held) stays in the pot, so in one write
     * its round keeps only what it has behind, and those chips go as carries to the seats still in the hand.
     */
    private void leaveChips(Seat s, HoldemHand.Player p) {
        int left = p.total() + s.carry;
        List<Seat> heirs = new ArrayList<>();
        for (Seat o : handSeats) {
            HoldemHand.Player op = o == null || o == s ? null : inHand(o);
            if (op != null && o.round != null && !op.folded()) {
                heirs.add(o);
            }
        }
        if (heirs.isEmpty()) {
            for (Seat o : handSeats) {
                if (o != null && o != s && o.round != null) {
                    heirs.add(o); // everyone left in it folded too: the chips still go to the table, not out of it
                }
            }
        }
        Map<Seat, int[]> writes = new LinkedHashMap<>();
        if (heirs.isEmpty() || left <= 0) {
            writes.put(s, new int[] {heirs.isEmpty() ? s.stack + s.carry : p.stack(), 0});
        } else {
            writes.put(s, new int[] {p.stack(), 0});
            int each = left / heirs.size();
            int odd = left % heirs.size();
            for (int k = 0; k < heirs.size(); k++) {
                Seat h = heirs.get(k);
                writes.put(h, new int[] {h.stack, h.carry + each + (k < odd ? 1 : 0)});
            }
        }
        if (!write(writes)) {
            recover(true);
        }
    }

    /** Close the seat's round, paying back its chips; tell the player what came back. */
    private void cashOut(Seat s, boolean tell) {
        ChanceRounds.Round r = s.round;
        if (r == null) {
            return;
        }
        int chips = s.stack + s.carry;
        s.round = null;
        s.carry = 0;
        boolean paid = games().rounds().close(r, chips, data(chips, 0));
        s.stack = 0;
        Player p = games().online(s.id);
        if (paid && p != null) {
            p.sendMessage(Text.of(cashOutLine(kind, chips, r.stake())));
        }
    }

    /** "Hold'em: you got up with 57 chips: 57 tokens back (40 in)." */
    static String cashOutLine(Kind kind, int chips, int in) {
        String unit = kind == Kind.MONEY ? Parlor.dollars(chips) : chips + " token" + (chips == 1 ? "" : "s");
        String put = kind == Kind.MONEY ? Parlor.dollars(in) : Integer.toString(in);
        if (chips <= 0) {
            return "&6Hold'em: &7your chips are gone this time (&f" + put + " &7in).";
        }
        return "&6Hold'em: &7you got up with &f" + chips + " chip" + (chips == 1 ? "" : "s") + "&7: &6" + unit
                + " &7back (&f" + put + " &7in).";
    }

    // ---- buying in, play chips, sitting out ---------------------------------------------------------------

    /**
     * {@code player}, sitting at a stakes table with no chips, buys {@code amount} chips: the play gate runs (the day's
     * limits, Take a break, the buy-ins a day, the balance) and the tokens or dollars go in as the seat's round.
     *
     * @return whether they bought in (a refusal has been told)
     */
    public boolean buyIn(Player player, int amount) {
        Seat s = seat(player.getUniqueId());
        if (s == null || !kind.stakes() || s.round != null || s.stack > 0 || amount <= 0) {
            return false;
        }
        if (kind == Kind.TOKENS && !settings().buyIns().contains(amount)) {
            return false;
        }
        if (inHand(s) != null && phase == Phase.PLAYING) {
            return false; // still in the hand on the table
        }
        ChanceRounds.Round r = games().rounds().open(player, game, amount, data(amount, 0), kind == Kind.MONEY);
        if (r == null) {
            return false;
        }
        s.round = r;
        s.stack = amount;
        s.carry = 0;
        s.sittingOut = false;
        touchedAt = now();
        changed();
        return true;
    }

    /** At the friendly table, a player with no chips left takes another free stack. */
    public boolean moreChips(Player player) {
        Seat s = seat(player.getUniqueId());
        if (s == null || kind != Kind.FRIENDLY || s.stack > 0 || (phase == Phase.PLAYING && inHand(s) != null)) {
            return false;
        }
        s.stack = settings().friendlyChips();
        s.sittingOut = false;
        changed();
        return true;
    }

    /** {@code player} sits out the next hands (keeping their seat and chips), or comes back in. */
    public void sitOut(Player player, boolean out) {
        Seat s = seat(player.getUniqueId());
        if (s != null && s.sittingOut != out) {
            s.sittingOut = out;
            changed();
        }
    }

    // ---- the moves ----------------------------------------------------------------------------------------

    /** {@code player}'s seat if it is their turn, else {@code null}. */
    private Seat mine(Player player) {
        Seat s = seat(player.getUniqueId());
        return s != null && s == turn() ? s : null;
    }

    public boolean fold(Player player) {
        Seat s = mine(player);
        if (s == null || hand.canCheck(s.inHand)) {
            return false; // nothing to call: a fold would only throw a free card away
        }
        hand.fold(s.inHand);
        moved();
        return true;
    }

    public boolean checkCall(Player player) {
        Seat s = mine(player);
        if (s == null) {
            return false;
        }
        hand.checkCall(s.inHand);
        moved();
        return true;
    }

    /** Raise to {@code to} chips this round (clamped to what is allowed). */
    public boolean raiseTo(Player player, int to) {
        Seat s = mine(player);
        if (s == null || !hand.canRaise(s.inHand)) {
            return false;
        }
        int i = s.inHand;
        int target = Math.max(hand.minRaiseTo(i), Math.min(hand.maxRaiseTo(i), to));
        if (!hand.raiseTo(i, target)) {
            return false;
        }
        moved();
        return true;
    }

    public boolean allIn(Player player) {
        Seat s = mine(player);
        if (s == null) {
            return false;
        }
        hand.allIn(s.inHand);
        moved();
        return true;
    }

    /** Hurry Up: whoever the table is waiting on has {@link #HURRY_MS} from now. */
    public void hurry(Player by) {
        Seat s = seat(by.getUniqueId());
        if (!canHurry(s)) {
            return;
        }
        hurryUntil = now() + HURRY_MS;
        hurryBy = s.name;
        changed();
    }

    /** After any move: the clocks start over, and a hand that is over is settled. */
    private void moved() {
        clearHurry();
        turnAt = now();
        for (Seat s : places) {
            if (s != null) {
                s.awayActAt = 0;
                s.actAt = 0;
            }
        }
        if (hand.over()) {
            finishHand();
        }
        changed();
    }

    // ---- once a second ------------------------------------------------------------------------------------

    /**
     * Once a second, from the game: players gone from the server, the away and Hurry Up clocks, the NPC players (when
     * {@code npcs} is not {@code null}: they are on), the pause after a hand, the next deal, and the touch that keeps a
     * slow table's rounds from being swept.
     */
    public void tick(TableNpcs npcs) {
        this.npcs = npcs;
        long now = now();
        for (Seat s : seats()) {
            if (s.npc == null && games().online(s.id) == null) {
                quit(s.id);
            }
        }
        if (kind == Kind.FRIENDLY) {
            npcSeats(npcs, now);
        } else {
            for (Seat s : seats()) {
                if (s.npc != null && phase != Phase.PLAYING) {
                    places[s.place] = null; // never at a stakes table (only a bug could put one there)
                }
            }
        }
        switch (phase) {
            case PLAYING -> turnClock(now);
            case BETWEEN -> {
                if (now >= nextAt) {
                    tidy();
                    phase = Phase.WAITING;
                    dealAt = 0;
                    changed();
                    tryDeal(now);
                }
            }
            case WAITING -> tryDeal(now);
        }
        if (kind.stakes() && now - touchedAt >= TOUCH_MS) {
            touchedAt = now;
            touch();
        }
    }

    /** The clock on the seat whose turn it is. */
    private void turnClock(long now) {
        Seat t = turn();
        if (t == null) {
            return;
        }
        if (t.npc != null) {
            if (npcs != null) {
                if (t.actAt == 0) {
                    t.actAt = now + Math.max(800, Math.min(2500, t.npc.personality().thinkMs
                            + npcs.rng().nextInt(700)));
                } else if (now >= t.actAt) {
                    npcMove(t, npcs);
                }
            } else if (now - turnAt >= NPC_STALL_MS) {
                hand.autoAct(t.inHand); // the NPC players were switched off mid-hand
                moved();
            }
            return;
        }
        boolean away = t.away || !screens.containsKey(t.id);
        if (away) {
            if (t.awayActAt == 0) {
                t.awayActAt = now + settings().awaySeconds() * 1000L;
                changed();
            } else if (now >= t.awayActAt) {
                hand.autoAct(t.inHand);
                moved();
            }
            return;
        }
        if (hurryUntil > 0) {
            if (now >= hurryUntil) {
                hand.autoAct(t.inHand); // the clock ran out: a check when it's free, else a fold
                moved();
            } else {
                changed(); // the countdown
            }
        }
    }

    /** Between hands: away and gone players get up, NPCs with no chips leave, everyone's last hand is put away. */
    private void tidy() {
        for (Seat s : seats()) {
            s.inHand = -1;
            if (s.npc != null) {
                if (s.stack <= 0) {
                    places[s.place] = null;
                }
            } else if (s.away || !screens.containsKey(s.id)) {
                Player p = games().online(s.id);
                if (p != null && s.away) {
                    p.sendMessage(Text.of("&7Hold'em: you were away from the table, so you got up."));
                }
                standUp(s, true);
            }
        }
        handSeats = new Seat[0];
    }

    /** Who would be dealt in now: chips, not sitting out, and (a real player) at the screen. */
    private List<Seat> ready() {
        List<Seat> out = new ArrayList<>();
        for (Seat s : places) {
            if (s == null || s.stack <= 0 || s.sittingOut) {
                continue;
            }
            if (s.npc == null && (s.away || !screens.containsKey(s.id) || games().online(s.id) == null)) {
                continue;
            }
            if (kind.stakes() && (s.npc != null || s.round == null)) {
                continue;
            }
            out.add(s);
        }
        return out;
    }

    private void tryDeal(long now) {
        List<Seat> in = ready();
        int real = 0;
        for (Seat s : in) {
            real += s.npc == null ? 1 : 0;
        }
        if (in.size() < 2 || real == 0) {
            if (dealAt != 0) {
                dealAt = 0;
                changed();
            }
            return;
        }
        if (dealAt == 0) {
            dealAt = now + GATHER_MS;
            changed();
            return;
        }
        if (now < dealAt) {
            return;
        }
        if (kind.stakes() && !stakesMayDeal(in)) {
            return; // someone was stood up: the next second looks again
        }
        deal(in, now);
    }

    /**
     * Before a stakes hand: a restart minutes away cashes everyone out; a player whose break started (or who may no
     * longer play here) gets up, with their chips.
     */
    private boolean stakesMayDeal(List<Seat> in) {
        Refusal held = games().restartRefusal();
        if (held != null) {
            say("&7Hold'em: the server restarts soon, so the table is cashed out.");
            for (Seat s : seats()) {
                standUp(s, true);
            }
            changed();
            return false;
        }
        boolean ok = true;
        for (Seat s : in) {
            Player p = games().online(s.id);
            Refusal r = p == null ? Refusal.CLOSED : games().gate().open(p, game);
            if (kind == Kind.MONEY && r == null && p != null) {
                r = games().parlor().allowed(p);
            }
            if (r != null) {
                if (p != null && !r.silent()) {
                    games().tell(p, r);
                }
                standUp(s, true);
                ok = false;
            }
        }
        if (!ok) {
            changed();
        }
        return ok;
    }

    private void deal(List<Seat> in, long now) {
        // the button moves to the next player dealt in after the last one
        int button = 0;
        for (int k = 1; k <= MAX_SEATS; k++) {
            int place = (buttonPlace + k + MAX_SEATS) % MAX_SEATS;
            int at = indexOfPlace(in, place);
            if (at >= 0) {
                button = at;
                break;
            }
        }
        int[] stacks = new int[in.size()];
        for (int i = 0; i < in.size(); i++) {
            stacks[i] = in.get(i).stack;
        }
        int[] deck = nextDeck != null ? nextDeck : PokerCard.shuffled(shuffler);
        nextDeck = null;
        HoldemSettings st = settings();
        handNo++;
        hand = new HoldemHand(stacks, button, st.smallBlind(), st.bigBlind(), deck);
        handSeats = in.toArray(new Seat[0]);
        for (Seat s : places) {
            if (s != null) {
                s.inHand = -1;
                s.actAt = 0;
                s.awayActAt = 0;
            }
        }
        for (int i = 0; i < handSeats.length; i++) {
            Seat s = handSeats[i];
            s.inHand = i;
            if (s.npc != null && npcs != null) {
                s.plays = npcs.level(s.npc, realIds(), game.id());
            }
        }
        buttonPlace = handSeats[button].place;
        phase = Phase.PLAYING;
        dealAt = 0;
        turnAt = now;
        clearHurry();
        lastResult.clear();
        if (kind.stakes()) {
            // every round says which hand it is in (and is touched): one write, or the deal is called off
            Map<Seat, int[]> writes = new LinkedHashMap<>();
            for (Seat s : handSeats) {
                writes.put(s, new int[] {s.stack, s.carry});
            }
            if (!write(writes)) {
                recover(false); // nobody has seen the cards yet
                return;
            }
            touchedAt = now;
        }
        if (hand.over()) {
            finishHand(); // everyone all in on the blinds: dealt straight out
        }
        changed();
    }

    private static int indexOfPlace(List<Seat> in, int place) {
        for (int i = 0; i < in.size(); i++) {
            if (in.get(i).place == place) {
                return i;
            }
        }
        return -1;
    }

    /** The hand is over: every seat's chips are what the hand left them, written at once; the result is told. */
    private void finishHand() {
        if (phase != Phase.PLAYING || hand == null) {
            return;
        }
        phase = Phase.BETWEEN; // first: nothing below may finish it twice
        nextAt = now() + settings().pauseSeconds() * 1000L;
        clearHurry();
        Map<Seat, int[]> writes = new LinkedHashMap<>();
        for (int i = 0; i < handSeats.length; i++) {
            Seat s = handSeats[i];
            if (s == null) {
                continue;
            }
            s.stack = hand.player(i).stack();
            if (kind.stakes() && s.round != null) {
                writes.put(s, new int[] {s.stack, 0});
            }
        }
        if (!writes.isEmpty() && !write(writes)) {
            recover(true);
            return;
        }
        for (Seat s : places) {
            if (s != null) {
                s.carry = 0;
            }
        }
        lastResult.clear();
        lastResult.addAll(resultLines());
        for (String line : lastResult) {
            say(line);
        }
        if (kind.stakes()) {
            for (Seat s : seats()) {
                if (s.round != null && s.stack <= 0) {
                    cashOut(s, true); // no chips left: the round is over (a new buy-in is a new one)
                }
            }
        }
        changed();
    }

    /** What chat says about a hand that just ended: who took each pot, with what. */
    List<String> resultLines() {
        List<String> out = new ArrayList<>();
        if (hand == null) {
            return out;
        }
        List<HoldemHand.Pot> pots = hand.pots();
        if (hand.street() == HoldemHand.Street.FOLDED) {
            HoldemHand.Pot pot = pots.get(0);
            out.add("&6Hold'em: &f" + nameAt(pot.winners().get(0)) + " &7takes the pot of &6"
                    + chipsText(pot.amount()) + "&7. Everyone else folded.");
            return out;
        }
        for (int i = 0; i < hand.size(); i++) {
            HoldemHand.Player p = hand.player(i);
            if (!p.folded() && p.value() != null) {
                out.add("&7" + nameAt(i) + ": &f" + p.hole().get(0).shortName() + " " + p.hole().get(1).shortName()
                        + " &8- &f" + p.value().name() + " &8(" + p.value().cardsText() + ")");
            }
        }
        int contested = 0;
        for (HoldemHand.Pot pot : pots) {
            if (pot.eligible().size() > 1) {
                contested++;
            }
        }
        int side = 0;
        for (int k = 0; k < pots.size(); k++) {
            HoldemHand.Pot pot = pots.get(k);
            if (pot.eligible().size() == 1) {
                int w = pot.winners().get(0);
                if (k == pots.size() - 1 && unmatched(w) == pot.amount()) {
                    // nobody else put in that much: those chips simply go back
                    out.add("&7" + nameAt(w) + " gets &6" + chipsText(pot.amount())
                            + " &7back (nobody else could match it).");
                } else {
                    out.add("&6Hold'em: &f" + nameAt(w) + " &7takes a side pot of &6" + chipsText(pot.amount())
                            + "&7. Everyone else in it folded.");
                }
                continue;
            }
            String which = contested == 1 ? "the pot of " : side++ == 0 ? "the main pot of " : "a side pot of ";
            String made = hand.player(pot.winners().get(0)).value().name();
            if (pot.winners().size() == 1) {
                out.add("&6Hold'em: &f" + nameAt(pot.winners().get(0)) + " &7wins " + which + "&6"
                        + chipsText(pot.amount()) + " &7with &f" + made + "&7.");
            } else {
                List<String> names = new ArrayList<>();
                for (int w : pot.winners()) {
                    names.add("&f" + nameAt(w) + "&7");
                }
                out.add("&6Hold'em: " + String.join(" and ", names) + " split " + which + "&6"
                        + chipsText(pot.amount()) + " &7(&f" + made + "&7, the same hand).");
            }
        }
        return out;
    }

    /** What player {@code w} put in this hand beyond anyone else's most. */
    private int unmatched(int w) {
        int others = 0;
        for (int j = 0; j < hand.size(); j++) {
            if (j != w) {
                others = Math.max(others, hand.player(j).total());
            }
        }
        return Math.max(0, hand.player(w).total() - others);
    }

    private String nameAt(int i) {
        Seat s = atHand(i);
        return s == null ? "A player who left" : com.dierks.homecraft.games.cpu.CpuRoster.isCpu(s.id) ? plainName(s)
                : s.name;
    }

    /** An NPC's name without its colours, for a chat line. */
    private static String plainName(Seat s) {
        return s.name.replaceAll("(?i)[&§][0-9a-fk-or]", "");
    }

    // ---- the rounds ---------------------------------------------------------------------------------------

    /** A seat's round's data: its chips as of the end of the last hand, and any carry. */
    String data(int stack, int carry) {
        return PREFIX + ";t=" + kind.key + ";s=" + stack + ";c=" + carry + ";h=" + handNo;
    }

    /**
     * The exit rule: a seat's round finished anywhere but at the table (the player left while the game was off, the
     * server stopped, the ten-minute sweep, a switch-off) pays back its chips as of the end of the last hand, plus any
     * carry: a hand in play is called off.
     */
    public static int settleOnExit(long seed, int stake, String data) {
        int[] sc = chipsOf(data);
        return Math.max(0, sc[0] + sc[1]);
    }

    /** {stack, carry} from a seat's round's data. */
    static int[] chipsOf(String data) {
        if (data == null || !data.startsWith(PREFIX + ";")) {
            throw new IllegalArgumentException("not a Hold'em seat: " + data);
        }
        Integer s = null;
        int c = 0;
        for (String part : data.split(";")) {
            if (part.startsWith("s=")) {
                s = Integer.parseInt(part.substring(2));
            } else if (part.startsWith("c=")) {
                c = Integer.parseInt(part.substring(2));
            }
        }
        if (s == null || s < 0 || c < 0) {
            throw new IllegalArgumentException("a Hold'em seat with no chips written: " + data);
        }
        return new int[] {s, c};
    }

    /**
     * Write these seats' chips and carries, all at once.
     *
     * @return false when one of their rounds was finished somewhere else (nothing written), or the database failed
     */
    private boolean write(Map<Seat, int[]> writes) {
        Map<Long, String> data = new LinkedHashMap<>();
        Map<Seat, String> bySeat = new LinkedHashMap<>();
        for (Map.Entry<Seat, int[]> e : writes.entrySet()) {
            Seat s = e.getKey();
            if (s.round == null) {
                continue;
            }
            String d = data(e.getValue()[0], e.getValue()[1]);
            data.put(s.round.id(), d);
            bySeat.put(s, d);
        }
        if (data.isEmpty()) {
            return true;
        }
        try {
            if (!games().dao().updateRounds(data, now())) {
                return false;
            }
        } catch (SQLException e) {
            games().logger().log(Level.SEVERE, "Hold'em could not write the " + kind.key + " table's chips", e);
            return false;
        }
        for (Map.Entry<Seat, String> e : bySeat.entrySet()) {
            Seat s = e.getKey();
            int[] v = writes.get(s);
            s.stack = v[0];
            s.carry = v[1];
            s.round = s.round.with(e.getValue(), s.round.stake());
        }
        return true;
    }

    /** Touch every open round with what it already says (the sweep leaves a slow table alone). */
    private void touch() {
        Map<Seat, int[]> writes = new LinkedHashMap<>();
        for (Seat s : places) {
            if (s != null && s.round != null) {
                writes.put(s, new int[] {s.stack, s.carry});
            }
        }
        if (!writes.isEmpty() && !write(writes)) {
            recover(phase == Phase.PLAYING);
        }
    }

    /**
     * A round was finished somewhere else (the sweep, an admin, the game switched off and on): the hand in play is
     * called off, and every seat is put back to what its round says now. A seat whose round is finished was paid by
     * it, and gets up.
     */
    private void recover(boolean hadHand) {
        List<Seat> keep = new ArrayList<>();
        try {
            for (Seat s : seats()) {
                if (s.round == null) {
                    continue;
                }
                ChanceRounds.Round now = games().dao().round(s.round.id());
                if (now == null || !now.open()) {
                    s.round = null;
                    s.stack = 0;
                    s.carry = 0;
                    if (s.npc == null) {
                        places[s.place] = null;
                    }
                    continue;
                }
                int[] sc = chipsOf(now.data());
                s.round = now;
                s.stack = sc[0] + sc[1];
                s.carry = 0;
                keep.add(s);
            }
        } catch (SQLException | RuntimeException e) {
            games().logger().log(Level.SEVERE, "Hold'em could not read the " + kind.key + " table's rounds - the"
                    + " table is cleared; every seat's round is finished by its exit rule within ten minutes", e);
            for (int i = 0; i < MAX_SEATS; i++) {
                places[i] = null;
            }
            endHand();
            return;
        }
        endHand();
        Map<Seat, int[]> writes = new LinkedHashMap<>();
        for (Seat s : keep) {
            writes.put(s, new int[] {s.stack, 0});
        }
        if (!writes.isEmpty() && !write(writes)) {
            games().logger().severe("Hold'em could not put the " + kind.key + " table's chips back - the table is"
                    + " cleared; every seat's round is finished by its exit rule within ten minutes");
            for (int i = 0; i < MAX_SEATS; i++) {
                places[i] = null;
            }
        }
        if (hadHand) {
            say("&7Hold'em: that hand was called off. Everyone has the chips they had before it.");
        }
        changed();
    }

    /** No hand on the table any more. */
    private void endHand() {
        hand = null;
        handSeats = new Seat[0];
        phase = Phase.WAITING;
        dealAt = 0;
        clearHurry();
        for (Seat s : places) {
            if (s != null) {
                s.inHand = -1;
            }
        }
    }

    // ---- NPC players --------------------------------------------------------------------------------------

    /** Whether a real player sits at the table. */
    public boolean hasReal() {
        for (Seat s : places) {
            if (s != null && s.npc == null) {
                return true;
            }
        }
        return false;
    }

    /** The real players sitting here. */
    public List<UUID> realIds() {
        List<UUID> out = new ArrayList<>();
        for (Seat s : places) {
            if (s != null && s.npc == null) {
                out.add(s.id);
            }
        }
        return out;
    }

    public int npcCount() {
        int n = 0;
        for (Seat s : places) {
            n += s != null && s.npc != null ? 1 : 0;
        }
        return n;
    }

    /** A real player sits down at a full friendly table: an NPC gets up for them between hands. */
    private boolean giveWay() {
        if (phase == Phase.PLAYING) {
            return false;
        }
        for (int i = MAX_SEATS - 1; i >= 0; i--) {
            if (places[i] != null && places[i].npc != null) {
                places[i] = null;
                return true;
            }
        }
        return false;
    }

    /**
     * {@code npc} sits down at the friendly table (between hands, in a free seat) with the play chips everyone gets.
     *
     * @return its seat, or {@code null} when it can't sit now
     */
    public Seat sitNpc(CpuPlayer npc, TableNpcs npcs) {
        if (npc == null || kind != Kind.FRIENDLY || phase == Phase.PLAYING || seat(npc.id()) != null) {
            return null;
        }
        int free = freePlace();
        if (free < 0) {
            return null;
        }
        Seat s = new Seat(npc.id(), npcs.label(npc), free);
        s.npc = npc;
        s.stack = settings().friendlyChips();
        s.plays = npcs.level(npc, realIds(), game.id());
        places[free] = s;
        changed();
        return s;
    }

    /**
     * {@code by}, sitting here, sends the NPC in {@code seat} away (between hands): it gets up, and no NPC joins them
     * again until they get up themselves.
     */
    public boolean sendAway(Player by, Seat seat) {
        if (by == null || seat == null || seat.npc == null || phase == Phase.PLAYING || seat(by.getUniqueId()) == null) {
            return false;
        }
        places[seat.place] = null;
        sentAway.add(by.getUniqueId());
        npcJoinAt = 0;
        changed();
        return true;
    }

    /**
     * The friendly table's NPCs, between hands: they leave when nobody real is here or someone has them off (or they
     * are off altogether), and while every real player here wants them and nobody sent one away, one sits in every
     * {@code join_after_seconds} until the table has {@code npc_seats} players.
     */
    private void npcSeats(TableNpcs npcs, long now) {
        if (phase == Phase.PLAYING) {
            return;
        }
        List<UUID> reals = realIds();
        boolean wanted = npcs != null && !reals.isEmpty();
        for (UUID r : reals) {
            wanted &= npcs != null && npcs.wants(r) && !sentAway.contains(r);
        }
        if (!wanted) {
            boolean gone = false;
            for (Seat s : seats()) {
                if (s.npc != null && (npcs == null || reals.isEmpty() || !allWant(npcs, reals))) {
                    places[s.place] = null;
                    gone = true;
                }
            }
            npcJoinAt = 0;
            if (gone) {
                changed();
            }
            return;
        }
        if (seats().size() >= Math.min(MAX_SEATS, settings().npcSeats()) || freePlace() < 0) {
            npcJoinAt = 0;
            return;
        }
        if (npcJoinAt == 0) {
            npcJoinAt = now + npcs.joinAfterMs();
        } else if (now >= npcJoinAt) {
            npcJoinAt = 0;
            sitNpc(pick(npcs), npcs);
        }
    }

    private static boolean allWant(TableNpcs npcs, List<UUID> reals) {
        for (UUID r : reals) {
            if (!npcs.wants(r)) {
                return false;
            }
        }
        return true;
    }

    private CpuPlayer pick(TableNpcs npcs) {
        List<CpuPlayer> free = new ArrayList<>();
        for (CpuPlayer c : npcs.roster()) {
            if (seat(c.id()) == null) {
                free.add(c);
            }
        }
        return free.isEmpty() ? null : free.get(npcs.rng().nextInt(free.size()));
    }

    /** The NPC whose turn it is plays its move. */
    private void npcMove(Seat t, TableNpcs npcs) {
        int i = t.inHand;
        HoldemHand.Player me = hand.player(i);
        int live = 0;
        for (int j = 0; j < hand.size(); j++) {
            live += hand.player(j).folded() ? 0 : 1;
        }
        CpuHoldem.View view = new CpuHoldem.View(me.hole(), hand.board(), live - 1, hand.pot(), hand.toCall(i),
                me.stack(), hand.canRaise(i), hand.minRaiseTo(i), hand.maxRaiseTo(i), hand.currentBet(),
                hand.bigBlind());
        CpuHoldem.Decision d = CpuHoldem.decide(view, t.plays, t.npc.personality(), npcs.rng());
        switch (d.move()) {
            case FOLD -> {
                if (hand.canCheck(i)) {
                    hand.checkCall(i);
                } else {
                    hand.fold(i);
                }
            }
            case CHECK_CALL -> hand.checkCall(i);
            case RAISE -> {
                if (!hand.raiseTo(i, d.to())) {
                    hand.checkCall(i);
                }
            }
            case ALL_IN -> hand.allIn(i);
        }
        moved();
    }

    // ---- the admin's stop ---------------------------------------------------------------------------------

    /**
     * An admin stops the table: a hand in play is called off (everyone has what they had before it), and every seat
     * gets up with its chips.
     *
     * @return how many seats got up
     */
    public int stopAll() {
        int n = 0;
        endHand();
        for (Seat s : seats()) {
            Player p = games().online(s.id);
            if (p != null) {
                p.sendMessage(Text.of("&7Hold'em: the " + kind.label.toLowerCase(java.util.Locale.ROOT)
                        + " was closed by an admin."));
            }
            if (s.round != null) {
                cashOut(s, true);
            }
            places[s.place] = null;
            n++;
        }
        lastResult.clear();
        changed();
        return n;
    }

    /** The game stopped (switched off, failed, the server stopping): forget everything; the rounds stay as written. */
    void forget() {
        for (int i = 0; i < MAX_SEATS; i++) {
            places[i] = null;
        }
        endHand();
        screens.clear();
        watchers.clear();
        sentAway.clear();
        lastResult.clear();
    }

    /** One line for {@code /hcm games holdem status}. */
    String statusLine() {
        StringBuilder out = new StringBuilder("&f" + kind.label + "&7: ");
        List<Seat> seats = seats();
        if (seats.isEmpty()) {
            return out.append("empty").toString();
        }
        out.append(phase.name().toLowerCase(java.util.Locale.ROOT)).append(", hand ").append(handNo).append(" -");
        int total = 0;
        for (Seat s : seats) {
            total += s.stack + s.carry;
            out.append(" ").append(s.npc != null ? plainName(s) : s.name).append(" ").append(chips(s))
                    .append(s.away ? " (away)" : "").append(s.sittingOut ? " (out)" : "");
        }
        if (kind.stakes()) {
            out.append(" &8| ").append(total).append(" chips in open rounds");
        }
        return out.toString();
    }

    // ---- chat and screens ---------------------------------------------------------------------------------

    /** A line to everyone at the table: the real players sitting here and the watchers. */
    private void say(String line) {
        Set<UUID> to = new HashSet<>(realIds());
        to.addAll(watchers);
        for (UUID id : to) {
            Player p = games().online(id);
            if (p != null) {
                p.sendMessage(Text.of(line));
            }
        }
    }

    private void clearHurry() {
        hurryUntil = 0;
        hurryBy = null;
    }

    private void changed() {
        for (Runnable r : new ArrayList<>(screens.values())) {
            try {
                r.run();
            } catch (RuntimeException e) {
                // one screen's trouble is never the table's
            }
        }
    }
}
