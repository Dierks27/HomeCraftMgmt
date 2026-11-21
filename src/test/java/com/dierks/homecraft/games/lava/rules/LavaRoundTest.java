package com.dierks.homecraft.games.lava.rules;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Floor Is Lava's rules as a pure state machine: the phase clock (safe, warning, lava, drain) for each mode, and
 * what each mode means. Little can't be lost and ends exactly when every star is collected; Group's shared count and
 * its cost for a touch, never below zero; Challenge's rising lava, who is out and who wins; and one player alone in
 * each.
 */
class LavaRoundTest {

    private static final UUID A = UUID.nameUUIDFromBytes("a".getBytes());
    private static final UUID B = UUID.nameUUIDFromBytes("b".getBytes());
    private static final UUID C = UUID.nameUUIDFromBytes("c".getBytes());

    /** 3 stars; safe 10, warning 4, lava 6, drain 2 ticks; 3 rounds; dry ground up to level 4, flooded at 5. */
    private static LavaRound.Rules rules(int gather) {
        return new LavaRound.Rules(3, 10, 4, 6, 2, 3, 4, 5, 4, gather);
    }

    private static List<LavaEvent> run(LavaRound r, int ticks) {
        List<LavaEvent> out = new ArrayList<>();
        for (int i = 0; i < ticks; i++) {
            out.addAll(r.tick());
        }
        return out;
    }

    private static <T extends LavaEvent> List<T> of(List<LavaEvent> events, Class<T> type) {
        List<T> out = new ArrayList<>();
        for (LavaEvent e : events) {
            if (type.isInstance(e)) {
                out.add(type.cast(e));
            }
        }
        return out;
    }

    private static LavaResult ended(List<LavaEvent> events) {
        List<LavaEvent.Ended> e = of(events, LavaEvent.Ended.class);
        assertEquals(1, e.size(), "the round ended once: " + events);
        return e.get(0).result();
    }

    // ---- the clock ----------------------------------------------------------------------------------

    @Test
    void thePhasesGoSafeWarningLavaDrainAndRoundAgain() {
        LavaRound r = new LavaRound(LavaMode.LITTLE, rules(0));
        assertEquals(LavaRound.Phase.GATHER, r.phase());
        assertEquals(List.of(), r.tick(), "nobody here: nothing begins");
        assertEquals(LavaRound.Join.JOINED, r.join(A));
        List<LavaEvent> first = r.tick();
        assertEquals(List.of(A), of(first, LavaEvent.Started.class).get(0).players(), "Little starts at once");
        assertEquals(LavaRound.Phase.SAFE, r.phase());
        assertEquals(1, r.round());
        assertEquals(0, r.level(), "a dry floor");
        assertEquals(List.of(0, 1, 2), of(first, LavaEvent.StarsOut.class).get(0).ids(), "every star is out");

        assertEquals(List.of(), run(r, 9), "nine quiet ticks of safe");
        List<LavaEvent> warn = r.tick();
        assertEquals(LavaRound.Phase.WARNING, r.phase(), "then the warning");
        assertEquals(new LavaEvent.PhaseChanged(LavaRound.Phase.WARNING, 1, 0, 4), warn.get(0));
        assertEquals(1, r.nextLevel(), "the lava that is coming is one deep");

        List<LavaEvent> lava = run(r, 4);
        assertEquals(LavaRound.Phase.LAVA, r.phase());
        assertEquals(1, r.level(), "Little's lava is always one block deep");
        assertEquals(1, of(lava, LavaEvent.StarsIn.class).size(), "the stars are put away when the lava comes");
        assertEquals(List.of(), r.starsOut());

        List<LavaEvent> drain = run(r, 6);
        assertEquals(LavaRound.Phase.DRAIN, r.phase());
        assertEquals(0, r.level(), "it drains to nothing");
        assertEquals(new LavaEvent.PhaseChanged(LavaRound.Phase.DRAIN, 1, 0, 2), drain.get(0));
        assertEquals(1, r.lavaCame());

        List<LavaEvent> again = run(r, 2);
        assertEquals(LavaRound.Phase.SAFE, r.phase(), "and the floor is safe again");
        assertEquals(2, r.round());
        assertEquals(List.of(0, 1, 2), of(again, LavaEvent.StarsOut.class).get(0).ids(), "the same stars, out again");
        assertEquals(2, of(again, LavaEvent.StarsOut.class).get(0).set(), "a new set: they are put somewhere new");
    }

