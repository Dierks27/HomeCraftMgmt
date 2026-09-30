package com.dierks.homecraft.gui.arcade;

import com.dierks.homecraft.arcade.ArcadeService;
import com.dierks.homecraft.arcade.TokenService;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bits of the Arcade screens that are plain logic: wallet wording, the spin's pacing, and what
 * a Scratch Ticket's squares show.
 */
class ArcadeScreensTest {

    @Test
    void ledgerLinesReadLikeSentences() {
        assertEquals("+5 Quest: Catch 8 fish", WalletMenu.friendly(5, TokenService.Source.QUEST, "Catch 8 fish"));
        assertEquals("−25 Mini Radar", WalletMenu.friendly(-25, TokenService.Source.PRIZE, "&bMini Radar"),
                "a prize line is just the prize, without its colour codes");
        assertEquals("+2 Login streak", WalletMenu.friendly(2, TokenService.Source.LOGIN_STREAK, null));
        assertEquals("−10 Scratch Ticket: Scratch Ticket",
                WalletMenu.friendly(-10, TokenService.Source.LOTTO, "Scratch Ticket"));
        assertEquals("+3 Tokens", WalletMenu.friendly(3, null, ""), "an unknown source still reads");
    }

    @Test
    void theSpinSlowsDownAndTakesAboutItsTime() {
        for (int frames : new int[] {6, 24}) {
            long[] d = CrateSpinMenu.delays(60, frames);
            assertEquals(frames, d.length);
            long sum = 0;
            for (int i = 0; i < d.length; i++) {
                assertTrue(d[i] >= 1, "every step waits at least a tick");
                if (i > 0) {
                    assertTrue(d[i] >= d[i - 1], "it never speeds up: " + java.util.Arrays.toString(d));
                }
                sum += d[i];
            }
            assertTrue(sum >= 50 && sum <= 72, frames + " frames took " + sum + " ticks, not about 60");
            assertTrue(d[d.length - 1] > d[0], "the last step is slower than the first");
        }
    }

    @Test
    void aTicketThatGaveSomeBackShowsThreePlainSquaresNotANearMiss() {
        Set<Material> winning = Set.of(Material.SUNFLOWER, Material.NETHER_STAR);
        Material[] back = ScratchTicketMenu.symbols(new ArcadeService.Outcome(true, null, null, "x", false, false, 3));
        assertEquals(3, back.length, "three squares");
        for (Material m : back) {
            assertFalse(winning.contains(m), "a part refund shows no winning symbol at all (it used to show two suns "
                    + "and a coal: an engineered near miss)");
            assertEquals(back[0], m, "the three squares are the same plain \"Tokens back\" square");
        }

        Material[] none = ScratchTicketMenu.symbols(new ArcadeService.Outcome(true, null, null, "x", false, false));
        assertEquals(3, Set.of(none).size(), "nothing back: three different things, no pair");
        for (Material m : none) {
            assertFalse(winning.contains(m), "a loss shows no winning symbol");
        }
        assertArrayEquals(new Material[] {Material.SUNFLOWER, Material.SUNFLOWER, Material.SUNFLOWER},
                ScratchTicketMenu.symbols(new ArcadeService.Outcome(true, null, null, "x", true, false)),
                "a win is three suns");
        assertArrayEquals(new Material[] {Material.NETHER_STAR, Material.NETHER_STAR, Material.NETHER_STAR},
                ScratchTicketMenu.symbols(new ArcadeService.Outcome(true, null, null, "x", true, true)),
                "the top prize is three stars");
    }
}
