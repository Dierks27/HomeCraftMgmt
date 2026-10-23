package com.dierks.homecraft.arcade.looks;

import com.dierks.homecraft.arcade.looks.LookRules.Area;
import com.dierks.homecraft.arcade.looks.LookRules.Decision;
import com.dierks.homecraft.arcade.looks.LookRules.Found;
import com.dierks.homecraft.arcade.looks.LookRules.Key;
import com.dierks.homecraft.arcade.looks.LookRules.Notice;
import com.dierks.homecraft.arcade.looks.LookRules.Verdict;
import com.dierks.homecraft.arcade.looks.LookRules.Window;
import com.dierks.homecraft.config.PluginConfig.Prize;
import com.dierks.homecraft.storage.LookDao;
import com.dierks.homecraft.storage.LookDao.Look;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dierks.homecraft.arcade.looks.LookFixtures.DAY;
import static com.dierks.homecraft.arcade.looks.LookFixtures.row;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wardrobe's rules (SHOP-V2-SPEC §3.3, §3.4, §3.8, §3.10): the dates a rented look runs to, the buy-again
 * window and its price, which heads-up is due, and each key's verdict, all on plain values.
 */
class LookRulesTest {

    private static final ZoneId ZONE = ZoneId.of("America/Chicago");
    private static final UUID HER = UUID.fromString("0b0a0c0d-1e1f-4a2b-8c3d-4e5f6a7b8c9d");
    private static final UUID GIVER = UUID.fromString("1c2d3e4f-5a6b-4c7d-8e9f-0a1b2c3d4e5f");

    private static long at(int y, int m, int d, int hour) {
        return ZonedDateTime.of(y, m, d, hour, 0, 0, 0, ZONE).toInstant().toEpochMilli();
    }

    private static LocalDate date(long millis) {
        return Instant.ofEpochMilli(millis).atZone(ZONE).toLocalDate();
    }

    // ---- dates ----------------------------------------------------------------------------------

    @Test
    void aNinetyDayBuyRunsToALocalMidnight() {
        long now = at(2026, 10, 20, 15);
        LocalDate last = LookRules.newLastDay(null, 90, now, ZONE);
        assertEquals(LocalDate.of(2027, 1, 18), last, "20 Oct + 90 = until 18 Jan");
        long end = LookRules.endOf(last, ZONE);
        assertEquals(at(2027, 1, 19, 0), end, "it ends 19 Jan 00:00 local");
        assertEquals(LocalDate.of(2027, 1, 18), LookRules.lastDay(end, ZONE), "an end at midnight is the day before");
        assertEquals("18 Jan", LookRules.until(last));
    }

    @Test
    void aRenewalAddsToTheLastDay() {
        Look live = row("cap_lime", LookDao.HAT, at(2027, 1, 19, 0), at(2026, 10, 20, 15));
        long renewOn = at(2027, 1, 15, 10);
        assertEquals(LocalDate.of(2027, 4, 18), LookRules.newLastDay(live, 90, renewOn, ZONE),
                "a renewal on 15 Jan: until 18 Apr");
        assertEquals(at(2026, 10, 20, 15), LookRules.since(live, renewOn), "a renewal keeps the run's start");
    }

    @Test
    void aBuyAfterTheEndStartsToday() {
        Look ended = row("cap_lime", LookDao.HAT, at(2027, 1, 19, 0), at(2026, 10, 20, 15));
        long tenDaysOn = at(2027, 1, 29, 9);
        assertEquals(LocalDate.of(2027, 1, 29).plusDays(90), LookRules.newLastDay(ended, 90, tenDaysOn, ZONE),
                "10 days after the end: a new run from today");
        assertEquals(tenDaysOn, LookRules.since(ended, tenDaysOn), "and a new start");
        assertEquals(tenDaysOn, LookRules.since(null, tenDaysOn));
        Look old = row("trail_hearts", LookDao.TRAIL, at(2027, 1, 19, 0), 0);
        assertEquals(at(2027, 1, 10, 0), LookRules.since(old, at(2027, 1, 10, 0)), "a live row from before v37 starts now");
    }

