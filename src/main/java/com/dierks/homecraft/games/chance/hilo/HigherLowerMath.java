package com.dierks.homecraft.games.chance.hilo;

import com.dierks.homecraft.games.RtpLimits;
import com.dierks.homecraft.games.RtpLimits.Ratio;
import com.dierks.homecraft.games.chance.hilo.HigherLowerRun.Side;
import com.dierks.homecraft.games.chance.hilo.HigherLowerRun.Terms;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The exact odds of Higher or Lower (spec §5.6, R1.6): what the best possible play gives back for
 * one stake, and the per-guess return {@code r} that lands it on the {@code rtp} target.
 *
 * <p><b>Why a DP, not a formula.</b> The pot is floored to whole tokens after every right guess,
 * capped at the top pot, stopped after the last allowed guess, and a side is only offered when it
 * would raise the pot — so "the best play gives back r" is simply false once real tokens are
 * involved. The best play is an optimal-stopping problem over (right guesses so far, the pot, the
 * card on show): at each state take the better of cashing out and each offered side's expected
 * value.
 *
 * <p><b>What it finds.</b> With {@code r ≤ 1} a guess's expected pot is at most {@code r} times the
 * pot, so after the first right guess the best play always cashes out: the one compulsory guess is
 * where the value is, and the floors can even make the less likely side the better first guess.
 * That is what the screen means by "every extra guess risks what you have". The per-stake solve
 * ({@link #rtp}) uses that bound to skip the deeper levels; the full table ({@link #policy}) is
 * kept for the soak and the exit-rule checks, and the tests pin that the two agree exactly.
 *
 * <p><b>Why exact.</b> Each next card is 1 in 13, so a value after {@code n} more guesses is a whole
 * number of {@code 13^-n}: the full table keeps every value as a {@code long} count of
 * {@code 13^-(guesses left)} (up to {@link #MAX_GUESSES} guesses, where that still fits), and the
 * return is an exact {@link Ratio} that {@link RtpLimits#pick(Ratio, Ratio[])} compares with the
 * target without rounding.
 *
 * <p><b>The free parameter.</b> {@code r} is a whole number of thousandths in [0.800, 1.000]; each
 * of those 201 values is solved and the R1.1 rule picks one. Where several give the same best-play
 * return, the largest {@code r} is taken — the same number for the best play, and a little more for
 * any other play.
 *
 * <p>Pure: no Bukkit, no config. Per-stake tables are cached.
 */
public final class HigherLowerMath {

    /** The range of {@code r}, in thousandths (spec: r ∈ [0.80, 1.00]). */
    public static final int R_LOW = 800;
    public static final int R_HIGH = 1000;
    /** The most right guesses the full table handles (every exact value still fits in a {@code long}). */
    public static final int MAX_GUESSES = 10;

    private static final long[] POW13 = new long[MAX_GUESSES + 1];

    static {
        POW13[0] = 1;
        for (int i = 1; i <= MAX_GUESSES; i++) {
            POW13[i] = POW13[i - 1] * 13;
        }
    }

    private static final Map<List<Integer>, Ratio[]> CACHE = new ConcurrentHashMap<>();

    private HigherLowerMath() {
    }

    /**
     * The best-play return of these terms, exactly, or {@code null} when no first card can be
     * played.
     *
     * <p>For {@code r ≤ 1} this is the DP evaluated top-down with an upper bound: from any pot, a
     * guess's expected pot is at most {@code r · pot ≤ pot} (by induction over the levels, since
     * no state is worth more than its pot), so after the first right guess cashing out is provably
     * best and only the compulsory first guess needs weighing — 13 cards × 2 sides, however big
     * the top pot. Above 1 the full table is solved. Tests pin that both agree exactly.
     */
    public static Ratio rtp(Terms t) {
        if (t.r() > HigherLowerRun.R_SCALE) {
            return new Dp(t, false).rtp;
        }
        long sum = 0;
        int playable = 0;
        for (int c = HigherLowerRun.LOWEST; c <= HigherLowerRun.HIGHEST; c++) {
            long best = -1;
            for (Side side : Side.values()) {
                int k = HigherLowerRun.winners(c, side);
                long raised = HigherLowerRun.potIfRight(t.in(), k, t.r(), t.cap());
                if (raised > 0) {
                    best = Math.max(best, k * raised);
                }
            }
            if (best >= 0) {
                sum += best;
                playable++;
            }
        }
        return playable == 0 ? null : new Ratio(sum, (long) playable * t.in() * 13);
    }

    /** The full-table DP's value (the reference the top-down {@link #rtp} is checked against). */
    static Ratio fullRtp(Terms t) {
        return new Dp(t, false).rtp;
    }

    /** The best play itself (every state's value and choice): for the soak and the exit-rule checks. */
    public static Policy policy(Terms t) {
        return new Policy(new Dp(t, true));
    }

    /**
     * Every achievable best-play return for one stake, one per {@code r} from {@link #R_HIGH} down
     * to {@link #R_LOW} ({@code null} where no first card can be played). Cached.
     */
    public static Ratio[] achievable(int in, int cap, int maxGuesses) {
        return CACHE.computeIfAbsent(List.of(in, cap, maxGuesses), k -> {
            Ratio[] out = new Ratio[R_HIGH - R_LOW + 1];
            for (int i = 0; i < out.length; i++) {
                out[i] = rtp(new Terms(in, R_HIGH - i, cap, maxGuesses));
            }
            return out;
        });
    }

    /** The {@code r} (thousandths) at an index of {@link #achievable}. */
    public static int rAt(int index) {
        return R_HIGH - index;
    }

    /**
     * The whole best play for one set of terms.
     */
    public static final class Policy {

        private final Dp dp;

        private Policy(Dp dp) {
            this.dp = dp;
        }

        public Terms terms() {
            return dp.t;
        }

        /** The exact best-play return (as {@link #rtp(Terms)}). */
        public Ratio rtp() {
            return dp.rtp;
        }

        /**
         * The best choice after {@code guesses} right guesses with this pot and card on show:
         * a side, or {@code null} to cash out. With no guess yet there is always a side (a first
         * card with none is never shown).
         */
        public Side choose(int guesses, long pot, int rank) {
            if (guesses == 0) {
                long h = dp.guessValue(0, pot, rank, Side.HIGHER);
                long l = dp.guessValue(0, pot, rank, Side.LOWER);
                if (h < 0 && l < 0) {
                    return null;
                }
                return h >= l ? Side.HIGHER : Side.LOWER;
            }
            long cash = dp.cashValue(guesses, pot);
            long h = dp.guessValue(guesses, pot, rank, Side.HIGHER);
            long l = dp.guessValue(guesses, pot, rank, Side.LOWER);
            if (cash >= h && cash >= l) {
                return null;
            }
            return h >= l ? Side.HIGHER : Side.LOWER;
        }

        /**
         * The best play's value at a state, as a whole number of {@code 13^-(max_guesses - guesses)}
         * tokens (compare two values at the same {@code guesses} only).
         */
        public long value(int guesses, long pot, int rank) {
            return dp.value(guesses, pot, rank);
        }

        /** Guessing {@code side} once and cashing out if right, on the same scale as {@link #value}. */
        public long guessThenCash(int guesses, long pot, int rank, Side side) {
            long raised = HigherLowerRun.potIfRight(pot, HigherLowerRun.winners(rank, side), dp.t.r(), dp.t.cap());
            if (raised < 0) {
                return -1;
            }
            return HigherLowerRun.winners(rank, side) * raised * POW13[dp.g - guesses - 1];
        }

        /** Cashing out, on the same scale as {@link #value}. */
        public long cash(int guesses, long pot) {
            return dp.cashValue(guesses, pot);
        }
    }

    /** The DP over (right guesses, pot, card). */
    private static final class Dp {

        final Terms t;
        final int g;
        /** The pots reachable after each number of right guesses, sorted. */
        final long[][] pots;
        /** {@code pre[L][i][c]}: the sum over ranks 2..c of the value at (L, pots[L][i], rank). Only L = 1 when not kept. */
        final long[][][] pre;
        final Ratio rtp;

        Dp(Terms t, boolean keepAll) {
            if (t.maxGuesses() > MAX_GUESSES) {
                throw new IllegalArgumentException("max_guesses above " + MAX_GUESSES + " can't be solved exactly");
            }
            this.t = t;
            this.g = t.maxGuesses();
            this.pots = reachable();
            this.pre = new long[g + 1][][];
            long[][] above = base();
            if (keepAll || g == 1) {
                pre[g] = above;
            }
            for (int level = g - 1; level >= 1; level--) {
                long[][] here = level(level, above);
                if (keepAll || level == 1) {
                    pre[level] = here;
                }
                above = here;
            }
            long sum = 0;
            int playable = 0;
            for (int c = HigherLowerRun.LOWEST; c <= HigherLowerRun.HIGHEST; c++) {
                long h = guessValue(0, t.in(), c, Side.HIGHER);
                long l = guessValue(0, t.in(), c, Side.LOWER);
                long best = Math.max(h, l);
                if (best >= 0) {
                    sum += best;
                    playable++;
                }
            }
            this.rtp = playable == 0 ? null : new Ratio(sum, (long) playable * t.in() * POW13[g]);
        }

        /** Forward from the stake: every pot a run can hold after 0, 1, … right guesses. */
        private long[][] reachable() {
            long[][] out = new long[g + 1][];
            out[0] = new long[] {t.in()};
            for (int level = 0; level < g; level++) {
                TreeSet<Long> next = new TreeSet<>();
                for (long pot : out[level]) {
                    for (int k = 1; k <= 12; k++) {
                        long raised = HigherLowerRun.potIfRight(pot, k, t.r(), t.cap());
                        if (raised > 0) {
                            next.add(raised);
                        }
                    }
                }
                out[level + 1] = next.stream().mapToLong(Long::longValue).toArray();
            }
            return out;
        }

        /** After the last allowed guess every card is worth the pot. */
        private long[][] base() {
            long[][] p = new long[pots[g].length][15];
            for (int i = 0; i < p.length; i++) {
                for (int c = HigherLowerRun.LOWEST; c <= HigherLowerRun.HIGHEST; c++) {
                    p[i][c] = p[i][c - 1] + pots[g][i];
                }
            }
            return p;
        }

        private long[][] level(int level, long[][] above) {
            long[][] p = new long[pots[level].length][15];
            long scale = POW13[g - level];
            for (int i = 0; i < p.length; i++) {
                long pot = pots[level][i];
                for (int c = HigherLowerRun.LOWEST; c <= HigherLowerRun.HIGHEST; c++) {
                    long v = pot * scale;
                    if (pot < t.cap()) {
                        v = Math.max(v, guess(level, pot, c, Side.HIGHER, above));
                        v = Math.max(v, guess(level, pot, c, Side.LOWER, above));
                    }
                    p[i][c] = p[i][c - 1] + v;
                }
            }
            return p;
        }

        /** A guess's expected value from the next level's sums, or -1 when the side isn't offered. */
        private long guess(int level, long pot, int rank, Side side, long[][] above) {
            long raised = HigherLowerRun.potIfRight(pot, HigherLowerRun.winners(rank, side), t.r(), t.cap());
            if (raised < 0) {
                return -1;
            }
            int j = Arrays.binarySearch(pots[level + 1], raised);
            long[] s = above[j];
            return side == Side.HIGHER ? s[HigherLowerRun.HIGHEST] - s[rank] : s[rank - 1];
        }

        long guessValue(int level, long pot, int rank, Side side) {
            if (level >= g) {
                return -1;
            }
            long[][] above = pre[level + 1];
            if (above == null) {
                throw new IllegalStateException("level " + (level + 1) + " was not kept");
            }
            return guess(level, pot, rank, side, above);
        }

        long cashValue(int level, long pot) {
            return pot * POW13[g - level];
        }

        long value(int level, long pot, int rank) {
            if (level == 0) {
                return Math.max(guessValue(0, pot, rank, Side.HIGHER), guessValue(0, pot, rank, Side.LOWER));
            }
            int i = Arrays.binarySearch(pots[level], pot);
            if (i < 0) {
                throw new IllegalArgumentException("pot " + pot + " can't be reached after " + level + " guesses");
            }
            return pre[level][i][rank] - pre[level][i][rank - 1];
        }
    }
}
