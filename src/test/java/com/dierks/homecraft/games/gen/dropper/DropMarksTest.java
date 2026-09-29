package com.dierks.homecraft.games.gen.dropper;

import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.Point;
import com.dierks.homecraft.games.trial.Tier;
import com.dierks.homecraft.games.trial.TrialKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a Dropper is written as an ordinary time trial (EVENTS-DROPPER-SPEC §B.1.7), the pure core of
 * the wiring's {@code DropperLayout}: ledges and pools alternate, a bonk goes back to the current
 * level's ledge, the pool box comes from the mark, and a player on the floor round a pool can never
 * reach the pool's mark: only the water can.
 */
class DropMarksTest {

    private static Course course(Course.Spot start, List<Course.Mark> cps, Course.Mark finish, Double fallY) {
        return new Course("fresh_dropper", TrialKind.PARKOUR, "Dropper", Tier.MEDIUM, "", start, cps, finish, fallY, 8,
                true, false, 1);
    }

    /** A hand-written two-level dropper: ledges at y 216, a medium pool at 176 and an easy one at 184. */
    private static Course twoLevels() {
        Course.Spot start = new Course.Spot(10.5, 216, 3.5, 0, 30);
        List<Course.Mark> cps = List.of(DropMarks.pool(10.5, 176, 8.5, 7), DropMarks.ledge(22.5, 216, 3.5));
        return course(start, cps, DropMarks.pool(22.5, 184, 7.5, 11), 168.0);
    }

    // ---- the marks ----------------------------------------------------------------------------------

    @Test
    void aPoolMarkSitsSoItsSphereTopsOutHalfABlockOverTheWater() {
        Course.Mark m = DropMarks.pool(10.5, 100, 20.5, 5);
        assertEquals(3.5, m.radius(), 1e-12, "r = h + 1 with h = 2.5");
        assertEquals(100 + 0.5, m.y() + m.radius(), 1e-12, "the sphere's top is 0.5 over the surface");
        assertEquals(2.5, DropMarks.halfWidth(m), 1e-12, "the half-width back from the mark");
        assertEquals(100, DropMarks.surface(m), 1e-12, "the surface back from the mark");
        assertEquals(96, DropMarks.floorRow(m), "the floor row under 3 blocks of water");
        assertArrayEquals(new double[]{8, 97, 18, 13, 100.5, 23}, DropMarks.poolBox(m), 1e-12,
                "the pool box: the water's column from its bottom to half a block over the surface");
        Course.Mark ledge = DropMarks.ledge(5.5, 116, 3.5);
        assertEquals(DropperGeometry.LEDGE_RADIUS, ledge.radius(), 0, "a ledge mark is 2.5 round its centre");
        assertEquals(116, ledge.y(), 0, "at feet height");
    }

    @Test
    void ledgesAndPoolsAlternate() {
        Course c = twoLevels();
        assertEquals(2, DropMarks.levels(c), "two checkpoints and a finish: two levels");
        assertEquals(10.5, DropMarks.ledgeOf(c, 0).x(), 0, "level 1's ledge is the start");
        assertSame(c.checkpoints().get(0), DropMarks.poolOf(c, 0), "then its pool");
        assertSame(c.checkpoints().get(1), DropMarks.ledgeOf(c, 1), "then level 2's ledge");
        assertSame(c.finish(), DropMarks.poolOf(c, 1), "and the last pool is the finish");
        assertTrue(DropMarks.isPool(0) && !DropMarks.isPool(1) && DropMarks.isPool(2),
                "target 0 is a pool, 1 a ledge, 2 a pool");
        assertEquals(List.of(0, 1, 1), List.of(DropMarks.levelOf(0), DropMarks.levelOf(1), DropMarks.levelOf(2)),
                "pool 1 is level 1's; ledge 2 and pool 2 are level 2's");
        assertNull(DropMarks.ledgeOf(c, 5), "no level 6");
    }

