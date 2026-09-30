package com.dierks.homecraft.gui.games.cabinet.sweeper;

import com.dierks.homecraft.games.cabinet.sweeper.SweeperEngine;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the Creeper Sweeper screen puts things, with no server.
 *
 * <p>Pinned here: the 9 by 5 board is exactly rows 0-4, one square per slot; every control is in
 * row 5 and never on 45/53 (page arrows) or 49 (the way out); the choice screen's tiles and their
 * high-score buttons never collide.
 */
class SweeperLayoutTest {

    private static final Set<Integer> RESERVED = Set.of(45, 49, 53);

    @Test
    void theBoardIsExactlyTheTopFiveRows() {
        Set<Integer> slots = new HashSet<>();
        for (int cell = 0; cell < SweeperEngine.CELLS; cell++) {
            int slot = SweeperLayout.slot(cell);
            assertTrue(slot >= 0 && slot < 45, "square " + cell + " is in rows 0-4");
            assertTrue(slots.add(slot), "square " + cell + " has a slot of its own");
        }
        assertEquals(45, slots.size(), "all 45 slots of rows 0-4 are the board");
    }

    @Test
    void theControlsAreInRowFiveAwayFromTheArrowsAndTheWayOut() {
        List<Integer> controls = List.of(SweeperLayout.TOGGLE, SweeperLayout.LEFT, SweeperLayout.TIME,
                SweeperLayout.AGAIN, SweeperLayout.RUN);
        assertEquals(controls.size(), new HashSet<>(controls).size(), "no two controls share a slot");
        for (int slot : controls) {
            assertTrue(slot >= 45 && slot <= 53, slot + " is in row 5, under the board");
            assertFalse(RESERVED.contains(slot), slot + " is not a page-arrow slot or the way out");
        }
    }

    @Test
    void theChoiceScreenTilesNeverCollide() {
        Set<Integer> used = new HashSet<>(Set.of(SweeperLayout.TITLE, SweeperLayout.RULES, 49));
        for (int slot : SweeperLayout.LEVELS) {
            assertTrue(used.add(slot), "difficulty tile " + slot + " is free");
            assertTrue(used.add(slot + SweeperLayout.SCORES_BELOW), "its high scores " + (slot + 9) + " are free");
        }
        assertTrue(used.add(SweeperLayout.DAILY), "the daily tile is free");
        assertTrue(used.add(SweeperLayout.DAILY + SweeperLayout.SCORES_BELOW), "and its high scores");
        for (int slot : used) {
            assertTrue(slot == 49 || !RESERVED.contains(slot), slot + " stays off the arrow slots");
        }
    }
}
