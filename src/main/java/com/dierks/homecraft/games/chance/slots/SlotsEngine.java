package com.dierks.homecraft.games.chance.slots;

import com.dierks.homecraft.games.ChanceRounds;
import com.dierks.homecraft.games.FeedWriter;
import com.dierks.homecraft.games.RtpLimits;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SplittableRandom;

/**
 * Ore Slots's odds and its draw, in one object (spec §5.1, §5.3, R1.1, R1.4, R1.9).
 *
 * <p>Why one object: the screen's paytable, the "gives back about N of every 100 tokens" line,
 * {@code /hcm arcade odds}, the website and the spin itself must all tell the same truth, so they
 * all read THIS engine, built once from the config it plays with. Nothing here is simulated: every
 * number is exact enumeration of all 7³ symbol triples with the real whole-token payouts, and every
 * published probability is an exact fraction because each reel is drawn with
 * {@code nextInt(total)} over whole-number weights.
 *
 * <p>The one free parameter is Stone's weight. Every configured weight is multiplied by
 * {@link #SCALE} first so a step of Stone moves the return by a hundredth of a point or so, then
 * Stone is solved PER STAKE (payouts are capped by {@code games.max_payout}, so a big stake can
 * need a different Stone): the return is exactly {@code N(s) / (stake · (W + s)³)} with
 * {@code N} linear in Stone {@code s} (a Stone beside a pair still leaves "two the same"; two
 * Stones leave nothing), which falls as Stone grows. So the largest return at or below the target
 * is at the smallest Stone that gets there, found by an exact search in whole numbers; the value
 * one Stone lighter is the smallest one above it. That pair is all the §5.1 rule
 * ({@link RtpLimits#pick}) needs: take the one at or below the target if it is inside the band,
 * else the one above it if that is inside the band, else drop the stake. Comparisons are
 * cross-multiplied {@link BigInteger}s, never doubles, so a value equal to the target counts.
 *
 * <p>With the shipped numbers the cap never binds, every stake gets Stone 1,479 and gives back
 * 89.97 of every 100 tokens (players read "about 89").
 */
public final class SlotsEngine implements ChanceRounds.InstantEngine<SlotsEngine.Spin> {

    /** Every configured weight is multiplied by this before Stone is solved (spec §5.3). */
    public static final int SCALE = 100;
    /** The engine version stored with every round, so an old round can always be read back. */
    public static final int VERSION = 1;
    /** A line paying at least this many times the tokens in gets the private title (spec §5.3). */
    public static final int BIG_LINE = 20;

    private static final Symbol[] SYMBOLS = Symbol.values();
    private static final Line[] LINES = Line.values();
    private static final int N = SYMBOLS.length;
    private static final BigInteger MIN_NUM = BigInteger.valueOf(RtpLimits.MIN_RATIO.num());
    private static final BigInteger MIN_DEN = BigInteger.valueOf(RtpLimits.MIN_RATIO.den());
    private static final BigInteger MAX_NUM = BigInteger.valueOf(RtpLimits.MAX_RATIO.num());
    private static final BigInteger MAX_DEN = BigInteger.valueOf(RtpLimits.MAX_RATIO.den());

    private final Map<Symbol, Integer> weights;
    private final Map<Line, Integer> pays;
    private final List<Integer> stakes;
    private final int cap;
    private final double targetPercent;
    private final Map<Integer, StakeOdds> odds;
    private final List<Dropped> dropped;
    private final Map<Integer, Table> tables;

    private SlotsEngine(Map<Symbol, Integer> weights, Map<Line, Integer> pays, List<Integer> stakes, int cap,
                        double targetPercent) {
        this.weights = Collections.unmodifiableMap(weights);
        this.pays = Collections.unmodifiableMap(pays);
        this.stakes = List.copyOf(stakes);
        this.cap = cap;
        this.targetPercent = targetPercent;
        Map<Integer, StakeOdds> solved = new LinkedHashMap<>();
        Map<Integer, Table> drawn = new LinkedHashMap<>();
        List<Dropped> out = new ArrayList<>();
        for (int stake : this.stakes) {
            solveStake(stake, solved, drawn, out);
        }
        this.odds = Collections.unmodifiableMap(solved);
        this.tables = Collections.unmodifiableMap(drawn);
        this.dropped = List.copyOf(out);
    }