    @Test
    void groupAndChallengeWaitForPlayersAndAnyoneMayStartSooner() {
        LavaRound r = new LavaRound(LavaMode.GROUP, rules(5));
        assertEquals(List.of(), run(r, 20), "the wait begins with the first player");
        r.join(A);
        assertEquals(List.of(), run(r, 4));
        assertEquals(LavaRound.Phase.GATHER, r.phase());
        assertEquals(LavaRound.Join.JOINED, r.join(B), "others join during the wait");
        List<LavaEvent> go = r.tick();
        assertEquals(List.of(A, B), of(go, LavaEvent.Started.class).get(0).players());
        assertEquals(LavaRound.Phase.SAFE, r.phase());

        LavaRound alone = new LavaRound(LavaMode.CHALLENGE, rules(500));
        alone.join(A);
        assertEquals(List.of(), alone.tick());
        List<LavaEvent> now = alone.startNow();
        assertEquals(LavaRound.Phase.SAFE, alone.phase(), "Start now: it can be started alone");
        assertEquals(1, of(now, LavaEvent.Started.class).size());
        assertEquals(List.of(), alone.startNow(), "once");
        assertEquals(List.of(), new LavaRound(LavaMode.GROUP, rules(5)).startNow(), "never with nobody in it");
    }

    @Test
    void challengesClimbingTimeShrinksAndItsLavaNeverDrains() {
        LavaRound.Rules rules = new LavaRound.Rules(3, 200, 4, 6, 2, 3, 4, 5, 4, 0);
        assertEquals(200, rules.safeTicks(LavaMode.LITTLE, 4), "Little and Group keep the time they were given");
        assertEquals(200, rules.safeTicks(LavaMode.GROUP, 4));
        assertEquals(200, rules.safeTicks(LavaMode.CHALLENGE, 1));
        assertEquals(160, rules.safeTicks(LavaMode.CHALLENGE, 2), "a fifth shorter every round");
        assertEquals(128, rules.safeTicks(LavaMode.CHALLENGE, 3));
        assertEquals(LavaRound.MIN_CLIMB_TICKS, rules.safeTicks(LavaMode.CHALLENGE, 30), "never under four seconds");

        LavaRound r = new LavaRound(LavaMode.CHALLENGE, rules(0));
        r.join(A);
        r.tick();
        assertEquals(0, r.level());
        run(r, 10 + 4);
        assertEquals(LavaRound.Phase.LAVA, r.phase());
        assertEquals(1, r.level());
        List<LavaEvent> next = run(r, 6);
        assertEquals(LavaRound.Phase.SAFE, r.phase(), "no drain: straight to the next climb");
        assertEquals(2, r.round());
        assertEquals(1, r.level(), "the lava stays where it reached");
        assertEquals(new LavaEvent.PhaseChanged(LavaRound.Phase.SAFE, 2, 1, 10), next.get(0),
                "a climb already under four seconds isn't cut shorter");
        assertEquals(0, of(next, LavaEvent.StarsOut.class).size(), "Challenge has no stars");
        assertEquals(1, r.survived(A));
    }

    @Test
    void theLavaLevelsPerMode() {
        LavaRound.Rules rules = rules(0);
        for (int round = 1; round <= 9; round++) {
            assertEquals(1, rules.level(LavaMode.LITTLE, round), "Little: one block, always");
            assertEquals(Math.min(round, 4), rules.level(LavaMode.GROUP, round), "Group: a block higher each round,"
                    + " never over the highest level with dry ground");
            assertEquals(Math.min(round, 5), rules.level(LavaMode.CHALLENGE, round), "Challenge: up to the flood");
        }
    }

    // ---- Little -------------------------------------------------------------------------------------

