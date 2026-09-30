package com.dierks.homecraft.games.gen.boat;

import com.dierks.homecraft.games.gen.api.GenRandom;

import java.util.ArrayList;
import java.util.List;

/**
 * The Mountain Run's pieces (Course Variety §2.4, §2.5): what makes a run more than ice and drops.
 * A seeded deck per tier ({@code fork("pieces:" + t)}), each piece placed on a straight where it
 * fits, and kept only while the checkpoints can still be laid round it.
 *
 * <ul>
 *   <li><b>Sand run-offs and kerbs</b> on every bend tighter than R 24: the lane widens on the
 *       outside of the bend by 1 (easy) or 2 columns of sand, and on medium and hard by a kerb of
 *       sand on the inside at the apex. Go wide and you crawl; the full lane of ice is still
 *       there, so sand is never forced (V5b).</li>
 *   <li><b>The sand pit</b>: the lane widens by 6 over 8 blocks, a pit of sand 4 wide (3 on hard)
 *       sits in the middle, and ice lanes at least P wide go round both sides.</li>
 *   <li><b>Pick a path</b>: the lane splits round a moss island with a stripped-spruce rim and a
 *       tree, both branches P wide at the same level; on medium and hard one branch has a sand
 *       patch.</li>
 *   <li><b>The Ice Cave</b>: 12-20 blocks of straight under a light-blue glass roof 5 over the
 *       ice, with sea lanterns in its walls and moss portals.</li>
 *   <li><b>The forest slalom</b> (medium, hard): the lane widens by 6 and 2 x 2 trunks stand in it,
 *       alternately near each wall, every gap at least P, under a leafy roof 5 and 6 over the
 *       ice.</li>
 *   <li><b>Boost strips</b> (medium): the middle 3 columns blue ice for 10-20 blocks, never within
 *       30 before a drop.</li>
 * </ul>
 * A piece never sits on the first 40 blocks after the start, on a drop's run-up or landing strip,
 * or at the finish. Sand pits, caves and boosts stay out of every flight zone; a split or forest
 * may stand in a 1-block drop's zone after its landing strip (its rim and canopy keep the zone's
 * walls and headroom), never in a 2-block drop's. Widening goes into the grass strip between the
 * rings, never nearer the stand than {@link BoatPlanner#STAND_ROOM} and never out of the half.
 *
 * <p>Lateral positions are "inward" offsets v: + toward the stand, - away. Pure: no Bukkit.
 */
final class TrackPieces {

    enum Kind {
        SAND_PIT("sand pit"), SPLIT("split"), CAVE("cave"), FOREST("forest"), BOOST("boost");

        final String word;

        Kind(String word) {
            this.word = word;
        }
    }

    /** One placed piece: [s1, s2] along leg {@code leg}'s straight, widened by eIn / eOut. */
    static final class Piece {
        final Kind kind;
        final int leg;
        final double s1;
        final double s2;
        final int eIn;
        final int eOut;
        final int taper;
        /** The main feature: a sand pit's or island's [a1, a2] along, [lo, hi] across (inward columns). */
        double a1;
        double a2;
        int lo;
        int hi;
        /** A split's sand patch, or none ({@code b2 <= b1}). */
        double b1;
        double b2;
        int blo;
        int bhi;
        /** A forest's trunks: {along start (s), inward lo}, each 2 x 2. */
        final List<double[]> trunks = new ArrayList<>();

        Piece(Kind kind, int leg, double s1, double s2, int eIn, int eOut, int taper) {
            this.kind = kind;
            this.leg = leg;
            this.s1 = s1;
            this.s2 = s2;
            this.eIn = eIn;
            this.eOut = eOut;
            this.taper = taper;
        }

        /** How far the lane is widened {@code s} along: inward (in = true) or outward. */
        double extra(double s, boolean in) {
            int e = in ? eIn : eOut;
            if (e == 0 || s <= s1 || s >= s2) {
                return 0;
            }
            if (taper <= 0) {
                return e;
            }
            double ramp = Math.min(1, Math.min((s - s1) / taper, (s2 - s) / taper));
            return e * ramp;
        }
    }

