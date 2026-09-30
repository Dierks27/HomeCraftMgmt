package com.dierks.homecraft.games.gen;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The mutation proof for the guard's built-detection (LAYOUT-DECISIONS item 5): break it, and the
 * scenarios must notice. Each mutant drops one rule of {@link LayoutGuard#RULES} (so one kind of built
 * thing goes unseen), and one drops them all (every install looks empty); each runs every scenario of
 * {@link LayoutScenarios} and must get at least one of them wrong. A mutant that survives means a kind
 * of built thing no scenario proves, and a server with only that could have its courses rerolled away
 * from their blocks.
 */
class LayoutGuardMutationTest {

    @Test
    void droppingAnyOneRuleIsCaught() {
        for (LayoutGuard.Evidence rule : LayoutGuard.RULES) {
            List<LayoutGuard.Evidence> mutant = new ArrayList<>(LayoutGuard.RULES);
            mutant.remove(rule);
            List<String> failed = LayoutScenarios.failures(mutant);
            assertFalse(failed.isEmpty(), "the mutant without the '" + rule.name() + "' rule survived every scenario");
        }
    }

    @Test
    void anInstallThatLooksEmptyWhateverItBuiltIsCaughtByEveryBuiltScenario() {
        List<String> failed = LayoutScenarios.failures(List.of());
        long built = LayoutScenarios.ALL.stream().filter(LayoutScenarios.Scenario::built).count();
        assertEquals(built, failed.size(), "every built install is caught taking the new layout: " + failed);
        assertFalse(failed.stream().anyMatch(f -> f.startsWith("never switched on")),
                "and the empty one still comes out right");
        assertFalse(failed.stream().noneMatch(f -> f.contains("would be moved")),
                "a moved claim is what the oracle sees: " + failed);
    }

    @Test
    void anInstallThatLooksBuiltWhateverItBuiltIsCaughtToo() {
        List<LayoutGuard.Evidence> always = List.of(new LayoutGuard.Evidence("always", f -> List.of("everything")));
        List<String> failed = LayoutScenarios.failures(always);
        assertFalse(failed.isEmpty(), "an empty install kept on the old layout is wrong too: " + failed);
    }
}
