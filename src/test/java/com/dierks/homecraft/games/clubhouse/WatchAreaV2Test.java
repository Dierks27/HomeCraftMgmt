package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.MountainRuns;
import com.dierks.homecraft.games.trial.MountainRunsV2;
import com.dierks.homecraft.games.trial.Point;
import com.dierks.homecraft.games.trial.RaceStand;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Watching a Mountain Run v2 live (MOUNTAIN-V2-SPEC §12, red-team F07), pure.
 *
 * <p>Pinned here: a watcher of a v2 run is first put 6 over its viewing stand at the bottom, looking north up
 * the mountain, where the riders come down to the finish (the summit is 500+ blocks away, beyond any view
 * distance, so the view promises only the lower face); the area is still the half grown by 8, so they can
 * fly anywhere in it; and the algo-3 run and a hand-built track keep their first view above the start.
 */
class WatchAreaV2Test {

    @Test
    void aV2WatcherStartsOverTheStandLookingNorth() {
        Course c = MountainRunsV2.road();
        Box half = MountainRunsV2.HALF;
        WatchArea a = WatchArea.forCourse(c, half);
        Point stand = RaceStand.of(c, half);
        assertEquals(stand.x(), a.viewX(), "over the stand");
        assertEquals(stand.z(), a.viewZ(), "over the stand");
        assertEquals(stand.y() + WatchArea.STAND_UP, a.viewY(), "6 over where its players stand");
        assertEquals(6, WatchArea.STAND_UP, "§12: 6 over the stand");
        assertEquals(180f, a.viewYaw(), "looking north, up the mountain");
        assertEquals(half.expand(WatchArea.GEN_GROW), a.box(), "the area is still the half grown by 8");
        assertTrue(a.contains(a.viewX(), a.viewY(), a.viewZ()), "and the first view is inside it");
        assertTrue(a.contains(c.start().x(), c.start().y(), c.start().z()), "the summit's start is in it too, to fly to");
    }

    @Test
    void theSlalomIsWatchedFromTheSameStand() {
        WatchArea road = WatchArea.forCourse(MountainRunsV2.road(), MountainRunsV2.HALF);
        WatchArea slalom = WatchArea.forCourse(MountainRunsV2.slalom(), MountainRunsV2.HALF);
        assertEquals(road, slalom, "the same view for either style (the stand is at the bottom of both)");
    }

    @Test
    void everyOtherBoatCourseKeepsItsViewAboveTheStart() {
        Course v3 = MountainRuns.medium();
        Box half = Box.sized(4480, 160, 4352, 128, 16, 128); // the hand-made spiral's half
        WatchArea a = WatchArea.forCourse(v3, half);
        assertEquals(v3.start().yaw(), a.viewYaw(), "the spiral: looking along the start, as before");
        assertEquals(v3.start().x(), a.viewX(), "above the start");
        Course hand = MountainRunsV2.of(null);
        WatchArea h = WatchArea.forCourse(hand, null);
        assertEquals(hand.start().yaw(), h.viewYaw(), "a hand-built track: as before");
        assertEquals(hand.start().y() + WatchArea.VIEW_UP, h.viewY(), "4 above its start");
        WatchArea noHalf = WatchArea.forCourse(MountainRunsV2.road(), null);
        assertEquals(MountainRunsV2.road().start().yaw(), noHalf.viewYaw(),
                "a v2 run whose half the engine doesn't know: the box round its marks, above the start");
    }
}
