package com.dierks.homecraft.gui.games.chance;

import com.dierks.homecraft.games.chance.twentyone.TwentyOneHand;
import com.dierks.homecraft.games.chance.twentyone.TwentyOneHand.Card;
import org.bukkit.Material;

import java.util.List;

/**
 * 0.57.1: what Twenty-One's total tile shows ({@link TwentyOneTableMenu#totalTile}), kept apart from the screen so it
 * is pure (the owner: "can we show the Totals more obvious").
 */
final class TwentyOneTotals {

    private TwentyOneTotals() {
    }

    /** What a total tile shows (pure): its block, its name and its count. */
    record Total(Material material, String name, int count) {
    }

    /** A hand's total: green, gold at 21, red over 21; the Arcade's up card while its hole card is down. */
    static Total total(List<Card> cards, boolean upCardOnly, boolean playing) {
        int b = TwentyOneHand.best(cards);
        if (upCardOnly) {
            return new Total(Material.LIGHT_GRAY_CONCRETE, "&fShows " + b, Math.max(1, b));
        }
        if (b > 21) {
            return new Total(Material.RED_CONCRETE, "&cOver 21 &7(" + b + ")", Math.min(64, b));
        }
        if (b == 21) {
            return new Total(Material.GOLD_BLOCK, "&e&l21!", 21);
        }
        if (playing && TwentyOneHand.soft(cards)) {
            return new Total(Material.LIME_CONCRETE, "&aTotal: &f" + (b - 10) + " or " + b, b);
        }
        return new Total(Material.LIME_CONCRETE, "&aTotal: &f" + b, Math.max(1, b));
    }

}
