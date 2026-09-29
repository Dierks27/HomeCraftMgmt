package com.dierks.homecraft.games.gen.api;

/**
 * One generator (GEN-SPEC §4.0): pure, deterministic, no Bukkit types. The same input always gives
 * the same plan, on any host, because every budget is counted work, never time.
 *
 * <p>Runs on the planner thread. It must never throw anything but {@link GenFailed}: whatever it
 * hits, it wraps. A plan it returns must pass its own independent validator; one that doesn't is a
 * failed try.
 */
public interface Planner {

    /** {@code parkour}, {@code rings}, {@code golf} or {@code boat}. */
    String id();

    /**
     * Its version. Any change to what it makes for a seed bumps it (golden hashes pin three seeds),
     * so a stored layout of an older version is checked structurally instead of re-derived.
     */
    int algo();

    /** Make the plan for {@code in}. */
    Plan plan(PlanInput in) throws GenFailed;

    /**
     * The same plan again from a stored tag, without searching (golf: from the stored attempts,
     * no solver), for the boot check and the rebuild of a live half.
     */
    Plan rederive(PlanInput in, GenTag tag) throws GenFailed;
}
