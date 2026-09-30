package com.dierks.homecraft.games.clubhouse;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * {@code /hcm play cheer} (CLUBHOUSE-SPEC §9, §10): a watcher cheers for the racers of the race they
 * are watching, one action-bar line each ("Sam cheers for you!"). At most once every
 * {@value #EVERY_MS} ms per watcher, so nobody can flood a racer's screen; a racer who typed
 * {@code /hcm play cheers off} never sees one. Pure: the clock comes in.
 */
public final class Cheers {

    /** One cheer per watcher this often (ms). */
    public static final long EVERY_MS = 10_000L;
    /** The player preference that turns cheers off ("off"). */
    public static final String PREF = "cheers";

    private final Map<UUID, Long> last = new HashMap<>();

    /**
     * Whether {@code who} may cheer now; when true it counts as their cheer.
     */
    public boolean allow(UUID who, long now) {
        Long at = last.get(who);
        if (at != null && now - at < EVERY_MS) {
            return false;
        }
        last.put(who, now);
        return true;
    }

    /** Seconds until {@code who} may cheer again (0 when they may). */
    public long waitSeconds(UUID who, long now) {
        Long at = last.get(who);
        if (at == null || now - at >= EVERY_MS) {
            return 0;
        }
        return (EVERY_MS - (now - at) + 999) / 1000;
    }

    /** Forget a player (they quit). */
    public void forget(UUID who) {
        last.remove(who);
    }

    /** What a racer reads: "&amp;dSam cheers for you!". */
    public static String line(String name) {
        return "&d" + (name == null || name.isBlank() ? "Someone" : name) + " cheers for you!";
    }
}
