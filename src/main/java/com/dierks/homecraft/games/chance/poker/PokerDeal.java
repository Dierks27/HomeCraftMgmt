package com.dierks.homecraft.games.chance.poker;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * 0.57.0: one hand of Video Poker, as pure state (no Bukkit): what was dealt, what is held, what the draw makes and
 * what it pays.
 *
 * <p><b>Every card is a pure function of the seed.</b> The deck is a fresh 52 cards shuffled by
 * {@code new SplittableRandom(seed)} (Fisher-Yates: every order equally likely): the first five are the deal, and the
 * next five replace the cards thrown away, in order, left to right. So the draw is exactly a draw from the 47 cards
 * left, the hand is decided by its seed and the cards held, and it can be stored as a few characters, shown again
 * after a restart, and finished for a player who walked away. This dealing rule is data format {@link #VERSION} 1.
 *
 * <p><b>The round's data carries every payout parameter</b> ({@code v=1;in=5;p=0,5,10,...}): the stake and what each
 * hand pays for it, in whole tokens (or dollars), already held under the payout cap. The draw adds the cards held
 * ({@code ;held=10110}, card by card) and what the hand made. Nothing is read from config to settle it.
 *
 * <p><b>Walking away</b> ({@link #settleOnExit}): a hand dealt but not drawn is drawn for the player with the hold
 * worth most on average for its own prizes ({@link PokerMath#bestHold}), and pays what that draw makes. It is the
 * best choice there was, never better: the cards that come are the seed's, whatever is held.
 */
public final class PokerDeal {

    /** The data format (and dealing rule) version written into every round. */
    public static final int VERSION = 1;

    private final long seed;
    private final int stake;
    private final int[] prizes;
    private final int[] deck;
    private int held;
    private boolean drawn;
    private int[] last;

    private PokerDeal(long seed, int stake, int[] prizes) {
        if (prizes.length != PokerHand.COUNT) {
            throw new IllegalArgumentException("a prize for each of the " + PokerHand.COUNT + " hands");
        }
        this.seed = seed;
        this.stake = stake;
        this.prizes = PokerMath.clean(prizes);
        this.deck = deck(seed);
    }

    /** A hand just dealt from {@code seed}: nothing held, nothing drawn. */
    public static PokerDeal deal(long seed, int stake, int[] prizes) {
        return new PokerDeal(seed, stake, prizes);
    }

    /** The 52 cards in the order the seed shuffled them (0-51, see {@link PokerHand}). */
    public static int[] deck(long seed) {
        int[] d = new int[52];
        for (int i = 0; i < 52; i++) {
            d[i] = i;
        }
        SplittableRandom r = new SplittableRandom(seed);
        for (int i = 51; i > 0; i--) {
            int j = r.nextInt(i + 1);
            int t = d[i];
            d[i] = d[j];
            d[j] = t;
        }
        return d;
    }

    /** The five cards dealt, left to right. */
    public int[] dealt() {
        int[] out = new int[5];
        System.arraycopy(deck, 0, out, 0, 5);
        return out;
    }

    /** The five cards after the draw for {@code holdMask} (bit {@code i} holds card {@code i}). */
    public int[] after(int holdMask) {
        int[] out = dealt();
        int next = 5;
        for (int i = 0; i < 5; i++) {
            if ((holdMask & (1 << i)) == 0) {
                out[i] = deck[next++];
            }
        }
        return out;
    }

    /** The cards on the table: as dealt, or as drawn. */
    public int[] cards() {
        return drawn ? last.clone() : dealt();
    }

    /** Hold card {@code i}, or let it go again (only before the draw). */
    public void toggle(int i) {
        if (drawn || i < 0 || i > 4) {
            return;
        }
        held ^= 1 << i;
    }

    /** Whether card {@code i} is held. */
    public boolean held(int i) {
        return (held & (1 << i)) != 0;
    }

    /** The cards held, a bit each. */
    public int heldMask() {
        return held;
    }

    /** Draw once: the cards not held are replaced. */
    public void draw() {
        if (drawn) {
            return;
        }
        last = after(held);
        drawn = true;
    }

    public boolean drawn() {
        return drawn;
    }

    /** What the cards on the table make (the deal before the draw). */
    public PokerHand hand() {
        return PokerHand.of(cards());
    }

    /** What the hand pays: its row's prize after the draw, 0 before. */
    public int payout() {
        return drawn ? prizes[hand().ordinal()] : 0;
    }

    /** What a hand pays, by this hand's own prizes. */
    public int prize(PokerHand h) {
        return prizes[h.ordinal()];
    }

    public int stake() {
        return stake;
    }

    public long seed() {
        return seed;
    }

    /** The prizes, a copy. */
    public int[] prizes() {
        return prizes.clone();
    }

    // ---- the round's data -----------------------------------------------------------------------------------

    /** The data a round opens with: the version, the stake and every hand's prize. */
    public static String openData(int stake, int[] prizes) {
        StringBuilder b = new StringBuilder("v=").append(VERSION).append(";in=").append(stake).append(";p=");
        int[] p = PokerMath.clean(prizes);
        for (int i = 0; i < p.length; i++) {
            b.append(i == 0 ? "" : ",").append(p[i]);
        }
        return b.toString();
    }

    /** The data now: as opened, then the cards held and what was made once drawn. */
    public String data() {
        String base = openData(stake, prizes);
        if (!drawn) {
            return base;
        }
        return base + ";held=" + mask(held) + ";hand=" + hand().key();
    }

    /** "10110": card by card, 1 held. */
    static String mask(int m) {
        StringBuilder b = new StringBuilder(5);
        for (int i = 0; i < 5; i++) {
            b.append((m & (1 << i)) != 0 ? '1' : '0');
        }
        return b.toString();
    }

    /**
     * A round's data read back.
     *
     * @param stake  what was put in
     * @param prizes each hand's prize
     * @param held   the cards held at the draw, or -1 before it
     */
    public record Saved(int stake, int[] prizes, int held) {
    }

    /** Read a round's data; throws on anything that isn't this format (the framework then gives the stake back). */
    public static Saved read(String data) {
        if (data == null) {
            throw new IllegalArgumentException("no data");
        }
        int version = -1;
        int stake = -1;
        int[] prizes = null;
        int held = -1;
        for (String part : data.split(";")) {
            int eq = part.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String k = part.substring(0, eq);
            String v = part.substring(eq + 1);
            switch (k) {
                case "v" -> version = Integer.parseInt(v);
                case "in" -> stake = Integer.parseInt(v);
                case "p" -> {
                    String[] ps = v.split(",");
                    if (ps.length != PokerHand.COUNT) {
                        throw new IllegalArgumentException("prizes for " + ps.length + " hands");
                    }
                    prizes = new int[ps.length];
                    for (int i = 0; i < ps.length; i++) {
                        prizes[i] = Integer.parseInt(ps[i]);
                        if (prizes[i] < 0) {
                            throw new IllegalArgumentException("a prize below 0");
                        }
                    }
                }
                case "held" -> {
                    if (v.length() != 5) {
                        throw new IllegalArgumentException("held is five cards");
                    }
                    held = 0;
                    for (int i = 0; i < 5; i++) {
                        if (v.charAt(i) == '1') {
                            held |= 1 << i;
                        } else if (v.charAt(i) != '0') {
                            throw new IllegalArgumentException("held is 1s and 0s");
                        }
                    }
                }
                default -> {
                    // what the hand made: shown only, worked out again from the seed
                }
            }
        }
        if (version != VERSION || stake <= 0 || prizes == null) {
            throw new IllegalArgumentException("not a Video Poker hand of version " + VERSION + ": " + data);
        }
        return new Saved(stake, prizes, held);
    }

    /** The hand on the table again, from a round's seed and data (a resumed hand: as dealt, nothing held). */
    public static PokerDeal resume(long seed, String data) {
        Saved s = read(data);
        PokerDeal d = new PokerDeal(seed, s.stake(), s.prizes());
        if (s.held() >= 0) {
            d.held = s.held();
            d.draw();
        }
        return d;
    }

    /**
     * The exit rule ({@link com.dierks.homecraft.games.ExitSettler}): a hand left before its draw is drawn with the
     * best hold for its own prizes; a hand already drawn pays what it made. Pure: the seed and the data alone.
     */
    public static int settleOnExit(long seed, int stake, String data) {
        Saved s = read(data);
        PokerDeal d = new PokerDeal(seed, s.stake(), s.prizes());
        d.held = s.held() >= 0 ? s.held() : PokerMath.bestHold(d.dealt(), s.prizes());
        d.draw();
        return d.payout();
    }

    /** "K♠ K♥ 7♦ 2♣ 9♠": the cards on the table in a line. */
    public static String line(int[] cards) {
        List<String> out = new ArrayList<>(cards.length);
        for (int c : cards) {
            out.add(PokerHand.shortName(c));
        }
        return String.join(" ", out);
    }
}
