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

    /**
     * How a course of version {@code algo} made for {@code def} is made again from its seed (a {@code seed:}
     * recall or keep): by the planner that made it, in the half size it was made in, when this planner is that
     * version or keeps it frozen ({@link Remake#exact}: the same course, so its old records are still the ones
     * to beat); otherwise by this planner at {@code def}'s size today — a different course from the same seed,
     * which must not take over the old one's records. This planner at its own version is exact; a planner that
     * keeps an older version frozen says so (golf: Adventure Golf, {@code GolfPlanner.v3()}).
     */
    default Remake remake(Slots.Def def, int algo) {
        return new Remake(this, def.sizeX(), def.sizeY(), def.sizeZ(), algo == algo());
    }

    /**
     * How a course is made again from its seed ({@link #remake}).
     *
     * @param planner the planner to plan it with
     * @param sizeX   the half's size to plan it in (x)
     * @param sizeY   (y)
     * @param sizeZ   (z)
     * @param exact   whether {@code planner} is the version that made it, so it gives the same course back;
     *                {@code false}: today's planner, another course from the same seed
     */
    record Remake(Planner planner, int sizeX, int sizeY, int sizeZ, boolean exact) {
    }
}
