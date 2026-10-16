package com.dierks.homecraft.games;

import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.games.GamesKit.Fake;
import com.dierks.homecraft.games.GamesKit.Host;
import com.dierks.homecraft.games.GamesKit.SkillSettings;
import com.dierks.homecraft.games.GamesKit.TestGame;
import com.dierks.homecraft.games.cabinet.CabinetGame;
import com.dierks.homecraft.games.cabinet.snake.Snake;
import com.dierks.homecraft.games.cabinet.snake.SnakeSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A cabinet's medals are paid whole (BALANCE-SPEC §5.2 #8, D9), against the real Snake and a real
 * database: a medal is once ever, so one the day's caps can't pay in full pays nothing, records nothing,
 * and is paid in full by the next run that reaches it on another day. Before 0.37 it was paid what was
 * left of the caps and recorded short for good.
 *
 * <p>Pinned here, at the shipped numbers ({@link TokenBalance}): with one token less than a medal left of
 * the {@code skill_daily_cap}, a bronze pays 0 and isn't recorded; the player reads
 * {@link CabinetGame#MEDAL_LIMIT}, not the "won all the game tokens" line; the next day the same run
 * pays it in full and the medal is then had; and medals that fit are paid as before.
 */
class CabinetMedalsPaidWholeTest {

    private Host host;
    private GamesService games;
    private TestGame filler;
    private Fake alex;

    @BeforeEach
    void setUp() {
        host = new Host(GamesKit.at(2026, 10, 5, 15, 0)); // Mon 5 Oct 2026
        GamesConfig.Common c = GamesKit.common(true, 100, 600, GamesConfig.Common.defaults().skillDailyCap());
        // no featured bonus, so the only Snake reward in play is the medal
        GamesConfig.Common noPick = new GamesConfig.Common(c.enabled(), c.worlds(), c.playWorlds(), c.clickCooldownMs(),
                c.chanceDailyTokens(), c.maxPayout(), c.skillDailyCap(), c.featured(), 0, c.restartTimes(),
                c.restartHoldMinutes(), c.breakDailyChoices(), c.breakPauseDays(), c.breakRaiseDelayDays());
        filler = GamesKit.skill("test_filler", "Filler");
        host.config = GamesKit.config(noPick, "snake", Snake.SPEC.defaults(), "test_filler", new SkillSettings(true, 100));
        games = GamesKit.service(host, List.of(Snake.SPEC, GamesKit.spec(filler, new SkillSettings(true, 100), null)));
        alex = new Fake("Alex");
        host.online.put(alex.id, alex.player);
    }

    @AfterEach
    void tearDown() throws Exception {
        host.connection.close();
    }

    /** Use {@code tokens} of today's skill cap on another game. */
    private void earnElsewhere(int tokens) {
        assertEquals(tokens, games.rewards().pay(alex.player, filler, filler.source(), RewardKind.DAILY_CHALLENGE,
                SkillRewards.dailyRef(host.clock.dayKey()), tokens, 100, "Filler: daily challenge"),
                "fixture: " + tokens + " of today's skill cap used elsewhere");
    }

    private static int medal() {
        return Snake.SPEC.defaults().milestoneReward();
    }

    private static int skillCap() {
        return GamesConfig.Common.defaults().skillDailyCap();
    }

    @Test
    void theShippedMedalFitsItsCaps() {
        SnakeSettings s = Snake.SPEC.defaults();
        assertEquals(TokenBalance.CABINET_MILESTONE, s.milestoneReward(), "each medal pays the token balance's");
        assertEquals(TokenBalance.CABINET_DAILY_CAP, s.dailyCap(), "under its cabinet's cap");
        assertTrue(s.milestoneReward() > 0 && s.milestoneReward() <= s.dailyCap() && s.milestoneReward() <= skillCap(),
                "a medal fits both caps whole, so it can always be paid on some day");
    }

    @Test
    void aMedalTheCapCantPayWholeWaitsForAnotherDayAndIsThenPaidInFull() throws Exception {
        Snake snake = (Snake) games.game("snake");
        int used = skillCap() - medal() + 1;
        earnElsewhere(used);
        CabinetGame.Finish f = snake.finishClassic(alex.player, Scores.CLASSIC, 12, false);
        assertEquals(0, f.tokens(), (medal() - 1) + " left of the skill cap and a bronze of " + medal()
                + ": nothing is paid");
        assertEquals(List.of(), f.milestones(), "and the screen shows no medal paid");
        assertEquals(used, host.balanceOf(alex.id), "not a token of it");
        assertFalse(games.dao().rewardPaid(alex.id, "snake", RewardKind.MILESTONE,
                SkillRewards.milestoneRef(Scores.CLASSIC, 1)), "nor is it recorded: it is still there to earn");
        String limit = CabinetGame.MEDAL_LIMIT.replaceAll("&[0-9a-fk-or]", "");
        assertEquals(1, alex.said.stream().filter(l -> l.contains(limit)).count(),
                "the player reads, once: " + limit + " - " + alex.said);
        assertTrue(alex.said.stream().noneMatch(l -> l.contains("You've won all the game tokens")),
                "not the capped line: another day will pay it");

        host.time.now = GamesKit.at(2026, 10, 6, 15, 0);
        alex.said.clear();
        CabinetGame.Finish next = snake.finishClassic(alex.player, Scores.CLASSIC, 12, false);
        assertEquals(medal(), next.tokens(), "the next day's run that reaches it pays it in full");
        assertEquals(List.of(1), next.milestones(), "the bronze");
        assertEquals(used + medal(), host.balanceOf(alex.id), "what was earned before, and the medal");
        assertTrue(games.dao().rewardPaid(alex.id, "snake", RewardKind.MILESTONE,
                SkillRewards.milestoneRef(Scores.CLASSIC, 1)), "and now it is had, once ever");
        assertEquals(0, snake.finishClassic(alex.player, Scores.CLASSIC, 12, false).tokens(), "never twice");
    }

    @Test
    void medalsThatFitArePaidAsBeforeAndTheOneThatDoesntWaits() throws Exception {
        Snake snake = (Snake) games.game("snake");
        assertTrue(Snake.SPEC.defaults().dailyCap() >= 2 * medal(), "fixture: Snake's own cap holds two medals");
        earnElsewhere(skillCap() - 2 * medal());
        CabinetGame.Finish f = snake.finishClassic(alex.player, Scores.CLASSIC, 31, false);
        assertEquals(List.of(1, 2), f.milestones(), "bronze and silver fit what is left; gold doesn't");
        assertEquals(2 * medal(), f.tokens(), "two medals in full, and gold's waits whole");
        assertFalse(games.dao().rewardPaid(alex.id, "snake", RewardKind.MILESTONE,
                SkillRewards.milestoneRef(Scores.CLASSIC, 3)), "gold isn't recorded short");
    }
}
