package com.dierks.homecraft.market;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The admin GUI's validation must match what {@code PluginConfig} would do to the row on
 * the next load. Where the parser silently clamps a bad number, the draft has to refuse it
 * instead — otherwise the admin's value changes under them after they hit Save.
 *
 * <p>No server needed: {@link MarketDraft} touches only {@link Material}, which is a plain
 * enum.
 */
class MarketDraftTest {

    /** A row that should pass every rule. */
    private static MarketDraft valid() {
        MarketDraft d = new MarketDraft();
        d.setId("copper_ingot");
        d.setMaterial(Material.COPPER_INGOT);
        d.setFloor(1.5);
        d.setCeiling(20.0);
        d.setFullStock(1000);
        d.setInitialStock(500);
        return d;
    }

    @Test
    void aWellFormedRowHasNoProblems() {
        assertEquals(java.util.List.of(), valid().problems(Set.of()));
    }

    @Test
    void idIsNormalisedSoDuplicatesCannotSlipThroughOnCase() {
        MarketDraft d = valid();
        d.setId("  Copper_Ingot  ");
        assertEquals("copper_ingot", d.id());
        assertFalse(d.problems(Set.of("copper_ingot")).isEmpty(), "must clash with the existing id");
        assertTrue(d.problems(Set.of("iron_ingot")).isEmpty(), "…but not with an unrelated one");
    }

    @Test
    void idCharsetIsRestrictedToWhatChatCommandsCanAddress() {
        for (String bad : java.util.List.of("", "  ", "copper ingot", "copper.ingot", "copper-ingot")) {
            MarketDraft d = valid();
            d.setId(bad);
            assertFalse(d.problems(Set.of()).isEmpty(), "'" + bad + "' should be rejected");
        }
    }

    @Test
    void aMaterialMustBePickedAndMustBeARealItem() {
        MarketDraft d = valid();
        d.setMaterial(null);
        assertFalse(d.problems(Set.of()).isEmpty(), "an unset material blocks saving");

        // Accepted by the config parser, but trading it charges the player and hands
        // back nothing — new ItemStack(AIR, qty) adds no item.
        d.setMaterial(Material.AIR);
        assertFalse(d.problems(Set.of()).isEmpty(), "AIR is not tradable");
    }

    @Test
    void aHalfFilledDraftStillHasALabelAndNeverThrows() {
        MarketDraft d = new MarketDraft();
        assertEquals("(no material)", d.label(), "the preview icon must survive an unset material");
        d.setMaterial(Material.DIAMOND);
        assertEquals("DIAMOND", d.label());
        d.setDisplayName("Shiny Rock");
        assertEquals("Shiny Rock", d.label());
    }

    @Test
    void theParsersSilentClampsAreRefusedInsteadOfAccepted() {
        MarketDraft d = valid();
        d.setFloor(0);            // parser keeps 0; the engine then collapses the ask
        assertFalse(d.problems(Set.of()).isEmpty(), "floor 0");

        d = valid();
        d.setCeiling(1.0);        // parser raises a below-floor ceiling and warns
        assertFalse(d.problems(Set.of()).isEmpty(), "ceiling below floor");

        d = valid();
        d.setFullStock(1);        // parser allows it; maxStock then becomes 0
        assertFalse(d.problems(Set.of()).isEmpty(), "full stock 1");

        d = valid();
        d.setInitialStock(1000);  // parser clamps to 85% of full_stock and warns
        d.setFullStock(1000);
        assertFalse(d.problems(Set.of()).isEmpty(), "initial stock == full stock");
    }

    @Test
    void displayNameClearsBackToTheMaterial() {
        MarketDraft d = valid();
        d.setDisplayName("Copper");
        assertEquals("Copper", d.displayName());
        d.setDisplayName("none");
        assertNull(d.displayName(), "'none' clears it");
        d.setDisplayName("   ");
        assertNull(d.displayName(), "blank clears it");
    }

    @Test
    void theRowRoundTripsInTheShapeTheParserExpects() {
        MarketDraft d = valid();
        d.setMaxDailySell(20);
        Map<String, Object> row = d.toRow();

        assertEquals("copper_ingot", row.get("id"));
        assertEquals("COPPER_INGOT", row.get("material"), "material must be its NAME, not the enum");
        assertFalse(row.containsKey("display_name"), "an unset display name is omitted, not null");
        assertEquals(1.5, row.get("floor"));
        assertEquals(20.0, row.get("ceiling"));
        assertEquals(500L, row.get("initial_stock"));
        assertEquals(1000L, row.get("full_stock"));

        // Both caps are always written, a deliberate 0 included: the revision-4 migration
        // only fills a cap that is not already a number, so an omitted 0 would be replaced
        // by the shipped default on the next upgrade.
        assertEquals(20L, row.get("max_daily_sell"));
        assertEquals(0L, row.get("max_daily_buy"));
    }

    @Test
    void anExistingItemLoadsBackIntoADraftUnchanged() {
        MarketItem item = new MarketItem("wheat", Material.WHEAT, "Wheat",
                0.5, 8.0, 900, 2000, 120, 240);
        MarketDraft d = MarketDraft.from(item);

        assertEquals(java.util.List.of(), d.problems(Set.of()), "a shipped row must be valid");
        assertEquals(item.id(), d.id());
        assertEquals(item.material(), d.material());
        assertEquals(item.displayName(), d.displayName());
        assertEquals(item.floor(), d.floor());
        assertEquals(item.ceiling(), d.ceiling());
        assertEquals(item.initialStock(), d.initialStock());
        assertEquals(item.fullStock(), d.fullStock());
        assertEquals(item.maxDailySell(), d.maxDailySell());
        assertEquals(item.maxDailyBuy(), d.maxDailyBuy());
    }
}
