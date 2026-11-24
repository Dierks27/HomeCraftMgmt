package com.dierks.homecraft.games.chance.holdem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One hand of no-limit Texas Hold'em, as pure state (0.57.0): the deal, the blinds, the four rounds of putting chips in,
 * the side pots and the showdown. Nothing here knows about tokens, money, players or screens: chips are numbers, and the
 * table ({@link HoldemTable}) decides what they are.
 *
 * <p><b>The deal.</b> A fresh 52-card deck each hand (the table shuffles it). Two cards each, one at a time, starting
 * left of the button; then the flop (three), the turn and the river, each after a burned card, as a real dealer does.
 *
 * <p><b>The blinds.</b> The two seats left of the button put in the small and the big blind; with two players the
 * button is the small blind and acts first before the flop, last after it. A blind bigger than a stack puts that
 * player all in. Everyone else must still match the full big blind (a short big blind is an all-in for less).
 *
 * <p><b>Each round.</b> Before the flop the seat after the big blind acts first (the big blind still gets its option);
 * after it, the first seat left of the button still in. A player may fold, check (nothing to match), call, raise or go
 * all in. A raise is at least the size of the last full raise (the big blind to begin with); an all-in for less is
 * allowed, but it doesn't reopen the raising to players who already acted (they may only call or fold). A round ends
 * when everyone still able to act has acted and matched the most put in.
 *
 * <p><b>The end.</b> When everyone else folds, the last player takes every chip without showing. Otherwise, once at
 * most one player can still act (the rest all in), the board is dealt out, and at the showdown each pot (the main pot,
 * then each side pot, layered by what each player put in) goes to the best hand among the players who put in at that
 * level and didn't fold; equal hands split it, and a chip that doesn't split evenly goes to the first of them left of
 * the button. A pot nobody still in can win (an all-in folded away) joins the pot below it.
 */
public final class HoldemHand {

    /** Where the hand is. */
    public enum Street {
        PRE_FLOP("Before the flop"),
        FLOP("The flop"),
        TURN("The turn"),
        RIVER("The river"),
        /** Over: the cards were shown and the pots shared. */
        SHOWDOWN("The showdown"),
        /** Over: everyone else folded. */
        FOLDED("Over");

        public final String label;

        Street(String label) {
            this.label = label;
        }
    }

    /** What a player did last. */
    public enum Act {
        SMALL_BLIND("Small blind"),
        BIG_BLIND("Big blind"),
        FOLD("Fold"),
        CHECK("Check"),
        CALL("Call"),
        RAISE("Raise"),
        ALL_IN("All in");

        public final String label;

        Act(String label) {
            this.label = label;
        }
    }

    /** One player in the hand. */
    public static final class Player {
        private final int start;
        private int stack;
        private int street;
        private int total;
        private boolean folded;
        private boolean allIn;
        private boolean acted;
        private boolean mayRaise = true;
        private final PokerCard[] hole = new PokerCard[2];
        private Act last;
        private int lastTo;
        private int won;
        private HandValue value;

        private Player(int stack) {
            this.start = stack;
            this.stack = stack;
        }

        /** Chips behind (not yet put in). */
        public int stack() {
            return stack;
        }

        /** Chips at the start of the hand. */
        public int start() {
            return start;
        }

        /** Put in this round. */
        public int street() {
            return street;
        }

        /** Put in this hand, every round. */
        public int total() {
            return total;
        }

        public boolean folded() {
            return folded;
        }

        public boolean allIn() {
            return allIn;
        }

        /** The two cards. */
        public List<PokerCard> hole() {
            return List.of(hole[0], hole[1]);
        }

        /** What they did last this round, or {@code null}. */
        public Act last() {
            return last;
        }

        /** The amount their last raise went to, or their call's total this round. */
        public int lastTo() {
            return lastTo;
        }

        /** Chips the pots gave them at the end (0 until then; their own unmatched chips back count too). */
        public int won() {
            return won;
        }

        /** Their best hand at the showdown, or {@code null} (folded, or no showdown). */
        public HandValue value() {
            return value;
        }

