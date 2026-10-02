package com.dierks.homecraft.games.gen.boat;

import com.dierks.homecraft.games.gen.V2Fixtures;
import com.dierks.homecraft.games.gen.V3Fixtures;
import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlanInput;
import com.dierks.homecraft.games.gen.api.Slots;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The Ice Boat check's dispatcher (Course Variety §1.4, MOUNTAIN-V2-SPEC §9.1, rule R5): a plan is
 * judged by the rules of its OWN algo. Algo 1 and 2 go to the frozen {@link LoopValidatorV2}, word
 * for word; algo 3 to the frozen Mountain Run check, {@link DownhillValidator}; algo 4 on to Mountain
 * Run v2's {@link MountainValidator}.
 */
class BoatValidatorTest {

    private static Plan withAlgo(Plan p, int algo) {
        return Plan.of(p.slot(), algo, p.seed(), p.half(), p.palette(), p.ops(), p.signs(), p.keepClear(), p.course(),
                p.summary(), p.work());
    }

    @Test
    void anAlgoTwoLoopIsJudgedByTheFrozenLoopCheck() {
        for (V2Fixtures.Fixture f : V2Fixtures.boats()) {
            assertEquals(List.of(), BoatValidator.problems(f.plan(), f.tierOrMix()),
                    f + ": the frozen loop passes through the dispatcher");
            List<BlockOp> fewer = new ArrayList<>(f.plan().ops());
            fewer.remove(fewer.size() / 2);
            Plan broken = Plan.of(f.plan().slot(), 2, f.plan().seed(), f.plan().half(), f.plan().palette(), fewer,
                    f.plan().signs(), f.plan().keepClear(), f.plan().course(), f.plan().summary(), f.plan().work());
            List<String> v2 = LoopValidatorV2.problems(broken, f.tierOrMix());
            assertFalse(v2.isEmpty(), f + ": a block taken out is caught");
            assertEquals(v2, BoatValidator.problems(broken, f.tierOrMix()),
                    f + ": and the dispatcher says exactly what the frozen check says");
        }
        Plan one = withAlgo(V2Fixtures.boat("easy").plan(), 1);
        assertEquals(LoopValidatorV2.problems(one, "easy"), BoatValidator.problems(one, "easy"),
                "an algo 1 layout is the loop check's too");
    }

    @Test
    void anAlgoThreePlanIsJudgedByTheMountainRunsOwnCheck() {
        for (V2Fixtures.Fixture f : V2Fixtures.boats()) {
            Plan loop = withAlgo(f.plan(), 3);
            List<String> v3 = BoatValidator.problems(loop, f.tierOrMix());
            assertFalse(v3.isEmpty(), f + ": a flat loop is never a Mountain Run, so a v3 plan of one is refused");
            assertEquals(DownhillValidator.problems(loop, f.tierOrMix()), v3,
                    f + ": and refused by the downhill check's own words, never the loop's");
        }
        for (V3Fixtures.Fixture f : V3Fixtures.all()) {
            assertEquals(List.of(), BoatValidator.problems(f.plan(), f.tier()),
                    f + ": a frozen algo-3 spiral is still proven through the dispatcher");
        }
        for (String tier : List.of("easy", "medium", "hard")) {
            Plan run = HandRun.of(tier).plan();
            assertEquals(List.of(), BoatValidator.problems(run, tier),
                    tier + ": a proven Mountain Run passes through the dispatcher");
            assertFalse(BoatValidator.problems(withAlgo(run, 2), tier).isEmpty(),
                    tier + ": and the same blocks called algo 2 are judged as a loop, which they aren't");
        }
    }

    @Test
    void anAlgoFourPlanOnIsJudgedByMountainRunTwosCheck() {
        for (V3Fixtures.Fixture f : V3Fixtures.all()) {
            for (int algo : new int[]{4, 5}) {
                Plan later = withAlgo(f.plan(), algo);
                List<String> v4 = BoatValidator.problems(later, f.tier());
                assertEquals(MountainValidator.problems(later, f.tier()), v4,
                        f + " as algo " + algo + ": judged by Mountain Run v2's check, in its words");
                assertFalse(v4.isEmpty(), f + " as algo " + algo + ": a 128-block spiral is no v2 mountain, so refused");
                assertFalse(v4.equals(DownhillValidator.problems(later, f.tier())),
                        f + " as algo " + algo + ": and never by the frozen spiral check, which would pass it");
            }
        }
        Plan mountain = HandMountain.road("medium").plan();
        assertEquals(List.of(), BoatValidator.problems(mountain, "medium"),
                "a proven v2 mountain passes through the dispatcher");
        assertFalse(BoatValidator.problems(withAlgo(mountain, 3), "medium").isEmpty(),
                "and the same blocks called algo 3 are judged as a spiral, which they aren't");
        assertEquals(MountainValidator.MAX_CHECKPOINTS, BoatValidator.MAX_CHECKPOINTS,
                "the dispatcher's cap is v2's 128; the frozen checks keep their own 64");
        assertEquals(64, DownhillValidator.MAX_CHECKPOINTS, "the spiral's cap is a frozen 64");
        assertEquals(64, LoopValidatorV2.MAX_CHECKPOINTS, "and so is the loop's");
    }

    @Test
    void theTierIsReadFromTheInputAndNoPlanIsAProblem() {
        V2Fixtures.Fixture f = V2Fixtures.boat("medium");
        PlanInput in = new PlanInput(Slots.ICE_BOAT, f.plan().half(), f.half(), f.day(), 0, f.seed(), " Medium ", 6, 0,
                null);
        assertEquals(List.of(), BoatValidator.problems(f.plan(), in), "the input's tier, normalised");
        assertFalse(BoatValidator.problems(null, "medium").isEmpty(), "no plan is never a pass");
    }
}
