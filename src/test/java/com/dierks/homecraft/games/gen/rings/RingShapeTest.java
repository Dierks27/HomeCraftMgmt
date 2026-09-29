package com.dierks.homecraft.games.gen.rings;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A ring's blocks (GEN-SPEC §4.2): a one-block-thick voxel circle round its centre, upright and
 * square to x or z, with a clear hole 2R − 1 wide, facing whichever axis is nearer the flight.
 */
class RingShapeTest {

    @Test
    void aRingIsAVoxelCircleInOnePlane() {
        for (int r = 4; r <= 6; r++) {
            for (RingShape.Normal n : RingShape.Normal.values()) {
                List<int[]> v = RingShape.voxels(100, 200, 300, r, n);
                Set<String> seen = new HashSet<>();
                for (int[] b : v) {
                    assertTrue(seen.add(b[0] + "," + b[1] + "," + b[2]), "no block twice");
                    int u = n == RingShape.Normal.Z ? b[0] - 100 : b[2] - 300;
                    int w = b[1] - 200;
                    double d = Math.sqrt(u * u + w * w);
                    assertTrue(d >= r - 0.5 && d < r + 0.5, "every block is R +- 0.5 from the centre: " + d);
                    if (n == RingShape.Normal.Z) {
                        assertEquals(300, b[2], "a z-facing ring stands in one z");
                    } else {
                        assertEquals(100, b[0], "an x-facing ring stands in one x");
                    }
                }
                assertTrue(v.size() >= 5 * r && v.size() <= 8 * r, "a closed circle, about 2 pi R blocks: " + v.size());
                assertTrue(seen.contains(n == RingShape.Normal.Z ? (100 - r) + ",200,300" : "100,200," + (300 - r)),
                        "it reaches R out sideways");
                assertTrue(seen.contains(n == RingShape.Normal.Z ? "100," + (200 + r) + ",300" : "100," + (200 + r)
                        + ",300"), "and R up");
                assertTrue(!seen.contains("100,200,300"), "its middle is open");
            }
        }
    }

    @Test
    void aRingFacesTheAxisNearerTheFlight() {
        assertEquals(RingShape.Normal.Z, RingShape.normalFor(0.2, -1), "a flight mostly north: facing z");
        assertEquals(RingShape.Normal.X, RingShape.normalFor(1, 0.3), "a flight mostly east: facing x");
        assertEquals(0, RingShape.offNormal(0, -1, RingShape.Normal.Z), 1e-9, "straight through");
        assertEquals(45, RingShape.offNormal(1, 1, RingShape.Normal.Z), 1e-9, "a diagonal is 45 off either way");
        assertEquals(35, RingShape.offNormal(StrictMath.sin(StrictMath.toRadians(35)),
                StrictMath.cos(StrictMath.toRadians(35)), RingShape.Normal.Z), 1e-9, "35 off");
    }

    @Test
    void theHoleIsTwoRMinusOneWide() {
        for (int r = 4; r <= 6; r++) {
            int open = 0;
            for (int u = -r; u <= r; u++) {
                if (!RingShape.inFrame(u, 0, r) && Math.abs(u) < r) {
                    open++;
                }
            }
            assertEquals(2 * r - 1, open, "a ring of radius " + r + " has a hole " + (2 * r - 1) + " wide");
        }
    }
}
