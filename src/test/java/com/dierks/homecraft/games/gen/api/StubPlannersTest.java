package com.dierks.homecraft.games.gen.api;

import com.dierks.homecraft.games.gen.boat.BoatPlanner;
import com.dierks.homecraft.games.gen.golf.GolfPlanner;
import com.dierks.homecraft.games.gen.parkour.ParkourPlanner;
import com.dierks.homecraft.games.gen.rings.RingsPlanner;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The planners as C0 ships them (GEN-SPEC §8): one per generator a slot names, each with its id
 * and version, and every plan failing with "not built yet" until its package lands, so the engine
 * keeps those slots closed and says why.
 */
class StubPlannersTest {

    private static final List<Planner> PLANNERS = List.of(new ParkourPlanner(), new RingsPlanner(),
            new BoatPlanner(), new GolfPlanner());

    @Test
    void thereIsAPlannerForEveryGeneratorASlotNames() {
        Set<String> generators = Slots.ALL.stream().map(Slots.Def::generator).collect(Collectors.toSet());
        assertEquals(generators, PLANNERS.stream().map(Planner::id).collect(Collectors.toSet()),
                "parkour, rings, boat and golf, one each");
        for (Planner p : PLANNERS) {
            assertTrue(p.algo() >= 1, p.id() + " has a version");
        }
    }

    @Test
    void everyStubSaysItIsNotBuiltYet() {
        for (Planner p : PLANNERS) {
            if (p instanceof GolfPlanner) {
                continue; // built (WP3): GolfPlannerTest covers it
            }
            Slots.Def slot = Slots.ALL.stream().filter(s -> s.generator().equals(p.id())).findFirst().orElseThrow();
            PlanInput in = new PlanInput(slot, slot.half('A'), 'A', 20725, 0, 1L, slot.tierOrMix(), 8, 1000, null);
            GenFailed plan = assertThrows(GenFailed.class, () -> p.plan(in), p.id() + " can't plan yet");
            assertEquals("not built yet", plan.getMessage(), p.id() + " says why");
            GenFailed again = assertThrows(GenFailed.class, () -> p.rederive(in, null), p.id() + " can't re-derive");
            assertEquals(GenFailed.NOT_BUILT, again.getMessage(), p.id() + " says why again");
        }
    }

    @Test
    void aPlanInputKnowsWhenItWasCancelled() throws GenFailed {
        PlanInput never = new PlanInput(Slots.TINY_GOLF, Slots.TINY_GOLF.half('A'), 'A', 1, 0, 1, "EEE", 8, 1, null);
        never.checkCancelled(); // no cancel flag: carries on
        assertTrue(!never.cancelled().getAsBoolean(), "no flag is never cancelled");
        PlanInput stop = new PlanInput(Slots.TINY_GOLF, Slots.TINY_GOLF.half('A'), 'A', 1, 0, 1, "EEE", 8, 1,
                () -> true);
        assertThrows(GenFailed.class, stop::checkCancelled, "a cancelled job gives up with GenFailed");
        assertThrows(IllegalArgumentException.class,
                () -> new PlanInput(null, Slots.TINY_GOLF.half('A'), 'A', 1, 0, 1, "EEE", 8, 1, null),
                "a plan needs its slot");
    }
}
