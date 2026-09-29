package com.dierks.homecraft.games.gen.api;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fresh Courses' words (GEN-SPEC §7): every sign fits a sign on both editions (at most four lines
 * of fifteen plain ASCII characters), no line uses a word the Games never say or a character
 * Bedrock can't draw, and the checks really catch each of those.
 */
class GenCopyTest {

    @Test
    void everySignFitsASign() {
        for (List<String> sign : GenCopy.everySign()) {
            assertEquals(List.of(), GenCopy.signProblems(sign), "this sign reads on Java and Bedrock: " + sign);
        }
        assertEquals(List.of("EASY PARKOUR", "Hop to the", "GOLD pad!", "Blue = saved"), GenCopy.parkourStart("easy"),
                "the easy start sign, as the spec wrote it");
        assertEquals("HARD PARKOUR", GenCopy.parkourStart("HARD").get(0), "the tier is read in any case");
        assertEquals("PARKOUR", GenCopy.parkourStart(null).get(0), "and anything else is plain Parkour");
        assertEquals(List.of("HOLE 18", "Par 6", "Hit the ball", "to the flag!"), GenCopy.golfTee(18, 6),
                "the longest tee sign still fits");
    }

    @Test
    void noLineUsesABannedWordOrAGlyphBedrockLacks() {
        for (String line : GenCopy.everyLine()) {
            assertEquals(List.of(), GenCopy.copyProblems(line), "this line is fine for a child: " + line);
        }
        assertFalse(GenCopy.copyProblems("&eSo close! Try again").isEmpty(), "near-miss words are caught");
        assertFalse(GenCopy.copyProblems("&7You'll sink it next time").isEmpty(), "'sink' is caught");
        assertFalse(GenCopy.copyProblems("&7almost ready").isEmpty(), "'almost' is caught");
        assertFalse(GenCopy.copyProblems("&aLUCKY shot").isEmpty(), "in any case");
        assertTrue(GenCopy.copyProblems("&7Sunken cups and shots").isEmpty(), "a word that contains one is fine");
        assertFalse(GenCopy.copyProblems("Nice 🏆").isEmpty(), "an emoji is caught");
    }

    @Test
    void theSignCheckCatchesEachProblem() {
        assertFalse(GenCopy.signProblems(List.of("A line that is too long")).isEmpty(), "more than 15 characters");
        assertFalse(GenCopy.signProblems(List.of("a", "b", "c", "d", "e")).isEmpty(), "more than 4 lines");
        assertFalse(GenCopy.signProblems(List.of("café")).isEmpty(), "anything but plain ASCII");
        assertFalse(GenCopy.signProblems(List.of()).isEmpty(), "no lines at all");
        assertFalse(GenCopy.signProblems(java.util.Arrays.asList("ok", null)).isEmpty(), "a missing line");
        assertThrows(IllegalArgumentException.class,
                () -> new SignText(0, 0, 0, Palette.sign(0), List.of("This is far too long for a sign")),
                "a plan can't carry a sign a child can't read");
        assertThrows(IllegalArgumentException.class, () -> new SignText(0, 0, 0, " ", List.of("OK")),
                "nor one without its block");
        assertEquals(List.of("FINISH!"), new SignText(1, 2, 3, Palette.sign(4), GenCopy.finish()).lines(),
                "a good one is kept as written");
    }

    @Test
    void waitsReadLikeAClock() {
        assertEquals("11h 2m", GenCopy.span(11 * 3_600_000L + 2 * 60_000L + 59_000L), "hours and minutes");
        assertEquals("5m", GenCopy.span(5 * 60_000L), "minutes alone");
        assertEquals("less than a minute", GenCopy.span(59_000L), "under a minute");
        assertEquals("less than a minute", GenCopy.span(-5), "and never negative");
        assertEquals("&7New course in &f11h 2m", GenCopy.newIn(11 * 3_600_000L + 120_000L), "the tile's line");
        assertTrue(GenCopy.comingHere(1).contains("next 1 minute -"), "one minute is singular");
        assertTrue(GenCopy.timesUp("fresh_golf").endsWith("/hcm play fresh_golf"), "it says how to try the new one");
        assertEquals("6d 14h", GenCopy.span(6 * 86_400_000L + 14 * 3_600_000L + 59 * 60_000L),
                "a weekly wait is days and hours");
        assertEquals("1d 0h", GenCopy.span(86_400_000L), "a day is a day");
    }

