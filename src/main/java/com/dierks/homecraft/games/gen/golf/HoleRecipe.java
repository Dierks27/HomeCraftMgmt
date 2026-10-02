package com.dierks.homecraft.games.gen.golf;

import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.GenRandom;
import com.dierks.homecraft.games.gen.api.Palette;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Golf v4's hole recipes (GOLF-V4-SPEC §3.3, §3.6): a routing (a straight, a dogleg, an S-bend, a
 * hairpin) of a length class, at most one piece per leg (sand, a hump, a hill, a creek, a pond, trees,
 * ice, bumpers, terraces, a layup) and a green (flat, a ramp, an island, a volcano), drawn by a
 * {@link Draft} on the size-aware {@link HoleTemplate.Sketch} with Adventure Golf's block rules.
 *
 * <p>Each recipe is one length class and one set of features, so the deal ({@link DealV4}) can count
 * a course's quota before a block is drawn, exactly as Adventure Golf's templates are counted. Each
 * draws its numbers in a fixed order from the attempt's stream; changing one changes every later
 * number, and so the golden plans (bump {@link GolfPlanner#ALGO} with it). On a later attempt the
 * lengths come from the shorter half of their ranges ({@link Draft#len}).
 *
 * <p>Plots: Golf of the Week's 40 x 64 (its S, M, L and X holes), Tiny Golf's 20 x 40 (S and M only,
 * every recipe with water in play left out: {@link #wet}). A recipe whose hole doesn't fit its plot
 * fails the attempt ({@link Draft.Redraw}).
 *
 * <p>Not dealt (a deviation, GOLF-V4-SPEC §3.6): CARRY, a pond across the whole lane. Adventure
 * Golf's lane rule (the lane is where the ball can roll from the tee) leaves the far side of such a
 * pond stranded, so the per-hole rules the spec keeps unchanged refuse it; its tee sign words exist.
 */
enum HoleRecipe {

    // ---- S: par 2, 8-12 -----------------------------------------------------------------------------
    S_STRAIGHT(LengthClass.S, "E"),
    S_BUMPERS(LengthClass.S, "E"),
    S_HUMP(LengthClass.S, "E"),
    S_SAND(LengthClass.S, "E"),
    S_TREE(LengthClass.S, "E"),
    S_RAMP(LengthClass.S, "EMH"),
    S_ISLAND(LengthClass.S, "EMH"),
    S_POND_VIEW(LengthClass.S, "E"),

    // ---- M: par 3, 16-25 ----------------------------------------------------------------------------
    M_SAND(LengthClass.M, "EMH"),
    M_HUMP(LengthClass.M, "E"),
    M_BUMPERS(LengthClass.M, "E"),
    M_HILL(LengthClass.M, "MH"),
    M_CREEK(LengthClass.M, "MH"),
    M_POND(LengthClass.M, "EMH"),
    M_TREES(LengthClass.M, "EMH"),
    M_ICE(LengthClass.M, "MH"),
    M_DOGLEG(LengthClass.M, "EMH"),
    M_DOGLEG_DOWN(LengthClass.M, "MH"),
    M_DOGLEG_TREES(LengthClass.M, "MH"),
    M_TERRACES(LengthClass.M, "MH"),
    M_TWO_WAY(LengthClass.M, "MH"),
    M_VOLCANO(LengthClass.M, "H"),

    // ---- L: par 4, 27-38 ----------------------------------------------------------------------------
    L_LAYUP(LengthClass.L, "MH"),
    L_LAYUP_SAND(LengthClass.L, "EM"),
    L_DOGLEG_SAND_POND(LengthClass.L, "MH"),
    L_DOGLEG_TREES_GREEN(LengthClass.L, "MH"),
    L_DOGLEG_HILL_SAND(LengthClass.L, "MH"),
    L_DOGLEG_DOWN(LengthClass.L, "MH"),
    L_SBEND(LengthClass.L, "MH"),
    L_SBEND_SAND(LengthClass.L, "MH"),
    L_TERRACES_CREEK(LengthClass.L, "MH"),
    L_STRAIGHT_HUMP_SAND(LengthClass.L, "MH"),

    // ---- X: par 5, 40-52 ----------------------------------------------------------------------------
    X_SBEND_SAND(LengthClass.X, "H"),
    X_SBEND_CREEK(LengthClass.X, "H"),
    X_SBEND_POND(LengthClass.X, "H"),
    X_SBEND_ISLAND(LengthClass.X, "H"),
    X_SBEND_ICE(LengthClass.X, "H"),
    X_LONG_DOGLEG_TREES(LengthClass.X, "H"),
    X_LONG_DOGLEG_POND(LengthClass.X, "H"),
    X_LONG_DOGLEG_ISLAND(LengthClass.X, "H"),
    X_HAIRPIN(LengthClass.X, "H"),
    X_LAYUP(LengthClass.X, "H");

    final LengthClass cls;
    private final String tiers;

    HoleRecipe(LengthClass cls, String tiers) {
        this.cls = cls;
        this.tiers = tiers;
    }

    /** Whether this recipe can be dealt to a hole of {@code tier}. */
    boolean fits(char tier) {
        return tiers.indexOf(Character.toUpperCase(tier)) >= 0;
    }

    /** Whether a hole of this recipe drawn for {@code tier} has water in play (never on Tiny Golf). */
    boolean wet(char tier) {
        return features(tier, false).contains(Quota.Feature.WATER);
    }

    /**
     * The recipes a hole of {@code tier} and {@code cls} is dealt from, in their fixed order; on a dry
     * course none with water in play.
     */
    static List<HoleRecipe> list(char tier, LengthClass cls, boolean dry) {
        List<HoleRecipe> out = new ArrayList<>();
        for (HoleRecipe h : values()) {
            if (h.cls == cls && h.fits(tier) && (!dry || !h.wet(tier))) {
                out.add(h);
            }
        }
        return out;
    }

    /**
     * What a hole of this recipe drawn for {@code tier} has, as the quota counts it ({@link Quota}); on
     * a {@code dry} course (Tiny Golf) without the water Hard's terraces would have. A drawn hole has
     * exactly these ({@link HoleLayout#features}).
     */
    Set<Quota.Feature> features(char tier, boolean dry) {
        char t = Character.toUpperCase(tier);
        boolean easy = t == 'E';
        boolean hard = t == 'H';
        EnumSet<Quota.Feature> f = EnumSet.noneOf(Quota.Feature.class);
        switch (this) {
            case S_STRAIGHT, S_BUMPERS, M_BUMPERS, M_ICE -> {
                // nothing the quota counts
            }
            case S_HUMP, S_RAMP, S_ISLAND, M_HUMP -> f.add(Quota.Feature.HEIGHT);
            case S_SAND, M_SAND -> f.add(Quota.Feature.SAND);
            case S_TREE, M_TREES -> f.add(Quota.Feature.TREES);
            case S_POND_VIEW -> f.add(Quota.Feature.VIEW);
            case M_HILL, M_VOLCANO -> f.addAll(EnumSet.of(Quota.Feature.HEIGHT, Quota.Feature.BIG_DROP));
            case M_CREEK -> f.add(Quota.Feature.WATER);
            case M_POND -> f.add(easy ? Quota.Feature.VIEW : Quota.Feature.WATER);
            case M_DOGLEG -> f.add(Quota.Feature.TWO_LEGS);
            case M_DOGLEG_DOWN, L_DOGLEG_DOWN -> {
                f.addAll(EnumSet.of(Quota.Feature.HEIGHT, Quota.Feature.BIG_DROP, Quota.Feature.TWO_LEGS));
                if (hard) {
                    f.add(Quota.Feature.SAND);
                }
            }
            case M_DOGLEG_TREES -> f.addAll(EnumSet.of(Quota.Feature.TREES, Quota.Feature.TWO_LEGS));
            case M_TERRACES -> {
                f.addAll(EnumSet.of(Quota.Feature.HEIGHT, Quota.Feature.BIG_DROP));
                if (hard && !dry) {
                    f.add(Quota.Feature.WATER);
                }
            }
            case M_TWO_WAY -> {
                f.addAll(EnumSet.of(Quota.Feature.TREES, Quota.Feature.WATER));
                if (hard) {
                    f.add(Quota.Feature.SAND);
                }
            }
            case L_LAYUP -> f.addAll(EnumSet.of(Quota.Feature.LAYUP, Quota.Feature.WATER, Quota.Feature.TWO_LEGS));
            case L_LAYUP_SAND -> f.addAll(EnumSet.of(Quota.Feature.LAYUP, Quota.Feature.SAND, Quota.Feature.TWO_LEGS));
            case L_DOGLEG_SAND_POND -> f.addAll(EnumSet.of(Quota.Feature.SAND, Quota.Feature.WATER,
                    Quota.Feature.TWO_LEGS));
            case L_DOGLEG_TREES_GREEN -> f.addAll(EnumSet.of(Quota.Feature.TREES, Quota.Feature.HEIGHT,
                    Quota.Feature.TWO_LEGS));
            case L_DOGLEG_HILL_SAND -> f.addAll(EnumSet.of(Quota.Feature.HEIGHT, Quota.Feature.BIG_DROP,
                    Quota.Feature.SAND, Quota.Feature.TWO_LEGS));
            case L_SBEND, X_SBEND_ICE, X_HAIRPIN -> f.add(Quota.Feature.THREE_LEGS);
            case L_SBEND_SAND -> f.addAll(EnumSet.of(Quota.Feature.SAND, Quota.Feature.THREE_LEGS));
            case L_TERRACES_CREEK -> f.addAll(EnumSet.of(Quota.Feature.HEIGHT, Quota.Feature.BIG_DROP,
                    Quota.Feature.WATER));
            case L_STRAIGHT_HUMP_SAND -> f.addAll(EnumSet.of(Quota.Feature.HEIGHT, Quota.Feature.SAND));
            case X_SBEND_SAND -> f.addAll(EnumSet.of(Quota.Feature.SAND, Quota.Feature.HEIGHT,
                    Quota.Feature.THREE_LEGS));
            case X_SBEND_CREEK, X_SBEND_POND -> f.addAll(EnumSet.of(Quota.Feature.WATER, Quota.Feature.THREE_LEGS));
            case X_SBEND_ISLAND -> f.addAll(EnumSet.of(Quota.Feature.HEIGHT, Quota.Feature.THREE_LEGS));
            case X_LONG_DOGLEG_TREES -> f.addAll(EnumSet.of(Quota.Feature.TREES, Quota.Feature.TWO_LEGS));
            case X_LONG_DOGLEG_POND -> f.addAll(EnumSet.of(Quota.Feature.WATER, Quota.Feature.TWO_LEGS));
            case X_LONG_DOGLEG_ISLAND -> f.addAll(EnumSet.of(Quota.Feature.HEIGHT, Quota.Feature.TWO_LEGS));
            case X_LAYUP -> f.addAll(EnumSet.of(Quota.Feature.LAYUP, Quota.Feature.WATER, Quota.Feature.THREE_LEGS));
            default -> throw new IllegalStateException("no features for " + this);
        }
        return f;
    }

    /**
     * Draw this recipe into the plot of {@code grid}'s size whose min corner is ({@code plotX},
     * {@code plotZ}), the turf top at {@code turfY}, with its numbers from {@code r}.
     *
     * @param tier    the hole's tier (E, M or H)
     * @param shorter lengths from the shorter half of their ranges (attempts 4-11)
     * @param dry     Tiny Golf: never water in play
     * @throws Draft.Redraw when this draw doesn't fit (the attempt fails)
     */
    HoleLayout draw(GenRandom r, char tier, PlotGrid grid, int plotX, int plotZ, int turfY, boolean shorter,
                    boolean dry) throws Draft.Redraw {
        Draft d = new Draft(r, tier, grid, shorter, dry);
        HoleLayout drawn;
        try {
            drawn = drawn(d, r, plotX, plotZ, turfY);
        } catch (IllegalStateException e) {
            // a piece that doesn't fit this draw's numbers (a wall the flight rule would raise too high, a
            // tree off the plot): this attempt fails, as the stored attempt never did
            throw new Draft.Redraw(this + " " + d.tier + ": " + e.getMessage());
        }
        Set<Quota.Feature> want = features(tier, dry);
        if (!want.equals(drawn.features())) {
            throw new IllegalStateException(this + " " + tier + " drew " + drawn.features() + ", not " + want);
        }
        return drawn;
    }

    private HoleLayout drawn(Draft d, GenRandom r, int plotX, int plotZ, int turfY) throws Draft.Redraw {
        char tier = d.tier;
        boolean dry = d.dry;
        GenCopy.TeeFeature sign = GenCopy.TeeFeature.NONE;
        boolean mirror;
        String what;
        int mid = (d.sx - 1) / 2;
        switch (this) {
            case S_STRAIGHT -> {
                int length = d.len(8, 12);
                int off = d.pick(-1, 1);
                mirror = r.nextBoolean();
                d.first(mid, length, 2);
                d.lay(off);
                what = length + " long";
            }
            case S_BUMPERS -> {
                int length = d.len(10, 12);
                int off = d.pick(-1, 1);
                int rocks = d.pick(1, 2);
                mirror = r.nextBoolean();
                d.first(mid, length, 2);
                d.lay(off);
                d.bumpers(0, rocks);
                what = length + " long";
            }
            case S_HUMP -> {
                int length = d.len(10, 12);
                int top = 2;
                int off = d.pick(-1, 1);
                mirror = r.nextBoolean();
                d.first(mid, length, 2);
                d.lay(off);
                d.hump(0, d.pick(d.from(0), d.to(0) - top - 1), top);
                sign = GenCopy.TeeFeature.HILL;
                what = length + " long";
            }
            case S_SAND -> {
                int length = d.len(10, 12);
                int before = d.pick(3, 4);
                int off = d.pick(-1, 1);
                mirror = r.nextBoolean();
                d.first(mid, length, 2);
                d.lay(off);
                d.sand(0, length - before - 2, 3);
                sign = GenCopy.TeeFeature.SAND;
                what = length + " long";
            }
            case S_TREE -> {
                int length = d.len(10, 12);
                int off = d.pick(-2, 2);
                mirror = r.nextBoolean();
                d.first(mid, length, 3);
                d.lay(off);
                d.trees(0, 1);
                sign = GenCopy.TeeFeature.TREES;
                what = length + " long";
            }
            case S_RAMP -> {
                int approach = d.pick(4, 6);
                int onGreen = d.len(4, 6);
                int off = d.pick(-1, 1);
                mirror = r.nextBoolean();
                d.first(mid, approach + onGreen, 2);
                d.lay(off);
                d.rampGreen(approach);
                what = (approach + onGreen) + " long, the cup " + onGreen + " on the green";
            }
            case S_ISLAND -> {
                int approach = d.len(5, 9);
                int ox = d.pick(-1, 1);
                mirror = r.nextBoolean();
                d.first(mid, approach + 3, 2);
                d.lay(ox);
                d.islandGreen();
                what = "approach " + approach;
            }
            case S_POND_VIEW -> {
                int length = d.len(10, 12);
                int off = d.pick(-1, 1);
                int long_ = d.pick(6, 10);
                mirror = r.nextBoolean();
                Draft.Leg l = d.first(mid - 2, length, 3);
                d.lay(off);
                view(d, l, long_);
                what = length + " long";
            }
            case M_SAND -> {
                boolean sunken = !d.easy();
                int length = d.len(16, 22);
                int rows = sunken ? d.pick(3, 4) : 3;
                int off = d.pick(-1, 1);
                mirror = r.nextBoolean();
                d.first(mid, length, sunken ? 3 : 2);
                d.lay(off);
                int at = d.landing(rows);
                d.sand(0, at >= 0 ? at : length - d.pick(3, 5) - rows + 1, rows);
                sign = GenCopy.TeeFeature.SAND;
                what = length + " long";
            }
            case M_HUMP -> {
                int length = d.len(17, 22);
                int top = d.pick(2, 3);
                int off = d.pick(-1, 1);
                mirror = r.nextBoolean();
                d.first(mid, length, 2);
                d.lay(off);
                d.hump(0, d.pick(d.from(0), Math.min(9, d.to(0) - top - 1)), top);
                sign = GenCopy.TeeFeature.HILL;
                what = length + " long";
            }
            case M_BUMPERS -> {
                int length = d.len(16, 20);
                int off = d.pick(-1, 1);
                int rocks = d.pick(1, 2);
                mirror = r.nextBoolean();
                d.first(mid, length, 2);
                d.lay(off);
                d.bumpers(0, rocks);
                what = length + " long";
            }
            case M_HILL -> {
                int length = d.len(16, 22);
                int crest = d.pick(3, 6);
                int off = d.pick(-2, 2);
                mirror = r.nextBoolean();
                d.first(mid, length, 3);
                d.lay(off);
                int last = Math.min(7, length - crest - 6);
                d.hill(0, d.pick(d.from(0), Math.max(d.from(0), last)), crest);
                sign = GenCopy.TeeFeature.HILL;
                what = length + " long";
            }
            case M_CREEK -> {
                int length = d.len(16, 22);
                int across = d.pick(3, 5);
                int bridge = d.pick(-2, 2);
                int off = d.pick(-2, 2);
                mirror = r.nextBoolean();
                d.first(mid, length, 3);
                d.lay(off);
                int last = length - across - 5;
                d.creek(0, d.pick(Math.max(d.from(0), 6), Math.max(Math.max(d.from(0), 6), last)), across, bridge);
                sign = GenCopy.TeeFeature.WATER;
                what = length + " long";
            }
            case M_POND -> {
                int length = d.len(16, 22);
                int off = d.pick(-1, 1);
                int long_ = d.pick(6, 10);
                int across = d.easy() ? 3 : d.pick(3, 4);
                int side = r.nextBoolean() ? 1 : -1;
                mirror = r.nextBoolean();
                if (d.easy()) {
                    Draft.Leg l = d.first(mid - 2, length, 3);
                    d.lay(off);
                    view(d, l, long_);
                    what = length + " long";
                } else {
                    d.first(mid, length, 3);
                    d.lay(off);
                    int last = length - 5;
                    int len = Math.min(long_, last - 6 + 1);
                    d.pond(0, d.pick(6, Math.max(6, last - len + 1)), len, across, side);
                    sign = GenCopy.TeeFeature.WATER;
                    what = length + " long";
                }
            }
            case M_TREES -> {
                boolean hard = d.hard();
                int length = d.len(16, 22);
                int off = hard ? d.pick(-1, 1) : d.pick(-2, 2);
                int want = d.easy() ? 1 : hard ? d.pick(2, 3) : d.pick(1, 2);
                mirror = r.nextBoolean();
                d.first(mid, length, 3);
                d.lay(off);
                d.trees(0, want);
                sign = GenCopy.TeeFeature.TREES;
                what = length + " long";
            }
            case M_ICE -> {
                int length = d.len(18, 24);
                int ice = d.pick(4, 8);
                int brake = d.pick(2, 3);
                int off = r.nextBoolean() ? 1 : -1;
                mirror = r.nextBoolean();
                d.first(mid, length, 2);
                d.lay(off);
                d.ice(0, d.pick(d.from(0), Math.max(d.from(0), d.to(0) - ice - brake + 1)), ice, brake);
                what = length + " long";
            }
            case M_DOGLEG -> {
                int up = d.len(10, 14);
                int across = d.len(6, 10);
                int off = d.pick(-1, 1);
                mirror = r.nextBoolean();
                d.first(d.centred(across, 3), up, 2);
                d.then(1, 0, across, 2);
                d.lay(off);
                d.bank(0);
                sign = d.legSign(mirror);
                what = up + " up, " + across + " across";
            }
            case M_DOGLEG_DOWN -> {
                int up = d.len(10, 14);
                int across = d.len(6, 10);
                int off = d.pick(-1, 1);
                mirror = r.nextBoolean();
                d.first(d.centred(across, 4), up, 2);
                d.then(1, 0, across, 2);
                d.lay(off);
                d.dropAtElbow();
                sign = GenCopy.TeeFeature.DOGLEG_DOWN;
                what = up + " up, dropping, " + across + " across";
            }
            case M_DOGLEG_TREES -> {
                int up = d.len(13, 15);
                int across = d.len(6, 9);
                int off = d.pick(-1, 1);
                mirror = r.nextBoolean();
                d.first(d.centred(across, 4), up, 3);
                d.then(1, 0, across, 2);
                d.lay(off);
                d.trees(0, 1);
                sign = GenCopy.TeeFeature.TREES;
                what = up + " up, " + across + " across";
            }
            case M_TERRACES -> {
                int top = d.pick(6, 8);
                int middle = d.pick(9, 10);
                int after = d.len(7, 11);
                boolean pond = d.hard() && !dry;
                int off = pond ? -1 : d.pick(-1, 1);
                mirror = r.nextBoolean();
                d.first(mid, top - 2 + middle + after, 2);
                d.lay(off);
                d.terraces(top, middle, pond);
                sign = GenCopy.TeeFeature.TERRACES;
                what = "the cup " + after + " past the terraces";
            }
            case M_TWO_WAY -> {
                mirror = r.nextBoolean();
                what = twoWay(d, mid);
                sign = GenCopy.TeeFeature.TWO_WAY;
            }
            case M_VOLCANO -> {
                mirror = r.nextBoolean();
                what = volcano(d, mid);
                sign = GenCopy.TeeFeature.VOLCANO;
            }
            case L_LAYUP, L_LAYUP_SAND -> {
                boolean sand = this == L_LAYUP_SAND;
                int up = d.pick(8, 10);
                int across = d.len(18, 24);
                int wide = d.pick(4, 6);
                int deep = d.pick(3, 4);
                int off = d.pick(-1, 1);
                mirror = r.nextBoolean();
                d.first(d.centred(across, 4), up, 2);
                d.then(1, 0, across, 2);
                d.lay(off);
                d.layup(wide, deep, sand);
                sign = d.tee;
                what = up + " up, " + across + " across";
            }
            case L_DOGLEG_SAND_POND -> {
                int up = d.len(18, 20);
                int across = d.len(14, 16);
                int rows = d.pick(3, 4);
                int off = d.pick(-1, 1);
                int long_ = d.pick(6, 8);
                int wide = d.pick(3, 4);
                mirror = r.nextBoolean();
                d.first(d.centred(across, 5), up, 3);
                d.then(1, 0, across, 2);
                d.lay(off);
                int at = d.landing(rows);
                if (at < 0) {
                    throw new Draft.Redraw("no landing zone for sand");
                }
                d.sand(0, at, rows);
                int last = across - 5;
                int len = Math.min(long_, last - d.from(1) + 1);
                d.pond(1, d.pick(d.from(1), Math.max(d.from(1), last - len + 1)), len, wide, -1);
                sign = GenCopy.TeeFeature.WATER;
                what = up + " up, " + across + " across";
            }
            case L_DOGLEG_TREES_GREEN -> {
                int up = d.len(16, 20);
                int across = d.len(12, 16);
                int want = d.hard() ? 2 : d.pick(1, 2);
                int off = d.pick(-1, 1);
                int onGreen = d.pick(4, 6);
                mirror = r.nextBoolean();
                d.first(d.centred(across, 4), up, 3);
                d.then(1, 0, across, 2);
                d.lay(off);
                d.trees(0, want);
                d.rampGreen(across - onGreen);
                sign = GenCopy.TeeFeature.TREES;
                what = up + " up, " + across + " across";
            }
            case L_DOGLEG_HILL_SAND -> {
                int up = d.len(17, 20);
                int across = d.len(13, 16);
                int crest = d.pick(3, 5);
                int rows = d.pick(3, 4);
                int off = d.pick(-1, 1);
                mirror = r.nextBoolean();
                d.first(d.centred(across, 4), up, 3);
                d.then(1, 0, across, 3);
                d.lay(off);
                d.hill(0, d.pick(d.from(0), Math.max(d.from(0), Math.min(7, d.to(0) - crest - 3))), crest);
                d.sand(1, across - d.pick(3, 4) - rows + 1, rows);
                sign = GenCopy.TeeFeature.HILL;
                what = up + " up, " + across + " across";
            }
            case L_DOGLEG_DOWN -> {
                int up = d.len(16, 20);
                int across = d.len(12, 16);
                int off = d.pick(-1, 1);
                mirror = r.nextBoolean();
                d.first(d.centred(across, 4), up, 2);
                d.then(1, 0, across, 2);
                d.lay(off);
                d.dropAtElbow();
                sign = GenCopy.TeeFeature.DOGLEG_DOWN;
                what = up + " up, dropping, " + across + " across";
            }
            case L_SBEND, L_SBEND_SAND -> {
                boolean sand = this == L_SBEND_SAND;
                int first = d.len(10, 12);
                int across = d.len(8, 10);
                int lastLeg = d.len(10, 12);
                int rows = sand ? d.pick(3, 4) : 0;
                int off = d.pick(-1, 1);
                mirror = r.nextBoolean();
                d.first(d.centred(across, 4), first, 2);
                d.then(1, 0, across, 2);
                d.then(0, 1, lastLeg, sand ? 3 : 2);
                d.lay(off);
                d.bank(0);
                d.bank(1);
                if (sand) {
                    int fit = Math.min(rows, d.to(2) - d.from(2) + 1);
                    d.sand(2, d.pick(d.from(2), Math.max(d.from(2), d.to(2) - fit + 1)), fit);
                }
                sign = GenCopy.TeeFeature.THREE_LEGS;
                what = first + " up, " + across + " across, " + lastLeg + " up";
            }
            case L_TERRACES_CREEK -> {
                int top = d.pick(6, 7);
                int middle = d.pick(9, 10);
                int gap = d.pick(4, 6);
                int across = d.pick(3, 4);
                int after = d.len(8, 12);
                int bridge = d.pick(-2, 2);
                int off = d.pick(-2, 2);
                mirror = r.nextBoolean();
                int creek = top - 2 + middle + gap;
                d.first(mid, creek + across + after, 3);
                d.lay(off);
                d.terraces(top, middle, false);
                d.creek(0, creek, across, bridge);
                sign = GenCopy.TeeFeature.TERRACES;
                what = (creek + across + after) + " long";
            }
            case L_STRAIGHT_HUMP_SAND -> {
                int length = d.len(28, 34);
                int top = d.pick(2, 3);
                int rows = d.pick(3, 4);
                int off = d.pick(-1, 1);
                mirror = r.nextBoolean();
                d.first(mid, length, 3);
                d.lay(off);
                int at = d.landing(rows);
                if (at < 0) {
                    throw new Draft.Redraw("no landing zone for sand");
                }
                d.sand(0, at, rows);
                d.hump(0, d.pick(at + rows + 3, Math.max(at + rows + 3, d.to(0) - top - 4)), top);
                sign = GenCopy.TeeFeature.SAND;
                what = length + " long";
            }
            case X_SBEND_SAND, X_SBEND_CREEK, X_SBEND_POND, X_SBEND_ISLAND, X_SBEND_ICE -> {
                boolean island = this == X_SBEND_ISLAND;
                boolean ice = this == X_SBEND_ICE;
                int first = island || ice || this == X_SBEND_CREEK ? d.len(14, 16) : d.len(16, 18);
                int across = island ? d.len(10, 12) : ice ? d.len(16, 18) : d.len(12, 14);
                int lastLeg = island || ice ? d.len(12, 14) : d.len(14, 16);
                int off = d.pick(-1, 1);
                boolean wideMiddle = this == X_SBEND_SAND;
                boolean wideLast = this == X_SBEND_CREEK;
                mirror = r.nextBoolean();
                d.first(d.centred(across, 7), first, 2);
                d.then(1, 0, across, wideMiddle ? 3 : 2);
                d.then(0, 1, lastLeg, wideLast ? 3 : 2);
                d.lay(off);
                d.bank(0);
                d.bank(1);
                sign = GenCopy.TeeFeature.THREE_LEGS;
                switch (this) {
                    case X_SBEND_SAND -> {
                        int rows = Math.min(d.pick(3, 4), d.to(1) - d.from(1) + 1);
                        d.sand(1, d.pick(d.from(1), Math.max(d.from(1), d.to(1) - rows + 1)), rows);
                        d.rampGreen(lastLeg - d.pick(4, 5));
                    }
                    case X_SBEND_CREEK -> {
                        int wide = d.pick(3, 4);
                        int last = lastLeg - wide - 5;
                        d.creek(2, d.pick(d.from(2), Math.max(d.from(2), last)), wide, d.pick(-2, 2));
                        sign = GenCopy.TeeFeature.WATER;
                    }
                    case X_SBEND_POND -> {
                        int len = d.pick(6, 9);
                        d.pond(0, d.pick(d.from(0), Math.max(d.from(0), d.to(0) - len + 1)), len, d.pick(3, 4), -1);
                        sign = GenCopy.TeeFeature.WATER;
                    }
                    case X_SBEND_ISLAND -> d.islandGreen();
                    default -> d.ice(1, d.from(1), d.pick(4, 6), 2);
                }
                what = first + " up, " + across + " across, " + lastLeg + " up";
            }
            case X_LONG_DOGLEG_TREES, X_LONG_DOGLEG_POND, X_LONG_DOGLEG_ISLAND -> {
                boolean island = this == X_LONG_DOGLEG_ISLAND;
                int up = island ? d.len(20, 24) : d.len(22, 26);
                int across = island ? d.len(16, 18) : d.len(18, 20);
                int off = d.pick(-1, 1);
                boolean trees = this == X_LONG_DOGLEG_TREES;
                mirror = r.nextBoolean();
                d.first(d.centred(across, 4), up, trees ? 3 : 2);
                d.then(1, 0, across, 2);
                d.lay(off);
                d.bank(0);
                sign = d.legSign(mirror);
                switch (this) {
                    case X_LONG_DOGLEG_TREES -> {
                        d.trees(0, 2);
                        sign = GenCopy.TeeFeature.TREES;
                    }
                    case X_LONG_DOGLEG_POND -> {
                        int len = d.pick(6, 9);
                        int last = across - 5;
                        d.pond(1, d.pick(d.from(1), Math.max(d.from(1), last - len + 1)), len, d.pick(3, 4), -1);
                        sign = GenCopy.TeeFeature.WATER;
                    }
                    default -> d.islandGreen();
                }
                what = up + " up, " + across + " across";
            }
            case X_HAIRPIN -> {
                int up = d.len(18, 22);
                int across = d.pick(8, 10);
                int down = d.len(12, 16);
                int off = d.pick(-1, 1);
                mirror = r.nextBoolean();
                d.first(d.centred(across, 4), up, 2);
                d.then(1, 0, across, 2);
                d.then(0, -1, down, 2);
                d.lay(off);
                d.bank(0);
                d.bank(1);
                sign = GenCopy.TeeFeature.THREE_LEGS;
                what = up + " up, " + across + " across, " + down + " back down";
            }
            case X_LAYUP -> {
                int up = d.pick(8, 10);
                int across = d.len(14, 18);
                int lastLeg = d.len(14, 18);
                int wide = d.pick(4, 6);
                int deep = d.pick(3, 4);
                int off = d.pick(-1, 1);
                mirror = r.nextBoolean();
                d.first(d.centred(across, 4), up, 2);
                d.then(1, 0, across, 2);
                d.then(0, 1, lastLeg, 2);
                d.lay(off);
                d.bank(1);
                d.layup(wide, deep, false);
                sign = d.tee;
                what = up + " up, " + across + " across, " + lastLeg + " up";
            }
            default -> throw new IllegalStateException("unknown recipe " + this);
        }
        String describe = cls.letter() + " " + name() + " " + what + (d.words.isEmpty() ? "" : ", "
                + String.join(", ", d.words)) + (mirror ? ", mirrored" : "");
        return d.s.render(null, name(), mirror, plotX, plotZ, turfY, describe, d.features(), sign);
    }

    /**
     * The proven fallback for a hole of {@code cls} (GOLF-V4-SPEC §3.6, attempt 12): plain 5-wide,
     * flat, never mirrored, nothing random — SAFE_S a straight of 10, SAFE_M a straight of 20, SAFE_L a
     * dogleg of 20 up and 16 across, SAFE_X an S-bend of 18, 14 and 16. A test proves each in every plot
     * of every golf half.
     */
    static HoleLayout fallback(LengthClass cls, PlotGrid grid, int plotX, int plotZ, int turfY) {
        Draft d = new Draft(new GenRandom(0), 'S', grid, false, false);
        int mid = (d.sx - 1) / 2;
        String what;
        try {
            switch (cls) {
                case S -> {
                    d.first(mid, 10, 2);
                    what = "straight 10";
                }
                case M -> {
                    d.first(mid, 20, 2);
                    what = "straight 20";
                }
                case L -> {
                    d.first((d.sx - 1 - 16) / 2, 20, 2);
                    d.then(1, 0, 16, 2);
                    what = "dogleg 20 up, 16 across";
                }
                default -> {
                    d.first((d.sx - 1 - 14) / 2, 18, 2);
                    d.then(1, 0, 14, 2);
                    d.then(0, 1, 16, 2);
                    what = "S-bend 18 up, 14 across, 16 up";
                }
            }
            d.lay(0);
        } catch (Draft.Redraw e) {
            throw new IllegalStateException("the " + cls + " fallback doesn't fit a plot of " + grid, e);
        }
        return d.s.render(null, "SAFE_" + cls, false, plotX, plotZ, turfY, cls.letter() + " SAFE_" + cls + " " + what,
                d.features(), d.legSign(false));
    }

    /**
     * Easy's pond to look at (scenery, outside the physics grid), beyond the side wall of leg
     * {@code l}: 3 across, {@code length} long, two columns clear of the bounds, rimmed with moss.
     */
    private static void view(Draft d, Draft.Leg l, int length) throws Draft.Redraw {
        int cupZ = l.z(l.length(), 0);
        int start = Math.min(l.z(4, 0) + d.pick(0, 6), cupZ + 2 - length);
        int x0 = l.x(0, l.half()) + 4;
        if (x0 + 3 >= d.sx || start < 1) {
            throw new Draft.Redraw("no room for a pond to look at");
        }
        d.s.decorativePond(x0, x0 + 2, start, start + length - 1);
        d.features.add(Quota.Feature.VIEW);
        d.words.add("a pond to look at");
    }

    /**
     * The two-way hole (Adventure Golf's TWO_WAY, made longer): a wide lane that splits round a 3 x 6
     * tree island — a short way 3-4 wide past a pond (sand first on Hard) and a long dry way 4-5
     * wide — meeting again before the cup. Drawn on its own columns from {@code mid} - 7.
     */
    private static String twoWay(Draft d, int mid) throws Draft.Redraw {
        if (d.dry) {
            throw new Draft.Redraw("a pond on a dry course");
        }
        GenRandom r = d.r;
        int wide = r.nextInt(4, 5);
        int narrow = r.nextInt(3, 4);
        if (wide + narrow > 8) {
            narrow = 3;
        }
        int at = d.len(9, 12);
        int after = d.len(5, 8);
        int cupOff = r.nextInt(0, narrow - 2);
        String wood = Palette.WOODS.get(r.nextInt(Palette.WOODS.size()));
        int x0 = mid - 7;
        int island = x0 + wide;
        int s0 = island + 3;
        int x1 = s0 + narrow - 1;
        int cupZ = at + 5 + after;
        if (x0 < 1 || x1 + 4 > d.sx - 2 || cupZ + 2 > d.sz - 2) {
            throw new Draft.Redraw("no room for two ways");
        }
        HoleTemplate.Sketch s = d.s;
        s.lane(x0, x1, 2, cupZ + 1, 0);
        s.island(island, island + 2, at, at + 5);
        s.trunk(island + 1, at + 1, wood);
        s.trunk(island + 1, at + 4, wood);
        for (int x = island - 1; x <= island + 3; x++) {
            for (int z = at; z <= at + 5; z++) {
                s.leaf(x, 3, z, wood);
            }
        }
        for (int x = island; x <= island + 2; x++) {
            for (int z = at + 1; z <= at + 4; z++) {
                s.leaf(x, 4, z, wood);
            }
        }
        if (d.hard()) {
            s.floor(s0, x1, at, at + 1, HoleTemplate.Sketch.SAND);
            s.water(x1 + 1, x1 + 3, at + 2, at + 4);
            d.features.add(Quota.Feature.SAND);
        } else {
            s.water(x1 + 1, x1 + 3, at + 1, at + 3);
        }
        s.tee(s0 + 1, 3);
        s.cup(s0 + cupOff, cupZ);
        d.features.add(Quota.Feature.TREES);
        d.features.add(Quota.Feature.WATER);
        return "a " + narrow + "-wide short way past a pond" + (d.hard() ? " and sand" : "") + ", a " + wide
                + "-wide long way, the cup " + (cupZ - 3) + " on";
    }

    /**
     * The volcano green (Adventure Golf's VOLCANO): a 5-wide approach of 8-12, a plateau a block up
     * that fills the hole's width, a terrace a block and a half up, the cup on a 5 x 5 summit two up.
     */
    private static String volcano(Draft d, int mid) throws Draft.Redraw {
        GenRandom r = d.r;
        int approach = d.len(6, 9);
        int ox = r.nextInt(-1, 1);
        int oz = r.nextInt(-1, 1);
        int a = 1 + approach;
        int summit = a + 9;
        if (mid - 6 < 1 || mid + 6 > d.sx - 2 || summit + 8 > d.sz - 2) {
            throw new Draft.Redraw("no room for a volcano");
        }
        HoleTemplate.Sketch s = d.s;
        s.lane(mid - 2, mid + 2, 2, a, 0);
        s.lane(mid - 6, mid + 6, a + 1, summit + 8, 2);
        s.lane(mid - 1, mid + 1, a + 1, a + 1, 1);
        s.lane(mid - 4, mid + 4, summit - 2, summit + 6, 3);
        s.lane(mid - 2, mid + 2, summit, summit + 4, 4);
        s.tee(mid, 3);
        s.cup(mid + ox, summit + 2 + oz);
        d.features.add(Quota.Feature.HEIGHT);
        d.features.add(Quota.Feature.BIG_DROP);
        return "approach " + approach + ", the summit " + (summit - 3) + " on";
    }
}
