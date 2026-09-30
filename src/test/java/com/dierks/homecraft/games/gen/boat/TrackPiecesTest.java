package com.dierks.homecraft.games.gen.boat;

import com.dierks.homecraft.games.gen.api.Palette;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Mountain Run's pieces (Course Variety §2.4, §2.5) as the blocks have them: sand pits with an
 * ice way P wide round both sides, splits round a moss island with a spruce rim, forest trunks P
 * apart under a leafy roof, caves roofed 5 over the ice, boost strips in the middle, sand on the
 * tight bends; and none where a piece may not go.
 */
class TrackPiecesTest {

    /** The lane's cells across {@code s} (drive cells whose nearest centreline point is there), by inward offset. */
    private static TreeMap<Double, int[]> across(TrackRaster r, double s) {
        TreeMap<Double, int[]> out = new TreeMap<>();
        for (int x = 0; x < r.sx; x++) {
            for (int z = 0; z < r.sz; z++) {
                if ((r.drive(x, z) || r.obstacle[x][z]) && Math.abs(r.sAt[x][z] - s) < 1e-6) {
                    out.put(r.vAt[x][z], new int[]{x, z});
                }
            }
        }
        return out;
    }

    /** The s of the columns' middles along straight {@code st} between {@code a} and {@code b}. */
    private static List<Double> along(TrackPath.Seg st, double a, double b) {
        List<Double> out = new ArrayList<>();
        for (double s = TrackProfile.spotStart(st); s < b; s += 1) {
            if (s > a) {
                out.add(s);
            }
        }
        return out;
    }

    @Test
    void aSandPitLeavesAnIceWayAtLeastPWideRoundBothSides() {
        int pits = 0;
        for (BoatPlanner.Level level : BoatPlanner.Level.values()) {
            int p = level.proof().narrowest();
            for (MountainRuns.Run run : MountainRuns.of(level)) {
                TrackRaster r = run.raster();
                for (TrackPieces.Piece pc : run.made().pieces.list) {
                    if (pc.kind != TrackPieces.Kind.SAND_PIT) {
                        continue;
                    }
                    pits++;
                    assertEquals(level.sandPit(), pc.hi - pc.lo + 1, level + ": the pit is 4 wide (3 on hard)");
                    for (double s : along(run.made().path.straight(pc.leg), pc.a1, pc.a2)) {
                        int outer = 0;
                        int inner = 0;
                        int sand = 0;
                        for (var e : across(r, s).entrySet()) {
                            int[] c = e.getValue();
                            if (r.mat[c[0]][c[1]] == TrackRaster.SAND) {
                                sand++;
                            } else if (e.getKey() < pc.lo) {
                                outer++;
                            } else {
                                inner++;
                            }
                        }
                        assertEquals(level.sandPit(), sand, level + " day " + run.day() + ": the pit's sand across " + s);
                        assertTrue(outer >= p && inner >= p, level + " day " + run.day() + ": both ice ways at least "
                                + p + " wide at " + s + " (" + outer + " + " + inner + ")");
                    }
                }
            }
        }
        assertTrue(pits > 0, "some runs have a sand pit");
    }

    @Test
    void aSplitHasTwoBranchesPWideRoundAMossIslandWithASpruceRim() {
        int splits = 0;
        for (BoatPlanner.Level level : BoatPlanner.Level.values()) {
            int p = level.proof().narrowest();
            for (MountainRuns.Run run : MountainRuns.of(level)) {
                TrackRaster r = run.raster();
                for (TrackPieces.Piece pc : run.made().pieces.list) {
                    if (pc.kind != TrackPieces.Kind.SPLIT) {
                        continue;
                    }
                    splits++;
                    int ice = r.profile.level((pc.s1 + pc.s2) / 2);
                    for (double s : along(run.made().path.straight(pc.leg), pc.a1, pc.a2)) {
                        int left = 0;
                        int right = 0;
                        int island = 0;
                        for (var e : across(r, s).entrySet()) {
                            int[] c = e.getValue();
                            if (r.obstacle[c[0]][c[1]]) {
                                island++;
                                String low = run.at(c[0], ice, c[1]);
                                String rimTop = run.at(c[0], ice + 2, c[1]);
                                assertTrue(Palette.TRACK_WALL.equals(low) || Palette.MOSS.equals(low),
                                        "the island is a spruce rim round moss: " + low);
                                assertTrue(rimTop != null, "its rim and moss reach the ice + 2");
                            } else if (e.getKey() < pc.lo) {
                                left++;
                            } else {
                                right++;
                            }
                        }
                        assertEquals(3, island, level + ": the island is 3 wide at " + s);
                        assertTrue(left >= p && right >= p, level + " day " + run.day() + ": both branches at least "
                                + p + " wide (" + left + " + " + right + ")");
                    }
                }
            }
        }
        assertTrue(splits > 0, "some runs split round an island");
    }

