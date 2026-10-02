package com.dierks.homecraft.games.golf;

import com.dierks.homecraft.games.gen.api.GenCopy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golf together's flow on the server side (EVENTS-OWNER-DECISIONS D4; the R2 review's #4, #5, #6 and
 * #18), with no server: {@link GolfGroups} over a bench that plays {@link GolfRounds}' part (the
 * rounds, the world sessions, the tees and the record) exactly as it calls in.
 *
 * <p>Pinned here: everyone goes to hole 1 together and moves to each next tee together; a player who
 * can't go, or whose trip never arrives, stops holding the group up within a second (the others are
 * told plainly), and a group nobody reached is over with its party open again; each round is recorded
 * once, at its player's own last hole, on the day it was played, whatever happens next (leaving, a
 * disconnect, the group's end); a finished player who leaves early stays on the card as played and is
 * never sent home twice; a round alone never plays while its player is still listed in a group; and
 * the hole clock picks up a ball still out at the course's pick-up score once it runs out.
 */
class GolfGroupsTest {

    private static final UUID SAM = new UUID(0, 1);
    private static final UUID AVA = new UUID(0, 2);
    private static final UUID LEE = new UUID(0, 3);
    private static final Map<UUID, String> NAMES = Map.of(SAM, "Sam", AVA, "Ava", LEE, "Lee");
    private static final long PARTY = 7;
    /** Golf's default: a hole is picked up at par + 3. */
    private static final int MAX_OVER_PAR = 3;

    private static GolfCourse course(int holes) {
        GolfCourse.Hole h = new GolfCourse.Hole(new GolfCourse.Tee(10.5, 64.0, -3.5, 90f), new GolfCourse.Spot(18, 63, -3),
                3, new GolfCourse.Spot(8, 63, -6), new GolfCourse.Spot(20, 66, 0));
        List<GolfCourse.Hole> all = new ArrayList<>();
        for (int i = 0; i < holes; i++) {
            all.add(h);
        }
        return new GolfCourse("meadow", "Meadow Links", "games", true, 4, all);
    }

    /**
     * The server as the flow sees it. Each method mirrors the GolfRounds call it stands for: a trip
     * that starts, arrives ({@code begin}) or never does; a ball in the cup ({@code inCup} →
     * {@code afterHole}); a session that ends ({@code ended}) or a quit ({@code quit}, then
     * {@code ended}). The trip home after the group's end ends the session at once, as a
     * same-tick teleport does.
     */
    private static final class Bench implements GolfGroups.Port {
        final GolfCourse course;
        final GolfGroups groups = new GolfGroups(this);
        final Map<UUID, LiveRound> rounds = new HashMap<>();
        final Set<UUID> sessions = new HashSet<>();
        final Set<UUID> cantGo = new HashSet<>();
        final Map<UUID, GolfGroup> trips = new HashMap<>();
        final Map<UUID, List<String>> told = new HashMap<>();
        /** The day each round was recorded on, per player. */
        final Map<UUID, List<Long>> recorded = new HashMap<>();
        final Map<UUID, Integer> home = new HashMap<>();
        final Map<UUID, Integer> tees = new HashMap<>();
        final Map<UUID, Integer> pickedUp = new HashMap<>();
        final List<Long> partiesOpened = new ArrayList<>();
        final List<Runnable> tasks = new ArrayList<>();
        long day = 1;

        Bench(int holes) {
            this(course(holes));
        }

        Bench(GolfCourse course) {
            this.course = course;
        }

        /** GolfTogether's Start: these players, in join order. */
        boolean start(UUID... who) {
            Map<UUID, String> names = new LinkedHashMap<>();
            for (UUID id : who) {
                names.put(id, NAMES.get(id));
            }
            return groups.start(PARTY, course, names);
        }

        GolfGroup group() {
            GolfGroup g = groups.of(SAM);
            return g != null ? g : groups.of(AVA);
        }

