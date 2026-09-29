package com.dierks.homecraft.storage;

import com.dierks.homecraft.arcade.TokenService.Source;
import com.dierks.homecraft.games.ChanceRounds;
import com.dierks.homecraft.games.ChanceRounds.Round;
import com.dierks.homecraft.games.RewardKind;
import com.dierks.homecraft.games.ScoreResult;
import com.dierks.homecraft.games.Scores;
import com.dierks.homecraft.games.SkillRewards;
import com.dierks.homecraft.games.gen.api.GenBoards;
import com.dierks.homecraft.games.world.SavedState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The games tables (schema v34) against a real SQLite.
 *
 * <p>Pinned here: a play moves tokens in ONE transaction (a refused stake writes nothing; a round
 * never pays twice; one OPEN round per game; a raise and a Coin Flip re-check everything at the
 * moment of the debit and roll back together); rewards honour their caps (partially), their
 * once-only refs and the once-a-day-across-games kinds; a world session's saved state is a plain
 * insert that refuses a second live row, and every later write is scoped to its session; score
 * boards rank and keep records; "tokens put in today" counts only the chance sources' debits
 * since local midnight; the daily-board attempt and the server secret are made once.
 */
class GamesDaoTest {

    private static final long DAY = 20_000;
    private static final long NOW = 1_790_000_000_000L;

    private Connection conn;
    private GamesDao dao;
    private TokenDao tokens;
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID carol = UUID.randomUUID();

    @BeforeEach
    void open() throws Exception {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        Database db = Database.open(conn, Logger.getAnonymousLogger());
        dao = new GamesDao(db);
        tokens = new TokenDao(db);
    }

    @AfterEach
    void close() throws Exception {
        conn.close();
    }

    private void give(UUID p, int n) throws Exception {
        tokens.change(p, n, "ADMIN", "seed", 1);
    }

    private int balance(UUID p) throws Exception {
        return tokens.get(p).tokens();
    }

    private int count(String sql) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    private String one(String sql) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    // ---- rounds -------------------------------------------------------------------------------

    @Test
    void anInstantPlayIsOneTransactionWithPlainLedgerLines() throws Exception {
        give(alice, 10);
        Round r = dao.settleRound(alice, "ore_slots", Source.ARCADE_SLOTS, DAY, 5, 12, 42L, "reels", "&aOre Slots: 5 in",
                "&6Ore Slots: won 12", NOW);
        assertNotNull(r);
        assertEquals(ChanceRounds.SETTLED, r.state());
        assertEquals(42L, r.seed());
        assertEquals(17, balance(alice), "10 - 5 + 12");
        assertEquals(3, count("SELECT COUNT(*) FROM token_ledger"), "the seed, the stake and the payout");
        assertEquals("Ore Slots: 5 in", one("SELECT detail FROM token_ledger WHERE delta = -5"),
                "details are stored without colour codes");
        assertEquals("ARCADE_SLOTS", one("SELECT source FROM token_ledger WHERE delta = 12"));
        assertEquals(1, dao.playsToday(alice, "ore_slots", DAY));
    }

    @Test
    void aRefusedStakeWritesNothing() throws Exception {
        give(alice, 3);
        assertNull(dao.settleRound(alice, "ore_slots", Source.ARCADE_SLOTS, DAY, 5, 50, 1L, "d", "in", "won", NOW));
        assertNull(dao.openRound(alice, "twenty_one", Source.ARCADE_TWENTY_ONE, DAY, 5, 1L, "d", "in", NOW));
        assertEquals(0, count("SELECT COUNT(*) FROM game_rounds"), "no round for a stake that was refused");
        assertEquals(1, count("SELECT COUNT(*) FROM token_ledger"), "only the seed line");
        assertEquals(3, balance(alice));
    }

    @Test
    void anOpenRoundSettlesOnceAndNeverPaysTwice() throws Exception {
        give(alice, 20);
        Round r = dao.openRound(alice, "twenty_one", Source.ARCADE_TWENTY_ONE, DAY, 10, 7L, "v1", "Twenty-One: 10 in", NOW);
        assertNotNull(r);
        assertTrue(r.open());
        assertEquals(10, balance(alice));
        assertEquals(r, dao.openRoundFor(alice, "twenty_one"), "the round to resume");

        assertTrue(dao.updateRound(r.id(), "v1 hit", NOW + 1));
        assertTrue(dao.closeRound(r.id(), 19, "v1 hit stand", Source.ARCADE_TWENTY_ONE, "Twenty-One: won 19", NOW + 2));
        assertEquals(29, balance(alice));
        assertFalse(dao.closeRound(r.id(), 19, null, Source.ARCADE_TWENTY_ONE, "again", NOW + 3), "already settled");
        assertEquals(29, balance(alice), "a second close pays nothing");
        assertFalse(dao.updateRound(r.id(), "late", NOW + 4), "a settled round's data can't change");
        assertEquals("v1 hit stand", dao.round(r.id()).data());
        assertNull(dao.openRoundFor(alice, "twenty_one"));
        assertEquals(List.of(), dao.openRounds(alice));
    }

