package com.dierks.homecraft.mini;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * A catalog entry — the admin-defined <em>type</em> of a Mini (config-driven).
 * The moving mint count + per-copy provenance live in the datastore; each minted
 * copy is a unique tagged item.
 *
 * @param id        unique key (used in commands / storage)
 * @param name      display name
 * @param series    the collection it belongs to
 * @param category  the "Type" / subject (Animal, Food, …) for browsing/filtering
 * @param rarity    tier (drives style + defaults)
 * @param type      HEAD or ARMOR_STAND
 * @param texture   Base64 head texture value (may be empty)
 * @param cap       mint cap (-1 = uncapped)
 * @param price     mint price
 * @param craftable whether it can be crafted at the Mini Workbench (recipe wiring: later)
 * @param tags      free-form drop tags ({@code mining}, {@code fish}, …); a loot source
 *                  may target a tag pool. An untagged Mini never drops from a tag pool.
 * @param eventOnly a holiday Mini: only event code mints it, between {@code earnFrom} and
 *                  {@code earnTo}; no pack, crate, Rare Card or wild drop ever gives it
 *                  (see {@link EventMinis})
 * @param earnFrom  the first local day an event can give it (null = not set)
 * @param earnTo    the last local day an event can give it; after it, closed for good (null = not set)
 */
public record MiniDef(String id, String name, String series, String category, Rarity rarity,
                      MiniType type, String texture, long cap, double price, boolean craftable,
                      List<String> tags, boolean eventOnly, LocalDate earnFrom, LocalDate earnTo) {

    public MiniDef {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }

    /** An ordinary Mini (not a holiday one): the constructor every pre-0.38.6 call site uses. */
    public MiniDef(String id, String name, String series, String category, Rarity rarity,
                   MiniType type, String texture, long cap, double price, boolean craftable,
                   List<String> tags) {
        this(id, name, series, category, rarity, type, texture, cap, price, craftable, tags, false, null, null);
    }

    /** Pre-tags constructor (kept for the importer / older call sites). */
    public MiniDef(String id, String name, String series, String category, Rarity rarity,
                   MiniType type, String texture, long cap, double price, boolean craftable) {
        this(id, name, series, category, rarity, type, texture, cap, price, craftable, List.of());
    }

    /** True if this Mini has no mint cap. */
    public boolean uncapped() {
        return cap < 0;
    }

    /** Case-insensitive tag test. */
    public boolean hasTag(String tag) {
        if (tag == null || tag.isBlank()) {
            return false;
        }
        String t = tag.trim().toLowerCase(Locale.ROOT);
        for (String mine : tags) {
            if (mine.equalsIgnoreCase(t)) {
                return true;
            }
        }
        return false;
    }
}
