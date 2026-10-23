package com.dierks.homecraft.arcade;

import com.dierks.homecraft.arcade.TrailService.Puff;
import com.dierks.homecraft.arcade.TrailService.Step;
import com.dierks.homecraft.arcade.TrailService.TrailPattern;
import org.bukkit.Particle;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The trail engine's patterns (SHOP-V2-SPEC §5.3, §5.5): a Keeper trail rotates its steps one per puff; the Royal
 * Trail puffs every 300 ms just over the crown (y 1.9, out of the eye line); every other trail is one step, today's
 * puff every 150 ms ({@link TrailPuffTest} is unchanged); a burst shows 12 particles shared out between the steps.
 */
class TrailPatternTest {

    @Test
    void aPatternRotatesItsSteps() {
        List<Integer> seen = new ArrayList<>();
        int i = 0;
        for (int puff = 0; puff < 7; puff++) {
            seen.add(i);
            i = TrailService.nextStep(i, 3);
        }
        assertEquals(List.of(0, 1, 2, 0, 1, 2, 0), seen, "Stardust: wax, wax, crit, and round again");
        assertEquals(0, TrailService.nextStep(0, 1), "one step: always the same");
        assertEquals(0, TrailService.nextStep(5, 0), "no steps: nothing to take");
        TrailPattern aurora = Keepers.pattern(Keepers.AURORA, Keepers.Variant.DEFAULT);
        assertEquals(List.of(Particle.SCRAPE, Particle.REVERSE_PORTAL),
                aurora.steps().stream().map(Step::particle).toList(), "Aurora: scrape, then reverse portal");
        assertEquals(150, aurora.puffMs());
    }

    @Test
    void theRoyalTrailPuffsOverTheCrown() {
        for (Keepers.Variant v : Keepers.Variant.values()) {
            TrailPattern royal = Keepers.pattern(Keepers.ROYAL, v);
            assertEquals(300, royal.puffMs(), "a puff every 300 ms");
            assertEquals(1, royal.steps().size());
            assertEquals(new Puff(1.9, 1, 0.2, 0.05, 0.2), royal.steps().get(0).puff(), "y 1.9, just over the crown");
        }
        assertEquals(Particle.TOTEM_OF_UNDYING, Keepers.pattern(Keepers.ROYAL, Keepers.Variant.DEFAULT).steps().get(0)
                .particle());
    }

    @Test
    void theOtherKeeperTrailsAreAsTheSpecDrawsThem() {
        TrailPattern stardust = Keepers.pattern(Keepers.STARDUST, Keepers.Variant.DEFAULT);
        assertEquals(List.of(new Step(Particle.WAX_ON, new Puff(1.3, 1, 0.3, 0.2, 0.3)),
                new Step(Particle.WAX_ON, new Puff(0.8, 1, 0.3, 0.2, 0.3)),
                new Step(Particle.CRIT, new Puff(1.0, 1, 0.25, 0.25, 0.25))), stardust.steps());
        TrailPattern frost = Keepers.pattern(Keepers.FROST, Keepers.Variant.DEFAULT);
        assertEquals(List.of(new Step(Particle.DOLPHIN, new Puff(0.15, 2, 0.2, 0.05, 0.2)),
                new Step(Particle.ITEM_SNOWBALL, new Puff(0.1, 1, 0.2, 0.02, 0.2))), frost.steps());
        assertEquals(new Step(Particle.SCRAPE, new Puff(1.7, 1, 0.35, 0.15, 0.35)),
                Keepers.pattern(Keepers.AURORA, Keepers.Variant.DEFAULT).steps().get(0));
        assertEquals(new Step(Particle.REVERSE_PORTAL, new Puff(1.5, 1, 0.3, 0.1, 0.3)),
                Keepers.pattern(Keepers.AURORA, Keepers.Variant.DEFAULT).steps().get(1));
        for (String id : List.of(Keepers.AURORA, Keepers.STARDUST, Keepers.FROST)) {
            assertEquals(150, Keepers.pattern(id, Keepers.Variant.DEFAULT).puffMs(), id);
            assertEquals(150, Keepers.pattern(id, Keepers.Variant.FALLBACK).puffMs(), id);
        }
    }

    @Test
    void aRentalIsOneStepOfItsOwnPuff() {
        for (String particle : List.of("HEART", "NOTE", "FIREWORK", "SNOWFLAKE", "END_ROD", "HAPPY_VILLAGER", "GLOW",
                "ENCHANT", "ELECTRIC_SPARK")) {
            TrailPattern single = TrailService.single(Particle.valueOf(particle));
            assertEquals(TrailService.PUFF_MS, single.puffMs(), particle + ": every 150 ms, as always");
            assertEquals(List.of(new Step(Particle.valueOf(particle), TrailService.puff(particle))), single.steps(),
                    particle + ": one step, today's puff");
        }
        assertEquals(new Puff(0.15, 2, 0.15, 0.05, 0.15), TrailService.single(Particle.HEART).steps().get(0).puff());
    }

    @Test
    void aBurstSharesItsTwelveOut() {
        assertEquals(12, TrailService.burstEach(1), "one step: all twelve, as before");
        assertEquals(6, TrailService.burstEach(2));
        assertEquals(4, TrailService.burstEach(3));
        assertEquals(12, TrailService.burstEach(0));
    }

    @Test
    void aPuffWaitsForItsPatternsPace() {
        long t = 1_000_000L;
        assertEquals(true, TrailService.due(null, t, 300), "the first puff");
        assertEquals(false, TrailService.due(t, t + 299, 300));
        assertEquals(true, TrailService.due(t, t + 300, 300));
        assertEquals(true, TrailService.due(t, t + 150, 150));
    }
}
