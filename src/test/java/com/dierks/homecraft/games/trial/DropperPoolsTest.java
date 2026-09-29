package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.gen.dropper.DropMarks;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.block.BlockFromToEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Time Trials' own flow guard over every Dropper course's water ({@link DropperPools}, the WP-D
 * review's #3: a pool must never outlive its guard, and Fresh Courses' guard is off while Fresh
 * Courses is): exactly the pools' water blocks are held, in the course's world, for every Dropper row
 * and no other kind; the hook refuses a flow whose source is one of them, and anywhere else looks at
 * no block at all.
 */
class DropperPoolsTest {

    private static final Course HAND = DropperCourses.hand(); // world "games"

    @Test
    void aPoolsWaterIsItsBoxFromTheFloorToTheSurface() {
        assertArrayEquals(new int[]{7, 65, 7, 17, 67, 17}, DropperPools.blocks(DropperCourses.POOL_1),
                "Easy: 11 x 11, three deep, the surface at 68 (its top row of water is 67)");
        assertArrayEquals(new int[]{34, 49, 10, 38, 51, 14}, DropperPools.blocks(DropperCourses.POOL_3),
                "Hard: 5 x 5");
        for (Course.Mark pool : List.of(DropperCourses.POOL_1, DropperCourses.POOL_2, DropperCourses.POOL_3)) {
            int[] b = DropperPools.blocks(pool);
            int side = (int) Math.rint(2 * DropMarks.halfWidth(pool));
            assertEquals(side, b[3] - b[0] + 1, "as wide as the pool");
            assertEquals(3, b[4] - b[1] + 1, "three deep");
        }
    }

    @Test
    void everyDropperCoursesWaterIsHeldAndNothingElse() {
        DropperPools pools = DropperPools.of(List.of(HAND, DropperCourses.asParkour()));
        assertEquals(3, pools.size(), "the Dropper's three pools; the same marks as a parkour course are no pools");
        assertTrue(pools.holds("games", 7, 65, 7) && pools.holds("GAMES", 17, 67, 17), "the Easy pool's corners");
        assertTrue(pools.holds("games", 36, 50, 12), "the Hard pool's middle");
        assertFalse(pools.holds("games", 6, 66, 12), "the wall beside it isn't water");
        assertFalse(pools.holds("games", 12, 68, 12), "nor the air over the surface");
        assertFalse(pools.holds("games", 12, 64, 12), "nor the floor under it");
        assertFalse(pools.holds("world", 12, 66, 12), "nor the same place in another world");
        assertTrue(pools.covers("games") && !pools.covers("world") && !pools.covers(null), "one world holds pools");

        Course malformed = new Course("bad", TrialKind.DROPPER, "Bad", Tier.EASY, "games", DropperCourses.START,
                List.of(DropperCourses.POOL_1), DropperCourses.POOL_3, 44.0, 4, true, false, 1);
        assertEquals(0, DropperPools.of(List.of(malformed)).size(), "a malformed Dropper row names no pools");
        assertEquals(0, DropperPools.of(null).size(), "no courses, no pools");
        assertFalse(DropperPools.NONE.holds("games", 12, 66, 12), "and none holds nothing");
    }

    @Test
    void theHookRefusesAFlowOutOfAPoolAndLooksAtNothingElsewhere() {
        DropperPools pools = DropperPools.of(List.of(HAND));
        AtomicInteger read = new AtomicInteger();
        assertTrue(DropperHooks.held(flow("games", 7, 65, 12, read), pools),
                "water from a pool whose wall was broken never moves");
        assertFalse(DropperHooks.held(flow("games", 6, 65, 12, read), pools), "water beside a pool is none of its business");
        int before = read.get();
        assertFalse(DropperHooks.held(flow("world", 7, 65, 12, read), pools), "nor a river in the economy world");
        assertEquals(before, read.get(), "whose place isn't even read");
    }

    private static BlockFromToEvent flow(String world, int x, int y, int z, AtomicInteger read) {
        World w = (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
                (proxy, m, args) -> "getName".equals(m.getName()) ? world : null);
        Block b = (Block) Proxy.newProxyInstance(Block.class.getClassLoader(), new Class<?>[]{Block.class},
                (proxy, m, args) -> switch (m.getName()) {
                    case "getWorld" -> w;
                    case "getX" -> {
                        read.incrementAndGet();
                        yield x;
                    }
                    case "getY" -> y;
                    case "getZ" -> z;
                    default -> null;
                });
        return new BlockFromToEvent(b, BlockFace.DOWN);
    }
}