    @Test
    void aBonkGoesBackToTheCurrentLevelsLedge() {
        Course c = twoLevels();
        assertEquals(DropMarks.ledgeOf(c, 0), DropMarks.backTo(c, -1), "before the first splash: level 1's ledge");
        assertEquals(DropMarks.ledgeOf(c, 1), DropMarks.backTo(c, 0), "pool 1 splashed: the hop to level 2's ledge");
        assertEquals(DropMarks.ledgeOf(c, 1), DropMarks.backTo(c, 1), "on level 2's ledge: still level 2");
        assertEquals(DropMarks.ledgeOf(c, 1), DropMarks.backTo(c, 2), "past the finish: never beyond the last level");
        assertEquals(List.of(0, 1, 1, 2), List.of(DropMarks.currentLevel(-1), DropMarks.currentLevel(0),
                DropMarks.currentLevel(1), DropMarks.currentLevel(2)), "the level a run is on");
    }

    @Test
    void theHopFacesFromTheLedgeTowardItsPool() {
        assertEquals(0f, DropMarks.yaw(0, 1), 1e-4, "south is yaw 0");
        assertEquals(90f, DropMarks.yaw(-1, 0), 1e-4, "west is 90");
        assertEquals(180f, DropMarks.yaw(0, -1), 1e-4, "north is 180");
        assertEquals(270f, DropMarks.yaw(1, 0), 1e-4, "east is 270");
        Course c = twoLevels();
        assertEquals(0f, DropMarks.facing(DropMarks.ledgeOf(c, 1), DropMarks.poolOf(c, 1)), 1e-4,
                "level 2's pool is south of its ledge");
    }

    // ---- the rim never reaches a pool mark ----------------------------------------------------------

    @Test
    void aPlayerStandingOnTheFloorRoundAPoolNeverReachesItsMark() {
        for (int size : DropMarks.POOL_SIZES) {
            int x0 = 100;
            int z0 = 200;
            int surface = 64;
            Course.Mark m = DropMarks.pool(x0 + size / 2.0, surface, z0 + size / 2.0, size);
            int checked = 0;
            for (double x = x0 - 3; x <= x0 + size + 3; x += 0.05) {
                for (double z = z0 - 3; z <= z0 + size + 3; z += 0.05) {
                    boolean overWaterOnly = x - DropSim.HALF_WIDTH >= x0 && x + DropSim.HALF_WIDTH <= x0 + size
                            && z - DropSim.HALF_WIDTH >= z0 && z + DropSim.HALF_WIDTH <= z0 + size;
                    if (overWaterOnly) {
                        continue; // not standing: that body is in the water
                    }
                    for (double lift = 0; lift <= 1.25; lift += 0.25) {
                        assertFalse(m.contains(new Point(x, surface + lift, z)), "pool " + size + ": feet at " + x
                                + ", " + (surface + lift) + ", " + z + " on the floor round it (or jumping there) "
                                + "must not reach the mark");
                        checked++;
                    }
                }
            }
            assertTrue(checked > 1000, "pool " + size + ": the floor round it was really walked: " + checked);
            assertTrue(m.contains(new Point(x0 + size / 2.0, surface, z0 + size / 2.0)),
                    "pool " + size + ": dropping into its middle reaches the mark");
        }
    }

    @Test
    void theBoxCatchesTheCornerSplashesTheSphereMisses() {
        Course.Mark m = DropMarks.pool(102.5, 64, 202.5, 5);
        Point corner = new Point(100.05, 64.2, 200.05);
        assertTrue(DropMarks.inPool(m, corner.x(), corner.y(), corner.z()),
                "a splash in the pool's corner is in its box");
        assertFalse(m.contains(corner), "though the sphere doesn't reach that corner");
        assertFalse(DropMarks.inPool(m, 99.9, 64.2, 202.5), "just outside the water isn't in the box");
        assertFalse(DropMarks.inPool(m, 102.5, 64.6, 202.5), "nor is more than half a block over the surface");
    }

    // ---- what makes a dropper a dropper --------------------------------------------------------------

    @Test
    void aWellFormedDropperHasNoProblems() {
        assertEquals(List.of(), DropMarks.problems(twoLevels()), "the hand-written two-level dropper is fine");
        Course planned = DropperFixtures.course(DropperFixtures.plan("EEMMH", 1));
        assertEquals(List.of(), DropMarks.problems(planned), "and so is what the planner writes");
    }

