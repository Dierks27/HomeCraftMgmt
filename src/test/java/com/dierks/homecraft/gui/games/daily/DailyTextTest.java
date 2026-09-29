package com.dierks.homecraft.gui.games.daily;

import com.dierks.homecraft.games.GeneratedCourses;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Slots;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The daily courses' words and the few sums behind them (GEN-SPEC §5.2-§5.4, §7), with no server.
 *
 * <p>Pinned here: every line is kid-safe and drawable on Bedrock; "today" is the course day the
 * engine sees (the day before its next change, else the 04:00 rollover); star times read in whole
 * seconds and the finish line leads with the stars, then the next one, then the week (the spec's
 * own example); golf star lines follow par; the Star Chart's lines; and the editors' "within 16
 * blocks of a Daily Courses half" check finds a point on every side of a half and no further.
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
    void todayIsTheCourseDayTheEngineSees() {
        long nextAt = at(9, 30, 4, 0);
        assertEquals(20_725, DailyText.courseDay(at(9, 29, 15, 0), nextAt, CHICAGO),
                "the day before the next change");
        assertEquals(20_725, DailyText.courseDay(at(9, 30, 3, 59), nextAt, CHICAGO),
                "at 03:59 it is still yesterday's course day");
        assertEquals(20_724, DailyText.courseDay(at(9, 29, 3, 0), -1, CHICAGO),
                "no engine: the shipped 04:00 rollover decides");
        assertEquals(20_725, DailyText.courseDay(at(9, 29, 4, 0), -1, CHICAGO), "from 04:00 it is the new day");
        assertEquals("today", DailyText.dayText(20_725, 20_725), "today reads as today");
        assertEquals("Mon 28 Sep", DailyText.dayText(20_724, 20_725), "any other day by its date");
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
        assertEquals("&7Next goal: &f25★ &7(+1 token)", DailyText.nextGoal(14, List.of(10, 25), 1), "the next goal");
        assertEquals("&aEvery goal this week reached!", DailyText.nextGoal(25, List.of(25, 10), 1), "all reached");
        assertNull(DailyText.nextGoal(3, List.of(), 1), "no goals: no line");
        assertTrue(DailyText.howStars(List.of(25, 10), 1).contains("&7Reach 10★ and 25★ in a week:"),
                "how stars work names the goals, smallest first");
        assertEquals("&7First finish today: &6+2 tokens", DailyText.firstToday(2, false), "the day's reward");
        assertEquals("&a✔ First finish today done", DailyText.firstToday(2, true), "once had");
        assertNull(DailyText.firstToday(0, false), "nothing to pay: nothing said");
        assertEquals("&aEasy Parkour &7- ★★☆ &a(new today)",
                DailyText.tabName(Slots.DAILY_PARKOUR_EASY, "Easy Parkour", DailyText.trialFact(2), 20_725, 20_725),
                "a daily tile on the Courses tab is marked new today");
        assertEquals("&aEasy Parkour &7- no time today &8(yesterday's)",
                DailyText.tabName(Slots.DAILY_PARKOUR_EASY, "Easy Parkour", DailyText.trialFact(0), 20_724, 20_725),
                "until today's is up, yesterday's says so");
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
