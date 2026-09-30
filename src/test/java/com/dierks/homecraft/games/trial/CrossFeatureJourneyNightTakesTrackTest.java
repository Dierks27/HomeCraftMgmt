package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.GamesBench;
import com.dierks.homecraft.games.NoPush;
import com.dierks.homecraft.games.Scores;
import com.dierks.homecraft.games.clubhouse.ClubVisits;
import com.dierks.homecraft.games.clubhouse.Clubhouse;
import com.dierks.homecraft.games.clubhouse.ClubhouseSettings;
import com.dierks.homecraft.games.clubhouse.LiveRace;
import com.dierks.homecraft.games.cup.live.CupSettings;
import com.dierks.homecraft.games.cup.live.WeeklyCup;
import com.dierks.homecraft.games.event.EventMachine;
import com.dierks.homecraft.games.event.NightRunner;
import com.dierks.homecraft.games.event.RaceNight;
import com.dierks.homecraft.games.event.RaceNightSettings;
import com.dierks.homecraft.games.world.WorldEntities;
import com.dierks.homecraft.storage.EventDao;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Journey 9: Race Night takes its track from a party race racing on it, with a rider in a boat and a
 * watcher in the Clubhouse, then seats its own racers, one of them waiting in the Clubhouse (the real
 * night, race mode, party race, Clubhouse, ride along and world sessions; only the server's I/O is
 * mirrored).
 *
 * <p>Ava (from the Clubhouse, a Cup entrant) and Ben (Kid riding behind him) race a party race on the
 * loop; Wes tapped the party's Watch and watches it live. Dee, Cal and Ava have joined tonight's Race
 * Night on the same track; Dee waits in the Clubhouse. At last call the night holds the track: the party
 * race is called off, its racers go home (a call-off always does) and Kid with Ben; Wes comes back; the
 * party can't start again; then the night seats Dee from the Clubhouse and Cal and Ava from home, and
 * races its first race.
 */
class CrossFeatureJourneyNightTakesTrackTest {

    private static final long T0 = GamesBench.at(2026, 9, 29, 12, 0);
    private static final String NIGHT = "rn-20260929-1211";
    private static final Point STAND = new Point(0.5, 70, 0.5);

    private JourneyBench j;
    private Course loop;
    private NightRunner night;
    private PartyRace party;
    private Player ava;
    private Player ben;
    private Player cal;
    private Player dee;
    private Player kid;
    private Player wes;

    @BeforeEach
    void setUp() throws Exception {
        j = new JourneyBench(T0, List.of(TimeTrials.SPEC, WeeklyCup.SPEC, RaceNight.SPEC, Clubhouse.SPEC), "trials",
                TimeTrialsSettings.defaults(), "cup", CupSettings.defaults(), "race_night", RaceNightSettings.defaults(),
                "clubhouse", ClubhouseSettings.defaults());
        loop = j.loop();
        j.cup.desk().dao().choose(loop.id(), true);
        ava = j.player("Ava", "SURVIVAL", "red");
        ben = j.player("Ben", "CREATIVE", "red");
        cal = j.player("Cal", "SURVIVAL", "blue");
        dee = j.player("Dee", "SURVIVAL", "blue");
        kid = j.player("Kid", "ADVENTURE", "blue");
        wes = j.player("Wes", "SURVIVAL", "red");
        night = j.night(NIGHT, loop, T0 + 11 * JourneyBench.MIN, 60, STAND, j.new NightPortsBench());
    }

    @AfterEach
    void tearDown() throws Exception {
        j.close();
    }

    private static UUID id(Player p) {
        return p.getUniqueId();
    }

    /** One server tick of everything: the night, race mode, the party race; the Clubhouse's second. */
    private void run(int maxTicks, BooleanSupplier done) {
        for (int i = 0; i < maxTicks && !done.getAsBoolean(); i++) {
            j.bench.move(50);
            night.tick();
            j.race.tick();
            if (party != null) {
                party.tick(j.race.tick, j.games.restartHeld() != null);
            }
            if (j.race.tick % 20 == 0) {
                j.club.refresh(ClubRaces.live(j.games));
                j.club.second();
                j.riders.second();
            }
        }
    }

    private boolean racing(Player p) {
        TrialRun r = j.trials.run(id(p));
        return r != null && r.race != null && !r.warmup && r.phase == TrialRun.Phase.RUNNING && r.progress != null;
    }