        /** Still in: not folded. */
        public boolean live() {
            return !folded;
        }
    }

    /**
     * A pot: the chips in it and who may win it (indexes into the hand's players).
     *
     * @param amount   chips
     * @param eligible the players still in who put in at its level
     * @param winners  who it went to, at the end (empty until then)
     * @param shares   what each winner got, matching {@code winners}
     */
    public record Pot(int amount, List<Integer> eligible, List<Integer> winners, List<Integer> shares) {
    }

    private final List<Player> players = new ArrayList<>();
    private final int button;
    private final int smallBlind;
    private final int bigBlind;
    private final int[] deck;
    private int next;
    private final List<PokerCard> board = new ArrayList<>();
    private Street street = Street.PRE_FLOP;
    private int currentBet;
    private int lastRaise;
    private int toAct = -1;
    private final int sbAt;
    private final int bbAt;
    private List<Pot> result = List.of();

    /**
     * Deal a hand.
     *
     * @param stacks     each player's chips, in seat order around the table
     * @param button     the index (into {@code stacks}) of the button
     * @param smallBlind the small blind, in chips
     * @param bigBlind   the big blind, in chips (at least the small one)
     * @param deck       the shuffled deck, as card numbers ({@link PokerCard#shuffled})
     * @throws IllegalArgumentException for fewer than two players, a player with no chips, or a deck that isn't 52
     *                                  different cards
     */
    public HoldemHand(int[] stacks, int button, int smallBlind, int bigBlind, int[] deck) {
        if (stacks.length < 2 || stacks.length > 10) {
            throw new IllegalArgumentException("a hand is two to ten players: " + stacks.length);
        }
        if (button < 0 || button >= stacks.length || smallBlind < 0 || bigBlind < 1 || bigBlind < smallBlind) {
            throw new IllegalArgumentException("button " + button + ", blinds " + smallBlind + "/" + bigBlind);
        }
        if (deck.length != PokerCard.DECK || java.util.Arrays.stream(deck).distinct().count() != PokerCard.DECK
                || java.util.Arrays.stream(deck).anyMatch(c -> c < 0 || c >= PokerCard.DECK)) {
            throw new IllegalArgumentException("a deck is the 52 cards once each");
        }
        for (int s : stacks) {
            if (s <= 0) {
                throw new IllegalArgumentException("everyone dealt in has chips: " + s);
            }
            players.add(new Player(s));
        }
        this.button = button;
        this.smallBlind = smallBlind;
        this.bigBlind = bigBlind;
        this.deck = deck.clone();
        int n = players.size();
        for (int round = 0; round < 2; round++) {
            for (int k = 1; k <= n; k++) {
                players.get((button + k) % n).hole[round] = draw();
            }
        }
        sbAt = n == 2 ? button : (button + 1) % n;
        bbAt = (sbAt + 1) % n;
        post(sbAt, smallBlind, Act.SMALL_BLIND);
        post(bbAt, bigBlind, Act.BIG_BLIND);
        currentBet = bigBlind;
        lastRaise = bigBlind;
        toAct = (bbAt + 1) % n;
        advance(toAct);
    }

    private PokerCard draw() {
        return PokerCard.of(deck[next++]);
    }

    private void post(int i, int amount, Act act) {
        Player p = players.get(i);
        int put = Math.min(amount, p.stack);
        p.stack -= put;
        p.street += put;
        p.total += put;
        p.allIn = p.stack == 0;
        p.last = act;
        p.lastTo = p.street;
    }

    // ---- what it shows ------------------------------------------------------------------------------------

    public int size() {
        return players.size();
    }

    public Player player(int i) {
        return players.get(i);
    }

    public List<PokerCard> board() {
        return Collections.unmodifiableList(board);
    }

    public Street street() {
        return street;
    }

    /** Over: folded to one player, or shown down. */
    public boolean over() {
        return street == Street.SHOWDOWN || street == Street.FOLDED;
    }

    public int button() {
        return button;
    }

