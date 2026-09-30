package com.dierks.homecraft.games.cabinet.match;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mini Match's rules without a server.
 *
 * <p>Pinned here: the deal is a pure function of the seed (so the daily board is the same for
 * everyone) with every pair exactly twice; a flip is one go of two cards; a match stays up, a miss
 * shows until it is hidden or the next card is turned; a face-down card tells the screen nothing;
 * perfect memory always finishes in at most 15 flips (which is why the milestones are 30/24/20);
 * and the face pick is distinct, stable, and empty when there aren't enough Minis.
 */
class MatchEngineTest {

    @Test
    void theSameSeedDealsTheSameBoardWithEveryPairTwice() {
        assertArrayEquals(MatchEngine.deal(42), MatchEngine.deal(42), "the daily board must be one layout for everyone");
        int[] counts = new int[MatchEngine.PAIRS];
        for (int id : MatchEngine.deal(42)) {
            counts[id]++;
        }
        for (int c : counts) {
            assertEquals(2, c, "every pair is on the board exactly twice");
        }
        Set<String> boards = new HashSet<>();
        for (long seed = 0; seed < 50; seed++) {
            boards.add(Arrays.toString(MatchEngine.deal(seed)));
        }
        assertTrue(boards.size() > 45, "different seeds deal different boards, got " + boards.size() + " of 50");
    }

    @Test
    void aMatchStaysFaceUpAndCountsOneFlip() {
        MatchEngine e = new MatchEngine(7);
        int[] pair = pairOf(7, 0);
        assertEquals(MatchEngine.Flip.FIRST, e.flip(pair[0]), "the first card of a go");
        assertEquals(0, e.flips(), "a flip is counted when the second card turns, not the first");
        assertEquals(MatchEngine.Flip.MATCH, e.flip(pair[1]), "the two cards of pair 0 match");
        assertEquals(1, e.flips(), "one go = one flip");
        assertTrue(e.matched(pair[0]) && e.matched(pair[1]), "a found pair stays found");
        assertEquals(0, e.face(pair[0]), "a found pair keeps showing its face");
        assertEquals(MatchEngine.Flip.IGNORED, e.flip(pair[0]), "a found card can't be turned again");
        assertEquals(1, e.flips(), "tapping a found card costs nothing");
    }

    @Test
    void aMissShowsBothCardsUntilItIsHidden() {
        MatchEngine e = new MatchEngine(7);
        int a = pairOf(7, 0)[0];
        int b = pairOf(7, 1)[0];
        e.flip(a);
        assertEquals(MatchEngine.Flip.MISS, e.flip(b), "two different faces are a miss");
        assertEquals(1, e.flips(), "a miss is a flip too");
        assertTrue(e.missShowing() && e.faceUp(a) && e.faceUp(b), "both stay showing so the player can learn them");
        assertTrue(e.hideMiss(), "the screen's timer turns them back");
        assertFalse(e.faceUp(a) || e.faceUp(b), "and then both are face down");
        assertFalse(e.hideMiss(), "a second hide has nothing to do (an old timer firing late)");
    }

    @Test
    void turningACardWhileAMissShowsHidesTheMissFirst() {
        MatchEngine e = new MatchEngine(9);
        int a = pairOf(9, 0)[0];
        int b = pairOf(9, 1)[0];
        int c = pairOf(9, 2)[0];
        e.flip(a);
        e.flip(b);
        assertEquals(MatchEngine.Flip.FIRST, e.flip(c), "a quick player needn't wait for the miss to turn back");
        assertFalse(e.faceUp(a) || e.faceUp(b), "the old miss is face down again");
        e.flip(a);
        assertEquals(MatchEngine.Flip.FIRST, e.flip(b),
                "after a second miss (c, a), turning b hides it and starts a new go");
        assertFalse(e.faceUp(a), "a card of the hidden miss is face down");
        assertTrue(e.faceUp(b), "the card just turned is up");
        assertEquals(MatchEngine.Flip.MISS, e.flip(a), "and it can be turned again, as the second card");
    }

    @Test
    void turningTheSameCardTwiceIsNotAGo() {
        MatchEngine e = new MatchEngine(3);
        e.flip(5);
        assertEquals(MatchEngine.Flip.IGNORED, e.flip(5), "a double tap on one card must not count as a pair");
        assertEquals(0, e.flips(), "and costs nothing");
        assertEquals(MatchEngine.Flip.IGNORED, e.flip(-1), "off the board");
        assertEquals(MatchEngine.Flip.IGNORED, e.flip(MatchEngine.CARDS), "off the board");
    }

