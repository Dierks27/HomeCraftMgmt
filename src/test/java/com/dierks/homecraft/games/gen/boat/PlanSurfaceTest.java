package com.dierks.homecraft.games.gen.boat;

import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Palette;
import com.dierks.homecraft.games.trial.RaceGrid;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A plan's blocks as the race grid reads the world (Course Variety §2.10 V10): what the live
 * surface would say once the plan is built. Every placed block is solid (ice, walls, glass, leaves,
 * slabs), still water is water, and everything the plan doesn't place (signs included) is air.
 */
class PlanSurfaceTest {

    @Test
    void placedBlocksAreSolidWaterIsWaterAndTheRestIsAir() {
        List<String> palette = List.of(Palette.TRACK, Palette.leaves("oak", 1), "minecraft:water[level=0]",
                Palette.RAMP, Palette.GLASS);
        List<BlockOp> ops = List.of(new BlockOp(1, 10, 1, (short) 0), new BlockOp(2, 10, 1, (short) 1),
                new BlockOp(3, 10, 1, (short) 2), new BlockOp(4, 10, 1, (short) 3), new BlockOp(-5, -3, 7, (short) 4));
        PlanSurface s = new PlanSurface(palette, ops);
        assertEquals(RaceGrid.Cell.SOLID, s.at(1, 10, 1), "ice is a floor a boat sits on");
        assertEquals(RaceGrid.Cell.SOLID, s.at(2, 10, 1), "leaves aren't passable: a wall at boat height");
        assertEquals(RaceGrid.Cell.WATER, s.at(3, 10, 1), "a still source is water");
        assertEquals(RaceGrid.Cell.SOLID, s.at(4, 10, 1), "a slab is solid");
        assertEquals(RaceGrid.Cell.SOLID, s.at(-5, -3, 7), "negative coordinates read back too");
        assertEquals(RaceGrid.Cell.AIR, s.at(1, 11, 1), "over the ice is air: the builder makes the half equal to the plan");
        assertEquals(RaceGrid.Cell.AIR, s.at(100, 10, 100), "anywhere the plan places nothing is air");
    }

    @Test
    void theHandMadeRunsStandIsStandableOnItsOwnBlocks() {
        var p = HandRun.easy().plan();
        PlanSurface s = new PlanSurface(p);
        var spot = com.dierks.homecraft.games.trial.RaceStand.spot(p.half(), HandRun.TOP + 1);
        assertEquals(true, com.dierks.homecraft.games.trial.RaceStand.standable(s, spot),
                "the platform is solid with two blocks of air over it (its sign is air)");
    }
}
