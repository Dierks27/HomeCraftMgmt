package com.dierks.homecraft.games.chance.sportsbook;

import com.dierks.homecraft.storage.SportsPicksDao;
import com.dierks.homecraft.storage.SportsPicksDao.Pick;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.60.0, "how did we do" ({@link PickHistory}): the sums of a player's picks (made, won, lost, money in and back, up or
 * down overall and this week), and My picks' pages.
 */
class PickHistoryTest {

    static final ZoneId ZONE = ZoneId.of("America/Chicago");
    /** Friday 27 November 2026, noon in Chicago. */
    static final long NOW = Instant.parse("2026-11-27T18:00:00Z").toEpochMilli();
    static final long DAY = 86_400_000L;
    static final UUID ME = UUID.randomUUID();

    private static Pick pick(long id, String status, int stake, long paid, long settledAt) {
        return new Pick(id, ME, "T-" + id, "yes", stake, 500, stake * 200L, settledAt - DAY, status, settledAt, paid,
                0, settledAt - DAY, "nba", "A vs B", "A");
    }

    @Test
    void theSumsCountEveryPickAndOnlySettledOnesForUpOrDown() {
        long monday = PickHistory.weekStart(NOW, ZONE);
        assertEquals(Instant.parse("2026-11-23T06:00:00Z").toEpochMilli(), monday, "Monday, midnight in Chicago");
        List<Pick> picks = List.of(
                new Pick(9, ME, "T-9", "yes", 20, 500, 4000, NOW, SportsPicksDao.OPEN, 0, 0, 0, NOW + DAY, "nba",
                        "A vs B", "A"),
                pick(8, SportsPicksDao.WON, 10, 1800, NOW - DAY),          // this week: +$8
                pick(7, SportsPicksDao.LOST, 15, 0, NOW - 2 * DAY),         // this week: -$15
                pick(6, SportsPicksDao.VOID, 5, 500, NOW - 3 * DAY),        // this week: even
                pick(5, SportsPicksDao.WON, 10, 2500, NOW - 9 * DAY),       // last week: +$15
                pick(4, SportsPicksDao.LAPSED, 10, 1000, NOW - 10 * DAY));  // last week: even
        PickHistory.Summary all = PickHistory.of(picks, 0);
        assertEquals(6, all.made());
        assertEquals(1, all.open());
        assertEquals(5, all.settled());
        assertEquals(2, all.won());
        assertEquals(1, all.lost());
        assertEquals(2, all.back(), "called off, and no result");
        assertEquals(5000, all.inCents(), "$50 into the settled picks");
        assertEquals(5800, all.backCents(), "$58 back");
        assertEquals(800, all.net());
        assertEquals(2000, all.openCents(), "$20 waiting");
        assertEquals("up $8", PickHistory.upDown(all.net()));

        PickHistory.Summary week = PickHistory.of(picks, monday);
        assertEquals(3, week.made(), "the open pick isn't this week's result");
        assertEquals(0, week.open());
        assertEquals(-700, week.net(), "+$8, -$15, even");
        assertEquals("down $7", PickHistory.upDown(week.net()));
        assertEquals("even", PickHistory.upDown(0));

        List<String> lines = PickHistory.lines(all, week);
        assertEquals("&7Picks made: &f6 &7(1 waiting, &6$20 &7in)", lines.get(0));
        assertEquals("&7Won: &a2 &7- didn't win: &f1 &7- given back: &f2", lines.get(1));
        assertEquals("&7Money in: &6$50 &7- money back: &6$58", lines.get(2));
        assertEquals("&7Overall: &aup $8", lines.get(3));
        assertEquals("&7This week: &7down $7 &7(3 picks)", lines.get(4));
        Pattern banned = Pattern.compile("(?i)\\bbet\\b|wager|gambl|casino|\\bhouse\\b|\\blucky\\b|\\bhot\\b|\\bdue\\b"
                + "|big\\s+win|jackpot");
        for (String line : lines) {
            assertFalse(banned.matcher(line).find(), line);
        }
        PickHistory.Summary none = PickHistory.of(List.of(), 0);
        assertEquals(0, none.made());
        assertEquals(0, none.net());
    }

    @Test
    void picksArePagedThirtySixAtATimeNewestFirst() {
        List<Integer> all = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            all.add(i);
        }
        assertEquals(36, PickHistory.PER_PAGE, "rows 1-4 of the screen");
        assertEquals(3, PickHistory.pages(80, PickHistory.PER_PAGE));
        assertEquals(1, PickHistory.pages(0, PickHistory.PER_PAGE), "an empty history is one page");
        assertEquals(1, PickHistory.pages(36, PickHistory.PER_PAGE));
        assertEquals(2, PickHistory.pages(37, PickHistory.PER_PAGE));
        assertEquals(36, PickHistory.page(all, 0, 36).size());
        assertEquals(0, PickHistory.page(all, 0, 36).get(0));
        assertEquals(36, PickHistory.page(all, 1, 36).get(0));
        assertEquals(List.of(72, 73, 74, 75, 76, 77, 78, 79), PickHistory.page(all, 2, 36));
        assertEquals(PickHistory.page(all, 2, 36), PickHistory.page(all, 9, 36), "past the end: the last page");
        assertTrue(PickHistory.page(List.of(), 0, 36).isEmpty());
    }
}
