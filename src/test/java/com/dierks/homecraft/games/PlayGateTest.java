package com.dierks.homecraft.games;

import com.dierks.homecraft.games.GamesKit.ChanceSettings;
import com.dierks.homecraft.games.GamesKit.Fake;
import com.dierks.homecraft.games.GamesKit.Host;
import com.dierks.homecraft.games.GamesKit.SkillSettings;
import com.dierks.homecraft.games.GamesKit.TestGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The play gate (spec §4.1, R1.10, R3.9) against a real database and a fixed local clock.
 *
 * <p>Pinned here: each step refuses with its own kid-friendly line, in order (the earliest failing
 * step is the one told); opening runs steps 0-4 only and a raise 2, 4, 7 and 8 only; the cooldown
 * is silent and starts only when tokens go in, never on a check; a game's daily limit and the
 * day's token limit both reset at LOCAL midnight; the day's limit is the lowest of the player's
 * own, the admin's and the server's, counted from the ledger; and games of chance fail closed
 * when Take a break can't be read, while skill games carry on.
 */
class PlayGateTest {

    private Host host;
    private TestGame slots;
    private TestGame snake;
    private GamesService games;
    private Fake alex;

    @BeforeEach
    void setUp() {
        host = new Host(GamesKit.at(2026, 3, 10, 15, 0));
        slots = GamesKit.chance();
        snake = GamesKit.skill("test_snake", "Test Snake");
        host.config = GamesKit.config(GamesKit.common(true, 100, 600, 6),
                "test_slots", new ChanceSettings(true, List.of(1, 5, 10), 3));
        games = GamesKit.service(host, List.of(
                GamesKit.spec(slots, new ChanceSettings(true, List.of(1, 5, 10), 3), null),
                GamesKit.spec(snake, new SkillSettings(true, 2), null)));
        alex = new Fake("Alex");
        host.online.put(alex.id, alex.player);
        host.give(alex.id, 200);
    }

    @AfterEach
    void tearDown() throws Exception {
        host.connection.close();
    }

    @Test
    void aPlayerWhoPassesEveryStepMayPutTokensIn() {
        assertNull(games.canStake(alex.player, slots, 5), "an allowed player with tokens in an economy world plays");
        assertNull(games.canOpen(alex.player, slots), "and may open the screen");
        assertNull(games.canStake(alex.player, snake, 0), "skill games are free to play");
    }

    @Test
    void theGamesOffOrTheGameOffClosesIt() {
        host.config = GamesKit.config(GamesKit.common(false, 100, 600, 6));
        assertEquals(Refusal.CLOSED, games.canOpen(alex.player, snake), "games.enabled false closes every game");
        host.config = GamesKit.config(GamesKit.common(true, 100, 600, 6));
        snake.open = false;
        assertEquals(Refusal.CLOSED, games.canOpen(alex.player, snake), "the game's own switch closes it");
    }

    @Test
    void aFailedGameSaysItIsTakingABreak() {
        games.fail(snake, new IllegalStateException("boom"));
        assertEquals(Refusal.BROKEN, games.canOpen(alex.player, snake),
                "a game that threw tells players it is taking a break, not that it is closed");
        assertNull(games.canOpen(alex.player, slots), "and no other game is touched");
    }

    @Test
    void thePermissionsGateEveryGameAndGamesOfChanceOnTheirOwn() {
        alex.denied.add("hcm.games.chance");
        assertEquals(Refusal.NO_CHANCE, games.canOpen(alex.player, slots),
                "without hcm.games.chance a game of chance is closed to them, with no more detail");
        assertNull(games.canOpen(alex.player, snake), "skill games are not affected");
        alex.denied.add("hcm.games.play");
        assertEquals(Refusal.NO_GAMES, games.canOpen(alex.player, snake), "without hcm.games.play nothing opens");
    }

    @Test
    void gamesArePlayedInEconomyWorldsTheGamesWorldsAndThePlayWorldsOnly() {
        alex.world = GamesKit.world("games");
        assertNull(games.canOpen(alex.player, snake), "a games.worlds world is allowed");
        alex.world = GamesKit.world("HUB");
        assertNull(games.canOpen(alex.player, snake), "a games.play_worlds world is allowed, any case");
        alex.world = GamesKit.world("world_nether");
        assertEquals(Refusal.WORLD, games.canOpen(alex.player, snake), "any other world is refused");
    }

