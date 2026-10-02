package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.boat.BoatStyle;

import java.util.ArrayList;
import java.util.List;

/**
 * A hand-made Mountain Run v2 (MOUNTAIN-V2-SPEC, boat planner algo 4) for the runtime tests: the shape of a
 * real one (a long serpentine down a 480 x 176 x 640 half, 74 checkpoints, one drop at most per leg, the
 * Final Drop on the finish's own leg, the finish at the foot, in front of the stand), with every number a
 * test can work out by hand, and its style picked by its seed ({@link BoatStyle#of}).
 *
 * <p>Half A of {@code fresh_boat} at (6080, 96, 2880). The start is on the top band at y 170, facing east.
 * Checkpoint i (0-based) is on band i / 8 (z 2940 + 50 per band), going east then west in turn, 40 apart
 * along a band; every fourth leg (the one ending at checkpoint 3, 7, 11, ...) ends one block lower (a Hop),
 * so the last checkpoint is 18 blocks under the start, and the finish leg is the Final Drop (2 blocks):
 * 20 blocks down in all, 19 drops. The model time T_m is {@value #MODEL_MS} ms, so its tag's star reference
 * is 4/5 of that.
 */
public final class MountainRunsV2 {

    /** Ice Boat's v2 half A (the shipped v4 spot). */
    public static final Box HALF = Box.sized(6080, 96, 2880, 480, 176, 640);
    /** The start's height. */
    public static final double TOP = 170;
    /** How many checkpoints. */
    public static final int CHECKPOINTS = 74;
    /** How many drops (18 Hops and the Final Drop). */
    public static final int DROPS = 19;
    /** Blocks from the start down to the finish. */
    public static final int DESCENT = 20;
    /** The model time T_m (2 minutes). */
    public static final long MODEL_MS = 120_000;
    /** A seed whose style is the Winding Road. */
    public static final long ROAD_SEED = seedOf(BoatStyle.ROAD);
    /** A seed whose style is the Slalom. */
    public static final long SLALOM_SEED = seedOf(BoatStyle.SLALOM);

    public static final Course.Spot START = new Course.Spot(6200.5, TOP, 2900.5, -90f, 0f);

    private MountainRunsV2() {
    }

    /** The first seed from 1 up whose style is {@code s}. */
    static long seedOf(BoatStyle s) {
        for (long seed = 1; ; seed++) {
            if (BoatStyle.of(seed) == s) {
                return seed;
            }
        }
    }

    /** The checkpoints (radius 4, on the ice). */
    public static List<Course.Mark> checkpoints() {
        List<Course.Mark> out = new ArrayList<>();
        double y = TOP;
        for (int i = 0; i < CHECKPOINTS; i++) {
            int band = i / 8;
            int col = i % 8;
            double x = band % 2 == 0 ? 6120.5 + col * 40 : 6400.5 - col * 40;
            double z = 2940.5 + band * 50;
            if (i % 4 == 3) {
                y -= 1; // a Hop in the leg that ends here
            }
            out.add(new Course.Mark(x, y, z, 4));
        }
        return out;
    }

    /** The finish: at the foot, 2 under the last checkpoint (the Final Drop), north of the stand. */
    public static Course.Mark finish() {
        List<Course.Mark> cps = checkpoints();
        return new Course.Mark(6320.5, cps.get(cps.size() - 1).y() - 2, 3452.5, 5);
    }

    /** A weekly Mountain Run v2 tag of {@code seed} with T_m {@code modelMs} (its star reference 4/5 of it). */
    public static GenTag tag(long seed, long modelMs) {
        return tag(seed, modelMs, 4, 7);
    }

    /** A {@code fresh_boat} tag of boat planner {@code algo}, in a set of {@code cadence} days. */
    public static GenTag tag(long seed, long modelMs, int algo, int cadence) {
        long ref = algo >= 4 ? modelMs * 4 / 5 : modelMs;
        return new GenTag("fresh_boat", "boat", algo, 20_725, 0, seed, 'A', "c0ffee000004", ref, ref * 3 / 2,
                ref * 11 / 5, List.of(), List.of(), 1_790_000_000_000L, cadence);
    }

    /** The run as a weekly Winding Road. */
    public static Course road() {
        return of(tag(ROAD_SEED, MODEL_MS));
    }

    /** The run as a weekly Slalom. */
    public static Course slalom() {
        return of(tag(SLALOM_SEED, MODEL_MS));
    }

    /** The run with {@code tag} ({@code null}: as a hand-built track). */
    public static Course of(GenTag tag) {
        return new Course("fresh_boat", TrialKind.BOAT, "Ice Boat", Tier.MEDIUM, "games", START, checkpoints(),
                finish(), (double) (finish().y() - 4), 60, true, false, 1, tag);
    }

    /**
     * A straight run of {@code n} checkpoints 10 apart east of the start, the finish 10 past the last: its
     * halfway checkpoint is plain to see (measured from checkpoint 1: the first of two as near when n is odd).
     */
    public static Course straight(int n, GenTag tag) {
        List<Course.Mark> cps = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            cps.add(new Course.Mark(6100.5 + i * 10, TOP, 2900.5, 4));
        }
        return new Course("fresh_boat", TrialKind.BOAT, "Ice Boat", Tier.MEDIUM, "games",
                new Course.Spot(6100.5, TOP, 2900.5, -90f, 0f), cps, new Course.Mark(6100.5 + (n + 1) * 10, TOP,
                2900.5, 5), null, 10, true, false, 1, tag);
    }
}
