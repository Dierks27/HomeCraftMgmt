package com.dierks.homecraft.games;

import com.dierks.homecraft.games.ChanceRounds.Round;
import com.dierks.homecraft.games.GamesKit.ChanceSettings;
import com.dierks.homecraft.games.GamesKit.Fake;
import com.dierks.homecraft.games.GamesKit.Host;
import com.dierks.homecraft.games.GamesKit.TestGame;
import com.dierks.homecraft.games.cabinet.CabinetGame;
import com.dierks.homecraft.games.cabinet.CabinetSettings;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scheduled-restart hold where it applies, through the framework over a real database and
 * the kit's movable clock (restarts at 4:00 PM Chicago time, held from 3:55 PM).
 *
 * <p>Pinned here: entering a world game is refused while held, with the restart's time, and a
 * closed game still says it is closed; a new multi-step round of chance is refused before anything
 * is taken, while an OPEN round resumes, moves, takes a Double and settles as normal, and an
 * instant play isn't held at all; today's cabinet board is practice while held, with the try kept
 * and one line saying why, and is the scored try again after the restart; the hold follows the
 * live config (none set, nothing held) and the status line reads it in the players' zone.
 */
class RestartHoldServiceTest {

    private static final String HELD = "The server restarts at 4:00 PM. New runs open again after it.";
    private static final String DAILY_HELD =
            "The server restarts at 4:00 PM, so today's board is practice for now. Your scored try waits until after.";

    private Host host;
    private TestGame slots;
    private TestGame course;
    private GamesService games;
    private Fake alex;

    @BeforeEach
    void setUp() {
        host = new Host(GamesKit.at(2026, 6, 10, 15, 50));
        slots = GamesKit.chance();
        course = new TestGame("test_trials", GameKind.TRIAL, "Test Trials",
                com.dierks.homecraft.arcade.TokenService.Source.GAMES_PARKOUR);
        ChanceSettings settings = new ChanceSettings(true, List.of(1, 5, 10), 30);
        host.config = GamesKit.config(GamesKit.common(true, 100, 600, 6)
                .withRestarts(List.of(LocalTime.of(4, 0), LocalTime.of(16, 0)), 5), "test_slots", settings);
        games = GamesKit.service(host, List.of(GamesKit.spec(slots, settings, (seed, stake, data) -> stake),
                GamesKit.spec(course, new GamesKit.SkillSettings(true, 10), null)));
        alex = new Fake("Alex");
        host.online.put(alex.id, alex.player);
        host.give(alex.id, 100);
    }

    @AfterEach
    void tearDown() throws Exception {
        host.connection.close();
    }

    /** Move the clock to a Chicago wall-clock time on the test day. */
    private void clockTo(int hour, int minute) {
        host.time.now = GamesKit.at(2026, 6, 10, hour, minute);
    }

    // ---- a. entering a world game ----------------------------------------------------------------

    @Test
    void enteringAWorldGameIsRefusedOnlyWhileTheRestartIsMinutesAway() {
        clockTo(15, 54);
        assertNull(games.sessions().entryRefusal(course), "at 3:54 PM a course starts as normal");
        clockTo(15, 55);
        Refusal held = games.sessions().entryRefusal(course);
        assertNotNull(held, "from 3:55 PM a new run would be cut off by the 4:00 PM restart");
        assertEquals(Refusal.Reason.RESTART, held.reason(), "it is refused for the restart");
        assertEquals(HELD, held.message(), "and the player reads when, and that it opens again after");
        clockTo(15, 59);
        assertEquals(HELD, games.sessions().entryRefusal(course).message(), "right up to the restart");
        clockTo(16, 0);
        assertNull(games.sessions().entryRefusal(course), "once the restart time has come, runs start again");
        assertEquals(100, host.balanceOf(alex.id), "nothing was taken for any of it");
    }

