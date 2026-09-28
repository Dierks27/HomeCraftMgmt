package com.dierks.homecraft.games;

import com.dierks.homecraft.games.GamesKit.ChanceSettings;
import com.dierks.homecraft.games.GamesKit.Fake;
import com.dierks.homecraft.games.GamesKit.Host;
import com.dierks.homecraft.games.GamesKit.SkillSettings;
import com.dierks.homecraft.games.GamesKit.TestGame;
import org.bukkit.GameMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The skill games' token rewards (spec §6.1, R1.22, R2.12) against a real database.
 *
 * <p>Pinned here: a reward counts toward the game's daily cap and the server-wide cap and pays
 * what is left, saying so once; a one-time reward pays once; a player who can't earn where they
 * are (creative, spectator, a world without games) earns nothing AND keeps the one-time reward for
 * later — the check comes before anything is recorded; nothing is paid for a game of chance or
 * for a personal best; the featured bonus is once a day across games and counts toward the
 * server-wide cap only; a first clear is not capped; the "+N tokens" line reads like the token
 * service's.
 */
class SkillRewardsTest {

    private Host host;
    private TestGame snake;
    private TestGame merge;
    private TestGame slots;
    private GamesService games;
    private Fake alex;

    @BeforeEach
    void setUp() {
        host = new Host(GamesKit.at(2026, 3, 10, 15, 0));
        snake = GamesKit.skill("test_snake", "Test Snake");
        merge = GamesKit.skill("test_merge", "Test Merge");
        slots = GamesKit.chance();
        games = GamesKit.service(host, List.of(
                GamesKit.spec(slots, new ChanceSettings(true, List.of(1), 30), null),
                GamesKit.spec(snake, new SkillSettings(true, 2), null),
                GamesKit.spec(merge, new SkillSettings(true, 2), null)));
        alex = new Fake("Alex");
        host.online.put(alex.id, alex.player);
    }

    @AfterEach
    void tearDown() throws Exception {
        host.connection.close();
    }

    private int pay(TestGame game, RewardKind kind, String ref, int tokens) {
        return games.rewards().pay(alex.player, game, game.source(), kind, ref, tokens, 2,
                game.name() + ": " + kind.name().toLowerCase());
    }

    @Test
    void aRewardPaysWhatIsLeftOfTheGamesCapAndSaysSoOnce() {
        assertEquals(1, pay(snake, RewardKind.MILESTONE, SkillRewards.milestoneRef("classic", 1), 1), "bronze");
        assertEquals(1, pay(snake, RewardKind.MILESTONE, SkillRewards.milestoneRef("classic", 2), 3),
                "silver would pay 3, but the game's cap of 2 has 1 left");
        assertEquals(0, pay(snake, RewardKind.MILESTONE, SkillRewards.milestoneRef("classic", 3), 1),
                "gold pays nothing today");
        assertEquals(2, host.balanceOf(alex.id), "2 tokens in all");
        long capped = alex.said.stream().filter(l -> l.contains("You've won all the game tokens you can today")).count();
        assertEquals(1, capped, "the capped line is said once for the finish, not per reward");
        host.time.now = GamesKit.at(2026, 3, 11, 9, 0);
        assertEquals(1, pay(snake, RewardKind.MILESTONE, SkillRewards.milestoneRef("classic", 3), 1),
                "a one-time reward capped away is not used up: it pays another day");
    }

    @Test
    void theServerWideCapSpansEveryGame() {
        host.config = GamesKit.config(GamesKit.common(true, 100, 600, 3));
        assertEquals(2, pay(snake, RewardKind.DAILY_CHALLENGE, SkillRewards.dailyRef(1), 2), "Snake's daily");
        assertEquals(1, pay(merge, RewardKind.DAILY_CHALLENGE, SkillRewards.dailyRef(1), 2),
                "Ore Merge's own cap has room, but skill_daily_cap 3 has only 1 left");
    }

