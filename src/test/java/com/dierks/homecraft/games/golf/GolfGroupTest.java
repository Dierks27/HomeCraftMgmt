package com.dierks.homecraft.games.golf;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golf together's turn flow and shared scorecard (EVENTS-OWNER-DECISIONS D4), with no server. Pinned
 * here: everyone plays the same hole at once; the hole ends only when every ball still in the group
 * is in or picked up, then everyone moves to the next tee together; leaving at any time is fine and
 * never holds the others up (a leaver who was the last one out ends the hole); the shared card shows
 * every player with their strokes; the ranking puts the fewest strokes first, level totals sharing a
 * place and anyone who left after the finishers; and a group is at most 4.
 */
class GolfGroupTest {

    private static final UUID SAM = new UUID(0, 1);
    private static final UUID AVA = new UUID(0, 2);
    private static final UUID LEE = new UUID(0, 3);

    private static GolfGroup group(UUID... who) {
        Map<UUID, String> names = new LinkedHashMap<>();
        for (UUID u : who) {
            names.put(u, u.equals(SAM) ? "Sam" : u.equals(AVA) ? "Ava" : "Lee");
        }
        return new GolfGroup(7, "meadow", "Meadow", List.of(3, 4, 3), names);
    }

    private static GolfRun.HoleScore score(int par, int strokes) {
        return new GolfRun.HoleScore(par, strokes, false);
    }

    @Test
    void theHoleEndsWhenEveryBallIsInThenEveryoneMovesOnTogether() {
        GolfGroup g = group(SAM, AVA, LEE);
        assertEquals(0, g.hole(), "everyone starts on hole 1");
        assertFalse(g.holeDone(SAM, score(3, 2)), "Sam is in: the others are still out");
        assertEquals(GolfGroup.Seat.WAITING, g.seat(SAM), "Sam waits");
        assertEquals(List.of("Ava", "Lee"), g.waitingFor(), "for Ava and Lee");
        assertFalse(g.advance(), "nobody moves on while a ball is out") ;
        assertEquals(0, g.hole(), "still hole 1");
        assertFalse(g.holeDone(AVA, new GolfRun.HoleScore(3, 5, true)), "Ava picked up: Lee is still out");
        assertTrue(g.holeDone(LEE, score(3, 3)), "Lee is in: the hole is over for everyone");
        assertTrue(g.advance(), "to the next tee together");
        assertEquals(1, g.hole(), "hole 2");
        for (UUID u : List.of(SAM, AVA, LEE)) {
            assertEquals(GolfGroup.Seat.PLAYING, g.seat(u), "everyone is playing hole 2");
        }
    }

    @Test
    void theRoundIsOverAfterTheLastHole() {
        GolfGroup g = group(SAM, AVA);
        for (int h = 0; h < 3; h++) {
            g.holeDone(SAM, score(3, 3));
            assertTrue(g.holeDone(AVA, score(3, 4)), "hole " + (h + 1) + " done");
            boolean more = g.advance();
            assertEquals(h < 2, more, h < 2 ? "on to the next tee" : "no next tee: the round is over");
        }
        assertTrue(g.over(), "over");
        assertTrue(g.card().over(), "the card says final");
        assertFalse(g.holeDone(SAM, score(3, 1)), "nothing more is scored");
        assertEquals(3, g.card().rows().get(0).scores().size(), "three holes each");
    }

    @Test
    void leavingIsFineAtAnyTimeAndNeverHoldsTheOthersUp() {
        GolfGroup g = group(SAM, AVA, LEE);
        g.holeDone(SAM, score(3, 2));
        g.holeDone(AVA, score(3, 3));
        assertTrue(g.left(LEE), "Lee leaves while the last one out: the hole is over for the others");
        assertTrue(g.advance(), "Sam and Ava move on");
        assertEquals(List.of(SAM, AVA), g.active(), "two still in");
        assertFalse(g.has(LEE), "Lee is out");
        assertEquals(GolfGroup.Seat.LEFT, g.seat(LEE), "and stays on the card as left");
        assertFalse(g.left(LEE), "leaving twice changes nothing");
        assertFalse(g.left(SAM), "Sam leaves mid-hole: Ava is still out");
        assertTrue(g.holeDone(AVA, score(4, 4)), "Ava alone finishes the hole");
        assertTrue(g.advance(), "and plays on");
        assertTrue(g.left(AVA) || g.over(), "the last one out");
        assertTrue(g.over(), "a group with nobody in it is over");
    }

