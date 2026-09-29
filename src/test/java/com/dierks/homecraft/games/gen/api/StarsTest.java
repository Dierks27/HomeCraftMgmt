package com.dierks.homecraft.games.gen.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Stars (GEN-SPEC §5.2): a trial's 3 at or under gold, 2 at or under silver, 1 for finishing; golf's
 * 3 at or under par, 2 at or under par + a third of the holes (rounded up), 1 for finishing; star
 * times rounded up to a whole second; what the next star needs; and how stars look.
 */
class StarsTest {

    @Test
    void aTrialGivesThreeTwoOrOneStar() {
        assertEquals(3, Stars.trial(76_000, 76_000, 114_000), "exactly gold is three stars");
        assertEquals(3, Stars.trial(40_000, 76_000, 114_000), "under gold too");
        assertEquals(2, Stars.trial(76_001, 76_000, 114_000), "a millisecond over gold is two");
        assertEquals(2, Stars.trial(114_000, 76_000, 114_000), "exactly silver is two");
        assertEquals(1, Stars.trial(114_001, 76_000, 114_000), "over silver is one: finishing always counts");
        assertEquals(1, Stars.trial(1, 0, 0), "star times that aren't set can't be reached");
    }

    @Test
    void golfGivesThreeTwoOrOneStar() {
        assertEquals(3, Stars.golf(29, 29, 9), "par is three stars");
        assertEquals(3, Stars.golf(25, 29, 9), "under par too");
        assertEquals(2, Stars.golf(32, 29, 9), "par + 3 on nine holes is two");
        assertEquals(1, Stars.golf(33, 29, 9), "par + 4 is one");
        assertEquals(29 + 3, Stars.golfSilver(29, 9), "nine holes: par + 3");
        assertEquals(9 + 1, Stars.golfSilver(9, 3), "three holes: par + 1");
        assertEquals(9 + 2, Stars.golfSilver(9, 4), "four holes: par + 2 (a third, rounded up)");
        assertEquals(2, Stars.golf(10, 9, 3), "Tiny Golf one over par is two stars");
    }

    @Test
    void starTimesAreRoundedUpToAWholeSecond() {
        assertEquals(76_000, Stars.threshold(38_000, 2.0), "the spec's easy example");
        assertEquals(114_000, Stars.threshold(38_000, 3.0), "and its silver");
        assertEquals(48_000, Stars.threshold(38_000, 1.25), "47.5 s rounds up to 48 s");
        assertEquals(84_000, Stars.threshold(38_000, 2.2), "83.6 s rounds up to 84 s");
        assertEquals(0, Stars.threshold(0, 2.0), "no reference, no star time");
        assertEquals(0, Stars.threshold(38_000, 0), "no factor, no star time");
        assertEquals(0, Stars.threshold(38_000, Double.NaN), "a broken factor, no star time");
    }

    @Test
    void theNextStarIsTheNextLine() {
        assertEquals(114_000, Stars.nextTrialMs(1, 76_000, 114_000), "from one star: silver");
        assertEquals(76_000, Stars.nextTrialMs(2, 76_000, 114_000), "from two: gold");
        assertEquals(-1, Stars.nextTrialMs(3, 76_000, 114_000), "three is the top");
        assertEquals(32, Stars.nextGolfStrokes(1, 29, 9), "golf from one star: par + 3");
        assertEquals(29, Stars.nextGolfStrokes(2, 29, 9), "from two: par");
        assertEquals(-1, Stars.nextGolfStrokes(3, 29, 9), "three is the top");
    }

    @Test
    void starsLookLikeStars() {
        assertEquals("★★☆", Stars.text(2), "two filled, one empty");
        assertEquals("★★★", Stars.text(3), "three filled");
        assertEquals("☆☆☆", Stars.text(0), "none filled");
        assertEquals("★★★", Stars.text(9), "never more than three");
        assertEquals(3, Stars.MAX, "three stars at most");
    }
}
