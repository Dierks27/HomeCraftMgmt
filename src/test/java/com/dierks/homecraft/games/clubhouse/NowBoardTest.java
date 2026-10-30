package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.golf.GolfNow;
import com.dierks.homecraft.games.trial.TrialsNow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Now playing (0.38.3): who is in which game and how far along ("Sam - Sky Rings - checkpoint 3 - 0:41", "Lee -
 * Tiny Golf - hole 4", "Ana - in the Clubhouse"), games first, then cabinets, then the Clubhouse; seven rows at most
 * ("and 3 more"); the cheer line while anyone is in a game; each player once; never anyone's own numbers.
 */
class NowBoardTest {

    private static final UUID SAM = UUID.randomUUID();
    private static final UUID LEE = UUID.randomUUID();
    private static final UUID ANA = UUID.randomUUID();
    private static final UUID MAX = UUID.randomUUID();
    private static final Map<UUID, String> NAMES = Map.of(SAM, "Sam", LEE, "Lee", ANA, "Ana", MAX, "Max");

    @Test
    void whoIsInWhichGameAndHowFar() {
        List<NowBoard.Playing> rows = NowBoard.rows(
                List.of(new TrialsNow.Run(SAM, "Sky Rings", 3, 12, 41_900, false, false, false, false)),
                List.of(new GolfNow.Round(LEE, "Tiny Golf", 4, 9, false, false)),
                List.of(new NowBoard.Other(ANA, ClubBoardText.IN_CLUBHOUSE, NowBoard.CLUBHOUSE),
                        new NowBoard.Other(MAX, "Snake", NowBoard.CABINET),
                        new NowBoard.Other(MAX, ClubBoardText.IN_CLUBHOUSE, NowBoard.CLUBHOUSE)),
                NAMES::get);
        assertEquals(4, rows.size(), "each player once (Max is at Snake, in the Clubhouse): " + rows);
        String text = NowBoard.text(rows);
        assertEquals(String.join("\n", "&b&lNow playing",
                "&fLee &7- &eTiny Golf &7- hole 4",
                "&fSam &7- &eSky Rings &7- checkpoint 3 &7- &f0:41",
                "&fMax &7- &eSnake",
                "&fAna &7- &ein the Clubhouse",
                ClubBoardText.CHEER), text, "games, cabinets, the Clubhouse; the cheer line");
    }

    @Test
    void howFarARunIs() {
        assertEquals("starting", NowBoard.runWhere(true, false, false, false, 0, 5), "the 3-2-1");
        assertEquals("warming up", NowBoard.runWhere(false, true, false, false, 2, 5), "not timed");
        assertEquals("checkpoint 2", NowBoard.runWhere(false, false, false, false, 2, 5), "on its way");
        assertEquals(null, NowBoard.runWhere(false, false, false, false, 0, 5), "none reached yet: just the clock");
        assertEquals(null, NowBoard.runWhere(false, false, false, false, 0, 0), "a course with no checkpoints");
        assertEquals("racing", NowBoard.runWhere(false, false, true, false, 0, 0), "a race");
        assertEquals("racing, checkpoint 1", NowBoard.runWhere(false, false, true, false, 1, 3), "and how far");
        assertEquals("finished", NowBoard.runWhere(false, false, false, true, 3, 3), "done");
        assertEquals("ring 7", NowBoard.runWhere(false, false, false, false, 7, 19, true), "an elytra course: rings");
        assertEquals("racing, ring 1", NowBoard.runWhere(false, false, true, false, 1, 19, true), "raced too");
        assertEquals("&fSam &7- &eSky Rings &7- ring 7 &7- &f0:41", NowBoard.text(NowBoard.rows(
                List.of(new TrialsNow.Run(SAM, "Sky Rings", 7, 19, 41_900, false, false, false, false, true)),
                List.of(), List.of(), NAMES::get)).split("\n")[1], "on the board");
        List<NowBoard.Playing> done = NowBoard.rows(
                List.of(new TrialsNow.Run(SAM, "Sky Rings", 3, 3, 60_000, false, false, false, true)), List.of(),
                List.of(), NAMES::get);
        assertEquals("&fSam &7- &eSky Rings &7- finished", NowBoard.row(done.get(0)), "a finished run has no clock");
        List<NowBoard.Playing> over = NowBoard.rows(List.of(),
                List.of(new GolfNow.Round(LEE, "Tiny Golf", 9, 9, true, true)), List.of(), NAMES::get);
        assertEquals("&fLee &7- &eTiny Golf &7- finished", NowBoard.row(over.get(0)), "a round over");
        assertEquals("12:05", ClubBoardText.clock(725_999), "minutes and seconds");
        assertEquals("0:00", ClubBoardText.clock(-5), "never negative");
    }

    @Test
    void sevenRowsAtMostAndNobodySaysSo() {
        assertEquals("&b&lNow playing\n" + ClubBoardText.NOBODY, NowBoard.text(List.of()), "nobody");
        List<NowBoard.Playing> many = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            many.add(new NowBoard.Playing("P" + i, "Snake", null, 0, NowBoard.CABINET));
        }
        String text = NowBoard.text(many);
        String[] lines = text.split("\n");
        assertEquals(WallBoards.LINES - 1, lines.length, "title, six rows, and 4 more: " + text);
        assertEquals(ClubBoardText.more(4), lines[lines.length - 1], "and 4 more");
        assertFalse(text.contains("cheer"), "nobody in a game to cheer");
        many.add(new NowBoard.Playing("Zed", "Sky Rings", null, 0, NowBoard.GAME));
        String[] withGame = NowBoard.text(many).split("\n");
        assertEquals(WallBoards.LINES, withGame.length, "title, six rows, and N more, and the cheer line");
        assertTrue(withGame[1].startsWith("&fZed"), "the games first");
        List<NowBoard.Playing> seven = many.subList(0, 7);
        assertEquals(8, NowBoard.text(seven).split("\n").length, "seven fit: no 'more'");
    }
}
