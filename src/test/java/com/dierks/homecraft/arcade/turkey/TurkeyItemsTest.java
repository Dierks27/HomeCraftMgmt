package com.dierks.homecraft.arcade.turkey;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Turkey Feather as text: its id and name from the event's year (the year in the name, for Bedrock). The item
 * itself needs a server, so it is checked live.
 */
class TurkeyItemsTest {

    @Test
    void theIdAndNameComeFromTheYear() {
        assertEquals("turkey_feather_2026", TurkeyItems.featherId(2026));
        assertEquals("turkey_feather_2027", TurkeyItems.featherId(2027), "next year's is its own");
        assertTrue(TurkeyItems.featherId(2026).startsWith(TurkeyItems.FEATHER_PREFIX));
        assertEquals("&6Turkey Feather 2026", TurkeyItems.featherName(2026));
    }

    @Test
    void theLoreIsPlain() {
        assertEquals(List.of("&7Thanksgiving 2026", "&7You caught the turkey!", "&8Arcade prize"),
                TurkeyItems.featherLore(2026));
        for (String line : TurkeyItems.featherLore(2026)) {
            assertTrue(line.chars().allMatch(ch -> ch < 128), line + " is ASCII");
        }
    }
}