    /** Where checkpoints can't go (a piece and a block either side), handed to the checkpoint check. */
    interface Feasible {
        boolean ok(List<double[]> blocked);
    }

    final List<Piece> list;
    /** Per path segment: sand columns on the outside of the bend, and the kerb on its inside at the apex. */
    final int[] runoff;
    final int[] kerb;

    TrackPieces(List<Piece> list, int[] runoff, int[] kerb) {
        this.list = List.copyOf(list);
        this.runoff = runoff;
        this.kerb = kerb;
    }

    /** No pieces and no sand on the bends ({@code SAFE_SPIRAL}). */
    static TrackPieces none(TrackPath path) {
        return new TrackPieces(List.of(), new int[path.segs.size()], new int[path.segs.size()]);
    }

    // ---- the lane, as the raster asks ---------------------------------------------------------------

    /** How far the lane reaches beyond its half width {@code s} along on segment {@code seg}: inward or outward. */
    double extra(TrackPath.Seg seg, double s, boolean in) {
        double e = 0;
        if (seg.arc) {
            if (!in) {
                e = runoff[seg.index];
            } else if (kerb[seg.index] > 0 && apex(seg, s)) {
                e = kerb[seg.index];
            }
        }
        for (Piece p : list) {
            e = Math.max(e, p.extra(s, in));
        }
        return e;
    }

    /** The middle half of a bend. */
    static boolean apex(TrackPath.Seg seg, double s) {
        double u = (s - seg.s0) / seg.len;
        return u > 0.25 && u < 0.75;
    }