    @Test
    void anEndAcrossEitherClockChangeIsExactlyLocalMidnight() {
        // Spring forward in Chicago on 14 Mar 2027, fall back on 1 Nov 2026.
        long spring = LookRules.endOf(LookRules.newLastDay(null, 90, at(2026, 12, 15, 20), ZONE), ZONE);
        ZonedDateTime s = Instant.ofEpochMilli(spring).atZone(ZONE);
        assertEquals(LocalDate.of(2027, 3, 16), s.toLocalDate(), "until 15 Mar, ends 16 Mar");
        assertEquals(LocalTime.MIDNIGHT, s.toLocalTime(), "local midnight after the spring change");
        long autumn = LookRules.endOf(LookRules.newLastDay(null, 90, at(2026, 8, 5, 12), ZONE), ZONE);
        ZonedDateTime a = Instant.ofEpochMilli(autumn).atZone(ZONE);
        assertEquals(LocalDate.of(2026, 11, 4), a.toLocalDate());
        assertEquals(LocalTime.MIDNIGHT, a.toLocalTime(), "local midnight after the autumn change");
        long day = LookRules.endOf(LocalDate.of(2026, 11, 1), ZONE) - LookRules.endOf(LocalDate.of(2026, 10, 31), ZONE);
        assertEquals(25 * 3_600_000L, day, "1 Nov is 25 hours long there: a date has no length, millis do");
    }

    @Test
    void daysLeftRoundUp() {
        assertEquals(6, LookRules.daysLeft(10 * DAY, 4 * DAY + 1));
        assertEquals(1, LookRules.daysLeft(10 * DAY, 10 * DAY - 1));
        assertEquals(0, LookRules.daysLeft(10 * DAY, 10 * DAY));
        assertEquals("1 day", LookRules.days(1));
        assertEquals("6 days", LookRules.days(6));
    }

    // ---- the window and the price --------------------------------------------------------------

    @Test
    void theWindowAtItsEdges() {
        long end = at(2027, 1, 19, 0);
        Look r = row("cap_lime", LookDao.HAT, end, 1);
        assertEquals(Window.NEW, LookRules.window(null, end), "never had it");
        assertEquals(Window.LIVE, LookRules.window(r, end - 7 * DAY - 1), "7 days + 1 ms left: a buy is refused");
        assertEquals(Window.OPEN, LookRules.window(r, end - 7 * DAY), "7 days left: buy again opens");
        assertEquals(Window.OPEN, LookRules.window(r, end), "the end");
        assertEquals(Window.OPEN, LookRules.window(r, end + 30 * DAY), "30 days after: still open");
        assertEquals(Window.NEW, LookRules.window(r, end + 30 * DAY + 1), "30 days + 1 ms: a first buy again");
        Look kept = row("trail_leaves_2026", LookDao.TRAIL, end + 3651 * DAY, 1);
        assertEquals(Window.KEPT, LookRules.window(kept, end), "3,651 days left: kept for good");
    }

    @Test
    void thePricePerWindow() {
        Prize cheap = LookFixtures.cap("cap_lime", "&aLime Cap", 60, 40);
        Prize same = LookFixtures.cap("cap_pink", "&dPink Cap", 60, 0);
        assertEquals(60, LookRules.price(cheap, Window.NEW));
        assertEquals(40, LookRules.price(cheap, Window.OPEN), "a hat row's renew_tokens is its buy-again price");
        assertEquals(60, LookRules.price(same, Window.OPEN), "no renew_tokens: the same price (D6)");
        Prize snow = LookFixtures.trail("trail_snow", "&fSnow Trail", 100, 30);
        assertEquals(100, LookRules.price(snow, Window.OPEN),
                "a trail never reads renew_tokens: a crate's free day opens a window at the full price (D-m15)");
    }

    @Test
    void theRefusalsSayWhen() {
        Look live = row("cap_lime", LookDao.HAT, at(2027, 1, 19, 0), 1);
        assertEquals("You have it until 18 Jan. You can buy it again from 12 Jan.",
                LookRules.refusal(Window.LIVE, live, ZONE));
        assertEquals("You have that for good.", LookRules.refusal(Window.KEPT, live, ZONE));
        assertNull(LookRules.refusal(Window.OPEN, live, ZONE));
        assertEquals(LocalDate.of(2027, 2, 17), LookRules.windowLastDay(live.expiresAt(), ZONE),
                "until 18 Jan: buy again until 17 Feb");
    }

