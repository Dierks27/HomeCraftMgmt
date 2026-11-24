package com.dierks.homecraft.games.chance.holdem;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * One card of Texas Hold'em's 52 (0.57.0). Aces are high (14), and low only inside a five-high straight (the wheel),
 * which the evaluator handles ({@link HandValue}).
 *
 * <p>A card is also a number, {@link #code()} 0-51 ({@code suit * 13 + rank - 2}), which is what a deck is shuffled as.
 *
 * @param rank 2-14 (11 Jack, 12 Queen, 13 King, 14 Ace)
 * @param suit 0 Spades, 1 Hearts, 2 Diamonds, 3 Clubs
 */
public record PokerCard(int rank, int suit) {

    /** Cards in a deck. */
    public static final int DECK = 52;

    public PokerCard {
        if (rank < 2 || rank > 14 || suit < 0 || suit > 3) {
            throw new IllegalArgumentException("no such card: " + rank + "/" + suit);
        }
    }

    /** The card numbered {@code code} (0-51). */
    public static PokerCard of(int code) {
        return new PokerCard(code % 13 + 2, code / 13);
    }

    /** Its number, 0-51. */
    public int code() {
        return suit * 13 + rank - 2;
    }

    /** Hearts and Diamonds. */
    public boolean red() {
        return suit == 1 || suit == 2;
    }

    /** "Ace", "King", "10", "2". */
    public String rankName() {
        return rankName(rank);
    }

    /** "Ace", "King", "10", "2" for a rank 2-14. */
    public static String rankName(int rank) {
        return switch (rank) {
            case 14 -> "Ace";
            case 13 -> "King";
            case 12 -> "Queen";
            case 11 -> "Jack";
            default -> Integer.toString(rank);
        };
    }

    /** "Aces", "Kings", "Tens", "Sixes", "Twos": a rank named as a group of them. */
    public static String plural(int rank) {
        return switch (rank) {
            case 14 -> "Aces";
            case 13 -> "Kings";
            case 12 -> "Queens";
            case 11 -> "Jacks";
            case 10 -> "Tens";
            case 9 -> "Nines";
            case 8 -> "Eights";
            case 7 -> "Sevens";
            case 6 -> "Sixes";
            case 5 -> "Fives";
            case 4 -> "Fours";
            case 3 -> "Threes";
            default -> "Twos";
        };
    }

    /** "Spades", "Hearts", "Diamonds", "Clubs". */
    public String suitName() {
        return switch (suit) {
            case 0 -> "Spades";
            case 1 -> "Hearts";
            case 2 -> "Diamonds";
            default -> "Clubs";
        };
    }

    /** ♠ ♥ ♦ ♣ (all below U+FFFF, so Bedrock draws them). */
    public String suitMark() {
        return switch (suit) {
            case 0 -> "♠";
            case 1 -> "♥";
            case 2 -> "♦";
            default -> "♣";
        };
    }

    /** "Ace of Spades". */
    public String name() {
        return rankName() + " of " + suitName();
    }

    /** "A♠", "10♥", "K♦": what a chat line shows. */
    public String shortName() {
        String r = switch (rank) {
            case 14 -> "A";
            case 13 -> "K";
            case 12 -> "Q";
            case 11 -> "J";
            default -> Integer.toString(rank);
        };
        return r + suitMark();
    }

    @Override
    public String toString() {
        return shortName();
    }

    /**
     * Cards written as text, for tests and the admin's eyes: "As Kd 10h 2c" (a rank {@code 2-9, T or 10, J, Q, K, A}
     * then a suit {@code s h d c}), separated by spaces.
     */
    public static List<PokerCard> parse(String text) {
        List<PokerCard> out = new ArrayList<>();
        for (String t : text.trim().split("\\s+")) {
            if (t.isEmpty()) {
                continue;
            }
            String r = t.substring(0, t.length() - 1).toUpperCase(java.util.Locale.ROOT);
            char s = Character.toLowerCase(t.charAt(t.length() - 1));
            int rank = switch (r) {
                case "A" -> 14;
                case "K" -> 13;
                case "Q" -> 12;
                case "J" -> 11;
                case "T", "10" -> 10;
                default -> Integer.parseInt(r);
            };
            int suit = "shdc".indexOf(s);
            out.add(new PokerCard(rank, suit));
        }
        return out;
    }

    /**
     * A freshly shuffled deck, as card numbers: a Fisher-Yates shuffle of all 52 with {@code random} (in play a
     * {@link java.security.SecureRandom}, one per table: every hand is a real new deck, never a shoe carried on).
     */
    public static int[] shuffled(RandomGenerator random) {
        int[] deck = new int[DECK];
        for (int i = 0; i < DECK; i++) {
            deck[i] = i;
        }
        for (int i = DECK - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int t = deck[i];
            deck[i] = deck[j];
            deck[j] = t;
        }
        return deck;
    }
}
