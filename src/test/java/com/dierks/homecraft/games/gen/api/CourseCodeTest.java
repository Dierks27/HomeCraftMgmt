package com.dierks.homecraft.games.gen.api;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Course codes (GEN-SPEC-KEEP §8): every slot has its word (EASY, PARK, HARD, RINGS, GOLF, TINY,
 * BOAT), a code is the word and a number from 1, and a code as a player types it — any case — reads
 * back to its slot and number; anything else is not a code.
 */
class CourseCodeTest {

    @Test
    void everySlotHasItsOwnWord() {
        assertEquals(List.of("EASY", "PARK", "HARD", "RINGS", "GOLF", "TINY", "BOAT", "EDROP", "DROP"),
                List.copyOf(CourseCode.SLOT_CODES.values()), "the owner's words, in slot order");
        Set<String> words = new HashSet<>(CourseCode.SLOT_CODES.values());
        assertEquals(Slots.ALL.size(), words.size(), "one word per slot, none shared");
        for (Slots.Def d : Slots.ALL) {
            assertTrue(CourseCode.SLOT_CODES.containsKey(d.id()), d.id() + " has a code word");
        }
        for (Slots.Def d : Slots.CLASSICS) {
            assertNull(CourseCode.slotCode(d.id()), "a Classics slot makes no editions of its own, so no code word");
        }
        assertEquals("DROP-12", CourseCode.format("fresh_dropper", 12), "the Dropper's code (EVENTS-DROPPER-SPEC §B.1.8)");
        assertEquals("fresh_dropper_easy", CourseCode.parse("edrop-3").slot(), "Easy Dropper's, typed in any case");
        assertEquals("fresh_dropper", CourseCode.parse("DROP-3").slot(), "and DROP is not EDROP");
    }

    @Test
    void aCodeIsTheWordAndTheNumberAndReadsBack() {
        assertEquals("HARD-40", CourseCode.format("fresh_parkour_hard", 40), "the hard parkour's 40th");
        assertEquals("TINY-1", CourseCode.format("fresh_tiny_golf", 1), "counting starts at 1");
        assertNull(CourseCode.format("fresh_parkour_hard", 0), "there is no 0th");
        assertNull(CourseCode.format("river_run", 3), "a hand-built course has no code");
        CourseCode.Parsed p = CourseCode.parse(" hard-40 ");
        assertEquals("fresh_parkour_hard", p.slot(), "any case, trimmed, reads to its slot");
        assertEquals(40, p.n(), "and its number");
        assertEquals("HARD-40", p.code(), "and is written back in capitals");
        assertEquals("fresh_rings", CourseCode.parse("Rings-7").slot(), "RINGS is Sky Rings");
        assertEquals("fresh_parkour", CourseCode.parse("PARK-12").slot(), "PARK is the middle parkour");
        for (String junk : new String[]{"HARD", "HARD-", "HARD-0", "-40", "NOPE-3", "HARD 40", "HARD-4O", "7:40", "",
                null, "HARD-1234567890"}) {
            assertNull(CourseCode.parse(junk), "'" + junk + "' is not a code");
            assertFalse(CourseCode.is(junk), "'" + junk + "' is not a code");
        }
    }
}
