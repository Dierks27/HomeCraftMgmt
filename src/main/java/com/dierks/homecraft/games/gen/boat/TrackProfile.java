package com.dierks.homecraft.games.gen.boat;

import com.dierks.homecraft.games.gen.api.GenRandom;

import java.util.ArrayList;
import java.util.List;

/**
 * The Mountain Run's heights (Course Variety §2.2, §2.4-2.6): where the drops are, how big each is,
 * and where the finish is, all as distances s along the {@link TrackPath}. The floor is a step
 * function of s: a column takes the level at its nearest centreline point, so every drop edge is
 * square to the centreline and runs wall to wall.
 *
 * <p><b>Drops</b> ({@code fork("drops:" + t)}) go only on straights, one a leg at most, each with a
 * flat straight run-up (at least 8, and room for the checkpoint before it) and a straight landing
 * strip after it (the fun constants of §2.5: 22 / 26 blocks on packed ice, 33 / 41 on blue, for a 1-
 * or 2-block drop). The first is at least the tier's distance after the start; each next one at
 * least Z(d) + 12 after the one before and never inside its flight zone ({@link #apart}: the zone is
 * flooded along the track only while the track stays within Z(d) of the lip, as
 * {@code DownhillValidator} proves it). Each goes only where its checkpoints can cross it: the one
 * just before the edge and the first place past the zone where one can go, at most 60 apart
 * ({@link #post}), so no leg ever holds two drops. The place on its leg is seeded among those that
 * do; then some become 2s (medium and hard: the Final Drop first, each moving along its leg if it
 * must) within the tier's count of 2s and its whole fall.
 *
 * <p><b>The finish</b> is on the last leg, the inner ring round the stand, 12-30 blocks from the
 * platform's edge (V11), with 14 blocks of run-out and a 6-block sand paddock after it. The Final
 * Drop (the last) keeps Z(d) + 3 from the finish's middle from every block of its edge, across the
 * ground.
 *
 * <p>Pure: no Bukkit.
 */
final class TrackProfile {

    /** The start is this far along leg 0: 30 blocks of pit behind it, 4 more in front. */
    static final double START = 30.5;
    /** The pit is this long; nothing but the pit and the lane is on the first {@link #CLEAN} after the start. */
    static final double PIT = 34;
    static final double CLEAN = 40;
    /** The finish's run-out: this many blocks of ice after its middle, then {@link #PADDOCK} of sand, then the end wall. */
    static final int RUN_OUT = 14;
    static final int PADDOCK = 6;
    /** The finish is at least this far (and at most {@link #FINISH_FAR}) from the platform's edge: V11's, with a margin. */
    static final double FINISH_NEAR = DownhillValidator.FINISH_NEAR + 0.5;
    static final double FINISH_FAR = DownhillValidator.FINISH_FAR - 0.5;
    /** The margin kept over Z(d) + 3 from the Final Drop to the finish. */
    static final double FINAL_MARGIN = 0.5;

    /** One drop: its leg, the s of its edge (upper before, lower after) and how far it falls. */
    record Lip(int leg, double s, int drop) {
    }

    final int top;
    final List<Lip> lips;
    final double finish;
    final double end;
    /**
     * Per drop: the s by which its flight zone has surely ended (the track has left the disc), made
     * exact once the lane is on blocks ({@link #withZones}).
     */
    final double[] exits;

    private TrackProfile(int top, List<Lip> lips, double finish, double end, double[] exits) {
        this.top = top;
        this.lips = List.copyOf(lips);
        this.finish = finish;
        this.end = end;
        this.exits = exits;
    }

    /** This profile with its drops' zone ends as the blocks have them ({@code ends[i]}, or kept where NaN). */
    TrackProfile withZones(double[] ends) {
        double[] e = exits.clone();
        for (int i = 0; i < e.length && i < ends.length; i++) {
            if (!Double.isNaN(ends[i])) {
                e[i] = ends[i];
            }
        }
        return new TrackProfile(top, lips, finish, end, e);
    }

    /** The ice height {@code s} along the centreline. */
    int level(double s) {
        int y = top;
        for (Lip l : lips) {
            if (s > l.s()) {
                y -= l.drop();
            }
        }
        return y;
    }

    /** The lowest ice (the finish's). */
    int bottom() {
        return level(Double.MAX_VALUE);
    }

