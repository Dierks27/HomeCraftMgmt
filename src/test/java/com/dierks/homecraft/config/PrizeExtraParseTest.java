package com.dierks.homecraft.config;

import com.dierks.homecraft.config.PluginConfig.Keeper;
import com.dierks.homecraft.config.PluginConfig.KeeperLook;
import com.dierks.homecraft.config.PluginConfig.LimitPer;
import com.dierks.homecraft.config.PluginConfig.OnlyIf;
import com.dierks.homecraft.config.PluginConfig.Prize;
import com.dierks.homecraft.config.PluginConfig.PrizeExtra;
import com.dierks.homecraft.config.PluginConfig.PrizeLimit;
import com.dierks.homecraft.config.PluginConfig.PrizeTab;
import com.dierks.homecraft.config.PluginConfig.PrizeType;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 0.38 row fields (SHOP-V2-SPEC §8 C0): every new field read as the spec writes it, and each wrong one with its
 * one WARN; a hat lasts 90 days unless it says; renew_tokens only on a hat; the Keepers tab and MONTH; the default
 * tabs; 0.37.2's rows read exactly as before, and 0.38's bundled ones with their new fields, both with no WARN.
 * The rows go through the loader's own {@code readPrize}, read from YAML the way {@code arcade.prizes} is.
 */
class PrizeExtraParseTest {

    /** One flow-style row, read as the loader reads {@code arcade.prizes}. */
    private static Map<?, ?> yamlRow(String flow) throws Exception {
        YamlConfiguration c = new YamlConfiguration();
        c.loadFromString("arcade:\n  prizes:\n    - " + flow + "\n");
        List<Map<?, ?>> rows = c.getMapList("arcade.prizes");
        assertEquals(1, rows.size(), "fixture: one row");
        return rows.get(0);
    }

    /**
     * {@code Material.isItem()} and {@code isBlock()} need a running server, so the test hands the loader name
     * rules for them: everything here is an item, and a block is one named like the shipped balls' and icons'.
     */
    private static boolean item(Material m) {
        return m != null;
    }

    private static boolean block(Material m) {
        String n = m.name();
        return n.endsWith("_CONCRETE") || n.endsWith("_BLOCK") || n.endsWith("_LEAVES") || n.equals("GLASS");
    }

    private static Prize read(Map<?, ?> row, List<String> warnings) {
        return PluginConfig.readPrize(row, 150, 1, warnings::add, PrizeExtraParseTest::item, PrizeExtraParseTest::block);
    }

    private static Prize read(String flow, List<String> warnings) throws Exception {
        return read(yamlRow(flow), warnings);
    }

    private static Prize readClean(String flow) throws Exception {
        List<String> warnings = new ArrayList<>();
        Prize p = read(flow, warnings);
        assertEquals(List.of(), warnings, "no WARN for " + flow);
        assertNotNull(p, flow);
        return p;
    }

    private static Prize readWarned(String flow, String says) throws Exception {
        List<String> warnings = new ArrayList<>();
        Prize p = read(flow, warnings);
        assertEquals(1, warnings.size(), "one WARN for " + flow + ": " + warnings);
        assertTrue(warnings.get(0).contains(says), warnings.get(0));
        return p;
    }

    // ---- hats ------------------------------------------------------------------------------------------

    @Test
    void aHatWithoutDaysReads90() throws Exception {
        Prize p = readClean("{ id: hat_bunny, tab: LOOKS, display: \"&fBunny Hat\", cost_tokens: 120, type: hat, "
                + "texture: \"\" }");
        assertEquals(90, p.days());
        assertEquals(PrizeTab.LOOKS, p.tab(), "tab: LOOKS is a tab now");
        assertNull(p.extra().hatMaterial(), "a textured head");
        assertEquals(0, p.extra().renewTokens());
        assertEquals(30, readClean("{ id: h, cost_tokens: 120, type: hat, days: 30 }").days());
        assertEquals(1, readClean("{ id: h, cost_tokens: 120, type: hat, days: 0 }").days(), "at least a day");
        assertEquals(365, readClean("{ id: h, cost_tokens: 120, type: hat, days: 1000 }").days(), "at most a year");
    }