    /**
     * Solve the game for these settings.
     *
     * @param reels      configured weights by {@link Symbol#key()} (Stone is solved; a missing key is 0)
     * @param pays       multiples by {@link Line#key()} (a missing key is 0: the line never pays)
     * @param stakes     the stakes, smallest first
     * @param cap        the most any single spin may pay ({@code Common.maxPayoutFor(stakes)})
     * @param rtpPercent the target, a percent (squeezed into the band if it is not already)
     */
    public static SlotsEngine solve(Map<String, Integer> reels, Map<String, Integer> pays, List<Integer> stakes,
                                    int cap, double rtpPercent) {
        Map<Symbol, Integer> w = new EnumMap<>(Symbol.class);
        for (Symbol s : SYMBOLS) {
            if (s != Symbol.STONE) {
                w.put(s, Math.max(0, reels.getOrDefault(s.key(), 0)));
            }
        }
        Map<Line, Integer> p = new EnumMap<>(Line.class);
        for (Line l : LINES) {
            p.put(l, Math.max(0, pays.getOrDefault(l.key(), 0)));
        }
        double target = Double.isFinite(rtpPercent)
                ? Math.max(RtpLimits.MIN_PERCENT, Math.min(RtpLimits.MAX_PERCENT, rtpPercent))
                : RtpLimits.DEFAULT_PERCENT;
        return new SlotsEngine(w, p, stakes, Math.max(1, cap), target);
    }

    // ---- what it publishes ------------------------------------------------------------------

    /** Whether any stake is left to play (a game with none is closed). */
    public boolean playable() {
        return !odds.isEmpty();
    }

    /** The stakes it takes, after the solve dropped any it could not fit in the band. */
    public List<Integer> stakes() {
        return List.copyOf(odds.keySet());
    }

    /** The stakes configured, dropped ones included. */
    public List<Integer> configuredStakes() {
        return stakes;
    }

    /** Each playable stake's solved odds, smallest stake first. */
    public Map<Integer, StakeOdds> odds() {
        return odds;
    }

    /** One stake's solved odds, or {@code null} if it doesn't take that stake. */
    public StakeOdds odds(int stake) {
        return odds.get(stake);
    }

    /** The stakes the solve dropped, and why. */
    public List<Dropped> dropped() {
        return dropped;
    }

    /** The lowest computed return over the playable stakes (the one number that stands for the game). */
    public double lowestRtp() {
        double low = Double.NaN;
        for (StakeOdds o : odds.values()) {
            if (Double.isNaN(low) || o.rtp() < low) {
                low = o.rtp();
            }
        }
        return low;
    }

    /** Each playable stake's computed return, a fraction (the feed floors it to one decimal). */
    public Map<Integer, Double> rtpByStake() {
        Map<Integer, Double> out = new LinkedHashMap<>();
        for (StakeOdds o : odds.values()) {
            out.put(o.stake(), o.rtp());
        }
        return Collections.unmodifiableMap(out);
    }

    /** The most a single spin pays. */
    public int cap() {
        return cap;
    }

    /** The target it was solved for, a percent inside the band. */
    public double targetPercent() {
        return targetPercent;
    }

    /** What a line pays, as a multiple of the tokens in (0 = it never pays). */
    public int multiple(Line line) {
        return pays.getOrDefault(line, 0);
    }

    /** A symbol's weight on the reels at this stake, scaled ({@link #SCALE}); Stone's is the solved one. */
    public int weight(int stake, Symbol symbol) {
        Table t = tables.get(stake);
        if (t == null) {
            return 0;
        }
        int lo = symbol.ordinal() == 0 ? 0 : t.bounds[symbol.ordinal() - 1];
        return t.bounds[symbol.ordinal()] - lo;
    }

