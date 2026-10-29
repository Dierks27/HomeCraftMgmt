package com.dierks.homecraft.golem;

import com.dierks.homecraft.golem.GolemRules.Act;
import com.dierks.homecraft.golem.GolemRules.Session;
import com.dierks.homecraft.golem.GolemRules.Sessions;
import com.dierks.homecraft.golem.GolemRules.Taps;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Copper Golem Brush's rules (0.38.4): a position packs and unpacks across the whole world (negatives and the
 * edges); a copper chest is always allowed, a golem with no chests uses any, one with chests only its own; a tap
 * opens, closes, switches, arms and clears (a single tap never clears; a late second shift-tap only re-arms); a
 * session runs out at 60 s; a dead golem's sessions end; a held tap counts once; the tag, the base name (never one
 * of our tags) and the reach; the stored list keeps its order, drops doubles and is cut at 32.
 */
class GolemRulesTest {

    private static final UUID P = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID Q = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID G1 = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID G2 = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    // ---- packing ---------------------------------------------------------------------------------

    @Test
    void aPositionRoundTripsAcrossTheWholeWorld() {
        int[][] cases = {
                {0, 0, 0}, {1, 2, 3}, {-1, -2, -3}, {120, 70, -340}, {-33_554_432, -64, 33_554_431},
                {33_554_431, 319, -33_554_432}, {12_345_678, -64, -12_345_678}, {7, 2047, 7}, {7, -2048, 7},
        };
        for (int[] c : cases) {
            long p = GolemRules.pack(c[0], c[1], c[2]);
            assertEquals(c[0], GolemRules.x(p), "x of " + c[0] + "," + c[1] + "," + c[2]);
            assertEquals(c[1], GolemRules.y(p), "y of " + c[0] + "," + c[1] + "," + c[2]);
            assertEquals(c[2], GolemRules.z(p), "z of " + c[0] + "," + c[1] + "," + c[2]);
        }
        assertTrue(GolemRules.pack(1, 2, 3) != GolemRules.pack(3, 2, 1), "x and z are told apart");
        assertTrue(GolemRules.pack(1, 2, 3) != GolemRules.pack(1, 3, 3), "y counts");
    }

    @Test
    void theStoredListKeepsItsOrderDropsDoublesAndIsCutAtThirtyTwo() {
        long a = GolemRules.pack(1, 64, 1);
        long b = GolemRules.pack(2, 64, 1);
        long c = GolemRules.pack(3, 64, 1);
        Set<Long> parsed = GolemRules.parse(new long[]{c, a, c, b});
        assertEquals(List.of(c, a, b), List.copyOf(parsed), "the link order: #1 is the first stored, doubles gone");
        assertEquals(List.of(c, a, b), List.copyOf(GolemRules.parse(GolemRules.format(parsed))), "and back");
        assertTrue(GolemRules.parse(null).isEmpty(), "nothing stored: no chests");
        long[] many = new long[40];
        for (int i = 0; i < many.length; i++) {
            many[i] = GolemRules.pack(i, 64, 0);
        }
        assertEquals(GolemRules.MAX_CHESTS, GolemRules.parse(many).size(), "cut at the cap");
    }

    // ---- the filter ------------------------------------------------------------------------------

    @Test
    void aCopperChestIsAlwaysAllowedAGolemWithNoChestsUsesAnyAndOneWithChestsOnlyItsOwn() {
        long mine = GolemRules.pack(10, 64, 10);
        long other = GolemRules.pack(11, 64, 10);
        Set<Long> assigned = new LinkedHashSet<>(List.of(mine));
        assertTrue(GolemRules.allow(true, assigned, other), "the copper chest is the source: always");
        assertTrue(GolemRules.allow(false, null, other), "no assignment: any container, as vanilla");
        assertTrue(GolemRules.allow(false, Set.of(), other), "an empty one too");
        assertTrue(GolemRules.allow(false, assigned, mine), "its own chest");
        assertFalse(GolemRules.allow(false, assigned, other), "another chest: denied");
    }

    // ---- sessions --------------------------------------------------------------------------------

