package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.arcade.feast.FeastRules;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Family Feast's tables in the Clubhouse (0.38.6): a plate shows food once the total passes its twentieth, only
 * the plates that changed (or lost their frame) are drawn again, and the line reads the family's total with its bar.
 */
class FeastTableTest {

    private static String[] drawn(int plates) {
        String[] out = new String[FeastRules.CELLS];
        for (int i = 0; i < out.length; i++) {
            out[i] = FeastTable.want(i, plates);
        }
        return out;
    }

    private static boolean[] alive(boolean all) {
        boolean[] out = new boolean[FeastRules.CELLS];
        Arrays.fill(out, all);
        return out;
    }

    @Test
    void aPlateShowsFoodOncePassed() {
        assertNull(FeastTable.want(5, 5), "plate 5 waits for the sixth twentieth");
        assertEquals(FeastRules.kindAt(4), FeastTable.want(4, 5));
        assertTrue(FeastRules.points(FeastTable.want(0, 20)) > 0, "food the Feast takes");
    }

    @Test
    void onlyTheChangedPlatesAreDrawnAgain() {
        assertEquals(List.of(), FeastTable.changed(drawn(5), alive(true), 5), "nothing changed: nothing drawn");
        assertEquals(List.of(5, 6), FeastTable.changed(drawn(5), alive(true), 7), "two more plates fill");
        assertEquals(List.of(3, 4), FeastTable.changed(drawn(5), alive(true), 3), "a reset empties two");
        boolean[] lost = alive(true);
        lost[12] = false;
        assertEquals(List.of(12), FeastTable.changed(drawn(5), lost, 5), "a frame its chunk took is made again");
        assertEquals(FeastRules.CELLS, FeastTable.changed(drawn(0), alive(false), 0).size(), "first draw: all");
    }

    @Test
    void theLine() {
        assertEquals("&6Family Feast &f137 / 150\n" + FeastRules.bar(137, 150), FeastTable.text(137, 150));
        assertTrue(FeastTable.text(10, 150).chars().allMatch(c -> c < 128), "plain ASCII: Bedrock shows it as a name");
    }
}
