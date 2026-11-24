package com.dierks.homecraft.games.chance.poker;

import com.dierks.homecraft.games.RtpLimits.Ratio;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

/**
 * 0.57.0: Video Poker's exact return, by counting every hand (pure, no Bukkit).
 *
 * <p><b>What is counted.</b> Every one of the 2,598,960 hands that can be dealt, every one of the 32 ways to hold its
 * cards, and every draw for each: the return is what a player who always holds the cards worth most on average gets
 * back, per token put in, as an exact fraction. Nothing is simulated and nothing is a published figure: the number on
 * the screen is this count for the very prizes the machine pays (each hand's prize in whole tokens, held under the
 * payout cap), so a pay table or a stake whose best play lands outside 85-95 of every 100 is never offered.
 *
 * <p><b>How, in about a second.</b> Counting the 2.6 million deals times 32 holds times up to 1.5 million draws one by
 * one is out of the question. Instead every five-card hand is made once, and its result added to a tally for each of
 * its 31 smaller subsets (the hands "containing" those cards). For a dealt hand {@code H} and the cards {@code S} it
 * holds, the draws are the hands that contain {@code S} and none of the discards {@code H \ S}; inclusion-exclusion
 * over the discards (the superset Möbius sum over {@code H}'s 32 subsets) gives their total prize from those tallies.
 * Hands that differ only by which suit is which pay alike, so each of the 134,459 such kinds is worked out once and
 * counted as many times as it can be dealt.
 *
 * <p>The tallies (about 11 MB) are built the first time they are needed and kept: a shipped pay table never needs
 * them ({@link VideoPokerSettings#SHIPPED_RETURN} is this count, pinned by a test), only a configured one, a stake
 * whose prizes the payout cap holds down, or a hand finished for a player who walked away ({@link #bestHold}, which
 * counts one hand's draws directly and needs no tallies).
 */
public final class PokerMath {

    /** Every five-card hand from one deck. */
    public static final int HANDS = 2_598_960;
    /** Hands that differ only by which suit is which, counted once each. */
    public static final int KINDS = 134_459;
    /** The cards left in the deck after five are dealt. */
    static final int LEFT = 47;

    private static final int CATS = PokerHand.COUNT;
    /** {@code C(n, k)} for n up to 52, k up to 5. */
    static final long[][] BINOM = new long[53][6];
    /** {@code C(47, k)}: the draws for {@code k} cards thrown away. */
    static final long[] DRAWS = new long[6];
    /** A whole multiple of every {@link #DRAWS}, so every hold's average is compared and summed exactly. */
    static final long LCM;

    static {
        for (int n = 0; n <= 52; n++) {
            BINOM[n][0] = 1;
            for (int k = 1; k <= 5; k++) {
                BINOM[n][k] = n == 0 ? 0 : BINOM[n - 1][k - 1] + BINOM[n - 1][k];
            }
        }
        long l = 1;
        for (int k = 0; k <= 5; k++) {
            DRAWS[k] = BINOM[LEFT][k];
            l = l / gcd(l, DRAWS[k]) * DRAWS[k];
        }
        LCM = l;
    }

    private PokerMath() {
    }

    /**
     * The exact return of the best play when each hand pays {@code prizes[hand.ordinal()]} (whole tokens; NOTHING's
     * is ignored and counts as 0) for {@code stake} put in: tokens back per token in.
     */
    public static Ratio exactReturn(int[] prizes, int stake) {
        if (stake <= 0) {
            throw new IllegalArgumentException("a stake is at least 1: " + stake);
        }
        BigInteger total = Tallies.get().total(clean(prizes));
        BigInteger den = BigInteger.valueOf(HANDS).multiply(BigInteger.valueOf(LCM)).multiply(BigInteger.valueOf(stake));
        BigInteger g = total.gcd(den);
        if (g.signum() == 0) {
            return new Ratio(0, 1);
        }
        return new Ratio(total.divide(g).longValueExact(), den.divide(g).longValueExact());
    }

    /** The prizes with NOTHING's set to 0 and none below 0. */
    static int[] clean(int[] prizes) {
        if (prizes.length != CATS) {
            throw new IllegalArgumentException("a prize for each of the " + CATS + " hands, not " + prizes.length);
        }
        int[] p = prizes.clone();
        p[PokerHand.NOTHING.ordinal()] = 0;
        for (int i = 0; i < p.length; i++) {
            p[i] = Math.max(0, p[i]);
        }
        return p;
    }

    // ---- one hand: the best cards to hold --------------------------------------------------------------------

    /**
     * The cards to hold that are worth most on average with these prizes, counted draw by draw (all 2,598,960 of the
     * hand's draws over its 32 holds). Bit {@code i} of the answer holds card {@code i}. A tie goes to the hold that
     * keeps more cards, then to the lower mask, so the answer is always the same.
     */
    public static int bestHold(int[] hand, int[] prizes) {
        long[] sums = holdSums(hand, prizes);
        int best = -1;
        for (int s = 0; s < 32; s++) {
            if (best < 0 || better(sums[s], s, sums[best], best)) {
                best = s;
            }
        }
        return best;
    }

