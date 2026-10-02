package com.dierks.homecraft.games.gen.boat;

import com.dierks.homecraft.games.gen.api.GenRandom;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * A Mountain Run v2's pieces (MOUNTAIN-V2-SPEC §7.1): what makes a run more than ice and drops, placed
 * on the {@link Skeleton}'s straights (of any heading) where they fit, each kept only while the
 * checkpoints can still be laid round it.
 *
 * <ul>
 *   <li><b>Sand run-offs</b> (roads): on every bend tighter than R {@value #SANDY_R}, two columns of sand
 *       outside the lane on its outer side, so going wide is slow and the full lane of ice is still
 *       there (V5b).</li>
 *   <li><b>The sand pit</b>: the lane widens by {@value #WIDEN} each side over {@value #TAPER} blocks, a
 *       pit of sand sits in the middle, ice ways round both sides at least P wide.</li>
 *   <li><b>Pick a path</b>: the lane splits round a moss island 3 wide with a tree; both branches wider
 *       than P (the islands rule).</li>
 *   <li><b>The Ice Cave</b>: 12-20 blocks under a light-blue glass roof exactly 5 over the ice, sea
 *       lanterns in its walls.</li>
 *   <li><b>The tunnel</b> (medium, hard): 20-40 blocks through a rock spur, a stone roof 5 over the ice
 *       (V7: rock over the track 5 up or more), lanterns every {@value #LANTERN_EVERY}.</li>
 *   <li><b>The forest slalom</b>: the lane widens and 2 x 2 trunks stand in it, alternately left and
 *       right of the middle, every gap wider than P, under leaves 5 and 6 over the ice.</li>
 *   <li><b>Boost strips</b> (medium): the middle 3 columns blue for 15-30 blocks, never within 30
 *       before a lip.</li>
 * </ul>
 * Placement (§7.1): never in the first 40 after the start, on a run-up or landing strip, in a flight
 * zone, or in the final 120 before the finish; 4 apart. Lateral offsets v are right of the line
 * positive. Pure.
 */
public final class PiecesV4 {

    /** Bends tighter than this get sand run-offs (roads). */
    public static final double SANDY_R = 40;
    /** Run-off sand, columns outside the lane. */
    static final int RUNOFF = 2;
    /** A widened piece's extra lane each side, and its taper length. */
    static final double WIDEN = 3;
    static final double TAPER = 8;
    /** A piece keeps this far from the next. */
    static final double APART = 4;
    /** No piece in the first this after the start, nor in the last {@value #FINISH_CLEAR} before the finish. */
    static final double START_CLEAR = 40;
    static final double FINISH_CLEAR = 120;
    /** Lanterns in a tunnel's walls this often. */
    static final int LANTERN_EVERY = 6;
    /** Places tried for a piece. */
    static final int ATTEMPTS = 8;

    /** The kinds, in the order the deck is laid. */
    public enum Kind {
        TUNNEL("tunnel"), CAVE("cave"), FOREST("forest"), SPLIT("split"), SAND_PIT("sand pit"), BOOST("boost");

        public final String word;

        Kind(String word) {
            this.word = word;
        }

        boolean widens() {
            return this == SAND_PIT || this == SPLIT || this == FOREST;
        }
    }

    /** One placed piece: [s1, s2] along the centreline, its features across (v). */
    public static final class Piece {
        public final Kind kind;
        public final double s1;
        public final double s2;
        /** The main feature along, [a1, a2], and across, [lo, hi] (a pit's sand, an island). */
        double a1;
        double a2;
        double lo;
        double hi;
        /** A forest's trunks: {s of the trunk's first row, v of its left column}. */
        final List<double[]> trunks = new ArrayList<>();

        Piece(Kind kind, double s1, double s2) {
            this.kind = kind;
            this.s1 = s1;
            this.s2 = s2;
        }

        /** The lane's extra width each side {@code s} along. */
        double extra(double s) {
            if (!kind.widens() || s <= s1 || s >= s2) {
                return 0;
            }
            double ramp = Math.min(1, Math.min((s - s1) / TAPER, (s2 - s) / TAPER));
            return WIDEN * ramp;
        }
    }

    public final List<Piece> list;
    /** Per element: run-off sand columns outside the bend (0 for none). */
    final int[] runoff;

    PiecesV4(List<Piece> list, int[] runoff) {
        this.list = List.copyOf(list);
        this.runoff = runoff;
    }

    /** No pieces, but the bends' run-offs (the safe layouts). */
    static PiecesV4 none(Skeleton sk) {
        return new PiecesV4(List.of(), runoffs(sk));
    }

    public int count(Kind k) {
        int n = 0;
        for (Piece p : list) {
            n += p.kind == k ? 1 : 0;
        }
        return n;
    }

    /** How many bends have run-offs. */
    public int runoffs() {
        int n = 0;
        for (int r : runoff) {
            n += r > 0 ? 1 : 0;
        }
        return n;
    }

    // ---- the lane, as the raster asks -------------------------------------------------------------------

    /** The piece over {@code s}, or {@code null}. */
    Piece at(double s) {
        for (Piece p : list) {
            if (s >= p.s1 && s <= p.s2) {
                return p;
            }
        }
        return null;
    }

    /** The lane's extra width {@code s} along (both sides alike). */
    double extra(double s) {
        Piece p = at(s);
        return p == null ? 0 : p.extra(s);
    }

    /** Whether (s, v) is a split's island or a forest's trunk: no lane there. */
    boolean obstacle(double s, double v) {
        Piece p = at(s);
        if (p == null) {
            return false;
        }
        if (p.kind == Kind.SPLIT) {
            return s >= p.a1 && s <= p.a2 && v >= p.lo && v <= p.hi;
        }
        if (p.kind == Kind.FOREST) {
            for (double[] t : p.trunks) {
                if (s >= t[0] && s < t[0] + 2 && v >= t[1] && v < t[1] + 2) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether (s, v) is a sand pit's sand. */
    boolean sandPit(double s, double v) {
        Piece p = at(s);
        return p != null && p.kind == Kind.SAND_PIT && s >= p.a1 && s <= p.a2 && v >= p.lo && v <= p.hi;
    }

    /** Whether (s, v) is a boost strip's blue ice. */
    boolean boost(double s, double v) {
        Piece p = at(s);
        return p != null && p.kind == Kind.BOOST && Math.abs(v) <= 1.5;
    }

    /** Where checkpoints can't go: each piece and a margin either side, {from, to} along. */
    List<double[]> blocked() {
        List<double[]> out = new ArrayList<>();
        for (Piece p : list) {
            if (p.kind != Kind.BOOST) {
                out.add(new double[]{p.s1 - 1, p.s2 + 1});
            }
        }
        return out;
    }

    /** Blue ice on the line model: the boost strips, {from, to, f} along the centreline. */
    List<double[]> fast() {
        List<double[]> out = new ArrayList<>();
        for (Piece p : list) {
            if (p.kind == Kind.BOOST) {
                out.add(new double[]{p.s1, p.s2, BoatLine.BLUE});
            }
        }
        return out;
    }

    // ---- placing them -------------------------------------------------------------------------------------

    /** The bends' run-offs: roads only, every arc tighter than {@value #SANDY_R}. */
    static int[] runoffs(Skeleton sk) {
        int[] out = new int[sk.line.elements().size()];
        if (!sk.tier.road()) {
            return out;
        }
        for (Centreline.Element e : sk.line.elements()) {
            if (e.arc() && e.radius < SANDY_R) {
                out[e.index] = RUNOFF;
            }
        }
        return out;
    }

    /**
     * The tier's pieces for {@code sk} and {@code drops} from stream {@code r}, at {@code richness} (0 full,
     * 1 reduced: no forest, one split, one pit, 2 basic: none), each kept only while {@code feasible}
     * accepts the checkpoint-free stretches with it.
     */
    static PiecesV4 draw(GenRandom r, Skeleton sk, DropPlan drops, int richness, Predicate<List<double[]>> feasible) {
        MountainTier tier = sk.tier;
        int[] runoff = runoffs(sk);
        List<Piece> placed = new ArrayList<>();
        if (richness >= 2 || tier.slalom()) {
            return new PiecesV4(placed, runoff);
        }
        List<Kind> deck = new ArrayList<>();
        add(deck, Kind.TUNNEL, r, tier.tunnels, richness);
        add(deck, Kind.CAVE, r, tier.caves, richness);
        if (richness == 0) {
            add(deck, Kind.FOREST, r, tier.forests, richness);
        }
        add(deck, Kind.SPLIT, r, tier.splits, richness);
        add(deck, Kind.SAND_PIT, r, tier.pits, richness);
        int boosts = tier.boostMax > 0 ? r.nextInt(tier.boostMin, tier.boostMax) : 0;
        for (int i = 0; i < boosts; i++) {
            deck.add(Kind.BOOST);
        }
        List<double[]> free = freeStretches(sk, drops);
        for (Kind k : deck) {
            for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
                Piece p = place(r, sk, drops, k, free, placed);
                if (p == null) {
                    break;
                }
                List<Piece> trial = new ArrayList<>(placed);
                trial.add(p);
                PiecesV4 with = new PiecesV4(trial, runoff);
                if (feasible.test(with.blocked())) {
                    placed.add(p);
                    break;
                }
            }
        }
        placed.sort((a, b) -> Double.compare(a.s1, b.s1));
        return new PiecesV4(placed, runoff);
    }

    /** {@code kind} {least..most} times (reduced: at most one of each). */
    private static void add(List<Kind> deck, Kind kind, GenRandom r, int[] range, int richness) {
        int n = range[1] <= 0 ? 0 : r.nextInt(range[0], range[1]);
        if (richness == 1) {
            n = Math.min(n, 1);
        }
        for (int i = 0; i < n; i++) {
            deck.add(kind);
        }
    }

    /**
     * The stretches a piece may use: every straight run, less the first {@value #START_CLEAR} after the
     * start, the last {@value #FINISH_CLEAR} before the finish, and every lip's run-up, landing strip and
     * flight zone.
     */
    static List<double[]> freeStretches(Skeleton sk, DropPlan drops) {
        List<double[]> out = new ArrayList<>();
        DropPlan.Planner probe = new DropPlan.Planner(new GenRandom(0), sk);
        for (DropPlan.Run run : probe.runs) {
            out.add(new double[]{Math.max(run.s0(), sk.start + START_CLEAR), Math.min(run.s1(),
                    sk.finish - FINISH_CLEAR)});
        }
        for (DropPlan.Drop d : drops.drops) {
            double w = drops.width(d.s());
            double from = d.s() - MountainTier.runUp(w) - 2;
            double to = Math.max(d.s() + MountainTier.landing(d.drop(), drops.blueAt(d.s() - 1)) + 2,
                    probe.zoneEnd(d.s(), d.drop()) + 3);
            List<double[]> next = new ArrayList<>();
            for (double[] f : out) {
                if (to <= f[0] || from >= f[1]) {
                    next.add(f);
                    continue;
                }
                if (from > f[0]) {
                    next.add(new double[]{f[0], from});
                }
                if (to < f[1]) {
                    next.add(new double[]{to, f[1]});
                }
            }
            out = next;
        }
        out.removeIf(f -> f[1] - f[0] < 12);
        return out;
    }

    /** A place for a piece of {@code k} in a free stretch away from the others, or {@code null}. */
    static Piece place(GenRandom r, Skeleton sk, DropPlan drops, Kind k, List<double[]> free, List<Piece> placed) {
        double len = switch (k) {
            case SAND_PIT -> 2 * TAPER + r.nextInt(10, 14);
            case SPLIT -> 10 + r.nextInt(8, 16);
            case CAVE -> r.nextInt(12, 20);
            case TUNNEL -> r.nextInt(20, 40);
            case FOREST -> 2 * TAPER + 6 * (sk.tier.hard() ? 4 : 3) - 2;
            case BOOST -> r.nextInt(15, 30);
        };
        List<double[]> fits = new ArrayList<>();
        for (double[] f : free) {
            if (f[1] - f[0] >= len + 2) {
                fits.add(f);
            }
        }
        for (int attempt = 0; attempt < ATTEMPTS && !fits.isEmpty(); attempt++) {
            double[] f = fits.get(r.nextInt(fits.size()));
            double s1 = Math.floor(f[0] + 1 + r.nextDouble() * (f[1] - f[0] - len - 2));
            double s2 = s1 + len;
            boolean clear = true;
            for (Piece q : placed) {
                clear &= s2 + APART <= q.s1 || s1 >= q.s2 + APART;
            }
            if (k == Kind.BOOST) {
                for (DropPlan.Drop d : drops.drops) {
                    clear &= !(s2 > d.s() - 30 && s1 < d.s());
                }
            }
            if (k.widens() && !roomToWiden(sk, s1, s2, drops)) {
                clear = false;
            }
            if (clear) {
                return make(r, sk, k, s1, s2);
            }
        }
        return null;
    }

    /**
     * Whether widening [s1, s2] by {@value #WIDEN} keeps 12 columns of terrain to every other part of the
     * corridor (§4.2's clearance), sampled every 2 blocks.
     */
    static boolean roomToWiden(Skeleton sk, double s1, double s2, DropPlan drops) {
        double reach = sk.tier.width / 2.0 + WIDEN + 1;
        for (double s = s1; s <= s2; s += 2) {
            double[] p = sk.line.at(s);
            for (double u = sk.start - Frame.START; u <= sk.end; u += 2) {
                if (Math.abs(u - s) < 60) {
                    continue;
                }
                double[] q = sk.line.at(u);
                double gap = Math.hypot(p[0] - q[0], p[1] - q[1]) - reach - (drops.width(u) / 2.0 + 1);
                if (gap < 12) {
                    return false;
                }
            }
        }
        return true;
    }

    /** A piece of kind {@code k} at [s1, s2] with its features. */
    static Piece make(GenRandom r, Skeleton sk, Kind k, double s1, double s2) {
        Piece p = new Piece(k, s1, s2);
        double w = sk.tier.width;
        double half = w / 2.0 + WIDEN;
        switch (k) {
            case SAND_PIT -> {
                p.a1 = s1 + TAPER + 1;
                p.a2 = s2 - TAPER - 1;
                double sand = sk.tier.hard() ? 1.5 : 2;
                double c = r.nextDouble(-0.5, 0.5);
                p.lo = c - sand;
                p.hi = c + sand;
            }
            case SPLIT -> {
                p.a1 = s1 + 5;
                p.a2 = s2 - 5;
                p.lo = -1.5;
                p.hi = 1.5;
            }
            case FOREST -> {
                // trunks alternately left and right of the middle; each gap past them wider than P
                double at = s1 + TAPER;
                boolean left = r.nextBoolean();
                int n = sk.tier.hard() ? 4 : 3;
                for (int i = 0; i < n; i++) {
                    double v = left ? -2.5 : 0.5;
                    p.trunks.add(new double[]{Math.floor(at), v});
                    at += 6;
                    left = !left;
                }
                p.a1 = s1 + TAPER;
                p.a2 = at;
                p.lo = -half;
                p.hi = half;
            }
            default -> {
                p.a1 = s1;
                p.a2 = s2;
            }
        }
        return p;
    }
}