        /** The trip arrived: {@code GolfRounds.begin}. */
        LiveRound arrive(UUID id) {
            LiveRound r = new LiveRound(id, course, new GolfRun(course.pars(), MAX_OVER_PAR), null);
            rounds.put(id, r);
            r.group = groups.joins(id, trips.get(id));
            return r;
        }

        /** A round alone starts (the course screen's Start): {@code GolfRounds.begin} with no group. */
        LiveRound alone(UUID id) {
            sessions.add(id);
            LiveRound r = new LiveRound(id, course, new GolfRun(course.pars(), MAX_OVER_PAR), null);
            rounds.put(id, r);
            r.group = groups.joins(id, null);
            return r;
        }

        /** The entry was called off on arrival: the session core drops it and calls no hook. */
        void neverArrives(UUID id) {
            sessions.remove(id);
        }

        /** In the cup after {@code strokes} putts: {@code GolfRounds.inCup}, then {@code afterHole}. */
        void holeIn(UUID id, int strokes) {
            LiveRound r = rounds.get(id);
            for (int i = 0; i < strokes; i++) {
                r.run.stroke();
            }
            r.last = r.run.inCup();
            groups.holeDone(id, r);
        }

        /** The session ended (Leave game, a trip home, an admin): {@code GolfRounds.ended}. */
        void ended(UUID id) {
            sessions.remove(id);
            groups.leave(id, rounds.remove(id));
        }

        /** A disconnect: {@code GolfRounds.quit}, then the session's end ({@code ended} with no round). */
        void quit(UUID id) {
            groups.leave(id, rounds.remove(id));
            sessions.remove(id);
            groups.leave(id, rounds.remove(id));
        }

        void seconds(int n) {
            for (int i = 0; i < n; i++) {
                groups.second();
            }
        }

        void runTasks() {
            while (!tasks.isEmpty()) {
                List<Runnable> due = new ArrayList<>(tasks);
                tasks.clear();
                due.forEach(Runnable::run);
            }
        }

        String heard(UUID id) {
            return String.join("\n", told.getOrDefault(id, List.of()));
        }

        int records(UUID id) {
            return recorded.getOrDefault(id, List.of()).size();
        }

        List<String> allLines() {
            List<String> out = new ArrayList<>();
            told.values().forEach(out::addAll);
            return out;
        }

        // ---- the port ---------------------------------------------------------------------------

        @Override
        public LiveRound round(UUID player) {
            return rounds.get(player);
        }

        @Override
        public boolean inSession(UUID player) {
            return sessions.contains(player);
        }

        @Override
        public boolean enter(UUID player, GolfGroup group) {
            if (cantGo.contains(player)) {
                tell(player, "&cYou can't start a game right now.");
                return false;
            }
            sessions.add(player);
            trips.put(player, group);
            return true;
        }

        @Override
        public void tell(UUID player, String line) {
            told.computeIfAbsent(player, k -> new ArrayList<>()).add(line);
        }

        @Override
        public void showCard(UUID player, GolfGroup.Card card) {
            assertNotNull(card, "a card to show");
        }

        @Override
        public void nextTee(UUID player, LiveRound round) {
            round.state = LiveRound.State.PLAYING; // GolfRounds.startHole
            tees.merge(player, 1, Integer::sum);
        }

        @Override
        public void finished(UUID player, LiveRound round) {
            assertTrue(round.run.finished(), "only a round with every hole played is recorded");
            recorded.computeIfAbsent(player, k -> new ArrayList<>()).add(day);
        }

        @Override
        public void pickedUp(UUID player, LiveRound round) {
            pickedUp.merge(player, 1, Integer::sum);
        }

        @Override
        public void home(UUID player, LiveRound round, GolfGroup.Card card) {
            assertTrue(card.over(), "the card they see at home is the final one");
            home.merge(player, 1, Integer::sum);
            ended(player); // a same-tick trip home ends the session inside the call
        }

        @Override
        public void later(long ticks, Runnable task) {
            tasks.add(task);
        }

        @Override
        public void roundOver(long partyId) {
            partiesOpened.add(partyId);
        }

        @Override
        public String name(UUID player) {
            return NAMES.get(player);
        }
    }

