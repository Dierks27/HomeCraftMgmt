package com.dierks.homecraft.arcade.candy;

import com.dierks.homecraft.config.CandyConfig;
import com.dierks.homecraft.config.CandyConfig.Candy;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.48.1 (the owner: "Cut the Harvest showers to only happen simply randomly from here on out"): the Harvest Basket's
 * showers come a few times a day at random times, new every day, never in the early morning, in place of one an hour
 * on the clock. The times are a pure function of the day, so a restart never repeats or skips one.
 */
class RandomShowersTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 21);

    @Test
    void theHarvestShipsThreeRandomShowersADayAndTheCandyStaysOnTheClock() {
        assertEquals(3, CandyConfig.HARVEST_DEFAULTS.randomADay());
        assertEquals(0, Candy.DEFAULTS.randomADay(), "the Candy Apple shower is as it was");
        List<String> warnings = new ArrayList<>();
        YamlConfiguration empty = new YamlConfiguration();
        Candy harvest = CandyConfig.parse(empty, CandyConfig.HARVEST_PATH, CandyConfig.HARVEST_DEFAULTS, "the Harvest",
                warnings::add);
        assertEquals(3, harvest.randomADay(), "a config.yml from before the update reads as three a day");
        empty.set(CandyConfig.HARVEST_PATH + ".random_a_day", 0);
        assertEquals(0, CandyConfig.parse(empty, CandyConfig.HARVEST_PATH, CandyConfig.HARVEST_DEFAULTS, "the Harvest",
                warnings::add).randomADay(), "0 puts it back on the clock");
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void aDayHasExactlyThatManyShowersNoneInTheEarlyMorning() {
        for (int aDay = 1; aDay <= CandyRules.MAX_RANDOM_A_DAY; aDay++) {
            for (int d = 0; d < 60; d++) {
                List<Integer> starts = CandyRules.randomStarts(DAY.plusDays(d), aDay);
                assertEquals(aDay, starts.size(), aDay + " a day");
                assertEquals(aDay, new HashSet<>(starts).size(), "never two at once");
                int before = -CandyRules.FIRE_MINUTES;
                for (int m : starts) {
                    int hour = m / 60;
                    assertTrue(hour < CandyRules.QUIET_FROM_HOUR || hour >= CandyRules.QUIET_TO_HOUR,
                            "never from 2 to 8 in the morning: " + m);
                    assertTrue(m - before >= CandyRules.FIRE_MINUTES, "in order, and apart: " + starts);
                    before = m;
                }
            }
        }
        assertTrue(CandyRules.randomStarts(DAY, 0).isEmpty());
        assertTrue(CandyRules.randomStarts(null, 3).isEmpty());
    }

    @Test
    void theTimesAreTheDaysOwnAndNewEachDay() {
        assertEquals(CandyRules.randomStarts(DAY, 3), CandyRules.randomStarts(DAY, 3), "the same after any restart");
        Set<List<Integer>> seen = new HashSet<>();
        for (int d = 0; d < 30; d++) {
            seen.add(CandyRules.randomStarts(DAY.plusDays(d), 3));
        }
        assertTrue(seen.size() >= 29, "thirty days, thirty different days (or all but one): " + seen.size());
    }

    @Test
    void aShowerIsDueAtItsMinuteAndTheNextAndOnlyThen() {
        List<Integer> starts = CandyRules.randomStarts(DAY, 3);
        int due = 0;
        for (int m = 0; m < CandyRules.MINUTES_PER_DAY; m++) {
            LocalDateTime now = DAY.atStartOfDay().plusMinutes(m);
            long slot = CandyRules.dueSlot(now, 60, 40, 30, false, 3);
            boolean expected = false;
            for (int s : starts) {
                expected |= m >= s && m - s < CandyRules.FIRE_MINUTES;
            }
            assertEquals(expected, slot != CandyRules.NO_SLOT, "minute " + m);
            if (slot != CandyRules.NO_SLOT) {
                due++;
                assertTrue(starts.contains((int) (slot - CandyRules.slotId(DAY, 0))), "the slot is its start");
            }
        }
        assertEquals(3 * CandyRules.FIRE_MINUTES, due, "three showers, each due for its firing window");
        // the last day is random too: no half-hourly showers
        LocalDateTime clockSlot = DAY.atStartOfDay().plusMinutes(10 * 60 + 40);
        if (!starts.contains(10 * 60 + 40) && !starts.contains(10 * 60 + 39)) {
            assertEquals(CandyRules.NO_SLOT, CandyRules.dueSlot(clockSlot, 60, 40, 30, true, 3),
                    "the clock's 10:40 is no shower any more");
        }
        assertNotEquals(CandyRules.NO_SLOT, CandyRules.dueSlot(clockSlot, 60, 40, 30, false, 0),
                "with 0 a day it is back on the clock");
    }

    @Test
    void theNextShowerIsTheNextRandomOneInsideTheWindow() {
        LocalDate from = LocalDate.of(2026, 11, 1);
        LocalDate to = LocalDate.of(2026, 11, 29);
        List<Integer> today = CandyRules.randomStarts(DAY, 3);
        LocalDateTime first = DAY.atStartOfDay().plusMinutes(today.get(0));
        assertEquals(first, CandyRules.nextSlot(DAY.atStartOfDay().minusMinutes(1), from, to, 60, 40, 30, 3),
                "from just before the day: its first shower");
        assertEquals(DAY.atStartOfDay().plusMinutes(today.get(1)), CandyRules.nextSlot(first, from, to, 60, 40, 30, 3),
                "after the first comes the second");
        LocalDateTime last = DAY.atStartOfDay().plusMinutes(today.get(2));
        LocalDateTime tomorrow = CandyRules.nextSlot(last, from, to, 60, 40, 30, 3);
        assertNotNull(tomorrow);
        assertEquals(DAY.plusDays(1), tomorrow.toLocalDate(), "then tomorrow's first");
        List<Integer> lastDay = CandyRules.randomStarts(to, 3);
        assertNull(CandyRules.nextSlot(to.atStartOfDay().plusMinutes(lastDay.get(2)), from, to, 60, 40, 30, 3),
                "after the window's last shower there is none");
    }

    @Test
    void theJoinLineSaysRandomTimes() {
        assertEquals("&6Treats fall at random times until 29 Nov! &7You've found &f3 &7of 26&7.",
                ShowerText.harvest().joinLine(0, "29 Nov", 3, 26));
        assertEquals("&6Treats fall every 60 minutes until 29 Nov! &7You've found &f3 &7of 26&7.",
                ShowerText.harvest().joinLine(60, "29 Nov", 3, 26), "on the clock it still says how often");
    }
}