    @Test
    void littleCannotBeLostAndEndsExactlyWhenEveryStarIsCollected() {
        LavaRound r = new LavaRound(LavaMode.LITTLE, rules(0));
        r.join(A);
        r.tick();
        assertEquals(3, r.goal());
        List<LavaEvent> one = r.collected(A, 0);
        assertEquals(new LavaEvent.StarTaken(A, 0, 1, 3), one.get(0));
        assertEquals(List.of(), r.collected(A, 0), "a star is collected once");
        assertEquals(List.of(), r.collected(A, 7), "and only a star that is out");

        // she stands still through the whole round: the lava comes, she is touched again and again, and nothing is lost
        run(r, 10 + 4);
        assertEquals(LavaRound.Phase.LAVA, r.phase());
        for (int i = 0; i < 5; i++) {
            List<LavaEvent> touch = r.touched(List.of(A));
            assertEquals(List.of(new LavaEvent.PutBack(A, false, 1)), touch, "a touch puts her back and costs nothing");
        }
        assertEquals(List.of(), r.collected(A, 1), "no star is collected while the floor is lava");
        assertEquals(1, r.stars(), "what she collected stays collected");
        run(r, 6 + 2);
        assertEquals(LavaRound.Phase.SAFE, r.phase());
        assertEquals(List.of(1, 2), r.starsOut(), "the two she hasn't got are out again");

        // however many rounds it takes: no clock ends it
        run(r, 5 * (10 + 4 + 6 + 2));
        assertFalse(r.over(), "Little never ends by time");
        assertEquals(1, r.stars());
        while (r.phase() != LavaRound.Phase.SAFE) {
            r.tick();
        }
        r.collected(A, 1);
        assertFalse(r.over(), "one star left: not over");
        LavaResult result = ended(r.collected(A, 2));
        assertTrue(r.over(), "the last star ends it, at once");
        assertEquals(LavaMode.LITTLE, result.mode());
        assertFalse(result.calledOff());
        assertEquals(3, result.stars(), "a finished round is the full count");
        assertEquals(List.of(A), result.players());
        assertEquals(List.of(), r.tick(), "an ended round does nothing more");
        assertEquals(LavaRound.Join.OVER, r.join(B), "and takes nobody: a new round is needed");
    }

    @Test
    void aSecondChildJoinsLittleAtAnyTimeAndTheyShareTheStars() {
        LavaRound r = new LavaRound(LavaMode.LITTLE, rules(0));
        r.join(A);
        r.tick();
        r.collected(A, 0);
        run(r, 12);
        assertEquals(LavaRound.Phase.WARNING, r.phase());
        assertEquals(LavaRound.Join.JOINED, r.join(B), "a second child joins the round going");
        assertEquals(LavaRound.Join.ALREADY_IN, r.join(B));
        assertEquals(1, r.collected(B, 1).size(), "a star can be collected during the warning too");
        LavaResult result = ended(r.collected(B, 2));
        assertEquals(List.of(A, B), result.players(), "both finished it");
        assertEquals(3, result.stars());
    }

    @Test
    void theRoomTakesOnlySoMany() {
        LavaRound r = new LavaRound(LavaMode.LITTLE, new LavaRound.Rules(3, 10, 4, 6, 2, 3, 4, 5, 2, 0));
        assertEquals(LavaRound.Join.JOINED, r.join(A));
        assertEquals(LavaRound.Join.JOINED, r.join(B));
        assertEquals(LavaRound.Join.FULL, r.join(C));
        r.leave(B);
        assertEquals(LavaRound.Join.JOINED, r.join(C), "a place is free again");
    }

    // ---- Group --------------------------------------------------------------------------------------

    @Test
    void groupSharesOneCountATouchCostsOneStarAndNeverGoesBelowZero() {
        LavaRound r = new LavaRound(LavaMode.GROUP, rules(0));
        r.join(A);
        r.join(B);
        r.tick();
        assertEquals(List.of(new LavaEvent.PutBack(A, false, 0)), r.touched(List.of(A)),
                "nothing collected yet: a touch costs nothing, and the count stays at zero");
        assertEquals(0, r.stars());
        r.collected(A, 0);
        r.collected(B, 1);
        assertEquals(2, r.stars(), "one count for everyone");
        assertEquals(0, r.goal(), "no goal: the group collects what it can");
        run(r, 10 + 4);
        assertEquals(1, r.level(), "round 1: one deep");
        assertEquals(List.of(new LavaEvent.PutBack(B, true, 1)), r.touched(List.of(B)), "a touch costs the group a star");
        assertEquals(List.of(new LavaEvent.PutBack(A, true, 0), new LavaEvent.PutBack(B, false, 0)),
                r.touched(List.of(A, B)), "the last star goes, and after it nothing: never below zero");
        assertEquals(0, r.stars());
        assertTrue(r.playing(A) && r.playing(B), "nobody is ever out of a Group round");
    }

