package com.dierks.homecraft.games.event;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Who hears Race Night in chat (EVENTS-DROPPER-SPEC §A.6). Pure: no Bukkit.
 *
 * <p>A player who hasn't joined hears at most {@value #MAX_LINES} lines a night: the heads-up, the
 * join window, the last call and the results. Only while their news is on ({@code /hcm play news
 * off} silences Race Night too), they are in a world games are played in, and they are not in a
 * world game (nobody mid-course gets chat). Racers hear their own lines from the night instead,
 * never these.
 */
public final class Announcer {

    /** The most broadcast lines a player who hasn't joined hears in one night. */
    public static final int MAX_LINES = 4;

    /** The broadcast lines. */
    public enum Line {
        HEADS_UP, JOIN_OPEN, LAST_CALL, RESULTS
    }

    /**
     * One listener.
     *
     * @param newsOn       their news toggle (E2) is on
     * @param allowedWorld they are in a world games are played in
     * @param inSession    they are in a world game
     * @param racer        they joined tonight
     */
    public record Who(boolean newsOn, boolean allowedWorld, boolean inSession, boolean racer) {
    }

    private final Map<UUID, Integer> heard = new HashMap<>();
    private String night;

    /** Whether {@code who}, who heard {@code heardTonight} lines, hears {@code line}. */
    public static boolean hears(Line line, Who who, int heardTonight) {
        if (line == null || who == null || who.racer()) {
            return false;
        }
        return who.newsOn() && who.allowedWorld() && !who.inSession() && heardTonight < MAX_LINES;
    }

    /**
     * Whether {@code player} hears {@code line} of night {@code nightId}, counting it when they do. A
     * new night starts everyone's count again.
     */
    public boolean tell(String nightId, UUID player, Line line, Who who) {
        if (nightId != null && !nightId.equals(night)) {
            night = nightId;
            heard.clear();
        }
        int n = heard.getOrDefault(player, 0);
        if (!hears(line, who, n)) {
            return false;
        }
        heard.put(player, n + 1);
        return true;
    }

    /** How many lines the player heard tonight. */
    public int heard(UUID player) {
        return heard.getOrDefault(player, 0);
    }
}
