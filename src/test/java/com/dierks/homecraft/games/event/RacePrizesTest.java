package com.dierks.homecraft.games.event;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Race Night's prizes ({@link RacePrizes}, EVENTS-DROPPER-SPEC §A.3, EVENTS-RECONCILED decision 1),
 * with no server.
 *
 * <p>Pinned here: the reconciled amounts (5, 3 and 2 by the night's place, 1 for every other
 * finisher); the podium rule for 1 to 8 racers (2nd needs 3, 3rd needs 4); ties sharing a place and
 * its prize; the finisher prize only for someone who finished a race; the 4th night of a week is
 * "just for fun"; a fun night pays nothing; and nobody ever gets more than 5 tokens a night, whatever
 * config says.
 */
class RacePrizesTest {

    private static final List<Integer> PRIZES = List.of(5, 3, 2);

    /** n racers, 1st to nth, each with points and each a finisher. */
    private static List<NightStandings.Ranked> field(List<UUID> who) {
        List<NightStandings.Ranked> out = new ArrayList<>();
        for (int i = 0; i < who.size(); i++) {
            out.add(new NightStandings.Ranked(who.get(i), i + 1, 30 - i));
        }
        return out;
    }

    private static List<UUID> racers(int n) {
        List<UUID> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(UUID.randomUUID());
        }
        return out;
    }

    @Test
    void thePodiumRuleForOneToEightRacers() {
        int[][] want = {
                {0},                      // 1 racer: nobody to beat, but they finished: the finisher prize
                {5, 1},                   // 2: 5 and 1
                {5, 3, 1},                // 3: 5, 3 and 1
                {5, 3, 2, 1},             // 4: the whole podium
                {5, 3, 2, 1, 1},
                {5, 3, 2, 1, 1, 1},
                {5, 3, 2, 1, 1, 1, 1},
                {5, 3, 2, 1, 1, 1, 1, 1}};
        for (int n = 1; n <= 8; n++) {
            List<UUID> who = racers(n);
            Map<UUID, RacePrizes.Prize> p = RacePrizes.plan(field(who), n, who, PRIZES, 1, true);
            for (int i = 0; i < n; i++) {
                int expected = n == 1 ? 1 : want[n - 1][i];
                RacePrizes.Prize got = p.get(who.get(i));
                assertEquals(expected, got == null ? 0 : got.tokens(), n + " racers: place " + (i + 1) + " wins "
                        + expected + " (2nd needs 3 racers, 3rd needs 4)");
            }
        }
    }

    @Test
    void tiesShareThePlaceAndEachGetsItsPrize() {
        List<UUID> who = racers(5);
        List<NightStandings.Ranked> tied = List.of(
                new NightStandings.Ranked(who.get(0), 1, 26),
                new NightStandings.Ranked(who.get(1), 1, 26),
                new NightStandings.Ranked(who.get(2), 3, 18),
                new NightStandings.Ranked(who.get(3), 4, 10),
                new NightStandings.Ranked(who.get(4), 5, 4));
        Map<UUID, RacePrizes.Prize> p = RacePrizes.plan(tied, 5, who, PRIZES, 1, true);
        assertEquals(5, p.get(who.get(0)).tokens(), "joint 1st: 5");
        assertEquals(5, p.get(who.get(1)).tokens(), "joint 1st: 5 as well");
        assertEquals(2, p.get(who.get(2)).tokens(), "the next place is 3rd: 2");
        assertEquals(1, p.get(who.get(3)).tokens(), "4th finished a race: 1");
        assertEquals("Race Night: 1st place", p.get(who.get(1)).detail(), "the ledger line says the place");
    }

    @Test
    void theFinisherPrizeNeedsAFinishedRace() {
        List<UUID> who = racers(6);
        List<UUID> finishers = new ArrayList<>(who.subList(0, 4));
        Map<UUID, RacePrizes.Prize> p = RacePrizes.plan(field(who), 6, finishers, PRIZES, 1, true);
        assertEquals(1, p.get(who.get(3)).tokens(), "4th finished a race: 1");
        assertFalse(p.containsKey(who.get(4)), "5th never crossed the line (still racing points only): nothing");
        assertFalse(p.containsKey(who.get(5)), "nor 6th");
        List<NightStandings.Ranked> zero = List.of(new NightStandings.Ranked(who.get(0), 1, 0));
        assertTrue(RacePrizes.plan(zero, 4, who, PRIZES, 1, true).isEmpty(), "no points tonight, no prize");
    }

    @Test
    void theFourthNightOfAWeekIsJustForFun() {
        assertTrue(RacePrizes.slotFree(0, 3), "the 1st prize night of the week");
        assertTrue(RacePrizes.slotFree(2, 3), "the 3rd");
        assertFalse(RacePrizes.slotFree(3, 3), "the 4th is just for fun");
        assertFalse(RacePrizes.slotFree(0, 0), "0 prize nights a week: every night is for fun");
        assertEquals(RacePrizes.JUST_FOR_FUN, RacePrizes.line(PRIZES, 1, false), "and says so");
        assertEquals("Prizes: 5, 3, 2 tokens, 1 for every other finisher", RacePrizes.line(PRIZES, 1, true),
                "a prize night says its prizes");
    }

    @Test
    void aFunNightPaysNothing() {
        List<UUID> who = racers(4);
        assertTrue(RacePrizes.plan(field(who), 4, who, PRIZES, 1, false).isEmpty(),
                "no prize slot (fun, or the week's used up): no tokens at all");
        assertTrue(NightRules.of(RaceNightSettings.defaults(), 3, 0, true, 8).fun(), "an admin's fun night is kept");
        assertFalse(NightRules.of(RaceNightSettings.defaults(), 3, 0, true, 8).wantsPrizes(),
                "and never asks for a slot");
    }

    @Test
    void nobodyEverWinsMoreThanFiveTokensANight() {
        List<UUID> who = racers(4);
        Map<UUID, RacePrizes.Prize> p = RacePrizes.plan(field(who), 4, who, List.of(10, 10, 10), 2, true);
        for (UUID u : who) {
            assertTrue(p.get(u).tokens() <= NightRules.MAX_PRIZE_PER_NIGHT,
                    "config's 10 is held to 5 a player a night: " + p.get(u));
        }
        assertEquals(5, p.get(who.get(0)).tokens(), "1st: 5");
        assertEquals(2, p.get(who.get(3)).tokens(), "a finisher prize of 2 stays 2");
        assertEquals(5, NightRules.MAX_PRIZE_PER_NIGHT, "the reconciled bound");
    }

    @Test
    void theRulesSurviveTheRoundTripThroughTheNightsRow() {
        NightRules r = new NightRules(2, 3, 3, 6, List.of(9, 7), 2, 1, List.of(4, 2), 1, true, 120, 45, 3, 15);
        NightRules back = NightRules.decode(r.encode(), NightRules.of(RaceNightSettings.defaults(), 3, 0, false, 8));
        assertEquals(r, back, "a night keeps the settings it opened with across a reload");
        NightRules d = NightRules.of(RaceNightSettings.defaults(), 3, 0, false, 8);
        assertEquals(d, NightRules.decode("junk\nraces=lots\n", d), "a row it can't read keeps the fallback, never throws");
    }

    @Test
    void aPodiumNeedsAFinishTonightStillRacingPointsNeverWinOne() {
        List<UUID> who = racers(3);
        List<NightStandings.Ranked> night = List.of(
                new NightStandings.Ranked(who.get(0), 1, 30), // finished all three
                new NightStandings.Ranked(who.get(1), 2, 3),  // still racing each time: 1 point a race
                new NightStandings.Ranked(who.get(2), 2, 3));
        Map<UUID, RacePrizes.Prize> p = RacePrizes.plan(night, 3, List.of(who.get(0)), PRIZES, 1, true);
        assertEquals(5, p.get(who.get(0)).tokens(), "the one finisher won: 5");
        assertFalse(p.containsKey(who.get(1)), "tied 2nd on still-racing points: no podium, no finish, nothing");
        assertFalse(p.containsKey(who.get(2)), "nor the other");
        assertTrue(RacePrizes.won(night.get(0), night, List.of(who.get(0))), "and only the finisher won the night");
        assertFalse(RacePrizes.won(new NightStandings.Ranked(who.get(1), 1, 3), night, List.of(who.get(0))),
                "a 1st on still-racing points alone isn't a win");
    }

    @Test
    void aNightWhereNobodyFinishedPaysNothingAtAll() {
        List<UUID> who = racers(4);
        List<NightStandings.Ranked> idle = List.of(new NightStandings.Ranked(who.get(0), 1, 3),
                new NightStandings.Ranked(who.get(1), 1, 3), new NightStandings.Ranked(who.get(2), 1, 3),
                new NightStandings.Ranked(who.get(3), 1, 3));
        assertTrue(RacePrizes.plan(idle, 4, List.of(), PRIZES, 1, true).isEmpty(),
                "idle racers tie 1st on still-racing points: no finisher, so no prize at all");
        assertFalse(RacePrizes.won(idle.get(0), idle, List.of()), "and nobody won");
    }

    @Test
    void racersTiedForLastCameLastSoThePodiumIsByDistinctPlaces() {
        List<UUID> who = racers(3);
        List<NightStandings.Ranked> night = List.of(
                new NightStandings.Ranked(who.get(0), 1, 30),
                new NightStandings.Ranked(who.get(1), 2, 20),
                new NightStandings.Ranked(who.get(2), 2, 20));
        Map<UUID, RacePrizes.Prize> p = RacePrizes.plan(night, 3, who, PRIZES, 1, true);
        assertEquals(5, p.get(who.get(0)).tokens(), "1st, with racers behind: 5");
        assertEquals(1, p.get(who.get(1)).tokens(), "tied 2nd is tied LAST: nobody is behind them, so the finisher's 1");
        assertEquals(1, p.get(who.get(2)).tokens(), "the same for the other");
        List<NightStandings.Ranked> allLevel = List.of(new NightStandings.Ranked(who.get(0), 1, 18),
                new NightStandings.Ranked(who.get(1), 1, 18));
        Map<UUID, RacePrizes.Prize> level = RacePrizes.plan(allLevel, 2, who.subList(0, 2), PRIZES, 1, true);
        assertEquals(1, level.get(who.get(0)).tokens(), "two finishers level on everything: nobody beat anybody, 1 each");
        assertFalse(RacePrizes.won(allLevel.get(0), allLevel, who), "and no winner");
    }

    @Test
    void theLedgerSaysThePodiumPlaceOrAFinishedRace() {
        assertEquals("Race Night: 2nd place", PayLoop.detail(2), "a podium prize says its place");
        assertEquals("Race Night: finished a race", PayLoop.detail(4), "a finisher's 1 isn't a '4th place' prize");
        assertEquals("Race Night prize", PayLoop.detail(null), "no place stored");
    }
}