    @Test
    void thereIsOneOpenRoundPerGame() throws Exception {
        give(alice, 50);
        assertNotNull(dao.openRound(alice, "twenty_one", Source.ARCADE_TWENTY_ONE, DAY, 10, 1L, "a", "in", NOW));
        assertNull(dao.openRound(alice, "twenty_one", Source.ARCADE_TWENTY_ONE, DAY, 10, 2L, "b", "in", NOW),
                "resume the open one instead");
        assertEquals(40, balance(alice), "the refused second round took nothing");
        assertNotNull(dao.openRound(alice, "higher_lower", Source.ARCADE_HILO, DAY, 10, 3L, "c", "in", NOW));
        assertEquals(2, dao.openRounds().size());
    }

    @Test
    void aRaiseReChecksEverythingAtTheMomentOfTheDebit() throws Exception {
        give(alice, 30);
        Round r = dao.openRound(alice, "twenty_one", Source.ARCADE_TWENTY_ONE, DAY, 10, 1L, "v1", "in", NOW);
        assertTrue(dao.raiseStake(r.id(), alice, Source.ARCADE_TWENTY_ONE, 10, () -> true, "v1 double", "in", NOW));
        assertEquals(10, balance(alice));
        assertEquals(20, dao.round(r.id()).stake());
        assertEquals("v1 double", dao.round(r.id()).data());

        assertFalse(dao.raiseStake(r.id(), alice, Source.ARCADE_TWENTY_ONE, 5, () -> false, null, "in", NOW),
                "the gate said no (a pause, the day's limit)");
        assertFalse(dao.raiseStake(r.id(), alice, Source.ARCADE_TWENTY_ONE, 50, null, null, "in", NOW), "can't afford it");
        assertFalse(dao.raiseStake(r.id(), bob, Source.ARCADE_TWENTY_ONE, 5, null, null, "in", NOW), "not bob's round");
        assertEquals(10, balance(alice), "no refused raise took anything");
        assertEquals(20, dao.round(r.id()).stake());

        assertTrue(dao.closeRound(r.id(), 0, null, Source.ARCADE_TWENTY_ONE, "lost", NOW));
        assertFalse(dao.raiseStake(r.id(), alice, Source.ARCADE_TWENTY_ONE, 5, null, null, "in", NOW), "settled");
        assertEquals(10, balance(alice));
        assertThrows(IllegalArgumentException.class,
                () -> dao.raiseStake(r.id(), alice, Source.ARCADE_TWENTY_ONE, 0, null, null, "in", NOW));
    }

