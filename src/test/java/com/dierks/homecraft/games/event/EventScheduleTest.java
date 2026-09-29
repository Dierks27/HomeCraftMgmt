package com.dierks.homecraft.games.event;

import com.dierks.homecraft.games.RestartHold;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.util.EnumSet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When Race Nights happen ({@link EventSchedule}, EVENTS-DROPPER-SPEC §A.2), with no server.
 *
 * <p>Pinned here: the entry forms ({@code FRI 19:00}, {@code SAT,SUN}, {@code DAILY},
 * {@code WEEKDAYS}, {@code WEEKENDS}) and a bad entry dropped with one WARN while the rest work;
 * ids that are the same however often they are worked out; the wall clock kept across both DST
 * days in America/Chicago; the restart fit (15:30 runs, 15:40 is skipped with restarts at 16:00 and
 * a 5-minute hold); a night on a Fresh course kept off the rebuild; entries too close together; and
 * no raceable track skipping everything.
 */
class EventScheduleTest {

    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");
    private static final long MINUTE = 60_000L;
    /** 3 races of 4 minutes with 20-second breaks, plus 2 minutes: the spec's 15-minute worst case. */
    private static final long WORST = 15 * MINUTE;

    private static long at(int year, int month, int day, int hour, int minute) {
        return LocalDateTime.of(year, month, day, hour, minute).atZone(CHICAGO).toInstant().toEpochMilli();
    }

    private static EventSchedule.Fit fit(RestartHold hold) {
        return new EventSchedule.Fit(CHICAGO, 10, WORST, hold, null, null, Set.of());
    }

    private static EventSchedule.Fit noRestarts() {
        return fit(new RestartHold(List.of(), CHICAGO, 5));
    }

    private static List<EventSchedule.Entry> entries(String... raw) {
        return EventSchedule.parse(List.of(raw), w -> {
            throw new AssertionError("no WARN expected: " + w);
        });
    }

    // ---- the entries --------------------------------------------------------------------------