    public int smallBlindAt() {
        return sbAt;
    }

    public int bigBlindAt() {
        return bbAt;
    }

    public int bigBlind() {
        return bigBlind;
    }

    public int smallBlind() {
        return smallBlind;
    }

    /** Whose turn it is (an index), or -1 when the hand is over. */
    public int toAct() {
        return over() ? -1 : toAct;
    }

    /** The most anyone has put in this round. */
    public int currentBet() {
        return currentBet;
    }

    /** Every chip put in this hand. */
    public int pot() {
        int sum = 0;
        for (Player p : players) {
            sum += p.total;
        }
        return sum;
    }

    /** What {@code i} must put in to call (never more than they have). */
    public int toCall(int i) {
        Player p = players.get(i);
        return Math.max(0, Math.min(currentBet - p.street, p.stack));
    }

    /** Whether {@code i} could check (nothing to match). */
    public boolean canCheck(int i) {
        return players.get(i).street >= currentBet;
    }

    /**
     * Whether {@code i} may raise now: it's their turn, they have chips beyond a call, raising is open to them (an
     * all-in for less didn't close it), and someone else could still answer.
     */
    public boolean canRaise(int i) {
        if (i != toAct() || !players.get(i).mayRaise) {
            return false;
        }
        Player p = players.get(i);
        if (p.stack <= toCall(i)) {
            return false;
        }
        for (int j = 0; j < players.size(); j++) {
            if (j != i && !players.get(j).folded && !players.get(j).allIn) {
                return true;
            }
        }
        return false;
    }

    /** The least a raise by {@code i} can go to (all of their chips, if that is less). */
    public int minRaiseTo(int i) {
        return Math.min(currentBet + lastRaise, maxRaiseTo(i));
    }

    /** The most {@code i} can put in this round: everything. */
    public int maxRaiseTo(int i) {
        Player p = players.get(i);
        return p.street + p.stack;
    }

    /** The pots as they stand (the main pot first), from what everyone has put in. */
    public List<Pot> pots() {
        return over() ? result : layer();
    }

    // ---- the moves ----------------------------------------------------------------------------------------

    /** {@code i} folds (on their turn). */
    public boolean fold(int i) {
        if (i != toAct()) {
            return false;
        }
        players.get(i).folded = true;
        players.get(i).last = Act.FOLD;
        players.get(i).acted = true;
        moved(i);
        return true;
    }

    /**
     * {@code i} is out of the hand whatever the turn (they got up, or left the server): folded, their chips stay in
     * the pots. When it was their turn the hand moves on.
     */
    public void forceFold(int i) {
        if (over() || players.get(i).folded) {
            return;
        }
        Player p = players.get(i);
        p.folded = true;
        p.last = Act.FOLD;
        p.acted = true;
        if (i == toAct) {
            moved(i);
        } else if (live() == 1) {
            finishFolded();
        } else if (!needsAction()) {
            moved(toAct); // the round waited only on someone who now has nobody left to answer: it ends
        }
    }

    /** {@code i} checks, or calls what is needed (all in, when that is all they have). */
    public boolean checkCall(int i) {
        if (i != toAct()) {
            return false;
        }
        Player p = players.get(i);
        int put = toCall(i);
        boolean check = put == 0;
        p.stack -= put;
        p.street += put;
        p.total += put;
        p.acted = true;
        if (p.stack == 0 && put > 0) {
            p.allIn = true;
            p.last = Act.ALL_IN;
        } else {
            p.last = check ? Act.CHECK : Act.CALL;
        }
        p.lastTo = p.street;
        moved(i);
        return true;
    }

