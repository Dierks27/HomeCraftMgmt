package com.dierks.homecraft.games.golf;

import com.dierks.homecraft.games.RewardKind;
import com.dierks.homecraft.games.ScoreResult;
import com.dierks.homecraft.games.gen.api.DailyStars;
import com.dierks.homecraft.games.gen.api.GenBoards;
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
 * calendar day, in that order, and never a reward of 0. A Fresh course's round (GEN-SPEC §5.3, the
 * weekly addendum §4) goes on its set's board only; earns stars (3 at or under par, 2 within one
 * stroke a three holes, 1 for finishing) kept per set with the week moved by the difference; pays
 * its set's first finish once, all or nothing (a reroll, or a second round a day later in the same
 * set, pays no second one); pays par and each hole-in-one once per set ({@code par:<slot>:<edition>},
 * so a second par in the same week pays nothing) while today's pick keeps the calendar day; keeps
 * the first clear once ever; and pays each Star Chart goal it reaches its own tokens once, whole,
 * through Fresh Courses.
 */
class GolfFinishTest {

    private static final long CALENDAR_DAY = 20_730;
    /** Mon 28 Sep 2026, the weekly set's first day. */
    private static final long LAYOUT_DAY = 20_724;
    private static final long WEEK = 20_724;

    /** Records every call; pays a once-only ref once; keeps stars like GamesDao.addStars. */
    private static class Ledger implements GolfFinish.Ledger {
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
        public int payWhole(RewardKind kind, String ref, int tokens, String detail) {
            calls.add("whole " + kind + " " + ref + " " + tokens + " " + detail);
            return paid.add(ref) ? tokens : 0;
        }

        @Override
        public int payGoal(String ref, int tokens, String detail) {
            calls.add("goal " + ref + " " + tokens);
            return paid.add(ref) ? tokens : 0;
        }
    }

    /** The big golf course's layout of the weekly set starting {@code day}. */
    private static GenTag tag(long day, int reroll) {
        return new GenTag("fresh_golf", "golf", 1, day, reroll, 5L, 'A', "0123456789ab", 0, 0, 0,
                List.of(0, 0, 0), List.of(), 1L, 7);
    }

    private static final List<DailyStars.Goal> GOALS = List.of(new DailyStars.Goal(10, 1), new DailyStars.Goal(25, 2));

    /** A round of 3 holes, par 9, finished on the calendar day. */
    private static GolfFinish.Round round(int strokes, List<Integer> holesInOne, boolean featured, GenTag tag) {
        return round(strokes, holesInOne, featured, tag, CALENDAR_DAY);
    }

