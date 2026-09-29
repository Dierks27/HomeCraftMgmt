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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The golf planner's block model (GEN-SPEC §4.3): a line that holes on it must hole on the real
 * blocks, so every block a plan may use reads here exactly as {@code LiveBlocks} reads it on the
 * server — the same surface (ice, slow, slime or normal, by {@code LiveBlocks.surface(Material)})
 * and the same collision top (a full block 1, a bottom slab 0.5, a sign nothing), at every point
 * of the block.
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
        REAL_TOP.put("minecraft:oak_sign", BallPhysics.Blocks.NONE);
        REAL_TOP.put("minecraft:oak_wall_sign", BallPhysics.Blocks.NONE);
    }

    /** Block-data text as plans write it: the slab as a bottom slab, signs with a state. */
    private static String data(String id) {
        return switch (id) {
            case "minecraft:smooth_stone_slab" -> Palette.RAMP;
            case "minecraft:oak_sign" -> Palette.sign(0);
            case "minecraft:oak_wall_sign" -> Palette.wallSign("north");
            case "minecraft:quartz_pillar" -> id + "[axis=y]";
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
            assertEquals(LiveBlocks.surface(m), PlanBlocks.surface(code),
                    id + " rolls on the same surface as on the server");
            assertEquals(REAL_TOP.get(id), PlanBlocks.top(code), id + " is as high as its real collision shape");
        }
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
