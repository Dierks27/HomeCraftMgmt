package com.dierks.homecraft.games;

import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.games.GamesKit.Fake;
import com.dierks.homecraft.games.GamesKit.Host;
import com.dierks.homecraft.games.GamesKit.SkillSettings;
import com.dierks.homecraft.games.GamesKit.TestGame;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The everyday cap (SHOP-V2-SPEC §5.12, S6b; D27, Q9): {@code games.everyday_cap} holds every game's daily
 * challenge together, a day, a third cap next to the game's and the server's, paid partially like them. It ships
 * 0 = off, so nothing changes until the owner sets it (15 closes the grinder gap). While it is on, the player
 * held back by it reads "Arcade goals: your first three each day give tokens.", and a {@code daily_reward} over
 * it gives one WARN at load.
 */
class EverydayCapTest {

    private Host host;
    private TestGame snake;
    private TestGame merge;
    private TestGame sweeper;
    private GamesService games;
    private Fake kid;

    @BeforeEach
    void setUp() {
        host = new Host(GamesKit.at(2026, 11, 23, 15, 0));
        snake = GamesKit.skill("test_snake", "Test Snake");
        merge = GamesKit.skill("test_merge", "Test Merge");
        sweeper = GamesKit.skill("test_sweeper", "Test Sweeper");
        games = GamesKit.service(host, List.of(
                GamesKit.spec(snake, new SkillSettings(true, 15), null),
                GamesKit.spec(merge, new SkillSettings(true, 15), null),
                GamesKit.spec(sweeper, new SkillSettings(true, 15), null)));
        kid = new Fake("Kid");
        host.online.put(kid.id, kid.player);
    }

    @AfterEach
    void tearDown() throws Exception {
        host.connection.close();
    }

    private void cap(int everyday) {
        host.config = GamesKit.config(GamesKit.common(true, 100, 600, 60).withEverydayCap(everyday));
    }

    private int daily(TestGame game, int tokens) {
        return games.rewards().pay(kid.player, game, game.source(), RewardKind.DAILY_CHALLENGE,
                SkillRewards.dailyRef(host.clock.dayKey()), tokens, 15, game.name() + ": today's goal");
    }

    @Test
    void itShipsOff() throws Exception {
        assertEquals(0, TokenBalance.EVERYDAY_CAP);
        assertEquals(0, GamesConfig.Common.defaults().everydayCap());
        assertEquals(-1, GamesConfig.Common.defaults().everydayCapOrNone(), "0 reads as no cap at all");
        try (InputStream in = getClass().getResourceAsStream("/config.yml")) {
            assertNotNull(in);
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                YamlConfiguration c = new YamlConfiguration();
                c.load(r);
                assertEquals(0, c.getInt("games.everyday_cap", -1), "config.yml ships games.everyday_cap: 0");
            }
        }
    }

    @Test
    void offPaysEveryDailyChallengeAsBefore() {
        cap(0);
        assertEquals(5, daily(snake, 5));
        assertEquals(5, daily(merge, 5));
        assertEquals(5, daily(sweeper, 5), "no third cap: the game's and the server's caps only");
        assertTrue(kid.said.stream().noneMatch(l -> l.contains("Arcade goals")), "no line while it is off");
    }

    @Test
    void theThirdCapPaysPartiallyLikeTheOthersAndAnotherDayPaysAgain() {
        cap(12);
        assertEquals(5, daily(snake, 5));
        assertEquals(5, daily(merge, 5));
        assertEquals(2, daily(sweeper, 5), "12 a day for every goal together: 2 left of it");
        assertEquals(12, host.balanceOf(kid.id));
        long line = kid.said.stream()
                .filter(l -> l.contains("Arcade goals: your first two each day give tokens.")).count();
        assertEquals(1, line, "the player line, once, instead of the general capped line");
        assertEquals(1, games.rewards().pay(kid.player, snake, snake.source(), RewardKind.MILESTONE,
                SkillRewards.milestoneRef("classic", 1), 1, 15, "bronze"), "a milestone is not an arcade goal");
        host.time.now = GamesKit.at(2026, 11, 24, 9, 0);
        assertEquals(5, daily(snake, 5), "a new local day, a new everyday cap");
    }

    @Test
    void thePlayerLineCountsTheGoals() {
        assertEquals("&7Arcade goals: your first three each day give tokens.", SkillRewards.everydayLine(15));
        assertEquals("&7Arcade goals: your first one each day gives tokens.", SkillRewards.everydayLine(5));
        assertEquals("&7Arcade goals: your first one each day gives tokens.", SkillRewards.everydayLine(2));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> shipped() throws Exception {
        try (InputStream in = EverydayCapTest.class.getResourceAsStream("/config.yml")) {
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                YamlConfiguration c = new YamlConfiguration();
                c.load(reader);
                return GamesConfig.tree(c.getConfigurationSection("games"));
            }
        }
    }

    @Test
    void rewardCeilingsWarnsWhenADailyRewardPassesTheCapWhileItIsOn() throws Exception {
        Map<String, Object> games = shipped();
        games.put("enabled", true);
        assertTrue(RewardCeilings.problems(GamesConfig.parse(games, w -> { }, null)).isEmpty(), "shipped: none");

        games.put("everyday_cap", 4);
        List<String> warns = new ArrayList<>();
        List<String> problems = RewardCeilings.problems(GamesConfig.parse(games, warns::add, null));
        assertEquals(problems, warns, "the load says each one, once (and 4 itself is a fine value)");
        assertTrue(problems.contains("games.snake.daily_reward 5 is more than games.everyday_cap 4, so it can only"
                + " ever pay 4 - raise the cap or lower the reward"), String.join("\n", problems));
        assertTrue(problems.stream().anyMatch(l -> l.startsWith("games.falling_floors.daily_reward 5 ")),
                "Falling Floors' daily round too");
        assertEquals(TokenBalance.SOLO_CABINETS.size() + TokenBalance.DUEL_CABINETS.size() + 1, problems.size(),
                "one WARN per daily_reward, nothing else");

        games.put("everyday_cap", 15);
        assertTrue(RewardCeilings.problems(GamesConfig.parse(games, w -> { }, null)).isEmpty(), "15 holds them all");

        games.put("everyday_cap", 5000);
        List<String> clamp = new ArrayList<>();
        assertEquals(1000, GamesConfig.parse(games, clamp::add, null).common().everydayCap(), "clamped to 1000");
        assertEquals(1, clamp.stream().filter(l -> l.contains("everyday_cap")).count(), "with one WARN: " + clamp);
    }
}
