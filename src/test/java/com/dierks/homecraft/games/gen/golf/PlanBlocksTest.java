package com.dierks.homecraft.games.gen.golf;

import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Palette;
import com.dierks.homecraft.games.golf.BallPhysics;
import com.dierks.homecraft.games.golf.LiveBlocks;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The golf planner's block model (GEN-SPEC §4.3): a line that holes on it must hole on the real
 * blocks, so every block a plan may use reads here exactly as {@code LiveBlocks} reads it on the
 * server for a generated course of golf algo 3 or later (Course Variety §3.4) — the same surface
 * (ice, slow, slime, water or normal, by {@code LiveBlocks.surface(Material, true)}) and the same
 * collision top (a full block 1, a bottom slab 0.5, a sign or water nothing), at every point of the
 * block. Every block but smooth sandstone reads the same on a hand-built course too, and no layout
 * of an older version has any, so none of their witness lines move.
 */
class PlanBlocksTest {

    /**
     * The real collision tops of every allowed block (Minecraft's block shapes: full cubes, the
     * bottom slab half a block, signs passable). Pinned: a block added to the palette must be
     * added here, with its real shape.
     */
    private static final Map<String, Double> REAL_TOP = new TreeMap<>();

    static {
        for (String id : Palette.ALLOWED) {
            REAL_TOP.put(id, 1.0);
        }
        REAL_TOP.put("minecraft:smooth_stone_slab", 0.5);
        REAL_TOP.put("minecraft:smooth_sandstone_slab", 0.5);
        REAL_TOP.put("minecraft:oak_sign", BallPhysics.Blocks.NONE);
        REAL_TOP.put("minecraft:oak_wall_sign", BallPhysics.Blocks.NONE);
    }

    /** Block-data text as plans write it: slabs as bottom slabs, signs, logs and leaves with their states. */
    private static String data(String id) {
        String wood = id.replace("minecraft:", "").replace("_log", "").replace("_leaves", "");
        return switch (id) {
            case "minecraft:smooth_stone_slab" -> Palette.RAMP;
            case "minecraft:smooth_sandstone_slab" -> Palette.SAND_SLAB;
            case "minecraft:oak_sign" -> Palette.sign(0);
            case "minecraft:oak_wall_sign" -> Palette.wallSign("north");
            case "minecraft:quartz_pillar" -> id + "[axis=y]";
            case "minecraft:oak_log", "minecraft:birch_log", "minecraft:cherry_log" -> Palette.log(wood);
            case "minecraft:oak_leaves", "minecraft:birch_leaves", "minecraft:cherry_leaves" -> Palette.leaves(wood, 2);
            default -> id;
        };
    }

    @Test
    void everyAllowedBlockReadsAsLiveBlocksReadsIt() {
        assertEquals(Palette.ALLOWED.size(), REAL_TOP.size(), "every allowed block has its real top pinned");
        for (String id : Palette.ALLOWED) {
            Material m = Material.matchMaterial(id);
            assertNotNull(m, id + " is a real block");
            byte code = PlanBlocks.code(data(id));
            assertEquals(LiveBlocks.surface(m, true), PlanBlocks.surface(code),
                    id + " rolls on the same surface as on the server, on a generated course of golf algo 3+");
            assertEquals(REAL_TOP.get(id), PlanBlocks.top(code), id + " is as high as its real collision shape");
            if (!id.startsWith("minecraft:smooth_sandstone")) {
                assertEquals(LiveBlocks.surface(m, false), PlanBlocks.surface(code),
                        id + " reads the same on a hand-built course too: only sand differs");
            }
        }
    }

