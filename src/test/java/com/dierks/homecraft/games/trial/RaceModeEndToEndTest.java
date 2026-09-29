package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.games.EndReason;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GamesBench;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.NoPush;
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
 * Race Night (WP-R2) and party races (WP-R1) end to end on race mode, with the real framework and a
 * real database: the coordinators ({@link NightRunner}, {@link PartyRace}), every racer's
 * {@link RaceRun}, {@link RaceMode}'s finish, park, endRace and holds, Time Trials' finish and
 * {@code settleCounted}, the Weekly Cup and the rewards are the real ones; only the server I/O of
 * seating, holding and teleporting is mirrored ({@link RaceBench}).
 *
 * <p>Pinned here:
 * <ul>
 *   <li>a whole Race Night: seat, the shared warm-up (a warm-up lap counts for nothing), Ready, the
 *       grid, one Go, finish, the stand, race 2 gridded by points (fewest in front), race 3, then
 *       prizes 5/3/1 paid once (a second pay pass pays nothing), everyone home, the track let go;</li>
 *   <li>its heats never touch the course's boards or records and never set a Cup time, but each
 *       finished race counts once toward FINISH_COURSE, and the night once toward
 *       raceNightFinished (won only by the winner);</li>
 *   <li>a party race start to finish: seat, the shared warm-up, Ready, the grid, one Go, three
 *       finishes home at the line, the results in order; each finish is also the course's normal
 *       counted run exactly once (its board, its first-clear reward, FINISH_COURSE once, the Cup),
 *       and a party pays nothing of its own (no prize; 2nd and 3rd earn the same);</li>
 *   <li>a solo counted run sets a Cup time too, through the same {@code settleCounted}.</li>
 * </ul>
 */
class RaceModeEndToEndTest {

    /** Tuesday 29 September 2026, noon (the kit's zone): a Cup week is open. */
    private static final long T0 = GamesBench.at(2026, 9, 29, 12, 0);
    private static final long MIN = 60_000L;
    private static final String NIGHT = "rn-20260929-1215";

    /** Laps' two-lap loop (a boat course, 6 marks a lap, the finish at the start, 40 s at the least). */
    private static final Course BUILT = LapsTest.loop(6, 2);
    /** Open ice at the loop's height: a floor under y 65, air above. */
    private static final RaceGrid.Surface ICE = (x, y, z) -> y < 65 ? RaceGrid.Cell.SOLID : RaceGrid.Cell.AIR;
    /** The viewing stand, in the loop's middle. */
    private static final Point STAND = new Point(0.5, 70, 0.5);

