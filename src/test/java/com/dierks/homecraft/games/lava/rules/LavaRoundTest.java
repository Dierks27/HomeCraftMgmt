package com.dierks.homecraft.games.lava.rules;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Floor Is Lava's rules as a pure state machine: the phase clock (safe, warning, lava, drain) for each mode, and
 * what each mode means. Little can't be lost and ends exactly when every star is collected; Group's shared count, its
 * star goals and hearts; Challenge's rising lava, who is out (a touch, or a round without stars) and who wins. And
 * 0.57.0's changes: phases drawn from ranges by the round's own seed, stars during the lava a few at a time, the
 * furniture's warning and move, and a surprise most rounds.
 */
class LavaRoundTest {

    private static final UUID A = UUID.nameUUIDFromBytes("a".getBytes());
    private static final UUID B = UUID.nameUUIDFromBytes("b".getBytes());
    private static final UUID C = UUID.nameUUIDFromBytes("c".getBytes());

    /**
     * 3 floor stars and 4 lava stars; safe 10, warning 4, lava 6, drain 2 ticks (fixed); 3 rounds; dry ground up to
     * level 4, flooded at 5; 4 players; no furniture moves; 3 hearts; no surprises.
     */
    private static LavaRound.Rules rules(int gather) {
        return rules(gather, 0, false);
    }

    private static LavaRound.Rules rules(int gather, int shift, boolean surprises) {
        return new LavaRound.Rules(3, 4, LavaRound.Span.of(10), LavaRound.Span.of(4), LavaRound.Span.of(6), 2, 3, 4,
                5, 4, gather, shift, 3, surprises);
    }

    private static LavaRound round(LavaMode mode, LavaRound.Rules rules) {
        return new LavaRound(mode, rules, 42L);
    }

    private static List<LavaEvent> run(LavaRound r, int ticks) {
        List<LavaEvent> out = new ArrayList<>();
        for (int i = 0; i < ticks; i++) {
            out.addAll(r.tick());
        }
        return out;
    }

