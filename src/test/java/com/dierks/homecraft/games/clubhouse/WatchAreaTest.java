package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.golf.GolfCourse;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.Tier;
import com.dierks.homecraft.games.trial.TrialKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a watcher may fly (CLUBHOUSE-SPEC §10): for every kind of course, with nothing built. A
 * Fresh, Classic or kept course's slot or plot box grown by 8; a hand-built course's start,
 * checkpoints and finish grown by 16 (a golf course's holes too); a Race Night track as its own
 * course. The first view is above the start, inside; the keeper's nearest point is inside.
 */
class WatchAreaTest {

    private static Course course(TrialKind kind, Course.Spot start, List<Course.Mark> cps, Course.Mark finish) {
        return new Course("c", kind, "C", Tier.EASY, "games", start, cps, finish, null, null, true, false, 1);
    }

    @Test
    void aFreshParkourRingsBoatOrDropperCourseIsItsSlotsBoxGrownByEight() {
        for (Slots.Def def : List.of(Slots.DAILY_PARKOUR_MEDIUM, Slots.SKY_RINGS, Slots.ICE_BOAT, Slots.FRESH_DROPPER,
                Slots.CLASSIC_PARKOUR)) {
            Box half = def.half(def.origin()[0], def.origin()[1], def.origin()[2], 'A');
            Course c = course(TrialKind.PARKOUR, new Course.Spot(half.minX() + 3, half.minY() + 2, half.minZ() + 3, 0, 0),
                    List.of(), new Course.Mark(half.minX() + 10, half.minY() + 2, half.minZ() + 10, 2));
            WatchArea a = WatchArea.forCourse(c, half);
            assertEquals(half.expand(8), a.box(), def.id() + ": its half grown by 8");
            assertTrue(a.contains(a.viewX(), a.viewY(), a.viewZ()), def.id() + ": the first view is inside");
            assertTrue(a.contains(half.minX() - 7.5, half.minY(), half.minZ()), "8 round it is inside");
            assertFalse(a.contains(half.minX() - 9, half.minY(), half.minZ()), "9 out is not");
        }
    }

    @Test
    void aHandBuiltCourseIsItsStartCheckpointsAndFinishGrownBySixteen() {
        Course parkour = course(TrialKind.PARKOUR, new Course.Spot(0.5, 65, 0.5, 0, 0),
                List.of(new Course.Mark(10, 70, 20, 3), new Course.Mark(-5, 60, 40, 3)), new Course.Mark(0, 80, 60, 3));
        assertEquals(new Box(-5 - 16, 60 - 16, -16, 10 + 16, 80 + 16, 60 + 16),
                WatchArea.forCourse(parkour, null).box(), "the box round every mark, grown by 16");
        Course elytra = course(TrialKind.ELYTRA, new Course.Spot(0, 150, 0, 0, 0),
                List.of(new Course.Mark(0, 120, 100, 5)), new Course.Mark(0, 90, 200, 5));
        WatchArea rings = WatchArea.forCourse(elytra, null);
        assertTrue(rings.contains(0, 90, 216) && !rings.contains(0, 90, 217.5), "Sky Rings: to 16 past the last ring");
        Course boat = course(TrialKind.BOAT, new Course.Spot(30, 65, -4, 0, 0),
                List.of(new Course.Mark(30, 65, 30, 5), new Course.Mark(-30, 65, 30, 5), new Course.Mark(-30, 65, -30, 5)),
                new Course.Mark(30, 65, 0, 5));
        WatchArea loop = WatchArea.forCourse(boat, null);
        assertTrue(loop.contains(0, 65, 0) && loop.contains(-45, 70, 45), "a boat track's whole loop");
        Course dropper = course(TrialKind.DROPPER, new Course.Spot(5, 200, 5, 0, 0), List.of(), new Course.Mark(5, 140, 5, 2));
        WatchArea shaft = WatchArea.forCourse(dropper, null);
        assertTrue(shaft.contains(5, 130, 5) && shaft.contains(5, 215, 5), "a Dropper's shaft, top to bottom: look in");
        assertEquals(69.0, WatchArea.forCourse(parkour, null).viewY(), 1e-9, "the first view 4 above the start");
        assertThrows(IllegalArgumentException.class, () -> WatchArea.forCourse(course(TrialKind.PARKOUR, null,
                List.of(), null), null), "a course needs a start");
    }

    @Test
    void aGolfCourseIsItsSlotOrItsHolesGrownBySixteen() {
        GolfCourse.Hole h1 = new GolfCourse.Hole(new GolfCourse.Tee(10.5, 64, -3.5, 90f), new GolfCourse.Spot(18, 63, -3),
                3, new GolfCourse.Spot(8, 63, -6), new GolfCourse.Spot(20, 66, 0));
        GolfCourse.Hole h2 = new GolfCourse.Hole(new GolfCourse.Tee(40.5, 64, 30.5, 0f), new GolfCourse.Spot(40, 63, 50),
                3, null, null);
        GolfCourse g = new GolfCourse("meadow", "Meadow", "games", true, 1, List.of(h1, h2));
        assertEquals(new Box(8 - 16, 63 - 16, -6 - 16, 40 + 16, 66 + 16, 50 + 16), WatchArea.forGolf(g, null).box(),
                "every tee, cup and bound corner, grown by 16");
        Box half = Slots.DAILY_GOLF.half(4864, 160, 4096, 'B');
        assertEquals(half.expand(8), WatchArea.forGolf(g, half).box(), "Fresh golf: its half grown by 8");
    }

    @Test
    void theKeepersNearestPointIsInsideAndAPointInsideStays() {
        WatchArea a = WatchArea.view(new Box(0, 60, 0, 31, 90, 31), 5, 65, 5, 0f);
        double[] in = a.nearestInside(5.5, 70, 5.5);
        assertEquals(List.of(5.5, 70.0, 5.5), List.of(in[0], in[1], in[2]), "inside: where they are");
        double[] out = a.nearestInside(100, 200, -50);
        assertTrue(a.contains(out[0], out[1], out[2]), "outside: back at the nearest point inside " + List.of(out[0],
                out[1], out[2]));
        assertEquals(31.7, out[0], 1e-9, "at the edge");
    }
    @Test
    void theAreaIsKeptInsideTheWorldsHeightsWithItsViewInsideToo() {
        WatchArea fits = WatchArea.view(new Box(0, 60, 0, 40, 100, 40), 10, 65, 10, 0f);
        assertTrue(fits == fits.within(-64, 320), "an area that fits is itself");
        WatchArea deep = WatchArea.view(new Box(0, -90, 0, 40, 330, 40), 10, -85, 10, 0f);
        WatchArea held = deep.within(-64, 320);
        assertEquals(new Box(0, -64, 0, 40, 319, 40), held.box(), "feet from the floor, head under the ceiling");
        assertTrue(held.contains(held.viewX(), held.viewY(), held.viewZ()), "the first view moved inside too");
        assertEquals(-64, held.viewY(), 1e-9, "at the floor, not in the void");
        WatchArea outside = WatchArea.view(new Box(0, -200, 0, 4, -100, 4), 1, -150, 1, 0f);
        assertTrue(outside == outside.within(-64, 320), "a box the world can't hold at all is left as it is");
        assertEquals(WatchArea.Keep.LET, fits.keep(1, 61, 1, 2, 61, 2), "inside to inside");
        assertEquals(WatchArea.Keep.CANCEL, fits.keep(1, 61, 1, -5, 61, 2), "inside to outside: cancelled");
        assertEquals(WatchArea.Keep.PULL, fits.keep(-6, 61, 1, -5, 61, 2), "outside to outside: pulled back in");
        assertTrue(fits.putBack(1, 61, 1) == null, "nothing to put back inside");
    }
}
