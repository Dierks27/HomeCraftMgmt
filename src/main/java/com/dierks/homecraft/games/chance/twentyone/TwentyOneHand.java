package com.dierks.homecraft.games.chance.twentyone;

import com.dierks.homecraft.games.RtpLimits.Ratio;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * One hand of Twenty-One, as pure state (spec §5.4, §5.2).
 *
 * <p><b>Every card is a pure function of the seed.</b> Card {@code i} is the {@code i}-th
 * {@code nextInt(52)} of {@code new SplittableRandom(seed)}: its rank is the draw mod 13 (Ace, 2 …
 * 10, Jack, Queen, King) and its suit the draw ÷ 13 (suits are only for show; each rank is exactly
 * 1 in 13, so the deck is "infinite"). Cards are dealt in a fixed order — you, the Arcade's up card,
 * you, the Arcade's hole card — and every later card in the order it is needed. So a hand is fully
 * decided by its seed and the actions taken, which is what lets it be stored as a few characters,
 * shown again after a restart, and finished for a player who walked away. This dealing rule is
 * data format {@link #VERSION} 1; a different rule would need a new version, never a changed 1.
 *
 * <p>The rules: the Arcade checks for Twenty-One when it shows an Ace or a ten-count (and a hand
 * where it has one ends at once: your own Twenty-One gets your tokens back, anything else loses);
 * a two-card 21 is "Twenty-One!"; you may Hit, Stand, or Double on your first two cards (put the
 * same in again, take exactly one card, stand); reaching 21 stands for you; the Arcade then draws
 * to 17 and stands on every 17. No split, insurance or surrender.
 */
public final class TwentyOneHand {

    /** The data format (and dealing rule) version written into every round. */
    public static final int VERSION = 1;

    public static final char HIT = 'H';
    public static final char STAND = 'S';
    public static final char DOUBLE = 'D';

    /** How a hand ended. */
    public enum Result {
        /** A two-card 21 against no Arcade 21. */
        TWENTY_ONE,
        WIN,
        PUSH,
        LOSE,
        /** Over 21. */
        BUST,
        /** The Arcade's two-card 21 ended the hand at once. */
        ARCADE_TWENTY_ONE,
        /** Both had a two-card 21: the tokens come back. */
        BOTH_TWENTY_ONE
    }

    /**
     * One card.
     *
     * @param rank 1 = Ace, 2-10, 11 = Jack, 12 = Queen, 13 = King
     * @param suit 0 Spades, 1 Hearts, 2 Diamonds, 3 Clubs
     */
    public record Card(int rank, int suit) {

        static Card of(int draw) {
            return new Card(draw % 13 + 1, draw / 13);
        }

        /** What it counts: an Ace 1 (or 11, see {@link #best}), a picture card 10. */
        public int value() {
            return Math.min(rank, 10);
        }

        public boolean red() {
            return suit == 1 || suit == 2;
        }

        /** "Ace", "7", "Queen". */
        public String rankName() {
            return switch (rank) {
                case 1 -> "Ace";
                case 11 -> "Jack";
                case 12 -> "Queen";
                case 13 -> "King";
                default -> Integer.toString(rank);
            };
        }

        public String suitName() {
            return switch (suit) {
                case 0 -> "Spades";
                case 1 -> "Hearts";
                case 2 -> "Diamonds";
                default -> "Clubs";
            };
        }

        /** "Queen of Hearts". */
        public String name() {
            return rankName() + " of " + suitName();
        }
    }

    /**
     * Everything a hand's payouts depend on, written into the round when it opens so settling it
     * later never reads config (spec §5.2).
     *
     * @param in    tokens put in for the hand
     * @param m     the win multiplier solved for this stake (a win adds {@code floor(in·m)})
     * @param bonus natural_bonus (a Twenty-One! adds {@code floor(in·m·bonus)})
     * @param cap   the payout cap ({@code games.max_payout}, never below the largest stake)
     */
    public record Terms(int in, Ratio m, Ratio bonus, int cap) {

        public Terms {
            if (in <= 0 || cap < in || m == null || bonus == null || m.num() < 0 || bonus.num() < 0) {
                throw new IllegalArgumentException("bad Twenty-One terms: " + in + " " + m + " " + bonus + " " + cap);
            }
        }

        public TwentyOneMath.Payouts payouts() {
            return payouts(in, m, bonus, cap);
        }

        /** The whole-token payouts: win = in + floor(in·m), Twenty-One! = in + floor(in·m·bonus), double win = 2in + floor(2in·m); all capped. */
        static TwentyOneMath.Payouts payouts(int in, Ratio m, Ratio bonus, int cap) {
            BigInteger mn = BigInteger.valueOf(m.num());
            BigInteger md = BigInteger.valueOf(m.den());
            int win = add(in, floor(BigInteger.valueOf(in).multiply(mn), md), cap);
            int natural = add(in, floor(BigInteger.valueOf(in).multiply(mn).multiply(BigInteger.valueOf(bonus.num())),
                    md.multiply(BigInteger.valueOf(bonus.den()))), cap);
            int doubleWin = add(2 * in, floor(BigInteger.valueOf(2L * in).multiply(mn), md), cap);
            return new TwentyOneMath.Payouts(in, win, Math.min(in, cap), natural, doubleWin, Math.min(2 * in, cap));
        }

        private static long floor(BigInteger num, BigInteger den) {
            return num.divide(den).longValueExact();
        }

        private static int add(int base, long extra, int cap) {
            return (int) Math.min(cap, base + extra);
        }
    }

    private final Terms terms;
    private final TwentyOneMath.Payouts pays;
    private final SplittableRandom deck;
    private final List<Card> player = new ArrayList<>();
    private final List<Card> dealer = new ArrayList<>();
    private final StringBuilder actions = new StringBuilder();
    private boolean doubled;
    private Result result;

    private TwentyOneHand(long seed, Terms terms) {
        this.terms = terms;
        this.pays = terms.payouts();
        this.deck = new SplittableRandom(seed);
        player.add(draw());
        dealer.add(draw());
        player.add(draw());
        dealer.add(draw());
        boolean mine = isTwentyOne(player);
        boolean peek = dealer.get(0).value() == 1 || dealer.get(0).value() == 10;
        if (peek && isTwentyOne(dealer)) {
            result = mine ? Result.BOTH_TWENTY_ONE : Result.ARCADE_TWENTY_ONE;
        } else if (mine) {
            result = Result.TWENTY_ONE;
        }
    }

    /** A fresh deal. */
    public static TwentyOneHand deal(long seed, Terms terms) {
        return new TwentyOneHand(seed, terms);
    }

    /**
     * The hand after {@code actions} ({@link #HIT}, {@link #STAND}, {@link #DOUBLE}), exactly as it
     * was played.
     *
     * @throws IllegalArgumentException for an action the hand could not have taken
     */
    public static TwentyOneHand replay(long seed, Terms terms, String actions) {
        TwentyOneHand h = new TwentyOneHand(seed, terms);
        for (int i = 0; i < actions.length(); i++) {
            char a = actions.charAt(i);
            switch (a) {
                case HIT -> h.hit();
                case STAND -> h.stand();
                case DOUBLE -> h.doubleDown();
                default -> throw new IllegalArgumentException("unknown Twenty-One action " + a);
            }
        }
        return h;
    }

    // ---- actions -------------------------------------------------------------------------------

    public boolean done() {
        return result != null;
    }

    /** Hit and Stand: while the hand is yours to play. */
    public boolean canPlay() {
        return !done();
    }

    /** Double: on your first two cards, when a doubled win pays more than the doubled tokens in. */
    public boolean canDouble() {
        return !done() && player.size() == 2 && pays.canDouble();
    }

    /** Take a card. Over 21 loses; exactly 21 stands for you. */
    public void hit() {
        require(canPlay(), "hit");
        actions.append(HIT);
        player.add(draw());
        int b = best(player);
        if (b > 21) {
            result = Result.BUST;
        } else if (b == 21) {
            finish();
        }
    }

    public void stand() {
        require(canPlay(), "stand");
        actions.append(STAND);
        finish();
    }

    /** Put the same in again, take exactly one card, stand. */
    public void doubleDown() {
        require(canDouble(), "double");
        actions.append(DOUBLE);
        doubled = true;
        player.add(draw());
        if (best(player) > 21) {
            result = Result.BUST;
        } else {
            finish();
        }
    }

    private void finish() {
        while (best(dealer) < 17) {
            dealer.add(draw());
        }
        int mine = best(player);
        int theirs = best(dealer);
        result = theirs > 21 || mine > theirs ? Result.WIN : mine == theirs ? Result.PUSH : Result.LOSE;
    }

    private Card draw() {
        return Card.of(deck.nextInt(52));
    }

    private static void require(boolean ok, String what) {
        if (!ok) {
            throw new IllegalArgumentException("a Twenty-One hand can't " + what + " now");
        }
    }

    // ---- what it shows and pays -------------------------------------------------------------------

    public Terms terms() {
        return terms;
    }

    public TwentyOneMath.Payouts pays() {
        return pays;
    }

    public List<Card> playerCards() {
        return Collections.unmodifiableList(player);
    }

    /** Every Arcade card, the hole card included (the screen hides it while {@link #holeHidden}). */
    public List<Card> dealerCards() {
        return Collections.unmodifiableList(dealer);
    }

    /** The Arcade's hole card stays face down until the hand is over. */
    public boolean holeHidden() {
        return !done();
    }

    public boolean doubled() {
        return doubled;
    }

    public String actions() {
        return actions.toString();
    }

    public Result result() {
        return result;
    }

    /** Tokens put in: the stake, twice after a Double. */
    public int putIn() {
        return doubled ? 2 * terms.in() : terms.in();
    }

    /** Tokens back once done (0 while playing), capped. */
    public int payout() {
        if (result == null) {
            return 0;
        }
        return switch (result) {
            case TWENTY_ONE -> pays.twentyOne();
            case WIN -> doubled ? pays.doubleWin() : pays.win();
            case PUSH -> doubled ? pays.doublePush() : pays.push();
            case BOTH_TWENTY_ONE -> pays.push();
            case LOSE, BUST, ARCADE_TWENTY_ONE -> 0;
        };
    }

    /** The best total of these cards (an Ace is 11 when that doesn't go over 21). */
    public static int best(List<Card> cards) {
        int total = 0;
        boolean ace = false;
        for (Card c : cards) {
            total += c.value();
            ace |= c.rank() == 1;
        }
        return TwentyOneMath.best(total, ace);
    }

    /** The total counting every Ace as 1, and whether there is an Ace (what the strategy looks up). */
    public static int hardTotal(List<Card> cards) {
        int total = 0;
        for (Card c : cards) {
            total += c.value();
        }
        return total;
    }

    public static boolean hasAce(List<Card> cards) {
        for (Card c : cards) {
            if (c.rank() == 1) {
                return true;
            }
        }
        return false;
    }

    /** Whether an Ace could still count 11 (the total reads "7 or 17"). */
    public static boolean soft(List<Card> cards) {
        return hasAce(cards) && hardTotal(cards) + 10 <= 21;
    }

    private static boolean isTwentyOne(List<Card> two) {
        return two.size() == 2 && best(two) == 21;
    }

    // ---- the round's data -------------------------------------------------------------------------

    /**
     * The round's data: {@code v=1;in=5;m=4/5;nb=3/2;cap=250;a=HS} — the version, every payout
     * parameter and every action so far.
     */
    public static String data(Terms t, String actions) {
        return "v=" + VERSION + ";in=" + t.in() + ";m=" + t.m().num() + "/" + t.m().den()
                + ";nb=" + t.bonus().num() + "/" + t.bonus().den() + ";cap=" + t.cap() + ";a=" + actions;
    }

    /** A round's data read back. */
    public record Saved(Terms terms, String actions) {
    }

    /** @throws IllegalArgumentException for data this version can't read */
    public static Saved read(String data) {
        Map<String, String> kv = new LinkedHashMap<>();
        for (String part : data.split(";", -1)) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                kv.put(part.substring(0, eq), part.substring(eq + 1));
            }
        }
        if (!Integer.toString(VERSION).equals(kv.get("v"))) {
            throw new IllegalArgumentException("Twenty-One data version " + kv.get("v") + " is not " + VERSION);
        }
        try {
            Terms t = new Terms(Integer.parseInt(kv.get("in")), ratio(kv.get("m")), ratio(kv.get("nb")),
                    Integer.parseInt(kv.get("cap")));
            return new Saved(t, kv.getOrDefault("a", ""));
        } catch (NullPointerException | NumberFormatException e) {
            throw new IllegalArgumentException("unreadable Twenty-One data: " + data, e);
        }
    }

    private static Ratio ratio(String s) {
        int slash = s.indexOf('/');
        return new Ratio(Long.parseLong(s.substring(0, slash)), Long.parseLong(s.substring(slash + 1)));
    }

    /**
     * The {@link com.dierks.homecraft.games.ExitSettler}: a hand left as it is stands (spec §5.2).
     * Pure, from the data alone. If the tokens for a Double went in but the Double itself was not
     * recorded yet ({@code stake} is twice the hand's), the Double is taken, as it was paid for.
     */
    public static int settleOnExit(long seed, int stake, String data) {
        Saved s = read(data);
        TwentyOneHand h = replay(seed, s.terms(), s.actions());
        if (!h.done() && !h.doubled() && stake >= 2 * s.terms().in() && h.canDouble()) {
            h.doubleDown();
        }
        if (!h.done()) {
            h.stand();
        }
        return h.payout();
    }
}
