package com.dierks.homecraft.games.gen.golf;

import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.GenRandom;
import com.dierks.homecraft.games.gen.api.Palette;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The shapes a generated golf hole can take, and the rasteriser that draws one as blocks
 * (GEN-SPEC §4.3, Course Variety §3.2).
 *
 * <p><b>Why templates, not free-form lanes.</b> Every hole must be solvable, readable by a small
 * child, and fit a 20 x 40 plot. A handful of shapes a child recognises (a straight, a bend, a
 * ramp up to a raised green, a run of ice, a sand trap, a pond to putt past, a hill to roll down)
 * with seeded lengths, widths, feature offsets and a mirror give plenty of variety, while the
 * physics — not the template — decides par: every hole is solved on its blocks by the expert
 * search and checked against the sloppy player of {@link KidPolicy}. A template that comes out too
 * hard or too easy for its tier is simply drawn again from a new seed.
 *
 * <p><b>Levels.</b> A lane cell's height is a level in half blocks above T: -1 is a sunken bunker
 * (a sand slab, its top T - 0.5), 0 the turf, 1 a slab, 2 a block up, 3 a slab on that, 4 two up.
 * Raised columns are solid from T - 1 to their top; cups go on whole-block levels (0, 2 or 4); a
 * raised slab sits in the middle of a lane flanked by full steps (the {@link #RAMP} rule), never
 * beside a wall; and no lip between lane cells is more than a block.
 *
 * <p><b>The drawing rules</b>, the same for every template (a lane runs along +Z from its tee, and
 * is mirrored in X or not):
 * <ul>
 *   <li>The turf's top is T; floor blocks sit at T - 1 on nothing. Turf is lime and green concrete
 *       in 2 x 2 checks, so distances are easy to read; sand is smooth sandstone.</li>
 *   <li>A pond is still water at T - 1 over blue concrete, walled round its outside; never lane.</li>
 *   <li>Walls go round every lane cell and pond (diagonals too), solid from T - 1. The templates
 *       GEN-SPEC first had keep one height all round: a block above their highest lane. Adventure
 *       Golf's templates give each column the least height {@link GolfValidatorV3} allows there: a
 *       block above the lane beside it, raised where a ball flying off a lip could reach it
 *       ({@link LaneMap#flightReach}), and never more than two above the lowest lane beside it.</li>
 *   <li>Bumpers are slime blocks in walls or as single-block rocks, never in the floor. Trees in
 *       play are a log trunk from T - 1 (its top T + 3) under leaves at T + 3 and T + 4, well clear
 *       of the tee and the cup so the flag is always seen.</li>
 *   <li>The cup is sunken: the lane block at the cup is left out and black concrete goes one
 *       lower, with a 3 x 3 white ring round it, a white tee, and red wool floating three above.
 *       Terrace edges are light blue glass: the "glass waterfall".</li>
 *   <li>The tee sign stands on the wall right behind the tee.</li>
 * </ul>
 */
public enum HoleTemplate {

    /** Width 5, the cup 10-18 ahead, centred or one off. */
    STRAIGHT("E"),
    /** A straight (10-16) with one or two slime rocks and slime side walls. */
    BUMPERS("E"),
    /** A row of slabs, then a full step up to a raised green with the cup (longer for Medium). */
    RAMP("EM"),
    /** 10-14 up, a right angle, 6-10 across; slime at the elbow for a bank shot. */
    DOGLEG("M"),
    /** Turf, 4-8 of packed ice, a 2-3 soul-soil brake, then the cup one off the middle. */
    ICE_RUN("M"),
    /** A 5 x 5 raised green at the end, walled except for its ramp entrance (2 wide on Hard). */
    ISLAND("MH"),
    /** 7-9 up, 7-9 across, 5-7 up again: two opposite right angles with slime corners. */
    S_BEND("H"),
    /**
     * Width 3, 10-15 of ice, then a right angle into a 3-wide pocket with the cup and a rock near
     * it. (A straight 3-wide ice lane always had a one-putt bank line, too easy for Hard.)
     */
    NARROW_ICE("H"),
    /** The fallback: width 5, 10 long, flat. Always passes the validator (tested). */
    SAFE_STRAIGHT(""),
    /**
     * Easy: a 5-wide straight (12-16) with a flush 3 x 3 patch of sand 3-5 before the cup, one off
     * the middle. Medium: 7 wide, a sunken bunker 3 wide and 3-4 long across the middle with turf
     * 2 wide either side, the cup 4-8 past it.
     */
    SAND_TRAP("EM"),
    /** 5 wide: a slab up, 2-3 rows a block up, a slab down, then the cup 5-9 on. */
    HUMP("E"),
    /** 7 wide: slab steps up to a crest a block up, 3-6 long, a 1-block lip down, the cup 5-9 past it. */
    HILL("M"),
    /**
     * The cup on a 5 x 5 summit two blocks up, ringed by a terrace a block and a half up (2 wide),
     * on a plateau a block up that fills the hole, reached from a 5-wide approach (8-12) by a 3-wide
     * slab stair. Too soft stops on a terrace; too hard rolls over the far side.
     */
    VOLCANO("H"),
    /**
     * The tee on a terrace two blocks up (6-8), a drop to one up (9-10), a drop to the turf and the
     * cup 4-8 beyond; the terraces' edges are glass. Hard: a pond beside the bottom terrace.
     */
    TERRACES("MH"),
    /**
     * The dogleg with its first leg a block up, dropping at the elbow. Medium: the slime bank
     * outside the elbow. Hard: a sunken 3 x 3 bunker there instead.
     */
    DOGLEG_DOWN("MH"),
    /** A straight (7 wide, 5 on Hard) with trees in play: 1 on Easy, 1-2 on Medium, 2-3 on Hard. */
    TREE_GARDEN("EMH"),
    /**
     * 6-7 wide. Easy: a pond to look at beyond the side wall, well out of reach. Medium: a pond 3-4
     * wide and 6-10 long along one side, no wall between it and the lane.
     */
    POND_SIDE("EM"),
    /** A 5-wide approach, a 3-wide causeway with water both sides, a 7 x 7 green with water round it. */
    ISLAND_POND("H"),
    /**
     * A wide lane that splits round a 3 x 6 tree island: a short way (3-4 wide) past a pond (sand
     * first on Hard) and a long dry way (4-5 wide); they meet again before the cup.
     */
    TWO_WAY("MH"),
    /** 7 wide: a creek 3-5 across the lane with a 3-wide turf bridge, the cup 5-9 past it. */
    CREEK("M");

    /** A plot's size in blocks (x, z). */
    public static final int PLOT_X = 20;
    public static final int PLOT_Z = 40;

    private final String tiers;

    HoleTemplate(String tiers) {
        this.tiers = tiers;
    }

    /** Whether this template can be drawn for a hole of this tier ('E', 'M' or 'H'). */
    public boolean fits(char tier) {
        return tiers.indexOf(Character.toUpperCase(tier)) >= 0;
    }

    /** The templates for a tier, in their fixed order. */
    public static List<HoleTemplate> forTier(char tier) {
        List<HoleTemplate> out = new ArrayList<>();
        for (HoleTemplate t : values()) {
            if (t.fits(tier)) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * What a hole of this template has when drawn for {@code tier}, as the variety quota counts it
     * ({@link Quota}). Water is in play only on Medium and Hard: Easy's pond side is a view.
     */
    public Set<Quota.Feature> features(char tier) {
        boolean hard = Character.toUpperCase(tier) == 'H';
        boolean easy = Character.toUpperCase(tier) == 'E';
        EnumSet<Quota.Feature> out = EnumSet.noneOf(Quota.Feature.class);
        switch (this) {
            case RAMP, ISLAND, HUMP -> out.add(Quota.Feature.HEIGHT);
            case SAND_TRAP -> out.add(Quota.Feature.SAND);
            case HILL, VOLCANO -> {
                out.add(Quota.Feature.HEIGHT);
                out.add(Quota.Feature.BIG_DROP);
            }
            case TERRACES -> {
                out.add(Quota.Feature.HEIGHT);
                out.add(Quota.Feature.BIG_DROP);
                if (hard) {
                    out.add(Quota.Feature.WATER);
                }
            }
            case DOGLEG_DOWN -> {
                out.add(Quota.Feature.HEIGHT);
                out.add(Quota.Feature.BIG_DROP);
                if (hard) {
                    out.add(Quota.Feature.SAND);
                }
            }
            case TREE_GARDEN -> out.add(Quota.Feature.TREES);
            case POND_SIDE -> out.add(easy ? Quota.Feature.VIEW : Quota.Feature.WATER);
            case ISLAND_POND, CREEK -> out.add(Quota.Feature.WATER);
            case TWO_WAY -> {
                out.add(Quota.Feature.TREES);
                out.add(Quota.Feature.WATER);
                if (hard) {
                    out.add(Quota.Feature.SAND);
                }
            }
            default -> {
                // the shapes GEN-SPEC first had, and the fallback: nothing the quota counts
            }
        }
        return out;
    }

    /** What the tee sign's last two lines say for a hole of this template drawn for {@code tier}. */
    public GenCopy.TeeFeature teeFeature(char tier) {
        return switch (this) {
            case SAND_TRAP -> GenCopy.TeeFeature.SAND;
            case HUMP, HILL -> GenCopy.TeeFeature.HILL;
            case VOLCANO -> GenCopy.TeeFeature.VOLCANO;
            case TERRACES -> GenCopy.TeeFeature.TERRACES;
            case DOGLEG_DOWN -> GenCopy.TeeFeature.DOGLEG_DOWN;
            case TREE_GARDEN -> GenCopy.TeeFeature.TREES;
            case POND_SIDE -> Character.toUpperCase(tier) == 'E' ? GenCopy.TeeFeature.NONE : GenCopy.TeeFeature.WATER;
            case ISLAND_POND, CREEK -> GenCopy.TeeFeature.WATER;
            case TWO_WAY -> GenCopy.TeeFeature.TWO_WAY;
            default -> GenCopy.TeeFeature.NONE;
        };
    }

    /**
     * Draw this template into the plot whose min corner is ({@code plotX}, {@code plotZ}), the turf
     * top at {@code turfY}, with its numbers drawn from {@code r}.
     *
     * @param tier the hole's tier ('E', 'M' or 'H'), for templates shared by two tiers
     */
    public HoleLayout draw(GenRandom r, char tier, int plotX, int plotZ, int turfY) {
        Sketch s = new Sketch();
        char t = Character.toUpperCase(tier);
        boolean hard = t == 'H';
        boolean easy = t == 'E';
        boolean mirror;
        // Every template draws its numbers in the same order for a seed; changing one changes
        // every later number, and so the golden plans (bump GolfPlanner.ALGO with it).
        String what;
        switch (this) {
            case STRAIGHT -> {
                int length = r.nextInt(10, 18);
                int off = r.nextInt(-1, 1);
                mirror = r.nextBoolean();
                straight(s, 7, 11, length, off);
                what = length + " long, cup " + signed(off);
            }
            case BUMPERS -> {
                int length = r.nextInt(10, 16);
                int off = r.nextInt(-1, 1);
                mirror = r.nextBoolean();
                straight(s, 7, 11, length, off);
                int rocks = r.nextInt(1, 2);
                int placed = 0;
                for (int i = 0; i < 12 && placed < rocks; i++) {
                    int x = r.nextInt(7, 11);
                    int z = r.nextInt(6, s.cupZ - 3);
                    if (s.canRock(x, z)) {
                        s.rock(x, z, true);
                        placed++;
                    }
                }
                s.slimeWalls(6, 6, 1, s.cupZ + 2);
                s.slimeWalls(12, 12, 1, s.cupZ + 2);
                what = length + " long, " + placed + " rock" + (placed == 1 ? "" : "s");
            }
            case RAMP -> {
                boolean medium = t != 'E';
                int approach = medium ? r.nextInt(6, 9) : r.nextInt(4, 6);
                int onGreen = medium ? r.nextInt(5, 9) : r.nextInt(3, 6);
                int off = medium ? r.nextInt(-1, 1) : 0;
                mirror = r.nextBoolean();
                int rampZ = 3 + approach;
                int cupZ = rampZ + onGreen;
                s.lane(7, 11, 2, rampZ - 1, 0);
                s.lane(7, 11, rampZ, cupZ + 1, 2);
                s.lane(8, 10, rampZ, rampZ, 1);
                s.tee(9, 3);
                s.cup(9 + off, cupZ);
                what = "ramp after " + approach + ", cup " + onGreen + " on";
            }
            case DOGLEG -> {
                int up = r.nextInt(10, 14);
                int across = r.nextInt(6, 10);
                int offZ = r.nextInt(-1, 1);
                mirror = r.nextBoolean();
                int elbowZ = 3 + up;
                s.lane(2, 6, 2, elbowZ + 2, 0);
                s.lane(2, 4 + across + 1, elbowZ - 2, elbowZ + 2, 0);
                s.tee(4, 3);
                s.cup(4 + across, elbowZ + offZ);
                s.slimeWalls(1, 7, elbowZ + 3, elbowZ + 3);
                s.slimeWalls(1, 1, elbowZ - 2, elbowZ + 3);
                what = up + " up, " + across + " across";
            }
            case ICE_RUN -> {
                int turf = r.nextInt(2, 4);
                int ice = r.nextInt(4, 8);
                int brake = r.nextInt(2, 3);
                int after = r.nextInt(3, 6);
                int off = r.nextBoolean() ? 1 : -1;
                mirror = r.nextBoolean();
                int ice0 = 3 + turf;
                int brake0 = ice0 + ice;
                int cupZ = brake0 + brake - 1 + after;
                straightTo(s, 7, 11, cupZ, off);
                s.floor(7, 11, ice0, brake0 - 1, Sketch.ICE);
                s.floor(7, 11, brake0, brake0 + brake - 1, Sketch.BRAKE);
                what = ice + " ice, " + brake + " brake";
            }
            case ISLAND -> {
                int approach = hard ? r.nextInt(6, 11) : r.nextInt(7, 11);
                int entrance = hard ? r.nextInt(8, 9) : 8;
                int width = hard ? 2 : 3;
                int ox = r.nextInt(-1, 1);
                int oz = r.nextInt(-1, 1);
                mirror = r.nextBoolean();
                int rampZ = 3 + approach;
                s.lane(7, 11, 2, rampZ - 1, 0);
                s.lane(7, 11, rampZ, rampZ + 5, 2);
                s.lane(entrance, entrance + width - 1, rampZ, rampZ, 1);
                s.tee(9, 3);
                s.cup(9 + ox, rampZ + 3 + oz);
                what = "green after " + approach + ", entrance " + width + " wide";
            }
            case S_BEND -> {
                int first = r.nextInt(7, 9);
                int across = r.nextInt(7, 9);
                int last = r.nextInt(5, 7);
                mirror = r.nextBoolean();
                int bendZ = 3 + first;
                int bendX = 4 + across;
                int cupZ = bendZ + last;
                s.lane(2, 6, 2, bendZ + 2, 0);
                s.lane(2, bendX + 2, bendZ - 2, bendZ + 2, 0);
                s.lane(bendX - 2, bendX + 2, bendZ - 2, cupZ + 1, 0);
                s.tee(4, 3);
                s.cup(bendX + r.nextInt(-1, 1), cupZ);
                s.slimeWalls(1, 7, bendZ + 3, bendZ + 3);
                s.slimeWalls(1, 1, bendZ - 2, bendZ + 3);
                s.slimeWalls(bendX - 3, bendX + 3, bendZ - 3, bendZ - 3);
                s.slimeWalls(bendX + 3, bendX + 3, bendZ - 3, bendZ + 2);
                what = first + " up, " + across + " across, " + last + " up";
            }
            case NARROW_ICE -> {
                int length = r.nextInt(10, 15);
                int pocket = r.nextInt(4, 6);
                mirror = r.nextBoolean();
                int top = 3 + length;
                s.lane(6, 8, 2, top + 1, 0);
                s.floor(6, 8, 5, top + 1, Sketch.ICE);
                s.lane(9, 8 + pocket + 1, top - 1, top + 1, 0);
                s.tee(7, 3);
                s.cup(8 + pocket, top);
                s.rock(9 + r.nextInt(0, pocket - 3), top + (r.nextBoolean() ? 1 : -1), false);
                what = length + " of ice, " + pocket + " across";
            }
            case SAFE_STRAIGHT -> {
                mirror = false;
                straight(s, 7, 11, 10, 0);
                what = "10 long";
            }
            case SAND_TRAP -> {
                s.adventure();
                if (easy) {
                    int length = r.nextInt(12, 16);
                    int before = r.nextInt(3, 5);
                    int side = r.nextBoolean() ? 1 : -1;
                    int off = r.nextInt(-1, 1);
                    mirror = r.nextBoolean();
                    int cupZ = 3 + length;
                    int end = cupZ - before;
                    s.lane(7, 11, 2, cupZ + 1, 0);
                    s.floor(8 + side, 10 + side, end - 2, end, Sketch.SAND);
                    s.tee(9, 3);
                    s.cup(9 + off, cupZ);
                    what = length + " long, sand " + before + " before the cup";
                } else {
                    int approach = r.nextInt(4, 7);
                    int length = r.nextInt(3, 4);
                    int beyond = r.nextInt(4, 8);
                    int off = r.nextInt(-1, 1);
                    mirror = r.nextBoolean();
                    int b0 = 3 + approach;
                    int cupZ = b0 + length - 1 + beyond;
                    s.lane(6, 12, 2, cupZ + 1, 0);
                    s.bunker(8, 10, b0, b0 + length - 1);
                    s.tee(9, 3);
                    s.cup(9 + off, cupZ);
                    what = "sunken bunker " + length + " long after " + approach + ", cup " + beyond + " past";
                }
            }
            case HUMP -> {
                s.adventure();
                int approach = r.nextInt(3, 6);
                int top = r.nextInt(2, 3);
                int after = r.nextInt(5, 9);
                int off = r.nextInt(-1, 1);
                mirror = r.nextBoolean();
                int up = 3 + approach;
                int down = up + top + 1;
                int cupZ = down + after;
                s.lane(7, 11, 2, cupZ + 1, 0);
                s.lane(7, 11, up, down, 2);
                s.lane(8, 10, up, up, 1);
                s.lane(8, 10, down, down, 1);
                s.tee(9, 3);
                s.cup(9 + off, cupZ);
                what = "hump after " + approach + ", " + top + " on top, cup " + after + " past";
            }
            case HILL -> {
                s.adventure();
                int approach = r.nextInt(3, 6);
                int crest = r.nextInt(3, 6);
                int after = r.nextInt(5, 9);
                int off = r.nextInt(-2, 2);
                mirror = r.nextBoolean();
                int up = 3 + approach;
                int lip = up + crest;
                int cupZ = lip + after;
                s.lane(6, 12, 2, cupZ + 1, 0);
                s.lane(6, 12, up, lip, 2);
                s.lane(7, 11, up, up, 1);
                s.tee(9, 3);
                s.cup(9 + off, cupZ);
                what = "crest " + crest + " long after " + approach + ", cup " + after + " past the lip";
            }
            case VOLCANO -> {
                s.adventure();
                int approach = r.nextInt(8, 12);
                int ox = r.nextInt(-1, 1);
                int oz = r.nextInt(-1, 1);
                mirror = r.nextBoolean();
                int a = 1 + approach; // the approach's last row
                int summit = a + 9; // so a ball off the summit can't fly to a wall beside the approach
                s.lane(7, 11, 2, a, 0);
                s.lane(3, 15, a + 1, summit + 8, 2);
                s.lane(8, 10, a + 1, a + 1, 1);
                s.lane(5, 13, summit - 2, summit + 6, 3);
                s.lane(7, 11, summit, summit + 4, 4);
                s.tee(9, 3);
                s.cup(9 + ox, summit + 2 + oz);
                what = "approach " + approach + ", the summit " + (summit - 3) + " on";
            }
            case TERRACES -> {
                s.adventure();
                int top = r.nextInt(6, 8);
                int middle = r.nextInt(9, 10);
                int after = r.nextInt(4, 8);
                int off = r.nextInt(-1, 1);
                int pond = hard ? r.nextInt(3, 4) : 0;
                mirror = r.nextBoolean();
                int z4 = 1 + top;
                int z2 = z4 + middle;
                int cupZ = z2 + after;
                if (pond > 0 && cupZ - (z2 + pond) < 4) {
                    off = -1; // 4 clear of the pond, which is on the other side
                }
                s.lane(7, 11, 2, cupZ + 1, 0);
                s.lane(7, 11, 2, z2, 2);
                s.lane(7, 11, 2, z4, 4);
                s.glass(7, 11, z4, z4);
                s.glass(7, 11, z2, z2);
                if (pond > 0) {
                    s.water(12, 14, z2 + 1, z2 + pond);
                }
                s.tee(9, 3);
                s.cup(9 + off, cupZ);
                what = "terraces " + top + " and " + middle + ", cup " + after + " past" + (pond > 0 ? ", a pond" : "");
            }
            case DOGLEG_DOWN -> {
                s.adventure();
                int up = r.nextInt(10, 14);
                int across = r.nextInt(6, 10);
                int offZ = r.nextInt(-1, 1);
                mirror = r.nextBoolean();
                int elbowZ = 3 + up;
                s.lane(2, 6, 2, elbowZ + 2, 0);
                s.lane(2, 4 + across + 1, elbowZ - 2, elbowZ + 2, 0);
                s.lane(2, 6, 2, elbowZ - 3, 2);
                s.tee(4, 3);
                s.cup(4 + across, elbowZ + offZ);
                if (hard) {
                    // the bunker in the elbow's outer corner, a turf rim round it: a wall beside the sand
                    // would have to stand 2.5 above it, as a ball off the lip could fly to it
                    s.lane(1, 6, elbowZ - 2, elbowZ + 3, 0);
                    s.bunker(2, 4, elbowZ, elbowZ + 2);
                } else {
                    s.slimeWalls(1, 7, elbowZ + 3, elbowZ + 3);
                    s.slimeWalls(1, 1, elbowZ - 2, elbowZ + 3);
                }
                what = up + " up, dropping at the elbow, " + across + " across" + (hard ? ", a bunker" : "");
            }
            case TREE_GARDEN -> {
                s.adventure();
                int x0 = hard ? 7 : 6;
                int x1 = hard ? 11 : 12;
                int distance = r.nextInt(13, 18);
                int off = hard ? r.nextInt(-1, 1) : r.nextInt(-2, 2);
                int want = easy ? 1 : hard ? r.nextInt(2, 3) : r.nextInt(1, 2);
                mirror = r.nextBoolean();
                int cupZ = 3 + distance;
                s.lane(x0, x1, 2, cupZ + 1, 0);
                s.tee(9, 3);
                s.cup(9 + off, cupZ);
                int placed = 0;
                for (int i = 0; i < 40 && placed < want; i++) {
                    int x = r.nextInt(x0 + 2, x1 - 2);
                    int z = r.nextInt(7, cupZ - 5);
                    String wood = Palette.WOODS.get(r.nextInt(Palette.WOODS.size()));
                    if (s.canTrunk(x, z)) {
                        s.tree(x, z, wood);
                        placed++;
                    }
                }
                what = distance + " long, " + placed + " tree" + (placed == 1 ? "" : "s");
            }
            case POND_SIDE -> {
                s.adventure();
                int width = r.nextInt(6, 7);
                int distance = r.nextInt(14, 20);
                int off = r.nextInt(-1, 1);
                int length = r.nextInt(6, 10);
                int across = easy ? 3 : r.nextInt(3, 4);
                mirror = r.nextBoolean();
                int x0 = 6;
                int x1 = 5 + width;
                int cx = (x0 + x1) / 2;
                int cupZ = 3 + distance;
                s.lane(x0, x1, 2, cupZ + 1, 0);
                s.tee(cx, 3);
                s.cup(cx + off, cupZ);
                if (easy) {
                    // to look at: beyond the side wall, 2 columns clear of the bounds, rimmed with moss
                    int start = Math.min(4 + r.nextInt(0, 6), cupZ + 2 - length);
                    s.decorativePond(x1 + 4, x1 + 3 + across, start, start + length - 1);
                    what = distance + " long, a pond to look at";
                } else {
                    int last = cupZ - 5; // 4 from the cup: 2 clear of its ring
                    int len = Math.min(length, last - 7 + 1);
                    int start = 7 + r.nextInt(0, last - 7 + 1 - len);
                    s.water(x1 + 1, x1 + across, start, start + len - 1);
                    what = distance + " long, a pond " + across + " by " + len;
                }
            }
            case ISLAND_POND -> {
                s.adventure();
                int approach = r.nextInt(6, 10);
                int causeway = r.nextInt(4, 6);
                mirror = r.nextBoolean();
                int a = 1 + approach;
                int g = a + causeway + 1;
                s.lane(7, 11, 2, a, 0);
                s.water(3, 15, a + 1, g + 9);
                s.lane(8, 10, a + 1, g - 1, 0);
                s.lane(6, 12, g, g + 6, 0);
                s.tee(9, 3);
                s.cup(9, g + 3);
                what = "approach " + approach + ", causeway " + causeway;
            }
            case TWO_WAY -> {
                s.adventure();
                int wide = r.nextInt(4, 5);
                int narrow = r.nextInt(3, 4);
                if (wide + narrow > 8) {
                    narrow = 3; // the lane at most 11 wide
                }
                int at = r.nextInt(7, 9);
                int after = r.nextInt(4, 6);
                int cupOff = r.nextInt(0, narrow - 2);
                String wood = Palette.WOODS.get(r.nextInt(Palette.WOODS.size()));
                mirror = r.nextBoolean();
                int x0 = 2;
                int island = x0 + wide; // the island's first column
                int s0 = island + 3; // the short way
                int x1 = s0 + narrow - 1;
                int cupZ = at + 5 + after;
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
                if (hard) {
                    s.floor(s0, x1, at, at + 1, Sketch.SAND);
                    s.water(x1 + 1, x1 + 3, at + 2, at + 4);
                } else {
                    s.water(x1 + 1, x1 + 3, at + 1, at + 3);
                }
                s.tee(s0 + 1, 3);
                s.cup(s0 + cupOff, cupZ);
                what = "a " + narrow + "-wide short way past a pond" + (hard ? " and sand" : "") + ", a " + wide
                        + "-wide long way";
            }
            case CREEK -> {
                s.adventure();
                int across = r.nextInt(3, 5);
                int c0 = r.nextInt(9, 14 - across);
                int bridge = r.nextInt(-2, 2);
                int after = r.nextInt(5, 9);
                int off = r.nextInt(-2, 2);
                mirror = r.nextBoolean();
                int c1 = c0 + across - 1;
                int cupZ = c1 + after;
                s.lane(6, 12, 2, cupZ + 1, 0);
                s.water(3, 15, c0, c1);
                s.lane(8 + bridge, 10 + bridge, c0, c1, 0);
                s.tee(9, 3);
                s.cup(9 + off, cupZ);
                what = "a creek " + across + " across, bridge " + signed(bridge) + ", cup " + after + " past";
            }
            default -> throw new IllegalStateException("unknown template " + this);
        }
        s.tier = t;
        return s.render(this, mirror, plotX, plotZ, turfY, name() + " " + what + (mirror ? ", mirrored" : ""));
    }

    /** A flat straight of lane columns x0..x1: tee at (9, 3), the cup {@code length} ahead, one off. */
    private static void straight(Sketch s, int x0, int x1, int length, int off) {
        straightTo(s, x0, x1, 3 + length, off);
    }

    private static void straightTo(Sketch s, int x0, int x1, int cupZ, int off) {
        s.lane(x0, x1, 2, cupZ + 1, 0);
        s.tee((x0 + x1) / 2, 3);
        s.cup((x0 + x1) / 2 + off, cupZ);
    }

    private static String signed(int n) {
        return n == 0 ? "centred" : (n > 0 ? "+" : "") + n;
    }

    /**
     * A hole drawn on the plot's own cells before it becomes blocks: which cells are lane and at
     * what level, what their floor is, where the ponds, rocks, trees, slime walls, tee and cup are,
     * and the scenery beside it.
     */
    static final class Sketch {

        /** Floors. */
        static final byte TURF = 0;
        static final byte ICE = 1;
        static final byte BRAKE = 2;
        static final byte SAND = 3;
        /** How high a trunk's top stands above T: logs from T - 1 to T + 2. */
        static final int TRUNK_TOP = 3;
        /** A pond is at least this many across, every way ({@link GolfValidatorV3#SKIM}). */
        static final int SKIM = GolfValidatorV3.SKIM;
        private static final double EPS = 1e-6;
        private static final int[][] FOUR = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        private static final int[][] EIGHT = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

        /** The plot's size in blocks (x, z): 20 x 40 for Adventure Golf, 40 x 64 for Golf v4 ({@link PlotGrid}). */
        final int sx;
        final int sz;
        /** Lane heights in half blocks above T (-1 to 4), where {@link #lane} is set. */
        private final int[][] level;
        private final boolean[][] lane;
        private final byte[][] floor;
        private final boolean[][] rock;
        private final boolean[][] slime;
        private final boolean[][] water;
        /** A moss obstacle: a tree island's ground. */
        private final boolean[][] island;
        /** A log trunk's wood, or null. */
        private final String[][] trunk;
        /** A lane cell whose exposed edge (above the lane below it) is blue glass. */
        private final boolean[][] glass;
        /** Leaves: {x, y above T, z} and their wood. */
        private final List<int[]> leaves = new ArrayList<>();
        private final List<String> leafWood = new ArrayList<>();
        /** Scenery (outside the physics grid): {x, y above T, z} and its block. */
        private final List<int[]> scenery = new ArrayList<>();
        private final List<String> sceneryBlock = new ArrayList<>();
        /** Adventure Golf: each wall column as high as the rules need, not one height all round. */
        private boolean adventure;
        /** The tier it is drawn for, for its features. */
        char tier = 'M';
        int teeX = -1;
        int teeZ = -1;
        int cupX = -1;
        int cupZ = -1;

        /** A sketch of an Adventure Golf plot, {@value #PLOT_X} x {@value #PLOT_Z}. */
        Sketch() {
            this(PLOT_X, PLOT_Z);
        }

        /**
         * A sketch of a plot {@code sx} x {@code sz} (GOLF-V4-SPEC §3.6: the same rules on any plot;
         * a 20 x 40 one draws exactly what Adventure Golf always drew).
         */
        Sketch(int sx, int sz) {
            if (sx < 3 || sz < 3) {
                throw new IllegalArgumentException("a plot of " + sx + " x " + sz);
            }
            this.sx = sx;
            this.sz = sz;
            level = new int[sx][sz];
            lane = new boolean[sx][sz];
            floor = new byte[sx][sz];
            rock = new boolean[sx][sz];
            slime = new boolean[sx][sz];
            water = new boolean[sx][sz];
            island = new boolean[sx][sz];
            trunk = new String[sx][sz];
            glass = new boolean[sx][sz];
        }

        /** Give each wall column the least height the Adventure Golf rules allow (the new templates). */
        void adventure() {
            adventure = true;
        }

        /** Lane cells x0..x1, z0..z1 at level {@code halfBlocks} (-1 to 4). */
        void lane(int x0, int x1, int z0, int z1, int halfBlocks) {
            if (halfBlocks < -1 || halfBlocks > 4) {
                throw new IllegalStateException("a lane level is -1 to 4: " + halfBlocks);
            }
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    check(x, z);
                    lane[x][z] = true;
                    level[x][z] = halfBlocks;
                    rock[x][z] = false;
                    water[x][z] = false;
                    island[x][z] = false;
                    trunk[x][z] = null;
                }
            }
        }

        /** A sunken bunker: lane cells at level -1, their floor sand (a smooth sandstone slab at T - 1). */
        void bunker(int x0, int x1, int z0, int z1) {
            lane(x0, x1, z0, z1, -1);
            floor(x0, x1, z0, z1, SAND);
        }

        /** The floor of the lane cells in x0..x1, z0..z1. */
        void floor(int x0, int x1, int z0, int z1, byte kind) {
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    if (lane(x, z)) {
                        floor[x][z] = kind;
                    }
                }
            }
        }

        /** A pond over x0..x1, z0..z1: still water at T - 1 on blue concrete; not lane. */
        void water(int x0, int x1, int z0, int z1) {
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    check(x, z);
                    lane[x][z] = false;
                    water[x][z] = true;
                    rock[x][z] = false;
                }
            }
        }

        /** Mark the lane cells in x0..x1, z0..z1 as terrace edges: their face above the lane below is glass. */
        void glass(int x0, int x1, int z0, int z1) {
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    glass[x][z] = lane(x, z);
                }
            }
        }

        /** A moss obstacle over x0..x1, z0..z1 (a tree island's ground), a block above the turf. */
        void island(int x0, int x1, int z0, int z1) {
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    check(x, z);
                    lane[x][z] = false;
                    island[x][z] = true;
                }
            }
        }

        /** A log trunk of {@code wood} from T - 1, its top at T + {@value #TRUNK_TOP}. */
        void trunk(int x, int z, String wood) {
            check(x, z);
            lane[x][z] = false;
            island[x][z] = false;
            trunk[x][z] = wood;
        }

        /** A leaf of {@code wood} at (x, T + up, z). */
        void leaf(int x, int up, int z, String wood) {
            leaves.add(new int[]{x, up, z});
            leafWood.add(wood);
        }

        /** A tree in play: its trunk, leaves at T + 3 in the 3 x 3 round it and at T + 4 in a plus. */
        void tree(int x, int z, String wood) {
            trunk(x, z, wood);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    leaf(x + dx, 3, z + dz, wood);
                    if (dx == 0 || dz == 0) {
                        leaf(x + dx, 4, z + dz, wood);
                    }
                }
            }
        }

        /**
         * Whether a tree in play may stand at (x, z): a turf cell with turf lane all round it (so 2
         * from any wall), 4 from the tee and 5 from the cup (its canopy then keeps 2 clear of the tee
         * and of the cup ring and flag), and 3 from any other trunk.
         */
        boolean canTrunk(int x, int z) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (!lane(x + dx, z + dz) || level[x + dx][z + dz] != 0) {
                        return false;
                    }
                }
            }
            if (Math.max(Math.abs(x - teeX), Math.abs(z - teeZ)) < 4
                    || Math.max(Math.abs(x - cupX), Math.abs(z - cupZ)) < 5) {
                return false;
            }
            for (int ox = 0; ox < sx; ox++) {
                for (int oz = 0; oz < sz; oz++) {
                    if (trunk[ox][oz] != null && Math.max(Math.abs(x - ox), Math.abs(z - oz)) < 3) {
                        return false;
                    }
                }
            }
            return true;
        }

        /**
         * A pond to look at (scenery, outside the physics grid): still water over x0..x1, z0..z1 at
         * T - 1 on blue concrete, rimmed with moss at T - 1 so it is sealed and a child who walks in
         * steps out onto it.
         */
        void decorativePond(int x0, int x1, int z0, int z1) {
            for (int x = x0 - 1; x <= x1 + 1; x++) {
                for (int z = z0 - 1; z <= z1 + 1; z++) {
                    if (x < 0 || x >= sx || z < 0 || z >= sz) {
                        throw new IllegalStateException("a pond drawn outside its plot: " + x + "," + z);
                    }
                    if (x >= x0 && x <= x1 && z >= z0 && z <= z1) {
                        scenery(x, -2, z, "minecraft:blue_concrete");
                        scenery(x, -1, z, "minecraft:water[level=0]");
                    } else {
                        scenery(x, -1, z, Palette.MOSS);
                    }
                }
            }
        }

        private void scenery(int x, int up, int z, String block) {
            scenery.add(new int[]{x, up, z});
            sceneryBlock.add(block);
        }

        /** Whether a rock may go here: a turf lane cell, clear of the tee, the cup ring and other rocks. */
        boolean canRock(int x, int z) {
            if (x < 0 || x >= sx || z < 0 || z >= sz || !lane[x][z] || level[x][z] != 0) {
                return false;
            }
            boolean nearCup = Math.abs(x - cupX) <= 2 && Math.abs(z - cupZ) <= 2;
            boolean nearTee = Math.abs(x - teeX) <= 1 && Math.abs(z - teeZ) <= 2;
            if (nearCup || nearTee) {
                return false;
            }
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    int nx = x + dx;
                    int nz = z + dz;
                    if (nx >= 0 && nx < sx && nz >= 0 && nz < sz && rock[nx][nz]) {
                        return false;
                    }
                }
            }
            return true;
        }

        /** A single-block rock (slime or wood) standing in the lane. */
        void rock(int x, int z, boolean isSlime) {
            check(x, z);
            lane[x][z] = false;
            rock[x][z] = true;
            slime[x][z] = isSlime;
        }

        /** Make the walls in x0..x1, z0..z1 slime (cells that turn out not to be walls stay empty). */
        void slimeWalls(int x0, int x1, int z0, int z1) {
            for (int x = Math.max(0, x0); x <= Math.min(sx - 1, x1); x++) {
                for (int z = Math.max(0, z0); z <= Math.min(sz - 1, z1); z++) {
                    if (!lane[x][z]) {
                        slime[x][z] = true;
                    }
                }
            }
        }

        void tee(int x, int z) {
            check(x, z);
            teeX = x;
            teeZ = z;
        }

        void cup(int x, int z) {
            check(x, z);
            cupX = x;
            cupZ = z;
        }

        private void check(int x, int z) {
            if (x < 1 || x >= sx - 1 || z < 1 || z >= sz - 1) {
                throw new IllegalStateException("a hole drawn outside its plot: " + x + "," + z);
            }
        }

        private boolean lane(int x, int z) {
            return x >= 0 && x < sx && z >= 0 && z < sz && lane[x][z];
        }

        private boolean water(int x, int z) {
            return x >= 0 && x < sx && z >= 0 && z < sz && water[x][z];
        }

        /** An obstacle standing in the lane: a rock, a tree island's moss or a trunk. */
        private boolean obstacle(int x, int z) {
            return rock[x][z] || island[x][z] || trunk[x][z] != null;
        }

        /**
         * Whether a wall goes here: a cell off the lane and out of the ponds with lane or pond round
         * it (diagonals too).
         */
        private boolean walled(int x, int z) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if ((dx != 0 || dz != 0) && (lane(x + dx, z + dz) || water(x + dx, z + dz))) {
                        return true;
                    }
                }
            }
            return false;
        }

        /** A lane cell's surface above T. */
        private double surface(int x, int z) {
            return level[x][z] / 2.0;
        }

        /**
         * Blocks of wall above T - 1, the same all round the hole (GEN-SPEC's first templates): one
         * block above its highest lane cell (1 on a flat hole, 2 round a raised green).
         */
        private int wallHeight() {
            int highest = 0;
            for (int x = 0; x < sx; x++) {
                for (int z = 0; z < sz; z++) {
                    if (lane[x][z]) {
                        highest = Math.max(highest, level[x][z]);
                    }
                }
            }
            return 1 + (highest + 1) / 2;
        }

        /**
         * Adventure Golf's walls: per solid column (a wall or an obstacle), how many blocks it has
         * from T - 1 up — the least that passes {@link GolfValidatorV3}'s rules 4-6 where it stands:
         * its top more than half a block above the highest lane beside it (a whole block for an
         * obstacle; a block above the turf round a pond), then raised a block at a time while a ball
         * off some lip could fly onto it ({@link LaneMap#flightReach}). A ring wall that would stand
         * more than two blocks above the lowest lane beside it is a template's bug.
         */
        private int[][] columnHeights(String template) {
            int[][] k = new int[sx][sz];
            List<int[]> lips = new ArrayList<>();
            for (int x = 0; x < sx; x++) {
                for (int z = 0; z < sz; z++) {
                    if (!lane[x][z]) {
                        continue;
                    }
                    for (int[] d : FOUR) {
                        int nx = x + d[0];
                        int nz = z + d[1];
                        boolean lower = lane(nx, nz) && !(nx == cupX && nz == cupZ)
                                && surface(nx, nz) < surface(x, z) - EPS;
                        if (water(nx, nz) || lower) {
                            lips.add(new int[]{x, z});
                            break;
                        }
                    }
                }
            }
            Map<Double, Double> reach = new HashMap<>();
            int rail = railLevel();
            for (int x = 0; x < sx; x++) {
                for (int z = 0; z < sz; z++) {
                    if (lane[x][z] || water[x][z]) {
                        continue;
                    }
                    boolean obstacle = obstacle(x, z);
                    if (!obstacle && !walled(x, z)) {
                        continue;
                    }
                    double high = Double.NEGATIVE_INFINITY;
                    double low = Double.POSITIVE_INFINITY;
                    for (int[] d : EIGHT) {
                        if (lane(x + d[0], z + d[1])) {
                            high = Math.max(high, surface(x + d[0], z + d[1]));
                            low = Math.min(low, surface(x + d[0], z + d[1]));
                        }
                    }
                    int h;
                    if (trunk[x][z] != null) {
                        h = TRUNK_TOP + 1;
                    } else if (high == Double.NEGATIVE_INFINITY) {
                        h = Math.max(2, rail + 2); // round a pond only: a block above the turf
                    } else if (obstacle) {
                        h = Math.max(2, (int) Math.ceil(high + 1 - EPS) + 1);
                    } else {
                        h = Math.max((int) Math.floor(high + 0.5 + EPS) + 2, rail + 2);
                    }
                    // h blocks from T - 1: the top is at T + h - 1
                    boolean raised = true;
                    while (raised) {
                        raised = false;
                        double top = h - 1;
                        for (int[] c : lips) {
                            double s = surface(c[0], c[1]);
                            if (top > s + LaneMap.STEP + EPS) {
                                continue;
                            }
                            double drop = s - top + LaneMap.STEP;
                            double far = reach.computeIfAbsent(drop, LaneMap::flightReach);
                            if (GolfValidatorV3.edgeDistance(c[0], c[1], x, z) <= far + EPS) {
                                h++;
                                raised = true;
                                break;
                            }
                        }
                    }
                    if (!obstacle && high != Double.NEGATIVE_INFINITY
                            && h - 1 - low > GolfValidatorV3.WALL_ABOVE_MOST + EPS) {
                        throw new IllegalStateException(template + "'s wall at " + x + "," + z + " would stand "
                                + (h - 1 - low) + " above the lane beside it");
                    }
                    k[x][z] = h;
                }
            }
            return k;
        }

        /**
         * The highest raised level (blocks above T) at which a ball can ride a wall's rail, or -1
         * when it never can ({@link GolfValidatorV3}'s rail rule, which checks the blocks). The
         * ball's physics reads each block of a column on its own, and holds a ball up by the edges
         * of its footprint, so a ball whose edge is over a wall rests on the top of the wall's block
         * at its own height and can roll along it like a rail, held above a lower lane or a pond
         * beside it — and over the first wall no more than half a block above it. Rolling sideways
         * never puts a ball's edge over a wall; it gets there only where the wall's line begins:
         * rolling along it from a column its edge was over (lane, or a pond) into one that is the
         * wall. Its edge can be out over a lower side (lane no more than a step up, or a pond, beside
         * the cell it is on: a plateau's edge where the approach's wall begins), or its centre can be
         * the one out, hanging off a lip over the lower lane or a pond with its edge on the upper
         * cell (the dogleg's ledge running into the upper leg's side wall). Either way, followed
         * along the wall's line while its centre's column lets it on, the ball rides the wall only
         * once its centre is over something lower. A rail at the turf is harmless (the lane is there;
         * over a pond, a ball that stops there has fallen in); a rail above it is closed by standing
         * every ring wall a block above it, so a ball riding it meets a wall at its end and never
         * rolls over one.
         */
        private int railLevel() {
            int rail = -1;
            for (int qx = 0; qx < sx; qx++) {
                for (int qz = 0; qz < sz; qz++) {
                    if (!lane[qx][qz] && !water[qx][qz]) {
                        continue; // the column under the ball's centre
                    }
                    for (int[] e : FOUR) {
                        int px = qx + e[0];
                        int pz = qz + e[1];
                        if (!lane(px, pz) && !water(px, pz)) {
                            continue; // the column under its edge
                        }
                        double s = Math.max(lane[qx][qz] ? surface(qx, qz) : Double.NEGATIVE_INFINITY,
                                lane(px, pz) ? surface(px, pz) : Double.NEGATIVE_INFINITY);
                        if (s < 1 - EPS || (int) Math.floor(s + EPS) <= rail) {
                            continue; // at the turf, over no lane, or no higher than a rail already found
                        }
                        for (int[] m : FOUR) {
                            if (m[0] * e[0] + m[1] * e[1] == 0 && rides(qx, qz, px, pz, m, s)) {
                                rail = (int) Math.floor(s + EPS);
                            }
                        }
                    }
                }
            }
            return rail;
        }

        /**
         * Whether a ball at height {@code s} above T, its centre over column q and its edge over
         * column p, rolled along {@code m} rides a wall: its edge goes onto a wall's line (the next
         * columns along from p) while its centre's columns let it on, until its centre is over
         * lower lane or a pond. Walls are taken to stand more than half a block above {@code s}
         * (the rail's own rule makes them so), so a centre that meets one stops the ball.
         */
        private boolean rides(int qx, int qz, int px, int pz, int[] m, double s) {
            for (int j = 1; ; j++) {
                int wx = px + j * m[0];
                int wz = pz + j * m[1];
                int nx = qx + j * m[0];
                int nz = qz + j * m[1];
                boolean wall = wx >= 0 && wz >= 0 && wx < sx && wz < sz && !lane[wx][wz] && !water[wx][wz]
                        && (obstacle(wx, wz) || walled(wx, wz));
                if (!wall) {
                    return false; // its edge is back over lane or a pond: the ball's own footing again
                }
                if (water(nx, nz) || lane(nx, nz) && surface(nx, nz) < s - EPS) {
                    return true; // held up by the wall alone, over something lower
                }
                if (!lane(nx, nz) || surface(nx, nz) > s + LaneMap.STEP + EPS) {
                    return false; // its centre meets a wall or a step it can't climb: it stops
                }
            }
        }

        HoleLayout render(HoleTemplate template, boolean mirror, int plotX, int plotZ, int turfY, String describe) {
            return render(template, String.valueOf(template), mirror, plotX, plotZ, turfY, describe,
                    template.features(tier), template.teeFeature(tier));
        }

        /**
         * The hole as blocks: {@link #render(HoleTemplate, boolean, int, int, int, String)} for a hole
         * that isn't one template's (a Golf v4 routing and its pieces: {@code template} {@code null}),
         * named {@code template} in a bug's message, with the quota's {@code features} and the tee
         * sign's {@code teeFeature} given.
         */
        HoleLayout render(HoleTemplate shape, String template, boolean mirror, int plotX, int plotZ, int turfY,
                          String describe, Set<Quota.Feature> features, GenCopy.TeeFeature teeFeature) {
            if (teeX < 0 || cupX < 0 || !lane[teeX][teeZ] || !lane[cupX][cupZ]) {
                throw new IllegalStateException(template + " has no tee or cup on its lane");
            }
            int teeLevel = level[teeX][teeZ];
            if (adventure ? teeLevel < 0 || teeLevel % 2 != 0 : teeLevel != 0) {
                throw new IllegalStateException(template + " put its tee on level " + teeLevel);
            }
            int cupLevel = level[cupX][cupZ];
            if (cupLevel < 0 || cupLevel % 2 != 0) {
                throw new IllegalStateException(template + " put its cup on level " + cupLevel);
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (!lane(cupX + dx, cupZ + dz) || level[cupX + dx][cupZ + dz] != cupLevel) {
                        throw new IllegalStateException(template + "'s cup ring isn't level lane");
                    }
                }
            }
            if (adventure) {
                slabRule(template);
            }
            int uniform = wallHeight();
            int[][] height = adventure ? columnHeights(template) : null;
            List<HoleLayout.Placed> out = new ArrayList<>();
            List<int[]> logs = new ArrayList<>();
            int minX = Integer.MAX_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int maxZ = Integer.MIN_VALUE;
            for (int lx = 0; lx < sx; lx++) {
                for (int lz = 0; lz < sz; lz++) {
                    int wx = plotX + (mirror ? sx - 1 - lx : lx);
                    int wz = plotZ + lz;
                    if (lane[lx][lz] || water[lx][lz]) {
                        minX = Math.min(minX, wx);
                        maxX = Math.max(maxX, wx);
                        minZ = Math.min(minZ, wz);
                        maxZ = Math.max(maxZ, wz);
                    }
                    if (water[lx][lz]) {
                        out.add(new HoleLayout.Placed(wx, turfY - 2, wz, "minecraft:blue_concrete"));
                        out.add(new HoleLayout.Placed(wx, turfY - 1, wz, "minecraft:water[level=0]"));
                        continue;
                    }
                    if (lane[lx][lz]) {
                        laneColumn(out, lx, lz, wx, wz, turfY);
                        continue;
                    }
                    if (trunk[lx][lz] != null) {
                        String log = Palette.log(trunk[lx][lz]);
                        for (int y = turfY - 1; y <= turfY + TRUNK_TOP - 1; y++) {
                            out.add(new HoleLayout.Placed(wx, y, wz, log));
                            logs.add(new int[]{wx, y, wz});
                        }
                        continue;
                    }
                    if (island[lx][lz]) {
                        for (int y = 0; y < height[lx][lz]; y++) {
                            out.add(new HoleLayout.Placed(wx, turfY - 1 + y, wz, Palette.MOSS));
                        }
                        continue;
                    }
                    if (!rock[lx][lz] && !walled(lx, lz)) {
                        continue;
                    }
                    String wall = slime[lx][lz] ? Palette.BUMPER : Palette.GOLF_WALL;
                    if (adventure) {
                        for (int y = 0; y < height[lx][lz]; y++) {
                            boolean foot = rock[lx][lz] && y == 0;
                            out.add(new HoleLayout.Placed(wx, turfY - 1 + y, wz, foot ? turf(wx, wz) : wall));
                            if (!foot && !slime[lx][lz]) {
                                logs.add(new int[]{wx, turfY - 1 + y, wz});
                            }
                        }
                        continue;
                    }
                    if (rock[lx][lz]) {
                        out.add(new HoleLayout.Placed(wx, turfY - 1, wz, turf(wx, wz)));
                    } else {
                        out.add(new HoleLayout.Placed(wx, turfY - 1, wz, wall));
                    }
                    for (int y = 0; y < uniform; y++) {
                        out.add(new HoleLayout.Placed(wx, turfY + y, wz, wall));
                    }
                }
            }
            leaves(out, logs, mirror, plotX, plotZ, turfY);
            int cupWX = plotX + (mirror ? sx - 1 - cupX : cupX);
            int cupWZ = plotZ + cupZ;
            int cupTop = turfY + cupLevel / 2;
            int flagY = cupTop + 3;
            out.add(new HoleLayout.Placed(cupWX, flagY, cupWZ, Palette.FLAG));
            int signLX = teeX;
            int signLZ = teeZ - 2;
            if (lane(signLX, signLZ) || water(signLX, signLZ) || obstacle(signLX, signLZ)
                    || !walled(signLX, signLZ)) {
                throw new IllegalStateException(template + " has no wall behind its tee for the sign");
            }
            int signY = turfY + (adventure ? height[signLX][signLZ] - 1 : uniform);
            int boundsTop = Math.max(turfY + 4, flagY);
            if (signY > boundsTop) {
                throw new IllegalStateException(template + "'s tee sign would stand above its bounds");
            }
            int teeWX = plotX + (mirror ? sx - 1 - teeX : teeX);
            List<HoleLayout.Placed> view = new ArrayList<>();
            for (int i = 0; i < scenery.size(); i++) {
                int[] p = scenery.get(i);
                view.add(new HoleLayout.Placed(plotX + (mirror ? sx - 1 - p[0] : p[0]), turfY + p[1],
                        plotZ + p[2], sceneryBlock.get(i)));
            }
            return new HoleLayout(shape, mirror, turfY, teeWX, turfY + teeLevel / 2.0, plotZ + teeZ, cupWX, cupWZ,
                    cupTop, minX, minZ, maxX, maxZ, boundsTop, teeWX, signY, plotZ + signLZ, out, view,
                    features, teeFeature, describe);
        }

        /**
         * One lane column's blocks: solid from T - 1 to its level, its top the floor (turf, ice,
         * brake, sand), the tee or the cup ring; the cup left out with black concrete one lower. A
         * terrace edge's blocks above the lane below it are blue glass.
         */
        private void laneColumn(List<HoleLayout.Placed> out, int lx, int lz, int wx, int wz, int turfY) {
            boolean cup = lx == cupX && lz == cupZ;
            boolean tee = lx == teeX && lz == teeZ;
            boolean ring = !cup && Math.abs(lx - cupX) <= 1 && Math.abs(lz - cupZ) <= 1;
            int lvl = level[lx][lz];
            String top = tee ? Palette.TEE : ring ? Palette.CUP_RING
                    : floor[lx][lz] == ICE ? Palette.GOLF_ICE
                    : floor[lx][lz] == BRAKE ? Palette.BRAKE
                    : floor[lx][lz] == SAND ? Palette.SAND : turf(wx, wz);
            int glassFrom = Integer.MAX_VALUE;
            if (glass[lx][lz]) {
                double lowest = surface(lx, lz);
                for (int[] d : FOUR) {
                    if (lane(lx + d[0], lz + d[1])) {
                        lowest = Math.min(lowest, surface(lx + d[0], lz + d[1]));
                    }
                }
                glassFrom = turfY + (int) Math.floor(lowest + EPS);
            }
            List<HoleLayout.Placed> column = new ArrayList<>();
            switch (lvl) {
                case -1 -> column.add(new HoleLayout.Placed(wx, turfY - 1, wz, Palette.SAND_SLAB));
                case 0 -> column.add(cup ? new HoleLayout.Placed(wx, turfY - 2, wz, Palette.CUP)
                        : new HoleLayout.Placed(wx, turfY - 1, wz, top));
                case 1 -> {
                    column.add(new HoleLayout.Placed(wx, turfY - 1, wz, turf(wx, wz)));
                    column.add(new HoleLayout.Placed(wx, turfY, wz, Palette.RAMP));
                }
                case 2 -> {
                    if (cup) {
                        column.add(new HoleLayout.Placed(wx, turfY - 1, wz, Palette.CUP));
                    } else {
                        column.add(new HoleLayout.Placed(wx, turfY - 1, wz, Palette.TURF_DARK));
                        column.add(new HoleLayout.Placed(wx, turfY, wz, top));
                    }
                }
                case 3 -> {
                    column.add(new HoleLayout.Placed(wx, turfY - 1, wz, Palette.TURF_DARK));
                    column.add(new HoleLayout.Placed(wx, turfY, wz, Palette.TURF_DARK));
                    column.add(new HoleLayout.Placed(wx, turfY + 1, wz, Palette.RAMP));
                }
                default -> {
                    column.add(new HoleLayout.Placed(wx, turfY - 1, wz, Palette.TURF_DARK));
                    if (cup) {
                        column.add(new HoleLayout.Placed(wx, turfY, wz, Palette.CUP));
                    } else {
                        column.add(new HoleLayout.Placed(wx, turfY, wz, Palette.TURF_DARK));
                        column.add(new HoleLayout.Placed(wx, turfY + 1, wz, top));
                    }
                }
            }
            for (HoleLayout.Placed p : column) {
                boolean face = p.y() >= glassFrom && !p.blockData().contains("slab");
                out.add(face ? new HoleLayout.Placed(p.x(), p.y(), p.z(), Palette.BLUE_GLASS) : p);
            }
        }

        /**
         * The Adventure Golf slab rule: a raised slab (a level 1 or 3 lane cell) has lane all round
         * it, diagonals too — slabs sit in the middle of a lane, flanked by full steps, never beside
         * a wall or a pond.
         */
        private void slabRule(String template) {
            for (int x = 0; x < sx; x++) {
                for (int z = 0; z < sz; z++) {
                    if (!lane[x][z] || level[x][z] != 1 && level[x][z] != 3) {
                        continue;
                    }
                    for (int[] d : EIGHT) {
                        if (!lane(x + d[0], z + d[1])) {
                            throw new IllegalStateException(template + " put a slab beside a wall at " + x + "," + z);
                        }
                    }
                }
            }
        }

        /**
         * The leaves as blocks: each leaf's {@code distance} worked out as vanilla works it out
         * ({@link Palette#leafDistances}) over this hole's own logs and walls (stripped wood holds
         * leaves too), so a neighbour update never changes one.
         */
        private void leaves(List<HoleLayout.Placed> out, List<int[]> logs, boolean mirror, int plotX, int plotZ,
                            int turfY) {
            if (leaves.isEmpty()) {
                return;
            }
            List<int[]> at = new ArrayList<>();
            Map<Long, String> wood = new HashMap<>();
            for (int i = 0; i < leaves.size(); i++) {
                int[] l = leaves.get(i);
                int[] w = {plotX + (mirror ? sx - 1 - l[0] : l[0]), turfY + l[1], plotZ + l[2]};
                if (wood.putIfAbsent(Palette.blockKey(w[0], w[1], w[2]), leafWood.get(i)) == null) {
                    at.add(w);
                }
            }
            Map<Long, Integer> d = Palette.leafDistances(logs, at);
            for (int[] w : at) {
                long key = Palette.blockKey(w[0], w[1], w[2]);
                out.add(new HoleLayout.Placed(w[0], w[1], w[2], Palette.leaves(wood.get(key), d.get(key))));
            }
        }

        /** Checked turf, 2 x 2, so distances read at a glance. */
        private static String turf(int wx, int wz) {
            return (((wx >> 1) + (wz >> 1)) & 1) == 0 ? Palette.TURF_LIGHT : Palette.TURF_DARK;
        }
    }
}
