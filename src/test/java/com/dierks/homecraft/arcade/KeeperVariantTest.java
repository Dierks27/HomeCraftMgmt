package com.dierks.homecraft.arcade;

import com.dierks.homecraft.arcade.looks.LookRules;
import com.dierks.homecraft.arcade.looks.LookRules.Decision;
import com.dierks.homecraft.arcade.looks.LookRules.Found;
import com.dierks.homecraft.arcade.looks.LookRules.Verdict;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.config.PluginConfig.KeeperLook;
import com.dierks.homecraft.config.PluginConfig.KeeperSwitches;
import com.dierks.homecraft.storage.LookDao;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The look switch (SHOP-V2-SPEC §5.2, §5.3, red team O5): each Keeper ships two forms and {@code
 * arcade.keepers.looks.<id>} picks one; a bad value reads {@code default} with one WARN; an owned key of the old form
 * is rebuilt in place at the next sweep (REFRESH), its number kept (the number lives in {@code arcade_state}, never in
 * the form); {@code arcade.keepers.marks: off} draws no mark.
 */
class KeeperVariantTest {

    private static final UUID HOLDER = UUID.randomUUID();
    private static final long NOW = 1_800_000_000_000L;

    private static YamlConfiguration yaml(String text) throws Exception {
        YamlConfiguration c = new YamlConfiguration();
        c.loadFromString(text);
        return c;
    }

    @Test
    void fallbackIsTheSecondTrimAndTheCrownLosesItsGlint() {
        KeeperLook crown = Keepers.look(Keepers.CROWN, Keepers.Variant.DEFAULT);
        KeeperLook crownBack = Keepers.look(Keepers.CROWN, Keepers.Variant.FALLBACK);
        assertEquals(new KeeperLook(Material.LEATHER_HELMET, 0xE8B931, "spire", "redstone", "minecraft:gold",
                "minecraft:golden_helmet", true), crown);
        assertEquals("spire", crownBack.trimPattern());
        assertEquals("diamond", crownBack.trimMaterial());
        assertFalse(crownBack.glint(), "the Crown's fallback drops the glint");
        assertEquals(crown.javaAsset(), crownBack.javaAsset(), "and keeps its gold for Java");
        assertEquals(crown.rgb(), crownBack.rgb());

        KeeperLook wizard = Keepers.look(Keepers.WIZARD, Keepers.Variant.DEFAULT);
        assertEquals(0x2A2F6E, wizard.rgb());
        assertEquals(List.of("flow", "gold"), List.of(wizard.trimPattern(), wizard.trimMaterial()));
        KeeperLook wizardBack = Keepers.look(Keepers.WIZARD, Keepers.Variant.FALLBACK);
        assertEquals(List.of("silence", "amethyst"), List.of(wizardBack.trimPattern(), wizardBack.trimMaterial()));

        KeeperLook knight = Keepers.look(Keepers.KNIGHT, Keepers.Variant.DEFAULT);
        assertEquals(Material.IRON_HELMET, knight.base());
        assertEquals(-1, knight.rgb(), "iron takes no dye");
        assertEquals(List.of("sentry", "gold"), List.of(knight.trimPattern(), knight.trimMaterial()));
        assertEquals(List.of("ward", "gold"), List.of(Keepers.look(Keepers.KNIGHT, Keepers.Variant.FALLBACK)
                .trimPattern(), Keepers.look(Keepers.KNIGHT, Keepers.Variant.FALLBACK).trimMaterial()));
        for (String hat : List.of(Keepers.CROWN, Keepers.WIZARD, Keepers.KNIGHT)) {
            assertEquals(Keepers.look(hat, Keepers.Variant.DEFAULT).base(),
                    Keepers.look(hat, Keepers.Variant.FALLBACK).base(), hat + ": one base, so a rebuild is in place");
        }
    }

