package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.arcade.TokenService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The time trials' words, with no server.
 *
 * <p>Pinned here: a time reads as minutes, seconds and tenths, floored (never better than it
 * was); a course is labelled by kind and tier, the hardest tier with its own name; tiers and kinds
 * are read in any case and nothing else is; each kind pays under its own ledger source; a course
 * id is a short lower-case word; a name comes from the id; and a typed name loses colour codes and
 * anything Bedrock can't draw.
 */
class TrialTextTest {

    @Test
    void aTimeReadsAsMinutesSecondsAndTenthsFloored() {
        assertEquals("0:00.0", TrialText.time(0), "nothing yet");
        assertEquals("1:02.3", TrialText.time(62_345), "a minute and two seconds");
        assertEquals("0:59.9", TrialText.time(59_999), "floored: never shown better than it was");
        assertEquals("60:00.0", TrialText.time(3_600_000), "an hour is sixty minutes");
        assertEquals("0:00.0", TrialText.time(-5), "never negative");
    }

    @Test
    void aCourseIsLabelledByKindAndTier() {
        Course c = Course.create("river_run", TrialKind.BOAT, Tier.MEDIUM);
        assertEquals("Boat · Medium", TrialText.label(c), "kind · tier");
        assertEquals("Why did we build this?", Tier.EXTREME.label(), "the hardest tier's name");
        assertEquals(List.of("Easy", "Medium", "Hard", "Why did we build this?"),
                List.of(Tier.EASY.label(), Tier.MEDIUM.label(), Tier.HARD.label(), Tier.EXTREME.label()),
                "easiest first");
    }

    @Test
    void tiersAndKindsAreReadInAnyCaseAndNothingElseIs() {
        assertEquals(Tier.HARD, Tier.of(" Hard "), "any case, spaces trimmed");
        assertNull(Tier.of("legendary"), "not a tier");
        assertEquals(TrialKind.ELYTRA, TrialKind.of("ELYTRA"), "any case");
        assertNull(TrialKind.of("minecart"), "not a kind");
        assertEquals(List.of("easy", "medium", "hard", "extreme"), Tier.ids(), "the config words, in order");
    }

    @Test
    void eachKindPaysUnderItsOwnLedgerSource() {
        assertEquals(TokenService.Source.GAMES_PARKOUR, TrialKind.PARKOUR.source(), "parkour");
        assertEquals(TokenService.Source.GAMES_ELYTRA, TrialKind.ELYTRA.source(), "elytra");
        assertEquals(TokenService.Source.GAMES_BOAT, TrialKind.BOAT.source(), "boat");
    }

    @Test
    void aCourseIdIsAShortLowerCaseWord() {
        assertTrue(TrialText.validId("river_run"), "letters and an underscore");
        assertTrue(TrialText.validId("cliffs2"), "a digit after the first letter");
        assertFalse(TrialText.validId("a"), "too short");
        assertFalse(TrialText.validId("2fast"), "must start with a letter");
        assertFalse(TrialText.validId("River"), "lower case only");
        assertFalse(TrialText.validId("a".repeat(33)), "at most 32 characters");
        assertFalse(TrialText.validId("sky-high"), "no dashes");
        assertFalse(TrialText.validId(null), "nothing is not an id");
    }

    @Test
    void aNameComesFromTheId() {
        assertEquals("River Run", TrialText.defaultName("river_run"), "words capitalised");
        assertEquals("Cliffs2", TrialText.defaultName("cliffs2"), "one word");
        assertEquals("Course", TrialText.defaultName(""), "nothing to go on");
    }

    @Test
    void aTypedNameIsMadeSafeForEveryScreen() {
        assertEquals("River Run", TrialText.cleanName("&6River &lRun"), "colour codes out");
        assertEquals("Sky High", TrialText.cleanName("  Sky " + new String(Character.toChars(0x1F680)) + "  High "),
                "emoji above U+FFFF out, spaces squeezed");
        assertEquals(32, TrialText.cleanName("x".repeat(50)).length(), "cut to 32 characters");
        assertNull(TrialText.cleanName("&a&b "), "nothing left: no name");
        assertNull(TrialText.cleanName(null), "no name at all");
    }

    @Test
    void countsReadNaturally() {
        assertEquals("1 checkpoint", TrialText.checkpoints(1), "one");
        assertEquals("5 checkpoints", TrialText.checkpoints(5), "many");
        assertEquals("1 token", TrialText.tokens(1), "one");
        assertEquals("10 tokens", TrialText.tokens(10), "many");
    }
}
