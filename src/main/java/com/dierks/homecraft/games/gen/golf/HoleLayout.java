package com.dierks.homecraft.games.gen.golf;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.golf.GolfCourse;

import java.util.List;

/**
 * One hole drawn out as blocks (GEN-SPEC §4.3): what a {@link HoleTemplate} made for one plot and
 * one seed, before anything was solved on it.
 *
 * <p>The blocks are world positions with their block-data text; the lane's bounds (the lane cells
 * only, not their walls), the tee, the sunken cup and the sign spot are world positions too. The
 * par isn't known yet — the expert search works it out — so {@link #hole(int)} takes it.
 *
 * @param template  the template that drew it
 * @param mirrored  whether it was mirrored in X
 * @param turfY     T: the turf's top, where the ball and the tee stand
 * @param teeX      the tee block
 * @param teeZ      ...
 * @param cupX      the cup's column
 * @param cupZ      ...
 * @param cupTop    the top of the lane around the cup (T, or T + 1 on a raised green); the cup
 *                  block is two below it
 * @param laneMinX  the lane's cells, walls not included
 * @param laneMinZ  ...
 * @param laneMaxX  ...
 * @param laneMaxZ  ...
 * @param signX     where the tee sign stands (on the wall behind the tee)
 * @param signY     ...
 * @param signZ     ...
 * @param blocks    every block of the hole, each position once
 * @param describe  admin words: the template and its numbers
 */
public record HoleLayout(HoleTemplate template, boolean mirrored, int turfY, int teeX, int teeZ, int cupX, int cupZ,
                         int cupTop, int laneMinX, int laneMinZ, int laneMaxX, int laneMaxZ, int signX, int signY,
                         int signZ, List<Placed> blocks, String describe) {

    /** One block: a world position and its block-data text. */
    public record Placed(int x, int y, int z, String blockData) {
    }

    public HoleLayout {
        blocks = List.copyOf(blocks);
    }

    /**
     * The hole as Mini Golf runs it: the tee on the tee block facing along the first leg (+Z), the
     * cup block under the sunken hole, and the bounds one block round the lane (its walls), from
     * T - 3 to T + 4.
     */
    public GolfCourse.Hole hole(int par) {
        return new GolfCourse.Hole(new GolfCourse.Tee(teeX + 0.5, turfY, teeZ + 0.5, 0f),
                new GolfCourse.Spot(cupX, cupTop - 2, cupZ), par,
                new GolfCourse.Spot(laneMinX - 1, turfY - 3, laneMinZ - 1),
                new GolfCourse.Spot(laneMaxX + 1, turfY + 4, laneMaxZ + 1));
    }

    /** The hole's bounds as a box (walls included). */
    public Box bounds() {
        return new Box(laneMinX - 1, turfY - 3, laneMinZ - 1, laneMaxX + 1, turfY + 4, laneMaxZ + 1);
    }

    /** A grid of just this hole's blocks, over {@code box} (the plot). */
    public PlanBlocks grid(Box box) {
        PlanBlocks g = new PlanBlocks(box);
        for (Placed p : blocks) {
            g.set(p.x(), p.y(), p.z(), PlanBlocks.code(p.blockData()));
        }
        return g;
    }
}
