package com.dierks.homecraft.games.event;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One Race Night as planned (EVENTS-DROPPER-SPEC §A.2): which track, when joining opens, when it
 * starts, and its rules. Pure: no Bukkit types.
 *
 * <p><b>Ids</b> are stable, so a reload or a restart never makes the same night twice:
 * {@code rn-<yyyyMMdd>-<HHmm>} for a scheduled night (its entry's local date and time:
 * {@code rn-20261002-1900}), and {@code rn-<yyyyMMdd>-<HHmm>-a<n>} for one an admin started
 * ({@code n} counts that minute's admin nights, from 1). The id is also the night's prize ref
 * ({@code event:<id>}), so each racer is paid at most once per night.
 *
 * @param id           the night's id
 * @param course       the track's course id, or {@link RaceNightSettings#AUTO} until one is chosen
 * @param joinAt       when the join window opens (epoch ms)
 * @param startsAt     the announced start (epoch ms): racers are taken to the track 15 s before it
 * @param rules        what it keeps from its settings
 * @param adminStarted an admin started it ({@code /hcm games event start})
 * @param madeBy       who started it ({@code ""} for the schedule)
 */
public record EventPlan(String id, String course, long joinAt, long startsAt, NightRules rules, boolean adminStarted,
                        String madeBy) {

    /** Every Race Night id starts with this. */
    public static final String PREFIX = "rn-";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT);
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HHmm", Locale.ROOT);
    private static final Pattern ID = Pattern.compile("rn-(\\d{8})-(\\d{4})(?:-a(\\d+))?");

    public EventPlan {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("a Race Night id is rn-yyyyMMdd-HHmm[-an]: " + id);
        }
        course = course == null || course.isBlank() ? RaceNightSettings.AUTO : course.trim().toLowerCase(Locale.ROOT);
        madeBy = madeBy == null ? "" : madeBy;
        if (rules == null) {
            throw new IllegalArgumentException("a night needs its rules");
        }
    }

    /** Whether it may pay tokens: a scheduled night, or an admin's without {@code fun}. */
    public boolean prizesAllowed() {
        return rules.wantsPrizes();
    }

    /** How many races. */
    public int races() {
        return rules.races();
    }

    /** The same night on a chosen track. */
    public EventPlan withCourse(String c) {
        return new EventPlan(id, c, joinAt, startsAt, rules, adminStarted, madeBy);
    }

    /** The same night starting at {@code at} (an admin's {@code go}). */
    public EventPlan withStart(long at) {
        return new EventPlan(id, course, Math.min(joinAt, at), at, rules, adminStarted, madeBy);
    }

    /** A scheduled night's id: {@code rn-20261002-1900}. */
    public static String scheduledId(LocalDate date, LocalTime time) {
        return PREFIX + DAY.format(date) + "-" + HOUR.format(time);
    }

    /** An admin night's id: {@code rn-20261002-1432-a1}. */
    public static String adminId(LocalDate date, LocalTime time, int n) {
        return scheduledId(date, time) + "-a" + Math.max(1, n);
    }

    /** Whether {@code id} is a Race Night id at all. */
    public static boolean isId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    /** Whether {@code id} is an admin-started night's. */
    public static boolean adminId(String id) {
        Matcher m = id == null ? null : ID.matcher(id);
        return m != null && m.matches() && m.group(3) != null;
    }

    /** The local date in a night's id ({@code rn-20261002-...} is 2 Oct 2026), or {@code null}. */
    public static LocalDate dateOf(String id) {
        Matcher m = id == null ? null : ID.matcher(id);
        if (m == null || !m.matches()) {
            return null;
        }
        try {
            return LocalDate.parse(m.group(1), DAY);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
