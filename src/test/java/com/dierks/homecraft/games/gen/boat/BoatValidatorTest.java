package com.dierks.homecraft.games.gen.boat;

import com.dierks.homecraft.games.gen.V2Fixtures;
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
 * The Ice Boat check's dispatcher (Course Variety §1.4, rule R5): a plan is judged by the rules of
 * its OWN algo. Algo 1 and 2 go to the frozen {@link LoopValidatorV2}, word for word; algo 3 on
 * goes to the Mountain Run's {@link DownhillValidator}.
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
        Plan later = withAlgo(V2Fixtures.boat("hard").plan(), 4);
        assertEquals(DownhillValidator.problems(later, "hard"), BoatValidator.problems(later, "hard"),
                "a later algo goes to the downhill check too");
        for (String tier : List.of("easy", "medium", "hard")) {
            Plan run = HandRun.of(tier).plan();
            assertEquals(List.of(), BoatValidator.problems(run, tier),
                    tier + ": a proven Mountain Run passes through the dispatcher");
            assertFalse(BoatValidator.problems(withAlgo(run, 2), tier).isEmpty(),
                    tier + ": and the same blocks called algo 2 are judged as a loop, which they aren't");
        }
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
