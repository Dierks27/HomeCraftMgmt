package com.dierks.homecraft.games.chance.bingo;

import java.util.Arrays;

/**
 * One player's cards in a Bingo Night round, as its round's data (0.57.0, pure): what went in, what a card cost, each
 * card's seed and, once the round is drawn, its seed, how many cards were in play and what this player's cards made.
 * Everything needed to finish the round is in it, so the exit rule reads no config and no memory:
 * {@code v=1;in=20;price=10;cards=<seed>,<seed>}, then {@code ...;calls=<seed>;n=9;pays=27} from the moment the round
 * is drawn.
 *
 * <p><b>The exit rule</b> ({@link #settleOnExit}). A round with no call seed was left while cards were still being
 * bought: nothing was decided, so what went in comes back whole (the cards are given back). A round with one was
 * drawn, and every card's prize was worked out then, over every card in play (which no single round can see again):
 * it pays exactly that. Data that can't be read is refused (it throws), and the framework gives the stake back.
 *
 * @param in    what went in: {@code price} times the cards
 * @param price what one card cost
 * @param cards each card's seed ({@link BingoCard#deal})
 * @param drawn whether the round has been drawn
 * @param calls the round's seed (only when {@code drawn})
 * @param n     how many cards were in play (only when {@code drawn})
 * @param pays  what this player's cards made in all (only when {@code drawn})
 */
public record BingoTicket(int in, int price, long[] cards, boolean drawn, long calls, int n, int pays) {

    /** The data's version: bump it if the way a round pays ever changes. */
    public static final int VERSION = 1;
    /** The most cards one player has in a round. */
    public static final int MAX_CARDS = 4;

    public BingoTicket {
        if (cards == null || cards.length < 1 || cards.length > MAX_CARDS) {
            throw new IllegalArgumentException("a player has 1 to " + MAX_CARDS + " cards");
        }
        if (price <= 0 || in != price * cards.length) {
            throw new IllegalArgumentException("what went in is the cards' price: " + in + " for " + cards.length
                    + " at " + price);
        }
        if (drawn && (n < cards.length || pays < 0)) {
            throw new IllegalArgumentException("a drawn round has its cards in play and pays nothing or more");
        }
        cards = cards.clone();
    }

    /** New cards, before the round is drawn. */
    public static BingoTicket bought(int price, long[] cards) {
        return new BingoTicket(price * cards.length, price, cards, false, 0L, 0, 0);
    }

    /** These cards in the round drawn from {@code seed}, with {@code n} cards in play, making {@code pays}. */
    public BingoTicket drawn(long seed, int n, int pays) {
        return new BingoTicket(in, price, cards, true, seed, n, pays);
    }

    /** Each card's seed (a copy). */
    @Override
    public long[] cards() {
        return cards.clone();
    }

    /** The cards themselves. */
    public BingoCard[] dealt() {
        BingoCard[] out = new BingoCard[cards.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = BingoCard.deal(cards[i]);
        }
        return out;
    }

    /** What the round pays: what went in before it is drawn (nothing decided), then what its cards made. */
    public int payout() {
        return drawn ? pays : in;
    }

    /** The round's data. */
    public String data() {
        StringBuilder b = new StringBuilder("v=").append(VERSION).append(";in=").append(in).append(";price=")
                .append(price).append(";cards=");
        for (int i = 0; i < cards.length; i++) {
            b.append(i == 0 ? "" : ",").append(cards[i]);
        }
        if (drawn) {
            b.append(";calls=").append(calls).append(";n=").append(n).append(";pays=").append(pays);
        }
        return b.toString();
    }

    /**
     * Read a round's data back.
     *
     * @throws IllegalArgumentException when it isn't Bingo Night's data (the framework then gives the stake back)
     */
    public static BingoTicket read(String data) {
        if (data == null || data.isBlank()) {
            throw new IllegalArgumentException("no Bingo Night data");
        }
        Integer version = null;
        Integer in = null;
        Integer price = null;
        long[] cards = null;
        Long calls = null;
        Integer n = null;
        Integer pays = null;
        for (String part : data.split(";", -1)) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("not Bingo Night data: " + data);
            }
            String key = part.substring(0, eq);
            String value = part.substring(eq + 1);
            try {
                switch (key) {
                    case "v" -> version = Integer.parseInt(value);
                    case "in" -> in = Integer.parseInt(value);
                    case "price" -> price = Integer.parseInt(value);
                    case "cards" -> {
                        String[] each = value.split(",", -1);
                        cards = new long[each.length];
                        for (int i = 0; i < each.length; i++) {
                            cards[i] = Long.parseLong(each[i]);
                        }
                    }
                    case "calls" -> calls = Long.parseLong(value);
                    case "n" -> n = Integer.parseInt(value);
                    case "pays" -> pays = Integer.parseInt(value);
                    default -> throw new IllegalArgumentException("not Bingo Night data: " + data);
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("not Bingo Night data: " + data);
            }
        }
        boolean drawn = calls != null;
        if (version == null || version != VERSION || in == null || price == null || cards == null
                || drawn != (n != null) || drawn != (pays != null)) {
            throw new IllegalArgumentException("not Bingo Night data: " + data);
        }
        return new BingoTicket(in, price, cards, drawn, drawn ? calls : 0L, drawn ? n : 0, drawn ? pays : 0);
    }

    /**
     * The exit rule: what an OPEN round pays when it is finished from its data alone.
     *
     * @param seed  the round's own seed (not used: the round's call seed is in the data, one for every player in it)
     * @param stake what the round holds
     * @param data  the round's data
     * @throws IllegalArgumentException for data that isn't Bingo Night's, or that disagrees with what the round holds
     */
    public static int settleOnExit(long seed, int stake, String data) {
        BingoTicket t = read(data);
        if (t.in() != stake) {
            throw new IllegalArgumentException("the round holds " + stake + ", its data says " + t.in());
        }
        return t.payout();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof BingoTicket t && t.in == in && t.price == price && Arrays.equals(t.cards, cards)
                && t.drawn == drawn && t.calls == calls && t.n == n && t.pays == pays;
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(cards) * 31 + Long.hashCode(calls) + 7 * in + 11 * pays + 13 * n;
    }

    @Override
    public String toString() {
        return data();
    }
}
