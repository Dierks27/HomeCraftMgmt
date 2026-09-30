package com.dierks.homecraft.games.event;

import com.dierks.homecraft.config.GamesConfig;

import java.util.List;
import java.util.Locale;

/**
 * Race Night's settings: {@code games.race_night} (EVENTS-DROPPER-SPEC §A.10, EVENTS-RECONCILED
 * decisions 1 and 4). Ships OFF.
 *
 * <p>{@link #defaults()} is the single source of the shipped values: the bundled config.yml block
 * parses to exactly it without a WARN ({@code GamesConfigTest}). {@link #parse} reads each key over
 * the defaults: an out-of-range number is clamped with one WARN naming its full key, junk closes
 * only {@code race_night} (one WARN, {@link GamesConfig.Node#invalid}); it never throws.
 *
 * <p>The schedule's entries ({@code "FRI 19:00"}) are kept as written here; the event schedule
 * (WP-R2) reads them, dropping a bad one with one WARN while the rest keep working.
 *
 * @param enabled             the game's own switch (it also needs {@code games.enabled} and Time Trials)
 * @param schedule            {@code "<days> <HH:mm>"} entries in local {@code clock.time_zone}; empty =
 *                            admin-started nights only
 * @param course              {@code auto} (take turns among the raceable tracks) or a course id
 * @param races               races a night, 1-5 (more than 1 needs a track with a stand)
 * @param laps                0 = the course's own laps; 1-5 on loop tracks
 * @param announceMinutes     the chat heads-up this long before the start (0 = none)
 * @param joinMinutes         joining opens this long before a scheduled start
 * @param adminJoinMinutes    ... and before a night an admin starts
 * @param minRacers           fewer seated at Go calls the night off (2 up to {@code maxRacers})
 * @param maxRacers           the most racers, also limited by the track's grid spots; 2-12
 * @param finishWindowSeconds a race ends this long after its first finisher
 * @param maxRaceMinutes      ... or after this long
 * @param breakSeconds        the break between races
 * @param warmupSeconds       the shared warm-up of free laps before the grid (owner decision D3;
 *                            0 = none). The grid waits for it or for every racer to be ready
 * @param points              points by place in each race
 * @param finishPoints        points for a finisher beyond the list
 * @param stillRacingPoints   points for anyone still going when a race ends
 * @param prizes              tokens for the night's 1st, 2nd and 3rd (each 0-10)
 * @param finisherPrize       tokens for everyone else who finished a race (0-2)
 * @param prizeEventsPerWeek  nights a week that pay tokens, server-wide (0-7)
 * @param season              {@code month} (a monthly season board) or {@code off}
 * @param standRadius         how far from the stand a watcher may wander before being put back
 * @param hype                the heads-up and join-open chat say how many drops a Mountain Run has
 *                            ("This week: 5 drops down the mountain!", COURSE-VARIETY-SPEC §5.2, §6);
 *                            runtime copy only, it never shapes a layout
 */
