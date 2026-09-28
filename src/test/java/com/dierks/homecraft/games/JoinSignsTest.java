package com.dierks.homecraft.games;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How a join sign is recognised and read (spec §8.5). */
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
    void theSecondLineIsTheIdTrimmedAndLowerCased() {
        assertEquals("ore_slots", JoinSigns.idOf(" Ore_Slots "), "ids are matched in lower case");
        assertNull(JoinSigns.idOf("   "), "a blank line names nothing");
        assertNull(JoinSigns.idOf(null), "no line names nothing");
    }
}
