package com.dierks.homecraft.arcade;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Arcade's result record says "some tokens came back" with its own field, and says it plainly
 * (spec §0.9, R1.18): the reveal no longer infers it from the icon's material.
 */
class ArcadeOutcomeTest {

    @Test
    void theOldConstructorsStillWorkAndMeanNothingCameBack() {
        ArcadeService.Outcome six = new ArcadeService.Outcome(true, null, null, "x", false, false);
        ArcadeService.Outcome five = new ArcadeService.Outcome(true, null, null, "x", true);
        assertEquals(0, six.returned(), "the six-argument form hands nothing back");
        assertFalse(six.someBack(), "a plain loss is not a part refund");
        assertEquals(0, five.returned(), "the five-argument form hands nothing back");
        assertFalse(five.big(), "the five-argument form is never a headline result");
    }

    @Test
    void somethingBackIsOnlyEverANonWin() {
        assertTrue(new ArcadeService.Outcome(true, null, null, "x", false, false, 3).someBack(),
                "no win, 3 tokens back: a part refund");
        assertFalse(new ArcadeService.Outcome(true, null, null, "x", true, false, 30).someBack(),
                "a win is a win, not tokens back");
    }

    @Test
    void tokensBackAreSaidExactly() {
        assertEquals("&f3 &7of your &f10 tokens &7back", ArcadeService.backText(3, 10),
                "a part refund says how many of how many");
        assertEquals("&7your &f10 tokens &7back", ArcadeService.backText(10, 10),
                "all of it back reads neutrally, never as a win");
    }
}
