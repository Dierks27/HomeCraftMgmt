package com.dierks.homecraft.games.cpu;

import com.dierks.homecraft.games.chance.holdem.HandValue;
import com.dierks.homecraft.games.chance.holdem.PokerCard;

import java.util.List;
import java.util.random.RandomGenerator;

/**
 * How an NPC plays a Texas Hold'em hand at the friendly table (0.57.0). Pure: the generator is the caller's. NPCs only
 * ever sit at the friendly table, where every chip is a pretend play chip: nothing an NPC decides touches a token, a
 * dollar or a board.
 *
 * <p><b>The level decides how well it reads the cards</b> ({@link Style}):
 * <ul>
 *   <li><b>Easy</b> (and every NPC when someone at the table wants easy): a gentle, friendly player. It judges its hand
 *       only by what it has made so far, calls along with almost anything playable whatever it costs (no sense of the
 *       price), hardly ever raises and never bluffs, and lets a big raise push it out unless it holds something
 *       strong. A child can win pots from it by simply playing good cards.</li>
 *   <li><b>Medium</b>: estimates its chance of winning by playing the hand out a hundred or so times against random
 *       cards, and mostly weighs that against the price of a call (the pot odds); raises its good hands, bluffs now and
 *       then.</li>
 *   <li><b>Hard</b>: the same estimate, more carefully (more pretend run-outs), always weighed against the pot odds;
 *       raises its strong hands harder, sizes to the pot, and bluffs more often when few are left in.</li>
 * </ul>
 *
 * <p><b>The personality colours it</b> (never its level): careful is tight (it wants a better hand to play) and rarely
 * bluffs; bold is loose and raises and bluffs more; chatty is middle of the road; a hunch now and then plays a hand on a
 * feeling, a little looser or tighter than its cards say.
 */
public final class CpuHoldem {

    /** What it does. */
    public enum Move {
        FOLD,
        CHECK_CALL,
        RAISE,
        ALL_IN
    }

    /**
     * Its decision: the move, and for a raise the amount to raise to.
     *
     * @param move what it does
     * @param to   for {@link Move#RAISE}, the chips it raises to this round; else 0
     */
    public record Decision(Move move, int to) {
    }

    /**
     * Everything an NPC sees when it is its turn (all of it a real player could see too: its own cards, the board, the
     * chips).
     *
     * @param hole       its two cards
     * @param board      the board so far (0, 3, 4 or 5 cards)
     * @param opponents  players still in besides it
     * @param pot        every chip put in so far
     * @param toCall     what it must put in to call (0: it may check)
     * @param stack      its chips behind
     * @param canRaise   whether a raise is open to it
     * @param minRaiseTo the least it can raise to
     * @param maxRaiseTo everything it has, this round
     * @param currentBet the most anyone has put in this round
     * @param bigBlind   the big blind
     */
    public record View(List<PokerCard> hole, List<PokerCard> board, int opponents, int pot, int toCall, int stack,
                       boolean canRaise, int minRaiseTo, int maxRaiseTo, int currentBet, int bigBlind) {
    }

    /**
     * How a level plays.
     *
     * @param sims       pretend run-outs for its estimate (0: it only looks at what it has made)
     * @param oddsSense  how much the price of a call counts (0 none, 1 fully)
     * @param raiseAt    the estimate it raises from
     * @param aggression how often it raises when it could
     * @param bluff      how often it raises with nothing, when few are in
     * @param potSize    its raises, as a share of the pot (0: the least raise)
     */
    public record Style(int sims, double oddsSense, double raiseAt, double aggression, double bluff, double potSize) {
    }

    static final Style EASY = new Style(0, 0.0, 0.85, 0.3, 0.0, 0.0);
    static final Style MEDIUM = new Style(120, 0.75, 0.62, 0.6, 0.05, 0.6);
    static final Style HARD = new Style(300, 1.0, 0.58, 0.8, 0.12, 0.85);

    private CpuHoldem() {
    }

