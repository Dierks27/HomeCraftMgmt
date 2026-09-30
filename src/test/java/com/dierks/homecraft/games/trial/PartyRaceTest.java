package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.EndReason;
import com.dierks.homecraft.games.Invites;
import com.dierks.homecraft.util.Text;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Party races (owner decision D4: "so it's not always lonely"), run on race mode: everyone starts on
 * the same tick after an optional shared warm-up; live positions and the finish order; leaving or
 * dropping out is a DNF and the others carry on; each racer's run is also a normal counted run,
 * exactly once; and nothing pays extra for being in a party.
 */
class PartyRaceTest {

    private static final UUID SAM = new UUID(0, 1);
    private static final UUID AVA = new UUID(0, 2);
    private static final UUID LEE = new UUID(0, 3);

    /** Laps' two-lap loop: 6 checkpoints a lap, 13 targets. */
    private static final Course LOOP = LapsTest.loop(6, 2);

    private final AtomicLong tick = new AtomicLong(1_000);
    private final List<String> said = new ArrayList<>();

    private PartyRace race(int warmupSeconds) {
        return new PartyRace(7, LOOP, List.of(new PartyRace.Racer(SAM, "Sam"), new PartyRace.Racer(AVA, "Ava"),
                new PartyRace.Racer(LEE, "Lee")), warmupSeconds, tick::get, said::add);
    }

    private static void seatAll(PartyRace r) {
        r.seated(SAM, new Course.Spot(1, 65, -4, 0, 0));
        r.seated(AVA, new Course.Spot(-2, 65, -4, 0, 0));
        r.seated(LEE, new Course.Spot(1, 65, -8, 0, 0));
    }

    private List<String> plainSaid() {
        return said.stream().map(Text::plain).toList();
    }

    @Test
    void everyoneStartsOnTheSameTickOnOneClock() {
        PartyRace r = race(0);
        assertEquals(Long.MAX_VALUE, r.goTick(), "nobody goes while they are being seated");
        assertEquals(0, r.warmupUntil(), "no warm-up chosen");
        seatAll(r);
        assertTrue(r.seatingDone(), "three seated: the race is on");
        assertEquals(PartyRace.State.GRID, r.state(), "straight onto the grid");
        assertEquals(tick.get() + PartyRace.GO_DELAY, r.goTick(), "one go tick for the whole group, 5 s away");

        RaceRun.Clock clock = new RaceRun.Clock();
        AtomicLong nanos = new AtomicLong();
        List<RaceRun> racers = new ArrayList<>();
        for (UUID id : r.racers()) {
            racers.add(new RaceRun(r, LOOP, r.grid(id), null, false));
        }
        long go = r.goTick();
        for (RaceRun rr : racers) {
            assertTrue(rr.release(go - 1, true, clock, () -> nanos.addAndGet(10)).hold(), "everyone held a tick before");
        }
        long start = racers.get(0).release(go, true, clock, () -> nanos.addAndGet(10)).nanos();
        for (RaceRun rr : racers) {
            RaceRun.Release rel = rr.release(go, true, clock, () -> nanos.addAndGet(10));
            assertTrue(rel.go(), "everyone released on the go tick");
            assertEquals(start, rel.nanos(), "on the same clock");
        }
        tick.set(go);
        assertEquals(PartyRace.Step.NONE, r.tick(go), "the go tick: racing");
        assertEquals(PartyRace.State.RACING, r.state(), "racing");
    }

