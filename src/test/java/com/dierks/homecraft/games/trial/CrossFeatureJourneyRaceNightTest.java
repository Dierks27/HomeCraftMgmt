package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.GamesBench;
import com.dierks.homecraft.games.NoPush;
import com.dierks.homecraft.games.Scores;
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
import com.dierks.homecraft.games.world.SessionBench;
import com.dierks.homecraft.storage.EventDao;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.util.ArrayList;
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
 * Journey 2: a whole Race Night with a rider and a watcher, ending in the Clubhouse with the podium, its
 * prizes paid once; then a crash with everyone still in the Clubhouse, and a crash inside the pay window,
 * whose boots re-pay nothing ({@link JourneyBench}: the real night, race mode, world sessions, Clubhouse,
 * ride along and rewards; only the server's I/O is mirrored).
 *
 * <p>Ava, Ben and Cal (a Cup entrant) join; Ava waits in the Clubhouse; Wes visits it and watches live;
 * Kid rides in the back of Ben's boat. Three races (Ava 28, Ben 26, Cal 18), the breaks on the stand,
 * the re-grids by points, then everyone to the Clubhouse, the board and the podium. A crash with the
 * results up (the night DONE) and a crash while the night was paying (SETTLING, one prize's
 * {@code paid_at} lost) are both booted with a fresh framework, a fresh state machine and a fresh no-push
 * team over the same database and scoreboard.
 */
class CrossFeatureJourneyRaceNightTest {

    private static final long T0 = GamesBench.at(2026, 9, 29, 12, 0);
    private static final String NIGHT = "rn-20260929-1211";
    private static final Point STAND = new Point(0.5, 70, 0.5);

    private JourneyBench j;
    private Course loop;
    private NightRunner night;
    private JourneyBench.NightPortsBench ports;
    private Player ava;
    private Player ben;
    private Player cal;
    private Player kid;
    private Player wes;
    /** Feed the night's live race to the Clubhouse (ClubNight.live, mirrored: a bench night isn't RaceNight's). */
    private boolean feeding = true;

    @BeforeEach
    void setUp() throws Exception {
        j = new JourneyBench(T0, List.of(TimeTrials.SPEC, WeeklyCup.SPEC, RaceNight.SPEC, Clubhouse.SPEC), "trials",
                TimeTrialsSettings.defaults(), "cup", new CupSettings(true, 5, 10) /* 0.36's Cup: 5 in, 10 on top */, "race_night", RaceNightSettings.defaults(),
                "clubhouse", ClubhouseSettings.defaults());
        loop = j.loop();
        j.cup.desk().dao().choose(loop.id(), true);
        ava = j.player("Ava", "SURVIVAL", "red");
        ben = j.player("Ben", "CREATIVE", "red");
        cal = j.player("Cal", "SURVIVAL", "blue");
        kid = j.player("Kid", "ADVENTURE", "blue");
        wes = j.player("Wes", "SURVIVAL", "red");
        assertNull(j.cup.desk().enter(id(cal), loop), "Cal enters this week's Cup on the loop");
        ports = j.new NightPortsBench();
        night = j.night(NIGHT, loop, T0 + 11 * JourneyBench.MIN, 60, STAND, ports);
    }

    @AfterEach
    void tearDown() throws Exception {
        j.close();
    }

    private static UUID id(Player p) {
        return p.getUniqueId();
    }

    private LiveRace live() {
        List<String> rows = new ArrayList<>();
        List<String> names = new ArrayList<>();
        java.util.Set<UUID> racers = new java.util.LinkedHashSet<>();
        for (NightRunner.Racer r : night.joined()) {
            racers.add(r.id());
        }
        for (NightStandings.Ranked s : night.standings()) {
            NightRunner.Racer r = night.racer(s.player());
            names.add(r == null ? null : r.name());
            rows.add(ClubBoard.pointsRow(s.place(), r == null ? null : r.name(), s.points()));
        }
        return new LiveRace("night:" + NIGHT, "&6Race Night: &f" + loop.name(), loop.world(),
                WatchArea.forCourse(loop, null), racers, rows, LiveRace.positions(names, 5), true);
    }

    private void second() {
        j.club.refresh(feeding && night.phase().running() ? List.of(live()) : List.of());
        j.club.second();
        j.riders.second();
    }

    private void run(int maxTicks, BooleanSupplier done) {
        for (int i = 0; i < maxTicks && !done.getAsBoolean(); i++) {
            j.bench.move(50);
            night.tick();
            j.race.tick();
            if (j.race.tick % 20 == 0) {
                second();
            }
        }
    }

    private boolean racing(Player p) {
        TrialRun r = j.trials.run(id(p));
        return r != null && r.race != null && !r.warmup && r.phase == TrialRun.Phase.RUNNING && r.progress != null;
    }

    private void raceIt(Player... order) {
        run(800, () -> night.phase() == EventMachine.Phase.RACING && racing(order[0]) && racing(order[1])
                && racing(order[2]));
        assertEquals(EventMachine.Phase.RACING, night.phase(), "race " + night.race() + " is off");
        run(60, () -> false);
        long ms = 45_000;
        for (Player p : order) {
            j.race.cross(id(p), ms);
            ms += 1_000;
            run(5, () -> false);
        }
    }

    private SessionBench.Spot stand() {
        return new SessionBench.Spot("games", STAND.x(), STAND.y(), STAND.z());
    }

    private boolean kidBehindBen() {
        var boat = j.boats.get(id(ben));
        return boat != null && j.seats.getOrDefault(boat, List.of()).contains(id(kid));
    }

    private void assertBalances(Map<UUID, Integer> expected, String why) {
        for (Map.Entry<UUID, Integer> e : expected.entrySet()) {
            assertEquals(e.getValue(), j.bench.balance(e.getKey()), j.players.get(e.getKey()).getName() + ": " + why);
        }
    }

    private Map<UUID, Integer> balances() {
        Map<UUID, Integer> out = new HashMap<>();
        for (Player p : List.of(ava, ben, cal, kid, wes)) {
            out.put(id(p), j.bench.balance(id(p)));
        }
        return out;
    }

    @Test
    void aRaceNightWithARiderAndAWatcherEndsOnThePodiumPaysOnceAndItsCrashBootsPayNothingMore()
            throws Exception {
        Map<UUID, Integer> before = balances();

        // 1. The night opens; Ava, Ben and Cal join; Ava waits in the Clubhouse; Wes visits; Kid rides with Ben
        run(2 * 60 * 20, () -> night.phase() == EventMachine.Phase.OPEN);
        for (Player p : List.of(ava, ben, cal)) {
            assertNull(night.join(id(p), p.getName()), p.getName() + " joins");
        }
        assertNull(j.club.enter(id(ava), ClubVisits.Kind.NIGHT, false), "Ava waits in the Clubhouse");
        assertNull(j.club.enter(id(wes), ClubVisits.Kind.VISIT, false), "Wes visits the Clubhouse");
        String avaSid = j.rail.session(id(ava)).id();
        assertNull(RideAlong.driverRefusal(j.games, j.trials, ben, loop.id(), RideAlong.Purpose.NIGHT),
                "Race Night takes riders: rider_runs_count is on");
        assertNull(RideAlong.riderRefusal(j.games, j.trials, ben, kid, RideAlong.Purpose.NIGHT), "Kid may ride");
        j.riders.paired(j.view(id(ben)), j.view(id(kid)), loop.id(), null);

        // 2. Last call holds the track; everyone is seated into the shared warm-up
        run(12 * 60 * 20, () -> night.phase() == EventMachine.Phase.WARMUP && j.trials.run(id(ava)) != null
                && j.trials.run(id(ben)) != null && j.trials.run(id(cal)) != null);
        assertEquals(EventMachine.Phase.WARMUP, night.phase(), "the shared warm-up");
        assertEquals(1, j.race.fromClub.get(id(ava)), "Ava was seated from the Clubhouse");
        assertNull(j.race.fromClub.get(id(ben)), "Ben from home");
        assertNull(j.race.fromClub.get(id(cal)), "Cal from home");
        assertEquals(avaSid, j.rail.session(id(ava)).id(), "Ava's Clubhouse session is the race's: one session");
        assertEquals("trials", j.rail.session(id(ben)).gameId(), "Ben is in a Time Trials session");
        assertTrue(kidBehindBen(), "Kid sits behind Ben");
        String kidSid = j.rail.session(id(kid)).id();

        // 3. Wes watches the night live
        second();
        LiveRace lr = j.club.live().stream().filter(r -> r.key().equals("night:" + NIGHT)).findFirst().orElseThrow();
        j.club.watching(id(wes), lr);
        assertEquals("SPECTATOR", j.rail.gameMode(id(wes)), "Wes watches in spectator mode");

        // 4. Warm-up (Cal's lap counts for nothing), then three races with breaks on the stand
        j.race.cross(id(cal), 42_000);
        assertNull(j.best(id(cal), Scores.course(loop.id())), "a warm-up lap never reaches a board");
        for (Player p : List.of(ava, ben, cal)) {
            j.race.ready(id(p));
        }
        raceIt(ava, ben, cal); // 10, 8, 6
        run(200, () -> night.phase() == EventMachine.Phase.BREAK && j.trials.run(id(ben)) != null
                && j.trials.run(id(ben)).phase == TrialRun.Phase.PARKED);
        assertEquals(EventMachine.Phase.BREAK, night.phase(), "the break");
        assertEquals(stand(), j.rail.place(id(kid)), "Kid stands on the stand with Ben at the break");
        assertEquals(stand(), j.rail.place(id(ben)), "Ben is on the stand");
        run(30 * 20, () -> List.of(ava, ben, cal).stream().allMatch(p -> j.race.regrids.getOrDefault(id(p), 0) == 2));
        assertTrue(kidBehindBen(), "Kid sits behind Ben again after the re-grid");
        raceIt(ava, ben, cal); // 20, 16, 12
        run(200, () -> night.phase() == EventMachine.Phase.BREAK && j.trials.run(id(ben)) != null
                && j.trials.run(id(ben)).phase == TrialRun.Phase.PARKED);
        assertEquals(stand(), j.rail.place(id(kid)), "Kid stands on the stand with Ben at the second break");
        run(30 * 20, () -> List.of(ava, ben, cal).stream().allMatch(p -> j.race.regrids.getOrDefault(id(p), 0) == 3));
        assertTrue(kidBehindBen(), "Kid sits behind Ben after the second re-grid");
        assertEquals(kidSid, j.rail.session(id(kid)).id(), "one rider session all night");
        raceIt(ben, ava, cal); // Ava 28, Ben 26, Cal 18

        // 5. The end: everyone to the Clubhouse, the board and the podium; Wes is brought back
        run(400, () -> night.phase().over() && j.trials.run(id(ava)) == null && j.trials.run(id(ben)) == null
                && j.trials.run(id(cal)) == null);
        assertEquals(EventMachine.Phase.DONE, night.phase(), "three races: done");
        feeding = false;
        second();
        assertEquals(List.of(id(ava), id(ben), id(cal)), j.door.podium, "the podium in the night's own order");
        ClubBoard.Sheet sheet = j.door.last();
        assertTrue(sheet.rows().get(0).contains("Ava") && sheet.rows().get(0).contains("28 pts"),
                "the board's row 1 is Ava with 28 points: " + sheet.rows());
        for (Player p : List.of(ava, ben, cal, kid)) {
            assertNotNull(j.club.visits().get(id(p)), p.getName() + " is in the Clubhouse");
            assertEquals(ClubVisits.Kind.NIGHT, j.club.visits().get(id(p)).kind(), p.getName() + " as a Race Night racer");
        }
        assertEquals(ClubVisits.Kind.VISIT, j.club.visits().get(id(wes)).kind(), "Wes is visiting");
        assertFalse(j.club.watchingNow(id(wes)), "Wes was brought back when the night stopped being live");
        assertEquals("ADVENTURE", j.rail.gameMode(id(wes)), "in the games' mode again");
        assertTrue(j.race.home.isEmpty(), "nobody went home: " + j.race.home.keySet());
        assertEquals(1, j.race.fromClub.get(id(ava)), "Ava was seated from the Clubhouse once");

        // prizes: 5 / 3 / 1 (3rd needs 4 racers), once - the bench's night runs at its own 5/3/2 + 1
        // (JourneyBench.night), whatever games.race_night ships
        assertEquals(before.get(id(ava)) + 5, j.bench.balance(id(ava)), "Ava won the night: 5");
        assertEquals(before.get(id(ben)) + 3, j.bench.balance(id(ben)), "Ben 2nd: 3");
        assertEquals(before.get(id(cal)) + 1, j.bench.balance(id(cal)), "Cal: the finisher's 1 (3rd needs 4 racers)");
        assertEquals(20 - 5 + 1, j.bench.balance(id(cal)), "Cal paid 5 for his Cup entry, and it stays in the Cup");
        EventDao dao = j.events();
        for (EventDao.EntryRow e : dao.entries(NIGHT)) {
            assertNotNull(e.paidAt(), e.name() + "'s prize is recorded as paid");
        }
        for (Player p : List.of(ava, ben, cal)) {
            assertEquals(1, j.rewards(id(p), "EVENT_PRIZE"), p.getName() + ": one EVENT_PRIZE row");
        }
        Map<UUID, Integer> paid = balances();
        assertEquals(0, j.payLoop().payNight(NIGHT), "a second pay pass owes nothing");
        assertBalances(paid, "no balance moved on the second pass");

        // the heats
        for (Player p : List.of(ava, ben, cal)) {
            assertNull(j.best(id(p), Scores.course(loop.id())), p.getName() + ": a heat never goes on the course's board");
            assertNull(j.best(id(p), Scores.week(loop.id(), j.trials.weekKey())), p.getName() + ": nor the week's");
            assertEquals(3, j.told.courses(id(p)), p.getName() + ": each finished race counts once (FINISH_COURSE)");
            assertEquals(1, j.told.nights.get(id(p)).size(), p.getName() + ": the night counts once");
        }
        assertNull(j.trials.record(loop.id()), "no course record from a heat");
        assertNull(j.cupTime(id(cal), loop.id()), "no Cup time for Cal from the heats, though he entered");
        assertEquals(List.of(true), j.told.nights.get(id(ava)), "Ava won");
        assertEquals(List.of(false), j.told.nights.get(id(ben)), "Ben didn't");

        // Kid and Wes: never racers
        for (Player p : List.of(kid, wes)) {
            assertFalse(night.joined().stream().anyMatch(r -> r.id().equals(id(p))), p.getName() + " never joined");
            assertFalse(night.standings().stream().anyMatch(s -> s.player().equals(id(p))), p.getName()
                    + " never in the standings");
            assertEquals(0, j.rows("game_rewards", "player", id(p)), p.getName() + ": no reward");
            assertEquals(0, j.rows("game_scores", "player", id(p)), p.getName() + ": no score");
            assertEquals(0, j.rows("cup_entries", "player", id(p)), p.getName() + ": no Cup entry");
            assertNull(j.told.nights.get(id(p)), p.getName() + ": no night counted");
            assertEquals(before.get(id(p)), j.bench.balance(id(p)), p.getName() + ": no tokens moved");
        }
        assertEquals(0, j.bench.severe(), "nothing threw: " + j.bench.severeLines());

        // 6. A crash with everyone in the Clubhouse (the results up, the night DONE)
        Map<UUID, Integer> atCrash = balances();
        j.rail.crash();
        for (Player p : List.of(ava, ben, cal, kid, wes)) {
            j.rail.joined(id(p));
        }
        j.rail.arriveAll();
        j.bench.reboot();
        j.pushes = new NoPush(() -> j.board);
        int stranded = j.pushes.clearAll(); // GamesService.start's first step
        j.wire();
        NightBench.recover((RaceNight) j.games.game(RaceNight.SPEC.id()), dao);
        assertEquals(0, j.payLoop().payNight(NIGHT), "nothing is owed after the boot");
        for (Player p : List.of(ava, ben, cal, kid, wes)) {
            assertNull(j.homeProblem(id(p)), p.getName() + " is home as they were after the crash: "
                    + j.homeProblem(id(p)));
            assertEquals(1, j.rail.applies(id(p)), p.getName() + "'s state was put back once");
        }
        assertEquals("SURVIVAL", j.rail.gameMode(id(wes)), "Wes gets his own mode back");
        assertEquals(5, stranded, "the boot found the five left on the no-push team");
        assertTrue(j.board.entries(NoPush.TEAM).isEmpty(), "and emptied it");
        assertEquals(EventDao.DONE, dao.event(NIGHT).state(), "the boot leaves a DONE night alone");
        assertBalances(atCrash, "the boot paid nothing");

        // 7. A crash inside the pay window: Ava's paid_at lost, the night SETTLING
        try (PreparedStatement ps = j.bench.connection().prepareStatement(
                "UPDATE game_event_entries SET paid_at = NULL WHERE event_id = ? AND player = ?")) {
            ps.setString(1, NIGHT);
            ps.setString(2, id(ava).toString());
            assertEquals(1, ps.executeUpdate(), "Ava's prize looks unpaid");
        }
        dao.setState(NIGHT, EventDao.SETTLING, "", null);
        j.bench.reboot();
        j.wire();
        NightBench.recover((RaceNight) j.games.game(RaceNight.SPEC.id()), dao);
        assertEquals(EventDao.DONE, dao.event(NIGHT).state(), "FINISH_PAYING: the night is DONE");
        assertTrue(dao.unpaid(NIGHT).stream().anyMatch(e -> e.player().equals(id(ava))),
                "RaceNight's own payer finds nobody online on a bench: Ava is owed");
        PayLoop pay = j.payLoop();
        assertEquals(0, pay.payNight(NIGHT), "the bench's pass owes nothing: Ava is online");
        EventDao.EntryRow avas = dao.entries(NIGHT).stream().filter(e -> e.player().equals(id(ava))).findFirst()
                .orElseThrow();
        assertNotNull(avas.paidAt(), "Ava's paid_at is set again");
        assertBalances(atCrash, "the ref was refused: Ava was not paid twice");
        assertEquals(1, j.rewards(id(ava), "EVENT_PRIZE"), "still one EVENT_PRIZE row for Ava");
        assertEquals(0, j.bench.severe(), "nothing threw: " + j.bench.severeLines());
        assertEquals(0, j.rail.severe(), "the world sessions logged nothing severe: " + j.rail.logged());
    }
}
