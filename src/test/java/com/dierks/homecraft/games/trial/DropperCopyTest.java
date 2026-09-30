package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.SignText;
import com.dierks.homecraft.games.gen.dropper.DropperPlanner;
import com.dierks.homecraft.gui.games.daily.DailyText;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Dropper's words (EVENTS-DROPPER-SPEC §B.1.1, §B.1.7, §B.1.8; the practice drop of D3), in one
 * place: "level 2 of 5", the level signs (4 lines of at most 15 ASCII characters, on every real plan
 * too), the titles, the clock line, the result's bonk line, the kit items with their key words in the
 * NAME (Bedrock shows lore only on tap-and-hold), and every line kid-safe: nothing Bedrock can't draw
 * and none of the words the games never say.
 */
class DropperCopyTest {

    @Test
    void levelsReadLevel2Of5() {
        assertEquals("level 2 of 5", TrialText.level(2, 5), "a dropper's progress");
        assertEquals("level 3", TrialText.level(3), "one level");
        assertEquals("1 level", TrialText.levels(1), "one");
        assertEquals("5 levels", TrialText.levels(5), "many");
        assertEquals("3 levels", DailyText.levels(3), "the Fresh Courses tiles say it the same way");
        assertEquals("&e0:12.4 &7· level 2 of 5 · 1 bonk", DropperText.clock(12_400, 2, 5, 1, false, false),
                "the clock line, the spec's own example");
        assertEquals("&e0:12.4 &7· level 1 of 3", DropperText.clock(12_400, 1, 3, 0, false, false),
                "no bonks: none said");
        assertEquals("&dTest &e0:12.4 &7· level 1 of 3", DropperText.clock(12_400, 1, 3, 0, true, false), "a test run");
        assertEquals("&c0:12.4 &7· level 1 of 3 · 2 bonks &8(won't count)", DropperText.clock(12_400, 1, 3, 2, false,
                true), "a run that won't count says so");
    }

    @Test
    void theSplashBonkAndResultLinesAreTheSpecs() {
        assertEquals("&aLevel 2!", DropperText.levelTitle(2), "a splash that clears level 1");
        assertEquals("&7of 5 - keep going!", DropperText.levelSubtitle(5), "and its subtitle");
        assertEquals("&eBonk!", DropperText.BONK_TITLE, "a bonk");
        assertEquals("&7Back to the top of level 2.", DropperText.bonkSubtitle(2), "and where it goes");
        assertEquals("&eSteer while you fall to go through the holes!", DropperText.TIP, "the first bonk's tip");
        assertEquals("&6Splash!", DropperText.SPLASH_TITLE, "the last splash");
        assertEquals("&f0:21.4 &7· ★★★", DropperText.splashSubtitle(21_400, 3), "with the time and the stars");
        assertEquals("&f0:21.4", DropperText.splashSubtitle(21_400, 0), "a hand-built dropper has no stars");
        assertEquals("&aNo bonks - perfect drop!", DropperText.bonks(0), "a clean run");
        assertEquals("&eBonks: 2", DropperText.bonks(2), "and one with bonks");
        assertEquals(List.of("Step off the ledge.", "Steer through the holes.", "Land in the water to clear a level."),
                TrialKind.DROPPER.rules(), "the course screen's rules");
    }

    @Test
    void theStartTileSaysThePracticeDropInItsName() {
        TimeTrialsSettings on = TimeTrialsSettings.defaults();
        TimeTrialsSettings off = new TimeTrialsSettings(true, on.firstClear(), 5, 2, 4, 6, 5, 0, 8);
        String name = DropperText.startName(DropperCourses.hand(), on);
        assertTrue(plain(name).startsWith("Start") && plain(name).contains("practice drop"),
                "a dropper's Start names its practice drop, for Bedrock: " + name);
        assertEquals("Start - practice drop optional", plain(name),
                "the owner's words: the practice drop is offered, never required");
        assertEquals("You can have one practice drop first, or skip it.", plain(DropperText.PRACTICE_ON_START),
                "its lore says it can be skipped");
        assertFalse(plain(name).contains("first"), "nothing in the NAME reads as if the practice drop comes first by rule");
        assertEquals(DropperText.START, DropperText.startName(DropperCourses.hand(), off),
                "with warm-ups off there is no practice drop to name");
        assertEquals(DropperText.START, DropperText.startName(DropperCourses.asParkour(), on),
                "every other course's Start is just Start");
        assertEquals(DropperText.START, DropperText.startName(null, null), "and nothing known is plain Start");
    }

    @Test
    void theKitsKeyWordsAreInItsNames() {
        assertTrue(plain(DropperText.PRACTICE_ITEM).contains("Practice drop (not timed)"), DropperText.PRACTICE_ITEM);
        assertTrue(plain(DropperText.STRAIGHT_ITEM).contains("Go straight to the timed run"), DropperText.STRAIGHT_ITEM);
        assertTrue(plain(DropperText.TIMED_ITEM).contains("Start timed run"), DropperText.TIMED_ITEM);
        assertTrue(plain(DropperText.BACK_ITEM).contains("Back to the top"), DropperText.BACK_ITEM);
        assertEquals("&bPractice drop - not counted", DropperText.PRACTICE_BAR, "the practice drop's action bar");
    }

    @Test
    void theLevelSignsFitASignOnEveryPlan() {
        assertEquals(List.of("LEVEL 2 of 5", "Step off and", "fall into the", "WATER!"), GenCopy.dropperLevel(2, 5),
                "the spec's sign");
        assertEquals(GenCopy.dropperLevel(3, 3), DropperPlanner.signLines(3, 3), "the planner writes GenCopy's words");
        List<List<String>> signs = new ArrayList<>();
        for (String mix : List.of("EEE", "EEMMH", "HHHHH")) {
            Plan p = DropperCourses.plan(mix, 4);
            for (SignText s : p.signs()) {
                signs.add(s.lines());
            }
            assertEquals(mix.length(), p.signs().size(), mix + ": one sign a level");
        }
        for (List<String> sign : signs) {
            assertTrue(sign.size() <= 4, "at most 4 lines: " + sign);
            for (String line : sign) {
                assertTrue(line.length() <= 15, "at most 15 characters: " + line);
                assertTrue(line.chars().allMatch(ch -> ch >= 32 && ch < 127), "plain ASCII: " + line);
            }
        }
    }

    @Test
    void everyLineIsKidSafeAndDrawableOnBedrock() {
        List<String> lines = new ArrayList<>(DropperText.everyLine());
        lines.addAll(TrialKind.DROPPER.rules());
        lines.add(CourseAdmin.HAND_MADE_DROPPER);
        for (String line : lines) {
            assertEquals(List.of(), GenCopy.copyProblems(line), "no banned word and nothing Bedrock can't draw: " + line);
            assertTrue(line.codePoints().allMatch(cp -> cp <= 0xFFFF), "nothing above U+FFFF: " + line);
            String lower = " " + line.toLowerCase(Locale.ROOT) + " ";
            for (String banned : List.of(" bet ", "wager", "gamble", "casino", "lucky", "almost", "so close", " sink",
                    "fail", "try again")) {
                assertFalse(lower.contains(banned), line + " says " + banned.trim());
            }
        }
    }

    private static String plain(String coloured) {
        return coloured.replaceAll("&[0-9a-fk-or]", "");
    }
}
