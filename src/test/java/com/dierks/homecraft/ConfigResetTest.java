package com.dierks.homecraft;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code /hcm config reset}: only the allowed sections, a dry run that is dry, a reset that is exact. */
class ConfigResetTest {

    private static YamlConfiguration bundled() throws Exception {
        try (InputStream in = ConfigResetTest.class.getResourceAsStream("/config.yml");
             Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            YamlConfiguration c = new YamlConfiguration();
            c.load(r);
            return c;
        }
    }

    /** A live file where an earlier setup pass wrote its own Arcade and pack numbers. */
    private static YamlConfiguration edited() throws Exception {
        YamlConfiguration c = bundled();
        c.set("arcade.pity.tokens", 25);
        c.set("arcade.lotto.ticket_tokens", 5);
        c.set("arcade.quests.daily_draw", 5);
        c.set("arcade.quests.old_key", true);
        c.set("arcade.crates.arcade_crate.cost_tokens", null);
        c.set("packs", List.of());
        c.set("market.sell_limits.max_money_per_day", 1234);
        return c;
    }

    @Test
    void onlyTheAllowedSectionsCanBeReset() {
        for (String ok : List.of("arcade", "arcade.quests", "arcade.prizes", "ARCADE.Lotto", "games", "games.break",
                "games.ore_slots", "Games.Snake", "packs", "minis.loot.natural", "minis.effects", "clock")) {
            assertTrue(ConfigReset.allowed(ok), ok);
        }
        for (String no : List.of("market", "market.catalog", "minis", "minis.catalog", "minis.loot", "courier",
                "shipping", "arcade.", "games.", "gamesx", "", "packsx", "clock.time_zone.x", "config_revision")) {
            assertFalse(ConfigReset.allowed(no), no);
        }
    }

    @Test
    void aDryRunChangesNothing() throws Exception {
        YamlConfiguration c = edited();
        String before = c.saveToString();
        ConfigReset.Plan plan = ConfigReset.plan(c, bundled(), "arcade");
        assertEquals(before, c.saveToString(), "planning must not touch the file");
        assertTrue(plan.changed().containsKey("arcade.pity.tokens"));
        assertEquals(25, plan.changed().get("arcade.pity.tokens")[0]);
        assertTrue(plan.added().containsKey("arcade.crates.arcade_crate.cost_tokens"));
        assertTrue(plan.removed().containsKey("arcade.quests.old_key"));
    }

    @Test
    void aResetMatchesAFreshInstallForThatSectionOnly() throws Exception {
        YamlConfiguration fresh = bundled();
        for (String section : List.of("arcade", "packs")) {
            YamlConfiguration c = edited();
            ConfigReset.apply(c, fresh, section);
            assertTrue(ConfigReset.plan(c, fresh, section).none(), section + " still differs after a reset");
            assertEquals(1234, c.getInt("market.sell_limits.max_money_per_day"), "other sections are untouched");
        }
    }

    @Test
    void aChildResetLeavesItsSiblingsAlone() throws Exception {
        YamlConfiguration c = edited();
        ConfigReset.apply(c, bundled(), "arcade.lotto");
        assertEquals(10, c.getInt("arcade.lotto.ticket_tokens"));
        assertEquals(25, c.getInt("arcade.pity.tokens"), "arcade.pity was not asked for");
    }

    @Test
    void oneGamesBlockResetsAndLeavesTheOtherGamesAlone() throws Exception {
        YamlConfiguration fresh = bundled();
        YamlConfiguration c = bundled();
        c.set("games.ore_slots.reels.coal", 99);
        c.set("games.snake.tick_java", 9);
        ConfigReset.apply(c, fresh, "games.ore_slots");
        assertTrue(ConfigReset.plan(c, fresh, "games.ore_slots").none(), "games.ore_slots is back to shipped");
        assertEquals(10, c.getInt("games.ore_slots.reels.coal"));
        assertEquals(9, c.getInt("games.snake.tick_java"), "games.snake was not asked for");
    }

    @Test
    void theSectionKeepsItsPlaceAndItsComments() throws Exception {
        YamlConfiguration fresh = bundled();
        YamlConfiguration c = edited();
        List<String> order = List.copyOf(c.getKeys(false));
        ConfigReset.apply(c, fresh, "arcade");
        assertEquals(order, List.copyOf(c.getKeys(false)), "arcade: stays where it was in the file");
        assertEquals(fresh.getComments("arcade.pity"), c.getComments("arcade.pity"));
    }

    @Test
    void resettingTheQuestPoolsRedrawsQuests() {
        assertTrue(ConfigReset.touchesQuests("arcade"));
        assertTrue(ConfigReset.touchesQuests("arcade.quests"));
        assertTrue(ConfigReset.touchesQuests("arcade.quests.daily_pool"));
        assertFalse(ConfigReset.touchesQuests("arcade.prizes"));
        assertFalse(ConfigReset.touchesQuests("packs"));
    }
}
