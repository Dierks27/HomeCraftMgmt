package com.dierks.homecraft.util;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.function.Consumer;

/**
 * The server's calendar: which day and week it is <b>where the players live</b>.
 *
 * <p>Every token "day" used to be a UTC day, so for a family in Minnesota the login streak, the
 * dailies and the weeklies all rolled over at 7 PM (6 PM once the clocks go back) — in the middle
 * of the evening, which is exactly when everybody plays. Somebody who logged in at 6:55 and again
 * at 7:05 had been online on two "days". This answers the same questions in {@code clock.time_zone}
 * instead, so a day ends at midnight like a day should.
 *
 * <p>Deliberately NOT used by the market or the Courier daily limits. Those also roll over at UTC
 * midnight, and moving them changes money flow, which is a separate decision.
 *
 * <p>Day and week keys are epoch days in the local zone — plain numbers that compare, subtract
 * and store like the UTC ones they replace. {@code dayKey() + 1} is tomorrow, whatever DST is
 * doing, because a date has no length; only the milliseconds until it arrives do.
 */
public final class GameClock {

    /** Used when {@code clock.time_zone} is blank or not a zone Java recognises. */
    public static final ZoneId FALLBACK = ZoneOffset.UTC;

    private final ZoneId zone;
    private final Clock clock;

    public GameClock(ZoneId zone, Clock clock) {
        this.zone = zone == null ? FALLBACK : zone;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /** The live clock in {@code zone}. */
    public GameClock(ZoneId zone) {
        this(zone, Clock.systemUTC());
    }

    /**
     * Parse a configured zone name. Blank or unknown falls back to UTC with a warning — the old
     * behaviour, so a typo costs the family their local midnight rather than the whole Arcade.
     */
    public static ZoneId parseZone(String name, Consumer<String> warn) {
        if (name == null || name.isBlank()) {
            if (warn != null) {
                warn.accept("clock.time_zone is blank — every daily reset runs on UTC. "
                        + "Set it to your players' zone, e.g. America/Chicago.");
            }
            return FALLBACK;
        }
        try {
            return ZoneId.of(name.trim());
        } catch (DateTimeException e) {
            if (warn != null) {
                warn.accept("clock.time_zone '" + name + "' is not a time zone Java knows — using UTC. "
                        + "Use a region name such as America/Chicago.");
            }
            return FALLBACK;
        }
    }

    public ZoneId zone() {
        return zone;
    }

    public long nowMillis() {
        return clock.millis();
    }

    /** Today's date where the players live. */
    public LocalDate today() {
        return LocalDate.now(clock.withZone(zone));
    }

    /** Today as a local epoch day. */
    public long dayKey() {
        return today().toEpochDay();
    }

    /** The local epoch day of the date containing {@code epochMillis}. */
    public long dayKeyAt(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate().toEpochDay();
    }

    /** The local epoch day the current week started on, weeks starting on {@code start}. */
    public long weekKey(DayOfWeek start) {
        return today().with(TemporalAdjusters.previousOrSame(start == null ? DayOfWeek.MONDAY : start))
                .toEpochDay();
    }

    /** Milliseconds until the next local midnight — 23 or 25 hours' worth on a DST day. */
    public long msUntilNextDay() {
        return millisAt(today().plusDays(1)) - clock.millis();
    }

    /** Milliseconds until the next week starts. */
    public long msUntilNextWeek(DayOfWeek start) {
        LocalDate next = today().with(TemporalAdjusters.next(start == null ? DayOfWeek.MONDAY : start));
        return millisAt(next) - clock.millis();
    }

    /** The instant a local date begins (midnight, or the first instant after a DST gap). */
    public long millisAt(LocalDate date) {
        return date.atStartOfDay(zone).toInstant().toEpochMilli();
    }

    /** The instant a local day key begins. */
    public long startOfDay(long dayKey) {
        return millisAt(LocalDate.ofEpochDay(dayKey));
    }
}
