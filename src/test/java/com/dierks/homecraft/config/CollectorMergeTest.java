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
 * and never replaced by a config row with their id; and the two switches as the bundled file ships them. Since 0.38
 * (SHOP-V2-SPEC §5.1) the Keepers come before them: Keepers, then collectors, then config rows.
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
        int k = com.dierks.homecraft.arcade.Keepers.ROWS.size();
        assertEquals(7, k, "the seven Keepers ship (S4)");
        assertEquals(k + 5, merged.size());
        assertEquals(com.dierks.homecraft.arcade.Keepers.ROWS, merged.subList(0, k), "the Keepers first");
        assertEquals("cap_pumpkin_2026", merged.get(k).id());
        assertEquals("trail_leaves_2026", merged.get(k + 1).id());
        assertEquals("cap_santa_2026", merged.get(k + 2).id());
        assertEquals("trail_twinkle_2026", merged.get(k + 3).id());
        assertSame(heart, merged.get(k + 4), "config rows follow, in their order");
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
        merged = merged.subList(com.dierks.homecraft.arcade.Keepers.ROWS.size(), merged.size());
        assertEquals(List.of("cap_pumpkin_2026", "trail_leaves_2026", "cap_santa_2026", "trail_twinkle_2026",
                        "trail_heart"), merged.stream().map(Prize::id).toList());
        assertEquals(250, merged.get(0).costTokens(), "the collector stays the catalogue's");
        assertNotNull(merged.get(0).collector());
        assertEquals(400, merged.get(1).costTokens());
        assertEquals(4, warnings.size());
        assertEquals(Collectors.skipWarning("cap_pumpkin_2026"), warnings.get(0));
        assertTrue(warnings.get(2).contains("'candy_apple_2026'"));
    }

    /**
     * 0.38 (SHOP-V2-SPEC §5.1): the Keepers come first, then the collectors, then the config rows; a config row with
     * a {@code keeper_} id is skipped with one WARN, whether or not a Keeper of that id has shipped yet.
     */
    @Test
    void theKeepersComeFirstAndKeepTheirIds() {
        List<String> warnings = new ArrayList<>();
        List<Prize> merged = PluginConfig.mergeCollectors(List.of(
                row("keeper_legend_crown", PrizeType.HAT, 10),
                row("Keeper_My_Own", PrizeType.TRAIL, 1),
                row("trail_heart", PrizeType.TRAIL, 100)), warnings::add);
        List<Prize> expected = new ArrayList<>(com.dierks.homecraft.arcade.Keepers.ROWS);
        expected.addAll(Collectors.ROWS);
        assertEquals(expected, merged.subList(0, expected.size()), "Keepers, then collectors");
        assertEquals("trail_heart", merged.get(merged.size() - 1).id(), "then the config rows");
        assertEquals(expected.size() + 1, merged.size(), "both keeper_ rows skipped");
        assertEquals(List.of(com.dierks.homecraft.arcade.Keepers.skipWarning("keeper_legend_crown"),
                com.dierks.homecraft.arcade.Keepers.skipWarning("Keeper_My_Own")), warnings);
        assertEquals(5000, merged.get(0).costTokens(), "the Crown stays the catalogue's, never the config row's 10");
        assertTrue(merged.get(0).keeper());
    }

    /**
     * A config trail drawn with a Keeper's particle (§5.3) still loads, with one WARN naming it as reserved; any other
     * particle loads quietly.
     */
    @Test
    void aConfigTrailWithAKeepersParticleLoadsWithAWarning() {
        List<String> warnings = new ArrayList<>();
        Prize totem = new Prize("trail_totem", PrizeTab.COSMETICS, "&6Totem Trail", List.of(), 100,
                new Icon("", Material.SUGAR), PrizeType.TRAIL, true, null, 1, null, null, null, null, 0, 0,
                "totem_of_undying", 30, "", List.of(), List.of(), null, null, List.of(), null);
        Prize heart = row("trail_heart", PrizeType.TRAIL, 100);
        List<Prize> merged = PluginConfig.mergeCollectors(List.of(totem, heart), warnings::add);
        assertTrue(merged.contains(totem), "it loads");
        assertTrue(merged.contains(heart));
        assertEquals(List.of(com.dierks.homecraft.arcade.Keepers.reservedWarning("trail_totem", "totem_of_undying")),
                warnings, "one WARN, for the reserved one only");
        assertTrue(warnings.get(0).contains("reserved for a Keeper"));
    }

    @Test
    void noShippedRowIsReservedOrACollector() throws Exception {
        for (Map<?, ?> m : bundled().getMapList("arcade.prizes")) {
            String id = String.valueOf(m.get("id"));
            assertFalse(Collectors.reserved(id), "the bundled row '" + id + "' would be skipped");
            assertFalse(com.dierks.homecraft.arcade.Keepers.reserved(id), "the bundled row '" + id + "' is no Keeper");
        }
        List<Prize> merged = PluginConfig.mergeCollectors(List.of(row("trail_heart", PrizeType.TRAIL, 100)), null);
        for (Prize p : merged.subList(com.dierks.homecraft.arcade.Keepers.ROWS.size() + Collectors.ROWS.size(),
                merged.size())) {
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
