package com.dierks.homecraft.games.gen.api;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared sealed-pool rule (Course Variety §1.3): a golf pond one deep, walled and floored with
 * full blocks, inside its pool box, passes; water with air, a slab, a sign or leaves beside or
 * under it, water outside every pool box, water deeper than allowed and water that isn't still are
 * each caught and said once; a plan with no water has nothing to say.
 */
class PoolsTest {

    private static final String WATER = "minecraft:water[level=0]";
    private static final List<String> PALETTE = List.of(Palette.TURF_LIGHT, WATER, Palette.GOLF_WALL, Palette.RAMP,
            Palette.SAND_SLAB, Palette.leaves("oak", 1), Palette.SIGN, Palette.MOSS);
    private static final short TURF = 0;
    private static final short POND = 1;
    private static final short WALL = 2;
    private static final short SLAB = 3;
    private static final short LEAF = 5;
    /** The pool box: a 3 x 3 pond at y 10 (T - 1), over the turf-coloured floor at y 9. */
    private static final Box BOX = new Box(0, 10, 0, 2, 10, 2);

    /** A 3 x 3 pond at y 10 over a floor at y 9, ringed at y 10 by {@code ring}. */
    private static List<BlockOp> pond(short ring) {
        List<BlockOp> ops = new ArrayList<>();
        for (int x = -1; x <= 3; x++) {
            for (int z = -1; z <= 3; z++) {
                boolean inside = x >= 0 && x <= 2 && z >= 0 && z <= 2;
                ops.add(new BlockOp(x, 9, z, TURF));
                ops.add(new BlockOp(x, 10, z, inside ? POND : ring));
            }
        }
        return ops;
    }

    private static List<BlockOp> without(List<BlockOp> ops, int x, int y, int z) {
        List<BlockOp> out = new ArrayList<>(ops);
        out.removeIf(op -> op.x() == x && op.y() == y && op.z() == z);
        return out;
    }

    private static List<BlockOp> with(List<BlockOp> ops, BlockOp add) {
        List<BlockOp> out = without(ops, add.x(), add.y(), add.z());
        out.add(add);
        return out;
    }

    @Test
    void aWalledAndFlooredPondOneDeepInItsBoxPasses() {
        assertEquals(List.of(), Pools.problems(PALETTE, pond(WALL), List.of(BOX), Pools.GOLF_DEPTH),
                "sealed on every side and below, one deep, in its box");
        assertEquals(List.of(), Pools.problems(PALETTE, pond(TURF), List.of(BOX), 1), "turf walls seal as well");
        assertEquals(1, Pools.GOLF_DEPTH, "a golf pond is exactly one deep (§3.8 rule 2)");
        List<BlockOp> dry = new ArrayList<>(pond(WALL));
        dry.removeIf(op -> op.state() == POND);
        assertEquals(List.of(), Pools.problems(PALETTE, dry, List.of(), 1), "no water, nothing to say");
        assertEquals(List.of(), Pools.problems(null, List.of(), 1), "no plan, nothing to say");
    }

    @Test
    void waterBesideOrOverAnythingButWaterOrAFullBlockIsCaught() {
        List<BlockOp> side = without(pond(WALL), 3, 10, 1);
        List<String> open = Pools.problems(PALETTE, side, List.of(BOX), 1);
        assertEquals(1, open.size(), "one problem: " + open);
        assertTrue(open.get(0).startsWith("1 water block has air") && open.get(0).contains("sealed"),
                "air beside the pond: " + open);
        assertTrue(open.get(0).contains("first at 2 10 1"), "naming where: " + open);

        List<BlockOp> floor = without(pond(WALL), 1, 9, 1);
        assertTrue(Pools.problems(PALETTE, floor, List.of(BOX), 1).get(0).contains("sealed"), "a hole in the floor");

        for (short part : new short[]{SLAB, 4, LEAF, 6}) {
            List<BlockOp> ring = with(pond(WALL), new BlockOp(-1, 10, 1, part));
            List<String> p = Pools.problems(PALETTE, ring, List.of(BOX), 1);
            assertEquals(1, p.size(), PALETTE.get(part) + " beside the pond: " + p);
            assertTrue(p.get(0).contains("sealed"), PALETTE.get(part) + " can hold water: it never seals a pond");
            assertFalse(Pools.seals(PALETTE.get(part)), PALETTE.get(part) + " is not a seal");
        }
        List<BlockOp> onSlab = with(pond(WALL), new BlockOp(1, 9, 1, SLAB));
        assertTrue(Pools.problems(PALETTE, onSlab, List.of(BOX), 1).get(0).contains("sealed"),
                "a slab under the water leaves its top half open");
        assertTrue(Pools.seals(Palette.MOSS) && Pools.seals(Palette.SAND) && Pools.seals(Palette.GOLF_WALL)
                && Pools.seals(Palette.log("oak")) && Pools.seals(Palette.GLASS), "full blocks seal");
        assertFalse(Pools.seals(WATER) || Pools.seals("minecraft:sand") || Pools.seals(null),
                "water, a block off the list and nothing don't");
    }

    @Test
    void waterOutsideEveryPoolBoxOrDeeperThanAllowedIsCaught() {
        List<String> stray = Pools.problems(PALETTE, pond(WALL), List.of(new Box(0, 10, 0, 1, 10, 2)), 1);
        assertEquals(List.of("3 water blocks are outside every pool box"), stray, "the pond's third row is outside");
        assertEquals(List.of("9 water blocks are outside every pool box"), Pools.problems(PALETTE, pond(WALL),
                List.of(), 1), "no pool box: all of it");
        assertEquals(List.of(), Pools.problems(PALETTE, pond(WALL), List.of(new Box(0, 10, 0, 0, 10, 2),
                new Box(1, 10, 0, 2, 10, 2)), 1), "any of the boxes will do");

        List<BlockOp> deep = new ArrayList<>(); // the same pond two deep: its floor at y 8
        for (BlockOp op : pond(WALL)) {
            if (op.y() == 9) {
                deep.add(new BlockOp(op.x(), 8, op.z(), TURF));
            } else {
                deep.add(op);
                deep.add(new BlockOp(op.x(), 9, op.z(), op.state()));
            }
        }
        Box deepBox = new Box(0, 9, 0, 2, 10, 2);
        assertEquals(List.of("9 pool columns are more than 1 deep"), Pools.problems(PALETTE, deep, List.of(deepBox), 1),
                "water over water on golf: each column said once");
        assertEquals(List.of(), Pools.problems(PALETTE, deep, List.of(deepBox), 2), "a pool allowed to be 2 deep");
    }

    @Test
    void onlyStillWaterMayBeInAPool() {
        List<String> palette = new ArrayList<>(PALETTE);
        palette.add("minecraft:water[level=3]");
        List<String> p = Pools.problems(palette, pond(WALL), List.of(BOX), 1);
        assertEquals(List.of("1 palette entry is water that isn't still: only still sources may be placed"), p,
                "flowing water is never a pool");
    }

    @Test
    void aPlansOwnBlocksAreRead() {
        Plan plan = Plan.of("fresh_golf", 3, 1, new Box(-5, 0, -5, 10, 20, 10), PALETTE, pond(WALL), List.of(),
                List.of(), null, List.of(), 0);
        assertEquals(List.of(), Pools.problems(plan, List.of(BOX), 1), "the same rule from a plan");
        assertEquals(List.of(), Pools.problems((Plan) null, List.of(BOX), 1), "no plan, nothing to say");
    }
}