    @Test
    void adventureGolfsBlocksAreTheOnesTheSpecNames() {
        assertEquals(PlanBlocks.WATER, PlanBlocks.code("minecraft:water[level=0]"), "a pond's still water");
        assertEquals(BallPhysics.Blocks.NONE, PlanBlocks.top(PlanBlocks.WATER), "water holds nothing up: the ball"
                + " drops in");
        assertEquals(BallPhysics.Surface.WATER, PlanBlocks.surface(PlanBlocks.WATER), "and it is wet");
        assertEquals(LiveBlocks.surface(Material.WATER, true), PlanBlocks.surface(PlanBlocks.WATER),
                "as the server reads it");
        assertEquals(PlanBlocks.SAND, PlanBlocks.code(Palette.SAND), "smooth sandstone is sand");
        assertEquals(1.0, PlanBlocks.top(PlanBlocks.SAND), "a full block (a flush bunker)");
        assertEquals(BallPhysics.Surface.SLOW, PlanBlocks.surface(PlanBlocks.SAND), "and slow (§3.4)");
        assertEquals(PlanBlocks.SAND_SLAB, PlanBlocks.code(Palette.SAND_SLAB), "its bottom slab");
        assertEquals(0.5, PlanBlocks.top(PlanBlocks.SAND_SLAB), "is half a block (a sunken bunker, top T - 0.5)");
        assertEquals(BallPhysics.Surface.SLOW, PlanBlocks.surface(PlanBlocks.SAND_SLAB), "and slow too");
        assertTrue(PlanBlocks.slab(PlanBlocks.SAND_SLAB) && PlanBlocks.slab(PlanBlocks.code(Palette.RAMP))
                && !PlanBlocks.slab(PlanBlocks.SAND), "both slabs are slabs, the full block isn't");
        for (String wood : Palette.WOODS) {
            byte leaf = PlanBlocks.code(Palette.leaves(wood, 3));
            assertEquals(PlanBlocks.LEAVES, leaf, wood + " leaves have their own code (a canopy is told apart)");
            assertEquals(1.0, PlanBlocks.top(leaf), "but to the ball they are a full block");
            assertEquals(BallPhysics.Surface.NORMAL, PlanBlocks.surface(leaf), "of a normal surface");
            assertEquals(PlanBlocks.FULL, PlanBlocks.code(Palette.log(wood)), wood + " logs are full, normal blocks");
        }
        for (String full : List.of(Palette.MOSS, Palette.BLUE_GLASS, Palette.GLASS)) {
            assertEquals(PlanBlocks.FULL, PlanBlocks.code(full), full + " is a full, normal block");
        }
    }

    @Test
    void waterIsOnlyAStillSourceAndIsNeverSolid() {
        assertThrows(IllegalArgumentException.class, () -> PlanBlocks.code("minecraft:water[level=1]"),
                "flowing water is no golf block");
        assertThrows(IllegalArgumentException.class, () -> PlanBlocks.code("minecraft:water"),
                "nor is water that doesn't say it is a still source");
        assertThrows(IllegalArgumentException.class, () -> PlanBlocks.code("minecraft:smooth_sandstone_slab"),
                "a sand slab must say it is a bottom slab");
        assertThrows(IllegalArgumentException.class,
                () -> PlanBlocks.code("minecraft:smooth_sandstone_slab[type=top]"), "and be one");
        Box box = Box.sized(0, 60, 0, 2, 2, 2);
        PlanBlocks g = PlanBlocks.of(box, List.of("minecraft:water[level=0]", Palette.SAND_SLAB),
                List.of(new BlockOp(0, 60, 0, (short) 0), new BlockOp(1, 60, 0, (short) 1)));
        assertFalse(g.solid(0, 60, 0), "water isn't solid");
        assertEquals(BallPhysics.Blocks.NONE, g.top(0, 60, 0, 0.5, 0.5), "the ball finds nothing to stand on in it");
        assertEquals(BallPhysics.Surface.WATER, g.surface(0, 60, 0), "and its surface is wet");
        assertTrue(g.solid(1, 60, 0), "a sand slab is solid");
        assertEquals(0.5, g.top(1, 60, 0, 1.9, 0.1), "half a block high everywhere in it");
    }

