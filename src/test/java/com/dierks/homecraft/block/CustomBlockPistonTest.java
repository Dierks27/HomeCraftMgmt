package com.dierks.homecraft.block;

import com.dierks.homecraft.arcade.PrizeItemListener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pistons, flows and explosions leave ours standing (0.37.3, spec §4.15 (3)-(4), D17, red team D-M1): "ours" is a
 * placed HomeCraft block, the upper head of a Vending Machine, or a placed prize head (a hat or a trophy). A push or
 * pull that moves one, or breaks one in front of its line, is refused; water or lava never flows into one; and an
 * explosion (TNT, or a bed or respawn anchor) takes ours out of its block list. Placed prizes used to pop onto the
 * ground for anyone to pick up.
 */
class CustomBlockPistonTest {

    /** A block position; the test's world is a map of what stands where. */
    private record Pos(int x, int y, int z) {
        Pos east(int n) {
            return new Pos(x + n, y, z);
        }
    }

    private static final Pos PISTON = new Pos(0, 64, 0);
    private static final Map<Pos, String> WORLD = Map.of(
            new Pos(3, 64, 0), "a placed PC",
            new Pos(5, 65, 0), "a Vending Machine's upper head",
            new Pos(8, 64, 0), "a placed trophy",
            new Pos(9, 64, 0), "a placed Display Case");
    private static final Predicate<Pos> OURS = WORLD::containsKey;

    /** What a piston at {@link #PISTON} pushing east touches, moving {@code moved} blocks from {@code from}. */
    private static List<Pos> push(Pos from, int moved) {
        List<Pos> line = new ArrayList<>();
        for (int i = 0; i < moved; i++) {
            line.add(from.east(i));
        }
        Pos inFront = PISTON.east(moved + 1); // as the listener works it out: one past the end of the moving line
        return CustomBlockGuard.pistonTouches(line, inFront);
    }

    @Test
    void aPushOrPullTouchingOneOfOursIsRefused() {
        assertFalse(CustomBlockGuard.pistonRefused(push(PISTON.east(1), 1), OURS),
                "a push that touches nothing of ours goes ahead");
        assertTrue(CustomBlockGuard.pistonRefused(push(PISTON.east(1), 3), OURS),
                "a push that would move a placed custom block (its record would point at the old spot)");
        assertTrue(CustomBlockGuard.pistonRefused(push(PISTON.east(1), 2), OURS),
                "the block one past the end of the moving line (the PC at x 3) counts: refused");
        assertTrue(CustomBlockGuard.pistonRefused(
                        CustomBlockGuard.pistonTouches(List.of(new Pos(7, 64, 0)), new Pos(8, 64, 0)), OURS),
                "a placed trophy in front of the line would pop off: refused");
        assertTrue(CustomBlockGuard.pistonRefused(
                        CustomBlockGuard.pistonTouches(List.of(), new Pos(9, 64, 0)), OURS),
                "a piston with nothing to move still breaks the head right in front of it");
        assertTrue(CustomBlockGuard.pistonRefused(
                        CustomBlockGuard.pistonTouches(List.of(new Pos(5, 65, 0)), null), OURS),
                "a sticky piston pulling a Vending Machine's top");
        assertFalse(CustomBlockGuard.pistonRefused(
                        CustomBlockGuard.pistonTouches(List.of(new Pos(6, 64, 0)), null), OURS),
                "a pull moves only what it moves: nothing of ours, so it goes ahead");
        assertEquals(List.of(new Pos(1, 64, 0)), CustomBlockGuard.pistonTouches(List.of(new Pos(1, 64, 0)), null),
                "a retract has no block in front");
    }

    @Test
    void waterOrLavaNeverFlowsIntoOneOfOurs() {
        for (Pos p : WORLD.keySet()) {
            assertTrue(CustomBlockGuard.flowRefused(p, OURS), WORLD.get(p) + " is never washed away");
        }
        assertFalse(CustomBlockGuard.flowRefused(new Pos(1, 1, 1), OURS), "any other block flows as ever");
        assertFalse(CustomBlockGuard.flowRefused(null, OURS));
    }

    @Test
    void tntAndABedOrAnchorExplosionNextToAPlacedTrophyLeaveItStanding() {
        for (String blast : List.of("TNT (an entity explosion)", "a bed or respawn anchor (a block explosion)")) {
            List<Pos> blocks = new ArrayList<>(List.of(new Pos(7, 64, 0), new Pos(8, 64, 0), new Pos(8, 65, 0),
                    new Pos(9, 64, 0), new Pos(3, 64, 0)));
            assertEquals(3, CustomBlockGuard.spare(blocks, OURS), blast + ": the trophy, the Display Case, the PC");
            assertEquals(List.of(new Pos(7, 64, 0), new Pos(8, 65, 0)), blocks,
                    blast + ": only the rest is blown up, in its order");
        }
    }

    @Test
    void theGuardsRunEarlyAndSkipWhatIsAlreadyCancelled() throws Exception {
        for (String[] h : new String[][]{{"onPistonExtend", BlockPistonExtendEvent.class.getName()},
                {"onPistonRetract", BlockPistonRetractEvent.class.getName()},
                {"onFlow", BlockFromToEvent.class.getName()}}) {
            EventHandler e = handler(CustomBlockListener.class, h[0], Class.forName(h[1]));
            assertEquals(EventPriority.LOW, e.priority(), h[0] + ": refused before anything acts on the move");
            assertTrue(e.ignoreCancelled(), h[0]);
        }
        assertTrue(handler(CustomBlockListener.class, "onEntityExplode", EntityExplodeEvent.class).ignoreCancelled());
        assertTrue(handler(CustomBlockListener.class, "onBlockExplode", BlockExplodeEvent.class).ignoreCancelled());
    }

    /** The old handlers popped a placed prize onto the ground; the guard keeps it in place instead. */
    @Test
    void placedPrizesAreNoLongerPoppedOffByTheirOwnListener() {
        for (Method m : PrizeItemListener.class.getDeclaredMethods()) {
            assertFalse(List.of("onPiston", "onFlow", "onEntityExplode", "onBlockExplode", "dropAll", "dropAt")
                    .contains(m.getName()), "PrizeItemListener." + m.getName() + " is gone");
        }
    }

    private static EventHandler handler(Class<?> listener, String name, Class<?> event) throws Exception {
        EventHandler h = listener.getMethod(name, event).getAnnotation(EventHandler.class);
        assertNotNull(h, listener.getSimpleName() + "." + name + " is a handler");
        return h;
    }
}