    @Test
    void theSharedCardShowsEveryPlayer() {
        GolfGroup g = group(SAM, AVA);
        g.strokes(AVA, 2);
        g.holeDone(SAM, score(3, 2));
        GolfGroup.Card card = g.card();
        assertEquals(2, card.rows().size(), "one row each");
        assertEquals("Sam", card.rows().get(0).name(), "in join order");
        assertEquals(List.of(score(3, 2)), card.rows().get(0).scores(), "Sam's hole 1");
        assertEquals(GolfGroup.Seat.WAITING, card.rows().get(0).seat(), "Sam waiting");
        assertEquals(2, card.rows().get(1).strokes(), "Ava's strokes on the hole being played");
        assertEquals(10, card.par(), "par for the course");
        assertEquals("Meadow", card.courseName(), "the course");
    }

    @Test
    void theRankingIsFewestStrokesFirstTiesShareAndLeaversLast() {
        GolfGroup g = group(SAM, AVA, LEE);
        int[][] strokes = {{3, 4, 3}, {2, 5, 3}, {4, 4, 4}};
        for (int h = 0; h < 3; h++) {
            if (h == 1) {
                g.left(LEE);
            } else if (h == 0) {
                g.holeDone(LEE, score(3, strokes[2][h]));
            }
            g.holeDone(SAM, score(3, strokes[0][h]));
            g.holeDone(AVA, score(3, strokes[1][h]));
            g.advance();
        }
        List<GolfGroup.Standing> r = g.ranking();
        assertEquals(1, r.get(0).place(), "Sam and Ava both took 10: a shared 1st");
        assertEquals(1, r.get(1).place(), "a shared 1st");
        assertEquals(10, r.get(0).total(), "10 strokes");
        assertTrue(r.get(0).finished(), "every hole played");
        assertEquals("Lee", r.get(2).name(), "Lee left: listed after the finishers");
        assertEquals(0, r.get(2).place(), "and unplaced");
        assertFalse(r.get(2).finished(), "not every hole");
    }

    @Test
    void aGroupIsOneToFourPlayers() {
        Map<UUID, String> five = new LinkedHashMap<>();
        for (int i = 0; i < 5; i++) {
            five.put(new UUID(1, i), "P" + i);
        }
        assertThrows(IllegalArgumentException.class, () -> new GolfGroup(1, "m", "M", List.of(3), five),
                "golf together is at most 4");
        assertEquals(4, GolfGroup.MAX, "golf's party limit");
        assertEquals("1st", GolfGroup.ordinal(1), "1st");
        assertEquals("2nd", GolfGroup.ordinal(2), "2nd");
        assertEquals("3rd", GolfGroup.ordinal(3), "3rd");
        assertEquals("4th", GolfGroup.ordinal(4), "4th");
    }

    @Test
    void eachRoundIsItsOwnNormalRound() {
        // The group never scores a round itself: each player's GolfRun counts the strokes and the
        // finish records it exactly as alone. The group only copies the hole scores it is handed.
        GolfRun sam = new GolfRun(List.of(3, 4, 3), 3);
        GolfGroup g = group(SAM, AVA);
        sam.stroke();
        sam.stroke();
        GolfRun.HoleScore first = sam.inCup();
        g.holeDone(SAM, first);
        assertEquals(first, g.card().rows().get(0).scores().get(0), "the card shows the round's own score");
        assertEquals(1, sam.hole(), "the round itself moved on, as alone");
    }

    @Test
    void theNamesOfWhoIsStillOutReadNaturally() {
        assertEquals("the others", GolfRounds.names(List.of()), "nobody named");
        assertEquals("Sam", GolfRounds.names(List.of("Sam")), "one");
        assertEquals("Sam and Ava", GolfRounds.names(List.of("Sam", "Ava")), "two");
        assertEquals("Sam, Ava and Lee", GolfRounds.names(List.of("Sam", "Ava", "Lee")), "three");
    }
}