    /** The style a level plays. */
    public static Style style(CpuLevel level) {
        return switch (level) {
            case EASY -> EASY;
            case MEDIUM -> MEDIUM;
            case HARD -> HARD;
        };
    }

    /** How much tighter (positive) or looser (negative) a personality plays. */
    static double tightness(CpuPersonality p) {
        return switch (p) {
            case CAREFUL -> 0.06;
            case BOLD -> -0.06;
            default -> 0.0;
        };
    }

    /** The NPC's move. */
    public static Decision decide(View v, CpuLevel level, CpuPersonality personality, RandomGenerator rng) {
        Style s = style(level);
        double strength = s.sims() > 0 ? equity(v.hole(), v.board(), v.opponents(), s.sims(), rng)
                : madeStrength(v.hole(), v.board());
        double tight = tightness(personality);
        if (personality == CpuPersonality.HUNCH && rng.nextInt(4) == 0) {
            tight += rng.nextBoolean() ? 0.08 : -0.08; // a feeling about this one
        }
        double aggression = Math.min(1.0, s.aggression() + (personality == CpuPersonality.BOLD ? 0.15 : 0.0));
        double bluff = s.bluff() * (personality == CpuPersonality.BOLD ? 2.0
                : personality == CpuPersonality.CAREFUL ? 0.3 : 1.0);
        double potOdds = v.toCall() <= 0 ? 0 : (double) v.toCall() / (v.pot() + v.toCall());
        boolean easy = s.sims() == 0;

        if (v.toCall() <= 0) {
            if (v.canRaise() && strength >= s.raiseAt() + tight && rng.nextDouble() < aggression) {
                return raise(v, s, strength, easy);
            }
            if (v.canRaise() && v.opponents() <= 2 && rng.nextDouble() < bluff) {
                return raise(v, s, 0, easy); // a bluff: the least it would raise
            }
            return new Decision(Move.CHECK_CALL, 0);
        }
        if (easy) {
            // gentle: plays along with anything playable, whatever the price; a big push only moves it with a good hand
            boolean big = v.toCall() * 2 >= v.stack(); // half or more of what it has left
            if (v.canRaise() && strength >= 0.9 && rng.nextDouble() < aggression) {
                return raise(v, s, strength, true);
            }
            if (big ? strength >= 0.7 : strength >= 0.25 + tight) {
                return new Decision(Move.CHECK_CALL, 0);
            }
            return new Decision(Move.FOLD, 0);
        }
        // what a call has to win to be worth it: the pot odds, as much as this level weighs them
        double need = s.oddsSense() * potOdds + (1 - s.oddsSense()) * 0.3 + tight;
        if (v.canRaise() && strength >= s.raiseAt() + 0.1 + tight && rng.nextDouble() < aggression) {
            return raise(v, s, strength, false);
        }
        if (strength >= need) {
            return new Decision(Move.CHECK_CALL, 0);
        }
        if (v.canRaise() && v.opponents() <= 1 && rng.nextDouble() < bluff / 2) {
            return raise(v, s, 0, false); // a re-raise on nothing, now and then
        }
        if (v.toCall() <= v.bigBlind() && strength >= need - 0.12) {
            return new Decision(Move.CHECK_CALL, 0); // cheap enough to see one more card
        }
        return new Decision(Move.FOLD, 0);
    }

    /** A raise sized by the style (the least one for easy and for a bluff), all in when that is most of the stack. */
    private static Decision raise(View v, Style s, double strength, boolean easy) {
        int to;
        if (easy || strength <= 0 || s.potSize() <= 0) {
            to = v.minRaiseTo();
        } else {
            double share = s.potSize() + (strength > 0.85 ? 0.3 : 0.0);
            to = v.currentBet() + v.toCall() + (int) Math.round(share * (v.pot() + v.toCall()));
        }
        to = Math.max(v.minRaiseTo(), Math.min(v.maxRaiseTo(), to));
        if (to >= v.maxRaiseTo() || (!easy && to * 10 >= v.maxRaiseTo() * 8 && strength >= 0.7)) {
            return new Decision(easy && strength < 0.9 ? Move.CHECK_CALL : Move.ALL_IN, 0);
        }
        return new Decision(Move.RAISE, to);
    }

