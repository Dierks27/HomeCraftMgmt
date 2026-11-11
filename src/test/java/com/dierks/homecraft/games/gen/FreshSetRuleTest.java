package com.dierks.homecraft.games.gen;

import com.dierks.homecraft.games.gen.api.Edition;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The website's {@code rules.freshSet}: when a new set of Fresh Courses goes up (0.38.8). */
class FreshSetRuleTest {

    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");

    private static Map<String, Object> rule(LocalTime at, DayOfWeek weekStart, int cadence, DayOfWeek rebuildDay) {
        return DailyCourses.freshSet(new Edition(CHICAGO, at, weekStart, cadence, rebuildDay));
    }

    @Test
    void theShippedScheduleIsEverySevenDaysOnMondayAtFour() {
        Map<String, Object> shipped = rule(Edition.DEFAULT_ROLLOVER, DayOfWeek.MONDAY, Edition.WEEKLY, null);
        assertEquals(Map.of("everyDays", 7, "at", "Mon 04:00", "zone", "America/Chicago"), shipped);
        assertEquals(List.of("everyDays", "at", "zone"), new ArrayList<>(shipped.keySet()), "in this order");
    }

    @Test
    void aRebuildDayWinsOverTheQuestsWeekStart() {
        assertEquals("Fri 16:30", rule(LocalTime.of(16, 30), DayOfWeek.MONDAY, 7, DayOfWeek.FRIDAY).get("at"));
        assertEquals("Sun 04:00", rule(LocalTime.of(4, 0), DayOfWeek.SUNDAY, 14, null).get("at"),
                "every two weeks still changes on one weekday");
    }

    @Test
    void aCadenceThatIsNotWholeWeeksHasNoWeekday() {
        assertEquals(Map.of("everyDays", 1, "at", "04:00", "zone", "America/Chicago"),
                rule(LocalTime.of(4, 0), DayOfWeek.MONDAY, Edition.DAILY, null));
        assertEquals("00:05", rule(LocalTime.of(0, 5), DayOfWeek.MONDAY, 3, DayOfWeek.FRIDAY).get("at"));
    }
}