    /** How many drops lie strictly between s = a and s = b. */
    int lipsBetween(double a, double b) {
        int n = 0;
        for (Lip l : lips) {
            if (l.s() > a && l.s() < b) {
                n++;
            }
        }
        return n;
    }

    /** The Final Drop, or {@code null} when there are no drops. */
    Lip last() {
        return lips.isEmpty() ? null : lips.get(lips.size() - 1);
    }

    /** The total fall. */
    int descent() {
        int d = 0;
        for (Lip l : lips) {
            d += l.drop();
        }
        return d;
    }

    // ---- drawing -------------------------------------------------------------------------------------

    /**
     * The drops and the finish for {@code path}, from {@code r}; {@code null} when no finish fits
     * the last leg or fewer drops fit than the tier's least. {@code standX}, {@code standZ} are the
     * stand's centre column (half columns); {@code top} the start's ice.
     *
     * <p>Each drop goes where the checkpoints can cross it: the one just before its edge, and the
     * first place past its flight zone where a checkpoint (or the finish) can go, at most
     * {@link BoatPlanner#LEG_MAX} apart ({@link #post}); the next drop's own checkpoint comes after
     * that place.
     */
    static TrackProfile draw(GenRandom r, TrackPath path, BoatPlanner.Level level, int top, int standX, int standZ) {
        double finish = finish(r, path, level, standX, standZ);
        if (Double.isNaN(finish)) {
            return null;
        }
        List<Lip> lips = new ArrayList<>();
        List<Double> posts = new ArrayList<>();
        int legs = path.legs();
        for (int k = 1; k < legs && lips.size() < level.maxDrops(); k++) {
            List<Lip> ok = new ArrayList<>();
            List<Double> okPost = new ArrayList<>();
            options(path, level, lips, posts, k, 1, finish, ok, okPost);
            if (ok.isEmpty()) {
                continue;
            }
            int i = r.nextInt(ok.size());
            lips.add(ok.get(i));
            posts.add(okPost.get(i));
        }
        // the Final Drop keeps its distance from the finish: another place on its leg, or give it up
        while (!lips.isEmpty() && !finalFits(path, level, lips.get(lips.size() - 1), finish)) {
            Lip l = lips.remove(lips.size() - 1);
            posts.remove(posts.size() - 1);
            List<Lip> ok = new ArrayList<>();
            List<Double> okPost = new ArrayList<>();
            options(path, level, lips, posts, l.leg(), 1, finish, ok, okPost);
            for (int i = 0; i < ok.size(); i++) {
                if (finalFits(path, level, ok.get(i), finish)) {
                    lips.add(ok.get(i));
                    posts.add(okPost.get(i));
                    break;
                }
            }
            if (!lips.isEmpty() && lips.get(lips.size() - 1).leg() == l.leg()) {
                break;
            }
        }
        if (lips.size() < level.minDrops()) {
            return null;
        }
        upgrade(r, path, level, lips, posts, finish);
        return of(path, level, top, lips, finish);
    }

    /**
     * Every place on leg {@code k} a drop of {@code d} may have its edge after {@code before} (whose
     * checkpoint crossings end at {@code posts}), in order, with where its own crossing ends.
     */
    private static void options(TrackPath path, BoatPlanner.Level level, List<Lip> before, List<Double> posts, int k,
                                int d, double finish, List<Lip> out, List<Double> outPost) {
        double[] w = window(path, level, before, k, d, finish);
        if (w == null) {
            return;
        }
        double cp = level.checkpointRadius();
        double lo = w[0];
        if (!posts.isEmpty()) {
            // its own checkpoint comes after the last drop's crossing
            lo = Math.max(lo, posts.get(posts.size() - 1) + cp + 0.5);
        }
        for (double s = w[0]; s <= w[1] + 1e-9; s += 1) {
            if (s < lo - 1e-9) {
                continue;
            }
            Lip c = new Lip(k, s, d);
            if (!apart(path, level, before, c)) {
                continue;
            }
            double post = post(path, level, c, finish);
            if (!Double.isNaN(post)) {
                out.add(c);
                outPost.add(post);
            }
        }
    }

