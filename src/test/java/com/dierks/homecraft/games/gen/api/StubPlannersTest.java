package com.dierks.homecraft.games.gen.api;

import com.dierks.homecraft.games.gen.boat.BoatPlanner;
import com.dierks.homecraft.games.gen.dropper.DropperPlanner;
import com.dierks.homecraft.games.gen.golf.GolfPlanner;
import com.dierks.homecraft.games.gen.parkour.ParkourPlanner;
import com.dierks.homecraft.games.gen.rings.RingsPlanner;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The planners (GEN-SPEC §8): one per generator a slot names, each with its id and version. C0
 * shipped them as stubs failing with "not built yet"; WP2 and WP3 built every one (and the Dropper's
 * came with EVENTS-DROPPER-SPEC), so this now
 * pins that none of them is still a stub (the per-planner tests cover what they build).
 */
class StubPlannersTest {

    private static final List<Planner> PLANNERS = List.of(new ParkourPlanner(), new RingsPlanner(),
            new BoatPlanner(), new GolfPlanner(), new DropperPlanner());

    @Test
    void thereIsAPlannerForEveryGeneratorASlotNames() {
        Set<String> generators = Slots.ALL.stream().map(Slots.Def::generator).collect(Collectors.toSet());
        assertEquals(generators, PLANNERS.stream().map(Planner::id).collect(Collectors.toSet()),
                "parkour, rings, boat, golf and the dropper, one each");
        for (Planner p : PLANNERS) {
            assertTrue(p.algo() >= 1, p.id() + " has a version");
        }
    }

    @Test
    void noPlannerIsAStubAnyMore() {
        for (Planner p : PLANNERS) {
            Slots.Def slot = Slots.ALL.stream().filter(s -> s.generator().equals(p.id())).findFirst().orElseThrow();
            PlanInput in = new PlanInput(slot, slot.half('A'), 'A', 20725, 0, 1L, slot.tierOrMix(), 6,
                    50_000_000L, null);
            try {
                Plan plan = p.plan(in);
                assertEquals(slot.id(), plan.slot(), p.id() + " plans the slot it was given");
            } catch (GenFailed e) {
                assertNotEquals(GenFailed.NOT_BUILT, e.getMessage(), p.id() + " is built (WP2/WP3), not a stub");
            }
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
