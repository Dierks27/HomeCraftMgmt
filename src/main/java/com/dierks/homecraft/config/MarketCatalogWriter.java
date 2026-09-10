package com.dierks.homecraft.config;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.market.MarketDraft;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Writes one {@code market.catalog} row back to config.yml.
 *
 * <p>Unlike {@link MiniCatalogWriter}, this deliberately does a targeted
 * read-modify-write of the rows as they sit ON DISK rather than re-serialising the parsed
 * catalog. {@code PluginConfig} drops rows it cannot parse and normalises the ones it
 * keeps, so rebuilding the list from {@code MarketService.catalog()} would quietly delete
 * an admin's broken row and bake the parser's clamps into their file — the same class of
 * silent data loss as the two config bugs fixed in 0.21.1 and 0.21.2. Editing one row
 * leaves every other row, and every key this plugin does not know about, exactly as it was.
 */
public final class MarketCatalogWriter {

    private final HomeCraftManagement plugin;

    public MarketCatalogWriter(HomeCraftManagement plugin) {
        this.plugin = plugin;
    }

    /** Insert or replace one row by id. @return false when nothing was written. */
    public boolean upsert(MarketDraft draft) {
        List<Map<String, Object>> rows = plugin.readCatalogRows();
        if (rows == null || draft.id() == null) {
            return false; // unreadable config.yml — never write a catalog over a file we failed to read
        }
        int at = indexOf(rows, draft.id());
        if (at < 0) {
            rows.add(draft.toRow());
        } else {
            // putAll rather than replace, so keys the plugin does not model survive.
            rows.get(at).putAll(draft.toRow());
            if (draft.displayName() == null) {
                rows.get(at).remove("display_name");
            }
        }
        return plugin.writeConfig("market.catalog", rows);
    }

    /** Remove one row by id. @return false when nothing was written. */
    public boolean remove(String id) {
        List<Map<String, Object>> rows = plugin.readCatalogRows();
        if (rows == null || id == null) {
            return false;
        }
        int at = indexOf(rows, id);
        if (at < 0) {
            return false;
        }
        rows.remove(at);
        // An empty LIST, never null: null would delete the key, and the jar's six shipped
        // commodities would then answer for it through the attached defaults.
        return plugin.writeConfig("market.catalog", rows);
    }

    /** Index of the row whose id matches, normalised the same way the parser normalises. */
    private static int indexOf(List<Map<String, Object>> rows, String id) {
        for (int i = 0; i < rows.size(); i++) {
            Object raw = rows.get(i).get("id");
            if (raw != null && String.valueOf(raw).trim().toLowerCase(Locale.ROOT).equals(id)) {
                return i;
            }
        }
        return -1;
    }
}