    /**
     * Whether every playable stake pays the same multiples with the same odds (the cap binds
     * nowhere), so one paytable in multiples stands for them all. When false, the website's rows
     * carry their stake and pay in tokens (spec §10).
     */
    public boolean sameAtEveryStake() {
        StakeOdds first = null;
        for (StakeOdds o : odds.values()) {
            if (o.capped()) {
                return false;
            }
            if (first == null) {
                first = o;
            } else if (first.stone() != o.stone()) {
                return false;
            }
        }
        return true;
    }

    /**
     * The website's paytable rows (spec §10, R1.19, R1.20): one per paying line with its exact
     * chance, in multiples when every stake pays the same, else one set per stake in tokens.
     */
    public List<FeedWriter.PayRow> feedRows() {
        List<FeedWriter.PayRow> rows = new ArrayList<>();
        if (odds.isEmpty()) {
            return rows;
        }
        if (sameAtEveryStake()) {
            for (LineOdds l : odds.values().iterator().next().lines()) {
                rows.add(new FeedWriter.PayRow(null, l.line().combo(), l.multiple(), l.chance(), null, null));
            }
            return rows;
        }
        for (StakeOdds o : odds.values()) {
            for (LineOdds l : o.lines()) {
                rows.add(new FeedWriter.PayRow(o.stake(), l.line().combo(), l.payout(), l.chance(), null, null));
            }
        }
        return rows;
    }

    // ---- the draw ---------------------------------------------------------------------------

    /**
     * The spin for this seed: three reels, each {@code nextInt(total)} over this stake's weights
     * from one {@link SplittableRandom}, then the best line. Same seed and stake, same spin.
     *
     * @throws IllegalArgumentException for a stake it doesn't take
     */
    @Override
    public Spin decide(long seed, int stake) {
        Table t = tables.get(stake);
        if (t == null) {
            throw new IllegalArgumentException("Ore Slots does not take " + stake + " tokens (it takes " + stakes() + ")");
        }
        SplittableRandom random = new SplittableRandom(seed);
        int a = t.draw(random.nextInt(t.total));
        int b = t.draw(random.nextInt(t.total));
        int c = t.draw(random.nextInt(t.total));
        int i = index(a, b, c);
        return new Spin(stake, List.of(SYMBOLS[a], SYMBOLS[b], SYMBOLS[c]), t.lines[i], t.pays[i]);
    }

    @Override
    public int payout(Spin spin) {
        return spin.payout();
    }

    /** What the round stores: {@code v=1;stake=5;reels=coal,wild,stone;line=two;payout=10;stone=1479;total=4679}. */
    @Override
    public String data(Spin spin) {
        StringBuilder sb = new StringBuilder("v=").append(VERSION).append(";stake=").append(spin.stake()).append(";reels=");
        for (int i = 0; i < spin.reels().size(); i++) {
            sb.append(i == 0 ? "" : ",").append(spin.reels().get(i).key());
        }
        sb.append(";line=").append(spin.line() == null ? "none" : spin.line().key());
        sb.append(";payout=").append(spin.payout());
        StakeOdds o = odds.get(spin.stake());
        if (o != null) {
            sb.append(";stone=").append(o.stone()).append(";total=").append(o.total());
        }
        return sb.toString();
    }

    /**
     * The line these three symbols make at this stake, or {@code null} when nothing pays. Works
     * for any stake (the cosmetic spin frames use it to never show a paying line early).
     */
    public Line line(int stake, Symbol a, Symbol b, Symbol c) {
        return best(a, b, c, stake);
    }

    /** What these three symbols pay at this stake, in tokens (capped). */
    public int payout(int stake, Symbol a, Symbol b, Symbol c) {
        Line l = best(a, b, c, stake);
        return l == null ? 0 : pay(l, stake);
    }

    // ---- the solve ----------------------------------------------------------------------------