    @Test
    void aPlainTapOpensAndAPlainTapOnTheSameGolemCloses() {
        Sessions s = new Sessions();
        assertEquals(Act.OPEN, s.tap(P, G1, false, 1_000));
        Session open = s.of(P);
        assertEquals(G1, open.golem());
        assertEquals(1_000 + GolemRules.SESSION_MS, open.endsAt());
        assertEquals(0, open.shiftAt(), "a plain tap doesn't arm");
        assertEquals(Act.CLOSE, s.tap(P, G1, false, 2_000));
        assertNull(s.of(P));
        assertTrue(s.isEmpty());
    }

    @Test
    void aTapOnAnotherGolemSwitches() {
        Sessions s = new Sessions();
        s.tap(P, G1, false, 1_000);
        assertEquals(Act.SWITCH, s.tap(P, G2, false, 2_000));
        assertEquals(G2, s.of(P).golem());
        assertEquals(2_000, s.of(P).startedAt(), "a fresh 60 s");
        assertEquals(Act.OPEN, s.tap(Q, G2, false, 2_000), "another player's own session, the same golem");
        assertEquals(G2, s.of(Q).golem());
    }

    @Test
    void twoShiftTapsWithinTwoSecondsClearAndALateOneOnlyReArms() {
        Sessions s = new Sessions();
        assertEquals(Act.OPEN, s.tap(P, G1, true, 1_000), "a shift-tap with no session opens...");
        assertEquals(1_000, s.of(P).shiftAt(), "...and arms");
        assertEquals(Act.CLEAR, s.tap(P, G1, true, 2_500), "the second within 2 s clears");
        assertNull(s.of(P), "and the session is over");

        assertEquals(Act.OPEN, s.tap(P, G1, false, 10_000));
        assertEquals(Act.ARM_CLEAR, s.tap(P, G1, true, 11_000), "a shift-tap on the session's golem arms");
        assertEquals(Act.ARM_CLEAR, s.tap(P, G1, true, 11_000 + GolemRules.CLEAR_MS + 1), "too late: re-arms");
        assertEquals(Act.CLEAR, s.tap(P, G1, true, 11_000 + GolemRules.CLEAR_MS + 1_500), "within 2 s of that");

        assertEquals(Act.OPEN, s.tap(P, G1, false, 20_000));
        assertEquals(Act.ARM_CLEAR, s.tap(P, G1, true, 21_000));
        assertEquals(Act.CLOSE, s.tap(P, G1, false, 21_500), "a plain tap after arming closes, never clears");
        assertEquals(Act.OPEN, s.tap(P, G1, true, 30_000));
        assertEquals(Act.SWITCH, s.tap(P, G2, true, 30_500), "a shift-tap on another golem switches, never clears");
        assertEquals(30_500, s.of(P).shiftAt(), "and arms the new one");
    }

    @Test
    void aSessionRunsOutAtSixtySecondsAndATapAfterThatOpensAfresh() {
        Sessions s = new Sessions();
        s.tap(P, G1, false, 1_000);
        s.tap(Q, G2, false, 5_000);
        assertEquals(List.of(), s.expired(1_000 + GolemRules.SESSION_MS - 1));
        List<Map.Entry<UUID, Session>> gone = s.expired(1_000 + GolemRules.SESSION_MS);
        assertEquals(1, gone.size());
        assertEquals(P, gone.get(0).getKey());
        assertEquals(G1, gone.get(0).getValue().golem());
        assertNull(s.of(P));
        assertEquals(G2, s.of(Q).golem(), "the other one runs on");
        assertEquals(Act.OPEN, s.tap(Q, G2, false, 5_000 + GolemRules.SESSION_MS + 10),
                "a tap on a session that ran out before the pass noticed opens a new one");
    }

    @Test
    void aDeadGolemsSessionsEndAndEndAllEndsEverything() {
        Sessions s = new Sessions();
        s.tap(P, G1, false, 1_000);
        s.tap(Q, G1, false, 1_000);
        UUID r = UUID.fromString("00000000-0000-0000-0000-000000000003");
        s.tap(r, G2, false, 1_000);
        List<UUID> ended = s.onGolem(G1);
        assertEquals(Set.of(P, Q), Set.copyOf(ended));
        assertNull(s.of(P));
        assertEquals(G2, s.of(r).golem());
        assertEquals(List.of(r), s.endAll());
        assertTrue(s.isEmpty());
        assertNull(s.end(P), "ending what isn't there is nothing");
    }

