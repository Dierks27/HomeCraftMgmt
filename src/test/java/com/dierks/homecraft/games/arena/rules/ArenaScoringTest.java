package com.dierks.homecraft.games.arena.rules;

import com.dierks.homecraft.games.RewardKind;
import com.dierks.homecraft.games.SkillRewards;
import com.dierks.homecraft.games.TokenBalance;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.dierks.homecraft.games.arena.rules.ArenaFixtures.p;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scores and rewards (EVENTS-DROPPER-SPEC §B.3.4): {@code ffsolo:<week>} survival ms, higher is
 * better; {@code ffwins:<week>} as a count; the daily reward for the first full round (multiplayer,
 * or solo from 20 s); solo milestones 30 / 60 / 120 s for 1 / 2 / 3 once ever; the featured bonus;
 * and wins paying nothing extra.
 */
class ArenaScoringTest {

    /**
     * The scoring at 0.36's rewards (1 a day; 1, 2 and 3 for the milestones), so each claim's amount
     * reads as the rule it pins. The shipped values are pinned in {@link #rewardKnobsAreKeptSane}.
     */
    private static final ArenaScoring.Rewards R = new ArenaScoring.Rewards(1, List.of(30, 60, 120), List.of(1, 2, 3));

    private static final long DAY = 20_725;
    private static final long WEEK = 2_960;

    private static RoundResult solo(long ticks, OutReason reason) {
        return new RoundResult(1, true, false, false, 1, ticks, List.of(new Standing(p(1), 1, ticks, reason, false,
                false)));
    }

    /** p(1) wins at 1,400 ticks (1:10); p(2) 2nd at 1,400; p(3) 3rd at 300; p(4) left at 100. */
    private static RoundResult multi() {
        return new RoundResult(2, false, false, true, 4, 1400, List.of(
                new Standing(p(1), 1, 1400, null, false, true),
                new Standing(p(2), 2, 1400, OutReason.FELL, false, false),
                new Standing(p(3), 3, 300, OutReason.FELL, false, false),
                new Standing(p(4), 4, 100, OutReason.LEFT, false, false)));
    }

    private static ArenaScoring.PlayerScore of(List<ArenaScoring.PlayerScore> scores, int n) {
        for (ArenaScoring.PlayerScore s : scores) {
            if (s.player().equals(p(n))) {
                return s;
            }
        }
        throw new AssertionError("no score for p(" + n + ")");
    }

    @Test
    void aSoloRoundGoesOnTheWeeksSoloBoardInMillisecondsHigherIsBetter() {
        ArenaScoring.PlayerScore s = ArenaScoring.score(solo(840, OutReason.FELL), DAY, WEEK,
                R, 0).get(0);
        assertEquals(List.of(new ArenaScoring.BoardWrite("ffsolo:" + WEEK, 42_000, ArenaScoring.BoardMode.BEST_HIGHER)),
                s.boards(), "0:42 solo is 42,000 on ffsolo:<week>, kept if it's their best");
    }

    @Test
    void soloMilestonesPayOnceEverAtThirtySixtyAndOneHundredTwentySeconds() {
        List<ArenaScoring.Claim> at45 = ArenaScoring.score(solo(900, OutReason.FELL), DAY, WEEK,
                R, 0).get(0).claims();
        assertTrue(at45.contains(new ArenaScoring.Claim(RewardKind.MILESTONE, "ms:ffsolo:1", 1,
                "Falling Floors: lasted 0:30")), "45 s passes the 30 s milestone: +1");
        assertEquals(1, at45.stream().filter(c -> c.kind() == RewardKind.MILESTONE).count(), "and only that one");

        List<ArenaScoring.Claim> at125 = ArenaScoring.score(solo(2500, OutReason.FELL), DAY, WEEK,
                R, 0).get(0).claims();
        assertEquals(List.of(new ArenaScoring.Claim(RewardKind.MILESTONE, "ms:ffsolo:1", 1, "Falling Floors: lasted 0:30"),
                        new ArenaScoring.Claim(RewardKind.MILESTONE, "ms:ffsolo:2", 2, "Falling Floors: lasted 1:00"),
                        new ArenaScoring.Claim(RewardKind.MILESTONE, "ms:ffsolo:3", 3, "Falling Floors: lasted 2:00")),
                at125.stream().filter(c -> c.kind() == RewardKind.MILESTONE).toList(),
                "125 s reaches all three: +1, +2, +3 (each claimed; the database pays each ref once, ever)");

        List<ArenaScoring.Claim> nextWeek = ArenaScoring.score(solo(2500, OutReason.FELL), DAY + 7, WEEK + 1,
                R, 0).get(0).claims();
        assertEquals(at125.stream().filter(c -> c.kind() == RewardKind.MILESTONE).map(ArenaScoring.Claim::ref).toList(),
                nextWeek.stream().filter(c -> c.kind() == RewardKind.MILESTONE).map(ArenaScoring.Claim::ref).toList(),
                "a new week claims the same refs, so a milestone is paid once ever, not once a week");
    }