    @Test
    void aOnceOnlyRewardPaysOnceAndIsQuietAfterwards() {
        assertEquals(1, pay(snake, RewardKind.MILESTONE, "ms:classic:1", 1), "the first time");
        alex.said.clear();
        assertEquals(0, pay(snake, RewardKind.MILESTONE, "ms:classic:1", 1), "never again");
        assertTrue(alex.said.isEmpty(), "and a reward already had is not called 'capped'");
    }

    @Test
    void aPlayerWhoCantEarnHereEarnsNothingAndKeepsTheRewardForLater() {
        alex.mode = GameMode.CREATIVE;
        assertEquals(0, pay(snake, RewardKind.MILESTONE, "ms:classic:1", 1), "creative earns nothing");
        assertTrue(alex.heard().contains("No tokens can be earned here"), "and is told why");
        alex.mode = GameMode.SPECTATOR;
        assertFalse(games.rewards().canEarnHere(alex.player), "neither does spectator");
        alex.mode = GameMode.SURVIVAL;
        alex.world = GamesKit.world("world_nether");
        assertEquals(0, pay(snake, RewardKind.MILESTONE, "ms:classic:1", 1), "nor a world without games");
        alex.world = GamesKit.world("games");
        assertEquals(1, pay(snake, RewardKind.MILESTONE, "ms:classic:1", 1),
                "the Games world pays, and the milestone was still there to earn");
        alex.online = false;
        assertFalse(games.rewards().canEarnHere(alex.player), "an offline player earns nothing");
    }

    @Test
    void nothingIsPaidForAGameOfChanceOrForAPersonalBest() {
        assertEquals(0, pay(slots, RewardKind.DAILY_CHALLENGE, "daily:1", 1), "nothing rewards a game of chance");
        assertEquals(0, pay(snake, RewardKind.PERSONAL_BEST, "", 1), "a best is announced, never paid");
        assertEquals(0, host.balanceOf(alex.id), "no tokens moved");
    }

    @Test
    void theFeaturedBonusIsOnceADayAcrossGamesAndCountsOnlyTowardTheServerCap() {
        pay(snake, RewardKind.MILESTONE, "ms:classic:1", 2);
        assertEquals(1, pay(snake, RewardKind.FEATURED, SkillRewards.featuredRef(1), 1),
                "Snake's own cap is used up, but the featured bonus counts toward the server cap only");
        assertEquals(0, pay(merge, RewardKind.FEATURED, SkillRewards.featuredRef(1), 1),
                "a second game's featured bonus the same day pays nothing");
    }

    @Test
    void aFirstClearIsNotCapped() {
        host.config = GamesKit.config(GamesKit.common(true, 100, 600, 1));
        pay(snake, RewardKind.MILESTONE, "ms:classic:1", 1);
        assertEquals(10, pay(snake, RewardKind.FIRST_CLEAR, SkillRewards.firstClearRef("river_run"), 10),
                "a first clear pays in full past every cap: there are only so many courses");
    }

    @Test
    void thePaidLineReadsLikeTheTokenServices() {
        host.give(alex.id, 4);
        games.rewards().pay(alex.player, snake, snake.source(), RewardKind.MILESTONE, "ms:classic:2", 1, 2,
                "Snake: silver milestone!");
        assertEquals("✦ +1 token (Snake: silver milestone!). You have 5.", alex.said.get(0),
                "the reward, why, and the new balance");
    }

    @Test
    void todaysPickCountsTowardTheServerCapButNotTheGamesOwn() {
        long day = host.clock.dayKey();
        assertEquals(1, pay(snake, RewardKind.FEATURED, SkillRewards.featuredRef(day), 1), "today's pick pays");
        assertEquals(1, pay(snake, RewardKind.MILESTONE, SkillRewards.milestoneRef("classic", 1), 1), "bronze pays");
        assertEquals(1, pay(snake, RewardKind.DAILY_CHALLENGE, SkillRewards.dailyRef(day), 1),
                "daily_cap 2 still holds the milestone and the daily challenge: the pick is the server cap's only");
    }
}
