package com.dierks.homecraft.courier;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Telling somebody's build from terrain that grew that way.
 *
 * <p>The asymmetry is the point. A <b>missed</b> build costs one delivery placed somewhere
 * rude — visible, annoying, reported. A <b>false positive</b> costs every delivery in a whole
 * biome, silently: the waypoint just rerolls away until the attempt budget runs out and the
 * player is told there is nowhere to deliver, with nothing to say why.
 *
 * <p>Registry-free: this exercises the name half of the test, which is where the mistakes live.
 */
class BuiltTest {

    /**
     * Blocks the world makes on its own must never read as handiwork.
     *
     * <p>Every one of these ends with a suffix the test otherwise treats as built, which is
     * exactly why the natural list has to be consulted first. Terracotta is the expensive one:
     * read it as a building and the entire badlands becomes undeliverable.
     */
    @Test
    void terrainThatLooksBuiltIsStillTerrain() {
        String[] natural = {
                "TERRACOTTA", "RED_TERRACOTTA", "ORANGE_TERRACOTTA", "WHITE_TERRACOTTA",
                "LIGHT_GRAY_TERRACOTTA", "BROWN_TERRACOTTA", "YELLOW_TERRACOTTA",
                "MOSS_CARPET", "PALE_MOSS_CARPET",
                "COBBLESTONE", "MOSSY_COBBLESTONE",
                "ICE", "PACKED_ICE", "BLUE_ICE",
                "SANDSTONE", "RED_SANDSTONE", "SMOOTH_SANDSTONE",
        };
        for (String name : natural) {
            assertFalse(Built.isBuiltName(name), () -> name
                    + " reads as somebody's build, so every delivery into the biome it belongs "
                    + "to will reroll away and eventually fail with no reason given");
        }
    }

    /** The things that actually mean a player, or a generated structure, was here. */
    @Test
    void handiworkIsRecognised() {
        String[] built = {
                "OAK_PLANKS", "SPRUCE_PLANKS", "CHERRY_PLANKS", "BAMBOO_PLANKS",
                "CRAFTING_TABLE", "FURNACE", "BOOKSHELF", "LADDER", "TORCH", "WALL_TORCH",
                "LANTERN", "GLASS", "GLASS_PANE", "WHITE_STAINED_GLASS",
                "RED_STAINED_GLASS_PANE", "IRON_BARS",
                "OAK_STAIRS", "STONE_BRICK_STAIRS", "OAK_SLAB", "OAK_DOOR", "OAK_FENCE",
                "COBBLESTONE_WALL", "STONE_BUTTON", "OAK_PRESSURE_PLATE",
                "WHITE_WOOL", "RED_CARPET", "LIME_CONCRETE", "BLUE_CONCRETE_POWDER",
                "WHITE_GLAZED_TERRACOTTA", "BRICKS", "STONE_BRICKS", "HAY_BLOCK",
                "HOPPER", "PISTON", "OBSERVER", "REPEATER", "SMOOTH_STONE",
        };
        for (String name : built) {
            assertTrue(Built.isBuiltName(name), () -> name
                    + " does not read as a build, so a delivery house can be dropped on top of "
                    + "one — including an unclaimed base, which no other check can see");
        }
    }

    /**
     * A natural block must win even though a suffix would otherwise claim it.
     *
     * <p>Pinned on its own because the failure mode is an ordering change in the method, not a
     * change to either list — reversing the two checks passes every other assertion here.
     */
    @Test
    void theNaturalListBeatsTheSuffixList() {
        // Ends with _TERRACOTTA … which is not a suffix, but _GLAZED_TERRACOTTA is, and these
        // sit one word apart. The pairing is the whole risk.
        assertFalse(Built.isBuiltName("RED_TERRACOTTA"), "badlands terracotta is terrain");
        assertTrue(Built.isBuiltName("RED_GLAZED_TERRACOTTA"), "glazed terracotta is made");

        // MOSS_CARPET ends with _CARPET, which is a suffix.
        assertFalse(Built.isBuiltName("MOSS_CARPET"), "moss carpet grows in lush caves");
        assertTrue(Built.isBuiltName("RED_CARPET"), "a dyed carpet was crafted");
    }

    /**
     * Ordinary ground, in every biome, must pass.
     *
     * <p>The broadest guard here and the cheapest to get wrong: one of these reading as a
     * building would quietly make some biome undeliverable. A delivery that lands in a pale
     * garden, a badlands, a dripstone cave or a basalt delta has to be able to find ground.
     */
    @Test
    void ordinaryTerrainIsNeverABuilding() {
        String[] terrain = {
                "STONE", "DIRT", "GRASS_BLOCK", "GRAVEL", "CLAY", "PODZOL", "MYCELIUM",
                "DEEPSLATE", "ANDESITE", "DIORITE", "GRANITE", "TUFF", "CALCITE",
                "DRIPSTONE_BLOCK", "AMETHYST_BLOCK", "BASALT", "BLACKSTONE", "NETHERRACK",
                "SOUL_SAND", "END_STONE", "SNOW_BLOCK", "OAK_LOG", "OAK_LEAVES",
        };
        for (String name : terrain) {
            assertFalse(Built.isBuiltName(name), () -> name
                    + " reads as a building, which would make everywhere it occurs "
                    + "undeliverable — the waypoint would reroll away until the budget ran out");
        }
    }

    /** Nothing sensible to say about nothing. */
    @Test
    void junkIsNotABuilding() {
        assertFalse(Built.isBuiltName(null));
        assertFalse(Built.isBuiltName(""));
        assertFalse(Built.isBuiltName("   "));
    }
}
