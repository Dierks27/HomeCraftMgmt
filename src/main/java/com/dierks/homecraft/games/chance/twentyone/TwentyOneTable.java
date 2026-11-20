package com.dierks.homecraft.games.chance.twentyone;

import com.dierks.homecraft.games.ChanceRounds;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.Refusal;
import com.dierks.homecraft.games.chance.twentyone.TwentyOneHand.Shoe;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Game Parlor's Twenty-One table (0.47.0; the owner: "It would be pretty great if we could play as a group around
 * the table!! In fact that would be amazing for any Table game so its much more a social event", and of the pace
 * "the player has unlimited time unless the other players at the table hit a 'Hurry Up' Button ... Then they have like
 * 30 seconds. I want to be able to move it along but I also want it to be chill").
 *
 * <p>Up to {@link TableRound#MAX_SEATS} players sit at it (opening its screen sits you down). Everyone picks what to put
 * in (tokens or, with the Parlor's money play, dollars: each seat its own) and says they are ready; when every seat is
 * ready the cards are dealt from the table's one shoe ({@link TableRound}), the seats play in turn, and the Arcade
 * draws once for all of them. A table of one plays exactly so, with nobody to wait for.
 *
 * <p><b>The pace.</b> Nobody is ever on a clock by themselves. A seat that is waiting on someone else may tap Hurry
 * Up: whoever the table is waiting on then has {@link #HURRY_MS} (30 seconds). When it runs out, a seat that wasn't
 * ready sits the round out and a seat that hadn't played stands. The clock is cleared once the table moves.
 *
 * <p><b>Every seat's hand is its own round</b> in {@link ChanceRounds}, so everything a hand alone has, a seat has:
 * the play gate when the stake goes in, the day's limits, a Double's own check, one payment, and the exit rule for a
 * player who leaves (they stand; see {@link TableRound#settleOnExit}). The table itself is only memory.
 */
public final class TwentyOneTable {

    /** How long a seat has once someone taps Hurry Up. */
    public static final long HURRY_MS = 30_000;
    /** How often the table touches its open rounds, so the ten-minute sweep leaves a slow table alone. */
    static final long TOUCH_MS = 60_000;

    /** Where the table is. */
    public enum Phase {
        /** Taking stakes: seats pick what to put in and say Ready. Results of the last round stay on show. */
        OPEN,
        /** Cards are out: the seats play in turn. */
        PLAYING
    }

    /** One seat. */
    public static final class Seat {
        private final UUID id;
        private String name;
        private int stake;
        private boolean money;
        private boolean ready;
        /** Not at the table's screen right now (they closed it mid-hand): the hand is theirs to come back to. */
        private boolean away;
        /** The seat's open round this deal, or {@code null} (not dealt in, or already paid). */
        private ChanceRounds.Round round;
        /** Its hand in the round on the table, or -1. */
        private int hand = -1;
        /** Its round was finished somewhere else mid-hand (they left the server; the sweep): the hand stands. */
        private boolean gone;

        private Seat(UUID id, String name) {
            this.id = id;
            this.name = name;
        }

        public UUID id() {
            return id;
        }

        public String name() {
            return name;
        }

        public int stake() {
            return stake;
        }

        public boolean money() {
            return money;
        }

        public boolean ready() {
            return ready;
        }

        public boolean away() {
            return away;
        }

        /** Its hand in the round on the table (dealt in this round or the last), or -1. */
        public int hand() {
            return hand;
        }
    }

    private final TwentyOne game;
    private final List<Seat> seats = new ArrayList<>();
    private final Map<UUID, Runnable> screens = new LinkedHashMap<>();
    private Phase phase = Phase.OPEN;
    /** The round on the table: being played, or the last one (its cards stay on show), or {@code null}. */
    private TableRound round;
    private long shoeSeed;
    private int shoeAt = -1;
    private long hurryUntil;
    private String hurryBy;
    private long touchedAt;
    private long tickedAt;
    /** A test's own seed for the next fresh shoe, or {@code null} (a random one, as in play). */
    private Long nextShoe;

    /** For tests: the next fresh shoe is shuffled from {@code seed} (nothing in play calls this). */
    public void seedNextShoe(long seed) {
        this.nextShoe = seed;
    }

    TwentyOneTable(TwentyOne game) {
        this.game = game;
    }

    private GamesService games() {
        return game.games();
    }

    private long now() {
        return games().clock().nowMillis();
    }

    // ---- what it shows ------------------------------------------------------------------------------------

    public Phase phase() {
        return phase;
    }

    public List<Seat> seats() {
        return Collections.unmodifiableList(seats);
    }

    public Seat seat(UUID id) {
        for (Seat s : seats) {
            if (s.id.equals(id)) {
                return s;
            }
        }
        return null;
    }

    /** The round on the table (in play, or the last one's cards), or {@code null} before the first deal. */
    public TableRound round() {
        return round;
    }

    /** The seat whose turn it is, or {@code null}. */
    public Seat turn() {
        if (phase != Phase.PLAYING || round == null) {
            return null;
        }
        int t = round.turn();
        for (Seat s : seats) {
            if (s.hand == t && t >= 0) {
                return s;
            }
        }
        return null;
    }

    /** Whether the table is waiting on this seat: not ready while stakes are being taken, or its turn to play. */
    public boolean waitingOn(Seat s) {
        if (s == null) {
            return false;
        }
        return phase == Phase.OPEN ? !s.ready && anyReady() : s == turn();
    }

    /** Whether {@code s} could tap Hurry Up now: the table waits on someone else, and nobody has tapped it yet. */
    public boolean canHurry(Seat s) {
        if (s == null || hurryUntil > 0) {
            return false;
        }
        if (phase == Phase.OPEN && !s.ready) {
            return false; // only a seat that is itself ready may hurry the others
        }
        for (Seat other : seats) {
            if (other != s && waitingOn(other)) {
                return true;
            }
        }
        return false;
    }

    /** Seconds left on the Hurry Up clock, or -1 when it isn't running. */
    public int hurrySeconds() {
        return hurryUntil <= 0 ? -1 : (int) Math.max(0, (hurryUntil - now() + 999) / 1000);
    }

    /** Who tapped Hurry Up, or {@code null}. */
    public String hurryBy() {
        return hurryUntil <= 0 ? null : hurryBy;
    }

    private boolean anyReady() {
        for (Seat s : seats) {
            if (s.ready) {
                return true;
            }
        }
        return false;
    }

    // ---- sitting down and getting up ----------------------------------------------------------------------

    /**
     * Sit {@code player} down (or bring them back to their seat), with {@code refresh} run whenever the table changes.
     *
     * @return their seat, or {@code null} when the table is full
     */
    public Seat sit(Player player, Runnable refresh) {
        UUID id = player.getUniqueId();
        Seat s = seat(id);
        if (s == null && screens.isEmpty() && !seats.isEmpty()) {
            abandon(); // nobody is at the table: whatever was left on it is over
        }
        if (s == null) {
            if (seats.size() >= TableRound.MAX_SEATS) {
                return null;
            }
            if (seats.isEmpty()) {
                shoeAt = -1; // a new session at the table: a fresh shoe at its first deal
                round = null;
                phase = Phase.OPEN;
                clearHurry();
            }
            // a hand of theirs left open from before (alone, or at a table that is gone) is finished first
            ChanceRounds.Round old = games().rounds().openRound(id, game.id());
            if (old != null) {
                games().rounds().finishNow(old);
            }
            s = new Seat(id, player.getName());
            List<Integer> playable = game.settings().playable();
            s.stake = playable.isEmpty() ? 0 : playable.get(0);
            seats.add(s);
        }
        s.name = player.getName();
        s.away = false;
        if (refresh != null) {
            screens.put(id, refresh);
        }
        changed();
        return s;
    }

    /**
     * {@code id}'s screen closed. With a hand in play they keep their seat (away) and the hand waits for them, or for
     * Hurry Up; otherwise the seat is free.
     */
    public void leave(UUID id) {
        screens.remove(id);
        Seat s = seat(id);
        if (s == null) {
            return;
        }
        if (phase == Phase.PLAYING && s.hand >= 0 && round != null && round.hand(s.hand).result() == null) {
            s.away = true;
        } else {
            seats.remove(s);
            if (phase == Phase.OPEN) {
                dealIfAllReady();
            }
        }
        if (seats.isEmpty()) {
            clearHurry();
        }
        changed();
    }

    /** Everyone walked away with cards out: each hand left stands (its own exit rule), and the table is cleared. */
    private void abandon() {
        for (Seat s : seats) {
            if (s.round != null) {
                games().rounds().finishNow(s.round);
            }
        }
        seats.clear();
        round = null;
        phase = Phase.OPEN;
        shoeAt = -1;
        clearHurry();
    }

    // ---- stakes -------------------------------------------------------------------------------------------

    /** What {@code id} puts in next round (not while their hand is out, or once they are ready). */
    public void stake(UUID id, int stake, boolean money) {
        Seat s = seat(id);
        if (s == null || s.ready || phase != Phase.OPEN) {
            return;
        }
        s.stake = stake;
        s.money = money;
        changed();
    }

    /** What a hand pays for {@code s}'s stake as it is set, or {@code null} when it can't be played at that stake. */
    public TwentyOneSettings.Odds odds(Seat s) {
        if (s == null || s.stake <= 0) {
            return null;
        }
        return s.money ? game.moneyOdds(s.stake) : game.settings().odds(s.stake);
    }

    /**
     * {@code player} is ready for the next deal (the play gate is asked now, so a refusal is told now), or takes it
     * back. When every seat is ready, the cards are dealt.
     */
    public void ready(Player player, boolean ready) {
        Seat s = seat(player.getUniqueId());
        if (s == null || phase != Phase.OPEN || s.ready == ready) {
            return;
        }
        if (ready) {
            if (odds(s) == null) {
                return;
            }
            Refusal r = games().gate().check(player, game, s.stake, s.money);
            if (r != null) {
                if (!r.silent()) {
                    games().tell(player, r);
                }
                return; // (the click cooldown too: a click that soon isn't a Ready)
            }
        }
        s.ready = ready;
        if (!anyReady()) {
            clearHurry();
        }
        dealIfAllReady();
        changed();
    }

    private void dealIfAllReady() {
        if (phase != Phase.OPEN || seats.isEmpty()) {
            return;
        }
        for (Seat s : seats) {
            if (!s.ready) {
                return;
            }
        }
        deal();
    }

    /** Deal to every ready seat whose stake goes in; the others sit this round out. */
    private void deal() {
        clearHurry();
        List<Seat> in = new ArrayList<>();
        List<TwentyOneHand.Terms> terms = new ArrayList<>();
        for (Seat s : seats) {
            s.hand = -1;
            s.round = null;
            s.gone = false;
            if (!s.ready) {
                continue;
            }
            s.ready = false;
            Player p = games().online(s.id);
            TwentyOneSettings.Odds odds = odds(s);
            if (p == null || odds == null) {
                continue;
            }
            // the stake goes in first, as a round that gives it back if the cards never come
            ChanceRounds.Round opened = games().rounds().open(p, game, odds.stake(), TableRound.undealt(odds.terms()),
                    s.money);
            if (opened == null) {
                continue; // refused, and told why
            }
            s.round = opened;
            in.add(s);
            terms.add(odds.terms());
        }
        if (in.isEmpty()) {
            return;
        }
        if (shoeAt < 0 || shoeAt >= TwentyOneHand.CUT_CARD) {
            shoeSeed = nextShoe != null ? nextShoe : ThreadLocalRandom.current().nextLong();
            nextShoe = null;
            shoeAt = 0;
        }
        round = new TableRound(new Shoe(shoeSeed, shoeAt), terms);
        for (int i = 0; i < in.size(); i++) {
            in.get(i).hand = i;
        }
        phase = Phase.PLAYING;
        touchedAt = now();
        record();
        advance();
    }

    // ---- playing ------------------------------------------------------------------------------------------

    public void hit(Player player) {
        Seat s = mine(player);
        if (s != null) {
            round.hit(s.hand);
            record();
            advance();
        }
    }

    public void stand(Player player) {
        Seat s = mine(player);
        if (s != null) {
            round.stand(s.hand);
            record();
            advance();
        }
    }

    /**
     * Double: the same again goes in (its own gate check, at the moment it goes in), one card, stand.
     *
     * @return false when it was refused (the player has been told) or isn't theirs to do
     */
    public boolean doubleDown(Player player) {
        Seat s = mine(player);
        if (s == null || !round.hand(s.hand).canDouble() || s.round == null) {
            return false;
        }
        int extra = round.hand(s.hand).terms().in();
        if (!games().rounds().raise(player, s.round, extra)) {
            return false;
        }
        s.round = s.round.with(s.round.data(), s.round.stake() + extra);
        round.doubleDown(s.hand);
        record();
        advance();
        return true;
    }

    /** {@code player}'s seat if it is their turn, else {@code null}. */
    private Seat mine(Player player) {
        Seat s = seat(player.getUniqueId());
        return s != null && s == turn() ? s : null;
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

    /**
     * Once a second while anyone has the table's screen open: the Hurry Up clock, seats whose players are gone, and
     * the touch that keeps a slow table's rounds from being swept.
     */
    public void tick() {
        long now = now();
        if (now - tickedAt < 900) {
            return; // every open screen ticks it: once a second is enough
        }
        tickedAt = now;
        boolean moved = false;
        // a seat whose player is no longer on the server: free it between rounds; mid-hand it stands at its turn
        for (Seat s : new ArrayList<>(seats)) {
            if (games().online(s.id) == null) {
                if (phase == Phase.OPEN || s.hand < 0 || round == null || round.hand(s.hand).result() != null) {
                    seats.remove(s);
                    screens.remove(s.id);
                    moved = true;
                } else if (!s.gone) {
                    s.gone = true; // their quit already finished it by the exit rule: the hand stands at its turn
                    s.round = null;
                    moved = true;
                }
            }
        }
        if (hurryUntil > 0 && now >= hurryUntil) {
            clearHurry();
            if (phase == Phase.OPEN) {
                if (anyReady()) {
                    deal(); // the seats that weren't ready sit this one out
                }
            } else {
                Seat slow = turn();
                if (slow != null) {
                    round.stand(slow.hand);
                }
            }
            moved = true;
        }
        if (moved) {
            if (phase == Phase.PLAYING) {
                record();
                advance();
            } else {
                dealIfAllReady();
            }
            changed();
        } else if (hurryUntil > 0) {
            changed(); // the countdown
        }
        if (phase == Phase.PLAYING && now - touchedAt >= TOUCH_MS) {
            touchedAt = now;
            record();
        }
    }

    /** Write every open seat's hand as it stands (its cards, the shoe's place) before anything more is shown. */
    private void record() {
        for (Seat s : seats) {
            if (s.round != null && s.hand >= 0) {
                ChanceRounds.Round stepped = games().rounds().step(s.round, round.data(s.hand));
                if (stepped != null) {
                    s.round = stepped;
                } else {
                    s.round = null; // finished somewhere else (they left; the sweep): it was paid there
                    s.gone = round.hand(s.hand).result() == null;
                }
            }
        }
    }

    /** Pay every hand that is settled, and when the round is over open the table for the next. */
    private void advance() {
        clearHurry();
        for (Seat turn = turn(); turn != null && turn.gone; turn = turn()) {
            round.stand(turn.hand); // nobody is playing that hand any more
        }
        for (Seat s : seats) {
            if (s.round != null && s.hand >= 0 && round.hand(s.hand).result() != null) {
                games().rounds().close(s.round, round.hand(s.hand).payout(), round.data(s.hand));
                s.round = null;
            }
        }
        if (round.over()) {
            shoeAt = round.nextAt();
            phase = Phase.OPEN;
            seats.removeIf(s -> s.away || games().online(s.id) == null); // nobody keeps a seat they walked away from
        }
        changed();
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