    @Test
    void groupPlaysItsRoundsWithNewStarsAndTheLavaABlockHigherEachTime() {
        LavaRound r = new LavaRound(LavaMode.GROUP, rules(0));
        r.join(A);
        r.tick();
        int total = 0;
        for (int round = 1; round <= 3; round++) {
            assertEquals(round, r.round());
            assertEquals(LavaRound.Phase.SAFE, r.phase());
            assertEquals(List.of(0, 1, 2), r.starsOut(), "a new set of stars every round");
            r.collected(A, 0);
            r.collected(A, 2);
            total += 2;
            run(r, 10 + 4);
            assertEquals(round, r.level(), "round " + round + ": the lava is " + round + " deep");
            assertEquals(List.of(), r.starsOut(), "the star nobody reached is gone");
            List<LavaEvent> end = run(r, 6 + 2);
            if (round < 3) {
                assertFalse(r.over());
            } else {
                LavaResult result = ended(end);
                assertEquals(LavaMode.GROUP, result.mode());
                assertEquals(total, result.stars(), "the group's total");
                assertEquals(3, result.rounds());
                assertEquals(List.of(A), result.players());
            }
        }
        assertTrue(r.over(), "after its last round's drain");
    }

    @Test
    void aPlayerMayJoinAGroupRoundGoingAndLeaveItWithoutEndingIt() {
        LavaRound r = new LavaRound(LavaMode.GROUP, rules(0));
        r.join(A);
        r.tick();
        r.collected(A, 0);
        assertEquals(LavaRound.Join.JOINED, r.join(B), "Group takes players at any time");
        r.collected(B, 1);
        assertEquals(List.of(), r.leave(A), "one leaves: the round goes on");
        assertFalse(r.over());
        assertEquals(2, r.stars(), "and the count stays");
        assertEquals(List.of(B), r.members());
    }

    // ---- Challenge ----------------------------------------------------------------------------------

    @Test
    void challengeTheLavaRisesATouchPutsYouOutAndTheLastOneStandingWins() {
        LavaRound r = new LavaRound(LavaMode.CHALLENGE, rules(3));
        r.join(A);
        r.join(B);
        r.join(C);
        r.startNow();
        assertEquals(List.of(A, B, C), r.alive());
        assertEquals(LavaRound.Join.TOO_LATE, r.join(UUID.randomUUID()), "nobody joins a Challenge that has started");
        run(r, 10 + 4);
        assertEquals(1, r.level());
        List<LavaEvent> out = r.touched(List.of(C));
        assertEquals(List.of(new LavaEvent.Out(C, 3, 0, false)), out, "third of three, and no round lasted");
        assertFalse(r.playing(C));
        assertTrue(r.isMember(C), "out, but still in the room: a watcher");
        assertEquals(List.of(new LavaEvent.PutBack(C, false, 0)), r.touched(List.of(C)),
                "a watcher who gets into the lava is only put back");
        run(r, 6);
        assertEquals(2, r.round());
        assertEquals(1, r.survived(A));
        assertEquals(0, r.survived(C), "a round only counts for whoever was still standing at its end");
        run(r, 10 + 4);
        assertEquals(2, r.level(), "a block higher");
        List<LavaEvent> last = r.touched(List.of(B));
        assertEquals(new LavaEvent.Out(B, 2, 1, false), last.get(0));
        LavaResult result = ended(last);
        assertTrue(r.over(), "one left standing: it ends at once");
        assertEquals(List.of(A), result.winners());
        assertFalse(result.solo());
        assertEquals(new LavaResult.Standing(A, 1, 1), result.standings().get(0));
        assertEquals(new LavaResult.Standing(B, 2, 1), result.standings().get(1));
        assertEquals(new LavaResult.Standing(C, 3, 0), result.standings().get(2));
        assertEquals(new LavaResult.Standing(C, 3, 0), result.standing(C));
        assertNull(result.standing(UUID.randomUUID()));
    }

    @Test
    void twoWhoGoUnderAtTheSameMomentShareThePlace() {
        LavaRound r = new LavaRound(LavaMode.CHALLENGE, rules(3));
        r.join(A);
        r.join(B);
        r.startNow();
        run(r, 10 + 4);
        LavaResult result = ended(r.touched(List.of(A, B)));
        assertEquals(List.of(A, B), result.winners(), "nobody outlasted the other: both are the last ones standing");
        assertEquals(1, result.standing(A).place());
        assertEquals(1, result.standing(B).place());
    }

    @Test
    void aloneInChallengeItIsHowManyRoundsYouLast() {
        LavaRound r = new LavaRound(LavaMode.CHALLENGE, rules(0));
        r.join(A);
        r.tick();
        int rounds = 0;
        while (r.round() < 4) { // three whole rounds
            int was = r.round();
            while (r.round() == was) {
                r.tick();
            }
            rounds++;
        }
        assertEquals(3, rounds);
        assertFalse(r.over(), "alone, it goes on while you stand");
        assertEquals(3, r.survived(A));
        while (r.phase() != LavaRound.Phase.LAVA) {
            r.tick();
        }
        LavaResult result = ended(r.touched(List.of(A)));
        assertTrue(result.solo());
        assertEquals(3, result.standing(A).survived(), "the rounds lasted, not the one that got you");
        assertEquals(List.of(A), result.winners());
    }