    @Test
    void theSharedWarmUpEndsWhenItsTimeIsUpOrEveryoneIsReady() {
        PartyRace r = race(180);
        long until = r.warmupUntil();
        assertEquals(tick.get() + PartyRace.seatingTicks(3) + 180 * 20, until,
                "three minutes, counted from after the seating so the last one in gets it all");
        seatAll(r);
        r.ready(SAM); // tapped while the others were still being seated: it counts
        assertTrue(r.seatingDone(), "on");
        assertEquals(PartyRace.State.WARMUP, r.state(), "free laps first");
        assertEquals(Long.MAX_VALUE, r.goTick(), "no Go during the warm-up");
        assertEquals(until, r.warmupUntil(), "race mode seats racers warming up from the course's start");
        assertEquals(PartyRace.Step.NONE, r.tick(tick.get() + 100), "the warm-up goes on");
        r.ready(AVA);
        assertEquals(PartyRace.Step.NONE, r.tick(tick.get() + 200), "two of three ready: still warming up");
        r.ready(LEE);
        long now = tick.get() + 300;
        assertEquals(PartyRace.Step.TO_GRID, r.tick(now), "everyone ready: to the grid early");
        assertEquals(now + PartyRace.GRID_SETTLE + PartyRace.GO_DELAY, r.goTick(), "then one 3-2-1 for all");
        assertEquals(0, r.warmupUntil(), "the warm-up is over");
        assertTrue(plainSaid().contains("Lee is ready. (3 of 3)"), "the group hears who's ready: " + plainSaid());

        PartyRace timed = race(60);
        seatAll(timed);
        timed.seatingDone();
        assertEquals(PartyRace.Step.NONE, timed.tick(timed.warmupUntil() - 1), "a tick before time");
        assertEquals(PartyRace.Step.TO_GRID, timed.tick(timed.warmupUntil()), "time's up: to the grid");
    }

    @Test
    void positionsShowTheOrderAndTheLap() {
        PartyRace r = race(0);
        seatAll(r);
        r.seatingDone();
        r.tick(r.goTick());
        r.progress(SAM, 3, 10.0, 500);
        r.progress(AVA, 7, 4.0, 700);
        r.progress(LEE, 3, 2.0, 600);
        assertEquals("1st of 3 · Lap 2/2", Text.plain(r.bar(AVA)), "Ava is furthest round, on her last lap");
        assertEquals("2nd of 3 · Lap 1/2", Text.plain(r.bar(LEE)), "Lee is level with Sam on targets but nearer the next");
        assertEquals("3rd of 3 · Lap 1/2", Text.plain(r.bar(SAM)), "Sam third");
        assertEquals(2, r.lap(AVA), "Ava's lap");
        assertEquals(2, r.laps(), "a two-lap race");
        assertEquals(3f / 13f, r.share(SAM), 1e-6f, "the bar fills by the share of the targets reached");

        r.finished(LEE, 61_400, true, null);
        assertEquals("1st of 3 · Lap 2/2", Text.plain(r.bar(LEE)), "over the line first: first, whatever the others do");
        r.finished(AVA, 61_500, true, null);
        assertTrue(plainSaid().contains("Photo finish! Lee by 0.10 s"), "two within 0.2 s: " + plainSaid());
        assertTrue(plainSaid().contains("Ava came 2nd! 1:01.5"), "each finish is told to the group: " + plainSaid());
    }

    @Test
    void aDnfIsLastAndTheOthersCarryOn() {
        PartyRace r = race(0);
        seatAll(r);
        r.seatingDone();
        r.tick(r.goTick());
        r.left(AVA, EndReason.DISCONNECT);
        assertTrue(plainSaid().contains("Ava left the race."), "the group hears it");
        assertFalse(r.racing(AVA), "Ava is out");
        assertNull(r.bar(AVA), "and has no position bar");
        assertEquals(PartyRace.Step.NONE, r.tick(r.goTick() + 20), "the others carry on");
        assertTrue(r.alive(), "the race is still on");
        r.finished(SAM, 40_000, true, null);
        r.finished(AVA, 39_000, true, null); // a line crossed after leaving never counts
        assertEquals(PartyRace.Step.NONE, r.tick(r.goTick() + 40), "Lee is still racing");
        r.left(LEE, EndReason.COMMAND);
        assertEquals(PartyRace.Step.END, r.tick(r.goTick() + 60), "nobody left racing: the race ends");
        List<PartyRace.Line> results = r.results();
        assertEquals(List.of(SAM, AVA, LEE), results.stream().map(PartyRace.Line::id).toList(),
                "the finisher first, then those who left (in grid order)");
        assertEquals(new PartyRace.Line(1, SAM, "Sam", 40_000, PartyRace.Result.FINISHED), results.get(0), "Sam 1st");
        assertEquals(PartyRace.Result.LEFT, results.get(1).result(), "Ava left");
        assertEquals(0, results.get(1).rank(), "no place for leaving");
        assertEquals("Ava - left", Text.plain(PartyRace.chatLine(results.get(1))), "in the results");
        assertFalse(r.alive(), "over");
    }