    @Test
    void forestTrunksStandPFromTheWallsAndEachOtherUnderALeafyRoof() {
        int forests = 0;
        for (BoatPlanner.Level level : BoatPlanner.Level.values()) {
            int p = level.proof().narrowest();
            for (MountainRuns.Run run : MountainRuns.of(level)) {
                TrackRaster r = run.raster();
                for (TrackPieces.Piece pc : run.made().pieces.list) {
                    if (pc.kind != TrackPieces.Kind.FOREST) {
                        continue;
                    }
                    forests++;
                    assertTrue(level != BoatPlanner.Level.EASY, "no forest on easy");
                    int ice = r.profile.level((pc.s1 + pc.s2) / 2);
                    assertTrue(ice <= r.h0 + BoatPlanner.FOREST_TOP, "the forest is on the lower rings (ice <= H0 + 6)");
                    assertEquals(level.trunks(), pc.trunks.size(), level + ": its trunks");
                    int leaves = 0;
                    for (double[] t : pc.trunks) {
                        for (double s : along(run.made().path.straight(pc.leg), t[0], t[0] + 2)) {
                            int before = 0;
                            int trunk = 0;
                            int after = 0;
                            for (var e : across(r, s).entrySet()) {
                                int[] c = e.getValue();
                                if (r.obstacle[c[0]][c[1]]) {
                                    trunk++;
                                    for (int y = ice; y <= ice + BoatPlanner.TRUNK_TOP; y++) {
                                        assertTrue(run.at(c[0], y, c[1]).endsWith("_log[axis=y]"),
                                                "a trunk is logs from the ice to 6 over it");
                                    }
                                } else if (trunk == 0) {
                                    before++;
                                } else {
                                    after++;
                                }
                            }
                            assertEquals(2, trunk, "2 x 2 trunks");
                            assertTrue(before >= p && after >= p, level + ": at least " + p + " round a trunk ("
                                    + before + " + " + after + ")");
                        }
                    }
                    for (int x = 0; x < r.sx; x++) {
                        for (int z = 0; z < r.sz; z++) {
                            if (r.drive(x, z) && r.sAt[x][z] > pc.s1 + 2 && r.sAt[x][z] < pc.s2 - 2
                                    && r.segAt[x][z].leg == pc.leg) {
                                for (int y = ice + 1; y <= ice + 4; y++) {
                                    assertEquals(null, run.at(x, y, z), "4 blocks of air over the forest's ice");
                                }
                                String roof = run.at(x, ice + 5, z);
                                leaves += roof != null && Palette.isLeaves(roof) ? 1 : 0;
                            }
                        }
                    }
                    assertTrue(leaves > 20, "a leafy roof 5 over the ice: " + leaves);
                }
            }
        }
        assertTrue(forests > 0, "some runs have a forest");
    }

    @Test
    void aCaveIsRoofedFiveOverItsIceLitAndOutOfEveryFlightZone() {
        int caves = 0;
        for (BoatPlanner.Level level : BoatPlanner.Level.values()) {
            for (MountainRuns.Run run : MountainRuns.of(level)) {
                TrackRaster r = run.raster();
                for (TrackPieces.Piece pc : run.made().pieces.list) {
                    if (pc.kind != TrackPieces.Kind.CAVE) {
                        continue;
                    }
                    caves++;
                    int ice = r.profile.level((pc.s1 + pc.s2) / 2);
                    assertTrue(ice <= r.h0 + BoatPlanner.CAVE_TOP, "its roof under the cap: ice <= H0 + 7");
                    assertTrue(pc.s2 - pc.s1 >= 12 && pc.s2 - pc.s1 <= 20, "12-20 long");
                    for (TrackProfile.Lip l : r.profile.lips) {
                        assertTrue(pc.s2 < l.s() - BoatPlanner.CAVE_LIP || pc.s1 > l.s() + BoatPlanner.CAVE_LIP,
                                "at least 10 from any drop");
                    }
                    int lanterns = 0;
                    for (int x = 0; x < r.sx; x++) {
                        for (int z = 0; z < r.sz; z++) {
                            if (!r.near[x][z] || r.sAt[x][z] <= pc.s1 + 1 || r.sAt[x][z] >= pc.s2 - 1
                                    || r.segAt[x][z].leg != pc.leg || r.segAt[x][z].arc) {
                                continue;
                            }
                            if (r.drive(x, z)) {
                                assertEquals(TrackRaster.NONE, r.zoneLip[x][z], "no cave in a flight zone");
                                assertEquals(Palette.BLUE_GLASS, run.at(x, ice + 5, z), "light-blue glass 5 over the ice");
                                for (int y = ice + 1; y <= ice + 4; y++) {
                                    assertEquals(null, run.at(x, y, z), "4 blocks of air under it");
                                }
                            } else if (r.beside(x, z)) {
                                lanterns += Palette.SEA_LANTERN.equals(run.at(x, ice + 1, z)) ? 1 : 0;
                            }
                        }
                    }
                    assertTrue(lanterns >= 2, "sea lanterns in its walls: " + lanterns);
                }
            }
        }
        assertTrue(caves > 0, "some runs have an ice cave");
    }

