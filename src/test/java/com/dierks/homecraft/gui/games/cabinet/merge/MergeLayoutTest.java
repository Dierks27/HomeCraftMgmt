package com.dierks.homecraft.gui.games.cabinet.merge;

import com.dierks.homecraft.games.cabinet.merge.MergeEngine;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the Ore Merge screen puts things, with no server.
 *
 * <p>Pinned here: the 4 by 4 grid is rows 1-4, columns 2-5, keeping its shape; the four slide
 * buttons are in row 5, never on 45/53 (page arrows) or 49 (the way out), left of Back for left
 * and up, right of it for down and right; row 0's tiles and End game stay clear of the grid.
 */
class MergeLayoutTest {

    private static final Set<Integer> RESERVED = Set.of(45, 49, 53);

    @Test
    void theGridIsRowsOneToFourColumnsTwoToFive() {
        Set<Integer> slots = new HashSet<>();
        for (int cell = 0; cell < MergeEngine.CELLS; cell++) {
            int slot = MergeLayout.slot(cell);
            assertEquals(cell / MergeEngine.SIZE + 1, slot / 9, "square " + cell + " keeps its row, one down");
            assertEquals(cell % MergeEngine.SIZE + 2, slot % 9, "square " + cell + " keeps its column, two in");
            assertTrue(slots.add(slot), "square " + cell + " has a slot of its own");
        }
    }

    @Test
    void theSlideButtonsAreInRowFiveEitherSideOfBack() {
        Set<Integer> arrows = new HashSet<>();
        for (MergeEngine.Dir dir : MergeEngine.Dir.values()) {
            int slot = MergeLayout.arrow(dir);
            assertTrue(slot > 45 && slot < 53, dir + " is in row 5");
            assertFalse(RESERVED.contains(slot), dir + " is never on a page-arrow slot or the way out");
            assertTrue(arrows.add(slot), dir + " has its own button");
        }
        assertTrue(MergeLayout.arrow(MergeEngine.Dir.LEFT) < MergeLayout.arrow(MergeEngine.Dir.RIGHT),
                "left is on the left of right");
        assertTrue(MergeLayout.arrow(MergeEngine.Dir.UP) < 49 && MergeLayout.arrow(MergeEngine.Dir.DOWN) > 49,
                "the row reads ◀ ▲ Back ▼ ▶");
        assertFalse(arrows.contains(MergeLayout.END), "End game is not a slide button");
        assertFalse(RESERVED.contains(MergeLayout.END), "nor an arrow slot");
    }

    @Test
    void rowZeroAndTheChoiceTilesStayOffTheGrid() {
        Set<Integer> grid = new HashSet<>();
        for (int cell = 0; cell < MergeEngine.CELLS; cell++) {
            grid.add(MergeLayout.slot(cell));
        }
        for (int slot : new int[]{MergeLayout.SCORE, MergeLayout.RUN, MergeLayout.BIGGEST}) {
            assertTrue(slot < 9, slot + " is in row 0");
            assertFalse(grid.contains(slot), slot + " isn't a grid square");
        }
        Set<Integer> choice = new HashSet<>(Set.of(MergeLayout.TITLE, MergeLayout.RULES, 49));
        assertTrue(choice.add(MergeLayout.CLASSIC) && choice.add(MergeLayout.DAILY), "the two boards have their own tiles");
        assertTrue(choice.add(MergeLayout.CLASSIC + MergeLayout.SCORES_BELOW)
                && choice.add(MergeLayout.DAILY + MergeLayout.SCORES_BELOW), "and their high scores too");
    }
}