    // ---- heads-up -----------------------------------------------------------------------------------

    @Test
    void theNoticeTableRowByRow() {
        long since = 0;
        long end = 100 * DAY;
        long start = end - 90 * DAY;
        assertEquals(Notice.NONE, LookRules.due(start, end, end - 8 * DAY, 0, 0), "more than a week left");
        assertEquals(Notice.SEVEN_DAYS, LookRules.due(start, end, end - 7 * DAY, 0, 0));
        assertEquals(Notice.NONE, LookRules.due(start, end, end - 3 * DAY, 7, end), "the 7-day one went");
        assertEquals(Notice.ONE_DAY, LookRules.due(start, end, end - DAY, 7, end));
        assertEquals(Notice.NONE, LookRules.due(start, end, end - 1, 1, end), "the 1-day one went");
        assertEquals(Notice.ENDED, LookRules.due(start, end, end, 1, end));
        assertEquals(Notice.NONE, LookRules.due(start, end, end + DAY, -1, end), "ENDED never twice");
        assertEquals(Notice.NONE, LookRules.due(since, end, end - DAY, 0, 0), "a row from before v37: no heads-up");
        assertEquals(Notice.ENDED, LookRules.due(since, end, end + 1, 0, 0), "only its end");
        assertEquals(Notice.NONE, LookRules.due(since, end, end + 31 * DAY, 0, 0),
                "an end long past is history: the first 0.38 join lists none");
        assertEquals(7, LookRules.stage(Notice.SEVEN_DAYS));
        assertEquals(1, LookRules.stage(Notice.ONE_DAY));
        assertEquals(-1, LookRules.stage(Notice.ENDED));
    }

    @Test
    void anOfflinePlayerAcrossBothThresholdsGetsOnlyTheOneDay() {
        long end = 100 * DAY;
        assertEquals(Notice.ONE_DAY, LookRules.due(end - 90 * DAY, end, end - DAY / 2, 0, 0));
        assertEquals(Notice.NONE, LookRules.due(end - 90 * DAY, end, end - DAY / 4, 1, end),
                "and after it, not the 7-day one");
    }

    @Test
    void aCratesOneDayTrailGetsOnlyItsEndAsOneChatLine() {
        long since = 50 * DAY;
        long end = since + DAY;
        assertEquals(Notice.NONE, LookRules.due(since, end, since + 1, 0, 0), "no 1-day heads-up on a 1-day run");
        assertEquals(Notice.ENDED, LookRules.due(since, end, end, 0, 0));
        assertTrue(LookRules.shortRun(since, end));
        assertNull(LookRules.title(Notice.ENDED, "Heart Trail", 0, true), "no title (red team O10)");
        assertEquals("&7Your 1-day Heart Trail is over.",
                LookRules.line(Notice.ENDED, "Heart Trail", LocalDate.of(2026, 10, 21), true, 100,
                        LocalDate.of(2026, 11, 20), null), "one chat line, no price");
        assertFalse(LookRules.shortRun(0, DAY), "a row from before v37 isn't called a 1-day run...");
        assertTrue(LookRules.quietEnd(0, DAY), "...but its end is quiet all the same (red-team finding F1)");
        assertTrue(LookRules.quietEnd(since, end), "a crate's day is quiet");
        assertFalse(LookRules.quietEnd(since, since + 30 * DAY), "a bought run is not");
    }

