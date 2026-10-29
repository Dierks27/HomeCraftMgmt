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
                ClubBoardText.ONLY_YOU, ClubBoardText.READING, ClubBoardText.NO_BESTS, ClubBoardText.COUNTING);
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
