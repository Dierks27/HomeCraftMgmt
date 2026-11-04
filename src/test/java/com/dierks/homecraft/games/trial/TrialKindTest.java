package com.dierks.homecraft.games.trial;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The kinds' rules lines, with no server: a hand-built elytra course says its rockets come back to 3 at every
 * ring, and Sky Rings (an elytra course with a rocket budget) says its line from the course, the whole run's
 * rockets and what a white ring means, with the other lines its kind's.
 */
class TrialKindTest {

    private static Course elytra(Course.Elytra budget) {
        return new Course("sky", TrialKind.ELYTRA, "Sky", Tier.EASY, "games", new Course.Spot(0, 100, 0, 0, 0),
                List.of(new Course.Mark(0, 90, 30, 4)), new Course.Mark(0, 80, 60, 4), null, null, true, false, 1,
                null, budget);
    }

    @Test
    void aHandBuiltElytraCourseSaysItsRocketsComeBackToThree() {
        assertEquals(List.of("Glide through every ring in order.", "Rockets give a boost: back to 3 at every ring.",
                "Landing or water sends you back to your last ring."), TrialKind.ELYTRA.rules(), "the kind's lines");
        assertEquals(TrialKind.ELYTRA.rules(), TimeTrials.rulesFor(elytra(null)), "a course without a budget");
    }

    @Test
    void skyRingsSaysItsRocketsForTheWholeRun() {
        List<String> rules = TimeTrials.rulesFor(elytra(new Course.Elytra(8, List.of(6, 6), "G")));
        assertEquals("8 rockets for the whole run: a white ring means climb.", rules.get(1), "the course's own line");
        assertEquals(TrialKind.ELYTRA.rules().get(0), rules.get(0), "the rest are the kind's");
        assertEquals(TrialKind.ELYTRA.rules().get(2), rules.get(2));
        assertEquals(3, rules.size());
    }

    @Test
    void anotherKindsRulesAreItsOwn() {
        Course parkour = new Course("steps", TrialKind.PARKOUR, "Steps", Tier.EASY, "games",
                new Course.Spot(0, 64, 0, 0, 0), List.of(), new Course.Mark(10, 64, 0, 2), null, null, true, false, 1);
        assertEquals(TrialKind.PARKOUR.rules(), TimeTrials.rulesFor(parkour));
    }
}
