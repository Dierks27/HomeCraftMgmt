package com.dierks.homecraft.games.event;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Who hears Race Night in chat (EVENTS-DROPPER-SPEC §A.6). Pure: no Bukkit.
 *
 * <p>A player who hasn't joined hears at most {@value #MAX_LINES} lines a night: the heads-up, the
 * join window, the last call and the results. Only while their news is on ({@code /hcm play news
 * off} silences Race Night too), they may play the Games ({@code hcm.games.play}: a child whose parent
 * took the Games away gets no Race Night nudge at all, as with every other games nudge), they are in a
 * world games are played in, and they are not in a world game (nobody mid-course gets chat). Racers
 * hear their own lines from the night instead, never these.
 *
 * <p>The join window's bar asks {@link #joinBar} with the same {@link Who}, so the chat and the bar can't
 * drift apart (the final gate's #1: both reached players without {@code hcm.games.play}).
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
     * @param canPlay      they may play the Games ({@code hcm.games.play})
     * @param allowedWorld they are in a world games are played in
     * @param inSession    they are in a world game
     * @param racer        they joined tonight
     */
    public record Who(boolean newsOn, boolean canPlay, boolean allowedWorld, boolean inSession, boolean racer) {
    }

    private final Map<UUID, Integer> heard = new HashMap<>();
    private String night;

    /** Whether {@code who}, who heard {@code heardTonight} lines, hears {@code line}. */
    public static boolean hears(Line line, Who who, int heardTonight) {
        if (line == null || !nudged(who) || who.racer()) {
            return false;
        }
        return who.allowedWorld() && !who.inSession() && heardTonight < MAX_LINES;
    }

    /**
     * Whether the join window's countdown bar shows for {@code who}: news on and allowed to play, and
     * either in a world games are played in (they could join from there) or joined already (the time to
     * the start, wherever they are).
     */
    public static boolean joinBar(Who who) {
        return nudged(who) && (who.allowedWorld() || who.racer());
    }

    /** Race Night may nudge them at all: their news is on and they may play the Games. */
    private static boolean nudged(Who who) {
        return who != null && who.newsOn() && who.canPlay();
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