    private static GolfFinish.Round round(int strokes, List<Integer> holesInOne, boolean featured, GenTag tag,
                                          long day) {
        GolfFinish.Daily daily = tag == null ? null : new GolfFinish.Daily(tag, WEEK, 2, GOALS);
        String id = tag == null ? "meadow" : "fresh_golf";
        return new GolfFinish.Round(id, "Mini Golf: X", strokes, 9, 3, strokes <= 9, holesInOne, day, featured,
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
        assertEquals(List.of("submit " + GenBoards.day(tag(LAYOUT_DAY, 0)) + " 12"),
                l.calls.stream().filter(c -> c.startsWith("submit")).toList(), "never golf:fresh_golf");
        assertEquals(List.of(true), l.announcedDaily, "announced as the set's board");
        Ledger r = new Ledger();
        GolfFinish.settle(round(12, List.of(), false, tag(LAYOUT_DAY, 1)), r);
        assertTrue(r.calls.contains("submit " + GenBoards.day(tag(LAYOUT_DAY, 1)) + " 12"), "a reroll: a fresh board");
    }

    @Test
    void parAndHolesInOnePayOncePerSetAndTodaysPickOnTheCalendarDay() {
        Ledger l = new Ledger();
        String edition = tag(LAYOUT_DAY, 0).edition();
        GolfFinish.settle(round(8, List.of(1, 3), true, tag(LAYOUT_DAY, 0)), l);
        assertTrue(l.calls.contains("pay PAR par:fresh_golf:" + edition + " 2 Mini Golf: X at par or better"),
                "par once per set, under the set's own key: " + l.calls);
        assertTrue(l.calls.contains("pay HOLE_IN_ONE hio:fresh_golf:1:" + edition + " 1 Mini Golf: X hole-in-one on hole 1"),
                "a hole-in-one too");
        assertTrue(l.calls.contains("pay HOLE_IN_ONE hio:fresh_golf:3:" + edition + " 1 Mini Golf: X hole-in-one on hole 3"),
                "each hole");
        assertTrue(l.calls.contains("pay FEATURED featured:" + CALENDAR_DAY + " 3 Mini Golf: X is today's pick"),
                "today's pick is the calendar's, like every game's");
        assertFalse(l.calls.stream().anyMatch(c -> c.contains(":" + CALENDAR_DAY) && !c.contains("FEATURED")),
                "nothing else uses the calendar day");

        GolfFinish.Summary again = GolfFinish.settle(round(8, List.of(), false, tag(LAYOUT_DAY, 0), CALENDAR_DAY + 1), l);
        assertEquals(0, again.earned(), "a second par a day later in the same week pays nothing: " + l.calls);
        assertEquals(2, l.calls.stream().filter(c -> c.startsWith("pay PAR par:fresh_golf:" + edition + " ")).count(),
                "it asked under the same once-per-set ref");
        GenTag daily = new GenTag("fresh_golf", "golf", 1, LAYOUT_DAY, 0, 5L, 'A', "0123456789ab", 0, 0, 0,
                List.of(0, 0, 0), List.of(), 1L, 1);
        assertFalse(daily.edition().equals(edition), "a daily set starting the same day has another key");
        GolfFinish.Summary other = GolfFinish.settle(round(8, List.of(), false, daily), l);
        assertTrue(other.earned() >= 2, "so a daily and a weekly set that begin the same day never share a par: "
                + l.calls);
    }

    @Test
    void theSetsFirstFinishPaysOncePerSetWholeAndTheFirstClearOnceEver() {
        Ledger l = new Ledger();
        String ref = GenBoards.clearRef(tag(LAYOUT_DAY, 0));
        GolfFinish.Summary first = GolfFinish.settle(round(12, List.of(), false, tag(LAYOUT_DAY, 0)), l);
        GolfFinish.Summary nextDay = GolfFinish.settle(round(12, List.of(), false, tag(LAYOUT_DAY, 0),
                CALENDAR_DAY + 1), l);
        GolfFinish.Summary reroll = GolfFinish.settle(round(12, List.of(), false, tag(LAYOUT_DAY, 1)), l);
        GolfFinish.Summary next = GolfFinish.settle(round(12, List.of(), false, tag(LAYOUT_DAY + 7, 0)), l);
        assertEquals(3, l.calls.stream().filter(c -> c.startsWith("whole DAILY_CLEAR " + ref + " 2 ")).count(),
                "the first finish, the next day's and the reroll ask all or nothing under one ref: " + l.calls);
        assertTrue(l.calls.contains("whole DAILY_CLEAR " + ref + " 2 Mini Golf: X first finish this week"),
                "in the set's words");
        assertEquals(5 + 2, first.earned(), "the very first finish and the set's first finish");
        assertEquals(0, nextDay.earned(), "a second finish a day later in the same set pays nothing");
        assertEquals(0, reroll.earned(), "a reroll of the set pays neither again");
        assertEquals(2, next.earned(), "the next set's first finish pays; the first clear doesn't");
        assertEquals(4, l.calls.stream().filter(c -> c.startsWith("pay FIRST_CLEAR first_clear:fresh_golf ")).count(),
                "one once-ever ref for every layout");
    }

    @Test
    void aRecalledRoundPaysUnderItsOriginalSetAndSlot() {
        Ledger l = new Ledger();
        GenTag original = tag(LAYOUT_DAY, 0);
        GenTag recalled = original.withRecall(new GenTag.Recall("fresh_classic_golf", 1L, LAYOUT_DAY + 14));
        l.paid.add(GenBoards.clearRef(original));
        GolfFinish.settle(new GolfFinish.Round("fresh_classic_golf", "Mini Golf: Classic", 8, 9, 3, true, List.of(2),
                CALENDAR_DAY, false, 5, 2, 1, 3, new GolfFinish.Daily(recalled, WEEK, 2, GOALS)), l);
        assertTrue(l.calls.contains("submit " + GenBoards.day(original) + " 8"), "the original board: " + l.calls);
        assertTrue(l.calls.stream().anyMatch(c -> c.startsWith("pay FIRST_CLEAR first_clear:fresh_golf ")),
                "the slot's first clear, never the Classics slot's");
        assertTrue(l.calls.stream().anyMatch(c -> c.startsWith("pay PAR par:fresh_golf:" + original.edition() + " ")),
                "par under the original set");
        assertTrue(l.calls.stream().anyMatch(c -> c.startsWith("stars gstars:fresh_classic_golf:")),
                "its own stars board");
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
        assertTrue(l.calls.contains("stars " + GenBoards.stars(tag(LAYOUT_DAY, 0)) + " gweek:" + WEEK + " 3"),
                "kept on the set's stars board and the round's week");
    }

    @Test
    void eachStarGoalReachedIsPaidItsOwnTokensOnce() {
        Ledger l = new Ledger();
        l.boards.put("gweek:" + WEEK, 9L);
        GolfFinish.Summary s = GolfFinish.settle(round(9, List.of(), false, tag(LAYOUT_DAY, 0)), l);
        assertTrue(l.calls.contains("goal ms:gweek:" + WEEK + ":10 1"), "9 to 12 crosses 10, which pays 1: " + l.calls);
        assertEquals(12, s.weekStars(), "the week after");
        GolfFinish.settle(round(9, List.of(), false, tag(LAYOUT_DAY + 7, 0)), l);
        assertEquals(1, l.paid.stream().filter(r -> r.startsWith("ms:gweek:")).count(),
                "12 to 15: the 10 is offered again but never paid twice");
        l.boards.put("gweek:" + WEEK, 23L);
        GolfFinish.settle(round(9, List.of(), false, tag(LAYOUT_DAY + 14, 0)), l);
        assertTrue(l.calls.contains("goal ms:gweek:" + WEEK + ":25 2"), "23 to 26 reaches 25, which pays its own 2");
    }

    @Test
    void aStarGoalTheCapsHeldBackIsPaidByTheNextRoundThatWeek() {
        boolean[] capped = {true};
        Ledger l = new Ledger() {
            @Override
            public int payGoal(String ref, int tokens, String detail) {
                calls.add("goal " + ref + " " + tokens);
                if (capped[0]) {
                    return 0; // today's caps are full: nothing paid, nothing recorded
                }
                return paid.add(ref) ? tokens : 0;
            }
        };
        l.boards.put("gweek:" + WEEK, 9L);
        GolfFinish.settle(round(9, List.of(), false, tag(LAYOUT_DAY, 0)), l);
        assertFalse(l.paid.contains("ms:gweek:" + WEEK + ":10"), "9 to 12 crosses 10 on a capped day: not paid");
        capped[0] = false;
        GolfFinish.settle(round(9, List.of(), false, tag(LAYOUT_DAY + 7, 0)), l);
        assertTrue(l.paid.contains("ms:gweek:" + WEEK + ":10"), "the next round that week pays it: " + l.calls);
    }
}
