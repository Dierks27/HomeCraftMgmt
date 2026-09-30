package com.dierks.homecraft.games.gen;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every 0.35 install of {@link LayoutScenarios} upgraded with the real rules: each kind of built thing,
 * alone, keeps every place where and as 0.35 built it with every claim still matching (so nothing is
 * rerolled away from its blocks); an install that built nothing takes the new layout.
 */
class LayoutGuardScenarioTest {

    @Test
    void everyScenarioComesOutRight() {
        assertEquals(List.of(), LayoutScenarios.failures(LayoutGuard.RULES),
                "a built install keeps 0.35's layout, an empty one takes the new one");
    }

    @Test
    void theScenariosCoverEveryKindOfBuiltThingTheDecisionsName() {
        List<String> names = LayoutScenarios.ALL.stream().map(LayoutScenarios.Scenario::name).toList();
        for (String kind : List.of("a Fresh set up", "a Classic holding a recall", "a kept course",
                "the Clubhouse claimed", "the arena claimed", "never switched on")) {
            assertTrue(names.contains(kind), "LAYOUT-DECISIONS item 5 names this case: " + kind);
        }
        assertTrue(LayoutScenarios.ALL.stream().anyMatch(s -> !s.built()), "and one that built nothing");
    }
}
