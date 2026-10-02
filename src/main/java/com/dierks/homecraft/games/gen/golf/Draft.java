package com.dierks.homecraft.games.gen.golf;

import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.GenRandom;
import com.dierks.homecraft.games.gen.api.Palette;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * A Golf v4 hole being laid out (GOLF-V4-SPEC §3.6): a routing of legs, then at most one piece per
 * leg and a green, drawn on a size-aware {@link HoleTemplate.Sketch} by Adventure Golf's own block
 * rules, then rendered to blocks.
 *
 * <p><b>Legs.</b> The first leg runs along +Z from the tee at local z 3; each later leg starts where
 * the one before it ends (its end centre: the elbow) and runs along +X, or −Z on a hairpin's last
 * leg. A leg has a length (centre to centre) and a half-width (5 wide: 2; 7 wide: 3). Pieces are
 * placed in a leg's own coordinates: u along it from its start centre, v across it (+X on a leg along
 * Z, +Z on a leg along X). The lane of a leg covers u from just behind its start (the row behind the
 * tee, or the elbow box) to its end's elbow box, or on the last leg {@link #runout} rows past the
 * cup (the green's run-out).
 *
 * <p><b>The rules kept.</b> Pieces keep their distance from the tee row, the elbow boxes and the cup
 * ring ({@link #clear}); a raised slab always has lane round it; ponds are 3 x 3 or more. A draw
 * that doesn't work out (no room for a tree, say) throws {@link Redraw}: that attempt fails and the
 * planner draws again from the next attempt's stream, exactly as the stored layout re-derives.
 */
final class Draft {

    /** Rows a piece keeps clear of the tee row, an elbow box and the cup ring. */
    static final int CLEAR = 3;
    /**
     * Rows of green behind the cup before the back wall on Golf v4's 40 x 64 plots (GOLF-V4-SPEC §3.6,
     * red-team F00): a ball that passes the cup at holing speed (at most 0.35 a tick) rolls at most 3.5
     * more, so it stops on the green; and a Drive too fast to drop meets the wall 4.5 past the cup and
     * comes back at most 60% as fast, too slow to reach the cup again from anywhere a block out or
     * more. (Adventure Golf's greens end one row behind the cup: a Drive banked back off that wall
     * into the cup from 8-13 blocks, so a Drive and a putter did every job.) Tiny Golf's 20 x 40 plots
     * keep Adventure Golf's one row ({@link #runout}): a child's course, whose par and kid bound were
     * set on those greens.
     */
    static final int RUNOUT = 4;

    /** A draw that didn't work out: the attempt fails (deterministically: the stored attempt never did). */
    static final class Redraw extends Exception {
        Redraw(String why) {
            super(why, null, false, false);
        }
    }

    /**
     * One leg: its start centre (x0, z0) in plot cells, its direction (dx, dz), its length from start
     * centre to end centre and its half-width.
     */
    record Leg(int x0, int z0, int dx, int dz, int length, int half) {

        /** Plot x of leg point (u along, v across). */
        int x(int u, int v) {
            return x0 + dx * u + (dz != 0 ? v : 0);
        }

        /** Plot z of leg point (u along, v across). */
        int z(int u, int v) {
            return z0 + dz * u + (dx != 0 ? v : 0);
        }

        /** The end centre x (the elbow, or the cup's row on the last leg). */
        int endX() {
            return x(length, 0);
        }

        int endZ() {
            return z(length, 0);
        }

        /** The width of its lane. */
        int width() {
            return 2 * half + 1;
        }
    }

    final HoleTemplate.Sketch s;
    final GenRandom r;
    final char tier;
    final boolean shorter;
    final boolean dry;
    final int sx;
    final int sz;
    final List<Leg> legs = new ArrayList<>();
    final EnumSet<Quota.Feature> features = EnumSet.noneOf(Quota.Feature.class);
    final List<String> words = new ArrayList<>();
    GenCopy.TeeFeature tee = GenCopy.TeeFeature.NONE;
    /** The cup's offset across the last leg (v). */
    int cupOff;
    /** Rows of green past the cup: {@value #RUNOUT} on a 40 x 64 plot, Adventure Golf's 1 on Tiny Golf's 20 x 40. */
    final int runout;

    /**
     * @param r       the attempt's stream
     * @param tier    the hole's tier (E, M or H)
     * @param grid    the plots it is drawn for
     * @param shorter whether lengths come from the shorter half of their ranges (attempts 4-11)
     * @param dry     Tiny Golf: never water in play
     */
    Draft(GenRandom r, char tier, PlotGrid grid, boolean shorter, boolean dry) {
        this.r = r;
        this.tier = Character.toUpperCase(tier);
        this.shorter = shorter;
        this.dry = dry;
        this.sx = grid.plotX();
        this.sz = grid.plotZ();
        this.runout = sz >= PlotGrid.V4.plotZ() ? RUNOUT : 1;
        this.s = new HoleTemplate.Sketch(sx, sz);
        s.adventure();
        s.tier = this.tier;
    }

    boolean easy() {
        return tier == 'E';
    }

    boolean hard() {
        return tier == 'H';
    }

    /**
     * A length from {@code lo}..{@code hi}, or from its shorter half on a later attempt. On Tiny Golf
     * (whose par a child sets: {@link OrdinaryPar.Model#CHILD}) the range's top is two shorter.
     */
    int len(int lo, int hi) {
        int top = dry ? Math.max(lo, hi - 2) : hi;
        return r.nextInt(lo, shorter ? lo + (top - lo) / 2 : top);
    }

    /** {@code lo}..{@code hi}, uniformly (a number that isn't a length). */
    int pick(int lo, int hi) {
        return r.nextInt(lo, hi);
    }

    // ---- the routing ----------------------------------------------------------------------------------

    /** The first leg: from the tee at (x0, 3) along +Z, {@code length} to its end centre. */
    Leg first(int x0, int length, int half) {
        Leg l = new Leg(x0, 3, 0, 1, length, half);
        legs.add(l);
        return l;
    }

    /** A leg from the last one's end centre along (dx, dz). */
    Leg then(int dx, int dz, int length, int half) {
        Leg prev = legs.get(legs.size() - 1);
        Leg l = new Leg(prev.endX(), prev.endZ(), dx, dz, length, half);
        legs.add(l);
        return l;
    }

    Leg last() {
        return legs.get(legs.size() - 1);
    }

    /**
     * Draw every leg's lane at the turf (each covering its elbow boxes), the tee, and the cup at the
     * last leg's end, {@code cupOff} across it. Pieces come after.
     */
    void lay(int cupOff) throws Redraw {
        if (Math.abs(cupOff) > last().half() - 1) {
            throw new IllegalStateException("a cup off its lane's middle by " + cupOff);
        }
        this.cupOff = cupOff;
        for (int i = 0; i < legs.size(); i++) {
            Leg l = legs.get(i);
            int u0 = i == 0 ? -1 : -legs.get(i - 1).half();
            int u1 = i == legs.size() - 1 ? l.length() + runout : l.length() + legs.get(i + 1).half();
            lane(l, u0, u1, -l.half(), l.half(), 0);
        }
        Leg f = legs.get(0);
        s.tee(f.x0(), f.z0());
        Leg e = last();
        s.cup(e.x(e.length(), cupOff), e.z(e.length(), cupOff));
        if (legs.size() == 2) {
            features.add(Quota.Feature.TWO_LEGS);
        } else if (legs.size() >= 3) {
            features.add(Quota.Feature.THREE_LEGS);
        }
    }

    /**
     * The tee sign for a hole of this many legs: a dogleg says which way it turns as the player faces
     * from the tee (+X is their left, so a mirrored hole turns right), a hole of three legs says so.
     */
    GenCopy.TeeFeature legSign(boolean mirrored) {
        if (legs.size() >= 3) {
            return GenCopy.TeeFeature.THREE_LEGS;
        }
        if (legs.size() == 2) {
            boolean left = legs.get(1).dx() > 0;
            return left != mirrored ? GenCopy.TeeFeature.DOGLEG_LEFT : GenCopy.TeeFeature.DOGLEG_RIGHT;
        }
        return GenCopy.TeeFeature.NONE;
    }

    /** The x a hole spanning {@code span} columns from its first leg's centre is centred at, jittered. */
    int centred(int span, int margin) throws Redraw {
        int lo = margin;
        int hi = sx - 1 - margin - span;
        if (hi < lo) {
            throw new Redraw("a hole " + span + " wide doesn't fit a plot " + sx + " wide");
        }
        int mid = (lo + hi) / 2;
        int jitter = Math.min(2, (hi - lo) / 2);
        return Math.max(lo, Math.min(hi, mid + r.nextInt(-jitter, jitter)));
    }

    // ---- cells in a leg's coordinates -------------------------------------------------------------------

    /** The plot rectangle {x0, x1, z0, z1} of leg cells u0..u1, v0..v1. */
    static int[] rect(Leg l, int u0, int u1, int v0, int v1) {
        int ax = l.x(u0, v0);
        int bx = l.x(u1, v1);
        int az = l.z(u0, v0);
        int bz = l.z(u1, v1);
        return new int[]{Math.min(ax, bx), Math.max(ax, bx), Math.min(az, bz), Math.max(az, bz)};
    }

    void lane(Leg l, int u0, int u1, int v0, int v1, int level) throws Redraw {
        int[] c = inside(rect(l, u0, u1, v0, v1));
        s.lane(c[0], c[1], c[2], c[3], level);
    }

    void floor(Leg l, int u0, int u1, int v0, int v1, byte kind) {
        int[] c = rect(l, u0, u1, v0, v1);
        s.floor(c[0], c[1], c[2], c[3], kind);
    }

    void water(Leg l, int u0, int u1, int v0, int v1) throws Redraw {
        int[] c = inside(rect(l, u0, u1, v0, v1));
        s.water(c[0], c[1], c[2], c[3]);
    }

    void bunker(Leg l, int u0, int u1, int v0, int v1) throws Redraw {
        int[] c = inside(rect(l, u0, u1, v0, v1));
        s.bunker(c[0], c[1], c[2], c[3]);
    }

    void glass(Leg l, int u0, int u1, int v0, int v1) {
        int[] c = rect(l, u0, u1, v0, v1);
        s.glass(c[0], c[1], c[2], c[3]);
    }

    void slime(Leg l, int u0, int u1, int v0, int v1) {
        int[] c = rect(l, u0, u1, v0, v1);
        s.slimeWalls(c[0], c[1], c[2], c[3]);
    }

    /** A rectangle the Sketch can draw lane or water on (a row and a column clear of the plot's edge). */
    private int[] inside(int[] c) throws Redraw {
        if (c[0] < 1 || c[2] < 1 || c[1] > sx - 2 || c[3] > sz - 2) {
            throw new Redraw("a piece past the plot's edge at " + c[0] + ".." + c[1] + ", " + c[2] + ".." + c[3]);
        }
        return c;
    }

    /**
     * Whether leg cells u0..u1 of leg {@code i} keep {@value #CLEAR} rows from the tee row, from the
     * elbow boxes at the leg's ends, and from the cup ({@link #from}, {@link #to}).
     */
    boolean clear(int i, int u0, int u1) {
        return u0 >= from(i) && u1 <= to(i);
    }

    /** The first u of leg {@code i} a piece may cover: {@value #CLEAR} past the tee row or the elbow box. */
    int from(int i) {
        return i == 0 ? CLEAR : legs.get(i - 1).half() + CLEAR;
    }

    /** The last u of leg {@code i} a piece may cover: {@value #CLEAR} before the next elbow box, or the cup. */
    int to(int i) {
        Leg l = legs.get(i);
        return i == legs.size() - 1 ? l.length() - CLEAR : l.length() - legs.get(i + 1).half() - CLEAR;
    }

    // ---- elbows ---------------------------------------------------------------------------------------

    /**
     * Slime on the outside walls of the elbow after leg {@code i}: the wall beyond it straight on, and
     * the wall on the side away from the turn (a bank shot).
     */
    void bank(int i) {
        Leg a = legs.get(i);
        Leg b = legs.get(i + 1);
        int h = b.half();
        int far = a.length() + h + 1;
        slime(a, far, far, -a.half() - 1, a.half() + 1);
        int turn = a.dz() != 0 ? b.dx() : b.dz(); // the turn's sign along this leg's v
        int out = turn > 0 ? -a.half() - 1 : a.half() + 1;
        slime(a, a.length() - h, far, out, out);
    }

    // ---- pieces ---------------------------------------------------------------------------------------

    /**
     * A sand patch on leg {@code i} over u0..u0+rows-1: on Easy flush (a 3 x 3 of smooth sandstone,
     * one off the middle on a 5-wide leg), on Medium and Hard a sunken bunker 3 wide across the middle
     * of a leg at least 7 wide.
     */
    void sand(int i, int u0, int rows) throws Redraw {
        Leg l = legs.get(i);
        if (!clear(i, u0, u0 + rows - 1)) {
            throw new Redraw("no room for sand on leg " + (i + 1));
        }
        if (easy() || l.half() < 3) {
            int side = l.half() >= 2 ? pick(-1, 1) : 0;
            floor(l, u0, u0 + rows - 1, -1 + side, 1 + side, HoleTemplate.Sketch.SAND);
            words.add("sand " + rows + " at " + u0);
        } else {
            bunker(l, u0, u0 + rows - 1, -1, 1);
            words.add("a bunker " + rows + " at " + u0);
        }
        features.add(Quota.Feature.SAND);
    }

    /**
     * Where a sand patch of {@code rows} on leg 0 catches a Drive off the tee (on turf it stops 12.9
     * on, the landing zone 11-15 from the tee; sand halves what is left of its roll, so a patch that
     * starts 9-12 on stops it inside), or -1 when the leg is too short for one.
     */
    int landing(int rows) {
        int lo = Math.max(9, from(0));
        int hi = Math.min(12, to(0) - rows + 1);
        return hi < lo ? -1 : pick(lo, hi);
    }

    /** A hump on leg {@code i} at u0: a slab up, {@code top} rows a block up, a slab down. */
    void hump(int i, int u0, int top) throws Redraw {
        Leg l = legs.get(i);
        int down = u0 + top + 1;
        if (!clear(i, u0, down)) {
            throw new Redraw("no room for a hump on leg " + (i + 1));
        }
        lane(l, u0, down, -l.half(), l.half(), 2);
        lane(l, u0, u0, -l.half() + 1, l.half() - 1, 1);
        lane(l, down, down, -l.half() + 1, l.half() - 1, 1);
        features.add(Quota.Feature.HEIGHT);
        words.add("a hump at " + u0);
    }

    /**
     * A hill on leg {@code i} (at least 7 wide): slab steps up at u0 to a crest a block up,
     * {@code crest} long, then a block's lip down.
     */
    void hill(int i, int u0, int crest) throws Redraw {
        Leg l = legs.get(i);
        int lip = u0 + crest;
        if (l.half() < 3 || !clear(i, u0, lip + 3)) {
            throw new Redraw("no room for a hill on leg " + (i + 1));
        }
        lane(l, u0, lip, -l.half(), l.half(), 2);
        lane(l, u0, u0, -l.half() + 1, l.half() - 1, 1);
        features.add(Quota.Feature.HEIGHT);
        features.add(Quota.Feature.BIG_DROP);
        words.add("a hill " + crest + " long at " + u0);
    }

    /**
     * A creek across leg {@code i} (at least 7 wide) at u0, {@code across} rows, reaching 3 past each
     * side wall, with a 3-wide turf bridge {@code bridge} off the middle.
     */
    void creek(int i, int u0, int across, int bridge) throws Redraw {
        Leg l = legs.get(i);
        if (dry || l.half() < 3 || !clear(i, u0, u0 + across - 1)) {
            throw new Redraw("no room for a creek on leg " + (i + 1));
        }
        water(l, u0, u0 + across - 1, -l.half() - 3, l.half() + 3);
        lane(l, u0, u0 + across - 1, bridge - 1, bridge + 1, 0);
        features.add(Quota.Feature.WATER);
        words.add("a creek " + across + " across at " + u0 + ", bridge " + bridge);
    }

    /**
     * A pond beside leg {@code i}, {@code across} wide and {@code length} long from u0, on side
     * {@code side} (+1 or -1), no wall between it and the lane.
     */
    void pond(int i, int u0, int length, int across, int side) throws Redraw {
        Leg l = legs.get(i);
        if (dry || length < HoleTemplate.Sketch.SKIM || !clear(i, u0, u0 + length - 1)) {
            throw new Redraw("no room for a pond on leg " + (i + 1));
        }
        int v0 = side > 0 ? l.half() + 1 : -l.half() - across;
        water(l, u0, u0 + length - 1, v0, v0 + across - 1);
        features.add(Quota.Feature.WATER);
        words.add("a pond " + across + " by " + length + " at " + u0);
    }

    /**
     * Trees in play on leg {@code i} (5 or 7 wide): {@code want} of them, where
     * {@link HoleTemplate.Sketch#canTrunk} allows.
     */
    void trees(int i, int want) throws Redraw {
        Leg l = legs.get(i);
        int placed = 0;
        int lo = from(i);
        int hi = to(i);
        for (int t = 0; t < 60 && placed < want && hi >= lo; t++) {
            int u = pick(lo, hi);
            int v = pick(-l.half() + 1, l.half() - 1);
            String wood = Palette.WOODS.get(r.nextInt(Palette.WOODS.size()));
            int x = l.x(u, v);
            int z = l.z(u, v);
            if (s.canTrunk(x, z)) {
                s.tree(x, z, wood);
                placed++;
            }
        }
        if (placed < want) {
            throw new Redraw("room for " + placed + " of " + want + " trees on leg " + (i + 1));
        }
        features.add(Quota.Feature.TREES);
        words.add(placed + " tree" + (placed == 1 ? "" : "s"));
    }

    /** Ice on leg {@code i} from u0, {@code ice} rows, then {@code brake} rows of soul soil. */
    void ice(int i, int u0, int ice, int brake) throws Redraw {
        Leg l = legs.get(i);
        if (!clear(i, u0, u0 + ice + brake - 1)) {
            throw new Redraw("no room for ice on leg " + (i + 1));
        }
        floor(l, u0, u0 + ice - 1, -l.half(), l.half(), HoleTemplate.Sketch.ICE);
        floor(l, u0 + ice, u0 + ice + brake - 1, -l.half(), l.half(), HoleTemplate.Sketch.BRAKE);
        words.add(ice + " ice, " + brake + " brake");
    }

    /** One or two slime rocks on leg {@code i} (Easy's bumpers), and slime side walls along it. */
    void bumpers(int i, int want) throws Redraw {
        Leg l = legs.get(i);
        int placed = 0;
        for (int t = 0; t < 24 && placed < want; t++) {
            int u = pick(from(i), Math.max(from(i), to(i)));
            int v = pick(-l.half(), l.half());
            int x = l.x(u, v);
            int z = l.z(u, v);
            if (s.canRock(x, z) && clear(i, u, u)) {
                s.rock(x, z, true);
                placed++;
            }
        }
        if (placed == 0) {
            throw new Redraw("no room for a bumper on leg " + (i + 1));
        }
        slime(l, from(i), to(i), -l.half() - 1, -l.half() - 1);
        slime(l, from(i), to(i), l.half() + 1, l.half() + 1);
        words.add(placed + " bumper" + (placed == 1 ? "" : "s"));
    }

    /**
     * Terraces from the tee along leg 0: T + 2 for {@code top} rows (the tee on it), T + 1 for
     * {@code middle} more, then the turf; the edges glass. Hard: a pond beside the bottom terrace.
     */
    void terraces(int top, int middle, boolean pond) throws Redraw {
        Leg l = legs.get(0);
        int z4 = top - 2; // the last row at T + 2 (u from the tee): the tee row is u 0
        int z2 = z4 + middle;
        if (z2 > to(0)) {
            throw new Redraw("no room for terraces");
        }
        lane(l, -1, z2, -l.half(), l.half(), 2);
        lane(l, -1, z4, -l.half(), l.half(), 4);
        glass(l, z4, z4, -l.half(), l.half());
        glass(l, z2, z2, -l.half(), l.half());
        features.add(Quota.Feature.HEIGHT);
        features.add(Quota.Feature.BIG_DROP);
        words.add("terraces " + top + " and " + middle);
        if (pond && !dry) {
            int rows = pick(3, 4);
            water(l, z2 + 1, z2 + rows, l.half() + 1, l.half() + 3);
            features.add(Quota.Feature.WATER);
            words.add("a pond below them");
        }
    }

    /**
     * The first leg a block up, dropping at the elbow (the dogleg that drops): Medium, the slime bank
     * outside the elbow; Hard, a sunken 3 x 3 bunker in its outer corner, a turf rim round it.
     */
    void dropAtElbow() throws Redraw {
        Leg a = legs.get(0);
        Leg b = legs.get(1);
        lane(a, -1, a.length() - b.half() - 1, -a.half(), a.half(), 2);
        if (hard()) {
            int turn = b.dx();
            int out = turn > 0 ? -1 : 1; // the outer side along v
            int rim = out * (a.half() + 1);
            lane(a, a.length() - b.half(), a.length() + b.half() + 1, Math.min(rim, -a.half()),
                    Math.max(rim, a.half()), 0);
            int v0 = out > 0 ? a.half() - 2 : -a.half();
            bunker(a, a.length(), a.length() + 2, v0, v0 + 2);
            features.add(Quota.Feature.SAND);
            words.add("dropping at the elbow, a bunker");
        } else {
            bank(0);
            words.add("dropping at the elbow");
        }
        features.add(Quota.Feature.HEIGHT);
        features.add(Quota.Feature.BIG_DROP);
    }

    /**
     * A corner pond (a layup, GOLF-V4-SPEC §3.6 after red-team F00): the far wall of the elbow after leg
     * {@code i} is water, {@code deep} rows from the row past the elbow box, reaching from beyond the
     * leg's outer side to {@code reach} across along the next leg's outer side. A ball played from the
     * tee towards the corner — the first-timer aims across it, at the furthest waypoint it sees — that
     * still rolls when it gets there goes in; one that stops short stays in the elbow. The planner
     * checks which clubs reach it along the line the player aims ({@link GolfPlannerV4#layupHolds}).
     */
    void cornerPond(int i, int deep, int reach) throws Redraw {
        if (dry) {
            throw new Redraw("a pond on a dry course");
        }
        Leg a = legs.get(i);
        Leg b = legs.get(i + 1);
        int turn = a.dz() != 0 ? b.dx() : b.dz(); // the turn's sign along this leg's v
        int u0 = a.length() + b.half() + 1;
        int out = -turn * (a.half() + 1);
        int far = turn * reach;
        water(a, u0, u0 + deep - 1, Math.min(out, far), Math.max(out, far));
        features.add(Quota.Feature.WATER);
        words.add("a corner pond " + deep + " deep, " + reach + " along");
    }

    /**
     * A wooden rock at the inner corner of the elbow after leg {@code i}, in the leg's last row
     * before the elbow box on the side it turns to (a chip layup's): it hides the next leg from the
     * tee, so the first-timer aims nearly straight on, into the corner pond a Swing reaches and a
     * Chip doesn't.
     */
    void cornerRock(int i) throws Redraw {
        Leg a = legs.get(i);
        Leg b = legs.get(i + 1);
        int turn = a.dz() != 0 ? b.dx() : b.dz();
        int x = a.x(a.length() - b.half() - 1, turn * a.half());
        int z = a.z(a.length() - b.half() - 1, turn * a.half());
        if (!s.canRock(x, z)) {
            throw new Redraw("no room for a rock at the corner");
        }
        s.rock(x, z, false);
        words.add("a rock at the corner");
    }

    /**
     * Water behind the green ({@code deep} rows past its run-out, the back wall's width and its
     * corners): a ball played too hard at the cup goes in rather than banking back off a wall.
     */
    void backPond(int deep) throws Redraw {
        if (dry) {
            throw new Redraw("a pond on a dry course");
        }
        Leg l = last();
        int u0 = l.length() + runout + 1;
        water(l, u0, u0 + deep - 1, -l.half() - 1, l.half() + 1);
        features.add(Quota.Feature.WATER);
        words.add("water behind the green");
    }

    /**
     * A raised green at the end of the last leg: a full step up at u0 with a slab ramp across its
     * middle (3 wide), the green to the cup and its run-out past it. The cup is then a block up.
     */
    void rampGreen(int u0) throws Redraw {
        int i = legs.size() - 1;
        Leg l = last();
        if (!clear(i, u0, u0)) {
            throw new Redraw("no room for a ramp green");
        }
        lane(l, u0, l.length() + runout, -l.half(), l.half(), 2);
        lane(l, u0, u0, -l.half() + 1, l.half() - 1, 1);
        features.add(Quota.Feature.HEIGHT);
        words.add("a raised green from " + u0);
    }

    /**
     * An island green: the end of the last leg (5 wide) raised a block from 3 rows before the cup to
     * its run-out, entered by a slab ramp 3 wide (2 on Hard) across its first row.
     */
    void islandGreen() throws Redraw {
        int i = legs.size() - 1;
        Leg l = last();
        if (l.half() != 2) {
            throw new IllegalStateException("an island green on a leg " + l.width() + " wide");
        }
        int g0 = l.length() - 3; // the cup in its fourth row, its run-out behind
        if (!clear(i, g0, g0)) {
            throw new Redraw("no room for an island green");
        }
        lane(l, g0, l.length() + Math.max(2, runout), -2, 2, 2); // Adventure Golf's island ran two past its cup
        int width = hard() ? 2 : 3;
        int e0 = hard() ? pick(-1, 0) : -1;
        lane(l, g0, g0, e0, e0 + width - 1, 1);
        features.add(Quota.Feature.HEIGHT);
        words.add("an island green, entrance " + width + " wide");
    }

    /** The features the hole has. */
    Set<Quota.Feature> features() {
        return features;
    }
}
