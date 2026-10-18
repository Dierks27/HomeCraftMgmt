package com.dierks.homecraft.games.gen.boat;

import com.dierks.homecraft.games.gen.api.GenRandom;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Where a Mountain Run v2 drops (MOUNTAIN-V2-SPEC §3.4, §7.2): every lip, how far it falls, and (hard
 * roads) the blue straights, all on the {@link Skeleton}'s straights.
 *
 * <p><b>A drop is a brake</b> (§3.1: a 2-block hop from 40 b/s lands at 19 b/s), so drops go where speed
 * must be shed anyway. In order:
 * <ol>
 *   <li>the <b>Final Drop</b>, mandatory, on the finish straight in front of the stand, 43-70 before the
 *       finish and at least Z(d) + 3 from it, in the finish's own leg (no checkpoint between);</li>
 *   <li>the <b>blue straights</b> (hard): long straights all blue, each ending in a 1-block brake drop
 *       (algo 4: or a sweeper R ≥ 150; their landing strips are the blue 33/41);</li>
 *   <li><b>staircases</b> ("THE CLIFFS"): 2-3 lips on one long straight;</li>
 *   <li><b>brake drops</b>: the line is ridden ({@link RideLine}, {@link BoatLine}); the arc entered
 *       hardest over its v_c (with no lip in the 40 before it) gets a lip in that 40, landing strip and
 *       all, and the line is ridden again;</li>
 *   <li><b>spread</b>: no 500 blocks without a lip outside the links (300 on the wide road's medium and hard
 *       tiers);</li>
 *   <li><b>fill</b> to the tier's count by brake value and spread, seeded ties, on any straight (algo 4:
 *       only where a lip brakes or on a long straight);</li>
 *   <li>then <b>2-block drops</b> (the Final Drop first) within the tier's 2-count and descent cap.</li>
 * </ol>
 *
 * <p><b>The proof's spacing, kept here.</b> Each lip has a straight run-up of {@link MountainTier#runUp}
 * and a straight landing strip; consecutive lips are at least Z(d) + 12 apart along the line, and each lip
 * stands past the earlier one's flight zone as the validator floods it (a disc of Z(d) round the lip,
 * walked along the line until the whole lane is out of it) with room for a checkpoint after it; and the
 * leg across a drop, from the checkpoint just before its lip to the first one past its zone, stays within
 * 60, or {@value #BIG_LEG} across a Big Drop (MOUNTAIN-V21-SPEC D2; with {@value #ROUNDING} for whole blocks,
 * and the raster's GROW budgeted at the Final Drop and wherever one checkpoint serves the legs either side
 * of it). The lane keeps its width at every lip: there are no necks, and a Big Drop stands only on an
 * axis-aligned straight, where its checkpoint is the smallest. A gate field and a piece leave
 * {@link MountainTier#clearBefore} before a lip, room for the checkpoint there.
 *
 * <p><b>Algo 4</b> ({@link MountainTier#wide} false: 0.37.0, kept block for block) holds every leg to 60. With
 * a checkpoint radius of W / 2 + 0.5 that leg is too long for a 2-block drop on a lane wider than 7, so such
 * a drop stands in a {@value MountainTier#NECK}-wide neck ({@link #necks}); every run-up is
 * {@link MountainTier#clearBefore} of the lane there, fill lays a lip only where it brakes or on a long
 * straight, and a blue straight may end in a sweeper. Pure.
 */
public final class DropPlan {

    /** Lips stand at least Z(d) + this apart along the line (§3.4). */
    static final double LIP_GAP = 12;
    /** The first lip is at least this far after the start (§4.2). */
    static final double FIRST_LIP = 60;
    /** A brake drop's lip is within this of the arc it brakes for (F-K's window). */
    static final double BRAKE_WINDOW = 40;
    /** No more than this along the line (outside links) without a lip (§7.2). */
    static final double SPREAD = 500;
    /**
     * The wide road's medium and hard tiers keep lips closer than {@value #SPREAD} (MOUNTAIN-V21-SPEC §6.4 step S:
     * in the mini soak a hard road's longest lip-free stretch was over 250 at p90); easy, the slaloms and algo 4
     * keep {@value #SPREAD}.
     */
    static final double SPREAD_ROAD = 300;

    /**
     * The most along the line (links not counted) without a lip on {@code tier}: {@link #SPREAD}, or
     * {@link #SPREAD_ROAD}.
     */
    static double spreadFor(MountainTier tier) {
        return tier.road() && tier.wide() && !tier.easy() ? SPREAD_ROAD : SPREAD;
    }

    /**
     * A run may fall this many lips short of the tier's count (§3.4's counts are targets; the proof's floor
     * is the descent, V8): the straights a 2-minute run has room for hold about that many fewer at the
     * spacing the flight zones need.
     */
    static final int SHORT_BY = 3;
    /**
     * Algo 4's fill lays a lip only where it brakes: before a link or an arc slower than this (b/s), or on a long
     * straight.
     */
    static final double BRAKE_VC = 26;
    /** Algo 4's long straight (fill and F-F: its lips count as brake drops). */
    static final double LONG_RUN = 100;
    /** A checkpoint's centre keeps this far from a lip cell and a zone cell (V6), and the legs' bound with a margin. */
    static final double CLEAR = 3;
    /** A leg with a hop, or none, stays within this across (§2.6); algo 4's every leg. */
    static final double LEG = DownhillValidator.MAX_LEG;              // 60
    /** A leg across a Big Drop may be this long (0.37.1, D2). */
    static final double BIG_LEG = MountainValidator.MAX_LEG_BIG_DROP; // 66
    /** An upgrade to a 2-block drop may move its lip this far along its straight. */
    static final double UPGRADE_SHIFT = 6;
    /** What whole blocks and half-block checkpoint places add to a leg across a drop. */
    static final double ROUNDING = 0.25;
    /**
     * Algo 4's neck: from this far before a lip to this far after it, with tapers of {@link #NECK_TAPER} either
     * side.
     */
    static final double NECK_BEFORE = 16;
    static final double NECK_AFTER = 4;
    static final double NECK_TAPER = 6;

    /** The most a leg holding a drop of {@code d} may be across on the wide road: 66 over a Big Drop, else 60. */
    static double legFor(int d) {
        return d >= 2 ? BIG_LEG : LEG;
    }

    /** Why a lip is where it is. */
    public enum Kind {
        FINAL, STAIR, BRAKE, SPREAD, FILL
    }

    /** One lip: the level changes {@code s} along the centreline, falling {@code drop} blocks. */
    public record Drop(double s, int drop, Kind kind) {
    }

    public final Skeleton sk;
    public final MountainTier tier;
    /** The lips in order along the line. */
    public final List<Drop> drops;
    /** Blue straights (hard): {from, to} along the centreline. */
    public final List<double[]> blue;

    DropPlan(Skeleton sk, List<Drop> drops, List<double[]> blue) {
        this.sk = sk;
        this.tier = sk.tier;
        List<Drop> sorted = new ArrayList<>(drops);
        sorted.sort(Comparator.comparingDouble(Drop::s));
        this.drops = List.copyOf(sorted);
        this.blue = List.copyOf(blue);
    }

    /** The whole fall, blocks. */
    public int descent() {
        int d = 0;
        for (Drop x : drops) {
            d += x.drop();
        }
        return d;
    }

    /** How many 2-block drops. */
    public int bigs() {
        int n = 0;
        for (Drop x : drops) {
            n += x.drop() >= 2 ? 1 : 0;
        }
        return n;
    }

    public Drop last() {
        return drops.isEmpty() ? null : drops.get(drops.size() - 1);
    }

    /** The line's straight runs (consecutive straight elements), in order along. */
    List<Run> runs() {
        return new Planner(new GenRandom(0), sk).runs;
    }

    /** How many STAIR lips stand on {@code run} (the Final Drop, the last lip, is never one). */
    int stairsOn(Run run) {
        int n = 0;
        for (int i = 0; i < drops.size() - 1; i++) {
            Drop d = drops.get(i);
            n += d.kind() == Kind.STAIR && d.s() >= run.s0() && d.s() <= run.s1() ? 1 : 0;
        }
        return n;
    }

    /**
     * The staircases ("THE CLIFFS!", §7.1): straight runs holding two or more STAIR lips (audit MTN-R3-03: not
     * any two lips on a straight).
     */
    public int staircases() {
        int n = 0;
        for (Run run : runs()) {
            n += stairsOn(run) >= 2 ? 1 : 0;
        }
        return n;
    }

    /** The track's level {@code s} along, relative to the finish's (0): the drops still to come. */
    public int levelAbove(double s) {
        int h = 0;
        for (int i = drops.size() - 1; i >= 0; i--) {
            if (drops.get(i).s() > s) {
                h += drops.get(i).drop();
            } else {
                break;
            }
        }
        return h;
    }

    /** How many lips lie in (a, b]. */
    public int lipsBetween(double a, double b) {
        int n = 0;
        for (Drop x : drops) {
            n += x.s() > a && x.s() <= b ? 1 : 0;
        }
        return n;
    }

    /** Whether a 2-block lip lies in (a, b]: the leg holding it may be {@value #BIG_LEG} on the wide road. */
    public boolean bigBetween(double a, double b) {
        for (Drop x : drops) {
            if (x.drop() >= 2 && x.s() > a && x.s() <= b) {
                return true;
            }
        }
        return false;
    }

    /** Fast ice for the line model: {from, to, f} along the centreline. */
    public List<double[]> fast() {
        List<double[]> out = new ArrayList<>();
        for (double[] b : blue) {
            out.add(new double[]{b[0], b[1], BoatLine.BLUE});
        }
        return out;
    }

    /** Whether centreline position {@code s} is on a blue straight. */
    public boolean blueAt(double s) {
        for (double[] b : blue) {
            if (s >= b[0] && s <= b[1]) {
                return true;
            }
        }
        return false;
    }

    /**
     * Algo 4's necks (2-block drops on a lane wider than {@value MountainTier#NECK}): {from, to} along the
     * centreline, tapers outside; none on the wide road.
     */
    public List<double[]> necks() {
        List<double[]> out = new ArrayList<>();
        if (tier.wide()) {
            return out;
        }
        for (Drop d : drops) {
            if (d.drop() >= 2 && sk.width(d.s()) > MountainTier.NECK) {
                out.add(new double[]{d.s() - NECK_BEFORE, d.s() + NECK_AFTER});
            }
        }
        return out;
    }

    /** The lane's width {@code s} along: the skeleton's, on the wide road everywhere; algo 4's necks and tapers in. */
    public double width(double s) {
        double w = sk.width(s);
        if (tier.wide()) {
            return w;
        }
        for (double[] n : necks()) {
            if (s >= n[0] - NECK_TAPER && s <= n[1] + NECK_TAPER) {
                double into = s < n[0] ? (s - (n[0] - NECK_TAPER)) / NECK_TAPER
                        : s > n[1] ? ((n[1] + NECK_TAPER) - s) / NECK_TAPER : 1;
                w = Math.min(w, w - (w - MountainTier.NECK) * Math.max(0, Math.min(1, into)));
            }
        }
        return w;
    }

    /** The line through this plan's lips and fast ice. */
    public RideLine ride() {
        return RideLine.of(sk, drops, fast());
    }

    // ---- drawing ---------------------------------------------------------------------------------------

    /** The drops of {@code sk} from stream {@code r}, or {@code null} when the tier's count or descent can't be met. */
    public static DropPlan draw(GenRandom r, Skeleton sk) {
        return new Planner(r, sk).plan();
    }

    /** A straight run: consecutive straight elements, [s0, s1] along the centreline. */
    record Run(double s0, double s1) {
        double length() {
            return s1 - s0;
        }
    }

    /** The planner's working state. */
    static final class Planner {
        final GenRandom r;
        final Skeleton sk;
        final MountainTier tier;
        /** The wide road's rules ({@link MountainTier#wide}), else algo 4's. */
        final boolean wide;
        final List<Run> runs = new ArrayList<>();
        final List<Drop> lips = new ArrayList<>();
        final List<double[]> blue = new ArrayList<>();
        /** Each lip's zone end along the centreline, as the flood would reach it. */
        final List<Double> zoneEnd = new ArrayList<>();
        int target;
        int descentWanted;
        int bigsWanted;

        Planner(GenRandom r, Skeleton sk) {
            this.r = r;
            this.sk = sk;
            this.tier = sk.tier;
            this.wide = tier.wide();
            Run open = null;
            for (Centreline.Element e : sk.line.elements()) {
                if (!e.arc()) {
                    open = open == null ? new Run(e.s0, e.s1()) : new Run(open.s0(), e.s1());
                } else {
                    if (open != null) {
                        runs.add(open);
                    }
                    open = null;
                }
            }
            if (open != null) {
                runs.add(open);
            }
        }

        DropPlan plan() {
            target = r.nextInt(tier.dropsMin, tier.dropsMax);
            descentWanted = r.nextInt(tier.descentLo, tier.descentHi);
            bigsWanted = Math.max(0, Math.min(tier.bigFor(target), descentWanted - target));
            if (!finalDrop()) {
                return null;
            }
            if (tier.road() && tier.blueMax > 0) {
                blueStraights();
            }
            staircases();
            brakes();
            spread();
            fill();
            downgrade();
            upgrade();
            share();
            DropPlan p = new DropPlan(sk, lips, blue);
            int descent = p.descent();
            if (lips.size() < tier.dropsMin - SHORT_BY || lips.size() > tier.dropsMax || descent < tier.descentMin
                    || descent > tier.descentCap || p.bigs() > tier.bigFor(lips.size())) {
                return null;
            }
            return p;
        }

        // ---- the rules a lip keeps ------------------------------------------------------------------

        Run runAt(double s) {
            for (Run run : runs) {
                if (s >= run.s0() && s <= run.s1()) {
                    return run;
                }
            }
            return null;
        }

        /** The lane's width at a lip of {@code d} at {@code s}: the skeleton's (algo 4: a neck's for a necked drop). */
        double width(double s, int d) {
            double w = sk.width(s);
            return necked(s, d) ? MountainTier.NECK : w;
        }

        /** Whether a {@code d}-block drop {@code s} along stands in a neck (algo 4 only). */
        boolean necked(double s, int d) {
            return !wide && d >= 2 && sk.width(s) > MountainTier.NECK;
        }

        /**
         * How far before its lip the checkpoint ahead of a drop sits: past its sphere's flat, 3 from the lip
         * ({@link MountainTier#setBack} of the lane there).
         */
        double before(double s, int d) {
            return MountainTier.setBack(width(s, d), axis(s));
        }

        /** The most the leg holding a drop of {@code d} may be across: {@link #legFor} (algo 4: 60 for every one). */
        double leg(int d) {
            return wide ? legFor(d) : LEG;
        }

        /**
         * How far the raster may grow a checkpoint ({@code RasterV4.GROW}), budgeted where one serves two legs and
         * at the Final Drop (0.37.1, D2); algo 4 budgets none.
         */
        double grow() {
            return wide ? RasterV4.GROW : 0;
        }

        /**
         * A lip's straight run-up: {@link MountainTier#runUp} of the checkpoint's set-back {@code c} before it
         * (algo 4: {@link MountainTier#clearBefore} of the lane there, a neck's for a necked drop).
         */
        int runUp(double s, int d, double c) {
            return wide ? tier.runUp(c) : MountainTier.clearBefore(width(s, d));
        }

        /** Whether the line {@code s} along is a straight along x or z (its checkpoints the smallest). */
        boolean axis(double s) {
            Centreline.Element e = sk.line.elementAt(s);
            return !e.arc() && Math.abs(Math.sin(2 * e.h0)) < 1e-6;
        }

        /**
         * Where a lip's flight zone ends along the centreline: the first place where the whole lane is more
         * than Z(d) from every lip cell (the flood can't pass it), walked a block at a time.
         */
        double zoneEnd(double s, int d) {
            double w = width(s, d);
            double[] q = sk.line.at(s - 0.5);
            double[] t = sk.line.tangent(s - 0.5);
            double nx = -t[1];
            double nz = t[0];
            double half = w / 2.0;
            double reach = BoatEnvelope.zone(d) + 0.75;
            double length = sk.line.length();
            for (double u = s; u <= length; u += 0.5) {
                double[] p = sk.line.at(u);
                double[] tu = sk.line.tangent(u);
                double side = sk.width(u) / 2.0 + (tier.road() ? PiecesV4.RUNOFF : 0);
                boolean out = true;
                for (int k = -4; k <= 4 && out; k++) {
                    double px = p[0] - tu[1] * side * k / 4.0;
                    double pz = p[1] + tu[0] * side * k / 4.0;
                    double dx = px - q[0];
                    double dz = pz - q[1];
                    double across = dx * nx + dz * nz;
                    double clamp = Math.max(-half, Math.min(half, across));
                    out = Math.hypot(dx - clamp * nx, dz - clamp * nz) > reach;
                }
                if (out) {
                    return u;
                }
            }
            return length;
        }

        /** Whether a gate field (its first fence less 3 to its last plus 3) meets [a, b]. */
        boolean gates(double a, double b) {
            for (Skeleton.GateSet g : sk.gates) {
                if (b >= g.from() - CLEAR - 1 && a <= g.to() + CLEAR + 1) {
                    return true;
                }
            }
            return false;
        }

        /** Whether a lip of {@code d} at {@code s} keeps every rule against the lips already placed. */
        boolean ok(double s, int d) {
            // the wide road keeps V11's Z(d) + 3 from the finish here too: upgrade() moves a hop of a Final Drop
            // into a Big one through this (algo 4: Z(d) + 2)
            if (s < sk.start + FIRST_LIP || s > sk.finish - BoatEnvelope.zone(d) - (wide ? CLEAR : 2)) {
                return false;
            }
            Run run = runAt(s);
            if (run == null) {
                return false;
            }
            double w = width(s, d);
            double c = before(s, d);
            boolean blueHere = inBlue(s - 1);
            if (s - run.s0() < runUp(s, d, c) || run.s1() - s < MountainTier.landing(d, blueHere)) {
                return false;
            }
            if (wide && d >= 2 && !(axis(s) && axis(s - c))) {
                return false; // a Big Drop and the checkpoint before it stand on an axis-aligned straight (D2)
            }
            if (c + BoatEnvelope.zone(d) + CLEAR + ROUNDING > leg(d)) {
                return false; // the leg across it would be longer than 60 (66 across a Big Drop)
            }
            double end = zoneEnd(s, d);
            // a gate field leaves room for the checkpoint before the lip: the lane's own clearBefore, never the
            // tight run-up (D4 item 3); algo 4's neck and its taper on top
            double back = MountainTier.clearBefore(w) + CLEAR + (w < sk.width(s) ? NECK_BEFORE + NECK_TAPER : 0);
            if (gates(s - back, end + CLEAR + 8)) {
                return false;
            }
            // the necks keep clear of the pit and the finish
            if (necked(s, d) && (s - NECK_BEFORE - NECK_TAPER < Frame.PIT)) {
                return false;
            }
            for (int i = 0; i < lips.size(); i++) {
                Drop o = lips.get(i);
                if (o.s() <= s) {
                    if (s - o.s() < BoatEnvelope.zone(o.drop()) + LIP_GAP || s < zoneEnd.get(i) + CLEAR + c + 1) {
                        return false;
                    }
                } else {
                    double oc = before(o.s(), o.drop());
                    if (o.s() - s < BoatEnvelope.zone(d) + LIP_GAP || o.s() < end + CLEAR + oc + 1) {
                        return false;
                    }
                }
            }
            return chains(s, d, end);
        }

        /**
         * Whether checkpoints can still be strung from the start across every lip (and the one at
         * {@code s}, its zone ending at {@code end}) to the finish, each leg within {@link #leg} of the drop it
         * holds: between two lips the checkpoints stand from the earlier zone's end + 3 to the later lip less
         * its radius, and where that window holds only one checkpoint (two spheres need their radii + 0.5
         * between them) that one must serve the legs either side, placed as if the one before it were grown
         * ({@link #grow}). Walked forward, keeping the furthest place the last checkpoint before each lip can
         * be; the finish's leg budgets the grow too, as {@link #finalDrop}'s window does.
         */
        boolean chains(double s, int d, double end) {
            double far = Double.NaN; // the furthest the last checkpoint before the next lip can be
            double prevEnd = Double.NaN;
            int prevD = 0;
            int n = lips.size();
            boolean placed = false;
            for (int i = 0; i <= n; i++) {
                double ls;
                int ld;
                double le;
                if (!placed && (i == n || lips.get(i).s() > s)) {
                    ls = s;
                    ld = d;
                    le = end;
                    placed = true;
                    i--;
                } else if (i < n) {
                    ls = lips.get(i).s();
                    ld = lips.get(i).drop();
                    le = zoneEnd.get(i);
                } else {
                    break;
                }
                double c = before(ls, ld);
                double b = ls - c;
                if (Double.isNaN(prevEnd)) {
                    far = b; // before the first lip the track is open from the start
                } else {
                    double a = prevEnd + CLEAR + ROUNDING;
                    if (a > b || a > far + leg(prevD)) {
                        return false;
                    }
                    boolean two = a + spotAt(a) + c + 0.75 <= b;
                    if (!two && a > far + leg(prevD) - grow()) {
                        return false; // the one checkpoint between can't serve both legs with the one before it grown
                    }
                    far = two ? b : Math.min(b, far + leg(prevD) - grow());
                }
                prevEnd = le;
                prevD = ld;
            }
            // the last lip is the Final Drop, the finish's leg its own
            return Double.isNaN(prevEnd) || sk.finish - far <= leg(prevD) - ROUNDING - grow();
        }

        boolean inBlue(double s) {
            for (double[] b : blue) {
                if (s >= b[0] && s <= b[1]) {
                    return true;
                }
            }
            return false;
        }

        void add(double s, int d, Kind kind) {
            int at = 0;
            while (at < lips.size() && lips.get(at).s() < s) {
                at++;
            }
            lips.add(at, new Drop(s, d, kind));
            zoneEnd.add(at, zoneEnd(s, d));
        }

        void remove(int i) {
            lips.remove(i);
            zoneEnd.remove(i);
        }

        /** Place a lip of {@code d} somewhere in [a, b] (half blocks, seeded start); whether one fitted. */
        boolean placeIn(double a, double b, int d, Kind kind) {
            double lo = Math.ceil(a * 2) / 2;
            double hi = Math.floor(b * 2) / 2;
            if (hi < lo) {
                return false;
            }
            int steps = (int) Math.round((hi - lo) * 2) + 1;
            int first = r.nextInt(steps);
            for (int i = 0; i < steps; i++) {
                double s = lo + ((first + i) % steps) / 2.0;
                if (ok(s, d)) {
                    add(s, d, kind);
                    return true;
                }
            }
            return false;
        }

        // ---- the steps --------------------------------------------------------------------------------

        /** The Final Drop: on the finish straight, its leg running on to the finish (a 2 where the tier has any). */
        boolean finalDrop() {
            int d = tier.bigFor(tier.dropsMax) >= 1 ? 2 : 1;
            for (int dd = d; dd >= 1; dd--) {
                double c = before(sk.finish, dd);
                // the window ends where the finish's leg, its checkpoint grown, is still within its bound (D3:
                // 58 at 11 wide down to 54.5 at 18 for a Big Final Drop); on the wide road it starts at V11's own
                // Z(d) + 3 (the lip's cells stand before it), so the 18-wide road's window is [54, 54.5] and holds
                // a half-block lip wherever the finish is (algo 4: Z(d) + 3.5)
                double lo = BoatEnvelope.zone(dd) + CLEAR + (wide ? 0 : 0.5);
                double hi = Math.min(70, leg(dd) - c - ROUNDING - grow());
                if (lo > hi) {
                    continue;
                }
                double want = sk.finish - r.nextDouble(lo, hi);
                double s = Math.round(want * 2) / 2.0;
                for (double t = 0; t <= hi - lo + 1; t += 0.5) {
                    for (double cand : new double[]{s - t, s + t}) {
                        double delta = sk.finish - cand;
                        if (delta < lo - 1e-9 || delta > hi + 1e-9) {
                            continue;
                        }
                        if (finalOk(cand, dd)) {
                            add(cand, dd, Kind.FINAL);
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        boolean finalOk(double s, int d) {
            Run run = runAt(s);
            if (run == null || run.s1() < sk.finish) {
                return false;
            }
            double c = before(s, d);
            if (wide && d >= 2 && !(axis(s) && axis(s - c))) {
                return false; // a Big Final Drop on an axis-aligned straight, as any Big Drop
            }
            return s - run.s0() >= runUp(s, d, c) + (necked(s, d) ? NECK_TAPER : 0);
        }

        /**
         * Hard: long straights all blue, each ending in a 1-block brake drop (1-2 on the wide road; algo 4's 2-4
         * may end in a sweeper R >= 150 instead).
         */
        void blueStraights() {
            int want = r.nextInt(tier.blueMin, tier.blueMax);
            List<Run> longOnes = new ArrayList<>();
            for (Run run : runs) {
                if (run.length() >= 110 && run.s0() > sk.start + 40 && run.s1() < sk.finish - 120) {
                    longOnes.add(run);
                }
            }
            shuffle(longOnes);
            for (Run run : longOnes) {
                if (blue.size() >= want) {
                    break;
                }
                Centreline.Element next = sk.line.elementAt(run.s1() + 0.5);
                double from = run.s0() + 2;
                if (!wide && next.arc() && next.radius >= 150) {
                    blue.add(new double[]{from, run.s1()});
                    continue;
                }
                // a brake drop at its end: blue to the lip, the blue landing strip after it
                double lipHi = run.s1() - MountainTier.landing(1, true);
                double lipLo = Math.max(from + 100, run.s1() - BRAKE_WINDOW);
                if (lipLo > lipHi) {
                    continue;
                }
                blue.add(new double[]{from, lipHi});
                double[] b = blue.get(blue.size() - 1);
                boolean placed = false;
                for (double s = lipHi; s >= lipLo && !placed; s -= 0.5) {
                    b[1] = s;
                    if (ok(s, 1)) {
                        add(s, 1, Kind.BRAKE);
                        placed = true;
                    }
                }
                if (!placed) {
                    blue.remove(blue.size() - 1);
                }
            }
        }

        /** Staircases: 2-3 lips down one long straight (2-block where the tier allows). */
        void staircases() {
            int want = r.nextInt(tier.stairsMin, tier.stairsMax);
            if (want == 0) {
                return;
            }
            int d = bigsWanted - bigsNow() >= 2 ? 2 : 1;
            List<Run> cands = new ArrayList<>();
            for (Run run : runs) {
                if (run.length() >= 100 && run.s0() > sk.start + 30 && !blueOver(run) && run.s1() < sk.finish - 60) {
                    cands.add(run);
                }
            }
            shuffle(cands);
            cands.sort(Comparator.comparingDouble(run -> -run.length()));
            int made = 0;
            for (Run run : cands) {
                if (made >= want) {
                    break;
                }
                int steps = run.length() >= 175 && r.chance(0.5) ? 3 : 2;
                if (stair(run, steps, d) || (steps == 3 && stair(run, 2, d)) || (d == 2 && stair(run, 2, 1))) {
                    made++;
                }
            }
        }

        boolean stair(Run run, int steps, int d) {
            // 2-block steps stand far enough apart for two checkpoints between them (chains' walk)
            double mid = (run.s0() + run.s1()) / 2;
            double gap = d >= 2 ? Math.max(BoatEnvelope.zone(d) + LIP_GAP + 1, BoatEnvelope.zone(d) + 1.5 + CLEAR
                    + ROUNDING + spotAt(mid) + 2 * before(mid, d) + 1.25) : BoatEnvelope.zone(d) + LIP_GAP + 1;
            double first = run.s0() + runUp(run.s0() + 1, d, before(run.s0() + 1, d)) + 2;
            double need = first + gap * (steps - 1) + MountainTier.landing(d, false);
            if (need > run.s1()) {
                return false;
            }
            double slack = run.s1() - need;
            double s = first + r.nextDouble(0, Math.min(slack, 30));
            List<Double> placed = new ArrayList<>();
            for (int i = 0; i < steps; i++) {
                double at = Math.round((s + i * gap) * 2) / 2.0;
                boolean done = false;
                for (double t = 0; t <= 4 && !done; t += 0.5) {
                    if (ok(at + t, d)) {
                        add(at + t, d, Kind.STAIR);
                        placed.add(at + t);
                        done = true;
                    }
                }
                if (!done) {
                    for (double p : placed) {
                        for (int k = 0; k < lips.size(); k++) {
                            if (lips.get(k).s() == p) {
                                remove(k);
                                break;
                            }
                        }
                    }
                    return false;
                }
            }
            return true;
        }

        boolean blueOver(Run run) {
            for (double[] b : blue) {
                if (b[1] >= run.s0() && b[0] <= run.s1()) {
                    return true;
                }
            }
            return false;
        }

        int bigsNow() {
            int n = 0;
            for (Drop d : lips) {
                n += d.drop() >= 2 ? 1 : 0;
            }
            return n;
        }

        /**
         * Brake drops: ride the line, find the arc entered hardest over its v_c with no lip in the 40
         * before it, and lay a lip in that 40; again, until none is left that a lip can fix or the count is met.
         */
        void brakes() {
            List<Double> tried = new ArrayList<>();
            for (int guard = 0; guard < tier.dropsMax * 2 && lips.size() < target; guard++) {
                RideLine line = RideLine.of(sk, lips, fastNow());
                BoatLine.Result res = BoatLine.run(line.segs());
                double bestValue = 0;
                RideLine.Part best = null;
                for (int i = 0; i < line.parts.size(); i++) {
                    RideLine.Part p = line.parts.get(i);
                    if (!p.arc() || p.gate()) {
                        continue;
                    }
                    double value = (res.entry()[i] - res.corner()[i]) * BoatLine.TICKS;
                    if (value <= 0.05 * res.corner()[i] * BoatLine.TICKS || tried.contains(p.c0())) {
                        continue;
                    }
                    boolean covered = false;
                    for (Drop d : lips) {
                        covered |= d.s() <= p.c0() && d.s() >= p.c0() - BRAKE_WINDOW;
                    }
                    if (covered) {
                        continue;
                    }
                    // only the first arc of a run of arcs can have a lip before it
                    Centreline.Element before = sk.line.elementAt(p.c0() - 0.5);
                    if (before.arc()) {
                        continue;
                    }
                    if (value > bestValue) {
                        bestValue = value;
                        best = p;
                    }
                }
                if (best == null) {
                    return;
                }
                tried.add(best.c0());
                double a = best.c0() - BRAKE_WINDOW;
                boolean blueBefore = inBlue(best.c0() - 30);
                if (bigsNow() < bigsWanted && placeIn(a, best.c0() - MountainTier.landing(2, blueBefore), 2,
                        Kind.BRAKE)) {
                    continue;
                }
                placeIn(a, best.c0() - MountainTier.landing(1, blueBefore), 1, Kind.BRAKE);
            }
        }

        List<double[]> fastNow() {
            List<double[]> out = new ArrayList<>();
            for (double[] b : blue) {
                out.add(new double[]{b[0], b[1], BoatLine.BLUE});
            }
            return out;
        }

        /** No more than {@link #spreadFor} the tier along the line (links not counted) without a lip. */
        void spread() {
            double most = spreadFor(sk.tier);
            for (int guard = 0; guard < 40; guard++) {
                double prev = sk.start;
                double[] gap = null;
                for (int i = 0; i <= lips.size(); i++) {
                    double next = i < lips.size() ? lips.get(i).s() : sk.finish;
                    if (unlinked(prev, next) > most) {
                        gap = new double[]{prev, next};
                        break;
                    }
                    prev = next;
                }
                if (gap == null) {
                    return;
                }
                double mid = (gap[0] + gap[1]) / 2;
                if (!nearest(mid, gap[0], gap[1], Kind.SPREAD)) {
                    return;
                }
            }
        }

        /** Length along [a, b] outside the links. */
        double unlinked(double a, double b) {
            double len = 0;
            for (Centreline.Element e : sk.line.elements()) {
                if (sk.tag(e).role().link()) {
                    continue;
                }
                double lo = Math.max(a, e.s0);
                double hi = Math.min(b, e.s1());
                if (hi > lo) {
                    len += hi - lo;
                }
            }
            return len;
        }

        /** A lip as near {@code mid} as fits within (a, b). */
        boolean nearest(double mid, double a, double b, Kind kind) {
            double m = Math.round(mid * 2) / 2.0;
            for (double t = 0; t <= (b - a) / 2; t += 0.5) {
                for (double s : new double[]{m - t, m + t}) {
                    if (s > a && s < b && ok(s, 1)) {
                        add(s, 1, kind);
                        return true;
                    }
                }
            }
            return false;
        }

        /**
         * Up to the drawn count: every place left, packed earliest-first along each straight from a seeded
         * offset (the most lips the straights hold), then the weakest of those (least brake value ahead,
         * least room round them, the most crowded straight) taken out again down to the count.
         */
        void fill() {
            if (lips.size() >= target) {
                return;
            }
            List<Double> added = new ArrayList<>();
            for (double[] c : packing()) {
                add(c[0], (int) c[1], Kind.FILL);
                added.add(c[0]);
            }
            while (!added.isEmpty() && (lips.size() > tier.dropsMax
                    || (lips.size() > target && descentNow() - 1 >= Math.max(descentWanted, tier.descentMin)))) {
                RideLine line = RideLine.of(sk, lips, fastNow());
                BoatLine.Result res = BoatLine.run(line.segs());
                double worst = Double.MAX_VALUE;
                int at = -1;
                for (int k = 0; k < added.size(); k++) {
                    double s = added.get(k);
                    Run run = runAt(s);
                    double score = roomExcept(s) / 200 + brakeAhead(line, res, s) / 10 + r.nextDouble() * 0.3
                            - 0.6 * (run == null ? 0 : onRun(run));
                    if (score < worst) {
                        worst = score;
                        at = k;
                    }
                }
                double s = added.remove(at);
                for (int k = 0; k < lips.size(); k++) {
                    if (lips.get(k).s() == s) {
                        remove(k);
                        break;
                    }
                }
            }
        }

        /**
         * Whether a lip at {@code s} would brake for what follows (F-F's test, on the tier's numbers): a link
         * or an arc of v_c under {@value #BRAKE_VC} b/s within its landing strip and 60 more. Algo 4's fill
         * filter only: the wide road fills any straight (D4), and {@link #share} still asks that 80% brake.
         */
        boolean brakesFor(double s) {
            double horizon = s + MountainTier.landing(1, false) + FlowScore.BRAKE_AFTER;
            for (Skeleton.GateSet g : sk.gates) {
                if (g.from() - g.spacing() > s && g.from() - g.spacing() <= horizon) {
                    return true; // the gate line's first swing
                }
            }
            for (double u = s; u <= horizon; u += 2) {
                Centreline.Element e = sk.line.elementAt(u);
                if (sk.tag(e).role().link()) {
                    return true;
                }
                if (e.arc() && BoatLine.cornerBps(e.radius, BoatLine.PACKED) < BRAKE_VC) {
                    return true;
                }
            }
            return false;
        }

        /**
         * The lips the straights hold besides those placed: a weighted interval schedule over every place a
         * lip may go (a block apart on any straight; algo 4: brake-worthy or on a long straight; each fitting
         * the lips already placed),
         * each consecutive pair the spacing and zone rules apart, most fall first: a 2-block drop is worth
         * 1 + the tier's 2-share (it takes more room and only so many may be 2s). {s, d} in order.
         */
        List<double[]> packing() {
            List<double[]> cand = new ArrayList<>(); // s, d, zone end, value
            // where the descent floor asks for more than a block a lip, a 2-block drop is worth nearly two
            double value2 = tier.descentMin > tier.dropsMin ? 1.95
                    : 1 + Math.min(1, tier.bigShare > 0 ? tier.bigShare : tier.bigMost / (double) target);
            int offset = r.nextInt(2);
            for (Run run : runs) {
                boolean longRun = run.length() >= LONG_RUN;
                for (double s = Math.ceil(run.s0()) + offset * 0.5; s <= run.s1(); s += 1) {
                    if (!wide && !longRun && !brakesFor(s)) {
                        continue;
                    }
                    for (int d = 1; d <= 2; d++) {
                        if ((d == 1 || tier.bigFor(tier.dropsMax) > 0) && ok(s, d)) {
                            cand.add(new double[]{s, d, zoneEnd(s, d), d == 2 ? value2 : 1});
                        }
                    }
                }
            }
            int n = cand.size();
            double[] best = new double[n];
            int[] from = new int[n];
            double top = 0;
            int at = -1;
            for (int i = 0; i < n; i++) {
                double[] c = cand.get(i);
                double ci = before(c[0], (int) c[1]);
                best[i] = c[3];
                from[i] = -1;
                for (int j = i - 1; j >= 0; j--) {
                    double[] q = cand.get(j);
                    if (c[0] - q[0] < BoatEnvelope.zone((int) q[1]) + LIP_GAP || c[0] < q[2] + CLEAR + ci + 1
                            || !handOn(q[0], before(q[0], (int) q[1]), q[2], (int) q[1], c[0], ci)) {
                        continue;
                    }
                    if (best[j] + c[3] > best[i] + 1e-9) {
                        best[i] = best[j] + c[3];
                        from[i] = j;
                    }
                    if (c[0] - q[0] > 400) {
                        break; // farther back, a nearer compatible place has done at least as well
                    }
                }
                if (best[i] > top + 1e-9) {
                    top = best[i];
                    at = i;
                }
            }
            List<double[]> out = new ArrayList<>();
            for (int i = at; i >= 0; i = from[i]) {
                out.add(0, new double[]{cand.get(i)[0], cand.get(i)[1]});
            }
            return out;
        }

        /**
         * Whether the checkpoints between a lip of {@code qd} at {@code qs} (its checkpoint {@code qc} before it,
         * its zone ending at {@code qEnd}) and the next at {@code s} ({@code c} before it) leave the last one
         * before {@code s} as far on as it can be ({@link #chains}' walk, kept true pair by pair): two fit
         * between them, or the furthest is within a leg of the one before {@code qs}, that one grown.
         */
        boolean handOn(double qs, double qc, double qEnd, int qd, double s, double c) {
            double a = qEnd + CLEAR + ROUNDING;
            double b = s - c;
            return a + spotAt(a) + c + 0.75 <= b || b <= qs - qc + leg(qd) - grow();
        }

        /** A checkpoint's radius {@code s} along on the lane's own width (no neck). */
        double spotAt(double s) {
            return MountainTier.spot(sk.width(s)) + (axis(s) ? 0 : RasterV4.ARC_SPOT);
        }

        /** How far {@code s} is from its nearest other lip (capped). */
        double roomExcept(double s) {
            double best = 400;
            for (Drop d : lips) {
                if (d.s() != s) {
                    best = Math.min(best, Math.abs(d.s() - s));
                }
            }
            return best;
        }

        /** How many lips a straight run holds already. */
        int onRun(Run run) {
            int n = 0;
            for (Drop d : lips) {
                n += d.s() >= run.s0() && d.s() <= run.s1() ? 1 : 0;
            }
            return n;
        }

        /** How far {@code s} is from its nearest lip (capped). */
        double room(double s) {
            double best = 400;
            for (Drop d : lips) {
                best = Math.min(best, Math.abs(d.s() - s));
            }
            return best;
        }

        /** The brake value (b/s over v_c) of the next arc within 60 after {@code s}, 0 for none. */
        double brakeAhead(RideLine line, BoatLine.Result res, double s) {
            double ls = line.toLine(s);
            for (int i = 0; i < line.parts.size(); i++) {
                RideLine.Part p = line.parts.get(i);
                if (p.s0() < ls) {
                    continue;
                }
                if (p.s0() > ls + 60) {
                    break;
                }
                if (p.arc() && !p.gate()) {
                    return Math.max(0, res.entry()[i] - res.corner()[i]) * BoatLine.TICKS;
                }
            }
            return 0;
        }

        /** 2-block drops toward a seeded descent inside the tier's range, within its 2-count. */
        void upgrade() {
            int n = lips.size();
            int bigs = Math.max(0, Math.min(tier.bigFor(n), Math.max(descentWanted, tier.descentMin) - n));
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                order.add(i);
            }
            shuffleInts(order);
            order.sort(Comparator.comparingInt(i -> rank(lips.get(i).kind())));
            for (int i : order) {
                if (bigsNow() >= bigs) {
                    break;
                }
                Drop d = lips.get(i);
                if (d.drop() >= 2) {
                    continue;
                }
                remove(i);
                // in place, or shifted a little along its straight
                boolean done = false;
                for (double t = 0; t <= UPGRADE_SHIFT && !done; t += 0.5) {
                    for (double at : t == 0 ? new double[]{d.s()} : new double[]{d.s() - t, d.s() + t}) {
                        if (!done && ok(at, 2)) {
                            add(at, 2, d.kind());
                            done = true;
                        }
                    }
                }
                if (!done) {
                    add(d.s(), d.drop(), d.kind());
                }
            }
        }

        int descentNow() {
            int d = 0;
            for (Drop x : lips) {
                d += x.drop();
            }
            return d;
        }

        /** Back to 1-block drops, the plainest first, until the 2s are within the tier's share of the count. */
        void downgrade() {
            List<Double> stuck = new ArrayList<>();
            for (int guard = 0; guard < 2 * lips.size() && bigsNow() > tier.bigFor(lips.size()); guard++) {
                int pick = -1;
                for (int i = 0; i < lips.size(); i++) {
                    Drop d = lips.get(i);
                    if (d.drop() >= 2 && d.kind() != Kind.FINAL && !stuck.contains(d.s())
                            && (pick < 0 || rank(d.kind()) > rank(lips.get(pick).kind()))) {
                        pick = i;
                    }
                }
                if (pick < 0) {
                    return;
                }
                Drop d = lips.get(pick);
                remove(pick);
                // a 1-block drop keeps every rule there too (algo 4: out of its neck its lane is wider, a longer
                // run-up)
                if (ok(d.s(), 1)) {
                    add(d.s(), 1, d.kind());
                } else {
                    add(d.s(), d.drop(), d.kind());
                    stuck.add(d.s());
                }
            }
        }

        /**
         * F-F's brake share: while fewer than 80% of the lips brake (or stand on a long straight), drop the
         * plainest lip that doesn't, as long as the count and the descent stay in the tier's range.
         */
        void share() {
            for (int guard = 0; guard < lips.size(); guard++) {
                boolean[] brake = FlowScore.braking(sk, new DropPlan(sk, lips, blue));
                int n = brake.length;
                int braking = 0;
                for (boolean b : brake) {
                    braking += b ? 1 : 0;
                }
                if (n != lips.size() || braking >= 0.8 * n - 1e-9 || n - 1 < tier.dropsMin - SHORT_BY) {
                    return;
                }
                int pick = -1;
                for (int i = 0; i < n; i++) {
                    Drop d = lips.get(i);
                    if (brake[i] || d.kind() == Kind.FINAL || descentNow() - d.drop() < tier.descentMin) {
                        continue;
                    }
                    if (pick < 0 || d.drop() < lips.get(pick).drop()
                            || (d.drop() == lips.get(pick).drop() && rank(d.kind()) > rank(lips.get(pick).kind()))) {
                        pick = i;
                    }
                }
                if (pick < 0) {
                    return;
                }
                remove(pick);
            }
        }

        static int rank(Kind k) {
            return switch (k) {
                case FINAL -> 0;
                case STAIR -> 1;
                case BRAKE -> 2;
                case SPREAD -> 3;
                case FILL -> 4;
            };
        }

        <T> void shuffle(List<T> list) {
            for (int i = list.size() - 1; i > 0; i--) {
                int j = r.nextInt(i + 1);
                T t = list.get(i);
                list.set(i, list.get(j));
                list.set(j, t);
            }
        }

        void shuffleInts(List<Integer> list) {
            shuffle(list);
        }
    }
}
