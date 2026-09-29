package com.dierks.homecraft.games.gen.golf;

import com.dierks.homecraft.games.gen.api.GenFailed;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlanInput;
import com.dierks.homecraft.games.gen.api.Planner;
import com.dierks.homecraft.games.gen.api.Slots;

/**
 * The planner for Daily Golf and Tiny Golf, GEN-SPEC §4.3.
 *
 * <p>A stub until WP3 lands: it has its id and version, and every plan fails with
 * "{@value GenFailed#NOT_BUILT}", so the engine keeps the slot closed and status says why. Nothing
 * else changes when the real body replaces this one.
 */
public final class GolfPlanner implements Planner {

    /** Its version; the real planner bumps it whenever what it makes for a seed changes. */
    public static final int ALGO = 1;

    @Override
    public String id() {
        return Slots.GOLF;
    }

    @Override
    public int algo() {
        return ALGO;
    }

    @Override
    public Plan plan(PlanInput in) throws GenFailed {
        throw new GenFailed(GenFailed.NOT_BUILT);
    }

    @Override
    public Plan rederive(PlanInput in, GenTag tag) throws GenFailed {
        throw new GenFailed(GenFailed.NOT_BUILT);
    }
}
