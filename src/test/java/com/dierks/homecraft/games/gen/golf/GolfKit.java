package com.dierks.homecraft.games.gen.golf;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenRandom;
import com.dierks.homecraft.games.gen.api.LegacyBoxes;
import com.dierks.homecraft.games.gen.api.PlanInput;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.golf.GolfCourse;

/** Holes and inputs the golf generator tests share. */
final class GolfKit {

    /** Daily Golf's half A at its 0.35 spot (no golden moves with the shipped layout), and its first plot. */
    static final Box DAILY_A = LegacyBoxes.half(Slots.DAILY_GOLF, 'A');
    static final int PLOT_X = DAILY_A.minX();
    static final int PLOT_Z = DAILY_A.minZ();
    static final int TURF = DAILY_A.minY() + GolfPlanner.TURF_ABOVE_FLOOR;

    private GolfKit() {
    }

    /**
     * A flat, walled straight in the first plot: width 5, the tee at local (9, 3), the cup
     * {@code distance} ahead and {@code off} to the side.
     */
    static HoleLayout straight(int distance, int off) {
        HoleTemplate.Sketch s = new HoleTemplate.Sketch();
        s.lane(7, 11, 2, 3 + distance + 1, 0);
        s.tee(9, 3);
        s.cup(9 + off, 3 + distance);
        return s.render(HoleTemplate.STRAIGHT, false, PLOT_X, PLOT_Z, TURF, "straight " + distance);
    }

    /**
     * A short dogleg, width 5: {@code up} along the first leg to the elbow's middle, then
     * {@code across} to the cup, the cup {@code offZ} off the across leg's middle; slime on the
     * elbow's far walls.
     */
    static HoleLayout dogleg(int up, int across, int offZ) {
        HoleTemplate.Sketch s = new HoleTemplate.Sketch();
        int elbow = 3 + up;
        s.lane(2, 6, 2, elbow + 2, 0);
        s.lane(2, 4 + across + 1, elbow - 2, elbow + 2, 0);
        s.tee(4, 3);
        s.cup(4 + across, elbow + offZ);
        s.slimeWalls(1, 7, elbow + 3, elbow + 3);
        s.slimeWalls(1, 1, elbow - 2, elbow + 3);
        return s.render(HoleTemplate.DOGLEG, false, PLOT_X, PLOT_Z, TURF, "dogleg " + up + "/" + across);
    }

    /**
     * An island green: a 5-wide approach of {@code approach}, then a raised 5 x 5 green entered by
     * a slab ramp over lane columns {@code from}..{@code to} (7..11 is the lane), the cup in the
     * green's middle.
     */
    static HoleLayout island(int approach, int from, int to) {
        HoleTemplate.Sketch s = new HoleTemplate.Sketch();
        int ramp = 3 + approach;
        s.lane(7, 11, 2, ramp - 1, 0);
        s.lane(7, 11, ramp, ramp + 5, 2);
        s.lane(from, to, ramp, ramp, 1);
        s.tee(9, 3);
        s.cup(9, ramp + 3);
        return s.render(HoleTemplate.ISLAND, false, PLOT_X, PLOT_Z, TURF, "island " + approach);
    }

    /** A layout's blocks on a grid round its plot. */
    static PlanBlocks grid(HoleLayout l) {
        return l.grid(GolfPlanner.plotBox(l));
    }

    /** The hole to play (par doesn't matter to the ball). */
    static GolfCourse.Hole hole(HoleLayout l) {
        return l.hole(GolfCourse.MIN_PAR);
    }

    /** A drawn template in the first plot. */
    static HoleLayout draw(HoleTemplate t, char tier, long seed) {
        return t.draw(new GenRandom(seed).fork("test"), tier, PLOT_X, PLOT_Z, TURF);
    }

    /** A plan input for a golf slot's half A at its 0.35 spot. */
    static PlanInput input(Slots.Def slot, long seed) {
        return input(slot, seed, slot.tierOrMix(), 0);
    }

    static PlanInput input(Slots.Def slot, long seed, String mix, long budget) {
        return new PlanInput(slot, LegacyBoxes.half(slot, 'A'), 'A', 20725, 0, seed, mix, 8, budget, null);
    }
}
