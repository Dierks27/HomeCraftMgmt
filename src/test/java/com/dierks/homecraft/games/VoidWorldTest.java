package com.dierks.homecraft.games;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.LegacyBoxes;
import com.dierks.homecraft.games.gen.api.Sight;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.engine.Regions;
import org.bukkit.Location;
import org.bukkit.generator.ChunkGenerator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The built-in void world (LAYOUT-VOID-ADDENDUM item 1): its spawn is fixed at (0, 100, 0) on a 5 x 5
 * smooth-stone platform with a light above head height, each block placed by the chunk it falls in
 * (so the platform is whole from the world's first load), far from every place the games build; and
 * the generator makes nothing else.
 */
class VoidWorldTest {

    @Test
    void thePlatformIsFiveByFiveUnderTheSpawnWithALightAboveIt() {
        List<VoidWorld.Block> p = VoidWorld.platform();
        assertEquals(26, p.size(), "25 floor blocks and one light");
        Set<String> floor = new HashSet<>();
        for (VoidWorld.Block b : p.subList(0, 25)) {
            assertEquals(VoidWorld.FLOOR, b.data(), "the floor is plain smooth stone");
            assertEquals(99, b.y(), "one under the spawn");
            assertTrue(Math.abs(b.x()) <= 2 && Math.abs(b.z()) <= 2, "round (0, 0): " + b);
            floor.add(b.x() + "," + b.z());
        }
        assertEquals(25, floor.size(), "every column of the 5 x 5 once");
        assertEquals(new VoidWorld.Block(0, 102, 0, "minecraft:light[level=15]"), p.get(25),
                "the light over the spawn, above a standing player's head (feet 100, head 101)");
        assertTrue(floor.contains(VoidWorld.SPAWN_X + "," + VoidWorld.SPAWN_Z), "the spawn stands on it");
        assertEquals(VoidWorld.SPAWN_Y - 1, p.get(0).y(), "right under the spawn's feet");
    }

    @Test
    void eachBlockIsPlacedByTheOneChunkItFallsIn() {
        List<VoidWorld.Block> all = new ArrayList<>();
        for (int cx = -2; cx <= 1; cx++) {
            for (int cz = -2; cz <= 1; cz++) {
                for (VoidWorld.Block b : VoidWorld.inChunk(cx, cz)) {
                    assertEquals(cx, Math.floorDiv(b.x(), 16), b + " is in chunk x " + cx);
                    assertEquals(cz, Math.floorDiv(b.z(), 16), b + " is in chunk z " + cz);
                    all.add(b);
                }
            }
        }
        assertEquals(VoidWorld.platform().size(), all.size(), "every block once, across the four chunks round 0,0");
        assertTrue(new HashSet<>(all).containsAll(VoidWorld.platform()), "and every one of them");
        assertEquals(10, VoidWorld.inChunk(0, 0).size(), "chunk 0,0 holds the floor's x, z 0..2 (9) and the light");
        assertEquals(4, VoidWorld.inChunk(-1, -1).size(), "chunk -1,-1 holds x, z -2..-1: 4");
        assertEquals(List.of(), VoidWorld.inChunk(5, 5), "a chunk far away holds nothing");
    }

    @Test
    void thePlatformIsFarFromEveryPlaceTheGamesBuild() {
        Box platform = Box.of(-2, 99, -2, 2, 102, 2);
        List<Box> places = new ArrayList<>();
        for (Slots.Def d : Slots.ALL) {
            places.add(d.half('A'));
            places.add(d.half('B'));
            places.add(LegacyBoxes.half(d, 'A'));
            places.add(LegacyBoxes.half(d, 'B'));
        }
        for (Slots.Def d : Slots.CLASSICS) {
            places.add(d.half('A'));
            places.add(d.half('B'));
        }
        places.add(LegacyBoxes.clubhouse());
        places.add(LegacyBoxes.fallingFloors());
        places.add(LegacyBoxes.keep().area());
        for (Box b : places) {
            assertTrue(platform.gap(b) >= Regions.CLEARANCE, "at least " + Regions.CLEARANCE + " from " + b.describe());
            assertTrue(Sight.chunksApart(platform, 0, b) >= 200, "and far out of sight: " + b.describe());
        }
    }

    @Test
    void theGeneratorMakesNothingButThePlatformAndFixesTheSpawn() {
        VoidWorld.Generator g = new VoidWorld.Generator();
        assertFalse(g.shouldGenerateNoise(), "no noise");
        assertFalse(g.shouldGenerateSurface(), "no surface");
        assertFalse(g.shouldGenerateCaves(), "no caves");
        assertFalse(g.shouldGenerateDecorations(), "no decorations");
        assertFalse(g.shouldGenerateMobs(), "no mobs");
        assertFalse(g.shouldGenerateStructures(), "no structures");
        Location spawn = g.getFixedSpawnLocation(null, new Random(1));
        assertNotNull(spawn, "a fixed spawn");
        assertEquals(List.of(0.5, 100.0, 0.5), List.of(spawn.getX(), spawn.getY(), spawn.getZ()),
                "in the middle of block (0, 100, 0), standing on the platform");
        assertNotNull(g.getDefaultBiomeProvider(null), "the void's own biomes");
        assertTrue(VoidWorld.ours(g), "ours");
        assertTrue(VoidWorld.ours(new VoidWorld.Generator()), "any instance of it");
        assertFalse(VoidWorld.ours(null), "no generator: a vanilla world");
        assertFalse(VoidWorld.ours(new ChunkGenerator() {
        }), "someone else's generator");
    }
}
