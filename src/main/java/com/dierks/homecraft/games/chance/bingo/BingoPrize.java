package com.dierks.homecraft.games.chance.bingo;

import com.dierks.homecraft.games.RtpLimits;

import java.util.Arrays;

/**
 * What a Bingo Night round pays (0.57.0, pure): its prize, worked out from the number of cards in play alone.
 *
 * <p><b>The prize table.</b> A round with {@code N} cards in play, every card costing {@code price}, has one prize of
 * {@link #pool}{@code (price, N)}: the whole amount closest to the game's target share of {@code price x N} from below,
 * inside the 85-95 band locked in code ({@link RtpLimits#pick}, compared exactly). It is split in two: four corners
 * gets {@code corners_share} percent of it ({@link #corners}), the line the rest ({@link #line}). Each part goes to
 * the cards that complete that pattern first, on the same call, shared evenly between them ({@link #shares}).
 *
 * <p><b>Why every card gives back exactly {@code pool / N}, whoever else plays.</b> Each card is dealt from its own
 * seed by one rule ({@link BingoCard#deal}), and the numbers are called in an order drawn from the round's own seed,
 * after every card is dealt and apart from them. So the cards in a round are exchangeable: swap any two and every
 * chance stays the same. For one pattern, call {@code W} the cards that complete it first (on the same call) and
 * {@code w} how many they are; a card's share is {@code prize / w} if it is in {@code W} and nothing otherwise. Adding
 * up {@code 1 / w} over the cards in {@code W} gives exactly 1 on every round, so the {@code N} cards' chances of
 * "being in {@code W}, divided by {@code w}" add up to exactly 1, and, being exchangeable, each one's is {@code 1 / N}:
 * every card's expected share of a part is {@code part / N}. Every pattern is always completed by some card (all 75
 * numbers are called if it takes that), so the two parts add up to {@code pool / N} per card: inside the band, because
 * the pool is. Nothing in this depends on who holds the other cards: an NPC's card or the room's own counts toward
 * {@code N} and toward the prize exactly like a player's, so a player's expected return is the same with none of them
 * or with twenty.
 *
 * <p><b>Whole amounts.</b> A share is paid in whole tokens (or dollars). When {@code w} doesn't divide a part, every
 * winner gets {@code part / w} rounded down, and the units left over go one each to the winners first in the round's
 * own shuffle of the cards ({@link BingoDraw#order()}), drawn from its seed apart from the cards: each winner gets the
 * extra unit with chance {@code leftover / w}, so its expected share is exactly {@code part / w}, and the part is paid
 * out whole. A card costs at least {@value #MIN_PRICE}: from there on every round, even of one card, has a whole prize
 * inside the band (a price of 5 has none for one card: 4 is 80 of every 100, 5 is all of it).
 */
public final class BingoPrize {

    /** The least a card may cost (tokens or dollars): at ten or more, every round has a whole prize in the band. */
    public static final int MIN_PRICE = 10;
    /** The most a card may cost. */
    public static final int MAX_PRICE = 1000;

    private BingoPrize() {
    }

    /**
     * The round's whole prize for {@code cards} cards at {@code price} each, or -1 when no whole amount gives back 85
     * to 95 of every 100 (never for a price of {@value #MIN_PRICE} or more).
     *
     * @param rtpPercent the target share, a percent inside the band
     */
    public static int pool(int price, int cards, double rtpPercent) {
        if (price <= 0 || cards <= 0) {
            return -1;
        }
        long in = (long) price * cards;
        RtpLimits.Ratio target = RtpLimits.Ratio.percent(rtpPercent);
        long below = Math.floorDiv(in * target.num(), target.den());       // the most at or under the target
        long floor = Math.ceilDiv(in * RtpLimits.MIN_RATIO.num(), RtpLimits.MIN_RATIO.den()); // the least in the band
        long[] amounts = {below, floor};
        RtpLimits.Ratio[] achievable = {new RtpLimits.Ratio(below, in), new RtpLimits.Ratio(floor, in)};
        RtpLimits.Pick p = RtpLimits.pick(target, achievable);
        return p == null ? -1 : (int) amounts[p.index()];
    }

    /** The exact return of a card in a round of {@code cards} cards: {@code pool / (price x cards)}, or NaN. */
    public static double rtp(int price, int cards, double rtpPercent) {
        int pool = pool(price, cards, rtpPercent);
        return pool < 0 ? Double.NaN : (double) pool / ((double) price * cards);
    }

    /** Four corners' part of {@code pool}: {@code share} percent, rounded down. */
    public static int corners(int pool, int share) {
        return (int) ((long) Math.max(0, pool) * Math.max(0, Math.min(100, share)) / 100);
    }

    /** The line's part of {@code pool}: what four corners doesn't take. */
    public static int line(int pool, int share) {
        return Math.max(0, pool) - corners(pool, share);
    }

    /**
     * Each winner's share of {@code part}, in the order {@code winners} are given: an even share each, rounded down,
     * and the units left over one each to the winners first in {@code order} (a shuffle of every card in play).
     *
     * @param winners the winning cards, by their place in the round (each once)
     * @param order   every card's place in the round, shuffled ({@link BingoDraw#order()})
     */
    public static int[] shares(int part, int[] winners, int[] order) {
        int w = winners.length;
        int[] out = new int[w];
        if (w == 0 || part <= 0) {
            return out;
        }
        int each = part / w;
        int left = part % w;
        Integer[] byOrder = new Integer[w];
        for (int i = 0; i < w; i++) {
            byOrder[i] = i;
        }
        int[] at = new int[order.length];
        for (int i = 0; i < order.length; i++) {
            at[order[i]] = i;
        }
        Arrays.sort(byOrder, (a, b) -> Integer.compare(at[winners[a]], at[winners[b]]));
        for (int i = 0; i < w; i++) {
            out[byOrder[i]] = each + (i < left ? 1 : 0);
        }
        return out;
    }

    /**
     * What every card in play is given, in one money: {@code [0][place]} its share of the line's part, {@code [1][place]}
     * of four corners' (0 for a card that didn't make it).
     */
    public static int[][] cardShares(BingoDraw draw, int n, int linePart, int cornersPart) {
        int[][] out = new int[2][n];
        int[] order = draw.order();
        int[] lw = draw.lineWinners();
        int[] ls = shares(linePart, lw, order);
        for (int i = 0; i < lw.length; i++) {
            out[0][lw[i]] = ls[i];
        }
        int[] cw = draw.cornersWinners();
        int[] cs = shares(cornersPart, cw, order);
        for (int i = 0; i < cw.length; i++) {
            out[1][cw[i]] = cs[i];
        }
        return out;
    }

    /**
     * The most cards a round may have at {@code price} with no share over {@code cap} (a lone winner takes a whole
     * part, so the whole prize must fit), up to {@code most}.
     */
    public static int mostCards(int price, double rtpPercent, int cap, int most) {
        int n = 0;
        for (int cards = 1; cards <= most; cards++) {
            int pool = pool(price, cards, rtpPercent);
            if (pool < 0 || pool > cap) {
                break;
            }
            n = cards;
        }
        return n;
    }
}