    @Test
    void theGateCheckOfARaiseCanReadTodaysTokensInsideItsTransaction() throws Exception {
        give(alice, 100);
        long dayStart = NOW - 1000;
        Round r = dao.openRound(alice, "twenty_one", Source.ARCADE_TWENTY_ONE, DAY, 20, 1L, "v1", "in", NOW);
        int limit = 30;
        assertFalse(dao.raiseStake(r.id(), alice, Source.ARCADE_TWENTY_ONE, 20, () -> {
            try {
                return dao.chanceTokensToday(alice, dayStart) + 20 <= limit;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, null, "in", NOW), "20 in already + 20 more passes the day's 30");
        assertEquals(80, balance(alice));
    }

    @Test
    void untouchedRoundsAreFoundForTheSweep() throws Exception {
        give(alice, 40);
        Round old = dao.openRound(alice, "twenty_one", Source.ARCADE_TWENTY_ONE, DAY, 10, 1L, "a", "in", NOW);
        Round busy = dao.openRound(alice, "higher_lower", Source.ARCADE_HILO, DAY, 10, 2L, "b", "in", NOW);
        assertTrue(dao.updateRound(busy.id(), "b guess", NOW + 600_000));
        List<Round> stale = dao.staleOpenRounds(NOW + 300_000);
        assertEquals(List.of(old.id()), stale.stream().map(Round::id).toList(), "only the round nobody touched");
    }

    @Test
    void aCoinFlipWritesTwoRowsAndPaysTheWinner() throws Exception {
        give(alice, 10);
        give(bob, 10);
        List<Round> rows = dao.pairRound(alice, bob, "coin_flip", Source.ARCADE_COIN_FLIP, DAY, 5, 99L, alice, 9,
                "pair=1", "Coin Flip: 5 in", "Coin Flip: won 9", NOW);
        assertNotNull(rows);
        assertEquals(2, rows.size());
        assertEquals(14, balance(alice), "10 - 5 + 9");
        assertEquals(5, balance(bob));
        assertEquals(bob.toString(), one("SELECT opponent FROM game_rounds WHERE player = '" + alice + "'"));
        assertEquals("9", one("SELECT payout FROM game_rounds WHERE player = '" + alice + "'"), "the winner's row");
        assertEquals("0", one("SELECT payout FROM game_rounds WHERE player = '" + bob + "'"));
        assertEquals(1, dao.playsToday(alice, "coin_flip", DAY), "each player's own row counts toward their limit");
        assertEquals(1, dao.playsToday(bob, "coin_flip", DAY));
        assertEquals(1, dao.pairPlaysToday(alice, bob, "coin_flip", DAY), "one flip between them");
        assertEquals(1, dao.pairPlaysToday(bob, alice, "coin_flip", DAY), "whoever asked whom");
    }

    @Test
    void aCoinFlipRollsBackWhenTheSecondStakeIsRefused() throws Exception {
        give(alice, 10);
        give(bob, 2);
        assertNull(dao.pairRound(alice, bob, "coin_flip", Source.ARCADE_COIN_FLIP, DAY, 5, 1L, alice, 9, "p", "in",
                "won", NOW));
        assertEquals(10, balance(alice), "alice's tokens were put back with the rollback");
        assertEquals(2, balance(bob));
        assertEquals(0, count("SELECT COUNT(*) FROM game_rounds"));
        assertEquals(2, count("SELECT COUNT(*) FROM token_ledger"), "only the two seed lines");
        assertTrue(conn.getAutoCommit(), "the connection is back to autocommit");
    }

    @Test
    void tokensPutInTodayCountOnlyTheChanceSourcesDebitsSinceMidnight() throws Exception {
        long dayStart = NOW;
        tokens.change(alice, 100, "ADMIN", "seed", dayStart - 5000);
        tokens.change(alice, -4, "CRATE", "yesterday", dayStart - 1);
        tokens.change(alice, -5, "LOTTO", "Scratch Ticket", dayStart + 1);
        tokens.change(alice, -3, "ARCADE_SLOTS", "Ore Slots: 3 in", dayStart + 2);
        tokens.change(alice, 10, "ARCADE_SLOTS", "Ore Slots: won 10", dayStart + 3);
        tokens.change(alice, -2, "PACK", "Card Pack", dayStart + 4);
        tokens.change(alice, -7, "PRIZE", "Night Vision", dayStart + 5);
        tokens.change(alice, -6, "CRATE", "Arcade Crate", dayStart + 6);
        tokens.change(bob, 50, "ADMIN", "seed", dayStart);
        tokens.change(bob, -9, "ARCADE_WHEEL", "The Wheel: 9 in", dayStart + 1);
        assertEquals(16, dao.chanceTokensToday(alice, dayStart), "5 + 3 + 2 + 6: not yesterday, prizes or payouts");
        assertEquals(9, dao.chanceTokensToday(bob, dayStart));
        assertEquals(0, dao.chanceTokensToday(carol, dayStart));
    }

    @Test
    void theDailyBoardIsDealtForScoreOnceADay() throws Exception {
        assertFalse(dao.dailyAttempt(alice, "snake", DAY));
        assertTrue(dao.markDailyAttempt(alice, "snake", DAY, 5L, "board", NOW));
        assertFalse(dao.markDailyAttempt(alice, "snake", DAY, 5L, "board", NOW), "later plays are practice");
        assertTrue(dao.dailyAttempt(alice, "snake", DAY));
        assertTrue(dao.markDailyAttempt(alice, "snake", DAY + 1, 6L, "board", NOW), "a new day, a new attempt");
        assertTrue(dao.markDailyAttempt(bob, "snake", DAY, 5L, "board", NOW));
        assertEquals(0, dao.playsToday(alice, "snake", DAY), "an attempt is not a play of chance");
    }

    // ---- rewards ------------------------------------------------------------------------------

    @Test
    void repeatableRewardsWithTheSameNowBothPay() throws Exception {
        assertEquals(1, dao.payReward(alice, "snake", Source.GAMES_SNAKE, DAY, RewardKind.PERSONAL_BEST, "", 1, -1, -1,
                false, "Snake: new best!", NOW));
        assertEquals(1, dao.payReward(alice, "snake", Source.GAMES_SNAKE, DAY, RewardKind.PERSONAL_BEST, "", 1, -1, -1,
                false, "Snake: new best!", NOW));
        assertEquals(2, balance(alice));
        assertEquals(2, count("SELECT COUNT(*) FROM game_rewards"));
    }

    @Test
    void aOneTimeRewardPaysOnce() throws Exception {
        String ref = "daily:" + DAY;
        assertEquals(2, dao.payReward(alice, "snake", Source.GAMES_SNAKE, DAY, RewardKind.DAILY_CHALLENGE, ref, 2, -1,
                -1, true, "&aSnake: daily challenge", NOW));
        assertEquals(0, dao.payReward(alice, "snake", Source.GAMES_SNAKE, DAY, RewardKind.DAILY_CHALLENGE, ref, 2, -1,
                -1, true, "Snake: daily challenge", NOW));
        assertEquals(2, balance(alice));
        assertTrue(dao.rewardPaid(alice, "snake", RewardKind.DAILY_CHALLENGE, ref));
        assertFalse(dao.rewardPaid(bob, "snake", RewardKind.DAILY_CHALLENGE, ref));
        assertEquals("Snake: daily challenge", one("SELECT detail FROM token_ledger"));
        assertThrows(IllegalArgumentException.class, () -> dao.payReward(alice, "snake", Source.GAMES_SNAKE, DAY,
                RewardKind.MILESTONE, " ", 1, -1, -1, true, "x", NOW), "a one-time reward needs a ref");
    }

    @Test
    void theFeaturedBonusIsOnceADayAcrossEveryGame() throws Exception {
        String ref = "featured:" + DAY;
        assertEquals(1, dao.payReward(alice, "snake", Source.GAMES_SNAKE, DAY, RewardKind.FEATURED, ref, 1, 2, 6, true,
                "Snake: today's pick", NOW));
        assertEquals(0, dao.payReward(alice, "ore_merge", Source.GAMES_MERGE, DAY, RewardKind.FEATURED, ref, 1, 2, 6,
                true, "Ore Merge: today's pick", NOW));
        assertEquals("*", one("SELECT game FROM game_rewards"), "stored across games");
        assertEquals(0, dao.rewardsToday(alice, "snake", DAY),
                "it counts toward the server-wide cap only, never the game's own (spec §6.1)");
        assertEquals(1, dao.rewardsToday(alice, DAY), "the server-wide total has it");
        assertTrue(dao.rewardPaid(alice, "ore_merge", RewardKind.FEATURED, ref));
    }

    @Test
    void capsPayWhatIsLeftAndFirstClearsAreExempt() throws Exception {
        int capGame = 2;
        int capAll = 3;
        assertEquals(1, dao.payReward(alice, "snake", Source.GAMES_SNAKE, DAY, RewardKind.DAILY_CHALLENGE,
                "daily:" + DAY, 1, capGame, capAll, true, "a", NOW));
        assertEquals(1, dao.payReward(alice, "snake", Source.GAMES_SNAKE, DAY, RewardKind.MILESTONE, "ms:classic:1", 3,
                capGame, capAll, true, "b", NOW), "the game's cap leaves 1 of 3");
        assertEquals(1, dao.payReward(alice, "ore_merge", Source.GAMES_MERGE, DAY, RewardKind.MILESTONE, "ms:classic:1",
                5, capGame, capAll, true, "c", NOW), "the server-wide cap leaves 1 of 5");
        assertEquals(0, dao.payReward(alice, "ore_merge", Source.GAMES_MERGE, DAY, RewardKind.MILESTONE, "ms:classic:2",
                1, capGame, capAll, true, "d", NOW), "nothing left today");
        assertFalse(dao.rewardPaid(alice, "ore_merge", RewardKind.MILESTONE, "ms:classic:2"),
                "a capped one-time reward that paid 0 is not recorded, so it can still be earned");
        assertEquals(5, dao.payReward(alice, "trials", Source.GAMES_PARKOUR, DAY, RewardKind.FIRST_CLEAR,
                "first_clear:river_run", 5, 4, capAll, true, "e", NOW), "a first clear is not capped");
        assertEquals(8, balance(alice));
        assertEquals(3, dao.rewardsToday(alice, DAY), "first clears don't count toward the caps");
        assertEquals(2, dao.rewardsToday(alice, "snake", DAY));
        assertEquals(1, dao.rewardsToday(alice, "ore_merge", DAY));
        assertEquals(0, dao.rewardsToday(alice, DAY + 1), "a new day");
    }

    @Test
    void aDailyClearIsCappedPerGameAndPaidOncePerCourseDay() throws Exception {
        String ref = SkillRewards.dailyClearRef("fresh_golf", DAY);
        assertEquals(2, dao.payReward(alice, "golf", Source.GAMES_GOLF, DAY, RewardKind.DAILY_CLEAR, ref, 2, 4, 6, true,
                "Daily Golf: first finish today", NOW), "the first counted finish of the course day pays");
        assertEquals(0, dao.payReward(alice, "golf", Source.GAMES_GOLF, DAY, RewardKind.DAILY_CLEAR, ref, 2, 4, 6, true,
                "again", NOW), "a reroll the same day has the same ref: no second one");
        assertEquals(2, dao.rewardsToday(alice, "golf", DAY), "it counts toward golf's own cap");
        assertEquals(2, dao.rewardsToday(alice, DAY), "and the server's");
        assertEquals(1, dao.payReward(alice, "trials", Source.GAMES_PARKOUR, DAY, RewardKind.DAILY_CLEAR,
                SkillRewards.dailyClearRef("fresh_parkour_hard", DAY), 3, 4, 3, true, "c", NOW),
                "the server-wide cap leaves 1 of 3");
        assertTrue(dao.rewardPaid(alice, "golf", RewardKind.DAILY_CLEAR, ref), "kept per game, not across games");
        assertFalse(dao.rewardPaid(alice, "trials", RewardKind.DAILY_CLEAR, ref), "another game's row is its own");
    }

    @Test
    void aWholeRewardPaysAllOrNothingAndStaysThereForAnotherDay() throws Exception {
        String ref = SkillRewards.freshClearRef("fresh_parkour_hard", "7:38");
        // 5 left of the game's cap, 4 asked: paid in full
        assertEquals(4, dao.payReward(alice, "trials", Source.GAMES_PARKOUR, DAY, RewardKind.DAILY_CLEAR, ref, 4, 5, 6,
                true, true, "Hard Parkour: first finish this week", NOW), "5 left and 4 asked: all of it is paid");
        assertTrue(dao.rewardPaid(alice, "trials", RewardKind.DAILY_CLEAR, ref), "and recorded once");

        // 3 left, 4 asked: nothing paid, nothing recorded
        assertEquals(1, dao.payReward(bob, "trials", Source.GAMES_PARKOUR, DAY, RewardKind.MILESTONE, "ms:x:1", 1, 4, 6,
                true, "a", NOW), "bob has used 1 of the game's 4");
        assertEquals(0, dao.payReward(bob, "trials", Source.GAMES_PARKOUR, DAY, RewardKind.DAILY_CLEAR, ref, 4, 4, 6,
                true, true, "Hard Parkour: first finish this week", NOW), "3 left and 4 asked: nothing is paid");
        assertFalse(dao.rewardPaid(bob, "trials", RewardKind.DAILY_CLEAR, ref), "and nothing is recorded");
        assertEquals(1, balance(bob), "not a token of it: never part of it");
        assertEquals(1, dao.rewardsToday(bob, "trials", DAY), "the caps are as they were");

        // the next day of the same set: payable
        assertEquals(4, dao.payReward(bob, "trials", Source.GAMES_PARKOUR, DAY + 1, RewardKind.DAILY_CLEAR, ref, 4, 4,
                6, true, true, "Hard Parkour: first finish this week", NOW), "a new day of the set: paid in full");
        assertEquals(0, dao.payReward(bob, "trials", Source.GAMES_PARKOUR, DAY + 2, RewardKind.DAILY_CLEAR, ref, 4, 4,
                6, true, true, "again", NOW), "and once only");

        // the server-wide cap counts too, and a partial reward is unchanged
        assertEquals(0, dao.payReward(alice, "golf", Source.GAMES_GOLF, DAY, RewardKind.DAILY_CLEAR,
                SkillRewards.freshClearRef("fresh_golf", "7:38"), 3, 4, 6, true, true, "g", NOW),
                "alice has 4 of the server's 6 today: 2 left and 3 asked pays nothing");
        assertEquals(2, dao.payReward(alice, "golf", Source.GAMES_GOLF, DAY, RewardKind.MILESTONE, "ms:y:1", 3, 4, 6,
                true, false, "h", NOW), "a reward that isn't whole still pays what is left");
    }

    // ---- Daily Courses' stars -------------------------------------------------------------------

    @Test
    void addingStarsKeepsTheDaysBestAndAddsOnlyTheRiseToTheWeek() throws Exception {
        String day = GenBoards.stars("fresh_parkour_easy", DAY);
        String week = GenBoards.week(DAY - 1);
        GamesDao.StarsAdded first = dao.addStars(alice, day, week, 1, NOW);
        assertEquals(new GamesDao.StarsAdded(1, 1, 1), first, "the first finish: one star, one for the week");
        assertEquals(0, first.weekBefore(), "the week had nothing before it");
        assertEquals(new GamesDao.StarsAdded(1, 0, 1), dao.addStars(alice, day, week, 1, NOW + 1),
                "the same stars again add nothing");
        assertEquals(new GamesDao.StarsAdded(3, 2, 3), dao.addStars(alice, day, week, 3, NOW + 2),
                "three stars add the two it rose by");
        assertEquals(new GamesDao.StarsAdded(3, 0, 3), dao.addStars(alice, day, week, 2, NOW + 3),
                "a worse run changes nothing");
        assertEquals(new GamesDao.StarsAdded(2, 2, 5), dao.addStars(alice, GenBoards.stars("fresh_tiny_golf", DAY), week, 2,
                NOW + 4), "another course adds to the same week");
        assertEquals(new GamesDao.StarsAdded(1, 1, 1), dao.addStars(bob, day, week, 1, NOW), "every player has their own");
        assertEquals(3L, dao.best(alice, GenBoards.GAME, day), "the day board keeps the best, under the daily game");
        assertEquals(5L, dao.best(alice, GenBoards.GAME, week), "the Star Chart keeps the sum of bests");
        assertEquals(new GamesDao.StarsAdded(3, 0, 5), dao.addStars(alice, day, week, 0, NOW + 5),
                "no stars records nothing");
        assertEquals(new GamesDao.StarsAdded(3, 0, 5), dao.addStars(alice, day, week, 4, NOW + 5),
                "nor do stars that can't be");
        assertEquals(new GamesDao.StarsAdded(1, 1, 1), dao.addStars(alice, GenBoards.stars("fresh_tiny_golf", DAY + 7),
                GenBoards.week(DAY + 6), 1, NOW), "a new week starts from nothing");
    }

    @Test
    void addingStarsIsOneTransaction() throws Exception {
        try (java.sql.Statement st = conn.createStatement()) {
            st.execute("CREATE TRIGGER no_week BEFORE INSERT ON game_scores WHEN substr(NEW.board, 1, 6) = 'gweek:' "
                    + "BEGIN SELECT RAISE(ABORT, 'the week write failed'); END");
        }
        String day = GenBoards.stars("fresh_rings", DAY);
        assertThrows(java.sql.SQLException.class, () -> dao.addStars(alice, day, GenBoards.week(DAY), 2, NOW),
                "the week's write fails");
        assertNull(dao.best(alice, GenBoards.GAME, day), "so the day's best was rolled back with it");
        assertEquals(0, count("SELECT COUNT(*) FROM game_scores"), "nothing was written at all");
    }

    @Test
    void pruningRemovesOnlyOldFreshCoursesStarBoards() throws Exception {
        long old = 100;
        long kept = 200;
        String oldEdition = "1:" + (old - 20458);
        String keptEdition = "1:" + (kept - 20458);
        for (String board : List.of(GenBoards.stars("fresh_golf", old), GenBoards.stars("fresh_golf", oldEdition),
                GenBoards.week(old - 2))) {
            dao.submit(alice, "golf", board, 30, true, NOW);
        }
        for (String board : List.of(GenBoards.stars("fresh_golf", kept), GenBoards.stars("fresh_golf", keptEdition),
                GenBoards.week(kept - 4), GenBoards.day("fresh_golf", oldEdition), Scores.course("river_run"),
                Scores.week("river_run", old), Scores.daily(old), "gday:broken", "gweek:x")) {
            dao.submit(alice, "golf", board, 30, true, NOW);
            dao.submit(bob, "golf", board, 31, true, NOW);
        }
        dao.submit(bob, "golf", GenBoards.stars("fresh_golf", oldEdition), 29, true, NOW);
        assertEquals(4, dao.pruneBoards(150, 150), "three old star boards, one of them with two players' rows");
        assertEquals(List.of(GenBoards.stars("fresh_golf", kept), GenBoards.stars("fresh_golf", keptEdition),
                GenBoards.week(kept - 4), GenBoards.day("fresh_golf", oldEdition), Scores.course("river_run"),
                Scores.daily(old), "gday:broken", "gweek:x", Scores.week("river_run", old)).stream().sorted().toList(),
                dao.boards("golf"), "recent boards, other games' boards, names that aren't ours and the edition "
                        + "leaderboards (the engine prunes those, keeping each course's last editions) are never "
                        + "touched here");
        assertEquals(0, dao.pruneBoards(150, 150), "a second prune finds nothing");
    }

    // ---- scores -------------------------------------------------------------------------------

    @Test
    void scoresKeepBestsRanksAndTheRecord() throws Exception {
        String b = "classic";
        ScoreResult a1 = dao.submit(alice, "creeper_sweeper", b, 5000, true, NOW);
        assertEquals(new ScoreResult(true, null, true, 1), a1, "a first score is a best and, alone, the record");
        ScoreResult b1 = dao.submit(bob, "creeper_sweeper", b, 4000, true, NOW + 1);
        assertEquals(new ScoreResult(true, null, true, 1), b1, "faster takes the record");
        ScoreResult a2 = dao.submit(alice, "creeper_sweeper", b, 4500, true, NOW + 2);
        assertEquals(new ScoreResult(true, 5000L, false, 2), a2, "a best, but not the record");
        ScoreResult a3 = dao.submit(alice, "creeper_sweeper", b, 6000, true, NOW + 3);
        assertEquals(new ScoreResult(false, 4500L, false, 2), a3, "a slower run keeps the best");
        ScoreResult c1 = dao.submit(carol, "creeper_sweeper", b, 4000, true, NOW + 4);
        assertEquals(new ScoreResult(true, null, false, 2), c1, "a tie leaves the record with whoever got there first");

        List<GamesDao.ScoreRow> top = dao.top("creeper_sweeper", b, true, 10);
        assertEquals(List.of(bob, carol, alice), top.stream().map(GamesDao.ScoreRow::player).toList());
        assertEquals(new GamesDao.ScoreRow(bob, 4000, NOW + 1), dao.record("creeper_sweeper", b, true));
        assertEquals(4500L, dao.best(alice, "creeper_sweeper", b));
        assertEquals("3", one("SELECT runs FROM game_scores WHERE player = '" + alice + "'"), "every run counts");

        dao.submit(alice, "snake", b, 12, false, NOW);
        dao.submit(bob, "snake", b, 20, false, NOW);
        assertEquals(bob, dao.record("snake", b, false).player(), "higher is better on this board");
        assertEquals(List.of(b), dao.boards("snake"));

        assertEquals(1, dao.resetScores("creeper_sweeper", b, alice));
        assertNull(dao.best(alice, "creeper_sweeper", b));
        assertEquals(2, dao.resetScores("creeper_sweeper", null, null));
        assertNull(dao.record("creeper_sweeper", b, true));
    }

    // ---- saved state --------------------------------------------------------------------------

    private static SavedState state(UUID player, String session) {
        return new SavedState(player, "trials", "river_run", SavedState.ACTIVE, session, "games",
                new byte[]{1, 2, 3}, null, 30, 0.5f, 1234, 17.5, 18, 4.5f, 1.25f, 0, 300, "SURVIVAL", true, false,
                0.2f, 0.1f, 2.0, "minecraft:speed|1|600|false|true|true", "world", 10.5, 64, -3.25, 90f, -12.5f,
                NOW, null);
    }

    @Test
    void aSavedStateRoundTrips() throws Exception {
        assertTrue(dao.saveState(state(alice, "s1")));
        SavedState s = dao.loadState(alice);
        assertNotNull(s);
        assertEquals("s1", s.sessionId());
        assertEquals("trials", s.gameId());
        assertEquals("river_run", s.ref());
        assertEquals("games", s.sessionWorld());
        assertArrayEquals(new byte[]{1, 2, 3}, s.items());
        assertNull(s.carry());
        assertEquals(30, s.xpLevel());
        assertEquals(0.5f, s.xpProgress());
        assertEquals(17.5, s.health());
        assertEquals("SURVIVAL", s.gameMode());
        assertTrue(s.allowFlight());
        assertFalse(s.flying());
        assertEquals(0.2f, s.walkSpeed());
        assertEquals(2.0, s.absorption());
        assertEquals("minecraft:speed|1|600|false|true|true", s.effects());
        assertEquals(-3.25, s.z());
        assertEquals(-12.5f, s.pitch());
        assertNull(s.doneAt());
    }

    @Test
    void aSecondLiveSessionIsRefusedByThePlainInsert() throws Exception {
        assertTrue(dao.saveState(state(alice, "s1")));
        assertFalse(dao.saveState(state(alice, "s2")), "alice already has a live row");
        assertFalse(dao.saveState(state(bob, "s1")), "the session id is taken");
        assertEquals("s1", dao.loadState(alice).sessionId(), "the first row is untouched");

        assertTrue(dao.finishState(alice, "s1", NOW + 10));
        assertNull(dao.loadState(alice), "a finished session is not live");
        assertTrue(dao.saveState(state(alice, "s3")), "a DONE row doesn't block the next session");
        assertEquals(List.of(alice), dao.livePlayers());
    }

    @Test
    void everyLaterWriteIsScopedToItsSession() throws Exception {
        assertTrue(dao.saveState(state(alice, "s1")));
        assertFalse(dao.setPhase(alice, "old", SavedState.RETURN), "a late call from another session");
        assertFalse(dao.setCarry(alice, "old", new byte[]{9}));
        assertFalse(dao.finishState(alice, "old", NOW));
        assertFalse(dao.deleteState(alice, "old"));
        assertEquals(SavedState.ACTIVE, dao.loadState(alice).phase());

        assertTrue(dao.setPhase(alice, "s1", SavedState.RETURN));
        assertTrue(dao.setCarry(alice, "s1", new byte[]{9}));
        assertEquals(SavedState.RETURN, dao.loadState(alice).phase());
        assertArrayEquals(new byte[]{9}, dao.loadState(alice).carry());
        assertEquals(1, dao.statesInPhase(SavedState.RETURN).size());
        assertThrows(IllegalArgumentException.class, () -> dao.setPhase(alice, "s1", SavedState.DONE));

        assertTrue(dao.finishState(alice, "s1", NOW + 5));
        assertFalse(dao.setPhase(alice, "s1", SavedState.ACTIVE), "a finished session is never revived");
        assertEquals(1, dao.statesInPhase(SavedState.DONE).size());
        assertEquals(0, dao.pruneDone(NOW + 5), "kept until it is old enough");
        assertEquals(1, dao.pruneDone(NOW + 6));
        assertEquals(0, count("SELECT COUNT(*) FROM game_saved_state"));

        assertTrue(dao.saveState(state(bob, "s9")));
        assertTrue(dao.deleteState(bob, "s9"), "an admin's discard deletes for good");
        assertNull(dao.loadState(bob));
    }

    @Test
    void finishingClearsTheCarryInTheSameWrite() throws Exception {
        assertTrue(dao.saveState(state(alice, "s1")), "seeded");
        assertTrue(dao.setCarry(alice, "s1", new byte[]{9}), "something is kept for later");
        assertTrue(dao.finishState(alice, "s1", NOW + 5), "home");
        SavedState done = dao.lastDoneState(alice);
        assertEquals(SavedState.DONE, done.phase(), "DONE");
        assertNull(done.carry(), "and its carry is cleared with it: the hand-over comes after this write, never before");
    }

    @Test
    void theLastFinishedSessionIsReadForOnePlayerOnly() throws Exception {
        assertNull(dao.lastDoneState(alice), "nothing finished yet");
        for (String[] s : new String[][]{{"a1", "10"}, {"a2", "30"}, {"a3", "20"}}) {
            assertTrue(dao.saveState(state(alice, s[0])), "seeded " + s[0]);
            assertTrue(dao.finishState(alice, s[0], NOW + Long.parseLong(s[1])), "finished " + s[0]);
        }
        assertTrue(dao.saveState(state(bob, "b1")), "bob's");
        assertTrue(dao.finishState(bob, "b1", NOW + 99), "finished later than any of alice's");
        assertTrue(dao.saveState(state(alice, "a4")), "and alice has a live one too");
        assertEquals("a2", dao.lastDoneState(alice).sessionId(), "the most recently finished of hers, not bob's or the live one");
        assertEquals("b1", dao.lastDoneState(bob).sessionId(), "bob's own");
    }

    // ---- Take a break, courses, prefs, the secret ---------------------------------------------

    @Test
    void aBreakRowRoundTripsAndDefaultsToNone() throws Exception {
        assertEquals(GamesDao.BreakRow.empty(alice), dao.breakRow(alice));
        GamesDao.BreakRow row = new GamesDao.BreakRow(alice, 25, 100, DAY + 7, NOW + 86_400_000L, 10, NOW, NOW);
        dao.saveBreak(row);
        assertEquals(row, dao.breakRow(alice));
        dao.saveBreak(row.withDailyTokens(10).withPending(-2, 0));
        assertEquals(10, dao.breakRow(alice).dailyTokens());
        assertEquals(-2, dao.breakRow(alice).pendingTokens());
    }

    @Test
    void coursesKeepTheirCreationAndBumpTheirRevisionOnAGeometryEdit() throws Exception {
        GamesDao.CourseRow row = new GamesDao.CourseRow("river_run", "trials", "boat", "River Run", "games", true,
                "start: {x: 1}", 1, NOW, NOW);
        dao.saveCourse(row);
        assertEquals(row, dao.course("river_run"));
        assertEquals(2, dao.saveCourse(new GamesDao.CourseRow("river_run", "trials", "boat", "River Run", "games", true,
                "start: {x: 2}", 1, NOW + 5, NOW + 5), true), "a geometry edit bumps rev");
        GamesDao.CourseRow after = dao.course("river_run");
        assertEquals(2, after.rev());
        assertEquals(NOW, after.createdAt(), "created_at is kept");
        assertEquals(1, dao.saveCourse(new GamesDao.CourseRow("cliffs", "trials", "parkour", "Cliffs", "games", false,
                "{}", 1, NOW, NOW), true), "a new course starts at rev 1");
        assertEquals(List.of("cliffs", "river_run"), dao.courses("trials").stream().map(GamesDao.CourseRow::id).toList());
        assertTrue(dao.deleteCourse("cliffs"));
        assertNull(dao.course("cliffs"));
    }

    @Test
    void preferencesSetReadAndClear() throws Exception {
        assertNull(dao.pref(alice, "invites.coin_flip"));
        dao.setPref(alice, "invites.coin_flip", "on");
        dao.setPref(alice, "notice.1", "Your Twenty-One hand was finished for you.");
        dao.setPref(alice, "notice.2", "Another.");
        assertEquals("on", dao.pref(alice, "invites.coin_flip"));
        assertEquals(Map.of("notice.1", "Your Twenty-One hand was finished for you.", "notice.2", "Another."),
                dao.prefsLike(alice, "notice."));
        dao.deletePref(alice, "notice.1");
        assertEquals(List.of("notice.2"), List.copyOf(dao.prefsLike(alice, "notice.").keySet()));
        assertNull(dao.pref(bob, "invites.coin_flip"));
    }

    @Test
    void theServerSecretIsMadeOnceAndKept() throws Exception {
        long secret = dao.secret();
        assertEquals(secret, dao.secret(), "made once");
        assertEquals(Long.toString(secret), one("SELECT value FROM hcm_meta WHERE key = 'games_secret'"));
    }
}