    @Test
    void aTrailsFallbackIsItsOtherStep() {
        assertEquals(Particle.FALLING_HONEY, Keepers.pattern(Keepers.ROYAL, Keepers.Variant.FALLBACK).steps().get(0)
                .particle(), "gold drips from the crown");
        assertEquals(List.of(Particle.SCRAPE, Particle.PORTAL), particles(Keepers.AURORA, Keepers.Variant.FALLBACK));
        assertEquals(List.of(Particle.WAX_ON, Particle.WAX_ON, Particle.FALLING_NECTAR),
                particles(Keepers.STARDUST, Keepers.Variant.FALLBACK), "nectar for CRIT");
        assertEquals(List.of(Particle.ITEM_SNOWBALL), particles(Keepers.FROST, Keepers.Variant.FALLBACK),
                "snowballs alone");
        assertEquals(Particle.FALLING_HONEY, Keepers.mark(Keepers.CROWN, Keepers.Variant.FALLBACK).particle(),
                "the Crown's mark follows its Royal Trail's form");
        assertEquals(Particle.TOTEM_OF_UNDYING, Keepers.mark(Keepers.CROWN, Keepers.Variant.DEFAULT).particle());
        assertEquals(Keepers.mark(Keepers.WIZARD, Keepers.Variant.DEFAULT),
                Keepers.mark(Keepers.WIZARD, Keepers.Variant.FALLBACK), "a mark has no fallback of its own");
    }

    private static List<Particle> particles(String id, Keepers.Variant v) {
        return Keepers.pattern(id, v).steps().stream().map(TrailService.Step::particle).toList();
    }

    @Test
    void theSwitchReadsDefaultFallbackAndWarnsOnAnythingElse() throws Exception {
        List<String> warnings = new ArrayList<>();
        KeeperSwitches s = PluginConfig.keeperSwitches(yaml("""
                arcade:
                  keepers:
                    looks:
                      keeper_legend_crown: fallback
                      keeper_wizard_hat: Default
                      keeper_knight_helm: shiny
                """), warnings::add);
        assertEquals(Keepers.Variant.FALLBACK, Keepers.variant(s, Keepers.CROWN));
        assertEquals(Keepers.Variant.DEFAULT, Keepers.variant(s, Keepers.WIZARD));
        assertEquals(Keepers.Variant.DEFAULT, Keepers.variant(s, Keepers.KNIGHT), "a bad value reads default");
        assertEquals(Keepers.Variant.DEFAULT, Keepers.variant(s, Keepers.FROST), "absent reads default");
        assertEquals(1, warnings.size(), "one WARN, for the bad value");
        assertTrue(warnings.get(0).contains("arcade.keepers.looks.keeper_knight_helm") && warnings.get(0)
                .contains("'shiny'"), warnings.get(0));
        assertEquals(7, s.looks().size(), "one key per Keeper");
        assertTrue(s.onSale(), "on sale unless switched off");
        assertTrue(s.marks(), "marks on unless switched off");
    }

