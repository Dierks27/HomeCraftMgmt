package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.GamesBench;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.Scores;
import com.dierks.homecraft.storage.GamesDao;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code games.trials.rider_runs_count: false} in a party race (the Clubhouse review, #12): a ride with a
 * rider is just for fun, which the driver was warned means no board, record, rewards or Cup time. It
 * must not also cost the driver their PLACE: the party's standings, board and podium keep it, and only
 * the course's normal run (and its quest step) is skipped. The rider is latched on the run when they
 * sit down, so one who hops out just before the line doesn't make the ride count.
 */
class RiderFunRaceTest {

    private static final long T0 = GamesBench.at(2026, 9, 29, 12, 0);
    private static final Course BUILT = LapsTest.loop(6, 2);
    private static final RaceGrid.Surface ICE = (x, y, z) -> y < 65 ? RaceGrid.Cell.SOLID : RaceGrid.Cell.AIR;

    private GamesBench bench;
    private TimeTrials trials;
    private Course loop;
    private RaceBench race;
    private final RaceBench.Told told = new RaceBench.Told();
    private Player ava;
    private Player ben;

    @BeforeEach
    void setUp() throws Exception {
        TimeTrialsSettings d = TimeTrialsSettings.defaults();
        TimeTrialsSettings funOnly = new TimeTrialsSettings(d.enabled(), d.firstClear(), d.weeklyBestBonus(),
                d.courseOfWeekBonus(), d.dailyCap(), d.fallDepth(), d.minSeconds(), d.warmupSeconds(), d.partyMax(),
                false);
        bench = new GamesBench(T0, List.of(TimeTrials.SPEC), "trials", funOnly);
        GamesService games = bench.games();
        games.progress(told);
        trials = (TimeTrials) games.game("trials");
        bench.dao().saveCourse(new GamesDao.CourseRow(BUILT.id(), "trials", BUILT.kind().id(), BUILT.name(),
                BUILT.world(), BUILT.enabled(), CourseCodec.encode(BUILT), BUILT.rev(), 0, 0), false);
        loop = trials.course(BUILT.id());
        assertNotNull(loop, "the boat loop");
        race = new RaceBench(trials);
        ava = bench.player("Ava");
        ben = bench.player("Ben");
        race.add(ava);
        race.add(ben);
    }

    @AfterEach
    void tearDown() throws Exception {
        bench.close();
    }

    private Long best(Player p) throws SQLException {
        return bench.dao().best(p.getUniqueId(), TimeTrials.SPEC.id(), Scores.course(loop.id()));
    }

    @Test
    void aDriverWithARiderKeepsTheirPlaceButTheRunIsNeverTheCoursesNormalRun() throws Exception {
        List<PartyRace.Racer> racers = List.of(new PartyRace.Racer(ava.getUniqueId(), "Ava"),
                new PartyRace.Racer(ben.getUniqueId(), "Ben"));
        PartyRace party = new PartyRace(7, loop, racers, 0, () -> race.tick, new ArrayList<String>()::add);
        List<Course.Spot> grid = RaceGrid.forCourse(loop, ICE, 2).spots();
        for (int i = 0; i < racers.size(); i++) {
            UUID id = racers.get(i).id();
            assertNull(race.seat(id, loop, Laps.raced(loop, grid.get(i), 0).course(), grid.get(i), null, party), "seated");
            party.seated(id, grid.get(i));
        }
        assertTrue(party.seatingDone(), "both seated: the race is on");
        boolean ended = false;
        List<Player> order = List.of(ava, ben);
        int next = 0;
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
                    if (party.state() == PartyRace.State.RACING && next < order.size() && race.tick % 40 == 0) {
                        if (next == 0) {
                            // Kid sat in the back of Ava's boat at the start, and hopped out before the line
                            TrialRun run = trials.run(ava.getUniqueId());
                            run.hadRider = true;
                            assertNull(trials.riders().ofDriver(ava.getUniqueId()), "no rider aboard at the line");
                        }
                        race.cross(order.get(next).getUniqueId(), 45_000 + next * 1_000L);
                        next++;
                    }
                }
            }
        }
        assertTrue(ended, "both in: the race is over");
        List<PartyRace.Line> results = party.results();
        assertEquals(PartyRace.Result.FINISHED, results.get(0).result(), "Ava's finish stands in the party's race");
        assertEquals(ava.getUniqueId(), results.get(0).id(), "and she won it: the ride never changed the placing");
        assertEquals(1, results.get(0).rank(), "1st");
        assertEquals(PartyRace.Result.FINISHED, results.get(1).result(), "Ben 2nd");

        assertNull(best(ava), "just for fun: no board time (latched: the rider had hopped out before the line)");
        assertEquals(0, told.courses(ava.getUniqueId()), "and no quest step");
        assertFalse(bench.connection().prepareStatement("SELECT 1 FROM game_rewards WHERE player = '"
                + ava.getUniqueId() + "'").executeQuery().next(), "and no reward");
        assertEquals(46_000L, best(ben), "Ben's run with no rider is his normal counted run");
        assertEquals(1, told.courses(ben.getUniqueId()), "once");
        assertEquals(0, bench.severe(), "nothing threw");
    }

    @Test
    void aSoloRunThatHadARiderIsJustForFunEvenIfTheyHoppedOutBeforeTheLine() {
        FairPlay.Verdict ok = new FairPlay.Verdict(FairPlay.Kind.COUNTED, null);
        TrialRun run = new TrialRun(ava.getUniqueId(), loop, false, 0);
        assertEquals(ok, trials.withRider(ava, run, ok), "no rider: counts");
        run.hadRider = true;
        FairPlay.Verdict fun = trials.withRider(ava, run, ok);
        assertEquals(FairPlay.Kind.VOID, fun.kind(), "a rider at any point of the run: just for fun");
        assertEquals(Riders.FUN_ONLY, fun.reason(), "said why");
    }
}