    /** Everyone listed starts and arrives. */
    private static Bench playing(int holes, UUID... who) {
        Bench b = new Bench(holes);
        assertTrue(b.start(who), "the group starts");
        for (UUID id : who) {
            assertSame(b.group(), b.arrive(id).group, "each arrival plays in the group");
        }
        return b;
    }

    // ---- starting and moving on together ----------------------------------------------------------

    @Test
    void everyoneGoesToHoleOneAndMovesToEachNextTeeTogether() {
        Bench b = playing(2, SAM, AVA);
        GolfGroup g = b.group();
        b.holeIn(SAM, 2);
        assertTrue(b.heard(SAM).contains("Waiting for Ava to finish the hole..."), "Sam waits for Ava");
        assertTrue(b.heard(AVA).contains("&dSam &7finished hole 1."), "Ava hears Sam is in");
        assertTrue(b.tasks.isEmpty(), "nobody moves on while Ava's ball is out");
        b.holeIn(AVA, 3);
        assertTrue(b.heard(SAM).contains("&dEveryone's done! &7Next tee in a few seconds."), "the hole is over");
        b.runTasks();
        assertEquals(1, g.hole(), "hole 2, for everyone");
        assertEquals(1, b.tees.get(SAM), "Sam to the next tee");
        assertEquals(1, b.tees.get(AVA), "Ava to the next tee");
        assertEquals(LiveRound.State.PLAYING, b.rounds.get(SAM).state, "playing again");
        assertEquals(0, b.records(SAM) + b.records(AVA), "nothing is recorded before a round's last hole");
    }

    @Test
    void aPlayerWhoCantGoIsNotInTheGroupAndTheRestPlay() {
        Bench b = new Bench(2);
        b.cantGo.add(AVA);
        assertTrue(b.start(SAM, AVA), "Sam goes");
        GolfGroup g = b.group();
        assertEquals(GolfGroup.Seat.LEFT, g.seat(AVA), "Ava isn't waited for");
        assertNull(b.groups.of(AVA), "and isn't listed with the group");
        assertTrue(b.heard(SAM).contains("&7Ava didn't make it to the course - the rest of you carry on."),
                "Sam is told plainly");
        assertTrue(b.partiesOpened.isEmpty(), "the round goes on");
    }

    @Test
    void aGroupNobodyCanStartIsOverAndItsPartyOpensAgain() {
        Bench b = new Bench(2);
        b.cantGo.add(SAM);
        b.cantGo.add(AVA);
        assertFalse(b.start(SAM, AVA), "nobody went");
        assertEquals(List.of(PARTY), b.partiesOpened, "the party opens again, once");
        assertNull(b.groups.of(SAM), "nobody is listed in a group");
    }

    // ---- R2 review #4: an arrival that never happens ------------------------------------------------

    @Test
    void anArrivalThatNeverHappensStopsHoldingTheGroupUpWithinASecond() {
        Bench b = new Bench(2);
        assertTrue(b.start(SAM, AVA), "both set off");
        GolfGroup g = b.group();
        b.arrive(SAM);
        b.holeIn(SAM, 2);
        b.seconds(1);
        assertEquals(GolfGroup.Seat.PLAYING, g.seat(AVA), "Ava is still on her way: she is waited for");
        b.neverArrives(AVA);
        b.seconds(1);
        assertEquals(GolfGroup.Seat.LEFT, g.seat(AVA), "no round and no session: Ava leaves the group");
        assertTrue(b.heard(SAM).contains("&7Ava didn't make it to the course - the rest of you carry on."),
                "Sam is told plainly");
        assertTrue(b.heard(AVA).contains("&7Your golf group carries on without you."), "and so is Ava");
        b.runTasks();
        assertEquals(1, g.hole(), "Sam was the last one waiting: on to hole 2");
        assertEquals(1, b.tees.get(SAM), "Sam at the next tee");
    }

