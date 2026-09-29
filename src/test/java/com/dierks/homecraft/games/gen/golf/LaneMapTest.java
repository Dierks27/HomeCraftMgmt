package com.dierks.homecraft.games.gen.golf;

import com.dierks.homecraft.games.golf.GolfCourse;
import com.dierks.homecraft.games.golf.GolfShot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A hole's lane read back from its blocks (GEN-SPEC §4.3), which the expert, the kid and the
 * validator all share: the lane is what the ball can roll onto from the tee, path distance runs
 * round walls and up ramps only, the centre line runs down the middle of a leg and keeps a block
 * clear of the walls round a bend, sight lines stop at rocks and cliffs, and a bearing is a Minecraft yaw that
 * {@link GolfShot#direction} turns back into the same way.
 */
class LaneMapTest {

    private static LaneMap lane(HoleLayout l) {
        return LaneMap.of(GolfKit.grid(l), GolfKit.hole(l));
    }

    @Test
    void aStraightLaneIsItsFiveColumnsAndItsCentreLineRunsDownTheMiddle() {
        HoleLayout l = GolfKit.straight(12, 0);
        LaneMap lane = lane(l);
        assertEquals(5 * 15, lane.laneCells(), "5 wide, from the row behind the tee to the row past the cup");
        assertEquals(0, lane.pathDistance(l.cupX() + 0.5, l.cupZ() + 0.5), 1e-9, "the cup is 0 from itself");
        assertEquals(12, lane.pathDistance(l.teeX() + 0.5, l.teeZ() + 0.5), 1e-9, "the tee is 12 up the lane");
        for (int i = 0; i < lane.waypoints(); i++) {
            assertEquals(l.teeX() + 0.5, lane.waypointX(i), 1e-9, "waypoint " + i + " is on the middle column");
        }
        assertEquals(lane.waypoints() - 1, lane.target(l.teeX() + 0.5, l.teeZ() + 0.5),
                "from the tee the kid sees the cup, the last waypoint");
    }

    @Test
    void aDoglegsCentreLineKeepsOffTheWallsRoundTheElbowAndTheCupIsHidden() {
        HoleLayout l = GolfKit.dogleg(12, 8, 0);
        LaneMap lane = lane(l);
        GolfCourse.Hole h = GolfKit.hole(l);
        int elbowZ = GolfKit.PLOT_Z + 3 + 12;
        boolean roundTheElbow = false;
        for (int i = 0; i < lane.waypoints(); i++) {
            int x = (int) Math.floor(lane.waypointX(i));
            int z = (int) Math.floor(lane.waypointZ(i));
            roundTheElbow |= Math.abs(z - elbowZ) <= 2 && Math.abs(x - (GolfKit.PLOT_X + 4)) <= 2;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    assertTrue(lane.isLane(x + dx, z + dz), "waypoint " + i + " keeps a block clear of the walls");
                }
            }
        }
        assertTrue(roundTheElbow, "the centre line goes round through the elbow's square");
        int aim = lane.target(h.tee().x(), h.tee().z());
        assertTrue(aim < lane.waypoints() - 1, "from the tee the cup can't be seen: the kid aims up the lane");
        assertTrue(lane.waypointZ(aim) > h.tee().z() + 8, "far up the first leg: " + lane.waypointZ(aim));
        assertTrue(lane.pathDistance(h.tee().x(), h.tee().z()) > Math.hypot(12, 8) + 2,
                "the way round the elbow is longer than the straight line to the cup");
    }

    @Test
    void sightLinesStopAtCliffsButClimbRamps() {
        HoleLayout l = GolfKit.island(6, 9, 9);
        LaneMap lane = lane(l);
        double teeX = l.teeX() + 0.5;
        double teeZ = l.teeZ() + 0.5;
        int ramp = GolfKit.PLOT_Z + 3 + 6;
        assertTrue(lane.clear(teeX, teeZ, GolfKit.PLOT_X + 9.5, ramp + 2.5), "straight up the one-wide ramp");
        assertFalse(lane.clear(GolfKit.PLOT_X + 7.5, ramp - 1.5, GolfKit.PLOT_X + 7.5, ramp + 2.5),
                "not up the side of the green: a full block is too high");
        assertTrue(lane.isLane(GolfKit.PLOT_X + 7, ramp + 2), "the green is lane (reached up the ramp)");
        assertTrue(lane.pathDistance(GolfKit.PLOT_X + 7.5, ramp - 0.5) > lane.pathDistance(GolfKit.PLOT_X + 9.5,
                ramp - 0.5), "the way to the cup from beside the ramp goes round by the ramp");
    }

    @Test
    void aBearingIsTheYawThatPuttsThatWay() {
        for (double[] to : new double[][]{{0, 5}, {-5, 0}, {5, 0}, {0, -5}, {3, 4}, {-2, 7}}) {
            double yaw = LaneMap.bearing(0, 0, to[0], to[1]);
            GolfShot.Direction d = GolfShot.direction((float) yaw);
            double len = Math.hypot(to[0], to[1]);
            assertEquals(to[0] / len, d.dx(), 1e-6, "towards " + to[0] + "," + to[1] + ": x");
            assertEquals(to[1] / len, d.dz(), 1e-6, "towards " + to[0] + "," + to[1] + ": z");
        }
        assertEquals(0, LaneMap.bearing(0, 0, 0, 1), 1e-9, "+Z is yaw 0");
        assertEquals(90, LaneMap.bearing(0, 0, -1, 0), 1e-9, "-X is yaw 90");
    }
}
