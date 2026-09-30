package com.dierks.homecraft.games;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.zone.ZoneOffsetTransition;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The scheduled-restart hold ({@code games.restart_times}, {@code games.restart_hold_minutes}):
 * the owner's host restarts the server at fixed local times and already warns players in chat, so
 * the games say nothing about it. What they do is not START anything a restart would cut off
 * during the last few minutes before one: a world game (a course, a round of golf), a new
 * multi-step round of chance, a daily board's one scored try. Anything already going carries on,
 * and whatever a restart does interrupt is already made safe elsewhere (world sessions restore at
 * the next join, OPEN rounds are finished by their exit rule).
 *
 * <p>Pure: no Bukkit types. "Now" is handed in (the plugin's {@code GameClock}), so tests can move
 * it. The times are local to {@code clock.time_zone} and worked out with {@link ZonedDateTime} for
 * each date, so they stay on the wall clock across DST changes. A time that doesn't exist on a
 * spring-forward day (02:30 in America/Chicago) is taken as the first instant after the gap
 * (03:00), where a host's scheduler runs a skipped job; a time that happens twice on a fall-back
 * day is the first of the two.
 *
 * <p>A restart the plugin came back from is over (the round-2 audit's G1 #2): a quick restart is up
 * again inside the restart's own minute, and the minute ({@link #RESTART_MINUTE_MS}) must not hold it
 * for the restart it just had. So the hold knows when the plugin was enabled ({@code bootedAt}), and a
 * restart whose minute that fell in is skipped.
 *
 * @param times       the restart times of every day, sorted, no duplicates (empty: never held)
 * @param zone        where the times are read
 * @param holdMinutes how long before each restart nothing new starts
 * @param bootedAt    when the plugin was enabled (epoch ms; {@link #NEVER_BOOTED} when unknown): a
 *                    restart whose minute it fell in has happened
 */
public record RestartHold(List<LocalTime> times, ZoneId zone, int holdMinutes, long bootedAt) {

    /** No boot time known: every restart counts. */
    public static final long NEVER_BOOTED = Long.MIN_VALUE;

    /** The shipped hold, in minutes. */
    public static final int DEFAULT_MINUTES = 5;
    /** The shortest and longest hold config may set. */
    public static final int MIN_MINUTES = 1;
    public static final int MAX_MINUTES = 60;

    /** A time the way players read it: "4:00 PM". */
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.US);
    /** "HH:mm" in 24-hour time; a single-digit hour ("4:00") is read too. */
    private static final Pattern TIME = Pattern.compile("(\\d{1,2}):(\\d{2})");

    public RestartHold {
        TreeSet<LocalTime> sorted = new TreeSet<>();
        if (times != null) {
            for (LocalTime t : times) {
                if (t != null) {
                    sorted.add(t);
                }
            }
        }
        times = List.copyOf(sorted);
        zone = zone == null ? ZoneOffset.UTC : zone;
        holdMinutes = Math.max(MIN_MINUTES, Math.min(MAX_MINUTES, holdMinutes));
    }

    /** The hold with no boot time known (config checks, a status line with no games running). */
    public RestartHold(List<LocalTime> times, ZoneId zone, int holdMinutes) {
        this(times, zone, holdMinutes, NEVER_BOOTED);
    }

    /** Whether no restart times are set (nothing is ever held). */
    public boolean off() {
        return times.isEmpty();
    }

    /**
     * How long each restart time lasts: a whole minute. The owner enters the minute the server
     * actually stops ({@code config.yml}, README), and a host's scheduler stops it some seconds into
     * that minute (its cron, the save), so the restart is still coming until the minute is over
     * (fx2-C #11). Every guard reads the restart through {@link #next}, {@link #holding} or
     * {@link #heldFor}, so none of them opens again in that minute.
     */
    public static final long RESTART_MINUTE_MS = 60_000L;

    /**
     * The next restart still to come at {@code now}, in epoch milliseconds; -1 when no restart times
     * are set. A restart time names a whole minute ({@link #RESTART_MINUTE_MS}): the restart at R is
     * the next one until R plus a minute, since the server stops somewhere in that minute; from then
     * the next is the one after it. A plugin enabled inside R's minute came back from R, so for it the
     * next is already the one after ({@link #cameBackFrom}).
     */
    public long next(long now) {
        if (off()) {
            return -1;
        }
        LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        long best = Long.MAX_VALUE;
        // Yesterday to two days on: every restart on the dates around now, whatever DST did.
        for (int d = -1; d <= 2; d++) {
            LocalDate date = today.plusDays(d);
            for (LocalTime t : times) {
                long at = instant(date, t, zone);
                if (at + RESTART_MINUTE_MS > now && at < best && !cameBackFrom(at)) {
                    best = at;
                }
            }
        }
        return best == Long.MAX_VALUE ? -1 : best;
    }

    /** Whether the plugin was enabled inside the minute of the restart at {@code restart}: it is over. */
    public boolean cameBackFrom(long restart) {
        return bootedAt >= restart && bootedAt < restart + RESTART_MINUTE_MS;
    }

    /**
     * Whether {@code now} is inside {@code [restart - hold, restart + 1 minute)} for the next restart:
     * from the hold's start to the end of the restart's own minute.
     */
    public boolean holding(long now) {
        long next = next(now);
        return next >= 0 && now >= next - holdMillis();
    }

    /** The next restart's time for players ("4:00 PM") while holding; {@code null} when not. */
    public String heldFor(long now) {
        long next = next(now);
        return next >= 0 && now >= next - holdMillis() ? clock(next) : null;
    }

    /** When the hold before the restart at {@code restart} starts. */
    public long holdStart(long restart) {
        return restart - holdMillis();
    }

    /** The hold, in milliseconds. */
    public long holdMillis() {
        return holdMinutes * 60_000L;
    }

    /** An instant as players read the time, in this zone: "4:00 PM". */
    public String clock(long millis) {
        return CLOCK.format(Instant.ofEpochMilli(millis).atZone(zone));
    }

    /**
     * The {@code /hcm games status} line: "Next restart: 4:00 PM (new runs held from 3:55 PM)", or
     * "No restart times set". Plain words, no colour codes.
     */
    public String status(long now) {
        long next = next(now);
        if (next < 0) {
            return "No restart times set";
        }
        return "Next restart: " + clock(next) + " (new runs held from " + clock(holdStart(next)) + ")";
    }

    // ---- pure helpers (tested) ----------------------------------------------------------------

    /**
     * The instant {@code time} happens on {@code date} in {@code zone}: through
     * {@link ZonedDateTime} (the earlier of two on a fall-back day), or, for a time a
     * spring-forward day skips, the first instant after the gap.
     */
    public static long instant(LocalDate date, LocalTime time, ZoneId zone) {
        LocalDateTime local = date.atTime(time);
        ZoneOffsetTransition t = zone.getRules().getTransition(local);
        if (t != null && t.isGap()) {
            return t.getInstant().toEpochMilli();
        }
        return ZonedDateTime.of(local, zone).toInstant().toEpochMilli();
    }

    /**
     * A config entry as a time of day: "HH:mm" in 24-hour time ("04:00", "16:00", "4:00");
     * {@code null} for anything else (a word, a number, "24:00", "7:5"). Never throws.
     */
    public static LocalTime parseTime(Object raw) {
        if (!(raw instanceof String s)) {
            return null;
        }
        Matcher m = TIME.matcher(s.trim());
        if (!m.matches()) {
            return null;
        }
        int hour = Integer.parseInt(m.group(1));
        int minute = Integer.parseInt(m.group(2));
        if (hour > 23 || minute > 59) {
            return null;
        }
        return LocalTime.of(hour, minute);
    }
}