    @Test
    void aGroupWhoseTripsAllFailIsOverAndItsPartyOpensAgain() {
        Bench b = new Bench(2);
        assertTrue(b.start(SAM, AVA), "both set off");
        b.neverArrives(SAM);
        b.neverArrives(AVA);
        b.seconds(1);
        assertEquals(List.of(PARTY), b.partiesOpened, "the party is not stuck mid-round: it opens again");
        assertNull(b.groups.of(SAM), "nobody is listed in a group");
        assertNull(b.groups.of(AVA), "nobody is listed in a group");
    }

    @Test
    void aTripStuckOnTheWayIsLetGoWhenTheHoleClockRunsOut() {
        Bench b = new Bench(2);
        assertTrue(b.start(SAM, AVA), "both set off");
        GolfGroup g = b.group();
        b.arrive(SAM);
        b.holeIn(SAM, 2);
        b.seconds(GolfGroup.HOLE_CLOCK_SECONDS - 1);
        assertEquals(GolfGroup.Seat.PLAYING, g.seat(AVA), "still on the way, with time left");
        b.seconds(1);
        assertEquals(GolfGroup.Seat.LEFT, g.seat(AVA), "the clock ran out with no round to pick up: out");
        b.runTasks();
        assertEquals(1, b.tees.get(SAM), "Sam plays on");
        assertNull(b.arrive(AVA).group, "a trip that arrives after all is a round alone, not the group's");
    }

    @Test
    void aRoundAloneLeavesAnyOldGroupFirst() {
        Bench b = new Bench(2);
        assertTrue(b.start(SAM, AVA), "both set off");
        GolfGroup g = b.group();
        b.arrive(SAM);
        b.neverArrives(AVA);
        LiveRound solo = b.alone(AVA); // before the next second's check
        assertNull(solo.group, "Ava's new round is her own");
        assertEquals(GolfGroup.Seat.LEFT, g.seat(AVA), "and she is out of the old group, so it never waits for her");
        assertTrue(b.heard(SAM).contains("&7Ava left the round - the rest of you carry on."), "Sam is told");
    }

    @Test
    void aRoundAlonesEndNeverDropsAGroupEntryWithoutLeavingTheGroup() {
        Bench b = new Bench(2);
        assertTrue(b.start(SAM, AVA), "both set off");
        GolfGroup g = b.group();
        b.arrive(SAM);
        LiveRound solo = new LiveRound(AVA, b.course, new GolfRun(b.course.pars(), MAX_OVER_PAR), null);
        solo.state = LiveRound.State.DONE; // a finished round alone, while still listed in the group
        b.groups.leave(AVA, solo);
        assertEquals(GolfGroup.Seat.LEFT, g.seat(AVA), "the group is told she is gone, never silently dropped");
        b.holeIn(SAM, 2);
        assertFalse(b.tasks.isEmpty(), "so Sam's hole ends and the group moves on");
    }

    // ---- R2 review #5: each round is recorded at its own last hole, once -----------------------------

    @Test
    void eachRoundIsRecordedAtItsOwnLastHoleOnTheDayItWasPlayedAndOnlyOnce() {
        Bench b = playing(2, SAM, AVA);
        b.holeIn(SAM, 2);
        b.holeIn(AVA, 3);
        b.runTasks();
        b.holeIn(SAM, 3);
        assertEquals(List.of(1L), b.recorded.get(SAM), "Sam's round is recorded at his last hole, today");
        assertEquals(LiveRound.State.DONE, b.rounds.get(SAM).state, "his round is done");
        assertEquals(0, b.home.getOrDefault(SAM, 0), "he stays with the group for the shared card");
        b.day = 2; // Ava takes past midnight
        b.holeIn(AVA, 4);
        assertEquals(List.of(2L), b.recorded.get(AVA), "Ava's on the day she finished");
        b.runTasks();
        assertEquals(1, b.home.get(SAM), "the group's end sends Sam home once");
        assertEquals(1, b.home.get(AVA), "and Ava");
        assertEquals(List.of(1L), b.recorded.get(SAM), "Sam's round still recorded once, on day 1");
        assertEquals(1, b.records(AVA), "Ava's once");
        assertEquals(List.of(PARTY), b.partiesOpened, "the party opens again once");
        assertTrue(b.heard(AVA).contains("&dGolf together &7- &f1st Sam &75&7, &f2nd Ava &77"),
                "the ranking, before the trip home: " + b.heard(AVA));
    }

