package com.dierks.homecraft.games.gen.boat;

import com.dierks.homecraft.games.gen.api.GenRandom;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
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
 * Placement (§7.1, {@link Room}): never in the first 40 after the start, on a run-up or landing strip, in
 * a flight zone (a split or a forest may stand in a 1-block drop's zone after its landing strip), or in the
 * final 120 before the finish; 4 apart; splits on straights of 40 or more, the rest on straights and wide
 * bends. Lateral offsets v are right of the line positive. Pure.
 */
public final class PiecesV4 {

    /** Bends tighter than this get sand run-offs (roads). */
    public static final double SANDY_R = 40;
    /** Run-off sand, columns outside the lane. */
    static final int RUNOFF = 2;
    /** A widened piece's extra lane each side, and its taper length. */
    static final double WIDEN = 4;
    static final double TAPER = 8;
    /** A piece keeps this far from the next. */
    static final double APART = 4;
    /** No piece in the first this after the start, nor in the last {@value #FINISH_CLEAR} before the finish. */
    static final double START_CLEAR = 40;
    static final double FINISH_CLEAR = 120;
    /** Lanterns in a tunnel's walls this often. */
    static final int LANTERN_EVERY = 6;

    /** The kinds (the deck is dealt in {@link #DEAL}'s order). */
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
    /** How many of each kind the tier's deck dealt (none for the safe and basic layouts). */
    final Map<Kind, Integer> dealt;

    PiecesV4(List<Piece> list, int[] runoff) {
        this(list, runoff, Map.of());
    }

    PiecesV4(List<Piece> list, int[] runoff, Map<Kind, Integer> dealt) {
        this.list = List.copyOf(list);
        this.runoff = runoff;
        this.dealt = Map.copyOf(dealt);
    }

    /** How many of kind {@code k} the deck dealt. */
    public int dealt(Kind k) {
        return dealt.getOrDefault(k, 0);
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
     *
     * <p>The deck (§5.3, audit MTN-R3-00) is dealt one of each kind before any second copy, the scarcest and
     * longest first ({@link #DEAL}); each card takes the tightest stretch it fits ({@link Room}), at its drawn
     * length or else its shortest, and the room it takes, with {@value #APART} either side, is gone for the
     * cards after it.
     */
    static PiecesV4 draw(GenRandom r, Skeleton sk, DropPlan drops, int richness, Predicate<List<double[]>> feasible) {
        MountainTier tier = sk.tier;
        int[] runoff = runoffs(sk);
        List<Piece> placed = new ArrayList<>();
        if (richness >= 2 || tier.slalom()) {
            return new PiecesV4(placed, runoff);
        }
        Map<Kind, Integer> dealt = deal(r, tier, richness);
        Room room = new Room(sk, drops, runoff);
        List<double[]> refused = new ArrayList<>();
        int most = 0;
        for (int n : dealt.values()) {
            most = Math.max(most, n);
        }
        for (int copy = 0; copy < most; copy++) {
            for (Kind k : DEAL) {
                if (dealt.get(k) <= copy) {
                    continue;
                }
                Piece p = place(r, sk, drops, k, room, placed, runoff, feasible, refused);
                if (p != null) {
                    placed.add(p);
                    room.take(p.s1, p.s2);
                }
            }
        }
        placed.sort((a, b) -> Double.compare(a.s1, b.s1));
        return new PiecesV4(placed, runoff, dealt);
    }

    /**
     * The order the deck is dealt in, a round of each kind at a time: the scarcest room first (a split stands
     * only on a long straight), then the longest pieces, the short boost strips last.
     */
    static final List<Kind> DEAL = List.of(Kind.SPLIT, Kind.FOREST, Kind.SAND_PIT, Kind.TUNNEL, Kind.CAVE, Kind.BOOST);

    /**
     * How many of each kind the tier deals (reduced: at most one of each, no forest). Where neither a cave nor
     * a tunnel is needed alone but either may come (medium's "cave or tunnel 1-2"), at least one does.
     */
    static Map<Kind, Integer> deal(GenRandom r, MountainTier tier, int richness) {
        Map<Kind, Integer> out = new EnumMap<>(Kind.class);
        out.put(Kind.TUNNEL, count(r, tier.tunnels, richness));
        out.put(Kind.CAVE, count(r, tier.caves, richness));
        out.put(Kind.FOREST, richness == 0 ? count(r, tier.forests, richness) : 0);
        out.put(Kind.SPLIT, count(r, tier.splits, richness));
        out.put(Kind.SAND_PIT, count(r, tier.pits, richness));
        out.put(Kind.BOOST, tier.boostMax > 0 ? r.nextInt(tier.boostMin, tier.boostMax) : 0);
        boolean either = tier.caves[0] == 0 && tier.tunnels[0] == 0 && tier.caves[1] > 0 && tier.tunnels[1] > 0;
        if (either && out.get(Kind.CAVE) + out.get(Kind.TUNNEL) == 0) {
            out.put(r.nextBoolean() ? Kind.CAVE : Kind.TUNNEL, 1);
        }
        return out;
    }

    /** {least..most} of a kind (reduced: at most one). */
    private static int count(GenRandom r, int[] range, int richness) {
        int n = range[1] <= 0 ? 0 : r.nextInt(range[0], range[1]);
        return richness == 1 ? Math.min(n, 1) : n;
    }

    /** Pieces may stand on a bend at least this wide (all but splits; never a link's). */
    static final double ARC_R = 60;
    /** A pick-a-path split stands on a straight of at least this (§7.1). */
    static final double SPLIT_RUN = 40;
    /** Spots of one card the checkpoint chain is asked about before the card is given up. */
    static final int CHAIN_TRIES = 4;

    /**
     * Where each kind of piece may stand (§7.1), as stretches [from, to] along the centreline: never on the
     * first {@value #START_CLEAR} after the start or the last {@value #FINISH_CLEAR} before the finish, on a
     * lip's run-up or landing strip, or in a flight zone (a split or a forest may stand in a 1-block drop's
     * zone after its landing strip, as in v3). A split stands on a straight of {@value #SPLIT_RUN} or more;
     * the rest also on bends of R {@value #ARC_R} or more that no link or run-off sand is on, and those bends
     * first ({@link #bends}), so the straights stay for the splits. A placed piece takes its stretch and
     * {@value #APART} either side from every kind.
     */
    static final class Room {
        final Map<Kind, List<double[]>> free = new EnumMap<>(Kind.class);
        final Map<Kind, List<double[]>> bends = new EnumMap<>(Kind.class);

        Room(Skeleton sk, DropPlan drops, int[] runoff) {
            for (Kind k : Kind.values()) {
                free.put(k, stretches(sk, drops, runoff, k, false));
                bends.put(k, stretches(sk, drops, runoff, k, true));
            }
        }

        /** Where kind {@code k} may stand: on its bends alone ({@code bendsOnly}), or anywhere it may. */
        List<double[]> of(Kind k, boolean bendsOnly) {
            return bendsOnly ? bends.get(k) : free.get(k);
        }

        void take(double s1, double s2) {
            for (Kind k : Kind.values()) {
                free.put(k, cut(free.get(k), s1 - APART, s2 + APART));
                bends.put(k, cut(bends.get(k), s1 - APART, s2 + APART));
            }
        }
    }

    /** {@code stretches} less [from, to]. */
    static List<double[]> cut(List<double[]> stretches, double from, double to) {
        List<double[]> next = new ArrayList<>();
        for (double[] f : stretches) {
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
        return next;
    }

    /** Whether element {@code e} may carry a piece of kind {@code k}. */
    static boolean carries(Skeleton sk, Centreline.Element e, int[] runoff, Kind k) {
        if (!e.arc()) {
            return true;
        }
        if (k == Kind.SPLIT) {
            return false;
        }
        Skeleton.Role role = sk.tag(e).role();
        return e.radius >= ARC_R && runoff[e.index] == 0 && !role.link() && role != Skeleton.Role.CHICANE;
    }

    /** The stretches kind {@code k} may stand on ({@link Room}), on bends alone or anywhere, before any piece is placed. */
    static List<double[]> stretches(Skeleton sk, DropPlan drops, int[] runoff, Kind k, boolean bendsOnly) {
        List<double[]> out = new ArrayList<>();
        double[] open = null;
        for (Centreline.Element e : sk.line.elements()) {
            if (carries(sk, e, runoff, k) && (e.arc() || !bendsOnly)) {
                open = open == null ? new double[]{e.s0, e.s1()} : new double[]{open[0], e.s1()};
            } else {
                if (open != null) {
                    out.add(open);
                }
                open = null;
            }
        }
        if (open != null) {
            out.add(open);
        }
        if (k == Kind.SPLIT) {
            out.removeIf(f -> f[1] - f[0] < SPLIT_RUN);
        }
        out = cut(out, Double.NEGATIVE_INFINITY, sk.start + START_CLEAR);
        out = cut(out, sk.finish - FINISH_CLEAR, Double.POSITIVE_INFINITY);
        DropPlan.Planner probe = new DropPlan.Planner(new GenRandom(0), sk);
        boolean zoneOk = k == Kind.SPLIT || k == Kind.FOREST;
        for (DropPlan.Drop d : drops.drops) {
            double w = drops.width(d.s());
            double from = d.s() - MountainTier.runUp(w) - 2;
            double landed = d.s() + MountainTier.landing(d.drop(), drops.blueAt(d.s() - 1)) + 2;
            double to = zoneOk && d.drop() == 1 ? landed : Math.max(landed, probe.zoneEnd(d.s(), d.drop()) + 3);
            out = cut(out, from, to);
        }
        out.removeIf(f -> f[1] - f[0] < 12);
        return out;
    }

    /** How long a piece of {@code k} is, along. */
    static double length(GenRandom r, Skeleton sk, Kind k) {
        return switch (k) {
            case SAND_PIT -> 2 * TAPER + r.nextInt(10, 14);
            case SPLIT -> 2 * TAPER + 2 + r.nextInt(6, 12);
            case CAVE -> r.nextInt(12, 20);
            case TUNNEL -> r.nextInt(20, 40);
            case FOREST -> 2 * TAPER + 6 * (sk.tier.hard() ? 4 : 3) - 2;
            case BOOST -> r.nextInt(15, 30);
        };
    }

    /** A place for a card of {@code k} at its drawn length, else at its shortest ({@link #shortest}); or {@code null}. */
    static Piece place(GenRandom r, Skeleton sk, DropPlan drops, Kind k, Room room, List<Piece> placed, int[] runoff,
                       Predicate<List<double[]>> feasible, List<double[]> refused) {
        // at its drawn length; where that fits nowhere the checkpoints allow, at its kind's shortest
        double len = length(r, sk, k);
        Piece p = place(r, sk, drops, k, len, room, placed, runoff, feasible, refused);
        double least = shortest(sk, k);
        return p != null || least >= len ? p : place(r, sk, drops, k, least, room, placed, runoff, feasible, refused);
    }

    /** The shortest piece of {@code k} ({@link #length}'s least). */
    static double shortest(Skeleton sk, Kind k) {
        return switch (k) {
            case SAND_PIT -> 2 * TAPER + 10;
            case SPLIT -> 2 * TAPER + 2 + 6;
            case CAVE -> 12;
            case TUNNEL -> 20;
            case FOREST -> 2 * TAPER + 6 * (sk.tier.hard() ? 4 : 3) - 2;
            case BOOST -> 15;
        };
    }

    /**
     * A place for a piece of {@code k}, {@code len} long: the free stretches it fits, on bends first and then
     * anywhere it may stand, the tightest first (best fit); each stretch's start, then each one's end, then a
     * seeded spot in each, so the checkpoints are asked about different stretches before the same one twice.
     * The first that keeps its own rules (a boost strip never within 30 before a lip, a widening piece §4.2's
     * clearance) and the checkpoint chain ({@code feasible}, asked at most {@value #CHAIN_TRIES} times; never
     * about a spot that blocks all a refused one did, since fewer checkpoint places never make a chain).
     * {@code null} when none does.
     */
    static Piece place(GenRandom r, Skeleton sk, DropPlan drops, Kind k, double len, Room room, List<Piece> placed,
                       int[] runoff, Predicate<List<double[]>> feasible, List<double[]> refused) {
        List<double[]> fits = new ArrayList<>();
        for (boolean bendsOnly : new boolean[]{true, false}) {
            List<double[]> these = new ArrayList<>();
            for (double[] f : room.of(k, bendsOnly)) {
                if (f[1] - f[0] >= len + 2) {
                    these.add(f);
                }
            }
            these.sort((a, b) -> a[1] - a[0] != b[1] - b[0] ? Double.compare(a[1] - a[0], b[1] - b[0])
                    : Double.compare(a[0], b[0]));
            fits.addAll(these);
        }
        List<Double> spots = new ArrayList<>();
        for (int pass = 0; pass < 3; pass++) {
            for (double[] f : fits) {
                double slack = f[1] - f[0] - len - 2;
                double s1 = switch (pass) {
                    case 0 -> Math.ceil(f[0] + 1);
                    case 1 -> Math.floor(f[0] + 1 + slack);
                    default -> Math.floor(f[0] + 1 + r.nextDouble() * slack);
                };
                boolean seen = false;
                for (double t : spots) {
                    seen |= Math.abs(t - s1) < 1;
                }
                if (!seen) {
                    spots.add(s1);
                }
            }
        }
        int asked = 0;
        for (double s1 : spots) {
            double s2 = s1 + len;
            boolean clear = true;
            if (k == Kind.BOOST) {
                for (DropPlan.Drop d : drops.drops) {
                    clear &= !(s2 > d.s() - 30 && s1 < d.s());
                }
            }
            for (double[] no : refused) {
                clear &= !(s1 - 1 <= no[0] && s2 + 1 >= no[1]);
            }
            if (!clear || (k.widens() && !roomToWiden(sk, s1, s2, drops))) {
                continue;
            }
            Piece p = make(r, sk, k, s1, s2);
            List<Piece> trial = new ArrayList<>(placed);
            trial.add(p);
            if (feasible.test(new PiecesV4(trial, runoff).blocked())) {
                return p;
            }
            refused.add(new double[]{s1 - 1, s2 + 1});
            if (++asked >= CHAIN_TRIES) {
                return null;
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
                // the island stands where the lane is at its widest, so both ways are P + 2 wide
                p.a1 = s1 + TAPER + 1;
                p.a2 = s2 - TAPER - 1;
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
