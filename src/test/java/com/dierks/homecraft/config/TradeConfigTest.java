package com.dierks.homecraft.config;

import com.dierks.homecraft.games.gen.LayoutFixtures;
import com.dierks.homecraft.trade.TradeRules;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The trade window's settings (SHOP-V2-SPEC §4.14): the bundled {@code trade:} section reads as the shipped values
 * with not a word; a missing one too; every number is squeezed into its range with one WARN naming the key; and the
 * extra refusals are read. ({@code TradeBackfillTest}: an older
 * file gets the section from the backfill.)
 */
class TradeConfigTest {

    @Test
    void theBundledSectionIsTheShippedValuesWithoutAWord() {
        List<String> warned = new ArrayList<>();
        PluginConfig.Trade t = PluginConfig.readTrade(LayoutFixtures.bundled().getConfigurationSection("trade"),
                warned::add);
        assertEquals(PluginConfig.Trade.DEFAULTS, t);
        assertEquals(List.of(), warned);
        assertTrue(t.enabled());
        assertEquals(30, t.requestSeconds());
        assertEquals(3, t.countdownSeconds());
        assertEquals(1_500, t.changeLockMs());
        assertEquals(0, t.maxDistance(), "anywhere in the same world");
        assertTrue(t.money());
        assertEquals(90, t.logKeepDays());
        assertEquals(TradeRules.Refuse.NONE, t.refuse());
        assertEquals(PluginConfig.Trade.DEFAULTS, PluginConfig.readTrade(null, warned::add), "no section: the same");
    }

    @Test
    void numbersAreSqueezedWithOneWarnEach() throws Exception {
        YamlConfiguration c = new YamlConfiguration();
        c.loadFromString("""
                trade:
                  enabled: false
                  request_seconds: 5
                  countdown_seconds: 30
                  change_lock_ms: -2
                  max_distance: 12
                  money: false
                  log_keep_days: 0
                  refuse_items: [DRAGON_EGG, "pdc:souls:bound", "not a thing"]
                """);
        List<String> warned = new ArrayList<>();
        PluginConfig.Trade t = PluginConfig.readTrade(c.getConfigurationSection("trade"), warned::add);
        assertFalse(t.enabled());
        assertEquals(10, t.requestSeconds());
        assertEquals(10, t.countdownSeconds());
        assertEquals(0, t.changeLockMs());
        assertEquals(12, t.maxDistance());
        assertFalse(t.money(), "items only");
        assertEquals(0, t.logKeepDays(), "keep the log for ever");
        assertEquals(Set.of("DRAGON_EGG"), t.refuse().materials());
        assertEquals(Set.of("souls:bound"), t.refuse().keys());
        assertEquals(4, warned.size(), String.join("\n", warned));
        assertTrue(warned.get(0).startsWith("trade.request_seconds: 5 is outside 10-600"), warned.get(0));
    }
}