    /**
     * Red-team finding F1: a trail from before v37 ({@code since} 0) can't be told from a 0.37 crate's day (both were
     * written by the same upsert), so its end is the quiet one, as 0.37 itself ended every trail: one chat line, no
     * title, no price and no [Buy again]. A crate trail pulled the day before the upgrade no longer ends mid-play
     * with "went back to the shop" and a 100-token pitch.
     */
    @Test
    void aRowFromBeforeV37EndsQuietly() {
        long end = 200 * DAY;
        Look old = new Look("trail_sparkles", LookDao.TRAIL, end, 0, true, 0, 0, "");
        assertEquals(Notice.ENDED, LookRules.due(old.since(), end, end + 1, 0, 0), "its end is still said");
        assertNull(LookRules.titleFor(List.of(new LookRules.Due(old, Notice.ENDED)), end + 1, id -> "Sparkle Trail"),
                "with no title");
        assertNull(LookRules.title(Notice.ENDED, "Sparkle Trail", 0, LookRules.quietEnd(0, end)));
        LocalDate last = LookRules.lastDay(end, ZONE);
        assertEquals("&7Your Sparkle Trail has ended.",
                LookRules.line(Notice.ENDED, "Sparkle Trail", last, LookRules.shortRun(0, end), -1, last, null),
                "one line, no price (the service offers none on a quiet end)");
        assertEquals("&7Your Heart Trail ended - your Autumn Leaves 2026 are on now.",
                LookRules.line(Notice.ENDED, "Heart Trail", last, false, -1, last, "Autumn Leaves 2026"),
                "something else came on (red team O7), still with no price");
        Look bought = new Look("trail_hearts", LookDao.TRAIL, end, end - 30 * DAY, true, 0, 0, "");
        assertArrayEquals(new String[] {"&6Your Heart Trail went back to the shop", "&7Get it again in My Looks"},
                LookRules.titleFor(List.of(new LookRules.Due(bought, Notice.ENDED)), end + 1, id -> "Heart Trail"),
                "a 0.38 run of 30 days keeps its title");
    }

    /**
     * Red-team finding F2: the title for several heads-ups counts only those with a title of their own, so two crate
     * trails ending together bring no "2 looks have ended" up, and a quiet end never counts as one that "ends soon".
     */
    @Test
    void quietEndsBringNoTitleUpTogether() {
        long now = 300 * DAY;
        LookRules.Due heart = new LookRules.Due(new Look("trail_hearts", LookDao.TRAIL, now - 1, now - 1 - DAY, false,
                0, 0, ""), Notice.ENDED);
        LookRules.Due snow = new LookRules.Due(new Look("trail_snow", LookDao.TRAIL, now - 2, now - 2 - DAY, false,
                0, 0, ""), Notice.ENDED);
        LookRules.Due old = new LookRules.Due(new Look("trail_notes", LookDao.TRAIL, now - 3, 0, false, 0, 0, ""),
                Notice.ENDED);
        LookRules.Due cap = new LookRules.Due(new Look("cap_lime", LookDao.HAT, now + 6 * DAY, now - 84 * DAY, false,
                0, 0, ""), Notice.SEVEN_DAYS);
        LookRules.Due bunny = new LookRules.Due(new Look("hat_bunny", LookDao.HAT, now - 4, now - 4 - 90 * DAY, false,
                0, 0, ""), Notice.ENDED);
        java.util.function.Function<String, String> name = id -> switch (id) {
            case "cap_lime" -> "Lime Cap";
            case "hat_bunny" -> "Bunny Hat";
            default -> id;
        };
        assertNull(LookRules.titleFor(List.of(heart, snow), now, name), "two crate days: no title");
        assertNull(LookRules.titleFor(List.of(heart, old), now, name), "a crate day and a 0.37 row: no title");
        assertArrayEquals(new String[] {"&eYour Lime Cap: 6 days left", "&7Buy it again in My Looks"},
                LookRules.titleFor(List.of(heart, cap), now, name),
                "a quiet end and a real heads-up: the real one's own title, not \"2 looks end soon\"");
        assertArrayEquals(new String[] {"&6Your Bunny Hat went back to the shop", "&7Get it again in My Looks"},
                LookRules.titleFor(List.of(snow, bunny), now, name));
        assertArrayEquals(new String[] {"&e2 looks end soon", "&7Look in My Looks"},
                LookRules.titleFor(List.of(heart, cap, bunny), now, name), "two with titles of their own");
        assertArrayEquals(new String[] {"&62 looks have ended", "&7Look in My Looks"},
                LookRules.titleFor(List.of(bunny, new LookRules.Due(new Look("hat_fox", LookDao.HAT, now - 5,
                        now - 5 - 90 * DAY, false, 0, 0, ""), Notice.ENDED), snow), now, name));
        assertNull(LookRules.titleFor(List.of(), now, name));
    }