    @Test
    void everyEntryFormTheSpecNamesIsRead() {
        assertEquals(EnumSet.of(DayOfWeek.FRIDAY), EventSchedule.entry("FRI 19:00").days(), "one day");
        assertEquals(LocalTime.of(19, 0), EventSchedule.entry("FRI 19:00").time(), "its time");
        assertEquals(EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), EventSchedule.entry("SAT,SUN 15:00").days(),
                "a list of days");
        assertEquals(EnumSet.allOf(DayOfWeek.class), EventSchedule.entry("DAILY 18:30").days(), "every day");
        assertEquals(EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), EventSchedule.entry("WEEKDAYS 17:00").days(),
                "Monday to Friday");
        assertEquals(EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), EventSchedule.entry("weekends 10:30").days(),
                "the weekend, any case");
        assertEquals(EnumSet.of(DayOfWeek.FRIDAY), EventSchedule.entry("Friday 7:00").days(), "a day in full");
    }

    @Test
    void aBadEntryIsDroppedWithOneWarnAndTheRestKeepWorking() {
        List<String> warns = new ArrayList<>();
        List<EventSchedule.Entry> got = EventSchedule.parse(List.of("FRI 19:00", "FUNDAY 19:00", "SAT 25:00",
                "SUN", "SAT 15:00"), warns::add);
        assertEquals(2, got.size(), "the two good entries are kept: " + got);
        assertEquals(3, warns.size(), "one WARN per bad entry: " + warns);
        assertTrue(warns.get(0).contains("FUNDAY 19:00"), "the WARN names the entry: " + warns.get(0));
        assertTrue(EventSchedule.parse(null, w -> { }).isEmpty(), "no schedule is admin-started nights only");
    }

    // ---- the nights -----------------------------------------------------------------------------

    @Test
    void aFridayEntryMakesAFridayNightWithAStableId() {
        List<EventSchedule.Entry> fri = entries("FRI 19:00");
        long from = at(2026, 9, 28, 0, 0);
        List<EventSchedule.Occurrence> a = EventSchedule.between(fri, from, from + 7 * 86_400_000L, noRestarts());
        List<EventSchedule.Occurrence> b = EventSchedule.between(fri, from, from + 7 * 86_400_000L, noRestarts());
        assertEquals(1, a.size(), "one Friday in the week: " + a);
        assertEquals("rn-20261002-1900", a.get(0).id(), "the id is the local date and time");
        assertEquals(a, b, "worked out again (a reload), the same night has the same id: it is never made twice");
        assertEquals(at(2026, 10, 2, 19, 0), a.get(0).startsAt(), "7:00 PM local");
        assertEquals(at(2026, 10, 2, 18, 50), a.get(0).joinAt(), "joining opens join_minutes (10) before");
        assertTrue(a.get(0).fits(), "nothing in its way");
    }

    @Test
    void theWallClockHoldsAcrossSpringForwardAndFallBack() {
        List<EventSchedule.Entry> daily = entries("DAILY 02:30");
        // 8 March 2026: 02:00 jumps to 03:00 in Chicago, so 02:30 doesn't exist
        List<EventSchedule.Occurrence> spring = EventSchedule.between(daily, at(2026, 3, 8, 0, 0), at(2026, 3, 8, 23, 0),
                noRestarts());
        assertEquals(1, spring.size(), "one night on the spring-forward day: " + spring);
        assertEquals(at(2026, 3, 8, 3, 0), spring.get(0).startsAt(),
                "a skipped time runs at the first instant after the gap");
        // 1 November 2026: 01:00-02:00 happens twice
        List<EventSchedule.Entry> late = entries("DAILY 01:30");
        List<EventSchedule.Occurrence> fall = EventSchedule.between(late, at(2026, 11, 1, 0, 0), at(2026, 11, 1, 23, 0),
                noRestarts());
        assertEquals(1, fall.size(), "a doubled time runs once: " + fall);
        assertEquals(LocalDateTime.of(2026, 11, 1, 1, 30).atZone(CHICAGO).withEarlierOffsetAtOverlap().toInstant()
                .toEpochMilli(), fall.get(0).startsAt(), "at the first of the two");
        List<EventSchedule.Entry> evening = entries("DAILY 19:00");
        for (LocalDate d = LocalDate.of(2026, 3, 6); d.isBefore(LocalDate.of(2026, 3, 11)); d = d.plusDays(1)) {
            List<EventSchedule.Occurrence> o = EventSchedule.between(evening, at(d.getYear(), d.getMonthValue(),
                    d.getDayOfMonth(), 0, 0), at(d.getYear(), d.getMonthValue(), d.getDayOfMonth(), 23, 0), noRestarts());
            assertEquals(at(d.getYear(), d.getMonthValue(), d.getDayOfMonth(), 19, 0), o.get(0).startsAt(),
                    "7:00 PM stays 7:00 PM on the wall clock either side of the change, " + d);
        }
    }

    @Test
    void theRestartFitRunsHalfPastThreeAndSkipsTwentyToFour() {
        RestartHold owner = new RestartHold(List.of(LocalTime.of(4, 0), LocalTime.of(16, 0)), CHICAGO, 5);
        List<EventSchedule.Occurrence> nights = EventSchedule.between(entries("SAT 15:30", "SAT 15:40"),
                at(2026, 10, 3, 0, 0), at(2026, 10, 3, 23, 0), fit(owner));
        EventSchedule.Occurrence half = nights.stream().filter(o -> o.id().endsWith("1530")).findFirst().orElseThrow();
        EventSchedule.Occurrence twenty = nights.stream().filter(o -> o.id().endsWith("1540")).findFirst().orElseThrow();
        assertTrue(half.fits(), "15:30 + 15 minutes ends by 15:45, before 15:53: it runs (" + half.skip() + ")");
        assertNotNull(twenty.skip(), "15:40 + 15 minutes is 15:55, past 15:53: skipped");
        assertTrue(twenty.skip().contains("4:00 PM"), "the reason names the restart: " + twenty.skip());
        assertEquals("A restart is at 4:00 PM - Race Night needs 15 minutes.",
                EventSchedule.restartProblem(at(2026, 10, 3, 15, 50), at(2026, 10, 3, 15, 50), WORST, owner),
                "an admin start near a restart reads the same");
        assertNull(EventSchedule.restartProblem(at(2026, 10, 3, 15, 20), at(2026, 10, 3, 15, 30), WORST, owner),
                "and one that fits reads nothing");
    }

    @Test
    void aNightOnAFreshCourseKeepsOffTheRebuild() {
        EventSchedule.Fit fresh = new EventSchedule.Fit(CHICAGO, 10, WORST, new RestartHold(List.of(), CHICAGO, 5),
                LocalTime.of(4, 0), null, Set.of());
        List<EventSchedule.Occurrence> nights = EventSchedule.between(entries("SAT 03:50", "SAT 19:00"),
                at(2026, 10, 3, 0, 0), at(2026, 10, 3, 23, 0), fresh);
        assertNotNull(nights.get(0).skip(), "03:50-04:05 touches the 04:00 rebuild: skipped");
        assertTrue(nights.get(0).skip().contains("rebuild"), "and says why: " + nights.get(0).skip());
        assertTrue(nights.get(1).fits(), "an evening night is nowhere near it");
        EventSchedule.Fit handBuilt = noRestarts();
        assertTrue(EventSchedule.between(entries("SAT 03:50"), at(2026, 10, 3, 0, 0), at(2026, 10, 3, 23, 0),
                handBuilt).get(0).fits(), "a hand-built track doesn't care about the rebuild");
    }

    @Test
    void anEntryTooSoonAfterAnotherIsSkipped() {
        List<EventSchedule.Occurrence> nights = EventSchedule.between(entries("SAT 15:00", "SAT 15:20", "SAT 15:30"),
                at(2026, 10, 3, 0, 0), at(2026, 10, 3, 23, 0), noRestarts());
        assertTrue(nights.get(0).fits(), "the first runs");
        assertNotNull(nights.get(1).skip(), "20 minutes later is less than worst + join (25): skipped");
        assertTrue(nights.get(1).skip().contains("3:00 PM"), "naming the night it's too close to: " + nights.get(1).skip());
        assertTrue(nights.get(2).fits(), "30 minutes after the one that ran is far enough");
    }

    @Test
    void noRaceableTrackSkipsEveryNight() {
        EventSchedule.Fit none = new EventSchedule.Fit(CHICAGO, 10, WORST, new RestartHold(List.of(), CHICAGO, 5), null,
                "no track: turn on Ice Boat or set a grid", Set.of());
        List<EventSchedule.Occurrence> nights = EventSchedule.between(entries("DAILY 19:00"), at(2026, 10, 1, 0, 0),
                at(2026, 10, 3, 23, 0), none);
        assertEquals(3, nights.size(), "three nights in the window");
        for (EventSchedule.Occurrence o : nights) {
            assertEquals("no track: turn on Ice Boat or set a grid", o.skip(), "each skipped with the reason: " + o);
        }
        assertNull(EventSchedule.next(entries("DAILY 19:00"), at(2026, 10, 1, 12, 0), none),
                "so there is no next night to open");
    }

    @Test
    void anAdminSkippedNightIsSkippedAndTheNextOneIsNext() {
        EventSchedule.Fit fit = new EventSchedule.Fit(CHICAGO, 10, WORST, new RestartHold(List.of(), CHICAGO, 5), null,
                null, Set.of("rn-20261002-1900"));
        EventSchedule.Occurrence next = EventSchedule.next(entries("FRI 19:00"), at(2026, 10, 1, 12, 0), fit);
        assertEquals("rn-20261009-1900", next.id(), "the skipped Friday is passed over");
        assertEquals("skipped by an admin", EventSchedule.between(entries("FRI 19:00"), at(2026, 10, 2, 0, 0),
                at(2026, 10, 2, 23, 0), fit).get(0).skip(), "and says why in the list");
    }

    @Test
    void theNextNightMustStillBeOpenable() {
        List<EventSchedule.Entry> fri = entries("FRI 19:00");
        assertEquals("rn-20261002-1900", EventSchedule.next(fri, at(2026, 10, 2, 18, 58), noRestarts()).id(),
                "two minutes before, tonight's can still open");
        assertEquals("rn-20261009-1900", EventSchedule.next(fri, at(2026, 10, 2, 18, 59, 30), noRestarts()).id(),
                "with less than a minute to go, it's next week's");
    }

    private static long at(int year, int month, int day, int hour, int minute, int second) {
        return at(year, month, day, hour, minute) + second * 1000L;
    }

    @Test
    void theWorstCaseCountsTheWarmUpAndEveryRace() {
        NightRules shipped = NightRules.of(RaceNightSettings.defaults(), 3, 0, false, 8);
        assertEquals(18 * MINUTE, shipped.worstMillis(),
                "3 races of 4 minutes, 3 breaks of 20 s, 2 minutes, and the 3-minute warm-up");
        NightRules noWarmup = new NightRules(3, 0, 2, 8, List.of(10), 2, 1, List.of(5, 3, 2), 1, false, 0, 60, 4, 20);
        assertEquals(WORST, noWarmup.worstMillis(), "the spec's 15 minutes without a warm-up");
    }
}
