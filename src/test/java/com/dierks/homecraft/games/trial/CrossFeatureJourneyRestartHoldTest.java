package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.EndReason;
import com.dierks.homecraft.games.GamesBench;
import com.dierks.homecraft.games.NoPush;
import com.dierks.homecraft.games.Refusal;
import com.dierks.homecraft.games.Scores;
import com.dierks.homecraft.games.clubhouse.ClubBench;
import com.dierks.homecraft.games.clubhouse.ClubVisits;
import com.dierks.homecraft.games.clubhouse.Clubhouse;
import com.dierks.homecraft.games.clubhouse.ClubhouseSettings;
import com.dierks.homecraft.games.clubhouse.LiveRace;
import com.dierks.homecraft.games.cup.live.CupSettings;
import com.dierks.homecraft.games.cup.live.WeeklyCup;
import com.dierks.homecraft.games.golf.GolfBench;
import com.dierks.homecraft.games.golf.MiniGolf;
import com.dierks.homecraft.games.golf.MiniGolfSettings;
import com.dierks.homecraft.games.world.SessionBench;
import com.dierks.homecraft.games.world.WorldEntities;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Journey 4: the restart hold (a restart at 4:00 PM, held from 3:55) starts while people are in the
 * Clubhouse, watching, riding, mid-party-race and mid-golf-group (the real framework's hold, the
 * Clubhouse's visits, race mode, golf together, ride along and world sessions; the Clubhouse's second
 * loop and its trip home are mirrored).
 *
 * <p>Pinned: every new start is refused with the restart's time and takes or moves nothing; a warm-up
 * ends so the counted run still happens; what was running finishes and counts (a party race that went
 * before the hold, a golf group on its last hole), and its players come back to the Clubhouse; everyone in
 * the Clubhouse is warned once and sent home a minute later with their things; by 3:59:59 nobody is in a
 * game, so the restart restores nobody in place. The last minute's edge is pinned on its own.
 */
class CrossFeatureJourneyRestartHoldTest {

    private static final long T0 = GamesBench.at(2026, 9, 29, 15, 45);
    private static final long SEC = 1_000L;
    private static final Course LAVA = new Course("lava_leap", TrialKind.PARKOUR, "Lava Leap", Tier.EASY, "games",
            new Course.Spot(0, 64, 0, 0, 0), List.of(new Course.Mark(10, 64, 0, 1.5)), new Course.Mark(20, 64, 0, 1.5),
            null, null, true, false, 1);