    @Test
    void aNewEndResetsWhatWasSent() {
        long end = 100 * DAY;
        long renewed = end + 90 * DAY;
        assertEquals(Notice.NONE, LookRules.due(end - 90 * DAY, end, end - 2 * DAY, 7, end));
        assertEquals(Notice.SEVEN_DAYS, LookRules.due(end - 90 * DAY, renewed, renewed - 6 * DAY, 7, end),
                "the 7-day one went for the old end: the new end gets its own");
    }

    @Test
    void theWordsOfEachNotice() {
        LocalDate last = LocalDate.of(2027, 1, 18);
        assertArrayEquals(new String[] {"&eYour Lime Cap: 5 days left", "&7Buy it again in My Looks"},
                LookRules.title(Notice.SEVEN_DAYS, "Lime Cap", 5, false));
        assertArrayEquals(new String[] {"&eLast day for your Lime Cap!", "&7Buy it again in My Looks"},
                LookRules.title(Notice.ONE_DAY, "Lime Cap", 1, false));
        assertArrayEquals(new String[] {"&6Your Lime Cap went back to the shop", "&7Get it again in My Looks"},
                LookRules.title(Notice.ENDED, "Lime Cap", 0, false));
        assertArrayEquals(new String[] {"&e2 looks end soon", "&7Look in My Looks"}, LookRules.severalTitle(2, false));
        assertArrayEquals(new String[] {"&62 looks have ended", "&7Look in My Looks"}, LookRules.severalTitle(2, true));
        assertEquals("&eYour Lime Cap ends on 18 Jan.",
                LookRules.line(Notice.SEVEN_DAYS, "Lime Cap", last, false, 60, last, null));
        assertEquals("&eYour Lime Cap ends tonight.", LookRules.line(Notice.ONE_DAY, "Lime Cap", last, false, 60, last,
                null));
        assertEquals("&7Your Lime Cap has ended. Buy it again for 60 tokens until 17 Feb (My Looks).",
                LookRules.line(Notice.ENDED, "Lime Cap", last, false, 60, LocalDate.of(2027, 2, 17), null));
        assertEquals("&7Your Heart Trail ended - your Autumn Leaves 2026 are on now. Buy it again for 100 tokens until"
                        + " 17 Feb (My Looks).",
                LookRules.line(Notice.ENDED, "Heart Trail", last, false, 100, LocalDate.of(2027, 2, 17),
                        "Autumn Leaves 2026"), "something else came on (red team O7)");
        assertEquals("&7Your Heart Trail ended - your Twinkle 2026 is on now.",
                LookRules.line(Notice.ENDED, "Heart Trail", last, false, -1, last, "Twinkle 2026"),
                "off the shelf: no price to offer");
        for (Notice n : Notice.values()) {
            String line = LookRules.line(n, "Lime Cap", last, false, 60, last, null);
            if (line != null) {
                assertFalse(line.toLowerCase().contains("time is up") || line.contains("for 30 days"),
                        "the words don't scold, and never read as the next run's length (red team O8): " + line);
            }
        }
    }

    @Test
    void aPresentsWeekLeftAlsoTellsItsGiver() {
        assertEquals("&eThe Lime Cap you gave Kid ends on 18 Jan.",
                LookRules.giverLine("Lime Cap", "Kid", LocalDate.of(2027, 1, 18)));
        Look present = new Look("cap_lime", LookDao.HAT, 100 * DAY, 10 * DAY, false, 0, 0, GIVER.toString());
        List<LookRules.Due> due = LookRules.dueNow(true, List.of(present), 93 * DAY);
        assertEquals(1, due.size());
        assertEquals(Notice.SEVEN_DAYS, due.get(0).notice());
        assertEquals(GIVER.toString(), due.get(0).row().givenBy(), "the giver's line goes to whoever paid");
    }

