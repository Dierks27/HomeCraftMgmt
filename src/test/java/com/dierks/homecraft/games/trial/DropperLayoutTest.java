package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.gen.dropper.DropMarks;
import com.dierks.homecraft.games.gen.dropper.DropperPlanner;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.dierks.homecraft.games.trial.DropperCourses.at;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Dropper as Time Trials reads it (EVENTS-DROPPER-SPEC §B.1.7), with no server.
 *
 * <p>Pinned here: the targets alternate pool, ledge, pool... and the finish is the last pool; a run
 * is on the level whose ledge it last reached (a pool reached means the next level); a bonk goes back
 * to the current level's ledge; the pool box comes from the pool mark alone; nobody standing on the
 * rim or the floor round a pool reaches its mark, only the water does; where a hop puts a runner (the
 * ledge, facing its pool, looking down); a malformed dropper never opens (its problems join the
 * course's); a kept dropper refuses the geometry verbs and nothing else; and every other kind is
 * untouched by all of it.
 */
class DropperLayoutTest {

    private final Course c = DropperCourses.hand();

    @Test
    void theTargetsAlternatePoolLedgePoolAndTheFinishIsTheLastPool() {
        List<Course.Mark> targets = c.targets();
        assertEquals(5, targets.size(), "three pools and the two later ledges");
        for (int i = 0; i < targets.size(); i++) {
            boolean pool = i % 2 == 0;
            assertEquals(pool, DropperLayout.isPool(i), "target " + i + " is a " + (pool ? "pool" : "ledge"));
        }
        assertEquals(3, DropperLayout.levels(c), "three levels");
        assertEquals(List.of(0, 1, 1, 2, 2), List.of(0, 1, 2, 3, 4).stream().map(DropperLayout::levelOf).toList(),
                "pool 1 is level 1's, ledge 2 and pool 2 level 2's, ledge 3 and pool 3 level 3's");
        assertEquals(DropperCourses.POOL_3, DropperLayout.poolOf(c, 2), "the last level's pool is the finish");
        assertEquals(DropperCourses.POOL_1, DropperLayout.poolOf(c, 0), "the first pool is the first checkpoint");
        assertEquals(DropperCourses.LEDGE_2, DropperLayout.ledgeOf(c, 1), "the second ledge the second checkpoint");
        Course.Mark first = DropperLayout.ledgeOf(c, 0);
        assertEquals(List.of(10.5, 100.0, 10.5), List.of(first.x(), first.y(), first.z()),
                "level 1's ledge is made from the start spot");
    }

    @Test
    void aBonkGoesBackToTheLedgeOfTheLevelTheRunIsOn() {
        assertEquals(0, DropperLayout.currentLevel(-1), "nothing reached yet: level 1");
        assertEquals(1, DropperLayout.currentLevel(0), "pool 1 reached: on its way to level 2");
        assertEquals(1, DropperLayout.currentLevel(1), "ledge 2 reached: level 2");
        assertEquals(2, DropperLayout.currentLevel(2), "pool 2 reached: level 3");
        assertEquals(DropperLayout.ledgeOf(c, 0), DropperLayout.backTo(c, -1), "a bonk on level 1: its ledge");
        assertEquals(DropperCourses.LEDGE_2, DropperLayout.backTo(c, 0), "a bonk after the first splash: level 2's");
        assertEquals(DropperCourses.LEDGE_2, DropperLayout.backTo(c, 1), "and on level 2 itself");
        assertEquals(DropperCourses.LEDGE_3, DropperLayout.backTo(c, 3), "level 3's");
        assertEquals(DropperCourses.LEDGE_3, DropperLayout.backTo(c, 4), "never past the last level");
        assertEquals(2, DropperLayout.levelNow(c, 5), "a finished run is on the last level");
        assertEquals(0, DropperLayout.levelNow(c, 0), "a run that reached nothing is on level 1");
    }

    @Test
    void thePoolBoxComesFromThePoolMarkAlone() {
        assertArrayEquals(new double[]{7, 65, 7, 18, 68.5, 18}, DropperLayout.poolBox(DropperCourses.POOL_1), 1e-9,
                "Easy: 11 x 11 of water from its bottom to half a block over the surface at 68");
        assertArrayEquals(new double[]{34, 49, 10, 39, 52.5, 15}, DropperLayout.poolBox(DropperCourses.POOL_3), 1e-9,
                "Hard: 5 x 5");
        assertTrue(DropperLayout.inPool(DropperCourses.POOL_3, at(34.1, 52.4, 14.9)), "a corner of the water is in it");
        assertFalse(DropperLayout.inPool(DropperCourses.POOL_3, at(33.9, 52.0, 12.5)), "just past its edge isn't");
        assertFalse(DropperLayout.inPool(DropperCourses.POOL_3, at(36.5, 52.6, 12.5)), "nor over it");
        assertFalse(DropperLayout.inPool(null, at(0, 0, 0)), "no pool, no splash");
    }

    @Test
    void standingOnTheRimOrTheFloorRoundAPoolNeverReachesItsMarkOnlyTheWaterDoes() {
        for (Course.Mark pool : List.of(DropperCourses.POOL_1, DropperCourses.POOL_2, DropperCourses.POOL_3)) {
            double h = DropMarks.halfWidth(pool);
            double s = DropMarks.surface(pool);
            // the feet of someone standing anywhere on the floor round the pool (its top is the surface)
            for (double dx = -h - 3; dx <= h + 3; dx += 0.25) {
                for (double dz = -h - 3; dz <= h + 3; dz += 0.25) {
                    boolean overWater = Math.abs(dx) <= h && Math.abs(dz) <= h;
                    if (overWater) {
                        continue;
                    }
                    for (double lift = 0; lift <= 0.6; lift += 0.2) {
                        Point feet = at(pool.x() + dx, s + lift, pool.z() + dz);
                        assertFalse(pool.contains(feet), "standing on the rim at " + feet + " doesn't reach the pool");
                        assertFalse(DropperLayout.inPool(pool, feet), "nor its box");
                    }
                }
            }
            assertTrue(pool.contains(at(pool.x(), s - 1, pool.z())), "a body in the middle of the water does");
            assertTrue(DropperLayout.inPool(pool, at(pool.x() + h - 0.1, s - 0.5, pool.z() + h - 0.1)),
                    "and the box has the corners of the water");
        }
    }

    @Test
    void aHopPutsTheRunnerOnTheLedgeFacingItsPoolLookingDown() {
        double[] one = DropperLayout.stand(c, 0);
        assertArrayEquals(new double[]{10.5, 100, 10.5, 0, 30}, one, 1e-9, "level 1: the start spot and its facing");
        double[] two = DropperLayout.stand(c, 1);
        assertEquals(22.5, two[0], 1e-9, "level 2: its ledge");
        assertEquals(100, two[1], 1e-9, "at the ledge top");
        assertEquals(DropMarks.facing(DropperCourses.LEDGE_2, DropperCourses.POOL_2), two[3], 1e-6, "facing its pool");
        assertEquals(DropperPlanner.START_PITCH, two[4], 1e-6, "looking down into the shaft as the start does");
        assertNull(DropperLayout.stand(c, 3), "there is no level 4");
        Course planned = DropperCourses.planned("EEMMH", 1);
        for (int level = 0; level < DropperLayout.levels(planned); level++) {
            double[] st = DropperLayout.stand(planned, level);
            assertNotNull(st, "every level of a real dropper has a ledge to stand on");
            assertTrue(DropperLayout.ledgeOf(planned, level).contains(at(st[0], st[1], st[2])),
                    "and standing there is inside its ledge mark, so the next move reaches it");
        }
    }

    @Test
    void aMalformedDropperNeverOpens() {
        assertEquals(List.of(), DropperLayout.problems(c), "the hand-made dropper is well formed");
        for (String mix : List.of("EEE", "EEMMH", "HHHHH")) {
            Course real = DropperCourses.planned(mix, 2);
            assertEquals(List.of(), real.problems(List.of("games")), mix + ": a planned dropper opens");
            assertEquals(mix.length(), DropperLayout.levels(real), mix + ": one level a letter");
        }
        Course odd = new Course("fresh_dropper", TrialKind.DROPPER, "Dropper", Tier.EASY, "games", DropperCourses.START,
                List.of(DropperCourses.POOL_1), DropperCourses.POOL_2, 44.0, null, true, false, 1);
        assertFalse(odd.problems(List.of("games")).isEmpty(), "checkpoints that don't come in pairs: never opens");
        Course swapped = new Course("fresh_dropper", TrialKind.DROPPER, "Dropper", Tier.EASY, "games",
                DropperCourses.START, List.of(DropperCourses.LEDGE_2, DropperCourses.POOL_1, DropperCourses.POOL_2,
                DropperCourses.LEDGE_3), DropperCourses.POOL_3, 44.0, null, true, false, 1);
        assertFalse(DropperLayout.problems(swapped).isEmpty(), "marks out of order: refused");
        Course high = new Course("fresh_dropper", TrialKind.DROPPER, "Dropper", Tier.EASY, "games",
                DropperCourses.START, c.checkpoints(), c.finish(), 60.0, null, true, false, 1);
        assertFalse(DropperLayout.problems(high).isEmpty(), "a fall height above a pool: refused");
        List<Course> both = new ArrayList<>(List.of(c, odd));
        assertEquals(List.of(c), TimeTrials.open(both, w -> true, com.dierks.homecraft.games.GeneratedCourses.NONE),
                "only the well-formed dropper is open");
    }

    @Test
    void aKeptDropperRefusesTheGeometryVerbsAndNothingElse() {
        for (String verb : List.of("start", "checkpoint", "checkpoints", "cp", "finish", "fall", "minseconds",
                " START ")) {
            assertEquals(DropperLayout.GEOMETRY_REFUSED, DropperLayout.editRefusal(c, verb),
                    verb + " would move a proven ledge or pool: refused");
        }
        for (String verb : List.of("info", "tp", "test", "name", "tier", "enable", "disable", "feature", "delete")) {
            assertNull(DropperLayout.editRefusal(c, verb), verb + " still works on a kept dropper");
        }
        assertNull(DropperLayout.editRefusal(DropperCourses.asParkour(), "start"), "a parkour course's start moves");
        assertNull(DropperLayout.editRefusal(null, "start"), "no course: nothing to say");
    }

    @Test
    void everyOtherKindIsUntouched() {
        Course parkour = DropperCourses.asParkour();
        assertFalse(DropperLayout.is(parkour), "a parkour course isn't a dropper");
        assertEquals(List.of(), DropperLayout.problems(parkour), "the dropper rules say nothing about it");
        assertEquals(List.of(), parkour.problems(List.of("games")), "its own problems are as they were");
        assertEquals(List.of(parkour), TimeTrials.open(List.of(parkour), w -> true,
                com.dierks.homecraft.games.GeneratedCourses.NONE), "and it opens as it did");
        assertEquals("5 checkpoints, then the finish", TrialText.route(new Course("x", TrialKind.PARKOUR, "X",
                Tier.EASY, "games", DropperCourses.START, List.of(DropperCourses.POOL_1, DropperCourses.LEDGE_2,
                DropperCourses.POOL_2, DropperCourses.LEDGE_3, DropperCourses.POOL_2), DropperCourses.POOL_3, null,
                null, true, false, 1)), "a parkour screen still counts checkpoints");
        assertEquals("3 levels, down to the water", TrialText.route(c), "a dropper's says levels");
    }
}
