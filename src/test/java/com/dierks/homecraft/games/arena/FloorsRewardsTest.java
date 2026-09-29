package com.dierks.homecraft.games.arena;

import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.games.RewardKind;
import com.dierks.homecraft.games.ScoreResult;
import com.dierks.homecraft.games.SkillRewards;
import com.dierks.homecraft.games.arena.rules.ArenaScoring;
import com.dierks.homecraft.games.arena.rules.OutReason;
import com.dierks.homecraft.games.arena.rules.RoundResult;
import com.dierks.homecraft.games.arena.rules.Standing;
import com.dierks.homecraft.storage.Database;
import com.dierks.homecraft.storage.GamesDao;
import com.dierks.homecraft.storage.TokenDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a played-out round records and pays (EVENTS-DROPPER-SPEC §B.3.4), once with a fake ledger
 * that refuses a once-only ref twice, and once against a real database through the same
 * {@code GamesDao.payReward} that {@code SkillRewards} uses.
 *
 * <p>Pinned here: the daily reward pays once a day and again the next day; the solo milestones pay
 * once ever, never again in a new week; a win pays nothing extra (the winner earns exactly what the
 * others do) and only adds 1 to this week's wins board; solo survival goes on this week's solo
 * board, best kept; a leaver and a round called off earn nothing; a whole minute counts toward the
 * {@code game_floors_minute} achievement; the game's {@code daily_cap} holds.
 */
class FloorsRewardsTest {

    private static final long DAY = 20_730;
    private static final long WEEK = 20_724;
    private static final long NOW = 1_790_000_000_000L;
    private static final UUID ANN = UUID.nameUUIDFromBytes("Ann".getBytes());
    private static final UUID BEN = UUID.nameUUIDFromBytes("Ben".getBytes());
    private static final UUID CAT = UUID.nameUUIDFromBytes("Cat".getBytes());

    /** Records every call; pays a once-only ref once, as the database does. */
    private static final class FakeLedger implements FloorsRewards.Ledger {
        final List<String> calls = new ArrayList<>();
        final Set<String> paid = new HashSet<>();
        final List<UUID> minutes = new ArrayList<>();
        int tokens;

        @Override
        public Long best(UUID player, String board, long value) {
            calls.add("best " + name(player) + " " + board + " " + value);
            return -1L;
        }

        @Override
        public void addPoints(UUID player, String board, long delta) {
            calls.add("points " + name(player) + " " + board + " +" + delta);
        }

        @Override
        public int pay(UUID player, ArenaScoring.Claim claim) {
            return paid(player, claim, "pay ");
        }

        @Override
        public int payWhole(UUID player, ArenaScoring.Claim claim) {
            return paid(player, claim, "pay whole ");
        }

        private int paid(UUID player, ArenaScoring.Claim claim, String how) {
            if (!paid.add(player + "|" + claim.kind() + "|" + claim.ref())) {
                return 0;
            }
            calls.add(how + name(player) + " " + claim.kind() + " " + claim.ref() + " " + claim.tokens());
            tokens += claim.tokens();
            return claim.tokens();
        }

        @Override
        public void lastedMinute(UUID player) {
            minutes.add(player);
        }

        @Override
        public void tell(UUID player, String line) {
        }
    }

    private static String name(UUID p) {
        return p.equals(ANN) ? "Ann" : p.equals(BEN) ? "Ben" : "Cat";
    }

    private static RoundResult solo(UUID player, long ticks, OutReason why) {
        return new RoundResult(1, true, false, false, 1, ticks, List.of(new Standing(player, 1, ticks, why, false,
                false)));
    }

    /** Cat outlasts Ben, who outlasts Ann: a contested round. */
    private static RoundResult threeOfUs(long annTicks, long benTicks, long catTicks) {
        return new RoundResult(2, false, false, true, 3, catTicks, List.of(
                new Standing(CAT, 1, catTicks, null, false, true),
                new Standing(BEN, 2, benTicks, OutReason.FELL, false, false),
                new Standing(ANN, 3, annTicks, OutReason.FELL, false, false)));
    }

    private static final ArenaScoring.Rewards SHIPPED = FallingFloorsSettings.defaults().rewards();

    // ---- with a fake ledger -------------------------------------------------------------------------

    @Test
    void aSoloRoundGoesOnThisWeeksSoloBoardAndPaysItsMilestonesAndTheDaily() {
        FakeLedger l = new FakeLedger();
        FloorsRewards.apply(solo(ANN, 65 * 20, OutReason.FELL), DAY, WEEK, SHIPPED, 0, l);
        assertEquals(List.of("best Ann ffsolo:" + WEEK + " 65000",
                "pay whole Ann MILESTONE ms:ffsolo:1 1",
                "pay whole Ann MILESTONE ms:ffsolo:2 2",
                "pay Ann DAILY_CHALLENGE daily:" + DAY + " 1"), l.calls,
                "the board first, then 30 s and 60 s (not 120 s yet) whole or not at all, then today's daily");
        assertEquals(SkillRewards.milestoneRef("ffsolo", 1), ArenaScoring.milestoneRef(1), "the one spelling of the ref");
        assertEquals(SkillRewards.dailyRef(DAY), ArenaScoring.dailyRef(DAY), "the one spelling of the daily ref");
        assertEquals(List.of(ANN), l.minutes, "a whole minute counts toward game_floors_minute");
    }

