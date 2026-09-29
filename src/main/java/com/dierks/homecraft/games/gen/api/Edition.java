package com.dierks.homecraft.games.gen.api;

import com.dierks.homecraft.games.RestartHold;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;

/**
 * Which set of Fresh Courses is due at a given instant (GEN-SPEC §3.1 and the weekly addendum §1):
 * an <b>edition</b> is one set of layouts, and a new one starts every {@code cadenceDays} days at
 * {@code rebuild_at} (04:00) local time.
 *
 * <p><b>Why a fixed grid.</b> Editions start on the local days {@code d} where
 * {@code (d - anchor) mod N == 0}, and {@code anchor} is the first {@code rebuild_day} on or after
 * {@link #EPOCH} (Monday 2026-01-05). So N=7 is every week on {@code rebuild_day}, N=14 every other
 * week, N=1 every day, and N=2 or 3 every 2 or 3 days on dates anyone can work out in advance. The
 * dates never depend on when a setting was changed, and {@code floorMod} keeps them right for days
 * before the anchor.
 *
 * <p><b>Why the day moves only forward.</b> The course day is worked out from instants, not
 * wall-clock times: the start on each date is {@link RestartHold#instant} (a time a spring-forward
 * day skips is the first instant after the gap; one that happens twice on a fall-back day is the
 * first of the two), and {@code day(now)} is the latest date whose start has passed. That is "the
 * local date, minus one before 04:00" everywhere except the repeated hour of a fall-back night, where
 * comparing wall clocks would step back to yesterday for an hour.
 *
 * <p><b>The edition key</b> is {@code N:<index>} ({@code 7:38}), with {@code r<n>} after an admin's
 * reroll ({@code 7:38r1}). It names the boards, the rewards and the seed, so a daily and a weekly
 * edition never share one. The index counts N-day steps from {@link #EPOCH}:
 * {@code floorDiv(start - EPOCH, N)}. That is exactly {@code (start - anchor) / N} whenever N is 7 or
 * more, or the rebuild day is Monday; for a shorter cadence with another rebuild day it is the same
 * number plus a constant, chosen so that an index is a function of the start date alone: moving
 * {@code rebuild_day} can then never hand out a key an earlier edition already used (which would
 * bring back an old layout, its old board and an already-paid first-finish reward).
 *
 * <p>A week (the Star Chart's) is still the quests' week ({@code quests.week_starts_on}), whatever
 * the cadence.
 *
 * @param zone        {@code clock.time_zone}
 * @param rollover    when an edition starts on its day ({@code games.fresh.rebuild_at})
 * @param weekStart   the first day of a week ({@code quests.week_starts_on})
 * @param cadenceDays how many days an edition lasts, 1 to {@value #MAX_CADENCE} ({@code games.fresh.cadence})
 * @param rebuildDay  the weekday editions are anchored on ({@code games.fresh.rebuild_day}); {@code null}
 *                    reads as {@code weekStart}
 */
public record Edition(ZoneId zone, LocalTime rollover, DayOfWeek weekStart, int cadenceDays, DayOfWeek rebuildDay) {

    /** The shipped {@code rebuild_at}. */
    public static final LocalTime DEFAULT_ROLLOVER = LocalTime.of(4, 0);
    /** A new set every day. */
    public static final int DAILY = 1;
    /** A new set every week: the shipped cadence. */
    public static final int WEEKLY = 7;
    /** The shipped cadence. */
    public static final int DEFAULT_CADENCE = WEEKLY;
    /** The longest cadence: four weeks. */
    public static final int MAX_CADENCE = 28;
    /** Monday 2026-01-05: the anchor is the first rebuild day on or after it, and indexes count from it. */
    public static final LocalDate EPOCH = LocalDate.of(2026, 1, 5);
    /** {@link #EPOCH} as a local epoch day. */
    public static final long EPOCH_DAY = EPOCH.toEpochDay();

    public Edition {
        zone = zone == null ? ZoneOffset.UTC : zone;
        rollover = rollover == null ? DEFAULT_ROLLOVER : rollover;
        weekStart = weekStart == null ? DayOfWeek.MONDAY : weekStart;
        cadenceDays = clampCadence(cadenceDays);
        rebuildDay = rebuildDay == null ? weekStart : rebuildDay;
    }

    /**
     * The course-day rules alone, for callers that only ask for {@link #day} and {@link #weekKey}:
     * a daily cadence (the meaning before editions), anchored on the week start.
     */
    public Edition(ZoneId zone, LocalTime rollover, DayOfWeek weekStart) {
        this(zone, rollover, weekStart, DAILY, null);
    }

    // ---- days -----------------------------------------------------------------------------------

    /** The course day at {@code now} (epoch ms): the latest local date whose {@code rebuild_at} has passed. */
    public long day(long now) {
        LocalDate date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        return now < startOf(date.toEpochDay()) ? date.toEpochDay() - 1 : date.toEpochDay();
    }

    /** When local day {@code day}'s {@code rebuild_at} is, DST-safe (epoch ms). */
    public long startOf(long day) {
        return RestartHold.instant(LocalDate.ofEpochDay(day), rollover, zone);
    }

    /** The week (the local epoch day it starts on) that course day {@code day} is in. */
    public long weekKey(long day) {
        return LocalDate.ofEpochDay(day).with(TemporalAdjusters.previousOrSame(weekStart)).toEpochDay();
    }

