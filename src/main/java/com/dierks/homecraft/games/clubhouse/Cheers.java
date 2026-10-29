package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.golf.GolfNow;
import com.dierks.homecraft.games.trial.TrialsNow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code /hcm play cheer} (CLUBHOUSE-SPEC §9, §10): a watcher cheers for the racers of the race they
 * are watching, one action-bar line each ("Sam cheers for you!"); 0.38.3: with no race going, for everyone
 * on a solo run or a round alone, the ones Now playing shows (R383-J1: the board says "Cheer them on",
 * so the cheer reaches them). At most once every {@value #EVERY_MS} ms per watcher, so nobody can flood
 * a racer's screen; a racer who typed {@code /hcm play cheers off} never sees one. Pure: the clock comes in.
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

    /** Nobody to cheer for: no race, and nobody on a run or a round. */
    public static final String NOBODY = "&7Nobody's in a game to cheer for right now.";

    /**
     * Who a cheer reaches when no race is going (0.38.3): everyone on a solo run with the clock running or in
     * its warm-up (not the 3-2-1, not finished, never a racer: a race's cheer goes through the race), and
     * everyone in a round alone that isn't done; never the cheerer. Pure, for the tests.
     */
    public static List<UUID> solo(List<TrialsNow.Run> runs, List<GolfNow.Round> rounds, UUID cheerer) {
        List<UUID> out = new ArrayList<>();
        for (TrialsNow.Run r : runs == null ? List.<TrialsNow.Run>of() : runs) {
            if (!r.racing() && !r.starting() && !r.done() && !r.player().equals(cheerer) && !out.contains(r.player())) {
                out.add(r.player());
            }
        }
        for (GolfNow.Round r : rounds == null ? List.<GolfNow.Round>of() : rounds) {
            if (!r.together() && !r.done() && !r.player().equals(cheerer) && !out.contains(r.player())) {
                out.add(r.player());
            }
        }
        return out;
    }

    /** What a racer reads: "&amp;dSam cheers for you!". */
    public static String line(String name) {
        return "&d" + (name == null || name.isBlank() ? "Someone" : name) + " cheers for you!";
    }
}
