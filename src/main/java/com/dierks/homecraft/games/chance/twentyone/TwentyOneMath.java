package com.dierks.homecraft.games.chance.twentyone;

import com.dierks.homecraft.games.RtpLimits;
import com.dierks.homecraft.games.RtpLimits.Ratio;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The exact odds of Twenty-One (spec §5.4, R1.5): what the best possible play gives back, per
 * token put in, for one stake's whole-token payouts — and the win multiplier that lands it on the
 * {@code rtp} target.
 *
 * <p><b>Why "per token put in", and why Dinkelbach.</b> A Double puts the same in again, so a
 * player who doubles more often puts more in. "Gives back about 89 of every 100 tokens" is a claim
 * about tokens put in, so the published number is the MAXIMUM over every strategy of tokens back ÷
 * tokens put in. That is a ratio, which the ordinary "best expected net" play does not maximise;
 * Dinkelbach's method does: start from λ₀ = the ratio of the best-net strategy, then repeatedly take
 * the strategy that maximises (back − λ·put in) and its ratio as the next λ, until λ stops moving.
 * Hit and Stand never change what is put in, so only the Double decisions depend on λ.
 *
 * <p><b>Why exact.</b> The rules compare the value with the target exactly (a value equal to the
 * target counts as at or below it). The deck is infinite — every card is an Ace, 2-9 with chance
 * 1/13 each or a ten-count with 4/13 — so every probability here is a whole number of 13ths of
 * 13ths, and every value is kept as a {@link BigInteger} count of {@code 13^-DEPTH}: sums and
 * comparisons are exact, and no hand draws anywhere near {@code DEPTH} cards. The Arcade's
 * Twenty-One check ("the peek") is folded in by working with joint probabilities — "the Arcade
 * ends on 19 AND had no Twenty-One" — which scales every choice at one up-card by the same amount,
 * so it never changes which choice is best.
 *
 * <p>Pure: no Bukkit, no config. Solutions are cached by payouts (a config reload that changes
 * nothing costs nothing).
 */
public final class TwentyOneMath {

    /** How much each card value (1 = Ace ... 10) weighs, in 13ths: 10, Jack, Queen and King all count 10. */
    static final int[] WEIGHT = {0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 4};

    /** Every probability is a whole number of {@code 13^-DEPTH}; no path through a hand is this long. */
    private static final int DEPTH = 60;
    private static final BigInteger THIRTEEN = BigInteger.valueOf(13);
    /** Probability 1. */
    static final BigInteger ONE = THIRTEEN.pow(DEPTH);
    private static final BigInteger ONE_13TH = ONE.divide(THIRTEEN);

    /** The Arcade's finals: 17-21 at index 0-4, over 21 at {@link #BUST}. */
    private static final int BUST = 5;
    /** Dinkelbach passes after the first (spec: "until λ stops changing, ≤ 6 passes"). */
    static final int MAX_PASSES = 6;

    /**
     * {@code DEALER[up][f]}: the chance the Arcade, showing {@code up}, ends on final {@code f} AND
     * had no two-card Twenty-One, as a count of {@code 13^-DEPTH}.
     */
    private static final BigInteger[][] DEALER = dealerTable();

    private static final Map<Payouts, Solution> CACHE = new ConcurrentHashMap<>();

    private TwentyOneMath() {
    }

    // ---- inputs and outputs ---------------------------------------------------------------------

    /**
     * What one hand pays back, in whole tokens, already capped by {@code max_payout}.
     *
     * @param in         tokens put in for the hand
     * @param win        back for a win
     * @param push       back for the same total (the tokens in)
     * @param twentyOne  back for a two-card 21 ("Twenty-One!")
     * @param doubleWin  back for a win after a Double (2·in put in)
     * @param doublePush back for the same total after a Double
     */
    public record Payouts(int in, int win, int push, int twentyOne, int doubleWin, int doublePush) {

        public Payouts {
            if (in <= 0 || win < 0 || push < 0 || twentyOne < 0 || doubleWin < 0 || doublePush < 0) {
                throw new IllegalArgumentException("payouts can't be negative and a hand puts something in");
            }
        }

        /**
         * Whether a Double is offered at all: only when a doubled win can pay back more than the
         * doubled tokens put in (a cap that makes it pay less would make it a trap).
         */
        public boolean canDouble() {
            return doubleWin > 2 * in;
        }
    }

