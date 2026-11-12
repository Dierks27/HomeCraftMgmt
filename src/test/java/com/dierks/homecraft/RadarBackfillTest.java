package com.dierks.homecraft;

import com.dierks.homecraft.games.gen.LayoutFixtures;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code arcade.radar} (the Mini Radar toned down) comes through the backfill, not a revision: an owner's file from
 * before it gains every key with its comments (the section's header too), a value the owner already set is kept,
 * {@code config_revision} is never written, and the shipped {@code mini_radar} prize row is not touched.
 */
class RadarBackfillTest {

    private static final List<String> KEYS = List.of("interval_seconds", "lasts_seconds", "burning_under",
            "hot_under", "warm_under", "one_per_hunt");

    @Test
    void anOlderFileGetsTheSectionWithItsComments() {
        YamlConfiguration old = LayoutFixtures.v0381();
        assertFalse(old.contains("arcade.radar"), "0.38.1 had no radar settings");
        Object revision = old.get("config_revision");
        Object prizes = old.get("arcade.prizes");
        List<String> added = HomeCraftManagement.backfillConfig(old, LayoutFixtures.bundled());
        for (String key : KEYS) {
            assertTrue(added.contains("arcade.radar." + key), key + ": " + added);
        }
        assertFalse(added.contains("config_revision"), "no revision: the backfill alone");
        assertEquals(revision, old.get("config_revision"));
        assertEquals(prizes, old.get("arcade.prizes"), "the prize rows are as they were");

        YamlConfiguration after = LayoutFixtures.reread(old);
        assertEquals(6, after.getInt("arcade.radar.interval_seconds"));
        assertEquals(60, after.getInt("arcade.radar.lasts_seconds"));
        assertEquals(0, after.getInt("arcade.radar.burning_under"));
        assertEquals(32, after.getInt("arcade.radar.hot_under"));
        assertEquals(64, after.getInt("arcade.radar.warm_under"));
        assertTrue(after.getBoolean("arcade.radar.one_per_hunt"));
        assertTrue(String.join("\n", after.getComments("arcade.radar")).contains("The Mini Radar"),
                "the section's header comes with it: " + after.getComments("arcade.radar"));
        for (String key : KEYS) {
            assertEquals(1, after.getInlineComments("arcade.radar." + key).size(), key + "'s one-line comment");
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) (List<?>) after.getMapList("arcade.prizes");
        Map<String, Object> radar = rows.stream().filter(r -> "mini_radar".equals(r.get("id"))).findFirst()
                .orElseThrow();
        assertEquals(25, ((Number) radar.get("cost_tokens")).intValue(), "the radar's price is as it was");
    }

    @Test
    void theOwnersOwnValueIsKept() {
        YamlConfiguration mine = LayoutFixtures.v0381();
        mine.set("arcade.radar.lasts_seconds", 0);
        HomeCraftManagement.backfillConfig(mine, LayoutFixtures.bundled());
        assertEquals(0, mine.getInt("arcade.radar.lasts_seconds"), "until it's caught, as the owner set it");
        assertEquals(6, mine.getInt("arcade.radar.interval_seconds"), "and the rest filled in");
    }

    @Test
    void theRevisionDoesNotMove() {
        assertEquals(27, HomeCraftManagement.CONFIG_REVISION, "the radar rides the backfill, not a new revision"
                + " (26 is Thanksgiving's, ThanksgivingMigrationTest; 27 graves', GravesMigrationTest)");
    }
}
