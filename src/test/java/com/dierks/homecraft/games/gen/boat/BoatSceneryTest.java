package com.dierks.homecraft.games.gen.boat;

import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Palette;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.trial.RaceStand;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mountain round the Mountain Run (Course Variety §2.5 "Scenery", §1.1.2, V1, V7, V9): moss
 * terraces at the outer ring's level with spruce cliffs under them, a stepped moss cone under the
 * stand, trees with real persistent leaves at vanilla's own distance; all of it two columns from the
 * track, two inside the half, and under the scenery cap.
 */
class BoatSceneryTest {

    @Test
    void sceneryIsMossLogsAndLeavesKeptFromTheTrackInsideTheHalfAndUnderTheCap() {
        for (BoatPlanner.Level level : BoatPlanner.Level.values()) {
            for (MountainRuns.Run run : MountainRuns.of(level).subList(0, 15)) {
                TrackRaster r = run.raster();
                Plan p = run.plan();
                for (BlockOp op : p.ops()) {
                    String b = p.blockOf(op);
                    int x = op.x() - r.half.minX();
                    int z = op.z() - r.half.minZ();
                    boolean scenery = b.equals(Palette.MOSS) || b.endsWith("_log[axis=y]") || Palette.isLeaves(b);
                    if (!scenery) {
                        continue;
                    }
                    assertTrue(op.y() <= r.top, level + ": scenery under the cap (the stand's floor less one)");
                    if (BoatScenery.far(r, x, z)) {
                        continue; // two columns or more from the track, inside the inset
                    }
                    // near the track: a trunk or island (an obstacle), a wall column's cave portal or
                    // canopy, or a canopy over the track: 5 or more over the ice beside it
                    int hi = Integer.MIN_VALUE;
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if (r.drive(x + dx, z + dz)) {
                                hi = Math.max(hi, r.h[x + dx][z + dz]);
                            }
                        }
                    }
                    boolean obstacle = r.obstacle[x][z];
                    boolean high = op.y() >= hi + DownhillValidator.ROOF;
                    boolean portal = b.equals(Palette.MOSS) && r.beside(x, z) && op.y() >= hi + 3;
                    assertTrue(obstacle || high || portal, level + " day " + run.day() + ": " + b + " at " + op
                            + " is too close to the track");
                }
            }
        }
    }

    @Test
    void everyLeafIsPersistentDryAndAtVanillasOwnDistance() {
        int leaves = 0;
        for (BoatPlanner.Level level : BoatPlanner.Level.values()) {
            for (MountainRuns.Run run : MountainRuns.of(level).subList(0, 10)) {
                Plan p = run.plan();
                assertEquals(java.util.List.of(), Palette.leafProblems(p.palette(), p.ops()),
                        level + ": the game would never change a leaf");
                assertEquals(java.util.List.of(), Palette.stateProblems(p.palette()), level + ": every state is kept");
                for (String b : p.palette()) {
                    if (Palette.isLeaves(b)) {
                        leaves++;
                        assertTrue(b.contains("persistent=true") && b.contains("waterlogged=false"),
                                "persistent, dry leaves: " + b);
                    }
                }
            }
        }
        assertTrue(leaves > 0, "there are leaves");
    }

    @Test
    void terracesSitAtTheOuterRingsLevelWithASpruceCliffUpToThem() {
        for (BoatPlanner.Level level : BoatPlanner.Level.values()) {
            for (MountainRuns.Run run : MountainRuns.of(level).subList(0, 5)) {
                TrackRaster r = run.raster();
                int[][] terrace = BoatScenery.terraces(r);
                int moss = 0;
                int cliffs = 0;
                for (int x = 0; x < r.sx; x++) {
                    for (int z = 0; z < r.sz; z++) {
                        if (terrace[x][z] != TrackRaster.NONE && BoatScenery.far(r, x, z)
                                && Palette.MOSS.equals(run.at(x, terrace[x][z], z))) {
                            moss++;
                        }
                        if (!r.beside(x, z) || r.obstacle[x][z]) {
                            continue;
                        }
                        int hi = Integer.MIN_VALUE;
                        int t = TrackRaster.NONE;
                        for (int dx = -1; dx <= 1; dx++) {
                            for (int dz = -1; dz <= 1; dz++) {
                                if (r.drive(x + dx, z + dz)) {
                                    hi = Math.max(hi, r.h[x + dx][z + dz]);
                                }
                                if (r.inside(x + dx, z + dz)) {
                                    t = Math.max(t, terrace[x + dx][z + dz]);
                                }
                            }
                        }
                        if (t != TrackRaster.NONE && t > hi + 1) {
                            cliffs++;
                            for (int y = hi; y <= t; y++) {
                                String b = run.at(x, y, z);
                                assertTrue(b != null, level + ": the cliff reaches its terrace at " + x + " " + z);
                            }
                        }
                    }
                }
                assertTrue(moss > 500, level + ": moss terraces between the rings: " + moss);
                assertTrue(cliffs > 0, level + ": the lower rings' outer walls are cliffs: " + cliffs);
            }
        }
    }

    @Test
    void aSteppedMossConeRisesUnderTheStand() {
        for (BoatPlanner.Level level : BoatPlanner.Level.values()) {
            MountainRuns.Run run = MountainRuns.of(level).get(0);
            TrackRaster r = run.raster();
            for (int x = r.standX - 3; x <= r.standX + 3; x++) {
                for (int z = r.standZ - 3; z <= r.standZ + 3; z++) {
                    assertEquals(Palette.MOSS, run.at(x, r.top, z), level + ": the cone's top is under the platform");
                    assertEquals(RaceStand.FLOOR, run.at(x, r.top + 1, z), "and the platform on it");
                }
            }
            assertEquals(Palette.MOSS, run.at(r.standX + 4, r.top - 1, r.standZ), "one block in per layer");
            assertEquals(Palette.MOSS, run.at(r.standX - 5, r.top - 2, r.standZ + 1), "and again");
        }
    }

    @Test
    void treesHaveLogTrunksOfThreeWoodsAndAreCounted() {
        java.util.Set<String> woods = new java.util.HashSet<>();
        for (BoatPlanner.Level level : BoatPlanner.Level.values()) {
            for (MountainRuns.Run run : MountainRuns.of(level).subList(0, 10)) {
                int trees = run.made().trees;
                assertTrue(trees >= 5 && trees <= BoatScenery.MAX_TREES + 4, level + ": trees on the mountain: " + trees);
                for (Map.Entry<Long, String> e : run.blocks().entrySet()) {
                    if (e.getValue().endsWith("_log[axis=y]")) {
                        woods.add(Palette.id(e.getValue()));
                    }
                }
            }
        }
        assertEquals(java.util.Set.of("minecraft:oak_log", "minecraft:birch_log", "minecraft:cherry_log"), woods,
                "oak, birch and cherry");
    }
}