    private void solveStake(int stake, Map<Integer, StakeOdds> solved, Map<Integer, Table> drawn, List<Dropped> out) {
        // Enumerate every triple once: what it pays is fixed, and its weight is a product of the
        // three reel weights, i.e. a polynomial in Stone's weight s of degree (Stones in it).
        long[] scaled = new long[N];
        long w = 0;
        for (Symbol s : SYMBOLS) {
            if (s != Symbol.STONE) {
                scaled[s.ordinal()] = (long) weights.get(s) * SCALE;
                w += scaled[s.ordinal()];
            }
        }
        BigInteger[] paid = zeros();
        BigInteger[] squared = zeros();
        BigInteger[] hits = zeros();
        Map<Line, BigInteger[]> byLine = new EnumMap<>(Line.class);
        Line[] lineOf = new Line[N * N * N];
        int[] payOf = new int[N * N * N];
        for (int a = 0; a < N; a++) {
            for (int b = 0; b < N; b++) {
                for (int c = 0; c < N; c++) {
                    Line l = best(SYMBOLS[a], SYMBOLS[b], SYMBOLS[c], stake);
                    int i = index(a, b, c);
                    lineOf[i] = l;
                    payOf[i] = l == null ? 0 : pay(l, stake);
                    if (l == null) {
                        continue;
                    }
                    int stones = 0;
                    BigInteger product = BigInteger.ONE;
                    for (int x : new int[]{a, b, c}) {
                        if (SYMBOLS[x] == Symbol.STONE) {
                            stones++;
                        } else {
                            product = product.multiply(BigInteger.valueOf(scaled[x]));
                        }
                    }
                    BigInteger p = BigInteger.valueOf(payOf[i]);
                    paid[stones] = paid[stones].add(product.multiply(p));
                    squared[stones] = squared[stones].add(product.multiply(p).multiply(p));
                    hits[stones] = hits[stones].add(product);
                    BigInteger[] line = byLine.computeIfAbsent(l, k -> zeros());
                    line[stones] = line[stones].add(product);
                }
            }
        }

        RtpLimits.Ratio t = RtpLimits.Ratio.percent(targetPercent);
        BigInteger tNum = BigInteger.valueOf(t.num());
        BigInteger tDen = BigInteger.valueOf(t.den());
        long smin = w > 0 ? 0 : 1;
        long smax = Integer.MAX_VALUE - w;
        if (smax < smin || isZero(paid)) {
            out.add(new Dropped(stake, isZero(paid) ? 0.0 : Double.NaN, Double.NaN));
            return;
        }

        // The smallest Stone whose return is at or below the target (the return falls as Stone grows).
        long lo = smin;
        long hi;
        if (atMost(paid, w, smin, stake, tNum, tDen)) {
            hi = smin;
        } else {
            hi = smin + 1;
            while (!atMost(paid, w, hi, stake, tNum, tDen)) {
                if (hi >= smax) {
                    out.add(new Dropped(stake, Double.NaN, value(paid, w, smax, stake)));
                    return;
                }
                lo = hi;
                hi = Math.min(smax, hi * 2);
            }
            while (hi - lo > 1) {
                long mid = lo + (hi - lo) / 2;
                if (atMost(paid, w, mid, stake, tNum, tDen)) {
                    hi = mid;
                } else {
                    lo = mid;
                }
            }
        }
        long below = hi;
        long stone;
        boolean aboveTarget;
        if (atLeast(paid, w, below, stake, MIN_NUM, MIN_DEN)) {
            stone = below;
            aboveTarget = false;
        } else if (below > smin && atMost(paid, w, below - 1, stake, MAX_NUM, MAX_DEN)) {
            stone = below - 1;
            aboveTarget = true;
        } else {
            out.add(new Dropped(stake, value(paid, w, below, stake),
                    below > smin ? value(paid, w, below - 1, stake) : Double.NaN));
            return;
        }

        BigInteger s = BigInteger.valueOf(stone);
        BigInteger cube = BigInteger.valueOf(w + stone).pow(3);
        BigInteger bigStake = BigInteger.valueOf(stake);
        BigInteger num = eval(paid, s);
        BigInteger den = cube.multiply(bigStake);
        double rtp = ratio(num, den);
        // Variance of (tokens back / tokens in): (Q·T³ − N²) / (stake² · T⁶).
        BigInteger varNum = eval(squared, s).multiply(cube).subtract(num.multiply(num));
        BigInteger varDen = bigStake.multiply(bigStake).multiply(cube).multiply(cube);
        double sd = Math.sqrt(Math.max(0, ratio(varNum, varDen)));
        List<LineOdds> lines = new ArrayList<>();
        for (Line l : LINES) {
            BigInteger[] poly = byLine.get(l);
            if (poly == null) {
                continue;
            }
            BigInteger count = eval(poly, s);
            if (count.signum() > 0) {
                lines.add(new LineOdds(l, stake, multiple(l), pay(l, stake), ratio(count, cube)));
            }
        }
        solved.put(stake, new StakeOdds(stake, (int) stone, (int) (w + stone), num, den, rtp, aboveTarget,
                ratio(eval(hits, s), cube), sd, List.copyOf(lines)));
        int[] bounds = new int[N];
        long run = 0;
        for (Symbol sym : SYMBOLS) {
            run += sym == Symbol.STONE ? stone : scaled[sym.ordinal()];
            bounds[sym.ordinal()] = (int) run;
        }
        drawn.put(stake, new Table((int) (w + stone), bounds, lineOf, payOf));
    }

