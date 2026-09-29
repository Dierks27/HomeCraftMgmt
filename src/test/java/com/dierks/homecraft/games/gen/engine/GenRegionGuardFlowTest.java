package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Slots;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Nothing flows out of a half (EVENTS-DROPPER-SPEC §B.1.9, safety S2), as pure decisions: a fluid
 * whose SOURCE is in a half never moves ({@code FLOW_OUT}: a Dropper's pool can't spill out, even
 * through a gap a bug left), a fluid flowing INTO a half is refused as before ({@code FLOW_INTO}), and
 * a flow that touches no half is none of the guard's business. The handler is wired to both ends of
 * {@code BlockFromToEvent}.
 */
class GenRegionGuardFlowTest {

    private static final Box HALF = Slots.FRESH_DROPPER.half('A');
    private static final GenRegionGuard.Area AREA = (w, x, y, z) -> "games".equalsIgnoreCase(w)
            && HALF.contains(x, y, z);

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

    @Test
    void theFlowHandlerLooksAtBothEnds() throws IOException {
        String src = Files.readString(Path.of("src/main/java/com/dierks/homecraft/games/gen/engine/GenRegionGuard.java"));
        assertTrue(src.contains("BlockFromToEvent.class, p, true, e -> g.flow(area, e, e.getBlock(), e.getToBlock(), log))"),
                "the source block (getBlock) and where it goes (getToBlock) are both judged");
    }
}
