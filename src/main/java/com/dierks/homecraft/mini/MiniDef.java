package com.dierks.homecraft.mini;

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
 */
public record MiniDef(String id, String name, String series, String category, Rarity rarity,
                      MiniType type, String texture, long cap, double price, boolean craftable) {

    /** True if this Mini has no mint cap. */
    public boolean uncapped() {
        return cap < 0;
    }
}