public record RaceNightSettings(boolean enabled, List<String> schedule, String course, int races, int laps,
                                int announceMinutes, int joinMinutes, int adminJoinMinutes, int minRacers,
                                int maxRacers, int finishWindowSeconds, int maxRaceMinutes, int breakSeconds,
                                int warmupSeconds, List<Integer> points, int finishPoints, int stillRacingPoints,
                                List<Integer> prizes, int finisherPrize, int prizeEventsPerWeek, String season,
                                int standRadius, boolean hype) {

    /** The leaves under {@code games.race_night}, in config order. */
    public static final List<String> KEYS = List.of("enabled", "schedule", "course", "races", "laps",
            "announce_minutes", "join_minutes", "admin_join_minutes", "min_racers", "max_racers",
            "finish_window_seconds", "max_race_minutes", "break_seconds", "warmup_seconds", "points",
            "finish_points", "still_racing_points", "prizes", "finisher_prize", "prize_events_per_week", "season",
            "stand_radius", "hype");

    /** {@code season: month}: points also go on a monthly season board. */
    public static final String SEASON_MONTH = "month";
    /** {@code season: off}: no season board. */
    public static final String SEASON_OFF = "off";
    /** {@code course: auto}: take turns among the raceable tracks. */
    public static final String AUTO = "auto";
    /** The most racers a night, whatever config says. */
    public static final int MAX_RACERS = 12;

    public RaceNightSettings {
        schedule = List.copyOf(schedule == null ? List.of() : schedule);
        course = course == null || course.isBlank() ? AUTO : course.trim().toLowerCase(Locale.ROOT);
        points = List.copyOf(points == null ? List.of() : points);
        prizes = List.copyOf(prizes == null ? List.of() : prizes);
        season = season == null ? SEASON_MONTH : season.trim().toLowerCase(Locale.ROOT);
        maxRacers = Math.max(2, Math.min(MAX_RACERS, maxRacers));
        minRacers = Math.max(2, Math.min(maxRacers, minRacers));
    }

    /** The settings before the {@code hype} key (it reads as on, as shipped): every existing caller's shape. */
    public RaceNightSettings(boolean enabled, List<String> schedule, String course, int races, int laps,
                             int announceMinutes, int joinMinutes, int adminJoinMinutes, int minRacers, int maxRacers,
                             int finishWindowSeconds, int maxRaceMinutes, int breakSeconds, int warmupSeconds,
                             List<Integer> points, int finishPoints, int stillRacingPoints, List<Integer> prizes,
                             int finisherPrize, int prizeEventsPerWeek, String season, int standRadius) {
        this(enabled, schedule, course, races, laps, announceMinutes, joinMinutes, adminJoinMinutes, minRacers,
                maxRacers, finishWindowSeconds, maxRaceMinutes, breakSeconds, warmupSeconds, points, finishPoints,
                stillRacingPoints, prizes, finisherPrize, prizeEventsPerWeek, season, standRadius, true);
    }

    /**
     * The shipped settings (§A.10): off; Fridays at 7:00 PM; 3 races; prizes 5, 3, 2 and 1; the drop
     * hype on (COURSE-VARIETY-SPEC §6).
     */
    public static RaceNightSettings defaults() {
        return new RaceNightSettings(
                false,
                List.of("FRI 19:00"),
                AUTO,
                3,
                0,
                30,
                10,
                5,
                2,
                8,
                60,
                4,
                20,
                180,
                List.of(10, 8, 6, 5, 4, 3, 2),
                2,
                1,
                List.of(5, 3, 2),
                1,
                3,
                SEASON_MONTH,
                4,
                true);
    }

    /** Read {@code games.race_night} over {@code d}; never throws. */
    public static RaceNightSettings parse(GamesConfig.Node n, RaceNightSettings d) {
        boolean enabled = n.enabled(d.enabled());
        List<String> schedule = n.stringList("schedule", d.schedule());
        String course = n.text("course", d.course());
        if (!course.isBlank() && !course.toLowerCase(Locale.ROOT).matches("[a-z0-9_]+")) {
            n.invalid(n.key("course") + " should be auto or a course id, not \"" + course + "\"");
            course = d.course();
        }
        int races = n.whole("races", d.races(), 1, 5);
        int laps = n.whole("laps", d.laps(), 0, 5);
        int announceMinutes = n.whole("announce_minutes", d.announceMinutes(), 0, 720);
        int joinMinutes = n.whole("join_minutes", d.joinMinutes(), 1, 60);
        int adminJoinMinutes = n.whole("admin_join_minutes", d.adminJoinMinutes(), 1, 60);
        int maxRacers = n.whole("max_racers", d.maxRacers(), 2, MAX_RACERS);
        int minRacers = n.whole("min_racers", d.minRacers(), 2, maxRacers);
        int finishWindowSeconds = n.whole("finish_window_seconds", d.finishWindowSeconds(), 10, 600);
        int maxRaceMinutes = n.whole("max_race_minutes", d.maxRaceMinutes(), 1, 15);
        int breakSeconds = n.whole("break_seconds", d.breakSeconds(), 5, 120);
        int warmupSeconds = n.whole("warmup_seconds", d.warmupSeconds(), 0, 600);
        List<Integer> points = n.intList("points", d.points(), 0, 100);
        if (points.size() > MAX_RACERS) {
            n.invalid(n.key("points") + " " + points + " has more places than a night can have (" + MAX_RACERS + ")");
            points = d.points();
        }
        int finishPoints = n.whole("finish_points", d.finishPoints(), 0, 100);
        int stillRacingPoints = n.whole("still_racing_points", d.stillRacingPoints(), 0, 100);
        List<Integer> prizes = n.intList("prizes", d.prizes(), 0, 10, 3);
        int finisherPrize = n.whole("finisher_prize", d.finisherPrize(), 0, 2);
        int prizeEventsPerWeek = n.whole("prize_events_per_week", d.prizeEventsPerWeek(), 0, 7);
        String season = n.text("season", d.season()).toLowerCase(Locale.ROOT);
        if (!SEASON_MONTH.equals(season) && !SEASON_OFF.equals(season)) {
            n.invalid(n.key("season") + " should be month or off, not \"" + season + "\"");
            season = d.season();
        }
        int standRadius = n.whole("stand_radius", d.standRadius(), 2, 16);
        boolean hype = n.bool("hype", d.hype());
        return new RaceNightSettings(enabled, schedule, course, races, laps, announceMinutes, joinMinutes,
                adminJoinMinutes, minRacers, maxRacers, finishWindowSeconds, maxRaceMinutes, breakSeconds,
                warmupSeconds, points, finishPoints, stillRacingPoints, prizes, finisherPrize, prizeEventsPerWeek,
                season, standRadius, hype);
    }

    /** Whether points also go on a monthly season board. */
    public boolean seasonOn() {
        return SEASON_MONTH.equals(season);
    }

    /** Whether the course is chosen by taking turns among the raceable tracks. */
    public boolean autoCourse() {
        return AUTO.equals(course);
    }
}
