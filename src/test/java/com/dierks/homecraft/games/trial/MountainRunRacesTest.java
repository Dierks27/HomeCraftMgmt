package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.games.EndReason;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GamesBench;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.RewardKind;
import com.dierks.homecraft.games.Scores;
import com.dierks.homecraft.games.cup.live.CupSettings;
import com.dierks.homecraft.games.cup.live.WeeklyCup;
import com.dierks.homecraft.games.event.Announcer;
import com.dierks.homecraft.games.event.EventMachine;
import com.dierks.homecraft.games.event.EventPlan;
import com.dierks.homecraft.games.event.NightPorts;
import com.dierks.homecraft.games.event.NightRules;
import com.dierks.homecraft.games.event.NightRunner;
import com.dierks.homecraft.games.event.PayLoop;
import com.dierks.homecraft.games.event.RaceNight;
import com.dierks.homecraft.games.event.RaceNightSettings;
import com.dierks.homecraft.games.event.RaceTrack;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlanInput;
import com.dierks.homecraft.games.gen.api.PlannedTrial;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.boat.BoatPlanner;
import com.dierks.homecraft.games.gen.boat.PlanSurface;
import com.dierks.homecraft.storage.EventDao;
import com.dierks.homecraft.storage.GamesDao;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A party race and a Race Night on a generated Mountain Run (Course Variety §5.1, §9 "a v3 boat
 * course through a party race, a Race Night race and the Weekly Cup"), with the real framework, race
 * mode and database of {@link RaceModeEndToEndTest}'s benches: the races need no change for a
 * downhill sprint.
 *
 * <p>The course is the planner's own ({@code BoatPlanner} algo 3, medium), raced as it is built: its
 * grid comes from the plan's own blocks ({@link PlanSurface}, as the world reads once it is built) and
 * finishers park on its viewing stand. Pinned: it is a one-lap sprint (the race's lap setting falls
 * back to the course's own), 12 racers fit its pit in rows of two, a party race seats, releases on
 * one Go, finishes in order and parks everyone on the stand, and each finish is a normal counted run
 * (the board, FINISH_COURSE once, the Weekly Cup); a Race Night runs two sprints gridded by points,
 * parks finishers on the stand, pays its prizes once and never touches the course's boards.
 */
class MountainRunRacesTest {

    /** Tuesday 29 September 2026, noon (the kit's zone): a Cup week is open. */
    private static final long T0 = GamesBench.at(2026, 9, 29, 12, 0);
    private static final long MIN = 60_000L;
    private static final String NIGHT = "rn-20260929-1215";
    private static final Slots.Def SLOT = Slots.ICE_BOAT;
    private static final Box HALF = SLOT.half('A');

    /** A medium Mountain Run and its blocks as the world will read them. */
    private static final Plan PLAN;
    private static final PlanSurface WORLD;

    static {
        try {
            PLAN = new BoatPlanner().plan(new PlanInput(SLOT, HALF, 'A', 20725, 0, 0x5EEDL, "medium", 6, 0, null));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        WORLD = new PlanSurface(PLAN);
    }

    private Course run;
    private Point stand;
    private GamesBench bench;
    private GamesService games;
    private TimeTrials trials;
    private WeeklyCup cup;
    private RaceBench race;
    private final RaceBench.Told told = new RaceBench.Told();
    private Player ava;
    private Player ben;
    private Player cal;

    @BeforeEach
    void setUp() throws Exception {
        bench = new GamesBench(T0, List.of(TimeTrials.SPEC, WeeklyCup.SPEC, RaceNight.SPEC),
                "trials", TimeTrialsSettings.defaults(), "cup", CupSettings.defaults(),
                "race_night", RaceNightSettings.defaults());
        games = bench.games();
        games.progress(told);
        trials = (TimeTrials) games.game("trials");
        cup = (WeeklyCup) games.game("cup");
        // the planner's course, saved as a course of the Games world (a hand-built row: no gen tag to gate)
        Course made = ((PlannedTrial) PLAN.course()).course();
        Course built = new Course("mountain", TrialKind.BOAT, "Mountain Run", made.tier(), "games", made.start(),
                made.checkpoints(), made.finish(), made.fallY(), made.minSeconds(), true, false, 1);
        bench.dao().saveCourse(new GamesDao.CourseRow(built.id(), "trials", built.kind().id(), built.name(),
                built.world(), built.enabled(), CourseCodec.encode(built), built.rev(), 0, 0), false);
        run = trials.course(built.id());
        assertNotNull(run, "the Mountain Run is a Time Trials course");
        stand = RaceStand.spot(HALF, run.start().y());
        assertTrue(RaceStand.standable(WORLD, stand), "its viewing stand can be stood on");
        race = new RaceBench(trials);
        ava = bench.player("Ava");
        ben = bench.player("Ben");
        cal = bench.player("Cal");
        for (Player p : List.of(ava, ben, cal)) {
            race.add(p);
            bench.give(p.getUniqueId(), 20);
        }
        cup.desk().dao().choose(run.id(), true);
        assertNull(cup.desk().enter(ava.getUniqueId(), run), "Ava enters this week's Cup on the Mountain Run");
    }

    @AfterEach
    void tearDown() throws Exception {
        bench.close();
    }

    private UUID id(Player p) {
        return p.getUniqueId();
    }

    private Long best(Player p, String board) throws SQLException {
        return bench.dao().best(id(p), TimeTrials.SPEC.id(), board);
    }

    private Long cupTime(Player p) throws SQLException {
        try (PreparedStatement ps = bench.connection().prepareStatement(
                "SELECT best_ms FROM cup_entries WHERE course = ? AND player = ?")) {
            ps.setString(1, run.id());
            ps.setString(2, id(p).toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                long v = rs.getLong(1);
                return rs.wasNull() ? null : v;
            }
        }
    }

    private List<String> rewardKinds(Player p) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = bench.connection().prepareStatement(
                "SELECT kind FROM game_rewards WHERE player = ? ORDER BY id")) {
            ps.setString(1, id(p).toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        }
        return out;
    }

    // ---- the course as the races see it ------------------------------------------------------------

    @Test
    void theMountainRunIsAOneLapSprintWhosePitSeatsTwelveInRowsOfTwo() {
        assertFalse(Laps.loop(run), "a sprint: the finish is far from the start");
        assertEquals(1, Laps.natural(run), "one lap");
        assertEquals(1, RaceTrack.laps(run, 0), "Race Night's shipped laps: 0 races the course's own lap");
        assertEquals(1, RaceTrack.laps(run, 3), "a lap setting a sprint can't use falls back to its own");
        Laps.Raced raced = Laps.raced(run, run.start(), 0);
        assertEquals(run.checkpoints(), raced.course().checkpoints(), "the raced course is the course itself");
        RaceGrid.Grid g = RaceGrid.forCourse(run, WORLD, RaceGrid.MAX_SPOTS);
        assertEquals(RaceGrid.MAX_SPOTS, g.size(), "12 racers fit the pit: Race Night's cap and any party");
        assertEquals(RaceGrid.Mode.DOUBLE, g.mode(), "in rows of two");
        for (Course.Spot s : g.spots()) {
            assertNull(RaceGrid.problem(s.point(), WORLD), "every spot fits a boat on the ice: " + s);
        }
    }

    // ---- a party race -----------------------------------------------------------------------------

    @Test
    void aPartyRaceRunsDownTheMountainAndEachFinishIsANormalCountedRun() throws Exception {
        List<String> said = new ArrayList<>();
        List<PartyRace.Racer> racers = List.of(new PartyRace.Racer(id(ava), "Ava"), new PartyRace.Racer(id(ben), "Ben"),
                new PartyRace.Racer(id(cal), "Cal"));
        PartyRace party = new PartyRace(7, run, racers, 0, () -> race.tick, said::add);
        List<Course.Spot> grid = RaceGrid.forCourse(run, WORLD, racers.size()).spots();
        assertEquals(3, grid.size(), "three spots in the pit");
        int[] before = {bench.balance(id(ava)), bench.balance(id(ben)), bench.balance(id(cal))};
        for (int i = 0; i < racers.size(); i++) {
            UUID id = racers.get(i).id();
            Course.Spot spot = grid.get(i);
            assertNull(race.seat(id, run, Laps.raced(run, spot, 0).course(), spot, stand, party), "seated on the grid");
            party.seated(id, spot);
        }
        bench.move(2 * MIN);
        assertTrue(party.seatingDone(), "three seated: the race is on");

        boolean ended = false;
        List<Player> order = List.of(ben, ava, cal);
        int next = 0;
        long go = -1;
        for (int i = 0; i < 20 * 60 * 3 && !ended; i++) {
            bench.move(50);
            race.tick();
            switch (party.tick(race.tick)) {
                case TO_GRID -> {
                    for (UUID id : party.racers()) {
                        Course.Spot spot = party.grid(id);
                        race.regrid(id, Laps.raced(run, spot, 0).course(), spot);
                    }
                }
                case END -> ended = true;
                default -> {
                    if (party.state() == PartyRace.State.RACING && next < order.size()) {
                        if (go < 0) {
                            go = race.startOf(id(order.get(0)));
                            for (Player p : order) {
                                assertEquals(go, race.startOf(id(p)), p.getName() + " shares the one go instant");
                            }
                        }
                        if (race.tick % 40 == 0) {
                            // a downhill sprint takes 20-35 s for a grown-up
                            race.cross(id(order.get(next)), 24_000 + next * 1_500L);
                            next++;
                        }
                    }
                }
            }
        }
        assertTrue(ended, "every racer in: the race is over");
        List<PartyRace.Line> results = party.results();
        assertEquals(List.of(id(ben), id(ava), id(cal)), results.stream().map(PartyRace.Line::id).toList(),
                "the results in finishing order");
        for (PartyRace.Line l : results) {
            assertEquals(PartyRace.Result.FINISHED, l.result(), l.name() + " finished: a clean sprint is never voided");
        }
        for (Player p : order) {
            assertTrue(race.parked.getOrDefault(id(p), 0) >= 1, p.getName() + " watched from the stand");
        }
        long[] times = {24_000, 25_500, 27_000};
        for (int i = 0; i < order.size(); i++) {
            Player p = order.get(i);
            assertEquals(times[i], best(p, Scores.course(run.id())), p.getName() + "'s race time is on the course's board");
            assertEquals(1, told.courses(id(p)), p.getName() + ": FINISH_COURSE once");
            assertEquals(1, rewardKinds(p).stream().filter("FIRST_CLEAR"::equals).count(),
                    p.getName() + ": its first finish pays once");
        }
        assertEquals(25_500L, cupTime(ava), "a party finish on the Mountain Run sets a Cup time: a normal counted run");
        assertTrue(bench.balance(id(ben)) > before[1], "a first finish pays its normal reward");
        assertEquals(0, bench.severe(), "nothing threw");
    }

    // ---- a Race Night -----------------------------------------------------------------------------

    /** Race Night's ports over the bench (as {@link RaceModeEndToEndTest}'s). */
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
            return race.free(player);
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
            Player p = race.player(player);
            if (p != null) {
                p.sendMessage(com.dierks.homecraft.util.Text.of(line));
            }
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
            try {
                Long best = bench.dao().best(player, RaceNight.SPEC.id(), board);
                return best == null ? 0L : best;
            } catch (SQLException e) {
                return 0L;
            }
        }

        @Override
        public void progress(UUID player, boolean won) {
            games.tellProgress(g -> g.raceNightFinished(player, won));
        }

        @Override
        public void log(String line, boolean warn) {
        }
    }

    private PayLoop payLoop(EventDao dao) {
        Game night = games.game(RaceNight.SPEC.id());
        return new PayLoop(dao, new PayLoop.Payer() {
            @Override
            public int pay(UUID player, String ref, int tokens, String detail) {
                Player p = race.player(player);
                if (p == null || !games.rewards().canEarnHere(p)) {
                    return -1;
                }
                int paid = games.rewards().pay(p, night, TokenService.Source.GAMES_RACE_NIGHT, RewardKind.EVENT_PRIZE,
                        ref, tokens, -1, detail);
                return paid > 0 ? paid : paid(player, ref) ? 0 : -1;
            }

            @Override
            public boolean paid(UUID player, String ref) {
                try {
                    return bench.dao().rewardPaid(player, RaceNight.SPEC.id(), RewardKind.EVENT_PRIZE, ref);
                } catch (SQLException e) {
                    return false;
                }
            }
        }, bench::now, Logger.getAnonymousLogger());
    }

    private void tick(NightRunner night, int maxTicks, java.util.function.BooleanSupplier done) {
        for (int i = 0; i < maxTicks && !done.getAsBoolean(); i++) {
            bench.move(50);
            night.tick();
            race.tick();
        }
    }

    /** Race the current sprint: finishes in this order, a second and a half apart from 24 s. */
    private void raceIt(NightRunner night, Player... order) {
        tick(night, 400, () -> night.phase() == EventMachine.Phase.RACING && race.startOf(id(order[0])) >= 0);
        assertEquals(EventMachine.Phase.RACING, night.phase(), "race " + night.race() + " is off");
        long go = race.startOf(id(order[0]));
        for (Player p : order) {
            assertEquals(go, race.startOf(id(p)), p.getName() + " started on the one shared go instant");
        }
        tick(night, 60, () -> false);
        long ms = 24_000;
        for (Player p : order) {
            race.cross(id(p), ms);
            ms += 1_500;
            tick(night, 5, () -> false);
        }
    }

    @Test
    void aRaceNightRacesTheMountainRunAsSprintsAndParksFinishersOnTheStand() throws Exception {
        EventDao dao = new EventDao(bench.db(), bench.dao());
        NightRules rules = new NightRules(2, 0, 2, 8, List.of(10, 8, 6, 5, 4, 3, 2), 2, 1, List.of(5, 3, 2), 1, false,
                0, 60, 4, 20);
        List<Course.Spot> grid = RaceGrid.forCourse(run, WORLD, 8).spots();
        assertEquals(8, grid.size(), "Race Night's grid from the plan's own blocks");
        long startsAt = T0 + 11 * MIN;
        EventPlan plan = new EventPlan(NIGHT, run.id(), startsAt - 10 * MIN, startsAt, rules, false, "");
        NightRunner night = new NightRunner(plan, new NightRunner.Track(run, run.name(), grid, stand), dao, new Ports(),
                payLoop(dao), ZoneOffset.UTC, "rnseason:2026-09", 30, EventMachine.State.scheduled());
        night.prizeWeek(() -> 2920L, 3);
        int[] before = {bench.balance(id(ava)), bench.balance(id(ben)), bench.balance(id(cal))};

        tick(night, 2 * 60 * 20, () -> night.phase() == EventMachine.Phase.OPEN);
        for (Player p : List.of(ava, ben, cal)) {
            assertNull(night.join(id(p), p.getName()), p.getName() + " joins");
        }
        tick(night, 12 * 60 * 20, () -> night.phase() == EventMachine.Phase.RACING
                || night.phase() == EventMachine.Phase.GRID);
        raceIt(night, ava, ben, cal); // 10, 8, 6
        tick(night, 100, () -> night.phase() == EventMachine.Phase.BREAK);
        assertEquals(EventMachine.Phase.BREAK, night.phase(), "everyone in: the break");
        for (Player p : List.of(ava, ben, cal)) {
            assertEquals(TrialRun.Phase.PARKED, trials.run(id(p)).phase, p.getName() + " waits on the viewing stand");
        }
        tick(night, 30 * 20, () -> List.of(ava, ben, cal).stream().allMatch(p -> race.regrids.getOrDefault(id(p), 0) == 1));
        assertEquals(grid.get(0), race.regridded.get(id(cal)), "race 2 is gridded by points: Cal (6) on pole");
        raceIt(night, ava, ben, cal); // 20, 16, 12
        tick(night, 200, () -> night.phase().over() && race.home.size() == 3);

        assertEquals(EventMachine.Phase.DONE, night.phase(), "two sprints: settled and done");
        assertEquals(before[0] + 5, bench.balance(id(ava)), "Ava won the night: 5 tokens");
        assertEquals(before[1] + 3, bench.balance(id(ben)), "Ben 2nd: 3 tokens");
        assertEquals(0, payLoop(dao).payNight(NIGHT), "a second pay pass owes nothing");
        for (Player p : List.of(ava, ben, cal)) {
            assertNull(best(p, Scores.course(run.id())), p.getName() + ": a heat never goes on the course's board");
            assertEquals(2, told.courses(id(p)), p.getName() + ": each finished sprint counts once toward FINISH_COURSE");
            assertTrue(race.parked.getOrDefault(id(p), 0) >= 2, p.getName() + " parked on the stand after each race");
            assertTrue(race.home.containsKey(id(p)), p.getName() + " went home at the end");
        }
        assertNull(cupTime(ava), "a heat never sets a Cup time");
        assertEquals(0, bench.severe(), "nothing threw");
    }
}
