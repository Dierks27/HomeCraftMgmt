package com.dierks.homecraft.arcade;

import com.dierks.homecraft.config.PluginConfig.Icon;
import com.dierks.homecraft.config.PluginConfig.OnlyIf;
import com.dierks.homecraft.config.PluginConfig.Prize;
import com.dierks.homecraft.config.PluginConfig.PrizeExtra;
import com.dierks.homecraft.config.PluginConfig.PrizeTab;
import com.dierks.homecraft.config.PluginConfig.PrizeType;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A rented look's key (SHOP-V2-SPEC §3.6): the facts in its NAME ("Lime Cap - until 18 Jan", D32) and its lore in
 * order: the row's lines, its last day, that only its owner can wear it, that it stays put, how to wear it.
 */
class LookKeyTest {

    private static final Prize LIME = new Prize("cap_lime", PrizeTab.LOOKS, "&aLime Cap",
            List.of("A comfy cap to wear!", "Yours for 90 days."), 60, new Icon("", Material.LEATHER_HELMET),
            PrizeType.HAT, true, null, 1, DyeColor.LIME, null, null, null, 0, 0, null, 90, "", List.of(), List.of(),
            null, null, List.of(), null, new PrizeExtra(Material.LEATHER_HELMET, 0, null, OnlyIf.ANY, 0, null));

    @Test
    void theFactsAreInTheName() {
        assertEquals("&aLime Cap &7- until 18 Jan", PrizeItems.lookKeyName(LIME, "18 Jan"));
    }

    @Test
    void theLoreInOrder() {
        assertEquals(List.of("&7A comfy cap to wear!", "&7Yours for 90 days.", "&7Yours until 18 Jan",
                        "&7Only you can wear it.", "&8Can't be dropped, traded or put away.",
                        "&7Wear it! Right-click to put it on.", "&8Arcade prize"),
                PrizeItems.lookKeyLore(LIME, "18 Jan"));
    }

    @Test
    void aCapIsItsColoursDye() {
        assertEquals(DyeColor.LIME.getColor().asRGB(), PrizeItems.capOf(LIME).rgb());
        assertEquals(0xFFFFFF, PrizeItems.capRgb(null), "no colour: white");
    }
}
