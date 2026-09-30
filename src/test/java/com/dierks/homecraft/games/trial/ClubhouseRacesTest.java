package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.games.EndReason;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GamesBench;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.RewardKind;
import com.dierks.homecraft.games.Scores;
import com.dierks.homecraft.games.clubhouse.ClubBoard;
import com.dierks.homecraft.games.clubhouse.ClubDoor;
import com.dierks.homecraft.games.clubhouse.ClubVisits;
import com.dierks.homecraft.games.event.Announcer;
import com.dierks.homecraft.games.event.ClubNight;
import com.dierks.homecraft.games.event.EventMachine;
import com.dierks.homecraft.games.event.EventPlan;
import com.dierks.homecraft.games.event.NightPorts;
import com.dierks.homecraft.games.event.NightRules;
import com.dierks.homecraft.games.event.NightRunner;
import com.dierks.homecraft.games.event.NightStandings;
import com.dierks.homecraft.games.event.PayLoop;
import com.dierks.homecraft.games.event.RaceNight;
import com.dierks.homecraft.games.event.RaceNightSettings;
import com.dierks.homecraft.storage.EventDao;
import com.dierks.homecraft.storage.GamesDao;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Clubhouse in a party race and a Race Night, start to finish (CLUBHOUSE-SPEC §2, §3, §7, §8),
 * with the Clubhouse ON and OFF, on the real race mode, framework and database ({@link RaceBench}
 * mirrors only the server I/O). The Clubhouse is a fake {@link ClubDoor}: who is in it, and what it
 * was told.
 *
 * <p>Pinned: waiting in the Clubhouse, Start seats them from there (a member who isn't is seated as
 * today); a finisher goes to the Clubhouse instead of home; "Race again" seats everyone from it; a
 * spectator is never seated; each finish is a normal run exactly once and the Clubhouse pays nothing;
 * Race Night seats a waiter from the Clubhouse and at its end sends everyone there, its top three to
 * the podium in the night's own order; and with the Clubhouse off, every racer goes home exactly as
 * before.
 */
class ClubhouseRacesTest {

    private static final long T0 = GamesBench.at(2026, 9, 29, 12, 0);
    private static final long MIN = 60_000L;
    private static final String NIGHT = "rn-20260929-1215";
    private static final Course BUILT = LapsTest.loop(6, 2);
    private static final RaceGrid.Surface ICE = (x, y, z) -> y < 65 ? RaceGrid.Cell.SOLID : RaceGrid.Cell.AIR;
    private static final Point STAND = new Point(0.5, 70, 0.5);

    /** The Clubhouse as race mode sees it: who is in, and what it heard. */
    static final class FakeDoor implements ClubDoor {
        final Map<UUID, ClubVisits.Kind> in = new LinkedHashMap<>();
        final Set<UUID> spectators = new LinkedHashSet<>();
        final Map<UUID, String> lines = new LinkedHashMap<>();
        final List<UUID> handedOut = new ArrayList<>();
        ClubBoard.Sheet result;
        List<UUID> podium;
        boolean open = true;
        /** Closing for a restart ({@link #closingForRestart}: the hold's last 66 s and the restart's own minute). */
        boolean closing;

        @Override
        public String world() {
            return BUILT.world();
        }

        @Override
        public boolean seatable(UUID player) {
            return in.containsKey(player);
        }

        @Override
        public boolean spectator(UUID player) {
            return spectators.contains(player);
        }

        @Override
        public boolean handOut(Player p, Game to, String ref) {
            if (in.remove(p.getUniqueId()) == null) {
                return false;
            }
            handedOut.add(p.getUniqueId());
            return true;
        }

        @Override
        public void handBack(Player p, ClubVisits.Kind kind) {
            in.put(p.getUniqueId(), kind);
        }

