package com.dierks.homecraft.games;

import com.dierks.homecraft.arcade.TokenService;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The small pure pieces of the framework contract every game codes against: the refusals and
 * which one is silent, the reward kinds' rules, the one spelling of every reward ref and board,
 * the ledger details (a partial return is never "won"), the featured pick, the tabs, the ledger
 * sources, and "1 in N".
 */
class GamesContractTest {

    @Test
    void onlyTheCooldownIsSilent() {
        assertTrue(Refusal.SILENT.silent());
        for (Refusal r : List.of(Refusal.CLOSED, Refusal.BROKEN, Refusal.CHANCE_CLOSED, Refusal.IN_SESSION,
                Refusal.NO_CHANCE, Refusal.NO_GAMES, Refusal.WORLD, Refusal.paused("Thu 12 AM"),
                Refusal.dailyLimit("Ore Slots"), Refusal.personalLimit(25), Refusal.needMore(3), Refusal.of("x"))) {
            assertFalse(r.silent(), r.toString());
            assertFalse(r.message().isEmpty(), r.toString());
        }
        assertEquals("You're taking a break from games of chance until Thu 12 AM.", Refusal.paused("Thu 12 AM").message());
        // "your plays of <name>" reads naturally for every name, "The Wheel" included (not "all the The Wheel")
        assertEquals("That's all your plays of Ore Slots for today. It opens again at midnight.",
                Refusal.dailyLimit("Ore Slots").message());
        assertEquals("That's your limit for today (25 tokens). It resets at midnight.",
                Refusal.personalLimit(25).message());
        assertEquals("You need 1 more token.", Refusal.needMore(1).message());
        assertEquals(Refusal.Reason.BALANCE, Refusal.needMore(3).reason());
    }

    @Test
    void theRewardKindsCarryTheirRules() {
        for (RewardKind k : RewardKind.values()) {
            assertEquals(k != RewardKind.FIRST_CLEAR, k.capped(), k + ": only a first clear is uncapped");
            assertEquals(k == RewardKind.FEATURED || k == RewardKind.COURSE_OF_WEEK, k.acrossGames(), k.name());
            assertEquals(k == RewardKind.PERSONAL_BEST, k.repeatable(), k.name());
        }
    }

    @Test
    void refsAndBoardsHaveOneSpelling() {
        assertEquals("daily:20000", SkillRewards.dailyRef(20000));
        assertEquals("ms:classic:2", SkillRewards.milestoneRef("classic", 2));
        assertEquals("featured:20000", SkillRewards.featuredRef(20000));
        assertEquals("first_clear:river_run", SkillRewards.firstClearRef("river_run"));
        assertEquals("weekly:river_run:19999", SkillRewards.weeklyRef("river_run", 19999));
        assertEquals("cotw:20000", SkillRewards.courseOfWeekRef(20000));
        assertEquals("par:meadow:20000", SkillRewards.parRef("meadow", 20000));
        assertEquals("hio:meadow:3:20000", SkillRewards.holeInOneRef("meadow", 3, 20000));
        assertEquals("daily:20000", Scores.daily(20000));
        assertEquals("course:river_run", Scores.course("river_run"));
        assertEquals("week:river_run:19999", Scores.week("river_run", 19999));
        assertEquals("golf:meadow", Scores.golf("meadow"));
        assertEquals("invites.coin_flip", Invites.prefKey("coin_flip"));
    }

    @Test
    void aPartialReturnIsNeverCalledAWin() {
        assertEquals("Ore Slots: 5 in", ChanceRounds.stakeDetail("Ore Slots", 5));
        assertEquals("Ore Slots: won 25", ChanceRounds.payoutDetail("Ore Slots", 5, 25));
        assertEquals("Ore Slots: 5 back", ChanceRounds.payoutDetail("Ore Slots", 5, 5));
        assertEquals("The Wheel: 3 back", ChanceRounds.payoutDetail("The Wheel", 5, 3));
    }

    @Test
    void theFeaturedPickIsStableForADayAndReachesEveryCandidate() {
        List<String> candidates = List.of("creeper_sweeper", "ore_merge", "snake", "river_run");
        assertNull(Featured.pick(List.of(), 20000));
        assertEquals(Featured.pick(candidates, 20000), Featured.pick(candidates, 20000), "the same all day");
        Set<String> seen = new HashSet<>();
        for (long day = 20000; day < 20100; day++) {
            String pick = Featured.pick(candidates, day);
            assertTrue(candidates.contains(pick), pick);
            seen.add(pick);
        }
        assertEquals(Set.copyOf(candidates), seen, "every candidate gets its days");
    }

    @Test
    void eachKindHasItsTab() {
        assertEquals(Game.Tab.LUCK, Game.Tab.of(GameKind.CHANCE));
        assertEquals(Game.Tab.CABINETS, Game.Tab.of(GameKind.CABINET));
        assertEquals(Game.Tab.COURSES, Game.Tab.of(GameKind.TRIAL));
        assertEquals(Game.Tab.GOLF, Game.Tab.of(GameKind.GOLF));
        assertTrue(GameKind.CHANCE.chance());
        assertFalse(GameKind.GOLF.chance());
    }

    @Test
    void everyGameHasItsOwnLedgerSource() {
        List<String> names = List.of("ARCADE_SLOTS", "ARCADE_TWENTY_ONE", "ARCADE_WHEEL", "ARCADE_HILO",
                "ARCADE_COIN_FLIP", "GAMES_SWEEPER", "GAMES_MERGE", "GAMES_SNAKE", "GAMES_MATCH", "GAMES_SIMON",
                "GAMES_WHACK", "GAMES_CONNECT", "GAMES_TICTACTOE", "GAMES_PARKOUR", "GAMES_ELYTRA", "GAMES_BOAT",
                "GAMES_GOLF");
        for (String n : names) {
            TokenService.Source s = TokenService.Source.of(n);
            assertTrue(s != null && !s.label().isBlank(), n);
        }
        assertEquals("Twenty-One", TokenService.Source.ARCADE_TWENTY_ONE.label());
        assertEquals(TokenService.Source.REFUND, TokenService.Source.of("REFUND"), "the old sources are untouched");
    }

    @Test
    void oneInNIsRounded() {
        assertEquals(2, FeedWriter.oneIn(0.5));
        assertEquals(1850, FeedWriter.oneIn(1.0 / 1850.4));
        assertEquals(0, FeedWriter.oneIn(0));
    }
}
