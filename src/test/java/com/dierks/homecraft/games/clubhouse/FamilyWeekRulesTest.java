package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.trial.PartyRace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The family week's rules (0.38.4): the points table, the daily cap, the boards' names, who won a party race, the
 * ceremony's decision table, and the chat line.
 */
class FamilyWeekRulesTest {

    private static final long WEEK = 20_700;
    private static final long START = 1_790_000_000_000L;
    private static final UUID SAM = UUID.randomUUID();
    private static final UUID LEE = UUID.randomUUID();

    @Test
    void thePointsTable() {
        assertEquals(1, FamilyWeekRules.runPoints(false, false, false), "a finished run");
        assertEquals(3, FamilyWeekRules.runPoints(true, false, false), "a personal best: 1 + 2");
        assertEquals(6, FamilyWeekRules.runPoints(true, true, false), "the week's best too: 1 + 2 + 3");
        assertEquals(11, FamilyWeekRules.runPoints(true, true, true), "and a record: 1 + 2 + 3 + 5");
        assertEquals(1, FamilyWeekRules.golfPoints(false, false, 0), "a round of golf");
        assertEquals(3, FamilyWeekRules.golfPoints(true, false, 0), "a personal best round");
        assertEquals(8, FamilyWeekRules.golfPoints(true, true, 0), "a record round: 1 + 2 + 5");
        assertEquals(7, FamilyWeekRules.golfPoints(false, false, 2), "two holes-in-one: 1 + 3 + 3");
        assertEquals(3, FamilyWeekRules.PARTY_WIN, "a party race win");
        assertEquals(1, FamilyWeekRules.CHEER, "a cheer given");
        assertEquals(10, FamilyWeekRules.DAY_CAP, "at most 10 a day");
    }

    @Test
    void theDailyCapNeverGoesBelowZero() {
        assertEquals(4, FamilyWeekRules.capped(4, 0, 10), "room for all of it");
        assertEquals(2, FamilyWeekRules.capped(4, 8, 10), "only what fits");
        assertEquals(0, FamilyWeekRules.capped(4, 10, 10), "the day is full");
        assertEquals(0, FamilyWeekRules.capped(4, 12, 10), "never negative");
        assertEquals(0, FamilyWeekRules.capped(0, 0, 10), "nothing to add");
    }

    @Test
    void theBoardsAreNamedByTheirWeekAndDay() {
        assertEquals("club:20700", FamilyWeekRules.boardOf(WEEK), "the week's points");
        assertEquals("clubday:20703", FamilyWeekRules.dayBoard(20_703), "a day's cap");
        assertEquals("clubwins:20700", FamilyWeekRules.winsBoard(WEEK), "the week's race wins");
    }

    @Test
    void aPartyRacesWinnerIsTheFirstFinisherOfAtLeastTwo() {
        List<PartyRace.Line> lines = List.of(new PartyRace.Line(1, LEE, "Lee", 0, PartyRace.Result.LEFT),
                new PartyRace.Line(2, SAM, "Sam", 61_000, PartyRace.Result.FINISHED),
                new PartyRace.Line(3, UUID.randomUUID(), "Ana", 62_000, PartyRace.Result.FINISHED));
        assertEquals(SAM, FamilyWeekRules.partyWinner(lines, 3), "the first FINISHED line of a ranked list");
        assertNull(FamilyWeekRules.partyWinner(lines, 1), "a race alone has no winner");
        assertNull(FamilyWeekRules.partyWinner(List.of(new PartyRace.Line(1, LEE, "Lee", 0,
                PartyRace.Result.STILL_RACING)), 2), "nobody finished");
        assertNull(FamilyWeekRules.partyWinner(null, 2), "no lines");
    }

    @Test
    void theCeremonyRunsOncePerWeekWhenTwoOfLastWeeksThreeAreIn() {
        String done = Long.toString(WEEK);
        assertEquals(FamilyWeekRules.Turn.NONE, FamilyWeekRules.turn(WEEK, done, START, START, 3, 3, false, false),
                "this week is done: nothing");
        assertEquals(FamilyWeekRules.Turn.DONE_NOW, FamilyWeekRules.turn(WEEK, null, START, START, 3, 3, false, false),
                "the first week ever: nothing to celebrate, mark it done");
        assertEquals(FamilyWeekRules.Turn.DONE_NOW, FamilyWeekRules.turn(WEEK, "20693", START, START, 0, 0, false,
                false), "last week had nobody: mark it done");
        assertEquals(FamilyWeekRules.Turn.CEREMONY, FamilyWeekRules.turn(WEEK, "20693", START + 1, START, 3, 2, false,
                false), "two of three here: the ceremony");
        assertEquals(FamilyWeekRules.Turn.NONE, FamilyWeekRules.turn(WEEK, "20693", START + 1, START, 3, 1, false,
                false), "one here: wait");
        assertEquals(FamilyWeekRules.Turn.NONE, FamilyWeekRules.turn(WEEK, "20693", START + 1, START, 3, 3, true,
                false), "closing for a restart: wait");
        assertEquals(FamilyWeekRules.Turn.NONE, FamilyWeekRules.turn(WEEK, "20693", START + 1, START, 3, 3, false,
                true), "a Photo already waiting: wait");
        assertEquals(FamilyWeekRules.Turn.EXPIRED, FamilyWeekRules.turn(WEEK, "20693", START + FamilyWeekRules
                .CEREMONY_MS, START, 3, 3, false, false), "7 days later: let the week go");
        assertEquals(FamilyWeekRules.Turn.CEREMONY, FamilyWeekRules.turn(WEEK, "20686", START + 1, START, 2, 2, false,
                false), "a week skipped meanwhile still gets last week's");
    }

    @Test
    void theChatLineNamesThemInPodiumOrder() {
        assertEquals("&6Last week: &f1. Sam, 2. Lee, 3. Ana", FamilyWeekRules.lastLine(List.of("Sam", "Lee", "Ana")));
        assertEquals("&6Last week: &f1. Sam", FamilyWeekRules.lastLine(List.of("Sam")), "one is fine");
    }
}