    /**
     * {@code i} raises to {@code to} chips this round (at least {@link #minRaiseTo}; at {@link #maxRaiseTo} or more it
     * is all in). A raise that isn't open to them is refused; so is one below the least.
     */
    public boolean raiseTo(int i, int to) {
        if (!canRaise(i)) {
            return false;
        }
        Player p = players.get(i);
        int max = maxRaiseTo(i);
        int target = Math.min(to, max);
        if (target < minRaiseTo(i) || target <= currentBet) {
            return false;
        }
        int put = target - p.street;
        int by = target - currentBet;
        p.stack -= put;
        p.street = target;
        p.total += put;
        p.acted = true;
        p.allIn = p.stack == 0;
        p.last = p.allIn ? Act.ALL_IN : Act.RAISE;
        p.lastTo = target;
        boolean full = by >= lastRaise;
        if (full) {
            lastRaise = by;
        }
        currentBet = target;
        for (int j = 0; j < players.size(); j++) {
            Player o = players.get(j);
            if (j == i || o.folded || o.allIn) {
                continue;
            }
            if (full) {
                o.mayRaise = true;
            } else if (o.acted) {
                o.mayRaise = false; // an all-in for less: they may call it or fold, not raise again
            }
            o.acted = false;
        }
        moved(i);
        return true;
    }

    /**
     * {@code i} puts everything in: a raise when that is more than the most put in and raising is open to them (an
     * all-in for less than a full raise included), else a call of what they can.
     */
    public boolean allIn(int i) {
        if (i != toAct()) {
            return false;
        }
        if (canRaise(i) && maxRaiseTo(i) > currentBet) {
            return raiseTo(i, maxRaiseTo(i));
        }
        return checkCall(i);
    }

    /**
     * The move for a player who isn't there to make one (away, or the clock ran out): check when they can, else
     * fold.
     */
    public void autoAct(int i) {
        if (i != toAct()) {
            return;
        }
        if (canCheck(i)) {
            checkCall(i);
        } else {
            fold(i);
        }
    }

    // ---- moving on ----------------------------------------------------------------------------------------

    private int live() {
        int n = 0;
        for (Player p : players) {
            n += p.folded ? 0 : 1;
        }
        return n;
    }

    /** Whether anyone still has to act this round. */
    private boolean needsAction() {
        int able = 0;
        for (Player p : players) {
            if (!p.folded && !p.allIn) {
                able++;
            }
        }
        for (Player p : players) {
            if (p.folded || p.allIn) {
                continue;
            }
            if (p.street < currentBet) {
                return true;
            }
            if (!p.acted && able > 1) {
                return true; // the last one able to act, already matching everyone, has nobody to raise against
            }
        }
        return false;
    }

    private boolean waitsOn(int i) {
        Player p = players.get(i);
        if (p.folded || p.allIn) {
            return false;
        }
        int able = 0;
        for (Player o : players) {
            if (!o.folded && !o.allIn) {
                able++;
            }
        }
        return p.street < currentBet || (!p.acted && able > 1);
    }

    /** After a move by {@code i}: the next turn, the next round, or the end. */
    private void moved(int i) {
        if (live() == 1) {
            finishFolded();
            return;
        }
        advance((i + 1) % players.size());
    }

    /** The turn goes to the first from {@code from} who has to act, or the round ends. */
    private void advance(int from) {
        if (live() == 1) {
            finishFolded();
            return;
        }
        if (needsAction()) {
            for (int k = 0; k < players.size(); k++) {
                int j = (from + k) % players.size();
                if (waitsOn(j)) {
                    toAct = j;
                    return;
                }
            }
        }
        nextStreet();
    }

    private void nextStreet() {
        for (Player p : players) {
            p.street = 0;
            p.acted = false;
            p.mayRaise = true;
            if (!p.folded && !p.allIn) {
                p.last = null;
            }
        }
        currentBet = 0;
        lastRaise = bigBlind;
        switch (street) {
            case PRE_FLOP -> {
                next++; // the burned card
                board.add(draw());
                board.add(draw());
                board.add(draw());
                street = Street.FLOP;
            }
            case FLOP -> {
                next++;
                board.add(draw());
                street = Street.TURN;
            }
            case TURN -> {
                next++;
                board.add(draw());
                street = Street.RIVER;
            }
            default -> {
                showdown();
                return;
            }
        }
        advance((button + 1) % players.size());
    }

