package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.golf.GolfValidator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PlanCheck's block cap per generator (MOUNTAIN-V2-SPEC §10.3 item 4): the Mountain Run v2 (boat algo 4 on) is
 * a whole mountain, about 350,000 blocks, and may place up to 400,000; an older boat plan and every other
 * generator stay at 100,000 (golf at its own validator's cap if that is ever higher). Per plan: a recall or
 * keep of an older plan is held to the cap it was made under.
 */
class PlanCheckCapsTest {

    @Test
    void theCapsArePerGeneratorAndPlannerVersion() {
        assertEquals(100_000, PlanCheck.maxOps(Slots.BOAT, 3), "the v3 Mountain Run: as before");
        assertEquals(400_000, PlanCheck.maxOps(Slots.BOAT, 4), "the Mountain Run v2");
        assertEquals(400_000, PlanCheck.maxOps(Slots.BOAT, 5), "and later");
        assertEquals(100_000, PlanCheck.maxOps(Slots.PARKOUR, 9), "parkour");
        assertEquals(100_000, PlanCheck.maxOps(Slots.DROPPER, 1), "the Dropper");
        assertTrue(PlanCheck.maxOps(Slots.GOLF, 4) >= GolfValidator.MAX_OPS && PlanCheck.maxOps(Slots.GOLF, 4)
                >= PlanCheck.MAX_OPS, "golf: never below its own validator's cap");
        assertEquals(100_000, PlanCheck.maxOps((Slots.Def) null, null), "no generator: the shared cap");
    }

    /** A plan of {@code n} blocks for the boat's v4 half at {@code algo}, every block inside it. */
    private static Plan boat(int n, int algo) {
        Box half = Slots.ICE_BOAT.half('A');
        List<BlockOp> ops = new ArrayList<>(n);
        for (int i = 0; ops.size() < n; i++) {
            int x = half.minX() + i % half.sizeX();
            int z = half.minZ() + (i / half.sizeX()) % half.sizeZ();
            int y = half.minY() + i / (half.sizeX() * half.sizeZ());
            ops.add(new BlockOp(x, y, z, (short) 0));
        }
        Plan p = GenKit.plan(Slots.ICE_BOAT, half, 1, algo);
        return Plan.of(Slots.ICE_BOAT.id(), algo, 1, half, List.of("minecraft:packed_ice"), ops, List.of(), List.of(),
                p.course(), List.of(), 1);
    }

    private static boolean tooMany(Plan p) {
        return PlanCheck.problems(p, Slots.ICE_BOAT, Slots.ICE_BOAT.half('A')).stream()
                .anyMatch(s -> s.startsWith("the plan places"));
    }

    @Test
    void aMountainOfMoreThanAHundredThousandBlocksPassesAndOneOverFourHundredThousandIsRefused() {
        assertFalse(tooMany(boat(150_000, 4)), "150,000 blocks: a v4 mountain is that big");
        assertTrue(tooMany(boat(400_001, 4)), "400,001: too many even for a mountain");
        assertTrue(PlanCheck.problems(boat(400_001, 4), Slots.ICE_BOAT, Slots.ICE_BOAT.half('A')).contains(
                "the plan places 400001 blocks, more than 400000"), "said with the cap");
        assertTrue(tooMany(boat(100_001, 3)), "an algo-3 plan of 100,001 is still refused: its cap is 100,000");
    }
}
