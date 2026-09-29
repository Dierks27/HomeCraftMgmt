package com.dierks.homecraft.games.trial;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Live race positions (EVENTS-DROPPER-SPEC §A.4.9): finishers first by their finish time; then
 * everyone still racing by the targets they have reached, then how close they are to the next one,
 * then when they reached their last; those who left last. The order is never a coin toss: the grid
 * order breaks what is still level.
 */
class RaceStandingsTest {

    private static final UUID A = new UUID(0, 1);
    private static final UUID B = new UUID(0, 2);
    private static final UUID C = new UUID(0, 3);
    private static final UUID D = new UUID(0, 4);
    private static final UUID E = new UUID(0, 5);
    private static final UUID F = new UUID(0, 6);

    private static RaceStandings.Row racing(UUID id, int reached, double toNext, long at, int order) {
        return new RaceStandings.Row(id, RaceStandings.State.RACING, reached, toNext, at, order);
    }

    private static RaceStandings.Row finished(UUID id, long at, int order) {
        return new RaceStandings.Row(id, RaceStandings.State.FINISHED, 9, 0, at, order);
    }

    private static RaceStandings.Row out(UUID id, int reached, int order) {
        return new RaceStandings.Row(id, RaceStandings.State.OUT, reached, 0, 0, order);
    }

    private static List<UUID> order(List<RaceStandings.Place> places) {
        List<UUID> out = new ArrayList<>();
        for (RaceStandings.Place p : places) {
            out.add(p.row().racer());
        }
        return out;
    }

    @Test
    void finishersComeFirstThenProgressThenDistanceThenTime() {
        List<RaceStandings.Row> rows = List.of(
                racing(A, 5, 3.0, 100, 0),
                out(B, 8, 1),
                finished(C, 41_200, 2),
                racing(D, 6, 9.0, 300, 3),
                racing(E, 5, 3.0, 90, 4),
                finished(F, 40_900, 5));
        List<RaceStandings.Place> places = RaceStandings.rank(rows);
        assertEquals(List.of(F, C, D, E, A, B), order(places),
                "F and C finished (F sooner), D has reached the most, E and A are level on targets and"
                        + " distance but E got there first, and B who left is last whatever B had done");
        assertEquals(List.of(1, 2, 3, 4, 5, 6), places.stream().map(RaceStandings.Place::rank).toList(),
                "places 1 to 5, and the one who left shares the place after the last one in");
        assertEquals(1, RaceStandings.placeOf(places, F), "F leads");
        assertEquals(3, RaceStandings.placeOf(places, D), "D is 3rd");
        assertEquals(0, RaceStandings.placeOf(places, new UUID(9, 9)), "someone not in the race has no place");
    }

    @Test
    void closerToTheNextTargetIsAheadOnTheSameTarget() {
        List<RaceStandings.Place> places = RaceStandings.rank(List.of(racing(A, 3, 12.0, 50, 0),
                racing(B, 3, 4.5, 80, 1)));
        assertEquals(List.of(B, A), order(places), "the same checkpoints, B nearer the next: B is ahead");
    }

    @Test
    void whatIsStillLevelGoesByTheGridNeverByChance() {
        List<RaceStandings.Place> places = RaceStandings.rank(List.of(racing(B, 0, 0, 0, 1), racing(A, 0, 0, 0, 0)));
        assertEquals(List.of(A, B), order(places), "on the grid, before anyone moves: the grid order");
        List<RaceStandings.Place> leavers = RaceStandings.rank(List.of(out(C, 0, 2), out(A, 0, 0), racing(B, 1, 0, 0, 1)));
        assertEquals(List.of(B, A, C), order(leavers), "the leavers after the one still racing, in grid order");
        assertEquals(2, leavers.get(1).rank(), "leavers share the place after the last one in");
        assertEquals(2, leavers.get(2).rank(), "both of them");
        assertEquals(List.of(), RaceStandings.rank(null), "no rows, no standings");
    }

    @Test
    void placesReadAsWords() {
        assertEquals("1st", RaceStandings.ordinal(1), "first");
        assertEquals("2nd", RaceStandings.ordinal(2), "second");
        assertEquals("3rd", RaceStandings.ordinal(3), "third");
        assertEquals("4th", RaceStandings.ordinal(4), "fourth");
        assertEquals("11th", RaceStandings.ordinal(11), "eleventh, not 11st");
        assertEquals("12th", RaceStandings.ordinal(12), "twelfth");
        assertEquals("13th", RaceStandings.ordinal(13), "thirteenth");
        assertEquals("21st", RaceStandings.ordinal(21), "twenty-first");
    }
}
