package com.dierks.homecraft.games.gen.boat;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenRandom;
import com.dierks.homecraft.games.gen.api.GenSeed;
import com.dierks.homecraft.games.gen.api.LegacyBoxes;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.trial.RaceStand;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Mountain Run's centreline (Course Variety §2.3, §9 TrackPathTest): a rounded-square spiral
 * whose rings never touch, that keeps inside the half and 12 from the stand, bends no tighter than
 * the tier allows, comes in all 8 orientations, and starts with a straight, flat pit.
 */
class TrackPathTest {

    private static final Box HALF = LegacyBoxes.v036(Slots.ICE_BOAT, 'A');
    private static final int SX = RaceStand.centreX(HALF) - HALF.minX();
    private static final int SZ = RaceStand.centreZ(HALF) - HALF.minZ();

    static TrackPath path(BoatPlanner.Level level, int day) {
        long seed = GenSeed.seed(0x5EC12E7L, 20_000 + day, Slots.ICE_BOAT.id(), 0);
        return TrackPath.draw(new GenRandom(seed).fork("track:0"), level, SX + 0.5, SZ + 0.5);
    }

    /** The lane of {@code path} on whole blocks, flat, with no pieces and no sand: its base footprint. */
    static TrackRaster lane(TrackPath path, BoatPlanner.Level level) {
        double finish = TrackProfile.finish(null, path, level, SX, SZ);
        if (Double.isNaN(finish)) {
            finish = path.length - 25;
        }
        TrackProfile flat = TrackProfile.of(path, level, HALF.minY() + BoatPlanner.TOP_ABOVE, List.of(), finish);
        return new TrackRaster(HALF, path, flat, TrackPieces.none(path), level);
    }