        @Override
        public boolean takeIn(Player p, ClubVisits.Kind kind, String line) {
            if (!open) { // not refused while closing: what is pinned is that the races ask closingForRestart first
                return false;
            }
            in.put(p.getUniqueId(), kind);
            lines.put(p.getUniqueId(), line == null ? "" : line);
            return true;
        }

        @Override
        public boolean closingForRestart() {
            return closing;
        }

        @Override
        public boolean partyAfter() {
            return true;
        }

        @Override
        public boolean nightAfter() {
            return true;
        }

        @Override
        public boolean golfAfter() {
            return true;
        }

        @Override
        public void result(ClubBoard.Sheet sheet, Consumer<Player> opener) {
            result = sheet;
        }

        @Override
        public void podium(List<UUID> topThree) {
            podium = List.copyOf(topThree);
        }
    }

    private Course loop;
    private GamesBench bench;
    private GamesService games;
    private TimeTrials trials;
    private RaceBench race;
    private final RaceBench.Told told = new RaceBench.Told();
    private final FakeDoor door = new FakeDoor();
    private Player ava;
    private Player ben;
    private Player cal;

    @BeforeEach
    void setUp() throws Exception {
        bench = new GamesBench(T0, List.of(TimeTrials.SPEC, RaceNight.SPEC), "trials", TimeTrialsSettings.defaults(),
                "race_night", RaceNightSettings.defaults());
        games = bench.games();
        games.progress(told);
        trials = (TimeTrials) games.game("trials");
        bench.dao().saveCourse(new GamesDao.CourseRow(BUILT.id(), "trials", BUILT.kind().id(), BUILT.name(),
                BUILT.world(), BUILT.enabled(), CourseCodec.encode(BUILT), BUILT.rev(), 0, 0), false);
        loop = trials.course(BUILT.id());
        race = new RaceBench(trials);
        ava = bench.player("Ava");
        ben = bench.player("Ben");
        cal = bench.player("Cal");
        for (Player p : List.of(ava, ben, cal)) {
            race.add(p);
            bench.give(p.getUniqueId(), 20);
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        bench.close();
    }

    private UUID id(Player p) {
        return p.getUniqueId();
    }

    private void clubOn() {
        race.door = door;
        trials.raceMode().door(door);
    }

    private void clubOff() {
        race.door = null;
        trials.raceMode().door(null);
    }

    private Long best(Player p) throws SQLException {
        return bench.dao().best(id(p), TimeTrials.SPEC.id(), Scores.course(loop.id()));
    }

    // ---- a party race -----------------------------------------------------------------------------

    /**
     * PartyRaces.start and the race to its end, as the live code runs it: members who are spectators
     * skipped, everyone else seated (from the Clubhouse when they are in it), the Clubhouse set on the
     * race when it is open, then finishes in {@code order}; the racers still on the race at its end
     * go to the Clubhouse (or home) as PartyRaces.finish sends them.
     */
    private PartyRace raceOnce(List<Player> members, List<Player> order, int seconds) {
        ClubDoor club = trials.raceMode().door();
        List<PartyRace.Racer> racers = new ArrayList<>();
        for (Player p : members) {
            if (club != null && club.spectator(id(p))) {
                continue; // PartyRaces.start: a spectator is never seated
            }
            racers.add(new PartyRace.Racer(id(p), p.getName()));
        }
        PartyRace party = new PartyRace(7, loop, racers, 0, () -> race.tick, line -> { });
        party.clubhouse(club != null && club.partyAfter() && loop.world().equalsIgnoreCase(club.world()));
        List<Course.Spot> grid = RaceGrid.forCourse(loop, ICE, racers.size()).spots();
        for (int i = 0; i < racers.size(); i++) {
            UUID id = racers.get(i).id();
            assertNull(race.seat(id, loop, Laps.raced(loop, grid.get(i), 0).course(), grid.get(i), null, party),
                    racers.get(i).name() + " is seated");
            party.seated(id, grid.get(i));
        }
        assertTrue(party.seatingDone(), "the race is on");
        int next = 0;
        boolean ended = false;
        for (int i = 0; i < 20 * seconds && !ended; i++) {
            bench.move(50);
            race.tick();
            if (party.tick(race.tick) == PartyRace.Step.END) {
                ended = true;
            } else if (party.state() == PartyRace.State.RACING && next < order.size() && race.tick % 40 == 0) {
                race.cross(id(order.get(next)), 45_000 + next * 1_000L);
                next++;
            }
        }
        if (!ended) {
            party.end();
        }
        for (PartyRace.Racer r : racers) { // PartyRaces.finish: whoever is still on this race
            TrialRun run = trials.run(r.id());
            if (run != null && run.race != null && run.race.link == party) {
                if (party.clubhouseAfter()) {
                    trials.endRaceToClubhouse(r.id(), EndReason.FINISH, "&7Race over - great racing!");
                } else {
                    trials.endRace(r.id(), EndReason.FINISH, "&7Race over - great racing!");
                }
            }
        }
        for (int i = 0; i < 5; i++) {
            race.tick();
        }
        return party;
    }

    @Test
    void waitInTheClubhouseStartGridFinishBackInTheClubhouseRaceAgainGrid() throws Exception {
        clubOn();
        door.in.put(id(ava), ClubVisits.Kind.PARTY); // "Go to the Clubhouse"
        door.in.put(id(ben), ClubVisits.Kind.PARTY);
        int[] before = {bench.balance(id(ava)), bench.balance(id(ben)), bench.balance(id(cal))};

        raceOnce(List.of(ava, ben, cal), List.of(ava, ben, cal), 180);
        assertEquals(1, race.fromClub.get(id(ava)), "Ava was seated from the Clubhouse");
        assertEquals(1, race.fromClub.get(id(ben)), "so was Ben");
        assertNull(race.fromClub.get(id(cal)), "Cal wasn't in the Clubhouse: seated from home, as today");
        for (Player p : List.of(ava, ben, cal)) {
            assertEquals(ClubVisits.Kind.PARTY, door.in.get(id(p)), p.getName() + " is back in the Clubhouse, not home");
            assertFalse(race.home.containsKey(id(p)), p.getName() + " never went home");
            assertNull(trials.run(id(p)), p.getName() + "'s race run is over");
            assertEquals(1, told.courses(id(p)), p.getName() + ": the finish is a normal run, once");
        }
        assertEquals(45_000L, best(ava), "Ava's time is on the course's board");

        // "Race again": the host starts from the Clubhouse, and everyone in it goes to the grid
        raceOnce(List.of(ava, ben, cal), List.of(cal, ben, ava), 180);
        assertEquals(2, race.fromClub.get(id(ava)), "Ava again, from the Clubhouse");
        assertEquals(1, race.fromClub.get(id(cal)), "Cal too, this time");
        for (Player p : List.of(ava, ben, cal)) {
            assertTrue(door.in.containsKey(id(p)), p.getName() + " back in the Clubhouse after race 2");
            assertEquals(2, told.courses(id(p)), p.getName() + ": one normal run a race");
        }
        for (Player p : List.of(ava, ben, cal)) {
            List<String> kinds = new ArrayList<>();
            try (var ps = bench.connection().prepareStatement("SELECT kind FROM game_rewards WHERE player = ?")) {
                ps.setString(1, id(p).toString());
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        kinds.add(rs.getString(1));
                    }
                }
            }
            assertFalse(kinds.contains("EVENT_PRIZE"), "the Clubhouse pays nothing and adds no prize: " + kinds);
        }
        int ben2 = bench.balance(id(ben)) - before[1];
        int cal2 = bench.balance(id(cal)) - before[2];
        assertEquals(ben2, cal2, "the same normal rewards whether you waited in the Clubhouse or not");
        assertEquals(0, bench.severe(), "nothing threw");
    }

    @Test
    void aSpectatorIsNeverSeatedCountedOrPaid() throws Exception {
        clubOn();
        door.in.put(id(ava), ClubVisits.Kind.PARTY);
        door.in.put(id(cal), ClubVisits.Kind.VISIT);
        door.spectators.add(id(cal)); // the party screen's Watch
        int calBefore = bench.balance(id(cal));
        PartyRace party = raceOnce(List.of(ava, ben, cal), List.of(ava, ben), 180);
        assertFalse(party.racers().contains(id(cal)), "never in the party's racer list");
        assertNull(race.fromClub.get(id(cal)), "never seated");
        assertTrue(door.in.containsKey(id(cal)), "still watching from the Clubhouse");
        assertEquals(0, told.courses(id(cal)), "never counted");
        assertNull(best(cal), "never on a board");
        assertEquals(calBefore, bench.balance(id(cal)), "never paid");
    }

    @Test
    void withTheClubhouseOffAPartyRaceIsExactlyAsBefore() throws Exception {
        clubOff();
        raceOnce(List.of(ava, ben, cal), List.of(ava, ben, cal), 180);
        for (Player p : List.of(ava, ben, cal)) {
            assertTrue(race.home.containsKey(id(p)), p.getName() + " went home at the line, as always");
            assertEquals(1, told.courses(id(p)), p.getName() + ": one normal run");
        }
        assertTrue(race.fromClub.isEmpty(), "nobody from a Clubhouse");
        assertTrue(door.in.isEmpty(), "nobody taken to one");
    }

    @Test
    void withTheClubhouseClosedMeanwhileRacersGoHomeAsBefore() throws Exception {
        clubOn();
        door.open = false; // it closed (a failed check) after the race started: takeIn says no
        raceOnce(List.of(ava, ben), List.of(ava, ben), 180);
        for (Player p : List.of(ava, ben)) {
            assertTrue(race.home.containsKey(id(p)), p.getName() + " went home: the Clubhouse couldn't take them");
            assertEquals(1, told.courses(id(p)), p.getName() + " still counted once");
        }
    }

    /**
     * The PRODBUG the journeys found: a racer who finished in the restart hold's last minute was taken into the
     * Clubhouse, still there when the server stopped. {@code ClubRaces.toClub} now asks the door first
     * ({@code closingForRestart}: from 66 s before the restart to the end of its minute) and then sends them
     * home, as with no Clubhouse, reading why; the finish still counts once. Earlier in the hold the door is
     * not closing, and a race's end comes in as always (CLUBHOUSE-SPEC §7; the journeys pin both sides).
     */
    @Test
    void aPartyRacerWhoFinishesAsTheClubhouseClosesForTheRestartGoesHomeNotToTheClubhouse() throws Exception {
        clubOn();
        door.in.put(id(ava), ClubVisits.Kind.PARTY);
        door.in.put(id(ben), ClubVisits.Kind.PARTY);
        door.closing = true; // the race went before the hold; it ends in the hold's last minute
        raceOnce(List.of(ava, ben, cal), List.of(ava, ben, cal), 180);
        assertEquals(1, race.fromClub.get(id(ava)), "Ava was seated from the Clubhouse");
        for (Player p : List.of(ava, ben, cal)) {
            assertTrue(race.home.containsKey(id(p)), p.getName() + " went home at the line");
            assertFalse(door.in.containsKey(id(p)), p.getName() + " is not in the Clubhouse");
            assertTrue(bench.heard(id(p)).contains("The Clubhouse is closed for the restart, so you're going home"),
                    p.getName() + " reads why: " + bench.heard(id(p)));
            assertEquals(1, told.courses(id(p)), p.getName() + ": the finish still counts, once");
        }
        assertTrue(door.lines.isEmpty(), "the Clubhouse was never asked to take anyone in: " + door.lines);
        assertEquals(45_000L, best(ava), "Ava's time is on the course's board");
        assertEquals(0, bench.severe(), "nothing threw");
    }

    @Test
    void aRacerStillRacingWhenItEndsGoesToTheClubhouseToo() {
        clubOn();
        PartyRace party = raceOnce(List.of(ava, ben), List.of(ava), 150); // Ben never finishes: the window ends it
        assertEquals(PartyRace.Result.STILL_RACING, party.results().get(1).result(), "Ben was still racing");
        assertTrue(door.in.containsKey(id(ben)), "and goes to the Clubhouse, not home");
        assertEquals("&7Race over - great racing!", door.lines.get(id(ben)), "reading the race's own line");
        assertEquals(com.dierks.homecraft.games.clubhouse.ClubhouseText.BACK_PARTY, door.lines.get(id(ava)),
                "a racer parked at their finish reads that they're back in the Clubhouse, with the board's results");
        assertEquals(0, told.courses(id(ben)), "an unfinished race never counts");
    }

    // ---- Race Night -------------------------------------------------------------------------------

    /** Race Night's ports over the bench, with the Clubhouse hooks LivePorts has. */
    private final class Ports implements NightPorts {

        @Override
        public long now() {
            return bench.now();
        }

        @Override
        public long tick() {
            return race.tick;
        }

        @Override
        public boolean online(UUID player) {
            return race.player(player) != null;
        }

        @Override
        public boolean free(UUID player) {
            ClubDoor club = trials.raceMode().door();
            return (club != null && club.seatable(player)) || race.free(player); // LivePorts.free
        }

        @Override
        public String name(UUID player) {
            Player p = race.player(player);
            return p == null ? null : p.getName();
        }

        @Override
        public String seat(UUID racer, Course base, Course raced, Course.Spot grid, Point stand, RaceLink link) {
            return race.seat(racer, base, raced, grid, stand, link);
        }

        @Override
        public void regrid(UUID racer, Course raced, Course.Spot grid) {
            race.regrid(racer, raced, grid);
        }

        @Override
        public void park(UUID racer) {
            race.park(racer);
        }

        @Override
        public void home(UUID racer, EndReason why, String line) {
            trials.endRace(racer, why, line);
        }

        @Override
        public boolean reserve(String courseId, Object holder, String line) {
            return trials.reserve(courseId, holder, line);
        }

        @Override
        public void release(String courseId, Object holder) {
            trials.release(courseId, holder);
        }

        @Override
        public void endSoloRuns(String courseId, Collection<UUID> racers, String line) {
        }

        @Override
        public void warnSoloRuns(String courseId, Collection<UUID> racers, String line) {
        }

        @Override
        public void tell(UUID player, String line, boolean queueIfOffline) {
        }

        @Override
        public void title(UUID player, String big, String small) {
        }

        @Override
        public void bar(UUID player, String line, float progress, boolean lastLap) {
        }

        @Override
        public void watchers(String line) {
        }

        @Override
        public void announce(Announcer.Line line, String text, Collection<UUID> racers) {
        }

        @Override
        public void changed() {
        }

        @Override
        public long points(UUID player, String board) {
            return 0L;
        }

        @Override
        public void progress(UUID player, boolean won) {
        }

        @Override
        public void log(String line, boolean warn) {
        }

        // LivePorts' Clubhouse hooks, over the bench
        @Override
        public boolean clubhouse(String trackWorld) {
            return ClubNight.takes(trials.raceMode().door(), trackWorld); // LivePorts.clubhouse (the real rule)
        }

        @Override
        public void toClubhouse(UUID racer, String line) {
            trials.endRaceToClubhouse(racer, EndReason.FINISH, line);
        }

        @Override
        public void clubhouseResults(NightRunner night) {
            ClubDoor club = trials.raceMode().door();
            club.result(ClubNight.sheet(night), null);
            club.podium(ClubNight.podium(night.standings()));
        }
    }

    private NightRunner night(Ports ports) {
        EventDao dao = new EventDao(bench.db(), bench.dao());
        NightRules rules = new NightRules(3, 0, 2, 8, List.of(10, 8, 6, 5, 4, 3, 2), 2, 1, List.of(5, 3, 2), 1, false,
                0, 60, 4, 20);
        List<Course.Spot> grid = RaceGrid.forCourse(loop, ICE, 8).spots();
        long startsAt = T0 + 11 * MIN;
        EventPlan plan = new EventPlan(NIGHT, loop.id(), startsAt - 10 * MIN, startsAt, rules, false, "");
        Game rn = games.game(RaceNight.SPEC.id());
        PayLoop pay = new PayLoop(dao, new PayLoop.Payer() {
            @Override
            public int pay(UUID player, String ref, int tokens, String detail) {
                Player p = race.player(player);
                return p == null ? -1 : games.rewards().pay(p, rn, TokenService.Source.GAMES_RACE_NIGHT,
                        RewardKind.EVENT_PRIZE, ref, tokens, -1, detail);
            }

            @Override
            public boolean paid(UUID player, String ref) {
                return false;
            }
        }, bench::now, Logger.getAnonymousLogger());
        NightRunner night = new NightRunner(plan, new NightRunner.Track(loop, loop.name(), grid, STAND), dao, ports, pay,
                ZoneOffset.UTC, null, 30, EventMachine.State.scheduled());
        night.prizeWeek(() -> 2920L, 3);
        return night;
    }

    private void run(NightRunner night, int maxTicks, java.util.function.BooleanSupplier done) {
        for (int i = 0; i < maxTicks && !done.getAsBoolean(); i++) {
            bench.move(50);
            night.tick();
            race.tick();
        }
    }

    private void raceIt(NightRunner night, Player... order) {
        run(night, 800, () -> night.phase() == EventMachine.Phase.RACING && race.startOf(id(order[0])) >= 0);
        run(night, 60, () -> false);
        long ms = 45_000;
        for (Player p : order) {
            race.cross(id(p), ms);
            ms += 1_000;
            run(night, 5, () -> false);
        }
    }

    /** A whole night: Ava waits in the Clubhouse; Ava 28, Ben 26, Cal 18 over three races. */
    private NightRunner wholeNight() {
        return wholeNight(() -> { });
    }

    /** {@link #wholeNight()}, with {@code beforeLastRace} run as race 3 goes off. */
    private NightRunner wholeNight(Runnable beforeLastRace) {
        NightRunner night = night(new Ports());
        run(night, 2 * 60 * 20, () -> night.phase() == EventMachine.Phase.OPEN);
        for (Player p : List.of(ava, ben, cal)) {
            assertNull(night.join(id(p), p.getName()), p.getName() + " joins");
        }
        run(night, 12 * 60 * 20, () -> night.phase() == EventMachine.Phase.GRID
                || night.phase() == EventMachine.Phase.RACING);
        raceIt(night, ava, ben, cal);
        run(night, 30 * 20, () -> List.of(ava, ben, cal).stream().allMatch(p -> race.regrids.getOrDefault(id(p), 0) == 1));
        raceIt(night, ava, ben, cal);
        run(night, 30 * 20, () -> List.of(ava, ben, cal).stream().allMatch(p -> race.regrids.getOrDefault(id(p), 0) == 2));
        beforeLastRace.run();
        raceIt(night, ben, ava, cal);
        run(night, 400, () -> night.phase().over() && trials.run(id(ava)) == null && trials.run(id(ben)) == null
                && trials.run(id(cal)) == null);
        assertEquals(EventMachine.Phase.DONE, night.phase(), "three races: done");
        return night;
    }

    @Test
    void raceNightSeatsAWaiterFromTheClubhouseAndEndsWithEveryoneThereTheTopThreeOnThePodium() {
        clubOn();
        door.in.put(id(ava), ClubVisits.Kind.NIGHT); // "Wait in the Clubhouse"
        NightRunner night = wholeNight();
        assertEquals(1, race.fromClub.get(id(ava)), "Ava was seated from the Clubhouse, once");
        assertNull(race.fromClub.get(id(ben)), "Ben from home, as today");
        for (Player p : List.of(ava, ben, cal)) {
            assertEquals(ClubVisits.Kind.NIGHT, door.in.get(id(p)), p.getName() + " ends the night in the Clubhouse");
            assertFalse(race.home.containsKey(id(p)), p.getName() + " never went home");
        }
        assertEquals(List.of(id(ava), id(ben), id(cal)), door.podium, "the top three on the podium, 1st first");
        assertEquals(List.of(id(ava), id(ben), id(cal)), night.standings().stream().map(NightStandings.Ranked::player)
                .toList(), "in the night's own order");
        assertNotNull(door.result, "the board shows the night");
        assertTrue(door.result.rows().get(0).contains("Ava") && door.result.rows().get(0).contains("28 pts"),
                door.result.rows().toString());
        assertEquals(0, bench.severe(), "nothing threw");
    }

    /**
     * Race Night's end-of-night trip in the restart hold's last minute (its last race ran into it): the Clubhouse
     * is closing for the restart and takes nobody then ({@code ClubNight.takes}), so everyone goes home with the
     * night's own home line, never "Everyone to the Clubhouse!", and nothing goes on the Clubhouse's board or
     * podium for a room that is emptying. A night that ends earlier in the hold still goes there.
     */
    @Test
    void raceNightEndingAsTheClubhouseClosesForTheRestartSendsEveryoneHomeNotToTheClubhouse() {
        clubOn();
        door.in.put(id(ava), ClubVisits.Kind.NIGHT); // "Wait in the Clubhouse"
        wholeNight(() -> door.closing = true);
        assertEquals(1, race.fromClub.get(id(ava)), "Ava was seated from the Clubhouse before the hold");
        for (Player p : List.of(ava, ben, cal)) {
            assertEquals("&7Race Night is over - great racing! Your things are back.", race.home.get(id(p)),
                    p.getName() + " went home with the night's own line");
            assertFalse(door.in.containsKey(id(p)), p.getName() + " is not in the Clubhouse");
        }
        assertNull(door.result, "no board for a Clubhouse closing for the restart");
        assertNull(door.podium, "and no podium");
        assertTrue(door.lines.isEmpty(), "nobody was taken in: " + door.lines);
        assertEquals(0, bench.severe(), "nothing threw");
    }

    @Test
    void withTheClubhouseOffRaceNightSendsEveryoneHomeAsBefore() {
        clubOff();
        wholeNight();
        for (Player p : List.of(ava, ben, cal)) {
            assertTrue(race.home.containsKey(id(p)), p.getName() + " went home with their things, as always");
        }
        assertTrue(door.in.isEmpty() && door.podium == null && door.result == null, "the Clubhouse heard nothing");
    }

    @Test
    void thePodiumFollowsTheNightsOwnRankingOnTies() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        UUID d = UUID.randomUUID();
        Map<UUID, Integer> points = new LinkedHashMap<>();
        points.put(a, 20);
        points.put(b, 24);
        points.put(c, 24);
        points.put(d, 0);
        Map<UUID, List<Integer>> places = new LinkedHashMap<>();
        places.put(a, List.of(1, 2, 3));
        places.put(b, List.of(3, 1, 2));
        places.put(c, List.of(1, 1, 5));
        places.put(d, List.of());
        List<NightStandings.Ranked> ranked = NightStandings.rank(points, places);
        List<UUID> expected = ranked.stream().limit(3).map(NightStandings.Ranked::player).toList();
        assertEquals(expected, ClubNight.podium(ranked), "the podium is the night's first three, its tie-break kept");
        assertEquals(Set.of(b, c), Set.copyOf(ClubNight.podium(ranked).subList(0, 2)),
                "the two on 24 points are 1st and 2nd, in the order the night ranked them");
        assertFalse(ClubNight.podium(ranked).contains(d), "nobody without points on the podium");
    }
}