    /** Everyone else folded: the last player takes every chip, nothing shown. */
    private void finishFolded() {
        int winner = -1;
        for (int i = 0; i < players.size(); i++) {
            if (!players.get(i).folded) {
                winner = i;
            }
        }
        int pot = pot();
        Player w = players.get(winner);
        w.won = pot;
        w.stack += pot;
        result = List.of(new Pot(pot, List.of(winner), List.of(winner), List.of(pot)));
        street = Street.FOLDED;
        toAct = -1;
    }

    private void showdown() {
        List<PokerCard> seven = new ArrayList<>(7);
        for (Player p : players) {
            if (!p.folded) {
                seven.clear();
                seven.add(p.hole[0]);
                seven.add(p.hole[1]);
                seven.addAll(board);
                p.value = HandValue.of(seven);
            }
        }
        List<Pot> shared = new ArrayList<>();
        for (Pot pot : layer()) {
            long best = Long.MIN_VALUE;
            for (int i : pot.eligible()) {
                best = Math.max(best, players.get(i).value.score());
            }
            List<Integer> winners = new ArrayList<>();
            // in order from the first seat left of the button: who gets a chip that doesn't split evenly
            for (int k = 1; k <= players.size(); k++) {
                int i = (button + k) % players.size();
                if (pot.eligible().contains(i) && players.get(i).value.score() == best) {
                    winners.add(i);
                }
            }
            int each = pot.amount() / winners.size();
            int odd = pot.amount() % winners.size();
            List<Integer> shares = new ArrayList<>();
            for (int w = 0; w < winners.size(); w++) {
                int share = each + (w < odd ? 1 : 0);
                shares.add(share);
                Player p = players.get(winners.get(w));
                p.won += share;
                p.stack += share;
            }
            shared.add(new Pot(pot.amount(), pot.eligible(), List.copyOf(winners), List.copyOf(shares)));
        }
        result = List.copyOf(shared);
        street = Street.SHOWDOWN;
        toAct = -1;
    }

    /**
     * The pots by level: for each distinct amount someone put in, the slice between it and the level below, open to
     * the players still in who put in at least that much. A slice nobody still in reached joins the pot below it;
     * neighbouring pots with the same players are one pot.
     */
    private List<Pot> layer() {
        List<Integer> levels = new ArrayList<>();
        for (Player p : players) {
            if (p.total > 0 && !levels.contains(p.total)) {
                levels.add(p.total);
            }
        }
        Collections.sort(levels);
        List<int[]> amounts = new ArrayList<>();
        List<List<Integer>> eligible = new ArrayList<>();
        int below = 0;
        for (int level : levels) {
            int slice = 0;
            List<Integer> who = new ArrayList<>();
            for (int i = 0; i < players.size(); i++) {
                Player p = players.get(i);
                slice += Math.max(0, Math.min(p.total, level) - below);
                if (!p.folded && p.total >= level) {
                    who.add(i);
                }
            }
            below = level;
            if (slice == 0) {
                continue;
            }
            if (who.isEmpty()) {
                if (!amounts.isEmpty()) {
                    amounts.get(amounts.size() - 1)[0] += slice;
                } else {
                    // no level below: everyone still in (folded chips with nobody to reach them)
                    List<Integer> all = new ArrayList<>();
                    for (int i = 0; i < players.size(); i++) {
                        if (!players.get(i).folded) {
                            all.add(i);
                        }
                    }
                    amounts.add(new int[] {slice});
                    eligible.add(all);
                }
                continue;
            }
            if (!eligible.isEmpty() && eligible.get(eligible.size() - 1).equals(who)) {
                amounts.get(amounts.size() - 1)[0] += slice;
            } else {
                amounts.add(new int[] {slice});
                eligible.add(who);
            }
        }
        List<Pot> out = new ArrayList<>();
        for (int k = 0; k < amounts.size(); k++) {
            out.add(new Pot(amounts.get(k)[0], List.copyOf(eligible.get(k)), List.of(), List.of()));
        }
        return out;
    }
}
