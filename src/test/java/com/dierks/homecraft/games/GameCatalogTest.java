package com.dierks.homecraft.games;

import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.games.GamesKit.Host;
import com.dierks.homecraft.games.GamesKit.SkillSettings;
import com.dierks.homecraft.games.GamesKit.TestGame;
import com.dierks.homecraft.games.gen.api.Slots;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fresh Courses' ids in the {@code /hcm play} namespace (GEN-SPEC §5.4, weekly addendum §2): every
 * slot id, {@code fresh_courses} and {@code fresh_parkour_tiers} are taken, so no hand-built course
 * can ever be made with one; but {@code /hcm play} doesn't ask that question, so a slot's own course
 * still resolves.
 */
class GameCatalogTest {

    private Host host;
    private TestGame trials;
    private GamesService games;

    @BeforeEach
    void setUp() {
        host = new Host(GamesKit.at(2026, 9, 29, 15, 0));
        trials = new TestGame("test_trials", GameKind.TRIAL, "Test Trials", TokenService.Source.GAMES_PARKOUR);
        trials.playables.add(new Game.Playable("fresh_golf", "Daily Golf", trials, TokenService.Source.GAMES_GOLF, ""));
        games = GamesKit.service(host, List.of(GamesKit.spec(trials, new SkillSettings(true, 4), null)));
    }

    @AfterEach
    void tearDown() throws Exception {
        host.connection.close();
    }

    @Test
    void everyFreshCoursesIdIsTaken() {
        for (String id : Slots.RESERVED) {
            assertTrue(GameCatalog.taken(id), id + " can't be a hand-built course's id");
            assertTrue(GameCatalog.taken(" " + id.toUpperCase() + " "), id + " in any case, with spaces");
            assertTrue(GameCatalog.taken(id, games), id + " with the service's check too");
        }
        assertEquals(9, Slots.RESERVED.size(), "seven slots, the Fresh Courses screen and the level picker");
        assertFalse(GameCatalog.taken("fresh_golf_2"), "a name that only starts like one is free");
        assertFalse(GameCatalog.taken("daily"), "the old daily id never shipped and is free");
        assertFalse(GameCatalog.taken("river_run"), "a hand-built id is free");
        assertTrue(GameCatalog.taken("accept"), "the old reserved words still are");
        assertTrue(GameCatalog.taken("snake"), "and the games' ids");
    }

    @Test
    void aSlotsCourseStillResolvesForPlay() {
        GamesService.Target t = games.resolve("Fresh_Golf");
        assertNotNull(t, "/hcm play fresh_golf finds the course, taken or not");
        assertSame(trials, t.game(), "in the game that runs it");
        assertEquals("fresh_golf", t.playable().id(), "and the course itself");
    }
}
