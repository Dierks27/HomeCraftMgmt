package com.dierks.homecraft.games.event;

import com.dierks.homecraft.games.RestartHold;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.Point;
import com.dierks.homecraft.games.trial.Tier;
import com.dierks.homecraft.games.trial.TrialKind;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /hcm games event ...} (EVENTS-DROPPER-SPEC §A.11), with no server. Pinned here: {@code
 * start}'s words in any order, and what it can't read; a start is refused while another night is
 * on, with Race Night or Time Trials closed, on a track that can't be raced, and near a restart
 * (a 3:30 start is fine before a 4:00 restart with a 5-minute hold, 3:45 isn't); {@code cancel} needs
 * {@code confirm} once racers are at the track; tab completion offers the right words at each place;
 * and the grid and stand rules an admin's spots are checked against.
 */
class EventAdminTest {

    private static List<String> words(String line) {
        return line.isBlank() ? List.of() : List.of(line.split(" "));
    }

    @Test
    void startReadsItsWordsInAnyOrder() {
        EventAdmin.Start s = EventAdmin.parseStart(words("fresh_boat races 2 laps 3 in 5 fun"));
        assertNull(s.error(), "all of it read");
        assertEquals("fresh_boat", s.course(), "the course");
        assertEquals(2, s.races(), "races 2");
        assertEquals(3, s.laps(), "laps 3");
        assertEquals(5, s.inMinutes(), "in 5");
        assertTrue(s.fun(), "fun: season points only");
        EventAdmin.Start again = EventAdmin.parseStart(words("fun in 1 ice_loop"));
        assertNull(again.error(), "any order");
        assertEquals("ice_loop", again.course(), "the course last");
        assertEquals(1, again.inMinutes(), "in 1");
        EventAdmin.Start bare = EventAdmin.parseStart(words(""));
        assertNull(bare.error(), "nothing is fine: the configured course, now");
        assertNull(bare.course(), "no course: the configured one");
        assertNull(bare.races(), "no races: the configured number");
        assertEquals(0, bare.inMinutes(), "now");
        assertFalse(bare.fun(), "a prize night unless fun");
    }

    @Test
    void startSaysWhatItCantRead() {
        assertNotNull(EventAdmin.parseStart(words("races")).error(), "races needs a number");
        assertNotNull(EventAdmin.parseStart(words("races 9")).error(), "races is 1-5");
        assertNotNull(EventAdmin.parseStart(words("laps 6")).error(), "laps is 0-5");
        assertNotNull(EventAdmin.parseStart(words("in soon")).error(), "in needs minutes");
        assertNotNull(EventAdmin.parseStart(words("ice two")).error(), "two courses");
        assertNotNull(EventAdmin.parseStart(words("Ice-Loop!")).error(), "not a course id");
    }

    @Test
    void aStartIsRefusedWhileAnotherNightIsOnOrAnythingIsClosed() {
        assertEquals("Another Race Night is on - use cancel first.",
                EventAdmin.startProblem(true, true, true, null, null, null), "one night at a time");
        assertTrue(EventAdmin.startProblem(false, false, true, null, null, null).contains("switched off"),
                "Race Night off");
        assertTrue(EventAdmin.startProblem(false, true, false, null, null, null).contains("Time Trials is closed"),
                "Time Trials closed");
        assertTrue(EventAdmin.startProblem(false, true, true, "Ice isn't a boat course", null, null)
                .contains("Ice isn't a boat course"), "the track's problem, named");
        assertTrue(EventAdmin.startProblem(false, true, true, null, null, "the scheduled night at Fri 7:00 PM is too close")
                .contains("too close"), "a scheduled night too close");
        assertNull(EventAdmin.startProblem(false, true, true, null, null, null), "all clear: it starts");
    }

    @Test
    void aStartIsRefusedNearARestart() {
        ZoneId zone = ZoneId.of("America/Chicago");
        RestartHold hold = new RestartHold(List.of(LocalTime.of(16, 0)), zone, 5);
        long worst = new NightRules(3, 0, 2, 8, List.of(10), 2, 1, List.of(5, 3, 2), 1, false, 0, 60, 4, 20)
                .worstMillis();
        assertEquals(15 * 60_000L, worst, "the shipped worst case is 15 minutes");
        LocalDate day = LocalDate.of(2026, 10, 3);
        long open1525 = RestartHold.instant(day, LocalTime.of(15, 25), zone);
        assertNull(EventSchedule.restartProblem(open1525, open1525 + 5 * 60_000L, worst, hold),
                "opens 3:25, starts 3:30, over by 3:45: before the 3:55 hold with 2 minutes to spare");
        long open1540 = RestartHold.instant(day, LocalTime.of(15, 40), zone);
        String why = EventSchedule.restartProblem(open1540, open1540 + 5 * 60_000L, worst, hold);
        assertNotNull(why, "opens 3:40, starts 3:45, could run to 4:00: past the 3:53 limit");
        assertEquals("A restart is at 4:00 PM - Race Night needs 15 minutes.", why, "said plainly");
        assertEquals(why, EventAdmin.startProblem(false, true, true, null, why, null), "the refusal is that line");
    }

    @Test
    void cancelNeedsConfirmOnceRacersAreAtTheTrack() {
        assertEquals("No Race Night is on.", EventAdmin.cancelProblem(null, false), "nothing to cancel");
        assertEquals("No Race Night is on.", EventAdmin.cancelProblem(EventMachine.Phase.DONE, true), "over already");
        assertNull(EventAdmin.cancelProblem(EventMachine.Phase.OPEN, false), "joining: cancel at once");
        assertNull(EventAdmin.cancelProblem(EventMachine.Phase.SCHEDULED, false), "not open yet: at once");
        for (EventMachine.Phase p : List.of(EventMachine.Phase.WARMUP, EventMachine.Phase.GRID, EventMachine.Phase.RACING,
                EventMachine.Phase.BREAK)) {
            assertTrue(EventAdmin.cancelProblem(p, false).contains("cancel confirm"), p + ": needs confirm");
            assertNull(EventAdmin.cancelProblem(p, true), p + " with confirm: called off");
        }
    }

    @Test
    void tabCompletionOffersTheRightWordsAtEachPlace() {
        List<String> courses = List.of("fresh_boat", "ice_loop");
        List<String> nights = List.of("rn-20261002-1900", "rn-20261009-1900");
        assertEquals(EventAdmin.VERBS, EventAdmin.complete(new String[]{""}, courses, nights), "every verb");
        assertEquals(List.of("status", "start", "stand", "skip"),
                sorted(EventAdmin.complete(new String[]{"s"}, courses, nights), List.of("status", "start", "stand", "skip")),
                "the verbs starting with s");
        assertTrue(EventAdmin.complete(new String[]{"start", ""}, courses, nights).containsAll(List.of("fresh_boat",
                "races", "fun")), "start: a course or a word");
        assertEquals(List.of("1", "2", "3", "4", "5"), EventAdmin.complete(new String[]{"start", "races", ""}, courses,
                nights), "races: a number");
        assertEquals(List.of("confirm"), EventAdmin.complete(new String[]{"cancel", ""}, courses, nights), "confirm");
        assertEquals(List.of("next", "rn-20261002-1900", "rn-20261009-1900"),
                EventAdmin.complete(new String[]{"skip", ""}, courses, nights), "skip: next or an id");
        assertEquals(List.of("ice_loop"), EventAdmin.complete(new String[]{"grid", "i"}, courses, nights), "a course");
        assertEquals(EventAdmin.GRID_VERBS, EventAdmin.complete(new String[]{"grid", "ice_loop", ""}, courses, nights),
                "the grid verbs");
        assertEquals(EventAdmin.STAND_VERBS, EventAdmin.complete(new String[]{"stand", "ice_loop", ""}, courses,
                nights), "the stand verbs");
        assertTrue(EventAdmin.complete(new String[]{"go", ""}, courses, nights).isEmpty(), "go takes nothing");
    }

    private static List<String> sorted(List<String> got, List<String> order) {
        List<String> out = new ArrayList<>(got);
        out.sort((a, b) -> Integer.compare(order.indexOf(a), order.indexOf(b)));
        return out;
    }

    // ---- the grid and the stand -------------------------------------------------------------------

    private static Course straight() {
        return new Course("run", TrialKind.BOAT, "River Run", Tier.EASY, "games", new Course.Spot(0, 64, 0, 0, 0),
                List.of(new Course.Mark(0, 64, 40, 3)), new Course.Mark(0, 64, 80, 3), null, null, true, false, 1);
    }

    @Test
    void anAdminsGridSpotMustBeBehindTheStartNearItAndApart() {
        Course c = straight();
        List<Course.Spot> grid = new ArrayList<>();
        assertNull(RaceTrack.gridProblem(c, grid, new Course.Spot(-1.5, 64, -4, 0, 0)), "behind the start: fine");
        grid.add(new Course.Spot(-1.5, 64, -4, 0, 0));
        assertNotNull(RaceTrack.gridProblem(c, grid, new Course.Spot(-1.5, 64, -5, 0, 0)), "closer than 2.5 to another");
        assertNotNull(RaceTrack.gridProblem(c, grid, new Course.Spot(0, 64, 5, 0, 0)), "in front of the start");
        assertNotNull(RaceTrack.gridProblem(c, grid, new Course.Spot(0, 64, -30, 0, 0)), "more than 24 from the start");
        List<Course.Spot> full = new ArrayList<>();
        for (int i = 0; i < RaceTrack.MAX_GRID; i++) {
            full.add(new Course.Spot(0, 64, -3 * (i + 1), 0, 0));
        }
        assertNotNull(RaceTrack.gridProblem(c, full, new Course.Spot(3, 64, -1, 0, 0)), "at most 8 spots");
    }

    @Test
    void aStandMustBeTenBlocksFromTheRacingLine() {
        Course c = straight();
        assertNotNull(RaceTrack.standProblem(c, List.of(), new Point(5, 70, 40)), "5 from the line: too near");
        assertNull(RaceTrack.standProblem(c, List.of(), new Point(12, 70, 40)), "12 from the line: fine");
    }
}
