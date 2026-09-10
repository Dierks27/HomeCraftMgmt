package com.dierks.homecraft;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        assertEquals(3, onDisk.getInt("config_revision"));
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
        assertEquals(3, onDisk.getInt("config_revision"), "defaults answer getInt() as well");
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
        onDisk.set("config_revision", HomeCraftManagement.CONFIG_REVISION - 1);

        assertFalse(HomeCraftManagement.migrateConfig(onDisk, "world").isEmpty(),
                "lowering the revision must re-run the rebalance");

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
