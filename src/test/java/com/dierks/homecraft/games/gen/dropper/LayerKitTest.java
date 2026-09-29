package com.dierks.homecraft.games.gen.dropper;

import com.dierks.homecraft.games.gen.api.GenRandom;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The layer templates (EVENTS-DROPPER-SPEC §B.1.3, §B.1.5 step 3): which blocks of an 11 x 11 plate
 * start solid before the planner cuts the proven path through them.
 */
class LayerKitTest {

    private static final int N = DropperGeometry.INSIDE;

    @Test
    void plateTwinAndDecoyStartWallToWall() {
        for (LayerKit.Template t : new LayerKit.Template[]{LayerKit.Template.PLATE, LayerKit.Template.TWIN,
                LayerKit.Template.DECOY}) {
            assertEquals(N * N, LayerKit.solidCount(new LayerKit.Shape(t, 0, 0)),
                    t + " starts as a whole plate: its holes are cut by the planner");
        }
    }

    @Test
    void aRingIsACentrePlateWithAnOpenBorder() {
        LayerKit.Shape ring = new LayerKit.Shape(LayerKit.Template.RING, 2, 0);
        assertFalse(ring.solid(0, 5), "the border along the wall is open");
        assertFalse(ring.solid(1, 5), "two wide");
        assertTrue(ring.solid(2, 5), "then the centre plate");
        assertTrue(ring.solid(8, 8), "to 2 from the far wall");
        assertFalse(ring.solid(9, 8), "and open again");
        assertEquals(7 * 7, LayerKit.solidCount(ring), "a 7 x 7 centre");
        assertEquals("RING/2", ring.describe(), "for the admin line");
    }

    @Test
    void barsAreTwoSolidThenOneOpenAcrossTheShaft() {
        LayerKit.Shape bars = new LayerKit.Shape(LayerKit.Template.BARS, 0, 0);
        assertTrue(bars.solid(0, 3) && bars.solid(1, 3), "two solid");
        assertFalse(bars.solid(2, 3), "one open");
        assertTrue(bars.solid(2, 3) == bars.solid(2, 7), "running the whole way along z");
        assertEquals("BARS/x", bars.describe(), "bars stepping along x");
        LayerKit.Shape other = new LayerKit.Shape(LayerKit.Template.BARS, 1, 0);
        assertFalse(other.solid(3, 2), "the other axis: open where j is 2");
        assertEquals("BARS/z", other.describe(), "stepping along z");
    }

    @Test
    void aCrossIsTwoThreeWideBarsWithOpenQuarters() {
        LayerKit.Shape cross = new LayerKit.Shape(LayerKit.Template.CROSS, 5, 5);
        assertTrue(cross.solid(4, 0) && cross.solid(6, 10), "the bar along z, three wide round 5");
        assertTrue(cross.solid(0, 4) && cross.solid(10, 6), "and the bar along x");
        assertFalse(cross.solid(1, 1), "an open quarter");
        assertEquals(2 * 3 * N - 9, LayerKit.solidCount(cross), "two bars of 3 x 11 sharing a 3 x 3 middle");
    }

    @Test
    void aCheckerIsTwoByTwoSquaresByTurns() {
        LayerKit.Shape c = new LayerKit.Shape(LayerKit.Template.CHECKER, 0, 0);
        assertTrue(c.solid(0, 0) && c.solid(1, 1), "the first square solid");
        assertFalse(c.solid(2, 0) || c.solid(0, 3), "its neighbours open");
        assertTrue(c.solid(2, 2), "and the next diagonal solid");
        LayerKit.Shape flipped = new LayerKit.Shape(LayerKit.Template.CHECKER, 1, 0);
        for (int i = 0; i < N; i++) {
            for (int j = 0; j < N; j++) {
                assertTrue(c.solid(i, j) != flipped.solid(i, j),
                        "the other phase is the exact opposite at " + i + "," + j);
            }
        }
    }

    @Test
    void theDrawnParametersStayInsideTheShaftAndFollowTheSeed() {
        GenRandom a = new GenRandom(42);
        GenRandom b = new GenRandom(42);
        for (int k = 0; k < 200; k++) {
            for (LayerKit.Template t : LayerKit.Template.values()) {
                LayerKit.Shape s = LayerKit.shape(t, a);
                assertEquals(s, LayerKit.shape(t, b), "the same stream draws the same shape");
                switch (t) {
                    case RING -> assertTrue(s.a() >= 1 && s.a() <= 2, "a ring's border is 1 or 2 wide: " + s);
                    case CROSS -> assertTrue(s.a() >= 3 && s.a() <= 7 && s.b() >= 3 && s.b() <= 7,
                            "a cross's middle keeps its bars off the walls: " + s);
                    case BARS -> assertTrue(s.a() >= 0 && s.a() <= 1 && s.b() >= 0 && s.b() <= 2, "bars: " + s);
                    default -> assertTrue(LayerKit.solidCount(s) > 0, t + " leaves something solid");
                }
            }
        }
    }
}
