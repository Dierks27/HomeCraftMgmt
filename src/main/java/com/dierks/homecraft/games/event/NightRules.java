package com.dierks.homecraft.games.event;

import com.dierks.homecraft.games.TokenBalance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The rules one Race Night keeps from the moment its join window opens (EVENTS-DROPPER-SPEC §A.9):
 * how many races and laps, the points, the prizes, how many racers, and the timings. A reload that
 * changes {@code games.race_night} never changes a night already open: these are stored with the
 * night's row ({@code game_events.settings}, {@code key=value} lines) and read back from there after
 * a restart.
 *
 * <p>Pure: no Bukkit, no clock. {@link #decode} never throws: a line it can't read keeps that key's
 * value from {@code fallback}, so a hand-edited row still runs.
 *
 * <p><b>Windows that scale with the track</b> (red-team F03, MOUNTAIN-V2-SPEC D8): a Mountain Run v2 is a
 * 2-3 minute run, so on one the night's finish window is at least 1.25 of the track's model time T_m
 * ({@link #finishWindow}) and its longest race at least 3 T_m ({@link #maxRace}, never over
 * {@value #MAX_RACE_MINUTES} minutes): a young rider at 0.45 of model speed behind a leader at 0.85 still
 * finishes inside the window. They are worked out once the track is known ({@link #of(RaceNightSettings,
 * int, int, boolean, int, long)}) and stored with the night like every other rule; config is untouched, and
 * any other track (T_m 0) keeps the configured values exactly. A night resumed after a restart widens them to
 * the track it resumes on ({@link #onTrack}, audit M05).
 *
 * @param races               races tonight (1-5; 1 without a stand)
 * @param laps                0 = the course's own laps, else 1-5 on a loop track
 * @param minRacers           fewer seated at race 1's Go calls the night off
 * @param maxRacers           the most who may join (already limited by the track's grid spots)
 * @param points              points by place in each race
 * @param finishPoints        points for a finisher beyond the list
 * @param stillRacingPoints   points for anyone still going when a race ends
 * @param prizes              tokens for the night's 1st, 2nd and 3rd
 * @param finisherPrize       tokens for every other racer who finished a race
 * @param fun                 a "just for fun" night: season points only, it never asks for a prize slot
 * @param warmupSeconds       the shared warm-up before race 1 (0 = none)
 * @param finishWindowSeconds a race ends this long after its first finisher
 * @param maxRaceMinutes      ... or this long after its Go
 * @param breakSeconds        the break between races
 */
public record NightRules(int races, int laps, int minRacers, int maxRacers, List<Integer> points, int finishPoints,
                         int stillRacingPoints, List<Integer> prizes, int finisherPrize, boolean fun,
                         int warmupSeconds, int finishWindowSeconds, int maxRaceMinutes, int breakSeconds) {

    /**
     * The most tokens one racer can win in one night, whatever config says (§A.3). EVENTS-RECONCILED 1
     * set it at 5 over prizes of 5/3/2; 0.37's token balance raised the prizes and moved this bound with
     * them ({@link com.dierks.homecraft.games.TokenBalance#RACE_MAX_PRIZE_PER_NIGHT}), so a night still
     * has a hard ceiling a little above the shipped 1st prize.
     */
    public static final int MAX_PRIZE_PER_NIGHT = TokenBalance.RACE_MAX_PRIZE_PER_NIGHT;
    /** The longest a race may be, in minutes, whatever config or the track says. */
    public static final int MAX_RACE_MINUTES = 15;
    /** A party race's finish window on any track, in seconds ({@code PartyRace}'s, before the track scales it). */
    public static final int PARTY_WINDOW_SECONDS = 120;

    public NightRules {
        races = Math.max(1, Math.min(5, races));
        laps = Math.max(0, Math.min(5, laps));
        maxRacers = Math.max(2, Math.min(RaceNightSettings.MAX_RACERS, maxRacers));
        minRacers = Math.max(2, Math.min(maxRacers, minRacers));
        points = List.copyOf(points == null ? List.of() : points);
        prizes = List.copyOf(prizes == null ? List.of() : prizes);
        finishPoints = Math.max(0, finishPoints);
        stillRacingPoints = Math.max(0, stillRacingPoints);
        finisherPrize = Math.max(0, Math.min(MAX_PRIZE_PER_NIGHT, finisherPrize));
        warmupSeconds = Math.max(0, Math.min(600, warmupSeconds));
        finishWindowSeconds = Math.max(10, Math.min(600, finishWindowSeconds));
        maxRaceMinutes = Math.max(1, Math.min(MAX_RACE_MINUTES, maxRaceMinutes));
        breakSeconds = Math.max(5, Math.min(120, breakSeconds));
    }

    /** A night's rules from the live settings, on a track whose windows don't scale (the configured ones). */
    public static NightRules of(RaceNightSettings s, int races, int laps, boolean fun, int maxRacers) {
        return of(s, races, laps, fun, maxRacers, 0);
    }

    /**
     * A night's rules from the live settings on a track of model time {@code modelMs} (a Mountain Run v2's
     * T_m, {@code BoatHype.modelMs}; 0 for any other track): the finish window and the longest race are the
     * track's effective ones ({@link #finishWindow}, {@link #maxRace}), so they are stored with the night.
     */
    public static NightRules of(RaceNightSettings s, int races, int laps, boolean fun, int maxRacers, long modelMs) {
        return new NightRules(races, laps, s.minRacers(), maxRacers, s.points(), s.finishPoints(),
                s.stillRacingPoints(), s.prizes(), s.finisherPrize(), fun, s.warmupSeconds(),
                finishWindow(s.finishWindowSeconds(), modelMs), maxRace(s.maxRaceMinutes(), modelMs), s.breakSeconds());
    }

    /**
     * The finish window, in seconds, on a track of model time {@code modelMs} (red-team F03):
     * max(config, ⌈1.25 T_m⌉); config itself for {@code modelMs} 0 (any track that doesn't scale).
     */
    public static int finishWindow(int configSeconds, long modelMs) {
        if (modelMs <= 0) {
            return configSeconds;
        }
        return (int) Math.max(configSeconds, Math.ceilDiv(modelMs * 5, 4_000L));
    }

    /**
     * The longest race, in minutes, on a track of model time {@code modelMs} (red-team F03):
     * max(config, ⌈3 T_m / 60⌉), never more than {@value #MAX_RACE_MINUTES}; config itself for {@code modelMs} 0.
     */
    public static int maxRace(int configMinutes, long modelMs) {
        if (modelMs <= 0) {
            return configMinutes;
        }
        long want = Math.min(MAX_RACE_MINUTES, Math.ceilDiv(modelMs * 3, 60_000L));
        return (int) Math.max(configMinutes, want);
    }

    /**
     * These rules on the track a night actually runs on, of model time {@code modelMs} (audit M05): the finish
     * window and the longest race widened to that track's ({@link #finishWindow}, {@link #maxRace}, each the
     * larger of the stored value and the track's), never lowered; these rules themselves for {@code modelMs} 0
     * (any track that doesn't scale). A night resumed after a restart runs with these, since the track it
     * resumes on may not be the one its row was stored for (a rebuilt week, a row from 0.36).
     */
    public NightRules onTrack(long modelMs) {
        if (modelMs <= 0) {
            return this;
        }
        return new NightRules(races, laps, minRacers, maxRacers, points, finishPoints, stillRacingPoints, prizes,
                finisherPrize, fun, warmupSeconds, finishWindow(finishWindowSeconds, modelMs),
                maxRace(maxRaceMinutes, modelMs), breakSeconds);
    }

    /**
     * A party race's finish window, in seconds, on a track of model time {@code modelMs} (red-team F03):
     * max({@value #PARTY_WINDOW_SECONDS}, ⌈1.25 T_m⌉); {@value #PARTY_WINDOW_SECONDS} for {@code modelMs} 0.
     */
    public static int partyWindow(long modelMs) {
        return finishWindow(PARTY_WINDOW_SECONDS, modelMs);
    }

    /** The night's settings as its row keeps them: one {@code key=value} line each, in a fixed order. */
    public String encode() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("races", Integer.toString(races));
        m.put("laps", Integer.toString(laps));
        m.put("min", Integer.toString(minRacers));
        m.put("max", Integer.toString(maxRacers));
        m.put("points", join(points));
        m.put("finish_points", Integer.toString(finishPoints));
        m.put("still_points", Integer.toString(stillRacingPoints));
        m.put("prizes", join(prizes));
        m.put("finisher_prize", Integer.toString(finisherPrize));
        m.put("fun", Boolean.toString(fun));
        m.put("warmup", Integer.toString(warmupSeconds));
        m.put("finish_window", Integer.toString(finishWindowSeconds));
        m.put("max_race", Integer.toString(maxRaceMinutes));
        m.put("break", Integer.toString(breakSeconds));
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, String> e : m.entrySet()) {
            b.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        return b.toString();
    }

    /** A stored night's rules; any key missing or unreadable keeps {@code fallback}'s. Never throws. */
    public static NightRules decode(String text, NightRules fallback) {
        Map<String, String> m = new LinkedHashMap<>();
        if (text != null) {
            for (String line : text.split("\n")) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    m.put(line.substring(0, eq).trim().toLowerCase(Locale.ROOT), line.substring(eq + 1).trim());
                }
            }
        }
        NightRules f = fallback;
        return new NightRules(num(m, "races", f.races), num(m, "laps", f.laps), num(m, "min", f.minRacers),
                num(m, "max", f.maxRacers), list(m, "points", f.points), num(m, "finish_points", f.finishPoints),
                num(m, "still_points", f.stillRacingPoints), list(m, "prizes", f.prizes),
                num(m, "finisher_prize", f.finisherPrize),
                m.containsKey("fun") ? Boolean.parseBoolean(m.get("fun")) : f.fun,
                num(m, "warmup", f.warmupSeconds), num(m, "finish_window", f.finishWindowSeconds),
                num(m, "max_race", f.maxRaceMinutes), num(m, "break", f.breakSeconds));
    }

    /** Whether the night may pay tokens at all (it still needs one of the week's prize slots). */
    public boolean wantsPrizes() {
        return !fun;
    }

    /**
     * The longest a night can take from its start to its end, for the restart fit (§A.2): the
     * warm-up, then each race at its longest with its break, plus 2 minutes for seating and
     * settling. 15 minutes for the shipped races without a warm-up.
     */
    public long worstMillis() {
        return warmupSeconds * 1000L + races * (maxRaceMinutes * 60_000L + breakSeconds * 1000L) + 120_000L;
    }

    private static int num(Map<String, String> m, String key, int fallback) {
        String v = m.get(key);
        if (v == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static List<Integer> list(Map<String, String> m, String key, List<Integer> fallback) {
        String v = m.get(key);
        if (v == null) {
            return fallback;
        }
        List<Integer> out = new ArrayList<>();
        if (v.isBlank()) {
            return out;
        }
        for (String part : v.split(",")) {
            try {
                out.add(Math.max(0, Integer.parseInt(part.trim())));
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return out;
    }

    private static String join(List<Integer> values) {
        StringBuilder b = new StringBuilder();
        for (int v : values) {
            if (!b.isEmpty()) {
                b.append(',');
            }
            b.append(v);
        }
        return b.toString();
    }
}
