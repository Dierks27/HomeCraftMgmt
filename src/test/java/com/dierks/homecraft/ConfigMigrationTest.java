package com.dierks.homecraft;

import com.dierks.homecraft.config.MarketSimConfig;
import com.dierks.homecraft.market.sim.RealSymbol;
import com.dierks.homecraft.market.sim.SimSettings;
import com.dierks.homecraft.mini.Grade;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The config migration and backfill must decide from what is PHYSICALLY IN THE FILE.
 *
 * <p>{@code JavaPlugin.reloadConfig()} attaches the jar's bundled config.yml to
 * {@code getConfig()} as DEFAULTS, and {@code ConfigurationSection.contains(path)} answers
 * out of those defaults — so while both methods ran against {@code getConfig()} every
 * "is this key on disk?" question came back {@code true} for anything the jar ships, the
 * backfill was a no-op for the plugin's entire life, and the {@code worlds:} /
 * {@code backups:} / {@code shops:} sections added in 0.21.0 never reached a single
 * server's config.yml. These tests pin that shut. No Bukkit server is needed:
 * {@link YamlConfiguration} is plain Java.
 */
class ConfigMigrationTest {

    /**
     * Departments the Store/Market tab row can show beside "All" — mirrors
     * {@code Departments.MAX_TABS}, which is package-private to the gui package.
     */
    private static final int TAB_CEILING = 8;

