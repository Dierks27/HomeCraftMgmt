package com.dierks.homecraft.games.event;

import com.dierks.homecraft.games.trial.BoatHype;
import com.dierks.homecraft.games.trial.Course;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * Race Night's words and numbers, in one place so the chat, the screens, the hub signs and the
 * feed say the same thing (EVENTS-DROPPER-SPEC §A.1, §A.6). Pure: every time is read in the zone
 * handed in. Kid copy: short words, no near-miss wording, nothing above U+FFFF.
 *
 * <p><b>The Mountain Run</b> (COURSE-VARIETY-SPEC §5.2): the Ice Boat's downhill sprint has no laps
 * to count, so its format reads "3 downhill races", and the heads-up and join-open lines can end with
 * its hype ("This week: 5 drops down the mountain!", {@link BoatHype#line}). Every other track reads
 * exactly as before.
 */
public final class EventCopy {

    /** The command that opens the Race Night screen, named in every chat line (Bedrock can't click). */
    public static final String COMMAND = "/hcm play race";
    /** The season board's prefix: {@code rnseason:2026-10}. */
    public static final String SEASON_PREFIX = "rnseason:";
    /** A night's board's prefix: {@code rnnight:rn-20261002-1900}. */
    public static final String NIGHT_PREFIX = "rnnight:";

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE h:mm a", Locale.US);
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.US);
    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("h:mm", Locale.US);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.US);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM", Locale.ROOT);

    private EventCopy() {
    }

    /** "Fri 7:00 PM". */
    public static String when(long millis, ZoneId zone) {
        return WHEN.format(Instant.ofEpochMilli(millis).atZone(zone));
    }

    /** "7:00 PM". */
    public static String clock(long millis, ZoneId zone) {
        return CLOCK.format(Instant.ofEpochMilli(millis).atZone(zone));
    }

    /** "7:00" (a sign's short time). */
    public static String shortClock(long millis, ZoneId zone) {
        return SHORT.format(Instant.ofEpochMilli(millis).atZone(zone));
    }

    /** "Fri 2 Oct". */
    public static String day(LocalDate date) {
        return DAY.format(date);
    }

    /** "in 2h 14m", "in 9m", "in 45s", "now". */
    public static String in(long ms) {
        if (ms <= 0) {
            return "now";
        }
        long s = ms / 1000;
        if (s < 60) {
            return "in " + s + "s";
        }
        long m = (ms + 59_999L) / 60_000L;
        if (m < 60) {
            return "in " + m + "m";
        }
        long h = m / 60;
        if (h < 48) {
            return "in " + h + "h " + (m % 60) + "m";
        }
        return "in " + (h / 24) + " days";
    }

    /** "9:58" as a countdown (minutes:seconds), for the join bossbar. */
    public static String countdown(long ms) {
        long s = Math.max(0, (ms + 999) / 1000);
        return s / 60 + ":" + (s % 60 < 10 ? "0" : "") + s % 60;
    }

    /** A race time: "0:41.2" (tenths, floored). */
    public static String time(long ms) {
        long t = Math.max(0, ms);
        long seconds = (t / 1000) % 60;
        return t / 60_000 + ":" + (seconds < 10 ? "0" : "") + seconds + "." + (t / 100) % 10;
    }

    /** "1 point", "18 points". */
    public static String points(long n) {
        return n + (n == 1 ? " point" : " points");
    }

    /** "1 token", "5 tokens". */
    public static String tokens(long n) {
        return n + (n == 1 ? " token" : " tokens");
    }

    /** "1 racer", "5 racers". */
    public static String racers(int n) {
        return n + (n == 1 ? " racer" : " racers");
    }

    /** "3 races, 2 laps", "1 race, 1 lap". */
    public static String format(int races, int laps) {
        return races + (races == 1 ? " race" : " races") + ", " + laps + (laps == 1 ? " lap" : " laps");
    }

    /** "3 downhill races" ({@code downhill}: a Mountain Run, a sprint with no laps), else {@link #format(int, int)}. */
    public static String format(int races, int laps, boolean downhill) {
        return downhill ? races + (races == 1 ? " downhill race" : " downhill races") : format(races, laps);
    }

    /** A night's format on {@code track}: "3 downhill races" on a Mountain Run, "3 races, 2 laps" on anything else. */
    public static String format(int races, int laps, Course track) {
        return format(races, laps, downhill(track));
    }

    /** Whether {@code track} is raced as a downhill sprint: a Mountain Run, which is never a loop. */
    public static boolean downhill(Course track) {
        return BoatHype.mountain(track) && !RaceTrack.loop(track);
    }

    /** A night's laps as the admin's status says them: "2 laps", "1 lap", or "a downhill sprint" on a Mountain Run. */
    public static String laps(int laps, Course track) {
        return downhill(track) ? "a downhill sprint" : laps + (laps == 1 ? " lap" : " laps");
    }

    // ---- the season -----------------------------------------------------------------------------

    /** The season key at {@code millis}: {@code 2026-10}. */
    public static String seasonKey(long millis, ZoneId zone) {
        return MONTH.format(Instant.ofEpochMilli(millis).atZone(zone));
    }

    /** The season board: {@code rnseason:2026-10}. */
    public static String seasonBoard(String key) {
        return SEASON_PREFIX + key;
    }

    /** A night's board: {@code rnnight:rn-20261002-1900}. */
    public static String nightBoard(String eventId) {
        return NIGHT_PREFIX + eventId;
    }

    /** "October" for {@code 2026-10}; the key itself when it can't be read. */
    public static String seasonName(String key) {
        try {
            return YearMonth.parse(key, MONTH).getMonth().getDisplayName(TextStyle.FULL, Locale.US);
        } catch (RuntimeException e) {
            return key;
        }
    }

    /** When the season {@code key} ends (the first instant of the next month, local), or 0. */
    public static long seasonEnds(String key, ZoneId zone) {
        try {
            return YearMonth.parse(key, MONTH).plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli();
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    /**
     * A Race Night board as the high-score screen labels it: "Race Night · Fri 2 Oct" for a night,
     * "Race Night · October" for a season; {@code null} for any other board.
     */
    public static String boardLabel(String board) {
        if (board == null) {
            return null;
        }
        if (board.startsWith(NIGHT_PREFIX)) {
            LocalDate d = EventPlan.dateOf(board.substring(NIGHT_PREFIX.length()));
            return "Race Night · " + (d == null ? "a past night" : day(d));
        }
        if (board.startsWith(SEASON_PREFIX)) {
            return "Race Night · " + seasonName(board.substring(SEASON_PREFIX.length()));
        }
        return null;
    }

    /** Whether a board is one of Race Night's (kept in points, higher is better). */
    public static boolean eventBoard(String board) {
        return board != null && (board.startsWith(NIGHT_PREFIX) || board.startsWith(SEASON_PREFIX));
    }

    // ---- the Games screen -----------------------------------------------------------------------

    /**
     * The Race Night tile's NAME, which carries the state (§A.6; Bedrock shows lore only on
     * tap-and-hold): "&amp;6Race Night &amp;7- Fri 7:00 PM", "&amp;aRace Night &amp;7- join now! 3/8",
     * "&amp;cRace Night &amp;7- racing (race 2 of 3)".
     */
    public static String tileName(EventMachine.Phase phase, long startsAt, int racers, int maxRacers, int race,
                                  int races, ZoneId zone) {
        if (phase == null) {
            return "&6Race Night &7- no race set";
        }
        return switch (phase) {
            case SCHEDULED -> "&6Race Night &7- " + when(startsAt, zone);
            case OPEN -> "&aRace Night &7- join now! " + racers + "/" + maxRacers;
            case WARMUP -> "&cRace Night &7- warm-up laps";
            case GRID, RACING, BREAK -> "&cRace Night &7- racing (race " + Math.max(1, race) + " of " + races + ")";
            case SETTLING, DONE -> "&6Race Night &7- results";
            case CALLED_OFF -> "&7Race Night &7- called off";
        };
    }

    // ---- chat lines -----------------------------------------------------------------------------

    /** The heads-up (T − 30 min). */
    public static String headsUp(long startsAt, long joinAt, String track, ZoneId zone) {
        return "&6Race Night at " + clock(startsAt, zone) + "! &7Boat races on " + track + " - join from "
                + clock(joinAt, zone) + ": &e" + COMMAND;
    }

    /** The heads-up with the track's hype at its end ({@link BoatHype#line}; {@code null}: none). */
    public static String headsUp(long startsAt, long joinAt, String track, ZoneId zone, String hype) {
        return withHype(headsUp(startsAt, joinAt, track, zone), hype);
    }

    /** The join window opening. */
    public static String joinOpen(long startsAt, String track, ZoneId zone) {
        return "&bRace Night &7on " + track + " starts at " + clock(startsAt, zone) + " - join now: &e" + COMMAND;
    }

    /** The join window opening with the track's hype at its end ({@link BoatHype#line}; {@code null}: none). */
    public static String joinOpen(long startsAt, String track, ZoneId zone, String hype) {
        return withHype(joinOpen(startsAt, track, zone), hype);
    }

    /** {@code line}, then {@code hype} after a space when there is one. */
    static String withHype(String line, String hype) {
        return hype == null || hype.isBlank() ? line : line + " " + hype;
    }

    /** The last call (T − 2 min). */
    public static String lastCall(int racers) {
        return "&bRace Night &7starts in 2 minutes! &7" + racers(racers) + " in so far - last call: &e" + COMMAND;
    }

    /** The join bossbar: "&amp;bRace Night &amp;7in &amp;e9:58 &amp;7· 0 racers · &amp;e/hcm play race". */
    public static String joinBar(long msLeft, int racers) {
        return "&bRace Night &7in &e" + countdown(msLeft) + " &7· " + racers(racers) + " · &e" + COMMAND;
    }
}