    @Test
    void aPauseRefusesGamesOfChanceButNotSkillGames() {
        assertTrue(games.breaks().pause(alex.id, 1), "the pause is saved");
        Refusal r = games.canOpen(alex.player, slots);
        assertEquals(Refusal.Reason.PAUSED, r.reason(), "a paused player can't even open a game of chance");
        assertEquals("You're taking a break from games of chance until Thu 12 AM.", r.message(),
                "a 1-day pause started Tuesday afternoon ends at the first local midnight a full day away");
        assertNull(games.canStake(alex.player, snake, 0), "skill games are not part of Take a break");
    }

    @Test
    void theCooldownIsSilentAndStartsOnlyWhenTokensGoIn() {
        assertNull(games.canStake(alex.player, slots, 1), "a check...");
        assertNull(games.canStake(alex.player, slots, 1), "...never starts the cooldown, however often a screen asks");
        games.gate().clicked(alex.player);
        Refusal r = games.canStake(alex.player, slots, 1);
        assertEquals(Refusal.SILENT, r, "a click inside the cooldown is refused");
        games.tell(alex.player, r);
        assertTrue(alex.said.isEmpty(), "and nothing is said about it: spam-clicking just does nothing");
        host.move(599);
        assertEquals(Refusal.SILENT, games.canStake(alex.player, slots, 1), "599 ms is still inside 600 ms");
        host.move(1);
        assertNull(games.canStake(alex.player, slots, 1), "at 600 ms the next play is allowed");
    }

    @Test
    void theGamesDailyLimitCountsTodaysRoundsAndResetsAtLocalMidnight() {
        host.time.now = GamesKit.at(2026, 3, 10, 23, 58);
        for (int i = 0; i < 3; i++) {
            assertNotNull(games.rounds().play(alex.player, slots, 1, engine(0)), "play " + (i + 1) + " of 3");
            host.move(1_000);
        }
        Refusal r = games.canStake(alex.player, slots, 1);
        assertEquals(Refusal.dailyLimit("Test Slots"), r, "the fourth play of the day is refused at 23:58");
        assertEquals("That's all your plays of Test Slots for today. It opens again at midnight.", r.message(),
                "and says when it opens again");
        assertNull(games.canOpen(alex.player, slots), "the screen still opens (steps 0-4 only)");
        host.time.now = GamesKit.at(2026, 3, 11, 0, 1);
        assertNull(games.canStake(alex.player, slots, 1), "at 00:01 LOCAL time it is a new day");
    }

    @Test
    void theDaysTokenLimitIsTheLowestOfOwnAdminAndServerAndCountsTheLedger() {
        assertTrue(games.breaks().setLimit(alex.id, 25), "their own limit");
        spend(20);
        assertEquals(Refusal.personalLimit(25), games.canStake(alex.player, slots, 10),
                "20 put in today + 10 would pass their own 25");
        assertNull(games.canStake(alex.player, slots, 5), "20 + 5 = 25 is exactly the limit and allowed");
        assertTrue(games.breaks().setAdminLimit(alex.id, 21), "a parent sets a lower one");
        assertEquals(Refusal.personalLimit(21), games.canStake(alex.player, slots, 5),
                "the lowest limit wins, whoever set it");
        assertEquals("That's your limit for today (21 tokens). It resets at midnight.",
                games.canStake(alex.player, slots, 5).message(), "the line says the limit and when it resets");
    }

    @Test
    void theServerLimitAppliesWithNoLimitOfTheirOwn() {
        spend(95);
        assertEquals(Refusal.personalLimit(100), games.canStake(alex.player, slots, 10),
                "games.chance_daily_tokens is everyone's limit");
    }

    @Test
    void tokensPutInYesterdayDontCountToday() {
        host.time.now = GamesKit.at(2026, 3, 10, 23, 59);
        spend(95);
        host.time.now = GamesKit.at(2026, 3, 11, 0, 1);
        assertNull(games.canStake(alex.player, slots, 10), "the ledger is counted from LOCAL midnight");
    }

