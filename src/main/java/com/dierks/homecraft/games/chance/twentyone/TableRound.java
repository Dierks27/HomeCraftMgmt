package com.dierks.homecraft.games.chance.twentyone;

import com.dierks.homecraft.games.RtpLimits.Ratio;
import com.dierks.homecraft.games.chance.twentyone.TwentyOneHand.Card;
import com.dierks.homecraft.games.chance.twentyone.TwentyOneHand.Result;
import com.dierks.homecraft.games.chance.twentyone.TwentyOneHand.Shoe;
import com.dierks.homecraft.games.chance.twentyone.TwentyOneHand.Terms;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One round of Twenty-One at a table of up to four (0.47.0; the owner: "It would be pretty great if we could play as a
 * group around the table!! ... its much more a social event"), as pure state.
 *
 * <p><b>One shoe, one Arcade hand, a hand each.</b> The cards come off the table's shared 4-deck shoe
 * ({@link Shoe}) in the order a real table deals them: a card to every seat in seat order, the Arcade's up card, a
 * second card to every seat, the Arcade's hole card. Then each seat plays in order (Hit, Stand, Double, the same rules
 * as a hand alone: {@link TwentyOneHand}), and once every seat has stood, gone over or been paid, the Arcade draws to
 * 17 once, for everyone. Each seat is paid by its own terms (its own stake, solved exactly as a hand alone is).
 *
 * <p><b>Every seat's hand is its own crash-safe round.</b> A seat's data ({@link #data}, format {@link #VERSION})
 * carries its terms, its own cards, the Arcade's two cards, the shoe's seed and where the shoe's next card is. That is
 * enough to finish the hand for a player who walked away, from the data alone ({@link #settleOnExit}): they stand, and
 * the Arcade draws from the shoe's next cards. The table itself is only memory; nothing is lost with it.
 */
public final class TableRound {

    /** The data format of a hand played at a table. */
    public static final int VERSION = 3;
    /** The most seats at a table. */
    public static final int MAX_SEATS = 4;

    /** One seat's hand. */
    public static final class Hand {
        private final Terms terms;
        private final TwentyOneMath.Payouts pays;
        private final List<Card> cards = new ArrayList<>();
        private final StringBuilder actions = new StringBuilder();
        private boolean doubled;
        private boolean stood;
        private Result result;

        private Hand(Terms terms) {
            this.terms = terms;
            this.pays = terms.payouts();
        }

        public Terms terms() {
            return terms;
        }

        public TwentyOneMath.Payouts pays() {
            return pays;
        }

        public List<Card> cards() {
            return Collections.unmodifiableList(cards);
        }

        public boolean doubled() {
            return doubled;
        }

        /** Stood (or doubled, or reached 21): waiting for the Arcade's hand. */
        public boolean stood() {
            return stood;
        }

        /** How it ended, or {@code null} while it isn't settled. */
        public Result result() {
            return result;
        }

        /** Still this seat's to play: not stood, not over 21, not paid. */
        public boolean playing() {
            return result == null && !stood;
        }

        public String actions() {
            return actions.toString();
        }

        /** What was put in: the stake, twice after a Double. */
        public int putIn() {
            return doubled ? 2 * terms.in() : terms.in();
        }

        /** What comes back once settled (0 until then). */
        public int payout() {
            return result == null ? 0 : payoutFor(result, doubled, pays);
        }

        public boolean canDouble() {
            return playing() && cards.size() == 2 && pays.canDouble();
        }
    }

    private final long shoeSeed;
    private int[] order;
    private int next;
    private final List<Hand> hands = new ArrayList<>();
    private final List<Card> dealer = new ArrayList<>();
    private boolean dealerPlayed;

    /**
     * Deal a round to these seats (one {@link Terms} each, in seat order) from {@code shoe}.
     *
     * @throws IllegalArgumentException for no seat, or more than {@link #MAX_SEATS}
     */
    public TableRound(Shoe shoe, List<Terms> seats) {
        if (seats.isEmpty() || seats.size() > MAX_SEATS) {
            throw new IllegalArgumentException("a table deals to 1-" + MAX_SEATS + " seats: " + seats.size());
        }
        this.shoeSeed = shoe.seed();
        this.order = shoe.order();
        this.next = shoe.at();
        for (Terms t : seats) {
            hands.add(new Hand(t));
        }
        for (Hand h : hands) {
            h.cards.add(draw());
        }
        dealer.add(draw());
        for (Hand h : hands) {
            h.cards.add(draw());
        }
        dealer.add(draw());
        boolean arcade = (dealer.get(0).value() == 1 || dealer.get(0).value() == 10) && twoCard21(dealer);
        for (Hand h : hands) {
            boolean mine = twoCard21(h.cards);
            if (arcade) {
                h.result = mine ? Result.BOTH_TWENTY_ONE : Result.ARCADE_TWENTY_ONE;
            } else if (mine) {
                h.result = Result.TWENTY_ONE;
            }
        }
        if (arcade) {
            dealerPlayed = true; // its hand is shown; nothing more is drawn
        }
        settleIfReady();
    }

    private Card draw() {
        if (next >= TwentyOneHand.SHOE_CARDS) {
            order = Shoe.shuffled(shoeSeed * 31 + 7); // never in play (a round starts before the cut card)
            next = 0;
        }
        return Card.of(order[next++]);
    }

    // ---- play ---------------------------------------------------------------------------------------------

    public int seats() {
        return hands.size();
    }

    public Hand hand(int seat) {
        return hands.get(seat);
    }

    /** The seat whose turn it is (the first still playing), or -1 when no seat has a move left. */
    public int turn() {
        for (int i = 0; i < hands.size(); i++) {
            if (hands.get(i).playing()) {
                return i;
            }
        }
        return -1;
    }

    /** Every hand is settled: the round is over. */
    public boolean over() {
        for (Hand h : hands) {
            if (h.result == null) {
                return false;
            }
        }
        return true;
    }

    /** Take a card. Over 21 loses at once; exactly 21 stands. */
    public void hit(int seat) {
        Hand h = mine(seat, "hit");
        h.actions.append(TwentyOneHand.HIT);
        h.cards.add(draw());
        int b = TwentyOneHand.best(h.cards);
        if (b > 21) {
            h.result = Result.BUST;
        } else if (b == 21) {
            h.stood = true;
        }
        settleIfReady();
    }

    public void stand(int seat) {
        Hand h = mine(seat, "stand");
        h.actions.append(TwentyOneHand.STAND);
        h.stood = true;
        settleIfReady();
    }

    /** Put the same in again, take exactly one card, stand. */
    public void doubleDown(int seat) {
        Hand h = mine(seat, "double");
        if (!h.canDouble()) {
            throw new IllegalArgumentException("that hand can't double now");
        }
        h.actions.append(TwentyOneHand.DOUBLE);
        h.doubled = true;
        h.cards.add(draw());
        if (TwentyOneHand.best(h.cards) > 21) {
            h.result = Result.BUST;
        } else {
            h.stood = true;
        }
        settleIfReady();
    }

    private Hand mine(int seat, String what) {
        if (seat != turn()) {
            throw new IllegalArgumentException("it isn't seat " + seat + "'s turn to " + what);
        }
        return hands.get(seat);
    }

    /** Once no seat has a move left: the Arcade draws to 17 (only if a hand is waiting on it) and every hand is settled. */
    private void settleIfReady() {
        if (turn() >= 0 || dealerPlayed) {
            return;
        }
        boolean waiting = false;
        for (Hand h : hands) {
            waiting |= h.result == null;
        }
        if (waiting) {
            while (TwentyOneHand.best(dealer) < 17) {
                dealer.add(draw());
            }
        }
        dealerPlayed = true;
        int theirs = TwentyOneHand.best(dealer);
        for (Hand h : hands) {
            if (h.result == null) {
                h.result = against(TwentyOneHand.best(h.cards), theirs);
            }
        }
    }

    private static Result against(int mine, int theirs) {
        return theirs > 21 || mine > theirs ? Result.WIN : mine == theirs ? Result.PUSH : Result.LOSE;
    }

    static int payoutFor(Result result, boolean doubled, TwentyOneMath.Payouts pays) {
        return switch (result) {
            case TWENTY_ONE -> pays.twentyOne();
            case WIN -> doubled ? pays.doubleWin() : pays.win();
            case PUSH -> doubled ? pays.doublePush() : pays.push();
            case BOTH_TWENTY_ONE -> pays.push();
            case LOSE, BUST, ARCADE_TWENTY_ONE -> 0;
        };
    }

    // ---- what it shows ------------------------------------------------------------------------------------

    /** Every Arcade card, the hole card included (hidden on screen while {@link #holeHidden}). */
    public List<Card> dealerCards() {
        return Collections.unmodifiableList(dealer);
    }

    /** The Arcade's hole card stays face down until its hand is played. */
    public boolean holeHidden() {
        return !dealerPlayed;
    }

    /** Where the shoe's next card is now (the next round's start, or the Arcade's next draw). */
    public int nextAt() {
        return next;
    }

    public long shoeSeed() {
        return shoeSeed;
    }

    // ---- a seat's data ------------------------------------------------------------------------------------

    /**
     * A seat's round data as it stands: {@code v=3;in=5;m=4/5;nb=3/2;cap=250;shoe=<seed>;at=<next>;p=12.40.7;d=3.25;a=H}
     * (cards as 0-51: rank - 1 + 13 x suit). {@code d} is the Arcade's whole hand once it has been played.
     */
    public String data(int seat) {
        Hand h = hands.get(seat);
        Terms t = h.terms;
        return "v=" + VERSION + ";in=" + t.in() + ";m=" + t.m().num() + "/" + t.m().den() + ";nb=" + t.bonus().num()
                + "/" + t.bonus().den() + ";cap=" + t.cap() + ";shoe=" + shoeSeed + ";at=" + Math.min(next,
                TwentyOneHand.SHOE_CARDS - 1) + ";p=" + codes(h.cards) + ";d=" + codes(dealerPlayed ? dealer
                : dealer.subList(0, 2)) + ";a=" + h.actions;
    }

    /**
     * The data of a seat whose stake is in and whose cards aren't dealt yet (the moment between the two): it settles
     * to the stake itself.
     */
    public static String undealt(Terms t) {
        return "v=" + VERSION + ";in=" + t.in() + ";m=" + t.m().num() + "/" + t.m().den() + ";nb=" + t.bonus().num()
                + "/" + t.bonus().den() + ";cap=" + t.cap() + ";shoe=0;at=0;p=;d=;a=";
    }

    /** Whether this is a table hand's data. */
    public static boolean isTable(String data) {
        return data != null && data.startsWith("v=" + VERSION + ";");
    }

    /**
     * The exit rule for a table hand, from its data alone: a hand not yet settled stands (a Double paid for but not
     * recorded is taken first, as with a hand alone), and the Arcade draws to 17 from the shoe's next cards.
     *
     * @throws IllegalArgumentException for data that isn't a readable table hand
     */
    public static int settleOnExit(long seed, int stake, String data) {
        Map<String, String> kv = new LinkedHashMap<>();
        for (String part : data.split(";", -1)) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                kv.put(part.substring(0, eq), part.substring(eq + 1));
            }
        }
        try {
            if (!Integer.toString(VERSION).equals(kv.get("v"))) {
                throw new IllegalArgumentException("not a table hand");
            }
            Terms t = new Terms(Integer.parseInt(kv.get("in")), ratio(kv.get("m")), ratio(kv.get("nb")),
                    Integer.parseInt(kv.get("cap")));
            TwentyOneMath.Payouts pays = t.payouts();
            List<Card> mine = cards(kv.get("p"));
            List<Card> theirs = new ArrayList<>(cards(kv.get("d")));
            if (mine.isEmpty() && theirs.isEmpty()) {
                return stake; // put in, never dealt (the table stopped between the two): it comes back
            }
            if (mine.size() < 2 || theirs.size() < 2) {
                throw new IllegalArgumentException("a table hand has two cards each");
            }
            String actions = kv.getOrDefault("a", "");
            boolean doubled = actions.indexOf(TwentyOneHand.DOUBLE) >= 0;
            int[] order = Shoe.shuffled(Long.parseLong(kv.get("shoe")));
            int at = Integer.parseInt(kv.get("at"));
            boolean arcade = (theirs.get(0).value() == 1 || theirs.get(0).value() == 10) && twoCard21(theirs.subList(0, 2));
            boolean natural = twoCard21(mine);
            if (arcade) {
                return payoutFor(natural ? Result.BOTH_TWENTY_ONE : Result.ARCADE_TWENTY_ONE, false, pays);
            }
            if (natural) {
                return payoutFor(Result.TWENTY_ONE, false, pays);
            }
            List<Card> hand = new ArrayList<>(mine);
            if (!doubled && stake >= 2 * t.in() && hand.size() == 2 && pays.canDouble()) {
                hand.add(Card.of(order[Math.floorMod(at++, order.length)]));
                doubled = true;
            }
            int best = TwentyOneHand.best(hand);
            if (best > 21) {
                return 0;
            }
            while (TwentyOneHand.best(theirs) < 17) {
                theirs.add(Card.of(order[Math.floorMod(at++, order.length)]));
            }
            return payoutFor(against(best, TwentyOneHand.best(theirs)), doubled, pays);
        } catch (NullPointerException | IndexOutOfBoundsException | IllegalArgumentException e) {
            throw new IllegalArgumentException("unreadable Twenty-One table data: " + data, e);
        }
    }

    /** The stake a table hand's data was dealt at, for a screen that finds one to resume. */
    public static int stakeOf(String data) {
        for (String part : data.split(";", -1)) {
            if (part.startsWith("in=")) {
                return Integer.parseInt(part.substring(3));
            }
        }
        throw new IllegalArgumentException("unreadable Twenty-One table data: " + data);
    }

    private static boolean twoCard21(List<Card> two) {
        return two.size() == 2 && TwentyOneHand.best(two) == 21;
    }

    private static String codes(List<Card> cards) {
        StringBuilder sb = new StringBuilder();
        for (Card c : cards) {
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(c.rank() - 1 + 13 * c.suit());
        }
        return sb.toString();
    }

    private static List<Card> cards(String codes) {
        List<Card> out = new ArrayList<>();
        if (codes == null || codes.isEmpty()) {
            return out;
        }
        for (String part : codes.split("\\.")) {
            int code = Integer.parseInt(part);
            if (code < 0 || code > 51) {
                throw new IllegalArgumentException("a card is 0-51: " + code);
            }
            out.add(Card.of(code));
        }
        return out;
    }

    private static Ratio ratio(String s) {
        int slash = s.indexOf('/');
        return new Ratio(Long.parseLong(s.substring(0, slash)), Long.parseLong(s.substring(slash + 1)));
    }
}
