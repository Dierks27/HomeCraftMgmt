package com.dierks.homecraft.muffler;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sound keys read aloud, found by search, recovered from the game's text, and marked when replayed. */
class SoundNamesTest {

    @Test
    void keysReadLikeWords() {
        assertEquals("Chicken — ambient", SoundNames.friendly("minecraft:entity.chicken.ambient"));
        assertEquals("Zombie Villager — converted", SoundNames.friendly("minecraft:entity.zombie_villager.converted"));
        assertEquals("Note Block — basedrum", SoundNames.friendly("minecraft:block.note_block.basedrum"));
        assertEquals("Parrot — imitate creeper", SoundNames.friendly("minecraft:entity.parrot.imitate.creeper"));
        assertEquals("Stonecutter — take result", SoundNames.friendly("minecraft:ui.stonecutter.take_result"));
        assertEquals("Intentionally Empty", SoundNames.friendly("minecraft:intentionally_empty"));
        assertEquals("mypack: Door — creak", SoundNames.friendly("mypack:door.creak"),
                "another namespace keeps its name so it can't pass for vanilla");
        assertEquals("Unknown sound", SoundNames.friendly(null));
    }

    @Test
    void searchNeedsEveryWordSomewhereInTheKey() {
        String key = "minecraft:entity.zombie.attack_wooden_door";
        assertTrue(SoundNames.matches(key, "zombie door"));
        assertTrue(SoundNames.matches(key, "  ZOMBIE   Door "));
        assertFalse(SoundNames.matches(key, "zombie iron"));
        assertTrue(SoundNames.matches("minecraft:block.note_block.harp", "note block"));
        assertTrue(SoundNames.matches(key, ""), "an empty search lists everything");
    }

    @Test
    void namespacesAreReadCarefully() {
        assertEquals("entity.cow.ambient", SoundNames.vanillaPath("minecraft:entity.cow.ambient"));
        assertEquals("entity.cow.ambient", SoundNames.vanillaPath("entity.cow.ambient"));
        assertNull(SoundNames.vanillaPath("mypack:entity.cow.ambient"));
        assertEquals("minecraft:entity.cow.ambient", SoundNames.normalise(" Entity.Cow.Ambient "));
        assertEquals("mypack:x", SoundNames.normalise("MyPack:X"));
        assertNull(SoundNames.normalise("  "));
        assertTrue(SoundNames.isKey("minecraft:block.piston.extend"));
        assertFalse(SoundNames.isKey("block.piston.extend"));
        assertFalse(SoundNames.isKey("minecraft:Block.Piston"));
    }

    @Test
    void theKeyIsRecoveredFromEitherShapeOfTheGamesSoundText() {
        assertEquals("minecraft:entity.cow.ambient", SoundNames.fromHolderText(
                "Reference{ResourceKey[minecraft:sound_event / minecraft:entity.cow.ambient]="
                        + "SoundEvent[location=minecraft:entity.cow.ambient, fixedRange=Optional.empty]}"));
        assertEquals("mypack:door.creak", SoundNames.fromHolderText(
                "Direct{SoundEvent[location=mypack:door.creak, fixedRange=Optional[16.0]]}"));
        assertEquals("minecraft:entity.cow.ambient", SoundNames.fromHolderText(
                "Reference{ResourceKey[minecraft:sound_event / minecraft:entity.cow.ambient]=net.minecraft.X@1f}"),
                "a value that prints no location still has its resource key");
        assertNull(SoundNames.fromHolderText("Holder{nothing here}"));
        assertNull(SoundNames.fromHolderText(null));
    }

    @Test
    void replaysCarryTheMarkAndStillVary() {
        Set<Long> seeds = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            long s = Replay.seed();
            assertTrue(Replay.isReplay(s));
            seeds.add(s);
        }
        assertTrue(seeds.size() > 40, "the high bits stay random so a chicken doesn't cluck the same way every time");
        assertFalse(Replay.isReplay(0L));
        assertFalse(Replay.isReplay(Replay.MARK + 1));
        assertTrue(Replay.isReplay((123L << 24) | Replay.MARK));
    }
}
