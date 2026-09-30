package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.GamesBench;
import com.dierks.homecraft.games.NoPush;
import com.dierks.homecraft.games.clubhouse.ClubBoard;
import com.dierks.homecraft.games.clubhouse.ClubVisits;
import com.dierks.homecraft.games.clubhouse.Clubhouse;
import com.dierks.homecraft.games.clubhouse.ClubhouseSettings;
import com.dierks.homecraft.games.clubhouse.LiveRace;
import com.dierks.homecraft.games.clubhouse.WatchArea;
import com.dierks.homecraft.games.cup.live.CupSettings;
import com.dierks.homecraft.games.cup.live.WeeklyCup;
import com.dierks.homecraft.games.event.EventMachine;
import com.dierks.homecraft.games.event.NightBench;
import com.dierks.homecraft.games.event.NightRunner;
import com.dierks.homecraft.games.event.NightStandings;
import com.dierks.homecraft.games.event.PayLoop;
import com.dierks.homecraft.games.event.RaceNight;
import com.dierks.homecraft.games.event.RaceNightSettings;
import com.dierks.homecraft.games.world.WorldEntities;
import com.dierks.homecraft.storage.EventDao;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Journey 10: the server crashes in the middle of Race Night's race 2 with every role live (a racer
 * seated from the Clubhouse, a driver with a rider, a Cup entrant, a live watcher flying over the track,
 * a visitor with an auction Mini that arrived in the Clubhouse), then boots twice (the real night,
 * recovery, pay loop, world-session state machine and no-push team over one database; the crash drops
 * every in-memory thing: no leave or quit runs).
 *
 * <p>Pinned: the first boot calls the night off and settles it on race 1 (its prize slot kept), each
 * racer's prize is paid exactly once when they join, nobody else is paid; everyone comes home once with
 * their things once and their own game mode (the watcher's saved mode, not spectator; the visitor's Mini
 * banked because his own data says it was saved after the clear); the team the crash stranded is emptied;
 * a second boot finds nothing to do, pays nothing and applies nothing again.
 */
class CrossFeatureJourneyCrashTest {

    private static final long T0 = GamesBench.at(2026, 9, 29, 12, 0);
    private static final String NIGHT = "rn-20260929-1211";
    private static final Point STAND = new Point(0.5, 70, 0.5);

