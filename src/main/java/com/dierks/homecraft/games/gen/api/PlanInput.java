package com.dierks.homecraft.games.gen.api;

import java.util.function.BooleanSupplier;

/**
 * Everything a planner is given (GEN-SPEC §3.3 step 1, §4.0), copied on the main thread before the
 * planner thread starts, so a plan never reads live config, the world or the database.
 *
 * @param slot       the slot being planned
 * @param half       the half the plan is for (every op must be inside it)
 * @param halfId     'A' or 'B'
 * @param day        the course day
 * @param reroll     0, or the admin's reroll number that day
 * @param seed       the seed ({@link GenSeed}, or an admin's pin)
 * @param tierOrMix  the difficulty: a tier ({@code easy}) or a golf mix ({@code EEEMMMMHH})
 * @param fallDepth  the live {@code trials.fall_depth} (parkour's fall rule for medium and hard)
 * @param workBudget the most counted work (simulated shots, search nodes) the plan may take
 * @param cancelled  true once the job was cancelled (a reload, a stop): give up with {@link GenFailed}
 */
public record PlanInput(Slots.Def slot, Box half, char halfId, long day, int reroll, long seed,
                        String tierOrMix, int fallDepth, long workBudget, BooleanSupplier cancelled) {

    public PlanInput {
        if (slot == null || half == null) {
            throw new IllegalArgumentException("a plan needs its slot and half");
        }
        cancelled = cancelled == null ? () -> false : cancelled;
    }

    /** Throw {@link GenFailed} if the job was cancelled; call it between units of work. */
    public void checkCancelled() throws GenFailed {
        if (cancelled.getAsBoolean()) {
            throw new GenFailed("cancelled");
        }
    }
}
