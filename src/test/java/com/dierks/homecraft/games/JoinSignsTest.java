package com.dierks.homecraft.games;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How a join sign is recognised, read, and who may write one (spec §8.5). */
class JoinSignsTest {

    @Test
    void theHeaderIsArcadeInBracketsInAnyCase() {
        assertTrue(JoinSigns.isHeader("[Arcade]"), "the written form");
        assertTrue(JoinSigns.isHeader("  [ARCADE] "), "any case, with stray spaces");
        assertTrue(JoinSigns.isHeader("[arcade]"), "lower case");
        assertFalse(JoinSigns.isHeader("Arcade"), "the brackets are part of it");
        assertFalse(JoinSigns.isHeader("[Arcade] 2"), "nothing else on the line");
        assertFalse(JoinSigns.isHeader(""), "an empty line");
        assertFalse(JoinSigns.isHeader(null), "no line");
    }

    @Test
    void aNonAdminsArcadeLineIsWipedEvenWhileTheGamesAreOff() {
        assertEquals(JoinSigns.Write.CLEAR, JoinSigns.onWrite(true, false, false),
                "games off: a look-alike sign must not be left waiting for the day they open");
        assertEquals(JoinSigns.Write.CLEAR, JoinSigns.onWrite(true, false, true), "games on: wiped as before");
        assertEquals(JoinSigns.Write.TAG, JoinSigns.onWrite(true, true, true), "an admin's sign is tagged");
        assertEquals(JoinSigns.Write.IGNORE, JoinSigns.onWrite(true, true, false),
                "an admin's sign waits while the games are off (there is no game to name)");
        assertEquals(JoinSigns.Write.IGNORE, JoinSigns.onWrite(false, false, true), "any other sign is left alone");
    }

    @Test
    void theSecondLineIsTheIdTrimmedAndLowerCased() {
        assertEquals("ore_slots", JoinSigns.idOf(" Ore_Slots "), "ids are matched in lower case");
        assertNull(JoinSigns.idOf("   "), "a blank line names nothing");
        assertNull(JoinSigns.idOf(null), "no line names nothing");
    }
}
