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
 *
 * <p>Each tab wears its department's own icon ({@link DepartmentIcons}), which the Crate
 * Marketplace's tab row shares, and the selected one shimmers.
 *
 * <p>{@value #MAX_TABS} departments is the ceiling, because the row is nine slots and "All"
 * takes the first. The shipped {@code marketplace.departments} is sized to it exactly —
 * Weapons and Armor are one Combat tab for this reason — and {@code MarketService} warns at
 * load if an admin's list runs past it. No rotating "more »" control: a tab you have to hunt
 * for by cycling is barely better than the flat grid the tabs replaced.
 */
final class Departments {

    static final String ALL = "All";
    /** Row 0. Slot 0 is always "All", so slots 1–8 are the departments. */
    static final int TAB_ROW = 9;
    /** How many departments the tab row holds beside "All" — the hard ceiling. */
    static final int MAX_TABS = TAB_ROW - 1;
    /** Rows 1–4 — the item grid. */
    static final int GRID_START = 9;
    static final int PAGE_SIZE = 36;

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
     * The departments to show as tabs: everything holding stock, capped at {@link #MAX_TABS}
     * so the row never needs a rotating "more »" control. The shipped list is sized to fit
     * exactly; only an admin who adds a department of their own can overflow it, and then it
     * is the trailing ones that drop out — which is why {@code Misc}, the classifier's
     * catch-all, is last in the shipped list and the tail is reachable from "All" regardless.
     *
     * <p>The selected department is always included, even when it has emptied or sits past
     * the cap: a tab the player is standing on must not vanish under them, or they cannot
     * see what they are filtered to.
     */
    static List<String> tabs(HomeCraftManagement plugin, String selected) {
        List<String> depts = new ArrayList<>(present(plugin));
        boolean all = selected == null || ALL.equalsIgnoreCase(selected);
        if (!all && !containsIgnoreCase(depts, selected)) {
            depts.add(selected);
        }
        if (depts.size() <= MAX_TABS) {
            return depts;
        }
        List<String> capped = new ArrayList<>(depts.subList(0, MAX_TABS));
        if (!all && !containsIgnoreCase(capped, selected)) {
            capped.set(MAX_TABS - 1, selected); // never hide the tab in use
        }
        return capped;
    }

    private static boolean containsIgnoreCase(List<String> haystack, String needle) {
        for (String s : haystack) {
            if (s.equalsIgnoreCase(needle)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Paint row 0: "All" at slot 0, then one tab per department in slots 1–8.
     *
     * @param onChange run after the selection changes, to redraw the menu
     */
    static void paintTabs(Menu menu, HomeCraftManagement plugin, BrowseState.Shop state, Runnable onChange) {
        List<String> depts = tabs(plugin, state.department);
        Map<String, Integer> counts = counts(plugin);

        int total = plugin.market().catalog().size();
        menu.set(0, tab(ALL, total, ALL.equalsIgnoreCase(state.department)), e -> {
            state.department = ALL;
            state.page = 0;
            onChange.run();
        });

        for (int i = 0; i < MAX_TABS; i++) {
            int slot = 1 + i;
            if (i >= depts.size()) {
                menu.set(slot, Menus.FILLER, null);
                continue;
            }
            String dept = depts.get(i);
            boolean selected = dept.equalsIgnoreCase(state.department);
            menu.set(slot, tab(dept, counts.getOrDefault(dept, 0), selected), e -> {
                state.department = dept;
                state.page = 0;
                onChange.run();
            });
        }
    }

    /**
     * One tab: the department's own icon, shimmering when it is the one being shown.
     *
     * <p>The icon must carry the meaning on its own. This row used to be a lime pane for the
     * selected department and a light-grey pane for the rest, and light grey is the colour of the
     * slot behind it — seven of the eight tabs were invisible, and the names that would have
     * identified them only appear on hover.
     */
    private static org.bukkit.inventory.ItemStack tab(String name, int count, boolean selected) {
        return Menus.glint(Menus.icon(DepartmentIcons.of(name),
                (selected ? "&a&l" : "&f") + name,
                "&7" + count + " item(s)",
                selected ? "&aShowing this department" : "&eClick to view"), selected);
    }

    /** The sort toggle for the bottom row. */
    static org.bukkit.inventory.ItemStack sortButton(BrowseState.Sort sort) {
        return Menus.icon(Material.HOPPER, "&bSort: &f" + sort.label(),
                "&7Click to cycle Name → Price ↑",
                "&7→ Price ↓ → Stock.");
    }
}