    @Test
    void theFloodEndsAChallengeAtTheLatest() {
        LavaRound r = new LavaRound(LavaMode.CHALLENGE, rules(0));
        r.join(A);
        r.tick();
        List<LavaEvent> all = new ArrayList<>();
        for (int i = 0; i < 10_000 && !r.over(); i++) {
            all.addAll(r.tick());
        }
        LavaResult result = ended(all);
        assertEquals(5, result.rounds(), "the lava came up to the flood level, round by round");
        assertEquals(5, result.standing(A).survived());
        int highest = 0;
        for (LavaEvent.PhaseChanged p : of(all, LavaEvent.PhaseChanged.class)) {
            highest = Math.max(highest, p.level());
        }
        assertEquals(5, highest, "never over the flood");
    }

    // ---- leaving ------------------------------------------------------------------------------------

    @Test
    void theLastPlayerLeavingEndsTheRoundCalledOffInEveryModeAndPhase() {
        for (LavaMode mode : LavaMode.values()) {
            for (int ticks : new int[]{0, 1, 5, 12, 16, 21}) {
                LavaRound r = new LavaRound(mode, rules(3));
                r.join(A);
                r.join(B);
                run(r, ticks);
                String at = mode + " after " + ticks + " ticks (" + r.phase() + ")";
                List<LavaEvent> first = r.leave(A);
                if (mode == LavaMode.CHALLENGE && r.over()) {
                    assertEquals(List.of(B), ended(first).winners(), "the one left standing wins: " + at);
                    assertFalse(r.result().calledOff());
                    continue;
                }
                assertFalse(r.over(), "one player is still in: " + at);
                LavaResult result = ended(r.leave(B));
                assertTrue(result.calledOff(), "nobody left: called off, " + at);
                assertEquals(List.of(), result.players());
                assertTrue(r.over());
                assertEquals(0, r.level(), "no lava is wanted any more: " + at);
                assertEquals(List.of(), r.starsOut());
                assertEquals(List.of(), r.leave(B), "leaving twice is nothing");
            }
        }
    }

    @Test
    void leavingAChallengeIsGoingOut() {
        LavaRound r = new LavaRound(LavaMode.CHALLENGE, rules(3));
        r.join(A);
        r.join(B);
        r.join(C);
        r.startNow();
        List<LavaEvent> left = r.leave(B);
        assertEquals(List.of(new LavaEvent.Out(B, 3, 0, true)), left, "out, and nobody to move to the gallery");
        assertFalse(r.over());
        LavaResult result = ended(r.leave(C));
        assertEquals(List.of(A), result.winners(), "the others left: the one who stayed is the last one standing");
        assertEquals(List.of(A), result.players());
    }

    @Test
    void onePlayerAloneInEachMode() {
        // Little alone: every star, then it ends
        LavaRound little = new LavaRound(LavaMode.LITTLE, rules(0));
        little.join(A);
        little.tick();
        little.collected(A, 0);
        little.collected(A, 1);
        assertEquals(3, ended(little.collected(A, 2)).stars());
        // Group alone: its rounds, then the total
        LavaRound group = new LavaRound(LavaMode.GROUP, rules(500));
        group.join(A);
        group.startNow();
        group.collected(A, 1);
        List<LavaEvent> all = new ArrayList<>();
        for (int i = 0; i < 1_000 && !group.over(); i++) {
            all.addAll(group.tick());
        }
        assertEquals(1, ended(all).stars());
        // Challenge alone: see aloneInChallengeItIsHowManyRoundsYouLast
        assertEquals("lava:little", LavaMode.LITTLE.board());
        assertEquals("lava:group", LavaMode.GROUP.board());
        assertEquals("lava:challenge", LavaMode.CHALLENGE.board());
        assertEquals(LavaMode.GROUP, LavaMode.of(" Group "));
        assertNull(LavaMode.of("family"), "the owner renamed it: it is Group");
        assertEquals(List.of("Little", "Group", "Challenge"),
                java.util.Arrays.stream(LavaMode.values()).map(LavaMode::label).toList(), "the owner's three names");
    }
}
