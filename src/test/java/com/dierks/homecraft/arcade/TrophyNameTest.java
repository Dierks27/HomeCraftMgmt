package com.dierks.homecraft.arcade;

import com.dierks.homecraft.config.PluginConfig.Icon;
import com.dierks.homecraft.config.PluginConfig.LimitPer;
import com.dierks.homecraft.config.PluginConfig.Prize;
import com.dierks.homecraft.config.PluginConfig.PrizeLimit;
import com.dierks.homecraft.config.PluginConfig.PrizeTab;
import com.dierks.homecraft.config.PluginConfig.PrizeType;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Trophy names (P1, SHOP-V2-SPEC §5.9): each trophy is named from its row with its number ("Bronze Trophy #3"),
 * the Trophy of the Month for the local month ("November 2026 Trophy #4"), its lore says "Bought by"; every trophy
 * row has its own serial while the Arcade Trophy keeps {@code trophy_serial}; the broadcast goes out at most once a
 * minute a player.
 */
class TrophyNameTest {

    private static Prize trophy(String id, String display, PrizeLimit limit) {
        return new Prize(id, PrizeTab.TROPHIES, display, List.of("A numbered trophy", "with your name on it."), 300,
                new Icon("", Material.COPPER_BLOCK), PrizeType.TROPHY, true, limit, 1, null, null, null, null, 0, 0,
                null, 0, "", List.of(), List.of(), null, null, List.of(), null);
    }

    @Test
    void aLadderTrophyIsNamedFromItsRow() {
        assertEquals("&6&lBronze Trophy #3", PrizeItems.trophyName(trophy("trophy_bronze", "&6Bronze Trophy",
                new PrizeLimit(LimitPer.LIFETIME, 1)), 3, LocalDate.of(2026, 11, 21)));
        assertEquals("&6&lArcade Trophy #12", PrizeItems.trophyName(trophy("arcade_trophy", "&6Arcade Trophy",
                new PrizeLimit(LimitPer.LIFETIME, 99)), 12, LocalDate.of(2026, 11, 21)), "as it always was");
        assertEquals("&6&lLegend Trophy #1", PrizeItems.trophyName(trophy("trophy_legend", "&5&lLegend Trophy", null),
                1, null), "the row's colours are left out");
    }

    @Test
    void theTrophyOfTheMonthIsNamedForTheLocalMonth() {
        Prize month = trophy("trophy_month", "&eTrophy of the Month", new PrizeLimit(LimitPer.MONTH, 1));
        assertEquals("&6&lNovember 2026 Trophy #4", PrizeItems.trophyName(month, 4, LocalDate.of(2026, 11, 30)));
        assertEquals("&6&lDecember 2026 Trophy #5", PrizeItems.trophyName(month, 5, LocalDate.of(2026, 12, 1)));
        assertEquals("&6&lJanuary 2027 Trophy #6", PrizeItems.trophyName(month, 6, LocalDate.of(2027, 1, 1)));
    }

    @Test
    void theLoreSaysBoughtByNeverWon() {
        List<String> lore = PrizeItems.trophyLore(trophy("trophy_bronze", "&6Bronze Trophy", null), "a player");
        assertEquals("&7Bought by &fa player", lore.get(0));
        assertEquals(List.of("&7Bought by &fa player", "&7A numbered trophy", "&7with your name on it.",
                "&7Put it on a shelf!", "&8Arcade prize"), lore);
        assertTrue(lore.stream().noneMatch(l -> l.contains("Won")), "a buy is never \"won\"");
    }

    @Test
    void everyTrophyRowHasItsOwnSerialAndTheArcadeTrophyKeepsItsOld() {
        assertEquals("trophy_serial", PrizeService.trophySerialKey("arcade_trophy"));
        assertEquals("trophy_serial:trophy_bronze", PrizeService.trophySerialKey("trophy_bronze"));
        assertEquals("trophy_serial:trophy_month", PrizeService.trophySerialKey("trophy_month"));
    }

    @Test
    void theBroadcastGoesOutAtMostOnceAMinuteAPlayer() {
        long now = 10_000_000;
        assertTrue(PrizeService.announceDue(null, now), "the first one");
        assertFalse(PrizeService.announceDue(now - 1_000, now), "a second within the minute is not");
        assertFalse(PrizeService.announceDue(now - 59_999, now));
        assertTrue(PrizeService.announceDue(now - 60_000, now), "a minute later it is");
    }
}