    /**
     * The best play for one set of payouts, and exactly what it gives back.
     *
     * @param payouts  what it was solved for
     * @param back     tokens back, summed over every deal (a count of {@code 13^-(DEPTH+3)})
     * @param putIn    tokens put in, on the same scale; {@code back / putIn} is the RTP
     * @param strategy the play that reaches it
     * @param passes   Dinkelbach passes it took (the best-net pass included)
     */
    public record Solution(Payouts payouts, BigInteger back, BigInteger putIn, Strategy strategy, int passes)
            implements Comparable<Solution> {

        /** The RTP as a double (display, the feed; never for a comparison). */
        public double rtp() {
            return new BigDecimal(back).divide(new BigDecimal(putIn), MathContext.DECIMAL64).doubleValue();
        }

        /** Exactly how it compares with a fraction (a target, a band edge). */
        public int compareTo(Ratio r) {
            return back.multiply(BigInteger.valueOf(r.den())).compareTo(BigInteger.valueOf(r.num()).multiply(putIn));
        }

        @Override
        public int compareTo(Solution o) {
            return back.multiply(o.putIn).compareTo(o.back.multiply(putIn));
        }

        /** Whether it lies inside the band, edges included. */
        public boolean inBand() {
            return compareTo(RtpLimits.MIN_RATIO) >= 0 && compareTo(RtpLimits.MAX_RATIO) <= 0;
        }
    }

    /**
     * A complete way to play: for every up-card and hand, hit or not; for every first two cards,
     * double or not. Totals count an Ace as 1; {@code soft} means the hand holds an Ace.
     */
    public static final class Strategy {

        private final boolean[][][] hit;
        private final boolean[][][] dbl;

        Strategy(boolean[][][] hit, boolean[][][] dbl) {
            this.hit = hit;
            this.dbl = dbl;
        }

        /** Hit (true) or stand on this hand against this up-card. */
        public boolean hit(int up, int total, boolean soft) {
            return total <= 21 && hit[up][total][soft ? 1 : 0];
        }

        /** Double (true) on these first two cards against this up-card. */
        public boolean dbl(int up, int total, boolean soft) {
            return total <= 21 && dbl[up][total][soft ? 1 : 0];
        }

        /** The same play with the Double choice on one hand flipped (tests: no single change does better). */
        Strategy flipDouble(int up, int total, boolean soft) {
            boolean[][][] d = new boolean[11][22][2];
            for (int u = 0; u < d.length; u++) {
                for (int t = 0; t < d[u].length; t++) {
                    d[u][t] = dbl[u][t].clone();
                }
            }
            d[up][total][soft ? 1 : 0] = !d[up][total][soft ? 1 : 0];
            return new Strategy(hit, d);
        }
    }

    // ---- solving ------------------------------------------------------------------------------

    /** The best play for these payouts (cached). */
    public static Solution solve(Payouts p) {
        return CACHE.computeIfAbsent(p, TwentyOneMath::dinkelbach);
    }

    /**
     * What a FIXED way of playing gives back (no maximising): the check that {@link #solve}'s
     * numbers are the numbers its own strategy really earns, and the source of the outcome
     * chances a soak needs.
     *
     * @return {@code {back, putIn}} on {@link Solution}'s scale
     */
    public static BigInteger[] evaluate(Strategy s, Payouts p) {
        Tables t = tables(p, s);
        return totals(p, t, s.dbl);
    }

    /** The best-NET play's totals (λ = 1: most tokens back minus tokens put in), {@code {back, putIn}}. */
    static BigInteger[] bestNet(Payouts p) {
        Tables t = tables(p, null);
        return totals(p, t, doubles(p, t, ONE, ONE));
    }

    /** What every deal weighs together on {@link Solution}'s scale: probability 1 times 13^3 deals. */
    static BigInteger totalScale() {
        return ONE.multiply(THIRTEEN.pow(3));
    }

