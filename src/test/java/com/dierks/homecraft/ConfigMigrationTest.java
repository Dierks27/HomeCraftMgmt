package com.dierks.homecraft;

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
                "arcade.crates.starter.rewards",
                "arcade.crates.starter.paid_odds")) {
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
        assertEquals(25, onDisk.getInt("arcade.pity.tokens"));

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
}
