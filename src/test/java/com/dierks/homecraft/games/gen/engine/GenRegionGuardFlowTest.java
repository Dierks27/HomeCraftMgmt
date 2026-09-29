package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Slots;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.block.BlockFromToEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Nothing flows out of a half (EVENTS-DROPPER-SPEC §B.1.9, safety S2), as pure decisions and on real
 * {@code BlockFromToEvent}s over fake blocks: a fluid whose SOURCE is in a half never moves
 * ({@code FLOW_OUT}: a Dropper's pool can't spill out, even through a gap a bug left), a fluid flowing
 * INTO a half is refused as before ({@code FLOW_INTO}), and a flow that touches no half is none of the
 * guard's business. A flow-only box (a kept Dropper's plot) refuses only fluids flowing out of it.
 *
 * <p>And the handler is cheap, since it runs for every fluid in every world: in a world the areas
 * don't cover it looks at no block and asks nobody where the fluid goes; it asks each area once for
 * the source, and for where the fluid goes only when the source decided nothing.
 */
class GenRegionGuardFlowTest {

    private static final Box HALF = Slots.FRESH_DROPPER.half('A');
    private static final GenRegionGuard.Area AREA = (w, x, y, z) -> "games".equalsIgnoreCase(w)
            && HALF.contains(x, y, z);
    private static final Box PLOT = Box.sized(HALF.maxX() + 100, HALF.minY(), HALF.minZ(), 64, 64, 16);

    @Test
    void waterFlowingOutOfAHalfIsRefused() {
        int y = HALF.minY() + 10;
        int[] inside = {HALF.minX(), y, HALF.minZ() + 5};
        int[] outside = {HALF.minX() - 1, y, HALF.minZ() + 5};
        assertTrue(GenRegionGuard.refused(GenRegionGuard.Change.FLOW_OUT, true, false), "FLOW_OUT inside is refused");
        assertTrue(GenRegionGuard.refused(GenRegionGuard.Change.FLOW_OUT, true, true), "for everyone, admins included");
        assertTrue(GenRegionGuard.flowRefused(AREA, "games", inside, outside), "a pool leaking out of its half is stopped");
        assertTrue(GenRegionGuard.flowRefused(AREA, "games", outside, inside), "water flowing in is stopped, as before");
        assertTrue(GenRegionGuard.flowRefused(AREA, "games", inside, new int[]{inside[0] + 1, y - 1, inside[2]}),
                "and water in a half never moves inside it either");
        assertFalse(GenRegionGuard.flowRefused(AREA, "games", outside, new int[]{outside[0] - 1, y, outside[2]}),
                "a flow that touches no half is fine");
        assertFalse(GenRegionGuard.flowRefused(AREA, "world", inside, outside), "nor in another world");
        assertTrue(GenRegionGuard.flowRefused(AREA, "games", inside, null), "a source in the half, whatever its target");
        assertFalse(GenRegionGuard.flowRefused(AREA, "games", null, null), "nothing to judge");
    }

    // ---- the handler, on real events ---------------------------------------------------------------

    /** How often the fakes were asked something. */
    private static final class Asked {
        final AtomicInteger to = new AtomicInteger();
        final AtomicInteger coords = new AtomicInteger();
        final AtomicInteger area = new AtomicInteger();
    }

    private static World world(String name) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
                (proxy, m, args) -> switch (m.getName()) {
                    case "getName" -> name;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }

