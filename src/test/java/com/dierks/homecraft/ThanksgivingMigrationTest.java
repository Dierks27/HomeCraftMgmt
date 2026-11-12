package com.dierks.homecraft;

import com.dierks.homecraft.config.MarketSimConfig;
import com.dierks.homecraft.market.MarketItem;
import com.dierks.homecraft.market.OrderMath;
import com.dierks.homecraft.market.PricingEngine;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Config revision 26 ({@link ThanksgivingMigration}), against a verbatim copy of 0.38.5's bundled config.yml and
 * against that file as the owner edited it live before this release (their own Thanksgiving window and Feast Week).
 *
 * <p>Pinned here: a 0.38.5 file ends with the bundled window, season, catalog and Holiday Collection, each row in its
 * place, with one INFO line a list and no WARN; a second run changes nothing; the owner's own rows win quietly (an
 * INFO line when theirs differs, nothing when it is the same, never a WARN); a missing anchor appends at the end; a
 * catalog without wheat is left alone with the rows to paste; a material the owner already sells is not added twice;
 * an absent list is left to the backfill; the Turkey Mini joins the owner's own Holiday Collection and is never added
 * twice; the frozen rows are the bundled file's own; and the seasons the owner added that name items Crate doesn't
 * sell are named in one WARN at start.
 */
class ThanksgivingMigrationTest {

    static final String WINDOWS_INFO = "Config migration: Firework shows - added the Thanksgiving show colours"
            + " (22-30 Nov).";
    static final String SEASONS_INFO = "Config migration: Crate seasons - added Feast Week (22-30 Nov, food a"
            + " little cheaper).";
    static final String CATALOG_INFO = "Config migration: Crate now sells pumpkin, carrot, potato, bread,"
            + " baked_potato, cooked_chicken (after wheat), for Feast Week.";
    static final String MINI_INFO = "Config migration: Minis - added the Holiday Collection series with the"
            + " Turkey Mini 2026 (earned only, 22-29 Nov 2026).";
    /** Revision 26's lines on a 0.38.0 or 0.38.1 file, which has no holiday_shows yet (the backfill writes it). */
    static final List<String> NO_WINDOWS = List.of(SEASONS_INFO, CATALOG_INFO, MINI_INFO);
    private static final List<String> NEW_FOODS = List.of("pumpkin", "carrot", "potato", "bread", "baked_potato",
            "cooked_chicken");

