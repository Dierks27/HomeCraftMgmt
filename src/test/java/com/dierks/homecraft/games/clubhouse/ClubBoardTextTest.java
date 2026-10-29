package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.golf.MiniGolf;
import com.dierks.homecraft.games.trial.TimeTrials;
import com.dierks.homecraft.util.Text;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The walls' words (0.38.3): the My stats item's key fact is in its NAME (Bedrock), every board line stays short and
 * plain for Bedrock's name tags (40 characters at most for typical names and courses, long ones cut with "..."),
 * and the two bridge files outside the Clubhouse's package carry no words a player reads.
 */
class ClubBoardTextTest {

    @Test
    void theKitsKeyFactIsInItsName() {
        assertTrue(ClubBoardText.KIT_STATS.contains("- ") && ClubBoardText.KIT_STATS.length() > 20,
                "Bedrock reads the NAME: " + ClubBoardText.KIT_STATS);
    }

    @Test
    void boardLinesAreShortAndPlainForTypicalNames() {
        List<String> lines = List.of(
                ClubBoardText.courseTime("Mountain Run", "Sam", 125_400),
                ClubBoardText.golfWeek("Golf of the Week", "Lee", 31, -2),
                ClubBoardText.golfRecord("Tiny Golf", "Alexander", 24, 0),
                ClubBoardText.pick("Hard Parkour", 5),
                ClubBoardText.cup("River Run", "Sam", 71_500),
                ClubBoardText.playing("Sam", "Sky Rings", "checkpoint 3", 41_000),
                ClubBoardText.playing("Lee", "Tiny Golf", "hole 4", 0),
                ClubBoardText.playing("Ana", ClubBoardText.IN_CLUBHOUSE, null, 0),
                ClubBoardText.runs(12), ClubBoardText.tokens(145), ClubBoardText.bestGolf("Tiny Golf", 24, -2),
                ClubBoardText.best("Sky Rings", 62_400, 2), ClubBoardText.statsTitle("Sam"),
                ClubBoardText.NO_RUNS, ClubBoardText.NO_RECORDS, ClubBoardText.CHEER, ClubBoardText.NOBODY,
                ClubBoardText.ONLY_YOU, ClubBoardText.READING, ClubBoardText.NO_BESTS, ClubBoardText.COUNTING,
                // 0.38.4: the family week, the trophy case and the jukebox
                ClubBoardText.familyRow(1, "Alexander", 14, 3), ClubBoardText.familyRow(2, "Sam", 1, 0),
                ClubBoardText.statsWeek(9, 2), ClubBoardText.statsWeek(0, 0), ClubBoardText.NO_FAMILY,
                ClubBoardText.PHOTO_FAMILY, ClubBoardText.trophyLine("Mountain Run", "1:02.4", "Alexander"),
                ClubBoardText.picked("Alexander", "Creator Music Box"), ClubBoardText.next("Creator Music Box"),
                ClubBoardText.quiet("Alexander"), ClubBoardText.nowPlaying("Pigstep"), ClubBoardText.QUIET,
                ClubBoardText.SONGS_HEADER, ClubBoardText.NOTHING_PLAYING, ClubBoardText.SONGS_ELSEWHERE,
                ClubBoardText.NO_JUKEBOX, FamilyWeekRules.lastLine(List.of("Sam", "Lee", "Ana")));
        for (String line : lines) {
            String plain = Text.plain(line);
            assertTrue(plain.length() <= 40, "at most 40 characters on a Bedrock name tag: " + plain);
            assertTrue(plain.chars().allMatch(c -> c >= 0x20 && c < 0x7F), "plain ASCII: " + plain);
        }
        assertEquals("Sam - Sky Rings - checkpoint 3 - 0:41",
                Text.plain(ClubBoardText.playing("Sam", "Sky Rings", "checkpoint 3", 41_000)), "the owner's example");
        assertEquals("Tiny Golf - Lee 24 (par)", Text.plain(ClubBoardText.golfRecord("Tiny Golf", "Lee", 24, 0)),
                "par in words, as the results board says it");
    }