    /**
     * Red-team finding F1-GIVER-SKIPPED: the giver heard only with the receiver's 7-day notice, so a receiver who didn't
     * come by that week meant the giver never heard. Whichever of the receiver's notices comes first after it brings
     * the line instead, once a run.
     */
    @Test
    void aGiverHearsEvenWhenTheReceiverSkippedTheWeek() {
        Look present = new Look("cap_lime", LookDao.HAT, 100 * DAY, 10 * DAY, false, 0, 0, GIVER.toString());
        assertTrue(LookRules.giverHears(LookRules.dueNow(true, List.of(present), 93 * DAY).get(0)), "a week before");
        LookRules.Due lastDay = LookRules.dueNow(true, List.of(present), 99 * DAY + 1).get(0);
        assertEquals(Notice.ONE_DAY, lastDay.notice());
        assertTrue(LookRules.giverHears(lastDay), "[X] she came by only on its last day: the giver still hears");
        LookRules.Due ended = LookRules.dueNow(true, List.of(present), 101 * DAY).get(0);
        assertEquals(Notice.ENDED, ended.notice());
        assertTrue(LookRules.giverHears(ended), "[X] or only after the end");
        Look weekSent = new Look("cap_lime", LookDao.HAT, 100 * DAY, 10 * DAY, false, 100 * DAY, 7, GIVER.toString());
        assertFalse(LookRules.giverHears(LookRules.dueNow(true, List.of(weekSent), 99 * DAY + 1).get(0)),
                "the giver heard with the week's notice: once a run");
        Look daySent = new Look("cap_lime", LookDao.HAT, 100 * DAY, 10 * DAY, false, 100 * DAY, 1, GIVER.toString());
        assertFalse(LookRules.giverHears(LookRules.dueNow(true, List.of(daySent), 101 * DAY).get(0)),
                "the end after a 1-day notice that brought it");
        Look shortRun = new Look("cap_lime", LookDao.HAT, 100 * DAY, 95 * DAY, false, 0, 0, GIVER.toString());
        assertFalse(LookRules.giverHears(LookRules.dueNow(true, List.of(shortRun), 99 * DAY + 1).get(0)),
                "a run too short for a week's notice brings none");
        assertEquals("&eThe Lime Cap you gave Kid ended on 18 Jan.",
                LookRules.giverLine("Lime Cap", "Kid", LocalDate.of(2027, 1, 18), true));
    }

    /**
     * Red-team finding WARD-1: an offline giver's line lived only in memory, and the receiver's stage was saved
     * first, so a restart before the giver's next join lost it for good. It is kept in {@code game_prefs} as text
     * that reads back the same; one line per look and receiver, oldest first, at most twenty.
     */
    @Test
    void aGiversWaitingLinesSurviveAsText() {
        String line = LookRules.giverLine("Lime Cap", "Kid", LocalDate.of(2027, 1, 18));
        String stored = LookRules.withGiverLine(null, "cap_lime", "Kid", line);
        List<String[]> back = LookRules.giverLines(stored);
        assertEquals(1, back.size());
        assertEquals(List.of("cap_lime", "Kid", line), List.of(back.get(0)), "read back after a restart");
        stored = LookRules.withGiverLine(stored, "hat_dragon", "Kid", "&eThe Dragon Hat you gave Kid ends on 2 Feb.");
        stored = LookRules.withGiverLine(stored, "cap_lime", "kid", "&eThe Lime Cap you gave Kid ends on 9 Mar.");
        back = LookRules.giverLines(stored);
        assertEquals(2, back.size(), "a newer line for the same look and receiver replaces the old one");
        assertEquals("hat_dragon", back.get(0)[0]);
        assertEquals("&eThe Lime Cap you gave Kid ends on 9 Mar.", back.get(1)[2]);
        for (int i = 0; i < 30; i++) {
            stored = LookRules.withGiverLine(stored, "trail_" + i, "Kid", "line " + i);
        }
        back = LookRules.giverLines(stored);
        assertEquals(20, back.size(), "at most twenty wait");
        assertEquals("trail_29", back.get(19)[0], "the newest kept");
        assertEquals(List.of(), LookRules.giverLines(""), "nothing waiting");
        assertEquals(List.of(), LookRules.giverLines("broken"), "a broken row is skipped");
        assertEquals("a b", LookRules.giverLines(LookRules.withGiverLine(null, "x", "y", "a\tb")).get(0)[2],
                "a tab in a line can't split it");
    }

    // ---- keys -----------------------------------------------------------------------------------

    private static Key key(String id, UUID to, long end) {
        return new Key(id, to, end, null);
    }

