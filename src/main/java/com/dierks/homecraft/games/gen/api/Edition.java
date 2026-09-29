package com.dierks.homecraft.games.gen.api;

import com.dierks.homecraft.games.RestartHold;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;

/**
 * The course day (GEN-SPEC §3.1): which day's courses are due at a given instant, when the next
 * set is due, and which week a day is in.
 *
 * <p>A course day starts at {@code rollover} (04:00) local time, not at midnight, so it changes at
 * the owner's morning restart, when nobody is playing: at 03:59 it is still yesterday. A day is a
 * local epoch day, like every other day key in the plugin; the week is the quests' week
 * ({@code quests.week_starts_on}), like the time trials' weekly boards.
 *
 * <p>The day is worked out from instants, not wall-clock times, so it only ever moves forward:
 * the rollover on each date is {@link RestartHold#instant} (a time a spring-forward day skips is
 * the first instant after the gap; one that happens twice on a fall-back day is the first of the
 * two), and {@code day(now)} is the latest date whose rollover has passed. That is the spec's
 * "local date, minus one before the rollover" everywhere except the repeated hour of a fall-back
 * night, where comparing wall clocks would step back to yesterday for an hour.
 *
 * @param zone      {@code clock.time_zone}
 * @param rollover  when a course day starts ({@code games.daily.rollover})
 * @param weekStart the first day of a week ({@code quests.week_starts_on})
 */
public record Edition(ZoneId zone, LocalTime rollover, DayOfWeek weekStart) {

    /** The shipped rollover. */
    public static final LocalTime DEFAULT_ROLLOVER = LocalTime.of(4, 0);

    public Edition {
        zone = zone == null ? ZoneOffset.UTC : zone;
        rollover = rollover == null ? DEFAULT_ROLLOVER : rollover;
        weekStart = weekStart == null ? DayOfWeek.MONDAY : weekStart;
    }

    /** The course day at {@code now} (epoch ms): the latest local date whose rollover has passed. */
    public long day(long now) {
        LocalDate date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        return now < startOf(date.toEpochDay()) ? date.toEpochDay() - 1 : date.toEpochDay();
    }

    /** When course day {@code day} starts: its date's rollover, DST-safe (epoch ms). */
    public long startOf(long day) {
        return RestartHold.instant(LocalDate.ofEpochDay(day), rollover, zone);
    }

    /** When the next course day starts, after {@code now} (epoch ms). */
    public long nextChangeAt(long now) {
        return startOf(day(now) + 1);
    }

    /** The week (the local epoch day it starts on) that course day {@code day} is in. */
    public long weekKey(long day) {
        return LocalDate.ofEpochDay(day).with(TemporalAdjusters.previousOrSame(weekStart)).toEpochDay();
    }

    /** A course day's date, for rows and people ("2026-09-29"). */
    public static LocalDate date(long day) {
        return LocalDate.ofEpochDay(day);
    }

    /** The name of one layout of a day: the day, or {@code <day>r<reroll>} after an admin reroll. */
    public static String editionKey(long day, int reroll) {
        return reroll <= 0 ? Long.toString(day) : day + "r" + reroll;
    }
}