    @Test
    void aHatsMaterial() throws Exception {
        assertSame(Material.CREEPER_HEAD, readClean("{ id: hat_creeper, tab: LOOKS, cost_tokens: 120, type: hat, "
                + "material: CREEPER_HEAD, days: 90 }").extra().hatMaterial());
        assertSame(Material.DRAGON_HEAD, readClean("{ id: hat_dragon, cost_tokens: 150, type: hat, "
                + "material: dragon_head }").extra().hatMaterial(), "case doesn't matter");
        Prize cap = readClean("{ id: cap_pink, tab: LOOKS, cost_tokens: 60, type: hat, material: LEATHER_HELMET, "
                + "color: PINK, days: 90 }");
        assertSame(Material.LEATHER_HELMET, cap.extra().hatMaterial());
        assertEquals(org.bukkit.DyeColor.PINK, cap.color(), "a cap's dye is the row's color");
        Prize odd = readWarned("{ id: hat_odd, cost_tokens: 120, type: hat, material: DIAMOND_HELMET }",
                "CREEPER_HEAD, DRAGON_HEAD or LEATHER_HELMET");
        assertNotNull(odd, "the row stays");
        assertNull(odd.extra().hatMaterial(), "the material is ignored");
        assertNull(readWarned("{ id: hat_odd, cost_tokens: 120, type: hat, material: NOT_A_THING }", "Ignored")
                .extra().hatMaterial());
    }

    @Test
    void renewTokensIsReadOnAHatOnly() throws Exception {
        Prize hat = readClean("{ id: hat_creeper, cost_tokens: 120, type: hat, material: CREEPER_HEAD, "
                + "renew_tokens: 90 }");
        assertEquals(90, hat.extra().renewTokens());
        assertEquals(90, hat.renewPrice(), "a lower price to buy it again");
        assertEquals(0, readClean("{ id: h, cost_tokens: 120, type: hat, renew_tokens: -5 }").extra().renewTokens(),
                "never below 0");
        // [X] a crate's free 1-day trail opens a window too, so a trail discount would be a 15-token pull's
        Prize trail = readWarned("{ id: trail_snow, cost_tokens: 100, type: trail, particle: SNOWFLAKE, days: 30, "
                + "renew_tokens: 10 }", "only a hat row");
        assertEquals(0, trail.extra().renewTokens(), "ignored");
        assertEquals(100, trail.renewPrice(), "buying it again costs the full price");
        assertEquals(0, readWarned("{ id: b, cost_tokens: 8, type: boost, effect: speed, renew_tokens: 4 }",
                "renew_tokens").extra().renewTokens());
    }

    // ---- balls, parties, only_if ----------------------------------------------------------------------

    @Test
    void aBallsLookAndItsLimit() throws Exception {
        Prize ball = readClean("{ id: ball_gold, tab: LOOKS, display: \"&6Gold Golf Ball\", cost_tokens: 250, "
                + "icon: { texture: \"\", material: GOLD_BLOCK }, type: ball, look: GOLD_BLOCK, "
                + "limit: { per: LIFETIME, count: 1 } }");
        assertEquals(PrizeType.BALL, ball.type());
        assertSame(Material.GOLD_BLOCK, ball.extra().ballLook());
        assertEquals(new PrizeLimit(LimitPer.LIFETIME, 1), ball.limit());
        assertEquals(new PrizeLimit(LimitPer.LIFETIME, 1),
                readClean("{ id: ball_x, cost_tokens: 40, type: ball, look: PINK_CONCRETE, "
                        + "limit: { per: DAY, count: 3 } }").limit(), "a ball is one each for good, whatever the row says");
        assertEquals(new PrizeLimit(LimitPer.LIFETIME, 1),
                readClean("{ id: ball_y, cost_tokens: 40, type: ball, look: RED_CONCRETE }").limit());
        assertNull(readWarned("{ id: ball_none, cost_tokens: 40, type: ball }", "skipped"), "no look: skipped");
        assertNull(readWarned("{ id: ball_gem, cost_tokens: 40, type: ball, look: DIAMOND }", "not a block"),
                "an item that isn't a block: skipped");
        assertNull(readWarned("{ id: ball_what, cost_tokens: 40, type: ball, look: NOT_A_THING }", "skipped"));
    }

