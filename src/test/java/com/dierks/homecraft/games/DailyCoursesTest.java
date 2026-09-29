package com.dierks.homecraft.games;

import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.games.GamesKit.Host;
import com.dierks.homecraft.games.GamesKit.SkillSettings;
import com.dierks.homecraft.games.GamesKit.TestGame;
import com.dierks.homecraft.games.gen.DailyCourses;
import com.dierks.homecraft.games.gen.DailySettings;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Planner;
import com.dierks.homecraft.games.gen.engine.GenHost;
import com.dierks.homecraft.games.gen.engine.GenService;
import com.dierks.homecraft.games.gen.engine.GenStore;
import com.dierks.homecraft.games.gen.engine.Person;
import com.dierks.homecraft.games.gen.engine.WorldPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Daily Courses as a game of the framework (GEN-SPEC §5.4, §6 S8): it is the catalog's {@code daily},
 * a TRIAL that is never today's pick; {@code /hcm play daily} opens it and {@code daily_parkour} is
 * its one playable; it follows {@code games.daily.enabled}; and anything its engine throws closes
 * Daily Courses alone — the gate goes with it, and the other games play on.
 */
class DailyCoursesTest {

    private Host host;
    private TestGame trials;
    private GamesService games;

    @BeforeEach
    void setUp() {
        host = new Host(GamesKit.at(2026, 9, 29, 15, 0));
        trials = new TestGame("test_trials", GameKind.TRIAL, "Test Trials", TokenService.Source.GAMES_PARKOUR);
        host.config = GamesKit.config(GamesKit.common(true, 100, 600, 6), "daily", on(true));
        games = GamesKit.service(host, List.of(GamesKit.spec(trials, new SkillSettings(true, 4), null),
                DailyCourses.SPEC));
    }

    @AfterEach
    void tearDown() throws Exception {
        host.connection.close();
    }

    private static DailySettings on(boolean enabled) {
        DailySettings d = DailySettings.defaults();
        return new DailySettings(enabled, d.world(), d.rollover(), d.startupDelaySeconds(),
                d.avoidBeforeRestartMinutes(), d.retryMinutes(), d.maxTriesPerDay(), d.clearWaitMinutes(),
                d.keepDays(), d.worldRules(), d.safeSpot(), d.dailyCap(), d.starGoals(), d.starGoalReward(),
                d.budget(), d.stars(), d.slots());
    }

    @Test
    void dailyIsTheCatalogsLastGameAndPlaysThroughTheFramework() {
        List<GameSpec<?>> specs = GameCatalog.SPECS;
        assertSame(DailyCourses.SPEC, specs.get(specs.size() - 1), "daily is listed last");
        assertEquals("daily", DailyCourses.SPEC.id(), "its id is daily");
        assertEquals(GameKind.TRIAL, DailyCourses.SPEC.kind(), "a course game");
        assertEquals(DailySettings.KEYS, DailyCourses.SPEC.keys(), "it ships its own keys");

        Game daily = games.game("daily");
        assertInstanceOf(DailyCourses.class, daily, "the framework built it");
        assertTrue(games.enabled(daily), "it follows games.daily.enabled");
        assertFalse(daily.featurable(), "never today's pick");
        assertEquals(TokenService.Source.GAMES_DAILY, daily.source(), "its own ledger source");
        GamesService.Target t = games.resolve("Daily_Parkour");
        assertNotNull(t, "/hcm play daily_parkour resolves");
        assertSame(daily, t.game(), "to Daily Courses");
        assertEquals("daily_parkour", t.playable().id(), "as its tier picker");
        assertNull(games.resolve("daily").playable(), "/hcm play daily opens the game itself");
        assertFalse(daily.play(null, "sky_rings", null), "it plays nothing else itself (slots are course rows)");
        assertEquals(List.of("not running"), daily.statusLines(), "status says when its engine isn't running");
        for (String line : daily.rules()) {
            assertEquals(List.of(), com.dierks.homecraft.games.gen.api.GenCopy.copyProblems(line), line);
        }

        host.config = GamesKit.config(GamesKit.common(true, 100, 600, 6), "daily", on(false));
        assertFalse(games.enabled(daily), "switched off in config it is closed");
        assertEquals("games.daily.enabled is false", games.closedReason(daily), "and status says why");
        assertNull(games.resolve("daily_parkour"), "and its playable is gone");
    }

    @Test
    void anythingTheEngineThrowsClosesDailyAloneAndItsGateWithIt() {
        Game daily = games.game("daily");
        boolean[] broken = {false};
        GenService engine = new GenService(host(broken), Map.<String, Planner>of());
        engine.start();
        games.generated(engine);
        assertSame(engine, games.generated(), "while daily is open its engine is the gate");
        GenTag tag = new GenTag("daily_golf", "golf", 1, 20725, 0, 1, 'A', "abc", 0, 0, 0, List.of(), List.of(), 0);
        assertFalse(games.generated().live("daily_golf", tag), "nothing unverified is live");

        games.guard(daily, engine::check);
        assertFalse(games.failed(daily), "a quiet check is fine");
        broken[0] = true;
        games.guard(daily, engine::check);
        assertTrue(games.failed(daily), "a throw from the engine switches Daily Courses off");
        assertSame(GeneratedCourses.NONE, games.generated(), "and with it the gate: nothing generated is live");
        assertTrue(games.generated().live("river_run", null), "hand-built courses are untouched");
        assertFalse(games.failed(trials), "the other games are not touched");
        assertTrue(games.enabled(trials), "and stay open");
        games.coursesChanged("test_trials");
        assertEquals(1, trials.coursesChanged, "and still hear about their courses");
    }

    /** The engine's host over the kit's database; {@code broken[0]} makes its settings throw. */
    private GenHost host(boolean[] broken) {
        GenStore store = GenStore.of(host.db);
        return new GenHost() {
            @Override
            public long now() {
                return host.time.now;
            }

            @Override
            public long nanoTime() {
                return System.nanoTime();
            }

            @Override
            public Logger logger() {
                return host.logger;
            }

            @Override
            public DailySettings settings() {
                if (broken[0]) {
                    throw new IllegalStateException("broken on purpose");
                }
                return on(true);
            }

            @Override
            public RestartHold restartHold() {
                return new RestartHold(List.of(), GamesKit.ZONE, 5);
            }

            @Override
            public ZoneId zone() {
                return GamesKit.ZONE;
            }

            @Override
            public DayOfWeek weekStart() {
                return DayOfWeek.MONDAY;
            }

            @Override
            public List<String> gamesWorlds() {
                return List.of("games");
            }

            @Override
            public int fallDepth() {
                return 6;
            }

            @Override
            public WorldPort world(String name) {
                return null;
            }

            @Override
            public GenStore store() {
                return store;
            }

            @Override
            public Executor planner() {
                return Runnable::run;
            }

            @Override
            public void coursesChanged(String gameId) {
                games.coursesChanged(gameId);
            }

            @Override
            public List<Person> people() {
                return List.of();
            }

            @Override
            public boolean anyoneOnline() {
                return false;
            }

            @Override
            public double mspt() {
                return 10;
            }

            @Override
            public void tell(UUID player, String line) {
            }

            @Override
            public void actionBar(UUID player, String line) {
            }

            @Override
            public void endRun(UUID player) {
            }

            @Override
            public void move(UUID player, String world, double x, double y, double z) {
            }
        };
    }
}