    @Test
    void theWordsFollowTheCadence() {
        assertEquals("weekly", GenCopy.cadenceName(7), "7 is weekly");
        assertEquals("daily", GenCopy.cadenceName(1), "1 is daily");
        assertEquals("every 3 days", GenCopy.cadenceName(3), "anything else counts its days");
        assertEquals("This week's courses", GenCopy.current(7), "weekly");
        assertEquals("Today's courses", GenCopy.current(1), "daily");
        assertEquals("The current courses", GenCopy.current(3), "every few days");
        assertEquals("The current courses", GenCopy.current(14), "or every other week");
        assertEquals("New courses every Monday", GenCopy.schedule(7, DayOfWeek.MONDAY, "Mon 4:00 AM"),
                "weekly names the day");
        assertEquals("New courses every Thursday", GenCopy.schedule(7, DayOfWeek.THURSDAY, null),
                "the configured day");
        assertEquals("New courses every day", GenCopy.schedule(1, DayOfWeek.MONDAY, "Tue 4:00 AM"), "daily");
        assertEquals("New courses every 3 days - next Thu 4:00 AM", GenCopy.schedule(3, DayOfWeek.MONDAY,
                "Thu 4:00 AM"), "every N days says when next");
        assertEquals("New courses every 3 days", GenCopy.schedule(3, DayOfWeek.MONDAY, null), "or not, when not given");
        assertEquals("&eFresh Courses &7- new every Monday", GenCopy.tile(7, DayOfWeek.MONDAY), "the tile, weekly");
        assertEquals(GenCopy.TILE, GenCopy.tile(7, DayOfWeek.MONDAY), "the constant is the shipped tile");
        assertEquals("&eFresh Courses &7- new every day", GenCopy.tile(1, DayOfWeek.MONDAY), "daily");
        assertEquals("&eFresh Courses &7- new every 2 days", GenCopy.tile(2, DayOfWeek.MONDAY), "every 2 days");
        assertTrue(GenCopy.previous(7).contains("Last week's course"), "weekly: last week's");
        assertTrue(GenCopy.previous(1).contains("Yesterday's course"), "daily: yesterday's");
        assertEquals(GenCopy.YESTERDAY, GenCopy.previous(3), "otherwise: the last course");
        assertEquals("Golf of the Week", GenCopy.slotName(Slots.DAILY_GOLF, 7), "the big golf course, weekly");
        assertEquals("Golf of the Day", GenCopy.slotName(Slots.DAILY_GOLF, 1), "daily");
        assertEquals("Fresh Golf", GenCopy.slotName(Slots.DAILY_GOLF, 3), "every few days");
        assertEquals("Tiny Golf", GenCopy.slotName(Slots.TINY_GOLF, 1), "every other course keeps its name");
        ZoneId chicago = ZoneId.of("America/Chicago");
        long mon = java.time.LocalDateTime.of(2026, 10, 5, 4, 0).atZone(chicago).toInstant().toEpochMilli();
        assertEquals("Mon 4:00 AM", GenCopy.when(mon, chicago), "a moment");
        assertEquals("Mon 5 Oct 4:00 AM", GenCopy.whenDated(mon, chicago), "with its date, as status shows it");
        for (String line : GenCopy.everyLine()) {
            String plain = line.toLowerCase(java.util.Locale.ROOT);
            assertFalse(plain.contains("daily courses"), "the old name is gone: " + line);
        }
        assertFalse(GenCopy.TILE.toLowerCase(java.util.Locale.ROOT).contains("today"), "the tile doesn't say today");
        assertEquals("this week", GenCopy.when(7), "a set's lines end in the cadence's words: weekly");
        assertEquals("today", GenCopy.when(1), "daily");
        assertEquals("on this course", GenCopy.when(3), "any other cadence: each set is a new course");
        assertEquals("This week's best", GenCopy.bestOf(7), "a set's board");
        assertEquals("Your best this week", GenCopy.yourBest(7), "your best in it");
        assertEquals("First finish this week", GenCopy.firstFinish(7), "its first finish");
        assertEquals("first finish this week", GenCopy.firstFinishReason(7), "the ledger's words");
        assertEquals("first finish today", GenCopy.firstFinishReason(1), "daily");
        assertEquals("This week's times", GenCopy.times(7), "a set's board as a tile names it");
        assertEquals("(new this week)", GenCopy.newMark(7), "the mark on a current course");
        assertEquals("(last week's)", GenCopy.oldMark(7), "and on the last set's");
        assertEquals("(yesterday's)", GenCopy.oldMark(1), "daily");
        assertEquals("Hard Parkour - this week", GenCopy.boardTitle("Hard Parkour", 7), "a leaderboard display's title");
        assertEquals("Hard Parkour", GenCopy.boardTitle("Hard Parkour", 3), "or the name alone");
        assertEquals("&7You've reached today's token limit - finish it again another day this week for its tokens.",
                GenCopy.clearLimit(7), "the owner's words, weekly");
        assertEquals(GenCopy.GOAL_LIMIT, GenCopy.clearLimit(14), "every other week too");
        assertTrue(GenCopy.clearLimit(3).contains("before the courses change"), "a 3-day set: before the change");
        assertFalse(GenCopy.clearLimit(1).contains("another day"), "a daily set can't be finished another day");
        assertFalse(GenCopy.YESTERDAY.toLowerCase(java.util.Locale.ROOT).contains("yesterday"),
                "and the cadence-free line doesn't say yesterday");
    }
}
