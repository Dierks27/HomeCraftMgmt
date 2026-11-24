package com.dierks.homecraft.games.chance.poker;

/**
 * 0.57.0: what five cards make in Jacks or Better, and the cards themselves (pure, no Bukkit).
 *
 * <p><b>A card is a number from 0 to 51</b>: its rank is {@code card / 4} (0 is a 2, 8 a 10, 9 a Jack, 10 a Queen, 11
 * a King, 12 an Ace) and its suit {@code card % 4} (0 Spades, 1 Hearts, 2 Diamonds, 3 Clubs). Rank first, so the
 * exact count of every hand ({@link PokerMath}) can treat hands that differ only by their suits as one.
 *
 * <p><b>The hands</b>, lowest first, are the machine's pay table rows: a pair of Jacks, Queens, Kings or Aces is the
 * least that pays ("Jacks or Better"); a lower pair pays nothing. An Ace plays high or low in a straight (A-2-3-4-5
 * and 10-J-Q-K-A), never round the corner (Q-K-A-2-3 is nothing). A Royal Flush is the 10-J-Q-K-A straight flush.
 * {@link #of} is the one evaluator the deal, the draw, the exit rule and the exact count all use.
 */
public enum PokerHand {
    /** Nothing that pays: no pair, or a pair of 10s or lower. */
    NOTHING("Nothing", "nothing"),
    JACKS_OR_BETTER("Jacks or Better", "jacks_or_better"),
    TWO_PAIR("Two Pair", "two_pair"),
    THREE_OF_A_KIND("Three of a Kind", "three_of_a_kind"),
    STRAIGHT("Straight", "straight"),
    FLUSH("Flush", "flush"),
    FULL_HOUSE("Full House", "full_house"),
    FOUR_OF_A_KIND("Four of a Kind", "four_of_a_kind"),
    STRAIGHT_FLUSH("Straight Flush", "straight_flush"),
    ROYAL_FLUSH("Royal Flush", "royal_flush");

    /** How many hands there are (the pay table's rows plus {@link #NOTHING}). */
    public static final int COUNT = values().length;
    /** The lowest rank of a pair that pays: a Jack. */
    public static final int JACK = 9;
    /** The ranks' bits of the 10-J-Q-K-A straight. */
    private static final int ROYAL = 0x1F00;
    /** The ranks' bits of the A-2-3-4-5 straight (the Ace low). */
    private static final int WHEEL = 0x100F;

    private static final PokerHand[] BY_ORDINAL = values();

    private final String label;
    private final String key;

    PokerHand(String label, String key) {
        this.label = label;
        this.key = key;
    }

    /** What the screen calls it: "Two Pair". */
    public String label() {
        return label;
    }

    /** Its key under {@code games.video_poker.pays}: {@code two_pair}. */
    public String key() {
        return key;
    }

    /** The hand by its ordinal (as {@link #ordinalOf} returns it). */
    public static PokerHand byOrdinal(int ordinal) {
        return BY_ORDINAL[ordinal];
    }

    /** What these five cards make. */
    public static PokerHand of(int[] cards) {
        if (cards.length != 5) {
            throw new IllegalArgumentException("a hand is five cards, not " + cards.length);
        }
        return BY_ORDINAL[ordinalOf(cards[0], cards[1], cards[2], cards[3], cards[4])];
    }