    /**
     * For one hand against one up-card, on one scale: {@code {stand, best of hit/stand, double}}
     * (each joint with "the Arcade had no Twenty-One").
     */
    static BigInteger[] handValues(Payouts p, int up, int total, boolean soft) {
        Tables t = tables(p, null);
        int s = soft ? 1 : 0;
        BigInteger w = winChance(up, best(total, soft));
        BigInteger push = pushChance(up, best(total, soft));
        BigInteger stand = w.multiply(BigInteger.valueOf(p.win())).add(push.multiply(BigInteger.valueOf(p.push())));
        return new BigInteger[] {stand, t.hs[up][total][s], t.dbl[up][total][s]};
    }

    private static Solution dinkelbach(Payouts p) {
        Tables t = tables(p, null);
        // λ = 1: the strategy with the best expected net (back − put in).
        boolean[][][] dbl = doubles(p, t, ONE, ONE);
        BigInteger[] tot = totals(p, t, dbl);
        int passes = 1;
        for (int i = 0; i < MAX_PASSES; i++) {
            boolean[][][] next = doubles(p, t, tot[0], tot[1]);
            BigInteger[] nextTot = totals(p, t, next);
            passes++;
            boolean same = nextTot[0].multiply(tot[1]).equals(tot[0].multiply(nextTot[1]));
            dbl = next;
            tot = nextTot;
            if (same) {
                break;
            }
        }
        return new Solution(p, tot[0], tot[1], new Strategy(t.hit, dbl), passes);
    }

    /** Hit/stand values (best, or following {@code fixed}) and Double values for every hand. */
    private static Tables tables(Payouts p, Strategy fixed) {
        Tables t = new Tables();
        for (int up = 1; up <= 10; up++) {
            BigInteger[] stand = new BigInteger[22];
            BigInteger[] dstand = new BigInteger[22];
            for (int b = 2; b <= 21; b++) {
                BigInteger w = winChance(up, b);
                BigInteger push = pushChance(up, b);
                stand[b] = w.multiply(BigInteger.valueOf(p.win())).add(push.multiply(BigInteger.valueOf(p.push())));
                dstand[b] = w.multiply(BigInteger.valueOf(p.doubleWin()))
                        .add(push.multiply(BigInteger.valueOf(p.doublePush())));
            }
            for (int total = 21; total >= 2; total--) {
                for (int soft = 1; soft >= 0; soft--) {
                    boolean isSoft = soft == 1;
                    BigInteger standValue = stand[best(total, isSoft)];
                    BigInteger hitValue = BigInteger.ZERO;
                    BigInteger dblValue = BigInteger.ZERO;
                    for (int v = 1; v <= 10; v++) {
                        int nt = total + v;
                        if (nt > 21) {
                            continue;
                        }
                        int ns = isSoft || v == 1 ? 1 : 0;
                        BigInteger w = BigInteger.valueOf(WEIGHT[v]);
                        hitValue = hitValue.add(w.multiply(t.hs[up][nt][ns]));
                        dblValue = dblValue.add(w.multiply(dstand[best(nt, ns == 1)]));
                    }
                    hitValue = div13(hitValue);
                    t.dbl[up][total][soft] = div13(dblValue);
                    boolean hit;
                    if (best(total, isSoft) == 21) {
                        hit = false;
                    } else if (fixed != null) {
                        hit = fixed.hit(up, total, isSoft);
                    } else {
                        hit = hitValue.compareTo(standValue) > 0;
                    }
                    t.hit[up][total][soft] = hit;
                    t.hs[up][total][soft] = hit ? hitValue : standValue;
                }
            }
        }
        return t;
    }

    /**
     * The Double choices that maximise {@code back − λ·putIn} with {@code λ = lamBack / lamPut}:
     * double when what it adds, {@code dbl − hs}, beats λ times the extra tokens put in (the extra
     * goes in only when the Arcade had no Twenty-One).
     */
    private static boolean[][][] doubles(Payouts p, Tables t, BigInteger lamBack, BigInteger lamPut) {
        boolean[][][] d = new boolean[11][22][2];
        if (!p.canDouble()) {
            return d;
        }
        BigInteger in = BigInteger.valueOf(p.in());
        for (int up = 1; up <= 10; up++) {
            BigInteger extra = in.multiply(noTwentyOne(up)).multiply(lamBack);
            for (int total = 2; total <= 21; total++) {
                for (int soft = 0; soft <= 1; soft++) {
                    BigInteger gain = t.dbl[up][total][soft].subtract(t.hs[up][total][soft]).multiply(lamPut);
                    d[up][total][soft] = gain.compareTo(extra) > 0;
                }
            }
        }
        return d;
    }

