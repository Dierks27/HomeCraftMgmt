package com.dierks.homecraft.games.gen.boat;

import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Palette;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.trial.RaceGrid;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A plan's own blocks as a race grid reads the world (Course Variety §2.10 V10): the
 * {@link RaceGrid.Surface} the live {@link com.dierks.homecraft.games.trial.WorldSurface} would be
 * once the plan is built, so the real {@link RaceGrid#plan} can be run on a layout before a single
 * block is set, and "raceable" is proven rather than assumed.
 *
 * <p>The mapping is the live one's: a still water source is {@link RaceGrid.Cell#WATER}; every
 * other block a plan places (ice, walls, glass, leaves, slabs: none of them is passable) is
 * {@link RaceGrid.Cell#SOLID}; everything else is {@link RaceGrid.Cell#AIR}, and that includes
 * signs (a player walks through one) and every block the plan doesn't place, because the builder
 * makes the half equal to the plan, air wherever it has no op. Pure: no Bukkit.
 */
public final class PlanSurface implements RaceGrid.Surface {

    private final Map<Long, RaceGrid.Cell> cells;

    /** The surface of {@code plan}'s blocks. */
    public PlanSurface(Plan plan) {
        this(plan.palette(), plan.ops());
    }

    /** The surface of these blocks, each naming an entry of {@code palette}. */
    public PlanSurface(List<String> palette, List<BlockOp> ops) {
        cells = new HashMap<>(Math.max(16, ops.size() * 2));
        RaceGrid.Cell[] byState = new RaceGrid.Cell[palette.size()];
        for (int i = 0; i < byState.length; i++) {
            byState[i] = Palette.poolWater(palette.get(i)) ? RaceGrid.Cell.WATER : RaceGrid.Cell.SOLID;
        }
        for (BlockOp op : ops) {
            if (op.state() < byState.length) {
                cells.put(Palette.blockKey(op.x(), op.y(), op.z()), byState[op.state()]);
            }
        }
    }

    @Override
    public RaceGrid.Cell at(int x, int y, int z) {
        return cells.getOrDefault(Palette.blockKey(x, y, z), RaceGrid.Cell.AIR);
    }
}
