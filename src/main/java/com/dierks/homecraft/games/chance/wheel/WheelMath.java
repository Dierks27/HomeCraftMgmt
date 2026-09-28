package com.dierks.homecraft.games.chance.wheel;

import com.dierks.homecraft.games.RtpLimits;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;
import java.util.TreeMap;

/**
 * The Wheel's odds, worked out exactly for one stake (spec §5.5, R1.7, R1.1).
 *
 * <p>A space's base says what kind of prize it is: 0 pays nothing, 1 gives the tokens put in back,
 * and a base above 1 is a win worth {@code max(in + 1, floor(in · base · k))}, capped at
 * {@code max_payout}. The one free parameter is the scale {@code k}: as it grows, each base's
 * prize steps up one token at a time, at the points {@code k = j / (in · base)}. Between two such
 * points nothing changes, so the only returns the wheel can reach are the ones at those
 * breakpoints. This walks them in order — every base's next step in one queue, steps that land on
 * the same {@code k} taken together — adding up the ring exactly in whole tokens, and stops just
 * past the top of the band (the prizes only ever grow, so nothing further could be picked). The
 * R1.1 rule then picks from that list with exact fractions, and the prizes are read off at the
 * chosen point.
 *
 * <p>Bases are read from their decimal text (2.5 is exactly 5/2), so no rounding ever moves a
 * breakpoint. Pure: no Bukkit, no config, no randomness.
 */
final class WheelMath {

    private WheelMath() {
    }

    /**
     * What the solve found for one stake.
     *
     * @param odds        the picked prizes, or {@code null} when nothing reachable is inside the band
     * @param aboveTarget nothing at or under the target was inside the band, so a higher value was
     *                    taken (worth one INFO line)
     * @param reachable   every return the wheel can reach at this stake, in order (for the WARN
     *                    that names the nearest ones when the stake is dropped)
     */
    record Solve(WheelOdds odds, boolean aboveTarget, double[] reachable) {
    }

    /** A base as an exact fraction {@code p / q} (from its decimal text). */
    record Base(long p, long q) {

        static Base of(double value) {
            BigDecimal d = BigDecimal.valueOf(value).stripTrailingZeros();
            BigInteger p = d.unscaledValue();
            BigInteger q = BigInteger.ONE;
            if (d.scale() > 0) {
                q = BigInteger.TEN.pow(d.scale());
            } else if (d.scale() < 0) {
                p = p.multiply(BigInteger.TEN.pow(-d.scale()));
            }
            BigInteger g = p.gcd(q);
            if (g.signum() > 0) {
                p = p.divide(g);
                q = q.divide(g);
            }
            return new Base(p.longValueExact(), q.longValueExact());
        }

        boolean zero() {
            return p == 0;
        }

        boolean one() {
            return p == q;
        }
    }

    /**
     * Solve one stake.
     *
     * @param stake    the tokens put in
     * @param segments the 24 bases in ring order (each 0 or at least 1)
     * @param cap      the most one spin may pay (never below the stake)
     * @param target   the clamped target return, as an exact fraction
     */
    static Solve solve(int stake, List<Double> segments, int cap, RtpLimits.Ratio target) {
        List<Base> ring = new ArrayList<>(segments.size());
        for (double v : segments) {
            ring.add(Base.of(v));
        }
        List<Long> totals = new ArrayList<>();
        Sweep sweep = new Sweep(stake, ring, cap);
        totals.add(sweep.total);
        while (sweep.below() && sweep.advance()) {
            totals.add(sweep.total);
        }
        long den = (long) WheelSettings.SPACES * stake;
        RtpLimits.Ratio[] ratios = new RtpLimits.Ratio[totals.size()];
        double[] reachable = new double[totals.size()];
        for (int i = 0; i < ratios.length; i++) {
            ratios[i] = new RtpLimits.Ratio(totals.get(i), den);
            reachable[i] = ratios[i].value();
        }
        RtpLimits.Pick pick = RtpLimits.pick(target, ratios);
        if (pick == null) {
            return new Solve(null, false, reachable);
        }
        Sweep chosen = new Sweep(stake, ring, cap);
        for (int i = 0; i < pick.index(); i++) {
            chosen.advance();
        }
        return new Solve(chosen.odds(), pick.aboveTarget(), reachable);
    }

    /**
     * The walk along {@code k}: the current prize of each distinct base above 1, the ring's total,
     * and a queue of each base's next step.
     */
    private static final class Sweep {