    @Test
    void marksOutOfOrderAreFound() {
        Course c = twoLevels();
        List<Course.Mark> swapped = List.of(c.checkpoints().get(1), c.checkpoints().get(0));
        List<String> p = DropMarks.problems(c.withCheckpoints(swapped));
        assertFalse(p.isEmpty(), "a ledge where a pool should be is refused");
        assertTrue(String.join("; ", p).contains("out of order"), "and the admin is told why: " + p);
        List<String> odd = DropMarks.problems(c.withCheckpoints(List.of(c.checkpoints().get(0))));
        assertTrue(String.join("; ", odd).contains("in pairs"), "an odd number of checkpoints: " + odd);
    }

    @Test
    void aDropperHasAtMostFiveLevels() {
        List<Course.Mark> cps = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            cps.add(DropMarks.pool(10.5 + 12 * i, 176, 8.5, 7));
            cps.add(DropMarks.ledge(22.5 + 12 * i, 216, 3.5));
        }
        Course six = course(new Course.Spot(10.5, 216, 3.5, 0, 30), cps, DropMarks.pool(70.5, 176, 8.5, 7), 160.0);
        assertEquals(6, DropMarks.levels(six), "five pairs and a finish");
        assertTrue(String.join("; ", DropMarks.problems(six)).contains("at most 5"), "six levels is one too many");
    }

    @Test
    void aShallowPoolAPoolAwayFromItsLedgeAndAHighFallLineAreFound() {
        Course c = twoLevels();
        Course shallow = c.withFinish(DropMarks.pool(22.5, 200, 7.5, 11));
        assertTrue(String.join("; ", DropMarks.problems(shallow)).contains("at least 24"),
                "a pool 16 below its ledge is too shallow a drop");
        Course away = c.withFinish(DropMarks.pool(60.5, 184, 7.5, 11));
        assertTrue(String.join("; ", DropMarks.problems(away)).contains("isn't under its ledge"),
                "a pool in another shaft");
        Course high = c.withFallY(175.0);
        assertTrue(String.join("; ", DropMarks.problems(high)).contains("fall height"),
                "a fall line above the lowest pool floor (172) would void a splash");
        Course none = c.withFallY(null);
        assertTrue(String.join("; ", DropMarks.problems(none)).contains("fall height"), "and none at all");
        assertFalse(DropMarks.problems(c.withStart(null)).isEmpty(), "no start");
    }

    // ---- reading a planned course back ---------------------------------------------------------------

    @Test
    void theMixReadsBackFromThePools() {
        assertEquals("EEMMH", DropMarks.mix(DropperFixtures.course(DropperFixtures.plan("EEMMH", 1))),
                "11, 11, 7, 7 and 5 a side: EEMMH");
        assertEquals("ME", DropMarks.mix(twoLevels()), "the hand-written one: a 7 then an 11");
        Course odd = twoLevels().withFinish(DropMarks.pool(22.5, 184, 7.5, 9));
        assertNull(DropMarks.mix(odd), "a 9 x 9 pool names no tier");
        assertNull(DropMarks.mix(null), "nor does nothing");
    }

    @Test
    void theLiveProbesAreTheLedgeBlocksAndThePoolsCentreWater() {
        Plan p = DropperFixtures.plan("EEMMH", 1);
        DropWorld w = DropperFixtures.world(p);
        List<DropMarks.Probe> probes = DropMarks.probes(DropperFixtures.course(p));
        assertEquals(10, probes.size(), "one ledge and one pool a level");
        for (DropMarks.Probe probe : probes) {
            byte want = probe.water() ? DropWorld.WATER : DropWorld.LEDGE;
            assertEquals(want, w.get(probe.x(), probe.y(), probe.z()), "the planned blocks stand where " + probe
                    + " looks");
        }
        assertTrue(DropMarks.probes(twoLevels().withStart(null)).isEmpty(), "no probes for a course with no start");
    }
}