    /**
     * What five different cards make, as the hand's ordinal (the exact count calls this two and a half million
     * times, so it allocates nothing). The rank bits say a straight; the cards seen twice and three times say pairs,
     * trips, a full house or four of a kind.
     */
    public static int ordinalOf(int c0, int c1, int c2, int c3, int c4) {
        int seen = 0;
        int twice = 0;
        int thrice = 0;
        int b = 1 << (c0 >> 2);
        seen |= b;
        b = 1 << (c1 >> 2);
        thrice |= twice & b;
        twice |= seen & b;
        seen |= b;
        b = 1 << (c2 >> 2);
        thrice |= twice & b;
        twice |= seen & b;
        seen |= b;
        b = 1 << (c3 >> 2);
        thrice |= twice & b;
        twice |= seen & b;
        seen |= b;
        b = 1 << (c4 >> 2);
        thrice |= twice & b;
        twice |= seen & b;
        seen |= b;
        int distinct = Integer.bitCount(seen);
        switch (distinct) {
            case 5 -> {
                boolean flush = ((c0 ^ c1) & 3) == 0 && ((c0 ^ c2) & 3) == 0 && ((c0 ^ c3) & 3) == 0
                        && ((c0 ^ c4) & 3) == 0;
                boolean straight = (seen >>> Integer.numberOfTrailingZeros(seen)) == 0x1F || seen == WHEEL;
                if (straight && flush) {
                    return seen == ROYAL ? ROYAL_FLUSH.ordinal() : STRAIGHT_FLUSH.ordinal();
                }
                if (flush) {
                    return FLUSH.ordinal();
                }
                return straight ? STRAIGHT.ordinal() : NOTHING.ordinal();
            }
            case 4 -> {
                return Integer.numberOfTrailingZeros(twice) >= JACK ? JACKS_OR_BETTER.ordinal() : NOTHING.ordinal();
            }
            case 3 -> {
                return Integer.bitCount(twice) == 2 ? TWO_PAIR.ordinal() : THREE_OF_A_KIND.ordinal();
            }
            default -> {
                // two ranks: 4 + 1 (one rank seen twice or more) or 3 + 2 (two ranks seen twice)
                return Integer.bitCount(twice) == 1 ? FOUR_OF_A_KIND.ordinal() : FULL_HOUSE.ordinal();
            }
        }
    }

    // ---- a card's name -----------------------------------------------------------------------------------------

    /** The card's rank, 0 (a 2) to 12 (an Ace). */
    public static int rank(int card) {
        return card >> 2;
    }

    /** The card's suit: 0 Spades, 1 Hearts, 2 Diamonds, 3 Clubs. */
    public static int suit(int card) {
        return card & 3;
    }

    /** The card for a rank (0-12) and a suit (0-3). */
    public static int card(int rank, int suit) {
        if (rank < 0 || rank > 12 || suit < 0 || suit > 3) {
            throw new IllegalArgumentException("no card of rank " + rank + " and suit " + suit);
        }
        return rank * 4 + suit;
    }

    /** Whether the card is red (Hearts, Diamonds). */
    public static boolean red(int card) {
        int s = suit(card);
        return s == 1 || s == 2;
    }

    /** "2" … "10", "Jack", "Queen", "King", "Ace". */
    public static String rankName(int card) {
        int r = rank(card);
        return switch (r) {
            case 9 -> "Jack";
            case 10 -> "Queen";
            case 11 -> "King";
            case 12 -> "Ace";
            default -> Integer.toString(r + 2);
        };
    }

    /** "Spades", "Hearts", "Diamonds", "Clubs". */
    public static String suitName(int card) {
        return switch (suit(card)) {
            case 0 -> "Spades";
            case 1 -> "Hearts";
            case 2 -> "Diamonds";
            default -> "Clubs";
        };
    }

    /** "♠", "♥", "♦", "♣". */
    public static String suitMark(int card) {
        return switch (suit(card)) {
            case 0 -> "♠";
            case 1 -> "♥";
            case 2 -> "♦";
            default -> "♣";
        };
    }

    /** "Queen of Hearts". */
    public static String name(int card) {
        return rankName(card) + " of " + suitName(card);
    }

    /** "Q♥", "10♠": short, for a line of five. */
    public static String shortName(int card) {
        int r = rank(card);
        String rank = r >= 9 ? rankName(card).substring(0, 1) : Integer.toString(r + 2);
        return rank + suitMark(card);
    }

    /**
     * What a card counts as on the screen's stack (2 to 10, then 11 to 14 for Jack to Ace), so the five cards read at
     * a glance even where lore takes a long press.
     */
    public static int count(int card) {
        return rank(card) + 2;
    }
}