    /** Whether column (s, v) is an island or a trunk: not track, though inside the lane's width. */
    boolean obstacle(double s, double v) {
        for (Piece p : list) {
            if (p.kind == Kind.SPLIT && s > p.a1 && s < p.a2 && v >= p.lo && v <= p.hi) {
                return true;
            }
            if (p.kind == Kind.FOREST) {
                for (double[] t : p.trunks) {
                    if (s > t[0] && s < t[0] + 2 && v >= t[1] && v <= t[1] + 1) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Whether drive cell (s, v) (hw its lane's half width) is sand: a run-off, a kerb, a pit or a patch. */
    boolean sand(TrackPath.Seg seg, double s, double v, double hw) {
        if (seg.arc && Math.abs(v) > hw) {
            return true;
        }
        for (Piece p : list) {
            if (p.kind == Kind.SAND_PIT && s > p.a1 && s < p.a2 && v >= p.lo && v <= p.hi) {
                return true;
            }
            if (p.kind == Kind.SPLIT && p.b2 > p.b1 && s > p.b1 && s < p.b2 && v >= p.blo && v <= p.bhi) {
                return true;
            }
        }
        return false;
    }

    /** Whether drive cell (s, v) is a boost strip's blue ice. */
    boolean boost(double s, double v) {
        for (Piece p : list) {
            if (p.kind == Kind.BOOST && s > p.s1 && s < p.s2 && Math.abs(v) <= 1) {
                return true;
            }
        }
        return false;
    }

    /** The piece at {@code s}, or {@code null}. */
    Piece at(double s) {
        for (Piece p : list) {
            if (s > p.s1 && s < p.s2) {
                return p;
            }
        }
        return null;
    }

    /** The piece at {@code s} that keeps checkpoints off (every piece but a boost strip), or {@code null}. */
    Piece blocks(double s) {
        for (Piece p : list) {
            if (p.kind != Kind.BOOST && s > p.s1 && s < p.s2) {
                return p;
            }
        }
        return null;
    }

    /** How many of a kind. */
    int count(Kind k) {
        int n = 0;
        for (Piece p : list) {
            n += p.kind == k ? 1 : 0;
        }
        return n;
    }

    /** How many bends have a run-off, and how many a kerb. */
    int runoffs() {
        int n = 0;
        for (int r : runoff) {
            n += r > 0 ? 1 : 0;
        }
        return n;
    }

    int kerbs() {
        int n = 0;
        for (int k : kerb) {
            n += k > 0 ? 1 : 0;
        }
        return n;
    }

    // ---- drawing -------------------------------------------------------------------------------------

    /**
     * The pieces for a try: sand on every tight bend, then the tier's deck for {@code richness}, each
     * placed where it fits and kept only if {@code feasible} still lays the checkpoints round it.
     */
    static TrackPieces draw(GenRandom r, TrackPath path, TrackProfile profile, BoatPlanner.Level level,
                            BoatPlanner.Richness richness, int h0, Feasible feasible) {
        int[] runoff = new int[path.segs.size()];
        int[] kerb = new int[path.segs.size()];
        bends(path, level, runoff, kerb);
        List<Piece> placed = new ArrayList<>();
        if (richness == BoatPlanner.Richness.BASIC) {
            return new TrackPieces(placed, runoff, kerb);
        }
        List<Kind> deck = deck(r, level, richness);
        for (Kind k : deck) {
            List<Piece> options = options(r, path, profile, level, k, placed, h0);
            for (int attempt = 0; attempt < BoatPlanner.PIECE_ATTEMPTS && !options.isEmpty(); attempt++) {
                Piece p = options.remove(r.nextInt(options.size()));
                List<double[]> blocked = blocked(placed);
                if (p.kind != Kind.BOOST) {
                    blocked.add(new double[]{p.s1, p.s2});
                }
                if (feasible.ok(blocked)) {
                    placed.add(p);
                    break;
                }
            }
        }
        placed.sort((a, b) -> Double.compare(a.s1, b.s1));
        return new TrackPieces(placed, runoff, kerb);
    }

    /** Where checkpoints can't go because of {@code placed}: every piece but a boost strip. */
    static List<double[]> blocked(List<Piece> placed) {
        List<double[]> out = new ArrayList<>();
        for (Piece q : placed) {
            if (q.kind != Kind.BOOST) {
                out.add(new double[]{q.s1, q.s2});
            }
        }
        return out;
    }

    /** Sand on the tight bends: run-offs outside, kerbs inside at the apex, none nearer the stand than it allows. */
    static void bends(TrackPath path, BoatPlanner.Level level, int[] runoff, int[] kerb) {
        for (TrackPath.Seg g : path.segs) {
            if (!g.arc || g.r >= BoatPlanner.SANDY_BEND) {
                continue;
            }
            runoff[g.index] = level.runoff();
            if (level.kerb() > 0 && kerbClear(path, level, g)) {
                kerb[g.index] = level.kerb();
            }
        }
    }

    /** Whether a kerb on bend {@code g} keeps the stand's clearance: the kerb's inner edge stays far enough. */
    private static boolean kerbClear(TrackPath path, BoatPlanner.Level level, TrackPath.Seg g) {
        double inner = g.r - level.width() / 2.0 - level.kerb() - 1;
        // the arc's nearest point to C
        double d = Math.hypot(g.ox - path.cx, g.oz - path.cz);
        double nearest = inner > d ? inner - d : Math.max(0, d - inner);
        // an offset of STAND_ROOM from C along an axis is as near as the clearance allows; a
        // diagonal is farther, so the plain distance is a safe test
        return nearest >= BoatPlanner.STAND_ROOM + 1;
    }

    /** The tier's deck for this richness, in a seeded order. */
    static List<Kind> deck(GenRandom r, BoatPlanner.Level level, BoatPlanner.Richness richness) {
        List<Kind> deck = new ArrayList<>();
        boolean reduced = richness == BoatPlanner.Richness.REDUCED;
        add(deck, Kind.SAND_PIT, reduced ? Math.min(1, level.pits()[1]) : r.nextInt(level.pits()[0], level.pits()[1]));
        add(deck, Kind.SPLIT, reduced ? Math.min(1, level.splits()[1])
                : r.nextInt(level.splits()[0], level.splits()[1]));
        add(deck, Kind.CAVE, r.nextInt(level.caves()[0], level.caves()[1]));
        if (!reduced) {
            add(deck, Kind.FOREST, level.forests());
        }
        add(deck, Kind.BOOST, r.nextInt(level.boosts()[0], level.boosts()[1]));
        // shuffle, seeded
        for (int i = deck.size() - 1; i > 0; i--) {
            int j = r.nextInt(i + 1);
            Kind t = deck.get(i);
            deck.set(i, deck.get(j));
            deck.set(j, t);
        }
        return deck;
    }

    private static void add(List<Kind> deck, Kind k, int n) {
        for (int i = 0; i < n; i++) {
            deck.add(k);
        }
    }

    /** Every place a piece of kind {@code k} fits, each drawn ready to place (its sizes seeded). */
    static List<Piece> options(GenRandom r, TrackPath path, TrackProfile profile, BoatPlanner.Level level, Kind k,
                               List<Piece> placed, int h0) {
        List<Piece> out = new ArrayList<>();
        int len = length(r, level, k);
        int extra = extra(k, level);
        int taper = switch (k) {
            case SAND_PIT -> BoatPlanner.PIT_TAPER;
            case FOREST -> BoatPlanner.FOREST_TAPER;
            default -> 0;
        };
        double hw = level.width() / 2.0;
        for (int leg = 0; leg < path.legs(); leg++) {
            TrackPath.Seg st = path.straight(leg);
            int[] room = room(path, level, leg);
            int eOut = Math.min(room[1], (extra + 1) / 2);
            int eIn = extra - eOut;
            if (eIn > room[0]) {
                eIn = room[0];
                eOut = extra - eIn;
            }
            if (eOut > room[1] || eIn > room[0]) {
                continue;
            }
            if ((k == Kind.SPLIT || k == Kind.SAND_PIT) && ((eIn - eOut) & 1) != 0) {
                // the island or pit sits on whole columns: keep the widening's middle on a column
                if (eIn + 1 <= room[0] && eOut - 1 >= 0) {
                    eIn++;
                    eOut--;
                } else if (eOut + 1 <= room[1] && eIn - 1 >= 0) {
                    eOut++;
                    eIn--;
                } else {
                    continue;
                }
            }
            // whole blocks from the straight's start, plus a half: the piece ends between columns
            double first = TrackProfile.spotStart(st) + BoatPlanner.PIECE_END_GAP + 0.5;
            for (double s1 = first; s1 + len + BoatPlanner.PIECE_END_GAP <= st.s1() + 1e-9; s1 += 1) {
                double s2 = s1 + len;
                if (!free(profile, level, k, s1, s2, placed, h0)) {
                    continue;
                }
                out.add(make(r, k, leg, s1, s2, eIn, eOut, taper, level, hw));
            }
        }
        return out;
    }

    /** How long a piece of kind {@code k} is, seeded. */
    private static int length(GenRandom r, BoatPlanner.Level level, Kind k) {
        return switch (k) {
            case SAND_PIT -> 2 * BoatPlanner.PIT_TAPER + 2 + r.nextInt(8, 12);
            case SPLIT -> 10 + r.nextInt(BoatPlanner.ISLAND_MIN, BoatPlanner.ISLAND_MAX);
            case CAVE -> r.nextInt(12, 20);
            case FOREST -> 2 * BoatPlanner.FOREST_TAPER + 2 + 6 * level.trunks() - 4;
            case BOOST -> r.nextInt(10, 20);
        };
    }

    /** How far a piece widens the lane in all. */
    private static int extra(Kind k, BoatPlanner.Level level) {
        return switch (k) {
            case SAND_PIT, FOREST -> 6;
            case SPLIT -> 2 * level.proof().narrowest() + 3 - level.width();
            default -> 0;
        };
    }

    /** {inward, outward}: how far leg {@code leg}'s lane may widen each way. */
    static int[] room(TrackPath path, BoatPlanner.Level level, int leg) {
        double hw = level.width() / 2.0;
        int a = path.offsets[leg];
        int out = leg <= 3 ? (int) Math.floor(BoatPlanner.OUTER_EDGE - (a + hw)) : BoatPlanner.MAX_EXTRA;
        int in = leg + 4 < path.legs() ? BoatPlanner.MAX_EXTRA
                : (int) Math.floor(a - hw - BoatPlanner.STAND_ROOM - 1);
        return new int[]{Math.max(0, Math.min(BoatPlanner.MAX_EXTRA, in)),
                Math.max(0, Math.min(BoatPlanner.MAX_EXTRA, out))};
    }

    /**
     * Whether [s1, s2] is free for kind {@code k}: after the start's 40, before the finish, apart
     * from the other pieces, on one level (a cave's ice at most H0 + 7, a forest's H0 + 6), off every
     * run-up and landing strip (a cave 10 further), a boost strip never within 30 before a drop, and
     * out of every flight zone but a 1-block drop's, where a split or forest may stand.
     */
    private static boolean free(TrackProfile profile, BoatPlanner.Level level, Kind k, double s1, double s2,
                                List<Piece> placed, int h0) {
        if (s1 < TrackProfile.START + TrackProfile.CLEAN) {
            return false;
        }
        if (s2 > profile.finish - level.finishRadius() - 3) {
            return false;
        }
        for (Piece q : placed) {
            if (s1 < q.s2 + BoatPlanner.PIECE_GAP && s2 > q.s1 - BoatPlanner.PIECE_GAP) {
                return false;
            }
        }
        int y = profile.level(s1);
        if (profile.level(s2) != y) {
            return false;
        }
        if (k == Kind.CAVE && y > h0 + BoatPlanner.CAVE_TOP) {
            return false;
        }
        if (k == Kind.FOREST && y > h0 + BoatPlanner.FOREST_TOP) {
            return false;
        }
        for (int i = 0; i < profile.lips.size(); i++) {
            TrackProfile.Lip l = profile.lips.get(i);
            double runUp = l.s() - level.runUp() - (k == Kind.CAVE ? BoatPlanner.CAVE_LIP : 0);
            double landing = l.s() + level.landing(l.drop());
            if (s1 < landing && s2 > runUp) {
                return false;
            }
            if (k == Kind.BOOST && s2 > l.s() - BoatPlanner.BOOST_LIP && s1 < l.s()) {
                return false;
            }
            double exit = profile.exits[i];
            boolean inZone = s1 < exit + (k == Kind.CAVE ? BoatPlanner.CAVE_LIP : 1) && s2 > l.s();
            if (inZone) {
                boolean mayStand = (k == Kind.SPLIT || k == Kind.FOREST) && l.drop() == 1;
                if (!mayStand) {
                    return false;
                }
            }
        }
        return true;
    }

    /** A piece of kind {@code k} at [s1, s2], its inner features drawn. */
    private static Piece make(GenRandom r, Kind k, int leg, double s1, double s2, int eIn, int eOut, int taper,
                              BoatPlanner.Level level, double hw) {
        Piece p = new Piece(k, leg, s1, s2, eIn, eOut, taper);
        int half = (int) Math.floor(hw);
        int centre = (eIn - eOut) / 2;
        switch (k) {
            case SAND_PIT -> {
                // the pit in the full-width middle, a block of ice before and after it
                p.a1 = s1 + taper + 1;
                p.a2 = s2 - taper - 1;
                int width = level.sandPit();
                int lo = centre - width / 2;
                if (width % 2 == 0 && r.nextBoolean()) {
                    lo++;
                }
                p.lo = lo;
                p.hi = lo + width - 1;
            }
            case SPLIT -> {
                p.a1 = s1 + BoatPlanner.SPLIT_APPROACH;
                p.a2 = s2 - BoatPlanner.SPLIT_APPROACH;
                int island = 3;
                p.lo = centre - island / 2;
                p.hi = centre + island / 2;
                if (level.splitSand()) {
                    // a sand patch across the middle of one branch
                    boolean inward = r.nextBoolean();
                    p.b1 = p.a1 + Math.floor((p.a2 - p.a1) / 2) - 2;
                    p.b2 = p.b1 + 4;
                    if (inward) {
                        p.blo = p.hi + 1;
                        p.bhi = half + eIn;
                    } else {
                        p.blo = -(half + eOut);
                        p.bhi = p.lo - 1;
                    }
                }
            }
            case FOREST -> {
                // trunks alternately near the outer and the inner wall, a gap of P from it
                int narrow = level.proof().narrowest();
                boolean outer = r.nextBoolean();
                double at = s1 + taper + 1;
                for (int i = 0; i < level.trunks(); i++) {
                    int lo = outer ? -(half + eOut) + narrow : (half + eIn) - narrow - 1;
                    p.trunks.add(new double[]{at, lo});
                    at += 6;
                    outer = !outer;
                }
            }
            default -> {
            }
        }
        return p;
    }
}
