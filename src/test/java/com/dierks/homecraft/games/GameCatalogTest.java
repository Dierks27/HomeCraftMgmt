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
        assertEquals(12, Slots.RESERVED.size(), "seven slots, three Classics slots, the Fresh Courses screen and the"
                + " level picker");
        assertFalse(GameCatalog.taken("fresh_golf_2"), "a name that only starts like one is free");
        assertFalse(GameCatalog.taken("daily"), "the old daily id never shipped and is free");
        assertFalse(GameCatalog.taken("river_run"), "a hand-built id is free");
        assertTrue(GameCatalog.taken("accept"), "the old reserved words still are");
        assertTrue(GameCatalog.taken("snake"), "and the games' ids");
    }

    @Test
    void raceNightAndFallingFloorsTakeTheirIdsAndAliases() {
        // EVENTS-DROPPER-SPEC C1: the two new games' ids, their aliases race and tnt_run, and the
        // Weekly Cup's /hcm play cup off, so no hand-built course can take one
        GamesService withThem = GamesKit.service(host, List.of(GamesKit.spec(trials, new SkillSettings(true, 4), null),
                com.dierks.homecraft.games.event.RaceNight.SPEC, com.dierks.homecraft.games.arena.FallingFloors.SPEC));
        for (String id : List.of("race_night", "falling_floors", "cup")) {
            assertTrue(GameCatalog.taken(id), id + " is taken without the service");
        }
        for (String id : List.of("race_night", "race", "RACE", "falling_floors", "tnt_run", "cup")) {
            assertTrue(GameCatalog.taken(id, withThem), id + " is taken");
        }
        assertEquals("race_night", withThem.game("race").id(), "/hcm play race is Race Night");
        assertEquals("falling_floors", withThem.game("tnt_run").id(), "/hcm play tnt_run is Falling Floors");
        assertFalse(withThem.enabled(withThem.game("race_night")), "Race Night is coming soon: closed");
        assertFalse(withThem.enabled(withThem.game("falling_floors")), "and so is Falling Floors");
        assertFalse(withThem.game("race_night").featurable(), "a night at set times is never today's pick");
        assertTrue(withThem.game("falling_floors").featurable(), "Falling Floors may be");
        GamesService.Target t = withThem.resolve("fresh_golf");
        assertNotNull(t, "resolving a course is unaffected");
        assertSame(trials, t.game(), "it is still the trials' course");
        assertFalse(GameCatalog.taken("racer"), "a word that only starts like one is free");
    }

    @Test
    void aSlotsCourseStillResolvesForPlay() {
        GamesService.Target t = games.resolve("Fresh_Golf");
        assertNotNull(t, "/hcm play fresh_golf finds the course, taken or not");
        assertSame(trials, t.game(), "in the game that runs it");
        assertEquals("fresh_golf", t.playable().id(), "and the course itself");
    }
}