    @Test
    void ringsOnTheSameSideArePitchApartAndTheirWallsNeverTouch() {
        for (BoatPlanner.Level level : BoatPlanner.Level.values()) {
            for (int day = 0; day < 40; day++) {
                TrackPath p = path(level, day);
                for (int k = 0; k + 4 < p.legs(); k++) {
                    assertEquals(level.pitch(), p.offsets[k] - p.offsets[k + 4],
                            level + ": legs " + k + " and " + (k + 4) + " are one pitch apart");
                }
                assertEquals(9, level.pitch() - level.width() - 2, level + ": 9 blocks between neighbouring rings' walls");
                TrackRaster r = lane(p, level);
                // no two drive cells side by side (8 ways) belong to different rings: the ring gap holds
                for (int x = 0; x < r.sx; x++) {
                    for (int z = 0; z < r.sz; z++) {
                        if (!r.drive(x, z)) {
                            continue;
                        }
                        for (int dx = -2; dx <= 2; dx++) {
                            for (int dz = -2; dz <= 2; dz++) {
                                if (r.drive(x + dx, z + dz)) {
                                    assertTrue(Math.abs(r.sAt[x][z] - r.sAt[x + dx][z + dz]) < 12, level + " day " + day
                                            + ": two rings come within 2 at " + x + " " + z);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void theSpiralKeepsInsideTheHalfAndTwelveFromTheStand() {
        for (BoatPlanner.Level level : BoatPlanner.Level.values()) {
            for (int day = 0; day < 40; day++) {
                TrackPath p = path(level, day);
                assertTrue(p.a0 >= BoatPlanner.A0_MIN && p.a0 <= BoatPlanner.A0_MAX, "a0 from 54 to 56: " + p.a0);
                int innermost = p.offsets[p.legs() - 1];
                int want = switch (level) {
                    case EASY -> 20;
                    case MEDIUM -> 19;
                    case HARD -> 18;
                };
                assertTrue(innermost >= want, level + ": the innermost leg is at least " + want + " from the centre: "
                        + innermost);
                TrackRaster r = lane(p, level);
                assertEquals(null, r.problem(), level + " day " + day + ": inside the half and 12 from the stand");
                for (int x = 0; x < r.sx; x++) {
                    for (int z = 0; z < r.sz; z++) {
                        if (r.beside(x, z)) {
                            double ox = Math.abs(x + 0.5 - p.cx);
                            double oz = Math.abs(z + 0.5 - p.cz);
                            assertTrue(ox <= 61 && oz <= 61, level + " day " + day + ": the outermost wall is at most 61"
                                    + " from the centre, at " + x + " " + z);
                        }
                    }
                }
            }
        }
    }

    @Test
    void everyBendIsARoundedCornerNoTighterThanTheTiersAndTheInnerOnesAreSmall() {
        for (BoatPlanner.Level level : BoatPlanner.Level.values()) {
            for (int day = 0; day < 40; day++) {
                TrackPath p = path(level, day);
                int arcs = 0;
                for (TrackPath.Seg g : p.segs) {
                    if (!g.arc) {
                        continue;
                    }
                    arcs++;
                    assertTrue(g.r >= level.minRadius(), level + ": no bend tighter than R " + level.minRadius());
                    assertTrue(g.r <= level.radiusCap(g.leg), level + ": corner " + g.leg + " at most its cap");
                    assertEquals(g.r * Math.PI / 2, g.len, 1e-9, "a quarter circle");
                }
                assertEquals(level.lastLeg(), arcs, level + ": a corner between every two legs");
                for (int k = 0; k < p.legs(); k++) {
                    assertTrue(p.straight(k).len >= 0, "every straight has a length");
                }
            }
        }
    }

    @Test
    void allEightOrientationsComeUp() {
        for (BoatPlanner.Level level : BoatPlanner.Level.values()) {
            Set<String> seen = new HashSet<>();
            for (int day = 0; day < 80; day++) {
                TrackPath p = path(level, day);
                seen.add(p.describe());
            }
            assertEquals(8, seen.size(), level + ": 4 sides x 2 ways round: " + seen);
        }
    }

    @Test
    void thePitIsTheStartOfAStraightFlatLegZero() {
        for (BoatPlanner.Level level : BoatPlanner.Level.values()) {
            for (int day = 0; day < 20; day++) {
                TrackPath p = path(level, day);
                TrackPath.Seg leg0 = p.straight(0);
                assertEquals(0, leg0.s0, 1e-9, "leg 0 starts the track");
                assertTrue(leg0.len >= TrackProfile.START + TrackProfile.CLEAN, level
                        + ": the pit and the 40 blocks after the start are one straight: " + leg0.len);
                double[] a = p.at(0.5);
                double[] b = p.at(TrackProfile.PIT);
                assertTrue(a[0] == b[0] || a[1] == b[1], "straight along an axis");
                double lateral = a[0] == b[0] ? a[0] : a[1];
                assertEquals(0.5, lateral - Math.floor(lateral), 1e-9, "the pit's middle runs down the middle of a column");
                TrackRaster r = lane(p, level);
                int[] start = r.cellAt(TrackProfile.START);
                assertNotNull(start, "the start is on the track");
                assertEquals(r.profile.top, r.h[start[0]][start[1]], "at the top level");
            }
        }
    }

    @Test
    void theNearestCentrelinePointIsExactOnStraightsAndBends() {
        TrackPath p = path(BoatPlanner.Level.MEDIUM, 3);
        for (double s = 5; s < p.length - 5; s += 7.25) {
            double[] q = p.at(s);
            int x = (int) Math.floor(q[0]);
            int z = (int) Math.floor(q[1]);
            TrackPath.Near n = p.nearest(x, z, 3, p.length);
            assertNotNull(n, "a column on the centreline finds it");
            assertTrue(n.dist <= Math.sqrt(0.5) + 1e-9, "within half a block's diagonal of its middle");
            assertTrue(Math.abs(n.s - s) <= 1.5, "at about the same s: " + n.s + " for " + s);
            double[] t = p.tangent(s);
            assertEquals(1, Math.hypot(t[0], t[1]), 1e-9, "the tangent is a unit vector");
        }
        TrackPath.Seg st = p.straight(2);
        double mid = st.s0 + st.len / 2;
        double[] c = p.at(mid);
        TrackPath.Near n = p.nearest((int) Math.floor(c[0]), (int) Math.floor(c[1]), 3, p.length);
        assertEquals(0, n.dist, 1e-9, "a straight's centreline runs through its columns' middles");
    }

    @Test
    void theLastLegStopsShortOfTheRingOutsideIt() {
        for (BoatPlanner.Level level : BoatPlanner.Level.values()) {
            for (int day = 0; day < 20; day++) {
                TrackPath p = path(level, day);
                int last = p.legs() - 1;
                double[] end = p.at(p.length);
                int outer = p.offsets[Math.max(0, last - 3)];
                double along = Math.abs((end[0] - p.cx) * TrackPath.SIDE[p.side(last + 1)][0]
                        + (end[1] - p.cz) * TrackPath.SIDE[p.side(last + 1)][1]);
                assertTrue(along <= outer - level.width() / 2.0 - BoatPlanner.END_ROOM + 1e-9, level
                        + ": the last leg ends clear of the ring outside it");
            }
        }
    }
}