    @Test
    void aPartysSeconds() throws Exception {
        Prize party = readClean("{ id: party_time, tab: COSMETICS, display: \"&dParty Time!\", cost_tokens: 300, "
                + "icon: { texture: \"\", material: CAKE }, type: party, seconds: 10, limit: { per: DAY, count: 1 } }");
        assertEquals(PrizeType.PARTY, party.type());
        assertEquals(10, party.extra().seconds());
        assertEquals(10, readClean("{ id: p, cost_tokens: 300, type: party }").extra().seconds(), "10 by default");
        assertEquals(5, readClean("{ id: p, cost_tokens: 300, type: party, seconds: 1 }").extra().seconds());
        assertEquals(30, readClean("{ id: p, cost_tokens: 300, type: party, seconds: 600 }").extra().seconds());
    }

    /** 0.38.1: a firework row's show lasts its {@code seconds}, 3 (the Firework Show's) unless it says, 3-30. */
    @Test
    void aFireworkRowsSeconds() throws Exception {
        Prize grand = readClean("{ id: firework_grand, tab: COSMETICS, display: \"&6Grand Firework Show\", "
                + "description: [\"A long firework show\", \"with a big finale!\"], cost_tokens: 40, "
                + "icon: { texture: \"\", material: FIREWORK_ROCKET }, type: firework, seconds: 15 }");
        assertEquals(PrizeType.FIREWORK, grand.type());
        assertEquals(15, grand.extra().seconds());
        assertEquals(3, readClean("{ id: f, cost_tokens: 15, type: firework }").extra().seconds(), "3 by default");
        assertEquals(3, readClean("{ id: f, cost_tokens: 15, type: firework, seconds: 3 }").extra().seconds());
        assertEquals(30, readClean("{ id: f, cost_tokens: 15, type: firework, seconds: 30 }").extra().seconds());
        assertEquals(12, readClean("{ id: f, cost_tokens: 15, type: firework, seconds: \"12\" }").extra().seconds(),
                "a quoted number reads too");
        assertEquals(3, readWarned("{ id: f, cost_tokens: 15, type: firework, seconds: 1 }", "3 to 30 seconds. Using 3.")
                .extra().seconds(), "at least 3, with one WARN");
        assertEquals(30, readWarned("{ id: f, cost_tokens: 15, type: firework, seconds: 300 }",
                "3 to 30 seconds. Using 30.").extra().seconds(), "at most 30, with one WARN");
        assertEquals(3, readWarned("{ id: f, cost_tokens: 15, type: firework, seconds: long }", "seconds 'long'")
                .extra().seconds(), "not a number: 3, with one WARN");
        assertEquals(4, readWarned("{ id: f, cost_tokens: 15, type: firework, seconds: 4.5 }", "Using 4.")
                .extra().seconds(), "whole seconds");
        assertNotNull(readWarned("{ id: f, cost_tokens: 15, type: firework, seconds: -2 }", "Using 3."),
                "a bad length never costs the row");
        assertEquals(0, readClean("{ id: b, cost_tokens: 8, type: boost, effect: speed, seconds: 15 }").extra()
                .seconds(), "only a firework or party row reads seconds");
    }

    /**
     * 0.38.1, the Mega Firework Show: a firework row's {@code launchers} (1-3) make it a show from the nearest Firework
     * Launchers, with its own length (45-90 seconds, 60 unless it says); a row without keeps 0.38.1's 3-30, so the
     * Firework Show and the Grand show are as they were.
     */
    @Test
    void aRemoteRowIsTheFireworkRemote() throws Exception {
        Prize remote = readClean("{ id: firework_remote, tab: COSMETICS, display: \"&eFirework Remote\", "
                + "description: [\"Tap your Firework Launchers\", \"to pick them for your show!\"], cost_tokens: 10, "
                + "icon: { texture: \"\", material: RECOVERY_COMPASS }, type: remote }");
        assertEquals(PrizeType.REMOTE, remote.type(), "0.38.2's type");
        assertEquals(PrizeTab.COSMETICS, remote.tab());
        assertEquals(Material.RECOVERY_COMPASS, remote.icon().material());
        assertEquals(PrizeTab.COSMETICS, readClean("{ id: r, cost_tokens: 10, type: remote }").tab(),
                "on the Cosmetics tab by default");
        assertEquals(Material.RECOVERY_COMPASS, readClean("{ id: r, cost_tokens: 10, type: remote }").icon()
                .material(), "a recovery compass by default (Bedrock has it)");
        assertEquals(0, readWarned("{ id: r, cost_tokens: 10, type: remote, launchers: 3 }",
                "only a firework row fires from Firework Launchers").extra().launchers(), "launchers on it: ignored");
    }

