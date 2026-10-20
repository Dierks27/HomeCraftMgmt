package com.dierks.homecraft.config;

import com.dierks.homecraft.arcade.Collectors;
import com.dierks.homecraft.config.PluginConfig.Icon;
import com.dierks.homecraft.config.PluginConfig.Prize;
import com.dierks.homecraft.config.PluginConfig.PrizeTab;
import com.dierks.homecraft.config.PluginConfig.PrizeType;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The collectors merged into the Prize Counter's rows (HALLOWEEN-SPEC §2.1, D1, D20): first, always,
 * and never replaced by a config row with their id; and the two switches as the bundled file ships them.
 */
class CollectorMergeTest {

    private static YamlConfiguration bundled() throws Exception {
        try (InputStream in = CollectorMergeTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "the bundled config.yml is on the test classpath");
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                YamlConfiguration c = new YamlConfiguration();
                c.load(r);
                return c;
            }
        }
    }

    private static Prize row(String id, PrizeType type, int cost) {
        return new Prize(id, PrizeTab.COSMETICS, "&f" + id, List.of(), cost, new Icon("", Material.SUGAR), type, true,
                null, 1, null, null, null, null, 0, 0, type == PrizeType.TRAIL ? "HEART" : null,
                type == PrizeType.TRAIL ? 7 : 0, "", List.of(), List.of(), null, null, List.of(), null);
    }

    @Test
    void theCollectorsComeFirst() {
        Prize heart = row("trail_heart", PrizeType.TRAIL, 100);
        List<Prize> merged = PluginConfig.mergeCollectors(List.of(heart), null);
        assertEquals(3, merged.size());
        assertEquals("cap_pumpkin_2026", merged.get(0).id());
        assertEquals("trail_leaves_2026", merged.get(1).id());
        assertSame(heart, merged.get(2), "config rows follow, in their order");
    }

    @Test
    void aHandAddedRowWithACollectorIdIsSkipped() {
        List<String> warnings = new ArrayList<>();
        List<Prize> merged = PluginConfig.mergeCollectors(List.of(
                row("cap_pumpkin_2026", PrizeType.HAT, 60),
                row("Trail_Leaves_2026", PrizeType.TRAIL, 1),
                row("candy_apple_2026", PrizeType.BOOST, 5),
                row("bag_trick_or_treat_2026", PrizeType.BOOST, 5),
                row("trail_heart", PrizeType.TRAIL, 100)), warnings::add);
        assertEquals(List.of("cap_pumpkin_2026", "trail_leaves_2026", "trail_heart"),
                merged.stream().map(Prize::id).toList());
        assertEquals(250, merged.get(0).costTokens(), "the collector stays the catalogue's");
        assertNotNull(merged.get(0).collector());
        assertEquals(400, merged.get(1).costTokens());
        assertEquals(4, warnings.size());
        assertEquals(Collectors.skipWarning("cap_pumpkin_2026"), warnings.get(0));
        assertTrue(warnings.get(2).contains("'candy_apple_2026'"));
    }

    @Test
    void noShippedRowIsReservedOrACollector() throws Exception {
        for (Map<?, ?> m : bundled().getMapList("arcade.prizes")) {
            String id = String.valueOf(m.get("id"));
            assertFalse(Collectors.reserved(id), "the bundled row '" + id + "' would be skipped");
        }
        List<Prize> merged = PluginConfig.mergeCollectors(List.of(row("trail_heart", PrizeType.TRAIL, 100)), null);
        for (Prize p : merged.subList(2, merged.size())) {
            assertNull(p.collector(), "a config row is never a collector");
        }
    }

    @Test
    void theSwitchesShipOn() throws Exception {
        YamlConfiguration c = bundled();
        assertTrue(c.isBoolean("arcade.collectors.on_sale"));
        assertTrue(c.getBoolean("arcade.collectors.on_sale"));
        assertTrue(c.isBoolean("arcade.collectors.gifting"));
        assertTrue(c.getBoolean("arcade.collectors.gifting"));
        YamlConfiguration off = new YamlConfiguration();
        off.loadFromString("arcade:\n  collectors:\n    on_sale: false\n    gifting: false\n");
        assertFalse(off.getBoolean("arcade.collectors.on_sale", true));
        assertFalse(off.getBoolean("arcade.collectors.gifting", true));
        assertTrue(new YamlConfiguration().getBoolean("arcade.collectors.gifting", true), "missing reads on");
    }
}
