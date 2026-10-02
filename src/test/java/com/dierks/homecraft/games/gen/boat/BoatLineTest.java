package com.dierks.homecraft.games.gen.boat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The line model (MOUNTAIN-V2-SPEC §3.1, a port of {@code mtn/sim.py}): every number the planner times
 * and scores a run by is pinned here, because a layout depends on each (changing one is an ALGO bump).
 */
class BoatLineTest {

    private static final double[] RADII = {15, 20, 25, 30, 40, 50, 60, 80, 100, 150, 200};
    private static final double[] PACKED = {14.9, 17.0, 18.8, 20.3, 22.9, 25.0, 26.7, 29.4, 31.4, 34.6, 36.4};
    private static final double[] BLUE = {15.3, 17.6, 19.6, 21.4, 24.5, 27.2, 29.6, 33.7, 37.1, 43.8, 48.7};

    @Test
    void theCorneringSpeedsAreTheSpecsTable() {
        for (int i = 0; i < RADII.length; i++) {
            assertEquals(PACKED[i], BoatLine.cornerBps(RADII[i], BoatLine.PACKED), 0.05,
                    "packed ice, R " + RADII[i] + ": v_c in blocks a second");
            assertEquals(BLUE[i], BoatLine.cornerBps(RADII[i], BoatLine.BLUE), 0.05,
                    "blue ice, R " + RADII[i] + ": v_c in blocks a second");
        }
        assertEquals(40.0, BoatLine.topBps(BoatLine.PACKED), 1e-9, "packed ice's top speed: 0.04 / 0.02 a tick");
        assertEquals(72.7, BoatLine.topBps(BoatLine.BLUE), 0.05, "blue ice's: 0.04 / 0.011");
        assertTrue(BoatLine.cornerBps(1e6, BoatLine.PACKED) < 40.0 + 1e-6, "no corner is faster than the straight");
    }

    @Test
    void aDropIsABrake() {
        double top = 2.0;
        BoatLine.Result one = BoatLine.run(List.of(BoatLine.Seg.straight(60, List.of(new BoatLine.Lip(0, 1)))), top);
        BoatLine.Result two = BoatLine.run(List.of(BoatLine.Seg.straight(60, List.of(new BoatLine.Lip(0, 2)))), top);
        assertEquals(23.3, one.landing()[0] * BoatLine.TICKS, 0.05, "a 1-block drop from 40 b/s lands at 23.3");
        assertEquals(19.2, two.landing()[0] * BoatLine.TICKS, 0.05, "a 2-block drop from 40 b/s lands at 19.2");
        double blueTop = 0.04 / (1 - BoatLine.BLUE);
        BoatLine.Seg blue1 = new BoatLine.Seg(60, 0, BoatLine.BLUE, List.of(new BoatLine.Lip(0, 1)), List.of());
        BoatLine.Seg blue2 = new BoatLine.Seg(60, 0, BoatLine.BLUE, List.of(new BoatLine.Lip(0, 2)), List.of());
        assertEquals(39.0, BoatLine.run(List.of(blue1), blueTop).landing()[0] * BoatLine.TICKS, 0.05,
                "on blue ice from 72.7 b/s a 1-block drop lands at 39.0");
        assertEquals(30.6, BoatLine.run(List.of(blue2), blueTop).landing()[0] * BoatLine.TICKS, 0.05,
                "and a 2-block one at 30.6");
        // the flights: 10.3 blocks in 7 ticks, 13.4 in 10 (the straight ends inside the flight, or just past)
        assertEquals(7, BoatLine.run(List.of(BoatLine.Seg.straight(10.2, List.of(new BoatLine.Lip(0, 1)))), top).ticks(),
                "a 1-block drop flies past 10.2 in its 7 air ticks");
        assertEquals(8, BoatLine.run(List.of(BoatLine.Seg.straight(10.5, List.of(new BoatLine.Lip(0, 1)))), top).ticks(),
                "but not past 10.5: it flies 10.3");
        assertEquals(10, BoatLine.run(List.of(BoatLine.Seg.straight(13.3, List.of(new BoatLine.Lip(0, 2)))), top).ticks(),
                "a 2-block drop flies past 13.3 in its 10 air ticks");
        assertEquals(11, BoatLine.run(List.of(BoatLine.Seg.straight(13.6, List.of(new BoatLine.Lip(0, 2)))), top).ticks(),
                "but not past 13.6: it flies 13.4");
        BoatLine.Result flat = BoatLine.run(List.of(BoatLine.Seg.straight(200)), top);
        BoatLine.Result dropped = BoatLine.run(List.of(BoatLine.Seg.straight(200, List.of(new BoatLine.Lip(50, 2)))), top);
        double cost = dropped.seconds() - flat.seconds();
        assertTrue(cost > 0.9 && cost < 1.5, "a 2-block drop costs about 1.1-1.3 s against holding 40 b/s: " + cost);
    }

    @Test
    void anArcCapsTheSpeedAtItsCorneringSpeed() {
        BoatLine.Result r = BoatLine.run(List.of(BoatLine.Seg.straight(150), BoatLine.Seg.arcDegrees(40, 90),
                BoatLine.Seg.straight(10)));
        double vc = BoatLine.cornerSpeed(40, BoatLine.PACKED);
        assertEquals(vc, r.corner()[1], 1e-12, "the arc's v_c is reported");
        assertTrue(r.entry()[1] > 1.2 * vc, "150 blocks of straight enter R 40 hard: " + r.entry()[1] * 20);
        assertEquals(vc, r.exit()[1], 1e-9, "and the boat leaves it at v_c");
        assertEquals(0, r.corner()[0], 0.0, "a straight has no v_c");
        assertEquals(160 + 40 * Math.PI / 2, r.length(), 1e-9, "the length is the segments'");
    }

    @Test
    void sandIsACrawl() {
        BoatLine.Seg sandy = new BoatLine.Seg(100, 0, BoatLine.PACKED, List.of(),
                List.of(new BoatLine.Zone(10, 100, BoatLine.SAND)));
        BoatLine.Result r = BoatLine.run(List.of(sandy, BoatLine.Seg.straight(1)), 2.0);
        assertEquals(2.0, r.exit()[0] * BoatLine.TICKS, 1.0, "90 blocks of sand bring 40 b/s down near the 2 b/s crawl");
        assertTrue(r.seconds() > 30, "and take a long time: " + r.seconds());
        assertEquals(2.0, BoatLine.topBps(BoatLine.SAND), 1e-9, "sand's own top speed is 2 b/s");
    }

    @Test
    void theModelIsDeterministic() {
        List<BoatLine.Seg> line = List.of(BoatLine.Seg.straight(80, List.of(new BoatLine.Lip(30, 1))),
                BoatLine.Seg.arcDegrees(60, -35), BoatLine.Seg.straight(40), BoatLine.Seg.arcDegrees(90, 40),
                BoatLine.Seg.straight(120, List.of(new BoatLine.Lip(20, 2), new BoatLine.Lip(90, 1))));
        BoatLine.Result a = BoatLine.run(line);
        BoatLine.Result b = BoatLine.run(line);
        assertEquals(a.ticks(), b.ticks(), "the same ticks");
        assertArrayEquals(a.entry(), b.entry(), "the same entry speeds");
        assertArrayEquals(a.landing(), b.landing(), "the same landings");
        assertEquals(3, a.landing().length, "one landing a lip, in order");
        assertEquals(a.ticks(), a.ticksBetween(0, 5), "segment 0 to the end is the whole run");
        assertEquals(a.length() / a.seconds(), a.average(), 1e-12, "the average is length over time");
    }
}