    @Test
    void boostStripsAreTheMiddleThreeColumnsOfMediumAndNeverJustBeforeADrop() {
        int boosts = 0;
        for (BoatPlanner.Level level : BoatPlanner.Level.values()) {
            for (MountainRuns.Run run : MountainRuns.of(level)) {
                TrackRaster r = run.raster();
                for (TrackPieces.Piece pc : run.made().pieces.list) {
                    if (pc.kind != TrackPieces.Kind.BOOST) {
                        continue;
                    }
                    boosts++;
                    assertEquals(BoatPlanner.Level.MEDIUM, level, "boost strips are medium's");
                    for (TrackProfile.Lip l : r.profile.lips) {
                        assertTrue(pc.s1 > l.s() || pc.s2 <= l.s() - BoatPlanner.BOOST_LIP, "never within 30 before a drop");
                    }
                    for (double s : along(run.made().path.straight(pc.leg), pc.s1, pc.s2)) {
                        for (var e : across(r, s).entrySet()) {
                            int[] c = e.getValue();
                            boolean blue = r.mat[c[0]][c[1]] == TrackRaster.BLUE;
                            assertEquals(Math.abs(e.getKey()) <= 1, blue, "blue ice in the middle 3 columns only");
                        }
                    }
                }
            }
        }
        assertTrue(boosts > 0, "some medium runs have boost strips");
    }

    @Test
    void noPieceIsOnTheStartsForty_ARunUp_ALandingOrTheFinish() {
        for (BoatPlanner.Level level : BoatPlanner.Level.values()) {
            for (MountainRuns.Run run : MountainRuns.of(level)) {
                TrackProfile prof = run.raster().profile;
                for (TrackPieces.Piece pc : run.made().pieces.list) {
                    assertTrue(pc.s1 >= TrackProfile.START + TrackProfile.CLEAN, "nothing on the 40 after the start");
                    assertTrue(pc.s2 <= prof.finish - level.finishRadius() - 3, "nothing at the finish");
                    for (TrackProfile.Lip l : prof.lips) {
                        assertTrue(pc.s2 <= l.s() - level.runUp() || pc.s1 >= l.s() + level.landing(l.drop()),
                                level + ": a piece keeps off a drop's run-up and landing strip");
                    }
                    TrackPath.Seg st = run.made().path.straight(pc.leg);
                    assertTrue(pc.s1 >= st.s0 && pc.s2 <= st.s1(), "every piece is on one straight");
                }
            }
        }
    }

    @Test
    void tightBendsHaveSandOutsideAndOnMediumAndHardAKerbInside() {
        for (BoatPlanner.Level level : BoatPlanner.Level.values()) {
            for (MountainRuns.Run run : MountainRuns.of(level)) {
                TrackRaster r = run.raster();
                TrackPieces pieces = run.made().pieces;
                for (TrackPath.Seg g : run.made().path.segs) {
                    if (!g.arc) {
                        continue;
                    }
                    boolean tight = g.r < BoatPlanner.SANDY_BEND;
                    assertEquals(tight ? level.runoff() : 0, pieces.runoff[g.index], level + ": R " + g.r);
                    assertTrue(pieces.kerb[g.index] <= level.kerb() && (tight || pieces.kerb[g.index] == 0),
                            "kerbs only on tight bends, the tier's width at most");
                }
                double hw = level.width() / 2.0;
                for (int x = 0; x < r.sx; x++) {
                    for (int z = 0; z < r.sz; z++) {
                        if (r.drive(x, z) && r.segAt[x][z].arc) {
                            boolean widened = Math.abs(r.vAt[x][z]) > hw;
                            assertEquals(widened, r.mat[x][z] == TrackRaster.SAND,
                                    "on a bend, the sand is exactly the widening beyond the lane: the ice lane stays whole");
                        }
                    }
                }
            }
        }
    }
}
