package com.dierks.homecraft.games.chance.holdem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What a poker hand is worth (0.57.0): the best five cards of a player's seven (their two and the board's five), its
 * category and its kickers, as one number that compares exactly the way poker does. Pure.
 *
 * <p><b>The number.</b> {@link #score()} is {@code category << 20} then up to five ranks, four bits each, in the order
 * that decides a tie: Four of a kind is the four's rank then the kicker; a Full House the three's then the pair's; Two
 * Pair the high pair, the low pair, the kicker; and so on. A bigger number wins; the same number splits. Suits never
 * break a tie (no poker rule does).
 *
 * <p><b>The wheel.</b> A-2-3-4-5 is a straight, five high (the Ace counts low only there), and the lowest straight.
 *
 * <p>Two ways in: {@link #of} finds the best five cards themselves (what a chat line shows, so a player can see the
 * kickers), by trying all 21 fives of seven; {@link #score(int[], int)} only scores, fast and without allocating, for
 * the NPCs' thousands of pretend run-outs. The tests hold the two to the same answer on every hand they try.
 */
public final class HandValue implements Comparable<HandValue> {

    /** The categories, weakest first. */
    public enum Category {
        HIGH_CARD,
        PAIR,
        TWO_PAIR,
        THREE,
        STRAIGHT,
        FLUSH,
        FULL_HOUSE,
        FOUR,
        STRAIGHT_FLUSH
    }

    private final long score;
    private final List<PokerCard> best;

    private HandValue(long score, List<PokerCard> best) {
        this.score = score;
        this.best = List.copyOf(best);
    }

    /** The number that compares hands: bigger wins, equal splits. */
    public long score() {
        return score;
    }

    public Category category() {
        return category(score);
    }

    /** The five cards that make it, the ones that matter most first (the pair, then the kickers). */
    public List<PokerCard> best() {
        return best;
    }

    /** The category a score is in. */
    public static Category category(long score) {
        return Category.values()[(int) (score >>> 20)];
    }

    /** The {@code i}-th deciding rank of a score (0 the most important). */
    static int rankAt(long score, int i) {
        return (int) ((score >>> (16 - 4 * i)) & 0xF);
    }

    @Override
    public int compareTo(HandValue o) {
        return Long.compare(score, o.score);
    }

    /**
     * Its name as a chat line says it: "a Royal Flush", "a Straight Flush, Nine high", "Four Jacks", "a Full House,
     * Queens over Twos", "a Flush, Ace high", "a Straight, Five high", "Three Sevens", "Two Pair, Kings and Fives",
     * "a Pair of Kings", "Ace high".
     */
    public String name() {
        return name(score);
    }

    /** {@link #name()} for a score. */
    public static String name(long score) {
        int a = rankAt(score, 0);
        int b = rankAt(score, 1);
        return switch (category(score)) {
            case STRAIGHT_FLUSH -> a == 14 ? "a Royal Flush" : "a Straight Flush, " + PokerCard.rankName(a) + " high";
            case FOUR -> "Four " + PokerCard.plural(a);
            case FULL_HOUSE -> "a Full House, " + PokerCard.plural(a) + " over " + PokerCard.plural(b);
            case FLUSH -> "a Flush, " + PokerCard.rankName(a) + " high";
            case STRAIGHT -> "a Straight, " + PokerCard.rankName(a) + " high";
            case THREE -> "Three " + PokerCard.plural(a);
            case TWO_PAIR -> "Two Pair, " + PokerCard.plural(a) + " and " + PokerCard.plural(b);
            case PAIR -> "a Pair of " + PokerCard.plural(a);
            case HIGH_CARD -> PokerCard.rankName(a) + " high";
        };
    }

    /** "K♥ K♣ A♠ 9♦ 4♣": the five cards, for a chat line. */
    public String cardsText() {
        StringBuilder out = new StringBuilder();
        for (PokerCard c : best) {
            out.append(out.isEmpty() ? "" : " ").append(c.shortName());
        }
        return out.toString();
    }

    @Override
    public String toString() {
        return name() + " (" + cardsText() + ")";
    }

    // ---- the best five of five to seven ---------------------------------------------------------------

    /**
     * The best hand among {@code cards} (five to seven of them, no card twice), with the five that make it.
     *
     * @throws IllegalArgumentException for fewer than five cards, more than seven, or a card twice
     */
    public static HandValue of(List<PokerCard> cards) {
        int n = cards.size();
        if (n < 5 || n > 7) {
            throw new IllegalArgumentException("a hand is the best five of five to seven cards: " + n);
        }
        if (new java.util.HashSet<>(cards).size() != n) {
            throw new IllegalArgumentException("a card twice: " + cards);
        }
        HandValue best = null;
        PokerCard[] five = new PokerCard[5];
        for (int a = 0; a < n; a++) {
            for (int b = a + 1; b < n; b++) {
                for (int c = b + 1; c < n; c++) {
                    for (int d = c + 1; d < n; d++) {
                        for (int e = d + 1; e < n; e++) {
                            five[0] = cards.get(a);
                            five[1] = cards.get(b);
                            five[2] = cards.get(c);
                            five[3] = cards.get(d);
                            five[4] = cards.get(e);
                            HandValue v = five(five);
                            if (best == null || v.score > best.score) {
                                best = v;
                            }
                        }
                    }
                }
            }
        }
        return best;
    }

    /** Exactly five cards' value, the cards ordered as they count. */
    static HandValue five(PokerCard[] cards) {
        List<PokerCard> sorted = new ArrayList<>(List.of(cards));
        // by how many of the rank there are, then by rank: the pair before its kickers, the three before the pair
        int[] count = new int[15];
        for (PokerCard c : sorted) {
            count[c.rank()]++;
        }
        sorted.sort((x, y) -> count[x.rank()] != count[y.rank()] ? count[y.rank()] - count[x.rank()]
                : y.rank() != x.rank() ? y.rank() - x.rank() : x.suit() - y.suit());
        boolean flush = true;
        for (PokerCard c : sorted) {
            flush &= c.suit() == sorted.get(0).suit();
        }
        int mask = 0;
        for (PokerCard c : sorted) {
            mask |= 1 << c.rank();
        }
        int straight = Integer.bitCount(mask) == 5 ? straightHigh(mask) : 0;
        if (straight == 5) {
            // the wheel: the Ace is the low card, so it goes last
            Collections.rotate(sorted, -1);
        }
        int[] r = new int[5];
        for (int i = 0; i < 5; i++) {
            r[i] = sorted.get(i).rank();
        }
        long score;
        if (straight > 0 && flush) {
            score = pack(Category.STRAIGHT_FLUSH, straight);
        } else if (count[r[0]] == 4) {
            score = pack(Category.FOUR, r[0], r[4]);
        } else if (count[r[0]] == 3 && count[r[3]] == 2) {
            score = pack(Category.FULL_HOUSE, r[0], r[3]);
        } else if (flush) {
            score = pack(Category.FLUSH, r[0], r[1], r[2], r[3], r[4]);
        } else if (straight > 0) {
            score = pack(Category.STRAIGHT, straight);
        } else if (count[r[0]] == 3) {
            score = pack(Category.THREE, r[0], r[3], r[4]);
        } else if (count[r[0]] == 2 && count[r[2]] == 2) {
            score = pack(Category.TWO_PAIR, r[0], r[2], r[4]);
        } else if (count[r[0]] == 2) {
            score = pack(Category.PAIR, r[0], r[2], r[3], r[4]);
        } else {
            score = pack(Category.HIGH_CARD, r[0], r[1], r[2], r[3], r[4]);
        }
        return new HandValue(score, sorted);
    }

    // ---- the fast score -------------------------------------------------------------------------------

    /**
     * The score of the best hand among the first {@code n} (5-7) card numbers in {@code codes} ({@link PokerCard#code}),
     * the same number {@link #of} gives, without finding the five or allocating anything.
     */
    public static long score(int[] codes, int n) {
        int[] rankCount = new int[15];
        int[] suitCount = new int[4];
        int[] suitMask = new int[4];
        int mask = 0;
        for (int i = 0; i < n; i++) {
            int rank = codes[i] % 13 + 2;
            int suit = codes[i] / 13;
            rankCount[rank]++;
            suitCount[suit]++;
            suitMask[suit] |= 1 << rank;
            mask |= 1 << rank;
        }
        int flushSuit = -1;
        for (int s = 0; s < 4; s++) {
            if (suitCount[s] >= 5) {
                flushSuit = s;
            }
        }
        if (flushSuit >= 0) {
            int sf = straightHigh(suitMask[flushSuit]);
            if (sf > 0) {
                return pack(Category.STRAIGHT_FLUSH, sf);
            }
        }
        int four = 0;
        int three1 = 0;
        int three2 = 0;
        int pair1 = 0;
        int pair2 = 0;
        int pair3 = 0;
        for (int rank = 14; rank >= 2; rank--) {
            switch (rankCount[rank]) {
                case 4 -> four = rank;
                case 3 -> {
                    if (three1 == 0) {
                        three1 = rank;
                    } else if (three2 == 0) {
                        three2 = rank;
                    }
                }
                case 2 -> {
                    if (pair1 == 0) {
                        pair1 = rank;
                    } else if (pair2 == 0) {
                        pair2 = rank;
                    } else if (pair3 == 0) {
                        pair3 = rank;
                    }
                }
                default -> {
                }
            }
        }
        if (four > 0) {
            return pack(Category.FOUR, four, highest(mask & ~(1 << four), 1)[0]);
        }
        if (three1 > 0 && (three2 > 0 || pair1 > 0)) {
            return pack(Category.FULL_HOUSE, three1, Math.max(three2, pair1));
        }
        if (flushSuit >= 0) {
            int[] f = highest(suitMask[flushSuit], 5);
            return pack(Category.FLUSH, f[0], f[1], f[2], f[3], f[4]);
        }
        int straight = straightHigh(mask);
        if (straight > 0) {
            return pack(Category.STRAIGHT, straight);
        }
        if (three1 > 0) {
            int[] k = highest(mask & ~(1 << three1), 2);
            return pack(Category.THREE, three1, k[0], k[1]);
        }
        if (pair2 > 0) {
            int[] k = highest(mask & ~(1 << pair1) & ~(1 << pair2), 1);
            return pack(Category.TWO_PAIR, pair1, pair2, k[0]);
        }
        if (pair1 > 0) {
            int[] k = highest(mask & ~(1 << pair1), 3);
            return pack(Category.PAIR, pair1, k[0], k[1], k[2]);
        }
        int[] h = highest(mask, 5);
        return pack(Category.HIGH_CARD, h[0], h[1], h[2], h[3], h[4]);
    }

    /** The top {@code n} ranks set in {@code mask}, highest first (0 where there are fewer). */
    private static int[] highest(int mask, int n) {
        int[] out = new int[n];
        int at = 0;
        for (int rank = 14; rank >= 2 && at < n; rank--) {
            if ((mask & (1 << rank)) != 0) {
                out[at++] = rank;
            }
        }
        return out;
    }

    /** The high card of the best straight in a rank mask (bit = rank, the Ace also low), or 0 for none. */
    static int straightHigh(int mask) {
        int m = mask | ((mask >> 14) & 1) << 1; // an Ace is also a 1
        for (int high = 14; high >= 5; high--) {
            int run = 0b11111 << (high - 4);
            if ((m & run) == run) {
                return high;
            }
        }
        return 0;
    }

    private static long pack(Category c, int... ranks) {
        long v = (long) c.ordinal() << 20;
        for (int i = 0; i < ranks.length; i++) {
            v |= (long) ranks[i] << (16 - 4 * i);
        }
        return v;
    }
}
