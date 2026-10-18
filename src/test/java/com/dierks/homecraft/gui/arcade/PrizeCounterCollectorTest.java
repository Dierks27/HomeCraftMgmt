package com.dierks.homecraft.gui.arcade;

import com.dierks.homecraft.arcade.Collectors;
import com.dierks.homecraft.config.PluginConfig.Collector;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A collector's tile on the Prize Counter (HALLOWEEN-SPEC §3.1 row 12): its year, that it is kept,
 * and the last day it is sold; and the "Give a present" entry's place on the Cosmetics tab.
 */
class PrizeCounterCollectorTest {

    @Test
    void theCapsTileSaysItsYearThatItIsKeptAndWhenItLeaves() {
        assertEquals(List.of("&6Halloween 2026 collector", "&aYours for good!", "&7On sale until 31 Oct"),
                PrizeCounterMenu.collectorLines(Collectors.ROWS.get(0).collector()));
    }

    @Test
    void theTrailsTileLeavesAtTheEndOfNovember() {
        assertEquals(List.of("&6Halloween 2026 collector", "&aYours for good!", "&7On sale until 30 Nov"),
                PrizeCounterMenu.collectorLines(Collectors.ROWS.get(1).collector()));
    }

    @Test
    void theDateIsTheLocalDayWithoutAZeroOrAYear() {
        Collector c = new Collector(2027, LocalDate.of(2027, 12, 1), LocalDate.of(2028, 1, 6), true, null);
        assertEquals("&7On sale until 6 Jan", PrizeCounterMenu.collectorLines(c).get(2));
        assertEquals("&6Halloween 2027 collector", PrizeCounterMenu.collectorLines(c).get(0),
                "the year is the collector's, not the season's end");
    }

    @Test
    void theGiftEntryIsTheTopRightTileAndNoTab() {
        assertEquals(8, PrizeCounterMenu.GIFT_SLOT, "top right of the Cosmetics tab (D8 step 1)");
        for (int tab : new int[] {2, 3, 4, 5, 6, 7}) {
            assertTrue(tab != PrizeCounterMenu.GIFT_SLOT);
        }
        assertTrue(PrizeCounterMenu.GIFT_SLOT != 0, "slot 0 is the balance");
    }

    /**
     * Red-team M2: a collector's own tile is a plain buy, so a child tapping her own cap to look at it
     * can never reach a present. Read from the source: the counter opens the present picker from the
     * gift entry only, and its tile click has no collector branch.
     */
    @Test
    void aCollectorsOwnTileNeverLeadsToAPresent() throws IOException {
        Path src = Path.of("src", "main", "java", "com", "dierks", "homecraft", "gui", "arcade", "PrizeCounterMenu.java");
        String text = Files.readString(src, StandardCharsets.UTF_8);
        int opens = text.split("new GiftPickerMenu\\(", -1).length - 1;
        assertEquals(1, opens, "the present picker is opened from exactly one place");
        int entry = text.indexOf("set(GIFT_SLOT");
        assertTrue(entry >= 0 && text.indexOf("new GiftPickerMenu(") > entry
                && text.indexOf("new GiftPickerMenu(") < text.indexOf(';', entry), "and that place is the gift tile");
        int click = text.indexOf("private void click(Prize p)");
        int end = text.indexOf("private void buy(Prize p)");
        assertTrue(click > 0 && end > click);
        assertFalse(text.substring(click, end).contains("collector"), "the tile's click is today's buy, unchanged");
    }
}