    @Test
    void aMegaRowsLaunchersAndSeconds() throws Exception {
        Prize ultimate = readClean("{ id: firework_ultimate, tab: COSMETICS, display: \"&dUltimate Firework Show\", "
                + "cost_tokens: 200, type: firework, seconds: 90, launchers: 10 }");
        assertEquals(10, ultimate.extra().launchers(), "the Ultimate's ten (0.38.2)");
        assertEquals(90, ultimate.extra().seconds());
        Prize mega = readClean("{ id: firework_mega, tab: COSMETICS, display: \"&dMega Firework Show\", "
                + "description: [\"A whole minute of fireworks\", \"from your Firework Launchers!\"], cost_tokens: 75, "
                + "icon: { texture: \"\", material: FIREWORK_ROCKET }, type: firework, seconds: 60, launchers: 3 }");
        assertEquals(PrizeType.FIREWORK, mega.type());
        assertEquals(3, mega.extra().launchers());
        assertEquals(60, mega.extra().seconds());
        assertEquals(60, readClean("{ id: m, cost_tokens: 75, type: firework, launchers: 3 }").extra().seconds(),
                "60 by default");
        assertEquals(45, readClean("{ id: m, cost_tokens: 75, type: firework, launchers: 2, seconds: 45 }").extra()
                .seconds());
        assertEquals(90, readClean("{ id: m, cost_tokens: 75, type: firework, launchers: 1, seconds: 90 }").extra()
                .seconds());
        assertEquals(45, readWarned("{ id: m, cost_tokens: 75, type: firework, launchers: 3, seconds: 15 }",
                "45 to 90 seconds. Using 45.").extra().seconds(), "its own bound: at least 45, with one WARN");
        assertEquals(90, readWarned("{ id: m, cost_tokens: 75, type: firework, launchers: 3, seconds: 600 }",
                "45 to 90 seconds. Using 90.").extra().seconds(), "at most 90");
        assertEquals(60, readWarned("{ id: m, cost_tokens: 75, type: firework, launchers: 3, seconds: long }",
                "Using 60.").extra().seconds(), "not a number: 60");
        // the launchers
        assertEquals(7, readClean("{ id: m, cost_tokens: 75, type: firework, launchers: 7 }").extra().launchers(),
                "0.38.2: up to 10");
        assertEquals(10, readWarned("{ id: m, cost_tokens: 75, type: firework, launchers: 11 }", "1 to 10 Firework "
                + "Launchers").extra().launchers(), "at most 10, with one WARN");
        Prize none = readWarned("{ id: m, cost_tokens: 75, type: firework, launchers: -1, seconds: 20 }",
                "Using 0.");
        assertEquals(0, none.extra().launchers(), "below 1: a show around the player");
        assertEquals(20, none.extra().seconds(), "with 0.38.1's 3-30");
        assertEquals(0, readClean("{ id: m, cost_tokens: 75, type: firework, launchers: 0, seconds: 20 }").extra()
                .launchers(), "0 says so plainly");
        assertEquals(0, readWarned("{ id: m, cost_tokens: 75, type: firework, launchers: lots }", "launchers 'lots'")
                .extra().launchers());
        // every other firework row keeps 0.38.1's 3-30 (the Firework Show's 3 and the Grand show's 15)
        assertEquals(15, readClean("{ id: firework_grand, cost_tokens: 40, type: firework, seconds: 15 }").extra()
                .seconds());
        assertEquals(0, readClean("{ id: firework_grand, cost_tokens: 40, type: firework, seconds: 15 }").extra()
                .launchers());
        assertEquals(30, readWarned("{ id: f, cost_tokens: 15, type: firework, seconds: 60 }", "3 to 30 seconds")
                .extra().seconds(), "60 seconds is a Mega length only");
        // launchers on another type: one WARN, ignored
        Prize boost = readWarned("{ id: b, cost_tokens: 8, type: boost, effect: speed, launchers: 3 }",
                "only a firework row fires from Firework Launchers");
        assertEquals(0, boost.extra().launchers(), "ignored");
        // the 0.38.0 constructor is no launchers
        assertEquals(new PrizeExtra(null, 0, null, OnlyIf.ANY, 3, null, 0), new PrizeExtra(null, 0, null, OnlyIf.ANY,
                3, null), "the six-argument PrizeExtra has no launchers");
        assertEquals(0, PrizeExtra.NONE.launchers());
    }