    /** The best line for three symbols at a stake: the most tokens, a tie going to the higher line. */
    private Line best(Symbol a, Symbol b, Symbol c, int stake) {
        Line best = null;
        int bestPay = 0;
        for (Line l : LINES) {
            if (multiple(l) <= 0 || !l.matches(a, b, c)) {
                continue;
            }
            int p = pay(l, stake);
            if (p > bestPay) {
                best = l;
                bestPay = p;
            }
        }
        return best;
    }

    /** A line's tokens at a stake: the multiple of the stake, capped. */
    private int pay(Line line, int stake) {
        return (int) Math.min((long) stake * multiple(line), cap);
    }

    /** Whether the return with Stone {@code s} is at most {@code fNum / fDen}. */
    private static boolean atMost(BigInteger[] paid, long w, long s, int stake, BigInteger fNum, BigInteger fDen) {
        return compare(paid, w, s, stake, fNum, fDen) <= 0;
    }

    /** Whether the return with Stone {@code s} is at least {@code fNum / fDen}. */
    private static boolean atLeast(BigInteger[] paid, long w, long s, int stake, BigInteger fNum, BigInteger fDen) {
        return compare(paid, w, s, stake, fNum, fDen) >= 0;
    }

    /** The return with Stone {@code s} compared with {@code fNum / fDen}, exactly. */
    private static int compare(BigInteger[] paid, long w, long s, int stake, BigInteger fNum, BigInteger fDen) {
        BigInteger left = eval(paid, BigInteger.valueOf(s)).multiply(fDen);
        BigInteger right = fNum.multiply(BigInteger.valueOf(stake)).multiply(BigInteger.valueOf(w + s).pow(3));
        return left.compareTo(right);
    }

    /** The return with Stone {@code s}, as a double (for WARN lines only). */
    private static double value(BigInteger[] paid, long w, long s, int stake) {
        return ratio(eval(paid, BigInteger.valueOf(s)), BigInteger.valueOf(w + s).pow(3).multiply(BigInteger.valueOf(stake)));
    }

    private static BigInteger eval(BigInteger[] poly, BigInteger s) {
        BigInteger out = BigInteger.ZERO;
        for (int k = poly.length - 1; k >= 0; k--) {
            out = out.multiply(s).add(poly[k]);
        }
        return out;
    }

    private static double ratio(BigInteger num, BigInteger den) {
        if (den.signum() == 0) {
            return 0;
        }
        return new BigDecimal(num).divide(new BigDecimal(den), MathContext.DECIMAL64).doubleValue();
    }

    private static BigInteger[] zeros() {
        return new BigInteger[]{BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO};
    }

    private static boolean isZero(BigInteger[] poly) {
        for (BigInteger c : poly) {
            if (c.signum() != 0) {
                return false;
            }
        }
        return true;
    }

    private static int index(int a, int b, int c) {
        return (a * N + b) * N + c;
    }

    // ---- values -------------------------------------------------------------------------------

    /**
     * One spin.
     *
     * @param stake  tokens put in
     * @param reels  the three symbols on the line, left to right
     * @param line   the line that pays, or {@code null} for none
     * @param payout tokens back (0 for none), already capped
     */
    public record Spin(int stake, List<Symbol> reels, Line line, int payout) {

        public Spin {
            reels = List.copyOf(reels);
        }