    @Test
    void eachKeysVerdict() {
        long end = 100 * DAY;
        Look right = row("cap_lime", LookDao.HAT, end, 1);
        long now = 50 * DAY;
        assertEquals(Verdict.KEEP, LookRules.verdict(false, HER, GIVER, null, now, false, 0, null, null).verdict(),
                "the rights didn't load: never delete on an unknown answer");
        assertEquals(Verdict.REMOVE, LookRules.verdict(true, HER, GIVER, right, now, false, end, null, null).verdict(),
                "someone else's key");
        assertEquals("not yours", LookRules.verdict(true, HER, GIVER, right, now, false, end, null, null).reason());
        assertEquals(Verdict.REMOVE, LookRules.verdict(true, HER, null, right, now, false, end, null, null).verdict(),
                "a binding that isn't a UUID binds to nobody");
        assertEquals("no right", LookRules.verdict(true, HER, HER, null, now, false, end, null, null).reason());
        assertEquals("ended", LookRules.verdict(true, HER, HER, right, end, false, end, null, null).reason());
        assertEquals(Verdict.REFRESH, LookRules.verdict(true, HER, HER, right, now, false, end - 90 * DAY, null, null)
                .verdict(), "a name written for another end: rewritten in place");
        assertEquals(Verdict.KEEP, LookRules.verdict(true, HER, HER, right, now, false, end, null, null).verdict());
        assertEquals(Verdict.KEEP, LookRules.verdict(true, HER, HER, right, now, true, 0, null, null).verdict(),
                "a Keeper carries no end");
        assertEquals(Verdict.REFRESH, LookRules.verdict(true, HER, HER, right, now, true, 0, "default", "fallback")
                .verdict(), "a Keeper of another look than the config's (§5.2)");
    }

    @Test
    void twoLiveCopiesOfYourOwnKeepOneTheWornOne() {
        long end = 100 * DAY;
        Map<String, Look> rights = Map.of("cap_lime", row("cap_lime", LookDao.HAT, end, 1));
        Found bag = new Found(Area.BAG, 4, List.of(), key("cap_lime", HER, end));
        Found worn = new Found(Area.HEAD, 0, List.of(), key("cap_lime", HER, end));
        Found ender = new Found(Area.ENDER_CHEST, 0, List.of(), key("cap_lime", HER, end));
        List<Decision> plan = LookRules.plan(true, HER, rights, 50 * DAY, List.of(bag, ender, worn), id -> null);
        assertEquals(3, plan.size());
        for (Decision d : plan) {
            assertEquals(d.found() == worn ? Verdict.KEEP : Verdict.REMOVE, d.verdict(), d.found().area().name());
            if (d.found() != worn) {
                assertEquals("a second key", d.reason());
            }
        }
        List<Decision> none = LookRules.plan(true, HER, rights, 50 * DAY, List.of(ender, bag), id -> null);
        assertEquals(Verdict.KEEP, none.stream().filter(d -> d.found() == bag).findFirst().orElseThrow().verdict(),
                "none worn: the first in slot order, the bag before the ender chest");
        List<Decision> unknown = LookRules.plan(false, HER, null, 50 * DAY, List.of(bag, worn), id -> null);
        assertTrue(unknown.stream().allMatch(d -> d.verdict() == Verdict.KEEP), "not loaded: not even a copy goes");
    }

    @Test
    void aGrandfatheredHatIsNeverAKey() {
        assertFalse(LookRules.isKeyOf(null, "hat_bunny", HER, "hat_bunny"),
                "a Bunny Hat from before 0.38 (same id, no bound_to) is never renamed, taken off or deleted");
        assertTrue(LookRules.isKeyOf(HER, "hat_bunny", HER, "hat_bunny"));
        assertTrue(LookRules.isKeyOf(HER, "HAT_BUNNY", HER, "hat_bunny"));
        assertFalse(LookRules.isKeyOf(GIVER, "hat_bunny", HER, "hat_bunny"), "someone else's key isn't hers");
        assertFalse(LookRules.isKeyOf(HER, "cap_lime", HER, "hat_bunny"), "another look");
        // The sweep only judges items that carry a binding: a grandfathered hat is never found.
        List<Found> found = LookRules.find(Area.HEAD, 0, "old bunny hat", s -> null, (s, d) -> java.util.Optional.of(List.of()));
        assertTrue(found.isEmpty());
    }
}