    /** 0.38.1: the Firework Launcher is a block row, delivered as one launcher. */
    @Test
    void theLauncherIsABlockRow() throws Exception {
        Prize launcher = readClean("{ id: firework_launcher, tab: COSMETICS, display: \"&6Firework Launcher\", "
                + "description: [\"Set up 3 of them\", \"for a Mega Firework Show!\"], cost_tokens: 5, "
                + "icon: { texture: \"\", material: DROPPER }, type: block, block: firework_launcher }");
        assertEquals(PrizeType.BLOCK, launcher.type());
        assertEquals("FIREWORK_LAUNCHER", launcher.blockKey());
        assertEquals(1, launcher.amount());
        assertEquals(PrizeTab.COSMETICS, launcher.tab(), "with the shows, not the Minis tab");
        assertNull(launcher.limit(), "no limit: buying more than 3 is fine, they wait in the bag");
        Prize bad = read("{ id: x, cost_tokens: 5, type: block, block: rocket_pad }", new ArrayList<>());
        assertNull(bad, "an unknown block is skipped");
        List<String> warnings = new ArrayList<>();
        read("{ id: x, cost_tokens: 5, type: block, block: rocket_pad }", warnings);
        assertTrue(warnings.get(0).contains("firework_launcher"), "and the WARN names the launcher: " + warnings);
    }

    @Test
    void aCommandRowsOnlyIf() throws Exception {
        Prize day = readClean("{ id: make_it_day, tab: BOOSTS, display: \"&eMake it Day!\", cost_tokens: 20, "
                + "icon: { texture: \"\", material: CLOCK }, type: command, "
                + "commands: [\"execute as %uuid% at @s run time set day\"], only_if: night, "
                + "limit: { per: DAY, count: 1 } }");
        assertEquals(OnlyIf.NIGHT, day.extra().onlyIf());
        assertEquals(List.of("execute as %uuid% at @s run time set day"), day.commands());
        assertEquals(OnlyIf.RAIN, readClean("{ id: s, cost_tokens: 20, type: command, commands: [\"weather clear\"], "
                + "only_if: RAIN }").extra().onlyIf());
        assertEquals(OnlyIf.ANY, readClean("{ id: s, cost_tokens: 20, type: command, commands: [\"x\"] }")
                .extra().onlyIf(), "any time, as every command row before 0.38");
        assertEquals(OnlyIf.ANY, readWarned("{ id: s, cost_tokens: 20, type: command, commands: [\"x\"], "
                + "only_if: sunny }", "use night or rain").extra().onlyIf(), "a bad value is ignored");
    }

    // ---- tabs, types, limits ----------------------------------------------------------------------------