    /**
     * Whether drop {@code c} is outside the flight zone of every drop before it: its lip cells more
     * than Z from the other's (lip row to lip row), or past where the track has surely left the
     * other's disc ({@link #exit}), since the zone is flooded along the track inside it.
     */
    static boolean apart(TrackPath path, BoatPlanner.Level level, List<Lip> before, Lip c) {
        List<double[]> row = row(path, level, c);
        for (Lip p : before) {
            double z = BoatEnvelope.zone(p.drop()) + 0.5;
            boolean near = false;
            for (double[] a : row(path, level, p)) {
                for (double[] b : row) {
                    near |= Math.hypot(a[0] - b[0], a[1] - b[1]) <= z;
                }
            }
            if (near && c.s() <= exit(path, level, p, c.s() + 1) + 1) {
                return false;
            }
        }
        return true;
    }

    /** The middles of drop {@code l}'s lip cells: the high row, across the lane. */
    static List<double[]> row(TrackPath path, BoatPlanner.Level level, Lip l) {
        double[] c = path.at(l.s() - 0.5);
        double[] t = path.tangent(l.s() - 0.5);
        double hw = level.width() / 2.0;
        List<double[]> out = new ArrayList<>();
        for (double o = -hw + 0.5; o <= hw - 0.5 + 1e-9; o += 1) {
            out.add(new double[]{c[0] - t[1] * o, c[1] + t[0] * o});
        }
        return out;
    }

    /**
     * Where the checkpoints' crossing of drop {@code l} ends: the first place after it that is at
     * least Z(d) + 3 from every lip cell where a checkpoint can go (on a straight, its disk flat on
     * it), or the finish if that comes first; {@code NaN} when that place is more than
     * {@link BoatPlanner#LEG_MAX} from the checkpoint just before the drop, or there is none.
     */
    static double post(TrackPath path, BoatPlanner.Level level, Lip l, double finish) {
        double r = level.checkpointRadius();
        double preS = l.s() - r - 0.5;
        TrackPath.Seg own = path.straight(l.leg());
        if (preS - r < own.s0 - 1e-9) {
            return Double.NaN;
        }
        double[] pre = path.at(preS);
        double want = BoatEnvelope.zone(l.drop()) + DownhillValidator.DROP_CLEAR + 0.25;
        List<double[]> row = row(path, level, l);
        double limit = BoatPlanner.LEG_MAX - 0.5;
        for (TrackPath.Seg g : path.segs) {
            if (g.s1() < l.s() || (g.arc && g.r < BoatPlanner.SANDY_BEND)) {
                continue; // before the drop, or a sandy bend (no checkpoint on sand)
            }
            double gr = g.arc ? level.width() / 2.0 + BoatPlanner.ARC_SPOT : r;
            double first = g.arc ? g.s0 + 0.5 : spotStart(g);
            for (double s = first; s <= g.s1() + 1e-9; s += 1) {
                if (!g.arc && (s - gr < g.s0 - 1e-9 || s + gr > g.s1() + 1e-9)) {
                    continue;
                }
                if (s - gr < l.s() + 0.5 - 1e-9) {
                    continue;
                }
                if (s >= finish - 1e-9) {
                    double[] f = path.at(finish);
                    return finalFits(path, level, l, finish) && Math.hypot(f[0] - pre[0], f[1] - pre[1]) <= limit
                            ? finish : Double.NaN;
                }
                double[] p = path.at(s);
                boolean clear = true;
                for (double[] c : row) {
                    if (Math.hypot(p[0] - c[0], p[1] - c[1]) < want) {
                        clear = false;
                        break;
                    }
                }
                if (clear) {
                    return Math.hypot(p[0] - pre[0], p[1] - pre[1]) <= limit ? s : Double.NaN;
                }
            }
        }
        return Double.NaN;
    }

    /** The first place along straight {@code g} in the middle of a column (where checkpoints are put). */
    static double spotStart(TrackPath.Seg g) {
        double along = g.tx != 0 ? g.ax : g.az;
        double frac = along - Math.floor(along);
        return g.s0 + (Math.abs(frac - 0.5) < 1e-6 ? 0 : 0.5);
    }

    /**
     * {@code SAFE_SPIRAL}'s drops: {@code drops} in order, each at the first place it may go (its
     * checkpoints crossing it) on the next leg that has room; {@code null} when they don't all fit or
     * the last is too near the finish.
     */
    static List<Lip> fixed(TrackPath path, BoatPlanner.Level level, int[] drops, double finish) {
        List<Lip> lips = new ArrayList<>();
        List<Double> posts = new ArrayList<>();
        int k = 1;
        for (int n = 0; n < drops.length; n++) {
            boolean last = n == drops.length - 1;
            Lip placed = null;
            for (; k < path.legs() && placed == null; k++) {
                List<Lip> ok = new ArrayList<>();
                List<Double> okPost = new ArrayList<>();
                options(path, level, lips, posts, k, drops[n], finish, ok, okPost);
                for (int i = 0; i < ok.size() && placed == null; i++) {
                    if (!last || finalFits(path, level, ok.get(i), finish)) {
                        placed = ok.get(i);
                        posts.add(okPost.get(i));
                    }
                }
            }
            if (placed == null) {
                return null;
            }
            lips.add(placed);
        }
        return lips;
    }

