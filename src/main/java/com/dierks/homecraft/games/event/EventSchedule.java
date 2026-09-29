package com.dierks.homecraft.games.event;

import com.dierks.homecraft.games.RestartHold;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * When Race Nights happen (EVENTS-DROPPER-SPEC §A.2): the schedule's entries, the nights they make,
 * and which of those can't run. Pure: no Bukkit, no clock ("now" is handed in).
 *
 * <p><b>Entries</b> ({@code games.race_night.schedule}) are {@code "<days> <HH:mm>"} in local
 * {@code clock.time_zone}: {@code "FRI 19:00"}, {@code "SAT,SUN 15:00"}, {@code "DAILY 18:30"},
 * {@code "WEEKDAYS 17:00"}, {@code "WEEKENDS 10:30"} (days by three letters or in full, any case). A
 * bad entry is dropped with one WARN and the rest keep working.
 *
 * <p><b>Times</b> are built with {@link RestartHold#instant}, so they stay on the wall clock across
 * DST: a time a spring-forward day skips runs at the first instant after the gap, and a time that
 * happens twice on a fall-back day runs at the first of the two.
 *
 * <p><b>Skipped nights</b> are never announced or opened; each has a reason in plain words:
 * <ul>
 *   <li>an admin skipped it ({@code race.skip.<id>});</li>
 *   <li>there is no track to race on;</li>
 *   <li>the <b>restart fit</b>: the whole night, {@code [joinAt, startsAt + worst]}, must end at least
 *       2 minutes before a scheduled restart's hold begins (with restarts at 16:00 and a 5-minute
 *       hold, 15:30 runs and 15:40 is skipped);</li>
 *   <li>on a Fresh course, the night may not touch {@code games.fresh.rebuild_at} ± 15 minutes;</li>
 *   <li>an entry less than {@code worst + join_minutes} after the night before it.</li>
 * </ul>
 */
public final class EventSchedule {

    /** A night must end this long before a restart's hold begins. */
    public static final long RESTART_MARGIN_MS = 2 * 60_000L;
    /** A night on a Fresh course keeps this far from the rebuild time, either side. */
    public static final long REBUILD_MARGIN_MS = 15 * 60_000L;

    private EventSchedule() {
    }

    /**
     * One schedule entry.
     *
     * @param days the days it runs
     * @param time the local start time
     * @param text what config says, for messages
     */
    public record Entry(Set<DayOfWeek> days, LocalTime time, String text) {

        public Entry {
            days = days == null || days.isEmpty() ? EnumSet.noneOf(DayOfWeek.class) : EnumSet.copyOf(days);
        }
    }

    /**
     * One night the schedule makes.
     *
     * @param id       its id ({@code rn-20261002-1900})
     * @param joinAt   when joining opens
     * @param startsAt its start
     * @param skip     why it can't run, or {@code null} when it fits
     */
    public record Occurrence(String id, LocalDate date, LocalTime time, long joinAt, long startsAt, String skip) {

        /** Whether it runs. */
        public boolean fits() {
            return skip == null;
        }

        Occurrence skipped(String why) {
            return new Occurrence(id, date, time, joinAt, startsAt, why);
        }
    }

    /**
     * What a night has to fit around.
     *
     * @param zone         where the times are read
     * @param joinMinutes  joining opens this long before the start
     * @param worstMs      the longest a night can take after its start ({@link NightRules#worstMillis})
     * @param hold         the scheduled restarts
     * @param freshRebuild {@code games.fresh.rebuild_at} when the track is a Fresh course, else {@code null}
     * @param trackProblem why there is no track to race on, or {@code null}
     * @param skipped      the ids an admin skipped
     */
    public record Fit(ZoneId zone, int joinMinutes, long worstMs, RestartHold hold, LocalTime freshRebuild,
                      String trackProblem, Set<String> skipped) {

        public Fit {
            skipped = skipped == null ? Set.of() : Set.copyOf(skipped);
        }
    }

    // ---- entries ------------------------------------------------------------------------------

    /** Read the entries; each bad one is dropped with one WARN through {@code warn}. Never throws. */
    public static List<Entry> parse(Collection<String> raw, Consumer<String> warn) {
        List<Entry> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (String text : raw) {
            Entry e = entry(text);
            if (e == null) {
                if (warn != null) {
                    warn.accept("games.race_night.schedule: \"" + text + "\" isn't \"<days> <HH:mm>\" (like \"FRI 19:00\""
                            + " or \"SAT,SUN 15:00\") - that entry is left out");
                }
                continue;
            }
            out.add(e);
        }
        return out;
    }

    /** One entry, or {@code null} when it can't be read. */
    public static Entry entry(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String[] parts = text.trim().split("\\s+");
        if (parts.length != 2) {
            return null;
        }
        LocalTime time = RestartHold.parseTime(parts[1]);
        Set<DayOfWeek> days = days(parts[0]);
        return time == null || days == null ? null : new Entry(days, time, text.trim());
    }

