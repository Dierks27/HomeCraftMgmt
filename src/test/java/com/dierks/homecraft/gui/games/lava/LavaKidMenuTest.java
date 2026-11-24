package com.dierks.homecraft.gui.games.lava;

import com.dierks.homecraft.games.lava.rules.LavaMode;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.57.0, the cabinet's picker (the owner: "you should be able to pick any level you want not just easy"): a column
 * for every mode, the columns side by side so one press of a controller's stick goes from one to the next, each a
 * picture over its magma, and none of them where the door is.
 */
class LavaKidMenuTest {

    @Test
    void everyModeHasAColumnSideBySideWithTheNext() {
        int[][] c = LavaKidMenu.COLUMNS;
        assertEquals(LavaMode.values().length, c.length, "Little, Group and Challenge");
        Set<Integer> used = new HashSet<>();
        for (int n = 0; n < c.length; n++) {
            assertEquals(c[n][0] + 9, c[n][1], "the magma right under the picture: one press down");
            assertTrue(used.add(c[n][0]) && used.add(c[n][1]));
            if (n > 0) {
                assertEquals(c[n - 1][0] + 1, c[n][0], "side by side: one press across");
                assertEquals(c[n - 1][0] / 9, c[n][0] / 9, "on one row");
            }
            assertTrue(c[n][1] < 18, "over the bottom row");
        }
        assertTrue(!used.contains(22), "the door (22) is its own");
        assertEquals(4, c[1][0], "Group in the middle, over the door");
    }
}
