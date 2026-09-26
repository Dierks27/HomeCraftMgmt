package com.dierks.homecraft.gui.arcade;

import com.dierks.homecraft.arcade.TokenService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bits of the Arcade screens that are plain logic: wallet wording and the spin's pacing. */
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
}