    // ---- editions -------------------------------------------------------------------------------

    /** The first {@link #rebuildDay} on or after {@link #EPOCH} (a local epoch day). */
    public long anchor() {
        return EPOCH.with(TemporalAdjusters.nextOrSame(rebuildDay)).toEpochDay();
    }

    /** Whether an edition starts on local day {@code day}: {@code (day - anchor) mod N == 0}. */
    public boolean starts(long day) {
        return Math.floorMod(day - anchor(), (long) cadenceDays) == 0;
    }

    /** The first day of the edition that local day {@code day} is in. */
    public long startOfEditionOn(long day) {
        return day - Math.floorMod(day - anchor(), (long) cadenceDays);
    }

    /** The first day (local epoch day) of the edition due at {@code now}. */
    public long editionStart(long now) {
        return startOfEditionOn(day(now));
    }

    /** The key of the edition due at {@code now} ({@code 7:38}). */
    public String key(long now) {
        return editionKey(cadenceDays, editionStart(now), 0);
    }

    /** When an edition of this cadence that starts on {@code startDay} ends: the next one's start (epoch ms). */
    public long endOf(long startDay) {
        return startOf(startDay + cadenceDays);
    }

    /** When the next edition starts, strictly after {@code now} (epoch ms). */
    public long nextChangeAt(long now) {
        return endOf(editionStart(now));
    }

    /** How many editions start inside the week that begins on {@code weekKey} (0 to 7). */
    public int startsInWeek(long weekKey) {
        int n = 0;
        for (long d = weekKey; d < weekKey + 7; d++) {
            if (starts(d)) {
                n++;
            }
        }
        return n;
    }

    /**
     * The first day of the edition {@code key} names, as these rules place it: the one grid day in
     * the N days from {@link Key#firstDay}. For a key of another cadence (made before the owner
     * changed it) that grid isn't known, so it is the key's earliest possible day.
     */
    public long startDayOf(Key key) {
        return key.cadence() == cadenceDays ? startOfEditionOn(key.firstDay() + cadenceDays - 1) : key.firstDay();
    }

    /** The same rules with another cadence. */
    public Edition withCadence(int days) {
        return new Edition(zone, rollover, weekStart, days, rebuildDay);
    }

    // ---- keys -----------------------------------------------------------------------------------

    /** A course day's date, for rows and people ("2026-09-29"). */
    public static LocalDate date(long day) {
        return LocalDate.ofEpochDay(day);
    }

    /** A cadence in range: 1 to {@value #MAX_CADENCE}; anything below 1 reads as daily. */
    public static int clampCadence(int days) {
        return Math.max(DAILY, Math.min(MAX_CADENCE, days));
    }

    /** The index of the N-day edition that starts on {@code startDay}: {@code floorDiv(startDay - EPOCH, N)}. */
    public static long index(int cadence, long startDay) {
        return Math.floorDiv(startDay - EPOCH_DAY, (long) clampCadence(cadence));
    }

    /** The earliest day an edition with this cadence and index can start on (its real start is at most N-1 later). */
    public static long firstDayOf(int cadence, long index) {
        return EPOCH_DAY + index * clampCadence(cadence);
    }

    /**
     * The name of one layout: {@code N:<index>}, or {@code N:<index>r<reroll>} after an admin's
     * reroll. Its board is {@code gfresh:<slot>:<this>}.
     */
    public static String editionKey(int cadence, long startDay, int reroll) {
        int n = clampCadence(cadence);
        String base = n + ":" + index(n, startDay);
        return reroll <= 0 ? base : base + "r" + reroll;
    }

    /** The daily edition of local day {@code day} ({@link #editionKey(int, long, int)} with N = 1). */
    public static String editionKey(long day, int reroll) {
        return editionKey(DAILY, day, reroll);
    }

    /**
     * An edition key read back ({@code 7:38}, {@code 7:38r1}).
     *
     * @param cadence the edition's N
     * @param index   its index
     * @param reroll  0, or the admin's reroll
     */
    public record Key(int cadence, long index, int reroll) {

        /** Its earliest possible first day ({@link #firstDayOf}). */
        public long firstDay() {
            return firstDayOf(cadence, index);
        }

        /** The key without the reroll ({@code 7:38}): what stars and first-finish rewards are kept per. */
        public String base() {
            return cadence + ":" + index;
        }

        @Override
        public String toString() {
            return reroll <= 0 ? base() : base() + "r" + reroll;
        }

        /** A key as written, or {@code null} when it isn't one. Never throws. */
        public static Key parse(String text) {
            if (text == null) {
                return null;
            }
            String t = text.trim().toLowerCase(Locale.ROOT);
            int colon = t.indexOf(':');
            if (colon <= 0) {
                return null;
            }
            int r = t.indexOf('r', colon);
            try {
                int n = Integer.parseInt(t.substring(0, colon));
                long index = Long.parseLong(r < 0 ? t.substring(colon + 1) : t.substring(colon + 1, r));
                int reroll = r < 0 ? 0 : Integer.parseInt(t.substring(r + 1));
                if (n < DAILY || n > MAX_CADENCE || reroll < 0 || (r >= 0 && reroll == 0)) {
                    return null;
                }
                return new Key(n, index, reroll);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }
}