    @Test
    void aRaceEndsWhenAllAreInOrTheWindowAfterTheFirstFinisherCloses() {
        PartyRace all = race(0);
        seatAll(all);
        all.seatingDone();
        long go = all.goTick();
        all.tick(go);
        all.finished(SAM, 50_000, true, null);
        all.finished(AVA, 51_000, false, "flying");
        assertEquals(PartyRace.Step.NONE, all.tick(go + 100), "Lee still racing");
        all.finished(LEE, 55_000, true, null);
        assertEquals(PartyRace.Step.END, all.tick(go + 120), "everyone in, finished or not counted");
        assertEquals(List.of(PartyRace.Result.FINISHED, PartyRace.Result.FINISHED, PartyRace.Result.NOT_COUNTED),
                all.results().stream().map(PartyRace.Line::result).toList(), "a race that didn't count after the finishers");

        PartyRace window = race(0);
        seatAll(window);
        window.seatingDone();
        long g = window.goTick();
        window.tick(g);
        tick.set(g + 400);
        window.finished(AVA, 20_000, true, null);
        assertEquals(PartyRace.Step.NONE, window.tick(g + 400 + PartyRace.FINISH_WINDOW_SECONDS * 20L - 1),
                "the others have 2 minutes after the first finish");
        assertEquals(PartyRace.Step.END, window.tick(g + 400 + PartyRace.FINISH_WINDOW_SECONDS * 20L), "then it ends");
        assertEquals(PartyRace.Result.STILL_RACING, window.results().get(1).result(), "still racing, never a loss");
        assertEquals("Sam - still racing", Text.plain(PartyRace.chatLine(window.results().get(1))), "said kindly");

        PartyRace longest = race(0);
        seatAll(longest);
        longest.seatingDone();
        long lg = longest.goTick();
        longest.tick(lg);
        assertEquals(PartyRace.Step.END, longest.tick(lg + PartyRace.MAX_RACE_MINUTES * 60L * 20L),
                "and no race runs past 10 minutes");
    }

    @Test
    void aRaceNeedsTwoSeated() {
        PartyRace r = race(0);
        r.seated(SAM, new Course.Spot(1, 65, -4, 0, 0));
        r.notSeated(AVA);
        r.notSeated(LEE);
        assertFalse(r.seatingDone(), "one racer is no race");
        assertFalse(r.alive(), "it's off");
        assertEquals(PartyRace.Result.NOT_STARTED, r.results().get(2).result(), "those who couldn't come sat it out");
    }

    @Test
    void eachRacersRunIsAlsoANormalCountedRunExactlyOnce() {
        PartyRace r = race(0);
        assertTrue(r.normalRun(), "a party race's finish is also the course's normal run");
        assertEquals("The party race is over.", Text.plain(r.calledOffLine()), "its own words when it ends");
        RaceRun rr = new RaceRun(r, LOOP, LOOP.start(), null, false);
        rr.started();
        RaceRun.Line line = rr.line(true);
        assertTrue(line.normal(), "counted once through the normal finish: boards, first finish, the Cup");
        assertFalse(line.e4(), "the normal finish tells the quests itself");
        assertFalse(rr.line(true).report(), "never twice");
    }

