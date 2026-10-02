package com.dierks.homecraft.games.gen.golf;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.golf.GolfCourse;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * One hole drawn out as blocks (GEN-SPEC §4.3): what a {@link HoleTemplate} made for one plot and
 * one seed, before anything was solved on it.
 *
 * <p>The blocks are world positions with their block-data text; the hole's bounds (its lane and
 * its ponds, walls not included), the tee, the sunken cup and the sign spot are world positions
 * too. The par isn't known yet — the expert search works it out — so {@link #hole(int)} takes it.
 *
 * <p><b>Two lists of blocks</b> (Course Variety §3.10, §3.11). {@link #blocks} is the hole the
 * ball plays: every one of them lies inside the physics grid ({@code GolfPlanner.plotBox}, the
 * bounds and one more round them). {@link #scenery} is what the template draws to look at, never to
 * play: Easy's decorative pond beyond the side wall, kept two columns clear of the bounds, so it is
 * outside that grid and can never change a proof.
 *
 * @param template   the template that drew it; {@code null} for a Golf v4 hole (a {@link HoleRecipe}'s
 *                   routing and pieces, which {@link #describe} names)
 * @param mirrored   whether it was mirrored in X
 * @param turfY      T: the turf's top; the ball stands on T on a level-0 lane
 * @param teeX       the tee block's column
 * @param teeY       the tee's surface: T, or higher on a raised tee (a terrace)
 * @param teeZ       ...
 * @param cupX       the cup's column
 * @param cupZ       ...
 * @param cupTop     the top of the lane around the cup (T, T + 1 or T + 2); the cup block is two
 *                   below it
 * @param laneMinX   the hole's lane and ponds, walls not included
 * @param laneMinZ   ...
 * @param laneMaxX   ...
 * @param laneMaxZ   ...
 * @param boundsTop  the bounds' top: T + 4, or the flag's block when it floats higher (a volcano)
 * @param signX      where the tee sign stands (on the wall behind the tee)
 * @param signY      ...
 * @param signZ      ...
 * @param blocks     every block of the hole, each position once
 * @param scenery    blocks to look at, outside the physics grid (a decorative pond)
 * @param features   what the hole has, as the variety quota counts it
 * @param teeFeature what its tee sign's last two lines say
 * @param describe   admin words: the template and its numbers
 */
public record HoleLayout(HoleTemplate template, boolean mirrored, int turfY, int teeX, double teeY, int teeZ,
                         int cupX, int cupZ, int cupTop, int laneMinX, int laneMinZ, int laneMaxX, int laneMaxZ,
                         int boundsTop, int signX, int signY, int signZ, List<Placed> blocks, List<Placed> scenery,
                         Set<Quota.Feature> features, GenCopy.TeeFeature teeFeature, String describe) {

    /** One block: a world position and its block-data text. */
    public record Placed(int x, int y, int z, String blockData) {
    }

    public HoleLayout {
        blocks = List.copyOf(blocks);
        scenery = List.copyOf(scenery);
        features = features.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(features));
        teeFeature = teeFeature == null ? GenCopy.TeeFeature.NONE : teeFeature;
    }

    /**
     * The hole as Mini Golf runs it: the tee on the tee block facing along the first leg (+Z), the
     * cup block under the sunken hole, and the bounds one block round the lane and its ponds (its
     * walls), from T - 3 to {@link #boundsTop}.
     */
    public GolfCourse.Hole hole(int par) {
        return new GolfCourse.Hole(new GolfCourse.Tee(teeX + 0.5, teeY, teeZ + 0.5, 0f),
                new GolfCourse.Spot(cupX, cupTop - 2, cupZ), par,
                new GolfCourse.Spot(laneMinX - 1, turfY - 3, laneMinZ - 1),
                new GolfCourse.Spot(laneMaxX + 1, boundsTop, laneMaxZ + 1));
    }

    /** The hole's bounds as a box (walls included). */
    public Box bounds() {
        return new Box(laneMinX - 1, turfY - 3, laneMinZ - 1, laneMaxX + 1, boundsTop, laneMaxZ + 1);
    }

    /** A grid of just this hole's blocks (never its scenery), over {@code box} (the plot). */
    public PlanBlocks grid(Box box) {
        PlanBlocks g = new PlanBlocks(box);
        for (Placed p : blocks) {
            g.set(p.x(), p.y(), p.z(), PlanBlocks.code(p.blockData()));
        }
        return g;
    }
}
