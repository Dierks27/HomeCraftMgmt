package com.dierks.homecraft.games;

import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.games.GamesKit.ChanceSettings;
import com.dierks.homecraft.games.GamesKit.Host;
import com.dierks.homecraft.games.GamesKit.SkillSettings;
import com.dierks.homecraft.games.GamesKit.TestGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The featured game of the day (spec §8.3) through the framework.
 *
 * <p>Pinned here: {@code auto} picks among the open skill games and courses only — never a game of
 * chance, never a closed game, and a world game by its courses — by a hash of the LOCAL day, the
 * same pick all day and the pure {@link Featured#pick} over the candidates; a pinned id wins while
 * it is an open skill game or course; {@code until} is the next local midnight.
 */
class FeaturedTest {

    private Host host;
    private TestGame snake;
    private TestGame merge;
    private TestGame trials;
    private TestGame slots;
    private GamesService games;

    @BeforeEach
    void setUp() {
        host = new Host(GamesKit.at(2026, 3, 10, 15, 0));
        snake = GamesKit.skill("test_snake", "Test Snake");
        merge = GamesKit.skill("test_merge", "Test Merge");
        slots = GamesKit.chance();
        trials = new TestGame("test_trials", GameKind.TRIAL, "Test Trials", TokenService.Source.GAMES_PARKOUR);
        trials.playables.add(new Game.Playable("river_run", "River Run", trials, TokenService.Source.GAMES_BOAT, "easy"));
        trials.playables.add(new Game.Playable("cliff_hop", "Cliff Hop", trials, TokenService.Source.GAMES_PARKOUR,
                "hard"));
        games = GamesKit.service(host, List.of(
                GamesKit.spec(slots, new ChanceSettings(true, List.of(1), 30), null),
                GamesKit.spec(snake, new SkillSettings(true, 2), null),
                GamesKit.spec(merge, new SkillSettings(true, 2), null),
                GamesKit.spec(trials, new SkillSettings(true, 4), null)));
    }

    @AfterEach
    void tearDown() throws Exception {
        host.connection.close();
    }

    @Test
    void theCandidatesAreOpenSkillGamesAndCoursesNeverAGameOfChance() {
        assertEquals(List.of("test_snake", "test_merge", "cliff_hop", "river_run"), games.featured().candidates(),
                "catalog order, a world game by its courses (by id), and no game of chance");
        merge.open = false;
        assertEquals(List.of("test_snake", "cliff_hop", "river_run"), games.featured().candidates(),
                "a closed game is never featured");
        trials.playables.clear();
        assertEquals(List.of("test_snake"), games.featured().candidates(), "a world game with no course has nothing");
    }

    @Test
    void theAutoPickIsTheHashOfTheLocalDayAndStaysAllDay() {
        List<String> candidates = games.featured().candidates();
        Set<String> seen = new HashSet<>();
        for (int d = 0; d < 30; d++) {
            host.time.now = GamesKit.at(2026, 3, 1, 0, 30) + d * 86_400_000L;
            String morning = games.featured().today();
            assertEquals(Featured.pick(candidates, host.clock.dayKey()), morning, "day " + d + ": the pure pick");
            host.move(22 * 3_600_000L);
            assertEquals(morning, games.featured().today(), "day " + d + ": the same pick late in the evening");
            assertTrue(games.featured().isFeatured(morning.toUpperCase()), "isFeatured ignores case");
            seen.add(morning);
        }
        assertTrue(seen.size() > 1, "the pick moves from day to day");
    }

    @Test
    void aPinnedIdWinsWhileItIsAnOpenSkillGameOrCourse() {
        host.config = GamesKit.config(GamesKit.featured(GamesKit.common(true, 100, 600, 6), "river_run"));
        assertEquals("river_run", games.featured().today(), "a pinned course");
        host.config = GamesKit.config(GamesKit.featured(GamesKit.common(true, 100, 600, 6), "test_slots"));
        assertFalse("test_slots".equals(games.featured().today()), "a pinned game of chance is never featured");
        host.config = GamesKit.config(GamesKit.featured(GamesKit.common(true, 100, 600, 6), "test_merge"));
        merge.open = false;
        games.featured().forget();
        assertEquals(Featured.pick(games.featured().candidates(), host.clock.dayKey()), games.featured().today(),
                "a pinned game that is closed falls back to the day's pick");
    }

    @Test
    void nothingIsFeaturedWhileTheGamesAreOff() {
        host.config = GamesKit.config(GamesKit.common(false, 100, 600, 6));
        assertNull(games.featured().today(), "no open game, no pick");
    }

    @Test
    void thePickChangesAtTheNextLocalMidnight() {
        assertEquals(GamesKit.at(2026, 3, 11, 0, 0), games.featured().until(), "midnight where the players live");
    }
}
