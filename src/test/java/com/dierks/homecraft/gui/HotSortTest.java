package com.dierks.homecraft.gui;

import com.dierks.homecraft.market.MarketItem;
import com.dierks.homecraft.market.sim.Badge;
import com.dierks.homecraft.market.sim.ItemStatus;
import com.dierks.homecraft.market.sim.Phase;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The "Hot &amp; Deals" shop sort (spec §7.2) and the sort cycle it joins.
 *
 * <p>Pinned: {@link BrowseState.Sort#HOT} is appended <em>last</em>, so every sort name an older
 * config or session saved still parses to the same sort; the sort button offers it only while the
 * live market runs ({@link BrowseState.Sort#next(boolean)}), so with the market off the cycle is
 * the four sorts it always was; and {@link Departments#hotFirst} orders by badge — HOT, UP, DEAL,
 * DOWN, WANTED, then unbadged — then the biggest move against usual first, leaving ties to the
 * name order. An item with no status (market off, unknown id) sorts as quiet and unmoved.
 *
 * <p>Registry-free: {@link Material} enum identity only, as {@code DepartmentIconsTest} explains.
 */
class HotSortTest {

    private static final Comparator<MarketItem> BY_NAME = Comparator.comparing(MarketItem::label);

    @Test
    void hotIsAppendedLastAndOldNamesStillParse() {
        BrowseState.Sort[] all = BrowseState.Sort.values();
        assertEquals(BrowseState.Sort.HOT, all[all.length - 1]);
        assertEquals("Hot & Deals", BrowseState.Sort.HOT.label());
        assertEquals(List.of("NAME", "PRICE_UP", "PRICE_DOWN", "STOCK"),
                List.of(all[0].name(), all[1].name(), all[2].name(), all[3].name()));
        assertEquals(BrowseState.Sort.STOCK, BrowseState.Sort.of("stock"));
        assertEquals(BrowseState.Sort.HOT, BrowseState.Sort.of(" hot "));
        assertEquals(BrowseState.Sort.NAME, BrowseState.Sort.of("hot_and_deals"));
    }

    @Test
    void theCycleOffersHotOnlyWhileTheMarketRuns() {
        assertEquals(List.of(BrowseState.Sort.PRICE_UP, BrowseState.Sort.PRICE_DOWN, BrowseState.Sort.STOCK,
                BrowseState.Sort.NAME), cycle(false));
        assertEquals(List.of(BrowseState.Sort.PRICE_UP, BrowseState.Sort.PRICE_DOWN, BrowseState.Sort.STOCK,
                BrowseState.Sort.HOT, BrowseState.Sort.NAME), cycle(true));
        // A player left on HOT when the market stops is moved on, not stuck.
        assertEquals(BrowseState.Sort.NAME, BrowseState.Sort.HOT.next(false));
    }

    @Test
    void hotReadsAsNameWhileTheMarketIsOff() {
        // A player left on "Hot & Deals" when the market is paused, and default_sort: HOT on a
        // server with it disabled, get 0.32's name order and "Name A–Z" button.
        assertEquals(BrowseState.Sort.NAME, BrowseState.Sort.HOT.usable(false));
        assertEquals(BrowseState.Sort.NAME, BrowseState.Sort.of("HOT").usable(false));
        assertEquals(BrowseState.Sort.HOT, BrowseState.Sort.HOT.usable(true));
        for (BrowseState.Sort s : BrowseState.Sort.values()) {
            if (s != BrowseState.Sort.HOT) {
                assertEquals(s, s.usable(false));
                assertEquals(s, s.usable(true));
            }
        }
        // From there the button cycles exactly as 0.32's did.
        assertEquals(BrowseState.Sort.PRICE_UP, BrowseState.Sort.HOT.usable(false).next(false));
    }

    @Test
    void badgedItemsComeFirstInBadgeOrder() {
        List<MarketItem> items = new ArrayList<>(List.of(
                item("a_quiet"), item("b_wanted"), item("c_down"), item("d_deal"), item("e_up"), item("f_hot")));
        Map<String, ItemStatus> status = new HashMap<>();
        status.put("b_wanted", status(Badge.WANTED, 0.0));
        status.put("c_down", status(Badge.DOWN, -20.0));
        status.put("d_deal", status(Badge.DEAL, -12.0));
        status.put("e_up", status(Badge.UP, 22.0));
        status.put("f_hot", status(Badge.HOT, 11.0));
        items.sort(Departments.hotFirst(status).thenComparing(BY_NAME));
        assertEquals(List.of("f_hot", "e_up", "d_deal", "c_down", "b_wanted", "a_quiet"), ids(items));
    }

    @Test
    void withinABadgeTheBiggestMoveComesFirst() {
        List<MarketItem> items = new ArrayList<>(List.of(
                item("a_small"), item("b_big"), item("c_drift_down"), item("d_drift_up"), item("e_nan")));
        Map<String, ItemStatus> status = new HashMap<>();
        status.put("a_small", status(Badge.HOT, 8.0));
        status.put("b_big", status(Badge.HOT, 14.0));
        status.put("c_drift_down", status(Badge.NONE, -6.0));
        status.put("d_drift_up", status(Badge.NONE, 4.0));
        status.put("e_nan", status(Badge.NONE, Double.NaN));
        items.sort(Departments.hotFirst(status).thenComparing(BY_NAME));
        // |pct| decides, whichever way the price moved; NaN reads as no move.
        assertEquals(List.of("b_big", "a_small", "c_drift_down", "d_drift_up", "e_nan"), ids(items));
    }

    @Test
    void withNoStatusesItIsTheNameOrder() {
        List<MarketItem> items = new ArrayList<>(List.of(item("c"), item("a"), item("b")));
        items.sort(Departments.hotFirst(Map.of()).thenComparing(BY_NAME));
        assertEquals(List.of("a", "b", "c"), ids(items));
    }

    private static List<BrowseState.Sort> cycle(boolean hot) {
        List<BrowseState.Sort> seen = new ArrayList<>();
        BrowseState.Sort s = BrowseState.Sort.NAME;
        do {
            s = s.next(hot);
            seen.add(s);
        } while (s != BrowseState.Sort.NAME && seen.size() < 10);
        return seen;
    }

    private static MarketItem item(String id) {
        return new MarketItem(id, Material.WHEAT, id, 1.0, 12.0, 100, 1000, 0, 0);
    }

    private static ItemStatus status(Badge badge, double pct) {
        return new ItemStatus(badge, 0L, 1.0 + pct / 100.0, 3.0, 3.0 * (1.0 + pct / 100.0), pct, null,
                badge == Badge.NONE ? null : Phase.FULL);
    }

    private static List<String> ids(List<MarketItem> items) {
        List<String> out = new ArrayList<>();
        for (MarketItem i : items) {
            out.add(i.id());
        }
        return out;
    }
}