    /** A block at (x, y, z) of {@code world}; its neighbours are made when asked for (getRelative). */
    private static Block block(World world, int x, int y, int z, Asked asked) {
        return (Block) Proxy.newProxyInstance(Block.class.getClassLoader(), new Class<?>[]{Block.class},
                (proxy, m, args) -> switch (m.getName()) {
                    case "getWorld" -> world;
                    case "getX" -> {
                        asked.coords.incrementAndGet();
                        yield x;
                    }
                    case "getY" -> y;
                    case "getZ" -> z;
                    case "getRelative" -> {
                        asked.to.incrementAndGet();
                        BlockFace f = (BlockFace) args[0];
                        yield block(world, x + f.getModX(), y + f.getModY(), z + f.getModZ(), asked);
                    }
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }

    /** A fluid at (x, y, z) of {@code world} flowing down. */
    private static BlockFromToEvent flow(String world, int x, int y, int z, Asked asked) {
        return new BlockFromToEvent(block(world(world), x, y, z, asked), BlockFace.DOWN);
    }

    /** {@code box} in "games", counting each look, covering {@code worlds}. */
    private static GenRegionGuard.Area counted(Box box, Asked asked, String... worlds) {
        return new GenRegionGuard.Area() {
            @Override
            public boolean in(String w, int x, int y, int z) {
                asked.area.incrementAndGet();
                return "games".equalsIgnoreCase(w) && box.contains(x, y, z);
            }

            @Override
            public boolean covers(String w) {
                return Arrays.stream(worlds).anyMatch(w::equalsIgnoreCase);
            }
        };
    }

    @Test
    void theHandlerJudgesTheSourceFirstAndWhereItGoesOnlyThen() {
        Asked asked = new Asked();
        int y = HALF.minY() + 10;
        assertTrue(GenRegionGuard.flowed(counted(HALF, asked, "games"), null,
                flow("games", HALF.minX(), y, HALF.minZ(), asked)), "water in a half never moves (its source, getBlock)");
        assertEquals(0, asked.to.get(), "decided by the source: where it goes is never asked for");
        assertEquals(1, asked.area.get(), "and the area is looked at once");

        Asked above = new Asked();
        assertTrue(GenRegionGuard.flowed(counted(HALF, above, "games"), null,
                        flow("games", HALF.minX(), HALF.maxY() + 1, HALF.minZ(), above)),
                "water flowing down INTO a half is refused (where it goes, getToBlock)");
        assertEquals(1, above.to.get(), "that needed where it goes, asked for once");
        assertEquals(2, above.area.get(), "the source, then where it goes");

        Asked beside = new Asked();
        BlockFromToEvent e = flow("games", HALF.minX() - 3, y, HALF.minZ(), beside);
        assertFalse(GenRegionGuard.flowed(counted(HALF, beside, "games"), null, e), "a flow that touches no half is fine");
        assertFalse(e.isCancelled(), "and the event is left alone");
    }

    @Test
    void inAWorldTheAreasDontCoverTheHandlerLooksAtNothing() {
        Asked asked = new Asked();
        BlockFromToEvent e = flow("world", HALF.minX(), HALF.minY() + 10, HALF.minZ(), asked);
        assertFalse(GenRegionGuard.flowed(counted(HALF, asked, "games"), counted(PLOT, asked, "games"), e),
                "the economy world's rivers are none of the guard's business");
        assertEquals(0, asked.area.get(), "no area is looked in");
        assertEquals(0, asked.coords.get(), "no block's place is read");
        assertEquals(0, asked.to.get(), "and nobody asks where it goes");
    }

    @Test
    void aFlowOnlyBoxKeepsItsWaterInAndLetsTheRestBe() {
        Asked asked = new Asked();
        GenRegionGuard.Area area = counted(HALF, asked, "games");
        GenRegionGuard.Area wet = counted(PLOT, asked, "games");
        int y = PLOT.minY() + 5;
        assertTrue(GenRegionGuard.flowed(area, wet, flow("games", PLOT.minX() + 3, y, PLOT.minZ() + 3, asked)),
                "a kept Dropper's water never flows, not even inside its plot");
        assertTrue(GenRegionGuard.flowed(area, wet, flow("games", PLOT.minX(), PLOT.minY(), PLOT.minZ(), asked)),
                "nor out of it at the bottom");
        assertFalse(GenRegionGuard.flowed(area, wet, flow("games", PLOT.minX() + 3, PLOT.maxY() + 1, PLOT.minZ() + 3,
                asked)), "water from outside may flow into the plot: the keep area is hand-built ground");
        assertFalse(GenRegionGuard.flowed(area, wet, flow("games", PLOT.minX() - 2, y, PLOT.minZ(), asked)),
                "and water beside it is fine");
        Asked alone = new Asked();
        assertTrue(GenRegionGuard.flowed(null, counted(PLOT, alone, "games"),
                flow("games", PLOT.minX() + 1, y, PLOT.minZ() + 1, alone)), "a flow-only box works on its own");
        assertEquals(0, alone.to.get(), "and never needs where the fluid goes");
    }

    @Test
    void anAreaThatNamesNoWorldsCoversThemAll() {
        GenRegionGuard.Area plain = (w, x, y, z) -> "games".equalsIgnoreCase(w) && HALF.contains(x, y, z);
        assertTrue(plain.covers("anything"), "an area that doesn't say which worlds it covers is asked everywhere");
        Asked asked = new Asked();
        assertTrue(GenRegionGuard.flowed(plain, null, flow("games", HALF.minX(), HALF.minY(), HALF.minZ(), asked)),
                "so the arena's guard (a plain box) works as before");
    }
}