    /** Tokens back and put in over every deal (up-card, first two cards) for these Double choices. */
    private static BigInteger[] totals(Payouts p, Tables t, boolean[][][] dbl) {
        BigInteger back = BigInteger.ZERO;
        BigInteger put = BigInteger.ZERO;
        BigInteger in = BigInteger.valueOf(p.in());
        BigInteger inOnce = in.multiply(ONE);
        for (int up = 1; up <= 10; up++) {
            BigInteger q = noTwentyOne(up);
            BigInteger peek = ONE.subtract(q);
            BigInteger naturalBack = peek.multiply(BigInteger.valueOf(p.push()))
                    .add(q.multiply(BigInteger.valueOf(p.twentyOne())));
            BigInteger doubledIn = inOnce.add(in.multiply(q));
            for (int c1 = 1; c1 <= 10; c1++) {
                for (int c2 = 1; c2 <= 10; c2++) {
                    BigInteger w = BigInteger.valueOf((long) WEIGHT[up] * WEIGHT[c1] * WEIGHT[c2]);
                    int total = c1 + c2;
                    boolean soft = c1 == 1 || c2 == 1;
                    int s = soft ? 1 : 0;
                    if (best(total, soft) == 21) {
                        back = back.add(w.multiply(naturalBack));
                        put = put.add(w.multiply(inOnce));
                    } else if (dbl[up][total][s]) {
                        back = back.add(w.multiply(t.dbl[up][total][s]));
                        put = put.add(w.multiply(doubledIn));
                    } else {
                        back = back.add(w.multiply(t.hs[up][total][s]));
                        put = put.add(w.multiply(inOnce));
                    }
                }
            }
        }
        return new BigInteger[] {back, put};
    }

    private static final class Tables {
        final BigInteger[][][] hs = new BigInteger[11][22][2];
        final BigInteger[][][] dbl = new BigInteger[11][22][2];
        final boolean[][][] hit = new boolean[11][22][2];
    }

    // ---- the Arcade's hand ------------------------------------------------------------------------

    /** The best total: an Ace counts 11 when that doesn't go over 21. */
    static int best(int total, boolean soft) {
        return soft && total + 10 <= 21 ? total + 10 : total;
    }

    /** The chance the Arcade has no Twenty-One when it shows {@code up} (1 but for an Ace or a ten-count). */
    static BigInteger noTwentyOne(int up) {
        int peek = up == 1 ? WEIGHT[10] : up == 10 ? WEIGHT[1] : 0;
        return ONE_13TH.multiply(BigInteger.valueOf(13 - peek));
    }

    /** Standing on best total {@code b} against {@code up}: the chance to win (joint with no Arcade 21). */
    static BigInteger winChance(int up, int b) {
        BigInteger w = DEALER[up][BUST];
        for (int f = 17; f < Math.min(b, 22); f++) {
            w = w.add(DEALER[up][f - 17]);
        }
        return w;
    }

    /** Standing on best total {@code b} against {@code up}: the chance of the same total. */
    static BigInteger pushChance(int up, int b) {
        return b >= 17 && b <= 21 ? DEALER[up][b - 17] : BigInteger.ZERO;
    }

    /** The Arcade's final, joint with "no Twenty-One", for each up-card (the table behind everything). */
    static BigInteger dealerFinal(int up, int f) {
        return DEALER[up][f > 21 ? BUST : f - 17];
    }

    private static BigInteger[][] dealerTable() {
        Map<Integer, BigInteger[]> memo = new HashMap<>();
        BigInteger[][] out = new BigInteger[11][];
        for (int up = 1; up <= 10; up++) {
            BigInteger[] sum = zeros();
            for (int v = 1; v <= 10; v++) {
                if ((up == 1 && v == 10) || (up == 10 && v == 1)) {
                    continue; // the peek: an Arcade Twenty-One ends the hand before anyone plays
                }
                add(sum, dealerFrom(up + v, up == 1 || v == 1, memo), WEIGHT[v]);
            }
            out[up] = div13(sum);
        }
        return out;
    }