    @Test
    void aWinPaysNothingExtraAndOnlyAddsOneToThisWeeksWinsBoard() {
        FakeLedger l = new FakeLedger();
        List<ArenaScoring.PlayerScore> scores = FloorsRewards.apply(threeOfUs(200, 400, 400), DAY, WEEK, SHIPPED, 2, l);
        assertEquals(List.of("points Cat ffwins:" + WEEK + " +1",
                "pay Cat DAILY_CHALLENGE daily:" + DAY + " 1",
                "pay Cat FEATURED featured:" + DAY + " 2",
                "pay Ben DAILY_CHALLENGE daily:" + DAY + " 1",
                "pay Ben FEATURED featured:" + DAY + " 2",
                "pay Ann DAILY_CHALLENGE daily:" + DAY + " 1",
                "pay Ann FEATURED featured:" + DAY + " 2"), l.calls,
                "everyone who played it out earns the same; the winner's only extra is a point on ffwins");
        for (ArenaScoring.PlayerScore ps : scores) {
            assertEquals(scores.get(0).claims().stream().map(c -> c.kind() + " " + c.tokens()).toList(),
                    ps.claims().stream().map(c -> c.kind() + " " + c.tokens()).toList(),
                    name(ps.player()) + " earns exactly what the winner earns");
        }
    }

    @Test
    void aLeaverAndARoundCalledOffEarnNothing() {
        FakeLedger l = new FakeLedger();
        FloorsRewards.apply(RoundResult.calledOff(3, false, 2, 40), DAY, WEEK, SHIPPED, 2, l);
        assertEquals(List.of(), l.calls, "called off: nothing at all");
        FloorsRewards.apply(solo(BEN, 2000, OutReason.LEFT), DAY, WEEK, SHIPPED, 2, l);
        assertEquals(List.of(), l.calls, "leaving isn't playing it out: no board, no milestone, no daily");
        assertEquals(List.of(), l.minutes, "and no achievement");
    }

    @Test
    void aShortSoloRoundIsNotTheDailysFullRound() {
        FakeLedger l = new FakeLedger();
        FloorsRewards.apply(solo(ANN, 19 * 20, OutReason.FELL), DAY, WEEK, SHIPPED, 0, l);
        assertEquals(List.of("best Ann ffsolo:" + WEEK + " 19000"), l.calls,
                "under 20 s solo: on the board, but not the day's full round");
    }

    @Test
    void theBestLineSaysAFirstTimeOrANewBestAndNothingOtherwise() {
        assertEquals("&aYour first solo time this week: 0:42!", FloorsRewards.bestLine(42_000, -1L), "a first time");
        assertEquals("&aNew best this week: 1:05 &7(was 0:42)&a!", FloorsRewards.bestLine(65_000, 42_000L),
                "a new best, with the old one");
        assertNull(FloorsRewards.bestLine(30_000, null), "not a best: nothing to say");
    }

    // ---- against the database -----------------------------------------------------------------------

    private Connection conn;
    private GamesDao dao;
    private TokenDao tokens;

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