    private JourneyBench j;
    private GolfBench golf;
    private Course loop;
    private Course lava;
    private final Map<UUID, Long> warned = new HashMap<>();
    private final Map<UUID, Long> sentHome = new HashMap<>();
    private final Map<UUID, Integer> warnings = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        j = new JourneyBench(T0, List.of(TimeTrials.SPEC, WeeklyCup.SPEC, MiniGolf.SPEC, Clubhouse.SPEC), "trials",
                TimeTrialsSettings.defaults(), "cup", CupSettings.defaults(), "golf", MiniGolfSettings.defaults(),
                "clubhouse", ClubhouseSettings.defaults());
        j.bench.restarts(List.of(LocalTime.of(16, 0)), 5);
        loop = j.loop();
        lava = j.course(LAVA);
        j.cup.desk().dao().choose(loop.id(), true);
        golf = new GolfBench(j.games, j.rail, j.club, j.players::get);
        GolfBench.save(j.games, GolfBench.course("meadow", "Meadow Links", 3));
    }

    @AfterEach
    void tearDown() throws Exception {
        j.close();
    }

    private static UUID id(Player p) {
        return p.getUniqueId();
    }

    private void at(int hour, int minute, int second) {
        long t = GamesBench.at(2026, 9, 29, hour, minute) + second * SEC;
        if (t > j.bench.now()) {
            j.bench.move(t - j.bench.now());
        }
    }

    private boolean racing(Player p) {
        TrialRun r = j.trials.run(id(p));
        return r != null && r.race != null && !r.warmup && r.phase == TrialRun.Phase.RUNNING && r.progress != null;
    }

    /** A copy of Clubhouse.second: the live list, Watch live, then the visits' timeouts and the hold. */
    private void clubSecond() {
        j.club.refresh(ClubRaces.live(j.games));
        j.club.second();
        long now = j.bench.now();
        for (ClubVisits.Act a : j.club.visitsSecond()) {
            switch (a.what()) {
                case WARN_HOLD -> {
                    warned.putIfAbsent(a.player(), now);
                    warnings.merge(a.player(), 1, Integer::sum);
                }
                case HOME_HOLD -> sentHome.put(a.player(), now);
                default -> {
                    // no idle timeouts in these few minutes
                }
            }
        }
        j.riders.second();
        golf.second();
    }

    /** {@code TimeTrials.finish} for a solo run; only its scheduled result screen (GamesService.later) needs a server. */
    private void soloCross(Player p, long ms) {
        try {
            j.race.cross(id(p), ms);
        } catch (NullPointerException e) {
            assertTrue(Arrays.stream(e.getStackTrace()).anyMatch(f -> f.getClassName().endsWith("GamesService")
                    && f.getMethodName().equals("later")), "only the finish's scheduled result screen needs a server: " + e);
        }
    }

    @Test
    void theRestartHoldRefusesEveryNewStartLetsWhatRunsFinishAndHasEveryoneHomeBeforeTheRestart()
            throws Exception {
        Player ava = j.player("Ava", "SURVIVAL", "red");
        Player ben = j.player("Ben", "CREATIVE", "red");
        Player kid = j.player("Kid", "ADVENTURE", "blue");
        Player vic = j.player("Vic", "SURVIVAL", "blue");
        Player wes = j.player("Wes", "SURVIVAL", "red");
        Player sam = j.player("Sam", "SURVIVAL", "red");
        Player lee = j.player("Lee", "ADVENTURE", "blue");
        Player dee = j.player("Dee", "SURVIVAL", "red");
        Player eve = j.player("Eve", "SURVIVAL", "blue");
        Player hal = j.player("Hal", "SURVIVAL", "red");
        Player ivy = j.player("Ivy", "SURVIVAL", "blue");
        Player fay = j.player("Fay", "SURVIVAL", "red");
        Player bea = j.player("Bea", "SURVIVAL", "red");
        assertNull(j.cup.desk().enter(id(ava), loop), "Ava is a Cup entrant on the loop");

        // 15:46: Vic and Wes in the Clubhouse; Ava waits there for her party; the lobbies
        at(15, 46, 0);
        assertNull(j.club.enter(id(vic), ClubVisits.Kind.VISIT, false), "Vic visits");
        assertNull(j.club.enter(id(wes), ClubVisits.Kind.VISIT, false), "Wes visits");
        PartyLobby party = j.games.parties().create(PartyLobby.Kind.RACE, loop.id(), id(ava), 8);
        assertNull(j.games.parties().join(party.id(), id(ben)), "Ben joins Ava's party");
        assertNull(j.club.enter(id(ava), ClubVisits.Kind.PARTY, false), "Ava waits in the Clubhouse");
        PartyLobby deeLobby = j.games.parties().create(PartyLobby.Kind.RACE, loop.id(), id(dee), 8);
        assertNull(j.games.parties().join(deeLobby.id(), id(eve)), "Eve joins Dee's lobby");
        PartyLobby halParty = j.games.parties().create(PartyLobby.Kind.GOLF, "meadow", id(hal), 4);
        assertNull(j.games.parties().join(halParty.id(), id(ivy)), "Ivy joins Hal's golf party");

        // 15:50: Sam and Lee's golf group plays two holes
        at(15, 50, 0);
        PartyLobby golfParty = j.games.parties().create(PartyLobby.Kind.GOLF, "meadow", id(sam), 4);
        assertNull(j.games.parties().join(golfParty.id(), id(lee)), "Lee joins Sam's golf party");
        assertNull(golfParty.start(id(sam)), "Sam starts");
        assertTrue(golf.start(golfParty.id(), "meadow", List.of(sam, lee)), "the group goes to hole 1");
        j.rail.step();
        j.rail.arriveAll();
        for (int hole = 0; hole < 2; hole++) {
            golf.holeIn(id(sam), 3);
            golf.holeIn(id(lee), 3);
            golf.runTasks();
        }

        // 15:53:25: the party race, seated (Ava from the Clubhouse, Ben from home with Kid behind him); Go at 15:53:30
        at(15, 53, 25);
        j.riders.paired(j.view(id(ben)), j.view(id(kid)), loop.id(), null);
        assertNull(j.trials.party().startProblem(ava), "no hold yet: Ava may start");
        assertNull(party.start(id(ava)), "the lobby starts");
        List<PartyRace.Racer> racers = List.of(new PartyRace.Racer(id(ava), "Ava"), new PartyRace.Racer(id(ben), "Ben"));
        PartyRace race = new PartyRace(party.id(), loop, racers, 0, () -> j.race.tick, line -> {
        });
        race.clubhouse(j.door.partyAfter());
        j.trials.party().running(race);
        List<Course.Spot> grid = RaceGrid.forCourse(loop, JourneyBench.ICE, 2).spots();
        for (int i = 0; i < 2; i++) {
            UUID r = racers.get(i).id();
            assertNull(j.race.seat(r, loop, Laps.raced(loop, grid.get(i), 0).course(), grid.get(i), null, race),
                    racers.get(i).name() + " is seated");
            race.seated(r, grid.get(i));
        }
        assertTrue(race.seatingDone(), "on the grid");
        for (int i = 0; i < 20 * 10 && !(racing(ava) && racing(ben)); i++) {
            j.bench.move(50);
            j.race.tick();
            race.tick(j.race.tick, j.games.restartHeld() != null);
        }
        assertTrue(racing(ava) && racing(ben), "racing before the hold");
        long go = j.bench.now();
        j.club.refresh(ClubRaces.live(j.games));
        LiveRace live = j.club.live().stream().filter(r -> r.key().equals("party:" + party.id())).findFirst()
                .orElseThrow();
        j.club.watching(id(wes), live);
        assertEquals("SPECTATOR", j.rail.gameMode(id(wes)), "Wes watches the party race live");

        // Fay's solo run with a warm-up chosen, on the lava course
        at(15, 54, 0);
        assertNull(j.rail.enterNow(id(fay), j.trials, lava.id(), JourneyBench.spot("games", lava.start()), q -> {
        }), "Fay goes to the course's start");
        TrialRun fayRun = new TrialRun(id(fay), lava, false, TimeTrials.COUNTDOWN_TICKS + 1);
        assertTrue(Warmup.begin(fayRun, j.race.tick, 180, lava.start().point(), j.race.nanos), "Fay warms up");
        j.trials.replaceRun(fayRun);

        // 15:55: the hold. Every new start is refused, and takes and moves nothing
        at(15, 55, 0);
        String held = "The server restarts at 4:00 PM";
        assertEquals("4:00 PM", j.games.restartHeld(), "the hold is on");
        j.trials.party().start(dee);
        assertTrue(j.bench.heard(id(dee)).contains(held), "Dee's party start: " + j.bench.heard(id(dee)));
        ((MiniGolf) j.games.game(MiniGolf.SPEC.id())).together().start(hal);
        assertTrue(j.bench.heard(id(hal)).contains(held), "Hal's golf party start: " + j.bench.heard(id(hal)));
        for (String game : List.of(TimeTrials.SPEC.id(), Clubhouse.SPEC.id(), MiniGolf.SPEC.id())) {
            Refusal r = j.games.sessions().entryRefusal(j.games.game(game));
            assertNotNull(r, game + " takes nobody in");
            assertTrue(r.message().contains(held), game + ": " + r.message());
        }
        String driver = RideAlong.driverRefusal(j.games, j.trials, dee, loop.id(), RideAlong.Purpose.SOLO);
        assertTrue(driver != null && driver.contains(held), "Dee can't take a rider: " + driver);
        String rider = RideAlong.riderRefusal(j.games, j.trials, dee, bea, RideAlong.Purpose.SOLO);
        assertTrue(rider != null && rider.contains(held), "nobody can ride: " + rider);
        for (Player p : List.of(dee, eve, hal)) {
            assertNull(j.rail.session(id(p)), p.getName() + " was taken nowhere");
            assertNull(j.rail.row(id(p)), p.getName() + " has no saved state");
        }
        assertEquals(PartyLobby.State.OPEN, deeLobby.state(), "Dee's lobby is still open");
        assertFalse(j.trials.party().racing(deeLobby), "no party race was made for it");
        assertEquals(PartyLobby.State.OPEN, halParty.state(), "Hal's golf party didn't start");
        assertNull(golf.groupOf(id(hal)), "nor went anywhere");
        assertTrue(Warmup.over(fayRun, j.race.tick, j.games.restartHeld() != null), "Fay's warm-up ends for the hold");
        PartyRace warming = new PartyRace(99, lava, List.of(new PartyRace.Racer(UUID.randomUUID(), "X"),
                new PartyRace.Racer(UUID.randomUUID(), "Y")), 180, () -> j.race.tick, line -> {
        });
        warming.racers().forEach(r -> warming.seated(r, lava.start()));
        assertTrue(warming.seatingDone(), "a party in its warm-up");
        assertEquals(PartyRace.Step.TO_GRID, warming.tick(j.race.tick, j.games.restartHeld() != null),
                "a warm-up goes straight to the grid during the hold");
        Warmup.toCountdown(fayRun); // Warmups.end: back to the start for the 3-2-1 (mirrored), then timed
        fayRun.progress = new Progress(lava, lava.start().point(), j.race.nanos);
        fayRun.phase = TrialRun.Phase.RUNNING;

        // 15:55:00 to 15:59:59, a second at a time: what was running finishes; the Clubhouse's hold
        for (int s = 1; s < 300; s++) {
            for (int k = 0; k < 20; k++) {
                j.bench.move(50);
                j.race.tick();
                if (race.tick(j.race.tick, j.games.restartHeld() != null) == PartyRace.Step.END) {
                    j.door.result(ClubRaces.partySheet(loop.name(), race.results(), 1), null); // PartyRaces.finish
                    party.finish();
                }
            }
            if (s == 10) { // the golf group's last putts
                golf.holeIn(id(sam), 3);
                golf.holeIn(id(lee), 2);
                golf.runTasks();
            }
            if (s == 20) {
                j.race.cross(id(ava), j.bench.now() - go);
            }
            if (s == 30) {
                j.race.cross(id(ben), j.bench.now() - go);
            }
            if (s == 45) {
                soloCross(fay, 44_000);
                j.rail.leave(id(fay), EndReason.FINISH); // TimeTrials.finish's scheduled leave (copied)
            }
            clubSecond();
            j.rail.arriveAll();
        }

        // what was running finished and counted
        for (Player p : List.of(ava, ben)) {
            assertEquals(1, j.told.courses(id(p)), p.getName() + "'s party race counted once");
            assertNotNull(j.best(id(p), Scores.course(loop.id())), p.getName() + "'s time is on the board");
            assertEquals(1, j.door.takenIn.getOrDefault(id(p), 0), p.getName() + " was taken into the Clubhouse");
        }
        assertEquals(j.best(id(ava), Scores.course(loop.id())), j.cupTime(id(ava), loop.id()), "Ava's Cup time");
        assertEquals(1, j.door.takenIn.getOrDefault(id(kid), 0), "Kid followed Ben");
        for (Player p : List.of(sam, lee)) {
            assertEquals(1, golf.recorded.get(id(p)), p.getName() + "'s golf round is recorded once");
            assertEquals(1, golf.toClub.get(id(p)), p.getName() + " went to the Clubhouse");
        }
        assertNotNull(j.best(id(fay), Scores.course(lava.id())), "Fay's timed run counted after her warm-up ended");

        // the Clubhouse's hold: warned once, home a minute later
        for (Player p : List.of(vic, wes, ava, ben, kid, sam, lee)) {
            assertEquals(1, warnings.getOrDefault(id(p), 0), p.getName() + " was warned once");
            assertNotNull(sentHome.get(id(p)), p.getName() + " was sent home");
            assertTrue(sentHome.get(id(p)) - warned.get(id(p)) >= ClubVisits.MINUTE, p.getName()
                    + " a minute after the warning");
            assertTrue(j.bench.heard(id(p)).contains("the Clubhouse closes in 1 minute"), j.bench.heard(id(p)));
        }
        assertTrue(j.rail.syncTeleports().stream().anyMatch(s -> s.world().equals("games") && s.y() == 161),
                "Wes was brought down to the Clubhouse floor: " + j.rail.syncTeleports());
        assertEquals("SURVIVAL", j.rail.gameMode(id(wes)), "and went home in survival");

        // by 3:59:59: nobody in a game
        for (Player p : List.of(ava, ben, kid, vic, wes, sam, lee, fay)) {
            assertNull(j.homeProblem(id(p)), p.getName() + " is home as they were: " + j.homeProblem(id(p)));
        }
        assertEquals(0, j.club.visits().size(), "the Clubhouse is empty");
        assertTrue(j.board.entries(NoPush.TEAM).isEmpty(), "nobody on the no-push team");
        assertTrue(j.club.visibility().hiddenFrom(id(wes)).isEmpty(), "nobody hidden");
        assertTrue(j.riders.rides().isEmpty(), "the ride is over");
        assertFalse(WorldEntities.mayEnter(id(ben), id(kid)), "Kid may no longer get into Ben's boat");

        // 16:00: the restart finds no live session
        int trips = j.rail.started();
        int syncs = j.rail.syncTeleports().size();
        at(16, 0, 0);
        j.rail.stopping(true);
        j.rail.stop();
        assertEquals(trips, j.rail.started(), "no trip at the restart");
        assertEquals(syncs, j.rail.syncTeleports().size(), "and nobody restored in place");
        assertEquals(0, j.bench.severe(), "nothing threw: " + j.bench.severeLines());
    }

    /** Ava's race session taken into the Clubhouse at {@code 15:59:30} (a race that ended then). */
    private Player takenInAt(int second) {
        Player ava = j.player("Ava", "SURVIVAL", "red");
        at(15, 58, 0);
        assertNull(j.rail.enterNow(id(ava), j.trials, loop.id(), JourneyBench.spot("games", loop.start()), q -> {
        }), "Ava is racing on the loop");
        at(15, 59, second);
        assertTrue(j.door.takeIn(ava, ClubVisits.Kind.PARTY, null), "the race's end takes her to the Clubhouse, "
                + "though the hold is on");
        return ava;
    }

    private void secondsTo1600() {
        while (j.bench.now() < GamesBench.at(2026, 9, 29, 16, 0)) {
            j.club.visitsSecond();
            j.rail.arriveAll();
            j.bench.move(SEC);
        }
    }

    @Test
    void anArrivalInTheLastMinuteIsWarnedStaysAcrossTheRestartAndIsRestoredInPlaceThenHomeAtTheNextJoin()
            throws Exception {
        Player ava = takenInAt(30);
        secondsTo1600();
        assertTrue(j.club.visits().in(id(ava)), "warned at 3:59:30, she would go home only at 4:00:30");
        assertNotNull(j.rail.session(id(ava)), "so she is still in a session at the restart");
        j.rail.stopping(true);
        j.rail.stop(); // the restart: every session ends in place
        assertEquals("RETURN", j.rail.row(id(ava)).phase(), "restored in place, RETURN: her things are on her");
        assertEquals("SURVIVAL", j.rail.gameMode(id(ava)), "in her own mode");
        j.rail.crash(); // the next start
        j.rail.joined(id(ava));
        j.rail.arriveAll();
        assertNull(j.homeProblem(id(ava)), "home at the next join, as she was: " + j.homeProblem(id(ava)));
        assertEquals(1, j.rail.applies(id(ava)), "put back once");
    }

    @Disabled("PRODBUG: ClubRaces.toClub/Clubhouse.takeIn ignore the restart hold, so a racer taken into the Clubhouse"
            + " less than a minute before a restart is still there when it comes (ClubVisits promises nobody is)")
    @Test
    void nobodyIsInTheClubhouseAcrossARestartEvenOneTakenInDuringItsLastMinute() throws Exception {
        Player ava = takenInAt(30);
        secondsTo1600();
        assertNull(j.rail.session(id(ava)), "ClubVisits: 'so nobody is ever here across a restart' - Ava should be"
                + " home (or never taken in) by 4:00 PM");
        assertFalse(j.club.visits().in(id(ava)), "and out of the Clubhouse");
    }
}