    private JourneyBench j;
    private Course loop;
    private NightRunner night;
    private Player ava;
    private Player ben;
    private Player cal;
    private Player kid;
    private Player wes;
    private Player vic;
    private List<Player> all;

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
        kid = j.player("Kid", "ADVENTURE", "blue");
        wes = j.player("Wes", "SURVIVAL", "red");
        vic = j.player("Vic", "SURVIVAL", "blue");
        all = List.of(ava, ben, cal, kid, wes, vic);
        assertNull(j.cup.desk().enter(id(cal), loop), "Cal enters this week's Cup");
        night = j.night(NIGHT, loop, T0 + 11 * JourneyBench.MIN, 60, STAND, j.new NightPortsBench());
    }

    @AfterEach
    void tearDown() throws Exception {
        j.close();
    }

    private static UUID id(Player p) {
        return p.getUniqueId();
    }

    private LiveRace live() {
        java.util.Set<UUID> racers = new java.util.LinkedHashSet<>();
        for (NightRunner.Racer r : night.joined()) {
            racers.add(r.id());
        }
        List<String> rows = new ArrayList<>();
        for (NightStandings.Ranked s : night.standings()) {
            rows.add(ClubBoard.pointsRow(s.place(), night.racer(s.player()).name(), s.points()));
        }
        return new LiveRace("night:" + NIGHT, "&6Race Night: &f" + loop.name(), loop.world(),
                WatchArea.forCourse(loop, null), racers, rows, "", true);
    }

    private void run(int maxTicks, BooleanSupplier done) {
        for (int i = 0; i < maxTicks && !done.getAsBoolean(); i++) {
            j.bench.move(50);
            night.tick();
            j.race.tick();
            if (j.race.tick % 20 == 0) {
                j.club.refresh(night.phase().running() ? List.of(live()) : List.of());
                j.club.second();
                j.riders.second();
            }
        }
    }

    private boolean racing(Player p) {
        TrialRun r = j.trials.run(id(p));
        return r != null && r.race != null && !r.warmup && r.phase == TrialRun.Phase.RUNNING && r.progress != null;
    }

    private Map<UUID, Integer> balances() {
        Map<UUID, Integer> out = new HashMap<>();
        for (Player p : all) {
            out.put(id(p), j.bench.balance(id(p)));
        }
        return out;
    }

    private long rewardRows() throws Exception {
        long n = 0;
        for (Player p : all) {
            n += j.rows("game_rewards", "player", id(p));
        }
        return n;
    }

    /** A boot: a new framework, a new no-push team over the same scoreboard (emptied), the night's recovery. */
    private int boot() {
        j.bench.reboot();
        j.pushes = new NoPush(() -> j.board);
        int stranded = j.pushes.clearAll(); // GamesService.start's first step
        j.wire(); // a new Time Trials, ride along, Clubhouse and Watch live: a crash forgets them all
        NightBench.recover((RaceNight) j.games.game(RaceNight.SPEC.id()), j.events());
        return stranded;
    }

    /** Everyone joins: the world sessions' join, the trip home, then RaceNight.onJoin's payOwed (mirrored). */
    private Map<UUID, Integer> joinAll(PayLoop pay) {
        Map<UUID, Integer> owed = new HashMap<>();
        for (Player p : all) {
            j.rail.joined(id(p));
        }
        j.rail.arriveAll();
        for (Player p : all) {
            owed.put(id(p), pay.payOwed(id(p)));
        }
        return owed;
    }

    @Test
    void aCrashInRaceTwoWithEveryRoleLiveBootsIntoOneSettlementAndOnePaymentAndEveryoneHomeOnce()
            throws Exception {
        Map<UUID, Integer> before = balances();
        EventDao dao = j.events();

        // the night: Ava waits in the Clubhouse, Ben takes Kid, Cal joins; Vic visits; Wes watches live
        run(2 * 60 * 20, () -> night.phase() == EventMachine.Phase.OPEN);
        for (Player p : List.of(ava, ben, cal)) {
            assertNull(night.join(id(p), p.getName()), p.getName() + " joins");
        }
        assertNull(j.club.enter(id(ava), ClubVisits.Kind.NIGHT, false), "Ava waits in the Clubhouse");
        assertNull(j.club.enter(id(wes), ClubVisits.Kind.VISIT, false), "Wes visits");
        assertNull(j.club.enter(id(vic), ClubVisits.Kind.VISIT, false), "Vic visits");
        j.riders.paired(j.view(id(ben)), j.view(id(kid)), loop.id(), null);
        run(12 * 60 * 20, () -> night.phase() == EventMachine.Phase.WARMUP && j.trials.run(id(ava)) != null
                && j.trials.run(id(ben)) != null && j.trials.run(id(cal)) != null);
        assertEquals(1, j.race.fromClub.get(id(ava)), "Ava was seated from the Clubhouse");
        j.club.refresh(List.of(live()));
        j.club.watching(id(wes), live());
        for (Player p : List.of(ava, ben, cal)) {
            j.race.ready(id(p));
        }

        // race 1 stored; race 2 racing
        run(800, () -> night.phase() == EventMachine.Phase.RACING && racing(ava) && racing(ben) && racing(cal));
        run(60, () -> false);
        long ms = 45_000;
        for (Player p : List.of(ava, ben, cal)) {
            j.race.cross(id(p), ms);
            ms += 1_000;
            run(5, () -> false);
        }
        run(40 * 20, () -> night.phase() == EventMachine.Phase.RACING && night.race() == 2 && racing(ava)
                && racing(ben) && racing(cal));
        assertEquals(2, night.race(), "race 2 is racing");
        assertEquals(1, dao.event(NIGHT).racesDone(), "race 1 is stored");
        assertTrue(j.rail.deliver(id(vic), "Mini #42"), "an auction Mini reaches Vic in the Clubhouse");
        assertTrue(j.rail.mark(id(vic)).startsWith("cleared:"), "Vic's own data says it was saved after the clear");
        assertEquals("SPECTATOR", j.rail.gameMode(id(wes)), "Wes flies over the track");
        Set<String> onTeam = Set.copyOf(j.board.entries(NoPush.TEAM));

        // 1. The crash: nothing runs; what memory held is gone
        j.rail.crash();
        WorldEntities.passenger(id(ben), null);

        // 2. Boot 1
        EventDao.EventRow row = dao.event(NIGHT);
        assertEquals(EventMachine.Boot.CALL_OFF_SETTLE, EventMachine.boot(row.state(), row.startsAt(), row.racesDone(),
                j.bench.now()), "a night that ran race 1 is called off and settled on it");
        int stranded = boot();
        assertEquals(Set.of("Kid", "Vic", "Wes"), onTeam, "who was on the team when it crashed: the rider and the"
                + " Clubhouse's visitors (boat racers never are)");
        assertEquals(onTeam.size(), stranded, "the boot finds them stranded on the no-push team");
        assertTrue(j.board.entries(NoPush.TEAM).isEmpty(), "and empties it");
        row = dao.event(NIGHT);
        assertEquals(EventDao.CALLED_OFF, row.state(), "the night is called off");
        assertTrue(row.prized(), "and keeps its prize slot");
        assertEquals(1, row.racesDone(), "settled on race 1");

        // 3. Everyone joins
        PayLoop pay = j.payLoop();
        Map<UUID, Integer> owed = joinAll(pay);
        for (Player p : all) {
            assertEquals(0, owed.get(id(p)), p.getName() + " is owed nothing after the join");
            assertNull(j.homeProblem(id(p)), p.getName() + " is home as they were: " + j.homeProblem(id(p)));
            assertEquals(1, j.rail.applies(id(p)), p.getName() + "'s state was put back once");
        }
        assertEquals("SURVIVAL", j.rail.gameMode(id(wes)), "Wes gets his saved mode, not spectator");
        assertEquals(1, j.rail.count(id(vic), "Mini #42"), "Vic's Mini came home (banked: it arrived after the clear)");
        assertEquals(before.get(id(ava)) + 5, j.bench.balance(id(ava)), "race 1's winner: 5");
        assertEquals(before.get(id(ben)) + 3, j.bench.balance(id(ben)), "2nd: 3");
        assertEquals(before.get(id(cal)) + 1, j.bench.balance(id(cal)), "3rd of 3: the finisher's 1");
        for (Player p : List.of(ava, ben, cal)) {
            assertEquals(1, j.rewards(id(p), "EVENT_PRIZE"), p.getName() + ": one EVENT_PRIZE");
        }
        for (Player p : List.of(kid, wes, vic)) {
            assertEquals(0, j.rows("game_rewards", "player", id(p)), p.getName() + ": no prize");
            assertEquals(before.get(id(p)), j.bench.balance(id(p)), p.getName() + ": no tokens moved");
        }
        assertNull(j.cupTime(id(cal), loop.id()), "Cal has no Cup time from the heats");
        for (EventDao.EntryRow e : dao.entries(NIGHT)) {
            assertNotNull(e.paidAt(), e.name() + "'s prize is recorded as paid");
        }

        // 4. Boot 2
        Map<UUID, Integer> paid = balances();
        long rewards = rewardRows();
        boot();
        assertEquals(EventDao.CALLED_OFF, dao.event(NIGHT).state(), "nothing to do: still called off");
        owed = joinAll(j.payLoop());
        for (Player p : all) {
            assertEquals(0, owed.get(id(p)), p.getName() + ": nothing owed");
            assertEquals(paid.get(id(p)), j.bench.balance(id(p)), p.getName() + ": no balance moved");
            assertEquals(1, j.rail.applies(id(p)), p.getName() + ": nothing applied again");
        }
        assertEquals(rewards, rewardRows(), "no reward row added");
        assertEquals(0, j.bench.severe(), "nothing threw: " + j.bench.severeLines());
    }
}