    /** The profile with exactly these drops and this finish ({@code SAFE_SPIRAL}, and after drawing). */
    static TrackProfile of(TrackPath path, BoatPlanner.Level level, int top, List<Lip> lips, double finish) {
        double[] exits = new double[lips.size()];
        for (int i = 0; i < lips.size(); i++) {
            exits[i] = exit(path, level, lips.get(i), finish + RUN_OUT + PADDOCK + 0.5);
        }
        return new TrackProfile(top, lips, finish, finish + RUN_OUT + PADDOCK + 0.5, exits);
    }

    /**
     * Some drops become 2s (medium and hard): the Final Drop first, then others in a seeded order,
     * each only while its landing strip, its checkpoints' crossing, the spacing to the next drop and
     * that drop's being outside its zone, the tier's count of 2s and the total fall all still hold.
     */
    private static void upgrade(GenRandom r, TrackPath path, BoatPlanner.Level level, List<Lip> lips,
                                List<Double> posts, double finish) {
        DownhillValidator.Tier t = level.proof();
        if (t.maxDrop() < 2) {
            return;
        }
        List<Integer> order = new ArrayList<>();
        for (int i = lips.size() - 2; i >= 0; i--) {
            order.add(r.nextInt(order.size() + 1), i);
        }
        order.add(0, lips.size() - 1);
        int bigs = 0;
        int fall = lips.size();
        for (int n = 0; n < order.size(); n++) {
            int i = order.get(n);
            if (bigs >= t.bigDrops() || fall + 1 > t.descent()) {
                break;
            }
            boolean want = n == 0 || r.chance(BoatPlanner.BIG_CHANCE);
            if (!want) {
                continue;
            }
            Lip l = lips.get(i);
            // a 2 where this one is, or anywhere else on its leg it keeps the rest
            List<Lip> ok = new ArrayList<>();
            List<Double> okPost = new ArrayList<>();
            options(path, level, lips.subList(0, i), posts.subList(0, i), l.leg(), 2, finish, ok, okPost);
            Lip big = null;
            double post = Double.NaN;
            int from = ok.isEmpty() ? 0 : r.nextInt(ok.size());
            for (int m = 0; m < ok.size() && big == null; m++) {
                Lip c = ok.get((from + m) % ok.size());
                double p = fits(path, level, lips, i, c, finish);
                if (!Double.isNaN(p)) {
                    big = c;
                    post = p;
                }
            }
            if (big == null) {
                continue;
            }
            lips.set(i, big);
            posts.set(i, post);
            bigs++;
            fall++;
        }
    }

    /** Where drop {@code i}'s crossing ends if it is {@code l} with the rest as they are; {@code NaN} if it can't be. */
    private static double fits(TrackPath path, BoatPlanner.Level level, List<Lip> lips, int i, Lip l, double finish) {
        TrackPath.Seg st = path.straight(l.leg());
        if (st.s1() - l.s() < level.landing(l.drop()) - 1e-9) {
            return Double.NaN;
        }
        double post = post(path, level, l, finish);
        if (Double.isNaN(post)) {
            return Double.NaN;
        }
        if (i + 1 < lips.size()) {
            Lip next = lips.get(i + 1);
            if (next.s() < l.s() + BoatEnvelope.zone(l.drop()) + BoatPlanner.LIP_GAP
                    || next.s() - level.checkpointRadius() - 0.5 < post
                    || !apart(path, level, List.of(l), next)) {
                return Double.NaN;
            }
        } else if (!finalFits(path, level, l, finish)) {
            return Double.NaN;
        }
        return post;
    }