    /** Tick until {@code phase} (within a few thousand ticks), returning everything that happened. */
    private static List<LavaEvent> until(LavaRound r, LavaRound.Phase phase) {
        List<LavaEvent> out = new ArrayList<>();
        for (int i = 0; i < 5000 && r.phase() != phase; i++) {
            out.addAll(r.tick());
        }
        assertEquals(phase, r.phase(), "reached " + phase);
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

    /** Collect every star out now for {@code p}. */
    private static List<LavaEvent> collectAll(LavaRound r, UUID p) {
        List<LavaEvent> out = new ArrayList<>();
        for (int guard = 0; guard < 50 && !r.starsOut().isEmpty() && !r.over(); guard++) {
            out.addAll(r.collected(p, r.starsOut().get(0)));
        }
        return out;
    }

    // ---- the clock ----------------------------------------------------------------------------------

    @Test
    void thePhasesGoSafeWarningLavaDrainAndRoundAgain() {
        LavaRound r = round(LavaMode.LITTLE, rules(0));
        assertEquals(LavaRound.Phase.GATHER, r.phase());
        assertEquals(List.of(), r.tick(), "nobody here: nothing begins");
        assertEquals(LavaRound.Join.JOINED, r.join(A));
        List<LavaEvent> first = r.tick();
        assertEquals(List.of(A), of(first, LavaEvent.Started.class).get(0).players(), "Little starts at once");
        assertEquals(LavaRound.Phase.SAFE, r.phase());
        assertEquals(1, r.round());
        assertEquals(0, r.level(), "a dry floor");
        LavaEvent.StarsOut out = of(first, LavaEvent.StarsOut.class).get(0);
        assertEquals(List.of(0, 1, 2), out.ids(), "every star is out");
        assertFalse(out.lava(), "on the floor");

        assertEquals(List.of(), run(r, 9), "nine quiet ticks of safe");
        List<LavaEvent> warn = r.tick();
        assertEquals(LavaRound.Phase.WARNING, r.phase(), "then the warning");
        assertEquals(new LavaEvent.PhaseChanged(LavaRound.Phase.WARNING, 1, 0, 4), warn.get(0));
        assertEquals(1, r.nextLevel(), "the lava that is coming is one deep");

        List<LavaEvent> lava = run(r, 4);
        assertEquals(LavaRound.Phase.LAVA, r.phase());
        assertEquals(1, r.level(), "Little's lava is always one block deep");
        assertEquals(1, of(lava, LavaEvent.StarsIn.class).size(), "the floor's stars are put away when the lava comes");
        LavaEvent.StarsOut up = of(lava, LavaEvent.StarsOut.class).get(0);
        assertTrue(up.lava(), "0.57.0: and two wait on the furniture during the lava");
        assertEquals(List.of(0, 1), up.ids(), "two of the ones still to collect");
        assertTrue(r.lavaStarsOut());

        List<LavaEvent> drain = run(r, 6);
        assertEquals(LavaRound.Phase.DRAIN, r.phase());
        assertEquals(0, r.level(), "it drains to nothing");
        assertEquals(1, of(drain, LavaEvent.StarsIn.class).size(), "the lava's stars go with it");
        assertEquals(1, r.lavaCame());

        List<LavaEvent> again = run(r, 2);
        assertEquals(LavaRound.Phase.SAFE, r.phase(), "and the floor is safe again");
        assertEquals(2, r.round());
        assertEquals(List.of(0, 1, 2), of(again, LavaEvent.StarsOut.class).get(0).ids(), "the same stars, out again");
        assertEquals(3, of(again, LavaEvent.StarsOut.class).get(0).set(), "a new set: they are put somewhere new");
    }

    @Test
    void groupAndChallengeWaitForPlayersAndAnyoneMayStartSooner() {
        LavaRound r = round(LavaMode.GROUP, rules(5));
        assertEquals(List.of(), run(r, 20), "the wait begins with the first player");
        r.join(A);
        assertEquals(List.of(), run(r, 4));
        assertEquals(LavaRound.Phase.GATHER, r.phase());
        assertEquals(LavaRound.Join.JOINED, r.join(B), "others join during the wait");
        List<LavaEvent> go = r.tick();
        assertEquals(List.of(A, B), of(go, LavaEvent.Started.class).get(0).players());
        assertEquals(LavaRound.Phase.SAFE, r.phase());

        LavaRound alone = round(LavaMode.CHALLENGE, rules(500));
        alone.join(A);
        assertEquals(List.of(), alone.tick());
        List<LavaEvent> now = alone.startNow();
        assertEquals(LavaRound.Phase.SAFE, alone.phase(), "Start now: it can be started alone");
        assertEquals(1, of(now, LavaEvent.Started.class).size());
        assertEquals(List.of(), alone.startNow(), "once");
        assertEquals(List.of(), round(LavaMode.GROUP, rules(5)).startNow(), "never with nobody in it");
    }

    /**
     * The owner: "Maybe a bit more random on the lava". Every phase is drawn from its range by the round's own random
     * numbers: never outside it, not the same every round, and the same seed gives the same round.
     */
    @Test
    void thePhasesAreDrawnFromTheirRangesSoTheLavaCantBeCounted() {
        LavaRound.Rules rules = new LavaRound.Rules(3, 4, new LavaRound.Span(100, 200), new LavaRound.Span(30, 60),
                new LavaRound.Span(80, 120), 2, 5, 4, 5, 4, 0, 0, 3, false);
        Set<Integer> safes = new HashSet<>();
        Set<Integer> warns = new HashSet<>();
        Set<Integer> lavas = new HashSet<>();
        List<Integer> first = null;
        for (long seed = 1; seed <= 12; seed++) {
            for (int pass = 0; pass < 2; pass++) {
                LavaRound r = new LavaRound(LavaMode.GROUP, rules, seed);
                r.join(A);
                r.startNow();
                List<Integer> lengths = new ArrayList<>();
                List<LavaEvent> ev = new ArrayList<>();
                for (int i = 0; i < 4000 && !r.over(); i++) {
                    ev.addAll(r.tick());
                }
                for (LavaEvent.PhaseChanged p : of(ev, LavaEvent.PhaseChanged.class)) {
                    lengths.add(p.ticks());
                    switch (p.phase()) {
                        case SAFE -> {
                            assertTrue(p.ticks() >= 100 && p.ticks() <= 200, "safe in range: " + p);
                            safes.add(p.ticks());
                        }
                        case WARNING -> {
                            assertTrue(p.ticks() >= 30 && p.ticks() <= 60, "warning in range: " + p);
                            warns.add(p.ticks());
                        }
                        case LAVA -> {
                            assertTrue(p.ticks() >= 80 && p.ticks() <= 120, "lava in range: " + p);
                            lavas.add(p.ticks());
                        }
                        default -> {
                        }
                    }
                }
                if (seed == 1 && pass == 0) {
                    first = lengths;
                } else if (seed == 1) {
                    assertEquals(first, lengths, "the same seed, the same round");
                }
            }
        }
        assertTrue(safes.size() > 10 && warns.size() > 8 && lavas.size() > 8, "lengths vary: " + safes + " " + warns
                + " " + lavas);
    }

    @Test
    void challengesFloorTimeShrinksAndItsLavaNeverDrains() {
        LavaRound.Rules rules = new LavaRound.Rules(3, 0, LavaRound.Span.of(200), LavaRound.Span.of(4),
                LavaRound.Span.of(6), 2, 3, 4, 5, 4, 0, 0, 3, false);
        SplittableRandom rng = new SplittableRandom(1);
        assertEquals(200, rules.safeTicks(LavaMode.LITTLE, 4, rng), "Little and Group keep the time drawn");
        assertEquals(200, rules.safeTicks(LavaMode.GROUP, 4, rng));
        assertEquals(200, rules.safeTicks(LavaMode.CHALLENGE, 1, rng));
        assertEquals(180, rules.safeTicks(LavaMode.CHALLENGE, 2, rng), "a tenth shorter every round");
        assertEquals(162, rules.safeTicks(LavaMode.CHALLENGE, 3, rng));
        assertEquals(LavaRound.MIN_CLIMB_TICKS, rules.safeTicks(LavaMode.CHALLENGE, 60, rng), "never under four seconds");

        LavaRound r = round(LavaMode.CHALLENGE, rules(0));
        r.join(A);
        r.tick();
        assertEquals(0, r.level());
        collectAll(r, A);
        until(r, LavaRound.Phase.LAVA);
        assertEquals(1, r.level());
        collectAll(r, A);
        List<LavaEvent> next = until(r, LavaRound.Phase.SAFE);
        assertEquals(1, r.level(), "the lava stays where it is");
        assertEquals(List.of(), of(next, LavaEvent.PhaseChanged.class).stream()
                .filter(p -> p.phase() == LavaRound.Phase.DRAIN).toList(), "Challenge never drains");
        assertEquals(2, r.round());
    }

    @Test
    void theLavaLevelsPerMode() {
        LavaRound.Rules rules = rules(0);
        assertEquals(1, rules.level(LavaMode.LITTLE, 7, false), "Little: always one");
        assertEquals(1, rules.level(LavaMode.LITTLE, 7, true), "even in a quick rise");
        assertEquals(2, rules.level(LavaMode.GROUP, 2, false), "Group: a block a round");
        assertEquals(3, rules.level(LavaMode.GROUP, 2, true), "and one more in a quick rise");
        assertEquals(4, rules.level(LavaMode.GROUP, 9, true), "never over the safe top");
        assertEquals(3, rules.level(LavaMode.CHALLENGE, 3, true), "Challenge: a block a round, quick or not");
        assertEquals(5, rules.level(LavaMode.CHALLENGE, 9, false), "up to the flood");
    }

    // ---- Little ------------------------------------------------------------------------------------

    @Test
    void littleCannotBeLostAndEndsExactlyWhenEveryStarIsCollected() {
        LavaRound r = round(LavaMode.LITTLE, rules(0));
        r.join(A);
        r.tick();
        assertEquals(3, r.goal());
        // touches never end it, never cost anything
        until(r, LavaRound.Phase.LAVA);
        for (int i = 0; i < 5; i++) {
            List<LavaEvent> t = r.touched(List.of(A));
            assertEquals(List.of(new LavaEvent.PutBack(A, false, 0)), t, "a touch: back, nothing lost");
        }
        // a star on the furniture during the lava counts
        int up = r.starsOut().get(0);
        List<LavaEvent> got = r.collected(A, up);
        assertEquals(1, of(got, LavaEvent.StarTaken.class).size());
        assertEquals(1, r.stars());
        assertEquals(1, r.starsOut().size(), "one more waits up there");
        List<LavaEvent> more = r.collected(A, r.starsOut().get(0));
        assertEquals(1, of(more, LavaEvent.StarsOut.class).size(), "the next comes out when those are collected");
        assertEquals(1, r.starsOut().size(), "the last one");
        // many rounds pass; it waits
        for (int round = 0; round < 4; round++) {
            until(r, LavaRound.Phase.SAFE);
            until(r, LavaRound.Phase.DRAIN);
        }
        assertFalse(r.over(), "there is no clock to beat");
        until(r, LavaRound.Phase.SAFE);
        assertEquals(1, r.starsOut().size(), "only the one still to collect comes out");
        List<LavaEvent> last = r.collected(A, r.starsOut().get(0));
        LavaResult res = ended(last);
        assertEquals(3, res.stars(), "every star");
        assertFalse(res.calledOff());
        assertTrue(r.over());
    }

    @Test
    void littlesSurprisesAreOnlyTheFriendlyOnesAndOnlyNowAndThen() {
        Set<LavaRound.Surprise> seen = new HashSet<>();
        int rounds = 0;
        int none = 0;
        for (long seed = 1; seed <= 30; seed++) {
            LavaRound r = new LavaRound(LavaMode.LITTLE, new LavaRound.Rules(40, 0, LavaRound.Span.of(3),
                    LavaRound.Span.of(2), LavaRound.Span.of(2), 1, 3, 4, 5, 4, 0, 0, 3, true), seed);
            r.join(A);
            List<LavaEvent> ev = run(r, 200);
            List<LavaEvent.SurpriseComing> s = of(ev, LavaEvent.SurpriseComing.class);
            for (LavaEvent.SurpriseComing c : s) {
                assertTrue(c.surprise().gentle(), "Little's surprises are friendly: " + c);
                assertTrue(c.round() >= 2, "never in the first round");
                seen.add(c.surprise());
            }
            rounds += r.round() - 1;
            none += r.round() - 1 - s.size();
        }
        assertEquals(Set.of(LavaRound.Surprise.STAR_SHOWER, LavaRound.Surprise.BOUNCY), seen);
        assertTrue(none > rounds / 4, "now and then, not every round: " + none + " of " + rounds);
    }

    @Test
    void aSecondChildJoinsLittleAtAnyTimeAndTheyShareTheStars() {
        LavaRound r = round(LavaMode.LITTLE, rules(0));
        r.join(A);
        r.tick();
        r.collected(A, 0);
        until(r, LavaRound.Phase.LAVA);
        assertEquals(LavaRound.Join.JOINED, r.join(B), "during the lava too");
        r.collected(B, r.starsOut().get(0));
        assertEquals(2, r.stars(), "one count, together");
        assertEquals(LavaRound.Join.ALREADY_IN, r.join(B));
    }

    // ---- Group -------------------------------------------------------------------------------------

    @Test
    void groupHasAStarGoalEachRoundAndAMissedGoalCostsAHeart() {
        LavaRound.Rules rules = rules(0);
        assertEquals(5, rules.goal(1, 1), "one player, round 1: 5 of the 7");
        assertEquals(5, rules.goal(4, 1), "never more than all but two");
        assertEquals(5, rules.goal(1, 3), "more players: more, never all");
        LavaRound.Rules big = new LavaRound.Rules(10, 4, LavaRound.Span.of(10), LavaRound.Span.of(4),
                LavaRound.Span.of(6), 2, 5, 4, 5, 4, 0, 0, 3, false);
        assertEquals(List.of(5, 6, 7, 8, 9), List.of(big.goal(1, 1), big.goal(2, 1), big.goal(3, 1), big.goal(4, 1),
                big.goal(5, 1)), "one more each round");
        assertEquals(7, big.goal(1, 2), "two more for a second player");
        assertEquals(12, big.goal(5, 6), "a full room: all but two");

        LavaRound r = round(LavaMode.GROUP, rules);
        r.join(A);
        r.startNow();
        assertEquals(3, r.hearts());
        assertEquals(5, r.roundGoal());
        collectAll(r, A); // the floor's three
        assertEquals(3, r.roundStars());
        until(r, LavaRound.Phase.LAVA);
        assertEquals(2, r.starsOut().size(), "the lava's stars, two at a time");
        collectAll(r, A); // the lava's four, wave after wave
        assertEquals(7, r.roundStars());
        r.touched(List.of(A));
        assertEquals(6, r.roundStars(), "a touch costs a star of the round's too");
        assertEquals(6, r.stars(), "and of the shared count");
        List<LavaEvent> made = until(r, LavaRound.Phase.DRAIN);
        assertEquals(List.of(new LavaEvent.Goal(1, 6, 5, true, 3)), of(made, LavaEvent.Goal.class), "goal reached");

        until(r, LavaRound.Phase.SAFE);
        assertEquals(0, r.roundStars(), "a new round counts from nothing");
        List<LavaEvent> missed = until(r, LavaRound.Phase.DRAIN);
        assertEquals(List.of(new LavaEvent.Goal(2, 0, 5, false, 2)), of(missed, LavaEvent.Goal.class),
                "nothing collected: a heart goes");
        until(r, LavaRound.Phase.SAFE);
        List<LavaEvent> rest = new ArrayList<>();
        for (int i = 0; i < 400 && !r.over(); i++) {
            rest.addAll(r.tick());
        }
        LavaResult res = ended(rest);
        assertEquals(3, res.rounds(), "its three rounds");
        assertEquals(1, res.goals(), "one goal reached");
        assertEquals(1, res.hearts(), "a heart left");
        assertEquals(6, res.stars());
    }

    @Test
    void groupWithNoHeartsLeftIsOver() {
        LavaRound.Rules rules = new LavaRound.Rules(3, 4, LavaRound.Span.of(10), LavaRound.Span.of(4),
                LavaRound.Span.of(6), 2, 9, 4, 5, 4, 0, 0, 2, false);
        LavaRound r = round(LavaMode.GROUP, rules);
        r.join(A);
        r.startNow();
        r.collected(A, r.starsOut().get(0));
        List<LavaEvent> all = new ArrayList<>();
        for (int i = 0; i < 2000 && !r.over(); i++) {
            all.addAll(r.tick());
        }
        LavaResult res = ended(all);
        assertEquals(2, res.rounds(), "two missed goals, two hearts: over after the second of nine rounds");
        assertEquals(0, res.hearts());
        assertEquals(1, res.stars(), "what was collected still counts");
        assertFalse(res.calledOff());
    }

    @Test
    void groupATouchNeverTakesTheCountBelowZero() {
        LavaRound r = round(LavaMode.GROUP, rules(0));
        r.join(A);
        r.startNow();
        List<LavaEvent> t = r.touched(List.of(A));
        assertEquals(List.of(new LavaEvent.PutBack(A, false, 0)), t);
        assertEquals(0, r.stars());
        r.collected(A, r.starsOut().get(0));
        assertEquals(List.of(new LavaEvent.PutBack(A, true, 0)), r.touched(List.of(A)));
    }

    @Test
    void groupsLavaStarsComeInWavesAndAShowerDoublesThem() {
        LavaRound r = round(LavaMode.GROUP, rules(0));
        r.join(A);
        r.startNow();
        List<LavaEvent> lava = until(r, LavaRound.Phase.LAVA);
        List<LavaEvent.StarsOut> outs = of(lava, LavaEvent.StarsOut.class);
        assertTrue(outs.get(outs.size() - 1).lava());
        assertEquals(2, r.starsOut().size(), "a wave of two");
        List<LavaEvent> got = collectAll(r, A);
        assertEquals(1, of(got, LavaEvent.StarsOut.class).size(), "one more wave: four in all");
        assertEquals(List.of(), r.starsOut(), "and then no more this round");
        assertEquals(4, r.stars());

        // the star shower: twice as many, three at a time
        int showered = 0;
        for (long seed = 1; seed < 80 && showered == 0; seed++) {
            LavaRound s = new LavaRound(LavaMode.GROUP, rules(0, 0, true), seed);
            s.join(A);
            s.startNow();
            until(s, LavaRound.Phase.DRAIN);
            until(s, LavaRound.Phase.SAFE);
            if (s.surprise() == LavaRound.Surprise.STAR_SHOWER) {
                until(s, LavaRound.Phase.LAVA);
                assertEquals(3, s.starsOut().size());
                int before = s.stars();
                collectAll(s, A);
                showered = s.stars() - before;
            }
        }
        assertEquals(8, showered, "a shower: twice the lava's stars");
    }

    @Test
    void aPlayerMayJoinAGroupRoundGoingAndLeaveItWithoutEndingIt() {
        LavaRound r = round(LavaMode.GROUP, rules(0));
        r.join(A);
        r.startNow();
        until(r, LavaRound.Phase.LAVA);
        assertEquals(LavaRound.Join.JOINED, r.join(B));
        assertEquals(List.of(), r.leave(A));
        assertFalse(r.over());
        assertEquals(List.of(B), r.members());
    }

    // ---- Challenge ---------------------------------------------------------------------------------

    @Test
    void challengeTheLavaRisesATouchPutsYouOutAndTheLastOneStandingWins() {
        LavaRound r = round(LavaMode.CHALLENGE, rules(0));
        r.join(A);
        r.join(B);
        r.join(C);
        r.startNow();
        assertEquals(LavaRound.Join.TOO_LATE, r.join(UUID.randomUUID()), "nobody joins a Challenge going");
        assertEquals(5, r.starsOut().size(), "a star each and two more");
        r.collected(A, r.starsOut().get(0));
        r.collected(B, r.starsOut().get(0));
        r.collected(C, r.starsOut().get(0));
        until(r, LavaRound.Phase.LAVA);
        List<LavaEvent> t = r.touched(List.of(C));
        assertEquals(List.of(new LavaEvent.Out(C, 3, 0, false, false)), t, "out, third");
        assertEquals(List.of(A, B), r.alive());
        assertEquals(List.of(), r.collected(C, r.starsOut().get(0)), "who is out collects nothing");
        until(r, LavaRound.Phase.SAFE);
        assertEquals(1, r.survived(A));
        r.collected(A, r.starsOut().get(0));
        r.collected(B, r.starsOut().get(0));
        until(r, LavaRound.Phase.LAVA);
        List<LavaEvent> end = r.touched(List.of(B));
        LavaResult res = ended(end);
        assertEquals(List.of(A), res.winners(), "the last one standing");
        assertEquals(new LavaResult.Standing(B, 2, 1), res.standing(B));
        assertEquals(new LavaResult.Standing(C, 3, 0), res.standing(C));
        assertFalse(res.solo());
    }

    /** The owner: "it seems like all you have to do is get the stars and wait now". In Challenge you must keep collecting. */
    @Test
    void challengeARoundWithoutItsStarsPutsYouOut() {
        LavaRound r = round(LavaMode.CHALLENGE, rules(0));
        r.join(A);
        r.join(B);
        r.startNow();
        assertEquals(1, r.need());
        r.collected(A, r.starsOut().get(0));
        assertEquals(1, r.starsThisRound(A));
        List<LavaEvent> ev = until(r, LavaRound.Phase.LAVA);
        assertTrue(r.lavaStarsOut(), "stars on the furniture while the lava is up");
        assertEquals(2, r.starsOut().size(), "one for each standing");
        ev = new ArrayList<>();
        for (int i = 0; i < 100 && !r.over(); i++) {
            ev.addAll(r.tick());
        }
        LavaResult res = ended(ev);
        assertEquals(List.of(new LavaEvent.Out(B, 2, 0, false, true)), of(ev, LavaEvent.Out.class),
                "B ended the round without a star: out");
        assertEquals(List.of(A), res.winners());
        assertEquals(1, res.standing(A).survived());

        LavaRound.Rules rules = rules(0);
        assertEquals(List.of(1, 1, 1, 2, 2), List.of(rules.need(1), rules.need(2), rules.need(3), rules.need(4),
                rules.need(5)), "two stars a round from the fourth");
    }

    @Test
    void aloneInChallengeItIsHowManyRoundsYouLast() {
        LavaRound r = round(LavaMode.CHALLENGE, rules(0));
        r.join(A);
        r.startNow();
        for (int round = 1; round <= 3; round++) {
            collectAll(r, A);
            until(r, LavaRound.Phase.LAVA);
            collectAll(r, A);
            if (round < 3) {
                until(r, LavaRound.Phase.SAFE);
            }
        }
        LavaResult res = ended(r.touched(List.of(A)));
        assertTrue(res.solo());
        assertEquals(2, res.standing(A).survived(), "two rounds lasted");
    }

    @Test
    void theFloodEndsAChallengeAtTheLatest() {
        LavaRound r = round(LavaMode.CHALLENGE, rules(0));
        r.join(A);
        r.startNow();
        List<LavaEvent> all = new ArrayList<>();
        for (int i = 0; i < 5000 && !r.over(); i++) {
            all.addAll(collectAll(r, A));
            all.addAll(r.tick());
        }
        LavaResult res = ended(all);
        assertEquals(5, res.standing(A).survived(), "every round up to the flood");
        assertEquals(List.of(A), res.winners());
    }

    @Test
    void twoWhoGoUnderAtTheSameMomentShareThePlace() {
        LavaRound r = round(LavaMode.CHALLENGE, rules(0));
        r.join(A);
        r.join(B);
        r.join(C);
        r.startNow();
        until(r, LavaRound.Phase.LAVA);
        List<LavaEvent> t = r.touched(List.of(B, C));
        assertEquals(2, of(t, LavaEvent.Out.class).size());
        for (LavaEvent.Out o : of(t, LavaEvent.Out.class)) {
            assertEquals(2, o.place(), "they share second");
        }
        assertTrue(r.over(), "A is left standing");
    }

    // ---- the furniture moves, and the surprises -------------------------------------------------------------

    @Test
    void fromTheSecondRoundTheFurnitureWarnsThenMovesAndTheStarsComeOutAfter() {
        LavaRound r = round(LavaMode.GROUP, rules(0, 6, false));
        r.join(A);
        List<LavaEvent> first = r.startNow();
        assertEquals(List.of(), of(first, LavaEvent.ShiftComing.class), "round 1: the room as the game began");
        assertFalse(r.shifting());
        assertEquals(1, of(first, LavaEvent.StarsOut.class).size());
        until(r, LavaRound.Phase.DRAIN);
        List<LavaEvent> second = until(r, LavaRound.Phase.SAFE);
        LavaEvent.ShiftComing coming = of(second, LavaEvent.ShiftComing.class).get(0);
        assertEquals(2, coming.round());
        assertEquals(6, coming.ticks());
        assertTrue(r.shifting());
        assertEquals(List.of(), r.starsOut(), "no stars while it moves");
        assertEquals(16, ((LavaEvent.PhaseChanged) second.get(second.size() - 2)).ticks(),
                "the safe time and the move's warning together");
        List<LavaEvent> wait = run(r, 5);
        assertEquals(List.of(), of(wait, LavaEvent.Shift.class));
        List<LavaEvent> moved = r.tick();
        assertEquals(new LavaEvent.Shift(2, coming.seed()), moved.get(0), "then it moves, to the layout it warned of");
        assertEquals(1, of(moved, LavaEvent.StarsOut.class).size(), "and the stars come out");
        assertFalse(r.shifting());
        assertEquals(10, r.left(), "the whole safe time is left for them");

        LavaRound little = round(LavaMode.LITTLE, rules(0, 0, false));
        little.join(A);
        List<LavaEvent> l = run(little, 200);
        assertEquals(List.of(), of(l, LavaEvent.ShiftComing.class), "Little's room moves only between games");
    }

    @Test
    void groupAndChallengeHaveASurpriseMostRoundsNeverTheSameTwiceRunning() {
        for (LavaMode mode : new LavaMode[]{LavaMode.GROUP, LavaMode.CHALLENGE}) {
            Set<LavaRound.Surprise> seen = new HashSet<>();
            for (long seed = 1; seed <= 40; seed++) {
                LavaRound r = new LavaRound(mode, new LavaRound.Rules(3, 4, LavaRound.Span.of(6),
                        LavaRound.Span.of(2), LavaRound.Span.of(3), 1, 5, 4, 5, 4, 0, 0, 9, true), seed);
                r.join(A);
                r.startNow();
                LavaRound.Surprise last = null;
                List<LavaEvent> ev = new ArrayList<>();
                for (int i = 0; i < 400 && !r.over(); i++) {
                    ev.addAll(collectAll(r, A));
                    ev.addAll(r.tick());
                }
                List<LavaEvent.SurpriseComing> s = of(ev, LavaEvent.SurpriseComing.class);
                assertTrue(s.size() >= 3, mode + ": every round after the first: " + s);
                for (LavaEvent.SurpriseComing c : s) {
                    assertTrue(c.round() >= 2);
                    assertNotEquals(last, c.surprise(), "never the same twice running");
                    last = c.surprise();
                    seen.add(c.surprise());
                    if (mode == LavaMode.CHALLENGE) {
                        assertNotEquals(LavaRound.Surprise.BOUNCY, c.surprise(), "no cushion is up in a Challenge");
                    }
                }
            }
            assertEquals(mode == LavaMode.GROUP ? 5 : 4, seen.size(), mode + ": every kind turns up: " + seen);
        }
        LavaRound off = round(LavaMode.GROUP, rules(0, 0, false));
        off.join(A);
        off.startNow();
        assertEquals(List.of(), of(run(off, 300), LavaEvent.SurpriseComing.class), "surprises: false");
    }

    @Test
    void aQuickRiseHasAShortWarningAndComesABlockHigherInGroup() {
        LavaRound.Rules rules = new LavaRound.Rules(3, 4, LavaRound.Span.of(6), LavaRound.Span.of(40),
                LavaRound.Span.of(3), 1, 5, 4, 5, 4, 0, 0, 9, true);
        boolean seen = false;
        for (long seed = 1; seed <= 60 && !seen; seed++) {
            LavaRound r = new LavaRound(LavaMode.GROUP, rules, seed);
            r.join(A);
            r.startNow();
            for (int i = 0; i < 400 && !r.over(); i++) {
                List<LavaEvent> ev = r.tick();
                for (LavaEvent.PhaseChanged p : of(ev, LavaEvent.PhaseChanged.class)) {
                    if (r.surprise() == LavaRound.Surprise.FAST_RISE) {
                        if (p.phase() == LavaRound.Phase.WARNING) {
                            assertEquals(20, p.ticks(), "half the warning");
                        }
                        if (p.phase() == LavaRound.Phase.LAVA) {
                            assertEquals(Math.min(4, p.round() + 1), p.level(), "a block higher");
                            seen = true;
                        }
                    }
                }
            }
        }
        assertTrue(seen);
    }

    // ---- leaving and the room's size ------------------------------------------------------------------

    @Test
    void theLastPlayerLeavingEndsTheRoundCalledOffInEveryModeAndPhase() {
        for (LavaMode mode : LavaMode.values()) {
            for (LavaRound.Phase phase : new LavaRound.Phase[]{LavaRound.Phase.SAFE, LavaRound.Phase.WARNING,
                    LavaRound.Phase.LAVA}) {
                LavaRound r = round(mode, rules(0, 4, true));
                r.join(A);
                r.startNow();
                r.tick();
                until(r, phase);
                LavaResult res = ended(r.leave(A));
                assertTrue(res.calledOff(), mode + " " + phase);
                assertTrue(r.over());
            }
        }
    }

    @Test
    void leavingAChallengeIsGoingOut() {
        LavaRound r = round(LavaMode.CHALLENGE, rules(0));
        r.join(A);
        r.join(B);
        r.join(C);
        r.startNow();
        List<LavaEvent> ev = r.leave(C);
        assertEquals(List.of(new LavaEvent.Out(C, 3, 0, true, false)), ev);
        assertFalse(r.over());
        LavaResult res = ended(r.leave(B));
        assertEquals(List.of(A), res.winners());
    }

    @Test
    void theRoomTakesOnlySoMany() {
        LavaRound r = round(LavaMode.GROUP, rules(0));
        for (int i = 0; i < 4; i++) {
            assertEquals(LavaRound.Join.JOINED, r.join(UUID.randomUUID()));
        }
        assertEquals(LavaRound.Join.FULL, r.join(UUID.randomUUID()));
    }

    @Test
    void aStarCountsOnlyWhenItIsOutAndForSomeoneInTheRound() {
        LavaRound r = round(LavaMode.GROUP, rules(0));
        r.join(A);
        r.startNow();
        assertEquals(List.of(), r.collected(B, r.starsOut().get(0)), "not in the round");
        assertEquals(List.of(), r.collected(A, 999), "not out");
        int id = r.starsOut().get(0);
        assertEquals(1, r.collected(A, id).size());
        assertEquals(List.of(), r.collected(A, id), "once");
        until(r, LavaRound.Phase.DRAIN);
        assertEquals(List.of(), r.starsOut(), "none while it drains");
        assertNotNull(r.rules());
        assertEquals(42L, r.seed());
    }
}