    /** The Arcade draws to 17 and stands on every 17, soft ones included. */
    private static BigInteger[] dealerFrom(int total, boolean soft, Map<Integer, BigInteger[]> memo) {
        int b = best(total, soft);
        if (b >= 17) {
            BigInteger[] r = zeros();
            r[b > 21 ? BUST : b - 17] = ONE;
            return r;
        }
        int key = total * 2 + (soft ? 1 : 0);
        BigInteger[] known = memo.get(key);
        if (known != null) {
            return known;
        }
        BigInteger[] sum = zeros();
        for (int v = 1; v <= 10; v++) {
            add(sum, dealerFrom(total + v, soft || v == 1, memo), WEIGHT[v]);
        }
        BigInteger[] r = div13(sum);
        memo.put(key, r);
        return r;
    }

    private static BigInteger[] zeros() {
        BigInteger[] z = new BigInteger[BUST + 1];
        java.util.Arrays.fill(z, BigInteger.ZERO);
        return z;
    }

    private static void add(BigInteger[] sum, BigInteger[] d, int weight) {
        BigInteger w = BigInteger.valueOf(weight);
        for (int i = 0; i < sum.length; i++) {
            sum[i] = sum[i].add(d[i].multiply(w));
        }
    }

    private static BigInteger[] div13(BigInteger[] v) {
        BigInteger[] out = new BigInteger[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = div13(v[i]);
        }
        return out;
    }

    /** One more card deep: exact, or a bug (a path longer than {@code DEPTH}). */
    private static BigInteger div13(BigInteger v) {
        BigInteger[] qr = v.divideAndRemainder(THIRTEEN);
        if (qr[1].signum() != 0) {
            throw new IllegalStateException("a hand went deeper than the exact scale allows");
        }
        return qr[0];
    }

    // ---- the per-stake solve ------------------------------------------------------------------------

    /** The smallest and largest win multiplier (spec: m ∈ [0.5, 1.0]). */
    public static final Ratio M_LOW = new Ratio(1, 2);
    public static final Ratio M_HIGH = new Ratio(1, 1);

    /**
     * Every distinct set of payouts a stake can have as the win multiplier {@code m} runs over
     * [0.5, 1.0], each with the SMALLEST {@code m} that gives it, in order of {@code m}. The payouts
     * only change where one of {@code floor(in·m)}, {@code floor(in·m·bonus)} or
     * {@code floor(2·in·m)} steps up, so those points (and 0.5) are all the candidates there are.
     */
    public static List<Candidate> candidates(int in, Ratio bonus, int cap) {
        List<Ratio> ms = new ArrayList<>();
        ms.add(M_LOW);
        addSteps(ms, BigInteger.valueOf(in), BigInteger.ONE);
        addSteps(ms, BigInteger.valueOf(in).multiply(BigInteger.valueOf(bonus.num())), BigInteger.valueOf(bonus.den()));
        addSteps(ms, BigInteger.valueOf(2L * in), BigInteger.ONE);
        ms.sort(Ratio::compareTo);
        Map<Payouts, Ratio> first = new LinkedHashMap<>();
        for (Ratio m : ms) {
            first.putIfAbsent(TwentyOneHand.Terms.payouts(in, m, bonus, cap), m);
        }
        List<Candidate> out = new ArrayList<>(first.size());
        first.forEach((p, m) -> out.add(new Candidate(m, p)));
        return out;
    }

    /** One choice of the free multiplier: the smallest {@code m} of its class and what it pays. */
    public record Candidate(Ratio m, Payouts payouts) {
    }

    /** Add every {@code m = j / (a/b)} in [0.5, 1] where {@code floor(m·a/b)} steps up. */
    private static void addSteps(List<Ratio> ms, BigInteger a, BigInteger b) {
        // m·a/b = j  <=>  m = j·b/a, for whole j with a/(2b) <= j <= a/b
        BigInteger two = BigInteger.TWO;
        BigInteger lo = ceilDiv(a, b.multiply(two));
        BigInteger hi = a.divide(b);
        for (BigInteger j = lo; j.compareTo(hi) <= 0; j = j.add(BigInteger.ONE)) {
            ms.add(reduced(j.multiply(b), a));
        }
    }

    private static BigInteger ceilDiv(BigInteger a, BigInteger b) {
        BigInteger[] qr = a.divideAndRemainder(b);
        return qr[1].signum() > 0 ? qr[0].add(BigInteger.ONE) : qr[0];
    }

