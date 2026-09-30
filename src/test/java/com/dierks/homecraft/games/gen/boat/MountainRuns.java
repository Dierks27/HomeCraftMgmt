package com.dierks.homecraft.games.gen.boat;

import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.GenRandom;
import com.dierks.homecraft.games.gen.api.GenSeed;
import com.dierks.homecraft.games.gen.api.Palette;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlanInput;
import com.dierks.homecraft.games.gen.api.Slots;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Proven Mountain Runs for the piece, raster and scenery tests: the first proven try of 60 seeds a
 * tier (every piece asked for), made once and shared, each with its raster made again (the planner's
 * own, block for block) and its blocks by position.
 */
final class MountainRuns {

    /** One proven run: what the planner made, its raster, and its blocks by {@link Palette#blockKey}. */
    record Run(BoatPlanner.Level level, int day, BoatPlanner.Made made, TrackRaster raster, Map<Long, String> blocks) {

        Plan plan() {
            return made.plan;
        }

        /** The block at half column (x, z), world height y, or {@code null} for air. */
        String at(int x, int y, int z) {
            return blocks.get(Palette.blockKey(raster.half.minX() + x, y, raster.half.minZ() + z));
        }
    }

    static final int DAYS = 60;
    private static final Map<BoatPlanner.Level, List<Run>> RUNS = new EnumMap<>(BoatPlanner.Level.class);

    private MountainRuns() {
    }

    static synchronized List<Run> of(BoatPlanner.Level level) {
        return RUNS.computeIfAbsent(level, MountainRuns::make);
    }

    private static List<Run> make(BoatPlanner.Level level) {
        List<Run> out = new ArrayList<>();
        for (int day = 0; day < DAYS; day++) {
            long seed = GenSeed.seed(0x5EC12E7L, 60_000 + day, Slots.ICE_BOAT.id(), 0);
            PlanInput in = new PlanInput(Slots.ICE_BOAT, Slots.ICE_BOAT.half('A'), 'A', 20725, 0, seed, level.id(), 6, 0,
                    null);
            GenRandom root = new GenRandom(seed);
            for (int t = 0; t < 10; t++) {
                BoatPlanner.Made m = BoatPlanner.attempt(in, level, root, t, BoatPlanner.Richness.FULL);
                if (m != null && DownhillValidator.problems(m.plan, level.id()).isEmpty()) {
                    TrackRaster r = new TrackRaster(in.half(), m.path, m.profile, m.pieces, level);
                    Map<Long, String> blocks = new HashMap<>();
                    for (BlockOp op : m.plan.ops()) {
                        blocks.put(Palette.blockKey(op.x(), op.y(), op.z()), m.plan.blockOf(op));
                    }
                    out.add(new Run(level, day, m, r, blocks));
                    break;
                }
            }
        }
        return out;
    }
}
