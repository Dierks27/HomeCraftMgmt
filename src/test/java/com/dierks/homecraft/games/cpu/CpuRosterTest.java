package com.dierks.homecraft.games.cpu;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The NPC roster and its marker: six friendly invented names, two at each level, each its own built-in skin and a
 * personality; the gear and the stars always follow the name, so nobody mistakes one for a real person, and renaming
 * one can never take them away.
 */
class CpuRosterTest {

    @Test
    void sixNpcsTwoAtEachLevelEachItsOwnLookAndId() {
        assertEquals(6, CpuRoster.ALL.size());
        for (CpuLevel level : CpuLevel.values()) {
            assertEquals(2, CpuRoster.ALL.stream().filter(c -> c.level() == level).count(), "two at " + level);
        }
        Set<String> keys = new HashSet<>();
        Set<String> names = new HashSet<>();
        Set<String> skins = new HashSet<>();
        Set<UUID> ids = new HashSet<>();
        Set<CpuPersonality> kinds = new HashSet<>();
        for (CpuPlayer c : CpuRoster.ALL) {
            assertTrue(keys.add(c.key()), "one key each: " + c.key());
            assertTrue(names.add(c.name()), "one name each: " + c.name());
            assertTrue(skins.add(c.skin().name()), "one built-in skin each: " + c.skin());
            assertTrue(ids.add(c.id()), "one id each");
            kinds.add(c.personality());
            assertEquals(3, c.id().version(), "a name-based UUID: no Mojang account (version 4) can have it");
            assertSame(c, CpuRoster.byId(c.id()));
            assertSame(c, CpuRoster.byKey(c.key().toUpperCase()));
            assertTrue(CpuRoster.isCpu(c.id()));
            assertTrue(c.favourite() >= 0 && c.favourite() < 6, "a racer's lane");
            assertEquals(c.name(), CpuRoster.cleanName(c.name()), "its own name is fit to show as it is");
        }
        assertEquals(Set.of(CpuPersonality.values()), kinds, "every personality is someone's");
        assertFalse(CpuRoster.isCpu(UUID.randomUUID()), "a real player is never an NPC");
        assertNull(CpuRoster.byId(null));
        assertNull(CpuRoster.byKey("nobody"));
    }

    @Test
    void theGearAndTheStarsAlwaysFollowTheNameAndThePlainWordIsNpc() {
        assertEquals("&fPip &7⚙ &e★", CpuRoster.label("Pip", CpuLevel.EASY));
        assertEquals("&fOzzie &7⚙ &e★★★", CpuRoster.label("Ozzie", CpuLevel.HARD));
        assertEquals("Pip (NPC)", CpuRoster.plain("Pip"));
        assertEquals("⚙", CpuRoster.MARKER);
        assertTrue(CpuRoster.MARKER.codePointAt(0) <= 0xFFFF, "the gear is in the BMP: Bedrock draws it");
        for (CpuLevel l : CpuLevel.values()) {
            assertEquals(l.stars, l.starText().length());
            assertEquals(l, CpuLevel.ofStars(l.stars));
        }
    }

    @Test
    void aRenameKeepsTheMarkerBecauseTheNameNeverHoldsIt() {
        assertEquals("Grandpa Joe", CpuRoster.cleanName("  &cGrandpa   Joe  "), "codes and extra spaces go");
        assertEquals("Sneaky", CpuRoster.cleanName("Sneaky ⚙"), "a typed gear goes: the label adds the real one");
        assertEquals("Star", CpuRoster.cleanName("Star ★★★"), "typed stars go too");
        assertNull(CpuRoster.cleanName("⚙ ★ &a"), "nothing left");
        assertNull(CpuRoster.cleanName(null));
        assertEquals(CpuRoster.MAX_NAME, CpuRoster.cleanName("A very very long name indeed").length());
        String label = CpuRoster.label(CpuRoster.cleanName("§lFake Player"), CpuLevel.MEDIUM);
        assertTrue(label.contains(CpuRoster.MARKER) && label.endsWith("★★"), "renamed, it still says NPC: " + label);
        assertNotEquals(-1, label.indexOf("Fake Player"));
    }
}
