package com.dierks.homecraft.games.gen.boat;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Palette;
import com.dierks.homecraft.games.gen.api.PlannedTrial;
import com.dierks.homecraft.games.trial.Course;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Mountain Run on whole blocks (Course Variety §2.5-2.8, §2.10): checkpoints where the proof
 * allows and as often as the track does, the walls' colour language, keep-clear boxes over every run
 * and zone under the stand, walls 2 over the ice and 2 over the lip round every landing.
 */
class TrackRasterTest {

    private static Course course(MountainRuns.Run run) {
        return ((PlannedTrial) run.plan().course()).course();
    }

    @Test
    void everyCheckpointIsFlatOffSandClearOfDropsAndZonesAndSpansItsLane() {
        for (BoatPlanner.Level level : BoatPlanner.Level.values()) {
            for (MountainRuns.Run run : MountainRuns.of(level)) {
                TrackRaster r = run.raster();
                Box half = r.half;
                for (Course.Mark m : course(run).checkpoints()) {
                    double px = m.x() - half.minX();
                    double pz = m.z() - half.minZ();
                    int cx = (int) Math.floor(px);
                    int cz = (int) Math.floor(pz);
                    assertTrue(r.drive(cx, cz), "on the track");
                    int ice = r.h[cx][cz];
                    assertEquals(ice + 1, m.y(), 1e-9, "at the ice's surface");
                    boolean arc = r.segAt[cx][cz].arc;
                    double want = level.width() / 2.0 + (arc ? BoatPlanner.ARC_SPOT : 0.5);
                    boolean pit = Math.abs(m.radius() - (level.pitWidth() / 2.0 + 0.5)) < 1e-9;
                    assertTrue(Math.abs(m.radius() - want) < 1e-9 || pit, "its radius spans its lane: " + m.radius());
                    for (int x = cx - 8; x <= cx + 8; x++) {
                        for (int z = cz - 8; z <= cz + 8; z++) {
                            if (!r.drive(x, z)) {
                                continue;
                            }
                            double d = Math.hypot(x + 0.5 - px, z + 0.5 - pz);
                            if (d <= m.radius()) {
                                assertEquals(ice, r.h[x][z], "a checkpoint is on flat track");
                                assertTrue(r.mat[x][z] != TrackRaster.SAND, "and on ice");
                                for (int y = ice + 1; y <= r.top; y++) {
                                    assertEquals(null, run.at(x, y, z), "and in the open");
                                }
                            }
                            if (d < DownhillValidator.DROP_CLEAR) {
                                assertEquals(0, r.lipDrop[x][z], "at least 3 from a drop's edge");
                                assertEquals(TrackRaster.NONE, r.zoneLip[x][z], "and from every flight zone");
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void targetsAreAtMostSixtyApartWithAtMostOneDropBetweenAndAtLeastOneBetweenTwoDrops() {
        for (BoatPlanner.Level level : BoatPlanner.Level.values()) {
            for (MountainRuns.Run run : MountainRuns.of(level)) {
                Course c = course(run);
                double x = c.start().x();
                double z = c.start().z();
                double y = c.start().y();
                for (Course.Mark m : c.targets()) {
                    assertTrue(Math.hypot(m.x() - x, m.z() - z) <= DownhillValidator.MAX_LEG,
                            level + " day " + run.day() + ": two targets at most 60 apart across the ground");
                    assertTrue(y - m.y() <= level.proof().maxDrop(), "at most one drop a leg (and never a 3)");
                    x = m.x();
                    z = m.z();
                    y = m.y();
                }
                assertEquals(run.raster().profile.lips.size(), BoatPlannerTest.drops(run.plan()),
                        "every drop is its own leg: the legs going down count the drops (Race Night's hype line)");
            }
        }
    }

    @Test
    void theChainPutsACheckpointEveryThirtyTwoWhereverOneCanGo() {
        for (BoatPlanner.Level level : BoatPlanner.Level.values()) {
            for (MountainRuns.Run run : MountainRuns.of(level).subList(0, 10)) {
                TrackRaster r = run.raster();
                List<TrackRaster.Spot> spots = r.spots();
                List<double[]> blocked = TrackPieces.blocked(run.made().pieces.list);
                List<TrackRaster.Spot> chain = BoatPlanner.chain(r, spots, blocked);
                assertNotNull(chain, "the chain the planner laid");
                assertEquals(course(run).checkpoints().size(), chain.size(), "is the course's checkpoints");
                for (int i = 0; i + 1 < chain.size(); i++) {
                    TrackRaster.Spot a = chain.get(i);
                    TrackRaster.Spot b = chain.get(i + 1);
                    if (r.profile.lipsBetween(a.s(), b.s()) > 0 || b.s() - a.s() <= BoatPlanner.SPACING) {
                        continue;
                    }
                    for (TrackRaster.Spot m : spots) {
                        boolean splits = m.s() - a.s() <= BoatPlanner.SPACING && b.s() - m.s() <= BoatPlanner.SPACING
                                && m.s() - a.s() > a.r() + m.r() + 1 && b.s() - m.s() > b.r() + m.r() + 1;
                        boolean free = true;
                        for (double[] bl : blocked) {
                            free &= !(m.s() + m.r() + 1.5 > bl[0] && m.s() - m.r() - 1.5 < bl[1]);
                        }
                        assertTrue(!splits || !free, level + ": a leg of " + (b.s() - a.s())
                                + " with a place in its middle would have had a checkpoint there");
                    }
                }
            }
        }
    }

    @Test
    void theWallsSpeakTheColourLanguage() {
        for (BoatPlanner.Level level : BoatPlanner.Level.values()) {
            for (MountainRuns.Run run : MountainRuns.of(level).subList(0, 10)) {
                TrackRaster r = run.raster();
                int lime = 0;
                int yellow = 0;
                int lightBlue = 0;
                int gold = 0;
                int arrows = 0;
                for (String b : run.blocks().values()) {
                    lime += b.equals(Palette.START) ? 1 : 0;
                    yellow += b.equals(Palette.LIP_CAP) ? 1 : 0;
                    lightBlue += b.equals(Palette.CHECKPOINT) ? 1 : 0;
                    gold += b.equals(Palette.FINISH) ? 1 : 0;
                    arrows += b.startsWith(Palette.ARROW) ? 1 : 0;
                }
                assertTrue(lime >= 12, level + ": the pit's lime back wall and grid rows: " + lime);
                assertTrue(yellow >= 2 * r.profile.lips.size(), level + ": yellow caps in both walls at every drop");
                assertTrue(lightBlue >= course(run).checkpoints().size(), level + ": light blue beside the checkpoints");
                assertTrue(gold >= 3, level + ": gold posts at the finish and in the end wall: " + gold);
                assertTrue(arrows >= 8, level + ": arrows along the way: " + arrows);
            }
        }
    }

    @Test
    void wallsStandTwoOverTheIceAndTwoOverTheLipRoundEveryLanding() {
        for (BoatPlanner.Level level : BoatPlanner.Level.values()) {
            for (MountainRuns.Run run : MountainRuns.of(level).subList(0, 5)) {
                TrackRaster r = run.raster();
                for (int x = 0; x < r.sx; x++) {
                    for (int z = 0; z < r.sz; z++) {
                        if (!r.beside(x, z)) {
                            continue;
                        }
                        int lo = Integer.MAX_VALUE;
                        int need = Integer.MIN_VALUE;
                        for (int dx = -1; dx <= 1; dx++) {
                            for (int dz = -1; dz <= 1; dz++) {
                                if (r.drive(x + dx, z + dz)) {
                                    lo = Math.min(lo, r.h[x + dx][z + dz]);
                                    need = Math.max(need, r.h[x + dx][z + dz] + 2);
                                    if (r.zoneLip[x + dx][z + dz] != TrackRaster.NONE) {
                                        need = Math.max(need, r.zoneLip[x + dx][z + dz] + 2);
                                    }
                                }
                            }
                        }
                        for (int y = lo; y <= Math.min(need, r.top); y++) {
                            String b = run.at(x, y, z);
                            assertTrue(b != null && !Palette.isLeaves(b), level + ": the wall at " + x + " " + z
                                    + " is solid at " + y);
                        }
                    }
                }
            }
        }
    }

    @Test
    void keepClearBoxesCoverEveryRunAndZoneAndStayUnderTheStand() {
        for (BoatPlanner.Level level : BoatPlanner.Level.values()) {
            for (MountainRuns.Run run : MountainRuns.of(level).subList(0, 10)) {
                TrackRaster r = run.raster();
                List<Box> boxes = run.plan().keepClear();
                assertTrue(boxes.size() <= DownhillValidator.MAX_BOXES, "at most 64 boxes");
                for (Box b : boxes) {
                    assertTrue(b.maxY() < r.top + 1, "under the stand's floor");
                    assertTrue(r.half.contains(b), "inside the half");
                }
                for (int x = 0; x < r.sx; x += 3) {
                    for (int z = 0; z < r.sz; z += 3) {
                        if (!r.drive(x, z)) {
                            continue;
                        }
                        int wx = r.half.minX() + x;
                        int wz = r.half.minZ() + z;
                        int y = r.h[x][z] + 1;
                        boolean covered = false;
                        for (Box b : boxes) {
                            covered |= b.contains(wx, y, wz) && b.contains(wx, Math.min(r.top, y + 3), wz);
                        }
                        assertTrue(covered, level + ": the headroom over " + x + " " + z + " is in a box");
                    }
                }
            }
        }
    }
}
