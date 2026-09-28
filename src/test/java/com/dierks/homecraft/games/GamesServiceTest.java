package com.dierks.homecraft.games;

import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.games.GamesKit.ChanceSettings;
import com.dierks.homecraft.games.GamesKit.Fake;
import com.dierks.homecraft.games.GamesKit.Host;
import com.dierks.homecraft.games.GamesKit.SkillSettings;
import com.dierks.homecraft.games.GamesKit.TestGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The framework's registry, lifecycle and failure isolation (spec §3.2, R3.2, R3.12).
 *
 * <p>Pinned here: a game whose constructor throws is left out (logged) while every other game is
 * built, and a reload tries it again; ids and aliases are found in any case and a course only
 * while its game is open; a game that throws is switched off alone, logged once, and its rounds
 * finished — a reload brings it back; a reload stops games that closed, starts games that opened
 * and leaves running games running; {@code /hcm play} runs the gate before the game; a stop while
 * the server is stopping leaves OPEN rounds for the next start, any other stop finishes them; the
 * Games screen stays shut while the games are off.
 */
class GamesServiceTest {

    private Host host;
    private TestGame snake;
    private TestGame slots;
    private TestGame trials;
    private GamesService games;
    private Fake alex;

    @BeforeEach
    void setUp() {
        host = new Host(GamesKit.at(2026, 3, 10, 15, 0));
        snake = GamesKit.skill("test_snake", "Test Snake");
        snake.aliases = List.of("Worm");
        slots = GamesKit.chance();
        trials = new TestGame("test_trials", GameKind.TRIAL, "Test Trials", TokenService.Source.GAMES_PARKOUR);
        trials.playables.add(new Game.Playable("river_run", "River Run", trials, TokenService.Source.GAMES_BOAT, "easy"));
        host.config = GamesKit.config(GamesKit.common(true, 100, 600, 6),
                "test_slots", new ChanceSettings(true, List.of(1, 5, 10), 30));
        games = GamesKit.service(host, List.of(
                GamesKit.spec(slots, new ChanceSettings(true, List.of(1, 5, 10), 30), (seed, stake, data) -> stake),
                GamesKit.spec(snake, new SkillSettings(true, 2), null),
                GamesKit.spec(trials, new SkillSettings(true, 4), null)));
        alex = new Fake("Alex");
        host.online.put(alex.id, alex.player);
        host.give(alex.id, 100);
    }

    @AfterEach
    void tearDown() throws Exception {
        host.connection.close();
    }

    @Test
    void aGameWhoseConstructorThrowsIsLeftOutAndTheRestAreBuilt() {
        AtomicInteger tries = new AtomicInteger();
        TestGame merge = GamesKit.skill("test_merge", "Test Merge");
        GamesService service = GamesKit.service(host, List.of(
                GamesKit.spec("test_merge", GameKind.CABINET, new SkillSettings(true, 2), ctx -> {
                    if (tries.incrementAndGet() == 1) {
                        throw new IllegalStateException("not today");
                    }
                    return merge;
                }, null),
                GamesKit.spec(snake, new SkillSettings(true, 2), null)));
        assertEquals(List.of(snake), List.copyOf(service.games()), "the game that threw is left out, the rest built");
        assertTrue(service.unbuilt().containsKey("test_merge"), "and it is listed as not built, for status");
        assertEquals(1, host.severe(), "logged once");
        service.reload();
        assertEquals(List.of(merge, snake), List.copyOf(service.games()), "a reload tries it again, in catalog order");
        assertTrue(service.unbuilt().isEmpty(), "and it is off the list");
    }

    @Test
    void idsAndAliasesAreFoundInAnyCaseAndCoursesOnlyWhileTheirGameIsOpen() {
        assertSame(snake, games.game("TEST_SNAKE"), "an id in any case");
        assertSame(snake, games.game(" worm "), "an alias in any case, trimmed");
        assertNull(games.game("nothing"), "an unknown id");
        GamesService.Target t = games.resolve("River_Run");
        assertNotNull(t, "a course id resolves");
        assertSame(trials, t.game(), "to its game");
        assertEquals("river_run", t.playable().id(), "and the course");
        trials.open = false;
        assertNull(games.resolve("river_run"), "not while its game is closed");
    }