    @Test
    void nothingPaysExtraForBeingInAParty() throws IOException {
        for (String file : List.of("games/trial/PartyRace.java", "games/trial/PartyRaces.java",
                "gui/games/trial/PartyMenu.java", "gui/games/trial/PartyResultsMenu.java")) {
            String code = Files.readString(Path.of("src/main/java/com/dierks/homecraft/" + file));
            for (String pays : List.of("rewards()", "TokenService", ".pay(", "payWhole", "tokens()", "SkillRewards",
                    "RewardKind", "canStake")) {
                assertFalse(code.contains(pays), file + " never moves a token (" + pays + "): party races are free and"
                        + " pay only what the normal run pays");
            }
        }
    }

    @Test
    void partyInvitesFollowThePlayersInviteSwitch() {
        assertTrue(Invites.FRIEND_GAMES.contains(TimeTrials.SPEC.id()),
                "/hcm play invites off turns off party-race invites too; its cooldown and switches stay");
        assertEquals(8, TimeTrialsSettings.defaults().partyMax(), "games.trials.party_max ships at 8");
    }

    // ---- the fixes (EV fix stage, R1 review) --------------------------------------------------------

    @Test
    void aRestartDueSoonEndsTheSharedWarmUpAtOnce() {
        PartyRace r = race(180);
        seatAll(r);
        assertTrue(r.seatingDone(), "on");
        assertEquals(PartyRace.State.WARMUP, r.state(), "warming up for 3 minutes");
        assertEquals(PartyRace.Step.NONE, r.tick(tick.get() + 100, false), "no restart soon: the warm-up goes on");
        assertEquals(PartyRace.Step.TO_GRID, r.tick(tick.get() + 120, true),
                "the restart hold: everyone to the grid now, so the race isn't eaten by free laps");
    }

    @Test
    void aRacerWhoHasntReportedYetIsBehindThoseWhoHave() {
        PartyRace r = race(0);
        seatAll(r);
        r.seatingDone();
        r.tick(r.goTick());
        r.progress(SAM, 0, 12.5, 100);
        r.progress(AVA, 0, 30.0, 100); // Lee's first report hasn't come yet
        List<RaceStandings.Place> live = r.standings();
        assertEquals(List.of(SAM, AVA, LEE), live.stream().map(p -> p.row().racer()).toList(),
                "no report is no lead: Lee is behind the two who reported on the same target count");
    }

    @Test
    void aBarIsShownOnlyWhileTheRacersRunIsOnThisRace() {
        PartyRace r = race(0);
        seatAll(r);
        r.seatingDone();
        r.tick(r.goTick());
        TrialRun racing = new TrialRun(SAM, LOOP, false, 0);
        racing.race = new RaceRun(r, LOOP, LOOP.start(), null, false);
        assertTrue(PartyRaces.barFor(r, racing, SAM).contains("of 3"), "racing: the place bar");
        r.finished(SAM, 40_000, true, null);
        assertTrue(PartyRaces.barFor(r, racing, SAM) != null, "finished and still on the stand: the bar stays");
        racing.race.ended = true;
        assertNull(PartyRaces.barFor(r, racing, SAM), "on the way home: no bar");
        assertNull(PartyRaces.barFor(r, null, SAM), "home: no bar");
        assertNull(PartyRaces.barFor(r, new TrialRun(SAM, LOOP, false, 0), SAM), "and never over a solo run of their own");
    }

    @Test
    void theDropperHasNoPartyRaces() {
        assertFalse(PartyRaces.offered(TrialKind.DROPPER), "the Dropper's only extra is its practice drop");
        Course dropper = new Course("drop", TrialKind.DROPPER, "Drop", Tier.EASY, "games", LOOP.start(), List.of(),
                LOOP.finish(), null, 30, true, false, 1);
        assertEquals(PartyRaces.NO_DROPPER, PartyRaces.courseProblem(dropper), "so a party on it is refused, with why");
        assertNull(PartyRaces.courseProblem(LOOP), "a boat course has party races");
        assertNull(PartyRaces.courseProblem(LapsTest.straight()), "and so does parkour");
        assertEquals("That course is closed right now.", PartyRaces.courseProblem(null), "a closed course says so");
    }
}
