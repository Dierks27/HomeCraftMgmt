package com.dierks.homecraft.games.gen.boat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The track as a graph (Course Variety §2.10 V2, V3, V6): where a boat fits (a 2 x 2 of level
 * track), how level fits join into decks, that decks only join downhill, and that a boat's reach
 * never climbs and stops at a blocked disk.
 */
class DeckGraphTest {

    private static final int N = DeckGraph.NONE;

    /** A 3-wide strip 8 long along x, level 5 for x 0-3 and 4 for x 4-7 (a drop of 1 after x 3). */
    private static int[][] strip() {
        int[][] h = new int[8][3];
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 3; z++) {
                h[x][z] = x <= 3 ? 5 : 4;
            }
        }
        return h;
    }

    @Test
    void aBoatFitsWhereTwoByTwoIsLevel() {
        int[][] h = strip();
        h[7][0] = N;
        h[7][2] = N; // a 1-wide nose at the end
        DeckGraph g = DeckGraph.of(h);
        assertTrue(g.fit(0, 0) && g.fit(3, 1) && g.fit(4, 1), "the wide strip fits a boat either side of the drop");
        assertFalse(g.fit(7, 1), "a 1-wide nose doesn't");
        assertFalse(g.fit(-1, 0), "outside is nothing");
        assertEquals(5, g.height(0, 0), "heights read back");
        assertEquals(N, g.height(7, 0), "and none where there is no track");
    }

    @Test
    void decksJoinOnlyDownhill() {
        DeckGraph g = DeckGraph.of(strip());
        assertEquals(2, g.decks(), "one deck above the drop, one below");
        int top = g.deck(0, 0);
        int low = g.deck(6, 1);
        assertEquals(List.of(low), g.below(top), "the top deck drops to the low one");
        assertEquals(List.of(), g.below(low), "and nothing climbs back");
        assertTrue(g.decksFrom(top)[low], "the low deck is reachable from the top");
        assertFalse(g.decksFrom(low)[top], "never the other way");
        assertTrue(g.decksTo(low)[top] && g.decksTo(low)[low], "both reach the low deck");
        assertFalse(g.decksTo(top)[low], "the low deck can't reach the top");
        assertEquals(5, g.deckHeight(top), "a deck's height");
        assertEquals(0, g.deckCell(top)[0], "a deck's first cell, for saying where it is");
    }

    @Test
    void aBoatsReachNeverClimbsAndStopsAtABlockedDisk() {
        DeckGraph g = DeckGraph.of(strip());
        boolean[][] fromTop = g.reach(0, 1, null);
        assertTrue(fromTop[7][1], "from the top the whole strip");
        boolean[][] fromLow = g.reach(6, 1, null);
        assertFalse(fromLow[2][1], "from below the drop the top is out of reach: a boat can't climb");
        assertTrue(fromLow[4][0], "but the low deck is all reachable, back and forth");
        boolean[][] cut = new boolean[8][3];
        for (int z = 0; z < 3; z++) {
            cut[5][z] = true;
        }
        boolean[][] blocked = g.reach(0, 1, cut);
        assertFalse(blocked[7][1], "a disk across the strip is a cut");
        assertTrue(blocked[4][1], "and everything before it is still reached");
        assertFalse(g.reach(5, 1, cut)[5][1], "starting inside a blocked disk reaches nothing");
    }

    @Test
    void stepsCountAlongTheTrackEitherWay() {
        DeckGraph g = DeckGraph.of(strip());
        int[][] steps = g.steps(List.of(new int[]{7, 1}), 3);
        assertEquals(0, steps[7][1], "the start of the count");
        assertEquals(3, steps[4][1], "three steps back, up the drop (a sphere doesn't care which way)");
        assertEquals(-1, steps[2][1], "farther than the limit is -1");
    }
}