    @Test
    void aBalanceShortOfTheStakeSaysHowManyMore() {
        Fake sam = new Fake("Sam");
        host.give(sam.id, 3);
        assertEquals(Refusal.needMore(2), games.canStake(sam.player, slots, 5), "3 tokens for a 5-token play");
        assertEquals("You need 2 more tokens.", Refusal.needMore(2).message(), "the line says how many");
    }

    @Test
    void theEarliestFailingStepIsTheOneTold() {
        Fake sam = new Fake("Sam");
        assertTrue(games.breaks().pause(sam.id, 7), "paused");
        sam.world = GamesKit.world("world_nether");
        assertEquals(Refusal.WORLD, games.canStake(sam.player, slots, 5),
                "the world (step 3) comes before the pause (step 4)");
        sam.world = GamesKit.world("world");
        assertEquals(Refusal.Reason.PAUSED, games.canStake(sam.player, slots, 5).reason(),
                "the pause (step 4) comes before the balance (step 8)");
    }

    @Test
    void aRaiseReRunsOnlyThePermissionThePauseTheLimitAndTheBalance() {
        games.gate().clicked(alex.player);
        host.config = GamesKit.config(GamesKit.common(true, 100, 600, 6),
                "test_slots", new ChanceSettings(true, List.of(1, 5, 10), 0));
        assertNull(games.gate().extra(alex.player, slots, 10),
                "a Double ignores the cooldown and the daily count: it is part of a play already started");
        spend(95);
        assertEquals(Refusal.personalLimit(100), games.gate().extra(alex.player, slots, 10),
                "but the day's limit counts the extra");
        alex.denied.add("hcm.games.chance");
        assertEquals(Refusal.NO_CHANCE, games.gate().extra(alex.player, slots, 1), "and the permission");
    }

    @Test
    void gamesOfChanceFailClosedWhenTakeABreakCantBeRead() throws Exception {
        host.connection.close();
        assertEquals(Refusal.CHANCE_CLOSED, games.canOpen(alex.player, slots),
                "limits that can't be read close games of chance");
        assertEquals(Refusal.CHANCE_CLOSED, games.canStake(alex.player, slots, 1), "for playing too");
        assertNull(games.canOpen(alex.player, snake), "skill games don't read the limits and still open");
    }

    /** A session in {@code gameId}'s game, ACTIVE. */
    private static com.dierks.homecraft.games.world.Session in(String gameId) {
        return new com.dierks.homecraft.games.world.Session(java.util.UUID.randomUUID(), gameId, gameId, "s1", "games",
                com.dierks.homecraft.games.world.Session.Phase.ACTIVE, 0L);
    }

    /**
     * 0.38.3 (the owner: "go to the clubhouse when click into a game"): a visitor in the Clubhouse passes
     * step 0 for the courses (Time Trials, Fresh Courses, the Cup), mini golf and the cabinets, which are
     * handed out from there or played on a screen right there.
     */
    @Test
    void aClubhouseVisitorMayOpenCoursesGolfAndCabinets() {
        var club = in("clubhouse");
        assertNull(PlayGate.session(club, new TestGame("trials", GameKind.TRIAL, "Time Trials",
                com.dierks.homecraft.arcade.TokenService.Source.GAMES_PARKOUR)), "the courses");
        assertNull(PlayGate.session(club, new TestGame("fresh_courses", GameKind.TRIAL, "Fresh Courses",
                com.dierks.homecraft.arcade.TokenService.Source.GAMES_DAILY)), "Fresh Courses");
        assertNull(PlayGate.session(club, new TestGame("cup", GameKind.TRIAL, "Weekly Cup",
                com.dierks.homecraft.arcade.TokenService.Source.GAMES_DAILY)), "the Weekly Cup");
        assertNull(PlayGate.session(club, new TestGame("golf", GameKind.GOLF, "Mini Golf",
                com.dierks.homecraft.arcade.TokenService.Source.GAMES_DAILY)), "mini golf");
        assertNull(PlayGate.session(club, snake), "a cabinet plays on its screen in the Clubhouse");
        assertNull(PlayGate.session(club, new TestGame("clubhouse", GameKind.TRIAL, "The Clubhouse",
                com.dierks.homecraft.arcade.TokenService.Source.GAMES_RACE_NIGHT)), "the Clubhouse itself, as before");
    }

