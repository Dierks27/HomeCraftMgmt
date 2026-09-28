package com.dierks.homecraft.games.trial;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The checkpoint maths, with no server.
 *
 * <p>Pinned here: a move is tested as its whole segment, not its end point — it touches a sphere
 * it passes straight through, at the fraction of the way where it first enters; a move that
 * starts inside touches at once; a move that stops short, or passes beside, doesn't touch; the
 * search can start part-way along (for the next checkpoint of the same move); a move that doesn't
 * move is simply inside or not; grazing the surface counts; and the facing used when a run goes
 * back looks at the next checkpoint in Minecraft's yaw convention.
 */
class GeometryTest {

    private static final Point O = new Point(0, 0, 0);
    private static final double EPS = 1e-9;

    @Test
    void aMoveThatPassesStraightThroughTouchesWhereItFirstEnters() {
        double t = Geometry.firstHit(O, new Point(10, 0, 0), new Point(5, 0, 0), 1, 0);
        assertEquals(0.4, t, EPS, "enters at x = 4, 40% of the way along a 10-block move");
    }

    @Test
    void aMoveThatEndsInsideTouchesAtTheEntryToo() {
        double t = Geometry.firstHit(O, new Point(5, 0, 0), new Point(5, 0, 0), 1, 0);
        assertEquals(0.8, t, EPS, "enters at x = 4 of a 5-block move");
    }

    @Test
    void aMoveThatStartsInsideTouchesAtOnce() {
        double t = Geometry.firstHit(new Point(5, 0, 0), new Point(9, 0, 0), new Point(5, 0, 0), 1, 0);
        assertEquals(0.0, t, EPS, "already inside at the start of the move");
    }

    @Test
    void aMoveThatStopsShortOrPassesBesideDoesNotTouch() {
        assertTrue(Double.isNaN(Geometry.firstHit(O, new Point(3, 0, 0), new Point(5, 0, 0), 1, 0)),
                "stops a block short of the sphere");
        assertTrue(Double.isNaN(Geometry.firstHit(O, new Point(10, 0, 0), new Point(5, 2, 0), 1, 0)),
                "passes two blocks beside a one-block sphere");
        assertTrue(Double.isNaN(Geometry.firstHit(new Point(10, 0, 0), new Point(20, 0, 0), new Point(5, 0, 0), 1, 0)),
                "the sphere is behind the move");
    }

    @Test
    void grazingTheSurfaceCounts() {
        double t = Geometry.firstHit(O, new Point(10, 0, 0), new Point(5, 1, 0), 1, 0);
        assertEquals(0.5, t, EPS, "touching the sphere's edge at one point is touching it");
    }

    @Test
    void theSearchCanStartPartWayAlong() {
        Point c = new Point(5, 0, 0);
        assertEquals(0.5, Geometry.firstHit(O, new Point(10, 0, 0), c, 1, 0.5), EPS,
                "still inside at 50%: touched right there");
        assertTrue(Double.isNaN(Geometry.firstHit(O, new Point(10, 0, 0), c, 1, 0.7)),
                "the move left the sphere at 60%, so nothing from 70% on touches it");
        assertTrue(Double.isNaN(Geometry.firstHit(O, new Point(10, 0, 0), c, 1, 1.5)), "past the end of the move");
    }

    @Test
    void aMoveThatDoesNotMoveIsInsideOrNot() {
        Point c = new Point(0, 0, 0.5);
        assertEquals(0.0, Geometry.firstHit(O, O, c, 1, 0), EPS, "standing inside");
        assertTrue(Double.isNaN(Geometry.firstHit(O, O, new Point(0, 0, 3), 1, 0)), "standing outside");
    }

    @Test
    void theFacingBackLooksAtTheNextCheckpoint() {
        assertEquals(0f, Geometry.yawToward(O, new Point(0, 0, 5)), 1e-4, "south (+z) is yaw 0");
        assertEquals(90f, Geometry.yawToward(O, new Point(-5, 0, 0)), 1e-4, "west (-x) is yaw 90");
        assertEquals(180f, Geometry.yawToward(O, new Point(0, 0, -5)), 1e-4, "north (-z) is yaw 180");
        assertEquals(270f, Geometry.yawToward(O, new Point(5, 0, 0)), 1e-4, "east (+x) is yaw 270");
        assertEquals(0f, Geometry.yawToward(O, new Point(0, 9, 0)), 1e-4, "straight up: any facing, 0");
    }

    @Test
    void aPointAlongTheWayIsInterpolated() {
        assertEquals(new Point(2.5, 5, -1), Geometry.along(O, new Point(10, 20, -4), 0.25),
                "a quarter of the way along every axis");
    }
}
