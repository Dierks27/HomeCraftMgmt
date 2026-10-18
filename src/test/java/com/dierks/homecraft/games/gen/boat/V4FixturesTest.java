package com.dierks.homecraft.games.gen.boat;

import com.dierks.homecraft.games.gen.V4Fixtures;
import com.dierks.homecraft.games.gen.api.GenFailed;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlanInput;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.engine.PlanCheck;
import com.dierks.homecraft.games.trial.Course;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The algo-4 Mountain Runs frozen before the wide road (MOUNTAIN-V21-SPEC §5.1, D13 "if it is judged, it is
 * judged by the frozen v4 rules"): they read back whole, their hashes are the index's, and the v4 rules of
 * {@link MountainValidator} prove them, directly and through {@link BoatValidator}'s dispatch on their own
 * algo, whatever the planner and the v5 rules have become.
 */
class V4FixturesTest {

    @Test
    void theThreeFrozenMountainsReadBackWhole() {
        List<V4Fixtures.Fixture> all = V4Fixtures.all();
        assertEquals(List.of("boat-road-medium", "boat-road-easy", "boat-slalom-hard"),
                all.stream().map(V4Fixtures.Fixture::name).toList(),
                "two Winding Roads and a Slalom, in index order");
        Slots.Def slot = Slots.ICE_BOAT;
        for (V4Fixtures.Fixture f : all) {
            Plan p = f.plan();
            assertEquals(MountainValidator.FIRST_ALGO, p.algo(), f + ": made by the algo-4 planner");
            assertEquals(f.hash(), p.hash(), f + ": the stored blocks still hash to the index's layout");
            assertEquals(Plan.hash(p.palette(), p.ops(), p.signs(), p.course()), p.hash(),
                    f + ": and the hash is worked out from the blocks, not just stored");
            assertEquals(slot.id(), p.slot(), f + ": an Ice Boat plan");
            assertEquals(f.seed(), p.seed(), f + ": of the index's seed");
            assertEquals(slot.half('A'), p.half(), f + ": in Ice Boat's half A, 480 x 176 x 640 at 6080, 96, 2880");
            assertTrue(BoatPlanner.mountain(p.half()), f + ": a Mountain Run v2 half");
            assertEquals(f.name().contains("slalom"), MountainValidator.slalom(f.seed()),
                    f + ": the seed's style is the name's");
            assertEquals(MountainValidator.FIRST_ALGO, f.tag().algo(), f + ": its row's tag says algo 4");
            assertEquals(f.hash(), f.tag().planHash(), f + ": and names the same layout");
            assertEquals(f.seed(), f.tag().seed(), f + ": from the same seed");
            assertEquals(f.half(), f.tag().half(), f + ": in the same half");
            assertEquals(((com.dierks.homecraft.games.gen.api.PlannedTrial) p.course()).refMs(), f.tag().refMs(),
                    f + ": the row's reference time is the plan's");
            Course c = f.trial().course();
            assertTrue(c.checkpoints().size() <= MountainValidator.MAX_CHECKPOINTS,
                    f + ": within v2's " + MountainValidator.MAX_CHECKPOINTS + " checkpoints");
        }
        assertEquals(V4Fixtures.OWNER_SEED, V4Fixtures.boat("road", "medium").seed(),
                "the medium road is the one the owner rode: their preview's seed");
    }

    @Test
    void theFrozenV4RulesProveThemForeverDirectlyAndThroughTheDispatch() {
        for (V4Fixtures.Fixture f : V4Fixtures.all()) {
            Plan p = f.plan();
            boolean slalom = MountainValidator.slalom(p.seed());
            assertEquals(List.of(), BoatValidator.problems(p, f.tier()),
                    f + ": the dispatcher sends an algo-4 plan to the v2 check, which proves it by the v4 rules");
            PlanInput in = new PlanInput(Slots.ICE_BOAT, p.half(), f.half(), f.day(), 0, f.seed(), f.tier(), 6, 0,
                    null);
            assertEquals(List.of(), BoatValidator.problems(p, in), f + ": with the tier read from the input");
            assertEquals(List.of(), MountainValidator.problems(p, f.tier()), f + ": and directly");
            assertEquals(List.of(), MountainValidator.problems(p, MountainValidator.Rules.of(f.tier(), slalom)),
                    f + ": by the v4 table, named");
            assertEquals(MountainValidator.Rules.of(f.tier(), slalom),
                    MountainValidator.Rules.of(f.tier(), slalom, p.algo()), f + ": which is its own algo's table");
            List<String> wide = MountainValidator.problems(p, MountainValidator.Rules.of(f.tier(), slalom,
                    MountainValidator.WIDE_ALGO));
            assertTrue(wide.stream().anyMatch(s -> s.startsWith("the track falls only ")),
                    f + ": the wide road's table would refuse it as no downhill, so judging a layout by its own"
                            + " algo (rule R5) matters: " + wide);
        }
    }

    @Test
    void theirTagsKeepTheirModelTimeAndTheEnginesCaps() {
        for (V4Fixtures.Fixture f : V4Fixtures.all()) {
            assertEquals(f.tag().refMs() * 5 / 4, BoatPlanner.modelMs(f.tag()),
                    f + ": an algo-4 tag's T_m is its reference x 5/4, whatever the planner's version (D12)");
            Plan p = f.plan();
            assertEquals(PlanCheck.BOAT_V4_MAX_OPS, PlanCheck.maxOps(Slots.ICE_BOAT, p),
                    f + ": an algo-4 boat plan is held to the mountain's 400,000");
            assertTrue(p.ops().size() <= PlanCheck.BOAT_V4_MAX_OPS, f + ": " + p.ops().size() + " blocks");
            assertEquals(List.of(), PlanCheck.problems(p, Slots.ICE_BOAT, Slots.ICE_BOAT.half('A')),
                    f + ": the engine's own check passes it");
            PlanInput in = new PlanInput(Slots.ICE_BOAT, p.half(), f.half(), f.day(), 0, f.seed(), f.tier(), 6, 0,
                    null);
            assertEquals(List.of(), PlanCheck.generator(new BoatPlanner(), p, in),
                    f + ": and the generator's check (BoatValidator) too");
        }
    }

    @Test
    void anAlgoFourTagIsMadeAgainOnlyByTheAlgoFourPlanner() throws GenFailed {
        // Until the planner moves past algo 4 (0.37.1's ALGO 5, D12) the tag is made again block for block, which
        // shows the fixtures are the 0.37.0 planner's own layouts; from then on it is refused, never re-made by a
        // newer planner as something else (the engine takes the older-planner path: the structure check).
        for (V4Fixtures.Fixture f : V4Fixtures.all()) {
            PlanInput in = new PlanInput(Slots.ICE_BOAT, f.plan().half(), f.half(), f.day(), 0, f.seed(), f.tier(), 6,
                    BoatPlanner.WORK_BUDGET, null);
            if (BoatPlanner.ALGO == MountainValidator.FIRST_ALGO) {
                assertEquals(f.hash(), new BoatPlanner().rederive(in, f.tag()).hash(),
                        f + ": the algo-4 planner makes the frozen layout from its tag, block for block");
                continue;
            }
            GenFailed e = assertThrows(GenFailed.class, () -> new BoatPlanner().rederive(in, f.tag()),
                    f + ": a newer planner refuses an algo-4 tag");
            assertTrue(e.getMessage().contains("made by boat planner v4, this is v" + BoatPlanner.ALGO),
                    f + ": and says why: " + e.getMessage());
        }
    }
}
