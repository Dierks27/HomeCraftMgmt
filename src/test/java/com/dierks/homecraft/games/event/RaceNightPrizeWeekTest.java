package com.dierks.homecraft.games.event;

import com.dierks.homecraft.games.EndReason;
import com.dierks.homecraft.games.GamesBench;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.CourseCodec;
import com.dierks.homecraft.games.trial.Point;
import com.dierks.homecraft.games.trial.RaceLink;
import com.dierks.homecraft.games.trial.Tier;
import com.dierks.homecraft.games.trial.TimeTrials;
import com.dierks.homecraft.games.trial.TimeTrialsSettings;
import com.dierks.homecraft.games.trial.TrialKind;
import com.dierks.homecraft.gui.games.daily.DailyLookup;
import com.dierks.homecraft.storage.EventDao;
import com.dierks.homecraft.storage.GamesDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * fx2-C #13, through Race Night itself: a night is made up to 12 hours before its window (a long
 * heads-up, an admin's {@code start ... in M}), maybe in the week before, and claims its prize night
 * at race 1's Go in the week the racing is in. A night an admin set before the week's 04:00 rollover,
 * with last week's prize nights all used, is still a prize night once race 1 goes in the new week,
 * and uses the new week's slot, never last week's. {@link NightRunnerTest} pins the runner's side;
 * this pins what {@link RaceNight#begin} hands it.
 *
 * <p>The real framework and database, with no server: the night reaches the "server" through a fake
 * {@link NightPorts} on the bench's clock (the only thing swapped), so the week is the framework's.
 */
class RaceNightPrizeWeekTest {

    /** Monday 5 October 2026, 03:30 (the kit's zone): half an hour before the week's 04:00 rollover. */
    private static final long T0 = GamesBench.at(2026, 10, 5, 3, 30);
    /** The week's rollover: Monday 04:00. */
    private static final long ROLLOVER = GamesBench.at(2026, 10, 5, 4, 0);
    private static final long LAST_WEEK = LocalDate.of(2026, 9, 28).toEpochDay();
    private static final long THIS_WEEK = LocalDate.of(2026, 10, 5).toEpochDay();
    private static final long MIN = 60_000L;
    private static final UUID A = new UUID(0, 1);
    private static final UUID B = new UUID(0, 2);
    private static final UUID C = new UUID(0, 3);
    private static final Map<UUID, String> NAMES = Map.of(A, "Ava", B, "Ben", C, "Cal");

    private GamesBench bench;
    private GamesService games;
    private RaceNight night;
    private EventDao dao;
    private Ports ports;

    /**
     * Race Night on, for the loop, with no schedule; an admin's night opens its window 10 minutes
     * before its start, and there is no warm-up (the grid, then Go).
     */
    private static RaceNightSettings on() {
        RaceNightSettings d = RaceNightSettings.defaults();
        return new RaceNightSettings(true, List.of(), "loop", d.races(), d.laps(), d.announceMinutes(), d.joinMinutes(),
                10, d.minRacers(), d.maxRacers(), d.finishWindowSeconds(), d.maxRaceMinutes(), d.breakSeconds(), 0,
                d.points(), d.finishPoints(), d.stillRacingPoints(), d.prizes(), d.finisherPrize(),
                d.prizeEventsPerWeek(), d.season(), d.standRadius());
    }

    /** The server as the night sees it: the bench's clock, everyone online and free, a Time Trials that seats them. */
    private final class Ports implements NightPorts {
        long tick = 1_000;
        final Set<UUID> seated = new HashSet<>();
        final Map<UUID, String> titles = new HashMap<>();

        @Override
        public long now() {
            return bench.now();
        }

        @Override
        public long tick() {
            return tick;
        }

        @Override
        public boolean online(UUID player) {
            return NAMES.containsKey(player);
        }

        @Override
        public boolean free(UUID player) {
            return online(player) && !seated.contains(player);
        }

        @Override
        public String name(UUID player) {
            return NAMES.get(player);
        }

        @Override
        public String seat(UUID racer, Course base, Course raced, Course.Spot grid, Point stand, RaceLink link) {
            seated.add(racer);
            return null;
        }

        @Override
        public void regrid(UUID racer, Course raced, Course.Spot grid) {
        }

        @Override
        public void park(UUID racer) {
        }

        @Override
        public void home(UUID racer, EndReason why, String line) {
            seated.remove(racer);
        }

        @Override
        public boolean reserve(String courseId, Object holder, String line) {
            return true;
        }

        @Override
        public void release(String courseId, Object holder) {
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
            titles.put(player, big + " | " + small);
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
    }

    @BeforeEach
    void setUp() throws Exception {
        bench = new GamesBench(T0, List.of(TimeTrials.SPEC, RaceNight.SPEC), "trials", TimeTrialsSettings.defaults(),
                "race_night", on());
        games = bench.games();
        List<Course.Mark> lap = List.of(new Course.Mark(20, 64, 20, 4), new Course.Mark(-20, 64, 20, 4),
                new Course.Mark(-20, 64, -20, 4), new Course.Mark(16, 64, -20, 4));
        Course built = new Course("loop", TrialKind.BOAT, "Ice Loop", Tier.EASY, "games",
                new Course.Spot(20, 64, -16, 0, 0), lap, new Course.Mark(20, 64, -14, 4), null, 30, true, false, 1);
        bench.dao().saveCourse(new GamesDao.CourseRow(built.id(), "trials", built.kind().id(), built.name(),
                built.world(), built.enabled(), CourseCodec.encode(built), built.rev(), 0, 0), false);
        Course loop = ((TimeTrials) games.game("trials")).course("loop");
        assertNotNull(loop, "the loop is a Time Trials course");
        dao = new EventDao(bench.db(), bench.dao());
        night = (RaceNight) games.game("race_night");
        night.dao(dao);
        List<Course.Spot> grid = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            grid.add(new Course.Spot(20 + (i % 2 == 0 ? -1.5 : 1.5), 64, -20 - 4 * (i / 2), 0, 0));
        }
        dao.setMeta(Tracks.GRID + "loop", RaceTrack.encodeGrid(loop.layoutHash(), grid)); // an admin's grid
        ports = new Ports();
        night.portsFor(id -> ports); // the one thing swapped: no server in the tests
    }

    @AfterEach
    void tearDown() throws Exception {
        bench.close();
    }

    /** Run the server tick by tick (50 ms each) until {@code at} or {@code phase}, whichever is first. */
    private void runUntil(NightRunner runner, long at, EventMachine.Phase phase) {
        while (bench.now() < at && runner.phase() != phase) {
            bench.move(50);
            ports.tick++;
            runner.tick();
        }
    }

    @Test
    void aNightSetBeforeTheWeeksRolloverClaimsItsPrizeNightInTheWeekRace1GoesIn() throws Exception {
        for (int i = 1; i <= 3; i++) { // last week used all three of its prize nights
            String other = "rn-2026092" + i + "-1900";
            dao.open(new EventDao.EventRow(other, "loop", T0 - 9 * 86_400_000L, T0 - 9 * 86_400_000L, EventDao.DONE,
                    "", 3, false, "", "", T0, T0, ""));
            assertTrue(dao.claimPrizeSlot(other, Long.toString(LAST_WEEK), 3), "last week's prize night " + i);
        }
        assertEquals(LAST_WEEK, DailyLookup.weekKey(games), "fixture: at 03:30 on Monday it is still last week");

        // 03:30: an admin sets a night for 25 minutes on: its window opens at 03:55, race 1 at 04:05
        assertNull(night.adminStart("Admin", "loop", null, null, 25, false), "an admin sets a night");
        NightRunner runner = night.night();
        assertNotNull(runner, "Race Night made it, and runs it");
        assertTrue(runner.plan().prizesAllowed(), "not a just-for-fun night: it may be a prize night");
        runUntil(runner, T0 + 25 * MIN + 1_000, null);
        assertEquals(EventMachine.Phase.OPEN, runner.phase(), "the window is open at 03:55");
        for (UUID u : List.of(A, B, C)) {
            assertNull(runner.join(u, NAMES.get(u)), NAMES.get(u) + " joins before the rollover");
        }
        assertEquals(LAST_WEEK, DailyLookup.weekKey(games), "fixture: still last week when they join");

        runUntil(runner, runner.startsAt() + 5 * MIN, EventMachine.Phase.RACING);
        assertEquals(EventMachine.Phase.RACING, runner.phase(), "race 1 is off");
        assertTrue(bench.now() >= ROLLOVER, "fixture: race 1 went after the week's 04:00 rollover");
        assertEquals(THIS_WEEK, DailyLookup.weekKey(games), "fixture: it is the new week now");
        assertTrue(runner.prizeNight(), "the new week has all its prize nights, so this is one, as the screen said");
        assertTrue(ports.titles.get(A).contains("Prizes: 5, 3, 2 tokens"), "and Go says so: " + ports.titles.get(A));
        assertEquals(1, dao.prizedIn(Long.toString(THIS_WEEK)), "it uses the new week's slot");
        assertEquals(3, dao.prizedIn(Long.toString(LAST_WEEK)), "never last week's, which stays as it was");
        assertEquals(0, bench.severe(), "nothing threw");
    }
}
