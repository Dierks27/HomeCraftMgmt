package com.dierks.homecraft.gui.games.cabinet.snake;

import com.dierks.homecraft.games.cabinet.snake.SnakeEngine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the Snake screen puts things (spec R3.14), with no server.
 *
 * <p>Pinned here: the 7 by 5 field is columns 1-7 of rows 0-4; column 0 is five "turn left"
 * buttons (0, 9, 18, 27, 36) and column 8 five "turn right" ones (8, 17, 26, 35, 44); row 5 is 46
 * apples, 47 best, 48 speed, 50 start/pause, with 45 and 53 left alone and 49 for Back.
 */
class SnakeLayoutTest {

    @Test
    void theFieldIsColumnsOneToSevenOfTheTopFiveRows() {
        Set<Integer> slots = new HashSet<>();
        for (int y = 0; y < SnakeEngine.ROWS; y++) {
            for (int x = 0; x < SnakeEngine.COLS; x++) {
                int slot = SnakeLayout.slot(x, y);
                assertEquals(y, slot / 9, "(" + x + "," + y + ") keeps its row");
                assertEquals(x + 1, slot % 9, "(" + x + "," + y + ") sits one column in");
                assertTrue(slots.add(slot), "(" + x + "," + y + ") has a slot of its own");
            }
        }
        assertEquals(35, slots.size(), "35 squares");
    }

    @Test
    void theTurnButtonsAreFiveTallColumnsEitherSide() {
        List<Integer> left = new ArrayList<>();
        List<Integer> right = new ArrayList<>();
        for (int y = 0; y < SnakeEngine.ROWS; y++) {
            left.add(SnakeLayout.left(y));
            right.add(SnakeLayout.right(y));
        }
        assertEquals(List.of(0, 9, 18, 27, 36), left, "column 0 is all turn left");
        assertEquals(List.of(8, 17, 26, 35, 44), right, "column 8 is all turn right");
    }

    @Test
    void rowFiveMatchesTheSpecAndLeavesTheArrowSlotsAlone() {
        assertEquals(46, SnakeLayout.APPLES, "46 apples");
        assertEquals(47, SnakeLayout.BEST, "47 best");
        assertEquals(48, SnakeLayout.SPEED, "48 speed");
        assertEquals(50, SnakeLayout.PLAY, "50 pause");
        List<Integer> row5 = List.of(SnakeLayout.APPLES, SnakeLayout.BEST, SnakeLayout.SPEED, SnakeLayout.PLAY,
                SnakeLayout.RUN);
        assertEquals(row5.size(), new HashSet<>(row5).size(), "no two share a slot");
        for (int slot : row5) {
            assertTrue(slot > 45 && slot < 53 && slot != 49, slot + " is off 45, 49 and 53");
        }
        Set<Integer> choice = new HashSet<>(Set.of(SnakeLayout.TITLE, SnakeLayout.RULES, 49));
        assertTrue(choice.add(SnakeLayout.CLASSIC) && choice.add(SnakeLayout.DAILY), "the two boards have their own tiles");
        assertFalse(choice.contains(SnakeLayout.CLASSIC + SnakeLayout.SCORES_BELOW)
                || choice.contains(SnakeLayout.DAILY + SnakeLayout.SCORES_BELOW), "and their high scores too");
    }
}