    @Test
    void leavingAfterTheLastHoleKeepsTheRoundAndTheOthersCarryOn() {
        Bench b = playing(1, SAM, AVA);
        GolfGroup g = b.group();
        b.holeIn(SAM, 2);
        assertEquals(1, b.records(SAM), "recorded at his last hole");
        b.ended(SAM); // Leave game while Ava finishes
        assertEquals(1, b.records(SAM), "leaving loses nothing and records nothing twice");
        assertEquals(GolfGroup.Seat.WAITING, g.seat(SAM), "his row stays as played, not 'left'");
        assertFalse(b.heard(AVA).contains("Sam left the round"), "Ava isn't told he left: he finished");
        b.holeIn(AVA, 3);
        b.runTasks();
        assertEquals(0, b.home.getOrDefault(SAM, 0), "Sam is home already: never sent twice");
        assertEquals(1, b.home.get(AVA), "Ava goes home");
        assertEquals(1, b.records(SAM), "Sam once");
        assertEquals(1, b.records(AVA), "Ava once");
        assertEquals(1, g.ranking().get(0).place(), "Sam is ranked with the finishers");
        assertEquals(SAM, g.ranking().get(0).player(), "first, with the fewest strokes");
        assertEquals(List.of(PARTY), b.partiesOpened, "the party opens again once");
    }

    @Test
    void aDisconnectWhileWaitingLosesNothing() {
        Bench b = playing(1, SAM, AVA, LEE);
        GolfGroup g = b.group();
        b.holeIn(SAM, 2);
        b.quit(SAM);
        assertEquals(1, b.records(SAM), "recorded once, before he went");
        assertEquals(GolfGroup.Seat.WAITING, g.seat(SAM), "still on the card as played");
        b.holeIn(AVA, 3);
        b.ended(LEE); // Lee leaves mid-hole: he was the last one out
        assertEquals(GolfGroup.Seat.LEFT, g.seat(LEE), "Lee left");
        assertEquals(0, b.records(LEE), "a round left early records nothing");
        assertTrue(b.heard(AVA).contains("&7Lee left the round - the rest of you carry on."), "Ava is told");
        b.runTasks();
        assertEquals(1, b.home.get(AVA), "the round is over: Ava goes home");
        assertEquals(1, b.records(SAM), "Sam once, however he went");
        assertEquals(1, b.records(AVA), "Ava once");
        assertEquals(List.of(PARTY), b.partiesOpened, "the party opens again");
    }

    @Test
    void leavingMidRoundAsTheLastOneOutEndsTheHoleForTheOthers() {
        Bench b = playing(2, SAM, AVA, LEE);
        GolfGroup g = b.group();
        b.holeIn(SAM, 2);
        b.holeIn(AVA, 3);
        b.ended(LEE);
        b.runTasks();
        assertEquals(1, g.hole(), "Sam and Ava move on without waiting");
        assertEquals(List.of(SAM, AVA), g.active(), "two still in");
        assertEquals(0, b.records(LEE), "nothing recorded for Lee");
    }

    // ---- R2 review #6: the hole clock ---------------------------------------------------------------

