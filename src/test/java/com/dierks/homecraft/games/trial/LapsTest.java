package com.dierks.homecraft.games.trial;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The laps of a race (EVENTS-DROPPER-SPEC §A.4.4): a course stores no lap count, so the natural lap
 * is read off its checkpoints; a loop may be raced for more laps (up to 5 and 64 targets), and a
 * point-to-point course is always one lap.
 */
class LapsTest {

    /** A loop of radius 30 round (0, 0) with {@code perLap} checkpoints, stored {@code laps} times. */
    static Course loop(int perLap, int laps) {
        List<Course.Mark> lap = new ArrayList<>();
        for (int i = 1; i <= perLap; i++) {
            double a = 2 * Math.PI * i / (perLap + 1);
            lap.add(new Course.Mark(30 * Math.cos(a), 65, 30 * Math.sin(a), 5));
        }
        List<Course.Mark> all = new ArrayList<>();
        for (int l = 0; l < laps; l++) {
            all.addAll(lap);
        }
        return new Course("loop", TrialKind.BOAT, "Loop", Tier.EASY, "games", new Course.Spot(30, 65, -4, 0, 0),
                all, new Course.Mark(30, 65, 0, 5), 60.0, 20 * laps, true, false, 1);
    }

    /** A straight course: start, three checkpoints ahead, the finish 60 blocks on. */
    static Course straight() {
        List<Course.Mark> cps = List.of(new Course.Mark(0, 65, 15, 3), new Course.Mark(0, 65, 30, 3),
                new Course.Mark(0, 65, 45, 3));
        return new Course("run", TrialKind.PARKOUR, "Run", Tier.EASY, "games", new Course.Spot(0, 65, 0, 0, 0), cps,
                new Course.Mark(0, 65, 60, 3), null, 10, true, false, 1);
    }

    @Test
    void aStoredTwoLapLoopIsFound() {
        Course c = loop(6, 2);
        assertEquals(6, Laps.period(c.checkpoints()), "Fresh Ice Boat stores its lap twice: the lap is 6 marks");
        assertEquals(2, Laps.natural(c), "so the course's own race is 2 laps");
        assertTrue(Laps.loop(c), "its finish is by its start and it has 3 or more checkpoints a lap");
        assertEquals(c.checkpoints().subList(0, 6), Laps.lap(c), "one lap is the first 6 marks");
        assertEquals(1, Laps.natural(loop(6, 1)), "a loop stored once is one lap");
        assertEquals(3, Laps.natural(loop(4, 3)), "and three times, three");
    }

    @Test
    void marksThatOnlyNearlyRepeatAreNotALap() {
        Course c = loop(5, 2);
        List<Course.Mark> cps = new ArrayList<>(c.checkpoints());
        Course.Mark m = cps.get(7);
        cps.set(7, new Course.Mark(m.x() + 0.5, m.y(), m.z(), m.radius()));
        assertEquals(10, Laps.period(cps), "a second lap that differs anywhere is not a repeat");
        assertEquals(0, Laps.period(List.of()), "no checkpoints: no period");
        assertEquals(1, Laps.natural(new Course("x", TrialKind.PARKOUR, "X", Tier.EASY, "games",
                new Course.Spot(0, 65, 0, 0, 0), List.of(), new Course.Mark(0, 65, 9, 2), null, null, true, false, 1)),
                "a course with no checkpoints is one lap");
    }

    @Test
    void aLoopExpandsToNLapsWithItsShortestTimeScaled() {
        Course c = loop(6, 2);
        Course.Spot grid = new Course.Spot(29, 65, -8, 0, 0);
        Laps.Raced three = Laps.raced(c, grid, 3);
        assertNull(three.problem(), "3 laps of a loop is fine");
        assertEquals(3, three.laps(), "three laps");
        assertEquals(18, three.course().checkpoints().size(), "the lap's 6 marks, three times");
        assertEquals(Laps.lap(c), three.course().checkpoints().subList(12, 18), "the third lap is the same lap");
        assertEquals(grid, three.course().start(), "racers start from their grid spot");
        assertEquals(40, c.minSeconds(), "the course's own shortest time, for its 2 laps");
        assertEquals(60, three.course().minSeconds(), "scaled by 3 laps over 2: the fair-play check judges it as ever");
        assertEquals(c.id(), three.course().id(), "the same course, so a party race's run counts on it");
        assertEquals(c.finish(), three.course().finish(), "the same line");

        Laps.Raced own = Laps.raced(c, grid, 0);
        assertEquals(2, own.laps(), "0 races the course's own laps");
        assertEquals(c.checkpoints(), own.course().checkpoints(), "unchanged");
        assertEquals(c.minSeconds(), own.course().minSeconds(), "and its own shortest time");
        assertEquals(Laps.raced(c, grid, 2).course(), own.course(), "asking for its own count is the same");
        assertEquals(c, Laps.raced(c, null, 0).course(), "no grid spot: the course itself");

        Laps.Raced one = Laps.raced(c, grid, 1);
        assertEquals(6, one.course().checkpoints().size(), "one lap of a two-lap loop");
        assertEquals(20, one.course().minSeconds(), "half the shortest time");
    }

    @Test
    void aRaceStaysWithinSixtyFourTargetsAndFiveLaps() {
        Course big = loop(15, 2);
        assertNotNull(Laps.raced(big, null, 4).course(), "4 laps of 15 is 60 marks and the finish: 61 targets");
        Laps.Raced tooMany = Laps.raced(loop(16, 2), null, 4);
        assertNull(tooMany.course(), "4 laps of 16 would be 65 targets");
        assertTrue(tooMany.problem().contains("65 targets"), "and it says so: " + tooMany.problem());
        Laps.Raced six = Laps.raced(loop(4, 2), null, 6);
        assertNull(six.course(), "a race is at most 5 laps");
        assertTrue(six.problem().contains("at most 5"), six.problem());
    }

    @Test
    void aPointToPointCourseStaysAtOneLap() {
        Course c = straight();
        assertFalse(Laps.loop(c), "its finish is 60 blocks from its start");
        assertEquals(1, Laps.natural(c), "one lap");
        Laps.Raced two = Laps.raced(c, null, 2);
        assertNull(two.course(), "it can't be raced for 2 laps");
        assertTrue(two.problem().contains("only a loop"), two.problem());
        assertEquals(1, Laps.raced(c, null, 0).laps(), "its own race is one lap");
        assertEquals(1, Laps.raced(c, null, 1).laps(), "and asking for one is fine");
        Course none = c.withFinish(null);
        assertNull(Laps.raced(none, null, 0).course(), "a course with no finish can't be raced");
    }

    @Test
    void theLapARacerIsOnReadsOffTheirTargets() {
        assertEquals(1, Laps.lapOf(0, 6, 2), "at the start: lap 1");
        assertEquals(1, Laps.lapOf(5, 6, 2), "five checkpoints in: still lap 1");
        assertEquals(2, Laps.lapOf(6, 6, 2), "the lap's last checkpoint behind: lap 2");
        assertEquals(2, Laps.lapOf(13, 6, 2), "past the last checkpoint: never more than the laps");
        assertEquals(1, Laps.lapOf(3, 0, 1), "a one-lap course is always lap 1");
    }
}