    static Ratio reduced(BigInteger num, BigInteger den) {
        BigInteger g = num.gcd(den);
        return new Ratio(num.divide(g).longValueExact(), den.divide(g).longValueExact());
    }

    /**
     * What the R1.1 rule chose for one stake.
     *
     * @param index       into the candidates, or -1 when nothing is inside the band
     * @param solution    the chosen candidate's solution ({@code null} when none)
     * @param aboveTarget nothing at or below the target was in the band, so a higher value was taken
     * @param nearest     when none: the closest values under and over the band (NaN for none), for
     *                    the WARN
     */
    public record Choice(int index, Solution solution, boolean aboveTarget, double[] nearest) {
    }

    /**
     * The R1.1 rule over a stake's candidates (in order of {@code m}), solving only what it needs.
     * The best-play return never goes down as {@code m} goes up — every payout can only rise, and a
     * Double only ever becomes allowed — so binary searches find the largest value at or below the
     * target (its first candidate), the smallest from 85 up, and the nearest values for a WARN, in
     * about a dozen solves however big the stake. Same answer as {@link #pick} over every value.
     */
    public static Choice choose(Ratio target, List<Candidate> cands) {
        int n = cands.size();
        Solution[] memo = new Solution[n];
        java.util.function.IntFunction<Solution> at = i -> {
            if (memo[i] == null) {
                memo[i] = solve(cands.get(i).payouts());
            }
            return memo[i];
        };
        int below = last(n, i -> at.apply(i).compareTo(target) <= 0);
        if (below >= 0) {
            Solution v = at.apply(below);
            below = first(below + 1, i -> at.apply(i).compareTo(v) >= 0);
            if (at.apply(below).inBand()) {
                return new Choice(below, at.apply(below), false, null);
            }
        }
        int floor = first(n, i -> at.apply(i).compareTo(RtpLimits.MIN_RATIO) >= 0);
        if (floor < n && at.apply(floor).inBand()) {
            return new Choice(floor, at.apply(floor), true, null);
        }
        int under = last(n, i -> at.apply(i).compareTo(RtpLimits.MIN_RATIO) < 0);
        int over = first(n, i -> at.apply(i).compareTo(RtpLimits.MAX_RATIO) > 0);
        return new Choice(-1, null, false, new double[] {under >= 0 ? at.apply(under).rtp() : Double.NaN,
                over < n ? at.apply(over).rtp() : Double.NaN});
    }

    /** The last index in [0, n) where a predicate that holds on a prefix holds, or -1. */
    private static int last(int n, java.util.function.IntPredicate holds) {
        int lo = 0;
        int hi = n - 1;
        int found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (holds.test(mid)) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return found;
    }

    /** The first index in [0, n) where a predicate that holds on a suffix holds, or n. */
    private static int first(int n, java.util.function.IntPredicate holds) {
        int lo = 0;
        int hi = n - 1;
        int found = n;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (holds.test(mid)) {
                found = mid;
                hi = mid - 1;
            } else {
                lo = mid + 1;
            }
        }
        return found;
    }

    /**
     * The spec's R1.1 rule, exactly — the same rule as {@link RtpLimits#pick(Ratio, Ratio[])},
     * which needs values that fit in a {@code long}; these don't. The largest value at or below
     * {@code target}; if that is under the band (or there is none), the smallest one at or above
     * its bottom, as long as it isn't over the top. Among equal values the first wins.
     *
     * @return the index into {@code values}, or -1 when nothing is inside the band
     */
    public static int pick(Ratio target, List<Solution> values) {
        int below = -1;
        int floor = -1;
        for (int i = 0; i < values.size(); i++) {
            Solution v = values.get(i);
            if (v.compareTo(target) <= 0 && (below < 0 || v.compareTo(values.get(below)) > 0)) {
                below = i;
            }
            if (v.compareTo(RtpLimits.MIN_RATIO) >= 0 && (floor < 0 || v.compareTo(values.get(floor)) < 0)) {
                floor = i;
            }
        }
        if (below >= 0 && values.get(below).inBand()) {
            return below;
        }
        if (floor >= 0 && values.get(floor).inBand()) {
            return floor;
        }
        return -1;
    }
}
