package com.dierks.homecraft.games.clubhouse;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The results board (CLUBHOUSE-SPEC §4, §11): its rows ("1. Sam 1:02.4 (+0.0)", points, strokes), at
 * most eight, a new result drawn at once and only once, live positions at most once a second.
 */
class ClubBoardTest {

    @Test
    void theRowsSayPlaceNameTimeAndGap() {
        assertEquals("&e1. &fSam &71:02.4 (+0.0)", ClubBoard.timeRow(1, "Sam", 62_400, 62_400), "the winner");
        assertEquals("&e2. &fAva &71:03.8 (+1.4)", ClubBoard.timeRow(2, "Ava", 63_800, 62_400), "the gap to the winner");
        assertEquals("&e1. &fSam &728 pts", ClubBoard.pointsRow(1, "Sam", 28), "Race Night: points");
        assertEquals("&e3. &fLee &71 pt", ClubBoard.pointsRow(3, "Lee", 1), "one point");
        assertEquals("&e1. &fSam &724 strokes (-2)", ClubBoard.strokesRow(1, "Sam", 24, -2), "golf: strokes");
        assertEquals("&e2. &fAva &727 strokes (+1)", ClubBoard.strokesRow(2, "Ava", 27, 1), "over par");
        assertEquals("&e1. &fAva &720 strokes (par)", ClubBoard.strokesRow(1, "Ava", 20, 0), "level");
        assertEquals("&fLee &7- still racing", ClubBoard.noTimeRow("Lee", "still racing"), "no time");
        assertEquals("&e1. &fa player &7lap 2/3 (+1.4)", ClubBoard.liveRow(1, null, "lap 2/3", 1_400L), "a live row");
    }

    @Test
    void atMostEightRows() {
        List<String> rows = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            rows.add(ClubBoard.pointsRow(i, "P" + i, 20 - i));
        }
        ClubBoard.Sheet s = new ClubBoard.Sheet("&6Race Night", rows, false, "n");
        assertEquals(8, s.rows().size(), "up to 8 rows");
        assertEquals(9, s.text().split("\n").length, "the title and 8 rows");
    }

    @Test
    void aNewResultIsDrawnOnceAndLivePositionsAtMostOnceASecond() {
        ClubBoard b = new ClubBoard();
        ClubBoard.Sheet result = new ClubBoard.Sheet("&6Party race", List.of("&e1. &fSam"), false, "r1");
        assertTrue(b.offer(result, 1_000), "a new result: drawn at once");
        assertFalse(b.offer(result, 1_001), "the same result: never drawn again");
        assertFalse(b.offer(result, 999_999), "not even much later: nothing redraws per tick");
        int drawn = 0;
        for (long t = 2_000; t < 12_000; t += 50) { // ten seconds of ticks, new live positions every tick
            ClubBoard.Sheet live = new ClubBoard.Sheet("&6Live", List.of("&7t=" + t), true, "live");
            if (b.offer(live, t)) {
                drawn++;
            }
        }
        assertEquals(10, drawn, "live positions every tick for ten seconds: drawn ten times, once a second");
        ClubBoard.Sheet fin = new ClubBoard.Sheet("&6Party race", List.of("&e1. &fAva"), false, "r2");
        assertTrue(b.offer(fin, 11_960), "the final result is drawn at once, even inside the second");
        assertEquals(fin.text(), b.shown(), "the board shows the final result after the end");
        b.forget();
        assertTrue(b.offer(fin, 11_970), "made again (its chunk had unloaded it): drawn again");
    }
}
