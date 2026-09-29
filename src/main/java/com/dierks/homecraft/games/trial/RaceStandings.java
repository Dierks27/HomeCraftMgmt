package com.dierks.homecraft.games.trial;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Live race positions (EVENTS-DROPPER-SPEC §A.4.9), pure and tested.
 *
 * <p>Who is ahead is decided by what a racer has already done, in this order: finishers by their
 * finish time; then everyone still racing by the targets they have reached (most first), then how
 * close they are to the next one (closest first), then when they reached their last one (earliest
 * first); and those who left (or whose race was voided) last. So a place shown before the line can
 * only be about the track, never about who happens to be moving; and once a racer crosses, nobody
 * behind can pass them, so their place is final at once.
 *
 * <p>It is recomputed every 5 ticks from a handful of rows, O(racers). The final order of a race is
 * by finish time, which is the first rule here.
 */
public final class RaceStandings {

    private RaceStandings() {
    }

    /** Where one racer is in a race. */
    public enum State {
        /** Still going. */
        RACING,
        /** Crossed the line. */
        FINISHED,
        /** Left, disconnected, or voided: no place from here on. */
        OUT
    }

    /**
     * One racer's row.
     *
     * @param racer   who
     * @param state   racing, finished or out
     * @param reached how many targets (checkpoints, then the finish) they have reached
     * @param toNext  the distance to the next target (ignored once finished)
     * @param at      when they reached their last target, or finished ({@code System.nanoTime()})
     * @param order   a tie-breaker that is never a coin toss: the grid order, then join order
     */
    public record Row(UUID racer, State state, int reached, double toNext, long at, int order) {
    }

    /**
     * A place in the standings.
     *
     * @param rank 1-based; racers who are out share the rank after the last one still in
     * @param row  the racer's row
     */
    public record Place(int rank, Row row) {
    }

    /** The order the rules above give: finishers, then racers, then those out. */
    public static final Comparator<Row> ORDER = Comparator
            .comparingInt((Row r) -> group(r.state()))
            .thenComparing((a, b) -> {
                if (a.state() == State.FINISHED && b.state() == State.FINISHED) {
                    return Long.compare(a.at(), b.at());
                }
                if (a.state() == State.RACING && b.state() == State.RACING) {
                    int c = Integer.compare(b.reached(), a.reached());
                    if (c != 0) {
                        return c;
                    }
                    c = Double.compare(a.toNext(), b.toNext());
                    return c != 0 ? c : Long.compare(a.at(), b.at());
                }
                return 0;
            })
            .thenComparingInt(Row::order);

    /**
     * The standings: every row ranked by the rules above. Finishers and racers each have their own
     * rank (1, 2, 3...); everyone who is out shares the rank after the last racer still in.
     */
    public static List<Place> rank(List<Row> rows) {
        List<Row> sorted = new ArrayList<>(rows == null ? List.of() : rows);
        sorted.sort(ORDER);
        List<Place> out = new ArrayList<>(sorted.size());
        int in = 0;
        for (Row r : sorted) {
            if (r.state() != State.OUT) {
                in++;
                out.add(new Place(in, r));
            }
        }
        for (Row r : sorted) {
            if (r.state() == State.OUT) {
                out.add(new Place(in + 1, r));
            }
        }
        return out;
    }

    /** Finishers first, then those still racing, then those out. */
    private static int group(State s) {
        return switch (s) {
            case FINISHED -> 0;
            case RACING -> 1;
            case OUT -> 2;
        };
    }

    /** The racer's place (1-based), or 0 when they aren't in the standings. */
    public static int placeOf(List<Place> standings, UUID racer) {
        for (Place p : standings) {
            if (p.row().racer().equals(racer)) {
                return p.rank();
            }
        }
        return 0;
    }

    /** "1st", "2nd", "3rd", "4th", ... "11th", "12th", "13th", "21st". */
    public static String ordinal(int n) {
        int mod100 = n % 100;
        String suffix = mod100 >= 11 && mod100 <= 13 ? "th"
                : switch (n % 10) {
                    case 1 -> "st";
                    case 2 -> "nd";
                    case 3 -> "rd";
                    default -> "th";
                };
        return n + suffix;
    }
}