    /** The total prize over every draw for each of the hand's 32 holds (index: the mask held), counted one by one. */
    static long[] holdSums(int[] hand, int[] prizes) {
        if (hand.length != 5) {
            throw new IllegalArgumentException("a hand is five cards");
        }
        int[] p = clean(prizes);
        boolean[] dealt = new boolean[52];
        for (int c : hand) {
            if (c < 0 || c > 51 || dealt[c]) {
                throw new IllegalArgumentException("five different cards, 0-51");
            }
            dealt[c] = true;
        }
        int[] rest = new int[LEFT];
        int n = 0;
        for (int c = 0; c < 52; c++) {
            if (!dealt[c]) {
                rest[n++] = c;
            }
        }
        long[] sums = new long[32];
        int[] f = new int[5];
        for (int s = 0; s < 32; s++) {
            int k = 0;
            for (int i = 0; i < 5; i++) {
                if ((s & (1 << i)) != 0) {
                    f[k++] = hand[i];
                }
            }
            sums[s] = draws(f, k, rest, 0, p);
        }
        return sums;
    }

    /** The total prize over every way to fill {@code f[k..4]} from {@code rest[from..]}, in order. */
    private static long draws(int[] f, int k, int[] rest, int from, int[] p) {
        if (k == 5) {
            return p[PokerHand.ordinalOf(f[0], f[1], f[2], f[3], f[4])];
        }
        long sum = 0;
        for (int i = from; i <= LEFT - (5 - k); i++) {
            f[k] = rest[i];
            sum += draws(f, k + 1, rest, i + 1, p);
        }
        return sum;
    }

    /** Whether holding {@code a} (total {@code sa}) beats holding {@code b} (total {@code sb}): by average, exactly. */
    static boolean better(long sa, int a, long sb, int b) {
        long da = DRAWS[5 - Integer.bitCount(a)];
        long db = DRAWS[5 - Integer.bitCount(b)];
        int c = Long.compare(Math.multiplyExact(sa, db), Math.multiplyExact(sb, da));
        if (c != 0) {
            return c > 0;
        }
        int ka = Integer.bitCount(a);
        int kb = Integer.bitCount(b);
        return ka != kb ? ka > kb : a < b;
    }

    // ---- the tallies -------------------------------------------------------------------------------------------

    /**
     * For every set of 0 to 4 cards, how many five-card hands containing it make each hand (and, for the kinds of
     * dealt hand, one example each and how many times it can be dealt). Built once, on first use; thread-safe.
     */
    static final class Tallies {

        private static volatile Tallies built;

        /** Per size k (0-4): counts[k][rank(set) * CATS + hand]. */
        private final int[][] counts = new int[5][];
        /** One dealt hand of each kind, five cards a kind, and how many hands are of its kind. */
        private final byte[] kinds;
        private final int[] weights;

        static Tallies get() {
            Tallies t = built;
            if (t == null) {
                synchronized (Tallies.class) {
                    t = built;
                    if (t == null) {
                        t = new Tallies();
                        built = t;
                    }
                }
            }
            return t;
        }

        /** Build the tallies now (a warm-up off the main thread), if they aren't yet. */
        static void warm() {
            get();
        }

        private Tallies() {
            for (int k = 0; k <= 4; k++) {
                counts[k] = new int[Math.toIntExact(BINOM[52][k] * CATS)];
            }
            Map<Long, int[]> byKind = new HashMap<>(KINDS * 2);
            byte[] reps = new byte[KINDS * 5];
            int[] w = new int[KINDS];
            int kindsSeen = 0;
            int[] h = new int[5];
            for (int a = 0; a < 48; a++) {
                h[0] = a;
                for (int b = a + 1; b < 49; b++) {
                    h[1] = b;
                    for (int c = b + 1; c < 50; c++) {
                        h[2] = c;
                        for (int d = c + 1; d < 51; d++) {
                            h[3] = d;
                            for (int e = d + 1; e < 52; e++) {
                                h[4] = e;
                                int cat = PokerHand.ordinalOf(a, b, c, d, e);
                                for (int m = 0; m < 31; m++) {
                                    int k = 0;
                                    long rank = 0;
                                    for (int i = 0; i < 5; i++) {
                                        if ((m & (1 << i)) != 0) {
                                            rank += BINOM[h[i]][++k];
                                        }
                                    }
                                    counts[k][(int) rank * CATS + cat]++;
                                }
                                long key = kind(h);
                                int[] seen = byKind.get(key);
                                if (seen == null) {
                                    seen = new int[] {kindsSeen++};
                                    byKind.put(key, seen);
                                    int at = seen[0] * 5;
                                    for (int i = 0; i < 5; i++) {
                                        reps[at + i] = (byte) h[i];
                                    }
                                }
                                w[seen[0]]++;
                            }
                        }
                    }
                }
            }
            if (kindsSeen != KINDS) {
                throw new IllegalStateException("expected " + KINDS + " kinds of hand, counted " + kindsSeen);
            }
            this.kinds = reps;
            this.weights = w;
        }