    @Test
    void aFaceDownCardTellsTheScreenNothing() {
        MatchEngine e = new MatchEngine(11);
        for (int i = 0; i < MatchEngine.CARDS; i++) {
            assertEquals(-1, e.face(i), "card " + i + " is face down: its face must not reach the screen");
        }
    }

    @Test
    void knowingTheLayoutFinishesInEightFlips() {
        MatchEngine e = new MatchEngine(123);
        MatchEngine.Flip last = null;
        for (int p = 0; p < MatchEngine.PAIRS; p++) {
            int[] pair = pairOf(123, p);
            e.flip(pair[0]);
            last = e.flip(pair[1]);
        }
        assertEquals(MatchEngine.Flip.DONE, last, "the last pair ends the game");
        assertTrue(e.done(), "every pair found");
        assertEquals(8, e.flips(), "the best possible score");
        assertEquals(MatchEngine.Flip.IGNORED, e.flip(0), "nothing more to turn once it's done");
    }

    @Test
    void perfectMemoryAlwaysFinishesInFifteenFlipsOrFewer() {
        for (long seed = 0; seed < 500; seed++) {
            int flips = playWithPerfectMemory(seed);
            assertTrue(flips >= 8 && flips <= 15,
                    "seed " + seed + ": a player who never forgets a card needs 8-15 flips, got " + flips
                            + " — the 30/24/20 milestones are measured against this");
        }
    }

    @Test
    void theFacePickIsDistinctStableAndEmptyWhenThereAreTooFew() {
        int[] pick = MatchEngine.pick(20, 8, 99);
        assertEquals(8, pick.length, "eight faces");
        assertEquals(8, Arrays.stream(pick).distinct().count(), "eight different Minis");
        assertTrue(Arrays.stream(pick).allMatch(i -> i >= 0 && i < 20), "all from the list");
        assertArrayEquals(pick, MatchEngine.pick(20, 8, 99), "the daily board shows the same Minis to everyone");
        assertEquals(0, MatchEngine.pick(7, 8, 99).length, "fewer than eight textured Minis: use the plain faces");
        assertEquals(8, MatchEngine.pick(8, 8, 1).length, "exactly eight is enough");
    }

    /** The two cards of pair {@code id} on the board dealt from {@code seed}. */
    private static int[] pairOf(long seed, int id) {
        int[] layout = MatchEngine.deal(seed);
        int[] out = new int[2];
        int n = 0;
        for (int i = 0; i < layout.length; i++) {
            if (layout[i] == id) {
                out[n++] = i;
            }
        }
        return out;
    }

    /** Play by remembering every face seen, reading faces only through the engine's public view. */
    private static int playWithPerfectMemory(long seed) {
        MatchEngine e = new MatchEngine(seed);
        Map<Integer, Integer> seen = new HashMap<>();
        while (!e.done()) {
            int[] known = knownPair(e, seen);
            if (known != null) {
                e.flip(known[0]);
                e.flip(known[1]);
                continue;
            }
            int a = nextUnseen(e, seen, -1);
            e.flip(a);
            seen.put(a, e.face(a));
            int partner = partnerOf(e, seen, a);
            int b = partner >= 0 ? partner : nextUnseen(e, seen, a);
            e.flip(b);
            seen.put(b, e.face(b));
        }
        return e.flips();
    }

    private static int[] knownPair(MatchEngine e, Map<Integer, Integer> seen) {
        Map<Integer, Integer> byFace = new HashMap<>();
        for (Map.Entry<Integer, Integer> s : seen.entrySet()) {
            if (e.matched(s.getKey())) {
                continue;
            }
            Integer other = byFace.put(s.getValue(), s.getKey());
            if (other != null) {
                return new int[] {other, s.getKey()};
            }
        }
        return null;
    }

    private static int partnerOf(MatchEngine e, Map<Integer, Integer> seen, int card) {
        for (Map.Entry<Integer, Integer> s : seen.entrySet()) {
            if (s.getKey() != card && !e.matched(s.getKey()) && s.getValue().equals(seen.get(card))) {
                return s.getKey();
            }
        }
        return -1;
    }

    private static int nextUnseen(MatchEngine e, Map<Integer, Integer> seen, int not) {
        for (int i = 0; i < MatchEngine.CARDS; i++) {
            if (i != not && !seen.containsKey(i) && !e.matched(i)) {
                return i;
            }
        }
        throw new AssertionError("no unseen card left");
    }
}
