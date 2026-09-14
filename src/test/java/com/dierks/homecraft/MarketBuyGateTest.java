package com.dierks.homecraft;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Buying from the house market must never be open to everyone.
 *
 * <p>This is a guard on the exact shape of a hole that was live: {@code hcm.market.order}
 * defaulted to {@code true} and granted "buy from and sell to the dynamic market", and
 * {@code /hcm} has no gate of its own — so any player could run {@code /hcm market buy} and
 * take goods at the live price with <b>no shipping cost and no wait</b>.
 *
 * <p>That is not a small leak. The store's whole shipping system — the tiers, the Locker,
 * in-transit orders — only has a reason to exist if goods coming to you take time and money to
 * arrive. A free instant alternative makes every tier content that is never chosen.
 *
 * <p>Selling is the asymmetry and stays open to everyone: you are the one delivering the
 * goods, so there is nothing to ship.
 *
 * <p>Registry-free: this reads the shipped {@code plugin.yml} as YAML, so it needs no server.
 */
class MarketBuyGateTest {

    private static YamlConfiguration pluginYml() throws IOException, InvalidConfigurationException {
        try (InputStream in = MarketBuyGateTest.class.getResourceAsStream("/plugin.yml")) {
            assertNotNull(in, "plugin.yml is missing from the test classpath");
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                YamlConfiguration yml = new YamlConfiguration();
                yml.load(reader);
                return yml;
            }
        }
    }

    /**
     * One permission node, read by its LITERAL name.
     *
     * <p>Not {@code get("permissions.hcm.market.buy.default")}: a permission node's name
     * contains dots, and Bukkit treats a dot as a path separator — so that would go looking
     * for a section {@code hcm} containing {@code market} containing {@code buy} and find
     * nothing. {@code getValues(false)} hands back the literal keys instead, which is the only
     * way to read a plugin.yml permission block without the name being torn apart.
     */
    private static Object node(YamlConfiguration yml, String permission, String field) {
        var perms = yml.getConfigurationSection("permissions");
        assertNotNull(perms, "plugin.yml declares no permissions at all");
        Object declared = perms.getValues(false).get(permission);
        if (declared instanceof org.bukkit.configuration.ConfigurationSection section) {
            return section.getValues(false).get(field);
        }
        if (declared instanceof java.util.Map<?, ?> map) {
            return map.get(field);
        }
        return null;
    }

    /** The node exists and is op-only. If this ever reads "true", the shipping tiers are dead. */
    @Test
    void buyingFromTheHouseMarketIsOpOnly() throws Exception {
        Object declared = node(pluginYml(), "hcm.market.buy", "default");
        assertNotNull(declared, "hcm.market.buy is not declared — without it /hcm market buy "
                + "falls back to hcm.market.order, which every player has");
        assertEquals("op", String.valueOf(declared).toLowerCase(java.util.Locale.ROOT),
                "hcm.market.buy must default to op. Open it up and any player can take goods "
                        + "at the live price with no shipping and no wait, which makes every "
                        + "shipping tier, the Locker and in-transit orders unreachable content");
    }

    /**
     * Selling stays open to everyone, and its description must not still claim it buys.
     *
     * <p>The description is what an admin reads when deciding who to grant the node to, so a
     * stale one is how the hole gets reopened by hand.
     */
    @Test
    void sellingStaysOpenAndItsDescriptionDoesNotPromiseBuying() throws Exception {
        YamlConfiguration yml = pluginYml();

        assertEquals("true", String.valueOf(node(yml, "hcm.market.order", "default")),
                "selling needs no shipping — the player is the one delivering — so it stays "
                        + "open to everyone");

        String description = String.valueOf(node(yml, "hcm.market.order", "description"))
                .toLowerCase(java.util.Locale.ROOT);
        assertTrue(description.contains("sell"),
                "hcm.market.order should say it is about selling");
        assertTrue(!description.contains("buy from"), "hcm.market.order still describes itself "
                + "as granting buying. It does not, and an admin reading that would reasonably "
                + "grant it expecting to hand out both");
    }
}
