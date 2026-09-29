package com.dierks.homecraft.games.gen.dropper;

import com.dierks.homecraft.games.gen.api.Palette;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Dropper's blocks (EVENTS-DROPPER-SPEC §B.1.3, §B.1.6 rule 8, §B.1.9): the colour language, what
 * each block does to a falling player, and the lint that admits water only as a still source.
 */
class DropBlocksTest {

    @Test
    void theLevelsAreTheRainbowAndNeverTheStartCheckpointOrFinishColours() {
        assertEquals(List.of("red", "orange", "yellow", "blue", "purple"), DropBlocks.COLOURS,
                "one colour a level, red to purple");
        for (int i = 0; i < DropRules.MAX_LEVELS; i++) {
            String plate = DropBlocks.plate(i);
            assertFalse(plate.equals(Palette.START) || plate.equals(Palette.CHECKPOINT) || plate.equals(Palette.FINISH),
                    "level " + (i + 1) + "'s obstacles (" + plate + ") never use lime, light blue or gold");
            assertEquals("minecraft:" + DropBlocks.COLOURS.get(i) + "_stained_glass", DropBlocks.glass(i),
                    "its walls are glass of the same colour");
        }
        assertEquals(Palette.START, DropBlocks.LEDGE, "the ledge is the start colour");
        assertEquals(Palette.CHECKPOINT, DropBlocks.RIM, "the floor round a pool is a checkpoint");
        assertEquals(Palette.FINISH, DropBlocks.RIM_LAST, "and round the last pool the finish");
    }

    @Test
    void eachBlockDoesWhatItShouldToAFallingPlayer() {
        assertEquals(DropWorld.WALL, DropBlocks.kind("minecraft:blue_stained_glass"), "stained glass is a wall");
        assertEquals(DropWorld.WALL, DropBlocks.kind("minecraft:glass"), "so is clear glass");
        assertEquals(DropWorld.LEDGE, DropBlocks.kind(DropBlocks.LEDGE), "lime is the ledge");
        assertEquals(DropWorld.WATER, DropBlocks.kind(DropBlocks.WATER), "water is the splash");
        assertEquals(DropWorld.SOLID, DropBlocks.kind(DropBlocks.LIGHT), "a guide light is solid: a bonk");
        assertEquals(DropWorld.SOLID, DropBlocks.kind(DropBlocks.RIM), "so is the floor round a pool");
        assertEquals(DropWorld.SOLID, DropBlocks.kind(DropBlocks.plate(2)), "and every plate");
    }

    @Test
    void aDropperPlacesOnlyPlainFullCubesAndStillWater() {
        for (String ok : List.of("minecraft:red_concrete", "minecraft:lime_concrete", "minecraft:gold_block",
                "minecraft:sea_lantern", "minecraft:glass", "minecraft:purple_stained_glass", DropBlocks.WATER)) {
            assertTrue(DropBlocks.allowed(ok), ok + " is a Dropper block");
        }
        for (String bad : List.of("minecraft:water[level=1]", "minecraft:water", "minecraft:lava",
                "minecraft:sand", "minecraft:smooth_stone_slab[type=bottom]", "minecraft:oak_sign",
                "minecraft:magenta_glazed_terracotta[facing=north]", "minecraft:slime_block",
                "minecraft:red_concrete[x=1]")) {
            assertFalse(DropBlocks.allowed(bad), bad + " is not: only still water, only full cubes");
        }
    }

    @Test
    void waterIsNeverOnTheSharedPalette() {
        assertFalse(Palette.allowed(DropBlocks.WATER),
                "water stays out of Palette.ALLOWED: every other generator refuses it");
        assertTrue(DropBlocks.POOL_WATER.contains(DropBlocks.WATER), "the Dropper admits it through its own set");
        for (String b : DropBlocks.PENDING_C1) {
            assertFalse(DropBlocks.isWater(b), "nothing pending for C1 is a fluid: " + b);
        }
    }
}