    private static YamlConfiguration load(String resource) {
        try (InputStream in = ThanksgivingMigrationTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, resource + " is on the test classpath");
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                YamlConfiguration c = new YamlConfiguration();
                c.load(r);
                return c;
            }
        } catch (Exception e) {
            throw new IllegalStateException("could not read " + resource, e);
        }
    }

    /** 0.38.5's bundled config.yml, verbatim (revision 25). */
    private static YamlConfiguration v0385() {
        return load("/config-0.38.5.yml");
    }

    private static YamlConfiguration bundled() {
        return load("/config.yml");
    }

    private static List<String> apply(FileConfiguration c) {
        List<String> log = new ArrayList<>();
        ThanksgivingMigration.apply(c, log);
        return log;
    }

    private static List<Map<String, Object>> rows(FileConfiguration c, String path) {
        return ArcadeConfigMigration.mapRows(c.getList(path));
    }

    private static List<String> ids(FileConfiguration c, String path) {
        return rows(c, path).stream().map(ShopMigration::idOf).toList();
    }

    private static Map<String, Object> rowOf(FileConfiguration c, String path, String id) {
        return rows(c, path).stream().filter(r -> id.equals(ShopMigration.idOf(r))).findFirst().orElse(null);
    }

    private static List<String> warns(List<String> log) {
        return log.stream().filter(l -> l.startsWith(HomeCraftManagement.WARN)).toList();
    }

    private static Map<String, Object> mutable(Map<String, Object> row) {
        return new LinkedHashMap<>(row);
    }

    // ---- a 0.38.5 file -------------------------------------------------------------------------

    @Test
    void a0385FileGetsEveryRowInPlaceAndEndsAtTheBundledLists() {
        YamlConfiguration c = v0385();
        List<String> log = HomeCraftManagement.migrateConfig(c, "world");
        assertEquals(List.of(WINDOWS_INFO, SEASONS_INFO, CATALOG_INFO, MINI_INFO,
                "Config migration: config_revision 25 → 27."), log, "one INFO a list, and the revision (27, graves,"
                        + " refreshes one comment quietly)");
        assertEquals(List.of(), warns(log));
        YamlConfiguration b = bundled();
        for (String path : List.of(ThanksgivingMigration.WINDOWS, ThanksgivingMigration.SEASONS,
                ThanksgivingMigration.CATALOG, ThanksgivingMigration.SERIES)) {
            assertTrue(ArcadeConfigMigration.sameRows(rows(c, path), rows(b, path)), path + " is the bundled one: "
                    + ids(c, path));
        }
        List<String> windows = ids(c, ThanksgivingMigration.WINDOWS);
        assertEquals("thanksgiving", windows.get(windows.indexOf("independence_day") + 1));
        List<String> seasons = ids(c, ThanksgivingMigration.SEASONS);
        assertEquals("feast_week", seasons.get(seasons.indexOf("gift_season") + 1));
        List<String> catalog = ids(c, ThanksgivingMigration.CATALOG);
        int wheat = catalog.indexOf("wheat");
        assertEquals(NEW_FOODS, catalog.subList(wheat + 1, wheat + 7), "the six right after wheat, in order");
        assertEquals(27, c.getInt("config_revision"));
        assertEquals(List.of(), HomeCraftManagement.migrateConfig(c, "world"), "a second start runs nothing");
    }

    @Test
    void aReRunChangesNothing() {
        YamlConfiguration c = v0385();
        apply(c);
        String once = c.saveToString();
        assertEquals(List.of(), apply(c), "the second run logs nothing");
        assertEquals(once, c.saveToString(), "and changes nothing");
        assertEquals(1, ids(c, ThanksgivingMigration.CATALOG).stream().filter("bread"::equals).count());
    }

    @Test
    void theBundledFileNeedsNoStep() {
        YamlConfiguration c = bundled();
        String before = c.saveToString();
        assertEquals(List.of(), apply(c), "every row is already there, the same as ours: nothing to say");
        assertEquals(before, c.saveToString());
    }

    // ---- the owner's own rows --------------------------------------------------------------------

    /** The 0.38.5 file as the owner put it live on 3 Nov: their own Thanksgiving window and seasons. */
    private static YamlConfiguration ownersLiveFile() {
        YamlConfiguration c = v0385();
        List<Object> windows = new ArrayList<>(c.getList(ThanksgivingMigration.WINDOWS));
        windows.add(mutable(ThanksgivingMigration.WINDOW));
        windows.add(Map.of("id", "easter", "name", "Easter", "from", "03-25", "to", "03-31",
                "colours", List.of("PINK", "LIGHT_BLUE", "YELLOW", "LIME"), "fades", List.of("WHITE"), "shape", "BALL"));
        c.set(ThanksgivingMigration.WINDOWS, windows);
        List<Object> seasons = new ArrayList<>(c.getList(ThanksgivingMigration.SEASONS));
        seasons.add(Map.of("id", "lucky_week", "name", "Lucky Week", "from", "03-14", "to", "03-17",
                "percent", Map.of("emerald", -4, "green_dye", -4), "headline", "Lucky week!"));
        Map<String, Object> feast = mutable(ThanksgivingMigration.SEASON);
        feast.put("headline", "Feast week! Their own words.");
        seasons.add(feast);
        c.set(ThanksgivingMigration.SEASONS, seasons);
        return c;
    }

    @Test
    void theOwnersOwnRowsWinQuietly() {
        YamlConfiguration c = ownersLiveFile();
        List<String> log = apply(c);
        assertEquals(List.of(), warns(log), "never a WARN: the owner added these on purpose");
        assertEquals(List.of("Config migration: kept your own market.sim.seasons.list → feast_week row.", CATALOG_INFO,
                MINI_INFO), log, "their identical window says nothing; their different Feast Week one INFO line");
        assertEquals("Feast week! Their own words.", rowOf(c, ThanksgivingMigration.SEASONS, "feast_week")
                .get("headline"), "theirs stays");
        assertEquals(1, ids(c, ThanksgivingMigration.WINDOWS).stream().filter("thanksgiving"::equals).count());
        assertEquals(1, ids(c, ThanksgivingMigration.SEASONS).stream().filter("feast_week"::equals).count());
        List<String> windows = ids(c, ThanksgivingMigration.WINDOWS);
        assertEquals(List.of("thanksgiving", "easter"), windows.subList(windows.size() - 2, windows.size()),
                "where they put it, and their other rows untouched");
        assertNotNull(rowOf(c, ThanksgivingMigration.SEASONS, "lucky_week"));
    }

    @Test
    void aDifferentWindowWithTheIdIsKeptWithOneInfoLine() {
        YamlConfiguration c = v0385();
        List<Object> windows = new ArrayList<>(c.getList(ThanksgivingMigration.WINDOWS));
        Map<String, Object> mine = mutable(ThanksgivingMigration.WINDOW);
        mine.put("shape", "BURST");
        windows.add(0, mine);
        c.set(ThanksgivingMigration.WINDOWS, windows);
        List<String> log = apply(c);
        assertTrue(log.contains("Config migration: kept your own arcade.holiday_shows.windows → thanksgiving row."),
                log.toString());
        assertFalse(log.contains(WINDOWS_INFO));
        assertEquals(List.of(), warns(log));
        assertEquals("BURST", rowOf(c, ThanksgivingMigration.WINDOWS, "thanksgiving").get("shape"));
    }

    @Test
    void aMissingAnchorAppendsAtTheEnd() {
        YamlConfiguration c = v0385();
        List<Object> windows = new ArrayList<>(c.getList(ThanksgivingMigration.WINDOWS));
        windows.removeIf(o -> o instanceof Map<?, ?> m && "independence_day".equals(m.get("id")));
        c.set(ThanksgivingMigration.WINDOWS, windows);
        List<Object> seasons = new ArrayList<>(c.getList(ThanksgivingMigration.SEASONS));
        seasons.removeIf(o -> o instanceof Map<?, ?> m && "gift_season".equals(m.get("id")));
        c.set(ThanksgivingMigration.SEASONS, seasons);
        apply(c);
        List<String> w = ids(c, ThanksgivingMigration.WINDOWS);
        assertEquals("thanksgiving", w.get(w.size() - 1));
        List<String> s = ids(c, ThanksgivingMigration.SEASONS);
        assertEquals("feast_week", s.get(s.size() - 1));
    }

    @Test
    void aCatalogWithoutWheatIsLeftAloneWithTheRowsToPaste() {
        YamlConfiguration c = v0385();
        List<Object> catalog = new ArrayList<>(c.getList(ThanksgivingMigration.CATALOG));
        catalog.removeIf(o -> o instanceof Map<?, ?> m && "wheat".equals(m.get("id")));
        c.set(ThanksgivingMigration.CATALOG, catalog);
        List<String> before = ids(c, ThanksgivingMigration.CATALOG);
        List<String> log = apply(c);
        assertEquals(before, ids(c, ThanksgivingMigration.CATALOG), "the owner's own design is untouched");
        String line = log.stream().filter(l -> l.contains("market.catalog")).findFirst().orElseThrow();
        assertTrue(line.startsWith("Config migration: left your own market.catalog alone (it has no wheat row)."),
                line);
        for (Map<String, Object> row : ThanksgivingMigration.CATALOG_ROWS) {
            assertTrue(line.contains(ArcadeConfigMigration.flowRow(row)), "the paste line for " + row.get("id"));
        }
        assertEquals(List.of(), warns(log));
    }

    @Test
    void aMaterialTheOwnerAlreadySellsIsNotAddedTwice() {
        YamlConfiguration c = v0385();
        List<Object> catalog = new ArrayList<>(c.getList(ThanksgivingMigration.CATALOG));
        catalog.add(Map.of("id", "pumpkins", "material", "PUMPKIN", "floor", 1.0, "ceiling", 9.0));
        catalog.add(mutable(ThanksgivingMigration.CATALOG_ROWS.get(3))); // their own bread, the same as ours
        c.set(ThanksgivingMigration.CATALOG, catalog);
        List<String> log = apply(c);
        assertTrue(log.contains("Config migration: kept your own market.catalog → pumpkins row, which already sells"
                + " PUMPKIN (no pumpkin row added)."), log.toString());
        assertTrue(log.contains("Config migration: Crate now sells carrot, potato, baked_potato, cooked_chicken"
                + " (after wheat), for Feast Week."), log.toString());
        List<String> ids = ids(c, ThanksgivingMigration.CATALOG);
        assertFalse(ids.contains("pumpkin"));
        assertEquals("carrot", ids.get(ids.indexOf("wheat") + 1), "carrot follows wheat when pumpkin isn't added");
        assertEquals(1, ids.stream().filter("bread"::equals).count());
        assertEquals("baked_potato", ids.get(ids.indexOf("bread") + 1), "the next row follows the owner's bread");
        assertEquals(List.of(), warns(log));
    }

    @Test
    void anAbsentListIsLeftToTheBackfill() {
        YamlConfiguration c = v0385();
        for (String path : List.of(ThanksgivingMigration.WINDOWS, ThanksgivingMigration.SEASONS,
                ThanksgivingMigration.CATALOG, ThanksgivingMigration.SERIES)) {
            c.set(path, null);
        }
        assertEquals(List.of(), apply(c));
        for (String path : List.of(ThanksgivingMigration.WINDOWS, ThanksgivingMigration.SEASONS,
                ThanksgivingMigration.CATALOG, ThanksgivingMigration.SERIES)) {
            assertNull(c.get(path), path + " is still absent");
        }
    }

    // ---- the Turkey Mini ---------------------------------------------------------------------------

    @Test
    void theTurkeyMiniJoinsTheOwnersOwnHolidayCollection() {
        YamlConfiguration c = v0385();
        List<Object> series = new ArrayList<>(c.getList(ThanksgivingMigration.SERIES));
        Map<String, Object> holiday = new LinkedHashMap<>();
        holiday.put("name", "Holiday Collection");
        holiday.put("rarity", "EPIC");
        holiday.put("entries", List.of(Map.of("name", "Santa Mini 2026", "category", "HOLIDAY")));
        series.add(holiday);
        c.set(ThanksgivingMigration.SERIES, series);
        int before = series.size();
        List<String> log = apply(c);
        assertTrue(log.contains("Config migration: Minis - added the Turkey Mini 2026 to your Holiday Collection series"
                + " (earned only, 22-29 Nov 2026)."), log.toString());
        List<Map<String, Object>> after = rows(c, ThanksgivingMigration.SERIES);
        assertEquals(before, after.size(), "no second series");
        List<?> entries = (List<?>) after.get(after.size() - 1).get("entries");
        assertEquals(2, entries.size());
        assertTrue(ShopMigration.same(ArcadeConfigMigration.mapRows(entries).get(1), ThanksgivingMigration.TURKEY_MINI));
        assertEquals(List.of(), apply(c), "never twice");
    }

    @Test
    void aTurkeyMiniKnownOnlyByItsNameIsNotAddedAgain() {
        YamlConfiguration c = v0385();
        List<Object> series = new ArrayList<>(c.getList(ThanksgivingMigration.SERIES));
        series.add(Map.of("name", "My Holidays", "rarity", "EPIC", "entries",
                List.of(Map.of("name", "Turkey Mini 2026", "category", "HOLIDAY"))));
        c.set(ThanksgivingMigration.SERIES, series);
        List<String> log = apply(c);
        assertTrue(log.contains("Config migration: kept your own Turkey Mini 2026 in minis.series."), log.toString());
        assertEquals(series.size(), rows(c, ThanksgivingMigration.SERIES).size(), "no Holiday Collection added");
        assertEquals("turkey_mini_2026", ThanksgivingMigration.miniId(Map.of("name", "Turkey Mini 2026")));
        assertEquals("turkey_mini_2026", ThanksgivingMigration.miniId(Map.of("name", "x", "id", "Turkey-Mini-2026")));
    }

    // ---- the frozen rows are the bundled file's own ---------------------------------------------------

    @Test
    void theFrozenRowsAreTheBundledRows() {
        YamlConfiguration b = bundled();
        assertTrue(ShopMigration.same(rowOf(b, ThanksgivingMigration.WINDOWS, "thanksgiving"),
                ThanksgivingMigration.WINDOW));
        assertTrue(ShopMigration.same(rowOf(b, ThanksgivingMigration.SEASONS, "feast_week"),
                ThanksgivingMigration.SEASON));
        for (Map<String, Object> row : ThanksgivingMigration.CATALOG_ROWS) {
            assertTrue(ShopMigration.same(rowOf(b, ThanksgivingMigration.CATALOG, ShopMigration.idOf(row)), row),
                    String.valueOf(row.get("id")));
        }
        Map<String, Object> holiday = rows(b, ThanksgivingMigration.SERIES).stream()
                .filter(s -> ThanksgivingMigration.HOLIDAY_SERIES.equals(s.get("name"))).findFirst().orElseThrow();
        assertEquals("EPIC", holiday.get("rarity"));
        List<Map<String, Object>> entries = ArcadeConfigMigration.mapRows((List<?>) holiday.get("entries"));
        assertEquals(1, entries.size(), "the Turkey Mini alone, live");
        assertTrue(ShopMigration.same(entries.get(0), ThanksgivingMigration.TURKEY_MINI));
    }

    // ---- Crate's new foods -----------------------------------------------------------------------

    @Test
    void theNewFoodsArePricedSoCookingNeverPaysASureProfit() {
        YamlConfiguration b = bundled();
        Map<String, Object> wheat = rowOf(b, ThanksgivingMigration.CATALOG, "wheat");
        Map<String, Object> potato = rowOf(b, ThanksgivingMigration.CATALOG, "potato");
        Map<String, Object> pumpkin = rowOf(b, ThanksgivingMigration.CATALOG, "pumpkin");
        Map<String, Object> bread = rowOf(b, ThanksgivingMigration.CATALOG, "bread");
        Map<String, Object> baked = rowOf(b, ThanksgivingMigration.CATALOG, "baked_potato");
        assertTrue(num(pumpkin, "floor") < num(wheat, "floor") && num(pumpkin, "ceiling") < num(wheat, "ceiling"),
                "a pumpkin is priced below wheat");
        // Each item is priced on its own stock, so side-by-side bands prove nothing: wheat can sit full (at its
        // floor) while bread sits low (at its ceiling). The output's best bid, at its ceiling and the highest mood,
        // must stay under the inputs' lowest ask, at their floor and the lowest mood.
        PricingEngine e = new PricingEngine(1.0, 0.0, b.getDouble("market.spread"));
        double breadBest = e.sellPrice(num(bread, "ceiling") * OrderMath.MAX_MULTIPLIER);
        double wheatLeast = 3 * e.buyPrice(num(wheat, "floor") * OrderMath.MIN_MULTIPLIER);
        assertTrue(breadBest < wheatLeast, "a loaf never sells for more than three wheat cost: " + breadBest
                + " vs " + wheatLeast);
        double bakedBest = e.sellPrice(num(baked, "ceiling") * OrderMath.MAX_MULTIPLIER);
        double potatoLeast = e.buyPrice(num(potato, "floor") * OrderMath.MIN_MULTIPLIER);
        assertTrue(bakedBest < potatoLeast, "a baked potato never sells for more than a potato costs: " + bakedBest
                + " vs " + potatoLeast);
        double fullWheat = 3 * e.buyPrice(e.targetPrice(marketItem(wheat), (long) num(wheat, "full_stock")));
        double seededBread = e.sellPrice(e.targetPrice(marketItem(bread), (long) num(bread, "initial_stock")));
        assertTrue(seededBread < fullWheat, "the reviewed case: full wheat, freshly seeded bread");
        for (String id : NEW_FOODS) {
            Map<String, Object> r = rowOf(b, ThanksgivingMigration.CATALOG, id);
            assertNotNull(Material.matchMaterial(String.valueOf(r.get("material"))), id + "'s material");
            double full = num(r, "full_stock");
            assertTrue(num(r, "initial_stock") < full, id + ": initial stock below full stock");
            assertEquals(full * 0.02, num(r, "max_daily_sell"), full * 0.005, id + ": about 2% a day sold");
            assertEquals(full * 0.04, num(r, "max_daily_buy"), full * 0.01, id + ": about 4% a day bought");
        }
    }

    private static MarketItem marketItem(Map<String, Object> r) {
        return new MarketItem(String.valueOf(r.get("id")), Material.matchMaterial(String.valueOf(r.get("material"))),
                null, num(r, "floor"), num(r, "ceiling"), (long) num(r, "initial_stock"), (long) num(r, "full_stock"),
                (long) num(r, "max_daily_sell"), (long) num(r, "max_daily_buy"));
    }

    private static double num(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).doubleValue();
    }

    @Test
    void feastWeekNamesOnlyWhatCrateSells() {
        List<String> warns = new ArrayList<>();
        MarketSimConfig.parse(bundled(), warns::add);
        assertEquals(List.of(), warns, "every item Feast Week names is in the bundled catalog");
    }

    /** The owner's own seasons: the items Crate doesn't sell are named once, at start, so they know which do nothing. */
    @Test
    void theOwnersSeasonsNameWhatCrateDoesntSellInOneWarn() {
        YamlConfiguration c = bundled();
        List<Object> seasons = new ArrayList<>(c.getList(ThanksgivingMigration.SEASONS));
        seasons.add(season("lucky_week", "03-14", "03-17", "emerald", "green_dye"));
        seasons.add(season("easter_week", "03-25", "03-31", "egg", "carrot"));
        seasons.add(season("summer_splash", "06-20", "06-26", "melon_slice", "sweet_berries"));
        seasons.add(season("fireworks_week", "07-01", "07-05", "firework_rocket", "red_dye", "blue_dye"));
        seasons.add(season("spooky_treats", "10-24", "10-31", "pumpkin", "sugar"));
        c.set(ThanksgivingMigration.SEASONS, seasons);
        List<String> warns = new ArrayList<>();
        MarketSimConfig.parse(c, warns::add);
        assertEquals(List.of("market.sim.seasons.list names item(s) that are not in market.catalog (ignored): "
                        + "blue_dye, egg, emerald, firework_rocket, green_dye, melon_slice, red_dye, sugar, sweet_berries"),
                warns, "one WARN; carrot and pumpkin are sold now");
    }

    private static Map<String, Object> season(String id, String from, String to, String... items) {
        Map<String, Object> pct = new LinkedHashMap<>();
        for (String item : items) {
            pct.put(item, -4);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", id);
        m.put("from", from);
        m.put("to", to);
        m.put("percent", pct);
        m.put("headline", "Something nice is happening!");
        return m;
    }
}
