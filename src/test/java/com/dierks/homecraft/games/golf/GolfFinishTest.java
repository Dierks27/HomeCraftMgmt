package com.dierks.homecraft.games.golf;

import com.dierks.homecraft.games.RewardKind;
import com.dierks.homecraft.games.ScoreResult;
import com.dierks.homecraft.games.gen.api.DailyStars;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.storage.GamesDao;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a finished golf round records and pays, with a fake ledger that refuses a once-only ref
 * twice, as the database does.
 *
 * <p>Pinned here: a hand-built round goes on the course's all-time board and pays the first
 * finish, par, each hole-in-one and today's pick, each under its one spelling of ref on the
 * calendar day, in that order, and never a reward of 0. A daily course's round (GEN-SPEC §5.3)
 * goes on its layout's board only; earns stars (3 at or under par, 2 within one stroke a three
 * holes, 1 for finishing) kept per course day with the week moved by the difference; pays its
 * course day's first finish once (a reroll that day pays no second one); pays par and each
 * hole-in-one on the layout's course day while today's pick keeps the calendar day; keeps the first
 * clear once ever; and pays a Star Chart goal it crosses once, through the daily game.
 */
class GolfFinishTest {

    private static final long CALENDAR_DAY = 20_730;
    private static final long LAYOUT_DAY = 20_729;
    private static final long WEEK = 20_727;

    /** Records every call; pays a once-only ref once; keeps stars like GamesDao.addStars. */
    private static final class Ledger implements GolfFinish.Ledger {
        final List<String> calls = new ArrayList<>();
        final Set<String> paid = new HashSet<>();
        final Map<String, Long> boards = new HashMap<>();
        final List<Boolean> announcedDaily = new ArrayList<>();

        @Override
        public ScoreResult submit(String board, int strokes) {
            calls.add("submit " + board + " " + strokes);
            return new ScoreResult(true, null, true, 1);
        }

        @Override
        public void announce(ScoreResult result, boolean daily) {
            announcedDaily.add(daily);
        }

        @Override
        public int pay(RewardKind kind, String ref, int tokens, String detail) {
            calls.add("pay " + kind + " " + ref + " " + tokens + " " + detail);
            return paid.add(ref) ? tokens : 0;
        }

        @Override
        public GamesDao.StarsAdded addStars(String dayBoard, String weekBoard, int stars) {
            calls.add("stars " + dayBoard + " " + weekBoard + " " + stars);
            Long best = boards.get(dayBoard);
            long week = boards.getOrDefault(weekBoard, 0L);
            int added = DailyStars.delta(best, stars);
            boards.put(dayBoard, Math.max(best == null ? 0 : best, stars));
            boards.put(weekBoard, week + added);
            return new GamesDao.StarsAdded((int) Math.max(best == null ? 0 : best, stars), added, week + added);
        }

        @Override
        public int payGoal(String ref, int tokens, String detail) {
            calls.add("goal " + ref + " " + tokens);
            return paid.add(ref) ? tokens : 0;
        }
    }

    private static GenTag tag(long day, int reroll) {
        return new GenTag("daily_golf", "golf", 1, day, reroll, 5L, 'A', "0123456789ab", 0, 0, 0,
                List.of(0, 0, 0), List.of(), 1L);
    }

    /** A round of 3 holes, par 9, finished on the calendar day. */
    private static GolfFinish.Round round(int strokes, List<Integer> holesInOne, boolean featured, GenTag tag) {
        GolfFinish.Daily daily = tag == null ? null : new GolfFinish.Daily(tag, WEEK, 2, List.of(10, 25), 1);
        String id = tag == null ? "meadow" : "daily_golf";
        return new GolfFinish.Round(id, "Mini Golf: X", strokes, 9, 3, strokes <= 9, holesInOne, CALENDAR_DAY, featured,
                5, 2, 1, 3, daily);
    }

    @Test
    void aHandBuiltRoundIsRecordedAndPaidAsBefore() {
        Ledger l = new Ledger();
        GolfFinish.Summary s = GolfFinish.settle(round(8, List.of(2), true, null), l);
        assertEquals(List.of(
                "submit golf:meadow 8",
                "pay FIRST_CLEAR first_clear:meadow 5 Mini Golf: X first finish",
                "pay PAR par:meadow:" + CALENDAR_DAY + " 2 Mini Golf: X at par or better",
                "pay HOLE_IN_ONE hio:meadow:2:" + CALENDAR_DAY + " 1 Mini Golf: X hole-in-one on hole 2",
                "pay FEATURED featured:" + CALENDAR_DAY + " 3 Mini Golf: X is today's pick"), l.calls,
                "the all-time board, then the rewards in the old order and refs");
        assertEquals(List.of(false), l.announcedDaily, "announced as the all-time board");
        assertEquals(0, s.stars(), "no stars on a hand-built course");
        assertEquals(-1, s.weekStars(), "and no Star Chart");
        assertEquals(11, s.earned(), "what was paid");
    }

    @Test
    void aRewardOfNothingIsNeverAsked() {
        Ledger l = new Ledger();
        GolfFinish.settle(new GolfFinish.Round("meadow", "X", 8, 9, 3, true, List.of(1), CALENDAR_DAY, true, 0, 0, 0,
                0, null), l);
        assertTrue(l.calls.stream().noneMatch(c -> c.startsWith("pay")), "every amount 0: nothing asked");
    }