    /** The live ledger's steps on a real database: Scores' submit, addPoints and SkillRewards' payReward. */
    private FloorsRewards.Ledger db(long day, int dailyCap) {
        return new FloorsRewards.Ledger() {
            @Override
            public Long best(UUID player, String board, long value) {
                try {
                    ScoreResult r = dao.submit(player, FloorsRewards.GAME, board, value, false, NOW);
                    return r.personalBest() ? (r.previous() == null ? -1L : r.previous()) : null;
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            }

            @Override
            public void addPoints(UUID player, String board, long delta) {
                try {
                    dao.addPoints(player, FloorsRewards.GAME, board, delta, NOW);
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            }

            @Override
            public int pay(UUID player, ArenaScoring.Claim c) {
                return paid(player, c, false);
            }

            @Override
            public int payWhole(UUID player, ArenaScoring.Claim c) {
                return paid(player, c, true);
            }

            private int paid(UUID player, ArenaScoring.Claim c, boolean whole) {
                try {
                    return dao.payReward(player, FloorsRewards.GAME, TokenService.Source.GAMES_FLOORS, day, c.kind(),
                            c.ref(), c.tokens(), c.kind().acrossGames() ? -1 : dailyCap, 6, true, whole, c.detail(),
                            NOW);
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            }

            @Override
            public void lastedMinute(UUID player) {
            }

            @Override
            public void tell(UUID player, String line) {
            }
        };
    }

    private int balance(UUID p) throws SQLException {
        return tokens.get(p).tokens();
    }

    @Test
    void theDailyRewardPaysOnceADayAndAgainTheNextDay() throws Exception {
        FloorsRewards.apply(threeOfUs(200, 400, 400), DAY, WEEK, SHIPPED, 0, db(DAY, 3));
        assertEquals(1, balance(ANN), "the first full round of the day pays 1");
        FloorsRewards.apply(threeOfUs(300, 500, 500), DAY, WEEK, SHIPPED, 0, db(DAY, 3));
        assertEquals(1, balance(ANN), "a second round the same day pays nothing more");
        FloorsRewards.apply(threeOfUs(300, 500, 500), DAY + 1, WEEK, SHIPPED, 0, db(DAY + 1, 3));
        assertEquals(2, balance(ANN), "the next day's first round pays again");
        assertEquals(balance(ANN), balance(CAT), "the winner earned no more than Ann");
        assertEquals(3L, dao.best(CAT, FloorsRewards.GAME, ArenaScoring.winsBoard(WEEK)), "Cat's three wins are on ffwins");
        assertNull(dao.best(ANN, FloorsRewards.GAME, ArenaScoring.winsBoard(WEEK)), "Ann has no wins");
        assertTrue(dao.rewardPaid(ANN, FloorsRewards.GAME, RewardKind.DAILY_CHALLENGE, SkillRewards.dailyRef(DAY)),
                "the daily is recorded under SkillRewards' own ref");
    }

    @Test
    void theMilestonesPayOnceEverNeverAgainInANewWeek() throws Exception {
        FloorsRewards.apply(solo(BEN, 65 * 20, OutReason.FELL), DAY, WEEK, SHIPPED, 0, db(DAY, 10));
        assertEquals(1 + 2 + 1, balance(BEN), "30 s and 60 s (1 + 2), and the daily 1");
        long nextWeek = WEEK + 7;
        FloorsRewards.apply(solo(BEN, 125 * 20, OutReason.FELL), DAY + 7, nextWeek, SHIPPED, 0, db(DAY + 7, 10));
        assertEquals(4 + 3 + 1, balance(BEN), "next week only the new 120 s milestone (3) and that day's daily (1)");
        assertEquals(125_000L, dao.best(BEN, FloorsRewards.GAME, ArenaScoring.soloBoard(nextWeek)),
                "the new week's solo board");
        assertEquals(65_000L, dao.best(BEN, FloorsRewards.GAME, ArenaScoring.soloBoard(WEEK)),
                "last week's board keeps last week's time");
        FloorsRewards.apply(solo(BEN, 30 * 20, OutReason.FELL), DAY + 7, nextWeek, SHIPPED, 0, db(DAY + 7, 10));
        assertEquals(125_000L, dao.best(BEN, FloorsRewards.GAME, ArenaScoring.soloBoard(nextWeek)),
                "a shorter round keeps the best");
    }

    /**
     * F review #5: a milestone the day's cap can pay only part of is paid whole another day, never
     * recorded short for ever (before, 1 of its 2 tokens was paid and its once-ever ref kept).
     */
    @Test
    void aMilestoneTheCapCanOnlyPayPartOfWaitsWholeForAnotherDay() throws Exception {
        FloorsRewards.apply(solo(ANN, 65 * 20, OutReason.FELL), DAY, WEEK, SHIPPED, 0, db(DAY, 2));
        assertEquals(1 + 1, balance(ANN), "30 s (1), then 60 s (2) can't be paid whole with 1 left, so the daily (1)");
        assertTrue(!dao.rewardPaid(ANN, FloorsRewards.GAME, RewardKind.MILESTONE, ArenaScoring.milestoneRef(2)),
                "the 60 s milestone isn't recorded: it is still there to earn");
        FloorsRewards.apply(solo(ANN, 65 * 20, OutReason.FELL), DAY + 1, WEEK, SHIPPED, 0, db(DAY + 1, 2));
        assertEquals(2 + 2, balance(ANN), "the next day it is paid whole: 2");
        assertTrue(dao.rewardPaid(ANN, FloorsRewards.GAME, RewardKind.MILESTONE, ArenaScoring.milestoneRef(2)),
                "and now it is once ever");
    }

    @Test
    void theGamesDailyCapHolds() throws Exception {
        FloorsRewards.apply(solo(CAT, 125 * 20, OutReason.FELL), DAY, WEEK, SHIPPED, 0, db(DAY, 3));
        assertEquals(3, balance(CAT), "1 + 2 + 3 + 1 would be 7, but daily_cap is 3");
        assertEquals(3, dao.rewardsToday(CAT, FloorsRewards.GAME, DAY), "all of it counted toward the cap");
        FloorsRewards.apply(solo(CAT, 125 * 20, OutReason.FELL), DAY + 1, WEEK, SHIPPED, 0, db(DAY + 1, 3));
        assertEquals(6, balance(CAT), "the milestone the cap held back is still there the next day");
    }
}
