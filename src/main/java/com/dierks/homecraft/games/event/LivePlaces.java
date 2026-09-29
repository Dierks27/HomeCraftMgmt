package com.dierks.homecraft.games.event;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Live positions during a race (EVENTS-DROPPER-SPEC §A.4.9), for the bossbar ("2nd of 5 · Lap
 * 1/2"), the watchers' "leader: Sam" and the feed. Pure. Recomputed every few ticks, O(racers).
 *
 * <p>Order: finishers by finish time; then everyone still racing by targets reached (most first),
 * then distance to the next target (least first), then when they reached their last target
 * (earliest first); then anyone out of this race. The places shown before the line are for
 * display; the final order is by finish time.
 */
public final class LivePlaces {

    private LivePlaces() {
    }

    /** Where a racer is in the race. */
    public enum State {
        RACING,
        FINISHED,
        /** Left, voided, or not seated: shown last. */
        OUT
    }

    /**
     * One racer as last reported.
     *
     * @param reached   targets reached (checkpoints, then the finish)
     * @param toNext    blocks to the next target
     * @param reachedAt when they reached the last one (nanos), or their finish time for a finisher
     */
    public record Row(UUID player, State state, int reached, double toNext, long reachedAt) {
    }

    /** The rows in place order, first place first. */
    public static List<Row> rank(List<Row> rows) {
        List<Row> out = new ArrayList<>(rows);
        out.sort(Comparator.comparingInt((Row r) -> switch (r.state()) {
                    case FINISHED -> 0;
                    case RACING -> 1;
                    case OUT -> 2;
                })
                .thenComparing((a, b) -> a.state() == State.FINISHED ? Long.compare(a.reachedAt(), b.reachedAt()) : 0)
                .thenComparing(Comparator.comparingInt(Row::reached).reversed())
                .thenComparingDouble(Row::toNext)
                .thenComparingLong(Row::reachedAt));
        return out;
    }

    /**
     * The lap a racer is on (1-based, at most {@code laps}) after reaching {@code reached} of
     * {@code targets} targets over {@code laps} laps.
     */
    public static int lap(int reached, int targets, int laps) {
        if (laps <= 1 || targets <= 0) {
            return 1;
        }
        int perLap = Math.max(1, targets / laps);
        return Math.max(1, Math.min(laps, reached / perLap + 1));
    }

    /** The bossbar line: "&amp;e2nd &amp;7of 5 · Lap 1/2", "&amp;eLAST LAP! &amp;72nd of 5". */
    public static String bar(int place, int of, int lap, int laps) {
        String where = NightStandings.ordinal(place);
        if (laps > 1 && lap >= laps) {
            return "&eLAST LAP! &7" + where + " of " + of;
        }
        return "&e" + where + " &7of " + of + (laps > 1 ? " · Lap " + lap + "/" + laps : "");
    }
}
