package com.dierks.homecraft.games.gen.boat;

import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlanInput;
import com.dierks.homecraft.games.trial.Course;

import java.util.List;

/**
 * The independent check of an Ice Boat plan (GEN-SPEC §4.4, Course Variety §1.4), run before a
 * single block is set: in the planner, and again through {@code PlanCheck.generator}.
 *
 * <p>It dispatches on the plan's own {@link Plan#algo()}, never on today's planner version, so a
 * layout is always judged by the rules it was made under (rule R5):
 * <ul>
 *   <li><b>algo {@value LoopValidatorV2#LAST_ALGO} or older</b>, the flat loop:
 *       {@link LoopValidatorV2}, frozen and pinned by the serialised algo-2 plans;</li>
 *   <li><b>algo 3 on</b>, the Mountain Run: the downhill validator (V1-V13). Until it lands this
 *       answers {@value #NO_V3}, so no algo-3 plan is ever built unproven.</li>
 * </ul>
 * Pure: no Bukkit.
 */
public final class BoatValidator {

    /** The most checkpoints a course may have. */
    public static final int MAX_CHECKPOINTS = Course.MAX_CHECKPOINTS;
    /** What an algo-3 plan gets until its validator is switched on: a refusal, never a pass. */
    public static final String NO_V3 = "no v3 validator yet";

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
        return downhill(plan, tier);
    }

    /**
     * The Mountain Run's check (algo 3 on). The boat-proof package switches this on:
     * {@code DownhillValidator.problems(plan, tier)}.
     */
    static List<String> downhill(Plan plan, String tier) {
        return List.of(NO_V3);
    }
}
