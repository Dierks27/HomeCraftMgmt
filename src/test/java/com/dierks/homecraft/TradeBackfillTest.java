package com.dierks.homecraft;

import com.dierks.homecraft.games.gen.LayoutFixtures;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code trade:} section (SHOP-V2-SPEC §4.14, §7.7) comes through the backfill, not a revision: an owner's
 * older file gains every key with its bundled comments (the section's header too), and a value the owner already
 * set is kept.
 */
class TradeBackfillTest {

    @Test
    void anOlderFileGetsTheSectionWithItsComments() {
        YamlConfiguration old = LayoutFixtures.v036();
        assertFalse(old.contains("trade"), "0.36 had no trade window");
        List<String> added = HomeCraftManagement.backfillConfig(old, LayoutFixtures.bundled());
        assertTrue(added.contains("trade.enabled") && added.contains("trade.refuse_items"), added.toString());
        YamlConfiguration after = LayoutFixtures.reread(old);
        assertTrue(after.getBoolean("trade.enabled"));
        assertEquals(30, after.getInt("trade.request_seconds"));
        assertEquals(List.of(), after.getStringList("trade.refuse_items"));
        assertTrue(String.join("\n", after.getComments("trade")).contains("---- The trade window (0.38)"),
                "the section's header comes with it: " + after.getComments("trade"));
        assertEquals(List.of("how long a request waits for an answer (10-600)"),
                after.getInlineComments("trade.request_seconds"));
    }

    @Test
    void theOwnersOwnValueIsKept() throws Exception {
        YamlConfiguration mine = LayoutFixtures.v036();
        mine.set("trade.money", false);
        HomeCraftManagement.backfillConfig(mine, LayoutFixtures.bundled());
        assertFalse(mine.getBoolean("trade.money"), "items only, as the owner set it");
        assertTrue(mine.getBoolean("trade.enabled"), "and the rest filled in");
    }
}
