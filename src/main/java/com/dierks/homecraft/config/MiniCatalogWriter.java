package com.dierks.homecraft.config;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.mini.MiniDef;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serializes the in-memory Mini catalog back into {@code minis.series} and saves
 * config.yml, so the Admin Studio can edit/add/remove Minis without hand-editing
 * YAML. Every field is written explicitly per entry so it round-trips exactly
 * through {@link PluginConfig#load()}. Only {@code minis.series} is touched —
 * rarity styles, categories, and pricing mode are left untouched.
 */
public final class MiniCatalogWriter {

    private final HomeCraftManagement plugin;

    public MiniCatalogWriter(HomeCraftManagement plugin) {
        this.plugin = plugin;
    }

    /** Write the whole catalog (grouped by series, preserving encounter order) and save. */
    public void write(List<MiniDef> defs) {
        // Preserve series order by first appearance.
        Map<String, List<MiniDef>> bySeries = new LinkedHashMap<>();
        for (MiniDef def : defs) {
            bySeries.computeIfAbsent(def.series(), k -> new ArrayList<>()).add(def);
        }

        List<Map<String, Object>> series = new ArrayList<>();
        for (Map.Entry<String, List<MiniDef>> entry : bySeries.entrySet()) {
            Map<String, Object> seriesMap = new LinkedHashMap<>();
            seriesMap.put("name", entry.getKey());
            // Series-level rarity is a fallback only; every entry writes its own,
            // so this is just a sensible default (the first entry's rarity).
            seriesMap.put("rarity", entry.getValue().get(0).rarity().name());

            List<Map<String, Object>> entries = new ArrayList<>();
            for (MiniDef def : entry.getValue()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", def.id());
                m.put("name", def.name());
                m.put("category", def.category());
                m.put("rarity", def.rarity().name());
                m.put("type", def.type().name());
                m.put("texture", def.texture() == null ? "" : def.texture());
                m.put("cap", def.cap());
                m.put("price", def.price());
                m.put("craftable", def.craftable());
                entries.add(m);
            }
            seriesMap.put("entries", entries);
            series.add(seriesMap);
        }

        FileConfiguration c = plugin.getConfig();
        c.set("minis.series", series);
        plugin.saveConfig();
    }
}