    // ---- held taps -------------------------------------------------------------------------------

    @Test
    void aHeldTapCountsOnceAndAQuickTapOnAnotherChestCountsAtOnce() {
        Taps taps = new Taps();
        long chestA = GolemRules.pack(1, 64, 1);
        long chestB = GolemRules.pack(2, 64, 1);
        assertTrue(taps.counts(P, chestA, 1_000));
        assertFalse(taps.counts(P, chestA, 1_200), "held: repeats every 200 ms");
        assertFalse(taps.counts(P, chestA, 1_400));
        assertFalse(taps.counts(P, chestA, 1_900), "every tap restarts the gap");
        assertTrue(taps.counts(P, chestB, 2_000), "another chest counts at once");
        assertTrue(taps.counts(P, chestA, 2_700), "and back, after the gap");
        assertTrue(taps.counts(Q, chestA, 2_700), "another player's tap is their own");
        assertTrue(taps.counts(P, G1, 2_800), "a golem tap after a chest tap");
        taps.forget(P);
        assertTrue(taps.counts(P, G1, 2_900), "forgotten at a quit");
    }

    // ---- the tag, the base, the reach ------------------------------------------------------------

    @Test
    void theTagSaysHowManyChestsOrWhatItHolds() {
        assertEquals("&6Rusty&7 -> no chests yet", GolemRules.tag("Rusty", 0, null));
        assertEquals("&6Rusty&7 -> 1 chest", GolemRules.tag("Rusty", 1, null));
        assertEquals("&6Rusty&7 -> 3 chests", GolemRules.tag("Rusty", 3, null));
        assertEquals("&6Bolt&7 - holding Cobblestone", GolemRules.tag("Bolt", 3, "Cobblestone"));
        assertEquals("&6Bolt&7 -> 3 chests", GolemRules.tag("Bolt", 3, "  "), "a blank hand is no hand");
        assertEquals("Cobblestone", GolemRules.pretty("COBBLESTONE"));
        assertEquals("Oak log", GolemRules.pretty("OAK_LOG"));
        assertEquals("", GolemRules.pretty(null));
    }

    @Test
    void theBaseIsTheGolemsOwnNameAndNeverOneOfOurTags() {
        assertEquals("Rusty", GolemRules.base(null, null), "no name: the default");
        assertEquals("Rusty", GolemRules.base("  ", null));
        assertEquals("Bolt", GolemRules.base("Bolt", null), "a name it was given is kept");
        assertEquals("Bolt", GolemRules.base("Bolt", "Old"), "the live name wins over the stored one");
        assertEquals("Bolt", GolemRules.base("Bolt -> 3 chests", "Bolt"), "our tag is never a base: the stored one");
        assertEquals("Bolt", GolemRules.base("Bolt - holding Cobblestone", "Bolt"));
        assertEquals("Rusty", GolemRules.base("Rusty -> no chests yet", null), "our tag and nothing stored");
        assertTrue(GolemRules.isTag("Rusty -> 2 chests"));
        assertFalse(GolemRules.isTag("Rusty"));
        assertFalse(GolemRules.isTag(null));
    }

    @Test
    void aChestIsInReachWithinThirtyTwoBlocksOneUpOrTwoDown() {
        assertTrue(GolemRules.nearEnough(0, 64, 0, 32, 64, -32));
        assertFalse(GolemRules.nearEnough(0, 64, 0, 33, 64, 0), "a block too far");
        assertFalse(GolemRules.nearEnough(0, 64, 0, 0, 64, -33));
        assertTrue(GolemRules.nearEnough(0, 64, 0, 5, 65, 5), "one up");
        assertFalse(GolemRules.nearEnough(0, 64, 0, 5, 66, 5), "two up: it can't reach");
        assertTrue(GolemRules.nearEnough(0, 64, 0, 5, 62, 5), "two down");
        assertFalse(GolemRules.nearEnough(0, 64, 0, 5, 61, 5), "three down");
    }

    @Test
    void chestsReadsAsWords() {
        assertEquals("no chests", GolemRules.chests(0));
        assertEquals("1 chest", GolemRules.chests(1));
        assertEquals("2 chests", GolemRules.chests(2));
    }
}
