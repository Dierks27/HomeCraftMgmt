package com.dierks.homecraft.games.trial;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Racer bumping by tier (the owner's decision after the 0.37.1 live test): off on Easy and Medium, kept on Hard
 * and harder, a course with no tier counted as Medium; boats keep their own rules on every tier (a racer not
 * collidable, never on the no-push team: boats bump), and a solo dropper run keeps its own (DropperRun).
 */
class BumpingTest {

    private static final List<TrialKind> ON_FOOT = List.of(TrialKind.PARKOUR, TrialKind.ELYTRA);

    @Test
    void onlyHardAndHarderKeepTheBumping() {
        assertFalse(Bumping.bumps(Tier.EASY), "easy: nobody bumps");
        assertFalse(Bumping.bumps(Tier.MEDIUM), "medium: nobody bumps");
        assertFalse(Bumping.bumps(null), "no tier counts as medium");
        assertTrue(Bumping.bumps(Tier.HARD), "hard keeps it");
        assertTrue(Bumping.bumps(Tier.EXTREME), "and so does the tier past hard");
    }

    @Test
    void racersOnFootAreKeptApartOnEasyAndMediumAndBumpOnHard() {
        for (TrialKind kind : ON_FOOT) {
            assertEquals(Bumping.Rule.APART, Bumping.race(kind, Tier.EASY), kind + " easy");
            assertEquals(Bumping.Rule.APART, Bumping.race(kind, Tier.MEDIUM), kind + " medium");
            assertEquals(Bumping.Rule.APART, Bumping.race(kind, null), kind + " with no tier");
            assertEquals(Bumping.Rule.NONE, Bumping.race(kind, Tier.HARD), kind + " hard: no team, still collidable");
            assertEquals(Bumping.Rule.NONE, Bumping.race(kind, Tier.EXTREME), kind + " extreme");
        }
    }

    @Test
    void aBoatRaceKeepsItsOwnRuleOnEveryTier() {
        for (Tier tier : Tier.values()) {
            assertEquals(Bumping.Rule.RIDER, Bumping.race(TrialKind.BOAT, tier),
                    tier + ": the rider not collidable, never on the team (boats bump)");
        }
        assertEquals(Bumping.Rule.RIDER, Bumping.race(TrialKind.BOAT, null));
    }

    @Test
    void soloRunsOnFootAreKeptApartOnEasyAndMediumToo() {
        for (TrialKind kind : ON_FOOT) {
            assertEquals(Bumping.Rule.APART, Bumping.run(kind, Tier.EASY), kind + " easy: two solo runs side by side");
            assertEquals(Bumping.Rule.APART, Bumping.run(kind, Tier.MEDIUM), kind + " medium");
            assertEquals(Bumping.Rule.APART, Bumping.run(kind, null), kind + " with no tier");
            assertEquals(Bumping.Rule.NONE, Bumping.run(kind, Tier.HARD), kind + " hard: as before");
            assertEquals(Bumping.Rule.NONE, Bumping.run(kind, Tier.EXTREME), kind + " extreme: as before");
        }
    }

    @Test
    void aSoloBoatOrDropperRunIsAsBefore() {
        for (Tier tier : Tier.values()) {
            assertEquals(Bumping.Rule.NONE, Bumping.run(TrialKind.BOAT, tier), tier + ": a solo boat run is left alone");
            assertEquals(Bumping.Rule.NONE, Bumping.run(TrialKind.DROPPER, tier),
                    tier + ": a dropper run keeps every faller apart itself");
        }
    }
}
