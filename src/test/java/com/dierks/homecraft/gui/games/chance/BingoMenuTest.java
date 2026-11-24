package com.dierks.homecraft.gui.games.chance;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bingo Night's screen copy (0.57.0): the prize says both parts, the cards in play and how it is shared; how it works
 * says the return as the band's one honest number and that NPCs' wins are never a player's; and none of it nudges.
 */
class BingoMenuTest {

    @Test
    void thePrizeSaysItsPartsAndHowItIsShared() {
        List<String> lines = BingoMenu.prizeLines(29, 43, true, 8, 8, false);
        assertEquals("&7A line: &629 tokens", lines.get(0));
        assertEquals("&7Then four corners: &643 tokens", lines.get(1));
        assertTrue(lines.get(2).contains("8 cards in play"), lines.toString());
        assertTrue(String.join(" ", lines).contains("same call"), lines.toString());
        List<String> dollars = BingoMenu.prizeLines(72, 0, false, 8, 8, true);
        assertEquals("&7A line: &6$72", dollars.get(0));
        assertFalse(String.join(" ", dollars).contains("corners"), "a round for the line only says only the line");
    }

    @Test
    void howItWorksSaysTheReturnAndNeverNudges() {
        List<String> lines = BingoMenu.howLines(0.9);
        String all = String.join(" ", lines);
        assertTrue(all.contains("about 90 of every 100"), all);
        assertTrue(all.contains("never yours"), "an NPC's win is never a player's: " + all);
        List<String> copy = new ArrayList<>(lines);
        copy.addAll(BingoMenu.prizeLines(29, 43, true, 8, 8, false));
        for (String line : copy) {
            String l = line.toLowerCase(Locale.ROOT);
            for (String banned : List.of("bet", "wager", "lucky", "jackpot", "try again", "one more", "almost")) {
                assertFalse(l.contains(banned), line);
            }
        }
    }
}