    /** 0.38.4: the family week's and the jukebox's words, the key fact in each NAME. */
    @Test
    void theFamilyWeeksWords() {
        assertEquals("1. Sam 14 pts - 3 wins", Text.plain(ClubBoardText.familyRow(1, "Sam", 14, 3)), "the owner's example");
        assertEquals("2. Lee 1 pt", Text.plain(ClubBoardText.familyRow(2, "Lee", 1, 0)), "no wins: no wins said");
        assertEquals("3. Ana 5 pts - 1 win", Text.plain(ClubBoardText.familyRow(3, "Ana", 5, 1)), "one win");
        assertEquals("This week: 9 pts, 2nd", Text.plain(ClubBoardText.statsWeek(9, 2)), "the owner's example");
        assertEquals("This week: no points yet", Text.plain(ClubBoardText.statsWeek(0, 0)), "nothing yet");
        assertEquals("This week: 3 pts", Text.plain(ClubBoardText.statsWeek(3, 0)), "points with no place yet");
        for (String[] o : new String[][]{{"1", "1st"}, {"2", "2nd"}, {"3", "3rd"}, {"4", "4th"}, {"11", "11th"},
                {"12", "12th"}, {"13", "13th"}, {"21", "21st"}, {"22", "22nd"}, {"103", "103rd"}}) {
            assertEquals(o[1], ClubBoardText.ordinal(Integer.parseInt(o[0])));
        }
        assertEquals("Sky Rings 1:02.4 - Sam", Text.plain(ClubBoardText.trophyLine("Sky Rings", "1:02.4", "Sam")));
        assertEquals("Sam picked Pigstep", Text.plain(ClubBoardText.picked("Sam", "Pigstep")));
        assertTrue(ClubBoardText.SONGS_TILE.contains("- ") && ClubBoardText.QUIET.contains("- "),
                "Bedrock reads the NAME");
    }

    @Test
    void longNamesAndCoursesAreCut() {
        assertEquals("A Very Long Cou...", ClubBoardText.course("A Very Long Course Name Indeed"),
                "a course: at most 18, with ...");
        assertEquals("Sky Rings", ClubBoardText.course("Sky Rings"), "a short one as it is");
        assertTrue(ClubBoardText.name("Abcdefghijklmnopqrstuvwxyz").length() <= ClubBoardText.NAME_MAX, "a name: 16");
        assertEquals("someone", ClubBoardText.name(null), "never blank");
        assertEquals("a course", ClubBoardText.course(" "), "never blank");
        String worst = Text.plain(ClubBoardText.playing("Abcdefghijklmnop", "Abcdefghijklmnopqrstu",
                "racing, checkpoint 12", 3_599_000));
        assertTrue(worst.length() <= 70, "the longest row still wraps at most once on a wall: " + worst);
    }

    @Test
    void theGamesTheBoardsReadArePinned() {
        assertEquals(TimeTrials.SPEC.id(), ClubData.TRIALS, "time-trial scores");
        assertEquals(MiniGolf.SPEC.id(), ClubData.GOLF, "golf scores");
        assertEquals(Clubhouse.SPEC.id(), ClubData.CLUB, "the family week's points (0.38.4)");
    }

    /** Words a player reads are the Clubhouse's ({@code ClubBoardText}): the bridges have none (the copy scan). */
    @Test
    void theBridgeFilesSayNothing() throws IOException {
        Path base = Path.of("src", "main", "java", "com", "dierks", "homecraft", "games");
        Pattern literal = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
        List<String> found = new ArrayList<>();
        for (Path f : List.of(base.resolve("trial/TrialsNow.java"), base.resolve("golf/GolfNow.java"))) {
            for (String line : Files.readAllLines(f)) {
                String code = line.strip();
                if (code.startsWith("*") || code.startsWith("//") || code.startsWith("/*")) {
                    continue;
                }
                Matcher m = literal.matcher(line);
                while (m.find()) {
                    found.add(f.getFileName() + ": " + m.group(1));
                }
            }
        }
        assertEquals(List.of(), found, "no player-facing strings outside the Clubhouse's copy");
    }
}