    /**
     * The bundled config.yml — the very resource the plugin backfills from at runtime.
     *
     * <p>Loaded with the throwing {@code load(Reader)} rather than
     * {@code YamlConfiguration.loadConfiguration(Reader)}: the static factory swallows a
     * parse error and reports it through {@code Bukkit.getLogger()}, which NPEs with no
     * server running. A YAML mistake in the bundled file should fail here with the line
     * number, not as a NullPointerException from inside Bukkit.
     */
    private static YamlConfiguration bundled() throws IOException, InvalidConfigurationException {
        try (InputStream in = ConfigMigrationTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "the bundled config.yml is missing from the test classpath");
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                YamlConfiguration defaults = new YamlConfiguration();
                defaults.load(reader);
                return defaults;
            }
        }
    }

    /**
     * A pre-0.21 on-disk config.yml: the three-tier shipping scheme, an edited market
     * catalog, and no {@code worlds:} / {@code backups:} / {@code shops:} /
     * {@code config_revision} at all — i.e. what the live server is actually running.
     */
    private static YamlConfiguration legacyOnDisk() {
        return yaml("""
                store:
                  name: "Crate"
                  display_url: "www.Crate.com"
                crafting:
                  pc:
                    base_block: PLAYER_HEAD
                    head_texture: ""
                market:
                  catalog:
                    - id: oak_log
                      material: OAK_LOG
                      full_stock: 8000
                    - id: diamond
                      material: DIAMOND
                      full_stock: 200
                  sell_limits:
                    max_money_per_day: 250
                shipping:
                  mode: PERCENTAGE
                  tiers:
                    one_day:   { real_hours: 24, percent: 10 }
                    two_day:   { real_hours: 48, percent: 5 }
                    three_day: { real_hours: 72, percent: 0 }
                skins:
                  pc: "OLD_PC_SKIN"
                """);
    }

    private static int intAt(Map<?, ?> row, String key) {
        Object v = row.get(key);
        assertTrue(v instanceof Number, key + " should be a number but was " + v);
        return ((Number) v).intValue();
    }

    private static YamlConfiguration yaml(String text) {
        YamlConfiguration c = new YamlConfiguration();
        try {
            c.loadFromString(text);
        } catch (InvalidConfigurationException e) {
            throw new AssertionError("test fixture is not valid YAML", e);
        }
        return c;
    }

    @Test
    void migrationAndBackfillWriteEverySectionTheFileIsMissing() throws Exception {
        YamlConfiguration onDisk = legacyOnDisk();

        List<String> migrated = HomeCraftManagement.migrateConfig(onDisk, "world");
        List<String> added = HomeCraftManagement.backfillConfig(onDisk, bundled());

        assertFalse(migrated.isEmpty(), "a pre-0.21 file needs migrating");
        assertFalse(added.isEmpty(), "a pre-0.21 file is missing whole sections");

        assertEquals(List.of("world"), onDisk.getStringList("worlds.economy_enabled"));
        assertTrue(onDisk.isSet("backups.enabled"), "backups.enabled must reach the file");
        assertTrue(onDisk.getBoolean("backups.enabled"));
        assertTrue(onDisk.isSet("shops.glow.enabled"), "shops.glow.enabled must reach the file");
        assertTrue(onDisk.getBoolean("shops.glow.enabled"));
        assertEquals(HomeCraftManagement.CONFIG_REVISION, onDisk.getInt("config_revision"));

        // The economy sandbox reads this list; before the fix it was never written and
        // the plugin silently ran off the jar's invisible [world].
        assertTrue(added.contains("worlds.log_blocked_attempts"), "added: " + added);
    }

    /**
     * The regression test for the bug itself: attach the bundled config as defaults —
     * exactly what {@code reloadConfig()} does — and the pass must still write the keys.
     */
    @Test
    void jarDefaultsCannotShadowTheFile() throws Exception {
        YamlConfiguration defaults = bundled();
        YamlConfiguration onDisk = legacyOnDisk();
        onDisk.setDefaults(defaults);

        // The trap, demonstrated: the keys are NOT in the file, yet the reads the old code
        // made all answer out of the jar.
        assertTrue(onDisk.contains("worlds.economy_enabled"), "defaults answer contains()");
        assertFalse(onDisk.contains("worlds.economy_enabled", true), "…but it is not in the file");
        assertEquals(HomeCraftManagement.CONFIG_REVISION, onDisk.getInt("config_revision"),
                "defaults answer getInt() as well — this reads the jar, not the file");
        assertFalse(onDisk.isSet("config_revision"), "…though the file carries no such key");

        HomeCraftManagement.migrateConfig(onDisk, "world");
        HomeCraftManagement.backfillConfig(onDisk, defaults);

        assertTrue(onDisk.contains("worlds.economy_enabled", true), "worlds.economy_enabled must be written");
        assertTrue(onDisk.contains("worlds.log_blocked_attempts", true));
        assertTrue(onDisk.contains("backups.enabled", true));
        assertTrue(onDisk.contains("shops.glow.enabled", true));
        assertTrue(onDisk.contains("config_revision", true));
    }

    /**
     * A brand-new install already holds the whole bundled file: nothing to do, nothing logged.
     *
     * <p>This holds because {@code saveDefaultConfig()} copies the jar resource verbatim and
     * that resource already satisfies every migration guard. The invariant it depends on most
     * silently is {@code config_revision}, pinned separately below — bump
     * {@link HomeCraftManagement#CONFIG_REVISION} without bumping the YAML and every fresh
     * install would snapshot, rewrite and log a migration on its first boot.
     */
    @Test
    void aFreshInstallIsANoOp() throws Exception {
        YamlConfiguration onDisk = bundled();

        assertEquals(List.of(), HomeCraftManagement.migrateConfig(onDisk, "world"),
                "saveDefaultConfig() just wrote this file — migration must not touch it");
        assertEquals(List.of(), HomeCraftManagement.backfillConfig(onDisk, bundled()),
                "a fresh install must not log a backfill line");
    }

    /** The Java gate and the shipped YAML must agree, or first boot migrates itself. */
    @Test
    void theBundledRevisionMatchesTheCodeGate() throws Exception {
        assertEquals(HomeCraftManagement.CONFIG_REVISION, bundled().getInt("config_revision"),
                "bump config_revision in src/main/resources/config.yml alongside CONFIG_REVISION");
    }

    /**
     * The rebalance writes hard-coded numbers that are supposed to mirror the bundled file.
     * If the two drift, a server whose config.yml lacks a section silently ends up with
     * values the shipped defaults contradict. Run the rebalance over the bundled config and
     * every value it touches must already be what the file says.
     */
    @Test
    void theRebalanceNumbersMatchTheBundledDefaults() throws Exception {
        YamlConfiguration shipped = bundled();
        YamlConfiguration onDisk = bundled();
        onDisk.set("config_revision", 0); // below every revision step, so the rebalance runs

        assertFalse(HomeCraftManagement.migrateConfig(onDisk, "world").isEmpty(),
                "a revision-0 file must run the rebalance");

        for (String key : List.of(
                "market.sell_limits.max_money_per_day",
                "market.buy_limits.max_money_per_day",
                "marketplace.fee.commission_percent",
                "printer.fee",
                "printer.public_fee",
                "arcade.pity.tokens")) {
            assertEquals(shipped.get(key), onDisk.get(key),
                    key + " has drifted from src/main/resources/config.yml");
        }
        for (String key : List.of(
                "market.catalog",
                "market.sell_limits.ranks",
                "market.buy_limits.ranks",
                "packs",
                "arcade.crates.arcade_crate.rewards",
                "arcade.prizes",
                "arcade.lotto.payouts",
                "arcade.quests.daily_pool",
                "arcade.quests.weekly_pool",
                "arcade.achievements")) {
            assertEquals(shipped.getMapList(key), onDisk.getMapList(key),
                    key + " has drifted from src/main/resources/config.yml — the hard-coded "
                            + "numbers in applyEconomyRebalance and the shipped file must agree, "
                            + "or a server missing this section ends up contradicting the defaults");
        }
    }

    /** Every value the admin edited survives; only missing keys are added. */
    @Test
    void adminEditsSurvive() throws Exception {
        YamlConfiguration onDisk = legacyOnDisk();
        onDisk.set("worlds.economy_enabled", List.of("survival", "resource"));
        onDisk.set("shops.glow.radius", 4);

        HomeCraftManagement.migrateConfig(onDisk, "world");
        HomeCraftManagement.backfillConfig(onDisk, bundled());

        assertEquals(List.of("survival", "resource"), onDisk.getStringList("worlds.economy_enabled"));
        assertEquals(4, onDisk.getInt("shops.glow.radius"));
        assertEquals("Crate", onDisk.getString("store.name"));
        assertTrue(onDisk.getBoolean("shops.glow.enabled"), "the rest of shops: still lands");
    }

    @Test
    void legacyShippingGainsTheExpressTier() {
        YamlConfiguration onDisk = legacyOnDisk();

        HomeCraftManagement.migrateConfig(onDisk, "world");

        assertEquals(5, onDisk.getInt("shipping.tiers.express.real_minutes"));
        assertEquals(1, onDisk.getInt("shipping.tiers.one_day.real_hours"));
        assertEquals(8, onDisk.getInt("shipping.tiers.three_day.real_hours"));
        assertEquals("PERCENTAGE", onDisk.getString("shipping.mode"), "the admin's mode is preserved");
        assertTrue(onDisk.getBoolean("shipping.locker.enabled"));
    }

    /** The pass-3 rebalance runs exactly once and stamps the revision that gates it. */
    @Test
    void theEconomyRebalanceIsAppliedOnceAndStamped() {
        YamlConfiguration onDisk = legacyOnDisk();

        HomeCraftManagement.migrateConfig(onDisk, "world");

        assertEquals(5000, onDisk.getInt("market.sell_limits.max_money_per_day"));
        assertEquals(5000, onDisk.getInt("market.buy_limits.max_money_per_day"));
        assertEquals(150, onDisk.getInt("printer.public_fee"));
        assertEquals(150, onDisk.getInt("arcade.pity.tokens"), "25 from pass 3, then 150 from revision 14");

        // The admin's own catalog rows are kept — they only gain the new per-item caps.
        List<Map<?, ?>> catalog = onDisk.getMapList("market.catalog");
        assertEquals(2, catalog.size(), "the admin's two rows, not the bundled catalog");
        assertEquals("oak_log", catalog.get(0).get("id"));
        assertEquals(160, intAt(catalog.get(0), "max_daily_sell"));
        assertEquals(320, intAt(catalog.get(0), "max_daily_buy"));
        assertEquals(8000, intAt(catalog.get(0), "full_stock"), "the admin's stock is untouched");

        assertEquals(HomeCraftManagement.CONFIG_REVISION, onDisk.getInt("config_revision"));
        assertEquals("OLD_PC_SKIN", onDisk.getString("crafting.pc.head_texture"),
                "the blank legacy head texture is seeded from skins.pc");

        assertEquals(List.of(), HomeCraftManagement.migrateConfig(onDisk, "world"),
                "a second pass must be a no-op — the revision gate is stamped");
    }

    /**
     * Pass 3 kept its caps list inline and it drifted from the shipped catalog: it carried
     * four of the six rows, so every server that upgraded through it has cobblestone and
     * diamond uncapped. Revision 4 gives every shipped row the caps the bundled file says
     * it should have.
     */
    @Test
    void everyShippedCatalogRowEndsUpCapped() throws Exception {
        YamlConfiguration shipped = bundled();
        YamlConfiguration onDisk = bundled();
        onDisk.set("market.catalog", withoutCaps(shipped));
        onDisk.set("config_revision", 3); // already past the pass-3 gate, as a live server is

        assertFalse(HomeCraftManagement.migrateConfig(onDisk, "world").isEmpty(),
                "a revision-3 file still owes the missing caps");

        assertEquals(shipped.getMapList("market.catalog"), onDisk.getMapList("market.catalog"),
                "every shipped row must end up carrying the shipped caps");
        assertEquals(HomeCraftManagement.CONFIG_REVISION, onDisk.getInt("config_revision"));
    }

    /**
     * Bumping the revision must NOT drag a server back through the pass-3 rebalance, which
     * rewrites whole sections — the caps step fills gaps and touches nothing else.
     */
    @Test
    void theCapsStepLeavesTunedValuesAlone() throws Exception {
        YamlConfiguration onDisk = bundled();
        onDisk.set("market.catalog", withoutCaps(bundled()));
        onDisk.set("config_revision", 3);
        onDisk.set("market.sell_limits.max_money_per_day", 250);
        onDisk.set("marketplace.fee.commission_percent", 2.5);
        onDisk.set("printer.public_fee", 999);
        onDisk.set("arcade.pity.tokens", 60);
        onDisk.set("packs", List.of(Map.of("id", "custom", "price", 42.0)));

        HomeCraftManagement.migrateConfig(onDisk, "world");

        assertEquals(250, onDisk.getInt("market.sell_limits.max_money_per_day"),
                "revision 4 must not re-run the pass-3 rebalance");
        assertEquals(2.5, onDisk.getDouble("marketplace.fee.commission_percent"));
        assertEquals(999, onDisk.getInt("printer.public_fee"));
        assertEquals(60, onDisk.getInt("arcade.pity.tokens"));
        assertEquals("custom", onDisk.getMapList("packs").get(0).get("id"), "packs must survive");

        // …while the step it IS meant to do still happened.
        assertEquals(10, intAt(rowFor(onDisk, "diamond"), "max_daily_sell"));
        assertEquals(20, intAt(rowFor(onDisk, "diamond"), "max_daily_buy"));
    }

    /** A cap the admin set themselves — a deliberate 0 included — is never overwritten. */
    @Test
    void anAdminsOwnCapIsKept() throws Exception {
        YamlConfiguration onDisk = bundled();
        List<Map<String, Object>> rows = withoutCaps(bundled());
        for (Map<String, Object> row : rows) {
            if ("diamond".equals(row.get("id"))) {
                row.put("max_daily_sell", 0);
            }
        }
        onDisk.set("market.catalog", rows);
        onDisk.set("config_revision", 3);

        HomeCraftManagement.migrateConfig(onDisk, "world");

        assertEquals(0, intAt(rowFor(onDisk, "diamond"), "max_daily_sell"), "their 0 stands");
        assertEquals(20, intAt(rowFor(onDisk, "diamond"), "max_daily_buy"), "the gap is still filled");
    }

    /** The shipped catalog with every daily cap stripped — a pass-3-era file. */
    private static List<Map<String, Object>> withoutCaps(YamlConfiguration shipped) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<?, ?> row : shipped.getMapList("market.catalog")) {
            Map<String, Object> m = new LinkedHashMap<>();
            row.forEach((k, v) -> m.put(String.valueOf(k), v));
            m.remove("max_daily_sell");
            m.remove("max_daily_buy");
            rows.add(m);
        }
        return rows;
    }

    private static Map<?, ?> rowFor(YamlConfiguration c, String id) {
        for (Map<?, ?> row : c.getMapList("market.catalog")) {
            if (id.equals(row.get("id"))) {
                return row;
            }
        }
        throw new AssertionError("no market.catalog row with id " + id);
    }

    /**
     * A department added to the shipped defaults cannot reach an existing server through the
     * backfill — every server already has a departments list, and the backfill only adds keys
     * that are missing entirely. Revision 5 inserts it, or everything the classifier routes
     * there silently lands in Misc.
     */
    @Test
    void aLegacyDepartmentListGainsMaterials() throws Exception {
        YamlConfiguration onDisk = bundled();
        onDisk.set("config_revision", 4);
        onDisk.set("marketplace.departments", List.of(
                "Blocks", "Food", "Tools", "Weapons", "Armor", "Redstone", "Collectibles", "Misc"));

        assertFalse(HomeCraftManagement.migrateConfig(onDisk, "world").isEmpty());

        List<String> depts = onDisk.getStringList("marketplace.departments");
        assertEquals(List.of("Blocks", "Materials", "Food", "Tools", "Combat",
                "Redstone", "Collectibles", "Misc"), depts);
        assertEquals("Materials", depts.get(1), "inserted after Blocks, not appended");
        assertEquals("Misc", depts.get(depts.size() - 1),
                "the catch-all must stay last — the classifier falls back to it");
        assertEquals(TAB_CEILING, depts.size(),
                "the tab row is nine slots and \"All\" takes the first");
    }

    /**
     * Weapons + Armor → Combat, standing where Weapons stood. Adding Materials without this
     * would take the list to nine, and the ninth department gets no tab at all.
     */
    @Test
    void weaponsAndArmorFoldIntoCombat() throws Exception {
        YamlConfiguration onDisk = bundled();
        onDisk.set("config_revision", 4);
        onDisk.set("marketplace.departments", List.of(
                "Blocks", "Food", "Tools", "Weapons", "Armor", "Redstone", "Collectibles", "Misc"));

        assertTrue(HomeCraftManagement.mergeDepartments(onDisk, List.of("Weapons", "Armor"), "Combat"));

        assertEquals(List.of("Blocks", "Food", "Tools", "Combat", "Redstone", "Collectibles", "Misc"),
                onDisk.getStringList("marketplace.departments"));
    }

    /** An admin override pointing at a folded department follows it, rather than falling to Misc. */
    @Test
    void overridesFollowTheMergedDepartment() throws Exception {
        YamlConfiguration onDisk = bundled();
        onDisk.set("config_revision", 4);
        onDisk.set("marketplace.departments", List.of(
                "Blocks", "Food", "Tools", "Weapons", "Armor", "Redstone", "Collectibles", "Misc"));
        onDisk.set("marketplace.category_overrides.FLINT", "Weapons");
        onDisk.set("marketplace.category_overrides.SHIELD", "Armor");
        onDisk.set("marketplace.category_overrides.ENDER_PEARL", "Misc");

        HomeCraftManagement.migrateConfig(onDisk, "world");

        assertEquals("Combat", onDisk.getString("marketplace.category_overrides.FLINT"));
        assertEquals("Combat", onDisk.getString("marketplace.category_overrides.SHIELD"));
        assertEquals("Misc", onDisk.getString("marketplace.category_overrides.ENDER_PEARL"),
                "an override naming a department that survived is not touched");
    }

    /** A list already carrying Combat loses the folded names without gaining a second Combat. */
    @Test
    void mergingIntoADepartmentThatIsAlreadyThereDoesNotDuplicateIt() throws Exception {
        YamlConfiguration onDisk = bundled();
        onDisk.set("marketplace.departments", List.of(
                "Blocks", "Combat", "Food", "Weapons", "Armor", "Misc"));

        assertTrue(HomeCraftManagement.mergeDepartments(onDisk, List.of("Weapons", "Armor"), "Combat"));

        assertEquals(List.of("Blocks", "Combat", "Food", "Misc"),
                onDisk.getStringList("marketplace.departments"));
    }

    /** Nothing to fold: the list is left byte-for-byte as the admin has it. */
    @Test
    void mergingLeavesAListWithoutThoseDepartmentsAlone() throws Exception {
        YamlConfiguration onDisk = bundled();
        List<String> before = onDisk.getStringList("marketplace.departments");

        assertFalse(HomeCraftManagement.mergeDepartments(onDisk, List.of("Weapons", "Armor"), "Combat"));

        assertEquals(before, onDisk.getStringList("marketplace.departments"));
    }

    /**
     * The shipped list has to fit the tab row. The Store and Market paint "All" into slot 0
     * and one department per slot after it, so a ninth shipped department would be sold but
     * unreachable from a tab — which is exactly the flat-grid problem the tabs exist to fix.
     */
    @Test
    void theShippedDepartmentListFitsTheTabRow() throws Exception {
        List<String> depts = bundled().getStringList("marketplace.departments");
        assertTrue(depts.size() <= TAB_CEILING,
                "marketplace.departments must fit the tab row, was " + depts);
        assertEquals("Misc", depts.get(depts.size() - 1),
                "the classifier's catch-all has to be last");
    }

    /** A list that already names it is left exactly as the admin ordered it. */
    @Test
    void aDepartmentListThatAlreadyHasItIsLeftAlone() throws Exception {
        YamlConfiguration onDisk = bundled();
        onDisk.set("config_revision", 4);
        List<String> before = onDisk.getStringList("marketplace.departments");

        HomeCraftManagement.migrateConfig(onDisk, "world");

        assertEquals(before, onDisk.getStringList("marketplace.departments"));
        assertEquals(HomeCraftManagement.CONFIG_REVISION, onDisk.getInt("config_revision"));
    }

    /**
     * Revision 6: the Common rarity shipped as a light-grey pane — the colour of the inventory
     * slot behind it — so every Common Museum header rendered as an empty tile. The backfill
     * cannot repair that: the key is present on every server, just holding the wrong value.
     */
    @Test
    void theCommonRarityIconIsCorrectedFromLightGray() throws Exception {
        YamlConfiguration onDisk = bundled();
        onDisk.set("config_revision", 5);
        onDisk.set("minis.rarity_styles.COMMON.pane", "LIGHT_GRAY");

        assertFalse(HomeCraftManagement.migrateConfig(onDisk, "world").isEmpty());

        assertEquals("WHITE", onDisk.getString("minis.rarity_styles.COMMON.pane"));
        assertEquals(HomeCraftManagement.CONFIG_REVISION, onDisk.getInt("config_revision"));
    }

    /** A value the admin chose is theirs, even when ours turned out to be wrong. */
    @Test
    void aCustomisedRarityIconIsLeftAlone() throws Exception {
        YamlConfiguration onDisk = bundled();
        onDisk.set("config_revision", 5);
        onDisk.set("minis.rarity_styles.COMMON.pane", "ORANGE");

        HomeCraftManagement.migrateConfig(onDisk, "world");

        assertEquals("ORANGE", onDisk.getString("minis.rarity_styles.COMMON.pane"));
    }

    /**
     * The market screen stopped buying, so its title had to stop promising it.
     *
     * <p>The blind backfill cannot do this — it only ADDS missing keys — so without a
     * migration every existing server would keep a title advertising a feature that is gone.
     */
    @Test
    void theMarketTitleStopsPromisingSomethingItNoLongerDoes() throws Exception {
        YamlConfiguration onDisk = bundled();
        onDisk.set("config_revision", 9);
        onDisk.set("menus.market_title", "&1Market — instant buy/sell");

        assertFalse(HomeCraftManagement.migrateConfig(onDisk, "world").isEmpty());

        assertEquals("&1Sell to Crate", onDisk.getString("menus.market_title"));
        assertEquals(HomeCraftManagement.CONFIG_REVISION, onDisk.getInt("config_revision"));
    }

    /** A title the admin wrote is theirs, whatever the screen beneath it now does. */
    @Test
    void anAdminsOwnMarketTitleIsLeftAlone() throws Exception {
        YamlConfiguration onDisk = bundled();
        onDisk.set("config_revision", 9);
        onDisk.set("menus.market_title", "&aDierks Trading Post");

        HomeCraftManagement.migrateConfig(onDisk, "world");

        assertEquals("&aDierks Trading Post", onDisk.getString("menus.market_title"));
    }

    /** replaceShippedDefault only fires on an exact (case-insensitive) match of what we shipped. */
    @Test
    void replacingAShippedDefaultIsExactAndIdempotent() throws Exception {
        YamlConfiguration c = bundled();

        c.set("minis.rarity_styles.COMMON.pane", "  light_gray  ");
        assertTrue(HomeCraftManagement.replaceShippedDefault(
                c, "minis.rarity_styles.COMMON.pane", "LIGHT_GRAY", "WHITE"),
                "casing and stray spaces should not hide a value we shipped");
        assertEquals("WHITE", c.getString("minis.rarity_styles.COMMON.pane"));

        // Already corrected — a second run must be a no-op, not a second rewrite.
        assertFalse(HomeCraftManagement.replaceShippedDefault(
                c, "minis.rarity_styles.COMMON.pane", "LIGHT_GRAY", "WHITE"));

        // Absent key: nothing to correct, and nothing created.
        assertFalse(HomeCraftManagement.replaceShippedDefault(
                c, "minis.rarity_styles.NOPE.pane", "LIGHT_GRAY", "WHITE"));
        assertNull(c.get("minis.rarity_styles.NOPE.pane", null));
    }

    /** Backfilled keys keep the bundled file's comments, and new sections keep their header. */
    @Test
    void backfilledKeysCarryTheirComments() throws Exception {
        YamlConfiguration onDisk = legacyOnDisk();

        HomeCraftManagement.migrateConfig(onDisk, "world");
        HomeCraftManagement.backfillConfig(onDisk, bundled());

        assertFalse(onDisk.getComments("worlds").isEmpty(),
                "the worlds: header explains what the economy sandbox does — the admin needs it");
        assertFalse(onDisk.getComments("backups").isEmpty(), "the backups: header should come across");
        assertFalse(onDisk.getInlineComments("worlds.log_blocked_attempts").isEmpty(),
                "inline comments come across too");

        String written = onDisk.saveToString();
        assertTrue(written.contains("economy_enabled"), written);
        assertTrue(written.contains("Which worlds the ECONOMY runs in"),
                "the bundled header must be emitted alongside the keys it explains");

        // …and it is still there after the file is read back, which is what the admin sees.
        YamlConfiguration reloaded = yaml(written);
        assertEquals(List.of("world"), reloaded.getStringList("worlds.economy_enabled"));
        assertFalse(reloaded.getComments("worlds").isEmpty(), "comments survive the round-trip");
    }

    /**
     * The shape the live server is most likely in. Every dotted read against a config with
     * defaults attached walks {@code getConfigurationSection()}, which MATERIALISES a real
     * empty section for a segment that exists only in the jar — and any later
     * {@code saveConfig()} (the admin GUI, the Mini catalog writer, the pack editor) then
     * wrote that empty section out. So the file can carry {@code worlds: {}} rather than no
     * {@code worlds} key at all. The fix has to fill it in either way.
     */
    @Test
    void emptySectionsLeftBehindByTheBugAreFilledIn() throws Exception {
        YamlConfiguration onDisk = legacyOnDisk();
        onDisk.createSection("worlds");
        onDisk.createSection("backups");
        onDisk.createSection("shops");

        HomeCraftManagement.migrateConfig(onDisk, "world");
        HomeCraftManagement.backfillConfig(onDisk, bundled());

        assertEquals(List.of("world"), onDisk.getStringList("worlds.economy_enabled"));
        assertTrue(onDisk.getBoolean("backups.enabled"));
        assertEquals(14, onDisk.getInt("backups.keep"));
        assertTrue(onDisk.getBoolean("shops.glow.enabled"));
        assertEquals(16, onDisk.getInt("shops.glow.radius"));
    }

    /** A section the admin already annotated is never re-commented from the jar. */
    @Test
    void anAdminsOwnSectionCommentIsKept() throws Exception {
        YamlConfiguration onDisk = legacyOnDisk();
        onDisk.set("shops.glow.enabled", false);
        onDisk.setComments("shops", List.of(" my own notes about shops"));

        HomeCraftManagement.backfillConfig(onDisk, bundled());

        assertEquals(List.of(" my own notes about shops"), onDisk.getComments("shops"));
        assertFalse(onDisk.getBoolean("shops.glow.enabled"), "and their value stands");
        assertTrue(onDisk.isSet("shops.hologram.enabled"), "while the missing leaves still land");
    }

    // ---- revision 7: wild Mini spawns, and the stars an editor ate ----------------

    /**
     * Revision 7: three hours of play produced two wild Minis, both inside 100 blocks, both
     * found in seconds. Every shipped number was pulling the same way, so all four moved
     * together — a rarer roll, a far longer walk, a find window short enough to be a hunt.
     * Revision 13 then brought the walk back in and lengthened the window (nobody found one
     * at 96–128 blocks in 3 minutes), so a rev-6 file lands on 13's numbers, via 7.
     */
    @Test
    void theNaturalSpawnDefaultsAreRetuned() throws Exception {
        YamlConfiguration onDisk = bundled();
        onDisk.set("config_revision", 6);
        onDisk.set("minis.loot.natural.interval_ticks", 12000);
        onDisk.set("minis.loot.natural.despawn_minutes", 10);
        onDisk.set("minis.loot.natural.min_distance", 24);
        onDisk.set("minis.loot.natural.max_distance", 48);

        List<String> log = HomeCraftManagement.migrateConfig(onDisk, "world");

        assertTrue(log.stream().anyMatch(l -> l.contains("96–128")), "revision 7 ran: " + log);
        assertEquals(24000, onDisk.getInt("minis.loot.natural.interval_ticks"));
        assertEquals(5, onDisk.getInt("minis.loot.natural.despawn_minutes"), "3 (rev 7) → 5 (rev 13)");
        assertEquals(48, onDisk.getInt("minis.loot.natural.min_distance"), "96 (rev 7) → 48 (rev 13)");
        assertEquals(96, onDisk.getInt("minis.loot.natural.max_distance"), "128 (rev 7) → 96 (rev 13)");
        assertEquals(HomeCraftManagement.CONFIG_REVISION, onDisk.getInt("config_revision"));
    }

    /** Numbers the admin has already tuned are theirs, exactly as a tuned String would be. */
    @Test
    void tunedNaturalSpawnNumbersAreLeftAlone() throws Exception {
        YamlConfiguration onDisk = bundled();
        onDisk.set("config_revision", 6);
        onDisk.set("minis.loot.natural.interval_ticks", 6000);
        onDisk.set("minis.loot.natural.despawn_minutes", 15);
        onDisk.set("minis.loot.natural.min_distance", 24);   // still ours
        onDisk.set("minis.loot.natural.max_distance", 60);

        HomeCraftManagement.migrateConfig(onDisk, "world");

        assertEquals(6000, onDisk.getInt("minis.loot.natural.interval_ticks"));
        assertEquals(15, onDisk.getInt("minis.loot.natural.despawn_minutes"));
        assertEquals(48, onDisk.getInt("minis.loot.natural.min_distance"), "ours moves (via rev 7 and 13)");
        assertEquals(60, onDisk.getInt("minis.loot.natural.max_distance"), "theirs does not");
    }

    /**
     * The whole point of a numeric replaceShippedDefault. The String version writes a String,
     * and {@code getInt} on {@code "24000"} returns the DEFAULT rather than the value — so a
     * migration using it would look like it had worked while the old cadence kept running.
     */
    @Test
    void replacingAShippedNumberLeavesANumberBehind() throws Exception {
        YamlConfiguration c = bundled();
        c.set("minis.loot.natural.interval_ticks", 12000);

        assertTrue(HomeCraftManagement.replaceShippedInt(c, "minis.loot.natural.interval_ticks", 12000, 24000));
        assertInstanceOf(Number.class, c.get("minis.loot.natural.interval_ticks"),
                "written as a String, getInt would silently fall back to its default");
        assertEquals(24000, c.getInt("minis.loot.natural.interval_ticks", -1));

        // Idempotent, and it never invents a key.
        assertFalse(HomeCraftManagement.replaceShippedInt(c, "minis.loot.natural.interval_ticks", 12000, 24000));
        assertFalse(HomeCraftManagement.replaceShippedInt(c, "minis.loot.natural.nope", 1, 2));
        assertNull(c.get("minis.loot.natural.nope", null));

        // A value that is not a number at all is not ours to touch.
        c.set("minis.loot.natural.despawn_minutes", "ten");
        assertFalse(HomeCraftManagement.replaceShippedInt(c, "minis.loot.natural.despawn_minutes", 10, 3));
        assertEquals("ten", c.getString("minis.loot.natural.despawn_minutes"));
    }

    /**
     * The hint had been claiming "within 100 blocks" while the spawn band was 24–48, and it
     * would have gone on being wrong at 96–128. It reads the distance now instead of stating it.
     */
    @Test
    void theSpawnHintStopsStatingADistanceItCannotKnow() throws Exception {
        YamlConfiguration onDisk = bundled();
        onDisk.set("config_revision", 6);
        onDisk.set("minis.announce.hint_radius_text", "within 100 blocks of a player");

        HomeCraftManagement.migrateConfig(onDisk, "world");

        assertEquals("within %blocks% blocks of a player",
                onDisk.getString("minis.announce.hint_radius_text"));
    }

    /**
     * "Creeper ?" — ☆ saved back by an editor set to ANSI instead of UTF-8. The file still
     * parses and nothing errors; every Mini in the game just loses its star, with no way to
     * see why from in-game.
     */
    @Test
    void gradeStarsFlattenedByANonUtf8SaveAreRestored() throws Exception {
        YamlConfiguration onDisk = bundled();
        onDisk.set("config_revision", 6);
        onDisk.set("minis.grades.STANDARD.symbol", "?");
        onDisk.set("minis.grades.GRADED.symbol", "??");
        onDisk.set("minis.grades.MINT.symbol", "???");

        HomeCraftManagement.migrateConfig(onDisk, "world");

        assertEquals(Grade.STANDARD.symbol(), onDisk.getString("minis.grades.STANDARD.symbol"));
        assertEquals(Grade.GRADED.symbol(), onDisk.getString("minis.grades.GRADED.symbol"));
        assertEquals(Grade.MINT.symbol(), onDisk.getString("minis.grades.MINT.symbol"));
    }

    /** A symbol the admin chose is theirs — including one that merely differs from ours. */
    @Test
    void aChosenGradeSymbolIsNotRepaired() throws Exception {
        YamlConfiguration onDisk = bundled();
        onDisk.set("config_revision", 6);
        onDisk.set("minis.grades.STANDARD.symbol", "?!");   // has a real character in it
        onDisk.set("minis.grades.GRADED.symbol", "++");

        HomeCraftManagement.migrateConfig(onDisk, "world");

        assertEquals("?!", onDisk.getString("minis.grades.STANDARD.symbol"));
        assertEquals("++", onDisk.getString("minis.grades.GRADED.symbol"));
    }

    /**
     * A wild Mini's label must stay much tighter than the general effects radius.
     *
     * <p>Sixteen blocks is the right distance to start particles on a trophy someone has put
     * on a shelf, and far too generous for a hunt: a TextDisplay is legible from hundreds of
     * blocks, so a label lit at that range points at the prize instead of naming it. The two
     * look like they want to be one number, which is exactly why they must not become one.
     */
    @Test
    void theWildLabelIsReadOnlyFromCloseUp() throws Exception {
        YamlConfiguration bundled = bundled();
        double range = bundled.getDouble("minis.effects.wild_hologram_range", -1);
        double radius = bundled.getDouble("minis.effects.radius", -1);

        assertTrue(range > 0, "the shipped label should be reachable, not switched off");
        assertTrue(range <= radius / 2,
                "wild_hologram_range (" + range + ") must stay well inside effects.radius ("
                        + radius + ") — at the same distance the label becomes a pointer");
    }

    /**
     * A courier's crate stops being Steve's head on an upgraded server.
     *
     * <p>{@code skins.courier_package} shipped with three blank values for three releases, and a
     * blank head texture is a default player head. The textures landed in the bundled file
     * later — but the backfill only adds keys that are MISSING, and an upgraded config.yml
     * already has all three, holding "". Without this revision step, every server that
     * upgraded would keep handing its couriers a severed Steve head to carry for the whole
     * delivery, and nothing in the file would ever look wrong.
     */
    @Test
    void blankCourierCrateSkinsAreFilledIn() throws Exception {
        YamlConfiguration onDisk = bundled();
        onDisk.set("config_revision", 10);
        onDisk.set("skins.courier_package.local", "");
        onDisk.set("skins.courier_package.regional", "");
        onDisk.set("skins.courier_package.long_haul", "");

        HomeCraftManagement.migrateConfig(onDisk, "world");

        for (Map.Entry<String, String> band : HomeCraftManagement.COURIER_PACKAGE_SKINS.entrySet()) {
            assertEquals(band.getValue(),
                    onDisk.getString("skins.courier_package." + band.getKey()),
                    band.getKey() + " is still blank — that is a Steve head in the courier's hand");
        }
    }

    /** A texture the admin chose is theirs; only an empty value is replaced. */
    @Test
    void anAdminsOwnCrateSkinIsLeftAlone() throws Exception {
        YamlConfiguration onDisk = bundled();
        onDisk.set("config_revision", 10);
        onDisk.set("skins.courier_package.local", "my-own-crate");
        onDisk.set("skins.courier_package.regional", "");

        HomeCraftManagement.migrateConfig(onDisk, "world");

        assertEquals("my-own-crate", onDisk.getString("skins.courier_package.local"));
        assertEquals(HomeCraftManagement.COURIER_PACKAGE_SKINS.get("regional"),
                onDisk.getString("skins.courier_package.regional"));
    }

    /**
     * The migration's hard-coded crate textures must be the ones the bundled file ships, or a
     * fresh install and an upgraded server end up carrying different parcels.
     */
    @Test
    void theCrateTexturesMatchTheBundledDefaults() throws Exception {
        YamlConfiguration shipped = bundled();
        for (Map.Entry<String, String> band : HomeCraftManagement.COURIER_PACKAGE_SKINS.entrySet()) {
            assertEquals(shipped.getString("skins.courier_package." + band.getKey()), band.getValue(),
                    "skins.courier_package." + band.getKey() + " has drifted from "
                            + "src/main/resources/config.yml");
        }
    }

    /** Lowercase grade keys parse at load, so the repair has to find them too. */
    @Test
    void lowercaseGradeKeysAreRepairedAsWell() throws Exception {
        YamlConfiguration c = bundled();
        c.set("minis.grades.standard.symbol", "?");

        assertEquals(List.of("standard"), HomeCraftManagement.repairGradeSymbols(c));
        assertEquals(Grade.STANDARD.symbol(), c.getString("minis.grades.standard.symbol"));
    }

    // ---- revision 12: the quests that pushed Mini output ---------------------------

    /** The quest pool rev 9 shipped, as a live rev-11 server has it. */
    private static YamlConfiguration rev11Quests() throws Exception {
        YamlConfiguration onDisk = bundled();
        onDisk.set("config_revision", 11);
        onDisk.set("arcade.quests.daily_pool", null); // a rev-11 file has only the old lists
        onDisk.set("arcade.quests.weekly_pool", null);
        onDisk.set("arcade.quests.daily", List.of(
                quest("fish_daily", "CATCH_FISH", 8, 3, "Catch 8 fish"),
                quest("walk_daily", "TRAVEL_ON_FOOT", 800, 3, "Travel 800 blocks on foot"),
                quest("print_daily", "PRINT_MINI", 1, 2, "Print a Mini at a Printer"),
                quest("sell_daily", "SELL_MARKET", 300, 1, "Sell $300 to the Market")));
        onDisk.set("arcade.quests.weekly", List.of(
                quest("hostiles_weekly", "KILL_HOSTILES", 120, 12, "Defeat 120 hostile mobs"),
                quest("breed_weekly", "BREED_ANIMALS", 12, 8, "Breed 12 animals"),
                quest("trade_weekly", "TRADE_VILLAGER", 15, 7, "Trade with villagers 15 times"),
                quest("pack_weekly", "OPEN_PACK", 3, 6, "Open 3 Card Packs"),
                quest("sell_weekly", "SELL_MARKET", 2000, 5, "Sell $2,000 to the Market")));
        return onDisk;
    }

    private static Map<String, Object> quest(String id, String type, int target, int reward, String display) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("type", type);
        m.put("target", target);
        m.put("reward", reward);
        m.put("display", display);
        return m;
    }

    private static List<String> ids(YamlConfiguration c, String path) {
        List<String> out = new ArrayList<>();
        for (Map<?, ?> row : c.getMapList(path)) {
            out.add(String.valueOf(row.get("id")));
        }
        return out;
    }

    @Test
    void theShippedPushQuestsLeaveThePool() throws Exception {
        YamlConfiguration onDisk = rev11Quests();

        List<String> log = HomeCraftManagement.migrateConfig(onDisk, "world");

        assertEquals(4, log.stream().filter(l -> l.contains("removed the") && l.contains("quest")).count(),
                "one line per quest: " + log);
        assertTrue(log.stream().noneMatch(l -> l.startsWith(HomeCraftManagement.WARN)), "nothing to warn about: " + log);
        assertEquals(HomeCraftManagement.CONFIG_REVISION, onDisk.getInt("config_revision"));
        // What is left is exactly what rev 12 shipped, so revision 14 swaps in the v2 pools.
        assertEquals(ids(bundled(), "arcade.quests.daily_pool"), ids(onDisk, "arcade.quests.daily_pool"),
                "an upgraded server ends up with the pool a fresh install ships");
        assertEquals(ids(bundled(), "arcade.quests.weekly_pool"), ids(onDisk, "arcade.quests.weekly_pool"));
        assertFalse(onDisk.contains("arcade.quests.daily"), "the old key is gone");
        assertFalse(onDisk.contains("arcade.quests.weekly"));
    }

    /** Revision 12 on its own: the rows go from the list they are in. */
    @Test
    void revisionTwelveRemovesOnlyTheShippedPushRows() throws Exception {
        YamlConfiguration onDisk = rev11Quests();

        List<String> log = HomeCraftManagement.retireShippedQuests(onDisk, "arcade.quests.daily");

        assertEquals(List.of("fish_daily", "walk_daily"), ids(onDisk, "arcade.quests.daily"));
        assertEquals(2, log.size(), "print_daily and sell_daily: " + log);
    }

    @Test
    void aRetunedPushQuestIsKeptWithAWarningNamingIt() throws Exception {
        YamlConfiguration onDisk = rev11Quests();
        List<Map<String, Object>> daily = new ArrayList<>();
        for (Map<?, ?> row : onDisk.getMapList("arcade.quests.daily")) {
            Map<String, Object> m = new LinkedHashMap<>();
            row.forEach((k, v) -> m.put(String.valueOf(k), v));
            if ("print_daily".equals(m.get("id"))) {
                m.put("target", 3); // the admin's own number
            }
            if ("sell_daily".equals(m.get("id"))) {
                m.put("display", "Sell some stuff"); // reworded only — still ours
            }
            daily.add(m);
        }
        onDisk.set("arcade.quests.daily", daily);

        List<String> log = HomeCraftManagement.migrateConfig(onDisk, "world");

        assertEquals(List.of("fish_daily", "walk_daily", "print_daily"), ids(onDisk, "arcade.quests.daily_pool"),
                "an admin-edited list becomes the pool as it is");
        assertTrue(log.stream().anyMatch(l -> l.startsWith(HomeCraftManagement.WARN) && l.contains("print_daily")
                        && l.contains("arcade.quests.daily")),
                "the kept row is named in a warning: " + log);
        assertTrue(log.stream().anyMatch(l -> l.startsWith(HomeCraftManagement.WARN)
                        && l.contains("arcade.quests.daily_pool")),
                "and so is the list that became a pool: " + log);
    }

    @Test
    void theOriginalShippedSellQuestsGoToo() throws Exception {
        YamlConfiguration onDisk = rev11Quests();
        onDisk.set("arcade.quests.daily", List.of(
                quest("sell_daily", "SELL_MARKET", 500, 2, "Sell $500 to the Market"),
                quest("crate_daily", "OPEN_CRATE", 1, 1, "Open a Loot Crate")));

        HomeCraftManagement.migrateConfig(onDisk, "world");

        assertEquals(List.of("crate_daily"), ids(onDisk, "arcade.quests.daily_pool"),
                "the 0.x sell_daily was also ours; a quest the brief does not name stays");
    }

    @Test
    void anAdminsOwnPoolWithoutThoseQuestsIsUntouched() throws Exception {
        YamlConfiguration onDisk = rev11Quests();
        List<Map<String, Object>> mine = List.of(quest("mine_daily", "KILL_HOSTILES", 5, 4, "Mine"));
        onDisk.set("arcade.quests.daily", mine);

        HomeCraftManagement.migrateConfig(onDisk, "world");

        assertEquals(List.of("mine_daily"), ids(onDisk, "arcade.quests.daily_pool"));
    }

    @Test
    void theClockZoneShipsAndReachesAnUpgradedFile() throws Exception {
        assertEquals("America/Chicago", bundled().getString("clock.time_zone"));
        YamlConfiguration onDisk = legacyOnDisk();
        HomeCraftManagement.migrateConfig(onDisk, "world");
        List<String> added = HomeCraftManagement.backfillConfig(onDisk, bundled());
        assertTrue(added.contains("clock.time_zone"), "added: " + added);
    }

    // ---- revision 13: the wild hunt retune ----------------------------------------

    /** A live file still holding the PR #37 numbers. */
    private static YamlConfiguration rev12Hunt() throws Exception {
        YamlConfiguration onDisk = bundled();
        onDisk.set("config_revision", 12);
        onDisk.set("minis.loot.natural.interval_ticks", 24000);
        onDisk.set("minis.loot.natural.despawn_minutes", 3);
        onDisk.set("minis.loot.natural.min_distance", 96);
        onDisk.set("minis.loot.natural.max_distance", 128);
        onDisk.set("minis.loot.natural.max_live", 2);
        onDisk.set("minis.loot.natural.player_cooldown_minutes", 120);
        return onDisk;
    }

    @Test
    void theHuntComesInCloserAndRunsLonger() throws Exception {
        YamlConfiguration onDisk = rev12Hunt();

        List<String> log = HomeCraftManagement.migrateConfig(onDisk, "world");

        assertEquals(24000, onDisk.getInt("minis.loot.natural.interval_ticks"), "the cadence is unchanged");
        assertEquals(48, onDisk.getInt("minis.loot.natural.min_distance"));
        assertEquals(96, onDisk.getInt("minis.loot.natural.max_distance"));
        assertEquals(5, onDisk.getInt("minis.loot.natural.despawn_minutes"));
        assertEquals(1, onDisk.getInt("minis.loot.natural.max_live"), "one hunt at a time");
        assertEquals(90, onDisk.getInt("minis.loot.natural.player_cooldown_minutes"));
        assertEquals(5, log.stream().filter(l -> l.contains("minis.loot.natural.") && l.contains("→")).count(),
                "every change is logged: " + log);
        assertTrue(log.stream().noneMatch(l -> l.startsWith(HomeCraftManagement.WARN)));
        assertEquals(HomeCraftManagement.CONFIG_REVISION, onDisk.getInt("config_revision"));
    }

    @Test
    void aTunedHuntValueIsKeptWithAWarningNamingIt() throws Exception {
        YamlConfiguration onDisk = rev12Hunt();
        onDisk.set("minis.loot.natural.max_live", 3);
        onDisk.set("minis.loot.natural.despawn_minutes", 8);

        List<String> log = HomeCraftManagement.migrateConfig(onDisk, "world");

        assertEquals(3, onDisk.getInt("minis.loot.natural.max_live"));
        assertEquals(8, onDisk.getInt("minis.loot.natural.despawn_minutes"));
        assertEquals(48, onDisk.getInt("minis.loot.natural.min_distance"), "the shipped ones still move");
        assertTrue(log.stream().anyMatch(l -> l.startsWith(HomeCraftManagement.WARN)
                && l.contains("minis.loot.natural.max_live")), "named: " + log);
        assertTrue(log.stream().anyMatch(l -> l.startsWith(HomeCraftManagement.WARN)
                && l.contains("minis.loot.natural.despawn_minutes")), "named: " + log);
    }

    /** The retune's targets are what a fresh install ships, or the two would disagree. */
    @Test
    void theRetuneMatchesTheBundledDefaults() throws Exception {
        YamlConfiguration shipped = bundled();
        YamlConfiguration onDisk = rev12Hunt();
        HomeCraftManagement.migrateConfig(onDisk, "world");
        for (String key : List.of("interval_ticks", "despawn_minutes", "min_distance", "max_distance",
                "max_live", "player_cooldown_minutes")) {
            assertEquals(shipped.getInt("minis.loot.natural." + key), onDisk.getInt("minis.loot.natural." + key),
                    "minis.loot.natural." + key + " has drifted from src/main/resources/config.yml");
        }
    }

    /** The hunt's new sections reach an upgraded file through the backfill. */
    @Test
    void theHuntEffectsBeamAndHintsReachAnUpgradedFile() throws Exception {
        YamlConfiguration onDisk = rev12Hunt();
        onDisk.set("minis.loot.natural.effects", null);
        onDisk.set("minis.loot.natural.beam", null);
        onDisk.set("minis.loot.natural.hints", null);
        HomeCraftManagement.migrateConfig(onDisk, "world");
        List<String> added = HomeCraftManagement.backfillConfig(onDisk, bundled());
        assertTrue(added.contains("minis.loot.natural.effects.radius"), "added: " + added);
        assertTrue(added.contains("minis.loot.natural.beam.height"), "added: " + added);
        assertTrue(added.contains("minis.loot.natural.hints"), "added: " + added);
        assertEquals(4, onDisk.getMapList("minis.loot.natural.hints").size());
    }

    // ---- revision 14: the token economy ---------------------------------------------

    /** The Arcade block exactly as 0.30.1 shipped it, on a live rev-13 server. */
    private static YamlConfiguration rev13Arcade() throws Exception {
        YamlConfiguration onDisk = bundled();
        onDisk.set("config_revision", 13);
        onDisk.set("arcade", null);
        YamlConfiguration old = yaml("""
                arcade:
                  enabled: true
                  block: { material: JUKEBOX, name: "&5Arcade Machine" }
                  tokens:
                    login_streak:
                      enabled: true
                      rewards: [ 1, 1, 2, 2, 3, 3, 5 ]
                      reward_per_day: 1
                    playtime:
                      enabled: true
                      minutes_per_token: 60
                  crates:
                    starter:
                      display: "&aStarter Crate"
                      cost_tokens: 5
                      rewards:
                        - { type: card,     tag: starter, weight: 40 }
                        - { type: filament, amount: 3,    weight: 30 }
                        - { type: pack,     pack: starter, weight: 20 }
                        - { type: mini,     tag: starter, weight: 10 }
                      paid_odds:
                        - { cost_money: 750, floor: RARE }
                  prizes:
                    - { id: filament_bundle, display: "&fFilament Bundle", cost_tokens: 6,  type: filament, amount: 8 }
                    - { id: display_case,    display: "&bDisplay Case",    cost_tokens: 25, type: block, block: display_case }
                    - { id: starter_pack,    display: "&dStarter Pack",    cost_tokens: 30, type: pack,  pack: starter }
                  pity:
                    tokens: 25
                    guarantees_rarity: RARE
                  achievements:
                    first_mini:  { enabled: true, reward: 3, display: "First Mini Collected" }
                    first_sale:  { enabled: true, reward: 2, display: "First Market Sale" }
                    first_pc:    { enabled: true, reward: 2, display: "Built Your First PC" }
                    first_crate: { enabled: true, reward: 1, display: "Opened Your First Crate" }
                    first_pack:  { enabled: true, reward: 2, display: "Opened Your First Pack" }
                    rich_10k:    { enabled: true, reward: 5, display: "Reached $10,000", threshold: 10000 }
                  quests:
                    enabled: true
                    week_starts: MONDAY
                    daily:
                      - { id: fish_daily,   type: CATCH_FISH,     target: 8,   reward: 3, display: "Catch 8 fish" }
                      - { id: walk_daily,   type: TRAVEL_ON_FOOT, target: 800, reward: 3, display: "Travel 800 blocks on foot" }
                    weekly:
                      - { id: hostiles_weekly, type: KILL_HOSTILES,  target: 120, reward: 12, display: "Defeat 120 hostile mobs" }
                      - { id: breed_weekly,    type: BREED_ANIMALS,  target: 12,  reward: 8,  display: "Breed 12 animals" }
                      - { id: trade_weekly,    type: TRADE_VILLAGER, target: 15,  reward: 7,  display: "Trade with villagers 15 times" }
                  lotto:
                    ticket_cost_money: 250
                    payouts:
                      - { amount: 0,    weight: 50 }
                      - { amount: 100,  weight: 30 }
                      - { amount: 500,  weight: 15 }
                      - { amount: 2000, weight: 5 }
                """);
        for (String key : old.getKeys(true)) {
            if (!old.isConfigurationSection(key)) {
                onDisk.set(key, old.get(key));
            }
        }
        return onDisk;
    }

    /**
     * An untouched 0.30.1 Arcade upgrades to exactly what a fresh install ships: after the
     * migration and the backfill, every Arcade value on disk is the bundled one.
     */
    @Test
    void theShippedArcadeBecomesTheTokenEconomy() throws Exception {
        YamlConfiguration shipped = bundled();
        YamlConfiguration onDisk = rev13Arcade();

        List<String> log = HomeCraftManagement.migrateConfig(onDisk, "world");
        HomeCraftManagement.backfillConfig(onDisk, shipped);

        assertTrue(log.stream().noneMatch(l -> l.startsWith(HomeCraftManagement.WARN)),
                "nothing was edited, so nothing to warn about: " + log);
        for (String key : shipped.getConfigurationSection("arcade").getKeys(true)) {
            String path = "arcade." + key;
            if (shipped.isConfigurationSection(path)) {
                continue;
            }
            assertEquals(shipped.get(path), onDisk.get(path), path + " differs from a fresh install");
        }
        assertFalse(onDisk.contains("arcade.crates.starter"), "the shipped Starter Crate is replaced");
        assertFalse(onDisk.contains("arcade.lotto.ticket_cost_money"), "no money in the Arcade");
        assertFalse(onDisk.contains("arcade.quests.daily"));
        assertFalse(onDisk.contains("arcade.quests.weekly"));
        assertTrue(onDisk.isList("arcade.achievements"));
        assertTrue(log.stream().anyMatch(l -> l.contains("$2000 ×5")), "the old ticket table is logged: " + log);
        assertEquals(List.of(), HomeCraftManagement.migrateConfig(onDisk, "world"), "a second pass is a no-op");
    }

    @Test
    void anAdminsArcadeEditsAreKeptWithWarnings() throws Exception {
        YamlConfiguration onDisk = rev13Arcade();
        onDisk.set("arcade.crates.starter.cost_tokens", 7);                // their own price
        onDisk.set("arcade.crates.vip.display", "VIP Crate");
        onDisk.set("arcade.crates.vip.cost_tokens", 20);
        onDisk.set("arcade.crates.vip.rewards", List.of(Map.of("type", "filament", "amount", 5, "weight", 1)));
        onDisk.set("arcade.crates.vip.paid_odds", List.of(Map.of("cost_money", 900, "floor", "EPIC")));
        List<Map<String, Object>> prizes = new ArrayList<>();
        for (Map<?, ?> row : onDisk.getMapList("arcade.prizes")) {
            Map<String, Object> m = new LinkedHashMap<>();
            row.forEach((k, v) -> m.put(String.valueOf(k), v));
            if ("filament_bundle".equals(m.get("id"))) {
                m.put("cost_tokens", 4);
            }
            prizes.add(m);
        }
        prizes.add(new LinkedHashMap<>(Map.of("id", "my_case", "cost_tokens", 9, "type", "block",
                "block", "display_case")));
        onDisk.set("arcade.prizes", prizes);
        onDisk.set("arcade.pity.tokens", 60);
        onDisk.set("arcade.tokens.login_streak.rewards", List.of(1, 2, 3));
        onDisk.set("arcade.lotto.ticket_cost_money", 500);
        onDisk.set("arcade.achievements.first_mini.reward", 9);
        onDisk.set("arcade.achievements.first_sale.enabled", false);
        onDisk.set("arcade.achievements.rich_10k.threshold", 50000);

        List<String> log = HomeCraftManagement.migrateConfig(onDisk, "world");

        // Crates: theirs stay, minus the money.
        assertEquals(7, onDisk.getInt("arcade.crates.starter.cost_tokens"));
        assertFalse(onDisk.contains("arcade.crates.starter.paid_odds"));
        assertFalse(onDisk.contains("arcade.crates.vip.paid_odds"));
        assertEquals(20, onDisk.getInt("arcade.crates.vip.cost_tokens"));
        assertTrue(onDisk.contains("arcade.crates.arcade_crate.rewards"), "the new crate is added beside them");
        assertWarns(log, "arcade.crates.starter.paid_odds");
        assertWarns(log, "arcade.crates.vip.paid_odds");

        // Prizes: their rows stay, the shipped ones move, the new ones are added.
        Map<String, Map<?, ?>> byId = new LinkedHashMap<>();
        for (Map<?, ?> row : onDisk.getMapList("arcade.prizes")) {
            byId.put(String.valueOf(row.get("id")), row);
        }
        assertEquals(4, intAt(byId.get("filament_bundle"), "cost_tokens"), "their price is kept");
        assertEquals(9, intAt(byId.get("my_case"), "cost_tokens"));
        assertEquals(10, intAt(byId.get("display_case"), "cost_tokens"), "ours moves to the new price");
        assertEquals("MINIS", byId.get("display_case").get("tab"));
        assertFalse(byId.containsKey("starter_pack"), "the shipped pack row goes");
        assertTrue(byId.keySet().containsAll(ids(bundled(), "arcade.prizes").stream()
                .filter(id -> !id.equals("filament_bundle")).toList()), "every new row is added: " + byId.keySet());
        assertWarns(log, "filament_bundle");

        // Numbers they chose.
        assertEquals(60, onDisk.getInt("arcade.pity.tokens"));
        assertWarns(log, "arcade.pity.tokens");
        assertEquals(List.of(1, 2, 3), onDisk.getIntegerList("arcade.tokens.login_streak.rewards"));
        assertWarns(log, "arcade.tokens.login_streak.rewards");

        // The Scratch Ticket cannot keep dollars, whoever set them — but it says so.
        assertFalse(onDisk.contains("arcade.lotto.ticket_cost_money"));
        assertEquals(bundled().getMapList("arcade.lotto.payouts"), onDisk.getMapList("arcade.lotto.payouts"));
        assertTrue(log.stream().anyMatch(l -> l.startsWith(HomeCraftManagement.WARN) && l.contains("$500")),
                "their ticket price is in the log: " + log);

        // Achievements: the list, with their settings carried over.
        Map<String, Map<?, ?>> ach = new LinkedHashMap<>();
        for (Map<?, ?> row : onDisk.getMapList("arcade.achievements")) {
            ach.put(String.valueOf(row.get("id")), row);
        }
        assertEquals(26, ach.size());
        assertEquals(9, intAt(ach.get("first_mini"), "reward"), "their reward is kept");
        assertEquals(10, intAt(ach.get("first_pc"), "reward"), "ours is rescaled");
        assertEquals(Boolean.FALSE, ach.get("first_sale").get("enabled"));
        assertEquals(50000, intAt(ach.get("rich_10k"), "target"));
        assertEquals(50, intAt(ach.get("rich_10k"), "reward"), "the reward was still ours");
        assertWarns(log, "first_mini");
        assertWarns(log, "rich_10k");
    }

    private static void assertWarns(List<String> log, String what) {
        assertTrue(log.stream().anyMatch(l -> l.startsWith(HomeCraftManagement.WARN) && l.contains(what)),
                what + " should be named in a warning: " + log);
    }

    /** A rev-13 file whose quest lists are already pools is left with the pools. */
    @Test
    void aFileAlreadyOnPoolsKeepsThem() throws Exception {
        YamlConfiguration onDisk = rev13Arcade();
        onDisk.set("arcade.quests.daily_pool", List.of(quest("x_daily", "CATCH_FISH", 1, 1, "Fish")));

        HomeCraftManagement.migrateConfig(onDisk, "world");

        assertEquals(List.of("x_daily"), ids(onDisk, "arcade.quests.daily_pool"));
        assertFalse(onDisk.contains("arcade.quests.daily"));
    }

    /** The draw sizes a pool needs reach an upgraded file. */
    @Test
    void theDrawSizesReachAnUpgradedFile() throws Exception {
        YamlConfiguration onDisk = rev13Arcade();
        HomeCraftManagement.migrateConfig(onDisk, "world");
        List<String> added = HomeCraftManagement.backfillConfig(onDisk, bundled());
        assertTrue(added.contains("arcade.quests.daily_draw"), "added: " + added);
        assertTrue(added.contains("arcade.pity.per_week"), "added: " + added);
        assertTrue(added.contains("arcade.trade_in.LEGENDARY"), "added: " + added);
        assertEquals(3, onDisk.getInt("arcade.quests.daily_draw"));
    }

    // ---- revision 15: one-Card packs on rarity odds ---------------------------------

    /** The two packs exactly as revision 3 shipped them, on a live rev-14 server. */
    private static YamlConfiguration rev14Packs() throws Exception {
        YamlConfiguration onDisk = bundled();
        onDisk.set("config_revision", 14);
        onDisk.set("packs", yaml("""
                packs:
                  - id: starter
                    display: "Starter Pack"
                    price: 250.0
                    count: 3
                    pool:
                      - { card: piggy_mini,  weight: 50 }
                      - { card: chick_mini,  weight: 50 }
                  - id: premium
                    display: "Premium Pack"
                    price: 750.0
                    count: 3
                    pool:
                      - { card: piggy_mini,  weight: 45 }
                      - { card: chick_mini,  weight: 45 }
                      - { card: golden_idol, weight: 1 }
                """).getList("packs"));
        return onDisk;
    }

    @Test
    void theShippedPacksBecomeOneCardOddsPacks() throws Exception {
        YamlConfiguration onDisk = rev14Packs();

        List<String> log = HomeCraftManagement.migrateConfig(onDisk, "world");

        assertEquals(bundled().getMapList("packs"), onDisk.getMapList("packs"),
                "an untouched server ends up with exactly the packs a fresh install ships");
        assertTrue(log.stream().noneMatch(l -> l.startsWith(HomeCraftManagement.WARN)), "nothing to warn: " + log);
        assertEquals(2, log.stream().filter(l -> l.contains("rolls by rarity odds")).count(), "one line each: " + log);
        assertEquals(List.of(), HomeCraftManagement.migrateConfig(onDisk, "world"), "a second pass is a no-op");
    }

    @Test
    void anAdminsPackKeepsItsPoolAndPriceButHoldsOneCard() throws Exception {
        YamlConfiguration onDisk = rev14Packs();
        List<Map<String, Object>> packs = new ArrayList<>();
        for (Map<?, ?> row : onDisk.getMapList("packs")) {
            Map<String, Object> m = new LinkedHashMap<>();
            row.forEach((k, v) -> m.put(String.valueOf(k), v));
            packs.add(m);
        }
        packs.get(0).put("pool", List.of(Map.of("card", "creeper_mini", "weight", 10))); // their own pool
        packs.get(1).put("price", 900.0);                                                   // their own price
        packs.add(new LinkedHashMap<>(Map.of("id", "holiday", "display", "Holiday Pack", "price", 500.0,
                "count", 5, "pool", List.of(Map.of("card", "golden_idol", "weight", 1)))));
        onDisk.set("packs", packs);

        List<String> log = HomeCraftManagement.migrateConfig(onDisk, "world");

        Map<String, Map<?, ?>> byId = new LinkedHashMap<>();
        for (Map<?, ?> row : onDisk.getMapList("packs")) {
            byId.put(String.valueOf(row.get("id")), row);
        }
        // starter: their pool stays a pool; its price was still ours, so it moves.
        assertEquals(1, intAt(byId.get("starter"), "count"));
        assertEquals(100, intAt(byId.get("starter"), "price"));
        assertInstanceOf(List.class, byId.get("starter").get("pool"));
        assertWarns(log, "packs → starter as a hand-picked pool");
        // premium: our pool becomes the odds row, their price stays.
        assertEquals(900, intAt(byId.get("premium"), "price"));
        assertNull(byId.get("premium").get("pool"));
        assertNotNull(byId.get("premium").get("rarity_odds"));
        assertWarns(log, "packs → premium price");
        // their own pack holds one Card now too, and says so.
        assertEquals(1, intAt(byId.get("holiday"), "count"));
        assertEquals(500, intAt(byId.get("holiday"), "price"));
        assertTrue(log.stream().anyMatch(l -> l.contains("packs → holiday count 5 → 1")), "logged: " + log);
    }

    // ---- revision 16: +1 Home ------------------------------------------------------------

    private static Map<String, Object> homeRow(String id, int cost, String tier) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("tab", "PERKS");
        m.put("cost_tokens", cost);
        m.put("type", "command");
        m.put("commands", List.of("lp user %player% permission set essentials.sethome.multiple." + tier + " true"));
        return m;
    }

    /** A 0.31.0 file: the two shipped home rows, no home_slot. */
    private static YamlConfiguration rev15Homes() throws Exception {
        YamlConfiguration onDisk = bundled();
        onDisk.set("config_revision", 15);
        List<Map<String, Object>> prizes = new ArrayList<>();
        for (Map<?, ?> row : onDisk.getMapList("arcade.prizes")) {
            Map<String, Object> m = new LinkedHashMap<>();
            row.forEach((k, v) -> m.put(String.valueOf(k), v));
            if ("home_slot".equals(m.get("id"))) {
                prizes.add(homeRow("home_2", 400, "homes2"));
                prizes.add(homeRow("home_3", 600, "homes3"));
            } else {
                prizes.add(m);
            }
        }
        onDisk.set("arcade.prizes", prizes);
        return onDisk;
    }

    @Test
    void theShippedHomeRowsBecomeOneHomeSlotRow() throws Exception {
        YamlConfiguration onDisk = rev15Homes();

        List<String> log = HomeCraftManagement.migrateConfig(onDisk, "world");

        assertEquals(ids(bundled(), "arcade.prizes"), ids(onDisk, "arcade.prizes"),
                "home_slot sits where home_2 was; the list matches a fresh install");
        assertTrue(log.stream().anyMatch(l -> l.contains("+1 Home")), "logged: " + log);
        assertTrue(log.stream().noneMatch(l -> l.startsWith(HomeCraftManagement.WARN)), "nothing to warn: " + log);
        assertEquals(List.of(), HomeCraftManagement.migrateConfig(onDisk, "world"), "a second pass is a no-op");
    }

    @Test
    void anEditedHomeRowIsKeptWithAWarning() throws Exception {
        YamlConfiguration onDisk = rev15Homes();
        List<Map<String, Object>> prizes = new ArrayList<>();
        for (Map<?, ?> row : onDisk.getMapList("arcade.prizes")) {
            Map<String, Object> m = new LinkedHashMap<>();
            row.forEach((k, v) -> m.put(String.valueOf(k), v));
            if ("home_3".equals(m.get("id"))) {
                m.put("cost_tokens", 800); // their own price
            }
            prizes.add(m);
        }
        onDisk.set("arcade.prizes", prizes);

        List<String> log = HomeCraftManagement.migrateConfig(onDisk, "world");

        List<String> ids = ids(onDisk, "arcade.prizes");
        assertTrue(ids.contains("home_slot"));
        assertTrue(ids.contains("home_3"), "their row stays");
        assertFalse(ids.contains("home_2"), "ours goes");
        assertTrue(log.stream().anyMatch(l -> l.startsWith(HomeCraftManagement.WARN) && l.contains("home_3")),
                "named: " + log);
    }

    // ---- 0.33: the live market (market.sim) arrives by backfill alone -------------------
    //
    // Every market.sim key is new, so no migration step and no config_revision bump: the leaf
    // backfill adds the section. Its lists (headline lists, same_plural, seasons.list,
    // real_world.symbols, the [low, high] pairs) are single leaves, so they arrive whole and an
    // admin's edits or deletions inside them are never undone.

    /** A 0.32 file: everything the jar ships except market.sim — what every live server has. */
    private static YamlConfiguration v032OnDisk() throws Exception {
        YamlConfiguration onDisk = bundled();
        onDisk.set("market.sim", null);
        assertFalse(onDisk.contains("market.sim", true), "fixture: market.sim is gone");
        return onDisk;
    }

    /** market.sim's leaves in the bundled file, full paths, in file order. */
    private static List<String> marketSimLeaves(YamlConfiguration c) {
        List<String> leaves = new ArrayList<>();
        for (String key : c.getConfigurationSection("market.sim").getKeys(true)) {
            if (!c.isConfigurationSection("market.sim." + key)) {
                leaves.add("market.sim." + key);
            }
        }
        return leaves;
    }

    @Test
    void aPreLiveMarketFileGainsEveryMarketSimLeafAndNothingElse() throws Exception {
        YamlConfiguration shipped = bundled();
        YamlConfiguration onDisk = v032OnDisk();
        List<Map<?, ?>> catalogBefore = onDisk.getMapList("market.catalog");

        assertEquals(List.of(), HomeCraftManagement.migrateConfig(onDisk, "world"),
                "every market.sim key is new: no migration step, no config_revision bump");
        List<String> added = HomeCraftManagement.backfillConfig(onDisk, shipped);

        List<String> leaves = marketSimLeaves(shipped);
        assertFalse(leaves.isEmpty(), "config.yml ships market.sim");
        assertEquals(leaves, added, "exactly the market.sim leaves, in file order");
        for (String key : leaves) {
            assertEquals(shipped.get(key), onDisk.get(key), key + " must arrive with its shipped value");
        }

        // Lists arrive whole, as one leaf each — never element by element.
        for (String list : List.of("market.sim.hot.percent", "market.sim.headlines.up", "market.sim.same_plural",
                "market.sim.seasons.list", "market.sim.real_world.symbols")) {
            assertTrue(added.contains(list), list + " is one leaf: " + added);
            assertTrue(added.stream().noneMatch(k -> k.startsWith(list + ".")), list + " was split: " + added);
        }
        assertEquals(List.of(8, 15), onDisk.getList("market.sim.hot.percent"));
        assertEquals(12, onDisk.getStringList("market.sim.headlines.up").size());
        assertEquals(41, onDisk.getStringList("market.sim.same_plural").size());
        assertEquals(7, onDisk.getMapList("market.sim.seasons.list").size());
        assertEquals(3, onDisk.getMapList("market.sim.real_world.symbols").size());

        assertEquals(catalogBefore, onDisk.getMapList("market.catalog"), "the catalog is untouched");
        assertEquals(HomeCraftManagement.CONFIG_REVISION, onDisk.getInt("config_revision"));

        // The explanation travels with the keys: the section header and the inline notes.
        assertTrue(String.valueOf(onDisk.getComments("market.sim")).contains("LOCKED IN CODE"),
                "the market.sim header must come across: " + onDisk.getComments("market.sim"));
        assertFalse(onDisk.getInlineComments("market.sim.tick_minutes").isEmpty());
        assertTrue(onDisk.saveToString().contains("LOCKED IN CODE"));

        // And what arrived is exactly the shipped live market, read without a WARN.
        List<String> warns = new ArrayList<>();
        assertEquals(SimSettings.defaults(), MarketSimConfig.parse(onDisk, warns::add).settings());
        assertEquals(List.of(), warns);

        assertEquals(List.of(), HomeCraftManagement.backfillConfig(onDisk, shipped), "a second pass adds nothing");
    }

    /** The pre-0.21 file (an admin's own two-row catalog) gains market.sim and keeps its catalog. */
    @Test
    void aLegacyFileGainsTheLiveMarketAndKeepsItsCatalog() throws Exception {
        YamlConfiguration onDisk = legacyOnDisk();
        HomeCraftManagement.migrateConfig(onDisk, "world");
        List<Map<?, ?>> catalog = onDisk.getMapList("market.catalog");

        List<String> added = HomeCraftManagement.backfillConfig(onDisk, bundled());

        assertTrue(added.contains("market.sim.enabled"), "added: " + added);
        assertTrue(added.contains("market.sim.seasons.list"), "added: " + added);
        assertEquals(catalog, onDisk.getMapList("market.catalog"), "the admin's two rows stay two rows");
        assertEquals(SimSettings.defaults(), MarketSimConfig.parse(onDisk, w -> { }).settings());
    }

    /**
     * An admin's live-market edits survive: a deleted real_world.symbols row (and season) is not
     * re-added, a trimmed headline list and a retuned pair stay as written — only a missing
     * scalar leaf comes back.
     */
    @Test
    void aDeletedRealWorldSymbolRowIsNotReAdded() throws Exception {
        YamlConfiguration onDisk = bundled();
        List<Map<?, ?>> symbols = new ArrayList<>(onDisk.getMapList("market.sim.real_world.symbols"));
        assertTrue(symbols.removeIf(row -> "wheat".equals(row.get("item"))), "fixture: wheat ships");
        onDisk.set("market.sim.real_world.symbols", symbols);
        List<Map<?, ?>> seasons = new ArrayList<>(onDisk.getMapList("market.sim.seasons.list"));
        assertTrue(seasons.removeIf(row -> "new_year".equals(row.get("id"))), "fixture: new_year ships");
        onDisk.set("market.sim.seasons.list", seasons);
        onDisk.set("market.sim.headlines.up", List.of("Everyone wants {item} at the fair!"));
        onDisk.set("market.sim.hot.percent", List.of(5, 10));
        onDisk.set("market.sim.enabled", false);
        onDisk.set("market.sim.news.max_per_day", null);

        assertEquals(List.of(), HomeCraftManagement.migrateConfig(onDisk, "world"));
        List<String> added = HomeCraftManagement.backfillConfig(onDisk, bundled());

        assertEquals(List.of("market.sim.news.max_per_day"), added,
                "only the missing scalar comes back; the lists are the admin's");
        assertEquals(symbols, onDisk.getMapList("market.sim.real_world.symbols"), "the wheat row stays deleted");
        assertEquals(seasons, onDisk.getMapList("market.sim.seasons.list"), "new_year stays deleted");
        assertEquals(List.of("Everyone wants {item} at the fair!"), onDisk.getStringList("market.sim.headlines.up"));
        assertEquals(List.of(5, 10), onDisk.getList("market.sim.hot.percent"));
        assertFalse(onDisk.getBoolean("market.sim.enabled"), "their off switch stands");

        List<String> warns = new ArrayList<>();
        SimSettings s = MarketSimConfig.parse(onDisk, warns::add).settings();
        assertEquals(List.of(), warns);
        assertFalse(s.enabled());
        assertEquals(List.of("gold_ingot", "iron_ingot"), s.real().symbols().stream().map(RealSymbol::item).toList());
        assertEquals(6, s.seasons().list().size());
        assertEquals(List.of("Everyone wants {item} at the fair!"), s.headlines().up());
        assertEquals(new SimSettings.Range(5, 10), s.hot().percent());
        assertEquals(2, s.news().maxPerDay(), "the re-added leaf carries the shipped value");
    }

    /**
     * The real_world.symbols notes (copper for iron, the unverified lumber row) live in the block
     * comment above {@code symbols:}, which Bukkit keeps on a re-save and the backfill copies to an
     * upgraded file. A comment inside the list was attached to market.catalog instead, never reached
     * an upgraded server, and re-saved at column 2 where uncommenting it broke the file.
     */
    @Test
    void theRealWorldSymbolNotesSurviveAnUpgradeAndAReSave() throws Exception {
        YamlConfiguration upgraded = v032OnDisk();
        HomeCraftManagement.backfillConfig(upgraded, bundled());
        for (YamlConfiguration c : List.of(bundled(), upgraded)) {
            YamlConfiguration resaved = yaml(yaml(c.saveToString()).saveToString());
            String notes = String.join("\n", resaved.getComments("market.sim.real_world.symbols"));
            assertTrue(notes.contains("LBR=F") && notes.contains("oak_log"), "the lumber note: " + notes);
            assertTrue(notes.contains("iron-ore"), "the copper note: " + notes);
            assertEquals(3, resaved.getMapList("market.sim.real_world.symbols").size());
        }
        assertFalse(String.valueOf(bundled().getComments("market.catalog")).contains("LBR=F"),
                "nothing about symbols is left on the catalog's comment");
    }

    // ---- a bare `market.sim: false` (review finding #32) --------------------------------
    //
    // Start and /hcm reload both run migrate + backfill BEFORE anything reads the file. The
    // backfill asks "is market.sim.enabled on disk?", finds a scalar where the section belongs,
    // and used to replace it with the whole shipped section, enabled: true included. The
    // migration now turns the scalar into market.sim.enabled first.

    /** The bundled config.yml as text, with its whole market.sim block replaced by {@code simLine}. */
    private static String bundledWithSim(String simLine) throws IOException {
        String text;
        try (InputStream in = ConfigMigrationTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "the bundled config.yml is missing from the test classpath");
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        int from = text.indexOf("\n  sim:\n") + 1;
        int to = text.indexOf("\n\n", from);
        assertTrue(from > 0 && to > from, "fixture: config.yml ships a market.sim block");
        return text.substring(0, from) + simLine + text.substring(to);
    }

    /** Migrate, backfill and read, as start and /hcm reload do; returns the migration log. */
    private static List<String> startUp(YamlConfiguration onDisk, List<String> added) throws Exception {
        List<String> log = HomeCraftManagement.migrateConfig(onDisk, "world");
        added.addAll(HomeCraftManagement.backfillConfig(onDisk, bundled()));
        return log;
    }

    @Test
    void aBareSimFalseTurnsTheMarketOffAndTheBackfillKeepsItOff() throws Exception {
        YamlConfiguration shipped = bundled();
        List<String> otherLeaves = new ArrayList<>(marketSimLeaves(shipped));
        assertTrue(otherLeaves.remove("market.sim.enabled"));

        for (String off : List.of("false", "off", "no", "\"no\"", "\"off\"", "False")) {
            YamlConfiguration onDisk = yaml(bundledWithSim("  sim: " + off));
            List<String> warns = new ArrayList<>();
            assertFalse(MarketSimConfig.parse(onDisk, warns::add).enabled(), "fixture: sim: " + off + " reads off");

            List<String> added = new ArrayList<>();
            List<String> log = startUp(onDisk, added);

            assertEquals(1, log.size(), "sim: " + off + " -> " + log);
            assertTrue(log.get(0).contains("market.sim.enabled: false"), log.get(0));
            assertFalse(log.get(0).startsWith(HomeCraftManagement.WARN), "a plain off is not a warning: " + log);
            assertEquals(otherLeaves, added, "sim: " + off + ": the rest of the section, around the switch");
            assertEquals(Boolean.FALSE, onDisk.get("market.sim.enabled", null), "sim: " + off + " stays off");

            warns.clear();
            assertEquals(SimSettings.defaults().withEnabled(false),
                    MarketSimConfig.parse(onDisk, warns::add).settings(), "sim: " + off);
            assertEquals(List.of(), warns, "sim: " + off + " now reads cleanly");

            // What was saved reads the same after a restart, and a second start changes nothing.
            YamlConfiguration reloaded = yaml(onDisk.saveToString());
            List<String> again = new ArrayList<>();
            assertEquals(List.of(), startUp(reloaded, again));
            assertEquals(List.of(), again);
            assertFalse(MarketSimConfig.parse(reloaded, w -> { }).enabled());
        }
    }

    @Test
    void aBareSimThatIsNotASwitchIsWrittenAsOffWithAWarning() throws Exception {
        for (String junk : List.of("\"yes please\"", "0", "1", "maybe", "[on]")) {
            YamlConfiguration onDisk = yaml(bundledWithSim("  sim: " + junk));
            List<String> added = new ArrayList<>();
            List<String> log = startUp(onDisk, added);

            assertEquals(1, log.size(), "sim: " + junk + " -> " + log);
            assertTrue(log.get(0).startsWith(HomeCraftManagement.WARN), "named as a warning: " + log);
            assertTrue(log.get(0).contains("market.sim"), log.get(0));
            assertFalse(added.contains("market.sim.enabled"), "sim: " + junk + ": " + added);
            assertEquals(Boolean.FALSE, onDisk.get("market.sim.enabled", null), "sim: " + junk + " fails closed");
            assertFalse(MarketSimConfig.parse(onDisk, w -> { }).enabled());
        }
    }

    @Test
    void aBareSimTrueIsTheShippedMarket() throws Exception {
        YamlConfiguration onDisk = yaml(bundledWithSim("  sim: true"));
        List<String> added = new ArrayList<>();
        List<String> log = startUp(onDisk, added);
        assertEquals(1, log.size(), log.toString());
        assertEquals(Boolean.TRUE, onDisk.get("market.sim.enabled", null));
        List<String> warns = new ArrayList<>();
        assertEquals(SimSettings.defaults(), MarketSimConfig.parse(onDisk, warns::add).settings());
        assertEquals(List.of(), warns);
    }

    /** The rewrite keeps the key where it was and keeps its comments, the shipped header included. */
    @Test
    void theRewrittenSwitchKeepsItsPlaceAndItsComments() throws Exception {
        YamlConfiguration onDisk = yaml(bundledWithSim("  sim: false             # off for the build contest"));
        List<Map<?, ?>> catalog = onDisk.getMapList("market.catalog");
        startUp(onDisk, new ArrayList<>());

        assertTrue(String.valueOf(onDisk.getComments("market.sim")).contains("LOCKED IN CODE"),
                "the shipped header stays: " + onDisk.getComments("market.sim"));
        assertEquals(List.of("off for the build contest"), onDisk.getInlineComments("market.sim"));
        String saved = onDisk.saveToString();
        int sim = saved.indexOf("\n  sim:");
        assertTrue(sim > 0 && sim < saved.indexOf("\n  catalog:"), "market.sim stays above the catalog");
        assertEquals(catalog, yaml(saved).getMapList("market.catalog"), "the catalog is untouched");
        assertFalse(MarketSimConfig.parse(yaml(saved), w -> { }).enabled());
    }

    /** A config with no market.sim, or with the section, is not touched by the rewrite. */
    @Test
    void theSwitchRewriteLeavesASectionOrNoSimAlone() throws Exception {
        List<String> log = new ArrayList<>();
        YamlConfiguration shipped = bundled();
        HomeCraftManagement.liveMarketSwitch(shipped, log);
        HomeCraftManagement.liveMarketSwitch(v032OnDisk(), log);
        HomeCraftManagement.liveMarketSwitch(legacyOnDisk(), log);
        HomeCraftManagement.liveMarketSwitch(yaml("store:\n  name: Crate\n"), log);
        assertEquals(List.of(), log);
        assertEquals(bundled().saveToString(), shipped.saveToString());
    }
}
