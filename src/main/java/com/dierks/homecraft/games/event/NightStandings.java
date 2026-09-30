package com.dierks.homecraft.games.event;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Race Night's points (EVENTS-DROPPER-SPEC §A.4.5 steps 5 and 7, §A.4.3): one race's result as
 * points, the night's standings by points and countback, and the grid order for the next race.
 * Pure: no Bukkit.
 *
 * <p><b>A race's points</b> ({@code points: [10, 8, 6, 5, 4, 3, 2]}):
 * <ul>
 *   <li>a finisher within the list scores by place; beyond it, {@code finish_points} (2);</li>
 *   <li>anyone still racing at the end scores {@code still_racing_points} (1): "you still get a
 *       point. Great racing!";</li>
 *   <li>someone who left, was voided or wasn't seated scores 0, and the places close up (a voided
 *       finish takes no place).</li>
 * </ul>
 * Two finishes on exactly the same millisecond share the place and its points; the next place is
 * skipped.
 *
 * <p><b>The night</b> is ranked by points, then <b>countback</b>: more 1st places, then more 2nd
 * places, and so on. Racers still level share the place (and so a prize).
 *
 * <p><b>The grid</b> (published, no chance): race 1 puts the fewest SEASON points at the front,
 * later races the fewest points TONIGHT; level racers go by who joined first.
 */
public final class NightStandings {

    private NightStandings() {
    }

    /** How a racer's race ended (the {@code game_event_races.result} column). */
    public enum Result {
        /** Crossed the line, counted. */
        FINISHED,
        /** Still going when the race ended. */
        STILL_RACING,
        /** Left the race (Leave game, a disconnect, the session ended). */
        LEFT,
        /** Crossed the line, but a fair-play rule voided the run. */
        VOID,
        /** Not seated when the race began. */
        DNS
    }

    /**
     * One racer's race as it ended.
     *
     * @param ms      the finish time from the shared Go (FINISHED, VOID), else 0
     * @param targets targets reached (for the record, and a still-racer's order)
     */
    public record Row(UUID player, Result result, long ms, int targets) {
    }

    /**
     * One racer's scored race.
     *
     * @param place  the finishing place (1-based), or {@code null} for a racer who didn't finish counted
     * @param points the race's points
     */
    public record Scored(UUID player, Result result, Integer place, long ms, int targets, int points) {
    }

    /**
     * One racer's line in the night's standings.
     *
     * @param place  the night's place, shared by racers level on points and countback
     * @param points the night's points
     */
    public record Ranked(UUID player, int place, int points) {
    }

    // ---- one race -----------------------------------------------------------------------------

    /** Score one race's rows (any order); the result lists finishers by place first, then the rest. */
    public static List<Scored> score(List<Row> rows, List<Integer> points, int finishPoints, int stillRacingPoints) {
        List<Row> finishers = new ArrayList<>();
        List<Row> rest = new ArrayList<>();
        for (Row r : rows) {
            if (r.result() == Result.FINISHED) {
                finishers.add(r);
            } else {
                rest.add(r);
            }
        }
        finishers.sort(Comparator.comparingLong(Row::ms));
        List<Scored> out = new ArrayList<>();
        int place = 0;
        long lastMs = Long.MIN_VALUE;
        for (int i = 0; i < finishers.size(); i++) {
            Row r = finishers.get(i);
            if (i == 0 || r.ms() != lastMs) {
                place = i + 1;
            }
            lastMs = r.ms();
            out.add(new Scored(r.player(), r.result(), place, r.ms(), r.targets(),
                    placePoints(place, points, finishPoints)));
        }
        rest.sort(Comparator.comparingInt((Row r) -> r.result() == Result.STILL_RACING ? 0 : 1)
                .thenComparing(Comparator.comparingInt(Row::targets).reversed()));
        for (Row r : rest) {
            int p = r.result() == Result.STILL_RACING ? Math.max(0, stillRacingPoints) : 0;
            out.add(new Scored(r.player(), r.result(), null, r.ms(), r.targets(), p));
        }
        return out;
    }

    /** A finisher's points for {@code place}: from the list, else {@code finishPoints}. */
    public static int placePoints(int place, List<Integer> points, int finishPoints) {
        if (place >= 1 && points != null && place <= points.size()) {
            return Math.max(0, points.get(place - 1));
        }
        return Math.max(0, finishPoints);
    }

    // ---- the night ----------------------------------------------------------------------------

    /**
     * The night's standings: most points first, then countback (more 1st places, then 2nd...), then
     * the order they joined (for display only: racers level on points and countback share a place).
     *
     * @param points each racer's points tonight, in join order (a {@link LinkedHashMap})
     * @param places each racer's finishing places so far, one per counted finish
     */
    public static List<Ranked> rank(Map<UUID, Integer> points, Map<UUID, List<Integer>> places) {
        List<UUID> order = new ArrayList<>(points.keySet());
        Map<UUID, Integer> joined = new HashMap<>();
        for (int i = 0; i < order.size(); i++) {
            joined.put(order.get(i), i);
        }
        int maxPlace = 0;
        for (List<Integer> ps : places.values()) {
            for (Integer p : ps) {
                if (p != null) {
                    maxPlace = Math.max(maxPlace, p);
                }
            }
        }
        Map<UUID, int[]> counts = new HashMap<>();
        for (UUID id : order) {
            int[] c = new int[maxPlace + 1];
            for (Integer p : places.getOrDefault(id, List.of())) {
                if (p != null && p >= 1) {
                    c[p]++;
                }
            }
            counts.put(id, c);
        }
        Comparator<UUID> byPoints = Comparator.comparingInt((UUID id) -> points.getOrDefault(id, 0)).reversed();
        Comparator<UUID> countback = (a, b) -> {
            int[] ca = counts.get(a);
            int[] cb = counts.get(b);
            for (int p = 1; p < ca.length; p++) {
                if (ca[p] != cb[p]) {
                    return Integer.compare(cb[p], ca[p]);
                }
            }
            return 0;
        };
        Comparator<UUID> level = byPoints.thenComparing(countback);
        order.sort(level.thenComparingInt(joined::get));
        List<Ranked> out = new ArrayList<>();
        for (int i = 0; i < order.size(); i++) {
            UUID id = order.get(i);
            int place = i > 0 && level.compare(order.get(i - 1), id) == 0 ? out.get(i - 1).place() : i + 1;
            out.add(new Ranked(id, place, points.getOrDefault(id, 0)));
        }
        return out;
    }

    /**
     * The grid for a race, pole first: the fewest points at the front, level racers in the order they
     * joined. Race 1 passes the season points, later races tonight's.
     *
     * @param joinOrder the racers in the order they joined
     */
    public static List<UUID> grid(List<UUID> joinOrder, Map<UUID, Long> points) {
        List<UUID> out = new ArrayList<>(joinOrder);
        Map<UUID, Integer> joined = new HashMap<>();
        for (int i = 0; i < joinOrder.size(); i++) {
            joined.putIfAbsent(joinOrder.get(i), i);
        }
        out.sort(Comparator.comparingLong((UUID id) -> points.getOrDefault(id, 0L)).thenComparingInt(joined::get));
        return out;
    }

    /** "1st", "2nd", "3rd", "4th", "11th", "12th", "13th", "21st". */
    public static String ordinal(int n) {
        int mod100 = n % 100;
        if (mod100 >= 11 && mod100 <= 13) {
            return n + "th";
        }
        return switch (n % 10) {
            case 1 -> n + "st";
            case 2 -> n + "nd";
            case 3 -> n + "rd";
            default -> n + "th";
        };
    }
}
