package com.dierks.homecraft.gui.games;

import com.dierks.homecraft.games.Breaks;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Take a break's words and choices (spec §4.3, R1.11-R1.13): what the screen says about today,
 * the state, and what picking each limit or pause would do — before the player picks it.
 */
class BreakMenuTest {

    @Test
    void todayReadsAgainstTheLimitOrAloneWithoutOne() {
        assertEquals("Today: 35 of 100 tokens", BreakMenu.todayLine(35, 100), "the spec's own example");
        assertEquals("Today: 35 tokens", BreakMenu.todayLine(35, Breaks.NO_LIMIT), "no limit at all: just the count");
        assertEquals("Today: 1 token", BreakMenu.todayLine(1, Breaks.NO_LIMIT), "one token is singular");
        assertEquals("Today: 0 of 0 tokens", BreakMenu.todayLine(0, 0), "a limit of 0 is still a limit");
    }

    @Test
    void limitsReadAsTokensADay() {
        assertEquals("25 tokens a day", BreakMenu.limitText(25), "a limit is tokens a day");
        assertEquals("1 token a day", BreakMenu.limitText(1), "singular for one");
        assertEquals("no limit", BreakMenu.limitText(Breaks.NO_LIMIT), "none reads as no limit");
    }

    @Test
    void theStateNameSaysPausedFirstThenTheLimit() {
        assertEquals("&bPaused until Tue 12 AM", BreakMenu.stateName(2_000, 1_000, 25, "Tue 12 AM"),
                "a pause is the headline while it lasts, even with a limit set");
        assertEquals("&bTake a break &7- limit 25 a day", BreakMenu.stateName(1_000, 1_000, 25, "x"),
                "a pause that has just ended no longer shows; the limit does");
        assertEquals("&bTake a break", BreakMenu.stateName(0, 1_000, Breaks.NO_LIMIT, "x"),
                "nothing set: just the name");
    }

    @Test
    void stricterStartsNowAndLooserWaits() {
        assertSame(BreakMenu.Choice.CURRENT, BreakMenu.choice(25, Breaks.NO_PENDING, 25), "25 is already the limit");
        assertSame(BreakMenu.Choice.NOW, BreakMenu.choice(25, Breaks.NO_PENDING, 10), "lower starts now");
        assertSame(BreakMenu.Choice.LATER, BreakMenu.choice(25, Breaks.NO_PENDING, 50), "higher waits");
        assertSame(BreakMenu.Choice.LATER, BreakMenu.choice(25, Breaks.NO_PENDING, Breaks.NO_LIMIT),
                "removing the limit is a raise, so it waits too");
        assertSame(BreakMenu.Choice.NOW, BreakMenu.choice(Breaks.NO_LIMIT, Breaks.NO_PENDING, 100),
                "a first limit is stricter than none, so it starts now");
        assertSame(BreakMenu.Choice.CURRENT, BreakMenu.choice(Breaks.NO_LIMIT, Breaks.NO_PENDING, Breaks.NO_LIMIT),
                "no limit is already the state");
        assertSame(BreakMenu.Choice.WAITING, BreakMenu.choice(25, 100, 100), "the raise already waiting");
        assertSame(BreakMenu.Choice.WAITING, BreakMenu.choice(25, Breaks.NO_LIMIT, Breaks.NO_LIMIT),
                "a waiting removal shows as waiting, not as a new raise");
        assertSame(BreakMenu.Choice.LATER, BreakMenu.choice(25, 100, 50), "another raise replaces the waiting one");
    }

    @Test
    void aPauseIsOnlyOfferedWhenItMakesThePauseLonger() {
        assertTrue(BreakMenu.lengthens(0, 5_000), "no pause yet: any pause lengthens it");
        assertFalse(BreakMenu.lengthens(5_000, 5_000), "the same end changes nothing");
        assertFalse(BreakMenu.lengthens(9_000, 5_000), "an earlier end would shorten it: never offered");
        assertTrue(BreakMenu.lengthens(5_000, 9_000), "a later end lengthens it");
    }

    @Test
    void theChoicesAreTheConfiguredOnesThenNoLimit() {
        assertEquals(List.of(10, 25, 50, 100, Breaks.NO_LIMIT), BreakMenu.choices(List.of(10, 25, 50, 100)),
                "the shipped choices, then \"No limit of my own\"");
        assertEquals(List.of(5, Breaks.NO_LIMIT), BreakMenu.choices(List.of(5, 5, -3)),
                "repeats and negatives are left out");
        assertEquals(List.of(1, 2, 3, 4, 5, Breaks.NO_LIMIT), BreakMenu.choices(List.of(1, 2, 3, 4, 5, 6, 7)),
                "at most five fit in the row next to No limit");
    }

    @Test
    void theScreenListsExactlyWhatItCovers() {
        assertEquals("Ore Slots, Twenty-One, the Wheel, Higher or Lower, Coin Flip, Crates, Scratch Tickets "
                + "and Card Packs bought with tokens.", BreakMenu.COVERS, "R1.11's list, word for word");
        List<String> lines = BreakMenu.wrap(BreakMenu.COVERS, 34);
        assertEquals(BreakMenu.COVERS, String.join(" ", lines), "wrapping loses and adds nothing");
        for (String line : lines) {
            assertTrue(line.length() <= 34, "a lore line fits the width: " + line);
        }
    }
}