    @Test
    void raceNightCallsOffThePartyRaceOnItsTrackSendsItsRacersAndRiderHomeAndSeatsTheClubhouseWaiter()
            throws Exception {
        assertNull(j.cup.desk().enter(id(ava), loop), "Ava is a Cup entrant on the loop");

        // the night opens; Dee, Cal and Ava join; Dee waits in the Clubhouse
        run(2 * 60 * 20, () -> night.phase() == EventMachine.Phase.OPEN);
        for (Player p : List.of(dee, cal, ava)) {
            assertNull(night.join(id(p), p.getName()), p.getName() + " joins tonight's Race Night");
        }
        assertNull(j.club.enter(id(dee), ClubVisits.Kind.NIGHT, false), "Dee waits in the Clubhouse");
        String deeSid = j.rail.session(id(dee)).id();

        // the party: Ava hosts from the Clubhouse, Ben joins from home with Kid riding, Wes taps Watch
        PartyLobby lobby = j.games.parties().create(PartyLobby.Kind.RACE, loop.id(), id(ava), 8);
        assertNull(j.games.parties().join(lobby.id(), id(ben)), "Ben joins the party");
        assertNull(j.games.parties().join(lobby.id(), id(wes)), "Wes joins the party");
        assertNull(j.club.enter(id(ava), ClubVisits.Kind.PARTY, false), "Ava goes to the Clubhouse");
        assertNull(j.club.enter(id(wes), ClubVisits.Kind.VISIT, true), "Wes taps the party's Watch");
        assertTrue(j.door.spectator(id(wes)), "Wes is a spectator");
        j.riders.paired(j.view(id(ben)), j.view(id(kid)), loop.id(), null);
        run(8 * 60 * 20, () -> j.bench.now() >= T0 + 8 * JourneyBench.MIN);

        // PartyRaces.start (copied): a spectator is never seated; Ava from the Clubhouse, Ben from home
        assertNull(j.trials.party().startProblem(ava), "Ava may start before the night's last call");
        List<PartyRace.Racer> racers = new ArrayList<>();
        for (UUID m : lobby.members()) {
            if (!j.door.spectator(m)) {
                racers.add(new PartyRace.Racer(m, j.players.get(m).getName()));
            }
        }
        assertNull(lobby.start(id(ava)), "the lobby starts");
        party = new PartyRace(lobby.id(), loop, racers, 0, () -> j.race.tick, line -> {
        });
        party.clubhouse(j.door.partyAfter());
        j.trials.party().running(party);
        List<Course.Spot> grid = RaceGrid.forCourse(loop, JourneyBench.ICE, 2).spots();
        for (int i = 0; i < racers.size(); i++) {
            UUID r = racers.get(i).id();
            assertNull(j.race.seat(r, loop, Laps.raced(loop, grid.get(i), 0).course(), grid.get(i), null, party),
                    racers.get(i).name() + " is seated");
            party.seated(r, grid.get(i));
        }
        assertTrue(party.seatingDone(), "on the grid");
        assertFalse(party.racers().contains(id(wes)), "Wes is never seated by the party");
        run(20 * 20, () -> racing(ava) && racing(ben));
        assertTrue(racing(ava) && racing(ben), "the party race is racing");
        j.club.refresh(ClubRaces.live(j.games));
        LiveRace live = j.club.live().stream().filter(r -> r.key().equals("party:" + lobby.id())).findFirst()
                .orElseThrow();
        j.club.watching(id(wes), live);
        assertEquals("SPECTATOR", j.rail.gameMode(id(wes)), "Wes watches the party race live");
        assertTrue(j.pushes.isOn(id(kid)), "Kid rides, on the no-push team");

        // 1-2. Together to the last call: the night holds the track and calls the party race off
        run(3 * 60 * 20, () -> party.state() == PartyRace.State.DONE && j.trials.run(id(ben)) == null);
        assertEquals(PartyRace.State.DONE, party.state(), "the party race was called off");
        j.rail.arriveAll();
        for (Player p : List.of(ava, ben)) {
            assertTrue(j.bench.heard(id(p)).contains("Race Night needs this track now"), p.getName() + " reads why: "
                    + j.bench.heard(id(p)));
            assertTrue(j.race.home.containsKey(id(p)), p.getName() + " went home: a call-off always sends home");
            assertNull(j.homeProblem(id(p)), p.getName() + " is home as they were: " + j.homeProblem(id(p)));
        }
        assertFalse(j.club.visits().in(id(ava)), "Ava went home, not to the Clubhouse");
        assertNull(j.best(id(ava), Scores.course(loop.id())), "Ava's unfinished race counts for nothing");
        assertNull(j.best(id(ben), Scores.course(loop.id())), "nor Ben's");
        assertNull(j.cupTime(id(ava), loop.id()), "no Cup time for Ava");
        assertTrue(j.trials.party().lastResults(lobby).isEmpty(), "a called-off race keeps no results");
        assertEquals(PartyLobby.State.OPEN, lobby.state(), "the party is open again");

        // Kid went home with Ben
        assertNull(j.homeProblem(id(kid)), "Kid is home as he was: " + j.homeProblem(id(kid)));
        assertNull(j.rail.row(id(kid)), "Kid's row is finished");
        assertFalse(j.pushes.isOn(id(kid)), "Kid is off the no-push team");
        assertEquals("blue", j.board.teamOf("Kid"), "back on his own team");
        assertFalse(j.notCollidable.contains(id(kid)), "and collidable");
        assertFalse(WorldEntities.mayEnter(id(ben), id(kid)), "Kid may no longer get into Ben's boat");
        assertTrue(j.riders.rides().isEmpty(), "the ride is over");

        // 3. Wes is back in the Clubhouse
        run(40, () -> false);
        assertFalse(j.club.watchingNow(id(wes)), "the party race is over: Wes is back");
        assertTrue(j.club.visits().in(id(wes)), "in the Clubhouse");
        assertNull(j.rail.sessionMode(id(wes)), "in the games' ADVENTURE session mode");
        assertEquals("ADVENTURE", j.rail.gameMode(id(wes)), "adventure");
        assertTrue(j.club.visibility().hiddenFrom(id(wes)).isEmpty(), "seen by everyone");

        // 4. The party can't start on a held track
        assertEquals("Race Night is on this track - watch or join!", j.trials.party().startProblem(ava),
                "a new start is refused while Race Night holds the track");

        // 5. The night seats Dee from the Clubhouse, Cal and Ava from home; race 1 to the break
        run(4 * 60 * 20, () -> night.phase() == EventMachine.Phase.WARMUP && j.trials.run(id(dee)) != null
                && j.trials.run(id(cal)) != null && j.trials.run(id(ava)) != null);
        assertEquals(EventMachine.Phase.WARMUP, night.phase(), "the night's warm-up");
        assertEquals(1, j.race.fromClub.get(id(dee)), "Dee was seated from the Clubhouse");
        assertEquals(1, j.door.handedOut.get(id(dee)), "handed out once");
        assertEquals(deeSid, j.rail.session(id(dee)).id(), "in the same session she waited in");
        assertNull(j.race.fromClub.get(id(cal)), "Cal from home");
        assertEquals(1, j.race.fromClub.get(id(ava)), "Ava's only seat from the Clubhouse was the party's");
        for (Player p : List.of(dee, cal, ava)) {
            j.race.ready(id(p));
        }
        run(800, () -> night.phase() == EventMachine.Phase.RACING && racing(dee) && racing(cal) && racing(ava));
        run(60, () -> false);
        long ms = 45_000;
        for (Player p : List.of(dee, cal, ava)) {
            j.race.cross(id(p), ms);
            ms += 1_000;
            run(5, () -> false);
        }
        run(200, () -> night.phase() == EventMachine.Phase.BREAK);
        assertEquals(EventMachine.Phase.BREAK, night.phase(), "race 1 done: the break");
        List<EventDao.RaceRow> race1 = j.events().races(NIGHT);
        for (Player p : List.of(dee, cal, ava)) {
            assertTrue(race1.stream().anyMatch(r -> r.player().equals(id(p)) && r.race() == 1 && r.points() > 0),
                    p.getName() + " has points from race 1: " + race1);
            assertNull(j.best(id(p), Scores.course(loop.id())), p.getName() + ": no heat time on the course's board");
        }
        for (Player p : List.of(kid, wes)) {
            assertFalse(night.in(id(p)), p.getName() + " is never on tonight's list");
            assertNull(j.trials.run(id(p)), p.getName() + " was never seated");
        }
        assertNotNull(j.club.visits().get(id(wes)), "Wes watches from the Clubhouse still");
        assertTrue(j.board.entries(NoPush.TEAM).stream().noneMatch(e -> e.equals("Kid")), "Kid never back on it");
        assertEquals(0, j.bench.severe(), "nothing threw: " + j.bench.severeLines());
    }
}
