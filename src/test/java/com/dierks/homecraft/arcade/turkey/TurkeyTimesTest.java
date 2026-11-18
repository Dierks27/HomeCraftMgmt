package com.dierks.homecraft.arcade.turkey;

import com.dierks.homecraft.arcade.candy.CandyRules;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Catch the Turkey's random times (0.44.0; the owner: "6 times a day but completely random times", "no turkey runs
 * from 2 until 8 am"): six a day, never in the quiet hours, never two at once, the same for everyone, new each day.
 */
class TurkeyTimesTest {

    private static List<Integer> day(LocalDate d) {
        return TurkeyRules.startsOn(d, 240, 50, true, 3, 2, 8);
    }

    @Test
    void sixADayNeverFromTwoToEightNeverTogether() {
        for (LocalDate d = LocalDate.of(2026, 11, 22); !d.isAfter(LocalDate.of(2026, 11, 29)); d = d.plusDays(1)) {
            List<Integer> starts = day(d);
            assertEquals(6, starts.size(), d + ": six runs");
            for (int i = 0; i < starts.size(); i++) {
                int m = starts.get(i);
                for (int k = 0; k <= 3 + CandyRules.FIRE_MINUTES; k++) {
                    assertFalse(TurkeyRules.quiet((m + k) % 1440, 2, 8), d + ": a run at " + m / 60 + ":" + m % 60
                            + " reaches the quiet hours");
                }
                if (i > 0) {
                    assertTrue(m - starts.get(i - 1) > 3 + CandyRules.FIRE_MINUTES, d + ": two runs at once " + starts);
                }
            }
        }
    }

    @Test
    void theSameForEveryoneAndNewEachDay() {
        LocalDate d = LocalDate.of(2026, 11, 23);
        assertEquals(day(d), day(d), "the same on every server, after any restart");
        assertNotEquals(day(d), day(d.plusDays(1)), "new each day");
        Set<Integer> hours = new HashSet<>();
        for (int i = 0; i < 8; i++) {
            for (int m : day(d.plusDays(i))) {
                hours.add(m / 60);
            }
        }
        assertTrue(hours.size() >= 10, "spread over the waking hours: " + hours);
    }

    @Test
    void dueAndNext() {
        LocalDate d = LocalDate.of(2026, 11, 24);
        int first = day(d).get(0);
        LocalDateTime at = d.atStartOfDay().plusMinutes(first);
        assertEquals(CandyRules.slotId(d, first), TurkeyRules.dueSlot(at, 240, 50, true, 3, 2, 8), "due at its start");
        assertEquals(CandyRules.slotId(d, first), TurkeyRules.dueSlot(at.plusMinutes(1), 240, 50, true, 3, 2, 8),
                "and a minute late");
        assertEquals(CandyRules.NO_SLOT, TurkeyRules.dueSlot(at.minusMinutes(1), 240, 50, true, 3, 2, 8));
        assertEquals(at, TurkeyRules.nextStart(at.minusMinutes(1), LocalDate.of(2026, 11, 22),
                LocalDate.of(2026, 11, 29), 240, 50, true, 3, 2, 8));
        assertNull(TurkeyRules.nextStart(LocalDateTime.of(2026, 11, 30, 0, 0), LocalDate.of(2026, 11, 22),
                LocalDate.of(2026, 11, 29), 240, 50, true, 3, 2, 8), "none after the window");
        assertEquals(6, TurkeyRules.slotsOn(d, 240, 50, true, 3, 2, 8).size());
    }

    @Test
    void theFixedTimesSkipTheQuietHoursToo() {
        assertEquals(List.of(50, 530, 770, 1010, 1250), TurkeyRules.startsOn(LocalDate.of(2026, 11, 24), 240, 50, false,
                3, 2, 8), "00:50, 08:50, 12:50, 16:50, 20:50: 04:50 is in the quiet hours");
        assertTrue(TurkeyRules.startsOn(LocalDate.of(2026, 11, 24), 240, 50, false, 3, 0, 0).size() == 6,
                "no quiet hours: all six");
        assertFalse(TurkeyRules.quiet(120 - 1, 2, 8));
        assertTrue(TurkeyRules.quiet(120, 2, 8));
        assertTrue(TurkeyRules.quiet(479, 2, 8));
        assertFalse(TurkeyRules.quiet(480, 2, 8));
        assertTrue(TurkeyRules.quiet(23 * 60, 22, 6), "quiet hours over midnight");
    }

    /** The catch-up turkey (0.44.0): once a day, after ten minutes' play, only for someone who caught none today. */
    @Test
    void theCatchUpTurkey() {
        assertEquals(600_000L, TurkeyRules.ownTurkeyDelayMs(false, true, 10), "ten minutes after the join");
        assertEquals(TurkeyRules.WELCOME_DELAY_MS, TurkeyRules.ownTurkeyDelayMs(true, true, 10),
                "the welcome turkey, when it is on, comes first");
        assertEquals(-1, TurkeyRules.ownTurkeyDelayMs(false, false, 10), "neither: none");
        assertTrue(TurkeyRules.ownTurkeyOwed(false, 0), "caught none today: one of your own");
        assertFalse(TurkeyRules.ownTurkeyOwed(false, 1), "caught one: no need");
        assertTrue(TurkeyRules.ownTurkeyOwed(true, 3), "a welcome turkey is everyone's");
    }
}
