package com.dierks.homecraft;

import com.dierks.homecraft.mini.MiniIds;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Config revision 26: Thanksgiving (0.38.6). Four lists gain rows by id, because a list is one leaf to the backfill
 * and a new row never reaches a file that already has the list:
 * <ul>
 *   <li>{@code arcade.holiday_shows.windows}: {@code thanksgiving}, 22 to 30 November, after
 *       {@code independence_day};</li>
 *   <li>{@code market.sim.seasons.list}: {@code feast_week}, 22 to 30 November, food for the family table a little
 *       cheaper, after {@code gift_season};</li>
 *   <li>{@code market.catalog}: pumpkin, carrot, potato, bread, baked_potato and cooked_chicken after
 *       {@code wheat}, so Feast Week has something to move (Crate sold none of them before);</li>
 *   <li>{@code minis.series}: the Holiday Collection series holding the Turkey Mini 2026, an event-only Mini earned
 *       at the Family Feast and Catch the Turkey.</li>
 * </ul>
 *
 * <p><b>The owner's rows win, quietly.</b> The owner put their own {@code thanksgiving} window and
 * {@code feast_week} season live before this release, on purpose. So a row whose id the owner already has is kept as
 * it is, with one INFO line ("kept your own ...") when it differs from ours and nothing when it is the same; never a
 * WARN. A catalog row is also kept out when the owner already sells its material under another id. Each new row goes
 * in right after the row it follows, or at the end of the list when that row is gone. The catalog is the exception:
 * a catalog without {@code wheat} is the owner's own design, so it is left alone, with one INFO line giving the rows
 * to paste. An absent list is left to the backfill, which writes the bundled one.
 *
 * <p><b>The Turkey Mini</b> goes in only while no Mini in any series has the id {@code turkey_mini_2026} (an entry
 * without an id is known by its name's slug, as the catalog reads it). It joins the owner's own "Holiday Collection"
 * series when there is one, and otherwise comes in a new EPIC series of that name at the end.
 *
 * <p><b>The tables are data</b>, frozen as literals and never read from the bundled file, so a later release that
 * edits a row can't change what revision 26 did. {@code ThanksgivingMigrationTest} pins them to the bundled rows. The
 * new {@code arcade.events.harvest}, {@code turkey} and {@code feast} sections need no step: the backfill writes them,
 * with their comments. No shipped comment goes stale. A second run changes nothing.
 */
final class ThanksgivingMigration {

    /** The config revision this step brings: {@code HomeCraftManagement.CONFIG_REVISION} 26. */
    static final int REVISION = 26;

    static final String WINDOWS = "arcade.holiday_shows.windows";
    static final String SEASONS = "market.sim.seasons.list";
    static final String CATALOG = "market.catalog";
    static final String SERIES = "minis.series";

    /** The Thanksgiving show window, as the owner wrote it and 0.38.6 ships it. */
    static final Map<String, Object> WINDOW = row("id", "thanksgiving", "name", "Thanksgiving", "from", "11-22",
            "to", "11-30", "colours", List.of("ORANGE", "BROWN", "#FFD700", "RED"), "fades", List.of("YELLOW"),
            "shape", "BALL_LARGE");

    /** Feast Week at Crate, as the owner wrote it and 0.38.6 ships it. */
    static final Map<String, Object> SEASON = row("id", "feast_week", "name", "Feast Week", "from", "11-22",
            "to", "11-30", "percent", row("bread", -4, "baked_potato", -4, "cooked_chicken", -4, "carrot", -4,
                    "pumpkin", -4),
            "headline", "Feast week! Food for the family table costs a little less.");

    /**
     * Crate's new rows, in order, each after the one before and the first after {@code wheat}. Each item is priced on
     * its own stock, so bread and baked potatoes sit wholly below what they are cooked from: bread's best bid, at its
     * ceiling and the highest mood, is under three wheat's lowest ask, at wheat's floor and the lowest mood (a baked
     * potato likewise under one potato). Cooking to sell at Crate then never pays, whatever the stocks or the mood.
     */
    static final List<Map<String, Object>> CATALOG_ROWS = List.of(
            item("pumpkin", "PUMPKIN", "&6Pumpkin", 0.5, 8.0, 1500, 3000, 60, 120),
            item("carrot", "CARROT", "&6Carrot", 0.3, 6.0, 3000, 6000, 120, 240),
            item("potato", "POTATO", "&ePotato", 0.3, 6.0, 3000, 6000, 120, 240),
            item("bread", "BREAD", "&6Bread", 0.5, 1.9, 1500, 3000, 60, 120),
            item("baked_potato", "BAKED_POTATO", "&6Baked Potato", 0.1, 0.19, 1500, 3000, 60, 120),
            item("cooked_chicken", "COOKED_CHICKEN", "&fCooked Chicken", 1.0, 12.0, 1000, 2000, 40, 80));

    /** The id of the Mini revision 26 adds. */
    static final String TURKEY_MINI_ID = "turkey_mini_2026";
    /** The series it lives in. */
    static final String HOLIDAY_SERIES = "Holiday Collection";

    /** The Turkey Mini 2026, as 0.38.6 ships it: earned only during Thanksgiving 2026. */
    static final Map<String, Object> TURKEY_MINI = row("name", "Turkey Mini 2026", "id", TURKEY_MINI_ID,
            "category", "HOLIDAY", "rarity", "EPIC", "cap", 20, "event_only", true, "earn_from", "2026-11-22",
            "earn_to", "2026-11-29", "texture", "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3Jh"
                    + "ZnQubmV0L3RleHR1cmUvZjA2NTU1NzA2YjY0MWZkYWY0MzZjMDc2NjNmOTIzYWZjNWVlNzIxNDZmOTAxOTVmYjMzN2I5ZG"
                    + "U3NjY1ODhkIn19fQ==");

    private ThanksgivingMigration() {
    }

    /** Run revision 26's four steps against {@code c} (defaults-free), appending what they did to {@code log}. */
    static void apply(FileConfiguration c, List<String> log) {
        addById(c, WINDOWS, List.of(WINDOW), "independence_day", "the Thanksgiving show colours (22-30 Nov)",
                "Firework shows", log);
        addById(c, SEASONS, List.of(SEASON), "gift_season", "Feast Week (22-30 Nov, food a little cheaper)",
                "Crate seasons", log);
        catalog(c, log);
        turkeyMini(c, log);
    }

    // ---- the windows and the seasons ------------------------------------------------------------

    /** Add {@code rows} to the list at {@code path} by id, the first after {@code anchor}; the owner's ids win. */
    private static void addById(FileConfiguration c, String path, List<Map<String, Object>> rows, String anchor,
                                String what, String listName, List<String> log) {
        if (!(c.get(path, null) instanceof List<?> raw)) {
            return; // absent: the backfill writes the whole bundled list
        }
        List<Object> out = new ArrayList<>(raw);
        String after = anchor;
        boolean any = false;
        for (Map<String, Object> mine : rows) {
            String id = ShopMigration.idOf(mine);
            Map<String, Object> theirs = find(out, id);
            if (theirs != null) {
                keptQuietly(path, id, theirs, mine, log);
                after = id;
                continue;
            }
            int at = indexOf(out, after);
            out.add(at < 0 ? out.size() : at + 1, ShopMigration.copy(mine));
            after = id;
            any = true;
        }
        if (any) {
            c.set(path, out);
            log.add("Config migration: " + listName + " - added " + what + ".");
        }
    }

    // ---- Crate's catalog ------------------------------------------------------------------------

    private static void catalog(FileConfiguration c, List<String> log) {
        if (!(c.get(CATALOG, null) instanceof List<?> raw)) {
            return; // absent: the backfill writes the whole bundled catalog
        }
        List<Object> out = new ArrayList<>(raw);
        if (indexOf(out, "wheat") < 0) {
            List<String> missing = new ArrayList<>();
            for (Map<String, Object> mine : CATALOG_ROWS) {
                if (find(out, ShopMigration.idOf(mine)) == null && sells(out, mine) == null) {
                    missing.add(ArcadeConfigMigration.flowRow(mine));
                }
            }
            if (!missing.isEmpty()) {
                log.add("Config migration: left your own " + CATALOG + " alone (it has no wheat row). For Feast Week"
                        + " to change prices, add these rows to it: " + String.join("  ", missing));
            }
            return;
        }
        String after = "wheat";
        List<String> added = new ArrayList<>();
        for (Map<String, Object> mine : CATALOG_ROWS) {
            String id = ShopMigration.idOf(mine);
            Map<String, Object> theirs = find(out, id);
            if (theirs != null) {
                keptQuietly(CATALOG, id, theirs, mine, log);
                after = id;
                continue;
            }
            String other = sells(out, mine);
            if (other != null) {
                log.add("Config migration: kept your own " + CATALOG + " → " + other + " row, which already sells "
                        + mine.get("material") + " (no " + id + " row added).");
                continue;
            }
            int at = indexOf(out, after);
            out.add(at < 0 ? out.size() : at + 1, ShopMigration.copy(mine));
            after = id;
            added.add(id);
        }
        if (!added.isEmpty()) {
            c.set(CATALOG, out);
            log.add("Config migration: Crate now sells " + String.join(", ", added) + " (after wheat), for Feast"
                    + " Week.");
        }
    }

    /** The id of the owner's row that already sells {@code mine}'s material under another id, or null. */
    private static String sells(List<Object> rows, Map<String, Object> mine) {
        String material = String.valueOf(mine.get("material"));
        for (Object o : rows) {
            Map<String, Object> r = asRow(o);
            if (r != null && r.get("material") != null
                    && String.valueOf(r.get("material")).trim().equalsIgnoreCase(material)) {
                return ShopMigration.idOf(r);
            }
        }
        return null;
    }

    // ---- the Turkey Mini ------------------------------------------------------------------------

    private static void turkeyMini(FileConfiguration c, List<String> log) {
        if (!(c.get(SERIES, null) instanceof List<?> raw)) {
            return; // absent: the backfill writes the whole bundled list
        }
        List<Object> series = new ArrayList<>(raw);
        int holiday = -1;
        for (int i = 0; i < series.size(); i++) {
            Map<String, Object> s = asRow(series.get(i));
            if (s == null) {
                continue;
            }
            if (s.get("entries") instanceof List<?> entries) {
                for (Object e : entries) {
                    Map<String, Object> entry = asRow(e);
                    if (entry != null && TURKEY_MINI_ID.equals(miniId(entry))) {
                        if (!ShopMigration.same(entry, TURKEY_MINI)) {
                            log.add("Config migration: kept your own Turkey Mini 2026 in " + SERIES + ".");
                        }
                        return;
                    }
                }
            }
            if (holiday < 0 && s.get("name") != null
                    && HOLIDAY_SERIES.equalsIgnoreCase(String.valueOf(s.get("name")).trim())) {
                holiday = i;
            }
        }
        if (holiday >= 0) {
            Map<String, Object> s = new LinkedHashMap<>(asRow(series.get(holiday)));
            List<Object> entries = s.get("entries") instanceof List<?> l ? new ArrayList<>(l) : new ArrayList<>();
            entries.add(ShopMigration.copy(TURKEY_MINI));
            s.put("entries", entries);
            series.set(holiday, s);
            c.set(SERIES, series);
            log.add("Config migration: Minis - added the Turkey Mini 2026 to your " + HOLIDAY_SERIES + " series"
                    + " (earned only, 22-29 Nov 2026).");
            return;
        }
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("name", HOLIDAY_SERIES);
        s.put("rarity", "EPIC");
        s.put("entries", new ArrayList<>(List.of(ShopMigration.copy(TURKEY_MINI))));
        series.add(s);
        c.set(SERIES, series);
        log.add("Config migration: Minis - added the " + HOLIDAY_SERIES + " series with the Turkey Mini 2026"
                + " (earned only, 22-29 Nov 2026).");
    }

    /** A Mini entry's id as the catalog reads it: its {@code id}, else its name, as a slug. */
    static String miniId(Map<String, Object> entry) {
        Object id = entry.get("id");
        Object name = entry.get("name");
        return MiniIds.slug(String.valueOf(id != null ? id : name != null ? name : ""));
    }

    // ---- helpers --------------------------------------------------------------------------------

    /** One INFO line when the owner's row with our id differs from ours; nothing when it is the same. */
    private static void keptQuietly(String path, String id, Map<String, Object> theirs, Map<String, Object> mine,
                                    List<String> log) {
        if (!ShopMigration.same(theirs, mine)) {
            log.add("Config migration: kept your own " + path + " → " + id + " row.");
        }
    }

    private static Map<String, Object> find(List<Object> rows, String id) {
        int at = indexOf(rows, id);
        return at < 0 ? null : asRow(rows.get(at));
    }

    private static int indexOf(List<Object> rows, String id) {
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> r = asRow(rows.get(i));
            if (r != null && ShopMigration.idOf(r).equals(id.toLowerCase(Locale.ROOT))) {
                return i;
            }
        }
        return -1;
    }

    private static Map<String, Object> asRow(Object o) {
        if (o instanceof ConfigurationSection s) {
            return new LinkedHashMap<>(s.getValues(false));
        }
        List<Map<String, Object>> rows = ArcadeConfigMigration.mapRows(Collections.singletonList(o));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static Map<String, Object> item(String id, String material, String display, double floor, double ceiling,
                                            int initial, int full, int sell, int buy) {
        return row("id", id, "material", material, "display_name", display, "floor", floor, "ceiling", ceiling,
                "initial_stock", initial, "full_stock", full, "max_daily_sell", sell, "max_daily_buy", buy);
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return Collections.unmodifiableMap(m);
    }
}