    @Test
    void luckRaceNightAndFallingFloorsWaitUntilTheyLeave() {
        var club = in("clubhouse");
        assertEquals(Refusal.NOT_IN_CLUBHOUSE, PlayGate.session(club, slots), "a game of luck stays outside");
        assertEquals(Refusal.NOT_IN_CLUBHOUSE, PlayGate.session(club, new TestGame("race_night", GameKind.TRIAL,
                "Race Night", com.dierks.homecraft.arcade.TokenService.Source.GAMES_RACE_NIGHT)),
                "Race Night is joined from home, as before (now with the Clubhouse's own line)");
        assertEquals(Refusal.NOT_IN_CLUBHOUSE, PlayGate.session(club, new TestGame("falling_floors", GameKind.TRIAL,
                "Falling Floors", com.dierks.homecraft.arcade.TokenService.Source.GAMES_RACE_NIGHT)),
                "Falling Floors too");
        assertEquals("That one isn't played in the Clubhouse - tap Leave game first.",
                Refusal.NOT_IN_CLUBHOUSE.message(), "one plain line, the way out in it");
        assertEquals(Refusal.Reason.IN_SESSION, Refusal.NOT_IN_CLUBHOUSE.reason(), "still gate step 0");
    }

    @Test
    void anyOtherSessionIsStillRefused() {
        assertEquals(Refusal.IN_SESSION, PlayGate.session(in("trials"), snake),
                "on a course: a cabinet waits, as before");
        assertEquals(Refusal.IN_SESSION, PlayGate.session(in("golf"), new TestGame("trials", GameKind.TRIAL,
                "Time Trials", com.dierks.homecraft.arcade.TokenService.Source.GAMES_PARKOUR)),
                "in a round of golf: a course waits, as before");
        assertNull(PlayGate.session(in("test_snake"), snake), "the session's own game");
        assertNull(PlayGate.session(null, slots), "no session: go ahead");
        assertNull(games.canOpen(alex.player, snake), "and the live gate with no session, unchanged");
    }

    @Test
    void theIdsArePinnedToTheGames() {
        assertEquals(com.dierks.homecraft.games.clubhouse.Clubhouse.SPEC.id(), PlayGate.CLUBHOUSE, "the Clubhouse");
        assertTrue(PlayGate.FROM_CLUBHOUSE.contains(com.dierks.homecraft.games.trial.TimeTrials.SPEC.id()), "trials");
        assertTrue(PlayGate.FROM_CLUBHOUSE.contains(com.dierks.homecraft.games.gen.DailyCourses.SPEC.id()),
                "Fresh Courses");
        assertTrue(PlayGate.FROM_CLUBHOUSE.contains(com.dierks.homecraft.games.cup.live.WeeklyCup.SPEC.id()),
                "the Cup");
        assertEquals(3, PlayGate.FROM_CLUBHOUSE.size(), "and no other TRIAL-kind game (Race Night, Falling Floors)");
        assertEquals(GameKind.GOLF, com.dierks.homecraft.games.golf.MiniGolf.SPEC.kind(), "golf passes by its kind");
    }

    /** Put {@code n} tokens into a game of chance today (a ledger debit under a chance source). */
    private void spend(int n) {
        try {
            host.tokens.change(alex.id, -n, "LOTTO", "Scratch Ticket", host.time.now);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static ChanceRounds.InstantEngine<Integer> engine(int payout) {
        return new ChanceRounds.InstantEngine<>() {
            @Override
            public Integer decide(long seed, int stake) {
                return payout;
            }

            @Override
            public int payout(Integer outcome) {
                return outcome;
            }

            @Override
            public String data(Integer outcome) {
                return "v1;pays=" + outcome;
            }
        };
    }
}
