package com.dierks.homecraft.games.gen.api;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The palette allowlist (GEN-SPEC §4.0), pinned: exactly these blocks, each a real block, none that
 * falls, flows, melts, ticks, carries power or holds things; every named block the planners share
 * is on it; and block-data text reads to its id whatever its states or case.
 */
class PaletteTest {

    @Test
    void theAllowlistIsPinned() {
        assertEquals(new TreeSet<>(Set.of("minecraft:lime_concrete", "minecraft:green_concrete",
                "minecraft:light_blue_concrete", "minecraft:white_concrete", "minecraft:yellow_concrete",
                "minecraft:orange_concrete", "minecraft:red_concrete", "minecraft:blue_concrete",
                "minecraft:purple_concrete", "minecraft:magenta_concrete", "minecraft:black_concrete",
                "minecraft:gold_block", "minecraft:magenta_glazed_terracotta", "minecraft:oak_sign",
                "minecraft:oak_wall_sign", "minecraft:quartz_pillar", "minecraft:stripped_spruce_wood",
                "minecraft:slime_block", "minecraft:packed_ice", "minecraft:blue_ice", "minecraft:soul_soil",
                "minecraft:smooth_stone_slab", "minecraft:red_wool")), new TreeSet<>(Palette.ALLOWED),
                "adding a block to generated courses is a decision: change this test with it");
    }

    @Test
    void everyAllowedIdIsARealBlock() {
        for (String id : Palette.ALLOWED) {
            Material m = Material.matchMaterial(id);
            assertNotNull(m, id + " is a Material");
            assertEquals(id, "minecraft:" + m.name().toLowerCase(java.util.Locale.ROOT),
                    id + " is spelled as the game spells it");
        }
    }

    @Test
    void nothingThatFallsFlowsMeltsOrTicksIsAllowed() {
        for (String banned : List.of("sand", "red_sand", "gravel", "white_concrete_powder", "anvil", "dragon_egg",
                "scaffolding", "pointed_dripstone", "water", "lava", "ice", "frosted_ice", "snow", "snow_block",
                "soul_sand", "oak_leaves", "wheat", "redstone_block", "redstone_wire", "piston", "sticky_piston",
                "chest", "hopper", "barrier", "light", "tnt", "fire", "magma_block", "honey_block", "air",
                "player_head", "cactus", "sugar_cane", "grass_block", "dirt", "farmland")) {
            assertFalse(Palette.allowed(banned), banned + " must never be in a generated course");
        }
    }

    @Test
    void everyNamedBlockIsAllowed() throws IllegalAccessException {
        int named = 0;
        for (Field f : Palette.class.getFields()) {
            if (!Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            if (f.getType() == String.class) {
                assertTrue(Palette.allowed((String) f.get(null)), f.getName() + " is on the allowlist");
                named++;
            }
        }
        for (String ring : Palette.RAINBOW) {
            assertTrue(Palette.allowed(ring), ring + " (a ring colour) is on the allowlist");
        }
        assertEquals(8, Palette.RAINBOW.size(), "eight rainbow colours, then round again");
        assertTrue(named >= 20, "the shared names were all checked: " + named);
        assertTrue(Palette.allowed(Palette.arrow("north")), "an arrow with its facing");
        assertTrue(Palette.allowed(Palette.sign(-1)), "a standing sign with its rotation");
        assertEquals("minecraft:oak_sign[rotation=15]", Palette.sign(-1), "a rotation wraps round");
        assertTrue(Palette.allowed(Palette.wallSign("east")), "a wall sign with its facing");
    }

    @Test
    void blockDataReadsToItsId() {
        assertEquals("minecraft:smooth_stone_slab", Palette.id("minecraft:smooth_stone_slab[type=bottom]"),
                "states are dropped");
        assertEquals("minecraft:packed_ice", Palette.id(" Packed_Ice "), "a bare id is namespaced and lower-cased");
        assertEquals("", Palette.id(null), "nothing is no id");
        assertEquals(List.of("minecraft:sand", "null"), Palette.problems(java.util.Arrays.asList(Palette.START,
                "minecraft:sand", null)), "the lint lists what isn't allowed, in order");
        assertEquals(List.of(), Palette.problems(List.of(Palette.RAMP, Palette.CUP)), "and nothing when all is fine");
    }
}