        /** A hand's kind: its four suits' rank sets, largest first, so swapping suits doesn't change it. */
        static long kind(int[] h) {
            long[] s = new long[4];
            for (int c : h) {
                s[c & 3] |= 1L << (c >> 2);
            }
            java.util.Arrays.sort(s);
            return (s[3] << 39) | (s[2] << 26) | (s[1] << 13) | s[0];
        }

        /**
         * The best play's total over every deal, in units of {@code 1 / LCM} of a token: for each kind of hand, the
         * best hold's total prize over its draws, scaled to a common denominator, times how many deals are of that kind.
         */
        BigInteger total(int[] p) {
            long[][] pay = new long[5][];
            for (int k = 0; k <= 4; k++) {
                int[] cnt = counts[k];
                long[] out = new long[cnt.length / CATS];
                for (int r = 0; r < out.length; r++) {
                    long sum = 0;
                    int at = r * CATS;
                    for (int cat = 1; cat < CATS; cat++) {
                        sum += (long) cnt[at + cat] * p[cat];
                    }
                    out[r] = sum;
                }
                pay[k] = out;
            }
            long[] g = new long[32];
            int[] h = new int[5];
            BigInteger total = BigInteger.ZERO;
            long chunk = 0;
            for (int kind = 0; kind < KINDS; kind++) {
                for (int i = 0; i < 5; i++) {
                    h[i] = kinds[kind * 5 + i];
                }
                for (int m = 0; m < 32; m++) {
                    if (m == 31) {
                        g[m] = p[PokerHand.ordinalOf(h[0], h[1], h[2], h[3], h[4])];
                        continue;
                    }
                    int k = 0;
                    long rank = 0;
                    for (int i = 0; i < 5; i++) {
                        if ((m & (1 << i)) != 0) {
                            rank += BINOM[h[i]][++k];
                        }
                    }
                    g[m] = pay[k][(int) rank];
                }
                // the superset Möbius sum: g[S] becomes the total over draws that keep S and none of H \ S
                for (int i = 0; i < 5; i++) {
                    int bit = 1 << i;
                    for (int m = 0; m < 32; m++) {
                        if ((m & bit) == 0) {
                            g[m] -= g[m | bit];
                        }
                    }
                }
                int best = 0;
                for (int m = 1; m < 32; m++) {
                    if (better(g[m], m, g[best], best)) {
                        best = m;
                    }
                }
                long scaled = Math.multiplyExact(g[best], LCM / DRAWS[5 - Integer.bitCount(best)]);
                long add = Math.multiplyExact(scaled, (long) weights[kind]);
                if (chunk > Long.MAX_VALUE / 2 - add) {
                    total = total.add(BigInteger.valueOf(chunk));
                    chunk = 0;
                }
                chunk += add;
            }
            return total.add(BigInteger.valueOf(chunk));
        }

        /**
         * One dealt hand's 32 hold totals worked out from the tallies, as {@link #total} does for every kind (for
         * tests: they must equal {@link #holdSums}, counted draw by draw). {@code sorted} is five cards, lowest first.
         */
        long[] holdTotals(int[] sorted, int[] p) {
            long[] g = new long[32];
            for (int m = 0; m < 32; m++) {
                if (m == 31) {
                    g[m] = p[PokerHand.ordinalOf(sorted[0], sorted[1], sorted[2], sorted[3], sorted[4])];
                    continue;
                }
                int k = 0;
                long rank = 0;
                for (int i = 0; i < 5; i++) {
                    if ((m & (1 << i)) != 0) {
                        rank += BINOM[sorted[i]][++k];
                    }
                }
                long sum = 0;
                for (int cat = 1; cat < CATS; cat++) {
                    sum += (long) counts[k][(int) rank * CATS + cat] * p[cat];
                }
                g[m] = sum;
            }
            for (int i = 0; i < 5; i++) {
                int bit = 1 << i;
                for (int m = 0; m < 32; m++) {
                    if ((m & bit) == 0) {
                        g[m] -= g[m | bit];
                    }
                }
            }
            return g;
        }

        /** How many five-card hands make each hand (for tests: the textbook counts). */
        long[] handCounts() {
            long[] out = new long[CATS];
            int[] cnt = counts[0];
            for (int cat = 0; cat < CATS; cat++) {
                out[cat] = cnt[cat];
            }
            return out;
        }
    }

    /** Build the tallies now, if they aren't yet (the game warms them off the main thread when it starts). */
    public static void warm() {
        Tallies.warm();
    }

    private static long gcd(long a, long b) {
        while (b != 0) {
            long t = a % b;
            a = b;
            b = t;
        }
        return Math.abs(a);
    }
}
