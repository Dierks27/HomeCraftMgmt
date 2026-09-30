package com.dierks.homecraft.gui.games.daily;

import com.dierks.homecraft.games.GeneratedCourses;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.DailyStars;
import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.storage.GamesDao;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Fresh Courses' words and the few sums behind them (GEN-SPEC §5.2-§5.4, §7, the weekly
 * addendum §2), with no server.
 *
 * <p>Pinned here: every line is kid-safe and drawable on Bedrock; the words follow the cadence
 * ("this week" as shipped, "today" when daily, "on this course" otherwise) and never say "today"
 * or "daily" on a weekly set; a set's dates read as its first and last day; star times read in
 * whole seconds and the finish line leads with the stars, then the next one, then the week (the
 * spec's own example); golf star lines follow par; the Star Chart's lines with each goal's own
 * tokens; the quests' and achievements' sums (a first finish in a set, the week's top goal reached,
 * a whole set finished); and the editors' "within 16 blocks of a Fresh Courses half" check finds a
 * point on every side of a half and no further.
 */
class DailyTextTest {

    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");

    private static long at(int month, int day, int hour, int minute) {
        return LocalDateTime.of(2026, month, day, hour, minute).atZone(CHICAGO).toInstant().toEpochMilli();
    }

    @Test
    void everyLineIsKidSafeAndDrawableOnBedrock() {
        for (String line : DailyText.everyLine()) {
            assertTrue(line.codePoints().allMatch(cp -> cp <= 0xFFFF), "Bedrock draws every glyph of: " + line);
            assertEquals(List.of(), GenCopy.copyProblems(line), "kid-safe words: " + line);
        }
    }

    @Test
    void aSetReadsAsItsDays() {
        assertEquals("today", DailyText.dayText(20_725, 20_725), "today reads as today");
        assertEquals("Mon 28 Sep", DailyText.dayText(20_724, 20_725), "any other day by its date");
        assertEquals("Mon 28 Sep-Sun 4 Oct", DailyText.setDates(7, 20_724), "a week: its first and last day");
        assertEquals("Tue 29 Sep", DailyText.setDates(1, 20_725), "a day: its date");
        assertEquals("Mon 28 Sep-Wed 30 Sep", DailyText.setDates(3, 20_724), "three days");
    }

    @Test
    void theWordsFollowTheCadence() {
        assertEquals("&7This week's best: &f0:58.1 &7by &fAlex", DailyText.setBest(7, "0:58.1", "Alex", false),
                "weekly: this week's best");
        assertEquals("&7Today's best: &f0:58.1 &7(yours)", DailyText.setBest(1, "0:58.1", null, true),
                "daily: today's");
        assertEquals("&7Best on this course: &f0:58.1 &7by &fAlex", DailyText.setBest(3, "0:58.1", "Alex", false),
                "every 3 days: on this course");
        assertEquals("&7No one has finished it this week - be the first!", DailyText.setBest(7, null, null, false),
                "nobody yet this week");
        assertEquals("&7No one has finished it yet - be the first!", DailyText.setBest(3, null, null, false),
                "nobody yet on this course");
        assertEquals("&7Your best this week: &f1:02.3", DailyText.yourBest(7, "1:02.3"), "your best this week");
        assertEquals("&7You haven't finished it today.", DailyText.yourBest(1, null), "daily: not today");
        assertEquals("&e★ Your first finish this week!", DailyText.newBest(7, null), "the first finish this week");
        assertEquals("&e★ Your best this week! &7(was 1:02.3)", DailyText.newBest(7, "1:02.3"), "a better one");
        assertEquals("&7First finish this week: &6+2 tokens", DailyText.firstFinish(7, 2, false), "the set's reward");
        assertEquals("&a✔ First finish today done", DailyText.firstFinish(1, 2, true), "once had, daily");
        assertEquals("&7First finish on this course: &6+1 token", DailyText.firstFinish(3, 1, false), "every 3 days");
        assertNull(DailyText.firstFinish(7, 0, false), "nothing to pay: nothing said");
        assertEquals("&eYour stars this week: &6★★☆", DailyText.starsNow(7, 2), "your stars this week");
        assertEquals("&7No stars today yet - finish it for ★", DailyText.starsNow(1, 0), "none yet today");
        assertEquals("&aEasy Parkour &7- ★★☆ &a(new this week)",
                DailyText.tabName(Slots.DAILY_PARKOUR_EASY, "Easy Parkour", DailyText.trialFact(7, 2), true, 7),
                "a Fresh tile on the Courses tab is marked new this week");
        assertEquals("&aEasy Parkour &7- no time this week &8(last week's)",
                DailyText.tabName(Slots.DAILY_PARKOUR_EASY, "Easy Parkour", DailyText.trialFact(7, 0), false, 7),
                "until this week's is up, last week's says so");
        assertEquals("&aEasy Parkour &7- no time today &a(new today)",
                DailyText.tabName(Slots.DAILY_PARKOUR_EASY, "Easy Parkour", DailyText.trialFact(1, 0), true, 1),
                "daily: new today");
        assertEquals("&aEasy Parkour &7- ★☆☆ &8(the last one)",
                DailyText.tabName(Slots.DAILY_PARKOUR_EASY, "Easy Parkour", DailyText.trialFact(3, 1), false, 3),
                "every 3 days: the last one");
        for (String line : DailyText.everyLine()) {
            if (line.contains("today") || line.contains("Today")) {
                continue;
            }
            assertFalse(line.toLowerCase(java.util.Locale.ROOT).contains("daily"), "never 'daily': " + line);
        }
        for (int cadence : new int[]{3, 7}) {
            for (String line : List.of(DailyText.setBest(cadence, null, null, false),
                    DailyText.setBest(cadence, "1", "a", false), DailyText.yourBest(cadence, null),
                    DailyText.newBest(cadence, null), DailyText.firstFinish(cadence, 1, false),
                    DailyText.starsNow(cadence, 0), DailyText.trialFact(cadence, 0))) {
                assertFalse(line.toLowerCase(java.util.Locale.ROOT).contains("today"),
                        "a set of " + cadence + " days never says today: " + line);
            }
        }
    }

    @Test
    void aRecalledCoursesOldRecordsAreNeverCalledThisWeeksOrTodays() {
        GenTag hard = new GenTag("fresh_parkour_hard", "parkour", 1, 20_724, 0, 1L, 'A', "a", 1, 2, 3, List.of(),
                List.of(), 1L, 7);
        GenTag recalled = hard.withRecall(new GenTag.Recall("fresh_classic_parkour", 1L, 20_759));
        assertEquals(7, GenCopy.words(hard), "a weekly set's own course: its week's words");
        assertEquals(GenCopy.CLASSIC, GenCopy.words(recalled),
                "HARD-40 five weeks later in Classic Parkour: its board still holds that week's times");
        assertEquals(1, GenCopy.words(new GenTag("fresh_golf", "golf", 1, 20_724, 0, 2L, 'A', "b", 0, 0, 0, List.of(),
                List.of(), 1L, 1)), "a daily set: today's");
        assertEquals(7, GenCopy.words(null), "no tag: weekly");
        int c = GenCopy.CLASSIC;
        assertEquals("&7Best on this course: &f0:58.1 &7by &fAlex", DailyText.setBest(c, "0:58.1", "Alex", false),
                "the record to beat, not 'This week's best'");
        assertEquals("&7Your best on this course: &f1:02.3", DailyText.yourBest(c, "1:02.3"), "the player's old time");
        assertEquals("&a✔ First finish on this course done", DailyText.firstFinish(c, 4, true),
                "cleared back then: done, not 'this week'");
        assertEquals("Best on this course", GenCopy.bestOf(c), "'★ Best on this course time!' when it is beaten");
        for (String line : List.of(DailyText.setBest(c, null, null, false), DailyText.setBest(c, "1", "a", true),
                DailyText.yourBest(c, null), DailyText.yourBest(c, "1"), DailyText.newBest(c, null),
                DailyText.newBest(c, "1"), DailyText.firstFinish(c, 1, false), DailyText.firstFinish(c, 1, true),
                DailyText.starsNow(c, 0), DailyText.starsNow(c, 2), DailyText.trialFact(c, 0), GenCopy.bestOf(c),
                GenCopy.yourBest(c), GenCopy.firstFinish(c), GenCopy.firstFinishReason(c), GenCopy.times(c),
                GenCopy.boardTitle("Hard Parkour", c), GenCopy.clearLimit(c))) {
            String l = line.toLowerCase(java.util.Locale.ROOT).replace("today's token limit", "");
            assertFalse(l.contains("week") || l.contains("today") || l.contains("another day"),
                    "a Classic never dates its old records, nor promises another day: " + line);
        }
    }

    @Test
    void starTimesAndTheFinishLineReadAsTheSpecSays() {
        assertEquals("1:10", DailyText.clock(70_000), "star times are whole seconds");
        assertEquals("0:45", DailyText.clock(45_000), "with a leading zero");
        assertEquals("&7★★ under 1:10 · ★★★ under 0:45", DailyText.starTimes(45_000, 70_000), "the lore line");
        assertEquals("&e★★☆ 2 stars! &7Next star: under 0:45. &7This week: &69★",
                DailyText.trialFinish(2, 45_000, 70_000, 9), "the spec's finish line, with its colours");
        assertEquals("&e★☆☆ 1 star! &7Next star: under 1:10.", DailyText.trialFinish(1, 45_000, 70_000, -1),
                "one star: the silver time next; an unknown week is left out");
        assertEquals("&e★★★ 3 stars! &7Top marks! &7This week: &612★", DailyText.trialFinish(3, 45_000, 70_000, 12),
                "three: nothing left to chase");
    }

    @Test
    void golfStarsFollowPar() {
        assertEquals("&7★★ in 32 or less · ★★★ in 29 (par) or less", DailyText.starStrokes(29, 9),
                "par 29 over nine holes: three strokes of room for two stars");
        assertEquals("&e★☆☆ 1 star! &7Next star: 32 strokes or less. &7This week: &63★",
                DailyText.golfFinish(1, 29, 9, 3), "one star: the two-star line next");
        assertEquals("&e★★☆ 2 stars! &7Next star: 29 strokes or less.", DailyText.golfFinish(2, 29, 9, -1),
                "two: par next");
    }

    @Test
    void theStarChartsLines() {
        assertEquals("&6Star Chart &7- you: 14★ this week", DailyText.chartName(14), "the tile's name, the spec's");
        List<DailyStars.Goal> goals = List.of(new DailyStars.Goal(12, 2), new DailyStars.Goal(6, 1));
        assertEquals("&7Next goal: &f6★ &7(+1 token)", DailyText.nextGoal(3, goals), "the next goal, its own tokens");
        assertEquals("&7Next goal: &f12★ &7(+2 tokens)", DailyText.nextGoal(7, goals), "the top goal pays 2");
        assertEquals("&aEvery goal this week reached!", DailyText.nextGoal(12, goals), "all reached");
        assertNull(DailyText.nextGoal(3, List.of()), "no goals: no line");
        List<String> how = DailyText.howStars(goals);
        assertTrue(how.contains("&7Reach 6★ in a week: &6+1 token"), "each goal with its own tokens: " + how);
        assertTrue(how.indexOf("&7Reach 6★ in a week: &6+1 token") < how.indexOf("&7Reach 12★ in a week: &6+2 tokens"),
                "smallest first: " + how);
    }

    @Test
    void theQuestsHearAFirstFinishTheTopGoalAndAWholeSet() {
        assertTrue(DailyLookup.firstStarsInSet(new GamesDao.StarsAdded(2, 2, 5)), "none to 2: the first in the set");
        assertFalse(DailyLookup.firstStarsInSet(new GamesDao.StarsAdded(3, 1, 5)), "2 to 3: not the first");
        assertFalse(DailyLookup.firstStarsInSet(new GamesDao.StarsAdded(3, 0, 5)), "no rise: not a first");
        assertFalse(DailyLookup.firstStarsInSet(null), "not recorded: nothing");
        List<DailyStars.Goal> goals = List.of(new DailyStars.Goal(6, 1), new DailyStars.Goal(12, 2));
        assertTrue(DailyLookup.reachedTopGoal(new GamesDao.StarsAdded(3, 2, 12), goals), "10 to 12: the top goal");
        assertTrue(DailyLookup.reachedTopGoal(new GamesDao.StarsAdded(3, 3, 13), goals), "10 to 13: across it");
        assertFalse(DailyLookup.reachedTopGoal(new GamesDao.StarsAdded(3, 1, 13), goals), "12 to 13: already had");
        assertFalse(DailyLookup.reachedTopGoal(new GamesDao.StarsAdded(3, 3, 7), goals), "4 to 7: only the first goal");
        assertFalse(DailyLookup.reachedTopGoal(new GamesDao.StarsAdded(3, 3, 13), List.of()), "no goals: no top");
        GenTag a = new GenTag("fresh_parkour_easy", "parkour", 1, 20_724, 0, 1L, 'A', "a", 1, 2, 3, List.of(),
                List.of(), 1L, 7);
        GenTag b = new GenTag("fresh_golf", "golf", 1, 20_724, 0, 2L, 'A', "b", 0, 0, 0, List.of(), List.of(), 1L, 7);
        assertTrue(DailyLookup.setFinished(List.of(a, b), t -> true), "stars on every course: the set is finished");
        assertFalse(DailyLookup.setFinished(List.of(a, b), t -> t == a), "one without: not yet");
        assertFalse(DailyLookup.setFinished(List.of(), t -> true), "no set up: never finished");
    }

    @Test
    void theEditorsCheckFindsEverySideOfAHalfAndNoFurther() {
        Box half = Slots.DAILY_GOLF.half('A'); // x 4864-4927, y 160-175, z 4096-4223
        GeneratedCourses g = new GeneratedCourses() {
            @Override
            public boolean live(String courseId, GenTag tag) {
                return true;
            }

            @Override
            public boolean standing(GenTag tag) {
                return false;
            }

            @Override
            public String closedLine(String courseId) {
                return "";
            }

            @Override
            public long nextChangeAt() {
                return -1;
            }

            @Override
            public boolean inArea(String world, int x, int y, int z) {
                return "games".equals(world) && half.contains(x, y, z);
            }
        };
        Box near = half.expand(DailyLookup.EDITOR_MARGIN);
        for (int x = near.minX() - 2; x <= near.maxX() + 2; x += 3) {
            for (int y = near.minY() - 2; y <= near.maxY() + 2; y++) {
                for (int z : new int[]{near.minZ() - 1, near.minZ(), 4150, near.maxZ(), near.maxZ() + 1}) {
                    assertEquals(near.contains(x, y, z), DailyLookup.nearArea(g, "games", x, y, z),
                            "(" + x + ", " + y + ", " + z + ") is within 16 of the half exactly when it is inside it + 16");
                }
            }
        }
        assertFalse(DailyLookup.nearArea(g, "other", 4900, 165, 4100), "another world");
        assertFalse(DailyLookup.nearArea(null, "games", 4900, 165, 4100), "no engine");
    }
}
