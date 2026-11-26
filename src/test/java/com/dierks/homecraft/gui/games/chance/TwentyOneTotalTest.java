package com.dierks.homecraft.gui.games.chance;

import com.dierks.homecraft.games.chance.twentyone.TwentyOneHand.Card;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 0.57.1 (the owner: "On Twenty-One can we show the Totals more obvious ... it would be nice not to have to [mouse over
 * the player]"): each hand's total is a tile of its own, the number as its count.
 */
class TwentyOneTotalTest {

    private static Card c(int rank) {
        return new Card(rank, 0);
    }

    @Test
    void aHandsTotalIsItsCountAndItsColour() {
        TwentyOneTotals.Total t = TwentyOneTotals.total(List.of(c(10), c(7)), false, true);
        assertEquals(Material.LIME_CONCRETE, t.material());
        assertEquals(17, t.count(), "the number reads at a glance");
        assertEquals("&aTotal: &f17", t.name());
        TwentyOneTotals.Total soft = TwentyOneTotals.total(List.of(c(1), c(6)), false, true);
        assertEquals("&aTotal: &f7 or 17", soft.name(), "a soft hand still in play says both");
        assertEquals(17, soft.count());
        assertEquals("&aTotal: &f17", TwentyOneTotals.total(List.of(c(1), c(6)), false, false).name(),
                "once it stands, its best");
        TwentyOneTotals.Total twentyOne = TwentyOneTotals.total(List.of(c(1), c(13)), false, true);
        assertEquals(Material.GOLD_BLOCK, twentyOne.material());
        assertEquals(21, twentyOne.count());
        TwentyOneTotals.Total over = TwentyOneTotals.total(List.of(c(10), c(9), c(5)), false, false);
        assertEquals(Material.RED_CONCRETE, over.material());
        assertEquals(24, over.count());
        TwentyOneTotals.Total shows = TwentyOneTotals.total(List.of(c(9)), true, false);
        assertEquals("&fShows 9", shows.name(), "the Arcade with its hole card down: what its up card shows");
        assertEquals(9, shows.count());
    }
}
