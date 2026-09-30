package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.EndReason;
import com.dierks.homecraft.games.GamesBench;
import com.dierks.homecraft.games.NoPush;
import com.dierks.homecraft.games.Scores;
import com.dierks.homecraft.games.clubhouse.ClubBoard;
import com.dierks.homecraft.games.clubhouse.ClubVisits;
import com.dierks.homecraft.games.clubhouse.ClubhouseSettings;
import com.dierks.homecraft.games.clubhouse.Clubhouse;
import com.dierks.homecraft.games.clubhouse.LiveRace;
import com.dierks.homecraft.games.cup.live.CupSettings;
import com.dierks.homecraft.games.cup.live.WeeklyCup;
import com.dierks.homecraft.games.world.WorldEntities;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Journey 1, across the Clubhouse, party races, ride along, Watch live, the Weekly Cup and the world
 * sessions at once: a party race started from the Clubhouse with a rider in the host's boat and a
 * live watcher, on the real framework, database and world-session state machine
 * ({@link JourneyBench}: only the server's I/O is mirrored).
 *
 * <p>Ava hosts a party race on the loop (a Cup entrant) and Ben joins; both go to the Clubhouse. Kid
 * rides in the back of Ava's boat; Wes watches live from the Clubhouse; Bea is online and plays
 * nothing. The host starts from the Clubhouse: both are handed from the Clubhouse's session to the
 * race's in place, Kid's own passenger session enters at the boat, Wes flies over the course in
 * spectator mode, hidden from everyone. Ben warms up a lap, both are ready, the grid, Go, Ava wins,
 * both go back to the Clubhouse (Kid with Ava), the results go up, the race drops out of the live
 * list and Wes is brought back; then everyone taps Leave game.
 *
 * <p>Pinned: each finish counts once (boards, first clear, the quests, Ava's Cup time; a ride still
 * counts with {@code rider_runs_count} on); a rider and a watcher are never racers and earn nothing;
 * every racer keeps one world session from the Clubhouse to the race and back, saved once and put
 * back once; everyone ends at home with their own things once and their own game mode (never
 * spectator), off the no-push team and back on their own team, seen by everyone, and the ride over.
 */
class CrossFeatureJourneyPartyRaceTest {

    private static final long T0 = GamesBench.at(2026, 9, 29, 12, 0);

    private JourneyBench j;
    private Course loop;
    private Player ava;
    private Player ben;
    private Player kid;
    private Player wes;
    private Player bea;

    @BeforeEach
    void setUp() throws Exception {
        j = new JourneyBench(T0, List.of(TimeTrials.SPEC, WeeklyCup.SPEC, Clubhouse.SPEC), "trials",
                TimeTrialsSettings.defaults(), "cup", CupSettings.defaults(), "clubhouse", ClubhouseSettings.defaults());
        loop = j.loop();
        j.cup.desk().dao().choose(loop.id(), true); // a hand-built course runs a Cup once an admin says so
        ava = j.player("Ava", "SURVIVAL", "red");
        ben = j.player("Ben", "CREATIVE", "red");
        kid = j.player("Kid", "ADVENTURE", "blue");
        wes = j.player("Wes", "SURVIVAL", "red");
        bea = j.player("Bea", "SURVIVAL", "blue");
    }

    @AfterEach
    void tearDown() throws Exception {
        j.close();
    }

    private static UUID id(Player p) {
        return p.getUniqueId();
    }

    private String sid(Player p) {
        return j.rail.session(id(p)) == null ? null : j.rail.session(id(p)).id();
    }

    private boolean onTeam(Player p) {
        return NoPush.TEAM.equals(j.board.teamOf(p.getName()));
    }

    /** Once a second: the Clubhouse's live list and Watch live, and ride along's second. */
    private void second() {
        j.club.refresh(ClubRaces.live(j.games));
        j.club.second();
        j.riders.second();
    }

    /** One server tick of the race, and its coordinator's tick (a copy of PartyRaces.tick's switch). */
    private PartyRace.Step tick(PartyRace party) {
        j.bench.move(50);
        j.race.tick();
        PartyRace.Step step = party.tick(j.race.tick, j.games.restartHeld() != null);
        if (step == PartyRace.Step.TO_GRID) { // PartyRaces.toGrid
            for (UUID r : party.racers()) {
                Course.Spot spot = party.grid(r);
                if (spot != null && party.racing(r)) {
                    j.race.regrid(r, Laps.raced(loop, spot, 0).course(), spot);
                }
            }
        }
        if (j.race.tick % 20 == 0) {
            second();
        }
        return step;
    }

    /** Released at Go: the race's own clock runs (not the warm-up's free laps, not the grid). */
    private boolean racing(Player p) {
        TrialRun r = j.trials.run(id(p));
        return r != null && r.race != null && !r.warmup && r.phase == TrialRun.Phase.RUNNING && r.progress != null;
    }

    private String dbg(Player p) {
        TrialRun r = j.trials.run(id(p));
        return r == null ? "no run" : "phase=" + r.phase + " warmup=" + r.warmup + " ready=" + r.warmupReady + " state="
                + (r.race == null ? null : r.race.state) + " start=" + j.race.startOf(id(p));
    }

    private void kidIsNoRacer(PartyRace party, String when) {
        assertNull(j.trials.run(id(kid)), "a rider has no run (" + when + ")");
        assertFalse(party != null && party.racers().contains(id(kid)), "Kid is never one of the racers (" + when + ")");
    }

    @Test
    void aPartyRaceFromTheClubhouseWithARiderAndAWatcherCountsOnceAndEveryoneEndsHomeAsTheyWere()
            throws Exception {
        Map<UUID, Integer> before = new java.util.HashMap<>();
        for (Player p : List.of(ava, ben, kid, wes, bea)) {
            before.put(id(p), j.bench.balance(id(p)));
        }
        assertTrue(j.trials.settings().riderRunsCount(), "shipped: a ride with a rider counts as normal");

        // 1. Ava's party on the loop; Ben joins; Ava enters this week's Cup, Ben doesn't
        PartyLobby lobby = j.games.parties().create(PartyLobby.Kind.RACE, loop.id(), id(ava), 8);
        assertNotNull(lobby, "Ava's party is open");
        assertNull(j.games.parties().join(lobby.id(), id(ben)), "Ben joins");
        assertNull(j.cup.desk().enter(id(ava), loop), "Ava enters this week's Cup on the loop");
        assertEquals(before.get(id(ava)) - 5, j.bench.balance(id(ava)), "for 5 tokens");

        // 2. Go to the Clubhouse (Ava, Ben); Wes watches (/hcm play watch: in as a visitor)
        assertNull(j.club.enter(id(ava), ClubVisits.Kind.PARTY, false), "Ava goes to the Clubhouse");
        assertNull(j.club.enter(id(ben), ClubVisits.Kind.PARTY, false), "Ben goes to the Clubhouse");
        assertNull(j.club.enter(id(wes), ClubVisits.Kind.VISIT, true), "Wes comes in to watch");
        String avaSid = sid(ava);
        String benSid = sid(ben);
        assertNotNull(avaSid, "Ava is in a Clubhouse session");
        for (Player p : List.of(ava, ben, wes)) {
            assertTrue(j.club.visits().in(id(p)), p.getName() + " is in the Clubhouse");
            assertTrue(onTeam(p), p.getName() + " is on the no-push team in the Clubhouse");
            assertEquals("ADVENTURE", j.rail.gameMode(id(p)), p.getName() + " is in the games' mode");
            assertTrue(j.rail.items(id(p)).stream().allMatch(i -> i.startsWith("kit:clubhouse:")),
                    p.getName() + " holds the Clubhouse's kit only: " + j.rail.items(id(p)));
        }
        assertTrue(j.rail.items(id(ava)).contains("kit:clubhouse:party"), "a party racer's kit has Party");
        assertTrue(j.bench.heard(id(ava)).contains("You're waiting in the Clubhouse"), j.bench.heard(id(ava)));

        // 3. Kid rides in Ava's boat
        assertNull(RideAlong.driverRefusal(j.games, j.trials, ava, loop.id(), RideAlong.Purpose.PARTY),
                "Ava may take a rider: the Clubhouse is on through the door");
        assertNull(RideAlong.riderRefusal(j.games, j.trials, ava, kid, RideAlong.Purpose.PARTY), "Kid may ride");
        j.riders.paired(j.view(id(ava)), j.view(id(kid)), loop.id(), null);
        assertNotNull(j.riders.ofDriver(id(ava)), "paired");

        // 4. Start, as PartyRaces.start does it
        assertNull(j.trials.party().startProblem(ava), "Ava may start");
        List<PartyRace.Racer> racers = new ArrayList<>();
        for (UUID m : lobby.members()) {
            if (j.door.spectator(m)) {
                continue;
            }
            assertTrue(j.door.seatable(m), "every member waits in the Clubhouse");
            racers.add(new PartyRace.Racer(m, j.players.get(m).getName()));
        }
        assertNull(lobby.start(id(ava)), "the lobby starts");
        List<String> said = new ArrayList<>();
        PartyRace party = new PartyRace(lobby.id(), loop, racers, 60, () -> j.race.tick, said::add);
        party.clubhouse(j.door.partyAfter() && loop.world().equalsIgnoreCase(j.door.world()));
        j.trials.party().running(party);
        kidIsNoRacer(party, "at the start");

        // 5. Seated from the Clubhouse; Kid's passenger session enters at Ava's boat
        List<Course.Spot> grid = RaceGrid.forCourse(loop, JourneyBench.ICE, 2).spots();
        for (int i = 0; i < racers.size(); i++) {
            UUID r = racers.get(i).id();
            assertNull(j.race.seat(r, loop, Laps.raced(loop, grid.get(i), 0).course(), grid.get(i), null, party),
                    racers.get(i).name() + " is seated");
            party.seated(r, grid.get(i));
        }
        assertTrue(party.seatingDone(), "both in: the warm-up");
        assertEquals(PartyRace.State.WARMUP, party.state(), "the host chose a warm-up");
        assertEquals(1, j.race.fromClub.get(id(ava)), "Ava was seated from the Clubhouse");
        assertEquals(1, j.race.fromClub.get(id(ben)), "and Ben");
        assertEquals(avaSid, sid(ava), "Ava's Clubhouse session is the race's now: one session");
        assertEquals("trials", j.rail.session(id(ava)).gameId(), "held by Time Trials");
        assertFalse(j.club.visits().in(id(ava)), "out of the Clubhouse");
        assertTrue(j.rail.items(id(ava)).stream().allMatch(i -> i.startsWith("kit:trials:")),
                "the race's kit only: " + j.rail.items(id(ava)));
        assertEquals("trials", j.rail.session(id(kid)).gameId(), "Kid is in a passenger session of Time Trials");
        assertTrue(j.rail.items(id(kid)).contains("kit:trials:ride"), "with the rider's kit: " + j.rail.items(id(kid)));
        assertTrue(j.pushes.isOn(id(kid)) && onTeam(kid), "the rider is on the no-push team");
        assertTrue(j.notCollidable.contains(id(kid)), "and can't collide with a racing boat");
        assertTrue(j.trials.run(id(ava)).hadRider, "Ava's run had a rider (latched when Kid sat down)");
        assertFalse(onTeam(ava) || onTeam(ben), "boat racers are never on the no-push team: boats bump");
        kidIsNoRacer(party, "seated");

        // 6. Wes watches the party race live
        second();
        LiveRace live = j.club.live().stream().filter(r -> r.key().equals("party:" + lobby.id())).findFirst()
                .orElse(null);
        assertNotNull(live, "the party race is live in the Clubhouse: " + j.club.live());
        j.club.watching(id(wes), live);
        assertEquals("SPECTATOR", j.rail.gameMode(id(wes)), "Wes watches in spectator mode");
        assertTrue(j.rail.flying(id(wes)), "flying");
        assertEquals(live.area().viewY(), j.rail.place(id(wes)).y(), 0.01, "over the loop");
        assertTrue(j.club.visibility().hiddenFrom(id(wes)).containsAll(List.of(id(ava), id(ben), id(kid), id(bea))),
                "a watcher is hidden from everyone who isn't watching: " + j.club.visibility().hiddenFrom(id(wes)));

        // 7. Ben's warm-up lap; both ready; the grid; Go
        j.race.cross(id(ben), 41_000);
        assertNull(j.best(id(ben), Scores.course(loop.id())), "a warm-up lap never reaches a board");
        j.race.ready(id(ava));
        j.race.ready(id(ben));
        for (int i = 0; i < 20 * 20 && !(racing(ava) && racing(ben)); i++) {
            tick(party);
        }
        assertEquals(PartyRace.State.RACING, party.state(), "racing: " + said + " ava " + dbg(ava) + " ben " + dbg(ben));
        assertEquals(j.race.startOf(id(ava)), j.race.startOf(id(ben)), "one go instant for both");
        assertEquals(1, j.race.regrids.get(id(ava)), "Ava went from the warm-up to the grid once");
        assertTrue(j.trials.run(id(ava)).hadRider, "Kid rides behind Ava again after the re-grid");
        kidIsNoRacer(party, "racing");

        // 8-9. Racing; Ava crosses at 45.0 s, Ben at 46.0 s
        for (int i = 0; i < 45 * 20; i++) { // the race's 45 seconds pass on the server's clock too
            tick(party);
        }
        j.race.cross(id(ava), 45_000);
        for (int i = 0; i < 20; i++) {
            tick(party);
        }
        j.race.cross(id(ben), 46_000);
        boolean ended = false;
        for (int i = 0; i < 40 && !ended; i++) {
            ended = tick(party) == PartyRace.Step.END;
        }
        assertTrue(ended, "both in: the race is over");
        // a copy of PartyRaces.finish: nobody is left on the race; the lobby opens; the results go up
        for (UUID r : party.racers()) {
            TrialRun run = j.trials.run(r);
            assertFalse(run != null && run.race != null && run.race.link == party, "nobody is still on the race");
        }
        lobby.finish();
        List<PartyRace.Line> results = party.results();
        j.door.result(ClubRaces.partySheet(loop.name(), results, 1), null);

        // where everyone is, after the race
        assertEquals(ClubVisits.Kind.PARTY, j.club.visits().get(id(ava)).kind(), "Ava is back in the Clubhouse");
        assertEquals(ClubVisits.Kind.PARTY, j.club.visits().get(id(ben)).kind(), "so is Ben");
        assertEquals(ClubVisits.Kind.PARTY, j.club.visits().get(id(kid)).kind(), "Kid came along with Ava");
        assertTrue(j.riders.ofDriver(id(ava)).inClub, "the ride is kept for Race again");
        assertEquals(ClubVisits.Kind.VISIT, j.club.visits().get(id(wes)).kind(), "Wes is still visiting");
        assertTrue(j.bench.heard(id(ava)).contains("Back in the Clubhouse! Look at the board for the results."),
                "Ava reads where she is: " + j.bench.heard(id(ava)));
        assertTrue(j.bench.heard(id(kid)).contains("Back in the Clubhouse with Ava!"), j.bench.heard(id(kid)));
        assertFalse(j.race.home.containsKey(id(ava)) || j.race.home.containsKey(id(ben)), "nobody went home");
        assertEquals(avaSid, sid(ava), "Ava kept one session from the Clubhouse to the race and back");
        assertEquals(benSid, sid(ben), "and so did Ben");
        assertEquals(avaSid, j.rail.row(id(ava)).sessionId(), "one row all along");
        for (Player p : List.of(ava, ben)) {
            // once at the Clubhouse's door, and once more when the hand-over to the race banked her things
            // (final gate #17: a bank is saved at once); a second session would have saved again going home
            assertEquals(2, j.rail.saves(id(p)), p.getName() + "'s state was saved at the Clubhouse's door and at"
                    + " the hand-over's bank, in one session");
        }
        for (Player p : List.of(ava, ben, kid, wes)) {
            assertTrue(onTeam(p), p.getName() + " is on the no-push team in the Clubhouse");
        }
        assertFalse(j.notCollidable.contains(id(kid)), "Kid is collidable again (the Clubhouse's team keeps him safe)");

        // the counting
        assertEquals(45_000L, j.best(id(ava), Scores.course(loop.id())), "Ava's time is on the course's board");
        assertEquals(46_000L, j.best(id(ben), Scores.course(loop.id())), "Ben's race time, not his warm-up lap");
        assertEquals(1, j.told.courses(id(ava)), "Ava: FINISH_COURSE once");
        assertEquals(1, j.told.courses(id(ben)), "Ben: FINISH_COURSE once");
        assertEquals(45_000L, j.cupTime(id(ava), loop.id()), "Ava's finish is her Cup time (an entrant)");
        assertEquals(0, j.rows("cup_entries", "player", id(ben)), "Ben has no Cup entry");
        for (Player p : List.of(ava, ben)) {
            assertEquals(1, j.rewards(id(p), "FIRST_CLEAR"), p.getName() + ": the first clear, once");
            assertEquals(0, j.rewards(id(p), "EVENT_PRIZE"), p.getName() + ": a party race pays no prize");
        }
        assertEquals(List.of(id(ava), id(ben)), results.stream().map(PartyRace.Line::id).toList(), "in finishing order");
        assertEquals(PartyRace.Result.FINISHED, results.get(0).result(), "Ava finished");
        assertEquals(1, results.get(0).rank(), "1st");
        assertEquals(PartyRace.Result.FINISHED, results.get(1).result(), "Ben finished");
        assertEquals(2, results.get(1).rank(), "2nd");
        ClubBoard.Sheet sheet = j.door.last();
        assertTrue(sheet.rows().get(0).contains("Ava") && sheet.rows().get(0).contains("0:45.0"),
                "the board's row 1 is Ava 0:45.0: " + sheet.rows());
        kidIsNoRacer(party, "after the race");

        // 10. The next second: the race is over, so Wes comes back
        assertTrue(j.club.watchingNow(id(wes)), "Wes still watches until the second");
        second();
        assertFalse(j.club.watchingNow(id(wes)), "the race dropped out of the live list: Wes is back");
        assertEquals("ADVENTURE", j.rail.gameMode(id(wes)), "back in the games' mode");
        assertNull(j.rail.sessionMode(id(wes)), "the session's own mode is gone");
        assertTrue(j.club.visibility().hiddenFrom(id(wes)).isEmpty(), "seen by everyone again");
        assertTrue(j.bench.heard(id(wes)).contains("That's the end of it"), j.bench.heard(id(wes)));

        // 11. Everyone taps Leave game
        for (Player p : List.of(ava, ben, kid, wes)) {
            j.rail.leave(id(p), EndReason.QUIT_ITEM);
        }
        j.rail.arriveAll();
        j.riders.second();

        for (Player p : List.of(ava, ben, kid, wes)) {
            assertNull(j.homeProblem(id(p)), p.getName() + " is home as they were: " + j.homeProblem(id(p)));
            assertEquals(1, j.rail.applies(id(p)), p.getName() + "'s state was put back once");
            assertEquals(j.teams.get(id(p)), j.board.teamOf(p.getName()), p.getName() + " is back on their own team");
            assertFalse(j.pushes.isOn(id(p)), p.getName() + " is off the no-push team");
            assertFalse(j.club.visits().in(id(p)), p.getName() + " is out of the Clubhouse");
        }
        assertEquals("CREATIVE", j.rail.gameMode(id(ben)), "Ben is in creative again");
        assertEquals("ADVENTURE", j.rail.gameMode(id(kid)), "Kid in adventure, his own");
        assertEquals("SURVIVAL", j.rail.gameMode(id(wes)), "Wes in survival, never left in spectator");
        assertTrue(j.board.entries(NoPush.TEAM).isEmpty(), "nobody is left on the no-push team");
        assertTrue(j.club.visibility().hiddenFrom(id(wes)).isEmpty(), "nobody is hidden from anyone");
        List<String> hides = new ArrayList<>(j.club.hides);
        List<String> shows = new ArrayList<>(j.club.shows);
        Collections.sort(hides);
        Collections.sort(shows);
        assertEquals(hides, shows, "every hide was matched by a show");
        assertTrue(j.riders.rides().isEmpty(), "the ride is over");
        assertFalse(WorldEntities.mayEnter(id(ava), id(kid)), "Kid may no longer get into Ava's boat");
        assertFalse(j.notCollidable.contains(id(kid)), "Kid is collidable again");

        // Kid and Wes: never racers, never paid or counted
        for (Player p : List.of(kid, wes)) {
            assertEquals(0, j.rows("game_scores", "player", id(p)), p.getName() + ": no score");
            assertEquals(0, j.rows("game_rewards", "player", id(p)), p.getName() + ": no reward");
            assertEquals(0, j.rows("cup_entries", "player", id(p)), p.getName() + ": no Cup entry");
            assertEquals(0, j.told.courses(id(p)), p.getName() + ": no quest step");
            assertEquals(before.get(id(p)), j.bench.balance(id(p)), p.getName() + ": no tokens moved");
        }
        assertEquals(0, j.bench.severe(), "nothing threw: " + j.bench.severeLines());
    }
}