    @Test
    void theGolfBlocksAreTheOnesTheSpecNames() {
        assertEquals(BallPhysics.Surface.ICE, PlanBlocks.surface(PlanBlocks.code(Palette.GOLF_ICE)),
                "packed ice is ice");
        assertEquals(BallPhysics.Surface.SLOW, PlanBlocks.surface(PlanBlocks.code(Palette.BRAKE)),
                "soul soil brakes");
        assertEquals(1.0, PlanBlocks.top(PlanBlocks.code(Palette.BRAKE)),
                "soul soil is a full block (soul sand's top would be 0.875)");
        assertEquals(BallPhysics.Surface.SLIME, PlanBlocks.surface(PlanBlocks.code(Palette.BUMPER)),
                "slime bounces");
        assertEquals(0.5, PlanBlocks.top(PlanBlocks.code(Palette.RAMP)), "a ramp step is half a block");
        for (String normal : List.of(Palette.TURF_LIGHT, Palette.TURF_DARK, Palette.GOLF_WALL, Palette.CUP,
                Palette.CUP_RING, Palette.TEE, Palette.FLAG)) {
            assertEquals(BallPhysics.Surface.NORMAL, PlanBlocks.surface(PlanBlocks.code(normal)),
                    normal + " is a normal surface");
            assertEquals(1.0, PlanBlocks.top(PlanBlocks.code(normal)), normal + " is a full block");
        }
    }

    @Test
    void onlyBlocksTheModelMatchesAreAccepted() {
        assertThrows(IllegalArgumentException.class, () -> PlanBlocks.code("minecraft:sand"),
                "a block off the allowlist can't be modelled");
        assertThrows(IllegalArgumentException.class,
                () -> PlanBlocks.code("minecraft:smooth_stone_slab[type=top]"),
                "a top slab's shape isn't a top height: not a golf block");
        assertThrows(IllegalArgumentException.class, () -> PlanBlocks.code("minecraft:smooth_stone_slab"),
                "a slab must say it is a bottom slab");
    }

    @Test
    void aGridAnswersTheSameEverywhereInABlockAndAirOutsideItsBox() {
        Box box = Box.sized(100, 60, -50, 4, 4, 4);
        List<String> palette = List.of(Palette.TURF_LIGHT, Palette.RAMP, Palette.GOLF_ICE, Palette.sign(3));
        List<BlockOp> ops = new ArrayList<>();
        ops.add(new BlockOp(100, 60, -50, (short) 0));
        ops.add(new BlockOp(101, 60, -50, (short) 1));
        ops.add(new BlockOp(102, 60, -50, (short) 2));
        ops.add(new BlockOp(103, 60, -50, (short) 3));
        PlanBlocks g = PlanBlocks.of(box, palette, ops);
        for (double f : new double[]{0.0, 0.01, 0.5, 0.99}) {
            assertEquals(1.0, g.top(100, 60, -50, 100 + f, -50 + f), "a full block is 1 high at " + f);
            assertEquals(0.5, g.top(101, 60, -50, 101 + f, -50 + f), "a slab is 0.5 high at " + f);
        }
        assertEquals(BallPhysics.Surface.ICE, g.surface(102, 60, -50), "ice where the ice is");
        assertEquals(BallPhysics.Blocks.NONE, g.top(103, 60, -50, 103.5, -49.5), "a sign is air to the ball");
        assertEquals(BallPhysics.Blocks.NONE, g.top(100, 61, -50, 100.5, -49.5), "air above");
        assertEquals(BallPhysics.Blocks.NONE, g.top(99, 60, -50, 99.5, -49.5), "air outside the box");
        assertEquals(BallPhysics.Surface.NORMAL, g.surface(99, 60, -50),
                "and a normal surface, like air on the server");
        assertThrows(IllegalArgumentException.class, () -> PlanBlocks.of(box, palette,
                List.of(new BlockOp(104, 60, -50, (short) 0))), "a block outside the grid's box is a bug");
    }
}
