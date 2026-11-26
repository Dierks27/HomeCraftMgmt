package com.dierks.homecraft.pack;

import java.util.ArrayList;
import java.util.List;

/**
 * 0.58.0: one playing card's face, the same for every card game in the Game Parlor (Twenty-One, Higher or Lower, Video
 * Poker, Texas Hold'em). The owner: "Can we make it so Jacks show a J, Queens are Q and such on the cards? Any way we
 * can make a bit of art for the cards to show what the cards are a bit better than a paper?"
 *
 * <p>Each game keeps its own card (an Ace is 1 in Twenty-One and 14 in the poker games); a screen turns it into a
 * face to name it ("J♠ Jack of Spades") and, for a player with HCM's pack, to draw it ({@link #modelId}, whose art
 * {@link CardArt} paints). Pure: no server.
 *
 * @param rank 1 Ace, 2-10, 11 Jack, 12 Queen, 13 King
 * @param suit 0 Spades, 1 Hearts, 2 Diamonds, 3 Clubs (the order every HCM card game already counts suits in)
 */
public record CardFace(int rank, int suit) {

    /** The card back's model name ({@code hcm:card_back}): a face-down card, for a player with the pack. */
    public static final String BACK = "card_back";
    /** {@code hcm:card_back}. */
    public static final String BACK_ID = ClubModels.NS + ":" + BACK;

    public CardFace {
        if (rank < 1 || rank > 13 || suit < 0 || suit > 3) {
            throw new IllegalArgumentException("no such card: " + rank + "/" + suit);
        }
    }

    /** A card from a game where Aces are high: {@code rank} 2-14 (14 the Ace). */
    public static CardFace aceHigh(int rank, int suit) {
        return new CardFace(rank == 14 ? 1 : rank, suit);
    }

    /** All 52, Spades then Hearts, Diamonds and Clubs, each Ace to King. */
    public static List<CardFace> deck() {
        List<CardFace> out = new ArrayList<>(52);
        for (int s = 0; s < 4; s++) {
            for (int r = 1; r <= 13; r++) {
                out.add(new CardFace(r, s));
            }
        }
        return out;
    }

    /** Hearts and Diamonds. */
    public boolean red() {
        return suit == 1 || suit == 2;
    }

    /** Its rank with Aces high: 2-10, 11 Jack, 12 Queen, 13 King, 14 Ace. */
    public int aceHighRank() {
        return rank == 1 ? 14 : rank;
    }

    /** What the card's corner says: "A", "2" … "10", "J", "Q", "K". */
    public String rankMark() {
        return switch (rank) {
            case 1 -> "A";
            case 11 -> "J";
            case 12 -> "Q";
            case 13 -> "K";
            default -> Integer.toString(rank);
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

    /** "A♠", "10♥", "J♦", "Q♣", "K♠". */
    public String shortName() {
        return rankMark() + suitMark();
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

    /** "Spades", "Hearts", "Diamonds", "Clubs". */
    public String suitName() {
        return switch (suit) {
            case 0 -> "Spades";
            case 1 -> "Hearts";
            case 2 -> "Diamonds";
            default -> "Clubs";
        };
    }

    /** "Jack of Spades". */
    public String longName() {
        return rankName() + " of " + suitName();
    }

    /** The card's id in the pack: "ace_spades", "10_hearts", "jack_diamonds". */
    public String id() {
        return rankName().toLowerCase(java.util.Locale.ROOT) + "_" + suitName().toLowerCase(java.util.Locale.ROOT);
    }

    /** Its model name: {@code card_ace_spades}. */
    public String modelName() {
        return "card_" + id();
    }

    /** {@code hcm:card_ace_spades}: what a card item is drawn as for a player with the pack. */
    public String modelId() {
        return ClubModels.NS + ":" + modelName();
    }

    @Override
    public String toString() {
        return shortName();
    }
}
