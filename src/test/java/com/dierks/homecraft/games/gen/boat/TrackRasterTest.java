package com.dierks.homecraft.games.gen.boat;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Palette;
import com.dierks.homecraft.games.gen.api.PlannedTrial;
import com.dierks.homecraft.games.trial.Course;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void everyCheckpointIsFlatOnIceWhereAResetLandsClearOfDropsAndZonesAndSpansItsLane() {
        int sandyBends = 0;
        for (BoatPlanner.Level level : BoatPlanner.Level.values()) {
            for (MountainRuns.Run run : MountainRuns.of(level)) {
                TrackRaster r = run.raster();
                Box half = r.half;
                List<TrackRaster.Spot> spots = run.made().spots;
                for (int i = 0; i < spots.size(); i++) {
                    Course.Mark m = course(run).checkpoints().get(i);
                    double px = m.x() - half.minX();
                    double pz = m.z() - half.minZ();
                    int cx = (int) Math.floor(px);
                    int cz = (int) Math.floor(pz);
                    assertTrue(r.drive(cx, cz), "on the track");
                    int ice = r.h[cx][cz];
                    assertEquals(ice + 1, m.y(), 1e-9, "at the ice's surface");
                    TrackPath.Seg g = run.made().path.segAt(spots.get(i).s());
                    int sand = g.arc ? Math.max(run.made().pieces.runoff[g.index], run.made().pieces.kerb[g.index]) : 0;
                    assertEquals(sand > 0, spots.get(i).sandy(), "a checkpoint on a bend with sand says so");
                    sandyBends += sand > 0 ? 1 : 0;
                    double want = level.width() / 2.0 + (g.arc ? BoatPlanner.ARC_SPOT + sand : 0.5);
                    boolean pit = Math.abs(m.radius() - (level.pitWidth() / 2.0 + 0.5)) < 1e-9;
                    assertTrue(Math.abs(m.radius() - want) < 1e-9 || pit, "its radius spans its lane and a bend's sand: "
                            + m.radius());
                    for (int x = cx - 8; x <= cx + 8; x++) {
                        for (int z = cz - 8; z <= cz + 8; z++) {
                            if (!r.drive(x, z)) {
                                continue;
                            }
                            double d = Math.hypot(x + 0.5 - px, z + 0.5 - pz);
                            if (d <= m.radius()) {
                                assertEquals(ice, r.h[x][z], "a checkpoint is on flat track");
                                assertTrue(d > DownhillValidator.RESET_ROOM || r.mat[x][z] != TrackRaster.SAND,
                                        "and on ice where a reset puts the boat down (sand only at a bend's rim)");
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
        // review CV gate: on hard every bend has sand, and a checkpoint on one (its middle on ice) is what
        // keeps a leg past a piece within 60 along the track, and a reset facing on
        assertTrue(sandyBends > 0, "some checkpoints stand on a sandy bend's ice: " + sandyBends);
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
                        // a place on plain track (a sandy bend's is only for the rules) that keeps both resets facing on
                        boolean splits = m.s() - a.s() <= BoatPlanner.SPACING && b.s() - m.s() <= BoatPlanner.SPACING
                                && m.s() - a.s() > a.r() + m.r() + 1 && b.s() - m.s() > b.r() + m.r() + 1
                                && !m.sandy() && r.faces(a, m) && r.faces(m, b);
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

    /**
     * Review CV gate (major): a reset ({@code TimeTrials.backTo}, "Back to checkpoint", a Bedrock sneak,
     * a stuck boat) sends a kid back to the last checkpoint facing the next target. Hard legs ran up to
     * 178 blocks past a sand pit, a split and a cave, and a reset could face 146 degrees off the lane.
     * Every leg with no drop is at most 60 along the track, and every reset faces within 60 degrees of
     * the lane there, by the very yaw a race uses.
     */
    @Test
    void aLegWithNoDropIsAtMostSixtyAlongTheTrackAndEveryResetFacesOnDownIt() {
        for (BoatPlanner.Level level : BoatPlanner.Level.values()) {
            for (MountainRuns.Run run : MountainRuns.of(level)) {
                String why = BoatPlannerTest.legProblem(run.made());
                assertEquals(null, why, level + " day " + run.day() + ": " + why);
            }
        }
    }

    @Test
    void theGatesHardSeedHasNoLongLegAndNoResetFacingBackUpTheTrack() throws Exception {
        // the review's case: hard, half A at (4480, 160, 4352), seed 39595 had checkpoint 11 (s 494) -> 12
        // (s 665) 171 along the track (29 across), past the sand pit, a split and the cave, and a reset at 11
        // facing 146 degrees off the lane
        BoatPlanner.Made m = BoatPlanner.made(BoatPlannerTest.inputIn(BoatPlannerTest.BOX_A, 'A', 39595, "hard"));
        assertEquals(null, BoatPlannerTest.legProblem(m), "the gate's seed keeps the rules");
        double prev = TrackProfile.START;
        for (TrackRaster.Spot sp : m.spots) {
            assertTrue(sp.s() - prev <= BoatPlanner.FLAT_LEG || m.profile.lipsBetween(prev, sp.s()) > 0,
                    "no leg with no drop longer than 60 along the track, to s " + Math.round(sp.s()));
            prev = sp.s();
        }
        assertTrue(m.pieces.list.size() >= 3, "and the run keeps its pieces: " + m.pieces.list.size());
    }

    @Test
    void aFinalDropFarUpTheMountainNeverSharesTheFinishsLeg() {
        // Review CV gate (minor): the race copy's "Final drop!" title is read off the marks (BoatHype.finalDrop:
        // the finish is the next target after the last drop), so it must stand exactly where the FINAL DROP!
        // sign does. On easy and hard the Final Drop is up the mountain (its sign says HOP! or BIG DROP!), so
        // the chain never lets its leg run on to the finish, even when nothing else would refuse it: here a
        // place 5 before the last drop and 40 straight back from the finish along the lane there.
        int checked = 0;
        for (BoatPlanner.Level level : List.of(BoatPlanner.Level.EASY, BoatPlanner.Level.HARD)) {
            for (MountainRuns.Run run : MountainRuns.of(level).subList(0, 5)) {
                TrackRaster r = run.raster();
                TrackProfile p = r.profile;
                assertFalse(p.finalInFront(), level + " fixture: the Final Drop is up the mountain");
                double[] fp = r.path.at(p.finish);
                TrackRaster.Spot finish = new TrackRaster.Spot(p.finish, fp[0], fp[1], r.level.finishRadius(), p.bottom());
                double s = p.last().s() - 5;
                double[] t = r.path.tangent(s);
                TrackRaster.Spot a = new TrackRaster.Spot(s, fp[0] - 40 * t[0], fp[1] - 40 * t[1], 3, p.level(s), false,
                        t[0], t[1]);
                assertEquals(1, p.lipsBetween(a.s(), finish.s()), "fixture: the last drop is in the leg");
                assertTrue(r.faces(a, finish), "fixture: a reset there faces the finish dead ahead");
                assertFalse(r.leg(a, finish, false, true), level + " day " + run.day()
                        + ": a Final Drop far from the finish has a checkpoint after it, so no \"Final drop!\" title");
                checked++;
            }
        }
        assertEquals(10, checked, "five runs a tier");
    }

    @Test
    void aLegRoundABendLongerThanSixtyAlongIsRefusedThoughItIsShortAcrossAndFacesOn() {
        // Review CV gate (major): spacing along the track was only a cost, so a leg could run on past a bend
        // wherever the ground distance allowed. Two real places with no drop between, more than 60 apart
        // along the track but under 60 across and a reset at the first facing the second within 60 degrees
        // of its lane: only the rule along the track refuses the leg.
        int found = 0;
        for (BoatPlanner.Level level : BoatPlanner.Level.values()) {
            for (MountainRuns.Run run : MountainRuns.of(level).subList(0, 20)) {
                TrackRaster r = run.raster();
                List<TrackRaster.Spot> spots = r.spots();
                for (TrackRaster.Spot a : spots) {
                    TrackRaster.Spot b = null;
                    for (TrackRaster.Spot c : spots) {
                        if (b == null && c.s() - a.s() > BoatPlanner.FLAT_LEG + 1) {
                            b = c;
                        }
                    }
                    if (b == null || r.profile.lipsBetween(a.s(), b.s()) > 0) {
                        continue;
                    }
                    double across = Math.hypot(b.x() - a.x(), b.z() - a.z());
                    if (across > BoatPlanner.LEG_MAX - 1 || across <= a.r() + b.r() + 1 || !r.faces(a, b)) {
                        continue;
                    }
                    assertFalse(r.leg(a, b, false, false), level + " day " + run.day() + ": a leg of "
                            + Math.round(b.s() - a.s()) + " along the track (" + Math.round(across)
                            + " across) with no drop");
                    found++;
                    break;
                }
            }
        }
        assertTrue(found >= 5, "fixture: legs round a bend, short across and facing on (" + found + ")");
    }

    @Test
    void aLegWhoseResetWouldFaceOffItsLaneIsRefusedThoughItIsShortAlongAndAcross() {
        // Review CV gate (major): a reset turns the boat toward the next target (TimeTrials.backTo), and a hard
        // seed's faced 146 degrees off its lane. Two real places with no drop between, under 60 apart along the
        // track and across, the line from the first to the second more than 60 degrees off the lane at the
        // first (round a bend): only the facing rule refuses the leg.
        int found = 0;
        for (BoatPlanner.Level level : BoatPlanner.Level.values()) {
            for (MountainRuns.Run run : MountainRuns.of(level).subList(0, 20)) {
                TrackRaster r = run.raster();
                List<TrackRaster.Spot> spots = r.spots();
                search:
                for (TrackRaster.Spot a : spots) {
                    for (TrackRaster.Spot b : spots) {
                        double along = b.s() - a.s();
                        double across = Math.hypot(b.x() - a.x(), b.z() - a.z());
                        if (along <= 0 || along > BoatPlanner.FLAT_LEG - 1 || r.profile.lipsBetween(a.s(), b.s()) > 0
                                || across > BoatPlanner.LEG_MAX - 1 || across <= a.r() + b.r() + 1) {
                            continue;
                        }
                        double[] t = r.path.tangent(a.s());
                        double off = Math.toDegrees(Math.acos(((b.x() - a.x()) * t[0] + (b.z() - a.z()) * t[1]) / across));
                        if (off <= BoatPlanner.FACING + 1) {
                            continue;
                        }
                        assertFalse(r.leg(a, b, false, false), level + " day " + run.day() + ": a reset at s "
                                + Math.round(a.s()) + " facing " + Math.round(off) + " degrees off its lane");
                        found++;
                        break search;
                    }
                }
            }
        }
        assertTrue(found >= 5, "fixture: legs round a bend whose reset faces off the lane (" + found + ")");
    }

    @Test
    void theChainRefusesALongFlatLegThatTheGroundDistanceAloneWouldAllow() {
        // a stretch of 70 with no drop and no place for a checkpoint (as a piece filling a straight and its
        // bends would leave), the places either side of it under 60 apart across the ground (the spiral
        // folds back on itself): the chain used to jump it in one leg, and now can't
        int found = 0;
        for (MountainRuns.Run run : MountainRuns.of(BoatPlanner.Level.HARD)) {
            TrackRaster r = run.raster();
            TrackProfile p = r.profile;
            List<TrackRaster.Spot> spots = r.spots();
            for (TrackRaster.Spot a : spots) {
                double from = a.s() + a.r() + 2;
                double to = from + 70;
                if (a.s() < TrackProfile.START + 60 || to > p.finish - 20 || p.lipsBetween(a.s() - 60, to + 60) > 0) {
                    continue;
                }
                TrackRaster.Spot b = null;
                for (TrackRaster.Spot c : spots) {
                    if (b == null && c.s() - c.r() - 1.5 >= to) {
                        b = c;
                    }
                }
                if (b == null || Math.hypot(b.x() - a.x(), b.z() - a.z()) > BoatPlanner.LEG_MAX - 1) {
                    continue;
                }
                List<double[]> blocked = new ArrayList<>(TrackPieces.blocked(run.made().pieces.list));
                blocked.add(new double[]{from, to});
                assertEquals(null, BoatPlanner.chain(r, spots, blocked), "day " + run.day() + ": 70 blocks from "
                        + Math.round(from) + " with no checkpoint and no drop leave no chain");
                found++;
                break;
            }
            if (found >= 5) {
                break;
            }
        }
        assertTrue(found >= 1, "fixture: a flat stretch to block (" + found + ")");
    }

    @Test
    void aBendHasSignSpotsOnItsWallTopOffTheTrackFacingTheBoatsComing() {
        // Review B2: a piece just past a bend has its sign window on the bend, so a bend has sign spots.
        for (BoatPlanner.Level level : BoatPlanner.Level.values()) {
            int bends = 0;
            int spots = 0;
            for (MountainRuns.Run run : MountainRuns.of(level).subList(0, 10)) {
                BoatPlanner.Made m = run.made();
                TrackRaster r = new TrackRaster(run.raster().half, m.path, m.profile, m.pieces, level);
                r.terrace = BoatScenery.terraces(r);
                r.blocks();
                r.walls();
                for (TrackPath.Seg g : m.path.segs) {
                    if (!g.arc || g.s1() > m.profile.finish) {
                        continue;
                    }
                    for (double s = g.s0 + 1; s <= g.s1() - 1; s += 1) {
                        bends++;
                        int[] spot = r.signSpot(s);
                        if (spot == null) {
                            continue;
                        }
                        spots++;
                        String at = level + " day " + run.day() + ": the spot " + (int) Math.round(s - g.s0)
                                + " into the bend at s " + Math.round(g.s0);
                        assertFalse(r.drive(spot[0], spot[2]), at + " is never over the track");
                        assertTrue(r.beside(spot[0], spot[2]), at + " is on the wall beside it");
                        assertTrue(r.topOf(spot[0], spot[2]) == spot[1] - 1, at + " stands on the wall top");
                        assertTrue(spot[1] <= r.top, at + " is under the stand's floor");
                        double[] t = m.path.tangent(s);
                        int want = Math.floorMod((int) Math.round(TrackRaster.yaw(-t[0], -t[1]) / 22.5), 16);
                        assertEquals(want, spot[3], at + " faces back along the bend, toward the boats coming");
                    }
                }
            }
            assertTrue(spots >= bends * 0.9, level + ": nearly every place on a bend has a wall top for a sign: "
                    + spots + " of " + bends);
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
