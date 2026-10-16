package com.dierks.homecraft.games.gen.boat;

import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlanInput;

import java.util.List;

/**
 * The independent check of an Ice Boat plan (GEN-SPEC §4.4, Course Variety §1.4, MOUNTAIN-V2-SPEC
 * §9.1), run before a single block is set: in the planner, and again through
 * {@code PlanCheck.generator}.
 *
 * <p>It dispatches on the plan's own {@link Plan#algo()}, never on today's planner version, so a
 * layout is always judged by the rules it was made under (rule R5):
 * <ul>
 *   <li><b>algo {@value LoopValidatorV2#LAST_ALGO} or older</b>, the flat loop:
 *       {@link LoopValidatorV2}, frozen and pinned by the serialised algo-2 plans;</li>
 *   <li><b>algo {@value DownhillValidator#FIRST_ALGO}</b>, the Mountain Run (the spiral):
 *       {@link DownhillValidator} (V1-V13), frozen and pinned by the serialised algo-3 plans;</li>
 *   <li><b>algo {@value MountainValidator#FIRST_ALGO} on</b>, Mountain Run v2 (the mountain):
 *       {@link MountainValidator}, the same proof scaled and column-sparse.</li>
 * </ul>
 * Each keeps its own caps: 64 checkpoints for the loop and the spiral (literals, frozen),
 * {@value MountainValidator#MAX_CHECKPOINTS} for v2. Pure: no Bukkit.
 */
public final class BoatValidator {

    /**
     * The most checkpoints a Fresh Ice Boat course of any algo may have: v2's
     * ({@link MountainValidator#MAX_CHECKPOINTS}); older algos keep their own 64.
     */
    public static final int MAX_CHECKPOINTS = MountainValidator.MAX_CHECKPOINTS;

    private BoatValidator() {
    }

    /** {@link #problems(Plan, String)} with the tier of the input. */
    public static List<String> problems(Plan plan, PlanInput in) {
        return problems(plan, in.slot().normalise(in.tierOrMix()));
    }

    /**
     * What is wrong with {@code plan} as a {@code tier} Ice Boat course, by the rules of its own
     * algo; empty when nothing is.
     */
    public static List<String> problems(Plan plan, String tier) {
        if (plan == null) {
            return List.of("there is no plan");
        }
        if (plan.algo() <= LoopValidatorV2.LAST_ALGO) {
            return LoopValidatorV2.problems(plan, tier);
        }
        if (plan.algo() < MountainValidator.FIRST_ALGO) {
            return downhill(plan, tier);
        }
        return mountain(plan, tier);
    }

    /** The Mountain Run's check (algo {@value DownhillValidator#FIRST_ALGO}): {@link DownhillValidator}, frozen. */
    static List<String> downhill(Plan plan, String tier) {
        return DownhillValidator.problems(plan, tier);
    }

    /** Mountain Run v2's check (algo {@value MountainValidator#FIRST_ALGO} on): {@link MountainValidator}. */
    static List<String> mountain(Plan plan, String tier) {
        return MountainValidator.problems(plan, tier);
    }
}
