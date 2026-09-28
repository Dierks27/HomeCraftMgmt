package com.dierks.homecraft.games.chance.hilo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * One run of Higher or Lower, as pure state (spec §5.6, R1.6, §5.2).
 *
 * <p><b>Every card is a pure function of the seed.</b> Card {@code i} is the {@code i}-th
 * {@code nextInt(52)} of {@code new SplittableRandom(seed)}: rank 2 + (draw mod 13), so 2 … 10,
 * Jack, Queen, King, Ace (high), each exactly 1 in 13; the suit, draw ÷ 13, is only for show. The
 * run is fully decided by its seed and the guesses made, which is what lets it be stored as a few
 * characters, shown again after a restart, and finished for a player who walked away. This is data
 * format {@link #VERSION} 1.
 *
 * <p><b>The pot is always whole tokens.</b> It starts at the tokens put in; a right guess on a side
 * that wins with chance {@code p} makes it {@code floor(pot · r / p)}. The same rank loses.
 *
 * <p><b>No losing "wins" (R1.3, R1.6).</b> A side is only OFFERED when a right guess would strictly
 * raise the pot — so a right guess always leaves you with more than you had, and Higher on an Ace
 * is never offered. A first card with no side to offer is replaced by the next card; if it happens
 * after a right guess, the run cashes out for you. It also cashes out by itself at the top pot
 * ({@code min(max_multiplier · in, max_payout)}) and after the last allowed guess.
 */
public final class HigherLowerRun {

    /** The data format (and dealing rule) version written into every round. */
    public static final int VERSION = 1;
    /** {@code r} is a whole number of thousandths. */
    public static final int R_SCALE = 1000;

    public static final char HIGHER = 'H';
    public static final char LOWER = 'L';
    public static final char CASH = 'C';

    /** Every rank in play, low to high (the Ace is high). */
    public static final int LOWEST = 2;
    public static final int HIGHEST = 14;

    public enum Side {
        HIGHER, LOWER;

        char code() {
            return this == HIGHER ? 'H' : 'L';
        }
    }

    /** How a run ended. */
    public enum End {
        /** A wrong guess (or the same rank): nothing back. */
        WRONG,
        /** The player cashed out. */
        CASHED,
        /** The pot reached the top: cashed out for them. */
        TOP,
        /** The last allowed guess was right: cashed out for them. */
        LAST_GUESS,
        /** The new card left no side that could raise the pot: cashed out for them. */
        NO_SIDE
    }

    /**
     * One card.
     *
     * @param rank 2-10, 11 Jack, 12 Queen, 13 King, 14 Ace
     * @param suit 0 Spades, 1 Hearts, 2 Diamonds, 3 Clubs
     */
    public record Card(int rank, int suit) {

        static Card of(int draw) {
            return new Card(LOWEST + draw % 13, draw / 13);
        }

        public boolean red() {
            return suit == 1 || suit == 2;
        }

        /** "Ace", "7", "Queen". */
        public String rankName() {
            return switch (rank) {
                case 11 -> "Jack";
                case 12 -> "Queen";
                case 13 -> "King";
                case 14 -> "Ace";
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

        /** "a 7", "an Ace", "an 8". */
        public String withArticle() {
            String n = rankName();
            return (rank == 8 || rank == 14 ? "an " : "a ") + n;
        }
    }

    /**
     * Everything a run's payouts depend on, written into the round when it opens so settling it
     * later never reads config.
     *
     * @param in         tokens put in
     * @param r          the per-guess return in thousandths (solved per stake)
     * @param cap        the top pot: {@code min(max_multiplier · in, max_payout)}
     * @param maxGuesses right guesses after which the run cashes out
     */
    public record Terms(int in, int r, int cap, int maxGuesses) {

        public Terms {
            if (in <= 0 || r <= 0 || cap < in || maxGuesses < 1) {
                throw new IllegalArgumentException("bad Higher or Lower terms: " + in + " " + r + " " + cap + " "
                        + maxGuesses);
            }
        }
    }

    /** How many ranks win on each side of {@code rank}. */
    public static int winners(int rank, Side side) {
        return side == Side.HIGHER ? HIGHEST - rank : rank - LOWEST;
    }

    /**
     * The pot after a right guess on a side that {@code k} of the 13 ranks win, or -1 when that
     * side is not offered (it could not raise the pot, or the pot is already at the top).
     */
    public static long potIfRight(long pot, int k, int r, int cap) {
        if (k <= 0 || pot >= cap) {
            return -1;
        }
        long raised = pot * 13L * r / ((long) k * R_SCALE);
        return raised > pot ? Math.min(raised, cap) : -1;
    }

    /** Whether any side is offered on {@code rank} with this pot. */
    public static boolean anySide(long pot, int rank, int r, int cap) {
        return potIfRight(pot, winners(rank, Side.HIGHER), r, cap) > 0
                || potIfRight(pot, winners(rank, Side.LOWER), r, cap) > 0;
    }

    private final Terms terms;
    private final SplittableRandom deck;
    /** The cards of this run, the shown one last (first cards that were replaced are not in it). */
    private final List<Card> cards = new ArrayList<>();
    private final StringBuilder actions = new StringBuilder();
    private int replaced;
    private long pot;
    private int guesses;
    private End end;
    private Side lastGuess;

    private HigherLowerRun(long seed, Terms terms) {
        this.terms = terms;
        this.deck = new SplittableRandom(seed);
        this.pot = terms.in();
        Card first = draw();
        while (!anySide(pot, first.rank(), terms.r(), terms.cap())) {
            if (++replaced > 1000) {
                throw new IllegalStateException("no first card can be played with these terms: " + terms);
            }
            first = draw();
        }
        cards.add(first);
    }

    /** A fresh run: the first card that has a side to offer. */
    public static HigherLowerRun start(long seed, Terms terms) {
        return new HigherLowerRun(seed, terms);
    }

    /**
     * The run after {@code actions} ({@link #HIGHER}, {@link #LOWER}, {@link #CASH}), exactly as
     * it was played.
     *
     * @throws IllegalArgumentException for an action the run could not have taken
     */
    public static HigherLowerRun replay(long seed, Terms terms, String actions) {
        HigherLowerRun run = new HigherLowerRun(seed, terms);
        for (int i = 0; i < actions.length(); i++) {
            char a = actions.charAt(i);
            switch (a) {
                case HIGHER -> run.guess(Side.HIGHER);
                case LOWER -> run.guess(Side.LOWER);
                case CASH -> run.cashOut();
                default -> throw new IllegalArgumentException("unknown Higher or Lower action " + a);
            }
        }
        return run;
    }

    // ---- actions -------------------------------------------------------------------------------

    public boolean done() {
        return end != null;
    }

    /** The rank on show. */
    public Card shown() {
        return cards.get(cards.size() - 1);
    }

    /** Whether this side can be guessed now. */
    public boolean offered(Side side) {
        return !done() && potIfRight(side) > 0;
    }

    /** What the pot becomes if a guess on this side is right, or -1 when it isn't offered. */
    public long potIfRight(Side side) {
        return potIfRight(pot, winners(shown().rank(), side), terms.r(), terms.cap());
    }

    /** Cashing out is open after at least one right guess. */
    public boolean canCashOut() {
        return !done() && guesses > 0;
    }

    /**
     * Guess: the next card is drawn and compared. Right: the pot grows (and the run may cash out by
     * itself). Wrong or the same rank: the run is over with nothing back.
     *
     * @return whether the guess was right
     */
    public boolean guess(Side side) {
        long raised = offered(side) ? potIfRight(side) : -1;
        if (raised < 0) {
            throw new IllegalArgumentException("Higher or Lower can't take " + side + " now");
        }
        actions.append(side.code());
        lastGuess = side;
        Card before = shown();
        Card next = draw();
        cards.add(next);
        boolean right = side == Side.HIGHER ? next.rank() > before.rank() : next.rank() < before.rank();
        if (!right) {
            end = End.WRONG;
            return false;
        }
        pot = raised;
        guesses++;
        if (pot >= terms.cap()) {
            end = End.TOP;
        } else if (guesses >= terms.maxGuesses()) {
            end = End.LAST_GUESS;
        } else if (!anySide(pot, next.rank(), terms.r(), terms.cap())) {
            end = End.NO_SIDE;
        }
        return true;
    }

    public void cashOut() {
        if (!canCashOut()) {
            throw new IllegalArgumentException("Higher or Lower can't cash out now");
        }
        actions.append(CASH);
        end = End.CASHED;
    }

    private Card draw() {
        return Card.of(deck.nextInt(52));
    }

    // ---- what it shows and pays -------------------------------------------------------------------

    public Terms terms() {
        return terms;
    }

    /** The cards of this run so far, oldest first; the last one is on show. */
    public List<Card> cards() {
        return Collections.unmodifiableList(cards);
    }

    /** First cards that were replaced because no side could be offered on them. */
    public int replaced() {
        return replaced;
    }

    /** What cashing out would pay now (the tokens in, before the first right guess). */
    public long pot() {
        return pot;
    }

    public int guesses() {
        return guesses;
    }

    public End end() {
        return end;
    }

    /** The side of the last guess, or {@code null} before any. */
    public Side lastGuess() {
        return lastGuess;
    }

    public String actions() {
        return actions.toString();
    }

    /** Tokens back once done (0 while playing). */
    public int payout() {
        if (end == null || end == End.WRONG) {
            return 0;
        }
        return (int) pot;
    }

    // ---- the round's data -------------------------------------------------------------------------

    /** The round's data: {@code v=1;in=10;r=915;cap=200;g=10;a=HLC}. */
    public static String data(Terms t, String actions) {
        return "v=" + VERSION + ";in=" + t.in() + ";r=" + t.r() + ";cap=" + t.cap() + ";g=" + t.maxGuesses()
                + ";a=" + actions;
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
            throw new IllegalArgumentException("Higher or Lower data version " + kv.get("v") + " is not " + VERSION);
        }
        try {
            Terms t = new Terms(Integer.parseInt(kv.get("in")), Integer.parseInt(kv.get("r")),
                    Integer.parseInt(kv.get("cap")), Integer.parseInt(kv.get("g")));
            return new Saved(t, kv.getOrDefault("a", ""));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("unreadable Higher or Lower data: " + data, e);
        }
    }

    /**
     * The side a player who left without guessing gets (spec §5.2): the likelier of the offered
     * sides, Higher on a tie (an 8). {@code null} when the run can't take a guess.
     */
    public Side likelierSide() {
        boolean h = offered(Side.HIGHER);
        boolean l = offered(Side.LOWER);
        if (h && l) {
            return winners(shown().rank(), Side.HIGHER) >= winners(shown().rank(), Side.LOWER) ? Side.HIGHER
                    : Side.LOWER;
        }
        return h ? Side.HIGHER : l ? Side.LOWER : null;
    }

    /**
     * The {@link com.dierks.homecraft.games.ExitSettler}: a run left with no guess yet takes the
     * likelier offered side and cashes out if it was right; a run with a right guess cashes out
     * (spec §5.2). Pure, from the data alone; never more than the best play from there.
     */
    public static int settleOnExit(long seed, int stake, String data) {
        Saved s = read(data);
        HigherLowerRun run = replay(seed, s.terms(), s.actions());
        if (!run.done() && run.guesses() == 0) {
            Side side = run.likelierSide();
            if (side == null) {
                throw new IllegalStateException("a run always starts on a card with a side to offer");
            }
            run.guess(side);
        }
        if (!run.done()) {
            run.cashOut();
        }
        return run.payout();
    }
}
