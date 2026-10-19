package com.dierks.homecraft.arcade;

import com.dierks.homecraft.config.PluginConfig.LimitPer;
import com.dierks.homecraft.config.PluginConfig.Prize;
import com.dierks.homecraft.config.PluginConfig.PrizeTab;
import com.dierks.homecraft.config.PluginConfig.PrizeType;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The collectors' catalogue as it ships (HALLOWEEN-SPEC §2.1, D2-D6): ids, names and lore stamped
 * with the year, the owner's prices, one each for good, and an append-only list of shipped ids.
 */
class CollectorsTest {

    private static Prize row(String id) {
        for (Prize p : Collectors.ROWS) {
            if (p.id().equals(id)) {
                return p;
            }
        }
        throw new AssertionError("no collector " + id);
    }

    @Test
    void shippedIdsAreAppendOnly() {
        assertEquals(List.of("cap_pumpkin_2026", "trail_leaves_2026"), Collectors.SHIPPED_IDS,
                "a shipped collector id is never removed or renamed: owned trails would vanish");
        for (Prize p : Collectors.ROWS) {
            assertTrue(Collectors.SHIPPED_IDS.contains(p.id()), p.id() + " is in SHIPPED_IDS");
        }
    }

    @Test
    void everyRowIsStampedWithItsYear() {
        Set<String> ids = new HashSet<>();
        for (Prize p : Collectors.ROWS) {
            assertNotNull(p.collector(), p.id());
            String year = String.valueOf(p.collector().year());
            assertTrue(ids.add(p.id().toLowerCase(Locale.ROOT)), "ids are unique: " + p.id());
            assertTrue(p.id().contains(year), "the id carries the year: " + p.id());
            assertTrue(p.display().contains(year), "the name carries the year: " + p.display());
            assertEquals(year, String.valueOf(p.collector().from().getYear()), "the season is in its year");
        }
    }

    @Test
    void theCapLoreCarriesTheYearAndTheGiver() {
        Prize cap = row("cap_pumpkin_2026");
        assertEquals(List.of("&7A pumpkin cap for Halloween!", "&6Halloween 2026 collector", "&aYours for good!",
                "&7Wear it! Right-click to put it on.", "&8Arcade prize"), PrizeItems.collectorLore(cap, null));
        assertEquals(List.of("&7A pumpkin cap for Halloween!", "&6Halloween 2026 collector", "&aYours for good!",
                        "&7A gift from &fA Parent", "&7Wear it! Right-click to put it on.", "&8Arcade prize"),
                PrizeItems.collectorLore(cap, "A Parent"));
        assertEquals(PrizeItems.collectorLore(cap, null), PrizeItems.collectorLore(cap, " "), "a blank giver is none");
    }

    /** D7 (SHOULD): the serial number goes after the giver and before how to wear it; 0 is none. */
    @Test
    void theCapLoreCarriesItsSerialNumber() {
        Prize cap = row("cap_pumpkin_2026");
        assertEquals(List.of("&7A pumpkin cap for Halloween!", "&6Halloween 2026 collector", "&aYours for good!",
                        "&7A gift from &fA Parent", "&7No. 3", "&7Wear it! Right-click to put it on.", "&8Arcade prize"),
                PrizeItems.collectorLore(cap, "A Parent", 3));
        assertEquals(List.of("&7A pumpkin cap for Halloween!", "&6Halloween 2026 collector", "&aYours for good!",
                        "&7No. 1", "&7Wear it! Right-click to put it on.", "&8Arcade prize"),
                PrizeItems.collectorLore(cap, null, 1));
        assertEquals(PrizeItems.collectorLore(cap, null), PrizeItems.collectorLore(cap, null, 0),
                "no number when it couldn't be read");
        assertEquals("collector:cap_pumpkin_2026", PrizeService.serialKey("cap_pumpkin_2026"),
                "its own counter in arcade_state, apart from the trophy's");
    }

    @Test
    void thePumpkinCap() {
        Prize cap = row("cap_pumpkin_2026");
        assertEquals("&6Pumpkin Cap 2026", cap.display());
        assertEquals(250, cap.costTokens());
        assertEquals(PrizeTab.COSMETICS, cap.tab());
        assertEquals(PrizeType.HAT, cap.type());
        assertEquals(LimitPer.LIFETIME, cap.limit().per());
        assertEquals(1, cap.limit().count());
        assertTrue(cap.enabled());
        assertTrue(cap.collector().giftable());
        assertEquals(Material.LEATHER_HELMET, cap.icon().material());
        assertNotNull(cap.collector().look());
        assertEquals(0xF9801D, cap.collector().look().rgb());
        assertEquals("wild", cap.collector().look().trimPattern());
        assertEquals("emerald", cap.collector().look().trimMaterial());
        assertTrue(cap.costs().isEmpty(), "one price, never a ladder");
    }

    @Test
    void theAutumnLeavesTrail() {
        Prize trail = row("trail_leaves_2026");
        assertEquals("&6Autumn Leaves 2026", trail.display());
        assertEquals(400, trail.costTokens());
        assertEquals(PrizeTab.COSMETICS, trail.tab());
        assertEquals(PrizeType.TRAIL, trail.type());
        assertEquals(LimitPer.LIFETIME, trail.limit().per());
        assertEquals(1, trail.limit().count());
        assertTrue(trail.enabled());
        assertTrue(trail.collector().giftable());
        assertEquals("ORANGE_POPLAR_LEAVES", trail.particle());
        // the orange poplar leaves block where the API has it (26.3); the 26.2 API the plugin compiles against
        // has none, and there a plain leaves block stands in
        Material poplar = Material.matchMaterial("ORANGE_POPLAR_LEAVES");
        assertEquals(poplar != null ? poplar : Material.OAK_LEAVES, trail.icon().material());
        assertEquals(36500, trail.days(), "kept for good: 100 years, never a Long.MAX_VALUE that overflows");
        assertNull(trail.collector().look());
    }

    @Test
    void collectorIdsAreCaseInsensitive() {
        assertTrue(Collectors.isCollectorId("cap_pumpkin_2026"));
        assertTrue(Collectors.isCollectorId("CAP_Pumpkin_2026"));
        assertTrue(Collectors.isCollectorId(" trail_leaves_2026 "));
        assertFalse(Collectors.isCollectorId("cap_pumpkin"));
        assertFalse(Collectors.isCollectorId("cap_pumpkin_2027"));
        assertFalse(Collectors.isCollectorId(null));
    }

    @Test
    void aConfigRowMayNotTakeACollectorOrCandyId() {
        assertTrue(Collectors.reserved("cap_pumpkin_2026"));
        assertTrue(Collectors.reserved("Trail_Leaves_2026"));
        assertTrue(Collectors.reserved("candy_x"));
        assertTrue(Collectors.reserved("CANDY_apple_2026"));
        assertTrue(Collectors.reserved("bag_trick_or_treat_x"));
        assertFalse(Collectors.reserved("candycane_cap"), "only the candy_ prefix, with its underscore");
        assertFalse(Collectors.reserved("trail_heart"));
        assertFalse(Collectors.reserved(null));
        assertEquals("Arcade prize 'candy_x' is one of the plugin's own collectors - your row is skipped "
                + "(collectors are built in; see arcade.collectors).", Collectors.skipWarning("candy_x"));
    }
}