    @Test
    void theDailyRewardIsTheFirstFullRoundOfTheDay() {
        ArenaScoring.PlayerScore longSolo = ArenaScoring.score(solo(400, OutReason.FELL), DAY, WEEK,
                R, 0).get(0);
        assertTrue(longSolo.fullRound(), "solo for 20 s is a full round");
        assertTrue(longSolo.claims().contains(new ArenaScoring.Claim(RewardKind.DAILY_CHALLENGE, "daily:" + DAY, 1,
                "Falling Floors: daily challenge")), "and claims today's daily: +1");
        ArenaScoring.PlayerScore shortSolo = ArenaScoring.score(solo(399, OutReason.FELL), DAY, WEEK,
                R, 0).get(0);
        assertFalse(shortSolo.fullRound(), "a tick under 20 s is not");
        assertTrue(shortSolo.claims().isEmpty(), "and claims nothing (it is still on the board)");
        assertEquals(1, shortSolo.boards().size(), "on the board");

        List<ArenaScoring.PlayerScore> m = ArenaScoring.score(multi(), DAY, WEEK, R, 0);
        for (int n = 1; n <= 3; n++) {
            assertTrue(of(m, n).claims().contains(new ArenaScoring.Claim(RewardKind.DAILY_CHALLENGE, "daily:" + DAY, 1,
                    "Falling Floors: daily challenge")), "a played-out multiplayer round is a full round for p(" + n
                    + "), however early they fell");
        }
        assertEquals(ArenaScoring.score(multi(), DAY, WEEK, R, 0).get(0).claims(),
                of(m, 1).claims(), "a second round the same day claims the same daily ref (the database pays it once)");
        assertNotEquals(ArenaScoring.dailyRef(DAY), ArenaScoring.dailyRef(DAY + 1), "tomorrow is a new daily");
    }

    @Test
    void winsGoOnTheWinsBoardAndPayNothingExtra() {
        List<ArenaScoring.PlayerScore> m = ArenaScoring.score(multi(), DAY, WEEK, R, 2);
        assertEquals(List.of(new ArenaScoring.BoardWrite("ffwins:" + WEEK, 1, ArenaScoring.BoardMode.ADD_POINTS)),
                of(m, 1).boards(), "the winner's win is +1 on ffwins:<week>, added up");
        assertTrue(of(m, 2).boards().isEmpty(), "2nd goes on no board");
        assertEquals(of(m, 2).claims(), of(m, 1).claims(),
                "the winner claims exactly what 2nd claims: wins pay nothing extra, so taking turns to lose earns nothing");
        assertEquals(of(m, 3).claims(), of(m, 1).claims(), "and what 3rd claims");
        assertTrue(of(m, 1).claims().stream().noneMatch(c -> c.kind() == RewardKind.MILESTONE),
                "solo milestones are for solo rounds only, however long a multiplayer round lasts");
    }

    @Test
    void leavingEarnsNothing() {
        ArenaScoring.PlayerScore left = of(ArenaScoring.score(multi(), DAY, WEEK, R, 2), 4);
        assertFalse(left.fullRound(), "leaving isn't playing the round out");
        assertTrue(left.claims().isEmpty() && left.boards().isEmpty(), "no daily, no featured, no board");
        ArenaScoring.PlayerScore soloLeft = ArenaScoring.score(solo(3000, OutReason.LEFT), DAY, WEEK,
                R, 2).get(0);
        assertTrue(soloLeft.claims().isEmpty() && soloLeft.boards().isEmpty(),
                "a solo player who leaves at 2:30 gets no time, milestone or daily: only a fall ends a solo run");
        assertFalse(soloLeft.lastedMinute(), "nor the quest");
    }

    @Test
    void aRoundEveryoneElseLeftIsNoWinAndNoFullRound() {
        RoundResult walkover = new RoundResult(3, false, false, false, 2, 60, List.of(
                new Standing(p(1), 1, 60, null, false, false),
                new Standing(p(2), 2, 60, OutReason.LEFT, false, false)));
        ArenaScoring.PlayerScore s = of(ArenaScoring.score(walkover, DAY, WEEK, R, 0), 1);
        assertTrue(s.boards().isEmpty(), "no win on ffwins");
        assertFalse(s.fullRound(), "and no daily: an uncontested round wasn't a round");
    }

