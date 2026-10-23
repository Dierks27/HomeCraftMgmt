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
 * A collector's tile on the Prize Counter (HALLOWEEN-SPEC §3.1 row 12; SHOP-V2-SPEC §5.7): its season and
 * year, that it is kept, and the last day it is sold; and the "Give a present" entry's place on the
 * Cosmetics tab.
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
    void theSantaCapsTileSaysChristmas() {
        assertEquals(List.of("&cChristmas 2026 collector", "&aYours for good!", "&7On sale until 31 Dec"),
                PrizeCounterMenu.collectorLines(row("cap_santa_2026").collector()));
    }

    @Test
    void theTwinkleTileLeavesOnTheSixthOfJanuary() {
        assertEquals(List.of("&cChristmas 2026 collector", "&aYours for good!", "&7On sale until 6 Jan"),
                PrizeCounterMenu.collectorLines(row("trail_twinkle_2026").collector()),
                "the 2026 collector, though it is sold into 2027");
    }

    @Test
    void theDateIsTheLocalDayWithoutAZeroOrAYear() {
        Collector c = new Collector(2027, LocalDate.of(2027, 12, 1), LocalDate.of(2028, 1, 6), true, null);
        assertEquals("&7On sale until 6 Jan", PrizeCounterMenu.collectorLines(c).get(2));
        assertEquals("&cChristmas 2027 collector", PrizeCounterMenu.collectorLines(c).get(0),
                "the year is the collector's, not the season's end");
    }

    @Test
    void eachSeasonHasItsOwnLineAndColour() {
        assertEquals("&dSpring 2027 collector", PrizeCounterMenu.collectorLines(
                new Collector(2027, LocalDate.of(2027, 4, 1), LocalDate.of(2027, 5, 31), true, null)).get(0));
        assertEquals("&eSummer 2027 collector", PrizeCounterMenu.collectorLines(
                new Collector(2027, LocalDate.of(2027, 7, 1), LocalDate.of(2027, 8, 31), true, null)).get(0));
        assertEquals("&6Halloween 2027 collector", PrizeCounterMenu.collectorLines(
                new Collector(2027, LocalDate.of(2027, 10, 1), LocalDate.of(2027, 10, 31), true, null)).get(0));
    }

    private static com.dierks.homecraft.config.PluginConfig.Prize row(String id) {
        return Collectors.ROWS.stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void theGiftEntryIsInTheBottomRowAndNoTab() {
        assertEquals(47, PrizeCounterMenu.GIFT_SLOT, "0.38: the bottom row, since the eight tabs fill row 0 "
                + "(SHOP-V2-SPEC §5.9; PrizeTabSlotsTest has the whole plan)");
        for (int tab : PrizeCounterMenu.TAB_SLOTS) {
            assertTrue(tab != PrizeCounterMenu.GIFT_SLOT);
        }
        assertTrue(PrizeCounterMenu.GIFT_SLOT != 0, "slot 0 is the balance");
    }

    @Test
    void theGiftEntryShowsOnTheCosmeticsAndLooksTabs() {
        for (com.dierks.homecraft.config.PluginConfig.PrizeTab t
                : com.dierks.homecraft.config.PluginConfig.PrizeTab.values()) {
            boolean where = t == com.dierks.homecraft.config.PluginConfig.PrizeTab.COSMETICS
                    || t == com.dierks.homecraft.config.PluginConfig.PrizeTab.LOOKS;
            assertEquals(where, PrizeCounterMenu.giftTab(t), t + ": the caps are on Looks now, Twinkle on Cosmetics");
        }
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
        int end = text.indexOf("private void buy(Prize p, int shown)");
        assertTrue(click > 0 && end > click);
        assertFalse(text.substring(click, end).contains("collector"), "the tile's click is today's buy, unchanged");
    }
}