    /**
     * The s range a drop of {@code d} may have its edge in on leg {@code k}, given the drops before
     * it ({@code {lo, hi}}, whole blocks from the straight's start plus a half, so the edge falls
     * between two columns), or {@code null}.
     */
    private static double[] window(TrackPath path, BoatPlanner.Level level, List<Lip> before, int k, int d,
                                   double finish) {
        TrackPath.Seg st = path.straight(k);
        double lo = st.s0 + level.runUp();
        double hi = st.s1() - level.landing(d);
        if (k == path.legs() - 1) {
            hi = Math.min(hi, finish - BoatEnvelope.zone(d) - 3 - level.finishRadius());
        }
        lo = Math.max(lo, START + level.firstLip());
        for (Lip p : before) {
            lo = Math.max(lo, p.s() + BoatEnvelope.zone(p.drop()) + BoatPlanner.LIP_GAP);
        }
        // whole blocks from the straight's start, plus a half: the edge between two columns
        double base = spotStart(st);
        double first = base + Math.ceil(lo - base - 0.5) + 0.5;
        double last = base + Math.floor(hi - base - 0.5) + 0.5;
        if (first > last + 1e-9) {
            return null;
        }
        return new double[]{first, last};
    }

    /**
     * The s by which drop {@code l}'s flight zone has surely ended: the first point along after it
     * where every block of the track's cross-section is farther than Z(d) from every block of the
     * lip (the zone is flooded along the track inside that disc, so it can't get past); the path's
     * end ({@code sEnd}) when the track never leaves it.
     */
    static double exit(TrackPath path, BoatPlanner.Level level, Lip l, double sEnd) {
        double[] lip = path.at(l.s() - 0.5);
        double reach = BoatEnvelope.zone(l.drop()) + level.width() + BoatPlanner.MAX_EXTRA + 1.5;
        for (double s = l.s(); s < sEnd; s += 0.5) {
            double[] p = path.at(s);
            if (Math.hypot(p[0] - lip[0], p[1] - lip[1]) > reach) {
                return s;
            }
        }
        return sEnd;
    }

    /** Whether drop {@code l}, as the Final Drop, is far enough from the finish at {@code finish}. */
    static boolean finalFits(TrackPath path, BoatPlanner.Level level, Lip l, double finish) {
        double[] f = path.at(finish);
        double[] c = path.at(l.s() - 0.5);
        double[] t = path.tangent(l.s() - 0.5);
        double want = BoatEnvelope.zone(l.drop()) + DownhillValidator.DROP_CLEAR + FINAL_MARGIN;
        double hw = level.width() / 2.0;
        // every column of the lip row: its middle is within the lane's half width across the centreline
        for (double o = -hw + 0.5; o <= hw - 0.5 + 1e-9; o += 1) {
            double x = c[0] - t[1] * o;
            double z = c[1] + t[0] * o;
            if (Math.hypot(f[0] - x, f[1] - z) < want) {
                return false;
            }
        }
        return true;
    }

    /**
     * The finish's s on the last leg: in the middle of a column, its whole disk on the straight,
     * the run-out and paddock before the leg's end, 12-30 from the platform; seeded among those,
     * {@code NaN} when none.
     */
    static double finish(GenRandom r, TrackPath path, BoatPlanner.Level level, int standX, int standZ) {
        TrackPath.Seg st = path.straight(path.legs() - 1);
        List<Double> ok = new ArrayList<>();
        for (int n = (int) Math.ceil(level.finishRadius() + 2); n + RUN_OUT + PADDOCK + 1 <= st.len; n++) {
            double s = st.s0 + n;
            double[] p = path.at(s);
            double d = standDistance(p[0], p[1], standX, standZ);
            if (d >= FINISH_NEAR && d <= FINISH_FAR) {
                ok.add(s);
            }
        }
        if (ok.isEmpty()) {
            return Double.NaN;
        }
        if (r == null) {
            return ok.get(ok.size() / 2); // the safe spiral's: the window's middle
        }
        // the middle half of the window, seeded
        int lo = ok.size() / 4;
        int hi = Math.max(lo, ok.size() - 1 - ok.size() / 4);
        return ok.get(r.nextInt(lo, hi));
    }

    /** V11's measure: from (x, z) (half columns) to the edge of the 7 x 7 platform round column (sx, sz). */
    static double standDistance(double x, double z, int sx, int sz) {
        int h = com.dierks.homecraft.games.trial.RaceStand.SIZE / 2;
        double ex = Math.max(0, Math.max((sx - h) - x, x - (sx + h + 1)));
        double ez = Math.max(0, Math.max((sz - h) - z, z - (sz + h + 1)));
        return Math.hypot(ex, ez);
    }
}