    @Test
    void aClosedWorldGameStillSaysItIsClosedDuringTheHold() {
        clockTo(15, 57);
        course.open = false;
        assertEquals(Refusal.CLOSED, games.sessions().entryRefusal(course), "closed comes first: it says what is true");
        assertEquals(Refusal.CLOSED, games.sessions().entryRefusal(null), "no game at all is closed too");
    }

    // ---- b. a new round of chance --------------------------------------------------------------------

    @Test
    void aNewMultiStepRoundIsRefusedBeforeAnythingIsTaken() throws Exception {
        clockTo(15, 56);
        assertNull(games.rounds().open(alex.player, slots, 10, "v1"), "no new hand in the last five minutes");
        assertEquals(100, host.balanceOf(alex.id), "the stake was never taken");
        assertEquals(0, count("SELECT COUNT(*) FROM game_rounds"), "no round was written");
        assertEquals(0, count("SELECT COUNT(*) FROM token_ledger WHERE source = 'ARCADE_SLOTS'"), "nothing in the ledger");
        assertEquals(List.of(HELD), alex.said, "the player was told once, with the restart's time");
        assertNull(games.canStake(alex.player, slots, 10),
                "the hold is not a gate step, and a held deal starts no click cooldown");
    }

    @Test
    void anOpenRoundResumesMovesDoublesAndSettlesDuringTheHold() {
        Round round = games.rounds().open(alex.player, slots, 10, "v1");
        assertNotNull(round, "at 3:50 PM the hand is dealt");
        clockTo(15, 58);
        Round resumed = games.rounds().openRound(alex.id, slots.id());
        assertEquals(round.id(), resumed.id(), "the open hand is found to resume during the hold");
        assertNull(games.rounds().open(alex.player, slots, 10, "v1"), "asking to deal again means resume it");
        assertFalse(alex.heard().contains("restarts"), "and that says nothing about the restart: " + alex.heard());
        Round stepped = games.rounds().step(resumed, "v1;h");
        assertNotNull(stepped, "a move is recorded as normal");
        assertTrue(games.rounds().raise(alex.player, stepped, 10), "a Double in an open hand still goes in");
        assertTrue(games.rounds().close(stepped, 40, null), "and the hand settles");
        assertEquals(100 - 20 + 40, host.balanceOf(alex.id), "paid exactly as it would be at any other time");
    }

    @Test
    void anInstantPlayIsNotHeldBecauseItIsOverAtOnce() {
        clockTo(15, 58);
        assertEquals(3, games.rounds().play(alex.player, slots, 5, PlayGateTest.engine(3)),
                "a spin finishes as it starts, so a restart can't cut it off");
        assertEquals(98, host.balanceOf(alex.id), "5 in, 3 back");
    }

    @Test
    void aNewRoundOpensAgainAfterTheRestart() {
        clockTo(15, 59);
        assertNull(games.rounds().open(alex.player, slots, 10, "v1"), "held at 3:59 PM");
        clockTo(16, 0);
        assertNotNull(games.rounds().open(alex.player, slots, 10, "v1"), "dealt again from 4:00 PM");
        assertEquals(90, host.balanceOf(alex.id), "and only now is the stake taken");
    }

    // ---- c. today's cabinet board ----------------------------------------------------------------

    @Test
    void todaysBoardIsPracticeWhileHeldAndTheScoredTryWaits() throws Exception {
        CabinetGame snake = cabinet();
        clockTo(15, 57);
        CabinetGame.DailyStart held = snake.startDaily(alex.player);
        assertFalse(held.scored(), "a scored try the restart would cut off is dealt as practice");
        assertFalse(games.dao().dailyAttempt(alex.id, "test_snake", held.day()), "and the try is not used up");
        assertEquals(List.of(DAILY_HELD), alex.said, "the player is told once why, with the restart's time");
        assertEquals(snake.dailySeed(held.day()), held.seed(), "it is still today's board, the same as everyone's");

        alex.said.clear();
        clockTo(16, 5);
        CabinetGame.DailyStart after = snake.startDaily(alex.player);
        assertTrue(after.scored(), "after the restart the first deal is the scored try");
        assertTrue(games.dao().dailyAttempt(alex.id, "test_snake", after.day()), "and now it is written");
        assertEquals(List.of(), alex.said, "with nothing said about practice");
    }

