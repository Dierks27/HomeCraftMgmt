package com.dierks.homecraft.games.event;

import com.dierks.homecraft.games.GamesBench;
import com.dierks.homecraft.games.GameProgress;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.CourseCodec;
import com.dierks.homecraft.games.trial.Tier;
import com.dierks.homecraft.games.trial.TimeTrials;
import com.dierks.homecraft.games.trial.TimeTrialsSettings;
import com.dierks.homecraft.games.trial.TrialKind;
import com.dierks.homecraft.storage.EventDao;
import com.dierks.homecraft.storage.GamesDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What Race Night's boot does with the nights a stop or a crash left (EVENTS-DROPPER-SPEC §A.9,
 * {@link RaceNight#recover}), on the real framework and a real database: an open night far enough off
 * resumes (as scheduled, when an admin set it before a restart and its window hasn't opened); one
 * too close is called off; a night running with nothing raced is called off and gives its prize slot
 * back; one with a race stored settles on it; one paying finishes paying. Also: an admin night set
 * for later is written at once, so a restart before its window keeps it; a failed track is looked at
 * again once a minute, not every second; and the reload warning.
 */
class RaceNightRecoverTest {

    /** Tuesday 29 September 2026, noon (the kit's zone). */
    private static final long T0 = GamesBench.at(2026, 9, 29, 12, 0);
    private static final long MIN = 60_000L;
    private static final UUID A = new UUID(0, 1);
    private static final UUID B = new UUID(0, 2);
    private static final UUID C = new UUID(0, 3);

    private GamesBench bench;
    private GamesService games;
    private RaceNight night;
    private EventDao dao;
    private Course loop;

    /** Race Night switched on, for the loop, with no schedule (nights only when an admin starts one). */
    private static RaceNightSettings on() {
        RaceNightSettings d = RaceNightSettings.defaults();
        return new RaceNightSettings(true, List.of(), "loop", d.races(), d.laps(), d.announceMinutes(), d.joinMinutes(),
                d.adminJoinMinutes(), d.minRacers(), d.maxRacers(), d.finishWindowSeconds(), d.maxRaceMinutes(),
                d.breakSeconds(), d.warmupSeconds(), d.points(), d.finishPoints(), d.stillRacingPoints(), d.prizes(),
                d.finisherPrize(), d.prizeEventsPerWeek(), d.season(), d.standRadius());
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
        TimeTrials trials = (TimeTrials) games.game("trials");
        loop = trials.course("loop");
        assertNotNull(loop, "the loop is a Time Trials course");
        dao = new EventDao(bench.db(), bench.dao());
        night = (RaceNight) games.game("race_night");
        night.dao(dao);
        List<Course.Spot> grid = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            grid.add(new Course.Spot(20 + (i % 2 == 0 ? -1.5 : 1.5), 64, -20 - 4 * (i / 2), 0, 0));
        }
        dao.setMeta(Tracks.GRID + "loop", RaceTrack.encodeGrid(loop.layoutHash(), grid)); // an admin's grid
    }

    @AfterEach
    void tearDown() throws Exception {
        bench.close();
    }

    private static String rules() {
        return NightRules.of(RaceNightSettings.defaults(), 3, 0, false, 8).encode();
    }

    /** A night's row as a stop left it, with Ava, Ben and Cal joined. */
    private void left(String id, long joinAt, long startsAt, String state, boolean prized) throws Exception {
        dao.open(new EventDao.EventRow(id, "loop", joinAt, startsAt, EventDao.OPEN, rules(), 0, false, "", "", T0 - MIN,
                null, ""));
        for (UUID u : List.of(A, B, C)) {
            assertTrue(dao.join(id, u, u == A ? "Ava" : u == B ? "Ben" : "Cal", T0 - MIN), "joined");
        }
        if (prized) {
            assertTrue(dao.claimPrizeSlot(id, "2920", 3), "it claimed a prize night at race 1's Go");
        }
        if (!EventDao.OPEN.equals(state)) {
            dao.setState(id, state, "", null);
        }
    }

    @Test
    void anAdminNightSetBeforeARestartIsWrittenAtOnceAndResumesScheduled() throws Exception {
        assertNull(night.adminStart("Admin", "loop", null, null, 30, false), "an admin sets a night for 30 minutes on");
        String id = night.night().plan().id();
        EventDao.EventRow row = dao.event(id);
        assertNotNull(row, "written at once, though its window opens later");
        assertEquals(T0 + 30 * MIN, row.joinAt(), "with its own window");

        RaceNight afterRestart = new RaceNight(games.context()); // the next boot's Race Night
        afterRestart.dao(dao);
        afterRestart.recover();
        NightRunner back = afterRestart.night();
        assertNotNull(back, "the night is still on after the restart");
        assertEquals(id, back.plan().id(), "the same night");
        assertEquals(EventMachine.Phase.SCHEDULED, back.phase(), "its window hasn't opened yet: it opens when it said");
    }

    @Test
    void anOpenNightFarEnoughOffResumesWithItsSignUps() throws Exception {
        String id = EventPlan.adminId(LocalDate.of(2026, 9, 29), LocalTime.of(11, 50), 1);
        left(id, T0 - 10 * MIN, T0 + 20 * MIN, EventDao.OPEN, false);
        night.recover();
        assertNotNull(night.night(), "resumed");
        assertEquals(EventMachine.Phase.OPEN, night.night().phase(), "its window was open: open again");
        assertEquals(3, night.night().joined().size(), "with its three sign-ups");
    }

    @Test
    void anOpenNightTooCloseToItsStartIsCalledOff() throws Exception {
        String id = "rn-20260929-1201";
        left(id, T0 - 10 * MIN, T0 + MIN, EventDao.OPEN, false);
        night.recover();
        assertNull(night.night(), "not resumed");
        assertEquals(EventDao.CALLED_OFF, dao.event(id).state(), "called off: too close to its start");
    }

    @Test
    void aNightRunningWithNothingRacedIsCalledOffAndGivesItsPrizeSlotBack() throws Exception {
        String id = "rn-20260929-1150";
        left(id, T0 - 20 * MIN, T0 - 10 * MIN, EventDao.RUNNING, true);
        night.recover();
        EventDao.EventRow row = dao.event(id);
        assertEquals(EventDao.CALLED_OFF, row.state(), "called off: the server restarted during race 1");
        assertFalse(row.prized(), "nothing was raced, so the week's prize night is given back");
        assertEquals(0, dao.prizedIn("2920"), "and the week has all of them again");
    }

    @Test
    void aNightRunningAfterARaceSettlesOnItAndOwesThePrizes() throws Exception {
        String id = "rn-20260929-1140";
        left(id, T0 - 30 * MIN, T0 - 20 * MIN, EventDao.RUNNING, true);
        dao.storeRace(id, 1, List.of(new EventDao.RaceRow(id, 1, A, 1, 45_000L, 9, 10, "FINISHED"),
                new EventDao.RaceRow(id, 1, B, 2, 46_000L, 9, 8, "FINISHED"),
                new EventDao.RaceRow(id, 1, C, 3, 47_000L, 9, 6, "FINISHED")), null, T0 - 15 * MIN);
        night.recover();
        assertEquals(EventDao.CALLED_OFF, dao.event(id).state(), "called off, settled on race 1");
        EventDao.EntryRow ava = dao.entries(id).stream().filter(e -> e.player().equals(A)).findFirst().orElseThrow();
        assertEquals(1, ava.place(), "race 1's points stand: Ava 1st");
        assertEquals(5, ava.prize(), "and her prize is kept");
        assertEquals(1, dao.owed(A).size(), "owed until she is online where tokens can be earned");
        assertTrue(dao.event(id).prized(), "a night with a race stored keeps its prize slot");
    }

    /**
     * fx2-C #6: a night settled at boot from its stored rows still tells the achievements, by id (the
     * racers are offline then): everyone who started a race raced it, and its 1st won it.
     */
    @Test
    void aNightSettledAtBootStillTellsTheAchievementsWhoRacedAndWhoWon() throws Exception {
        Map<UUID, Boolean> told = new LinkedHashMap<>();
        games.progress(new GameProgress() {
            @Override
            public void raceNightFinished(UUID player, boolean won) {
                told.put(player, won);
            }
        });
        String id = "rn-20260929-1140";
        left(id, T0 - 30 * MIN, T0 - 20 * MIN, EventDao.RUNNING, true);
        dao.storeRace(id, 1, List.of(new EventDao.RaceRow(id, 1, A, 1, 45_000L, 9, 10, "FINISHED"),
                new EventDao.RaceRow(id, 1, B, 2, 46_000L, 9, 8, "FINISHED"),
                new EventDao.RaceRow(id, 1, C, null, null, 0, 0, "DNS")), null, T0 - 15 * MIN);
        night.recover();
        assertEquals(EventDao.CALLED_OFF, dao.event(id).state(), "called off, settled on race 1");
        assertEquals(Map.of(A, true, B, false), told,
                "Ava won it and Ben raced it; Cal never started a race, so it isn't his Race Night");
    }

    @Test
    void aNightThatWasPayingFinishesPayingAndIsDone() throws Exception {
        String id = "rn-20260929-1130";
        left(id, T0 - 40 * MIN, T0 - 30 * MIN, EventDao.SETTLING, true);
        night.recover();
        assertEquals(EventDao.DONE, dao.event(id).state(), "finished paying (what can't be paid is owed): DONE");
    }

    @Test
    void aFailedTrackIsLookedAtAgainOnceAMinuteAndTheReloadWarningNeedsARunningNight() {
        assertTrue(RaceNight.recheck(null, T0), "never failed: look");
        assertFalse(RaceNight.recheck(T0, T0 + 1_000), "failed a second ago: no scan of the world every second");
        assertTrue(RaceNight.recheck(T0, T0 + RaceNight.TRACK_RECHECK_MS), "a minute on: look again (an admin may have fixed it)");
        assertNull(RaceNight.reloadWarning(games), "no night at the track: a reload says nothing");
    }
}
