package com.dierks.homecraft.gui.games.clubhouse;

import com.dierks.homecraft.games.clubhouse.StatsCard;
import com.dierks.homecraft.gui.games.GameScreen;
import com.dierks.homecraft.util.Text;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * My stats as a screen (0.38.3): a games screen (so it opens in the Clubhouse's session), every fact in an item's
 * NAME for Bedrock, its tiles clear of the page arrows' slots and of the way out.
 */
class MyStatsMenuTest {

    @Test
    void aCoursesBestAndItsPlaceAreInTheName() {
        assertEquals("Sky Rings - 1:02.4 (#2)", Text.plain(MyStatsMenu.bestName(new StatsCard.Best("Sky Rings", 62_400,
                2))), "the best and its place");
        assertEquals("River Run - 1:10.0", Text.plain(MyStatsMenu.bestName(new StatsCard.Best("River Run", 70_000, 0))),
                "no place read: none shown");
    }

    @Test
    void itIsAGamesScreenWithItsTilesClearOfTheArrowsAndTheWayOut() {
        assertTrue(GameScreen.class.isAssignableFrom(MyStatsMenu.class), "opens in a world session");
        Set<Integer> tiles = Set.of(MyStatsMenu.HEADER, MyStatsMenu.RUNS, MyStatsMenu.TOKENS, MyStatsMenu.GOLF,
                MyStatsMenu.BESTS, MyStatsMenu.COUNTING);
        for (int i = 0; i < MyStatsMenu.MAX_BESTS; i++) {
            int slot = MyStatsMenu.FIRST_BEST + i;
            assertTrue(slot < 45, "the bests stay in rows 3-4: " + slot);
            assertTrue(!tiles.contains(slot), "and clear of the other tiles: " + slot);
        }
        for (int slot : tiles) {
            assertTrue(slot != 45 && slot != 49 && slot != 53, "never on an arrow's slot or the way out: " + slot);
        }
    }
}
