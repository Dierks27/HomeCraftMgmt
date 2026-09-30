package com.dierks.homecraft.games.event;

import com.dierks.homecraft.games.EndReason;
import com.dierks.homecraft.games.GamesBench;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.CourseCodec;
import com.dierks.homecraft.games.trial.Point;
import com.dierks.homecraft.games.trial.RaceLink;
import com.dierks.homecraft.games.trial.Tier;
import com.dierks.homecraft.games.trial.TimeTrials;
import com.dierks.homecraft.games.trial.TimeTrialsSettings;
import com.dierks.homecraft.games.trial.TrialKind;
import com.dierks.homecraft.storage.EventDao;
import com.dierks.homecraft.storage.GamesDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code games.race_night.hype} reaches the night (COURSE-VARIETY-SPEC §5.2): {@link RaceNight#begin}
 * hands each night the switch as configured, the way it hands over {@code stand_radius}.
 * {@link NightRunnerHypeTest} pins what the night then says; this pins that the setting gets there.
 *
 * <p>The real framework and database with no server; an admin starts a night on a hand-built loop.
 */
class RaceNightHypeTest {

    private static final long T0 = GamesBench.at(2026, 10, 2, 12, 0);

    private GamesBench bench;

    /** Race Night on for the loop, with no schedule; the hype as given. */
    private static RaceNightSettings on(boolean hype) {
        RaceNightSettings d = RaceNightSettings.defaults();
        return new RaceNightSettings(true, List.of(), "loop", d.races(), d.laps(), d.announceMinutes(), d.joinMinutes(),
                10, d.minRacers(), d.maxRacers(), d.finishWindowSeconds(), d.maxRaceMinutes(), d.breakSeconds(), 0,
                d.points(), d.finishPoints(), d.stillRacingPoints(), d.prizes(), d.finisherPrize(),
                d.prizeEventsPerWeek(), d.season(), d.standRadius(), hype);
    }

    /** The server as the night sees it: the bench's clock; nothing else is asked before the window. */
    private final class Ports implements NightPorts {

        @Override
        public long now() {
            return bench.now();
        }

        @Override
        public long tick() {
            return 1_000;
        }

        @Override
        public boolean online(UUID player) {
            return false;
        }

        @Override
        public boolean free(UUID player) {
            return false;
        }

        @Override
        public String name(UUID player) {
            return "Ava";
        }

        @Override
        public String seat(UUID racer, Course base, Course raced, Course.Spot grid, Point stand, RaceLink link) {
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

    /** An admin's night on the loop, under {@code settings}. */
    private NightRunner adminNight(RaceNightSettings settings) throws Exception {
        bench = new GamesBench(T0, List.of(TimeTrials.SPEC, RaceNight.SPEC), "trials", TimeTrialsSettings.defaults(),
                "race_night", settings);
        List<Course.Mark> lap = List.of(new Course.Mark(20, 64, 20, 4), new Course.Mark(-20, 64, 20, 4),
                new Course.Mark(-20, 64, -20, 4), new Course.Mark(16, 64, -20, 4));
        Course built = new Course("loop", TrialKind.BOAT, "Ice Loop", Tier.EASY, "games",
                new Course.Spot(20, 64, -16, 0, 0), lap, new Course.Mark(20, 64, -14, 4), null, 30, true, false, 1);
        bench.dao().saveCourse(new GamesDao.CourseRow(built.id(), "trials", built.kind().id(), built.name(),
                built.world(), built.enabled(), CourseCodec.encode(built), built.rev(), 0, 0), false);
        Course loop = ((TimeTrials) bench.games().game("trials")).course("loop");
        assertNotNull(loop, "fixture: the loop is a Time Trials course");
        RaceNight night = (RaceNight) bench.games().game("race_night");
        EventDao dao = new EventDao(bench.db(), bench.dao());
        night.dao(dao);
        List<Course.Spot> grid = List.of(new Course.Spot(18.5, 64, -20, 0, 0), new Course.Spot(21.5, 64, -20, 0, 0),
                new Course.Spot(18.5, 64, -24, 0, 0), new Course.Spot(21.5, 64, -24, 0, 0));
        dao.setMeta(Tracks.GRID + "loop", RaceTrack.encodeGrid(loop.layoutHash(), grid)); // an admin's grid
        night.portsFor(id -> new Ports());
        assertNull(night.adminStart("Admin", "loop", null, null, 25, false), "fixture: an admin sets a night");
        NightRunner runner = night.night();
        assertNotNull(runner, "fixture: Race Night made it, and runs it");
        return runner;
    }

    @AfterEach
    void tearDown() throws Exception {
        if (bench != null) {
            bench.close();
        }
    }

    @Test
    void theNightGetsTheHypeAsShipped() throws Exception {
        NightRunner runner = adminNight(on(true));
        assertTrue(runner.hype(), "hype: true (shipped) reaches the night");
        assertEquals(0, bench.severe(), "nothing threw");
    }

    @Test
    void hypeFalseReachesTheNight() throws Exception {
        NightRunner runner = adminNight(on(false));
        assertFalse(runner.hype(), "hype: false reaches the night, so its lines stay as they were");
        assertEquals(0, bench.severe(), "nothing threw");
    }
}
