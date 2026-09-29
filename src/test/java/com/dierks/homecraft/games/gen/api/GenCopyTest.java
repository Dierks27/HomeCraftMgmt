package com.dierks.homecraft.games.gen.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Daily Courses' words (GEN-SPEC §7): every sign fits a sign on both editions (at most four lines
 * of fifteen plain ASCII characters), no line uses a word the Games never say or a character
 * Bedrock can't draw, and the checks really catch each of those.
 */
class GenCopyTest {

    @Test
    void everySignFitsASign() {
        for (List<String> sign : GenCopy.everySign()) {
            assertEquals(List.of(), GenCopy.signProblems(sign), "this sign reads on Java and Bedrock: " + sign);
        }
        assertEquals(List.of("EASY PARKOUR", "Hop to the", "GOLD pad!", "Blue = saved"), GenCopy.parkourStart("easy"),
                "the easy start sign, as the spec wrote it");
        assertEquals("HARD PARKOUR", GenCopy.parkourStart("HARD").get(0), "the tier is read in any case");
        assertEquals("PARKOUR", GenCopy.parkourStart(null).get(0), "and anything else is plain Parkour");
        assertEquals(List.of("HOLE 18", "Par 6", "Hit the ball", "to the flag!"), GenCopy.golfTee(18, 6),
                "the longest tee sign still fits");
    }

    @Test
    void noLineUsesABannedWordOrAGlyphBedrockLacks() {
        for (String line : GenCopy.everyLine()) {
            assertEquals(List.of(), GenCopy.copyProblems(line), "this line is fine for a child: " + line);
        }
        assertFalse(GenCopy.copyProblems("&eSo close! Try again").isEmpty(), "near-miss words are caught");
        assertFalse(GenCopy.copyProblems("&7You'll sink it next time").isEmpty(), "'sink' is caught");
        assertFalse(GenCopy.copyProblems("&7almost ready").isEmpty(), "'almost' is caught");
        assertFalse(GenCopy.copyProblems("&aLUCKY shot").isEmpty(), "in any case");
        assertTrue(GenCopy.copyProblems("&7Sunken cups and shots").isEmpty(), "a word that contains one is fine");
        assertFalse(GenCopy.copyProblems("Nice 🏆").isEmpty(), "an emoji is caught");
    }

    @Test
    void theSignCheckCatchesEachProblem() {
        assertFalse(GenCopy.signProblems(List.of("A line that is too long")).isEmpty(), "more than 15 characters");
        assertFalse(GenCopy.signProblems(List.of("a", "b", "c", "d", "e")).isEmpty(), "more than 4 lines");
        assertFalse(GenCopy.signProblems(List.of("café")).isEmpty(), "anything but plain ASCII");
        assertFalse(GenCopy.signProblems(List.of()).isEmpty(), "no lines at all");
        assertFalse(GenCopy.signProblems(java.util.Arrays.asList("ok", null)).isEmpty(), "a missing line");
        assertThrows(IllegalArgumentException.class,
                () -> new SignText(0, 0, 0, Palette.sign(0), List.of("This is far too long for a sign")),
                "a plan can't carry a sign a child can't read");
        assertThrows(IllegalArgumentException.class, () -> new SignText(0, 0, 0, " ", List.of("OK")),
                "nor one without its block");
        assertEquals(List.of("FINISH!"), new SignText(1, 2, 3, Palette.sign(4), GenCopy.finish()).lines(),
                "a good one is kept as written");
    }

    @Test
    void waitsReadLikeAClock() {
        assertEquals("11h 2m", GenCopy.span(11 * 3_600_000L + 2 * 60_000L + 59_000L), "hours and minutes");
        assertEquals("5m", GenCopy.span(5 * 60_000L), "minutes alone");
        assertEquals("less than a minute", GenCopy.span(59_000L), "under a minute");
        assertEquals("less than a minute", GenCopy.span(-5), "and never negative");
        assertEquals("&7New course in &f11h 2m", GenCopy.newIn(11 * 3_600_000L + 120_000L), "the tile's line");
        assertTrue(GenCopy.comingHere(1).contains("next 1 minute -"), "one minute is singular");
        assertTrue(GenCopy.timesUp("daily_golf").endsWith("/hcm play daily_golf"), "it says how to try the new one");
    }
}