    @Test
    void aUsedTryDuringTheHoldIsPlainPracticeWithNothingSaidAboutWaiting() throws Exception {
        CabinetGame snake = cabinet();
        clockTo(10, 0);
        assertTrue(snake.startDaily(alex.player).scored(), "the morning's first deal is the scored try");
        clockTo(15, 57);
        assertFalse(snake.startDaily(alex.player).scored(), "later deals are practice anyway");
        assertEquals(List.of(), alex.said, "no scored try is waiting, so the restart line would not be true");
    }

    @Test
    void somewhereNothingCanBeEarnedTheNotHereLineIsTheOneSaid() throws Exception {
        CabinetGame snake = cabinet();
        alex.mode = GameMode.CREATIVE;
        clockTo(15, 57);
        assertFalse(snake.startDaily(alex.player).scored(), "practice");
        assertEquals(1, alex.said.size(), "one line, not two: " + alex.said);
        assertTrue(alex.said.get(0).startsWith("No tokens can be earned here"), "the reason that lasts: " + alex.said);
    }

    // ---- the live config and the status line ------------------------------------------------------

    @Test
    void theHoldFollowsTheLiveConfigAndReadsInThePlayersZone() {
        clockTo(10, 0);
        assertEquals("Next restart: 4:00 PM (new runs held from 3:55 PM)",
                games.restartHold().status(games.clock().nowMillis()), "the /hcm games status line, Chicago time");
        clockTo(15, 57);
        assertEquals("4:00 PM", games.restartHeld(), "held, with the time players read");

        host.config = GamesKit.config(GamesKit.common(true, 100, 600, 6).withRestarts(List.of(), 5));
        assertNull(games.restartHeld(), "restart_times: [] and a reload: nothing is held");
        assertNull(games.restartRefusal(), "so nothing is refused");
        assertEquals("No restart times set", games.restartHold().status(games.clock().nowMillis()),
                "and the status line says so");

        host.config = GamesKit.config(GamesKit.common(true, 100, 600, 6)
                .withRestarts(List.of(LocalTime.of(16, 0)), 1));
        assertNull(games.restartRefusal(), "a one-minute hold hasn't started at 3:57 PM");
        clockTo(15, 59);
        assertEquals(HELD, games.restartRefusal().message(), "and has at 3:59 PM");
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** A cabinet with today's board and nothing else, built on the framework under test. */
    private CabinetGame cabinet() {
        CabinetSettings settings = new CabinetSettings() {
            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public int dailyReward() {
                return 1;
            }

            @Override
            public int dailyCap() {
                return 5;
            }
        };
        return new CabinetGame(games.context()) {
            @Override
            protected CabinetSettings cabinetSettings() {
                return settings;
            }

            @Override
            public String id() {
                return "test_snake";
            }

            @Override
            public GameKind kind() {
                return GameKind.CABINET;
            }

            @Override
            public String name() {
                return "Test Snake";
            }

            @Override
            public com.dierks.homecraft.arcade.TokenService.Source source() {
                return com.dierks.homecraft.arcade.TokenService.Source.GAMES_SNAKE;
            }

            @Override
            public boolean configEnabled() {
                return true;
            }

            @Override
            public List<String> rules() {
                return List.of("Eat apples.");
            }

            @Override
            public ItemStack tile(Player viewer) {
                return null;
            }

            @Override
            public void open(Player player, Runnable back) {
            }
        };
    }

    private int count(String sql) throws Exception {
        try (PreparedStatement ps = host.connection.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }
}