    // ---- reading the cards --------------------------------------------------------------------------------

    /**
     * Its chance of winning (a share, ties counted by how many share), by playing the hand out {@code sims} times
     * against {@code opponents} hands of random cards from what is left.
     */
    public static double equity(List<PokerCard> hole, List<PokerCard> board, int opponents, int sims,
                                RandomGenerator rng) {
        int opp = Math.max(1, Math.min(9, opponents));
        boolean[] used = new boolean[PokerCard.DECK];
        int[] mine = new int[7];
        int known = 0;
        for (PokerCard c : hole) {
            used[c.code()] = true;
            mine[known++] = c.code();
        }
        for (PokerCard c : board) {
            used[c.code()] = true;
            mine[known++] = c.code();
        }
        int[] rest = new int[PokerCard.DECK];
        int left = 0;
        for (int c = 0; c < PokerCard.DECK; c++) {
            if (!used[c]) {
                rest[left++] = c;
            }
        }
        int boardLeft = 5 - board.size();
        int need = boardLeft + 2 * opp;
        int[] hand = new int[7];
        double won = 0;
        for (int sim = 0; sim < sims; sim++) {
            // a partial shuffle: just the cards this run-out needs
            for (int k = 0; k < need; k++) {
                int j = k + rng.nextInt(left - k);
                int t = rest[k];
                rest[k] = rest[j];
                rest[j] = t;
            }
            System.arraycopy(mine, 0, hand, 0, known);
            for (int k = 0; k < boardLeft; k++) {
                hand[known + k] = rest[k];
            }
            long me = HandValue.score(hand, 7);
            int ties = 1;
            boolean lost = false;
            for (int o = 0; o < opp && !lost; o++) {
                int[] theirs = hand.clone();
                theirs[0] = rest[boardLeft + 2 * o];
                theirs[1] = rest[boardLeft + 2 * o + 1];
                long them = HandValue.score(theirs, 7);
                if (them > me) {
                    lost = true;
                } else if (them == me) {
                    ties++;
                }
            }
            if (!lost) {
                won += 1.0 / ties;
            }
        }
        return sims <= 0 ? 0 : won / sims;
    }

    /**
     * What an easy NPC goes by: before the flop how playable its two cards look (a pair, high cards, the same suit, close
     * together), after it only the hand it has made so far (0-1, rough).
     */
    public static double madeStrength(List<PokerCard> hole, List<PokerCard> board) {
        PokerCard a = hole.get(0);
        PokerCard b = hole.get(1);
        if (board.isEmpty()) {
            int hi = Math.max(a.rank(), b.rank());
            int lo = Math.min(a.rank(), b.rank());
            double s = (hi + lo - 4) / 48.0; // 0 for 2-2's ranks, ~0.5 for A-K
            if (a.rank() == b.rank()) {
                s += 0.35;
            }
            if (a.suit() == b.suit()) {
                s += 0.06;
            }
            if (hi - lo == 1) {
                s += 0.04;
            }
            return Math.min(1.0, s);
        }
        int[] codes = new int[7];
        int n = 0;
        codes[n++] = a.code();
        codes[n++] = b.code();
        for (PokerCard c : board) {
            codes[n++] = c.code();
        }
        HandValue.Category cat = HandValue.category(HandValue.score(codes, n));
        return switch (cat) {
            case HIGH_CARD -> 0.15;
            case PAIR -> 0.45;
            case TWO_PAIR -> 0.7;
            case THREE -> 0.8;
            case STRAIGHT, FLUSH -> 0.88;
            case FULL_HOUSE -> 0.94;
            case FOUR, STRAIGHT_FLUSH -> 0.99;
        };
    }
}