    @Test
    void aGameThatThrowsIsSwitchedOffAloneLoggedOnceAndReloadBringsItBack() {
        slots.throwOnOpen = true;
        games.start();
        games.rounds().open(alex.player, slots, 10, "v1");
        assertFalse(games.open(alex.player, "test_slots", null), "opening it fails");
        assertTrue(games.failed(slots), "the game is switched off");
        assertFalse(games.enabled(slots), "and closed");
        assertTrue(games.enabled(snake), "every other game is untouched");
        assertEquals(1, slots.stopped, "the failed game was stopped");
        games.guard(slots, () -> {
            throw new IllegalStateException("again");
        });
        assertEquals(1, host.severe(), "a failure is logged once, however often the game throws");
        host.runTasks();
        assertNull(games.rounds().openRound(alex.id, slots.id()), "its open round is finished (the tick after)");
        assertEquals(Refusal.BROKEN, games.canOpen(alex.player, slots), "players are told it is taking a break");
        slots.throwOnOpen = false;
        games.reload();
        assertFalse(games.failed(slots), "a reload clears the failure");
        assertEquals(2, slots.started, "and starts it again");
        assertTrue(games.open(alex.player, "test_slots", null), "it opens again");
    }

    @Test
    void aReloadStopsGamesThatClosedStartsOnesThatOpenedAndLeavesTheRestRunning() {
        games.start();
        assertEquals(1, snake.started, "started at enable");
        snake.open = false;
        slots.open = true;
        games.reload();
        assertEquals(1, snake.stopped, "a game that closed is stopped");
        assertEquals(1, slots.started, "a game that stayed open is not started twice");
        assertEquals(0, slots.stopped, "nor stopped");
        snake.open = true;
        games.reload();
        assertEquals(2, snake.started, "a game that opened again is started");
    }

    @Test
    void playRunsTheGateBeforeTheGame() {
        alex.denied.add("hcm.games.chance");
        assertFalse(games.open(alex.player, "test_slots", null), "a player without the chance permission");
        assertEquals(0, slots.opened, "never reaches the game");
        assertTrue(alex.heard().contains("Games of chance aren't open to you."), "and is told plainly");
        assertTrue(games.open(alex.player, "worm", null), "an alias opens its game");
        assertEquals(1, snake.opened, "once");
        assertTrue(games.open(alex.player, "river_run", null), "a course starts in its game");
        assertEquals(1, trials.played, "through play()");
        assertFalse(games.open(alex.player, "nope", null), "an unknown id");
        assertTrue(alex.heard().contains("There's no game called \"nope\"."), "says so");
    }

    @Test
    void aStopWhileTheServerIsStoppingLeavesOpenRoundsForTheNextStart() {
        games.start();
        games.rounds().open(alex.player, slots, 10, "v1");
        host.stopping = true;
        games.stop();
        assertNotNull(games.rounds().openRound(alex.id, slots.id()), "the round waits for the next start");
        assertEquals(1, slots.stopped, "the games are stopped");
    }

    @Test
    void anyOtherStopFinishesOpenRounds() {
        games.start();
        games.rounds().open(alex.player, slots, 10, "v1");
        games.stop();
        assertNull(games.rounds().openRound(alex.id, slots.id()), "finished by the exit rule");
        assertEquals(100, host.balanceOf(alex.id), "which here gives the 10 back");
    }

    @Test
    void theGamesScreenStaysShutWhileTheGamesAreOffOrElsewhere() {
        host.config = GamesKit.config(GamesKit.common(false, 100, 600, 6));
        games.openGamesScreen(alex.player, null);
        assertTrue(alex.heard().contains("The games are closed right now."), "games.enabled false");
        host.config = GamesKit.config(GamesKit.common(true, 100, 600, 6));
        alex.world = GamesKit.world("world_nether");
        games.openGamesScreen(alex.player, null);
        assertTrue(alex.heard().contains("Games can't be played in this world."), "a world without games");
        alex.world = GamesKit.world("world");
        alex.said.clear();
        games.openGamesScreen(alex.player, null);
        assertEquals(List.of("Coming soon!"), alex.said, "otherwise the installed screens open (none yet)");
    }

    @Test
    void joinsAndQuitsReachOnlyOpenGames() {
        games.start();
        snake.open = false;
        games.onJoin(alex.player);
        games.onQuit(alex.player);
        assertEquals(0, snake.joins + snake.quits, "a closed game hears nothing");
        assertEquals(1, slots.joins, "an open one hears the join");
        assertEquals(1, slots.quits, "and the quit");
    }

    @Test
    void goingBackToARoundAlreadyPaidForSkipsTheGate() {
        ChanceRounds.Round round = games.rounds().open(alex.player, slots, 5, "v1");
        assertNotNull(round, "a round opens");
        games.breaks().pause(alex.id, 1);
        assertTrue(games.open(alex.player, "test_slots", null),
                "a pause stops new plays, not the way back to a hand already paid for (spec §5.2)");
        assertEquals(1, slots.opened, "the game opens to resume it");
        assertTrue(games.rounds().close(round, 0, "v1"), "the hand is finished");
        assertFalse(games.open(alex.player, "test_slots", null), "with the round finished, the pause applies again");
        assertEquals(1, slots.opened, "and the game isn't opened");
    }
}