    @Test
    void theKeepersTabIsRefusedOnAConfigRow() throws Exception {
        Prize p = readWarned("{ id: my_trail, tab: KEEPERS, cost_tokens: 100, type: trail, particle: HEART, "
                + "days: 30 }", "only the plugin's own Keepers");
        assertEquals(PrizeTab.COSMETICS, p.tab(), "shown under its default tab");
        assertFalse(p.keeper(), "and a config row is never a Keeper");
        List<String> warnings = new ArrayList<>();
        Prize q = read("{ id: q, tab: SHOES, cost_tokens: 8, type: boost, effect: speed }", warnings);
        assertEquals(PrizeTab.BOOSTS, q.tab());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("BOOSTS, HUNT, COSMETICS, LOOKS, PERKS, TROPHIES, MINIS (KEEPERS"),
                "the WARN names the new tabs: " + warnings.get(0));
    }

    @Test
    void theDefaultTabs() throws Exception {
        assertEquals(PrizeTab.LOOKS, readClean("{ id: h, cost_tokens: 120, type: hat }").tab());
        assertEquals(PrizeTab.LOOKS, readClean("{ id: b, cost_tokens: 40, type: ball, look: LIME_CONCRETE }").tab());
        assertEquals(PrizeTab.COSMETICS, readClean("{ id: p, cost_tokens: 300, type: party }").tab());
        assertEquals(PrizeTab.COSMETICS, readClean("{ id: t, cost_tokens: 100, type: trail, particle: NOTE }").tab());
        assertEquals(PrizeTab.TROPHIES, readClean("{ id: tr, cost_tokens: 300, type: trophy }").tab());
    }

    @Test
    void monthIsALimit() throws Exception {
        Prize monthly = readClean("{ id: trophy_month, tab: TROPHIES, display: \"&eTrophy of the Month\", "
                + "cost_tokens: 1000, icon: { texture: \"\", material: CLOCK }, type: trophy, texture: \"\", "
                + "limit: { per: MONTH, count: 1 } }");
        assertEquals(new PrizeLimit(LimitPer.MONTH, 1), monthly.limit());
        Prize odd = readWarned("{ id: o, cost_tokens: 8, type: boost, effect: speed, limit: { per: YEAR, count: 1 } }",
                "use DAY, WEEK, MONTH or LIFETIME");
        assertNull(odd.limit(), "the limit is ignored");
    }

    @Test
    void anUnknownTypeNamesTheNewOnes() throws Exception {
        List<String> warnings = new ArrayList<>();
        assertNull(read("{ id: x, cost_tokens: 5, type: kite }", warnings));
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("hat, ball, party, command"), warnings.get(0));
    }

    // ---- the helpers ---------------------------------------------------------------------------------------

    @Test
    void rentalKeeperAndRenewPrice() throws Exception {
        Prize hat = readClean("{ id: cap_sky, cost_tokens: 60, type: hat, material: LEATHER_HELMET, color: LIGHT_BLUE }");
        Prize trail = readClean("{ id: trail_magic, cost_tokens: 100, type: trail, particle: ENCHANT, days: 30 }");
        Prize ball = readClean("{ id: ball_red, cost_tokens: 40, type: ball, look: RED_CONCRETE }");
        assertTrue(hat.rental(), "a hat row is a rental");
        assertTrue(trail.rental(), "a trail row is a rental");
        assertFalse(ball.rental(), "a ball is kept for good");
        assertEquals(60, hat.renewPrice(), "no renew_tokens: the same price");
        for (Prize c : com.dierks.homecraft.arcade.Collectors.ROWS) {
            assertFalse(c.rental(), c.id() + ": a collector is kept for good");
            assertFalse(c.keeper());
            assertSame(PrizeExtra.NONE, c.extra());
        }
        Prize keeper = new Prize("keeper_test", PrizeTab.KEEPERS, "&5Test", List.of(), 2500, hat.icon(),
                PrizeType.HAT, true, new PrizeLimit(LimitPer.LIFETIME, 1), 1, null, null, null, null, 0, 0, null,
                36500, "", List.of(), List.of(), null, null, List.of(), null,
                new PrizeExtra(null, 0, null, OnlyIf.ANY, 0, new Keeper(
                        new KeeperLook(Material.LEATHER_HELMET, 0x2A2F6E, "flow", "gold", null, null, false),
                        false, null, null)));
        assertTrue(keeper.keeper());
        assertFalse(keeper.rental(), "a Keeper is kept for good");
        Prize old = new Prize("x", PrizeTab.BOOSTS, "x", List.of(), 1, hat.icon(), PrizeType.BOOST, true, null, 1,
                null, null, null, "speed", 0, 1, null, 0, "", List.of(), List.of(), null, null, List.of(), null);
        assertSame(PrizeExtra.NONE, old.extra(), "the 0.37 constructor gives no 0.38 fields");
        assertEquals(PrizeExtra.NONE, new PrizeExtra(null, 0, null, null, 0, null), "a null only_if reads ANY");
    }

    // ---- what 0.37 shipped -------------------------------------------------------------------------------

    private static YamlConfiguration bundled() throws Exception {
        return file("/config.yml");
    }

    /** A config.yml on the test classpath: the bundled one, or an older version's verbatim copy. */
    private static YamlConfiguration file(String resource) throws Exception {
        try (InputStream in = PrizeExtraParseTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, resource + " is on the test classpath");
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                YamlConfiguration c = new YamlConfiguration();
                c.load(r);
                return c;
            }
        }
    }

    /** Nothing changes for the rows 0.37 shipped but the parse of the new fields: no WARN, none of them set. */
    @Test
    void the0372RowsReadAsBeforeWithNoWarn() throws Exception {
        List<String> warnings = new ArrayList<>();
        int rows = 0;
        for (Map<?, ?> row : file("/config-0.37.2.yml").getMapList("arcade.prizes")) {
            Prize p = read(row, warnings);
            assertNotNull(p, String.valueOf(row.get("id")));
            rows++;
            assertNull(p.extra().hatMaterial(), p.id());
            assertEquals(0, p.extra().renewTokens(), p.id());
            assertNull(p.extra().ballLook(), p.id());
            assertEquals(OnlyIf.ANY, p.extra().onlyIf(), p.id());
            assertEquals(p.type() == PrizeType.FIREWORK ? 3 : 0, p.extra().seconds(),
                    p.id() + ": a firework row's show is 3 seconds when it doesn't say (0.38.1), as it always was");
            assertNull(p.extra().keeper(), p.id());
            assertTrue(p.tab() != PrizeTab.LOOKS && p.tab() != PrizeTab.KEEPERS,
                    p.id() + ": every 0.37 row keeps its tab (revision 22 moves the hats)");
            if (p.type() == PrizeType.HAT) {
                assertEquals(90, p.days(), p.id() + ": 90 days once rentals are on (nothing reads a hat's days yet)");
            }
        }
        assertTrue(rows >= 30, "the whole shelf was read: " + rows);
        assertEquals(List.of(), warnings);
    }

    /** 0.38's bundled rows (revision 22) read with their new fields set as they ship, and with no WARN. */
    @Test
    void theBundledRowsReadWithTheirFieldsAndNoWarn() throws Exception {
        List<String> warnings = new ArrayList<>();
        Map<String, Prize> byId = new java.util.LinkedHashMap<>();
        for (Map<?, ?> row : bundled().getMapList("arcade.prizes")) {
            Prize p = read(row, warnings);
            assertNotNull(p, String.valueOf(row.get("id")));
            byId.put(p.id(), p);
            assertEquals(0, p.extra().renewTokens(), p.id() + ": no shipped row sets renew_tokens (D6)");
            assertNull(p.extra().keeper(), p.id() + ": no config row is a Keeper");
        }
        assertEquals(List.of(), warnings);
        assertEquals(Material.CREEPER_HEAD, byId.get("hat_creeper").extra().hatMaterial());
        assertEquals(Material.DRAGON_HEAD, byId.get("hat_dragon").extra().hatMaterial());
        assertEquals(Material.LEATHER_HELMET, byId.get("cap_sky").extra().hatMaterial());
        assertEquals(org.bukkit.DyeColor.LIGHT_BLUE, byId.get("cap_sky").color());
        assertNull(byId.get("hat_bunny").extra().hatMaterial(), "an animal hat is a textured head");
        for (Prize p : byId.values()) {
            if (p.type() == PrizeType.HAT) {
                assertEquals(PrizeTab.LOOKS, p.tab(), p.id());
                assertEquals(90, p.days(), p.id());
            }
            if (p.type() == PrizeType.TRAIL) {
                assertEquals(30, p.days(), p.id() + ": a month");
            }
            if (p.type() == PrizeType.BALL) {
                assertEquals(PrizeTab.LOOKS, p.tab(), p.id());
                assertEquals(p.icon().material(), p.extra().ballLook(), p.id() + ": the tile is the ball's block");
                assertEquals(new PrizeLimit(LimitPer.LIFETIME, 1), p.limit(), p.id());
            }
        }
        assertEquals(10, byId.values().stream().filter(p -> p.type() == PrizeType.BALL).count());
        assertEquals(10, byId.get("party_time").extra().seconds());
        assertEquals(3, byId.get("firework_show").extra().seconds(), "the Firework Show is the short show");
        Prize grand = byId.get("firework_grand");
        assertEquals(PrizeType.FIREWORK, grand.type());
        assertEquals(15, grand.extra().seconds(), "the Grand Firework Show: 15 seconds");
        assertEquals(40, grand.costTokens());
        assertEquals(PrizeTab.COSMETICS, grand.tab());
        assertNull(grand.limit(), "no limit");
        assertEquals(Material.FIREWORK_ROCKET, grand.icon().material());
        assertEquals(List.of("A long firework show", "with a big finale!"), grand.description());
        List<String> order = new ArrayList<>(byId.keySet());
        assertEquals(order.indexOf("firework_show") + 1, order.indexOf("firework_grand"), "right after the Firework Show");
        assertEquals(0, grand.extra().launchers(), "the Grand show plays around her");
        Prize launcher = byId.get("firework_launcher");
        assertEquals(PrizeType.BLOCK, launcher.type());
        assertEquals("FIREWORK_LAUNCHER", launcher.blockKey());
        assertEquals(5, launcher.costTokens(), "5 tokens, kept for good");
        assertEquals(PrizeTab.COSMETICS, launcher.tab());
        assertNull(launcher.limit());
        assertEquals(Material.DROPPER, launcher.icon().material());
        Prize mega = byId.get("firework_mega");
        assertEquals(PrizeType.FIREWORK, mega.type());
        assertEquals(75, mega.costTokens(), "the owner's \"one more level at like 75\"");
        assertEquals(60, mega.extra().seconds(), "a minute");
        assertEquals(3, mega.extra().launchers(), "three firing locations");
        assertEquals(PrizeTab.COSMETICS, mega.tab());
        assertNull(mega.limit(), "no limit");
        assertTrue(mega.costTokens() < 100, "under the confirm screen's 100");
        assertEquals(List.of("firework_grand", "firework_launcher", "firework_mega"),
                order.subList(order.indexOf("firework_grand"), order.indexOf("firework_grand") + 3),
                "the launcher and the Mega show right after the Grand show");
        assertEquals(List.of("firework_mega", "firework_super", "firework_ultra", "firework_ultimate"),
                byId.values().stream().filter(p -> p.extra().launchers() > 0).map(Prize::id).toList(),
                "the four Mega tiers fire from launchers (0.38.2), and only they");
        int[][] tiers = {{4, 100, 60}, {5, 125, 60}, {10, 200, 90}};
        String[] ids = {"firework_super", "firework_ultra", "firework_ultimate"};
        for (int i = 0; i < ids.length; i++) {
            Prize t = byId.get(ids[i]);
            assertEquals(PrizeType.FIREWORK, t.type(), ids[i]);
            assertEquals(tiers[i][0], t.extra().launchers(), ids[i] + ": its launchers");
            assertEquals(tiers[i][1], t.costTokens(), ids[i] + ": its price");
            assertEquals(tiers[i][2], t.extra().seconds(), ids[i] + ": its length");
            assertEquals(PrizeTab.COSMETICS, t.tab(), ids[i]);
            assertNull(t.limit(), ids[i] + ": no limit");
            assertTrue(t.costTokens() >= 100, ids[i] + ": the confirm screen asks first");
        }
        assertEquals(List.of("firework_mega", "firework_super", "firework_ultra", "firework_ultimate",
                        "firework_remote"),
                order.subList(order.indexOf("firework_mega"), order.indexOf("firework_mega") + 5),
                "the three tiers and the Remote right after the Mega show (0.38.2)");
        Prize remote = byId.get("firework_remote");
        assertEquals(PrizeType.REMOTE, remote.type());
        assertEquals(PrizeTab.COSMETICS, remote.tab());
        assertEquals(Material.RECOVERY_COMPASS, remote.icon().material());
        assertEquals(10, remote.costTokens());
        assertNull(remote.limit(), "a lost one can be bought again");
        assertEquals(0, remote.extra().launchers());
        assertEquals(PrizeType.PARTY, byId.get("party_time").type());
        assertEquals(OnlyIf.NIGHT, byId.get("make_it_day").extra().onlyIf());
        assertEquals(OnlyIf.RAIN, byId.get("sunny_day").extra().onlyIf());
        assertEquals(new PrizeLimit(LimitPer.MONTH, 1), byId.get("trophy_month").limit());
        assertEquals(new PrizeLimit(LimitPer.LIFETIME, 99), byId.get("arcade_trophy").limit());
        assertEquals(List.of(750, 1000, 1250, 1500), byId.get("arcade_trophy").costs());
        assertEquals("trophy_diamond", byId.get("trophy_legend").requiresPrize());
    }
}
