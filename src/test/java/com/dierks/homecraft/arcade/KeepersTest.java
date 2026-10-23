package com.dierks.homecraft.arcade;

import com.dierks.homecraft.config.PluginConfig.LimitPer;
import com.dierks.homecraft.config.PluginConfig.Prize;
import com.dierks.homecraft.config.PluginConfig.PrizeTab;
import com.dierks.homecraft.config.PluginConfig.PrizeType;
import org.bukkit.Particle;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Keepers catalogue (SHOP-V2-SPEC §5.1, §5.3, §5.3a, §9 S4): seven rows in the jar, append only, at the approved
 * prices, one each for good; the Royal Trail a companion that is never a tile; every particle a Keeper can draw (the
 * patterns', the fallbacks', the marks': twelve) reserved and data-free, and none of them used by a rental or a
 * collector.
 */
class KeepersTest {

    private static final PrizeService.Shelf SHELF = new PrizeService.Shelf(true, LocalDate.of(2026, 11, 21), Set.of(),
            true);

    @Test
    void theIdsAreUniqueAndAllKeepers() {
        Set<String> ids = new HashSet<>();
        for (Prize p : Keepers.ROWS) {
            assertTrue(ids.add(p.id()), p.id() + " is used once");
            assertTrue(p.id().startsWith(Keepers.PREFIX), p.id());
            assertTrue(Keepers.reserved(p.id()), p.id() + ": no config row may take it");
            assertTrue(Keepers.isKeeperId(p.id().toUpperCase()), p.id());
            assertTrue(p.keeper(), p.id() + " carries its Keeper record");
            assertEquals(PrizeTab.KEEPERS, p.tab(), p.id());
            assertNull(p.collector(), p.id() + " is no collector");
            assertFalse(p.rental(), p.id() + " is kept for good, never rented");
        }
        assertEquals(Keepers.SHIPPED_IDS, Keepers.ROWS.stream().map(Prize::id).toList(),
                "SHIPPED_IDS are the row ids, in order");
        assertEquals(List.of("keeper_legend_crown", "keeper_trail_royal", "keeper_trail_aurora", "keeper_trail_stardust",
                "keeper_trail_frost", "keeper_wizard_hat", "keeper_knight_helm"), Keepers.SHIPPED_IDS);
    }

    @Test
    void thePricesAreTheApprovedOnes() {
        assertEquals(5000, Keepers.row(Keepers.CROWN).costTokens());
        assertEquals(3000, Keepers.row(Keepers.AURORA).costTokens());
        assertEquals(3000, Keepers.row(Keepers.STARDUST).costTokens());
        assertEquals(3000, Keepers.row(Keepers.FROST).costTokens());
        assertEquals(2500, Keepers.row(Keepers.WIZARD).costTokens());
        assertEquals(2500, Keepers.row(Keepers.KNIGHT).costTokens());
        for (Prize p : Keepers.ROWS) {
            assertNotNull(p.limit(), p.id());
            assertEquals(LimitPer.LIFETIME, p.limit().per(), p.id() + ": one each");
            assertEquals(1, p.limit().count(), p.id());
            assertEquals(36500, p.days(), p.id() + ": kept for good");
            assertTrue(p.costs().isEmpty(), p.id() + ": one price");
            assertFalse(PrizeService.giftable(p), p.id() + ": never a present");
        }
    }

    @Test
    void theRowsAreWhatTheSpecShips() {
        Prize crown = Keepers.row(Keepers.CROWN);
        assertEquals(PrizeType.HAT, crown.type());
        assertEquals("&6&lLegend Crown", crown.display());
        assertEquals(List.of("The rarest look of all.", "Comes with its own Royal Trail."), crown.description());
        assertEquals(Keepers.ROYAL, crown.extra().keeper().withTrail(), "the Crown comes with the Royal Trail");
        assertNull(crown.extra().keeper().mark(), "its mark is the Royal Trail's own step");
        assertEquals(Particle.WITCH, Keepers.row(Keepers.WIZARD).extra().keeper().mark());
        assertEquals(Particle.SMALL_FLAME, Keepers.row(Keepers.KNIGHT).extra().keeper().mark());
        for (String trail : List.of(Keepers.ROYAL, Keepers.AURORA, Keepers.STARDUST, Keepers.FROST)) {
            Prize p = Keepers.row(trail);
            assertEquals(PrizeType.TRAIL, p.type(), trail);
            assertNull(p.extra().keeper().look(), trail + " has no worn look");
            assertNull(p.extra().keeper().mark(), trail + "'s mark is null");
            assertEquals(Keepers.pattern(trail, Keepers.Variant.DEFAULT).steps().get(0).particle().name(),
                    p.particle(), trail + ": its particle is its pattern's first step");
        }
        for (String hat : List.of(Keepers.CROWN, Keepers.WIZARD, Keepers.KNIGHT)) {
            assertEquals(Keepers.look(hat, Keepers.Variant.DEFAULT), Keepers.row(hat).extra().keeper().look(),
                    hat + ": the row carries its default look");
            assertEquals("", Keepers.row(hat).texture(), hat + ": a trimmed helmet, never a head");
        }
        assertEquals("&5Wizard Hat", Keepers.row(Keepers.WIZARD).display());
        assertEquals("&7Knight's Helm", Keepers.row(Keepers.KNIGHT).display());
        assertEquals(List.of("A shining knight's helmet.", "Just for looks: no armour.", "A tiny flame on its crest."),
                Keepers.row(Keepers.KNIGHT).description());
    }

    @Test
    void theRoyalTrailIsACompanionNeverATile() {
        Prize royal = Keepers.row(Keepers.ROYAL);
        assertTrue(royal.extra().keeper().companion());
        assertFalse(PrizeService.shelved(royal, SHELF), "never visible: it comes with the Crown");
        for (Prize p : Keepers.ROWS) {
            if (!p.id().equals(Keepers.ROYAL)) {
                assertFalse(p.extra().keeper().companion(), p.id());
                assertTrue(PrizeService.shelved(p, SHELF), p.id() + " is on the shelf (no texture needed)");
            }
        }
    }

    @Test
    void onSaleFalseHidesEveryKeeper() {
        PrizeService.Shelf off = new PrizeService.Shelf(true, SHELF.today(), Set.of(), true, false);
        for (Prize p : Keepers.ROWS) {
            assertFalse(PrizeService.shelved(p, off), p.id() + ": not sold (an owned one is drawn on the tab anyway)");
        }
        PrizeService.Shelf noTotem = new PrizeService.Shelf(true, SHELF.today(), Set.of(Keepers.AURORA), true);
        assertFalse(PrizeService.shelved(Keepers.row(Keepers.AURORA), noTotem), "a trail this server can't show");
    }

    @Test
    void everyParticleAKeeperDrawsIsReservedAndTakesNoData() {
        Set<Particle> drawn = new LinkedHashSet<>();
        for (String trail : List.of(Keepers.ROYAL, Keepers.AURORA, Keepers.STARDUST, Keepers.FROST)) {
            for (Keepers.Variant v : Keepers.Variant.values()) {
                for (TrailService.Step step : Keepers.pattern(trail, v).steps()) {
                    drawn.add(step.particle());
                }
            }
        }
        for (String hat : List.of(Keepers.CROWN, Keepers.WIZARD, Keepers.KNIGHT)) {
            for (Keepers.Variant v : Keepers.Variant.values()) {
                drawn.add(Keepers.mark(hat, v).particle());
            }
        }
        assertEquals(12, drawn.size(), "the patterns' seven, the three fallbacks, the two marks");
        assertEquals(Set.copyOf(drawn), Set.copyOf(Keepers.RESERVED_PARTICLES), "exactly the reserved twelve");
        assertEquals(12, Keepers.RESERVED_PARTICLES.size());
        for (Particle p : Keepers.RESERVED_PARTICLES) {
            assertEquals(Void.class, p.getDataType(), p + " takes no data in paper-api 26.2");
            assertTrue(TrailService.particleUsable(p.name()), p.name());
            assertTrue(Keepers.reservedParticle(p.name().toLowerCase()), p.name());
        }
        assertFalse(Keepers.reservedParticle("HEART"));
        assertFalse(Keepers.reservedParticle(null));
    }

    @Test
    void noShippedRentalOrCollectorUsesAReservedParticle() throws Exception {
        int trails = 0;
        for (Map<?, ?> row : bundled().getMapList("arcade.prizes")) {
            Object particle = row.get("particle");
            if (particle != null) {
                trails++;
                assertFalse(Keepers.reservedParticle(String.valueOf(particle)),
                        row.get("id") + " uses " + particle + ", a Keeper's");
            }
        }
        assertEquals(8, trails, "the eight rented trails were read");
        for (Prize p : Collectors.ROWS) {
            if (p.type() == PrizeType.TRAIL) {
                assertFalse(Keepers.reservedParticle(p.particle()), p.id());
            }
        }
    }

    @Test
    void theVariantWordsParse() {
        assertEquals(Keepers.Variant.DEFAULT, Keepers.Variant.parse(" Default "));
        assertEquals(Keepers.Variant.FALLBACK, Keepers.Variant.parse("FALLBACK"));
        assertNull(Keepers.Variant.parse("gold"));
        assertNull(Keepers.Variant.parse(null));
        assertEquals("default", Keepers.Variant.DEFAULT.key());
        assertEquals("fallback", Keepers.Variant.FALLBACK.key());
    }

    private static YamlConfiguration bundled() throws Exception {
        try (InputStream in = KeepersTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in);
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                YamlConfiguration c = new YamlConfiguration();
                c.load(r);
                return c;
            }
        }
    }
}