        private final int stake;
        private final int cap;
        private final List<Base> ring;
        /** The distinct bases above 1, and how many spaces carry each. */
        private final List<Base> bigs = new ArrayList<>();
        private final List<Integer> counts = new ArrayList<>();
        /** Each big base's prize right now. */
        private final int[] prize;
        private final PriorityQueue<Step> queue;
        private long total;
        /** The {@code k} of the last step taken, as {@code num / den}, or null before the first. */
        private BigInteger kNum;
        private BigInteger kDen;

        /** Base {@code index} reaches prize {@code j} at {@code k = j · q / (stake · p)}. */
        private record Step(int index, int j) {
        }

        Sweep(int stake, List<Base> ring, int cap) {
            this.stake = stake;
            this.cap = Math.max(stake, cap);
            this.ring = ring;
            // Smallest base first; bases are normalised fractions, so equal values are equal records.
            TreeMap<Base, Integer> byValue = new TreeMap<>((x, y) -> BigInteger.valueOf(x.p())
                    .multiply(BigInteger.valueOf(y.q())).compareTo(BigInteger.valueOf(y.p()).multiply(BigInteger.valueOf(x.q()))));
            int ones = 0;
            for (Base b : ring) {
                if (b.zero()) {
                    continue;
                }
                if (b.one()) {
                    ones++;
                    continue;
                }
                byValue.merge(b, 1, Integer::sum);
            }
            for (var e : byValue.entrySet()) {
                bigs.add(e.getKey());
                counts.add(e.getValue());
            }
            this.prize = new int[bigs.size()];
            int start = Math.min(this.cap, stake + 1);
            total = (long) ones * stake;
            for (int i = 0; i < prize.length; i++) {
                prize[i] = start;
                total += (long) counts.get(i) * start;
            }
            this.queue = new PriorityQueue<>(Math.max(1, bigs.size()), this::compare);
            for (int i = 0; i < prize.length; i++) {
                if (stake + 2 <= this.cap) {
                    queue.add(new Step(i, stake + 2));
                }
            }
        }

        /** Whether the ring's return is still at or under the top of the band (95 of 100). */
        boolean below() {
            // total / (24 · stake) <= 95 / 100
            return total * 100 <= 95L * WheelSettings.SPACES * stake;
        }

        /** Take the next step along {@code k} (every base that steps there); false when none is left. */
        boolean advance() {
            Step first = queue.poll();
            if (first == null) {
                return false;
            }
            List<Step> group = new ArrayList<>();
            group.add(first);
            while (!queue.isEmpty() && compare(queue.peek(), first) == 0) {
                group.add(queue.poll());
            }
            for (Step s : group) {
                total += (long) counts.get(s.index()) * (s.j() - prize[s.index()]);
                prize[s.index()] = s.j();
                if (s.j() + 1 <= cap) {
                    queue.add(new Step(s.index(), s.j() + 1));
                }
            }
            Base b = bigs.get(first.index());
            kNum = BigInteger.valueOf(first.j()).multiply(BigInteger.valueOf(b.q()));
            kDen = BigInteger.valueOf(stake).multiply(BigInteger.valueOf(b.p()));
            return true;
        }

        /** Order two steps by their {@code k}: j1·q1/(p1) against j2·q2/(p2), exactly. */
        private int compare(Step x, Step y) {
            Base a = bigs.get(x.index());
            Base b = bigs.get(y.index());
            BigInteger left = BigInteger.valueOf(x.j()).multiply(BigInteger.valueOf(a.q())).multiply(BigInteger.valueOf(b.p()));
            BigInteger right = BigInteger.valueOf(y.j()).multiply(BigInteger.valueOf(b.q())).multiply(BigInteger.valueOf(a.p()));
            return left.compareTo(right);
        }

        /** The prizes where the walk stands, in ring order. */
        WheelOdds odds() {
            List<Integer> out = new ArrayList<>(ring.size());
            for (Base b : ring) {
                if (b.zero()) {
                    out.add(0);
                } else if (b.one()) {
                    out.add(stake);
                } else {
                    out.add(prize[bigs.indexOf(b)]);
                }
            }
            return new WheelOdds(stake, Collections.unmodifiableList(out), total, k());
        }

        /**
         * The smallest scale that gives these prizes: the last step's {@code k}, or before any step
         * the point where the biggest base first pays one token more than was put in.
         */
        private double k() {
            if (bigs.isEmpty()) {
                return 0;
            }
            if (kNum != null) {
                return new BigDecimal(kNum).divide(new BigDecimal(kDen), MathContext.DECIMAL64).doubleValue();
            }
            Base top = bigs.get(bigs.size() - 1);
            return (double) (stake + 1) * top.q() / ((double) stake * top.p());
        }
    }
}