        /** More came back than went in: the only result ever called a win (spec R1.3). */
        public boolean win() {
            return payout > stake;
        }

        /** A line paying {@link #BIG_LINE} times the tokens in or more: the private title. */
        public boolean big() {
            return win() && payout >= (long) BIG_LINE * stake;
        }
    }

    /**
     * One stake's solved odds.
     *
     * @param stake       tokens in
     * @param stone       Stone's solved weight (scaled, like every weight here)
     * @param total       the sum of the weights: each reel is {@code nextInt(total)}
     * @param rtpNum      the exact return: {@code rtpNum / rtpDen} tokens back per token in
     * @param rtpDen      its denominator
     * @param rtp         the same as a double: publish and play THIS, never the target
     * @param aboveTarget nothing at or below the target was inside the band, so this is the
     *                    smallest value above it (one INFO line at load)
     * @param hitChance   the chance a spin pays anything
     * @param sd          the spread of tokens back per token in, per spin (a soak's tolerance)
     * @param lines       each paying line, top line first
     */
    public record StakeOdds(int stake, int stone, int total, BigInteger rtpNum, BigInteger rtpDen, double rtp,
                            boolean aboveTarget, double hitChance, double sd, List<LineOdds> lines) {

        /** Whether the payout cap brings any line below its multiple at this stake. */
        public boolean capped() {
            for (LineOdds l : lines) {
                if (l.capped()) {
                    return true;
                }
            }
            return false;
        }

        /** "1 in N" for a paying spin. */
        public long hitOneIn() {
            return FeedWriter.oneIn(hitChance);
        }

        /** The line, or {@code null} if it doesn't pay at this stake. */
        public LineOdds line(Line line) {
            for (LineOdds l : lines) {
                if (l.line() == line) {
                    return l;
                }
            }
            return null;
        }
    }

    /**
     * One paying line at one stake.
     *
     * @param multiple what config says it pays, times the tokens in
     * @param payout   what it really pays at this stake, in tokens (the cap applied)
     * @param chance   its exact chance on a spin (it being the best line)
     */
    public record LineOdds(Line line, int stake, int multiple, int payout, double chance) {

        /** "1 in N", the same N the website shows. */
        public long oneIn() {
            return FeedWriter.oneIn(chance);
        }

        /** Whether {@code games.max_payout} brings it below {@code multiple × stake}. */
        public boolean capped() {
            return payout < (long) multiple * stake;
        }

        /** Whether it gives back more than was put in (else it reads "Your N back"). */
        public boolean win() {
            return payout > stake;
        }
    }

    /**
     * A stake the solve dropped: nothing it can reach is inside the band.
     *
     * @param under the nearest return below the band ({@code NaN} for none)
     * @param over  the nearest return above the band ({@code NaN} for none)
     */
    public record Dropped(int stake, double under, double over) {

        /** "84.2% or 96.1%", or "nothing" (for the WARN). */
        public String nearest() {
            return RtpLimits.nearest(new double[]{under, over});
        }
    }

    /** One stake's draw: cumulative weights in {@link Symbol} order, and every triple's line and pay. */
    private record Table(int total, int[] bounds, Line[] lines, int[] pays) {

        int draw(int roll) {
            for (int i = 0; i < bounds.length; i++) {
                if (roll < bounds[i]) {
                    return i;
                }
            }
            return bounds.length - 1;
        }
    }

    // ---- identity: the same inputs solve to the same engine -----------------------------------

    @Override
    public boolean equals(Object o) {
        return o instanceof SlotsEngine e && cap == e.cap && Double.compare(targetPercent, e.targetPercent) == 0
                && weights.equals(e.weights) && pays.equals(e.pays) && stakes.equals(e.stakes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(weights, pays, stakes, cap, targetPercent);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("SlotsEngine{cap=").append(cap).append(", target=").append(targetPercent);
        for (StakeOdds o : odds.values()) {
            sb.append(", ").append(o.stake()).append(": stone ").append(o.stone()).append(" -> ").append(o.rtp());
        }
        for (Dropped d : dropped) {
            sb.append(", ").append(d.stake()).append(": dropped");
        }
        return sb.append('}').toString();
    }
}