    @Test
    void aDailyRoundGoesOnItsLayoutsBoardOnly() {
        Ledger l = new Ledger();
        GolfFinish.settle(round(12, List.of(), false, tag(LAYOUT_DAY, 0)), l);
        assertEquals(List.of("submit gday:daily_golf:" + LAYOUT_DAY + " 12"),
                l.calls.stream().filter(c -> c.startsWith("submit")).toList(), "never golf:daily_golf");
        assertEquals(List.of(true), l.announcedDaily, "announced as today's board");
        Ledger r = new Ledger();
        GolfFinish.settle(round(12, List.of(), false, tag(LAYOUT_DAY, 1)), r);
        assertTrue(r.calls.contains("submit gday:daily_golf:" + LAYOUT_DAY + "r1 12"), "a reroll: a fresh board");
    }

    @Test
    void parAndHolesInOnePayOnTheLayoutsDayAndTodaysPickOnTheCalendars() {
        Ledger l = new Ledger();
        GolfFinish.settle(round(8, List.of(1, 3), true, tag(LAYOUT_DAY, 0)), l);
        assertTrue(l.calls.contains("pay PAR par:daily_golf:" + LAYOUT_DAY + " 2 Mini Golf: X at par or better"),
                "par on the course day of the layout: " + l.calls);
        assertTrue(l.calls.contains("pay HOLE_IN_ONE hio:daily_golf:1:" + LAYOUT_DAY + " 1 Mini Golf: X hole-in-one on hole 1"),
                "a hole-in-one too");
        assertTrue(l.calls.contains("pay HOLE_IN_ONE hio:daily_golf:3:" + LAYOUT_DAY + " 1 Mini Golf: X hole-in-one on hole 3"),
                "each hole");
        assertTrue(l.calls.contains("pay FEATURED featured:" + CALENDAR_DAY + " 3 Mini Golf: X is today's pick"),
                "today's pick is the calendar's, like every game's");
        assertFalse(l.calls.stream().anyMatch(c -> c.contains(":" + CALENDAR_DAY) && !c.contains("FEATURED")),
                "nothing else uses the calendar day");
    }

    @Test
    void theDailyFirstFinishPaysOncePerCourseDayAndTheFirstClearOnceEver() {
        Ledger l = new Ledger();
        GolfFinish.Summary first = GolfFinish.settle(round(12, List.of(), false, tag(LAYOUT_DAY, 0)), l);
        GolfFinish.Summary reroll = GolfFinish.settle(round(12, List.of(), false, tag(LAYOUT_DAY, 1)), l);
        GolfFinish.Summary next = GolfFinish.settle(round(12, List.of(), false, tag(LAYOUT_DAY + 1, 0)), l);
        assertEquals(2, l.calls.stream().filter(c -> c.startsWith("pay DAILY_CLEAR dclear:daily_golf:" + LAYOUT_DAY + " "))
                .count(), "the first finish and the reroll ask under one ref: " + l.calls);
        assertEquals(5 + 2, first.earned(), "the very first finish and the day's first finish");
        assertEquals(0, reroll.earned(), "a reroll that day pays neither again");
        assertEquals(2, next.earned(), "the next course day's first finish pays; the first clear doesn't");
        assertEquals(3, l.calls.stream().filter(c -> c.startsWith("pay FIRST_CLEAR first_clear:daily_golf ")).count(),
                "one once-ever ref for every layout");
    }

    @Test
    void starsFollowParAndTheWeekMovesByTheDifference() {
        Ledger l = new Ledger();
        GolfFinish.Summary one = GolfFinish.settle(round(12, List.of(), false, tag(LAYOUT_DAY, 0)), l);
        assertEquals(1, one.stars(), "12 on par 9 over 3 holes: past par + 1, one star for finishing");
        GolfFinish.Summary two = GolfFinish.settle(round(10, List.of(), false, tag(LAYOUT_DAY, 0)), l);
        assertEquals(2, two.stars(), "par + 1 on three holes: two stars");
        GolfFinish.Summary three = GolfFinish.settle(round(9, List.of(), false, tag(LAYOUT_DAY, 0)), l);
        assertEquals(3, three.stars(), "at par: three");
        assertEquals(3, three.weekStars(), "the week has the day's best, 3, not 1 + 2 + 3");
        assertTrue(l.calls.contains("stars gstars:daily_golf:" + LAYOUT_DAY + " gweek:" + WEEK + " 3"),
                "kept on the layout's day and its week");
    }

    @Test
    void aStarGoalCrossedIsPaidOnce() {
        Ledger l = new Ledger();
        l.boards.put("gweek:" + WEEK, 9L);
        GolfFinish.Summary s = GolfFinish.settle(round(9, List.of(), false, tag(LAYOUT_DAY, 0)), l);
        assertTrue(l.calls.contains("goal ms:gweek:" + WEEK + ":10 1"), "9 to 12 crosses 10: " + l.calls);
        assertEquals(12, s.weekStars(), "the week after");
        GolfFinish.settle(round(9, List.of(), false, tag(LAYOUT_DAY + 1, 0)), l);
        assertEquals(1, l.calls.stream().filter(c -> c.startsWith("goal")).count(), "12 to 15 crosses nothing");
    }
}