    @Test
    void jointWinnersEachGetAWin() {
        RoundResult together = new RoundResult(4, false, false, true, 2, 500, List.of(
                new Standing(p(1), 1, 500, OutReason.FELL, true, true),
                new Standing(p(2), 1, 500, OutReason.FELL, true, true)));
        List<ArenaScoring.PlayerScore> m = ArenaScoring.score(together, DAY, WEEK, R, 0);
        assertEquals(1, of(m, 1).boards().size(), "the first joint winner's win");
        assertEquals(1, of(m, 2).boards().size(), "and the second's");
    }

    @Test
    void theFeaturedBonusIsClaimedForAFullRoundOnTodaysPick() {
        ArenaScoring.PlayerScore picked = ArenaScoring.score(solo(600, OutReason.FELL), DAY, WEEK,
                R, 2).get(0);
        assertTrue(picked.claims().contains(new ArenaScoring.Claim(RewardKind.FEATURED, "featured:" + DAY, 2,
                "Falling Floors: today's pick")), "today's pick: the featured bonus is claimed");
        ArenaScoring.PlayerScore notPicked = ArenaScoring.score(solo(600, OutReason.FELL), DAY, WEEK,
                R, 0).get(0);
        assertTrue(notPicked.claims().stream().noneMatch(c -> c.kind() == RewardKind.FEATURED), "otherwise not");
        ArenaScoring.PlayerScore tooShort = ArenaScoring.score(solo(100, OutReason.FELL), DAY, WEEK,
                R, 2).get(0);
        assertTrue(tooShort.claims().isEmpty(), "and not for a 5 s solo");
    }

    @Test
    void aCalledOffRoundEarnsNothing() {
        assertTrue(ArenaScoring.score(RoundResult.calledOff(5, false, 3, 40), DAY, WEEK,
                R, 2).isEmpty(), "no results are kept for a round that wasn't played out");
        assertTrue(ArenaScoring.score(null, DAY, WEEK, null, 0).isEmpty(), "nor for none");
    }

    @Test
    void lastingAWholeMinuteIsTheQuest() {
        assertTrue(ArenaScoring.score(solo(1200, OutReason.FELL), DAY, WEEK, null, 0).get(0).lastedMinute(),
                "1:00 solo");
        assertFalse(ArenaScoring.score(solo(1199, OutReason.FELL), DAY, WEEK, null, 0).get(0).lastedMinute(),
                "a tick short");
        assertTrue(of(ArenaScoring.score(multi(), DAY, WEEK, null, 0), 1).lastedMinute(), "the winner at 1:10");
        assertFalse(of(ArenaScoring.score(multi(), DAY, WEEK, null, 0), 3).lastedMinute(), "3rd at 0:15");
    }

    @Test
    void theRefsAreSkillRewardsOwn() {
        assertEquals(SkillRewards.dailyRef(DAY), ArenaScoring.dailyRef(DAY), "the daily ref");
        assertEquals(SkillRewards.featuredRef(DAY), ArenaScoring.featuredRef(DAY), "the featured ref");
        for (int n = 1; n <= 3; n++) {
            assertEquals(SkillRewards.milestoneRef(ArenaScoring.SOLO_BOARD, n), ArenaScoring.milestoneRef(n),
                    "milestone " + n + "'s ref, on the board's stem");
        }
    }

    @Test
    void rewardKnobsAreKeptSane() {
        ArenaScoring.Rewards r = new ArenaScoring.Rewards(-3, List.of(30, 60, 120), List.of(1, -2));
        assertEquals(0, r.daily(), "a negative daily is none");
        assertEquals(List.of(30, 60), r.milestoneSeconds(), "a milestone without a reward is dropped");
        assertEquals(List.of(1, 0), r.milestoneTokens(), "a negative reward is none");
        List<ArenaScoring.Claim> claims = ArenaScoring.score(solo(2500, OutReason.FELL), DAY, WEEK, r, 0).get(0)
                .claims();
        assertEquals(List.of(new ArenaScoring.Claim(RewardKind.MILESTONE, "ms:ffsolo:1", 1,
                "Falling Floors: lasted 0:30")), claims, "only the milestone that pays, and no daily of 0");
        ArenaScoring.Rewards d = ArenaScoring.Rewards.defaults();
        assertEquals(List.of(TokenBalance.FLOORS_DAILY, List.of(30, 60, 120), TokenBalance.FLOORS_MILESTONES),
                List.of(d.daily(), d.milestoneSeconds(), d.milestoneTokens()),
                "the shipped values: 30, 60 and 120 seconds, and the token balance's tokens");
    }
}
