package com.dierks.homecraft.muffler;

import org.bukkit.SoundCategory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dispenser click and the other sounds that travel as level events. The group buttons have to
 * catch them too, or "Dispensers: Silent" would silence nothing at all.
 */
class LevelEventsTest {

    private static List<String> groups(int event) {
        return SoundGroups.of(LevelEvents.of(event).key()).stream().map(SoundGroups.SoundGroup::id).toList();
    }

    @Test
    void theDispenserClicksAreDispensers() {
        assertEquals(List.of("dispensers"), groups(1000));
        assertEquals(List.of("dispensers"), groups(1001));
        assertEquals(List.of("dispensers"), groups(1002));
    }

    @Test
    void theOtherEventsLandInTheGroupsTheirButtonsPromise() {
        assertEquals(List.of("workstations"), groups(1030), "anvil");
        assertEquals(List.of("workstations"), groups(1035), "brewing stand");
        assertEquals(List.of("workstations"), groups(1042), "grindstone");
        assertEquals(List.of("workstations"), groups(1049), "crafter");
        assertEquals(List.of("zombies"), groups(1019), "a zombie banging on a door");
        assertEquals(List.of("zombies"), groups(1027), "a zombie villager cured");
        assertEquals(List.of("nether"), groups(1016), "a ghast firing");
        assertEquals(List.of("wildlife"), groups(1025), "a bat taking off");
    }

    @Test
    void everyEntryIsAWellFormedSoundWithARealCategory() {
        for (LevelEvents.Entry e : LevelEvents.all().values()) {
            assertTrue(SoundNames.isKey(e.key()), e.id() + " → " + e.key());
            assertTrue(e.key().startsWith("minecraft:"), e.key());
            assertDoesNotThrow(() -> SoundCategory.valueOf(e.category()), e.id() + " category " + e.category());
            assertTrue(e.volume() > 0 && e.pitch() > 0, e.id() + " plays at a volume and pitch");
        }
    }

    @Test
    void eventsThatAlsoDrawParticlesAreLeftAlone() {
        assertNull(LevelEvents.of(2001), "a block breaking: sound AND particles");
        assertNull(LevelEvents.of(1500), "a composter filling: sound AND particles");
        assertNull(LevelEvents.of(1010), "a jukebox starting is music, which the player's game plays");
        assertNull(LevelEvents.of(1023), "the wither spawning is heard everywhere");
    }
}
