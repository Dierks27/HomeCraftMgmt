package com.dierks.homecraft.games.arena.rules;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.dierks.homecraft.games.arena.rules.ArenaFixtures.p;
import static com.dierks.homecraft.games.arena.rules.ArenaFixtures.verify;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A round's life (EVENTS-DROPPER-SPEC §B.3.3): the gate, the lobby and its two ways to start, the
 * 10 s countdown, teleports two a tick, the 3 s hold, outs and shared places, the last player
 * standing, solo survival time, leaving, the restart hold, and the reset every round ends with.
 */
class ArenaRoundTest {

    // ---- helpers --------------------------------------------------------------------------------

    /** An arena whose boot verify has passed: an open lobby. */
    private static ArenaRound open(RoundSettings s, int spawns) {
        ArenaRound r = new ArenaRound(s, spawns);
        verify(r, true);
        r.drain();
        return r;
    }

    private static ArenaRound open() {
        return open(RoundSettings.defaults(), 12);
    }

    private static List<RoundEvent> ticks(ArenaRound r, int n, boolean hold) {
        List<RoundEvent> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            r.tick(hold);
            out.addAll(r.drain());
        }
        return out;
    }

    private static <T extends RoundEvent> List<T> only(List<RoundEvent> events, Class<T> type) {
        List<T> out = new ArrayList<>();
        for (RoundEvent e : events) {
            if (type.isInstance(e)) {
                out.add(type.cast(e));
            }
        }
        return out;
    }

    /** Ticks until Go (at most 100); fails if it never comes. */
    private static void untilGo(ArenaRound r) {
        for (int i = 0; i < 100; i++) {
            r.tick(false);
            if (!only(r.drain(), RoundEvent.Go.class).isEmpty()) {
                return;
            }
        }
        throw new AssertionError("no Go within 100 ticks; phase " + r.phase());
    }

    /** A multiplayer round of these players, started by everyone pressing Ready, ticked to Go. */
    private static ArenaRound playing(UUID... players) {
        ArenaRound r = open();
        for (UUID u : players) {
            r.join(u);
            r.ready(u, true);
        }
        r.tick(false); // countdown starts
        ticks(r, RoundSettings.COUNTDOWN_TICKS, false);
        untilGo(r);
        assertEquals(ArenaRound.Phase.PLAYING, r.phase(), "the round is being played");
        return r;
    }

    /** Ticks {@code n} play ticks with nobody out. */
    private static void play(ArenaRound r, int n) {
        for (int i = 0; i < n; i++) {
            r.tick(false);
        }
        r.drain();
    }

    // ---- the gate and the reset -------------------------------------------------------------------

    @Test
    void theGateIsShutAtBootUntilAVerifyPasses() {
        ArenaRound r = new ArenaRound(RoundSettings.defaults(), 12);
        assertEquals(ArenaRound.Phase.RESET, r.phase(), "nobody knows what a crash left: it starts by resetting");
        assertEquals(List.of(new RoundEvent.ResetNeeded(1, 1)), r.drain(), "and asks for the boot verify at once");
        assertEquals(ArenaRound.Join.JOINED, r.join(p(1)), "players may wait in the gallery meanwhile");
        assertEquals(ArenaRound.Solo.NOT_READY, r.solo(p(1), false), "but no round starts on unverified floors");
        ticks(r, 1000, false);
        assertEquals(ArenaRound.Phase.RESET, r.phase(), "time alone never opens the gate");
        assertTrue(r.resetDone(1, true), "a passing verify is taken");
        assertEquals(ArenaRound.Phase.LOBBY, r.phase(), "and opens the lobby");
        assertEquals(List.of(new RoundEvent.LobbyOpen()), r.drain(), "announced");
        assertFalse(r.resetDone(1, true), "a late or second answer is ignored");
    }

    @Test
    void aResetThatFailsItsVerifyThreeTimesClosesTheGame() {
        ArenaRound r = new ArenaRound(RoundSettings.defaults(), 12);
        r.drain();
        r.resetDone(1, false);
        assertEquals(List.of(new RoundEvent.ResetNeeded(2, 2)), r.drain(), "the first failure tries again");
        r.resetDone(2, false);
        assertEquals(List.of(new RoundEvent.ResetNeeded(3, 3)), r.drain(), "and the second");
        r.resetDone(3, false);
        assertEquals(ArenaRound.Phase.CLOSED, r.phase(), "the third closes the game");
        List<RoundEvent> closed = r.drain();
        assertEquals(1, only(closed, RoundEvent.Closed.class).size(), "with a Closed event for the status line");
        assertTrue(r.closedReason().contains("3"), "saying how many tries: " + r.closedReason());
        assertEquals(ArenaRound.Join.CLOSED, r.join(p(1)), "nobody can join a closed game");
        assertTrue(r.reopen(), "an admin can open it again");
        assertEquals(List.of(new RoundEvent.ResetNeeded(4, 1)), r.drain(),
                "starting with a fresh reset (a new ticket, attempt 1), like a boot");
        r.resetDone(4, false);
        r.resetDone(5, false);
        assertEquals(ArenaRound.Phase.RESET, r.phase(), "the count starts again after a reopen");
    }

    @Test
    void aLateAnswerToAResetThatWasReplacedNeverOpensTheGate() {
        ArenaRound r = new ArenaRound(RoundSettings.defaults(), 12);
        assertEquals(List.of(new RoundEvent.ResetNeeded(1, 1)), r.drain(), "the boot reset: job A, on last week's plan");
        assertTrue(r.requestReset(), "the week turns over while job A runs: a reset on the new plan is asked for");
        assertEquals(List.of(new RoundEvent.ResetNeeded(2, 1)), r.drain(),
                "job B, with a new ticket (and still the first try: nothing has failed)");
        assertFalse(r.resetDone(1, true), "job A verified the box against the OLD plan: its answer is ignored");
        assertEquals(ArenaRound.Phase.RESET, r.phase(), "so the gate stays shut");
        assertTrue(r.drain().isEmpty(), "and no LobbyOpen is sent");
        r.join(p(1));
        assertEquals(ArenaRound.Solo.NOT_READY, r.solo(p(1), false),
                "no solo round on a box nobody has verified against this week's plan");
        assertTrue(r.resetDone(2, false), "job B's failure is the answer that counts");
        assertEquals(List.of(new RoundEvent.ResetNeeded(3, 2)), r.drain(), "and it is tried again, as attempt 2");
        assertFalse(r.resetDone(2, true), "job B can't answer twice");
        assertTrue(r.resetDone(3, true), "the retry verifies");
        assertEquals(ArenaRound.Phase.LOBBY, r.phase(), "and only then does the gate open");
    }

    @Test
    void askingForAResetAgainAndAgainNeverRestartsTheCountOfFailedVerifies() {
        ArenaRound r = new ArenaRound(RoundSettings.defaults(), 12);
        r.drain();
        verify(r, false);
        verify(r, false);
        assertEquals(List.of(new RoundEvent.ResetNeeded(2, 2), new RoundEvent.ResetNeeded(3, 3)), r.drain(),
                "two failed verifies: the third try is running");
        assertTrue(r.requestReset(), "an admin resets during it");
        assertEquals(List.of(new RoundEvent.ResetNeeded(4, 3)), r.drain(),
                "the running job is replaced, and it is still the third try");
        assertFalse(r.resetDone(3, false), "the replaced job's failure doesn't count twice");
        assertEquals(ArenaRound.Phase.RESET, r.phase(), "still resetting");
        assertTrue(r.resetDone(4, false), "the replacement fails as well");
        assertEquals(ArenaRound.Phase.CLOSED, r.phase(), "three failed verifies in a row close the game");
    }

    @Test
    void anAnswerFromBeforeACloseCannotOpenTheReopenedGate() {
        ArenaRound r = new ArenaRound(RoundSettings.defaults(), 12);
        r.drain(); // the boot reset, ticket 1, is running
        r.close("The game was switched off");
        assertTrue(r.reopen(), "an admin opens it again");
        assertEquals(List.of(new RoundEvent.Closed("The game was switched off"), new RoundEvent.ResetNeeded(2, 1)),
                r.drain(), "the reopen asks for its own reset");
        assertFalse(r.resetDone(1, true), "the job from before the close is not the reopen's verify");
        assertEquals(ArenaRound.Phase.RESET, r.phase(), "the gate stays shut until ticket 2 answers");
        assertTrue(r.resetDone(2, true), "its own answer");
        assertEquals(ArenaRound.Phase.LOBBY, r.phase(), "opens it");
    }

    @Test
    void everyRoundEndsWithTheResetAndThenTheLobbyAgain() {
        ArenaRound r = playing(p(1), p(2));
        r.out(p(1), OutReason.FELL);
        List<RoundEvent> end = ticks(r, 1, false);
        assertEquals(1, only(end, RoundEvent.Ended.class).size(), "the round ended");
        assertEquals(List.of(new RoundEvent.ResetNeeded(2, 1)), only(end, RoundEvent.ResetNeeded.class),
                "and asked for the reset straight after the results (the boot's was ticket 1)");
        assertEquals(ArenaRound.Phase.RESET, r.phase(), "the gate is shut while the floors come back");
        ticks(r, 500, false);
        assertEquals(ArenaRound.Phase.RESET, r.phase(), "no round starts before the floors are verified whole");
        verify(r, true);
        assertEquals(ArenaRound.Phase.LOBBY, r.phase(), "then back to the lobby");
        assertEquals(2, r.members().size(), "with everyone still in the gallery");
    }

    // ---- the lobby ------------------------------------------------------------------------------

    @Test
    void enoughReadyPlayersStartATenSecondCountdown() {
        ArenaRound r = open();
        r.join(p(1));
        r.join(p(2));
        r.ready(p(1), true);
        assertTrue(ticks(r, 5, false).isEmpty(), "one ready of the two needed: nothing yet");
        r.ready(p(2), true);
        assertEquals(List.of(new RoundEvent.CountdownStarted(200)), ticks(r, 1, false),
                "min_players ready: the 10 s bar starts");
        assertEquals(200, r.countdownLeft(), "10 s of ticks");
        List<RoundEvent> during = ticks(r, 199, false);
        assertTrue(during.isEmpty(), "nothing happens for the rest of the bar: " + during);
        List<RoundEvent> start = ticks(r, 1, false);
        assertEquals(List.of(new RoundEvent.RoundStarting(1, List.of(p(1), p(2)), false)), start,
                "exactly 10 s after the bar started, the round starts with everyone here");
        assertEquals(0, r.readyCount(), "Ready is for one round: it clears as the round starts");
    }

    @Test
    void twentySecondsAfterTheSecondArrivalItStartsAnyway() {
        ArenaRound r = open();
        r.join(p(1));
        ticks(r, 100, false);
        assertEquals(-1, r.autoStartIn(), "alone, nothing counts down");
        r.join(p(2));
        assertEquals(400, r.autoStartIn(), "the second arrival starts 20 s");
        assertTrue(ticks(r, 399, false).isEmpty(), "nobody pressed Ready: it waits the whole 20 s");
        assertEquals(1, r.autoStartIn(), "one tick to go");
        assertEquals(List.of(new RoundEvent.CountdownStarted(200)), ticks(r, 1, false),
                "then the bar starts anyway");
    }

    @Test
    void theTwentySecondsStartAgainIfTheSecondPlayerLeavesAndSomeoneElseComes() {
        ArenaRound r = open();
        r.join(p(1));
        r.join(p(2));
        ticks(r, 300, false);
        r.leave(p(2));
        assertEquals(-1, r.autoStartIn(), "down to one: no auto start");
        ticks(r, 50, false);
        r.join(p(3));
        assertEquals(400, r.autoStartIn(), "a new second arrival gets the full 20 s");
    }

    @Test
    void theCountdownStopsWhenTooFewAreLeft() {
        ArenaRound r = open();
        r.join(p(1));
        r.join(p(2));
        r.ready(p(1), true);
        r.ready(p(2), true);
        ticks(r, 50, false);
        r.leave(p(2));
        assertEquals(List.of(new RoundEvent.CountdownCancelled(RoundEvent.Why.TOO_FEW)), r.drain(),
                "one left: the bar stops");
        assertEquals(ArenaRound.Phase.LOBBY, r.phase(), "back to waiting");
    }

    @Test
    void joiningAFullArenaOrJoiningTwiceIsRefused() {
        ArenaRound r = open(new RoundSettings(10, 2, 2, true, 180), 12);
        assertEquals(ArenaRound.Join.JOINED, r.join(p(1)), "the first");
        assertEquals(ArenaRound.Join.ALREADY_IN, r.join(p(1)), "twice is refused");
        assertEquals(ArenaRound.Join.JOINED, r.join(p(2)), "the second");
        assertEquals(ArenaRound.Join.FULL, r.join(p(3)), "max_players reached");
        assertTrue(r.leave(p(1)), "someone leaves");
        assertEquals(ArenaRound.Join.JOINED, r.join(p(3)), "and there is room again");
        assertFalse(r.leave(p(9)), "leaving when not here does nothing");
    }

    @Test
    void readyOnlyCountsInTheLobbyOrTheCountdown() {
        ArenaRound r = playing(p(1), p(2), p(3));
        assertFalse(r.ready(p(1), true), "Ready during a round does nothing");
        assertFalse(r.ready(p(9), true), "nor from someone not here");
        assertEquals(0, r.readyCount(), "nobody is ready");
    }

    // ---- solo -----------------------------------------------------------------------------------

    @Test
    void soloStartsAtOnceButOnlyAloneWithSoloOnAndNoRestartClose() {
        ArenaRound r = open();
        assertEquals(ArenaRound.Solo.NOT_IN, r.solo(p(1), false), "join first");
        r.join(p(1));
        assertEquals(ArenaRound.Solo.HELD, r.solo(p(1), true), "no new round in the restart hold");
        assertEquals(ArenaRound.Solo.STARTED, r.solo(p(1), false), "alone: it starts");
        assertEquals(List.of(new RoundEvent.RoundStarting(1, List.of(p(1)), true)), r.drain(),
                "at once: no countdown bar");
        assertEquals(ArenaRound.Phase.TELEPORT, r.phase(), "straight to the spawn");
        assertEquals(ArenaRound.Solo.BUSY, r.solo(p(1), false), "not twice");

        ArenaRound two = open();
        two.join(p(1));
        two.join(p(2));
        assertEquals(ArenaRound.Solo.NOT_ALONE, two.solo(p(1), false), "someone else is here: play together");

        ArenaRound off = open(new RoundSettings(10, 2, 12, false, 180), 12);
        off.join(p(1));
        assertEquals(ArenaRound.Solo.OFF, off.solo(p(1), false), "solo: false");
    }

    @Test
    void soloSurvivalIsTheTicksFromGoToTheFallInMilliseconds() {
        ArenaRound r = open();
        r.join(p(1));
        r.solo(p(1), false);
        untilGo(r);
        play(r, 840);
        assertEquals(840, r.playTicks(), "840 ticks played");
        assertTrue(r.out(p(1), OutReason.FELL), "then they fall");
        List<RoundEvent> end = ticks(r, 1, false);
        RoundEvent.Out out = only(end, RoundEvent.Out.class).get(0);
        assertEquals(new RoundEvent.Out(p(1), 840, 1, 1, false, OutReason.FELL, true, false), out,
                "out after 840 ticks, 1st of 1, solo");
        RoundResult res = only(end, RoundEvent.Ended.class).get(0).result();
        assertTrue(res.solo(), "a solo round");
        assertEquals(42_000, res.standings().get(0).survivalMs(), "0:42 is 42,000 ms (ticks x 50)");
        assertTrue(res.winners().isEmpty(), "solo has no winner: nobody to beat");
        assertFalse(res.contested(), "and isn't contested");
    }

    @Test
    void aSoloRoundGoesOnUntilThePlayerIsOutWhoeverArrives() {
        ArenaRound r = open();
        r.join(p(1));
        r.solo(p(1), false);
        untilGo(r);
        r.join(p(2));
        play(r, 500);
        assertEquals(ArenaRound.Phase.PLAYING, r.phase(), "a newcomer doesn't end a solo round");
        assertEquals(List.of(p(2)), r.waiting(), "they watch and play the next one");
        assertFalse(r.out(p(2), OutReason.FELL), "and can't be out of a round they aren't in");
    }

    // ---- the start: teleports and hold ----------------------------------------------------------

    @Test
    void playersAreTeleportedTwoATickThenHeldThreeSecondsBeforeGo() {
        ArenaRound r = open();
        for (int i = 1; i <= 5; i++) {
            r.join(p(i));
            r.ready(p(i), true);
        }
        ticks(r, 1 + RoundSettings.COUNTDOWN_TICKS, false);
        assertEquals(ArenaRound.Phase.TELEPORT, r.phase(), "the countdown is over");
        assertEquals(2, only(ticks(r, 1, false), RoundEvent.TeleportTo.class).size(), "two on the first tick");
        assertEquals(2, only(ticks(r, 1, false), RoundEvent.TeleportTo.class).size(), "two on the next");
        List<RoundEvent> third = ticks(r, 1, false);
        assertEquals(1, only(third, RoundEvent.TeleportTo.class).size(), "the last one");
        assertEquals(List.of(new RoundEvent.HoldStarted(60)), only(third, RoundEvent.HoldStarted.class),
                "and the 3 s hold starts once everyone is on a spawn");
        assertTrue(only(ticks(r, 59, false), RoundEvent.Go.class).isEmpty(), "held for 59 ticks");
        assertEquals(List.of(new RoundEvent.Go(1)), ticks(r, 1, false), "Go on the 60th");
        assertEquals(0, r.playTicks(), "play time starts at 0");
    }

    @Test
    void spawnsAreSpreadEvenlyAndTurnEachRound() {
        assertEquals(List.of(0, 4, 8), List.of(ArenaRound.spawnIndex(0, 3, 12, 1), ArenaRound.spawnIndex(1, 3, 12, 1),
                ArenaRound.spawnIndex(2, 3, 12, 1)), "round 1, three players on twelve spawns: a third of the way round each");
        assertEquals(List.of(1, 5, 9), List.of(ArenaRound.spawnIndex(0, 3, 12, 2), ArenaRound.spawnIndex(1, 3, 12, 2),
                ArenaRound.spawnIndex(2, 3, 12, 2)), "round 2: the same spread, turned by one");
        assertEquals(List.of(0, 1, 0), List.of(ArenaRound.spawnIndex(0, 3, 2, 1), ArenaRound.spawnIndex(1, 3, 2, 1),
                ArenaRound.spawnIndex(2, 3, 2, 1)), "more players than spawns share them in turn");

        ArenaRound r = open(RoundSettings.defaults(), 12);

        r.join(p(1));
        r.solo(p(1), false);
        List<RoundEvent> first = ticks(r, 1, false);
        assertEquals(0, only(first, RoundEvent.TeleportTo.class).get(0).spawn(), "round 1's first spawn is 0");
        r.leave(p(1)); // before Go: called off, reset
        verify(r, true);
        r.join(p(1));
        r.solo(p(1), false);
        List<RoundEvent> second = ticks(r, 1, false);
        assertEquals(1, only(second, RoundEvent.TeleportTo.class).get(0).spawn(),
                "round 2 turns by one, so nobody always gets the same spot");
    }

    // ---- outs, places, the last one standing ------------------------------------------------------

    @Test
    void eliminationOrderGivesPlacesAndTheLastOneStandingWins() {
        ArenaRound r = playing(p(1), p(2), p(3), p(4));
        play(r, 10);
        r.out(p(1), OutReason.FELL);
        List<RoundEvent> first = ticks(r, 1, false);
        assertEquals(List.of(new RoundEvent.Out(p(1), 10, 4, 4, false, OutReason.FELL, false, false)), first,
                "the first out is 4th of 4 at 10 ticks");
        play(r, 9);
        r.out(p(3), OutReason.FELL);
        List<RoundEvent> second = ticks(r, 1, false);
        assertEquals(List.of(new RoundEvent.Out(p(3), 20, 3, 4, false, OutReason.FELL, false, false)), second,
                "the next is 3rd at 20 ticks");
        r.out(p(2), OutReason.FELL);
        List<RoundEvent> last = ticks(r, 1, false);
        assertEquals(new RoundEvent.Out(p(2), 21, 2, 4, false, OutReason.FELL, false, false),
                only(last, RoundEvent.Out.class).get(0), "2nd at 21 ticks");
        RoundResult res = only(last, RoundEvent.Ended.class).get(0).result();
        assertEquals(21, res.ticks(), "one player left: the round ends on that tick");
        assertEquals(List.of(p(4), p(2), p(3), p(1)), res.standings().stream().map(Standing::player).toList(),
                "standings by place");
        Standing winner = res.standing(p(4));
        assertTrue(winner.winner() && winner.stillStanding(), "the last one standing wins");
        assertEquals(21, winner.survivedTicks(), "and lasted as long as the round");
        assertEquals(List.of(p(4)), res.winners(), "only them");
        assertTrue(res.contested(), "a real contest");
    }

    @Test
    void playersOutOnTheSameTickShareThePlaceWhateverOrderTheyWereSeenIn() {
        ArenaRound r = playing(p(1), p(2), p(3), p(4));
        play(r, 30);
        r.out(p(3), OutReason.FELL); // seen in this order...
        r.out(p(1), OutReason.FELL);
        List<RoundEvent> outs = ticks(r, 1, false);
        assertEquals(List.of(new RoundEvent.Out(p(1), 30, 3, 4, true, OutReason.FELL, false, false),
                        new RoundEvent.Out(p(3), 30, 3, 4, true, OutReason.FELL, false, false)), outs,
                "...but both are joint 3rd, listed in start order: the server's checking order decides nothing");
        play(r, 5);
        r.out(p(2), OutReason.FELL);
        RoundResult res = only(ticks(r, 1, false), RoundEvent.Ended.class).get(0).result();
        assertEquals(List.of(1, 2, 3, 3), res.standings().stream().map(Standing::place).toList(),
                "places 1, 2, 3, 3");
    }

    @Test
    void theLastPlayersOutTogetherShareFirstPlace() {
        ArenaRound r = playing(p(1), p(2), p(3));
        play(r, 40);
        r.out(p(1), OutReason.FELL);
        r.out(p(2), OutReason.FELL);
        r.out(p(3), OutReason.FELL);
        List<RoundEvent> end = ticks(r, 1, false);
        assertTrue(only(end, RoundEvent.Out.class).stream().allMatch(o -> o.won() && o.tied() && o.place() == 1),
                "each hears they won together: " + end);
        RoundResult res = only(end, RoundEvent.Ended.class).get(0).result();
        assertEquals(List.of(1, 1, 1), res.standings().stream().map(Standing::place).toList(),
                "nobody was left standing: all three share 1st");
        assertEquals(List.of(p(1), p(2), p(3)), res.winners(), "and all three won together");
        assertTrue(res.standings().get(0).tied(), "marked as a shared place");
    }

    @Test
    void aPlayerWhoLeavesOnTheTickAnotherFallsNeitherSharesTheirPlaceNorMakesItAWin() {
        ArenaRound two = playing(p(1), p(2));
        play(two, 100);
        two.out(p(1), OutReason.FELL);
        two.leave(p(2));
        List<RoundEvent> end = ticks(two, 1, false);
        assertEquals(List.of(new RoundEvent.Out(p(1), 100, 1, 2, false, OutReason.FELL, false, false),
                        new RoundEvent.Out(p(2), 100, 1, 2, false, OutReason.LEFT, false, false)),
                only(end, RoundEvent.Out.class),
                "p1 fell as p2 left: 1st, but not shared with a leaver, and not a win (nobody else played it out)");
        RoundResult res = only(end, RoundEvent.Ended.class).get(0).result();
        assertFalse(res.contested(), "the result agrees: not contested");
        assertTrue(res.winners().isEmpty(), "and no winner");
        assertFalse(res.standing(p(1)).tied(), "p1's standing isn't marked as shared either");

        ArenaRound three = playing(p(1), p(2), p(3));
        play(three, 50);
        three.out(p(3), OutReason.FELL);
        ticks(three, 1, false);
        play(three, 49);
        three.out(p(1), OutReason.FELL);
        three.leave(p(2));
        end = ticks(three, 1, false);
        assertEquals(new RoundEvent.Out(p(1), 100, 1, 3, false, OutReason.FELL, false, true),
                only(end, RoundEvent.Out.class).get(0),
                "with p3 out-lasted it is contested: p1's 1st is a win, and theirs alone, not shared with the leaver");
        assertEquals(List.of(p(1)), only(end, RoundEvent.Ended.class).get(0).result().winners(),
                "the result counts exactly that win");
    }

    @Test
    void leavingMidRoundIsOutAndEveryoneElseLeavingIsNoWin() {
        ArenaRound r = playing(p(1), p(2));
        play(r, 50);
        assertTrue(r.leave(p(2)), "a player leaves mid-round");
        List<RoundEvent> end = ticks(r, 1, false);
        assertEquals(new RoundEvent.Out(p(2), 50, 2, 2, false, OutReason.LEFT, false, false),
                only(end, RoundEvent.Out.class).get(0), "they are out, as having left");
        RoundResult res = only(end, RoundEvent.Ended.class).get(0).result();
        assertFalse(res.contested(), "only one player played it out");
        assertTrue(res.winners().isEmpty(), "so the one left is not handed a win");
        assertTrue(res.standing(p(1)).stillStanding(), "though they were still standing");
    }

    @Test
    void leavingBeforeGoDropsThePlayerAndCallsOffARoundWithTooFew() {
        ArenaRound r = open();
        for (int i = 1; i <= 3; i++) {
            r.join(p(i));
            r.ready(p(i), true);
        }
        ticks(r, 1 + RoundSettings.COUNTDOWN_TICKS + 2, false);
        assertEquals(ArenaRound.Phase.HOLD, r.phase(), "on the spawns, held");
        r.leave(p(3));
        assertEquals(List.of(p(1), p(2)), r.starters(), "a leaver before Go is simply dropped");
        assertTrue(r.drain().isEmpty(), "two are enough: the round goes on");
        r.leave(p(2));
        List<RoundEvent> off = r.drain();
        RoundResult res = only(off, RoundEvent.Ended.class).get(0).result();
        assertTrue(res.calledOff(), "one left before Go: the round is called off");
        assertTrue(res.standings().isEmpty(), "with no results");
        assertEquals(ArenaRound.Phase.RESET, r.phase(), "and the floors are reset (the reset moves the player back)");
    }

    @Test
    void aSoloPlayerLeavingBeforeGoCallsItOff() {
        ArenaRound r = open();
        r.join(p(1));
        r.solo(p(1), false);
        r.drain();
        r.leave(p(1));
        assertTrue(only(r.drain(), RoundEvent.Ended.class).get(0).result().calledOff(), "called off");
    }

    @Test
    void outIsOnlyNotedForPlayersStillInDuringPlay() {
        ArenaRound r = open();
        r.join(p(1));
        r.join(p(2));
        assertFalse(r.out(p(1), OutReason.FELL), "not during the lobby");
        r = playing(p(1), p(2), p(3));
        assertTrue(r.out(p(1), OutReason.FELL), "noted");
        assertFalse(r.out(p(1), OutReason.LEFT), "not twice in one tick (the first reason stands)");
        r.tick(false);
        r.drain();
        assertFalse(r.out(p(1), OutReason.FELL), "nor once they're out");
        assertFalse(r.out(p(1), null), "nor without a reason");
    }

    // ---- sudden death, the restart hold, resets and closing ----------------------------------------

    @Test
    void suddenDeathIsAnnouncedAtRoundSeconds() {
        ArenaRound r = open(new RoundSettings(10, 2, 12, true, 30), 12);
        r.join(p(1));
        r.solo(p(1), false);
        untilGo(r);
        List<RoundEvent> before = ticks(r, 600, false);
        assertTrue(only(before, RoundEvent.SuddenDeath.class).isEmpty(), "not before 30 s of play");
        assertEquals(List.of(new RoundEvent.SuddenDeath(1)), ticks(r, 1, false),
                "on the play tick 600 (30 s), the same tick the floors take their first ring");
        assertTrue(r.suddenDeath(), "and it stays on");
        assertTrue(ticks(r, 100, false).isEmpty(), "announced once");
    }

    @Test
    void theRestartHoldStartsNoNewRoundButLetsOneGoingFinish() {
        ArenaRound r = open();
        r.join(p(1));
        r.join(p(2));
        r.ready(p(1), true);
        r.ready(p(2), true);
        assertTrue(ticks(r, 1000, true).isEmpty(), "held: no countdown, however ready they are");
        ticks(r, 10, false);
        assertEquals(ArenaRound.Phase.COUNTDOWN, r.phase(), "the hold lifted: the bar starts");
        assertEquals(List.of(new RoundEvent.CountdownCancelled(RoundEvent.Why.HOLD)), ticks(r, 1, true),
                "a hold during the bar stops it");

        ArenaRound going = playing(p(1), p(2));
        ticks(going, 200, true);
        assertEquals(ArenaRound.Phase.PLAYING, going.phase(), "a round going when the hold starts carries on");
        going.out(p(1), OutReason.FELL);
        assertEquals(1, only(ticks(going, 1, true), RoundEvent.Ended.class).size(), "and finishes");
    }

    @Test
    void aResetRequestFromTheLobbyOrTheCountdownHappensNowAndMidRoundWaits() {
        ArenaRound r = open();
        assertTrue(r.requestReset(), "from the lobby");
        assertEquals(List.of(new RoundEvent.ResetNeeded(2, 1)), r.drain(), "at once");
        verify(r, true);
        r.join(p(1));
        r.join(p(2));
        r.ready(p(1), true);
        r.ready(p(2), true);
        ticks(r, 1, false);
        assertTrue(r.requestReset(), "from the countdown");
        assertEquals(List.of(new RoundEvent.CountdownCancelled(RoundEvent.Why.RESET), new RoundEvent.ResetNeeded(3, 1)),
                r.drain(), "the bar stops and the reset starts");
        ArenaRound going = playing(p(1), p(2));
        assertFalse(going.requestReset(), "mid-round it waits: every round ends with a reset anyway");
        assertEquals(ArenaRound.Phase.PLAYING, going.phase(), "the round is untouched");
    }

    @Test
    void closingMidRoundCallsItOffAndReopeningStartsWithAReset() {
        ArenaRound r = playing(p(1), p(2));
        r.close("The game was switched off");
        List<RoundEvent> closed = r.drain();
        assertTrue(only(closed, RoundEvent.Ended.class).get(0).result().calledOff(), "the round is called off");
        assertEquals(List.of(new RoundEvent.Closed("The game was switched off")),
                only(closed, RoundEvent.Closed.class), "and the game says why");
        assertEquals(ArenaRound.Phase.CLOSED, r.phase(), "closed");
        assertEquals(ArenaRound.Solo.CLOSED, r.solo(p(1), false), "nothing starts");
        assertNull(RoundResult.calledOff(1, false, 2, 0).standing(p(1)), "a called-off round has no standings");
        assertTrue(r.reopen(), "reopened");
        assertEquals(ArenaRound.Phase.RESET, r.phase(), "through the gate, as at boot");
    }

    @Test
    void settingsAreClampedSoEveryRoundCanEnd() {
        RoundSettings wild = new RoundSettings(0, 99, 99, true, 1_000_000);
        assertEquals(RoundSettings.MIN_FADE_TICKS, wild.fadeTicks(), "a fade of 0 would be no warning: 6 at least");
        assertEquals(RoundSettings.MAX_PLAYERS_LIMIT, wild.maxPlayers(), "at most 16 (the writer's 128 a tick)");
        assertEquals(RoundSettings.MAX_PLAYERS_LIMIT, wild.minPlayers(), "min never above max");
        assertEquals(RoundSettings.MAX_ROUND_SECONDS, wild.roundSeconds(), "sudden death always comes");
        RoundSettings low = new RoundSettings(99, 0, 0, false, 0);
        assertEquals(RoundSettings.MAX_FADE_TICKS, low.fadeTicks(), "a fade of 99 would let players stand: 20 at most");
        assertEquals(2, low.maxPlayers(), "a multiplayer arena holds 2 at least");
        assertEquals(2, low.minPlayers(), "and needs 2 ready at least");
        assertEquals(RoundSettings.MIN_ROUND_SECONDS, low.roundSeconds(), "30 s at least");
        RoundSettings d = RoundSettings.defaults();
        assertEquals(List.of(10, 2, 12, 180), List.of(d.fadeTicks(), d.minPlayers(), d.maxPlayers(), d.roundSeconds()),
                "the shipped values of games.falling_floors");
        assertTrue(d.solo(), "solo ships on");
        assertEquals(3600, d.roundTicks(), "180 s of ticks");
    }
}
