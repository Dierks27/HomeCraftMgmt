package com.dierks.homecraft.gui;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.market.MarketItem;
import com.dierks.homecraft.market.MarketService;
import com.dierks.homecraft.market.MarketState;
import com.dierks.homecraft.marketplace.Categorizer;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The department tab row shared by the Store and the instant Market, plus the filtering
 * and sorting behind it.
 *
 * <p>Both menus are 54 slots laid out the same way: row 0 is the tab row, rows 1–4 are the
 * {@value #PAGE_SIZE}-slot grid, row 5 is navigation. Items are classified by the same
 * {@link Categorizer} the Marketplace uses for Pallet listings, so one material lands in
 * the same department wherever a player meets it.
 */
final class Departments {

    static final String ALL = "All";
    /** Row 0. Slot 0 is always "All"; the rest window over the departments. */
    static final int TAB_ROW = 9;
    /** Rows 1–4 — the item grid. */
    static final int GRID_START = 9;
    static final int PAGE_SIZE = 36;
    /** Departments visible at once when they do not all fit beside "All". */
    private static final int TAB_WINDOW = 7;

    private Departments() {
    }

    /**
     * How many catalog items land in each configured department, in config order.
     * Departments with no items are still present with a count of 0, so the startup log
     * shows an empty department rather than hiding it.
     */
    static Map<String, Integer> counts(HomeCraftManagement plugin) {
        Categorizer cat = new Categorizer(plugin);
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String dept : plugin.config().marketplace().departments()) {
            out.put(dept, 0);
        }
        for (MarketItem item : plugin.market().catalog()) {
            String dept = cat.department(item.material());
            out.merge(dept, 1, Integer::sum);
        }
        return out;
    }

    /** The departments that actually hold something, in config order. */
    static List<String> present(HomeCraftManagement plugin) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : counts(plugin).entrySet()) {
            if (e.getValue() > 0) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /** The catalog filtered to one department (or everything) and sorted. */
    static List<MarketItem> view(HomeCraftManagement plugin, String department, BrowseState.Sort sort) {
        Categorizer cat = new Categorizer(plugin);
        MarketService market = plugin.market();
        List<MarketItem> out = new ArrayList<>();
        for (MarketItem item : market.catalog()) {
            if (department == null || ALL.equalsIgnoreCase(department)
                    || cat.department(item.material()).equalsIgnoreCase(department)) {
                out.add(item);
            }
        }
        out.sort(comparator(market, sort));
        return out;
    }

    private static Comparator<MarketItem> comparator(MarketService market, BrowseState.Sort sort) {
        Comparator<MarketItem> byName = Comparator.comparing(i -> i.label().toLowerCase(java.util.Locale.ROOT));
        return switch (sort) {
            case PRICE_UP -> Comparator.<MarketItem>comparingDouble(i -> market.buyPrice(i.id())).thenComparing(byName);
            case PRICE_DOWN -> Comparator.<MarketItem>comparingDouble(i -> -market.buyPrice(i.id())).thenComparing(byName);
            case STOCK -> Comparator.<MarketItem>comparingLong(i -> -stock(market, i.id())).thenComparing(byName);
            case NAME -> byName;
        };
    }

    /** Live stock, tolerating a state row that has not caught up with a catalog edit. */
    static long stock(MarketService market, String id) {
        MarketState st = market.state(id);
        return st == null ? 0 : st.stock();
    }

    /**
     * Paint row 0: "All" at slot 0, then a window of departments. When they do not all fit,
     * slot 8 becomes "More »" and cycles the window rather than hiding a department for good.
     *
     * @param onChange run after the selection or window changes, to redraw the menu
     */
    static void paintTabs(Menu menu, HomeCraftManagement plugin, BrowseState.Shop state, Runnable onChange) {
        List<String> depts = present(plugin);
        Map<String, Integer> counts = counts(plugin);

        // A department the player has selected but which has since emptied stays offered,
        // otherwise their tab would silently vanish under them.
        if (!ALL.equalsIgnoreCase(state.department) && !depts.contains(state.department)) {
            depts = new ArrayList<>(depts);
            depts.add(state.department);
        }

        int total = plugin.market().catalog().size();
        menu.set(0, tab(ALL, total, ALL.equalsIgnoreCase(state.department)), e -> {
            state.department = ALL;
            state.page = 0;
            onChange.run();
        });

        boolean windowed = depts.size() > TAB_ROW - 1;
        int visible = windowed ? TAB_WINDOW : depts.size();
        if (windowed) {
            state.tabOffset = ((state.tabOffset % depts.size()) + depts.size()) % depts.size();
        } else {
            state.tabOffset = 0;
        }

        for (int i = 0; i < TAB_ROW - 1; i++) {
            int slot = 1 + i;
            if (i >= visible) {
                menu.set(slot, Menus.FILLER, null);
                continue;
            }
            String dept = depts.get((state.tabOffset + i) % depts.size());
            boolean selected = dept.equalsIgnoreCase(state.department);
            menu.set(slot, tab(dept, counts.getOrDefault(dept, 0), selected), e -> {
                state.department = dept;
                state.page = 0;
                onChange.run();
            });
        }

        if (windowed) {
            List<String> all = depts;
            menu.set(8, Menus.icon(Material.SPECTRAL_ARROW, "&bMore departments »",
                    "&7" + all.size() + " in total — click to",
                    "&7bring the rest into view."), e -> {
                state.tabOffset += TAB_WINDOW;
                onChange.run();
            });
        }
    }

    /** One tab: a lime pane when selected, grey otherwise. */
    private static org.bukkit.inventory.ItemStack tab(String name, int count, boolean selected) {
        return Menus.icon(selected ? Material.LIME_STAINED_GLASS_PANE : Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                (selected ? "&a&l" : "&7") + name,
                "&8" + count + " item(s)",
                selected ? "&aShowing this department" : "&eClick to view");
    }

    /** The sort toggle for the bottom row. */
    static org.bukkit.inventory.ItemStack sortButton(BrowseState.Sort sort) {
        return Menus.icon(Material.HOPPER, "&bSort: &f" + sort.label(),
                "&7Click to cycle Name → Price ↑",
                "&7→ Price ↓ → Stock.");
    }
}