    @Test
    void theBundledFileShipsEveryKeeperInItsDefaultForm() throws Exception {
        YamlConfiguration c;
        try (InputStream in = KeeperVariantTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in);
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                c = new YamlConfiguration();
                c.load(r);
            }
        }
        List<String> warnings = new ArrayList<>();
        KeeperSwitches s = PluginConfig.keeperSwitches(c, warnings::add);
        assertTrue(warnings.isEmpty(), warnings.toString());
        assertTrue(s.onSale());
        assertTrue(s.marks());
        for (String id : Keepers.SHIPPED_IDS) {
            assertEquals("default", c.getString("arcade.keepers.looks." + id), id + " ships its key");
            assertEquals(Keepers.Variant.DEFAULT, Keepers.variant(s, id));
        }
        assertEquals(Keepers.SHIPPED_IDS.size(), c.getConfigurationSection("arcade.keepers.looks").getKeys(false)
                .size());
    }

    @Test
    void marksOffDrawsNone() throws Exception {
        for (String off : List.of("marks: off", "marks: \"off\"", "marks: false")) {
            KeeperSwitches s = PluginConfig.keeperSwitches(yaml("arcade:\n  keepers:\n    " + off + "\n"), null);
            assertFalse(s.marks(), off);
            assertFalse(TrailService.markPuffs(s.marks(), true, true, true, false, null, NOW), off + ": no mark puffs");
        }
        List<String> warnings = new ArrayList<>();
        KeeperSwitches odd = PluginConfig.keeperSwitches(yaml("arcade:\n  keepers:\n    marks: sparkly\n"),
                warnings::add);
        assertTrue(odd.marks(), "anything else reads on");
        assertEquals(1, warnings.size());
        KeeperSwitches on = PluginConfig.keeperSwitches(yaml("arcade:\n  keepers:\n    marks: on\n"), null);
        assertTrue(TrailService.markPuffs(on.marks(), true, true, true, false, null, NOW));
        KeeperSwitches notSold = PluginConfig.keeperSwitches(yaml("arcade:\n  keepers:\n    on_sale: false\n"), null);
        assertFalse(notSold.onSale());
    }

    /**
     * A sweep finding a live Keeper key of another form than the config's gives REFRESH (rebuilt in place: name, lore,
     * number and binding kept, only the look changes); one of the configured form, or a rental, is kept as it is.
     */
    @Test
    void aSweepRefreshesAKeyOfTheOldFormInPlace() {
        long kept = NOW + 36_500L * LookRules.DAY;
        Map<String, LookDao.Look> rights = Map.of(Keepers.CROWN,
                new LookDao.Look(Keepers.CROWN, LookDao.HAT, kept, NOW - LookRules.DAY, false, 0, 0, ""));
        Found old = new Found(LookRules.Area.HEAD, 0, List.of(),
                new LookRules.Key(Keepers.CROWN, HOLDER, 0, "default"));
        List<Decision> plan = LookRules.plan(true, HOLDER, rights, NOW, List.of(old),
                id -> Keepers.CROWN.equals(id) ? "fallback" : null);
        assertEquals(Verdict.REFRESH, plan.get(0).verdict(), "the owner picked fallback: rebuilt in place");

        Found same = new Found(LookRules.Area.HEAD, 0, List.of(),
                new LookRules.Key(Keepers.CROWN, HOLDER, 0, "fallback"));
        assertEquals(Verdict.KEEP, LookRules.plan(true, HOLDER, rights, NOW, List.of(same),
                id -> "fallback").get(0).verdict(), "once rebuilt, kept");
        Found unmarked = new Found(LookRules.Area.BAG, 3, List.of(), new LookRules.Key(Keepers.CROWN, HOLDER, 0, null));
        assertEquals(Verdict.REFRESH, LookRules.plan(true, HOLDER, rights, NOW, List.of(unmarked),
                id -> "default").get(0).verdict(), "a key with no form written is given one");
        assertEquals(Verdict.KEEP, LookRules.plan(false, HOLDER, rights, NOW, List.of(old),
                id -> "fallback").get(0).verdict(), "never on an unknown answer");
    }

    /** The Crown's "No. N" is stored per player in arcade_state, never derived from the look, so a rebuild keeps it. */
    @Test
    void theNumberIsKeptAcrossTheSwitch() {
        assertEquals("keeper_no:keeper_legend_crown:" + HOLDER,
                com.dierks.homecraft.arcade.looks.LookService.numberKey(Keepers.CROWN, HOLDER));
        assertEquals("keeper_serial:keeper_legend_crown",
                com.dierks.homecraft.arcade.looks.LookService.serialKey(Keepers.CROWN));
        assertNotEquals(Keepers.look(Keepers.CROWN, Keepers.Variant.DEFAULT),
                Keepers.look(Keepers.CROWN, Keepers.Variant.FALLBACK));
        List<String> lore = PrizeItems.keeperLore(Keepers.row(Keepers.CROWN), 7, true);
        assertTrue(lore.contains("&7No. 7"), lore.toString());
        assertTrue(lore.containsAll(PrizeItems.keeperLines()));
        assertFalse(PrizeItems.keeperLore(Keepers.row(Keepers.WIZARD), 0, false).stream()
                .anyMatch(l -> l.startsWith("&7No.")), "only a number above 0 is written");
    }
}