    /** The loop as Time Trials reads it back from its row: the course raced. */
    private Course loop;
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
        assertNotNull(trials, "Time Trials is built from its spec");
        assertNotNull(cup, "and the Weekly Cup");
        bench.dao().saveCourse(new GamesDao.CourseRow(BUILT.id(), "trials", BUILT.kind().id(), BUILT.name(),
                BUILT.world(), BUILT.enabled(), CourseCodec.encode(BUILT), BUILT.rev(), 0, 0), false);
        loop = trials.course(BUILT.id()); // the course as Time Trials reads it back: the one raced
        assertNotNull(loop, "the loop is a Time Trials course");
        race = new RaceBench(trials);
        ava = bench.player("Ava");
        ben = bench.player("Ben");
        cal = bench.player("Cal");
        for (Player p : List.of(ava, ben, cal)) {
            race.add(p);
            bench.give(p.getUniqueId(), 20);
        }
        cup.desk().dao().choose(loop.id(), true); // a hand-built course runs a Cup once an admin says so
        assertNull(cup.desk().enter(ava.getUniqueId(), loop), "Ava enters this week's Cup on the loop");
    }

    @AfterEach
    void tearDown() throws Exception {
        bench.close();
    }

    private UUID id(Player p) {
        return p.getUniqueId();
    }

    /** A player's time on a board of Time Trials, or {@code null}. */
    private Long best(Player p, String board) throws SQLException {
        return bench.dao().best(id(p), TimeTrials.SPEC.id(), board);
    }

    /** The player's Cup time this week on the loop, or {@code null} (none, or not in). */
    private Long cupTime(Player p) throws SQLException {
        try (PreparedStatement ps = bench.connection().prepareStatement(
                "SELECT best_ms FROM cup_entries WHERE course = ? AND player = ?")) {
            ps.setString(1, loop.id());
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

    /** The kinds of reward the player was paid, in order. */
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

    // ---- a whole Race Night ------------------------------------------------------------------------

    /** Race Night's ports over the bench: LivePorts' calls, with the server's parts faked. */
    private final class Ports implements NightPorts {
        final List<String> log = new ArrayList<>();
        /** Racers whose entry starts (TimeTrials.race says null) but who never arrive: dropped on the way in. */
        final java.util.Set<UUID> ghosts = new java.util.HashSet<>();

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
            if (ghosts.contains(racer)) {
                trials.raceMode().expect(racer, link); // RaceMode.race: the entry started, the session will drop it
                return null;
            }
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
            trials.endRace(racer, why, line); // LivePorts.home: race mode sends them home on its next tick
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
            Player p = race.player(player);
            if (p != null) {
                games.tellProgress(g -> g.raceNightFinished(p, won)); // LivePorts.progress
            }
        }

        @Override
        public void log(String line, boolean warn) {
            log.add(line);
        }
    }

    /** RaceNight.payLoop's payer: EVENT_PRIZE through the real rewards, once per ref. */
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

    /** Run the server a tick at a time until {@code done} or {@code maxTicks}. */
    private void run(NightRunner night, int maxTicks, java.util.function.BooleanSupplier done) {
        for (int i = 0; i < maxTicks && !done.getAsBoolean(); i++) {
            bench.move(50);
            night.tick();
            race.tick();
        }
    }

    /** Race the current race: finishes in this order, 45, 46 and 47 seconds from Go. */
    private void raceIt(NightRunner night, Player... order) {
        run(night, 400, () -> night.phase() == EventMachine.Phase.RACING && race.startOf(id(order[0])) >= 0);
        assertEquals(EventMachine.Phase.RACING, night.phase(), "race " + night.race() + " is off");
        long go = race.startOf(id(order[0]));
        for (Player p : order) {
            assertEquals(go, race.startOf(id(p)), p.getName() + " started on the one shared go instant");
        }
        run(night, 60, () -> false); // three seconds of racing
        long ms = 45_000;
        for (Player p : order) {
            race.cross(id(p), ms);
            ms += 1_000;
            run(night, 5, () -> false);
        }
    }

    @Test
    void aWholeRaceNightRunsOnRaceModeAndPaysFiveThreeAndOneOnce() throws Exception {
        EventDao dao = new EventDao(bench.db(), bench.dao());
        NightRules rules = new NightRules(3, 0, 2, 8, List.of(10, 8, 6, 5, 4, 3, 2), 2, 1, List.of(5, 3, 2), 1, false,
                60, 60, 4, 20);
        List<Course.Spot> grid = RaceGrid.forCourse(loop, ICE, 8).spots(); // Time Trials' grid, Race Night's too
        assertTrue(grid.size() >= 3, "the loop seats the night: " + grid);
        long startsAt = T0 + 11 * MIN;
        EventPlan plan = new EventPlan(NIGHT, loop.id(), startsAt - 10 * MIN, startsAt, rules, false, "");
        Ports ports = new Ports();
        PayLoop pay = payLoop(dao);
        NightRunner night = new NightRunner(plan, new NightRunner.Track(loop, loop.name(), grid, STAND), dao, ports,
                pay, ZoneOffset.UTC, "rnseason:2026-09", 30, EventMachine.State.scheduled());
        night.prizeWeek(2920, 3);
        int[] before = {bench.balance(id(ava)), bench.balance(id(ben)), bench.balance(id(cal))};

        // joining opens at T - 10; Ava, Ben and Cal join
        run(night, 2 * 60 * 20, () -> night.phase() == EventMachine.Phase.OPEN);
        for (Player p : List.of(ava, ben, cal)) {
            assertNull(night.join(id(p), p.getName()), p.getName() + " joins");
        }

        // a minute before: the track is held; then everyone is seated into the shared warm-up
        run(night, 12 * 60 * 20, () -> night.phase() == EventMachine.Phase.WARMUP && race.free(id(cal)) == false);
        assertEquals(EventMachine.Phase.WARMUP, night.phase(), "the shared warm-up");
        assertTrue(trials.raceMode().refuseSolo(bench.player("Solo"), loop.id()), "the track is held: no solo runs");
        for (Player p : List.of(ava, ben, cal)) {
            TrialRun run = trials.run(id(p));
            assertNotNull(run, p.getName() + " is at the track");
            assertTrue(run.warmup && run.race != null, p.getName() + " warms up on race mode");
        }
        race.cross(id(ava), 42_000); // a warm-up lap
        assertNull(best(ava, Scores.course(loop.id())), "a warm-up lap never reaches a board");
        assertNull(cupTime(ava), "nor the Cup");
        assertEquals(0, told.courses(id(ava)), "nor the quests");
        assertTrue(bench.heard(id(ava)).contains("Warm-up lap"), "it was a warm-up lap: " + bench.heard(id(ava)));
        for (Player p : List.of(ava, ben, cal)) {
            race.ready(id(p));
        }

        // everyone ready: the grid, one 3-2-1, race 1
        raceIt(night, ava, ben, cal); // 10, 8, 6
        for (Player p : List.of(ava, ben, cal)) {
            assertEquals(1, race.regrids.get(id(p)), p.getName() + " went from the warm-up to the grid once");
        }
        run(night, 100, () -> night.phase() == EventMachine.Phase.BREAK);
        assertEquals(EventMachine.Phase.BREAK, night.phase(), "everyone in: the break");
        for (Player p : List.of(ava, ben, cal)) {
            assertEquals(TrialRun.Phase.PARKED, trials.run(id(p)).phase, p.getName() + " waits on the stand");
        }

        // race 2: the grid by tonight's points, fewest in front
        run(night, 30 * 20, () -> List.of(ava, ben, cal).stream().allMatch(p -> race.regrids.getOrDefault(id(p), 0) == 2));
        assertEquals(grid.get(0), race.regridded.get(id(cal)), "Cal (6) starts on pole");
        assertEquals(grid.get(1), race.regridded.get(id(ben)), "Ben (8) second");
        assertEquals(grid.get(2), race.regridded.get(id(ava)), "Ava (10) at the back");
        raceIt(night, ava, ben, cal); // 20, 16, 12
        run(night, 30 * 20, () -> List.of(ava, ben, cal).stream().allMatch(p -> race.regrids.getOrDefault(id(p), 0) == 3));
        raceIt(night, ben, ava, cal); // Ava 28, Ben 26, Cal 18
        run(night, 200, () -> night.phase().over() && race.home.size() == 3);

        assertEquals(EventMachine.Phase.DONE, night.phase(), "three races: settled and done");
        assertEquals(before[0] + 5, bench.balance(id(ava)), "Ava won the night: 5 tokens");
        assertEquals(before[1] + 3, bench.balance(id(ben)), "Ben 2nd: 3 tokens (3 racers started)");
        assertEquals(before[2] + 1, bench.balance(id(cal)), "Cal: 3rd needs 4 racers, so the finisher's 1");
        assertEquals(0, pay.payNight(NIGHT), "a second pay pass owes nothing");
        assertEquals(List.of(before[0] + 5, before[1] + 3, before[2] + 1),
                List.of(bench.balance(id(ava)), bench.balance(id(ben)), bench.balance(id(cal))), "paid once");
        for (EventDao.EntryRow e : dao.entries(NIGHT)) {
            assertNotNull(e.paidAt(), e.name() + "'s prize is recorded as paid");
        }

        for (Player p : List.of(ava, ben, cal)) {
            assertNull(best(p, Scores.course(loop.id())), p.getName() + ": a heat never goes on the course's board");
            assertNull(best(p, Scores.week(loop.id(), trials.weekKey())), "nor its week's board");
            assertEquals(3, told.courses(id(p)), p.getName() + ": each finished race counts once toward FINISH_COURSE");
            assertEquals(1, told.nights.get(id(p)).size(), p.getName() + ": the night counts once (raceNightFinished)");
            assertTrue(race.home.containsKey(id(p)), p.getName() + " went home with their things");
            assertNull(trials.run(id(p)), "and their run is over");
        }
        for (Player p : List.of(ava, ben, cal)) {
            assertEquals(List.of("EVENT_PRIZE"), rewardKinds(p), p.getName() + ": the night's prize is all a heat pays");
        }
        assertEquals(List.of(true), told.nights.get(id(ava)), "Ava won");
        assertEquals(List.of(false), told.nights.get(id(ben)), "Ben didn't");
        assertNull(trials.record(loop.id()), "no course record from a heat");
        assertNull(trials.weekRecord(loop.id()), "nor a week's best");
        assertNull(cupTime(ava), "and a heat never sets a Cup time (its start is a grid spot)");
        assertFalse(trials.raceMode().refuseSolo(bench.player("Solo2"), loop.id()), "the track is let go");
        assertEquals(0, bench.severe(), "nothing threw");
    }

    // ---- a party race ---------------------------------------------------------------------------

    @Test
    void aPartyRaceRunsStartToFinishAndEachFinishIsANormalRunOnce() throws Exception {
        List<String> said = new ArrayList<>();
        List<PartyRace.Racer> racers = List.of(new PartyRace.Racer(id(ava), "Ava"), new PartyRace.Racer(id(ben), "Ben"),
                new PartyRace.Racer(id(cal), "Cal"));
        PartyRace party = new PartyRace(7, loop, racers, 60, () -> race.tick, said::add);
        List<Course.Spot> grid = RaceGrid.forCourse(loop, ICE, 3).spots();
        int[] before = {bench.balance(id(ava)), bench.balance(id(ben)), bench.balance(id(cal))};

        // PartyRaces.start: seat everyone (no stand on a hand-built course: home at the line)
        for (int i = 0; i < racers.size(); i++) {
            UUID id = racers.get(i).id();
            Course.Spot spot = grid.get(i);
            Laps.Raced raced = Laps.raced(loop, spot, 0);
            assertNull(race.seat(id, loop, raced.course(), spot, null, party), "seated");
            party.seated(id, spot);
        }
        bench.move(2 * MIN); // the party gathered a while after Ava entered the Cup
        assertTrue(party.seatingDone(), "three seated: the race is on");
        assertEquals(PartyRace.State.WARMUP, party.state(), "the host chose a warm-up first");
        race.cross(id(ben), 41_000); // a warm-up lap
        assertNull(best(ben, Scores.course(loop.id())), "a warm-up lap is never a run");
        for (Player p : List.of(ava, ben, cal)) {
            race.ready(id(p));
        }

        // PartyRaces.tick: to the grid, one Go, then the finishes
        boolean ended = false;
        List<Player> order = List.of(ava, ben, cal);
        int next = 0;
        long go = -1;
        for (int i = 0; i < 20 * 60 * 3 && !ended; i++) {
            bench.move(50);
            race.tick();
            switch (party.tick(race.tick)) {
                case TO_GRID -> {
                    for (UUID id : party.racers()) {
                        Course.Spot spot = party.grid(id);
                        race.regrid(id, Laps.raced(loop, spot, 0).course(), spot);
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
                            race.cross(id(order.get(next)), 45_000 + next * 1_000L);
                            next++;
                        }
                    }
                }
            }
        }
        assertTrue(ended, "every racer in: the race is over");
        List<PartyRace.Line> results = party.results();
        assertEquals(List.of(id(ava), id(ben), id(cal)), results.stream().map(PartyRace.Line::id).toList(),
                "the results in finishing order");
        for (PartyRace.Line l : results) {
            assertEquals(PartyRace.Result.FINISHED, l.result(), l.name() + " finished");
        }

        long[] times = {45_000, 46_000, 47_000};
        for (int i = 0; i < order.size(); i++) {
            Player p = order.get(i);
            assertEquals(times[i], best(p, Scores.course(loop.id())), p.getName() + "'s race time is on the course's board");
            assertEquals(1, told.courses(id(p)), p.getName() + ": FINISH_COURSE once (never twice for a party finish)");
            assertTrue(race.home.containsKey(id(p)), p.getName() + " went home at the line (no stand)");
        }
        assertEquals(45_000L, cupTime(ava), "a party race's finish sets a Cup time: it is a normal counted run");
        int ava2 = bench.balance(id(ava)) - before[0];
        int ben2 = bench.balance(id(ben)) - before[1];
        int cal2 = bench.balance(id(cal)) - before[2];
        assertTrue(ben2 > 0, "a first finish pays its normal reward: " + ben2);
        assertEquals(ben2, cal2, "2nd and 3rd earn the same: a placing pays nothing");
        for (Player p : order) {
            List<String> kinds = rewardKinds(p);
            assertEquals(1, kinds.stream().filter("FIRST_CLEAR"::equals).count(), p.getName() + ": its first finish, once");
            assertFalse(kinds.contains("EVENT_PRIZE"), p.getName() + ": a party race pays no prize of its own: " + kinds);
        }
        assertEquals(0, bench.severe(), "nothing threw");

        // a solo counted run after: its COUNTED finish settles through the same settleCounted (the
        // rest of a solo finish schedules its result screen on the server, which the bench has none of)
        TrialRun solo = new TrialRun(id(ava), loop, false, 0);
        bench.move(60_000);
        trials.settleCounted(ava, solo, 44_000, new FairPlay.Verdict(FairPlay.Kind.COUNTED, null));
        assertEquals(44_000L, cupTime(ava), "a solo counted run sets the Cup time too");
        assertEquals(44_000L, best(ava, Scores.course(loop.id())), "and the board");
        assertEquals(2, told.courses(id(ava)), "and FINISH_COURSE once more");
        trials.settleCounted(ava, new TrialRun(id(ava), loop, false, 0), 30_000,
                new FairPlay.Verdict(FairPlay.Kind.VOID, "flying"));
        assertEquals(44_000L, cupTime(ava), "a voided run never reaches the Cup");
    }

    // ---- the fixes: ghosts, holds, the guard, the grid, the no-push team (EV fix stage) ------------

    /** A night on the loop starting at {@code startsAt}, with a {@code warmup}-second shared warm-up. */
    private NightRunner night(Ports ports, long startsAt, int warmup) throws SQLException {
        EventDao dao = new EventDao(bench.db(), bench.dao());
        NightRules rules = new NightRules(3, 0, 2, 8, List.of(10, 8, 6, 5, 4, 3, 2), 2, 1, List.of(5, 3, 2), 1, false,
                warmup, 60, 4, 20);
        List<Course.Spot> grid = RaceGrid.forCourse(loop, ICE, 8).spots();
        EventPlan plan = new EventPlan(NIGHT, loop.id(), startsAt - 10 * MIN, startsAt, rules, false, "");
        NightRunner night = new NightRunner(plan, new NightRunner.Track(loop, loop.name(), grid, STAND), dao, ports,
                payLoop(dao), ZoneOffset.UTC, "rnseason:2026-09", 30, EventMachine.State.scheduled());
        night.prizeWeek(2920, 3);
        return night;
    }

    @Test
    void aPartyRacerWhoseEntryNeverArrivesIsSweptAndTheOthersRaceOn() {
        List<String> said = new ArrayList<>();
        PartyRace party = new PartyRace(8, loop, List.of(new PartyRace.Racer(id(ava), "Ava"),
                new PartyRace.Racer(id(ben), "Ben"), new PartyRace.Racer(id(cal), "Cal")), 60, () -> race.tick, said::add);
        List<Course.Spot> grid = RaceGrid.forCourse(loop, ICE, 3).spots();
        RaceMode mode = trials.raceMode();
        for (int i = 0; i < 2; i++) {
            UUID id = List.of(id(ava), id(ben)).get(i);
            assertNull(race.seat(id, loop, Laps.raced(loop, grid.get(i), 0).course(), grid.get(i), null, party), "seated");
            party.seated(id, grid.get(i));
        }
        // PartyRaces.seat: TimeTrials.race said null (the entry started), but the session drops Cal on the way in
        mode.expect(id(cal), party);
        party.seated(id(cal), grid.get(2));
        assertTrue(party.seatingDone(), "three in: the warm-up");
        race.ready(id(ava));
        race.ready(id(ben));
        assertEquals(PartyRace.Step.NONE, party.tick(race.tick), "Cal counts as seated and not ready: the warm-up waits");

        mode.sweepArrivals(id -> true);
        assertTrue(mode.arriving(id(cal)), "an entry still on its way (ENTERING) is never swept");
        mode.sweepArrivals(id -> false);
        assertFalse(mode.arriving(id(cal)), "an entry that was dropped is forgotten");
        assertFalse(party.racing(id(cal)), "and the race hears Cal left: never waits on a ghost");
        assertEquals(PartyRace.Step.TO_GRID, party.tick(race.tick), "the two who are there go to the grid at once");
        mode.sweepArrivals(id -> false);
        assertEquals(1, said.stream().filter(l -> l.contains("Cal left")).count(), "the race heard it once: " + said);
        assertEquals(PartyRace.Result.LEFT, party.results().stream().filter(l -> l.id().equals(id(cal))).findFirst()
                .orElseThrow().result(), "Cal's line in the results");
    }

    @Test
    void aRaceNightRacerWhoseEntryNeverArrivesIsSweptAndTakenToTheTrackAgain() throws Exception {
        Ports ports = new Ports();
        long startsAt = T0 + 11 * MIN;
        NightRunner night = night(ports, startsAt, 60);
        run(night, 2 * 60 * 20, () -> night.phase() == EventMachine.Phase.OPEN);
        for (Player p : List.of(ava, ben, cal)) {
            assertNull(night.join(id(p), p.getName()), p.getName() + " joins");
        }
        ports.ghosts.add(id(cal));
        run(night, 12 * 60 * 20, () -> night.phase() == EventMachine.Phase.WARMUP && !race.free(id(ben))
                && night.racer(id(cal)).seated());
        assertEquals(EventMachine.Phase.WARMUP, night.phase(), "the shared warm-up");
        assertTrue(night.racer(id(cal)).seated(), "Race Night thinks Cal is at the track: the entry started");
        assertNull(trials.run(id(cal)), "but Cal never arrived");
        race.ready(id(ava));
        race.ready(id(ben));
        run(night, 40, () -> false);
        assertEquals(EventMachine.Phase.WARMUP, night.phase(), "with a ghost 'seated' and not ready, the warm-up waits");

        ports.ghosts.clear();
        trials.raceMode().sweepArrivals(id -> false);
        assertFalse(night.racer(id(cal)).seated(), "the sweep tells the night: Cal isn't at the track");
        run(night, 60, () -> trials.run(id(cal)) != null);
        assertNotNull(trials.run(id(cal)), "free again, Cal is taken to the track on the next retry");
        assertTrue(night.racer(id(cal)).seated(), "and really seated this time");
        assertEquals(0, bench.severe(), "nothing threw");
    }

    @Test
    void raceNightsHoldCallsOffAPartyRaceOnItsTrackAndSendsItsRacersHome() throws Exception {
        PartyLobby lobby = games.parties().create(PartyLobby.Kind.RACE, loop.id(), id(ava), 8);
        assertNull(games.parties().join(lobby.id(), id(ben)), "Ben joins Ava's party");
        assertNull(lobby.start(id(ava)), "Ava starts it");
        List<String> said = new ArrayList<>();
        PartyRace party = new PartyRace(lobby.id(), loop, List.of(new PartyRace.Racer(id(ava), "Ava"),
                new PartyRace.Racer(id(ben), "Ben")), 0, () -> race.tick, said::add);
        List<Course.Spot> grid = RaceGrid.forCourse(loop, ICE, 2).spots();
        for (int i = 0; i < 2; i++) {
            UUID id = List.of(id(ava), id(ben)).get(i);
            assertNull(race.seat(id, loop, Laps.raced(loop, grid.get(i), 0).course(), grid.get(i), null, party), "seated");
            party.seated(id, grid.get(i));
        }
        assertTrue(party.seatingDone(), "on the grid");
        trials.party().running(party);
        for (int i = 0; i < 20 * 10 && race.startOf(id(ben)) < 0; i++) {
            bench.move(50);
            race.tick();
            party.tick(race.tick);
        }
        assertTrue(race.startOf(id(ben)) >= 0, "the race is on");
        race.cross(id(ava), 45_000); // Ava finishes before Race Night comes: a normal run, as ever
        race.tick();

        Object raceNight = new Object();
        assertTrue(trials.reserve(loop.id(), raceNight, "&7Race Night is on this track - watch or join!"),
                "Race Night holds the track");
        assertFalse(party.alive(), "the party race on it is called off");
        race.tick();
        assertTrue(race.home.get(id(ben)).contains("Race Night needs this track now"),
                "Ben goes home with a clear line: " + race.home.get(id(ben)));
        assertNull(trials.run(id(ben)), "his run is over");
        assertNull(best(ben, Scores.course(loop.id())), "and his unfinished race counts for nothing (DNF)");
        assertEquals(45_000L, best(ava, Scores.course(loop.id())), "Ava's finish before the call-off stays a normal run");
        assertTrue(trials.party().lastResults(lobby).isEmpty(), "a race that was called off keeps no results");
        assertEquals(PartyLobby.State.OPEN, lobby.state(), "the party opens again");
        assertEquals("Race Night is on this track - watch or join!", trials.party().startProblem(ava),
                "and a new start is refused while Race Night holds the track");
        trials.release(loop.id(), raceNight);
        assertNull(trials.party().startProblem(ava), "let go: the party can race again");
        assertEquals(0, bench.severe(), "nothing threw");
    }

    @Test
    void raceNightHoldsItsTrackAgainOnItsNextStepAfterATimeTrialsStop() throws Exception {
        Ports ports = new Ports();
        long startsAt = T0 + 11 * MIN;
        NightRunner night = night(ports, startsAt, 0);
        run(night, 2 * 60 * 20, () -> night.phase() == EventMachine.Phase.OPEN);
        for (Player p : List.of(ava, ben)) {
            assertNull(night.join(id(p), p.getName()), p.getName() + " joins");
        }
        Player solo = bench.player("Solo");
        run(night, 12 * 60 * 20, () -> bench.now() >= startsAt - EventMachine.RESERVE_MS + 1_000);
        assertTrue(trials.raceMode().refuseSolo(solo, loop.id()), "the last call holds the track");
        trials.raceMode().stop(); // Time Trials stopping (a reload that closes and opens it) drops every hold
        assertFalse(trials.raceMode().refuseSolo(solo, loop.id()), "the stop let the track go");
        run(night, 5, () -> false);
        assertTrue(trials.raceMode().refuseSolo(solo, loop.id()), "Race Night holds it again on its next step");
    }

    @Test
    void aLinkThatThrowsAtTheLineNeverThrowsOutOfTheFinishAndItsRacerGoesHome() throws Exception {
        TrialFakes.Link broken = new TrialFakes.Link() {
            @Override
            public boolean normalRun() {
                throw new IllegalStateException("the coordinator broke");
            }
        };
        broken.goTick = race.tick + 10;
        Course.Spot spot = RaceGrid.forCourse(loop, ICE, 1).spots().get(0);
        assertNull(race.seat(id(ava), loop, Laps.raced(loop, spot, 0).course(), spot, null, broken), "seated");
        for (int i = 0; i < 20 && race.startOf(id(ava)) < 0; i++) {
            race.tick();
        }
        race.cross(id(ava), 45_000); // TimeTrials.finish -> RaceMode.finish asks normalRun() through its guard
        assertFalse(trials.raceMode().alive(broken), "the link that threw is over");
        race.tick();
        assertTrue(race.home.containsKey(id(ava)), "and its racer goes home");
        assertNull(best(ava, Scores.course(loop.id())), "a broken link's finish is no normal run");
    }

    @Test
    void theRealGridReleasesEveryRunnerOnOneInstantAndADrifterOnlyOnceBackOnItsSpot() {
        Course run = LapsTest.straight();
        TrialFakes.Link link = new TrialFakes.Link();
        link.goTick = 500;
        RaceMode mode = trials.raceMode();
        List<Course.Spot> spots = List.of(new Course.Spot(-1.5, 65, 0, 0, 0), new Course.Spot(1.5, 65, 0, 0, 0),
                new Course.Spot(-1.5, 65, -5, 0, 0));
        java.util.Map<UUID, org.bukkit.Location> at = new java.util.HashMap<>();
        List<Player> players = new ArrayList<>();
        List<TrialRun> runs = new ArrayList<>();
        for (int i = 0; i < spots.size(); i++) {
            UUID id = UUID.randomUUID();
            Course.Spot s = spots.get(i);
            at.put(id, new org.bukkit.Location(null, s.x(), s.y(), s.z()));
            players.add(TrialFakes.player(id, "Runner" + i, new ArrayList<>(), () -> at.get(id)));
            TrialRun r = new TrialRun(id, run.withStart(s), false, 0);
            r.race = new RaceRun(link, run, s, null, false);
            r.voided = "a test run"; // the fair-play watch at Go reads the server's registries
            trials.replaceRun(r);
            runs.add(r);
        }
        UUID drifter = players.get(2).getUniqueId();
        at.put(drifter, new org.bukkit.Location(null, -1.5, 65, -3)); // crept two blocks forward before Go
        for (long tick = 480; tick <= 500; tick++) {
            for (int i = 0; i < players.size(); i++) {
                assertTrue(mode.tick(players.get(i), runs.get(i), tick), "the grid is race mode's own tick");
            }
        }
        assertEquals(TrialRun.Phase.RUNNING, runs.get(0).phase, "released at Go");
        assertEquals(TrialRun.Phase.RUNNING, runs.get(1).phase, "released at Go");
        long go = runs.get(0).progress.startNanos();
        assertEquals(go, runs.get(1).progress.startNanos(), "every runner's clock starts on one instant");
        assertEquals(TrialRun.Phase.COUNTDOWN, runs.get(2).phase, "the one off its spot isn't released from there");

        at.put(drifter, new org.bukkit.Location(null, -1.5, 65, -5)); // back on the spot
        mode.tick(players.get(2), runs.get(2), 510);
        assertEquals(TrialRun.Phase.RUNNING, runs.get(2).phase, "back on its spot: off it goes");
        assertEquals(go, runs.get(2).progress.startNanos(), "on the shared clock: the time going back is lost");
    }

    /** A main scoreboard's teams, as a map: entry to team. */
    private static final class Board implements NoPush.Board {
        final java.util.Map<String, String> teamOf = new java.util.HashMap<>();
        final java.util.Set<String> teams = new java.util.HashSet<>();

        @Override
        public String teamOf(String entry) {
            return teamOf.get(entry);
        }

        @Override
        public void ensureNoCollision(String team) {
            teams.add(team);
        }

        @Override
        public boolean exists(String team) {
            return teams.contains(team);
        }

        @Override
        public void add(String team, String entry) {
            teamOf.put(entry, team);
        }

        @Override
        public void remove(String team, String entry) {
            teamOf.remove(entry, team);
        }

        @Override
        public java.util.Set<String> entries(String team) {
            java.util.Set<String> out = new java.util.HashSet<>();
            teamOf.forEach((e, t) -> {
                if (t.equals(team)) {
                    out.add(e);
                }
            });
            return out;
        }
    }

    @Test
    void everyWayARunnersRaceRunEndsTakesThemOffTheNoPushTeamAndABoatRacerIsNeverOnIt() {
        Board board = new Board();
        board.teams.add("nametags");
        board.teamOf.put("Ava", "nametags"); // another plugin's team
        RaceMode mode = trials.raceMode();
        mode.pushes(new NoPush(() -> board));
        Course parkour = LapsTest.straight();
        TrialFakes.Link link = new TrialFakes.Link();
        java.util.Map<String, Runnable> exits = new java.util.LinkedHashMap<>();
        exits.put("Leave game", () -> trials.onSessionEnd(ava, EndReason.QUIT_ITEM));
        exits.put("a disconnect", () -> trials.onQuit(ava));
        exits.put("sent home by the race", () -> {
            trials.endRace(id(ava), EndReason.FINISH, "&7Race over - great racing!");
            race.tick();
        });
        exits.put("the race called off", () -> {
            link.alive = false;
            race.tick();
            link.alive = true;
        });
        exits.put("the session vanished (the tick's backstop)", () -> {
            mode.gone(trials.run(id(ava)), null);
            trials.end(ava);
        });
        exits.put("Time Trials stopping", () -> {
            mode.stop();
            trials.end(ava);
        });
        for (java.util.Map.Entry<String, Runnable> exit : exits.entrySet()) {
            TrialRun r = new TrialRun(id(ava), parkour, false, 0);
            r.race = new RaceRun(link, parkour, parkour.start(), null, false);
            trials.replaceRun(r);
            mode.inRace(ava, r.race); // RaceMode.seated
            assertEquals(NoPush.TEAM, board.teamOf("Ava"), exit.getKey() + ": a runner is on the no-push team once seated");
            exit.getValue().run();
            assertNull(trials.run(id(ava)), exit.getKey() + ": the run ended");
            assertEquals("nametags", board.teamOf("Ava"), exit.getKey() + ": off the no-push team, back on their own");
        }

        TrialRun boat = new TrialRun(id(ben), loop, false, 0);
        boat.race = new RaceRun(link, loop, loop.start(), STAND, false);
        trials.replaceRun(boat);
        mode.inRace(ben, boat.race);
        assertNull(board.teamOf("Ben"), "boats bump, as the owner chose: a boat racer never joins it");
        trials.onSessionEnd(ben, EndReason.QUIT_ITEM);
        assertEquals(0, bench.severe(), "nothing threw");
    }
}
