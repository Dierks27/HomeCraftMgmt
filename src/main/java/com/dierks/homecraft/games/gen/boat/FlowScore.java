package com.dierks.homecraft.games.gen.boat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * How well a Mountain Run flows (MOUNTAIN-V2-SPEC §6): the owner rode the v3 spiral and said "go fast,
 * turn, go fast, turn" and "all left hand turns", so the planner measures exactly that, on the line model,
 * and refuses what repeats.
 *
 * <p><b>The metrics</b> (all on the {@link RideLine}: a slalom's on its gate line):
 * <ul>
 *   <li><b>F-T time:</b> the model time inside the tier's window (a gate).</li>
 *   <li><b>F-B balance:</b> 1 - |Σθ left - Σθ right| / Σ|θ| over the arcs after the pit (≥ 0.75 road,
 *       0.9 slalom).</li>
 *   <li><b>F-R same-way run:</b> the longest run of turns of one hand, where arcs of one hand less than
 *       {@value #CHAIN} apart are one turn and a link is one (≤ 3 road, ≤ 2 slalom).</li>
 *   <li><b>F-K hard brakes:</b> arcs entered over 1.2 v_c with no lip in the {@value #BRAKE_LOOK} before,
 *       per 1,000 blocks (≤ 1, road).</li>
 *   <li><b>F-C carry:</b> the arc length entered at ≤ 1.1 v_c over all the arc length (≥ 0.6, road),
 *       hairpin links aside: under the boat model no legal brake drop gets a boat under 1.1 v_c of an
 *       R 30-55 turn (a 2-block drop lands at about 18 b/s and its 26-block landing strip rebuilds 27), so
 *       a hairpin is a planned brake, which F-K's lip rule judges.</li>
 *   <li><b>F-P periodicity:</b> the largest mean-removed, normalised autocorrelation of |κ|(s) sampled every
 *       {@value #SAMPLE} blocks, over lags 40..400 (≤ 0.6 road, 0.8 slalom). A slalom's rhythm inside a
 *       gate set is wanted, so there each set counts as its mean |κ|: it is measured over set boundaries.</li>
 *   <li><b>F-V variety:</b> the Shannon entropy of beat bigrams over log₂ 64 (≥ 0.45), and no bigram over a
 *       quarter of them. Beats: Ls/Ms/Ss straights (≥ 100, 40-99, under 40), W/M arcs (R ≥ 60, 30-59), H a
 *       hairpin link, C a chicane pair, D a lip; a slalom's gate set is one beat, G.</li>
 *   <li><b>F-L rhythm:</b> the coefficient of variation of the straights' lengths (≥ 0.45, road); a
 *       slalom's of its gate-to-gate distances along the run, the open runs between sets included
 *       (≥ 0.12).</li>
 *   <li><b>F-F features:</b> the tier's S-curves, chicanes (its least to its most), long straights (≥ 120),
 *       hairpins (1 to its most on a road), staircases, and at least 80% of the lips brake drops (a link, or an
 *       arc slower than the landing speed + 4 b/s, within 60 after the landing strip) or on a straight of 100
 *       or more.</li>
 * </ul>
 * The score is 0.15 B + 0.10 (1 - K/km) + 0.20 C + 0.15 (1 - P) + 0.20 V + 0.10 min(1, CV) + 0.10 F, in
 * [0, 1]; a candidate passes when every gate holds and the score is at least {@value #PASS}. Pure and
 * deterministic.
 */
public final class FlowScore {

    /** The least score a candidate may have. */
    public static final double PASS = 0.60;
    /** Arcs of one hand less than this apart along the line are one turn (F-R). */
    static final double CHAIN = 25;
    /** F-R: an arc turning less than this (degrees) is a wobble, not a turn. */
    static final double WOBBLE = 3;
    /** F-K: a lip within this before an arc excuses its brake. */
    static final double BRAKE_LOOK = 40;
    /** F-P: |κ| sampled this often, lags from 40 to 400. */
    static final double SAMPLE = 4;
    static final int LAG_MIN = 40;
    static final int LAG_MAX = 400;
    /** F-F: a lip is a brake drop when a link or a slow arc comes within this after its landing strip. */
    static final double BRAKE_AFTER = 60;
    static final double SLOW = 4;

    /** The beats of F-V. */
    public enum Beat {
        Ls, Ms, Ss, W, M, H, C, D, G
    }

    /** What a part of the line is for, where it matters to the score. */
    public enum Mark {
        NONE, PIT, HAIRPIN, LINK, GATE
    }

    public final double seconds;
    public final double balance;
    public final int sameWay;
    public final double brakesPerKm;
    public final double carry;
    public final double period;
    public final double variety;
    public final double share;
    public final double rhythm;
    public final boolean features;
    /** What F-F found missing (empty when nothing). */
    public final List<String> missing;
    /** The gates that failed (F-T, F-B, ...), empty when none. */
    public final List<String> failed;
    public final double total;
    /** The beats, in order (for tests and the summary). */
    public final List<Beat> beats;

    private FlowScore(double seconds, double balance, int sameWay, double brakesPerKm, double carry, double period,
                      double variety, double share, double rhythm, boolean features, List<String> missing,
                      List<String> failed, double total, List<Beat> beats) {
        this.seconds = seconds;
        this.balance = balance;
        this.sameWay = sameWay;
        this.brakesPerKm = brakesPerKm;
        this.carry = carry;
        this.period = period;
        this.variety = variety;
        this.share = share;
        this.rhythm = rhythm;
        this.features = features;
        this.missing = List.copyOf(missing);
        this.failed = List.copyOf(failed);
        this.total = total;
        this.beats = List.copyOf(beats);
    }

    /** Whether every gate holds and the score is at least {@value #PASS}. */
    public boolean passes() {
        return failed.isEmpty() && total >= PASS;
    }

    /** "flow 0.78 (carry .81, balance .96, period .32, variety .71, brakes 0.3/km)". */
    public String summary() {
        return String.format(Locale.ROOT, "flow %.2f (carry %s, balance %s, period %s, variety %s, brakes %.1f/km)",
                total, two(carry), two(balance), two(period), two(variety), brakesPerKm);
    }

    private static String two(double v) {
        String s = String.format(Locale.ROOT, "%.2f", Math.max(0, Math.min(1, v)));
        return s.startsWith("0") ? s.substring(1) : s;
    }

    // ---- scoring ---------------------------------------------------------------------------------------

    /** The score of a planned run: its line ridden with its drops. */
    public static FlowScore of(Skeleton sk, DropPlan drops) {
        RideLine line = drops.ride();
        BoatLine.Result res = BoatLine.run(line.segs());
        Mark[] marks = marks(sk, line);
        List<Double> gates = new ArrayList<>();
        for (Skeleton.GateSet g : sk.gates) {
            for (double a : g.at()) {
                gates.add(a);
            }
        }
        return of(line, res, marks, sk.tier, gates, drops);
    }

    /** Each part of {@code line} marked: a gate's, the pit's, a hairpin's, another link's, or none. */
    static Mark[] marks(Skeleton sk, RideLine line) {
        Mark[] marks = new Mark[line.parts.size()];
        for (int i = 0; i < marks.length; i++) {
            RideLine.Part p = line.parts.get(i);
            Skeleton.Role role = sk.tags.get(p.element()).role();
            marks[i] = p.gate() ? Mark.GATE : role == Skeleton.Role.PIT ? Mark.PIT
                    : role == Skeleton.Role.HAIRPIN ? Mark.HAIRPIN : role.link() ? Mark.LINK : Mark.NONE;
        }
        return marks;
    }

    /** For each lip of {@code drops} in order, whether F-F counts it a brake drop (or one on a long straight). */
    static boolean[] braking(Skeleton sk, DropPlan drops) {
        RideLine line = drops.ride();
        BoatLine.Result res = BoatLine.run(line.segs());
        Mark[] marks = marks(sk, line);
        boolean[] out = new boolean[line.lips.size()];
        for (int k = 0; k < out.length; k++) {
            RideLine.Lip l = line.lips.get(k);
            out[k] = onLongStraight(line.parts, l.s()) || brakes(line, res, marks, l, k, drops);
        }
        return out;
    }

    /**
     * The score of {@code line} ridden as {@code res}, its parts marked {@code marks}, for {@code tier};
     * {@code gates} the slalom's gate positions in order (centreline), {@code drops} for the landing
     * strips (null: packed ones).
     */
    public static FlowScore of(RideLine line, BoatLine.Result res, Mark[] marks, MountainTier tier, List<Double> gates,
                               DropPlan drops) {
        boolean slalom = tier.slalom();
        List<RideLine.Part> parts = line.parts;
        List<String> failed = new ArrayList<>();
        double seconds = res.seconds();
        if (seconds < tier.tMin || seconds > tier.tMax) {
            failed.add("F-T");
        }
        // F-B
        double left = 0;
        double right = 0;
        for (int i = 0; i < parts.size(); i++) {
            RideLine.Part p = parts.get(i);
            if (!p.arc() || marks[i] == Mark.PIT) {
                continue;
            }
            if (p.angle() > 0) {
                right += p.angle();
            } else {
                left -= p.angle();
            }
        }
        double balance = left + right < 1e-9 ? 1 : 1 - Math.abs(left - right) / (left + right);
        if (balance < (slalom ? 0.9 : 0.75)) {
            failed.add("F-B");
        }
        // F-R
        int sameWay = sameWay(parts, marks);
        if (sameWay > (slalom ? 2 : 3)) {
            failed.add("F-R");
        }
        // F-K, F-C
        int hard = 0;
        double arcLen = 0;
        double carried = 0;
        for (int i = 0; i < parts.size(); i++) {
            RideLine.Part p = parts.get(i);
            if (!p.arc()) {
                continue;
            }
            double vc = res.corner()[i];
            double v = res.entry()[i];
            // a hairpin link is a planned brake (F-K's lip rule judges it): no legal brake drop brings a boat
            // under 1.1 v_c of an R 30-55 turn, its landing strip rebuilds the speed, so F-C reads the rest
            if (marks[i] != Mark.HAIRPIN) {
                arcLen += p.len();
                if (v <= 1.1 * vc + 1e-12) {
                    carried += p.len();
                }
            }
            if (p.gate() || v <= 1.2 * vc) {
                continue;
            }
            boolean excused = false;
            for (RideLine.Lip l : line.lips) {
                excused |= l.s() <= p.s0() && l.s() >= p.s0() - BRAKE_LOOK;
            }
            if (!excused) {
                hard++;
            }
        }
        double km = Math.max(1e-9, line.length / 1000);
        double brakesPerKm = hard / km;
        double carry = arcLen < 1e-9 ? 1 : carried / arcLen;
        if (!slalom && brakesPerKm > 1 + 1e-9) {
            failed.add("F-K");
        }
        if (!slalom && carry < 0.6) {
            failed.add("F-C");
        }
        // F-P
        double period = period(parts, marks, slalom);
        if (period > (slalom ? 0.8 : 0.6)) {
            failed.add("F-P");
        }
        // F-V
        List<Beat> beats = beats(line, marks);
        Map<String, Integer> bigrams = new HashMap<>();
        for (int i = 0; i + 1 < beats.size(); i++) {
            bigrams.merge(beats.get(i) + " " + beats.get(i + 1), 1, Integer::sum);
        }
        int pairs = Math.max(1, beats.size() - 1);
        double entropy = 0;
        int most = 0;
        for (int c : bigrams.values()) {
            double q = c / (double) pairs;
            entropy -= q * Math.log(q) / Math.log(2);
            most = Math.max(most, c);
        }
        double variety = entropy / 6;
        double share = most / (double) pairs;
        if (variety < 0.45 || share > 0.25) {
            failed.add("F-V");
        }
        // F-L
        double rhythm = slalom ? cv(gateGaps(gates)) : cv(straights(parts));
        if (rhythm < (slalom ? 0.12 : 0.45)) {
            failed.add("F-L");
        }
        // F-F
        List<String> missing = features(line, res, marks, tier, drops);
        boolean features = missing.isEmpty();
        if (!features) {
            failed.add("F-F");
        }
        double total = 0.15 * balance + 0.10 * Math.max(0, 1 - brakesPerKm) + 0.20 * carry + 0.15 * (1 - period)
                + 0.20 * Math.min(1, variety) + 0.10 * Math.min(1, rhythm) + 0.10 * (features ? 1 : 0);
        return new FlowScore(seconds, balance, sameWay, brakesPerKm, carry, period, variety, share, rhythm, features,
                missing, failed, total, beats);
    }

    /** F-R: the longest run of one-handed turns. */
    static int sameWay(List<RideLine.Part> parts, Mark[] marks) {
        List<Integer> hands = new ArrayList<>();
        double lastEnd = Double.NEGATIVE_INFINITY;
        int lastHand = 0;
        boolean inLink = false;
        for (int i = 0; i < parts.size(); i++) {
            RideLine.Part p = parts.get(i);
            boolean link = marks[i] == Mark.HAIRPIN || marks[i] == Mark.LINK;
            if (link) {
                if (!inLink) {
                    // the link's hand: its net turn
                    double net = 0;
                    for (int j = i; j < parts.size() && (marks[j] == Mark.HAIRPIN || marks[j] == Mark.LINK); j++) {
                        net += parts.get(j).angle();
                    }
                    int hand = net > 0 ? 1 : -1;
                    if (hand == lastHand && p.s0() - lastEnd < CHAIN) {
                        // one turn with the arc just before it
                    } else {
                        hands.add(hand);
                    }
                    lastHand = hand;
                }
                inLink = true;
                lastEnd = p.s1();
                continue;
            }
            inLink = false;
            if (!p.arc() || marks[i] == Mark.PIT || Math.abs(p.angle()) < Math.toRadians(WOBBLE)) {
                continue;
            }
            int hand = p.kappa() > 0 ? 1 : -1;
            if (!(hand == lastHand && p.s0() - lastEnd < CHAIN)) {
                hands.add(hand);
            }
            lastHand = hand;
            lastEnd = p.s1();
        }
        int best = 0;
        int run = 0;
        int prev = 0;
        for (int h : hands) {
            run = h == prev ? run + 1 : 1;
            prev = h;
            best = Math.max(best, run);
        }
        return best;
    }

    /** F-P over |κ| sampled every {@value #SAMPLE} blocks (a slalom's gate sets as their mean). */
    static double period(List<RideLine.Part> parts, Mark[] marks, boolean slalom) {
        if (parts.isEmpty()) {
            return 0;
        }
        double length = parts.get(parts.size() - 1).s1();
        int n = (int) Math.floor(length / SAMPLE);
        if (n < 4) {
            return 0;
        }
        double[] x = new double[n];
        int[] set = new int[n];
        int pi = 0;
        int setNo = 0;
        boolean inSet = false;
        int[] setOf = new int[parts.size()];
        for (int i = 0; i < parts.size(); i++) {
            boolean gate = marks[i] == Mark.GATE;
            if (gate && !inSet) {
                setNo++;
            }
            inSet = gate;
            setOf[i] = gate ? setNo : 0;
        }
        for (int k = 0; k < n; k++) {
            double s = k * SAMPLE;
            while (pi + 1 < parts.size() && parts.get(pi).s1() <= s) {
                pi++;
            }
            x[k] = Math.abs(parts.get(pi).kappa());
            set[k] = setOf[pi];
        }
        if (slalom) {
            Map<Integer, double[]> mean = new HashMap<>();
            for (int k = 0; k < n; k++) {
                if (set[k] > 0) {
                    double[] m = mean.computeIfAbsent(set[k], q -> new double[2]);
                    m[0] += x[k];
                    m[1]++;
                }
            }
            for (int k = 0; k < n; k++) {
                if (set[k] > 0) {
                    double[] m = mean.get(set[k]);
                    x[k] = m[0] / m[1];
                }
            }
        }
        double mu = 0;
        for (double v : x) {
            mu += v;
        }
        mu /= n;
        double var = 0;
        for (double v : x) {
            var += (v - mu) * (v - mu);
        }
        if (var < 1e-15) {
            return 0;
        }
        double best = -1;
        for (int lag = (int) (LAG_MIN / SAMPLE); lag <= (int) (LAG_MAX / SAMPLE) && lag < n; lag++) {
            double c = 0;
            for (int k = 0; k + lag < n; k++) {
                c += (x[k] - mu) * (x[k + lag] - mu);
            }
            best = Math.max(best, c / var);
        }
        return Math.max(0, best);
    }

    /** F-V's beats along the line. */
    static List<Beat> beats(RideLine line, Mark[] marks) {
        List<Beat> out = new ArrayList<>();
        List<RideLine.Part> parts = line.parts;
        int i = 0;
        while (i < parts.size()) {
            RideLine.Part p = parts.get(i);
            if (marks[i] == Mark.GATE) {
                while (i < parts.size() && marks[i] == Mark.GATE) {
                    i++;
                }
                out.add(Beat.G);
                continue;
            }
            if (marks[i] == Mark.HAIRPIN) {
                while (i < parts.size() && marks[i] == Mark.HAIRPIN) {
                    i++;
                }
                out.add(Beat.H);
                continue;
            }
            if (!p.arc()) {
                int j = i;
                double len = 0;
                List<Beat> lips = new ArrayList<>();
                while (j < parts.size() && !parts.get(j).arc() && marks[j] != Mark.GATE && marks[j] != Mark.HAIRPIN) {
                    len += parts.get(j).len();
                    for (RideLine.Lip l : line.lips) {
                        if (l.s() >= parts.get(j).s0() && l.s() < parts.get(j).s1()) {
                            lips.add(Beat.D);
                        }
                    }
                    j++;
                }
                out.add(len >= 100 ? Beat.Ls : len >= 40 ? Beat.Ms : Beat.Ss);
                out.addAll(lips);
                i = j;
                continue;
            }
            // a chicane pair: two opposite tight arcs, at most 8 apart
            int pair = chicane(parts, marks, i);
            if (pair > i) {
                out.add(Beat.C);
                i = pair + 1;
                continue;
            }
            out.add(p.radius() >= 60 ? Beat.W : Beat.M);
            i++;
        }
        return out;
    }

    /**
     * The index of the second arc of a chicane starting at arc {@code i} (opposite hands, R 30-40, 15-30°
     * each, at most 8 of straight between), or -1.
     */
    static int chicane(List<RideLine.Part> parts, Mark[] marks, int i) {
        return pairEnd(parts, marks, i, 29.5, 40.5, 14.5, 30.5, 8.5);
    }

    /** The same for an S-curve: R 50-120, 20-45°, at most 20 between. */
    static int sCurve(List<RideLine.Part> parts, Mark[] marks, int i) {
        return pairEnd(parts, marks, i, 49.5, 120.5, 19.5, 45.5, 20.5);
    }

    static int pairEnd(List<RideLine.Part> parts, Mark[] marks, int i, double rLo, double rHi, double dLo, double dHi,
                       double gapMax) {
        RideLine.Part a = parts.get(i);
        if (!a.arc() || marks[i] != Mark.NONE || !within(a, rLo, rHi, dLo, dHi)) {
            return -1;
        }
        double gap = 0;
        int j = i + 1;
        while (j < parts.size() && !parts.get(j).arc() && marks[j] == Mark.NONE) {
            gap += parts.get(j).len();
            j++;
        }
        if (j >= parts.size() || gap > gapMax || marks[j] != Mark.NONE) {
            return -1;
        }
        RideLine.Part b = parts.get(j);
        if (!b.arc() || Math.signum(b.kappa()) == Math.signum(a.kappa()) || !within(b, rLo, rHi, dLo, dHi)) {
            return -1;
        }
        return j;
    }

    static boolean within(RideLine.Part p, double rLo, double rHi, double dLo, double dHi) {
        double deg = Math.toDegrees(Math.abs(p.angle()));
        return p.radius() >= rLo && p.radius() <= rHi && deg >= dLo && deg <= dHi;
    }

    /** The straights' lengths (consecutive straight parts as one), the pit's excepted. */
    static List<Double> straights(List<RideLine.Part> parts) {
        List<Double> out = new ArrayList<>();
        double run = 0;
        for (RideLine.Part p : parts) {
            if (p.arc()) {
                if (run > 0) {
                    out.add(run);
                }
                run = 0;
            } else {
                run += p.len();
            }
        }
        if (run > 0) {
            out.add(run);
        }
        return out;
    }

    static List<Double> gateGaps(List<Double> gates) {
        List<Double> out = new ArrayList<>();
        for (int i = 0; i + 1 < gates.size(); i++) {
            out.add(gates.get(i + 1) - gates.get(i));
        }
        return out;
    }

    /** The coefficient of variation (0 for fewer than two values). */
    static double cv(List<Double> xs) {
        if (xs.size() < 2) {
            return 0;
        }
        double mu = 0;
        for (double x : xs) {
            mu += x;
        }
        mu /= xs.size();
        double var = 0;
        for (double x : xs) {
            var += (x - mu) * (x - mu);
        }
        var /= xs.size();
        return mu < 1e-9 ? 0 : Math.sqrt(var) / mu;
    }

    /** F-F: what the tier asks for that the run lacks. */
    static List<String> features(RideLine line, BoatLine.Result res, Mark[] marks, MountainTier tier, DropPlan drops) {
        List<String> out = new ArrayList<>();
        List<RideLine.Part> parts = line.parts;
        int sCurves = 0;
        int chicanes = 0;
        for (int i = 0; i < parts.size(); i++) {
            int c = chicane(parts, marks, i);
            if (c > i) {
                chicanes++;
                i = c;
                continue;
            }
            int s = sCurve(parts, marks, i);
            if (s > i) {
                sCurves++;
                i = s;
            }
        }
        int longs = 0;
        for (double len : straights(parts)) {
            longs += len >= 120 ? 1 : 0;
        }
        int hairpins = 0;
        for (int i = 0; i < parts.size(); i++) {
            if (marks[i] == Mark.HAIRPIN && (i == 0 || marks[i - 1] != Mark.HAIRPIN)) {
                hairpins++;
            }
        }
        if (sCurves < tier.sCurvesMin) {
            out.add(sCurves + " S-curves (" + tier.sCurvesMin + " wanted)");
        }
        if (chicanes < tier.chicMin || chicanes > tier.chicMax) {
            out.add(chicanes + " chicanes (" + tier.chicMin + "-" + tier.chicMax + ")");
        }
        if (longs < tier.longMin) {
            out.add(longs + " long straights (" + tier.longMin + " wanted)");
        }
        int hairMin = tier.road() ? 1 : 0;
        if (hairpins < hairMin || hairpins > tier.hairMost) {
            out.add(hairpins + " hairpins (" + hairMin + "-" + tier.hairMost + ")");
        }
        // staircases: two lips or more on one straight
        int stairs = 0;
        int lip = 0;
        double run = 0;
        int onRun = 0;
        for (RideLine.Part p : parts) {
            if (p.arc()) {
                stairs += onRun >= 2 ? 1 : 0;
                onRun = 0;
                run = 0;
                continue;
            }
            run += p.len();
            while (lip < line.lips.size() && line.lips.get(lip).s() < p.s1()) {
                if (line.lips.get(lip).s() >= p.s0()) {
                    onRun++;
                }
                lip++;
            }
        }
        stairs += onRun >= 2 ? 1 : 0;
        if (stairs < tier.stairsMin) {
            out.add(stairs + " staircases (" + tier.stairsMin + " wanted)");
        }
        // brake drops
        int braking = 0;
        for (int k = 0; k < line.lips.size(); k++) {
            RideLine.Lip l = line.lips.get(k);
            if (onLongStraight(parts, l.s()) || brakes(line, res, marks, l, k, drops)) {
                braking++;
            }
        }
        if (!line.lips.isEmpty() && braking < 0.8 * line.lips.size() - 1e-9) {
            out.add(braking + " of " + line.lips.size() + " lips brake (80% wanted)");
        }
        return out;
    }

    /** Whether the lip at line position {@code s} stands on a straight of 100 or more. */
    static boolean onLongStraight(List<RideLine.Part> parts, double s) {
        double from = -1;
        double len = 0;
        boolean has = false;
        for (RideLine.Part p : parts) {
            if (p.arc()) {
                if (has && len >= 100) {
                    return true;
                }
                has = false;
                len = 0;
                continue;
            }
            len += p.len();
            has |= s >= p.s0() && s < p.s1();
            from = p.s0();
        }
        return has && len >= 100 && from >= 0;
    }

    /** Whether lip {@code k} brakes for a link or a slow arc within {@value #BRAKE_AFTER} after its landing strip. */
    static boolean brakes(RideLine line, BoatLine.Result res, Mark[] marks, RideLine.Lip l, int k, DropPlan drops) {
        boolean blue = drops != null && drops.blueAt(l.c() - 1);
        double horizon = l.s() + MountainTier.landing(l.drop(), blue) + BRAKE_AFTER;
        double landing = k < res.landing().length ? res.landing()[k] * BoatLine.TICKS : 0;
        for (int i = 0; i < line.parts.size(); i++) {
            RideLine.Part p = line.parts.get(i);
            if (p.s0() < l.s()) {
                continue;
            }
            if (p.s0() > horizon) {
                break;
            }
            if (marks[i] == Mark.HAIRPIN || marks[i] == Mark.LINK) {
                return true;
            }
            if (p.arc() && res.corner()[i] * BoatLine.TICKS < landing + SLOW) {
                return true;
            }
        }
        return false;
    }
}
