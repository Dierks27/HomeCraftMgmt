package com.dierks.homecraft.muffler;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The friendly groups on a Sound Muffler: which sounds each one catches — and, as much, which it
 * must not (a zombified piglin is not a zombie; a skeleton horse is not a skeleton).
 */
class SoundGroupsTest {

    private static List<String> ids(String key) {
        return SoundGroups.of(key).stream().map(SoundGroups.SoundGroup::id).toList();
    }

    @Test
    void farmAnimalsLandInTheirOwnGroup() {
        assertEquals(List.of("chickens"), ids("minecraft:entity.chicken.ambient"));
        assertEquals(List.of("chickens"), ids("minecraft:entity.chicken.egg"));
        assertEquals(List.of("cows"), ids("minecraft:entity.mooshroom.milk"));
        assertEquals(List.of("goats"), ids("minecraft:entity.goat.screaming.ambient"));
        assertEquals(List.of("bees"), ids("minecraft:block.beehive.work"));
    }

    @Test
    void aStepIsBothItsMobAndAFootstep() {
        assertEquals(List.of("chickens", "footsteps"), ids("minecraft:entity.chicken.step"));
        assertEquals(List.of("footsteps"), ids("minecraft:block.stone.step"));
        assertEquals(List.of("footsteps"), ids("minecraft:block.wool.step"));
    }

    @Test
    void lookAlikeNamesDoNotLeakIntoTheWrongGroup() {
        assertEquals(List.of("nether"), ids("minecraft:entity.zombified_piglin.angry"));
        assertEquals(List.of("horses"), ids("minecraft:entity.skeleton_horse.ambient"));
        assertEquals(List.of("zombies"), ids("minecraft:entity.zombie_villager.converted"));
        assertEquals(List.of("skeletons"), ids("minecraft:entity.wither_skeleton.ambient"));
        assertEquals(List.of("monsters"), ids("minecraft:entity.wither.shoot"));
        assertEquals(List.of("pets"), ids("minecraft:entity.wolf_big.growl"));
    }

    @Test
    void machinesAndBlocks() {
        assertEquals(List.of("pistons"), ids("minecraft:block.piston.extend"));
        assertEquals(List.of("dispensers"), ids("minecraft:block.dispenser.dispense"));
        assertEquals(List.of("doors"), ids("minecraft:block.wooden_door.open"));
        assertEquals(List.of("doors"), ids("minecraft:block.iron_trapdoor.close"));
        assertEquals(List.of("doors"), ids("minecraft:block.bamboo_wood_fence_gate.open"));
        assertEquals(List.of("switches"), ids("minecraft:block.stone_button.click_on"));
        assertEquals(List.of("switches"), ids("minecraft:block.wooden_pressure_plate.click_off"));
        assertEquals(List.of("storage"), ids("minecraft:block.chest.open"));
        assertEquals(List.of("storage"), ids("minecraft:block.ender_chest.close"));
        assertEquals(List.of("storage"), ids("minecraft:block.barrel.open"));
        assertEquals(List.of("workstations"), ids("minecraft:block.anvil.use"));
        assertEquals(List.of("workstations"), ids("minecraft:ui.stonecutter.take_result"));
        assertEquals(List.of("note_blocks"), ids("minecraft:block.note_block.harp"));
    }

    @Test
    void playersAreOnlyTheirOwnSounds() {
        assertEquals(List.of("players"), ids("minecraft:entity.player.burp"));
        assertEquals(List.of("players"), ids("minecraft:entity.generic.eat"));
        assertEquals(List.of(), ids("minecraft:entity.generic.explode"));
    }

    @Test
    void onlyVanillaSoundsBelongToGroups() {
        assertEquals(List.of(), ids("mypack:entity.chicken.ambient"));
        assertEquals(List.of("chickens"), ids("entity.chicken.ambient"), "a key with no namespace is vanilla");
        assertEquals(List.of(), ids(null));
    }

    @Test
    void theGroupsFitTheMenuWithoutOverlapping() {
        Set<String> seenIds = new HashSet<>();
        Set<Integer> seenSlots = new HashSet<>();
        for (SoundGroups.SoundGroup g : SoundGroups.all()) {
            assertTrue(seenIds.add(g.id()), "duplicate id " + g.id());
            assertTrue(seenSlots.add(g.slot()), "two groups on slot " + g.slot());
            assertTrue(g.slot() >= 9 && g.slot() <= 44, g.id() + " is on slot " + g.slot()
                    + " — rows 2-5 (9-44) are the groups; row 1 and row 6 are the controls");
            assertFalse(g.patterns().isEmpty(), g.id() + " catches nothing");
            assertFalse(g.about().isEmpty(), g.id() + " explains nothing");
            assertTrue(g.id().matches("[a-z_]+"), "ids are stored in the database: " + g.id());
            assertNotNull(SoundGroups.byId(g.id()));
        }
    }

    @Test
    void everyIconIsARealMaterial() {
        for (SoundGroups.SoundGroup g : SoundGroups.all()) {
            assertDoesNotThrow(() -> Material.valueOf(g.icon()), g.id() + " icon " + g.icon());
        }
    }

    @Test
    void theGlobStarCrossesDotsAndEverythingElseIsLiteral() {
        assertTrue(SoundGroups.glob("block.*.step").matcher("block.stone.step").matches());
        assertTrue(SoundGroups.glob("block.*door*").matcher("block.wooden_door.open").matches());
        assertFalse(SoundGroups.glob("block.*.step").matcher("blockXstoneXstep").matches(),
                "a dot is a dot, not any character");
        assertFalse(SoundGroups.glob("entity.cow.*").matcher("entity.cowbell.ring").matches());
    }
}
