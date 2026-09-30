package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.EndReason;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GamesBench;
import com.dierks.homecraft.games.NoPush;
import com.dierks.homecraft.games.Refusal;
import com.dierks.homecraft.games.Scores;
import com.dierks.homecraft.games.clubhouse.ClubVisits;
import com.dierks.homecraft.games.clubhouse.Clubhouse;
import com.dierks.homecraft.games.clubhouse.ClubhouseSettings;
import com.dierks.homecraft.games.clubhouse.LiveRace;
import com.dierks.homecraft.games.cup.CupKey;
import com.dierks.homecraft.games.cup.live.CupSettings;
import com.dierks.homecraft.games.cup.live.WeeklyCup;
import com.dierks.homecraft.games.event.EventMachine;
import com.dierks.homecraft.games.event.NightRunner;
import com.dierks.homecraft.games.event.RaceNight;
import com.dierks.homecraft.games.event.RaceNightSettings;
import com.dierks.homecraft.games.golf.GolfBench;
import com.dierks.homecraft.games.golf.MiniGolf;
import com.dierks.homecraft.games.golf.MiniGolfSettings;
import com.dierks.homecraft.games.world.Session;
import com.dierks.homecraft.games.world.WorldEntities;
import com.dierks.homecraft.storage.EventDao;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Journey 8: the games switched off ({@code games.enabled: false} and a reload) with people in every mode
 * at once: a Clubhouse visitor, a live watcher, a party race racing with a rider, Race Night in its break,
 * a golf group mid-round and a solo warm-up (the real games' stops and the world sessions' GAME_OFF;
 * a bench runs no game, so the reload's stop-then-switch-off per game, in catalog order, is replayed).
 *
 * <p>Pinned: everyone is home at once (a games-off trip is synchronous) with their things once, their own
 * game mode, speeds and xp; off the no-push team; seen by everyone; the ride over; Race Night called off
 * and settled on its stored race, paid once; the unfinished party race and golf round count for nothing
 * while the Cup entry stays; and afterwards nothing can start. Falling Floors (optional in the brief) is
 * left to its own tests.
 */
class CrossFeatureJourneyGamesOffTest {

    private static final long T0 = GamesBench.at(2026, 9, 29, 19, 30);
    private static final String NIGHT = "rn-20260929-1941";
    private static final Point STAND = new Point(0.5, 70, 0.5);
    private static final Course LAVA = new Course("lava_leap", TrialKind.PARKOUR, "Lava Leap", Tier.EASY, "games",
            new Course.Spot(0, 64, 0, 0, 0), List.of(new Course.Mark(10, 64, 0, 1.5)), new Course.Mark(20, 64, 0, 1.5),
            null, null, true, false, 1);

    private JourneyBench j;
    private GolfBench golf;
    private Course loop;
    private Course bay;
    private Course lava;
    private NightRunner night;
    private PartyRace party;

    @BeforeEach
    void setUp() throws Exception {
        j = new JourneyBench(T0, List.of(TimeTrials.SPEC, MiniGolf.SPEC, WeeklyCup.SPEC, RaceNight.SPEC, Clubhouse.SPEC),
                "trials", TimeTrialsSettings.defaults(), "golf", MiniGolfSettings.defaults(), "cup", CupSettings.defaults(),
                "race_night", RaceNightSettings.defaults(), "clubhouse", ClubhouseSettings.defaults());
        loop = j.loop();
        Course l = JourneyBench.LOOP;
        bay = j.course(new Course("bay", TrialKind.BOAT, "Bay", Tier.EASY, "games", l.start(), l.checkpoints(),
                l.finish(), l.fallY(), l.minSeconds(), true, false, 1));
        lava = j.course(LAVA);
        loop = j.trials.course(loop.id());
        j.cup.desk().dao().choose(loop.id(), true);
        golf = new GolfBench(j.games, j.rail, j.club, j.players::get);
        GolfBench.save(j.games, GolfBench.course("meadow", "Meadow Links", 3));
        night = j.night(NIGHT, bay, T0 + 11 * JourneyBench.MIN, 60, STAND, j.new NightPortsBench());
    }

    @AfterEach
    void tearDown() throws Exception {
        j.close();
    }

    private static UUID id(Player p) {
        return p.getUniqueId();
    }

    private boolean racing(Player p) {
        TrialRun r = j.trials.run(id(p));
        return r != null && r.race != null && !r.warmup && r.phase == TrialRun.Phase.RUNNING && r.progress != null;
    }

    private void run(int maxTicks, BooleanSupplier done) {
        for (int i = 0; i < maxTicks && !done.getAsBoolean(); i++) {
            j.bench.move(50);
            night.tick();
            j.race.tick();
            if (party != null) {
                party.tick(j.race.tick, false);
            }
            if (j.race.tick % 20 == 0) {
                j.club.refresh(ClubRaces.live(j.games));
                j.club.second();
                j.riders.second();
                golf.second();
            }
        }
    }

    /** GamesService.switchOff's session step: every session of {@code game} ends GAME_OFF, the player told. */
    private void switchOff(Game game) {
        for (UUID p : j.players.keySet()) {
            Session s = j.rail.session(p);
            if (s != null && game.id().equals(s.gameId())) {
                j.rail.leave(p, EndReason.GAME_OFF);
                j.games.tell(j.players.get(p), Refusal.CLOSED);
            }
        }
    }

    @Test
    void theGamesSwitchedOffWithEveryoneInEveryModeSendsEveryoneHomeAsTheyWereAndStartsNothing() throws Exception {
        Player ava = j.player("Ava", "SURVIVAL", "red");
        Player ben = j.player("Ben", "CREATIVE", "red");
        Player kid = j.player("Kid", "ADVENTURE", "blue");
        Player vic = j.player("Vic", "SURVIVAL", "blue");
        Player wes = j.player("Wes", "SURVIVAL", "red");
        Player cal = j.player("Cal", "SURVIVAL", "blue");
        Player dee = j.player("Dee", "ADVENTURE", "red");
        Player sam = j.player("Sam", "SURVIVAL", "red");
        Player lee = j.player("Lee", "CREATIVE", "blue");
        Player fay = j.player("Fay", "SURVIVAL", "red");
        List<Player> all = List.of(ava, ben, kid, vic, wes, cal, dee, sam, lee, fay);
        j.rail.speeds(id(ben), 0.3f, 0.2f);
        j.rail.xp(id(lee), 30, 0.5f, 1_500);
        Map<UUID, float[]> speeds = new HashMap<>();
        Map<UUID, int[]> xp = new HashMap<>();
        for (Player p : all) {
            speeds.put(id(p), new float[]{j.rail.walk(id(p)), j.rail.fly(id(p))});
            xp.put(id(p), new int[]{j.rail.level(id(p)), j.rail.totalXp(id(p))});
        }
        assertNull(j.cup.desk().enter(id(ava), loop), "Ava is a Cup entrant on the loop");
        int avaAfterCup = j.bench.balance(id(ava));

        // Vic and Wes in the Clubhouse; Ava waits there for her party; Kid will ride with Ben
        assertNull(j.club.enter(id(vic), ClubVisits.Kind.VISIT, false), "Vic visits");
        assertNull(j.club.enter(id(wes), ClubVisits.Kind.VISIT, false), "Wes visits");
        PartyLobby lobby = j.games.parties().create(PartyLobby.Kind.RACE, loop.id(), id(ava), 8);
        assertNull(j.games.parties().join(lobby.id(), id(ben)), "Ben joins");
        assertNull(j.club.enter(id(ava), ClubVisits.Kind.PARTY, false), "Ava waits in the Clubhouse");
        j.riders.paired(j.view(id(ben)), j.view(id(kid)), loop.id(), null);

        // Race Night on the bay opens; Cal and Dee join
        run(2 * 60 * 20, () -> night.phase() == EventMachine.Phase.OPEN);
        assertNull(night.join(id(cal), "Cal"), "Cal joins");
        assertNull(night.join(id(dee), "Dee"), "Dee joins");

        // a golf group on hole 2 of 3
        PartyLobby golfParty = j.games.parties().create(PartyLobby.Kind.GOLF, "meadow", id(sam), 4);
        assertNull(j.games.parties().join(golfParty.id(), id(lee)), "Lee joins Sam's golf party");
        assertNull(golfParty.start(id(sam)), "Sam starts");
        assertTrue(golf.start(golfParty.id(), "meadow", List.of(sam, lee)), "the group goes");
        j.rail.step();
        j.rail.arriveAll();
        golf.holeIn(id(sam), 3);
        golf.holeIn(id(lee), 4);
        golf.runTasks();
        assertEquals(1, golf.groupOf(id(sam)).hole(), "on hole 2");

        // the party race, racing (nobody will finish it)
        run(7 * 60 * 20, () -> j.bench.now() >= T0 + 8 * JourneyBench.MIN);
        assertNull(lobby.start(id(ava)), "Ava starts");
        party = new PartyRace(lobby.id(), loop, List.of(new PartyRace.Racer(id(ava), "Ava"),
                new PartyRace.Racer(id(ben), "Ben")), 0, () -> j.race.tick, line -> {
        });
        party.clubhouse(j.door.partyAfter());
        j.trials.party().running(party);
        List<Course.Spot> grid = RaceGrid.forCourse(loop, JourneyBench.ICE, 2).spots();
        assertNull(j.race.seat(id(ava), loop, Laps.raced(loop, grid.get(0), 0).course(), grid.get(0), null, party),
                "Ava from the Clubhouse");
        party.seated(id(ava), grid.get(0));
        assertNull(j.race.seat(id(ben), loop, Laps.raced(loop, grid.get(1), 0).course(), grid.get(1), null, party),
                "Ben from home, Kid behind him");
        party.seated(id(ben), grid.get(1));
        assertTrue(party.seatingDone(), "on the grid");
        run(20 * 20, () -> racing(ava) && racing(ben));
        assertTrue(racing(ava) && racing(ben), "the party race is racing");
        j.club.refresh(ClubRaces.live(j.games));
        LiveRace live = j.club.live().stream().filter(r -> r.key().equals("party:" + lobby.id())).findFirst()
                .orElseThrow();
        j.club.watching(id(wes), live);

        // Race Night's race 1, to its break: Cal and Dee on the stand
        run(12 * 60 * 20, () -> night.phase() == EventMachine.Phase.WARMUP && j.trials.run(id(cal)) != null
                && j.trials.run(id(dee)) != null);
        j.race.ready(id(cal));
        j.race.ready(id(dee));
        run(800, () -> night.phase() == EventMachine.Phase.RACING && racing(cal) && racing(dee));
        run(60, () -> false);
        j.race.cross(id(cal), 45_000);
        run(5, () -> false);
        j.race.cross(id(dee), 46_000);
        run(200, () -> night.phase() == EventMachine.Phase.BREAK && j.trials.run(id(dee)) != null
                && j.trials.run(id(dee)).phase == TrialRun.Phase.PARKED);
        assertEquals(EventMachine.Phase.BREAK, night.phase(), "the night's break");
        assertEquals(TrialRun.Phase.PARKED, j.trials.run(id(cal)).phase, "Cal waits on the stand");
        assertTrue(racing(ava) && racing(ben), "the party race is still racing");
        EventMachine.Phase phase = night.phase();

        // Fay's solo warm-up
        assertNull(j.rail.enterNow(id(fay), j.trials, lava.id(), JourneyBench.spot("games", lava.start()), q -> {
        }), "Fay at the lava course's start");
        TrialRun fayRun = new TrialRun(id(fay), lava, false, TimeTrials.COUNTDOWN_TICKS + 1);
        assertTrue(Warmup.begin(fayRun, j.race.tick, 180, lava.start().point(), j.race.nanos), "Fay warms up");
        j.trials.replaceRun(fayRun);
        for (Player p : all) {
            assertNotNull(j.rail.session(id(p)), p.getName() + " is in a game");
        }
        assertEquals("SPECTATOR", j.rail.gameMode(id(wes)), "Wes is watching live");

        // 1. games.enabled false, and the reload (the real call)
        j.bench.enabled(false);
        j.games.reload();

        // 2-3. Each game in catalog order: its stop, then its switch-off
        j.games.guard(j.trials, j.trials::stop); // SEAM S2: TimeTrials.stop's player lookups are guarded
        switchOff(j.trials);
        assertEquals(0, j.rail.trips(), "a games-off trip home is synchronous: nothing left on its way");
        Game golfGame = j.games.game(MiniGolf.SPEC.id());
        golf.stop(); // MiniGolf.stop (its rounds and parties) and the bench's group
        switchOff(golfGame);
        Game cup = j.games.game("cup");
        j.games.guard(cup, cup::stop);
        switchOff(cup);
        Game rn = j.games.game(RaceNight.SPEC.id());
        j.games.guard(rn, rn::stop);
        night.stopNow("&7Race Night was called off - it was switched off. Points so far count.",
                "Race Night stopped: it was switched off"); // RaceNight.stop, for the bench's night
        switchOff(rn);
        j.club.stop();
        switchOff(j.club.club());
        assertEquals(0, j.rail.trips(), "still nothing on its way");

        // every body: home, as they were
        for (Player p : all) {
            assertNull(j.homeProblem(id(p)), p.getName() + " is home as they were: " + j.homeProblem(id(p)));
            assertEquals(speeds.get(id(p))[0], j.rail.walk(id(p)), p.getName() + "'s walk speed");
            assertEquals(speeds.get(id(p))[1], j.rail.fly(id(p)), p.getName() + "'s fly speed");
            assertEquals(xp.get(id(p))[0], j.rail.level(id(p)), p.getName() + "'s level");
            assertEquals(xp.get(id(p))[1], j.rail.totalXp(id(p)), p.getName() + "'s xp");
            assertEquals(j.teams.get(id(p)), j.board.teamOf(p.getName()), p.getName() + " is back on their own team");
        }
        assertEquals("SURVIVAL", j.rail.gameMode(id(wes)), "Wes in survival, not spectator");
        assertEquals("ADVENTURE", j.rail.gameMode(id(kid)), "Kid in adventure");
        assertEquals("CREATIVE", j.rail.gameMode(id(ben)), "Ben in creative");
        assertTrue(j.board.entries(NoPush.TEAM).isEmpty(), "nobody on the no-push team");
        assertFalse(j.notCollidable.contains(id(kid)), "Kid is collidable");
        assertTrue(j.club.visibility().hiddenFrom(id(wes)).isEmpty(), "nobody is hidden from anyone");
        List<String> hides = new ArrayList<>(j.club.hides);
        List<String> shows = new ArrayList<>(j.club.shows);
        Collections.sort(hides);
        Collections.sort(shows);
        assertEquals(hides, shows, "every hide was matched by a show");
        assertTrue(j.riders.rides().isEmpty(), "no ride");
        assertFalse(WorldEntities.mayEnter(id(ben), id(kid)), "Kid may not get into Ben's boat");

        // Race Night: called off and settled on race 1, paid once
        EventDao dao = j.events();
        EventDao.EventRow row = dao.event(NIGHT);
        assertEquals(EventMachine.Boot.CALL_OFF_SETTLE, EventMachine.stop(phase, 1), "a break after race 1");
        assertEquals(EventDao.CALLED_OFF, row.state(), "the night is called off");
        assertEquals(1, row.racesDone(), "on its stored race");
        for (EventDao.EntryRow e : dao.entries(NIGHT)) {
            assertNotNull(e.paidAt(), e.name() + "'s prize is paid");
        }
        assertEquals(1, j.rewards(id(cal), "EVENT_PRIZE"), "Cal's prize, once");
        assertEquals(1, j.rewards(id(dee), "EVENT_PRIZE"), "Dee's prize, once");
        int calPaid = j.bench.balance(id(cal));
        assertEquals(0, j.payLoop().payNight(NIGHT), "a second pass owes nothing");
        assertEquals(calPaid, j.bench.balance(id(cal)), "and pays nothing");

        // the party race and the golf round: unfinished, nothing counted; the Cup entry stays
        assertNull(j.best(id(ava), Scores.course(loop.id())), "the unfinished party race counts for nothing");
        assertNull(j.best(id(ben), Scores.course(loop.id())), "for Ben neither");
        assertEquals(PartyLobby.State.CLOSED, lobby.state(), "the race lobby is closed");
        assertNotNull(j.cup.desk().dao().entry(new CupKey(loop.id(), j.cup.desk().week()), id(ava)),
                "Ava's Cup entry is untouched");
        assertNull(j.cup.desk().dao().settledAs(new CupKey(loop.id(), j.cup.desk().week())), "the Cup isn't called off");
        assertEquals(avaAfterCup, j.bench.balance(id(ava)), "nor refunded: it settles at its own rollover");
        assertTrue(golf.recorded.isEmpty(), "the unfinished golf round is not recorded");
        assertEquals(0, j.rows("game_scores", "player", id(sam)), "no golf score");

        // afterwards nothing starts
        for (String gameId : List.of(TimeTrials.SPEC.id(), MiniGolf.SPEC.id(), RaceNight.SPEC.id(),
                Clubhouse.SPEC.id())) {
            assertEquals(Refusal.CLOSED, j.games.sessions().entryRefusal(j.games.game(gameId)), gameId + " is closed");
        }
        assertNull(Clubhouse.door(j.games), "no Clubhouse door");
        assertFalse(RideAlong.offered(j.games, TrialKind.BOAT), "no Take a rider");
        assertNotNull(j.trials.party().startProblem(ava), "no party race start");
        assertEquals(0, j.bench.severe(), "nothing threw: " + j.bench.severeLines());
    }
}
