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
                "minecraft:smooth_stone_slab", "minecraft:red_wool",
                // EVENTS-DROPPER-SPEC C1: the Dropper's shafts and lights, Falling Floors' floors
                "minecraft:glass", "minecraft:red_stained_glass", "minecraft:orange_stained_glass",
                "minecraft:yellow_stained_glass", "minecraft:blue_stained_glass", "minecraft:purple_stained_glass",
                "minecraft:pink_stained_glass", "minecraft:light_blue_stained_glass", "minecraft:sea_lantern",
                // Course Variety §1.1: the sand that never falls, moss, and trees (their states: stateProblems)
                "minecraft:smooth_sandstone", "minecraft:smooth_sandstone_slab", "minecraft:moss_block",
                "minecraft:oak_log", "minecraft:birch_log", "minecraft:cherry_log", "minecraft:oak_leaves",
                "minecraft:birch_leaves", "minecraft:cherry_leaves")),
                new TreeSet<>(Palette.ALLOWED),
                "adding a block to generated courses is a decision: change this test with it");
    }

    @Test
    void waterIsOnlyInPoolWaterNeverOnTheAllowlist() {
        // §B.1.9, Course Variety §1.2: the one fluid, only in sealed pools (a Dropper's, golf's ponds)
        assertEquals(Set.of("minecraft:water[level=0]"), Palette.POOL_WATER, "still water sources only");
        for (String water : List.of("minecraft:water[level=0]", "minecraft:water", "water", "minecraft:water[level=1]")) {
            assertFalse(Palette.allowed(water), water + " is never on the shared allowlist");
            assertTrue(Palette.problems(List.of(water)).contains(water), water + " fails every other generator's lint");
        }
        assertTrue(Palette.poolWater("minecraft:water[level=0]"), "a still source is pool water");
        assertTrue(Palette.poolWater(" MINECRAFT:WATER[LEVEL=0] "), "in any case, trimmed");
        assertFalse(Palette.poolWater("minecraft:water[level=1]"), "flowing water never is");
        assertFalse(Palette.poolWater("minecraft:water"), "nor water without its level");
        assertFalse(Palette.poolWater("minecraft:lava[level=0]"), "nor any other fluid");
        assertFalse(Palette.poolWater(null), "nor nothing");
        for (String b : Palette.GLASS_AND_LIGHTS) {
            assertTrue(Palette.allowed(b), b + " is on the allowlist");
            assertFalse(Palette.poolWater(b), b + " is no fluid");
        }
        assertTrue(Palette.ALLOWED.containsAll(Palette.GLASS_AND_LIGHTS), "C1's additions are all allowed");
    }

    @Test
    void eachGlassColourIsAStainedGlassOnTheAllowlist() {
        assertEquals(List.of("red", "orange", "yellow", "blue", "purple", "pink", "light_blue"), Palette.GLASS_COLOURS,
                "the Dropper's rainbow, then Falling Floors' pink and light blue");
        for (String c : Palette.GLASS_COLOURS) {
            assertTrue(Palette.allowed(Palette.stainedGlass(c)), c + " glass is allowed");
            assertNotNull(Material.matchMaterial(Palette.stainedGlass(c)), c + " glass is a real block");
        }
        assertEquals("minecraft:light_blue_stained_glass", Palette.stainedGlass(" Light_Blue "), "any case");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> Palette.stainedGlass("black"), "a colour the palette doesn't have is refused");
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
                "soul_sand", "wheat", "redstone_block", "redstone_wire", "piston", "sticky_piston",
                "chest", "hopper", "barrier", "light", "tnt", "fire", "magma_block", "honey_block", "air",
                "player_head", "cactus", "sugar_cane", "grass_block", "dirt", "farmland", "moss_carpet",
                "sandstone_slab", "spruce_leaves", "azalea_leaves", "oak_sapling", "bubble_column")) {
            assertFalse(Palette.allowed(banned), banned + " must never be in a generated course");
        }
    }

    @Test
    void leavesAreAllowedOnlyPersistentDryAndAtADistanceLogsOnlyUprightSlabsOnlyBottom() {
        // §1.1.1: the new blocks are on the list by id; their STATES are what keeps them from changing
        assertEquals(List.of(), Palette.stateProblems(List.of(Palette.leaves("oak", 1), Palette.leaves("birch", 4),
                Palette.leaves("cherry", 7), Palette.log("oak"), Palette.log("birch"), Palette.log("cherry"),
                Palette.SAND, Palette.SAND_SLAB, Palette.MOSS, Palette.RAMP, Palette.TRACK, Palette.arrow("north"),
                Palette.sign(3), Palette.PILLAR + "[axis=y]")), "every block the planners write is fine");
        List<String> bad = List.of("minecraft:oak_leaves", "minecraft:oak_leaves[distance=2]",
                "minecraft:oak_leaves[distance=2,persistent=false,waterlogged=false]",
                "minecraft:birch_leaves[distance=2,persistent=true]",
                "minecraft:birch_leaves[distance=2,persistent=true,waterlogged=true]",
                "minecraft:cherry_leaves[distance=0,persistent=true,waterlogged=false]",
                "minecraft:cherry_leaves[distance=8,persistent=true,waterlogged=false]",
                "minecraft:oak_leaves[distance=x,persistent=true,waterlogged=false]",
                "minecraft:oak_leaves[distance=2,persistent=true,waterlogged=false,extra=1]",
                "minecraft:oak_log", "minecraft:oak_log[axis=x]", "minecraft:birch_log[axis=z]",
                "minecraft:smooth_sandstone_slab", "minecraft:smooth_sandstone_slab[type=top]",
                "minecraft:smooth_stone_slab[type=double]", "minecraft:smooth_stone_slab[type=bottom,waterlogged=true]",
                "minecraft:oak_sign[rotation=4,waterlogged=true]", "minecraft:oak_leaves[distance=2,distance=3]",
                "minecraft:oak_log[axis=y", "minecraft:oak_log[axis]");
        List<String> problems = Palette.stateProblems(bad);
        assertEquals(bad.size(), problems.size(), "each bad entry is said once: " + problems);
        for (int i = 0; i < bad.size(); i++) {
            assertTrue(problems.get(i).startsWith("'" + bad.get(i) + "'"), "in order, naming the entry: "
                    + problems.get(i));
        }
        assertTrue(problems.get(0).contains("persistent=true"), "a bare leaf would decay: " + problems.get(0));
        assertTrue(problems.get(9).contains("axis=y"), "a log on its side: " + problems.get(9));
        assertTrue(problems.get(12).contains("type=bottom"), "a slab of no type: " + problems.get(12));
        assertTrue(problems.get(15).contains("water"), "a waterlogged slab holds water: " + problems.get(15));
        assertEquals(List.of(), Palette.stateProblems(null), "no palette, nothing to say");
        assertEquals(List.of(), Palette.stateProblems(java.util.Arrays.asList((String) null)),
                "a missing entry is the allowlist's to report");
    }

    @Test
    void leavesAndLogsAreWrittenAsTheGameSpellsThem() {
        // §1.1.2: states in alphabetical order, so the builder's parse gives back the same text
        assertEquals("minecraft:oak_leaves[distance=2,persistent=true,waterlogged=false]", Palette.leaves("oak", 2),
                "a leaf two from its log");
        assertEquals("minecraft:cherry_leaves[distance=7,persistent=true,waterlogged=false]",
                Palette.leaves(" Cherry ", 7), "any case, the farthest");
        assertEquals("minecraft:birch_log[axis=y]", Palette.log("birch"), "an upright trunk");
        assertEquals(List.of("oak", "birch", "cherry"), Palette.WOODS, "the three woods");
        for (String wood : Palette.WOODS) {
            assertTrue(Palette.allowed(Palette.log(wood)), wood + " logs are allowed");
            assertNotNull(Material.matchMaterial(Palette.id(Palette.log(wood))), wood + " log is a real block");
            for (int d = 1; d <= Palette.MAX_LEAF_DISTANCE; d++) {
                assertTrue(Palette.allowed(Palette.leaves(wood, d)), wood + " leaves are allowed");
                assertEquals(List.of(), Palette.stateProblems(List.of(Palette.leaves(wood, d))),
                        wood + " leaves at " + d + " keep the state rules");
                assertEquals(Palette.id(Palette.leaves(wood, d)), Palette.id(Palette.leaves(wood, d).toUpperCase()),
                        "and read to one id");
            }
        }
        assertEquals(7, Palette.MAX_LEAF_DISTANCE, "vanilla's cap");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> Palette.log("spruce"),
                "a wood the palette doesn't have");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> Palette.leaves("oak", 0),
                "a leaf touching nothing");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> Palette.leaves("oak", 8),
                "or past the cap");
        assertEquals(java.util.Map.of("axis", "y"), Palette.states("minecraft:oak_log[ Axis = Y ]"),
                "states read lower-case and trimmed");
        assertEquals(java.util.Map.of(), Palette.states("minecraft:moss_block"), "none");
        assertEquals(null, Palette.states("minecraft:oak_log]"), "a stray bracket can't be read");
    }

    @Test
    void theSandIsSmoothSandstoneAndNeverFalls() {
        assertEquals("minecraft:smooth_sandstone", Palette.SAND, "a full block that never falls, flows or ticks");
        assertEquals("minecraft:smooth_sandstone_slab[type=bottom]", Palette.SAND_SLAB, "a sunken bunker's slab");
        assertEquals("minecraft:moss_block", Palette.MOSS, "moss never spreads on its own");
        assertFalse(Palette.allowed("minecraft:sand"), "real sand falls: never");
        assertTrue(Palette.allowed(Palette.SAND) && Palette.allowed(Palette.SAND_SLAB) && Palette.allowed(Palette.MOSS),
                "the new ground blocks are allowed");
        assertEquals("minecraft:yellow_concrete", Palette.LIP_CAP, "drop caps are yellow");
        assertEquals("minecraft:light_blue_stained_glass", Palette.BLUE_GLASS, "the cave roof and glass waterfall");
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
