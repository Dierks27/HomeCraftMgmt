package com.dierks.homecraft.games.trial;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The checkpoint maths, with no server.
 *
 * <p>Pinned here: a move is tested as its whole segment, not its end point — it touches a sphere
 * it passes straight through, at the fraction of the way where it first enters; a move that
 * starts inside touches at once; a move that stops short, or passes beside, doesn't touch; the
 * search can start part-way along (for the next checkpoint of the same move); a move that doesn't
 * move is simply inside or not; grazing the surface counts; the parkour form ({@code firstHitAbove})
 * tests only the part of the move at or above a floor, and its 0.37.3 form ({@code firstHitOver}) only the
 * part over the pad's column too, so a move beside the column never touches; a stand on a pad ({@code standsOn})
 * may be a hair off its edge and its top, but only where it is, never along a move; and the facing used when a
 * run goes back looks at the next checkpoint in Minecraft's yaw convention.
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
    void aMoveUnderTheFloorNeverTouchesFromBelow() {
        Point from = new Point(3, -0.4, 0);
        Point to = new Point(1, -1.4, 0);
        assertEquals(0.24, Geometry.firstHit(from, to, O, 2.6, 0), EPS, "the whole sphere: touched under its middle");
        assertTrue(Double.isNaN(Geometry.firstHitAbove(from, to, O, 2.6, 0, -0.5)),
                "under the floor the whole time it is inside: nothing");
        assertTrue(Double.isNaN(Geometry.firstHitAbove(new Point(1, -1, 0), new Point(1, -1, 0), O, 2.6, 0, -0.5)),
                "standing still under the floor, inside the sphere: nothing");
    }

    @Test
    void aMoveThatClimbsOverTheFloorTouchesWhereItCrossesIt() {
        assertEquals(5.0 / 12, Geometry.firstHitAbove(new Point(2.8, -1, 0), new Point(1.6, 0.2, 0), O, 2.6, 0, -0.5),
                EPS, "inside the sphere from the start, but it counts only once the feet rise over the floor");
    }

    @Test
    void aMoveWhollyAboveTheFloorIsTestedAsBefore() {
        Point c = new Point(5, 0, 0);
        assertEquals(Geometry.firstHit(O, new Point(10, 0, 0), c, 1, 0),
                Geometry.firstHitAbove(O, new Point(10, 0, 0), c, 1, 0, -0.5), EPS, "level, above the floor");
        assertEquals(Geometry.firstHit(new Point(0, 2, 0), new Point(8, 0, 0), c, 1, 0),
                Geometry.firstHitAbove(new Point(0, 2, 0), new Point(8, 0, 0), c, 1, 0, -0.5), EPS,
                "coming down onto it, never under the floor");
        assertEquals(Geometry.firstHit(O, new Point(10, 0, 0), c, 1, 0.5),
                Geometry.firstHitAbove(O, new Point(10, 0, 0), c, 1, 0.5, -0.5), EPS, "part-way along, as before");
        assertTrue(Double.isNaN(Geometry.firstHitAbove(O, new Point(3, 0, 0), c, 1, 0, -0.5)),
                "a miss stays a miss");
    }

    /** A real Easy checkpoint: its pad is x 4138-4140, z 4153-4155, top 170; the mark is the top's middle. */
    private static final Point PAD_MARK = new Point(4139.5, 170, 4154.5);
    /** A 3x3 pad's column: half the pad and half a player's width (1.5 + 0.3), where Java's feet can stand. */
    private static final double HALF = 1.8;
    /** The column's floor: the pad's top. */
    private static final double FLOOR = 170;
    /** A stand's hair ({@link Progress#PAD_HAIR}). */
    private static final double HAIR = Progress.PAD_HAIR;

    @Test
    void aMoveBesideThePadsColumnNeverTouches() {
        // a standing jump that fell short past the pad's side: the box 0.55 clear of its face at x 4141
        Point a = new Point(4141.985, 170.495, 4155.1);
        Point b = new Point(4141.850, 170.121, 4155.1);
        assertFalse(Double.isNaN(Geometry.firstHitAbove(a, b, PAD_MARK, 2.6, 0, 169.5)),
                "0.37.1's floor alone: inside the sphere, above the floor, so it touched");
        assertTrue(Double.isNaN(Geometry.firstHitOver(a, b, PAD_MARK, 2.6, 0, HALF, FLOOR)),
                "beside the pad's column the whole move: nothing");
    }

    @Test
    void aLandingTouchesWhereTheFeetCrossIntoTheColumn() {
        Point a = new Point(4142.2, 171.0, 4154.5);
        Point b = new Point(4141.0, 170.0, 4154.5);
        assertEquals(0.9 / 1.2, Geometry.firstHitOver(a, b, PAD_MARK, 2.6, 0, HALF, FLOOR), EPS,
                "in the sphere from the start, but over the pad only from x 4141.3");
        Point high = new Point(4140.0, 174, 4154.5);
        Point low = new Point(4140.0, 170, 4154.5);
        assertEquals(Geometry.firstHit(high, low, PAD_MARK, 2.6, 0),
                Geometry.firstHitOver(high, low, PAD_MARK, 2.6, 0, HALF, FLOOR), EPS,
                "a move wholly inside the column: where it enters the sphere, as before");
        assertEquals(Geometry.firstHit(high, low, PAD_MARK, 2.6, 0.5),
                Geometry.firstHitOver(high, low, PAD_MARK, 2.6, 0.5, HALF, FLOOR), EPS, "part-way along, as before");
        assertTrue(Double.isNaN(Geometry.firstHitOver(high, low, PAD_MARK, 2.6, 1.5, HALF, FLOOR)),
                "past the end of the move");
    }

    @Test
    void underTheColumnsFloorNothingTouches() {
        Point under = new Point(4141.0, 169.95, 4154.5);
        assertTrue(Double.isNaN(Geometry.firstHitOver(under, under, PAD_MARK, 2.6, 0, HALF, FLOOR)),
                "standing still over the pad but 0.05 under its top: nothing");
        Point on = new Point(4141.0, 170.0, 4154.5);
        assertEquals(0.0, Geometry.firstHitOver(on, on, PAD_MARK, 2.6, 0, HALF, FLOOR), EPS,
                "standing on the top: touched at once");
        Point justUnder = new Point(4141.0, 169.9999, 4154.5);
        assertTrue(Double.isNaN(Geometry.firstHitOver(justUnder, justUnder, PAD_MARK, 2.6, 0, HALF, FLOOR)),
                "a ten-thousandth under: the line has no hair (that is standsOn's, for where a move ends)");
        Point beside = new Point(4141.33, 170.0, 4154.5);
        assertTrue(Double.isNaN(Geometry.firstHitOver(beside, beside, PAD_MARK, 2.6, 0, HALF, FLOOR)),
                "standing still 0.33 past the pad's edge, on its top's height: beside the column, nothing");
    }

    @Test
    void aMoveStoppedFlatAgainstThePadsSideFromJustBesideItNeverTouches() {
        // the box 0.03 clear of the face and the feet 0.12 over the top, then flush against it 0.28 under
        Point a = new Point(4141.33, 170.12, 4154.5);
        Point b = new Point(4141.30, 169.72, 4154.5);
        assertEquals(1 / 3.0, Geometry.firstHitOver(a, b, PAD_MARK, 2.6, 0, HALF + 0.02, FLOOR - 0.02), 1e-6,
                "with a 0.02 band past the edge the line crosses it at y 169.987, above that floor: it touched");
        assertTrue(Double.isNaN(Geometry.firstHitOver(a, b, PAD_MARK, 2.6, 0, HALF, FLOOR)),
                "the exact column: the line is in it only at its end, under the top: nothing");
        assertFalse(Geometry.standsOn(b, PAD_MARK, 2.6, HALF, HAIR), "nor is that end a stand");
    }

    @Test
    void aStandOnThePadMayBeAHairOffButOnlyAtItsTopsHeight() {
        assertTrue(Geometry.standsOn(new Point(4141.3004, 169.99999, 4154.5), PAD_MARK, 2.6, HALF, HAIR),
                "a Bedrock stand on the edge, a float step past it and under the top: on the pad");
        assertTrue(Geometry.standsOn(new Point(4141.3004, 170.0, 4156.3004), PAD_MARK, 2.6, HALF, HAIR),
                "and on its corner");
        assertTrue(Geometry.standsOn(new Point(4139.5, 170.0, 4154.5), PAD_MARK, 2.6, HALF, HAIR), "its middle");
        assertFalse(Geometry.standsOn(new Point(4141.302, 170.0, 4154.5), PAD_MARK, 2.6, HALF, HAIR),
                "two hairs past the edge: beside it");
        assertFalse(Geometry.standsOn(new Point(4141.3004, 170.3, 4154.5), PAD_MARK, 2.6, HALF, HAIR),
                "a hair past the edge in the air beside it (a jump falling past): not a stand");
        assertFalse(Geometry.standsOn(new Point(4141.3004, 169.998, 4154.5), PAD_MARK, 2.6, HALF, HAIR),
                "a hair past the edge, falling past it two hairs under the top: not a stand");
        assertFalse(Geometry.standsOn(new Point(4141.0, 170.0, 4154.5), PAD_MARK, 1.0, HALF, HAIR),
                "on the pad but outside the sphere: the sphere still bounds it");
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