    /** {@code FRI}, {@code SAT,SUN}, {@code DAILY}, {@code WEEKDAYS}, {@code WEEKENDS}; {@code null} when unreadable. */
    static Set<DayOfWeek> days(String word) {
        String w = word.trim().toUpperCase(Locale.ROOT);
        switch (w) {
            case "DAILY", "EVERYDAY" -> {
                return EnumSet.allOf(DayOfWeek.class);
            }
            case "WEEKDAYS" -> {
                return EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY);
            }
            case "WEEKENDS" -> {
                return EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
            }
            default -> {
                Set<DayOfWeek> out = EnumSet.noneOf(DayOfWeek.class);
                for (String d : w.split(",")) {
                    DayOfWeek day = day(d.trim());
                    if (day == null) {
                        return null;
                    }
                    out.add(day);
                }
                return out.isEmpty() ? null : out;
            }
        }
    }

    private static DayOfWeek day(String d) {
        for (DayOfWeek day : DayOfWeek.values()) {
            if (d.equals(day.name()) || (d.length() == 3 && day.name().startsWith(d))) {
                return day;
            }
        }
        return null;
    }

    // ---- nights -------------------------------------------------------------------------------

    /**
     * Every night the entries make that starts in {@code [from, to]}, soonest first, each with why it
     * can't run ({@code null} when it fits).
     */
    public static List<Occurrence> between(List<Entry> entries, long from, long to, Fit fit) {
        Map<String, Occurrence> byId = new LinkedHashMap<>();
        ZoneId zone = fit.zone();
        LocalDate first = Instant.ofEpochMilli(from).atZone(zone).toLocalDate().minusDays(1);
        LocalDate last = Instant.ofEpochMilli(to).atZone(zone).toLocalDate().plusDays(1);
        for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
            for (Entry e : entries) {
                if (!e.days().contains(d.getDayOfWeek())) {
                    continue;
                }
                long starts = RestartHold.instant(d, e.time(), zone);
                if (starts < from || starts > to) {
                    continue;
                }
                String id = EventPlan.scheduledId(d, e.time());
                byId.putIfAbsent(id, new Occurrence(id, d, e.time(), starts - fit.joinMinutes() * 60_000L, starts, null));
            }
        }
        List<Occurrence> sorted = new ArrayList<>(byId.values());
        sorted.sort(Comparator.comparingLong(Occurrence::startsAt).thenComparing(Occurrence::id));
        List<Occurrence> out = new ArrayList<>();
        Occurrence kept = null;
        for (Occurrence o : sorted) {
            String why = why(o.joinAt(), o.startsAt(), o.id(), fit);
            if (why == null && kept != null && o.startsAt() - kept.startsAt() < fit.worstMs() + fit.joinMinutes() * 60_000L) {
                why = "too soon after the " + clock(kept.startsAt(), zone) + " night";
            }
            Occurrence r = why == null ? o : o.skipped(why);
            if (r.fits()) {
                kept = r;
            }
            out.add(r);
        }
        return out;
    }

    /** The next night that runs and can still be opened at {@code now} (starting more than a minute on), or {@code null}. */
    public static Occurrence next(List<Entry> entries, long now, Fit fit) {
        for (Occurrence o : between(entries, now - 8 * 86_400_000L, now + 15 * 86_400_000L, fit)) {
            if (o.fits() && o.startsAt() - 60_000L > now) {
                return o;
            }
        }
        return null;
    }

    /**
     * Why a night joining at {@code joinAt} and starting at {@code startsAt} can't run, or {@code null}:
     * an admin skipped it, there's no track, a restart would cut it off, or it touches the Fresh
     * rebuild. Also what an admin start is checked against.
     */
    public static String why(long joinAt, long startsAt, String id, Fit fit) {
        if (id != null && fit.skipped().contains(id)) {
            return "skipped by an admin";
        }
        if (fit.trackProblem() != null) {
            return fit.trackProblem();
        }
        String restart = restartProblem(joinAt, startsAt, fit.worstMs(), fit.hold());
        if (restart != null) {
            return restart;
        }
        return rebuildProblem(joinAt, startsAt + fit.worstMs(), fit.freshRebuild(), fit.zone());
    }

    /**
     * Why a scheduled restart would cut a night off, or {@code null}: its whole window
     * {@code [joinAt, startsAt + worst]} has to end {@value #RESTART_MARGIN_MS} ms before the next
     * restart's hold begins. "A restart is at 4:00 PM - Race Night needs 18 minutes."
     */
    public static String restartProblem(long joinAt, long startsAt, long worstMs, RestartHold hold) {
        if (hold == null || hold.off()) {
            return null;
        }
        long end = startsAt + worstMs;
        long restart = hold.next(joinAt);
        if (restart < 0 || restart - hold.holdMillis() - RESTART_MARGIN_MS >= end) {
            return null;
        }
        return "A restart is at " + hold.clock(restart) + " - Race Night needs " + minutes(worstMs) + ".";
    }

    /** Why a night on a Fresh course touches the rebuild time, or {@code null}. */
    static String rebuildProblem(long from, long to, LocalTime rebuild, ZoneId zone) {
        if (rebuild == null) {
            return null;
        }
        LocalDate first = Instant.ofEpochMilli(from).atZone(zone).toLocalDate().minusDays(1);
        LocalDate last = Instant.ofEpochMilli(to).atZone(zone).toLocalDate().plusDays(1);
        for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
            long at = RestartHold.instant(d, rebuild, zone);
            if (from < at + REBUILD_MARGIN_MS && to > at - REBUILD_MARGIN_MS) {
                return "too close to the Fresh Courses rebuild at " + clock(at, zone);
            }
        }
        return null;
    }

    /** "18 minutes", "1 minute". */
    static String minutes(long ms) {
        long m = (ms + 59_999L) / 60_000L;
        return m + (m == 1 ? " minute" : " minutes");
    }

    private static String clock(long millis, ZoneId zone) {
        return new RestartHold(List.of(), zone, RestartHold.DEFAULT_MINUTES).clock(millis);
    }
}
