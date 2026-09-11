package com.dierks.homecraft.gui;

import org.bukkit.Material;

import java.util.Locale;
import java.util.Map;

/**
 * The icon shown on each department tab, in the Store, the instant Market and the Crate
 * Marketplace alike — so a department wears the same face wherever a player meets it.
 *
 * <p>These are real items on purpose. The tabs originally used stained-glass panes, colour-coded
 * for selection, and every unselected one was invisible: a pane is a thin, semi-transparent sprite
 * and light grey is the colour of the slot behind it. An inventory item's display name only appears
 * on hover, so a tile whose material does not read is a tile with no information on it at all.
 *
 * <p>Two rules hold this map together, and the test pins both. Every icon must be OPAQUE and
 * saturated — no panes, no greyscale. And no icon may repeat another department's, or any material
 * already used by the surrounding menu chrome, because a tab that looks like the balance tile or
 * like a commodity in the grid below it is a misclick waiting to happen: one filters a list, the
 * other spends money.
 *
 * <p>No Bukkit call beyond {@link Material} enum access lives here, which is what keeps it
 * unit-testable — on Paper 26.2 the Material predicates resolve through the registry and throw
 * without a running server.
 */
public final class DepartmentIcons {

    /** Tab icon for a department name that is not one of the shipped ones. */
    public static final Material FALLBACK = Material.BOOKSHELF;

    private static final Map<String, Material> ICONS = Map.of(
            "all", Material.NETHER_STAR,
            "blocks", Material.GRASS_BLOCK,
            "materials", Material.AMETHYST_SHARD,
            "food", Material.BREAD,
            "tools", Material.DIAMOND_PICKAXE,
            "combat", Material.IRON_SWORD,
            "redstone", Material.REDSTONE,
            "collectibles", Material.ZOMBIE_HEAD,
            "misc", Material.BARREL);

    private DepartmentIcons() {
    }

    /**
     * The icon for a department, or {@link #FALLBACK} for a name an admin invented. Matching is
     * case-insensitive because {@code marketplace.departments} is hand-written.
     */
    public static Material of(String department) {
        if (department == null || department.isBlank()) {
            return FALLBACK;
        }
        return ICONS.getOrDefault(department.trim().toLowerCase(Locale.ROOT), FALLBACK);
    }

    /** The department names this map covers, lowercased. Exposed for the test. */
    static java.util.Set<String> known() {
        return ICONS.keySet();
    }
}
