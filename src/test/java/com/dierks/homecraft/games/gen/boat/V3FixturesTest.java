package com.dierks.homecraft.games.gen.boat;

import com.dierks.homecraft.games.gen.V3Fixtures;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlanInput;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.trial.Course;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The algo-3 Mountain Runs frozen before Mountain Run v2 (MOUNTAIN-V2-SPEC §11.4, §15 "algo-3
 * fixtures pass the frozen DownhillValidator forever"): they read back whole, their hashes are the
 * index's, and the frozen check proves them, directly and through {@link BoatValidator}'s dispatch
 * on their own algo, whatever the planner has become.
 */
class V3FixturesTest {

    @Test
    void theThreeFrozenSpiralsReadBackWholeOnePerTier() {
        List<V3Fixtures.Fixture> all = V3Fixtures.all();
        assertEquals(List.of("boat-easy", "boat-medium", "boat-hard"), all.stream().map(V3Fixtures.Fixture::name).toList(),
                "one frozen Mountain Run per tier, in tier order");
        for (V3Fixtures.Fixture f : all) {
            Plan p = f.plan();
            assertEquals(3, p.algo(), f + ": made by the algo-3 planner");
            assertEquals(f.hash(), p.hash(), f + ": the stored blocks still hash to the index's layout");
            assertEquals(Plan.hash(p.palette(), p.ops(), p.signs(), p.course()), p.hash(),
                    f + ": and the hash is worked out from the blocks, not just stored");
            assertEquals(Slots.ICE_BOAT.id(), p.slot(), f + ": an Ice Boat plan");
            assertEquals(f.seed(), p.seed(), f + ": of the index's seed");
            assertEquals(128, p.half().sizeX(), f + ": in the 0.36 half, 128 wide");
            assertEquals(16, p.half().sizeY(), f + ": 16 high");
            assertEquals(128, p.half().sizeZ(), f + ": 128 deep");
            assertEquals(6080, p.half().minX(), f + ": at the 0.36 spot");
            assertEquals(5888, p.half().minZ(), f + ": at the 0.36 spot");
            assertEquals(3, f.tag().algo(), f + ": its row's tag says algo 3");
            assertEquals(f.hash(), f.tag().planHash(), f + ": and names the same layout");
            assertEquals(f.seed(), f.tag().seed(), f + ": from the same seed");
            Course c = f.trial().course();
            assertTrue(c.checkpoints().size() <= 64, f + ": within the frozen 64 checkpoints");
        }
        assertEquals(V3Fixtures.OWNER_SEED, V3Fixtures.boat("medium").seed(),
                "the medium spiral is the one the owner rode: his preview's seed");
    }

    @Test
    void theFrozenCheckProvesThemForeverDirectlyAndThroughTheDispatch() {
        for (V3Fixtures.Fixture f : V3Fixtures.all()) {
            assertEquals(List.of(), DownhillValidator.problems(f.plan(), f.tier()),
                    f + ": the frozen downhill check proves the algo-3 spiral");
            assertEquals(List.of(), BoatValidator.problems(f.plan(), f.tier()),
                    f + ": and the dispatcher sends an algo-3 plan to it, never to the v2 check");
            PlanInput in = new PlanInput(Slots.ICE_BOAT, f.plan().half(), f.half(), f.day(), 0, f.seed(), f.tier(), 6,
                    0, null);
            assertEquals(List.of(), BoatValidator.problems(f.plan(), in), f + ": with the tier read from the input");
            assertFalse(MountainValidator.problems(f.plan(), f.tier()).isEmpty(),
                    f + ": the v2 check would refuse it (a 128 x 16 x 128 spiral is no mountain): rule R5 matters");
        }
    }

    @Test
    void theAlgoThreeTagStillMakesEachFixtureAgain() {
        // The planner is algo 4 now; V4-DECISIONS keeps the spiral's classes, so an algo-3 tag is still made
        // again from its seed by them, block for block.
        for (V3Fixtures.Fixture f : V3Fixtures.all()) {
            PlanInput in = new PlanInput(Slots.ICE_BOAT, f.plan().half(), f.half(), f.day(), 0, f.seed(), f.tier(), 6,
                    BoatPlanner.WORK_BUDGET, null);
            GenTag tag = new GenTag(Slots.ICE_BOAT.id(), Slots.BOAT, BoatPlanner.ALGO_V3, f.day(), 0, f.seed(),
                    f.half(), f.hash(), 1, 2, 3, List.of(), List.of(), 0);
            try {
                assertEquals(f.hash(), new BoatPlanner().rederive(in, tag).hash(),
                        f + ": the algo-3 planner makes the frozen layout from its tag, block for block");
            } catch (com.dierks.homecraft.games.gen.api.GenFailed e) {
                throw new AssertionError(f + ": the algo-3 planner failed: " + e.getMessage(), e);
            }
        }
    }
}
