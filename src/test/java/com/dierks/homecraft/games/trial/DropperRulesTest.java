package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.gen.dropper.DropMarks;
import org.junit.jupiter.api.Test;

import static com.dierks.homecraft.games.trial.DropperCourses.at;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Dropper's splash and bonk rules (EVENTS-DROPPER-SPEC §B.1.7), pure, with no server.
 *
 * <p>Pinned here: a splash into a pool's corner counts even where the pool mark's sphere doesn't
 * reach, and its time is where the feet crossed into the box; a landing on the rim is never a
 * splash; two still ticks well under the ledge, in the shaft and out of the water are a landing (a
 * bonk), but one still tick (a jump's apex), standing on the ledge, floating in a pool or being
 * outside the shaft are not; and a bonk counts only while the clock runs, never during the hop or
 * the run's own teleport, and one fall seen twice is one bonk.
 */
class DropperRulesTest {

    private static final Course.Mark POOL = DropperCourses.POOL_3; // Hard: 5 x 5 at (36.5, 12.5), water at 52

    @Test
    void aCornerSplashCountsWhereThePoolMarksSphereDoesNotReach() {
        Point above = at(34.2, 60, 14.8); // over the water's corner
        Point in = at(34.2, 51.5, 14.8);
        assertFalse(POOL.contains(in), "the corner of the water is outside the pool mark's sphere");
        double t = DropperRules.splash(above, in, POOL);
        assertFalse(Double.isNaN(t), "but the fall into it is a splash");
        assertEquals((60 - 52.5) / (60 - 51.5), t, 1e-9, "where the feet crossed the box top, half a block over the water");
        assertEquals(1_000 + Math.round(t * 1_000), DropperRules.splashNanos(1_000, 2_000, t),
                "and its time is interpolated there");
        assertEquals(0, DropperRules.splash(at(36.5, 51, 12.5), at(36.5, 50, 12.5), POOL), 1e-9,
                "a move that starts in the water splashes at once");
    }

    @Test
    void aRimLandingIsNeverASplash() {
        double s = DropMarks.surface(POOL);
        assertTrue(Double.isNaN(DropperRules.splash(at(33.6, 60, 12.5), at(33.6, s, 12.5), POOL)),
                "falling onto the floor beside the pool is no splash");
        assertTrue(Double.isNaN(DropperRules.splash(at(33.6, s, 12.5), at(33.2, s, 12.5), POOL)),
                "nor walking along the rim");
        assertTrue(Double.isNaN(DropperRules.splash(at(36.5, 70, 16.0), at(36.5, 50, 16.0), POOL)),
                "nor a fall just past its far edge");
        assertTrue(Double.isNaN(DropperRules.splash(null, at(36.5, 50, 12.5), POOL)), "no move, no splash");
    }

    @Test
    void twoStillTicksUnderTheLedgeOutOfTheWaterAreALanding() {
        DropperRules.Landing l = new DropperRules.Landing();
        double ledge = 100;
        assertFalse(l.tick(80, ledge, true, false), "the first tick only starts the watch");
        assertFalse(l.tick(80, ledge, true, false), "one still tick is not yet a landing");
        assertTrue(l.tick(80.005, ledge, true, false), "two still ticks in a row are (a hair up is still)");
        assertFalse(l.tick(80, ledge, true, false), "the watch starts over after one");
    }

    @Test
    void aFallingBodyIsNeverALanding() {
        DropperRules.Landing l = new DropperRules.Landing();
        double y = 98.5;
        double vy = 0;
        for (int tick = 0; tick < 40; tick++) {
            assertFalse(l.tick(y, 100, true, false), "tick " + tick + " of a free fall");
            vy = (vy - 0.08) * 0.98;
            y += vy;
        }
    }

    @Test
    void aJumpsApexTheLedgeTheWaterAndOutsideTheShaftAreNotLandings() {
        DropperRules.Landing apex = new DropperRules.Landing();
        apex.tick(100.9, 100, true, false);
        assertFalse(apex.tick(101.25, 100, true, false), "rising");
        assertFalse(apex.tick(101.25, 100, true, false), "a jump's apex is one still tick, above the ledge anyway");
        assertFalse(apex.tick(101.0, 100, true, false), "then it falls");

        DropperRules.Landing ledge = new DropperRules.Landing();
        for (int i = 0; i < 5; i++) {
            assertFalse(ledge.tick(100, 100, true, false), "standing on the ledge is never a bonk");
        }
        DropperRules.Landing lip = new DropperRules.Landing();
        for (int i = 0; i < 5; i++) {
            assertFalse(lip.tick(98.6, 100, true, false), "nor a step just under the ledge top (above the 1.5 line)");
        }
        DropperRules.Landing water = new DropperRules.Landing();
        for (int i = 0; i < 5; i++) {
            assertFalse(water.tick(51.8, 100, true, true), "floating in a pool is never a bonk");
        }
        DropperRules.Landing out = new DropperRules.Landing();
        for (int i = 0; i < 5; i++) {
            assertFalse(out.tick(60, 100, false, false), "outside the shaft is none of the watch's business");
        }
        DropperRules.Landing reset = new DropperRules.Landing();
        reset.tick(80, 100, true, false);
        reset.tick(80, 100, true, false);
        reset.reset();
        assertFalse(reset.tick(80, 100, true, false), "after a teleport the watch starts over");
    }

    @Test
    void aBonkCountsOnlyWhileTheClockRunsAndNeverDuringTheHop() {
        assertTrue(DropperRules.counts(true, false, false, 100, 0), "a landing mid-run is a bonk");
        assertFalse(DropperRules.counts(false, false, false, 100, 0), "not before Go");
        assertFalse(DropperRules.counts(true, true, false, 100, 0), "not during the hop to the next ledge");
        assertFalse(DropperRules.counts(true, false, true, 100, 0), "not while the run's own teleport is on its way");
        assertFalse(DropperRules.counts(true, false, false, 100, 100 - DropperRules.BONK_GAP + 1),
                "one fall seen twice (the landing, then its damage event) is one bonk");
        assertTrue(DropperRules.counts(true, false, false, 100, 100 - DropperRules.BONK_GAP),
                "a new fall half a second later is another");
    }

    @Test
    void theShaftIsTheLedgesOwn() {
        Course.Mark ledge = DropperCourses.LEDGE_2;
        assertTrue(DropperRules.inShaft(ledge, at(24.5, 70, 12.5)), "under its own ledge");
        assertTrue(DropperRules.inShaft(ledge, at(22.5 + 11, 70, 10.5)), "to its far wall");
        assertFalse(DropperRules.inShaft(ledge, at(22.5 + 12, 70, 10.5)), "past it");
        assertFalse(DropperRules.inShaft(null, at(0, 0, 0)), "no ledge, no shaft");
    }

    @Test
    void theHopWaitsAQuarterSecond() {
        assertEquals(5, DropperRules.HOP_TICKS, "the splash title plays for 5 ticks before the next ledge");
        assertEquals(2, DropperRules.STILL_TICKS, "two still ticks");
        assertEquals(1.5, DropperRules.BELOW_LEDGE, 0, "at least 1.5 below the ledge top");
    }
}