    @Test
    void theHoleClockPicksUpABallStillOutAtThePickUpScore() {
        Bench b = playing(2, SAM, AVA);
        GolfGroup g = b.group();
        LiveRound ava = b.rounds.get(AVA);
        ava.run.stroke();
        b.holeIn(SAM, 2);
        assertTrue(b.heard(AVA).contains(GolfGroups.clockLine(GolfGroup.HOLE_CLOCK_SECONDS)),
                "Ava hears the hole clock started: " + b.heard(AVA));
        assertFalse(b.heard(SAM).contains("Hole clock"), "Sam is in: the clock isn't his");
        ava.ball.putt(1, 0, 0.3); // on the move when time runs out
        b.seconds(GolfGroup.HOLE_CLOCK_SECONDS - 1);
        assertEquals(GolfGroup.Seat.PLAYING, g.seat(AVA), "one second left: Ava still plays");
        b.seconds(1);
        assertEquals(new GolfRun.HoleScore(3, 3 + MAX_OVER_PAR, true), ava.last,
                "picked up at par + max over par, as if the strokes had reached it");
        assertFalse(ava.ball.moving(), "the ball stops where it is");
        assertEquals(1, b.pickedUp.get(AVA), "Ava sees it was picked up");
        assertTrue(b.heard(AVA).contains("&eTime's up on this hole &7- your ball is picked up."), "and why");
        b.runTasks();
        assertEquals(1, g.hole(), "everyone moves on");
        assertEquals(-1, g.clock(), "with no clock on the new hole");
        assertEquals(0, b.records(AVA), "hole 1 of 2: nothing recorded yet");
    }

    @Test
    void aGolfV4CoursesGroupGetsEachHolesOwnClock() {
        // GOLF-V4-SPEC §6.4: a par-5 hole of a v4 layout gives max(120, 30 x 6) = 180 seconds
        GolfCourse plain = course(2);
        List<GolfCourse.Hole> holes = new ArrayList<>();
        for (GolfCourse.Hole h : plain.holes()) {
            holes.add(new GolfCourse.Hole(h.tee(), h.cup(), 5, h.corner1(), h.corner2()));
        }
        com.dierks.homecraft.games.gen.api.GenTag tag = new com.dierks.homecraft.games.gen.api.GenTag("fresh_golf",
                com.dierks.homecraft.games.gen.api.Slots.GOLF, 4, 20725, 0, 1, 'A', "", 0, 0, 0, List.of(), List.of(), 0);
        Bench b = new Bench(new GolfCourse("fresh_golf", "Golf of the Week", "games", true, 1, holes, tag));
        assertTrue(b.start(SAM, AVA), "the group starts");
        b.arrive(SAM);
        b.arrive(AVA);
        b.holeIn(SAM, 4);
        assertEquals(180, b.group().clock(), "the first ball in starts the v4 hole's own clock");
        assertTrue(b.heard(AVA).contains(GolfGroups.clockLine(180)), "Ava hears it: " + b.heard(AVA));
    }

    @Test
    void theHoleClockOnTheLastHoleFinishesTheSlowRoundOnce() {
        Bench b = playing(1, SAM, AVA);
        b.holeIn(SAM, 1);
        b.seconds(GolfGroup.HOLE_CLOCK_SECONDS);
        assertEquals(1, b.records(AVA), "Ava's picked-up last hole finishes her round: recorded");
        b.runTasks();
        b.seconds(GolfGroup.HOLE_CLOCK_SECONDS);
        assertEquals(1, b.records(SAM), "Sam once");
        assertEquals(1, b.records(AVA), "Ava once");
        assertEquals(1, b.home.get(AVA), "home once");
        assertEquals(List.of(PARTY), b.partiesOpened, "the party opens again");
    }

    // ---- copy -------------------------------------------------------------------------------------

    @Test
    void everyLineIsKidSafeAndDrawable() {
        Bench b = playing(2, SAM, AVA, LEE);
        b.holeIn(SAM, 2);
        b.seconds(GolfGroup.HOLE_CLOCK_SECONDS);
        b.runTasks();
        b.ended(LEE);
        b.holeIn(SAM, 2);
        b.holeIn(AVA, 2);
        b.runTasks();
        Bench missed = new Bench(1);
        missed.start(SAM, AVA);
        missed.neverArrives(AVA);
        missed.seconds(1);
        List<String> lines = new ArrayList<>(b.allLines());
        lines.addAll(missed.allLines());
        assertTrue(lines.size() > 10, "a busy round says plenty: " + lines);
        for (String l : lines) {
            assertEquals(List.of(), GenCopy.copyProblems(l), l);
            assertFalse(l.toLowerCase().matches(".*\\b(bet|wager|gamble|casino|lucky|almost|so close|sink)\\b.*"),
                    "never: " + l);
        }
    }
}
